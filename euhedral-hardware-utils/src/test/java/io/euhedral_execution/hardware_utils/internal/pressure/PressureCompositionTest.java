package io.euhedral_execution.hardware_utils.internal.pressure;

import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalResolution;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterDelta;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.LatencyInterval;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.ResolvedDouble;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.ResolvedLong;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.IntervalHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuSlowIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.IoIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.MemoryIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.SystemSlowIntervalSignals;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PressureCompositionTest {
    
    private static CounterDelta delta(long d, long elapsed) {
        return new CounterDelta(d, elapsed, 1000L, SignalResolution.CURRENT);
    }
    
    @Test
    void compositionGoldenTest() {
        BitSet effective = new BitSet();
        effective.set(0);
        
        CpuIntervalSignals cpu0 = new CpuIntervalSignals(
            delta(25, 100), // schedulerWait = 0.25
            delta(40, 100), // psiStall = 0.40
            new ResolvedDouble(0.0, 1000L, SignalResolution.UNAVAILABLE),
            delta(30, 100), // quotaThrottle = 0.30
            delta(20, 100), // steal = 0.20
            new ResolvedDouble(0.0, 1000L, SignalResolution.UNAVAILABLE),
            new ResolvedDouble(2.5, 1000L, SignalResolution.CURRENT) // runnablePerCapacity
        );
        
        CpuSlowIntervalSignals slow0 = new CpuSlowIntervalSignals(
            new ResolvedDouble(90.0, 1000L, SignalResolution.CURRENT), // available
            new ResolvedDouble(100.0, 1000L, SignalResolution.CURRENT), // nominal => 0.10 capacity loss
            new ResolvedLong(0, 1000L, SignalResolution.UNAVAILABLE),
            new ResolvedLong(0, 1000L, SignalResolution.UNAVAILABLE),
            ThermalSeverity.NOMINAL,
            false,
            1000L,
            SignalResolution.CURRENT
        );
        
        MemoryIntervalSignals mem = new MemoryIntervalSignals(
            new ResolvedLong(1000L, 1000L, SignalResolution.CURRENT), // hardLimit
            new ResolvedLong(0, 1000L, SignalResolution.UNAVAILABLE),
            new ResolvedLong(900L, 1000L, SignalResolution.CURRENT), // usage
            new ResolvedLong(0, 1000L, SignalResolution.UNAVAILABLE),
            new CounterDelta(10, 1_000_000_000L, 1000L, SignalResolution.CURRENT), // cumulativeReclaimBytes -> 10/1000 per sec = 0.01 limit/sec
            delta(20, 100) // memoryStall = 0.20
        );
        
        IoIntervalSignals io = new IoIntervalSignals(
            new CounterDelta(0, 1000L, 1000L, SignalResolution.UNAVAILABLE), // productiveBytes
            delta(40, 100), // ioStall = 0.40
            new LatencyInterval(25_500_000L, 1L, 100L, 1000L, SignalResolution.CURRENT), // 25.5ms
            new ResolvedDouble(0.0, 1000L, SignalResolution.UNAVAILABLE) // max queue
        );
        
        SystemSlowIntervalSignals sysSlow = new SystemSlowIntervalSignals(
            new ResolvedDouble(0.0, 1000L, SignalResolution.UNAVAILABLE),
            new ResolvedDouble(0.0, 1000L, SignalResolution.UNAVAILABLE),
            ThermalSeverity.NOMINAL, false, 1000L, SignalResolution.CURRENT
        );

        IntervalHardwareSample sample = new IntervalHardwareSample(
            1000L,
            1,
            new UnmodifiableBitSet(effective),
            new ResolvedLong(1000L, 1000L, SignalResolution.CURRENT),
            new ResolvedLong(1000L, 1000L, SignalResolution.CURRENT),
            new CounterDelta(0, 1000L, 1000L, SignalResolution.UNAVAILABLE), // productive
            new CounterDelta(0, 1000L, 1000L, SignalResolution.UNAVAILABLE), // scope Wait
            new CounterDelta(0, 1000L, 1000L, SignalResolution.UNAVAILABLE), // scope Psi
            new CounterDelta(0, 1000L, 1000L, SignalResolution.UNAVAILABLE), // scope Throttled
            new ResolvedDouble(0.0, 1000L, SignalResolution.UNAVAILABLE), // scope Reported
            new CpuIntervalSignals[]{ cpu0 },
            mem,
            io,
            new CpuSlowIntervalSignals[]{ slow0 },
            sysSlow
        );

        PressureState prior = new PressureState(1);
        PressureEvaluation eval = PressureEvaluator.evaluate(sample, prior, 1000L);
        HardwareUtilization util = eval.candidate();
        
        assertEquals(0.50, util.pressure(), 0.001);
        assertEquals(0.50, util.perQuotaCpuPressure().get(0), 0.001);
        assertEquals(0.30, util.cpuThrottleRatio(), 0.001);
    }
}
