# Phase 02 - Three-dimensional solver

Prerequisite: 01. Read [master plan](README.md) and [NUMERICS.md](NUMERICS.md), especially the post-collision population convention.

## Deliverable

A working 3D periodic D3Q19 solver with a serial CLI path and numerical regression tests. It must simulate evolving fields, not just execute a stencil-shaped CPU loop.

## Changes

- Implement `D3Q19`, `PopulationGrid`, `SimulationState`, `StepContext`, and a scheduler-independent `LbmKernel` under `solver`. These are proposed names; follow local Java conventions.
- Allocate two sets of 19 flat double arrays. Use X-fastest addressing and the explicit direction/opposite table. Keep current populations immutable throughout a step.
- Implement equilibrium initialization and fused pull-stream/collide BGK updates. This phase supports periodic faces and zero forcing. Explicitly reject other requested conditions until their owning phase is implemented.
- Give the kernel an explicit rectangular destination range and caller-owned scratch storage. The serial driver uses the whole volume initially; this contract will become the brick kernel in phase 07 without changing equations.
- Reuse a small 19-value scratch buffer or scalar locals; do not allocate vectors, streams, records, or arrays inside cell loops. Do not retain full-domain velocity/pressure arrays merely for diagnostics.
- The driver swaps population references only after a successful step, increments a long step counter, and exposes a completed-state field extractor. Periodic wrapping must be correct for all axis and diagonal directions.
- Add `simulate --config <file> --backend serial` with a finite step count and final numerical summary. No visualization is needed yet. Add a low-amplitude periodic shear scene that varies across Y and Z, so indexing is exercised in three dimensions.
- Keep the numerical package free of Euhedral, Reactor, JMH, affinity calls, and backend-dependent arithmetic.

## Acceptance

Unit tests must check weight sums, opposite symmetry, first/second stencil moments, equilibrium mass/momentum, constant equilibrium preservation, and propagation through all 18 non-rest directions. Use non-cubic domains, including periodic boundaries at faces, edges, and corners.

Implement an independent tiny-grid test reference with separate streaming and collision steps. Compare complete population arrays after one and many steps, not just aggregate density. This reference may allocate because it is test-only; it must not simply call the production kernel.

For the periodic shear scene use a small amplitude such as `u_x = A*sin(ky*y)*cos(kz*z)` with `u_y=u_z=0`; compare the measured decay against `exp(-nu*(ky^2+kz^2)*t)`. Choose documented resolution-aware tolerances and show improvement with refinement. Do not confuse floating-point identity with continuum discretization error.

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-smoke.json --backend serial
```

A bad density or non-finite population fails the run with cell/step context. A failed step does not become the current state.

## Implementation prompt

```text
Implement benchmarks/cfd phase 02 only on top of phase 01. Follow NUMERICS.md exactly, including storing post-collision g populations. Build the actual 3D periodic BGK solver and serial simulate command. Separate the pure numerical range kernel from the driver. Add an independent tiny-grid reference and analytical shear-decay coverage; do not substitute scheduler comparisons for numerical validation. Do not add open boundaries, STL, visualization, Euhedral integration, or performance claims. Run focused tests and a tiny real simulation, then report results.
```
