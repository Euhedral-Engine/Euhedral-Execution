# Phase 4 Fragment Work-Cost Surface Plan

## Objective

Discover the first legitimate DIRECT versus STAGED decision surface after the failed-handle
acquisition correction. Measure both forced paths across a small deterministic CPU-cost sweep under
plentiful and genuinely scarce sources, validate the existing batch-compatible execution-cost
signal, and record only the smallest decision tree supported by corrected evidence.

Success means the Phase 4 blueprint contains the isolated body-cost curve, fork-level path results,
participation evidence, winner regions for both source shapes, the runtime-signal verdict, and
exactly one next unresolved branch. It does not add or change production policy.

## Requirements and boundaries

- Treat current Core handle retention as baseline behavior. Do not revisit pre-fix bimodality.
- Preserve two same-kind workers, logical CPUs 0 and 6 when available, batch target 32, natural
  source publication, routing, completion windows, forced modes, and the established JMH protocol.
- Sweep only a coarse predeclared set near 0, 5-10, 20, 40, 80, 150, 225, and 400-500 ns of
  isolated CPU work. Add points only inside an observed reversal interval.
- Retain JMH throughput/error, raw fork results, per-worker fractions, dominance, effective lanes,
  and relative STAGED advantage. Do not average discrete regimes together.
- Use the existing fragment execution-latency telemetry to evaluate a candidate runtime work-cost
  input. Do not add per-frame timing or production instrumentation.
- Do not change production scheduler behavior, batch sizing, source classification, or shared
  coordination. If evidence reveals another correctness defect, isolate and prove it before any
  correction.

## Current state and selected direction

`FragmentPathCalibrationBenchmark` already owns the forced DIRECT/STAGED fixture, corrected natural
source setup, worker completion accounting, and handle diagnostics. Its fixed 256-round arithmetic
body measured near 225 ns on the calibration host. Parameterizing the same loop by rounds gives a
cheap deterministic sweep without changing frame, source, or graph behavior.

The selected round counts are `0, 8, 24, 48, 96, 176, 256, 512`. The existing fixed 256-round
benchmarks remain unchanged; new work-only and scheduled sweep methods carry the parameter. First
measure actual isolated ns/op, then run the full coarse forced-path/source surface. If adjacent
points reverse the winner, add only enough intermediate round counts to establish a stable region.

For runtime-signal validation, the sweep fixture supplies a trial-local Micrometer registry through
the normal `FragmentConfig`. Each fragment already times multi-frame execution operations and
reports its smoothed nanoseconds-per-frame estimate at completed-batch boundaries. The fixture will
snapshot those existing per-core summaries at JMH iteration boundaries and compare their
measurement-only deltas with isolated body cost. This adds no Core hook and no per-frame observer.

## Risks and verification

- Very cheap loop bodies may be optimized differently from the isolated fixed body. The scheduled
  executor feeds each invocation from mutable worker-owned state and retains the result, while JMH
  consumes the isolated result.
- Existing latency telemetry includes execution-path overhead around the body, so it should track
  work cost monotonically but is not expected to equal isolated body cost at cheap points.
- Metrics publication adds the same configured batch-boundary work to all sweep rows. Its cost and
  any resulting distortion must be stated in the blueprint.
- Large variance or worker imbalance pauses decision-tree interpretation until the physical cause is
  inspected.

Run focused benchmark tests and assembly before JMH, preserve raw output outside the repository,
then run the benchmark module checks and repository build. Finish with stale-reference review,
`git diff --check`, and `git status --short`.

## Work sequence

1. Maximum intensity: write
   `docs/blueprints/fragment-decision-tree/phase-4-fragment-work-cost-surface.md` with the bounded
   experiment, hypotheses, stop rules, and reporting format.
2. High intensity: extend only the benchmark fixture and deterministic helper tests.
3. Maximum intensity: run the isolated calibration and corrected forced-path surface, investigate
   only reversal intervals or anomalies, and append completion evidence to the blueprint.
4. High intensity: verify the benchmark module and full repository without changing production
   policy.
