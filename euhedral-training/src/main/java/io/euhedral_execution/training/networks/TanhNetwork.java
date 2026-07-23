package io.euhedral_execution.training.networks;

@SuppressWarnings("unused")
public class TanhNetwork extends AbstractNeuralNetwork {

    public TanhNetwork(String importPath) throws Exception {
        super(importPath);
    }

    public TanhNetwork(int[] layers, double learningRate, double alpha, double momentum) {
        super(layers, learningRate, alpha, momentum);
    }

    @Override
    protected double activate(double x) {
        return Math.tanh(x);
    }

    @Override
    protected double derivative(double activatedValue) {
        return 1.0 - activatedValue * activatedValue;
    }
}
