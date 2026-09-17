package calibration.infra;

public final class Constants {
    public static final String HARNESS_CPU_PROP = "euhedral.calibration.harnessCpu";
    public static final String TRIAL_CONFIG_PROP = "euhedral.calibration.trialConfigPath";
    public static final String TRIAL_FILTER_PROP = "euhedral.calibration.trialFilter";

    public static final String OUTPUT_DIRECTORY_PROP = "euhedral.calibration.outputDirectory";
    public static final String RETAIN_OBSERVER_DATA_PROP = "euhedral.calibration.retainObserverData";
    public static final String RETAIN_PER_FORK_RESULTS_PROP = "euhedral.calibration.retainPerForkResults";
    public static final String RETAIN_PER_ITERATION_RESULTS_PROP = "euhedral.calibration.retainPerIterationResults";

    public static final String BENCHMARK_OUTPUT_LOG = "benchmark_output.log";

    public static final String RAW_OBSERVATION_TSV = "raw_observations.tsv";

    public static final String STATISTICS_TSV = "statistics.tsv";

    public static final String CORRELATIONS_TSV = "correlations.tsv";

    public static final String COMPARISON_MANIFEST_JSON = "comparison_manifest.json";

    public static final String COMPARISON_SUMMARY_TSV = "comparison_summary.tsv";

    public static final String CONFIGURATION_DIFFERENCES_TSV = "configuration_differences.tsv";

    private Constants() {}
}
