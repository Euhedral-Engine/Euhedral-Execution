package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.solver.FlowDiagnostics;
import io.euhedral_execution.benchmarks.cfd.solver.RangeResults;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;

/// Persistent serialized sources expose published logical work on demand. Euhedral owns all placement.
public final class EuhedralBackend implements ExecutionBackend {
    private static final LatticeReceiver TERMINATED = new LatticeReceiver() {
        @Override
        public void addUpstream(LatticeSource source) {
            source.complete();
        }

        @Override
        public void push(AbstractFrame frame) {
            throw new IllegalStateException("source completed");
        }

        @Override
        public void onComplete() {}

        @Override
        public void onError(Throwable error) {}
    };

    private final ControlPlaneLattice lattice;
    private final boolean parallel, ownsLattice;
    private final Source[] sources;
    private final long shutdownNs;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private RangePlan plan;
    private RangeResults results;
    private volatile StepContext active;
    private volatile boolean cancelled;
    private boolean closed;

    public EuhedralBackend(
            ControlPlaneLattice lattice, boolean parallel, int sources, boolean ownsLattice, long shutdownMillis) {
        if (sources <= 0 || (!parallel && sources != 1) || shutdownMillis <= 0) {
            throw new IllegalArgumentException("positive sources and timeout required; serial requires one source");
        }
        this.lattice = Objects.requireNonNull(lattice);
        this.parallel = parallel;
        this.ownsLattice = ownsLattice;
        this.shutdownNs = Math.multiplyExact(shutdownMillis, 1_000_000L);
        this.sources = new Source[sources];
        for (int i = 0; i < sources; i++) {
            this.sources[i] = new Source(i);
        }
    }

    /// Recycler capacity limits retained idle frames, never logical work or creation on a miss.
    public static long sourceStorageBytes(long maximumRanges, int sources) {
        if (maximumRanges < 0 || sources < 0) {
            throw new IllegalArgumentException("invalid source storage dimensions");
        }
        return Math.addExact(
                Math.multiplyExact(sources, 8192L * 16 + 4096),
                Math.multiplyExact(Math.min(maximumRanges, Math.multiplyExact(sources, 8192L)), 768));
    }

    @Override
    public void prepare(RangePlan plan) {
        if (this.plan != null || closed) {
            throw new IllegalStateException("backend already prepared or closed");
        }
        this.plan = Objects.requireNonNull(plan);
        results = new RangeResults(plan.count(), plan.geometry());
        for (var source : sources) {
            lattice.addUpstream(source);
        }
    }

    @Override
    public void execute(StepContext context, FlowDiagnostics diagnostics) {
        if (plan == null || closed || cancelled || active != null) {
            throw new IllegalStateException("backend unavailable");
        }
        for (var source : sources) {
            source.target =
                    source.completed.get() + (plan.count() + (long) sources.length - 1 - source.index) / sources.length;
        }
        /// Release publishes context and targets. No per-range preparation occurs on the driver.
        active = context;
        try {
            for (var source : sources) {
                while (source.completed.get() != source.target) {
                    check(context);
                    LockSupport.parkNanos(10_000);
                }
            }
            active = null;
            quiesce();
            check(context);
            diagnostics.reduce(context.step(), results);
        } catch (RuntimeException | Error error) {
            cancel();
            try {
                quiesce();
            } catch (RuntimeException secondary) {
                error.addSuppressed(secondary);
            }
            throw error;
        }
    }

    private void check(StepContext context) {
        var error = failure.get();
        if (error != null) {
            throw error;
        }
        if (cancelled) {
            throw new io.euhedral_execution.benchmarks.cfd.solver.SimulationException(
                    context.step(), 0, 0, 0, "generation cancelled");
        }
        context.checkProgress(0, 0, 0);
    }

    @Override
    public void cancel() {
        cancelled = true;
        active = null;
    }

    /// Freeze publication before entering. Busy is a driver/source ownership boundary, not a source lock.
    /// A late entrant observes active == null and cannot touch cursors, pending frames or issued counts.
    private void quiesce() {
        long start = System.nanoTime();
        boolean interrupted = Thread.interrupted();
        try {
            for (var source : sources) {
                while (source.busy) {
                    awaitQuiescence(start);
                    interrupted |= Thread.interrupted();
                }
                if (source.pending != null) {
                    source.pending.kill();
                    source.pending.doFinally();
                    source.pending = null;
                }
                while (source.completed.get() != source.issued) {
                    awaitQuiescence(start);
                    interrupted |= Thread.interrupted();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitQuiescence(long start) {
        if (System.nanoTime() - start >= shutdownNs) {
            throw new IllegalStateException("CFD sources/workers did not quiesce; buffers retained");
        }
        LockSupport.parkNanos(10_000);
    }

    /// Read only at the driver barrier, after execute has returned.
    public long framesCreated() {
        long count = 0;
        for (var source : sources) {
            count += source.created;
        }
        return count;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancel();
        try {
            quiesce();
        } finally {
            for (var source : sources) {
                source.complete();
            }
            if (ownsLattice) {
                lattice.close();
            }
        }
    }

    private final class Source implements CfdRangeFrame.Completion, LatticeSource {
        private final int index;
        /// Only registration/close crosses the driver boundary; demand is a call-local value.
        private final AtomicReference<LatticeReceiver> downstream = new AtomicReference<>();
        private LatticeReceiver forwarding;
        private final Consumer<AbstractFrame> forward = frame -> forwarding.push(frame);
        private final FrameManager<Source, CfdRangeFrame> manager = new FrameManager<>(8192, 0);
        private final PaddedAtomicLong completed = new PaddedAtomicLong(0);
        /// These fields have one source-handle owner. The driver reads them only after freezing and acquiring busy.
        private StepContext context;
        private long cursor, issued, created;
        private CfdRangeFrame pending;
        private volatile boolean busy;
        private long target;

        Source(int index) {
            this.index = index;
            manager.setFactory(new FrameFactory<>(
                    (id, source) -> {
                        var frame = new CfdRangeFrame(id, source.manager);
                        frame.completion(source);
                        source.created++;
                        source.replace(frame);
                        if (parallel) {
                            frame.randomizeHash(1);
                        }
                        return frame;
                    },
                    (source, frame) -> source.replace(frame)));
        }

        private void replace(CfdRangeFrame frame) {
            plan.replace(frame, context, (int) cursor);
        }

        @Override
        public boolean isAlive() {
            return !cancelled;
        }

        @Override
        public void complete(CfdRangeFrame frame, RuntimeException error) {
            try {
                if (error == null) {
                    frame.copyResults(results);
                } else {
                    failure.compareAndSet(null, error);
                    cancelled = true;
                }
            } catch (RuntimeException recordFailure) {
                failure.compareAndSet(null, recordFailure);
                cancelled = true;
            } finally {
                /// The release sequence publishes disjoint result slots and numerical writes to the driver.
                completed.incrementAndGet();
            }
        }

        @Override
        public void addDownstream(LatticeReceiver receiver) {
            if (!downstream.compareAndSet(null, Objects.requireNonNull(receiver))) {
                receiver.onError(new IllegalStateException("source already registered or completed"));
            }
        }

        @Override
        public void complete() {
            var receiver = downstream.getAndSet(TERMINATED);
            if (receiver != null && receiver != TERMINATED) {
                receiver.onComplete();
            }
        }

        @Override
        public boolean isComplete() {
            return downstream.get() == TERMINATED;
        }

        @Override
        public long pull(Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stop, long limit) {
            var receiver = downstream.get();
            if (limit <= 0 || receiver == null || receiver == TERMINATED) {
                return 0;
            }
            return emit(consumer, stop, limit);
        }

        @Override
        public void request(long limit) {
            var receiver = downstream.get();
            if (limit <= 0 || receiver == null || receiver == TERMINATED) {
                return;
            }
            /// Requests are serialized and bounded by this call; empty generations accrue no credit.
            forwarding = receiver;
            emit(forward, null, limit);
        }

        private long emit(Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stop, long limit) {
            busy = true;
            try {
                var published = active;
                if (published == null || cancelled) {
                    return 0;
                }
                if (context != published) {
                    context = published;
                    cursor = index;
                }
                long emitted = 0;
                while (emitted < limit && !cancelled && (pending != null || cursor < plan.count())) {
                    if (pending == null) {
                        if (!plan.hasFluid((int) cursor)) {
                            cursor += sources.length;
                            issued++;
                            completed.incrementAndGet();
                            continue;
                        }
                        pending = manager.getOrCreate(Source.this, 0);
                        cursor += sources.length;
                        issued++;
                    }
                    if (stop != null && stop.apply(pending)) {
                        break;
                    }
                    var frame = pending;
                    pending = null;
                    consumer.accept(frame);
                    emitted++;
                }
                return emitted;
            } catch (RuntimeException error) {
                failure.compareAndSet(null, error);
                cancelled = true;
                return 0;
            } finally {
                busy = false;
            }
        }
    }
}
