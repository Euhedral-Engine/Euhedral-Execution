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

    private enum EwmaRemainder {
        RATE, VAR_RATE, UNIT, VAR_UNIT
    }

    private final long minWindowNs, maxWindowNs;

    private long dynamicWindowNs = 3_000_000_000L;

    private long prevWindowCount = 0;
    private long currWindowCount = 0;

    private long windowStartNs;

    @Getter
    private long lastRecordingTime;
    @Getter
    private long lastInterval;

    @Getter
    private long averageRate, rateVariation = 0L;

    @Getter
    private long averageUnits, unitVariation = 0L;
    @Getter
    private long rollingSum = 0;
    private long rollingRemainder = 0;

    private long rateRemainder, varRateRemainder, unitRemainder, varUnitRemainder;


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

        int alpha = getRatio(interval, interval + dynamicWindowNs);
        alpha = Math.max(655, Math.min(SCALE, alpha));

        long decayDelta = (-rollingSum) * alpha;
        long totalDecay = decayDelta + rollingRemainder;

        long appliedDecay = totalDecay >> SHIFT;
        rollingRemainder = totalDecay & MASK;

        rollingSum += appliedDecay;
        rollingSum += units;

        long currentRateScaled = getRatio(units << SHIFT, interval);
        long currentUnitsScaled = units << SHIFT;

        updateMetrics(currentRateScaled, currentUnitsScaled, alpha);

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

    private void updateMetrics(long currentRate, long currentUnits, int alpha) {
        averageRate = ewma(averageRate, currentRate, alpha, EwmaRemainder.RATE);
        rateVariation = ewma(rateVariation, Math.abs(currentRate - averageRate), alpha, EwmaRemainder.VAR_RATE);

        averageUnits = ewma(averageUnits, currentUnits, alpha, EwmaRemainder.UNIT);
        unitVariation = ewma(unitVariation, Math.abs(currentUnits - averageUnits), alpha, EwmaRemainder.VAR_UNIT);
    }

    private long ewma(long oldVal, long newVal, int alpha, EwmaRemainder type) {
        long delta = (newVal - oldVal) * alpha;

        long remainder = switch(type) {
            case RATE -> rateRemainder;
            case VAR_RATE -> varRateRemainder;
            case UNIT -> unitRemainder;
            case VAR_UNIT -> varUnitRemainder;
        };

        long totalDelta = delta + remainder;
        long appliedDelta = totalDelta >> SHIFT;

        long newRemainder = totalDelta & MASK;
        switch(type) {
            case RATE -> rateRemainder = newRemainder;
            case VAR_RATE -> varRateRemainder = newRemainder;
            case UNIT -> unitRemainder = newRemainder;
            case VAR_UNIT -> varUnitRemainder = newRemainder;
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

    public double getRollingAveragePerRecording(long now) {
        long count = getEffectiveMeasurementWindowCount(now) >> SHIFT;
        return count == 0 ? 0.0 : (double) rollingSum / count;
    }

    public long getThroughputNs() {
        if (averageRate == 0) {
            return 0;
        }

        long numerator = averageUnits + unitVariation;
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
}
