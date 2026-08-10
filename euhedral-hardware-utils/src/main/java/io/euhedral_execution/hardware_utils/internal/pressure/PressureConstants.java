package io.euhedral_execution.hardware_utils.internal.pressure;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.ThermalSeverity;

final class PressureConstants {

    static final double ATTACK_TAU_SECONDS = 0.8962840235449102;
    static final double ATTACK_TAU_SECONDS_INV = 1.0 / ATTACK_TAU_SECONDS;
    static final double RELEASE_TAU_SECONDS = 3.8991451492447347;
    static final double RELEASE_TAU_SECONDS_INV = 1.0 / RELEASE_TAU_SECONDS;
    static final double HEADROOM_ONSET = 0.80;
    static final double HEADROOM_RANGE = 0.20;
    static final double RECLAIM_FULL_FRACTION = 0.02;
    static final double RUN_QUEUE_ONSET = 1.0;
    static final double RUN_QUEUE_RANGE = 3.0;
    static final double IO_LATENCY_ONSET_NS = 1_000_000.0;
    static final double IO_LATENCY_RANGE_NS = 49_000_000.0;
    static final double IO_QUEUE_ONSET = 1.0;
    static final double IO_QUEUE_RANGE = 7.0;
    static final double THERMAL_LOSS_NOMINAL = 0.00;
    static final double THERMAL_LOSS_FAIR = 0.15;
    static final double THERMAL_LOSS_SERIOUS = 0.35;
    static final double THERMAL_LOSS_CRITICAL = 0.65;
    static final double THERMAL_LOSS_EMERGENCY = 1.00;
    static final double LOW_POWER_LOSS = 0.15;

    private PressureConstants() {}

    static double unit(double x) {
        if (x <= 0.0) {
            return 0.0;
        }
        return Math.min(x, 1.0);
    }

    static double nonnegativeTelemetry(double x) {
        if (Double.isNaN(x) || x < 0.0) {
            return 0.0;
        }
        if (x == Double.POSITIVE_INFINITY) {
            return Double.MAX_VALUE;
        }
        if (x == -0.0) {
            return 0.0; // canonicalize
        }
        return x;
    }

    static double thermalLoss(ThermalSeverity severity) {
        if (severity == null) {
            return THERMAL_LOSS_NOMINAL;
        }
        return switch (severity) {
            case NOMINAL -> THERMAL_LOSS_NOMINAL;
            case FAIR -> THERMAL_LOSS_FAIR;
            case SERIOUS -> THERMAL_LOSS_SERIOUS;
            case CRITICAL -> THERMAL_LOSS_CRITICAL;
            case EMERGENCY -> THERMAL_LOSS_EMERGENCY;
        };
    }

    static double lowPowerLoss(boolean lowPower) {
        return lowPower ? LOW_POWER_LOSS : 0.0;
    }
}
