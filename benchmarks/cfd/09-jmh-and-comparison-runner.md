# Phase 09 - JMH and comparison runner

Prerequisites: 05, 06, 08. Read the [master plan](README.md), [repository map](REPOSITORY_MAP.md), and all backend completion contracts.

## Deliverable

A repeatable comparison of serial, ForkJoinPool, static workers, and Euhedral on the same real 3D fluid problem, with raw JMH results and validated field equivalence.

## Changes

- Add JMH using the existing version-catalog aliases and annotation processor. Keep benchmarks in this subproject; do not wire them into calibration trials or change the parent BenchRunner.
- Add `CfdBenchmark` and the `bench --config <file>` entry point. Use one JMH driver thread and an explicit backend parameter. Create workers once per trial/fork and close them afterward. Backends run in separate forked JVMs, never concurrently in the same process.
- Each measured invocation advances exactly `stepsPerInvocation` timesteps. Use `Mode.AverageTime` with an explicit time unit. Keep the raw per-invocation meaning intact; do not use a compile-time OperationsPerInvocation annotation for runtime cell/step counts.
- Prepare the same deterministic initial state and configured physical pre-steps outside measurement for every invocation. Reinitialize existing buffers without retaining another huge full-state copy. Keep reset/first-touch policy identical across backends and record it; untimed reset can still change cache/NUMA conditions.
- Exclude JVM/runtime startup, mesh parsing, voxelization, grid allocation/reset, export, and full-field comparison from steady-state timing. Include dispatch, computation, required boundaries/diagnostics, terminal waits, reductions, and swaps. Provide separate explicitly labeled setup/end-to-end timings instead of mixing them into the scheduler result.
- Validate all backends against serial on bounded representative cases before scoring them. For a large case, create one full-field reference with an already validated FJP/static backend and reuse it across matching scheduling variants; do not rerun a giant serial simulation for every brick/source setting. Compare fields outside timing without retaining multiple large simulations at once; a streamed reference artifact is acceptable. Distinguish small-case validation, large-case field comparison, and runtime validity checks in reports; random spot checks alone are not full-field equivalence.
- Resolve the same effective physical-worker CPU set for parallel backends, accounting for Euhedral's reserved core and logical siblings. Do not equate requested CPU count with active workers. Record requested/effective IDs, affinity capability, JVM args, precision, grid, active-fluid cells, brick shape, source count, and source revision. No physical pinning claim when only hints are available.
- Add named small smoke and normal suites covering uniform periodic work, obstacle-heavy flow, a duct, and the imported fixture. JMH warmups/forks/iterations must be explicit; smoke results are not release performance evidence.
- Persist raw JMH JSON, resolved config, correctness status, failures, and a compact CSV/Markdown comparison. Compute `MLUPS = fluidCells*stepsPerInvocation/(secondsPerInvocation*1e6)`, speedup, and parallel efficiency against the same case's baseline. Report per-fork spread, not only a pooled best number.
- Keep failed, numerically unstable, timed-out, and incomparable cases visible. Do not rank them as fast results or automatically change a production default.

## Acceptance

Test metric conversions with fixed data, configuration compatibility, partial/failed runs, zero work rejection, and retention of raw fork results. Intentionally change a boundary/viscosity/step count and verify comparison rejects a same-problem claim.

Run a small real JMH smoke comparison with all available backends. Ensure no image export occurs in measured invocations and that worker startup is outside the measured region. Record unsupported hardware checks honestly; never fabricate a speedup if this environment cannot run the suite.

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:integrationTest :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd bench --config benchmarks/cfd/suites/smoke.json
```

## Implementation prompt

```text
Implement benchmarks/cfd phase 09 only. Add isolated JMH comparisons over the existing solver/backends, deterministic state preparation, full-field correctness gates outside timing, effective CPU-budget matching, raw results, and concise comparison reports. Include the persistent static baseline and preserve all failures. Do not count setup or file output as solver execution, normalize by brick count, or import the policy-training harness. Run only a bounded smoke suite unless a larger campaign is separately requested, and report actual measurements without inventing results.
```
