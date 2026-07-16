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
public abstract class ReactorGrpcTransportClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReactorGrpcTransportClient.class);

    private final GrpcTransportServiceGrpc.GrpcTransportServiceStub stub;
    private final int sendQueueChunkSize;

    protected ReactorGrpcTransportClient(GrpcTransportServiceGrpc.GrpcTransportServiceStub stub, int sendQueueChunkSize) {
        this.stub = stub;
        this.sendQueueChunkSize = sendQueueChunkSize;
    }

    public Mono<GrpcMessage> unaryRequest(GrpcMessage message) {
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler();

        return handler.doOnSubscribe(sub -> stub.unaryMethod(message, handler)).next();
    }

    public Mono<GrpcMessage> clientStream(Flux<GrpcMessage> messageFlux) {
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler(this.sendQueueChunkSize);

        stub.clientStreamMethod(handler);
        messageFlux.subscribe(handler.getSubscriber());

        return handler.next();
    }

    public Flux<GrpcMessage> serverStream(GrpcMessage message) {
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler();
        return handler.doOnSubscribe(sub -> stub.serverStreamMethod(message, handler));
    }

    public Flux<GrpcMessage> bidirectionalStream(Flux<GrpcMessage> messageFlux) {
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler(this.sendQueueChunkSize);

        stub.bidirectionalMethod(handler);
        messageFlux.subscribe(handler.getSubscriber());
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
                LOGGER.error("ReactorGrpcTransportClient improperly shut down!", e);
            }
        }
    }
}

