package io.euhedral_execution.core.config;

import io.euhedral_execution.core.utils.CommonVarHandles;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

@SuppressWarnings("unused")
public class FragmentActionPicker {

    private static final VarHandle WEIGHTS = CommonVarHandles.makeHandle(
            MethodHandles.lookup(), FragmentActionPicker.class, "actionWeights", double[][].class);

    private double[][] actionWeights;
    private volatile boolean halt;

    public FragmentActionPicker(double[] weights) {
        validate(weights);
        this.actionWeights = reshape(weights);
        this.halt = isHaltVector(weights);
    }

    public static FragmentActionPicker ofDefaults() {
        double[] weights = new double[28];
        Arrays.fill(weights, 1);
        return new FragmentActionPicker(weights);
    }

    private static void validate(double[] weights) {
        if (weights.length != 28) {
            throw new IllegalArgumentException("Weights length must be 28");
        }
    }

    private static boolean isHaltVector(double[] weights) {
        for (double weight : weights) {
            if (weight != 0.0) {
                return false;
            }
        }
        return true;
    }

    private static double[][] reshape(double[] weights) {
        double[][] reshaped = new double[4][7];
        int index = 0;
        for (int action = 0; action < reshaped.length; action++) {
            for (int input = 0; input < reshaped[action].length; input++) {
                reshaped[action][input] = weights[index++];
            }
        }
        return reshaped;
    }

    public boolean performAction(Action action, double[] inputs) {
        return predict(action, inputs) > 0;
    }

    public double predict(Action action, double[] inputs) {
        double[][] weights = (double[][]) WEIGHTS.getOpaque(this);
        return weights[action.index][0] * inputs[0]
                + weights[action.index][1] * inputs[1]
                + weights[action.index][2] * inputs[2]
                + weights[action.index][3] * inputs[3]
                + weights[action.index][4] * inputs[4]
                + weights[action.index][5] * inputs[5]
                + weights[action.index][6];
    }

    public void normalize(double[] inputs) {
        double sum = 0;
        for (double d : inputs) {
            sum += d * d;
        }
        double length = Math.max(Math.sqrt(sum), 1e-9);
        for (int i = 0; i < inputs.length; i++) {
            inputs[i] /= length;
        }
    }

    public boolean halted() {
        return this.halt;
    }

    public void setWeights(double[] weights) {
        validate(weights);
        double[][] nextWeights = reshape(weights);
        boolean nextHalt = isHaltVector(weights);

        // Publish the complete immutable matrix before exposing the corresponding halt state.
        WEIGHTS.setVolatile(this, nextWeights);
        this.halt = nextHalt;
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
