# Phase 04 - Open boundaries and obstacle forces

Prerequisite: 03. Read [NUMERICS.md](NUMERICS.md) and its Hecht/Harting reference before implementing boundary equations.

## Deliverable

A full 3D inlet-to-outlet simulation around stationary obstacles, with quantitative force and flow diagnostics.

## Changes

- Implement axis-aligned prescribed-velocity inlet and prescribed-density/pressure outlet conditions using the local D3Q19 on-site reconstruction. Translate direction indices explicitly from the reference to this solver's table. Do not paste D2Q9 formulas or replace missing populations with equilibrium as an undocumented approximation.
- Classify missing distributions during gather, reconstruct them before computing collision, and ensure every destination population is assigned exactly once. Do not let boundary code read partially updated next-buffer cells.
- Define supported face combinations and intersection ownership. Wall nodes at inlet/outlet perimeters remain solid; adjacent fluid links and corner/edge cases need an explicit tested rule. Reject unsupported intersecting open-face combinations. Provide orientation coverage rather than assuming flow is always along X.
- Initially disallow body force in open-boundary cases; periodic/walled forced cases remain supported. A force-aware inlet/outlet extension is outside this phase.
- Add optional finite-time inlet ramping as part of the physics configuration, independent of backend. Report its duration and exclude unsupported backflow-dominated cases from default scenes. A fixed-pressure outlet is not advertised as reflection-free.
- Accumulate stationary-solid momentum-exchange force per obstacle. Use private range/brick partials, count every reflected link once, and reduce in stable ID order. No atomics per surface link.
- Export mass, density range, actual maximum Mach, inlet/outlet flux, force vector, and drag coefficient where explicit reference velocity/area are provided. Distinguish physical mass conservation from net inflow/outflow; a through-flow domain does not have a zero mass-change requirement at every transient step.
- Add `scenes/duct-obstacle.json` and `scenes/sphere-wake.json`, with small smoke variants. Use conservative low-Mach laminar defaults and document resolution, blockage, reference length/area, and operating limits. Do not tune a scene merely to create attractive turbulence.

## Acceptance

Verify uniform through-flow and known channel behavior, inlet velocity and outlet density reconstruction, all supported face orientations, and inlet/wall intersections. Include a case whose brick boundary will later cut through an inlet or obstacle.

Check zero net force for a symmetric resting-fluid fixture, positive downstream drag under forward flow, sign reversal when flow is reversed, and consistent attribution for two obstacles. Compare the discrete mass change with the flux implied by boundary updates; label any separate macroscopic flux estimate accordingly.

Complete a bounded simulation without non-finite state or unexplained mass growth. Quantitative curved-body drag accuracy requires resolution/domain checks; this phase must not label its approximate drag as an industrial reference result.

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend serial
```

## Implementation prompt

```text
Implement benchmarks/cfd phase 04 only. Add properly sourced D3Q19 velocity/pressure boundaries, complete ownership rules at their intersections with walls, obstacle momentum-exchange forces, and runnable inlet-to-outlet scenes. Keep all writes destination-owned and all reductions deterministic. Preserve the explicit restriction on forced open-boundary cases. Validate flow, boundary moments, mass balance, and force direction rather than relying on a picture. Do not modify scheduler internals or claim validated high-Reynolds turbulence.
```
