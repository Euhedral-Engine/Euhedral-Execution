# Phase 14: Acquisition-Contention Signal Validation Blueprint

Status: complete

Plan:
[
`phase-14-acquisition-contention-signal-validation.md`](../../plans/fragment-decision-tree/phase-14-acquisition-contention-signal-validation.md)

Blueprint intensity: maximum

Implementation intensity: high

## Signal ownership and accounting

Each worker-local `UpstreamQueue` owns one dedicated default `FlowRecorder`. Do not reuse the
fragment throughput, service, or unused batch recorder. This keeps the physical meaning adjacent
to handle traversal and allows eventual policy code to read the same worker-local queue without
global publication.

Within each eligible `pull` invocation initialize primitive locals:

```text
attempts = 0
failedAcquires = 0
```

Increment `attempts` immediately before every real `handle.acquireLock()` call. Increment
`failedAcquires` only when that call returns false. Null polls, completed handles, demand/lifecycle
early returns, delegated pull results, and productivity transitions are not acquisition attempts.
After the loop, if `attempts > 0`, scale and record exactly once. If `attempts == 0`, do not update
the recorder or replace the last raw observation.

For diagnostics retain only the latest observed cycle's attempts, failures, scaled value, and an
observation-present flag as plain queue-owner fields. No shared/cumulative production counter is
added. Reset these fields and the recorder only through the fragment's existing owner-thread trial
reset.

## Fixed-point representation

Define one documented constant in `UpstreamQueue`:

```text
ACQUIRE_CONTENTION_SCALE = 1_000_000L
```

The scaled value is floor(`failedAcquires * SCALE / attempts`). Inputs must satisfy
`0 <= failedAcquires <= attempts`; the pull loop establishes this, while the package-visible helper
validates it for tests. All-success is 0, all-failure is SCALE, and fractional cases truncate.

Use a checked fast path when multiplication is safe. For the theoretical overflow range, use
allocation-free base-10 long division for six digits, with modular addition that never forms an
overflowing product. The fallback must return the same floor result and keep every result in
`[0, SCALE]`. Test the largest realistic Phase 14 attempt count (32), the fast-path boundary, and a
`Long.MAX_VALUE` denominator/numerator boundary.

`FlowRecorder.recordUnits(long)` supplies the existing alpha 0.05 unit EWMA, bootstrap behavior,
variance/trend, extrema decay, and 10 ms reset/window behavior. The fixed-point bound keeps squared
unit residuals near at most `1e12`, far inside finite double range, and the rolling recurrence
converges near `SCALE / alpha = 20,000,000`, far inside `long`. Normalize only on snapshot/policy
read as `fixedPoint / (double) SCALE`.

## Integration, lifecycle, and cost control

The signal defaults enabled and can be disabled only at JVM startup with a documented diagnostic
system property used by same-build JMH forks. A static-final gate lets the JIT remove the disabled
path. Enabled execution adds the two local increments, one ratio calculation, one recorder update,
and low-frequency plain last-observation stores. It adds no atomics, allocations, registry calls,
logging, per-frame updates, or cross-worker aggregation. The recorder's own existing update obtains
its timestamp; do not add any separate acquisition timer read.

`ControlPlaneFragment` retains its owner queue reference after `FlowThread` initialization, resets
the queue recorder in `CycleState.reset`, and appends an acquisition snapshot to the existing
benchmark-only `FragmentPolicySnapshot`. This does not alter policy inputs or mode selection.

## Deterministic tests

In `UpstreamQueueTest`, cover all success, all failure, 1/2, 1/3 truncation, a mixed fraction,
zero-attempt preservation for zero demand/no live queue, normalization, 32 attempts, the checked
multiplication boundary, and `Long.MAX_VALUE` arithmetic. Verify exactly one recorder observation
per pull loop by inspecting the recorder's last/average state, not by adding a hot callback.

Extend fragment reset/snapshot tests only where required to prove owner-local reset and normalized
interpretation. Benchmark helper tests cover formatting/normalization and the feedback phase
sequence.

## Experimental design and gates

Reuse `sourceToCoreCrossover` with explicit actual counts. Retain both forced modes at rounds
0/96/256 for P-only 7/7, 4/7, 2/7, 1/7 and mixed 32/23, 16/23, 8/23, 4/23, 1/23, but begin with a
one-fork smoke and omit duplicate retained rows where Phase 13 winner evidence plus new contention
snapshots is sufficient. Add 176 only to separate a transition. Preserve the 4/7 no-op fork values
individually.

At every retained boundary report actual source/productive/registered/active-polling counts, worker
identity/class/rank, forced mode, body estimate, throughput, completions/dominance, existing
source-by-worker acquisitions, and per-worker latest raw attempts/failures/scaled/normalized value
plus recorder raw/normalized EWMA. Use aggregate attempt-weighted failure fractions only offline to
assess whether equal pull-cycle weighting distorts the worker-local EWMA.

A resolved forced-path winner retains Phase 13's at least 5% advantage, non-overlapping 99.9% JMH
intervals, and same fork direction. Signal monotonicity requires clear abundant-versus-severe
separation within comparable mode/body rows, not adjacent perfection. Cross-topology support
requires comparable contention/body regions to imply comparable winner regions better than nominal
source/worker labels.

For overhead, run signal-disabled and enabled same-build forks at mixed 32/23 DIRECT/no-op and mixed
1/23 DIRECT/no-op. Pass only when median loss is at most 1% and the enabled lowest fork is at least
98% of the disabled lowest fork for both controls.

Add one bounded benchmark diagnostic that holds the mixed 1/23 workload and body point fixed while
changing a benchmark-only forced mode DIRECT -> STAGED -> DIRECT at completed-batch boundaries.
Capture settled contention after each phase without changing production selection. If STAGED moves
contention enough to cross back into the DIRECT region, classify whether body-cost structure or
simple hysteresis can prevent reversal; do not implement that controller.

## Acceptance and completion

Investigate attempt/failure boundaries, scaling, handle reinsertion, completion, productivity,
worker participation, and traversal cycles before treating any anomaly as policy evidence. Append
the requested 23-part completion record, raw evidence locations, smallest supported explicit tree,
and exactly one Outcome 1-4.

Run:

```text
mise exec -- gradle :euhedral-core:test --no-daemon
mise exec -- gradle :benchmarks:test --no-daemon
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck --no-daemon
mise exec -- gradle build --no-daemon
git diff --check
```

Retain raw benchmark JSON/logs outside source-controlled repository data.

## Completion record

### 1-7. Accounting, representation, and recorder behavior

The candidate counted exactly one attempt immediately before each real
`UpstreamHandle.acquireLock()` invocation reached after a non-null, non-complete handle poll.
Null polls, completed handles, zero/nonpositive demand, no-live-source returns, delegated pull
results, productivity changes, and handle reinsertion were not attempts. A failed acquire was
counted only when that invocation returned false. Both consumer pulls and request-only pulls used
the same boundary.

Each eligible pull loop held two plain `long` locals, `attempts` and `failedAcquires`. One candidate
observation was emitted after loop exit only when `attempts > 0`; a zero-attempt loop did not record
zero or alter prior state. No atomics, shared acquisition counters, per-attempt recorder calls,
allocations, logging, registry access, or extra timer reads were added by the candidate boundary.

The predeclared fixed-point scale was `1_000_000L`. It supplied six decimal digits, much finer than
plausible scheduler thresholds, while bounding all recorder inputs to `[0, 1_000_000]`. The tested
calculation used floor truncation: all success `0`, all failure `1_000_000`, `1/2 = 500_000`,
`1/3 = 333_333`, and `2/3 = 666_666`. A checked common path used
`failures * SCALE / attempts` only below the multiplication boundary. An allocation-free decimal
long-division fallback produced the same floor result above it. Deterministic tests passed for the
32-source fixture, the fast-path boundary, and `(Long.MAX_VALUE - 1) / Long.MAX_VALUE` against a
test-only `BigInteger` oracle. The fallback was never reachable in the benchmark fixture.

The candidate used a dedicated default `FlowRecorder` owned by each worker-local `UpstreamQueue`;
it did not overload throughput, service, or the semantically unused batch recorder. `FlowContext`
size did not change. One queue reference plus the existing recorder object's fields was the added
per-worker state. `FlowRecorder` uses alpha `0.05`, a nominal 10 ms discontinuity/window, first
observation bootstrap, double-backed unit EWMA/variance/trend, decaying extrema, and a rounded
rolling recurrence. At the chosen scale, squared residuals are at most about `1e12` and the
rolling recurrence converges near 20 million, so recorder arithmetic was safe. Normalization was
performed only on diagnostic reads by division by `1_000_000.0`.

All fixed-point, bootstrap, normalization, zero-attempt, reset, and arithmetic-boundary tests
passed before measurement. Because the candidate failed its acceptance gates, the production
instrumentation, dedicated recorder, snapshots, mutable benchmark mode lease, and their tests were
then removed. The final source tree therefore retains evidence in this record but no rejected hot
path or controller-facing field.

### 8. Implementation overhead

The overhead gate was predeclared as at most 1% median loss and an enabled lowest fork at least 98%
of the disabled lowest fork. Both controls used the same candidate build, forced DIRECT, no-op
body, three forks, two 2-second warmups, and three 3-second measurements.

| Mixed control | Enabled fork means, Mframes/s | Disabled fork means, Mframes/s | Median change | Lowest-fork ratio | Gate |
|---------------|--------------------------------|---------------------------------|--------------:|------------------:|------|
| 32 sources / 23 workers | 238.626 / 186.651 / 201.344 | 230.395 / 206.032 / 224.259 | -10.22% | 90.59% | fail |
| 1 source / 23 workers   | 14.193 / 14.466 / 14.720    | 13.439 / 13.707 / 13.843    | +5.53% | 105.61% | pass |

The abundant control fails both bounds by a wide margin. The severe control's gain does not rescue
the candidate; it demonstrates that the cost is regime-dependent. The expensive operation is not
the two local increments alone: the existing general `FlowRecorder.recordUnits` path also obtains
time, maintains interval/rate statistics, variance/trends, and extrema. Paying that full update on
every eligible pull cycle consumed a meaningful fraction of the abundant DIRECT payoff.

### 9-11. Raw cycle and recorder observations

Latest-cycle snapshots commonly contained one attempt because a successful batch-sized service
ends the loop and a one-source failed pass is bounded to one traversal. Abundant rows occasionally
showed two or three attempts. Representative exact snapshots were:

```text
P-only 1/7 no-op DIRECT:
    attempts=1 failures=1 scaled=1000000 normalized=1.0
    recorderAverageUnits=991225.068 normalized=0.991225

P-only 1/7 no-op DIRECT, successful latest cycle:
    attempts=1 failures=0 scaled=0 normalized=0.0
    recorderAverageUnits=962285.022 normalized=0.962285

P-only 1/7 no-op STAGED:
    attempts=1 failures=1 scaled=1000000 normalized=1.0
    recorderAverageUnits=1000000.0 normalized=1.0

mixed 32/23 no-op DIRECT:
    latest attempts range=1-3
    final worker recorder normalized range=0.013-0.193 across retained overhead forks

mixed 1/23 no-op DIRECT:
    latest attempts=1
    final worker recorder normalized range=0.932-1.000 across retained overhead forks
```

Thus raw cycle values were deliberately discrete while the recorder supplied a continuous recent
estimate. The recorder bootstrap and fixed-point interpretation were correct; no scaling defect
was observed.

### 12-15. P-only, mixed, body-cost, and forced-path mapping

The bounded mapping used one-fork 1-second warmup/measurement diagnostics after the overhead gate,
because a failed cost gate made a full Phase 13-quality retained surface unwarranted. Phase 13's
existing three-fork winner evidence remains the path classification authority. Phase 14 reran all
no-op source rows and only abundant/extreme rounds 96 and 256 body endpoints in both modes.

P-only final worker-median normalized recorder values:

| Sources/workers | Rounds | DIRECT contention | STAGED contention | Phase 13 path region |
|-----------------|-------:|------------------:|------------------:|----------------------|
| 7/7 | 0   | 0.111 | 0.141 | DIRECT |
| 4/7 | 0   | 0.378 | 0.618 | DIRECT, discrete throughput regime retained |
| 2/7 | 0   | 0.825 | 1.000 | DIRECT |
| 1/7 | 0   | 0.976 | 1.000 | unresolved/DIRECT-leaning |
| 7/7 | 96  | 0.483 | 0.245 | DIRECT |
| 1/7 | 96  | 0.983 | 0.972 | STAGED |
| 7/7 | 256 | 0.582 | 0.267 | DIRECT |
| 1/7 | 256 | 0.930 | 0.943 | STAGED |

Mixed final worker-median normalized recorder values:

| Sources/workers | Rounds | DIRECT contention | STAGED contention | Phase 13 path region |
|-----------------|-------:|------------------:|------------------:|----------------------|
| 32/23 | 0   | 0.082 | 0.094 | DIRECT |
| 16/23 | 0   | 0.259 | 0.265 | DIRECT |
| 8/23  | 0   | 0.188 | 0.694 | DIRECT; adjacent non-monotonic signal row |
| 4/23  | 0   | 0.705 | 1.000 | DIRECT |
| 1/23  | 0   | 0.986 | 1.000 | DIRECT cheap return |
| 32/23 | 96  | 0.208 | 0.131 | DIRECT |
| 1/23  | 96  | 0.996 | 1.000 | unresolved |
| 32/23 | 256 | 0.307 | 0.145 | DIRECT |
| 1/23  | 256 | 0.998 | 1.000 | STAGED |

Current body measurements were approximately 16-34 ns on P-only no-op rows, 103-107 ns at P-only
rounds 96, and 244-247 ns at P-only rounds 256. Mixed P/E medians were about 106-117 / 183-215 ns
at rounds 96 and 256-270 / 452-482 ns at rounds 256. The extreme 1/23 no-op body remained inflated,
as in Phase 13.

The signal separates abundant from severe contention in both topologies, but it does not define
one path-independent state. At fixed 7/7 abundance, increasing body cost moved DIRECT contention
from `0.111` to `0.582`, while STAGED moved only from `0.141` to `0.267`. At mixed 32/23/256,
DIRECT was `0.307` and STAGED `0.145`. Conversely STAGED saturated at 1.0 earlier under scarcity:
P-only 2/7 and mixed 4/23 were already effectively 1.0 at no-op. Source scarcity, body duration,
and selected path all change the observation.

### 16. Path feedback

A same-live-graph mixed 1/23, rounds-256 diagnostic changed the benchmark-only forced mode after
fixed 1,048,576-completion phases:

```text
DIRECT -> STAGED -> DIRECT
worker-median normalized recorder:
0.999161 -> 1.000000 -> 0.999070

worker-mean normalized recorder:
0.989822 -> 1.000000 -> 0.991939
```

The mode change was visible but did not lower contention or cross into a low-contention DIRECT
region. This extreme case therefore did not demonstrate the feared immediate oscillatory reversal.
It did demonstrate saturation: once severe, the signal has little resolution with which to
distinguish the cheap DIRECT return from the expensive STAGED winner. Body cost can preserve those
two leaves, but acquisition contention adds little separation there.

### 17. Equal-cycle weighting

Equal cycle weighting materially differed from attempt-weighted acquisition fractions outside the
near-saturated tail. Representative `(attempt-weighted, cycle-EWMA worker median)` pairs were:

```text
P-only 7/7 no-op DIRECT:       (0.224, 0.111)
P-only 4/7 no-op DIRECT:       (0.547, 0.378)
mixed 16/23 no-op DIRECT:      (0.501, 0.259)
mixed 8/23 no-op DIRECT:       (0.421, 0.188)
mixed 32/23 rounds-256 DIRECT: (0.527, 0.307)
mixed 1/23 rounds-256 DIRECT:  (0.991, 0.998)
```

Differences reached 0.24 for DIRECT and 0.33 for STAGED in moderate rows. The last-cycle snapshots
confirmed that eligible cycles varied from one to three attempts in abundant rows, so equal cycle
weighting systematically emphasized short cycles. It retained directional endpoint separation but
did not faithfully summarize the physical attempt population. Per requirements, no attempt-weighted
production aggregation was substituted after observing this result.

### 18-20. Regimes, participation, and bug-first checks

The observed regimes were:

```text
low:       abundant/no-op DIRECT, roughly 0.08-0.11 median
middle:    moderate scarcity or abundant expensive DIRECT, roughly 0.19-0.58
high:      severe DIRECT, roughly 0.70-0.998
saturated: STAGED under severe/extreme scarcity, approximately 1.0
```

The P-only 4/7 no-op diagnostic remained visible rather than averaged away: DIRECT throughput was
119.962 Mframes/s in this short run, with median recorder contention 0.378 and worker range
0.353-0.546. Phase 13's statistically retained fast/slow fork split remains the evidence that this
row can enter a higher-contention discrete regime.

Every measured topology retained its Phase 13 workers: seven P-only workers or 23 mixed workers,
stable core/rank/class metadata, all active polling, none production parked, and positive aggregate
completion for every worker. Live/productive source counts remained exact and repeating sources
did not complete. Handle failures never exceeded attempts; successful service was derived as
attempts minus failures; failed live handles were reinserted; latest pull-cycle attempt counts were
bounded by the visible handle traversal. No contradiction was found in completed-handle handling,
productive state, registration, source completion, queue traversal, scale interpretation, or
worker participation. H4 is rejected.

The only implementation defect relative to acceptance is cost, not correctness. The intended use
of the general-purpose `FlowRecorder` brings substantially more hot-path arithmetic than the
conceptual "one EWMA update" cost description suggests.

### 21. Raw evidence locations and commands

Raw evidence is outside source-controlled repository data under
`/tmp/euhedral-phase14-20260813`:

- `smoke-p-only.{json,log}`;
- `overhead-enabled.{json,log}` and `overhead-disabled.{json,log}`;
- `map-p-noop.{json,log}` and `map-mixed-noop.{json,log}`;
- `map-p-body.{json,log}` and `map-mixed-body.{json,log}`; and
- `feedback.{json,log}`.

The host was `brandons-desktop`, Linux `7.0.0-28-generic` x86-64, Intel Core i9-14900K, 32 online
logical CPUs, 24 physical cores, one socket, seven retained P workers, and 23 retained mixed
workers. The toolchain was OpenJDK `21.0.2+13-58`, Gradle `9.6.1`, Zig `0.16.0`, and JMH `1.37`.
The source revision was `cd76dbe87d9b3944105cdb6cf629b0e9b8623db6` plus the temporary Phase 14
candidate during measurement.

The retained overhead command shape was:

```text
mise exec -- java -XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dlogback.configurationFile=benchmark-logback.xml \
  -cp 'benchmarks/build/euhedral-benchmark.jar:benchmarks/build/lib/*' \
  org.openjdk.jmh.Main \
  'io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmark.sourceToCoreCrossover' \
  -p topology=FULL_MACHINE -p productiveSources=32,1 -p ratioDivisor=0 \
  -p workRounds=0 -p mode=DIRECT -p handleLayout=NATURAL \
  -f 3 -wi 2 -w 2s -i 3 -r 3s -foe true -rf json -rff <evidence.json> \
  -jvmArgsAppend '<standard native/export flags> \
    -Deuhedral.fragment.acquireContention.enabled=<true|false>'
```

Mapping used the same launcher with one fork, one 1-second warmup, and one 1-second measurement;
it was diagnostic routing evidence after the overhead failure, not a replacement for Phase 13's
three-fork winner evidence.

### 22. Smallest supported decision-tree relationship

The experiment does not support replacing nominal scarcity with this candidate. The smallest
truthful relationship is descriptive only:

```text
pull-cycle acquire contention
    identifies abundant versus severe lock competition
    but is shifted by body duration and DIRECT/STAGED behavior
    and saturates near 1.0 in the extreme cheap and expensive leaves

body cost
    remains necessary to distinguish extreme-scarcity DIRECT/no-op
    from extreme-scarcity STAGED/meaningful-work
```

Source/worker ratio is still only an experimental proxy, but this equal-cycle, full-`FlowRecorder`
candidate cannot replace it in production. A later phase would need a materially cheaper bounded
recorder and evidence that its weighting semantics represent the intended physical population;
that is a new signal design, not tuning this rejected candidate.

### 23. Final outcome

**Outcome 3: signal rejected.** The pull-cycle fixed-point ratio is arithmetically correct and
directionally separates abundant from severe contention, but equal-cycle weighting materially
understates moderate attempt-weighted contention, path/body changes move the reading, extreme
scarcity saturates away useful resolution, and the abundant enabled control lost 10.22% median
throughput with a 90.59% lowest-fork ratio. It therefore cannot replace nominal scarcity under the
required cost and explanatory gates. All production instrumentation and benchmark-only support for
the rejected candidate were removed; production path selection and the idle mechanic remain
unchanged.
