package io.euhedral_execution.training.learning.metadata;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import java.util.List;
import java.util.Objects;

public record MetadataProbe(PolicyId policyId, SourceScenario scenario,
                            List<String> predictionRawBits, String producingDevice) {

    public static MetadataProbe from(PolicyId policyId, ScenarioPrediction prediction,
            String device) {
        return new MetadataProbe(policyId, prediction.scenario(), List.of(
                bits(prediction.predictedQuality()), bits(prediction.ordinalStdDev()),
                bits(prediction.qualityIntervalLow()), bits(prediction.qualityIntervalHigh()),
                bits(prediction.ordinalEntropy()), bits(prediction.topDecileProbability()),
                bits(prediction.epistemicStdDev()), bits(prediction.disagreementRange())), device);
    }

    private static String bits(double value) {
        return "%016x".formatted(Double.doubleToRawLongBits(value));
    }

    public MetadataProbe {
        Objects.requireNonNull(policyId);
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(predictionRawBits);
        Objects.requireNonNull(producingDevice);
        predictionRawBits = List.copyOf(predictionRawBits);
        if (predictionRawBits.size() != 8
                || predictionRawBits.stream().anyMatch(bits -> !bits.matches("[0-9a-f]{16}"))
                || !producingDevice.matches("(?:cpu|gpu[0-9]+)")) {
            throw new IllegalArgumentException("Invalid metadata probe");
        }
        double[] prediction = predictionRawBits.stream()
                .mapToDouble(bits -> Double.longBitsToDouble(
                        Long.parseUnsignedLong(bits, 16))).toArray();
        new ScenarioPrediction(scenario, prediction[0], prediction[1], prediction[2],
                prediction[3], prediction[4], prediction[5], prediction[6], prediction[7]);
    }
}
