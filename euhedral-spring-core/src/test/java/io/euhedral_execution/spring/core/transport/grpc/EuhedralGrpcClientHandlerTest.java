package io.euhedral_execution.spring.core.transport.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.spring.core.frames.GrpcFrame;
import io.euhedral_execution.spring.core.frames.GrpcFrame.CommunicationMethod;
import io.euhedral_execution.spring.core.transport.grpc.GrpcTestSupport.RecordingClientObserver;
import io.euhedral_execution.spring.core.transport.grpc.GrpcTestSupport.RecordingReceiver;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import org.junit.jupiter.api.Test;

class EuhedralGrpcClientHandlerTest {

    private static GrpcMessage message(int value, boolean ordered) {
        return GrpcUtils.toGrpc(null, new byte[] {(byte) value}, ordered);
    }

    @Test
    void demandCreatesFramesAndResponsesWaitForTransportReadiness() {
        RecordingClientObserver transport = new RecordingClientObserver();
        RecordingReceiver receiver = new RecordingReceiver();
        EuhedralGrpcClientHandler handler = new EuhedralGrpcClientHandler(CommunicationMethod.BIDI, 8, 4);
        handler.beforeStart(transport);
        handler.addDownstream(receiver);

        handler.request(0);
        handler.request(-1);
        handler.request(3);
        GrpcMessage request = message(1, false);
        handler.onNext(request);

        assertTrue(transport.autoRequestDisabled);
        assertEquals(1, transport.requests.size());
        assertEquals(3, transport.requests.getFirst());
        GrpcFrame frame = assertInstanceOf(GrpcFrame.class, receiver.frames.getFirst());
        assertSame(request, frame.getGrpcMessage());
        assertFalse(frame.isOrdered());

        GrpcMessage response = message(2, true);
        frame.respond(response);
        assertTrue(transport.messages.isEmpty());

        transport.setReady(true);
        assertEquals(java.util.List.of(response), transport.messages);
        assertEquals(0, receiver.completions);
        assertNull(transport.error);
    }

    @Test
    void completionAndErrorAreDeliveredAtMostOnce() {
        RecordingClientObserver completedTransport = new RecordingClientObserver();
        RecordingReceiver completedReceiver = new RecordingReceiver();
        EuhedralGrpcClientHandler completed = new EuhedralGrpcClientHandler(CommunicationMethod.BIDI, 4, 4);
        completed.beforeStart(completedTransport);
        completed.addDownstream(completedReceiver);

        completed.complete();
        completed.complete();

        assertTrue(completed.isComplete());
        assertEquals(1, completedReceiver.completions);
        assertEquals(1, completedTransport.completions);

        RecordingClientObserver failedTransport = new RecordingClientObserver();
        RecordingReceiver failedReceiver = new RecordingReceiver();
        EuhedralGrpcClientHandler failed = new EuhedralGrpcClientHandler(CommunicationMethod.BIDI, 4, 4);
        failed.beforeStart(failedTransport);
        failed.addDownstream(failedReceiver);
        IllegalStateException failure = new IllegalStateException("failure");

        failed.onError(failure);
        failed.onError(new AssertionError("duplicate"));

        assertTrue(failed.isComplete());
        assertEquals(java.util.List.of(failure), failedReceiver.errors);
        assertSame(failure, failedTransport.error);
    }

    @Test
    void onlyOneDownstreamCanBeRegistered() {
        EuhedralGrpcClientHandler handler = new EuhedralGrpcClientHandler(CommunicationMethod.BIDI, 4, 4);
        handler.beforeStart(new RecordingClientObserver());
        RecordingReceiver first = new RecordingReceiver();
        RecordingReceiver second = new RecordingReceiver();

        handler.addDownstream(first);
        handler.addDownstream(second);

        assertTrue(first.errors.isEmpty());
        assertInstanceOf(IllegalAccessException.class, second.errors.getFirst());
    }

    @Test
    void cancelPropagatesCauseAndCompletesTheSource() {
        RecordingClientObserver transport = new RecordingClientObserver();
        RecordingReceiver receiver = new RecordingReceiver();
        EuhedralGrpcClientHandler handler = new EuhedralGrpcClientHandler(CommunicationMethod.BIDI, 4, 4);
        handler.beforeStart(transport);
        handler.addDownstream(receiver);
        RuntimeException cause = new RuntimeException("cancelled");

        handler.cancel("stop", cause);

        assertEquals("stop", transport.cancelMessage);
        assertSame(cause, transport.cancelCause);
        assertTrue(handler.isComplete());
        assertEquals(1, receiver.completions);
    }
}
