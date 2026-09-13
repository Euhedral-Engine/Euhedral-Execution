# Numerical model

The shared kernel uses D3Q19 BGK lattice Boltzmann with double-precision post-collision populations. Symbols are in lattice units unless explicitly marked physical.

## D3Q19 and storage

The stencil contains the rest direction, six axis directions, and twelve face diagonals:

```text
0: ( 0, 0, 0)
1: ( 1, 0, 0)   2: (-1, 0, 0)
3: ( 0, 1, 0)   4: ( 0,-1, 0)
5: ( 0, 0, 1)   6: ( 0, 0,-1)
7: ( 1, 1, 0)   8: (-1,-1, 0)
9: ( 1,-1, 0)  10: (-1, 1, 0)
11:( 1, 0, 1)  12: (-1, 0,-1)
13:( 1, 0,-1)  14: (-1, 0, 1)
15:( 0, 1, 1)  16: ( 0,-1,-1)
17:( 0, 1,-1)  18: ( 0,-1, 1)

opposite = [0,2,1,4,3,6,5,8,7,10,9,12,11,14,13,16,15,18,17]
w0 = 1/3; axis weight = 1/18; diagonal weight = 1/36
cs2 = 1/3
index = x + nx * (y + ny * z)
```

Two sets of 19 `double[]` arrays store POST-collision populations `g`. Each array has length `N = nx*ny*nz`, with X varying fastest. Neighbor locations derive from coordinates and constant stencil offsets. Counts, memory sizes, file offsets, and update counts use checked `long` arithmetic; allocation also checks direction-array indexability.

Population storage is `2*19*8*N = 304*N` bytes:

| Grid | Cells | Population storage |
| --- | ---: | ---: |
| 128^3 | 2,097,152 | 608 MiB |
| 256^3 | 16,777,216 | 4.75 GiB |
| 512^3 | 134,217,728 | 38 GiB |

Geometry, masks, obstacle IDs, descriptors, temporary exports, and JVM overhead add to this total. Memory preflight evaluates the complete configured case. Field output is streamed.

## Timestep

Each destination fluid cell gathers PRE-collision populations `f` from the immutable current buffer:

```text
f_i(x) = current_g_i(x - c_i)             # ordinary/periodic neighbor
f_i(x) = current_g_opposite(i)(x)         # stationary solid neighbor
```

Open-face reconstruction supplies missing incoming populations before collision. The local update is:

```text
rho = sum_i f_i
F = rho * acceleration                   # force density, when enabled
u = (sum_i(c_i*f_i) + F/2) / rho
feq_i = w_i*rho*(1 + 3*(c_i.u) + 4.5*(c_i.u)^2 - 1.5*(u.u))
nu = (tau - 0.5)/3
omega = 1/tau
S_i = w_i * ((c_i-u)/cs2 + ((c_i.u)*c_i)/(cs2*cs2)) . F
next_g_i = f_i - omega*(f_i-feq_i) + (1-omega/2)*S_i
```

`S_i` is the unprefactored Guo source; its relaxation prefactor appears once in the population update. Unforced cases have zero `F` and `S_i`.

Each brick writes all 19 populations for its destination cells. Source reads and destination writes remain disjoint across brick faces, edges, and corners. Successful terminal completion publishes these writes. The driver then reduces diagnostics in stable brick order, checks the completed step, swaps buffers, and increments time. A failed generation retains the previous current buffer.

## Initialization and field extraction

Unforced initialization is `g_i = feq_i(rho0,u0)`. Forced initialization is `g_i = feq_i(rho0,u0) + S_i(rho0,u0,F)/2`.

A completed POST-collision state yields:

```text
rho = sum_i g_i
F = rho * acceleration
u = (sum_i(c_i*g_i) - F/2) / rho
p_gauge = cs2 * (rho-rho0)
```

The half-force correction is positive for PRE-collision `f` and negative for stored POST-collision `g`. Initialization, diagnostics, reference-solver comparison, and export share this represented-time convention.

## Physical units and operating range

A configuration selects either lattice parameters or physical parameters. Physical voxel width `dx`, timestep `dt`, density scale `rhoScale = rhoPhysicalReference/rho0`, and kinematic viscosity `nuPhysical` determine:

```text
u_lattice = u_physical * dt/dx
nu_lattice = nu_physical * dt/(dx*dx)
a_lattice = a_physical * dt*dt/dx
Re = U_reference * L_reference / nu
Ma = max_speed / sqrt(cs2)

velocity_physical = velocity_lattice * dx/dt
pressure_gauge_physical = p_gauge_lattice * rhoScale * (dx/dt)^2
force_physical = force_lattice * rhoScale * dx^4/(dt*dt)
```

Resolved `tau` is finite and greater than 0.5. Presets target `Ma <= 0.1`; configured guards monitor actual Mach number, density variation, finite fields, and positive density. Guard violations produce a failed-run result with step and location context. This operating range describes the application's low-Mach model; accuracy is assessed through numerical comparison and refinement.

Resolution studies preserve reference geometry, Reynolds number, and physical duration while resolving `dx` and `dt` consistently.

## Boundaries and forces

Link-wise halfway bounce-back places a stationary wall halfway between fluid and solid cell centers. Analytical and external-reference cases use the same effective wall location.

Axis-aligned open faces use the local D3Q19 velocity/density reconstruction of Hecht and Harting, with explicit direction-index correspondence and intersection handling. A fixed-density outlet defines a pressure boundary. Supported combinations are forced periodic/walled cases and unforced open-boundary cases.

For a reflected link whose `c_i` points from solid toward fluid, the impulse on the stationary solid is `-2*c_i*current_g_opposite(i)(x)` per lattice timestep. Per-brick contributions attribute each reflected link once and reduce in brick-ID order. Drag is `Cd = F_parallel/(0.5*rhoReference*UReference^2*AReference)` with configured reference direction and area.

## Numerical checks

Stencil moments, equilibrium preservation, all streaming directions, periodic shear decay, forced Poiseuille flow, open-boundary moments, mass balance, and obstacle-force symmetry cover individual numerical features. Unequal dimensions and partial bricks exercise three-dimensional indexing and ownership.

[External-solver validation](07-external-solver-validation.md) compares the serial implementation with OpenLB. The comparison aligns physical coordinates, wall positions, force conventions, pressure reference, and sample times. Matching discrete methods support tighter field checks; different boundary discretizations use separately identified refinement-based comparisons. Curved voxel boundaries have geometry-dependent discretization error.

Backend equivalence compares the same Java kernel under serial, ForkJoinPool, static-worker, and Euhedral execution. External agreement supports numerical verification; physical-model validation against experiments is a separate form of evidence.

## References

- [waLBerla basic LBM tutorial](https://www.walberla.net/doxygen/tutorial_lbm01.html): 3D LBM structure.
- [Guo, Zheng and Shi, 2002](https://journals.aps.org/pre/abstract/10.1103/PhysRevE.65.046308): force treatment.
- [Hecht and Harting](https://arxiv.org/abs/0811.4593): D3Q19 on-site boundaries.
- [OpenLB](https://www.openlb.net/): external reference solver.
- [OpenLB release 1.9](https://www.openlb.net/news/openlb-release-1-9-available-for-download/): reference distribution.
- [OpenLB user guides](https://www.openlb.net/user-guide/): versioned solver documentation.
- [VTK XML format](https://docs.vtk.org/en/latest/vtk_file_formats/vtkxml_file_format.html): field export.
- [JDK ForkJoinTask](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ForkJoinTask.html): task lifecycle.
