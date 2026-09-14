package io.euhedral_execution.benchmarks.cfd.geometry;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.*;
import io.euhedral_execution.benchmarks.cfd.frames.CfdFrame;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
class GeometryFramesTest {
    private ControlPlaneLattice lattice;

    @BeforeEach
    void start() {
        lattice = CfdTestRuntime.singleWorker();
    }

    @AfterEach
    void stop() {
        lattice.close();
    }

    private CfdConfiguration config(int sources, int brick, long deadline) throws Exception {
        return ConfigLoader.load(
                Path.of("scenes/stl-obstacle.json"),
                List.of(
                        "execution.geometrySources=" + sources,
                        "execution.geometryDeadlineMillis=" + deadline,
                        "execution.brick={\"nx\":" + brick + ",\"ny\":" + brick + ",\"nz\":" + brick + "}"));
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - end >= 0) fail("missing geometry completion");
            LockSupport.parkNanos(10_000);
        }
    }

    @Test
    void frameManagerReusesBoundsScratchAndClearsSuccessCancellationAndFailure() throws Exception {
        var config = config(1, 1, 30_000);
        var mesh = new TriangleMesh(config.meshes().getFirst(), config.physics());
        var shape = config.config().grid();
        int[] ids = new int[(int) shape.cellCount()];
        int[] bounds = {0, shape.nx(), 0, shape.ny(), 0, shape.nz()};
        var manager = new FrameManager<TriangleMesh, GeometryRangeFrame>(8, 73);
        var sink = new QueueIngestSink(new PartitionedMpscQueue<>(16));
        lattice.addUpstream(sink);
        try {
            FrameFactory.FrameReplace<TriangleMesh, GeometryRangeFrame> replace = (m, f) -> f.paint(
                    m,
                    shape,
                    ids,
                    1,
                    bounds[0],
                    bounds[1],
                    bounds[2],
                    bounds[3],
                    bounds[4],
                    bounds[5],
                    System.nanoTime(),
                    TimeUnit.SECONDS.toNanos(5));
            manager.setFactory(new FrameFactory<>(
                    (id, m) -> {
                        var f = new GeometryRangeFrame(id, manager);
                        replace.replace(m, f);
                        return f;
                    },
                    replace));
            var original = manager.getOrCreate(mesh, 73);
            for (int pass = 0; pass < 4; pass++) {
                var frame = pass == 0 ? original : manager.getOrCreate(mesh, 73);
                assertSame(original, frame);
                assertThrows(IllegalStateException.class, () -> replace.replace(mesh, frame));
                if (pass == 1) frame.kill();
                assertTrue(sink.offer(frame));
                await(frame::isDone);
                if (pass == 1) {
                    assertEquals(CfdFrame.Status.CANCELLED, frame.status());
                    assertThrows(RuntimeException.class, frame::surfaceCells);
                } else {
                    frame.requireSuccess();
                    assertTrue(frame.surfaceCells() + frame.interiorCells() > 0);
                }
                await(() -> manager.getRecycleQueue().peek() == frame);
                int[] smaller = {4, 6, 3, 5, 3, 5};
                System.arraycopy(smaller, 0, bounds, 0, 6);
            }
            var failed = manager.getOrCreate(mesh, 73);
            /// Cancel through the executor first so this deliberately expired replacement is out of flight.
            failed.kill();
            assertTrue(sink.offer(failed));
            await(failed::isDone);
            await(() -> manager.getRecycleQueue().peek() == failed);
            assertSame(failed, manager.get(73));
            failed.validate(mesh, 0, mesh.info.triangles(), System.nanoTime() - 1_000_000, 1);
            assertTrue(sink.offer(failed));
            await(failed::isDone);
            assertEquals(CfdFrame.Status.FAILED, failed.status());
            assertTrue(assertThrows(
                            io.euhedral_execution.benchmarks.cfd.solver.SimulationException.class,
                            failed::requireSuccess)
                    .getMessage()
                    .contains("deadline"));
            await(() -> manager.getRecycleQueue().peek() == failed);
            var recovered = manager.getOrCreate(mesh, 73);
            assertSame(original, recovered);
            assertTrue(sink.offer(recovered));
            await(recovered::isDone);
            recovered.requireSuccess();
            await(() -> manager.getRecycleQueue().peek() == recovered);
            assertEquals(8, recovered.surfaceCells() + recovered.interiorCells());
        } finally {
            sink.complete();
            manager.close();
        }
    }

    @Test
    void manyReusedRangesAndSourcesMatchOneWholeRange() throws Exception {
        var expected = GeometryMask.resolve(config(1, 32, 30_000), lattice);
        var partitioned = GeometryMask.resolve(config(2, 1, 30_000), lattice);
        assertEquals(expected.fluidCells(), partitioned.fluidCells());
        assertEquals(expected.voxelization(), partitioned.voxelization());
        for (int i = 0; i < expected.shape().cellCount(); i++)
            assertEquals(expected.obstacleId(i), partitioned.obstacleId(i));
    }

    @Test
    void workerValidationFailureNeverReturnsAMaskAndLeavesLatticeUsable() throws Exception {
        var config = config(2, 1, 30_000);
        var mesh = new TriangleMesh(config.meshes().getFirst(), config.physics(), true);
        /// Force a duplicate triangle after the serial topology setup, so failure occurs in a worker.
        System.arraycopy(mesh.vertices, 0, mesh.vertices, 3, 3);
        try (var work = new GeometryWork(config, lattice)) {
            work.beginStage();
            work.next().validate(mesh, 0, mesh.info.triangles(), work.started(), work.timeout());
            work.submit();
            assertTrue(assertThrows(IllegalArgumentException.class, work::finishStage)
                    .getMessage()
                    .contains("duplicate triangle"));
        }
        assertTrue(GeometryMask.resolve(config, lattice).fluidCells() > 0);
    }

    @Test
    void queuedStageDeadlineCancelsPendingFramesBeforeReturning() throws Exception {
        var config = config(1, 1, 30_000);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blockerSink = new QueueIngestSink(new PartitionedMpscQueue<>(16));
        lattice.addUpstream(blockerSink);
        assertTrue(blockerSink.offer(new AbstractFrame(100) {
            @Override
            public void execute() {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("blocker timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var failure = new AtomicReference<Throwable>();
        var stageFailed = new CountDownLatch(1);
        var clock = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        var builder = new Thread(
                () -> {
                    try (var work = new GeometryWork(config, lattice, clock::get)) {
                        var mesh = new TriangleMesh(config.meshes().getFirst(), config.physics(), true);
                        work.beginStage();
                        work.next().validate(mesh, 0, mesh.info.triangles(), work.started(), work.timeout());
                        work.submit();
                        clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
                        try {
                            work.finishStage();
                        } catch (RuntimeException e) {
                            failure.set(e);
                            stageFailed.countDown();
                            throw e;
                        }
                    } catch (RuntimeException e) {
                        failure.set(e);
                    }
                },
                "geometry-deadline-test");
        try {
            builder.start();
            assertTrue(stageFailed.await(5, TimeUnit.SECONDS));
            assertTrue(builder.isAlive(), "close must wait for queued cancellation acknowledgement");
            release.countDown();
            builder.join(5000);
            assertFalse(builder.isAlive());
            assertTrue(
                    failure.get().getMessage().contains("deadline"),
                    failure.get().toString());
        } finally {
            release.countDown();
            builder.join(5000);
            blockerSink.complete();
        }
        assertTrue(GeometryMask.resolve(config(1, 1, 30_000), lattice).fluidCells() > 0);
    }

    @Test
    @Tag("integration")
    void twoWorkersProduceTheSameMaskWithOneOrMultipleSources() throws Exception {
        var expected = GeometryMask.resolve(config(1, 1, 30_000), lattice);
        lattice.close();
        lattice = CfdTestRuntime.upToTwoWorkers();
        lattice.start();
        Assumptions.assumeTrue(lattice.getActiveWorkers() >= 2, "requires two available physical workers");
        for (int sources : new int[] {1, 2}) {
            var actual = GeometryMask.resolve(config(sources, 1, 30_000), lattice);
            assertEquals(expected.voxelization(), actual.voxelization());
            for (int i = 0; i < expected.shape().cellCount(); i++)
                assertEquals(expected.obstacleId(i), actual.obstacleId(i));
        }
    }
}
