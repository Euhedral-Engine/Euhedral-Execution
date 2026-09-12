package io.euhedral_execution.core.flow_control;

/// Selects how pull demand is converted into a target number of source-handle buckets.
public enum PullBucketDivisionMode {
    FLOOR,
    CEIL
}
