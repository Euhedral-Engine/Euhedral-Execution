package calibration;

import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.CalibrationLifecycleMode;
import calibration.config.PullBucketTreatment;
import calibration.config.TrialConfig;
import calibration.infra.BenchmarkObserver;
import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.infra.CalibrationExecutor;
import calibration.infra.Constants;
import calibration.io.TrialExport;
import calibration.statistics.HighSpeedMetricsStatistics;
import calibration.statistics.fork.ForkCalculationResult;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.IterationResult;
import calibration.statistics.iteration.SystemIterationResult;
import calibration.statistics.iteration.TrajectoryWindow;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.benchmarks.frames.NoOpFrame;
import io.euhedral_execution.benchmarks.utils.RepeatingSink;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicReferenceArray;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hashing.HasherApi;
import java.io.File;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.AuxCounters.Type;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class CalibrationBenchmark {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalibrationBenchmark.class);

    private final PaddedLongAdder executionCounter = new PaddedLongAdder(SystemInfo.CPU_COUNT);

    private final long configId = System.currentTimeMillis();
    private final long jvmId = ProcessHandle.current().pid();
    private final TrialConfig trialConfig = getConfig();
    private final CalibrationBenchmarkConfig calibrationConfig = trialConfig.calibrationConfig();
    private final CalibrationIterationLifecycle iterationLifecycle =
            new CalibrationIterationLifecycle(this.calibrationConfig.lifecycleMode());
    private final List<PaddedAtomicReferenceArray<HighSpeedMetrics>> measurementObservations = new ArrayList<>();
    private final List<PaddedAtomicReferenceArray<HighSpeedMetrics>> warmupObservations = new ArrayList<>();
    private final List<IterationResult> calculationResults = new ArrayList<>();
    private final List<TrajectoryWindow> trajectoryWindows = new ArrayList<>();
    private ForkCalculationResult forkCalculationResult;
    private BenchmarkObserver observer;
    private ControlPlaneLattice controlPlane;
    private RepeatingSink[] sinks;
    private final List<PullBucketTreatment> measurementTreatments = new ArrayList<>();
    private long trajectoryStartNanos;
    private long windowStartNanos;
    private long windowStartExecutions;

    private static TrialConfig getConfig() {
        String configPath = getRequiredPropertyValue(Constants.TRIAL_CONFIG_PROP);
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(new File(configPath), TrialConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read calibration config", e);
        }
    }

    private static String getRequiredPropertyValue(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Calibration benchmark cannot start without property: " + property);
        }
        return value;
    }

    private static UnmodifiableBitSet parseBitset(List<Integer> cpuSet) {
        BitSet surrogate = new BitSet();
        for (Integer c : cpuSet) {
            surrogate.set(c);
        }
        return UnmodifiableBitSet.wrap(surrogate);
    }

    private static void validateForcedActiveParticipantCount(
            UnmodifiableBitSet workerCpus, Integer forcedActiveParticipantCount) {
        if (forcedActiveParticipantCount == null) {
            return;
        }
        BitSet workerCores = new BitSet();
        for (int workerCpu = workerCpus.nextSetBit(0);
                workerCpu >= 0;
                workerCpu = workerCpus.nextSetBit(workerCpu + 1)) {
            SystemInfo.CpuInfo workerInfo = SystemInfo.getCpuInfo(workerCpu);
            if (workerInfo == null) {
                throw new IllegalArgumentException("Unknown calibration worker CPU: " + workerCpu);
            }
            workerCores.set(workerInfo.core());
        }
        int registeredWorkers = workerCores.cardinality();
        if (forcedActiveParticipantCount > registeredWorkers) {
            throw new IllegalArgumentException("forcedActiveParticipantCount "
                    + forcedActiveParticipantCount
                    + " exceeds resolved registered worker count "
                    + registeredWorkers);
        }
    }

    private static void pinHarnessThread(UnmodifiableBitSet workerCpus) {
        BitSet defaultHarnessCpus = SystemInfo.getCoreInfo(0).getCpuSet();
        int defaultHarnessCpu = defaultHarnessCpus.nextSetBit(0);
        int harnessCpu = defaultHarnessCpu;
        String configuredHarnessCpu = System.getProperty(Constants.HARNESS_CPU_PROP);
        if (configuredHarnessCpu != null) {
            try {
                harnessCpu = Integer.parseInt(configuredHarnessCpu);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Calibration harness CPU must be an integer: " + configuredHarnessCpu, e);
            }
        }
        SystemInfo.CpuInfo harnessInfo = SystemInfo.getCpuInfo(harnessCpu);
        if (harnessInfo == null) {
            throw new IllegalArgumentException("Unknown calibration harness CPU: " + harnessCpu);
        }
        for (int workerCpu = workerCpus.nextSetBit(0);
                workerCpu >= 0;
                workerCpu = workerCpus.nextSetBit(workerCpu + 1)) {
            SystemInfo.CpuInfo workerInfo = SystemInfo.getCpuInfo(workerCpu);
            if (workerInfo != null && workerInfo.core() == harnessInfo.core()) {
                throw new IllegalArgumentException("Calibration harness CPU " + harnessCpu + " shares physical core "
                        + harnessInfo.core() + " with worker CPU " + workerCpu);
            }
        }
        if (!ThreadTools.setAffinity(harnessCpu)) {
            throw new IllegalStateException("Unable to pin calibration harness thread to CPU " + harnessCpu);
        }
    }

    private static void await(long target, PaddedLongAdder counters, long timeoutMs) {
        long now = System.nanoTime();
        long deadline = now + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        int cycles = 0;
        while (System.nanoTime() < deadline) {
            if (counters.sum() >= target) {
                return;
            }
            if ((cycles++ & 127) == 0) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
        throw new RuntimeException("Timed out waiting for invocation to finish.");
    }

    @Setup(Level.Trial)
    public void trialSetup() {
        LOGGER.info("Fork start");
        UnmodifiableBitSet cpuSet = parseBitset(this.calibrationConfig.cpuSet());
        if (cpuSet.isEmpty()) {
            throw new IllegalArgumentException("Cpu set cannot be empty");
        }
        validateForcedActiveParticipantCount(cpuSet, this.calibrationConfig.forcedActiveParticipantCount());
        pinHarnessThread(cpuSet);
        this.observer = new BenchmarkObserver(this.calibrationConfig);
        if (this.calibrationConfig.observePullConvoy()
                && this.calibrationConfig.pullBucketTreatments().size() != this.trialConfig.iterations()) {
            throw new IllegalArgumentException("Pull-bucket treatment count must equal measurement iteration count");
        }
        this.sinks =
                new RepeatingSink[this.calibrationConfig.parallelSources() + this.calibrationConfig.orderedSources()];

        long idHash = HasherApi.BASE_SEED;
        int idx = 0;
        for (int i = 0; i < this.calibrationConfig.parallelSources(); i++) {
            this.sinks[idx++] = new RepeatingSink(NoOpFrame.generate(idHash, 1_024, this.executionCounter, false));
            idHash = HasherApi.mix(idHash + 1);
        }
        for (int i = 0; i < this.calibrationConfig.orderedSources(); i++) {
            this.sinks[idx++] = new RepeatingSink(NoOpFrame.generate(idHash, 1_024, this.executionCounter, true));
            idHash = HasherApi.mix(idHash + 1);
        }

        LatticeConfig latticeConfig = LatticeConfig.ofBenchmark(
                cpuSet,
                this.observer,
                this.calibrationConfig.decisionWeights(),
                new CalibrationExecutor(this.calibrationConfig.workUnits(), this.calibrationConfig.randomizeWork()));
        this.controlPlane = ControlPlaneLattice.getOrCreate(latticeConfig);
        this.controlPlane.start();
        for (RepeatingSink s : this.sinks) {
            this.controlPlane.addUpstream(s);
        }
        this.trajectoryStartNanos = System.nanoTime();
    }

    @Setup(Level.Iteration)
    public void iterationSetup(IterationParams iterationParams) {
        IterationType type = iterationParams != null ? iterationParams.getType() : IterationType.MEASUREMENT;
        int index = type == IterationType.WARMUP ? this.warmupObservations.size() : this.measurementObservations.size();
        int count = type == IterationType.WARMUP ? this.trialConfig.warmups() : this.trialConfig.iterations();
        PullBucketTreatment treatment = type == IterationType.WARMUP
                ? PullBucketTreatment.BASELINE
                : this.calibrationConfig.pullBucketTreatments().isEmpty()
                        ? PullBucketTreatment.BASELINE
                        : this.calibrationConfig.pullBucketTreatments().get(index);
        this.observer.setPullBucketTreatment(treatment);
        if (type == IterationType.MEASUREMENT) {
            this.measurementTreatments.add(treatment);
        }
        LOGGER.info(
                "Iteration start: type={}, index={}, count={}, pullBucketTreatment={}",
                type,
                index,
                count,
                treatment.id());
        this.iterationLifecycle.beforeWindow(this::resetPhysicalState, () -> {
            this.observer.startObserving();
            if (type == IterationType.MEASUREMENT
                    && this.calibrationConfig.lifecycleMode() == CalibrationLifecycleMode.CONTINUOUS) {
                this.windowStartExecutions = this.executionCounter.sum();
                this.windowStartNanos = System.nanoTime();
            }
        });
    }

    @Benchmark
    public void calibrate(OperationCounter opCounter) {
        long sum = this.executionCounter.sum();
        await(
                sum + this.calibrationConfig.totalRequiredExecutions(),
                this.executionCounter,
                this.calibrationConfig.invocationTimeoutMillis());
        // An accurate count of executions is not needed here. The observer has the fine-grained
        // statistics. JMH provides the coarse aggregate used for an overall view.
        opCounter.executions += this.calibrationConfig.totalRequiredExecutions();
    }

    @TearDown(Level.Iteration)
    public void iterationTeardown(IterationParams iterationParams) {
        long windowEndExecutions = 0L;
        long windowEndNanos = 0L;
        IterationType type = iterationParams != null ? iterationParams.getType() : IterationType.MEASUREMENT;
        if (type == IterationType.MEASUREMENT
                && this.calibrationConfig.lifecycleMode() == CalibrationLifecycleMode.CONTINUOUS) {
            windowEndExecutions = this.executionCounter.sum();
            windowEndNanos = System.nanoTime();
        }
        PaddedAtomicReferenceArray<HighSpeedMetrics> obs = this.observer.stopObserving();
        int index = type == IterationType.WARMUP ? this.warmupObservations.size() : this.measurementObservations.size();
        int count = type == IterationType.WARMUP ? this.trialConfig.warmups() : this.trialConfig.iterations();
        LOGGER.info("Iteration stop: type={}, index={}, count={}", type, index, count);
        if (type == IterationType.WARMUP) {
            this.warmupObservations.add(obs);
        } else {
            this.measurementObservations.add(obs);
            if (this.calibrationConfig.lifecycleMode() == CalibrationLifecycleMode.CONTINUOUS) {
                recordTrajectoryWindow(index, windowEndExecutions, windowEndNanos);
            }
        }
        this.iterationLifecycle.afterWindow(this::resetPhysicalState);
    }

    private void resetPhysicalState() {
        this.controlPlane.clear(Duration.ofSeconds(1), this::resetBenchmarkSources);
    }

    private void recordTrajectoryWindow(int index, long windowEndExecutions, long windowEndNanos) {
        this.trajectoryWindows.add(createTrajectoryWindow(
                this.jvmId,
                index,
                this.trajectoryStartNanos,
                this.windowStartNanos,
                windowEndNanos,
                this.windowStartExecutions,
                windowEndExecutions,
                this.calibrationConfig.totalRequiredExecutions()));
    }

    static TrajectoryWindow createTrajectoryWindow(
            long jvmId,
            int index,
            long trajectoryStartNanos,
            long windowStartNanos,
            long windowEndNanos,
            long windowStartExecutions,
            long windowEndExecutions,
            long minimumRequiredExecutions) {
        long completedExecutions = windowEndExecutions - windowStartExecutions;
        long elapsedNanos = windowEndNanos - windowStartNanos;
        boolean continuouslyFed = completedExecutions >= minimumRequiredExecutions;
        if (!continuouslyFed) {
            throw new IllegalStateException("Continuous measurement window " + index + " did not remain fed: completed "
                    + completedExecutions + " executions, expected at least "
                    + minimumRequiredExecutions);
        }
        double throughput = completedExecutions * 1_000_000_000.0 / elapsedNanos;
        return new TrajectoryWindow(
                jvmId,
                index,
                windowEndNanos - trajectoryStartNanos,
                elapsedNanos,
                completedExecutions,
                throughput,
                true);
    }

    private void resetBenchmarkSources() {
        for (RepeatingSink sink : this.sinks) {
            sink.resetForNextIteration();
        }
    }

    @TearDown(Level.Trial)
    public void trialTeardown() throws Exception {
        LOGGER.info("Fork stop");
        for (RepeatingSink s : this.sinks) {
            s.complete();
        }
        this.controlPlane.close();
        SpinWait.awaitWhile(() -> this.controlPlane.getActiveWorkers() > 0);

        // getActiveWorkers is atomic. Fragments only decrement when they are out of the cycle loop
        // This fence is purely for redundancy.
        VarHandle.fullFence();

        int iteration = 0;
        List<List<HighSpeedMetrics>> forkMeasurementMetrics = new ArrayList<>();
        for (PaddedAtomicReferenceArray<HighSpeedMetrics> iterationMetrics : this.measurementObservations) {
            List<CoreIterationResult> iterationResults = new ArrayList<>();
            List<HighSpeedMetrics> participatingMetrics = new ArrayList<>();
            for (int core = 0; core < SystemInfo.getMaxCoreId() + 1; core++) {
                HighSpeedMetrics samples = iterationMetrics.getPlain(core);
                if (samples == null) {
                    continue;
                }
                participatingMetrics.add(samples);
                CoreIterationResult result = HighSpeedMetricsStatistics.calculate(iteration, core, samples);
                iterationResults.add(result);
            }
            SystemIterationResult systemResult =
                    HighSpeedMetricsStatistics.calculateSystem(iteration, participatingMetrics);
            this.calculationResults.add(new IterationResult(iteration, systemResult, iterationResults));
            forkMeasurementMetrics.add(participatingMetrics);
            iteration++;
        }

        SystemForkResult forkResult = HighSpeedMetricsStatistics.calculateSystemFork(0, forkMeasurementMetrics);
        this.forkCalculationResult = new ForkCalculationResult(forkResult, this.calculationResults);

        String output = System.getProperty(Constants.OUTPUT_DIRECTORY_PROP);
        if (output == null || output.isBlank()) {
            return;
        }
        boolean retainObserverData =
                Boolean.parseBoolean(System.getProperty(Constants.RETAIN_OBSERVER_DATA_PROP, "true"));
        if (!retainObserverData) {
            return;
        }
        boolean retainPerFork =
                Boolean.parseBoolean(System.getProperty(Constants.RETAIN_PER_FORK_RESULTS_PROP, "false"));
        boolean retainPerIteration =
                Boolean.parseBoolean(System.getProperty(Constants.RETAIN_PER_ITERATION_RESULTS_PROP, "false"));

        Path targetPath = retainPerFork
                ? Path.of(output, "fork-" + this.configId + "-" + System.currentTimeMillis() + "/")
                : Path.of(output);
        File outputDir = targetPath.toFile();
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new RuntimeException("Failed to create output directory: " + targetPath);
        }
        TrialExport.exportAll(targetPath, this.forkCalculationResult, retainPerIteration);
        if (this.calibrationConfig.lifecycleMode() == CalibrationLifecycleMode.CONTINUOUS) {
            TrialExport.exportTrajectoryTsv(
                    targetPath,
                    this.calibrationConfig.lifecycleMode(),
                    this.trajectoryWindows,
                    this.forkCalculationResult,
                    forkMeasurementMetrics);
        }
        if (this.calibrationConfig.observeContentionStaleness()) {
            TrialExport.exportContentionStalenessTsv(targetPath, forkMeasurementMetrics);
        }
        if (this.calibrationConfig.observePullConvoy()) {
            TrialExport.exportPullConvoyTsv(
                    targetPath,
                    this.calibrationConfig.pullBucketFork(),
                    this.measurementTreatments,
                    forkMeasurementMetrics);
        }
    }

    @State(Scope.Thread)
    @AuxCounters(Type.OPERATIONS)
    public static class OperationCounter {
        public long executions = 0L;
    }
}
