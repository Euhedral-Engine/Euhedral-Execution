# Open-boundary scenes

All four scenes use D3Q19 BGK, lattice viscosity 0.1, reference density 1, zero initial velocity,
zero body acceleration, X-min velocity inlet, X-max density-1 outlet, and a linear inlet ramp.
The velocity and density guards retain their defaults (Mach 0.1, relative density variation 0.1).
Lengths, areas, speeds, and times below are in lattice units. Each obstacle uses ID 1.

| Scene                                           | Grid     | Obstacle                              | Reference speed | Reference length / area |   Re | Ramp / steps |
|-------------------------------------------------|----------|---------------------------------------|----------------:|-------------------------|-----:|--------------|
| [duct-obstacle](duct-obstacle.json)             | 48x16x16 | cube, side 4, center (16,8,8)         |            0.01 | 4 / 16                  |  0.4 | 200 / 1000   |
| [duct-obstacle-smoke](duct-obstacle-smoke.json) | 16x8x8   | cube, side 2, center (16/3,4,4)       |           0.003 | 2 / 4                   | 0.06 | 20 / 100     |
| [sphere-wake](sphere-wake.json)                 | 64x24x24 | sphere, radius 3, center (64/3,12,12) |            0.02 | 6 / 9*pi                |  1.2 | 200 / 1000   |
| [sphere-wake-smoke](sphere-wake-smoke.json)     | 20x10x10 | sphere, radius 1.5, center (20/3,5,5) |           0.006 | 3 / 2.25*pi             | 0.18 | 20 / 100     |

The duct's Y/Z boundary layers are solid, with effective wall planes at 1 and N-1.
Its nominal area blockage is 16/196 = 8.16%; the smoke variant is 4/36 = 11.11%.
Sphere cases have periodic transverse faces and thus represent transverse arrays of obstacles,
with nominal projected area fractions 9 *pi/576 = 4.91% and 2.25*pi/100 = 7.07%.
Inlet/outlet planes are at X=0.5 and X=nx-0.5. These are bounded laminar demonstrations;
`sphere-wake` describes the downstream disturbance and does not claim vortex shedding or a
converged isolated-sphere drag law. Smoke variants test execution and are too coarse for force
accuracy claims. The full scenes have not been externally validated.

`physics.forceReference` fixes the speed/area/density above and +X direction, independently of the
ramp. `physics.referenceLength` uses the obstacle diameter/side; inspection's initial Reynolds is
zero because initialization is at rest. The table reports Reynolds at the prescribed inlet speed.

## Resolution and domain checks

`ObstacleForcesTest` compares a radius-2 centered voxel sphere on a 24x12x12 open/periodic domain
with its scale-2 version on 48x24x24. Speed changes from 0.006 to 0.003, viscosity stays 0.1, and
time changes from 400 to 1600 updates: fixed Re=0.24 and matched diffusive duration. Both begin
with uniform through-flow and no ramp. Reference area is pi*r^2. The local Cd values are
192.614310 and 197.601345 (2.59% difference; regression bound 10%). This is finite-domain,
finite-time resolution sensitivity, including the voxel surface and on-site plane placement.

Keeping the coarse sphere and duration but widening the transverse periodic domain from 12x12
to 16x16 gives Cd=163.807862, about 15% lower. This demonstrates substantial domain sensitivity;
these values are not isolated-sphere reference coefficients. The tests check the sign of this
change and discrete mass balance in every run.

A separate centered-sphere volume check measures voxelization error relative to 4 *pi*r^3/3:
radius 2 has 32 solid voxels (4.51% error), and radius 8 has 2176 (1.46% error). This quantifies
curved-mask resolution error independently of flow. Force agreement with an external solver
belongs to [Phase 07](../07-external-solver-validation.md).
