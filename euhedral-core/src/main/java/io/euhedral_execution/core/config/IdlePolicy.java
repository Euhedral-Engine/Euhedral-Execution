package io.euhedral_execution.core.config;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record IdlePolicy(
        long idleParkNs,
        long contentionHalfLifeNanos,
        @Nullable IdleTimingFunction function) {

    /// Explicit fixed timing, including legacy callers and benchmark POLICY_OFF.
    public IdlePolicy(long idleParkNs, long contentionHalfLifeNanos) {
        this(idleParkNs, contentionHalfLifeNanos, null);
    }

    public static final long DEFAULT_IDLE_PARK_NS = 15_000L;
    public static final long DEFAULT_CONTENTION_HALF_LIFE_NANOS = 1_000_000L;
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
    public static final IdlePolicy DEFAULT =
            new IdlePolicy(DEFAULT_IDLE_PARK_NS, DEFAULT_CONTENTION_HALF_LIFE_NANOS, DEFAULT_FUNCTION);

    public IdlePolicy {
        if (idleParkNs < 0L) {
            throw new IllegalArgumentException("idleParkNs must not be negative");
        }
        if (contentionHalfLifeNanos <= 0L) {
            throw new IllegalArgumentException("contentionHalfLifeNanos must be positive");
        }
    }
}
