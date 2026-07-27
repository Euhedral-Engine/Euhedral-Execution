package io.euhedral_execution.reactor.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.euhedral_execution.core.flow_control.LatticeHotReceiver.Response;
import io.euhedral_execution.core.frames.AbstractFrame.CancelSignal;
import io.euhedral_execution.core.frames.CallbackFrame;
import io.euhedral_execution.data_structures.queues.MpmcQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.test.StepVerifier;

class EuhedralSinkTest {

    @Test
    void convertsReactorEmissionResultsToFlowControlResponses() {
        assertEquals(Response.OK, EuhedralSink.toResponse(EmitResult.OK));
        assertEquals(Response.RETRY, EuhedralSink.toResponse(EmitResult.FAIL_NON_SERIALIZED));
        assertEquals(Response.RETRY, EuhedralSink.toResponse(EmitResult.FAIL_OVERFLOW));
        assertEquals(Response.TERMINATE, EuhedralSink.toResponse(EmitResult.FAIL_ZERO_SUBSCRIBER));
        assertEquals(Response.TERMINATE, EuhedralSink.toResponse(EmitResult.FAIL_CANCELLED));
        assertEquals(Response.TERMINATE, EuhedralSink.toResponse(EmitResult.FAIL_TERMINATED));
    }

    @Test
    void callbackFramesEmitTheirMappedResult() {
        EuhedralSink<Integer, String> sink = new EuhedralSink<>(new MpmcQueue<>(8));
        CallbackFrame<Integer, String> frame =
                new CallbackFrame<>(1, 7, value -> "value-" + value, sink);

        StepVerifier.create(sink.asFlux())
                .then(frame::execute)
                .expectNext("value-7")
                .then(() -> assertEquals(EmitResult.OK, sink.tryEmitComplete()))
                .verifyComplete();
    }

    @Test
    void cancelledFramesStopBeforeEmitting() {
        EuhedralSink<Integer, Integer> sink = new EuhedralSink<>(new MpmcQueue<>(8));
        AtomicBoolean cancelled = new AtomicBoolean(true);
        CallbackFrame<Integer, Integer> frame =
                new CallbackFrame<>(1, 7, value -> value, sink, null, cancelled);

        assertThrows(CancelSignal.class, () -> sink.push(frame));
    }
}
