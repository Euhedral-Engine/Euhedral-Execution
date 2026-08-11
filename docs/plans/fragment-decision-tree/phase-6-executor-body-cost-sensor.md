# Phase 6 Executor Body-Cost Sensor Plan

Status: completed; sparse executor-only timing passed the predeclared signal and overhead gates

## Objective

Determine whether the executor boundary can provide a low-cost, tier-neutral body-work signal that
separates the retained 80-round and 96-round regions. Success means one diagnostic-only sensor
measures the same executor work consistently under forced DIRECT and STAGED and under one and two
live handles, while leaving the normal selector unchanged.

This is a sensor investigation, not another scheduler-surface experiment. The phase must either
validate the signal for later use by the already-designed tree or reject executor timing and name a
different kind of observable for the next design.

## Requirements and boundaries

- Treat `live upstream handles / registered active fragment workers` as validated. Do not revisit
  availability semantics or the failed-handle defect.
- Retain only rounds 24, 80, and 96, fixed batch target 32, two same-kind pinned workers, forced
  DIRECT/STAGED, and the existing scarce/plentiful repeating-source fixtures.
- Do not vary readiness, batch size, topology, routing, source order, or any other policy input.
- Do not reuse Core service latency or the rejected sampled dispatch interval.
- Time only executor work. Exclude requests, cache traversal, pulls, handle acquisition,
  `LatticeHotSource` dispatch, liveness checks, and frame finalization.
- Keep instrumentation diagnostic-only. Do not add the normal decision tree or an expensive-work
  constant.
- Add no per-frame timer pair, lock, allocation, log operation, registry operation, or cross-core
  controller state.
- Predeclare cadence, neutrality, separation, and overhead gates. Do not tune them after a failed
  run.

## Current-state findings

The fragment's existing multi-frame intervals surround `ControlPlaneCache.drain`,
`LatticeVertex.pull`, or `UpstreamQueue.pull`. They necessarily include the scheduling behavior that
made the existing service statistic tier- and availability-dependent. Queue drains invoke a
consumer once per frame; they do not expose a contiguous body-only sub-batch.

`LatticeHotSource.accept` and `AbstractExecutor.ExecutionTerminal` also receive one frame at a time.
The terminal is nevertheless the narrowest general boundary: after the liveness check it invokes
`AbstractExecutor.execute(frame)`, then performs `doFinally` separately. Timing only the virtual
executor call measures executor/body work without terminal finalization or upstream path work.

`ArrayFrame` and `CollectionFrame` execute multiple child bodies, but they are optional composite
workloads with different lifecycle semantics. They cannot represent ordinary frames or define a
general production signal. Creating a general contiguous executor batch would require buffering or
changing finalization order, so it is outside this investigation.

The smallest viable experiment is therefore sparse single-body timing at the executor terminal,
not a timer around a multi-frame scheduling loop. One fixed timer pair every 256 eligible executor
calls avoids timing every frame. Cadence stays owner-local in the terminal; the benchmark publishes
only sparse diagnostic totals into existing padded per-CPU storage.

## Selected direction

Add an opt-in protected diagnostic constructor to `AbstractExecutor`. Existing constructors and all
normal executors remain sampling-disabled. The diagnostic form accepts a nanosecond clock and an
elapsed-time recorder supplied by the calibration executor. The terminal times only
`AbstractExecutor.this.execute(frame)` for every 256th live frame and records only normally returned
calls. Cancellation, exceptions, liveness, and both finalization paths remain outside the sample.

Extend `FragmentPathCalibrationBenchmark` with one bounded executor-body validation state. Retain
raw measurement-iteration sample counts and elapsed totals per worker, then derive fork-worker
aggregate estimates without EWMA. Validate all 24 fork-worker estimates at each work point across:

```text
2 modes * 2 source shapes * 3 forks * 2 workers
```

The primary gate is a strict retained-range separation with at least a 5 ns margin:

```text
max(all 80-round fork-worker estimates) + 5 ns
    <=
min(all 96-round fork-worker estimates)
```

For each work point, every fork-worker estimate must also remain within
`max(5 ns, 10% of the point median)` of the global median. At 24 rounds, the four mode/shape group
medians must span no more than 5 ns. These bounds are fixed before the run and are not threshold
candidates.

Measure overhead against the clean Phase 5 baseline and against sampling-disabled execution in the
same candidate build. Use the 24-round plentiful DIRECT and scarce STAGED controls. The median
throughput loss must be at most 1%, the lowest retained fork must remain within 2% of the comparison
run's lowest fork, and the benchmark must not expose new participation regimes. An inconclusive
result may repeat the same protocol once; it may not change cadence or smoothing.

If all gates pass, retain the diagnostic seam and record that the executor-work signal is ready for
a later production-tree integration blueprint. If separation, neutrality, or overhead fails,
remove the diagnostic seam after preserving evidence and stop. The next design must choose a
non-timing observable, such as executor-supplied coarse work units, rather than refining this timer.

## Risks and validation

- `AbstractExecutor.execute` is application-extensible, so the signal measures the whole executor
  override, not necessarily only `AbstractFrame.execute`. That is the intended generic definition of
  executor work; benchmark completion accounting is common fixed executor work and the 24-round
  neutrality control will reveal if it dominates.
- `System.nanoTime()` noise may still obscure the 14 ns isolated-body gap. Aggregating many sparse
  samples reduces random error without changing the physical interval or using an EWMA.
- Even a disabled final-field branch can affect a hot terminal. Compare the candidate's disabled
  path with commit `3331747` before retaining the seam.
- Sample publication uses padded CPU-indexed counters only on sampled calls. If that still causes a
  material forced-path regression, reject the mechanism.
- A sampled body that throws is not representative of productive work. It must not update the
  estimate, and ordinary error/finalization behavior must remain unchanged.

Run focused executor lifecycle tests, benchmark aggregation tests, the bounded JMH protocol, Core
and benchmark builds, the repository build, `git diff --check`, an ASCII scan of new documents, and
final status review.

## Work sequence

1. Maximum intensity: complete
   `docs/blueprints/fragment-decision-tree/phase-6-executor-body-cost-sensor.md`.
2. High intensity: add the diagnostic-only executor sampler and deterministic lifecycle/cadence
   tests; do not touch fragment policy.
3. High intensity: extend only the existing calibration benchmark, capture the current baseline,
   and run the fixed 24/80/96 forced-path validation.
4. Maximum intensity: apply the predeclared gates, remove a rejected seam or retain a passing one,
   and append the single accepted outcome and evidence to the blueprint.
