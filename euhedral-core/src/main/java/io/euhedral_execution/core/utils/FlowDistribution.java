package io.euhedral_execution.core.utils;

import lombok.Getter;

public final class FlowDistribution {

    private static final double ENVELOPE_DECAY = 5e-3;

    private final P2Quantile p10 = new P2Quantile(0.10);
    private final P2Quantile p25 = new P2Quantile(0.25);
    private final P2Quantile p50 = new P2Quantile(0.50);
    private final P2Quantile p75 = new P2Quantile(0.75);
    private final P2Quantile p90 = new P2Quantile(0.90);
    @Getter
    private long measurements;
    private double meanUnits;
    private double m2Units;
    private double minUnits = Double.MAX_VALUE;
    private double maxUnits = -Double.MAX_VALUE;

    public void record(double units) {
        this.measurements++;

        this.p10.add(units);
        this.p25.add(units);
        this.p50.add(units);
        this.p75.add(units);
        this.p90.add(units);

        updateEnvelopeUnits(units);

        double delta = units - this.meanUnits;
        this.meanUnits += delta / this.measurements;
        this.m2Units += delta * (units - this.meanUnits);
    }

    private void updateEnvelopeUnits(double x) {

        if (x < this.minUnits) {
            this.minUnits = x;
        } else {
            this.minUnits += ENVELOPE_DECAY * (x - this.minUnits);
        }

        if (x > this.maxUnits) {
            this.maxUnits = x;
        } else {
            this.maxUnits += ENVELOPE_DECAY * (x - this.maxUnits);
        }
    }

    public double mean() {
        return this.meanUnits;
    }

    public double p10() {
        return this.p10.value();
    }

    public double p25() {
        return this.p25.value();
    }

    public double p50() {
        return this.p50.value();
    }

    public double p75() {
        return this.p75.value();
    }

    public double p90() {
        return this.p90.value();
    }

    public double variance() {
        return this.measurements > 1 ? this.m2Units / (this.measurements - 1) : 0;
    }

    public double standardDeviation() {
        return Math.sqrt(variance());
    }

    public double iqr() {
        return this.p75.value() - this.p25.value();
    }

    public double percentile(double units) {
        double p10 = p10();
        if (units <= p10) {
            return 0.0;
        }
        double p25 = p25();
        double p50 = p50();
        double p75 = p75();
        double p90 = p90();
        return percentile(units, p10, p25, p50, p75, p90);
    }

    private double percentile(double value, double p10, double p25, double p50, double p75,
            double p90) {
        if (value <= p25) {
            return 0.10 + ((value - p10) / (p25 - p10)) * 0.15;
        }
        if (value <= p50) {
            return 0.25 + ((value - p25) / (p50 - p25)) * 0.25;
        }
        if (value <= p75) {
            return 0.50 + ((value - p50) / (p75 - p50)) * 0.25;
        }
        if (value <= p90) {
            return 0.75 + ((value - p75) / (p90 - p75)) * 0.15;
        }

        return 1.0;
    }

    public boolean initialized() {
        return this.p90.initialized();
    }

    public void reset() {
        this.measurements = 0;

        this.meanUnits = 0;
        this.m2Units = 0;

        this.minUnits = Double.MAX_VALUE;

        this.maxUnits = Double.MIN_VALUE;

        this.p10.reset();
        this.p25.reset();
        this.p50.reset();
        this.p75.reset();
        this.p90.reset();
    }
}
