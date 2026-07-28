package io.euhedral_execution.training.data;

import java.util.Objects;

import org.jspecify.annotations.NonNull;

public record SourceScenario(String environmentId, int sourceCount, int availablePhysicalCoreCount,
                             SourceRatio ratio) implements Comparable<SourceScenario> {

    public SourceScenario {
        Objects.requireNonNull(environmentId);
        Objects.requireNonNull(ratio);
        if (!environmentId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Malformed environment ID");
        }
        if (sourceCount <= 0 || availablePhysicalCoreCount <= 0 || !ratio.equals(
                SourceRatio.of(sourceCount, availablePhysicalCoreCount))) {
            throw new IllegalArgumentException("Scenario counts and ratio disagree");
        }
    }

    public static SourceScenario of(String environmentId, int sourceCount, int coreCount) {
        return new SourceScenario(environmentId, sourceCount, coreCount,
                SourceRatio.of(sourceCount, coreCount));
    }

    public static SourceScenario parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Malformed scenario ID");
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "s1-([a-z0-9][a-z0-9._-]{0,63})-src([1-9][0-9]*)-core([1-9][0-9]*)"
                        + "-r([1-9][0-9]*)of([1-9][0-9]*)").matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Malformed scenario ID: " + value);
        }
        SourceScenario scenario = of(matcher.group(1), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
        if (scenario.ratio().numerator() != Integer.parseInt(matcher.group(4))
                || scenario.ratio().denominator() != Integer.parseInt(matcher.group(5))
                || !scenario.canonical().equals(value)) {
            throw new IllegalArgumentException("Scenario ID ratio mismatch");
        }
        return scenario;
    }

    public String canonical() {
        return "s1-" + environmentId + "-src" + sourceCount + "-core" + availablePhysicalCoreCount
                + "-r" + ratio.numerator() + "of" + ratio.denominator();
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
        return result != 0 ? result
                : Integer.compare(ratio.denominator(), other.ratio.denominator());
    }

    @Override
    public @NonNull String toString() {
        return canonical();
    }
}
