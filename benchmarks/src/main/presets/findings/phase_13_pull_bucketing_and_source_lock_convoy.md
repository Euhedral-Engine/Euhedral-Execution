# Phase 13 Findings: Pull Bucketing and Source-Lock Convoy Formation

## Evidence and controls

Phase 13 retained the Phase 12 multistable fixture: CPUs 16-23, eight E-core workers, four parallel
sources, no ordered sources, `productiveHandleRatio = 0.500`, 144 deterministic work units, an
8,000,000-execution target, a 30-second invocation timeout, the 4096-frame batch cap, the
`staged-high-contention` decision weights and idle policy, and the `STAGED` execution path. Each JVM
ran two 2-second warmups followed by eight 3-second measurement iterations. Only the pull-bucketing
treatment changed between measurements; pull-convoy observation replaced the Phase 12
contention-staleness diagnostic. A field-by-field comparison of the completed trial configs, after
removing identity, diagnostic, fork, and treatment-order fields, found no fixture difference.

Eight independent one-fork trials completed all 64 prescribed measurement iterations. All 448
fork-artifact digests and all nine comparison-artifact digests match their files (457/457 total).
Every benchmark log is nonempty and reports Java 21.0.2, two warmups, eight measurements, and no
benchmark error. Every treatment manifest agrees with its completed trial config and convoy rows.
The 8x8 comparison matrix is complete. Its eight self-comparisons are `COMPATIBLE`; the 56
cross-fork comparisons are `PARTIAL` only because fork identity and the deliberately different
treatment sequences are harness differences.

The fixed treatment labels were:

| Label | Treatment                            |
|-------|--------------------------------------|
| A     | `FLOOR / 512`                        |
| B     | `CEIL / 512`                         |
| C     | `FLOOR / 1024`                       |
| D     | `CEIL / 1024`                        |
| E     | `FLOOR / 2048` (production baseline) |
| F     | `CEIL / 2048`                        |
| G     | `FLOOR / 4096`                       |
| H     | `CEIL / 4096`                        |

The executed Williams order was:

| Fork | Order           |
|-----:|-----------------|
|    1 | A B H C G D F E |
|    2 | B C A D H E G F |
|    3 | C D B E A F H G |
|    4 | D E C F B G A H |
|    5 | E F D G C H B A |
|    6 | F G E H D A C B |
|    7 | G H F A E B D C |
|    8 | H A G B F C E D |

Each treatment therefore occurred once in every fork and once at every measurement position. No
warmup row is included below.

## Pull, production, and hold-time distributions

These are the bounded successful-acquisition samples from the configured `steadyState` segment,
pooled only to describe the physical distributions. Counts differ because the recorder retains a
bounded per-core tail and successful acquisitions differ by regime; forks, not rows, remain the
independent replication unit. Demand and frame counts are in frames.

| Treatment  | Samples | Requested demand mean / p50 / p95 / max | Calculated pull mean / p50 / p95 / max | Produced mean / p50 / p95 / max |
|------------|--------:|----------------------------------------:|---------------------------------------:|--------------------------------:|
| FLOOR 512  |  50,053 |          3,368 / 2,560 / 9,391 / 34,648 |              906 / 677 / 2,348 / 8,662 |       906 / 677 / 2,348 / 8,662 |
| CEIL 512   |  50,626 |          3,209 / 2,560 / 9,044 / 25,539 |              843 / 640 / 2,261 / 6,385 |       843 / 640 / 2,261 / 6,385 |
| FLOOR 1024 |  41,958 |         3,877 / 3,072 / 10,631 / 31,120 |          1,304 / 1,195 / 2,658 / 7,780 |   1,304 / 1,195 / 2,658 / 7,780 |
| CEIL 1024  |  40,779 |          3,550 / 3,072 / 9,616 / 27,200 |            1,080 / 926 / 2,404 / 6,800 |     1,080 / 926 / 2,404 / 6,800 |
| FLOOR 2048 |  35,378 |         3,836 / 2,560 / 11,648 / 44,850 |         1,925 / 2,134 / 3,584 / 11,213 |  1,925 / 2,134 / 3,584 / 11,213 |
| CEIL 2048  |  34,800 |         3,851 / 3,072 / 10,831 / 32,575 |          1,520 / 1,536 / 2,708 / 8,144 |   1,520 / 1,536 / 2,708 / 8,144 |
| FLOOR 4096 |  31,660 |         3,115 / 2,048 / 11,565 / 47,508 |         2,313 / 2,048 / 5,783 / 11,877 |  2,313 / 2,048 / 5,783 / 11,877 |
| CEIL 4096  |  34,377 |         3,571 / 2,304 / 11,712 / 34,381 |          2,035 / 2,124 / 3,809 / 8,596 |   2,035 / 2,124 / 3,809 / 8,596 |

All 319,631 retained successful samples produced exactly the calculated pull count. Thus the
treatment changed synchronous production while the source handle was held, not merely a recorded
intermediate value.

Lock-hold durations are in microseconds:

| Treatment  |  Mean |   p50 |     p95 |     p99 |  Maximum |
|------------|------:|------:|--------:|--------:|---------:|
| FLOOR 512  | 213.1 | 213.8 |   418.0 | 1,596.1 |  3,541.1 |
| CEIL 512   | 199.3 | 175.2 |   408.5 | 1,525.4 |  3,284.8 |
| FLOOR 1024 | 313.6 | 152.1 |   679.0 | 2,768.5 |  6,612.3 |
| CEIL 1024  | 265.4 | 145.9 |   512.0 | 2,385.0 |  3,916.4 |
| FLOOR 2048 | 457.8 | 166.9 | 1,509.5 | 3,407.5 | 10,088.2 |
| CEIL 2048  | 367.0 | 138.2 |   908.8 | 3,276.0 |  7,177.1 |
| FLOOR 4096 | 541.2 | 290.0 | 1,542.7 | 3,718.2 | 10,477.5 |
| CEIL 4096  | 477.4 | 204.3 | 1,509.2 | 3,374.5 | 10,681.2 |

The manipulation and first physical link are strong. Mean pull increases monotonically with target
within both modes. Relative to FLOOR at the same target, CEIL reduced mean pull by 6.9%, 17.2%,
21.0%, and 12.0% at 512, 1024, 2048, and 4096; mean hold time fell by 6.5%, 15.4%, 19.8%, and 11.8%.
Across the eight treatment means, pull and hold time have Pearson `r = 0.9993`. Across the 64
fork-treatment units, host and attractor effects weaken but do not reverse the relationship
(`r = 0.608`, Spearman `rho = 0.792`). Relationships 1 and 2 are supported.

## Acquisition and ownership geometry

Success shares below are means and sample standard deviations across the eight independent forks.
The pooled exact share is also shown because a state-22 iteration makes many more failed attempts
and must not silently be given the weight of multiple independent forks. Turnover is the fraction of
adjacent successful owners that differ, computed separately per handle in the common complete time
window of the bounded per-core steady tails. HHI is calculated from exact acquisition aggregates
across the eight cores for each handle; 0.125 is uniform ownership.

| Treatment  | Fork-balanced success share | Pooled exact share | Owner turnover | Mean same-owner run | Acquisition HHI |
|------------|----------------------------:|-------------------:|---------------:|--------------------:|----------------:|
| FLOOR 512  |            18.49% +/- 0.34% |             18.50% |          39.4% |                2.54 |          0.1317 |
| CEIL 512   |            18.86% +/- 0.40% |             18.87% |          38.7% |                2.58 |          0.1316 |
| FLOOR 1024 |            15.64% +/- 0.28% |             15.65% |          45.3% |                2.21 |          0.1322 |
| CEIL 1024  |            15.01% +/- 5.83% |              4.60% |          41.0% |                2.43 |          0.1326 |
| FLOOR 2048 |            13.05% +/- 0.31% |             13.07% |          61.5% |                1.63 |          0.1313 |
| CEIL 2048  |            12.89% +/- 5.08% |              3.39% |          50.5% |                1.97 |          0.1326 |
| FLOOR 4096 |            11.57% +/- 0.38% |             11.57% |          71.9% |                1.39 |          0.1303 |
| CEIL 4096  |            12.39% +/- 0.48% |             12.42% |          64.5% |                1.55 |          0.1307 |

Shorter holds generally coincide with higher acquisition success: across the 64 units, hold mean
versus success share has Spearman `rho = -0.845`. The ownership part of the proposed mechanism moves
the wrong way, however. The 512 treatments have the shortest holds but only 39% turnover and runs of
about 2.6 consecutive acquisitions by the same core. FLOOR 4096 has the longest holds but 72%
turnover and 1.39-acquisition runs. A short holder appears more likely to reacquire the handle; it
does not cause more rotation among cores.

There is no persistent handle or owner monopolizing total hold time. Across treatments, the largest
handle accounts for only 25.1% of exact hold duration on average (the four-handle uniform value is
25%), and its worst iteration share is 25.3%. The largest core accounts for 13.1%-14.3% on average
(the eight-core uniform value is 12.5%). The two state-22 iterations produce the worst core hold
share, 20.8%, but even there no handle dominates. Acquisition HHI likewise remains close to uniform;
the worst observed single-core acquisition share for any handle is 22.6%.

## Scheduler state and throughput

State occurrence means an iteration whose dominant idle-decision state is 22. Occupancy is the exact
full-iteration state-22 count share. Throughput is the authoritative JMH secondary
`executions` result in millions per second. The standard deviation and CV use the eight forks as the
replication units.

| Treatment  | State-22 dominant | State-22 occupancy mean / median | Dominant states by fork               | Contention / body centroid | Mean dominant self-transition | Idle fraction | Throughput mean +/- SD |    CV | Median |
|------------|------------------:|---------------------------------:|---------------------------------------|---------------------------:|------------------------------:|--------------:|-----------------------:|------:|-------:|
| FLOOR 512  |               0/8 |                      4.3% / 4.4% | 12 / 12 / 12 / 12 / 12 / 12 / 12 / 12 |              1.978 / 2.058 |                         0.844 |         88.1% |       15.916 +/- 1.956 | 12.3% | 16.465 |
| CEIL 512   |               0/8 |                      4.3% / 4.0% | 12 / 12 / 12 / 12 / 12 / 12 / 12 / 12 |              2.033 / 2.055 |                         0.853 |         88.0% |       15.948 +/- 2.042 | 12.8% | 16.458 |
| FLOOR 1024 |               0/8 |                      4.3% / 3.9% | 12 / 12 / 12 / 12 / 12 / 12 / 12 / 12 |              1.844 / 2.053 |                         0.830 |         88.3% |       15.937 +/- 1.943 | 12.2% | 16.428 |
| CEIL 1024  |               1/8 |                     16.4% / 4.5% | 22 / 12 / 12 / 12 / 12 / 12 / 12 / 12 |              2.181 / 2.042 |                         0.858 |         87.7% |       15.289 +/- 2.539 | 16.6% | 16.461 |
| FLOOR 2048 |               0/8 |                      3.3% / 3.0% | 7 / 7 / 7 / 7 / 7 / 7 / 7 / 7         |              1.447 / 2.054 |                         0.844 |         88.4% |       15.939 +/- 1.966 | 12.3% | 16.452 |
| CEIL 2048  |               1/8 |                     15.8% / 4.0% | 22 / 7 / 12 / 12 / 7 / 7 / 12 / 12    |              2.003 / 2.039 |                         0.858 |         88.0% |       15.318 +/- 2.525 | 16.5% | 16.422 |
| FLOOR 4096 |               0/8 |                      3.8% / 3.6% | 7 / 7 / 7 / 7 / 7 / 7 / 7 / 7         |              1.459 / 2.050 |                         0.843 |         88.3% |       15.979 +/- 2.080 | 13.0% | 16.619 |
| CEIL 4096  |               0/8 |                      3.7% / 3.5% | 7 / 7 / 7 / 7 / 7 / 7 / 7 / 7         |              1.477 / 2.052 |                         0.845 |         88.3% |       15.973 +/- 2.059 | 12.9% | 16.516 |

The smaller treatments systematically move the ordinary dominant controller state from 7 to 12,
while the body centroid stays close to 2.05. That is a real contention-band response to treatment,
but it is not elimination of the high-contention attractor. Exact 25x25 head and steady-state
transition matrices are retained for every iteration; the two state-22 iterations have dominant
self-transition rates of 0.999 and 1.000.

State 22 occurred only in fork 1, in adjacent positions 6 and 7 of the one-based sequence. CEIL 1024
reached 99.6% state-22 occupancy, 0.627% acquisition success, a 1,024-frame mean successful pull, a
358.9 us mean hold, and 11.412 million executions/s. CEIL 2048 then reached 99.5%, 0.324%, 2,048
frames, 709.7 us, and 11.428 million executions/s. The following baseline FLOOR 2048 iteration
returned to state 7, 2.3% state-22 occupancy, 13.21% success, and 16.286 million executions/s. Thus
the reset prevented an irrevocably retained controller state, but the adjacency of the only two
state-22 observations leaves a fork/history or owner-phase confound. Each treatment ran in the other
seven forks, and the balanced design placed each at every other sequence position without another
state-22 selection. Neither CEIL treatment therefore has a repeatable attractor effect.

Within-fork throughput is stable except where the attractor changes. Forks 2-8 have treatment CVs of
0.3%-0.9%; fork 1 has 15.1% because its two state-22 iterations are slow. Fork 6 is a separate,
stable slow regime: all eight treatments remain in states 7 or 12 at 11.001-11.252 million
executions/s with 0.9% CV. Across the eight fork means, throughput is 15.787 +/- 2.015 million
executions/s (12.8% CV). As in Phase 12, low throughput is therefore not a sufficient state-22
classifier. No treatment reduced fork variance or improved normal-regime throughput repeatably; the
depressed CEIL 1024 and CEIL 2048 means are consequences of their single fork-1 state-22
observations, not replicated treatment effects.

### Paired throughput comparisons

Because every fork measured every treatment, the direct comparison subtracts the same fork's FLOOR
2048 baseline. Deltas are millions of executions per second. The percentage changes are calculated
within each fork before taking the mean or median; repeated iterations inside a JVM are not treated
as independent forks. The baseline is included explicitly as the reference row.

| Treatment versus FLOOR 2048 | Absolute mean | Absolute median | Mean delta | Median delta | Mean paired change | Median paired change | Fork-delta range |
|-----------------------------|--------------:|----------------:|-----------:|-------------:|-------------------:|---------------------:|-----------------:|
| FLOOR 512                   |        15.916 |          16.465 |     -0.023 |       -0.033 |             -0.13% |               -0.20% | -0.143 to +0.151 |
| CEIL 512                    |        15.948 |          16.458 |     +0.009 |       -0.032 |             -0.01% |               -0.19% | -0.165 to +0.259 |
| FLOOR 1024                  |        15.937 |          16.428 |     -0.002 |       -0.018 |             +0.01% |               -0.10% | -0.192 to +0.334 |
| CEIL 1024                   |        15.289 |          16.461 |     -0.650 |       -0.065 |             -4.04% |               -0.38% | -4.874 to +0.088 |
| **FLOOR 2048 baseline**     |    **15.939** |      **16.452** |  **0.000** |    **0.000** |          **0.00%** |            **0.00%** |        **0.000** |
| CEIL 2048                   |        15.318 |          16.422 |     -0.621 |       -0.027 |             -3.83% |               -0.17% | -4.858 to +0.072 |
| FLOOR 4096                  |        15.979 |          16.619 |     +0.040 |       +0.093 |             +0.16% |               +0.55% | -0.298 to +0.221 |
| CEIL 4096                   |        15.973 |          16.516 |     +0.034 |       +0.009 |             +0.14% |               +0.06% | -0.180 to +0.242 |

The large mean losses for CEIL 1024 and CEIL 2048 are entirely driven by their adjacent state-22
measurements in fork 1. As a sensitivity check rather than a replacement analysis, across forks 2-8
CEIL 1024 is only 0.047 million/s (-0.34%) below the paired baseline on average, and CEIL 2048 is
0.016 million/s (-0.12%) below it. Their all-fork median effects, -0.38% and -0.17%, likewise show
that the mean loss is an attractor-selection event rather than a repeatable normal-regime throughput
penalty.

The paired CEIL-minus-FLOOR comparisons at each target are:

| Target | Mean CEIL - FLOOR | Median CEIL - FLOOR | Mean paired change | Median paired change | Fork-delta range |
|-------:|------------------:|--------------------:|-------------------:|---------------------:|-----------------:|
|    512 |            +0.032 |              +0.019 |             +0.13% |               +0.12% | -0.180 to +0.211 |
|   1024 |            -0.648 |              +0.000 |             -4.04% |               +0.00% | -4.873 to +0.174 |
|   2048 |            -0.621 |              -0.027 |             -3.83% |               -0.17% | -4.858 to +0.072 |
|   4096 |            -0.006 |              -0.023 |             -0.02% |               -0.14% | -0.166 to +0.334 |

Outside the two state-22 observations, the paired differences are small relative to the 11-17
million/s fork regimes and change sign across targets. FLOOR versus CEIL therefore has no repeatable
normal-regime throughput winner in this coarse experiment.

## Causal result and outcome

The complete proposed chain is not supported:

1. Bucket mode and target materially changed actual pull and production count: supported.
2. Smaller pulls shortened source-lock holds: supported strongly.
3. Shorter holds usually raised acquisition success, but did not improve ownership turnover;
   turnover changed in the opposite direction.
4. Higher ordinary-regime success did not demonstrably reduce state-22 selection. The baseline had
   no state-22 iteration, while acquisition collapse still formed under CEIL 1024 and CEIL 2048.
5. No repeatable reduction in variance or increase in throughput followed the smaller pulls.

Phase 13 is therefore **Outcome C - pull bucketing does not explain the convoy**. The experiment
does not prove pull duration irrelevant: state 22 is rare (2/64), both occurrences share one fork
and adjacent sequence positions, and the bounded ownership timeline is descriptive rather than a
complete event log. It does show that materially shorter synchronous holds are not sufficient to
prevent the fresh acquisition-collapse attractor, and the predicted ownership-rotation link is false
for this fixture. There is no defensible coarse transition region, so no refinement sweep and no
production change to the `FLOOR / 2048` default are justified.

The minimum justified next research question is:

```text
Why do shorter holds increase successful acquisition share while increasing same-core
reacquisition, and can per-core handle iteration order or source-lock handoff phase create the rare
fresh acquisition collapse independently of synchronous pull duration?
```

A follow-on should retain this fixture and isolate one acquisition-ordering or handoff variable. It
should not tune bucket values, contention thresholds, idle policy, execution policy, or skip.
