package io.euhedral_execution.core.utils;

import java.util.Random;

public class OnlineLearner {

    private final int inputSize;
    private final int hiddenSize;
    private final int outputSize;
    private final double learningRate;
    private final double learningThreshold;
    private final double alpha;
    private final double momentum;

    // Weights & Biases
    private final double[][] weightsIH;
    private final double[][] weightsHO;
    private final double[] biasH;
    private final double[] biasO;

    // Velocity arrays for momentum
    private final double[][] vWeightsIH;
    private final double[][] vWeightsHO;
    private final double[] vBiasH;
    private final double[] vBiasO;

    // Internal buffers
    private final double[] hiddenActivations;
    private final double[] outputActivations;
    private final double[] deltaO;
    private final double[] deltaH;

    // Scaler Arrays
    private final double[] inputMins;
    private final double[] inputMaxes;

    // Internal
    private final double[] lastRawInputs;
    private final double[] normalizedInputs;
    private final double[] denormalizedOutputs;

    public OnlineLearner(int inputSize, int hiddenSize, int outputSize, double learningRate,
            double learningThreshold, double alpha,
            double momentum) {
        this.inputSize = inputSize;
        this.hiddenSize = hiddenSize;
        this.outputSize = outputSize;
        this.learningRate = learningRate;
        this.learningThreshold = learningThreshold;
        this.alpha = alpha;
        this.momentum = momentum;

        this.weightsIH = new double[hiddenSize][inputSize];
        this.weightsHO = new double[outputSize][hiddenSize];
        this.biasH = new double[hiddenSize];
        this.biasO = new double[outputSize];

        this.vWeightsIH = new double[hiddenSize][inputSize];
        this.vWeightsHO = new double[outputSize][hiddenSize];
        this.vBiasH = new double[hiddenSize];
        this.vBiasO = new double[outputSize];

        this.hiddenActivations = new double[hiddenSize];
        this.outputActivations = new double[outputSize];
        this.deltaO = new double[outputSize];
        this.deltaH = new double[hiddenSize];

        // Scaler storage
        this.inputMins = new double[inputSize];
        this.inputMaxes = new double[inputSize];

        // Execution workspaces
        this.lastRawInputs = new double[inputSize];
        this.normalizedInputs = new double[inputSize];
        this.denormalizedOutputs = new double[outputSize];

        initializeParameters();
        resetScalers();
    }

    private void initializeParameters() {
        Random rand = new Random();
        double heStdDev = Math.sqrt(2.0 / inputSize);
        double outStdDev = Math.sqrt(2.0 / hiddenSize);

        for (int i = 0; i < hiddenSize; i++) {
            for (int j = 0; j < inputSize; j++) {
                weightsIH[i][j] = rand.nextGaussian() * heStdDev;
            }
            biasH[i] = 0.0;
        }

        for (int i = 0; i < outputSize; i++) {
            for (int j = 0; j < hiddenSize; j++) {
                weightsHO[i][j] = rand.nextGaussian() * outStdDev;
            }
            biasO[i] = 0.0;
        }
    }

    private void resetScalers() {
        for (int i = 0; i < inputSize; i++) {
            inputMins[i] = Double.MAX_VALUE;
            inputMaxes[i] = -Double.MAX_VALUE;
        }
    }

    private double relu(double x) {
        return x > 0.0 ? x : this.alpha * x;
    }

    private double reluDerivative(double activatedValue) {
        return activatedValue > 0.0 ? 1.0 : this.alpha;
    }

    public double[] predict(double[] rawInputs) {
        for (int i = 0; i < inputSize; i++) {
            double range = inputMaxes[i] - inputMins[i];
            if (range <= 0.0) {
                normalizedInputs[i] = 0.5;
            } else {
                double raw = rawInputs[i];
                normalizedInputs[i] = (raw - inputMins[i]) / range;
            }
        }

        for (int i = 0; i < hiddenSize; i++) {
            double sum = biasH[i];
            for (int j = 0; j < inputSize; j++) {
                sum += normalizedInputs[j] * weightsIH[i][j];
            }
            hiddenActivations[i] = relu(sum);
        }

        for (int i = 0; i < outputSize; i++) {
            double sum = biasO[i];
            for (int j = 0; j < hiddenSize; j++) {
                sum += hiddenActivations[j] * weightsHO[i][j];
            }
            outputActivations[i] = sum;
        }

        return denormalizedOutputs;
    }

    public void train(double[] rawInputs, double[] rawTargets) {
        if (!shouldTrain(rawInputs)) {
            return;
        }

        for (int i = 0; i < inputSize; i++) {
            inputMins[i] = MathFunctions.ewma(inputMins[i], rawInputs[i], this.alpha);
            inputMaxes[i] = MathFunctions.ewma(inputMaxes[i], rawInputs[i], this.alpha);
            if (rawInputs[i] < inputMins[i]) {
                inputMins[i] = rawInputs[i];
            }
            if (rawInputs[i] > inputMaxes[i]) {
                inputMaxes[i] = rawInputs[i];
            }
        }

        predict(rawInputs);

        // Output Gradients
        for (int k = 0; k < outputSize; k++) {
            deltaO[k] = outputActivations[k] - rawTargets[k];
        }

        // Hidden Gradients
        for (int j = 0; j < hiddenSize; j++) {
            double error = 0.0;
            for (int k = 0; k < outputSize; k++) {
                error += deltaO[k] * weightsHO[k][j];
            }
            deltaH[j] = error * reluDerivative(hiddenActivations[j]);
        }

        // Update Hidden-to-Output Layer
        for (int k = 0; k < outputSize; k++) {
            for (int j = 0; j < hiddenSize; j++) {
                double gradient = deltaO[k] * hiddenActivations[j];
                vWeightsHO[k][j] = (momentum * vWeightsHO[k][j]) - (learningRate * gradient);
                weightsHO[k][j] += vWeightsHO[k][j];
            }
            vBiasO[k] = (momentum * vBiasO[k]) - (learningRate * deltaO[k]);
            biasO[k] += vBiasO[k];
        }

        // Update Input-to-Hidden Layer
        for (int j = 0; j < hiddenSize; j++) {
            for (int i = 0; i < inputSize; i++) {
                double gradient = deltaH[j] * normalizedInputs[i];
                vWeightsIH[j][i] = (momentum * vWeightsIH[j][i]) - (learningRate * gradient);
                weightsIH[j][i] += vWeightsIH[j][i];
            }
            vBiasH[j] = (momentum * vBiasH[j]) - (learningRate * deltaH[j]);
            biasH[j] += vBiasH[j];
        }
    }

    private boolean shouldTrain(double[] rawInputs) {
        if (this.learningThreshold <= 0) {
            return true;
        }

        double variance = 0.0;
        for (int i = 0; i < inputSize; i++) {
            double diff = rawInputs[i] - lastRawInputs[i];
            variance += diff * diff;
            lastRawInputs[i] = rawInputs[i];
        }

        double stdDev = Math.sqrt(variance);
        return stdDev >= this.learningThreshold;
    }
}



