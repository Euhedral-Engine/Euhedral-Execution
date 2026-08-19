package calibration.config;

/// Strategy defining how completed calibration runs are paired for comparison.
public enum ComparisonStrategy {
    /// One fixed baseline run compared against one or more candidate runs.
    BASELINE,

    /// Two run sets paired one-to-one based on matching comparison keys extracted from resolved TrialConfig.
    KEYED,

    /// Cartesian product comparison between all baseline runs and all candidate runs.
    CROSS
}
