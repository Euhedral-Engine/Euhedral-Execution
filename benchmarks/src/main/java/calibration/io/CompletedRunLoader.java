package calibration.io;

import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.RunArtifacts;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ThroughputResult;
import calibration.config.CalibrationLifecycleMode;
import calibration.config.TrialConfig;
import calibration.infra.Constants;
import calibration.io.exceptions.ChecksumMismatchException;
import calibration.io.exceptions.MalformedArtifactException;
import calibration.io.exceptions.MissingArtifactException;
import calibration.io.exceptions.MissingAuthoritativeSummaryException;
import calibration.statistics.DecisionGrid;
import calibration.statistics.DescriptiveSummary;
import calibration.statistics.QuantileSummary;
import calibration.statistics.VectorCell;
import calibration.statistics.VectorField;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.BatchCompleteScalars;
import calibration.statistics.iteration.BatchCompleteStatistics;
import calibration.statistics.iteration.BatchProgressScalars;
import calibration.statistics.iteration.BatchProgressStatistics;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.CycleStartScalars;
import calibration.statistics.iteration.CycleStartStatistics;
import calibration.statistics.iteration.DecisionScalars;
import calibration.statistics.iteration.DecisionStatistics;
import calibration.statistics.iteration.OccupancySummary;
import calibration.statistics.iteration.RawBodyCostStatistics;
import calibration.statistics.iteration.ScalarSummary;
import calibration.statistics.iteration.TransitionAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/// Loads a completed calibration benchmark run directory into CompletedRun.
public final class CompletedRunLoader {

    private static final String[] SEGMENTS_3 = {"head", "steadyState", "combined"};
    private static final String[] SEGMENTS_2 = {"head", "steadyState"};
    private static final String[] DECISION_TYPES = {"idle", "exec"};
    private static final Pattern REPEAT_PATTERN = Pattern.compile(".*_repeat_(\\d+)$");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CompletedRunLoader() {}

    public static @NonNull CompletedRun load(@NonNull String runDirectoryPath) {
        Objects.requireNonNull(runDirectoryPath, "runDirectoryPath must not be null");
        return load(Path.of(runDirectoryPath));
    }

    public static @NonNull CompletedRun load(@NonNull Path runDirectory) {
        Objects.requireNonNull(runDirectory, "runDirectory must not be null");

        Path normalizedDir = resolveRunPath(runDirectory);
        if (!Files.exists(normalizedDir)) {
            throw new MissingArtifactException(normalizedDir, normalizedDir);
        }
        if (!Files.isDirectory(normalizedDir)) {
            throw new MalformedArtifactException(normalizedDir, normalizedDir, "Path is not a directory");
        }

        Path configPath = normalizedDir.resolve("trial_config.json");
        if (!Files.exists(configPath) && normalizedDir.getParent() != null) {
            Path parentConfig = normalizedDir.getParent().resolve("trial_config.json");
            if (Files.exists(parentConfig)) {
                configPath = parentConfig;
            }
        }
        if (!Files.exists(configPath)) {
            throw new MissingArtifactException(normalizedDir, configPath);
        }

        TrialConfig trialConfig;
        try {
            trialConfig = OBJECT_MAPPER.readValue(Files.readAllBytes(configPath), TrialConfig.class);
        } catch (Exception e) {
            throw new MalformedArtifactException(normalizedDir, configPath, "Failed to parse trial_config.json", e);
        }
        if (trialConfig == null) {
            throw new MalformedArtifactException(normalizedDir, configPath, "trial_config.json parsed to null");
        }

        Path logPath = normalizedDir.resolve(Constants.BENCHMARK_OUTPUT_LOG);
        if (!Files.exists(logPath) && normalizedDir.getParent() != null) {
            Path parentLog = normalizedDir.getParent().resolve(Constants.BENCHMARK_OUTPUT_LOG);
            if (Files.exists(parentLog)) {
                logPath = parentLog;
            }
        }
        if (!Files.exists(logPath)) {
            throw new MissingArtifactException(normalizedDir, logPath);
        }
        Integer forkIndex = extractForkIndex(normalizedDir);
        ThroughputResult throughput = JmhOutputParser.parse(normalizedDir, logPath, forkIndex);

        Path tsvDir = findTsvDir(normalizedDir);

        Path rawObsPath = tsvDir.resolve(Constants.RAW_OBSERVATION_TSV);
        Path statsPath = tsvDir.resolve(Constants.STATISTICS_TSV);
        Path occPath = tsvDir.resolve(Constants.OCCUPANCY_TSV);
        Path transPath = tsvDir.resolve(Constants.TRANSITIONS_TSV);
        Path vecPath = tsvDir.resolve(Constants.VECTOR_FIELDS_TSV);
        Path corrPath = tsvDir.resolve(Constants.CORRELATIONS_TSV);

        validateRequiredFile(normalizedDir, rawObsPath);
        validateRequiredFile(normalizedDir, statsPath);
        validateRequiredFile(normalizedDir, occPath);
        validateRequiredFile(normalizedDir, transPath);
        validateRequiredFile(normalizedDir, vecPath);
        validateRequiredFile(normalizedDir, corrPath);

        verifyChecksumIfExists(normalizedDir, rawObsPath);
        verifyChecksumIfExists(normalizedDir, statsPath);
        verifyChecksumIfExists(normalizedDir, occPath);
        verifyChecksumIfExists(normalizedDir, transPath);
        verifyChecksumIfExists(normalizedDir, vecPath);
        verifyChecksumIfExists(normalizedDir, corrPath);
        if (trialConfig.calibrationConfig().lifecycleMode() == CalibrationLifecycleMode.CONTINUOUS) {
            Path trajectoryWindows = tsvDir.resolve(Constants.TRAJECTORY_WINDOWS_TSV);
            Path trajectoryOccupancy = tsvDir.resolve(Constants.TRAJECTORY_OCCUPANCY_TSV);
            validateRequiredFile(normalizedDir, trajectoryWindows);
            validateRequiredFile(normalizedDir, trajectoryOccupancy);
            verifyChecksumIfExists(normalizedDir, trajectoryWindows);
            verifyChecksumIfExists(normalizedDir, trajectoryOccupancy);
        }

        SystemForkResult system =
                parseSystemForkResult(normalizedDir, rawObsPath, statsPath, occPath, transPath, vecPath, corrPath);

        RunIdentity identity = buildIdentity(normalizedDir, trialConfig);
        RunArtifacts artifacts = buildArtifacts(normalizedDir, tsvDir);

        return new CompletedRun(identity, trialConfig, throughput, system, List.of(), artifacts);
    }

    private static void validateRequiredFile(Path runDir, Path file) {
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new MissingArtifactException(runDir, file);
        }
    }

    private static void verifyChecksumIfExists(Path runDir, Path file) {
        Path checksumFile = file.resolveSibling(file.getFileName().toString() + ".sha256");
        if (!Files.exists(checksumFile)) {
            return;
        }

        String expectedChecksum;
        try {
            expectedChecksum =
                    Files.readString(checksumFile, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new MalformedArtifactException(runDir, checksumFile, "Failed to read checksum file", e);
        }

        String actualChecksum;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            actualChecksum = HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new MalformedArtifactException(runDir, file, "Failed to compute SHA-256 checksum", e);
        }

        if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
            throw new ChecksumMismatchException(runDir, file, expectedChecksum, actualChecksum);
        }
    }

    private static SystemForkResult parseSystemForkResult(
            Path runDir, Path rawObsPath, Path statsPath, Path occPath, Path transPath, Path vecPath, Path corrPath) {

        RawObsForkData rawData = parseRawObservations(runDir, rawObsPath);
        Map<String, ScalarSummary> statsMap = parseScalarStatistics(runDir, statsPath);
        Map<String, BranchOccupancyResult> occMap = parseOccupancy(runDir, occPath);
        Map<String, TransitionAnalysis> transMap = parseTransitions(runDir, transPath);
        Map<String, VectorField> vecMap = parseVectorFields(runDir, vecPath);
        Map<String, CorrelationResult> corrMap = parseCorrelations(runDir, corrPath);

        CycleStartScalars cycleHead = buildCycleStartScalars("head", statsMap);
        CycleStartScalars cycleSteady = buildCycleStartScalars("steadyState", statsMap);
        CycleStartScalars cycleCombined = buildCycleStartScalars("combined", statsMap);
        CorrelationResult cycleHeadCorr = getCorr(corrMap, "cycleStart.head", CycleStartStatistics.COLUMN_NAMES);
        CorrelationResult cycleSteadyCorr =
                getCorr(corrMap, "cycleStart.steadyState", CycleStartStatistics.COLUMN_NAMES);
        CorrelationResult cycleCombinedCorr =
                getCorr(corrMap, "cycleStart.combined", CycleStartStatistics.COLUMN_NAMES);

        CycleStartStatistics cycleStart = new CycleStartStatistics(
                rawData.cycleStartTotal,
                cycleHead,
                cycleSteady,
                cycleCombined,
                cycleHeadCorr,
                cycleSteadyCorr,
                cycleCombinedCorr);

        BatchProgressScalars progHead = buildBatchProgressScalars("head", statsMap);
        BatchProgressScalars progSteady = buildBatchProgressScalars("steadyState", statsMap);
        BatchProgressScalars progCombined = buildBatchProgressScalars("combined", statsMap);
        CorrelationResult progHeadCorr = getCorr(corrMap, "batchProgress.head", BatchProgressStatistics.COLUMN_NAMES);
        CorrelationResult progSteadyCorr =
                getCorr(corrMap, "batchProgress.steadyState", BatchProgressStatistics.COLUMN_NAMES);
        CorrelationResult progCombinedCorr =
                getCorr(corrMap, "batchProgress.combined", BatchProgressStatistics.COLUMN_NAMES);

        BatchProgressStatistics batchProgress = new BatchProgressStatistics(
                rawData.batchProgressTotal,
                progHead,
                progSteady,
                progCombined,
                progHeadCorr,
                progSteadyCorr,
                progCombinedCorr);

        BatchCompleteScalars compHead = buildBatchCompleteScalars("head", statsMap);
        BatchCompleteScalars compSteady = buildBatchCompleteScalars("steadyState", statsMap);
        BatchCompleteScalars compCombined = buildBatchCompleteScalars("combined", statsMap);
        CorrelationResult compHeadCorr = getCorr(corrMap, "batchComplete.head", BatchCompleteStatistics.COLUMN_NAMES);
        CorrelationResult compSteadyCorr =
                getCorr(corrMap, "batchComplete.steadyState", BatchCompleteStatistics.COLUMN_NAMES);
        CorrelationResult compCombinedCorr =
                getCorr(corrMap, "batchComplete.combined", BatchCompleteStatistics.COLUMN_NAMES);

        BatchCompleteStatistics batchComplete = new BatchCompleteStatistics(
                rawData.batchCompleteTotal,
                compHead,
                compSteady,
                compCombined,
                compHeadCorr,
                compSteadyCorr,
                compCombinedCorr);

        ScalarSummary bodyHead = statsMap.getOrDefault("rawBodyCost.head.cost", ScalarSummary.EMPTY);
        ScalarSummary bodySteady = statsMap.getOrDefault("rawBodyCost.steadyState.cost", ScalarSummary.EMPTY);
        ScalarSummary bodyCombined = statsMap.getOrDefault("rawBodyCost.combined.cost", ScalarSummary.EMPTY);

        RawBodyCostStatistics rawBodyCost =
                new RawBodyCostStatistics(rawData.rawBodyCostTotal, 0L, bodyHead, bodySteady, bodyCombined);

        DecisionStatistics idleDecisions =
                buildDecisionStatistics("idle", rawData.idleDecisionTotal, occMap, statsMap, transMap, vecMap, corrMap);

        DecisionStatistics execDecisions =
                buildDecisionStatistics("exec", rawData.execDecisionTotal, occMap, statsMap, transMap, vecMap, corrMap);

        return new SystemForkResult(
                0,
                rawData.iterationCount,
                rawData.coreCount,
                rawData.cycleStartTotal,
                rawData.batchProgressTotal,
                rawData.batchCompleteTotal,
                rawData.rawBodyCostTotal,
                rawData.idleDecisionTotal,
                rawData.execDecisionTotal,
                cycleStart,
                batchProgress,
                batchComplete,
                rawBodyCost,
                idleDecisions,
                execDecisions,
                rawData.centroidDistance);
    }

    private static final class RawObsForkData {
        long cycleStartTotal;
        long batchProgressTotal;
        long batchCompleteTotal;
        long rawBodyCostTotal;
        long idleDecisionTotal;
        long execDecisionTotal;
        double centroidDistance;
        int iterationCount;
        int coreCount;
    }

    private static RawObsForkData parseRawObservations(Path runDir, Path file) {
        RawObsForkData data = new RawObsForkData();
        int forkRowCount = 0;
        Set<Integer> iterations = new HashSet<>();
        Set<Integer> cores = new HashSet<>();

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("iteration\t")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 10) {
                    continue;
                }
                int iter = Integer.parseInt(parts[0]);
                String scope = parts[1];
                int core = Integer.parseInt(parts[2]);

                if ("FORK".equalsIgnoreCase(scope)) {
                    forkRowCount++;
                    data.cycleStartTotal = Long.parseLong(parts[3]);
                    data.batchProgressTotal = Long.parseLong(parts[4]);
                    data.batchCompleteTotal = Long.parseLong(parts[5]);
                    data.rawBodyCostTotal = Long.parseLong(parts[6]);
                    data.idleDecisionTotal = Long.parseLong(parts[7]);
                    data.execDecisionTotal = Long.parseLong(parts[8]);
                    data.centroidDistance = Double.parseDouble(parts[9]);
                } else if ("ITERATION".equalsIgnoreCase(scope)) {
                    if (iter >= 0) {
                        iterations.add(iter);
                    }
                } else if ("CORE".equalsIgnoreCase(scope)) {
                    if (core >= 0) {
                        cores.add(core);
                    }
                }
            }
        } catch (NumberFormatException e) {
            throw new MalformedArtifactException(
                    runDir, file, "Failed to parse numeric value in raw_observations.tsv", e);
        } catch (Exception e) {
            throw new MalformedArtifactException(runDir, file, "Failed to read raw_observations.tsv", e);
        }

        if (forkRowCount == 0) {
            throw new MissingAuthoritativeSummaryException(runDir, file);
        }
        if (forkRowCount > 1) {
            throw new MalformedArtifactException(
                    runDir, file, "Multiple conflicting FORK summaries found in raw_observations.tsv");
        }

        data.iterationCount = iterations.size();
        data.coreCount = cores.size();
        return data;
    }

    private static Map<String, ScalarSummary> parseScalarStatistics(Path runDir, Path file) {
        Map<String, ScalarSummary> map = new HashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("iteration\t")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 21) {
                    continue;
                }
                String scope = parts[1];
                if (!"FORK".equalsIgnoreCase(scope)) {
                    continue;
                }

                String metric = parts[3];
                String segment = parts[4];
                String variable = parts[5];

                long count = Long.parseLong(parts[6]);
                double mean = parseDouble(parts[7]);
                double stdDev = parseDouble(parts[8]);
                double variance = parseDouble(parts[9]);
                double cv = parseDouble(parts[10]);
                double min = parseDouble(parts[11]);
                double max = parseDouble(parts[12]);
                double median = parseDouble(parts[13]);
                double p25 = parseDouble(parts[14]);
                double p50 = parseDouble(parts[15]);
                double p75 = parseDouble(parts[16]);
                double p95 = parseDouble(parts[17]);
                double iqr = parseDouble(parts[18]);
                double normalizedIqr = parseDouble(parts[19]);
                double p95ToP50Ratio = parseDouble(parts[20]);

                DescriptiveSummary desc = new DescriptiveSummary(count, mean, stdDev, variance, cv, min, max, median);
                QuantileSummary quant = new QuantileSummary(p25, p50, p75, p95, iqr, normalizedIqr, p95ToP50Ratio);
                ScalarSummary summary = new ScalarSummary(desc, quant);

                map.put(metric + "." + segment + "." + variable, summary);
            }
        } catch (NumberFormatException e) {
            throw new MalformedArtifactException(runDir, file, "Failed to parse numeric value in statistics.tsv", e);
        } catch (Exception e) {
            throw new MalformedArtifactException(runDir, file, "Failed to read statistics.tsv", e);
        }

        return map;
    }

    private static Map<String, BranchOccupancyResult> parseOccupancy(Path runDir, Path file) {
        Map<String, BranchOccupancyResult> map = new HashMap<>();

        Map<String, long[][]> countsMap = new HashMap<>();
        Map<String, double[][]> probsMap = new HashMap<>();
        Map<String, double[]> summaryFieldsMap = new HashMap<>();

        for (String dec : DECISION_TYPES) {
            countsMap.put(dec, new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES]);
            probsMap.put(dec, new double[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES]);
            summaryFieldsMap.put(
                    dec,
                    new double
                            [7]); // contentionCentroid, bodyCentroid, contentionVariance, bodyVariance, cov, radSq, rad
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("iteration\t")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 15) {
                    continue;
                }
                String scope = parts[1];
                if (!"FORK".equalsIgnoreCase(scope)) {
                    continue;
                }

                String decisionType = parts[3];
                int c = Integer.parseInt(parts[4]);
                int b = Integer.parseInt(parts[5]);
                long count = Long.parseLong(parts[6]);
                double prob = parseDouble(parts[7]);

                long[][] counts = countsMap.get(decisionType);
                double[][] probs = probsMap.get(decisionType);
                double[] fields = summaryFieldsMap.get(decisionType);

                if (counts != null
                        && c >= 0
                        && c < DecisionGrid.CONTENTION_OUTCOMES
                        && b >= 0
                        && b < DecisionGrid.BODY_OUTCOMES) {
                    counts[c][b] = count;
                    probs[c][b] = prob;
                    fields[0] = parseDouble(parts[8]); // contentionCentroid
                    fields[1] = parseDouble(parts[9]); // bodyCentroid
                    fields[2] = parseDouble(parts[10]); // contentionVariance
                    fields[3] = parseDouble(parts[11]); // bodyVariance
                    fields[4] = parseDouble(parts[12]); // cov
                    fields[5] = parseDouble(parts[13]); // radSq
                    fields[6] = parseDouble(parts[14]); // rad
                }
            }
        } catch (NumberFormatException e) {
            throw new MalformedArtifactException(runDir, file, "Failed to parse numeric value in occupancy.tsv", e);
        } catch (Exception e) {
            throw new MalformedArtifactException(runDir, file, "Failed to read occupancy.tsv", e);
        }

        for (String dec : DECISION_TYPES) {
            long[][] counts = countsMap.get(dec);
            double[][] probs = probsMap.get(dec);
            double[] fields = summaryFieldsMap.get(dec);

            long totalCount = 0L;
            for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
                for (int j = 0; j < DecisionGrid.BODY_OUTCOMES; j++) {
                    totalCount += counts[i][j];
                }
            }

            OccupancySummary summary = new OccupancySummary(
                    totalCount, probs, fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6]);
            map.put(dec, new BranchOccupancyResult(counts, summary));
        }

        return map;
    }

    private static Map<String, TransitionAnalysis> parseTransitions(Path runDir, Path file) {
        Map<String, TransitionAnalysis> map = new HashMap<>();
        Map<String, long[][]> countsMap = new HashMap<>();

        for (String dec : DECISION_TYPES) {
            for (String seg : SEGMENTS_2) {
                countsMap.put(dec + "." + seg, new long[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES]);
            }
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("iteration\t")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 16) {
                    continue;
                }
                String scope = parts[1];
                if (!"FORK".equalsIgnoreCase(scope)) {
                    continue;
                }

                String dec = parts[3];
                String seg = parts[4];
                int from = Integer.parseInt(parts[5]);
                int to = Integer.parseInt(parts[8]);
                long count = Long.parseLong(parts[11]);

                long[][] matrix = countsMap.get(dec + "." + seg);
                if (matrix != null
                        && from >= 0
                        && from < DecisionGrid.TOTAL_STATES
                        && to >= 0
                        && to < DecisionGrid.TOTAL_STATES) {
                    matrix[from][to] = count;
                }
            }
        } catch (NumberFormatException e) {
            throw new MalformedArtifactException(runDir, file, "Failed to parse numeric value in transitions.tsv", e);
        } catch (Exception e) {
            throw new MalformedArtifactException(runDir, file, "Failed to read transitions.tsv", e);
        }

        for (Map.Entry<String, long[][]> entry : countsMap.entrySet()) {
            map.put(entry.getKey(), TransitionAnalysis.computeFromCounts(entry.getValue()));
        }

        return map;
    }

    private static Map<String, VectorField> parseVectorFields(Path runDir, Path file) {
        Map<String, VectorField> map = new HashMap<>();
        Map<String, VectorCell[][]> gridMap = new HashMap<>();

        for (String dec : DECISION_TYPES) {
            for (String seg : SEGMENTS_2) {
                VectorCell[][] grid = new VectorCell[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
                for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
                    for (int j = 0; j < DecisionGrid.BODY_OUTCOMES; j++) {
                        grid[i][j] = VectorCell.empty(i, j);
                    }
                }
                gridMap.put(dec + "." + seg, grid);
            }
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("iteration\t")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 11) {
                    continue;
                }
                String scope = parts[1];
                if (!"FORK".equalsIgnoreCase(scope)) {
                    continue;
                }

                String dec = parts[3];
                String seg = parts[4];
                int c = Integer.parseInt(parts[5]);
                int b = Integer.parseInt(parts[6]);
                long count = Long.parseLong(parts[7]);
                double deltaC = parseDouble(parts[8]);
                double deltaB = parseDouble(parts[9]);
                double mag = parseDouble(parts[10]);

                VectorCell[][] grid = gridMap.get(dec + "." + seg);
                if (grid != null
                        && c >= 0
                        && c < DecisionGrid.CONTENTION_OUTCOMES
                        && b >= 0
                        && b < DecisionGrid.BODY_OUTCOMES) {
                    grid[c][b] = new VectorCell(c, b, count, deltaC, deltaB, mag);
                }
            }
        } catch (NumberFormatException e) {
            throw new MalformedArtifactException(runDir, file, "Failed to parse numeric value in vector_fields.tsv", e);
        } catch (Exception e) {
            throw new MalformedArtifactException(runDir, file, "Failed to read vector_fields.tsv", e);
        }

        for (Map.Entry<String, VectorCell[][]> entry : gridMap.entrySet()) {
            map.put(entry.getKey(), new VectorField(entry.getValue()));
        }

        return map;
    }

    private static Map<String, CorrelationResult> parseCorrelations(Path runDir, Path file) {
        Map<String, CorrelationResult> map = new HashMap<>();

        Map<String, double[][]> pearsonMap = new HashMap<>();
        Map<String, double[][]> spearmanMap = new HashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("iteration\t")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 9) {
                    continue;
                }
                String scope = parts[1];
                if (!"FORK".equalsIgnoreCase(scope)) {
                    continue;
                }

                String metric = parts[3];
                String seg = parts[4];
                String key = metric + "." + seg;

                String[] cols = getColumnsForMetric(metric);
                int n = cols.length;

                double[][] pearson = pearsonMap.computeIfAbsent(key, k -> {
                    double[][] m = new double[n][n];
                    for (double[] row : m) Arrays.fill(row, Double.NaN);
                    return m;
                });
                double[][] spearman = spearmanMap.computeIfAbsent(key, k -> {
                    double[][] m = new double[n][n];
                    for (double[] row : m) Arrays.fill(row, Double.NaN);
                    return m;
                });

                String varA = parts[5];
                String varB = parts[6];
                int idxA = indexOf(cols, varA);
                int idxB = indexOf(cols, varB);

                if (idxA >= 0 && idxB >= 0 && idxA < n && idxB < n) {
                    pearson[idxA][idxB] = parseDouble(parts[7]);
                    spearman[idxA][idxB] = parseDouble(parts[8]);
                }
            }
        } catch (NumberFormatException e) {
            throw new MalformedArtifactException(runDir, file, "Failed to parse numeric value in correlations.tsv", e);
        } catch (Exception e) {
            throw new MalformedArtifactException(runDir, file, "Failed to read correlations.tsv", e);
        }

        for (String key : pearsonMap.keySet()) {
            String metric = key.substring(0, key.indexOf('.'));
            String[] cols = getColumnsForMetric(metric);
            map.put(key, new CorrelationResult(cols, pearsonMap.get(key), spearmanMap.get(key)));
        }

        return map;
    }

    private static String[] getColumnsForMetric(String metric) {
        return switch (metric) {
            case "cycleStart" -> CycleStartStatistics.COLUMN_NAMES;
            case "batchProgress" -> BatchProgressStatistics.COLUMN_NAMES;
            case "batchComplete" -> BatchCompleteStatistics.COLUMN_NAMES;
            case "idleDecisions", "execDecisions" -> DecisionStatistics.COLUMN_NAMES;
            default -> new String[0];
        };
    }

    private static int indexOf(String[] array, String target) {
        for (int i = 0; i < array.length; i++) {
            if (array[i].equalsIgnoreCase(target)) {
                return i;
            }
        }
        return -1;
    }

    private static CycleStartScalars buildCycleStartScalars(String seg, Map<String, ScalarSummary> statsMap) {
        String p = "cycleStart." + seg + ".";
        return new CycleStartScalars(
                statsMap.getOrDefault(p + "completed", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "batchSize", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "upstreamCount", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "registeredWorkers", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "productiveHandleCount", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "productiveHandleRatio", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "workerRank", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "contention", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "throughput", ScalarSummary.EMPTY));
    }

    private static BatchProgressScalars buildBatchProgressScalars(String seg, Map<String, ScalarSummary> statsMap) {
        String p = "batchProgress." + seg + ".";
        return new BatchProgressScalars(
                statsMap.getOrDefault(p + "upstreamCount", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "registeredWorkers", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "productiveHandleCount", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "productiveHandleRatio", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "workerRank", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "contention", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "avgServiceTime", ScalarSummary.EMPTY));
    }

    private static BatchCompleteScalars buildBatchCompleteScalars(String seg, Map<String, ScalarSummary> statsMap) {
        String p = "batchComplete." + seg + ".";
        return new BatchCompleteScalars(
                statsMap.getOrDefault(p + "upstreamCount", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "registeredWorkers", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "productiveHandleCount", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "productiveHandleRatio", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "workerRank", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "contention", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "avgServiceTime", ScalarSummary.EMPTY),
                statsMap.getOrDefault(p + "throughput", ScalarSummary.EMPTY));
    }

    private static DecisionStatistics buildDecisionStatistics(
            String decisionType,
            long totalObservations,
            Map<String, BranchOccupancyResult> occMap,
            Map<String, ScalarSummary> statsMap,
            Map<String, TransitionAnalysis> transMap,
            Map<String, VectorField> vecMap,
            Map<String, CorrelationResult> corrMap) {

        String metric = decisionType + "Decisions";
        BranchOccupancyResult occ = occMap.getOrDefault(decisionType, BranchOccupancyResult.EMPTY);

        String pHead = metric + ".head.";
        String pSteady = metric + ".steadyState.";
        String pComb = metric + ".combined.";

        DecisionScalars headScalars = new DecisionScalars(
                statsMap.getOrDefault(pHead + "contention", ScalarSummary.EMPTY),
                statsMap.getOrDefault(pHead + "smoothedBodyCost", ScalarSummary.EMPTY));

        DecisionScalars steadyScalars = new DecisionScalars(
                statsMap.getOrDefault(pSteady + "contention", ScalarSummary.EMPTY),
                statsMap.getOrDefault(pSteady + "smoothedBodyCost", ScalarSummary.EMPTY));

        DecisionScalars combinedScalars = new DecisionScalars(
                statsMap.getOrDefault(pComb + "contention", ScalarSummary.EMPTY),
                statsMap.getOrDefault(pComb + "smoothedBodyCost", ScalarSummary.EMPTY));

        TransitionAnalysis headTrans =
                transMap.getOrDefault(decisionType + ".head", TransitionAnalysis.compute(new int[0]));
        TransitionAnalysis steadyTrans =
                transMap.getOrDefault(decisionType + ".steadyState", TransitionAnalysis.compute(new int[0]));

        VectorField headVec = vecMap.getOrDefault(
                decisionType + ".head",
                new VectorField(new VectorCell[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES]));
        VectorField steadyVec = vecMap.getOrDefault(
                decisionType + ".steadyState",
                new VectorField(new VectorCell[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES]));

        CorrelationResult headCorr = getCorr(corrMap, metric + ".head", DecisionStatistics.COLUMN_NAMES);
        CorrelationResult steadyCorr = getCorr(corrMap, metric + ".steadyState", DecisionStatistics.COLUMN_NAMES);
        CorrelationResult combinedCorr = getCorr(corrMap, metric + ".combined", DecisionStatistics.COLUMN_NAMES);

        return new DecisionStatistics(
                totalObservations,
                occ,
                headScalars,
                steadyScalars,
                combinedScalars,
                headTrans,
                steadyTrans,
                headVec,
                steadyVec,
                headCorr,
                steadyCorr,
                combinedCorr);
    }

    private static CorrelationResult getCorr(Map<String, CorrelationResult> corrMap, String key, String[] defaultCols) {
        CorrelationResult result = corrMap.get(key);
        if (result != null) {
            return result;
        }
        return CorrelationResult.empty(defaultCols);
    }

    private static double parseDouble(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("NaN")) {
            return Double.NaN;
        }
        return Double.parseDouble(s);
    }

    private static RunIdentity buildIdentity(Path runDir, TrialConfig trialConfig) {
        String dirName = runDir.getFileName().toString();
        String trialId = trialConfig.id();
        if (trialId == null || trialId.isBlank()) {
            trialId = extractTrialIdFromDirName(dirName);
        }

        String repeatDirectoryName = dirName.startsWith("fork-") && runDir.getParent() != null
                ? runDir.getParent().getFileName().toString()
                : dirName;
        int repeatIndex = extractRepeatIndexFromDirName(repeatDirectoryName);
        Integer forkIndex = extractForkIndex(runDir);

        return new RunIdentity(
                trialId,
                trialConfig.name(),
                trialConfig.group(),
                repeatIndex,
                forkIndex,
                runDir.toAbsolutePath().normalize().toString());
    }

    private static String extractTrialIdFromDirName(String dirName) {
        int repeatIdx = dirName.indexOf("_repeat_");
        if (repeatIdx > 0) {
            return dirName.substring(0, repeatIdx);
        }
        return dirName;
    }

    private static int extractRepeatIndexFromDirName(String dirName) {
        Matcher m = REPEAT_PATTERN.matcher(dirName);
        if (m.matches()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static Integer extractForkIndex(Path runDir) {
        if (runDir.getFileName() == null || !runDir.getFileName().toString().startsWith("fork-")) {
            return null;
        }
        Path parent = runDir.getParent();
        if (parent == null) {
            return null;
        }
        List<Path> forkDirectories = findForkDirectories(parent);
        int index = forkDirectories.indexOf(runDir.toAbsolutePath().normalize());
        return index >= 0 ? index : null;
    }

    public static @NonNull List<Path> findForkDirectories(@NonNull Path runDirectory) {
        Objects.requireNonNull(runDirectory, "runDirectory must not be null");
        Path normalizedDir = resolveRunPath(runDirectory);
        if (normalizedDir.getFileName() != null
                && normalizedDir.getFileName().toString().startsWith("fork-")
                && Files.exists(normalizedDir.resolve(Constants.RAW_OBSERVATION_TSV))) {
            return List.of(normalizedDir);
        }
        try (var stream = Files.list(normalizedDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("fork-"))
                    .filter(path -> Files.exists(path.resolve(Constants.RAW_OBSERVATION_TSV)))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new MalformedArtifactException(
                    normalizedDir, normalizedDir, "Failed to discover retained JMH fork artifacts", e);
        }
    }

    public static @NonNull List<CompletedRun> loadForks(@NonNull String runDirectoryPath) {
        Objects.requireNonNull(runDirectoryPath, "runDirectoryPath must not be null");
        List<Path> forkDirectories = findForkDirectories(Path.of(runDirectoryPath));
        if (forkDirectories.isEmpty()) {
            throw new MissingArtifactException(
                    Path.of(runDirectoryPath), Path.of(runDirectoryPath).resolve("fork-*"));
        }
        return forkDirectories.stream().map(CompletedRunLoader::load).toList();
    }

    private static RunArtifacts buildArtifacts(Path runDir, Path tsvDir) {
        String root = runDir.toAbsolutePath().normalize().toString();
        return new RunArtifacts(
                root,
                resolveExistingPath(runDir, "trial_config.json"),
                resolveExistingPath(tsvDir, Constants.RAW_OBSERVATION_TSV),
                resolveExistingPath(tsvDir, Constants.RAW_OBSERVATION_CHECKSUM),
                resolveExistingPath(tsvDir, Constants.STATISTICS_TSV),
                resolveExistingPath(tsvDir, Constants.STATISTICS_CHECKSUM),
                resolveExistingPath(tsvDir, Constants.OCCUPANCY_TSV),
                resolveExistingPath(tsvDir, Constants.OCCUPANCY_CHECKSUM),
                resolveExistingPath(tsvDir, Constants.TRANSITIONS_TSV),
                resolveExistingPath(tsvDir, Constants.TRANSITIONS_CHECKSUM),
                resolveExistingPath(tsvDir, Constants.VECTOR_FIELDS_TSV),
                resolveExistingPath(tsvDir, Constants.VECTOR_FIELDS_CHECKSUM),
                resolveExistingPath(tsvDir, Constants.CORRELATIONS_TSV),
                resolveExistingPath(tsvDir, Constants.CORRELATIONS_CHECKSUM),
                resolveExistingPath(runDir, Constants.BENCHMARK_OUTPUT_LOG),
                resolveExistingPath(runDir, Constants.BENCHMARK_OUTPUT_LOG),
                resolveExistingPath(tsvDir, Constants.TRAJECTORY_WINDOWS_TSV),
                resolveExistingPath(tsvDir, Constants.TRAJECTORY_WINDOWS_CHECKSUM),
                resolveExistingPath(tsvDir, Constants.TRAJECTORY_OCCUPANCY_TSV),
                resolveExistingPath(tsvDir, Constants.TRAJECTORY_OCCUPANCY_CHECKSUM));
    }

    private static Path findTsvDir(Path normalizedDir) {
        if (Files.exists(normalizedDir.resolve(Constants.RAW_OBSERVATION_TSV))) {
            return normalizedDir;
        }
        try (var stream = Files.list(normalizedDir)) {
            List<Path> forkDirs = stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("fork-"))
                    .filter(p -> Files.exists(p.resolve(Constants.RAW_OBSERVATION_TSV)))
                    .sorted()
                    .toList();
            if (!forkDirs.isEmpty()) {
                return forkDirs.getFirst();
            }
        } catch (Exception ignored) {
        }
        return normalizedDir;
    }

    public static @NonNull List<CompletedRun> loadExperiment(@NonNull String experimentDirPath) {
        Objects.requireNonNull(experimentDirPath, "experimentDirPath must not be null");
        return loadExperiment(Path.of(experimentDirPath));
    }

    public static @NonNull List<CompletedRun> loadExperiment(@NonNull Path experimentDir) {
        Objects.requireNonNull(experimentDir, "experimentDir must not be null");

        Path normalizedDir = resolveRunPath(experimentDir);
        if (!Files.exists(normalizedDir)) {
            throw new MissingArtifactException(normalizedDir, normalizedDir);
        }
        if (!Files.isDirectory(normalizedDir)) {
            throw new MalformedArtifactException(normalizedDir, normalizedDir, "Path is not a directory");
        }

        List<CompletedRun> runs = new ArrayList<>();
        try (var stream = Files.list(normalizedDir)) {
            List<Path> subDirs = stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals("comparisons"))
                    .sorted()
                    .toList();

            for (Path subDir : subDirs) {
                Path config = subDir.resolve("trial_config.json");
                Path log = subDir.resolve(Constants.BENCHMARK_OUTPUT_LOG);
                Path tsvDir = findTsvDir(subDir);
                if (Files.exists(config)
                        || Files.exists(log)
                        || !tsvDir.equals(subDir)
                        || Files.exists(subDir.resolve(Constants.RAW_OBSERVATION_TSV))) {
                    try {
                        CompletedRun run = load(subDir);
                        runs.add(run);
                    } catch (Exception ignored) {
                        // Skip subdirectories that are not completed runs
                    }
                }
            }
        } catch (Exception e) {
            throw new MalformedArtifactException(
                    normalizedDir, normalizedDir, "Failed to list experiment directory", e);
        }

        if (runs.isEmpty()) {
            throw new IllegalArgumentException(
                    "No completed calibration runs found in experiment directory: " + normalizedDir);
        }

        return List.copyOf(runs);
    }

    public static @NonNull Path resolveRunPath(@NonNull Path path) {
        Objects.requireNonNull(path, "path must not be null");
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.exists(normalized) && hasTrialRunsOrFile(normalized)) {
            return normalized;
        }

        List<Path> candidateBases = List.of(
                Path.of(""),
                Path.of("benchmarks"),
                Path.of("benchmarks/experiments"),
                Path.of("experiments"),
                Path.of("benchmarks/src/main/presets/comparisons"),
                Path.of("src/main/presets/comparisons"),
                Path.of("benchmarks/src/main/presets/experiments"),
                Path.of("src/main/presets/experiments"));

        for (Path base : candidateBases) {
            Path candidate = base.resolve(path).toAbsolutePath().normalize();
            if (Files.exists(candidate) && hasTrialRunsOrFile(candidate)) {
                return candidate;
            }
        }

        for (Path base : candidateBases) {
            Path candidate = base.resolve(path).toAbsolutePath().normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        return normalized;
    }

    private static boolean hasTrialRunsOrFile(Path p) {
        if (!Files.exists(p)) {
            return false;
        }
        if (Files.isRegularFile(p)) {
            return true;
        }
        if (Files.exists(p.resolve("trial_config.json")) || Files.exists(p.resolve(Constants.BENCHMARK_OUTPUT_LOG))) {
            return true;
        }
        try (var stream = Files.list(p)) {
            return stream.anyMatch(sub -> Files.isDirectory(sub)
                    && !sub.getFileName().toString().equals("comparisons")
                    && (Files.exists(sub.resolve("trial_config.json"))
                            || Files.exists(sub.resolve(Constants.BENCHMARK_OUTPUT_LOG))
                            || !findTsvDir(sub).equals(sub)));
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveExistingPath(Path runDir, String relativeName) {
        Path p = runDir.resolve(relativeName);
        return Files.exists(p) ? p.toAbsolutePath().normalize().toString() : null;
    }
}
