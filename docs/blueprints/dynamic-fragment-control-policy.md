# Dynamic Fragment Control Policy Blueprint

## Status

- Date: 2026-08-10
- Source plan: [`dynamic-fragment-control-policy.md`](../plans/dynamic-fragment-control-policy.md)
- Blueprint intensity: maximum
- Implementation intensity: maximum
- Scope: one bounded Core implementation plus focused tests and one diagnostic benchmark

The implementation intensity is raised from the plan's provisional high to maximum because the
change publishes cross-core observations, participates in shard topology and reset lifecycle, and
uses variance in production decisions. This does not broaden the implementation. The design is
intentionally limited to the existing fragment boundary, one socket-local shared decision, and one
owner-local batch search.

## Decision Summary

Replace the service-time thresholds and target-work calculations in
[`FragmentControlPolicy`](../../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/FragmentControlPolicy.java)
with two small probe controllers:

- one socket-wide controller chooses `DIRECT` or `STAGED`; and
- one owner-thread controller per mode chooses a batch size by probing the adjacent half or double.

Each fragment measures complete active loop iterations. It publishes one compact observation after
10 ms of active time. The lowest active worker CPU on the socket is the sole writer of the shared
controller state. Other workers only publish their own padded slot and acquire-read the shared
directive at a safe batch boundary.

This version adds no controller thread, locks, public configuration, source classification,
per-source history, training hook, or change to routing, queues, frame semantics, request
watermarks, or park policy. `FragmentActionPicker` and benchmark mode remain outside this work.

## Current Contracts to Preserve

The current implementation establishes the following boundaries:

- [`ControlPlaneFragment`](../../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java)
  always tries its owner-local cache first. `DIRECT` then tries the shared remote cache and a direct
  upstream pull, requesting only after a miss. `STAGED` requests first, then drains local, remote,
  and direct paths. The new controller selects between these two existing paths; it does not alter
  their order.
- Direct upstream pulls stop at ordered frames. Ordered work therefore continues through the
  established request/cache path in either mode.
- [`UpstreamQueue`](../../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/UpstreamQueue.java)
  serializes each upstream handle. A mode decision must not add another caller or retain a handle
  beyond the existing call.
- [`ControlPlaneCache`](../../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneCache.java)
  has an MPSC owner-consumed local cache. Its reset remains an acknowledged owner-thread action.
- The pressure-derived eligible batch cap remains `min(config.maxBatchSize(), frameQuota)` with the
  existing floor of two. A controller target never bypasses that cap.
- [`ControlPlaneShard`](../../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneShard.java)
  owns core membership changes and trial reset. It already drains before remapping, clears trial
  state while ingest is frozen, and closes removed clones.
- [`GlobalState`](../../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/GlobalState.java)
  is already bounded JVM-wide socket state backed by padded atomics. The JVM-wide lattice singleton
  makes it the narrowest place to share a socket decision without changing clone APIs.
- Existing execution latency and throughput meters keep their current meaning. Controller metrics
  are additive.

The four workload-tuned constants `DIRECT_TARGET_BATCH_WORK_NS`,
`STAGED_TARGET_BATCH_WORK_NS`, `STAGED_THRESHOLD_NS`, and `DIRECT_THRESHOLD_NS` no longer influence
production behavior. `SPIN_MISSES` and its current park behavior are unchanged.

## Observation Contract

### Active interval

An active interval starts after the top-of-loop idle check and ends after that iteration's cache,
request, pull, execution, and active-miss spin or park work. It includes iterations that complete no
frames while a source or cache is still active. Time inside `idleSpin`, when the fragment has no
source and no local or upstream cached work, is excluded.

The fragment accumulates:

```text
active nanoseconds += active iteration elapsed time
completed frames   += frames executed by that iteration
```

When active time reaches 10,000,000 ns, it closes a local observation, computes
`completed * 1_000_000_000 / activeNanoseconds`, and resets the two accumulators. Overshoot belongs
to that observation; it is not carried into the next one. Existing inner execution timing remains
in place for the existing latency metric and is not used by the controller.

Entering the fully idle path discards a partial observation, marks the shared slot inactive, and
cancels an unfinished batch probe. Idle time and isolated sparse arrivals therefore do not
accumulate into a later calibration window. Idle does not erase a settled mode or learned batch
size.

### Atomic observation format

Each active primary worker CPU has one slot in a `PaddedAtomicLongArray` owned by its socket
controller. A release store publishes this allocation-free 64-bit value:

```text
63                         48 47 46                       24 23                         0
+----------------------------+--+---------------------------+----------------------------+
| non-zero sequence (16 bits)|M | active microseconds (23)  | completed frames (24)      |
+----------------------------+--+---------------------------+----------------------------+
```

- `M` is the mode actually used for the observation: zero for `DIRECT`, one for `STAGED`.
- Sequence zero is reserved. The owner increments modulo 16 bits and skips zero.
- Active nanoseconds are rounded up to microseconds. A value that exceeds 23 bits or a completion
  count that exceeds 24 bits is invalidated instead of truncated into a misleading rate.
- The all-zero value means inactive or no valid observation.

The compact value makes frames, elapsed time, mode, and freshness one coherent acquire/release
publication. It avoids a seqlock, object allocation, or cross-field snapshot protocol. Missing one
complete sequence wrap merely delays one socket sample; it cannot select a mode from mixed data.

### Socket observation

The socket controller records the primary CPU used by each active physical core. The leader accepts
one socket observation only when every recorded CPU has published a non-zero, previously unconsumed
sequence in the currently commanded mode. On acceptance it advances all last-seen sequences and
computes:

```text
socket score = sum(completedFrames * 1_000_000 / activeMicroseconds)
```

This is the sum of each worker's active frames per second. It rewards work spread across siblings
and charges active misses, while excluded full-idle time cannot improve or reduce the score.

The observation is valid only when aggregate completions are at least
`max(8, activeWorkerCount)`. Invalid, inactive, stale, wrong-mode, non-finite, or non-positive-time
input does not advance controller state. No partial-worker estimate is substituted.

## Statistical Rule

Both controllers use allocation-free Welford accumulators containing only `count`, `mean`, and
`M2`. For two complete samples `A` and `B`:

```text
variance(X) = M2(X) / (count(X) - 1)
uncertainty = 2 * sqrt(variance(A) / count(A) + variance(B) / count(B))
practical   = 0.01 * max(mean(A), mean(B))
margin      = max(uncertainty, practical)
delta       = mean(B) - mean(A)
```

The result is:

- `B_BETTER` when `delta > margin`;
- `A_BETTER` when `delta < -margin`;
- `EQUIVALENT` when `uncertainty <= practical` and `abs(delta) <= practical`; or
- `INCONCLUSIVE` otherwise.

This is a bounded variance-aware comparison, not a claim of a formal t-test. No controller extends
a probe to chase significance. The socket mode retains its current choice for both `EQUIVALENT`
and `INCONCLUSIVE`. The batch controller prefers the smaller size only for `EQUIVALENT`; it retains
the current size for `INCONCLUSIVE`.

## Socket Mode Controller

### Ownership

`GlobalState` holds one current socket-controller reference per known socket in padded atomic
references. Replacing that reference creates a new controller epoch. A controller contains an
immutable sorted primary-CPU list, a padded observation array, a padded directive, and plain state
owned only by the lowest CPU in that list.

The leader advances the state machine after publishing its own local observation. It never waits or
retries. If another member has not published a fresh matching observation, it returns immediately.
Every fragment acquire-reads the current controller at an observation boundary and the directive at
a completed batch boundary. A standalone fragment that has no shard-published controller uses a
private one-member controller; this is the same adaptive implementation, not the old static policy.

### State machine

The shared controller has four phases:

| Phase | Mode in use | Exit |
|---|---|---|
| `BASELINE` | Settled mode | After 8 valid socket observations, command the other mode and enter `WARMUP`. |
| `WARMUP` | Target mode | Ignore 2 fresh all-member observations in that mode, then enter its recorded next phase. |
| `PROBE` | Alternative mode | After 8 observations, compare with the baseline and enter `HOLD` in the winner. |
| `HOLD` | Settled mode | Re-enter `BASELINE` after material drift or 256 valid observations. |

Construction, topology replacement, and trial reset start in `BASELINE`, settled `DIRECT`, with
zero observations and zero committed mode changes. Short work that cannot provide eight valid
baseline observations finishes in direct mode without ever paying for an alternative-mode probe.

After a probe:

- a significantly better alternative becomes settled and increments the committed mode-change
  count once;
- otherwise the prior mode remains settled;
- returning from a losing probe goes through `WARMUP` before `HOLD`, so transition residue is not
  used for drift; and
- an adopted alternative also discards two observations before hold accounting begins.

`HOLD` compares each disjoint block of 8 observations with the last settled winning sample. Drift
is material only when the means differ significantly and the absolute difference exceeds four
times that comparison's margin. Material drift restarts `BASELINE` in the current settled mode.
The 256-observation limit forces a periodic recheck even without detected drift.

An alternative-mode `WARMUP` plus `PROBE` is bounded to one second of monotonic wall time. Failure
to collect matching all-member observations by then aborts the probe and restores the prior mode.
Entering full socket idle also aborts a probe. `BASELINE` and `HOLD` have no wall deadline because
they do not move short or sparse work away from its settled path.

Directives are applied only when the fragment has completed its current batch, or when no partial
batch exists. A local observation is reset when the applied mode changes, and its published mode bit
prevents a transition window from entering either comparison.

## Owner-Local Batch Controller

Each `FragmentControlPolicy` retains independent search state for `DIRECT` and `STAGED`. Direct
starts at two. Staged also starts at two and begins learning only when first used. Inactive mode
state is retained across probes.

The search uses the same local 10 ms score and statistical rule with 4 baseline samples, 1 discarded
warmup sample, and 4 candidate samples:

1. Measure the selected size.
2. Probe `min(cap, selected * 2)` first.
3. If the larger size wins, adopt it and continue upward.
4. If it does not win, probe `max(2, ceil(selected / 2))` when distinct.
5. Adopt the smaller candidate when it wins or is equivalent.
6. After both distinct neighbors fail to replace the selected size, hold it for 128 valid local
   observations, then restart at step 1.

Arithmetic doubles with saturation. A candidate is applied only at a completed batch boundary and
its warmup begins only after application. Idle or a mode change cancels an unfinished candidate and
restores that mode's selected size.

The learned selected size is not overwritten by temporary pressure. The effective size is
`min(selectedOrCandidate, eligibleCap)` with a floor of two. A falling cap is honored at the next
safe batch boundary without truncating an in-progress batch. When the cap recovers, the retained
size becomes eligible again. A cap change that creates or removes a distinct neighbor cancels the
current comparison and restarts its baseline; it does not synthesize a result.

Mode and batch probes are not globally scheduled around one another. Their fixed windows and
variance margin are the entire first-version protection against interference. If this makes the
mode comparison unreliable in benchmarks, implementation returns to design; it must not add probe
barriers, a controller thread, or another coordination layer in the same pass.

## Shared-State Lifecycle and Memory Semantics

`ControlPlaneShard` performs only these controller lifecycle calls:

- Initial start and every effective core-topology change publish a new controller built from the
  first effective CPU of each active core. Publication occurs after ingest enters drain and before
  new clones start.
- `resetForNextTrial` publishes a fresh controller after owner-thread fragment resets complete and
  before clones and the distributor leave drain mode.
- Socket shutdown and close deactivate the current controller. Old fragments may finish against
  the old object, but it is no longer discoverable and cannot affect a later epoch.

Normal source addition/removal and idle do not replace the controller. Observation reset plus the
`HOLD` drift rule handles workload changes without turning source churn into topology churn.

The happens-before rules are:

- Shard lifecycle uses `PaddedAtomicReference.setRelease`; fragments use `getAcquire`. The immutable
  membership and initial direct directive are therefore visible before any fragment publishes.
- A fragment owns its observation sequence and uses one release store for the packed observation.
  The leader uses acquire loads before unpacking it.
- The leader is the sole writer of phase, Welford accumulators, last-seen sequences, and settled
  choice, so those fields use plain access.
- The leader release-publishes a directive after updating its plain state. Fragments acquire-read
  it only at batch boundaries.
- Fragment-local mode, batch search, partial observation, and miss streak use plain access because
  the pinned fragment thread is their only owner.
- The fragment release-publishes its effective batch size at a batch boundary for the optional
  gauge. Metrics acquire-read that value plus the controller's settled mode and change count.
  Metrics never participate in a decision.

No stronger access mode is introduced per frame. Shared stores occur once per local observation,
and directive reads occur once per completed batch, not on every frame.

## Metrics

Keep the existing latency, throughput, in-progress, cache backlog, and cap-factor meters unchanged.
Add these per-core gauges through the existing optional registry path:

| Suffix | Value |
|---|---|
| `.execution.control.mode` | Settled socket mode: `0` direct, `1` staged. Temporary probes do not change this gauge. |
| `.execution.control.batchSize` | Fragment's current effective batch size. |
| `.execution.control.modeChanges` | Committed socket mode changes in the current controller epoch. |

The gauges retain the existing `core` tag. Mode-change values are intentionally identical for cores
on one socket. Topology or trial reset may return the gauge to zero; it is a gauge, not a monotonic
counter. Do not add phase, raw sample, variance, candidate, or per-source meters in this pass.

## Deterministic Fixtures

Policy tests use explicit elapsed values and packed observations; they do not use sleeps,
`System.nanoTime`, affinity, or statistical randomness.

### Pure controller fixtures

- Initial and reset state is settled direct, batch two, no samples, and no changes.
- Eight direct socket observations followed by two discarded and eight slower staged observations
  retain direct.
- Direct observations shaped `{160, 0}` and staged observations shaped `{100, 100}` over equal
  active times select staged, proving the score sees sibling distribution rather than only the
  productive fragment.
- A later significant reversal selects direct again. Exactly one committed change is recorded per
  winning comparison.
- Equal zero-variance samples are equivalent; overlapping noisy samples are inconclusive; a clear
  winner outside both margins wins.
- Wrong-mode, repeated-sequence, inactive, overflow, and below-minimum-completion packets do not
  advance a phase.
- Warmup data, including an extreme outlier, never enters baseline, probe, or drift statistics.
- An incomplete probe times out or idles back to the prior settled mode.
- Stable hold data triggers a periodic recheck; material drift triggers an earlier recheck; ordinary
  noise does neither.

### Batch fixtures

- A better double is adopted repeatedly up to the cap.
- A worse double is rejected; an equivalent smaller neighbor is selected; noisy inconclusive data
  retains the current size.
- Direct and staged retain different learned sizes across mode changes.
- A falling cap clamps the effective size without erasing the learned size, and a rising cap exposes
  it again.
- Idle, mode change, or a newly distinct cap neighbor cancels candidate measurements rather than
  manufacturing a result.

### Lifecycle and loop fixtures

- Shard startup publishes the exact primary-CPU membership; rebalance replaces it; trial reset
  creates a fresh direct epoch; shutdown deactivates it.
- A stale controller can receive a late publication without changing the newly published epoch.
- Fragment loop tests force directives through a test controller and retain local-cache-first,
  direct pull, ordered-request fallback, staged request, bounded miss parking, reset, drain, and
  close behavior.
- Active timing tests pass an explicit iteration duration and prove request-only and zero-completion
  active misses contribute, while an idle transition discards its partial window.

Existing cancellation, frame recycling, cache ownership, lattice reset, shard rebalance, and
shutdown tests remain part of acceptance; they are not rewritten as controller tests.

## Benchmarks and Payoff Gate

Keep the current benchmark implementations as the comparison surface:

- `core-latency` for the cheap-path cost;
- `core-lc-throughput` and `core-hc-throughput` for light and high contention;
- `mandelbrot` at degrees 2, 3, and 5 for irregular compute; and
- `batched-mandelbrot` as a coarse-work check.

Add one diagnostic `core-dynamic-policy` benchmark, not a tuning matrix. On one live lattice it runs
many unordered no-op repeating sources, then one scarce CPU-work source, then the plentiful no-op
sources again. It records phase throughput and time to the settled mode through the three gauges.
The source switch does not call `clear`, recreate the lattice, or reset the controller. Work arrays
and source groups are allocated at trial setup.

Record the current static policy before implementation on the same host, JVM, CPU set, and launcher.
Report means, variance or confidence intervals, forks/runs, architecture, CPU model, core count, and
JVM flags. Existing one-fork benchmarks may be launched three independent times instead of changing
their annotations.

The feature is worth keeping only if all of the following hold:

- the mixed benchmark settles direct -> staged -> direct without repeated stable-workload flips;
- the scarce-work phase improves by at least 5 percent over the recorded static-policy baseline,
  outside observed run-to-run noise;
- no latency, light-contention, high-contention, or Mandelbrot result has a resolved regression over
  2 percent; and
- controller metrics and JMH profiling show no per-frame allocation and no meaningful new shared
  contention.

Results on untested architectures are reported as unverified. A result inside the noise band is not
an improvement claim.

## Implementation Boundary

The intended production footprint is limited to:

- `FragmentControlPolicy` for local observation, comparison, and per-mode batch state;
- `ControlPlaneFragment` for full active-iteration timing and safe-boundary application;
- `GlobalState` for the socket controller and padded observation slots;
- `ControlPlaneShard` for configure/reset/deactivate calls; and
- `ExecutionMetrics` plus `MetricsAggregator` for the three gauges.

Tests stay in the owning Core packages. Benchmark work is one new class plus its runner/README
registration. Preserve existing public constructors and module exports. Do not modify
`CloneConfig`, `LatticeConfig`, `CloneableObject`, `WorkRequester`, `ControlPlaneCache`, queue or
atomic implementations, routing, frames, Reactor, Spring, `FragmentActionPicker`, or any training
code. Preserve all existing public methods, including the current `GlobalState` observation API;
new controller entry points remain package-private.

Implementation order:

1. Add deterministic statistics, packed-observation, socket-state, and batch-policy tests.
2. Implement `FragmentControlPolicy` and the bounded `GlobalState` controller.
3. Wire active timing and safe-boundary changes into the fragment.
4. Add the three shard lifecycle calls and metrics.
5. Add the single mixed benchmark, run focused Core tests, then the plan's Core and JMH checks.

## Return to Design Instead of Guessing

Stop implementation and update this blueprint if any of these results occurs:

- The full-loop socket score cannot reliably distinguish the two target workload shapes in the
  deterministic fixture or repeated JMH runs.
- Normal sustained work repeatedly fails the all-member observation rule or hits the one-second
  probe bound. Do not add heartbeats, partial-member weighting, acknowledgements, or barriers as an
  implementation guess.
- Batch probing materially confounds mode comparisons. Do not add a probe scheduler or cross-core
  batch coordination in this pass.
- Meeting correctness requires a public controller/configuration API, changing clone contracts,
  classifying sources or frames, or moving ownership out of the shard and fragment.
- The mixed-workload gain is below 5 percent, falls inside noise, or does not offset a resolved
  regression above 2 percent on an existing benchmark. In that case the adaptive policy is not
  worth its runtime and maintenance cost.
- Stable workloads oscillate across successive rechecks, or x86-64 and arm64 evidence selects
  incompatible rules that the variance margin does not explain.
- Correct publication would require per-frame atomics, allocation, a lock, blocking coordination,
  or another thread.

These are design failures, not invitations to tune constants until a benchmark passes. The old
static policy remains the recorded comparison baseline; it is not retained as a hidden production
fallback.

## Acceptance

Implementation is complete only when the focused deterministic tests pass, existing lifecycle and
flow tests remain green, `mise exec -- gradle :euhedral-core:test` passes, benchmark evidence meets
the payoff gate, `git diff --check` is clean, stale threshold/target names are absent from production
code, and completion notes are appended here with commands, results, hardware, and any environmental
limits.
