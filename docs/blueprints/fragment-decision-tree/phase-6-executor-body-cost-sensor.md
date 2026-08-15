# Phase 6: Executor Body-Cost Sensor

Status: completed; signal accepted on 2026-08-11

Plan:
[
`phase-6-executor-body-cost-sensor.md`](../../plans/fragment-decision-tree/phase-6-executor-body-cost-sensor.md)

Blueprint intensity: maximum

Implementation intensity: high

## Decision and scope

Test exactly one candidate for the missing decision-tree input: sparse wall-clock timing around the
executor override and nothing around the scheduler. The candidate remains diagnostic-only for this
phase. Normal DIRECT/STAGED selection, forced-mode semantics, the fixed batch target, availability
state, and both execution paths remain unchanged.

The retained boundary to distinguish is:

```text
80 rounds ~= 70.689 ns isolated body work
96 rounds ~= 84.657 ns isolated body work
```

Rounds 24 (`~= 21.566 ns`) is the cheap fixed-overhead control. No other work points are required.

Do not add an `EXPENSIVE_WORK_BOUNDARY_NS`, work-cost EWMA, fragment policy field, normal-policy
benchmark, or source/readiness branch. A passing result authorizes a later production integration
blueprint; it does not authorize that integration here.

The direct executor boundary is available without restructuring the fragment, so calibrated
path-floor subtraction is not part of this blueprint. If implementation disproves that structural
finding, stop and return to design instead of silently pivoting to subtraction in the same run.

## Execution-boundary inventory

No existing general boundary executes multiple actual frame bodies contiguously without also
including scheduler or lifecycle work.

| Location | Multiple frames per outer call | Why it is not body-only |
|----------|-------------------------------:|-------------------------|
| `ControlPlaneFragment.cycle` path intervals | yes | Include cache/pull traversal, handle contention, and path bookkeeping |
| `ControlPlaneCache.drain` | yes | Queue traversal invokes the complete downstream consumer once per frame |
| `LatticeVertex.pull` remote-cache loop | yes | Includes routing-cache selection and queue drains |
| `UpstreamQueue.pull` | yes | Includes handle polling, locking, demand buckets, and source pulls |
| `LatticeHotSource.accept` | no | Adds source dispatch and the whole executor terminal |
| `AbstractExecutor.ExecutionTerminal` | no | Cleanly separates liveness, the executor call, and finalization |
| `ArrayFrame` / `CollectionFrame` | only for composite frames | Type-specific nested bodies do not receive ordinary terminal lifecycle handling |

Do not create a multi-frame terminal buffer merely to obtain one timer pair. It would change
completion/finalization order and the synchronous pull contract. The selected fallback within the
allowed cost is a sparse timer pair around one executor call, not a timer pair on every frame.

## Candidate measurement boundary

Instrument `AbstractExecutor.ExecutionTerminal` immediately around:

```text
AbstractExecutor.this.execute(frame)
```

The complete successful sequence remains:

```text
frame.isAlive()
    -> optional start timer
    -> AbstractExecutor.this.execute(frame)
    -> optional stop timer and record
    -> frame.doFinally()
```

The interval includes the virtual call and all work intentionally owned by a custom executor. It
excludes:

- liveness/cancellation checks before execution;
- `LatticeHotSource.accept` and terminal dispatch;
- DIRECT/STAGED cache, request, pull, routing, and acquisition behavior;
- `doFinally` and `doFinallyWithError`;
- logging and error handling after a thrown body.

Only normally returned executor calls create samples. If the sampled call throws or signals
cancellation, reset the cadence and discard the interval before following the existing error and
finalization behavior. Do not time or classify failures.

## Diagnostic API and ownership

Keep the existing `AbstractExecutor(int cpu)` constructor and every existing subclass source-
compatible. It installs no clock or recorder.

Add one protected diagnostic constructor accepting:

- the executor CPU;
- a positive fixed sampling interval;
- a `LongSupplier` nanosecond clock; and
- a `LongConsumer` elapsed-nanosecond recorder.

Validate that interval, clock, and recorder are all present for the diagnostic form. Add adjacent
`///` documentation for the new constructor and any helper method, as required by the repository
workflow.

The executor terminal owns the countdown as a plain field. It has one synchronous caller: the
pinned fragment worker connected through `LatticeHotSource`. No atomic access or memory fence is
needed for cadence. The existing constructor leaves the clock and recorder `null`; its normal fast
path performs the same executor call with no timer reads and no recorder invocation.

Use a fixed interval of 256 eligible executor calls. Sample the 256th call, reset to 256 after the
call whether it succeeds or throws, and repeat. Do not make the cadence a JMH parameter and do not
change it after results are observed.

The calibration `CountingExecutor` supplies `System::nanoTime` and a clone-local recorder that
publishes into two benchmark-owned `PaddedLongAdder` instances indexed by the clone CPU:

```text
sample count
elapsed nanosecond total
```

Those are the only sampled-call publications. No Micrometer meter, registry lookup, allocation,
formatting, or logging occurs in the executor. Different workers write distinct padded CPU cells;
the JMH owner reads them at iteration boundaries with the same acquire snapshots used for completion
counters. This is diagnostic evidence transport, not production controller state.

Do not add a static diagnostic lease, modify `FragmentConfig`, expose the sampler through
`ControlPlaneFragment`, or connect it to `FragmentDecisionTree`.

## Benchmark shape

Add one state and benchmark method to `FragmentPathCalibrationBenchmark`, reusing `PathState`, the
real fragment graph, natural handle layout, fixed completion windows, and existing forced-mode
lease. The state parameters are exactly:

```text
mode:        DIRECT, STAGED
sourceShape: PLENTIFUL, SCARCE
workRounds:  24, 80, 96
```

Keep the established protocol:

- two same-kind workers on the existing logical CPU set;
- fixed batch target 32;
- three forks;
- three 3-second warmups;
- five 5-second measurements; and
- 1,048,576-frame completion windows.

At each warmup and measurement boundary snapshot per-worker sample counts and elapsed totals. Retain
measurement deltas only for decision evidence, but log warmup deltas so startup contamination is
visible. At trial teardown report, in stable CPU order:

- mode, source shape, rounds, batch, worker CPUs, and isolated body cost;
- per-iteration sample-count and elapsed-total deltas;
- per-iteration `elapsed / samples` estimates;
- aggregate measurement sample counts and elapsed totals per worker;
- aggregate fork-worker estimates;
- existing throughput and per-worker completion evidence; and
- live handle and registered worker counts as fixture assertions only.

An estimate with zero samples is invalid and fails the run. Do not average workers or forks before
checking the retained ranges. Do not add an EWMA, percentile tuning, outlier removal, or mode-specific
normalization.

## Predeclared signal gates

Use the aggregate measurement estimate for each worker in each fork as the retained validation
interpretation. Each work point therefore has 24 estimates:

```text
2 modes * 2 source shapes * 3 forks * 2 workers
```

All following gates must pass.

### Separation

Require both monotonic medians and a usable retained gap:

```text
median(24) < median(80) < median(96)

max(all 80-round estimates) + 5 ns
    <=
min(all 96-round estimates)
```

The extra 5 ns is a robustness margin, not the future production threshold. Do not weaken it after
the run.

### Worker and fork stability

For each of rounds 24, 80, and 96, calculate the median across all 24 retained estimates. Every
estimate must lie within:

```text
max(5 ns, 10% of that work point's median)
```

This single rule bounds worker and fork dispersion before any group averaging.

### Tier and availability neutrality

For each work point, calculate medians for the four `(mode, sourceShape)` groups. The range from the
lowest to highest group median must be no more than 5 ns. Apply the rule separately at 24, 80, and
96 rounds.

The 24-round rule is the explicit fixed-overhead check. A common constant offset is tolerable; a
DIRECT/STAGED or one/two-handle shift is not.

If any gate fails, reject the signal. Do not alter cadence, smooth the data, subtract per-mode
floors, or choose a threshold from overlapping observations.

## Instrumentation overhead gate

Measure two costs, using rounds 24 because it maximizes the fractional effect:

1. disabled-branch cost: compare the candidate build with sampling disabled against clean commit
   `3331747`;
2. enabled-sampling cost: compare diagnostic sampling enabled against disabled in the same candidate
   build.

Run both comparisons for:

- plentiful DIRECT; and
- scarce STAGED.

Use the same three-fork JMH protocol and retain fork-level throughput and participation. For each
comparison:

- loss in the median of the three fork scores must be at most 1%;
- the candidate run's lowest fork score must be at least 98% of the comparison run's lowest fork
  score; and
- completion dominance must remain within the corrected balanced regime already expected for the
  selected fixture.

Do not pair fork ordinals across separate JVM runs as if they shared state. JMH aggregate errors and
all raw fork scores remain in the record. If scheduling noise makes the result inconclusive, repeat
this exact overhead protocol once. Do not change interval 256. A repeated inconclusive result fails
the overhead gate.

## Deterministic tests

Extend the executor tests with a scripted `LongSupplier` and recorder. Prove:

- the existing constructor never reads the diagnostic clock or calls the recorder;
- the diagnostic form samples exactly the 256th eligible call and then every 256th call;
- the recorded interval includes the executor override's simulated work but excludes simulated
  liveness and `doFinally` time;
- a body exception records no sample and still follows `doFinallyWithError` without changing the
  existing lifecycle contract;
- cancellation records no sample and retains current finalization behavior;
- a normal sample records a positive elapsed value exactly once; and
- invalid constructor inputs fail before execution.

Add focused benchmark tests for snapshot deltas, zero-sample rejection, retained range construction,
the 5 ns separation margin, and the neutrality/stability calculations. Do not turn these formulas
into a reusable policy or statistics framework; local benchmark helpers are sufficient.

## Forced-path regression and evidence handling

The new benchmark must not modify the existing `workCostDecision` state. That state remains the
sampling-disabled forced-path regression. Standard forced DIRECT/STAGED overrides must not enable
the executor sampler implicitly.

Keep raw JMH JSON and logs outside the repository data tree unless the user explicitly requests
otherwise. Append to this blueprint:

- exact commands and host/toolchain identity;
- raw-file locations;
- all retained fork-worker estimates;
- group medians and stability bounds;
- separation extrema and margin;
- disabled and enabled overhead comparisons;
- participation evidence; and
- exactly one acceptance outcome.

Do not preserve a failed diagnostic API merely for convenience. If the signal fails, retain the
evidence in this document, remove its Core and benchmark instrumentation, rerun the build, and leave
only any tests or documentation that remain truthful.

## Stop conditions and outcomes

Stop immediately when any of these occurs:

- timing the executor override changes its execution or finalization order;
- implementation needs a timer pair on every frame;
- implementation needs a shared controller, lock, allocation, or registry operation in the hot
  path;
- the 80- and 96-round retained ranges overlap or leave less than the fixed 5 ns margin;
- a work point violates worker/fork stability or tier/availability neutrality;
- enabled or disabled instrumentation violates the overhead gate; or
- multiple benchmark changes would be required to explain a surprising regime.

The completion record must state exactly one result:

1. **Signal accepted.** Sparse executor-only timing passes separation, neutrality, stability, and
   overhead gates. It is ready to be integrated as the missing work-cost input by a later production
   tree blueprint. No threshold or selector is implemented here.
2. **Timing rejected.** Executor-only timing cannot provide the required resolution under the
   allowed cost. Remove the diagnostic seam and direct the next design to a different observable,
   specifically executor-supplied coarse work units or another non-timing body-cost declaration.
   Do not continue refining timing or start path-floor subtraction from these results.

## Verification

Run, at minimum:

```text
mise exec -- gradle :euhedral-core:test
mise exec -- gradle :benchmarks:test :benchmarks:assemble
mise exec -- gradle build
git diff --check
```

Search for accidental additions to `FragmentDecisionTree`, `ControlPlaneFragment`, normal policy
selection, or `EXPENSIVE_WORK_BOUNDARY_NS`. Confirm new documentation is ASCII and final status
contains only intended source, test, benchmark, and completion-record changes.

## Completion record

Outcome: **Signal accepted.** Sparse timing around only `AbstractExecutor.execute(frame)` passed the
separation, stability, tier/availability-neutrality, and overhead gates. The diagnostic seam is
retained for a later production-tree integration blueprint. This phase adds no work-cost threshold
and does not change normal DIRECT/STAGED selection.

### Implementation

- `AbstractExecutor` retains its existing sampling-disabled constructor and adds one protected,
  opt-in diagnostic constructor. Its terminal owns a plain countdown and reads the supplied clock
  only around every 256th live executor call. Failed and cancelled sampled calls reset the cadence
  but publish no sample; finalization stays outside the interval.
- `FragmentPathCalibrationBenchmark.executorBodyCost` is limited to rounds 24, 80, and 96 under
  forced DIRECT/STAGED and plentiful/scarce natural-handle fixtures. Its `CountingExecutor` clones
  publish sample counts and elapsed nanoseconds into worker-indexed padded counters.
- The benchmark retains raw warmup and measurement deltas, per-iteration estimates, and aggregate
  fork-worker estimates. A trial-only system property,
  `euhedral.fragment.bodyTiming.enabled=false`, disables the sampler solely for the reproducible
  same-build overhead control; it does not affect normal scheduler behavior.
- Deterministic Core tests cover disabled execution, exact cadence, timing boundaries, error and
  cancellation behavior, positive sample publication, and constructor validation. Benchmark tests
  cover deltas, zero-sample rejection, retained ranges, separation, stability, and neutrality.

### Environment and raw evidence

The validation host was the established Intel Core i9-14900K fixture: one socket, 24 physical
cores, 32 logical CPUs, and diagnostic worker CPUs 0 and 6. The run used Linux
`7.0.0-28-generic`, OpenJDK 21.0.2, and Gradle 9.6.1. The candidate started from commit
`3a1c7549c90a4e2cc04ee5570fe988994007ae96`; disabled-branch comparison used clean commit
`33317474673b2834ad774b2c76adfa35b8a41fef`.

Raw evidence is outside the repository at:

- `/tmp/euhedral-phase6-20260811/executor-body-validation.json`
- `/tmp/euhedral-phase6-20260811/executor-body-validation-jmh.log`
- `/tmp/euhedral-phase6-20260811/executor-body-validation.log`
- `/tmp/euhedral-phase6-20260811/retained-estimates.csv`
- `/tmp/euhedral-phase6-20260811/signal-gates.txt`
- `/tmp/euhedral-phase6-20260811/{baseline,candidate}-disabled-*.json`
- `/tmp/euhedral-phase6-20260811/candidate-sampling-disabled-*.json`
- `/tmp/euhedral-phase6-20260811/repeat-sampling-{disabled,enabled}-*.json`

The signal matrix command was:

```text
mise exec -- java --enable-native-access=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dlogback.configurationFile=/tmp/euhedral-phase6-20260811/phase6-logback.xml \
  -cp benchmarks/build/euhedral-benchmark.jar:benchmarks/build/lib/* \
  org.openjdk.jmh.Main '.*FragmentPathCalibrationBenchmark.executorBodyCost' \
  -p mode=DIRECT,STAGED -p sourceShape=PLENTIFUL,SCARCE \
  -p workRounds=24,80,96 -p handleLayout=NATURAL \
  -o /tmp/euhedral-phase6-20260811/executor-body-validation-jmh.log \
  -rf json -rff /tmp/euhedral-phase6-20260811/executor-body-validation.json
```

The annotations supplied three forks, three 3-second warmups, five 5-second measurements, and
1,048,576-frame completion windows. Overhead controls used the same command shape and annotations,
restricted to rounds 24 and each of `mode=DIRECT, sourceShape=PLENTIFUL` and
`mode=STAGED, sourceShape=SCARCE`. Sampling-disabled same-build runs added exactly:

```text
-Deuhedral.fragment.bodyTiming.enabled=false
```

The dormant-branch baseline used `workCostDecision` at commit `3331747`; the candidate used the
same benchmark and parameters. The permitted repeat reran both enabled and disabled controls for
both fixtures without changing any argument, cadence, or bound.

### Retained signal evidence

Each table cell contains `[worker 0, worker 1]` nanoseconds for one fork, in execution order.

| Rounds | Mode | Source shape | Fork 1 | Fork 2 | Fork 3 |
|-------:|------|--------------|--------|--------|--------|
| 24 | DIRECT | PLENTIFUL | [35.968, 35.932] | [36.014, 35.721] | [35.944, 35.544] |
| 24 | DIRECT | SCARCE | [36.163, 35.946] | [36.122, 35.756] | [36.421, 36.036] |
| 24 | STAGED | PLENTIFUL | [37.545, 37.909] | [39.370, 37.058] | [37.980, 38.372] |
| 24 | STAGED | SCARCE | [35.952, 36.277] | [36.285, 36.280] | [36.650, 36.178] |
| 80 | DIRECT | PLENTIFUL | [85.635, 85.312] | [85.624, 86.115] | [86.096, 85.418] |
| 80 | DIRECT | SCARCE | [85.806, 85.784] | [86.352, 85.543] | [85.495, 86.253] |
| 80 | STAGED | PLENTIFUL | [85.977, 86.915] | [85.546, 85.232] | [87.393, 87.337] |
| 80 | STAGED | SCARCE | [85.963, 85.842] | [88.421, 85.276] | [86.330, 86.314] |
| 96 | DIRECT | PLENTIFUL | [99.255, 99.432] | [99.940, 99.225] | [99.772, 99.830] |
| 96 | DIRECT | SCARCE | [100.402, 100.242] | [100.571, 100.192] | [100.966, 100.029] |
| 96 | STAGED | PLENTIFUL | [101.304, 102.006] | [103.118, 102.397] | [101.597, 101.099] |
| 96 | STAGED | SCARCE | [99.808, 99.767] | [100.686, 99.966] | [100.170, 100.315] |

All 24 retained estimates at each point passed the fixed stability rule:

| Rounds | Minimum ns | Median ns | Maximum ns | Allowed deviation ns | Observed maximum deviation ns |
|-------:|-----------:|----------:|-----------:|---------------------:|------------------------------:|
| 24 | 35.544 | 36.170 | 39.370 | 5.000 | 3.200 |
| 80 | 85.232 | 85.903 | 88.421 | 8.590 | 2.518 |
| 96 | 99.225 | 100.217 | 103.118 | 10.022 | 2.901 |

The four `(mode, sourceShape)` group-median spans were 2.006 ns at rounds 24, 0.816 ns at rounds
80, and 2.199 ns at rounds 96, all below the 5 ns neutrality limit. The work-point medians were
strictly monotonic. The primary retained separation was:

```text
max(rounds 80) = 88.420588 ns
min(rounds 96) = 99.224869 ns
raw gap        = 10.804281 ns
gap after the required 5 ns margin = 5.804281 ns
```

### Overhead and participation evidence

The dormant-branch comparison passed on its first run:

| Fixture | Baseline median frames/s | Candidate median frames/s | Median loss | Lowest-fork ratio |
|---------|-------------------------:|--------------------------:|------------:|------------------:|
| DIRECT / plentiful | 58,051,300 | 58,580,058 | -0.91% | 98.98% |
| STAGED / scarce | 30,257,551 | 30,473,450 | -0.71% | 99.52% |

The initial enabled-versus-disabled run passed both median-loss bounds but the STAGED/scarce
lowest-fork ratio was 97.87%, 0.13 percentage points below the 98% floor. This was treated as the
blueprint's one allowed inconclusive result. The complete protocol was repeated once unchanged.
The repeat passed:

| Fixture | Disabled median frames/s | Enabled median frames/s | Median loss | Lowest-fork ratio |
|---------|--------------------------:|-------------------------:|------------:|------------------:|
| DIRECT / plentiful | 59,697,476 | 60,581,871 | -1.48% | 101.69% |
| STAGED / scarce | 31,730,772 | 32,018,621 | -0.91% | 99.91% |

Across all retained signal and overhead fork reports, aggregate completion dominance stayed in the
corrected balanced regime: 0.500006-0.504978 for DIRECT/plentiful and 0.500023-0.528667 for
STAGED/scarce. No worker disappeared and no new fork-level regime appeared.

### Acceptance conclusion

The measured value contains a common executor-call/completion-accounting offset, visible in the
24-round control, but its shift by tier and handle availability is bounded well below the required
80-versus-96 separation. The signal is therefore sufficiently tier-neutral and availability-neutral
for the already-designed broad cheap/transition/expensive production input. Selecting and
integrating the conservative production boundary remains the next blueprint; it is not implemented
here.
