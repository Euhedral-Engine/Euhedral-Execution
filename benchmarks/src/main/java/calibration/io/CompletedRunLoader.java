package calibration.io;

import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ThroughputResult;
import calibration.config.TrialConfig;
import calibration.infra.Constants;
import calibration.io.exceptions.MalformedArtifactException;
import calibration.io.exceptions.MissingArtifactException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/// Loads only the configuration and JMH output needed to compare independent forks.
public final class CompletedRunLoader {

    private static final Pattern REPEAT_PATTERN = Pattern.compile(".*_repeat_(\\d+)$");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CompletedRunLoader() {}

    public static @NonNull CompletedRun load(@NonNull String runDirectoryPath) {
        return load(Path.of(Objects.requireNonNull(runDirectoryPath, "runDirectoryPath must not be null")));
    }

    public static @NonNull CompletedRun load(@NonNull Path runDirectory) {
        Path normalized = resolveRunPath(Objects.requireNonNull(runDirectory, "runDirectory must not be null"));
        if (!Files.isDirectory(normalized)) {
            throw new MissingArtifactException(normalized, normalized);
        }

        Path configPath = findInRunOrParent(normalized, "trial_config.json");
        Path logPath = findInRunOrParent(normalized, Constants.BENCHMARK_OUTPUT_LOG);
        if (configPath == null) {
            throw new MissingArtifactException(normalized, normalized.resolve("trial_config.json"));
        }
        if (logPath == null) {
            throw new MissingArtifactException(normalized, normalized.resolve(Constants.BENCHMARK_OUTPUT_LOG));
        }

        TrialConfig config;
        try {
            config = OBJECT_MAPPER.readValue(configPath.toFile(), TrialConfig.class);
        } catch (Exception exception) {
            throw new MalformedArtifactException(
                    normalized, configPath, "Failed to parse trial_config.json", exception);
        }

        ThroughputResult throughput = JmhOutputParser.parse(normalized, logPath);
        RunIdentity identity = buildIdentity(normalized, config);
        return new CompletedRun(identity, config, throughput);
    }

    private static Path findInRunOrParent(Path run, String name) {
        Path local = run.resolve(name);
        if (Files.isRegularFile(local)) {
            return local.toAbsolutePath().normalize();
        }
        Path parent = run.getParent();
        if (parent != null) {
            Path inherited = parent.resolve(name);
            if (Files.isRegularFile(inherited)) {
                return inherited.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static RunIdentity buildIdentity(Path run, TrialConfig config) {
        String directoryName = run.getFileName().toString();
        String trialId = config.id();
        if (trialId == null || trialId.isBlank()) {
            int repeat = directoryName.indexOf("_repeat_");
            trialId = repeat > 0 ? directoryName.substring(0, repeat) : directoryName;
        }
        Matcher matcher = REPEAT_PATTERN.matcher(directoryName);
        int repeatIndex = matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
        return new RunIdentity(
                trialId,
                config.name(),
                config.group(),
                repeatIndex,
                run.toAbsolutePath().normalize().toString());
    }

    public static @NonNull List<CompletedRun> loadExperiment(@NonNull Path experimentDirectory) {
        Path normalized = resolveRunPath(experimentDirectory);
        if (!Files.isDirectory(normalized)) {
            throw new MissingArtifactException(normalized, normalized);
        }
        List<CompletedRun> runs = new ArrayList<>();
        try (var paths = Files.list(normalized)) {
            for (Path path : paths.filter(Files::isDirectory)
                    .filter(candidate -> !candidate.getFileName().toString().equals("comparisons"))
                    .sorted()
                    .toList()) {
                if (Files.isRegularFile(path.resolve("trial_config.json"))
                        && Files.isRegularFile(path.resolve(Constants.BENCHMARK_OUTPUT_LOG))) {
                    runs.add(load(path));
                }
            }
        } catch (Exception exception) {
            throw new MalformedArtifactException(
                    normalized, normalized, "Failed to list experiment directory", exception);
        }
        if (runs.isEmpty()) {
            throw new IllegalArgumentException(
                    "No completed calibration runs found in experiment directory: " + normalized);
        }
        return List.copyOf(runs);
    }

    public static @NonNull Path resolveRunPath(@NonNull Path path) {
        Path normalized = Objects.requireNonNull(path, "path must not be null")
                .toAbsolutePath()
                .normalize();
        if (Files.exists(normalized)) {
            return normalized;
        }
        List<Path> bases = List.of(
                Path.of(""),
                Path.of("benchmarks"),
                Path.of("benchmarks/experiments"),
                Path.of("experiments"),
                Path.of("benchmarks/src/main/presets/comparisons"),
                Path.of("src/main/presets/comparisons"));
        for (Path base : bases) {
            Path candidate = base.resolve(path).toAbsolutePath().normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return normalized;
    }
}
