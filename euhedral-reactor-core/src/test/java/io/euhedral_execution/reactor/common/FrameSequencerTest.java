package io.euhedral_execution.reactor.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class FrameSequencerTest {

    @Test
    void emitsResultsInInputOrderAfterOutOfOrderExecution() {
        FrameSequencer<Integer, String> sequencer = new FrameSequencer<>(123);
        List<SequencedFrame<Integer, String>> frames = sequencer
                .flatMapSequential(Flux.just(1, 2, 3), value -> "value-" + value, 2)
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals(3, frames.size());
        assertTrue(frames.stream().allMatch(SequencedFrame::isAlive));

        StepVerifier.create(sequencer.output())
                .then(() -> execute(frames.get(2)))
                .then(() -> execute(frames.get(1)))
                .then(() -> execute(frames.get(0)))
                .expectNext("value-1", "value-2", "value-3")
                .verifyComplete();
    }

    @Test
    void sourceErrorsTerminateOutputAndCancelPendingFrames() {
        FrameSequencer<Integer, Integer> sequencer = new FrameSequencer<>(456);
        IllegalStateException failure = new IllegalStateException("boom");
        List<SequencedFrame<Integer, Integer>> frames = new ArrayList<>();

        sequencer.flatMapSequential(
                        Flux.concat(Flux.just(1), Flux.error(failure)),
                        value -> value * 2,
                        1)
                .subscribe(frames::add, ignored -> {
                });

        assertEquals(1, frames.size());
        assertFalse(frames.get(0).isAlive());
        StepVerifier.create(sequencer.output())
                .expectErrorSatisfies(error -> assertEquals(failure, error))
                .verify();
    }

    @Test
    void sequencerCannotBeReusedForAnotherInputFlux() {
        FrameSequencer<Integer, Integer> sequencer = new FrameSequencer<>(789);
        sequencer.flatMapSequential(Flux.empty(), value -> value, 0).blockLast();

        assertThrows(IllegalStateException.class,
                () -> sequencer.flatMapSequential(Flux.empty(), value -> value, 0));
    }

    private static void execute(SequencedFrame<?, ?> frame) {
        frame.execute();
        frame.doFinally();
    }
}
