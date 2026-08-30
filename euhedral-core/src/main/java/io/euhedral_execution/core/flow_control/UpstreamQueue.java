package io.euhedral_execution.core.flow_control;

import io.euhedral_execution.core.control_plane.FragmentObserver;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeInterceptor;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.utils.AverageFlow;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.core.utils.MathFunctions;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hashing.HasherApi;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Getter;

/// ## The upstream aggregation and scheduling layer
///
/// `UpstreamQueue` is a thread-local coordination point for upstream sources feeding a
/// [LatticeEdge] graph.
///
/// Each thread owns a single queue instance which aggregates upstream handles and participates in
/// global demand distribution.
///
/// **Core behavior:**
/// - Collect upstream handles per thread
/// - Track active upstream count
/// - Distribute pull demand across all active handles
///
///
/// This avoids global contention by keeping scheduling localized per thread.
public class UpstreamQueue {

    public static final long ACQUIRE_CONTENTION_SCALE = 1_000_000L;
    private static final long MAX_SCALED_FAILURES = Long.MAX_VALUE / ACQUIRE_CONTENTION_SCALE;
    private static final double LN_2 = Math.log(2.0);

    public static final ThreadLocal<UpstreamQueue> UP_QUEUE = new ThreadLocal<>();
    public final int core;
    private final MpscQueue<UpstreamHandle> upstreams;
    private final UpstreamHandle[] buffer = new UpstreamHandle[SystemInfo.getCoreCount()];

    private final PaddedAtomicLong upstreamCount;
    private final AverageFlow acquireContention = new AverageFlow();

    @Getter
    private long contentionEvidenceCount;

    @Getter
    private long lastContentionEvidenceNanos = -1L;

    private long appliedContentionEvidenceCount;
    long cachedUpCount = 0L;
    long nonproductiveCount = 0L;
    private boolean acquireDiagnosticsEnabled;

    @Getter
    private long contentionObservationCount;

    @Getter
    private long lastRawContention = -1L;

    @Getter
    private long lastContentionObservationNs = -1L;

    @Getter
    private long successfulAcquisitionCount;

    @Getter
    private long failedAcquisitionCount;

    @Getter
    private long totalAcquisitionAttempts;

    private long pullBucketTarget = 2_048L;
    private PullBucketDivisionMode pullBucketDivisionMode = PullBucketDivisionMode.FLOOR;
    private FragmentObserver pullConvoyObserver;

    private long seed = ThreadLocalRandom.current().nextLong();
    private int bufferIndex = 0;

    public UpstreamQueue(int core, MpscQueue<UpstreamHandle> upstreams, PaddedAtomicLong upstreamCount) {
        this.core = core;
        this.upstreams = upstreams;
        this.upstreamCount = upstreamCount;
    }

    /// Returns the caller's thread-local queue without changing active-worker registration state.
    public static UpstreamQueue get(MpscQueue<UpstreamHandle>[] upstreams, PaddedAtomicLong upstreamCount) {
        UpstreamQueue queue = UP_QUEUE.get();
        if (queue == null) {
            int core = SystemInfo.getCpuInfo(ThreadTools.getCpu()).core();
            queue = new UpstreamQueue(core, upstreams[core], upstreamCount);
            UP_QUEUE.set(queue);
        }
        return queue;
    }

    protected static long drain(
            UpstreamHandle handle,
            Consumer<AbstractFrame> consumer,
            Function<AbstractFrame, Boolean> stopCondition,
            long demand) {
        if (consumer != null) {
            return handle.pull(consumer, stopCondition, demand);
        }
        handle.request(demand);
        return 0;
    }

    public long getCachedUpCount() {
        if (this.cachedUpCount == 0L) {
            return getTrueUpstreamCount();
        }
        return this.cachedUpCount;
    }

    public long getTrueUpstreamCount() {
        this.cachedUpCount = this.upstreamCount.getAcquire();
        return this.cachedUpCount;
    }

    /// Returns whether this worker has completed an eligible acquisition cycle since reset.
    public boolean hasAcquireContention() {
        return this.acquireContention.initialized();
    }

    /// Returns this worker's fixed-point acquisition EWMA; validity is reported separately.
    public long getContention() {
        if (!this.acquireContention.initialized()) {
            return 0L;
        }
        return this.acquireContention.value();
    }

    /// Returns the contention EWMA decayed by its evidence age.
    public long getEffectiveContention(long nowNs, long halfLifeNanos) {
        if (halfLifeNanos <= 0L) {
            throw new IllegalArgumentException("contention half-life must be positive");
        }
        if (this.contentionEvidenceCount <= 0L) {
            return 0L;
        }
        if (this.contentionEvidenceCount != this.appliedContentionEvidenceCount) {
            this.appliedContentionEvidenceCount = this.contentionEvidenceCount;
            this.lastContentionEvidenceNanos = nowNs;
        }

        long ageNanos = nowNs - this.lastContentionEvidenceNanos;
        return decayContention(this.acquireContention.value(), ageNanos, halfLifeNanos);
    }

    static long decayContention(long storedContention, long ageNanos, long halfLifeNanos) {
        if (halfLifeNanos <= 0L) {
            throw new IllegalArgumentException("contention half-life must be positive");
        }
        long boundedContention = MathFunctions.clampLong(storedContention, 0L, ACQUIRE_CONTENTION_SCALE);
        if (ageNanos <= 0L || boundedContention == 0L) {
            return boundedContention;
        }

        double multiplier = Math.exp(-LN_2 * ((double) ageNanos / halfLifeNanos));
        double decayedContention = boundedContention * multiplier;
        if (!Double.isFinite(decayedContention) || decayedContention <= 0.0) {
            return 0L;
        }
        return MathFunctions.clampLong(Math.round(decayedContention), 0L, ACQUIRE_CONTENTION_SCALE);
    }

    /// Returns the fixed-point EWMA or `-1` when no eligible acquisition cycle has been observed.
    public long getAcquireContentionOrUninitialized() {
        return this.acquireContention.initialized() ? this.acquireContention.value() : -1L;
    }

    /// Normalizes the worker-local fixed-point value only for diagnostics and external reporting.
    public double getNormalizedAcquireContention() {
        return this.acquireContention.initialized()
                ? this.acquireContention.value() / (double) ACQUIRE_CONTENTION_SCALE
                : Double.NaN;
    }

    /// Resets acquisition history under the existing worker-owner lifecycle handoff.
    public void resetAcquireContention() {
        this.acquireContention.reset();
        this.contentionEvidenceCount = 0L;
        this.appliedContentionEvidenceCount = 0L;
        this.lastContentionEvidenceNanos = -1L;
        this.contentionObservationCount = 0L;
        this.lastRawContention = -1L;
        this.lastContentionObservationNs = -1L;
        this.successfulAcquisitionCount = 0L;
        this.failedAcquisitionCount = 0L;
        this.totalAcquisitionAttempts = 0L;
    }

    /// Restores owner-local scheduler and handle order at a drained benchmark boundary.
    public void resetForNextTrial() {
        resetAcquireContention();
        fillQueue();
        long queued = this.upstreams.sizeLong();
        if (queued > Integer.MAX_VALUE) {
            throw new IllegalStateException("Too many upstream handles to reset");
        }
        UpstreamHandle[] handles = new UpstreamHandle[(int) queued];
        int live = 0;
        for (int i = 0; i < handles.length; i++) {
            UpstreamHandle handle = this.upstreams.poll();
            if (handle != null && !handle.isComplete()) {
                handle.setProductivity(true);
                handles[live++] = handle;
            }
        }
        Arrays.sort(handles, 0, live, Comparator.comparingLong(UpstreamHandle::getSequence));
        for (int i = 0; i < live; i++) {
            this.upstreams.offer(handles[i]);
        }
        this.cachedUpCount = this.upstreamCount.getAcquire();
        this.nonproductiveCount = 0L;
    }

    /// Enables owner-local acquisition diagnostics for calibration runs.
    public void setAcquireDiagnosticsEnabled(boolean enabled) {
        this.acquireDiagnosticsEnabled = enabled;
        resetAcquireContention();
    }

    /// Returns live handles minus handles this worker last observed as nonproductive.
    ///
    /// New handles are optimistic until this worker services them. Completed handles are reconciled
    /// from this owner-local queue; no productivity state is published between workers.
    public long getProductiveHandleCount() {
        getTrueUpstreamCount();
        removeCompletedHandles();
        return this.cachedUpCount - Math.min(this.cachedUpCount, this.nonproductiveCount);
    }

    /// Applies an owner-local experimental bucketing treatment while the lattice is drained.
    public void setPullBucketTreatment(long target, PullBucketDivisionMode divisionMode) {
        if (target <= 0L) {
            throw new IllegalArgumentException("Pull bucket target must be positive");
        }
        this.pullBucketTarget = target;
        this.pullBucketDivisionMode = java.util.Objects.requireNonNull(divisionMode);
    }

    /// Enables bounded calibration-only source-handle observations.
    public void setPullConvoyObserver(FragmentObserver observer) {
        this.pullConvoyObserver = observer;
    }

    public void request(long demand) {
        pull(null, null, demand);
    }

    /// Pulls work without requesting from the [UpstreamHandles][UpstreamHandle]. If the consumer is
    /// `null`, it will **request** the work.
    public long pull(Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
        getTrueUpstreamCount();

        if (demand <= 0 || this.cachedUpCount == 0) {
            return 0;
        }

        FlowThread.FlowContext context = FlowThread.getContext();
        long totalPull = 0;
        long bucketSize = calculatePullBuckets(demand);
        long attempts = 0L;
        long failedAcquires = 0L;

        long limit = demand;
        int cycles = 0;
        // Cycle through the queue and pull round-robin style.
        while (cycles < this.cachedUpCount && limit > 0) {
            UpstreamHandle handle = this.upstreams.poll();

            if (handle == null && this.bufferIndex > 0) {
                fillQueue();
                continue;
            }

            if (handle == null) {
                cycles++;
                continue;
            }
            if (handle.isComplete()) {
                observeRemoval(handle);
                continue;
            }

            boolean wasProductive = handle.isProductive();
            attempts++;
            if (!handle.acquireLock()) {
                failedAcquires++;
                recordPullConvoy(handle, -1, demand, Math.min(limit, bucketSize), 0L, false, 0L);
                bufferHandle(handle);
                cycles++;
                continue;
            }

            long holdStartNs = this.pullConvoyObserver == null ? 0L : System.nanoTime();
            long request = 0L;
            long producedFrameCount = 0L;
            try {
                long requestBefore = context == null || consumer != null ? 0L : context.satisfiedRequest;
                request = Math.min(limit, bucketSize);
                limit -= request;

                long drainCount = drain(handle, consumer, stopCondition, request);
                producedFrameCount = consumer != null
                        ? drainCount
                        : context == null ? 0L : Math.max(0L, context.satisfiedRequest - requestBefore);
                totalPull += drainCount;
                if (context != null) {
                    context.satisfiedPull += drainCount;
                }

                if (consumer == null) {
                    if (context != null && context.satisfiedRequest != requestBefore) {
                        handle.setProductivity(true);
                    } else if (!handle.isProductive()) {
                        // Request has no empty-source result. Without a synchronous push, it
                        // supplies no new evidence and retains the worker's prior observation.
                        handle.setProductivity(wasProductive);
                    }
                }

                boolean produced = handle.isProductive();
                if (!wasProductive && produced) {
                    if (this.nonproductiveCount > 0L) {
                        this.nonproductiveCount--;
                    }
                } else if (wasProductive && !produced) {
                    this.nonproductiveCount++;
                }
            } finally {
                handle.releaseLock();
                long holdDurationNs =
                        this.pullConvoyObserver == null ? 0L : Math.max(0L, System.nanoTime() - holdStartNs);
                recordPullConvoy(handle, this.core, demand, request, producedFrameCount, true, holdDurationNs);
                bufferHandle(handle);
            }
            cycles = 0;
        }
        if (attempts > 0L) {
            long rawContention = scaleAcquireContentionUnchecked(failedAcquires, attempts);
            this.acquireContention.record(rawContention);
            this.contentionEvidenceCount++;
            if (this.acquireDiagnosticsEnabled) {
                this.contentionObservationCount++;
                this.lastRawContention = rawContention;
                this.lastContentionObservationNs = System.nanoTime();
                this.successfulAcquisitionCount += attempts - failedAcquires;
                this.failedAcquisitionCount += failedAcquires;
                this.totalAcquisitionAttempts += attempts;
            }
        }
        fillQueue();
        return totalPull;
    }

    /// Returns every dequeued live handle through the owner-local shuffle buffer.
    private void bufferHandle(UpstreamHandle handle) {
        if (this.bufferIndex == this.buffer.length) {
            fillQueue();
        }
        this.buffer[this.bufferIndex++] = handle;
    }

    private void recordPullConvoy(
            UpstreamHandle handle,
            int ownerCore,
            long requestedDemand,
            long calculatedPullSize,
            long producedFrameCount,
            boolean acquired,
            long lockHoldDurationNs) {
        FragmentObserver observer = this.pullConvoyObserver;
        if (observer == null) {
            return;
        }
        observer.pullConvoyState(
                System.nanoTime(),
                handle.getId(),
                this.core,
                ownerCore,
                requestedDemand,
                calculatedPullSize,
                producedFrameCount,
                acquired,
                lockHoldDurationNs);
    }

    void fillQueue() {
        while (this.bufferIndex > 1) {
            this.seed = HasherApi.mix(this.seed + 1);

            int idx = (int) Math.unsignedMultiplyHigh(this.seed, this.bufferIndex);

            this.bufferIndex--;

            UpstreamHandle handle = this.buffer[idx];
            this.buffer[idx] = this.buffer[this.bufferIndex];
            this.buffer[this.bufferIndex] = null;

            this.upstreams.offer(handle);
        }

        if (this.bufferIndex == 1) {
            this.bufferIndex = 0;
            this.upstreams.offer(this.buffer[0]);
            this.buffer[0] = null;
        }
    }

    /// Scales a valid failed/attempt count for deterministic boundary tests and diagnostics.
    static long scaleAcquireContention(long failedAcquires, long attempts) {
        if (attempts <= 0L || failedAcquires < 0L || failedAcquires > attempts) {
            throw new IllegalArgumentException("Acquisition counts require 0 <= failures <= positive attempts");
        }
        return scaleAcquireContentionUnchecked(failedAcquires, attempts);
    }

    /// Keeps the scheduler-domain multiply/divide fast while handling wider public queue inputs.
    private static long scaleAcquireContentionUnchecked(long failedAcquires, long attempts) {
        if (failedAcquires <= MAX_SCALED_FAILURES) {
            return failedAcquires * ACQUIRE_CONTENTION_SCALE / attempts;
        }
        return scaleAcquireContentionLarge(failedAcquires, attempts);
    }

    /// Produces six exact decimal fraction digits without forming an overflowing product.
    private static long scaleAcquireContentionLarge(long failedAcquires, long attempts) {
        if (failedAcquires == attempts) {
            return ACQUIRE_CONTENTION_SCALE;
        }
        long remainder = failedAcquires;
        long scaled = 0L;
        for (int place = 0; place < 6; place++) {
            long nextRemainder = 0L;
            long digit = 0L;
            for (int add = 0; add < 10; add++) {
                if (nextRemainder >= attempts - remainder) {
                    nextRemainder -= attempts - remainder;
                    digit++;
                } else {
                    nextRemainder += remainder;
                }
            }
            scaled = scaled * 10L + digit;
            remainder = nextRemainder;
        }
        return scaled;
    }

    /// Removes completed queue entries when lifecycle changes occur without another pull.
    private void removeCompletedHandles() {
        fillQueue();
        long queued = this.upstreams.sizeLong();
        long surplus = queued - this.cachedUpCount;
        while (queued > 0L && surplus > 0L) {
            UpstreamHandle handle = this.upstreams.poll();
            if (handle == null) {
                return;
            }
            queued--;
            if (handle.isComplete()) {
                observeRemoval(handle);
                surplus--;
            } else {
                this.upstreams.offer(handle);
            }
        }
    }

    private void observeRemoval(UpstreamHandle handle) {
        if (!handle.isProductive() && this.nonproductiveCount > 0L) {
            this.nonproductiveCount--;
        }
    }

    /// Calculates an even per-handle pull using the configured experimental bucket rule.
    protected long calculatePullBuckets(long demand) {
        if (demand <= 0L || this.cachedUpCount < 2) {
            return demand;
        }

        long bucketsNeeded =
                switch (this.pullBucketDivisionMode) {
                    case FLOOR -> demand / this.pullBucketTarget;
                    case CEIL -> 1L + (demand - 1L) / this.pullBucketTarget;
                };
        long buckets = MathFunctions.clampLong(bucketsNeeded, 1L, this.cachedUpCount);

        return 1L + (demand - 1L) / buckets;
    }

    /// A wrapper for an upstream source.
    public abstract static class UpstreamHandle implements LatticeInterceptor {

        public abstract long getId();

        /// Returns stable source-registration order for benchmark reset; other handles fall back to identity.
        public long getSequence() {
            return getId();
        }

        public void addUpstream(LatticeSource upstream) {
            upstream.complete();
        }

        public void addDownstream(LatticeReceiver terminal) {
            terminal.onError(new IllegalStateException("Not supported"));
        }

        public boolean acquireLock() {
            return true;
        }

        public void releaseLock() {}

        /// Returns this worker's last observation of whether the handle produced useful work.
        public boolean isProductive() {
            return true;
        }

        /// Sets this worker's plain observation after classifying one acquired service.
        public void setProductivity(boolean productive) {}
    }
}
