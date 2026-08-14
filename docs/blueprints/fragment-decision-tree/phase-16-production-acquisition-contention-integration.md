# Phase 16: Production Acquisition-Contention Integration Blueprint

Status: completed - retained

Plan:
[`phase-16-production-acquisition-contention-integration.md`](../../plans/fragment-decision-tree/phase-16-production-acquisition-contention-integration.md)

Blueprint intensity: maximum

Implementation intensity: high

## Production data flow and ownership

Keep `AcquisitionContentionSmoother` and all `UpstreamQueue.pull` accounting unchanged. Add one
allocation-free queue read returning the fixed-point EWMA or `-1` when uninitialized. At each
completed productive fragment batch, read that scalar once and pass it to
`FragmentControlPolicy.completeBatch`. No contention read occurs per frame, in the idle predicate,
or in polling-capacity selection.

`-1` is only an internal invalid sentinel; valid values remain `[0, 1_000_000]`. The existing
boolean validity state remains the authority. Reset continues to clear the smoother through
`CycleState.reset` on the queue owner thread. Diagnostic snapshots remain best-effort and perform
the only floating-point normalization.

## Explicit decision tree

Isolate the host-derived calibration constant as:

```text
LOW_ACQUIRE_CONTENTION_MAX = 650_000
```

Normal selection is:

```text
invalid registered-worker state
or productiveHandles >= registeredWorkers
    -> DIRECT

insufficient body history
    -> DIRECT

contention integration disabled for comparison
or acquisition contention uninitialized
    -> existing 90/95 ns body tree

body cost <= 90 ns
    -> DIRECT

contention <= 650_000
    -> DIRECT

body cost >= 95 ns
    -> STAGED

otherwise
    -> current settled mode
```

This keeps the Phase 13/14 shapes without fitting extra buckets: abundant and moderate contention
extend DIRECT; high contention delegates to body cost; saturated contention with cheap work remains
DIRECT; saturated contention with meaningful work becomes STAGED. The 90/95 ns thresholds,
estimator, confirmation, and guard behavior are unchanged. Productive availability retains its
existing independent root. No new hysteresis is authorized unless runtime evidence shows stable
input causing threshold-adjacent oscillation.

The `650_000` boundary is a machine-calibration candidate, not portable policy. It splits the
retained calibration-host gap between the largest observed low/middle DIRECT region (`582_000` for
abundant expensive DIRECT) and the first clearly high region (`705_000` for scarce DIRECT). Regions
without valid contention preserve the current selector.

## Comparison control and diagnostics

Add a startup-fixed selector-integration property, default enabled, solely so JMH can compare the
old and new policy in the same distribution while leaving Phase 15 signal recording active. A
static-final read allows the disabled fork to execute the current tree. Report whether integration
is enabled in benchmark-only policy snapshots; do not add counters or tracing to the hot path.

Add one normal source-to-core JMH state reusing the Phase 13 topology/source fixture with production
body sampling and normal selection. Its retained snapshot validation requires exact source,
productive, registered, rank, polling, contention-validity, and non-missing-worker state, but does
not prescribe every worker mode before measurement. Existing forced source-to-core rows remain the
no-op tax and selector-diagnostic controls.

Add one bounded dynamic diagnostic using a long-lived production graph whose repeating source count
changes abundant -> scarce -> abundant by completing one source and later publishing a replacement.
Use meaningful body work so contention and selection can both respond. Wait by completed-work and
deadline bounds, not arbitrary sleeps. Record phase snapshots only outside the production hot path.

## Deterministic tests

- Test exact contention threshold edges, contention bootstrap fallback, selector-disable fallback,
  cheap saturated DIRECT, expensive saturated STAGED, low-contention expensive DIRECT, productive
  abundance, body-history startup, and the unchanged 90/95 ns guard.
- Test that `completeBatch` consumes contention only at its boundary and that reset still restores
  DIRECT/body bootstrap while queue reset clears contention validity.
- Extend benchmark helper tests for the normal crossover fixture and diagnostic fields without
  production callbacks.
- Preserve all existing idle, body estimator, acquisition accounting, and forced-mode tests.

## Performance evaluation

Build one benchmark distribution. Compare selector integration disabled/enabled with signal
recording enabled. Retain three-fork evidence where practical:

- no-op controls: abundant, moderate scarcity, and severe scarcity using the existing forced
  natural-handle source semantics;
- controlled useful work: bounded source rows representing abundant, moderate, high, and extreme
  contention at rounds near cheap-nontrivial, transition, moderate, and expensive regions, pruning
  rows after short exploration when they duplicate a resolved region;
- actual workloads: maintained Euhedral Mandelbrot plus the most relevant existing execution
  pipeline benchmarks that perform meaningful work.

For important production rows retain contention, body estimate, selected mode, productive handles,
registered and polling workers, sources, throughput, and individual fork means. Treat sub-1%
differences as noise unless unusually stable, inspect fork direction and uncertainty, and do not
average away bimodality. Signal snapshot/report work occurs only at iteration/trial boundaries; run
actual workloads without additional diagnostics.

## Acceptance and completion

Run:

```text
mise exec -- gradle :euhedral-core:test --no-daemon
mise exec -- gradle :benchmarks:test --no-daemon
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck --no-daemon
mise exec -- gradle build --no-daemon
git diff --check
```

Append the requested 21-part completion record, raw evidence paths, limitations, and exactly one of
the five Phase 16 outcome labels. If correctness or a stable-workload discontinuity appears, apply
the bug-first checks before changing the tree.

## Completion record

1. **Exact production smoother implementation.** `AcquisitionContentionSmoother` remains one
   worker-owned `long value` plus one `boolean initialized`. The first valid sample bootstraps
   `value` directly. Later samples execute `value += (sample - value) / 16`. The implementation has
   no clock, rate, interval, extrema, variance, allocation, atomic, or shared aggregation.

2. **Acquisition accounting boundary.** `UpstreamQueue.pull` keeps two local `long` counters.
   `attempts` increments once immediately before `UpstreamHandle.acquireLock()` after a non-null,
   non-complete handle is selected. `failedAcquires` increments only when that invocation returns
   false. One ratio is recorded after the loop only when `attempts > 0`; absence of an attempt does
   not update the smoother.

3. **Fixed-point scale.** The scheduling representation remains a `long` with
   `ACQUIRE_CONTENTION_SCALE = 1_000_000`. The ordinary path computes
   `failedAcquires * 1_000_000 / attempts`; the retained overflow-safe equivalent handles very
   large counters. Floating-point normalization exists only in benchmark diagnostics.

4. **Smoother constant.** `AcquisitionContentionSmoother.DIVISOR` remains `16`, the accepted
   division-based EWMA candidate from Phase 15. No shift/division retuning was performed.

5. **Bootstrap and reset.** `getAcquireContentionOrUninitialized()` returns `-1` until a real
   acquisition cycle initializes the existing boolean validity state. Selection then preserves the
   legacy policy. The first observation is not treated as zero. Owner-thread reset clears both the
   smoother value and validity through the existing `CycleState.reset` handoff. Body bootstrap and
   reset mechanics are unchanged.

6. **Exact decision-tree integration.** At a completed productive fragment batch,
   `ControlPlaneFragment` reads the queue scalar once and passes it to
   `FragmentControlPolicy.completeBatch`. Normal selection is:

   ```text
   invalid registered-worker state or productiveHandles >= registeredWorkers -> DIRECT
   insufficient body history                                               -> DIRECT
   selector comparison disabled or contention invalid                      -> legacy body tree
   body cost <= 90 ns                                                       -> DIRECT
   contention <= 650_000                                                    -> DIRECT
   body cost >= 95 ns                                                       -> STAGED
   otherwise                                                                -> settled mode
   ```

   The signal is not read per frame and does not participate in polling quota, park duration,
   worker rank, or idle eligibility.

7. **Contention threshold.** The only new policy threshold is
   `LOW_ACQUIRE_CONTENTION_MAX = 650_000`. It is isolated and explicitly documented as a
   calibration-host candidate, not a portable constant.

8. **Body-cost thresholds.** The retained thresholds are `90 ns` for cheap/DIRECT and `95 ns` for
   expensive/STAGED, with the existing settled-mode guard between them. The extremely-cheap
   `20 ns` idle threshold remains independent and unchanged. Sampling cadence, robust estimator,
   confirmation windows, and observation mechanics were not changed.

9. **Threshold support.** Phase 14 left a host-observed gap between the largest retained
   low/middle contention DIRECT point (`582_000`) and the first clearly high contention point
   (`705_000`). `650_000` conservatively splits that gap. The unchanged `90/95 ns` body thresholds
   preserve the Phase 13 validated body reversal and cheap-work return instead of encoding
   `high contention -> STAGED`.

10. **Representative selector decisions.** The production-policy fixture used 23 registered
    workers. All listed useful-work rows kept 23 active polling workers, and productive handles
    equaled source count.

    | Sources | Work rounds | Active contention min/median/max | Body min/median/max ns | Final selection | Enabled throughput |
    |---:|---:|---:|---:|:---|---:|
    | 32 | 96 | 65k-384k / 203k-249k medians | 101/173/177 | 23 DIRECT | 99.502 Mframes/s |
    | 4 | 96 | 969k-1,000k / 1,000k medians | 101/173/175 | 23 STAGED | 43.275 Mframes/s |
    | 1 | 24 | 911k-1,000k / 977k-999k medians | 35/56/61 | 23 DIRECT | 13.113 Mframes/s |
    | 16 | 256 | 224k-652k / 367k-407k medians | 250/433/434 | 22-23 DIRECT | 45.387 Mframes/s |
    | 8 | 256 | 664k-899k / 788k-802k medians | 250/433/434 | 22-23 STAGED | 38.346 Mframes/s |
    | 1 | 256 | 999k-1,000k / 1,000k medians | 250/434/451 | 23 STAGED | 11.120 Mframes/s |

    This reproduces the structural expectations: abundant meaningful work extends DIRECT, scarce
    meaningful work selects STAGED, saturated cheap work remains DIRECT, and saturated expensive
    work selects STAGED.

11. **No-op benchmark results.** Same-build normal-policy controls used three forks, two 1-second
    warmups, and three 2-second measurements per fork. Higher is better.

    | Sources | Integrated fork means | Legacy fork means | Pooled change | Interpretation |
    |---:|:---|:---|---:|:---|
    | 32 | 156.352 / 251.076 / 243.572 | 249.113 / 170.203 / 154.597 | +13.43% | Discrete in both configurations; pooled result rejected |
    | 4 | 85.450 / 75.587 / 74.714 | 76.245 / 75.378 / 75.580 | +3.76% | First integrated fork high; remaining forks effectively neutral |
    | 1 | 32.224 / 32.271 / 32.770 | 32.381 / 32.836 / 32.559 | -0.52% | Stable and within normal noise |

    Values are Mframes/s. Normal no-op scheduling deliberately parks excess ranks: 32 sources kept
    23 active, 4 sources settled at 4-5 active, and 1 source settled at 1 active. Consequently these
    rows evaluate the closed scheduler, not sustained all-worker contention. The Phase 15 maximum
    hot-path-tax evidence is retained without reinterpretation: severe enabled
    `12.931 / 13.309 / 14.567` versus disabled `13.293 / 13.318 / 14.059 Mframes/s`, median change
    about `-0.064%`, lowest-fork ratio `97.27%`. That conservative strict-gate miss remains the
    documented research exception.

12. **Controlled useful-work results.** Same-build settings and three-fork protocol matched the
    no-op controls. Higher is better.

    | Region | Sources / rounds | Integrated fork means | Legacy fork means | Pooled change |
    |:---|:---|:---|:---|---:|
    | Cheap extreme | 1 / 24 | 12.819 / 12.918 / 13.602 | 13.407 / 13.094 / 12.385 | +1.16% |
    | Transition abundant | 32 / 96 | 100.884 / 101.113 / 96.510 | 94.487 / 109.159 / 97.261 | -0.80% |
    | Transition moderate | 4 / 96 | 43.550 / 42.865 / 43.410 | 43.202 / 42.394 / 42.878 | +1.05% |
    | Transition extreme | 1 / 96 | 10.694 / 10.616 / 10.294 | 10.607 / 9.774 / 11.014 | +0.67% |
    | Expensive low | 16 / 256 | 44.351 / 45.721 / 46.089 | 44.181 / 43.661 / 43.702 | **+3.51%** |
    | Expensive moderate | 8 / 256 | 38.193 / 38.397 / 38.449 | 38.375 / 38.461 / 38.426 | -0.19% |
    | Expensive extreme | 1 / 256 | 10.215 / 12.573 / 10.571 | 11.167 / 10.502 / 10.496 | +3.71% discrete |

    Values are Mframes/s. The policy-changing expensive/low-contention row improved consistently
    enough to be material. Expensive/moderate was neutral. Cheap and transition differences were
    within their fork spread. The expensive/extreme aggregate was not credited because one
    integrated fork entered a higher discrete regime.

13. **Representative actual workloads.** Existing maintained Euhedral Mandelbrot benchmarks ran
    with `degree=2`, three independent forks, and one full 8K render per fork. Each invocation took
    roughly 50 seconds, so no extra warmup render was added. Lower normalized time is better.

    | Workload | Integrated fork means | Legacy fork means | Pooled time change |
    |:---|:---|:---|---:|
    | Batched Mandelbrot (1,024 pixels/frame) | 388.630 / 387.576 / 390.288 ns/op | 390.372 / 383.603 / 390.914 ns/op | +0.14% |
    | Mandelbrot (per-pixel frames) | 352.745 / 348.292 / 362.883 ns/op | 346.571 / 364.670 / 366.724 ns/op | -1.30% |

    Batched execution was neutral. Per-frame execution was neutral-to-positive with a 1.30% lower
    pooled time, but overlapping fork ranges prevent classifying it as an independently strong
    actual-workload win. Neither workload regressed materially.

14. **Fork-level stability.** No useful or actual fork timed out, lost registration, or failed
    source completion. Expensive/low and expensive/moderate were stable. Abundant no-op was strongly
    discrete in both configurations; expensive/extreme had one high integrated fork. Those pooled
    deltas were not used as acceptance claims.

15. **Path oscillation.** Fully resolved regions stayed fixed at all retained iteration snapshots:
    cheap/extreme was `23 DIRECT`, transition/extreme and expensive/extreme were `23 STAGED`, and
    transition/abundant was `23 DIRECT`. At the deliberately adjacent 16-source expensive boundary,
    a few lanes crossed `650_000`: one fork briefly showed 8 STAGED lanes and ended 22 DIRECT / 1
    STAGED, while the others settled at 23 DIRECT. This was worker-local threshold response, not
    rapid global branch thrashing, and the row improved 3.51%; no hysteresis was added.

16. **Discrete and bimodal regimes.** The 32-source no-op control remained host-discrete regardless
    of selector configuration. One expensive/extreme integrated fork reached 12.573 Mframes/s while
    the other two were 10.215 and 10.571. Both contradictions were retained at fork level and not
    averaged into a policy claim.

17. **Comparison against current production policy.** A startup-fixed system property defaulting
    to enabled controls only contention participation in selection. Disabled forks still record
    the same production signal and execute the unchanged legacy body tree from the same benchmark
    distribution. The only clearly changed useful region was expensive/low contention: the legacy
    selector staged it, while the new selector predominantly selected DIRECT and gained 3.51%.
    Other important regions were neutral or selected the same path.

18. **Diagnostic measurement effect.** Normal crossover rows captured existing benchmark-only
    handle matrices and policy snapshots at iteration/trial boundaries for explanation. They add no
    production per-cycle snapshot, trace, or floating-point normalization, and both comparison
    configurations used identical diagnostics. Mandelbrot acceptance runs used the ordinary
    production graph with no calibration diagnostic lease, so actual-workload timing did not include
    those diagnostics.

19. **Future high-contention-idle evidence.** With one meaningful-work source, all 23 workers stayed
    active, contention remained near `999_985`, and STAGED throughput was about 10.5-11.1 Mframes/s.
    STAGED therefore did not remove acquisition failure pressure. In contrast, the existing
    extremely-cheap idle branch reduced one-source no-op execution to one active worker. This is
    evidence for a later contention-idle experiment, not justification for changing polling here.

20. **Raw evidence locations.** Phase 16 JSON and logs are retained under
    `/tmp/euhedral-phase16-20260813.T774g7`: `noop-{enabled-rerun,disabled}`, every
    `useful-{enabled,disabled}-*` pair, `batched-mandelbrot-{enabled,disabled}`,
    `mandelbrot-{enabled,disabled}`, and `dynamic-smoke-2`. The interrupted IDE-crash artifacts
    `noop-enabled.json` and `dynamic-smoke.json` are zero length and explicitly excluded. Phase 15
    tax evidence remains under `/tmp/euhedral-phase15-20260813.k1ubaR`.

21. **Final retain/remove decision.** Retain the production signal and selector integration. The
    policy-changing useful-work row gained a material 3.51%, actual workloads were neutral to
    positive, stable important rows showed no unacceptable regression, and the stable severe no-op
    delta was only -0.52% in the closed scheduler. The dynamic two-worker sanity test followed
    abundant -> scarce -> abundant: contention moved from `241_381/156_422` DIRECT, to
    `872_126/567_346` with one STAGED worker, then to `611_871/317_391` with both DIRECT. This
    confirms bounded adaptation without requiring instantaneous worker agreement.

## Verification

All required commands passed with the repository's pinned Java 21 and Gradle 9.6.1 toolchain:

```text
mise exec -- gradle :euhedral-core:test --no-daemon
mise exec -- gradle :benchmarks:test --no-daemon
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck --no-daemon
mise exec -- gradle build --no-daemon
git diff --check
```

### Outcome 1: retain - meaningful workload improvement

The acquisition-contention branch materially improves the resolved expensive/low-contention useful
workload without an unacceptable regression in representative actual, useful, or no-op workloads.
Keep the production signal and branch. The bounded no-op evidence remains documented separately.
