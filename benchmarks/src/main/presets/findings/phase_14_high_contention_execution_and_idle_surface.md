# Phase 14 Findings: High-Contention Execution and Idle Surface

## Result

Phase 14 is classified as Outcome B with a body-conditioned follower-idle split. Across the E-core
topology checks and P-core deficit portability checks, the selected STAGED policy won 39 of 40
paired blocks. Separate E-core body checks established the S, M, and H choices. The selected
upper-contention policy is:

| Body row | Tested work units | Execution | Follower idle |
|----------|------------------:|-----------|--------------:|
| S        |                48 | STAGED    |          0 ns |
| M        |               144 | STAGED    |      5,000 ns |
| H        |               216 | STAGED    |      5,000 ns |

The production baseline already selected STAGED in its highest contention row. The resulting
baseline change is therefore limited to replacing the S-row 15,000 ns idle with 0 ns. The XS and XH
body choices are retained because Phase 14 did not directly calibrate them. The contention
thresholds are also retained: Phase 14 established action economics, not the DIRECT-to-STAGED
crossover boundary.

## Anchor fixture and design

The anchor used eight E-core workers on CPUs 16-23, four parallel sources, no ordered sources,
deterministic 144-unit M-body work, FLOOR / 2048 pull bucketing, the current batch cap and handle
behavior, two 2-second warmups, and five 3-second measurements. The matrix contained DIRECT and
STAGED crossed with 0, 1,000, 5,000, and 15,000 ns follower idle.

The eight treatments were labeled A through H as follows:

```text
A DIRECT / 0 ns          E STAGED / 0 ns
B DIRECT / 1,000 ns      F STAGED / 1,000 ns
C DIRECT / 5,000 ns      G STAGED / 5,000 ns
D DIRECT / 15,000 ns     H STAGED / 15,000 ns
```

Eight independent JVM blocks used these Williams orders:

```text
A B H C G D F E
B C A D H E G F
C D B E A F H G
D E C F B G A H
E F D G C H B A
F G E H D A C B
G H F A E B D C
H A G B F C E D
```

Each treatment therefore occurred once in every measurement position and had eight independent JVM
replicas. All 64 anchor runs completed without timeout or runtime error, fixture checks passed, and
all retained artifact digests matched.

## Anchor response

Throughput is the JMH `executions` secondary score. Across CV is variation across independent JVMs;
within CV reports the mean and median iteration CV within a JVM.

| Treatment | Throughput Mops/s | Across CV | Within CV mean / median | Contention centroid | Acquisition success | Idle fraction | Dominant state (probability / self-transition) | State 22 iterations / JVMs |
|-----------|------------------:|----------:|------------------------:|--------------------:|--------------------:|--------------:|-----------------------------------------------:|---------------------------:|
| DIRECT / 0 ns      | 13.99 +/- 1.78 | 12.70% | 12.40% / 16.47% | 3.595 | 1.14% | 85.9% | 22 (0.75 / 0.97) | 35/40 / 8/8 |
| DIRECT / 1,000 ns  | 13.20 +/- 1.63 | 12.37% | 12.55% / 17.43% | 3.620 | 1.06% | 85.2% | 22 (0.79 / 0.98) | 31/40 / 7/8 |
| DIRECT / 5,000 ns  | 12.63 +/- 1.49 | 11.82% | 6.41% / 3.41%   | 3.343 | 1.84% | 75.1% | 22 (0.54 / 0.94) | 29/40 / 8/8 |
| DIRECT / 15,000 ns | 12.72 +/- 0.94 | 7.35%  | 7.76% / 8.98%   | 2.903 | 2.80% | 78.0% | 17 (0.53 / 0.89) | 0/40 / 0/8  |
| STAGED / 0 ns      | 15.71 +/- 1.96 | 12.45% | 5.32% / 0.55%   | 2.207 | 1.69% | 88.3% | 7 (0.38 / 0.83)  | 3/40 / 2/8  |
| STAGED / 1,000 ns  | 16.09 +/- 2.17 | 13.51% | 4.56% / 0.58%   | 2.109 | 3.34% | 88.2% | 7 (0.42 / 0.84)  | 2/40 / 2/8  |
| STAGED / 5,000 ns  | 16.47 +/- 1.27 | 7.72%  | 3.59% / 0.75%   | 1.529 | 4.70% | 87.3% | 7 (0.49 / 0.85)  | 3/40 / 1/8  |
| STAGED / 15,000 ns | 15.45 +/- 2.48 | 16.06% | 2.83% / 0.51%   | 1.350 | 6.16% | 88.1% | 7 (0.50 / 0.85)  | 4/40 / 1/8  |

Fork-paired STAGED-minus-DIRECT throughput deltas were:

| Block | 0 ns | 1,000 ns | 5,000 ns | 15,000 ns |
|------:|-----:|---------:|---------:|----------:|
| 1 | +3.221 M/s | +5.089 M/s | +4.703 M/s | +2.202 M/s |
| 2 | +4.871 M/s | +3.490 M/s | +3.827 M/s | +5.852 M/s |
| 3 | +2.779 M/s | +1.563 M/s | +3.181 M/s | +4.811 M/s |
| 4 | +2.043 M/s | +2.639 M/s | +5.650 M/s | -0.713 M/s |
| 5 | +5.713 M/s | +2.121 M/s | +2.281 M/s | +3.197 M/s |
| 6 | +0.939 M/s | -1.420 M/s | +1.997 M/s | -0.450 M/s |
| 7 | -4.489 M/s | +3.646 M/s | +3.093 M/s | +3.084 M/s |
| 8 | -1.251 M/s | +6.025 M/s | +5.931 M/s | +3.816 M/s |

STAGED won 6/8, 7/8, 8/8, and 6/8 blocks at the four ascending idle durations. Its mean advantages
were 14.88%, 23.03%, 31.40%, and 21.56%. Increasing idle under DIRECT reduced mean throughput at
every tested nonzero duration. Under STAGED, 5,000 ns had the best anchor mean, the lowest
across-JVM CV, and substantially fewer acquisition attempts than 0 or 1,000 ns. This is Case B: the
execution winner did not reverse with idle, but idle materially changed throughput and stability.

## Local idle refinement

The M-body STAGED refinement tested 2,000, 3,500, 5,000, 7,500, and 10,000 ns in ten independent
Williams blocks. The fixture remained multistable, with a normal branch near 17 Mops/s and an
occasional state-22 branch near 11.2 Mops/s. The physically useful plateau was 3,500-7,500 ns.
5,000 ns was retained as its center and had the highest acquisition success share (13.83%) and the
fewest acquisition attempts (199,000 per iteration) in this run. The experiment did not justify
tuning toward an isolated sample mean inside the plateau.

## Body sensitivity

At 5,000 ns, paired STAGED-minus-DIRECT effects were +4.78% with 5/8 wins for S, +31.40% with 8/8
wins for M, and +37.05% with 7/8 wins for H. The S result was ambiguous because 5,000 ns was too
long for its work duration, so S was reopened across the full action/idle matrix.

For S-body work, STAGED / 0 ns reached 18.00 +/- 1.01 Mops/s, 5.59% across-JVM CV, 1.44% / 0.47%
mean/median within-JVM CV, 4.37% acquisition success, state 7 dominance (0.30 probability and 0.80
self-transition), and zero state-22-dominant iterations. It beat DIRECT / 0 ns in 7/8 paired blocks
by 14.18% on average. It also improved over the former STAGED / 15,000 ns S-row baseline by 1.306
Mops/s (10.82%) while lowering across-JVM CV from 16.03% to 5.59%.

## Contention and topology sensitivity

On eight E-cores with M-body work and 5,000 ns idle, STAGED beat DIRECT across every deficit fixture:

| Sources | PHR | DIRECT Mops/s | STAGED Mops/s | Paired effect | STAGED wins |
|--------:|----:|--------------:|--------------:|--------------:|------------:|
| 4 | 0.50  | 12.63 | 16.47 | +31.40%  | 8/8 |
| 2 | 0.25  | 9.06  | 12.71 | +41.33%  | 8/8 |
| 1 | 0.125 | 5.58  | 14.69 | +169.70% | 8/8 |

PHR is the defensible physical control. Occupancy centroids are normalized discrete-state summaries,
not continuous contention percentages. The retained four-source rows did not record the later raw
measured-contention field, so Phase 14 does not claim exact numerical coverage intervals from these
fixtures.

The four-P-core verification separated productive (four sources, PHR approximately 1) from deficit
(one source, PHR 0.25) regimes:

| Body / sources | DIRECT Mops/s | STAGED Mops/s | Paired effect | STAGED wins | Interpretation |
|----------------|--------------:|--------------:|--------------:|------------:|----------------|
| S / 4 | 22.01 | 20.96 | -3.32%   | 3/8 | neutral/productive |
| S / 1 | 14.38 | 19.95 | +40.22%  | 7/8 | STAGED deficit win |
| M / 4 | 17.67 | 16.45 | -5.91%   | 2/8 | modest DIRECT productive win |
| M / 1 | 6.45  | 14.82 | +131.58% | 8/8 | STAGED deficit win |

The P-core productive rows had contention centroids of 0.003-0.024 and high acquisition-success
shares. They confirm the existing low-contention DIRECT policy. The deficit rows had centroids of
2.455-3.889 and confirm that the upper-region STAGED relationship is portable. State 22 remained
diagnostic rather than a selection objective; the winning policies can still enter it, especially
under the most severe deficits.

## Conclusion and next question

The all-STAGED assumption survives for the tested upper, productive-handle-deficit region, with a
body-conditioned idle split of 0 ns for S and 5,000 ns for M/H. Skip was not reopened, no state-22
escape rule was added, and unrelated mechanics remained fixed.

The minimum justified next question is Phase 15 contention-boundary calibration: locate the
DIRECT-to-STAGED crossover and encode it in the contention thresholds. Phase 14 does not support
moving those thresholds itself.
