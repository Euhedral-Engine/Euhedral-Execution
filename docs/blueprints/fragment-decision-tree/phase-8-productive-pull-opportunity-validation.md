# Phase 8: Productive Pull Opportunity Validation

Status: implementation-ready experimental blueprint

Prior evidence:

- [`phase-4-fragment-work-cost-surface.md`](phase-4-fragment-work-cost-surface.md)
- [`phase-7-production-tree-integration.md`](phase-7-production-tree-integration.md)

Blueprint intensity: maximum

Implementation intensity: high

## Decision and scope

Test exactly one unresolved physical question:

```text
Can live upstream handles overstate sustained, independently productive pull opportunities?
```

Keep the Phase 7 production selector and both execution paths unchanged. This phase may extend only
benchmark fixtures, benchmark-only observation, their tests, and this completion record. It does
not design or implement a productive-handle sensor, readiness estimate, source classifier, or new
policy branch.

The primary experiment uses one already-mapped expensive executor body and forced DIRECT/STAGED.
It does not run normal policy, sweep work cost, vary batch size, alter handle publication order, or
test another availability dimension.

## Required planning answers

### 1. What exact production state can make a live handle nonproductive?

An empty, incomplete `QueueIngestSink` is the concrete state. `FunctionIngestSink` and
`ConsumerIngestSink` use this queue source in normal Core ingest, so it is not a synthetic source
condition.

`LatticeVertex.ingest` creates an `UpstreamInterceptor`, publishes the handle to every registered
worker partition, and increments the global upstream count. That count is decremented only when the
interceptor observes `onComplete`, `onError`, or explicit `complete`. An empty `QueueIngestSink`
does none of those things. While it waits for a future producer it remains live, accepts demand,
returns zero from `pull`, and pushes zero frames from `request`.

Therefore, this state is reachable without violating any source contract:

```text
handle complete flag = false
source complete flag = false
queue size = 0
live handle count includes the handle
pull result = 0
request-produced frames = 0
```

### 2. Can that state persist long enough to matter?

Yes. An empty `QueueIngestSink` remains incomplete until its owner explicitly completes it or marks
it for graceful completion. It may wait for input indefinitely while accumulated demand remains
outstanding. The static candidate will hold the queue empty and incomplete through setup, all three
warmups, all five measurements, and final snapshots. This is much longer than a fragment batch or
mode-selection interval.

Array exhaustion is not the primary candidate because `ArrayIngestSink` completes when its final
item is consumed. A transient failed handle acquisition is also not the candidate: the corrected
`UpstreamQueue` reinserts a live handle, and acquisition failure only means another worker currently
owns it. `LatticeHotSource.pull` always returns zero, but the instance used by a fragment is its
downstream output to the executor and is not one of the fragment's counted upstream handles.

### 3. How can the benchmark produce it without changing scheduler semantics?

Use the existing production `QueueIngestSink`, ingest it normally through
`DiagnosticDistributor.ingestTracked`, never offer it a frame, and do not complete it until trial
teardown. Pair it with one unchanged `RepeatingSink`. The graph then has two registered handles and
two workers, but only the repeating source can supply frames. No scheduler method, handle lock,
queue algorithm, routing rule, request rule, or production source implementation changes.

### 4. What direct evidence proves productive opportunity rather than merely throughput?

Retain source-by-worker acquisition attempts, failures, pull calls, empty pull calls, and pulled
frames. Also retain request calls and request-produced frame totals at source-service-call
granularity. For the empty queue retain queue size, accumulated demand, completion state, and a
fixture assertion that no offer occurred. Pair those values with live-handle count, registered
worker count, and per-worker executor completions.

The candidate is valid only when both handles remain live, both workers remain registered, one
handle has repeated service attempts but zero productive frames, and the other handle accounts for
all source-produced frames. Throughput alone cannot classify the state.

### 5. What forced-path result falsifies the current live-handle proxy?

With `2 live handles / 2 workers / 1 productive handle`, STAGED must beat DIRECT by a statistically
resolved and materially useful margin at the fixed expensive body cost, while Control A with
`2 live / 2 productive` still favors DIRECT and Control B with `1 live / 1 productive` favors
STAGED. This changes the winner in the direction predicted by reduced productive opportunity even
though the production proxy still reports sufficient handles.

### 6. What result leaves the current tree unchanged?

If the real `2 live / 1 productive` state is valid and repeatable but DIRECT remains the winner, or
the STAGED advantage is unresolved or below the predeclared materiality gate, accept live-handle
count as sufficient for the first production tree. Transient empty pulls without a material winner
change do not justify another branch.

## Implementation-state inventory

### Registration and liveness

- `LatticeVertex.ingest` creates one shared `UpstreamInterceptor` per source.
- `LatticeVertex.addUpstream` offers that handle to every active owner-local upstream queue, then
  increments `LatticeEdge.UPSTREAM_COUNT` once.
- `ControlPlaneFragment` reads that count through
  `UpstreamQueue.getTrueUpstreamCount()` only at a completed batch boundary.
- A handle remains live until the interceptor's completion flag changes through `onComplete`,
  `onError`, or `complete`; source queue occupancy and outstanding demand do not affect the count.
- A completed handle is not reinserted when an owner-local queue encounters it. The global count
  has already been decremented by the completion callback.

### Acquisition and eligibility

- Every worker has an owner-local queue containing references to the same shared handles.
- `UpstreamInterceptor.acquireLock` uses `wip` to permit one worker at a time to service one handle.
- A losing worker records a transient acquisition failure and the corrected `UpstreamQueue` puts
  the still-live handle back in its local queue.
- After a successful acquisition, `UpstreamInterceptor.pull` or `request` delegates only when
  demand is positive, the vertex is open and not draining, and the handle is incomplete.
- Lock ownership makes a source independently serviceable by at most one worker at an instant. Two
  productive handles can therefore support two simultaneous source-service opportunities; one
  productive handle cannot.

### Productive and nonproductive service

- `pull` may consume only already-available work and returns its productive frame count.
- `request` may cause an ingest source to push available frames through the interceptor.
- `QueueIngestSink` drains its queue for either operation. Empty and incomplete means both
  operations are valid but nonproductive; request demand accumulates for later producer input.
- The calibration `RepeatingSink` supplies the full requested amount until explicit teardown, so it
  is continuously productive under the fixed fixture.
- Remote and fragment-local caches can let workers execute frames that another worker requested.
  Per-worker completions therefore measure execution participation, while per-handle service
  evidence measures source productivity. Neither substitutes for the other.

The experiment must preserve one observed implementation detail: `UpstreamQueue` allocates a
requested bucket to an acquired handle before it knows how many frames that handle will yield. An
empty live handle can therefore consume one service opportunity without producing work. This is
existing behavior to measure, not a defect or variable to change in this phase.

## Hypotheses

### H0: live-handle count remains sufficient

Under a real sustained empty-live-source state, DIRECT either remains the winner or STAGED's
advantage is too small or uncertain to justify a root-node distinction. Cache behavior, handle
migration, or the timescale of source service makes the nominal count adequate for this first
tree.

### H1: productive opportunity is a distinct branch dimension

A sustained production-reachable state exists where:

```text
liveHandles >= registeredWorkers
productiveHandles < registeredWorkers
```

and STAGED materially and reproducibly beats DIRECT at an expensive work point where the one-handle
control already favors STAGED.

H1 authorizes only a later sensor-design blueprint. It does not authorize a policy edit in Phase 8.

## Fixed experiment

### Environment and controls

Use the same host and topology as the accepted surface unless the completion record explicitly
explains an unavoidable change:

- Intel Core i9-14900K, one socket, 24 physical cores, 32 logical CPUs;
- two same-kind fragment workers selected by the existing fixture, expected logical CPUs 0 and 6;
- OpenJDK 21.0.2 and Gradle 9.6.1 from `mise.toml`;
- fixed batch target 32;
- existing unordered frame hashes and natural handle publication;
- 1,048,576-frame completion windows;
- three forks, three 3-second warmups, and five 5-second measurements; and
- forced DIRECT and forced STAGED with normal selection and production-estimator publication
  disabled.

Use CPU work at `workRounds = 512` for all primary rows. Its isolated body was approximately
449.914 ns in Phase 4, where the one-handle fixture gave STAGED the largest mapped advantage and the
two-productive-handle fixture still gave DIRECT a resolved advantage. Rounds 512 maximizes the
expected availability signal without adding a work-cost sweep. Do not add another work point unless
the fixed point is invalidated by a correctness or measurement problem.

Enable the already-validated Phase 6 raw executor-body diagnostic uniformly across all six primary
rows at its unchanged 256-call cadence. Retain its fork/worker estimates only to prove that the body
remained in the same expensive region; do not feed them to mode selection or use them to reinterpret
availability. The 90/95 ns guard, production estimator, and normal selector are not consulted
because all primary rows are forced. They remain source-level invariants to verify unchanged after
implementation.

### Fixture rows

Add one benchmark enum whose names describe physical state rather than production policy labels:

| Fixture                       | Sources                                      | Live | Productive | Workers |
|-------------------------------|----------------------------------------------|-----:|-----------:|--------:|
| `TWO_PRODUCTIVE_HANDLES`      | two unchanged `RepeatingSink` instances      |    2 |          2 |       2 |
| `ONE_PRODUCTIVE_HANDLE`       | one unchanged `RepeatingSink`                |    1 |          1 |       2 |
| `TWO_LIVE_ONE_PRODUCTIVE`     | one `RepeatingSink`, one empty queue sink    |    2 |          1 |       2 |

Run exactly DIRECT and STAGED for each row. Do not add intermittent, correlated, delayed,
rate-limited, ordered, or differently routed sources to the primary matrix.

For `TWO_LIVE_ONE_PRODUCTIVE`, use an actual `QueueIngestSink`. Assert at every JMH lifecycle
snapshot that it is incomplete, its size is zero, and no benchmark offer has occurred. Its demand
may increase and should be retained as evidence rather than reset. Complete it only during common
trial teardown.

## Benchmark-only diagnostics

Extend the existing `HandleAcquisitionRecorder` and `HandleSnapshot` rather than creating another
registry. Add source-by-worker padded counters for:

- successful service attempts, derived from attempts minus acquisition failures;
- pull calls;
- empty pull calls;
- frames returned by pulls;
- request calls; and
- frames synchronously produced by request where the benchmark source exposes that count.

Keep the existing first-productive order and completion counters. Update pull statistics once after
each delegated `pull`, never once per returned frame. Update request-call statistics once per
delegated `request`.

Add an optional benchmark-only request-result observer to `RepeatingSink`. Its existing constructor
must delegate to an observer-disabled constructor. `hookOnRequest` already has the local delivered
frame total; publish that total once after the loop. Do not wrap every pushed frame or add an atomic
write per frame. The observer runs synchronously while the source handle is acquired and can record
the current diagnostic worker without another ownership protocol.

The empty queue needs no request-result wrapper: setup makes zero offers, lifecycle snapshots prove
its size remains zero, and the production queue implementation can produce no requested frames from
an empty queue. Record its handle's request calls plus the queue's accumulated demand and zero size.
Do not generalize this inference to a future asynchronously fed source.

Snapshot all counters only at existing JMH iteration boundaries. The productive diagnostic adds no
timer reads, allocations, locks, logging, formatting, production fields, or cross-core controller
to a source service call.

For source `s` and interval `i`, report:

```text
productiveFrames[s,i] = pulledFrames[s,i] + requestProducedFrames[s,i]

nonproductivePullRate[s,i] =
    emptyPullCalls[s,i] / max(1, pullCalls[s,i])
```

Do not collapse request and pull into a guessed readiness score. Raw matrices remain the evidence.

### Instrumentation overhead gate

Before the full primary run, compare the added diagnostics enabled and disabled in the same build.
Use the existing Phase 7 overhead controls at rounds 24:

- plentiful forced DIRECT; and
- scarce forced STAGED.

Predeclare the same gate used for Phase 7 integration: no more than one percent median throughput
loss and no enabled lowest-fork score below 98 percent of the disabled lowest-fork score. Use three
forks and the full warmup/measurement protocol. If the diagnostic fails, remove or narrow it; do not
run the primary experiment with perturbing observation.

## Experimental sequence

### Stage A: deterministic fixture and recorder tests

Add benchmark-helper tests proving:

1. the three fixture definitions create the exact live/productive source counts;
2. the empty queue remains live and incomplete after empty pulls and requests;
3. no offered frame means queue size and request-produced frames remain zero while demand can grow;
4. pull-call, empty-pull, request-call, request-produced, acquisition, and first-productive deltas
   retain stable source/worker coordinates;
5. the optional repeating-source observer runs once per request with the batch total, not per frame;
6. diagnostic enable/disable does not change forced mode or source behavior; and
7. teardown completes both source kinds and restores the shared upstream registry.

Do not change Core tests unless the experiment exposes a correctness defect. Existing
`UpstreamQueueTest` remains the regression authority for transient acquisition reinsertion.

### Stage B: static forced-path experiment

Run the six primary rows in one same-build JMH group. For every fork retain:

- JMH score and error/confidence information;
- raw measurement scores, not only the aggregate mean;
- worker CPUs and cores;
- live handle count and registered worker count at every lifecycle snapshot;
- per-worker completion deltas, fractions, dominance, and effective lanes;
- handle IDs and source ordinals/types;
- acquisition attempts and failures;
- pull calls, empty pulls, and pulled frames;
- request calls and request-produced frames where observed;
- empty queue size, demand, completion state, and offer count; and
- raw executor-body diagnostic snapshots as an unchanged control, with no production selector
  publication.

Expected control validity is:

```text
TWO_PRODUCTIVE_HANDLES:
    2 live / 2 registered
    both handles productive in every retained measurement interval
    both workers productive
    DIRECT winner

ONE_PRODUCTIVE_HANDLE:
    1 live / 2 registered
    one productive handle
    both workers remain present and productive
    STAGED winner

TWO_LIVE_ONE_PRODUCTIVE:
    2 live / 2 registered
    repeating handle productive
    empty handle repeatedly serviced but never productive
    both workers remain present and productive
    winner is the hypothesis result
```

Use `D <= 0.60` as the corrected-fixture worker-presence gate, matching the prior surface where the
largest retained dominance was 0.5499. A fork above 0.60 is not automatically a branch result; it
triggers the bug-first investigation.

### Stage C: hypothesis gate and bug-first review

Define path advantage as:

```text
stagedAdvantage = (stagedThroughput - directThroughput) / directThroughput
```

Predeclare a five-percent materiality floor for H1. Accept a winner only when its mean advantage is
at least five percent and the DIRECT/STAGED JMH confidence intervals do not overlap. For Candidate
C, also require every retained STAGED fork mean to exceed every retained DIRECT fork mean; do not
construct artificial fork pairs. Preserve the exact values even when the gate is not met.

H1 passes only when all of these are true:

1. Control A remains a resolved DIRECT winner.
2. Control B remains a resolved STAGED winner.
3. Candidate C retains two live handles and two registered workers for every measurement.
4. Candidate C's empty handle has service attempts but zero productive frames for every interval.
5. Candidate C retains both execution workers with `D <= 0.60` and no disappearance regime.
6. Candidate C gives STAGED a non-overlapping advantage of at least five percent.
7. Acquisition, registration, routing, and fixture evidence reveals no correctness defect.

If Candidate C favors DIRECT, is statistically unresolved, or has a resolved STAGED advantage below
five percent, accept H0 for the present production tree. A small effect may be recorded but does not
authorize sensor design.

Before classification, investigate any unexpected discrete regime, `D > 0.60`, handle count change,
productive frame from the empty source, absent service attempts, or source/worker asymmetry. Check
queue membership, acquisition reinsertion, interceptor completion, worker registration, source
hashes, and fixture publication before interpreting throughput. Do not tune the materiality gate or
add another source shape.

### Stage D: bounded time-scale diagnostic

Run this stage only if Stage C passes H1. The static candidate already proves that nonproductivity
can persist for an entire fork; the dynamic diagnostic characterizes transition duration and does
not contribute to branch acceptance.

Add one benchmark-local gateable repeating source whose open state is behaviorally identical to the
existing repeating source and whose closed state returns/pushes zero while remaining incomplete.
This models the already-proven reachable empty/nonempty queue transition without adding producer
thread contention. It is diagnostic-only and must not replace the actual `QueueIngestSink` in the
primary acceptance row.

For forced DIRECT and forced STAGED separately, run:

```text
2 productive handles
    -> gate one handle closed
    -> 1 productive + 1 live nonproductive
    -> gate reopened
    -> 2 productive handles
```

Hold each phase for at least one 1,048,576-completion window after its per-handle evidence reaches
the requested state. Retain transition timestamps, completion deltas, handle evidence, cache counts
already exposed by the fixture, and the number of completed batches before the fork reaches a
stable phase throughput. Do not clear caches, reset workers, switch policy, or infer a controller
reaction time. If natural cached work masks only the start of a phase, record the drain duration.

Stop the dynamic diagnostic if the gate requires per-frame coordination, changes frame semantics,
or produces a state inconsistent with the actual static queue candidate.

## Files and implementation boundary

Expected experimental changes are limited to:

- `benchmarks/src/main/java/io/euhedral_execution/core/control_plane/FragmentPathCalibrationBenchmark.java`
  - add the three physical fixtures, the forced benchmark state, snapshot fields, reporting, and
    optional dynamic diagnostic;
- `benchmarks/src/main/java/io/euhedral_execution/benchmarks/utils/RepeatingSink.java`
  - add only the optional one-callback-per-request result observer while preserving the existing
    constructor and source behavior;
- `benchmarks/src/test/java/io/euhedral_execution/core/control_plane/FragmentPathCalibrationBenchmarkTest.java`
  - test fixture semantics, recorder deltas, reporting helpers, lifecycle, and teardown; and
- this blueprint's completion record.

Do not modify `ControlPlaneFragment`, `FragmentControlPolicy`, `AbstractExecutor`, `UpstreamQueue`,
`LatticeVertex`, `LatticeEdge`, `QueueIngestSink`, module descriptors, or public production
constructors during the experiment. A proven correctness defect is the only exception: stop the
benchmark interpretation, record the defect here, make the smallest separately reviewable fix with
Core regression coverage, and rerun the original six rows unchanged.

## JMH commands and raw evidence

Assemble with the pinned toolchain:

```text
mise exec -- gradle :benchmarks:test :benchmarks:assemble
```

After the benchmark method and parameter names are finalized, use this command shape for the six
primary rows:

```text
mise exec -- java \
  -XX:+UseThreadPriorities \
  --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dlogback.configurationFile=benchmark-logback.xml \
  -cp 'benchmarks/build/euhedral-benchmark.jar:benchmarks/build/lib/*' \
  org.openjdk.jmh.Main \
  'io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmark.productivePullOpportunity' \
  -p mode=DIRECT,STAGED \
  -p opportunityFixture=TWO_PRODUCTIVE_HANDLES,ONE_PRODUCTIVE_HANDLE,TWO_LIVE_ONE_PRODUCTIVE \
  -p workRounds=512 \
  -p handleLayout=NATURAL \
  -f 3 -wi 3 -w 3s -i 5 -r 5s -tu s -foe true \
  -rf json -rff benchmarks/build/reports/phase8-productive-pull-opportunity.json \
  -jvmArgsAppend '-XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED'
```

Use separate `phase8-overhead-disabled.json`, `phase8-overhead-enabled.json`, and, if authorized,
`phase8-productive-transition.json` files. Keep raw reports under `benchmarks/build/reports`; do not
add generated benchmark data to source control.

The completion record must include exact commands as actually run, host kernel and topology, JVM,
Gradle and JMH versions, git commit, worker CPUs/cores, fixture invariants, raw file locations,
fork-level scores, confidence/error data, all handle matrices, participation data, and any bug
investigation. Do not average away fork regimes.

## Stop rules

Stop and return to design when:

- the empty-live state cannot be maintained with the production `QueueIngestSink` contract;
- new observation exceeds the overhead gate or requires per-frame shared writes;
- either control fails to reproduce its known winner or handle semantics;
- any worker disappears or participation becomes inconsistent without a proven cause;
- a live-handle count changes unexpectedly;
- the empty source produces work despite the no-offer invariant;
- forced-path differences are within uncertainty or below materiality, in which case stop
  expansion and classify the valid candidate as Outcome 1;
- a correctness defect is found and has not yet been fixed and rerun;
- a second source property must change to create the candidate; or
- the work starts expanding into readiness, rate, ordering, batch-size, or source-shape discovery.

Do not respond to a failed result by changing work rounds, sampling, source order, hashes, batch
target, confidence rule, or the five-percent materiality gate.

## Verification

At minimum run:

```text
mise exec -- gradle :euhedral-core:test
mise exec -- gradle :benchmarks:test :benchmarks:assemble
mise exec -- gradle build
git diff --check
```

Also verify by direct diff that:

- `FragmentControlPolicy` retains the 90/95 ns guard and existing root comparison;
- `AbstractExecutor.PRODUCTION_BODY_TIMING_INTERVAL` remains 256;
- the non-overlapping 32-sample second-minimum and two-window expensive confirmation are unchanged;
- normal mode and safe batch-boundary selection are unchanged;
- forced modes remain isolated from normal selection;
- no production availability state or generic controller was added; and
- every new source and documentation line is ASCII.

## Acceptance and completion outcome

Append a completion record that states exactly one final outcome.

### Outcome 1: live handles accepted

The actual empty-live-source candidate did not produce a material, resolved STAGED winner after all
validity gates. Retain:

```text
liveHandles >= registeredWorkers -> DIRECT
```

Do not add a productive-opportunity branch or sensor-design phase from this result.

### Outcome 2: productive opportunity confirmed

The actual empty-live-source candidate retained nominally sufficient handles, demonstrated only one
productive handle, and produced a material resolved STAGED winner. Leave production unchanged. The
only next blueprint is the cheapest reliable runtime observable for sustained productive pull
opportunity.

### Outcome 3: correctness defect found

The apparent distinction cannot yet be classified because a Core or fixture correctness defect
caused it. Record the defect, its regression evidence, and why the original six-row experiment is
not yet valid. If the defect can be fixed narrowly and the same experiment rerun within this phase,
record Outcome 1 or Outcome 2 instead and retain the defect as an intermediate finding.

The phase is complete only when the outcome is supported by production-reachable source semantics,
direct handle evidence, forced-path results, participation evidence, and the predeclared uncertainty
and materiality gates.
