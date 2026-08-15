package calibration.config;

/// Strategy for automated candidate search generation.
public enum SearchStrategy {
    GRID,
    RANDOM,
    SOBOL,
    EXTERNAL
}
