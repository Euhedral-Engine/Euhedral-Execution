# Phase 1 Findings: Body-Conditioned Idle Duration Calibration

## Decision

Phase 1 is classified as **Outcome D - idling only matters for part of the body surface**.
The corrected CONTINUOUS results support this production policy:

| Body band | Production name | Previous (ns) | Selected (ns) |
|---|---|---:|---:|
| XS | `xsPark` | 1,000 | 50,000 |
| S | `sPark` | 0 | 0 |
| M | `mPark` | 5,000 | 0 |
| H | `hPark` | 5,000 | 0 |
| XH | `xhPark` | 5,000 | 0 |

This is a two-region structure: XS receives a 50 us ordinary timed park; S/M/H/XH use the
0 ns no-timed-park control. The implementation still selects the ordinary idle action at 0 ns,
but `LockSupport.parkNanos(0)` returns immediately. Thus 0 ns is not an idle-action disable switch.

No body, contention, DIRECT/STAGED, pull-bucketing, ordering, source-assignment, or productivity
threshold was changed.

## Corrected harness and invariant checks

The authoritative lifecycle was CONTINUOUS. A trial JVM was initialized and warmed once, then all
six ordered five-second measurement windows observed the established trajectory without clearing
worker caches or resetting controller state. RESET was not used because no result required a
state-isolation diagnostic.

The corrected harness used:

- completion accounting attributed to the CPU that executed each `NoOpFrame`;
- a true no-op when `MicroCalibrator` receives zero work;
- nonzero `MicroCalibrator` work for the remaining body fixtures;
- 23 physical workers on CPU set 2-31, harness CPU 0, and 11 continuously fed parallel repeating
  sources for the main surfaces;
- 8,000,000 required executions per invocation, two 2-second warmups, then six 5-second
  CONTINUOUS windows;
- one fork per expanded trial and two independently started JVM trials per treatment.

Every completed `trial_config.json` was checked for `lifecycleMode=CONTINUOUS` and
`productivityThresholdWeight=0`. Runtime telemetry resolved the productivity threshold to zero;
the rank-based productivity participation gate did not participate in any measurement. The
reported productive-handle ratio of 11/23 (0.4783) describes the source/worker topology, not a
productivity-gate exclusion. Ordinary idle observations came from the ordinary idle-decision
observer and remain separate from unrelated parking.

All main and refinement windows were continuously fed. Each five-treatment batch retained 10 JVMs
and 60 measurement windows. The neighboring validation retained 8 JVMs and 48 windows.

## Body and contention fixtures

The existing thresholds remained `BodyCostWeights(96, 128, 216, 288)`. Existing calibration
artifacts supplied the body work fixtures; the first attempted S value (`workUnits=96`) was rejected
because its marginal occupancy was 76.166% XS and only 23.802% S. A short qualification over
104/112/120/128 selected 112: it had the strongest stable S marginal occupancy (72.230% S, 26.241%
XS, 1.529% M) among those candidates. That qualification was not an idle-policy response surface.

| Band | `workUnits` | Mean raw measured cost (ns) | Mean smoothed cost (ns) | Fixture note |
|---|---:|---:|---:|---|
| XS | 0 | 68.8-72.6 | 21.6-23.4 | Corrected true no-op; 100% XS occupancy |
| S | 112 | 231.5 | 201.2 | Qualified narrow-band fixture |
| M | 172 | 316.5 | 287.9 | Existing calibrated midpoint fixture |
| H | 252 | 430.0 | 403.1 | Existing calibrated midpoint fixture |
| XH | 384 | 617.4 | 591.0 | Existing calibrated upper fixture |

The 23-worker/11-source common topology exercised follower idling, stayed fed, and produced useful
idle selection and contention signal for every body row. No body extreme required a different main
topology. Marginal band occupancy changes with the idle treatment because the observer samples the
controller's smoothed decision inputs; raw body cost remained monotonic and stable. At the selected
0 ns controls, the two-fork target-band occupancies were S 71.943/86.636%, M 60.306/72.039%, H
approximately 45.3/45.1%, and XH 42.771/30.411%. The H/XH spill is preserved as a diagnostic rather
than being used to retune thresholds in this phase.

## Experimental design

Each preset imports the common Phase 1 profile and uses a one-parameter DSL sweep. Sweep
`repetitions=2` creates independent JVM replicas. `balancedTrialOrder=true` runs the first replica
in forward treatment order and its partner in reverse order, preventing duration and experiment
position from being confounded. CONTINUOUS windows are temporal samples, never counted as
independent replicas.

Coarse treatments were:

- XS: 0, 1, 5, 15, and 50 us;
- S, M, H, XH: 0, 5, 25, 50, and 250 us for the parameter belonging to that row;
- all other idle values held at their then-production references during each row;
- productivity participation held OFF throughout.

Only XS reached the coarse boundary while throughput was still improving. Its single local
refinement was 25, 50, 100, 250, and 500 us. The 50 us anchor was deliberately repeated with two
new JVMs. The other four rows did not require refinement because the control was already best or on
the best plateau and longer durations regressed.

## Throughput and stability response

Throughput is M executions/second. Across-fork CV is the sample CV of the two per-fork trajectory
means. Within CV is the larger of the two CVs across that fork's six ordered windows.

| Row | Idle (ns) | Fork 0 | Fork 1 | Mean | Across CV | Max within CV |
|---|---:|---:|---:|---:|---:|---:|
| XS coarse | 0 | 118.734 | 101.058 | 109.896 | 11.373% | 0.387% |
| XS coarse | 1,000 | 120.014 | 113.344 | 116.679 | 4.042% | 0.247% |
| XS coarse | 5,000 | 116.504 | 124.665 | 120.585 | 4.786% | 0.272% |
| XS coarse | 15,000 | 134.973 | 130.413 | 132.693 | 2.430% | 0.868% |
| XS coarse | 50,000 | 139.997 | 132.900 | 136.449 | 3.678% | 0.868% |
| XS refine | 25,000 | 137.263 | 134.860 | 136.062 | 1.249% | 1.212% |
| XS refine | 50,000 | 143.163 | 142.832 | 142.998 | 0.164% | 0.324% |
| XS refine | 100,000 | 137.936 | 135.731 | 136.834 | 1.139% | 0.337% |
| XS refine | 250,000 | 143.279 | 140.145 | 141.712 | 1.564% | 0.268% |
| XS refine | 500,000 | 138.388 | 141.088 | 139.738 | 1.366% | 0.394% |
| S | 0 | 94.878 | 93.665 | 94.272 | 0.910% | 0.304% |
| S | 5,000 | 94.585 | 88.598 | 91.591 | 4.622% | 1.202% |
| S | 25,000 | 94.249 | 94.042 | 94.145 | 0.155% | 0.226% |
| S | 50,000 | 93.064 | 91.988 | 92.526 | 0.822% | 0.147% |
| S | 250,000 | 89.979 | 80.850 | 85.415 | 7.557% | 1.122% |
| M | 0 | 75.041 | 73.582 | 74.311 | 1.388% | 0.192% |
| M | 5,000 | 73.430 | 72.160 | 72.795 | 1.234% | 0.386% |
| M | 25,000 | 72.690 | 71.645 | 72.167 | 1.024% | 0.227% |
| M | 50,000 | 67.517 | 67.400 | 67.459 | 0.123% | 0.127% |
| M | 250,000 | 68.298 | 69.061 | 68.680 | 0.786% | 0.360% |
| H | 0 | 55.332 | 55.778 | 55.555 | 0.568% | 0.181% |
| H | 5,000 | 55.159 | 55.043 | 55.101 | 0.149% | 0.445% |
| H | 25,000 | 54.638 | 54.435 | 54.537 | 0.263% | 0.075% |
| H | 50,000 | 53.964 | 53.153 | 53.558 | 1.071% | 0.194% |
| H | 250,000 | 52.193 | 51.645 | 51.919 | 0.746% | 0.357% |
| XH | 0 | 39.592 | 39.679 | 39.636 | 0.155% | 0.190% |
| XH | 5,000 | 38.800 | 39.175 | 38.987 | 0.680% | 0.110% |
| XH | 25,000 | 38.583 | 38.809 | 38.696 | 0.413% | 0.129% |
| XH | 50,000 | 38.170 | 38.275 | 38.222 | 0.194% | 0.169% |
| XH | 250,000 | 37.445 | 37.416 | 37.430 | 0.055% | 0.258% |

### Ordered CONTINUOUS trajectories

Each line is `idle-ns/fork: window0,...,window5` in M executions/second.

```text
XS coarse
0/0: 118.887,118.635,118.811,118.674,118.584,118.816
0/1: 100.448,101.381,101.101,101.193,100.751,101.476
1000/0: 120.022,120.142,120.001,119.955,119.938,120.027
1000/1: 113.596,113.156,113.584,113.596,112.974,113.160
5000/0: 115.865,116.699,116.550,116.626,116.662,116.619
5000/1: 124.186,124.935,124.558,124.693,124.658,124.962
15000/0: 137.345,134.588,134.683,134.528,134.443,134.249
15000/1: 129.254,131.141,130.350,130.600,130.633,130.501
50000/0: 139.795,139.398,138.788,139.046,141.130,141.823
50000/1: 133.442,133.194,132.914,132.634,132.482,132.731

XS refinement
25000/0: 140.639,136.460,136.936,136.502,136.517,136.522
25000/1: 135.275,135.030,134.760,134.871,134.466,134.756
50000/0: 143.725,143.532,143.076,142.621,143.126,142.899
50000/1: 143.512,143.319,142.622,142.477,142.626,142.434
100000/0: 138.301,138.248,138.022,137.736,137.610,137.702
100000/1: 136.518,135.846,135.877,135.513,135.324,135.309
250000/0: 143.866,143.335,143.202,142.662,143.309,143.302
250000/1: 140.485,140.489,140.255,139.862,139.723,140.058
500000/0: 138.098,138.636,138.080,138.221,138.701,138.595
500000/1: 142.155,140.630,140.919,141.055,141.091,140.676

S coarse
0/0: 95.397,95.019,94.795,94.724,94.727,94.607
0/1: 93.679,93.770,93.670,93.643,93.662,93.568
5000/0: 96.903,94.202,94.140,94.060,94.149,94.058
5000/1: 88.651,88.576,88.666,88.576,88.599,88.519
25000/0: 94.360,94.218,94.288,94.232,94.328,94.066
25000/1: 93.616,94.169,94.100,94.063,94.132,94.169
50000/0: 93.214,93.219,93.114,92.896,92.978,92.965
50000/1: 92.001,92.135,91.974,91.971,91.917,91.931
250000/0: 87.923,90.432,90.467,90.282,90.450,90.319
250000/1: 80.914,80.863,80.656,80.569,81.187,80.914

M coarse
0/0: 74.749,75.102,75.127,75.074,75.098,75.096
0/1: 73.568,73.591,73.599,73.607,73.609,73.518
5000/0: 72.863,73.443,73.566,73.595,73.580,73.534
5000/1: 72.345,72.325,72.085,72.078,72.068,72.060
25000/0: 72.362,72.692,72.807,72.752,72.749,72.778
25000/1: 71.723,71.688,71.600,71.583,71.659,71.619
50000/0: 67.472,67.535,67.400,67.644,67.572,67.479
50000/1: 67.465,67.413,67.398,67.383,67.401,67.341
250000/0: 68.734,68.335,68.195,68.332,68.191,68.004
250000/1: 69.185,69.079,69.035,69.083,69.018,68.968

H coarse
0/0: 55.425,55.288,55.331,55.337,55.294,55.317
0/1: 55.880,55.909,55.802,55.680,55.724,55.675
5000/0: 54.663,55.206,55.256,55.297,55.293,55.241
5000/1: 54.908,55.033,55.083,55.100,55.068,55.068
25000/0: 54.692,54.636,54.676,54.630,54.615,54.580
25000/1: 54.444,54.447,54.431,54.419,54.439,54.428
50000/0: 53.780,54.050,53.985,53.997,54.014,53.959
50000/1: 53.346,53.178,53.059,53.112,53.138,53.086
250000/0: 52.530,52.266,52.150,52.092,52.124,51.998
250000/1: 51.856,51.760,51.641,51.558,51.568,51.487

XH coarse
0/0: 39.459,39.582,39.673,39.644,39.621,39.573
0/1: 39.537,39.729,39.701,39.706,39.716,39.684
5000/0: 38.751,38.845,38.853,38.812,38.776,38.764
5000/1: 39.149,39.194,39.205,39.184,39.171,39.147
25000/0: 38.532,38.630,38.612,38.580,38.574,38.567
25000/1: 38.709,38.832,38.834,38.842,38.817,38.821
50000/0: 38.190,38.192,38.163,38.173,38.161,38.141
50000/1: 38.169,38.224,38.312,38.297,38.338,38.310
250000/0: 37.544,37.537,37.408,37.375,37.374,37.433
250000/1: 37.578,37.454,37.428,37.379,37.361,37.296
```

## Contention, acquisition, and ordinary-idle response

The table averages the 12 trajectory windows for each treatment. Attempts are successful plus
failed acquisitions per window. Idle count is the mean ordinary idle-decision sample count exported
per fork. All raw successful/failed counts and window identities remain in `trajectory_windows.tsv`;
all ordinary selection counts remain in `raw_observations.tsv`. Ordinary parked time was not already
exported cheaply, so no hot-path diagnostic was added.

| Row | Idle ns | Contention centroid | Attempts/window (M) | Success share | Ordinary idle fraction | Mean ordinary idle count |
|---|---:|---:|---:|---:|---:|---:|
| XS coarse | 0 | 0.9222 | 81.744 | 0.0004 | 0.9783 | 4,142,725 |
| XS coarse | 1,000 | 1.0000 | 62.714 | 0.0005 | 1.0000 | 2,913,235 |
| XS coarse | 5,000 | 1.0000 | 37.563 | 0.0008 | 1.0000 | 1,772,135 |
| XS coarse | 15,000 | 1.0000 | 20.685 | 0.0049 | 0.9825 | 978,441 |
| XS coarse | 50,000 | 0.9672 | 10.571 | 0.0380 | 0.9447 | 482,115 |
| XS refine | 25,000 | 0.9996 | 16.595 | 0.0169 | 0.9765 | 743,839 |
| XS refine | 50,000 | 0.9440 | 10.294 | 0.0411 | 0.9223 | 483,658 |
| XS refine | 100,000 | 0.7081 | 7.053 | 0.0574 | 0.6958 | 398,716 |
| XS refine | 250,000 | 0.3193 | 4.779 | 0.0747 | 0.3183 | 404,159 |
| XS refine | 500,000 | 0.1800 | 3.578 | 0.0839 | 0.1830 | 384,871 |
| S | 0 | 0.8724 | 6.707 | 0.0393 | 0.8537 | 362,884 |
| S | 5,000 | 0.7548 | 5.771 | 0.0478 | 0.7352 | 340,331 |
| S | 25,000 | 0.5123 | 5.530 | 0.0587 | 0.5004 | 398,607 |
| S | 50,000 | 0.2397 | 5.931 | 0.0737 | 0.2393 | 560,841 |
| S | 250,000 | 0.0887 | 5.372 | 0.0900 | 0.0894 | 653,896 |
| M | 0 | 0.7118 | 4.769 | 0.0460 | 0.7049 | 295,809 |
| M | 5,000 | 0.4790 | 4.414 | 0.0586 | 0.4822 | 331,094 |
| M | 25,000 | 0.2738 | 4.663 | 0.0703 | 0.2869 | 428,996 |
| M | 50,000 | 0.2790 | 4.378 | 0.0704 | 0.2802 | 400,475 |
| M | 250,000 | 0.0482 | 4.896 | 0.0983 | 0.0511 | 645,748 |
| H | 0 | 0.5354 | 3.773 | 0.0519 | 0.5377 | 274,999 |
| H | 5,000 | 0.3507 | 4.048 | 0.0623 | 0.3509 | 354,492 |
| H | 25,000 | 0.1746 | 4.955 | 0.0754 | 0.1872 | 534,006 |
| H | 50,000 | 0.0954 | 5.588 | 0.0858 | 0.1013 | 672,565 |
| H | 250,000 | 0.0293 | 5.696 | 0.1045 | 0.0314 | 811,063 |
| XH | 0 | 0.4145 | 2.970 | 0.0566 | 0.4127 | 243,309 |
| XH | 5,000 | 0.2493 | 3.366 | 0.0686 | 0.2610 | 332,692 |
| XH | 25,000 | 0.1226 | 4.215 | 0.0815 | 0.1169 | 492,564 |
| XH | 50,000 | 0.0791 | 4.480 | 0.0888 | 0.0839 | 560,100 |
| XH | 250,000 | 0.0273 | 4.877 | 0.1052 | 0.0292 | 696,440 |

This response demonstrates why minimum contention or maximum acquisition-success share was not the
selection rule. In M/H/XH, long parks greatly reduced the contention centroid and raised success
share while throughput fell. Productive-handle ratio remained 0.4783 for every main treatment.

The dominant joint policy state remained stable inside most trajectories. For XS, 100-250 us also
crossed a pronounced occupancy boundary: contention/body state moved from the high-contention XS
region toward state 0. That boundary did not produce a uniquely better plateau and is another reason
to choose the shorter 50 us point rather than 250 us.

## Plateau selections

- **XS: 50 us.** Coarse performance improved through the 50 us boundary. Refinement showed a broad
  50-500 us region rather than a unique optimum. The fresh 50 us pair had 0.164% across-fork CV,
  and the repeated coarse/refinement anchor totals four independent JVMs. 50 us is the shortest,
  best-replicated high-throughput point and avoids the controller-state boundary above 100 us.
- **S: 0 ns.** 0 and 25 us were statistically close, but 25 us achieved neutrality only while the
  marginal S selection share fell to roughly 37%. There is no evidence that timed S parking helps;
  50 and 250 us regress materially.
- **M: 0 ns.** The surface declines from the control; even 5 us loses about 2.0% and longer parks
  lose more.
- **H: 0 ns.** 0 and 5 us are close, so the simpler no-timed-park control is central to the best
  plateau. Longer parks monotonically regress.
- **XH: 0 ns.** The control is best with 0.155% across-fork CV; every positive duration regresses.

Additional refinement would be smaller than observed fork variation for S/H and cannot reverse the
clear direction for M/XH. It was therefore not justified.

## Neighboring-fixture validation

The selected candidate `(50,000, 0, 0, 0, 0)` was compared with the all-zero control at 8 and 16
parallel sources, neighboring the 11-source calibration fixture. Work remained the true XS no-op,
the same 23 workers remained active, and source count plus `xsPark` were the only two axes.

| Sources | XS idle ns | Fork 0 | Fork 1 | Cell mean | Across CV | Max within CV |
|---:|---:|---:|---:|---:|---:|---:|
| 8 | 0 | 71.802 | 81.829 | 76.815 | 9.230% | 0.417% |
| 8 | 50,000 | 100.790 | 103.856 | 102.323 | 2.119% | 0.379% |
| 16 | 0 | 214.917 | 217.749 | 216.333 | 0.926% | 1.985% |
| 16 | 50,000 | 229.793 | 221.994 | 225.893 | 2.441% | 1.084% |

Same-balanced-position deltas were +40.373% and +26.919% at 8 sources (cell mean +33.207%), and
+6.921% and +1.950% at 16 sources (cell mean +4.419%). The 8-source zero control had high
across-fork dispersion, but both independent comparisons favored 50 us. At 8 sources, attempts fell
about 91.43% and success share rose from 0.00013 to 0.00250. At 16 sources, contention centroid fell
from 0.6339 to 0.2417, attempts fell about 62.1%, and success share rose from 0.0219 to 0.0669.
There was no neighboring-fixture failure or sign reversal.

```text
sources/xs-idle/fork: window0,...,window5 (M executions/second)
8/0/0: 72.193,71.543,71.559,71.772,72.153,71.589
8/0/1: 81.910,81.894,81.483,81.961,81.785,81.939
8/50000/0: 101.226,100.662,100.632,101.053,100.698,100.470
8/50000/1: 104.558,103.682,103.388,103.807,103.729,103.971
16/0/0: 212.205,214.049,209.121,215.730,221.648,216.752
16/0/1: 211.928,219.289,216.892,220.773,218.617,218.994
16/50000/0: 226.660,227.124,229.357,232.663,231.524,231.427
16/50000/1: 221.543,221.642,223.746,223.205,221.195,220.633
```

## Artifact audit and completion gate

The prepared presets and comparison definitions are numbered 03-11 under
`benchmarks/src/main/presets`. Expanded configurations, raw benchmark logs, every fork, every
CONTINUOUS window, observer exports, and comparisons are retained under matching `experiments/`
directories.

Direct SHA-256 recalculation matched all retained sidecars: 459/459 files in each coarse/refinement
batch, 108/108 in S qualification, and 369/369 in neighbor validation. The sidecars contain bare
digests rather than `sha256sum -c` filename records, so verification compared each recalculated
digest directly. No runtime errors, unfed windows, missing forks, or RESET measurements occurred.

All ten completion conditions are satisfied: productivity stayed disabled; five corrected
CONTINUOUS coarse surfaces were completed; only XS needed one local refinement; independent JVM and
temporal stability were checked; cross-body simplification produced the XS versus S-XH split; the
neighboring-source check passed; and no body, contention, or productivity threshold moved.

## Minimum justified Phase 2 question

With ordinary idle defaults fixed at `(50,000, 0, 0, 0, 0)`, the minimum next question is:

> At what measured body cost is it physically appropriate to exclude excess upstream participants?

That phase should calibrate only the productivity body threshold under corrected CONTINUOUS
operation. Productivity participation count and park duration remain outside the Phase 1 result.
