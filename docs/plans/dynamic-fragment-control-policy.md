# Dynamic Fragment Control Policy

## Status

- Date: 2026-08-10
- Planning intensity: maximum
- Blueprint intensity: maximum
- Implementation intensity: high, to be reassessed by the blueprint

## Objective

Replace the workload-tuned constants in `FragmentControlPolicy` with a small online controller that
adapts to the workload and machine while preserving the current pull graph, ownership rules, and hot
loop constraints.

The policy should optimize sustained throughput without making sparse or short-lived work pay a
large calibration cost. When two choices perform equivalently, prefer direct execution and the
smaller batch.

## Scope

In scope:

- Direct versus staged execution selection.
- Per-fragment batch-size adaptation within the existing pressure, quota, and configured caps.
- Runtime measurements needed by the policy.
- Focused Core tests and JMH coverage for the adaptive behavior.
- Minimal metrics needed to understand the selected mode, batch size, and mode changes.

Out of scope:

- `euhedral-training` and all relationships or hooks involving it.
- `FragmentActionPicker` and its configuration or behavior.
- Changes to routing, frame semantics, source ownership, topology lifecycle, or idle spin/park policy.
- A central dispatcher or any blocking coordination in the worker loop.

## Current State

The policy already reacts to measured service time, but the important decisions still depend on
fixed values tuned with `core-hc-throughput` and Mandelbrot:

- the direct/staged service-time thresholds;
- the target amount of work in a direct or staged batch;
- the smoothing and transition thresholds.

Service time alone cannot identify the best mode everywhere. Direct pulling can look efficient on
one fragment while holding an upstream handle long enough to leave sibling cores underutilized.
Source count, ordered work, contention, cache state, topology, and workload variance also affect the
result.

## Selected Direction

- Keep batch adaptation owner-local in `FragmentControlPolicy`.
- Select direct versus staged mode from low-frequency socket-wide progress, since staging changes
  work distribution across sibling fragments.
- Measure complete active work intervals, including requests, pulls, cache operations, misses, and
  frame execution. Exclude time spent fully idle with no source or cached work.
- Compare the current choice with bounded probes of the alternative. Discard transition warmup,
  learn the comparison margin from observed variance, and retain the current choice when evidence
  is inconclusive.
- Tune batch size by testing neighboring bounded sizes rather than deriving it from a fixed target
  duration. Retain learned sizes separately for direct and staged operation, clamp immediately to
  the eligible cap, and prefer the smaller size on a tie.
- Start conservatively in direct mode with batch size two. Avoid calibration until enough sustained
  work exists, periodically recheck a settled choice, and restart learning when throughput or active
  topology changes materially.
- Keep fragment-local state plain and allocation-free. Publish shared socket observations with the
  repository's padded atomics and explicit acquire/release or CAS transitions only at batch or
  observation boundaries.
- Do not add a public configuration API in the first version. The existing static policy remains the
  recorded benchmark baseline, not a runtime fallback that continues to make workload decisions.

## Acceptance Criteria

- The four workload-tuned mode and batch-work constants no longer decide production behavior.
- Cheap, plentiful unordered work converges to direct execution; expensive work with scarce shared
  sources can converge to staged execution.
- A persistent workload that changes between those shapes can move in both directions without
  oscillating continuously.
- Batch size adapts within the existing pressure/configuration cap and recovers when that cap grows.
- Ordered work, sparse arrivals, cancellation, draining, topology changes, and shutdown retain their
  existing semantics.
- The hot loop adds no allocation, locks, blocking I/O, formatting, or info-level logging.
- JMH results show no meaningful regression from the current policy across Core latency, light and
  high contention throughput, and multiple Mandelbrot degrees. A mixed cheap/expensive benchmark
  demonstrates convergence after a live workload change.
- Performance claims are reported with JMH variance and hardware details; untested architectures
  are described as unverified rather than assumed equivalent.

## Work Sequence

### 1. Blueprint

Intensity: maximum.

Write `docs/blueprints/dynamic-fragment-control-policy.md`. Inspect the current fragment loop,
policy tests, cache/request flow, topology lifecycle, padded atomics, and relevant benchmarks. Settle
the compact controller state machine, observation windows, statistical comparison, shared-state
lifecycle, memory semantics, metrics, and deterministic fixtures. Keep the design small enough for
one implementation pass and do not edit production code.

The blueprint must reassess the implementation intensity and identify any result that would require
returning to design rather than guessing during implementation.

### 2. Implementation

Intensity: provisionally high; use the blueprint's final assessment.

Implement only the blueprint in `euhedral-core` and the benchmark module. Add declaration comments
required by the repository workflow, preserve hot-loop ownership and memory semantics, and avoid
unrelated cleanup. Do not inspect or modify `euhedral-training` or `FragmentActionPicker`.

### 3. Verification and Handoff

Run the focused policy and fragment tests, then `mise exec -- gradle :euhedral-core:test`. Run the
relevant JMH benchmarks against a recorded pre-change baseline, inspect `git diff --check`, and
confirm that only blueprint-approved files changed. Record test results, benchmark variance,
environmental limits, and any unmet acceptance criterion in the blueprint completion notes.

