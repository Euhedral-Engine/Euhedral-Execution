package io.euhedral_execution.training.networks;

import io.euhedral_execution.core.utils.MathFunctions;
import java.io.BufferedReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import java.util.StringJoiner;
import lombok.Getter;

public abstract class AbstractNeuralNetwork {

    protected static String arrayToString(double[] arr) {
        StringJoiner sj = new StringJoiner(" ");

        for (double d : arr) {
            sj.add(new BigDecimal(d).toPlainString());
        }
        return sj.toString();
    }
    protected final int[] layers;
    protected final double learningRate;
    protected final double alpha;
    protected final double momentum;
    // Weights & Biases
    protected final double[][][] weights;
    protected final double[][] bias;
    // Velocity arrays for momentum
    protected final double[][][] vWeights;
    protected final double[][] vBias;
    // Internal buffers
    protected final double[][] layerActivations;
    protected final double[][] deltas;
    @Getter
    protected double MSE = Double.NaN;
    @Getter
    protected double MAE = 0;
    protected double count = 0;

    public AbstractNeuralNetwork(String importPath) throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(importPath))) {

            String[] layerTokens = reader.readLine().trim().split("\\s+");
            this.layers = new int[layerTokens.length];
            for (int i = 0; i < layerTokens.length; i++) {
                this.layers[i] = Integer.parseInt(layerTokens[i]);
            }

            int layerCount = this.layers.length;

            this.weights = new double[layerCount - 1][][];
            this.vWeights = new double[layerCount - 1][][];

            this.bias = new double[layerCount - 1][];
            this.vBias = new double[layerCount - 1][];

            this.layerActivations = new double[layerCount - 1][];
            this.deltas = new double[layerCount - 1][];

            this.learningRate = Double.parseDouble(reader.readLine());
            this.alpha = Double.parseDouble(reader.readLine());
            this.momentum = Double.parseDouble(reader.readLine());

            for (int l = 0; l < layerCount - 1; l++) {
                int in = this.layers[l];
                int out = this.layers[l + 1];

                this.weights[l] = new double[out][in];
                this.vWeights[l] = new double[out][in];

                this.bias[l] = new double[out];
                this.vBias[l] = new double[out];

                this.layerActivations[l] = new double[out];
                this.deltas[l] = new double[out];

                for (int j = 0; j < out; j++) {
                    String[] weightTokens = reader.readLine().trim().split("\\s+");
                    for (int k = 0; k < in; k++) {
                        this.weights[l][j][k] = Double.parseDouble(weightTokens[k]);
                    }
                }
            }

            for (int l = 0; l < layerCount - 1; l++) {
                int out = this.layers[l + 1];
                String[] biasTokens = reader.readLine().trim().split("\\s+");
                for (int j = 0; j < out; j++) {
                    this.bias[l][j] = Double.parseDouble(biasTokens[j]);
                }
            }

            this.MAE = 0.0;
            this.count = 0;
        }
    }

    public AbstractNeuralNetwork(int[] layers, double learningRate, double alpha, double momentum) {
        this.layers = Arrays.copyOf(layers, layers.length);
        int layerCount = this.layers.length;

        weights = new double[layerCount - 1][][];
        vWeights = new double[layerCount - 1][][];

        bias = new double[layerCount - 1][];
        vBias = new double[layerCount - 1][];

        layerActivations = new double[layerCount - 1][];
        deltas = new double[layerCount - 1][];

        for (int l = 0; l < layerCount - 1; l++) {
            int in = this.layers[l];
            int out = this.layers[l + 1];

            weights[l] = new double[out][in];
            vWeights[l] = new double[out][in];

            bias[l] = new double[out];
            vBias[l] = new double[out];

            layerActivations[l] = new double[out];
            deltas[l] = new double[out];
        }

        this.learningRate = learningRate;
        this.alpha = alpha;
        this.momentum = momentum;

        initializeParameters();
    }

    private void initializeParameters() {
        Random rand = new Random();
        for (int l = 0; l < weights.length; l++) {
            int fanIn = layers[l];
            double stdDev = Math.sqrt(2.0 / fanIn);

            for (int o = 0; o < weights[l].length; o++) {
                for (int i = 0; i < weights[l][o].length; i++) {
                    weights[l][o][i] = rand.nextGaussian() * stdDev;
                }
                bias[l][o] = rand.nextGaussian() * 0.01;
            }
        }
    }

    public double[] predict(double[] input) {
        double[] current = input;

        for (int l = 0; l < this.weights.length; l++) {
            double[] next = this.layerActivations[l];

            for (int o = 0; o < next.length; o++) {
                double sum = this.bias[l][o];

                for (int i = 0; i < current.length; i++) {
                    sum += current[i] * weights[l][o][i];
                }

                if (l == this.weights.length - 1) {
                    next[o] = sum;
                } else {
                    next[o] = activate(sum);
                }
            }

            current = next;
        }

        return current;
    }

    protected abstract double activate(double x);

    protected abstract double derivative(double activatedValue);

    public void train(double[] rawInputs, double[] target) {
        double[] predicted = predict(rawInputs);

        calculateError(predicted, target);

        backwardPass();

        adjustWeightsAndBiases(rawInputs);
    }

    protected void calculateError(double[] predicted, double[] target) {
        int outputDim = predicted.length;
        int lastIndex = this.deltas.length - 1;

        double sumSquared = 0.0;
        double sumAbs = 0.0;

        for (int i = 0; i < outputDim; i++) {
            double e = predicted[i] - target[i];
            this.deltas[lastIndex][i] = e;
            sumSquared += e * e;
            sumAbs += Math.abs(e);
        }

        double mseSample = sumSquared / outputDim;
        double maeSample = sumAbs / outputDim;

        this.MSE = !Double.isFinite(this.MSE)
                ? mseSample
                : MathFunctions.ewma(this.MSE, mseSample, this.alpha);

        if (this.count == 0) {
            this.MAE = maeSample;
            this.count = 1;
        } else {
            this.count++;
            this.MAE += (maeSample - this.MAE) / this.count;
        }
    }

    protected void backwardPass() {
        for (int layer = this.deltas.length - 2; layer >= 0; layer--) {
            for (int neuron = 0; neuron < this.deltas[layer].length; neuron++) {
                double sum = 0.0;
                for (int next = 0; next < this.deltas[layer + 1].length; next++) {
                    sum += this.weights[layer + 1][next][neuron] * this.deltas[layer + 1][next];
                }
                this.deltas[layer][neuron] =
                        sum * derivative(this.layerActivations[layer][neuron]);
            }
        }
    }

    protected void adjustWeightsAndBiases(double[] rawInputs) {
        for (int layer = 0; layer < this.weights.length; layer++) {
            double[] previous = (layer == 0) ? rawInputs : this.layerActivations[layer - 1];

            for (int out = 0; out < this.weights[layer].length; out++) {
                for (int in = 0; in < this.weights[layer][out].length; in++) {
                    double gradient = this.deltas[layer][out] * previous[in];

                    this.vWeights[layer][out][in] =
                            this.momentum * this.vWeights[layer][out][in]
                                    - this.learningRate * gradient;

                    this.weights[layer][out][in] += this.vWeights[layer][out][in];
                }

                this.vBias[layer][out] =
                        this.momentum * this.vBias[layer][out]
                                - this.learningRate * this.deltas[layer][out];

                this.bias[layer][out] += this.vBias[layer][out];
            }
        }
    }

    public String export() {
        StringJoiner sj = new StringJoiner("\n");

        sj.add(Arrays.toString(this.layers)
                .replace("[", "")
                .replace("]", "")
                .replace(",", ""));
        sj.add(Double.toString(this.learningRate));
        sj.add(Double.toString(this.alpha));
        sj.add(Double.toString(this.momentum));

        for (double[][] layer : this.weights) {
            for (double[] neuronWeights : layer) {
                sj.add(arrayToString(neuronWeights));
            }
        }

        for (double[] layerBiases : this.bias) {
            sj.add(arrayToString(layerBiases));
        }

        return sj.toString();
    }
}

