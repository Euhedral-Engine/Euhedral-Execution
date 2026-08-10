package io.euhedral_execution.training.merge.data;

import io.euhedral_execution.training.data.SourceScenario;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

public record ReferenceRunCatalog(
        int schemaVersion, String anchorSetId, SortedMap<SourceScenario, String> referenceRunIds) {

    public ReferenceRunCatalog {
        referenceRunIds = Collections.unmodifiableSortedMap(new TreeMap<>(referenceRunIds));
        if (schemaVersion != 1) {
            throw new IllegalArgumentException(String.format("The schema version %s is not supported", schemaVersion));
        }
        if (anchorSetId == null) {
            throw new IllegalArgumentException("AnchorSetId is null");
        }
        if (!anchorSetId.matches("a1-[0-9a-f]{16}")) {
            throw new IllegalArgumentException(String.format("AnchorSetId %s is malformed", anchorSetId));
        }
        if (referenceRunIds.isEmpty()) {
            throw new IllegalArgumentException("ReferenceRunIds is empty");
        }
        if (referenceRunIds.values().stream()
                        .anyMatch(runId -> runId == null || !runId.matches("[a-z0-9][a-z0-9._-]{0,95}"))
                || referenceRunIds.values().stream().distinct().count() != referenceRunIds.size()) {
            throw new IllegalArgumentException("Invalid or duplicate reference run ID");
        }
    }
}
