package test_impl;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.spring.core.frames.GrpcFrame;
import io.euhedral_execution.spring.core.transport.grpc.GrpcUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

public class GrpcExecutor extends AbstractExecutor {
    public static final String RECEIVED = "exec_received";

    public GrpcExecutor() {
        super(-1);
    }

    protected GrpcExecutor(int cpu) {
        super(cpu);
    }

    @Override
    public void execute(AbstractFrame frame) {
        if(frame instanceof GrpcFrame grpc) {
            Message<byte[]> decoded = GrpcUtils.toSpringMessage(grpc.getGrpcMessage());
            Message<byte[]> response = MessageBuilder.fromMessage(decoded).setHeader(RECEIVED, true).build();

            grpc.respond(GrpcUtils.toGrpc(response, grpc.isOrdered()));
        }
    }

    @Override
    public AbstractExecutor hookOnClone(int cpu) {
        return new GrpcExecutor(cpu);
    }
}
