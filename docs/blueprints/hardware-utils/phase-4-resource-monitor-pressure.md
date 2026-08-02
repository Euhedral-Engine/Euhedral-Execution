# Phase 4 Resource Monitor and Pressure Blueprint

## Status and authority

This is the implementation-ready parent blueprint for P4. The developer's P4 authorization
designates `hardware-utils-overhaul/phase-3-affinity-executor` at `748f34d5` as the completed P3
predecessor. The P4 root is `hardware-utils-overhaul/phase-4-pressure-monitor`; this document is
prepared on `hardware-utils-overhaul/phase-4-pressure-monitor-blueprint`.

This action is planning-only. It changes this blueprint, the controlling plan, and the narrow
developer-authorized integrated-conformance exception in `docs/AGENT_WORKFLOW.md`; it does not
change production or test source, inspect training, or authorize a commit, merge, push,
implementation branch, or child branch. After review, this parent blueprint must merge into the
P4 root before the first child branch is created.

## Purpose and fixed boundaries

P4 replaces the common resource-monitor contract with one deterministic path:

```text
platform provider or legacy SystemSnapshotProvider
                    |
                    v
       immutable detailed hardware samples
       canonical units + per-signal validity/time
                    |
                    v
       fixed-field delta, cache, and age state
                    |
                    v
       normalized and smoothed pressure signals
                    |
                    v
       complete immutable HardwareUtilization candidate
                    |
                    v
       TopologyMapper.update(candidate)
                    |
                    v
       one atomic release publication
                    |
                    v
       latest-value listener dispatcher
```

P4 owns common sampling records and validity, legacy-provider compatibility adapters, common
delta/staleness state, pressure mathematics, public snapshot projection, listener dispatch, and
`ResourceMonitor` scheduling/lifecycle. It may change hardware Java and hardware tests only as
assigned to a child below.

The following remain outside P4:

- Linux, Windows, and macOS collection corrections or new native calls are P5-P7.
- `ControlPlaneFragment`, `ControlPlaneCache`, and every other core production file are read-only;
  their policy changes are P8.
- Public `SystemUtilization` record component names, order, and types do not change.
- `module-info.java` exports do not change. New SPI types live in an unexported internal package.
- Action-picker weights, policy training, benchmarks, corpora, and every training path or command
  are prohibited.
- Pressure never changes effective CPU membership or topology version.

## Bounded implementation context envelope

All P4 actions inherit only these inputs:

- `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, and the architecture and P4 summary in the controlling
  plan;
- this parent blueprint and, for a child, that child's completed blueprint/completion record;
- the exact P0-P3 artifact-index entries and compact closeout summaries, not an unbounded history;
- `euhedral-hardware-utils/pom.xml`, `module-info.java`, `ResourceMonitor`,
  `SystemSnapshotProvider`, all records in `SystemUtilization`, and the child-owned package/tests;
- current platform resource providers only as compatibility input; their collection bodies are
  read-only in P4; and
- read-only pressure call sites in `ControlPlaneLattice`, `ControlPlaneFragment`, and
  `ControlPlaneCache`, plus their named focused tests. No other core inventory is needed.

Owned outputs are the four child blueprint/completion records, their bounded source/test changes,
and one integrated P4 conformance record at the end. A child must not read another child's full
source history: it consumes the parent contract plus the preceding child's published interface and
compact completion/review summary.

## Evidence and current defects

The inherited P0-P3 artifacts establish the public API baseline, immutable snapshot ownership,
stable logical CPU indexing, topology update semantics, and managed hardware lifecycle behavior.
The P3 root audit classifies all 16 parent criteria and A01-A02 as satisfied. P1-A and the P1-B
audit paths absent from the current tree are intentionally absent historical artifacts and are not
reconstructed.

Current code establishes the following P4 repair inputs:

- `ResourceMonitor` samples in its constructor, samples again in `start`, polls concurrently from
  stopped reads, uses a period-derived fixed EWMA coefficient, and subtracts poll cost twice.
- Counter regression and first-sample baselines are not defined. Unit conversions mix ratios,
  microseconds, and nanoseconds.
- Listener work uses the common pool, is unbounded and overlapping, and holds a spin guard while
  invoking callbacks; a reentrant `addListener` can deadlock and an `Error` can wedge the guard.
- Mutable evaluation fields are published incrementally before one complete result exists.
- Throughput is treated as I/O pressure, productive work leaks into pressure, and the existing
  CPU, memory, and throttle formulas are dimensionally or semantically incorrect.
- Linux pressure is scope-mismatched host apportionment, Windows pressure is productive busy time,
  and macOS pressure is host load adjusted by process utilization. P4 adapters must mark those
  legacy signals unsupported rather than make them authoritative before P5-P7.
- `ControlPlaneFragment` consumes only `CpuSnapshot.pressure()`. `ControlPlaneCache` applies its
  own already-settled control-policy hysteresis. Both remain read-only.

## Sizing and split gate

One P4 implementation fails every split-gate test. It combines four independently testable
responsibilities, three independent concurrency/state machines, mathematical precision, public
compatibility projection, and broad failure coverage. A single implementation context would need
to hold all platform adapter inputs, counter recovery, pressure math, Java Memory Model edges,
lifecycle transitions, and adversarial listener behavior at once.

P4 is therefore split, in this mandatory order:

| Child | Responsibility                                                                       | Branch family                                                                     |
|-------|--------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| P4-A  | Immutable sample/validity SPI, legacy adapter, delta/age/slow-cache state            | `hardware-utils-overhaul/phase-4-sample-validity-{blueprint,implementation}`      |
| P4-B  | Normalization, smoothing, public field projection, immutable snapshots               | `hardware-utils-overhaul/phase-4-pressure-math-{blueprint,implementation}`        |
| P4-C  | Bounded latest-value listener registry/dispatcher and close barrier                  | `hardware-utils-overhaul/phase-4-listener-publication-{blueprint,implementation}` |
| P4-D  | `ResourceMonitor` duration validation, lifecycle, scheduler, integration/publication | `hardware-utils-overhaul/phase-4-monitor-lifecycle-{blueprint,implementation}`    |

Each blueprint and implementation is reviewed and merged into the updated P4 root before the next
action starts. Each child reruns the split and implementation-model gates. By explicit developer
direction, P4 has no per-child validation, conformance, or audit actions and no root implementation
or validation branch. After P4-D implementation merges, the only conformance action runs on
`hardware-utils-overhaul/phase-4-pressure-monitor-audit` and audits the integrated P4 result.

The child envelopes later in this document show that each child has one owner state machine and a
testable immutable boundary. None needs another split now. If a child discovers a second
independent responsibility, it must stop in its blueprint and split again; it may not defer design
to implementation.

## Package ownership and names

The settled high-reasoning packages and types are:

- `io.euhedral_execution.hardware_utils.internal.sampling`
    - `DetailedSystemSnapshotProvider`
    - `SignalValidity`
    - `CounterSignal`, `LongGaugeSignal`, `DoubleGaugeSignal`, `BooleanSignal`, and
      `ThermalSignal`
    - `CpuFastSignals`, `MemoryFastSignals`, `IoFastSignals`, `CpuSlowSignals`,
      `SystemSlowSignals`, `FastHardwareSample`, and `SlowHardwareSample`
    - `SignalResolution`, `CounterDelta`, `ResolvedLong`, `ResolvedDouble`,
      `LatencyInterval`, and `IntervalHardwareSample`
    - `SampleStateEngine` and `SlowSampleCache`
    - `ThermalSeverity`, `CompatibilityProfile`, `SystemSnapshotCompatibilityAdapter`, and its
      fixed legacy mappings
- `io.euhedral_execution.hardware_utils.internal.pressure`
    - `PressureConstants`, `PressureState`, `PressureEvaluation`, `PressureEvaluator`, and
      `PressureProjection`
- `io.euhedral_execution.hardware_utils.internal.monitor`
    - `LatestValueDispatcher`
    - `MonotonicClock`, `DeadlineWaiter`, `TopologyUpdater`, and JDK `ThreadFactory` injection used
      by `ResourceMonitor`/the dispatcher

These types may be `public` so sibling packages in the same module can use them, but their package
is not exported. `DetailedSystemSnapshotProvider` is the internal rich SPI; the existing public
`SystemSnapshotProvider` remains the public compatibility contract.
`DetailedSystemSnapshotProvider extends SystemSnapshotProvider` and adds `sampleFast`/`sampleSlow`
without a default `getSnapshot`; rich built-in providers continue satisfying their public
compatibility method while `ResourceMonitor` uses the detailed methods. Do not add a module export,
service declaration, reflection registry, or dependency.

New minor records/helpers may be named by a child where mechanically implied by this schema.
Renaming or moving the three package boundaries, changing their responsibilities, or introducing
another public recovery channel is a parent-blueprint decision.

Every new class/method and every changed signature receives the workflow-required adjacent `///`
contract comment covering unit, ownership, validity, ordering, or failure semantics. Comments and
test fixtures use ASCII only.

## Detailed sample and validity contract

### Signal validity

Every detailed leaf is an immutable primitive value with a physical-unit name, a signed monotonic
`observedAtNs`, and exactly one `SignalValidity`:

| Validity            | Meaning                                                       | Payload rule                                                                                                        | Cache rule                                           |
|---------------------|---------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|------------------------------------------------------|
| `VALID`             | The named sensor produced a current semantically valid value. | Counter payload is in `[0, Long.MAX_VALUE]`; double payload is finite and satisfies the field's nonnegative domain. | Validate, copy, and refresh.                         |
| `TRANSIENT_FAILURE` | This attempt failed but the sensor remains supported.         | Payload is canonical zero and is never interpreted.                                                                 | Retain the last valid value only through its TTL.    |
| `UNSUPPORTED`       | The platform/profile cannot supply this signal honestly.      | Payload is canonical zero and is never interpreted.                                                                 | Clear the value, baseline, and smoother immediately. |

There is no `INVALID` state. A null sample, null leaf, malformed array, out-of-span CPU, negative
counter, non-finite gauge, future observation, or inconsistent pair is converted at the adapter or
engine boundary to `TRANSIENT_FAILURE`. `UNSUPPORTED` is a capability statement and must never be
inferred from one failed attempt.

The primitive records are exact: `CounterSignal` and `LongGaugeSignal` carry a `long` payload;
`DoubleGaugeSignal` carries a `double`; `BooleanSignal` carries a boolean; and `ThermalSignal`
carries `ThermalSeverity`. Each also carries `observedAtNs` and `SignalValidity`. A non-`VALID`
record must contain canonical payload `0`, `0.0`, `false`, or `NOMINAL` respectively. Providers and
adapters use static factories; compact constructors enforce the same invariants for every caller.
Group compact constructors additionally require every already-normalized ratio leaf in
`[0.0, 1.0]`; an out-of-range ratio is transient failure, not silently clamped at the SPI boundary.
Unnormalized capacity, runnable-work, and queue gauges remain finite/nonnegative and may exceed
one before their named formula.

For `VALID`, `observedAtNs` is the monotonic instant at which that value was completed. For
`TRANSIENT_FAILURE`, it is the failed attempt instant; for `UNSUPPORTED`, it is that sample's
requested instant. Neither nonvalid timestamp refreshes value age: all TTL calculation uses the
stored last-valid timestamp. Timestamp payloads may be any signed `System.nanoTime` value.

Signal records deep-copy any array or `BitSet` in their compact constructors. Their accessors
return copies or the P2 immutable wrappers. No record retains provider-owned mutable storage.

### Resolved interval boundary

P4-A resolves raw leaves into an immutable `IntervalHardwareSample` without applying P4-B's
normalization curves. `SignalResolution` is exactly `CURRENT`, `CACHED`, `BASELINE`, or
`UNAVAILABLE`:

- `CURRENT` is a valid current gauge or counter delta;
- `CACHED` reuses the last valid physical interval/gauge within the applicable TTL and retains its
  original observation timestamp;
- `BASELINE` is a first/reset/regressed counter with canonical zero contribution; and
- `UNAVAILABLE` covers unsupported, expired, invalid-without-cache, or inconsistent data.

`CounterDelta` carries nonnegative `delta`, strictly positive `elapsedNs` for `CURRENT`/`CACHED`,
the last valid observation timestamp, and resolution. `BASELINE`/`UNAVAILABLE` carry zero delta and
elapsed time. `ResolvedLong`/`ResolvedDouble` carry the physical gauge/telemetry value, observation
timestamp, and resolution. `LatencyInterval` carries paired nonnegative latency/operation deltas,
one shared positive elapsed time, timestamp, and resolution. These resolved records never encode a
normalized pressure except where the raw SPI field itself is explicitly a ratio.

`IntervalHardwareSample` preserves the same global/CPU/memory/I/O/slow grouping as the raw samples,
adds resolved productive CPU and I/O telemetry, effective limit/working-set bytes, and carries a
deep-copied logical span/membership. This is the complete P4-A -> P4-B boundary; P4-B does not read
raw provider records or recover state from public snapshots.

### Fast sample schema

`FastHardwareSample` has one outer `observedAtNs`, one fixed Euhedral logical CPU span, one copied
effective-CPU set, and these leaves:

- quota capacity in CPUs and quota accounting period in nanoseconds;
- cumulative productive CPU time in nanoseconds;
- cumulative scope quota-throttled time in nanoseconds;
- optional cumulative scope scheduler-wait and PSI/scheduler-stall time plus an optional
  already-normalized scope scheduler-stall ratio;
- one `CpuFastSignals` entry per logical CPU containing cumulative scheduler-wait time,
  cumulative PSI/scheduler-stall time, an optional already-normalized scheduler-stall ratio,
  cumulative per-CPU quota-throttle time, cumulative steal time, an external-contention ratio,
  and runnable work per unit of available CPU capacity;
- `MemoryFastSignals`: hard-limit bytes, high-limit bytes, usage bytes, inactive-file bytes,
  cumulative reclaim bytes, and cumulative memory-stall nanoseconds; and
- `IoFastSignals`: cumulative productive bytes, cumulative stall nanoseconds, cumulative operation
  latency nanoseconds, cumulative completed operations, and the current maximum queue depth among
  in-scope devices.

Counter fields are cumulative, not since-boot pressure and not interval deltas. An
already-normalized ratio is allowed only for a documented API that actually reports that interval
ratio; it never shares a field with a duration. Runnable work and queue depth are nonnegative
scalars and may exceed `1.0` before normalization. Queue depth is one `DoubleGaugeSignal`: a
provider with an enumerable in-scope device set takes the maximum nonnegative depth in stable
device-name order; an empty supported set reports valid zero, and an API without honest scope or
depth marks it unsupported. P4 stores no device identities or per-device recovery state.

Production captures the P2 index span from `SystemInfo.getCpuCount()` exactly once at monitor
construction and rejects a nonpositive result with `IllegalStateException`; it never derives span
from effective cardinality, `BitSet.length()`, or a sample. The package-private test constructor
accepts an explicit positive logical span and rejects `<= 0` with `IllegalArgumentException`. CPU
array position `i` is logical CPU `i`; signal records do not carry a second CPU identity. Every
fast/slow CPU array must have exactly that length and every effective bit must be below it. A span
mismatch,
null/short/long array, or out-of-span bit invalidates the outer sample for that attempt; it is not
truncated, padded, or remapped. An empty effective set remains a valid sample representing complete
capacity loss.

### Slow sample schema

`SlowHardwareSample` has an outer `observedAtNs` and contains one `CpuSlowSignals` per logical CPU
plus system-wide slow signals:

- available and nominal capacity units;
- constrained frequency ceiling and nominal frequency in hertz;
- thermal severity `NOMINAL`, `FAIR`, `SERIOUS`, `CRITICAL`, or `EMERGENCY`; and
- low-power-mode state.

A capacity unit is a nonnegative provider-native relative capacity scalar; available and nominal
must come from the same API/scale and are used only as their within-CPU ratio. Frequencies are
nonnegative hertz. Thermal and low-power fields are enums/booleans, not numeric platform ordinals.

A current opportunistic clock frequency is not a constrained ceiling. A provider that cannot
prove a capacity or frequency ceiling marks it unsupported, preventing idle frequency scaling
from becoming pressure. Per-CPU values override nothing: CPU and system thermal/low-power losses
are correlated with `max` as specified below.

### SPI call contract

`DetailedSystemSnapshotProvider.sampleFast(long requestedAtNs)` is attempted once per monitor poll.
`sampleSlow(long requestedAtNs)` is attempted on the independent slow grid. Implementations return
fresh immutable records, may throw any `Exception` or `LinkageError`, and must not return null.
The monitor catches those failures at the provider boundary and supplies transient-failure leaves;
fatal VM conditions are not deliberately swallowed there. Platform implementations must keep
sensor calls bounded and responsive to interruption where their API permits; P5-P7 own those
collection details.

Evaluation call order is fixed: capture `pollStartNs`; if the slow boundary is due, attempt
`sampleSlow(pollStartNs)`; then attempt `sampleFast(pollStartNs)` regardless of slow success; then
capture `evaluationNs` once. Fast-last keeps the high-rate observation closest to publication. A
not-due slow sensor is not called, and neither failure suppresses normalization of the other
sample/cache. This sensor order precedes the logical-CPU/signal evaluation order fixed below.

The requested timestamp is an observation aid, not publication authority. Each signal reports its
own observation timestamp. Outer `observedAtNs` is captured after that sample's leaf reads; every
valid leaf must not be after its outer time, and the outer time must not be after `evaluationNs`
under signed elapsed ordering. Violations follow the transient/outer-failure rules. Only the
monitor clock sets public publication time.

## Canonical public units and field mapping

The existing public records retain their exact component shapes. P4 documents and enforces these
units:

| Public component/accessor           | Canonical meaning                                                                                                                     |
|-------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `SystemSnapshot.timeNs`             | Monitor publication time from the injected monotonic clock, in nanoseconds.                                                           |
| `totalCpus`                         | Stable Euhedral logical CPU array span, not an effective count.                                                                       |
| `quotaCpus`                         | Finite effective CPU capacity count in `[0, effectiveCpus.cardinality()]`; unsupported quota falls back to the effective count.       |
| `period`                            | Quota accounting period in nanoseconds; `0` means unavailable/not applicable.                                                         |
| `cpuUsage`                          | Nonnegative cumulative productive CPU time in nanoseconds; telemetry only.                                                            |
| `cpuThrottle`                       | Nonnegative cumulative scope quota-throttled time in nanoseconds.                                                                     |
| `effectiveCpus`                     | Membership only; pressure cannot change it.                                                                                           |
| `pressurePerCpu[i]`                 | Current canonical scheduler/OS-stall interval ratio for logical CPU `i`; unsupported is `0.0`.                                        |
| `memoryLimit`                       | Effective hard/high byte limit; `Long.MAX_VALUE` is the public unbounded/unknown compatibility sentinel and `0` is a real zero limit. |
| `memoryUsage`, `inactiveFileMemory` | Nonnegative bytes. Working set is `max(usage - inactive, 0)`.                                                                         |
| `diskIOBytes`                       | Nonnegative cumulative productive bytes; telemetry only.                                                                              |
| `HardwareUtilization.timestampNs`   | Same publication timestamp as its `SystemSnapshot.timeNs`.                                                                            |
| `quotaCpuUsage`                     | Unsmoothed productive interval utilization ratio; never a pressure input.                                                             |
| `cpuThrottleRatio`                  | Smoothed scope throttle ratio.                                                                                                        |
| `perQuotaCpuThrottleRatio[i]`       | Smoothed throttle ratio applicable to logical CPU `i`.                                                                                |
| `perQuotaCpuPressure[i]`            | Final composite pressure for logical CPU `i`, composed from smoothed signals.                                                         |
| `globalMemoryPool`                  | Effective memory limit bytes or `Long.MAX_VALUE` sentinel.                                                                            |
| `perCpuMemoryPool`                  | `globalMemoryPool / effectiveCount`, integer floor; `0` when no CPU is effective.                                                     |
| `totalMemoryUtilization`            | Unsmoothed working-set occupancy ratio. Unknown/unbounded is `0.0`; a valid zero limit is `1.0`.                                      |
| `memPerCpuUsageBytes`               | `workingSetBytes / effectiveCount`, integer floor; `0` when no CPU is effective.                                                      |
| `diskIOBytesPerSecond`              | Unsmoothed productive byte rate, finite and nonnegative; telemetry only.                                                              |
| `diskIOPressure`                    | Smoothed I/O contention domain, never throughput divided by a peak.                                                                   |
| `CpuSnapshot.stallRatio`            | The matching `SystemSnapshot.pressurePerCpu[cpuId]`.                                                                                  |
| `CpuSnapshot.throttleRatio`         | The matching `perQuotaCpuThrottleRatio[cpuId]`.                                                                                       |
| `CpuSnapshot.pressure`              | The matching `perQuotaCpuPressure[cpuId]`; no recomposition in the accessor.                                                          |
| `HardwareUtilization.pressure()`    | Maximum final composite over effective CPUs; exactly `1.0` for a valid publication with no effective CPU.                             |

`SocketSnapshot.memoryUtilization`, `CoreSnapshot.memoryUtilization`, and
`CpuSnapshot.memoryUtilization` all carry the same finite scope working-set occupancy computed at
publication, not a second division of rounded per-CPU byte values. `globalBytesUsed` is the exact
saturated working set. Scoped byte fields use nonnegative saturating multiplication where needed.

Every populated CPU and socket snapshot derived from one utilization has
`lastUsageNs == HardwareUtilization.timestampNs()`. No method substitutes call time. All ratio
accessors listed in the plan are finite in `[0.0, 1.0]`, including objects constructed through
public record constructors. Compact constructors canonicalize `-0.0` to `+0.0`, map malformed
ratio inputs to `0.0`, and clamp finite out-of-range values. They reject null owned objects and
active CPU spans not covered by required arrays. `HardwareUtilization` also rejects a nested
`SystemSnapshot.timeNs` that differs from its `timestampNs`, a membership set that differs from
`snapshot.effectiveCpus`, or quota/period values that differ from the canonical nested snapshot.
`SystemSnapshot` rejects a nonpositive span, a pressure array whose length is not exactly that
span, or an effective bit outside it.

Public compact-constructor sanitation for non-ratio telemetry is also exact: negative cumulative
counters, usage/inactive/I/O/pool/scoped bytes, and period become zero; a negative
`SystemSnapshot.memoryLimit` becomes the `Long.MAX_VALUE` unknown sentinel; quota is non-finite or
negative -> zero and otherwise clamps to effective cardinality; `diskIOBytesPerSecond` uses
`nonnegativeTelemetry`. These corrections happen before the nested Hardware/System equality checks
above.

## Compatibility adapter

`SystemSnapshotCompatibilityAdapter` selects one immutable profile at construction; selection is
not keyed by a sample identity or timestamp:

| Profile            | Mapping before its platform phase                                                                                                                                                                      |
|--------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CANONICAL_PUBLIC` | Treat documented public counters/bytes as canonical, validate/copy them, and accept `pressurePerCpu` only as finite interval ratios. Used by injected and third-party providers after the P4 contract. |
| `LINUX_V2_LEGACY`  | Accept currently cumulative CPU/throttle/I/O and byte gauges with checked microsecond-to-nanosecond period conversion. Mark current host-apportioned PSI/scheduler pressure unsupported.               |
| `WINDOWS_LEGACY`   | Accept safely dimensional byte gauges/counters only. Mark current cycle-derived CPU/throttle and busy-time pressure unsupported.                                                                       |
| `MACOS_LEGACY`     | Accept safely dimensional byte gauges/I/O counters only. Mark current delta CPU time and load-derived pressure unsupported.                                                                            |

The adapter is an honest bridge, not the P5-P7 collection fix. It never guesses a missing unit,
keys mutable recovery state by class/thread, inspects provider identity changes, or reinterprets
productive busy time as contention. A built-in class maps to its named fixed profile once. Null
providers fail construction as specified under lifecycle; a provider returning null is a transient
sample failure.

Selection order is exact and occurs once in the monitor constructor: a provider implementing
`DetailedSystemSnapshotProvider` is used directly; otherwise `CgroupV2Resources`,
`WindowsResources`, and `OSXResources` select their named legacy profile by `instanceof`; every
other nonnull `SystemSnapshotProvider` selects `CANONICAL_PUBLIC`. The resulting detailed provider
is stored directly. No sample-time class check or profile change is permitted.

For every compatibility profile, adapter `sampleFast` invokes wrapped `getSnapshot()` exactly once
and maps that one deep-copied result. The copied `SystemSnapshot.timeNs` is the fast outer and every
snapshot-derived valid-leaf observation time; normal future/regression validation still applies.
Adapter `sampleSlow` performs no wrapped provider call and returns an all-`UNSUPPORTED` slow sample
timestamped at `requestedAtNs`. The slow grid therefore cannot double-sample a legacy public
provider.

Checked legacy period conversion uses `Math.multiplyExact(periodMicros, 1_000L)` after rejecting a
negative input. Overflow is transient failure and publishes period `0`; it cannot wrap. The
adapter immediately deep-copies the effective set and pressure array. Unsupported rich values are
explicit validity, not public sentinel inference. At the public compatibility boundary only,
`memoryLimit == Long.MAX_VALUE` is the documented unknown/unbounded sentinel and maps to an
unsupported internal limit; zero is a valid zero limit and any other nonnegative value is a valid
byte limit. Internal detailed providers never encode validity with that sentinel.

## Delta, timestamp, reset, and age rules

### Counter state

`SampleStateEngine` is monitor-instance-owned. It stores a fixed field per global counter and
fixed arrays indexed by stable Euhedral logical CPU ID. It uses no `Map`, static mutable state,
`ThreadLocal`, timestamp-keyed sidecar, identity-keyed sidecar, or global registry.

For a valid cumulative counter pair `(p, tp)` and `(c, tc)`:

```text
dt = tc - tp

no prior baseline       -> store (c, tc), contribution unavailable/zero
dt <= 0                 -> replace baseline with (c, tc), contribution unavailable/zero
c < p                    -> reset or wrap: replace baseline, contribution unavailable/zero
otherwise                -> delta = c - p; replace baseline; evaluate delta over dt
```

All canonical counters are signed nonnegative `long` values. P4 never assumes an unknown native
counter width, so `c < p` is reset/wrap and never modular subtraction. A first value, reset,
wrap, or per-signal timestamp regression establishes a baseline and cannot emit a since-boot
usage, throttle, reclaim, latency, I/O rate, or stall spike. It also refreshes that affected
signal's normalized input to valid zero so an old nonzero sample is not mistaken for the reset
interval. Other counter baselines remain intact.

`c - p` cannot overflow after the nonnegative and `c >= p` checks. Nanosecond differences use Java
signed subtraction, which remains valid across `System.nanoTime()` wrap for elapsed intervals less
than `2^63` nanoseconds. A negative signed difference is regression and rebaseline, never a large
positive duration.

Paired latency is valid only when both latency and operation counters produce deltas over the same
observation interval. A missing/reset member makes latency unavailable for that interval and
rebases the affected members.

Noncumulative gauge/ratio/boolean/thermal state refreshes only from a strictly newer valid leaf
timestamp. A duplicate or regressing leaf is a transient attempt: it does not replace the stored
value or its observation time and resolves from that prior value only while still within TTL. This
rule also covers a duplicate timestamp carrying a different payload; payload equality does not
turn a duplicate into a refresh. A newly observed valid leaf already older than its TTL may be
stored for ordering/baseline purposes but resolves `UNAVAILABLE` immediately.

Effective membership owns one fixed `lastMembershipObservedAtNs`, not a timestamp-keyed sidecar.
The first valid outer fast sample establishes it. Thereafter only a strictly newer outer fast
timestamp may replace membership; a duplicate/regressing outer timestamp retains the previous
copied set while its cumulative leaves independently resolve or rebaseline under the counter
rules above. Thus stale provider output cannot remap topology.

### Monitor time and publication

The poll-start clock, signal timestamps, evaluation time, completion time, and public timestamp
are monotonic nanoseconds. Immediately after all due sensor reads, the monitor reads its injected
clock once as `evaluationNs`. That value drives ages, smoothing elapsed time, and every timestamp in
the candidate public snapshot. After topology update, atomic publication, and nonblocking listener
offer, it reads the clock again as `completionNs` for scheduling.

Every monotonic comparison uses signed elapsed subtraction, not numeric timestamp ordering:
`after(a, b)` is exactly `(a - b) > 0`, valid for separations below `2^63` nanoseconds. The same
helper governs publication acceptance, cache age, deadline checks, and listener timestamp order,
so a normal `System.nanoTime()` signed wrap does not look like regression.

`evaluationNs` must be strictly later than the prior publication timestamp. A duplicate or
regressing monitor time produces no publication, rebases all currently valid cumulative inputs,
clears freshness/smoothing epochs, and leaves the previous immutable publication unchanged. The
scheduler reanchors after an observed clock regression. A signal timestamp later than
`evaluationNs` is transient failure; a regressing individual signal timestamp follows its own
counter or cache rule without poisoning other signals.

### Staleness

The configured fast period is `P`. Exact cache constants are:

```text
FAST_TTL_NS = min(30 seconds, max(1 second, saturatingMultiply(5, P)))
SLOW_PERIOD_NS = 5 seconds
SLOW_TTL_NS = 15 seconds
```

Age is `evaluationNs - lastValidObservedAtNs`. Age in `[0, TTL]` is fresh, including the boundary;
age greater than TTL is expired. A negative age is invalid. On transient failure, the last valid
value is reused only while fresh. On expiry it becomes unavailable and its pressure smoother is
cleared to zero immediately. A valid zero refreshes and replaces a stale nonzero. `UNSUPPORTED`
also clears immediately. Thus unsupported and expired signals are validity-neutral rather than a
slowly decaying hidden input.

TTL is enforced at evaluation boundaries; no auxiliary expiry timer publishes by itself. The
first evaluation after `age > TTL` clears the value, so observable retention is bounded above by
`TTL + P` (plus one in-flight evaluation) and is about TTL at the default 200 ms cadence. A caller
choosing a very long valid public period intentionally accepts that coarser expiry observation.

Logical span is construction-fixed. Effective membership is structural outer-sample state, not a
pressure signal: after the first valid outer sample, a complete fast failure retains the last
copied set until a successful sample (including a valid empty set) replaces it or close clears it.
This prevents sensor failure from fabricating a topology remap. Every pressure-bearing value and
current gauge/rate still expires by its own bounded TTL, so continued failure produces timestamped
neutral-pressure/current-rate-zero publications rather than retaining stale pressure. The last
nonnegative cumulative counter value remains a monotonic public telemetry/baseline value but cannot
produce an interval contribution after TTL. Before any valid membership exists, a complete failure
cannot publish.
Membership is never inferred from per-CPU signal availability.

`SlowSampleCache` has its own anchored 5-second attempt grid and per-field age. A slow failure does
not trigger an immediate retry and does not change the fast 200 ms grid. An overrun skips slow
boundaries by the same first-future rule used for fast polling. A slow sample can be reused by many
fast evaluations but is retained for at most 15 seconds from its observation. Provider-specific
collection parallelism remains P5-P7; a due slow read may lengthen that poll and the fast scheduler
then skips, never catches up.

The first evaluation attempts both fast and slow sampling and anchors the slow grid at that poll
start. A stopped pre-start evaluation therefore owns the first slow attempt; a subsequent fresh
`start` reuse does not immediately repeat it. Stop retains fresh slow state for restart, while
`close` clears it.

Dynamic effective-set removal immediately clears that CPU's baselines, caches, and smoothers.
Reactivation starts from empty state. Productive CPU utilization is evaluated only when the prior
and current quota are both available, the effective sets are equal, and the sanitized quota values
compare exactly with `Double.compare`; otherwise its cumulative counter rebases and utilization is
zero for that interval. The current quota/membership still publishes. No state from an old span,
monitor, test, or provider survives close.

## Numeric and precision contract

### Primitive sanitation

The following helper semantics are fixed:

```text
unit(x):
    non-finite -> unavailable before this function is called
    x <= 0     -> +0.0
    x >= 1     -> 1.0
    otherwise  -> x

nonnegativeTelemetry(x):
    NaN or x < 0      -> 0.0
    positive infinity -> Double.MAX_VALUE
    otherwise         -> x with -0.0 canonicalized
```

Do not use `Math.min`/`Math.max` as a NaN sanitizer. Validate finiteness first. Division validates
its denominator before division, performs the division in `double`, then checks finiteness, then
clamps. Intermediate invalidity makes that signal transient failure; it is not silently pressure
zero while a fresh cached value exists.

Every scaled long calculation casts the nonnegative numerator to `double` before multiplication
(for example `((double) deltaBytes * 1_000_000_000.0) / elapsedNs`); no long multiplication is
allowed to wrap before conversion.

All floating-point expressions use Java 17 strict evaluation in the written order. Do not use
`float`, decimal formatting, quantization, `BigDecimal`, or platform-dependent fused operations.
No normalized result is rounded for publication. Tests use exact boundaries or at most eight ULPs
for `StrictMath.expm1` results. Integer byte allocation uses floor division; nonnegative scoped
products saturate at `Long.MAX_VALUE`. Subtraction clamps inactive file bytes before conversion:
`working = usage >= inactive ? usage - inactive : 0`.

### Actual-time asymmetric smoothing

Only pressure-bearing signals are smoothed. Productive CPU utilization, memory occupancy,
scheduler interval telemetry, and byte rate are not. Each supported signal owns one smoother; a
domain/composite is the deterministic `max` of those smoothed signals, so correlated signals are
not counted twice.

Each fixed `PressureState` smoother cell is exactly `(initialized, previous, lastEvaluationNs)`.
For a `CURRENT`, `CACHED`, or `BASELINE` input at a valid publication evaluation,
`dtNs = evaluationNs - lastEvaluationNs`; the first cell value is installed directly. A cached raw
input is deliberately re-applied at each publication while it remains within TTL, using actual
evaluation elapsed time, then clears immediately on expiry. A `BASELINE` counter supplies valid
zero; a reset therefore releases from any prior value without fabricating a delta. Prospective
cells and their timestamps commit only in the one-publication transaction below.

The constants intentionally produce attack alpha `0.20` and release alpha `0.05` at 200 ms:

```text
ATTACK_TAU_SECONDS  = 0.8962840235449102
RELEASE_TAU_SECONDS = 3.8991451492447347

tau = input >= previous ? ATTACK_TAU_SECONDS : RELEASE_TAU_SECONDS
alpha = unit(-StrictMath.expm1(-(dtNs / 1_000_000_000.0) / tau))
next = unit(previous + (input - previous) * alpha)
```

The two tau values are source `double` literals, not recomputed from rounded alpha constants at
runtime. At 200 ms they yield `0.19999999999999996` and `0.050000000000000044` under the specified
evaluation; tests compare the conceptual `0.20`/`0.05` values within eight ULPs.

The first supported value initializes its smoother directly to the already-clamped input. A
cumulative counter's first normalized input is zero, so since-boot state still cannot spike.
`dtNs <= 0` does not evaluate. Exponential underflow naturally gives alpha `1.0`; overflow cannot
occur after positive finite time/tau validation. An unsupported/expired input clears its own
smoother immediately. A valid decrease, including valid zero, uses release smoothing.

The evaluation order is stable logical CPU ID ascending, then signal order as listed below, then
CPU, memory, I/O domains, then public projection. `max` starts from `+0.0`. These rules make the
result monotonic in every supported pressure input and reproducible independent of hash or listener
order.

## Exact pressure mathematics

All symbols below are valid normalized values after delta, age, sanitation, and per-signal
smoothing unless marked `raw`. Missing/unsupported values are omitted from `max`; an empty `max`
is `0.0`.

### CPU scheduler, throttle, external contention, and capacity

Productive utilization is published but never enters pressure. After the compatible-quota rule:

```text
denominator = elapsedNs * quotaCpus              computed in double, in this order
quotaCpuUsage = unit(deltaProductiveCpuNs / denominator)
```

If `quotaCpus <= 0`, elapsed time is invalid, the double denominator is non-finite, or the
productive counter is baseline/unavailable, utilization is `0.0`. A valid aggregate productive
delta may exceed the nominal capacity transiently but the public ratio clamps only after division.

For logical CPU `i`:

```text
scopeWaitRaw       = unit(deltaScopeSchedulerWaitNs / deltaScopeTimeNs)
scopePsiRaw        = unit(deltaScopePsiStallNs / deltaScopeTimeNs)
scopeReportedRaw   = unit(scopeReportedSchedulerStallRatio)
wait_i_raw       = unit(deltaSchedulerWaitNs_i / deltaTimeNs_i)
psi_i_raw        = unit(deltaPsiStallNs_i / deltaTimeNs_i)
reported_i_raw   = unit(reportedSchedulerStallRatio_i)
runQueue_i_raw   = unit((runnablePerCapacity_i - 1.0) / 3.0)

scheduler_i_raw  = max(scopeWaitRaw, scopePsiRaw, scopeReportedRaw,
                       wait_i_raw, psi_i_raw, reported_i_raw, runQueue_i_raw)
scheduler_i      = max(smooth(scopeWaitRaw), smooth(scopePsiRaw),
                       smooth(scopeReportedRaw), smooth(wait_i_raw), smooth(psi_i_raw),
                       smooth(reported_i_raw), smooth(runQueue_i_raw))
```

Runnable work at or below one per available CPU adds no pressure; four or more is full scheduler
pressure. `SystemSnapshot.pressurePerCpu[i]` and `CpuSnapshot.stallRatio` publish
`scheduler_i_raw`; the composite uses `scheduler_i`. Each scope smoother is stored once and its
same value applies to every effective CPU. Scope scheduler/PSI evidence is never apportioned by
productive activity; if honest scope alignment is unavailable, it is unsupported rather than
attributed.

```text
globalThrottleRaw = unit(deltaGlobalThrottleNs / deltaTimeNs)
cpuThrottleRaw_i  = unit(deltaCpuThrottleNs_i / deltaTimeNs_i)
throttle_i        = max(smooth(globalThrottleRaw), smooth(cpuThrottleRaw_i))
```

When only global quota evidence exists, the same honest global value applies to every effective
CPU. P4 never apportions it by host activity. `cpuThrottleRatio` is the smoothed global value when
available, otherwise the maximum supported per-CPU throttle. Each
`perQuotaCpuThrottleRatio[i]` is `throttle_i`.

```text
steal_i           = smooth(unit(deltaStealNs_i / deltaTimeNs_i))
external_i        = smooth(unit(externalContentionRatio_i))
externalDomain_i  = max(steal_i, external_i)

capacityLoss_i    = smooth(unit(1.0 - availableCapacity_i / nominalCapacity_i))
frequencyLoss_i   = smooth(unit(1.0 - frequencyCeilingHz_i / nominalFrequencyHz_i))
thermalLoss       = NOMINAL:0.00, FAIR:0.15, SERIOUS:0.35,
                    CRITICAL:0.65, EMERGENCY:1.00
lowPowerLoss      = lowPowerMode ? 0.15 : 0.00
capacityDomain_i  = max(capacityLoss_i, frequencyLoss_i,
                        smooth(perCpuThermalLoss_i), smooth(systemThermalLoss),
                        smooth(perCpuLowPowerLoss_i), smooth(systemLowPowerLoss))

cpuDomain_i       = max(scheduler_i, throttle_i, externalDomain_i, capacityDomain_i)
```

Nominal denominators must be finite and strictly positive. A zero/negative/missing denominator is
unavailable, not full loss. A valid available value at or above nominal is zero loss. Productive
CPU usage is absent from every pressure expression.

### Memory

The effective memory limit `L` is the minimum valid positive hard/high limit. A valid zero hard or
high limit makes `L = 0`. Unsupported/unbounded limits are omitted; no remaining limit means
unbounded/unknown.

A current/cached nonnegative usage value is required. A current/cached inactive-file value is
subtracted; unsupported/expired inactive-file data uses zero, conservatively treating all usage as
working set. A transient inactive-file failure may use its fresh cached value. Missing usage makes
occupancy/headroom unavailable rather than zero pressure; the public working-set telemetry retains
only a fresh resolved value.

```text
W = max(memoryUsageBytes - inactiveFileBytes, 0)

occupancy U =
    1.0                         if L == 0
    unit(W / L)                 if L > 0
    0.0                         if L is unknown/unbounded

headroomRaw = unit((U - 0.80) / 0.20)
reclaimFractionPerSecond = (deltaReclaimBytes / L) * (1 second / deltaTime)
reclaimRaw = unit(reclaimFractionPerSecond / 0.02)
memoryStallRaw = unit(deltaMemoryStallNs / deltaTimeNs)

memoryDomain = max(smooth(headroomRaw), smooth(reclaimRaw), smooth(memoryStallRaw))
```

Reclaim is unavailable when `L <= 0`. Occupancy up to 80 percent has zero headroom pressure and
100 percent is full. Reclaiming two percent of the effective limit per second is full reclaim
pressure. A valid zero limit is complete headroom pressure. `totalMemoryUtilization` publishes `U`
but is not itself substituted for `memoryDomain`.

### I/O

```text
ioStallRaw = unit(deltaIoStallNs / deltaTimeNs)
averageLatencyNs = deltaTotalLatencyNs / deltaCompletedOperations
latencyRaw = unit((averageLatencyNs - 1_000_000.0) / 49_000_000.0)
queueRaw = unit((maxQueueDepthInScope - 1.0) / 7.0)

ioDomain = max(smooth(ioStallRaw), smooth(latencyRaw), smooth(queueRaw))
diskIOBytesPerSecond = deltaIoBytes * 1_000_000_000.0 / deltaTimeNs
```

No completed operation makes latency unavailable for that interval. Latency at or below 1 ms is
zero and 50 ms or above is full. The maximum in-scope queue depth at or below one is zero and eight
or above is full. Bytes per second is sanitized telemetry and never enters `ioDomain`.

### Composite and public values

```text
perQuotaCpuPressure[i] = max(cpuDomain_i, memoryDomain, ioDomain)
diskIOPressure         = ioDomain
pressure()             = max(perQuotaCpuPressure[i] for effective i)
```

Memory and I/O are independent bottlenecks propagated honestly to every effective CPU. Within each
domain, `max` handles correlated observations without noisy-or amplification. Across domains,
`max` means one saturated bottleneck is sufficient and prevents double-counting related lost
capacity. A valid publication with no effective CPU returns `1.0`; before the first publication
`ResourceMonitor` has no synthetic public utilization.

For that valid empty set, public quota capacity/usage, scope/per-CPU throttle, scheduler arrays, and
per-CPU composite arrays are canonical zero; `pressure()` alone reports the complete capacity loss
as `1.0`.

Golden correlated case: scheduler signals `0.25`, `0.40`, and runnable-per-capacity `2.5` produce
`scheduler = 0.50`, not `0.80`; throttle `0.30`, external `0.20`, and capacity `0.10` leave the CPU
domain `0.50`. Occupancy `0.90`, reclaim `0.01` limit/second, and stall `0.20` produce memory
`0.50`. Average latency `25.5 ms` and I/O stall `0.40` produce I/O `0.50`. The composite is `0.50`,
not a sum or noisy-or. A first smoother value is used in this golden; later values follow the
actual-time formula.

## Public projection, copying, and ownership

`PressureProjection` receives one immutable interval sample, normalized pressure result, and
`evaluationNs`, then constructs the complete public object graph locally. It does not mutate the
last publication. Construction order is:

1. sanitize and copy canonical telemetry and membership;
2. allocate exact logical-span scheduler, throttle, and composite arrays;
3. fill effective CPU entries in ascending ID and leave inactive entries canonical zero;
4. construct a canonical deep-copied `SystemSnapshot` with `timeNs = evaluationNs`;
5. construct `HardwareUtilization` with `timestampNs = evaluationNs` and that snapshot; and
6. validate finite ratios, span coverage, and timestamp equality before returning the candidate.

Unavailable public gauges/rates project as canonical zero, except unsupported/unbounded memory
limit projects as `Long.MAX_VALUE` and unsupported quota capacity falls back to current effective
cardinality with period zero. Baseline counters publish their current cumulative value while their
interval contribution remains zero. After counter TTL, the last nonnegative cumulative value may
remain public to avoid a fabricated regression, but its delta/rate/pressure resolution is
`UNAVAILABLE` until a valid new observation. These compatibility values never reconstruct internal
validity.

Provider arrays, engine scratch arrays, and compatibility arrays may be reused only before step 4.
P2 public constructors copy again, forming the publication ownership boundary. Tests mutate every
provider input after projection and require all prior public objects, nested arrays, and bitsets to
remain unchanged.

One evaluation may allocate one bounded graph proportional to the stable logical CPU span.
Long-lived state is limited to one previous publication, fixed counter/cache/smoother arrays, one
poll thread while running, one listener thread while needed, one listener registry array, and one
pending listener value. Close retains only the last immutable public utilization so a closed read
can return it, and clears provider, scratch, listener, pending, and thread references where API
ownership permits. No per-publication history, future chain, common-pool task, map keyed by
timestamps/objects/threads, static mutable registry, or `ThreadLocal` is allowed.

`TopologyMapper` and the detailed/public provider are borrowed dependencies: the monitor never
closes them or mutates provider-owned storage. It may clear only its references at close. The
monitor owns its sampling/pressure state, threads, dispatcher, listener registrations, and copied
publications. Listener objects are borrowed and are never invoked after the close barrier.

## Listener registry and dispatcher

`LatestValueDispatcher` is monitor-owned and has exactly three storage bounds:

- at most one callback is active globally;
- at most one pending `HardwareUtilization` reference is retained; and
- the listener registry contains at most one entry per listener object identity, in insertion
  order, until monitor close.

That identity registry is solely the public listener registration set. It never stores or recovers
sensor, pressure, timestamp, or snapshot state and therefore is not a prohibited measurement
sidecar.

One dispatcher-state monitor guards exactly `OPEN`, `CLOSING`, or `CLOSED`, the ordinary one-slot
`pending` reference, `lastAcceptedTimestampNs` plus its initialized bit, worker identity,
`callbackInProgress`, listener count, and the close hook. A separate registry monitor guards one
copy-on-write `MonitorListener[]`. The only nested dispatcher order is state -> registry for
addition/close cleanup; the worker snapshots the registry and checks state in separate critical
sections. No callback, provider, topology call, wait, join, logging call, or thread parking occurs
under either monitor.

`offer(value)` enters only the dispatcher-state monitor, validates `OPEN` and strictly-newer signed
timestamp order, replaces `pending`, starts/wakes the sole worker if listeners exist, and returns.
That critical section has no loop except a bounded timestamp comparison and performs no registry
scan; "nonblocking" means it never waits for callback progress or queue space. With zero listeners,
it drops the value without starting a thread or creating replay state. If worker creation/start
fails, it restores the prior accepted timestamp, clears `pending`/worker identity, returns `false`,
and leaves `OPEN` for an identical or newer offer to retry.

The worker waits on the dispatcher-state condition with the monitor released. It atomically takes
and clears `pending`, snapshots the registry under only the registry monitor, and, before each
listener invocation, briefly enters the state monitor to require `OPEN` and mark
`callbackInProgress`. Its `finally` clears that flag and signals waiters before considering the
next listener or pending value. Consequently an accepted value exists only as the current worker
local or the one replaceable pending reference, and `beginClose` can stop a frozen batch before
any not-yet-started callback.

`offer(value)` is nonblocking. While a callback is active, a newer offer atomically replaces the
pending value. Intermediate values are intentionally coalesced. Older/equal timestamps are
discarded. The dispatcher takes the pending value, snapshots the current registry under its
registry monitor, releases that monitor, then invokes the snapshot sequentially in insertion
order. Delivery timestamps for each listener are strictly increasing, but best effort does not
promise every publication or an initial replay.

`addListener` rejects null with `NullPointerException("listener")`. Adding the identical object is
an idempotent no-op. It never invokes user `equals`/`hashCode`. Most importantly, no callback runs
under the registry monitor. A callback that calls `addListener` completes registration safely;
the new listener is not part of the current frozen batch and becomes eligible for the next
retained publication.

Each callback is an isolation boundary that catches `Throwable`, records it through parameterized
logging, restores dispatcher invariants in `finally`, and proceeds to the next listener unless
close has linearized. Callback-set interrupt status is cleared before the next callback; dispatcher
shutdown is controlled by its state predicate, not an untrusted callback's interrupt bit.

The dispatcher close protocol has two package-private phases. `beginClose(Runnable
terminationHook)` is nonblocking and linearizes by rejecting offers/additions, clearing pending
work, and preventing any new callback from beginning. The non-null monitor-owned hook is accepted
only on the first transition, retained at most until termination, and invoked exactly once after
dispatcher state/references are cleared and outside every dispatcher/registry lock. When no
dispatcher thread or callback exists it may run synchronously before `beginClose` returns.
`awaitClosed()` waits on a condition, without spinning, until an active callback and dispatcher
thread finish; if interrupted, it completes the barrier and restores the caller's interrupt
status. A close called reentrantly by the dispatcher callback performs `beginClose`, clears
remaining callbacks, and returns without self-join; the dispatch loop invokes the hook after that
callback returns. Thus no callback begins after `beginClose` and reentrant close cannot deadlock or
strand monitor teardown. `stop()` does not destroy the dispatcher because the monitor may restart;
only `close()` does.

The dispatcher uses one dedicated daemon thread named `euhedral-resource-monitor-listener`.
Thread-start failure leaves sampling/publication intact, makes that `offer` return `false`, clears
its pending value and thread reference, and permits a later offer to retry. Listener callbacks never
execute on the polling thread or the common pool. A package-private constructor accepts a JDK
`ThreadFactory` for deterministic start-failure and identity tests; production uses its fixed
daemon factory.

## ResourceMonitor lifecycle

### Construction and duration validation

The default public constructor uses exactly `Duration.ofMillis(200)`. Public constructors perform
no sensor read and no topology update. Validation order and exceptions are fixed:

1. null `TopologyMapper` -> `NullPointerException("mapper")`;
2. null `Duration` -> `NullPointerException("sampleRate")`;
3. `Duration.toNanos()` overflow -> `IllegalArgumentException` with the arithmetic exception as
   cause and message naming non-representable nanoseconds;
4. converted value `<= 0` -> `IllegalArgumentException`;
5. value below `10_000_000 ns` -> `IllegalArgumentException` naming the 10 ms minimum; and
6. value above `86_400_000_000_000 ns` (24 hours) -> `IllegalArgumentException` naming the maximum.

No duration is rounded; a converted zero follows the nonpositive rule. The bounded range makes TTL
and deadline calculations representable; multiplication still uses saturating helpers. A null
injected provider is `NullPointerException("snapshotProvider")`.
If `SystemInfo.SNAPSHOTTER` is unavailable, public construction throws
`IllegalStateException("Resource monitoring is unavailable on this platform")`; a null provider
cannot survive to race with `start` or `getUtilization`.

P4 adds an additive `public void stop()` method; existing signatures remain unchanged. P0's API
gate must classify it as additive and record duration/lifecycle behavior corrections exactly in
the defect ledger.

### State machine

One lifecycle monitor owns `STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, `CLOSING`, and `CLOSED`,
the polling thread identity, start anchor, stop predicate, initial-evaluation generation,
`evaluationActive`, `evaluationOwner`, and `publicationClaimed`. `CLOSING` is the permanent close
request and barrier in progress; `CLOSED` means every externally waitable barrier completed. One
separate evaluation gate owns all sample/delta/smoother mutation.

An evaluator is claimed by setting `evaluationActive` under the lifecycle monitor, releases that
monitor, and only then acquires the evaluation gate. No lifecycle path acquires or waits for the
evaluation gate while holding the lifecycle monitor. While it holds the evaluation gate, the
evaluator may briefly acquire the lifecycle monitor to claim publication or finish; this is the
only nested order, evaluation -> lifecycle. Provider and topology calls occur under the evaluation
gate but under neither the lifecycle nor registry monitor. The only lifecycle -> dispatcher-state
nests are `addListener`, delivery `offer`, and `beginClose`; the dispatcher never enters the
lifecycle monitor while holding one of its monitors, callbacks run under none of them, and its
internal termination hook runs after all dispatcher locks are released.

| Operation/state                                          | Settled behavior                                                                                                                                                                                                |
|----------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| constructor                                              | Ends in `STOPPED` with no publication and no threads.                                                                                                                                                           |
| `getUtilization`, `STOPPED`, no publication              | Exactly one concurrent caller performs a synchronous evaluation; peers await that attempt and observe the same result/failure.                                                                                  |
| `getUtilization`, any state, publication exists          | Acquire-read and return the immutable cached reference; never poll.                                                                                                                                             |
| `getUtilization`, active/closing/closed, no publication  | Throw `IllegalStateException`; never synthesize zeros or poll concurrently.                                                                                                                                     |
| `start`, `STOPPED`                                       | Linearize through `STARTING`; establish the grid, ensure/reuse one initial publication as below, start exactly one polling thread, then enter `RUNNING`.                                                        |
| `start`, `STARTING`                                      | Wait for the transition and observe the initiating caller's success/failure.                                                                                                                                    |
| `start`, `RUNNING`                                       | Idempotent immediate no-op.                                                                                                                                                                                     |
| `start`, `STOPPING`                                      | Wait for stop, then perform the normal start transition.                                                                                                                                                        |
| `start`, `CLOSING` or `CLOSED`                           | Throw `IllegalStateException`; close is permanent.                                                                                                                                                              |
| `stop`, `STARTING`                                       | Mark the start cancelled, wait for its in-flight initial evaluation/thread-start boundary, then finish `STOPPED`; at most that already-started evaluation may publish.                                          |
| `stop`, `RUNNING`                                        | Enter `STOPPING`, signal/unpark/interrupt the poll waiter, allow at most the already-started evaluation to finish publication without listener delivery, and externally wait for poll exit; finish `STOPPED`.   |
| `stop`, `STOPPING`                                       | Idempotently wait for the same stop completion.                                                                                                                                                                 |
| `stop`, `STOPPED`                                        | Idempotent no-op when idle; if a synchronous stopped evaluation is active, wait for that generation to finish and still return in `STOPPED`.                                                                    |
| `stop`, `CLOSING`                                        | Idempotent no-op; the stronger permanent transition already owns teardown.                                                                                                                                      |
| `stop`, `CLOSED`                                         | Idempotent no-op.                                                                                                                                                                                               |
| `close`, `STOPPED`, `STARTING`, `RUNNING`, or `STOPPING` | Under the lifecycle monitor enter `CLOSING`, request poll cancellation, and call dispatcher `beginClose(this::onDispatcherTerminated)`; then await the barriers below, clear dynamic state, and enter `CLOSED`. |
| `close`, `CLOSING`                                       | An external caller waits for the same completion; a current poll/evaluation/dispatcher owner returns request-only rather than self-waiting.                                                                     |
| `close`, `CLOSED`                                        | Idempotent no-op.                                                                                                                                                                                               |

An external `stop`/`close` waits with condition rechecks, not a busy loop or arbitrary timeout. It
preserves interruption by completing the safety barrier and restoring the caller interrupt flag.
A call from the polling or current evaluation-owner thread requests the transition and returns
without self-wait; its `finally` publishes the terminal state. Reentrant listener close follows
the dispatcher rule.
Every join/`awaitClosed` uses a locally captured owner reference after releasing the lifecycle
monitor; a lifecycle-condition wait also releases that monitor. No barrier wait holds lifecycle,
evaluation, dispatcher, or registry ownership.

External close barriers use one order: stop/await the poll thread or synchronous evaluator, then
`awaitClosed()` on the dispatcher. The polling thread never waits for dispatcher callbacks during
ordinary delivery. A poll-thread close begins both stops and lets the owner `finally` paths finish
without self-waiting; a dispatcher-callback close may await poll termination and then uses the
dispatcher's reentrant no-self-wait rule. Poll/evaluation `finally` and the one-shot dispatcher
termination hook each signal the lifecycle condition and call the same idempotent
`tryFinalizeClose` under the lifecycle monitor. It changes `CLOSING -> CLOSED` only when no
evaluation/poll owner remains and dispatcher termination was observed. Every non-owner close
returns only after observing `CLOSED`. This order prevents a poll close and callback close from
waiting on each other. `CLOSED` retains only the last immutable publication; all other monitor/
dispatcher dynamic references are cleared.

The production poll thread is non-daemon, named `euhedral-resource-monitor`, and held by exactly
one state. Thread creation/start failure rolls `STARTING` back to `STOPPED`, clears identity, wakes
waiters, and throws. A complete fast-provider failure before membership has ever been established
during the required initial evaluation also rolls back and throws `IllegalStateException` with
cause. With prior membership, that failure follows normal bounded transient publication. A partial
sample with valid membership is a successful neutral or cached evaluation.

A periodic provider `Exception`/`LinkageError` becomes an all-fast-signals transient attempt. Once
membership has been established, it produces cached then neutral-pressure publications as leaves
reach their TTLs; before membership exists it produces none. A projection/topology exception
produces no publication or offer. Each failure is logged with the throwable, and scheduling selects
the next anchored future boundary; it does not recursively call `close` or terminate the loop. An
unexpected fatal error reaches run-loop `finally`, truthfully transitions out of `RUNNING`, and is
not converted to an application pressure value.

`addListener` performs its null check first. Under the lifecycle monitor it accepts additions only
in `STOPPED`, `STARTING`, `RUNNING`, or `STOPPING`, delegates to the dispatcher, and releases the
monitor; it throws `IllegalStateException` in `CLOSING` or `CLOSED`. Registration during stop
remains for a later restart. Because close calls dispatcher `beginClose` before releasing the
lifecycle monitor, callback-time registration and close have one unambiguous order.

After building a candidate, the evaluator acquires the lifecycle monitor while retaining the
evaluation gate. If state is `CLOSING` or `CLOSED`, it aborts before topology and release. Otherwise
it sets `publicationClaimed = true`; that claim orders this one overlapping evaluation before a
later close request. Close may then enter `CLOSING`, but it must wait for the claimed topology/
release attempt to finish, and no second evaluation can be claimed. Thus at most one already-
claimed publication may become visible after `CLOSING` begins, always before external close
returns. An unclaimed evaluation cannot update topology or publish, no listener offer occurs once
`CLOSING` begins, and any accepted sampling state is cleared by close.

### Initial sample and restart

`start` reads the clock at `t0`. If a successful stopped evaluation exists and its age is in
`[0, P)`, `start` reuses it and schedules the first poll at `t0 + P`; this prevents the production
`getUtilization(); start()` sequence from double-priming. Otherwise `start` performs one synchronous
poll whose start is `t0`, returns only after its publication, and schedules from the same anchor.
A restart uses the same freshness rule. Constructor sampling is prohibited.

Stopped `getUtilization` and `start` share one initial-evaluation generation. A caller arriving
while that generation is in flight waits on the lifecycle condition and observes the same
publication or exception; it never starts a second provider call. A failed generation is retained
only long enough for its current waiters to observe it. The next later explicit read/start may
retry. A concurrent `start` that observes the failed generation rolls back to `STOPPED` and throws;
it does not silently launch a retry thread.

The first cumulative detailed sample establishes baselines. It may legitimately publish current
gauge pressure such as zero memory headroom, but it publishes zero contributions for all
cumulative CPU, throttle, reclaim, latency, I/O-rate, and stall inputs. There is no public
pre-first-sample placeholder.

## Anchored polling recurrence

For period `P > 0` and anchor `t0`, mathematical poll-start boundaries are:

```text
D(k) = t0 + k * P, k = 0, 1, 2, ...
```

After an attempt completes at `c`, select the strictly first future boundary:

```text
kNext = floor((c - t0) / P) + 1
next = D(kNext)                         where next > c
```

The implementation avoids `k * P` overflow but remains equivalent: for nonnegative signed elapsed
`e = c - t0`, compute `delay = P - (e % P)` and wait until `c + delay`, comparing deadlines through
signed elapsed differences. If the monitor clock regresses, reanchor `t0 = c`, rebaseline temporal
state, and set `delay = P`. A stalled/equal fake clock blocks in `DeadlineWaiter`; it never spins.

Consequences are exact:

- cost below period: starts at `0, 200, 400, ... ms`;
- completion exactly on a boundary selects the following boundary, not an immediate attempt;
- start `0`, completion `450`, period `200` selects `600 ms`;
- no attempts occur at skipped `200`, `400`, or completion time `450`; and
- there is no catch-up burst and poll cost is subtracted exactly once.

`MonotonicClock` and `DeadlineWaiter` are package-private injectable seams. The production waiter
uses interruptible park/unpark with predicate rechecks. Tests use a manual clock/deadline waiter and
latches; they contain no wall-clock sleep. The package-private `ResourceMonitor` test constructor
also accepts the explicit logical span, a JDK `ThreadFactory` for the non-daemon polling thread,
and already-constructed A-C dependencies. Public constructors use only the P2 `SystemInfo` span
and fixed production factories.

`ResourceMonitor` does not call `ThreadTools.setTimerResolution`. A 200 ms grid does not justify a
process- or thread-level 1 ns timer/scheduler mutation, and the current macOS path conflicts with
the plan's realtime-policy prohibition. P4-D removes only that monitor call and records the exact
behavior correction; public timer APIs and their platform implementations remain unchanged for
P6/P7.

## One-publication transaction and memory semantics

An evaluation resolves and commits P4-A's sampling baselines when a semantically valid sample is
accepted, even if a later projection/topology call prevents public publication; this avoids
combining a later interval with an already consumed sample. P4-B separately builds immutable
`PressureEvaluation(newPressureState, candidate)`. Its prospective smoother state commits only after
the production `TopologyUpdater` (`TopologyMapper.update`) succeeds and immediately before the
release publication. A
failed candidate therefore consumes real counter observations but not an unpublished smoothing
transition. No partial public field is visible.

For every completed successful evaluation, ordering is:

```text
collect/copy -> resolve/commit sample state -> evaluate prospective pressure state
             -> build/validate candidate -> lifecycle publication claim
             -> TopologyUpdater.update(candidate)               [mapper::update in production]
             -> commit prospective pressure state
             -> PUBLISHED.setRelease(monitor, candidate)       [exactly once]
             -> dispatcher.offer(candidate) if deliveryEligible [nonblocking]
```

If no membership context has ever been established, projection fails, or topology update throws,
there is no release store and no listener offer; the previous publication remains. After
membership exists, per-signal failures or a complete transient fast attempt can still produce one
publication using retained membership plus bounded caches/neutral values. Logging is parameterized
and outside hot ownership locks.

In a `finally` path the evaluator reacquires the lifecycle monitor, clears
`publicationClaimed`/`evaluationActive`/`evaluationOwner`, records the generation result, and
signals all waiters. After a successful release and before clearing those fields, it may call
nonblocking
`dispatcher.offer(candidate)` while holding the lifecycle monitor only when this is a periodic
poll and state is still `RUNNING`. Close performs its state change and dispatcher `beginClose`
under that same monitor, so an offer is wholly ordered before close or is rejected; it cannot
straddle the close point.

Only that periodic `RUNNING` evaluation is `deliveryEligible`. A synchronous stopped read or
synchronous `STARTING` prime updates topology and publishes for acquire readers but does not notify
listeners. This preserves the existing lattice's explicit initial
`getUtilization()`/`update()` sequence and prevents a duplicate asynchronous initial rebalance.
Listener registration has no replay; the first eligible periodic publication is the first possible
callback.

`getUtilization` uses `PUBLISHED.getAcquire`. The release/acquire pair publishes the complete
deep-copied record graph and all prior topology-update effects to a reader that observes it.
Dispatcher-state monitor exit/entry publishes the same immutable candidate to the listener thread;
registry monitor exit/entry publishes copy-on-write listener arrays. Lifecycle monitor exit/entry
publishes state and thread identities; `Thread.start` and join/condition completion publish
run-loop setup/teardown. Dispatcher termination clears its owned references before invoking the
hook; the hook's lifecycle-monitor exit publishes that fact and the `CLOSED` transition to an
external close waiter entering the same monitor. No plain/opaque access substitutes for a required
edge; no volatile/atomic field duplicates an already monitor-guarded dispatcher field or the
authoritative utilization publication.

Topology mapping may have its own internal publication, but `ResourceMonitor` performs exactly one
atomic utilization publication per successful evaluation. Listener coalescing is deliberately not
a publication count.

## Child action items and context envelopes

### P4-A sample/validity contract

P4-A owns the unexported sampling package, canonical documentation on
`common/SystemSnapshotProvider.java`, compatibility adapters/profiles, fixed counter/cache state,
and focused sampling tests/resources. It reads current platform providers only to fixture the
profile table and does not edit them. It does not edit `ResourceMonitor`, `SystemUtilization`,
listener code, core, native code, or Maven/module declarations; training is prohibited and not
read.

Input is a provider or detailed sample plus evaluation time and logical span. Output is one deeply
immutable `IntervalHardwareSample` containing canonical telemetry, physical interval quantities,
validity, and fixed-field state for P4-B. Its completion must prove first/reset/wrap/regression,
future time, invalid value, fast TTL, independent slow grid/cache, dynamic mask cleanup, legacy
profile honesty, and mutation isolation.

### P4-B pressure mathematics and projection

P4-B owns the unexported pressure package, `common/SystemUtilization.java`, public ratio sanitation,
and math/projection tests. It consumes only P4-A's immutable interval contract. Sampling/provider,
monitor/lifecycle/listener, platform, core, native, and Maven/module code is read-only; training is
prohibited and not read.

Input is `IntervalHardwareSample` plus prior fixed smoother state and `evaluationNs`. Output is one
validated `HardwareUtilization` candidate plus new fixed smoother state. It must prove every
formula/constant, correlated max, validity neutrality, finite outputs, field mapping, first/reset
behavior, actual-time alpha, byte units/rounding, identical timestamps, deep copies, and direct
public-constructor sanitation.

### P4-C listener publication

P4-C owns `internal.monitor.LatestValueDispatcher` and its deterministic tests. It may refer to
`ResourceMonitor.MonitorListener` and `HardwareUtilization` but does not edit `ResourceMonitor` or
sampling/pressure/public records. Platform, core, native, and Maven/module files are read-only;
training is prohibited and not read.

Input is a strictly timestamped immutable utilization and identity-based listener additions.
Output is ordered best-effort delivery with one active/one latest pending, reentrant add/close,
`Throwable` isolation, nonblocking offer, and the two-phase `beginClose(terminationHook)`/
`awaitClosed()` barrier. Tests prove exactly-once unlocked termination notification, bounds, and
cleanup without sleeping or the common pool.

### P4-D monitor lifecycle and scheduler

P4-D owns `ResourceMonitor.java`, the small clock/waiter seams in `internal.monitor`, integration
tests, and exact P4 compatibility-ledger entries. It composes the reviewed and merged P4-A
provider/state, P4-B evaluator/projection, and P4-C dispatcher interfaces without changing their
contracts.
`TopologyMapper` is strictly read-only. Production adapts `mapper::update` to the package-private
`TopologyUpdater` functional seam; tests inject recording/failing updaters without changing the
mapper. All platform collection, core production, native, module exports, action-picker, and
training remain prohibited.

Input is a mapper, detailed/compatibility provider, validated period, clock, waiter, and listener
dispatcher. Output is the settled six-state lifecycle, anchored poll grid, one claimed release
publication, topology ordering, and cleanup. This child proves all duration cases,
constructor/no-prime, concurrent initial read, start/reuse/restart, stop/close/self-call, close
before/after publication claim, failures, exact poll starts, overrun, clock regression, atomic
publication count, listener independence, and minimal read-only core compatibility.

## Deterministic tests and fixtures

No property-test dependency is added. Property tests use fixed-seed `SplittableRandom`, explicit
boundary tables, and at least 20,000 generated cases per algebraic property. A failure reports seed,
iteration, and generated values.

Required test groups are:

1. `SampleStateEngineTest`: first values, normal deltas, missing intervals, duplicate/regressing
   times, reset/wrap, near-`Long.MAX_VALUE`, paired counters, partial failures, TTL boundaries,
   valid zero replacement, and fixed-index cleanup.
2. `SlowSampleCacheTest`: `0, 5, 10... s` attempts, overrun skip, 15-second inclusive freshness,
   expiry, independent fast polls, unsupported clear, and mutation isolation.
3. `SystemSnapshotCompatibilityAdapterTest` and the ledger-anchored `ProviderContractTest`: all
   four profiles, checked unit conversion, null/malformed values, deep copy, and explicit neutral
   legacy Linux/Windows/macOS pressure.
4. `PressureEvaluatorTest` and the ledger-anchored `PressureCompositionTest`: every threshold and
   thermal mapping, correlated golden case, healthy high-throughput I/O, low-throughput stall,
   productive CPU neutrality, no effective CPU, and unsupported/transient/reset behavior.
5. `PressurePropertiesTest`: bounds, finiteness, per-signal monotonicity, max idempotence,
   correlation, invalid doubles/divisors, counter extremes, irregular elapsed time, and smoothing
   attack faster than release.
6. `RatioAccessorContractTest`: a reflection-backed exhaustive manifest of every ratio-valued
   public accessor named in the parent plan. Reflection enumerates every public zero-argument
   `double` accessor and every `UnmodifiableDoubleArray` record component on the five record types;
   an exact manifest classifies each as normalized ratio, capacity, rate, or ratio array. Any
   unclassified addition fails. Only the normalized/array entries receive `[0,1]` assertions. The
   test also covers public direct constructors with NaN, infinities, negative zero, and out-of-range
   values.
7. Existing `SystemUtilizationTest`, `SnapshotOwnershipTest`, and `SnapshotIndexContractTest`:
   updated golden mapping, exact working-set bytes, repeated derivation, overlap rejection, sparse
   spans, and identical CPU/socket publication timestamps.
8. `LatestValueDispatcherTest`: sequential ordering, one pending replacement, non-overlap,
   nonblocking offer, identity dedupe, reentrant add, reentrant close, `beginClose` cutoff,
   exactly-once unlocked termination hook, external `awaitClosed()` barrier, callback
   `RuntimeException`/`Error`/interrupt, thread-start failure, and zero retained references.
9. Ledger-anchored `ResourceMonitorLifecycleTest`: all state-table transitions and concurrent
   operations with
   latches, including provider/topology/thread failures and self-stop/close. Its duration table
   includes null, zero, negative, 1 ns, 9,999,999 ns, 10 ms, 200 ms, exactly 24 hours, 24 hours plus
   1 ns, and `Duration.toNanos()` overflow.
10. Ledger-anchored `ResourceMonitorSchedulerTest`: fresh starts `0, 200, 400`, exact-boundary skip,
    the
    `0 -> 450 -> 600 ms` golden, multi-period overrun, stopped-read coalescing, restart freshness,
    and regressing/stalled clock without sleeps.
11. `ResourceMonitorPublicationTest`: topology-before-release, exactly one release per successful
    evaluation, none on failure, close before/after the publication claim, no post-`CLOSING`
    listener offer, listener count independent/coalesced, dynamic cpuset/quota, provider-buffer
    mutation, and close contamination.
12. Ledger-anchored `ResourceMonitorPressureTest`, `ResourceMonitorListenerTest`, and
    `PressureSignalAvailabilityTest`; existing P0 compatibility/default-cadence tests; and a
    read-only focused core compile/test gate.

Required tabular fixtures under `euhedral-hardware-utils/src/test/resources/sampling/` encode
counter timelines, slow-sensor timelines, normalization boundaries, mixed-domain golden values,
and legacy profile snapshots. They are small deterministic text, not captured host data. The Linux
scope-mismatch row requires global pressure propagation or neutral attribution and explicitly
forbids host-jiffy apportionment.

## Validation commands

Every child records exact commands/results in its completion section. Use the pinned tools from
`mise.toml` and never run a training command.

Fast Java-only compilation and focused tests avoid the bound Zig lifecycle when native setup is
not the subject:

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='<child-owned tests and named inherited tests>' surefire:test
```

P4-A adds `SampleStateEngineTest,SlowSampleCacheTest,SystemSnapshotCompatibilityAdapterTest,
ProviderContractTest`. P4-B adds `PressureEvaluatorTest,PressureCompositionTest,
PressurePropertiesTest,PressureSignalAvailabilityTest,RatioAccessorContractTest,
SystemUtilizationTest,SnapshotOwnershipTest,SnapshotIndexContractTest`. P4-C adds
`LatestValueDispatcherTest`. P4-D adds all `ResourceMonitor*Test`, including the exact
`ResourceMonitorPressureTest`, `ResourceMonitorSchedulerTest`, `ResourceMonitorListenerTest`, and
`ResourceMonitorLifecycleTest` ledger anchors, `DefaultCadenceCompatibilityTest`, and the complete
P4-A/P4-B/P4-C suite.

After each child implementation, run the P0 compatibility gate from the P0 blueprint and classify
only additive APIs and exact P4 defect-ledger corrections. During P4-D and the final integrated
conformance action, run when the documented native environment is available:

```bash
mise exec -- mvn -B -Dmaven.build.cache.enabled=false \
  -pl euhedral-hardware-utils -am verify
mise exec -- mvn -B -Dmaven.build.cache.enabled=false \
  -pl euhedral-core -am test
```

The core command is read-only validation. Hardware verify may fail before Java tests when Zig,
cross-target JNI headers, macOS SDK, Docker, or host CPU facilities are absent; record the exact
environmental limit rather than changing build/source configuration. The direct Java-only command
must still pass on a compatible JDK 21/Maven toolchain.

Every action also runs:

```bash
git diff --check
git status --short
git diff --name-only <action-start>..HEAD
git diff --exit-code <action-start>..HEAD -- \
  benchmarks/src/main euhedral-core/src/main
```

The name-only scope list must contain no training path; do not run a path-specific training check
or inspect training files. The final integrated P4 conformance audit additionally checks that no
root implementation/
validation artifact, per-child validation/conformance/audit artifact, or corresponding branch was
used and that every indexed child completion record is present.

## P4 acceptance matrix

The single integrated P4 conformance audit classifies each item exactly as `satisfied`, `deviated`,
`unverified`, or `ambiguous` with command/test evidence:

1. Public/module compatibility and P4 behavior corrections are exact, additive, and allowlisted.
2. Detailed records, validity, canonical units, and deep immutable ownership match this schema.
3. Legacy profiles are honest and current fabricated platform pressure is neutral before P5-P7.
4. Fixed-field delta state handles first/reset/wrap/regression without spikes or sidecars.
5. Fast/slow age, transient retention, unsupported clearing, and independent slow cadence match
   exact TTL boundaries.
6. Numeric validation, clamp order, overflow, NaN, negative zero, precision, and integer rounding
   match this document.
7. CPU scheduler/PSI/run-queue correlation and public stall mapping are exact.
8. Global/per-CPU throttle and honest propagation are exact.
9. Steal/external, capacity/frequency/thermal, and low-power normalization are exact.
10. Memory headroom/reclaim/stall formulas and zero/unbounded limits are exact.
11. I/O stall/latency/queue formulas are exact; productive bytes remain telemetry.
12. Actual-time per-signal asymmetric smoothing has exact constants and validity behavior.
13. Composite `max`, productive-utilization neutrality, monotonicity, and all finite ratio outputs
    are proven by golden, reflection, and generated tests.
14. Public field roles, bytes, spans, deep copies, and identical publication timestamps are exact.
15. Duration validation and the default 200 ms behavior fail fast without a busy-loop path.
16. Constructor/read/start/stop/restart/close and failure transitions match the state table.
17. Anchored first-future scheduling and all overrun/clock cases match the recurrence.
18. Each successful evaluation performs one release publication after topology update; failures
    publish none and readers have the documented happens-before edge.
19. Listener delivery is nonblocking, bounded, ordered, coalesced, reentrant-safe, Throwable-safe,
    and closed truthfully with no common-pool work.
20. Allocation/retention is bounded by CPU span/listener count and close leaves no dynamic state,
    thread, pending value, provider buffer, or cross-test contamination.
21. Platform collection and core production remain unchanged; training is neither inspected nor
    run.
22. P0 compatibility, focused hardware tests, applicable full verification, read-only core tests,
    diff hygiene, and environmental limits are recorded.

Common portions of R01-R10 and R13-R14 are owned here; platform collection portions remain
explicitly carried to P5-P7. Any new unit, threshold, validity state, TTL, smoother, composition,
lifecycle transition, deadline rule, memory mode, queue bound, or callback ownership choice is a
blueprint redesign and stops the implementation action.

## Implementation model reassessment

The unsplit P4 pass would span one module but four ownership packages, two public contracts, many
immutable schemas, six lifecycle states, three concurrent state machines, numerical recovery,
strict floating-point behavior, compatibility migration, and broad deterministic test repair. It
would require simultaneous knowledge of provider quirks, math, public projection, scheduler JMM,
listener safety, and downstream core semantics. Earlier P2/P3 evidence also shows that
responsibility splits materially improve implementation reviewability for immutable publication
and lifecycle work. The root implementation is therefore rejected and superseded. The developer
has selected one integrated conformance action after all four implementations instead of four
child conformance actions.

After the split:

| Child | Remaining coupled difficulty                                                         | Parent-selected implementation capability |
|-------|--------------------------------------------------------------------------------------|-------------------------------------------|
| P4-A  | Immutable schema plus temporal delta/cache recovery and legacy compatibility         | `gpt-5.6-sol`, `high`                     |
| P4-B  | Exact multidomain math, floating-point sanitation, smoothing, and public projection  | `gpt-5.6-sol`, `high`                     |
| P4-C  | One bounded dispatcher state machine, reentrancy, close, and JMM proof               | `gpt-5.6-sol`, `high`                     |
| P4-D  | Six-state monitor lifecycle, fake scheduling, transactional integration, and cleanup | `gpt-5.6-sol`, `high`                     |

Each remains implementation-complex despite a complete design: A and B combine recovery/precision,
and C and D combine concurrency/failure/JMM behavior. A lower-capability or medium-effort pass is
not selected. Each child blueprint must confirm or upgrade this selection after inspecting only
its bounded context; it may not downgrade silently. The single final conformance audit uses
`gpt-5.6-sol`, `high`. Child
blueprints use `gpt-5.6-sol`, `max` because they must validate inventory and translate this parent
contract into exact local tests without reopening it.

## Risks and unresolved decisions

- Current built-in providers cannot honestly populate many rich signals. P4 deliberately publishes
  neutral unsupported values until P5-P7, rather than preserving fabricated pressure.
- A due platform slow sensor can overrun one fast poll. The scheduler skips boundaries; future
  platform phases may improve collection internals without changing cadence/TTL semantics.
- External close can wait for an uncooperative provider or listener because truthfully guaranteeing
  no work after close is stronger than a false timeout. Provider calls are required to be bounded;
  callback ownership is explicitly documented.
- The chosen normalization thresholds are policy constants and may affect throughput when P5-P7
  begin supplying signals. They are fixed for P4 and may change only through a new reviewed plan,
  golden updates, and compatibility record.
- Listener registration is additive-only because the existing API has no removal operation. Close
  clears all registrations; adding removal is outside P4.

There are no unresolved P4 design decisions. Units, validity, counters, TTLs, thresholds,
correlation, smoothing, public mapping, duration range, state transitions, scheduling, publication,
listener bounds, memory modes, ownership, split order, and selected implementation capability are
settled. Child blueprints may enumerate local files/fixtures and prove feasibility only.

## Handoff condition

Hand this parent blueprint off for review and authorized merge into the P4 root only when:

- the controlling plan names all four child blueprint/implementation branch families, artifacts,
  prompts, and the single final conformance audit, and marks root implementation/validation and
  every per-child conformance action as superseded;
- the plan contains the P3 closeout and P4 developer-review summary;
- every child has a bounded context, acceptance surface, complete prompt, and selected capability;
- implementation can translate this contract without choosing a unit, formula, threshold,
  rounding rule, TTL, smoothing constant, state transition, recurrence, memory mode, or listener
  queue design;
- only this blueprint and authorized plan/planning text differ from the P4 root;
- `git diff --check`, link/path checks, and planning-scope checks pass; and
- no child or implementation branch has been created before this parent blueprint merge.

Do not implement P4 from this branch. After review and explicit merge authorization, merge this
blueprint child into the P4 root, create only the P4-A blueprint branch from that updated root, and
rerun P4-A's sizing/model gate.
