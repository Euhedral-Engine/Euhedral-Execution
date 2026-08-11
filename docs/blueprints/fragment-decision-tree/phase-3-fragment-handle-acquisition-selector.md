# Fragment Handle Acquisition Selector Blueprint

## Status

- Date: 2026-08-10
- Source plan: [
  `startup-fragment-path-calibration.md`](../../plans/fragment-decision-tree/phase-1-startup-fragment-path-calibration.md)
- Prior blueprint: [
  `phase-2-fragment-worker-participation-discovery.md`](./phase-2-fragment-worker-participation-discovery.md)
- Blueprint intensity: high
- Implementation intensity: medium
- Scope: benchmark-only handle acquisition diagnostics, followed by one narrowly scoped Core
  correction only if the diagnostic proves the suspected handle-loss mechanism
- Investigative decision-tree work only. No production scheduler policy is approved.
- Recorded result: H5-H8 accepted; transient acquisition no longer deletes a live local handle.

## Objective

Explain why the plentiful two-source DIRECT fixture initializes into either one productive worker or
two productive workers. Identify the smallest measurable source/handle condition that selects the
participation regime, then deliberately manipulate that condition. Do not add a scheduler branch in
this phase.

If the experiment shows that a failed transient acquisition permanently removes a still-live handle
from one worker's local upstream queue and that this loss selects the low DIRECT regime, correct
that queue behavior and rerun the same experiment. This conditional correction is a handle-lifecycle
fix, not an adaptive policy.

The pass succeeds when it either:

1. proves the handle-loss mechanism, fixes it, and shows that the unexplained DIRECT/plentiful split
   is removed; or
2. falsifies handle acquisition/assignment as the selector and records the next single variable to
   investigate.

## Established decision-tree state

Phase 2 proved this intermediate behavior:

```text
DIRECT
    |
    +-- one productive worker -> low-throughput regime
    |
    +-- two productive workers -> high-throughput regime
```

For fixed 225 ns CPU work, low forks run near 4.25 million frames/s with `D = 1.0`, while high forks
run near 8.5 million frames/s with `D` near 0.5. The no-op workload has the same participation
split. One source always produces the low regime, but two sources permit both regimes. Nominal
source count therefore enables parallelism without selecting it.

STAGED remains a control, not the subject of this pass. Its CPU fixture is balanced for both source
shapes, while no-op STAGED/scarce is dominated. Do not open the workload interaction leaf unless the
same handle evidence directly resolves it.

## Code-grounded physical hypothesis

The current handle path contains a more specific candidate than generic startup ordering:

1. Both fragment workers register their owner-local `UpstreamQueue` before the benchmark ingests
   sources.
2. `LatticeVertex.ingest` creates one shared `UpstreamInterceptor` per source.
3. `LatticeVertex.addUpstream` offers that same handle reference to every active worker-local queue.
   Sequential source ingestion gives both queues the same initial FIFO order.
4. `UpstreamQueue.pull` polls one local handle and calls `handle.acquireLock()`.
5. A successful acquisition drains the handle, releases it, and reinserts it into that worker's
   queue.
6. A failed acquisition advances to the next handle without reinserting the failed handle.

The final behavior is observable in `UpstreamQueue.pull`; it is not yet proven to cause the measured
regimes. With two workers and two identically ordered handles, however, an early collision can
remove a source from the losing worker's local queue. Repeated losses can settle into either:

```text
split service coverage
    worker 0 -> handle 0
    worker 1 -> handle 1

concentrated service coverage
    worker 0 -> handle 0 + handle 1
    worker 1 -> no retained productive handle
```

Because a failed handle is not restored, either mapping can remain stable for the fork. This is the
first mechanism to falsify. Do not instrument routing, caches, request timing, or worker startup
order in parallel with it.

## Hypotheses

- H5: Low and high plentiful DIRECT forks have materially different worker-to-handle acquisition or
  productive-service matrices.
- H6: In a low fork, the unproductive worker records failed acquisition attempts followed by no
  productive pulls for the lost handles, while the dominant worker services all useful handles.
- H7: In a high fork, both workers productively pull at least one distinct handle and retain that
  service coverage through the measurement iterations.
- H8: Restoring a live handle to the local queue after a failed transient acquisition prevents the
  startup mapping from permanently losing independently useful work and removes the low
  DIRECT/plentiful regime.

Reject H5 through H7 if low and high forks have materially identical acquisition and service
matrices. Reject H8 if the corrected queue still produces stable one-worker DIRECT/plentiful forks,
or if the diagnostic shows no local handle loss preceding domination.

## Benchmark-only observation design

Extend `FragmentPathCalibrationBenchmark`; retain its JMH selection, graph, worker CPUs, source
construction, fixed batch target, and completion-window accounting.

`DiagnosticDistributor` creates a diagnostic subclass of `LatticeVertex.UpstreamInterceptor` for
each benchmark source. It must use the normal `stream.addDownstream(interceptor)` and
`interceptor.addUpstream(stream)` connection and publication sequence. The natural fixture therefore
preserves production handle insertion and acquisition behavior. Assign each source a stable
zero-based benchmark ordinal and also retain the interceptor's actual handle ID.

A preallocated `HandleAcquisitionRecorder` records by source ordinal and worker logical CPU:

- acquisition attempts;
- failed acquisitions;
- frames returned by successful pulls; and
- the first productive-pull order for each worker/source pair.

Override `acquireLock()` only to call `super.acquireLock()` and update the attempt/failure cells.
Override `pull()` only to call `super.pull(...)`, add its returned frame count, and claim the
first-productive order when the result is positive. Never change the returned result, lock lifetime,
request behavior, source behavior, or queue publication in the observational fixture.

Use preallocated padded counters indexed by logical CPU for acquisition and frame totals. Use one
low-frequency atomic sequence only for the first productive event in each of the four worker/source
cells. Resolve the worker from the already-present owner-local `UpstreamQueue.UP_QUEUE` and its
core; do not perform a topology lookup, allocation, formatting, logging, timer read, or lock
operation on each acquisition.

The diagnostic runs once per upstream acquisition, not once per frame. The existing executor
completion counter remains the authority for throughput and participation. If instrumented natural
forks no longer reproduce both established regimes, treat instrumentation perturbation as a failed
experiment and return to design rather than accepting its mapping.

## Snapshot and report format

Take acquire snapshots only at JMH lifecycle boundaries:

- the cumulative state before the first warmup iteration, capturing activity between ingest and the
  first iteration;
- one delta for each of the three warmup iterations; and
- one delta for each of the five measurement iterations.

Report one fork record containing:

- JMH throughput;
- worker CPU and core identities;
- source ordinal, handle ID, and ingest ordinal;
- the existing raw completion deltas, `f0`, `f1`, `D`, and `L`;
- acquisition-attempt matrix by iteration;
- acquisition-failure matrix by iteration;
- productive pulled-frame matrix by iteration;
- first productive worker and first productive order for every source; and
- the final aggregate matrices.

For source `s` and worker `w`, define productive service fraction:

```text
p[w,s] = pulledFrames[w,s] / sumWorkers(pulledFrames[*,s])
```

Classify one handle as worker dominated when one worker supplies at least 90 percent of its pulled
frames. Classify the mapping as stable when each handle retains the same dominant/shared
classification in the final warmup iteration and all five measurement iterations. Preserve the raw
values even when a classification is clear.

Do not average low and high forks together. Pair the JMH fork result with its exact diagnostic
record.

## Experimental sequence

### Stage A: natural acquisition observation

Run only DIRECT/plentiful CPU first, using the established protocol. Collect at least three low and
three high forks. Run additional independent three-fork groups only when one regime is undersampled;
stop after twelve total forks and report inconclusive if the instrumentation prevents observing both
regimes.

Accept H5 through H7 only when all sampled forks obey the following fork-level relationship:

- low: `D >= 0.9`, one worker supplies at least 90 percent of frames from every useful handle, and
  the other worker's failed attempts precede absent productive service;
- high: `D <= 0.6`, both workers have positive productive pulls from distinct source ordinals, and
  the mapping is stable by the final warmup iteration; and
- no low fork has the high mapping and no high fork has the low mapping.

If this relationship fails, stop. Do not run a handle-order perturbation or change Core. Record
source ingest/insertion order as the next bounded hypothesis.

### Stage B: deterministic acquisition perturbation

Run this stage only if Stage A supports H5 through H7. Perturb the smallest property exhibited by
the raw evidence:

- Keep `NATURAL` as the unchanged production-shaped baseline, where each source becomes globally
  visible immediately after its sequential ingest.
- Add a `BATCH_ALIGNED` control that constructs both handles, fills both worker queues in the same
  `[source 0, source 1]` order, and publishes the global source count only after the complete layout
  exists. This separates complete publication from per-worker ordering.
- Add one `PHASED` layout with the same batched publication boundary: worker 0 receives
  `[source 0, source 1]`; worker 1 receives `[source 1, source 0]`.
- Do not add another layout.

The custom layout is setup-only and may access the protected upstream partitions from
`DiagnosticDistributor`; it must still create the same two interceptors, increment the global source
count once per source, and retain the same sources, frames, hashes, workers, and lifecycle. It is
not a production API or runtime option.

The incomplete-visibility/acquisition mechanism is causally supported when all three
`BATCH_ALIGNED` and all three `PHASED` forks are balanced with `D <= 0.6`, both workers retain
productive handle coverage, and the established low mapping is absent. If `PHASED` succeeds but
`BATCH_ALIGNED` does not, initial order remains the selector. If both succeed, complete publication
before acquisition is sufficient and opposite order is not uniquely causal. If neither succeeds,
reject this mechanism and stop before a Core change.

### Stage C: conditional Core correction

Run this stage only when Stage A shows permanent local handle loss and Stage B shows that avoiding
the initial collision removes the low regime.

In `UpstreamQueue.pull`, reinsert a non-complete handle into the same owner-local queue when
`acquireLock()` fails, just as the successful path eventually reinserts it. Count the failed attempt
toward the existing bounded `cycles` limit so unavailable handles cannot cause an unbounded retry.
Do not release a lock the caller did not acquire, change `UpstreamInterceptor.wip`, strengthen
memory accesses, add backoff, change bucket sizing, or alter completion removal.

Add a deterministic `UpstreamQueueTest` proving that:

1. an unavailable live handle remains locally available after a failed pull attempt;
2. it can be acquired and drained on a later call; and
3. repeated unavailable handles remain bounded by the current cycle count.

Every new or changed class/method/signature receives an adjacent `///` declaration comment as
required by the repository workflow.

### Stage D: post-correction validation

Rerun the natural DIRECT/plentiful CPU fixture with at least three forks. H8 is accepted only if all
forks are balanced (`D <= 0.6`), throughput is in the established high regime, both workers retain
productive access, and no worker-local handle disappears after failed acquisition.

Then run the bounded controls, without recreating the full Phase 2 matrix:

- DIRECT/scarce CPU: verifies one shared handle remains serialized at each instant and detects any
  participation/throughput change caused by retrying it;
- STAGED/plentiful CPU: verifies the known balanced control is not regressed; and
- DIRECT/plentiful no-op: confirms the fix is not specific to the 225 ns body.

Use three forks for each control with the established three 3-second warmups and five 5-second
measurements. Any control behavior change is evidence to report, not a reason to tune another
variable in this pass.

## Fixed controls

Keep unchanged:

- two same-kind workers on one socket, pinned to logical CPUs 0 and 6 on the recorded host;
- one source per worker for plentiful and one source for the scarce control;
- fixed 225 ns CPU and no-op executor bodies;
- fixed batch target 32;
- 1,048,576-frame completion windows;
- existing unordered routing hashes and source frame pools;
- three 3-second warmups and five 5-second measurements;
- forced DIRECT/STAGED modes; and
- graph setup, drain publication, worker registration, and teardown ordering.

Do not rerun STAGED/scarce no-op or the isolated overhead methods unless a specific regression makes
one necessary.

## Stop conditions

Stop and return to the next blueprint when:

- natural low and high mappings are materially identical;
- failed acquisition does not precede lost productive access;
- neither complete-publication layout removes the low mapping reproducibly;
- observation prevents the established regimes from appearing;
- the correction leaves any stable low DIRECT/plentiful fork;
- a safe correction would require shared coordination, a new ownership protocol, per-frame state, or
  production logging; or
- source ordering, worker startup, and acquisition timing remain entangled after the one permitted
  perturbation.

If H5 through H7 fail, the next leaf is source ingest/insertion order with acquisition held
observationally fixed. If H8 fails after the correction, the next leaf is the initial worker that
pulls each source. Do not advance to routing, cache state, request timing, distributor order, or
worker startup order in this pass.

## Decision-tree interpretation

If H8 passes, the experiment justifies this physical branch for continued design work:

```text
independently useful upstream handles remain reachable by available workers?
    |
    +-- no  -> DIRECT cannot use all workers; Tier 2 remains a candidate
    |
    +-- yes -> continue evaluating the work-cost/path-overhead leaf
```

The Core correction should make accidental loss of a live handle stop selecting the `no` branch. It
does not prove that every source topology can feed every worker, and it does not authorize a runtime
scheduler decision based on completion imbalance.

No decision-tree branch is justified if controlled acquisition/order manipulation fails. In that
case retain only the established participation branch and name the next unresolved physical leaf.

## Verification and completion record

Before handoff run:

-
`mise exec -- gradle :euhedral-core:test --tests io.euhedral_execution.core.flow_control.UpstreamQueueTest`
-
`mise exec -- gradle :benchmarks:test --tests io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmarkTest`
- `mise exec -- gradle :euhedral-core:test :benchmarks:test :benchmarks:assemble`
- `git diff --check`
- `git status --short`

Append completion notes with the environment, exact JMH commands, every raw fork-level matrix,
throughput and participation values, H5-H8 verdicts, the correction (if authorized by the evidence),
control changes, and environmental limits. State explicitly:

- what selected the natural low/high mapping;
- what was falsified;
- whether the correction removed the unexplained split;
- whether a new physical decision-tree branch is justified; and
- the exact unresolved leaf to investigate next.

## Completion notes

Implemented and measured on 2026-08-10. The handle diagnostic is confined to the benchmark module.
The only Core behavior change is in `UpstreamQueue.pull`: a live handle that loses a transient
`acquireLock()` attempt is returned to the same owner-local queue before the existing cycle count
advances. The cycle bound, successful lock/release path, completion removal, bucket sizing, and
memory access modes are unchanged.

Changed files:

-
`benchmarks/src/main/java/io/euhedral_execution/core/control_plane/FragmentPathCalibrationBenchmark.java`
-
`benchmarks/src/test/java/io/euhedral_execution/core/control_plane/FragmentPathCalibrationBenchmarkTest.java`
- `euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/UpstreamQueue.java`
- `euhedral-core/src/test/java/io/euhedral_execution/core/flow_control/UpstreamQueueTest.java`
- this blueprint

### Environment and protocol

- CPU: Intel Core i9-14900K, x86-64, one socket, 24 physical cores, 32 logical CPUs
- Effective process CPUs: 0-31; diagnostic workers: logical CPUs 0 and 6 on physical cores 0 and 1
- Cache: 36 MiB shared L3
- JVM: OpenJDK 64-Bit Server VM 21.0.2+13-58
- JMH: 1.37, three forks per group, three 3-second warmups, five 5-second measurements
- Batch target: 32; completion window: 1,048,576 frames
- CPU lane ceiling for `L`: 4,444,444 frames/s from the fixed 225 ns body
- No-op DIRECT lane ceiling for `L`: 88,797,000 frames/s from Phase 1

After `mise exec -- gradle :benchmarks:assemble`, each row used this command shape, with the listed
method and parameter values substituted:

```text
mise exec -- java -XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dlogback.configurationFile=benchmark-logback.xml \
  -cp 'benchmarks/build/euhedral-benchmark.jar:benchmarks/build/lib/*' \
  org.openjdk.jmh.Main \
  'io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmark.<method>' \
  -p mode=<mode> -p sourceShape=<shape> -p handleLayout=<layout> \
  -f 3 -wi 3 -w 3s -i 5 -r 5s -tu s -foe true \
  -jvmArgsAppend '<the same JVM flags above>'
```

The natural pre-fix primary ran three independent three-fork groups to obtain at least three samples
from each regime. `BATCH_ALIGNED` and `PHASED` ran in one six-fork JMH invocation. Post-fix rows
each ran three forks.

### Stage A: natural pre-fix acquisition evidence

Matrices are source-major with worker columns `[worker 0, worker 1]`. First-event order is flattened
as `[source0/worker0, source0/worker1, source1/worker0, source1/worker1]`; `-1` means that pair
never made a productive pull. Throughput is the mean of the five matching JMH measurements.
Attempts, failures, pulled frames, and completion deltas are sums across the complete fork.

| Fork | Throughput | Completion deltas       |        D |        L | Attempts                    | Failures        | Pulled frames                   | First order   |
|-----:|-----------:|-------------------------|---------:|---------:|-----------------------------|-----------------|---------------------------------|---------------|
|   N1 |  8,513,945 | `[107349840,107613117]` | 0.500612 | 1.915731 | `[[1,4594314],[4583168,1]]` | `[[1,0],[0,1]]` | `[[0,147017956],[146661284,0]]` | `[-1,1,0,-1]` |
|   N2 |  8,476,615 | `[107413524,107549760]` | 0.500317 | 1.907338 | `[[1,4575048],[4569506,1]]` | `[[1,0],[0,1]]` | `[[0,146401444],[146224100,0]]` | `[-1,1,0,-1]` |
|   N3 |  8,511,988 | `[107601017,107361943]` | 0.500556 | 1.915286 | `[[1,4565264],[4579571,2]]` | `[[1,0],[0,1]]` | `[[0,146088386],[146546212,2]]` | `[-1,0,2,1]`  |
|   N4 |  4,244,585 | `[0,110102491]`         | 1.000000 | 0.955067 | `[[3,2343609],[1,2343609]]` | `[[1,0],[1,0]]` | `[[4,74995458],[0,74995458]]`   | `[0,1,-1,2]`  |
|   N5 |  8,489,918 | `[107137552,107825777]` | 0.501601 | 1.910322 | `[[4574607,1],[1,4603006]]` | `[[0,1],[1,0]]` | `[[146387332,0],[0,147296100]]` | `[0,-1,-1,1]` |
|   N6 |  4,231,930 | `[110102536,0]`         | 1.000000 | 0.952221 | `[[2343633,1],[2343633,2]]` | `[[0,1],[0,1]]` | `[[74996226,0],[74996194,2]]`   | `[0,-1,2,1]`  |
|   N7 |  4,257,560 | `[0,110102229]`         | 1.000000 | 0.957982 | `[[1,2359875],[2,2359874]]` | `[[1,0],[1,0]]` | `[[0,75515938],[2,75515938]]`   | `[-1,1,0,2]`  |
|   N8 |  8,545,349 | `[107394261,107569146]` | 0.500407 | 1.922804 | `[[4585072,1],[1,4592517]]` | `[[0,1],[1,0]]` | `[[146722212,0],[0,146960452]]` | `[0,-1,-1,1]` |
|   N9 |  4,263,291 | `[0,110102356]`         | 1.000000 | 0.959275 | `[[1,2359993],[2,2359992]]` | `[[1,0],[1,0]]` | `[[0,75519714],[2,75519714]]`   | `[-1,1,0,2]`  |

All five high forks lost one different handle per worker and then serviced one source per worker for
every later warmup and measurement interval. All four low forks show the losing worker fail once on
both sources, make no later acquisition attempt, and complete effectively no later work. The winning
worker retained and alternated both sources. No low fork had the high mapping and no high fork had
the low mapping.

### Stage B: complete-publication and order perturbation

Both custom layouts constructed the same two handles and filled both queues while the global source
count remained zero. `BATCH_ALIGNED` used the same source order in both queues; `PHASED` used
opposite orders. Publishing the count after both queues were complete produced six balanced forks.

| Layout/fork | Throughput | Completion deltas       |        D | Attempts                    | Failures        | Pulled frames                   | First order   |
|-------------|-----------:|-------------------------|---------:|-----------------------------|-----------------|---------------------------------|---------------|
| B1          |  8,534,153 | `[107226617,107737118]` | 0.501187 | `[[1,4599693],[4577763,1]]` | `[[1,0],[0,1]]` | `[[0,147190114],[146488354,0]]` | `[-1,1,0,-1]` |
| B2          |  8,474,404 | `[107282453,107680483]` | 0.500926 | `[[4564449,1],[1,4580233]]` | `[[0,1],[1,0]]` | `[[146062338,0],[0,146567426]]` | `[0,-1,-1,1]` |
| B3          |  8,457,251 | `[107354111,107608824]` | 0.500592 | `[[4566917,1],[1,4577733]]` | `[[0,1],[1,0]]` | `[[146141282,0],[0,146487394]]` | `[0,-1,-1,1]` |
| P1          |  8,505,019 | `[107217619,107745467]` | 0.501228 | `[[2,4599422],[4578064,2]]` | `[[1,0],[0,1]]` | `[[2,147181504],[146498016,2]]` | `[1,3,2,0]`   |
| P2          |  8,344,219 | `[103464932,106255607]` | 0.506653 | `[[2,4519211],[4396246,2]]` | `[[1,0],[0,1]]` | `[[2,144614752],[140679840,2]]` | `[0,2,3,1]`   |
| P3          |  8,502,671 | `[107083306,107880063]` | 0.501853 | `[[2,4605927],[4571954,2]]` | `[[1,0],[0,1]]` | `[[2,147389632],[146302496,2]]` | `[0,2,3,1]`   |

Opposite order is therefore not uniquely causal: same-order complete publication also eliminates the
low state. The supported mechanism is incremental visibility plus destructive failed acquisition.
Which source identity appears first, its routing hash, and opposite versus equal complete queue
order are falsified as complete selectors.

### Stage C: correction and deterministic regression

The Core correction restores a polled live handle to its local queue when acquisition fails. The
focused test first makes one handle unavailable, verifies that one bounded pull leaves the queue
size at one, makes the handle available, and verifies that the next pull drains it. A second test
places two unavailable handles in the queue and verifies one attempt per handle and a final queue
size of two. No caller releases an unacquired lock.

### Stage D: post-fix results

Aggregate JMH results:

| Workload | Mode   | Sources   | Mean frames/s | 99.9% error | Verdict                                         |
|----------|--------|-----------|--------------:|------------:|-------------------------------------------------|
| CPU      | DIRECT | plentiful |     8,357,379 |      20,109 | balanced high in 3/3 forks                      |
| CPU      | DIRECT | scarce    |     6,994,073 |      92,960 | balanced in 3/3; materially faster than pre-fix |
| CPU      | STAGED | plentiful |     7,404,907 |     103,324 | balanced control retained                       |
| CPU      | STAGED | scarce    |     7,640,157 |      23,340 | balanced; faster than DIRECT/scarce             |
| no-op    | DIRECT | plentiful |   128,402,117 |   1,035,419 | balanced high in 3/3 forks                      |

Raw post-fix participation evidence:

| Fixture/fork             |  Throughput | Completion deltas         | f0 / f1             |        D |        L |
|--------------------------|------------:|---------------------------|---------------------|---------:|---------:|
| DIRECT plentiful CPU 1   |   8,353,457 | `[104772383,104950324]`   | 0.499576 / 0.500424 | 0.500424 | 1.879652 |
| DIRECT plentiful CPU 2   |   8,365,940 | `[104898087,104822626]`   | 0.500180 / 0.499820 | 0.500180 | 1.882439 |
| DIRECT plentiful CPU 3   |   8,352,740 | `[105168830,104554717]`   | 0.501464 / 0.498536 | 0.501464 | 1.879525 |
| DIRECT scarce CPU 1      |   6,906,502 | `[86369548,86649669]`     | 0.499190 / 0.500810 | 0.500810 | 1.554038 |
| DIRECT scarce CPU 2      |   6,969,376 | `[89334149,88928540]`     | 0.501138 / 0.498862 | 0.501138 | 1.568189 |
| DIRECT scarce CPU 3      |   7,106,340 | `[89103082,89158950]`     | 0.499843 / 0.500157 | 0.500157 | 1.598995 |
| STAGED plentiful CPU 1   |   7,530,182 | `[94356926,94390881]`     | 0.499910 / 0.500090 | 0.500090 | 1.694370 |
| STAGED plentiful CPU 2   |   7,375,641 | `[94238776,94508915]`     | 0.499284 / 0.500716 | 0.500716 | 1.659588 |
| STAGED plentiful CPU 3   |   7,308,897 | `[92342477,92210661]`     | 0.500357 / 0.499643 | 0.500357 | 1.644601 |
| STAGED scarce CPU 1      |   7,617,981 | `[96980782,97010374]`     | 0.499924 / 0.500076 | 0.500076 | 1.714127 |
| STAGED scarce CPU 2      |   7,635,022 | `[96983191,97008348]`     | 0.499935 / 0.500065 | 0.500065 | 1.717969 |
| STAGED scarce CPU 3      |   7,667,468 | `[96967797,97023459]`     | 0.499857 / 0.500143 | 0.500143 | 1.725263 |
| DIRECT plentiful no-op 1 | 128,506,464 | `[1590588803,1625729403]` | 0.494537 / 0.505463 | 0.505463 | 1.447412 |
| DIRECT plentiful no-op 2 | 127,925,822 | `[1596957235,1604675644]` | 0.498795 / 0.501205 | 0.501205 | 1.440869 |
| DIRECT plentiful no-op 3 | 128,774,065 | `[1610843964,1609676530]` | 0.500181 / 0.499819 | 0.500181 | 1.450433 |

Raw post-fix handle matrices, again source-major with worker columns:

| Fixture/fork             | Attempts                                    | Failures                                | Pulled frames                                       | First order |
|--------------------------|---------------------------------------------|-----------------------------------------|-----------------------------------------------------|-------------|
| DIRECT plentiful CPU 1   | `[[4258748,4271924],[4258748,4271923]]`     | `[[2158756,1913771],[1907729,2165870]]` | `[[67198605,75459819],[75231518,67392338]]`         | `[2,0,1,3]` |
| DIRECT plentiful CPU 2   | `[[4180040,4177870],[4180040,4177869]]`     | `[[2220062,1680230],[1681246,2218918]]` | `[[62717747,79922883],[79959840,62684946]]`         | `[1,0,3,2]` |
| DIRECT plentiful CPU 3   | `[[4259870,4234748],[4259870,4234747]]`     | `[[2099386,1938037],[1950117,2086270]]` | `[[69134396,73493607],[73911046,68750111]]`         | `[2,1,0,3]` |
| DIRECT scarce CPU 1      | `[[41475673,41616488]]`                     | `[[38445924,38704921]]`                 | `[[48974072,47396914]]`                             | `[1,0]`     |
| DIRECT scarce CPU 2      | `[[38086551,41110479]]`                     | `[[35012570,38216605]]`                 | `[[50447609,45060107]]`                             | `[1,0]`     |
| DIRECT scarce CPU 3      | `[[34645755,33760004]]`                     | `[[31648194,30767154]]`                 | `[[46204076,46751910]]`                             | `[1,0]`     |
| STAGED plentiful CPU 1   | `[[5295902,4787876],[5295901,4787875]]`     | `[[1415783,1191770],[1423091,1146057]]` | `[[30814640,32711168],[32706208,30833632]]`         | `[0,2,3,1]` |
| STAGED plentiful CPU 2   | `[[3969796,4150680],[3969795,4150679]]`     | `[[325191,372532],[337794,373511]]`     | `[[29984109,31942505],[30902443,30967168]]`         | `[3,2,1,0]` |
| STAGED plentiful CPU 3   | `[[4168319,4058204],[4168319,4058202]]`     | `[[450302,444950],[448243,447090]]`     | `[[31333440,29519666],[30503373,30405980]]`         | `[0,3,2,1]` |
| STAGED scarce CPU 1      | `[[10125901,10218468]]`                     | `[[5200006,5382893]]`                   | `[[33941952,32425348]]`                             | `[0,1]`     |
| STAGED scarce CPU 2      | `[[10409705,9813068]]`                      | `[[5445975,4267659]]`                   | `[[28693880,38746116]]`                             | `[1,0]`     |
| STAGED scarce CPU 3      | `[[7313205,7131430]]`                       | `[[2557072,2292318]]`                   | `[[32286152,33733760]]`                             | `[0,1]`     |
| DIRECT plentiful no-op 1 | `[[34914162,35703391],[34914162,35703390]]` | `[[824271,1203963],[982264,1330254]]`   | `[[1090874024,1103967794],[1085818482,1099922748]]` | `[1,2,3,0]` |
| DIRECT plentiful no-op 2 | `[[34302814,34843537],[34302814,34843536]]` | `[[695292,861608],[500044,857071]]`     | `[[1075439388,1087415517],[1081686707,1087563324]]` | `[3,1,0,2]` |
| DIRECT plentiful no-op 3 | `[[34971251,34928467],[34971250,34928467]]` | `[[711668,712446],[852469,800276]]`     | `[[1096302871,1094906659],[1091799027,1092097403]]` | `[0,3,2,1]` |

After the fix, every worker continues attempting every live handle and every worker/source pair is
productive. Failed acquisitions are numerous under contention but no longer alter handle
reachability. DIRECT/scarce changes from the Phase 2 one-worker state near 4.25 million frames/s to
balanced 6.99 million frames/s; a single handle is still mutually exclusive at each instant, but
retries and DIRECT fallback requests allow both workers to contribute through the complete graph.

### Verdict and next decision-tree leaf

- H5: **accepted**. Natural low and high forks have different handle-service mappings in lockstep
  with participation and throughput.
- H6: **accepted**. Each low fork contains exactly the destructive signature: the losing worker
  fails both handles, never attempts either again, and performs no measured completions.
- H7: **accepted**. Each high fork retains a distinct productive handle per worker through every
  later iteration.
- H8: **accepted**. Restoring a failed live handle eliminates the one-worker DIRECT/plentiful state
  in all three CPU and all three no-op validation forks. Both workers retain all handles.
- Falsified: opposite per-worker handle order is not the unique selector. Complete same-order
  publication is also sufficient. Source identity and routing hash do not explain the split.

The unexplained participation branch was caused by a queue-lifecycle defect, not a scheduler choice.
The correction removes that accidental selector. A new physical decision-tree branch is nevertheless
justified for the fixed 225 ns workload because independently pullable handle availability now
reproducibly reverses the tier winner:

```text
independently pullable upstream handles < available workers
    -> STAGED wins: 7.640M vs DIRECT 6.994M frames/s

independently pullable upstream handles >= available workers
    -> DIRECT wins: 8.357M vs STAGED 7.405M frames/s
```

This records a candidate branch only; no production policy was added. The exact next unresolved leaf
is workload cost under `independently pullable handles < available workers`. The next bounded
experiment should rerun post-fix DIRECT/scarce and STAGED/scarce at no-op and one predeclared
intermediate CPU cost to determine whether request/cache staging has a reproducible crossover. Do
not expand routing, cache occupancy, request timing, or worker startup variables until that leaf is
resolved.

### Verification

-
`mise exec -- gradle :euhedral-core:test --tests io.euhedral_execution.core.flow_control.UpstreamQueueTest`
passed.
-
`mise exec -- gradle :benchmarks:test --tests io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmarkTest`
passed.
- `mise exec -- gradle :euhedral-core:test :benchmarks:test :benchmarks:assemble` passed.
- `mise exec -- gradle build` passed, including repository Spotless checks and all module tests.
- The pre-fix natural observation, complete-publication perturbations, post-fix primary, and all
  stated CPU/no-op controls completed under the declared JMH protocol.
- Stale-reference review found only the intentional pre-fix explanation and the final layout names;
  `git diff --check` is clean.
