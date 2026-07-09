package io.euhedral_execution.spring.core.protocols.grpc.base;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameFactory.FrameCreate;
import io.euhedral_execution.core.impl.FrameFactory.FrameReplace;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.spring.core.frames.GrpcFrame;
import io.euhedral_execution.spring.core.frames.GrpcFrame.CommunicationMethod;
import io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.euhedral_execution.spring.core.utils.KillSwitch;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.reactivestreams.Subscription;

public class GrpcServerInboundHandle implements LatticeSource, StreamObserver<GrpcMessage>,
        Subscription {

    private static final VarHandle CANCELLED;
    private static final VarHandle COMPLETE;
    private static final VarHandle DOWNSTREAM;

    static {
        try {
            CANCELLED = MethodHandles.lookup()
                    .findVarHandle(GrpcServerInboundHandle.class, "cancelled", boolean.class);
            COMPLETE = MethodHandles.lookup()
                    .findVarHandle(GrpcServerInboundHandle.class, "complete", boolean.class);
            DOWNSTREAM = MethodHandles.lookup()
                    .findVarHandle(GrpcServerInboundHandle.class, "downstream",
                            LatticeReceiver.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private static long addPending(long num1, long num2) {
        long sum = num1 + num2;
        return sum < 0 || sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : sum;
    }
    private final ServerCallStreamObserver<GrpcMessage> client;
    private final CommunicationMethod method;
    private final FrameManager<GrpcMessage, GrpcFrame> manager;
    private final long ingestPassword;
    private final AtomicLong pending = new AtomicLong(0);
    private LatticeReceiver downstream = null;
    private boolean complete = false;
    private boolean cancelled = false;

    private long seed = ThreadLocalRandom.current().nextLong();

    public GrpcServerInboundHandle(long idHash, ServerCallStreamObserver<GrpcMessage> client,
            CommunicationMethod method) {
        this.client = client;
        this.method = method;
        this.ingestPassword = HasherApi.combine(ThreadLocalRandom.current().nextLong(), idHash);
        this.manager = new FrameManager<>(8_192, ingestPassword);

        KillSwitch killSwitch = new KillSwitch();
        client.setOnCancelHandler(() -> {
            killSwitch.boop();
            complete();
        });


        FrameCreate<GrpcMessage, GrpcFrame> create = (id, message) -> {
            GrpcFrame frame = new GrpcFrame(id, message, method, client, this.manager, killSwitch);
            if(!message.getIsOrdered()) {
                frame.randomizeHash(this.seed++);
            }
            return frame;
        };
        FrameReplace<GrpcMessage, GrpcFrame> replace = (message, frame) -> {
            frame.replace(message);
            if(!message.getIsOrdered()) {
                frame.randomizeHash(this.seed++);
            }
        };
        manager.setFactory(new FrameFactory<>(create, replace));
    }

    public boolean isOpen() {
        return !(boolean) CANCELLED.getAcquire(this) && !(boolean) COMPLETE.getAcquire(this);
    }

    @Override
    public void onNext(GrpcMessage message) {
        if (!isOpen()) {
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

    @Override
    public void onError(Throwable throwable) {
        this.downstream.onError(throwable);
    }

    @Override
    public void request(long demand) {
        if (demand <= 0 || !isOpen()) {
            return;
        }
        long pending = this.pending.getAcquire();

        int request = (int) Math.min(demand, Integer.MAX_VALUE - pending);
        if (request > 0) {
            this.pending.getAndAccumulate(demand, GrpcServerInboundHandle::addPending);
            this.client.request(request);
        }
    }

    @Override
    public void onCompleted() {
        complete();
    }

    @Override
    public void complete() {
        if (COMPLETE.compareAndSet(this, false, true)) {
            downstream.onComplete();
        }
    }

    @Override
    public void addDownstream(LatticeReceiver downstream) {
        if (isOpen() && !DOWNSTREAM.compareAndSet(this, null, downstream)) {
            downstream.onError(new IllegalAccessException("Only one downstream allowed."));
        }
    }

    @Override
    public long pull(Consumer<AbstractFrame> consumer, long demand) {
        return 0;
    }

    @Override
    public void cancel() {
        if (isOpen() && CANCELLED.compareAndSet(this, false, true)) {
            this.client.onError(new RuntimeException("Server has cancelled"));
        }
    }
}
