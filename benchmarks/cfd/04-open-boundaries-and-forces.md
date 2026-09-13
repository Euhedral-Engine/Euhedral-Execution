# Phase 04 - Open boundaries and obstacle forces

Dependencies: [03](03-walls-forcing-and-units.md). Boundary and force definitions: [NUMERICS.md](NUMERICS.md).

## Feature

Prescribed inlet and outlet conditions drive three-dimensional flow around stationary obstacles, with quantitative flow and force diagnostics.

## Components

Axis-aligned velocity inlets and density/pressure outlets use local D3Q19 on-site reconstruction. The boundary tables map the published Hecht/Harting directions to the solver's stencil. Gather classifies missing incoming distributions and reconstructs them before local collision.

Face configuration describes supported orientations, wall intersections, and ownership of each population. Perimeter wall cells remain solid; adjacent fluid links have explicit edge and corner treatment. Unsupported intersecting open-face combinations produce configuration errors. Open-boundary cases use zero body force; periodic and walled configurations retain forced-flow support.

An optional finite-time inlet ramp is part of the physical case. Its duration and boundary convention appear in resolved output. A fixed-density outlet represents a prescribed pressure boundary.

Momentum exchange accumulates obstacle forces in private range/brick slots. Each reflected link contributes once to its obstacle, and reductions follow stable brick-ID order. Reported quantities include vector force and drag coefficient with configured reference velocity, area, density, and direction.

Transient diagnostics cover mass, density range, maximum Mach number, inlet/outlet flux, and obstacle forces. Discrete mass change is paired with boundary-update flux; a separately estimated macroscopic flux is labeled accordingly.

`scenes/duct-obstacle.json` and `scenes/sphere-wake.json` provide low-Mach laminar scenes and small smoke variants. Metadata describes resolution, blockage, reference dimensions, and operating range.

## Verification

Uniform through-flow and channel cases cover reconstructed velocity/density, supported face orientations, and inlet/wall intersections. Boundary and obstacle locations cross future brick partitions.

Force checks cover symmetric resting fluid, downstream drag, reversed-flow sign, and two-obstacle attribution. Bounded runs exercise stability and mass balance. Resolution and domain checks quantify curved-boundary error. These observables also enter the [external-reference comparison](07-external-solver-validation.md).

## Interface

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend serial
```
