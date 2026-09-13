# Phase 01 - Module and configuration

Prerequisites: none. Read [master plan](README.md), [repository map](REPOSITORY_MAP.md), and the storage/unit sections of [NUMERICS.md](NUMERICS.md).

## Deliverable

A separately buildable `:benchmarks:cfd` application that validates a 3D scene and reports its resolved lattice parameters and memory requirements without allocating the full simulation.

## Changes

- Add `include(":benchmarks:cfd")` to root `settings.gradle.kts`. Add `benchmarks/cfd/build.gradle.kts` with `buildlogic.java-conventions` and Gradle's application plugin. Set application name `euhedral-cfd` and main class `io.euhedral_execution.benchmarks.cfd.CfdMain`.
- Reuse existing catalog aliases for Jackson and JUnit. Add engine/JMH dependencies only when needed by later phases. Do not depend on `:benchmarks` or modify its runner/manifest. Suppress benchmark-module publishing/signing tasks locally.
- Implement immutable `SimulationConfig`, `GridShape`, and resolved-configuration records. Separate physics, geometry, execution, and output settings. Validate unknown JSON fields, dimensions, finite values, conflicting parameter modes, and invalid ranges.
- Define configuration `schemaVersion: 1`, explicit lattice versus physical units, dimensions, density reference, viscosity, initial velocity, acceleration, face conditions, step count, timeouts, brick dimensions, and output cadence. Resolve absent optional sections to documented defaults; do not silently ignore requested features.
- Add `inspect --config <file>` and `--help`. Inspection prints cells, population bytes, estimated additional memory, indexability, unit mode, resolved `tau`, reference Mach/Reynolds values where defined, and unsupported feature combinations.
- Use checked long arithmetic before any allocation. Keep exact population bytes separate from estimated JVM/geometry overhead. Allow an explicit memory limit and a conservative default based on available heap; explain rejection rather than catching OOM and retrying smaller grids.
- Add a tiny fully 3D `scenes/periodic-smoke.json`. Keep scene paths relative to the declaring JSON file. The inspect command must not create a lattice, load engine workers, run physics, or overwrite output.
- Add a module-local `.gitignore` for generated runs if needed. Preserve all existing benchmark outputs.

## Acceptance

From the repository root, the implementation must support:

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd inspect --config benchmarks/cfd/scenes/periodic-smoke.json
```

Tests cover overflow, unsupported Java array sizes, negative/non-finite inputs, unknown JSON fields, deterministic defaults, path resolution, and memory rejection without allocation. Verify the application distribution and `--help`. No numerical result or speedup is expected in this phase.

## Implementation prompt

```text
Implement benchmarks/cfd phase 01 only. Read README.md, REPOSITORY_MAP.md, this phase, and the relevant NUMERICS.md sections. Create the nested Gradle application and strict configuration/inspection path using the repository toolchain and conventions. Keep all CFD files beneath benchmarks/cfd except root project inclusion. Do not add a solver placeholder, start Euhedral, alter production modules, or run benchmarks. Test configuration and memory preflight, verify the installed CLI, and report actual commands and results.
```
