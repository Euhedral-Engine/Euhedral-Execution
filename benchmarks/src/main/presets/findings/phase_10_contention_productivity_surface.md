# Phase 10 Findings: Contention and Productivity Surface

This document records Phase 10 of the
[Euhedral Tuning Process](../../EUHEDRAL_TUNING_PROCESS.md#13-phase-10---map-the-contention-x-productivity-surface).

## Experimental scope

The initial bounded surface is defined by
[`23-contention-productivity-surface.json`](../experiments/23-contention-productivity-surface.json).
It compares `STAGED` with `SKIP_THEN_STAGED` at all five body landmarks on:

- four P-core workers with 4, 3, 2, and 1 productive parallel handles;
- eight E-core workers with 4, 2, and 1 productive parallel handles.

The resulting productive-handle ratios were exactly 1.0, 0.75, 0.50, 0.25, and 0.125 at
system-fork batch-complete steady state. Source count was only the fixture control. The analysis uses
the measured contention and `productiveHandleRatio` values.

Both policies retained the Phase 8 low-contention `DIRECT` bootstrap, calibrated follower parking,
and the candidate contention thresholds `[650000, 800000, 900000, 970000]`. The surface used one
fork, one warmup, and three measurement iterations to locate candidate reversals. Direct evidence
was reused from earlier phases; `DIRECT` and `SKIP_THEN_DIRECT` were not rerun.

The targeted replication preset is
[`24-contention-productivity-multifork-verification.json`](../experiments/24-contention-productivity-multifork-verification.json).
It uses three independent JVM forks, two warmups, and five measurement iterations per fork for the
high-contention crossover candidates. The authoritative comparison presets are:

- [`24-contention-productivity-multifork-pcore.json`](../comparisons/24-contention-productivity-multifork-pcore.json)
- [`24-contention-productivity-multifork-ecore.json`](../comparisons/24-contention-productivity-multifork-ecore.json)

The completed local artifacts are under `experiments/23-contention-productivity-surface` and
`experiments/24-contention-productivity-multifork-verification`.

## Initial surface

The delta is relative to `STAGED`; positive means `SKIP_THEN_STAGED` had higher throughput. The
contention column is `STAGED / SKIP_THEN_STAGED`. These one-fork results select replication targets;
they are not independent-fork winner claims.

### P-core surface

| Productive ratio | Body | Measured contention | Throughput delta |
|-----------------:|------|---------------------|-----------------:|
| 0.250 | XS (0) | 89.2% / 86.1% | -15.64% |
| 0.250 | S (48) | 85.3% / 51.9% | +7.97% |
| 0.250 | M (144) | 86.2% / 83.3% | +12.55% |
| 0.250 | H (216) | 85.2% / 82.8% | -0.30% |
| 0.250 | XH (288) | 86.3% / 40.8% | +0.45% |
| 0.500 | XS (0) | 66.1% / 33.8% | -6.51% |
| 0.500 | S (48) | 64.6% / 49.2% | +0.66% |
| 0.500 | M (144) | 55.5% / 53.5% | -1.87% |
| 0.500 | H (216) | 47.0% / 46.9% | -1.71% |
| 0.500 | XH (288) | 17.2% / 42.9% | +1.45% |
| 0.750 | XS (0) | 16.2% / 44.6% | -0.20% |
| 0.750 | S (48) | 23.1% / 37.3% | +5.03% |
| 0.750 | M (144) | 14.7% / 12.5% | +3.62% |
| 0.750 | H (216) | 9.9% / 12.7% | -0.45% |
| 0.750 | XH (288) | 21.4% / 45.6% | -2.90% |
| 1.000 | XS (0) | 30.2% / 20.9% | +1.24% |
| 1.000 | S (48) | 21.9% / 15.1% | -12.80% |
| 1.000 | M (144) | 16.1% / 38.5% | -10.25% |
| 1.000 | H (216) | 21.5% / 16.8% | -3.81% |
| 1.000 | XH (288) | 25.3% / 6.2% | +21.96% |

Ratios 0.75 and 1.0 remained below the 65% staged-family boundary. Both configurations therefore
executed the `DIRECT` bootstrap in steady state. Their throughput deltas are run variance, not
`STAGED` versus `SKIP_THEN_STAGED` evidence. The 0.50 fixture also crossed below the boundary for
many body/policy combinations. Only the 0.25 fixture consistently occupied the intended P-core
high-contention region.

### E-core surface

| Productive ratio | Body | Measured contention | Throughput delta |
|-----------------:|------|---------------------|-----------------:|
| 0.125 | XS (0) | 96.0% / 96.6% | -11.31% |
| 0.125 | S (48) | 94.9% / 93.6% | +3.01% |
| 0.125 | M (144) | 62.5% / 94.3% | +2.05% |
| 0.125 | H (216) | 95.2% / 94.2% | +0.25% |
| 0.125 | XH (288) | 95.0% / 52.0% | -3.14% |
| 0.250 | XS (0) | 91.5% / 93.6% | -31.52% |
| 0.250 | S (48) | 91.2% / 88.9% | +13.32% |
| 0.250 | M (144) | 92.1% / 90.2% | +3.06% |
| 0.250 | H (216) | 91.5% / 55.1% | -2.68% |
| 0.250 | XH (288) | 91.5% / 89.4% | +1.65% |
| 0.500 | XS (0) | 75.7% / 81.9% | -35.61% |
| 0.500 | S (48) | 82.3% / 72.0% | -6.00% |
| 0.500 | M (144) | 74.9% / 45.7% | -31.33% |
| 0.500 | H (216) | 74.8% / 72.1% | +3.30% |
| 0.500 | XH (288) | 74.2% / 70.3% | -0.23% |

The E-core fixtures provide the useful 0.50, 0.25, and 0.125 high-contention coverage. They also
show that execution policy can move the closed-loop contention state substantially while the
productive ratio remains fixed.

## Independent-fork replication

The comparison pipeline originally exported one aggregate JMH score even for a multi-fork log.
`JmhOutputParser` was corrected to calculate one mean from the measurement iterations in each
explicit JMH fork. The `*-fork-aware` comparison artifacts contain the authoritative results below.
The earlier derived `comparisons-pcore` and `comparisons-ecore` directories are retained but are not
authoritative because they report `baselineForkCount = 1`.

Throughput is in millions of executions per second. CV is the coefficient of variation across the
three independent fork means. Positive delta favors `SKIP_THEN_STAGED`.

### P-core replication

| Productive ratio | Body | STAGED | SKIP_THEN_STAGED | Delta | Fork CVs | Outcome |
|-----------------:|------|-------:|-----------------:|------:|----------|---------|
| 0.250 | XS (0) | 22.246 | 20.319 | -8.66% | 9.6% / 7.7% | inconclusive |
| 0.250 | S (48) | 20.230 | 19.957 | -1.35% | 4.6% / 4.6% | inconclusive |
| 0.250 | M (144) | 14.168 | 16.142 | +13.94% | 26.3% / 12.3% | inconclusive |

The XS and M directions repeated their initial one-fork directions, but their independent-fork
uncertainty exceeded both the throughput delta and the 1% practical margin. The S direction did not
repeat.

### E-core replication

| Productive ratio | Body | STAGED | SKIP_THEN_STAGED | Delta | Fork CVs | Outcome |
|-----------------:|------|-------:|-----------------:|------:|----------|---------|
| 0.125 | XS (0) | 17.127 | 15.247 | -10.98% | 4.9% / 20.4% | inconclusive |
| 0.125 | S (48) | 17.246 | 16.370 | -5.08% | 5.0% / 4.7% | inconclusive |
| 0.125 | XH (288) | 12.270 | 12.015 | -2.07% | 0.6% / 0.5% | `STAGED` |
| 0.250 | XS (0) | 15.078 | 15.570 | +3.26% | 14.8% / 21.5% | inconclusive |
| 0.250 | S (48) | 18.124 | 15.395 | -15.06% | 5.2% / 26.9% | inconclusive |
| 0.250 | H (216) | 13.188 | 12.308 | -6.68% | 10.2% / 21.8% | inconclusive |
| 0.500 | S (48) | 18.421 | 15.606 | -15.28% | 3.5% / 26.2% | inconclusive |
| 0.500 | M (144) | 15.061 | 14.440 | -4.12% | 20.4% / 17.7% | inconclusive |
| 0.500 | H (216) | 14.974 | 14.273 | -4.69% | 0.2% / 5.4% | inconclusive |

Only the very-low-productivity XH-body cell produced a variance-aware replicated winner:
`STAGED` by 2.07%. All other E-core cells remained inconclusive, and four apparent initial
crossovers reversed direction. In particular, the ratio-0.50 M result no longer reproduces Phase
8's `SKIP_THEN_STAGED` advantage.

## Occupancy and transition behavior

All matched comparisons retain exact occupancy and transition exports. The largest execution-state
distribution changes were:

| Topology / ratio / body | STAGED dominant state | SKIP dominant state | Total variation distance | Dominant self-transition |
|-------------------------|----------------------:|--------------------:|-------------------------:|-------------------------:|
| P-core / 0.25 / XS | 20 | 15 | 0.675 | 0.976 / 0.824 |
| P-core / 0.25 / S | 1 | 12 | 0.750 | 0.964 / 0.719 |
| E-core / 0.125 / XS | 1 | 20 | 0.669 | 0.998 / 0.972 |
| E-core / 0.25 / XS | 20 | 16 | 0.818 | 0.986 / 0.813 |
| E-core / 0.50 / M | 22 | 7 | 0.875 | 0.989 / 0.847 |

State is `contentionBand * 5 + bodyBand`. These large distances show that the paired policies often
created different closed-loop contention/body populations. They are not clean observations of two
actions at one fixed physical point.

The one replicated `STAGED` winner, E-core ratio 0.125 and XH body, had much more comparable state
geometry: both policies were dominated by state 24, total variation distance was 0.074, and the
dominant self-transition rates were 0.978 and 0.968. This is the strongest interpretable Phase 10
result.

## Phase 10 conclusion

`productiveHandleRatio` is a real, independent description of opportunity geometry, but it does
not explain the remaining staged-family winner variance by itself. The data reject the proposed
simple shape in which moderate productive deficit reliably selects `SKIP_THEN_STAGED`:

- equal productive ratios produce different results on P-cores and clustered E-cores;
- body band changes both throughput direction and closed-loop contention state;
- apparent single-fork crossovers usually reverse or become inconclusive across independent forks;
- the only replicated winner is `STAGED` at E-core ratio 0.125 with XH body.

The bounded surface covers all five requested productivity levels and all body bands initially,
with P-core and E-core evidence kept separate. No repeated `STAGED` / `SKIP_THEN_STAGED` crossover
survived the replication gate, so Phase 10 establishes a negative result rather than a new policy
boundary. Do not add a productivity threshold, composite pressure formula, or third decision-tree
axis from this evidence. A future refinement would need an isolated host and fixtures that hold the
policy-conditioned contention population more stable before Phase 11 considers compression.
