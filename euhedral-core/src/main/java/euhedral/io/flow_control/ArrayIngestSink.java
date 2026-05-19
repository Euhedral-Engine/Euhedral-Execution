package euhedral.io.flow_control;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.IngestSink;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

@SuppressWarnings({"unchecked", "unused"})
public class ArrayIngestSink implements IngestSink {

    private final Delegate delegate;

    public ArrayIngestSink(AbstractFrame[] frames) {
        this.delegate = new Delegate(frames);
    }

    public Publisher<AbstractFrame> getDelegate() {
        return this.delegate;
    }

    @Override
    public void close() {
        delegate.close();
    }

    protected static final class ArrayWrapper {

        int start;
        int end;
        AbstractFrame[] array;
    }

    private static class Delegate implements IngestSink.Delegate {

        private static final VarHandle SUBSCRIBER;

        static {
            try {
                SUBSCRIBER = MethodHandles.lookup()
                        .findVarHandle(Delegate.class, "subscriber", Subscriber.class);
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        private static long accumulate(long curr, long next) {
            if (curr + next < 0) {
                return Long.MAX_VALUE;
            }
            return curr + next;
        }

        private final PaddedAtomicLong demand = new PaddedAtomicLong(0);
        private final AbstractFrame[] array;
        int start;
        private Subscriber<? super AbstractFrame> subscriber;

        public Delegate(AbstractFrame[] array) {
            this.array = array;
        }

        @Override
        public void request(long demand) {
            var sub = (Subscriber<? super AbstractFrame>) SUBSCRIBER.getOpaque(this);
            if (sub == null || demand <= 0 || start >= array.length) {
                return;
            }
            demand = this.demand.accumulateAndGet(demand, Delegate::accumulate);
            int batch = (int) Math.min(demand, Integer.MAX_VALUE);

            while (start < batch && start < array.length) {
                sub.onNext(array[start++]);
            }
            VarHandle.releaseFence();
            if (start >= array.length) {
                close();
            }
        }

        @Override
        public void cancel() {
            close();
        }

        @Override
        public void subscribe(Subscriber<? super AbstractFrame> s) {
            if (SUBSCRIBER.compareAndSet(this, null, s)) {
                s.onSubscribe(this);
            } else {
                s.onError(new IllegalStateException("Already Subscribed"));
            }
        }

        @Override
        public void close() {
            var sub = (Subscriber<? super AbstractFrame>) SUBSCRIBER.getAndSet(this, null);
            this.demand.lazySet(0);
            if (sub != null) {
                sub.onComplete();
            }
        }
    }
}
