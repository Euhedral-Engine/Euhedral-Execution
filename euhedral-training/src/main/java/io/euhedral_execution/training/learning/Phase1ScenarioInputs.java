package io.euhedral_execution.training.learning;
import io.euhedral_execution.training.DataMerger;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;
public record Phase1ScenarioInputs(Path scenarioResults, Path robustLeaderVectors,
        Path incompletePolicyVectors) {
    public Phase1ScenarioInputs {
        Objects.requireNonNull(scenarioResults); Objects.requireNonNull(robustLeaderVectors);
        Objects.requireNonNull(incompletePolicyVectors);
    }
    public static Phase1ScenarioInputs from(DataMerger.MergeArtifacts artifacts) {
        return new Phase1ScenarioInputs(artifacts.scenarioResults(), artifacts.robustLeaderVectors(),
                artifacts.incompleteVectors());
    }
    void requireFiles() throws IOException {
        for (Path path : new Path[]{scenarioResults, robustLeaderVectors, incompletePolicyVectors})
            if (!Files.isRegularFile(path)) {
                throw new IOException("Not a regular Phase 1 input file: " + path);
            }
    }
}
