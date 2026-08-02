package io.euhedral_execution.hardware_utils;

/// Describes what the common affinity path can apply and later undo honestly.
public enum AffinityCapability {
    /// Applies the complete requested CPU mask and restores the thread's original mask.
    EXACT,

    /// Applies only a scheduler placement preference, without promising an exact CPU.
    LOCALITY_HINT,

    /// Performs no affinity mutation; requests return `false` safely.
    UNSUPPORTED
}
