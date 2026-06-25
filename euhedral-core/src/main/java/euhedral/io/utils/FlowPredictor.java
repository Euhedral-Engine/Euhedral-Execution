package euhedral.io.utils;

import euhedral.hashing.HasherApi;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("unused")
public class FlowPredictor {

    private final int mask;
    private final double decayFactor;

    private final double[] sumW;
    private final double[] sumY;
    private final double[] sumY2;

    private long rand = HasherApi.mix(ThreadLocalRandom.current().nextLong());

    public FlowPredictor(int buckets, double decayFactor) {
        buckets = Integer.highestOneBit(buckets) << 6;
        if(buckets == 0) {
            buckets = 1 << 16;
        }

        this.decayFactor = decayFactor;
        this.mask = buckets - 1;

        this.sumW = new double[buckets];
        this.sumY = new double[buckets];
        this.sumY2 = new double[buckets];
    }

    public void record(double x, double y) {
        int idx = getIndex(x);

        this.sumW[idx] = (this.sumW[idx] * this.decayFactor) + 1.0;
        this.sumY[idx] = (this.sumY[idx] * this.decayFactor) + y;
        this.sumY2[idx] = (this.sumY2[idx] * this.decayFactor) + (y * y);
    }

    public double predictY(double x) {
        int idx = getIndex(x);

        double weight = this.sumW[idx];

        if(weight < 1e-6) {
            return 0.0;
        }

        return this.sumY[idx] / weight;
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
        double scorePlus = mean(xPlus) + stdDev(xPlus);
        double scoreMinus = mean(xMinus) + stdDev(xMinus);

        if(scorePlus > scoreCurrent && scorePlus >= scoreMinus) {
            return xPlus;
        } else if (scoreMinus > scoreCurrent) {
            return xMinus;
        }

        return currentX;
    }

    public double mean(double x) {
        int idx = getIndex(x);

        double w = this.sumW[idx];
        if(w < 1e-6) {
            return 0.0;
        }

        return this.sumY[idx] / w;
    }

    public double variance(double x) {
        int idx = getIndex(x);

        double w = this.sumW[idx];
        if(w < 1e-6) {
            return 0.0;
        }

        double mean = this.sumY[idx] / w;
        double mean2 = this.sumY2[idx] / w;

        return Math.max(0.0, mean2 - (mean * mean));
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
        Arrays.fill(this.sumW, 0.0);
        Arrays.fill(this.sumY, 0.0);
        Arrays.fill(this.sumY2, 0.0);
    }

    private int getIndex(double x) {
        int exponent = Math.getExponent(x);
        double normalized = x / Math.scalb(1.0, exponent);

        int mantissa = (int)((normalized - 1.0) * 16.0);

        int idx = (exponent << 4) | mantissa;
        return idx & this.mask;
    }
}
