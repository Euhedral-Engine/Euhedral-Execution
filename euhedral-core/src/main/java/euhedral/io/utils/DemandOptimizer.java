package euhedral.io.utils;

public class DemandOptimizer {

    /// Estimates how many additional items should be requested based on observed drain rate,
    /// arrival latency, variance, and remaining memory budget.
    ///
    /// Uses Little’s Law (`drainRate * latency`) as the baseline demand estimate and adds a
    /// variance-derived safety buffer to reduce the risk of underrun during bursty workloads.
    ///
    /// The final request count is capped by the remaining byte budget.
    public static long getDemand(double drainRate, double arrivalLatency, double drainRateVariance,
            double latencyVariance, long currentCount, long itemByteSize, long maxBytes) {
        long availableBytes = maxBytes - (currentCount * itemByteSize);
        if (availableBytes <= 0 || itemByteSize <= 0) {
            return 0;
        }

        double baseDemand = drainRate * arrivalLatency;

        // Total Variance
        double term1 = arrivalLatency * (arrivalLatency * drainRateVariance);
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
