package io.euhedral_execution.benchmarks.cfd.frames;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.execution.RangePlan;
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
class CfdBatchFrameTest {
    @Test
    void oneLifecycleAndProgressCountdownSpanAllSingleCellBricksAndReuse() throws Exception {
        var config = ConfigLoader.load(
                Path.of("scenes/periodic-smoke.json"), List.of("grid.nx=8", "grid.ny=8", "grid.nz=8"));
        var lattice = CfdTestRuntime.singleWorker();
        try (AutoCloseable runtime = lattice::close) {
            var geometry = GeometryMask.resolve(config, lattice);
            var plan = new RangePlan(geometry, new GridShape(1, 1, 1), 512);
            double[][] current = new double[19][512], next = new double[19][512];
            for (int q = 0; q < 19; q++) {
                Arrays.fill(current[q], D3Q19.equilibrium(q, 1, 0.01, 0, 0));
            }
            int[] checks = {0}, completions = {0}, recycled = {0};
            var frame = new CfdRangeFrame(1, plan, 0);
            frame.completion(new CfdRangeFrame.Completion() {
                public boolean isAlive() {
                    return true;
                }

                public void complete(CfdRangeFrame completed, RuntimeException error) {
                    assertNull(error);
                    completions[0]++;
                }

                public void recycled() {
                    recycled[0]++;
                }
            });
            for (int step = 1; step <= 2; step++) {
                checks[0] = 0;
                for (var direction : next) {
                    Arrays.fill(direction, Double.NaN);
                }
                var context = new StepContext(
                        geometry.shape(),
                        current,
                        next,
                        1.25,
                        step,
                        0,
                        Long.MAX_VALUE,
                        () -> {
                            checks[0]++;
                            return 0;
                        },
                        geometry,
                        config.physics().acceleration(),
                        1,
                        config.config().physics().guards());
                frame.replace(context);
                assertEquals(512, frame.brickCount());
                assertEquals(0, frame.firstBrickOrdinal());
                frame.execute();
                frame.doFinally();
                frame.requireSuccess();
                assertEquals(3, checks[0], "one check per 256 cells and a final check, not per brick");
                assertEquals(step, completions[0]);
                assertEquals(step, recycled[0]);
                for (var direction : next) {
                    for (double value : direction) {
                        assertTrue(Double.isFinite(value), "all destinations must be visited");
                    }
                }
            }
        }
    }
}
