package calibration.benchmarks;

import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.TrialConfig;
import calibration.infra.BenchmarkObserver;
import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.infra.CalibrationExecutor;
import calibration.infra.Constants;
import calibration.io.TrialExport;
import calibration.statistics.HighSpeedMetricsStatistics;
import calibration.statistics.iteration.CoreIterationResult;
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
import java.io.BufferedWriter;
import java.io.File;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
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

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class CalibrationBenchmark {

    private final PaddedLongAdder executionCounter = new PaddedLongAdder(SystemInfo.CPU_COUNT);

    private final TrialConfig trialConfig = getConfig();
    private final CalibrationBenchmarkConfig calibrationConfig = trialConfig.calibrationConfig();
    private final List<PaddedAtomicReferenceArray<HighSpeedMetrics>> observations = new ArrayList<>();
    private final List<List<CoreIterationResult>> calculationResults = new ArrayList<>();
    private BenchmarkObserver observer;
    private ControlPlaneLattice controlPlane;
    private RepeatingSink[] sinks;

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

    private static UnmodifiableBitSet parseBitset(String raw) {
        String[] tokens = raw.split(",");
        BitSet surrogate = new BitSet();
        for (String t : tokens) {
            surrogate.set(Integer.parseInt(t));
        }
        return UnmodifiableBitSet.wrap(surrogate);
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
        // Benchmark thread stays off active cores
        ThreadTools.setAffinity(SystemInfo.getCoreInfo(0).getCpuSet());
        UnmodifiableBitSet cpuSet = parseBitset(getRequiredPropertyValue(Constants.CPU_SET_PROP));
        if (cpuSet.isEmpty()) {
            throw new IllegalArgumentException("Cpu set cannot be empty");
        }
        this.observer = new BenchmarkObserver(this.calibrationConfig);
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
    }

    @Setup(Level.Iteration)
    public void iterationSetup() {
        this.controlPlane.clear(Duration.ofMillis(1));
        this.observer.startObserving();
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
    public void iterationTeardown() {
        this.observations.add(this.observer.stopObserving());
    }

    @TearDown(Level.Trial)
    public void trialTeardown() throws Exception {
        for (RepeatingSink s : this.sinks) {
            s.complete();
        }
        this.controlPlane.close();
        SpinWait.awaitWhile(() -> this.controlPlane.getActiveWorkers() > 0);

        // getActiveWorkers is atomic. Fragments only decrement when they are out of the cycle loop
        // This fence is purely for redundancy.
        VarHandle.fullFence();
        for (int i = 0; i < this.trialConfig.warmups(); i++) {
            this.observations.removeFirst();
        }
        int iteration = 0;
        for (PaddedAtomicReferenceArray<HighSpeedMetrics> iterationMetrics : this.observations) {
            List<CoreIterationResult> iterationResults = new ArrayList<>();
            for (int core = 0; core < SystemInfo.getMaxCoreId() + 1; core++) {
                HighSpeedMetrics samples = iterationMetrics.getPlain(core);
                if (samples == null) {
                    continue;
                }
                CoreIterationResult result = HighSpeedMetricsStatistics.calculate(iteration, core, samples);
                iterationResults.add(result);
            }
            this.calculationResults.add(Collections.unmodifiableList(iterationResults));
            iteration++;
        }
        String output = System.getProperty(Constants.OUTPUT_DIRECTORY_PROP);
        if (output == null || output.isBlank()) {
            return;
        }
        Path path = Path.of(output, "fork-" + System.currentTimeMillis() + "/");
        File outputDir = path.toFile();
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new RuntimeException("Failed to create output directory: " + output);
        }

        File configFile = Path.of(output).resolve(Constants.TRIAL_FILE_NAME).toFile();
        if (!configFile.exists()) {
            ObjectMapper mapper = new ObjectMapper();
            String trialJson = mapper.writeValueAsString(this.trialConfig);
            try (BufferedWriter writer = Files.newBufferedWriter(configFile.toPath())) {
                writer.write(trialJson);
            }
            TrialExport.writeChecksum(configFile.toPath());
        }
        TrialExport.exportAll(path, this.calculationResults);
    }

    @State(Scope.Thread)
    @AuxCounters(Type.OPERATIONS)
    public static class OperationCounter {
        public long executions = 0L;
    }
}
