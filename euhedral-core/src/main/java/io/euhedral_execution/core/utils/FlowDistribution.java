package io.euhedral_execution.core.utils;

import com.tdunning.math.stats.MergingDigest;
import com.tdunning.math.stats.ScaleFunction;
import com.tdunning.math.stats.TDigest;
import lombok.Getter;

public final class FlowDistribution {

    @Getter
    private final TDigest digest = new MergingDigest(200);

    @Getter
    private long measurements;
    private double meanUnits;
    private double m2Units;

    public FlowDistribution() {
        this.digest.setScaleFunction(ScaleFunction.K_1);
    }

    public void record(double units) {
        this.measurements++;

        this.digest.add(units);

        double delta = units - this.meanUnits;
        this.meanUnits += delta / this.measurements;
        this.m2Units += delta * (units - this.meanUnits);
    }

    public double mean() {
        return this.meanUnits;
    }

    public double variance() {
        return this.measurements > 1 ? this.m2Units / (this.measurements - 1) : 0;
    }

    public double standardDeviation() {
        return Math.sqrt(variance());
    }

}
