package io.euhedral_execution.spring.core.transport.grpc;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.ingest.SingleUseSource;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.spring.core.frames.GrpcFrame;
import io.euhedral_execution.spring.core.frames.GrpcFrame.CommunicationMethod;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceGrpc.GrpcTransportServiceImplBase;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ThreadLocalRandom;

public abstract class GrpcTransportServer extends GrpcTransportServiceImplBase {

    private final ControlPlaneLattice controlPlane;
    private final int recycleCapacity;
    private final int responseQueueChunkSize;

    protected GrpcTransportServer(ControlPlaneLattice controlPlane, int recycleCapacity,
            int responseQueueChunkSize) {
        this.controlPlane = controlPlane;
        this.recycleCapacity = recycleCapacity;
        this.responseQueueChunkSize = responseQueueChunkSize;
    }

    protected void processSingle(GrpcFrame frame) {
        this.controlPlane.addUpstream(SingleUseSource.wrap(frame));
    }

    protected void processStream(EuhedralGrpcServerHandler serverHandler) {
        this.controlPlane.addUpstream(serverHandler);
    }

    @Override
    public void unaryMethod(GrpcMessage message,
            StreamObserver<GrpcMessage> responseObserver) {
        long idHash = HasherApi.mix(ThreadLocalRandom.current().nextLong());
        ServerCallStreamObserver<GrpcMessage> serverCallObserver = (ServerCallStreamObserver<GrpcMessage>) responseObserver;

        GrpcFrame frame = new GrpcFrame(idHash, message, CommunicationMethod.SINGLE_RESPONSE,
                msg -> {
            System.out.println("Ready: {}" + serverCallObserver.isReady());
            serverCallObserver.onNext(msg);
                }, null, null);
        processSingle(frame);
    }

    @Override
    public StreamObserver<GrpcMessage> clientStreamMethod(
            StreamObserver<GrpcMessage> responseObserver) {
        ServerCallStreamObserver<GrpcMessage> serverCallObserver = (ServerCallStreamObserver<GrpcMessage>) responseObserver;
        serverCallObserver.disableAutoInboundFlowControl();

        EuhedralGrpcServerHandler serverHandler = new EuhedralGrpcServerHandler(
                serverCallObserver, CommunicationMethod.CLIENT_STREAM, this.recycleCapacity,
                this.responseQueueChunkSize);

        processStream(serverHandler);

        return serverHandler;
    }

    @Override
    public void serverStreamMethod(GrpcMessage request,
            StreamObserver<GrpcMessage> responseObserver) {
        ServerCallStreamObserver<GrpcMessage> serverCallObserver = (ServerCallStreamObserver<GrpcMessage>) responseObserver;
        serverCallObserver.disableAutoInboundFlowControl();

        EuhedralGrpcServerHandler serverHandler = new EuhedralGrpcServerHandler(
                serverCallObserver, CommunicationMethod.SERVER_STREAM, this.recycleCapacity,
                this.responseQueueChunkSize);

        processStream(serverHandler);
    }

    @Override
    public StreamObserver<GrpcMessage> bidirectionalMethod(
            StreamObserver<GrpcMessage> responseObserver) {
        ServerCallStreamObserver<GrpcMessage> serverCallObserver = (ServerCallStreamObserver<GrpcMessage>) responseObserver;
        serverCallObserver.disableAutoInboundFlowControl();

        EuhedralGrpcServerHandler serverHandler = new EuhedralGrpcServerHandler(
                serverCallObserver, CommunicationMethod.BIDI, this.recycleCapacity,
                this.responseQueueChunkSize);

        processStream(serverHandler);
        return serverHandler;
    }
}
