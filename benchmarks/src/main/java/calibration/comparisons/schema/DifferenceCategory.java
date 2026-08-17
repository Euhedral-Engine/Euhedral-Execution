package calibration.comparisons.schema;

/// Category of configuration difference between baseline and candidate.
public enum DifferenceCategory {
    IDENTITY,
    HARNESS,
    WORKLOAD,
    POLICY,
    OBSERVATION,
    JMH,
    JVM
}
