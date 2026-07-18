package io.euhedral_execution.spring.core.transport.grpc;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.spring.core.frames.GrpcFrame.CommunicationMethod;
import io.euhedral_execution.spring.core.internal.Constants;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceGrpc;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public class EuhedralGrpcClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(EuhedralGrpcClient.class));

    private final GrpcTransportServiceGrpc.GrpcTransportServiceStub stub;
    private final int recycleCapacity;
    private final int sendQueueChunkSize;

    private final ControlPlaneLattice controlPlane;

    protected EuhedralGrpcClient(ControlPlaneLattice controlPlane,
            GrpcTransportServiceGrpc.GrpcTransportServiceStub stub, int recycleCapacity,
            int sendQueueChunkSize) {
        this.controlPlane = controlPlane;
        this.stub = stub;
        this.recycleCapacity = recycleCapacity;
        this.sendQueueChunkSize = sendQueueChunkSize;
    }

    public void unaryRequest(GrpcMessage message) {
        EuhedralGrpcClientHandler handler = new EuhedralGrpcClientHandler(
                CommunicationMethod.SINGLE_RESPONSE, 4, 4);

        this.stub.unaryMethod(message, handler);
        this.controlPlane.addUpstream(handler);
    }

    public ServerHandle clientStream() {
        EuhedralGrpcClientHandler handler = new EuhedralGrpcClientHandler(
                CommunicationMethod.CLIENT_STREAM, this.recycleCapacity, this.sendQueueChunkSize);

        this.stub.clientStreamMethod(handler);
        this.controlPlane.addUpstream(handler);

        return new ServerHandle(handler);
    }

    public void serverStream(GrpcMessage message) {
        EuhedralGrpcClientHandler handler = new EuhedralGrpcClientHandler(
                CommunicationMethod.SERVER_STREAM, this.recycleCapacity, this.sendQueueChunkSize);

        this.stub.serverStreamMethod(message, handler);
        this.controlPlane.addUpstream(handler);
    }

    public ServerHandle bidirectionalStream() {
        EuhedralGrpcClientHandler handler = new EuhedralGrpcClientHandler(CommunicationMethod.BIDI,
                this.recycleCapacity, this.sendQueueChunkSize);

        this.stub.bidirectionalMethod(handler);
        this.controlPlane.addUpstream(handler);

        return new ServerHandle(handler);
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
                LOGGER.error("ReactorGrpcClient improperly shut down!", e);
            }
        }
    }

    public static class ServerHandle {

        private final Consumer<GrpcMessage> sender;
        private final BiConsumer<String, Throwable> canceller;
        private final Consumer<Throwable> error;

        public ServerHandle(EuhedralGrpcClientHandler handler) {
            this.sender = handler::send;
            this.canceller = handler::cancel;
            this.error = handler::onError;
        }

        public void send(GrpcMessage message) {
            this.sender.accept(message);
        }

        public void cancel(String message, Throwable cause) {
            this.canceller.accept(message, cause);
        }

        public void onError(Throwable cause) {
            this.error.accept(cause);
        }
    }
}
