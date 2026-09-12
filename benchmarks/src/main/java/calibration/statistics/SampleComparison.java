package calibration.statistics;

import org.jspecify.annotations.NonNull;

/// Result of variance-aware sample comparison between samples A and B.
public record SampleComparison(
        @NonNull ComparisonOutcome outcome, double delta, double uncertainty, double practical, double margin) {}
