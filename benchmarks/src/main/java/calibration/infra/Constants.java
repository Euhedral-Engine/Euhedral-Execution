package calibration.infra;

public final class Constants {
    public static final String REPEAT_INDEX_PROP = "euhedral.calibration.repeatIndex";
    public static final String TRIAL_CONFIG_PROP = "euhedral.calibration.trialConfigPath";
    public static final String TRIAL_ID_PROP = "euhedral.calibration.trialId";
    public static final String TRIAL_INDEX_PROP = "euhedral.calibration.trialIndex";
    public static final String TRIAL_NAME_PROP = "euhedral.calibration.trialName";

    public static final String OUTPUT_DIRECTORY_PROP = "euhedral.calibration.outputDirectory";
    public static final String RETAIN_OBSERVER_DATA_PROP = "euhedral.calibration.retainObserverData";
    public static final String RETAIN_OBSERVER_PROP = RETAIN_OBSERVER_DATA_PROP;
    public static final String RETAIN_PER_FORK_RESULTS_PROP = "euhedral.calibration.retainPerForkResults";
    public static final String RETAIN_PER_FORK_PROP = RETAIN_PER_FORK_RESULTS_PROP;
    public static final String RETAIN_PER_ITERATION_RESULTS_PROP = "euhedral.calibration.retainPerIterationResults";
    public static final String RETAIN_PER_ITERATION_PROP = RETAIN_PER_ITERATION_RESULTS_PROP;

    public static final String BENCHMARK_OUTPUT_LOG = "benchmark_output.log";
    public static final String RAW_BENCHMARK_OUTPUT_LOG = BENCHMARK_OUTPUT_LOG;
    public static final String RAW_BENCHMARK_OUTPUT = BENCHMARK_OUTPUT_LOG;

    public static final String RAW_OBSERVATION_TSV = "raw_observations.tsv";
    public static final String RAW_OBSERVATIONS_TSV = RAW_OBSERVATION_TSV;
    public static final String RAW_OBSERVATION_CHECKSUM = "raw_observations.tsv.sha256";
    public static final String RAW_OBSERVATIONS_CHECKSUM = RAW_OBSERVATION_CHECKSUM;

    public static final String STATISTICS_TSV = "statistics.tsv";
    public static final String STATISTICS_CHECKSUM = "statistics.tsv.sha256";

    public static final String OCCUPANCY_TSV = "occupancy.tsv";
    public static final String OCCUPANCY_CHECKSUM = "occupancy.tsv.sha256";

    public static final String TRANSITIONS_TSV = "transitions.tsv";
    public static final String TRANSITIONS_CHECKSUM = "transitions.tsv.sha256";

    public static final String VECTOR_FIELDS_TSV = "vector_fields.tsv";
    public static final String VECTOR_FIELDS_CHECKSUM = "vector_fields.tsv.sha256";

    public static final String CORRELATIONS_TSV = "correlations.tsv";
    public static final String CORRELATIONS_CHECKSUM = "correlations.tsv.sha256";

    public static final String CONTENTION_STALENESS_TSV = "contention_staleness.tsv";
    public static final String CONTENTION_STALENESS_CHECKSUM = "contention_staleness.tsv.sha256";

    public static final String COMPARISON_MANIFEST_JSON = "comparison_manifest.json";
    public static final String COMPARISON_MANIFEST_CHECKSUM = "comparison_manifest.json.sha256";

    public static final String COMPARISON_SUMMARY_TSV = "comparison_summary.tsv";
    public static final String COMPARISON_SUMMARY_CHECKSUM = "comparison_summary.tsv.sha256";

    public static final String CONFIGURATION_DIFFERENCES_TSV = "configuration_differences.tsv";
    public static final String CONFIGURATION_DIFFERENCES_CHECKSUM = "configuration_differences.tsv.sha256";

    public static final String SCALAR_COMPARISONS_TSV = "scalar_comparisons.tsv";
    public static final String SCALAR_COMPARISONS_CHECKSUM = "scalar_comparisons.tsv.sha256";

    public static final String OCCUPANCY_COMPARISONS_TSV = "occupancy_comparisons.tsv";
    public static final String OCCUPANCY_COMPARISONS_CHECKSUM = "occupancy_comparisons.tsv.sha256";

    public static final String TRANSITION_COMPARISONS_TSV = "transition_comparisons.tsv";
    public static final String TRANSITION_COMPARISONS_CHECKSUM = "transition_comparisons.tsv.sha256";

    public static final String VECTOR_FIELD_COMPARISONS_TSV = "vector_field_comparisons.tsv";
    public static final String VECTOR_FIELD_COMPARISONS_CHECKSUM = "vector_field_comparisons.tsv.sha256";

    public static final String CORRELATION_COMPARISONS_TSV = "correlation_comparisons.tsv";
    public static final String CORRELATION_COMPARISONS_CHECKSUM = "correlation_comparisons.tsv.sha256";
    public static final String STATE_COMPARABILITY_TSV = "state_comparability.tsv";
    public static final String STATE_COMPARABILITY_CHECKSUM = "state_comparability.tsv.sha256";

    private Constants() {}
}
