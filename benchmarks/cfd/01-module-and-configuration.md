# Phase 01 - Module and configuration

Status: implemented. The concrete schema and defaults are documented
in [CONFIGURATION.md](CONFIGURATION.md).

Dependencies: none. Related components: [system overview](README.md), [repository integration](REPOSITORY_MAP.md), [numerical model](NUMERICS.md).

## Feature

A separately buildable `:benchmarks:cfd` application describes and validates a three-dimensional simulation before population allocation.

## Components

The Gradle application uses Java 21 and the repository's shared conventions and catalog aliases. Its distribution is `euhedral-cfd`, with entry point `io.euhedral_execution.benchmarks.cfd.CfdMain`. Root project registration includes the nested module; the application owns its packaging and dependencies.

Immutable `SimulationConfig`, `GridShape`, and resolved-configuration records separate physics, geometry, execution, and output. Schema version 1 describes dimensions, density reference, viscosity, initial velocity, acceleration, face conditions, duration, deadlines, brick dimensions, and export cadence. Lattice-unit and physical-unit configurations have explicit alternatives and documented defaults.

Strict JSON parsing identifies unknown fields, conflicting parameter modes, unsupported combinations, non-finite values, invalid dimensions, and invalid ranges. Paths resolve relative to their declaring configuration file.

Memory inspection reports exact population bytes, estimated auxiliary memory, array indexability, and available budget. Checked arithmetic precedes allocation. An explicit memory limit overrides the conservative heap-based default; an over-budget case returns a diagnostic with the requested dimensions and estimated requirement.

`inspect --config` prints cell count, resolved viscosity and `tau`, applicable Mach/Reynolds values, memory estimates, and configuration errors. Inspection performs configuration resolution independently of worker startup or numerical execution. A small fully three-dimensional `scenes/periodic-smoke.json` supplies the initial fixture.

## Verification

Coverage includes overflow, Java array limits, unknown JSON fields, deterministic defaults, non-finite inputs, relative paths, mutually exclusive parameter modes, and budget rejection before allocation. Distribution checks cover the installed launcher and command help.

## Build and interface

```bash
mise exec -- gradle :benchmarks:cfd:build
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd inspect --config benchmarks/cfd/scenes/periodic-smoke.json
```
