package calibration.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.TrialConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletedRunLoaderTest {

    @Test
    void loadsConfigurationAndAllForkScores(@TempDir Path run) throws Exception {
        TrialConfig trial = new TrialConfig(2, 1, 2, List.of(), benchmarkConfig());
        new ObjectMapper().writeValue(run.resolve("trial_config.json").toFile(), trial);
        Files.writeString(run.resolve("benchmark_output.log"), """
                # Fork: 1 of 2
                Iteration   1: 1.0 ops/s
                                 executions: 100.0 ops/s
                Iteration   2: 1.0 ops/s
                                 executions: 120.0 ops/s
                # Fork: 2 of 2
                Iteration   1: 1.0 ops/s
                                 executions: 140.0 ops/s
                Iteration   2: 1.0 ops/s
                                 executions: 160.0 ops/s
                Secondary result "calibration.CalibrationBenchmark.calibrate:executions":
                  130.0 +/- 10.0 ops/s [Average]
                """);

        var completed = CompletedRunLoader.load(run);

        assertEquals(trial, completed.trialConfig());
        assertEquals(List.of(110.0, 150.0), completed.throughput().forkScores());
        assertEquals(
                run.toAbsolutePath().normalize().toString(),
                completed.identity().sourcePath());
    }

    static CalibrationBenchmarkConfig benchmarkConfig() {
        return new CalibrationBenchmarkConfig(
                List.of(2, 4), 2, 0, 0, false, 100, 1_000, 8, false, false, 15_000L, 1_000_000L, null);
    }
}
