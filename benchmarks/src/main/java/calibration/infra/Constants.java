package calibration.infra;

public final class Constants {
    public static final String CPU_SET_PROP = "euhedral.calibration.cpuSet";
    public static final String REPEAT_INDEX_PROP = "euhedral.calibration.repeatIndex";
    public static final String TRIAL_CONFIG_PROP = "euhedral.calibration.trialConfigPath";
    public static final String TRIAL_ID_PROP = "euhedral.calibration.trialId";
    public static final String TRIAL_INDEX_PROP = "euhedral.calibration.trialIndex";
    public static final String TRIAL_NAME_PROP = "euhedral.calibration.trialName";

    public static final String OUTPUT_DIRECTORY_PROP = "euhedral.calibration.outputDirectory";

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

    private Constants() {}
}
