package euhedral.common.io.dispatch.utils;

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
    private long dynamicWindowNs = 3_000_000_000L;
    private long prevWindowCount = 0;
    private long currWindowCount = 0;
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
    private long rollingRemainder = 0;
    private long rateRemainder, varRateRemainder, unitRemainder, varUnitRemainder, intervalRemainder, varIntervRemainder;

    public FlowRecorder() {
        this(Duration.ofMillis(100), Duration.ofSeconds(5));
    }


    public FlowRecorder(@NonNull Duration minWindowSize, @NonNull Duration maxWindowSize) {
        this.minWindowNs = minWindowSize.toNanos();
        this.maxWindowNs = maxWindowSize.toNanos();
        this.windowStartNs = System.nanoTime();
        this.lastRecordingTime = windowStartNs;
    }

    public void record(long units) {
        record(System.nanoTime(), units);
    }

    public void record(long now, long units) {
        long interval = (now - lastRecordingTime);
        if (now <= 0) {
            return;
        }

        lastRecordingTime = now;
        currWindowCount += units;
        lastInterval = interval;

        int alpha = getRatio(interval, averageInterval + dynamicWindowNs);
        alpha = Math.max(655, Math.min(SCALE, alpha));

        long decayDelta = (-rollingSum) * alpha;
        long totalDecay = decayDelta + rollingRemainder;

        long appliedDecay = totalDecay >> SHIFT;
        rollingRemainder = totalDecay & MASK;

        rollingSum += appliedDecay;
        rollingSum += units;

        long currentUnitsScaled = units << SHIFT;
        long currentRateScaled = getRatio(currentUnitsScaled, interval);

        long elapsed = now - lastRecordingTime;
        if (elapsed > dynamicWindowNs) {
            int decayAlpha = getRatio(dynamicWindowNs, elapsed + dynamicWindowNs);
            averageRate = ewma(averageRate, 0, decayAlpha, EwmaRemainder.RATE);
            averageUnits = ewma(averageUnits, 0, decayAlpha, EwmaRemainder.UNIT);
            averageInterval = ewma(averageInterval, 0, decayAlpha, EwmaRemainder.INTERVAL);
            rateVariation = ewma(rateVariation, 0, decayAlpha, EwmaRemainder.VAR_RATE);
            unitVariation = ewma(unitVariation, 0, decayAlpha, EwmaRemainder.VAR_UNIT);
            intervalVariation = ewma(averageInterval, 0, decayAlpha, EwmaRemainder.VAR_INTERVAL);
        }

        updateMetrics(currentRateScaled, currentUnitsScaled, interval, alpha);

        if (averageRate > 0) {
            int varRatio = getRatio(rateVariation, averageRate);
            long baseWindow = interval * 10;
            // Shrink window as jitter (variation) increases
            dynamicWindowNs = Math.clamp(baseWindow - ((baseWindow * varRatio) >> (SHIFT + 1)),
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
        averageRate = ewma(averageRate, currentRate, alpha, EwmaRemainder.RATE);
        rateVariation = ewma(rateVariation, Math.abs(currentRate - averageRate), alpha,
                EwmaRemainder.VAR_RATE);

        averageUnits = ewma(averageUnits, currentUnits, alpha, EwmaRemainder.UNIT);
        unitVariation = ewma(unitVariation, Math.abs(currentUnits - averageUnits), alpha,
                EwmaRemainder.VAR_UNIT);

        averageInterval = ewma(averageInterval, currentInterval, alpha, EwmaRemainder.INTERVAL);
        intervalVariation = ewma(intervalVariation, Math.abs(currentInterval - averageInterval),
                alpha, EwmaRemainder.VAR_INTERVAL);
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
        if (num >= den << SHIFT) {
            return SCALE;
        }
        return (int) ((num << SHIFT) / den);
    }

    public long getAverageUnits() {
        return (averageUnits + unitVariation) >> SHIFT;
    }

    public long getAverageRate() {
        return (averageRate + rateVariation) >> SHIFT;
    }

    public long getAverageInterval() {
        return (averageInterval + intervalVariation) >> SHIFT;
    }

    public double getRollingAveragePerRecording(long now) {
        long count = getEffectiveMeasurementWindowCount(now) >> SHIFT;
        return count == 0 ? 0.0 : (double) rollingSum / count;
    }

    public long getEffectiveMeasurementWindowCount(long now) {
        long elapsed = now - windowStartNs;

        if (elapsed >= dynamicWindowNs) {
            return currWindowCount << SHIFT;
        }

        // progress = (elapsed * SCALE) / windowSize
        long progress = (elapsed << SHIFT) / dynamicWindowNs;

        // effective = (prev * (1 - progress)) + current
        long prevContribution = (prevWindowCount * (SCALE - progress));
        return prevContribution + (currWindowCount << SHIFT);
    }

    public long getThroughputNs() {
        if (averageRate == 0) {
            return 0;
        }

        long numerator = (averageUnits + unitVariation) >> SHIFT;
        long denominator = (averageRate + rateVariation) >> SHIFT;

        return (denominator <= 0) ? 0 : numerator / denominator;
    }

    public double getUnitCV() {
        long base = averageUnits;
        long variance = unitVariation;
        return base == 0 ? 0.0 : (double) variance / base;
    }

    public long estimateFutureUnits(long lookAheadNs) {
        return ((averageRate + rateVariation) * lookAheadNs) >> SHIFT;
    }

    /**
     * Returns an estimate of how much time it would take to record the units
     *
     * @param units Desired units
     * @return Time needed to wait for them
     */
    public long estimateIntervalNsForUnits(long units) {
        long avgInterval = (averageInterval + intervalVariation) >> SHIFT;
        long avgUnits = (averageUnits + unitVariation) >> SHIFT;

        if (avgUnits <= 0) {
            return avgInterval;
        }

        return (units * avgInterval) / avgUnits;
    }

    /**
     * Returns the queue estimate scaled by the provided 'scaler'.
     */
    public long getVegasQueueEstimate(long units, long scaler) {
        long unitsScaled = units << SHIFT;

        if (unitsScaled <= averageUnits || averageUnits <= 0) {
            return 0L;
        }

        long target = averageUnits + unitVariation;

        // Buffer: ~6% of Average to prevent jitter
        long buffer = averageUnits >> 4;

        long minTarget = averageUnits + buffer;
        if (target < minTarget) {
            target = minTarget;
        }

        long denominator = target - averageUnits;

        if (denominator < (1L << (SHIFT - 8))) {
            return scaler;
        }

        long diff = unitsScaled - averageUnits;

        return (scaler * diff) / denominator;
    }

    private enum EwmaRemainder {
        RATE, VAR_RATE, UNIT, VAR_UNIT, INTERVAL, VAR_INTERVAL
    }
}
