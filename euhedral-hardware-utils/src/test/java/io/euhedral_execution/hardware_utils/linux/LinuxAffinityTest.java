package io.euhedral_execution.hardware_utils.linux;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.AffinityCapability;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.AffinityController;
import io.euhedral_execution.hardware_utils.internal.AffinityProvider;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class LinuxAffinityTest {

    @Test
    void validatesBeforeOneRawCall() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<long[]> seen = new AtomicReference<>();
        assertFalse(LinuxAffinityCalls.apply(new long[0], mask -> calls.incrementAndGet()));
        assertTrue(LinuxAffinityCalls.apply(new long[] {1}, mask -> {
            calls.incrementAndGet();
            seen.set(mask.clone());
            mask[0] = 0;
            return 0;
        }));
        assertArrayEquals(new long[] {1}, seen.get());
        assertFalse(LinuxAffinityCalls.apply(new long[] {1}, mask -> {
            calls.incrementAndGet();
            return 1;
        }));
        assertFalse(LinuxAffinityCalls.apply(new long[] {1}, mask -> {
            throw new IllegalStateException("configured failure");
        }));
        assertFalse(LinuxAffinityCalls.apply(new long[] {1}, mask -> {
            throw new UnsatisfiedLinkError("configured failure");
        }));
        assertEquals(2, calls.get());
    }

    @Test
    void timerResolutionRejectsNegative() {
        if (LinuxAffinity.INSTANCE != null) {
            assertThrows(RuntimeException.class, () -> LinuxAffinity.INSTANCE.setTimerResolution(-500L));
        }
    }

    @Test
    void linuxAffinityInstanceContract() {
        if (OSName.isLinux()) {
            assertNotNull(LinuxAffinity.INSTANCE);
            if (LinuxAffinity.INSTANCE.capability() == AffinityCapability.EXACT) {
                long[] captured = LinuxAffinity.INSTANCE.captureAffinity();
                assertNotNull(captured);
                assertTrue(captured.length > 0);
                assertTrue(LinuxAffinity.INSTANCE.applyExact(captured));
                assertTrue(LinuxAffinity.INSTANCE.restoreExact(captured));
                assertTrue(LinuxAffinity.INSTANCE.setTimerResolution(100_000L));
                assertTrue(LinuxAffinity.INSTANCE.getCpu() >= 0 || LinuxAffinity.INSTANCE.getCpu() == -1);
            }
        }
    }

    @Test
    void affinityControllerLeaseCaptureAndRestoration() {
        BitSet supported = new BitSet();
        supported.set(0, Math.min(64, SystemInfo.getCpuCount()));
        int span = SystemInfo.getCpuCount();

        AtomicReference<long[]> activeMask = new AtomicReference<>(new long[] {1L});
        AtomicInteger applyCount = new AtomicInteger();
        AtomicInteger restoreCount = new AtomicInteger();

        AffinityProvider mockProvider = new LinuxAffinityProxy(
                AffinityCapability.EXACT,
                () -> activeMask.get().clone(),
                mask -> {
                    applyCount.incrementAndGet();
                    activeMask.set(mask.clone());
                    return true;
                },
                mask -> {
                    restoreCount.incrementAndGet();
                    activeMask.set(mask.clone());
                    return true;
                });

        AffinityController controller = new AffinityController(mockProvider, supported, span, null);
        assertEquals(AffinityCapability.EXACT, controller.capability());
        assertFalse(controller.hasAffinityLease());

        assertTrue(controller.setAffinity(new long[] {1L}));
        assertTrue(controller.hasAffinityLease());
        assertEquals(1, applyCount.get());

        controller.releaseAffinity();
        assertFalse(controller.hasAffinityLease());
        assertEquals(1, restoreCount.get());
    }

    private static final class LinuxAffinityProxy
            implements io.euhedral_execution.hardware_utils.internal.AffinityProvider {

        private final AffinityCapability capability;
        private final Supplier<long[]> captureSupplier;
        private final Function<long[], Boolean> applyFunction;
        private final Function<long[], Boolean> restoreFunction;

        LinuxAffinityProxy(
                AffinityCapability capability,
                Supplier<long[]> captureSupplier,
                Function<long[], Boolean> applyFunction,
                Function<long[], Boolean> restoreFunction) {
            this.capability = capability;
            this.captureSupplier = captureSupplier;
            this.applyFunction = applyFunction;
            this.restoreFunction = restoreFunction;
        }

        @Override
        public AffinityCapability capability() {
            return capability;
        }

        @Override
        public long[] captureAffinity() {
            return captureSupplier.get();
        }

        @Override
        public boolean applyExact(long[] mask) {
            return applyFunction.apply(mask);
        }

        @Override
        public boolean restoreExact(long[] mask) {
            return restoreFunction.apply(mask);
        }

        @Override
        public int currentCpu() {
            return 0;
        }

        @Override
        public boolean setTimerResolution(long nanos) {
            return true;
        }
    }
}
