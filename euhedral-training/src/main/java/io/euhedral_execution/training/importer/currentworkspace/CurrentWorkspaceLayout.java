package io.euhedral_execution.training.importer.currentworkspace;

import java.util.Comparator;
import java.util.List;

final class CurrentWorkspaceLayout {
    static final List<CurrentWorkspaceMapping> MAPPINGS = List.of(
            alternating("euhedral-training/input/merger/graviton5-32core-1.txt"),
            alternating("euhedral-training/input/merger/graviton5-32core-2.txt"),
            alternating("euhedral-training/input/merger/graviton5-32core-3.txt"),
            alternating("euhedral-training/input/merger/laptop-1.txt"),
            alternating("euhedral-training/input/merger/raw_data.txt"),
            alternating("euhedral-training/input/merger/zen4-32core-1.txt"),
            alternating("euhedral-training/input/merger/zen4-32core-2.txt"),
            alternating("euhedral-training/input/merger/zen4-32core-3.txt"),
            human("euhedral-training/input/temp/graviton5-32core-1.txt"),
            human("euhedral-training/input/temp/graviton5-32core-3.txt"),
            alternating("euhedral-training/input/temp/laptop-1.txt"),
            alternating("euhedral-training/input/temp/laptop-2.txt"),
            human("euhedral-training/input/temp/zen4-32core-1.txt"),
            human("euhedral-training/input/temp/zen4-32core-2.txt"),
            human("euhedral-training/output/results.txt"),
            new CurrentWorkspaceMapping("euhedral-training/output/temp_data",
                    CurrentWorkspaceFileShape.VECTOR_ONLY))
            .stream().sorted(Comparator.comparing(CurrentWorkspaceMapping::relativePath)).toList();

    private static CurrentWorkspaceMapping alternating(String path) {
        return new CurrentWorkspaceMapping(path,
                CurrentWorkspaceFileShape.ALTERNATING_VECTOR_MEASUREMENTS);
    }

    private static CurrentWorkspaceMapping human(String path) {
        return new CurrentWorkspaceMapping(path, CurrentWorkspaceFileShape.HUMAN_SUMMARY);
    }

    private CurrentWorkspaceLayout() {
    }
}
