# Phase 13 Recheck: Collision-Triggered Handle Shuffling

## Result

Collision-triggered worker-local handle shuffling did not eliminate the state-22 high-contention
attractor. The post-change experiment entered state 22 in 6 of 64 measurements across 3 of 8
independent forks. The retained pre-change Phase 13 baseline entered it in 2 of 64 measurements in 1
of 8 forks.

The post-change state-22 iterations reproduce the original signature: 97.5%-99.3% state-22
occupancy, 0.994-0.999 dominant self-transition, 0.171%-0.696% acquisition success, and
11.275-11.635 million executions/s. The pre-change signature was 99.5%-99.6% occupancy, 0.999
self-transition, 0.324%-0.627% success, and 11.412-11.428 million executions/s.

The observed increase from one affected fork to three is not enough to conclude that shuffling
causes more attractor entries. It is enough to reject the claim that this implementation removes the
pathology.

## Evidence and controls

The post-change run retained the eight-sequence Phase 13 Williams matrix. It completed eight
independent one-fork trials, each with two 2-second warmups and eight 3-second measurements. The
completed pre- and post-change trial configurations are identical. CPUs 16-23, eight E-core workers,
four parallel sources, deterministic 144-unit work, the 8,000,000-execution target, STAGED policy,
idle policy, decision weights, 4096-frame batch cap, JVM arguments, and treatment orders were
unchanged.

All 64 post-change measurement directories and both comparison directories are present. All 466
post-change artifact digests match. Every treatment occurred once per fork and once at each matrix
position. Every fork observed four handle identities and normally observed eight attempting workers.
The logs report Java 21.0.2, one JMH fork, two warmups, eight measurements, and no runtime error or
timeout.

One state-22 measurement, fork 3 position 4, had no acquisition events from core 12. Its aggregated
steady-state `registeredWorkers` and `productiveHandleRatio` means were consequently 7.0 and 0.4375,
although the other seven core records retained four handles and a ratio of 0.500. This iteration is
preserved as a runtime anomaly rather than discarded. The other five state-22 measurements observed
all eight workers and the 0.500 ratio, so the primary result does not depend on the anomalous row.

## State-22 episodes

| Fork | Position | Treatment    | Occupancy | Contention centroid | Self-transition | Success share | Mean pull | Mean hold | Throughput |
|-----:|---------:|--------------|----------:|--------------------:|----------------:|--------------:|----------:|----------:|-----------:|
|    3 |        4 | FLOOR / 2048 |    99.31% |               3.993 |          0.9982 |        0.430% |     2,048 |    719 us | 11.275 M/s |
|    4 |        7 | FLOOR / 512  |    97.49% |               3.975 |          0.9939 |        0.696% |       974 |    332 us | 11.620 M/s |
|    4 |        8 | CEIL / 4096  |    97.61% |               3.976 |          0.9966 |        0.171% |     4,096 |  1,384 us | 11.635 M/s |
|    7 |        5 | FLOOR / 2048 |    99.18% |               3.992 |          0.9988 |        0.302% |     2,048 |    713 us | 11.441 M/s |
|    7 |        6 | CEIL / 512   |    97.52% |               3.975 |          0.9939 |        0.686% |       970 |    342 us | 11.495 M/s |
|    7 |        7 | CEIL / 1024  |    97.68% |               3.977 |          0.9941 |        0.659% |     1,024 |    357 us | 11.429 M/s |

State 22 therefore appeared under both FLOOR and CEIL, at targets from 512 through 4096, and under
the production baseline twice. Fork 7 recovered at position 8 after three adjacent state-22
measurements. Fork 3 recovered immediately after its isolated entry. The benchmark reset still
prevents an irrevocably retained controller state, but collision shuffling does not reliably break
the acquisition-collapse episode.

## Pre/post geometry

The comparison below uses forks as the replication unit. Iteration pooling is used only for the
descriptive exact shares.

| Metric                         |           Pre-change |          Post-change | Interpretation                                      |
|--------------------------------|---------------------:|---------------------:|-----------------------------------------------------|
| State-22 dominant measurements |               2 / 64 |               6 / 64 | Pathology persists                                  |
| Forks with state 22            |                1 / 8 |                3 / 8 | No evidence of improved entry stability             |
| Mean state-22 occupancy        |                6.97% |               12.22% | Increased in this realization                       |
| Mean contention centroid       |                1.803 |                1.440 | Ordinary contention decreased                       |
| Mean body centroid             |                2.050 |                2.036 | Essentially unchanged                               |
| Mean dominant self-transition  |                0.847 |                0.870 | Did not decrease                                    |
| Non-state-22 success share     |               15.20% |               18.69% | Ordinary acquisition improved                       |
| State-22 success share         |               0.476% |               0.491% | Collapse unchanged                                  |
| Fork-balanced success share    |               14.74% |               16.98% | Improved descriptively                              |
| Pooled exact success share     |                9.03% |                5.51% | Fell because attractor rows generated many failures |
| Mean owner turnover            |               51.61% |               45.78% | Ownership rotation decreased                        |
| Mean same-owner run            |                 2.04 |                 2.38 | Same-owner reacquisition increased                  |
| Fork-mean throughput           | 15.787 +/- 2.015 M/s | 15.815 +/- 1.757 M/s | No material mean change                             |
| Across-fork throughput CV      |               12.76% |               11.11% | Modest descriptive decrease                         |
| Median within-fork CV          |               0.718% |               0.732% | Unchanged                                           |
| Mean within-fork CV            |                2.43% |                4.37% | Worsened through more attractor transitions         |

The ordinary regime clearly changed. Excluding state 22, acquisition success increased by about 3.5
percentage points and the contention centroid fell from 1.732 to 1.178. Ordinary dominant states
moved from the pre-change state-12/state-7 split to state 7, with one state-2 fork. This is evidence
that collision shuffling materially changes acquisition geometry.

It is not evidence that the implementation resolves the pathology. Within state 22, acquisition
success stayed near 0.5%, the contention centroid stayed near 4.0, and dominant self-transition
stayed near 1.0. The post-change aggregate recorded 43.85 million failed acquisitions versus 25.43
million pre-change because more measurements occupied the collapsed regime. Owner turnover also
moved opposite to a fairness interpretation: it decreased while same-owner runs lengthened.

## Throughput and treatment effects

Overall fork-mean throughput changed by only +0.027 million executions/s (+0.17%). This is
negligible relative to the 1.76-2.02 million/s across-fork standard deviations. There is no overall
throughput improvement or regression supported by these eight forks.

The production FLOOR / 2048 row did regress in this realization: mean throughput fell from 15.939 to
14.770 million/s because it contained two post-change state-22 measurements and none in the
baseline. That treatment-specific loss should be reported separately from the unchanged overall
mean, but it is not a replicated normal-regime cost estimate.

FLOOR/CEIL still materially altered the physical pull at a fixed target. Post-change CEIL reduced
mean pull relative to FLOOR by 6.3%, 13.8%, 19.2%, and 9.2% at targets 512, 1024, 2048, and 4096.
The corresponding mean hold-time changes were +10.4%, -12.2%, -23.5%, and -20.6%. The 512 hold
reversal reflects the changed regime/sample mix; the manipulation remains clear in pull count.

There is no repeatable controller-state or throughput winner between FLOOR and CEIL. Median paired
CEIL-minus-FLOOR throughput changes were -0.12%, +0.29%, -0.16%, and -0.02% by ascending target. The
much larger mean differences at 512, 1024, and 2048 are driven by which treatment inherited a rare
state-22 episode. This matrix still provides no basis for pull-bucketing calibration.

## Interpretation

Collision-triggered handle shuffling changes normal acquisition geometry but is not sufficient to
prevent the high-contention attractor. The result does not establish that handle order is
irrelevant: the shuffle only perturbs collided handles, and the retained telemetry cannot show the
resulting per-worker permutations or distinguish synchronized post-shuffle traversal from lock
handoff or owner-phase effects. It does establish that the current shuffle cannot be accepted as a
pathology fix based on lower average contention or higher ordinary-regime success.

Do not redesign the scheduler, tune the shuffle, or begin pull-bucketing calibration from this
result. If mechanism work continues, the now-justified narrow diagnostic is bounded handle-order
observation around collision-buffer return and reacquisition. That diagnostic should test why
independent worker seeds and repeated collision shuffles can coexist with multi-iteration state-22
episodes; it should not introduce another scheduler policy dimension.
