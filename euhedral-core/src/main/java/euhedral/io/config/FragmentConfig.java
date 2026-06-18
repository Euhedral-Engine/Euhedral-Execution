package euhedral.io.config;

import euhedral.io.generics.CloneableObject;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// ## Configuration values for the [`ControlPlaneFragment`][euhedral.io.control_plane.ControlPlaneFragment]
///
/// @param cloneConfig       See [CloneConfig]
/// @param minConcurrency    Sets the lowest concurrency the `ControlPlaneFragment` will
/// throttle down to.
/// @param maxUpdateInterval Adjusts the maximum frequency of execution rate updates. Higher
/// intervals reduce unneeded recomputation of limits and execution latency recording. They also
/// reduce sensitivity to micro jitter. The `ControlPlaneFragment` will automatically
/// adjust its update interval between 1 and the max depending on the variance of execution latency.
/// The max interval will be adjusted to the next highest power of 2 if the configured value is not
/// a power of 2.
/// @param enableSMT         If the `ControlPlaneFragment` is running on a core with SMT,
/// setting this to `true` will make the manager offload the demand signaling and pulling to its SMT
/// hyper thread sibling.
/// @param idleCyclePolicy   Adjusts how the `ControlPlaneFragment` will idle when it
/// does not have work to do. Lower thresholds decrease responsiveness and decrease power
/// consumption. Higher ones do the opposite. Higher thresholds will also cause more contention on
/// the producers due to more frequent pulling.
/// @param meterRegistry     Used to record execution metrics.
/// @param metricPrefix      Prefix applied to the metric names. It will default to the shard name
/// if it is null or empty.
public record FragmentConfig(@Nullable CloneConfig cloneConfig, int minConcurrency,
                             int maxUpdateInterval,
                             boolean enableSMT,
                             @NonNull IdleCyclePolicy idleCyclePolicy,
                             @Nullable MeterRegistry meterRegistry,
                             @Nullable String metricPrefix)
        implements CloneableObject {

    public static FragmentConfig powerSavingDefault() {
        return powerSavingDefault(null, null);
    }

    public static FragmentConfig powerSavingDefault(MeterRegistry meterRegistry,
            String metricPrefix) {
        return new FragmentConfig(null, 64, 1024, false,
                IdleCyclePolicy.POWER_SAVING, meterRegistry,
                metricPrefix);
    }

    public static FragmentConfig balancedDefault() {
        return balancedDefault(null, null);
    }

    public static FragmentConfig balancedDefault(MeterRegistry meterRegistry,
            String metricPrefix) {
        return new FragmentConfig(null, 512, 1024, false,
                IdleCyclePolicy.DEFAULT, meterRegistry,
                metricPrefix);
    }

    @Override
    public FragmentConfig clone(CloneConfig cloneConfig) {
        return new FragmentConfig(cloneConfig, minConcurrency, maxUpdateInterval,
                enableSMT,
                idleCyclePolicy, meterRegistry, metricPrefix);
    }

    @Override
    public void close() {
    }

    /// Defines how the ControlPlaneFragment will react when it doesn't process work in a cycle. Setting
    /// the threshold values negative disables them. `maxParkTime` is also used to calculate demand
    /// signaling backoff. Keep the value reasonable even if the spin and yield are disabled. Values
    /// that are set too low will increase latency and contention on high core counts.
    ///
    /// @param spinThreshold  Upper limit defined by idleCyles / totalCycles for using
    /// Thread.onSpinWait()
    /// @param yieldThreshold Upper limit defined by idleCyles / totalCycles for using
    /// Thread.yield()
    /// @param parkThreshold  Upper limit defined by idleCycles / totalCycles for using
    /// LockSupport.parkNanos()
    /// @param maxParkTime    Max duration of each LockSupport.parkNanos()
    public record IdleCyclePolicy(double spinThreshold, double yieldThreshold, double parkThreshold,
                                  Duration maxParkTime) {

        public static IdleCyclePolicy DEFAULT =
                new IdleCyclePolicy(0.40, 0.80, 1.0, Duration.ofNanos(20_000)); // 20 micros
        public static IdleCyclePolicy POWER_SAVING =
                new IdleCyclePolicy(-1.0, -1.0, 1.0, Duration.ofNanos(100_000)); // 100 micros
    }
}
