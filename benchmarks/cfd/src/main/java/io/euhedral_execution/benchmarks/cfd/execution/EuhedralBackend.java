package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/// Persistent independent sources; Euhedral owns placement and execution with its default executor.
public final class EuhedralBackend extends RangeBackend {
    private final ControlPlaneLattice lattice;
    private final boolean parallel, ownsLattice;
    private final QueueIngestSink[] sinks;
    private int submitted;
    private boolean inFlight;

    public EuhedralBackend(
            ControlPlaneLattice lattice, boolean parallel, int sources, boolean ownsLattice, long shutdownMillis) {
        this(lattice, parallel, createSinks(sources, parallel), ownsLattice, shutdownMillis);
    }

    EuhedralBackend(
            ControlPlaneLattice lattice,
            boolean parallel,
            QueueIngestSink[] sinks,
            boolean ownsLattice,
            long shutdownMillis) {
        super(shutdownMillis);
        if (sinks.length == 0 || (!parallel && sinks.length != 1)) {
            throw new IllegalArgumentException("sources must be positive; serial requires one source");
        }
        this.lattice = Objects.requireNonNull(lattice);
        this.parallel = parallel;
        this.ownsLattice = ownsLattice;
        this.sinks = sinks.clone();
    }

    private static QueueIngestSink[] createSinks(int sources, boolean parallel) {
        if (sources <= 0 || (!parallel && sources != 1)) {
            throw new IllegalArgumentException("sources must be positive; serial requires one source");
        }
        var sinks = new QueueIngestSink[sources];
        /// One partition preserves insertion order within each source and bounds setup storage.
        for (int i = 0; i < sources; i++) {
            sinks[i] = new QueueIngestSink(new PartitionedMpscQueue<>(1, 64));
        }
        return sinks;
    }

    @Override
    public void prepare(CfdRangeFrame[] ranges) {
        super.prepare(ranges);
        for (var sink : sinks) {
            lattice.addUpstream(sink);
        }
    }

    @Override
    protected void dispatch(StepContext context) {
        submitted = 0;
        inFlight = true;
        for (var frame : ranges) {
            if (parallel) {
                frame.randomizeHash(frame.rangeId() + 1L);
            }
            check(context);
            /// Keep both the frame and sink unchanged on retry; successful offers advance the cursor.
            var sink = sinks[submitted % sinks.length];
            while (!sink.offer(frame)) {
                check(context);
                LockSupport.parkNanos(10_000);
            }
            submitted++;
        }
    }

    @Override
    protected void finishGeneration(StepContext context) {
        inFlight = false;
    }

    @Override
    public void cancel() {
        super.cancel();
        /// Frames never submitted are exclusively driver-owned and need no worker acknowledgment.
        if (inFlight) {
            for (int i = submitted; i < ranges.length; i++) {
                ranges[i].doFinally();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancel();
        try {
            if (inFlight) {
                quiesce();
            }
        } finally {
            for (var sink : sinks) {
                sink.complete();
            }
            if (ownsLattice) {
                lattice.close();
            }
        }
    }
}
