package io.euhedral_execution.training.learning;

import java.util.Locale;
import java.util.Objects;
import lombok.Getter;

public final class TrainingDevice {

    private static final TrainingDevice CPU = new TrainingDevice(false, -1, "cpu");

    public static TrainingDevice cpu() {
        return CPU;
    }

    public static TrainingDevice resolve(String requested) {
        String normalized =
                Objects.requireNonNull(requested, "requested").trim().toLowerCase(Locale.ROOT);

        if (normalized.isEmpty() || "auto".equals(normalized) || "cpu".equals(normalized)) {
            return CPU;
        }
        if ("gpu".equals(normalized) || "cuda".equals(normalized)) {
            return new TrainingDevice(true, 0, "gpu0");
        }
        if (normalized.startsWith("gpu")) {
            return new TrainingDevice(true, parseIndex(normalized, "gpu"), normalizedName(normalized, "gpu"));
        }
        if (normalized.startsWith("cuda")) {
            int deviceId = parseIndex(normalized, "cuda");
            return new TrainingDevice(true, deviceId, "gpu" + deviceId);
        }
        throw new IllegalArgumentException("Unsupported training device: " + requested);
    }

    private static int parseIndex(String normalized, String prefix) {
        if (normalized.equals(prefix)) {
            return 0;
        }
        String suffix;
        if (normalized.startsWith(prefix + ":")) {
            suffix = normalized.substring(prefix.length() + 1);
        } else {
            suffix = normalized.substring(prefix.length());
        }
        if (suffix.isBlank()) {
            return 0;
        }
        int value = Integer.parseInt(suffix);
        if (value < 0) {
            throw new IllegalArgumentException("GPU device id must be non-negative");
        }
        return value;
    }

    private static String normalizedName(String normalized, String prefix) {
        return "gpu" + parseIndex(normalized, prefix);
    }

    @Getter
    private final boolean gpu;

    @Getter
    private final int deviceId;

    private final String name;

    private TrainingDevice(boolean gpu, int deviceId, String name) {
        this.gpu = gpu;
        this.deviceId = deviceId;
        this.name = name;
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
