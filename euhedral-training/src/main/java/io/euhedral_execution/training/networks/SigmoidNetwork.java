package io.euhedral_execution.training.networks;

@SuppressWarnings("unused")
public class SigmoidNetwork extends AbstractNeuralNetwork{

    public SigmoidNetwork(String importPath) throws Exception {
        super(importPath);
    }

    public SigmoidNetwork(int[] layers, double learningRate, double alpha, double momentum) {
        super(layers, learningRate, alpha, momentum);
    }

    @Override
    protected double activate(double x) {
        return 1.0 / (1 + Math.pow(Math.E, -x));
    }

    @Override
    protected double derivative(double activatedValue) {
        return activatedValue * (1 - activatedValue);
    }
}
