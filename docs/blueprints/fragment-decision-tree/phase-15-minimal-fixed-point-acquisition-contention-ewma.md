# Phase 15: Minimal Fixed-Point Acquisition Contention EWMA Blueprint

Status: complete

Plan:
[`phase-15-minimal-fixed-point-acquisition-contention-ewma.md`](../../plans/fragment-decision-tree/phase-15-minimal-fixed-point-acquisition-contention-ewma.md)

Blueprint intensity: maximum

Implementation intensity: high

## State, arithmetic, and ownership

Add one package-local acquisition-specific smoother in `core.flow_control`. It owns exactly:

```text
long value
boolean initialized
```

The first sample bootstraps `value`. Later samples apply `value += (sample - value) / 16`. Inputs are
validated in `[0, 1_000_000]`; subtraction cannot overflow, truncation is deterministic, and the
state remains bounded between its old value and the sample. Integer truncation permits a terminal
error below 16 fixed-point units, which is at most 0.000015 normalized and irrelevant to future
thresholds. Reset writes zero and clears initialization; an uninitialized zero is never interpreted
as observed low contention.

Each worker-local `UpstreamQueue` owns one smoother. Fields and methods remain plain: queue traversal,
updates, and reset are worker-owned, while benchmark snapshots are explicitly best-effort. Add no
atomic, volatile, synchronized, clock, allocation-per-update, or cross-worker aggregation.

Use `ACQUIRE_CONTENTION_SCALE = 1_000_000L`. For valid `0 <= failures <= attempts`, the common ratio
is floor(`failures * SCALE / attempts`). The Phase 15 benchmark has batch 32 and at most 32 sources;
because demand is at most 32, its loop exits after the first successful acquire or one unsuccessful
traversal, proving at most 32 attempts and a numerator at most 32,000,000. Retain a cold helper for
larger public-API counts when multiplication would overflow. It must be allocation-free and exactly
match floor division.

## Acquisition boundary and lifecycle

In each eligible `UpstreamQueue.pull` invocation, initialize plain local `attempts` and
`failedAcquires`. After a non-null, non-complete handle is polled, increment `attempts` immediately
before `acquireLock()`. Increment `failedAcquires` only when that call returns false. Do not count
null polls, completed handles, lifecycle/demand exits, delegated no-acquire results, productivity,
release, or reinsertion.

After the loop, if instrumentation is enabled and `attempts > 0`, calculate one fixed-point fraction
and update once. Do nothing for zero attempts. A startup-fixed system property defaults the candidate
on and permits same-build JMH forks with the path disabled; it must not affect policy selection.

`ControlPlaneFragment` retains the initialized queue reference. Its existing `CycleState.reset`,
which is serviced by the owner thread while running, resets the queue smoother. A stopped worker may
reset synchronously under the existing lifecycle rule. Fragment policy snapshots add a compact
acquisition snapshot containing only enabled, initialized, fixed-point value, and diagnostic
normalization; production scheduling performs no floating-point conversion.

## Tests and benchmarks

Focused core tests cover first bootstrap, all-success, all-failure, mixed scaling, zero-attempt
preservation, stable equal samples, low-high-low monotonic response, bounds, reset, normalization,
failed-handle reinsertion, and completed-handle exclusion. Existing fragment reset testing is
extended to prove lifecycle reset.

If straightforward, add a small JMH comparison of baseline local bookkeeping versus ratio plus
smoother update. It is advisory only. Extend `sourceToCoreCrossover` reporting/validation so retained
worker snapshots provide fixed-point and normalized contention without changing its execution graph.

Run focused tests first, then:

```text
mise exec -- gradle :euhedral-core:test --no-daemon
mise exec -- gradle :benchmarks:test --no-daemon
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck --no-daemon
```

Build one candidate distribution and run mixed 32/23 and 1/23, forced DIRECT, rounds 0, natural
handles, three forks, two 2-second warmups, and three 3-second measurements, enabled versus disabled.
For each row require median loss `<= 1%` and enabled lowest fork at least `98%` of disabled lowest.
The abundant row is the stop gate.

Only if abundant passes, run one-fork bounded diagnostics for mixed 32/8/4/1 over 23 workers and
P-only 7/4/1 over 7 workers at rounds 0, plus mixed 32/1 at rounds 0/96/256. Preserve one observation
per cycle. A feedback transition is unnecessary unless adaptation differs materially from the
deterministic response test.

Finally run the requested full verification, inspect stale names, `git diff --check`, and status.
Append the 21 requested completion items, raw evidence locations, and exactly one Outcome 1-4. If
the abundant gate fails, remove candidate instrumentation and stop broader diagnostics before
recording Outcome 2.

## Bounded arithmetic comparison

The initial division update passed the abundant gate but narrowly missed the severe lowest-fork
bound: its severe median change was -0.064%, while the enabled-lowest/disabled-lowest ratio was
97.27% against the required 98%. The phase explicitly permits one bounded power-of-two alternative
when arithmetic cost narrowly fails. Replace only `/ 16` with signed `>> 4`, retain every ownership,
accounting, bootstrap, reset, scale, and benchmark rule above, and rerun both controls from the same
rebuilt candidate. Signed rounding is deterministic and remains bounded; positive steps may settle
within 15 units below the target, while negative steps round down and reach their lower target.

The shift alternative passed the abundant controls but failed the severe median gate by 6.36%.
After reviewing both bounded alternatives, the developer explicitly authorized retaining the
division implementation despite its narrow 97.27% severe lowest-fork ratio. Restore `/ 16`, retain
the candidate, and continue the bounded signal and body-cost checks. Record this as an explicit
acceptance exception rather than describing the original predeclared severe gate as passed.

## Completion record

### 1. Exact smoother implementation

`AverageFlow` is a package-local final class containing one plain `long value` and
one plain `boolean initialized`. The first valid sample assigns `value` and sets validity. Later
updates execute:

```java
value += (sample - value) / 16;
```

It has no clock, atomics, volatility, allocations per update, interval/rate state, variance, trend,
extrema, rolling recurrence, or `FlowRecorder` call.

### 2. Fixed-point scale

`UpstreamQueue.ACQUIRE_CONTENTION_SCALE` is `1_000_000L`. Recording and future policy reads remain
integer fixed point. `double` normalization exists only in diagnostic getters and benchmark
snapshots.

### 3. Smoothing alpha, divisor, and shift

The retained alpha is `1/16` with divisor 16. The bounded alternative used signed `>> 4` only during
the permitted arithmetic comparison and was not retained.

### 4. Arithmetic selection

Division has symmetric Java truncation toward zero, deterministic monotonic movement, and the
closest simple update-based alpha to Phase 14's approximately 0.05. It passed the abundant cost
gate and severe median-loss gate. The shift alternative failed the severe median gate by 6.36%, so
the division form was restored. The developer explicitly accepted its separate 97.27% severe
lowest-fork ratio as a narrow exception to the predeclared 98% bound.

### 5. State ownership

Each thread-local `UpstreamQueue` owns one smoother beside its existing worker-local traversal and
productivity state. No state is shared or aggregated across workers. Snapshot reads are best-effort
diagnostics and do not create a publication contract.

### 6. Bootstrap semantics

Before the first eligible cycle, `initialized == false`; the stored zero is invalid and diagnostic
normalization returns `NaN`. The first valid sample bootstraps exactly, so observed zero contention
is distinguishable from no history.

### 7. Reset semantics

Reset assigns zero and clears initialization. `ControlPlaneFragment.CycleState.reset()` invokes the
queue reset through the existing owner-thread reset handoff; a stopped worker retains the existing
synchronous lifecycle rule. Focused queue and live fragment tests passed.

### 8. Acquisition accounting boundary

One attempt is counted immediately before each real `UpstreamHandle.acquireLock()` reached after a
non-null, non-complete poll. A failure is counted only when that invocation returns false. Nulls,
completed handles, nonpositive demand, no-live-source exits, delegated no-acquire work,
productivity, release, and reinsertion are not counted. Failed live handles are still reinserted.
Exactly one failed/attempt fraction is recorded after a completed eligible pull cycle; zero attempts
perform no update.

### 9. Realistic maximum pull-loop attempt count

The Phase 15 scheduler fixture has fixed demand/batch 32 and at most 32 visible repeating sources.
Its loop stops after a successful batch-sized pull or after one failed traversal, so the proven
fixture maximum is 32 acquisitions per cycle. The general public queue API can represent larger
counts, which is why scaling retains a cold overflow path.

### 10. Ratio arithmetic and overflow argument

The common path computes floor(`failedAcquires * 1_000_000 / attempts`). At the fixture maximum its
numerator is at most 32,000,000, far below `Long.MAX_VALUE`. The implementation checks failures
against `Long.MAX_VALUE / SCALE`; only wider artificial/public inputs use an allocation-free
six-digit decimal long-division fallback. Tests compare the fallback at `(Long.MAX_VALUE - 1) /
Long.MAX_VALUE` with a test-only `BigInteger` oracle.

### 11. Abundant enabled/disabled fork means

For mixed 32 sources / 23 registered workers, forced DIRECT and rounds 0, division-enabled fork
means were `240.399 / 187.922 / 160.258` Mframes/s. Same-build disabled fork means were
`141.201 / 222.705 / 185.390` Mframes/s.

### 12. Abundant median overhead

The median fork means were 187.922 enabled and 185.390 disabled Mframes/s: enabled throughput changed
by `+1.366%`, or a median loss of `-1.366%`. This passes the required loss-at-most-1% bound.

### 13. Abundant lowest-fork ratio

The enabled lowest fork was 160.258 Mframes/s and the disabled lowest fork was 141.201 Mframes/s,
for a `113.50%` ratio. This passes the required 98% bound.

### 14. Severe-contention overhead result

For mixed 1/23 DIRECT/no-op, division-enabled fork means were
`14.567 / 13.309 / 12.931` Mframes/s and disabled means were
`14.059 / 13.318 / 13.293` Mframes/s. Median throughput changed by `-0.064%`, passing the median
loss bound. The lowest-fork ratio was `97.27%`, narrowly below 98%; the developer explicitly
accepted this exception. The shift comparison did not provide a better acceptable candidate:
it passed abundant controls but lost `6.36%` at the severe median.

### 15. Representative contention across source scarcity

One-fork diagnostic worker medians were:

| Topology | Sources/workers | Rounds | Contention |
|----------|-----------------|-------:|-----------:|
| mixed | 32/23 | 0 | 0.064 |
| mixed | 8/23 | 0 | 0.204 |
| mixed | 4/23 | 0 | 0.776 |
| mixed | 1/23 | 0 | 0.986 |
| P-only | 7/7 | 0 | 0.149 |
| P-only | 4/7 | 0 | 0.472 |
| P-only | 1/7 | 0 | 0.939 |

Abundance remains low, moderate scarcity is materially higher, and severe scarcity approaches
saturation in both topologies.

### 16. Body-cost interaction

At mixed 32/23 DIRECT, worker-median contention increased `0.064 -> 0.176 -> 0.294` for rounds
`0 -> 96 -> 256`. At mixed 1/23 it remained near saturation while increasing
`0.986 -> 0.999 -> 0.999`. Body cost therefore still changes observed acquisition contention; the
signal is not a source-count proxy.

### 17. Adaptation behavior

The deterministic update test bootstraps at zero, moves monotonically upward for repeated SCALE
samples, and moves monotonically downward for repeated zero samples while staying in range. From
zero, 256 high updates reach `999_985` (`0.999985`); 256 subsequent low updates reach `15`
(`0.000015`). Adaptation is measured in eligible pull cycles, not time.

### 18. Phase 14 ordering

The useful Phase 14 physical ordering was preserved. Exact values shifted modestly under alpha
`1/16`, but abundant, moderate, severe, and near-saturated regions remained clearly ordered. Equal
cycle weighting remains unchanged.

### 19. Bugs and discrete regimes

No scaling, accounting, bootstrap, reset, ownership, reinsertion, or completion defect was found.
The principal experimental regime was high abundant-path fork variance, also visible in prior
phases; both the median and lowest-fork controls were retained for that reason. The division/shift
comparison was limited to the two allowed forms. No feedback transition was rerun because the
minimal deterministic response and extreme saturation did not show materially different adaptation
from Phase 14.

### 20. Raw evidence locations

Raw JSON and logs are outside source-controlled data under
`/tmp/euhedral-phase15-20260813.k1ubaR`:

- `abundant-{enabled,disabled}.{json,log}` and `severe-{enabled,disabled}.{json,log}` contain the
  retained division controls;
- `shift-abundant-{enabled,disabled}.{json,log}` and
  `shift-severe-{enabled,disabled}.{json,log}` contain the bounded rejected alternative; and
- `map-mixed-noop.{json,log}`, `map-p-noop.{json,log}`, and `map-mixed-body.{json,log}` contain the
  bounded signal checks.

The host/toolchain was Linux `7.0.0-28-generic` x86-64, OpenJDK `21.0.2+13-58`, Gradle `9.6.1`,
Zig `0.16.0`, and JMH `1.37`. A separate artificial smoother microbenchmark was not added because
the existing full scheduler control directly measured the authoritative update location.

Verification passed:

```text
mise exec -- gradle :euhedral-core:test --no-daemon
mise exec -- gradle :benchmarks:test --no-daemon
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck --no-daemon
mise exec -- gradle build --no-daemon
```

### 21. Completion outcome

**Outcome 1: minimal contention smoother accepted under an explicit cost-gate exception.** The
purpose-built fixed-point smoother passes the abundant hot-path gate, preserves the useful
acquisition-contention signal, and replaces none of the production selector or idle policy. The
severe median loss also passes, while its 97.27% lowest-fork ratio narrowly misses the original 98%
bound and is retained only because the developer explicitly authorized that exception. The Phase 14
cost was attributable to the general-purpose `FlowRecorder`; retain this minimal smoother, and leave
DIRECT/STAGED integration for the next phase.
