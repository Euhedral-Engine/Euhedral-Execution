package test_impl;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.spring.core.frames.GrpcFrame;
import io.euhedral_execution.spring.core.transport.grpc.GrpcUtils;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

public class GrpcExecutor extends AbstractExecutor {

    public static final String RECEIVED = "exec_received";
    public static final String RESPOND_THRESHOLD = "threshold";
    public static final String RESPOND_COUNT = "res_count";

    public static final String THROW = "throw_error";

    public static AtomicLong M_COUNT = new AtomicLong(0);

    public GrpcExecutor() {
        super(-1);
    }

    protected GrpcExecutor(int cpu) {
        super(cpu);
    }

    @Override
    public void execute(AbstractFrame frame) {
        if (frame instanceof GrpcFrame grpc) {
            Message<byte[]> decoded = GrpcUtils.toSpringMessage(grpc.getGrpcMessage());
            if (decoded.getHeaders().containsKey(THROW)) {
                throw new RuntimeException("Intentional Throw");
            }

            long count = M_COUNT.incrementAndGet();
            if (count >= decoded.getHeaders().get(RESPOND_THRESHOLD, Number.class).longValue()) {

                long rLimit = decoded.getHeaders().get(RESPOND_COUNT, Number.class).longValue();
                Message<byte[]> response = MessageBuilder.fromMessage(decoded)
                        .setHeader(RECEIVED, true).build();
                for (int i = 0; i < rLimit; i++) {
                    grpc.respond(GrpcUtils.toGrpc(response, grpc.isOrdered()));
                }
                grpc.complete();
            }

        }
    }

    @Override
    public AbstractExecutor hookOnClone(int cpu) {
        return new GrpcExecutor(cpu);
    }
}
