# Phase 9: Productive-Handle Production-Root Integration

Status: complete - Outcome 2, production unchanged

Prior evidence:

- [`phase-7-production-tree-integration.md`](phase-7-production-tree-integration.md)
- [
  `phase-8-productive-pull-opportunity-validation.md`](phase-8-productive-pull-opportunity-validation.md)

Blueprint intensity: maximum

Implementation intensity: high

## Decision and bounded scope

Replace exactly one production input:

```text
liveHandles >= registeredWorkers
```

becomes:

```text
productiveHandles >= registeredWorkers
```

where `productiveHandles` is read from the `UpstreamQueue` owned by the same fragment worker that
evaluates `FragmentControlPolicy`. Keep the selector's remaining body-history, cheap, guard-band,
and expensive branches byte-for-byte equivalent except for availability-oriented parameter names.

Do not aggregate across workers, publish producer readiness, synchronize observations, add a ratio
or another estimator, or change productive-sensor semantics. Do not modify either execution path,
body-cost sampling or aggregation, thresholds, batch sizing, cache behavior, routing, requests,
topology, or the next research branch.

## Ownership, ordering, and lifecycle

`UpstreamQueue` remains the sole owner of each worker's observational productive count. Plain
productivity state is valid because the pinned worker services handles and reads its own count.
Shared handle locking and live-count atomics retain their existing semantics; no new publication
edge is required or permitted.

Normal policy reads `getProductiveHandleCount()` only after `state.completed` reaches the existing
batch target. A productivity transition during source service cannot truncate or switch the active
batch. The next completed boundary may consume the new local belief. Availability changes retain
body-cost history, caches, routing, and productive observations.

New handles remain optimistic. Source emptying and producer offers do not asynchronously change a
worker's belief. Completion/removal, replacement, zero-live, ordinary count refresh, full idle,
trial reset, and close retain the validated sensor and Phase 7 lifecycle behavior. Workers may
legitimately disagree until each services the handle again.

Forced DIRECT and STAGED return before normal selection and therefore ignore productive availability
exactly as they ignored live availability.

## Production implementation

In `ControlPlaneFragment.recordProgress`, after resetting the completed-batch counter, replace the
true-live accessor with `context.upstream.getProductiveHandleCount()` and pass that value to
`FragmentControlPolicy.completeBatch`. Rename package-private availability parameters and comments
from live to productive where needed to prevent semantic drift. Do not change selector ordering or
arithmetic.

Extend the existing best-effort benchmark snapshot only as needed to report the owner-local
productive count beside mode and body-cost state. Snapshot disagreement is diagnostic evidence, not
a failure by itself.

## Deterministic tests

Keep the existing productive-sensor conformance suite as lifecycle authority and add integration
coverage proving:

```text
productive >= workers                 -> DIRECT for every body region
productive < workers + no history     -> DIRECT
productive < workers + cheap          -> DIRECT
productive < workers + guard          -> retain settled mode
productive < workers + expensive      -> STAGED
live >= workers + productive < workers + expensive -> STAGED
```

Add a completed-batch integration test or an equivalent narrow seam proving stale optimistic state
can keep DIRECT until the owner observes an empty source, after which the next boundary can select
STAGED. Prove a producer offer alone leaves the local scarcity belief and selected mode unchanged,
then useful owner observation permits the next boundary to return to sufficient DIRECT. Do not
require cross-worker convergence.

Retain tests that availability changes preserve body history, safe batch boundaries, forced modes,
registration/removal/replacement, zero-live, trial reset, ordinary refresh, idle, and close.

## Bounded JMH validation

Reuse `FragmentPathCalibrationBenchmark`, its real repeating and empty incomplete
`QueueIngestSink` sources, natural publication, two same-kind workers, batch cap 32, 1,048,576-frame
completion windows, per-worker completions, handle recorder, and policy snapshots.

Run normal production policy at:

| Row           | Sources                               | Rounds | Expected mode |
|---------------|---------------------------------------|-------:|---------------|
| A             | two repeating                         |    512 | DIRECT        |
| B             | one repeating                         |    512 | STAGED        |
| C             | repeating plus empty incomplete queue |    512 | STAGED        |
| cheap control | repeating plus empty incomplete queue |     24 | DIRECT        |

Rows A-C must retain the declared live/productive/worker counts. Row C is accepted only when each
worker that observed the empty source reports local scarcity and its settled expensive mode is
STAGED. If a worker has not yet observed the empty source, its optimistic disagreement is valid but
the benchmark must wait for observation before measuring the resolved row rather than coordinate
worker state in production.

For rows A-C, compare normal throughput with the already-established forced winner using the same
build and fixture. Predeclared gates are:

- normal median no more than 2% below the forced-winner median;
- normal lowest fork at least 97% of the forced winner's lowest fork;
- both workers remain productive, with no unexplained new fork regime; and
- worker-local selector state matches that worker's own reported productive observation.

Retain per-worker completions and dominance, live handles, owner-local productive handles,
registered workers, selected mode, body estimate/history, handle acquisitions, empty-source state,
and raw fork scores.

## Same-build productive-sensor overhead

Use a setup-only benchmark diagnostic to compare the validated sensor's observation/bookkeeping with
a same-build liveness-only baseline while mode remains forced and normal selection is bypassed. The
switch must not become application configuration or alter default production behavior.

Measure separately:

- productive fast path: successful pulls from repeating sources; and
- miss path: successful acquisition of the real empty incomplete `QueueIngestSink` returning zero.

Use the smallest protocol that yields stable same-build evidence; retain the standard three-fork,
three-warmup, five-measurement protocol unless a shorter smoke clearly establishes stability before
the acceptance run. Predeclared gates are unchanged from Phase 7:

- enabled median loss at most 1%;
- enabled lowest fork at least 98% of disabled lowest fork; and
- balanced participation with no new regime.

The miss-path comparison must exercise the corrected one-item zero-result probe and may not infer
its cost from the productive row. Sensor cost is unacceptable if it consumes a substantial share of
the Phase 8 5.668% critical benefit even when the numeric gates otherwise appear noisy; record that
as an integration defect rather than tuning the sensor.

## Contradiction handling and outcomes

For a wrong resolved row, investigate in order: owner-local productive count, completed-batch read,
body history, matching forced surface, lifecycle/accounting defects, then a genuinely new physical
relationship. Fix narrow correctness defects and rerun the same rows. Do not tune thresholds or
productive semantics.

Append a completion record with exactly one final result:

1. **Outcome 1: production integration accepted.** All selection, throughput, overhead,
   participation, and lifecycle gates pass; the productive-opportunity branch is complete.
2. **Outcome 2: integration implementation defect.** The signal remains valid but a bounded
   selector, ownership, lifecycle, or benchmark defect cannot be resolved; leave production
   unchanged and document the blocker.
3. **Outcome 3: productive branch contradicted.** Valid normal and forced evidence contradicts the
   Phase 8 relationship despite correct sensor semantics; leave production unchanged and record the
   contradiction without tuning.

## Required verification

```text
mise exec -- gradle :euhedral-core:test --no-daemon
mise exec -- gradle :benchmarks:test --no-daemon
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck --no-daemon
mise exec -- gradle build --no-daemon
git diff --check
```

Also retain exact bounded JMH commands and raw report/log locations under
`benchmarks/build/reports` in the completion record.

## Completion record

Completed: 2026-08-13

Final result: **Outcome 2: integration implementation defect.**

The worker-local productive signal remains semantically valid, and the temporary production-root
integration selected every expected row. It was not accepted because the corrected empty-source miss
path failed the predeclared one-percent median overhead gate in the original run and the one
unchanged repeat. A narrow attempt to avoid redundant probes for already nonproductive handles did
not resolve the gate and regressed the productive control, so it was reverted. The production
selector is restored to the Phase 7 live-handle root. No Core production or Core test file remains
changed.

### Temporary production integration and ownership

The evaluated candidate changed only `ControlPlaneFragment.recordProgress` at the existing
completed-batch boundary:

```text
context.upstream.getTrueUpstreamCount()
    ->
context.upstream.getProductiveHandleCount()
```

The value came from the fragment worker's own `UpstreamQueue`; no worker aggregation,
synchronization, producer publication, ratio, cache change, body-history reset, batch truncation, or
forced-mode override was added. A benchmark-only policy snapshot retained the last count consumed at
the safe boundary. Both the selector change and snapshot field were removed after the overhead gate
failed.

### Deterministic and lifecycle tests

The temporary candidate added direct selector tests for productive sufficiency, startup history,
cheap work, both guard-band settled modes, expensive work, and the exact two-live/one-productive
regression. It also tested stale productive and stale nonproductive beliefs across successive safe
boundaries while proving retained body history. Those tests passed before the candidate was
benchmarked and were removed with the rejected integration.

The retained conformance and lifecycle suite continued to pass after the revert. It covers
optimistic registration; all four productive/nonproductive transitions; acquisition failure;
productive and nonproductive completion/removal; replacement and zero-live bounds; request-only,
stop, exception, and cancellation behavior; producer offers and later useful observation;
worker-local disagreement; ordinary count refresh; trial reset; idle; and close. Forced DIRECT and
STAGED remained independent throughout the temporary normal and forced runs.

### Normal-policy rows

The temporary candidate ran the fixed full protocol: three forks, three 3-second warmups, five
5-second measurements, two workers on logical CPUs 0 and 6, natural handles, batch cap 32, and
1,048,576-frame completion windows.

| Physical row             | Rounds | Live/productive/workers | Selected mode | Mean frames/s | JMH error | Fork means                     |
|--------------------------|-------:|-------------------------|---------------|--------------:|----------:|--------------------------------|
| two productive           |    512 | 2/2/2                   | DIRECT        |     4,287,934 |    16,813 | `[4271871,4307184,4284746]`    |
| one productive           |    512 | 1/1/2                   | STAGED        |     4,081,327 |    10,384 | `[4069013,4085827,4089141]`    |
| two live, one productive |    512 | 2/1/2                   | STAGED        |     4,048,408 |    25,561 | `[4052864,4047971,4044389]`    |
| two live, one productive |     24 | 2/1/2                   | DIRECT        |    27,975,911 | 3,276,211 | `[30251454,23802568,29873712]` |

Every warmup, measurement, and final expensive-row policy snapshot reported the expected mode,
body-cost region, live count, registered-worker count, and owner-local productive count. In the
decisive row, both workers reported one productive handle and STAGED. The empty `QueueIngestSink`
remained live, incomplete, size zero, and offer count zero, and produced zero pulled frames. The
cheap control remained DIRECT in every snapshot. Its low fork retained both workers with aggregate
dominance 0.55027, below the established 0.60 worker-presence gate, so it was not a
worker-disappearance or selector contradiction.

### Normal versus forced winner

The matching same-build forced winners enabled production body sampling and productive observation;
the only selection difference was the forced bypass.

| Physical row             | Normal mode | Normal mean | Forced mean | Median change | Lowest-fork ratio |
|--------------------------|-------------|------------:|------------:|--------------:|------------------:|
| two productive           | DIRECT      |   4,287,934 |   4,288,223 |        -0.15% |            99.80% |
| one productive           | STAGED      |   4,081,327 |   4,097,813 |        -0.19% |            99.41% |
| two live, one productive | STAGED      |   4,048,408 |   4,022,915 |        +0.73% |           100.72% |

All rows passed the predeclared maximum two-percent median regression and minimum 97-percent
lowest-fork ratio. Expensive normal aggregate dominance was 0.50003-0.50101; forced aggregate
dominance was 0.50000-0.50176. There was no worker disappearance or new expensive-work fork regime.

### Productive and empty-miss overhead

The retained benchmark-only overhead fixture compares otherwise identical full production graphs.
Both arms use the real sources, two workers, acquisition locking, routing, batch 32, forced path,
rounds 24, completion accounting, and participation evidence. `ENABLED` uses the production
productive observation and zero-result probe. `LIVENESS_ONLY` is a benchmark-only pre-sensor
interceptor and cannot affect normal production.

The predeclared gates were median loss at most one percent and enabled lowest fork at least 98
percent of the baseline lowest fork.

| Run/control                      | Enabled median | Baseline median | Median change | Lowest-fork ratio | Result      |
|----------------------------------|---------------:|----------------:|--------------:|------------------:|-------------|
| original productive fast         |     59,788,195 |      59,152,120 |        +1.08% |           101.49% | pass        |
| original empty miss              |     31,837,974 |      32,468,541 |        -1.94% |           101.27% | median fail |
| unchanged repeat productive fast |     60,036,880 |      59,944,471 |        +0.15% |           102.11% | pass        |
| unchanged repeat empty miss      |     31,151,489 |      32,244,255 |        -3.39% |            98.18% | median fail |

The repeat was permitted by the predeclared Phase 7 rule for an inconclusive overhead result. It
confirmed that the miss-path median loss was not a one-run artifact. The two losses consume a
substantial fraction of the Phase 8 5.668-percent critical branch payoff even though the lowest-fork
gate passed.

One narrow correctness-preserving optimization was tested after the repeated failure: an already
nonproductive handle skipped the extra stop-disambiguation probe because both another empty result
and a stop-rejected result preserve the same nonproductive belief. The full Core conformance suite
passed, but the full overhead run worsened to -1.64 percent on the productive median and -4.75
percent on the miss median; its lowest-fork ratios were 98.09 and 96.26 percent. The optimization
was reverted. No threshold, sensor state transition, or benchmark gate was changed.

Across the two retained overhead runs, aggregate dominance was 0.50011-0.50255 for productive
controls and 0.50133-0.51988 for empty-miss controls. Both workers remained present and productive,
the empty queue remained live and incomplete, and the repeating source accounted for all pulled
frames. The overhead failure is not explained by lost execution participation.

### Raw evidence and exact commands

Generated evidence remains outside source control:

```text
benchmarks/build/reports/phase9-normal-smoke.json
benchmarks/build/reports/phase9-normal-smoke.log
benchmarks/build/reports/phase9-sensor-overhead-smoke.json
benchmarks/build/reports/phase9-sensor-overhead-smoke.log
benchmarks/build/reports/phase9-normal-policy.json
benchmarks/build/reports/phase9-normal-policy.log
benchmarks/build/reports/phase9-forced-winners.json
benchmarks/build/reports/phase9-forced-winners.log
benchmarks/build/reports/phase9-sensor-overhead.json
benchmarks/build/reports/phase9-sensor-overhead.log
benchmarks/build/reports/phase9-sensor-overhead-repeat.json
benchmarks/build/reports/phase9-sensor-overhead-repeat.log
benchmarks/build/reports/phase9-sensor-overhead-optimized.json
benchmarks/build/reports/phase9-sensor-overhead-optimized.log
```

The full normal command was:

```text
mise exec -- java -XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dlogback.configurationFile=benchmark-logback.xml \
  -cp 'benchmarks/build/euhedral-benchmark.jar:benchmarks/build/lib/*' \
  org.openjdk.jmh.Main \
  'io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmark.productiveHandleNormalPolicy' \
  -p policyCase=TWO_PRODUCTIVE_EXPENSIVE,ONE_PRODUCTIVE_EXPENSIVE,TWO_LIVE_ONE_PRODUCTIVE_EXPENSIVE,TWO_LIVE_ONE_PRODUCTIVE_CHEAP \
  -p handleLayout=NATURAL -f 3 -wi 3 -w 3s -i 5 -r 5s -tu s -foe true \
  -rf json -rff benchmarks/build/reports/phase9-normal-policy.json \
  -jvmArgsAppend '-XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED' \
  > benchmarks/build/reports/phase9-normal-policy.log 2>&1
```

The matching forced command was:

```text
mise exec -- java -XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dlogback.configurationFile=benchmark-logback.xml \
  -cp 'benchmarks/build/euhedral-benchmark.jar:benchmarks/build/lib/*' \
  org.openjdk.jmh.Main \
  'io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmark.productiveHandleForcedWinner' \
  -p policyCase=TWO_PRODUCTIVE_EXPENSIVE,ONE_PRODUCTIVE_EXPENSIVE,TWO_LIVE_ONE_PRODUCTIVE_EXPENSIVE \
  -p handleLayout=NATURAL -f 3 -wi 3 -w 3s -i 5 -r 5s -tu s -foe true \
  -rf json -rff benchmarks/build/reports/phase9-forced-winners.json \
  -jvmArgsAppend '-XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED' \
  > benchmarks/build/reports/phase9-forced-winners.log 2>&1
```

The original overhead command was:

```text
mise exec -- java -XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dlogback.configurationFile=benchmark-logback.xml \
  -cp 'benchmarks/build/euhedral-benchmark.jar:benchmarks/build/lib/*' \
  org.openjdk.jmh.Main \
  'io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmark.productiveHandleSensorOverhead' \
  -p overheadCase=PRODUCTIVE_FAST,EMPTY_MISS \
  -p productiveObservation=ENABLED,LIVENESS_ONLY -p handleLayout=NATURAL \
  -f 3 -wi 3 -w 3s -i 5 -r 5s -tu s -foe true \
  -rf json -rff benchmarks/build/reports/phase9-sensor-overhead.json \
  -jvmArgsAppend '-XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED' \
  > benchmarks/build/reports/phase9-sensor-overhead.log 2>&1
```

The unchanged repeat and reverted-optimization run used the identical command with only the report
and log basenames changed to `phase9-sensor-overhead-repeat` and
`phase9-sensor-overhead-optimized`.

### Final verification

After reverting the production selector and attempted miss optimization:

```text
mise exec -- gradle :euhedral-core:test --no-daemon                                      PASS
mise exec -- gradle :benchmarks:test --no-daemon                                         PASS
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck --no-daemon    PASS
mise exec -- gradle build --no-daemon                                                     PASS
git diff --check                                                                          PASS
```

The retained source changes are benchmark-only reproducibility support, this plan, and this
completion record. The productive-opportunity relationship was not contradicted: temporary normal
selection matched every forced winner and incurred negligible selector overhead. The blocker is the
validated sensor's real empty-miss cost under the predeclared gate. Production remains on the
live-handle root, and this phase does not proceed to the next research point.
