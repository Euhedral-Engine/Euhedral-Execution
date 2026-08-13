# Phase 11: Idle-Mechanic Decision-Tree Discovery

Status: complete

Plan:
[
`phase-11-idle-mechanic-decision-tree-discovery.md`](../../plans/fragment-decision-tree/phase-11-idle-mechanic-decision-tree-discovery.md)

Prior evidence:

- [`phase-10-flow-thread-productive-observation.md`](phase-10-flow-thread-productive-observation.md)
- [`phase-4-fragment-work-cost-surface.md`](phase-4-fragment-work-cost-surface.md)

Blueprint intensity: maximum

Implementation intensity: high

## Decision and boundaries

Discover the physical eligibility branch before designing a waiting mechanic. Production remains
unchanged. The benchmark will vary active polling workers, productive repeating handles, and the
existing calibrated arithmetic body while observing normal production selection. It will use one
fixed diagnostic park only after an all-active row passes the harmful-regime gate.

Do not change `FragmentControlPolicy.selectMode`, productive-handle observation, body sampling or
aggregation, the 90/95 ns bounds, batch sizing, source semantics, caches, routing, registration, or
topology lifecycle. Do not add a throughput controller, EWMA, miss counter, idle framework, adaptive
wait, wake policy, or numeric production threshold.

## Existing FlowRecorder inventory

Each recorder is plain, fragment-worker-owned state. The default maximum interval discontinuity is
10 ms and the exponential alpha is 0.05. `recordProgress` supplies an already-read `nowNs`, so the
two active recorders add no separate clock read. After initialization, one update performs count and
field stores, division, three EWMA/variance/trend groups, extrema comparisons, and three square
roots while decaying extrema. This cost is already paid on productive production loops.

| Existing state                              | Physical meaning and form                                                                                                                | Idling sensitivity                                                                            | Idle-eligibility value                                                                                                                                                  |
|---------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `lastRecordedUnits`                         | Last units argument; frames for throughput, ns/frame for service. Instantaneous.                                                         | Stops changing while parked; active-worker contention can alter it.                           | Frames per productive loop may show batch utilization; service units exclude empty polling.                                                                             |
| `lastInterval`                              | Nanoseconds between the last two productive recorder updates. Instantaneous.                                                             | Directly lengthens when useful progress becomes sparse; a gap over 10 ms resets the recorder. | Plausible local progress-spacing signal, but not a direct poll-cost measure.                                                                                            |
| `lastRecordingTime`                         | `nanoTime` timestamp of the last accepted update. Instantaneous.                                                                         | Becomes stale while parked or unproductive.                                                   | Useful only to interpret staleness; absolute values are not portable.                                                                                                   |
| `averageUnits`                              | EWMA of units with alpha 0.05 after initialization. Smoothed.                                                                            | Stops updating while parked.                                                                  | Throughput-recorder value approximates progress per productive cycle; service value approximates execution-path service cost.                                           |
| `averageInterval`                           | EWMA of productive-update spacing in ns. Smoothed.                                                                                       | Includes time spent polling between productive cycles and changes because of idling.          | Promising indirect useful-work intensity signal.                                                                                                                        |
| `averageUnitsOverTime`                      | EWMA of `units / interval`; frames/ns for throughput and `(ns/frame)/ns` for service. Smoothed.                                          | Changes directly when parking changes progress spacing.                                       | Throughput form is promising experimental evidence but is self-referential as a controller input. Service form has no clean standalone physical unit for this decision. |
| unit/interval/units-per-time variance       | Exponentially decayed squared residual about each updated mean. Smoothed, not a fixed window.                                            | Stops updating while parked; reacts to transitions and irregular progress.                    | Useful for stability/persistence checks, not a primary root unless it adds resolved separation.                                                                         |
| unit/interval/units-per-time trend          | EWMA of signed residual, updated only after nonzero variance. Smoothed.                                                                  | Reacts to arrival/participation transitions and then decays.                                  | Candidate transition evidence; reject if sign changes within a stable row.                                                                                              |
| standard deviations and CVs                 | Derived square root of each variance and normalized standard deviation. Smoothed derived views.                                          | Same sensitivity as their source moments.                                                     | Offline stability normalization only; no production field is needed.                                                                                                    |
| min/max units, interval, and units-per-time | Historical extrema pulled toward mean +/- 3 standard deviations when outside that band. Decaying extrema, not exact fixed-window bounds. | Can retain pre-idle history until later productive updates.                                   | Secondary stability bounds; unsuitable as the primary branch because lifecycle semantics are indirect.                                                                  |
| `rollingSum`                                | Rounded recurrence `(1-alpha) * prior + units`; neither cumulative work nor a conventional normalized EWMA.                              | Stops updating while parked.                                                                  | Reject as physically ambiguous and redundant with `averageUnits`.                                                                                                       |
| current/previous/effective window counts    | Productive update counts in current/previous nominal 10 ms windows; effective count is a time-weighted derived value. Windowed.          | Falls to zero when no update occurs for a window.                                             | Potential persistence context only; it counts recorder updates, not polls or completed frames.                                                                          |
| `measurementWindowNs`                       | Configured 10 ms discontinuity/window constant. Fixed configuration.                                                                     | Determines reset and count aging.                                                             | Context, not an observed workload signal.                                                                                                                               |
| `FlowSnapshot`                              | Mutable copy of means/extrema/variance/trend. Updated only on construction/reset unless explicitly requested.                            | Normally stale in current production use.                                                     | Do not use; benchmark reads the live existing fields through its diagnostic snapshot.                                                                                   |

Recorder roles:

- `throughputRecorder` receives `processed` once per productive fragment loop. It measures local
  progress batch size and spacing, not global throughput. It can indirectly include wasted polling
  through the interval before the next productive update.
- `serviceTimeRecorder` receives execution-path elapsed nanoseconds divided by frames when at least
  one timed execution path completed work. It excludes empty polling and is path-contaminated, so it
  cannot replace the validated executor-body estimate.
- `batchRecorder` is allocated and reset but never passed to `recordUnits`; no signal from it is
  paid beyond construction/reset and it cannot answer this phase.

All three recorders are worker-local. Meter publication is separate and occurs only at completed
batches when a registry exists. The experiment will read live recorder fields through the existing
package-private best-effort policy snapshot and will not enable Micrometer merely to observe them.

## Benchmark surface

Add `idleEligibilityDiscovery` with a state whose command-line parameters default to a single row:

```text
workerCount=1
productiveHandles=1
workRounds=0
emptyLiveHandles=0
activePollingWorkers=0  # zero means all registered workers poll
```

Validation rejects nonpositive worker/productive counts, productive handles above a bounded fixture
limit, negative work/empty counts, active polling above registration, and an active subset smaller
than one. Productive handles are real `RepeatingSink` instances. The optional empty handle is the
validated incomplete `QueueIngestSink`; it must remain live, empty, incomplete, and unoffered.

The retained matrix is staged rather than Cartesian:

1. `workers=1,2,4,8`, one productive handle, rounds 0, all polling;
2. at the smallest harmful worker count, productive handles `1`, approximately half the workers, and
   equal to workers, rounds 0;
3. at that smallest harmful worker/one-handle shape, rounds `0,24,256`, corresponding to prior
   isolated bodies near 0.353, 21.566, and 225.235 ns on this host;
4. one repeating plus one real empty-live source as a classification/lifecycle control if the
   retained shape has scarce productive opportunity; and
5. the decisive all-polling row versus the same registration/source/body graph with the supported
   useful subset polling and every other worker fixed parked.

Expand beyond these rows only if a transition lies between retained points. Do not use E-core
workers in this phase.

## Topology and affinity

`selectWorkerCores` must select one socket and one core kind, preferring P cores as today. Before
returning, compare every selected worker CPU's L2 mask with every other selected worker CPU's mask;
an intersection is a fixture error. SMT siblings on the same physical core are never separate
workers. Log worker core kind and L2 masks with the fork evidence.

On the i9-14900K, the retained 1/2/4/8 worker rows selected logical CPUs
`[0,6,8,10,12,14,2,4]` in that order. Their L2 masks were
`[3,c0,300,c00,3000,c000,c,30]`; all intersections were empty. These are the eight P cores in the
topology normalized by `SystemInfo`, even though the logical-CPU order is not numerical. The E-core
shared-L2 clusters were excluded. The harness pinned to a non-worker physical core.

## Diagnostic fixed parking

Extend the existing setup-only `FragmentControlPolicy.DiagnosticOverride` with an optional immutable
set of cores allowed to poll. A null set preserves every existing forced-mode benchmark. Install the
override before constructing fragments and clear the exact lease after all fragments close.

After registration but before normal cycling, a fragment excluded from that set enters a diagnostic
loop:

```text
while running:
    service reset request
    park indefinitely
```

Reset and close already unpark the owner thread. The worker remains registered, owns its normal
pipeline, and is reported in per-worker completion arrays, but performs no scheduler polling. This
behavior is reachable only through the package-private setup lease and has no duration, spin, yield,
backoff, wake cadence, or policy decision to tune.

The causal pair must use normal DIRECT selection at the decisive cheap row. Trapped staged/cache
work would confound a fixed inactive routing destination, so a non-DIRECT causal row is invalid for
this intervention rather than grounds for altering routing.

## Diagnostics and lifecycle

Extend `FragmentPolicySnapshot` with:

- whether this worker is allowed to poll;
- throughput-recorder average units, interval, units/time, three CVs, and three trends;
- service-recorder average units, interval, units/time, three CVs, and three trends.

These are reads of existing fields. Do not add writes to `recordProgress`, recorder instances, or
the execution loop. Snapshot reads remain best-effort diagnostics like the existing mode/body
snapshot and are sampled only from the JMH harness at iteration boundaries and teardown.

Retain per-iteration and fork-level:

- total throughput and raw fork means;
- per-worker completion deltas, fractions, and dominance;
- live/productive handle expectations and worker-local last observed productive counts;
- registered and configured polling worker counts;
- body history/estimate and selected mode;
- handle attempts, failures, productive pulls, and first participation;
- empty-source state where present; and
- raw warmup, measurement, and final recorder snapshots.

An idled worker is expected to have zero completions and no completed-batch productive snapshot.
That must be reported as intentionally inactive, not mistaken for worker disappearance. Every
all-polling row requires nonzero completion from every worker before it can support a branch.

## Interpretation and persistence

Use aggregate JMH throughput only to rank experimental policies. Candidate production inputs are
limited to physical local state available before choosing an alternative policy.

Test explanatory power in order:

1. productive handles relative to registered/all-active workers;
2. the validated executor-body estimate;
3. throughput-recorder average interval or local progress per productive cycle;
4. recorder variance/CV/trend only as evidence that the state is stable or changing.

Reject a recorder signal if it merely restates benchmark throughput, changes sign across stable
forks, separates only after parking changes it, or adds no classification beyond availability and
body cost. Do not turn an offline ratio into a new runtime field.

Five-second retained measurement iterations contain many completed batches and the recorder's 10 ms
history windows. A harmful regime and its candidate signal must persist across all warmup and
measurement boundaries, not appear after one miss. The fixed intervention proves only steady-state
causality. This phase may conclude that a future branch needs multi-batch persistence; it must not
select an exact sleep-entry duration or claim short-gap behavior that was not measured.

## Bug-first and acceptance gates

Before interpreting a discontinuity, verify affinity, distinct L2 masks, registration, source
lifecycle, productive classification, handle reinsertion/acquisition, routing mode, cache state, and
nonzero all-polling participation. A discrete fork cluster is retained and investigated, not
averaged into one result.

A harmful regime requires at least 5% lower total throughput than the best lower worker count, the
same direction in every fork, resolved uncertainty where practical, and stable physical state. The
fixed-park intervention requires at least 5% median recovery, recovery of at least half the
all-active penalty, unchanged registration/source/body state, the expected zero/nonzero completion
split, and no trapped-work or lifecycle defect.

## Outcomes

Append exactly one completion result after measurement:

1. **Outcome 1: no idle branch justified.** No stable material penalty remains, or fixed parking
   does not recover meaningful throughput.
2. **Outcome 2: existing signals explain idle eligibility.** A harmful regime and causal recovery
   exist, and the smallest combination of existing availability, body cost, and optionally one
   existing recorder observation separates it.
3. **Outcome 3: additional observable required.** The regime and causal recovery exist, but current
   observations do not distinguish it reliably; name the missing physical quantity.
4. **Outcome 4: correctness defect found.** A scheduler, lifecycle, worker, handle, topology, or
   fixture defect explains the apparent reversal; fix narrowly and rerun before interpretation.

## Required verification

```text
mise exec -- gradle :euhedral-core:test --no-daemon
mise exec -- gradle :benchmarks:test --no-daemon
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck --no-daemon
mise exec -- gradle build --no-daemon
git diff --check
```

Raw JMH JSON and logs stay under ignored `benchmarks/build/reports`. The completion record must name
the exact commands, host/toolchain, evidence paths, retained rows, rejected signals, bug checks, and
one final outcome.

## Completion record

### Host, topology, and method

The retained measurements ran on `brandons-desktop`, Linux
`7.0.0-28-generic #28~24.04.1-Ubuntu`, an Intel Core i9-14900K with one socket, 24 physical cores,
32 online logical CPUs, and 12 L2 instances. The JVM was OpenJDK `21.0.2+13-58`, Gradle was `9.6.1`,
and JMH was `1.37`. `mise` supplied the repository toolchain.

Every retained JMH row used three forks, three 3-second warmup iterations, five 5-second measurement
iterations, one harness thread, throughput units of operations/second, and `handleLayout=NATURAL`.
The worker-core selector and the new pairwise-L2 assertion kept the experiment on the eight P cores
listed above. No retained worker pair shared L2. The E cores and their four-core shared-cache
clusters were not part of this surface.

The common command was:

```text
mise exec -- java -XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dlogback.configurationFile=benchmark-logback.xml \
  -cp 'benchmarks/build/euhedral-benchmark.jar:benchmarks/build/lib/*' \
  org.openjdk.jmh.Main \
  'io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmark.idleEligibilityDiscovery' \
  <row parameters> -p handleLayout=NATURAL \
  -f 3 -wi 3 -w 3s -i 5 -r 5s -tu s -foe true \
  -rf json -rff <evidence.json> \
  -jvmArgsAppend '-XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED'
```

The five retained parameter groups were:

```text
active scaling:        -p workerCount=1,2,4,8 -p productiveHandles=1 \
                       -p emptyLiveHandles=0 -p workRounds=0 -p activePollingWorkers=0
productive interaction:-p workerCount=2 -p productiveHandles=2 \
                       -p emptyLiveHandles=0 -p workRounds=0 -p activePollingWorkers=0
body interaction:      -p workerCount=1,2 -p productiveHandles=1 \
                       -p emptyLiveHandles=0 -p workRounds=24,256 -p activePollingWorkers=0
real-empty control:    -p workerCount=2 -p productiveHandles=1 \
                       -p emptyLiveHandles=1 -p workRounds=0 -p activePollingWorkers=0
fixed-park proof:      -p workerCount=2 -p productiveHandles=1 \
                       -p emptyLiveHandles=0 -p workRounds=0 -p activePollingWorkers=1
```

The no-op body point from active scaling supplies the omitted `workRounds=0` body rows. The
one-productive-handle two-worker row supplies the corresponding scarce-opportunity and all-active
causal controls. This avoided rerunning duplicate rows.

### Active-worker scaling

With one repeating productive handle and the near-no-op body, the reversal began at the second
worker and grew monotonically:

| Active workers | Productive handles | Throughput, ops/s | 99.9% error | Fork means, ops/s                  | Loss vs. one worker | Completed work per active worker, ops/s |
|---------------:|-------------------:|------------------:|------------:|------------------------------------|--------------------:|----------------------------------------:|
|              1 |                  1 |        82,189,204 |   1,551,590 | 83,260,474; 83,097,857; 80,209,282 |                   - |                              82,189,204 |
|              2 |                  1 |        40,718,713 |   1,163,281 | 40,146,439; 39,817,978; 42,191,721 |             -50.46% |                              20,359,356 |
|              4 |                  1 |        28,365,348 |     189,214 | 28,151,612; 28,447,280; 28,497,151 |             -65.49% |                               7,091,337 |
|              8 |                  1 |        20,760,710 |     631,115 | 20,030,666; 21,420,525; 20,830,937 |             -74.74% |                               2,595,089 |

All three harmful rows exceeded the predeclared 5% gate, every fork had the same direction, and the
uncertainty did not overlap the one-worker row. Since there was one productive handle, completed
work per productive handle is the total-throughput column.

The result is not a lost-worker regime. Aggregate completion dominance for 2/4/8 workers was
respectively `0.516/0.522/0.503`, `0.254/0.250/0.254`, and `0.1266/0.1277/0.1278` across the three
forks. Every worker completed work in every measurement. The corresponding aggregate per-worker
completion arrays are retained in the logs; for example, the two-worker arrays were
`[487039062,519639575]`, `[521506748,477830128]`, and `[525882696,532175985]`.

### Productive-opportunity interaction

At two active workers, changing only the number of repeating productive handles from one to two
raised throughput from `40,718,713 +/- 1,163,281` to
`125,251,195 +/- 966,523` ops/s, a 207.60% increase. The two-handle fork means were
`124,396,260`, `126,308,823`, and `125,048,501` ops/s. Completed work per active worker and per
productive handle was `62,625,597` ops/s.

The two workers remained balanced: dominance was `0.514`, `0.500`, and `0.503`, with aggregate
completion arrays `[1599069292,1513409826]`, `[1581690271,1579064410]`, and
`[1555559770,1573698470]`. Thus raw worker count is rejected. The material loss follows productive
scarcity relative to active workers.

The real empty-live-source control kept two live handles but only one productive handle. It reached
`35,489,297 +/- 2,659,609` ops/s with fork means `37,538,867`, `33,788,982`, and `35,140,040`.
Throughout setup, warmup, measurement, and teardown the source remained live, incomplete, empty, and
had zero offers; the production observation remained `productiveHandles=1`, not nominal
`liveHandles=2`. Both workers participated, with dominance `0.519`, `0.515`, and `0.551`. Its extra
12.84% loss relative to the one-live-handle scarce row is attributable to servicing/requesting the
real empty source, but both rows have the same idle-eligibility classification.

### Executor-body interaction

At the smallest harmful shape, one productive handle and one versus two active workers, increasing
body cost removed and then reversed the extra-worker penalty:

| Work rounds | Workers | Production body estimate | Selected mode | Throughput, ops/s | 99.9% error | Fork means, ops/s                  |
|------------:|--------:|-------------------------:|---------------|------------------:|------------:|------------------------------------|
|           0 |       1 |                 13-15 ns | DIRECT        |        82,189,204 |   1,551,590 | 83,260,474; 83,097,857; 80,209,282 |
|           0 |       2 |                 13-15 ns | DIRECT        |        40,718,713 |   1,163,281 | 40,146,439; 39,817,978; 42,191,721 |
|          24 |       1 |                 33-34 ns | DIRECT        |        34,256,320 |     226,917 | 34,117,635; 34,538,659; 34,112,666 |
|          24 |       2 |                 33-34 ns | DIRECT        |        34,634,326 |     571,050 | 34,215,852; 35,360,963; 34,326,163 |
|         256 |       1 |               236-237 ns | DIRECT        |         4,258,556 |      20,897 | 4,284,735; 4,241,178; 4,249,755    |
|         256 |       2 |               236-237 ns | STAGED        |         7,688,273 |      53,427 | 7,655,266; 7,743,742; 7,665,812    |

At 24 rounds, two workers were 1.10% faster, the uncertainty overlapped, and every fork showed only
a small gain. That is not material either way. At 256 rounds, two workers were 80.54% faster with
resolved uncertainty. Participation was again real and balanced: two-worker dominance was
`0.513/0.521/0.533` at 24 rounds and `0.5001/0.5001/0.5001` at 256 rounds. The prior isolated-body
calibration was approximately `0.353`, `21.566`, and `225.235` ns for 0, 24, and 256 rounds; the
production estimate includes its normal surrounding measurement semantics.

This proves a body-cost interaction but does not produce a portable threshold. On this host the
material reversal lies somewhere between the extreme no-op point and the 24-round point. The
existing 90/95 ns production guard and body aggregation were not changed.

### Existing FlowRecorder evidence

The inventory above was verified against the implementation before adding the benchmark surface. No
recorder, recorder write, clock read, EWMA, or production metric was added. The diagnostic snapshot
reads only existing live owner-local fields at JMH iteration boundaries and teardown.

Representative retained final snapshots showed:

| Physical row                                   | Throughput-recorder interpretation                                                                                                                                          |
|------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 worker / 1 handle / 0 rounds                 | Average units stayed at 32; average productive-update interval was about 379-399 ns and average rate about 0.081-0.085 frames/ns.                                           |
| 2 workers / 1 handle / 0 rounds                | Average units commonly fell into the mid-20s; interval was roughly 865-1,984 ns and rate roughly 0.031-0.037 frames/ns. Both workers showed the same contested regime.      |
| 2 workers / 2 handles / 0 rounds               | Average units stayed at 32; interval was about 485-527 ns and rate about 0.061-0.066 frames/ns.                                                                             |
| 1 versus 2 workers / 1 handle / 24 rounds      | The one-worker rate was about 0.035 frames/ns; the two-worker local rates were only about 0.020-0.025 even though total throughput was flat rather than materially harmful. |
| 1 versus 2 workers / 1 handle / 256 rounds     | Local rates were about 0.0043 versus 0.0038 frames/ns even though the second worker improved total throughput by 80.54%.                                                    |
| 2 registered / 1 polling / 1 handle / 0 rounds | The polling worker returned to average units 32, about 394-414 ns intervals, and about 0.078-0.081 frames/ns. The parked worker's recorder state remained zero.             |

These observations reject throughput-recorder batch fill, progress interval, and local progress rate
as standalone idle roots. The 24-round and 256-round controls can look locally less productive than
the harmful no-op row while the second worker is neutral or strongly useful. Raw progress also
changes as a consequence of the intervention and would be self-referential if used directly.

Interval/rate CV was low in the single-worker and matched-opportunity rows but commonly above one in
contested scarce rows. It establishes that scarce-handle service is bursty; it does not decide
whether the second worker is harmful because the body controls retain contention without retaining
the penalty. Recorder trend signs changed between otherwise stable measurements and workers, so
trend is rejected as an eligibility root. Extrema, standard deviations, window counts, and
`rollingSum` add no classification beyond their source observations. The service recorder excludes
empty polling and mixes execution-path timing; it is redundant with and less direct than the
validated executor-body estimate. The never-updated batch recorder provides no signal.

Existing recorder history is still useful later for transition and persistence validation, but H2 is
rejected: no FlowRecorder observation adds necessary explanatory power to productive availability
plus body cost.

### Controlled-idle causal proof

The diagnostic intervention kept the same two workers registered and the same one-handle no-op
graph, but allowed only the first worker to enter the production polling loop. The second worker
used one indefinite `LockSupport.park` loop with wakeup only for reset and teardown. There was no
duration, spin count, yield count, wake interval, backoff, or adaptive choice.

Fixed parking reached `77,633,371 +/- 1,234,569` ops/s with fork means `76,543,658`, `79,162,709`,
and `77,193,744`. This is 90.66% above the two-worker all-active result and recovers 89.01% of the
absolute gap between that result and the standalone one-worker result. Every parked fork exceeded
every all-active fork, and the uncertainty did not overlap. The remaining 5.54% gap from the
standalone one-worker result is consistent with retaining the two-worker registration/routing graph.

All snapshots continued to report two registered workers, one live and productive handle, the same
no-op body, and DIRECT mode. The polling worker completed
`1,916,907,698`, `1,981,932,673`, and `1,933,691,668` measurement frames by fork. The parked worker
completed exactly zero, reported `activePolling=false`, had no body history, and retained zero
throughput/service recorder state. Reset and close completed normally. This passes both the 5%
recovery gate and the requirement to recover at least half the all-active penalty, and it directly
attributes the loss to aggressive polling by excess capacity.

### Persistence and bug-first findings

The physical state and selected production mode were stable across every warmup and measurement
boundary. Each retained fork observed 34 seconds of steady load; each 5-second measurement in the
all-active two-worker no-op row completed about 200-211 million frames. Aggregate measurement work
was about 31-33 million productive batches per fork at batch 32. The earlier one-second smoke also
showed the parking benefit in every measurement. The harmful condition is therefore persistent over
many batches and at least one second, rather than a single empty pull or a short gap.

This experiment does not measure sub-second sleep-entry cost, wake latency, burst return, or a safe
idle duration. A later controller should require persistent evidence across multiple batches and
must validate hysteresis, but no duration or extra EWMA is justified here.

The following potential defects were checked before interpretation:

- all selected workers were homogeneous P cores with pairwise-disjoint L2 masks;
- registration, live count, productive count, body history, and selected mode were stable;
- every worker in every all-polling row had nonzero completions, balanced participation, handle
  attempts, successful pulls, and reinsertion;
- the real empty-live source remained empty, incomplete, and correctly nonproductive;
- the fixed parked worker remained registered, serviced reset/teardown, and completed no work;
- DIRECT mode in the causal pair prevented parked routing destinations or staged-cache work from
  confounding the result; and
- no discrete fork cluster indicated worker disappearance, bad source state, cache imbalance,
  affinity drift, or a lifecycle failure.

H3 is rejected. No correctness fix was needed. The test implementation also caught and corrected a
benchmark-only compatibility error during development: legacy fragments without a production policy
must bypass the diagnostic polling check. The retained evidence was collected after that guard and
does not contain the failed regime.

### Smallest supported physical branch

The data supports this relationship, without a numeric production threshold:

```text
productiveHandles >= activeWorkers
    -> keep workers actively polling

productiveHandles < activeWorkers
    + executor body is extremely cheap
        -> excess active capacity is idle-eligible

productiveHandles < activeWorkers
    + executor body is no longer extremely cheap
        -> keep workers active
```

In current production all registered workers poll, so `registeredWorkers` is the available active
count. H1 is supported and H0 is rejected. The all-active workers were deliberately symmetric in
completion share and local recorder state, so these observations establish eligibility for excess
capacity but do not identify which particular worker should sleep. Deterministic designation,
persistence, wakeup, and hysteresis are waiting-mechanic questions for the next blueprint; they do
not require a new physical eligibility sensor or global worker voting in this phase.

### Evidence and verification

Raw retained evidence is outside source-controlled data under:

- `benchmarks/build/reports/phase11-active-scaling.json` and `.log`;
- `benchmarks/build/reports/phase11-productive-opportunity.json` and `.log`;
- `benchmarks/build/reports/phase11-body-cost.json` and `.log`;
- `benchmarks/build/reports/phase11-real-empty-control.json` and `.log`; and
- `benchmarks/build/reports/phase11-fixed-park.json` and `.log`.

The corresponding `*-smoke.json` and `*-smoke.log` files contain the one-fork setup checks. These
paths are ignored build output and were intentionally not added to version control.

The required verification completed with:

```text
mise exec -- gradle :euhedral-core:test --no-daemon
mise exec -- gradle :benchmarks:test --no-daemon
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck --no-daemon
mise exec -- gradle build --no-daemon
git diff --check
```

**Final result - Outcome 2: existing signals explain idle eligibility.** The repeatable harmful
excess-worker regime is causal, fixed diagnostic idling materially restores throughput, and the
smallest sufficient existing inputs are productive availability relative to active workers plus the
existing executor-body estimate. No final idle controller or waiting mechanic was implemented.
