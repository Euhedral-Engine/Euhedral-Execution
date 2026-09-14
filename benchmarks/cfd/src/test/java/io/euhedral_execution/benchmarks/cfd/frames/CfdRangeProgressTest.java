package io.euhedral_execution.benchmarks.cfd.frames;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.solver.D3Q19;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class CfdRangeProgressTest {
    private final GridShape shape = new GridShape(40, 12, 6);
    private final double[][] current = new double[19][(int) shape.cellCount()];
    private final double[][] next = new double[19][(int) shape.cellCount()];

    private CfdRangeFrame frame(LongSupplier clock) {
        for (int q = 0; q < 19; q++) {
            Arrays.fill(current[q], D3Q19.weight(q));
        }
        var frame = new CfdRangeFrame(1, null);
        frame.replace(new StepContext(shape, current, next, 1.25, 1, 0, 100, clock), 3, 35, 2, 11, 1, 3);
        return frame;
    }

    private long written() {
        return Arrays.stream(next[0]).filter(value -> value != 0).count();
    }

    @Test
    void checkpointsSpanRowsAndPlanesAndIncludeTheFinalPartialBlock() {
        var checkpoints = new ArrayList<Long>();
        var frame = frame(() -> {
            checkpoints.add(written());
            return 0;
        });
        frame.execute();
        frame.doFinally();
        frame.requireSuccess();
        assertEquals(java.util.List.of(0L, 256L, 512L, 576L), checkpoints);
    }

    @Test
    void expiredCheckpointStopsBeforeFurtherDestinationWrites() {
        var checks = new AtomicInteger();
        var frame = frame(() -> checks.incrementAndGet() == 2 ? 100 : 0);
        var error = assertThrows(SimulationException.class, frame::execute);
        frame.doFinallyWithError(error);
        assertEquals(256, written());
        assertEquals(CfdFrame.Status.FAILED, frame.status());
    }

    @Test
    void finalCheckpointCanRejectAnOtherwiseCompletedBody() {
        var checks = new AtomicInteger();
        var frame = frame(() -> checks.incrementAndGet() == 4 ? 100 : 0);
        var error = assertThrows(SimulationException.class, frame::execute);
        frame.doFinallyWithError(error);
        assertEquals(576, written());
        assertThrows(SimulationException.class, frame::requireSuccess);
    }

    @Test
    void interruptionStillStopsAtTheNextCheckpoint() {
        var frame = frame(() -> {
            Thread.currentThread().interrupt();
            return 0;
        });
        try {
            var error = assertThrows(SimulationException.class, frame::execute);
            frame.doFinallyWithError(error);
            assertEquals(256, written());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
