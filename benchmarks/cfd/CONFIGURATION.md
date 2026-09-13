# CFD configuration (schema version 1)

The `euhedral-cfd` application currently provides configuration inspection. Build and run it with
Java 21 through the repository's Mise toolchain:

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
creates no output directories, population arrays, geometry masks, or workers. A valid inspection
exits with code 0; usage, parsing, resolution, and memory errors exit with code 2 and a diagnostic
on stderr. An inspection pass establishes configuration feasibility, not numerical validation.

## Fields and defaults

All names are case-sensitive. Unknown fields, duplicate keys, trailing documents, explicit nulls,
non-finite numbers, fractional integers, and scalar type coercions are rejected. Omit optional
fields
to select defaults. No configuration imports or environment-variable expansion are performed.

| Field                          | Meaning and default                                                                                      |
|--------------------------------|----------------------------------------------------------------------------------------------------------|
| `schemaVersion`                | Required integer `1`                                                                                     |
| `grid`                         | Required `nx`, `ny`, `nz` integers, each at least 3                                                      |
| `physics.densityReference`     | Positive lattice reference density `rho0`; default `1`                                                   |
| `physics.lattice`              | Lattice parameters; defaults to an empty lattice section when neither mode is supplied                   |
| `physics.physical`             | Alternative SI parameters; mutually exclusive with `lattice`                                             |
| `physics.referenceLength`      | Optional positive reference length in the selected mode's units                                          |
| `geometry.faces`               | `xMin`, `xMax`, `yMin`, `yMax`, `zMin`, `zMax`; each defaults to `PERIODIC`                              |
| `execution.steps`              | Positive integer timestep count; default `100` when no duration is supplied                              |
| `execution.durationSeconds`    | Alternative positive physical duration, requiring physical parameters                                    |
| `execution.stepDeadlineMillis` | Positive per-step deadline, default `30000`; must fit a signed long in nanoseconds                       |
| `execution.brick`              | Positive integer `nx`, `ny`, `nz`; default `16x16x16`, partial edge bricks allowed                       |
| `output.directory`             | Default `output`, resolved relative to the declaring JSON file's directory; absolute paths stay absolute |
| `output.exportEverySteps`      | Non-negative integer cadence; default `0` disables field export                                          |
| `memoryLimitBytes`             | Optional positive integer budget; overrides the heap-based default                                       |

The `lattice` section accepts positive `viscosity` (default `0.1`), `initialVelocity` (default
zero),
and `acceleration` (default zero). Vectors are objects with numeric `x`, `y`, and `z` components;
omitted components default to zero. Viscosity resolves to `tau = 0.5 + 3*viscosity`, which must be
finite and strictly greater than `0.5`, including after floating-point rounding.

The `physical` section requires positive `voxelWidth` (m), `timeStep` (s), `densityReference`
(kg/m^3), and `viscosity` (m^2/s). Optional `initialVelocity` (m/s) and `acceleration` (m/s^2)
default
to zero. `physics.densityReference` remains the lattice reference density in both modes.

Face conditions currently accept `PERIODIC` and stationary `WALL`. Periodic faces must occur in
opposite pairs on each axis. Open-face conditions, obstacle geometry, STL imports, and backend
selection are introduced in their respective phases and are currently rejected. Walls and forcing
can be inspected; their numerical execution belongs to Phase 03. No simulation command is provided
in Phase 01.

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
forcing or future boundaries can change the speed. The low-Mach target is `Ma <= 0.1`; inspection
reports the initial value but does not substitute for runtime guards or numerical validation.

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

Estimated auxiliary storage includes `5*N` bytes for a byte mask and integer obstacle labels,
`256*brickCount` bytes for descriptors, scratch, and reductions, and 1 MiB for array/JVM overhead.
Enabled field export adds a 64 KiB streaming buffer. These allowances are estimates for the current
domain-face configuration and must evolve with later geometry and execution features. They are
not measurements of a running solver's retained heap.

The default budget is half of currently available JVM heap:
`(maxHeap - usedHeap)/2` from a consistent JVM heap snapshot, with a minimum of one byte. Only this
budget varies
with the inspecting process; schema defaults and memory requirements are deterministic. An explicit
`memoryLimitBytes` replaces this default, allowing planning against a different target heap. It does
not guarantee that the inspecting JVM or host can allocate that amount. Array-limit and over-budget
errors include the grid dimensions, estimated requirement, and budget. Arithmetic overflow is
reported separately because an exact requirement cannot then be represented.
