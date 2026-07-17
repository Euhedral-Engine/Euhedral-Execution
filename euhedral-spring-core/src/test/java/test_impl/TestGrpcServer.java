package test_impl;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.spring.core.transport.grpc.EuhedralGrpcServer;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class TestGrpcServer extends EuhedralGrpcServer {

    public TestGrpcServer(ControlPlaneLattice controlPlane) {
        super(controlPlane, 1024, 4096);
    }
}
