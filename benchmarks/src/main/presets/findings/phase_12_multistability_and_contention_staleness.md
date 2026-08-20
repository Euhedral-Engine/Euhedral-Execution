# Phase 12 Findings: Multistability and Contention Staleness

## Evidence and controls

Phase 12 reused the Experiment 23/24 eight-E-core, four-parallel-source,
`productiveHandleRatio = 0.500`, M-body fixture. `STAGED` was held fixed because it reproduced the
historical states 7 and 22 without adding the transitory skip action. The Experiment 26 replication
kept the Phase 11 CPU set, source topology, productive handles, work units, deterministic work,
idle policy, decision weights, JVM arguments, warmups, and measurement iterations unchanged. It
added only bounded observation of acquisition freshness and idle streaks.

All eight forks completed five measurement iterations. Each fork has all seven expected telemetry
tables and digest sidecars. All 56 fork artifact digests and all nine comparison artifact digests
match. The complete 8x8 fork matrix contains 64 `COMPATIBLE` comparisons. No benchmark error was
reported.

The retained Experiment 23/24 evidence could not answer the freshness question. It contained the
contention EWMA, decisions, occupancy, transitions, productive-handle ratio, and throughput, but no
ordered acquisition-observation age or idle streak. Experiment 24's aggregate idle fraction was
also non-identifying: 84.0% in its state-22 `STAGED` fork versus 87.3% and 87.4% in its state-7
forks. Experiment 26 therefore supplied the minimum missing evidence.

## Fork-level results

Throughput is in millions of executions per second. The iteration sequence reports the dominant
execution state in each of the five measurement iterations. Fork occupancy is cycle-weighted, so a
state-22 iteration with many failed-acquisition cycles can dominate the aggregate occupancy even
when the other four iterations reached state 7.

| Fork | Iteration states | Fork assignment | Throughput mean | Iteration range | Dominant state | Dominant probability | Contention centroid | Body centroid | Dominant self-transition |
|-----:|------------------|-----------------|----------------:|----------------:|---------------:|---------------------:|--------------------:|--------------:|-------------------------:|
| 1 | 7 / 7 / 7 / 7 / 7 | low | 16.229 | 16.084-16.350 | 7 | 0.406 | 1.475 | 2.131 | 0.826 |
| 2 | 7 / 7 / 7 / 7 / 7 | low | 16.848 | 16.618-17.065 | 7 | 0.459 | 1.328 | 2.075 | 0.844 |
| 3 | 7 / 7 / 7 / 7 / 7 | low | 16.940 | 16.863-17.028 | 7 | 0.450 | 1.481 | 2.023 | 0.848 |
| 4 | 7 / 7 / 7 / 7 / 7 | low, slow | 11.709 | 11.688-11.723 | 7 | 0.361 | 1.733 | 2.034 | 0.838 |
| 5 | 7 / 22 / 7 / 7 / 7 | mixed, cycle-dominant high | 15.758 | 11.392-17.017 | 22 | 0.913 | 3.784 | 2.005 | 0.996 |
| 6 | 7 / 7 / 7 / 22 / 7 | mixed, cycle-dominant high | 16.258 | 11.441-17.532 | 22 | 0.904 | 3.770 | 2.002 | 0.996 |
| 7 | 7 / 7 / 7 / 7 / 7 | low | 16.391 | 16.346-16.478 | 7 | 0.413 | 1.447 | 2.107 | 0.829 |
| 8 | 7 / 7 / 7 / 7 / 7 | low | 15.933 | 15.816-16.100 | 7 | 0.436 | 1.339 | 2.124 | 0.835 |

The fixture therefore reproduced both attractors, although state 22 appeared in only two of 40
measurement iterations and no fork remained there for all five iterations. The eight fork means
have a 10.7% sample CV. Fork 4 also shows that low throughput is not a sufficient state-22
classifier: it remained in state 7 in every iteration while matching the state-22 throughput band.

The fork matrix classifies forks 5 and 6 as state-comparable to each other with occupancy TV 0.013.
Each is state-divergent from all six state-7 forks, with occupancy TV 0.839-0.887 and contention
centroid separation 2.037-2.456 bands. Body-centroid separation is only 0.018-0.130 bands. The
reproduced split is therefore almost entirely a contention-state split, not a body-state split.

## Contention freshness and idle streaks

The diagnostic records the existing 0-1 acquisition-failure EWMA as `measuredContention`. The
0-4 contention centroid in the occupancy tables is a controller-band centroid, not another raw
contention measurement.

| Iteration population | Iterations | Observation-age p95, cycles | Observation-age p99, cycles | Maximum age, cycles | Observation-age p95, time | Adjacent samples with a new observation | Idle-streak p95 | Maximum idle streak |
|----------------------|-----------:|-----------------------------:|-----------------------------:|--------------------:|---------------------------:|----------------------------------------:|----------------:|--------------------:|
| State 7 | 38 | 0 | 1 | 6 | 11.1 us | 98.94% | 4,159 | 5,244 |
| State 22 | 2 | 0 | 0 | 0 | 10.1 us | 100.00% | 191,452 | 194,729 |

State 22 has much longer idle-decision streaks, but it does not have older or sparser contention
observations. Every adjacent retained steady-state sample in both state-22 iterations has a new
acquisition observation, and every sample is zero cycles old. Its p95 observation time is slightly
shorter than the state-7 population's. The long idle streak is consequently not evidence that
idling stopped measurement.

Worker rank zero never selects the policy idle, while ranks one through seven normally select the
configured 5 us park in state 22. Nevertheless, rank zero and every idling rank independently report
a fresh EWMA and raw contention near 1.0 in both state-22 iterations. The high state therefore does
not depend on an idling worker retaining an old high value.

The acquisition counters identify the different live regimes:

| Iteration population | Median attempts/core-cycle | Median successes/core-cycle | Median failures/core-cycle | Median successful share |
|----------------------|---------------------------:|----------------------------:|-------------------------:|------------------------:|
| State 7 | 6.271 | 0.844 | 5.430 | 13.43% |
| State 22 | 4.014 | 0.013 | 4.002 | 0.31% |

Thus state 22 is sustained by real, newly measured lock-acquisition failures. The state-22
iteration self-transition rates are 0.9990 and 0.9988 because the near-total collision pattern is
itself stable. In state 7, productive acquisitions make cycles longer and reset the EWMA away from
1.0 more often; the shorter cycle-count streak does not indicate a shorter wall-clock sequence of
policy idling.

## Intervention gate

Decision-only aging was not implemented or run. Phase 12 permits it only when high-state runs show
older or sparser contention evidence. Here `dt` is effectively zero every cycle, so a correctly
gated staleness decay would be the identity and would not probe the observed mechanism.

Forced refresh was also not implemented or run. Its proposed purpose is to create an acquisition
opportunity after idling has suppressed observations. These workers already make acquisition
attempts and create a fresh observation every cycle, including the non-idling rank-zero worker.
Forcing another refresh would duplicate existing behavior rather than isolate causality.

No production contention rule, threshold, idle policy, execution policy, or freshness mechanism is
changed. Because neither intervention passed its evidence gate, there is no justified
before/after-intervention attractor or fork-variance comparison.

## Narrowed scheduler evidence

Across the two state-22 iterations, the fixed topology remains eight registered workers, four
productive handles, ratio 0.500, ranks zero through seven, body band 2, a 4096-frame batch cap, and
the `STAGED` execution path. The controller's measured input agrees with the last raw observation.
This rules out missing contention observations as the sustaining mechanism and provides no support
for a stale worker-rank, productive-handle, execution-path, or contention-EWMA value.

The remaining difference is lock/acquisition ordering. In state 22, each core visits roughly all
four source handles per cycle but almost never acquires one. In state 7, cores make more attempts
and acquire a handle on about 13% of them. The shared source handle holds its lock while a
synchronous request pushes frames, so different per-handle owner/hold-time and queue-order phases
can plausibly form a real source-lock convoy. Existing telemetry does not identify which core owns
which handle, how long it holds that handle, or the requested and produced frame count per hold.

The diagnostic head begins after the iteration reset has completed and workers have resumed. The
state-22 head samples are already near saturation, so this run establishes how the attractor is
maintained but does not expose the ownership sequence that forms it. Fork 4's consistently slow
state-7 behavior is a separate warning that another unobserved execution or host dynamic can affect
throughput without changing the dominant contention/body state.

## Outcome classification

Phase 12 is **Outcome C - staleness is not causal**.

The known fixture reproduced states 7 and 22, per-core freshness and idle streaks are observable,
and high/low populations were compared directly. State 22 has longer idle-decision streaks, but its
contention observations are newer and denser, not stale. Aging and forced refresh were therefore
not justified, and no experimental or production freshness behavior was adopted.

The minimum justified next research question is:

```text
Does a per-handle source-lock convoy, determined by owner core, lock hold time, synchronous request
size, and per-core handle iteration order, create the fresh state-22 contention attractor?
```

Any follow-on should retain this one fixture and fixed policy and add only per-handle acquisition
identity, owner, hold-time, requested-count, and produced-count diagnostics around attractor
formation. It should not resume threshold or decay tuning.
