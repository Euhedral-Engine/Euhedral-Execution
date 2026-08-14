package io.euhedral_execution.core.control_plane;

import static io.euhedral_execution.core.utils.MathFunctions.unsignedMultiplyHigh;

import io.euhedral_execution.benchmarks.utils.RepeatingSink;
import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.control_plane.FragmentControlPolicy.ExecutionPath;
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
import io.euhedral_execution.core.ingest.AbstractIngestSink;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import io.euhedral_execution.core.metrics.MetricsAggregator;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    static final long DYNAMIC_RESPONSE_MAX_FRAMES = INVOCATION_FRAMES + FIXED_BATCH_SIZE;
    static final double BODY_TIMING_SEPARATION_MARGIN_NS = 5.0;
    static final double BODY_TIMING_NEUTRALITY_LIMIT_NS = 5.0;
    static final String BODY_TIMING_ENABLED_PROPERTY = "euhedral.fragment.bodyTiming.enabled";
    static final String PRODUCTION_TIMING_ENABLED_PROPERTY = "euhedral.fragment.productionTiming.enabled";
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

    /// Measures forced paths with the complete production estimator plumbing enabled or disabled.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void productionEstimatorOverhead(ProductionEstimatorState state) {
        state.awaitInvocation();
    }

    /// Measures whether nominally live handles correspond to independently productive pull opportunities.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void productivePullOpportunity(ProductivePullOpportunityState state) {
        state.awaitInvocation();
    }

    /// Measures productive observation overhead in otherwise identical forced production graphs.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void productiveHandleSensorOverhead(ProductiveHandleSensorOverheadState state) {
        state.awaitInvocation();
    }

    /// Confirms the productive-count production root at the four bounded physical rows.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void productiveHandleNormalPolicy(ProductiveHandleNormalPolicyState state) {
        state.awaitInvocation();
    }

    /// Maps when registered fragment workers become harmful and tests one fixed parked subset.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void idleEligibilityDiscovery(IdleEligibilityState state) {
        state.awaitInvocation();
    }

    /// Maps forced path crossover against actual source scarcity on homogeneous and mixed cores.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void sourceToCoreCrossover(SourceToCoreCrossoverState state) {
        state.awaitInvocation();
    }

    /// Measures normal contention-aware selection on the exact Phase 13 source/topology fixture.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void acquisitionContentionNormalPolicy(AcquisitionContentionNormalPolicyState state) {
        state.awaitInvocation();
    }

    /// Runs one bounded production park, reset, wake, and resumed-participation transition.
    @Benchmark
    public void productionIdleWakeSmoke(IdleWakeSmokeState state) {
        state.runOnce();
    }

    /// Measures the first production tree at the five predeclared resolved or guard-band rows.
    @Benchmark
    @OperationsPerInvocation(INVOCATION_FRAMES)
    public void normalPolicy(NormalPolicyState state) {
        state.awaitInvocation();
    }

    /// Executes one bounded cheap-expensive-cheap response sequence under normal scarce policy.
    @Benchmark
    public void dynamicPolicyResponse(DynamicPolicyState state) {
        state.runSequence();
    }

    /// Executes one bounded abundant-scarce-abundant contention and selector response sequence.
    @Benchmark
    public void dynamicAcquisitionContentionResponse(DynamicAcquisitionContentionState state) {
        state.runSequence();
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

    /// Resolves either one CPU-relative configured ratio or one explicit physical source count.
    static int crossoverSourceCount(int cpuCount, int ratioDivisor, int explicitSources) {
        if (cpuCount <= 0 || ratioDivisor < 0 || explicitSources < 0) {
            throw new IllegalArgumentException("CPU, ratio, and explicit source counts must be valid");
        }
        if ((ratioDivisor == 0) == (explicitSources == 0)) {
            throw new IllegalArgumentException("Exactly one source-count mechanism is required");
        }
        if (ratioDivisor > 0) {
            return Math.max(1, cpuCount / ratioDivisor);
        }
        return explicitSources;
    }

    /// Selects the Phase 13 workers after reserving physical core zero from fragment execution.
    static BitSet crossoverWorkerCores(CrossoverTopology topology, BitSet activeCores, BitSet pCores, BitSet eCores) {
        if (activeCores.cardinality() < 2 || !activeCores.get(0)) {
            throw new IllegalStateException("The crossover fixture requires reservable physical core zero");
        }
        BitSet workers = (BitSet) activeCores.clone();
        workers.clear(0);
        if (topology == CrossoverTopology.HOMOGENEOUS_P) {
            workers.and(pCores);
            if (workers.isEmpty()) {
                throw new IllegalStateException("The crossover fixture requires active P-core workers");
            }
            return workers;
        }

        BitSet activeP = (BitSet) activeCores.clone();
        activeP.and(pCores);
        BitSet activeE = (BitSet) activeCores.clone();
        activeE.and(eCores);
        if (!activeP.isEmpty() && !activeE.isEmpty()) {
            BitSet selectedP = (BitSet) workers.clone();
            selectedP.and(pCores);
            BitSet selectedE = (BitSet) workers.clone();
            selectedE.and(eCores);
            if (selectedP.isEmpty() || selectedE.isEmpty()) {
                throw new IllegalStateException("The full-machine crossover fixture lost one declared core class");
            }
        }
        return workers;
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

    /// Reports whether every cache-sharing mask is disjoint from every other selected worker mask.
    static boolean pairwiseDisjoint(BitSet[] masks) {
        for (int left = 0; left < masks.length; left++) {
            if (masks[left] == null || masks[left].isEmpty()) {
                return false;
            }
            for (int right = left + 1; right < masks.length; right++) {
                if (masks[right] == null) {
                    return false;
                }
                BitSet overlap = (BitSet) masks[left].clone();
                overlap.and(masks[right]);
                if (!overlap.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
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

    /// Lifecycle evidence for the production-reachable empty-live-source fixture.
    record OpportunitySnapshot(
            int liveHandles,
            int registeredWorkers,
            long emptyQueueSize,
            long emptyQueueDemand,
            long emptyQueueOfferCount,
            boolean emptyQueueComplete) {}

    /// Exact physical source counts and optional fixed polling count for an idle row.
    record IdleFixture(int productiveHandles, int emptyLiveHandles, int activePollingWorkers) {

        IdleFixture {
            if (productiveHandles <= 0 || emptyLiveHandles < 0 || emptyLiveHandles > 1 || activePollingWorkers < 0) {
                throw new IllegalArgumentException("Idle fixture requires productive handles and at most one empty");
            }
        }

        /// Returns the complete live-handle count without overflow.
        int liveHandles() {
            return Math.addExact(this.productiveHandles, this.emptyLiveHandles);
        }
    }

    /// Exact configured and physical source/topology inputs for one Phase 13 row.
    record CrossoverFixture(
            CrossoverTopology topology,
            int ratioDivisor,
            int cpuRatioBasis,
            int productiveSources,
            BitSet workerCores) {

        CrossoverFixture {
            if (topology == null
                    || ratioDivisor < 0
                    || cpuRatioBasis <= 0
                    || productiveSources <= 0
                    || workerCores == null
                    || workerCores.isEmpty()) {
                throw new IllegalArgumentException("Crossover fixture requires complete physical counts");
            }
            workerCores = (BitSet) workerCores.clone();
        }

        /// Isolates the selected-core set retained beyond setup.
        @Override
        public BitSet workerCores() {
            return (BitSet) this.workerCores.clone();
        }

        /// Retains the configured CPU-relative label separately from actual counts.
        String configuredRatio() {
            return this.ratioDivisor == 0 ? "EXPLICIT" : "1:" + this.ratioDivisor;
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

    /// Derives successful service attempts from the acquisition evidence without another hot counter.
    static long[][] successfulServiceAttempts(HandleSnapshot snapshot) {
        return matrixDifferenceSaturated(snapshot.attempts, snapshot.failures);
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

    /// Applies checked source-by-worker subtraction for derived diagnostic matrices.
    private static long[][] matrixDifferenceSaturated(long[][] left, long[][] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("Handle diagnostic matrices must have equal source counts");
        }
        long[][] result = new long[left.length][];
        for (int source = 0; source < left.length; source++) {
            if (left[source].length != right[source].length) {
                throw new IllegalArgumentException("Handle diagnostic matrices must have equal worker counts");
            }
            result[source] = new long[left[source].length];
            for (int worker = 0; worker < left[source].length; worker++) {
                result[source][worker] = Math.max(0L, left[source][worker] - right[source][worker]);
            }
        }
        return result;
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

        /// Records one delegated pull result and any productive frames with one callback per pull.
        void recordPullResult(int source, int worker, long frames) {
            if (frames > 0L) {
                int cpu = workerCpu(source, worker);
                this.pulledFrames[source].add(cpu, frames);
                recordFirstProductive(source, worker);
            }
        }

        /// Publishes the globally ordered first productive source/worker event once per cell.
        private void recordFirstProductive(int source, int worker) {
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
                + ", successfulServiceAttempts=" + Arrays.deepToString(successfulServiceAttempts(snapshot))
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

    /// Worker topologies retained separately for Phase 13 comparison.
    public enum CrossoverTopology {
        HOMOGENEOUS_P,
        FULL_MACHINE
    }

    /// Phase 8 fixtures that vary only live and independently productive upstream opportunities.
    public enum OpportunityFixture {
        TWO_PRODUCTIVE_HANDLES(2, 2),
        ONE_PRODUCTIVE_HANDLE(1, 1),
        TWO_LIVE_ONE_PRODUCTIVE(2, 1);

        final int liveHandles;
        final int productiveHandles;

        /// Retains the expected physical handle counts used by fixture validation and reporting.
        OpportunityFixture(int liveHandles, int productiveHandles) {
            this.liveHandles = liveHandles;
            this.productiveHandles = productiveHandles;
        }
    }

    /// Public JMH parameter mapped to the package-private production mode during trial setup.
    public enum ForcedMode {
        DIRECT,
        STAGED
    }

    /// Predeclared normal-tree rows without benchmark source labels entering production policy.
    public enum NormalPolicyCase {
        PLENTIFUL_24(SourceShape.PLENTIFUL, 24),
        PLENTIFUL_96(SourceShape.PLENTIFUL, 96),
        SCARCE_24(SourceShape.SCARCE, 24),
        SCARCE_88(SourceShape.SCARCE, 88),
        SCARCE_96(SourceShape.SCARCE, 96);

        final SourceShape sourceShape;
        final int workRounds;

        /// Retains the exact availability and work point represented by one bounded row.
        NormalPolicyCase(SourceShape sourceShape, int workRounds) {
            this.sourceShape = sourceShape;
            this.workRounds = workRounds;
        }
    }

    /// Bounded physical rows for productive-count production-root confirmation.
    public enum ProductivePolicyCase {
        TWO_PRODUCTIVE_EXPENSIVE(OpportunityFixture.TWO_PRODUCTIVE_HANDLES, 512, ForcedMode.DIRECT),
        ONE_PRODUCTIVE_EXPENSIVE(OpportunityFixture.ONE_PRODUCTIVE_HANDLE, 512, ForcedMode.STAGED),
        TWO_LIVE_ONE_PRODUCTIVE_EXPENSIVE(OpportunityFixture.TWO_LIVE_ONE_PRODUCTIVE, 512, ForcedMode.STAGED),
        TWO_LIVE_ONE_PRODUCTIVE_CHEAP(OpportunityFixture.TWO_LIVE_ONE_PRODUCTIVE, 24, ForcedMode.DIRECT);

        final OpportunityFixture opportunityFixture;
        final int workRounds;
        final ForcedMode expectedMode;

        /// Retains one predeclared physical fixture, work point, and expected normal mode.
        ProductivePolicyCase(OpportunityFixture opportunityFixture, int workRounds, ForcedMode expectedMode) {
            this.opportunityFixture = opportunityFixture;
            this.workRounds = workRounds;
            this.expectedMode = expectedMode;
        }
    }

    /// Same-build full-graph controls for successful service and corrected empty misses.
    public enum ProductiveSensorOverheadCase {
        PRODUCTIVE_FAST(OpportunityFixture.TWO_PRODUCTIVE_HANDLES, ForcedMode.DIRECT),
        EMPTY_MISS(OpportunityFixture.TWO_LIVE_ONE_PRODUCTIVE, ForcedMode.STAGED);

        final OpportunityFixture opportunityFixture;
        final ForcedMode mode;

        /// Retains the physical source state and fixed path for one sensor overhead control.
        ProductiveSensorOverheadCase(OpportunityFixture opportunityFixture, ForcedMode mode) {
            this.opportunityFixture = opportunityFixture;
            this.mode = mode;
        }
    }

    public enum ProductiveObservation {
        ENABLED,
        LIVENESS_ONLY
    }

    /// Forced production-sensor overhead controls selected by the Phase 7 blueprint.
    public enum ProductionEstimatorCase {
        PLENTIFUL_DIRECT(SourceShape.PLENTIFUL, ForcedMode.DIRECT),
        SCARCE_STAGED(SourceShape.SCARCE, ForcedMode.STAGED);

        final SourceShape sourceShape;
        final ForcedMode mode;

        /// Retains the exact source shape and forced winner used by one overhead row.
        ProductionEstimatorCase(SourceShape sourceShape, ForcedMode mode) {
            this.sourceShape = sourceShape;
            this.mode = mode;
        }
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

    /// Shared JMH parameter for states that intentionally force one existing fragment path.
    public abstract static class ForcedPathState extends PathState {

        @Param({"DIRECT", "STAGED"})
        public ForcedMode mode;

        @Override
        final ForcedMode forcedMode() {
            return this.mode;
        }
    }

    /// One-worker state that records cold and post-warmup no-op path samples.
    @State(Scope.Benchmark)
    public static class SingleWorkerState extends ForcedPathState {

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
    public static class NoOpDecisionState extends ForcedPathState {

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
    public static class CpuWorkDecisionState extends ForcedPathState {

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
    public static class WorkCostDecisionState extends ForcedPathState {

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
    public static class ExecutorBodyCostState extends ForcedPathState {

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

    /// Two-worker forced-path state for the Phase 8 productive-opportunity experiment.
    @State(Scope.Benchmark)
    public static class ProductivePullOpportunityState extends ForcedPathState {

        @Param({"TWO_PRODUCTIVE_HANDLES", "ONE_PRODUCTIVE_HANDLE", "TWO_LIVE_ONE_PRODUCTIVE"})
        public OpportunityFixture opportunityFixture;

        @Param({"512"})
        public int workRounds;

        /// Starts one physical availability row with raw executor timing and existing handle evidence.
        @Setup(Level.Trial)
        public void setup() {
            setupOpportunityPath(this.opportunityFixture, this.workRounds);
        }
    }

    /// Forced full production graphs with worker-local observation enabled or bypassed.
    @State(Scope.Benchmark)
    public static class ProductiveHandleSensorOverheadState extends PathState {

        @Param({"PRODUCTIVE_FAST", "EMPTY_MISS"})
        public ProductiveSensorOverheadCase overheadCase;

        @Param({"ENABLED", "LIVENESS_ONLY"})
        public ProductiveObservation productiveObservation;

        @Override
        final ForcedMode forcedMode() {
            return this.overheadCase.mode;
        }

        /// Starts one forced rounds-24 graph with only benchmark interception behavior varied.
        @Setup(Level.Trial)
        public void setup() {
            setupSensorOverheadPath(
                    this.overheadCase.opportunityFixture, this.productiveObservation == ProductiveObservation.ENABLED);
        }
    }

    /// Normal production policy over the four bounded productive-opportunity rows.
    @State(Scope.Benchmark)
    public static class ProductiveHandleNormalPolicyState extends PathState {

        @Param({
            "TWO_PRODUCTIVE_EXPENSIVE",
            "ONE_PRODUCTIVE_EXPENSIVE",
            "TWO_LIVE_ONE_PRODUCTIVE_EXPENSIVE",
            "TWO_LIVE_ONE_PRODUCTIVE_CHEAP"
        })
        public ProductivePolicyCase policyCase;

        @Override
        final ForcedMode forcedMode() {
            return null;
        }

        /// Starts the normal tree with the retained real source fixture and batch cap 32.
        @Setup(Level.Trial)
        public void setup() {
            setupProductivePolicyPath(this.policyCase.opportunityFixture, this.policyCase.workRounds);
        }

        /// Requires every worker's completed-batch snapshot to match its resolved local observation.
        @Override
        void validatePolicySnapshots(
                IterationType iterationType, ControlPlaneFragment.FragmentPolicySnapshot[] snapshots) {
            ExecutionPath expected = ExecutionPath.valueOf(this.policyCase.expectedMode.name());
            for (ControlPlaneFragment.FragmentPolicySnapshot snapshot : snapshots) {
                if (snapshot.bodyCostHistoryCount() < FragmentControlPolicy.BODY_COST_MIN_HISTORY) {
                    failPolicyValidation("Productive normal policy estimator did not initialize: " + snapshot);
                }
                if (snapshot.productiveHandleCount() != this.policyCase.opportunityFixture.productiveHandles) {
                    failPolicyValidation("Productive normal policy observed the wrong owner-local count: " + snapshot);
                }
                if (snapshot.executionPath() != expected) {
                    failPolicyValidation("Productive normal policy selected the wrong mode: " + snapshot);
                }
            }
        }
    }

    /// Independently parameterized normal-policy state for the bounded Phase 11 discovery rows.
    @State(Scope.Benchmark)
    public static class IdleEligibilityState extends PathState {

        @Param({"1"})
        public int workerCount;

        @Param({"1"})
        public int productiveHandles;

        @Param({"0"})
        public int emptyLiveHandles;

        @Param({"0"})
        public int workRounds;

        @Param({"0"})
        public int activePollingWorkers;

        @Override
        final ForcedMode forcedMode() {
            return null;
        }

        /// Starts one normal production graph with an optional fixed polling subset.
        @Setup(Level.Trial)
        public void setup() {
            setupIdleEligibilityPath(
                    this.workerCount,
                    this.productiveHandles,
                    this.emptyLiveHandles,
                    this.workRounds,
                    this.activePollingWorkers);
        }

        /// Validates active and intentionally parked owner-local snapshots separately.
        @Override
        void validatePolicySnapshots(
                IterationType iterationType, ControlPlaneFragment.FragmentPolicySnapshot[] snapshots) {
            int expectedActive = expectedPollingWorkers();
            ExecutionPath expectedExecutionPath = this.productiveHandles >= this.workerCount || this.workRounds < 96
                    ? ExecutionPath.DIRECT
                    : ExecutionPath.STAGED;
            for (int worker = 0; worker < snapshots.length; worker++) {
                ControlPlaneFragment.FragmentPolicySnapshot snapshot = snapshots[worker];
                boolean expectedPolling = worker < expectedActive;
                if (snapshot.activePolling() != expectedPolling) {
                    failPolicyValidation("Idle discovery observed the wrong polling subset: " + snapshot);
                } else if (this.activePollingWorkers > 0 && !expectedPolling) {
                    if (snapshot.bodyCostHistoryCount() != 0 || snapshot.productiveHandleCount() != 0L) {
                        failPolicyValidation("Fixed parked worker made unexpected policy progress: " + snapshot);
                    }
                    continue;
                }
                if (snapshot.bodyCostHistoryCount() < FragmentControlPolicy.BODY_COST_MIN_HISTORY) {
                    failPolicyValidation("Idle discovery body estimator did not initialize: " + snapshot);
                }
                if (snapshot.productiveHandleCount() != this.productiveHandles) {
                    failPolicyValidation("Idle discovery observed the wrong productive count: " + snapshot);
                }
                if (snapshot.registeredWorkers() != this.workerCount || snapshot.workerRank() != worker) {
                    failPolicyValidation("Production idle rank or registration changed: " + snapshot);
                }
                if (snapshot.productionParked() == expectedPolling) {
                    failPolicyValidation("Production parked state disagreed with polling state: " + snapshot);
                }
                if (snapshot.executionPath() != expectedExecutionPath) {
                    failPolicyValidation("Idle discovery selected the wrong production mode: " + snapshot);
                }
            }
        }

        /// Returns the fixed diagnostic subset or the conservative production polling quota.
        private int expectedPollingWorkers() {
            if (this.activePollingWorkers > 0) {
                return this.activePollingWorkers;
            }
            if (this.workRounds != 0 || this.productiveHandles >= this.workerCount) {
                return this.workerCount;
            }
            return Math.max(1, this.productiveHandles);
        }

        /// Rejects worker disappearance separately from the intentional parked-worker zeroes.
        @Override
        protected void beforeClose() {
            validateIdleParticipation();
            super.beforeClose();
        }
    }

    /// Forced-path Phase 13 state with independent configured and physical source counts.
    @State(Scope.Benchmark)
    public static class SourceToCoreCrossoverState extends PathState {

        @Param({"HOMOGENEOUS_P"})
        public CrossoverTopology topology;

        @Param({"0"})
        public int ratioDivisor;

        @Param({"1"})
        public int productiveSources;

        @Param({"96"})
        public int workRounds;

        @Param({"DIRECT", "STAGED"})
        public ForcedMode mode;

        @Override
        final ForcedMode forcedMode() {
            return this.mode;
        }

        /// Starts one exact source/topology row with existing path and body diagnostics.
        @Setup(Level.Trial)
        public void setup() {
            setupCrossoverPath(this.topology, this.ratioDivisor, this.productiveSources, this.workRounds);
        }

        /// Requires forced mode, physical counts, stable rank, and all-worker polling.
        @Override
        void validatePolicySnapshots(
                IterationType iterationType, ControlPlaneFragment.FragmentPolicySnapshot[] snapshots) {
            ExecutionPath expected = ExecutionPath.valueOf(this.mode.name());
            int expectedSources = crossoverProductiveSources();
            for (int worker = 0; worker < snapshots.length; worker++) {
                ControlPlaneFragment.FragmentPolicySnapshot snapshot = snapshots[worker];
                if (snapshot == null
                        || snapshot.executionPath() != expected
                        || snapshot.productiveHandleCount() != expectedSources
                        || snapshot.registeredWorkers() != snapshots.length
                        || snapshot.workerRank() != worker
                        || !snapshot.activePolling()
                        || snapshot.productionParked()
                        || snapshot.acquireContention() == null
                        || snapshot.acquireContention().enabled() != UpstreamQueue.acquireContentionEnabled()
                        || (snapshot.acquireContention().enabled()
                                && !snapshot.acquireContention().initialized())) {
                    failPolicyValidation("Crossover physical state changed: " + Arrays.toString(snapshots));
                    return;
                }
            }
        }

        /// Rejects a registered worker that never participates during the retained fork.
        @Override
        protected void beforeClose() {
            validateCrossoverParticipation();
            super.beforeClose();
        }
    }

    /// Normal production selector over the Phase 13 physical source-to-core fixture.
    @State(Scope.Benchmark)
    public static class AcquisitionContentionNormalPolicyState extends PathState {

        @Param({"FULL_MACHINE"})
        public CrossoverTopology topology;

        @Param({"0"})
        public int ratioDivisor;

        @Param({"1"})
        public int productiveSources;

        @Param({"96"})
        public int workRounds;

        @Override
        final ForcedMode forcedMode() {
            return null;
        }

        /// Starts one exact source/topology row with production body and policy observation active.
        @Setup(Level.Trial)
        public void setup() {
            setupCrossoverPath(this.topology, this.ratioDivisor, this.productiveSources, this.workRounds);
        }

        /// Requires exact physical inputs while allowing the production selector to choose each lane.
        @Override
        void validatePolicySnapshots(
                IterationType iterationType, ControlPlaneFragment.FragmentPolicySnapshot[] snapshots) {
            int expectedSources = crossoverProductiveSources();
            for (int worker = 0; worker < snapshots.length; worker++) {
                ControlPlaneFragment.FragmentPolicySnapshot snapshot = snapshots[worker];
                if (snapshot == null
                        || snapshot.bodyCostHistoryCount() < FragmentControlPolicy.BODY_COST_MIN_HISTORY
                        || snapshot.productiveHandleCount() != expectedSources
                        || snapshot.registeredWorkers() != snapshots.length
                        || snapshot.workerRank() != worker
                        || (snapshot.productionParked() || snapshot.highContentionParked()) == snapshot.activePolling()
                        || snapshot.acquireContention() == null
                        || snapshot.acquireContention().enabled() != UpstreamQueue.acquireContentionEnabled()
                        || snapshot.acquireContention().selectionEnabled()
                                != FragmentControlPolicy.acquireContentionSelectionEnabled()
                        || (snapshot.activePolling()
                                && snapshot.acquireContention().enabled()
                                && !snapshot.acquireContention().initialized())) {
                    failPolicyValidation("Normal crossover physical state changed: " + Arrays.toString(snapshots));
                    return;
                }
            }
        }

        /// Rejects a non-parked registered worker that never participates during the retained fork.
        @Override
        protected void beforeClose() {
            validateNormalCrossoverParticipation();
            super.beforeClose();
        }
    }

    /// One-shot production lifecycle state for the minimal idle integration.
    @State(Scope.Benchmark)
    public static class IdleWakeSmokeState extends PathState {

        private boolean completed;
        private long resetCleared;
        private long[] parkedCounts;
        private long[] resetWakeCounts;
        private long[] productiveWakeCounts;
        private ControlPlaneFragment.FragmentPolicySnapshot[] parkedSnapshots;
        private ControlPlaneFragment.FragmentPolicySnapshot[] resetWakeSnapshots;
        private ControlPlaneFragment.FragmentPolicySnapshot[] productiveWakeSnapshots;

        @Override
        final ForcedMode forcedMode() {
            return null;
        }

        /// Starts the real two-worker, one-productive-handle near-no-op production graph.
        @Setup(Level.Trial)
        public void setup() {
            this.completed = false;
            setupIdleEligibilityPath(2, 1, 0, 0, 0);
        }

        /// Proves reset and a newly published productive source wake the production-idle worker.
        void runOnce() {
            if (this.completed) {
                return;
            }

            awaitParkedScarce();
            this.parkedSnapshots = policySnapshots();
            this.parkedCounts = workerCompletionCounts();

            long deadline = System.nanoTime() + TIMEOUT_NS;
            this.resetCleared = resetPipelines(deadline);
            awaitSecondWorkerProgress(this.parkedCounts[1]);
            this.resetWakeSnapshots = policySnapshots();
            this.resetWakeCounts = workerCompletionCounts();

            awaitParkedScarce();
            long beforeProductiveWake = workerCompletionCounts()[1];
            BenchmarkFrame[] frames =
                    BenchmarkFrame.generate(FRAME_POOL_SIZE, false, HasherApi.mix(12L), HasherApi.mix(13L));
            publishAdditionalProductiveSource(frames);

            awaitBothWorkersProductive(beforeProductiveWake);
            this.productiveWakeSnapshots = policySnapshots();
            this.productiveWakeCounts = workerCompletionCounts();
            this.completed = true;
        }

        /// Waits for the stable cheap/scarce polling and parked split.
        private void awaitParkedScarce() {
            long deadline = System.nanoTime() + TIMEOUT_NS;
            while (System.nanoTime() < deadline) {
                ControlPlaneFragment.FragmentPolicySnapshot[] snapshots = policySnapshots();
                if (snapshots.length == 2
                        && snapshots[0].activePolling()
                        && snapshots[1].productionParked()
                        && snapshots[0].productiveHandleCount() == 1L
                        && snapshots[1].productiveHandleCount() == 1L) {
                    return;
                }
                Thread.onSpinWait();
            }
            throw new IllegalStateException(
                    "Timed out waiting for production idle entry: " + Arrays.toString(policySnapshots()));
        }

        /// Waits for reset to release the parked worker into conservative startup polling.
        private void awaitSecondWorkerProgress(long priorCount) {
            long deadline = System.nanoTime() + TIMEOUT_NS;
            while (System.nanoTime() < deadline) {
                if (workerCompletionCounts()[1] > priorCount) {
                    return;
                }
                Thread.onSpinWait();
            }
            throw new IllegalStateException("Timed out waiting for reset to wake the parked worker");
        }

        /// Waits for the newly published opportunity to restore both workers to active polling.
        private void awaitBothWorkersProductive(long priorSecondWorkerCount) {
            long deadline = System.nanoTime() + TIMEOUT_NS;
            while (System.nanoTime() < deadline) {
                ControlPlaneFragment.FragmentPolicySnapshot[] snapshots = policySnapshots();
                if (snapshots.length == 2
                        && snapshots[0].activePolling()
                        && snapshots[1].activePolling()
                        && snapshots[0].productiveHandleCount() == 2L
                        && snapshots[1].productiveHandleCount() == 2L
                        && workerCompletionCounts()[1] > priorSecondWorkerCount) {
                    return;
                }
                Thread.onSpinWait();
            }
            throw new IllegalStateException(
                    "Timed out waiting for productive wake: " + Arrays.toString(policySnapshots()));
        }

        /// Records the one-shot transition before the common close path tests parked teardown.
        @Override
        protected void beforeClose() {
            if (!this.completed || registeredWorkerCount() != 2) {
                throw new IllegalStateException("Production idle wake smoke did not complete");
            }
            LOGGER.info(
                    "Fragment production idle wake smoke registeredWorkers={} resetCleared={} parkedCounts={} "
                            + "parkedSnapshots={} resetWakeCounts={} resetWakeSnapshots={} productiveWakeCounts={} "
                            + "productiveWakeSnapshots={}",
                    registeredWorkerCount(),
                    this.resetCleared,
                    Arrays.toString(this.parkedCounts),
                    Arrays.toString(this.parkedSnapshots),
                    Arrays.toString(this.resetWakeCounts),
                    Arrays.toString(this.resetWakeSnapshots),
                    Arrays.toString(this.productiveWakeCounts),
                    Arrays.toString(this.productiveWakeSnapshots));
        }
    }

    /// Same-build forced controls for the complete production estimator's overhead.
    @State(Scope.Benchmark)
    public static class ProductionEstimatorState extends PathState {

        @Param({"PLENTIFUL_DIRECT", "SCARCE_STAGED"})
        public ProductionEstimatorCase estimatorCase;

        private boolean samplingEnabled;

        @Override
        final ForcedMode forcedMode() {
            return this.estimatorCase.mode;
        }

        /// Starts a forced rounds-24 row with production sampling controlled by one property.
        @Setup(Level.Trial)
        public void setup() {
            this.samplingEnabled = Boolean.parseBoolean(
                    System.getProperty(PRODUCTION_TIMING_ENABLED_PROPERTY, Boolean.TRUE.toString()));
            setupPath(
                    2,
                    this.estimatorCase.sourceShape,
                    Workload.CPU_WORK,
                    24,
                    false,
                    false,
                    this.samplingEnabled,
                    true,
                    null);
        }

        /// Checks that forced mode remains fixed and sampling follows only the explicit property.
        @Override
        void validatePolicySnapshots(
                IterationType iterationType, ControlPlaneFragment.FragmentPolicySnapshot[] snapshots) {
            ExecutionPath expected = ExecutionPath.valueOf(this.estimatorCase.mode.name());
            for (ControlPlaneFragment.FragmentPolicySnapshot snapshot : snapshots) {
                if (snapshot.executionPath() != expected) {
                    failPolicyValidation("Forced production estimator changed mode: " + snapshot);
                }
                if (this.samplingEnabled
                        && snapshot.bodyCostHistoryCount() < FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES) {
                    failPolicyValidation("Enabled production estimator did not initialize: " + snapshot);
                }
                if (!this.samplingEnabled && snapshot.bodyCostHistoryCount() != 0) {
                    failPolicyValidation("Disabled production estimator collected history: " + snapshot);
                }
            }
        }
    }

    /// Normal production-tree rows selected before measurement rather than searched afterward.
    @State(Scope.Benchmark)
    public static class NormalPolicyState extends PathState {

        @Param({"PLENTIFUL_24", "PLENTIFUL_96", "SCARCE_24", "SCARCE_88", "SCARCE_96"})
        public NormalPolicyCase policyCase;

        @Override
        final ForcedMode forcedMode() {
            return null;
        }

        /// Starts the normal tree with a fixed production maximum batch of 32.
        @Setup(Level.Trial)
        public void setup() {
            setupPath(
                    2,
                    this.policyCase.sourceShape,
                    Workload.CPU_WORK,
                    this.policyCase.workRounds,
                    false,
                    false,
                    false,
                    true,
                    null);
        }

        /// Enforces the four resolved leaves and the predeclared stable rounds-88 guard row.
        @Override
        void validatePolicySnapshots(
                IterationType iterationType, ControlPlaneFragment.FragmentPolicySnapshot[] snapshots) {
            ExecutionPath expected =
                    this.policyCase == NormalPolicyCase.SCARCE_96 ? ExecutionPath.STAGED : ExecutionPath.DIRECT;
            for (ControlPlaneFragment.FragmentPolicySnapshot snapshot : snapshots) {
                if (snapshot.bodyCostHistoryCount() < FragmentControlPolicy.BODY_COST_MIN_HISTORY) {
                    failPolicyValidation("Normal production estimator did not initialize: " + snapshot);
                }
                if (snapshot.executionPath() != expected) {
                    failPolicyValidation(
                            "Normal production policy selected the wrong resolved or guard mode: " + snapshot);
                }
            }
        }
    }

    /// One long-lived scarce fixture that repeatedly validates bounded normal-tree response.
    @State(Scope.Benchmark)
    public static class DynamicPolicyState extends PathState {

        private final AtomicInteger dynamicWorkRounds = new AtomicInteger(24);
        private final long[] transitionCounts = new long[3];
        private final long[] transitionCompletedFrames = new long[3];
        private final long[] transitionMaxCompletedFrames = new long[3];
        private final long[] transitionSamples = new long[3];
        private final long[] transitionMaxSamples = new long[3];

        @Override
        final ForcedMode forcedMode() {
            return null;
        }

        /// Starts normal policy at the stable cheap point without resetting between later phases.
        @Setup(Level.Trial)
        public void setup() {
            setupPath(2, SourceShape.SCARCE, Workload.CPU_WORK, 24, false, false, false, true, this.dynamicWorkRounds);
            awaitSettledMode(
                    0,
                    "startup-cheap",
                    ExecutionPath.DIRECT,
                    FragmentControlPolicy.BODY_COST_MIN_HISTORY,
                    0,
                    DYNAMIC_RESPONSE_MAX_FRAMES);
        }

        /// Runs one expensive and one cheap step without recreating or resetting the graph.
        final void runSequence() {
            if (policyValidationFailure() != null) {
                return;
            }
            this.dynamicWorkRounds.set(96);
            awaitSettledMode(
                    1,
                    "cheap-to-expensive",
                    ExecutionPath.STAGED,
                    FragmentControlPolicy.BODY_COST_MIN_HISTORY,
                    FragmentControlPolicy.EXPENSIVE_CONFIRMATION_SAMPLES,
                    DYNAMIC_RESPONSE_MAX_FRAMES);
            if (policyValidationFailure() != null) {
                return;
            }
            this.dynamicWorkRounds.set(24);
            awaitSettledMode(
                    2,
                    "expensive-to-cheap",
                    ExecutionPath.DIRECT,
                    FragmentControlPolicy.BODY_COST_MIN_HISTORY,
                    FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES,
                    DYNAMIC_RESPONSE_MAX_FRAMES);
        }

        /// Aggregates bounded response evidence without logging inside each JMH invocation.
        @Override
        void recordDynamicResponse(int phase, long completedFrames, int samples) {
            this.transitionCounts[phase]++;
            this.transitionCompletedFrames[phase] += completedFrames;
            this.transitionMaxCompletedFrames[phase] =
                    Math.max(this.transitionMaxCompletedFrames[phase], completedFrames);
            this.transitionSamples[phase] += samples;
            this.transitionMaxSamples[phase] = Math.max(this.transitionMaxSamples[phase], samples);
        }

        /// Reports aggregate startup and step-response evidence once before common close-safe checks.
        @Override
        protected void beforeClose() {
            LOGGER.info(
                    "Fragment dynamic policy phases=[startup-cheap, cheap-to-expensive, expensive-to-cheap] "
                            + "counts={} completedFrames={} maxCompletedFrames={} samples={} maxSamples={}",
                    Arrays.toString(this.transitionCounts),
                    Arrays.toString(this.transitionCompletedFrames),
                    Arrays.toString(this.transitionMaxCompletedFrames),
                    Arrays.toString(this.transitionSamples),
                    Arrays.toString(this.transitionMaxSamples));
            super.beforeClose();
        }
    }

    /// One long-lived graph validating source-count, contention, and path adaptation together.
    @State(Scope.Benchmark)
    public static class DynamicAcquisitionContentionState extends PathState {

        private boolean completed;
        private ControlPlaneFragment.FragmentPolicySnapshot[][] phaseSnapshots;

        @Override
        final ForcedMode forcedMode() {
            return null;
        }

        /// Starts the known two-worker 96-round path-reversal fixture with two live sources.
        @Setup(Level.Trial)
        public void setup() {
            this.completed = false;
            this.phaseSnapshots = new ControlPlaneFragment.FragmentPolicySnapshot[3][];
            setupPath(2, SourceShape.PLENTIFUL, Workload.CPU_WORK, 96, false, false, true, true, null);
        }

        /// Changes only live repeating-source count and waits for bounded EWMA/policy convergence.
        void runSequence() {
            if (this.completed || policyValidationFailure() != null) {
                return;
            }
            this.phaseSnapshots[0] = awaitContentionPhase(2, ExecutionPath.DIRECT, false);

            completeProductiveSource(1);
            this.phaseSnapshots[1] = awaitContentionPhase(1, ExecutionPath.STAGED, true);

            BenchmarkFrame[] frames =
                    BenchmarkFrame.generate(FRAME_POOL_SIZE, false, HasherApi.mix(31L), HasherApi.mix(32L));
            publishProductiveSource(frames);
            this.phaseSnapshots[2] = awaitContentionPhase(2, ExecutionPath.DIRECT, false);
            this.completed = true;
        }

        /// Waits for every lane's physical count and the expected worker-local mode response.
        private ControlPlaneFragment.FragmentPolicySnapshot[] awaitContentionPhase(
                long productiveSources, ExecutionPath expectedExecutionPath, boolean highContention) {
            long startFrames = completedFrames();
            long deadline = System.nanoTime() + TIMEOUT_NS;
            while (System.nanoTime() < deadline && completedFrames() - startFrames <= DYNAMIC_RESPONSE_MAX_FRAMES) {
                ControlPlaneFragment.FragmentPolicySnapshot[] snapshots = policySnapshots();
                long[] contention = new long[snapshots.length];
                boolean settled = snapshots.length == 2;
                int expectedModeCount = 0;
                for (int worker = 0; worker < snapshots.length && settled; worker++) {
                    ControlPlaneFragment.FragmentPolicySnapshot snapshot = snapshots[worker];
                    settled = snapshot != null
                            && snapshot.productiveHandleCount() == productiveSources
                            && snapshot.registeredWorkers() == 2
                            && snapshot.bodyCostHistoryCount() >= FragmentControlPolicy.BODY_COST_MIN_HISTORY
                            && snapshot.acquireContention() != null
                            && snapshot.acquireContention().initialized();
                    if (settled) {
                        if (snapshot.executionPath() == expectedExecutionPath) {
                            expectedModeCount++;
                        }
                        contention[worker] = snapshot.acquireContention().fixedPointValue();
                    }
                }
                if (settled) {
                    Arrays.sort(contention);
                    long median = contention[contention.length / 2];
                    boolean modeSettled =
                            highContention ? expectedModeCount > 0 : expectedModeCount == snapshots.length;
                    boolean contentionSettled = highContention
                            ? median > FragmentControlPolicy.LOW_CONTENTION_MAX
                            : median <= FragmentControlPolicy.LOW_CONTENTION_MAX;
                    if (modeSettled && contentionSettled) {
                        return snapshots;
                    }
                }
                Thread.onSpinWait();
            }
            failPolicyValidation("Dynamic contention phase did not settle sources=" + productiveSources + " mode="
                    + expectedExecutionPath + " snapshots=" + Arrays.toString(policySnapshots()));
            return policySnapshots();
        }

        @Override
        void validatePolicySnapshots(
                IterationType iterationType, ControlPlaneFragment.FragmentPolicySnapshot[] snapshots) {}

        /// Reports all three low-frequency phase snapshots after the one-shot transition.
        @Override
        protected void beforeClose() {
            if (!this.completed) {
                failPolicyValidation("Dynamic contention response did not complete");
            }
            LOGGER.info(
                    "Fragment dynamic acquisition contention phases=[abundant, scarce, abundant] snapshots={}",
                    Arrays.deepToString(this.phaseSnapshots));
            super.beforeClose();
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

        @Param({"NATURAL"})
        public HandleLayout handleLayout;

        private final PaddedLongAdder counters = new PaddedLongAdder(SystemInfo.CPU_COUNT, true, true);
        private final PaddedLongAdder bodyTimingSampleCounts = new PaddedLongAdder(SystemInfo.CPU_COUNT, true, true);
        private final PaddedLongAdder bodyTimingElapsedNanos = new PaddedLongAdder(SystemInfo.CPU_COUNT, true, true);
        private final List<CloneableObject> pipelines = new ArrayList<>();
        private final List<ObservedPipeline> observedPipelines = new ArrayList<>();
        private final List<ControlPlaneFragment.FragmentPolicySnapshot[]> warmupPolicySnapshots = new ArrayList<>();
        private final List<ControlPlaneFragment.FragmentPolicySnapshot[]> measurementPolicySnapshots =
                new ArrayList<>();
        private DiagnosticLease diagnosticLease;
        private DiagnosticDistributor distributor;
        private AbstractIngestSink[] sources;
        private String[] sourceTypes;
        private QueueIngestSink emptyQueueSource;
        private long emptyQueueOfferCount;
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
        /// Fixed mode for forced states, or null when the production selector owns the choice.
        private ForcedMode selectedForcedMode;
        /// True when cloned fragments are retained for production-policy diagnostics.
        private boolean observeProductionPolicy;
        /// First predeclared policy-gate failure retained until close-safe trial teardown.
        private String policyValidationFailure;
        /// Phase 8 physical fixture, or null for all existing calibration states.
        private OpportunityFixture opportunityFixture;
        /// Phase 11 physical fixture, or null for all earlier calibration states.
        private IdleFixture idleFixture;
        /// Phase 13 physical fixture, or null for all earlier calibration states.
        private CrossoverFixture crossoverFixture;
        /// L2 affinity masks aligned with `workerCpus` for topology evidence.
        private String[] workerL2Masks;
        /// P/E core classes aligned with stable worker rank.
        private String[] workerCoreClasses;
        /// Fixture state retained immediately after source publication.
        private OpportunitySnapshot setupOpportunitySnapshot;
        /// Fixture state retained at each JMH iteration entry.
        private final List<OpportunitySnapshot> warmupOpportunityStarts = new ArrayList<>();
        private final List<OpportunitySnapshot> measurementOpportunityStarts = new ArrayList<>();
        /// Fixture state retained at each JMH iteration exit.
        private final List<OpportunitySnapshot> warmupOpportunityEnds = new ArrayList<>();
        private final List<OpportunitySnapshot> measurementOpportunityEnds = new ArrayList<>();
        /// Current iteration's physical state for lifecycle-aligned reporting.
        private OpportunitySnapshot iterationOpportunityBefore;

        /// Returns the forced path for diagnostic states, or null for normal policy.
        abstract ForcedMode forcedMode();

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
            setupPath(
                    workerCount,
                    sourceShape,
                    workload,
                    workRounds,
                    observeServiceMetric,
                    observeBodyTiming,
                    false,
                    false,
                    null);
        }

        /// Builds the path with independently gated raw timing, production timing, and policy observation.
        protected final void setupPath(
                int workerCount,
                SourceShape sourceShape,
                Workload workload,
                int workRounds,
                boolean observeServiceMetric,
                boolean observeBodyTiming,
                boolean productionBodyTiming,
                boolean observeProductionPolicy,
                AtomicInteger dynamicWorkRounds) {
            setupPath(
                    workerCount,
                    sourceShape,
                    workload,
                    workRounds,
                    observeServiceMetric,
                    observeBodyTiming,
                    productionBodyTiming,
                    observeProductionPolicy,
                    dynamicWorkRounds,
                    null);
        }

        /// Builds the fixed Phase 8 graph while leaving production selection and aggregation disabled.
        protected final void setupOpportunityPath(OpportunityFixture opportunityFixture, int workRounds) {
            setupPath(2, null, Workload.CPU_WORK, workRounds, false, true, false, false, null, opportunityFixture);
        }

        /// Builds a forced full-graph sensor control at the fixed cheap work point.
        protected final void setupSensorOverheadPath(
                OpportunityFixture opportunityFixture, boolean productiveObservation) {
            setupPath(
                    2,
                    null,
                    Workload.CPU_WORK,
                    24,
                    false,
                    false,
                    false,
                    false,
                    null,
                    opportunityFixture,
                    productiveObservation);
        }

        /// Builds one normal-policy graph over a retained productive-opportunity fixture.
        protected final void setupProductivePolicyPath(OpportunityFixture opportunityFixture, int workRounds) {
            setupPath(2, null, Workload.CPU_WORK, workRounds, false, false, false, true, null, opportunityFixture);
        }

        /// Builds one normal-policy Phase 11 graph with an optional fixed polling subset.
        protected final void setupIdleEligibilityPath(
                int workerCount,
                int productiveHandles,
                int emptyLiveHandles,
                int workRounds,
                int activePollingWorkers) {
            int active = activePollingWorkers == 0 ? workerCount : activePollingWorkers;
            if (workerCount <= 0
                    || productiveHandles <= 0
                    || productiveHandles > workerCount
                    || active <= 0
                    || active > workerCount
                    || workRounds < 0) {
                throw new IllegalArgumentException("Invalid idle-discovery worker, source, work, or polling count");
            }
            setupPath(
                    workerCount,
                    null,
                    Workload.CPU_WORK,
                    workRounds,
                    false,
                    false,
                    false,
                    true,
                    null,
                    null,
                    true,
                    new IdleFixture(productiveHandles, emptyLiveHandles, activePollingWorkers));
        }

        /// Builds one Phase 13 graph from either a configured CPU ratio or explicit source count.
        protected final void setupCrossoverPath(
                CrossoverTopology topology, int ratioDivisor, int explicitSources, int workRounds) {
            if (topology == CrossoverTopology.HOMOGENEOUS_P && ratioDivisor != 0) {
                throw new IllegalArgumentException("Homogeneous crossover rows require explicit sources");
            }
            BitSet workerCores =
                    crossoverWorkerCores(topology, activeCoreSet(), SystemInfo.getPCoreSet(), SystemInfo.getECoreSet());
            int sources = crossoverSourceCount(SystemInfo.CPU_COUNT, ratioDivisor, explicitSources);
            if (sources > SystemInfo.CPU_COUNT) {
                throw new IllegalArgumentException("Crossover source count exceeds the diagnostic bound");
            }
            this.crossoverFixture =
                    new CrossoverFixture(topology, ratioDivisor, SystemInfo.CPU_COUNT, sources, workerCores);
            setupPath(
                    workerCores.cardinality(),
                    null,
                    Workload.CPU_WORK,
                    workRounds,
                    false,
                    forcedMode() != null,
                    forcedMode() == null,
                    true,
                    null,
                    null,
                    true,
                    null);
        }

        /// Builds one graph with an optional physical opportunity fixture.
        private void setupPath(
                int workerCount,
                SourceShape sourceShape,
                Workload workload,
                int workRounds,
                boolean observeServiceMetric,
                boolean observeBodyTiming,
                boolean productionBodyTiming,
                boolean observeProductionPolicy,
                AtomicInteger dynamicWorkRounds,
                OpportunityFixture opportunityFixture) {
            setupPath(
                    workerCount,
                    sourceShape,
                    workload,
                    workRounds,
                    observeServiceMetric,
                    observeBodyTiming,
                    productionBodyTiming,
                    observeProductionPolicy,
                    dynamicWorkRounds,
                    opportunityFixture,
                    true);
        }

        /// Builds one graph with an optional benchmark-only productive-observation bypass.
        private void setupPath(
                int workerCount,
                SourceShape sourceShape,
                Workload workload,
                int workRounds,
                boolean observeServiceMetric,
                boolean observeBodyTiming,
                boolean productionBodyTiming,
                boolean observeProductionPolicy,
                AtomicInteger dynamicWorkRounds,
                OpportunityFixture opportunityFixture,
                boolean productiveObservation) {
            setupPath(
                    workerCount,
                    sourceShape,
                    workload,
                    workRounds,
                    observeServiceMetric,
                    observeBodyTiming,
                    productionBodyTiming,
                    observeProductionPolicy,
                    dynamicWorkRounds,
                    opportunityFixture,
                    productiveObservation,
                    null);
        }

        /// Builds one graph with optional earlier-phase or Phase 11 physical fixtures.
        private void setupPath(
                int workerCount,
                SourceShape sourceShape,
                Workload workload,
                int workRounds,
                boolean observeServiceMetric,
                boolean observeBodyTiming,
                boolean productionBodyTiming,
                boolean observeProductionPolicy,
                AtomicInteger dynamicWorkRounds,
                OpportunityFixture opportunityFixture,
                boolean productiveObservation,
                IdleFixture idleFixture) {
            try {
                if (workRounds < 0 || (workload == Workload.NO_OP && workRounds != 0)) {
                    throw new IllegalArgumentException("Work rounds must match a non-negative CPU workload");
                }
                this.sourceShape = sourceShape;
                this.opportunityFixture = opportunityFixture;
                this.idleFixture = idleFixture;
                this.workload = workload;
                this.workRounds = workRounds;
                this.workerCpus = new int[workerCount];
                this.workerCores = new int[workerCount];
                this.workerL2Masks = new String[workerCount];
                this.workerCoreClasses = new String[workerCount];
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
                this.warmupPolicySnapshots.clear();
                this.measurementPolicySnapshots.clear();
                this.observedPipelines.clear();
                this.selectedForcedMode = forcedMode();
                this.observeProductionPolicy = observeProductionPolicy;
                this.policyValidationFailure = null;
                this.sourceTypes = null;
                this.emptyQueueSource = null;
                this.emptyQueueOfferCount = 0L;
                this.setupOpportunitySnapshot = null;
                this.iterationOpportunityBefore = null;
                this.warmupOpportunityStarts.clear();
                this.measurementOpportunityStarts.clear();
                this.warmupOpportunityEnds.clear();
                this.measurementOpportunityEnds.clear();
                DiagnosticDistributor.resetSharedRoutingState();
                if (this.selectedForcedMode != null) {
                    this.diagnosticLease =
                            new DiagnosticLease(this.selectedForcedMode, FIXED_BATCH_SIZE, productionBodyTiming);
                }
                if (observeServiceMetric) {
                    this.serviceRegistry = new SimpleMeterRegistry();
                }

                BitSet workerCores = this.crossoverFixture == null
                        ? selectWorkerCores(workerCount)
                        : this.crossoverFixture.workerCores();
                if (idleFixture != null) {
                    requireDistinctWorkerL2Caches(workerCores);
                }
                if (idleFixture != null && idleFixture.activePollingWorkers > 0) {
                    this.diagnosticLease =
                            new DiagnosticLease(firstCores(workerCores, idleFixture.activePollingWorkers));
                }
                if (this.crossoverFixture == null) {
                    pinHarness(workerCores);
                } else {
                    pinHarnessCore(0);
                }
                this.distributor = new DiagnosticDistributor(workerCores.nextSetBit(0));

                LatticeEdge[] handles = new LatticeEdge[SystemInfo.MAX_CORE_ID + 1];
                for (int core = workerCores.nextSetBit(0); core >= 0; core = workerCores.nextSetBit(core + 1)) {
                    handles[core] = new LatticeEdge(this.distributor.getDrainFlag());
                }
                this.distributor.setDrain(true);
                if (!this.distributor.setDownstreamMapping(workerCores, handles)) {
                    throw new IllegalStateException("Unable to publish the diagnostic core mapping");
                }

                CountingExecutor executor = dynamicWorkRounds != null
                        ? CountingExecutor.dynamicPrototype(this.counters, workload, dynamicWorkRounds)
                        : observeBodyTiming
                                ? CountingExecutor.bodyTimingPrototype(
                                        this.counters,
                                        workload,
                                        workRounds,
                                        this.bodyTimingSampleCounts,
                                        this.bodyTimingElapsedNanos)
                                : new CountingExecutor(-1, this.counters, workload, workRounds);
                FragmentConfig fragmentConfig = fragmentConfig(this.serviceRegistry, this.selectedForcedMode == null);
                BaseCloneableObject base =
                        observeProductionPolicy ? null : new BaseCloneableObject(fragmentConfig, executor);
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
                    this.workerCores[workerIndex] = core;
                    this.workerL2Masks[workerIndex] =
                            SystemInfo.getCacheLayout(workerCpu).maskL2();
                    this.workerCoreClasses[workerIndex++] =
                            SystemInfo.getCoreInfo(core).pCore() ? "P" : "E";
                    CloneConfig cloneConfig = new CloneConfig("FragmentPathCalibration", core, cpus);
                    CloneableObject pipeline;
                    if (observeProductionPolicy) {
                        ObservedPipeline observed = new ObservedPipeline(fragmentConfig, executor, cloneConfig);
                        this.observedPipelines.add(observed);
                        pipeline = observed;
                    } else {
                        pipeline = base.clone(cloneConfig);
                    }
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

                int sourceCount = this.crossoverFixture != null
                        ? this.crossoverFixture.productiveSources
                        : idleFixture != null
                                ? idleFixture.liveHandles()
                                : opportunityFixture == null
                                        ? sourceCount(sourceShape, workerCount)
                                        : opportunityFixture.liveHandles;
                this.handleRecorder = new HandleAcquisitionRecorder(sourceCount, this.workerCpus, this.workerCores);
                this.sourceHandleIds = new long[sourceCount];
                this.sources = new AbstractIngestSink[sourceCount];
                this.sourceTypes = new String[sourceCount];
                LatticeSource[] delegates = new LatticeSource[sourceCount];
                long idHash = HasherApi.mix(HasherApi.BASE_SEED);
                for (int i = 0; i < sourceCount; i++) {
                    boolean emptyLive = opportunityFixture == OpportunityFixture.TWO_LIVE_ONE_PRODUCTIVE && i == 1;
                    if (idleFixture != null && i >= idleFixture.productiveHandles) {
                        emptyLive = true;
                    }
                    if (emptyLive) {
                        this.emptyQueueSource = new QueueIngestSink();
                        this.sources[i] = this.emptyQueueSource;
                        this.sourceTypes[i] = "EMPTY_QUEUE";
                    } else {
                        BenchmarkFrame[] frames = BenchmarkFrame.generate(
                                FRAME_POOL_SIZE, false, idHash + i, HasherApi.BASE_SEED + (long) i * FRAME_POOL_SIZE);
                        this.sources[i] = new RepeatingSink(frames);
                        this.sourceTypes[i] = "REPEATING";
                    }
                    delegates[i] = this.sources[i].getDelegate();
                    if (this.handleLayout == HandleLayout.NATURAL) {
                        this.sourceHandleIds[i] = this.distributor.ingestTracked(
                                delegates[i], i, this.handleRecorder, productiveObservation);
                    }
                }
                if (this.handleLayout != HandleLayout.NATURAL) {
                    this.sourceHandleIds = this.distributor.ingestTracked(
                            delegates, this.workerCores, this.handleLayout, this.handleRecorder);
                }
                if (this.opportunityFixture != null || this.idleFixture != null || this.crossoverFixture != null) {
                    this.setupOpportunitySnapshot = captureOpportunitySnapshot();
                    requireValidOpportunitySnapshot(this.setupOpportunitySnapshot, "trial setup");
                }
            } catch (RuntimeException e) {
                closePath();
                throw e;
            }
        }

        /// Opens one measurement-only participation accumulator and ignores warmup iterations.
        @Setup(Level.Iteration)
        public final void setupIteration(IterationParams iterationParams) {
            if (this.opportunityFixture != null || this.idleFixture != null || this.crossoverFixture != null) {
                this.iterationOpportunityBefore = captureOpportunitySnapshot();
                requireValidOpportunitySnapshot(this.iterationOpportunityBefore, "iteration start");
            }
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
            OpportunitySnapshot opportunityAfter = null;
            if (this.opportunityFixture != null || this.idleFixture != null || this.crossoverFixture != null) {
                opportunityAfter = captureOpportunitySnapshot();
                requireValidOpportunitySnapshot(opportunityAfter, "iteration end");
            }
            HandleSnapshot handleDeltas = handleDelta(this.iterationHandleBefore, this.handleRecorder.snapshot());
            ServiceMetricSnapshot serviceDelta = this.serviceSummaries == null
                    ? null
                    : serviceMetricDelta(this.iterationServiceBefore, serviceMetricSnapshot(this.serviceSummaries));
            BodyTimingSnapshot bodyTimingDelta = this.observeBodyTiming
                    ? bodyTimingDelta(this.iterationBodyTimingBefore, bodyTimingSnapshot())
                    : null;
            ControlPlaneFragment.FragmentPolicySnapshot[] policySnapshots =
                    this.observeProductionPolicy ? policySnapshots() : null;
            if (iterationParams.getType() == IterationType.WARMUP) {
                this.warmupHandleDeltas.add(handleDeltas);
                if (this.iterationOpportunityBefore != null) {
                    this.warmupOpportunityStarts.add(this.iterationOpportunityBefore);
                    this.warmupOpportunityEnds.add(opportunityAfter);
                }
                if (bodyTimingDelta != null) {
                    this.warmupBodyTimingDeltas.add(bodyTimingDelta);
                }
                if (policySnapshots != null) {
                    this.warmupPolicySnapshots.add(policySnapshots);
                }
            } else if (iterationParams.getType() == IterationType.MEASUREMENT) {
                this.measurementHandleDeltas.add(handleDeltas);
                if (this.iterationOpportunityBefore != null) {
                    this.measurementOpportunityStarts.add(this.iterationOpportunityBefore);
                    this.measurementOpportunityEnds.add(opportunityAfter);
                }
                if (policySnapshots != null) {
                    this.measurementPolicySnapshots.add(policySnapshots);
                }
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
            this.iterationOpportunityBefore = null;
            if (policySnapshots != null) {
                validatePolicySnapshots(iterationParams.getType(), policySnapshots);
            }
        }

        /// Allows a bounded production-policy state to enforce its predeclared iteration result.
        void validatePolicySnapshots(
                IterationType iterationType, ControlPlaneFragment.FragmentPolicySnapshot[] snapshots) {}

        /// Retains the first policy-gate failure so trial teardown can still close worker threads.
        final void failPolicyValidation(String message) {
            if (this.policyValidationFailure == null) {
                this.policyValidationFailure = message;
            }
        }

        /// Returns the retained gate failure for deterministic benchmark-helper tests.
        final String policyValidationFailure() {
            return this.policyValidationFailure;
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
            reportProductiveOpportunity();
            reportServiceEstimate();
            reportBodyTimingEstimate();
            reportProductionPolicy();
            if (this.policyValidationFailure != null) {
                throw new IllegalStateException(this.policyValidationFailure);
            }
        }

        /// Validates the expected nonzero/zero completion split for a Phase 11 polling fixture.
        final void validateIdleParticipation() {
            if (this.idleFixture == null) {
                throw new IllegalStateException("Missing idle fixture for participation validation");
            }
            long[] aggregate = new long[this.workerCpus.length];
            for (long[] iteration : this.measurementWorkerDeltas) {
                for (int worker = 0; worker < aggregate.length; worker++) {
                    aggregate[worker] = Math.addExact(aggregate[worker], iteration[worker]);
                }
            }
            int expectedActive = this.idleFixture.activePollingWorkers == 0
                    ? this.workRounds == 0 ? Math.max(1, this.idleFixture.productiveHandles) : this.workerCpus.length
                    : this.idleFixture.activePollingWorkers;
            for (int worker = 0; worker < aggregate.length; worker++) {
                boolean expectedPolling = worker < expectedActive;
                if (expectedPolling && aggregate[worker] <= 0L) {
                    failPolicyValidation("Active polling worker disappeared: " + Arrays.toString(aggregate));
                }
                if (!expectedPolling && aggregate[worker] != 0L) {
                    failPolicyValidation("Intentionally parked worker completed work: " + Arrays.toString(aggregate));
                }
            }
            if (this.distributor.getThreadCount() != this.workerCpus.length) {
                failPolicyValidation("Idle discovery changed registered worker count");
            }
        }

        /// Rejects registration loss or a worker with no completion across the Phase 13 fork.
        final void validateCrossoverParticipation() {
            if (this.crossoverFixture == null) {
                throw new IllegalStateException("Missing crossover fixture for participation validation");
            }
            long[] aggregate = new long[this.workerCpus.length];
            for (long[] iteration : this.measurementWorkerDeltas) {
                for (int worker = 0; worker < aggregate.length; worker++) {
                    aggregate[worker] = Math.addExact(aggregate[worker], iteration[worker]);
                }
            }
            for (long completed : aggregate) {
                if (completed <= 0L) {
                    failPolicyValidation("Crossover worker disappeared: " + Arrays.toString(aggregate));
                    break;
                }
            }
            if (this.distributor.getThreadCount() != this.workerCpus.length) {
                failPolicyValidation("Crossover changed registered worker count");
            }
        }

        /// Rejects registration loss or a currently active production worker with no retained work.
        final void validateNormalCrossoverParticipation() {
            if (this.crossoverFixture == null) {
                throw new IllegalStateException("Missing crossover fixture for participation validation");
            }
            long[] aggregate = new long[this.workerCpus.length];
            for (long[] iteration : this.measurementWorkerDeltas) {
                for (int worker = 0; worker < aggregate.length; worker++) {
                    aggregate[worker] = Math.addExact(aggregate[worker], iteration[worker]);
                }
            }
            ControlPlaneFragment.FragmentPolicySnapshot[] snapshots = policySnapshots();
            for (int worker = 0; worker < aggregate.length; worker++) {
                if (snapshots[worker].activePolling() && aggregate[worker] <= 0L) {
                    failPolicyValidation("Active crossover worker disappeared: " + Arrays.toString(aggregate));
                    break;
                }
            }
            if (this.distributor.getThreadCount() != this.workerCpus.length) {
                failPolicyValidation("Crossover changed registered worker count");
            }
        }

        /// Captures live registration and empty-source state without changing source behavior.
        private OpportunitySnapshot captureOpportunitySnapshot() {
            return new OpportunitySnapshot(
                    Math.toIntExact(this.distributor.getUpstreamHandleCount()),
                    this.distributor.getThreadCount(),
                    this.emptyQueueSource == null ? -1L : this.emptyQueueSource.size(),
                    this.emptyQueueSource == null ? -1L : this.emptyQueueSource.getDemand(),
                    this.emptyQueueSource == null ? -1L : this.emptyQueueOfferCount,
                    this.emptyQueueSource != null && this.emptyQueueSource.isComplete());
        }

        /// Enforces the predeclared physical fixture invariants at every lifecycle snapshot.
        private void requireValidOpportunitySnapshot(OpportunitySnapshot snapshot, String phase) {
            int expectedLive = this.crossoverFixture != null
                    ? this.crossoverFixture.productiveSources
                    : this.idleFixture == null ? this.opportunityFixture.liveHandles : this.idleFixture.liveHandles();
            boolean expectsEmpty = this.idleFixture == null
                    ? this.opportunityFixture == OpportunityFixture.TWO_LIVE_ONE_PRODUCTIVE
                    : this.idleFixture.emptyLiveHandles > 0;
            if (snapshot.liveHandles() != expectedLive || snapshot.registeredWorkers() != this.workerCpus.length) {
                throw new IllegalStateException(
                        "Invalid diagnostic live handle or worker count at " + phase + ": " + snapshot);
            }
            if (expectsEmpty
                    && (snapshot.emptyQueueSize() != 0L
                            || snapshot.emptyQueueOfferCount() != 0L
                            || snapshot.emptyQueueComplete())) {
                throw new IllegalStateException(
                        "Invalid diagnostic empty-live source state at " + phase + ": " + snapshot);
            }
        }

        /// Reports raw measurement splits and fork-level participation metrics before graph close.
        private void reportParticipation() {
            long[][] rawDeltas = this.measurementWorkerDeltas.toArray(long[][]::new);
            long[] finalWorkerCounts = workerCounts(this.counters, this.workerCpus);
            boolean hasTimedMeasurement = rawDeltas.length > 0;
            for (long elapsedNs : this.measurementElapsedNanos) {
                hasTimedMeasurement &= elapsedNs > 0L;
            }
            if (!hasTimedMeasurement) {
                LOGGER.info(
                        "Fragment worker participation mode={} sourceShape={} opportunityFixture={} idleFixture={} crossoverFixture={} workload={} workRounds={} handleLayout={} batch={} workerCpus={} workerCores={} workerCoreClasses={} workerL2Masks={} "
                                + "rawMeasurementDeltas={} finalWorkerCounts={} verdict=NO_TIMED_FRAME_WINDOW",
                        policyLabel(),
                        this.sourceShape,
                        this.opportunityFixture,
                        this.idleFixture,
                        this.crossoverFixture,
                        this.workload,
                        this.workRounds,
                        this.handleLayout,
                        FIXED_BATCH_SIZE,
                        Arrays.toString(this.workerCpus),
                        Arrays.toString(this.workerCores),
                        Arrays.toString(this.workerCoreClasses),
                        Arrays.toString(this.workerL2Masks),
                        Arrays.deepToString(rawDeltas),
                        Arrays.toString(finalWorkerCounts));
                return;
            }
            double[][] fractions = new double[rawDeltas.length][];
            double[] dominance = new double[rawDeltas.length];
            double[] effectiveLanes = new double[rawDeltas.length];
            long[] aggregateDeltas = new long[this.workerCpus.length];
            long aggregateElapsedNanos = 0L;
            boolean lanesComparable = this.workload == Workload.NO_OP || this.workRounds == CPU_WORK_ROUNDS;
            double singleLaneCeilingFramesPerSecond = lanesComparable
                    ? singleLaneCeiling(
                            this.workload,
                            this.selectedForcedMode == null ? ForcedMode.DIRECT : this.selectedForcedMode)
                    : 1.0;
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
                    "Fragment worker participation mode={} sourceShape={} opportunityFixture={} idleFixture={} crossoverFixture={} workload={} workRounds={} handleLayout={} batch={} workerCpus={} workerCores={} workerCoreClasses={} workerL2Masks={} "
                            + "rawMeasurementDeltas={} perMeasurementFractions={} perMeasurementDominance={} "
                            + "perMeasurementEffectiveLanes={} aggregateDeltas={} aggregateFractions={} "
                            + "aggregateDominance={} aggregateEffectiveLanes={} finalWorkerCounts={} "
                            + "singleLaneCeilingFramesPerSecond={}",
                    policyLabel(),
                    this.sourceShape,
                    this.opportunityFixture,
                    this.idleFixture,
                    this.crossoverFixture,
                    this.workload,
                    this.workRounds,
                    this.handleLayout,
                    FIXED_BATCH_SIZE,
                    Arrays.toString(this.workerCpus),
                    Arrays.toString(this.workerCores),
                    Arrays.toString(this.workerCoreClasses),
                    Arrays.toString(this.workerL2Masks),
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
                    "Fragment handle acquisition mode={} sourceShape={} opportunityFixture={} idleFixture={} crossoverFixture={} workload={} workRounds={} "
                            + "handleLayout={} batch={} workerCpus={} workerCores={} workerCoreClasses={} workerL2Masks={} sourceOrdinals={} sourceTypes={} "
                            + "sourceHandleIds={} preFirstIteration={} "
                            + "warmupDeltas={} measurementDeltas={} aggregateAttempts={} aggregateFailures={} "
                            + "aggregateSuccessfulServiceAttempts={} aggregatePulledFrames={} "
                            + "firstProductiveOrder={}",
                    policyLabel(),
                    this.sourceShape,
                    this.opportunityFixture,
                    this.idleFixture,
                    this.crossoverFixture,
                    this.workload,
                    this.workRounds,
                    this.handleLayout,
                    FIXED_BATCH_SIZE,
                    Arrays.toString(this.workerCpus),
                    Arrays.toString(this.workerCores),
                    Arrays.toString(this.workerCoreClasses),
                    Arrays.toString(this.workerL2Masks),
                    Arrays.toString(sourceOrdinals(this.sourceHandleIds.length)),
                    Arrays.toString(this.sourceTypes),
                    Arrays.toString(this.sourceHandleIds),
                    formatHandleSnapshot(this.preFirstIterationHandles),
                    formatHandleSnapshots(this.warmupHandleDeltas),
                    formatHandleSnapshots(this.measurementHandleDeltas),
                    Arrays.deepToString(finalSnapshot.attempts()),
                    Arrays.deepToString(finalSnapshot.failures()),
                    Arrays.deepToString(successfulServiceAttempts(finalSnapshot)),
                    Arrays.deepToString(finalSnapshot.pulledFrames()),
                    Arrays.toString(finalSnapshot.firstProductiveOrder()));
        }

        /// Reports lifecycle state and declared physical source counts for availability fixtures.
        private void reportProductiveOpportunity() {
            if (this.opportunityFixture == null && this.idleFixture == null && this.crossoverFixture == null) {
                return;
            }
            int expectedLive = this.crossoverFixture != null
                    ? this.crossoverFixture.productiveSources
                    : this.idleFixture == null ? this.opportunityFixture.liveHandles : this.idleFixture.liveHandles();
            int expectedProductive = this.crossoverFixture != null
                    ? this.crossoverFixture.productiveSources
                    : this.idleFixture == null
                            ? this.opportunityFixture.productiveHandles
                            : this.idleFixture.productiveHandles;
            OpportunitySnapshot finalSnapshot = captureOpportunitySnapshot();
            requireValidOpportunitySnapshot(finalSnapshot, "trial close");
            LOGGER.info(
                    "Fragment productive opportunity mode={} opportunityFixture={} idleFixture={} crossoverFixture={} configuredRatio={} cpuRatioBasis={} actualSourceCount={} expectedLiveHandles={} "
                            + "expectedProductiveHandles={} sourceTypes={} setupSnapshot={} warmupStarts={} "
                            + "warmupEnds={} measurementStarts={} measurementEnds={} finalSnapshot={}",
                    policyLabel(),
                    this.opportunityFixture,
                    this.idleFixture,
                    this.crossoverFixture,
                    this.crossoverFixture == null ? "NONE" : this.crossoverFixture.configuredRatio(),
                    this.crossoverFixture == null ? -1 : this.crossoverFixture.cpuRatioBasis,
                    this.crossoverFixture == null ? expectedLive : this.crossoverFixture.productiveSources,
                    expectedLive,
                    expectedProductive,
                    Arrays.toString(this.sourceTypes),
                    this.setupOpportunitySnapshot,
                    this.warmupOpportunityStarts,
                    this.warmupOpportunityEnds,
                    this.measurementOpportunityStarts,
                    this.measurementOpportunityEnds,
                    finalSnapshot);
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
                    policyLabel(),
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
                    "Fragment executor body timing mode={} sourceShape={} crossoverFixture={} workRounds={} batch={} sampleInterval={} "
                            + "workerCpus={} workerCores={} workerCoreClasses={} isolatedBodyCostNs={} liveHandles={} registeredWorkers={} "
                            + "warmupSampleCounts={} warmupElapsedNanos={} warmupWorkerEstimatesNs={} "
                            + "measurementSampleCounts={} measurementElapsedNanos={} measurementWorkerEstimatesNs={} "
                            + "aggregateSampleCounts={} aggregateElapsedNanos={} aggregateWorkerEstimatesNs={}",
                    policyLabel(),
                    this.sourceShape,
                    this.crossoverFixture,
                    this.workRounds,
                    FIXED_BATCH_SIZE,
                    BODY_TIMING_INTERVAL,
                    Arrays.toString(this.workerCpus),
                    Arrays.toString(this.workerCores),
                    Arrays.toString(this.workerCoreClasses),
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

        /// Reports production estimator and selected-mode snapshots at JMH iteration boundaries.
        private void reportProductionPolicy() {
            if (!this.observeProductionPolicy) {
                return;
            }
            ControlPlaneFragment.FragmentPolicySnapshot[] finalSnapshots = policySnapshots();
            LOGGER.info(
                    "Fragment production policy policy={} sourceShape={} opportunityFixture={} idleFixture={} crossoverFixture={} workRounds={} batchCap={} workerCpus={} workerCores={} workerCoreClasses={} workerL2Masks={} "
                            + "liveHandles={} registeredWorkers={} highContentionIdleThreshold={} highContentionParkNanos={} highContentionIdleBodyCostMaxNs={} "
                            + "finalHighContentionParkCounts={} warmupSnapshots={} measurementSnapshots={} finalSnapshots={}",
                    policyLabel(),
                    this.sourceShape,
                    this.opportunityFixture,
                    this.idleFixture,
                    this.crossoverFixture,
                    this.workRounds,
                    FIXED_BATCH_SIZE,
                    Arrays.toString(this.workerCpus),
                    Arrays.toString(this.workerCores),
                    Arrays.toString(this.workerCoreClasses),
                    Arrays.toString(this.workerL2Masks),
                    this.distributor.getUpstreamHandleCount(),
                    this.distributor.getThreadCount(),
                    FragmentControlPolicy.HIGH_CONTENTION_THRESHOLD,
                    FragmentControlPolicy.HIGH_CONTENTION_PARK_NANOS,
                    FragmentControlPolicy.HIGH_CONTENTION_BODY_COST_NS,
                    Arrays.toString(highContentionParkCounts(finalSnapshots)),
                    Arrays.deepToString(
                            this.warmupPolicySnapshots.toArray(ControlPlaneFragment.FragmentPolicySnapshot[][]::new)),
                    Arrays.deepToString(this.measurementPolicySnapshots.toArray(
                            ControlPlaneFragment.FragmentPolicySnapshot[][]::new)),
                    Arrays.toString(finalSnapshots));
            if (this.crossoverFixture != null) {
                ControlPlaneFragment.AcquireContentionSnapshot[] contention =
                        new ControlPlaneFragment.AcquireContentionSnapshot[finalSnapshots.length];
                for (int worker = 0; worker < finalSnapshots.length; worker++) {
                    contention[worker] = finalSnapshots[worker].acquireContention();
                }
                LOGGER.info(
                        "Fragment acquisition contention policy={} crossoverFixture={} workRounds={} enabled={} "
                                + "selectionEnabled={} scale={} fixedPoint={} normalized={}",
                        policyLabel(),
                        this.crossoverFixture,
                        this.workRounds,
                        UpstreamQueue.acquireContentionEnabled(),
                        FragmentControlPolicy.acquireContentionSelectionEnabled(),
                        UpstreamQueue.ACQUIRE_CONTENTION_SCALE,
                        Arrays.toString(acquisitionContentionFixedPoint(contention)),
                        Arrays.toString(acquisitionContentionNormalized(contention)));
            }
            validatePolicySnapshots(IterationType.MEASUREMENT, finalSnapshots);
        }

        /// Extracts worker-aligned fixed-point acquisition values for concise retained diagnostics.
        static long[] acquisitionContentionFixedPoint(ControlPlaneFragment.AcquireContentionSnapshot[] snapshots) {
            long[] values = new long[snapshots.length];
            for (int worker = 0; worker < values.length; worker++) {
                values[worker] = snapshots[worker].fixedPointValue();
            }
            return values;
        }

        /// Extracts worker-aligned normalized acquisition values outside the scheduling hot path.
        static double[] acquisitionContentionNormalized(ControlPlaneFragment.AcquireContentionSnapshot[] snapshots) {
            double[] values = new double[snapshots.length];
            for (int worker = 0; worker < values.length; worker++) {
                values[worker] = snapshots[worker].normalized();
            }
            return values;
        }

        /// Extracts worker-aligned contention park counts from low-frequency policy snapshots.
        static long[] highContentionParkCounts(ControlPlaneFragment.FragmentPolicySnapshot[] snapshots) {
            long[] values = new long[snapshots.length];
            for (int worker = 0; worker < values.length; worker++) {
                values[worker] = snapshots[worker].highContentionParkCount();
            }
            return values;
        }

        /// Reads the retained fragment references for benchmark-only policy diagnostics.
        protected final ControlPlaneFragment.FragmentPolicySnapshot[] policySnapshots() {
            ControlPlaneFragment.FragmentPolicySnapshot[] snapshots =
                    new ControlPlaneFragment.FragmentPolicySnapshot[this.observedPipelines.size()];
            for (int i = 0; i < snapshots.length; i++) {
                snapshots[i] = this.observedPipelines.get(i).policySnapshot();
            }
            return snapshots;
        }

        /// Returns current per-worker completion totals in stable worker order.
        protected final long[] workerCompletionCounts() {
            return workerCounts(this.counters, this.workerCpus);
        }

        /// Resets all trial-owned pipelines before one shared deadline.
        protected final long resetPipelines(long deadlineNanos) {
            long cleared = 0L;
            for (CloneableObject pipeline : this.pipelines) {
                cleared = Math.addExact(cleared, pipeline.resetForNextTrial(deadlineNanos));
            }
            return cleared;
        }

        /// Publishes a second repeating source and updates the dynamic smoke's physical fixture.
        protected final void publishAdditionalProductiveSource(BenchmarkFrame[] frames) {
            RepeatingSink wakeSource = new RepeatingSink(frames);
            this.sources = Arrays.copyOf(this.sources, this.sources.length + 1);
            this.sources[this.sources.length - 1] = wakeSource;
            this.distributor.ingest(wakeSource.getDelegate());
            this.idleFixture = new IdleFixture(2, 0, 0);
        }

        /// Completes one trial-owned repeating source without changing any other graph input.
        protected final void completeProductiveSource(int sourceIndex) {
            if (sourceIndex < 0 || sourceIndex >= this.sources.length) {
                throw new IllegalArgumentException("Source index is outside the trial-owned source array");
            }
            this.sources[sourceIndex].complete();
        }

        /// Publishes one replacement repeating source for a bounded dynamic physical transition.
        protected final void publishProductiveSource(BenchmarkFrame[] frames) {
            RepeatingSink source = new RepeatingSink(frames);
            this.sources = Arrays.copyOf(this.sources, this.sources.length + 1);
            this.sources[this.sources.length - 1] = source;
            this.distributor.ingest(source.getDelegate());
        }

        /// Returns the monotonic completed-frame total for bounded dynamic waits.
        protected final long completedFrames() {
            return this.counters.sum();
        }

        /// Returns the existing registered-worker count for bounded lifecycle validation.
        protected final int registeredWorkerCount() {
            return this.distributor.getThreadCount();
        }

        /// Returns the actual productive-source count retained by the Phase 13 fixture.
        protected final int crossoverProductiveSources() {
            if (this.crossoverFixture == null) {
                throw new IllegalStateException("Missing crossover fixture");
            }
            return this.crossoverFixture.productiveSources;
        }

        /// Waits for the most productive owner-local policy to settle within a completed-work bound.
        final void awaitSettledMode(
                int phaseIndex,
                String phase,
                ExecutionPath expectedExecutionPath,
                int minimumHistory,
                int minimumNewSamples,
                long maximumCompletedFrames) {
            long startFrames = this.counters.sum();
            ControlPlaneFragment.FragmentPolicySnapshot[] startingSnapshots = policySnapshots();
            int[] startingHistory = new int[startingSnapshots.length];
            for (int worker = 0; worker < startingSnapshots.length; worker++) {
                ControlPlaneFragment.FragmentPolicySnapshot snapshot = startingSnapshots[worker];
                startingHistory[worker] = snapshot == null ? 0 : snapshot.bodyCostHistoryCount();
            }
            long deadline = System.nanoTime() + TIMEOUT_NS;
            while (System.nanoTime() < deadline && this.counters.sum() - startFrames <= maximumCompletedFrames) {
                ControlPlaneFragment.FragmentPolicySnapshot[] snapshots = policySnapshots();
                int selectedWorker = -1;
                int selectedProgress = -1;
                for (int worker = 0; worker < snapshots.length; worker++) {
                    ControlPlaneFragment.FragmentPolicySnapshot snapshot = snapshots[worker];
                    if (snapshot == null) {
                        continue;
                    }
                    int progress = minimumNewSamples == 0
                            ? snapshot.bodyCostHistoryCount()
                            : Math.max(0, snapshot.bodyCostHistoryCount() - startingHistory[worker]);
                    if (progress > selectedProgress) {
                        selectedWorker = worker;
                        selectedProgress = progress;
                    }
                }
                if (selectedWorker >= 0
                        && snapshots[selectedWorker].bodyCostHistoryCount() >= minimumHistory
                        && selectedProgress >= minimumNewSamples
                        && snapshots[selectedWorker].executionPath() == expectedExecutionPath) {
                    recordDynamicResponse(phaseIndex, this.counters.sum() - startFrames, selectedProgress);
                    return;
                }
                Thread.onSpinWait();
            }
            failPolicyValidation("Production policy phase " + phase + " did not settle " + expectedExecutionPath
                    + " within "
                    + maximumCompletedFrames + " completed frames; snapshots=" + Arrays.toString(policySnapshots()));
        }

        /// Retains dynamic response evidence when the specialized state requests it.
        void recordDynamicResponse(int phase, long completedFrames, int samples) {}

        /// Returns the report label without turning normal selection into a forced benchmark mode.
        private String policyLabel() {
            return this.selectedForcedMode == null ? "NORMAL" : this.selectedForcedMode.name();
        }

        /// Creates the normal fixed-cap fixture or the unchanged forced-path fragment configuration.
        private static FragmentConfig fragmentConfig(SimpleMeterRegistry registry, boolean normalPolicy) {
            FragmentConfig defaults = registry == null
                    ? FragmentConfig.ofDefaults()
                    : FragmentConfig.ofDefaults(WORK_COST_METRIC_PREFIX, registry);
            if (!normalPolicy) {
                return defaults;
            }
            return new FragmentConfig(
                    null,
                    defaults.cacheConfig(),
                    defaults.actionPicker(),
                    FIXED_BATCH_SIZE,
                    false,
                    defaults.metricPrefix(),
                    defaults.registry());
        }

        /// Returns the completed isolated-work calibration value for one retained validation point.
        private static double isolatedBodyCost(int workRounds) {
            return switch (workRounds) {
                case 24 -> 21.566;
                case 80 -> 70.689;
                case 96 -> 84.657;
                case 512 -> 449.914;
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
                for (AbstractIngestSink source : this.sources) {
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
            this.sourceTypes = null;
            this.emptyQueueSource = null;
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

    /// Benchmark-owned clone pair that preserves production wiring while retaining fragment diagnostics.
    static final class ObservedPipeline implements CloneableObject {

        private final ControlPlaneFragment fragment;
        private final AbstractExecutor executor;

        /// Clones and connects the exact fragment/executor pair before either side starts.
        ObservedPipeline(FragmentConfig fragmentConfig, AbstractExecutor executorPrototype, CloneConfig cloneConfig) {
            this.fragment = new ControlPlaneFragment(fragmentConfig.clone(cloneConfig));
            this.executor = executorPrototype.clone(cloneConfig);
            this.fragment.connectBodyCostRecorder(this.executor);
        }

        @Override
        public CloneableObject clone(CloneConfig cloneConfig) {
            throw new UnsupportedOperationException("Observed benchmark pipelines are already cloned");
        }

        @Override
        public void start() {
            this.executor.start();
            this.fragment.start();
            this.executor.input(this.fragment.output());
        }

        @Override
        public void input(LatticeSource stream) {
            this.fragment.input(stream);
        }

        @Override
        public boolean isDrained() {
            return this.fragment.isDrained() && this.executor.isDrained();
        }

        @Override
        public void setDrainMode(boolean value) {
            this.executor.setDrainMode(value);
            this.fragment.setDrainMode(value);
        }

        @Override
        public long resetForNextTrial(long deadlineNanos) {
            return this.fragment.resetForNextTrial(deadlineNanos) + this.executor.resetForNextTrial(deadlineNanos);
        }

        /// Returns the benchmark-only best-effort policy view from the retained fragment.
        ControlPlaneFragment.FragmentPolicySnapshot policySnapshot() {
            return this.fragment.policySnapshot();
        }

        @Override
        public void close() {
            this.fragment.close();
            this.executor.close();
        }
    }

    /// Setup-only lease that guarantees the package-private policy override is cleared exactly once.
    static final class DiagnosticLease implements AutoCloseable {

        private FragmentControlPolicy.DiagnosticOverride override;

        /// Publishes a fixed mode and batch before any benchmark fragment policy is constructed.
        DiagnosticLease(ForcedMode mode, long batchSize) {
            this(mode, batchSize, false);
        }

        /// Publishes a fixed mode with optional production estimator sampling.
        DiagnosticLease(ForcedMode mode, long batchSize, boolean productionBodyTiming) {
            this.override = FragmentControlPolicy.installDiagnosticOverride(
                    ExecutionPath.valueOf(mode.name()), batchSize, productionBodyTiming);
        }

        /// Publishes a fixed polling subset while leaving normal production selection intact.
        DiagnosticLease(BitSet pollingCores) {
            this.override = FragmentControlPolicy.installDiagnosticPollingOverride(pollingCores);
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
        private final AtomicInteger dynamicWorkRounds;
        private final PaddedLongAdder bodyTimingSampleCounts;
        private final PaddedLongAdder bodyTimingElapsedNanos;
        private long workSink = HasherApi.BASE_SEED;

        /// Creates an executor prototype or pinned clone for the selected work body.
        CountingExecutor(int cpu, PaddedLongAdder counters, Workload workload, int workRounds) {
            super(cpu);
            this.counters = counters;
            this.workload = workload;
            this.workRounds = workRounds;
            this.dynamicWorkRounds = null;
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
            this.dynamicWorkRounds = null;
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
            this.dynamicWorkRounds = null;
            this.bodyTimingSampleCounts = bodyTimingSampleCounts;
            this.bodyTimingElapsedNanos = bodyTimingElapsedNanos;
        }

        /// Creates a prototype or clone whose benchmark-owned work body can change between phases.
        private CountingExecutor(
                int cpu, PaddedLongAdder counters, Workload workload, AtomicInteger dynamicWorkRounds) {
            super(cpu);
            this.counters = counters;
            this.workload = workload;
            this.workRounds = 0;
            this.dynamicWorkRounds = dynamicWorkRounds;
            this.bodyTimingSampleCounts = null;
            this.bodyTimingElapsedNanos = null;
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

        /// Returns the prototype used only by the bounded dynamic normal-policy diagnostic.
        static CountingExecutor dynamicPrototype(
                PaddedLongAdder counters, Workload workload, AtomicInteger dynamicWorkRounds) {
            return new CountingExecutor(-1, counters, workload, dynamicWorkRounds);
        }

        /// Executes the no-op frame, optionally performs CPU work, and publishes completion.
        @Override
        public void execute(AbstractFrame frame) {
            frame.execute();
            if (this.workload == Workload.CPU_WORK) {
                int rounds = this.dynamicWorkRounds == null ? this.workRounds : this.dynamicWorkRounds.getAcquire();
                this.workSink = cpuWork(this.workSink ^ frame.getRoutingHash(), rounds);
            }
            this.counters.increment(super.cpu);
        }

        /// Clones the benchmark executor for the fragment's selected logical CPU.
        @Override
        public CountingExecutor hookOnClone(int cpu) {
            if (this.dynamicWorkRounds != null) {
                return new CountingExecutor(cpu, this.counters, this.workload, this.dynamicWorkRounds);
            }
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
            return ingestTracked(source, sourceOrdinal, recorder, true);
        }

        /// Connects one source with production observation or a same-build liveness-only control.
        long ingestTracked(
                LatticeSource source,
                int sourceOrdinal,
                HandleAcquisitionRecorder recorder,
                boolean productiveObservation) {
            UpstreamInterceptor interceptor = productiveObservation
                    ? new DiagnosticUpstreamInterceptor(sourceOrdinal, recorder)
                    : new DiagnosticLivenessOnlyInterceptor(sourceOrdinal, recorder);
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
                this.recorder.recordPullResult(this.sourceOrdinal, worker, frames);
                return frames;
            }
        }

        /// Benchmark-only pre-sensor behavior retaining production locking, routing, and recording.
        final class DiagnosticLivenessOnlyInterceptor extends UpstreamInterceptor {

            private final int sourceOrdinal;
            private final HandleAcquisitionRecorder recorder;
            private final PaddedAtomicLong benchmarkWip = new PaddedAtomicLong();

            /// Retains source identity and accounting while bypassing productive observation.
            DiagnosticLivenessOnlyInterceptor(int sourceOrdinal, HandleAcquisitionRecorder recorder) {
                this.sourceOrdinal = sourceOrdinal;
                this.recorder = recorder;
            }

            @Override
            public void push(AbstractFrame frame) {
                DiagnosticDistributor.this.push(frame);
            }

            @Override
            public long pull(
                    Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
                int worker = this.recorder.currentWorker();
                long frames = 0L;
                if (demand > 0
                        && this.benchmarkWip.getOpaque() != 0L
                        && !DiagnosticDistributor.this.isClosed()
                        && !DiagnosticDistributor.this.drain.getOpaque()
                        && !isComplete()) {
                    try {
                        frames = this.upstream.pull(consumer, stopCondition, demand);
                    } catch (Throwable throwable) {
                        LOGGER.error("Benchmark liveness-only upstream threw during pull", throwable);
                        complete();
                    }
                }
                this.recorder.recordPullResult(this.sourceOrdinal, worker, frames);
                return frames;
            }

            @Override
            public void request(long demand) {
                if (demand <= 0
                        || this.benchmarkWip.getOpaque() == 0L
                        || DiagnosticDistributor.this.isClosed()
                        || DiagnosticDistributor.this.drain.getOpaque()
                        || isComplete()) {
                    return;
                }
                try {
                    this.upstream.request(demand);
                } catch (Throwable throwable) {
                    LOGGER.error("Benchmark liveness-only upstream threw during request", throwable);
                    complete();
                }
            }

            @Override
            public boolean isProductive() {
                return true;
            }

            @Override
            public boolean acquireLock() {
                int worker = this.recorder.currentWorker();
                boolean acquired = this.benchmarkWip.getAndIncrement() == 0L;
                this.recorder.recordAcquisition(this.sourceOrdinal, worker, acquired);
                return acquired;
            }

            @Override
            public void releaseLock() {
                this.benchmarkWip.setRelease(0L);
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

    /// Rejects selected workers that share their nearest unified cache with one another.
    private static void requireDistinctWorkerL2Caches(BitSet workerCores) {
        BitSet[] masks = new BitSet[workerCores.cardinality()];
        int index = 0;
        for (int core = workerCores.nextSetBit(0); core >= 0; core = workerCores.nextSetBit(core + 1)) {
            BitSet cpus = (BitSet) SystemInfo.getCoreInfo(core).getCpuSet().clone();
            cpus.and(SystemInfo.getCpuSet());
            int cpu = cpus.nextSetBit(0);
            if (cpu < 0 || SystemInfo.getCacheLayout(cpu) == null) {
                throw new IllegalStateException("Selected worker is missing effective L2 topology");
            }
            masks[index++] = SystemInfo.getCacheLayout(cpu).getL2Mask();
        }
        if (!pairwiseDisjoint(masks)) {
            throw new IllegalStateException("The diagnostic requires workers with distinct L2 caches");
        }
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

    /// Pins the Phase 13 harness to its explicitly reserved physical core.
    private static void pinHarnessCore(int harnessCore) {
        CoreInfo info = SystemInfo.getCoreInfo(harnessCore);
        if (info == null) {
            throw new IllegalStateException("Reserved harness core is absent: " + harnessCore);
        }
        BitSet cpus = (BitSet) info.getCpuSet().clone();
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
