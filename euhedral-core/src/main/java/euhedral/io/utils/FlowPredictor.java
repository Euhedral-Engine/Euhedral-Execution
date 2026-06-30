package euhedral.io.utils;

import euhedral.hashing.HasherApi;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("unused")
public class FlowPredictor {

    private final int mask;
    private final double alpha;

    private final int[] exponent;
    private final double[] mean;
    private final double[] variance;

    private final boolean optimizeMax;

    private long rand = HasherApi.mix(ThreadLocalRandom.current().nextLong());

    public FlowPredictor(int buckets, double alpha, boolean optimizeMax) {
        buckets = Integer.highestOneBit(buckets) << 6;
        if(buckets == 0) {
            buckets = 1 << 16;
        }

        this.alpha = alpha;
        this.mask = buckets - 1;

        this.exponent = new int[buckets];
        this.mean = new double[buckets];
        this.variance = new double[buckets];
        Arrays.fill(this.exponent, Integer.MIN_VALUE);

        this.optimizeMax = optimizeMax;
    }

    public void record(double x, double y) {
        int idx = getIndex(x);

        int exponent = Math.getExponent(x);
        if(this.exponent[idx] == Integer.MIN_VALUE || this.exponent[idx] != exponent) {
            this.exponent[idx] = exponent;
            this.mean[idx] = y;
            this.variance[idx] = 0;
        } else {
            double delta = y - this.mean[idx];
            double stdDev = Math.sqrt(this.variance[idx]);

            if(Math.abs(delta) <= stdDev * 2) {
                this.mean[idx] += this.alpha * delta;
                this.variance[idx] = (1 - this.alpha) * (this.variance[idx] + this.alpha * delta * delta);
            } else {
                this.mean[idx] = MathFunctions.ewma(this.mean[idx], y, 0.2);
                this.variance[idx] = 0.8 * this.variance[idx] + 0.2 * delta * delta;
            }
        }
    }

    public double computeNextBestX(double currentX, double stepSize, double explorationRate) {
        this.rand = HasherApi.mix(this.rand + 1);
        double rand = (double) (this.rand & Long.MAX_VALUE) / Long.MAX_VALUE;

        if(rand < explorationRate) {
            if(rand < (explorationRate * 0.5)) {
                return currentX + stepSize * 2.0;
            } else {
                return Math.max(1.0, currentX - stepSize);
            }
        }

        double xPlus = currentX + stepSize;
        double xMinus = Math.max(1.0, currentX - stepSize);

        double scoreCurrent = mean(currentX) + stdDev(currentX);
        double scorePlus = 0;
        double scoreMinus = 0;

        int exponent = Math.getExponent(currentX);
        if(Math.abs(exponent - Math.getExponent(xPlus)) <= 1) {
            scorePlus = mean(xPlus) + stdDev(xPlus);
        }
        if(Math.abs(exponent - Math.getExponent(xMinus)) <= 1) {
            scoreMinus = mean(xMinus) + stdDev(xMinus);
        }

        if(this.optimizeMax) {
            if(scorePlus > scoreCurrent && scorePlus >= scoreMinus) {
                return xPlus;
            } else if (scoreMinus > scoreCurrent) {
                return xMinus;
            }
        } else {
            if(scorePlus < scoreCurrent && scorePlus <= scoreMinus) {
                return xPlus;
            } else if (scoreMinus < scoreCurrent) {
                return xMinus;
            }
        }

        return currentX;
    }

    public double mean(double x) {
        int idx = getIndex(x);
        return this.mean[idx];
    }

    public double variance(double x) {
        int idx = getIndex(x);
        return this.variance[idx];
    }

    public double stdDev(double x) {
        return Math.sqrt(variance(x));
    }

    public double cv(double x) {
        double mean = mean(x);

        if(mean < 1e-6) {
            return Double.POSITIVE_INFINITY;
        }

        return stdDev(x) / mean;
    }

    public void reset() {
        Arrays.fill(this.exponent, Integer.MIN_VALUE);
        Arrays.fill(this.mean, 0.0);
        Arrays.fill(this.variance, 0.0);
    }

    private int getIndex(double x) {
        int exponent = Math.getExponent(x);
        double normalized = x / Math.scalb(1.0, exponent);

        int mantissa = (int)((normalized - 1.0) * 16.0);

        int idx = (exponent << 4) | mantissa;
        return idx & this.mask;
    }
}
