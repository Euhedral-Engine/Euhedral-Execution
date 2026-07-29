package io.euhedral_execution.training.learning.metadata;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningRow;

public record FeatureNormalizer(String featureSchemaId, List<String> featureNames, double[] means,
                                double[] scales, boolean[] constantFeatures) {

    public static FeatureNormalizer fit(List<ScenarioLearningRow> rows, ScenarioFeatureSet set) {
        Objects.requireNonNull(rows);
        Objects.requireNonNull(set);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Training rows are empty");
        }
        TreeMap<PolicyId, PolicyVector> policies = new TreeMap<>();
        TreeSet<SourceScenario> scenarios = new TreeSet<>();
        for (ScenarioLearningRow row : rows) {
            PolicyVector previous = policies.putIfAbsent(row.policy().id(), row.policy());
            if (previous != null && !previous.bitwiseEquals(row.policy())) {
                throw new IllegalArgumentException("Policy identity collision");
            }
            scenarios.add(row.scenario());
        }
        double[] mean = new double[set.width()], scale = new double[set.width()];
        boolean[] constant = new boolean[set.width()];
        for (int f = 0; f < set.width(); f++) {
            double[] values;
            if (f < 28) {
                values = new double[policies.size()];
                int i = 0;
                for (PolicyVector p : policies.values()) {
                    values[i++] = p.weight(f);
                }
            } else {
                values = new double[scenarios.size()];
                int i = 0;
                for (SourceScenario s : scenarios) {
                    values[i++] = raw(s, f);
                }
            }
            mean[f] = sum(values) / values.length;
            double[] deviations = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                double d = values[i] - mean[f];
                deviations[i] = d * d;
            }
            double variance = sum(deviations) / values.length;
            if (!Double.isFinite(variance)) {
                throw new IllegalArgumentException("Non-finite variance");
            }
            if (variance < 0) {
                if (variance >= -Math.ulp(StrictMath.abs(mean[f]))) {
                    variance = 0;
                } else {
                    throw new IllegalArgumentException("Negative feature variance");
                }
            }
            double std = StrictMath.sqrt(variance);
            constant[f] = std < 1e-12;
            scale[f] = constant[f] ? 1.0 : std;
        }
        return new FeatureNormalizer(set.schemaId(), set.featureNames(), mean, scale, constant);
    }

    private static double raw(SourceScenario scenario, int index) {
        return switch (index) {
            case 28 -> {
                double ratio =
                        scenario.sourceCount() / (double) scenario.availablePhysicalCoreCount();
                if (Double.compare(ratio, scenario.ratio().asDouble()) != 0) {
                    throw new IllegalArgumentException("Scenario ratio disagrees with counts");
                }
                yield ratio;
            }
            case 29 -> StrictMath.log1p(scenario.sourceCount());
            case 30 -> StrictMath.log1p(scenario.availablePhysicalCoreCount());
            default -> throw new IndexOutOfBoundsException();
        };
    }

    private static double sum(double[] values) {
        double sum = 0, correction = 0;
        for (double value : values) {
            double t = sum + value;
            correction += StrictMath.abs(sum) >= StrictMath.abs(value) ? (sum - t) + value
                    : (value - t) + sum;
            sum = t;
        }
        return sum + correction;
    }

    public FeatureNormalizer {
        Objects.requireNonNull(featureSchemaId);
        Objects.requireNonNull(featureNames);
        Objects.requireNonNull(means);
        Objects.requireNonNull(scales);
        Objects.requireNonNull(constantFeatures);
        featureNames = List.copyOf(featureNames);
        means = means.clone();
        scales = scales.clone();
        constantFeatures = constantFeatures.clone();
        int n = featureNames.size();
        if (n == 0 || means.length != n || scales.length != n || constantFeatures.length != n) {
            throw new IllegalArgumentException("Normalizer lengths disagree");
        }
        ScenarioFeatureSet featureSet = Arrays.stream(ScenarioFeatureSet.values())
                .filter(value -> value.schemaId().equals(featureSchemaId)).findFirst().orElseThrow(
                        () -> new IllegalArgumentException(
                                "Unknown feature schema " + featureSchemaId));
        if (!featureNames.equals(featureSet.featureNames())) {
            throw new IllegalArgumentException("Feature names do not match schema");
        }
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(means[i]) || !Double.isFinite(scales[i]) || scales[i] <= 0) {
                throw new IllegalArgumentException("Invalid normalizer");
            }
            if (constantFeatures[i] && Double.compare(scales[i], 1.0) != 0) {
                throw new IllegalArgumentException("Constant features must use unit scale");
            }
            if (!constantFeatures[i] && scales[i] < 1.0e-12) {
                throw new IllegalArgumentException("Non-constant feature scale is too small");
            }
        }
    }

    @Override
    public double[] means() {
        return means.clone();
    }

    @Override
    public double[] scales() {
        return scales.clone();
    }

    @Override
    public boolean[] constantFeatures() {
        return constantFeatures.clone();
    }

    public void encode(PolicyVector policy, SourceScenario scenario, float[] out, int offset) {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(out);
        if (offset < 0 || offset + means.length > out.length) {
            throw new IndexOutOfBoundsException();
        }
        for (int i = 0; i < means.length; i++) {
            double value = i < 28 ? policy.weight(i) : raw(scenario, i);
            float encoded = (float) ((value - means[i]) / scales[i]);
            if (!Float.isFinite(encoded)) {
                throw new IllegalArgumentException(
                        "Non-finite feature for " + policy.id() + " and " + scenario);
            }
            out[offset + i] = encoded == 0 ? 0.0f : encoded;
        }
    }
}
