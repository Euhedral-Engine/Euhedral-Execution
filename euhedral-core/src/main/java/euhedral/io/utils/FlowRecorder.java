package euhedral.io.utils;

import static euhedral.io.utils.MathFunctions.clampInt;
import static euhedral.io.utils.MathFunctions.clampLong;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.Getter;
import org.jspecify.annotations.NonNull;

/// This class calculates rates, units, intervals, and variances of each for any type of unit using
/// fixed-point arithmetic.
///
/// It has a built-in dynamic sizing and sliding window that responds to jitter.
public class FlowRecorder {

    // 16 bits of precision
    public static final int SHIFT = 16;
    public static final int SCALE = 1 << SHIFT;
    public static final double SCALE_INV = 1.0 / SCALE;
    public static final int MASK = SCALE - 1;

    /**
     * Returns the queue estimate scaled by the provided 'scaler'.
     */
    public static double getVegasQueueEstimate(FlowSnapshot flowSnapshot, long scaler) {
        double units = flowSnapshot.lastRecordedUnits;
        double averageUnits = flowSnapshot.avgUnits;
        if (units <= averageUnits || averageUnits <= 0) {
            return 0L;
        }

        double unitVariation = flowSnapshot.unitVariation;
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
    private final long minWindowNs, maxWindowNs;
    private final AtomicBoolean wip = new AtomicBoolean();
    @Getter
    private final FlowSnapshot flowSnapshot = new FlowSnapshot();
    @Getter
    private long dynamicWindowNs;
    private long prevWindowCount = 0;
    private long currWindowCount = 0;
    @Getter
    private long windowStartNs;
    @Getter
    private long lastRecordingTime;
    @Getter
    private long lastInterval;
    @Getter
    private long lastRecordedUnits;
    private long averageUnitsOverTime, uotVariation = 0L, uotTrend = 0L;
    private long averageUnits, unitVariation = 0L, unitTrend = 0L;
    private long averageInterval, intervalVariation = 0L, intervalTrend = 0L;
    @Getter
    private long rollingSum = 0;
    private long uotRemainder, varUoTRemainder, uotTrendRemainder, unitRemainder, varUnitRemainder, unitTrendRemainder,
            intervalRemainder, varIntervalRemainder, intervalTrendRemainder;

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

    public void decay(long now, boolean threadSafe) {
        if (now <= 0) {
            return;
        }

        if (threadSafe) {
            acquireLock();
        }
        long interval = (now - lastRecordingTime);
        int alpha = getRatio(interval, dynamicWindowNs);
        alpha = clampInt(alpha, 1024, SCALE);
        decay(now, alpha);
        if (threadSafe) {
            releaseLock();
        }
    }

    private void decay(long now, long alpha) {
        long decayDelta = (-rollingSum) * alpha;
        long appliedDecay = decayDelta >> SHIFT;

        rollingSum += appliedDecay;

        long elapsed = now - lastRecordingTime;
        if (elapsed > dynamicWindowNs) {
            int decayAlpha = getRatio(dynamicWindowNs, elapsed);
            decayAlpha = Math.min(decayAlpha, SCALE);
            averageUnitsOverTime = ewma(averageUnitsOverTime, 0, decayAlpha, EwmaRemainder.UOT);
            averageUnits = ewma(averageUnits, 0, decayAlpha, EwmaRemainder.UNIT);
            averageInterval = ewma(averageInterval, 0, decayAlpha, EwmaRemainder.INTERVAL);

            uotVariation = ewma(uotVariation, 0, decayAlpha, EwmaRemainder.VAR_UOT);
            unitTrend = ewma(unitTrend, 0, decayAlpha, EwmaRemainder.UOT_TREND);
            unitVariation = ewma(unitVariation, 0, decayAlpha, EwmaRemainder.VAR_UNIT);
            unitTrend = ewma(unitTrend, 0, decayAlpha, EwmaRemainder.UNIT_TREND);
            intervalVariation = ewma(averageInterval, 0, decayAlpha, EwmaRemainder.VAR_INTERVAL);
            intervalTrend = ewma(intervalTrend, 0, decayAlpha, EwmaRemainder.INTERVAL_TREND);
        }
    }

    private long ewma(long oldVal, long newVal, int alpha, EwmaRemainder type) {
        long delta = (newVal - oldVal) * alpha;

        long remainder = switch (type) {
            case UOT -> uotRemainder;
            case VAR_UOT -> varUoTRemainder;
            case UOT_TREND -> uotTrendRemainder;
            case UNIT -> unitRemainder;
            case VAR_UNIT -> varUnitRemainder;
            case UNIT_TREND -> unitTrendRemainder;
            case INTERVAL -> intervalRemainder;
            case VAR_INTERVAL -> varIntervalRemainder;
            case INTERVAL_TREND -> intervalTrendRemainder;
        };

        long totalDelta = delta + remainder;
        long appliedDelta = totalDelta >> SHIFT;

        long newRemainder = totalDelta & MASK;
        switch (type) {
            case UOT -> uotRemainder = newRemainder;
            case VAR_UOT -> varUoTRemainder = newRemainder;
            case UOT_TREND -> uotTrendRemainder = newRemainder;
            case UNIT -> unitRemainder = newRemainder;
            case VAR_UNIT -> varUnitRemainder = newRemainder;
            case UNIT_TREND -> unitTrendRemainder = newRemainder;
            case INTERVAL -> intervalRemainder = newRemainder;
            case VAR_INTERVAL -> varIntervalRemainder = newRemainder;
            case INTERVAL_TREND -> intervalTrendRemainder = newRemainder;
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

    private void acquireLock() {
        while (!wip.compareAndSet(false, true)) {
            Thread.onSpinWait();
        }
    }

    private void releaseLock() {
        wip.set(false);
    }

    public void reset(boolean threadSafe) {
        if (threadSafe) {
            acquireLock();
        }
        dynamicWindowNs = this.minWindowNs;
        prevWindowCount = 0;
        currWindowCount = 0;
        windowStartNs = System.nanoTime();
        lastRecordingTime = windowStartNs;
        lastRecordedUnits = 0;
        lastInterval = 0;
        rollingSum = 0;

        averageUnits = 0;
        unitVariation = 0;
        unitTrend = 0;
        unitRemainder = 0;
        varUnitRemainder = 0;
        unitTrendRemainder = 0;

        averageUnitsOverTime = 0;
        uotVariation = 0;
        uotTrend = 0;
        uotRemainder = 0;
        varUoTRemainder = 0;
        uotTrendRemainder = 0;

        averageInterval = 0;
        intervalVariation = 0;
        intervalTrend = 0;
        intervalRemainder = 0;
        varIntervalRemainder = 0;
        intervalTrendRemainder = 0;

        if (threadSafe) {
            releaseLock();
        }
    }

    public void record(long units, boolean threadSafeRecord) {
        record(System.nanoTime(), units, threadSafeRecord);
    }

    public void record(long now, long units, boolean threadSafeRecord) {
        if (threadSafeRecord) {
            acquireLock();
        }

        long interval = (now - lastRecordingTime);
        if (now <= 0 || interval <= 0) {
            if (threadSafeRecord) {
                releaseLock();
            }
            return;
        }
        interval = Math.min(interval, dynamicWindowNs * 2);

        int alpha = getRatio(interval, dynamicWindowNs);
        alpha = clampInt(alpha, 1024, SCALE);

        decay(now, alpha);

        lastRecordingTime = now;
        lastInterval = interval;
        lastRecordedUnits = units;
        currWindowCount++;
        rollingSum += units;

        long currentUnitsScaled = units << SHIFT;
        long currentRateScaled = currentUnitsScaled / interval;

        updateMetrics(currentRateScaled, currentUnitsScaled, interval, alpha);

        if (averageUnitsOverTime > 0) {
            int varRatio = getRatio(uotVariation, averageUnitsOverTime);
            long baseWindow = interval * 10;
            long adjustment = (baseWindow * varRatio) >> (SHIFT + 1);
            // Shrink window as jitter (variation) increases
            dynamicWindowNs = clampLong(baseWindow - adjustment, minWindowNs, maxWindowNs);
        }

        if (now - windowStartNs > dynamicWindowNs) {
            prevWindowCount = currWindowCount;
            currWindowCount = 0;
            windowStartNs = now;
        }
        if (threadSafeRecord) {
            releaseLock();
        }
    }

    private void updateMetrics(long currentRate, long currentUnits, long currentInterval,
            int alpha) {
        long prevUnits = averageUnits;

        averageUnitsOverTime = ewma(averageUnitsOverTime, currentRate, alpha, EwmaRemainder.UOT);
        averageUnits = ewma(averageUnits, currentUnits, alpha, EwmaRemainder.UNIT);
        averageInterval = ewma(averageInterval, currentInterval, alpha, EwmaRemainder.INTERVAL);

        if (prevUnits != 0) {
            uotVariation = ewma(uotVariation, Math.abs(currentRate - averageUnitsOverTime) >> 1,
                    alpha,
                    EwmaRemainder.VAR_UOT);
            uotTrend = ewma(uotTrend, (currentRate - averageUnitsOverTime) >> 1, alpha,
                    EwmaRemainder.UOT_TREND);
            unitVariation = ewma(unitVariation, Math.abs(currentUnits - averageUnits) >> 1, alpha,
                    EwmaRemainder.VAR_UNIT);
            unitTrend = ewma(unitTrend, (currentUnits - averageUnits) >> 1, alpha,
                    EwmaRemainder.UNIT_TREND);
            intervalVariation =
                    ewma(intervalVariation, Math.abs(currentInterval - averageInterval) >> 1, alpha,
                            EwmaRemainder.VAR_INTERVAL);
            intervalTrend =
                    ewma(intervalTrend, (currentInterval - averageInterval) >> 1, alpha,
                            EwmaRemainder.INTERVAL_TREND);
        }

    }

    public void refreshSnapshot(FlowSnapshot flowSnapshot, boolean threadSafe) {
        refreshSnapshot(flowSnapshot, 0, threadSafe);
    }

    public void refreshSnapshot(FlowSnapshot flowSnapshot, long now, boolean threadSafe) {
        if (threadSafe) {
            acquireLock();
        }
        if (now > this.lastRecordingTime) {
            decay(now, false);
        }
        refreshSnapshot(flowSnapshot);
        if (threadSafe) {
            releaseLock();
        }
    }

    private void refreshSnapshot(FlowSnapshot flowSnapshot) {
        flowSnapshot.lastRecordingTimeNs = lastRecordingTime;
        flowSnapshot.lastRecordedUnits = lastRecordedUnits;
        flowSnapshot.lastRecordedInterval = lastInterval;

        flowSnapshot.avgUnits = (averageUnits >> SHIFT) + unitRemainder * SCALE_INV;
        flowSnapshot.unitVariation = (unitVariation >> SHIFT) + varUnitRemainder * SCALE_INV;
        flowSnapshot.unitTrend = (unitTrend >> SHIFT) + unitTrendRemainder * SCALE_INV;
        flowSnapshot.unitCV = flowSnapshot.avgUnits == 0 ? 0.0
                : flowSnapshot.unitVariation / flowSnapshot.avgUnits;

        flowSnapshot.avgUnitsOverTime = (averageUnitsOverTime >> SHIFT) + uotRemainder * SCALE_INV;
        flowSnapshot.unitsOverTimeVariation = (uotVariation >> SHIFT) + varUoTRemainder * SCALE_INV;
        flowSnapshot.unitsOverTimeTrend = (uotTrend >> SHIFT) + uotTrendRemainder * SCALE_INV;
        flowSnapshot.unitsOverTimeCV =
                flowSnapshot.avgUnitsOverTime
                        == 0 ? 0.0
                        : flowSnapshot.unitsOverTimeVariation / flowSnapshot.avgUnitsOverTime;

        flowSnapshot.avgInterval = (averageInterval >> SHIFT) + intervalRemainder * SCALE_INV;
        flowSnapshot.intervalVariation =
                (intervalVariation >> SHIFT) + varIntervalRemainder * SCALE_INV;
        flowSnapshot.intervalTrend = (intervalTrend >> SHIFT) + intervalTrendRemainder * SCALE_INV;
        flowSnapshot.intervalCV = flowSnapshot.avgInterval == 0 ? 0.0
                : flowSnapshot.intervalVariation / flowSnapshot.avgInterval;
    }

    public double getRollingAverage(boolean getThreadSafe) {
        if (getThreadSafe) {
            acquireLock();
        }

        long count = getEffectiveMeasurementWindowCount(this.lastRecordingTime, false);
        double rollingAverage = count == 0 ? 0 : (double) this.rollingSum / count;

        if (getThreadSafe) {
            releaseLock();
        }
        return rollingAverage;
    }

    public long getEffectiveMeasurementWindowCount(long now, boolean getThreadSafe) {
        if (getThreadSafe) {
            acquireLock();
        }
        long prevWindowCount = this.prevWindowCount;
        long currWindowCount = this.currWindowCount;
        long dynamicWindowNs = this.dynamicWindowNs;
        long windowStartNs = this.windowStartNs;
        if (getThreadSafe) {
            releaseLock();
        }

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

    private enum EwmaRemainder {
        UOT, VAR_UOT, UOT_TREND, UNIT, VAR_UNIT, UNIT_TREND, INTERVAL, VAR_INTERVAL, INTERVAL_TREND
    }

    public static final class FlowSnapshot {

        public long lastRecordingTimeNs = 0;
        public long lastRecordedUnits = 0;
        public long lastRecordedInterval = 0;

        public double avgUnits;
        public double unitVariation;
        public double unitTrend;
        public double unitCV;

        public double avgInterval;
        public double intervalVariation;
        public double intervalTrend;
        public double intervalCV;

        public double avgUnitsOverTime;
        public double unitsOverTimeVariation;
        public double unitsOverTimeTrend;
        public double unitsOverTimeCV;

    }
}
