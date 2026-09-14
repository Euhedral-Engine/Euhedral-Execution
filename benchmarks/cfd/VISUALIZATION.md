# Simulation output and ParaView

Build the bundled launcher and export a small obstacle-flow time series:

```bash
mise exec -- gradle :benchmarks:cfd:build
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate \
  --config benchmarks/cfd/scenes/duct-obstacle-smoke.json \
  --backend serial --steps 100 --export-every 10 \
  --output benchmarks/cfd/build/runs/duct-demo
```

The command prints a new `run-...` directory beneath the output base. Open that directory's
`flow.pvd` in ParaView. Repeating the command creates another directory and preserves earlier runs.
An export cadence greater than zero writes the initial state, every matching completed step, and
the final state even when it falls between cadence steps. The default cadence, zero, disables
field export but still writes configuration, metrics, and status.

## CLI overrides

Options may appear in any order after `simulate`. Only the serial backend is implemented.

| Option | Effect |
| --- | --- |
| `--steps N` | Replace the configured step count or physical duration |
| `--duration SECONDS` | Replace the configured duration or step count; requires physical parameters |
| `--output DIRECTORY` | Select an output base relative to the current working directory |
| `--export-every N` | Select field cadence; zero disables VTI/PVD output |
| `--format appended\|ascii` | Select streaming binary output or readable diagnostic arrays |
| `--set path=JSON-value` | Override a schema property; may be repeated for distinct paths |

For example, add `--set execution.diagnosticsEverySteps=10` or
`--set 'physics.guards.maxMach=0.08'`. Values use JSON syntax, including quotes around strings.
Dotted paths address object properties; replace an entire array to change geometry primitives.
`inspect` also accepts `--set`, so overrides can be validated before running. Unknown fields,
wrong types, duplicate paths, and conflicting duration/step overrides fail before output allocation.
Overriding one duration form removes the other form from the input. All normal configuration,
physics, and memory checks run on the resulting configuration.

`output.directory` in JSON, including `--set output.directory=...`, is relative to the configuration
file. The dedicated `--output` option is relative to the shell's current directory. See
[CONFIGURATION.md](CONFIGURATION.md) for the complete schema.

## Run artifacts

| File | Contents |
| --- | --- |
| `configuration.json` | Effective schema configuration, including defaults and CLI overrides |
| `resolved.json` | Source and run paths, backend, resolved physics, units, steps, and memory estimate |
| `metrics.csv` | Initial state and one row per successfully recorded completed timestep |
| `status.json` | `RUNNING`, `COMPLETED`, `FAILED`, or `INTERRUPTED`, completed/exported steps, timing totals, and error if present |
| `frame-NNNNNNNNNNNN.vti` | Completed cell-centered fields at the step encoded in the filename |
| `flow.pvd` | Ordered collection of published VTI files and their times |

The saved configuration can be supplied to `--config` for replay. Supply `--output` to select the
replay destination explicitly; relative paths in the saved JSON resolve from its new location.
Physical runs use seconds for collection and CSV time. Lattice runs use completed lattice steps,
labeled `lattice_steps` in the PVD comment, CSV, and resolved metadata. VTI FieldData contains
`TimeValue` and either `time_seconds` or `time_lattice_steps`.

The CSV keeps numerical quantities explicitly in lattice units, including obstacle forces. Convert
them with the factors in `resolved.json` when comparing SI quantities; VTI fields already use SI
for physical configurations. The full-field mass, density range, maximum speed, and maximum Mach
columns are blank when a diagnostic scan was not scheduled for that row. `diagnostics_step`
identifies the most recent scan. Initial and final scans always run. Flux, mass-change, and force
columns describe each completed update independently of that cadence. Initial exchanged flux
and forces are zero; Cd is blank without a configured drag reference. Discrete boundary-update
fluxes and estimated macroscopic fluxes are separate columns, as defined in
[NUMERICS.md](NUMERICS.md).

Per-row `simulation_ns` measures initialization/runtime startup at step zero and numerical stepping
thereafter, including queue waits, runtime guards, and scheduled diagnostics. `export_ns` measures
VTI writing and PVD publication at that row. Status records their cumulative totals. CSV, metadata,
console output, and runtime shutdown are outside both counters; these are elapsed durations, not
worker CPU time or JMH performance evidence.

Export runs synchronously after successful timestep publication and before the next step. It
streams from stable populations using reusable scalar scratch, without a full-field snapshot
allocation. Each VTI is closed and atomically renamed before its PVD entry is published through
another atomic rename. This requires an output filesystem supporting atomic sibling moves.
The PVD rewrite streams prior entries with bounded memory rather than retaining a growing list.

On a numerical or output failure, previously published frames and the collection remain usable.
An interrupted export can leave a complete VTI that has no PVD entry; the collection only exposes
completed publications. Ctrl-C requests interruption and records `INTERRUPTED` during orderly
shutdown. An uncatchable termination such as SIGKILL can leave `RUNNING` status and temporary files;
it cannot be converted into an orderly interruption report. Normal failures and interruptions
remove their temporary files. Exit codes are 0 for success, 2 for usage/configuration errors,
3 for numerical/runtime failures, 4 for output errors, and 130 for interruption.

## Coordinates and fields

VTI uses ImageData point extents `0 nx 0 ny 0 nz` with `nx*ny*nz` **CellData** samples. Origin is
`0 0 0`; spacing is the physical voxel width, or one in lattice mode. Cell centers are therefore
`((x+0.5)*dx, (y+0.5)*dx, (z+0.5)*dx)`. X varies fastest, followed by Y, then Z.

| Cell array | Type | Physical / lattice units |
| --- | --- | --- |
| `velocity` | Float64, three components | m/s / lattice velocity |
| `density` | Float64 | kg/m^3 / lattice density |
| `gauge_pressure` | Float64 | Pa / lattice gauge pressure |
| `solid` | UInt8 | 0 for fluid, 1 for solid |
| `obstacle_id` | Int32 | 0 for fluid, positive obstacle ID, negative wall ID |

Density, velocity, and pressure inside solid cells are zero placeholders, not fluid observations.
Mask solids before interpolation or analysis. Exterior walls have no cell in the exported volume;
negative IDs appear where open-boundary geometry includes solid perimeter cells. Fluid extraction
uses the completed post-collision populations and the negative half-force velocity correction in
[NUMERICS.md](NUMERICS.md). Pressure is relative to the configured reference density.

The default encoding is uncompressed, little-endian raw appended data with UInt64 block headers
and checked 64-bit offsets. `--format ascii` produces the same arrays inline for small diagnostic
fixtures. Raw appended files require a VTK reader; their binary section is not ordinary XML text.
The layout follows the [VTK XML format specification](https://docs.vtk.org/en/latest/vtk_file_formats/vtkxml_file_format.html).

## ParaView workflow

1. Open `flow.pvd`, click **Apply**, and use the time controls to select a completed state.
2. Select the original collection and add **Threshold** using cell array `solid`, lower and upper
   bounds both 1. Display this branch as a solid surface. To isolate an obstacle instead, threshold
   `obstacle_id` to its positive ID.
3. Create a separate **Threshold** branch from the collection with `solid` equal to 0. Use this
   fluid-only branch for field analysis, keeping zero placeholders out of interpolation.
4. Add **Slice** to the fluid branch, place its plane through the domain, and color by velocity
   magnitude or `gauge_pressure`. The displayed coordinates use the units above.
5. For streamlines, apply **Cell Data to Point Data** to the fluid branch, then **Stream Tracer**.
   Choose `velocity`, place the seed line or point cloud inside the fluid near the inlet, and
   adjust integration length to the domain size. A **Tube** filter can make the curves easier to see.

Streamlines are tangent to the velocity field at the selected instant. Playing an animation of
them does not trace particles through time. A time-dependent **Particle Tracer** instead integrates
across saved times; its result depends on export cadence and temporal interpolation. See the
[ParaView filtering guide](https://docs.paraview.org/en/latest/UsersGuide/filteringData.html).

## Independent reader check

Java tests verify non-cubic fields, cell ordering, SI conversions, labels, UInt64 block lengths,
offset arithmetic beyond 4 GiB, collection times, and interrupted publication. The separate reader
check requires a Python environment with VTK installed:

```bash
CFD_VTK_PYTHON=/absolute/path/to/vtk-environment/bin/python \
  mise exec -- gradle :benchmarks:cfd:integrationTest --rerun-tasks
```

`VtiWriterTest.installedVtkReaderLoadsBothFormats` invokes
[`verify_vti.py`](src/test/python/verify_vti.py) on both formats and checks actual VTK cells,
coordinates, components, values, and labels. Without `CFD_VTK_PYTHON` it is explicitly skipped;
the normal Java tests still run. This checks file interoperability, not external solver accuracy.
