# Phase 10 - JMH and comparison runner

Status: implemented. See [BENCHMARKING.md](BENCHMARKING.md) for settings, qualification, timing boundaries, and artifact interpretation.

Dependencies: [07](07-external-solver-validation.md), [08](08-parallel-execution-backends.md).

## Feature

Validation-gated JMH suites compare serial, ForkJoinPool, static-worker, and Euhedral execution of the same three-dimensional fluid problem.

## Numerical eligibility

Benchmark preflight requires a passing OpenLB comparison suite for the candidate numerical implementation and the physics features exercised by the selected cases. The report records the external reference version, case coverage, tolerances, and validation result. Missing, incompatible, failed, or unverified reference evidence produces an ineligible case.

The external suite checks the numerical implementation on bounded representative cases. Large cases use a full-field Java reference, stored or streamed outside timing after external numerical eligibility checks. Matching scheduling variants reuse that reference. Reports distinguish external numerical coverage, same-kernel field equivalence, and runtime validity checks.

Case identity includes geometry, numerical model, physical parameters, boundaries, initial state, duration, precision, and diagnostic settings. Numerical changes require corresponding validation evidence. Scheduling-only changes require renewed backend equivalence. An incompatible physical case remains separate from the comparable result group.

OpenLB supplies reference answers. Scheduler speedups compare the Java backends executing the shared kernel; OpenLB's execution time is reference-generation cost.

## Measurement lifecycle

`CfdBenchmark` uses JMH and the repository's annotation processor and catalog aliases. The `bench --config` command selects explicit backend, warmup, fork, iteration, and duration settings. Each fork owns one backend instance, and backends run sequentially in isolated JVMs with one external driver thread.

A measured invocation advances exactly `stepsPerInvocation` steps. `Mode.AverageTime` reports time per invocation. Runtime cell/step counts remain explicit in derived metrics.

Each invocation starts from the same deterministic state and configured physical pre-steps. Reinitialization uses existing buffers and a recorded reset/first-touch policy. Worker startup, geometry preprocessing, allocation, reset, field export, and full-field comparison occur outside steady-state timing.

The measured interval includes dispatch, numerical work, boundary handling, configured diagnostics, terminal waits, reductions, and buffer swaps. Separate setup and end-to-end measurements describe the remaining application costs.

Parallel backends share the same effective physical-worker CPU set, accounting for reserved cores
and logical siblings. Reports include requested/effective IDs, affinity capability, JVM options,
precision, grid, active-fluid cells, range shape, requested/resolved source count, submission
policy, and source revision.

The presets select one Euhedral source per effective worker. Custom variants can select other
positive counts. Every variant uses the same numerical kernel, cell/timestep work, worker budget,
diagnostic settings and measurement boundaries. The normal suite selects the same 8-cubed bricks for
Euhedral, FJP and static, without per-variant overrides. Each distinct
partition has one full correctness reference, shared across its variants and forks. Fixed-granularity
sweeps override these settings uniformly across backends. Reports retain source count and brick
shape as explicit dimensions.

Persistent sources lazily materialize ordinal ranges as defined in Phase 08. Frame acquisition and
execution are included in the measured interval; source creation, full frame/scratch preallocation
and registration occur during setup. Recycler misses fail without allocating. Euhedral manages
worker source order and internal distribution.

## Results

A small smoke suite covers periodic flow, obstacle-heavy flow, a duct, and imported geometry. The stock normal suite uses a 256-cubed periodic-shear domain with 1,000 timed steps. Results include raw JMH JSON, resolved configuration, validation reports, per-fork measurements, failures, and CSV/Markdown comparisons.

```text
MLUPS = fluidCells * stepsPerInvocation / (secondsPerInvocation * 1e6)
speedup = baselineSeconds / candidateSeconds
parallelEfficiency = serialSeconds / (workerCount * parallelSeconds)
```

Derived metrics use matching physical cases and measurement boundaries. Fork variability accompanies aggregate statistics. Failed, unstable, timed-out, and numerically ineligible cases retain their status and remain outside performance rankings.

## Verification

Coverage includes metric conversion, configuration compatibility, missing/stale validation evidence,
changed viscosity/boundaries/duration, zero work, incomplete runs, explicit single-source FJP
comparison settings, resolved worker-count sources, separation of source-count variants, and raw
fork retention. Small real JMH runs exercise every available backend with completed field checks and
isolated numerical timing.

## Interface

```bash
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd bench --config benchmarks/cfd/suites/smoke.json
```

## Implementation notes

The `benchmark` package owns suite parsing, numerical eligibility, full streamed references,
JMH fork orchestration, runtime lifecycle, and CSV/Markdown comparison. `Simulation.reset()`
reuses existing buffers and clears driver reductions; initialization and pre-steps run in JMH
invocation setup. Full-field checks run after every invocation; a checked warmup qualifies the fork
before measurement without a separate preflight simulation. Numerical
identity is distinct from the loaded execution artifact identity, so scheduling changes renew
backend equivalence while physical/numerical changes require matching external evidence.

The smoke preset uses bounded fixtures and retains the duct as ineligible. The normal preset
runs a 256-cubed periodic-shear domain for 1,000 timed steps per invocation, with two forks
per variant and an explicit representative periodic-shear coverage scope. Only grid and duration
may differ from the externally verified family; complete qualified-reference comparisons use the
actual large domain and duration. Other physical configurations require exact external evidence.
Reports keep representative external coverage separate from exact-case validation.
The tests exercise real isolated JMH forks for every backend, verify raw fork retention, and test
rejection of stale/missing evidence and changed physical settings. Short verification measurements
do not establish production defaults or a throughput winner.

Both supplied suites omit serial timing and generate their reference with FJP, without a separate
serial qualification replay. `referenceBackend` is independent of the speedup baseline and defaults
to serial for custom suites. Parallel efficiency is absent when no serial timing variant is selected.
The measurement order is Euhedral with worker-count sources, FJP, then static workers. The runner
announces reference preparation and every backend/fork before starting.
Successful Euhedral forks export their final state to `simulation-final.vti` outside JMH timing,
using the existing streaming writer. See [BENCHMARKING.md](BENCHMARKING.md) for viewing instructions.
