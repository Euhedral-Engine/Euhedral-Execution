package io.euhedral_execution.core.config;

import java.util.List;
import org.jspecify.annotations.NonNull;

/// Timing for workers that have no work.
///
/// @param idleParkNs             Default park of a worker the decision tree selected for `IDLE`.
/// @param contentionHalfLifeNanos Half-life of acquisition contention history.
/// @param function               Park and half-life curves, or `null` for fixed timing.
/// @param unproductiveParkNs     Park of a worker that processed nothing while none of its upstream
/// handles emitted work on their last attempt. Longer parks save power at the cost of the time a
/// parked worker takes to notice new work. Zero disables the park.
/// @param probeIntervalNs        The longest a worker sits out the `IDLE` path without entering a
/// handle. A worker that sits out never services a handle, so its view of them (which decides to
/// sit out) cannot change by itself; every probe interval its cycle takes the `DIRECT` path
/// instead, enters one handle and so can find work. With the first-ranked worker held inside a
/// frame, this is how long the lattice's handles go unserved at most. Zero makes every cycle enter
/// a handle (the `IDLE` path is never taken); `Long.MAX_VALUE` sits out for good.
public record IdlePolicy(
        long idleParkNs,
        long contentionHalfLifeNanos,
        @NonNull TimingProvider function,
        long unproductiveParkNs,
        long probeIntervalNs) {

    /// Retains the default probe interval.
    public IdlePolicy(long idleParkNs, long contentionHalfLifeNanos, TimingProvider function, long unproductiveParkNs) {
        this(idleParkNs, contentionHalfLifeNanos, function, unproductiveParkNs, DEFAULT_PROBE_INTERVAL_NS);
    }

    /// Retains the default unproductive park.
    public IdlePolicy(long idleParkNs, long contentionHalfLifeNanos, TimingProvider function) {
        this(idleParkNs, contentionHalfLifeNanos, function, DEFAULT_UNPRODUCTIVE_PARK_NS);
    }

    /// Explicit fixed timing.
    public IdlePolicy(long idleParkNs, long contentionHalfLifeNanos) {
        this(idleParkNs, contentionHalfLifeNanos, new TimingProvider() {
            @Override
            public long parkNanos(double contention, double phr, double bodyNanos, long fallback) {
                return idleParkNs;
            }

            @Override
            public long halfLifeNanos(double contention, double phr, double bodyNanos, long fallback) {
                return contentionHalfLifeNanos;
            }

            @Override
            public boolean equals(Object obj) {
                return obj != null
                        && obj.getClass() == getClass()
                        && ((TimingProvider) obj).parkNanos(0, 0, 0, 0) == idleParkNs
                        && ((TimingProvider) obj).halfLifeNanos(0, 0, 0, 0) == contentionHalfLifeNanos;
            }

            @Override
            public int hashCode() {
                return Long.hashCode(idleParkNs) * 31 + Long.hashCode(contentionHalfLifeNanos);
            }
        });
    }

    public static final long DEFAULT_IDLE_PARK_NS = 15_000L;
    public static final long DEFAULT_UNPRODUCTIVE_PARK_NS = 15_000L;
    public static final long DEFAULT_CONTENTION_HALF_LIFE_NANOS = 1_000_000L;
    public static final long DEFAULT_PROBE_INTERVAL_NS = 1_000_000L;
    /// Default IDLE timing policy selected from scarce-source calibration.
    /// Source policy: cache-scarce-v1 / policy-158a61afee6653cbbfde.
    public static final IdleTimingFunction DEFAULT_FUNCTION = new IdleTimingFunction(
            "cache-local-bounded-v1",
            List.of(0.5, 2.0, 8.0),
            List.of(0.5, 2.0, 8.0),
            List.of(0.0, 0.0, 0.0),
            List.of(1.0, 4.0, 16.0),
            List.of(
                    1.8063635377779264,
                    -5.551115123125783e-16,
                    -1.1782901489929007,
                    -0.0885253008972973,
                    -3.2959746043559335e-17,
                    -1.0787811616230769e-16,
                    -1.3530843112619095e-16),
            List.of(
                    -0.7202320591072836,
                    2.220446049250313e-16,
                    0.4005695409857165,
                    3.642919299551295e-17,
                    -8.239936510889834e-18,
                    -2.888721163316066e-17,
                    -4.0766001685454967e-17),
            15000L,
            1000000L,
            15000L,
            814375L,
            250000L,
            2000000L);
    public static final IdlePolicy DEFAULT;

    static {
        if (System.getProperty("euhedral.fixed.idle.policy") != null) {
            DEFAULT = new IdlePolicy(DEFAULT_IDLE_PARK_NS, DEFAULT_CONTENTION_HALF_LIFE_NANOS);
        } else {
            DEFAULT = new IdlePolicy(DEFAULT_IDLE_PARK_NS, DEFAULT_CONTENTION_HALF_LIFE_NANOS, DEFAULT_FUNCTION);
        }
    }

    public IdlePolicy {
        if (idleParkNs < 0L) {
            throw new IllegalArgumentException("idleParkNs must not be negative");
        }
        if (unproductiveParkNs < 0L) {
            throw new IllegalArgumentException("unproductiveParkNs must not be negative");
        }
        if (contentionHalfLifeNanos <= 0L) {
            throw new IllegalArgumentException("contentionHalfLifeNanos must be positive");
        }
        if (probeIntervalNs < 0L) {
            throw new IllegalArgumentException("probeIntervalNs must not be negative");
        }
    }

    /// Returns this policy with a different unproductive park.
    public IdlePolicy withUnproductiveParkNs(long unproductiveParkNs) {
        return new IdlePolicy(
                this.idleParkNs, this.contentionHalfLifeNanos, this.function, unproductiveParkNs, this.probeIntervalNs);
    }

    /// Returns this policy with a different probe interval.
    public IdlePolicy withProbeIntervalNs(long probeIntervalNs) {
        return new IdlePolicy(
                this.idleParkNs, this.contentionHalfLifeNanos, this.function, this.unproductiveParkNs, probeIntervalNs);
    }
}
