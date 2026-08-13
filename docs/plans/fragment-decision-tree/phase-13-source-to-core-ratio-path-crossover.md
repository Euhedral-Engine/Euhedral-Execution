# Phase 13: Source-to-Core Ratio and Path-Crossover Surface

Status: complete

Intensity: maximum

## Objective

Measure whether actual productive-source scarcity materially moves the forced DIRECT/STAGED body-cost
crossover, and whether that movement differs between homogeneous P-core and heterogeneous full-machine
workers. Interpret configured source ratios only as reproducible CPU-relative fixture inputs; record and
compare the actual source, registered-worker, and active-polling-worker counts.

The corrected severe full-machine fixture is configured `1:32`, which produces one source from the
32-logical-CPU ratio basis. It is not `1:24`. Physical core 0 is reserved from fragment execution, so the
current host is expected to register 23 workers. Neither count is hardcoded into generic policy.

## Scope and success criteria

Extend the existing forced-path calibration benchmark with one bounded source-to-core surface. Keep
productive sensing, FlowThread observation, stale local semantics, the 20 ns idle boundary, polling quota,
rank, 1 ms production park, body aggregation, 90/95 ns selector guard, request order, caches, routing, and
batch size unchanged. Do not implement a production branch or calibration.

Success requires:

- homogeneous P-core rows at explicit productive-source counts near workers, half, quarter, and one;
- heterogeneous full-machine rows at configured `1:1`, `1:2`, `1:4`, `1:8`, and corrected `1:32`;
- forced DIRECT and STAGED at coarse body anchors, with local refinement only around reversals;
- explicit CPU ratio basis, actual source/productive counts, registration, polling/parked counts, worker
  logical/physical identity, P/E class, rank, completion split, body estimate, handle service, fork means,
  JMH uncertainty, and throughput for every retained row;
- physical normalization by both productive sources / registered workers and productive sources / active
  polling workers;
- bug-first investigation of discontinuities; and
- exactly one of the five requested Phase 13 outcomes in the completion record.

## Current-state findings

`FragmentPathCalibrationBenchmark` already owns the real forced DIRECT/STAGED paths, fixed batch size,
repeating sources, source-by-worker acquisition/service evidence, per-worker completions, sparse executor-body
timing, production policy snapshots, and pinned topology construction. Its existing selector is intentionally
same-kind and bounded to earlier two-worker/idle work. `FragmentControlPolicy` exempts forced mode from the
production idle branch, so forced discovery rows keep every registered worker polling. This is an explicit
forced-path semantic, not a change to the idle mechanic.

The checked-in high-contention fixture currently creates one source per logical CPU and does not expose the
historical ratio parameter. Phase 13 will therefore encode the stated CPU-relative rule directly in the
calibration fixture: `max(1, CPU count / configured divisor)`. Actual source count remains separately reported.

On the current i9-14900K topology, physical cores 0-7 are P cores and 8-23 are E cores. Reserving physical core
0 for the harness should leave homogeneous P cores 1-7 (7 workers) and mixed cores 1-23 (23 workers). Setup
will assert and report the real selections rather than assuming those counts.

## Hypotheses and materiality

- **H0:** the crossover is materially stable across physical source scarcity; no scarcity branch is justified.
- **H1:** increasing scarcity moves the crossover toward DIRECT at higher body cost.
- **H2:** comparable physical scarcity has a materially different surface on homogeneous and heterogeneous
  workers.
- **H3:** path choice does not recover enough throughput to explain the severe low-source loss.
- **H4:** a worker, source, accounting, routing, cache, CPU-selection, or fixture defect creates an apparent
  regime; fix it narrowly and rerun before interpretation.

A resolved winner requires at least 5% relative advantage, non-overlapping JMH confidence intervals, and the
same fork-level direction. Rows failing any gate are transition/unresolved. Preserve bimodality or
non-monotonicity rather than reducing it to one threshold.

## Selected direction

Add a `sourceToCoreCrossover` benchmark state with independently supplied topology, CPU-relative divisor,
explicit source count, work rounds, and forced mode. Exactly one source-count mechanism is valid per row.
Reserve physical core 0 when another active core exists. Select only remaining P cores for the homogeneous
surface and all remaining cores for the full-machine surface. Fail setup when the requested topology is not
physically present.

Start with rounds `0`, `96`, and `256` for both modes. Use one-fork short exploratory runs to identify local
reversals, then retain only three-fork standard-duration rows at the anchors and necessary refinements from
`24`, `48`/`64`, `80`, `176`, and `512`. The corrected full-machine configured ratios are `1:1`, `1:2`, `1:4`,
`1:8`, and `1:32`; homogeneous rows use explicit counts `7`, `4`, `2`, and `1` if setup confirms seven workers.

## Risks and work sequence

The main risks are source-by-worker diagnostic cost at 32 sources, forced-mode idle bypass obscuring a polling
denominator distinction, mixed-core body estimates, harness interference, and long/bimodal forks. Keep the
existing instrumentation cadence, report worker-local estimates rather than hiding heterogeneity, and add a
benchmark-only fixed polling follow-up only if the initial physical surface is otherwise uninterpretable.

1. Complete
   `docs/blueprints/fragment-decision-tree/phase-13-source-to-core-ratio-path-crossover.md`.
2. Implement the bounded topology/source fixture and deterministic helper tests in `benchmarks` only.
3. Run focused tests and a smoke; inspect actual host counts and topology before retaining data.
4. Run coarse exploratory and three-fork retained surfaces, refining only observed crossover regions.
5. Analyze physical ratios, topology, polling composition, body estimates, completions, and handle evidence;
   investigate discontinuities before classification.
6. Append the completion record, run all required verification, inspect diff/status, and record exactly one
   final outcome.
