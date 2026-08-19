package calibration.comparisons;

import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Strongly typed scalar value extracted from a TrialConfig representing one component of a ComparisonKey.
public sealed interface ComparisonKeyValue extends Comparable<ComparisonKeyValue> {

    /// Returns the string representation of this key value.
    @NonNull
    String format();

    static ComparisonKeyValue of(long value) {
        return new NumberKeyValue(BigDecimal.valueOf(value), Long.toString(value));
    }

    static ComparisonKeyValue of(double value) {
        return new NumberKeyValue(BigDecimal.valueOf(value), Double.toString(value));
    }

    static ComparisonKeyValue of(BigDecimal value) {
        Objects.requireNonNull(value, "value must not be null");
        return new NumberKeyValue(value, value.toPlainString());
    }

    static ComparisonKeyValue of(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new StringKeyValue(value);
    }

    static ComparisonKeyValue of(boolean value) {
        return new BooleanKeyValue(value);
    }

    record NumberKeyValue(
            @NonNull BigDecimal number, @NonNull String rawString) implements ComparisonKeyValue {
        public NumberKeyValue {
            Objects.requireNonNull(number, "number must not be null");
            Objects.requireNonNull(rawString, "rawString must not be null");
        }

        @Override
        public String format() {
            return rawString;
        }

        @Override
        public int compareTo(@NonNull ComparisonKeyValue o) {
            if (o instanceof NumberKeyValue otherNum) {
                return this.number.compareTo(otherNum.number);
            }
            return -1; // Numbers sort before strings/booleans
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof NumberKeyValue other)) return false;
            return this.number.compareTo(other.number) == 0;
        }

        @Override
        public int hashCode() {
            return Double.hashCode(number.doubleValue());
        }

        @Override
        public String toString() {
            return format();
        }
    }

    record StringKeyValue(@NonNull String text) implements ComparisonKeyValue {
        public StringKeyValue {
            Objects.requireNonNull(text, "text must not be null");
        }

        @Override
        public String format() {
            return text;
        }

        @Override
        public int compareTo(@NonNull ComparisonKeyValue o) {
            if (o instanceof NumberKeyValue) {
                return 1;
            }
            if (o instanceof StringKeyValue otherStr) {
                return this.text.compareTo(otherStr.text);
            }
            return -1; // Strings sort before booleans
        }

        @Override
        public String toString() {
            return format();
        }
    }

    record BooleanKeyValue(boolean flag) implements ComparisonKeyValue {
        @Override
        public String format() {
            return Boolean.toString(flag);
        }

        @Override
        public int compareTo(@NonNull ComparisonKeyValue o) {
            if (o instanceof BooleanKeyValue otherBool) {
                return Boolean.compare(this.flag, otherBool.flag);
            }
            return 1; // Booleans sort after numbers and strings
        }

        @Override
        public String toString() {
            return format();
        }
    }
}
