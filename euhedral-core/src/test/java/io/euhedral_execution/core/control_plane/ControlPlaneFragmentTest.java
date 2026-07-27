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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ControlPlaneFragmentTest {

    private final List<ControlPlaneFragment> fragments = new ArrayList<>();

    @AfterEach
    void closeFragments() {
        for (ControlPlaneFragment fragment : this.fragments) {
            fragment.close();
        }
        PinnedThreadExecutor.closeAll();
    }

    @Test
    void baseConfigurationCannotStartWithoutCpuOwnership() {
        ControlPlaneFragment fragment = create(FragmentConfig.ofDefaults());

        assertFalse(fragment.isStarted());
        assertEquals(-1, fragment.core);
        assertNull(fragment.output());
        assertThrows(IllegalStateException.class, fragment::start);
    }

    @Test
    void clonedConfigurationCreatesWorkerInfrastructure() {
        ControlPlaneFragment fragment = create(workerConfig());

        assertNotNull(fragment.output());
        assertNotNull(fragment.getLocalCache());
        assertNotNull(fragment.outputStream);
    }

    @Test
    void drainModeIsPropagatedToTheWorkerCache() {
        ControlPlaneFragment fragment = create(workerConfig());

        fragment.setDrainMode(true);

        assertTrue(fragment.drainMode);
    }

    @Test
    void firstTouchInitializesWorkerOwnedStructures() {
        ControlPlaneFragment fragment = create(workerConfig());

        assertDoesNotThrow(fragment::firstTouch);
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
        ControlPlaneFragment fragment = create(workerConfig());

        assertTrue(fragment.isDrained());
        assertEquals(0, fragment.resetForNextTrial(System.nanoTime()));
    }

    @Test
    void closeIsIdempotentBeforeStart() {
        ControlPlaneFragment fragment = create(workerConfig());

        fragment.close();

        assertDoesNotThrow(fragment::close);
    }

    private ControlPlaneFragment create(FragmentConfig config) {
        ControlPlaneFragment fragment = new ControlPlaneFragment(config);
        this.fragments.add(fragment);
        return fragment;
    }

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
}
