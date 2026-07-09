package io.euhedral_execution.spring.core.protocols.grpc;

import io.euhedral_execution.spring.core.frames.GrpcFrame.CommunicationMethod;
import io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceGrpc;
import io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SuppressWarnings("unused")
public class GrpcTransportClient implements AutoCloseable {

    private final GrpcTransportServiceGrpc.GrpcTransportServiceStub stub;

    public GrpcTransportClient(GrpcTransportServiceGrpc.GrpcTransportServiceStub stub) {
        this.stub = stub;
    }

    public void sendSingle(GrpcMessage message) {
        sendSingleRespondSingle(message).then().subscribe();
    }

    public Mono<GrpcMessage> sendSingleRespondSingle(GrpcMessage message) {
        GrpcClientHandler interceptor = new GrpcClientHandler(
                CommunicationMethod.SINGLE_RESPONSE);
        return Mono.from(interceptor).doOnSubscribe(sub -> stub.unaryMethod(message, interceptor));
    }

    public Mono<Void> sendStream(Flux<GrpcMessage> messageFlux) {
        return sendStreamRespondSingle(messageFlux).then();
    }

    public Mono<GrpcMessage> sendStreamRespondSingle(Flux<GrpcMessage> messageFlux) {
        GrpcClientHandler interceptor = new GrpcClientHandler(
                CommunicationMethod.CLIENT_STREAM);
        return Mono.from(interceptor).doOnSubscribe(sub -> stub.clientStreamMethod(interceptor));
    }

    public Flux<GrpcMessage> sendSingleRespondStream(GrpcMessage message) {
        GrpcClientHandler interceptor = new GrpcClientHandler(
                CommunicationMethod.SERVER_STREAM);
        return interceptor.doOnSubscribe(sub -> stub.serverStreamMethod(message, interceptor));
    }

    public Flux<GrpcMessage> sendStreamRespondStream(Flux<GrpcMessage> messageFlux) {
        GrpcClientHandler interceptor = new GrpcClientHandler(CommunicationMethod.BIDI);
        return interceptor.doOnSubscribe(sub -> stub.bidirectionalMethod(interceptor));
    }

    @Override
    public void close() {
        shutdown();
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
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}

