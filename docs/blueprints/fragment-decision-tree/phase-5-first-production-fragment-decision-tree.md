# Phase 5: First Production Fragment Decision Tree

Status: planned; implementation is gated on the two input validations below

Plan:
[`phase-5-first-production-fragment-decision-tree.md`](../../plans/fragment-decision-tree/phase-5-first-production-fragment-decision-tree.md)

Blueprint intensity: maximum

Implementation intensity: high after validation

## Decision and scope

Implement the first production DIRECT/STAGED tree only after validating its two physical inputs.
The tree selects the existing paths; it does not change their operation order, rediscover the broad
surface, or introduce an adaptive controller.

The corrected fixed-batch-32 evidence supports only this structure:

```text
independent pull availability sufficient?
    |
    +-- yes -> DIRECT
    |
    +-- no
            |
            +-- clearly expensive executor work -> STAGED
            |
            +-- otherwise -> DIRECT
```

The otherwise leaf includes startup, insufficient work history, cheap work, and the unresolved
42.563-70.689 ns body-cost region. No production claim is made beyond the measured 0.353-449.914 ns
range or outside the corrected source topology.

## Input A: independent pull availability

### Selected existing state

Use these existing root-graph values:

- opportunities: `UpstreamQueue.getTrueUpstreamCount()`, which acquire-reads
  `LatticeEdge.UPSTREAM_COUNT`;
- active execution workers: `LatticeEdge.getThreadCount()`, backed by `THREAD_COUNT`.

Compare the integer counts directly:

```text
sufficient = activeWorkers > 0 && liveUpstreamHandles >= activeWorkers
```

Do not create a floating-point ratio. Do not use `cachedUpCount`: the decision occurs once at a
batch boundary, where one acquire read of the true live-handle count is preferable to a potentially
stale cache. Each `UpstreamInterceptor` contributes one live handle with one independent acquisition
lock and is published into every registered active partition, so handle count is the existing state
that corresponds to the fixture's independent pull opportunities.

The existing `THREAD_COUNT` update point does not yet match that meaning: `UpstreamQueue.get`
increments it when any thread creates a thread-local queue, before `LatticeEdge.register` marks an
active partition. Correct that existing state narrowly:

- remove the counter update and counter argument from `UpstreamQueue.get`;
- in root `LatticeEdge.register`, increment `THREAD_COUNT` only when an
  `ACTIVE_PARTITIONS.compareAndSet(core, 0, 1)` succeeds; and
- in root `removeThread`, decrement only when the matching
  `ACTIVE_PARTITIONS.compareAndSet(core, 1, 0)` succeeds.

The runtime invariant is one fragment worker per active physical-core partition. The CAS makes
repeated registration/removal idempotent and couples the count to the already-maintained active
partition state. This is a semantic correction to the existing counter, not a new registry or
cross-core controller. If tests show that more than one legitimate fragment worker can own the same
partition, stop this phase; do not add another active-worker system or scan `ACTIVE_PARTITIONS` in
the hot loop.

### Required validation

Extend the existing deterministic calibration fixture without changing its routing or source
behavior. After both workers register and after sources are ingested, retain per fork:

- live handle count;
- registered worker count;
- configured source and worker counts;
- source-handle identities; and
- existing per-worker productive-pull and completion evidence.

The required cases are one repeating source/two workers and two repeating sources/two workers.
They pass only when the runtime reports `1/2` and `2/2`, respectively, and the established handle
recorder confirms that the two-handle case exposes two independently acquired productive handles.
Also prove deterministically that handle completion decrements the numerator, worker removal
decrements the denominator, repeated registration is idempotent, and merely obtaining an
unregistered thread-local queue does not change the denominator. These are count-semantics checks,
not a rerun of the failed-acquisition investigation.

No additional source counts are required unless `1/2` and `2/2` leave the count semantics
ambiguous. Any mismatch is a stop condition for production implementation.

## Input B: tier-neutral executor work cost

### Selected measurement boundary

Add a second owner-local cost estimate; do not repurpose `serviceTimeNs` or the existing execution
latency metric.

`ControlPlaneFragment.accept` is the common boundary after a frame has crossed local cache,
remote cache, or direct upstream acquisition and before it enters the synchronous executor
terminal. For a sampled accepted frame, time only:

```text
start = System.nanoTime()
outputStream.accept(frame)
end = System.nanoTime()
```

Keep the existing in-progress metric updates outside this interval. The interval includes the
common `LatticeHotSource` dispatch, executor liveness check, application executor body, and frame
finalization. It excludes the DIRECT/STAGED request, cache, routing, and handle-acquisition work
that contaminated the existing service estimate. This common dispatch cost is the production
work-cost axis; it is intentionally not described as pure isolated body nanoseconds.

There is no existing multi-frame executor-body entry point. Adding one would change `LatticeSource`
or `AbstractExecutor` contracts for this single branch. Instead, amortize a single-frame timer pair:

- sample the first accepted frame, then one accepted frame every eight completed policy batches;
- retain all cadence, count, and EWMA fields in `FragmentControlPolicy` or the fragment's
  owner-local `CycleState` with plain access;
- apply the existing one-eighth EWMA form to positive finite elapsed samples;
- require eight valid samples before work cost may select STAGED; and
- reset the sample cadence and history on trial/reset lifecycle, not on ordinary mode changes.

This is two timer reads per eight productive batches, not per-frame timing. A batch with no accepted
frame contributes no sample. Startup and fewer than eight samples are insufficient history and
therefore select DIRECT.

Keep the current path-inclusive `serviceTimeNs`, its `recordExecution` input, and the DIRECT/STAGED
target-work batch calculation unchanged. That estimate continues to size batches and publish the
existing latency metric; it must not enter the new path decision.

### Signal calibration and boundary rule

Add a validation-only option to the existing package-private diagnostic override that enables the
new sampler while holding DIRECT or STAGED. The existing two-argument override installs with
sampling disabled, so current forced-path behavior and cost remain unchanged. The option is
captured at construction like the existing immutable mode and batch target; it adds no shared read
to the hot path.

Under both forced modes and both one-handle/two-handle and two-handle/two-worker fixtures, measure
at least rounds `24`, `80`, and `96`, corresponding to isolated body costs 21.566, 70.689, and
84.657 ns on the completed host run. Retain fork-level smoothed dispatch estimates and their JMH
errors alongside throughput and participation.

The signal passes only if:

1. each fixed mode/source curve is monotonic from 24 through 96 rounds;
2. identical work has no material DIRECT/STAGED or source-shape shift;
3. all 24-round forks lie in a separated cheap region;
4. the steady 80-round and 96-round ranges do not overlap across retained forks; and
5. enabling validation sampling does not create a worker-participation regime or change the forced
   winner ordering.

For condition 2, the maximum within-cost spread must be less than one quarter of the distance
between the aggregate 24-round and 96-round estimates. This tests decision usefulness rather than
nanosecond equality.

If the signal passes, choose one `EXPENSIVE_WORK_BOUNDARY_NS` in dispatch-cost units as follows:

1. take the maximum steady fork estimate at 80 rounds;
2. take the minimum steady fork estimate at 96 rounds;
3. choose the midpoint; and
4. round upward to the next whole 5 ns for an understandable conservative constant.

The rounded value must remain strictly above every retained 80-round estimate and no greater than
the minimum retained 96-round estimate. If rounding destroys that property or no gap exists, do
not implement the branch. Return to design for a better body-cost measurement; do not use the old
service estimate, tune per-mode constants, or classify the unresolved interval.

Record the selected numeric boundary and all calibration evidence in this blueprint's completion
notes before production selection is enabled. The constant is a conservative representation of a
resolved region on the measured host, not a universal claim that the isolated body crossover is
exactly that value.

## Explicit production logic

Keep `FragmentControlPolicy` as the small owner-thread controller. Remove the old service-time mode
thresholds and completed-batch transition streak from normal selection. Preserve its miss/park and
batch-size responsibilities.

At a safe boundary, normal mode evaluates exactly:

```text
if activeWorkers > 0 and liveUpstreamHandles >= activeWorkers:
    mode = DIRECT
else if validWorkCostSamples < 8:
    mode = DIRECT
else if executorDispatchCostEwmaNs >= EXPENSIVE_WORK_BOUNDARY_NS:
    mode = STAGED
else:
    mode = DIRECT
```

Use a single boundary and the one-eighth EWMA. Do not retain the old eight-batch mode-transition
streak, add separate enter/exit thresholds, or create another noise controller. The unresolved
region remains on DIRECT because the calibrated boundary must sit above every retained 70.689 ns
sample.

Diagnostic override selection happens first. A forced `DIRECT` or `STAGED` policy never evaluates
the normal tree. Unless the validation-only sampling flag is explicitly set, it also skips the new
timer. Its fixed batch target retains the existing cap and floor.

## Safe-boundary integration

The mode used by an active batch is immutable until that batch completes. Apply availability and
work-cost changes only when `state.completed == 0` before a new batch or in
`FragmentControlPolicy.completeBatch` after the old batch reaches its target.

At a completed boundary:

1. obtain the true live-handle count from the owner `UpstreamQueue`;
2. obtain the registered worker count;
3. finish the current service-time and work-cost observations;
4. select the mode for the next batch; and
5. calculate the next batch size with the existing eligible cap and target-work rule.

The current top-of-cycle response to an upstream-count change broadly calls `CycleState.reset`,
which can discard partial batch accounting and reset mode immediately. Replace that coupling with
an owner-local count update. Ordinary handle-count changes must not reset `completed`, batch size,
service history, work-cost history, or mode. If no batch is in progress, the new counts may be
applied before the next frame; otherwise they remain inputs for the next completed boundary.

Trial reset, owner-cache reset, close, and clone construction keep their existing lifecycle. A true
trial reset clears both estimates and starts DIRECT at batch two. Topology removal continues through
shard drain; no new lifecycle message or cross-core publication is introduced.

All new policy and sampling fields are accessed only by the pinned fragment owner and use plain
access. The existing handle and worker counters retain their current acquire/atomic semantics. No
VarHandle strengthening, lock, allocation, or shared write is added to per-frame execution.

## Implementation footprint

Production changes are limited to:

- `FragmentControlPolicy.java`: separate dispatch-cost EWMA/history/cadence, explicit selector,
  conservative boundary, safe-boundary arguments, and validation-only diagnostic sampling flag;
- `ControlPlaneFragment.java`: sampled common-dispatch timer, true availability reads at boundaries,
  and removal of broad state reset on ordinary upstream-count changes;
- `LatticeEdge.java` and `UpstreamQueue.java`: align the existing worker counter with active
  partition registration and removal;
- `ExecutionMetrics.java` and `MetricsAggregator.java`: add one additive
  `.execution.dispatchCost` summary. Report a smoothed estimate only at the first completed-batch
  boundary after a new valid sample, and never read the meter for selection; and
- no change to `AbstractExecutor`, `FragmentActionPicker`, shard topology, queue acquisition,
  routing, frame lifecycle, or module exports.

The new summary is the calibration and operational observability seam. It is additive when a
registry is configured and absent when no registry exists. Do not add a general policy snapshot API
solely for JMH.

Test and benchmark changes are limited to:

- `FragmentControlPolicyTest.java`;
- `ControlPlaneFragmentThreadTest.java`;
- `LatticeEdgeTest.java` for exact count lifecycle semantics if current coverage is insufficient;
- `FragmentPathCalibrationBenchmark.java`; and
- `FragmentPathCalibrationBenchmarkTest.java`.

## Deterministic tests

Policy tests use supplied elapsed/count values and availability counts; they do not assert wall
clock timing. Prove at minimum:

- sufficient handles select DIRECT with no history, cheap history, and expensive history;
- insufficient handles plus eight clearly cheap samples select DIRECT;
- insufficient handles plus eight clearly expensive samples select STAGED;
- fewer than eight valid samples select DIRECT;
- zero/negative elapsed values do not create history;
- the one-eighth EWMA and eight-batch sampling cadence are exact;
- changing availability or cost while a batch is in progress does not change its mode;
- the next safe boundary reflects the latest availability and work-cost input;
- reset clears history and restores DIRECT;
- standard forced modes bypass selection and sampling;
- validation-enabled forced modes retain their forced mode while collecting samples; and
- batch sizing continues to use the separate service estimate and eligible cap.

Fragment/thread tests prove observable integration:

- one source/two workers exposes one opportunity and two registered workers;
- two sources/two workers exposes two opportunities and two registered workers;
- source completion and worker close update their respective counts;
- normal scarce cheap work directly pulls;
- normal scarce expensive work begins DIRECT and requests first only after sufficient sampled
  history at a completed batch boundary;
- adding a second opportunity moves an expensive workload to DIRECT only at the next safe boundary;
- changing executor work cost crosses the branch without losing or duplicating a frame; and
- local-cache-first, ordered fallback, reset, drain, and close behavior remain intact.

The tests must not use sleeps to prove batch ordering. Use latches or bounded sources that pause
immediately before a known batch boundary.

## Benchmark validation

### Forced-path regression

Keep the existing `workCostDecision` forced DIRECT/STAGED behavior and its standard diagnostic
lease. With normal sampling disabled by that lease, rerun only representative anchors
`0,24,96,512` for both source shapes and both paths under the retained three-fork JMH protocol.

The rows pass when:

- their winner ordering matches the completed Phase 4 surface;
- throughput remains within five percent of the corresponding retained Phase 4 mean or the 99.9%
  intervals overlap;
- both workers remain productive; and
- no discrete fork regime appears.

Do not rerun every transition point unless an anchor contradicts Phase 4.

### Normal-tree benchmark

Add one bounded normal-policy JMH state using the same real graph, two workers, natural handles,
batch cap 32, completion windows, routing, and work body. It does not install a forced mode. Run
only rounds `24` and `96` with one and two sources:

| Availability | Rounds | Expected normal leaf |
|--------------|-------:|----------------------|
| two handles / two workers | 24 | DIRECT |
| two handles / two workers | 96 | DIRECT |
| one handle / two workers  | 24 | DIRECT |
| one handle / two workers  | 96 | STAGED |

Retain per fork:

- throughput and JMH error;
- worker fractions and dominance;
- live handles and registered workers;
- sampled dispatch-cost estimate and valid-sample count; and
- request/direct-pull counts sufficient to identify the selected path without adding production
  labels.

After JMH warmup, every measurement iteration must remain in the expected leaf. Its mean throughput
must be no more than five percent below the matching forced-path winner unless the 99.9% intervals
overlap. A normal row inside the 48-80-round transition is intentionally absent.

If the normal benchmark exposes a discrete regime, worker loss, a new winner reversal, non-monotonic
cost estimate, repeated mode crossing, or a material forced-path regression, stop and explain the
physical cause. Do not tune the constant or add a branch around unexplained behavior.

## Acceptance criteria

This blueprint is complete only after implementation records evidence that:

1. existing live-handle and registered-worker state distinguishes `1/2` from `2/2` opportunities;
2. the sampled common executor-dispatch cost is monotonic, tier-neutral enough, and separates the
   retained 70.689 and 84.657 ns regions;
3. the numeric conservative boundary was derived by the predeclared rule;
4. normal startup and insufficient history are DIRECT;
5. the four resolved normal-policy rows select their expected leaf at safe boundaries;
6. standard forced DIRECT/STAGED modes bypass the tree and do not regress materially;
7. the existing path-inclusive service metric and batch-size control retain their meanings;
8. no production path, frame, routing, acquisition, cache, or topology contract changed;
9. focused tests, Core and benchmark builds, full `mise exec -- gradle build`, and
   `git diff --check` pass; and
10. completion notes record any environmental limit and the final intended file set.

Failure of input validation ends implementation before the production selector is enabled. Partial
measurement code should not be left active as an unproven policy input.

## Future extension seam

Keep one package-private selector method in `FragmentControlPolicy` whose inputs are only live
handles, registered workers, work-cost history count, and smoothed dispatch cost. Later evidence may
split a leaf by adding one explicit conditional to that method; callers and execution paths should
not require restructuring.

Exactly one unresolved production leaf follows this phase: whether the sufficient-handle DIRECT
leaf remains valid when live handle count overstates currently productive pull opportunities. A
future bounded experiment may compare live handles with ready/productive opportunities while
holding one cheap and one expensive cost fixed. Batch size, hardware topology, and the scarcity
transition remain out of scope until that availability semantic is tested.
