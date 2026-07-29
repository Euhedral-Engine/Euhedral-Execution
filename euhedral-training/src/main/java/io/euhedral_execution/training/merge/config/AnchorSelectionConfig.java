package io.euhedral_execution.training.merge.config;

public record AnchorSelectionConfig(double fixedFraction, int minimumFixedAnchors,
        double maximumBootstrapNonSuccessRate, double maximumBootstrapRelativeIqr,
        boolean allowImportedBootstrap) {
    public AnchorSelectionConfig {
        if (!(fixedFraction > 0 && fixedFraction <= 1) || minimumFixedAnchors < 1
                || !Double.isFinite(maximumBootstrapNonSuccessRate)
                || !Double.isFinite(maximumBootstrapRelativeIqr)
                || maximumBootstrapNonSuccessRate < 0 || maximumBootstrapNonSuccessRate > 1
                || maximumBootstrapRelativeIqr < 0) throw new IllegalArgumentException();
    }
    public static AnchorSelectionConfig defaults() {
        return new AnchorSelectionConfig(0.02, 5, 0.10, 0.25, false);
    }
    public int targetCount(int policyBudget) {
        int target = Math.max(minimumFixedAnchors, (int) StrictMath.ceil(fixedFraction * policyBudget));
        if (policyBudget <= target) throw new IllegalArgumentException("Budget must exceed anchors");
        return target;
    }
}
