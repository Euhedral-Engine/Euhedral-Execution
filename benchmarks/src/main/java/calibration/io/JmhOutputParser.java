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
final class JmhOutputParser {

    private static final Pattern ITERATION_PATTERN = Pattern.compile(
            "^Iteration\\s+\\d+(?:\\s+\\(fork\\s+\\d+\\))?:\\s+([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\\s+([a-zA-Z/_]+)");

    private static final Pattern SUMMARY_ROW_PATTERN = Pattern.compile(
            "^([a-zA-Z0-9_.$]+)\\s+([a-zA-Z]+)\\s+(\\d+)\\s+([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)(?:\\s+(?:\\+/-|\\u00b1)?\\s*([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|N/A|\\?))?\\s+([a-zA-Z/_]+)$");

    private static final Pattern RESULT_HEADER_PATTERN = Pattern.compile("^Result\\s+\"([^\"]+)\":");

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

        List<Double> iterationScores = new ArrayList<>();
        String detectedUnit = null;
        Double summaryScore = null;
        double summaryError = 0.0;
        String summaryUnit = null;

        boolean inWarmup = false;
        boolean inResultBlock = false;

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

                if (!inWarmup && !trimmed.contains("executions")) {
                    Matcher iterMatcher = ITERATION_PATTERN.matcher(trimmed);
                    if (iterMatcher.find()) {
                        double score = Double.parseDouble(iterMatcher.group(1));
                        iterationScores.add(score);
                        if (detectedUnit == null) {
                            detectedUnit = iterMatcher.group(2);
                        }
                    }
                }

                Matcher resultHeaderMatcher = RESULT_HEADER_PATTERN.matcher(trimmed);
                if (resultHeaderMatcher.find() && !resultHeaderMatcher.group(1).contains(":")) {
                    inResultBlock = true;
                    continue;
                }

                if (inResultBlock) {
                    Matcher resultValMatcher = RESULT_VALUE_PATTERN.matcher(line);
                    if (resultValMatcher.find()) {
                        summaryScore = Double.parseDouble(resultValMatcher.group(1));
                        String errStr = resultValMatcher.group(2);
                        if (errStr != null
                                && !errStr.isBlank()
                                && !errStr.equalsIgnoreCase("N/A")
                                && !errStr.equals("?")) {
                            summaryError = Double.parseDouble(errStr);
                        }
                        summaryUnit = resultValMatcher.group(3);
                        inResultBlock = false;
                    } else if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                        inResultBlock = false;
                    }
                }

                if (!trimmed.startsWith("#") && !trimmed.startsWith("Benchmark") && !trimmed.contains(":")) {
                    Matcher summaryMatcher = SUMMARY_ROW_PATTERN.matcher(trimmed);
                    if (summaryMatcher.matches()) {
                        summaryScore = Double.parseDouble(summaryMatcher.group(4));
                        String errStr = summaryMatcher.group(5);
                        if (errStr != null
                                && !errStr.isBlank()
                                && !errStr.equalsIgnoreCase("N/A")
                                && !errStr.equals("?")) {
                            summaryError = Double.parseDouble(errStr);
                        }
                        summaryUnit = summaryMatcher.group(6);
                    }
                }
            }
        } catch (Exception e) {
            throw new MalformedArtifactException(runPath, logPath, "Error parsing JMH output log", e);
        }

        double finalScore;
        double finalError = summaryError;
        String finalUnit;

        if (summaryScore != null && summaryUnit != null && !summaryUnit.isBlank()) {
            finalScore = summaryScore;
            finalUnit = summaryUnit;
        } else if (!iterationScores.isEmpty() && detectedUnit != null) {
            double sum = 0.0;
            for (double s : iterationScores) {
                sum += s;
            }
            finalScore = sum / iterationScores.size();
            finalUnit = detectedUnit;
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
