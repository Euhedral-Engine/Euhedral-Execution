package calibration;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Execution modes supported by CalibrationRunner.
public enum RunnerMode {
    RUN,
    COMPARE;

    /// Parses a string into a RunnerMode using case-insensitive matching.
    ///
    /// @param value the string representation of the mode
    /// @return the matched RunnerMode
    /// @throws IllegalArgumentException if value is null, blank, or unknown
    public static @NonNull RunnerMode parse(@NonNull String value) {
        Objects.requireNonNull(value, "value must not be null");
        String trimmed = value.trim();
        for (RunnerMode mode : values()) {
            if (mode.name().equalsIgnoreCase(trimmed)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown runner mode: " + value);
    }
}
