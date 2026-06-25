package euhedral.io.utils;

import euhedral.hashing.HasherApi;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("unused")
public class FlowPredictor2 {

    private static double[][] randomMatrix(int row, int col) {
        long rand = HasherApi.mix(ThreadLocalRandom.current().nextLong());

        double[][] matrix = new double[row][col];
        for (int i = 0; i < row; i++) {
            for (int j = 0; j < col; j++) {
                rand = HasherApi.mix(rand + 1);
                double num = (double) (rand & Long.MAX_VALUE) / Long.MAX_VALUE;
                matrix[i][j] = num * 0.5;
            }
        }
        return matrix;
    }

    private static int sizeHiddenDim(int inputDim, int outputDim) {
        int hiddenDim;
        int sum = inputDim + outputDim;

        if (sum <= 5) {
            return (int) (sum * 1.5);
        }
        return sum / 2;
    }

    private final int inputDim;
    private final int hiddenDim;
    private final int outputDim;

    private final double learningRate;

    private final double[][] weight1;
    private final double[] bias1;
    private final double[][] weight2;
    private final double[] bias2;

    private final double[] hidden;
    private final double[] deltaHidden;
    private final double[] outputInternal;
    private final double[] deltaOut;

    public FlowPredictor2(PredictorProfile profile, double learningRate) {
        this.inputDim = profile.weight1[0].length;
        this.hiddenDim = profile.weight1.length;
        this.outputDim = profile.bias2.length;
        this.learningRate = learningRate;

        this.weight1 = profile.weight1;
        this.bias1 = profile.bias1;
        this.weight2 = profile.weight2;
        this.bias2 = profile.bias2;

        this.hidden = new double[this.hiddenDim];
        this.deltaHidden = new double[this.hiddenDim];
        this.outputInternal = new double[this.outputDim];
        this.deltaOut = new double[this.outputDim];
    }

    public FlowPredictor2(int inputDim, int outputDim, double learningRate) {
        this(inputDim, sizeHiddenDim(inputDim, outputDim), outputDim, learningRate);
    }

    public FlowPredictor2(int inputDim, int hiddenDim, int outputDim, double learningRate) {
        this.inputDim = inputDim;
        this.hiddenDim = hiddenDim;
        this.outputDim = outputDim;
        this.learningRate = learningRate;

        this.weight1 = randomMatrix(hiddenDim, inputDim);
        this.bias1 = new double[hiddenDim];
        this.weight2 = randomMatrix(outputDim, hiddenDim);
        this.bias2 = new double[outputDim];

        this.hidden = new double[hiddenDim];
        this.deltaHidden = new double[hiddenDim];
        this.outputInternal = new double[outputDim];
        this.deltaOut = new double[outputDim];
    }

    public void predict(double[] input, double[] output) {
        for (int i = 0; i < this.hiddenDim; i++) {
            double net = this.bias1[i];
            for (int j = 0; j < this.inputDim; j++) {
                net += this.weight1[i][j] * input[j];
            }
            hidden[i] = Math.tanh(net);
        }

        for (int i = 0; i < this.outputDim; i++) {
            output[i] = this.bias2[i];
            for (int j = 0; j < this.hiddenDim; j++) {
                output[i] += this.weight2[i][j] * this.hidden[j];
            }
        }
    }

    public void train(double[] input, double[] target) {
        // Predict
        predict(input, this.outputInternal);

        // Hidden Layer Errors
        for (int i = 0; i < this.hiddenDim; i++) {
            double errorSum = 0;
            for (int j = 0; j < this.outputDim; j++) {
                errorSum += this.deltaOut[j] * this.weight2[j][i];
            }
            this.deltaHidden[i] = errorSum * (1.0 - this.hidden[i] * this.hidden[i]);
        }

        // Output Layer Errors
        for (int i = 0; i < this.outputDim; i++) {
            this.deltaOut[i] = this.outputInternal[i] - target[i];
        }

        // Update Input/Hidden Weights
        for (int i = 0; i < this.hiddenDim; i++) {
            this.bias1[i] -= this.learningRate * this.deltaHidden[i];
            for (int j = 0; j < this.inputDim; j++) {
                this.weight1[i][j] -= this.learningRate * this.deltaHidden[i] * input[j];
            }
        }

        // Update Output Weights
        for (int i = 0; i < this.outputDim; i++) {
            this.bias2[i] -= this.learningRate * this.deltaOut[i];
            for (int j = 0; j < this.hiddenDim; j++) {
                this.weight2[i][j] -= this.learningRate * this.deltaOut[i] * this.hidden[j];
            }
        }
    }

    public PredictorProfile export() {
        double[][] weight1 = new double[this.hiddenDim][this.inputDim];
        double[] bias1 = new double[this.hiddenDim];
        double[][] weight2 = new double[this.outputDim][this.hiddenDim];
        double[] bias2 = new double[this.outputDim];

        for (int i = 0; i < this.hiddenDim; i++) {
            System.arraycopy(this.weight1[i], 0, weight1[i], 0, this.inputDim);
        }
        System.arraycopy(this.bias1, 0, bias1, 0, this.hiddenDim);
        for (int i = 0; i < this.outputDim; i++) {
            System.arraycopy(this.weight2[i], 0, weight2[i], 0, this.hiddenDim);
        }
        System.arraycopy(this.bias2, 0, bias2, 0, this.outputDim);

        return new PredictorProfile(weight1, bias1, weight2, bias2);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("double[][] weight1 = new double[][]")
                .append(Arrays.deepToString(this.weight1)
                        .replace('[', '{')
                        .replace(']', '}')
                ).append(";\n");
        sb.append("double[] bias1 = new double[]")
                .append(Arrays.toString(this.bias1)
                        .replace('[', '{')
                        .replace(']', '}')
                ).append(";\n");
        sb.append("double[][] weight2 = new double[][]")
                .append(Arrays.deepToString(this.weight2)
                        .replace('[', '{')
                        .replace(']', '}')
                ).append(";\n");
        sb.append("double[] bias2 = new double[]")
                .append(Arrays.toString(this.bias2)
                        .replace('[', '{')
                        .replace(']', '}')
                ).append(";\n");

        return sb.toString();
    }

    public static class PredictorProfile {

        private final double[][] weight1;
        private final double[] bias1;
        private final double[][] weight2;
        private final double[] bias2;

        public PredictorProfile(double[][] weight1, double[] bias1, double[][] weight2,
                double[] bias2) {
            this.weight1 = weight1;
            this.bias1 = bias1;
            this.weight2 = weight2;
            this.bias2 = bias2;
        }
    }
}
