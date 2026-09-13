# Phase 02 - Three-dimensional solver

Status: implemented. See [SIMULATION.md](SIMULATION.md) for the current interface and verification
details.

Dependencies: [01](01-module-and-configuration.md). Numerical definition: [NUMERICS.md](NUMERICS.md).

## Feature

The serial application evolves a three-dimensional periodic fluid field using D3Q19 BGK lattice Boltzmann.

## Components

`D3Q19` defines the direction, opposite, and weight tables. `PopulationGrid` owns two sets of 19
flat double arrays. `SimulationState` tracks the current buffer and completed time. `StepContext`
describes one generation, and `CfdRangeFrame` directly implements pull/collide over replaceable
primitive destination bounds, retaining its private scratch across reuse.

The fused kernel gathers from the immutable current buffer, computes local density and velocity,
performs collision, and writes post-collision populations to the next buffer. X-fastest addressing
and periodic wrapping cover axis and diagonal neighbors. Frame-owned scratch holds temporary
directional values.

Population allocation and initialization happen once in ordinary setup. `CfdRangeFrame` extends
`AbstractFrame` and carries the generation context, six replaceable bounds, and reusable scratch for
pull/collide work. A `QueueIngestSink` feeds the lattice; its standard terminal invokes the frame's
body and completion hooks. The serial driver offers one whole-volume frame per step with unchanged
identity and routing hashes, keeping execution on one ordered lane. After successful range
completion,
the driver validates completed fields and advances the buffer and time; failures retain the previous
state.
Disjoint ranges can share a context for later parallel execution. Numerical helpers remain
independent of execution-framework types.

`simulate --config --backend serial` advances a finite number of steps and emits final diagnostics. A periodic shear fixture has `u_x = A*sin(ky*y)*cos(kz*z)` and zero transverse velocity, exercising variation in both Y and Z. This phase's supported physics is unforced periodic flow.

## Verification

Tests cover stencil moments, opposite symmetry, equilibrium mass/momentum, constant-state preservation, and propagation along all 18 moving directions. Non-cubic domains exercise periodic faces, edges, and corners.

A separate tiny-grid streaming/collision reference compares complete populations after one and multiple steps. Analytical shear decay follows `exp(-nu*(ky^2+kz^2)*t)` with resolution-aware tolerances and refinement checks. Invalid density and non-finite populations carry cell and step context.

These local checks also supply fixtures for the [external-reference phase](07-external-solver-validation.md).

## Interface

```bash
mise exec -- gradle :benchmarks:cfd:build
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-smoke.json --backend serial
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-shear.json --backend serial
```
