package io.euhedral_execution.spring.core.transport.grpc;

import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceGrpc;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SuppressWarnings("unused")
public abstract class GrpcTransportClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(GrpcTransportClient.class);

    private final GrpcTransportServiceGrpc.GrpcTransportServiceStub stub;
    private final int sendQueueChunkSize;

    protected GrpcTransportClient(GrpcTransportServiceGrpc.GrpcTransportServiceStub stub, int sendQueueChunkSize) {
        this.stub = stub;
        this.sendQueueChunkSize = sendQueueChunkSize;
    }

    public void sendSingle(GrpcMessage message) {
        sendSingleRespondSingle(message).then().subscribe();
    }

    public Mono<GrpcMessage> sendSingleRespondSingle(GrpcMessage message) {
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler();

        return handler.doOnSubscribe(sub -> {
            stub.unaryMethod(message, handler);
            handler.request(1);
        }).next();
    }

    public Mono<Void> sendStream(Flux<GrpcMessage> messageFlux) {
        return sendStreamRespondSingle(messageFlux).then();
    }

    public Mono<GrpcMessage> sendStreamRespondSingle(Flux<GrpcMessage> messageFlux) {
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler(this.sendQueueChunkSize);

        stub.clientStreamMethod(handler);
        messageFlux.subscribe(handler.getSubscriber());

        return handler.next();
    }

    public Flux<GrpcMessage> sendSingleRespondStream(GrpcMessage message) {
        ReactorGrpcClientHandler interceptor = new ReactorGrpcClientHandler();
        return interceptor.doOnSubscribe(sub -> stub.serverStreamMethod(message, interceptor));
    }

    public Flux<GrpcMessage> sendStreamRespondStream(Flux<GrpcMessage> messageFlux) {
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler(this.sendQueueChunkSize);

        stub.bidirectionalMethod(handler);
        messageFlux.subscribeWith(handler.getSubscriber());
        return handler;
    }

    public void shutdown() {
        var channel = (ManagedChannel) stub.getChannel();
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.error("GrpcTransportClient improperly shut down!", e);
            }
        }
    }
}

