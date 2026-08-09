package io.euhedral_execution.hardware_utils.internal.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.common.UnmodifiableDoubleArray;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuFastSignals;
import io.euhedral_execution.hardware_utils.linux.CgroupV2Resources;
import io.euhedral_execution.hardware_utils.osx.OSXResources;
import io.euhedral_execution.hardware_utils.windows.WindowsResources;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

public class ProviderContractTest {

    private SystemSnapshot createSnapshot(long period) {
        return new SystemSnapshot(
                1000L, 1, 1, period, 1000L, 0L,
                new UnmodifiableBitSet(new BitSet(1)),
                new UnmodifiableDoubleArray(new double[1]),
                1024L, 512L, 256L, 100L
        );
    }

    @Test
    public void testLinuxProfile() {
        CgroupV2Resources linuxProvider = mock(CgroupV2Resources.class);
        when(linuxProvider.getSnapshot()).thenReturn(createSnapshot(10L));
        DetailedSystemSnapshotProvider provider = SystemSnapshotCompatibilityAdapter.wrap(
                linuxProvider);
        FastHardwareSample fast = provider.sampleFast(2000L);
        // Period should be multiplied by 1000
        assertEquals(10_000L, fast.quotaPeriodNs().value());
        assertEquals(SignalValidity.VALID, fast.quotaPeriodNs().validity());

        // Linux neutral pressure: reported ratios are UNSUPPORTED
        CpuFastSignals cpu = fast.cpuSignals()[0];
        assertEquals(SignalValidity.UNSUPPORTED, cpu.reportedSchedulerStallRatio().validity());
    }

    @Test
    public void testWindowsProfile() {
        WindowsResources windowsProvider = mock(WindowsResources.class);
        when(windowsProvider.getSnapshot()).thenReturn(createSnapshot(10_000L));
        DetailedSystemSnapshotProvider provider = SystemSnapshotCompatibilityAdapter.wrap(
                windowsProvider);
        FastHardwareSample fast = provider.sampleFast(2000L);
        // Period is unchanged (nanos)
        assertEquals(10_000L, fast.quotaPeriodNs().value());

        // Windows neutral pressure: productive and throttled CPU are UNSUPPORTED
        assertEquals(SignalValidity.UNSUPPORTED, fast.productiveCpuNs().validity());
        assertEquals(SignalValidity.UNSUPPORTED, fast.scopeQuotaThrottledNs().validity());
    }

    @Test
    public void testOSXProfile() {
        OSXResources osxProvider = mock(OSXResources.class);
        when(osxProvider.getSnapshot()).thenReturn(createSnapshot(10_000L));
        DetailedSystemSnapshotProvider provider = SystemSnapshotCompatibilityAdapter.wrap(
                osxProvider);
        FastHardwareSample fast = provider.sampleFast(2000L);
        // Period is unchanged (nanos)
        assertEquals(10_000L, fast.quotaPeriodNs().value());

        // Mac neutral pressure: productive and throttled CPU are UNSUPPORTED
        assertEquals(SignalValidity.UNSUPPORTED, fast.productiveCpuNs().validity());
        assertEquals(SignalValidity.UNSUPPORTED, fast.scopeQuotaThrottledNs().validity());
    }

    @Test
    public void testCanonicalProfile() {
        SystemSnapshotProvider dummyProvider = mock(SystemSnapshotProvider.class);
        when(dummyProvider.getSnapshot()).thenReturn(createSnapshot(10_000L));
        DetailedSystemSnapshotProvider provider = SystemSnapshotCompatibilityAdapter.wrap(
                dummyProvider);
        FastHardwareSample fast = provider.sampleFast(2000L);
        // Period is unchanged (nanos)
        assertEquals(10_000L, fast.quotaPeriodNs().value());

        // Canonical supports ratio
        CpuFastSignals cpu = fast.cpuSignals()[0];
        assertEquals(SignalValidity.VALID, cpu.reportedSchedulerStallRatio().validity());
    }

    @Test
    public void testDeepCopyValidation() {
        BitSet bs = new BitSet(1);
        bs.set(0);
        SystemSnapshot snap = new SystemSnapshot(
                1000L, 1, 1.0, 10_000L, 1000L, 0L,
                new UnmodifiableBitSet(bs),
                new UnmodifiableDoubleArray(new double[]{0.5}),
                1024L, 512L, 256L, 100L
        );
        SystemSnapshotProvider dummyProvider = mock(SystemSnapshotProvider.class);
        when(dummyProvider.getSnapshot()).thenReturn(snap);
        DetailedSystemSnapshotProvider provider = SystemSnapshotCompatibilityAdapter.wrap(
                dummyProvider);
        FastHardwareSample fast = provider.sampleFast(2000L);

        CpuFastSignals[] cpus = fast.cpuSignals();
        assertNotNull(cpus);
        assertEquals(1, cpus.length);
        assertEquals(0.5, cpus[0].reportedSchedulerStallRatio().value(), 0.001);
    }
}
