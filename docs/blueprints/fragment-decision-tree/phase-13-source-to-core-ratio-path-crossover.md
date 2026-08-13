# Phase 13: Source-to-Core Ratio and Path-Crossover Surface Blueprint

Status: complete

Plan:
[
`phase-13-source-to-core-ratio-path-crossover.md`](../../plans/fragment-decision-tree/phase-13-source-to-core-ratio-path-crossover.md)

Blueprint intensity: maximum

Implementation intensity: high

## Fixture and ownership

Extend `FragmentPathCalibrationBenchmark`; do not change Core production code. Add one
`sourceToCoreCrossover` method whose benchmark state exposes:

```text
topology = HOMOGENEOUS_P | FULL_MACHINE
ratioDivisor = 0 | 1 | 2 | 4 | 8 | 32
productiveSources = 0 | positive explicit count
workRounds = non-negative integer
mode = DIRECT | STAGED
```

Exactly one count mechanism is active. `ratioDivisor > 0` requires `productiveSources == 0` and is
allowed only for `FULL_MACHINE`; the actual count is `max(1, SystemInfo.CPU_COUNT / ratioDivisor)`.
An explicit positive count requires `ratioDivisor == 0`. Reject an explicit count above a bounded
diagnostic maximum, but allow more sources than workers because configured `1:1` produces 32 sources
with 23 workers on this host.

Every source is an independently tracked `RepeatingSink`; actual source count and actual productive
count are therefore identical in this phase. Keep natural handle publication, existing source
interception, routing, cache behavior, fixed batch 32, completion windows, and sparse body timing
unchanged.

## Topology selection

Build the active physical-core set from process-visible CPUs. If more than one core is active,
reserve physical core 0 and require it to exist; pin the JMH harness to one of its effective logical
CPUs. Then select:

- `HOMOGENEOUS_P`: every remaining active P core on the selected socket;
- `FULL_MACHINE`: every remaining active core.

Reject an empty set, a homogeneous set containing an E core, or a full-machine request that does not
retain both P and E cores on a host whose topology declares both classes. Construct and register one
fragment per selected physical core, then read the actual registered count from the distributor. The
setup report must include the ratio basis, configured label, actual source count, selected logical
CPUs, physical cores, class, and L2 mask.

Do not infer registration from CPU masks. Require the distributor's actual registered count to equal
the number of constructed pipelines before source publication, and retain lifecycle checks through
teardown.

## Forced path and idle semantics

Use the existing `DiagnosticLease(mode, FIXED_BATCH_SIZE)` before fragment construction. This is the
real forced DIRECT or STAGED path and deliberately leaves production body aggregation disabled while
the existing sparse executor-body diagnostic remains enabled.

Phase 12's production idle predicate is unchanged. Its existing contract exempts a forced-mode
diagnostic, so all registered Phase 13 workers are expected to be active pollers and none
production-parked. Validate this at every retained iteration boundary. Consequently,
productiveSources/registeredWorkers and productiveSources/activePollingWorkers are initially
numerically equal; report both and state that this surface cannot choose between denominators unless
a later controlled-polling follow-up is justified.

For each worker, retain in stable rank order:

```text
logical CPU
physical core
P or E class
registered rank
active polling / production parked
per-worker completions
worker-local body estimate
```

## Validation and reporting

Introduce a Phase 13 fixture record containing topology, configured divisor, CPU ratio basis, actual
productive count, and the actual worker selection. Use it only in benchmark setup/reporting. At
setup and every iteration boundary require:

- actual live handles equal actual source/productive count;
- actual registered workers equal selected worker count;
- every source remains live and productive;
- every forced policy snapshot retains the requested mode;
- every worker reports the actual productive count after initialization;
- every worker is active polling, none production parked, and rank matches stable physical-core
  order; and
- measurement completion deltas are nonzero in aggregate, without treating an individually low
  mixed-core lane as disappearance unless it remains zero for the entire fork.

Existing reports already retain fork-local handle attempts, failures, successful services, pulled
frames, per-worker completion deltas/dominance, and raw body timing. Extend report labels with the
Phase 13 fixture and worker cores/classes. Do not add production metrics or per-frame counters.

## Adaptive experiment and decision rules

The coarse anchors are rounds `0`, `96`, and `256`. Run both forced modes for:

- homogeneous explicit source counts approximating workers, half, quarter, and one; and
- full-machine CPU-relative `1:1`, `1:2`, `1:4`, `1:8`, `1:32` (corrected from `1:24`).

Use short one-fork exploration first. Retain standard three-fork JMH evidence for every anchor and
add only rounds needed to bracket a winner reversal. Candidate refinements are `24`, `48` or `64`,
`80`, `176`, and
`512`. Do not assume monotonicity.

For each matched pair compute `(STAGED - DIRECT) / DIRECT`. Classify STAGED or DIRECT only when the
magnitude is at least 5%, confidence intervals do not overlap, and every fork has the same
direction. Otherwise classify the point transition/unresolved. A crossover is an interval between
adjacent measured body-cost regions, not a single interpolated threshold.

Compare topology only at closest actual productiveSources/registeredWorkers and body-cost regions.
Report the direction and magnitude of crossover motion, total throughput scaling, active P/E
composition, dominance, and source service. Any discrete participation, registration,
source-lifecycle, routing, or body-estimate regime is a correctness investigation before it becomes
policy evidence.

## Acceptance and completion

Focused tests cover count derivation, invalid mixed count mechanisms, reserve-core selection,
topology class selection, and deterministic worker metadata. A smoke must prove the current host's
reported CPU basis, actual source count, worker set, registration, active polling, and forced mode
before the sweep.

Retain raw JSON and logs outside source-controlled data. Append to this blueprint:

1. exact host/JVM/Gradle/topology evidence;
2. all configured and actual counts and both physical ratios;
3. worker identity/class/rank/polling state;
4. body rounds and estimates;
5. forced scores, uncertainty, fork means, completion dominance, and handle evidence;
6. crossover regions and homogeneous/heterogeneous comparison;
7. low-source contention interpretation, rejected dimensions, defects/discrete regimes, and raw
   paths;
8. the smallest supported tree relationship; and
9. exactly one requested Outcome 1-5.

Run:

```text
mise exec -- gradle :euhedral-core:test --no-daemon
mise exec -- gradle :benchmarks:test --no-daemon
mise exec -- gradle :euhedral-core:spotlessCheck :benchmarks:spotlessCheck --no-daemon
mise exec -- gradle build --no-daemon
git diff --check
```

## Completion record

### Host, controls, and materiality

The experiment ran on an Intel Core i9-14900K with 32 online logical CPUs, 24 physical cores, one
socket, 7 retained P-core workers, and 16 retained E-core workers in the mixed topology. The OS was
Linux `7.0.0-28-generic` x86-64. The pinned toolchain was OpenJDK `21.0.2+13-58`, Gradle `9.6.1`,
Zig `0.16.0`, and JMH `1.37`. The tested source revision was
`9e21d6e0b7db9294640a17eba594e6635e977c06` plus the uncommitted Phase 13 benchmark/report diff.

Core production code did not change. Forced DIRECT/STAGED used the existing diagnostic override,
fixed batch 32, natural handle layout, existing routing/caches/request ordering, and sparse body
timing. Forced mode is exempt from the Phase 12 production idle predicate, so every registered
worker polled and no worker production-parked in every retained row. The 20 ns boundary, polling
quota, rank implementation, 1 ms park, productive sensing, body aggregation, and 90/95 ns production
guard were unchanged.

The predeclared winner gate was at least 5% relative advantage, non-overlapping JMH confidence
intervals, and the same direction in all three fork means. Otherwise the point is unresolved. The
P-only 4-source/no-op DIRECT row met the aggregate winner gate but is separately marked discrete
because its fork means were bimodal.

Anchor and refinement rows used three forks, two 2-second warmups, and three 3-second measurements.
The one discrete contradiction row used three forks, three 3-second warmups, and five 5-second
measurements. JMH errors below are the reported 99.9% uncertainty. Exploratory one-fork results were
used only to route refinements and are not winner evidence.

### Actual source and worker counts

The corrected severe full-machine fixture is **configured `1:32`**, derived from a 32-CPU basis, and
produced **1 actual source**. It is not `1:24`. Configured labels were never reconstructed from the
23-worker execution graph.

| Topology | Configured source input | CPU ratio basis | Actual/productive sources | Registered workers | Active polling | Parked | Sources/registered | Sources/polling | Workers/source |
|----------|-------------------------|----------------:|--------------------------:|-------------------:|---------------:|-------:|-------------------:|----------------:|---------------:|
| P-only   | explicit                |              32 |                         7 |                  7 |              7 |      0 |              1.000 |           1.000 |          1.000 |
| P-only   | explicit                |              32 |                         4 |                  7 |              7 |      0 |              0.571 |           0.571 |          1.750 |
| P-only   | explicit                |              32 |                         2 |                  7 |              7 |      0 |              0.286 |           0.286 |          3.500 |
| P-only   | explicit                |              32 |                         1 |                  7 |              7 |      0 |              0.143 |           0.143 |          7.000 |
| mixed    | `1:1`                   |              32 |                        32 |                 23 |             23 |      0 |              1.391 |           1.391 |          0.719 |
| mixed    | `1:2`                   |              32 |                        16 |                 23 |             23 |      0 |              0.696 |           0.696 |          1.438 |
| mixed    | `1:4`                   |              32 |                         8 |                 23 |             23 |      0 |              0.348 |           0.348 |          2.875 |
| mixed    | `1:8`                   |              32 |                         4 |                 23 |             23 |      0 |              0.174 |           0.174 |          5.750 |
| mixed    | `1:32`                  |              32 |                         1 |                 23 |             23 |      0 |              0.043 |           0.043 |         23.000 |

Because forced-path semantics kept all registered workers polling, the registered-worker and
active-polling-worker denominators are numerically identical throughout this phase. The data cannot
prefer one denominator. Adding both to a tree would be redundant on this surface; a later
normal-policy/controlled-polling experiment is required to separate them.

### Worker topology and participation

Core IDs below are Euhedral's normalized physical-core IDs, not inferred Linux `lscpu` ordinals.
Every row retained the same stable rank, polling state, and logical CPU for its topology.

| Mixed rank | Logical CPU | Physical core | Class | P-only rank | State   |
|-----------:|------------:|--------------:|-------|------------:|---------|
|          0 |           6 |             1 | P     |           0 | polling |
|          1 |           8 |             2 | P     |           1 | polling |
|          2 |          10 |             3 | P     |           2 | polling |
|          3 |          12 |             4 | P     |           3 | polling |
|          4 |          14 |             5 | P     |           4 | polling |
|          5 |          16 |             6 | E     |           - | polling |
|          6 |          17 |             7 | E     |           - | polling |
|          7 |          18 |             8 | E     |           - | polling |
|          8 |          19 |             9 | E     |           - | polling |
|          9 |          20 |            10 | E     |           - | polling |
|         10 |          21 |            11 | E     |           - | polling |
|         11 |          22 |            12 | E     |           - | polling |
|         12 |          23 |            13 | E     |           - | polling |
|         13 |           2 |            14 | P     |           5 | polling |
|         14 |          24 |            15 | E     |           - | polling |
|         15 |          25 |            16 | E     |           - | polling |
|         16 |          26 |            17 | E     |           - | polling |
|         17 |          27 |            18 | E     |           - | polling |
|         18 |          28 |            19 | E     |           - | polling |
|         19 |          29 |            20 | E     |           - | polling |
|         20 |          30 |            21 | E     |           - | polling |
|         21 |          31 |            22 | E     |           - | polling |
|         22 |           4 |            23 | P     |           6 | polling |

Every retained fork reported positive completions for every worker. Aggregate completion dominance
was `0.143-0.157` on seven P workers and `0.050-0.073` on 23 mixed workers. The mixed split reflects
expected P/E throughput differences; it did not contain a missing lane. Exact per-iteration
per-worker completion arrays, fractions, dominance, source-by-worker acquisition failures,
successful service, pulled frames, and first-productive order are in the retained logs.

### Body-cost evidence

Sparse executor-only measurements remained mode-neutral enough for region interpretation, but mixed
cores have physically different costs and are not collapsed into one estimate. Values below are the
range of fork/class medians across both forced modes and retained source rows at each round point.
The severe 1-source mixed row is listed separately because contention/preemption inflated even its
nominal no-op sample.

|         Work rounds | P-only median range | Mixed P median range | Mixed E median range |
|--------------------:|--------------------:|---------------------:|---------------------:|
| 0, non-extreme rows |        16.4-30.3 ns |         18.3-32.4 ns |         23.9-47.2 ns |
|   0, mixed 1 source |                   - |         36.6-51.2 ns |         55.4-74.9 ns |
|                  24 |        42.5-45.8 ns |                    - |                    - |
|                  48 |        59.5-70.7 ns |         62.4-65.7 ns |       104.5-113.8 ns |
|                  96 |      101.0-123.6 ns |       105.4-125.9 ns |       182.2-217.5 ns |
|                 176 |      172.6-188.0 ns |       182.2-216.3 ns |       318.7-353.6 ns |
|                 256 |      246.4-253.1 ns |       256.3-275.6 ns |       451.1-480.6 ns |
|                 512 |      474.6-494.2 ns |       499.3-505.1 ns |       882.0-888.9 ns |

### P-only forced-path surface

Scores and fork means are millions of frames/second. The parenthesized values are the three fork
means. Relative advantage is `(STAGED - DIRECT) / DIRECT`.

| Sources/workers | Rounds |                     DIRECT score (forks) |                 STAGED score (forks) | STAGED advantage | Region           |
|-----------------|-------:|-----------------------------------------:|-------------------------------------:|-----------------:|------------------|
| 1/7             |      0 |     20.306 +/- 2.695 (21.30/21.45/18.17) | 16.935 +/- 1.172 (16.90/16.15/17.75) |           -16.6% | unresolved       |
| 1/7             |     24 |     20.002 +/- 0.838 (20.59/19.48/19.94) | 17.986 +/- 1.342 (17.53/19.02/17.40) |           -10.1% | unresolved       |
| 1/7             |     48 |     16.624 +/- 4.470 (19.33/17.13/13.41) | 17.602 +/- 3.180 (19.69/17.75/15.36) |            +5.9% | unresolved       |
| 1/7             |     96 |     16.462 +/- 4.227 (13.12/17.93/18.34) | 24.443 +/- 0.691 (24.97/24.23/24.12) |           +48.5% | STAGED           |
| 1/7             |    256 |     12.970 +/- 0.136 (12.88/13.07/12.96) | 16.934 +/- 0.292 (17.07/17.01/16.73) |           +30.6% | STAGED           |
| 2/7             |      0 |     48.115 +/- 6.881 (51.10/42.66/50.58) | 32.116 +/- 2.451 (33.90/31.85/30.59) |           -33.3% | DIRECT           |
| 2/7             |     48 |     35.163 +/- 3.247 (32.81/35.53/37.15) | 31.922 +/- 2.295 (31.48/30.75/33.53) |            -9.2% | unresolved       |
| 2/7             |     96 |     27.007 +/- 1.506 (27.74/27.45/25.83) | 28.557 +/- 1.298 (29.16/28.95/27.56) |            +5.7% | unresolved       |
| 2/7             |    176 |     21.862 +/- 1.810 (21.21/22.27/22.11) | 23.494 +/- 0.930 (24.04/23.62/22.82) |            +7.5% | unresolved       |
| 2/7             |    256 |     17.624 +/- 0.504 (17.76/17.24/17.87) | 19.382 +/- 0.292 (19.46/19.49/19.19) |           +10.0% | STAGED           |
| 4/7             |      0 | 119.539 +/- 40.851 (144.22/67.37/147.02) | 52.033 +/- 1.404 (52.10/50.50/53.50) |           -56.5% | DIRECT, discrete |
| 4/7             |     96 |     39.934 +/- 6.409 (39.94/35.53/44.33) | 38.013 +/- 3.930 (39.84/39.28/34.92) |            -4.8% | unresolved       |
| 4/7             |    176 |     29.403 +/- 2.023 (29.47/30.69/28.05) | 27.754 +/- 2.370 (28.91/28.25/26.10) |            -5.6% | unresolved       |
| 4/7             |    256 |     22.882 +/- 0.234 (22.72/22.94/22.98) | 22.386 +/- 0.373 (22.67/22.18/22.31) |            -2.2% | unresolved       |
| 7/7             |      0 | 225.126 +/- 8.735 (221.83/221.63/231.92) | 67.778 +/- 6.586 (68.38/65.47/69.49) |           -69.9% | DIRECT           |
| 7/7             |     96 |     62.100 +/- 3.400 (59.45/63.01/63.85) | 38.549 +/- 7.127 (36.72/34.86/44.07) |           -37.9% | DIRECT           |
| 7/7             |    256 |     26.990 +/- 0.541 (26.64/27.36/26.97) | 22.980 +/- 0.924 (23.49/23.08/22.37) |           -14.9% | DIRECT           |
| 7/7             |    512 |     14.112 +/- 0.693 (14.59/13.99/13.76) | 13.342 +/- 0.261 (13.21/13.43/13.39) |            -5.5% | unresolved       |

The P-only crossover regions are therefore:

- `7/7`: DIRECT through 256 rounds; transition/unresolved at 512;
- `4/7`: DIRECT at no-op, then unresolved from 96 through 256;
- `2/7`: DIRECT at no-op, unresolved from 48 through 176, STAGED at 256; and
- `1/7`: unresolved through 48, STAGED from 96 through 256.

### Full-machine forced-path surface

The configured labels below remain CPU-relative. The parenthesized fraction is the separately
measured actual productive-source/worker ratio.

| Configured and actual row | Rounds |                      DIRECT score (forks) |                   STAGED score (forks) | STAGED advantage | Region     |
|---------------------------|-------:|------------------------------------------:|---------------------------------------:|-----------------:|------------|
| `1:1` (32/23)             |      0 | 156.099 +/- 20.890 (141.32/169.98/156.99) | 102.023 +/- 9.203 (99.01/107.53/99.54) |           -34.6% | DIRECT     |
| `1:1` (32/23)             |     96 |   98.871 +/- 13.931 (88.32/106.99/101.30) |   65.881 +/- 6.047 (67.02/69.23/61.40) |           -33.4% | DIRECT     |
| `1:1` (32/23)             |    256 |      52.363 +/- 0.754 (52.88/51.87/52.34) |   41.633 +/- 1.865 (41.55/42.22/41.13) |           -20.5% | DIRECT     |
| `1:1` (32/23)             |    512 |      29.188 +/- 0.174 (29.23/29.26/29.08) |   26.405 +/- 0.793 (26.68/25.83/26.71) |            -9.5% | DIRECT     |
| `1:2` (16/23)             |      0 |  162.648 +/- 7.972 (158.08/168.70/161.16) |  98.629 +/- 6.763 (98.98/94.39/102.52) |           -39.4% | DIRECT     |
| `1:2` (16/23)             |     96 |     80.702 +/- 11.545 (81.04/72.60/88.46) |   67.272 +/- 7.113 (70.99/66.36/64.46) |           -16.6% | unresolved |
| `1:2` (16/23)             |    256 |      45.599 +/- 0.776 (45.05/45.66/46.09) |   43.030 +/- 0.730 (42.88/43.18/43.03) |            -5.6% | DIRECT     |
| `1:2` (16/23)             |    512 |      26.223 +/- 0.401 (26.23/26.41/26.03) |   26.418 +/- 0.190 (26.44/26.29/26.52) |            +0.7% | unresolved |
| `1:4` (8/23)              |      0 |  230.129 +/- 4.242 (227.89/233.34/229.16) |   77.850 +/- 5.821 (76.19/77.64/79.72) |           -66.2% | DIRECT     |
| `1:4` (8/23)              |     96 |      62.064 +/- 0.321 (61.90/62.02/62.27) |   57.357 +/- 6.664 (59.67/53.45/58.94) |            -7.6% | unresolved |
| `1:4` (8/23)              |    176 |      41.120 +/- 3.689 (43.49/40.75/39.13) |   45.458 +/- 1.913 (44.57/46.29/45.51) |           +10.5% | unresolved |
| `1:4` (8/23)              |    256 |      34.963 +/- 0.133 (34.98/34.94/34.97) |   37.144 +/- 0.678 (37.21/37.47/36.75) |            +6.2% | STAGED     |
| `1:8` (4/23)              |      0 |  107.894 +/- 1.749 (106.81/108.23/108.64) |   40.829 +/- 2.265 (40.05/42.58/39.86) |           -62.2% | DIRECT     |
| `1:8` (4/23)              |     48 |      46.940 +/- 0.987 (46.37/47.63/46.82) |   41.680 +/- 1.513 (42.57/41.90/40.57) |           -11.2% | DIRECT     |
| `1:8` (4/23)              |     96 |      36.791 +/- 0.835 (37.04/37.15/36.18) |   41.406 +/- 1.942 (41.98/41.91/40.33) |           +12.5% | STAGED     |
| `1:8` (4/23)              |    256 |      23.586 +/- 0.364 (23.81/23.50/23.45) |   30.223 +/- 1.787 (30.01/29.28/31.37) |           +28.1% | STAGED     |
| `1:32` (1/23)             |      0 |      13.343 +/- 1.208 (13.05/14.28/12.70) |   10.719 +/- 0.385 (10.89/10.61/10.66) |           -19.7% | DIRECT     |
| `1:32` (1/23)             |     96 |      11.443 +/- 0.655 (10.98/11.87/11.47) |   10.704 +/- 0.623 (11.07/10.51/10.53) |            -6.5% | unresolved |
| `1:32` (1/23)             |    176 |          9.699 +/- 0.794 (9.84/9.98/9.28) |    10.170 +/- 1.252 (10.96/10.07/9.48) |            +4.9% | unresolved |
| `1:32` (1/23)             |    256 |          8.886 +/- 0.213 (8.95/8.92/8.79) |   11.340 +/- 0.325 (11.32/11.32/11.38) |           +27.6% | STAGED     |

The mixed crossover regions are:

- `32/23`: DIRECT through 512 rounds;
- `16/23`: DIRECT through 256, transition/unresolved at 512;
- `8/23`: DIRECT at no-op, unresolved from 96 through 176, STAGED at 256;
- `4/23`: DIRECT through 48, STAGED from 96 through 256; and
- `1/23`: DIRECT at no-op, unresolved from 96 through 176, STAGED at 256.

The extreme `1/23` point is non-monotonic relative to `4/23`: DIRECT returns at no-op before the
same high-cost STAGED region reappears. The tree must preserve that return rather than encode one
monotonic threshold.

### Topology, contention, and hypotheses

At the closest physical ratios, topology did not reverse the scarcity relationship:

- P-only `1/7 = 0.143` and mixed `4/23 = 0.174` both transition between 48 and 96 rounds;
- P-only `2/7 = 0.286` and mixed `8/23 = 0.348` are unresolved through 176 and resolve STAGED at
  256; and
- abundant rows preserve DIRECT much longer.

Mixed P/E body cost and completion magnitude differ physically, but the crossover direction at
comparable ratios is structurally similar. H2 is therefore not confirmed as the primary branch. Core
type, shared E-core L2 groups, and active-core composition remain future variables, especially for
the unmatched extreme `1/23` tail, but no P/E weighting or rank change is justified here.

Low-source degradation is not useful-parallelism disappearance. All workers completed work, but
handle acquisition failures rose sharply as sources fell. Representative median fork evidence:

| Topology/row | Rounds/mode | Acquisition failure fraction | Frames per successful service |
|--------------|-------------|-----------------------------:|------------------------------:|
| mixed 32/23  | 0 DIRECT    |                        0.145 |                         32.00 |
| mixed 4/23   | 0 DIRECT    |                        0.834 |                         24.93 |
| mixed 1/23   | 0 DIRECT    |                        0.976 |                         15.67 |
| mixed 1/23   | 96 DIRECT   |                        0.987 |                         11.59 |
| mixed 1/23   | 256 DIRECT  |                        0.991 |                         11.89 |
| P-only 7/7   | 0 DIRECT    |                        0.248 |                         32.00 |
| P-only 1/7   | 0 DIRECT    |                        0.953 |                         13.86 |
| P-only 1/7   | 256 STAGED  |                        0.933 |                          5.15 |

STAGED acquisition counters are not directly comparable in magnitude because STAGED performs work
from cache between acquisitions, but its retained pulled-frame/service evidence confirms continuous
source progress.

The P-only 4-source/no-op contradiction row exposed a real discrete DIRECT contention regime. Two
forks ran at `144.22M` and `147.02M`; one ran at `67.37M`. All seven workers were balanced and
present in all forks. The fast forks had about 47% acquisition failures and 27.8 frames/success; the
slow fork had 73% failures and 19.9 frames/success. Its sampled no-op cost was also interrupted to a
27-83 ns worker range versus roughly 17-19 ns in fast forks. Longer warmup did not remove the
regime. This is contention-sensitive service behavior, not a missing worker, source completion,
productive observation, registration, rank, cache ownership, routing, or CPU-selection defect. The
fork cluster is retained and not reduced to its aggregate mean.

Hypothesis decisions:

- H0 rejected: physical scarcity materially moves the crossover.
- H1 rejected in its declared direction: increasing scarcity generally moves STAGED earlier, not
  DIRECT later, with a separate extreme-scarcity cheap DIRECT return.
- H2 not confirmed as the primary interaction: closest P-only/mixed physical ratios have similar
  transition regions; mixed core cost remains a future refinement variable.
- H3 accepted for the remaining severe low-source throughput collapse, but not for the existence of
  the crossover interaction. At `1/23`, DIRECT is resolved only for no-op. At rounds 96 and 176,
  where the production body guard would prefer or approach STAGED, DIRECT's apparent advantages are
  unresolved or absent; STAGED wins by 27.6% at 256. Path choice therefore does not materially
  recover the catastrophic low-source loss in the current STAGED-cost region.
- H4 rejected: every lifecycle/count/participation check passed. The one discrete row is a measured
  acquisition-contention regime and does not invalidate the rest of the surface.

The dimensions rejected as primary explanations were configured ratio label, worker disappearance,
parking/rank selection, source completion, stale productive count, and a universal body-cost
threshold. Actual source/polling-worker ratio plus body cost preserves the measured relationship
better than configured ratio. Registered-worker ratio is equivalent on this forced surface.

### Raw evidence and commands

Raw evidence is outside source-controlled data under `/tmp/euhedral-phase13-20260813`:

- `host-toolchain.txt`;
- `smoke-homogeneous.{json,log}` and `smoke-full-1-32.{json,log}`;
- `explore-homogeneous.{json,log}` and `explore-full-machine.{json,log}`;
- `retained-homogeneous-anchors.{json,log}` and `retained-full-machine-anchors.{json,log}`;
- every `refine-homogeneous-*.{json,log}` and `refine-full-*.{json,log}` pair; and
- `contradiction-homogeneous-sources-4-rounds-0.{json,log}`.

The common retained command was:

```text
mise exec -- java -XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dlogback.configurationFile=benchmark-logback.xml \
  -cp 'benchmarks/build/euhedral-benchmark.jar:benchmarks/build/lib/*' \
  org.openjdk.jmh.Main \
  'io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmark.sourceToCoreCrossover' \
  <topology/source/round parameters> -p mode=DIRECT,STAGED \
  -f 3 -wi 2 -w 2s -i 3 -r 3s -foe true -rf json -rff <evidence.json> \
  -jvmArgsAppend '-XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED'
```

### Smallest supported decision-tree relationship

The smallest relationship supported on this host is a measured interaction, not a portable numeric
policy:

```text
actual productive sources / polling workers
    + body-cost region
        -> DIRECT / transition / STAGED
```

As the physical source fraction falls from abundance toward roughly `0.14-0.35`, STAGED becomes
favorable at lower body costs. At the unmatched extreme `1/23` fraction, cheap DIRECT reappears
before an unresolved middle and high-cost STAGED region. This non-monotonic tail must remain a
separate measured region until another machine or controlled-polling surface confirms its cause. No
numeric production threshold is portable and no production selector change was made.

**Final outcome - Outcome 1: scarcity-dependent path branch confirmed.** Actual productive-source
scarcity materially and repeatably shifts the DIRECT/STAGED crossover, although the observed main
direction is toward earlier STAGED rather than H1's predicted wider DIRECT region. The registered
and active-polling denominators are identical in all forced rows, so neither is empirically better
on this surface and both are not required as separate inputs. The next blueprint should integrate
the interaction conservatively only after separating those denominators under production-reachable
polling and preserving the extreme-scarcity non-monotonic return.
