# Phase 4 Resource Monitor and Pressure Conformance Audit

## Scope and disposition

Audited `hardware-utils-overhaul/phase-4-pressure-monitor-audit` from the updated P4 root branch
`hardware-utils-overhaul/phase-4-pressure-monitor` (at commit `cbb034e`). The parent artifacts are
the P4 parent blueprint (`docs/blueprints/hardware-utils/phase-4-resource-monitor-pressure.md`) and
the four child blueprint and completion records:

- P4-A: `docs/blueprints/hardware-utils/phase-4-sample-validity-contract.md`
- P4-B: `docs/blueprints/hardware-utils/phase-4-pressure-mathematics.md`
- P4-C: `docs/blueprints/hardware-utils/phase-4-listener-publication.md`
- P4-D: `docs/blueprints/hardware-utils/phase-4-monitor-lifecycle-scheduler.md`

By explicit developer authorization, P4 had no intermediate child validation or audit branches. This
document represents the single integrated conformance and manual-review action for Phase 4.

Inspection was limited to the P4 hardware-utils sources and tests, named read-only core consumers
(`ControlPlaneLattice`, `ControlPlaneFragment`, `ControlPlaneCache`, `LatticeEdge`, `LatticeVertex`,
`FrameFactory`), child handoffs, and summarized P0-P3 closeouts. `euhedral-training` was neither
inspected nor run.

**Disposition: review-ready; P4 is not yet closed.** All 22 parent criteria and common P4 portions
of R01-R10/R13-R14 are classified as `satisfied`. No production or blueprint corrections are
required. The audit child must be reviewed and explicitly authorized for merge/closeout before
merging into the P4 root, removing the temporary P4 status block in `AGENTS.md`, appending the P4
closeout summary to `docs/plans/hardware-utils-platform-parity-overhaul.md`, or creating P5.

## Parent acceptance matrix

| Parent criterion                                                                    | Classification | Evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
|-------------------------------------------------------------------------------------|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1. Public/module compatibility & additive P4 corrections                            | satisfied      | Public API components in `SystemUtilization`, `ResourceMonitor`, `SystemInfo`, and exported packages in `module-info.java` are strictly preserved. All new P4 SPI types live in unexported package `io.euhedral_execution.hardware_utils.internal.*`. `gradle build` and `gradle :euhedral-hardware-utils:test` succeed without breaking existing callers.                                                                                                                                    |
| 2. Detailed records, validity, canonical units & deep immutability                  | satisfied      | `SignalValidity` (`VALID`, `TRANSIENT_FAILURE`, `UNSUPPORTED`) and canonical units (nanoseconds, bytes, dimensionless ratios in `[0.0, 1.0]`) are enforced across `FastHardwareSample`, `SlowHardwareSample`, `IntervalHardwareSample`, and primitive signal types (`CounterSignal`, `DoubleGaugeSignal`, `LongGaugeSignal`, `BooleanSignal`, `ThermalSignal`). Compact constructors enforce range invariants, canonical zero payloads for non-`VALID` signals, and deep-copy arrays/BitSets. |
| 3. Honest legacy profiles & neutral platform fallback before P5-P7                  | satisfied      | `SystemSnapshotCompatibilityAdapter` wraps legacy providers into `DetailedSystemSnapshotProvider`. Scope-mismatched or unverified legacy signals (e.g. host-apportioned cgroup PSI, process-adjusted macOS load, Windows busy time) are marked `UNSUPPORTED`, falling back to canonical neutral zero pressure rather than fabricating inaccurate contention before platform native collection in P5-P7.                                                                                       |
| 4. Fixed-field delta state, reset/regression/first-sample handling                  | satisfied      | `SampleStateEngine` tracks prior fast/slow samples per CPU and global domain. Counter regressions (current < previous) and initial samples return `BASELINE` with 0 delta and 0 elapsed time, preventing artificial pressure spikes or requiring sidecars.                                                                                                                                                                                                                                    |
| 5. Fast/slow TTL boundaries, transient retention & independent slow cadence         | satisfied      | `SlowSampleCache` manages slow metrics with a 60 s TTL. Fast sample TTL is 30 s (`min(30s, max(1s, 5P))`). `TRANSIENT_FAILURE` retains the last valid payload without advancing observation timestamp; `UNSUPPORTED` clears cached value immediately. Independent slow polling cadence checked by `isSlowDue(now)`. Tested in `SlowSampleCacheTest` and `SampleStateEngineTest`.                                                                                                              |
| 6. Numeric validation, clamp order, precision & sanitation                          | satisfied      | `PressureEvaluator`, `PressureProjection`, `PressureState`, and public `SystemUtilization` constructors sanitize floating-point ratios (`NaN`, `Infinity`, negative zero `-0.0d -> 0.0d`, clamping to `[0.0, 1.0]`). Direct public record constructors sanitize input arrays and ratios. Tested in `RatioAccessorContractTest` and `SystemUtilizationTest`.                                                                                                                                   |
| 7. CPU scheduler/PSI/run-queue correlation & stall mapping                          | satisfied      | `PressureEvaluator` evaluates CPU pressure from scheduler wait, PSI stall ratio, per-CPU quota throttle, run-queue load, and external contention. When PSI stall or scheduler wait is present, it maps to CPU pressure without double scaling. Tested in `PressureCompositionTest`.                                                                                                                                                                                                           |
| 8. Global/per-CPU throttle & honest propagation                                     | satisfied      | Per-CPU quota throttle ratio is calculated as `throttleNs / elapsedNs`. Scope mismatch (cgroup vs host) uses neutral per-CPU attribution when evidence is unavailable. Tested in `PressureCompositionTest`.                                                                                                                                                                                                                                                                                   |
| 9. Steal/external, capacity/frequency/thermal & low-power normalization             | satisfied      | Steal time ratio is `stealNs / elapsedNs`. Thermal severity (`NOMINAL`, `FAIR`, `SERIOUS`, `CRITICAL`) maps to normalized thermal pressure (`0.0`, `0.25`, `0.65`, `1.0`). Active low-power mode applies low-power capacity penalty. Tested in `PressureCompositionTest` and `PressureEvaluatorTest`.                                                                                                                                                                                         |
| 10. Memory headroom/reclaim/stall formulas & zero/unbounded limits                  | satisfied      | Memory pressure combines working-set headroom ratio, reclaim activity, and memory stall ratio. Unbounded/zero hard limits handle zero-limit cases gracefully without `NaN`/`Infinity`. Tested in `PressureCompositionTest`.                                                                                                                                                                                                                                                                   |
| 11. I/O stall/latency formulas vs productive throughput telemetry                   | satisfied      | `diskIOBytesPerSecond` and productive throughput are emitted strictly as telemetry in `HardwareUtilization`. I/O pressure is driven by I/O stall ratio and latency relative to baseline, not by productive byte throughput. Tested in `PressureCompositionTest` and `ResourceMonitorTest`.                                                                                                                                                                                                    |
| 12. Actual-time asymmetric EWMA smoothing                                           | satisfied      | `PressureEvaluator` applies actual elapsed time `dt` for asymmetric EWMA smoothing: `alpha_up = 1 - exp(-dt / tau_up)` ($\tau_{up} = 0.5\text{ s}$), `alpha_down = 1 - exp(-dt / tau_down)` ($\tau_{down} = 2.0\text{ s}$). Fast spike response and gradual recovery decay. Tested in `PressureEvaluatorTest`.                                                                                                                                                                                |
| 13. Composite max, productive-work neutrality, monotonicity & finite outputs        | satisfied      | `HardwareUtilization.pressure()` computes max across effective CPUs and domain pressures. Productive CPU work alone does not raise pressure. Monotonicity and finite ratio outputs in `[0.0, 1.0]` proven by `PressureCompositionTest`, `PressurePropertiesTest`, `PressureSignalAvailabilityTest`.                                                                                                                                                                                           |
| 14. Public field roles, deep copies & publication timestamp identity                | satisfied      | `HardwareUtilization` and `CpuSnapshot` store deep-copied arrays and `UnmodifiableBitSet`. All published snapshot components share the identical evaluation timestamp (`evaluationNs`). Tested in `SnapshotOwnershipTest`, `SnapshotIndexContractTest`.                                                                                                                                                                                                                                       |
| 15. Non-positive duration fail-fast & nonblocking 200 ms scheduler                  | satisfied      | `ResourceMonitor` constructor validates `sampleRate != null && sampleRate.toNanos() > 0`, throwing `IllegalArgumentException` on non-positive or null duration. Polling loop uses `DeadlineWaiter` with `Thread.onSpinWait()` or bounded `parkNanos`, avoiding busy-spin loops. Tested in `ResourceMonitorTest`.                                                                                                                                                                              |
| 16. Six-state monitor lifecycle & publication-claim close ordering                  | satisfied      | Six states (`NEW`, `STARTING`, `RUNNING`, `STOPPED`, `CLOSING`, `CLOSED`) linearized via atomic `VarHandle` `STATE`. `start()` transitions `NEW/STOPPED -> STARTING -> RUNNING`. `stop()` transitions `RUNNING -> STOPPED` with coalesced read. `close()` gates on `EVAL_ACTIVE` and `PUB_CLAIMED` before transitioning to `CLOSED`.                                                                                                                                                          |
| 17. Anchored recurrence, overrun handling & clock regression reanchoring            | satisfied      | `runLoop()` anchors to `t0`. On overrun (`now >= nextTick`), skips intervals `skips = (now - t0) / sampleRateNs` and sets `nextTick = t0 + (skips + 1) * sampleRateNs`. Clock regression (`now < lastNow`) reanchors `t0 = now`. Tested in `ResourceMonitorTest`.                                                                                                                                                                                                                             |
| 18. Single release publication after topology update; zero publication on failure   | satisfied      | `evaluateAndPublish()` updates `TopologyUpdater.update(util)` strictly before `dispatcher.offer(util)`. Controlled by `EVAL_ACTIVE` and `PUB_CLAIMED` VarHandles. No publication on failed evaluation. Volatile `lastUtilization` provides happens-before edge.                                                                                                                                                                                                                               |
| 19. Bounded, ordered, coalesced, reentrant & Throwable-safe listener dispatcher     | satisfied      | `LatestValueDispatcher` uses a dedicated worker thread (no `ForkJoinPool.commonPool`). Offer coalesces to one active dispatch and one latest pending item (bounded). CopyOnWrite listener list allows safe reentrant add/remove during callbacks. Callback `Throwable` exceptions caught and isolated. `beginClose()` and `awaitClosed()` ensure no callbacks run after close. Tested in `LatestValueDispatcherTest`.                                                                         |
| 20. Bounded allocation retention & clean state teardown                             | satisfied      | All state arrays sized by fixed `cpuCount`. On `CLOSED`, dispatcher worker thread terminates, polling thread terminates, listener list and pending state clear. No thread, memory, or static state leaks across tests. Tested in `LatestValueDispatcherTest` and `ResourceMonitorTest`.                                                                                                                                                                                                       |
| 21. Platform collection & core production unchanged; training uninspected and unrun | satisfied      | Platform native/collector bodies and `euhedral-core` production sources are unchanged (`git diff af5a130..HEAD` shows 0 changes in core production code except test fixture updates; 0 changes in `euhedral-training`). `euhedral-training` was neither inspected nor run.                                                                                                                                                                                                                    |
| 22. P0 compatibility, focused hardware tests, read-only core tests, diff hygiene    | satisfied      | P0 baseline compatibility tests pass (`SystemInfoTest`, `SystemUtilizationTest`). All 67 P4 hardware-utils tests pass. Read-only core tests (`euhedral-core`) pass 100%. `git diff --check` is clean.                                                                                                                                                                                                                                                                                         |

## Audit of common P4 portions of R01-R10 and R13-R14

| Item | P4 Responsibility                                                                                                                         | Classification | Evidence and Carryover                                                                                                                                                                     |
|------|-------------------------------------------------------------------------------------------------------------------------------------------|----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| R01  | Define canonical units (ns timestamps, bytes, dimensionless ratios in `[0.0, 1.0]`) and adapt providers via detailed sampling structures. | satisfied      | Fast/slow hardware sample contracts implemented in `internal.sampling`. Platform collection adaptation carried explicitly to P5-P7.                                                        |
| R02  | Correct Linux quota calculation, avoid double scaling, and handle PSI reset without stale stalls.                                         | satisfied      | `SampleStateEngine` computes CPU cardinality, quota period, and resets PSI cleanly on counter regression. Linux native cgroup parsing carried explicitly to P5.                            |
| R03  | Correct macOS cumulative counter schemas and memory semantics.                                                                            | satisfied      | Cumulative counter schemas and working-set headroom formulas defined in `SampleStateEngine` and `PressureEvaluator`. macOS Mach API collection carried explicitly to P7.                   |
| R04  | Correct Windows cycle count units and private working-set underflow handling.                                                             | satisfied      | Standardized ns and ratio contracts in `DetailedSystemSnapshotProvider`. Windows PDH/job collection carried explicitly to P6.                                                              |
| R05  | Restore dimensional correctness to memory usage and eliminate zero-limit NaN/Infinity divisions.                                          | satisfied      | Restored dimensional correctness (bytes, ratios) and safe zero-limit behavior without NaN/Infinity in `PressureEvaluator` and `SystemUtilization`.                                         |
| R06  | Separate productive I/O throughput (telemetry) from I/O stall/contention (pressure).                                                      | satisfied      | `diskIOBytesPerSecond` kept strictly as telemetry; I/O stall ratio and latency used for pressure in `PressureEvaluator`. Linux device filtering carried explicitly to P5.                  |
| R07  | Define independent normalized domain signals and composition via composite max.                                                           | satisfied      | Independent normalized domain signals (CPU, Memory, I/O, Thermal, Power) composed via composite max in `PressureEvaluator` and `HardwareUtilization.pressure()`.                           |
| R08  | Use actual elapsed-time EWMA constants and fixed-rate deadline waiter without catch-up.                                                   | satisfied      | Actual-time asymmetric EWMA smoothing ($1 - e^{-\Delta t / \tau}$) and `DeadlineWaiter` fixed-rate recurrence in `PressureEvaluator` and `ResourceMonitor`.                                |
| R09  | Bounded, ordered, latest-value listener delivery without common-pool futures, spin deadlocks, or leaks.                                   | satisfied      | `LatestValueDispatcher` implements bounded one-active/one-pending coalescing, reentrant listener registration, Throwable isolation, and clean close.                                       |
| R10  | Define a complete idempotent six-state monitor lifecycle without constructor sampling or concurrent poll races.                           | satisfied      | `ResourceMonitor` six-state machine (`NEW` -> `STARTING` -> `RUNNING` -> `STOPPED` -> `CLOSING` -> `CLOSED`) with atomic VarHandles, no constructor sampling, and coalesced stopped reads. |
| R13  | Add validity-tracked signals for scheduler, memory, I/O, thermal, and low-power domains.                                                  | satisfied      | Detailed signal records track `SignalValidity` (`VALID`, `TRANSIENT_FAILURE`, `UNSUPPORTED`) and TTLs. Platform collector implementations carried explicitly to P5-P7.                     |
| R14  | Prohibit host-activity apportionment when cgroup and host scope differ; use neutral attribution.                                          | satisfied      | Scope mismatch uses neutral per-CPU attribution when evidence is unavailable, prohibiting host-activity apportionment. Platform collection carried explicitly to P5.                       |

## Detailed domain audits

### End-to-end data flow

The pipeline operates strictly in sequence:

1. `DetailedSystemSnapshotProvider.sampleSlow()` / `sampleFast()` return immutable
   `SlowHardwareSample` and `FastHardwareSample`.
2. `SampleStateEngine` evaluates deltas, validates timestamps, enforces fast/slow TTLs, and produces
   an immutable `IntervalHardwareSample`.
3. `PressureEvaluator.evaluate()` calculates multidomain normalized pressures, applies actual-time
   asymmetric EWMA smoothing, computes composite max pressure, and builds candidate
   `HardwareUtilization`.
4. `TopologyUpdater.update(util)` applies topological context to `TopologyMapper`.
5. Exactly-once publication updates `lastUtilization` and invokes
   `LatestValueDispatcher.offer(util)`.
6. `LatestValueDispatcher` dispatches the latest utilization to registered `MonitorListener`
   callbacks on its dedicated thread.

### Canonical units and counter baselines

All timestamps use monotonic `System.nanoTime()` nanoseconds. Counter fields represent
non-decreasing cumulative values. Counter regressions or first samples result in `BASELINE`
resolution with 0 delta and 0 elapsed time, preventing false spikes.

### Fast/slow TTLs and caching

Slow metrics are cached via `SlowSampleCache` with a 60-second TTL. Fast metrics have a 30-second
TTL. `TRANSIENT_FAILURE` retains valid values within TTL without updating observation timestamps.
`UNSUPPORTED` clears cached state immediately.

### Formulas, precision, clamping, and finite ratio manifest

All exposed pressure ratios are guaranteed finite doubles in `[0.0, 1.0]`. Floating-point values are
sanitized (`NaN`/`Infinity` converted to neutral zero, negative zero `-0.0d` converted to `0.0d`,
clamped to `[0.0, 1.0]`).

### Correlated signals & actual-time EWMA smoothing

Correlated CPU signals (scheduler wait, PSI stall, quota throttle, run-queue load) are combined into
composite CPU domain pressure. EWMA smoothing uses actual elapsed nanoseconds:
$$\alpha = 1 - e^{-\Delta t / \tau}$$
where $\tau_{up} = 0.5\text{ s}$ for rising pressure and $\tau_{down} = 2.0\text{ s}$ for falling
pressure.

### Snapshot deep-copy ownership and timestamps

`HardwareUtilization` and `CpuSnapshot` perform deep copies of input arrays and `UnmodifiableBitSet`
in their constructors. Component accessors return unmodifiable wrappers or array clones. All
components of a single publication share the identical evaluation timestamp (`evaluationNs`).

### Duration safety

`ResourceMonitor` validates `sampleRate != null && sampleRate.toNanos() > 0` in its constructor,
throwing `IllegalArgumentException` on invalid inputs.

### Six-state lifecycle & close ordering

The monitor implements states `NEW` (0), `STARTING` (1), `RUNNING` (2), `STOPPED` (3), `CLOSING`
(4), and `CLOSED` (5) via atomic `VarHandle` state transitions. `close()` sets `CLOSING`, invokes
`dispatcher.beginClose()`, interrupts the polling thread (if external), waits for active evaluation
(`EVAL_ACTIVE`) and publication (`PUB_CLAIMED`) to exit, transitions to `CLOSED`, and calls
`dispatcher.awaitClosed()`.

### Anchored 200 ms recurrence & 0 -> 450 -> 600 ms overrun behavior

Recurrence anchors to initial timestamp $t_0$. Overruns calculate skipped boundaries:
$$\text{nextTick} = t_0 + (\text{skips} + 1) \times P$$
Under an artificial 250 ms poll delay starting at $t=200\text{ ms}$, the second sample executes
at $t=450\text{ ms}$, and the next scheduled tick calculates to $t=600\text{ ms}$, preserving
boundary alignment without catch-up bursts.

### JMM and atomic publication

State transitions use VarHandle `getAcquire`/`compareAndSet`/`setRelease`. Volatile
`lastUtilization` guarantees visibility to `getUtilization()` readers.

### Bounded listener dispatcher

`LatestValueDispatcher` maintains a single active dispatch thread and at most one pending
utilization candidate. Multiple rapid offers coalesce into the latest pending value.
`CopyOnWriteArrayList` permits safe concurrent `addListener` and `removeListener` during callbacks.
`Throwable` exceptions inside callbacks are caught and logged without killing the dispatcher thread.

### Allocation and retention hygiene

All data structures are sized strictly by fixed CPU count. Closing the monitor clears listener lists
and pending references, terminating dispatcher and polling threads.

### Compatibility and read-only core integration

P0 compatibility tests (`SystemInfoTest`, `SystemUtilizationTest`) pass 100%. Read-only core
consumers (`ControlPlaneLattice`, `ControlPlaneFragment`, `ControlPlaneCache`, `LatticeEdge`,
`LatticeVertex`, `FrameFactory`) compile and pass all 99 core unit tests.

## Commands, results, and limits

- `git status --short`: clean (before audit artifact creation).
- `gradle build`: SUCCESSFUL (all modules compiled and tested).
- `gradle :euhedral-hardware-utils:test --rerun-tasks`: SUCCESSFUL (67 tests executed, 0 failures).
- `gradle :euhedral-core:test --rerun-tasks`: SUCCESSFUL (99 tests executed, 0 failures).
- `git diff --check`: clean (0 whitespace/formatting issues).

**Environmental limits**: The local environment lacks a running Docker daemon and native
cross-compilation headers for C/Zig JNI generation. Test suite execution uses Java fallback mocks
and synthetic fixtures, which is a recognized environmental limitation per `docs/AGENT_WORKFLOW.md`.
