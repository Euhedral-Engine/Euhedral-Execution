# Phase 11 Findings: Policy-Conditioned State Dynamics

## Evidence and controls

Phase 11 re-analyzed every retained fork from Experiment 24 before adding the minimum new matrix in
Experiment 25. The new matrix contains only the three unresolved representative fixtures, with
`STAGED` and `SKIP_THEN_STAGED` as the sole policy difference:

```text
P-core / productive ratio 0.250 / XS body
P-core / productive ratio 0.250 / S body
E-core / productive ratio 0.125 / XH body
```

Each of the six new trials used three independent JMH forks, two warmup iterations, five measurement
iterations, fixed CPU sets, one parallel source, `randomizeWork = false`, and the unchanged Phase 10
JVM, idle, body-weight, and contention-threshold configuration. The E-core ratio-0.500/M
high-variance case was reused from Experiment 24 rather than rerun.

All 18 new forks completed with 15 measurement scores per trial. Every required per-fork telemetry
artifact is present. All 702 digest sidecars under Experiment 25 match their artifacts, and all 39
comparison pairs are `COMPATIBLE`. The digest sidecars contain raw hexadecimal digests rather than
the filename-bearing format accepted by `sha256sum -c`.

The fork comparison evaluates all 3x3 cross-policy pairs. This does not assert a false pairing
between independent forks. Whole-run comparison remains authoritative for throughput.

## Authoritative fork-aware throughput

Throughput is in millions of executions per second. CV is calculated across the three independent
fork means. Positive delta favors `SKIP_THEN_STAGED`.

| Evidence      | Fixture             | STAGED | SKIP_THEN_STAGED |  Delta |      Fork CVs | Harness outcome |
|---------------|---------------------|-------:|-----------------:|-------:|--------------:|-----------------|
| Experiment 25 | P-core / 0.250 / XS | 23.882 |           23.437 | -1.87% |  14.3% / 3.4% | inconclusive    |
| Experiment 25 | P-core / 0.250 / S  | 20.169 |           19.637 | -2.64% |   2.0% / 6.0% | inconclusive    |
| Experiment 25 | E-core / 0.125 / XH | 12.229 |           11.154 | -8.79% |   0.8% / 9.7% | inconclusive    |
| Experiment 24 | E-core / 0.125 / XH | 12.270 |           12.015 | -2.07% |   0.6% / 0.5% | `STAGED`        |
| Experiment 24 | E-core / 0.500 / M  | 15.061 |           14.440 | -4.12% | 20.4% / 17.7% | inconclusive    |

The Experiment 25 E-core/XH aggregate is conservatively inconclusive because one
`SKIP_THEN_STAGED` fork fell to 9.905 million ops/s and widened its variance. The direction still
replicated: the three `STAGED` fork means were 12.242, 12.121, and 12.324, while the three skip fork
means were 11.768, 11.791, and 9.905. Experiment 24 independently produced the same complete
ordering, with all three `STAGED` fork means above all three skip fork means. Across the two runs,
the smallest `STAGED` fork mean is above the largest skip fork mean. Phase 11 therefore treats this
as a replicated direction while retaining the Experiment 25 harness verdict as reported.

The P-core fork means do not establish a winner:

| Fixture             | STAGED fork means        | SKIP_THEN_STAGED fork means |
|---------------------|--------------------------|-----------------------------|
| P-core / 0.250 / XS | 22.896 / 21.070 / 27.682 | 23.310 / 22.717 / 24.283    |
| P-core / 0.250 / S  | 20.147 / 20.576 / 19.783 | 20.775 / 19.710 / 18.425    |

## Fork-level state comparability

The state-comparability criteria are analysis tolerances only. They are not scheduler thresholds.
`productiveHandleRatio` remained exact and unchanged between policies at every fixture.

| Fixture                           | Ratio | Comparable / shifted / divergent pairs | Occupancy TV | Contention centroid delta | Body centroid delta | Transition TV |
|-----------------------------------|------:|---------------------------------------:|-------------:|--------------------------:|--------------------:|--------------:|
| E-core / 0.125 / XH               | 0.125 |                              9 / 0 / 0 |  0.065-0.177 |          -0.100 to +0.221 |    -0.060 to +0.001 |   0.028-0.093 |
| P-core / 0.250 / XS               | 0.250 |                              3 / 6 / 0 |  0.049-0.597 |          -0.213 to +0.314 |    -0.427 to +0.008 |   0.060-0.452 |
| P-core / 0.250 / S                | 0.250 |                              4 / 2 / 3 |  0.162-0.689 |          -0.618 to +0.833 |    -0.765 to -0.013 |   0.175-0.721 |
| E-core / 0.500 / M, Experiment 24 | 0.500 |                              5 / 0 / 4 |  0.061-0.895 |          -2.738 to +2.234 |    -0.079 to +0.042 |   0.084-0.460 |

### Clean same-state comparison

All E-core/XH forks were dominated by state 24. `STAGED` contention centroids were 3.685, 3.687, and
3.678; skip centroids were 3.586, 3.596, and 3.899. Dominant-state self-transition rates were
0.978-0.979 for `STAGED` and 0.969-0.976 for skip. Both policies' dominant-state vectors pointed
toward slightly lower contention with negligible body movement; their magnitudes were 0.025-0.026
and 0.029-0.038 respectively.

This rerun does not reproduce Experiment 24's single skip fork that settled in state 4. Instead, all
nine cross-fork comparisons are state-comparable while the throughput direction remains the same as
Experiment 24. The evidence is therefore a same-state action-cost result, not evidence that skip
moved the controller to a beneficial or harmful attractor.

### Shifted P-core comparisons

For XS body, all skip forks settled near contention centroid 2.82-2.93 with dominant state 15 and
self-transition 0.830-0.833. The `STAGED` forks did not supply one repeatable starting population:
their centroids were 2.616, 2.613, and 3.030; dominant states were 20, 15, and 20; and one fork had
a body centroid of 0.432 while the other two remained near zero. Consequently, the apparent skip
displacement changes with the baseline fork. The dominant vectors point toward lower contention
under both policies, so they do not establish a policy-specific transition direction.

For S body, two skip forks occupied state 11 near contention centroid 1.92, while the third occupied
state 21 near 3.15. Two `STAGED` forks occupied state 11 near 2.32, while the third occupied state
17 with body centroid 1.687. Dominant self-transition rates span 0.738-0.816 for `STAGED` and
0.736-0.974 for skip. Although every cross-pair has a lower skip body centroid, that direction is
driven mainly by the one high-body `STAGED` fork and does not accompany a repeatable contention
movement or throughput effect.

### Reused high-variance control

The Experiment 24 E-core ratio-0.500/M control remains bimodal. Both policies reach states 7 and 22:
`STAGED` has one state-22 fork and two state-7 forks, while skip has two state-7 forks and one
state-22 fork. The same-policy recurrence of both attractors explains why cross-policy comparisons
alternate between comparable and divergent. It is topology-sensitive multistability, not a
repeatable skip-induced transition.

## Outcome classification

| Targeted comparison | Classification                                                      | Basis                                                                                                                                                                                                                                                         |
|---------------------|---------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| E-core / 0.125 / XH | **Outcome B - same-state performance difference favoring `STAGED`** | All Experiment 25 state pairs are comparable, and the fork ordering repeats the statistically decisive Experiment 24 direction. The action-local effect is replicated even though Experiment 25 alone retains an inconclusive variance-aware aggregate label. |
| P-core / 0.250 / XS | **Outcome A - no reliable policy difference**                       | Throughput is inconclusive; baseline state and throughput vary by fork; no common displacement from `STAGED` to skip repeats.                                                                                                                                 |
| P-core / 0.250 / S  | **Outcome A - no reliable policy difference**                       | Throughput is inconclusive; both policies contain fork-specific state changes; the shift direction is not repeatable.                                                                                                                                         |
| E-core / 0.500 / M  | **Outcome A - no reliable policy difference**                       | Throughput is inconclusive and both policies enter the same two attractors with high fork variance.                                                                                                                                                           |

No targeted comparison qualifies as Outcome C or D. `SKIP_THEN_STAGED` has no established repeatable
beneficial or harmful dynamic state effect. The stable evidence is static: within the same state-24
population, `STAGED` has lower action cost. The other observations are fork- and topology-sensitive
attractor selection, not a demonstrated transition-control benefit.

## Revised Phase 8 interpretation

Phase 8 remains the experiment that exposed a region where skip could materially change throughput.
Its apparent 80%-90% contention skip band is not a static policy boundary. Phase 10 showed that
contention and productive ratio alone did not reproduce the result and that policy, topology, and
fork could change the observed state population. Phase 11 now shows that the clean replicated effect
is same-state and favors `STAGED`, while the strongly shifted cases do not produce a repeatable
skip-induced movement or winner. The original skip advantage is therefore best classified as
topology-sensitive instability rather than static action superiority or established dynamic
realignment.

No productivity axis, composite-pressure formula, or finalized contention threshold follows from
this result. `productiveHandleRatio` remains required telemetry, and the experimental contention
thresholds remain `[650000, 800000, 900000, 970000]` without promotion.

## Minimum justified next research question

Within the stable E-core ratio-0.125/XH state-24 regime, what action-local mechanism makes `STAGED`
consistently faster than `SKIP_THEN_STAGED`?
