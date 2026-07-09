package io.euhedral_execution.spring.core.protocols.grpc;

import io.euhedral_execution.spring.core.frames.GrpcFrame.CommunicationMethod;
import io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;

public class GrpcClientHandler extends Flux<GrpcMessage> implements
        ClientResponseObserver<GrpcMessage, GrpcMessage>, Subscription {

    private final CommunicationMethod method;
    private final AtomicInteger wip = new AtomicInteger(0);
    private final AtomicLong demand = new AtomicLong(0);
    private final AtomicLong pending = new AtomicLong(0);

    private final int demandLowWaterMark;
    private final int maxRequest;

    private volatile CoreSubscriber<? super GrpcMessage> downstream;

    private volatile ClientCallStreamObserver<GrpcMessage> clientCallStreamObserver;
    private volatile boolean ready = false;
    private volatile boolean complete = false;
    private volatile boolean cancelled = false;

    public GrpcClientHandler(CommunicationMethod method) {
        this(method, 4096, 256);
    }

    public GrpcClientHandler(CommunicationMethod method, int maxRequest, int demandLowWaterMark) {
        this.method = method;
        this.demandLowWaterMark = demandLowWaterMark;
        this.maxRequest = maxRequest;
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<GrpcMessage> clientCallStreamObserver) {
        this.clientCallStreamObserver = clientCallStreamObserver;
        clientCallStreamObserver.setOnReadyHandler(() -> {
            ready = true;
            if(demand.get() > 0) {
                drainDemand();
            }
        });
        ready = clientCallStreamObserver.isReady();
    }

    @Override
    public void subscribe(@NonNull CoreSubscriber<? super GrpcMessage> coreSubscriber) {
        if (this.downstream == null) {
            this.downstream = coreSubscriber;
            coreSubscriber.onSubscribe(this);
        } else {
            coreSubscriber.onError(
                    new IllegalAccessException("This class can only have 1 subscriber"));
        }
    }

    @Override
    public void onNext(GrpcMessage message) {
        if (cancelled || complete) {
            return;
        }

        this.downstream.onNext(message);
        if (this.method == CommunicationMethod.SINGLE_RESPONSE
                || this.method == CommunicationMethod.CLIENT_STREAM) {
            onCompleted();
            return;
        }
        long pending = this.pending.decrementAndGet();
        if(pending <= demandLowWaterMark && this.demand.get() > 0) {
            drainDemand();
        }
    }

    @Override
    public void onCompleted() {
        if (!cancelled && !complete) {
            complete = true;
            downstream.onComplete();
        }
    }

    @Override
    public void onError(Throwable throwable) {
        downstream.onError(throwable);
    }

    @Override
    public void request(long demand) {
        if (demand <= 0 || cancelled || complete) {
            return;
        }
        this.demand.accumulateAndGet(demand, GrpcClientHandler::addDemand);

        if (ready && pending.get() <= demandLowWaterMark) {
            drainDemand();
        }
    }

    private static long addDemand(long num1, long num2) {
        if (num1 < 0 || num2 < 0) {
            return Long.MAX_VALUE;
        }
        long sum = num1 + num2;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    private void drainDemand() {
        if(!wip.compareAndSet(0, 1)) {
            return;
        }
        try {
            long demand = this.demand.get();
            if (demand == Long.MAX_VALUE) {
                clientCallStreamObserver.request(maxRequest);
                pending.addAndGet(maxRequest);
            } else {
                int request = (int) Math.min(demand, maxRequest);
                this.demand.addAndGet(-request);
                this.pending.addAndGet(request);
                clientCallStreamObserver.request(request);
            }
        } finally {
            wip.set(0);
        }
    }

    @Override
    public void cancel() {
        if(!cancelled) {
            cancelled = true;
            clientCallStreamObserver.cancel("Client has cancelled their subscription",
                    new CancellationException("Client has cancelled their subscription"));
        }
    }
}
