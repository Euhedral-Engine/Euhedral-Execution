# Phase 5 First Production Fragment Decision Tree Plan

## Objective

Validate the two runtime inputs implied by the corrected forced-path surface, then implement the
smallest normal-mode decision tree that selects the existing DIRECT or STAGED path. Success means
the runtime distinguishes sufficient independent pull availability from scarcity, distinguishes
clearly cheap from clearly expensive executor work with a tier-neutral owner-local signal, and
selects the four resolved leaves without changing either forced path.

This is the first production branch, not renewed policy discovery. The implementation must stop if
the existing handle/worker counts do not represent availability or if a low-cost body measurement
cannot separate the resolved work regions.

## Requirements and boundaries

- Preserve the corrected upstream-handle acquisition behavior. Do not rerun or encode the pre-fix
  worker-starvation regimes.
- Select between the existing DIRECT and STAGED operation orders. Do not redesign either path.
- Validate only independent pull availability and tier-neutral executor work cost.
- Make decisions on the fragment owner thread at batch boundaries. Add no locks, controller thread,
  online alternative-path probes, socket-wide optimizer, source labels, or generic policy system.
- Preserve `FragmentActionPicker`, benchmark mode, diagnostic DIRECT/STAGED overrides, pressure
  caps, cache ownership, and the existing path-inclusive service telemetry.
- Keep the unresolved 42.563-70.689 ns region on DIRECT. Do not add another branch for it.
- Use the existing one-eighth owner-local EWMA style only after the sampled work-cost signal proves
  sufficiently stable. Startup and insufficient history remain DIRECT.

## Current-state findings

`LatticeEdge.UPSTREAM_COUNT` is incremented once for each live `UpstreamHandle` and decremented on
handle completion. Every active fragment receives those handles in its owner-local
`UpstreamQueue`, and each handle has its own acquisition lock. At the fragment root,
`UpstreamQueue.getTrueUpstreamCount()` is therefore the existing numerator most closely aligned
with independently pullable opportunities.

`LatticeEdge.THREAD_COUNT`, exposed by `getThreadCount()`, is intended to represent registered
worker queues, but it currently increments when any thread first obtains an `UpstreamQueue` rather
than when a fragment registers. The selected minimal correction is to update that same counter only
when `ACTIVE_PARTITIONS` changes during `register` or `removeThread`. This preserves the existing
state and avoids a second registry while making the denominator match active execution workers.

The existing `FragmentControlPolicy.serviceTimeNs` is unsuitable for path selection. It is measured
around local, remote-cache, or upstream operations and changes with the selected path and handle
contention. It remains useful for the existing batch-size calculation and service metric, so the
new work-cost signal must be separate.

All fragment paths synchronously call `ControlPlaneFragment.accept` only after a frame has crossed
the selected cache/pull path. Timing the downstream executor dispatch there excludes acquisition,
routing, request, and cache costs. There is no existing multi-frame executor-body boundary. The
smallest trustworthy addition is therefore one sampled timer pair around a single downstream
dispatch, amortized across completed batches rather than paid per frame.

## Selected direction

Use the comparison `live upstream handles >= registered fragment workers` as the sufficient-
availability branch after the bounded count-semantics validation. Correct the existing worker
counter's registration semantics; do not introduce a ratio object or new shared counter.

Add a separate owner-local executor-dispatch estimate. Sample one accepted frame at a bounded batch
cadence, smooth valid samples with the existing one-eighth EWMA convention, and require a small
fixed history before the estimate can select STAGED. Calibrate the production boundary in the
signal's measured units from the retained 70.689 ns transition point and 84.657 ns first resolved
STAGED point. Implement only if their steady signal ranges leave a robust gap; otherwise return to
design.

Normal mode then applies one explicit conditional at a batch boundary:

```text
enough live handles for registered workers -> DIRECT
otherwise, insufficient work-cost history -> DIRECT
otherwise, work cost at or above conservative expensive boundary -> STAGED
otherwise -> DIRECT
```

Keep the current service estimate and target-work batch calculation independent from the new mode
selector. Standard forced overrides bypass normal selection and new work sampling so their path
cost remains unchanged. A validation-only diagnostic option may enable sampling under a forced
mode; it must not change the existing override's default behavior.

## Risks and validation

- A live handle count may describe lock independence but not current source readiness. This phase
  accepts it only for the already-observed repeating-source surface; readiness is a possible future
  split, not a branch to add now.
- Moving `THREAD_COUNT` to registration boundaries must preserve one-worker-per-active-core and
  cleanup behavior. If the existing `ACTIVE_PARTITIONS` invariant cannot make that exact, stop
  rather than adding coordination in this phase.
- A single-dispatch sample includes the common hot-source, executor-terminal, and frame-finalization
  boundary in addition to body work. It is acceptable only if forced DIRECT/STAGED and one/two-
  handle fixtures report materially equivalent values for identical synthetic work.
- Timer noise could move the unresolved region. Predeclare a conservative threshold derivation and
  require separation before implementing the branch; do not tune around unstable forks.
- Availability currently resets broad cycle state when its count changes. The implementation must
  stop using that reset as an implicit mode switch and retain the current mode through the active
  batch.

Run focused Core tests, the bounded signal fixtures, representative forced-path regression rows,
and four normal-tree rows. Then run the Core and benchmark builds, the repository build,
`git diff --check`, stale-reference review, and `git status --short`.

## Work sequence

1. Maximum intensity: complete
   `docs/blueprints/fragment-decision-tree/phase-5-first-production-fragment-decision-tree.md`.
2. High intensity: add only the availability observations and sampled work-cost measurement needed
   for validation; run the predeclared forced fixtures and stop if either input fails.
3. Maximum intensity: if both inputs pass, replace only normal-mode path selection with the explicit
   two-input tree and preserve the separate batch controller and forced modes.
4. High intensity: add deterministic policy/integration tests, run representative forced and normal
   JMH rows, and append completion evidence to the blueprint.

## Implementation outcome

Implementation stopped at step 2 on 2026-08-11. The live-handle/registered-worker input passed its
deterministic and three-fork fixture checks. The corrected common executor-dispatch EWMA remained
materially path/core dependent and its retained 80-round and 96-round ranges overlapped, so the
blueprint's predeclared boundary rule could not produce a valid constant. The normal selector was
not changed. Full evidence and the next single design question are recorded in the blueprint.
