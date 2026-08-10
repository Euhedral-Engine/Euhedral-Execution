package io.euhedral_execution.spring.core.transport.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.spring.core.frames.GrpcFrame;
import io.euhedral_execution.spring.core.frames.GrpcFrame.CommunicationMethod;
import io.euhedral_execution.spring.core.transport.grpc.GrpcTestSupport.RecordingReceiver;
import io.euhedral_execution.spring.core.transport.grpc.GrpcTestSupport.RecordingServerObserver;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EuhedralGrpcServerHandlerTest {

    private static GrpcMessage message(int value, boolean ordered) {
        return GrpcUtils.toGrpc(null, new byte[] {(byte) value}, ordered);
    }

    @Test
    void demandCreatesFramesAndResponsesWaitForClientReadiness() {
        RecordingServerObserver client = new RecordingServerObserver();
        RecordingReceiver receiver = new RecordingReceiver();
        EuhedralGrpcServerHandler handler = new EuhedralGrpcServerHandler(client, CommunicationMethod.BIDI, 8, 4);
        handler.addDownstream(receiver);

        handler.request(0);
        handler.request(-1);
        handler.request(2);
        GrpcMessage request = message(1, true);
        handler.onNext(request);

        assertEquals(List.of(2), client.requests);
        GrpcFrame frame = assertInstanceOf(GrpcFrame.class, receiver.frames.getFirst());
        assertSame(request, frame.getGrpcMessage());
        assertTrue(frame.isOrdered());

        GrpcMessage response = message(2, true);
        frame.respond(response);
        assertTrue(client.messages.isEmpty());

        client.setReady(true);
        assertEquals(List.of(response), client.messages);
        assertEquals(0, client.completions);
    }

    @Test
    void clientStreamResponseCompletesClientAndDownstreamOnce() {
        RecordingServerObserver client = new RecordingServerObserver();
        client.ready = true;
        RecordingReceiver receiver = new RecordingReceiver();
        EuhedralGrpcServerHandler handler =
                new EuhedralGrpcServerHandler(client, CommunicationMethod.CLIENT_STREAM, 4, 4);
        handler.addDownstream(receiver);
        handler.onNext(message(1, true));
        GrpcFrame frame = (GrpcFrame) receiver.frames.getFirst();

        frame.respond(message(2, true));
        handler.complete();

        assertTrue(handler.isComplete());
        assertEquals(1, client.completions);
        assertEquals(1, receiver.completions);
        assertEquals(1, client.messages.size());
    }

    @Test
    void cancelCloseAndErrorsAreTerminalAndIdempotent() {
        RecordingServerObserver cancelledClient = new RecordingServerObserver();
        RecordingReceiver cancelledReceiver = new RecordingReceiver();
        EuhedralGrpcServerHandler cancelled =
                new EuhedralGrpcServerHandler(cancelledClient, CommunicationMethod.BIDI, 4, 4);
        cancelled.addDownstream(cancelledReceiver);

        cancelledClient.cancel();
        cancelledClient.close();

        assertTrue(cancelled.isComplete());
        assertEquals(1, cancelledClient.completions);
        assertEquals(1, cancelledReceiver.completions);

        RecordingServerObserver failedClient = new RecordingServerObserver();
        RecordingReceiver failedReceiver = new RecordingReceiver();
        EuhedralGrpcServerHandler failed = new EuhedralGrpcServerHandler(failedClient, CommunicationMethod.BIDI, 4, 4);
        failed.addDownstream(failedReceiver);
        RuntimeException failure = new RuntimeException("failure");

        failed.onError(failure);
        failed.onError(new AssertionError("duplicate"));

        assertTrue(failed.isComplete());
        assertSame(failure, failedClient.error);
        assertEquals(List.of(failure), failedReceiver.errors);
    }

    @Test
    void inboundCompletionCallbackAndSingleDownstreamContractAreObservable() {
        RecordingServerObserver client = new RecordingServerObserver();
        EuhedralGrpcServerHandler handler = new EuhedralGrpcServerHandler(client, CommunicationMethod.BIDI, 4, 4);
        AtomicInteger inboundCompletions = new AtomicInteger();
        RecordingReceiver first = new RecordingReceiver();
        RecordingReceiver second = new RecordingReceiver();
        handler.setOnCompleteHandler(inboundCompletions::incrementAndGet);

        handler.addDownstream(first);
        handler.addDownstream(second);
        handler.onCompleted();

        assertEquals(1, inboundCompletions.get());
        assertTrue(first.errors.isEmpty());
        assertInstanceOf(IllegalAccessException.class, second.errors.getFirst());
    }
}
