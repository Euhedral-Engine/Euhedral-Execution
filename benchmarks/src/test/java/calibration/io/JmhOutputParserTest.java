package calibration.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calibration.comparisons.schema.ThroughputResult;
import calibration.io.exceptions.MalformedArtifactException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Tests for JmhOutputParser verifying extraction and prioritization of auxiliary counter throughput.
class JmhOutputParserTest {

    @Test
    void testPrioritizesAuxiliaryCounterOverPrimaryBenchmark(@TempDir Path tempDir) throws Exception {
        Path logPath = tempDir.resolve("benchmark_output.log");
        String logContent = """
                # JMH version: 1.37
                # Benchmark: calibration.CalibrationBenchmark.calibrate
                # Fork: 1 of 1
                # Warmup Iteration   1: 0.050 ops/ms
                                 ·executions: 400000.000 ops/ms
                Iteration   1: 0.052 ops/ms
                                 ·executions: 412345.678 ops/ms
                Iteration   2: 0.054 ops/ms
                                 ·executions: 423456.789 ops/ms
                Iteration   3: 0.053 ops/ms
                                 ·executions: 418765.432 ops/ms

                Result "calibration.CalibrationBenchmark.calibrate":
                  0.053 ±(99.9%) 0.005 ops/ms [Average]
                  (min, avg, max) = (0.052, 0.053, 0.054), stdev = 0.001
                  CI (99.9%): [0.048, 0.058] (assumes normal distribution)

                Secondary result "calibration.CalibrationBenchmark.calibrate:executions":
                  418189.300 ±(99.9%) 1234.567 ops/ms [Average]
                  (min, avg, max) = (412345.678, 418189.300, 423456.789), stdev = 5555.555
                  CI (99.9%): [416666.666, 419135.800] (assumes normal distribution)

                # Run complete. Total time: 00:00:30

                Benchmark                                                Mode  Cnt        Score       Error   Units
                CalibrationBenchmark.calibrate                          thrpt    3        0.053 ±     0.005  ops/ms
                CalibrationBenchmark.calibrate:executions               thrpt    3   418189.300 ±  1234.567  ops/ms
                """;
        Files.writeString(logPath, logContent);

        ThroughputResult result = JmhOutputParser.parse(tempDir, logPath);
        assertNotNull(result);
        assertEquals(418189.300, result.score(), 1e-6);
        assertEquals(1234.567, result.scoreError(), 1e-6);
        assertEquals("ops/ms", result.scoreUnit());
        assertEquals(List.of(412345.678, 423456.789, 418765.432), result.iterationScores());
        assertEquals(List.of((412345.678 + 423456.789 + 418765.432) / 3.0), result.forkScores());
    }

    @Test
    void testFallsBackToPrimaryBenchmarkWhenNoAuxCountersPresent(@TempDir Path tempDir) throws Exception {
        Path logPath = tempDir.resolve("benchmark_output.log");
        String logContent = """
                # JMH version: 1.37
                # Benchmark: calibration.benchmarks.CalibrationBenchmark.benchmark
                # Fork: 1 of 1
                Iteration   1: 12345.678 ops/s
                Iteration   2: 12456.789 ops/s
                Iteration   3: 12567.890 ops/s

                Benchmark                                                 Mode  Cnt      Score     Error  Units
                CalibrationBenchmark.benchmark                           thrpt    3  12456.786 +/- 123.456  ops/s
                """;
        Files.writeString(logPath, logContent);

        ThroughputResult result = JmhOutputParser.parse(tempDir, logPath);
        assertNotNull(result);
        assertEquals(12456.786, result.score(), 1e-6);
        assertEquals(123.456, result.scoreError(), 1e-6);
        assertEquals("ops/s", result.scoreUnit());
        assertEquals(List.of(12345.678, 12456.789, 12567.890), result.iterationScores());
    }

    @Test
    void testAuxiliaryCountersWithVariousPrefixFormats(@TempDir Path tempDir) throws Exception {
        Path logPath = tempDir.resolve("benchmark_output.log");
        String logContent = """
                Iteration   1: 0.052 ops/ms
                  calibrate:executions: 100000.0 ops/ms
                Iteration   2: 0.054 ops/ms
                  calibrate:executions: 200000.0 ops/ms
                """;
        Files.writeString(logPath, logContent);

        ThroughputResult result = JmhOutputParser.parse(tempDir, logPath);
        assertNotNull(result);
        assertEquals(150000.0, result.score(), 1e-6);
        assertEquals("ops/ms", result.scoreUnit());
        assertEquals(List.of(100000.0, 200000.0), result.iterationScores());
    }

    @Test
    void testCalculatesIndependentAuxiliaryMeansForEachFork(@TempDir Path tempDir) throws Exception {
        Path logPath = tempDir.resolve("benchmark_output.log");
        String logContent = """
                # Fork: 1 of 2
                # Warmup Iteration   1: 0.050 ops/s
                                 executions: 900.0 ops/s
                Iteration   1: 0.052 ops/s
                                 executions: 1000.0 ops/s
                Iteration   2: 0.054 ops/s
                                 executions: 1200.0 ops/s

                # Fork: 2 of 2
                # Warmup Iteration   1: 0.051 ops/s
                                 executions: 950.0 ops/s
                Iteration   1: 0.053 ops/s
                                 executions: 1400.0 ops/s
                Iteration   2: 0.055 ops/s
                                 executions: 1600.0 ops/s

                Secondary result "calibration.CalibrationBenchmark.calibrate:executions":
                  1300.0 +/- 100.0 ops/s [Average]
                """;
        Files.writeString(logPath, logContent);

        ThroughputResult result = JmhOutputParser.parse(tempDir, logPath);

        assertEquals(List.of(1100.0, 1500.0), result.forkScores());
        assertEquals(List.of(1000.0, 1200.0, 1400.0, 1600.0), result.iterationScores());
    }

    @Test
    void testEmptyLogThrowsMalformedArtifactException(@TempDir Path tempDir) throws Exception {
        Path logPath = tempDir.resolve("benchmark_output.log");
        Files.writeString(logPath, "   \n\t  ");

        assertThrows(MalformedArtifactException.class, () -> JmhOutputParser.parse(tempDir, logPath));
    }
}
