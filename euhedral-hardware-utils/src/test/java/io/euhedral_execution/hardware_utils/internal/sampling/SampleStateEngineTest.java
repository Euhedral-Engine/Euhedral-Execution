package io.euhedral_execution.hardware_utils.internal.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalResolution;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.LongGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.IntervalHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuFastSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.IoFastSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.MemoryFastSignals;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

public class SampleStateEngineTest {

    private FastHardwareSample createFastSample(long timeNs, int logicalSpan, BitSet effective,
            long cpuUsage, long memUsage, long ioOps, long ioLatency) {
        CpuFastSignals[] cpus = new CpuFastSignals[logicalSpan];
        for (int i = 0; i < logicalSpan; i++) {
            cpus[i] = new CpuFastSignals(
                    CounterSignal.unsupported(timeNs),
                    CounterSignal.unsupported(timeNs),
                    DoubleGaugeSignal.unsupported(timeNs),
                    CounterSignal.unsupported(timeNs),
                    CounterSignal.unsupported(timeNs),
                    DoubleGaugeSignal.unsupported(timeNs),
                    DoubleGaugeSignal.unsupported(timeNs)
            );
        }
        MemoryFastSignals mem = new MemoryFastSignals(
                LongGaugeSignal.unsupported(timeNs),
                LongGaugeSignal.unsupported(timeNs),
                LongGaugeSignal.valid(memUsage, timeNs),
                LongGaugeSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs)
        );
        IoFastSignals io = new IoFastSignals(
                CounterSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                CounterSignal.valid(ioLatency, timeNs),
                CounterSignal.valid(ioOps, timeNs),
                DoubleGaugeSignal.unsupported(timeNs)
        );

        return new FastHardwareSample(
                timeNs,
                logicalSpan,
                new UnmodifiableBitSet(effective),
                LongGaugeSignal.valid(4L, timeNs),
                LongGaugeSignal.unsupported(timeNs),
                CounterSignal.valid(cpuUsage, timeNs),
                CounterSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                DoubleGaugeSignal.unsupported(timeNs),
                cpus,
                mem,
                io
        );
    }

    @Test
    public void testCounterRuleAndRebaseline() {
        SampleStateEngine engine = new SampleStateEngine(2, 100_000_000L); // 100ms period
        BitSet eff = new BitSet();
        eff.set(0);

        long t1 = 1000L;
        FastHardwareSample s1 = createFastSample(t1, 2, eff, 100L, 500L, 10L, 50L);
        IntervalHardwareSample res1 = engine.processFast(t1 + 100L, s1);
        assertNotNull(res1);
        assertEquals(SignalResolution.BASELINE, res1.productiveCpuNs().resolution());

        long t2 = 2000L;
        FastHardwareSample s2 = createFastSample(t2, 2, eff, 150L, 500L, 15L, 70L);
        IntervalHardwareSample res2 = engine.processFast(t2 + 100L, s2);
        assertNotNull(res2);
        assertEquals(SignalResolution.CURRENT, res2.productiveCpuNs().resolution());
        assertEquals(50L, res2.productiveCpuNs().delta());
        assertEquals(1000L, res2.productiveCpuNs().elapsedNs());

        long t3 = 3000L;
        FastHardwareSample s3 = createFastSample(t3, 2, eff, 120L, 500L, 18L, 80L);
        IntervalHardwareSample res3 = engine.processFast(t3 + 100L, s3);
        assertNotNull(res3);
        assertEquals(SignalResolution.BASELINE, res3.productiveCpuNs().resolution());
    }

    @Test
    public void testGaugeRefreshAndTtlExpiry() {
        long fastPeriodNs = 100_000_000L;
        SampleStateEngine engine = new SampleStateEngine(2, fastPeriodNs);
        long ttlNs = Math.min(30_000_000_000L, Math.max(1_000_000_000L, fastPeriodNs * 5));
        BitSet eff = new BitSet();
        eff.set(0);

        long t1 = 1000L;
        FastHardwareSample s1 = createFastSample(t1, 2, eff, 100L, 500L, 10L, 50L);
        IntervalHardwareSample res1 = engine.processFast(t1 + 100L, s1);
        assertNotNull(res1);
        assertEquals(SignalResolution.CACHED, res1.memorySignals().usageBytes().resolution());
        assertEquals(500L, res1.memorySignals().usageBytes().value());

        long t2 = t1 + ttlNs + 100L;
        IntervalHardwareSample res2 = engine.processFast(t2 + 100L, null);
        assertNull(res2);

        long t3 = t1 + ttlNs + 200L;
        CpuFastSignals[] cpus = new CpuFastSignals[2];
        for (int i = 0; i < 2; i++) {
            cpus[i] = new CpuFastSignals(CounterSignal.unsupported(t3),
                    CounterSignal.unsupported(t3), DoubleGaugeSignal.unsupported(t3),
                    CounterSignal.unsupported(t3), CounterSignal.unsupported(t3),
                    DoubleGaugeSignal.unsupported(t3), DoubleGaugeSignal.unsupported(t3));
        }
        MemoryFastSignals mem = new MemoryFastSignals(LongGaugeSignal.unsupported(t3),
                LongGaugeSignal.unsupported(t3), LongGaugeSignal.unsupported(t3),
                LongGaugeSignal.unsupported(t3), CounterSignal.unsupported(t3),
                CounterSignal.unsupported(t3));
        IoFastSignals io = new IoFastSignals(CounterSignal.unsupported(t3),
                CounterSignal.unsupported(t3), CounterSignal.unsupported(t3),
                CounterSignal.unsupported(t3), DoubleGaugeSignal.unsupported(t3));
        FastHardwareSample s3 = new FastHardwareSample(t3, 2, new UnmodifiableBitSet(eff),
                LongGaugeSignal.unsupported(t3), LongGaugeSignal.unsupported(t3),
                CounterSignal.unsupported(t3), CounterSignal.unsupported(t3),
                CounterSignal.unsupported(t3), CounterSignal.unsupported(t3),
                DoubleGaugeSignal.unsupported(t3), cpus, mem, io);

        IntervalHardwareSample res3 = engine.processFast(t3 + 100L, s3);
        assertNotNull(res3);
        assertEquals(SignalResolution.UNAVAILABLE, res3.memorySignals().usageBytes().resolution());
    }

    @Test
    public void testMembershipUpdateAndPerCpuCleanup() {
        SampleStateEngine engine = new SampleStateEngine(2, 100_000_000L);

        BitSet eff1 = new BitSet();
        eff1.set(0);
        eff1.set(1);

        long t1 = 1000L;
        FastHardwareSample s1 = createFastSample(t1, 2, eff1, 100L, 500L, 10L, 50L);
        engine.processFast(t1 + 10L, s1);

        BitSet eff2 = new BitSet();
        eff2.set(0);

        long t2 = 2000L;
        FastHardwareSample s2 = createFastSample(t2, 2, eff2, 150L, 500L, 15L, 70L);
        IntervalHardwareSample res2 = engine.processFast(t2 + 10L, s2);

        assertNotNull(res2);
        assertEquals(SignalResolution.UNAVAILABLE,
                res2.cpuSignals()[1].schedulerWait().resolution());
    }

    @Test
    public void testRegressionReset() {
        SampleStateEngine engine = new SampleStateEngine(2, 100_000_000L);
        BitSet eff = new BitSet();
        eff.set(0);

        long t1 = 1000L;
        FastHardwareSample s1 = createFastSample(t1, 2, eff, 100L, 500L, 10L, 50L);
        IntervalHardwareSample res1 = engine.processFast(t1 + 100L, s1);
        assertNotNull(res1);

        IntervalHardwareSample res2 = engine.processFast(t1 + 50L, s1);
        assertNull(res2);
    }
}
