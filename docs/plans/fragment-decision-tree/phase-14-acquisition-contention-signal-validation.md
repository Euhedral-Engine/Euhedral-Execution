# Phase 14: Acquisition-Contention Signal Validation

Status: complete

Intensity: maximum

## Objective

Determine whether a worker-local, once-per-`UpstreamQueue.pull` acquisition-miss fraction plus
executor body cost preserves the Phase 13 DIRECT/STAGED surface better than nominal source/worker
scarcity. Validate correctness, overhead, cross-topology behavior, equal-cycle weighting, and
path feedback without changing production path selection or idling.

## Scope and success criteria

Add one fixed-point observation to the existing pull loop. Count real `acquireLock()` calls and
failed calls in plain locals, publish one scaled observation only after a pull with at least one
attempt, and retain the state on zero-attempt pulls. Use an existing `FlowRecorder` dedicated to
this meaning and expose only best-effort worker-local benchmark diagnostics.

Keep productive sensing, `FlowThread` classification, the 20 ns idle boundary, polling quota,
rank, 1 ms park, body sampling and aggregation, the 90/95 ns production guard, batch size, request
order, routing, and caches unchanged. Do not add a controller, global state, per-attempt recorder
updates, timers for acquisition measurement, or production selection logic.

Success requires deterministic fixed-point and zero-attempt tests, a representative P-only and
mixed forced-path mapping at rounds 0/96/256 with refinement only where necessary, same-build
enabled/disabled overhead controls, one DIRECT -> STAGED -> DIRECT feedback diagnostic, explicit
worker participation and raw acquisition evidence, and exactly one requested completion outcome.

## Current-state findings and selected direction

`UpstreamQueue.pull` attempts acquisition only after polling a non-null, live handle. Failed
handles are reinserted and count toward the existing bounded `cycles` traversal; successful
service resets `cycles` and consumes a positive request bucket. The candidate boundary is exactly
the call to `acquireLock()`, for both request and consumer pulls.

`FlowRecorder` accepts `long` units and applies a default alpha of 0.05 with a 10 ms discontinuity
window. Its unit EWMA and variance are double-backed; its rounded rolling recurrence remains
bounded for a bounded input. Throughput, service, and unused batch recorders have different or
ambiguous meanings, so the queue will own a dedicated recorder. This adds one reference and one
recorder object per worker-local queue and no field to `FlowContext`.

Use `ACQUIRE_CONTENTION_SCALE = 1_000_000L`, selected before measurement. It gives six decimal
digits while keeping every recorder input in `[0, 1_000_000]`. The common scaling path multiplies
only when `failedAcquires <= Long.MAX_VALUE / SCALE`; a primitive decimal long-division fallback
handles the theoretical overflow range. Integer division truncates toward zero. The actual
Phase 14 fixture has at most 32 live sources and batch 32, so it always uses the fast path.

The existing Phase 13 benchmark already supplies topology selection, forced modes, sparse body
timing, per-worker completion, source lifecycle, and source-by-worker acquisition counters. Extend
that fixture with pull-cycle and recorder snapshots. Use a JVM property only to form the same-build
signal-disabled overhead control; the accepted candidate defaults enabled.

## Risks, evidence, and work sequence

The main risks are recorder clock/update cost, equal weighting of cycles with different attempt
counts, path-dependent contention, best-effort cross-thread snapshots, the P-only 4/7 no-op
discrete regime, and experiment duration. Predeclare the overhead gate as no more than 1% median
loss with the enabled lowest fork at least 98% of the disabled lowest fork.

1. Complete
   `docs/blueprints/fragment-decision-tree/phase-14-acquisition-contention-signal-validation.md`.
2. Implement the dedicated queue recorder, snapshot/reset plumbing, deterministic tests, and
   benchmark-only reports/feedback fixture.
3. Run focused tests and a short smoke before retaining measurements.
4. Retain the bounded forced-path surface, overhead controls, and feedback diagnostic; investigate
   accounting or lifecycle contradictions before policy interpretation.
5. Append the completion record and exactly one outcome, then run all required verification.
