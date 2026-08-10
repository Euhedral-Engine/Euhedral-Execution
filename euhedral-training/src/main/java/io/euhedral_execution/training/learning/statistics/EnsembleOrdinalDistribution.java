package io.euhedral_execution.training.learning.statistics;

public record EnsembleOrdinalDistribution(
        double[] meanBinMasses,
        double predictedQuality,
        double ordinalStdDev,
        double qualityIntervalLow,
        double qualityIntervalHigh,
        double ordinalEntropy,
        double topDecileProbability,
        double epistemicStdDev,
        double disagreementRange) {

    public EnsembleOrdinalDistribution {
        if (meanBinMasses == null) {
            throw new NullPointerException();
        }
        meanBinMasses = meanBinMasses.clone();
        if (meanBinMasses.length != 10
                || !rate(predictedQuality)
                || !nonnegative(ordinalStdDev)
                || !rate(qualityIntervalLow)
                || !rate(qualityIntervalHigh)
                || qualityIntervalLow > qualityIntervalHigh
                || !rate(ordinalEntropy)
                || !rate(topDecileProbability)
                || !nonnegative(epistemicStdDev)
                || !nonnegative(disagreementRange)) {
            throw new IllegalArgumentException("Invalid ensemble ordinal distribution");
        }
        double total = 0;
        for (double mass : meanBinMasses) {
            if (!nonnegative(mass)) {
                throw new IllegalArgumentException("Invalid bin mass");
            }
            total += mass;
        }
        if (StrictMath.abs(total - 1.0) > 1.0e-12) {
            throw new IllegalArgumentException("Bin masses do not sum to one");
        }
    }

    private static boolean rate(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static boolean nonnegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    @Override
    public double[] meanBinMasses() {
        return meanBinMasses.clone();
    }
}
