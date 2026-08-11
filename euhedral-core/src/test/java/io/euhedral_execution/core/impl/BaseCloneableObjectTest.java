package io.euhedral_execution.core.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
class BaseCloneableObjectTest {

    /// Releases any temporary pinned executor used during clone allocation.
    @AfterEach
    void closeExecutors() {
        PinnedThreadExecutor.closeAll();
    }

    @Test
    void clonedPipelineConnectsBodyRecorderBeforeStartOrInput() {
        AtomicReference<CloningExecutor> clonedExecutor = new AtomicReference<>();
        BaseCloneableObject prototype = new BaseCloneableObject(new CloningExecutor(-1, clonedExecutor));
        CloneConfig cloneConfig = cloneConfig();

        BaseCloneableObject clone = prototype.clone(cloneConfig);

        try {
            CloningExecutor executor = clonedExecutor.get();
            assertNotNull(executor);
            assertThrows(IllegalStateException.class, () -> executor.attachProductionBodyTimingRecorder(ignored -> {}));
        } finally {
            clone.close();
            prototype.close();
        }
    }

    /// Returns a one-worker clone configuration for a process-visible logical CPU.
    private static CloneConfig cloneConfig() {
        int cpu = SystemInfo.getCpuSet().nextSetBit(0);
        if (cpu < 0) {
            throw new IllegalStateException("No CPU is available for the unit test");
        }
        BitSet cpus = new BitSet();
        cpus.set(cpu);
        return new CloneConfig("base-clone-test", SystemInfo.getCpuInfo(cpu).core(), cpus);
    }

    private static final class CloningExecutor extends AbstractExecutor {

        private final AtomicReference<CloningExecutor> clonedExecutor;

        /// Creates a prototype or clone that exposes the exact cloned executor to the test owner.
        private CloningExecutor(int cpu, AtomicReference<CloningExecutor> clonedExecutor) {
            super(cpu);
            this.clonedExecutor = clonedExecutor;
            if (cpu >= 0) {
                clonedExecutor.set(this);
            }
        }

        @Override
        public void execute(AbstractFrame frame) {}

        @Override
        public AbstractExecutor hookOnClone(int cpu) {
            return new CloningExecutor(cpu, this.clonedExecutor);
        }
    }
}
