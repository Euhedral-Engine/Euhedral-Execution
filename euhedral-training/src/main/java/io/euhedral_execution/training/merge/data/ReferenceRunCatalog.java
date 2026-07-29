package io.euhedral_execution.training.merge.data;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

import io.euhedral_execution.training.data.SourceScenario;

public record ReferenceRunCatalog(int schemaVersion, String anchorSetId,
                                  SortedMap<SourceScenario, String> referenceRunIds) {

    public ReferenceRunCatalog {
        referenceRunIds = Collections.unmodifiableSortedMap(new TreeMap<>(referenceRunIds));
        if (schemaVersion != 1 || anchorSetId == null || !anchorSetId.matches("a1-[0-9a-f]{16}")
                || referenceRunIds.isEmpty()) {
            throw new IllegalArgumentException("Invalid reference catalog");
        }
        if (referenceRunIds.values().stream()
                .anyMatch(runId -> runId == null || !runId.matches("[a-z0-9][a-z0-9._-]{0,95}"))
                || referenceRunIds.values().stream().distinct().count() != referenceRunIds.size()) {
            throw new IllegalArgumentException("Invalid or duplicate reference run ID");
        }
    }
}
