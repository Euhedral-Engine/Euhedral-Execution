# Phase 03 - Walls, forcing, and physical units

Status: implemented. Configuration: [CONFIGURATION.md](CONFIGURATION.md).
Runtime: [SIMULATION.md](SIMULATION.md).

Dependencies: [02](02-three-dimensional-solver.md). Numerical definition: [NUMERICS.md](NUMERICS.md).

## Feature

Stationary solid geometry and body forcing support three-dimensional channel and obstacle flow with explicit physical-unit conversion.

## Components

A precomputed mask classifies fluid and solid cells and assigns stable obstacle IDs. Geometry includes boxes, spheres, finite cylinders, and domain-wall faces. Cell-center classification and halfway wall positions define the resolved domain. Geometry resolution detects empty-fluid domains and conflicting face declarations.

Link-wise halfway bounce-back handles all 18 moving directions during destination-cell gather. A solid source neighbor reflects the opposite current population at the fluid cell. Solid cells remain outside fluid collision work.

Uniform acceleration enters through the Guo force-density term. Initialization and field extraction follow the half-force conventions in the numerical model. Kernel execution uses the precomputed geometry and caller-owned scratch.

The unit resolver converts physical voxel width, timestep, density, velocity, acceleration, and kinematic viscosity into lattice parameters. Derived values include `tau`, Reynolds number, Mach number, and physical duration. The resolved configuration records each conversion and the selected timestep.

Diagnostics describe mass, density range, maximum speed/Mach, and finite-state validity. In-kernel checks and optional full-field analysis have separate costs. `scenes/forced-channel.json` provides a body-force-driven channel, and a periodic obstacle fixture exercises three-dimensional solids.

## Verification

Coverage includes stationary-fluid preservation, closed/periodic mass conservation, diagonal bounce-back, reversed acceleration, zero-force equivalence, and force-aware initialization and extraction.

Planar Poiseuille flow in a three-dimensional domain has the reference profile `u(y)=a*y*(H-y)/(2*nu)`, measured from the halfway wall planes. Bounded convergence checks cover multiple viscosities and resolutions. Unit tests cover physical/lattice round trips, preserved Reynolds number under refinement, overflow, and invalid inputs.

The same channel and wall definitions feed the [OpenLB comparison](07-external-solver-validation.md).

## Interface

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/forced-channel.json --backend serial
```
