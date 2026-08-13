# Phase 10: FlowThread-Based Productive Observation Refinement

Status: complete - Outcome 1, productive branch integrated

Prior evidence:

- [`phase-8-productive-pull-opportunity-validation.md`](phase-8-productive-pull-opportunity-validation.md)
- [`phase-9-productive-handle-production-root-integration.md`](phase-9-productive-handle-production-root-integration.md)

Blueprint intensity: maximum

Implementation intensity: high

## Decision and ownership

Classify one acquired handle from evidence produced synchronously on the servicing worker during the
original operation. Keep productivity in the interceptor's plain thread-local observation and keep
`UpstreamQueue.nonproductiveCount` plain and owner-local. Do not publish producer readiness, add a
counter, or coordinate workers.

For an available `FlowContext`, snapshot `satisfiedRequest` after successful acquisition and before
a request. Perform exactly one existing request or pull. A request-side counter change is positive
evidence. A pull's returned delivery count is its narrower service-local positive signal and is also
added to `satisfiedPull`. Compare request values for change rather than subtracting: one service
cannot legitimately produce a full unsigned `long` range, so equality is the only ambiguous wrap
case and is outside the bounded demand contract.

`FlowContext` is owned by the current thread. Ordinary control-plane service does not reset it or
service another source reentrantly inside the measured interval. Pull sources may only deliver
already-available frames to the supplied consumer; requested pushes return through their own
interceptor. No supported production path lets another thread mutate this context. Calls made on a
non-`FlowThread` retain correct classification from the direct pull result and interceptor-local
requested-push observation without allocating a fallback context.

## Classification

Use these rules after the original service:

```text
positive pull result or satisfied counter changed or synchronous handle push observed
    -> productive

pull returned zero, completed normally, and its wrapped stop predicate did not stop
    -> nonproductive

request produced no work
    -> preserve prior observation

stop rejected delivery, acquisition failed, service was invalid, or service threw/cancelled
    -> preserve prior observation
```

The original pull receives the existing `ProductivityObservation` as its stop function. That object
delegates to the caller's predicate and records whether it stopped. A normal zero therefore remains
valid empty evidence, while a stopped zero restores the prior belief. Do not call the source again
or run another predicate pass.

The interceptor retains its existing synchronous `push` observation because `satisfiedRequest`
currently counts accepted remote-cache routing, not every ordered/direct requested push. This is a
service-local plain write and preserves the already-conformant request behavior. Producer-thread
pushes still touch only that producer thread's unused observation and do not publish to workers.

After classification, compare the final handle observation with the pre-service observation and
change `nonproductiveCount` exactly once for the two real transitions. Completion/removal remains
identity-based. Live-count accessors remain live-only.

## Tests and acceptance

Deterministic Core tests must cover productive and empty pulls, request production despite the void
request API, request without production, stop rejection, cancellation, exception, failed
acquisition, producer offers, stale worker disagreement, all four transitions, completion/removal,
and the three real Phase 8 source shapes. Tests using the real interceptor must initialize and clear
`FlowThread` context explicitly when asserting counter evidence.

Run the required Core, benchmark, formatting, full-build, and diff checks. Then run
`productiveHandleSensorOverhead` for `PRODUCTIVE_FAST` and `EMPTY_MISS`, `ENABLED` versus
`LIVENESS_ONLY`, with the unchanged three-fork, three-warmup, five-measurement protocol and gates:

- enabled median loss no more than one percent;
- enabled lowest fork at least 98 percent of baseline lowest fork; and
- both workers present with no unexplained regime.

Only after both rows pass, change `ControlPlaneFragment.recordProgress` at its completed-batch
boundary to pass `getProductiveHandleCount()` to the unchanged policy. Restore only the bounded
normal-policy fixture needed to confirm two productive/expensive DIRECT, one productive/expensive
STAGED, two-live/one-productive/expensive STAGED, and two-live/one-productive/cheap DIRECT.

## Completion record

Completed: 2026-08-13

Final result: **Outcome 1: FlowThread observation accepted.**

FlowThread and the existing interceptor-local validity observation provide sufficient evidence to
classify a serviced handle without another source operation. The semantic suite, retained real
source shapes, productive fast-path gate, empty-miss gate, and bounded normal-policy confirmation
all pass. The production root now consumes the worker-local productive-handle count at the existing
completed-batch boundary. The productive-opportunity branch is fully integrated.

### Starting implementation and refinements

The uncommitted implementation found at the start changed four files. Its correct direction was to
wrap the original pull's stop predicate, remove the one-item re-probe, sample `FlowContext`
production, and expose a general productivity setter. Those parts were retained and refined.

The following issues were corrected:

- `>=` classified an unchanged cumulative counter as production; request evidence now uses strict
  before/after inequality.
- transition accounting ran only when the interceptor had already changed state, preventing a
  nonproductive handle from becoming productive from counter evidence; final state is now compared
  directly with pre-service state.
- a missing fallback `FlowContext` caused an ordinary-thread null dereference; non-FlowThread calls
  now use direct pull and interceptor-local evidence without allocating a context.
- request-without-production became false empty evidence; it again preserves the prior observation.
- removing `UpstreamInterceptor.push` productivity lost ordered/direct synchronous request
  production not counted by `satisfiedRequest`; the plain service-local observation was restored.
- removing `WorkRequester`'s remote-cache pull assignment discarded unrelated existing telemetry;
  that manual edit was removed and the file has no final diff.
- the context lookup was hoisted outside the per-handle loop, pull classification uses the returned
  service-local count, and the stop wrapper returns the delegate's existing `Boolean`. This narrow
  refinement preserved semantics and eliminated the remaining measured gate loss.

The original manual test-only method rename was retained, then expanded with direct FlowThread and
lifecycle assertions. No manually correct work was discarded.

### Exact evidence and classification

The servicing worker obtains its existing `FlowContext` once per `UpstreamQueue.pull` call. For a
request, it reads `satisfiedRequest` immediately before the original request. Synchronous unordered
pushes accepted into a routing cache increment that existing counter. A changed value marks the
handle productive. Ordered/direct synchronous pushes use the interceptor's pre-existing plain
thread-local push observation because the counter's established semantics do not include that
route. A request with neither form of production preserves the prior belief because the void
request API provides no empty-source result.

For a pull, the original source return is the unambiguous service-local produced-frame count and is
added to `satisfiedPull`. A positive count marks the interceptor productive. A normal zero leaves
the acquired observation nonproductive. The original pull receives `ProductivityObservation` as
its stop function; this delegates exactly once per source predicate evaluation and records a true
stop. A stopped zero restores the prior belief. There is no second pull, request, predicate pass,
timer, metric, log, allocation, lock, atomic productivity counter, or cross-worker publication.

Invalid lifecycle service, exceptions, and `AbstractFrame.CANCEL_SIGNAL` restore the prior
observation in the existing interceptor path. Failed acquisition never begins an observation. The
queue changes `nonproductiveCount` only when final state differs from pre-service state, preserving
all four transitions and identity-correct completion/removal.

The counters are plain current-thread state. An ordinary service interval is synchronous and no
supported source path resets the context or services another source reentrantly inside it. Pull
sources may only deliver already-available frames to the supplied consumer, and requested pushes
return through the requesting handle's interceptor. No other thread can mutate the worker's
context. Strict changed-value comparison is safe across signed overflow; a deterministic test
crosses `Long.MAX_VALUE` to `Long.MIN_VALUE`. Equality after a complete unsigned wrap would require
one bounded service to produce `2^64` events and is outside the demand contract.

### Deterministic and source-shape tests

The Core suite now directly proves:

- productive pull records `satisfiedPull` and remains productive;
- an actual empty pull invokes the source once, records no production, and becomes nonproductive;
- a void request with synchronous cached routing changes `satisfiedRequest` and is productive;
- a request with no synchronous production preserves productive and nonproductive prior beliefs;
- a stopped delivery remains queued and preserves the prior observation;
- exception and cancellation restore productivity before existing completion handling;
- failed acquisition changes no observation;
- producer offer does not publish productivity, while later owner service does;
- workers retain independent stale beliefs until each services the handle;
- all four productive/nonproductive transitions and productive/nonproductive removals occur once;
  and
- cumulative pull accounting remains positive evidence across signed counter overflow.

The retained real `QueueIngestSink` regression reports two productive handles as 2, one productive
handle as 1, and the real two-live/one-empty-incomplete shape as live 2 and productive 1 after owner
observation. The empty source remained size zero, incomplete, and never offered a frame throughout
the JMH controls.

### Overhead evidence

The first full implementation run still failed the unchanged median gates, although its confidence
intervals overlapped and both lowest-fork gates passed. Productive median was 59,135,624 versus
60,317,139 (-1.96 percent), and empty median was 31,526,389 versus 32,008,522 (-1.51 percent). This
localized remaining cost to per-handle context lookup and redundant pull-state work, not a source
probe. The narrow refinement described above passed the complete Core suite before the same gate
was rerun.

The accepted refined run used three forks, three 3-second warmups, five 5-second measurements,
rounds 24, two pinned workers on logical CPUs 0 and 6, natural handles, batch 32, and forced modes.

| Control         | Enabled fork means                         | Baseline fork means                        | Median change | Lowest-fork ratio | Result |
|-----------------|--------------------------------------------|--------------------------------------------|--------------:|------------------:|--------|
| Productive fast | `[60011964,59676121,60315503]`             | `[59989834,59632375,59324505]`             |        +0.64% |           100.59% | pass   |
| Empty miss      | `[32894893,32537504,32690035]`             | `[32485803,31771556,32222223]`             |        +1.45% |           102.41% | pass   |

The predeclared gates were enabled median loss no more than one percent and enabled lowest fork at
least 98 percent of baseline. Aggregate worker dominance was 0.50125-0.52683 across accepted
overhead forks, so both workers remained present with no new regime.

### Production root and bounded confirmation

`ControlPlaneFragment.recordProgress` now reads `getProductiveHandleCount()` only after the existing
batch completes, stores that plain value for benchmark diagnostics, and passes it to the unchanged
`FragmentControlPolicy`. `CycleState.upstreamCount`, `getCachedUpCount()`, and
`getTrueUpstreamCount()` retain live-count semantics. Forced modes, selector ordering, the 90/95 ns
body-cost bounds, history, sampling cadence, batching, routing, and request ordering are unchanged.

The full bounded normal run produced:

| Physical row                     | Live/productive/workers | Mode   | Mean frames/s | Fork means                     |
|----------------------------------|-------------------------|--------|--------------:|--------------------------------|
| two productive / expensive       | 2/2/2                   | DIRECT |     4,281,365 | `[4280869,4286396,4276830]`    |
| one productive / expensive       | 1/1/2                   | STAGED |     4,074,962 | `[4052953,4099156,4072776]`    |
| two live / one productive / expensive | 2/1/2              | STAGED |     4,030,637 | `[4018746,4036818,4036347]`    |
| two live / one productive / cheap | 2/1/2                  | DIRECT |    28,327,761 | `[30858473,23335371,30789441]` |

Every warmup, measurement, and final snapshot on both workers reported the expected productive
count and mode. The expensive rows had aggregate worker dominance 0.50000-0.50167. The known noisy
cheap row retained both workers with maximum aggregate dominance 0.54280, below the established
0.60 worker-presence bound.

### Raw evidence and exact commands

Generated evidence remains outside source control:

```text
benchmarks/build/reports/phase10-flow-thread-overhead-smoke.json
benchmarks/build/reports/phase10-flow-thread-overhead-smoke.log
benchmarks/build/reports/phase10-flow-thread-overhead.json
benchmarks/build/reports/phase10-flow-thread-overhead.log
benchmarks/build/reports/phase10-flow-thread-overhead-refined-smoke.json
benchmarks/build/reports/phase10-flow-thread-overhead-refined-smoke.log
benchmarks/build/reports/phase10-flow-thread-overhead-refined.json
benchmarks/build/reports/phase10-flow-thread-overhead-refined.log
benchmarks/build/reports/phase10-productive-normal-smoke.json
benchmarks/build/reports/phase10-productive-normal-smoke.log
benchmarks/build/reports/phase10-productive-normal.json
benchmarks/build/reports/phase10-productive-normal.log
```

The accepted overhead command was:

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
  -rf json -rff benchmarks/build/reports/phase10-flow-thread-overhead-refined.json \
  -jvmArgsAppend '-XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED' \
  > benchmarks/build/reports/phase10-flow-thread-overhead-refined.log 2>&1
```

The bounded normal-policy command was:

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
  -rf json -rff benchmarks/build/reports/phase10-productive-normal.json \
  -jvmArgsAppend '-XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED' \
  > benchmarks/build/reports/phase10-productive-normal.log 2>&1
```

### Final verification

```text
mise exec -- gradle :euhedral-core:test --no-daemon                         PASS
mise exec -- gradle :benchmarks:test --no-daemon                            PASS
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck \
  --no-daemon                                                               PASS
mise exec -- gradle build --no-daemon                                       PASS
git diff --check                                                            PASS
```

The full build used the pinned Java 21 and Gradle 9.6.1 toolchain, completed native packaging and
all repository module checks, and reported no environmental limitation. Final stale-reference,
ASCII, diff, and status audits found only the intended Core, benchmark, test, plan, and blueprint
changes. Raw JMH reports remain ignored build output and no source-controlled data was added.
