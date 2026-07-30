package io.euhedral_execution.training.learning.metadata;

import java.util.Objects;

public record ProducerMetadata(String commitSha, boolean dirtyWorkingTree, String djlEngine,
                               String djlEngineVersion, String trainingDevice) {

    public ProducerMetadata {
        Objects.requireNonNull(commitSha);
        Objects.requireNonNull(djlEngine);
        Objects.requireNonNull(djlEngineVersion);
        Objects.requireNonNull(trainingDevice);
        if (!commitSha.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                || !djlEngine.equals("PyTorch") || djlEngineVersion.isBlank()
                || !trainingDevice.matches("cpu|gpu[0-9]+")) {
            throw new IllegalArgumentException("Invalid producer metadata");
        }
    }
}
