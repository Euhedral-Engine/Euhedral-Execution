package io.euhedral_execution.core.control_plane;

import static io.euhedral_execution.core.utils.MathFunctions.unsignedMultiplyHigh;

import io.euhedral_execution.benchmarks.utils.RepeatingSink;
import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.BenchmarkFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.impl.BaseCloneableObject;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hashing.HasherApi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

/// Forced-mode diagnostic for deciding whether direct no-op overhead predicts the faster fragment
/// path independently of upstream-source availability.
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
        long value = input;
        for (int i = 0; i < CPU_WORK_ROUNDS; i++) {
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

    /// Owner-local state for the scheduler-free work-body measurements.
    @State(Scope.Thread)
    public static class WorkOnlyState {

        final BenchmarkFrame frame = new BenchmarkFrame(HasherApi.BASE_SEED);
        long value = HasherApi.BASE_SEED;
    }

    /// Shared lifecycle for one forced-mode fragment graph owned by a JMH trial.
    @State(Scope.Benchmark)
    public abstract static class PathState {

        @Param({"DIRECT", "STAGED"})
        public ForcedMode mode;

        private final PaddedLongAdder counters = new PaddedLongAdder(SystemInfo.CPU_COUNT, true, true);
        private final List<CloneableObject> pipelines = new ArrayList<>();
        private DiagnosticLease diagnosticLease;
        private DiagnosticDistributor distributor;
        private RepeatingSink[] sources;
        /// Logical CPUs in the same stable order as the selected worker cores.
        private int[] workerCpus;
        /// Per-worker completion totals accumulated during the current measurement iteration.
        private long[] iterationWorkerDeltas;
        /// Sum of fixed completion-window durations during the current measurement iteration.
        private long iterationElapsedNanos;
        /// Raw per-worker deltas aligned with the five JMH measurement iterations.
        private final List<long[]> measurementWorkerDeltas = new ArrayList<>();
        /// Fixed-window elapsed totals aligned with `measurementWorkerDeltas`.
        private final List<Long> measurementElapsedNanos = new ArrayList<>();
        /// True only while JMH is invoking the measured, non-warmup iteration.
        private boolean measurementIteration;
        /// Source availability retained for the fork-level diagnostic report.
        private SourceShape sourceShape;
        /// Executor work body retained for the fork-level diagnostic report.
        private Workload workload;

        /// Builds and starts the requested pinned fragment graph without the lattice monitor.
        protected final void setupPath(int workerCount, SourceShape sourceShape, Workload workload) {
            try {
                this.sourceShape = sourceShape;
                this.workload = workload;
                this.workerCpus = new int[workerCount];
                this.measurementWorkerDeltas.clear();
                this.measurementElapsedNanos.clear();
                DiagnosticDistributor.resetSharedRoutingState();
                this.diagnosticLease = new DiagnosticLease(this.mode, FIXED_BATCH_SIZE);

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

                BaseCloneableObject base = new BaseCloneableObject(new CountingExecutor(-1, this.counters, workload));
                int workerIndex = 0;
                for (int core = workerCores.nextSetBit(0); core >= 0; core = workerCores.nextSetBit(core + 1)) {
                    BitSet cpus =
                            (BitSet) SystemInfo.getCoreInfo(core).getCpuSet().clone();
                    cpus.and(SystemInfo.getCpuSet());
                    int workerCpu = cpus.nextSetBit(0);
                    if (workerCpu < 0) {
                        throw new IllegalStateException("Selected worker core has no effective logical CPU");
                    }
                    this.workerCpus[workerIndex++] = workerCpu;
                    CloneableObject pipeline = base.clone(new CloneConfig("FragmentPathCalibration", core, cpus));
                    pipeline.input(handles[core]);
                    pipeline.setDrainMode(true);
                    pipeline.start();
                    this.pipelines.add(pipeline);
                }
                awaitRegisteredWorkers(workerCount);
                for (CloneableObject pipeline : this.pipelines) {
                    pipeline.setDrainMode(false);
                }
                this.distributor.setDrain(false);

                int sourceCount = sourceCount(sourceShape, workerCount);
                this.sources = new RepeatingSink[sourceCount];
                long idHash = HasherApi.mix(HasherApi.BASE_SEED);
                for (int i = 0; i < sourceCount; i++) {
                    BenchmarkFrame[] frames = BenchmarkFrame.generate(
                            FRAME_POOL_SIZE, false, idHash + i, HasherApi.BASE_SEED + (long) i * FRAME_POOL_SIZE);
                    this.sources[i] = new RepeatingSink(frames);
                    this.distributor.ingest(this.sources[i].getDelegate());
                }
            } catch (RuntimeException e) {
                closePath();
                throw e;
            }
        }

        /// Opens one measurement-only participation accumulator and ignores warmup iterations.
        @Setup(Level.Iteration)
        public final void setupIteration(IterationParams iterationParams) {
            this.measurementIteration = iterationParams.getType() == IterationType.MEASUREMENT;
            if (this.measurementIteration) {
                this.iterationWorkerDeltas = new long[this.workerCpus.length];
                this.iterationElapsedNanos = 0L;
            }
        }

        /// Retains one raw per-worker split aligned with the completed JMH measurement iteration.
        @TearDown(Level.Iteration)
        public final void tearDownIteration(IterationParams iterationParams) {
            if (iterationParams.getType() == IterationType.MEASUREMENT) {
                this.measurementWorkerDeltas.add(this.iterationWorkerDeltas.clone());
                this.measurementElapsedNanos.add(this.iterationElapsedNanos);
            }
            this.measurementIteration = false;
            this.iterationWorkerDeltas = null;
            this.iterationElapsedNanos = 0L;
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
        }

        /// Reports raw measurement splits and fork-level participation metrics before graph close.
        private void reportParticipation() {
            long[][] rawDeltas = this.measurementWorkerDeltas.toArray(long[][]::new);
            long[] finalWorkerCounts = workerCounts(this.counters, this.workerCpus);
            if (rawDeltas.length == 0) {
                LOGGER.info(
                        "Fragment worker participation mode={} sourceShape={} workload={} batch={} workerCpus={} "
                                + "rawMeasurementDeltas=[] finalWorkerCounts={} verdict=NO_MEASUREMENT_SAMPLES",
                        this.mode,
                        this.sourceShape,
                        this.workload,
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
            double singleLaneCeilingFramesPerSecond = singleLaneCeiling(this.workload, this.mode);
            for (int i = 0; i < rawDeltas.length; i++) {
                ParticipationMetrics metrics = participationMetrics(
                        rawDeltas[i], this.measurementElapsedNanos.get(i), singleLaneCeilingFramesPerSecond);
                fractions[i] = metrics.fractions();
                dominance[i] = metrics.dominance();
                effectiveLanes[i] = metrics.effectiveLanes();
                for (int worker = 0; worker < aggregateDeltas.length; worker++) {
                    aggregateDeltas[worker] = Math.addExact(aggregateDeltas[worker], rawDeltas[i][worker]);
                }
                aggregateElapsedNanos = Math.addExact(aggregateElapsedNanos, this.measurementElapsedNanos.get(i));
            }
            ParticipationMetrics aggregate =
                    participationMetrics(aggregateDeltas, aggregateElapsedNanos, singleLaneCeilingFramesPerSecond);
            LOGGER.info(
                    "Fragment worker participation mode={} sourceShape={} workload={} batch={} workerCpus={} "
                            + "rawMeasurementDeltas={} perMeasurementFractions={} perMeasurementDominance={} "
                            + "perMeasurementEffectiveLanes={} aggregateDeltas={} aggregateFractions={} "
                            + "aggregateDominance={} aggregateEffectiveLanes={} finalWorkerCounts={} "
                            + "singleLaneCeilingFramesPerSecond={}",
                    this.mode,
                    this.sourceShape,
                    this.workload,
                    FIXED_BATCH_SIZE,
                    Arrays.toString(this.workerCpus),
                    Arrays.deepToString(rawDeltas),
                    Arrays.deepToString(fractions),
                    Arrays.toString(dominance),
                    Arrays.toString(effectiveLanes),
                    Arrays.toString(aggregateDeltas),
                    Arrays.toString(aggregate.fractions()),
                    aggregate.dominance(),
                    aggregate.effectiveLanes(),
                    Arrays.toString(finalWorkerCounts),
                    singleLaneCeilingFramesPerSecond);
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
        private long workSink = HasherApi.BASE_SEED;

        /// Creates an executor prototype or pinned clone for the selected work body.
        CountingExecutor(int cpu, PaddedLongAdder counters, Workload workload) {
            super(cpu);
            this.counters = counters;
            this.workload = workload;
        }

        /// Executes the no-op frame, optionally performs CPU work, and publishes completion.
        @Override
        public void execute(AbstractFrame frame) {
            frame.execute();
            if (this.workload == Workload.CPU_WORK) {
                this.workSink = cpuWork(this.workSink ^ frame.getRoutingHash());
            }
            this.counters.increment(super.cpu);
        }

        /// Clones the benchmark executor for the fragment's selected logical CPU.
        @Override
        public CountingExecutor hookOnClone(int cpu) {
            return new CountingExecutor(cpu, this.counters, this.workload);
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
