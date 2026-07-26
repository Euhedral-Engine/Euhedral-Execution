package io.euhedral_execution.training;

import java.util.Objects;

public class Runner {

    public static void main(String[] args) throws Exception {
        if (Objects.equals(args[0], "merge-data")) {
            DataMerger.mergeQuentiles();
            return;
        }
        if(Objects.equals(args[0], "merge-vectors")) {
            DataMerger.mergeVectors();
            return;
        }
        if (Objects.equals(args[0], "train-vector-finder")) {
            new SequenceFinder(args);
            return;
        }
        if (Objects.equals(args[0], "benchmark")) {
            BenchmarkRunner.run(args);
        }
    }
}
