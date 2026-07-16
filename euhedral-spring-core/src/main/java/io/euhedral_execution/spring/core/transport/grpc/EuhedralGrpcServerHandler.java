package io.euhedral_execution.spring.core.transport.grpc;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameFactory.FrameCreate;
import io.euhedral_execution.core.impl.FrameFactory.FrameReplace;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.data_structures.queues.MpmcQueue;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.spring.core.frames.GrpcFrame;
import io.euhedral_execution.spring.core.frames.GrpcFrame.CommunicationMethod;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public class EuhedralGrpcServerHandler implements LatticeSource, StreamObserver<GrpcMessage> {

    private static final VarHandle COMPLETE;
    private static final VarHandle DOWNSTREAM;

    static {
        try {
            COMPLETE = MethodHandles.lookup()
                    .findVarHandle(EuhedralGrpcServerHandler.class, "complete", boolean.class);
            DOWNSTREAM = MethodHandles.lookup()
                    .findVarHandle(EuhedralGrpcServerHandler.class, "downstream",
                            LatticeReceiver.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static long addPending(long num1, long num2) {
        long sum = num1 + num2;
        return sum < 0 || sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : sum;
    }

    private final CallStreamObserver<GrpcMessage> client;
    private final CommunicationMethod method;
    private final long ingestPassword;
    private final FrameManager<GrpcMessage, GrpcFrame> manager;

    private final MpmcQueue<GrpcMessage> responseQueue;

    private final AtomicLong pending = new AtomicLong(0);

    private Runnable onCompleteCallback;

    private LatticeReceiver downstream = null;
    private boolean complete = false;

    private long seed = HasherApi.mix(ThreadLocalRandom.current().nextLong());

    public EuhedralGrpcServerHandler(ServerCallStreamObserver<GrpcMessage> client,
            CommunicationMethod method, int recycleCapacity, int responseQueueChunkSize) {
        this.client = client;
        this.method = method;
        this.ingestPassword = HasherApi.mix(ThreadLocalRandom.current().nextLong());
        this.manager = new FrameManager<>(recycleCapacity, ingestPassword);
        this.responseQueue = new MpmcQueue<>(responseQueueChunkSize);

        AtomicBoolean killSwitch = new AtomicBoolean();
        client.setOnCancelHandler(() -> {
            killSwitch.setRelease(true);
            complete();
        });
        client.setOnCloseHandler(this::complete);
        client.setOnReadyHandler(this::onReady);

        FrameCreate<GrpcMessage, GrpcFrame> create = (id, message) -> {
            GrpcFrame frame = new GrpcFrame(id, message, method, msg -> {
                this.responseQueue.offer(msg);
                onReady();
            }, this.manager, killSwitch);
            if (!message.getIsOrdered()) {
                frame.randomizeHash(this.seed++);
            }
            return frame;
        };
        FrameReplace<GrpcMessage, GrpcFrame> replace = (message, frame) -> {
            frame.replace(message);
            if (!message.getIsOrdered()) {
                frame.randomizeHash(this.seed++);
            }
        };
        this.manager.setFactory(new FrameFactory<>(create, replace));
    }

    @Override
    public void onNext(GrpcMessage message) {
        if (!canSend()) {
            return;
        }

        GrpcFrame frame = this.manager.getOrCreate(message, this.ingestPassword);
        this.downstream.push(frame);

        this.pending.decrementAndGet();
        if (this.method == CommunicationMethod.SERVER_STREAM
                || this.method == CommunicationMethod.SINGLE_RESPONSE) {
            complete();
        }
    }

    private void onReady() {
        while (this.client.isReady()) {
            if(this.responseQueue.drain(this.client::onNext, 32) == 0) {
                break;
            }
            if(this.method == CommunicationMethod.CLIENT_STREAM || this.method == CommunicationMethod.SINGLE_RESPONSE) {
                complete();
                break;
            }
        }
    }

    @Override
    public void request(long demand) {
        if (demand <= 0 || !canSend()) {
            return;
        }
        long pending = this.pending.getAcquire();

        int request = (int) Math.min(demand, Integer.MAX_VALUE - pending);
        if (request > 0) {
            this.pending.getAndAccumulate(demand, EuhedralGrpcServerHandler::addPending);
            this.client.request(request);
        }
    }

    @Override
    public void onCompleted() {
        if(this.onCompleteCallback != null) {
            this.onCompleteCallback.run();
        }
    }

    public void setOnCompleteHandler(Runnable runnable) {
        this.onCompleteCallback = runnable;
    }

    @Override
    public void complete() {
        if (COMPLETE.compareAndSet(this, false, true)) {
            LatticeReceiver receiver = (LatticeReceiver) DOWNSTREAM.getOpaque(this);
            if (receiver != null) {
                receiver.onComplete();
            }
            this.client.onCompleted();
        }
    }

    @Override
    public void addDownstream(LatticeReceiver downstream) {
        if (canSend() && !DOWNSTREAM.compareAndSet(this, null, downstream)) {
            downstream.onError(new IllegalAccessException("Only one downstream allowed."));
        }
    }

    private boolean canSend() {
        return !(boolean) COMPLETE.getAcquire(this);
    }

    @Override
    public long pull(Consumer<AbstractFrame> consumer, long demand) {
        return 0;
    }

    @Override
    public void onError(Throwable t) {
        if(COMPLETE.compareAndSet(this, false, true)) {
            LatticeReceiver receiver = (LatticeReceiver) DOWNSTREAM.getOpaque(this);
            if (receiver != null) {
                receiver.onError(t);
            }
        }
    }
}
