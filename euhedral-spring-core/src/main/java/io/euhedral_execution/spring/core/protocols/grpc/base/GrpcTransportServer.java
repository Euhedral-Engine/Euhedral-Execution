package io.euhedral_execution.spring.core.protocols.grpc.base;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.spring.core.frames.GrpcFrame;
import io.euhedral_execution.spring.core.frames.GrpcFrame.CommunicationMethod;
import io.euhedral_execution.spring.core.protocols.grpc.ConnectionIdInterceptor;
import io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceGrpc.GrpcTransportServiceImplBase;
import io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

public abstract class GrpcTransportServer extends GrpcTransportServiceImplBase {

    protected abstract void processSingle(GrpcFrame frame);

    protected abstract void processStream(GrpcServerInboundHandle inboundHandle);

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

        GrpcServerInboundHandle inboundHandle = new GrpcServerInboundHandle(
                idHash, serverCallObserver, CommunicationMethod.CLIENT_STREAM);

        processStream(inboundHandle);

        return inboundHandle;
    }

    @Override
    public void serverStreamMethod(GrpcMessage request,
            StreamObserver<GrpcMessage> responseObserver) {
        ServerCallStreamObserver<GrpcMessage> serverCallObserver = (ServerCallStreamObserver<GrpcMessage>) responseObserver;
        serverCallObserver.disableAutoInboundFlowControl();

        String connectionId = ConnectionIdInterceptor.CONN_ID_KEY.get();
        long idHash = HasherApi.getHash(connectionId);

        GrpcServerInboundHandle inboundHandle = new GrpcServerInboundHandle(
                idHash, serverCallObserver, CommunicationMethod.SERVER_STREAM);

        processStream(inboundHandle);
    }

    @Override
    public StreamObserver<GrpcMessage> bidirectionalMethod(
            StreamObserver<GrpcMessage> responseObserver) {
        ServerCallStreamObserver<GrpcMessage> serverCallObserver = (ServerCallStreamObserver<GrpcMessage>) responseObserver;
        serverCallObserver.disableAutoInboundFlowControl();

        String connectionId = ConnectionIdInterceptor.CONN_ID_KEY.get();
        long idHash = HasherApi.getHash(connectionId);

        GrpcServerInboundHandle inboundHandle = new GrpcServerInboundHandle(
                idHash, serverCallObserver, CommunicationMethod.BIDI);

        processStream(inboundHandle);
        return inboundHandle;
    }

}
