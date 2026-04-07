package euhedral.io.utils;

import java.time.Duration;
import jdk.internal.vm.annotation.Contended;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

@Contended
public class FlowRecorder {

    // 10 bits of precision (1024) for smoothing factors
    public static final int SHIFT = 16;
    public static final int SCALE = 1 << SHIFT;
    public static final int MASK = SCALE - 1;
    private final long minWindowNs, maxWindowNs;
    private long dynamicWindowNs = 0;
    private long prevWindowCount = 0;
    private long currWindowCount = 0;
    @Getter
    private long windowStartNs;
    @Getter
    private long lastRecordingTime;
    @Getter
    private long lastInterval;
    private long averageRate, rateVariation = 0L;
    private long averageUnits, unitVariation = 0L;
    private long averageInterval, intervalVariation = 0L;
    @Getter
    private long rollingSum = 0;
    private long rateRemainder, varRateRemainder, unitRemainder, varUnitRemainder, intervalRemainder, varIntervRemainder;

    public FlowRecorder() {
        this(Duration.ofNanos(10_000), Duration.ofMillis(1));
    }


    public FlowRecorder(@NonNull Duration minWindowSize, @NonNull Duration maxWindowSize) {
        this.minWindowNs = minWindowSize.toNanos();
        this.maxWindowNs = maxWindowSize.toNanos();
        this.dynamicWindowNs = minWindowNs;
        this.windowStartNs = System.nanoTime();
        this.lastRecordingTime = windowStartNs;
    }

    public void decay(long now) {
        if (now <= 0) {
            return;
        }

        long interval = (now - lastRecordingTime);
        int alpha = getRatio(interval, dynamicWindowNs);
        alpha = Math.max(1024, Math.min(SCALE, alpha));
        decay(now, alpha);
    }

    private void decay(long now, long alpha) {
        long decayDelta = (-rollingSum) * alpha;
        long appliedDecay = decayDelta >> SHIFT;

        rollingSum += appliedDecay;

        long elapsed = now - lastRecordingTime;
        if (elapsed > dynamicWindowNs) {
            int decayAlpha = getRatio(dynamicWindowNs, elapsed);
            decayAlpha = Math.min(decayAlpha, SCALE);
            averageRate = ewma(averageRate, 0, decayAlpha, EwmaRemainder.RATE);
            averageUnits = ewma(averageUnits, 0, decayAlpha, EwmaRemainder.UNIT);
            averageInterval = ewma(averageInterval, 0, decayAlpha, EwmaRemainder.INTERVAL);
            rateVariation = ewma(rateVariation, 0, decayAlpha, EwmaRemainder.VAR_RATE);
            unitVariation = ewma(unitVariation, 0, decayAlpha, EwmaRemainder.VAR_UNIT);
            intervalVariation = ewma(averageInterval, 0, decayAlpha, EwmaRemainder.VAR_INTERVAL);
        }
    }

    public void reset() {
        dynamicWindowNs = 3_000_000_000L;
        prevWindowCount = 0;
        currWindowCount = 0;
        windowStartNs = System.nanoTime();
        lastRecordingTime = windowStartNs;
        lastInterval = 0;
        averageRate = 0;
        rateVariation = 0;
        averageUnits = 0;
        unitVariation = 0;
        averageInterval = 0;
        intervalVariation = 0;
        rollingSum = 0;
        rateRemainder = 0;
        varRateRemainder = 0;
        unitRemainder = 0;
        varUnitRemainder = 0;
        intervalRemainder = 0;
        varIntervRemainder = 0;
    }

    private long ewma(long oldVal, long newVal, int alpha, EwmaRemainder type) {
        long delta = (newVal - oldVal) * alpha;

        long remainder = switch (type) {
            case RATE -> rateRemainder;
            case VAR_RATE -> varRateRemainder;
            case UNIT -> unitRemainder;
            case VAR_UNIT -> varUnitRemainder;
            case INTERVAL -> intervalRemainder;
            case VAR_INTERVAL -> varIntervRemainder;
        };

        long totalDelta = delta + remainder;
        long appliedDelta = totalDelta >> SHIFT;

        long newRemainder = totalDelta & MASK;
        switch (type) {
            case RATE -> rateRemainder = newRemainder;
            case VAR_RATE -> varRateRemainder = newRemainder;
            case UNIT -> unitRemainder = newRemainder;
            case VAR_UNIT -> varUnitRemainder = newRemainder;
            case INTERVAL -> intervalRemainder = newRemainder;
            case VAR_INTERVAL -> varIntervRemainder = newRemainder;
        }

        return oldVal + appliedDelta;
    }

    private int getRatio(long num, long den) {
        if (den <= 0) {
            return 0;
        }
        if (num >= den) {
            return SCALE;
        }
        return (int) ((num << SHIFT) / den);
    }

    public void record(long units) {
        record(System.nanoTime(), units);
    }

    public void record(long now, long units) {
        long interval = (now - lastRecordingTime);
        if (now <= 0) {
            return;
        }

        int alpha = getRatio(interval, dynamicWindowNs);
        alpha = Math.max(1024, Math.min(SCALE, alpha));

        decay(now, alpha);

        lastRecordingTime = now;
        currWindowCount++;
        lastInterval = interval;
        rollingSum += units;

        long currentUnitsScaled = units << SHIFT;
        long currentRateScaled = currentUnitsScaled / interval;

        updateMetrics(currentRateScaled, currentUnitsScaled, interval, alpha);

        if (averageRate > 0) {
            int varRatio = getRatio(rateVariation, averageRate);
            long baseWindow = interval * 10;
            long adjustment = (baseWindow * varRatio) >> (SHIFT + 1);
            // Shrink window as jitter (variation) increases
            dynamicWindowNs = Math.clamp(baseWindow - adjustment,
                    minWindowNs, maxWindowNs);
        }

        if (now - windowStartNs > dynamicWindowNs) {
            prevWindowCount = currWindowCount;
            currWindowCount = 0;
            windowStartNs = now;
        }
    }

    private void updateMetrics(long currentRate, long currentUnits, long currentInterval,
            int alpha) {
        long prevUnits = averageUnits;

        averageRate = ewma(averageRate, currentRate, alpha, EwmaRemainder.RATE);
        averageUnits = ewma(averageUnits, currentUnits, alpha, EwmaRemainder.UNIT);
        averageInterval = ewma(averageInterval, currentInterval, alpha, EwmaRemainder.INTERVAL);

        if(prevUnits != 0) {
            rateVariation = ewma(rateVariation, Math.abs(currentRate - averageRate) / 2, alpha,
                    EwmaRemainder.VAR_RATE);
            unitVariation = ewma(unitVariation, Math.abs(currentUnits - averageUnits) / 2, alpha,
                    EwmaRemainder.VAR_UNIT);
            intervalVariation = ewma(intervalVariation, Math.abs(currentInterval - averageInterval) / 2,
                    alpha, EwmaRemainder.VAR_INTERVAL);
        }

    }

    public double getAverageUnits() {
        return (averageUnits >> SHIFT) + unitRemainder / (double) SCALE;
    }

    public double getUnitVariation() {
        return (unitVariation >> SHIFT) + varUnitRemainder / (double) SCALE;
    }

    public double getAverageRate() {
        return (averageRate >> SHIFT) + rateRemainder / (double) SCALE;
    }

    public double getRateVariation() {
        return (rateVariation >> SHIFT) + varRateRemainder / (double) SCALE;
    }

    public double getAverageInterval() {
        return (averageInterval >> SHIFT) + intervalRemainder / (double) SCALE;
    }

    public double getIntervalVariation() {
        return (intervalVariation >> SHIFT) + varIntervRemainder / (double) SCALE;
    }

    public double getRollingAverage(long now) {
        long count = getEffectiveMeasurementWindowCount(now);
        if(count == 0) {
            return 0;
        }
        return (double) rollingSum / count;
    }

    public long getEffectiveMeasurementWindowCount(long now) {
        long elapsed = now - windowStartNs;

        if (elapsed >= dynamicWindowNs) {
            return currWindowCount;
        }

        long progress = (elapsed << SHIFT) / dynamicWindowNs;
        long invProgress = SCALE - progress;

        // effective = (prev * (1 - progress)) + current
        long prevContribution = (prevWindowCount * invProgress) >> SHIFT;
        long currContribution = (currWindowCount * progress) >> SHIFT;

        return prevContribution + currContribution;
    }

    public long getThroughputNs() {
        if (averageRate <= 0) {
            return 0;
        }

        return (SCALE) / averageRate;
    }

    public double getUnitCV() {
        double base = getAverageUnits();
        double variance = getUnitVariation();
        return base == 0 ? 0.0 : variance / base;
    }

    public double getRateCV() {
        double base = getAverageRate();
        double variance = getRateVariation();
        return base == 0 ? 0.0 : variance / base;
    }

    public double getIntervalCV() {
        double base = getAverageInterval();
        double variance = getIntervalVariation();
        return base == 0 ? 0.0 : variance / base;
    }

    /**
     * Returns the queue estimate scaled by the provided 'scaler'.
     */
    public double getVegasQueueEstimate(double units, long scaler) {
        double averageUnits = getAverageUnits();
        if (units <= averageUnits || averageUnits <= 0) {
            return 0L;
        }

        double unitVariation = getUnitVariation();
        double target = averageUnits + unitVariation;

        // Buffer: ~6% of Average to prevent jitter
        double buffer = averageUnits * 0.06;

        double minTarget = averageUnits + buffer;
        if (target < minTarget) {
            target = minTarget;
        }

        double diff = units - averageUnits;
        double denominator = target - averageUnits;

        return (scaler * diff) / denominator;
    }

    private enum EwmaRemainder {
        RATE, VAR_RATE, UNIT, VAR_UNIT, INTERVAL, VAR_INTERVAL
    }
}
