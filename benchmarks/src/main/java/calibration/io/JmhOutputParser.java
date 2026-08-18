package calibration.io;

import calibration.comparisons.schema.ThroughputResult;
import calibration.io.exceptions.MalformedArtifactException;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/// Parser for extracting JMH throughput results from raw console logs (benchmark_output.log).
/// Prioritizes auxiliary counter throughput (e.g. executions) over primary benchmark invocation throughput.
final class JmhOutputParser {

    private static final Pattern PRIMARY_ITERATION_PATTERN = Pattern.compile(
            "^Iteration\\s+\\d+(?:\\s+\\(fork\\s+\\d+\\))?:\\s+([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\\s+([a-zA-Z/_]+)");

    private static final Pattern AUX_ITERATION_PATTERN = Pattern.compile(
            "^\\s*(?:[·\\u00b7\\.\\*]\\s*)?(?:[a-zA-Z0-9_.$]+:)?executions:\\s+([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\\s+([a-zA-Z/_]+)");

    private static final Pattern SUMMARY_ROW_PATTERN = Pattern.compile(
            "^([a-zA-Z0-9_.$:]+)\\s+([a-zA-Z]+)\\s+(\\d+)\\s+([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)(?:\\s+(?:\\+/-|\\u00b1)?\\s*([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|N/A|\\?))?\\s+([a-zA-Z/_]+)$");

    private static final Pattern RESULT_HEADER_PATTERN =
            Pattern.compile("^(?:Secondary\\s+result|Result)\\s+\"([^\"]+)\":");

    private static final Pattern RESULT_VALUE_PATTERN = Pattern.compile(
            "^\\s*([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)(?:\\s*(?:\\+/-|\\u00b1)?(?:\\([0-9.]+[%]\\))?\\s*([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?))?\\s+([a-zA-Z/_]+)(?:\\s+\\[Average\\])?");

    private JmhOutputParser() {}

    public static @NonNull ThroughputResult parse(@NonNull Path runPath, @NonNull Path logPath) {
        Objects.requireNonNull(runPath, "runPath must not be null");
        Objects.requireNonNull(logPath, "logPath must not be null");

        String content;
        try {
            content = Files.readString(logPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new MalformedArtifactException(runPath, logPath, "Failed to read benchmark output log", e);
        }

        if (content.isBlank()) {
            throw new MalformedArtifactException(runPath, logPath, "Benchmark output log is empty");
        }

        List<Double> primaryIterationScores = new ArrayList<>();
        String primaryDetectedUnit = null;
        Double primarySummaryScore = null;
        double primarySummaryError = 0.0;
        String primarySummaryUnit = null;

        List<Double> auxIterationScores = new ArrayList<>();
        String auxDetectedUnit = null;
        Double auxSummaryScore = null;
        double auxSummaryError = 0.0;
        String auxSummaryUnit = null;

        boolean inWarmup = false;
        boolean inPrimaryResultBlock = false;
        boolean inAuxResultBlock = false;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                if (trimmed.startsWith("# Warmup Iteration") || trimmed.startsWith("Warmup Iteration")) {
                    inWarmup = true;
                    continue;
                }
                if (trimmed.startsWith("# Measurement:")
                        || trimmed.startsWith("Iteration ")
                        || trimmed.startsWith("# Fork:")) {
                    inWarmup = false;
                }

                if (!inWarmup) {
                    Matcher auxIterMatcher = AUX_ITERATION_PATTERN.matcher(line);
                    if (auxIterMatcher.find()) {
                        double score = Double.parseDouble(auxIterMatcher.group(1));
                        auxIterationScores.add(score);
                        if (auxDetectedUnit == null) {
                            auxDetectedUnit = auxIterMatcher.group(2);
                        }
                    } else {
                        Matcher primaryIterMatcher = PRIMARY_ITERATION_PATTERN.matcher(trimmed);
                        if (primaryIterMatcher.find()) {
                            double score = Double.parseDouble(primaryIterMatcher.group(1));
                            primaryIterationScores.add(score);
                            if (primaryDetectedUnit == null) {
                                primaryDetectedUnit = primaryIterMatcher.group(2);
                            }
                        }
                    }
                }

                Matcher resultHeaderMatcher = RESULT_HEADER_PATTERN.matcher(trimmed);
                if (resultHeaderMatcher.find()) {
                    String target = resultHeaderMatcher.group(1);
                    if (target.contains("executions") || target.contains(":")) {
                        inAuxResultBlock = true;
                        inPrimaryResultBlock = false;
                    } else {
                        inPrimaryResultBlock = true;
                        inAuxResultBlock = false;
                    }
                    continue;
                }

                if (inAuxResultBlock) {
                    Matcher resultValMatcher = RESULT_VALUE_PATTERN.matcher(line);
                    if (resultValMatcher.find()) {
                        auxSummaryScore = Double.parseDouble(resultValMatcher.group(1));
                        String errStr = resultValMatcher.group(2);
                        if (errStr != null
                                && !errStr.isBlank()
                                && !errStr.equalsIgnoreCase("N/A")
                                && !errStr.equals("?")) {
                            auxSummaryError = Double.parseDouble(errStr);
                        }
                        auxSummaryUnit = resultValMatcher.group(3);
                        inAuxResultBlock = false;
                    } else if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                        inAuxResultBlock = false;
                    }
                } else if (inPrimaryResultBlock) {
                    Matcher resultValMatcher = RESULT_VALUE_PATTERN.matcher(line);
                    if (resultValMatcher.find()) {
                        primarySummaryScore = Double.parseDouble(resultValMatcher.group(1));
                        String errStr = resultValMatcher.group(2);
                        if (errStr != null
                                && !errStr.isBlank()
                                && !errStr.equalsIgnoreCase("N/A")
                                && !errStr.equals("?")) {
                            primarySummaryError = Double.parseDouble(errStr);
                        }
                        primarySummaryUnit = resultValMatcher.group(3);
                        inPrimaryResultBlock = false;
                    } else if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                        inPrimaryResultBlock = false;
                    }
                }

                if (!trimmed.startsWith("#") && !trimmed.startsWith("Benchmark")) {
                    Matcher summaryMatcher = SUMMARY_ROW_PATTERN.matcher(trimmed);
                    if (summaryMatcher.matches()) {
                        String benchmarkName = summaryMatcher.group(1);
                        double score = Double.parseDouble(summaryMatcher.group(4));
                        double error = 0.0;
                        String errStr = summaryMatcher.group(5);
                        if (errStr != null
                                && !errStr.isBlank()
                                && !errStr.equalsIgnoreCase("N/A")
                                && !errStr.equals("?")) {
                            error = Double.parseDouble(errStr);
                        }
                        String unit = summaryMatcher.group(6);

                        if (benchmarkName.contains("executions") || benchmarkName.contains(":")) {
                            auxSummaryScore = score;
                            auxSummaryError = error;
                            auxSummaryUnit = unit;
                        } else {
                            primarySummaryScore = score;
                            primarySummaryError = error;
                            primarySummaryUnit = unit;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new MalformedArtifactException(runPath, logPath, "Error parsing JMH output log", e);
        }

        boolean hasAux = (auxSummaryScore != null && auxSummaryUnit != null && !auxSummaryUnit.isBlank())
                || (!auxIterationScores.isEmpty() && auxDetectedUnit != null);

        double finalScore;
        double finalError;
        String finalUnit;
        List<Double> iterationScores;

        if (hasAux) {
            if (auxSummaryScore != null && auxSummaryUnit != null && !auxSummaryUnit.isBlank()) {
                finalScore = auxSummaryScore;
                finalUnit = auxSummaryUnit;
                finalError = auxSummaryError;
            } else {
                double sum = 0.0;
                for (double s : auxIterationScores) {
                    sum += s;
                }
                finalScore = sum / auxIterationScores.size();
                finalUnit = auxDetectedUnit;
                finalError = 0.0;
            }
            iterationScores = auxIterationScores;
        } else if (primarySummaryScore != null && primarySummaryUnit != null && !primarySummaryUnit.isBlank()) {
            finalScore = primarySummaryScore;
            finalUnit = primarySummaryUnit;
            finalError = primarySummaryError;
            iterationScores = primaryIterationScores;
        } else if (!primaryIterationScores.isEmpty() && primaryDetectedUnit != null) {
            double sum = 0.0;
            for (double s : primaryIterationScores) {
                sum += s;
            }
            finalScore = sum / primaryIterationScores.size();
            finalUnit = primaryDetectedUnit;
            finalError = 0.0;
            iterationScores = primaryIterationScores;
        } else {
            throw new MalformedArtifactException(
                    runPath, logPath, "No JMH throughput score found in benchmark output log");
        }

        if (!Double.isFinite(finalScore)) {
            throw new MalformedArtifactException(runPath, logPath, "JMH throughput score is not finite: " + finalScore);
        }
        if (!Double.isFinite(finalError)) {
            finalError = 0.0;
        }

        List<Double> forkScores = List.of(finalScore);

        return new ThroughputResult(finalScore, finalError, finalUnit, forkScores, iterationScores);
    }
}
