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
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public class EuhedralGrpcClientHandler implements LatticeSource,
        ClientResponseObserver<GrpcMessage, GrpcMessage> {

    private static final VarHandle COMPLETE;
    private static final VarHandle DOWNSTREAM;

    static {
        try {
            COMPLETE = MethodHandles.lookup()
                    .findVarHandle(EuhedralGrpcClientHandler.class, "complete", boolean.class);
            DOWNSTREAM = MethodHandles.lookup()
                    .findVarHandle(EuhedralGrpcClientHandler.class, "downstream",
                            LatticeReceiver.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static long addPending(long num1, long num2) {
        long sum = num1 + num2;
        return sum < 0 || sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : sum;
    }

    private final FrameManager<GrpcMessage, GrpcFrame> manager;
    private final MpmcQueue<GrpcMessage> sendQueue;
    private final CommunicationMethod method;
    private final long ingestPassword;

    private final AtomicLong pending = new AtomicLong(0);

    private ClientCallStreamObserver<GrpcMessage> upstream;
    private LatticeReceiver downstream = null;
    private boolean complete = false;

    private long seed = HasherApi.mix(ThreadLocalRandom.current().nextLong());

    public EuhedralGrpcClientHandler(CommunicationMethod method, int recycleCapacity,
            int sendQueueChunkSize) {
        this.ingestPassword = HasherApi.mix(ThreadLocalRandom.current().nextLong());
        this.manager = new FrameManager<>(recycleCapacity, ingestPassword);
        this.sendQueue = new MpmcQueue<>(sendQueueChunkSize);
        this.method = method;
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<GrpcMessage> stream) {
        stream.disableAutoRequestWithInitial(0);

        AtomicBoolean killSwitch = new AtomicBoolean();
        stream.setOnReadyHandler(this::onReady);

        FrameCreate<GrpcMessage, GrpcFrame> create = (id, message) -> {
            GrpcFrame frame = new GrpcFrame(id, message, method, msg -> {
                this.sendQueue.offer(msg);
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
        this.upstream = stream;
    }

    private void onReady() {
        while (this.upstream.isReady()) {
            if(this.sendQueue.drain(this.upstream::onNext, 32) == 0) {
                break;
            }
        }
    }

    @Override
    public void request(long demand) {
        if (demand <= 0 || !isOpen()) {
            return;
        }
        long pending = this.pending.getAcquire();

        int request = (int) Math.min(demand, Integer.MAX_VALUE - pending);
        if (request > 0) {
            this.pending.getAndAccumulate(demand, EuhedralGrpcClientHandler::addPending);
            this.upstream.request(request);
        }
    }

    @Override
    public void onNext(GrpcMessage message) {
        LatticeReceiver receiver = (LatticeReceiver) DOWNSTREAM.getOpaque(this);
        if (receiver != null) {
            GrpcFrame frame = this.manager.getOrCreate(message, this.ingestPassword);
            receiver.push(frame);
        }
    }

    @Override
    public void onCompleted() {
        complete();
    }

    @Override
    public void complete() {
        if (COMPLETE.compareAndSet(this, false, true)) {
            LatticeReceiver receiver = (LatticeReceiver) DOWNSTREAM.getAndSetRelease(this, null);
            if (receiver != null) {
                receiver.onComplete();
            }
        }
    }

    @Override
    public void addDownstream(LatticeReceiver downstream) {
        if (isOpen() && !DOWNSTREAM.compareAndSet(this, null, downstream)) {
            downstream.onError(new IllegalAccessException("Only one downstream allowed."));
        }
    }

    public boolean isOpen() {
        return !(boolean) COMPLETE.getAcquire(this);
    }

    @Override
    public long pull(Consumer<AbstractFrame> consumer, long demand) {
        return 0;
    }

    @Override
    public void onError(Throwable t) {
        if (COMPLETE.compareAndSet(this, false, true)) {
            LatticeReceiver receiver = (LatticeReceiver) DOWNSTREAM.getAndSetRelease(this, null);
            if (receiver != null) {
                receiver.onError(t);
            }
        }
    }
}
