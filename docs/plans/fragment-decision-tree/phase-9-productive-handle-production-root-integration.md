# Phase 9 Productive-Handle Production-Root Integration Plan

Status: complete - integration blocked by productive-sensor miss-path overhead

Intensity: high implementation and maximum evidence review

## Objective

Replace only the production selector's live-handle availability input with the already-validated
worker-local observed productive-handle count. Accept the integration only if deterministic tests,
the fixed normal-policy rows, same-build overhead controls, worker participation, and lifecycle
evidence preserve the Phase 7 tree and the Phase 8 winner relationship.

## Scope and success criteria

The production change is limited to the completed-batch read made by each `ControlPlaneFragment`:

```text
worker-local UpstreamQueue.getProductiveHandleCount()
    -> FragmentControlPolicy.completeBatch(...)
    -> unchanged availability comparison and body-cost child
```

Do not change the productive sensor, DIRECT or STAGED execution paths, batch boundaries, body-cost
cadence or aggregation, 90/95 ns bounds, routing, cache behavior, topology, request ordering, or
forced-mode behavior. Productive observations remain optimistic, owner-local, deliberately stale,
and unsynchronized across workers.

Success requires:

- sufficient productive availability selects DIRECT regardless of body cost;
- scarce productive availability delegates to the unchanged startup/cheap/guard/expensive child;
- the fixed expensive normal rows select DIRECT, STAGED, and STAGED for the Phase 8 physical
  fixtures in order;
- the two-live/one-productive cheap control selects DIRECT;
- resolved normal rows remain within the predeclared forced-winner gates;
- productive and empty-miss sensor controls remain within the predeclared overhead gates; and
- worker participation and all existing productive-sensor lifecycle contracts remain valid.

## Current state and affected components

`UpstreamQueue` already owns the conformance-tested `live - observedNonproductive` count. Its live
accessors retain liveness semantics. `ControlPlaneFragment.recordProgress` currently reads the same
owner queue's true live count only after a completed batch, then passes it to
`FragmentControlPolicy`. `FragmentControlPolicy` already implements the complete Phase 7 body-cost
tree and isolates diagnostic overrides from normal selection.

The owning code is in `euhedral-core`; bounded fixtures and JMH reporting are in `benchmarks`.
Neither module descriptor changes.

## Validation and fixed gates

Use rounds 512 for the three expensive physical fixtures and rounds 24 for the two-live,
one-productive cheap control. Use the existing two-worker, batch-cap-32, natural-handle,
1,048,576-frame fixture and the existing three-fork protocol for acceptance evidence.

Before full measurement, retain these gates from Phase 7:

- normal median throughput no more than 2% below the matching forced-winner median;
- normal lowest-fork score at least 97% of the matching forced winner's lowest fork;
- sensor-enabled median throughput loss at most 1% versus same-build disabled control;
- sensor-enabled lowest-fork score at least 98% of the disabled lowest fork; and
- both workers remain productive with no new discrete fork regime.

Measure productive-success and real empty-incomplete-queue miss paths separately. Raw generated
evidence stays under `benchmarks/build/reports` and outside source control.

## Work sequence

1. Complete
   `docs/blueprints/fragment-decision-tree/phase-9-productive-handle-production-root-integration.md`.
2. Change the one production availability read and add focused selector/safe-boundary tests.
3. Extend the existing calibration fixture for normal and forced Phase 9 rows plus worker-local
   productive-count reporting and same-build overhead controls.
4. Run focused tests, bounded JMH gates, required module/full checks, stale-reference searches,
   `git diff --check`, and final status review.
5. Append one and only one completion outcome to the Phase 9 blueprint.
