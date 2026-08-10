package io.euhedral_execution.spring.core.transport.grpc;

import io.euhedral_execution.core.utils.CommonVarHandles;
import io.euhedral_execution.data_structures.queues.SpmcQueue;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;

@SuppressWarnings({"unchecked", "unused"})
public class ReactorGrpcClientHandler extends Flux<GrpcMessage>
        implements ClientResponseObserver<GrpcMessage, GrpcMessage>, Subscription {

    private static final VarHandle COMPLETE =
            CommonVarHandles.complete(MethodHandles.lookup(), ReactorGrpcClientHandler.class);
    private static final VarHandle DOWNSTREAM = CommonVarHandles.makeHandle(
            MethodHandles.lookup(), ReactorGrpcClientHandler.class, "downstream", CoreSubscriber.class);
    private static final VarHandle UNLIMITED = CommonVarHandles.makeHandle(
            MethodHandles.lookup(), ReactorGrpcClientHandler.class, "unlimited", boolean.class);
    private final boolean createSubscriber;
    private final int sendQueueChunkSize;
    private final AtomicLong demand = new AtomicLong(0);
    private ClientCallStreamObserver<GrpcMessage> upstream;
    private CoreSubscriber<? super GrpcMessage> downstream;
    private boolean complete = false;
    private boolean unlimited = false;

    @Getter
    private GrpcSubscriber subscriber;

    public ReactorGrpcClientHandler() {
        this(0, false);
    }

    public ReactorGrpcClientHandler(int sendQueueChunkSize) {
        this(sendQueueChunkSize, sendQueueChunkSize > 0);
    }

    ReactorGrpcClientHandler(int sendQueueChunkSize, boolean createSubscriber) {
        this.createSubscriber = createSubscriber;
        this.sendQueueChunkSize = sendQueueChunkSize;
    }

    private static long addCap(long num1, long num2) {
        long sum = num1 + num2;
        return sum < 0 || sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : sum;
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<GrpcMessage> stream) {
        stream.disableAutoRequestWithInitial(0);
        this.upstream = stream;

        if (this.createSubscriber) {
            this.subscriber = new GrpcSubscriber(stream, this.sendQueueChunkSize);
        }
    }

    @Override
    public void subscribe(@NonNull CoreSubscriber<? super GrpcMessage> downstream) {
        if (!isOpen()) {
            Operators.complete(downstream);
            return;
        }

        downstream.onSubscribe(this);
        if (!DOWNSTREAM.compareAndSet(this, null, downstream)) {
            downstream.onError(new IllegalAccessException("This instance has a subscriber."));
            return;
        }

        if (!isOpen() && DOWNSTREAM.compareAndSet(this, downstream, null)) {
            downstream.onComplete();
        }
    }

    @Override
    public void onNext(GrpcMessage message) {
        if (!isOpen()) {
            return;
        }

        CoreSubscriber<? super GrpcMessage> downstream =
                (CoreSubscriber<? super GrpcMessage>) DOWNSTREAM.getAcquire(this);
        if (downstream != null) {
            downstream.onNext(message);

            long demand = this.demand.decrementAndGet();
            if (demand < 8_192 && (boolean) UNLIMITED.getOpaque(this)) {
                request(Integer.MAX_VALUE);
            }
        }
    }

    @Override
    public void request(long demand) {
        if (demand <= 0 || !isOpen()) {
            return;
        }
        if (demand == Long.MAX_VALUE) {
            UNLIMITED.setVolatile(this, true);
        }

        long pending = this.demand.getAcquire();

        int request = (int) Math.min(demand, Integer.MAX_VALUE - pending);
        if (request > 0) {
            this.demand.getAndAccumulate(demand, ReactorGrpcClientHandler::addCap);
            if (this.upstream != null) {
                this.upstream.request(request);
            }
            if (this.subscriber != null) {
                this.subscriber.request(demand);
            }
        }
    }

    public boolean isOpen() {
        return !(boolean) COMPLETE.getAcquire(this);
    }

    @Override
    public void onCompleted() {
        if (COMPLETE.compareAndSet(this, false, true)) {
            CoreSubscriber<? super GrpcMessage> downstream =
                    (CoreSubscriber<? super GrpcMessage>) DOWNSTREAM.getAndSetRelease(this, null);
            if (downstream != null) {
                downstream.onComplete();
            }
        }
    }

    @Override
    public void onError(Throwable t) {
        if (COMPLETE.compareAndSet(this, false, true)) {
            CoreSubscriber<? super GrpcMessage> downstream =
                    (CoreSubscriber<? super GrpcMessage>) DOWNSTREAM.getAndSetRelease(this, null);
            if (downstream != null) {
                downstream.onError(t);
            }
        }
    }

    @Override
    public void cancel() {
        if (COMPLETE.compareAndSet(this, false, true)) {
            CoreSubscriber<? super GrpcMessage> downstream =
                    (CoreSubscriber<? super GrpcMessage>) DOWNSTREAM.getAndSetRelease(this, null);
            if (downstream != null) {
                downstream.onComplete();
            }
        }
    }

    public static class GrpcSubscriber implements CoreSubscriber<GrpcMessage> {

        private static final VarHandle COMPLETE =
                CommonVarHandles.complete(MethodHandles.lookup(), GrpcSubscriber.class);
        private static final VarHandle EMPTY =
                CommonVarHandles.makeHandle(MethodHandles.lookup(), GrpcSubscriber.class, "empty", boolean.class);

        private final ClientCallStreamObserver<GrpcMessage> upstream;
        private final SpmcQueue<GrpcMessage> sendQueue;

        private Subscription subscription;

        private boolean empty = false;
        private boolean complete = false;

        public GrpcSubscriber(ClientCallStreamObserver<GrpcMessage> upstream, int sendQueueChunkSize) {
            this.upstream = upstream;
            this.sendQueue = new SpmcQueue<>(sendQueueChunkSize);
            this.upstream.setOnReadyHandler(this::onReady);
        }

        void request(long demand) {
            Subscription current = this.subscription;
            if (current != null) {
                current.request(demand);
            }
        }

        void onReady() {
            boolean drained = false;
            while (this.upstream.isReady()) {
                if (this.sendQueue.drain(this.upstream::onNext, 32) == 0) {
                    drained = true;
                    break;
                }
            }
            if (drained && (boolean) EMPTY.getAcquire(this) && COMPLETE.compareAndSet(this, false, true)) {
                try {
                    this.upstream.onCompleted();
                } catch (Exception e) {
                    // Ignore complete() failures.
                }
            }
        }

        @Override
        public void onSubscribe(@NonNull Subscription sub) {
            if (this.subscription != null) {
                sub.cancel();
            } else {
                this.subscription = sub;
            }
        }

        @Override
        public void onNext(GrpcMessage message) {
            if (!(boolean) COMPLETE.getAcquire(this)) {
                this.sendQueue.offer(message);
                onReady();
            }
        }

        @Override
        public void onError(Throwable t) {
            if (COMPLETE.compareAndSet(this, false, true)) {
                this.sendQueue.clear();
                this.upstream.onError(t);
            }
        }

        @Override
        public void onComplete() {
            EMPTY.setRelease(this, true);
            onReady();
        }
    }
}
