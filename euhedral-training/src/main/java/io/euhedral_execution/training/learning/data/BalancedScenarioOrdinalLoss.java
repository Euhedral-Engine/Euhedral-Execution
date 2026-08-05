package io.euhedral_execution.training.learning.data;

import io.euhedral_execution.training.learning.InsufficientScenarioLearningDataException;

public final class BalancedScenarioOrdinalLoss {

    public static ClassBalance fit(ScenarioLearningMatrix matrix) {
        int rows = matrix.rows();
        if (rows < 2) {
            throw new InsufficientScenarioLearningDataException("Class balancing requires at least two fitting rows");
        }
        float[] labels = matrix.ordinalLabels();
        float[] rowWeights = matrix.rowWeights();
        double totalWeight = compensated(rowWeights);
        if (!Double.isFinite(totalWeight) || totalWeight <= 0) {
            throw new IllegalArgumentException("Invalid row weights");
        }
        float[] positive = new float[9], negative = new float[9], rates = new float[9];
        double floor = 1.0 / rows;
        for (int output = 0; output < 9; output++) {
            CompensatedSum sum = new CompensatedSum();
            for (int row = 0; row < rows; row++) {
                float label = labels[row * 9 + output];
                if (label != 0.0f && label != 1.0f) {
                    throw new IllegalArgumentException("Expected hard ordinal labels");
                }
                sum.add(rowWeights[row] * label);
            }
            double rate = StrictMath.max(floor, StrictMath.min(1.0 - floor, sum.value() / totalWeight));
            rates[output] = (float) rate;
            positive[output] = (float) (0.5 / rate);
            negative[output] = (float) (0.5 / (1.0 - rate));
        }
        return new ClassBalance(positive, negative, rates);
    }

    public static double weightedBce(
            float[] logits, ScenarioLearningMatrix matrix, ClassBalance balance, float smoothing) {
        if (logits.length != matrix.rows() * 9) {
            throw new IllegalArgumentException("Logit shape mismatch");
        }
        float[] labels = matrix.ordinalLabels();
        float[] rowWeights = matrix.rowWeights();
        CompensatedSum total = new CompensatedSum();
        CompensatedSum denominator = new CompensatedSum();
        float[] positive = balance.positiveWeights();
        float[] negative = balance.negativeWeights();
        for (int row = 0; row < matrix.rows(); row++) {
            denominator.add(rowWeights[row]);
            for (int output = 0; output < 9; output++) {
                int index = row * 9 + output;
                double logit = logits[index];
                double hard = labels[index];
                double target = hard * (1 - 2 * smoothing) + smoothing;
                double bce = StrictMath.max(logit, 0)
                        - logit * target
                        + StrictMath.log1p(StrictMath.exp(-StrictMath.abs(logit)));
                double classWeight = hard == 1 ? positive[output] : negative[output];
                total.add(rowWeights[row] * classWeight * bce);
            }
        }
        double divisor = denominator.value() * 9;
        if (!Double.isFinite(divisor) || divisor <= 0) {
            throw new IllegalArgumentException("Invalid weighted BCE denominator");
        }
        return total.value() / divisor;
    }

    private static double compensated(float[] values) {
        CompensatedSum sum = new CompensatedSum();
        for (float value : values) {
            sum.add(value);
        }
        return sum.value();
    }

    private BalancedScenarioOrdinalLoss() {}

    public record ClassBalance(float[] positiveWeights, float[] negativeWeights, float[] positiveRates) {

        public ClassBalance {
            positiveWeights = positiveWeights.clone();
            negativeWeights = negativeWeights.clone();
            positiveRates = positiveRates.clone();
            if (positiveWeights.length != 9 || negativeWeights.length != 9 || positiveRates.length != 9) {
                throw new IllegalArgumentException("Class balance width mismatch");
            }
        }

        @Override
        public float[] positiveWeights() {
            return positiveWeights.clone();
        }

        @Override
        public float[] negativeWeights() {
            return negativeWeights.clone();
        }
    }

    private static final class CompensatedSum {

        private double sum;
        private double correction;

        void add(double value) {
            double next = sum + value;
            correction += StrictMath.abs(sum) >= StrictMath.abs(value) ? (sum - next) + value : (value - next) + sum;
            sum = next;
        }

        double value() {
            return sum + correction;
        }
    }
}
