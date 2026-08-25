# Phase 2 Findings: Productivity Body Threshold Calibration

## Decision

Phase 2 supports a conservative worker-calibrated productivity threshold weight of `40` as the
scalar production fallback. It does not support a raw nanosecond constant or a claim that one
body-only crossover is portable across every productive-handle deficit.

The topology surface is Outcome C: the forced ON/OFF crossover moves materially with productive
handle ratio. The existing data also contains an upward turn at the heavy end, so a ratio-conditioned
U-shaped threshold-band curve is a plausible representation. That curve is not fitted or tuned in
this phase. Calibration stops at `11 / 23 = 47.83%` productive handles, below the requested 50%
limit, and no additional benchmark is proposed.

All evidence in this report comes from the corrected, contention-independent productivity gate.
Earlier contention-qualified evidence is excluded.

## Runtime semantics

The gate is evaluated only when the worker-local cache is empty. In automatic mode, worker `w` is
excluded from upstream participation when:

```text
thresholdNs(w) > 0
and bodyHistoryCount(w) >= 32
and upstreamHandles > 0
and registeredWorkers > 1
and workerRank(w) > 0
and workerRank(w) > productiveHandles
and smoothedBodyCostNs(w) <= thresholdNs(w)
```

There is no contention term in this equation. The measured body signal is the worker-local sparse
executor-body estimate `smoothedBodyCostNs`, updated from body observations by the existing decision
tree. The comparison is inclusive (`<=`).

The configured production coordinate is a non-negative micro-calibrator work weight, not
nanoseconds. Each pinned worker warms and runs its own `MicroCalibrator`, converting the weight into
its local `thresholdNs(w)`. Weight zero resolves to zero and disables the gate. Benchmark-only
`FORCE_OFF` resolves to zero; benchmark-only `FORCE_ON` resolves to `Long.MAX_VALUE`, which enables
the gate over the tested body surface once the other participation predicates hold.

Exclusion is effectively indefinite while the predicate remains true: the worker repeatedly takes
the existing bounded 15,000 ns owner-loop park and re-evaluates the predicate. No productivity park
duration was introduced or tuned.

## Fixed controls

- Ordinary idle defaults remain `50,000 / 0 / 0 / 0 / 0 ns` for XS/S/M/H/XH.
- Body-band weights remain `96 / 128 / 216 / 288`.
- The JMH harness thread was explicitly pinned to CPU 0 in every retained fork. Because physical
  core 0 was therefore the harness core, the configured worker CPU set `2..31` produced 23 workers.
  A harness thread placed on any of those worker cores would instead leave 22 workers and would be
  a different topology.
- Worker ranking, productive-handle count, participation-count rule, source assignment, caches,
  pull bucketing, and DIRECT/STAGED thresholds are unchanged.
- All authoritative measurements use `CONTINUOUS` lifecycle and independent JVMs as replicas.
- Primary topology is 23 workers and 11 continuously fed parallel sources.

## Corrected 23/11 physical surface

The broad coarse sweep used work fixtures `0, 112, 172, 252, 384, 768, 3,072, 12,288, 98,304`.
It established a very large true-no-op win and a loss by workUnits 112, so the local search used
`16, 32, 48, 64, 80, 96`. Work units are fixture identifiers only; the threshold decision is based
on the measured per-worker body coordinate.

| workUnits | Representative smoothed body coordinate | OFF mean ops/s | ON mean ops/s | ON - OFF | ON - OFF % | Across-fork CV OFF / ON |
|---:|:---|---:|---:|---:|---:|:---|
| 16 | about 39-52 ns | 133,800,131 | 275,353,514 | +141,553,382 | +105.79% | 4.69% / 7.61% |
| 32 | about 47-76 ns | 132,858,484 | 172,351,333 | +39,492,849 | +29.73% | 0.50% / 7.98% |
| 48 | about 62-103 ns | 124,683,352 | 139,537,457 | +14,854,105 | +11.91% | 3.54% / 6.95% |
| 64 | about 78-130 ns | 115,466,347 | 115,376,997 | -89,350 | -0.08% | 1.00% / 2.15% |
| 80 | about 91-167 ns | 107,203,442 | 93,155,825 | -14,047,617 | -13.10% | 1.13% / 1.40% |
| 96 | heavy side of the 64-80 bracket | 98,099,258 | 80,230,318 | -17,868,940 | -18.22% | 5.04% / 3.46% |

The physical 23/11 crossover is a neutral region centered on workUnits 64. Depending on which
treatment's worker-local estimate is used, that fixture occupies roughly the 104-127 ns smoothed
body region. This interval, not an integer workUnits value, is the measured anchor coordinate.
Normal worker and treatment variability is already wider than any useful finer cutoff.

The complete per-fork body distributions, body-band occupancy, acquisitions, contention centroid,
productive-handle ratio, exclusions, ordinary idle fraction, DIRECT/STAGED occupancy, and ordered
six-window trajectories are retained in the
[coarse handoff](../../../../../experiments/12-phase-2-productivity-body-coarse/HANDOFF.md) and
[refinement handoff](../../../../../experiments/13-phase-2-productivity-body-refinement/HANDOFF.md).

## Explanatory telemetry

At 23/11, forced exclusion increases acquisition success share substantially at the cheap fixtures
while lowering contention centroid, but those metrics are not themselves the objective. The
one-source data makes the distinction decisive: FORCE_ON reports much higher acquisition success
share while throughput falls by 47-61%. Fewer contenders can make acquisition metrics look better
while physically serializing useful work.

Execution occupancy also changes with the treatment. In the portability runs, FORCE_ON DIRECT
occupancy tracks the productive-handle fraction (26.1%, 8.7%, and 4.3% for 6, 2, and 1 sources),
whereas the excess workers are excluded. This interaction is retained as explanation only;
DIRECT/STAGED policy was not recalibrated.

## Threshold encoding

Weight 48 retained too much exclusion above the physical crossover: compared with the retained
forced winner it was -2.54% at workUnits 64 and -6.41% at workUnits 80. Weight 40 is the more
conservative scalar:

| workUnits | Forced winner | AUTO-40 exclusion | AUTO-40 vs winner |
|---:|:---:|---:|---:|
| 32 | ON | 52.2% | +10.08% |
| 48 | ON | 26.8% | -7.61% |
| 64 | OFF | 20.7% | -5.06% |
| 80 | OFF | 6.5% | -0.36% |

This response is not a monotone optimization curve: the scalar candidate wins at the cheap anchor,
falls below the forced winner in the middle, and returns to essentially neutral at the heavy anchor
as exclusion fades. That visible return is part of the U-shaped threshold-band evidence and is not
smoothed away.

Weight 40 resolves to different nanosecond thresholds on different workers and JVMs, as intended.
The runtime therefore stores `40` as the default productivity threshold weight and calibrates the
corresponding nanoseconds independently on each pinned worker. It does not store one observed
nanosecond cutoff. Full per-worker threshold/body pairs and ordered trajectories are retained in the
[AUTO-48 handoff](../../../../../experiments/14-phase-2-productivity-body-automatic-validation/HANDOFF.md)
and [AUTO-40 handoff](../../../../../experiments/15-phase-2-productivity-body-automatic-weight-40/HANDOFF.md).

## Source-deficit portability

The corrected forced surface covers productive-handle ratios from 4.35% through 47.83%; nothing
above 50% is tuned or extrapolated.

| Topology | Productive-handle ratio | workUnits 32 | workUnits 48 | workUnits 64 | workUnits 80 | Crossover observation |
|:---:|---:|---:|---:|---:|---:|:---|
| 23/11 | 47.83% | +29.73% | +11.91% | -0.08% | -13.10% | neutral at 64 |
| 23/6 | 26.09% | +61.21% | +32.88% | -5.60% | -11.85% | between 48 and 64 |
| 23/2 | 8.70% | +41.22% | -2.05% | -30.75% | -33.93% | between 32 and 48 |
| 23/1 | 4.35% | -47.35% | -56.69% | -61.40% | -55.95% | below 32; upward turn at 80 |

The crossover moves materially as the source deficit deepens. At 23/1 the forced response also
turns upward from -61.40% at workUnits 64 to -55.95% at workUnits 80. It remains strongly negative,
so this is evidence of curvature, not evidence that a second positive region has been located.

The critical 23/1 AUTO-40 check avoids the forced-ON collapse:

| workUnits | AUTO-40 independent JVM result versus FORCE_OFF | AUTO-40 exclusion |
|---:|:---|:---|
| 48 | +9.29%, -0.25% | 47.8%, 39.1% |
| 64 | +5.06%, -1.76% | 17.4%, 4.3% |

Thus weight 40 is a defensible low-downside scalar fallback on the tested one-source failure mode,
but it is not evidence that body cost alone explains the full surface. The full 52-fork telemetry
and trajectories are retained in the
[portability handoff](../../../../../experiments/16-phase-2-productivity-body-source-deficit-portability/HANDOFF.md).

## Integrity and confidence

For the final portability step, 48 forced JVMs and 4 AUTO JVMs produced 312/312 continuously fed
measurement windows. Independent verification matched all 2,367/2,367 SHA-256 sidecars, with no
missing target. The retained configurations and VM command lines all specify harness CPU 0, and
all 14,696,448 contention-staleness samples from experiments 16/17 report
`registeredWorkers = 23`. Earlier stages report 36, 48, 16, and 16 completed JVMs respectively,
with all ordered trajectories retained and all reported sidecars matching.

Confidence is:

- High that hard exclusion is beneficial for cheap bodies at 23/11 and 23/6.
- High that hard exclusion is unsafe for the sampled body surface at 23/1.
- High that the physical crossover moves with productive-handle ratio.
- Moderate that weight 40 is a safe scalar production fallback through the tested ratio range.
- Low that one body-only threshold is globally optimal or that the upper arm of a U-shaped curve is
  numerically calibrated.

## Classification and production change

Primary classification: Outcome C, with preserved non-monotonic curvature at the heavy end.

The exact production change is:

```text
default productivity threshold representation: worker-local calibrator weight
default productivity threshold weight:         40
raw nanosecond production constant:             none
contention qualifier:                           none
ordinary idle defaults:                         unchanged
productivity park duration:                     unchanged
participation-count rule:                       unchanged
```

No U-shaped band parameters are added in Phase 2. The minimum justified Phase 3 question is whether
the already observed response can be represented by a bounded productive-handle-ratio-conditioned
threshold band over `0 < productiveHandleRatio <= 0.5`. Any future work should begin from these
retained artifacts; this report does not request another benchmark.
