package io.euhedral_execution.core.utils;

import java.util.Arrays;

public class P2Quantile {

    final double quantile;

    private final double[] markerHeight = new double[5];
    private final int[] positions = new int[5];
    private final double[] pDesired = new double[5];
    private final double[] dPosition = new double[5];

    private long count = 0;

    public P2Quantile(double quantile) {
        if (quantile < 0.0 || quantile > 1.0) {
            throw new IllegalArgumentException("Quantile must be between 0.0 and 1.0");
        }

        this.quantile = quantile;
        this.dPosition[0] = 0;
        this.dPosition[1] = quantile / 2.0;
        this.dPosition[2] = quantile;
        this.dPosition[3] = (1.0 + quantile) / 2.0;
        this.dPosition[4] = 1.0;
    }

    public void add(double x) {
        double n = this.count;
        if (this.count++ < 5) {
            this.markerHeight[(int) n] = x;
            if (this.count == 5) {
                Arrays.sort(this.markerHeight);
                for (int i = 0; i < 5; i++) {
                    this.positions[i] = i + 1;
                    this.pDesired[i] = 1 + n * this.dPosition[i];
                }
            }
            return;
        }

        int k;
        if (x < this.markerHeight[0]) {
            this.markerHeight[0] = x; // Update minimum
            k = 0;
        } else if (x < this.markerHeight[1]) {
            k = 0;
        } else if (x < this.markerHeight[2]) {
            k = 1;
        } else if (x < this.markerHeight[3]) {
            k = 2;
        } else if (x <= this.markerHeight[4]) {
            k = 3;
        } else {
            this.markerHeight[4] = x; // Update maximum
            k = 3;
        }

        for (int i = k + 1; i < 5; i++) {
            this.positions[i]++;
        }

        this.pDesired[0] = 1;
        this.pDesired[1] = 1 + n * this.quantile / 2;
        this.pDesired[2] = 1 + n * this.quantile;
        this.pDesired[3] = 1 + n * (1 + this.quantile) / 2;
        this.pDesired[4] = this.count;

        for (int i = 1; i <= 3; i++) {
            double d = this.pDesired[i] - this.positions[i];

            if ((d >= 1.0 && this.positions[i + 1] - this.positions[i] > 1) || (d <= -1.0
                    && this.positions[i] - this.positions[i - 1] > 1)) {
                int dSign = (d >= 1.0) ? 1 : -1;

                double qNew = parabolicFormula(i, dSign);

                if (this.markerHeight[i - 1] < qNew && qNew < this.markerHeight[i + 1]) {
                    this.markerHeight[i] = qNew;
                } else {
                    this.markerHeight[i] = linearFormula(i, dSign);
                }

                this.positions[i] += dSign;
            }
        }
    }

    private double parabolicFormula(int i, int d) {
        double nIMinus1 = this.positions[i - 1];
        double nI = this.positions[i];
        double nIPlus1 = this.positions[i + 1];

        double term1 =
                (nI - nIMinus1 + d) * (this.markerHeight[i + 1] - this.markerHeight[i]) / (nIPlus1
                        - nI);
        double term2 = (nIPlus1 - nI - d) * (this.markerHeight[i] - this.markerHeight[i - 1]) / (nI
                - nIMinus1);

        return this.markerHeight[i] + (d / (nIPlus1 - nIMinus1)) * (term1 + term2);
    }

    private double linearFormula(int i, int d) {
        if (d > 0) {
            return this.markerHeight[i] + (this.markerHeight[i + 1] - this.markerHeight[i]) / (
                    this.positions[i + 1] - this.positions[i]);
        } else {
            return this.markerHeight[i] - (this.markerHeight[i - 1] - this.markerHeight[i]) / (
                    this.positions[i - 1] - this.positions[i]);
        }
    }

    public double value() {
        if (this.count < 5) {
            if (this.count == 0) {
                return Double.NaN;
            }
            double[] copy = Arrays.copyOf(this.markerHeight, (int) this.count);
            Arrays.sort(copy);
            int idx = (int) Math.floor(this.quantile * (this.count - 1));
            return copy[idx];
        }
        return this.markerHeight[2];
    }

    public long count() {
        return this.count;
    }

    public boolean initialized() {
        return this.count >= 5;
    }

    public void reset() {
        Arrays.fill(this.markerHeight, 0);
        Arrays.fill(this.positions, 0);
        Arrays.fill(this.pDesired, 0);
        this.count = 0;
    }
}
