package io.euhedral_execution.training.learning.data;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.nn.Activation;
import ai.djl.training.loss.Loss;
import io.euhedral_execution.training.learning.InsufficientScenarioLearningDataException;
import io.euhedral_execution.training.learning.utils.ScenarioOrdinalTargets;

public final class BalancedScenarioOrdinalLoss extends Loss {

    public static ClassBalance fit(ScenarioLearningMatrix matrix) {
        int rows = matrix.rows();
        if (rows < 2) {
            throw new InsufficientScenarioLearningDataException(
                    "Class balancing requires at least two fitting rows");
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
            double rate =
                    StrictMath.max(floor, StrictMath.min(1.0 - floor, sum.value() / totalWeight));
            rates[output] = (float) rate;
            positive[output] = (float) (0.5 / rate);
            negative[output] = (float) (0.5 / (1.0 - rate));
        }
        return new ClassBalance(positive, negative, rates);
    }

    public static double weightedBce(float[] logits, ScenarioLearningMatrix matrix,
            ClassBalance balance, float smoothing) {
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
                double bce = StrictMath.max(logit, 0) - logit * target + StrictMath.log1p(
                        StrictMath.exp(-StrictMath.abs(logit)));
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

    private final NDArray positiveWeights;
    private final NDArray negativeWeights;
    private final float labelSmoothing;

    public BalancedScenarioOrdinalLoss(NDManager manager, ClassBalance balance,
            float labelSmoothing) {
        super("BalancedScenarioOrdinalBinaryCrossEntropy");
        if (labelSmoothing < 0 || labelSmoothing >= 0.5f) {
            throw new IllegalArgumentException("Label smoothing must be in [0, 0.5)");
        }
        this.labelSmoothing = labelSmoothing;
        positiveWeights = manager.create(balance.positiveWeights()).reshape(1, 9);
        negativeWeights = manager.create(balance.negativeWeights()).reshape(1, 9);
    }

    @Override
    public NDArray evaluate(NDList labels, NDList predictions) {
        if (labels.size() != 2 || predictions.size() != 1) {
            throw new IllegalArgumentException("Expected ordinal labels and row weights");
        }
        NDArray hardLabel = labels.get(0);
        NDArray rowWeight = labels.get(1);
        NDArray logit = predictions.singletonOrThrow();
        NDArray classWeight =
                hardLabel.mul(positiveWeights).add(hardLabel.neg().add(1.0f).mul(negativeWeights));
        NDArray target = labelSmoothing == 0 ? hardLabel
                : hardLabel.mul(1.0f - 2.0f * labelSmoothing).add(labelSmoothing);
        NDArray stableBce = Activation.relu(logit).sub(logit.mul(target))
                .add(Activation.softPlus(logit.abs().neg()));
        NDArray denominator = rowWeight.sum().mul(ScenarioOrdinalTargets.OUTPUT_WIDTH);
        return stableBce.mul(classWeight).mul(rowWeight).sum().div(denominator);
    }

    public record ClassBalance(float[] positiveWeights, float[] negativeWeights,
                               float[] positiveRates) {

        public ClassBalance {
            positiveWeights = positiveWeights.clone();
            negativeWeights = negativeWeights.clone();
            positiveRates = positiveRates.clone();
            if (positiveWeights.length != 9 || negativeWeights.length != 9
                    || positiveRates.length != 9) {
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

        @Override
        public float[] positiveRates() {
            return positiveRates.clone();
        }
    }

    private static final class CompensatedSum {

        private double sum;
        private double correction;

        void add(double value) {
            double next = sum + value;
            correction += StrictMath.abs(sum) >= StrictMath.abs(value) ? (sum - next) + value
                    : (value - next) + sum;
            sum = next;
        }

        double value() {
            return sum + correction;
        }
    }
}
