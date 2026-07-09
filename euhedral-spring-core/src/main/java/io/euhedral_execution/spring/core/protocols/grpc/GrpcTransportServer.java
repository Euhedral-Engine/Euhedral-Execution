package io.euhedral_execution.spring.core.protocols.grpc;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.ingest.SingleUseSource;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.spring.core.frames.GrpcFrame;
import io.euhedral_execution.spring.core.frames.GrpcFrame.CommunicationMethod;
import io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceGrpc.GrpcTransportServiceImplBase;
import io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class GrpcTransportServer extends GrpcTransportServiceImplBase {

    private final ControlPlaneLattice controlPlane;

    public GrpcTransportServer(ControlPlaneLattice controlPlane) {
        this.controlPlane = controlPlane;
    }

    protected void processSingle(GrpcFrame frame) {
        this.controlPlane.addUpstream(SingleUseSource.wrap(frame));
    }

    protected void processStream(GrpcServerHandler serverHandler) {
        this.controlPlane.addUpstream(serverHandler);
    }

    @Override
    public void unaryMethod(GrpcMessage message,
            StreamObserver<GrpcMessage> responseObserver) {
        ServerCallStreamObserver<GrpcMessage> serverCallObserver = (ServerCallStreamObserver<GrpcMessage>) responseObserver;
        String connectionId = ConnectionIdInterceptor.CONN_ID_KEY.get();
        long idHash = HasherApi.getHash(connectionId);

        GrpcFrame frame = new GrpcFrame(idHash, message, CommunicationMethod.SINGLE_RESPONSE,
                serverCallObserver, null, null);
        processSingle(frame);
    }

    @Override
    public StreamObserver<GrpcMessage> clientStreamMethod(
            StreamObserver<GrpcMessage> responseObserver) {
        ServerCallStreamObserver<GrpcMessage> serverCallObserver = (ServerCallStreamObserver<GrpcMessage>) responseObserver;
        serverCallObserver.disableAutoInboundFlowControl();

        String connectionId = ConnectionIdInterceptor.CONN_ID_KEY.get();
        long idHash = HasherApi.getHash(connectionId);

        GrpcServerHandler serverHandler = new GrpcServerHandler(
                idHash, serverCallObserver, CommunicationMethod.CLIENT_STREAM);

        processStream(serverHandler);

        return serverHandler;
    }

    @Override
    public void serverStreamMethod(GrpcMessage request,
            StreamObserver<GrpcMessage> responseObserver) {
        ServerCallStreamObserver<GrpcMessage> serverCallObserver = (ServerCallStreamObserver<GrpcMessage>) responseObserver;
        serverCallObserver.disableAutoInboundFlowControl();

        String connectionId = ConnectionIdInterceptor.CONN_ID_KEY.get();
        long idHash = HasherApi.getHash(connectionId);

        GrpcServerHandler serverHandler = new GrpcServerHandler(
                idHash, serverCallObserver, CommunicationMethod.SERVER_STREAM);

        processStream(serverHandler);
    }

    @Override
    public StreamObserver<GrpcMessage> bidirectionalMethod(
            StreamObserver<GrpcMessage> responseObserver) {
        ServerCallStreamObserver<GrpcMessage> serverCallObserver = (ServerCallStreamObserver<GrpcMessage>) responseObserver;
        serverCallObserver.disableAutoInboundFlowControl();

        String connectionId = ConnectionIdInterceptor.CONN_ID_KEY.get();
        long idHash = HasherApi.getHash(connectionId);

        GrpcServerHandler serverHandler = new GrpcServerHandler(
                idHash, serverCallObserver, CommunicationMethod.BIDI);

        processStream(serverHandler);
        return serverHandler;
    }

}
