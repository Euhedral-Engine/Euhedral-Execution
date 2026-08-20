# Phase 12 Experiment Design: Multistability and Contention Staleness

This document records the pre-run evidence audit and the minimum diagnostic experiment. It is not
the Phase 12 findings report.

## Existing fork evidence exhausted

The primary Phase 11 candidate is the eight-E-core, four-parallel-source,
`productiveHandleRatio = 0.500`, M-body fixture. Experiment 24 retained three independent forks per
policy, while Experiment 23 retained the original single-fork surface point.

`STAGED` is the minimum-control policy and reproduces the split most cleanly:

| Evidence | Fork | Throughput | Dominant state | Contention centroid | Dominant probability | Self-transition |
|----------|------|-----------:|---------------:|--------------------:|---------------------:|----------------:|
| Experiment 23 | 1 | 17.074M | 7 | 1.484 | 0.443 | 0.846 |
| Experiment 24 | 1 | 11.522M | 22 | 3.839 | 0.922 | 0.989 |
| Experiment 24 | 2 | 16.786M | 7 | 1.412 | 0.468 | 0.852 |
| Experiment 24 | 3 | 16.876M | 7 | 1.456 | 0.428 | 0.836 |

Experiment 24 `SKIP_THEN_STAGED` also reached both states, but adds a transitory execution action
without improving identification of the underlying feedback loop. No Phase 25 run used this
fixture; Phase 25 reused the Experiment 24 evidence.

The retained telemetry cannot evaluate contention staleness. It contains measured contention,
decision counts, occupancy, transitions, vector fields, productive-handle ratio, and fork
throughput, but it does not contain acquisition-observation sequence numbers, last raw contention,
observation age, acquisition counts, selected idle duration, or ordered idle streaks. Aggregate
idle-decision frequency is not a substitute: the Experiment 24 high-state `STAGED` fork recorded an
idle decision on 84.0% of observed cycles, versus 87.3% and 87.4% for the low-state forks.

## Minimum missing diagnostics

The calibration-only `observeContentionStaleness` toggle enables owner-local acquisition counters
and one bounded per-cycle observer stream. It exports `contention_staleness.tsv` per fork with:

```text
measured contention EWMA
last raw contention observation
contention observation count
last contention observation timestamp
cycles and nanoseconds since the last observation
consecutive idle decisions and selected duration
successful, failed, and total acquisition attempts
selected execution path
local cache count
productive handles, registered workers, and worker rank
```

The stream retains the first and most recent `rawSampleLimit` samples per core and measurement iteration.
Acquisition timestamps and counters are disabled outside explicitly configured diagnostic runs. The
authoritative measured contention signal and all controller decisions remain unchanged.

## Minimum experiment and comparison

[`26-contention-staleness-diagnostic.json`](../experiments/26-contention-staleness-diagnostic.json)
contains one physical fixture and one fixed policy. It preserves the Experiment 24 CPU set,
four productive handles, ratio 0.500, work units 144, deterministic work, source topology, idle
policy, decision weights, JVM arguments, warmups, and measurement iterations. Eight independent
forks are used: under the observed one-high/two-low split, eight is the smallest fork count with
less than a 5% chance of seeing only one attractor if the historical attractor probabilities hold.

[`26-contention-staleness-fork-matrix.json`](../comparisons/26-contention-staleness-fork-matrix.json)
performs the complete fork-scope self-cross matrix for the one completed run. Diagonal and mirrored
pairs are controls; unique off-diagonal pairs provide direct high-attractor versus low-attractor
occupancy, transition, state-comparability, and throughput comparisons without asserting a false
fork pairing.

No decision-only aging or forced-refresh preset is justified yet. Create and run neither unless the
diagnostic result shows that high-attractor forks have materially longer idle streaks and older or
sparser contention observations than low-attractor forks. If the diagnostic run does not reproduce
both attractors, Phase 12 is Outcome D for this fixture and no intervention follows from this run.
