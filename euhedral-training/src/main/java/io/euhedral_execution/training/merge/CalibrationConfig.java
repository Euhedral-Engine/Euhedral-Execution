package io.euhedral_execution.training.merge;

public record CalibrationConfig(int minimumStrongAnchors, int minimumWeakAnchors,
        double maximumStrongResidual, double maximumWeakResidual, double minimumLogSigma,
        double maximumAnchorWeightShare) {
    public CalibrationConfig {
        if (minimumStrongAnchors < minimumWeakAnchors || minimumWeakAnchors < 1
                || !Double.isFinite(maximumStrongResidual)
                || !Double.isFinite(maximumWeakResidual)
                || !Double.isFinite(minimumLogSigma)
                || !Double.isFinite(maximumAnchorWeightShare)
                || maximumStrongResidual < 0 || maximumWeakResidual < maximumStrongResidual
                || minimumLogSigma <= 0 || maximumAnchorWeightShare <= 0
                || maximumAnchorWeightShare > 1) throw new IllegalArgumentException();
    }
    public static CalibrationConfig defaults() {
        return new CalibrationConfig(5, 3, 0.05, 0.15, 0.01, 0.25);
    }
}
