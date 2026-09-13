# CFD system and implementation phases

Status: planned.

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

Serial, ForkJoinPool, persistent static workers, and Euhedral execute the same kernel over the same destination-owned bricks. The driver owns generation publication, completion, diagnostics, and buffer swaps. Preallocated population arrays, brick descriptors, and work wrappers support repeated timesteps.

## Numerical validation

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

| Phase | Feature | Dependencies |
| --- | --- | --- |
| [01](01-module-and-configuration.md) | Module, configuration, CLI inspection, memory budget | None |
| [02](02-three-dimensional-solver.md) | Three-dimensional periodic D3Q19 solver | 01 |
| [03](03-walls-forcing-and-units.md) | Solid geometry, walls, forcing, physical units | 02 |
| [04](04-open-boundaries-and-forces.md) | Inlet/outlet flow and obstacle forces | 03 |
| [05](05-visualization-and-simulation-cli.md) | Simulation workflow and ParaView output | 04 |
| [06](06-stl-import-and-voxelization.md) | CAD-exported STL geometry | 05 |
| [07](07-external-solver-validation.md) | OpenLB reference execution and numerical comparison | 05, 06 |
| [08](08-parallel-execution-backends.md) | Tiled serial, ForkJoinPool, and static-worker execution | 04 |
| [09](09-euhedral-execution-backend.md) | Persistent Euhedral sources and reusable brick frames | 08 |
| [10](10-jmh-and-comparison-runner.md) | Validation-gated JMH comparisons and reports | 07, 09 |
| [11](11-locality-and-sweep-automation.md) | Locality, granularity, and scaling experiments | 10 |

The geometry/reference and execution tracks branch after phase 04 and converge in phase 10. The serial simulation is usable before parallel integration. Scored performance results are associated with independently checked numerical behavior.

## Application interfaces

The planned distribution provides `inspect`, `simulate`, `validate`, and `bench` commands. Configuration and validation fixtures describe the physical problem; execution settings describe worker selection, brick shape, source count, and placement. Output includes resolved settings, reference-comparison reports, simulation fields, raw benchmark results, and per-fork summaries.

A failed timestep retains the last completed state. Missing reference results, incompatible cases, failed numerical checks, and execution failures appear as distinct report states. The performance report ranks comparable, numerically verified runs.
