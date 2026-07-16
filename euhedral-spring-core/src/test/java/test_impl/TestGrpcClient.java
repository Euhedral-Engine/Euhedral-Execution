package test_impl;

import io.euhedral_execution.spring.core.transport.grpc.ReactorGrpcTransportClient;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceGrpc.GrpcTransportServiceStub;

public class TestGrpcClient extends ReactorGrpcTransportClient {

    public TestGrpcClient(GrpcTransportServiceStub stub, int sendQueueChunkSize) {
        super(stub, sendQueueChunkSize);
    }
}
