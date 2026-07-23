package io.euhedral_execution.training;

import io.euhedral_execution.core.utils.MathFunctions;
import java.io.BufferedReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import java.util.StringJoiner;
import lombok.Getter;

public class ActionLearner {

    private final int[] layers;
    private final double learningRate;
    private final double alpha;
    private final double momentum;

    // Weights & Biases
    private final double[][][] weights;
    private final double[][] bias;

    // Velocity arrays for momentum
    private final double[][][] vWeights;
    private final double[][] vBias;

    // Internal buffers
    private final double[][] layerActivations;
    private final double[][] deltas;

    @Getter
    private double loss = Double.NaN;

    @Getter
    private double MAE = 0;
    private double count = 0;

    public ActionLearner(String importPath) throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(importPath))) {

            String[] layerTokens = reader.readLine().trim().split("\\s+");
            int[] hiddenLayers = new int[layerTokens.length];
            for (int i = 0; i < layerTokens.length; i++) {
                hiddenLayers[i] = Integer.parseInt(layerTokens[i]);
            }

            this.layers = Arrays.copyOf(hiddenLayers, hiddenLayers.length + 1);
            this.layers[this.layers.length - 1] = 1;

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

    public ActionLearner(int[] layers, double learningRate, double alpha, double momentum) {

        this.layers = Arrays.copyOf(layers, layers.length + 1);
        this.layers[this.layers.length - 1] = 1;

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

    public double predict(double[] input) {
        double[] current = input;

        for (int l = 0; l < weights.length; l++) {
            double[] next = layerActivations[l];

            for (int o = 0; o < next.length; o++) {
                double sum = bias[l][o];

                for (int i = 0; i < current.length; i++) {
                    sum += current[i] * weights[l][o][i];
                }

                if (l == weights.length - 1) {
                    next[o] = sum;
                } else {
                    next[o] = relu(sum);
                }
            }

            current = next;
        }

        return current[0];
    }

    private double relu(double x) {
        return x > 0.0 ? x : this.alpha * x;
    }

    private double reluDerivative(double activatedValue) {
        return activatedValue > 0.0 ? 1.0 : this.alpha;
    }

    public void train(double[] rawInputs, double reward) {
        double predicted = predict(rawInputs);

        double error = predicted - reward;

        this.loss = !Double.isFinite(this.loss) ? error : MathFunctions.ewma(this.loss, error, this.alpha);

        double absError = Math.abs(error);

        if (this.count == 0) {
            this.MAE = absError;
            this.count = 1;
        } else {
            this.count++;
            this.MAE = this.MAE + (absError - this.MAE) / this.count;
        }


        // Output delta (linear output)
        deltas[deltas.length - 1][0] = error;

        // Hidden deltas
        for (int layer = deltas.length - 2; layer >= 0; layer--) {
            for (int neuron = 0; neuron < deltas[layer].length; neuron++) {
                double sum = 0.0;

                for (int next = 0; next < deltas[layer + 1].length; next++) {
                    sum += weights[layer + 1][next][neuron]
                            * deltas[layer + 1][next];
                }

                deltas[layer][neuron] = sum * reluDerivative(layerActivations[layer][neuron]);
            }
        }

        // Weight updates
        for (int layer = 0; layer < weights.length; layer++) {
            double[] previous = (layer == 0) ? rawInputs : layerActivations[layer - 1];

            for (int out = 0; out < weights[layer].length; out++) {

                for (int in = 0; in < weights[layer][out].length; in++) {

                    double gradient = deltas[layer][out] * previous[in];

                    vWeights[layer][out][in] =
                            momentum * vWeights[layer][out][in] - learningRate * gradient;

                    weights[layer][out][in] += vWeights[layer][out][in];
                }

                vBias[layer][out] =
                        momentum * vBias[layer][out] - learningRate * deltas[layer][out];

                bias[layer][out] += vBias[layer][out];
            }
        }
    }

    public String export() {
        StringJoiner sj = new StringJoiner("\n");

        sj.add(Arrays.toString(Arrays.copyOfRange(this.layers, 0, this.layers.length - 1)).replace("[", "").replace("]", "").replace(",", ""));
        sj.add(Double.toString(this.learningRate));
        sj.add(Double.toString(this.alpha));
        sj.add(Double.toString(this.momentum));

        for (double[][] layer : this.weights) {
            for (double[] neuronWeights : layer) {
                sj.add(toPlainStringSpaceSeparated(neuronWeights));
            }
        }

        for (double[] layerBiases : this.bias) {
            sj.add(toPlainStringSpaceSeparated(layerBiases));
        }

        return sj.toString();
    }

    private static String toPlainStringSpaceSeparated(double[] vector) {
        StringBuilder sb = new StringBuilder(512);
        for (int i = 0; i < vector.length; i++) {
            sb.append(new BigDecimal(Double.toString(vector[i])).toPlainString());
            if (i < vector.length - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }
}

