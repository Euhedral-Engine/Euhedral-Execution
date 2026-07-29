package io.euhedral_execution.training.learning.utils;

import java.util.List;
import java.util.Objects;

import io.euhedral_execution.training.learning.statistics.EnsembleOrdinalDistribution;
import io.euhedral_execution.training.learning.statistics.OrdinalDistribution;

public final class ScenarioOrdinalTargets {

    public static final int OUTPUT_WIDTH = 9;

    public static double threshold(int k) {
        if (k < 0 || k >= 9) {
            throw new IndexOutOfBoundsException();
        }
        return (k + 1) / 10.0;
    }

    public static void encode(double quality, float[] out, int offset) {
        Objects.requireNonNull(out);
        if (!finiteRate(quality) || offset < 0 || offset + 9 > out.length) {
            throw new IllegalArgumentException();
        }
        for (int k = 0; k < 9; k++) {
            out[offset + k] = quality >= threshold(k) ? 1 : 0;
        }
    }

    public static OrdinalDistribution decode(double[] logits) {
        Objects.requireNonNull(logits);
        if (logits.length != 9) {
            throw new IllegalArgumentException("Expected nine logits");
        }
        double[] p = new double[9];
        for (int i = 0; i < 9; i++) {
            double x = logits[i];
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException("Non-finite logit");
            }
            if (x >= 0) {
                p[i] = 1 / (1 + StrictMath.exp(-x));
            } else {
                double exponential = StrictMath.exp(x);
                p[i] = exponential / (1 + exponential);
            }
        }
        pav(p);
        double[] mass = new double[10];
        mass[0] = 1 - p[0];
        for (int b = 1; b < 9; b++) {
            mass[b] = p[b - 1] - p[b];
        }
        mass[9] = p[8];
        CompensatedSum total = new CompensatedSum();
        for (int b = 0; b < 10; b++) {
            if (mass[b] < -1e-15) {
                throw new IllegalArgumentException("Negative ordinal mass");
            }
            if (mass[b] < 0) {
                mass[b] = 0;
            }
            total.add(mass[b]);
        }
        mass[9] += 1 - total.value();
        if (mass[9] < 0 || mass[9] > 1) {
            throw new IllegalArgumentException("Invalid ordinal remainder");
        }
        CompensatedSum meanSum = new CompensatedSum();
        CompensatedSum entropySum = new CompensatedSum();
        for (int b = 0; b < 10; b++) {
            meanSum.add(mass[b] * center(b));
            if (mass[b] > 0) {
                entropySum.add(-mass[b] * StrictMath.log(mass[b]));
            }
        }
        double mean = meanSum.value();
        CompensatedSum variance = new CompensatedSum();
        for (int b = 0; b < 10; b++) {
            variance.add(mass[b] * square(center(b) - mean));
        }
        return new OrdinalDistribution(p, mass, mean, nonnegativeVariance(variance.value()),
                entropySum.value() / StrictMath.log(10), p[8]);
    }

    public static EnsembleOrdinalDistribution combine(List<OrdinalDistribution> members) {
        Objects.requireNonNull(members);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("No members");
        }
        double[] mass = new double[10], compensation = new double[10];
        double[] means = new double[members.size()];
        CompensatedSum top = new CompensatedSum();
        for (int m = 0; m < members.size(); m++) {
            var d = Objects.requireNonNull(members.get(m));
            means[m] = d.meanQuality();
            top.add(d.topDecileProbability());
            double[] dm = d.binMasses();
            for (int b = 0; b < 10; b++) {
                neumaierAdd(mass, compensation, b, dm[b]);
            }
        }
        for (int b = 0; b < 10; b++) {
            mass[b] = (mass[b] + compensation[b]) / members.size();
        }
        return combineAggregated(mass, top.value() / members.size(), means);
    }

    public static EnsembleOrdinalDistribution combineAggregated(double[] meanMasses,
            double topDecileProbability, double[] memberMeans) {
        if (meanMasses.length != 10 || memberMeans.length == 0 || !finiteRate(
                topDecileProbability)) {
            throw new IllegalArgumentException("Invalid ensemble aggregates");
        }
        double[] mass = meanMasses.clone();
        for (int b = 0; b < 10; b++) {
            if (!Double.isFinite(mass[b]) || mass[b] < 0) {
                throw new IllegalArgumentException("Invalid ensemble bin mass");
            }
        }
        CompensatedSum memberMean = new CompensatedSum();
        for (double value : memberMeans) {
            if (!finiteRate(value)) {
                throw new IllegalArgumentException("Invalid member mean");
            }
            memberMean.add(value);
        }
        double meanOfMembers = memberMean.value() / memberMeans.length;
        CompensatedSum disagreement = new CompensatedSum();
        double min = memberMeans[0], max = memberMeans[0];
        for (double x : memberMeans) {
            disagreement.add(square(x - meanOfMembers));
            min = StrictMath.min(min, x);
            max = StrictMath.max(max, x);
        }
        double epistemic = memberMeans.length == 1 ? 0 : StrictMath.sqrt(
                nonnegativeVariance(disagreement.value() / (memberMeans.length - 1)));
        return combineAggregatedUncertainty(mass, topDecileProbability, epistemic, max - min);
    }

    public static EnsembleOrdinalDistribution combineAggregatedUncertainty(double[] meanMasses,
            double topDecileProbability, double epistemicStdDev, double disagreementRange) {
        double[] mass = meanMasses.clone();
        CompensatedSum massTotal = new CompensatedSum();
        CompensatedSum meanSum = new CompensatedSum();
        CompensatedSum entropy = new CompensatedSum();
        for (int b = 0; b < 10; b++) {
            if (!Double.isFinite(mass[b]) || mass[b] < 0) {
                throw new IllegalArgumentException("Invalid ensemble bin mass");
            }
            massTotal.add(mass[b]);
            meanSum.add(mass[b] * center(b));
            if (mass[b] > 0) {
                entropy.add(-mass[b] * StrictMath.log(mass[b]));
            }
        }
        if (StrictMath.abs(massTotal.value() - 1.0) > 1.0e-12 || !finiteRate(topDecileProbability)
                || !Double.isFinite(epistemicStdDev) || epistemicStdDev < 0 || !Double.isFinite(
                disagreementRange) || disagreementRange < 0) {
            throw new IllegalArgumentException("Invalid ensemble aggregates");
        }
        double mean = meanSum.value();
        CompensatedSum variance = new CompensatedSum();
        for (int b = 0; b < 10; b++) {
            variance.add(mass[b] * square(center(b) - mean));
        }
        double low = quantile(mass, .025), high = quantile(mass, .975);
        return new EnsembleOrdinalDistribution(mass, mean,
                StrictMath.sqrt(nonnegativeVariance(variance.value())), low, high,
                entropy.value() / StrictMath.log(10), topDecileProbability, epistemicStdDev,
                disagreementRange);
    }

    private static void pav(double[] p) {
        double[] means = new double[9];
        int[] starts = new int[9], sizes = new int[9];
        int blocks = 0;
        for (int i = 0; i < 9; i++) {
            means[blocks] = p[i];
            starts[blocks] = i;
            sizes[blocks++] = 1;
            while (blocks > 1 && Double.compare(means[blocks - 2], means[blocks - 1]) < 0) {
                int n = sizes[blocks - 2] + sizes[blocks - 1];
                means[blocks - 2] =
                        (means[blocks - 2] * sizes[blocks - 2] + means[blocks - 1] * sizes[blocks
                                - 1]) / n;
                sizes[blocks - 2] = n;
                blocks--;
            }
        }
        for (int b = 0; b < blocks; b++) {
            for (int i = starts[b]; i < starts[b] + sizes[b]; i++) {
                p[i] = means[b];
            }
        }
    }

    private static double quantile(double[] mass, double q) {
        CompensatedSum cumulative = new CompensatedSum();
        for (int b = 0; b < 10; b++) {
            cumulative.add(mass[b]);
            if (cumulative.value() >= q) {
                return center(b);
            }
        }
        return .95;
    }

    private static double center(int b) {
        return .05 + .10 * b;
    }

    private static double square(double x) {
        return x * x;
    }

    private static boolean finiteRate(double x) {
        return Double.isFinite(x) && x >= 0 && x <= 1;
    }

    private static double nonnegativeVariance(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite variance");
        }
        if (value < 0 && value >= -1.0e-15) {
            return 0;
        }
        if (value < 0) {
            throw new IllegalArgumentException("Negative variance");
        }
        return value;
    }

    private static void neumaierAdd(double[] sums, double[] corrections, int index, double value) {
        double current = sums[index];
        double next = current + value;
        corrections[index] +=
                StrictMath.abs(current) >= StrictMath.abs(value) ? (current - next) + value
                        : (value - next) + current;
        sums[index] = next;
    }

    private ScenarioOrdinalTargets() {
    }

    private static final class CompensatedSum {

        private double sum;
        private double correction;

        void add(double value) {
            double next = sum + value;
            correction += StrictMath.abs(sum) >= StrictMath.abs(value) ? (sum - next) + value
                    : (value - next) + sum;
            sum = next;
        }

        double value() {
            return sum + correction;
        }
    }
}
