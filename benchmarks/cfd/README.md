# CFD implementation master plan

Status: planned. This directory contains implementation plans, not a completed solver or benchmark results.

Build a real, headless 3D fluid simulator and use it to compare execution backends. Start in 3D; small test grids are the same solver, not a separate 2D implementation. The intended workflow is:

```text
built-in scene or watertight STL geometry
    -> voxelize and resolve physical parameters
    -> advance a 3D fluid field
    -> export velocity, pressure, and obstacle forces
    -> inspect in ParaView
```

## Scope and architecture

Create the Gradle subproject `:benchmarks:cfd`, rooted here. Use package `io.euhedral_execution.benchmarks.cfd` with `config`, `geometry`, `solver`, `execution`, `io`, and `benchmark` subpackages as needed. Keep the numerical solver independent of Euhedral classes; adapters own runtime integration. Do not depend on the parent `:benchmarks` artifact or its calibration harness.

The numerical method is D3Q19, single-relaxation-time BGK lattice Boltzmann with double-precision populations, two population buffers, and a fused pull-stream/collide update. It is an isothermal, weakly compressible method used in its low-Mach regime to approximate incompressible flow. The shared equations and population-time convention are in [NUMERICS.md](NUMERICS.md).

Required capabilities: stationary 3D primitive and imported geometry, no-slip walls, periodic faces, velocity inlet, pressure outlet, viscosity and unit conversion, transient flow, obstacle force/drag, VTI/PVD export, and interchangeable serial, ForkJoinPool, static-worker, and Euhedral execution.

Not included: a CAD editor, free surfaces/splashing, multiple fluids, heat transfer, combustion, moving solids, compressible aerodynamics, adaptive meshes, GPU/MPI execution, or industrial validation. Visible vortices do not establish a validated turbulence model. Do not expand this scope while implementing a phase.

## Feature-sized phases

Each phase file contains its context, changes, acceptance checks, and a paste-ready implementation prompt. Complete prerequisites before starting a dependent phase. Implementation class names below are proposed, not existing APIs.

| Phase | Feature | Depends on |
| --- | --- | --- |
| [01](01-module-and-configuration.md) | Module, CLI inspection, configuration, memory budget | None |
| [02](02-three-dimensional-solver.md) | Actual 3D periodic D3Q19 solver | 01 |
| [03](03-walls-forcing-and-units.md) | Solid geometry, no-slip walls, forcing, physical units | 02 |
| [04](04-open-boundaries-and-forces.md) | Inlet/outlet, obstacle forces, complete flow scenes | 03 |
| [05](05-visualization-and-simulation-cli.md) | Runnable simulation workflow and ParaView output | 04 |
| [06](06-stl-import-and-voxelization.md) | CAD-exported STL geometry | 05 |
| [07](07-parallel-execution-backends.md) | Tiled execution, ForkJoinPool, static-worker baseline | 04 |
| [08](08-euhedral-execution-backend.md) | Persistent Euhedral sources and reusable brick frames | 07 |
| [09](09-jmh-and-comparison-runner.md) | Correctness-gated JMH comparisons and reports | 05, 06, 08 |
| [10](10-locality-and-sweep-automation.md) | Measured locality/granularity tuning and automated sweeps | 09 |

Phases 05-06 and 07-08 can progress independently after 04, but share the solver contracts. Serial flow is usable before scheduler integration; all backends are comparable before optional tuning.

## Non-negotiable contracts

- All backends call the same numerical kernel on the same cells, using the same precision, boundaries, and diagnostics. Change scheduling, not physics.
- The driver owns buffer swaps. A timestep succeeds only after every brick has finished successfully and published its writes. Queue emptiness is not completion.
- No worker waits for another brick on the same bounded worker pool. No next-generation reads from partially written state.
- No per-cell objects, locks, atomics, logging, or allocation. Preallocate brick descriptors and reusable work wrappers; bounded per-step coordination is acceptable and measured.
- Preserve the existing engine, policy, calibration tools, and benchmark outputs. Euhedral does not use DRR; do not design against old architecture descriptions.
- Do not claim scheduler speedups in advance. A static bulk-synchronous loop is a required baseline, not just task frameworks.
- Stop failed or unstable runs with a useful error. Never silently clamp numerical problems, swap a failed grid, or label a partial run successful.
- No checksum registry, model tournament, policy-training integration, or elaborate provenance system. Keep the resolved configuration, source revision, timings, and correctness results.

## Working instructions

Read [REPOSITORY_MAP.md](REPOSITORY_MAP.md), the repository's root `AGENTS.md`, and the relevant sections of `benchmarks/AGENTS.md`. Code is authoritative where those guides disagree. These plans were grounded in main commit `3a4f5e8e46709b193b36cca61c021d313e4ccf2c`; recheck actual signatures when implementing.

Commands in phase files are implementation acceptance targets. They are not available merely because these plans exist. Use `mise exec -- gradle`; the repository uses a system-managed Gradle toolchain, not an assumed wrapper. Do not run large benchmark campaigns as a side effect of implementing a phase.

For each completed phase, report changed files, actual commands and results, remaining limitations, and a command that demonstrates the new feature. Keep phase results separate from performance claims.
