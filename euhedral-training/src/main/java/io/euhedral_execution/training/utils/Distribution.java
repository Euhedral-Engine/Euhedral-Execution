package io.euhedral_execution.training.utils;

import static io.euhedral_execution.training.utils.CommonFunctions.round;

import com.tdunning.math.stats.MergingDigest;
import com.tdunning.math.stats.TDigest;
import java.util.Arrays;
import org.jspecify.annotations.NonNull;

public class Distribution implements Comparable<Distribution> {

    public final double[] vector;

    public final TDigest digest = new MergingDigest(100);
    public double mean = 0;
    public double[] quantiles = new double[5];

    public Distribution(double[] vector) {
        this.vector = vector;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Distribution other) {
            return Arrays.equals(vector, other.vector);
        }
        return false;
    }


    @Override
    public int compareTo(@NonNull Distribution other) {
        if (this == other) {
            return 0;
        }

        int p50Compare = Double.compare(round(digest.quantile(0.5)),
                round(other.digest.quantile(0.5)));
        if (p50Compare != 0) {
            return p50Compare;
        }

        double thisIQR = round(digest.quantile(0.75)) - round(digest.quantile(0.25));
        double otherIQR =
                round(other.digest.quantile(0.75)) - round(other.digest.quantile(0.25));
        int iqrCompare = Double.compare(otherIQR, thisIQR);
        if (iqrCompare != 0) {
            return iqrCompare;
        }

        double thisTailRange = round(digest.quantile(0.9)) - round(digest.quantile(0.1));
        double otherTailRange =
                round(other.digest.quantile(0.9)) - round(other.digest.quantile(0.1));
        return Double.compare(otherTailRange, thisTailRange);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.vector) ^ Arrays.hashCode(this.quantiles);
    }

    double[] toArray() {
        if (!Double.isFinite(this.digest.quantile(.5))) {
            for (int i = 0; i < 100; i++) {
                this.digest.add(0);
            }
        }
        this.quantiles[0] = digest.quantile(0.1);
        this.quantiles[1] = digest.quantile(0.25);
        this.quantiles[2] = digest.quantile(0.5);
        this.quantiles[3] = digest.quantile(0.75);
        this.quantiles[4] = digest.quantile(0.9);
        return this.quantiles;
    }
}
