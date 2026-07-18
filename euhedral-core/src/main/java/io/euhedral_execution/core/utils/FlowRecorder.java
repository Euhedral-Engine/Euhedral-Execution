package io.euhedral_execution.core.utils;

import java.time.Duration;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

@SuppressWarnings("unused")
public class FlowRecorder {
    @Getter
    private final FlowSnapshot snapshot = new FlowSnapshot();

    private final double alpha;

    @Getter
    private final long measurementWindowNs;
    private long windowStartNs;

    private long prevWindowCount = 0;
    private long currWindowCount = 0;

    @Getter
    private long lastRecordingTime;
    @Getter
    private long lastInterval;
    @Getter
    private long lastRecordedUnits;

    @Getter
    private long rollingSum = 0;

    private double averageUnits, averageInterval, averageUnitsOverTime;
    @Getter
    private long minUnits = Long.MAX_VALUE, minInterval = Long.MAX_VALUE;
    @Getter
    private long maxUnits = Long.MIN_VALUE, maxInterval = Long.MIN_VALUE;
    @Getter
    private double minUoT = Double.MAX_VALUE;
    @Getter
    private double maxUoT = Double.MIN_VALUE;

    private double unitVariance, intervalVariance, uotVariance;
    private double unitTrend, intervalTrend, uotTrend;

    public FlowRecorder() {
        this(Duration.ofMillis(10), 0.05);
    }

    public FlowRecorder(@NonNull Duration maxWindowSize, double alpha) {
        this.measurementWindowNs = maxWindowSize.toNanos();
        this.windowStartNs = System.nanoTime();
        this.lastRecordingTime = 0;
        this.alpha = alpha;
        this.snapshot.updateSnapshot();
    }

    public void recordUnits(long units) {
        recordUnits(System.nanoTime(), units);
    }

    public void recordUnits(long now, long units) {

        long interval = (now - this.lastRecordingTime);
        if (now <= 0 || interval <= 0) {
            return;
        }

        if(this.lastRecordingTime == 0) {
            this.lastRecordingTime = now;
            this.lastRecordedUnits = units;
            this.rollingSum = units;
            this.prevWindowCount = 0;
            this.currWindowCount = 1;
            return;
        }


        this.lastRecordingTime = now;
        this.lastRecordedUnits = units;
        this.currWindowCount++;
        this.lastInterval = interval;

        updateMetrics(units, interval, this.alpha);

        if (now - this.windowStartNs > this.measurementWindowNs) {
            this.prevWindowCount = this.currWindowCount;
            this.currWindowCount = 0;
            this.windowStartNs = now;
        }
    }

    private void updateMetrics(long currentUnits, long currentInterval, double alpha) {
        double unitsOverTime = currentUnits / Math.max(currentInterval, 1e-9);

        this.rollingSum = Math.round((1.0 - alpha) * this.rollingSum + currentUnits);
        if(this.averageInterval == 0) {
            this.averageUnits = currentUnits;
            this.averageInterval = currentInterval;
            this.averageUnitsOverTime = 0;
            this.minUnits = currentUnits;
            this.maxUnits = currentUnits;
            this.minInterval = currentInterval;
            this.maxInterval = currentInterval;
            this.minUoT = unitsOverTime;
            this.maxUoT = unitsOverTime;
            return;
        } else if(this.prevWindowCount + this.currWindowCount == 2) {
            this.averageUnitsOverTime = unitsOverTime;
        }

        double delta = Math.abs(currentInterval - this.averageInterval);
        if(delta > this.measurementWindowNs) {
            reset();
            return;
        }

        this.averageUnits = MathFunctions.ewma(this.averageUnits, currentUnits, alpha);
        this.averageInterval = MathFunctions.ewma(this.averageInterval, currentInterval, alpha);
        this.averageUnitsOverTime = MathFunctions.ewma(this.averageUnitsOverTime, unitsOverTime, alpha);

        tryDecayMaxima();
        this.minUnits = Math.min(this.minUnits, currentUnits);
        this.minInterval = Math.min(this.minInterval, currentInterval);
        this.minUoT = Math.min(this.minUoT, unitsOverTime);
        this.maxUnits = Math.max(this.maxUnits, currentUnits);
        this.maxInterval = Math.max(this.maxInterval, currentInterval);
        this.maxUoT = Math.max(this.maxUoT, unitsOverTime);

        double decay = 1.0 - alpha;
        delta = currentUnits - this.averageUnits;
        this.unitVariance = decay * (this.unitVariance + alpha * delta * delta);
        this.unitTrend = this.unitVariance == 0 ? 0 : MathFunctions.ewma(this.unitTrend, delta, alpha);

        delta = currentInterval - this.averageInterval;
        this.intervalVariance = decay * (this.intervalVariance + alpha * delta * delta);
        this.intervalTrend = this.intervalVariance == 0 ? 0 : MathFunctions.ewma(this.intervalTrend, delta, alpha);

        delta = unitsOverTime - this.averageUnitsOverTime;
        this.uotVariance = decay * (this.uotVariance + alpha * delta * delta);
        this.uotTrend = this.uotVariance == 0 ? 0 : MathFunctions.ewma(this.uotTrend, delta, alpha);
    }

    private void tryDecayMaxima() {
        double stdDev = unitStandardDeviation() * 3;
        if(this.minUnits < this.averageUnits - stdDev) {
            this.minUnits = (long) Math.floor(this.averageUnits - stdDev);
        }
        if(this.maxUnits > this.averageUnits + stdDev) {
            this.maxUnits = (long) Math.ceil(this.averageUnits + stdDev);
        }

        stdDev = intervalStandardDeviation() * 3;
        if(this.minInterval < this.averageInterval - stdDev) {
            this.minInterval = (long) Math.floor(this.averageInterval - stdDev);
        }
        if(this.maxInterval > this.averageInterval + stdDev) {
            this.maxInterval = (long) Math.ceil(this.averageInterval + stdDev);
        }

        stdDev = uotStandardDeviation() * 3;
        if(this.minUoT < this.averageUnitsOverTime - stdDev) {
            this.minUoT = this.averageUnitsOverTime - stdDev;
        }
        if(this.maxUoT > this.averageUnitsOverTime + stdDev) {
            this.maxUoT = this.averageUnitsOverTime + stdDev;
        }
    }

    public void reset() {
        this.prevWindowCount = 0;
        this.currWindowCount = 0;
        this.lastRecordingTime = 0;
        this.windowStartNs = System.nanoTime();

        this.rollingSum = 0;

        this.averageUnitsOverTime = 0;
        this.minUoT = Double.MAX_VALUE;
        this.maxUoT = Double.MIN_VALUE;

        this.averageUnits = 0;
        this.minUnits = Long.MAX_VALUE;
        this.maxUnits = Long.MIN_VALUE;

        this.averageInterval = 0;
        this.minInterval = Long.MAX_VALUE;
        this.maxInterval = Long.MIN_VALUE;

        this.unitVariance = 0;
        this.uotVariance = 0;
        this.intervalVariance = 0;

        this.unitTrend = 0;
        this.intervalTrend = 0;
        this.uotTrend = 0;
        this.snapshot.updateSnapshot();
    }

    public double getRollingAverage() {
        return getRollingAverage(System.nanoTime());
    }

    public double getRollingAverage(long now) {
        double count = getEffectiveMeasurementWindowCount(now);
        if(count == 0) {
            return 0;
        }
        return this.rollingSum / count;
    }

    public long getCurrentWindowCount() {
        return this.currWindowCount;
    }

    public double getEffectiveMeasurementWindowCount() {
        return getEffectiveMeasurementWindowCount(System.nanoTime());
    }

    public double getEffectiveMeasurementWindowCount(long now) {
        long prevWindowCount = this.prevWindowCount;
        long currWindowCount = this.currWindowCount;
        long dynamicWindowNs = this.measurementWindowNs;
        long windowStartNs = this.windowStartNs;


        if(windowStartNs + dynamicWindowNs < now) {
            return 0;
        }

        long elapsed = now - windowStartNs;
        if(elapsed == 0) {
            return prevWindowCount;
        }

        if(prevWindowCount == 0 && now >= this.lastRecordingTime) {
            return currWindowCount;
        }

        double progress = (double) elapsed / dynamicWindowNs;
        double invProgress = 1.0 / progress;

        // effective = (prev * (1 - progress)) + current
        double prevContribution = prevWindowCount * invProgress;
        double currContribution = currWindowCount * progress;

        double total = prevContribution + currContribution;
        return total < 1 ? 1 : total;
    }

    public double averageUnits() {
        return this.averageUnits;
    }

    public double unitVariance() {
        return this.unitVariance;
    }

    public double unitTrend() {
        return this.unitTrend;
    }

    public double averageInterval() {
        return this.averageInterval;
    }

    public double intervalVariance() {
        return this.intervalVariance;
    }

    public double intervalTrend() {
        return this.intervalTrend;
    }

    public double averageUnitsOverTime() {
        return this.averageUnitsOverTime;
    }

    public double unitsOverTimeVariance() {
        return this.uotVariance;
    }

    public double unitsOverTimeTrend() {
        return this.uotTrend;
    }

    public double unitStandardDeviation() {
        return Math.sqrt(this.unitVariance);
    }

    public double intervalStandardDeviation() {
        return Math.sqrt(this.intervalVariance);
    }

    public double uotStandardDeviation() {
        return Math.sqrt(this.uotVariance);
    }

    public double unitCV() {
        if(this.averageUnits == 0) {
            return 0;
        }
        return unitStandardDeviation() / averageUnits();
    }

    public double intervalCV() {
        if(this.averageInterval == 0) {
            return 0;
        }
        return intervalStandardDeviation() / averageInterval();
    }

    public double unitsOverTimeCV() {
        if(this.averageUnitsOverTime == 0) {
            return 0;
        }
        return uotStandardDeviation() / averageUnitsOverTime();
    }

    public final class FlowSnapshot {
        public boolean isReset = true;

        public double averageUnits, averageInterval, averageUnitsOverTime;
        public long minUnits, minInterval;
        public long maxUnits, maxInterval;
        public double minUoT;
        public double maxUoT;

        public double unitVariance, intervalVariance, uotVariance;
        public double unitTrend, intervalTrend, uotTrend;

        public void updateSnapshot() {
            this.averageUnits = FlowRecorder.this.averageUnits;
            this.averageInterval = FlowRecorder.this.averageInterval;
            this.averageUnitsOverTime = FlowRecorder.this.averageUnitsOverTime;
            this.minUnits = FlowRecorder.this.minUnits;
            this.minInterval = FlowRecorder.this.minInterval;
            this.maxUnits = FlowRecorder.this.maxUnits;
            this.maxInterval = FlowRecorder.this.maxInterval;
            this.minUoT = FlowRecorder.this.minUoT;
            this.maxUoT = FlowRecorder.this.maxUoT;
            this.unitVariance = FlowRecorder.this.unitVariance;
            this.intervalVariance = FlowRecorder.this.intervalVariance;
            this.uotVariance = FlowRecorder.this.uotVariance;
            this.unitTrend = FlowRecorder.this.unitTrend;
            this.intervalTrend = FlowRecorder.this.intervalTrend;
            this.uotTrend = FlowRecorder.this.uotTrend;

            this.isReset = this.minUnits == Long.MAX_VALUE && this.maxUnits == Long.MIN_VALUE;
        }
    }
}
