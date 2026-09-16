package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.management.ThreadMXBean;
import io.euhedral_execution.core.config.CacheTimingConfig;
import io.euhedral_execution.core.config.CacheTimingFunctionConfig;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
class CacheScarcityGateTest {
    private static CacheTimingFunctionConfig function() {
        return new CacheTimingFunctionConfig(
                "gate-test",
                List.of(.5, 2., 8.),
                List.of(.5, 2., 8.),
                List.of(0., 0., 0.),
                List.of(1., 4., 16.),
                List.of(1., 0., -0.8, 0.),
                List.of(-.8, 0., .3, 0.),
                15000,
                1000000,
                15000,
                814375,
                250000,
                2000000);
    }

    private static FragmentDecisionTree tree(boolean gate, boolean participation) {
        var tree = new FragmentDecisionTree(
                FragmentDecisionWeights.DEFAULT,
                null,
                0,
                0,
                null,
                new CacheTimingConfig(15000, 1000000, function(), gate),
                participation);
        for (int i = 0; i < 64; i++) tree.recordBodyCost(96);
        return tree;
    }

    @Test
    void plentifulUsesOriginalDirectStagedRuleAndNeverSelectsWithdrawal() {
        var gated = tree(true, true);
        var normal = tree(false, false);
        for (int workers : new int[] {7, 15, 23}) {
            for (long productive : new long[] {workers, workers + 1L, workers * 2L}) {
                for (long contention : new long[] {0, 650000, 1000000}) {
                    assertTrue(gated.isPlentiful(productive, workers));
                    assertFalse(gated.shouldCacheExecute(contention / 1000000., productive, workers, workers));
                    assertFalse(gated.willCacheExecute(productive, productive, workers, contention, workers));
                    assertEquals(
                            normal.executionPath(1, 1, productive, productive, workers, contention, workers),
                            gated.executionPath(1, 1, productive, productive, workers, contention, workers));
                }
            }
        }
        assertFalse(gated.isPlentiful(100, 0));
        assertTrue(CacheTimingConfig.DEFAULT.scarcityGateEnabled());
    }

    @Test
    void scarceSelectionAndProspectiveTimingRemainUnchanged() {
        var gated = tree(true, true);
        var original = tree(false, true);
        for (int workers : new int[] {7, 15, 23}) {
            for (int productive = 0; productive < workers; productive++) {
                for (long contention : new long[] {0, 650000, 1000000}) {
                    assertEquals(
                            original.executionPath(1, 1, productive, workers, workers, contention, workers),
                            gated.executionPath(1, 1, productive, workers, workers, contention, workers));
                }
            }
        }
        assertEquals(ExecutionPath.CACHE, gated.executionPath(1, 1, 0, 7, 7, 1000000, 7));
        var timing = new CacheTimingConfig(15000, 1000000, function(), true);
        var upstream = mock(UpstreamQueue.class);
        when(upstream.getAdaptiveContention(123, 1000000)).thenReturn(500000L);
        ControlPlaneFragment.idleCache(timing, gated, upstream, 123, 7, 0);
        verify(upstream).installContentionHalfLife(123, function().halfLifeNanos(.5, 0, 96, 1000000), 1000000);
    }

    @Test
    void localGateAllocatesNoPerDecisionObjects() throws Exception {
        // Other tests instrument this class with Mockito spies. Measure in a fresh
        // JVM so agent advice allocations cannot be mistaken for gate allocations.
        var child = new ProcessBuilder(
                        System.getProperty("java.home") + "/bin/java",
                        "-cp",
                        System.getProperty("java.class.path"),
                        AllocationProbe.class.getName())
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS));
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertEquals("allocated=0", output.strip());
        } finally {
            child.destroyForcibly();
        }
    }

    public static class AllocationProbe {
        public static void main(String[] args) {
            var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            bean.setThreadAllocatedMemoryEnabled(true);
            var gate = tree(true, true);
            long id = Thread.currentThread().threadId();
            long checksum = 0;
            for (int i = 0; i < 1000000; i++) if (gate.isPlentiful(i & 31, 15)) checksum++;
            long before = bean.getThreadAllocatedBytes(id);
            for (int i = 0; i < 1000000; i++) if (gate.isPlentiful(i & 31, 15)) checksum++;
            long allocated = bean.getThreadAllocatedBytes(id) - before;
            if (checksum == 0) throw new AssertionError("gate not exercised");
            System.out.println("allocated=" + allocated);
        }
    }

    @Test
    void plentifulAndFullParticipationOverrideExplicitCacheCutoff() {
        var forced = new FragmentDecisionTree(
                FragmentDecisionWeights.DEFAULT,
                null,
                0,
                0,
                1,
                new CacheTimingConfig(15000, 1000000, function(), true),
                true);
        for (int i = 0; i < 64; i++) {
            forced.recordBodyCost(96);
        }
        assertEquals(ExecutionPath.CACHE, forced.executionPath(1, 1, 1, 7, 7, 1000000, 7));
        assertEquals(ExecutionPath.DIRECT, forced.executionPath(1, 1, 7, 7, 7, 1000000, 7));
    }
}
