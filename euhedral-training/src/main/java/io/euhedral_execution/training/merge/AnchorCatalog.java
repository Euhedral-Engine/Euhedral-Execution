package io.euhedral_execution.training.merge;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.data.PolicyVector;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Comparator;

public record AnchorCatalog(int schemaVersion, String anchorSetId,
        List<PolicyVector> fixedAnchors) {
    public AnchorCatalog {
        fixedAnchors = fixedAnchors.stream().sorted(
                Comparator.comparing(PolicyVector::id)).toList();
        if (schemaVersion != 1 || anchorSetId == null
                || !anchorSetId.matches("a1-[0-9a-f]{16}") || fixedAnchors.isEmpty()) {
            throw new IllegalArgumentException("Invalid anchor catalog");
        }
        if (fixedAnchors.stream().map(PolicyVector::id).distinct().count() != fixedAnchors.size()) {
            throw new IllegalArgumentException("Duplicate fixed anchor");
        }
        if (!computedId(fixedAnchors).equals(anchorSetId)) {
            throw new IllegalArgumentException("Anchor set ID does not match policies");
        }
    }

    public static AnchorCatalog of(List<PolicyVector> fixedAnchors) {
        List<PolicyVector> sorted = fixedAnchors.stream().sorted(
                Comparator.comparing(PolicyVector::id)).toList();
        return new AnchorCatalog(1, computedId(sorted), sorted);
    }

    private static String computedId(List<PolicyVector> anchors) {
        StringBuilder input = new StringBuilder("fixed-anchor-set-v1\n");
        anchors.forEach(policy -> input.append(policy.id().canonical()).append('\n'));
        return "a1-" + String.format("%016x", HasherApi.getHash(
                input.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
