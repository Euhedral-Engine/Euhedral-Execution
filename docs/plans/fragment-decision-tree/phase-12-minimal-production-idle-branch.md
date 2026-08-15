# Phase 12: Minimal Production Idle Branch Integration

## Objective and success

Integrate the smallest production idle branch supported by Phase 11: when worker-local productive
availability is scarce relative to registered workers and the existing executor-body estimate is
clearly in the measured extreme-cheap regime, retain a deterministic polling quota and remove only
excess workers from aggressive polling. Success requires material recovery in the one-handle
near-no-op rows, no suppression of matched or non-extreme parallelism, bounded wake/reset/close,
and a stable idle set.

Intensity is **maximum** because the change joins worker selection, parking, wakeup, stale local
state, and lifecycle behavior in the hot control loop.

## Requirements and boundaries

- Preserve productive-handle observation, body sampling and aggregation, the 90/95 ns path guard,
  DIRECT/STAGED behavior and overrides, request order, caches, batches, routing, and topology.
- Use only productive handles, registered workers, the existing body estimate, and stable worker
  rank for production eligibility. FlowRecorder remains diagnostic only.
- Enter idle only between cycles after a completed batch and with no owner-local cached work.
- Use one fixed allocation-free park primitive. Do not tune duration or add adaptive waiting.
- Startup and uncertain, neutral, expensive, or sufficiently productive states remain active.
- Do not add calibration, smoothing, voting, topology scoring, or a new worker registry.

## Current state and direction

`ControlPlaneFragment.recordProgress` already publishes worker-local productive count and advances
`FragmentDecisionTree` only at a completed-batch boundary. `FragmentDecisionTree` already owns the
body estimate and captures diagnostic overrides. `LatticeEdge` already owns the JVM-wide registered
core bitmap and count. Reset and close already unpark the fragment owner.

Add an orthogonal idle decision to `FragmentDecisionTree`, derive a zero-based rank by counting the
existing registered cores below the owner core, and guarantee rank zero always polls. A production
idle loop will use one fixed timed park so source/productivity changes are eventually rechecked
without a new wake controller; reset and close retain their immediate unpark paths. Benchmark-only
fixed polling remains a setup override and forced DIRECT/STAGED modes remain exempt from production
idling.

The temporary extremely-cheap maximum is **20.0 ns**. Phase 11 measured the harmful near-no-op
region at 13-15 ns and the neutral 24-round region at 33-34 ns. Twenty nanoseconds leaves the neutral
point outside the branch and is intentionally not portable; it will be one isolated constant.

## Risks and validation

The principal risks are all-worker parking under stale counts, trapped owner-local cached work,
failure to reevaluate new opportunity, diagnostic override interference, and benchmark
misclassification of intentional zero participation. Focused tests cover the selector boundary,
rank-zero invariant, registered-rank calculation, startup/reset behavior, and fixed-override
compatibility. A bounded production JMH matrix supplies participation, mode, estimate, count, and
throughput evidence, plus a dynamic wake/lifecycle smoke.

Predeclared performance gates, using the Phase 11 retained baselines on this host, are:

- one handle/two workers/no-op: at least 50% recovery of the `40.719M -> 82.189M` absolute gap,
  or at least **61.454M ops/s**;
- one handle/four workers/no-op: at least 50% recovery of the `28.365M -> 82.189M` absolute gap,
  or at least **55.277M ops/s**;
- two handles/two workers/no-op: both workers poll and throughput is at least **118.989M ops/s**,
  95% of the retained 125.251M control;
- one handle/two workers/24 rounds: both workers poll and throughput is at least
  **32.903M ops/s**, 95% of the retained neutral control;
- one handle/two workers/256 rounds: both workers poll, STAGED remains reachable, and throughput is
  at least **7.304M ops/s**, 95% of the retained 7.688M control;
- the real two-live/one-productive no-op control must classify by productive count and materially
  recover versus its 35.489M all-active baseline;
- no unexplained fork cluster, unexpected active-worker disappearance, lifecycle failure, or
  repeated active/idle churn is accepted.

## Work sequence

1. Write `docs/blueprints/fragment-decision-tree/phase-12-minimal-production-idle-branch.md` at
   maximum intensity and settle the concurrency and lifecycle contract.
2. Implement only that blueprint in Core and the existing calibration benchmark.
3. Run focused tests, the bounded JMH matrix and wake smoke, required Gradle checks, and append the
   completion record with exactly one requested outcome.
