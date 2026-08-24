package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.common.SystemUtilization;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.Mockito;

@Isolated
class ControlPlaneFragmentTest {

    private final List<ControlPlaneFragment> fragments = new ArrayList<>();

    private static FragmentConfig workerConfig() {
        return FragmentConfig.ofDefaults().clone(cloneConfig());
    }

    private static CloneConfig cloneConfig() {
        int cpu = SystemInfo.getCpuSet().nextSetBit(0);
        if (cpu < 0) {
            throw new IllegalStateException("No CPU is available for the unit test");
        }
        CpuInfo info = SystemInfo.getCpuInfo(cpu);
        BitSet cpus = new BitSet();
        cpus.set(cpu);
        return new CloneConfig("test", info.core(), cpus);
    }

    @AfterEach
    void closeFragments() {
        for (ControlPlaneFragment fragment : this.fragments) {
            fragment.close();
        }
        PinnedThreadExecutor.closeAll();
    }

    @Test
    void baseConfigurationCannotStartWithoutCpuOwnership() {
        try (ControlPlaneFragment fragment = create(FragmentConfig.ofDefaults())) {

            assertFalse(fragment.isStarted());
            assertEquals(-1, fragment.core);
            assertNull(fragment.output());
            assertThrows(IllegalStateException.class, fragment::start);
        }
    }

    @Test
    void clonedConfigurationCreatesWorkerInfrastructure() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            assertNotNull(fragment.output());
            assertNotNull(fragment.getLocalCache());
            assertNotNull(fragment.outputStream);
        }
    }

    @Test
    void drainModeIsPropagatedToTheWorkerCache() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            fragment.setDrainMode(true);

            assertTrue(fragment.drainMode);
        }
    }

    @Test
    void firstTouchInitializesWorkerOwnedStructures() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            assertDoesNotThrow(fragment::firstTouch);
        }
    }

    @Test
    void cloneCreatesAnIndependentWorkerWithTheRequestedOwnership() {
        ControlPlaneFragment fragment = create(workerConfig());
        CloneConfig cloneConfig = cloneConfig();

        ControlPlaneFragment cloned = fragment.clone(cloneConfig);
        this.fragments.add(cloned);

        assertNotSame(fragment, cloned);
        assertSame(cloneConfig, cloned.getConfig().cloneConfig());
    }

    @Test
    void fragmentConfigurationPropagatesCloneOwnership() {
        CloneConfig cloneConfig = cloneConfig();

        FragmentConfig cloned = FragmentConfig.ofDefaults().clone(cloneConfig);

        assertSame(cloneConfig, cloned.cloneConfig());
        assertSame(cloneConfig, cloned.cacheConfig().cloneConfig());
    }

    @Test
    void stoppedWorkerIsDrainedAndCanResetSynchronously() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {

            assertTrue(fragment.isDrained());
            assertEquals(0, fragment.reset(System.nanoTime()));
        }
    }

    @Test
    void productivityParkingRequiresAnEnabledThresholdAndEligibleSurplusWorker() {
        assertTrue(ControlPlaneFragment.productivityParkRequired(300L, 200.0, true, 2L, 4, 3, 2L, 900_000L));

        assertFalse(ControlPlaneFragment.productivityParkRequired(0L, 0.0, true, 2L, 4, 3, 2L, 900_000L));
        assertFalse(ControlPlaneFragment.productivityParkRequired(300L, 200.0, false, 2L, 4, 3, 2L, 900_000L));
        assertFalse(ControlPlaneFragment.productivityParkRequired(300L, 200.0, true, 2L, 4, 2, 2L, 900_000L));
        assertFalse(ControlPlaneFragment.productivityParkRequired(300L, 200.0, true, 2L, 4, 3, 2L, 850_000L));
        assertFalse(ControlPlaneFragment.productivityParkRequired(300L, 301.0, true, 2L, 4, 3, 2L, 900_000L));
    }

    @Test
    void closeIsIdempotentBeforeStart() {
        ControlPlaneFragment fragment = create(workerConfig());

        fragment.close();

        assertDoesNotThrow(fragment::close);
    }

    @Test
    void shouldUpdateAdaptiveBatchCapFromValidSnapshot() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            int cpu = fragment.cpu;

            SystemUtilization.CpuSnapshot cpuSnap = Mockito.mock(SystemUtilization.CpuSnapshot.class);
            Mockito.when(cpuSnap.pressure()).thenReturn(0.50);
            Mockito.when(cpuSnap.lastUsageNs()).thenReturn(100L);

            SystemUtilization.CpuSnapshot[] cpus = new SystemUtilization.CpuSnapshot[cpu + 1];
            cpus[cpu] = cpuSnap;

            SystemUtilization.CoreSnapshot snapshot = Mockito.mock(SystemUtilization.CoreSnapshot.class);
            org.mockito.Mockito.when(snapshot.cpuSnapshots()).thenReturn(cpus);

            fragment.update(snapshot);

            assertTrue(fragment.getAdaptiveBatchCap() >= 2L);
        }
    }

    @Test
    void shouldEnforceMinimumBatchSizeFloorOfTwo() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            int cpu = fragment.cpu;

            SystemUtilization.CpuSnapshot cpuSnap = Mockito.mock(SystemUtilization.CpuSnapshot.class);
            Mockito.when(cpuSnap.pressure()).thenReturn(1.00);
            Mockito.when(cpuSnap.lastUsageNs()).thenReturn(200L);

            SystemUtilization.CpuSnapshot[] cpus = new SystemUtilization.CpuSnapshot[cpu + 1];
            cpus[cpu] = cpuSnap;

            SystemUtilization.CoreSnapshot snapshot = Mockito.mock(SystemUtilization.CoreSnapshot.class);
            Mockito.when(snapshot.cpuSnapshots()).thenReturn(cpus);

            fragment.update(snapshot);

            assertEquals(2L, fragment.getAdaptiveBatchCap());
        }
    }

    @Test
    void shouldRejectMalformedOrOutOfOrderSnapshots() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            int cpu = fragment.cpu;

            SystemUtilization.CpuSnapshot cpuSnap1 = Mockito.mock(SystemUtilization.CpuSnapshot.class);
            Mockito.when(cpuSnap1.pressure()).thenReturn(0.0);
            Mockito.when(cpuSnap1.lastUsageNs()).thenReturn(500L);

            SystemUtilization.CpuSnapshot[] cpus1 = new SystemUtilization.CpuSnapshot[cpu + 1];
            cpus1[cpu] = cpuSnap1;

            SystemUtilization.CoreSnapshot snapshot1 = Mockito.mock(SystemUtilization.CoreSnapshot.class);
            Mockito.when(snapshot1.cpuSnapshots()).thenReturn(cpus1);

            fragment.update(snapshot1);
            long cap1 = fragment.getAdaptiveBatchCap();

            SystemUtilization.CpuSnapshot cpuSnap2 = Mockito.mock(SystemUtilization.CpuSnapshot.class);
            Mockito.when(cpuSnap2.pressure()).thenReturn(1.0);
            Mockito.when(cpuSnap2.lastUsageNs()).thenReturn(300L);

            SystemUtilization.CpuSnapshot[] cpus2 = new SystemUtilization.CpuSnapshot[cpu + 1];
            cpus2[cpu] = cpuSnap2;

            SystemUtilization.CoreSnapshot snapshot2 = Mockito.mock(SystemUtilization.CoreSnapshot.class);
            Mockito.when(snapshot2.cpuSnapshots()).thenReturn(cpus2);

            fragment.update(snapshot2);

            assertEquals(cap1, fragment.getAdaptiveBatchCap());
        }
    }

    @Test
    void shouldIgnoreNullAndSparseSnapshots() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            long capBefore = fragment.getAdaptiveBatchCap();

            assertDoesNotThrow(() -> fragment.update(null));
            assertEquals(capBefore, fragment.getAdaptiveBatchCap());

            SystemUtilization.CoreSnapshot mockSnap = Mockito.mock(SystemUtilization.CoreSnapshot.class);
            Mockito.when(mockSnap.cpuSnapshots()).thenReturn(new SystemUtilization.CpuSnapshot[0]);
            assertDoesNotThrow(() -> fragment.update(mockSnap));
            assertEquals(capBefore, fragment.getAdaptiveBatchCap());
        }
    }

    private ControlPlaneFragment create(FragmentConfig config) {
        ControlPlaneFragment fragment = new ControlPlaneFragment(config);
        this.fragments.add(fragment);
        return fragment;
    }
}
