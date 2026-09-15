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
            var plan = new RangePlan(geometry, config.config().execution().brick());
            for (var frame : ranges) {
                int ordinal = frame.rangeId();
                assertEquals(frame.xFrom(), plan.xFrom(ordinal));
                assertEquals(frame.xTo(), plan.xTo(ordinal));
                assertEquals(frame.yFrom(), plan.yFrom(ordinal));
                assertEquals(frame.yTo(), plan.yTo(ordinal));
                assertEquals(frame.zFrom(), plan.zFrom(ordinal));
                assertEquals(frame.zTo(), plan.zTo(ordinal));
                assertTrue(plan.hasFluid(ordinal));
            }
            assertFalse(plan.hasFluid(0));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"32,512", "16,4096", "8,32768", "4,262144", "2,2097152", "1,16777216"
    })
    void everyPositiveGranularityHasCompleteOrdinalCoverage(int side, long expected) throws Exception {
        var brick = new io.euhedral_execution.benchmarks.cfd.config.GridShape(side, side, side);
        assertEquals(
                expected, new io.euhedral_execution.benchmarks.cfd.config.GridShape(256, 256, 256).brickCount(brick));
        var config = ConfigLoader.load(
                Path.of("scenes/periodic-smoke.json"), List.of("grid.nx=31", "grid.ny=17", "grid.nz=9"));
        var lattice = CfdTestRuntime.singleWorker();
        try (AutoCloseable runtime = lattice::close) {
            var plan = new RangePlan(GeometryMask.resolve(config, lattice), brick);
            var shape = config.config().grid();
            int[] coverage = new int[Math.toIntExact(shape.cellCount())];
            for (int ordinal = 0; ordinal < plan.count(); ordinal++) {
                for (int z = plan.zFrom(ordinal); z < plan.zTo(ordinal); z++) {
                    for (int y = plan.yFrom(ordinal); y < plan.yTo(ordinal); y++) {
                        for (int x = plan.xFrom(ordinal); x < plan.xTo(ordinal); x++) {
                            coverage[x + shape.nx() * (y + shape.ny() * z)]++;
                        }
                    }
                }
            }
            for (int count : coverage) {
                assertEquals(1, count);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 16, 64, 256, 1024, Integer.MAX_VALUE})
    void batchesCoverEveryBrickOnceIncludingPartialEdges(int bricksPerFrame) throws Exception {
        var config = ConfigLoader.load(
                Path.of("scenes/periodic-smoke.json"), List.of("grid.nx=17", "grid.ny=9", "grid.nz=7"));
        var lattice = CfdTestRuntime.singleWorker();
        try (AutoCloseable runtime = lattice::close) {
            var brick = new io.euhedral_execution.benchmarks.cfd.config.GridShape(2, 2, 2);
            var plan = new RangePlan(GeometryMask.resolve(config, lattice), brick, bricksPerFrame);
            int next = 0;
            for (int batch = 0; batch < plan.frameCount(); batch++) {
                assertEquals(next, plan.firstBrick(batch));
                assertTrue(plan.bricksInFrame(batch) > 0);
                assertTrue(plan.bricksInFrame(batch) <= bricksPerFrame);
                next += plan.bricksInFrame(batch);
            }
            assertEquals(plan.count(), next);
            assertThrows(IllegalArgumentException.class, () -> plan.firstBrick(plan.frameCount()));
            assertThrows(IllegalArgumentException.class, () -> new RangePlan(plan.geometry(), brick, 0));
            assertEquals(
                    1024,
                    new io.euhedral_execution.benchmarks.cfd.config.GridShape(128, 128, 128).frameCount(brick, 256));
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
