package io.euhedral_execution.core.control_plane;

import static io.euhedral_execution.core.utils.MathFunctions.unsignedMultiplyHigh;

import io.euhedral_execution.benchmarks.utils.RepeatingSink;
import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.BenchmarkFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.impl.BaseCloneableObject;
import io.euhedral_execution.core.metrics.MetricsAggregator;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hashing.HasherApi;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;
import java.util.function.Function;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Forced-mode diagnostic for discovering how work cost and upstream availability select the faster
/// fragment path.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(3)
public class FragmentPathCalibrationBenchmark {

    static final int FIXED_BATCH_SIZE = 32;
    static final int INVOCATION_FRAMES = 1_048_576;
    static final int FRAME_POOL_SIZE = 16_384;
    static final int SAMPLE_COUNT = 9;
    static final int CPU_WORK_ROUNDS = 256;
    static final int BODY_TIMING_INTERVAL = 256;
    static final double BODY_TIMING_SEPARATION_MARGIN_NS = 5.0;
    static final double BODY_TIMING_NEUTRALITY_LIMIT_NS = 5.0;
    static final String BODY_TIMING_ENABLED_PROPERTY = "euhedral.fragment.bodyTiming.enabled";
    static final String WORK_COST_METRIC_PREFIX = "fragment-work-cost";
    static final long TIMEOUT_NS = TimeUnit.MINUTES.toNanos(1);
    /// The blueprint's 225 ns theoretical one-worker ceiling for CPU-work lane estimates.
    static final double CPU_SINGLE_LANE_CEILING_FRAMES_PER_SECOND = 1_000_000_000.0 / 225.0;
    /// Phase 1's isolated DIRECT no-op throughput control on the calibration host.
    static final double DIRECT_NO_OP_SINGLE_LANE_CEILING_FRAMES_PER_SECOND = 88_797_000.0;
    /// Phase 1's isolated STAGED no-op throughput control on the calibration host.
    static final double STAGED_NO_OP_SINGLE_LANE_CEILING_FRAMES_PER_SECOND = 35_919_000.0;

    private static final Logger LOGGER = LoggerFactory.getLogger(FragmentPathCalibrationBenchmark.class);

    /// Measures one forced path with one pinned worker and no-op frames.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void singleWorkerOverhead(SingleWorkerState state) {
        state.awaitInvocation();
    }

    /// Measures no-op work with either plentiful or scarce sources on two same-kind workers.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void noOpDecision(NoOpDecisionState state) {
        state.awaitInvocation();
    }

    /// Measures fixed CPU work with either plentiful or scarce sources on two same-kind workers.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void cpuWorkDecision(CpuWorkDecisionState state) {
        state.awaitInvocation();
    }

    /// Measures a coarse deterministic work cost through either real forced fragment path.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void workCostDecision(WorkCostDecisionState state) {
        state.awaitInvocation();
    }

    /// Measures the sparse executor-only body-cost sensor through either forced fragment path.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void executorBodyCost(ExecutorBodyCostState state) {
        state.awaitInvocation();
    }

    /// Measures the effectively empty `BenchmarkFrame.execute()` body without the scheduler.
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public BenchmarkFrame noOpWorkOnly(WorkOnlyState state) {
        state.frame.execute();
        return state.frame;
    }

    /// Measures exactly the arithmetic body used by the CPU-work fragment executor.
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long cpuWorkOnly(WorkOnlyState state) {
        state.value = cpuWork(state.value);
        return state.value;
    }

    /// Measures one parameterized arithmetic body without scheduler or frame-path work.
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long workCostOnly(WorkCostOnlyState state) {
        state.value = cpuWork(state.value, state.workRounds);
        return state.value;
    }

    /// Adds one fixed completion window without allowing target wraparound.
    static long completionTarget(long completed) {
        return Math.addExact(completed, INVOCATION_FRAMES);
    }

    /// Returns the middle value after sorting the caller-owned odd sample array in place.
    static double median(double[] samples) {
        if (samples.length == 0 || (samples.length & 1) == 0) {
            throw new IllegalArgumentException("Median requires a non-empty odd sample count");
        }
        Arrays.sort(samples);
        return samples[samples.length >>> 1];
    }

    /// Maps the source fixture to one shared source or one independent source per worker.
    static int sourceCount(SourceShape shape, int workers) {
        if (workers <= 0) {
            throw new IllegalArgumentException("Workers must be positive");
        }
        return shape == SourceShape.SCARCE ? 1 : workers;
    }

    /// Returns the first `count` candidate cores without mutating the caller-owned set.
    static BitSet firstCores(BitSet candidates, int count) {
        if (count <= 0 || candidates.cardinality() < count) {
            throw new IllegalArgumentException("Not enough candidate cores for the requested fixture");
        }
        BitSet selected = new BitSet();
        for (int core = candidates.nextSetBit(0);
                core >= 0 && selected.cardinality() < count;
                core = candidates.nextSetBit(core + 1)) {
            selected.set(core);
        }
        return selected;
    }

    /// Applies the deterministic arithmetic workload used by both scheduled and work-only cases.
    static long cpuWork(long input) {
        return cpuWork(input, CPU_WORK_ROUNDS);
    }

    /// Applies a deterministic number of arithmetic rounds for the coarse work-cost sweep.
    static long cpuWork(long input, int rounds) {
        if (rounds < 0) {
            throw new IllegalArgumentException("Work rounds must be non-negative");
        }
        long value = input;
        for (int i = 0; i < rounds; i++) {
            value ^= 0x9e3779b97f4a7c15L + i;
            value = Long.rotateLeft(value * 0xbf58476d1ce4e5b9L, 17);
        }
        return value;
    }

    /// Reads the selected worker counters with acquire semantics in stable worker order.
    static long[] workerCounts(PaddedLongAdder counters, int[] workerCpus) {
        long[] counts = new long[workerCpus.length];
        for (int i = 0; i < workerCpus.length; i++) {
            counts[i] = counters.getAcquire(workerCpus[i]);
        }
        return counts;
    }

    /// Adds one monotonic fixed-window delta to its measurement-iteration accumulator.
    static void accumulateCompletionDeltas(long[] accumulated, long[] before, long[] after) {
        if (accumulated.length != before.length || before.length != after.length) {
            throw new IllegalArgumentException("Worker counter arrays must have equal lengths");
        }
        for (int i = 0; i < accumulated.length; i++) {
            long delta = after[i] - before[i];
            if (delta < 0L) {
                throw new IllegalArgumentException("Worker counters must be monotonic");
            }
            accumulated[i] = Math.addExact(accumulated[i], delta);
        }
    }

    /// Derives participation fractions, dominance, and effective lanes from one completion window.
    static ParticipationMetrics participationMetrics(long[] workerDeltas, long elapsedNanos) {
        return participationMetrics(workerDeltas, elapsedNanos, CPU_SINGLE_LANE_CEILING_FRAMES_PER_SECOND);
    }

    /// Derives participation metrics relative to the selected workload's isolated lane ceiling.
    static ParticipationMetrics participationMetrics(
            long[] workerDeltas, long elapsedNanos, double singleLaneCeilingFramesPerSecond) {
        if (workerDeltas.length == 0) {
            throw new IllegalArgumentException("At least one worker delta is required");
        }
        if (elapsedNanos <= 0L) {
            throw new IllegalArgumentException("Elapsed time must be positive");
        }
        if (!Double.isFinite(singleLaneCeilingFramesPerSecond) || singleLaneCeilingFramesPerSecond <= 0.0) {
            throw new IllegalArgumentException("Single-lane ceiling must be finite and positive");
        }
        long completed = 0L;
        long maximum = 0L;
        for (long delta : workerDeltas) {
            if (delta < 0L) {
                throw new IllegalArgumentException("Worker deltas must be non-negative");
            }
            completed = Math.addExact(completed, delta);
            maximum = Math.max(maximum, delta);
        }
        double[] fractions = new double[workerDeltas.length];
        if (completed > 0L) {
            for (int i = 0; i < workerDeltas.length; i++) {
                fractions[i] = (double) workerDeltas[i] / completed;
            }
        }
        double dominance = completed == 0L ? 0.0 : (double) maximum / completed;
        double throughput = (double) completed * TimeUnit.SECONDS.toNanos(1L) / elapsedNanos;
        return new ParticipationMetrics(
                fractions, dominance, throughput, throughput / singleLaneCeilingFramesPerSecond);
    }

    /// Selects the predeclared isolated single-worker control for effective-lane reporting.
    static double singleLaneCeiling(Workload workload, ForcedMode mode) {
        if (workload == Workload.CPU_WORK) {
            return CPU_SINGLE_LANE_CEILING_FRAMES_PER_SECOND;
        }
        return mode == ForcedMode.DIRECT
                ? DIRECT_NO_OP_SINGLE_LANE_CEILING_FRAMES_PER_SECOND
                : STAGED_NO_OP_SINGLE_LANE_CEILING_FRAMES_PER_SECOND;
    }

    /// Cumulative sparse executor-body timing state in stable worker order.
    record BodyTimingSnapshot(long[] counts, long[] elapsedNanos) {

        /// Isolates counter arrays retained beyond an iteration boundary.
        BodyTimingSnapshot {
            if (counts.length != elapsedNanos.length) {
                throw new IllegalArgumentException("Body timing arrays must have equal lengths");
            }
            counts = counts.clone();
            elapsedNanos = elapsedNanos.clone();
        }

        /// Returns isolated sample counts for deterministic diagnostics.
        @Override
        public long[] counts() {
            return this.counts.clone();
        }

        /// Returns isolated elapsed totals for deterministic diagnostics.
        @Override
        public long[] elapsedNanos() {
            return this.elapsedNanos.clone();
        }
    }

    /// Computes one monotonic sparse body-timing delta in stable worker order.
    static BodyTimingSnapshot bodyTimingDelta(BodyTimingSnapshot before, BodyTimingSnapshot after) {
        long[] beforeCounts = before.counts;
        long[] afterCounts = after.counts;
        long[] beforeElapsed = before.elapsedNanos;
        long[] afterElapsed = after.elapsedNanos;
        if (beforeCounts.length != afterCounts.length) {
            throw new IllegalArgumentException("Body timing snapshots must have equal worker counts");
        }
        long[] counts = new long[beforeCounts.length];
        long[] elapsedNanos = new long[beforeCounts.length];
        for (int worker = 0; worker < counts.length; worker++) {
            counts[worker] = afterCounts[worker] - beforeCounts[worker];
            elapsedNanos[worker] = afterElapsed[worker] - beforeElapsed[worker];
            if (counts[worker] < 0L || elapsedNanos[worker] < 0L) {
                throw new IllegalArgumentException("Body timing counters must be monotonic");
            }
        }
        return new BodyTimingSnapshot(counts, elapsedNanos);
    }

    /// Converts sparse timing totals to per-worker nanoseconds per sampled executor call.
    static double[] bodyTimingEstimates(BodyTimingSnapshot snapshot) {
        long[] counts = snapshot.counts;
        long[] elapsedNanos = snapshot.elapsedNanos;
        double[] estimates = new double[counts.length];
        for (int worker = 0; worker < estimates.length; worker++) {
            if (counts[worker] <= 0L) {
                throw new IllegalArgumentException("Every retained worker must have a body timing sample");
            }
            estimates[worker] = (double) elapsedNanos[worker] / counts[worker];
        }
        return estimates;
    }

    /// Returns a median without mutating the retained fork-worker evidence.
    static double bodyTimingMedian(double[] estimates) {
        if (estimates.length == 0) {
            throw new IllegalArgumentException("At least one retained body estimate is required");
        }
        double[] sorted = estimates.clone();
        for (double estimate : sorted) {
            if (!Double.isFinite(estimate) || estimate < 0.0) {
                throw new IllegalArgumentException("Body timing estimates must be finite and non-negative");
            }
        }
        Arrays.sort(sorted);
        int middle = sorted.length >>> 1;
        return (sorted.length & 1) == 0 ? (sorted[middle - 1] + sorted[middle]) / 2.0 : sorted[middle];
    }

    /// Returns the inclusive range of one retained fork-worker estimate set.
    static RetainedRange retainedRange(double[] estimates) {
        bodyTimingMedian(estimates);
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (double estimate : estimates) {
            minimum = Math.min(minimum, estimate);
            maximum = Math.max(maximum, estimate);
        }
        return new RetainedRange(minimum, maximum);
    }

    /// Applies the predeclared monotonic-median and five-nanosecond separation gates.
    static boolean bodyTimingSeparationPassed(double[] rounds24, double[] rounds80, double[] rounds96) {
        double median24 = bodyTimingMedian(rounds24);
        double median80 = bodyTimingMedian(rounds80);
        double median96 = bodyTimingMedian(rounds96);
        RetainedRange range80 = retainedRange(rounds80);
        RetainedRange range96 = retainedRange(rounds96);
        return median24 < median80
                && median80 < median96
                && range80.maximum() + BODY_TIMING_SEPARATION_MARGIN_NS <= range96.minimum();
    }

    /// Applies the predeclared per-point worker and fork dispersion bound.
    static boolean bodyTimingStabilityPassed(double[] estimates) {
        double median = bodyTimingMedian(estimates);
        double tolerance = Math.max(5.0, median * 0.10);
        for (double estimate : estimates) {
            if (Math.abs(estimate - median) > tolerance) {
                return false;
            }
        }
        return true;
    }

    /// Applies the five-nanosecond span bound to four mode-and-availability groups.
    static boolean bodyTimingNeutralityPassed(double[][] groupEstimates) {
        if (groupEstimates.length != 4) {
            throw new IllegalArgumentException("Exactly four mode-and-availability groups are required");
        }
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (double[] group : groupEstimates) {
            double median = bodyTimingMedian(group);
            minimum = Math.min(minimum, median);
            maximum = Math.max(maximum, median);
        }
        return maximum - minimum <= BODY_TIMING_NEUTRALITY_LIMIT_NS;
    }

    /// Inclusive extrema for one retained body-timing estimate set.
    record RetainedRange(double minimum, double maximum) {}

    /// Immutable derived participation values for one fixed measurement window.
    record ParticipationMetrics(
            double[] fractions, double dominance, double throughputFramesPerSecond, double effectiveLanes) {

        /// Protects recorded fractions from later mutation by reporting code or tests.
        ParticipationMetrics {
            fractions = fractions.clone();
        }

        /// Returns an isolated fraction vector for stable diagnostic reporting.
        @Override
        public double[] fractions() {
            return this.fractions.clone();
        }
    }

    /// Cumulative per-source acquisition state in stable worker order at one lifecycle boundary.
    record HandleSnapshot(long[][] attempts, long[][] failures, long[][] pulledFrames, long[] firstProductiveOrder) {

        /// Isolates all mutable arrays retained by one diagnostic snapshot.
        HandleSnapshot {
            attempts = deepCopy(attempts);
            failures = deepCopy(failures);
            pulledFrames = deepCopy(pulledFrames);
            firstProductiveOrder = firstProductiveOrder.clone();
        }

        /// Returns isolated attempt totals for stable reporting and tests.
        @Override
        public long[][] attempts() {
            return deepCopy(this.attempts);
        }

        /// Returns isolated failure totals for stable reporting and tests.
        @Override
        public long[][] failures() {
            return deepCopy(this.failures);
        }

        /// Returns isolated productive-frame totals for stable reporting and tests.
        @Override
        public long[][] pulledFrames() {
            return deepCopy(this.pulledFrames);
        }

        /// Returns isolated first-productive order values for stable reporting and tests.
        @Override
        public long[] firstProductiveOrder() {
            return this.firstProductiveOrder.clone();
        }
    }

    /// Cumulative existing latency-summary state in stable worker order at one lifecycle boundary.
    record ServiceMetricSnapshot(long[] counts, double[] totals) {

        /// Isolates registry-derived arrays retained beyond the lifecycle callback.
        ServiceMetricSnapshot {
            if (counts.length != totals.length) {
                throw new IllegalArgumentException("Service metric arrays must have equal lengths");
            }
            counts = counts.clone();
            totals = totals.clone();
        }

        /// Returns isolated report counts for deterministic diagnostics.
        @Override
        public long[] counts() {
            return this.counts.clone();
        }

        /// Returns isolated reported-latency totals for deterministic diagnostics.
        @Override
        public double[] totals() {
            return this.totals.clone();
        }
    }

    /// Computes one monotonic measurement-only delta from existing latency summaries.
    static ServiceMetricSnapshot serviceMetricDelta(ServiceMetricSnapshot before, ServiceMetricSnapshot after) {
        long[] beforeCounts = before.counts;
        long[] afterCounts = after.counts;
        double[] beforeTotals = before.totals;
        double[] afterTotals = after.totals;
        if (beforeCounts.length != afterCounts.length) {
            throw new IllegalArgumentException("Service metric snapshots must have equal worker counts");
        }
        long[] counts = new long[beforeCounts.length];
        double[] totals = new double[beforeCounts.length];
        for (int worker = 0; worker < counts.length; worker++) {
            counts[worker] = afterCounts[worker] - beforeCounts[worker];
            totals[worker] = afterTotals[worker] - beforeTotals[worker];
            if (counts[worker] < 0L || !Double.isFinite(totals[worker]) || totals[worker] < 0.0) {
                throw new IllegalArgumentException("Service metric values must be finite and monotonic");
            }
        }
        return new ServiceMetricSnapshot(counts, totals);
    }

    /// Converts latency-summary totals to per-worker nanoseconds per frame estimates.
    static double[] serviceEstimates(ServiceMetricSnapshot snapshot) {
        long[] counts = snapshot.counts;
        double[] totals = snapshot.totals;
        double[] estimates = new double[counts.length];
        for (int worker = 0; worker < estimates.length; worker++) {
            estimates[worker] = counts[worker] == 0L ? Double.NaN : totals[worker] / counts[worker];
        }
        return estimates;
    }

    /// Resolves Core's existing per-worker latency summaries in stable core order.
    static DistributionSummary[] serviceSummaries(SimpleMeterRegistry registry, int[] workerCores) {
        DistributionSummary[] summaries = new DistributionSummary[workerCores.length];
        String name = MetricsAggregator.metricName(WORK_COST_METRIC_PREFIX, MetricsAggregator.LATENCY_SUMMARY_SUFFIX);
        for (int worker = 0; worker < workerCores.length; worker++) {
            summaries[worker] = registry.find(name)
                    .tag(MetricsAggregator.CORE_TAG, Integer.toString(workerCores[worker]))
                    .summary();
            if (summaries[worker] == null) {
                throw new IllegalStateException("Missing execution-latency summary for diagnostic worker");
            }
        }
        return summaries;
    }

    /// Snapshots existing latency summary counts and totals without changing fragment behavior.
    static ServiceMetricSnapshot serviceMetricSnapshot(DistributionSummary[] summaries) {
        long[] counts = new long[summaries.length];
        double[] totals = new double[summaries.length];
        for (int worker = 0; worker < summaries.length; worker++) {
            counts[worker] = summaries[worker].count();
            totals[worker] = summaries[worker].totalAmount();
        }
        return new ServiceMetricSnapshot(counts, totals);
    }

    /// Computes one monotonic handle-diagnostic delta while preserving first-event order metadata.
    static HandleSnapshot handleDelta(HandleSnapshot before, HandleSnapshot after) {
        long[][] attempts = matrixDelta(before.attempts, after.attempts);
        long[][] failures = matrixDelta(before.failures, after.failures);
        long[][] pulledFrames = matrixDelta(before.pulledFrames, after.pulledFrames);
        return new HandleSnapshot(attempts, failures, pulledFrames, after.firstProductiveOrder);
    }

    /// Copies a rectangular diagnostic matrix without exposing recorder-owned rows.
    private static long[][] deepCopy(long[][] matrix) {
        long[][] copy = new long[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            copy[i] = matrix[i].clone();
        }
        return copy;
    }

    /// Subtracts two equally shaped monotonic diagnostic matrices.
    private static long[][] matrixDelta(long[][] before, long[][] after) {
        if (before.length != after.length) {
            throw new IllegalArgumentException("Handle diagnostic matrices must have equal source counts");
        }
        long[][] delta = new long[before.length][];
        for (int source = 0; source < before.length; source++) {
            if (before[source].length != after[source].length) {
                throw new IllegalArgumentException("Handle diagnostic matrices must have equal worker counts");
            }
            delta[source] = new long[before[source].length];
            for (int worker = 0; worker < before[source].length; worker++) {
                long value = after[source][worker] - before[source][worker];
                if (value < 0L) {
                    throw new IllegalArgumentException("Handle diagnostic counters must be monotonic");
                }
                delta[source][worker] = value;
            }
        }
        return delta;
    }

    /// Preallocated acquisition accounting updated once per handle attempt or productive pull.
    static final class HandleAcquisitionRecorder {

        private final int[] workerCpus;
        private final int[] workerByCore;
        private final PaddedLongAdder[] attempts;
        private final PaddedLongAdder[] failures;
        private final PaddedLongAdder[] pulledFrames;
        private final AtomicLong firstSequence = new AtomicLong();
        private final AtomicLongArray firstProductiveOrder;

        /// Creates source-by-worker counters and the stable core-to-worker lookup used in hot callbacks.
        HandleAcquisitionRecorder(int sourceCount, int[] workerCpus, int[] workerCores) {
            if (sourceCount <= 0 || workerCpus.length == 0 || workerCpus.length != workerCores.length) {
                throw new IllegalArgumentException("Sources and equally shaped worker identities are required");
            }
            this.workerCpus = workerCpus.clone();
            this.workerByCore = new int[SystemInfo.MAX_CORE_ID + 1];
            Arrays.fill(this.workerByCore, -1);
            for (int worker = 0; worker < workerCores.length; worker++) {
                int core = workerCores[worker];
                if (core < 0 || core >= this.workerByCore.length || this.workerByCore[core] >= 0) {
                    throw new IllegalArgumentException("Worker cores must be unique and in range");
                }
                this.workerByCore[core] = worker;
            }
            this.attempts = counters(sourceCount);
            this.failures = counters(sourceCount);
            this.pulledFrames = counters(sourceCount);
            this.firstProductiveOrder = new AtomicLongArray(sourceCount * workerCpus.length);
            for (int i = 0; i < this.firstProductiveOrder.length(); i++) {
                this.firstProductiveOrder.setPlain(i, -1L);
            }
        }

        /// Resolves the calling fragment's stable worker ordinal from its existing owner-local queue.
        int currentWorker() {
            UpstreamQueue queue = UpstreamQueue.UP_QUEUE.get();
            if (queue == null || queue.core < 0 || queue.core >= this.workerByCore.length) {
                throw new IllegalStateException("Handle acquisition did not originate from a diagnostic worker");
            }
            int worker = this.workerByCore[queue.core];
            if (worker < 0) {
                throw new IllegalStateException("Handle acquisition originated from an unselected worker core");
            }
            return worker;
        }

        /// Records one acquisition result in a worker-exclusive padded counter cell.
        void recordAcquisition(int source, int worker, boolean acquired) {
            int cpu = workerCpu(source, worker);
            this.attempts[source].increment(cpu);
            if (!acquired) {
                this.failures[source].increment(cpu);
            }
        }

        /// Records productive frames and the globally ordered first productive event for one cell.
        void recordPulledFrames(int source, int worker, long frames) {
            if (frames <= 0L) {
                return;
            }
            int cpu = workerCpu(source, worker);
            this.pulledFrames[source].add(cpu, frames);
            int cell = source * this.workerCpus.length + worker;
            if (this.firstProductiveOrder.getPlain(cell) < 0L) {
                this.firstProductiveOrder.setRelease(cell, this.firstSequence.getAndIncrement());
            }
        }

        /// Captures all counters with acquire semantics in source-major, stable-worker order.
        HandleSnapshot snapshot() {
            long[][] attemptSnapshot = snapshot(this.attempts);
            long[][] failureSnapshot = snapshot(this.failures);
            long[][] frameSnapshot = snapshot(this.pulledFrames);
            long[] firstOrder = new long[this.firstProductiveOrder.length()];
            for (int i = 0; i < firstOrder.length; i++) {
                firstOrder[i] = this.firstProductiveOrder.getAcquire(i);
            }
            return new HandleSnapshot(attemptSnapshot, failureSnapshot, frameSnapshot, firstOrder);
        }

        /// Returns a fresh padded counter for every independently identified source.
        private static PaddedLongAdder[] counters(int sourceCount) {
            PaddedLongAdder[] counters = new PaddedLongAdder[sourceCount];
            for (int source = 0; source < sourceCount; source++) {
                counters[source] = new PaddedLongAdder(SystemInfo.CPU_COUNT, true, true);
            }
            return counters;
        }

        /// Reads one counter family in the recorder's stable worker order.
        private long[][] snapshot(PaddedLongAdder[] counters) {
            long[][] values = new long[counters.length][this.workerCpus.length];
            for (int source = 0; source < counters.length; source++) {
                for (int worker = 0; worker < this.workerCpus.length; worker++) {
                    values[source][worker] = counters[source].getAcquire(this.workerCpus[worker]);
                }
            }
            return values;
        }

        /// Validates source/worker coordinates and returns the worker's logical CPU counter index.
        private int workerCpu(int source, int worker) {
            if (source < 0 || source >= this.attempts.length || worker < 0 || worker >= this.workerCpus.length) {
                throw new IllegalArgumentException("Handle diagnostic coordinate is outside the fixture");
            }
            return this.workerCpus[worker];
        }
    }

    /// Creates the stable zero-based source identities retained in each fork report.
    static int[] sourceOrdinals(int sourceCount) {
        if (sourceCount < 0) {
            throw new IllegalArgumentException("Source count must be non-negative");
        }
        int[] ordinals = new int[sourceCount];
        for (int source = 0; source < sourceCount; source++) {
            ordinals[source] = source;
        }
        return ordinals;
    }

    /// Formats one lifecycle snapshot with explicit primitive-array contents.
    static String formatHandleSnapshot(HandleSnapshot snapshot) {
        if (snapshot == null) {
            return "null";
        }
        return "{attempts=" + Arrays.deepToString(snapshot.attempts())
                + ", failures=" + Arrays.deepToString(snapshot.failures())
                + ", pulledFrames=" + Arrays.deepToString(snapshot.pulledFrames())
                + ", firstProductiveOrder=" + Arrays.toString(snapshot.firstProductiveOrder()) + '}';
    }

    /// Formats lifecycle-aligned snapshots without relying on record array identity strings.
    static String formatHandleSnapshots(List<HandleSnapshot> snapshots) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < snapshots.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(formatHandleSnapshot(snapshots.get(i)));
        }
        return builder.append(']').toString();
    }

    /// Benchmark source-availability fixtures.
    public enum SourceShape {
        PLENTIFUL,
        SCARCE
    }

    /// Public JMH parameter mapped to the package-private production mode during trial setup.
    public enum ForcedMode {
        DIRECT,
        STAGED
    }

    /// Benchmark executor bodies.
    enum Workload {
        NO_OP,
        CPU_WORK
    }

    /// Benchmark-only upstream publication layouts used after natural acquisition is classified.
    public enum HandleLayout {
        NATURAL,
        BATCH_ALIGNED,
        PHASED
    }

    /// One-worker state that records cold and post-warmup no-op path samples.
    @State(Scope.Benchmark)
    public static class SingleWorkerState extends PathState {

        private double[] startupSamples;

        /// Starts one forced-mode worker and records the bounded startup sample set.
        @Setup(Level.Trial)
        public void setup() {
            setupPath(1, SourceShape.SCARCE, Workload.NO_OP);
            awaitFrames(FIXED_BATCH_SIZE * 2L);
            this.startupSamples = collectSamples();
        }

        /// Records warmed samples and reports both sets before common graph teardown.
        @Override
        protected void beforeClose() {
            double[] warmedSamples = collectSamples();
            double startupMedian = median(this.startupSamples);
            double warmedMedian = median(warmedSamples);
            LOGGER.info(
                    "Fragment path calibration mode={} batch={} phase=startup rawNsPerFrame={} medianNsPerFrame={}",
                    this.mode,
                    FIXED_BATCH_SIZE,
                    Arrays.toString(this.startupSamples),
                    startupMedian);
            LOGGER.info(
                    "Fragment path calibration mode={} batch={} phase=warmed rawNsPerFrame={} medianNsPerFrame={} startupDifferencePercent={}",
                    this.mode,
                    FIXED_BATCH_SIZE,
                    Arrays.toString(warmedSamples),
                    warmedMedian,
                    Math.abs(startupMedian - warmedMedian) * 100.0 / Math.max(warmedMedian, 1e-9));
            super.beforeClose();
        }
    }

    /// Two-worker no-op state parameterized only by mode and source availability.
    @State(Scope.Benchmark)
    public static class NoOpDecisionState extends PathState {

        @Param({"PLENTIFUL", "SCARCE"})
        public SourceShape sourceShape;

        /// Starts the no-op source-shape comparison.
        @Setup(Level.Trial)
        public void setup() {
            setupPath(2, this.sourceShape, Workload.NO_OP);
        }
    }

    /// Two-worker CPU state parameterized only by mode and source availability.
    @State(Scope.Benchmark)
    public static class CpuWorkDecisionState extends PathState {

        @Param({"PLENTIFUL", "SCARCE"})
        public SourceShape sourceShape;

        /// Starts the CPU-work source-shape comparison.
        @Setup(Level.Trial)
        public void setup() {
            setupPath(2, this.sourceShape, Workload.CPU_WORK);
        }
    }

    /// Two-worker corrected-path state for one source shape and deterministic arithmetic cost.
    @State(Scope.Benchmark)
    public static class WorkCostDecisionState extends PathState {

        @Param({"PLENTIFUL", "SCARCE"})
        public SourceShape sourceShape;

        @Param({"0", "8", "24", "48", "64", "80", "96", "176", "256", "512"})
        public int workRounds;

        /// Starts one forced-path work-cost row with the existing latency metric enabled.
        @Setup(Level.Trial)
        public void setup() {
            setupPath(2, this.sourceShape, Workload.CPU_WORK, this.workRounds, true);
        }
    }

    /// Two-worker forced-path state for the diagnostic executor-only timing candidate.
    @State(Scope.Benchmark)
    public static class ExecutorBodyCostState extends PathState {

        @Param({"PLENTIFUL", "SCARCE"})
        public SourceShape sourceShape;

        @Param({"24", "80", "96"})
        public int workRounds;

        /// Starts one fixed validation point; a diagnostic property disables timing for overhead control.
        @Setup(Level.Trial)
        public void setup() {
            boolean bodyTimingEnabled =
                    Boolean.parseBoolean(System.getProperty(BODY_TIMING_ENABLED_PROPERTY, Boolean.TRUE.toString()));
            setupPath(2, this.sourceShape, Workload.CPU_WORK, this.workRounds, false, bodyTimingEnabled);
        }
    }

    /// Owner-local state for the scheduler-free work-body measurements.
    @State(Scope.Thread)
    public static class WorkOnlyState {

        final BenchmarkFrame frame = new BenchmarkFrame(HasherApi.BASE_SEED);
        long value = HasherApi.BASE_SEED;
    }

    /// Owner-local state for the scheduler-free parameterized work-cost measurements.
    @State(Scope.Thread)
    public static class WorkCostOnlyState {

        @Param({"0", "8", "24", "48", "64", "80", "96", "176", "256", "512"})
        public int workRounds;

        long value = HasherApi.BASE_SEED;
    }

    /// Shared lifecycle for one forced-mode fragment graph owned by a JMH trial.
    @State(Scope.Benchmark)
    public abstract static class PathState {

        @Param({"DIRECT", "STAGED"})
        public ForcedMode mode;

        @Param({"NATURAL"})
        public HandleLayout handleLayout;

        private final PaddedLongAdder counters = new PaddedLongAdder(SystemInfo.CPU_COUNT, true, true);
        private final PaddedLongAdder bodyTimingSampleCounts = new PaddedLongAdder(SystemInfo.CPU_COUNT, true, true);
        private final PaddedLongAdder bodyTimingElapsedNanos = new PaddedLongAdder(SystemInfo.CPU_COUNT, true, true);
        private final List<CloneableObject> pipelines = new ArrayList<>();
        private DiagnosticLease diagnosticLease;
        private DiagnosticDistributor distributor;
        private RepeatingSink[] sources;
        private HandleAcquisitionRecorder handleRecorder;
        private long[] sourceHandleIds;
        /// Logical CPUs in the same stable order as the selected worker cores.
        private int[] workerCpus;
        /// Physical cores aligned with `workerCpus` for owner-local acquisition attribution.
        private int[] workerCores;
        /// Per-worker completion totals accumulated during the current measurement iteration.
        private long[] iterationWorkerDeltas;
        /// Sum of fixed completion-window durations during the current measurement iteration.
        private long iterationElapsedNanos;
        /// Raw per-worker deltas aligned with the five JMH measurement iterations.
        private final List<long[]> measurementWorkerDeltas = new ArrayList<>();
        /// Fixed-window elapsed totals aligned with `measurementWorkerDeltas`.
        private final List<Long> measurementElapsedNanos = new ArrayList<>();
        /// Cumulative acquisition state before the first JMH warmup iteration.
        private HandleSnapshot preFirstIterationHandles;
        /// Acquisition state captured at the start of the current JMH iteration.
        private HandleSnapshot iterationHandleBefore;
        /// Raw acquisition deltas aligned with the three JMH warmup iterations.
        private final List<HandleSnapshot> warmupHandleDeltas = new ArrayList<>();
        /// Raw acquisition deltas aligned with the five JMH measurement iterations.
        private final List<HandleSnapshot> measurementHandleDeltas = new ArrayList<>();
        /// Existing execution-latency metric deltas aligned with measurement iterations.
        private final List<ServiceMetricSnapshot> measurementServiceDeltas = new ArrayList<>();
        /// Trial-local registry used only to observe Core's existing execution-latency signal.
        private SimpleMeterRegistry serviceRegistry;
        /// Per-worker latency summaries aligned with `workerCpus` and `workerCores`.
        private DistributionSummary[] serviceSummaries;
        /// Existing service metric state captured at the start of the current iteration.
        private ServiceMetricSnapshot iterationServiceBefore;
        /// Sparse body-timing state captured at the start of the current JMH iteration.
        private BodyTimingSnapshot iterationBodyTimingBefore;
        /// Raw sparse timing deltas aligned with the three JMH warmup iterations.
        private final List<BodyTimingSnapshot> warmupBodyTimingDeltas = new ArrayList<>();
        /// Raw sparse timing deltas aligned with the five JMH measurement iterations.
        private final List<BodyTimingSnapshot> measurementBodyTimingDeltas = new ArrayList<>();
        /// True only for the bounded executor-body sensor validation state.
        private boolean observeBodyTiming;
        /// True only while JMH is invoking the measured, non-warmup iteration.
        private boolean measurementIteration;
        /// Source availability retained for the fork-level diagnostic report.
        private SourceShape sourceShape;
        /// Executor work body retained for the fork-level diagnostic report.
        private Workload workload;
        /// Deterministic arithmetic rounds retained for reports; zero for no-op work.
        private int workRounds;

        /// Builds and starts the requested pinned fragment graph without the lattice monitor.
        protected final void setupPath(int workerCount, SourceShape sourceShape, Workload workload) {
            setupPath(workerCount, sourceShape, workload, workload == Workload.CPU_WORK ? CPU_WORK_ROUNDS : 0, false);
        }

        /// Builds the path with one deterministic body cost and optional existing latency telemetry.
        protected final void setupPath(
                int workerCount,
                SourceShape sourceShape,
                Workload workload,
                int workRounds,
                boolean observeServiceMetric) {
            setupPath(workerCount, sourceShape, workload, workRounds, observeServiceMetric, false);
        }

        /// Builds the path with independently gated service telemetry and executor-body timing.
        protected final void setupPath(
                int workerCount,
                SourceShape sourceShape,
                Workload workload,
                int workRounds,
                boolean observeServiceMetric,
                boolean observeBodyTiming) {
            try {
                if (workRounds < 0 || (workload == Workload.NO_OP && workRounds != 0)) {
                    throw new IllegalArgumentException("Work rounds must match a non-negative CPU workload");
                }
                this.sourceShape = sourceShape;
                this.workload = workload;
                this.workRounds = workRounds;
                this.workerCpus = new int[workerCount];
                this.workerCores = new int[workerCount];
                this.measurementWorkerDeltas.clear();
                this.measurementElapsedNanos.clear();
                this.preFirstIterationHandles = null;
                this.iterationHandleBefore = null;
                this.warmupHandleDeltas.clear();
                this.measurementHandleDeltas.clear();
                this.measurementServiceDeltas.clear();
                this.iterationServiceBefore = null;
                this.observeBodyTiming = observeBodyTiming;
                this.iterationBodyTimingBefore = null;
                this.warmupBodyTimingDeltas.clear();
                this.measurementBodyTimingDeltas.clear();
                DiagnosticDistributor.resetSharedRoutingState();
                this.diagnosticLease = new DiagnosticLease(this.mode, FIXED_BATCH_SIZE);
                if (observeServiceMetric) {
                    this.serviceRegistry = new SimpleMeterRegistry();
                }

                BitSet workerCores = selectWorkerCores(workerCount);
                pinHarness(workerCores);
                this.distributor = new DiagnosticDistributor(workerCores.nextSetBit(0));

                LatticeEdge[] handles = new LatticeEdge[SystemInfo.MAX_CORE_ID + 1];
                for (int core = workerCores.nextSetBit(0); core >= 0; core = workerCores.nextSetBit(core + 1)) {
                    handles[core] = new LatticeEdge(this.distributor.getDrainFlag());
                }
                this.distributor.setDrain(true);
                if (!this.distributor.setDownstreamMapping(workerCores, handles)) {
                    throw new IllegalStateException("Unable to publish the diagnostic core mapping");
                }

                CountingExecutor executor = observeBodyTiming
                        ? CountingExecutor.bodyTimingPrototype(
                                this.counters,
                                workload,
                                workRounds,
                                this.bodyTimingSampleCounts,
                                this.bodyTimingElapsedNanos)
                        : new CountingExecutor(-1, this.counters, workload, workRounds);
                BaseCloneableObject base = this.serviceRegistry == null
                        ? new BaseCloneableObject(executor)
                        : new BaseCloneableObject(
                                FragmentConfig.ofDefaults(WORK_COST_METRIC_PREFIX, this.serviceRegistry), executor);
                int workerIndex = 0;
                for (int core = workerCores.nextSetBit(0); core >= 0; core = workerCores.nextSetBit(core + 1)) {
                    BitSet cpus =
                            (BitSet) SystemInfo.getCoreInfo(core).getCpuSet().clone();
                    cpus.and(SystemInfo.getCpuSet());
                    int workerCpu = cpus.nextSetBit(0);
                    if (workerCpu < 0) {
                        throw new IllegalStateException("Selected worker core has no effective logical CPU");
                    }
                    this.workerCpus[workerIndex] = workerCpu;
                    this.workerCores[workerIndex++] = core;
                    CloneableObject pipeline = base.clone(new CloneConfig("FragmentPathCalibration", core, cpus));
                    pipeline.input(handles[core]);
                    pipeline.setDrainMode(true);
                    pipeline.start();
                    this.pipelines.add(pipeline);
                }
                if (this.serviceRegistry != null) {
                    this.serviceSummaries = serviceSummaries(this.serviceRegistry, this.workerCores);
                }
                awaitRegisteredWorkers(workerCount);
                for (CloneableObject pipeline : this.pipelines) {
                    pipeline.setDrainMode(false);
                }
                this.distributor.setDrain(false);

                int sourceCount = sourceCount(sourceShape, workerCount);
                this.handleRecorder = new HandleAcquisitionRecorder(sourceCount, this.workerCpus, this.workerCores);
                this.sourceHandleIds = new long[sourceCount];
                this.sources = new RepeatingSink[sourceCount];
                LatticeSource[] delegates = new LatticeSource[sourceCount];
                long idHash = HasherApi.mix(HasherApi.BASE_SEED);
                for (int i = 0; i < sourceCount; i++) {
                    BenchmarkFrame[] frames = BenchmarkFrame.generate(
                            FRAME_POOL_SIZE, false, idHash + i, HasherApi.BASE_SEED + (long) i * FRAME_POOL_SIZE);
                    this.sources[i] = new RepeatingSink(frames);
                    delegates[i] = this.sources[i].getDelegate();
                    if (this.handleLayout == HandleLayout.NATURAL) {
                        this.sourceHandleIds[i] = this.distributor.ingestTracked(delegates[i], i, this.handleRecorder);
                    }
                }
                if (this.handleLayout != HandleLayout.NATURAL) {
                    this.sourceHandleIds = this.distributor.ingestTracked(
                            delegates, this.workerCores, this.handleLayout, this.handleRecorder);
                }
            } catch (RuntimeException e) {
                closePath();
                throw e;
            }
        }

        /// Opens one measurement-only participation accumulator and ignores warmup iterations.
        @Setup(Level.Iteration)
        public final void setupIteration(IterationParams iterationParams) {
            this.iterationHandleBefore = this.handleRecorder.snapshot();
            if (this.serviceSummaries != null) {
                this.iterationServiceBefore = serviceMetricSnapshot(this.serviceSummaries);
            }
            if (this.observeBodyTiming) {
                this.iterationBodyTimingBefore = bodyTimingSnapshot();
            }
            if (this.preFirstIterationHandles == null) {
                this.preFirstIterationHandles = this.iterationHandleBefore;
            }
            this.measurementIteration = iterationParams.getType() == IterationType.MEASUREMENT;
            if (this.measurementIteration) {
                this.iterationWorkerDeltas = new long[this.workerCpus.length];
                this.iterationElapsedNanos = 0L;
            }
        }

        /// Retains one raw per-worker split aligned with the completed JMH measurement iteration.
        @TearDown(Level.Iteration)
        public final void tearDownIteration(IterationParams iterationParams) {
            HandleSnapshot handleDeltas = handleDelta(this.iterationHandleBefore, this.handleRecorder.snapshot());
            ServiceMetricSnapshot serviceDelta = this.serviceSummaries == null
                    ? null
                    : serviceMetricDelta(this.iterationServiceBefore, serviceMetricSnapshot(this.serviceSummaries));
            BodyTimingSnapshot bodyTimingDelta = this.observeBodyTiming
                    ? bodyTimingDelta(this.iterationBodyTimingBefore, bodyTimingSnapshot())
                    : null;
            if (iterationParams.getType() == IterationType.WARMUP) {
                this.warmupHandleDeltas.add(handleDeltas);
                if (bodyTimingDelta != null) {
                    this.warmupBodyTimingDeltas.add(bodyTimingDelta);
                }
            } else if (iterationParams.getType() == IterationType.MEASUREMENT) {
                this.measurementHandleDeltas.add(handleDeltas);
            }
            if (iterationParams.getType() == IterationType.MEASUREMENT) {
                this.measurementWorkerDeltas.add(this.iterationWorkerDeltas.clone());
                this.measurementElapsedNanos.add(this.iterationElapsedNanos);
                if (serviceDelta != null) {
                    this.measurementServiceDeltas.add(serviceDelta);
                }
                if (bodyTimingDelta != null) {
                    this.measurementBodyTimingDeltas.add(bodyTimingDelta);
                }
            }
            this.measurementIteration = false;
            this.iterationWorkerDeltas = null;
            this.iterationElapsedNanos = 0L;
            this.iterationHandleBefore = null;
            this.iterationServiceBefore = null;
            this.iterationBodyTimingBefore = null;
        }

        /// Waits for one JMH invocation's additional completed frames.
        final void awaitInvocation() {
            if (!this.measurementIteration) {
                awaitFrames(INVOCATION_FRAMES);
                return;
            }
            long[] before = workerCounts(this.counters, this.workerCpus);
            long start = System.nanoTime();
            awaitFrames(INVOCATION_FRAMES);
            long elapsed = System.nanoTime() - start;
            long[] after = workerCounts(this.counters, this.workerCpus);
            accumulateCompletionDeltas(this.iterationWorkerDeltas, before, after);
            this.iterationElapsedNanos = Math.addExact(this.iterationElapsedNanos, elapsed);
        }

        /// Closes all trial-owned graph state and releases the diagnostic override.
        @TearDown(Level.Trial)
        public final void tearDown() {
            try {
                beforeClose();
            } finally {
                closePath();
            }
        }

        /// Allows the single-worker state to sample the warmed graph before common teardown.
        protected void beforeClose() {
            reportParticipation();
            reportHandleAcquisition();
            reportServiceEstimate();
            reportBodyTimingEstimate();
        }

        /// Reports raw measurement splits and fork-level participation metrics before graph close.
        private void reportParticipation() {
            long[][] rawDeltas = this.measurementWorkerDeltas.toArray(long[][]::new);
            long[] finalWorkerCounts = workerCounts(this.counters, this.workerCpus);
            if (rawDeltas.length == 0) {
                LOGGER.info(
                        "Fragment worker participation mode={} sourceShape={} workload={} workRounds={} handleLayout={} batch={} workerCpus={} "
                                + "rawMeasurementDeltas=[] finalWorkerCounts={} verdict=NO_MEASUREMENT_SAMPLES",
                        this.mode,
                        this.sourceShape,
                        this.workload,
                        this.workRounds,
                        this.handleLayout,
                        FIXED_BATCH_SIZE,
                        Arrays.toString(this.workerCpus),
                        Arrays.toString(finalWorkerCounts));
                return;
            }
            double[][] fractions = new double[rawDeltas.length][];
            double[] dominance = new double[rawDeltas.length];
            double[] effectiveLanes = new double[rawDeltas.length];
            long[] aggregateDeltas = new long[this.workerCpus.length];
            long aggregateElapsedNanos = 0L;
            boolean lanesComparable = this.serviceSummaries == null || this.workRounds == CPU_WORK_ROUNDS;
            double singleLaneCeilingFramesPerSecond =
                    lanesComparable ? singleLaneCeiling(this.workload, this.mode) : 1.0;
            for (int i = 0; i < rawDeltas.length; i++) {
                ParticipationMetrics metrics = participationMetrics(
                        rawDeltas[i], this.measurementElapsedNanos.get(i), singleLaneCeilingFramesPerSecond);
                fractions[i] = metrics.fractions();
                dominance[i] = metrics.dominance();
                effectiveLanes[i] = lanesComparable ? metrics.effectiveLanes() : Double.NaN;
                for (int worker = 0; worker < aggregateDeltas.length; worker++) {
                    aggregateDeltas[worker] = Math.addExact(aggregateDeltas[worker], rawDeltas[i][worker]);
                }
                aggregateElapsedNanos = Math.addExact(aggregateElapsedNanos, this.measurementElapsedNanos.get(i));
            }
            ParticipationMetrics aggregate =
                    participationMetrics(aggregateDeltas, aggregateElapsedNanos, singleLaneCeilingFramesPerSecond);
            LOGGER.info(
                    "Fragment worker participation mode={} sourceShape={} workload={} workRounds={} handleLayout={} batch={} workerCpus={} "
                            + "rawMeasurementDeltas={} perMeasurementFractions={} perMeasurementDominance={} "
                            + "perMeasurementEffectiveLanes={} aggregateDeltas={} aggregateFractions={} "
                            + "aggregateDominance={} aggregateEffectiveLanes={} finalWorkerCounts={} "
                            + "singleLaneCeilingFramesPerSecond={}",
                    this.mode,
                    this.sourceShape,
                    this.workload,
                    this.workRounds,
                    this.handleLayout,
                    FIXED_BATCH_SIZE,
                    Arrays.toString(this.workerCpus),
                    Arrays.deepToString(rawDeltas),
                    Arrays.deepToString(fractions),
                    Arrays.toString(dominance),
                    Arrays.toString(effectiveLanes),
                    Arrays.toString(aggregateDeltas),
                    Arrays.toString(aggregate.fractions()),
                    aggregate.dominance(),
                    lanesComparable ? aggregate.effectiveLanes() : Double.NaN,
                    Arrays.toString(finalWorkerCounts),
                    singleLaneCeilingFramesPerSecond);
        }

        /// Reports source/handle acquisition and productive-service matrices for the complete fork.
        private void reportHandleAcquisition() {
            HandleSnapshot finalSnapshot = this.handleRecorder.snapshot();
            LOGGER.info(
                    "Fragment handle acquisition mode={} sourceShape={} workload={} workRounds={} handleLayout={} batch={} workerCpus={} "
                            + "workerCores={} sourceOrdinals={} sourceHandleIds={} preFirstIteration={} "
                            + "warmupDeltas={} measurementDeltas={} aggregateAttempts={} aggregateFailures={} "
                            + "aggregatePulledFrames={} firstProductiveOrder={}",
                    this.mode,
                    this.sourceShape,
                    this.workload,
                    this.workRounds,
                    this.handleLayout,
                    FIXED_BATCH_SIZE,
                    Arrays.toString(this.workerCpus),
                    Arrays.toString(this.workerCores),
                    Arrays.toString(sourceOrdinals(this.sourceHandleIds.length)),
                    Arrays.toString(this.sourceHandleIds),
                    formatHandleSnapshot(this.preFirstIterationHandles),
                    formatHandleSnapshots(this.warmupHandleDeltas),
                    formatHandleSnapshots(this.measurementHandleDeltas),
                    Arrays.deepToString(finalSnapshot.attempts()),
                    Arrays.deepToString(finalSnapshot.failures()),
                    Arrays.deepToString(finalSnapshot.pulledFrames()),
                    Arrays.toString(finalSnapshot.firstProductiveOrder()));
        }

        /// Reports measurement-only observations of Core's existing execution-latency signal.
        private void reportServiceEstimate() {
            if (this.measurementServiceDeltas.isEmpty()) {
                return;
            }
            long[][] rawCounts = new long[this.measurementServiceDeltas.size()][];
            double[][] rawTotals = new double[this.measurementServiceDeltas.size()][];
            double[][] perMeasurementEstimates = new double[this.measurementServiceDeltas.size()][];
            long[] aggregateCounts = new long[this.workerCpus.length];
            double[] aggregateTotals = new double[this.workerCpus.length];
            for (int iteration = 0; iteration < this.measurementServiceDeltas.size(); iteration++) {
                ServiceMetricSnapshot delta = this.measurementServiceDeltas.get(iteration);
                rawCounts[iteration] = delta.counts();
                rawTotals[iteration] = delta.totals();
                perMeasurementEstimates[iteration] = serviceEstimates(delta);
                for (int worker = 0; worker < aggregateCounts.length; worker++) {
                    aggregateCounts[worker] = Math.addExact(aggregateCounts[worker], rawCounts[iteration][worker]);
                    aggregateTotals[worker] += rawTotals[iteration][worker];
                }
            }
            ServiceMetricSnapshot aggregate = new ServiceMetricSnapshot(aggregateCounts, aggregateTotals);
            double total = 0.0;
            long count = 0L;
            for (int worker = 0; worker < aggregateCounts.length; worker++) {
                count = Math.addExact(count, aggregateCounts[worker]);
                total += aggregateTotals[worker];
            }
            double aggregateEstimate = count == 0L ? Double.NaN : total / count;
            LOGGER.info(
                    "Fragment work cost estimate mode={} sourceShape={} workload={} workRounds={} batch={} workerCpus={} "
                            + "rawReportCounts={} rawReportedTotalsNs={} perMeasurementWorkerEstimatesNs={} "
                            + "aggregateReportCounts={} aggregateReportedTotalsNs={} aggregateWorkerEstimatesNs={} "
                            + "aggregateEstimateNs={}",
                    this.mode,
                    this.sourceShape,
                    this.workload,
                    this.workRounds,
                    FIXED_BATCH_SIZE,
                    Arrays.toString(this.workerCpus),
                    Arrays.deepToString(rawCounts),
                    Arrays.deepToString(rawTotals),
                    Arrays.deepToString(perMeasurementEstimates),
                    Arrays.toString(aggregateCounts),
                    Arrays.toString(aggregateTotals),
                    Arrays.toString(serviceEstimates(aggregate)),
                    aggregateEstimate);
        }

        /// Snapshots sparse body-timing counters with acquire semantics in stable worker order.
        private BodyTimingSnapshot bodyTimingSnapshot() {
            return new BodyTimingSnapshot(
                    workerCounts(this.bodyTimingSampleCounts, this.workerCpus),
                    workerCounts(this.bodyTimingElapsedNanos, this.workerCpus));
        }

        /// Reports raw iteration deltas and the retained fork-worker body-cost estimates.
        private void reportBodyTimingEstimate() {
            if (!this.observeBodyTiming) {
                return;
            }
            long[][] warmupCounts = new long[this.warmupBodyTimingDeltas.size()][];
            long[][] warmupElapsed = new long[this.warmupBodyTimingDeltas.size()][];
            double[][] warmupEstimates = new double[this.warmupBodyTimingDeltas.size()][];
            for (int iteration = 0; iteration < this.warmupBodyTimingDeltas.size(); iteration++) {
                BodyTimingSnapshot delta = this.warmupBodyTimingDeltas.get(iteration);
                warmupCounts[iteration] = delta.counts();
                warmupElapsed[iteration] = delta.elapsedNanos();
                warmupEstimates[iteration] = bodyTimingEstimates(delta);
            }

            long[][] measurementCounts = new long[this.measurementBodyTimingDeltas.size()][];
            long[][] measurementElapsed = new long[this.measurementBodyTimingDeltas.size()][];
            double[][] measurementEstimates = new double[this.measurementBodyTimingDeltas.size()][];
            long[] aggregateCounts = new long[this.workerCpus.length];
            long[] aggregateElapsed = new long[this.workerCpus.length];
            for (int iteration = 0; iteration < this.measurementBodyTimingDeltas.size(); iteration++) {
                BodyTimingSnapshot delta = this.measurementBodyTimingDeltas.get(iteration);
                measurementCounts[iteration] = delta.counts();
                measurementElapsed[iteration] = delta.elapsedNanos();
                measurementEstimates[iteration] = bodyTimingEstimates(delta);
                for (int worker = 0; worker < aggregateCounts.length; worker++) {
                    aggregateCounts[worker] =
                            Math.addExact(aggregateCounts[worker], measurementCounts[iteration][worker]);
                    aggregateElapsed[worker] =
                            Math.addExact(aggregateElapsed[worker], measurementElapsed[iteration][worker]);
                }
            }
            BodyTimingSnapshot aggregate = new BodyTimingSnapshot(aggregateCounts, aggregateElapsed);
            LOGGER.info(
                    "Fragment executor body timing mode={} sourceShape={} workRounds={} batch={} sampleInterval={} "
                            + "workerCpus={} isolatedBodyCostNs={} liveHandles={} registeredWorkers={} "
                            + "warmupSampleCounts={} warmupElapsedNanos={} warmupWorkerEstimatesNs={} "
                            + "measurementSampleCounts={} measurementElapsedNanos={} measurementWorkerEstimatesNs={} "
                            + "aggregateSampleCounts={} aggregateElapsedNanos={} aggregateWorkerEstimatesNs={}",
                    this.mode,
                    this.sourceShape,
                    this.workRounds,
                    FIXED_BATCH_SIZE,
                    BODY_TIMING_INTERVAL,
                    Arrays.toString(this.workerCpus),
                    isolatedBodyCost(this.workRounds),
                    this.distributor.getUpstreamHandleCount(),
                    this.distributor.getThreadCount(),
                    Arrays.deepToString(warmupCounts),
                    Arrays.deepToString(warmupElapsed),
                    Arrays.deepToString(warmupEstimates),
                    Arrays.deepToString(measurementCounts),
                    Arrays.deepToString(measurementElapsed),
                    Arrays.deepToString(measurementEstimates),
                    Arrays.toString(aggregateCounts),
                    Arrays.toString(aggregateElapsed),
                    Arrays.toString(bodyTimingEstimates(aggregate)));
        }

        /// Returns the completed isolated-work calibration value for one retained validation point.
        private static double isolatedBodyCost(int workRounds) {
            return switch (workRounds) {
                case 24 -> 21.566;
                case 80 -> 70.689;
                case 96 -> 84.657;
                default -> Double.NaN;
            };
        }

        /// Collects nine fixed-completion samples from the continuously running graph.
        protected final double[] collectSamples() {
            double[] samples = new double[SAMPLE_COUNT];
            for (int i = 0; i < samples.length; i++) {
                long before = this.counters.sum();
                long target = completionTarget(before);
                long start = System.nanoTime();
                await(this.counters, target, TIMEOUT_NS);
                long elapsed = System.nanoTime() - start;
                long completed = Math.max(1L, this.counters.sum() - before);
                samples[i] = (double) elapsed / completed;
            }
            return samples;
        }

        /// Waits for `frames` more completions relative to a monotonic counter snapshot.
        protected final void awaitFrames(long frames) {
            long current = this.counters.sum();
            await(this.counters, Math.addExact(current, frames), TIMEOUT_NS);
        }

        /// Waits until the configured worker count has registered its owner-local upstream queue.
        private void awaitRegisteredWorkers(int workerCount) {
            long deadline = System.nanoTime() + TIMEOUT_NS;
            while (this.distributor.getThreadCount() < workerCount && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            if (this.distributor.getThreadCount() < workerCount) {
                throw new IllegalStateException("Timed out waiting for diagnostic fragment registration");
            }
        }

        /// Completes sources before workers, queues, and the setup-only override are released.
        private void closePath() {
            if (this.sources != null) {
                for (RepeatingSink source : this.sources) {
                    if (source != null) {
                        source.complete();
                    }
                }
                this.sources = null;
            }
            for (CloneableObject pipeline : this.pipelines) {
                pipeline.close();
            }
            this.pipelines.clear();
            if (this.distributor != null) {
                this.distributor.close();
                this.distributor = null;
            }
            this.handleRecorder = null;
            this.sourceHandleIds = null;
            this.serviceSummaries = null;
            if (this.serviceRegistry != null) {
                this.serviceRegistry.close();
                this.serviceRegistry = null;
            }
            PinnedThreadExecutor.closeAll();
            DiagnosticDistributor.resetSharedRoutingState();
            if (this.diagnosticLease != null) {
                this.diagnosticLease.close();
                this.diagnosticLease = null;
            }
        }
    }

    /// Setup-only lease that guarantees the package-private policy override is cleared exactly once.
    static final class DiagnosticLease implements AutoCloseable {

        private FragmentControlPolicy.DiagnosticOverride override;

        /// Publishes a fixed mode and batch before any benchmark fragment policy is constructed.
        DiagnosticLease(ForcedMode mode, long batchSize) {
            this.override = FragmentControlPolicy.installDiagnosticOverride(
                    FragmentControlPolicy.Mode.valueOf(mode.name()), batchSize);
        }

        /// Clears the captured override after all owning fragments have closed.
        @Override
        public void close() {
            if (this.override != null) {
                FragmentControlPolicy.clearDiagnosticOverride(this.override);
                this.override = null;
            }
        }
    }

    /// Minimal executor that preserves frame lifecycle while counting owner-CPU completions.
    static final class CountingExecutor extends AbstractExecutor {

        private final PaddedLongAdder counters;
        private final Workload workload;
        private final int workRounds;
        private final PaddedLongAdder bodyTimingSampleCounts;
        private final PaddedLongAdder bodyTimingElapsedNanos;
        private long workSink = HasherApi.BASE_SEED;

        /// Creates an executor prototype or pinned clone for the selected work body.
        CountingExecutor(int cpu, PaddedLongAdder counters, Workload workload, int workRounds) {
            super(cpu);
            this.counters = counters;
            this.workload = workload;
            this.workRounds = workRounds;
            this.bodyTimingSampleCounts = null;
            this.bodyTimingElapsedNanos = null;
        }

        /// Creates a sampling-disabled prototype that produces diagnostic pinned clones.
        private CountingExecutor(
                PaddedLongAdder counters,
                Workload workload,
                int workRounds,
                PaddedLongAdder bodyTimingSampleCounts,
                PaddedLongAdder bodyTimingElapsedNanos) {
            super(-1);
            this.counters = counters;
            this.workload = workload;
            this.workRounds = workRounds;
            this.bodyTimingSampleCounts = bodyTimingSampleCounts;
            this.bodyTimingElapsedNanos = bodyTimingElapsedNanos;
        }

        /// Creates one pinned clone with sparse timing around only its executor override.
        private CountingExecutor(
                int cpu,
                PaddedLongAdder counters,
                Workload workload,
                int workRounds,
                PaddedLongAdder bodyTimingSampleCounts,
                PaddedLongAdder bodyTimingElapsedNanos,
                int sampleInterval) {
            super(cpu, sampleInterval, System::nanoTime, elapsedNanos -> {
                bodyTimingSampleCounts.increment(cpu);
                bodyTimingElapsedNanos.add(cpu, elapsedNanos);
            });
            this.counters = counters;
            this.workload = workload;
            this.workRounds = workRounds;
            this.bodyTimingSampleCounts = bodyTimingSampleCounts;
            this.bodyTimingElapsedNanos = bodyTimingElapsedNanos;
        }

        /// Returns the trial prototype used only by the executor-body validation state.
        static CountingExecutor bodyTimingPrototype(
                PaddedLongAdder counters,
                Workload workload,
                int workRounds,
                PaddedLongAdder bodyTimingSampleCounts,
                PaddedLongAdder bodyTimingElapsedNanos) {
            return new CountingExecutor(counters, workload, workRounds, bodyTimingSampleCounts, bodyTimingElapsedNanos);
        }

        /// Executes the no-op frame, optionally performs CPU work, and publishes completion.
        @Override
        public void execute(AbstractFrame frame) {
            frame.execute();
            if (this.workload == Workload.CPU_WORK) {
                this.workSink = cpuWork(this.workSink ^ frame.getRoutingHash(), this.workRounds);
            }
            this.counters.increment(super.cpu);
        }

        /// Clones the benchmark executor for the fragment's selected logical CPU.
        @Override
        public CountingExecutor hookOnClone(int cpu) {
            if (this.bodyTimingSampleCounts != null) {
                return new CountingExecutor(
                        cpu,
                        this.counters,
                        this.workload,
                        this.workRounds,
                        this.bodyTimingSampleCounts,
                        this.bodyTimingElapsedNanos,
                        BODY_TIMING_INTERVAL);
            }
            return new CountingExecutor(cpu, this.counters, this.workload, this.workRounds);
        }
    }

    /// Production-shaped core distributor with an isolated shared-registry reset for trial teardown.
    static final class DiagnosticDistributor extends LatticeVertex {

        /// Creates a routing cache sized from the selected socket's production L3 budget.
        DiagnosticDistributor(int firstCore) {
            super(
                    "FragmentPathCalibrationDistributor",
                    SystemInfo.MAX_CORE_ID + 1,
                    (frame, mapSize) ->
                            (int) unsignedMultiplyHigh(Long.rotateLeft(frame.getRoutingHash(), 31), mapSize),
                    cacheCapacity(firstCore),
                    RoutingPolicy.SOCKET_LOCAL);
        }

        /// Connects one source through the production interceptor path with batch-level diagnostics.
        long ingestTracked(LatticeSource source, int sourceOrdinal, HandleAcquisitionRecorder recorder) {
            DiagnosticUpstreamInterceptor interceptor = new DiagnosticUpstreamInterceptor(sourceOrdinal, recorder);
            source.addDownstream(interceptor);
            interceptor.addUpstream(source);
            return interceptor.getId();
        }

        /// Publishes a complete two-source diagnostic layout before making its global count visible.
        long[] ingestTracked(
                LatticeSource[] sources, int[] workerCores, HandleLayout layout, HandleAcquisitionRecorder recorder) {
            if (sources.length != 2 || workerCores.length != 2 || layout == HandleLayout.NATURAL) {
                throw new IllegalArgumentException("Custom handle layouts require two sources and two workers");
            }
            DiagnosticUpstreamInterceptor[] interceptors = new DiagnosticUpstreamInterceptor[sources.length];
            long[] handleIds = new long[sources.length];
            for (int source = 0; source < sources.length; source++) {
                DiagnosticUpstreamInterceptor interceptor = new DiagnosticUpstreamInterceptor(source, recorder);
                sources[source].addDownstream(interceptor);
                interceptor.upstream = sources[source];
                interceptors[source] = interceptor;
                handleIds[source] = interceptor.getId();
            }

            for (int worker = 0; worker < workerCores.length; worker++) {
                int core = workerCores[worker];
                int first = layout == HandleLayout.PHASED && worker == 1 ? 1 : 0;
                int second = first ^ 1;
                UPSTREAMS[core].offer(interceptors[first]);
                UPSTREAMS[core].offer(interceptors[second]);
            }
            UPSTREAM_COUNT.getAndAdd(sources.length);
            return handleIds;
        }

        /// Restores the JVM-wide upstream registry after every isolated diagnostic trial.
        static void resetSharedRoutingState() {
            UpstreamQueue.UP_QUEUE.remove();
            UPSTREAM_COUNT.set(0L);
            THREAD_COUNT.set(0L);
            for (int i = 0; i < UPSTREAMS.length; i++) {
                if (UPSTREAMS[i] != null) {
                    UPSTREAMS[i].clear();
                }
                ACTIVE_PARTITIONS.set(i, 0L);
            }
        }

        /// Returns the bounded remote-cache capacity used by the production socket distributor.
        private static int cacheCapacity(int firstCore) {
            int socket = SystemInfo.getCoreInfo(firstCore).socket();
            long references = (long) (SystemInfo.socketL3Cache(socket) * 0.7) / QueueUtils.REFERENCE_SIZE;
            return (int) Math.min(Math.max(0L, references), Integer.MAX_VALUE);
        }

        /// Production interceptor behavior plus per-acquisition benchmark accounting.
        final class DiagnosticUpstreamInterceptor extends UpstreamInterceptor {

            private final int sourceOrdinal;
            private final HandleAcquisitionRecorder recorder;

            /// Retains the deterministic source identity and trial-owned diagnostic recorder.
            DiagnosticUpstreamInterceptor(int sourceOrdinal, HandleAcquisitionRecorder recorder) {
                this.sourceOrdinal = sourceOrdinal;
                this.recorder = recorder;
            }

            /// Records whether the calling worker obtained this handle without changing lock behavior.
            @Override
            public boolean acquireLock() {
                int worker = this.recorder.currentWorker();
                boolean acquired = super.acquireLock();
                this.recorder.recordAcquisition(this.sourceOrdinal, worker, acquired);
                return acquired;
            }

            /// Records productive frames after delegating the complete production pull behavior.
            @Override
            public long pull(
                    Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
                int worker = this.recorder.currentWorker();
                long frames = super.pull(consumer, stopCondition, demand);
                this.recorder.recordPulledFrames(this.sourceOrdinal, worker, frames);
                return frames;
            }
        }
    }

    /// Waits for a monotonic padded completion counter with a bounded spin/yield loop.
    static void await(PaddedLongAdder counters, long target, long timeoutNanos) {
        long deadline = System.nanoTime() + timeoutNanos;
        int spin = 0;
        while (System.nanoTime() < deadline) {
            if ((spin++ & 31) == 0 && counters.sum() >= target) {
                return;
            }
            if ((spin & 127) == 0) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
        throw new IllegalStateException(
                "Timed out waiting for completion target " + target + "; observed " + counters.sum());
    }

    /// Chooses same-kind active cores from one socket for a comparable worker fixture.
    private static BitSet selectWorkerCores(int count) {
        BitSet active = activeCoreSet();
        for (int socket = 0; socket <= SystemInfo.MAX_SOCKET_ID; socket++) {
            SocketInfo socketInfo = SystemInfo.getSocketInfo(socket);
            if (socketInfo == null) {
                continue;
            }
            BitSet socketCores = socketInfo.getCoreSet();
            socketCores.and(active);

            BitSet pCores = (BitSet) socketCores.clone();
            pCores.and(SystemInfo.getPCoreSet());
            if (pCores.cardinality() >= count) {
                return firstCores(pCores, count);
            }

            BitSet eCores = (BitSet) socketCores.clone();
            eCores.and(SystemInfo.getECoreSet());
            if (eCores.cardinality() >= count) {
                return firstCores(eCores, count);
            }

            if (SystemInfo.getPCoreSet().isEmpty()
                    && SystemInfo.getECoreSet().isEmpty()
                    && socketCores.cardinality() >= count) {
                return firstCores(socketCores, count);
            }
        }
        throw new IllegalStateException("The diagnostic requires same-kind workers on one socket");
    }

    /// Pins the JMH harness to an active physical core outside the worker set.
    private static void pinHarness(BitSet workerCores) {
        BitSet harnessCandidates = activeCoreSet();
        harnessCandidates.andNot(workerCores);
        int harnessCore = harnessCandidates.previousSetBit(Math.max(0, harnessCandidates.length() - 1));
        if (harnessCore < 0) {
            throw new IllegalStateException("The diagnostic requires a separate harness core");
        }
        BitSet cpus = (BitSet) SystemInfo.getCoreInfo(harnessCore).getCpuSet().clone();
        cpus.and(SystemInfo.getCpuSet());
        int harnessCpu = cpus.nextSetBit(0);
        if (harnessCpu < 0 || !ThreadTools.setAffinity(harnessCpu)) {
            throw new IllegalStateException("Unable to pin the diagnostic harness to core " + harnessCore);
        }
    }

    /// Builds the active physical-core set from process-visible logical CPUs.
    private static BitSet activeCoreSet() {
        BitSet active = new BitSet(SystemInfo.MAX_CORE_ID + 1);
        BitSet allowedCpus = SystemInfo.getCpuSet();
        for (int core = 0; core <= SystemInfo.MAX_CORE_ID; core++) {
            CoreInfo info = SystemInfo.getCoreInfo(core);
            if (info == null) {
                continue;
            }
            BitSet cpus = (BitSet) info.getCpuSet().clone();
            cpus.and(allowedCpus);
            if (!cpus.isEmpty()) {
                active.set(core);
            }
        }
        return active;
    }
}
