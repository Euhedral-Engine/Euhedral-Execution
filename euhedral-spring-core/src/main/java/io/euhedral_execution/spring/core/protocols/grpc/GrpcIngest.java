package io.euhedral_execution.spring.core.protocols.grpc;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.ingest.SingleUseSource;
import io.euhedral_execution.spring.core.frames.GrpcFrame;
import io.euhedral_execution.spring.core.protocols.grpc.base.GrpcServerInboundHandle;
import io.euhedral_execution.spring.core.protocols.grpc.base.GrpcTransportServer;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class GrpcIngest extends GrpcTransportServer {

    private final ControlPlaneLattice controlPlane;

    public GrpcIngest(ControlPlaneLattice controlPlane) {
        this.controlPlane = controlPlane;
    }

    @Override
    protected void processSingle(GrpcFrame frame) {
        controlPlane.addUpstream(SingleUseSource.wrap(frame));
    }

    @Override
    protected void processStream(GrpcServerInboundHandle inboundHandle) {
        this.controlPlane.addUpstream(inboundHandle);
    }
}
