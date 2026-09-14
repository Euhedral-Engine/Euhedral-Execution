package io.euhedral_execution.benchmarks.cfd.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.frames.CfdFrame;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import io.euhedral_execution.benchmarks.cfd.solver.D3Q19;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
class RangePlanTest {
    @Test
    void partialAndSolidBricksCoverEveryFluidCellExactlyOnce() throws Exception {
        var config = ConfigLoader.load(
                Path.of("scenes/periodic-smoke.json"),
                List.of(
                        "grid.nx=11",
                        "grid.ny=7",
                        "grid.nz=5",
                        "execution.brick.nx=3",
                        "execution.brick.ny=3",
                        "execution.brick.nz=3",
                        "geometry.boxes=[{\"id\":1,\"min\":{\"x\":0,\"y\":0,\"z\":0},\"max\":{\"x\":3,\"y\":3,\"z\":3}}]"));
        var lattice = CfdTestRuntime.singleWorker();
        try (AutoCloseable runtime = lattice::close) {
            var geometry = GeometryMask.resolve(config, lattice);
            var shape = config.config().grid();
            var ranges = RangePlan.create(geometry, config.config().execution().brick());
            assertEquals(shape.brickCount(config.config().execution().brick()) - 1, ranges.length);
            assertEquals(1, ranges[0].rangeId());
            int cells = Math.toIntExact(shape.cellCount());
            int[] coverage = new int[cells];
            double[][] current = new double[19][cells], next = new double[19][cells];
            for (int q = 0; q < 19; q++) {
                Arrays.fill(current[q], D3Q19.equilibrium(q, 1, 0.01, 0, 0));
                Arrays.fill(next[q], Double.NaN);
            }
            var context = new StepContext(
                    shape,
                    current,
                    next,
                    1.25,
                    1,
                    0,
                    Long.MAX_VALUE,
                    () -> 0,
                    geometry,
                    config.physics().acceleration(),
                    1,
                    config.config().physics().guards());
            for (var frame : ranges) {
                for (int z = frame.zFrom(); z < frame.zTo(); z++) {
                    for (int y = frame.yFrom(); y < frame.yTo(); y++) {
                        for (int x = frame.xFrom(); x < frame.xTo(); x++) {
                            coverage[x + shape.nx() * (y + shape.ny() * z)]++;
                        }
                    }
                }
                frame.replace(context);
                RangeBackend.runFrame(frame);
                frame.requireSuccess();
            }
            for (int i = 0; i < cells; i++) {
                if (!geometry.isSolid(i)) {
                    assertEquals(1, coverage[i]);
                    for (int q = 0; q < 19; q++) {
                        assertTrue(Double.isFinite(next[q][i]));
                    }
                }
            }
            try (var backend = new ForkJoinBackend(new int[] {0}, false, 1000)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> backend.prepare(
                                new io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame[] {ranges[0], ranges[0]
                                }));
            }
        }
    }

    @Test
    void fatalErrorInTerminalPublicationStillAcknowledgesFailure() {
        var frame = new CfdFrame(1, null) {
            {
                beginPreparation();
                ready();
            }

            @Override
            protected void executeBody() {}

            @Override
            protected void publishSuccess() {
                throw new AssertionError("terminal failure");
            }

            @Override
            protected long generation() {
                return 1;
            }
        };
        frame.execute();
        assertThrows(AssertionError.class, frame::doFinally);
        assertTrue(frame.isDone());
        assertEquals(CfdFrame.Status.FAILED, frame.status());
        var error = assertThrows(RuntimeException.class, frame::requireSuccess);
        assertInstanceOf(AssertionError.class, error.getCause());
    }
}
