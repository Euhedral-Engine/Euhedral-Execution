# Phase 11 Idle-Mechanic Decision-Tree Discovery Plan

Status: complete

Intensity: maximum

## Objective

Determine whether an additional registered fragment worker can become net harmful under
production-reachable light work, prove whether aggressive polling is causal, and identify the
smallest already-available physical observations that separate useful from excess active capacity.
This phase discovers idle eligibility only. It does not redesign the existing production tree or
choose how an eligible worker should wait.

## Scope and success criteria

Reuse the production selector's worker-local `productiveHandles`, shared `registeredWorkers`, and
executor-body estimate together with the existing `FlowRecorder` state. Add only benchmark
diagnostics, a parameterized scaling fixture, and one setup-only fixed parking intervention. Do not
change productive observation, body-cost aggregation, the 90/95 ns guard, normal execution modes,
batch adaptation, routing, worker registration, or production waiting behavior.

Success requires:

- homogeneous workers on physical cores that do not share L2 caches with one another;
- a 1/2/4/8 active-worker scaling surface at one productive handle and near-no-op work;
- a bounded productive-handle interaction at the smallest retained worker count;
- near-no-op, 24-round cheap, and 256-round moderate body controls at the smallest harmful shape;
- total throughput, per-worker completions, dominance, productive counts, registration, body cost,
  selected mode, and relevant existing recorder state for every retained row;
- no lost worker, lost handle, source-state, routing, cache, affinity, or fixture defect;
- if a harmful row exists, the same registered graph with only the physically useful subset fixed
  in a parked diagnostic state; and
- exactly one completion outcome recorded in the Phase 11 blueprint.

## Current-state findings

`ControlPlaneFragment.CycleState` owns three worker-local `FlowRecorder` instances. The throughput
recorder records completed frames once per productive loop. The service recorder records measured
execution nanoseconds per completed frame on the same cadence when execution timing is available.
Both are already updated in production whether or not a meter registry is configured. The batch
recorder is allocated and reset but is not updated. Recorder averages, extrema, exponentially
weighted variance/trend, and units-per-time state are therefore reusable observations; none records
an empty poll directly.

The existing calibration benchmark already retains per-worker completions, handle acquisition,
body timing, source lifecycle, and production-policy snapshots. Its core selector prefers P cores
on one socket. On the current i9-14900K, physical cores 0-7 are P cores with distinct L2 masks;
physical cores 8-23 are E cores in four groups that share an L2 cache. The initial matrix will use
the eight P cores only and explicitly reject selected workers whose L2 masks overlap. The JMH
harness remains on a separate core.

## Hypotheses and materiality gates

- **H0:** correcting productive-handle selection is sufficient; more active workers cause no
  stable material loss.
- **H1:** when active workers exceed productive opportunities and executor work is cheap, polling
  and scheduler contention create a stable throughput loss.
- **H2:** productive availability and body cost do not separate the regimes, but the smallest
  existing `FlowRecorder` observation does.
- **H3:** any apparent reversal is a correctness or benchmark defect.

A harmful excess-worker row must lose at least 5% total throughput relative to the best lower
worker count, repeat in every retained fork, and have resolved uncertainty where practical. The
source/body state, registration, worker presence, and topology must remain valid. Diagnostic
parking must improve median throughput by at least 5%, recover at least half of the measured
all-active penalty, and preserve the same registered workers and sources. Smaller effects are not
material enough to justify a future controller.

## Selected direction

Extend `FragmentPathCalibrationBenchmark` with one independently parameterized production-policy
state. Command-line parameters select worker count, productive repeating handles, work rounds,
whether the real empty-live queue is included, and whether all workers or only a fixed subset polls.
Defaults remain one bounded row so JMH does not accidentally create a Cartesian full matrix.

Expose the existing throughput and service recorder statistics through the benchmark's existing
best-effort fragment snapshot. This is a diagnostic read only; recorder updates and production
metrics do not change. Treat completion throughput as experimental evidence, not a production
branch input.

For the causal control, install a setup-only diagnostic override before fragments are constructed.
All selected fragments still start and register. Non-selected fragments enter one indefinite
`LockSupport.park` loop that wakes only for reset or teardown. This is one fixed intervention, not a
sleep policy, park-duration experiment, or application configuration.

## Work sequence

1. Complete the bounded design in
   `docs/blueprints/fragment-decision-tree/phase-11-idle-mechanic-decision-tree-discovery.md`.
2. Add the generalized fixture, explicit private-L2 selection check, recorder diagnostics, fixed
   parking override, and deterministic helper/lifecycle tests.
3. Run a short smoke, then the 1/2/4/8 one-handle scaling experiment. Investigate any discrete
   fork or participation regime before continuing.
4. At the smallest material reversal, run only the productive-opportunity and body-cost rows needed
   to separate H0/H1/H2/H3. Add the real empty-live-source control where it tests classification.
5. If warranted, run the all-active versus fixed-parked causal pair and inspect recorder stability
   across warmup and measurement windows.
6. Run required module, formatting, full-build, diff, stale-reference, and final status checks;
   retain raw JMH evidence under ignored `benchmarks/build/reports`; append exactly one outcome.
