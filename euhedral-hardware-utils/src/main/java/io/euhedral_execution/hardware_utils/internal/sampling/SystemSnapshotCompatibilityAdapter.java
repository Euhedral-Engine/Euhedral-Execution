package io.euhedral_execution.hardware_utils.internal.sampling;

import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.common.UnmodifiableDoubleArray;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.CompatibilityProfile;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.BooleanSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.LongGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.SlowHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuFastSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuSlowSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.IoFastSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.MemoryFastSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.SystemSlowSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.ThermalSignal;
import io.euhedral_execution.hardware_utils.linux.CgroupV2Resources;
import io.euhedral_execution.hardware_utils.osx.OSXResources;
import io.euhedral_execution.hardware_utils.windows.WindowsResources;
import java.util.BitSet;
import java.util.Objects;

/// Translates legacy SystemSnapshotProvider implementations into the richer
/// DetailedSystemSnapshotProvider SPI by performing one-time profile detection
/// and producing signal-validity-annotated fast and slow samples.
///
/// Construction: the profile is detected once at construction from the concrete
///   type of the delegate via instanceof. The profile is fixed for the lifetime
///   of the adapter. No dynamic profile change is possible.
///
///   A construction-time call to delegate.getSnapshot() is made once to capture
///   logicalSpan from snap.totalCpus(). This is necessary because sampleSlow()
///   must produce a CpuSlowSignals[] of exactly logicalSpan entries without
///   consulting the delegate again. If the initial snapshot is null, construction
///   fails with IllegalStateException because logicalSpan cannot be determined
///   safely. This is the only construction-time failure mode beyond a null delegate.
///
/// sampleFast semantics:
///   Calls delegate.getSnapshot() exactly once per invocation. All valid signal
///   observedAtNs values are set to snap.timeNs() from that one call.
///   A null snapshot is a transient failure: all signals are returned as
///   TRANSIENT_FAILURE in a complete FastHardwareSample at requestedAtNs.
///
/// sampleSlow semantics:
///   Returns an all-UNSUPPORTED SlowHardwareSample at requestedAtNs without
///   calling the delegate. Legacy providers cannot supply slow signals.
///
/// Period conversion (C1 fix):
///   LINUX_V2_LEGACY: snap.period() is in microseconds; multiplied by 1000 with
///     Math.multiplyExact after rejecting a negative input. Overflow returns
///     transient failure. CANONICAL_PUBLIC/WINDOWS_LEGACY/MACOS_LEGACY:
///     snap.period() is in nanoseconds and is used directly.
///
/// Null profile restriction:
///   DetailedSystemSnapshotProvider delegates must not be wrapped; use wrap()
///   which returns the delegate directly in that case.
public final class SystemSnapshotCompatibilityAdapter implements DetailedSystemSnapshotProvider {

    private final SystemSnapshotProvider delegate;
    private final CompatibilityProfile profile;

    /// Fixed logical CPU count captured from the first getSnapshot() call at
    /// construction. Used to produce correctly sized CpuSlowSignals[] in sampleSlow().
    private final int logicalSpan;

    /// Constructs an adapter for a non-DetailedSystemSnapshotProvider delegate.
    ///
    /// Throws NullPointerException if delegate is null.
    /// Throws IllegalArgumentException if delegate is already a DetailedSystemSnapshotProvider.
    /// Throws IllegalStateException if the initial getSnapshot() returns null
    ///   (logicalSpan cannot be determined).
    public SystemSnapshotCompatibilityAdapter(SystemSnapshotProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (delegate instanceof DetailedSystemSnapshotProvider) {
            throw new IllegalArgumentException("Do not wrap DetailedSystemSnapshotProvider");
        } else if (delegate instanceof CgroupV2Resources) {
            this.profile = CompatibilityProfile.LINUX_V2_LEGACY;
        } else if (delegate instanceof WindowsResources) {
            this.profile = CompatibilityProfile.WINDOWS_LEGACY;
        } else if (delegate instanceof OSXResources) {
            this.profile = CompatibilityProfile.MACOS_LEGACY;
        } else {
            this.profile = CompatibilityProfile.CANONICAL_PUBLIC;
        }

        // Capture logicalSpan once at construction. Required so sampleSlow()
        // can produce a correctly sized per-CPU array without consulting the
        // delegate again (spec: sampleSlow must not invoke the wrapped provider).
        SystemSnapshot snap = delegate.getSnapshot();
        if (snap == null) {
            throw new IllegalStateException(
                "SystemSnapshotCompatibilityAdapter: initial getSnapshot() returned null; "
                + "cannot determine logicalSpan");
        }
        this.logicalSpan = snap.totalCpus();
    }

    /// Factory that returns the provider directly if it already implements
    /// DetailedSystemSnapshotProvider, or wraps it in an adapter otherwise.
    ///
    /// Selection order matches the spec:
    ///   1. DetailedSystemSnapshotProvider -> returned as-is
    ///   2. CgroupV2Resources             -> LINUX_V2_LEGACY
    ///   3. WindowsResources              -> WINDOWS_LEGACY
    ///   4. OSXResources                  -> MACOS_LEGACY
    ///   5. other                         -> CANONICAL_PUBLIC
    public static DetailedSystemSnapshotProvider wrap(SystemSnapshotProvider provider) {
        if (provider instanceof DetailedSystemSnapshotProvider detailed) {
            return detailed;
        }
        return new SystemSnapshotCompatibilityAdapter(provider);
    }

    /// Delegates to the wrapped provider's getSnapshot(); does not modify the result.
    @Override
    public SystemSnapshot getSnapshot() {
        return delegate.getSnapshot();
    }

    /// Calls delegate.getSnapshot() exactly once and maps the result to a
    /// FastHardwareSample with per-profile signal validity annotations.
    ///
    /// A null snapshot is a transient failure: returns a complete FastHardwareSample
    /// with every signal set to TRANSIENT_FAILURE at requestedAtNs (M2 fix).
    ///
    /// Period conversion (C1 fix):
    ///   LINUX_V2_LEGACY -- snap.period() is in microseconds.
    ///     A negative period is rejected before multiplication (M5 fix).
    ///     Overflow from Math.multiplyExact returns transient-failure period.
    ///   All other profiles -- snap.period() is in nanoseconds; used directly.
    ///     A negative nanosecond period maps to transient-failure period.
    @Override
    public FastHardwareSample sampleFast(long requestedAtNs) {
        SystemSnapshot snap = delegate.getSnapshot();
        if (snap == null) {
            return buildAllTransientFailureFast(requestedAtNs);
        }

        long timeNs = snap.timeNs();
        int totalCpus = snap.totalCpus();
        UnmodifiableBitSet effective = snap.effectiveCpus();

        LongGaugeSignal quotaPeriod;
        if (profile == CompatibilityProfile.LINUX_V2_LEGACY) {
            quotaPeriod = convertLinuxPeriodMicros(snap.period(), timeNs);
        } else {
            // CANONICAL_PUBLIC, WINDOWS_LEGACY, MACOS_LEGACY: period is in nanoseconds.
            long periodNs = snap.period();
            if (periodNs < 0) {
                quotaPeriod = LongGaugeSignal.transientFailure(timeNs);
            } else if (periodNs == 0) {
                // Zero means unavailable/not applicable.
                quotaPeriod = LongGaugeSignal.unsupported(timeNs);
            } else {
                quotaPeriod = LongGaugeSignal.valid(periodNs, timeNs);
            }
        }

        LongGaugeSignal quotaCapacity = LongGaugeSignal.valid((long) snap.quotaCpus(), timeNs);

        CounterSignal productiveCpuNs;
        CounterSignal scopeQuotaThrottledNs;
        if (profile == CompatibilityProfile.WINDOWS_LEGACY || profile == CompatibilityProfile.MACOS_LEGACY) {
            productiveCpuNs = CounterSignal.unsupported(timeNs);
            scopeQuotaThrottledNs = CounterSignal.unsupported(timeNs);
        } else {
            productiveCpuNs = CounterSignal.valid(snap.cpuUsage(), timeNs);
            scopeQuotaThrottledNs = CounterSignal.valid(snap.cpuThrottle(), timeNs);
        }

        CounterSignal scopeSchedulerWaitNs = CounterSignal.unsupported(timeNs);
        CounterSignal scopePsiStallNs = CounterSignal.unsupported(timeNs);
        DoubleGaugeSignal scopeReportedSchedulerStallRatio = DoubleGaugeSignal.unsupported(timeNs);

        CpuFastSignals[] cpuSignals = new CpuFastSignals[totalCpus];
        UnmodifiableDoubleArray pressure = snap.pressurePerCpu();
        for (int i = 0; i < totalCpus; i++) {
            DoubleGaugeSignal reportedRatio;
            if (profile == CompatibilityProfile.CANONICAL_PUBLIC) {
                reportedRatio = new DoubleGaugeSignal(pressure.get(i), timeNs, SignalValidity.VALID);
            } else {
                reportedRatio = DoubleGaugeSignal.unsupported(timeNs);
            }
            cpuSignals[i] = new CpuFastSignals(
                CounterSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                reportedRatio,
                CounterSignal.unsupported(timeNs),
                CounterSignal.unsupported(timeNs),
                DoubleGaugeSignal.unsupported(timeNs),
                DoubleGaugeSignal.unsupported(timeNs)
            );
        }

        long memLimit = snap.memoryLimit();
        LongGaugeSignal hardLimit;
        if (memLimit == Long.MAX_VALUE) {
            // Long.MAX_VALUE sentinel means no hard limit is configured.
            hardLimit = LongGaugeSignal.unsupported(timeNs);
        } else {
            hardLimit = LongGaugeSignal.valid(memLimit, timeNs);
        }

        MemoryFastSignals memory = new MemoryFastSignals(
            hardLimit,
            LongGaugeSignal.unsupported(timeNs),
            LongGaugeSignal.valid(snap.memoryUsage(), timeNs),
            LongGaugeSignal.valid(snap.inactiveFileMemory(), timeNs),
            CounterSignal.unsupported(timeNs),
            CounterSignal.unsupported(timeNs)
        );

        IoFastSignals io = new IoFastSignals(
            CounterSignal.valid(snap.diskIOBytes(), timeNs),
            CounterSignal.unsupported(timeNs),
            CounterSignal.unsupported(timeNs),
            CounterSignal.unsupported(timeNs),
            DoubleGaugeSignal.unsupported(timeNs)
        );

        return new FastHardwareSample(
            timeNs,
            totalCpus,
            effective,
            quotaCapacity,
            quotaPeriod,
            productiveCpuNs,
            scopeQuotaThrottledNs,
            scopeSchedulerWaitNs,
            scopePsiStallNs,
            scopeReportedSchedulerStallRatio,
            cpuSignals,
            memory,
            io
        );
    }

    /// Returns an all-UNSUPPORTED SlowHardwareSample at requestedAtNs without
    /// invoking the wrapped provider. Legacy providers cannot supply slow signals.
    @Override
    public SlowHardwareSample sampleSlow(long requestedAtNs) {
        CpuSlowSignals[] cpuSlow = new CpuSlowSignals[logicalSpan];
        for (int i = 0; i < logicalSpan; i++) {
            cpuSlow[i] = createUnsupportedCpuSlow(requestedAtNs);
        }
        return new SlowHardwareSample(
            requestedAtNs,
            logicalSpan,
            cpuSlow,
            createUnsupportedSystemSlow(requestedAtNs)
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /// Converts a LINUX_V2_LEGACY period value from microseconds to nanoseconds.
    ///
    /// Rejects negative input before calling Math.multiplyExact (M5 fix):
    ///   periodMicros < 0 -> transient failure (not a valid period).
    ///   overflow          -> transient failure.
    ///   0                 -> unsupported (unavailable/not applicable).
    ///   positive          -> valid LongGaugeSignal in nanoseconds.
    private static LongGaugeSignal convertLinuxPeriodMicros(long periodMicros, long timeNs) {
        if (periodMicros < 0) {
            return LongGaugeSignal.transientFailure(timeNs);
        }
        if (periodMicros == 0) {
            return LongGaugeSignal.unsupported(timeNs);
        }
        try {
            long periodNs = Math.multiplyExact(periodMicros, 1_000L);
            return LongGaugeSignal.valid(periodNs, timeNs);
        } catch (ArithmeticException e) {
            return LongGaugeSignal.transientFailure(timeNs);
        }
    }

    /// Builds a complete FastHardwareSample with every signal set to
    /// TRANSIENT_FAILURE, used when the delegate returns a null snapshot.
    ///
    /// requestedAtNs is used as the observedAtNs for all signals and for the
    /// outer sample itself, matching the spec's "transient sample failure" rule.
    private FastHardwareSample buildAllTransientFailureFast(long requestedAtNs) {
        CpuFastSignals[] cpus = new CpuFastSignals[logicalSpan];
        for (int i = 0; i < logicalSpan; i++) {
            cpus[i] = new CpuFastSignals(
                CounterSignal.transientFailure(requestedAtNs),
                CounterSignal.transientFailure(requestedAtNs),
                DoubleGaugeSignal.transientFailure(requestedAtNs),
                CounterSignal.transientFailure(requestedAtNs),
                CounterSignal.transientFailure(requestedAtNs),
                DoubleGaugeSignal.transientFailure(requestedAtNs),
                DoubleGaugeSignal.transientFailure(requestedAtNs)
            );
        }
        // effectiveCpus: use an empty bit set (no CPUs are reliably known to be effective).
        BitSet empty = new BitSet(logicalSpan);
        MemoryFastSignals memory = new MemoryFastSignals(
            LongGaugeSignal.transientFailure(requestedAtNs),
            LongGaugeSignal.transientFailure(requestedAtNs),
            LongGaugeSignal.transientFailure(requestedAtNs),
            LongGaugeSignal.transientFailure(requestedAtNs),
            CounterSignal.transientFailure(requestedAtNs),
            CounterSignal.transientFailure(requestedAtNs)
        );
        IoFastSignals io = new IoFastSignals(
            CounterSignal.transientFailure(requestedAtNs),
            CounterSignal.transientFailure(requestedAtNs),
            CounterSignal.transientFailure(requestedAtNs),
            CounterSignal.transientFailure(requestedAtNs),
            DoubleGaugeSignal.transientFailure(requestedAtNs)
        );
        return new FastHardwareSample(
            requestedAtNs,
            logicalSpan,
            new UnmodifiableBitSet(empty),
            LongGaugeSignal.transientFailure(requestedAtNs),
            LongGaugeSignal.transientFailure(requestedAtNs),
            CounterSignal.transientFailure(requestedAtNs),
            CounterSignal.transientFailure(requestedAtNs),
            CounterSignal.transientFailure(requestedAtNs),
            CounterSignal.transientFailure(requestedAtNs),
            DoubleGaugeSignal.transientFailure(requestedAtNs),
            cpus,
            memory,
            io
        );
    }

    private CpuSlowSignals createUnsupportedCpuSlow(long t) {
        return new CpuSlowSignals(
            DoubleGaugeSignal.unsupported(t),
            DoubleGaugeSignal.unsupported(t),
            LongGaugeSignal.unsupported(t),
            LongGaugeSignal.unsupported(t),
            new ThermalSignal(ThermalSeverity.NOMINAL, t, SignalValidity.UNSUPPORTED),
            new BooleanSignal(false, t, SignalValidity.UNSUPPORTED)
        );
    }

    private SystemSlowSignals createUnsupportedSystemSlow(long t) {
        return new SystemSlowSignals(
            DoubleGaugeSignal.unsupported(t),
            DoubleGaugeSignal.unsupported(t),
            new ThermalSignal(ThermalSeverity.NOMINAL, t, SignalValidity.UNSUPPORTED),
            new BooleanSignal(false, t, SignalValidity.UNSUPPORTED)
        );
    }
}
