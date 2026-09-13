# Shared numerical contract

These are design decisions for the new solver, not claims about existing repository code. Implement every backend against this contract. Symbols below are in lattice units unless explicitly marked physical.

## D3Q19 and storage

Use the rest direction, six axis directions, and twelve face diagonals. Fix one order and its opposite table:

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

Store two sets of 19 `double[]` arrays of length `N = nx*ny*nz`. They hold POST-collision populations `g`, not pre-collision populations `f`. X varies fastest. There is no object per cell and no full `19*N` neighbor-index table.

Use checked `long` arithmetic for counts, byte budgets, output offsets, and work counts. Each direction array must fit Java's supported array indexing. Resolve sizes before allocation; do not fall back to a smaller grid silently.

Population storage alone is `2*19*8*N = 304*N` bytes:

| Grid | Cells | Population storage |
| --- | ---: | ---: |
| 128^3 | 2,097,152 | 608 MiB |
| 256^3 | 16,777,216 | 4.75 GiB |
| 512^3 | 134,217,728 | 38 GiB |

Masks, obstacle IDs, descriptors, temporary exports, JVM overhead, and geometry are additional. Stream output rather than retaining every frame. Large cases require explicit memory preflight; 512^3 is not a default demo.

## One successful timestep

For each destination fluid cell `x`:

```text
f_i(x) = current_g_i(x - c_i)             # ordinary/periodic neighbor
f_i(x) = current_g_opposite(i)(x)         # stationary solid neighbor
```

Open-face missing populations use the boundary reconstruction from phase 04. They must never be read from arbitrary out-of-domain memory. Then compute:

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

`S_i` is the unprefactored Guo source. Apply its relaxation prefactor exactly once. With no force, `F` and `S_i` are zero. Every task writes all 19 values only for its destination cells. Reading current and writing next are disjoint operations, even across brick faces, edges, and corners.

After all bricks complete successfully, reduce diagnostics deterministically, reject an invalid step, swap buffers, and increment time. No swap on failure. The driver alone owns this transition. If a later boundary algorithm needs another pass, declare and synchronize that pass explicitly for every backend.

## Initialization and field extraction

Without forcing, initialize `g_i = feq_i(rho0,u0)`. With Guo forcing, use `g_i = feq_i(rho0,u0) + S_i(rho0,u0,F)/2` so the initial momentum follows the same time convention.

For a completed POST-collision state:

```text
rho = sum_i g_i
F = rho * acceleration
u = (sum_i(c_i*g_i) - F/2) / rho
p_gauge = cs2 * (rho-rho0)
```

The minus sign for extracting velocity from `g` is intentional. The update uses a plus half-force with PRE-collision `f`; copying that formula onto stored `g` gives the wrong velocity. Test zero and nonzero forcing explicitly. Field extraction, force diagnostics, and export must all agree on the represented timestep.

## Physical inputs and limits

Accept either explicit lattice parameters or an explicit physical-unit configuration, never an ambiguous mixture. For physical voxel width `dx`, timestep `dt`, density scale `rhoScale = rhoPhysicalReference/rho0`, and kinematic viscosity `nuPhysical`:

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

Derive `tau` from viscosity; it must be finite and greater than 0.5. That alone does not guarantee stability. Design shipped scenes for `Ma <= 0.1`, report actual maximum Mach and density variation, and stop on non-finite fields, non-positive density, or configured stability-limit violations. This Mach threshold is a chosen operating guard, not a universal accuracy guarantee. Never hide failures by clamping populations or velocity.

Preserve the physical problem when refining a grid: resolve `dx`, `dt`, Reynolds number, and physical duration together. Matching step counts alone does not imply matching physical time.

## Boundaries and force accounting

Solid walls use link-wise halfway bounce-back. The effective wall lies halfway between fluid and solid cell centers. Analytic validation must use that wall location, not the solid cell center.

Use the local D3Q19 on-site velocity/density reconstruction described by Hecht and Harting for axis-aligned open faces, mapping their direction numbering to the table above. Cover face orientations and wall intersections explicitly. An outlet with fixed density is a pressure boundary, not a perfectly nonreflecting boundary. Initially support Guo forcing in periodic/walled cases and zero body force in open-boundary cases; reject unsupported combinations rather than quietly applying an inconsistent formula.

For a reflected link whose `c_i` points from solid toward fluid, the stationary-wall impulse on the solid is `-2*c_i*current_g_opposite(i)(x)` per lattice timestep. Attribute each link once to its obstacle. Sum per brick, then in brick-ID order. Report vector force and `Cd = F_parallel/(0.5*rhoReference*UReference^2*AReference)` with explicit reference area and direction. Do not invent a universal reference area for arbitrary STL objects.

## Validation and sources

Use an independent tiny-grid reference implementation and analytical cases, not agreement between backends alone. Required checks include stencil moments, equilibrium preservation, all 18 streaming directions, periodic shear decay, forced planar Poiseuille flow in a 3D domain, open-face velocity/density reconstruction, mass balance, obstacle-force symmetry/sign, and backend field equivalence. Include unequal grid dimensions and partial bricks.

Staircase geometry is an approximation. Do not infer engineering-grade drag accuracy from an attractive visualization or assume second-order geometry accuracy for voxelized curved surfaces.

Primary references for implementers:

- [waLBerla basic LBM tutorial](https://www.walberla.net/doxygen/tutorial_lbm01.html): established 3D LBM implementation structure.
- [Guo, Zheng and Shi, 2002](https://journals.aps.org/pre/abstract/10.1103/PhysRevE.65.046308): forcing treatment.
- [Hecht and Harting, D3Q19 boundary conditions](https://arxiv.org/abs/0811.4593): local on-site velocity and density boundaries; consult the full paper and corrections when translating formulas.
- [VTK XML file format](https://docs.vtk.org/en/latest/vtk_file_formats/vtkxml_file_format.html): phase 05 export contract.
- [JDK ForkJoinTask documentation](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ForkJoinTask.html): phase 07 completion, exception propagation, and task reuse.

The storage layout, backend interface, guard values, and feature boundaries in these plans are project design choices, not requirements asserted by those references.
