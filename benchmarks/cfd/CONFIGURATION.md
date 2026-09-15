# CFD configuration (schema version 1)

The `euhedral-cfd` application provides configuration inspection and periodic/walled
forced flow or unforced inlet/outlet flow with stationary solids, obstacle forces, and ParaView
field export. See [VISUALIZATION.md](VISUALIZATION.md) for output artifacts and CLI overrides.
Build and run it with Java 21 through the repository's Mise toolchain:

```bash
mise exec -- gradle :benchmarks:cfd:build
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd --help
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd inspect --config benchmarks/cfd/scenes/periodic-smoke.json
```

Both `:benchmarks:cfd:assemble` and `:benchmarks:cfd:build` automatically bundle
`build/bin/euhedral-cfd`, `build/euhedral-cfd.jar`, and runtime dependencies in `build/lib`,
matching
the regular benchmark and calibration runners. The source launcher is
[`src/main/scripts/euhedral-cfd`](src/main/scripts/euhedral-cfd). It uses `JAVA_HOME/bin/java` when
`JAVA_HOME` is set, otherwise `java` from `PATH`; `JAVA_OPTS` supplies whitespace-separated JVM
options. Paths and CLI arguments retain spaces, and launch does not change the caller's directory.
Relocate the launcher, JAR, and `lib` directory together to preserve their relative layout.

`:benchmarks:cfd:installDist` also provides the standard Gradle application distribution at
`build/install/euhedral-cfd`, with generated Unix and Windows launchers. ZIP and TAR distributions
are included in the normal build.

Inspection reads the configuration and prints its resolved parameters and memory estimate. It
creates no output directories, population arrays, geometry masks, or workers. Imported meshes are
streamed for structure, transformed bounds, fingerprints, and memory estimates. Add `--voxelize`
to explicitly allocate preprocessing storage and use Euhedral workers to inspect mesh topology and fluid connectivity; see
[STL.md](STL.md). A valid inspection
exits with code 0; usage, parsing, resolution, and memory errors exit with code 2 and a diagnostic
on stderr. An inspection pass establishes configuration feasibility, not numerical validation.

## Fields and defaults

All names are case-sensitive. Unknown fields, duplicate keys, trailing documents, explicit nulls,
non-finite numbers, fractional integers, and scalar type coercions are rejected. Omit optional
fields
to select defaults. No configuration imports or environment-variable expansion are performed.

| Field                                        | Meaning and default                                                                                      |
|----------------------------------------------|----------------------------------------------------------------------------------------------------------|
| `schemaVersion`                              | Required integer `1`                                                                                     |
| `grid`                                       | Required `nx`, `ny`, `nz` integers, each at least 3                                                      |
| `physics.densityReference`                   | Positive lattice reference density `rho0`; default `1`                                                   |
| `physics.lattice`                            | Lattice parameters; defaults to an empty lattice section when neither mode is supplied                   |
| `physics.physical`                           | Alternative SI parameters; mutually exclusive with `lattice`                                             |
| `geometry.openBoundary`                     | Required with open faces; inlet `velocity`, optional `outletDensity` and `rampTime`                      |
| `physics.forceReference`                     | Optional drag reference: positive `velocity`, `area`, `density`, and nonzero `direction`                |
| `physics.referenceLength`                    | Optional positive reference length in the selected mode's units                                          |
| `geometry.faces`                             | `xMin`, `xMax`, `yMin`, `yMax`, `zMin`, `zMax`; each defaults to `PERIODIC`                              |
| `geometry.boxes`                             | Optional list of `{id, min, max}` solid boxes; default empty                                             |
| `geometry.spheres`                           | Optional list of `{id, center, radius}` solid spheres; default empty                                     |
| `geometry.cylinders`                         | Optional list of `{id, start, end, radius}` finite solid cylinders; default empty                        |
| `geometry.meshes`                           | Optional STL obstacles with `id`, `file`, and `units`; transforms and names described in [STL.md](STL.md) |
| `physics.guards.maxMach`                     | Positive maximum actual Mach number; default `0.1`                                                       |
| `physics.guards.maxRelativeDensityVariation` | Positive maximum `abs(rho/rho0 - 1)`; default `0.1`                                                      |
| `execution.diagnosticsEverySteps`            | Full-field diagnostic cadence; default `1`, `0` means initial/final only                                 |
| `execution.steps`                            | Positive integer timestep count; default `100` when no duration is supplied                              |
| `execution.durationSeconds`                  | Alternative positive physical duration, requiring physical parameters                                    |
| `execution.stepDeadlineMillis`               | Positive per-step deadline, default `30000`; must fit a signed long in nanoseconds                       |
| `execution.geometrySources`                 | STL preprocessing ingest sources, 1-64; default one per active worker, capped at 64                       |
| `execution.geometryDeadlineMillis`          | Positive deadline per parallel geometry stage, including queue waits; default `300000` ms                |
| `execution.brick`                            | Positive integer `nx`, `ny`, `nz`; default `16x16x16`, partial edge bricks allowed                       |
| `execution.backendOptions.backend` | `serial` (default), `euhedral`, `fjp`, or `static` |
| `execution.backendOptions.workers` | Positive physical-worker budget; serial uses 1, parallel defaults to eligible physical cores |
| `execution.backendOptions.sources` | Positive JSON integer or `"workers"` (default); Euhedral only; serial resolves to 1 |
| `execution.backendOptions.cpus` | Optional comma-separated available logical CPU IDs; worker selection excludes reserved/driver cores |
| `execution.backendOptions.affinity` | Boolean, default false; request driver and FJP/static worker affinity; Euhedral retains normal managed affinity |
| `execution.backendOptions.shutdownTimeoutMillis` | Positive shutdown/quiescence timeout, default 5000 ms |
| `output.directory`                           | Default `output`, resolved relative to the declaring JSON file's directory; absolute paths stay absolute |
| `output.exportEverySteps`                    | Non-negative integer cadence; default `0` disables field export                                          |
| `output.format`                             | `APPENDED` (default) for uncompressed binary VTI or `ASCII` for diagnostic fixtures                         |
| `memoryLimitBytes`                           | Optional positive integer budget; overrides the heap-based default                                       |

The `lattice` section accepts positive `viscosity` (default `0.1`), `initialVelocity` (default
zero),
and `acceleration` (default zero). Vectors are objects with numeric `x`, `y`, and `z` components;
omitted components default to zero. Viscosity resolves to `tau = 0.5 + 3*viscosity`, which must be
finite and strictly greater than `0.5`, including after floating-point rounding.

The `physical` section requires positive `voxelWidth` (m), `timeStep` (s), `densityReference`
(kg/m^3), and `viscosity` (m^2/s). Optional `initialVelocity` (m/s) and `acceleration` (m/s^2)
default
to zero. `physics.densityReference` remains the lattice reference density in both modes.

Face conditions accept `PERIODIC`, stationary `WALL`, `VELOCITY_INLET`, and `DENSITY_OUTLET`.
Periodic faces must occur in opposite pairs. Open flow requires exactly one opposing inlet/outlet
pair, on X, Y, or Z in either direction. Each transverse pair must be both periodic or both walls.
Intersecting open faces, an open face opposite a wall, missing/extraneous `geometry.openBoundary`,
nonzero acceleration, and shear initialization with open boundaries are rejected. All four execution backends support these boundaries.

`geometry.openBoundary.velocity` is a prescribed velocity vector in lattice units or m/s; its
normal component must point inward. Its full magnitude must satisfy the configured Mach guard.
Tangential inlet velocity is supported; the outlet prescribes zero tangential velocity. The
positive `outletDensity` defaults to the reference density; an explicit value uses lattice density
or kg/m^3 according to the input mode. It is checked against the density guard. Outlet gauge
pressure is `(outletDensity_lattice - rho0)/3`.

`rampTime` defaults to zero (immediate inlet velocity). A positive value is a duration in lattice
timesteps or physical seconds; fractional durations are supported. At completed step `n`, inlet
velocity is multiplied by `min(1, n/rampSteps)`, with `rampSteps = rampTime/timeStep`. Initialization
retains `initialVelocity`; use zero initialization for a startup ramp. Inspection and simulation
reports identify the convention, resolved velocity, outlet density/pressure, and ramp duration.

Open faces are on the outermost fluid-center planes (coordinate `0.5*dx` or `(N-0.5)*dx`). For an
open case with transverse walls, the entire outermost transverse cell layers are solid. Their
halfway wall planes are at `dx` and `(N-1)*dx`, giving fluid width `(N-2)*dx`. This keeps all inlet
and outlet perimeter edge/corner cells solid. Configuration setup rejects an obstacle occupying
an open-face plane. The open-face reconstruction owns each of its five missing distributions,
including adjacent-fluid diagonal links beyond the perimeter. Other solid links use bounce-back.
See [NUMERICS.md](NUMERICS.md) for direction mapping and flux accounting.

`physics.forceReference` supplies a fixed reference speed, area, density, and direction for drag.
Values use the selected mode (lattice units or m/s, m^2, kg/m^3). Direction is normalized during
setup. The reference stays fixed throughout an inlet ramp; it need not equal the initial velocity.
Forces are reported for all declared obstacle IDs, in ascending ID order, including zero for
obstacles with no exposed fluid links. Imported mesh IDs use the same force slots. Domain walls are excluded. The CLI reports lattice force,
force in newtons for physical cases, and Cd when a reference is supplied. Without a reference,
vector forces remain available and requesting Cd through the library raises an error.

Solid geometry uses cell centers at `(x+0.5, y+0.5, z+0.5)` in lattice mode and these coordinates
multiplied by `voxelWidth` in physical mode. Primitive coordinates/radii therefore use lattice
lengths or meters, respectively. Boxes include their min/max surfaces, spheres include their
surface, and cylinders include the side surface and flat caps at their start/end points. Cylinders
can have arbitrary orientation. Primitives are clipped to the grid and are not periodically copied;
the resolved cell mask repeats across periodic faces.

Obstacle IDs must be positive and unique across all primitive and mesh lists. Overlapping solids
assign the lowest ID at each cell, independently of declaration order. Zero denotes fluid. Domain
walls in cases without open faces occupy exterior neighbor cells, leaving all interior grid
centers available to fluid or obstacles; their wall planes are at `0` and the full axis extent.
Exterior IDs are `-1/-2` for X,
`-3/-4` for Y, and `-5/-6` for Z. At intersecting walls, X takes precedence over Y, then Z.
Simulation setup rejects a mask with no fluid cells before allocating populations. Inspection
validates primitive definitions but does not allocate a mask or establish the resolved fluid count.

For example, an obstacle in lattice units can be declared as:

```json
"geometry": {
  "faces": {
    "yMin": "WALL",
    "yMax": "WALL"
  },
  "spheres": [
    {
      "id": 1,
      "center": {
        "x": 6,
        "y": 5,
        "z": 4
      },
      "radius": 2
    }
  ]
}
```

An optional `physics.shear` section selects the periodic profile
`u_x = amplitude*sin(2*pi*modeY*y/ny)*cos(2*pi*modeZ*z/nz)`, with `u_y = u_z = 0`.
Here `y` and `z` are zero-based cell indexes. `amplitude` must be positive and finite, in lattice
velocity units or m/s according to the selected parameter mode. `modeY` and `modeZ` are positive
integers (both default to 1) strictly below the corresponding Nyquist frequencies; `2*modeY < ny`
and `2*modeZ < nz`. A shear profile cannot be combined with nonzero `initialVelocity`.
Omitting `shear` preserves the uniform initialization default. Inspection uses the shear amplitude
for reference speed in Mach/Reynolds estimates; the sampled grid maximum can be smaller.

`steps` and `durationSeconds` cannot both be specified. Physical duration resolves to
`ceil(durationSeconds/timeStep)` whole steps; the report gives the resulting duration, which can
exceed the requested duration by less than one timestep. Positive step counts, physical durations,
and total cell updates must remain representable.

## Unit resolution and dimensionless values

The resolver follows [NUMERICS.md](NUMERICS.md):

```text
u_lattice = u_physical * dt/dx
nu_lattice = nu_physical * dt/(dx*dx)
a_lattice = a_physical * dt*dt/dx
rhoScale = rhoPhysicalReference/rho0
initial Mach = norm(u_lattice) * sqrt(3)
initial Reynolds = norm(u_lattice) * referenceLength_lattice / nu_lattice
```

Reynolds is reported only when `referenceLength` is supplied. These are initial-state values;
forcing or boundaries can change the speed. The low-Mach target is `Ma <= 0.1`; inspection
reports the initial value but does not substitute for runtime guards or numerical validation.
The configured `timeStep` is retained explicitly; the resolver does not automatically select it.
`CfdPhysics.units()` records physical units per lattice unit for length, time, density, velocity,
acceleration, viscosity, gauge pressure, and force. Scalar conversions work in both directions and
reject overflow, non-finite inputs, and nonzero values that underflow to zero. The gauge-pressure
factor is `rhoScale*(dx/dt)^2`; the force factor is `rhoScale*dx^4/dt^2`. The CLI uses this
conversion for completed obstacle forces.

Guards apply during initialization and every range update, even if full-field diagnostic scans are
skipped. The density threshold is relative to the configured lattice reference density. Exceeding
either guard fails the generation with step and cell context. Initial and final diagnostics are
always computed; intermediate scans follow `diagnosticsEverySteps`. `SimulationState.diagnostics()`
identifies the last sampled step, which may precede `completedSteps()` when scans are disabled.

For example, this physical case resolves to `nu=0.1`, `tau=0.8`, initial speed `0.01`, `Re=2`,
and 100 timesteps:

```json
{
  "schemaVersion": 1,
  "grid": { "nx": 24, "ny": 20, "nz": 16 },
  "physics": {
    "physical": {
      "voxelWidth": 0.01,
      "timeStep": 0.001,
      "densityReference": 1000.0,
      "viscosity": 0.01,
      "initialVelocity": { "x": 0.1, "y": 0.0, "z": 0.0 }
    },
    "referenceLength": 0.2
  },
  "execution": { "durationSeconds": 0.1 }
}
```

## Memory preflight

With `N = nx*ny*nz`, the two D3Q19 population buffers have exact array payload `304*N` bytes.
Every direction uses its own `double[N]`; the conservative indexability limit is
`N <= Integer.MAX_VALUE - 8`. Count, update, brick, and memory arithmetic is checked before any
population allocation.

Estimated auxiliary storage reserves `5*N` bytes for cell classification and obstacle labels (the
current mask combines these in one `int[N]` array, omitted for domains without primitives or open faces),
`40*brickCount` bytes for compact reduction slots, 256 bytes per primitive,
`24*primitiveCount*(2*brickCount+2)` for private, pending, and completed force vectors, and 1 MiB
for
array/JVM overhead. Enabled field export reserves 128 KiB for streaming VTI output and PVD copying. STL preprocessing adds the budget described in [STL.md](STL.md). These allowances are
estimates and must evolve with later geometry and execution features. They are not measurements
of a running solver's retained heap. Backend memory checks additionally reserve every physical
frame and retained execution structure. Euhedral includes the complete source frame pools plus
power-of-two recycler and consumer-buffer reference arrays; it has no fixed 8,192-frame cap.

The default budget is half of currently available JVM heap:
`(maxHeap - usedHeap)/2` from a consistent JVM heap snapshot, with a minimum of one byte. Only this
budget varies
with the inspecting process; schema defaults and memory requirements are deterministic. An explicit
`memoryLimitBytes` replaces this default, allowing planning against a different target heap. It does
not guarantee that the inspecting JVM or host can allocate that amount. Array-limit and over-budget
errors include the grid dimensions, estimated requirement, and budget. Arithmetic overflow is
reported separately because an exact requirement cannot then be represented.

`SimulationState.flowDiagnostics()` returns borrowed storage published after every successful
step, independently of the full-field scan cadence. Read its scalar values before advancing again. It reports the step,
actual discrete mass change, boundary-update inlet/outlet fluxes, their balance residual, separately
labeled macroscopic flux estimates, per-obstacle vector forces, and optional Cd. Forces represent
the momentum exchanged during that completed update. Failed or cancelled work does not publish
new flow totals. Full-field mass, density range, and maximum Mach remain in `diagnostics()` at the
configured scan cadence. The CLI writes these values to `metrics.csv`; see [VISUALIZATION.md](VISUALIZATION.md).

## Execution selection

CLI options `--backend`, `--workers`, `--sources`, `--cpus`, and `--affinity` override the corresponding
`execution.backendOptions` fields. For example:

```bash
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-smoke.json --backend euhedral --workers 2 --sources 1
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-smoke.json --backend fjp --workers 2
```

Use `--sources workers` for one persistent Euhedral ingest sink per effective physical worker, or
an explicit positive count. Sources lazily claim disjoint strided ordinal streams each timestep;
the driver publishes context and waits for completion. Source count does not assign sources to workers. Zero/negative counts and non-integer
counts are rejected. Serial requires one worker/source; FJP and static do not accept explicit source
counts. `inspect` validates settings without starting workers; the simulation resolves topology
and records the effective budget in `resolved.json`. On a one-core host, the driver and worker
necessarily share that core. Backend storage is checked against the run's memory budget. Brick dimensions are independent
and may be any positive integers, including one; partial edges are clipped without gaps or overlaps.
