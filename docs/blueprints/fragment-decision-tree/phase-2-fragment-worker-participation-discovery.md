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

## Completion notes

Implemented on 2026-08-10. The change is confined to
`benchmarks/src/main/java/io/euhedral_execution/core/control_plane/FragmentPathCalibrationBenchmark.java`
and its benchmark test. The fixture now retains worker logical CPUs in stable physical-core order,
uses `PaddedLongAdder.getAcquire(cpu)` around every fixed completion invocation, excludes warmup
iterations through the JMH iteration lifecycle, aggregates raw worker deltas per measurement
iteration, and reports per-window and per-fork fractions, dominance, and effective lanes. The
executor hot loop and all production modules are unchanged.

### Environment and protocol

- CPU: Intel Core i9-14900K, x86-64, one socket, 24 physical cores, 32 logical CPUs
- Effective process CPUs: 0-31; diagnostic workers: logical CPUs 0 and 6
- Cache: 36 MiB shared L3
- JVM: OpenJDK 64-Bit Server VM 21.0.2+13-58
- JMH: 1.37, three forks, three 3-second warmups, five 5-second measurements
- Batch target: 32; completion window: 1,048,576 frames
- CPU single-lane ceiling for `L`: 4,444,444 frames/s from the fixed 225 ns body
- No-op `L`: fork throughput divided by the phase 1 isolated same-mode control, 88.797 million
  frames/s for DIRECT and 35.919 million frames/s for STAGED

The primary CPU matrix produced these JMH aggregate scores:

| Mode | Sources | Mean frames/s | 99.9% error |
|---|---|---:|---:|
| DIRECT | plentiful | 7,086,484 | 2,225,429 |
| DIRECT | scarce | 4,246,564 | 25,865 |
| STAGED | plentiful | 7,467,877 | 90,907 |
| STAGED | scarce | 7,492,984 | 25,375 |

DIRECT/plentiful was rerun for three additional forks because the primary run contained only one
low-regime fork. Across both runs there were three low and three high forks. The table below records
the added run, which contains both regimes, and the primary run for the other rows. `Deltas` are the
raw per-worker completion deltas summed over the five measurement windows. `f0/f1`, `D`, and `L` are
derived from those same windows; throughput is the mean of the five matching JMH iterations.

| Mode | Sources | Fork | Throughput | Deltas | f0 / f1 | D | L |
|---|---|---:|---:|---|---|---:|---:|
| DIRECT | plentiful | A1 | 4,242,189 | `[0, 110103894]` | 0.000000 / 1.000000 | 1.000000 | 0.954545 |
| DIRECT | plentiful | A2 | 8,516,287 | `[107348958, 107614951]` | 0.499381 / 0.500619 | 0.500619 | 1.916280 |
| DIRECT | plentiful | A3 | 4,252,830 | `[0, 110103174]` | 0.000000 / 1.000000 | 1.000000 | 0.956937 |
| DIRECT | scarce | 1 | 4,255,473 | `[110103418, 0]` | 1.000000 / 0.000000 | 1.000000 | 0.957539 |
| DIRECT | scarce | 2 | 4,242,196 | `[0, 109055577]` | 0.000000 / 1.000000 | 1.000000 | 0.954646 |
| DIRECT | scarce | 3 | 4,242,024 | `[0, 110103440]` | 0.000000 / 1.000000 | 1.000000 | 0.954508 |
| STAGED | plentiful | 1 | 7,573,507 | `[96999162, 96993185]` | 0.500015 / 0.499985 | 0.500015 | 1.704147 |
| STAGED | plentiful | 2 | 7,375,619 | `[94346229, 94401911]` | 0.499852 / 0.500148 | 0.500148 | 1.659590 |
| STAGED | plentiful | 3 | 7,454,507 | `[94272857, 94476258]` | 0.499461 / 0.500539 | 0.500539 | 1.677365 |
| STAGED | scarce | 1 | 7,487,114 | `[94534246, 94215245]` | 0.500845 / 0.499155 | 0.500845 | 1.684708 |
| STAGED | scarce | 2 | 7,470,150 | `[93885266, 94864184]` | 0.497407 / 0.502593 | 0.502593 | 1.680891 |
| STAGED | scarce | 3 | 7,521,688 | `[93964271, 94784957]` | 0.497826 / 0.502174 | 0.502174 | 1.692485 |

The no-op matrix produced these aggregate JMH scores:

| Mode | Sources | Mean frames/s | 99.9% error |
|---|---|---:|---:|
| DIRECT | plentiful | 122,467,336 | 32,714,177 |
| DIRECT | scarce | 81,185,718 | 582,089 |
| STAGED | plentiful | 38,420,521 | 1,997,376 |
| STAGED | scarce | 46,834,661 | 1,706,497 |

The corresponding fork evidence is:

| Mode | Sources | Fork | Throughput | Deltas | f0 / f1 | D | L |
|---|---|---:|---:|---|---|---:|---:|
| DIRECT | plentiful | 1 | 143,798,565 | `[1788074610, 1810017665]` | 0.496951 / 0.503049 | 0.503049 | 1.619 |
| DIRECT | plentiful | 2 | 142,941,833 | `[1788933257, 1788178069]` | 0.500106 / 0.499894 | 0.500106 | 1.610 |
| DIRECT | plentiful | 3 | 80,661,611 | `[0, 2018645094]` | 0.000000 / 1.000000 | 1.000000 | 0.908 |
| DIRECT | scarce | 1 | 81,704,944 | `[0, 2045911520]` | 0.000000 / 1.000000 | 1.000000 | 0.920 |
| DIRECT | scarce | 2 | 80,481,811 | `[2015499515, 0]` | 1.000000 / 0.000000 | 1.000000 | 0.906 |
| DIRECT | scarce | 3 | 81,370,398 | `[2036478983, 0]` | 1.000000 / 0.000000 | 1.000000 | 0.916 |
| STAGED | plentiful | 1 | 39,296,153 | `[493081325, 492618937]` | 0.500235 / 0.499765 | 0.500235 | 1.094 |
| STAGED | plentiful | 2 | 35,906,646 | `[451489276, 450318408]` | 0.500649 / 0.499351 | 0.500649 | 1.000 |
| STAGED | plentiful | 3 | 40,058,564 | `[504321541, 500254844]` | 0.502024 / 0.497976 | 0.502024 | 1.115 |
| STAGED | scarce | 1 | 47,474,000 | `[29628339, 1159517379]` | 0.024916 / 0.975084 | 0.975084 | 1.322 |
| STAGED | scarce | 2 | 48,115,498 | `[8681624, 1197242005]` | 0.007199 / 0.992801 | 0.992801 | 1.339 |
| STAGED | scarce | 3 | 44,914,504 | `[12030849, 1112097824]` | 0.010702 / 0.989298 | 0.989298 | 1.250 |

### Verdict

- H1: **accepted**. DIRECT throughput and participation are bimodal in lockstep in both CPU and
  no-op plentiful-source forks.
- H2: **accepted**. Every low CPU DIRECT fork has `D = 1.0` and `L = 0.955-0.957`; one worker does
  effectively all completions.
- H3: **accepted**. Every high CPU DIRECT fork has `D` near 0.5 and `L` near 1.92. The no-op high
  forks are likewise balanced, while their low fork has `D = 1.0`.
- H4: **accepted for the stated CPU fixture**. STAGED is balanced in every CPU fork for both source
  shapes and remains near 7.47-7.49 million frames/s in aggregate. The no-op control exposes a
  boundary: STAGED/scarce is highly dominated, so request-first ordering does not guarantee balanced
  participation for every workload.
- H0 and HA: **rejected**. No low DIRECT fork was balanced and no high DIRECT fork was dominated.
- HP: **supported as a starvation mechanism, but rejected as a complete source-count split**.
  One shared source always leaves DIRECT with one productive worker, and two sources make balanced
  execution possible. However, plentiful DIRECT still selects either one or two productive workers
  across otherwise fixed forks. Nominal source count relative to worker count therefore does not
  select the regime by itself. Source/handle acquisition, assignment, or insertion order remains the
  next unresolved variable.

The participation hypothesis explains the Tier 1 bimodality, but the ranked source-count sweep does
not yet identify a deterministic decision-tree signal. This pass authorizes the next bounded
experiment on handle acquisition or assignment; it does not authorize the production split sketched
above.

### Verification

- `mise exec -- gradle :benchmarks:test --tests io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmarkTest`
  passed.
- `mise exec -- gradle :euhedral-core:test :benchmarks:test :benchmarks:assemble` passed.
- The full CPU and no-op JMH matrices completed at the declared protocol; three additional
  DIRECT/plentiful CPU forks supplied three observations in each regime across both runs.
- `git diff --check` is clean.
