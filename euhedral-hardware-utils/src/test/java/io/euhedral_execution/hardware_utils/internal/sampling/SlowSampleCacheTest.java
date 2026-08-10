package io.euhedral_execution.hardware_utils.internal.sampling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.BooleanSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.LongGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.SlowHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuSlowSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.SystemSlowSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.ThermalSignal;
import org.junit.jupiter.api.Test;

public class SlowSampleCacheTest {
    @Test
    public void testIsDueAndAnchor() {
        SlowSampleCache cache = new SlowSampleCache();
        assertTrue(cache.isDue(1000L));

        SlowHardwareSample sample = createDummySample(1000L);
        cache.anchorAndStore(1000L, sample);

        // Next attempt is at 1000L + 5s (5_000_000_000L)
        assertFalse(cache.isDue(1000L + 4_999_999_999L));
        assertTrue(cache.isDue(1000L + 5_000_000_000L));

        // Advance by first future rule
        cache.anchorAndStore(1000L + 6_000_000_000L, createDummySample(1000L + 6_000_000_000L));
        // Previous anchor was 5s + 1000. New poll is at 6s + 1000. Next should be 10s + 1000.
        assertFalse(cache.isDue(1000L + 9_999_999_999L));
        assertTrue(cache.isDue(1000L + 10_000_000_000L));
    }

    @Test
    public void testResolveTTL() {
        SlowSampleCache cache = new SlowSampleCache();
        SlowHardwareSample sample = createDummySample(1000L);
        cache.anchorAndStore(1000L, sample);

        assertSame(sample, cache.resolve(1000L + 15_000_000_000L));
        assertNull(cache.resolve(1000L + 15_000_000_001L));
    }

    @Test
    public void testResolveNegativeAge() {
        SlowSampleCache cache = new SlowSampleCache();
        SlowHardwareSample sample = createDummySample(1000L);
        cache.anchorAndStore(1000L, sample);

        assertNull(cache.resolve(999L)); // age < 0
    }

    @Test
    public void testClear() {
        SlowSampleCache cache = new SlowSampleCache();
        cache.anchorAndStore(1000L, createDummySample(1000L));
        cache.clear();
        assertNull(cache.resolve(1000L));
        assertTrue(cache.isDue(5000L));
    }

    @Test
    public void testRetainForStop() {
        SlowSampleCache cache = new SlowSampleCache();
        SlowHardwareSample sample = createDummySample(1000L);
        cache.anchorAndStore(1000L, sample);
        cache.retainForStop();
        assertSame(sample, cache.resolve(1000L));
    }

    private SlowHardwareSample createDummySample(long t) {
        CpuSlowSignals cpu = new CpuSlowSignals(
                DoubleGaugeSignal.unsupported(t),
                DoubleGaugeSignal.unsupported(t),
                LongGaugeSignal.unsupported(t),
                LongGaugeSignal.unsupported(t),
                new ThermalSignal(ThermalSeverity.NOMINAL, t, SignalValidity.UNSUPPORTED),
                new BooleanSignal(false, t, SignalValidity.UNSUPPORTED));
        SystemSlowSignals sys = new SystemSlowSignals(
                DoubleGaugeSignal.unsupported(t),
                DoubleGaugeSignal.unsupported(t),
                new ThermalSignal(ThermalSeverity.NOMINAL, t, SignalValidity.UNSUPPORTED),
                new BooleanSignal(false, t, SignalValidity.UNSUPPORTED));
        return new SlowHardwareSample(t, 1, new CpuSlowSignals[] {cpu}, sys);
    }
}
