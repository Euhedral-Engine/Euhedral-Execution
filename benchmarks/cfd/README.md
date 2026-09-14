# CFD system and implementation phases

Status: Phases 01-08 implemented; Phases 10-11 planned. Phase 09 is incorporated into
Phase 08.

The current application provides `inspect --config` for configuration resolution and memory
preflight, and `simulate --config --backend serial|euhedral|fjp|static` for periodic/walled forced flow or unforced
inlet/outlet flow, with stationary solids and obstacle-force diagnostics. See
[CONFIGURATION.md](CONFIGURATION.md) for settings and [SIMULATION.md](SIMULATION.md) for the solver,
commands, numerical checks, and current limits. [Scene metadata](scenes/README.md) records the
open-boundary cases and smoke variants. [VISUALIZATION.md](VISUALIZATION.md) covers CLI overrides,
run artifacts, and ParaView field export. [STL.md](STL.md) covers CAD mesh import, conservative
voxelization, and detailed inspection. Mesh intersection checks and voxel ranges run in parallel
on Euhedral using reusable specialized frames. Numerical updates use the same reusable range frames across all four backends.

The CFD application simulates three-dimensional flow around stationary geometry and compares the execution time of interchangeable CPU backends. Built-in scenes and CAD-exported STL files become voxelized fluid domains, and completed simulations produce velocity, pressure, and obstacle-force data for ParaView.

```text
scene or STL geometry
    -> voxelized domain and resolved physical parameters
    -> 3D fluid simulation
    -> velocity, pressure, and obstacle forces
    -> ParaView time series
```

## System architecture

The Gradle application `:benchmarks:cfd` resides in `benchmarks/cfd`. Its Java package is `io.euhedral_execution.benchmarks.cfd`, with configuration, geometry, solver, execution, output, validation, and benchmark components.

The shared numerical kernel uses double-precision D3Q19 BGK lattice Boltzmann, two population buffers, and a fused pull-stream/collide update. The physical model is isothermal, single-phase, low-Mach flow through stationary geometry. [NUMERICS.md](NUMERICS.md) describes its equations, units, storage, and boundaries. [REPOSITORY_MAP.md](REPOSITORY_MAP.md) describes the build and runtime integration points.

Serial, ForkJoinPool, persistent static workers, and parallel Euhedral execute the same numerical
body over the same destination-owned ranges. The driver owns generation publication, completion,
diagnostics, and buffer swaps. Reusable frames retain their primitive range bounds and scratch;
separate brick descriptor objects are optional. Serial and parallel Euhedral execution share the
existing lattice path, using ordered and mixed routing hashes respectively.

Parallel Euhedral uses a configurable number of persistent ingest sinks, with one per effective
worker by default and an explicit single-source setting for the FJP comparison. One driver submits
frames round-robin across sinks. Euhedral handles source acquisition and work distribution
internally.

## Numerical validation

See [VALIDATION.md](VALIDATION.md) for the pinned OpenLB setup, runnable suites, artifact contract, and currently unverified open-boundary/convergence cases.

OpenLB supplies independently computed reference fields for matched physical cases. The validation system compares velocity, gauge pressure, density, flow balance, and obstacle forces at aligned locations and times. Analytical solutions supplement the solver comparison for equilibrium, shear decay, and channel flow.

```text
matched case specification
    +-> OpenLB reference solution
    +-> serial CFD solution
              |
       field and observable comparison
              |
       verified serial implementation
              |
       FJP / static / Euhedral equivalence
              |
       scored performance comparison
```

A validation report identifies the reference release, numerical-method correspondence, comparison metrics, tolerances, and result. Benchmark eligibility combines a passing external-reference suite, backend equivalence, and valid runtime diagnostics. [Phase 07](07-external-solver-validation.md) defines this workflow.

## Phases

| Phase                                        | Feature                                                                                            | Dependencies |
|----------------------------------------------|----------------------------------------------------------------------------------------------------|--------------|
| [01](01-module-and-configuration.md)         | Module, configuration, CLI inspection, memory budget                                               | None         |
| [02](02-three-dimensional-solver.md)         | Three-dimensional periodic D3Q19 solver                                                            | 01           |
| [03](03-walls-forcing-and-units.md)          | Solid geometry, walls, forcing, physical units                                                     | 02           |
| [04](04-open-boundaries-and-forces.md)       | Inlet/outlet flow and obstacle forces                                                              | 03           |
| [05](05-visualization-and-simulation-cli.md) | Simulation workflow and ParaView output                                                            | 04           |
| [06](06-stl-import-and-voxelization.md)      | CAD-exported STL geometry                                                                          | 05           |
| [07](07-external-solver-validation.md)       | OpenLB reference execution and numerical comparison                                                | 05, 06       |
| [08](08-parallel-execution-backends.md)      | Independent ranges, serial/parallel Euhedral, FJP, static workers, and configurable ingest sources | 04           |
| [10](10-jmh-and-comparison-runner.md)        | Validation-gated JMH comparisons and reports                                                       | 07, 08       |
| [11](11-locality-and-sweep-automation.md)    | Locality, granularity, and scaling experiments                                                     | 10           |

[Phase 09](09-euhedral-execution-backend.md) is incorporated into Phase 08; subsequent phase numbers
are retained.

The geometry/reference and execution tracks branch after phase 04 and converge in phase 10. The serial simulation is usable before parallel integration. Scored performance results are associated with independently checked numerical behavior.

## Application interfaces

The distribution provides `inspect`, `simulate`, and `validate`; `bench` is planned. Configuration and validation fixtures describe the physical problem; execution settings describe worker selection, brick shape, source count, and placement. Output includes resolved settings, reference-comparison reports, simulation fields, raw benchmark results, and per-fork summaries.

A failed timestep retains the last completed state. Missing reference results, incompatible cases, failed numerical checks, and execution failures appear as distinct report states. The performance report ranks comparable, numerically verified runs.
