# Phase 10 - JMH and comparison runner

Dependencies: [07](07-external-solver-validation.md), [09](09-euhedral-execution-backend.md).

## Feature

Validation-gated JMH suites compare serial, ForkJoinPool, static-worker, and Euhedral execution of the same three-dimensional fluid problem.

## Numerical eligibility

Benchmark preflight requires a passing OpenLB comparison suite for the candidate numerical implementation and the physics features exercised by the selected cases. The report records the external reference version, case coverage, tolerances, and validation result. Missing, incompatible, failed, or unverified reference evidence produces an ineligible case.

Backend checks compare complete Java population fields against the externally checked serial implementation on bounded representative cases. Large cases use a full-field reference from an already verified Java backend, stored or streamed outside timing. Matching scheduling variants reuse that reference. Reports distinguish external numerical coverage, same-kernel field equivalence, and runtime validity checks.

Case identity includes geometry, numerical model, physical parameters, boundaries, initial state, duration, precision, and diagnostic settings. Numerical changes require corresponding validation evidence. Scheduling-only changes require renewed backend equivalence. An incompatible physical case remains separate from the comparable result group.

OpenLB supplies reference answers. Scheduler speedups compare the Java backends executing the shared kernel; OpenLB's execution time is reference-generation cost.

## Measurement lifecycle

`CfdBenchmark` uses JMH and the repository's annotation processor and catalog aliases. The `bench --config` command selects explicit backend, warmup, fork, iteration, and duration settings. Each fork owns one backend instance, and backends run sequentially in isolated JVMs with one external driver thread.

A measured invocation advances exactly `stepsPerInvocation` steps. `Mode.AverageTime` reports time per invocation. Runtime cell/step counts remain explicit in derived metrics.

Each invocation starts from the same deterministic state and configured physical pre-steps. Reinitialization uses existing buffers and a recorded reset/first-touch policy. Worker startup, geometry preprocessing, allocation, reset, field export, and full-field comparison occur outside steady-state timing.

The measured interval includes dispatch, numerical work, boundary handling, configured diagnostics, terminal waits, reductions, and buffer swaps. Separate setup and end-to-end measurements describe the remaining application costs.

Parallel backends share the same effective physical-worker CPU set, accounting for reserved cores and logical siblings. Reports include requested/effective IDs, affinity capability, JVM options, precision, grid, active-fluid cells, brick shape, source count, and source revision.

## Results

Small smoke and normal suites cover periodic flow, obstacle-heavy flow, a duct, and imported geometry. Results include raw JMH JSON, resolved configuration, validation reports, per-fork measurements, failures, and CSV/Markdown comparisons.

```text
MLUPS = fluidCells * stepsPerInvocation / (secondsPerInvocation * 1e6)
speedup = baselineSeconds / candidateSeconds
parallelEfficiency = serialSeconds / (workerCount * parallelSeconds)
```

Derived metrics use matching physical cases and measurement boundaries. Fork variability accompanies aggregate statistics. Failed, unstable, timed-out, and numerically ineligible cases retain their status and remain outside performance rankings.

## Verification

Coverage includes metric conversion, configuration compatibility, missing/stale validation evidence, changed viscosity/boundaries/duration, zero work, incomplete runs, and raw fork retention. Small real JMH runs exercise every available backend with completed field checks and isolated numerical timing.

## Interface

```bash
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd bench --config benchmarks/cfd/suites/smoke.json
```
