package io.euhedral_execution.core.config;

import java.util.List;
import java.util.Objects;

/// Fixed affine or pairwise-interaction coefficients in the declared coordinate system.
/// Inputs are contention, productive handles / registered workers, and log1p(body nanoseconds).
/// Lists are made immutable at initialization; primitive inference allocates nothing.
public record CacheTimingFunctionConfig(
        String normalizationVersion,
        List<Double> means,
        List<Double> scales,
        List<Double> supportMin,
        List<Double> supportMax,
        List<Double> parkCoefficients,
        List<Double> halfLifeCoefficients,
        long parkReferenceNanos,
        long halfLifeReferenceNanos,
        long parkMinNanos,
        long parkMaxNanos,
        long halfLifeMinNanos,
        long halfLifeMaxNanos) {
    public CacheTimingFunctionConfig {
        if (Objects.requireNonNull(normalizationVersion).isBlank()) {
            throw new IllegalArgumentException("normalizationVersion must be named");
        }
        means = copy(means, 3);
        scales = copy(scales, 3);
        supportMin = copy(supportMin, 3);
        supportMax = copy(supportMax, 3);
        int width = Objects.requireNonNull(parkCoefficients).size();
        if (width != 4 && width != 7) {
            throw new IllegalArgumentException("timing basis requires four or seven coefficients per output");
        }
        parkCoefficients = copy(parkCoefficients, width);
        halfLifeCoefficients = copy(halfLifeCoefficients, width);
        for (int i = 0; i < 3; i++) {
            if (scales.get(i) <= 0
                    || supportMin.get(i) < 0
                    || supportMax.get(i) < supportMin.get(i)
                    || !Double.isFinite((supportMin.get(i) - means.get(i)) / scales.get(i))
                    || !Double.isFinite((supportMax.get(i) - means.get(i)) / scales.get(i))) {
                throw new IllegalArgumentException("invalid normalization/support");
            }
        }
        if (supportMax.get(0) > 1
                || parkMinNanos < 0
                || halfLifeMinNanos <= 0
                || parkReferenceNanos <= 0
                || halfLifeReferenceNanos <= 0
                || parkReferenceNanos < parkMinNanos
                || parkReferenceNanos > parkMaxNanos
                || halfLifeReferenceNanos < halfLifeMinNanos
                || halfLifeReferenceNanos > halfLifeMaxNanos) {
            throw new IllegalArgumentException("invalid timing references/bounds");
        }
        validateEffect(parkCoefficients, means, scales, supportMin, supportMax);
        validateEffect(halfLifeCoefficients, means, scales, supportMin, supportMax);
    }

    private static List<Double> copy(List<Double> values, int size) {
        if (Objects.requireNonNull(values).size() != size) {
            throw new IllegalArgumentException("invalid coefficient/feature width");
        }
        List<Double> result = List.copyOf(values);
        for (double v : result) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("nonfinite coefficient/feature");
            }
        }
        return result;
    }

    private static void validateEffect(
            List<Double> coefficients, List<Double> means, List<Double> scales, List<Double> lo, List<Double> hi) {
        double effect = Math.abs(coefficients.get(0));
        for (int i = 0; i < 3; i++) {
            effect += Math.abs(coefficients.get(i + 1))
                    * Math.max(
                            Math.abs((lo.get(i) - means.get(i)) / scales.get(i)),
                            Math.abs((hi.get(i) - means.get(i)) / scales.get(i)));
        }
        if (coefficients.size() == 7) {
            int index = 4;
            for (int i = 0; i < 3; i++) {
                double left = Math.max(
                        Math.abs((lo.get(i) - means.get(i)) / scales.get(i)),
                        Math.abs((hi.get(i) - means.get(i)) / scales.get(i)));
                for (int j = i + 1; j < 3; j++) {
                    double right = Math.max(
                            Math.abs((lo.get(j) - means.get(j)) / scales.get(j)),
                            Math.abs((hi.get(j) - means.get(j)) / scales.get(j)));
                    effect += Math.abs(coefficients.get(index++)) * left * right;
                }
            }
        }
        if (!Double.isFinite(effect)) {
            throw new IllegalArgumentException("nonfinite log-time effect");
        }
    }

    public long parkNanos(double contention, double phr, double bodyNanos, long fallback) {
        return output(
                contention, phr, bodyNanos, parkCoefficients, parkReferenceNanos, parkMinNanos, parkMaxNanos, fallback);
    }

    public long halfLifeNanos(double contention, double phr, double bodyNanos, long fallback) {
        return output(
                contention,
                phr,
                bodyNanos,
                halfLifeCoefficients,
                halfLifeReferenceNanos,
                halfLifeMinNanos,
                halfLifeMaxNanos,
                fallback);
    }

    private long output(
            double c,
            double p,
            double body,
            List<Double> coefficients,
            long reference,
            long lo,
            long hi,
            long fallback) {
        double b = Math.log1p(body);
        if (!Double.isFinite(c)
                || !Double.isFinite(p)
                || !Double.isFinite(b)
                || body < 0
                || c < supportMin.get(0)
                || c > supportMax.get(0)
                || p < supportMin.get(1)
                || p > supportMax.get(1)
                || b < supportMin.get(2)
                || b > supportMax.get(2)) {
            return fallback;
        }
        double zc = (c - means.get(0)) / scales.get(0);
        double zp = (p - means.get(1)) / scales.get(1);
        double zb = (b - means.get(2)) / scales.get(2);
        double exponent =
                coefficients.get(0) + coefficients.get(1) * zc + coefficients.get(2) * zp + coefficients.get(3) * zb;
        if (coefficients.size() == 7) {
            exponent +=
                    coefficients.get(4) * (zc * zp) + coefficients.get(5) * (zc * zb) + coefficients.get(6) * (zp * zb);
        }
        double bounded = Math.clamp(reference * Math.exp(exponent), (double) lo, (double) hi);
        return Math.clamp(Math.round(bounded), lo, hi);
    }
}
