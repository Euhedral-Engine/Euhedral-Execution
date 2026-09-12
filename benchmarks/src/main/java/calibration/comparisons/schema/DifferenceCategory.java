package calibration.comparisons.schema;

/// Category of configuration difference between baseline and candidate.
public enum DifferenceCategory {
    IDENTITY,
    HARNESS,
    WORKLOAD,
    ACTUATOR,
    LIFECYCLE,
    POLICY,
    OBSERVATION,
    JMH,
    JVM
}
