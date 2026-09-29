package io.euhedral_execution.hardware_utils.monitor.sampling;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.euhedral_execution.hardware_utils.monitor.internal.SampleStateEngine;
import io.euhedral_execution.hardware_utils.monitor.internal.SlowSampleCache;
import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.monitor.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.BooleanSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.LongGaugeSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.IntervalHardwareSample;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.SlowHardwareSample;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.CpuFastSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.CpuSlowSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.IoFastSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.MemoryFastSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.SystemSlowSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.ThermalSignal;
import io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

public class SamplingContractTest {

    @Test
    public void testSlowSampleCacheAnchorAndTTL() {
        SlowSampleCache cache = new SlowSampleCache();
        long startNs = 1000L;

        CpuSlowSignals[] cpuSlows = new CpuSlowSignals[1];
        cpuSlows[0] = new CpuSlowSignals(
                DoubleGaugeSignal.unsupported(startNs),
                DoubleGaugeSignal.unsupported(startNs),
                LongGaugeSignal.unsupported(startNs),
                LongGaugeSignal.unsupported(startNs),
                new ThermalSignal(ThermalSeverity.NOMINAL, startNs, SignalValidity.UNSUPPORTED),
                new BooleanSignal(false, startNs, SignalValidity.UNSUPPORTED));
        SystemSlowSignals sysSlow = new SystemSlowSignals(
                DoubleGaugeSignal.unsupported(startNs),
                DoubleGaugeSignal.unsupported(startNs),
                new ThermalSignal(ThermalSeverity.NOMINAL, startNs, SignalValidity.UNSUPPORTED),
                new BooleanSignal(false, startNs, SignalValidity.UNSUPPORTED));

        SlowHardwareSample sample = new SlowHardwareSample(startNs, 1, cpuSlows, sysSlow);
        cache.anchorAndStore(startNs, sample);

        assertNotNull(cache.resolve(startNs + 100)); // Fresh
        assertNotNull(cache.resolve(startNs + 15_000_000_000L)); // Exactly TTL
        assertNull(cache.resolve(startNs + 15_000_000_001L)); // Expired
    }

    @Test
    public void testStateEngineResetOnRegression() {
        SampleStateEngine engine = new SampleStateEngine(1, 200_000_000L);

        BitSet bs = new BitSet();
        bs.set(0);
        UnmodifiableBitSet eff = new UnmodifiableBitSet(bs);

        CpuFastSignals[] cpus = new CpuFastSignals[1];
        cpus[0] = new CpuFastSignals(
                CounterSignal.unsupported(0),
                CounterSignal.unsupported(0),
                DoubleGaugeSignal.unsupported(0),
                CounterSignal.unsupported(0),
                CounterSignal.unsupported(0),
                DoubleGaugeSignal.unsupported(0),
                DoubleGaugeSignal.unsupported(0));
        MemoryFastSignals mem = new MemoryFastSignals(
                LongGaugeSignal.unsupported(0),
                LongGaugeSignal.unsupported(0),
                LongGaugeSignal.unsupported(0),
                LongGaugeSignal.unsupported(0),
                CounterSignal.unsupported(0),
                CounterSignal.unsupported(0));
        IoFastSignals io = new IoFastSignals(
                CounterSignal.unsupported(0),
                CounterSignal.unsupported(0),
                CounterSignal.unsupported(0),
                CounterSignal.unsupported(0),
                DoubleGaugeSignal.unsupported(0));

        FastHardwareSample fast = new FastHardwareSample(
                1000L,
                1,
                eff,
                LongGaugeSignal.unsupported(0),
                LongGaugeSignal.unsupported(0),
                CounterSignal.unsupported(0),
                CounterSignal.unsupported(0),
                CounterSignal.unsupported(0),
                CounterSignal.unsupported(0),
                DoubleGaugeSignal.unsupported(0),
                cpus,
                mem,
                io);

        IntervalHardwareSample res1 = engine.processFast(2000L, fast);
        assertNotNull(res1);

        // Regression
        IntervalHardwareSample res2 = engine.processFast(1500L, fast);
        assertNull(res2); // Returns null and resets
    }
}
