# Phase 7: Production Fragment Decision-Tree Integration

Status: implemented and validated

Prior evidence:

- [
  `phase-5-first-production-fragment-decision-tree.md`](phase-5-first-production-fragment-decision-tree.md)
- [`phase-6-executor-body-cost-sensor.md`](phase-6-executor-body-cost-sensor.md)

Blueprint intensity: maximum

Implementation intensity: high

## Decision and scope

Replace the current normal-mode service-latency transition with the first explicit production
DIRECT/STAGED tree. The tree uses only the two validated physical inputs:

```text
live upstream handles / registered active fragment workers
tier-neutral executor-body cost
```

The implementation selects between the existing DIRECT and STAGED operation orders. It does not
change either path, request watermarks, routing, cache ownership, ordered-frame behavior, pressure
caps, or batch truncation. It does not remap the performance surface or add a readiness, batch-size,
topology, source-label, or learned-policy branch.

The existing path-inclusive `serviceTimeNs` remains the input to batch sizing and execution-latency
telemetry. It is removed only from mode selection. The body-cost estimate is a separate owner-local
signal and must not be derived by subtracting path cost from `serviceTimeNs`.

## Supported tree

Normal mode implements exactly:

```text
registeredWorkers <= 0
    -> DIRECT

liveHandles >= registeredWorkers
    -> DIRECT

bodyCostHistoryCount < 32
    -> DIRECT

smoothedBodyCostNs <= 90 ns
    -> DIRECT

smoothedBodyCostNs >= 95 ns
    -> STAGED

90 ns < smoothedBodyCostNs < 95 ns
    -> retain current settled mode
```

The `registeredWorkers <= 0` case is a conservative invalid/startup fallback, not another physical
branch. An executing registered fragment should observe at least one worker.

Keep one package-private selector in `FragmentControlPolicy` with exactly these conceptual inputs:

```text
liveHandles
registeredWorkers
bodyCostHistoryCount
smoothedBodyCostNs
currentSettledMode
```

The selector returns only `DIRECT` or `STAGED`. Do not create a rules engine, policy object graph,
tree framework, classifier abstraction, vector input, or third mode.

## Guard-band derivation

Phase 6 retained the following extrema in the validated executor-sensor units:

```text
max(all 80-round estimates) = 88.420588 ns
min(all 96-round estimates) = 99.224869 ns
```

Choose the bounds before production benchmarking as follows:

- `CHEAP_BODY_COST_MAX_NS = 90.0`: the next whole 5 ns value above every retained 80-round estimate;
- `EXPENSIVE_BODY_COST_MIN_NS = 95.0`: the next whole 5 ns value above the cheap bound that remains
  below every retained 96-round estimate.

These constants preserve every retained 80-round observation on the DIRECT side and every retained
96-round observation on the STAGED side under insufficient availability. They leave an explicit 5 ns
open interval rather than choosing a midpoint. The bounds operate directly in sensor units; do not
subtract the common executor-call/accounting offset or describe either value as isolated body
nanoseconds.

The Phase 6 24-round maximum was 39.370065 ns, so the clearly cheap control has substantial distance
from the cheap bound. The constants are conservative evidence from the validated host and fixture,
not universal architectural crossover claims. If the production aggregation fails to keep steady
80-round estimates at or below 90 ns and steady 96-round estimates at or above 95 ns, stop and
investigate aggregation. Do not move the bounds.

## Production body-cost data path

Use the existing synchronous clone ownership:

```text
AbstractExecutor.ExecutionTerminal
    -> sparse elapsed time around AbstractExecutor.execute(frame)
    -> setup-only LongConsumer callback
    -> FragmentControlPolicy.recordBodyCost(elapsedNs)
    -> owner-local estimator
    -> completed-batch selector
```

`BaseCloneableObject` already owns the exact cloned `ControlPlaneFragment` and `AbstractExecutor`
pair. In its clone-only private constructor, after assigning both objects and before `start()` or
`input()`, ask the fragment to connect its body-cost recorder to that executor. Do not put the
recorder in `FragmentConfig`, `CloneConfig`, a static registry, a socket object, or a cross-core
array.

Add one narrowly documented setup method on `ControlPlaneFragment` that accepts the paired
`AbstractExecutor`. It attaches `controlPolicy::recordBodyCost` only when:

- the fragment has a normal control policy; and
- normal selection is active, or a forced diagnostic explicitly requests production sampling.

The public visibility required between the exported `control_plane` and `impl` packages is an
internal lifecycle seam, not a new user configuration surface. Preserve every existing public
constructor. The prototype `BaseCloneableObject`, unconfigured fragments, and action-picker
benchmark fragments do not attach a production recorder.

Extend the Phase 6 `AbstractExecutor` seam rather than wrapping `ControlPlaneFragment.accept`:

- retain `AbstractExecutor(int cpu)` and the protected diagnostic constructor;
- add one setup-only recorder attachment used by `BaseCloneableObject`;
- require attachment before the first `input()` creates an execution terminal;
- permit at most one production recorder per cloned executor;
- retain the fixed interval of 256 eligible executor calls; and
- snapshot the configured clock, interval, and recorder references into the terminal before work
  begins.

If an executor already has the Phase 6 diagnostic recorder, one sampled executor call must use one
timer pair and fan the successful elapsed value to both explicitly enabled recorders. Do not compose
callbacks on each sample or read two clocks. Normal production attachment requires the validated
256-call cadence; reject an incompatible preconfigured diagnostic cadence rather than silently
changing production semantics.

All recorder configuration happens before worker publication. The terminal countdown and policy
estimator then use plain access because the same pinned fragment thread calls the executor terminal
synchronously and returns through the callback. There is no atomics-per-sample publication,
allocation, lock, registry lookup, or cross-core read in this path.

## Estimator ownership and arithmetic

Keep the estimator fields in `FragmentControlPolicy`, beside the selector that consumes them. They
are owned by one pinned fragment thread for the policy lifetime.

Use one fixed, allocation-free robust aggregation:

1. Ignore elapsed values less than or equal to zero.
2. Fill a 32-sample owner-local window.
3. At each non-overlapping window boundary, scan the primitive array for its second-smallest value.
4. Publish a cheap or guard-band estimate immediately.
5. Publish an expensive estimate only after two consecutive completed windows are at or above 95 ns;
   a non-expensive window resets that confirmation count.
6. Count only successfully published executor samples and saturate the history count.

Timer interference is additive, so the second minimum retains the underlying body-cost region while
tolerating isolated positive timing stalls. Non-overlapping windows prevent one correlated rolling
window from being counted repeatedly. Two expensive windows require 64 valid samples, or 16,384
eligible executor calls on the productive owner, before STAGED can first be selected. A cheap return
requires one completed 32-sample window. The 90/95 bounds and 256-call sampling cadence remain
unchanged. Mode application still waits for the next completed batch.

The 32-sample array, index, saturated history count, two-state expensive confirmation count, and
published estimate remain owner-local. No additional selector branch or third execution mode is
introduced.

## Executor lifecycle and failure behavior

Preserve the Phase 6 executor sequence:

```text
isAlive
    -> optional timer start
    -> AbstractExecutor.execute(frame)
    -> optional timer stop and recorder callback
    -> doFinally
```

Only a normally returned executor body publishes a sample. A body exception or cancellation resets
the sampled-call cadence as already validated, publishes no body-cost value, and follows the
existing `doFinallyWithError` or `doFinally` behavior. Liveness, terminal dispatch, logging, and
finalization remain outside the interval.

Do not move the timer into frame implementations or add a timer pair per frame. Do not catch a
recorder failure as an application-body failure. The production recorder is an internal arithmetic
operation that must not throw; deterministic tests should prove that invariant. The existing
diagnostic recorder contract remains setup-only and should be invoked after elapsed time is known.

## Safe-boundary application

`ControlPlaneFragment.recordProgress` already calls `FragmentControlPolicy.completeBatch` only after
`state.completed` reaches the current target. Keep the mode unchanged while
`state.completed < state.batchSize`.

At a completed boundary:

1. finish the current path-inclusive service and throughput observations;
2. acquire-read the true live handle count from the owner `UpstreamQueue`;
3. read the registered fragment-worker count from the corrected `LatticeEdge` counter;
4. call `completeBatch(eligibleCap, liveHandles, registeredWorkers)`;
5. select the next mode inside that call; and
6. calculate the next batch size using the existing service estimate, mode-specific target work,
   growth limit, and eligible cap.

The selector runs before the existing next-batch calculation so batch sizing uses the selected mode
for that next batch. Do not change `DIRECT_TARGET_BATCH_WORK_NS`, `STAGED_TARGET_BATCH_WORK_NS`, the
two-times batch growth limit, the pressure-derived cap, or the batch floor in this phase.

Remove the current normal-mode `STAGED_THRESHOLD_NS`, `DIRECT_THRESHOLD_NS`, completed-batch
transition streak, and `updateMode()` service-latency selector. Keep `serviceTimeNs`,
`recordExecution`, miss/park behavior, and their tests where they still describe batch control.

At the top of `cycle`, an ordinary cached upstream-count change currently calls `CycleState.reset`
and can discard a partial batch and all policy history. Replace that coupling with only an
owner-local cached-count update. Availability for policy is read from the true counters at the next
completed boundary. Do not reset, truncate, or switch an in-progress batch because a handle appears
or disappears.

## Availability semantics

Use the already-validated state without modification:

- `UpstreamQueue.getTrueUpstreamCount()` for live handles; and
- `LatticeEdge.getThreadCount()` for registered active fragment workers.

The count comparison is integer-only. Do not create a ratio object or use benchmark source labels.
Do not replace live handles with ready handles, scan `ACTIVE_PARTITIONS`, or add an availability
registry. Phase 5 already corrected registration/removal idempotence and proved the repeating-source
`1/2` and `2/2` fixtures.

## Guard-band and settled-mode behavior

The guard band is hysteresis implemented by returning `currentSettledMode`. The two-window expensive
confirmation belongs to signal aggregation and cannot be advanced by repeated batch reads.

- A valid value at exactly 90 ns selects DIRECT.
- A valid value at exactly 95 ns selects STAGED when handles are insufficient.
- A value strictly between the two retains either DIRECT or STAGED.
- Sufficient handles always settle the policy to DIRECT, including when the prior mode was STAGED.
- If handles later become insufficient, the retained body-cost estimate is evaluated at the next
  boundary. A clearly expensive estimate may immediately settle STAGED; a guard-band estimate
  retains the now-settled DIRECT choice.
- Reset or insufficient history always settles DIRECT.

Do not preserve a hidden scarcity-only mode separate from the current mode. Do not add dwell time,
configurable smoothing, or online alternative-path probes.

## Lifecycle semantics

Apply the following exact lifecycle rules:

| Event                              | Body-cost history  | Settled mode                                          |
|------------------------------------|--------------------|-------------------------------------------------------|
| Fragment clone creation            | Empty              | DIRECT unless forced diagnostic mode                  |
| Source addition/removal            | Retain             | Re-evaluate availability only at next completed batch |
| Sufficient -> insufficient handles | Retain             | Evaluate retained estimate at next completed batch    |
| Insufficient -> sufficient handles | Retain             | DIRECT at next completed batch                        |
| Full idle                          | Retain             | Retain until a later completed batch evaluates inputs |
| Drain on a surviving clone         | Retain             | Retain; do not change mode during drain               |
| Rebalance, surviving clone         | Retain             | Retain across drain/resume                            |
| Rebalance, new clone               | Empty              | DIRECT startup                                        |
| `resetForNextTrial`                | Clear              | DIRECT or captured forced mode                        |
| Close/removal                      | Discard with clone | No successor inheritance                              |

The executor object and override define the work boundary for the clone lifetime. A source-count
change does not prove that the executor/workload changed, and an executor workload can change
without a source-count change. Retaining and adapting the estimate is therefore less arbitrary than
resetting on topology labels. Do not reset useful history on a mode change.

Trial reset deliberately clears estimator window, index, confirmation count, history count,
estimate, settled normal mode, existing service history, and batch state through the owner-thread
`CycleState.reset` path. The executor's 256-call countdown may retain its sampling phase across a
trial reset: no elapsed value survives, 32 new valid samples are still required, and resetting that
plain terminal field from another thread would add an unnecessary lifecycle handoff. Record this
behavior in its test.

`setDrainMode`, full idle, and normal source completion are not trial reset. Close requires no
publication of the discarded owner-local estimator.

## Forced and diagnostic compatibility

Keep the current two-argument setup lease:

```text
installDiagnosticOverride(mode, batchSize)
```

It must continue to:

- force DIRECT or STAGED before fragments are constructed;
- bypass the normal selector;
- hold the requested fixed batch within the existing floor and cap; and
- leave production body-cost sampling disabled.

Add only the narrow overload or record field needed to request production body-cost sampling while
still forcing a mode. The existing two-argument form delegates to `sampling = false`. Explicit
forced sampling updates the estimator and exposes diagnostic evidence, but `completeBatch` still
returns the forced mode and never evaluates availability or guard-band conditions.

Preserve the independent Phase 6 executor diagnostic constructor and
`executorBodyCost` benchmark. A forced benchmark can therefore use:

```text
forced mode + no sampler
forced mode + Phase 6 raw sampler
forced mode + production estimator sampler
```

The default remains the first form. Normal-tree benchmarks install no forced override. Do not alter
`FragmentActionPicker`, training compatibility, or action-picker benchmark mode.

## Implementation footprint

Production changes are limited to:

- `euhedral-core/src/main/java/io/euhedral_execution/core/generics/AbstractExecutor.java`
    - retain the validated body-only interval;
    - add setup-only production-recorder attachment and one-timer fan-out;
    - preserve constructor, clone, execution, and finalization compatibility;
- `euhedral-core/src/main/java/io/euhedral_execution/core/impl/BaseCloneableObject.java`
    - connect each cloned fragment/executor pair before start;
- `euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java`
    - provide the narrow connection seam;
    - read availability at completed boundaries;
    - stop resetting cycle/policy state on ordinary handle-count changes;
    - expose package-private diagnostic snapshots only if the benchmark needs them;
- `euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/FragmentControlPolicy.java`
    - own the fixed robust window, confirmation state, guard constants, selector, reset, and
      forced-sampling flag.

Do not change `LatticeEdge`, `UpstreamQueue`, `FragmentConfig`, module descriptors, execution path
methods, request logic, metrics classes, or cache classes unless implementation finds a direct
contract contradiction. Such a contradiction returns to this blueprint before broadening scope.

Test and diagnostic changes are limited to:

- `AbstractExecutorTest.java`;
- `FragmentControlPolicyTest.java`;
- `ControlPlaneFragmentTest.java` and `ControlPlaneFragmentThreadTest.java`;
- a focused `BaseCloneableObject` test if the existing fragment-thread tests cannot observe clone
  wiring without exposing production state;
- `FragmentPathCalibrationBenchmark.java`; and
- `FragmentPathCalibrationBenchmarkTest.java`.

Do not add a generic controller test harness or production metrics solely to inspect the policy. The
benchmark may use a local observed pipeline that retains direct fragment references while calling
the same production connection/start/input sequence as `BaseCloneableObject`.

## Deterministic policy tests

Replace the old service-threshold transition tests with direct tests of the explicit selector and
estimator. Prove at minimum:

```text
registeredWorkers <= 0
    -> DIRECT

liveHandles >= registeredWorkers
    -> DIRECT for no history, cheap, guard-band, and expensive values

liveHandles < registeredWorkers + history < 32
    -> DIRECT

liveHandles < registeredWorkers + estimate == 90
    -> DIRECT

liveHandles < registeredWorkers + estimate == 95
    -> STAGED

liveHandles < registeredWorkers + 90 < estimate < 95
    -> retain DIRECT when DIRECT is settled
    -> retain STAGED when STAGED is settled
```

Also prove:

- the first 31 positive samples do not make history selectable;
- the 32nd sample publishes the exact second minimum for cheap/guard data;
- one expensive window cannot publish an expensive estimate;
- two consecutive expensive windows publish it and a non-expensive window clears confirmation;
- zero/negative samples do not change count, window, or estimate;
- count saturation cannot wrap to insufficient history;
- reset clears the estimator and restores DIRECT;
- service EWMA still controls batch-size calculation but cannot change mode;
- sufficient availability changes STAGED to DIRECT at `completeBatch`;
- insufficient availability can reuse retained expensive history and change DIRECT to STAGED;
- an in-progress batch cannot call the selector or change mode;
- guard-band samples cannot oscillate the selected mode;
- ordinary source-count changes do not call full state reset;
- trial reset clears policy history on the owner thread; and
- forced mode bypasses selection with sampling disabled or explicitly enabled.

Extend executor tests to prove production recorder attachment before `input`, exact 256-call
cadence, one timer pair with diagnostic and production fan-out, late/duplicate attachment rejection,
success-only publication, error/cancellation discard, and retained countdown phase across policy
trial reset.

Use deterministic bounded sources, latches, or scripted clocks for batch-boundary tests. Do not use
arbitrary sleeps. Preserve existing local-cache-first, ordered stop, request, drain, reset, close,
and frame-lifecycle coverage.

## Forced-path and sensor regression

Use commit `697893d` as the clean pre-integration reference. Keep the existing
`workCostDecision` forced-path fixture and standard sampling-disabled lease.

Rerun only rounds 24 and 96 for DIRECT and STAGED under one- and two-handle shapes. The forced paths
pass when:

- winner ordering matches the completed corrected surface;
- mean throughput remains within five percent of the retained result or the JMH 99.9 percent
  intervals overlap;
- both workers remain productive; and
- no discrete fork regime appears.

For production-sensor overhead, compare the same candidate build with forced mode and batch 32:

```text
production estimator sampling disabled
production estimator sampling explicitly enabled
```

Use rounds 24 for plentiful DIRECT and scarce STAGED, three forks, three 3-second warmups, and five
5-second measurements. Predeclare the Phase 6 limits unchanged:

- median throughput loss at most 1 percent;
- enabled lowest-fork score at least 98 percent of the disabled lowest-fork score; and
- participation remains in the corrected balanced regime.

The enabled form must include the timer, recorder callback, robust-window arithmetic, and
completed-batch selector input plumbing while retaining the forced mode. One unchanged repeat is
permitted if scheduling noise makes the result inconclusive. Do not change cadence, estimator, or
bounds between runs.

## Normal-policy benchmark

Extend `FragmentPathCalibrationBenchmark` with a normal-policy state that installs no forced
override. Reuse the real fragment paths, two same-kind workers, natural handles, fixed completion
windows, routing, worker CPUs, synthetic work, and a maximum batch size of 32. The fixed cap keeps
normal and forced winner comparisons on the mapped batch-32 surface without varying batch size.

Run these resolved rows:

| Availability          | Rounds | Expected settled mode |
|-----------------------|-------:|-----------------------|
| 2 handles / 2 workers |     24 | DIRECT                |
| 2 handles / 2 workers |     96 | DIRECT                |
| 1 handle / 2 workers  |     24 | DIRECT                |
| 1 handle / 2 workers  |     96 | STAGED                |

Add one diagnostic-only insufficient-handle row at rounds 88. Linear interpolation of the retained
synthetic executor work places its sensor estimate inside the 90-95 ns guard band. Predeclare the
point; do not search for another point after results are known. It passes only if the steady
production estimate lies inside the guard band and a startup DIRECT policy remains DIRECT. It makes
no throughput-winner claim.

For each fork retain:

- JMH throughput and error;
- per-worker completions, fractions, dominance, and effective lanes where meaningful;
- live handle and registered worker counts;
- body-cost history count and robust estimate per worker;
- selected mode at measurement boundaries; and
- request/direct-pull evidence already available from the benchmark recorder.

The benchmark may retain direct references to diagnostic fragment clones through a local
`ObservedPipeline` so it can take package-private snapshots at iteration boundaries. That wrapper
must call the same executor-body connection, start, input, drain, reset, and close sequence as
`BaseCloneableObject`; it must not implement a second selector or body estimator.

After warmup, every resolved measurement interval must remain in the expected mode. Every retained
80-round production estimate collected by the fixed sensor-validation controls must be at or below
90 ns, and every 96-round estimate must be at or above 95 ns. This is an aggregation check, not a
new surface sweep.

Compare each resolved normal row with its matching forced-path winner at batch 32. Predeclare:

- normal median throughput no more than 2 percent below the forced winner median;
- normal lowest-fork score at least 97 percent of the forced winner lowest fork; and
- no worker disappearance, new fork-level regime, or unexpected winner reversal.

The guard-band row is checked only for stable selection, body estimate, participation, and absence
of unexplained regimes.

## Dynamic cheap-expensive-cheap validation

Add one bounded diagnostic sequence with one handle, two workers, real normal selection, and one
long-lived cloned pipeline:

```text
rounds 24 -> rounds 96 -> rounds 24
```

Change only the deterministic executor work body at phase boundaries. Do not install a forced mode,
probe the alternative path, recreate the fragment, reset policy history, or change source handles.
Diagnostic control of the synthetic work value may use benchmark-owned setup state; keep it out of
production configuration.

Record for each phase:

- starting and ending mode;
- valid sample-count delta;
- completed-frame delta and elapsed time to the mode transition;
- smoothed body cost at each completed batch boundary that changes mode; and
- all observed mode transitions.

The sequence passes when:

- startup remains DIRECT until one 32-sample window exists;
- stable cheap work settles DIRECT;
- clearly expensive work reaches STAGED after two confirmed windows;
- the final cheap phase returns to DIRECT after one completed window;
- each productive-lane transition remains within one 1,048,576-frame completion window;
- each stable phase produces at most its one expected transition; and
- crossing the 90-95 ns guard band does not cause repeated flips.

The benchmark evaluates the most productive owner-local policy in each phase. With one shared
handle, an idle lane cannot be forced to acquire samples without forbidden coordination; its stale
mode is not a failed active-path response. Retain completed work and valid samples as evidence.

## Correctness and compatibility gates

Implementation must preserve:

- zero allocation in productive execution after setup;
- one timer pair per 256 eligible executor calls, never per frame;
- owner-only policy and estimator state;
- existing cache producer/consumer ownership and clear handoff;
- pull-driven routing and ordered-frame stop behavior;
- pressure caps and request watermarks;
- finalization and cancellation ordering;
- topology drain/remap ordering;
- benchmark trial reset semantics; and
- forced DIRECT/STAGED and Phase 6 raw-sensor compatibility.

No new atomic, volatile, VarHandle, lock, registry access, logging, or formatting belongs in the
sampled executor callback or completed-batch selector. Existing live-handle and worker counters keep
their established acquire/atomic semantics. No module export is required.

## Contradiction and stop rules

Stop implementation and return to this design before changing constants or adding conditions if:

- the clone pair cannot attach the callback before executor publication;
- normal body timing requires per-frame clocks, shared aggregation, or a recorder lookup;
- a steady 80-round production estimate exceeds 90 ns or a steady 96-round estimate is below 95 ns
  across the declared fixtures;
- DIRECT/STAGED or one-/two-handle state materially shifts identical production estimates;
- a mode changes before its active batch completes;
- ordinary availability changes require clearing caches or estimator history for correctness;
- sensor/selector integration violates the overhead gates;
- the normal tree contradicts a resolved forced winner;
- the dynamic sequence exceeds its response bound or oscillates under stable work; or
- a worker disappears or a new discrete fork regime appears.

Investigate contradictions in this order:

1. production recorder wiring or aggregation;
2. lifecycle/reset behavior;
3. availability count semantics already present at the boundary;
4. another correctness defect; and
5. a legitimate missing physical branch.

Fix a proven defect narrowly and rerun only affected evidence. Do not tune the 90/95 bounds or
sample cadence around unexplained behavior. A readiness contradiction is recorded for the next
discovery phase; it is not implemented here.

## Implementation sequence

1. Refactor the accepted executor timing seam for setup-only production attachment while preserving
   the Phase 6 diagnostic API and tests.
2. Add the owner-local robust estimator and pure selector to `FragmentControlPolicy`; replace old
   service-time mode hysteresis without changing batch sizing.
3. Wire cloned fragment/executor pairs in `BaseCloneableObject` before start and update
   `ControlPlaneFragment` only at completed-batch and ordinary upstream-count boundaries.
4. Add deterministic policy, executor, clone-wiring, safe-boundary, and lifecycle tests.
5. Extend the calibration benchmark with production-estimator forced controls, the five bounded
   normal rows, and the one dynamic sequence.
6. Run forced regression, signal/overhead gates, normal resolved leaves, guard stability, and
   dynamic response. Investigate any contradiction before changing policy.
7. Run focused modules, the full repository build, stale-reference searches, `git diff --check`,
   ASCII validation, and final status review. Append exact commands and evidence to this blueprint.

## Acceptance criteria

This phase is complete only when implementation evidence shows:

1. the production sampler preserves the Phase 6 body-only boundary and fixed cadence;
2. the 32-sample second-minimum windows and two-window expensive confirmation produce a stable,
   tier- and availability-neutral selector input;
3. fixed 90 ns and 95 ns bounds preserve all retained 80-/96-round classifications;
4. sufficient handles always select DIRECT;
5. insufficient handles select DIRECT for startup/cheap work, retain mode in the guard band, and
   select STAGED for clearly expensive work;
6. all mode changes occur at completed-batch boundaries;
7. lifecycle retention/reset behavior matches the table above;
8. standard forced modes bypass selection and sampling, while explicit forced sampling still holds
   its forced mode;
9. underlying forced paths, participation, and body signal do not regress materially;
10. four resolved normal rows select and perform near their forced winners;
11. the predeclared rounds-88 guard row remains stable without a winner claim;
12. cheap-expensive-cheap adaptation is bounded and non-oscillatory;
13. focused Core/benchmark tests, `mise exec -- gradle build`, and `git diff --check` pass; and
14. completion notes retain fork-level results, raw-output locations, exact commands, and any
    environmental limits.

## Future extension seam

Future experimentally proven branches are added as explicit conditions in the one package-private
selector. The executor callback, estimator ownership, safe-boundary call, and execution paths must
not require restructuring.

Exactly one unresolved discovery leaf follows this phase:

```text
Can live handle count overstate currently productive independent pull opportunities
under source behavior not represented by the repeating-source fixtures?
```

Do not investigate or encode that leaf during Phase 7. Availability readiness is the next bounded
discovery phase only after this production tree passes all integration gates.

## Completion record: 2026-08-11

The production tree and its diagnostic controls are implemented. The original arithmetic-mean/EWMA
draft was rejected by the rounds-88 stability gate before acceptance. The authorized bounded
aggregation comparison retained the same physical branches, 90/95 ns guard, and 256-call sensor
cadence:

| Candidate                                                           | Result                                                      |
|---------------------------------------------------------------------|-------------------------------------------------------------|
| one-eighth and one-twelfth EWMA                                     | rejected: stable guard workload entered STAGED              |
| rolling-sample confirmation counts                                  | rejected: correlated timing stalls counted repeatedly       |
| 32-sample mean                                                      | rejected: additive preemption bursts dominated the estimate |
| rolling medians of 5 and 9                                          | rejected: guard excursions remained                         |
| rolling 9-sample lower quartile/minimum                             | rejected: guard or cold sparse-worker instability           |
| rolling 32-sample second minimum                                    | rejected: isolated exact-95 ns guard excursions remained    |
| non-overlapping 32-sample second minimum plus two expensive windows | accepted                                                    |

The accepted estimator is the simplest candidate that passed every gate. It performs one primitive
array scan per 8,192 eligible executor calls, allocates nothing after setup, immediately publishes
cheap/guard windows, and requires two distinct expensive windows. It does not alter the decision
tree or add coordination.

Implemented production path:

- `AbstractExecutor` retains the Phase 6 body-only interval, samples every 256 eligible calls, and
  fans one successful timed call to diagnostic and production recorders with one timer pair.
- `BaseCloneableObject` attaches each clone's recorder before start/input publication.
- `FragmentControlPolicy` owns the robust window, expensive confirmation, 90/95 ns guard, explicit
  selector, forced bypass, and owner-local reset state.
- `ControlPlaneFragment` reads live handles and registered workers only at completed batches and
  does not truncate a batch or clear history on ordinary availability changes.
- Forced DIRECT/STAGED remains separate; raw body timing is still explicitly opt-in in forced mode.

The full three-fork rounds-88 run used three 3-second warmups and five 5-second measurements. Every
warmup, measurement, and final worker snapshot remained DIRECT. Throughput was:

```text
SCARCE_88 normal: 16,461,445.889 +/- 221,417.767 frames/s
```

Three-fork representative resolved-leaf validation selected the expected mode in every retained
snapshot and showed no worker disappearance:

```text
PLENTIFUL_24 -> DIRECT: 59,212,230.779 frames/s
PLENTIFUL_96 -> DIRECT: 20,423,074.990 frames/s
SCARCE_24    -> DIRECT: 34,794,728.704 frames/s
SCARCE_96    -> STAGED: 16,247,445.479 frames/s
```

The three-fork dynamic scarce sequence completed hundreds of repetitions without a response-bound
failure. Across forks, startup DIRECT needed at most 32 productive-lane samples, cheap-to-expensive
needed at most 94, and expensive-to-cheap needed at most 72. Maximum completed-frame deltas were
15,825, 47,350, and 37,004 respectively, below the 1,048,576-frame bound. A nonproductive lane may
retain its old local mode until it receives new samples; no cross-core work is added to force an
idle lane to converge.

The full same-build overhead controls passed the predeclared gates:

| Forced control              | Disabled median | Enabled median | Change | Lowest-fork ratio |
|-----------------------------|----------------:|---------------:|-------:|------------------:|
| plentiful DIRECT, rounds 24 |  59,737,723.910 | 59,856,049.644 | +0.20% |           100.95% |
| scarce STAGED, rounds 24    |  31,246,083.426 | 31,148,631.210 | -0.31% |            99.52% |

Both are within the one-percent median-loss gate and above the 98-percent lowest-fork gate; worker
participation remained balanced. Generated JMH JSON is retained under
`benchmarks/build/reports/phase7-*.json` and is not source-controlled.

Focused Core and benchmark tests cover cadence, fan-out, error/cancellation discard, attachment
lifecycle, robust arithmetic, confirmation reset, exact selector boundaries, guard retention, forced
modes, owner-thread trial reset, safe batch boundaries, clone wiring, benchmark cleanup, and dynamic
response accounting. The repository checks completed with:

```text
mise exec -- gradle :euhedral-core:test :benchmarks:test
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck
mise exec -- gradle build
git diff --check
```

No production execution path, module descriptor, public constructor, availability semantics, or
future branch was changed. The only unresolved next discovery leaf remains whether live handles can
overstate currently productive independent pull opportunities outside the repeating-source fixtures.
