package calibration;

import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.TrialConfig;
import calibration.infra.CalibrationExecutor;
import calibration.infra.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.flow_control.LatticeHotSource;
import io.euhedral_execution.core.frames.DummyFrame;
import io.euhedral_execution.core.utils.MicroCalibrator;
import io.euhedral_execution.core.utils.StopWatch;
import java.io.File;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class WeightBenchmark {

    private final TrialConfig trialConfig = getConfig();
    private final CalibrationBenchmarkConfig calibrationConfig = trialConfig.calibrationConfig();
    private final MicroCalibrator calibrator = new MicroCalibrator();
    private LatticeHotSource latticeHotSource;

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

    @Setup(Level.Trial)
    public void setupTrial(Blackhole bh) {
        calibrator.warmup();
        Objects.requireNonNull(this.calibrationConfig, "Calibration config not set");
        StopWatch stopWatch = new StopWatch();
        // This is how ControlPlaneFragment sets up its output stream and measures the body cost.
        this.latticeHotSource = new LatticeHotSource(ignored -> stopWatch.start(), () -> {
            bh.consume(stopWatch.stop());
        });
        CalibrationExecutor executor = new CalibrationExecutor(this.calibrationConfig.workUnits(), false);
        executor.input(latticeHotSource);
    }

    @Benchmark
    @OperationsPerInvocation(32_000_000)
    public void calibrate() {
        for (int i = 0; i < 32_000_000; i++) {
            this.latticeHotSource.accept(DummyFrame.INSTANCE);
        }
    }
}
