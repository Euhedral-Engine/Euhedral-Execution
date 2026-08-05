package io.euhedral_execution.training.learning.metadata;

import java.util.Objects;

public record ProducerMetadata(
        String commitSha, boolean dirtyWorkingTree, String runtime, String runtimeVersion, String trainingDevice) {

    public ProducerMetadata {
        Objects.requireNonNull(commitSha);
        Objects.requireNonNull(runtime);
        Objects.requireNonNull(runtimeVersion);
        Objects.requireNonNull(trainingDevice);
        if (!commitSha.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                || !runtime.equals("TensorFlow")
                || runtimeVersion.isBlank()
                || !trainingDevice.matches("cpu|gpu[0-9]+")) {
            throw new IllegalArgumentException("Invalid producer metadata");
        }
    }
}
