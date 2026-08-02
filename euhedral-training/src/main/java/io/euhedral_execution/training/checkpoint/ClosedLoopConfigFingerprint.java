package io.euhedral_execution.training.checkpoint;

import io.euhedral_execution.training.InitialObservationBundleResolver;
import io.euhedral_execution.training.config.ClosedLoopConfig;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.merge.data.CalibrationPlanCsv;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

public final class ClosedLoopConfigFingerprint {
    public static String sha256(ClosedLoopConfig config) throws IOException {
        StringBuilder material = new StringBuilder("closed-loop-config-v1\n");
        append(material, "trainingRunId", config.trainingRunId());
        append(material, "iterations", config.iterations());
        append(material, "candidateBudget", config.candidateBudget());
        append(material, "schedulerSeed", seed(config.schedulerSeed()));
        append(material, "initialSobolCursor", config.initialSobolCursor());
        append(material, "scenariosPerIteration", config.scenariosPerIteration());
        appendRecord(material, "budgetConfig", config.budgetConfig());
        appendRecord(material, "generationConfig", config.generationConfig());
        appendRecord(material, "benchmarkConfig", config.benchmarkConfig());
        appendRecord(material, "anchorSelectionConfig", config.anchorSelectionConfig());
        appendRecord(material, "calibrationConfig", config.calibrationConfig());
        appendRecord(material, "aggregationConfig", config.aggregationConfig());
        appendRecord(material, "trainingConfig", config.trainingConfig());
        for (SourceScenario scenario : config.requiredScenarios()) {
            append(material, "requiredScenario", scenario.canonical());
        }
        config.referenceOverrides().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry ->
                        append(material, "referenceOverride." + entry.getKey().canonical(), entry.getValue()));
        append(material, "commitSha", config.commitSha());
        append(material, "dirtyWorkingTree", config.dirtyWorkingTree());
        if (config.bootstrapPolicies().isPresent()) {
            append(
                    material,
                    "bootstrapPolicySha256",
                    ArtifactFingerprint.sha256(config.bootstrapPolicies().get()));
        } else if (config.initialCalibrationPlan().isPresent()) {
            append(
                    material,
                    "calibrationPlanSha256",
                    ArtifactFingerprint.sha256(config.initialCalibrationPlan().get()));
        } else {
            append(material, "bootstrapSourceSha256", "none");
        }
        java.util.TreeMap<String, java.nio.file.Path> bundles = new java.util.TreeMap<>();
        if (config.initialCalibrationPlan().isPresent()) {
            CalibrationPlan plan =
                    CalibrationPlanCsv.read(config.initialCalibrationPlan().get());
            for (var bundle : InitialObservationBundleResolver.resolve(config, plan)) {
                String runId =
                        ObservationBundleReader.read(bundle).run().descriptor().benchmarkRunId();
                if (bundles.put(runId, bundle) != null) {
                    throw new IllegalArgumentException("Duplicate initial benchmark run");
                }
            }
        } else {
            for (var bundle : config.initialObservationBundles()) {
                String runId =
                        ObservationBundleReader.read(bundle).run().descriptor().benchmarkRunId();
                if (bundles.put(runId, bundle) != null) {
                    throw new IllegalArgumentException("Duplicate initial benchmark run");
                }
            }
        }
        for (var entry : bundles.entrySet()) {
            append(material, "initialObservationBundleRunId", entry.getKey());
            append(material, "initialObservationBundleSha256", ArtifactFingerprint.sha256(entry.getValue()));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void appendRecord(StringBuilder material, String prefix, Object record) {
        if (record == null || !record.getClass().isRecord()) {
            throw new IllegalArgumentException("Configuration value must be a record");
        }
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                Object value = component.getAccessor().invoke(record);
                String name = prefix + "." + component.getName();
                if (value != null && value.getClass().isRecord()) {
                    appendRecord(material, name, value);
                } else if (value instanceof int[] values) {
                    for (int i = 0; i < values.length; i++) {
                        append(material, name + "[" + i + "]", values[i]);
                    }
                } else {
                    append(material, name, canonical(component.getName(), value));
                }
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    private static Object canonical(String name, Object value) {
        if (value instanceof Float number) {
            return "%08x".formatted(Float.floatToRawIntBits(number));
        }
        if (value instanceof Double number) {
            return "%016x".formatted(Double.doubleToRawLongBits(number));
        }
        if (value instanceof Long number && name.toLowerCase().contains("seed")) {
            return seed(number);
        }
        if (value instanceof Enum<?> item) {
            return item.name();
        }
        return value;
    }

    private static String seed(long value) {
        return "%016x".formatted(value);
    }

    private static void append(StringBuilder material, String name, Object value) {
        material.append(name).append('=').append(value).append('\n');
    }

    private ClosedLoopConfigFingerprint() {}
}
