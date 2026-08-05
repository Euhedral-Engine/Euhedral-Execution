package io.euhedral_execution.hardware_utils.internal.sampling;

import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalResolution;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.BooleanSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterDelta;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.LatencyInterval;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.LongGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.ResolvedDouble;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.ResolvedLong;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.IntervalHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.SlowHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuFastSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuSlowIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuSlowSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.IoIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.MemoryIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.SystemSlowIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.SystemSlowSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.ThermalSignal;

/// Per-monitor-instance state engine for converting raw provider samples into resolved interval
/// records (CounterDelta, ResolvedLong, ResolvedDouble, LatencyInterval, IntervalHardwareSample).
///
/// Ownership: monitor-instance-owned; all methods must be called on the same monitor thread. No
/// static mutable state, Map, ThreadLocal, or identity-keyed sidecar is used.
///
/// Construction: logicalSpan is fixed at construction and must be strictly positive. It is never
/// derived from effective cardinality or BitSet.length(). fastPeriodNs is used to compute
/// FAST_TTL_NS.
///
/// State lifecycle: resetCounterState() -- used by stop: resets all counter baselines and gauge
/// caches, clears lastEvaluationNs, but retains the slow cache for restart. clearAll() -- used by
/// close and clock-regression: calls resetCounterState() then also clears the slow cache.
///
/// processFast() returns null when:
///   - evaluationNs is duplicate or regressing (triggers clearAll + no publication);
///   - sample is null (complete fast failure, membership retained by caller);
///   - outer sample observedAtNs is after evaluationNs.
public final class SampleStateEngine {

    /// Saturating multiply: returns value * multiplier, saturating at Long.MAX_VALUE.
    /// Both operands are treated as nonnegative.
    private static long saturatedMultiply(long value, int multiplier) {
        long left = Math.max(value, 0);
        long right = Math.max(multiplier, 0);
        if (left == 0 || right == 0) return 0;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static ResolvedDouble resolveDoubleSignal(DoubleGaugeSignal sig, SignalResolution res) {
        if (sig.validity() == SignalValidity.VALID) {
            return new ResolvedDouble(sig.value(), sig.observedAtNs(), res);
        }
        return new ResolvedDouble(0, sig.observedAtNs(), SignalResolution.UNAVAILABLE);
    }
    private static ResolvedLong resolveLongSignal(LongGaugeSignal sig, SignalResolution res) {
        if (sig.validity() == SignalValidity.VALID) {
            return new ResolvedLong(sig.value(), sig.observedAtNs(), res);
        }
        return new ResolvedLong(0, sig.observedAtNs(), SignalResolution.UNAVAILABLE);
    }

    private static CpuIntervalSignals unavailableCpuInterval() {
        return new CpuIntervalSignals(
                new CounterDelta(0, 0, 0, SignalResolution.UNAVAILABLE),
                new CounterDelta(0, 0, 0, SignalResolution.UNAVAILABLE),
                new ResolvedDouble(0, 0, SignalResolution.UNAVAILABLE),
                new CounterDelta(0, 0, 0, SignalResolution.UNAVAILABLE),
                new CounterDelta(0, 0, 0, SignalResolution.UNAVAILABLE),
                new ResolvedDouble(0, 0, SignalResolution.UNAVAILABLE),
                new ResolvedDouble(0, 0, SignalResolution.UNAVAILABLE)
        );
    }

    private static CpuSlowIntervalSignals unavailableCpuSlowInterval() {
        return new CpuSlowIntervalSignals(
                new ResolvedDouble(0, 0, SignalResolution.UNAVAILABLE),
                new ResolvedDouble(0, 0, SignalResolution.UNAVAILABLE),
                new ResolvedLong(0, 0, SignalResolution.UNAVAILABLE),
                new ResolvedLong(0, 0, SignalResolution.UNAVAILABLE),
                ThermalSeverity.NOMINAL, false, 0, SignalResolution.UNAVAILABLE
        );
    }

    private static SystemSlowIntervalSignals unavailableSystemSlowInterval() {
        return new SystemSlowIntervalSignals(
                new ResolvedDouble(0, 0, SignalResolution.UNAVAILABLE),
                new ResolvedDouble(0, 0, SignalResolution.UNAVAILABLE),
                ThermalSeverity.NOMINAL, false, 0, SignalResolution.UNAVAILABLE
        );
    }

    // Timing
    private final int logicalSpan;
    private final long fastTtlNs;

    private long lastEvaluationNs;
    private UnmodifiableBitSet lastMembership;
    private long lastMembershipObservedAtNs;
    private final CounterState scopeThrottledNs = new CounterState();
    private final CounterState scopeWaitNs = new CounterState();
    private final CounterState scopePsiNs = new CounterState();

    // Global counter states.
    private final CounterState quotaCpuNs = new CounterState();
    private final GaugeState scopeReportedRatio = new GaugeState();
    private final CounterState memReclaim = new CounterState();
    private final CounterState memStall = new CounterState();
    private final GaugeState quotaCapacity = new GaugeState();
    private final CounterState ioStall = new CounterState();
    private final CounterState ioLatency = new CounterState();
    private final CounterState ioOps = new CounterState();
    private final GaugeState quotaPeriod = new GaugeState();
    // Memory states.
    private final GaugeState memHard = new GaugeState();
    private final CounterState[] cpuPsi;
    private final GaugeState memHigh = new GaugeState();
    private final CounterState[] cpuThrottle;
    private final CounterState[] cpuSteal;
    private final GaugeState memUsage = new GaugeState();
    private final GaugeState memInactive = new GaugeState();
    // I/O states.
    private final CounterState ioBytes = new CounterState();
    private final GaugeState ioQueue = new GaugeState();
    // Per-logical-CPU states (indexed by stable Euhedral logical CPU ID).
    private final CounterState[] cpuWait;

    // -------------------------------------------------------------------------
    // Inner state classes
    // -------------------------------------------------------------------------
    private final GaugeState[] cpuReportedRatio;
    private final GaugeState[] cpuExternal;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------
    private final GaugeState[] cpuRunnable;
    /// Slow-sample cache with its own 5-second anchored attempt grid.
    private final SlowSampleCache slowCache = new SlowSampleCache();

    /// Constructs an engine for the given fixed logical CPU span and fast period.
    ///
    /// logicalSpan must be strictly positive; derived from SystemInfo.getCpuCount()
    ///   in production, or from an explicit test argument in test constructors.
    /// fastPeriodNs is the monitor's fast-polling period in nanoseconds; used to
    ///   compute FAST_TTL_NS = min(30s, max(1s, 5 * fastPeriodNs)).
    public SampleStateEngine(int logicalSpan, long fastPeriodNs) {
        if (logicalSpan <= 0) {
            throw new IllegalArgumentException("logicalSpan must be positive");
        }
        this.logicalSpan = logicalSpan;

        long baseTtl = Math.max(1_000_000_000L, saturatedMultiply(fastPeriodNs, 5));
        this.fastTtlNs = Math.min(30_000_000_000L, baseTtl);

        this.cpuWait = new CounterState[logicalSpan];
        this.cpuPsi = new CounterState[logicalSpan];
        this.cpuReportedRatio = new GaugeState[logicalSpan];
        this.cpuThrottle = new CounterState[logicalSpan];
        this.cpuSteal = new CounterState[logicalSpan];
        this.cpuExternal = new GaugeState[logicalSpan];
        this.cpuRunnable = new GaugeState[logicalSpan];

        for (int i = 0; i < logicalSpan; i++) {
            cpuWait[i] = new CounterState();
            cpuPsi[i] = new CounterState();
            cpuReportedRatio[i] = new GaugeState();
            cpuThrottle[i] = new CounterState();
            cpuSteal[i] = new CounterState();
            cpuExternal[i] = new GaugeState();
            cpuRunnable[i] = new GaugeState();
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /// Returns true if a slow sample attempt is due at the given poll-start timestamp.
    public boolean isSlowDue(long pollStartNs) {
        return slowCache.isDue(pollStartNs);
    }

    /// Stores a successful slow sample and advances the grid anchor.
    /// sample must not be null; call this only when the provider returned successfully.
    public void processSlow(long pollStartNs, SlowHardwareSample sample) {
        if (sample != null) {
            slowCache.anchorAndStore(pollStartNs, sample);
        }
    }

    // Kept for compatibility with existing callers that used resetState().
    // P4-D should migrate to resetCounterState() (stop) or clearAll() (close).

    /// Processes one fast sample at the given evaluation timestamp and returns an
    /// IntervalHardwareSample, or null if no publication should occur.
    ///
    /// evaluationNs -- the monitor clock value captured after all sensor reads.
    ///   Must be strictly after the prior publication timestamp (signed comparison).
    ///   A duplicate or regressing evaluationNs triggers clearAll() and returns null.
    ///
    /// sample -- the fresh FastHardwareSample, or null for a complete fast failure.
    ///   A null sample returns null without discarding the last known membership.
    ///
    /// Leaf validity: each counter leaf is checked against sample.observedAtNs() as
    ///   its timestamp ceiling (spec: leaf <= outer <= evaluationNs). A leaf after the
    ///   outer is treated as inconsistent and resolves UNAVAILABLE for that signal.
    ///
    /// Membership: the first valid outer fast sample sets lastMembership. Only a
    ///   strictly newer outer observedAtNs may replace it; stale provider output
    ///   cannot remap topology. Removing a CPU from membership immediately clears its
    ///   per-CPU baselines and gauge states.
    public IntervalHardwareSample processFast(long evaluationNs, FastHardwareSample sample) {
        if (lastEvaluationNs != 0 && (evaluationNs - lastEvaluationNs) <= 0) {
            // Regressing or duplicate monitor time: clear all state and produce no publication.
            clearAll();
            return null;
        }
        lastEvaluationNs = evaluationNs;

        if (sample == null) {
            return null; // Complete fast failure; caller retains membership.
        }

        if (sample.observedAtNs() - evaluationNs > 0) {
            return null; // Outer sample is in the future relative to evaluationNs.
        }

        long outerObservedAtNs = sample.observedAtNs();

        if (lastMembership == null || (outerObservedAtNs - lastMembershipObservedAtNs) > 0) {
            lastMembership = sample.effectiveCpus();
            lastMembershipObservedAtNs = outerObservedAtNs;
        }

        quotaCapacity.updateLong(sample.quotaCapacityCpus());
        quotaPeriod.updateLong(sample.quotaPeriodNs());

        CounterDelta productive = quotaCpuNs.evaluate(
                sample.productiveCpuNs(), outerObservedAtNs, evaluationNs, fastTtlNs);
        CounterDelta scopeThrottled = scopeThrottledNs.evaluate(
                sample.scopeQuotaThrottledNs(), outerObservedAtNs, evaluationNs, fastTtlNs);
        CounterDelta scopeWait = scopeWaitNs.evaluate(
                sample.scopeSchedulerWaitNs(), outerObservedAtNs, evaluationNs, fastTtlNs);
        CounterDelta scopePsi = scopePsiNs.evaluate(
                sample.scopePsiStallNs(), outerObservedAtNs, evaluationNs, fastTtlNs);

        scopeReportedRatio.updateDouble(sample.scopeReportedSchedulerStallRatio());
        ResolvedDouble resolvedScopeRatio = scopeReportedRatio.resolveDouble(evaluationNs, fastTtlNs);

        // Memory signals.
        memHard.updateLong(sample.memorySignals().hardLimitBytes());
        memHigh.updateLong(sample.memorySignals().highLimitBytes());
        memUsage.updateLong(sample.memorySignals().usageBytes());
        memInactive.updateLong(sample.memorySignals().inactiveFileBytes());
        CounterDelta memReclaimDelta = memReclaim.evaluate(
                sample.memorySignals().cumulativeReclaimBytes(), outerObservedAtNs, evaluationNs,
                fastTtlNs);
        CounterDelta memStallDelta = memStall.evaluate(
                sample.memorySignals().memoryStallNs(), outerObservedAtNs, evaluationNs, fastTtlNs);

        MemoryIntervalSignals memInterval = new MemoryIntervalSignals(
            memHard.resolveLong(evaluationNs, fastTtlNs),
            memHigh.resolveLong(evaluationNs, fastTtlNs),
            memUsage.resolveLong(evaluationNs, fastTtlNs),
            memInactive.resolveLong(evaluationNs, fastTtlNs),
            memReclaimDelta,
            memStallDelta
        );

        // I/O signals.
        CounterDelta ioBytesDelta = ioBytes.evaluate(
                sample.ioSignals().productiveBytes(), outerObservedAtNs, evaluationNs, fastTtlNs);
        CounterDelta ioStallDelta = ioStall.evaluate(
                sample.ioSignals().stallNs(), outerObservedAtNs, evaluationNs, fastTtlNs);
        CounterDelta ioLatDelta = ioLatency.evaluate(
                sample.ioSignals().operationLatencyNs(), outerObservedAtNs, evaluationNs,
                fastTtlNs);
        CounterDelta ioOpsDelta = ioOps.evaluate(
                sample.ioSignals().completedOperations(), outerObservedAtNs, evaluationNs,
                fastTtlNs);
        ioQueue.updateDouble(sample.ioSignals().maximumQueueDepth());

        // Paired latency: valid only when both members produce a CURRENT delta over
        // the same interval (matching elapsedNs). If either fails, both are rebased
        // so the next interval starts fresh on both counters (H3 fix).
        LatencyInterval ioLatInt;
        boolean latencyPairValid =
                ioLatDelta.resolution() == SignalResolution.CURRENT
                        && ioOpsDelta.resolution() == SignalResolution.CURRENT
                        && ioLatDelta.elapsedNs() == ioOpsDelta.elapsedNs();
        if (latencyPairValid) {
            ioLatInt = new LatencyInterval(
                    ioLatDelta.delta(), ioOpsDelta.delta(),
                    ioLatDelta.elapsedNs(), ioLatDelta.observedAtNs(),
                    SignalResolution.CURRENT);
        } else {
            // Rebase both members so neither carries a stale baseline.
            ioLatency.rebase();
            ioOps.rebase();
            ioLatInt = new LatencyInterval(0, 0, 0, 0, SignalResolution.UNAVAILABLE);
        }

        IoIntervalSignals ioInterval = new IoIntervalSignals(
            ioBytesDelta,
            ioStallDelta,
            ioLatInt,
            ioQueue.resolveDouble(evaluationNs, fastTtlNs)
        );

        // Per-CPU signals.
        CpuIntervalSignals[] cpuIntervals = new CpuIntervalSignals[logicalSpan];
        CpuFastSignals[] fastSigs = sample.cpuSignals();
        for (int i = 0; i < logicalSpan; i++) {
            if (!lastMembership.get(i)) {
                // CPU removed from effective set: clear all per-CPU state immediately.
                cpuWait[i].hasBaseline = false;
                cpuPsi[i].hasBaseline = false;
                cpuReportedRatio[i].hasValue = false;
                cpuThrottle[i].hasBaseline = false;
                cpuSteal[i].hasBaseline = false;
                cpuExternal[i].hasValue = false;
                cpuRunnable[i].hasValue = false;
                cpuIntervals[i] = unavailableCpuInterval();
                continue;
            }
            CpuFastSignals sig = fastSigs[i];
            CounterDelta w = cpuWait[i].evaluate(sig.schedulerWait(), outerObservedAtNs,
                    evaluationNs, fastTtlNs);
            CounterDelta p = cpuPsi[i].evaluate(sig.psiStall(), outerObservedAtNs, evaluationNs,
                    fastTtlNs);
            cpuReportedRatio[i].updateDouble(sig.reportedSchedulerStallRatio());
            CounterDelta t = cpuThrottle[i].evaluate(sig.quotaThrottle(), outerObservedAtNs,
                    evaluationNs, fastTtlNs);
            CounterDelta st = cpuSteal[i].evaluate(sig.steal(), outerObservedAtNs, evaluationNs,
                    fastTtlNs);
            cpuExternal[i].updateDouble(sig.externalContentionRatio());
            cpuRunnable[i].updateDouble(sig.runnablePerCapacity());

            cpuIntervals[i] = new CpuIntervalSignals(
                w, p, cpuReportedRatio[i].resolveDouble(evaluationNs, fastTtlNs),
                t, st, cpuExternal[i].resolveDouble(evaluationNs, fastTtlNs),
                cpuRunnable[i].resolveDouble(evaluationNs, fastTtlNs)
            );
        }

        // Slow signals from cache.
        SlowHardwareSample slow = slowCache.resolve(evaluationNs);
        CpuSlowIntervalSignals[] slowCpu = new CpuSlowIntervalSignals[logicalSpan];
        SystemSlowIntervalSignals slowSys;

        if (slow != null) {
            long age = evaluationNs - slow.observedAtNs();
            SignalResolution res = age == 0 ? SignalResolution.CURRENT : SignalResolution.CACHED;

            SystemSlowSignals sysSig = slow.systemSignals();
            slowSys = new SystemSlowIntervalSignals(
                resolveDoubleSignal(sysSig.availableCapacityUnits(), res),
                    resolveDoubleSignal(sysSig.nominalCapacityUnits(), res),
                    sysSig.thermalSeverity().validity() == SignalValidity.VALID
                            ? sysSig.thermalSeverity().value() : ThermalSeverity.NOMINAL,
                    sysSig.lowPowerMode().validity() == SignalValidity.VALID
                            && sysSig.lowPowerMode().value(),
                    slow.observedAtNs(),
                res
            );

            CpuSlowSignals[] cSigs = slow.cpuSignals();
            for (int i = 0; i < logicalSpan; i++) {
                if (!lastMembership.get(i)) {
                    slowCpu[i] = unavailableCpuSlowInterval();
                    continue;
                }
                CpuSlowSignals cs = cSigs[i];
                slowCpu[i] = new CpuSlowIntervalSignals(
                    resolveDoubleSignal(cs.availableCapacityUnits(), res),
                        resolveDoubleSignal(cs.nominalCapacityUnits(), res),
                        resolveLongSignal(cs.constrainedFrequencyHz(), res),
                        resolveLongSignal(cs.nominalFrequencyHz(), res),
                        cs.thermalSeverity().validity() == SignalValidity.VALID
                                ? cs.thermalSeverity().value() : ThermalSeverity.NOMINAL,
                        cs.lowPowerMode().validity() == SignalValidity.VALID && cs.lowPowerMode()
                                .value(),
                        slow.observedAtNs(),
                    res
                );
            }
        } else {
            slowSys = unavailableSystemSlowInterval();
            for (int i = 0; i < logicalSpan; i++) {
                slowCpu[i] = unavailableCpuSlowInterval();
            }
        }

        return new IntervalHardwareSample(
            evaluationNs,
                logicalSpan,
                lastMembership,
            quotaCapacity.resolveLong(evaluationNs, fastTtlNs),
            quotaPeriod.resolveLong(evaluationNs, fastTtlNs),
            productive,
            scopeThrottled,
            scopeWait,
            scopePsi,
            resolvedScopeRatio,
            cpuIntervals,
            memInterval,
            ioInterval,
            slowCpu,
            slowSys
        );
    }

    // -------------------------------------------------------------------------
    // Helper factories
    // -------------------------------------------------------------------------

    /// Resets all counter baselines and gauge states, and clears lastEvaluationNs, but retains the
    /// slow sample cache for restart (stop semantics). P4-D must call this when stopping the
    /// monitor to allow restart to reuse a fresh slow sample without triggering an immediate slow
    /// re-read.
    public void resetCounterState() {
        lastEvaluationNs = 0;
        quotaCpuNs.hasBaseline = false;
        scopeThrottledNs.hasBaseline = false;
        scopeWaitNs.hasBaseline = false;
        scopePsiNs.hasBaseline = false;
        scopeReportedRatio.hasValue = false;
        quotaCapacity.hasValue = false;
        quotaPeriod.hasValue = false;

        memHard.hasValue = false;
        memHigh.hasValue = false;
        memUsage.hasValue = false;
        memInactive.hasValue = false;
        memReclaim.hasBaseline = false;
        memStall.hasBaseline = false;

        ioBytes.hasBaseline = false;
        ioStall.hasBaseline = false;
        ioLatency.hasBaseline = false;
        ioOps.hasBaseline = false;
        ioQueue.hasValue = false;

        for (int i = 0; i < logicalSpan; i++) {
            cpuWait[i].hasBaseline = false;
            cpuPsi[i].hasBaseline = false;
            cpuReportedRatio[i].hasValue = false;
            cpuThrottle[i].hasBaseline = false;
            cpuSteal[i].hasBaseline = false;
            cpuExternal[i].hasValue = false;
            cpuRunnable[i].hasValue = false;
        }
    }

    /// Resets all counter and gauge state and also clears the slow cache and
    /// lastMembership (close/regression semantics). No state from this engine
    /// survives after clearAll().
    public void clearAll() {
        resetCounterState();
        lastMembership = null;
        lastMembershipObservedAtNs = 0;
        slowCache.clear();
    }

    /// Single-counter baseline and TTL state. All fields are package-accessible for direct reset
    /// from SampleStateEngine without synthetic accessors.
    ///
    /// Ownership: one CounterState per signal slot; owned by the engine instance. Thread-safety:
    /// not thread-safe; access is confined to the monitor thread.
    private static class CounterState {
        long value = 0;
        long observedAtNs = 0;
        boolean hasBaseline = false;

        /// Evaluates the new counter signal against the stored baseline and returns
        /// an immutable CounterDelta.
        ///
        /// evaluationNs -- the monitor's current evaluation timestamp (monotonic ns).
        ///   Used as the TTL age reference and as the ceiling for leaf timestamps.
        /// outerObservedAtNs -- the outer fast sample's observedAtNs. Valid leaves
        ///   must not be after this value (spec: leaf <= outerObservedAtNs <= evaluationNs).
        ///   A leaf after outerObservedAtNs is treated as inconsistent and returns UNAVAILABLE.
        /// ttl -- FAST_TTL_NS for this engine; the transient-failure retention window.
        ///
        /// Counter rule (all canonical, signed non-negative longs):
        ///   no prior baseline       -> store (c, tc), return BASELINE
        ///   dt <= 0 (tc - tp)       -> replace baseline with (c, tc), return BASELINE
        ///   c < p (reset/wrap)      -> replace baseline with (c, tc), return BASELINE
        ///   otherwise               -> delta = c - p; replace baseline; return CURRENT
        ///
        /// UNSUPPORTED clears the baseline immediately.
        /// TRANSIENT_FAILURE retains the baseline for TTL but produces no contribution.
        CounterDelta evaluate(CounterSignal signal, long outerObservedAtNs, long evaluationNs,
                long ttl) {
            if (signal == null || signal.validity() != SignalValidity.VALID) {
                if (signal != null && signal.validity() == SignalValidity.UNSUPPORTED) {
                    hasBaseline = false;
                    return new CounterDelta(0, 0, 0, SignalResolution.UNAVAILABLE);
                }
                // Null or TRANSIENT_FAILURE: retain baseline within TTL.
                if (!hasBaseline) {
                    return new CounterDelta(0, 0, 0, SignalResolution.UNAVAILABLE);
                }
                long age = evaluationNs - observedAtNs;
                if (age < 0 || age > ttl) {
                    hasBaseline = false;
                    return new CounterDelta(0, 0, 0, SignalResolution.UNAVAILABLE);
                }
                return new CounterDelta(0, 0, 0, SignalResolution.UNAVAILABLE);
            }

            long c = signal.value();
            long tc = signal.observedAtNs();

            // Leaf must not be after the outer sample's observedAtNs (M3 fix).
            if (tc - outerObservedAtNs > 0) {
                return new CounterDelta(0, 0, 0, SignalResolution.UNAVAILABLE);
            }

            if (!hasBaseline) {
                value = c;
                observedAtNs = tc;
                hasBaseline = true;
                return new CounterDelta(0, 0, tc, SignalResolution.BASELINE);
            }

            long p = value;
            long tp = observedAtNs;
            long dt = tc - tp;

            if (dt <= 0 || c < p) {
                value = c;
                observedAtNs = tc;
                return new CounterDelta(0, 0, tc, SignalResolution.BASELINE);
            }

            value = c;
            observedAtNs = tc;
            return new CounterDelta(c - p, dt, tc, SignalResolution.CURRENT);
        }

        /// Resets the baseline so the next valid sample establishes a fresh baseline
        /// rather than producing a potentially multi-interval delta. Used when a
        /// paired counter (e.g., latency/ops pairing) requires both members to rebase.
        void rebase() {
            hasBaseline = false;
        }
    }

    /// Single-gauge last-valid-value and TTL state.
    ///
    /// Ownership: one GaugeState per signal slot; owned by the engine instance.
    /// Thread-safety: not thread-safe; access is confined to the monitor thread.
    ///
    /// Refresh rule: only a strictly newer valid leaf timestamp replaces the stored
    ///   value. A duplicate or regressing valid timestamp is ignored (retains prior).
    ///   TRANSIENT_FAILURE is ignored (retains prior). UNSUPPORTED clears immediately.
    private static class GaugeState {

        double doubleVal = 0.0;
        long longVal = 0L;
        boolean booleanVal = false;
        ThermalSeverity thermalVal = ThermalSeverity.NOMINAL;

        long observedAtNs = 0;
        boolean hasValue = false;

        /// Updates the stored double gauge value from signal. Strictly newer timestamps only;
        /// TRANSIENT_FAILURE is ignored; UNSUPPORTED clears.
        void updateDouble(DoubleGaugeSignal signal) {
            if (signal == null || signal.validity() == SignalValidity.UNSUPPORTED) {
                hasValue = false;
                return;
            }
            if (signal.validity() == SignalValidity.TRANSIENT_FAILURE) {
                return;
            }
            if (!hasValue || (signal.observedAtNs() - observedAtNs) > 0) {
                doubleVal = signal.value();
                observedAtNs = signal.observedAtNs();
                hasValue = true;
            }
        }

        /// Updates the stored long gauge value from signal.
        void updateLong(LongGaugeSignal signal) {
            if (signal == null || signal.validity() == SignalValidity.UNSUPPORTED) {
                hasValue = false;
                return;
            }
            if (signal.validity() == SignalValidity.TRANSIENT_FAILURE) return;
            if (!hasValue || (signal.observedAtNs() - observedAtNs) > 0) {
                longVal = signal.value();
                observedAtNs = signal.observedAtNs();
                hasValue = true;
            }
        }

        /// Updates the stored thermal severity from signal.
        void updateThermal(ThermalSignal signal) {
            if (signal == null || signal.validity() == SignalValidity.UNSUPPORTED) {
                hasValue = false;
                return;
            }
            if (signal.validity() == SignalValidity.TRANSIENT_FAILURE) return;
            if (!hasValue || (signal.observedAtNs() - observedAtNs) > 0) {
                thermalVal = signal.value();
                observedAtNs = signal.observedAtNs();
                hasValue = true;
            }
        }

        /// Updates the stored boolean value from signal.
        void updateBoolean(BooleanSignal signal) {
            if (signal == null || signal.validity() == SignalValidity.UNSUPPORTED) {
                hasValue = false;
                return;
            }
            if (signal.validity() == SignalValidity.TRANSIENT_FAILURE) return;
            if (!hasValue || (signal.observedAtNs() - observedAtNs) > 0) {
                booleanVal = signal.value();
                observedAtNs = signal.observedAtNs();
                hasValue = true;
            }
        }

        /// Resolves the stored double value against the TTL. Returns UNAVAILABLE if
        /// hasValue is false, age < 0 (invalid), or age > ttl (expired).
        /// Age in [0, ttl] is fresh (boundary is included). Clears hasValue on expiry.
        ResolvedDouble resolveDouble(long evaluationNs, long ttl) {
            if (!hasValue)
                return new ResolvedDouble(0, 0, SignalResolution.UNAVAILABLE);
            long age = evaluationNs - observedAtNs;
            if (age < 0 || age > ttl) {
                hasValue = false;
                return new ResolvedDouble(0, 0, SignalResolution.UNAVAILABLE);
            }
            return new ResolvedDouble(doubleVal, observedAtNs,
                    age == 0 ? SignalResolution.CURRENT : SignalResolution.CACHED);
        }

        /// Resolves the stored long value against the TTL.
        ResolvedLong resolveLong(long evaluationNs, long ttl) {
            if (!hasValue)
                return new ResolvedLong(0, 0, SignalResolution.UNAVAILABLE);
            long age = evaluationNs - observedAtNs;
            if (age < 0 || age > ttl) {
                hasValue = false;
                return new ResolvedLong(0, 0, SignalResolution.UNAVAILABLE);
            }
            return new ResolvedLong(longVal, observedAtNs,
                    age == 0 ? SignalResolution.CURRENT : SignalResolution.CACHED);
        }

        /// Resolves the stored ThermalSeverity, returning NOMINAL on expiry.
        ThermalSeverity resolveThermal(long evaluationNs, long ttl) {
            if (!hasValue)
                return ThermalSeverity.NOMINAL;
            long age = evaluationNs - observedAtNs;
            if (age < 0 || age > ttl) {
                hasValue = false;
                return ThermalSeverity.NOMINAL;
            }
            return thermalVal;
        }

        /// Resolves the stored boolean, returning false on expiry.
        boolean resolveBoolean(long evaluationNs, long ttl) {
            if (!hasValue)
                return false;
            long age = evaluationNs - observedAtNs;
            if (age < 0 || age > ttl) {
                hasValue = false;
                return false;
            }
            return booleanVal;
        }

        /// Returns the resolution for the stored value without materializing the payload.
        SignalResolution resolveResolution(long evaluationNs, long ttl) {
            if (!hasValue)
                return SignalResolution.UNAVAILABLE;
            long age = evaluationNs - observedAtNs;
            if (age < 0 || age > ttl) return SignalResolution.UNAVAILABLE;
            return age == 0 ? SignalResolution.CURRENT : SignalResolution.CACHED;
        }

        long observedAtNs() {
            return observedAtNs;
        }
    }
}
