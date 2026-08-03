package io.euhedral_execution.training.learning.network_operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TensorFlowNetworkTest {

    @Test
    void testTensorFlowNetworkConstructionAndInference() {
        int featureWidth = 64;
        try (TensorFlowNetwork network = new TensorFlowNetwork(featureWidth)) {
            assertThat(network.graph()).isNotNull();
            assertThat(network.outputLayer()).isNotNull();
            assertThat(network.featureWidth()).isEqualTo(64);

            int rows = 2;
            float[] features = new float[rows * featureWidth];
            float[] destination = new float[rows * 9];

            network.predictLogits(features, rows, destination);
            assertThat(destination).hasSize(18);
        }
    }

    @Test
    void saveKeepsCheckpointFilesAfterClose(@TempDir Path temporary) throws Exception {
        int featureWidth = 64;
        Path memberDirectory = temporary.resolve("member-000");

        try (TensorFlowNetwork network = new TensorFlowNetwork(featureWidth)) {
            network.save(memberDirectory, "model");
        }

        assertThat(Files.exists(memberDirectory.resolve("model.properties"))).isTrue();
        assertThat(Files.exists(memberDirectory.resolve("checkpoint"))).isTrue();
        try (var files = Files.list(memberDirectory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .contains("checkpoint", "model.properties");
        }
        try (var files = Files.list(memberDirectory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList().stream()
                    .anyMatch(fileName -> fileName.startsWith("model.")
                            && !fileName.equals("model.properties")))
                    .isTrue();
        }

        try (TensorFlowNetwork reloaded = new TensorFlowNetwork(featureWidth)) {
            reloaded.load(memberDirectory, "model");
            float[] features = new float[featureWidth];
            float[] destination = new float[9];

            reloaded.predictLogits(features, 1, destination);
            assertThat(destination).hasSize(9);
        }
    }
}
