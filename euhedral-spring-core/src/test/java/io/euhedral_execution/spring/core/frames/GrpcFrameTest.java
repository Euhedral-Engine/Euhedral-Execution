package io.euhedral_execution.spring.core.frames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.spring.core.frames.GrpcFrame.CommunicationMethod;
import io.euhedral_execution.spring.core.transport.grpc.GrpcUtils;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GrpcFrameTest {

    private static GrpcMessage message(int value, boolean ordered) {
        return GrpcUtils.toGrpc(null, new byte[] {(byte) value}, ordered);
    }

    @Test
    void responseCompletionDependsOnCommunicationMethod() {
        for (CommunicationMethod method : CommunicationMethod.values()) {
            List<GrpcMessage> responses = new ArrayList<>();
            AtomicInteger completions = new AtomicInteger();
            GrpcFrame frame = new GrpcFrame(
                    1,
                    message(1, true),
                    method,
                    responses::add,
                    completions::incrementAndGet,
                    ignored -> {},
                    null,
                    new AtomicBoolean());

            GrpcMessage response = message(2, true);
            frame.respond(response);

            assertEquals(List.of(response), responses);
            int expectedCompletions =
                    method == CommunicationMethod.SINGLE_RESPONSE || method == CommunicationMethod.CLIENT_STREAM
                            ? 1
                            : 0;
            assertEquals(expectedCompletions, completions.get(), method.name());
        }
    }

    @Test
    void replaceRestoresOrderedRoutingAndUpdatesPayload() {
        GrpcFrame frame = new GrpcFrame(
                41,
                message(1, true),
                CommunicationMethod.BIDI,
                ignored -> {},
                () -> {},
                ignored -> {},
                null,
                new AtomicBoolean());
        frame.randomizeHash(12);
        assertFalse(frame.isOrdered());

        GrpcMessage replacement = message(2, true);
        frame.replace(replacement);

        assertTrue(frame.isOrdered());
        assertSame(replacement, frame.getGrpcMessage());
    }

    @Test
    void errorCallbackReceivesGrpcStatusAndFrameIsRecycled() {
        long password = 77;
        FrameManager<GrpcMessage, GrpcFrame> manager = new FrameManager<>(4, password);
        List<Throwable> errors = new ArrayList<>();
        GrpcFrame frame = new GrpcFrame(
                1,
                message(1, true),
                CommunicationMethod.SINGLE_RESPONSE,
                ignored -> {},
                () -> {},
                errors::add,
                manager,
                new AtomicBoolean());

        frame.doFinallyWithError(new IllegalStateException("boom"));

        StatusRuntimeException error = (StatusRuntimeException) errors.getFirst();
        assertEquals(Status.Code.UNKNOWN, error.getStatus().getCode());
        assertSame(frame, manager.get(password));
    }

    @Test
    void killSwitchControlsLiveness() {
        AtomicBoolean killSwitch = new AtomicBoolean();
        GrpcFrame frame = new GrpcFrame(
                1,
                message(1, true),
                CommunicationMethod.BIDI,
                ignored -> {},
                () -> {},
                ignored -> {},
                null,
                killSwitch);

        assertTrue(frame.isAlive());
        frame.kill();
        assertFalse(frame.isAlive());
        assertTrue(killSwitch.getAcquire());
    }
}
