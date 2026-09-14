package io.euhedral_execution.benchmarks.cfd.geometry;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/// A driver-owned bounded window of work for the existing lattice; it creates no executors/threads.
/// A stage barrier acquires each frame's terminal publication before reduction or mesh replacement.
final class GeometryWork implements AutoCloseable {
    private final QueueIngestSink[] sinks;
    private final GeometryRangeFrame[] frames;
    private final long timeout;
    private final LongSupplier clock;
    private int pending, nextSink;
    private long started, seed, surface, interior;
    private boolean closed;

    GeometryWork(CfdConfiguration config, ControlPlaneLattice lattice) {
        this(config, lattice, System::nanoTime);
    }

    GeometryWork(CfdConfiguration config, ControlPlaneLattice lattice, LongSupplier clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
        lattice.start();
        int workers = Math.max(1, lattice.getActiveWorkers());
        Integer requested = config.config().execution().geometrySources();
        int sources = requested == null ? Math.min(64, workers) : requested;
        sinks = new QueueIngestSink[sources];
        frames = new GeometryRangeFrame[Math.min(256, Math.max(sources, workers * 4))];
        timeout = config.config().execution().geometryDeadlineMillis() * 1_000_000;
        for (int i = 0; i < frames.length; i++) frames[i] = new GeometryRangeFrame(i + 1, null);
        try {
            for (int i = 0; i < sinks.length; i++) {
                sinks[i] = new QueueIngestSink(new PartitionedMpscQueue<>(64));
                lattice.addUpstream(sinks[i]);
            }
        } catch (RuntimeException error) {
            for (var sink : sinks) if (sink != null) sink.complete();
            throw error;
        }
    }

    void beginStage() {
        if (closed || pending != 0) throw new IllegalStateException("previous geometry stage is still active");
        started = clock.getAsLong();
        surface = 0;
        interior = 0;
    }

    long started() {
        return started;
    }

    long timeout() {
        return timeout;
    }

    long surfaceCells() {
        return surface;
    }

    long interiorCells() {
        return interior;
    }

    GeometryRangeFrame next() {
        if (closed) throw new IllegalStateException("geometry source is closed");
        if (pending == frames.length) finishStage();
        checkProgress();
        return frames[pending];
    }

    void submit() {
        checkProgress();
        var frame = frames[pending];
        frame.randomizeHash(seed++);
        if (!sinks[nextSink].offer(frame)) throw new IllegalStateException("geometry ingest rejected work");
        pending++;
        nextSink = (nextSink + 1) % sinks.length;
    }

    void finishStage() {
        for (int i = 0; i < pending; i++) {
            var frame = frames[i];
            while (!frame.isDone()) {
                checkProgress();
                LockSupport.parkNanos(10_000);
            }
            frame.requireSuccess();
            surface += frame.surfaceCells();
            interior += frame.interiorCells();
        }
        checkProgress();
        pending = 0;
    }

    private void checkProgress() {
        if (Thread.currentThread().isInterrupted())
            throw new SimulationException(0, 0, 0, 0, "interrupted during geometry preprocessing");
        if (clock.getAsLong() - started >= timeout)
            throw new SimulationException(0, 0, 0, 0, "mesh preprocessing stage deadline exceeded");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (int i = 0; i < pending; i++) frames[i].kill();
        /// Keep sources attached while cancelled work reaches its terminal hook. On a stalled runtime,
        /// abandon this private mask after a bounded wait; never publish or reuse its buffers.
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            for (int i = 0; i < pending; i++)
                while (!frames[i].isDone()) {
                    if (System.nanoTime() - deadline >= 0)
                        throw new IllegalStateException("geometry cancellation did not quiesce within five seconds");
                    LockSupport.parkNanos(10_000);
                }
        } finally {
            for (var sink : sinks) sink.complete();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
