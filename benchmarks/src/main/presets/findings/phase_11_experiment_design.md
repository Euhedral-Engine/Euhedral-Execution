# Phase 11 Experiment Design: Policy-Conditioned State Dynamics

This document records the pre-run evidence audit and the minimum targeted matrix for Phase 11. It
is not the Phase 11 findings report.

## Phase 10 evidence exhausted

The authoritative Experiment 24 throughput artifacts correctly retain three independent JMH fork
means. The existing telemetry comparison artifacts, however, loaded the first retained fork only.
All retained fork-level `occupancy.tsv`, `transitions.tsv`, `vector_fields.tsv`, `statistics.tsv`,
and JMH iteration scores were therefore inspected directly.

The four representative cases resolve as follows before any new run:

| Role | Fixture | Existing fork evidence | Decision |
|------|---------|------------------------|----------|
| Clean reference | 8 E-cores, ratio 0.125, XH body | STAGED stayed near contention centroid 3.68 in all forks. Two SKIP forks stayed near 3.59, but one settled near 1.04. Throughput was stable despite that state divergence. | Rerun; same-state behavior is not consistent across all forks. |
| Shifted, STAGED appeared better | 4 P-cores, ratio 0.25, XS body | Cross-policy fork TV distances span 0.110-0.795 and contention displacement changes sign. | Rerun; the apparent shift is not repeatable yet. |
| Shifted, SKIP previously appeared better | 4 P-cores, ratio 0.25, S body | Cross-policy fork TV distances span 0.045-0.789 and contention displacement changes sign. | Rerun; the original advantage and state movement both remain unresolved. |
| High variance / inconclusive | 8 E-cores, ratio 0.50, M body | Cross-policy fork TV distances span 0.061-0.895, contention displacement spans -2.738 to +2.234, and throughput CVs are 20.4% / 17.7%. | Reuse; this already supplies the required inconsistent, topology-sensitive control. |

The E-core high-variance control is retained specifically because it is the Phase 8 conflict case
and tests topology sensitivity. No other E-core high-variance point is added.

Transition and vector evidence agrees with these selections. The P-core XS forks alternate among
dominant states 10, 15, and 20, with dominant self-transition rates from 0.732 to 0.992 and no
consistent contention-vector direction. The P-core S forks alternate among states 1, 11, and 12,
with self-transition rates from 0.719 to 0.964. The E-core clean-reference STAGED forks remain in
state 24 with self-transition near 0.978; two SKIP forks remain in state 24 near 0.968 while the
third settles in state 4 with self-transition 1.000. The high-variance E-core M control alternates
between states 7 and 22 under both policies. Experiment 23 remains useful only as the original
one-fork surface and selection evidence; it adds no independent replicate beyond Experiment 24.

## Minimum new matrix

[`25-policy-conditioned-state-dynamics.json`](../experiments/25-policy-conditioned-state-dynamics.json)
contains three physical fixtures and two policies per fixture:

```text
P-core / ratio 0.25 / XS:  STAGED, SKIP_THEN_STAGED
P-core / ratio 0.25 / S:   STAGED, SKIP_THEN_STAGED
E-core / ratio 0.125 / XH: STAGED, SKIP_THEN_STAGED
```

Each trial retains three independent JMH forks, five measurement iterations per fork, exact
per-fork telemetry, the Phase 10 JVM configuration, fixed CPU sets, fixed handle geometry,
`randomizeWork = false`, and the unchanged idle and candidate contention thresholds. This is six
new policy points and eighteen JMH forks. No DIRECT or SKIP_THEN_DIRECT point is rerun.

## State-comparability analysis criteria

The comparison layer now exports explicit component metrics in `state_comparability.tsv`; it does
not create a synthetic similarity score. The provisional analysis tolerances are derived from the
Experiment 24 aggregate occupancy-TV clusters:

```text
low cluster:         0.074-0.138
intermediate case:   0.370
large-shift cluster: 0.669-0.875
```

The analysis therefore uses 0.25 as the comparable TV ceiling and 0.60 as the divergent TV floor,
with explicit productive-ratio and centroid guards. Values between those regions are
`STATE_SHIFTED`. These are analysis criteria only and must not become scheduler policy constants.

Fork-scope comparison expands each parent run into its retained JMH forks and evaluates all 3 x 3
cross-policy fork pairs. This avoids assigning a false matching relationship between independent
forks and makes direction consistency directly inspectable. Whole-run comparison remains
authoritative for throughput; state classification is emitted only when each side represents one
fork so first-fork telemetry cannot be mistaken for pooled state evidence.
