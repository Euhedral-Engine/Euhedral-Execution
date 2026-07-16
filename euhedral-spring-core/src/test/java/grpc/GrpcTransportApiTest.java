package grpc;

import grpc.GrpcTransportApiTest.TestConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.spring.core.transport.grpc.GrpcUtils;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceGrpc;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import test_impl.GrpcExecutor;
import test_impl.TestApplication;
import test_impl.TestGrpcClient;

@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.grpc.server.port=9090",
                "spring.cloud.config.enabled=false",
                "spring.main.lazy-initialization=true"
        }
)
@Import(TestConfig.class)
class GrpcTransportApiTest {

    static TestGrpcClient CLIENT;

    @BeforeAll
    static void setup() {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 9090)
                .usePlaintext()
                .build();
        GrpcTransportServiceGrpc.GrpcTransportServiceStub stub = GrpcTransportServiceGrpc.newStub(
                channel);

        CLIENT = new TestGrpcClient(stub, 4096);
    }

    @AfterAll
    static void teardown() {
        ControlPlaneLattice.getOrCreate().close();
    }

    @Test
    void testUnary() {
        Message<byte[]> single = MessageBuilder.withPayload(new byte[]{1, 2, 3, 4})
                .setHeader("TEST", 1).build();


        GrpcMessage message = GrpcUtils.toGrpc(single, false);
        GrpcMessage response = CLIENT.sendSingleRespondSingle(message).block();

        System.out.println(message + " " + response);
    }

    @TestConfiguration
    public static class TestConfig {

        @Bean
        public AbstractExecutor executor() {
            return new GrpcExecutor();
        }
    }
}