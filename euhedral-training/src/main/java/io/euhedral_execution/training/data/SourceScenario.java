package io.euhedral_execution.training.data;

import java.util.Objects;

public record SourceScenario(
        String environmentId,
        int sourceCount,
        int availablePhysicalCoreCount,
        SourceRatio ratio) implements Comparable<SourceScenario> {

    public SourceScenario {
        Objects.requireNonNull(environmentId);
        Objects.requireNonNull(ratio);
        if (!environmentId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Malformed environment ID");
        }
        if (sourceCount <= 0 || availablePhysicalCoreCount <= 0
                || !ratio.equals(SourceRatio.of(sourceCount, availablePhysicalCoreCount))) {
            throw new IllegalArgumentException("Scenario counts and ratio disagree");
        }
    }

    public static SourceScenario of(String environmentId, int sourceCount, int coreCount) {
        return new SourceScenario(environmentId, sourceCount, coreCount,
                SourceRatio.of(sourceCount, coreCount));
    }

    public String canonical() {
        return "s1-" + environmentId + "-src" + sourceCount + "-core"
                + availablePhysicalCoreCount + "-r" + ratio.numerator() + "of"
                + ratio.denominator();
    }

    @Override
    public int compareTo(SourceScenario other) {
        int result = environmentId.compareTo(other.environmentId);
        if (result == 0) {
            result = Integer.compare(availablePhysicalCoreCount, other.availablePhysicalCoreCount);
        }
        if (result == 0) {
            result = Integer.compare(sourceCount, other.sourceCount);
        }
        if (result == 0) {
            result = Integer.compare(ratio.numerator(), other.ratio.numerator());
        }
        return result != 0 ? result : Integer.compare(ratio.denominator(), other.ratio.denominator());
    }

    @Override
    public String toString() {
        return canonical();
    }
}
