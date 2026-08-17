package calibration.io;

import calibration.infra.Constants;
import calibration.statistics.Band;
import calibration.statistics.VectorCell;
import calibration.statistics.VectorField;
import calibration.statistics.iteration.BatchCompleteScalars;
import calibration.statistics.iteration.BatchProgressScalars;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.CycleStartScalars;
import calibration.statistics.iteration.DecisionScalars;
import calibration.statistics.iteration.OccupancySummary;
import calibration.statistics.iteration.ScalarSummary;
import calibration.statistics.iteration.TransitionAnalysis;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class TrialExport {
    public static void writeChecksum(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        String hex = HexFormat.of().formatHex(digest.digest());
        Path checksumFile = file.resolveSibling(file.getFileName().toString() + ".sha256");
        Files.writeString(checksumFile, hex + "\n", StandardCharsets.UTF_8);
    }

    public static void exportAll(Path outputDir, List<List<CoreIterationResult>> results, boolean perIteration)
            throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        exportRawObservationsTsv(outputDir, results);
        exportStatisticsTsv(outputDir, results);
        exportOccupancyTsv(outputDir, results);
        exportTransitionsTsv(outputDir, results);
        exportVectorFieldsTsv(outputDir, results);
        exportCorrelationsTsv(outputDir, results);

        if (perIteration) {
            for (int i = 0; i < results.size(); i++) {
                List<CoreIterationResult> iterationResults = results.get(i);
                if (iterationResults == null || iterationResults.isEmpty()) {
                    continue;
                }
                Path iterDir = outputDir.resolve("iteration-" + i);
                exportAll(iterDir, List.of(iterationResults), false);
            }
        }
    }

    /// Exports raw observation totals for each iteration and physical core to TSV.
    public static void exportRawObservationsTsv(Path outputDir, List<List<CoreIterationResult>> results)
            throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.RAW_OBSERVATION_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(CoreIterationResult.TSV_HEADER);
            for (List<CoreIterationResult> iterationResults : results) {
                if (iterationResults == null) {
                    continue;
                }
                for (CoreIterationResult r : iterationResults) {
                    if (r == null) {
                        continue;
                    }
                    writer.write(r.toTsvRow() + "\n");
                }
            }
        }
        writeChecksum(file);
    }

    /// Exports continuous scalar descriptive and quantile statistics to TSV.
    public static void exportStatisticsTsv(Path outputDir, List<List<CoreIterationResult>> results) throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.STATISTICS_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "iteration\tcore\tmetric\tsegment\tvariable\tcount\tmean\tstdDev\tvariance\tcv\tmin\tmax\tmedian\tp25\tp50\tp75\tp95\tiqr\tnormalizedIqr\tp95ToP50Ratio\n");
            String[] segments = {"head", "steadyState", "combined"};
            for (List<CoreIterationResult> iterationResults : results) {
                if (iterationResults == null) {
                    continue;
                }
                for (CoreIterationResult r : iterationResults) {
                    if (r == null) {
                        continue;
                    }
                    int iter = r.iterationIndex();
                    int core = r.core();

                    for (String segment : segments) {
                        CycleStartScalars s =
                                switch (segment) {
                                    case "head" -> r.cycleStart().head();
                                    case "steadyState" -> r.cycleStart().steadyState();
                                    default -> r.cycleStart().combined();
                                };
                        writeScalarSummaryRow(writer, iter, core, "cycleStart", segment, "completed", s.completed());
                        writeScalarSummaryRow(writer, iter, core, "cycleStart", segment, "batchSize", s.batchSize());
                        writeScalarSummaryRow(
                                writer, iter, core, "cycleStart", segment, "upstreamCount", s.upstreamCount());
                        writeScalarSummaryRow(
                                writer, iter, core, "cycleStart", segment, "registeredWorkers", s.registeredWorkers());
                        writeScalarSummaryRow(writer, iter, core, "cycleStart", segment, "workerRank", s.workerRank());
                        writeScalarSummaryRow(writer, iter, core, "cycleStart", segment, "contention", s.contention());
                        writeScalarSummaryRow(writer, iter, core, "cycleStart", segment, "throughput", s.throughput());
                    }

                    for (String segment : segments) {
                        BatchProgressScalars s =
                                switch (segment) {
                                    case "head" -> r.batchProgress().head();
                                    case "steadyState" -> r.batchProgress().steadyState();
                                    default -> r.batchProgress().combined();
                                };
                        writeScalarSummaryRow(
                                writer, iter, core, "batchProgress", segment, "upstreamCount", s.upstreamCount());
                        writeScalarSummaryRow(
                                writer,
                                iter,
                                core,
                                "batchProgress",
                                segment,
                                "registeredWorkers",
                                s.registeredWorkers());
                        writeScalarSummaryRow(
                                writer, iter, core, "batchProgress", segment, "workerRank", s.workerRank());
                        writeScalarSummaryRow(
                                writer, iter, core, "batchProgress", segment, "contention", s.contention());
                        writeScalarSummaryRow(
                                writer, iter, core, "batchProgress", segment, "avgServiceTime", s.avgServiceTime());
                    }

                    for (String segment : segments) {
                        BatchCompleteScalars s =
                                switch (segment) {
                                    case "head" -> r.batchComplete().head();
                                    case "steadyState" -> r.batchComplete().steadyState();
                                    default -> r.batchComplete().combined();
                                };
                        writeScalarSummaryRow(
                                writer, iter, core, "batchComplete", segment, "upstreamCount", s.upstreamCount());
                        writeScalarSummaryRow(
                                writer,
                                iter,
                                core,
                                "batchComplete",
                                segment,
                                "registeredWorkers",
                                s.registeredWorkers());
                        writeScalarSummaryRow(
                                writer, iter, core, "batchComplete", segment, "workerRank", s.workerRank());
                        writeScalarSummaryRow(
                                writer, iter, core, "batchComplete", segment, "contention", s.contention());
                        writeScalarSummaryRow(
                                writer, iter, core, "batchComplete", segment, "avgServiceTime", s.avgServiceTime());
                        writeScalarSummaryRow(
                                writer, iter, core, "batchComplete", segment, "throughput", s.throughput());
                    }

                    for (String segment : segments) {
                        ScalarSummary s =
                                switch (segment) {
                                    case "head" -> r.rawBodyCost().head();
                                    case "steadyState" -> r.rawBodyCost().steadyState();
                                    default -> r.rawBodyCost().combined();
                                };
                        writeScalarSummaryRow(writer, iter, core, "rawBodyCost", segment, "cost", s);
                    }

                    for (String segment : segments) {
                        DecisionScalars s =
                                switch (segment) {
                                    case "head" -> r.idleDecisions().head();
                                    case "steadyState" -> r.idleDecisions().steadyState();
                                    default -> r.idleDecisions().combined();
                                };
                        writeScalarSummaryRow(
                                writer, iter, core, "idleDecisions", segment, "contention", s.contention());
                        writeScalarSummaryRow(
                                writer, iter, core, "idleDecisions", segment, "smoothedBodyCost", s.smoothedBodyCost());
                    }

                    for (String segment : segments) {
                        DecisionScalars s =
                                switch (segment) {
                                    case "head" -> r.execDecisions().head();
                                    case "steadyState" -> r.execDecisions().steadyState();
                                    default -> r.execDecisions().combined();
                                };
                        writeScalarSummaryRow(
                                writer, iter, core, "execDecisions", segment, "contention", s.contention());
                        writeScalarSummaryRow(
                                writer, iter, core, "execDecisions", segment, "smoothedBodyCost", s.smoothedBodyCost());
                    }
                }
            }
        }
        writeChecksum(file);
    }

    private static void writeScalarSummaryRow(
            BufferedWriter writer,
            int iteration,
            int core,
            String metric,
            String segment,
            String variable,
            ScalarSummary s)
            throws Exception {
        writer.write(iteration + "\t"
                + core + "\t"
                + metric + "\t"
                + segment + "\t"
                + variable + "\t"
                + s.toTsvRow() + "\n");
    }

    /// Exports 5x5 branch occupancy results to TSV.
    public static void exportOccupancyTsv(Path outputDir, List<List<CoreIterationResult>> results) throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.OCCUPANCY_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "iteration\tcore\tdecisionType\tcontentionBand\tbodyBand\tcount\tprobability\tcontentionCentroid\tbodyCentroid\tcontentionVariance\tbodyVariance\tcontentionBodyCovariance\tradiusSquared\tradius\n");
            String[] decisionTypes = {"idle", "exec"};
            for (List<CoreIterationResult> iterationResults : results) {
                if (iterationResults == null) {
                    continue;
                }
                for (CoreIterationResult r : iterationResults) {
                    if (r == null) {
                        continue;
                    }
                    int iter = r.iterationIndex();
                    int core = r.core();
                    for (String dt : decisionTypes) {
                        BranchOccupancyResult occ = "idle".equals(dt) ? r.idleOccupancy() : r.execOccupancy();
                        OccupancySummary summary = occ.summary();
                        long[][] counts = occ.exactCounts();
                        double[][] probs = occ.normalizedOccupancy();
                        for (int c = 0; c < Band.GRID_SIZE; c++) {
                            for (int b = 0; b < Band.GRID_SIZE; b++) {
                                writer.write(iter + "\t"
                                        + core + "\t"
                                        + dt + "\t"
                                        + c + "\t"
                                        + b + "\t"
                                        + counts[c][b] + "\t"
                                        + probs[c][b] + "\t"
                                        + summary.toTsvRow() + "\n");
                            }
                        }
                    }
                }
            }
        }
        writeChecksum(file);
    }

    /// Exports 25x25 state transition matrices to TSV.
    public static void exportTransitionsTsv(Path outputDir, List<List<CoreIterationResult>> results) throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.TRANSITIONS_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "iteration\tcore\tdecisionType\tsegment\tfromState\tfromContention\tfromBody\ttoState\ttoContention\ttoBody\tcount\tprobability\tselfTransitionRate\tdominantOutgoingState\tdominantOutgoingProbability\n");
            String[] decisionTypes = {"idle", "exec"};
            String[] segments = {"head", "steadyState"};
            for (List<CoreIterationResult> iterationResults : results) {
                if (iterationResults == null) {
                    continue;
                }
                for (CoreIterationResult r : iterationResults) {
                    if (r == null) {
                        continue;
                    }
                    int iter = r.iterationIndex();
                    int core = r.core();
                    for (String dt : decisionTypes) {
                        for (String seg : segments) {
                            TransitionAnalysis ta =
                                    switch (dt + "_" + seg) {
                                        case "idle_head" -> r.idleHeadTransitions();
                                        case "idle_steadyState" -> r.idleSteadyStateTransitions();
                                        case "exec_head" -> r.execHeadTransitions();
                                        default -> r.execSteadyStateTransitions();
                                    };
                            long[][] counts = ta.transitionCounts();
                            double[][] probs = ta.transitionProbabilities();
                            for (int from = 0; from < Band.TOTAL_STATES; from++) {
                                int fromC = TransitionAnalysis.contentionBandOf(from);
                                int fromB = TransitionAnalysis.bodyBandOf(from);
                                double selfRate = ta.selfTransitionRate(from);
                                int domState = ta.dominantOutgoingState(from);
                                double domProb = ta.dominantOutgoingProbability(from);
                                for (int to = 0; to < Band.TOTAL_STATES; to++) {
                                    int toC = TransitionAnalysis.contentionBandOf(to);
                                    int toB = TransitionAnalysis.bodyBandOf(to);
                                    long count = counts[from][to];
                                    double prob = probs[from][to];
                                    writer.write(iter + "\t"
                                            + core + "\t"
                                            + dt + "\t"
                                            + seg + "\t"
                                            + from + "\t"
                                            + fromC + "\t"
                                            + fromB + "\t"
                                            + to + "\t"
                                            + toC + "\t"
                                            + toB + "\t"
                                            + count + "\t"
                                            + prob + "\t"
                                            + selfRate + "\t"
                                            + domState + "\t"
                                            + domProb + "\n");
                                }
                            }
                        }
                    }
                }
            }
        }
        writeChecksum(file);
    }

    /// Exports 5x5 vector field displacement results to TSV.
    public static void exportVectorFieldsTsv(Path outputDir, List<List<CoreIterationResult>> results) throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.VECTOR_FIELDS_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "iteration\tcore\tdecisionType\tsegment\tcontentionBand\tbodyBand\ttransitionCount\tmeanDeltaContention\tmeanDeltaBody\tmagnitude\n");
            String[] decisionTypes = {"idle", "exec"};
            String[] segments = {"head", "steadyState"};
            for (List<CoreIterationResult> iterationResults : results) {
                if (iterationResults == null) {
                    continue;
                }
                for (CoreIterationResult r : iterationResults) {
                    if (r == null) {
                        continue;
                    }
                    int iter = r.iterationIndex();
                    int core = r.core();
                    for (String dt : decisionTypes) {
                        for (String seg : segments) {
                            VectorField vf =
                                    switch (dt + "_" + seg) {
                                        case "idle_head" -> r.idleHeadVectorField();
                                        case "idle_steadyState" -> r.idleSteadyStateVectorField();
                                        case "exec_head" -> r.execHeadVectorField();
                                        default -> r.execSteadyStateVectorField();
                                    };
                            VectorCell[][] grid = vf.grid();
                            for (int c = 0; c < Band.GRID_SIZE; c++) {
                                for (int b = 0; b < Band.GRID_SIZE; b++) {
                                    VectorCell cell = grid[c][b];
                                    writer.write(iter + "\t"
                                            + core + "\t"
                                            + dt + "\t"
                                            + seg + "\t"
                                            + c + "\t"
                                            + b + "\t"
                                            + cell.toTsvRow() + "\n");
                                }
                            }
                        }
                    }
                }
            }
        }
        writeChecksum(file);
    }

    /// Exports Pearson and Spearman correlation matrices to TSV.
    public static void exportCorrelationsTsv(Path outputDir, List<List<CoreIterationResult>> results) throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.CORRELATIONS_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("iteration\tcore\tmetric\tsegment\tvariable1\tvariable2\tpearson\tspearman\n");
            String[] segments = {"head", "steadyState", "combined"};
            for (List<CoreIterationResult> iterationResults : results) {
                if (iterationResults == null) {
                    continue;
                }
                for (CoreIterationResult r : iterationResults) {
                    if (r == null) {
                        continue;
                    }
                    int iter = r.iterationIndex();
                    int core = r.core();

                    for (String seg : segments) {
                        CorrelationResult cs =
                                switch (seg) {
                                    case "head" -> r.cycleStart().headCorrelations();
                                    case "steadyState" -> r.cycleStart().steadyStateCorrelations();
                                    default -> r.cycleStart().combinedCorrelations();
                                };
                        writeCorrelationRows(writer, iter, core, "cycleStart", seg, cs);

                        CorrelationResult bp =
                                switch (seg) {
                                    case "head" -> r.batchProgress().headCorrelations();
                                    case "steadyState" -> r.batchProgress().steadyStateCorrelations();
                                    default -> r.batchProgress().combinedCorrelations();
                                };
                        writeCorrelationRows(writer, iter, core, "batchProgress", seg, bp);

                        CorrelationResult bc =
                                switch (seg) {
                                    case "head" -> r.batchComplete().headCorrelations();
                                    case "steadyState" -> r.batchComplete().steadyStateCorrelations();
                                    default -> r.batchComplete().combinedCorrelations();
                                };
                        writeCorrelationRows(writer, iter, core, "batchComplete", seg, bc);

                        CorrelationResult id =
                                switch (seg) {
                                    case "head" -> r.idleDecisions().headCorrelations();
                                    case "steadyState" -> r.idleDecisions().steadyStateCorrelations();
                                    default -> r.idleDecisions().combinedCorrelations();
                                };
                        writeCorrelationRows(writer, iter, core, "idleDecisions", seg, id);

                        CorrelationResult ed =
                                switch (seg) {
                                    case "head" -> r.execDecisions().headCorrelations();
                                    case "steadyState" -> r.execDecisions().steadyStateCorrelations();
                                    default -> r.execDecisions().combinedCorrelations();
                                };
                        writeCorrelationRows(writer, iter, core, "execDecisions", seg, ed);
                    }
                }
            }
        }
        writeChecksum(file);
    }

    private static void writeCorrelationRows(
            BufferedWriter writer, int iteration, int core, String metric, String segment, CorrelationResult corr)
            throws Exception {
        String[] cols = corr.columnNames();
        if (cols.length == 0) {
            return;
        }
        double[][] pearson = corr.pearsonMatrix();
        double[][] spearman = corr.spearmanMatrix();
        for (int i = 0; i < cols.length; i++) {
            for (int j = 0; j < cols.length; j++) {
                double p = (i < pearson.length && j < pearson[i].length) ? pearson[i][j] : Double.NaN;
                double s = (i < spearman.length && j < spearman[i].length) ? spearman[i][j] : Double.NaN;
                writer.write(iteration + "\t"
                        + core + "\t"
                        + metric + "\t"
                        + segment + "\t"
                        + cols[i] + "\t"
                        + cols[j] + "\t"
                        + p + "\t"
                        + s + "\n");
            }
        }
    }
}
