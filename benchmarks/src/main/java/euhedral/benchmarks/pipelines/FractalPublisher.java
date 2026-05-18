package euhedral.benchmarks.pipelines;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import euhedral.atomics.PaddedLongAdder;
import euhedral.benchmarks.frames.FractalFrame;
import euhedral.io.frames.AbstractFrame;
import lombok.Getter;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class FractalPublisher implements Publisher<AbstractFrame>, Subscription {
    private static final VarHandle COMPLETE;

    static {
        try {
            COMPLETE = MethodHandles.lookup().findVarHandle(FractalPublisher.class, "complete", boolean.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Getter
    private final FractalFrame[] pixels;
    private final int start;
    private final int end;

    private long seed = ThreadLocalRandom.current().nextLong();

    private Subscriber<? super AbstractFrame> subscriber;
    private int internalIter = 0;

    private boolean complete = false;

    public FractalPublisher(FractalFrame[] frames, int start, int end) {
        this.pixels = frames;
        this.start = start;
        this.end = end;
        this.internalIter = start;
    }

    public void reset() {
        this.internalIter = this.start;
        COMPLETE.setRelease(this, false);
    }

    @Override
    public void request(long demand) {
        if (demand <= 0 || internalIter >= this.end || (boolean) COMPLETE.getOpaque(this)) {
            return;
        }

        for (int i = 0; i < demand && internalIter < this.end; i++) {
            FractalFrame f = this.pixels[this.internalIter++];
            f.randomizeHash(++this.seed);
            subscriber.onNext(f);
        }

        if (this.internalIter >= this.end) {
            this.subscriber.onComplete();
            COMPLETE.setRelease(this, true);
            this.subscriber = null;
        }
    }

    @Override
    public void cancel() {

    }

    @Override
    public void subscribe(Subscriber<? super AbstractFrame> subscriber) {
        this.subscriber = subscriber;
        subscriber.onSubscribe(this);
    }
}
