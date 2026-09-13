# Phase 03 - Walls, forcing, and physical units

Prerequisite: 02. Read [NUMERICS.md](NUMERICS.md), including initialization and field extraction with force.

## Deliverable

Real 3D flow through primitive solid geometry, including a body-force-driven channel with a verifiable velocity profile and explicit physical-unit conversion.

## Changes

- Add a geometry mask with fluid/solid classification and stable obstacle IDs. Implement deterministic box, sphere, and finite cylinder primitives, plus domain-wall faces. Classify cell centers consistently and document the effective halfway wall location.
- Resolve geometry once before execution. Detect empty-fluid domains and invalid/overlapping face declarations. Do not run geometry predicates or allocate geometry objects during a timestep.
- Implement link-wise halfway bounce-back inside the destination-cell gather. A solid source neighbor reflects the opposite population from the current fluid cell; solids are not updated as fluid. Handle all 18 links, not just the six axis neighbors.
- Add uniform acceleration through the Guo force-density term. Respect the half-force initialization and the different signs for velocity extraction from pre- and post-collision populations. Do not add a second force increment elsewhere in the driver.
- Implement the physical-to-lattice resolver from NUMERICS.md. Accept either lattice configuration or explicit physical units. Where `dt` is derived, print its derivation and resolved viscosity/Mach values; reject inconsistent or unsupported inputs rather than silently changing viscosity.
- Add step diagnostics for valid density, finite values, maximum speed/Mach, and mass. Keep diagnostics backend-neutral and distinguish inexpensive in-kernel checks from optional full-field analysis.
- Add `scenes/forced-channel.json` and a small primitive-obstacle periodic case. These are fully 3D domains; planar analytical flow is a validation fixture, not a separate solver.
- Add output metadata for the physical voxel width, timestep, reference density, viscosity, and force convention. No claimed turbulence model or free surface.

## Acceptance

Check that a stationary closed/periodic fluid remains at rest with symmetric obstacles, bounce-back prevents normal penetration, and diagonal links cannot pass through solid cells. Verify mass conservation over multiple steps in closed and periodic cases.

For planar Poiseuille flow, compare the steady velocity against `u(y)=a*y*(H-y)/(2*nu)` using distances from the halfway wall planes. Use a deterministic convergence criterion and bounded maximum steps. Verify at more than one viscosity and with acceleration reversed; a half-force-sign error must fail a regression test.

Tests must cover zero-force equivalence to phase 02, physical/lattice unit round trips, preservation of Reynolds number during resolution changes, and overflow/invalid physical inputs. Tolerances belong in test fixtures with a rationale, not widened automatically until a result passes.

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/forced-channel.json --backend serial
```

Keep the default fixture small. Larger convergence studies should be explicit integration tests, not mandatory long jobs on every build.

## Implementation prompt

```text
Implement benchmarks/cfd phase 03 only. Add primitive 3D solids, complete D3Q19 halfway bounce-back, Guo forcing, physical-unit resolution, and a working forced-channel scene. Preserve the shared numerical kernel and population-time convention. Validate against analytical channel flow and force-aware initialization/extraction, including diagonal wall links. Do not add a turbulence model, change Euhedral, or run a broad benchmark campaign. Report actual tests and the runnable scene command.
```
