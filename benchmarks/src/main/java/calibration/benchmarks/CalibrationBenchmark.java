package calibration.benchmarks;

import calibration.config.CalibrationBenchmarkConfig;
import calibration.infra.BenchmarkObserver;
import calibration.infra.CalibrationExecutor;
import calibration.infra.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.benchmarks.frames.NoOpFrame;
import io.euhedral_execution.benchmarks.utils.RepeatingSink;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hashing.HasherApi;
import java.io.File;
import java.time.Duration;
import java.util.BitSet;
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

    private final CalibrationBenchmarkConfig calibrationConfig = getConfig();
    private BenchmarkObserver observer;
    private ControlPlaneLattice controlPlane;
    private RepeatingSink[] sinks;

    private static CalibrationBenchmarkConfig getConfig() {
        String configPath = getRequiredPropertyValue(Constants.TRIAL_CONFIG_PROP);
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(new File(configPath), CalibrationBenchmarkConfig.class);
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
        UnmodifiableBitSet cpuSet = parseBitset(getRequiredPropertyValue(Constants.CPU_SET_PROP));
        this.observer = new BenchmarkObserver(this.calibrationConfig);
        this.sinks =
                new RepeatingSink[this.calibrationConfig.parallelSources() + this.calibrationConfig.orderedSources()];

        long idHash = HasherApi.BASE_SEED;
        int idx = 0;
        for (int i = 0; i < this.calibrationConfig.parallelSources(); i++) {
            this.sinks[idx++] = new RepeatingSink(NoOpFrame.generate(idHash, 1_024, this.executionCounter, false));
        }
        for (int i = 0; i < this.calibrationConfig.orderedSources(); i++) {
            this.sinks[idx++] = new RepeatingSink(NoOpFrame.generate(idHash, 1_024, this.executionCounter, false));
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
        this.observer.startObserving();
    }

    @Benchmark
    public void calibrate(OperationCounter opCounter) {
        this.executionCounter.reset();
        await(
                this.calibrationConfig.totalRequiredExecutions(),
                this.executionCounter,
                this.calibrationConfig.invocationTimeoutMillis());
        opCounter.executions += this.calibrationConfig.totalRequiredExecutions();
    }

    @TearDown(Level.Iteration)
    public void iterationTeardown() {
        this.observer.stopObserving();
        this.controlPlane.clear(Duration.ofMillis(1));
    }

    @TearDown(Level.Trial)
    public void trialTeardown() {
        for (RepeatingSink s : this.sinks) {
            s.complete();
        }
        this.controlPlane.close();
    }

    @State(Scope.Thread)
    @AuxCounters(Type.OPERATIONS)
    public static class OperationCounter {
        public long executions = 0L;
    }
}
