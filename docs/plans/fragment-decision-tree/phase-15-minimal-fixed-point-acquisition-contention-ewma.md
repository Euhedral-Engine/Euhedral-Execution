# Phase 15: Minimal Fixed-Point Acquisition Contention EWMA Plan

Status: complete

Plan intensity: maximum

Implementation intensity: high

## Objective and success criteria

Determine whether the Phase 14 per-pull-cycle acquisition-contention observation can remain enabled
with a purpose-built fixed-point EWMA instead of `FlowRecorder`. Success requires correct worker-local
bootstrap/reset/accounting semantics, the predeclared scheduler overhead gate at mixed 32/23 and
1/23 DIRECT no-op, and preservation of the useful scarcity and body-cost ordering if the cost gate
passes.

## Scope and constraints

- Retain one equal-weight observation per eligible completed `UpstreamQueue.pull` cycle.
- Count only real `UpstreamHandle.acquireLock()` calls reached after null and completion checks.
- Use scale `1_000_000`, fixed-point hot-path arithmetic, one smoothed `long`, and one validity bit.
- Keep state plain and worker-local beside `UpstreamQueue`; reset it through the existing fragment
  owner-thread reset handoff.
- Add only diagnostic snapshot/reporting needed to retain benchmark evidence. Do not add production
  policy inputs, shared state, clocks, general statistics, or `FlowRecorder` calls for contention.
- Do not change DIRECT/STAGED selection, body guards, idling, worker ranking, acquisition behavior,
  cycle weighting, or accounting.

The two untracked Phase 14 plan/blueprint files present at task start are prior user-owned evidence
and are not modified by this phase.

## Current state and selected direction

Phase 14 removed its rejected instrumentation after demonstrating correct accounting, useful signal
separation, and a 10.22% abundant-path median loss. `UpstreamQueue` still owns the relevant pull loop;
`ControlPlaneFragment` already performs lifecycle reset on the worker thread and exposes low-frequency
benchmark snapshots. `sourceToCoreCrossover` retains the forced path, physical source counts, fixed
batch size 32, worker participation validation, and three-fork JMH methodology.

Implement a package-local acquisition-specific smoother with alpha `1/16`, truncating integer
division, first-sample bootstrap, and explicit reset. `UpstreamQueue` will record the scaled cycle
fraction once after an eligible loop. A startup-fixed diagnostic property will permit same-build
enabled/disabled scheduler comparisons, and fragment snapshots will expose only validity and the
fixed-point EWMA. The measured fixture has at most 32 attempts per pull cycle; the public scaling
helper will retain a cold overflow-safe path for wider callers without putting general statistics in
the update path.

## Risks and acceptance evidence

- Integer truncation may stop within 15 fixed-point units of a target; deterministic monotonic and
  bounded adaptation tests will make that behavior explicit.
- Cross-thread snapshots are best-effort diagnostics only and do not alter owner-local semantics.
- JMH noise is controlled with the Phase 14 same-build, three-fork enabled/disabled comparison.
- If mixed 32/23 fails median loss `<= 1%` or enabled-lowest/disabled-lowest `>= 98%`, stop broad
  mapping and remove candidate instrumentation for Outcome 2.
- If it passes, run the bounded source-scarcity and body-cost rows requested by the phase.

## Work sequence

1. Settle implementation and gates in
   `docs/blueprints/fragment-decision-tree/phase-15-minimal-fixed-point-acquisition-contention-ewma.md`.
2. Implement at high intensity with focused core and benchmark tests.
3. Run focused verification, the optional small smoother micro-cost check, then the authoritative
   abundant and severe scheduler controls.
4. Only after the cost gate passes, run bounded signal/body diagnostics and append the completion
   record with exactly one outcome.
