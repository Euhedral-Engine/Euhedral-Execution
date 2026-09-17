package calibration.io;

import calibration.infra.Constants;
import calibration.statistics.fork.ForkCalculationResult;
import calibration.statistics.iteration.BatchCompleteScalars;
import calibration.statistics.iteration.BatchCompleteStatistics;
import calibration.statistics.iteration.BatchProgressScalars;
import calibration.statistics.iteration.BatchProgressStatistics;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.IterationResult;
import calibration.statistics.iteration.ScalarSummary;
import calibration.statistics.iteration.SystemIterationResult;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/// Exports the two observation streams still exposed by FragmentObserver.
public final class TrialExport {

    private static final String[] SEGMENTS = {"head", "steadyState", "combined"};

    private TrialExport() {}

    public static void exportAll(Path outputDir, ForkCalculationResult result, boolean perIteration) throws Exception {
        if (outputDir == null || result == null) {
            return;
        }
        Files.createDirectories(outputDir);
        exportRawObservations(outputDir, result);
        exportStatistics(outputDir, result);
        exportCorrelations(outputDir, result);

        if (perIteration) {
            for (IterationResult iteration : result.iterations()) {
                Path iterationDirectory = outputDir.resolve("iteration-" + iteration.iterationIndex());
                Files.createDirectories(iterationDirectory);
                ForkCalculationResult oneIteration = new ForkCalculationResult(
                        calibration.statistics.fork.SystemForkResult.empty(0, 0, 0), java.util.List.of(iteration));
                exportRawObservations(iterationDirectory, oneIteration);
                exportStatistics(iterationDirectory, oneIteration);
                exportCorrelations(iterationDirectory, oneIteration);
            }
        }
    }

    public static void writeChecksum(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        Path checksum = file.resolveSibling(file.getFileName() + ".sha256");
        Files.writeString(checksum, HexFormat.of().formatHex(digest.digest()) + "\n", StandardCharsets.UTF_8);
    }

    private static void exportRawObservations(Path outputDir, ForkCalculationResult result) throws Exception {
        Path file = outputDir.resolve(Constants.RAW_OBSERVATION_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(calibration.statistics.fork.SystemForkResult.TSV_HEADER);
            if (result.system().measurementIterationCount() > 0) {
                writer.write(result.system().toTsvRow());
                writer.newLine();
            }
            for (IterationResult iteration : result.iterations()) {
                writer.write(iteration.system().toTsvRow());
                writer.newLine();
                for (CoreIterationResult core : iteration.cores()) {
                    writer.write(core.toTsvRow());
                    writer.newLine();
                }
            }
        }
        writeChecksum(file);
    }

    private static void exportStatistics(Path outputDir, ForkCalculationResult result) throws Exception {
        Path file = outputDir.resolve(Constants.STATISTICS_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "iteration\tscope\tcore\tmetric\tsegment\tvariable\tcount\tmean\tstdDev\tvariance\tcv\tmin\tmax\tmedian\tp25\tp50\tp75\tp95\tiqr\tnormalizedIqr\tp95ToP50Ratio\n");
            if (result.system().measurementIterationCount() > 0) {
                writeStatistics(
                        writer,
                        -1,
                        "FORK",
                        -1,
                        result.system().batchProgress(),
                        result.system().batchComplete());
            }
            for (IterationResult iteration : result.iterations()) {
                SystemIterationResult system = iteration.system();
                writeStatistics(
                        writer,
                        iteration.iterationIndex(),
                        "ITERATION",
                        -1,
                        system.batchProgress(),
                        system.batchComplete());
                for (CoreIterationResult core : iteration.cores()) {
                    writeStatistics(
                            writer,
                            iteration.iterationIndex(),
                            "CORE",
                            core.core(),
                            core.batchProgress(),
                            core.batchComplete());
                }
            }
        }
        writeChecksum(file);
    }

    private static void writeStatistics(
            BufferedWriter writer,
            int iteration,
            String scope,
            int core,
            BatchProgressStatistics progress,
            BatchCompleteStatistics complete)
            throws Exception {
        BatchProgressScalars[] progressSegments = {progress.head(), progress.steadyState(), progress.combined()};
        BatchCompleteScalars[] completeSegments = {complete.head(), complete.steadyState(), complete.combined()};
        for (int index = 0; index < SEGMENTS.length; index++) {
            BatchProgressScalars p = progressSegments[index];
            writeScalar(
                    writer,
                    iteration,
                    scope,
                    core,
                    "batchProgress",
                    SEGMENTS[index],
                    "upstreamCount",
                    p.upstreamCount());
            writeScalar(
                    writer,
                    iteration,
                    scope,
                    core,
                    "batchProgress",
                    SEGMENTS[index],
                    "registeredWorkers",
                    p.registeredWorkers());
            writeScalar(
                    writer,
                    iteration,
                    scope,
                    core,
                    "batchProgress",
                    SEGMENTS[index],
                    "productiveHandleCount",
                    p.productiveHandleCount());
            writeScalar(
                    writer,
                    iteration,
                    scope,
                    core,
                    "batchProgress",
                    SEGMENTS[index],
                    "productiveHandleRatio",
                    p.productiveHandleRatio());
            writeScalar(writer, iteration, scope, core, "batchProgress", SEGMENTS[index], "workerRank", p.workerRank());
            writeScalar(writer, iteration, scope, core, "batchProgress", SEGMENTS[index], "contention", p.contention());
            writeScalar(
                    writer,
                    iteration,
                    scope,
                    core,
                    "batchProgress",
                    SEGMENTS[index],
                    "avgServiceTime",
                    p.avgServiceTime());

            BatchCompleteScalars c = completeSegments[index];
            writeScalar(
                    writer,
                    iteration,
                    scope,
                    core,
                    "batchComplete",
                    SEGMENTS[index],
                    "upstreamCount",
                    c.upstreamCount());
            writeScalar(
                    writer,
                    iteration,
                    scope,
                    core,
                    "batchComplete",
                    SEGMENTS[index],
                    "registeredWorkers",
                    c.registeredWorkers());
            writeScalar(
                    writer,
                    iteration,
                    scope,
                    core,
                    "batchComplete",
                    SEGMENTS[index],
                    "productiveHandleCount",
                    c.productiveHandleCount());
            writeScalar(
                    writer,
                    iteration,
                    scope,
                    core,
                    "batchComplete",
                    SEGMENTS[index],
                    "productiveHandleRatio",
                    c.productiveHandleRatio());
            writeScalar(writer, iteration, scope, core, "batchComplete", SEGMENTS[index], "workerRank", c.workerRank());
            writeScalar(writer, iteration, scope, core, "batchComplete", SEGMENTS[index], "contention", c.contention());
            writeScalar(
                    writer,
                    iteration,
                    scope,
                    core,
                    "batchComplete",
                    SEGMENTS[index],
                    "avgServiceTime",
                    c.avgServiceTime());
            writeScalar(writer, iteration, scope, core, "batchComplete", SEGMENTS[index], "throughput", c.throughput());
        }
    }

    private static void writeScalar(
            BufferedWriter writer,
            int iteration,
            String scope,
            int core,
            String metric,
            String segment,
            String variable,
            ScalarSummary summary)
            throws Exception {
        writer.write(iteration + "\t" + scope + "\t" + core + "\t" + metric + "\t" + segment + "\t" + variable + "\t"
                + summary.toTsvRow() + "\n");
    }

    private static void exportCorrelations(Path outputDir, ForkCalculationResult result) throws Exception {
        Path file = outputDir.resolve(Constants.CORRELATIONS_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("iteration\tscope\tcore\tmetric\tsegment\tvariable1\tvariable2\tpearson\tspearman\n");
            if (result.system().measurementIterationCount() > 0) {
                writeCorrelations(
                        writer,
                        -1,
                        "FORK",
                        -1,
                        result.system().batchProgress(),
                        result.system().batchComplete());
            }
            for (IterationResult iteration : result.iterations()) {
                writeCorrelations(
                        writer,
                        iteration.iterationIndex(),
                        "ITERATION",
                        -1,
                        iteration.system().batchProgress(),
                        iteration.system().batchComplete());
                for (CoreIterationResult core : iteration.cores()) {
                    writeCorrelations(
                            writer,
                            iteration.iterationIndex(),
                            "CORE",
                            core.core(),
                            core.batchProgress(),
                            core.batchComplete());
                }
            }
        }
        writeChecksum(file);
    }

    private static void writeCorrelations(
            BufferedWriter writer,
            int iteration,
            String scope,
            int core,
            BatchProgressStatistics progress,
            BatchCompleteStatistics complete)
            throws Exception {
        CorrelationResult[] progressSegments = {
            progress.headCorrelations(), progress.steadyStateCorrelations(), progress.combinedCorrelations()
        };
        CorrelationResult[] completeSegments = {
            complete.headCorrelations(), complete.steadyStateCorrelations(), complete.combinedCorrelations()
        };
        for (int index = 0; index < SEGMENTS.length; index++) {
            writeCorrelation(writer, iteration, scope, core, "batchProgress", SEGMENTS[index], progressSegments[index]);
            writeCorrelation(writer, iteration, scope, core, "batchComplete", SEGMENTS[index], completeSegments[index]);
        }
    }

    private static void writeCorrelation(
            BufferedWriter writer,
            int iteration,
            String scope,
            int core,
            String metric,
            String segment,
            CorrelationResult result)
            throws Exception {
        String[] names = result.columnNames();
        double[][] pearson = result.pearsonMatrix();
        double[][] spearman = result.spearmanMatrix();
        for (int row = 0; row < names.length; row++) {
            for (int column = 0; column < names.length; column++) {
                writer.write(iteration + "\t" + scope + "\t" + core + "\t" + metric + "\t" + segment + "\t"
                        + names[row] + "\t" + names[column] + "\t" + pearson[row][column] + "\t"
                        + spearman[row][column] + "\n");
            }
        }
    }
}
