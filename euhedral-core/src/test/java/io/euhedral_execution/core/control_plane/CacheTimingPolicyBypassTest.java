package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import io.euhedral_execution.core.config.CacheTimingConfig;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class CacheTimingPolicyBypassTest {
    @Test
    void productionScarcePathUsesFrozenParkAndHalfLife() {
        var timing = CacheTimingConfig.DEFAULT;
        var policy = new FragmentDecisionTree(FragmentDecisionWeights.DEFAULT, null, 0, 0);
        for (int i = 0; i < 64; i++) policy.recordBodyCost(96);
        var upstream = mock(UpstreamQueue.class);
        when(upstream.getAdaptiveContention(123, 1000000)).thenReturn(800000L);
        assertEquals(800000, ControlPlaneFragment.cacheContention(timing, policy, upstream, 123));
        try (var parks = mockStatic(LockSupport.class)) {
            ControlPlaneFragment.idleCache(timing, policy, upstream, 123, 15, 1);
            parks.verify(() -> LockSupport.parkNanos(296288));
            parks.verifyNoMoreInteractions();
        }
        verify(upstream).installContentionHalfLife(123, 330401, 1000000);
    }

    @Test
    void productionGateBypassesWithdrawalAndPreservesDirectStagedDecisions() {
        var production = new FragmentDecisionTree(FragmentDecisionWeights.DEFAULT, null, 0, 0);
        var fixed = new FragmentDecisionTree(
                FragmentDecisionWeights.DEFAULT, null, 0, 0, null, new CacheTimingConfig(15000, 1000000), false);
        for (int i = 0; i < 64; i++) {
            production.recordBodyCost(96);
            fixed.recordBodyCost(96);
        }
        for (int workers : new int[] {7, 15, 23}) {
            for (long contention : new long[] {0, 650000, 1000000}) {
                assertTrue(production.isPlentiful(workers, workers));
                assertFalse(production.willCacheExecute(workers, workers, workers, contention, workers));
                assertEquals(
                        fixed.executionPath(1, 1, workers, workers, workers, contention, workers),
                        production.executionPath(1, 1, workers, workers, workers, contention, workers));
            }
        }
        assertEquals(FragmentControlConfig.ExecutionPath.CACHE, production.executionPath(1, 1, 0, 7, 7, 1000000, 7));
    }

    @Test
    void policyOffUsesConfiguredFixedValuesWithoutReadingLiveInputsOrInstallingHalfLife() {
        for (var timing :
                new CacheTimingConfig[] {new CacheTimingConfig(43000, 7000000), new CacheTimingConfig(0, 29000)}) {
            var policy = spy(new FragmentDecisionTree(FragmentDecisionWeights.DEFAULT, null, 0, 0, null, timing, true));
            var upstream = mock(UpstreamQueue.class);
            when(upstream.getEffectiveContention(123, timing.contentionHalfLifeNanos()))
                    .thenReturn(456L);
            assertEquals(456, ControlPlaneFragment.cacheContention(timing, policy, upstream, 123));
            // Static interception is confined to this test thread; no real parking or timing assertion.
            try (var parks = mockStatic(LockSupport.class)) {
                ControlPlaneFragment.idleCache(timing, policy, upstream, 123, 0, -1);
                parks.verify(() -> LockSupport.parkNanos(timing.cacheParkNs()));
                parks.verifyNoMoreInteractions();
            }
            verify(policy).cachePark();
            verify(policy, never()).smoothedBodyCostNs();
            verify(upstream).getEffectiveContention(123, timing.contentionHalfLifeNanos());
            verifyNoMoreInteractions(upstream);
        }
    }
}
