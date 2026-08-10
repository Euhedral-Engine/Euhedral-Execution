# Offline robust policy-training architecture

Euhedral training is an offline policy-search system. It produces 28-weight vectors for the fixed
runtime action picker; DJL, corpus handling, prediction, and candidate search never enter
`euhedral-core`. Runtime pull-graph, routing, frame, topology, and memory contracts remain in
[ARCHITECTURE.md](ARCHITECTURE.md).

## Evidence and identity

The durable experiment is a panel:

```text
policy x source scenario x benchmark run/iteration
```

`PolicyVector` preserves the raw IEEE-754 bits of all 28 finite weights and derives a stable policy
ID. `SourceScenario` preserves environment, absolute source count, visible physical-core count, and
the reduced source/core ratio. Native observations additionally carry run, cohort, iteration,
commit, benchmark parameters, repetition, timing, and status identity.

The closed loop accepts either a strict schema-v1 bootstrap vector file or an explicit native
calibration/evidence state. Bootstrap vectors carry no measurements and must be benchmarked
natively. Current-layout names, alternating vector/measurement rows, old models, and old checkpoints
are not live input formats.

## Implemented flow

```text
strict bootstrap vectors
    -> native exact-scenario evidence
    -> fixed anchor calibration
    -> hierarchical Phase 1 merge
    -> scenario-conditioned model
    -> robust predicted scheduling and carry completion
    -> native evidence and post-merge
    -> checkpoint-backed package
```

Bootstrap may pause across environments. Each invocation runs only exact required scenarios that
match the active environment and visible core count. A complete checkpoint records evidence and the
pending schedule, so resume never regenerates already published scheduling decisions.

## Calibration and aggregation

Fixed anchors establish a stable scale within each scenario. A reference run is selected explicitly
or deterministically, and other runs estimate a weighted-median log throughput offset using shared
anchors. The merger reports overlap, scale, residual, and strong/weak/uncalibrated status; a
candidate cohort never defines its own comparison scale.

Aggregation is hierarchical:

1. repetitions become one median/IQR/failure summary per policy, run, and scenario;
2. run summaries become one median-of-runs policy/scenario row, with runs receiving equal weight;
3. scenario rows receive empirical percentile quality with midranks and then form a robust policy
   summary.

Raw observations and every scenario row remain available. The robust summary never replaces them.

## Complete and incomplete policy pools

Full required-scenario coverage is a gate for robust leadership. Eligible policies compare
lexicographically by minimum scenario quality, type-7 P25 quality, geometric mean quality, lower
cross-scenario MAD, and finally lower measurement instability and timeout rate.

Incomplete policies live in a separate carry-forward queue. The scenario-conditioned ordinal model
predicts a complete quality curve for each candidate and reports uncertainty/disagreement.
Scheduling reserves explicit budgets for new exploration, missing-scenario completion, robust leader
revalidation, and disagreement audits. Fixed anchors are reserved before those categories.

## Lifecycle and publication

`ClosedLoopRunner` owns the stage machine. Immutable records cross stage boundaries; mutable
builders, parser buffers, model tensors, and filesystem staging state remain single-owner. Native
benchmark counter publication retains its existing acquire/release semantics. Offline configuration,
merge, and packaging add no VarHandles or shared hot-loop state.

Checkpoints publish complete snapshots and retain carry-forward state, evidence references, rotation
cursors, pending schedules, merge/model references, and frozen configuration identity. The
operational active environment, resume flag, workspace paths, and stop-file path are excluded from
the frozen fingerprint. A regular configured stop file is polled only at existing safe boundaries.

Every normal return produces a checkpoint-backed package. The packager streams raw evidence and
model members, writes deterministic machine-readable datasets and human-readable reports into an
owned sibling staging directory, validates inventory and checksums, and publishes with one atomic
rename. Complete and partial packages have collision-safe deterministic IDs. `package-run`
reconstructs this package from recorded inputs; it does not repeat the physical experiment.

Detailed schemas, mathematical definitions, failure rules, and phase evidence live in
[ROBUST_TRAINING_OPTIMIZER_PLAN.md](ROBUST_TRAINING_OPTIMIZER_PLAN.md) and
[`robust-training-optimizer/blueprints/`](robust-training-optimizer/blueprints/).
