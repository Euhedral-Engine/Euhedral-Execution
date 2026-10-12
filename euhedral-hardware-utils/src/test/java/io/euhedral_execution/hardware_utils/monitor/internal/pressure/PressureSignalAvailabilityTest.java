package io.euhedral_execution.hardware_utils.monitor.internal.pressure;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.monitor.internal.SampleStateEngine;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.LongGaugeSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.IntervalHardwareSample;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.CpuFastSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.IoFastSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.MemoryFastSignals;
import io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet;
import java.util.BitSet;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class PressureSignalAvailabilityTest {

    private static final int SPAN = 2;
    private static final long FAST_PERIOD_NS = 100_000_000L;

    /** Builds a fast sample whose only live signal is the scope scheduler-wait counter. */
    private static FastHardwareSample fastSample(long timeNs, CounterSignal scopeWait) {
        BitSet effective = new BitSet();
        effective.set(0);
        CpuFastSignals[] cpus = new CpuFastSignals[SPAN];
        for (int i = 0; i < SPAN; i++) {
            cpus[i] = new CpuFastSignals(
                    CounterSignal.unsupported(timeNs),
                    CounterSignal.unsupported(timeNs),
                    DoubleGaugeSignal.unsupported(timeNs),
                    CounterSignal.unsupported(timeNs),
                    CounterSignal.unsupported(timeNs),
                    DoubleGaugeSignal.unsupported(timeNs),
                    DoubleGaugeSignal.unsupported(timeNs));
        }
        MemoryFastSignals mem = new MemoryFastSignals(
                LongGaugeSignal.unsupported(timeNs),
                LongGaugeSignal.unsupported(timeNs),
                LongGaugeSignal.unsupported(timeNs),
                LongGaugeSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs));
        IoFastSignals io = new IoFastSignals(
                CounterSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                DoubleGaugeSignal.unsupported(timeNs));
        return new FastHardwareSample(
                timeNs,
                SPAN,
                new UnmodifiableBitSet(effective),
                LongGaugeSignal.unsupported(timeNs),
                LongGaugeSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                scopeWait,
                CounterSignal.unsupported(timeNs),
                DoubleGaugeSignal.unsupported(timeNs),
                cpus,
                mem,
                io);
    }

    private static double step(
            SampleStateEngine engine, PressureState[] state, long timeNs, CounterSignal scopeWait, String label) {
        IntervalHardwareSample interval = engine.processFast(timeNs, fastSample(timeNs, scopeWait));
        assertNotNull(interval, label + ": engine published nothing");
        PressureEvaluation eval = PressureEvaluator.evaluate(interval, state[0], timeNs);
        state[0] = eval.state();
        return eval.candidate().perQuotaCpuPressure().get(0);
    }

    /**
     * M13: a single transient read failure must not collapse established pressure to zero. The
     * engine reports the failed counter as {@code UNAVAILABLE} and the evaluator's smoother then
     * resets the cell, so pressure drops to 0.0 for one tick instead of holding the prior value.
     */
    @Disabled("M13 - fixed in Phase 4")
    @Test
    void transientFailureHoldsPriorPressureInsteadOfDroppingToZero() {
        SampleStateEngine engine = new SampleStateEngine(SPAN, FAST_PERIOD_NS);
        PressureState[] state = {new PressureState(SPAN)};
        long t1 = 1_000_000_000L;
        long t2 = t1 + FAST_PERIOD_NS;
        long t3 = t2 + FAST_PERIOD_NS;
        long t4 = t3 + FAST_PERIOD_NS;

        // Baseline, then 50 ms of scheduler wait in a 100 ms interval (raw rate 0.5).
        step(engine, state, t1, CounterSignal.valid(0L, t1), "t1 baseline");
        double afterValid = step(engine, state, t2, CounterSignal.valid(50_000_000L, t2), "t2 valid");
        assertTrue(afterValid > 0.0, "precondition: a valid interval must produce pressure, got " + afterValid);

        double afterFailure = step(engine, state, t3, CounterSignal.transientFailure(t3), "t3 transient failure");
        assertTrue(
                afterFailure > 0.0,
                "pressure collapsed to " + afterFailure + " after a transient failure; expected the held value "
                        + afterValid);
        assertTrue(
                afterFailure >= afterValid - 1e-9,
                "held pressure " + afterFailure + " is below the pre-failure value " + afterValid);

        double afterRecovery = step(engine, state, t4, CounterSignal.valid(100_000_000L, t4), "t4 valid");
        assertTrue(afterRecovery >= afterFailure - 1e-9, "pressure fell on recovery: " + afterRecovery);
    }
}
