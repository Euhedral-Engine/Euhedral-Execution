# Phase 16: Production Acquisition-Contention Integration

Status: completed

Plan intensity: maximum

Implementation intensity: high

## Objective and success criteria

Integrate the retained Phase 15 worker-local acquisition-contention EWMA into normal DIRECT/STAGED
selection, then evaluate the closed scheduler against the current production selector. Success
requires conservative selector behavior in the Phase 13 resolved regions, unchanged contention and
body-sensor ownership/lifecycle, complete focused and repository verification, and retained raw JMH
evidence for no-op, controlled useful work, and maintained representative workloads.

## Scope and boundaries

- Keep Phase 15 accounting, fixed-point scale `1_000_000`, division EWMA `1/16`, bootstrap, reset,
  and worker-local ownership unchanged.
- Read contention only at completed production batch boundaries and use it only for DIRECT/STAGED
  selection.
- Preserve productive-handle observation and the Phase 12 idle branch independently. Do not change
  polling quota, rank, park duration, body sampling, or body estimation.
- Add only benchmark/test diagnostics needed to compare the old and new selectors in the same build
  and explain retained rows.
- Do not add contention-driven idling, global aggregation, floating-point scheduling input,
  hysteresis without evidence, calibration machinery, or broader scheduler tuning.

## Current state and selected direction

`UpstreamQueue` already owns the accepted scalar smoother and resets it through the fragment's
owner-thread reset path. `FragmentControlPolicy` currently selects DIRECT for sufficient productive
availability or body cost at most 90 ns, STAGED at least 95 ns under scarcity, and retains its mode
inside the guard band. `ControlPlaneFragment` already exposes contention in benchmark-only policy
snapshots but does not pass it into selection.

Use one host-derived low-contention threshold at `650_000`. This lies between Phase 14's highest
retained low/middle DIRECT observation (`582_000`) and first clearly high observation (`705_000`).
Normal selection remains DIRECT for sufficient productive availability, incomplete body history,
or cheap body work. A valid contention value at or below `650_000` extends DIRECT across the broad
body-cost region. Above it, the unchanged 90/95 ns body tree selects DIRECT, STAGED, or the settled
guard-band mode. Before contention initialization, run the current production tree exactly.

## Risks and validation

The main risks are startup misclassification, a stale signal after reset, disagreement among
worker-local selectors, threshold-adjacent branch thrashing, and benchmark regimes hidden by
aggregate means. Focused tests will cover every tree boundary and fragment plumbing/reset.
Benchmark diagnostics will retain per-worker contention, body estimate, mode, productive handles,
registration, polling state, source count, and fork throughput. A bounded dynamic source transition
will check adaptation without adding production instrumentation.

Run focused tests, the required Core/benchmark tests and Spotless checks, full `gradle build`,
`git diff --check`, and a same-build policy-disabled/policy-enabled JMH comparison. Retain raw files
outside source-controlled data and classify no-op, useful-work, and actual-workload effects
separately.

## Work sequence

1. Write the implementation blueprint at
   `docs/blueprints/fragment-decision-tree/phase-16-production-acquisition-contention-integration.md`.
2. Implement Core plumbing, the explicit tree, focused tests, and bounded benchmark diagnostics at
   high intensity.
3. Run verification and the bounded benchmark matrix, investigate discrete regimes, and append the
   21-part completion record with exactly one requested outcome.
