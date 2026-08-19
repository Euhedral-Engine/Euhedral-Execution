package calibration.comparisons;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

/// Strongly typed, immutable, ordered tuple of comparison key values extracted from a TrialConfig.
public record ComparisonKey(@NonNull List<ComparisonKeyValue> values) implements Comparable<ComparisonKey> {

    public ComparisonKey {
        Objects.requireNonNull(values, "values must not be null");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("ComparisonKey must contain at least one value");
        }
        for (ComparisonKeyValue v : values) {
            Objects.requireNonNull(v, "ComparisonKeyValue element must not be null");
        }
        values = List.copyOf(values);
    }

    public static ComparisonKey of(@NonNull ComparisonKeyValue value) {
        return new ComparisonKey(List.of(value));
    }

    public static ComparisonKey of(@NonNull List<ComparisonKeyValue> values) {
        return new ComparisonKey(values);
    }

    /// Formats the key into a deterministic string representation.
    /// Single keys return the raw scalar format (e.g. "0", "24", "direct").
    /// Compound keys return bracketed comma-separated values (e.g. "[24, 2]").
    public @NonNull String format() {
        if (values.size() == 1) {
            return values.getFirst().format();
        }
        return "[" + values.stream().map(ComparisonKeyValue::format).collect(Collectors.joining(", ")) + "]";
    }

    @Override
    public int compareTo(@NonNull ComparisonKey o) {
        int minLen = Math.min(this.values.size(), o.values.size());
        for (int i = 0; i < minLen; i++) {
            int cmp = this.values.get(i).compareTo(o.values.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(this.values.size(), o.values.size());
    }

    @Override
    public String toString() {
        return format();
    }
}
