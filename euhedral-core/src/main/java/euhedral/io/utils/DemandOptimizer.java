package euhedral.io.utils;

public class DemandOptimizer {

    public static long getDemand(double drainRate, double latency, double drainRateVariance,
            double latencyVariance, long currentCount, long itemByteSize, long maxBytes) {
        long availableBytes = maxBytes - (currentCount * itemByteSize);
        if (availableBytes <= 0 || itemByteSize <= 0) {
            return 0;
        }

        double baseDemand = drainRate * latency;

        // Total Variance
        double term1 = latency * (latency * drainRateVariance);
        double term2 = drainRate * (drainRate * latencyVariance);
        double totalVariance = term1 + term2;

        double sqrtVar = Math.sqrt(totalVariance);

        // Z-score (2.326)
        double buffer = sqrtVar * 2.326;

        long targetItems = (long) (baseDemand + buffer);
        long toRequest = targetItems - currentCount;

        if (toRequest <= 0) {
            return 0;
        }

        long maxItems = availableBytes / itemByteSize;
        return Math.min(toRequest, maxItems);
    }
}
