package euhedral.io.utils;

import static euhedral.io.utils.MathFunctions.clampInt;
import static euhedral.io.utils.MathFunctions.clampLong;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

public class FlowRecorder {

    // 16 bits of precision
    public static final int SHIFT = 16;
    public static final int SCALE = 1 << SHIFT;
    public static final double SCALE_INV = 1.0 / SCALE;
    public static final int MASK = SCALE - 1;

    private final long minWindowNs, maxWindowNs;
    private final AtomicBoolean wip = new AtomicBoolean();
    @Getter
    private final FlowSnapshot flowSnapshot = new FlowSnapshot();

    private long dynamicWindowNs;
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
            averageRate = ewma(averageRate, 0, decayAlpha, EwmaRemainder.RATE);
            averageUnits = ewma(averageUnits, 0, decayAlpha, EwmaRemainder.UNIT);
            averageInterval = ewma(averageInterval, 0, decayAlpha, EwmaRemainder.INTERVAL);
            rateVariation = ewma(rateVariation, 0, decayAlpha, EwmaRemainder.VAR_RATE);
            unitVariation = ewma(unitVariation, 0, decayAlpha, EwmaRemainder.VAR_UNIT);
            intervalVariation = ewma(averageInterval, 0, decayAlpha, EwmaRemainder.VAR_INTERVAL);
        }
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

        int alpha = getRatio(interval, dynamicWindowNs);
        alpha = clampInt(alpha, 1024, SCALE);

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

        averageRate = ewma(averageRate, currentRate, alpha, EwmaRemainder.RATE);
        averageUnits = ewma(averageUnits, currentUnits, alpha, EwmaRemainder.UNIT);
        averageInterval = ewma(averageInterval, currentInterval, alpha, EwmaRemainder.INTERVAL);

        if (prevUnits != 0) {
            rateVariation = ewma(rateVariation, Math.abs(currentRate - averageRate) / 2, alpha,
                    EwmaRemainder.VAR_RATE);
            unitVariation = ewma(unitVariation, Math.abs(currentUnits - averageUnits) / 2, alpha,
                    EwmaRemainder.VAR_UNIT);
            intervalVariation = ewma(intervalVariation,
                    Math.abs(currentInterval - averageInterval) / 2,
                    alpha, EwmaRemainder.VAR_INTERVAL);
        }

    }

    public void refreshSnapshot(FlowSnapshot flowSnapshot, boolean threadSafe) {
        if (threadSafe) {
            acquireLock();
        }
        if (flowSnapshot.lastRecordingTimeNs != lastRecordingTime) {
            refreshSnapshot(flowSnapshot);
        }
        if (threadSafe) {
            releaseLock();
        }
    }

    private void refreshSnapshot(FlowSnapshot flowSnapshot) {
        flowSnapshot.lastRecordingTimeNs = lastRecordingTime;

        flowSnapshot.avgUnits = (averageUnits >> SHIFT) + unitRemainder * SCALE_INV;
        flowSnapshot.unitVariation = (unitVariation >> SHIFT) + varUnitRemainder * SCALE_INV;
        flowSnapshot.unitCV = flowSnapshot.avgUnits == 0 ? 0.0
                : flowSnapshot.avgUnits / flowSnapshot.unitVariation;

        flowSnapshot.avgRate = (averageRate >> SHIFT) + rateRemainder * SCALE_INV;
        flowSnapshot.rateVariation = (rateVariation >> SHIFT) + varRateRemainder * SCALE_INV;
        flowSnapshot.rateCV =
                flowSnapshot.avgRate == 0 ? 0.0 : flowSnapshot.avgRate / flowSnapshot.rateVariation;

        flowSnapshot.avgInterval = (averageInterval >> SHIFT) + intervalRemainder * SCALE_INV;
        flowSnapshot.intervalVariation =
                (intervalVariation >> SHIFT) + varIntervRemainder * SCALE_INV;
        flowSnapshot.intervalCV = flowSnapshot.avgInterval == 0 ? 0.0
                : flowSnapshot.avgInterval / flowSnapshot.intervalVariation;

        flowSnapshot.throughputNs = averageRate <= 0 ? 0 : (SCALE) / averageRate;
    }

    public double getRollingAverage(long now, boolean getThreadSafe) {
        if (getThreadSafe) {
            acquireLock();
        }

        long count = getEffectiveMeasurementWindowCount(now, false);
        double rollingSum = count == 0 ? 0 : (double) this.rollingSum / count;

        if (getThreadSafe) {
            releaseLock();
        }
        return rollingSum;
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

    /**
     * Returns the queue estimate scaled by the provided 'scaler'.
     */
    public double getVegasQueueEstimate(FlowSnapshot flowSnapshot, double units, long scaler) {
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

    private enum EwmaRemainder {
        RATE, VAR_RATE, UNIT, VAR_UNIT, INTERVAL, VAR_INTERVAL
    }

    public static final class FlowSnapshot {

        public long lastRecordingTimeNs = 0;

        public double avgUnits;
        public double unitVariation;
        public double unitCV;

        public double avgRate;
        public double rateVariation;
        public double rateCV;

        public double avgInterval;
        public double intervalVariation;
        public double intervalCV;

        public long throughputNs;
    }
}
