# Phase 12: Minimal Production Idle Branch Integration Blueprint

## Production rule and ownership

`FragmentDecisionTree` remains owner-thread state and owns the idle eligibility predicate
independently of DIRECT/STAGED selection. Isolate the temporary bound as:

```text
EXTREMELY_CHEAP_BODY_COST_MAX_NS = 20.0
```

The bound includes the measured 13-15 ns harmful extreme while excluding the measured 33-34 ns
neutral region. It is host-derived and temporary, not a portable crossover claim.

At a completed productive batch, use the worker's existing productive count, global registered
count, current registered-core rank, body history, and body estimate:

```text
pollingQuota = max(1, min(productiveHandles, registeredWorkers))

idle eligible only when:
    no diagnostic override is installed
    registeredWorkers > 1
    productiveHandles < registeredWorkers
    body history >= existing minimum
    0 < body estimate <= 20 ns
    workerRank >= pollingQuota
```

All other states poll. The predicate neither reads nor changes the selected execution mode. Forced
DIRECT/STAGED and setup-fixed polling retain their current behavior.

## Rank and stale-state invariant

Do not create another registry. `LatticeEdge` derives the owner rank from its existing
`ACTIVE_PARTITIONS` registration bitmap: count registered cores with smaller physical core IDs.
Acquire reads match the bitmap's existing CAS registration publication. This ordering is stable once
registration settles and follows the benchmark/shard's ascending core order.

Every worker clamps its polling quota to at least one, so the lowest currently registered core has
rank zero and is never idle-eligible even when it observes zero productive handles. Worker-local
productive disagreement can retain extra pollers but cannot make all workers conclude another worker
owns the polling obligation. Registration changes recompute rank rather than retaining a stale
index.

## Safe boundary and waiting

The worker records eligibility only when `state.completed` reaches the existing batch boundary. It
may enter the idle loop only between control-loop cycles, after all acquired source handles have
been released, and only while its owner-local cache is empty. Cached work always takes priority.

The production idle loop uses only fixed `LockSupport.parkNanos` with a **1 ms** recheck interval.
The value is a correctness-oriented fixed interval for this phase, not a latency or throughput
optimization. Each wake services reset, refreshes productive count, registered count and rank, and
reevaluates the same predicate. Newly published sources become visible through the existing
`UpstreamQueue` count on a bounded recheck. Reset and close additionally use the existing immediate
`unpark`; close also interrupts. No new coordination, timer policy, or backoff is introduced.

Reset clears policy history and idle eligibility, so startup behavior resumes active. Close follows
the existing join path. Diagnostic fixed-idle workers keep the existing indefinite park loop.

## Diagnostics and compatibility

Extend the existing low-frequency `FragmentPolicySnapshot` with registered workers, worker rank, and
current production parked state. `activePolling` means the worker is presently in the normal polling
loop; it distinguishes intentional idling from disappearance. These plain owner fields are
diagnostic snapshots only and add no hot-path publication.

Retain the Phase 11 benchmark fixture. `activePollingWorkers > 0` continues to request a fixed
diagnostic subset; zero exercises the normal production idle branch. Normal production validation
expects only the productive quota parked at zero rounds and all workers active at 24 and 256 rounds.
Logs retain per-worker completions, registration, productivity, mode, body estimate, and
parked/polling state.

## Focused acceptance

- Selector tests cover insufficient history, exact 20 ns inclusion, above-bound exclusion, plentiful
  availability, rank quota, zero-productivity rank-zero safety, diagnostics, and reset.
- Registration-rank tests cover sparse registered core IDs and removal.
- Thread/lifecycle smoke proves cheap/scarce parking can wake after productive capacity increases,
  and reset/close cannot strand a parked worker. If deterministic production timing cannot be
  established in a unit fixture, run this as a bounded benchmark smoke using the real estimator.
- Stable cheap/scarce measurements show the same active/parked set at iteration boundaries; no extra
  persistence mechanism is added unless this check exposes churn.
- Run the plan's predeclared matrix and required repository verification. Raw evidence remains
  outside source-controlled data.

## Completion record

### Integrated rule and lifecycle

1. The exact temporary idle boundary is `EXTREMELY_CHEAP_BODY_COST_MAX_NS = 20.0`. It includes the
   measured 13-15 ns near-no-op extreme and excludes the measured 33-35 ns 24-round region. It is
   isolated in `FragmentDecisionTree` and is not claimed to be portable.
2. The polling quota is `max(1, min(productiveHandles, registeredWorkers))`. A worker polls when its
   zero-based registered-core rank is below that quota; higher ranks are idle-eligible only when
   history and body cost also pass. Rank is the count of already-registered lower physical core IDs
   in `LatticeEdge.ACTIVE_PARTITIONS`.
3. Production idle uses only fixed `LockSupport.parkNanos(blocker, 1_000_000L)`. There is no spin,
   yield comparison, adaptive duration, backoff, or allocation.
4. The one-millisecond wake refreshes the existing upstream count/productive observation, registered
   count, and rank. A newly published handle is optimistic in the existing worker-local
   `UpstreamQueue`, so additional productive opportunity releases the excess worker. Existing
   `unpark` calls wake reset and close immediately; close also interrupts.
5. Eligibility is recorded only at the existing completed-batch boundary. The actual park starts
   between cycles, after a source handle has been released, and only with an empty owner-local
   cache. Any newly received local cached work releases the wait.
6. Startup remains active until the unchanged 32 valid body samples establish a positive estimate.
   Reset clears the policy history and idle flag and therefore restores active startup.
7. Stale productive observations cannot park all workers: the polling quota is always at least one,
   and the current lowest registered core has rank zero. Disagreement may retain extra pollers but
   cannot delegate the final polling obligation away.

The production diff is limited to the policy predicate, registered-core rank derivation, the
fragment wait/recheck loop, and diagnostic snapshot fields. DIRECT/STAGED selection, the 90/95 ns
guard, body sampling/aggregation, productive sensing, request order, cache paths, routing, and
forced diagnostic behavior were not changed.

### Bounded production matrix

The retained JMH method used three forks, three 3-second warmups, five 5-second measurements, one
harness thread, natural handle layout, and the same P-core/disjoint-L2 selection as Phase 11.

| Row                             | Polling/parked result | Mode and body estimate | Throughput, ops/s | 99.9% error | Fork means, ops/s                     | Gate |
|---------------------------------|-----------------------|------------------------|------------------:|------------:|---------------------------------------|------|
| 1 handle, 2 workers, 0 rounds   | 1 / 1                 | DIRECT, 14-19 ns       |        77,025,741 |     638,741 | 77,744,366; 76,971,722; 76,397,662    | pass |
| 1 handle, 4 workers, 0 rounds   | 1 / 3                 | DIRECT, 14-20 ns       |        68,677,537 |     296,212 | 68,648,640; 68,309,641; 68,995,366    | pass |
| 2 handles, 2 workers, 0 rounds  | 2 / 0                 | DIRECT, 13-15 ns       |       128,226,028 |   1,585,047 | 126,696,417; 127,424,612; 129,922,885 | pass |
| 1 handle, 2 workers, 24 rounds  | 2 / 0                 | DIRECT, 33-35 ns       |        34,997,427 |     142,595 | 35,011,087; 35,161,580; 34,829,587    | pass |
| 1 handle, 2 workers, 256 rounds | 2 / 0                 | STAGED, 236-237 ns     |         7,576,253 |     122,114 | 7,735,236; 7,524,670; 7,478,948       | pass |

The two-worker no-op row improved 89.17% over the Phase 11 all-active value and recovered 87.55% of
the absolute gap to the Phase 11 standalone result. The four-worker row improved 142.12% and
recovered 74.90% of its absolute gap. Both exceed the predeclared half-gap gates.

The matched no-op control improved 2.38% over its retained baseline. The 24-round control improved
1.05%. The 256-round control was 1.46% below its retained two-worker baseline, within the 5% gate,
and remained 77.91% above the retained one-worker result. All controls retained the expected active
workers and the expensive row retained STAGED.

### Empty-live source, participation, and stability

The retained `2 live / 1 productive / 2 workers` production control reported exactly one productive
handle on each worker, kept two workers registered, left rank zero polling, and parked rank one. It
reached `36,369,233 +/- 470,555` ops/s with fork means `36,831,879`, `36,486,586`, and `35,819,078`.
That is only 2.48% above the prior all-active value, below the plan's optional material-recovery
expectation. The required classification passed, however, and the narrow contradiction check with
the same real-empty graph and one registered worker reached `38,288,109 +/- 475,860` ops/s. The
production-idle result is 94.99% of that matching standalone control; continued empty-source
service, not an eligibility, rank, wake, mode, or disappearance defect, explains the lower ceiling.
No threshold or productive semantics were changed in response.

Across every retained scarce no-op measurement, polling workers had positive completions and parked
workers had exactly zero measurement completions while remaining registered. The four-worker row,
for example, reported one positive lane and three zero lanes in all 15 measurements. Matched no-op
aggregate completion dominance was 0.500-0.507 by fork, 24-round dominance was 0.520-0.546, and
256-round dominance was 0.5001. Thus active workers did not disappear.

Warmup and measurement snapshots retained the same polling/parked ranks after the expected startup
transition. Each of the three scarce-row forks had three stable warmup boundaries and five stable
measurement boundaries, and no parked worker completed measurement work. No repeated idle churn or
fork bimodality appeared.

### Wake, reset, close, commands, and evidence

The one-shot production smoke first observed rank one parked with one productive handle and a 15 ns
estimate. Its completion counts were `[19400, 8206]`. Reset returned with zero cached frames
cleared, rank one returned to active startup with zero body history, and its count advanced to 8208.
After a second repeating source was published, both workers reported two productive handles, both
returned to active polling, and counts advanced to `[82200, 16428]`. Trial teardown closed both
workers normally. The smoke log contains the full before/reset/after policy snapshots.

Raw evidence is outside source-controlled repository data at:

- `/tmp/euhedral-phase12-20260813/noop-scarce.json` and `.log`;
- `/tmp/euhedral-phase12-20260813/noop-matched.json` and `.log`;
- `/tmp/euhedral-phase12-20260813/body-controls.json` and `.log`;
- `/tmp/euhedral-phase12-20260813/real-empty.json` and `.log`;
- `/tmp/euhedral-phase12-20260813/real-empty-one-worker.json` and `.log`; and
- `/tmp/euhedral-phase12-20260813/wake-smoke.json` and `.log`.

The matrix used this exact common command, with each parameter group below substituted for
`<row parameters>` and the named JSON/log destination:

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

The exact row parameter groups were:

```text
-p workerCount=2,4 -p productiveHandles=1 -p emptyLiveHandles=0 -p workRounds=0 -p activePollingWorkers=0
-p workerCount=2 -p productiveHandles=2 -p emptyLiveHandles=0 -p workRounds=0 -p activePollingWorkers=0
-p workerCount=2 -p productiveHandles=1 -p emptyLiveHandles=0 -p workRounds=24,256 -p activePollingWorkers=0
-p workerCount=2 -p productiveHandles=1 -p emptyLiveHandles=1 -p workRounds=0 -p activePollingWorkers=0
-p workerCount=1 -p productiveHandles=1 -p emptyLiveHandles=1 -p workRounds=0 -p activePollingWorkers=0
```

The wake smoke used the same JVM/classpath options and:

```text
org.openjdk.jmh.Main \
  'io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmark.productionIdleWakeSmoke' \
  -f 1 -wi 0 -i 1 -r 1s -foe true \
  -rf json -rff /tmp/euhedral-phase12-20260813/wake-smoke.json
```

Repository verification completed successfully with:

```text
mise exec -- gradle :euhedral-core:test --no-daemon
mise exec -- gradle :benchmarks:test --no-daemon
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck --no-daemon
mise exec -- gradle build --no-daemon
git diff --check
```

**Final outcome - Outcome 1: minimal idle branch accepted.** The production branch safely idles
deterministic excess workers only in the clearly cheap/productively scarce regime. The known no-op
loss is materially recovered, matched opportunities remain active, the neutral and expensive
controls are preserved, wake/reset/close pass, and no unstable idle or correctness regime appears.
