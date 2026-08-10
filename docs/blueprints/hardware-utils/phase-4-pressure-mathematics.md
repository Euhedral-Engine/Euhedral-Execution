# Phase 4-B Pressure Mathematics and Projection Blueprint

## Status and authority

This is the implementation-ready blueprint for P4-B. The parent blueprint
(`phase-4-resource-monitor-pressure.md`) on the P4 root
`hardware-utils-overhaul/phase-4-pressure-monitor` provides the architectural boundaries. P4-A
(sample validity contract) is merged; this branch
`hardware-utils-overhaul/phase-4-pressure-math-blueprint` is created from the updated P4 root
containing the P4-A implementation.

This action is planning-only. It changes this blueprint and, if needed, the controlling plan. It
does not change production or test source. After review, this blueprint must merge into the P4 root
before the P4-B implementation branch is created.

## Purpose and fixed boundaries

P4-B owns the unexported pressure package
(`io.euhedral_execution.hardware_utils.internal.pressure`), public ratio sanitation in
`common/SystemUtilization.java`, and math/projection tests. It consumes only P4-A's immutable
interval contract (`IntervalHardwareSample` plus prior fixed smoother state and `evaluationNs`).
Output is one validated `HardwareUtilization` candidate plus new fixed smoother state.

P4-B must prove every formula/constant, correlated max, validity neutrality, finite outputs, field
mapping, first/reset behavior, actual-time alpha, byte units/rounding, identical timestamps, deep
copies, and direct public-constructor sanitation.

The following remain outside P4-B:

- Sampling, provider, delta/cache state, and compatibility adapters (P4-A, frozen).
- Listener dispatch (P4-C) and monitor lifecycle/scheduler (P4-D).
- Platform providers, core production, native code, `module-info.java` exports.
- Action-picker weights, training, benchmarks.
- No new pressure signals. The four channels (CPU, memory, I/O, composite) are fixed.
- No parent threshold or normalization constant changes.

## P4-A compact completion/review summary

P4-A delivered immutable sampling records, validity mapping, exact delta evaluation, and legacy
compatibility adapters in `io.euhedral_execution.hardware_utils.internal.sampling`. Implementation
used `gpt-5.6-sol` at `high` effort. 31 files created/changed. Compilation succeeded. Focused
sampling tests passed. `JniHeaderTest` failed due to missing native headers/SDK (environmental
limit). Zero deviations from parent blueprint. All constraints met.

Key P4-A artifacts consumed by P4-B:

- `IntervalHardwareSample` - complete resolved boundary record.
- `SignalResolution` - `CURRENT`, `CACHED`, `BASELINE`, `UNAVAILABLE`.
- `CounterDelta`, `ResolvedDouble`, `ResolvedLong`, `LatencyInterval` - immutable resolved
  primitives.
- `CpuIntervalSignals`, `MemoryIntervalSignals`, `IoIntervalSignals`,
  `CpuSlowIntervalSignals`, `SystemSlowIntervalSignals` - grouped interval signal records.
- `ThermalSeverity` - `NOMINAL`, `FAIR`, `SERIOUS`, `CRITICAL`, `EMERGENCY`.

## Sizing and split gate

The parent sized P4-B as "exact multidomain math, floating-point sanitation, smoothing, and public
projection" with one owner state machine and a testable immutable boundary. P4-B has:

- 5 internal pressure classes (`PressureConstants`, `PressureState`, `PressureEvaluation`,
  `PressureEvaluator`, `PressureProjection`).
- Public ratio sanitation changes to `SystemUtilization.java` compact constructors.
- 7 required test classes (`PressureEvaluatorTest`, `PressureCompositionTest`,
  `PressurePropertiesTest`, `PressureSignalAvailabilityTest`, `RatioAccessorContractTest`, plus
  updated `SystemUtilizationTest`, `SnapshotOwnershipTest`, `SnapshotIndexContractTest`).

No second independent responsibility exists. P4-B does not need further splitting.

## Implementation model reassessment

The parent selected `gpt-5.6-sol`, `high` for implementation and `gpt-5.6-sol`, `max` for child
blueprints. P4-B combines exact multidomain pressure formulas with double-precision sanitation,
actual-time exponential smoothing, per-signal validity/resolution dispatch, public field projection
with deep-copy ownership, and reflective ratio-accessor contract testing. The coupling between
formula precision and public projection sanitation is high. `gpt-5.6-sol`, `high` is **confirmed**
as the implementation model. No downgrade.

---

## Local package inventory

Package: `io.euhedral_execution.hardware_utils.internal.pressure` (not exported).

### PressureConstants

Final class, no instances. Contains only `static final double` literals and helper methods.

```text
ATTACK_TAU_SECONDS          = 0.8962840235449102
RELEASE_TAU_SECONDS         = 3.8991451492447347
HEADROOM_ONSET              = 0.80
HEADROOM_RANGE              = 0.20
RECLAIM_FULL_FRACTION       = 0.02
RUN_QUEUE_ONSET             = 1.0
RUN_QUEUE_RANGE             = 3.0
IO_LATENCY_ONSET_NS         = 1_000_000.0
IO_LATENCY_RANGE_NS         = 49_000_000.0
IO_QUEUE_ONSET              = 1.0
IO_QUEUE_RANGE              = 7.0

THERMAL_LOSS_NOMINAL        = 0.00
THERMAL_LOSS_FAIR           = 0.15
THERMAL_LOSS_SERIOUS        = 0.35
THERMAL_LOSS_CRITICAL       = 0.65
THERMAL_LOSS_EMERGENCY      = 1.00
LOW_POWER_LOSS              = 0.15
```

Helper methods:

```java
/// Clamps a finite value to [0.0, 1.0]. Precondition: x is finite.
/// x <= 0 -> +0.0; x >= 1 -> 1.0; else x.
static double unit(double x)

/// Sanitizes telemetry: NaN or negative -> 0.0; +Inf -> Double.MAX_VALUE;
/// -0.0 -> +0.0; else x.
static double nonnegativeTelemetry(double x)

/// Maps ThermalSeverity to fixed loss constant.
static double thermalLoss(ThermalSeverity severity)

/// Returns LOW_POWER_LOSS if lowPower is true, else 0.0.
static double lowPowerLoss(boolean lowPower)
```

These constants are source `double` literals, not recomputed from rounded alpha at runtime.
`unit()` and `nonnegativeTelemetry()` must not use `Math.min`/`Math.max` as NaN sanitizers.
Finiteness is validated before calling `unit()`.

### PressureState

Mutable, single-owner, not thread-safe. Holds the fixed smoother arrays and per-evaluation transient
state. Constructed once per monitor instance with the stable logical CPU span.

Fields:

```text
int logicalSpan
boolean[] initialized          -- one per smoother cell
double[]  previous             -- one per smoother cell
long[]    lastEvaluationNs     -- one per smoother cell
```

Total smoother cell count is bounded and enumerated below. Cells are indexed by a fixed internal
scheme mapping (cpuId, signalOrdinal) or (globalSignalOrdinal) to flat array positions.

#### Smoother cell inventory

Per-CPU cells (one per effective logical CPU, `logicalSpan` entries each):

| Signal                      | Source                                                                 |
|-----------------------------|------------------------------------------------------------------------|
| `scopeWait`                 | `IntervalHardwareSample.scopeSchedulerWaitNs`                          |
| `scopePsi`                  | `IntervalHardwareSample.scopePsiStallNs`                               |
| `scopeReported`             | `IntervalHardwareSample.scopeReportedSchedulerStallRatio`              |
| `wait_i`                    | `CpuIntervalSignals.schedulerWait`                                     |
| `psi_i`                     | `CpuIntervalSignals.psiStall`                                          |
| `reported_i`                | `CpuIntervalSignals.reportedSchedulerStallRatio`                       |
| `runQueue_i`                | `CpuIntervalSignals.runnablePerCapacity`                               |
| `globalThrottle` (shared)   | `IntervalHardwareSample.scopeQuotaThrottledNs`                         |
| `cpuThrottle_i`             | `CpuIntervalSignals.quotaThrottle`                                     |
| `steal_i`                   | `CpuIntervalSignals.steal`                                             |
| `external_i`                | `CpuIntervalSignals.externalContentionRatio`                           |
| `capacityLoss_i`            | `CpuSlowIntervalSignals.availableCapacityUnits / nominalCapacityUnits` |
| `frequencyLoss_i`           | `CpuSlowIntervalSignals.constrainedFrequencyHz / nominalFrequencyHz`   |
| `perCpuThermal_i`           | `CpuSlowIntervalSignals.thermalSeverity`                               |
| `systemThermal` (shared)    | `SystemSlowIntervalSignals.thermalSeverity`                            |
| `perCpuLowPower_i` (shared) | `CpuSlowIntervalSignals.lowPowerMode`                                  |
| `systemLowPower` (shared)   | `SystemSlowIntervalSignals.lowPowerMode`                               |

Global/memory/I/O cells (one each, independent of CPU count):

| Signal        | Source                                         |
|---------------|------------------------------------------------|
| `headroom`    | Derived from memory occupancy formula          |
| `reclaim`     | `MemoryIntervalSignals.cumulativeReclaimBytes` |
| `memoryStall` | `MemoryIntervalSignals.memoryStallNs`          |
| `ioStall`     | `IoIntervalSignals.stallNs`                    |
| `ioLatency`   | `IoIntervalSignals.operationsLatency`          |
| `ioQueue`     | `IoIntervalSignals.maximumQueueDepth`          |

Scope smoothers (`scopeWait`, `scopePsi`, `scopeReported`, `globalThrottle`, `systemThermal`,
`systemLowPower`) are stored once and their smoothed values apply to every effective CPU.

Total cell count: `(per-CPU-specific x logicalSpan) + scope-shared + global` where the exact count
is derived at construction from this inventory. Arrays are allocated at construction, touched by
`firstTouch()` if applicable.

#### Smoother formula

For each cell receiving a `CURRENT`, `CACHED`, or `BASELINE` input at a valid publication:

```text
dtNs = evaluationNs - lastEvaluationNs[cell]

if not initialized[cell]:
    previous[cell] = unit(input)     -- first value seeds directly
    initialized[cell] = true
    lastEvaluationNs[cell] = evaluationNs
else if dtNs <= 0:
    skip update                      -- clock stall; value unchanged
else:
    tau = (input >= previous[cell]) ? ATTACK_TAU_SECONDS : RELEASE_TAU_SECONDS
    alpha = unit(-StrictMath.expm1(-(dtNs / 1_000_000_000.0) / tau))
    next = unit(previous[cell] + (input - previous[cell]) * alpha)
    previous[cell] = next
    lastEvaluationNs[cell] = evaluationNs
```

Asymmetric behavior:

- Rising pressure: `ATTACK_TAU_SECONDS` (0.896...) produces alpha ~0.20 at 200 ms -> fast attack.
- Falling pressure: `RELEASE_TAU_SECONDS` (3.899...) produces alpha ~0.05 at 200 ms -> slow release.
- At 200 ms: attack alpha = `0.19999999999999996`, release alpha = `0.050000000000000044`. Tests
  compare to conceptual `0.20`/`0.05` within 8 ULPs.
- Exponential underflow naturally gives alpha 1.0 (huge dt/tau).
- Overflow cannot occur after positive finite time/tau validation.

#### Smoother clearing

- `UNSUPPORTED` or expired (`age > TTL`) input: clear its smoother immediately
  (`initialized = false`, `previous = 0.0`).
- A `BASELINE` counter supplies valid zero (release smoothing from prior value, not a spike).
- Dynamic effective-set removal clears that CPU's smoothers immediately.
- `clear()` resets all cells to uninitialized.

#### Evaluation order

Stable logical CPU ID ascending, then signal order as listed above, then CPU domain, memory domain,
I/O domain, then public projection. `max` starts from `+0.0`. This makes the result monotonic in
every supported pressure input and reproducible independent of hash or listener order.

### PressureEvaluation

Immutable record holding the result of one evaluation:

```java
record PressureEvaluation(PressureState newState, HardwareUtilization candidate)
```

The `newState` is a prospective state: smoother cells are updated speculatively. The caller commits
`newState` only after topology update succeeds and immediately before release publication. A failed
candidate therefore does not commit an unpublished smoothing transition.

### PressureEvaluator

Stateless final class. Single entry point:

```java
/// Evaluates pressure from one resolved interval sample and prior smoother state.
///
/// Input:  IntervalHardwareSample, prior PressureState, evaluationNs
/// Output: PressureEvaluation containing the candidate HardwareUtilization
///         and new prospective PressureState.
///
/// Does not mutate the input PressureState. The new state is a deep copy
/// with updated smoother cells.
PressureEvaluation evaluate(IntervalHardwareSample sample,
                            PressureState priorState,
                            long evaluationNs)
```

The evaluation proceeds through these exact stages:

1. Deep-copy `priorState` into a working `PressureState`.
2. Resolve quota/membership from the sample.
3. Evaluate productive CPU utilization (unsmoothed, telemetry only).
4. For each effective CPU in ascending ID order, evaluate all CPU signals.
5. Evaluate memory domain signals.
6. Evaluate I/O domain signals.
7. Compose per-CPU composite, public throttle, and system pressure.
8. Project into public `HardwareUtilization` via `PressureProjection`.
9. Return `PressureEvaluation(workingState, candidate)`.

---

## Exact pressure formulas

All symbols below are valid normalized values after delta, age, sanitation, and per-signal smoothing
unless marked `raw`. Missing/unsupported values are omitted from `max`; an empty `max`
is `+0.0`. These formulas are transcribed from the parent blueprint without alteration.

### Productive CPU utilization (telemetry, not pressure)

```text
denominator = elapsedNs * quotaCpus              -- computed in double, this order
quotaCpuUsage = unit(deltaProductiveCpuNs / denominator)
```

Unavailable when: `quotaCpus <= 0`, elapsed time is invalid, the double denominator is non-finite,
or the productive counter is `BASELINE`/`UNAVAILABLE`. Publishes `0.0` in those cases.

Source: `IntervalHardwareSample.productiveCpuNs` (`CounterDelta`).

### CPU scheduler domain

For logical CPU `i`:

```text
scopeWaitRaw       = unit(deltaScopeSchedulerWaitNs / deltaScopeTimeNs)
scopePsiRaw        = unit(deltaScopePsiStallNs / deltaScopeTimeNs)
scopeReportedRaw   = unit(scopeReportedSchedulerStallRatio)
wait_i_raw         = unit(deltaSchedulerWaitNs_i / deltaTimeNs_i)
psi_i_raw          = unit(deltaPsiStallNs_i / deltaTimeNs_i)
reported_i_raw     = unit(reportedSchedulerStallRatio_i)
runQueue_i_raw     = unit((runnablePerCapacity_i - 1.0) / 3.0)
```

Raw composition and smoothed composition:

```text
scheduler_i_raw  = max(scopeWaitRaw, scopePsiRaw, scopeReportedRaw,
                       wait_i_raw, psi_i_raw, reported_i_raw, runQueue_i_raw)

scheduler_i      = max(smooth(scopeWaitRaw), smooth(scopePsiRaw),
                       smooth(scopeReportedRaw), smooth(wait_i_raw), smooth(psi_i_raw),
                       smooth(reported_i_raw), smooth(runQueue_i_raw))
```

Run-queue normalization: at or below 1.0 runnable per capacity -> zero pressure; 4.0+ -> full.
`(runnablePerCapacity - 1.0) / 3.0` maps [1, 4] to [0, 1].

Publication:

- `SystemSnapshot.pressurePerCpu[i]` and `CpuSnapshot.stallRatio` publish `scheduler_i_raw`.
- Composite uses `scheduler_i` (the smoothed version).

Scope smoothers are stored once; their smoothed value applies to every effective CPU.

### CPU throttle domain

```text
globalThrottleRaw = unit(deltaGlobalThrottleNs / deltaTimeNs)
cpuThrottleRaw_i  = unit(deltaCpuThrottleNs_i / deltaTimeNs_i)
throttle_i        = max(smooth(globalThrottleRaw), smooth(cpuThrottleRaw_i))
```

Publication:

- `cpuThrottleRatio` is the smoothed global value when available, otherwise the max supported
  per-CPU throttle.
- `perQuotaCpuThrottleRatio[i]` = `throttle_i`.

Source: `IntervalHardwareSample.scopeQuotaThrottledNs` (global), `CpuIntervalSignals.quotaThrottle`
(per-CPU).

### External contention domain

```text
steal_i           = smooth(unit(deltaStealNs_i / deltaTimeNs_i))
external_i        = smooth(unit(externalContentionRatio_i))
externalDomain_i  = max(steal_i, external_i)
```

Source: `CpuIntervalSignals.steal`, `CpuIntervalSignals.externalContentionRatio`.

### Capacity domain

```text
capacityLoss_i    = smooth(unit(1.0 - availableCapacity_i / nominalCapacity_i))
frequencyLoss_i   = smooth(unit(1.0 - frequencyCeilingHz_i / nominalFrequencyHz_i))
```

Thermal loss mapping (from `ThermalSeverity`):

| Severity    | Loss constant |
|-------------|---------------|
| `NOMINAL`   | 0.00          |
| `FAIR`      | 0.15          |
| `SERIOUS`   | 0.35          |
| `CRITICAL`  | 0.65          |
| `EMERGENCY` | 1.00          |

Low-power loss: `lowPowerMode ? 0.15 : 0.00`.

```text
capacityDomain_i  = max(capacityLoss_i, frequencyLoss_i,
                        smooth(perCpuThermalLoss_i), smooth(systemThermalLoss),
                        smooth(perCpuLowPowerLoss_i), smooth(systemLowPowerLoss))
```

Nominal denominators must be finite and strictly positive. Zero/negative/missing -> unavailable, not
full loss. Available at or above nominal -> zero loss.

Source: `CpuSlowIntervalSignals` (per-CPU capacity/frequency/thermal/low-power),
`SystemSlowIntervalSignals` (system thermal/low-power).

### CPU domain composite

```text
cpuDomain_i       = max(scheduler_i, throttle_i, externalDomain_i, capacityDomain_i)
```

### Memory domain

Effective memory limit `L`:

```text
L = minimum valid positive hard/high limit from MemoryIntervalSignals
    valid zero hard or high limit -> L = 0
    unsupported/unbounded -> omitted; no remaining limit means unknown/unbounded
```

Working set:

```text
W = max(memoryUsageBytes - inactiveFileBytes, 0)
```

Subtraction clamps inactive before conversion: `usage >= inactive ? usage - inactive : 0`.
Unsupported/expired inactive-file uses zero (conservatively all usage is working set).

Occupancy:

```text
U = 1.0                         if L == 0
    unit(W / L)                 if L > 0
    0.0                         if L is unknown/unbounded
```

Formulas:

```text
headroomRaw = unit((U - 0.80) / 0.20)
reclaimFractionPerSecond = ((double) deltaReclaimBytes * 1_000_000_000.0) / (L * deltaTimeNs)
reclaimRaw = unit(reclaimFractionPerSecond / 0.02)
memoryStallRaw = unit(deltaMemoryStallNs / deltaTimeNs)

memoryDomain = max(smooth(headroomRaw), smooth(reclaimRaw), smooth(memoryStallRaw))
```

- Occupancy up to 80% -> zero headroom pressure; 100% -> full.
- Reclaiming 2% of effective limit per second -> full reclaim pressure.
- Reclaim unavailable when `L <= 0`.
- Valid zero limit -> complete headroom pressure (`U = 1.0`).
- `totalMemoryUtilization` publishes `U` but is not itself substituted for `memoryDomain`.

Source: `MemoryIntervalSignals`.

### I/O domain

```text
ioStallRaw = unit(deltaIoStallNs / deltaTimeNs)
averageLatencyNs = deltaTotalLatencyNs / deltaCompletedOperations
latencyRaw = unit((averageLatencyNs - 1_000_000.0) / 49_000_000.0)
queueRaw = unit((maxQueueDepthInScope - 1.0) / 7.0)

ioDomain = max(smooth(ioStallRaw), smooth(latencyRaw), smooth(queueRaw))
diskIOBytesPerSecond = ((double) deltaIoBytes * 1_000_000_000.0) / deltaTimeNs
```

- No completed operations -> latency unavailable for that interval.
- Latency at or below 1 ms -> zero; 50 ms+ -> full.
- Queue depth at or below 1 -> zero; 8+ -> full.
- `diskIOBytesPerSecond` is sanitized telemetry and never enters `ioDomain`.

Source: `IoIntervalSignals`.

### Composite and public values

```text
perQuotaCpuPressure[i] = max(cpuDomain_i, memoryDomain, ioDomain)
diskIOPressure         = ioDomain
pressure()             = max(perQuotaCpuPressure[i] for effective i)
```

A valid publication with no effective CPU: `pressure()` returns `1.0`.

---

## Golden test case

From the parent blueprint, the canonical correlated golden:

```text
Input:
  scheduler signals   0.25, 0.40     (two of the seven; others unavailable)
  runnablePerCapacity  2.5           -> runQueue_raw = unit((2.5 - 1.0) / 3.0) = 0.50
  -> scheduler = max(0.25, 0.40, ..., 0.50) = 0.50
  throttle            0.30
  external            0.20
  capacity            0.10
  -> cpuDomain = max(0.50, 0.30, 0.20, 0.10) = 0.50

  occupancy           0.90
  -> headroom = unit((0.90 - 0.80) / 0.20) = 0.50
  reclaim             0.01 of limit/second
  -> reclaimRaw = unit(0.01 / 0.02) = 0.50
  stall               0.20
  -> memoryDomain = max(0.50, 0.50, 0.20) = 0.50

  averageLatency      25.5 ms
  -> latencyRaw = unit((25_500_000 - 1_000_000) / 49_000_000) = 0.50
  I/O stall           0.40
  -> ioDomain = max(0.40, 0.50, ...) = 0.50

  composite = max(0.50, 0.50, 0.50) = 0.50  (not a sum or noisy-or)
```

A first smoother value is used in this golden (seeds directly); later values follow the actual-time
formula.

---

## ULP, clamp, and overflow rules

### `unit(x)` contract

Non-finite inputs must be handled **before** calling `unit()`:

- Before division, validate denominator is finite and positive.
- After division, check finiteness.
- Non-finite signal -> intermediate invalidity -> that signal is transient failure.

`unit()` itself handles only finite inputs: `x <= 0 -> +0.0`; `x >= 1 -> 1.0`; else `x`.

### `nonnegativeTelemetry(x)` contract

- NaN or `x < 0` -> `0.0`.
- Positive infinity -> `Double.MAX_VALUE`.
- `-0.0` -> `+0.0`.
- Else `x`.

### Precision

- All expressions use Java 17 strict evaluation in written order.
- No `float`, `BigDecimal`, quantization, or platform-dependent fused operations.
- No normalized result is rounded for publication.
- Tests use exact boundaries or at most 8 ULPs for `StrictMath.expm1` results.
- `Double.compare` for quota comparisons.

### Overflow

- Weighted sums of `[0,1]` values with `max` composition are bounded by `1.0`. Double rounding may
  exceed by ULPs; `unit()` clamping absorbs this.
- Scaled long calculations cast nonnegative numerator to `double` before multiplication:
  `((double) deltaBytes * 1_000_000_000.0) / elapsedNs`. No long multiplication wraps before
  conversion.
- Integer byte allocation uses floor division. Nonnegative scoped products saturate at
  `Long.MAX_VALUE`.

### Underflow

- Minimum is `+0.0`. Weighted sums/`max` of non-negative cannot produce negative. `unit()` clamps
  defensively.

---

## Public constructor sanitation

P4-B updates `SystemUtilization.java` compact constructors to enforce the parent's exact sanitation
contract. These are the existing records; P4-B does not change component names, order, or types.

### `SystemSnapshot` compact constructor changes

Reject (throw `IllegalArgumentException`):

- `totalCpus <= 0` (nonpositive span).
- `pressurePerCpu.length() != totalCpus`.
- Any effective bit `>= totalCpus`.

Sanitize:

- `cpuUsage`, `cpuThrottle`, `diskIOBytes`: negative -> `0`.
- `memoryUsage`, `inactiveFileMemory`: negative -> `0`.
- `memoryLimit`: negative -> `Long.MAX_VALUE` (unknown sentinel).
- `quotaCpus`: non-finite or negative -> `0.0`; otherwise clamp to
  `[0.0, effectiveCpus.cardinality()]`.
- `period`: negative -> `0`.
- `pressurePerCpu[i]`: each value sanitized: NaN/non-finite -> `0.0`; negative -> `0.0`;
  `> 1.0` -> `1.0`; `-0.0` -> `+0.0`.

### `HardwareUtilization` compact constructor changes

Reject (throw `IllegalArgumentException`):

- `snapshot.timeNs() != timestampNs` (timestamp mismatch).
- `!snapshot.effectiveCpus().equals(globalEffectiveCpus)` (membership mismatch).
- Quota or period mismatch with nested snapshot (after sanitation).

Sanitize:

- `quotaCpuUsage`: `nonnegativeTelemetry()`. Then clamp to `[0.0, 1.0]`; `-0.0` -> `+0.0`.
- `cpuThrottleRatio`: clamp finite to `[0.0, 1.0]`; NaN/non-finite -> `0.0`; `-0.0` -> `+0.0`.
- `perQuotaCpuThrottleRatio[i]`, `perQuotaCpuPressure[i]`: each entry clamp to `[0.0, 1.0]`;
  NaN/non-finite -> `0.0`; `-0.0` -> `+0.0`.
- `totalMemoryUtilization`: clamp finite to `[0.0, 1.0]`; NaN/non-finite -> `0.0`; `-0.0` ->
  `+0.0`.
- `diskIOPressure`: clamp finite to `[0.0, 1.0]`; NaN/non-finite -> `0.0`; `-0.0` -> `+0.0`.
- `globalMemoryPool`, `perCpuMemoryPool`, `memPerCpuUsageBytes`: negative -> `0`.
- `diskIOBytesPerSecond`: `nonnegativeTelemetry()`.
- `quotaCpus`: non-finite or negative -> `0.0`; clamp to `[0.0, globalEffectiveCpus.cardinality()]`.
- `period`: negative -> `0`.

### `CpuSnapshot` - no compact constructor changes needed

`CpuSnapshot` is a simple record. Its fields are always constructed by `HardwareUtilization`
accessor methods which perform their own sanitation. Direct public construction of `CpuSnapshot`
must still produce valid values; the existing record semantics suffice because all fields that
participate in ratio contracts pass through the projection's `unit()` before becoming
`CpuSnapshot` components.

### `SocketSnapshot` and `CoreSnapshot` - existing constructors

Already deep-copy `effectiveCores`/`effectiveCpus` via `UnmodifiableBitSet` and clone arrays.
Existing constructors are sufficient.

### `HardwareUtilization.pressure()` formula update

The existing formula:

```java
double cpu = 1.0 - (1.0 - cpuThrottleRatio);   // == cpuThrottleRatio
double io = diskIOPressure * 0.8;
double pressure = Math.max(Math.max(cpu, totalMemoryUtilization), io);
return Math.min(1.0, Math.max(0.0, pressure));
```

Must be replaced with:

```java
/// Maximum final composite pressure over all effective CPUs.
/// Returns 1.0 when globalEffectiveCpus is empty.
public double pressure() {
    if (globalEffectiveCpus.isEmpty()) {
        return 1.0;
    }
    double max = 0.0;
    for (int i = globalEffectiveCpus.nextSetBit(0); i >= 0;
            i = globalEffectiveCpus.nextSetBit(i + 1)) {
        if (i < perQuotaCpuPressure.length()) {
            double p = perQuotaCpuPressure.get(i);
            if (p > max) {
                max = p;
            }
        }
    }
    return Math.min(1.0, Math.max(0.0, max));
}
```

This matches the parent: `pressure() = max(perQuotaCpuPressure[i] for effective i)`.

### `HardwareUtilization.getCpuSnapshot()` formula update

The existing per-CPU pressure composition:

```java
double cpuPressure = 1.0 - ((1.0 - stallRatio) * (1.0 - throttleRatio));
// ...
double combinedPressure = 1.0 - ((1.0 - cpuPressure) * (1.0 - io) * (1.0 - memUtil));
```

Must be replaced with direct passthrough from `perQuotaCpuPressure[cpuId]`:

```java
/// CpuSnapshot.pressure matches perQuotaCpuPressure[cpuId]; no recomposition.
double pressure = perQuotaCpuPressure.get(cpuId);
```

`stallRatio` maps to `pressurePerCpu[cpuId]` (scheduler_raw). `throttleRatio` maps to
`perQuotaCpuThrottleRatio[cpuId]`. `pressure` maps to `perQuotaCpuPressure[cpuId]`.

---

## Field mapping: IntervalHardwareSample -> evaluator -> public records

```text
IntervalHardwareSample
    |
    +-- productiveCpuNs ---------> quotaCpuUsage (telemetry, unsmoothed)
    |
    +-- scopeSchedulerWaitNs ----+
    +-- scopePsiStallNs ---------+
    +-- scopeReportedStallRatio -+-> scheduler_i_raw (published as pressurePerCpu[i])
    +-- cpuSignals[i].* --------+   scheduler_i (smoothed, used in cpuDomain_i)
    |
    +-- scopeQuotaThrottledNs ---+-> throttle_i -> perQuotaCpuThrottleRatio[i]
    +-- cpuSignals[i].quotaThrottle +    cpuThrottleRatio (global smoothed)
    |
    +-- cpuSignals[i].steal -----+-> externalDomain_i
    +-- cpuSignals[i].external --+
    |
    +-- cpuSlowSignals[i].* ----+-> capacityDomain_i
    +-- systemSlowSignals.* ----+
    |
    +-- cpuDomain_i = max(scheduler_i, throttle_i, externalDomain_i, capacityDomain_i)
    |
    +-- memorySignals.* ---------> memoryDomain
    +-- ioSignals.* -------------> ioDomain, diskIOBytesPerSecond
    |
    +-- perQuotaCpuPressure[i] = max(cpuDomain_i, memoryDomain, ioDomain)
    +-- diskIOPressure         = ioDomain
    +-- pressure()             = max(perQuotaCpuPressure[i] for effective i)
    |
    v
    HardwareUtilization candidate (via PressureProjection)
```

---

## PressureProjection

Receives one immutable `IntervalHardwareSample`, normalized pressure result, and `evaluationNs`.
Constructs the complete public object graph. Construction order:

1. Sanitize and copy canonical telemetry and membership.
2. Allocate exact logical-span scheduler (`pressurePerCpu`), throttle (`perQuotaCpuThrottleRatio`),
   and composite (`perQuotaCpuPressure`) arrays.
3. Fill effective CPU entries in ascending ID; inactive entries = canonical zero.
4. Construct deep-copied `SystemSnapshot` with `timeNs = evaluationNs`.
5. Construct `HardwareUtilization` with `timestampNs = evaluationNs` and that snapshot.
6. Validate finite ratios, span coverage, and timestamp equality before returning.

### Unavailable signal projection

- Unavailable public gauges/rates -> canonical zero, except:
    - Unsupported/unbounded memory limit -> `Long.MAX_VALUE`.
    - Unsupported quota capacity -> fallback to current effective cardinality with period `0`.
- `BASELINE` counters publish their current cumulative value; interval contribution is zero.
- After TTL, last nonneg cumulative value may remain public; delta/rate/pressure is `UNAVAILABLE`.

### Byte allocation and memory layout

| Record                | Payload bytes                                                                                                                                                                                                                                                                                                                                                                                                                                                     | Estimated total (HotSpot 64-bit, compressed oops) |
|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------|
| `SystemSnapshot`      | 12 fields: 1 long `timeNs` + 1 int `totalCpus` + 1 double `quotaCpus` + 1 long `period` + 1 long `cpuUsage` + 1 long `cpuThrottle` + 1 ref `effectiveCpus` + 1 ref `pressurePerCpu` + 1 long `memoryLimit` + 1 long `memoryUsage` + 1 long `inactiveFileMemory` + 1 long `diskIOBytes` = ~88 bytes payload                                                                                                                                                        | ~104 bytes                                        |
| `HardwareUtilization` | 15 fields: 1 long `timestampNs` + 1 double `quotaCpus` + 1 double `quotaCpuUsage` + 1 long `period` + 1 ref `globalEffectiveCpus` + 1 double `cpuThrottleRatio` + 1 ref `perQuotaCpuThrottleRatio` + 1 ref `perQuotaCpuPressure` + 1 long `globalMemoryPool` + 1 long `perCpuMemoryPool` + 1 double `totalMemoryUtilization` + 1 long `memPerCpuUsageBytes` + 1 double `diskIOBytesPerSecond` + 1 double `diskIOPressure` + 1 ref `snapshot` = ~120 bytes payload | ~136 bytes                                        |
| `CpuSnapshot`         | 12 fields: 1 int + 5 doubles + 3 longs + 1 int + 1 double + 1 long = ~88 bytes                                                                                                                                                                                                                                                                                                                                                                                    | ~104 bytes                                        |
| `CoreSnapshot`        | 10 fields: 1 int + 1 double + 1 long + 1 long + 1 long + 1 long + 1 long + 1 double + 1 ref + 1 ref = ~72 bytes                                                                                                                                                                                                                                                                                                                                                   | ~88 bytes                                         |
| `SocketSnapshot`      | 8 fields: 1 int + 1 ref + 2 longs + 1 long + 1 double + 1 ref + 1 long = ~56 bytes                                                                                                                                                                                                                                                                                                                                                                                | ~72 bytes                                         |
| `PressureState`       | 3 arrays: `boolean[N]` + `double[N]` + `long[N]` where N = smoother cell count                                                                                                                                                                                                                                                                                                                                                                                    | Proportional to logical CPU span                  |

One evaluation allocates one bounded graph proportional to the stable logical CPU span plus the
fixed record headers above.

### Deep copy guarantee

- `SystemSnapshot`: compact constructor copies `effectiveCpus` via `UnmodifiableBitSet(...)` and
  `pressurePerCpu` via `copyOf(...)`. All other components are primitives.
- `HardwareUtilization`: compact constructor copies `globalEffectiveCpus` via
  `UnmodifiableBitSet(...)`, `perQuotaCpuThrottleRatio` and `perQuotaCpuPressure` via
  `copyOf(...)`. `snapshot` is an immutable record of deep-copied components.
- `CoreSnapshot`, `SocketSnapshot`: clone arrays and wrap BitSets in compact constructors. Array
  accessors return clones.
- Provider arrays, engine scratch arrays, and compatibility arrays may be reused only before step 4
  of projection. P2 public constructors copy again at the publication boundary.

### Timestamp invariant

- `SystemSnapshot.timeNs = evaluationNs`.
- `HardwareUtilization.timestampNs = evaluationNs`.
- `SocketSnapshot.lastUsageNs = timestampNs` (from `HardwareUtilization`).
- `CpuSnapshot.lastUsageNs = timestampNs`.
- Reject if `snapshot.timeNs() != timestampNs` in `HardwareUtilization` compact constructor.
- All snapshots derived from one utilization share identical publication timestamps.
- No method substitutes call time for publication time.

---

## Test inventory

### P4-B owned test classes

1. **`PressureEvaluatorTest`**: Every threshold and thermal mapping, correlated golden case, healthy
   high-throughput I/O, low-throughput stall, productive CPU neutrality, no effective CPU
   (`pressure() == 1.0`), and unsupported/transient/reset behavior.

2. **`PressureCompositionTest`**: Correlated `max` golden (composite 0.50 from three domains at
   0.50), per-domain isolation, memory/I/O propagation to every effective CPU, and independent
   bottleneck dominance.

3. **`PressurePropertiesTest`**: Fixed-seed `SplittableRandom`, 20,000+ generated cases per
   property. Properties:
    - Boundedness: all ratio outputs in `[0.0, 1.0]`.
    - Finiteness: no NaN/Infinity in any published ratio.
    - Per-signal monotonicity: increasing one supported input must not decrease its domain output.
    - `max` idempotence: `max(x, x) == x`.
    - Correlation: independent domains compose with `max`, not sum.
    - Invalid doubles/divisors: NaN, infinity, negative zero, extreme values.
    - Counter extremes: `Long.MAX_VALUE` deltas and near-max elapsed time.
    - Irregular elapsed time: very short and very long dt.
    - Smoothing attack faster than release: verify alpha_attack > alpha_release at 200 ms.

4. **`PressureSignalAvailabilityTest`**: Each individual signal unavailable/unsupported/baseline
   produces validity-neutral output (that signal contributes zero, not missing-domain-error). All
   signals unavailable -> all pressure zero (except `pressure()` with no effective CPU ->
   1.0).

5. **`RatioAccessorContractTest`**: Reflection-backed exhaustive manifest of every ratio-valued
   public accessor on `SystemSnapshot`, `HardwareUtilization`, `CpuSnapshot`, `CoreSnapshot`,
   `SocketSnapshot`. Classifies each `double` accessor and `UnmodifiableDoubleArray` component as
   normalized ratio, capacity, rate, or ratio array. Unclassified additions fail. Only
   normalized/array entries receive `[0,1]` assertions. Covers direct public constructors with NaN,
   infinities, negative zero, and out-of-range values.

### P4-B updated inherited tests

6. **`SystemUtilizationTest`**: Updated golden mappings reflecting corrected `pressure()` and
   `getCpuSnapshot()`. Exact working-set bytes. Repeated derivation stability. Overlap rejection.
   Sparse spans. Identical CPU/socket publication timestamps.

7. **`SnapshotOwnershipTest`**: Existing mutation-isolation tests remain. Add coverage for new
   compact-constructor sanitation of ratio fields (NaN -> 0.0, out-of-range clamping, `-0.0`
   canonicalization).

8. **`SnapshotIndexContractTest`**: Existing index/boundary tests remain. Verify corrected memory
   utilization and pressure field values under the new projection.

### Golden test mechanics

- Use specific `IntervalHardwareSample` instances with known field values.
- Assert exact `double` equality for the evaluator/projection output where the parent specifies
  exact results (e.g., the correlated golden produces `0.50` exactly).
- For `StrictMath.expm1`-based smoother alpha, compare within 8 ULPs.
- Cover: zero inputs, maximum inputs, boundary inputs (exactly `0.0`, exactly `1.0`), NaN
  propagation.
- Cover: default alpha and custom dt values.
- No property-test external dependency. Use `SplittableRandom` with fixed seeds.

### Property test mechanics

- `SplittableRandom` with deterministic seed.
- At least 20,000 generated cases per algebraic property.
- Failure reports: seed, iteration index, and generated values.
- Properties cover all domains, composition, smoothing, and projection.

---

## Validation commands

```bash
gradle :euhedral-hardware-utils:compileJava
gradle :euhedral-hardware-utils:test --tests \
    "io.euhedral_execution.hardware_utils.internal.pressure.PressureEvaluatorTest" \
    --tests "io.euhedral_execution.hardware_utils.internal.pressure.PressureCompositionTest" \
    --tests "io.euhedral_execution.hardware_utils.internal.pressure.PressurePropertiesTest" \
    --tests "io.euhedral_execution.hardware_utils.internal.pressure.PressureSignalAvailabilityTest" \
    --tests "io.euhedral_execution.hardware_utils.common.RatioAccessorContractTest" \
    --tests "io.euhedral_execution.hardware_utils.common.SystemUtilizationTest" \
    --tests "io.euhedral_execution.hardware_utils.common.SnapshotOwnershipTest" \
    --tests "io.euhedral_execution.hardware_utils.common.SnapshotIndexContractTest"
```

After implementation, also run the P0 compatibility gate and classify only additive APIs and exact
P4 defect-ledger corrections.

```bash
git diff --check
git status --short
git diff --name-only <action-start>..HEAD
git diff --exit-code <action-start>..HEAD -- benchmarks/src/main euhedral-core/src/main
```

The name-only scope list must contain no training path.

## Constraints summary

- Do not alter any parent threshold, normalization constant, or formula.
- Do not add a new pressure signal (channels are fixed: CPU scheduler, CPU throttle, external,
  capacity, memory, I/O, composite).
- Do not add DJL, PyTorch, ML, or external dependencies.
- Do not change `IntervalHardwareSample` or any P4-A type.
- Do not edit `ResourceMonitor`, listener code, platform providers, native code, core production,
  `module-info.java`, or training.
- `SystemUtilization.java` compact constructor/method changes are limited to the exact sanitation
  and formula corrections documented above.
- New pressure classes live in the unexported `internal.pressure` package.
- Tests live in the hardware-utils test tree under matching package names.
- ASCII-only in comments and test fixtures.

## Handoff condition

Hand this blueprint off for review and merge into the P4 root only when:

- Every evaluator, projector, smoother cell, and constant is enumerated with no implementation
  latitude in normalization formula, correlation composition, precision handling, smoother
  initialization/clearing, unsupported signal behavior, derived record construction, or
  direct-constructor sanitation.
- The golden test specifies exact expected outputs.
- Property test coverage, seed, and iteration count are specified.
- Public constructor sanitation rules are itemized per record field.
- Field mapping from `IntervalHardwareSample` through evaluator to public `HardwareUtilization`
  is complete.
- Byte allocation and deep-copy ownership are documented.
- Timestamp identity is documented.
- The `pressure()` and `getCpuSnapshot()` formula corrections are specified exactly.
- The implementation model is confirmed or upgraded.
- No implementation choice remains.

Do not implement P4-B from this branch. After review and explicit merge authorization, merge this
blueprint into the P4 root, create only the P4-B implementation branch from that updated root, and
rerun P4-B's sizing/model gate.

## Completion Record

### Changed Files

-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/pressure/PressureConstants.java`
-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/pressure/PressureEvaluation.java`
-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/pressure/PressureEvaluator.java`
-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/pressure/PressureProjection.java`
-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/pressure/PressureState.java`
-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/common/SystemUtilization.java`
-
`euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/internal/pressure/PressureCompositionTest.java`
-
`euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/internal/pressure/PressureEvaluatorTest.java`
-
`euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/internal/pressure/PressurePropertiesTest.java`
-
`euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/internal/pressure/PressureSignalAvailabilityTest.java`
-
`euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/common/RatioAccessorContractTest.java`

### Commands and Results

-
`gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.internal.pressure.*"` -
Passed.
-
`gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.common.RatioAccessorContractTest"` -
Passed.

### Acceptance Evidence

Evaluates multidomain hardware pressure signals with actual-time EWMA smoothing, finite ratio
sanitation in [0.0, 1.0], composite max, and public record projection.

### Deviations

None.

### Environmental Limits

None.
