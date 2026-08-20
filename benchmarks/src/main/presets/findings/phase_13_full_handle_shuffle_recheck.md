# Phase 13 Recheck 2: Full Handle Shuffling

## Result

Returning every dequeued live handle through the worker-local shuffle buffer did not eliminate the
state-22 high-contention attractor. The full-shuffle experiment entered state 22 in 2 of 64
measurements across 2 of 8 independent forks. Recheck 1's collision-only shuffle entered it in 6 of
64 measurements across 3 forks; the original Phase 13 implementation entered it in 2 of 64
measurements in 1 fork.

The two full-shuffle episodes retained the pathological signature: 98.4%-98.6% state-22 occupancy,
0.997 dominant self-transition, 0.782%-0.813% acquisition success, and 11.382-11.579 million
executions/s. Both measurements retained all eight workers, four live handles, and the 0.500
productive-handle ratio. Full shuffling therefore reduces the episode count relative to the
collision-only realization, but the primary hypothesis that it removes the attractor is rejected.

This does not show that full shuffling is ineffective. Owner turnover rose and same-owner runs
shortened substantially, while ordinary-regime acquisition remained better than in the original
implementation. It shows that disrupting worker-local handle traversal is not sufficient to prevent
the collapsed regime.

## Evidence and controls

The experiment reused the exact completed recheck-1 trial configurations. It completed eight
independent one-fork trials, each with two 2-second warmups and eight 3-second measurements. CPUs
16-23, eight E-core workers, four parallel sources, deterministic 144-unit work, the
8,000,000-execution target, STAGED policy, idle policy, decision weights, 4096-frame batch cap, JVM
arguments, and Williams treatment orders were unchanged.

All eight run directories, eight logs, eight completed trial configurations, eight fork directories,
and 64 measurement directories are present. Every treatment occurred once per fork and once at every
matrix position. The three comparison matrices each contain 64 pairs. All retained artifact digests
match: 457 of 457 for the original experiment, 466 of 466 for recheck 1, and 475 of 475 for recheck
2. The benchmark logs report Java 21.0.2, one JMH fork, two warmups, eight measurements, and no
runtime error or timeout.

Every recheck-2 measurement observed four handle identities, eight attempting workers, eight
registered workers, and a steady-state productive-handle ratio of 0.500. There are no runtime
control anomalies to qualify the two state-22 episodes.

## State-22 episodes

| Fork | Position | Treatment   | Occupancy | Contention centroid | Self-transition | Success share | Mean pull | Mean hold | Throughput |
|-----:|---------:|-------------|----------:|--------------------:|----------------:|--------------:|----------:|----------:|-----------:|
|    5 |        8 | FLOOR / 512 |    98.61% |               3.986 |          0.9971 |        0.782% |       727 |    251 us | 11.382 M/s |
|    8 |        2 | FLOOR / 512 |    98.43% |               3.984 |          0.9971 |        0.813% |       730 |    253 us | 11.579 M/s |

Both entries happened under FLOOR / 512, but two episodes are not enough to identify that treatment
as causal. The retained experiments place state-22 episodes under both division modes and at targets
from 512 through 4096. Neither full-shuffle episode continued into the following measurement, so the
per-iteration reset still permits recovery.

## Three-implementation comparison

Forks remain the replication unit. Exact pooled shares and measurement counts below are descriptive
and are not treated as 64 independent replications.

| Metric                         |    Original Phase 13 | Collision-only shuffle |         Full shuffle |
|--------------------------------|---------------------:|-----------------------:|---------------------:|
| State-22 dominant measurements |               2 / 64 |                 6 / 64 |               2 / 64 |
| Forks with state 22            |                1 / 8 |                  3 / 8 |                2 / 8 |
| Mean state-22 occupancy        |                6.97% |                 12.22% |                6.55% |
| Mean contention centroid       |                1.803 |                  1.440 |                1.349 |
| Mean body centroid             |                2.050 |                  2.036 |                2.055 |
| Mean dominant self-transition  |                0.847 |                  0.870 |                0.877 |
| Non-state-22 success share     |               15.20% |                 18.69% |               17.47% |
| State-22 success share         |               0.476% |                 0.491% |               0.797% |
| Fork-balanced success share    |               14.74% |                 16.98% |               16.95% |
| Pooled exact success share     |                9.03% |                  5.51% |               10.00% |
| Attempts / successes           |     27.95 M / 2.52 M |       46.40 M / 2.56 M |     27.73 M / 2.77 M |
| Failed acquisitions            |              25.43 M |                43.85 M |              24.96 M |
| Mean owner turnover            |               51.61% |                 45.78% |               60.04% |
| Mean same-owner run            |                 2.04 |                   2.38 |                 1.68 |
| Fork-mean throughput           | 15.787 +/- 2.015 M/s |   15.815 +/- 1.757 M/s | 15.985 +/- 2.038 M/s |
| Across-fork throughput CV      |               12.76% |                 11.11% |               12.75% |
| Median within-fork CV          |               0.718% |                 0.732% |               0.929% |
| Mean within-fork CV            |                2.43% |                  4.37% |                3.82% |

Full shuffling clearly changes ownership geometry. Relative to collision-only shuffling, turnover
increased by 14.3 percentage points and the mean same-owner run fell from 2.38 to 1.68. Despite that
stronger rotation, the surviving state-22 measurements still achieved fewer than 1% of acquisition
attempts. The pathology therefore cannot be accepted as fixed based on the intended ownership
effect.

Average contention fell from 1.440 to 1.349 relative to recheck 1, but that improvement is explained
by fewer collapsed measurements. Among non-state-22 measurements, contention increased from 1.178 to
1.264 and acquisition success decreased from 18.69% to 17.47%. Both remain better than the original
implementation's 1.732 contention centroid and 15.20% acquisition success. Full shuffling thus
preserves part of the ordinary-regime improvement but does not improve it over collision-only
shuffling in this realization.

Dominant-state self-transition did not decrease. Its overall mean rose to 0.877, and the two
state-22 episodes remained almost absorbing at 0.997. Lower average contention without reduced state
persistence is only partial evidence.

## Throughput and stability

Full-shuffle fork-mean throughput was 15.985 million executions/s, +1.08% versus recheck 1 and
+1.25% versus the original run. Those differences are small compared with the 1.76-2.04 million/s
across-fork standard deviations and do not support a throughput improvement claim.

Stability did not consistently improve. Across-fork CV returned from recheck 1's 11.11% to 12.75%,
essentially the original 12.76%. Median within-fork CV worsened to 0.929%. Mean within-fork CV
improved relative to collision-only shuffling because there were fewer attractor transitions, but
remained worse than the original run. Fork 3 also retained all ordinary state-7 measurements while
running at 11.242 million/s for the whole fork. That surprising fork is preserved; its telemetry
does not identify it as a state-22 acquisition collapse, and it prevents interpreting fewer state-22
rows as general throughput stability.

The production FLOOR / 2048 treatment had no state-22 entries and averaged 16.124 million/s,
compared with 14.770 million/s in recheck 1 and 15.939 million/s originally. Recheck 1's value was
depressed by two state-22 rows, so this is recovery from episode assignment rather than evidence of
a production-baseline speedup.

## Pull-bucketing treatments

FLOOR/CEIL still materially changed physical pulling. Relative to FLOOR at the same target, CEIL
reduced mean pull by 7.0%, 18.4%, 24.8%, and 15.2% at targets 512, 1024, 2048, and 4096. Mean lock
hold time changed by -21.4%, -19.9%, -26.1%, and -3.2%, respectively.

There is still no repeatable throughput winner. Median paired CEIL-minus-FLOOR throughput changes
were +0.14%, -0.03%, +0.42%, and +0.12% by ascending target. The FLOOR / 512 mean is heavily
depressed because both rare state-22 episodes landed there; the median paired result remains small.
The retained matrix confirms that bucketing changes pull and hold geometry, but provides no basis
for a new calibration phase.

## Interpretation

Full handle shuffling more directly disrupts correlated traversal than collision-only shuffling, and
the owner-turnover telemetry confirms that it materially changes acquisition order. The lower
state-22 count relative to recheck 1 is compatible with handle-order correlation contributing to
attractor entry. It is not proof that the implementation is optimal, and the surviving independent
episodes show that handle-order decorrelation alone is insufficient.

Do not tune the shuffle, redesign the scheduler, or begin pull-bucketing calibration from this
result. If mechanism work continues, the pathology now satisfies the prerequisite for a bounded
handle-sequence diagnostic: state 22 survives full shuffling, while the existing convoy telemetry
shows improved rotation but cannot distinguish the remaining synchronization mechanism. That
diagnostic should be a separate observational experiment rather than a new scheduler-policy
dimension.
