# Fragment Worker Participation Discovery Blueprint

## Status

- Date: 2026-08-10
- Source plan: [`startup-fragment-path-calibration.md`](../../plans/fragment-decision-tree/phase-1-startup-fragment-path-calibration.md)
- Prior blueprint: [`startup-fragment-path-calibration.md`](./phase-1-startup-fragment-path-calibration.md)
- Blueprint intensity: high
- Implementation intensity: medium
- Scope: benchmark-only per-worker completion accounting on the existing forced-path fixture, plus bounded single-variable sweeps
- Investigative only. This blueprint does not approve any production policy change.

This blueprint specifies the smallest experiment that can explain the Tier 1 CPU throughput
bimodality recorded in the phase 1 run and decide whether effective worker participation is the next
branching variable in the scheduler decision tree. It does not add a production split. It produces
per-fork, per-worker evidence and an accept or reject verdict for the participation hypothesis.

## Objective

Explain the Tier 1 CPU throughput regimes observed in phase 1 (roughly 4.24 million and 8.5 million
frames/s within a fixed 225 ns workload) and determine whether they correspond to different numbers
of productively supplied workers. Build the scheduler policy incrementally as an explicit, measurable
decision tree whose branches are discovered and justified through controlled benchmarks. Change one
meaningful input at a time and add a branch only when the current tree cannot explain a resolved
change in which path wins.

## Grounding in the current code

The two tiers are the two arms of `ControlPlaneFragment.cycle()`
(`euhedral-core/.../control_plane/ControlPlaneFragment.java:197`). Both arms first drain owner-local
cache, then diverge on `this.controlPolicy.mode()`.

Tier 1 (DIRECT, `ControlPlaneFragment.java:232-256`):

```text
localCacheExecute -> super.drain(outputStream, limit)                 // owner-local cached work
remoteCacheExecute -> super.pull(outputStream, NO_STOP, limit)        // remote cached work directly
remoteExecute      -> super.upstreamPull(context.upstream, ..., limit)// direct upstream pull
if processed == 0  -> super.requestAndPull(context, batchSize)        // request + pull fallback
```

Tier 2 (STAGED, `ControlPlaneFragment.java:257-293`):

```text
super.request(context)                                               // request upstream work first
localCacheExecute -> super.drain(...)                                // owner-local cached work
remoteCacheExecute -> super.pull(...)                                // remote cached work via cache path
remoteExecute      -> super.upstreamPull(...)                        // remaining remote work directly
```

The suspected physical mechanism is the upstream handle acquired inside `remoteExecute`
(`super.upstreamPull`). While one worker holds a source handle and pulls, a second worker that shares
that source has no independent handle to pull from and can only fall through to a cache path that may
be empty. Tier 2's leading `super.request(context)` routes unordered frames into caches before any
worker pulls, which can keep both workers supplied. This is the hypothesis to prove or falsify, not a
settled fact.

The forced-mode seam and fixture already exist from phase 1 and are reused unchanged:

- `FragmentControlPolicy.DiagnosticOverride`, `installDiagnosticOverride`, `clearDiagnosticOverride`
  (`euhedral-core/.../control_plane/FragmentControlPolicy.java:34-50`) pin mode and batch at setup.
- `FragmentPathCalibrationBenchmark` (`benchmarks/.../control_plane/FragmentPathCalibrationBenchmark.java`)
  builds the real graph, pins two same-kind workers on one socket, and counts completions.
- `BenchRunner` selection `core-fragment-path-calibration` already runs the fixture.

## The diagnostic is nearly free

The completion counter is already partitioned per worker. `CountingExecutor.execute`
(`FragmentPathCalibrationBenchmark.java:416-423`) calls `this.counters.increment(super.cpu)`, where
`super.cpu` is the pinned logical CPU of that worker's executor clone. `PaddedLongAdder` extends
`PaddedAtomicLongArray`, which exposes per-index `getAcquire(int idx)`
(`euhedral-data-structures/.../atomics/PaddedAtomicLongArray.java:51`). Today only `counters.sum()`
is read, which collapses the per-worker detail.

Per-worker accounting therefore requires no change to the production hot loop and no new hot-loop
state. It changes only what the benchmark reads and reports:

1. Retain the ordered list of worker logical CPUs selected in `setupPath` so per-index reads map to a
   stable worker identity. The CPUs are already known when each `CountingExecutor` clone is pinned.
2. At each fixed completion window and in `beforeClose`, read `counters.getAcquire(cpu)` for each
   worker CPU and record the per-worker delta over the window, alongside the existing aggregate
   sample. Use VarHandle acquire reads only; add no per-frame reads, no locks, and no parking.
3. Report per-worker deltas per fork so each fork's regime, taken from its JMH throughput, can be
   paired with its participation split.

All new fields and methods are confined to the benchmark module and carry `///` declaration comments,
consistent with the phase 1 fixture. Nothing is added to `ControlPlaneFragment`,
`FragmentControlPolicy`, or any production hot path.

## Exact hypotheses

Quantified against the phase 1 workload. For 225 ns fixed CPU work, one execution lane has a ceiling
near 4.44 million ops/s and two lanes near 8.88 million ops/s.

- H1: Tier 1 throughput has an inflection driven by effective worker participation, not by ordinary
  warmup or benchmark noise. Evidence for the discreteness is already recorded: each phase 1 fork sat
  entirely in one regime.
- H2: The low Tier 1 regime (near 4.24 million frames/s, close to the one-lane ceiling) corresponds
  to one worker performing nearly all useful work.
- H3: The high Tier 1 regime (near 8.5 million frames/s, close to the two-lane ceiling) corresponds
  to both workers performing substantial useful work.
- H4: Tier 2 CPU throughput is stable across source shapes (near 7.44 million frames/s in phase 1)
  because its request-first ordering and cache path keep work available to both workers regardless of
  handle contention.
- HP (physical driver, tested only if H1 through H3 hold): the regime is selected by effective source
  parallelism relative to execution parallelism, mediated by upstream-handle serialization in
  `super.upstreamPull`. When independent pullable handles are fewer than active workers, Tier 1 leaves
  a worker starved.

Null and alternative outcomes that reject the participation hypothesis:

- H0: In the low regime both workers show near-equal completions. Then the bottleneck is a shared
  resource or fixed overhead, not worker starvation, and participation is not the branching variable.
- HA: The per-fork participation split does not track the per-fork throughput split. Then throughput
  and participation are not the same phenomenon and the regime has another cause.

## Metrics

Per fork, over the JMH measurement windows:

- Per-worker completion fractions `f_i = c_i / sum(c)` for each worker `i`.
- Dominance `D = max_i(c_i) / sum(c)`. For two workers `D = 0.5` is perfectly balanced and `D = 1.0`
  is one worker doing all work.
- Effective lanes `L = observed_throughput / single_lane_ceiling`, where the single-lane ceiling is
  the isolated one-worker throughput measured by `singleWorkerOverhead` scaled to the workload, or the
  225 ns theoretical 4.44 million ops/s when that control is unavailable.

Report `D`, all `f_i`, `L`, and the raw per-worker deltas. Do not add a percentile or statistics
library; reuse the existing in-place `median` helper for per-fork summaries.

## Benchmark fixtures

- Extend `FragmentPathCalibrationBenchmark`; do not add a new benchmark class. Keep the
  `core-fragment-path-calibration` selection and README entry.
- Topology: two workers of the same core kind on one socket, using the existing `selectWorkerCores`
  and `pinHarness` logic. If the host cannot provide that topology, report the fixture as unsupported
  rather than mixing core kinds or sockets.
- Workload: 225 ns fixed CPU work and no-op `BenchmarkFrame` work, unchanged from phase 1.
- Hold identical unordered routing hashes, worker CPU sets, source arrays, fixed batch target of 32,
  preallocated frame pools, and absolute completion targets.

## Variables to sweep

Start with the known exposer: plentiful versus scarce source shapes, already the `sourceShape`
parameter. Confirm or reject H1 through H3 there first.

Only if H1 through H3 hold, drill into HP by changing one variable at a time. Rank the candidates by
how directly the current code reaches them without adding production surface:

1. Effective source parallelism relative to execution parallelism (source count vs worker count),
   driven by the existing `RepeatingSink` count and `sourceCount(shape, workers)` mapping. This is the
   prime candidate and the likely first split.
2. Upstream handle serialization or independent pullability, exercised by `super.upstreamPull` in
   `remoteExecute`. Vary whether the shared source exposes one handle or independent handles.
3. Which worker ingests or pulls first, driven by source ingest order in `setupPath` and distributor
   `ingest`.
4. Routing and hash decisions established at graph construction in `DiagnosticDistributor`'s routing
   function.
5. Local-cache occupancy and remote-cache availability, and whether work is consumed directly from
   the remote path or enters the cache path first.
6. Source-to-worker ratio beyond one and two, source ownership or assignment, insertion or order
   effects, request timing and request ownership, worker or core affinity.

Do not treat scarce versus plentiful as the final feature. It is the variable that exposed the
phenomenon. Prefer discovering the underlying physical condition, stated as effective source
parallelism being lower than available execution parallelism.

## Controls

- Base regime medians from the phase 1 run: Tier 1 CPU 4.24 million and roughly 8.5 million frames/s;
  Tier 1 no-op 81.7 million and 144 million frames/s; Tier 2 CPU near 7.44 million frames/s and stable
  across shapes. Direct intrinsic overhead startup 11.344 ns/frame, warmed 11.235 ns/frame.
- Fixed CPU work 225 ns and fixed no-op body.
- Fixed batch target 32; discard the initial batch of two; a run that does not hold an effective batch
  of 32 after that boundary is invalid.
- Three forks, three warmups of at least three seconds, five measurements of at least five seconds.
  Because the regime is per fork and discrete, add forks if either regime is under-sampled; do not
  average across regimes.
- Fixed routing hashes, fixed worker CPU sets, same socket and core kind.

## Iterative experimental method

1. Start from the current smallest decision tree (Tier 1 preferred below the direct no-op threshold,
   Tier 2 above it, as recorded in phase 1).
2. Select one unresolved leaf or unexplained regime; begin with the Tier 1 CPU bimodality.
3. State the simplest physical hypothesis explaining it (participation, then HP).
4. Design the smallest controlled benchmark that can falsify it.
5. Change one variable at a time when practical.
6. Preserve raw per-fork and per-worker evidence.
7. Add a decision-tree split only when a variable produces a reproducible, meaningful winner or
   behavior reversal.
8. Once a split is established, recursively investigate only the leaves that still contain materially
   different behavior.
9. Stop splitting when additional variables do not meaningfully change the execution-path decision.

## Evidence needed to accept or reject

Judge per fork, then require the per-fork participation split and the per-fork throughput split to be
bimodal in lockstep.

- Accept H2 and H3 when, across forks:
  - low-regime forks show `D >= 0.9` (one worker accounts for at least ninety percent of completions)
    and `L` near 1.0; and
  - high-regime forks show `D <= 0.6` (both workers substantial) and `L` near 2.0.
- Accept H1 when the throughput regime and the participation regime coincide fork by fork, not only in
  aggregate.
- Reject via H0 when low-regime forks show `D` near 0.5. Participation is then not the cause; the
  bottleneck is a shared resource or fixed overhead. Stop this branch and return to design.
- Reject via HA when a high-throughput fork shows `D >= 0.9` or a low-throughput fork shows `D` near
  0.5. Throughput and participation are then distinct phenomena.

## Distinguishing artifacts

Rule out non-participation causes explicitly:

- Cache: hold the routing function and cache capacities fixed. Note that even the scarce single-source
  shape hashes frames into the cache; a cache-driven result would not track per-worker completions.
- Affinity: pin workers to fixed CPU sets from `SystemInfo.getCoreInfo`, same socket and core kind, as
  the fixture already does. Reject affinity as the cause only if participation tracks throughput while
  affinity is held constant.
- JIT: require the startup median within ten percent of the warmed median, using the existing
  `SingleWorkerState` startup and warmed sampling. Phase 1 already showed this holds for both paths.
- Request ordering and lifecycle: hold the fixed batch target, discard the initial batch of two, and
  keep the startup barrier `awaitRegisteredWorkers` and the drain toggling before ingest.
- Timing: fixed no-op and CPU bodies isolate overhead from workload variation; per-worker reads are at
  window boundaries only, never per frame.
- Parking versus idle-but-unproductive: a starved worker parks through
  `FragmentControlPolicy.missRequiresPark` and `LockSupport.parkNanos`
  (`ControlPlaneFragment.java:302-303`), so it shows near-zero completions. The per-worker completion
  delta already distinguishes a parked worker from one that is scheduled but doing no useful work; no
  separate park counter is required, though an optional benchmark-only park tally may be added if the
  completion delta alone is ambiguous.

## Decision-tree feed

If participation is confirmed and HP holds, the first real split is a measurable physical condition,
not a source label:

```text
effective source parallelism < execution parallelism
    -> prefer Tier 2 (request-first ordering keeps both workers supplied)
otherwise
    -> prefer Tier 1 (lower overhead, both workers already supplied)
```

This condition is observable from data the fragment already tracks. `cycle()` reads
`context.upstream.getCachedUpCount()` into `this.state.upstreamCount`
(`ControlPlaneFragment.java:204-207`); comparing that against the active worker or execution
parallelism is a candidate signal for the split. Recurse only into leaves that still show materially
different behavior after the split.

## Explicit stop conditions

- H0 holds (balanced low regime): stop this branch. Participation is not the branching variable;
  return to design for the true shared-resource bottleneck.
- A single physical split fully explains the Tier 1 bimodality across both source shapes and both
  workloads: stop splitting and record the proposed split.
- Additional swept variables do not change the winning execution-path decision: stop splitting.
- Explaining the regime would require per-frame timing, shared cross-core state, source label
  classification, a controller thread, continuous online experimentation, or a public configuration
  surface: stop. That exceeds the payoff and the scope of this investigation.

## Minimal production implications

If the hypothesis is eventually confirmed, the only production change is one explicit decision-tree
split encoding a measurable physical condition (effective source parallelism versus execution
parallelism), evaluated at existing completed-batch boundaries using data the fragment already holds.
Reuse the current owner-local one-eighth EWMA and completed-batch streak; add no second statistical
controller and no new shared state. Do not add public configuration, arbitrary source classification,
online experimentation, or a coordination subsystem. Do not implement the production policy in this
phase.

## Acceptance and design boundary

This investigative pass is complete when:

- new benchmark-only code carries `///` declaration comments and is confined to the benchmark module;
- focused tests and `mise exec -- gradle :euhedral-core:test :benchmarks:test :benchmarks:assemble`
  pass, and `git diff --check` is clean;
- the recorded result includes hardware, effective CPUs, JVM, batch size, raw per-fork per-worker
  completion deltas, per-fork `D`, `f_i`, and `L`, the pairing of each fork's throughput regime with
  its participation split, and an explicit accept or reject verdict against H1 through H4 and HP.

Passing this pass authorizes only the next single decision-tree split, discovered by the ranked
variable sweep. It does not authorize a general adaptive controller or any production implementation
in the same pass.
