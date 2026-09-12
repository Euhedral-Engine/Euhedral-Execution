package calibration.config;

/// Selects whether comparison run references represent whole JMH runs or each retained JMH fork.
public enum ComparisonScope {
    RUN,
    FORK
}
