package io.euhedral_execution.hardware_utils.internal.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.common.UnmodifiableDoubleArray;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.SlowHardwareSample;
import io.euhedral_execution.hardware_utils.linux.CgroupV2Resources;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

public class SystemSnapshotCompatibilityAdapterTest {

    private SystemSnapshot createDummySnapshot(long period) {
        return new SystemSnapshot(
                1000L,
                4,
                4,
                period,
                1000L,
                0L,
                new UnmodifiableBitSet(new BitSet(4)),
                new UnmodifiableDoubleArray(new double[4]),
                1024L,
                512L,
                256L,
                100L);
    }

    @Test
    public void testWrapDetailedProvider() {
        DetailedSystemSnapshotProvider detailed = mock(DetailedSystemSnapshotProvider.class);
        DetailedSystemSnapshotProvider wrapped = SystemSnapshotCompatibilityAdapter.wrap(detailed);
        assertSame(detailed, wrapped);
    }

    @Test
    public void testWrapThrowsIllegalArgument() {
        DetailedSystemSnapshotProvider detailed = mock(DetailedSystemSnapshotProvider.class);
        assertThrows(IllegalArgumentException.class, () -> {
            new SystemSnapshotCompatibilityAdapter(detailed);
        });
    }

    @Test
    public void testInitialNullSnapshotThrows() {
        SystemSnapshotProvider provider = mock(SystemSnapshotProvider.class);
        when(provider.getSnapshot()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> {
            new SystemSnapshotCompatibilityAdapter(provider);
        });
    }

    @Test
    public void testSampleSlowDoesNotCallDelegate() {
        SystemSnapshotProvider provider = mock(SystemSnapshotProvider.class);
        when(provider.getSnapshot()).thenReturn(createDummySnapshot(100L));

        SystemSnapshotCompatibilityAdapter adapter = new SystemSnapshotCompatibilityAdapter(provider);
        verify(provider, times(1)).getSnapshot();

        SlowHardwareSample slow = adapter.sampleSlow(2000L);
        verify(provider, times(1)).getSnapshot(); // Unchanged

        assertEquals(2000L, slow.observedAtNs());
        assertEquals(4, slow.logicalSpan());
        assertEquals(4, slow.cpuSignals().length);
        assertEquals(
                SignalValidity.UNSUPPORTED,
                slow.cpuSignals()[0].constrainedFrequencyHz().validity());
    }

    @Test
    public void testSampleFastNullTransientFailure() {
        SystemSnapshotProvider provider = mock(SystemSnapshotProvider.class);
        when(provider.getSnapshot()).thenReturn(createDummySnapshot(100L)).thenReturn(null);
        SystemSnapshotCompatibilityAdapter adapter = new SystemSnapshotCompatibilityAdapter(provider);

        FastHardwareSample fast = adapter.sampleFast(2000L);

        assertEquals(SignalValidity.TRANSIENT_FAILURE, fast.quotaCapacityCpus().validity());
        assertEquals(SignalValidity.TRANSIENT_FAILURE, fast.productiveCpuNs().validity());
    }

    @Test
    public void testLinuxPeriodConversion() {
        CgroupV2Resources provider = mock(CgroupV2Resources.class);
        when(provider.getSnapshot())
                .thenReturn(createDummySnapshot(10L)) // Initial snapshot for constructor
                .thenReturn(createDummySnapshot(10L)) // 1st sampleFast call
                .thenReturn(createDummySnapshot(-1L)) // 2nd sampleFast call
                .thenReturn(createDummySnapshot(Long.MAX_VALUE)); // 3rd sampleFast call
        SystemSnapshotCompatibilityAdapter adapter = new SystemSnapshotCompatibilityAdapter(provider);

        FastHardwareSample fast = adapter.sampleFast(2000L);
        assertEquals(SignalValidity.VALID, fast.quotaPeriodNs().validity());
        assertEquals(10_000L, fast.quotaPeriodNs().value());

        fast = adapter.sampleFast(2000L);
        assertEquals(SignalValidity.UNSUPPORTED, fast.quotaPeriodNs().validity());

        // Overflow
        fast = adapter.sampleFast(2000L);
        assertEquals(SignalValidity.TRANSIENT_FAILURE, fast.quotaPeriodNs().validity());
    }

    @Test
    public void testGenericPeriodConversion() {
        SystemSnapshotProvider provider = mock(SystemSnapshotProvider.class);
        when(provider.getSnapshot())
                .thenReturn(createDummySnapshot(10_000L)) // Initial snapshot for constructor
                .thenReturn(createDummySnapshot(10_000L)) // 1st sampleFast call
                .thenReturn(createDummySnapshot(-1L)) // 2nd sampleFast call
                .thenReturn(createDummySnapshot(0L)); // 3rd sampleFast call
        SystemSnapshotCompatibilityAdapter adapter = new SystemSnapshotCompatibilityAdapter(provider);

        FastHardwareSample fast = adapter.sampleFast(2000L);
        assertEquals(SignalValidity.VALID, fast.quotaPeriodNs().validity());
        assertEquals(10_000L, fast.quotaPeriodNs().value());

        // Negative period is sanitized to 0
        fast = adapter.sampleFast(2000L);
        assertEquals(SignalValidity.UNSUPPORTED, fast.quotaPeriodNs().validity());

        // Zero period
        fast = adapter.sampleFast(2000L);
        assertEquals(SignalValidity.UNSUPPORTED, fast.quotaPeriodNs().validity());
    }
}
