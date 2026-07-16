package test_impl;

import java.util.concurrent.atomic.AtomicLong;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.spring.core.frames.GrpcFrame;
import io.euhedral_execution.spring.core.transport.grpc.GrpcUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

public class GrpcExecutor extends AbstractExecutor {
    public static final String RECEIVED = "exec_received";
    public static final String THRESHOLD = "threshold";
    public static AtomicLong M_COUNT = new AtomicLong(0);

    public GrpcExecutor() {
        super(-1);
    }

    protected GrpcExecutor(int cpu) {
        super(cpu);
    }

    @Override
    public void execute(AbstractFrame frame) {
        if(frame instanceof GrpcFrame grpc) {
            long count = M_COUNT.incrementAndGet();
            Message<byte[]> decoded = GrpcUtils.toSpringMessage(grpc.getGrpcMessage());
            if(count >= decoded.getHeaders().get(THRESHOLD, Number.class).longValue()) {
                Message<byte[]> response = MessageBuilder.fromMessage(decoded).setHeader(RECEIVED, true).build();
                grpc.respond(GrpcUtils.toGrpc(response, grpc.isOrdered()));
            }

        }
    }

    @Override
    public AbstractExecutor hookOnClone(int cpu) {
        return new GrpcExecutor(cpu);
    }
}
