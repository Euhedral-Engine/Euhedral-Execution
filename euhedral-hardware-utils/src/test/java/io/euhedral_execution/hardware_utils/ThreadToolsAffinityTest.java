package io.euhedral_execution.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.internal.AffinityController;
import io.euhedral_execution.hardware_utils.internal.AffinityProvider;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ThreadToolsAffinityTest {

    private static AffinityController controller(FakeProvider provider, BitSet supported, int span) {
        return new AffinityController(provider, supported, span, null);
    }

    private static FakeProvider exact(long[] initialization, long[] perThread) {
        FakeProvider provider = new FakeProvider(AffinityCapability.EXACT);
        provider.capturesToReturn.add(initialization.clone());
        provider.capturesToReturn.add(perThread.clone());
        return provider;
    }

    private static BitSet bits(int... cpus) {
        BitSet set = new BitSet();
        for (int cpu : cpus) {
            set.set(cpu);
        }
        return set;
    }

    @Test
    void discoversAndRestoresTheOriginalMask() {
        FakeProvider provider = exact(new long[] {1, Long.MIN_VALUE}, new long[] {4, Long.MIN_VALUE});
        AffinityController controller = controller(provider, bits(0, 2, 63, 127), 128);

        assertEquals(AffinityCapability.EXACT, controller.capability());
        assertEquals(bits(0, 127), controller.baseMask());
        assertEquals(1, provider.captures);
        assertEquals(0, provider.applies);

        assertTrue(controller.setAffinity(new long[] {4}));
        assertTrue(controller.setAffinity(new long[] {0, Long.MIN_VALUE}));
        assertEquals(2, provider.captures);
        assertEquals(2, provider.applies);
        controller.releaseAffinity();

        assertArrayEquals(new long[] {4, Long.MIN_VALUE}, provider.restored);
        assertEquals(1, provider.restores);
        assertFalse(controller.hasAffinityLease());
    }

    @Test
    void rejectedRequestsMakeZeroPlatformCallsAndRetainBit63() {
        FakeProvider provider = exact(new long[] {1}, new long[] {1});
        AffinityController controller = controller(provider, bits(0, 63, 64, 127), 128);
        int baselineCaptures = provider.captures;

        assertFalse(controller.setAffinity((long[]) null));
        assertFalse(controller.setAffinity(new long[0]));
        assertFalse(controller.setAffinity(new long[] {0}));
        assertFalse(controller.setAffinity(new long[] {2}));
        assertFalse(controller.setAffinity(new long[] {0, 0, 1}));
        assertEquals(baselineCaptures, provider.captures);
        assertEquals(0, provider.applies);

        long[] request = {Long.MIN_VALUE, Long.MIN_VALUE};
        assertTrue(controller.setAffinity(request));
        request[0] = 0;
        assertArrayEquals(new long[] {Long.MIN_VALUE, Long.MIN_VALUE}, provider.applied);
        controller.releaseAffinity();
    }

    @Test
    void everyOverloadUsesOwnedCompleteRequests() {
        FakeProvider provider = exact(new long[] {1}, new long[] {1});
        provider.capturesToReturn.add(new long[] {1});
        provider.capturesToReturn.add(new long[] {1});
        AffinityController controller = controller(provider, bits(0, 63, 64, 127), 128);

        assertFalse(controller.setAffinity(-1));
        assertFalse(controller.setAffinity(128));
        assertFalse(controller.setAffinity(new int[0]));
        assertFalse(controller.setAffinity(new int[] {0, 1}));
        assertFalse(controller.setAffinity(new BitSet()));
        assertEquals(0, provider.applies);

        int[] ids = {127, 0, 127};
        assertTrue(controller.setAffinity(ids));
        ids[0] = 64;
        assertArrayEquals(new long[] {1, Long.MIN_VALUE}, provider.applied);
        controller.releaseAffinity();

        BitSet requested = bits(63, 64);
        assertTrue(controller.setAffinity(requested));
        requested.clear();
        assertArrayEquals(new long[] {Long.MIN_VALUE, 1}, provider.applied);
        controller.releaseAffinity();

        provider.currentCpu = 64;
        assertTrue(controller.setAffinity());
        assertArrayEquals(new long[] {0, 1}, provider.applied);
        controller.releaseAffinity();
    }

    @Test
    void constructorAndMaximumBoundsAreEnforcedWithoutAliasing() {
        BitSet supported = bits(0, 1_048_575);
        FakeProvider provider = new FakeProvider(AffinityCapability.UNSUPPORTED);
        AffinityController controller = controller(provider, supported, 1_048_576);
        supported.clear();
        assertEquals(bits(0, 1_048_575), controller.baseMask());
        assertFalse(controller.setAffinity(new long[16_385]));
        assertThrows(IllegalArgumentException.class, () -> controller(provider, bits(0), 0));
        assertThrows(IllegalArgumentException.class, () -> controller(provider, bits(0), 1_048_577));
        assertThrows(IllegalArgumentException.class, () -> controller(provider, bits(1), 1));
    }

    @Test
    void exactFailuresCleanPendingButPreserveFirstSuccessfulLease() {
        FakeProvider provider = exact(new long[] {1}, new long[] {1});
        provider.capturesToReturn.add(new long[] {1});
        AffinityController controller = controller(provider, bits(0), 1);
        provider.applyResult = false;
        assertFalse(controller.setAffinity(new long[] {1}));
        assertFalse(controller.hasAffinityLease());

        provider.applyResult = true;
        assertTrue(controller.setAffinity(new long[] {1}));
        provider.applyResult = false;
        assertFalse(controller.setAffinity(new long[] {1}));
        assertTrue(controller.hasAffinityLease());
        provider.restoreResult = false;
        controller.releaseAffinity();
        assertFalse(controller.hasAffinityLease());
    }

    @Test
    void recoverableAndFatalApplyFailuresCleanNewPendingLeases() {
        FakeProvider provider = exact(new long[] {1}, new long[] {1});
        provider.capturesToReturn.add(new long[] {1});
        AffinityController controller = controller(provider, bits(0), 1);

        provider.applyLinkageFailure = true;
        assertFalse(controller.setAffinity(new long[] {1}));
        assertFalse(controller.hasAffinityLease());

        provider.applyLinkageFailure = false;
        provider.applyFatalFailure = true;
        assertThrows(OutOfMemoryError.class, () -> controller.setAffinity(new long[] {1}));
        assertFalse(controller.hasAffinityLease());
    }

    @Test
    void localityResolvesWholeRequestAndReleasesTagZeroObligation() {
        FakeProvider provider = new FakeProvider(AffinityCapability.LOCALITY_HINT);
        provider.localities.put(0, 7);
        provider.localities.put(1, 7);
        provider.localities.put(2, 8);
        AffinityController controller = controller(provider, bits(0, 1, 2), 3);

        assertTrue(controller.setAffinity(new long[] {3}));
        assertEquals(1, provider.localityApplies);
        assertEquals(7, provider.appliedLocality);
        assertFalse(controller.setAffinity(new long[] {5}));
        assertEquals(1, provider.localityApplies);
        controller.releaseAffinity();
        assertEquals(1, provider.localityReleases);
        assertFalse(controller.hasAffinityLease());
    }

    @Test
    void exactCrossWordApplyIsAtomicAndLocalityMissingMappingMakesZeroCalls() {
        FakeProvider exact = exact(new long[] {1}, new long[] {1});
        AffinityController exactController = controller(exact, bits(0, 64), 65);
        assertTrue(exactController.setAffinity(new long[] {1, 1}));
        assertArrayEquals(new long[] {1, 1}, exact.applied);
        assertEquals(1, exact.applies);
        exactController.releaseAffinity();

        FakeProvider locality = new FakeProvider(AffinityCapability.LOCALITY_HINT);
        locality.localities.put(0, 7);
        AffinityController localityController = controller(locality, bits(0, 64), 65);
        assertFalse(localityController.setAffinity(new long[] {1, 1}));
        assertEquals(0, locality.localityApplies);
        assertFalse(localityController.hasAffinityLease());
    }

    @Test
    void unsupportedAndInvalidExactDiscoveryAreNonDestructive() {
        FakeProvider unsupported = new FakeProvider(AffinityCapability.UNSUPPORTED);
        AffinityController first = controller(unsupported, bits(0, 63), 64);
        assertEquals(AffinityCapability.UNSUPPORTED, first.capability());
        assertEquals(0, unsupported.captures);
        assertFalse(first.setAffinity(new long[] {1}));
        assertEquals(0, unsupported.applies);

        FakeProvider invalid = exact(new long[] {2}, new long[] {1});
        AffinityController second = controller(invalid, bits(0), 1);
        assertEquals(AffinityCapability.UNSUPPORTED, second.capability());
        assertEquals(bits(0), second.baseMask());
        assertEquals(1, invalid.captures);
        assertEquals(0, invalid.applies);
    }

    @Test
    void managedOwnersAreNestedLifoThreadOwnedAndCleaned() throws Exception {
        AffinityController controller = controller(null, bits(0, 2), 3);
        assertThrows(IllegalArgumentException.class, () -> controller.bindManagedCpu(1));
        AffinityController.ManagedOwner outer = controller.bindManagedCpu(0);
        AffinityController.ManagedOwner inner = controller.bindManagedCpu(2);
        assertEquals(2, controller.currentCpu());
        assertThrows(IllegalStateException.class, outer::close);

        AtomicReference<Throwable> wrongThread = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                inner.close();
            } catch (Throwable failure) {
                wrongThread.set(failure);
            }
        });
        thread.start();
        thread.join();
        assertTrue(wrongThread.get() instanceof IllegalStateException);

        inner.close();
        inner.close();
        assertEquals(0, controller.currentCpu());
        outer.close();
        assertEquals(-1, controller.currentCpu());
        assertFalse(controller.hasManagedOwner());
    }

    @Test
    void currentCpuIsIndependentOfMutationCapabilityAndFallsBackToManagedOwner() {
        FakeProvider provider = exact(new long[] {1}, new long[] {1});
        AffinityController controller = controller(provider, bits(0, 2), 3);
        provider.currentCpu = 2;
        assertEquals(2, controller.currentCpu());
        provider.currentCpu = 1;
        assertEquals(-1, controller.currentCpu());
        try (AffinityController.ManagedOwner ignored = controller.bindManagedCpu(0)) {
            assertEquals(0, controller.currentCpu());
            provider.currentLinkageFailure = true;
            assertEquals(0, controller.currentCpu());
            provider.currentLinkageFailure = false;
        }
        assertEquals(-1, controller.currentCpu());

        FakeProvider unsupported = new FakeProvider(AffinityCapability.UNSUPPORTED);
        unsupported.currentCpu = 2;
        AffinityController readOnly = controller(unsupported, bits(0, 2), 3);
        assertEquals(AffinityCapability.UNSUPPORTED, readOnly.capability());
        assertEquals(2, readOnly.currentCpu());
        assertFalse(readOnly.setAffinity(new long[] {4}));
        assertEquals(0, unsupported.applies);

        FakeProvider locality = new FakeProvider(AffinityCapability.LOCALITY_HINT);
        locality.currentCpu = 2;
        assertEquals(2, controller(locality, bits(0, 2), 3).currentCpu());
    }

    private static final class FakeProvider implements AffinityProvider {

        private final AffinityCapability capability;
        private final Deque<long[]> capturesToReturn = new ArrayDeque<>();
        private final Map<Integer, Integer> localities = new HashMap<>();
        private int captures;
        private int applies;
        private int restores;
        private int localityApplies;
        private int localityReleases;
        private int currentCpu = -1;
        private int appliedLocality;
        private boolean applyResult = true;
        private boolean restoreResult = true;
        private boolean applyLinkageFailure;
        private boolean applyFatalFailure;
        private boolean currentLinkageFailure;
        private long[] applied;
        private long[] restored;

        private FakeProvider(AffinityCapability capability) {
            this.capability = capability;
        }

        @Override
        public AffinityCapability capability() {
            return capability;
        }

        @Override
        public int currentCpu() {
            if (currentLinkageFailure) {
                throw new UnsatisfiedLinkError("configured failure");
            }
            return currentCpu;
        }

        @Override
        public long[] captureAffinity() {
            captures++;
            long[] result = capturesToReturn.poll();
            return result == null ? null : result.clone();
        }

        @Override
        public boolean applyExact(long[] mask) {
            if (applyLinkageFailure) {
                throw new UnsatisfiedLinkError("configured failure");
            }
            if (applyFatalFailure) {
                throw new OutOfMemoryError("configured failure");
            }
            applies++;
            applied = mask.clone();
            mask[0] = 0;
            return applyResult;
        }

        @Override
        public boolean restoreExact(long[] mask) {
            restores++;
            restored = mask.clone();
            return restoreResult;
        }

        @Override
        public int localityForCpu(int cpu) {
            return localities.getOrDefault(cpu, -1);
        }

        @Override
        public boolean applyLocality(int locality) {
            localityApplies++;
            appliedLocality = locality;
            return applyResult;
        }

        @Override
        public boolean releaseLocality() {
            localityReleases++;
            return restoreResult;
        }

        @Override
        public boolean setTimerResolution(long nanos) {
            return true;
        }
    }
}
