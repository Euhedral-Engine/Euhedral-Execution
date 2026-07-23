package io.euhedral_execution.training.networks;

@SuppressWarnings("unused")
public class LeakyReluNetwork extends AbstractNeuralNetwork {

    public LeakyReluNetwork(String importPath) throws Exception {
        super(importPath);
    }

    public LeakyReluNetwork(int[] layers, double learningRate, double alpha, double momentum) {
        super(layers, learningRate, alpha, momentum);
    }

    @Override
    protected double activate(double x) {
        return x > 0.0 ? x : this.alpha * x;
    }

    @Override
    protected double derivative(double activatedValue) {
        return activatedValue > 0.0 ? 1.0 : this.alpha;
    }
}
