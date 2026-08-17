package calibration.io;

import calibration.infra.Constants;
import calibration.statistics.Band;
import calibration.statistics.VectorCell;
import calibration.statistics.VectorField;
import calibration.statistics.iteration.BatchCompleteScalars;
import calibration.statistics.iteration.BatchCompleteStatistics;
import calibration.statistics.iteration.BatchProgressScalars;
import calibration.statistics.iteration.BatchProgressStatistics;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.CycleStartScalars;
import calibration.statistics.iteration.CycleStartStatistics;
import calibration.statistics.iteration.DecisionScalars;
import calibration.statistics.iteration.DecisionStatistics;
import calibration.statistics.iteration.IterationResult;
import calibration.statistics.iteration.OccupancySummary;
import calibration.statistics.iteration.RawBodyCostStatistics;
import calibration.statistics.iteration.ScalarSummary;
import calibration.statistics.iteration.SystemIterationResult;
import calibration.statistics.iteration.TransitionAnalysis;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/// Exporter for calibration iteration telemetry into TSV files and SHA-256 integrity checksums.
/// Persists whole-system (SYSTEM) and per-core diagnostic (CORE) statistics.
public final class TrialExport {

    private static final String[] SEGMENTS_3 = {"head", "steadyState", "combined"};
    private static final String[] SEGMENTS_2 = {"head", "steadyState"};
    private static final String[] DECISION_TYPES = {"idle", "exec"};

    private TrialExport() {}

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

    public static void exportAll(Path outputDir, List<IterationResult> results, boolean perIteration) throws Exception {
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
            for (IterationResult r : results) {
                if (r == null) {
                    continue;
                }
                Path iterDir = outputDir.resolve("iteration-" + r.iterationIndex());
                exportAll(iterDir, List.of(r), false);
            }
        }
    }

    /// Exports raw observation totals for each iteration, whole system, and physical cores to TSV.
    public static void exportRawObservationsTsv(Path outputDir, List<IterationResult> results) throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.RAW_OBSERVATION_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(SystemIterationResult.TSV_HEADER);
            for (IterationResult ir : results) {
                if (ir == null) {
                    continue;
                }
                writer.write(ir.system().toTsvRow() + "\n");
                for (CoreIterationResult cr : ir.cores()) {
                    if (cr != null) {
                        writer.write(cr.toTsvRow() + "\n");
                    }
                }
            }
        }
        writeChecksum(file);
    }

    /// Exports continuous scalar descriptive and quantile statistics to TSV.
    public static void exportStatisticsTsv(Path outputDir, List<IterationResult> results) throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.STATISTICS_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "iteration\tscope\tcore\tmetric\tsegment\tvariable\tcount\tmean\tstdDev\tvariance\tcv\tmin\tmax\tmedian\tp25\tp50\tp75\tp95\tiqr\tnormalizedIqr\tp95ToP50Ratio\n");
            for (IterationResult ir : results) {
                if (ir == null) {
                    continue;
                }
                int iter = ir.iterationIndex();
                SystemIterationResult sys = ir.system();
                writeScalarStatistics(
                        writer,
                        iter,
                        "SYSTEM",
                        -1,
                        sys.cycleStart(),
                        sys.batchProgress(),
                        sys.batchComplete(),
                        sys.rawBodyCost(),
                        sys.idleDecisions(),
                        sys.execDecisions());

                for (CoreIterationResult cr : ir.cores()) {
                    if (cr == null) {
                        continue;
                    }
                    writeScalarStatistics(
                        writer,
                        iter,
                        "CORE",
                        cr.core(),
                        cr.cycleStart(),
                        cr.batchProgress(),
                        cr.batchComplete(),
                        cr.rawBodyCost(),
                        cr.idleDecisions(),
                        cr.execDecisions());
                }
            }
        }
        writeChecksum(file);
    }

    private static void writeScalarStatistics(
            BufferedWriter writer,
            int iter,
            String scope,
            int core,
            CycleStartStatistics cycleStart,
            BatchProgressStatistics batchProgress,
            BatchCompleteStatistics batchComplete,
            RawBodyCostStatistics rawBodyCost,
            DecisionStatistics idleDecisions,
            DecisionStatistics execDecisions)
            throws Exception {
        for (String segment : SEGMENTS_3) {
            CycleStartScalars s =
                    switch (segment) {
                        case "head" -> cycleStart.head();
                        case "steadyState" -> cycleStart.steadyState();
                        default -> cycleStart.combined();
                    };
            writeScalarSummaryRow(writer, iter, scope, core, "cycleStart", segment, "completed", s.completed());
            writeScalarSummaryRow(writer, iter, scope, core, "cycleStart", segment, "batchSize", s.batchSize());
            writeScalarSummaryRow(writer, iter, scope, core, "cycleStart", segment, "upstreamCount", s.upstreamCount());
            writeScalarSummaryRow(
                    writer, iter, scope, core, "cycleStart", segment, "registeredWorkers", s.registeredWorkers());
            writeScalarSummaryRow(writer, iter, scope, core, "cycleStart", segment, "workerRank", s.workerRank());
            writeScalarSummaryRow(writer, iter, scope, core, "cycleStart", segment, "contention", s.contention());
            writeScalarSummaryRow(writer, iter, scope, core, "cycleStart", segment, "throughput", s.throughput());
        }

        for (String segment : SEGMENTS_3) {
            BatchProgressScalars s =
                    switch (segment) {
                        case "head" -> batchProgress.head();
                        case "steadyState" -> batchProgress.steadyState();
                        default -> batchProgress.combined();
                    };
            writeScalarSummaryRow(writer, iter, scope, core, "batchProgress", segment, "upstreamCount", s.upstreamCount());
            writeScalarSummaryRow(
                    writer,
                    iter,
                    scope,
                    core,
                    "batchProgress",
                    segment,
                    "registeredWorkers",
                    s.registeredWorkers());
            writeScalarSummaryRow(writer, iter, scope, core, "batchProgress", segment, "workerRank", s.workerRank());
            writeScalarSummaryRow(writer, iter, scope, core, "batchProgress", segment, "contention", s.contention());
            writeScalarSummaryRow(
                    writer, iter, scope, core, "batchProgress", segment, "avgServiceTime", s.avgServiceTime());
        }

        for (String segment : SEGMENTS_3) {
            BatchCompleteScalars s =
                    switch (segment) {
                        case "head" -> batchComplete.head();
                        case "steadyState" -> batchComplete.steadyState();
                        default -> batchComplete.combined();
                    };
            writeScalarSummaryRow(
                    writer, iter, scope, core, "batchComplete", segment, "upstreamCount", s.upstreamCount());
            writeScalarSummaryRow(
                    writer,
                    iter,
                    scope,
                    core,
                    "batchComplete",
                    segment,
                    "registeredWorkers",
                    s.registeredWorkers());
            writeScalarSummaryRow(writer, iter, scope, core, "batchComplete", segment, "workerRank", s.workerRank());
            writeScalarSummaryRow(writer, iter, scope, core, "batchComplete", segment, "contention", s.contention());
            writeScalarSummaryRow(
                    writer, iter, scope, core, "batchComplete", segment, "avgServiceTime", s.avgServiceTime());
            writeScalarSummaryRow(writer, iter, scope, core, "batchComplete", segment, "throughput", s.throughput());
        }

        for (String segment : SEGMENTS_3) {
            ScalarSummary s =
                    switch (segment) {
                        case "head" -> rawBodyCost.head();
                        case "steadyState" -> rawBodyCost.steadyState();
                        default -> rawBodyCost.combined();
                    };
            writeScalarSummaryRow(writer, iter, scope, core, "rawBodyCost", segment, "cost", s);
        }

        for (String segment : SEGMENTS_3) {
            DecisionScalars s =
                    switch (segment) {
                        case "head" -> idleDecisions.head();
                        case "steadyState" -> idleDecisions.steadyState();
                        default -> idleDecisions.combined();
                    };
            writeScalarSummaryRow(writer, iter, scope, core, "idleDecisions", segment, "contention", s.contention());
            writeScalarSummaryRow(
                    writer, iter, scope, core, "idleDecisions", segment, "smoothedBodyCost", s.smoothedBodyCost());
        }

        for (String segment : SEGMENTS_3) {
            DecisionScalars s =
                    switch (segment) {
                        case "head" -> execDecisions.head();
                        case "steadyState" -> execDecisions.steadyState();
                        default -> execDecisions.combined();
                    };
            writeScalarSummaryRow(writer, iter, scope, core, "execDecisions", segment, "contention", s.contention());
            writeScalarSummaryRow(
                    writer, iter, scope, core, "execDecisions", segment, "smoothedBodyCost", s.smoothedBodyCost());
        }
    }

    private static void writeScalarSummaryRow(
            BufferedWriter writer,
            int iteration,
            String scope,
            int core,
            String metric,
            String segment,
            String variable,
            ScalarSummary s)
            throws Exception {
        writer.write(iteration + "\t"
                + scope + "\t"
                + core + "\t"
                + metric + "\t"
                + segment + "\t"
                + variable + "\t"
                + s.toTsvRow() + "\n");
    }

    /// Exports 5x5 branch occupancy results to TSV.
    public static void exportOccupancyTsv(Path outputDir, List<IterationResult> results) throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.OCCUPANCY_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "iteration\tscope\tcore\tdecisionType\tcontentionBand\tbodyBand\tcount\tprobability\tcontentionCentroid\tbodyCentroid\tcontentionVariance\tbodyVariance\tcontentionBodyCovariance\tradiusSquared\tradius\n");
            for (IterationResult ir : results) {
                if (ir == null) {
                    continue;
                }
                int iter = ir.iterationIndex();
                writeOccupancy(writer, iter, "SYSTEM", -1, ir.system().idleOccupancy(), ir.system().execOccupancy());
                for (CoreIterationResult cr : ir.cores()) {
                    if (cr == null) {
                        continue;
                    }
                    writeOccupancy(writer, iter, "CORE", cr.core(), cr.idleOccupancy(), cr.execOccupancy());
                }
            }
        }
        writeChecksum(file);
    }

    private static void writeOccupancy(
            BufferedWriter writer,
            int iter,
            String scope,
            int core,
            BranchOccupancyResult idleOcc,
            BranchOccupancyResult execOcc)
            throws Exception {
        for (String dt : DECISION_TYPES) {
            BranchOccupancyResult occ = "idle".equals(dt) ? idleOcc : execOcc;
            OccupancySummary summary = occ.summary();
            long[][] counts = occ.exactCounts();
            double[][] probs = occ.normalizedOccupancy();
            for (int c = 0; c < Band.GRID_SIZE; c++) {
                for (int b = 0; b < Band.GRID_SIZE; b++) {
                    writer.write(iter + "\t"
                            + scope + "\t"
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

    /// Exports 25x25 state transition matrices to TSV.
    public static void exportTransitionsTsv(Path outputDir, List<IterationResult> results) throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.TRANSITIONS_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "iteration\tscope\tcore\tdecisionType\tsegment\tfromState\tfromContention\tfromBody\ttoState\ttoContention\ttoBody\tcount\tprobability\tselfTransitionRate\tdominantOutgoingState\tdominantOutgoingProbability\n");
            for (IterationResult ir : results) {
                if (ir == null) {
                    continue;
                }
                int iter = ir.iterationIndex();
                writeTransitions(
                        writer,
                        iter,
                        "SYSTEM",
                        -1,
                        ir.system().idleHeadTransitions(),
                        ir.system().idleSteadyStateTransitions(),
                        ir.system().execHeadTransitions(),
                        ir.system().execSteadyStateTransitions());

                for (CoreIterationResult cr : ir.cores()) {
                    if (cr == null) {
                        continue;
                    }
                    writeTransitions(
                            writer,
                            iter,
                            "CORE",
                            cr.core(),
                            cr.idleHeadTransitions(),
                            cr.idleSteadyStateTransitions(),
                            cr.execHeadTransitions(),
                            cr.execSteadyStateTransitions());
                }
            }
        }
        writeChecksum(file);
    }

    private static void writeTransitions(
            BufferedWriter writer,
            int iter,
            String scope,
            int core,
            TransitionAnalysis idleHead,
            TransitionAnalysis idleSteadyState,
            TransitionAnalysis execHead,
            TransitionAnalysis execSteadyState)
            throws Exception {
        for (String dt : DECISION_TYPES) {
            for (String seg : SEGMENTS_2) {
                TransitionAnalysis ta =
                        switch (dt + "_" + seg) {
                            case "idle_head" -> idleHead;
                            case "idle_steadyState" -> idleSteadyState;
                            case "exec_head" -> execHead;
                            default -> execSteadyState;
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
                                + scope + "\t"
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

    /// Exports 5x5 vector field displacement results to TSV.
    public static void exportVectorFieldsTsv(Path outputDir, List<IterationResult> results) throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.VECTOR_FIELDS_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "iteration\tscope\tcore\tdecisionType\tsegment\tcontentionBand\tbodyBand\ttransitionCount\tmeanDeltaContention\tmeanDeltaBody\tmagnitude\n");
            for (IterationResult ir : results) {
                if (ir == null) {
                    continue;
                }
                int iter = ir.iterationIndex();
                writeVectorFields(
                        writer,
                        iter,
                        "SYSTEM",
                        -1,
                        ir.system().idleHeadVectorField(),
                        ir.system().idleSteadyStateVectorField(),
                        ir.system().execHeadVectorField(),
                        ir.system().execSteadyStateVectorField());

                for (CoreIterationResult cr : ir.cores()) {
                    if (cr == null) {
                        continue;
                    }
                    writeVectorFields(
                            writer,
                            iter,
                            "CORE",
                            cr.core(),
                            cr.idleHeadVectorField(),
                            cr.idleSteadyStateVectorField(),
                            cr.execHeadVectorField(),
                            cr.execSteadyStateVectorField());
                }
            }
        }
        writeChecksum(file);
    }

    private static void writeVectorFields(
            BufferedWriter writer,
            int iter,
            String scope,
            int core,
            VectorField idleHead,
            VectorField idleSteadyState,
            VectorField execHead,
            VectorField execSteadyState)
            throws Exception {
        for (String dt : DECISION_TYPES) {
            for (String seg : SEGMENTS_2) {
                VectorField vf =
                        switch (dt + "_" + seg) {
                            case "idle_head" -> idleHead;
                            case "idle_steadyState" -> idleSteadyState;
                            case "exec_head" -> execHead;
                            default -> execSteadyState;
                        };
                VectorCell[][] grid = vf.grid();
                for (int c = 0; c < Band.GRID_SIZE; c++) {
                    for (int b = 0; b < Band.GRID_SIZE; b++) {
                        VectorCell cell = grid[c][b];
                        writer.write(iter + "\t"
                                + scope + "\t"
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

    /// Exports Pearson and Spearman correlation matrices to TSV.
    public static void exportCorrelationsTsv(Path outputDir, List<IterationResult> results) throws Exception {
        if (outputDir == null || results == null || results.isEmpty()) {
            return;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.CORRELATIONS_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("iteration\tscope\tcore\tmetric\tsegment\tvariable1\tvariable2\tpearson\tspearman\n");
            for (IterationResult ir : results) {
                if (ir == null) {
                    continue;
                }
                int iter = ir.iterationIndex();
                writeCorrelations(
                        writer,
                        iter,
                        "SYSTEM",
                        -1,
                        ir.system().cycleStart(),
                        ir.system().batchProgress(),
                        ir.system().batchComplete(),
                        ir.system().idleDecisions(),
                        ir.system().execDecisions());

                for (CoreIterationResult cr : ir.cores()) {
                    if (cr == null) {
                        continue;
                    }
                    writeCorrelations(
                            writer,
                            iter,
                            "CORE",
                            cr.core(),
                            cr.cycleStart(),
                            cr.batchProgress(),
                            cr.batchComplete(),
                            cr.idleDecisions(),
                            cr.execDecisions());
                }
            }
        }
        writeChecksum(file);
    }

    private static void writeCorrelations(
            BufferedWriter writer,
            int iter,
            String scope,
            int core,
            CycleStartStatistics cycleStart,
            BatchProgressStatistics batchProgress,
            BatchCompleteStatistics batchComplete,
            DecisionStatistics idleDecisions,
            DecisionStatistics execDecisions)
            throws Exception {
        for (String seg : SEGMENTS_3) {
            CorrelationResult cs =
                    switch (seg) {
                        case "head" -> cycleStart.headCorrelations();
                        case "steadyState" -> cycleStart.steadyStateCorrelations();
                        default -> cycleStart.combinedCorrelations();
                    };
            writeCorrelationRows(writer, iter, scope, core, "cycleStart", seg, cs);

            CorrelationResult bp =
                    switch (seg) {
                        case "head" -> batchProgress.headCorrelations();
                        case "steadyState" -> batchProgress.steadyStateCorrelations();
                        default -> batchProgress.combinedCorrelations();
                    };
            writeCorrelationRows(writer, iter, scope, core, "batchProgress", seg, bp);

            CorrelationResult bc =
                    switch (seg) {
                        case "head" -> batchComplete.headCorrelations();
                        case "steadyState" -> batchComplete.steadyStateCorrelations();
                        default -> batchComplete.combinedCorrelations();
                    };
            writeCorrelationRows(writer, iter, scope, core, "batchComplete", seg, bc);

            CorrelationResult id =
                    switch (seg) {
                        case "head" -> idleDecisions.headCorrelations();
                        case "steadyState" -> idleDecisions.steadyStateCorrelations();
                        default -> idleDecisions.combinedCorrelations();
                    };
            writeCorrelationRows(writer, iter, scope, core, "idleDecisions", seg, id);

            CorrelationResult ed =
                    switch (seg) {
                        case "head" -> execDecisions.headCorrelations();
                        case "steadyState" -> execDecisions.steadyStateCorrelations();
                        default -> execDecisions.combinedCorrelations();
                    };
            writeCorrelationRows(writer, iter, scope, core, "execDecisions", seg, ed);
        }
    }

    private static void writeCorrelationRows(
            BufferedWriter writer,
            int iteration,
            String scope,
            int core,
            String metric,
            String segment,
            CorrelationResult corr)
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
                        + scope + "\t"
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
