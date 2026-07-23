package io.euhedral_execution.training.networks;

@SuppressWarnings("unused")
public final class SoftmaxNetwork extends AbstractNeuralNetwork {

    public SoftmaxNetwork(String importPath) throws Exception {
        super(importPath);
    }

    public SoftmaxNetwork(int[] layers, double learningRate, double alpha, double momentum) {
        super(layers, learningRate, alpha, momentum);
    }

    @Override
    protected double activate(double x) {
        return Math.max(x, 0.0);
    }

    @Override
    protected double derivative(double activatedValue) {
        return activatedValue > 0.0 ? 1.0 : 0.0;
    }

    @Override
    public double[] predict(double[] input) {
        double[] logits = super.predict(input);

        double max = Double.NEGATIVE_INFINITY;
        for (double v : logits) {
            if (v > max) {
                max = v;
            }
        }
        double sum = 0.0;
        for (int i = 0; i < logits.length; i++) {
            logits[i] = Math.exp(logits[i] - max);
            sum += logits[i];
        }
        double invSum = 1.0 / Math.max(sum, 1e-12);
        for (int i = 0; i < logits.length; i++) {
            logits[i] *= invSum;
        }
        return logits;
    }
}
