package io.euhedral_execution.core.generics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.frames.AbstractFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/// Deterministic lifecycle tests for the executor terminal's sparse diagnostic timing boundary.
class AbstractExecutorTest {

    @Test
    void productionConstructorExecutesWithoutDiagnosticCallbacks() {
        AtomicInteger executions = new AtomicInteger();
        RecordingFrame frame = new RecordingFrame();
        TestExecutor executor = new TestExecutor(executions::incrementAndGet);

        executor.input(new RepeatingTestSource(frame, 512));

        assertEquals(512, executions.get());
        assertEquals(512, frame.finalizations);
    }

    @Test
    void samplesEveryConfiguredEligibleCall() {
        AtomicLong time = new AtomicLong();
        List<Long> samples = new ArrayList<>();
        RecordingFrame frame = new RecordingFrame();
        TestExecutor executor = new TestExecutor(256, time::get, samples::add, () -> time.addAndGet(37L));

        executor.input(new RepeatingTestSource(frame, 512));

        assertEquals(List.of(37L, 37L), samples);
        assertEquals(512, frame.finalizations);
    }

    @Test
    void productionRecorderSamplesAtTheFixedCadence() {
        List<Long> samples = new ArrayList<>();
        RecordingFrame frame = new RecordingFrame();
        TestExecutor executor = new TestExecutor(() -> {});
        executor.attachProductionBodyTimingRecorder(samples::add);

        executor.input(new RepeatingTestSource(frame, 512));

        assertEquals(2, samples.size());
        assertTrue(samples.get(0) >= 0L);
        assertTrue(samples.get(1) >= 0L);
        assertEquals(512, frame.finalizations);
    }

    @Test
    void diagnosticAndProductionRecordersShareOneTimerPair() {
        AtomicLong time = new AtomicLong();
        AtomicInteger clockReads = new AtomicInteger();
        List<Long> diagnosticSamples = new ArrayList<>();
        List<Long> productionSamples = new ArrayList<>();
        RecordingFrame frame = new RecordingFrame();
        TestExecutor executor = new TestExecutor(
                AbstractExecutor.PRODUCTION_BODY_TIMING_INTERVAL,
                () -> {
                    clockReads.incrementAndGet();
                    return time.get();
                },
                diagnosticSamples::add,
                () -> time.addAndGet(37L));
        executor.attachProductionBodyTimingRecorder(productionSamples::add);

        executor.input(new RepeatingTestSource(frame, AbstractExecutor.PRODUCTION_BODY_TIMING_INTERVAL));

        assertEquals(List.of(37L), diagnosticSamples);
        assertEquals(List.of(37L), productionSamples);
        assertEquals(2, clockReads.get());
    }

    @Test
    void rejectsLateDuplicateAndIncompatibleProductionAttachment() {
        TestExecutor duplicate = new TestExecutor(() -> {});
        duplicate.attachProductionBodyTimingRecorder(ignored -> {});
        assertThrows(IllegalStateException.class, () -> duplicate.attachProductionBodyTimingRecorder(ignored -> {}));

        TestExecutor late = new TestExecutor(() -> {});
        late.input(new RepeatingTestSource(new RecordingFrame(), 0));
        assertThrows(IllegalStateException.class, () -> late.attachProductionBodyTimingRecorder(ignored -> {}));

        TestExecutor incompatible = new TestExecutor(8, System::nanoTime, ignored -> {}, () -> {});
        assertThrows(IllegalStateException.class, () -> incompatible.attachProductionBodyTimingRecorder(ignored -> {}));
        assertThrows(
                NullPointerException.class, () -> new TestExecutor(() -> {}).attachProductionBodyTimingRecorder(null));
    }

    @Test
    void excludesLivenessAndFinalizationFromRecordedInterval() {
        AtomicLong time = new AtomicLong();
        List<Long> samples = new ArrayList<>();
        RecordingFrame frame = new RecordingFrame(time, 100L, 200L);
        TestExecutor executor = new TestExecutor(1, time::get, samples::add, () -> time.addAndGet(23L));

        executor.input(new RepeatingTestSource(frame, 1));

        assertEquals(List.of(23L), samples);
        assertEquals(323L, time.get());
    }

    @Test
    void discardsFailedSampleAndResetsCadence() {
        AtomicLong time = new AtomicLong();
        List<Long> samples = new ArrayList<>();
        AtomicInteger executions = new AtomicInteger();
        RecordingFrame frame = new RecordingFrame();
        TestExecutor executor = new TestExecutor(256, time::get, samples::add, () -> {
            int call = executions.incrementAndGet();
            time.addAndGet(31L);
            if (call == 256) {
                throw new IllegalStateException("expected");
            }
        });
        List<Long> productionSamples = new ArrayList<>();
        executor.attachProductionBodyTimingRecorder(productionSamples::add);

        executor.input(new RepeatingTestSource(frame, 512));

        assertEquals(List.of(31L), samples);
        assertEquals(List.of(31L), productionSamples);
        assertEquals(511, frame.finalizations);
        assertEquals(1, frame.errorFinalizations);
    }

    @Test
    void discardsCancelledSampleAndPreservesNormalFinalization() {
        AtomicLong time = new AtomicLong();
        List<Long> samples = new ArrayList<>();
        RecordingFrame frame = new RecordingFrame();
        TestExecutor executor = new TestExecutor(1, time::get, samples::add, frame::throwCancelSignal);

        executor.input(new RepeatingTestSource(frame, 1));

        assertEquals(List.of(), samples);
        assertEquals(1, frame.finalizations);
        assertEquals(0, frame.errorFinalizations);
    }

    @Test
    void recorderFailureDoesNotBecomeAFrameFailureOrBlockFanOut() {
        AtomicLong time = new AtomicLong();
        List<Long> productionSamples = new ArrayList<>();
        RecordingFrame frame = new RecordingFrame();
        TestExecutor executor = new TestExecutor(
                AbstractExecutor.PRODUCTION_BODY_TIMING_INTERVAL,
                time::get,
                ignored -> {
                    throw new IllegalStateException("expected diagnostic recorder failure");
                },
                () -> time.addAndGet(19L));
        executor.attachProductionBodyTimingRecorder(productionSamples::add);

        executor.input(new RepeatingTestSource(frame, AbstractExecutor.PRODUCTION_BODY_TIMING_INTERVAL));

        assertEquals(List.of(19L), productionSamples);
        assertEquals(AbstractExecutor.PRODUCTION_BODY_TIMING_INTERVAL, frame.finalizations);
        assertEquals(0, frame.errorFinalizations);
    }

    @Test
    void terminalCountdownRetainsPhaseWhenRecorderStateIsCleared() {
        AtomicLong time = new AtomicLong();
        List<Long> productionSamples = new ArrayList<>();
        RecordingFrame frame = new RecordingFrame();
        TestExecutor executor = new TestExecutor(
                AbstractExecutor.PRODUCTION_BODY_TIMING_INTERVAL, time::get, ignored -> {}, () -> time.addAndGet(29L));
        executor.attachProductionBodyTimingRecorder(productionSamples::add);
        ManualTestSource source = new ManualTestSource(frame);

        executor.input(source);
        source.push(128);
        productionSamples.clear();
        source.push(128);

        assertEquals(List.of(29L), productionSamples);
    }

    @Test
    void validatesDiagnosticConstructorInputs() {
        LongSupplier clock = () -> 1L;
        LongConsumer recorder = ignored -> {};

        assertThrows(IllegalArgumentException.class, () -> new TestExecutor(0, clock, recorder, () -> {}));
        assertThrows(NullPointerException.class, () -> new TestExecutor(1, null, recorder, () -> {}));
        assertThrows(NullPointerException.class, () -> new TestExecutor(1, clock, null, () -> {}));
    }

    private static final class TestExecutor extends AbstractExecutor {

        private final Runnable body;

        /// Creates a sampling-disabled executor for the production fast-path control.
        private TestExecutor(Runnable body) {
            super(0);
            this.body = body;
        }

        /// Creates a diagnostic executor using the caller's deterministic clock and recorder.
        private TestExecutor(int interval, LongSupplier clock, LongConsumer recorder, Runnable body) {
            super(0, interval, clock, recorder);
            this.body = body;
        }

        @Override
        public void execute(AbstractFrame frame) {
            this.body.run();
        }

        @Override
        public AbstractExecutor hookOnClone(int cpu) {
            throw new UnsupportedOperationException("Test executor is not cloned");
        }
    }

    private static final class RecordingFrame extends AbstractFrame {

        private final AtomicLong time;
        private final long livenessNanos;
        private final long finalizationNanos;
        private int finalizations;
        private int errorFinalizations;

        /// Creates a frame whose lifecycle hooks do not advance a test clock.
        private RecordingFrame() {
            this(new AtomicLong(), 0L, 0L);
        }

        /// Creates a frame that exposes timing-boundary mistakes through a shared logical clock.
        private RecordingFrame(AtomicLong time, long livenessNanos, long finalizationNanos) {
            super(0L);
            this.time = time;
            this.livenessNanos = livenessNanos;
            this.finalizationNanos = finalizationNanos;
        }

        @Override
        public boolean isAlive() {
            this.time.addAndGet(this.livenessNanos);
            return true;
        }

        @Override
        public void doFinally() {
            this.time.addAndGet(this.finalizationNanos);
            this.finalizations++;
        }

        @Override
        public void doFinallyWithError(Throwable throwable) {
            this.time.addAndGet(this.finalizationNanos);
            this.errorFinalizations++;
        }
    }

    private static final class RepeatingTestSource implements LatticeSource {

        private final AbstractFrame frame;
        private final int pushes;

        /// Creates a synchronous source that presents the same frame a fixed number of times.
        private RepeatingTestSource(AbstractFrame frame, int pushes) {
            this.frame = frame;
            this.pushes = pushes;
        }

        @Override
        public void addDownstream(LatticeReceiver terminal) {
            terminal.addUpstream(this);
            for (int i = 0; i < this.pushes; i++) {
                terminal.push(this.frame);
            }
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            return 0L;
        }

        @Override
        public void request(long demand) {}

        @Override
        public void complete() {}

        @Override
        public boolean isComplete() {
            return false;
        }
    }

    private static final class ManualTestSource implements LatticeSource {

        private final AbstractFrame frame;
        private LatticeReceiver terminal;

        /// Creates a source whose single terminal can be driven across diagnostic phase changes.
        private ManualTestSource(AbstractFrame frame) {
            this.frame = frame;
        }

        @Override
        public void addDownstream(LatticeReceiver terminal) {
            this.terminal = terminal;
            terminal.addUpstream(this);
        }

        /// Pushes a fixed number of eligible calls through the already-created terminal.
        private void push(int count) {
            for (int i = 0; i < count; i++) {
                this.terminal.push(this.frame);
            }
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            return 0L;
        }

        @Override
        public void request(long demand) {}

        @Override
        public void complete() {}

        @Override
        public boolean isComplete() {
            return false;
        }
    }
}
