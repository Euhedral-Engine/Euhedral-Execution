# Phase 02 - Three-dimensional solver

Dependencies: [01](01-module-and-configuration.md). Numerical definition: [NUMERICS.md](NUMERICS.md).

## Feature

The serial application evolves a three-dimensional periodic fluid field using D3Q19 BGK lattice Boltzmann.

## Components

`D3Q19` defines the direction, opposite, and weight tables. `PopulationGrid` owns two sets of 19 flat double arrays. `SimulationState` tracks the current buffer and completed time. `StepContext` describes one generation, and `LbmKernel` updates an explicit rectangular destination range.

The fused kernel gathers from the immutable current buffer, computes local density and velocity, performs collision, and writes post-collision populations to the next buffer. X-fastest addressing and periodic wrapping cover axis and diagonal neighbors. Caller-owned scratch holds temporary directional values.

The serial driver initially assigns the complete volume to the range kernel. Successful completion advances the buffer and time; numerical failure retains the previous completed state. The field extractor derives macroscopic values from stored post-collision populations. Kernel inputs describe the numerical problem independently of execution-framework types.

`simulate --config --backend serial` advances a finite number of steps and emits final diagnostics. A periodic shear fixture has `u_x = A*sin(ky*y)*cos(kz*z)` and zero transverse velocity, exercising variation in both Y and Z. This phase's supported physics is unforced periodic flow.

## Verification

Tests cover stencil moments, opposite symmetry, equilibrium mass/momentum, constant-state preservation, and propagation along all 18 moving directions. Non-cubic domains exercise periodic faces, edges, and corners.

A separate tiny-grid streaming/collision reference compares complete populations after one and multiple steps. Analytical shear decay follows `exp(-nu*(ky^2+kz^2)*t)` with resolution-aware tolerances and refinement checks. Invalid density and non-finite populations carry cell and step context.

These local checks also supply fixtures for the [external-reference phase](07-external-solver-validation.md).

## Interface

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-smoke.json --backend serial
```
