package calibration.comparisons.schema;

import calibration.infra.Constants;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// References to output artifacts for a completed run invocation.
public record RunArtifacts(
        @NonNull String rootDirectory,
        @Nullable String trialConfigPath,
        @Nullable String rawObservationsPath,
        @Nullable String rawObservationsChecksumPath,
        @Nullable String statisticsPath,
        @Nullable String statisticsChecksumPath,
        @Nullable String occupancyPath,
        @Nullable String occupancyChecksumPath,
        @Nullable String transitionsPath,
        @Nullable String transitionsChecksumPath,
        @Nullable String vectorFieldsPath,
        @Nullable String vectorFieldsChecksumPath,
        @Nullable String correlationsPath,
        @Nullable String correlationsChecksumPath,
        @Nullable String benchmarkOutputPath,
        @Nullable String jmhResultPath,
        @Nullable String trajectoryWindowsPath,
        @Nullable String trajectoryWindowsChecksumPath,
        @Nullable String trajectoryOccupancyPath,
        @Nullable String trajectoryOccupancyChecksumPath) {

    public RunArtifacts {
        Objects.requireNonNull(rootDirectory, "rootDirectory must not be null");
    }

    /// Backwards-compatible constructor for result sets without long-horizon trajectory artifacts.
    public RunArtifacts(
            @NonNull String rootDirectory,
            @Nullable String trialConfigPath,
            @Nullable String rawObservationsPath,
            @Nullable String rawObservationsChecksumPath,
            @Nullable String statisticsPath,
            @Nullable String statisticsChecksumPath,
            @Nullable String occupancyPath,
            @Nullable String occupancyChecksumPath,
            @Nullable String transitionsPath,
            @Nullable String transitionsChecksumPath,
            @Nullable String vectorFieldsPath,
            @Nullable String vectorFieldsChecksumPath,
            @Nullable String correlationsPath,
            @Nullable String correlationsChecksumPath,
            @Nullable String benchmarkOutputPath,
            @Nullable String jmhResultPath) {
        this(
                rootDirectory,
                trialConfigPath,
                rawObservationsPath,
                rawObservationsChecksumPath,
                statisticsPath,
                statisticsChecksumPath,
                occupancyPath,
                occupancyChecksumPath,
                transitionsPath,
                transitionsChecksumPath,
                vectorFieldsPath,
                vectorFieldsChecksumPath,
                correlationsPath,
                correlationsChecksumPath,
                benchmarkOutputPath,
                jmhResultPath,
                null,
                null,
                null,
                null);
    }

    public static RunArtifacts standard(@NonNull String rootDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory must not be null");
        String sep = rootDirectory.endsWith("/") ? "" : "/";
        return new RunArtifacts(
                rootDirectory,
                rootDirectory + sep + "trial_config.json",
                rootDirectory + sep + Constants.RAW_OBSERVATION_TSV,
                rootDirectory + sep + Constants.RAW_OBSERVATION_CHECKSUM,
                rootDirectory + sep + Constants.STATISTICS_TSV,
                rootDirectory + sep + Constants.STATISTICS_CHECKSUM,
                rootDirectory + sep + Constants.OCCUPANCY_TSV,
                rootDirectory + sep + Constants.OCCUPANCY_CHECKSUM,
                rootDirectory + sep + Constants.TRANSITIONS_TSV,
                rootDirectory + sep + Constants.TRANSITIONS_CHECKSUM,
                rootDirectory + sep + Constants.VECTOR_FIELDS_TSV,
                rootDirectory + sep + Constants.VECTOR_FIELDS_CHECKSUM,
                rootDirectory + sep + Constants.CORRELATIONS_TSV,
                rootDirectory + sep + Constants.CORRELATIONS_CHECKSUM,
                rootDirectory + sep + Constants.BENCHMARK_OUTPUT_LOG,
                rootDirectory + sep + Constants.BENCHMARK_OUTPUT_LOG,
                rootDirectory + sep + Constants.TRAJECTORY_WINDOWS_TSV,
                rootDirectory + sep + Constants.TRAJECTORY_WINDOWS_CHECKSUM,
                rootDirectory + sep + Constants.TRAJECTORY_OCCUPANCY_TSV,
                rootDirectory + sep + Constants.TRAJECTORY_OCCUPANCY_CHECKSUM);
    }
}
