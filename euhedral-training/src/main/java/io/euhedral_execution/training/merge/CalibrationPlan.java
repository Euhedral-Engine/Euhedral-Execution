package io.euhedral_execution.training.merge;

import java.util.Objects;

public record CalibrationPlan(AnchorCatalog anchors, ReferenceRunCatalog references) {
    public CalibrationPlan {
        Objects.requireNonNull(anchors);
        Objects.requireNonNull(references);
        if (!anchors.anchorSetId().equals(references.anchorSetId())) {
            throw new IllegalArgumentException("Anchor set IDs disagree");
        }
    }
}
