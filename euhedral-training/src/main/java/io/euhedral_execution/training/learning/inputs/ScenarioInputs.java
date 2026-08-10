package io.euhedral_execution.training.learning.inputs;

import io.euhedral_execution.training.DataMerger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public record ScenarioInputs(Path scenarioResults, Path robustLeaderVectors, Path incompletePolicyVectors) {

    public ScenarioInputs {
        Objects.requireNonNull(scenarioResults);
        Objects.requireNonNull(robustLeaderVectors);
        Objects.requireNonNull(incompletePolicyVectors);
    }

    public static ScenarioInputs from(DataMerger.MergeArtifacts artifacts) {
        return new ScenarioInputs(
                artifacts.scenarioResults(), artifacts.robustLeaderVectors(), artifacts.incompleteVectors());
    }

    public void requireFiles() throws IOException {
        for (Path path : new Path[] {scenarioResults, robustLeaderVectors, incompletePolicyVectors}) {
            if (!Files.isRegularFile(path)) {
                throw new IOException("Not a regular Phase 1 input file: " + path);
            }
        }
    }
}
