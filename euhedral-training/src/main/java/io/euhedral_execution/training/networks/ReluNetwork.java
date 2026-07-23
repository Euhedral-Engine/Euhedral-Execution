package io.euhedral_execution.training.networks;

public class ReluNetwork extends AbstractNeuralNetwork {

    public ReluNetwork(String importPath) throws Exception {
        super(importPath);
    }

    public ReluNetwork(int[] layers, double learningRate, double alpha, double momentum) {
        super(layers, learningRate, alpha, momentum);
    }

    @Override
    protected double activate(double x) {
        return x > 0.0 ? x : 0.0;
    }

    @Override
    protected double derivative(double activatedValue) {
        return activatedValue > 0.0 ? 1.0 : 0.0;
    }
}
