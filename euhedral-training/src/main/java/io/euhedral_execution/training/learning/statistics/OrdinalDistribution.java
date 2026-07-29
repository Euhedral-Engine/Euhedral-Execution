package io.euhedral_execution.training.learning.statistics;

public record OrdinalDistribution(double[] cumulativeProbabilities, double[] binMasses,
                                  double meanQuality, double variance, double entropy,
                                  double topDecileProbability) {

    private static boolean rate(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static boolean nonnegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    public OrdinalDistribution {
        if (cumulativeProbabilities == null || binMasses == null) {
            throw new NullPointerException();
        }
        cumulativeProbabilities = cumulativeProbabilities.clone();
        binMasses = binMasses.clone();
        if (cumulativeProbabilities.length != 9 || binMasses.length != 10
                || !rate(meanQuality) || !nonnegative(variance) || !rate(entropy)
                || !rate(topDecileProbability)) {
            throw new IllegalArgumentException("Invalid ordinal distribution");
        }
        double mass = 0;
        for (int i = 0; i < cumulativeProbabilities.length; i++) {
            if (!rate(cumulativeProbabilities[i])
                    || i > 0 && Double.compare(cumulativeProbabilities[i - 1],
                    cumulativeProbabilities[i]) < 0) {
                throw new IllegalArgumentException("Cumulative probabilities are not monotonic");
            }
        }
        for (double value : binMasses) {
            if (!nonnegative(value)) {
                throw new IllegalArgumentException("Invalid bin mass");
            }
            mass += value;
        }
        if (StrictMath.abs(mass - 1.0) > 1.0e-12) {
            throw new IllegalArgumentException("Bin masses do not sum to one");
        }
    }

    @Override
    public double[] cumulativeProbabilities() {
        return cumulativeProbabilities.clone();
    }

    @Override
    public double[] binMasses() {
        return binMasses.clone();
    }
}
