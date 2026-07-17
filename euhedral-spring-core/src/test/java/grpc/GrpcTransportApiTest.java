package grpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import grpc.GrpcTransportApiTest.TestConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.spring.core.transport.grpc.GrpcUtils;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceGrpc;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Flux;
import test_impl.GrpcExecutor;
import test_impl.TestApplication;
import test_impl.TestGrpcClient;

@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.grpc.server.port=0", "spring.cloud.config.enabled=false",
        "spring.main.lazy-initialization=true"
})
@Import(TestConfig.class)
class GrpcTransportApiTest {

    @AfterAll
    static void teardown() {
        ControlPlaneLattice.getOrCreate().close();
    }

    private static Message<byte[]> message(long id, byte[] payload, int threshold,
            int responseCount) {
        return MessageBuilder.withPayload(payload)
                .setHeader("TEST", id)
                .setHeader(GrpcExecutor.RESPOND_THRESHOLD, threshold)
                .setHeader(GrpcExecutor.RESPOND_COUNT, responseCount)
                .build();
    }

    private static void assertMessage(Message<byte[]> expected, Message<byte[]> actual) {
        assertEquals(expected.getHeaders().get("TEST"), actual.getHeaders().get("TEST"));
        assertTrue(actual.getHeaders().containsKey(GrpcExecutor.RECEIVED));
        assertArrayEquals(expected.getPayload(), actual.getPayload());
    }

    @Autowired
    TestGrpcClient client;

    @AfterEach
    void reset() {
        GrpcExecutor.M_COUNT.setRelease(0);
    }

    @Test
    void testUnary() {
        System.out.println("Test Unary");
        Message<byte[]> single = message(1, new byte[]{1, 2, 3, 4}, 1, 1);

        GrpcMessage message = GrpcUtils.toGrpc(single, false);
        GrpcMessage response = this.client.unaryRequest(message).block();

        Message<byte[]> retVal = GrpcUtils.toSpringMessage(response);

        assertMessage(single, retVal);
        assertEquals(1, GrpcExecutor.M_COUNT.getAcquire());
    }

    @Test
    void testClientStream() {
        System.out.println("Test Client Stream");
        Flux<GrpcMessage> stream =
                Flux.just(GrpcUtils.toGrpc(message(1, new byte[]{1, 2, 3}, 3, 1), false),
                        GrpcUtils.toGrpc(message(2, new byte[]{3, 4, 5}, 3, 1), false),
                        GrpcUtils.toGrpc(message(3, new byte[]{6, 7, 8}, 3, 1), false));

        GrpcMessage response = this.client.clientStream(stream).block();

        assertNotNull(response);
        assertEquals(3, GrpcExecutor.M_COUNT.getAcquire());
    }

    @Test
    void testServerStream() {
        System.out.println("Test Server Stream");
        Message<byte[]> single = message(1, new byte[]{1, 2, 3, 4}, 1, 3);

        Long responses = this.client.serverStream(GrpcUtils.toGrpc(single, false)).take(3).count()
                .block();

        assertEquals(3, responses);
        assertEquals(1, GrpcExecutor.M_COUNT.getAcquire());
    }

    @Test
    void testBiDiStream() {
        System.out.println("Test BiDi");
        Flux<GrpcMessage> stream =
                Flux.just(GrpcUtils.toGrpc(message(1, new byte[]{1, 2, 3}, 3, 3), false),
                        GrpcUtils.toGrpc(message(2, new byte[]{3, 4, 5}, 3, 3), false),
                        GrpcUtils.toGrpc(message(3, new byte[]{6, 7, 8}, 3, 3), false));

        Long responses = this.client.bidirectionalStream(stream).take(3).count().block();

        assertEquals(3, responses);
        assertEquals(3, GrpcExecutor.M_COUNT.getAcquire());
    }

    @Test
    void testThrow() {
        System.out.println("Test Throw");
        Message<byte[]> message = MessageBuilder.withPayload(new byte[]{1})
                .setHeader(GrpcExecutor.THROW, "").build();

        assertThrows(StatusRuntimeException.class,
                () -> this.client.unaryRequest(GrpcUtils.toGrpc(message, false)).block());
    }

    @TestConfiguration
    public static class TestConfig {

        @Bean
        public AbstractExecutor executor() {
            return new GrpcExecutor();
        }

        @Bean
        public GrpcTransportServiceGrpc.GrpcTransportServiceStub grpcTransportServiceStub(GrpcServerLifecycle lifecycle) {
            ManagedChannel channel =
                    ManagedChannelBuilder.forAddress("localhost", lifecycle.getPort())
                            .usePlaintext().build();

            return GrpcTransportServiceGrpc.newStub(channel);
        }

        @Bean
        public TestGrpcClient grpcClient(GrpcTransportServiceGrpc.GrpcTransportServiceStub stub) {
            return new TestGrpcClient(stub, 4096);
        }
    }
}