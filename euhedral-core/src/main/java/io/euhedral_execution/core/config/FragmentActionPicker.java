package io.euhedral_execution.core.config;

import java.util.Arrays;

@SuppressWarnings("unused")
public class FragmentActionPicker {

    private final double[][] actionWeights;

    public static FragmentActionPicker ofDefaults() {
        double[] weights = new double[28];
        Arrays.fill(weights, 1);
        return new FragmentActionPicker(weights);
    }

    public FragmentActionPicker(double[] weights) {
        if (weights.length != 28) {
            throw new IllegalArgumentException("Weights length must be 28");
        }
        this.actionWeights = new double[4][7];

        int w = 0;
        for (int i = 0; i < this.actionWeights.length; i++) {
            for (int j = 0; j < this.actionWeights[i].length; j++) {
                this.actionWeights[i][j] = weights[w++];
            }
        }
    }

    public boolean performAction(Action action, double[] inputs) {
        return predict(action, inputs) > 0;
    }

    public double predict(Action action, double[] inputs) {
        return this.actionWeights[action.index][0] * inputs[0] +
                this.actionWeights[action.index][1] * inputs[1] +
                this.actionWeights[action.index][2] * inputs[2] +
                this.actionWeights[action.index][3] * inputs[3] +
                this.actionWeights[action.index][4] * inputs[4] +
                this.actionWeights[action.index][5] * inputs[5] +
                this.actionWeights[action.index][6];
    }

    public void normalize(double[] inputs) {
        double sum = 0;
        for(double d : inputs) {
            sum = d * d;
        }
        double length = Math.max(Math.sqrt(sum), 1e-9);
        for(int i = 0; i < inputs.length; i++) {
            inputs[i] /= length;
        }
    }

    public enum Input {
        COMPLETED(0),
        BATCH(1),
        THROUGHPUT(2),
        THROUGHPUT_CV(3),
        AVAILABILITY(4),
        REMOTE_CACHE(5),
        BIAS(6);

        public final int index;

        Input(int index) {
            this.index = index;
        }
    }

    public enum Action {
        REQUEST(0),
        REMOTE_CACHE_EXECUTE(1),
        REMOTE_EXECUTE(2),
        SLEEP(3);

        public final int index;

        Action(int index) {
            this.index = index;
        }
    }
}
