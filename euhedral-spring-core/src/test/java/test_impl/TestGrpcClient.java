package test_impl;

import io.euhedral_execution.spring.core.transport.grpc.ReactorGrpcClient;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceGrpc.GrpcTransportServiceStub;

public class TestGrpcClient extends ReactorGrpcClient {

    public TestGrpcClient(GrpcTransportServiceStub stub, int sendQueueChunkSize) {
        super(stub, sendQueueChunkSize);
    }
}
