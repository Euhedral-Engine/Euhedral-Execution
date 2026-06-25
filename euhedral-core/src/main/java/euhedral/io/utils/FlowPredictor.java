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

    private long rand = HasherApi.mix(ThreadLocalRandom.current().nextLong());

    public FlowPredictor(int buckets, double decayFactor) {
        buckets = Integer.highestOneBit(buckets);
        this.decayFactor = decayFactor;
        this.mask = buckets - 1;

        this.sumY = new double[buckets];
        this.sumW = new double[buckets];
    }

    public void record(double x, double y) {
        int idx = getIndex(x);

        this.sumW[idx] = (this.sumW[idx] * this.decayFactor) + 1.0;
        this.sumY[idx] = (this.sumY[idx] * this.decayFactor) + y;
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

        double yCurrent = predictY(currentX);
        double yPlus = predictY(xPlus);
        double yMinus = predictY(xMinus);

        if(yPlus > yCurrent && yPlus >= yMinus) {
            return xPlus;
        } else if (yMinus > yCurrent) {
            return xMinus;
        }

        return currentX;
    }

    public void reset() {
        Arrays.fill(this.sumW, 0);
        Arrays.fill(this.sumY, 0);
    }
    private int getIndex(double x) {
        if(x <= 0) {
            return 0;
        }

        int exponent = Math.getExponent(x);
        return exponent & this.mask;
    }
}
