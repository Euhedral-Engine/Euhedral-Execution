package io.euhedral_execution.training.learning.network_operations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
