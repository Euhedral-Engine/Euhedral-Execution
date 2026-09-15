# Serial CFD simulation

Build and run the bundled launcher from the repository root:

```bash
mise exec -- gradle :benchmarks:cfd:build
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-smoke.json --backend serial
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-shear.json --backend serial
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/forced-channel.json --backend serial
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-obstacle.json --backend serial
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle-smoke.json --backend serial
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/sphere-wake-smoke.json --backend serial
```

`serial` is the default backend; `--config` and `--backend` may appear in either order. The smoke
case advances a uniform velocity on a `12x10x8` grid for 20 timesteps. The shear case uses a
`12x24x20` grid and evolves for 100 timesteps, with spatial variation in both Y and Z. These
commands create a distinct run directory containing configuration, metrics, and status. Field export
is enabled with `--export-every N`; see [VISUALIZATION.md](VISUALIZATION.md) for CLI overrides and
ParaView usage. The final report includes completed steps/time, fluid-cell count,
fluid mass, density
range, maximum lattice speed and Mach number, and finite/positive-density validity.

Periodic/walled flow supports stationary boxes, spheres, finite cylinders, imported STL solids,
and uniform acceleration. [STL.md](STL.md) explains mesh preprocessing and conservative cell marking.
`forced-channel.json` has a `4x12x4` periodic X/Z channel between Y wall planes, with viscosity 0.1
and X acceleration 0.0001, evolved for 2000 steps. Its analytical peak speed is 0.018; cell centers
sample the parabola between the wall planes. `periodic-obstacle.json` places a sphere in a periodic
`12x10x8` grid and applies X acceleration for 100 steps. Unforced open boundaries and obstacle
forces are also supported; [scene metadata](scenes/README.md) records inlet/outlet cases and smoke
variants. Select `euhedral`, `fjp`, or `static` with `--backend` for parallel range execution; see
[execution settings](CONFIGURATION.md#execution-selection).

Physical inputs use checked unit conversions; CLI diagnostics are in lattice units, with completed
physical time labeled separately. `SimulationState.physicalField()` converts density, velocity, and
gauge pressure to SI for physical configurations. Fields at solid cells are undefined and rejected.
See [CONFIGURATION.md](CONFIGURATION.md) for geometry coordinates, guards, and diagnostics cadence.

## Numerical state and ownership

The implementation follows [NUMERICS.md](NUMERICS.md). `PopulationGrid` owns two disjoint sets of
19 `double[]` arrays of length `nx*ny*nz`. Checked memory preflight precedes allocation. X varies
fastest. Stored values are post-collision populations; initialization uses local equilibrium at the
configured density and uniform or shear velocity, plus half the unprefactored Guo source when
forced.

`StepContext` supplies buffers, relaxation frequency, geometry, acceleration, guards, generation,
and deadline. The `CfdRangeFrame` body gathers with periodic wraparound or halfway bounce-back,
reconstructs missing open-face populations when present, then performs BGK collision with the Guo
source inside its six primitive, half-open bounds. A solid
source or exterior wall reflects `current[opposite(i)][destination]`. Every fluid destination writes
its own 19 populations; solid destinations are skipped. Range boundaries never restrict source
reads. Directional scratch is allocated
once by each reusable frame; replacing work inputs and normal body execution allocate no range or
scratch objects. Direction tables are exposed through immutable accessors.

STL geometry preprocessing dispatches reusable intersection/voxel frames to the supplied lattice
and waits for terminal completion before publishing its immutable mask. Parsing, welding, spatial
index construction, and connectivity remain setup; [STL.md](STL.md) details stage ownership.

Initialization in `SerialSimulation` then uses the immutable geometry mask to
reject empty fluid domains, allocate the two population buffers, initialize force-aware populations,
and compute initial diagnostics once per simulation. Geometry/configuration objects are shared by
all generations. Static stencil, equilibrium, force, and validation helpers create no per-cell
objects. Each frame retains one 19-value scratch array; the driver retains its diagnostic scratch.
One immutable context is created per generation, shared by that generation's ranges, and one
summary record is created per diagnostic scan. Frame replacement/body execution allocates neither
range descriptors nor numerical scratch.

`CfdRangeFrame` extends `AbstractFrame` through `CfdFrame` and represents the repeating,
parallelizable work. It carries replaceable primitive destination bounds, private directional
scratch,
and a shared `StepContext` containing the buffers, relaxation frequency, generation, and deadline.
Its body
performs pull/collide for that range; its terminal hook acknowledges the range result. It does not
initialize the simulation, reduce full-field diagnostics, or swap buffers.

`Simulation` publishes a stable Z/Y/X ordinal plan and a new context each timestep. Bounds are
computed from grid and brick dimensions without descriptors; partial edge bricks are clipped.
All positive brick dimensions are valid, including `1x1x1`. Solid-only ranges are discovered lazily
by Euhedral sources. All backends execute the same frame body and complete a generation before
reducing diagnostics and swapping buffers.

`SerialSimulation` uses one persistent lazy source on a caller-owned lattice. Equal identity and
routing hashes select ordered execution through request-and-route. Parallel `EuhedralBackend`
uses mixed hashes. Source `i` claims ordinals `i, i + sources, ...`; every worker can acquire every
source. The driver publishes only the context and per-source completion targets. Each serialized
source owns a plain cursor and a `FrameManager`. Setup preallocates its full logical-range count
of physical frames and scratch, with recycler capacity rounded up including the reserved queue slot.
Pulls use only `get()` and lazy ordinal/context replacement; a miss is an explicit failure.
A stopped pull retains its prepared frame for the next call. Empty-generation demand is discarded.

Completion producers copy results into disjoint numeric slots, recycle, then increment a source's
padded
completion counter using an owner reference captured before recycling. No frame reads follow
enqueue. The driver acquires every counter and folds slots in ordinal
order. This preserves bitwise diagnostic reductions across backends without binding retained frames
to particular ordinals.
Numeric result storage is 40 bytes per logical range plus 24 bytes per range per obstacle ID.
Source-local floating-point sums would change the validated summation order, so are not used.

Source handles serialize cursor and recycler consumption. Volatile context publication and the
source's busy flag coordinate the driver with source calls; completion counters publish numerical
and result writes. Cancellation freezes generation publication, waits for source calls to leave,
finishes any source-owned stopped frame, then waits for all issued work. No next-generation context
is published until the barrier. Frame results must be consumed before recycling.

`ForkJoinBackend` retains its bulk task tree and range frames in a dedicated pool; `StaticBackend`
retains frames and workers with contiguous range sequences. Their backend owns frame preparation;
FJP joins its root and static workers acknowledge generation exit before reuse. Runtime memory
checks include this retained storage; very small bricks need more heap on these backends.

The CLI owns synchronous output and closes the lattice on success or failure. The solver library
does not write files when `run()` or `step()` is called. Library callers supply the lattice:

```java
var lattice = ControlPlaneLattice.getOrCreate();
try(
var simulation = new SerialSimulation(configuration, lattice)){
        simulation.

run();
}finally{
        lattice.

close();
}
```

Closing a simulation cancels its ranges and closes its backend. The runtime owner closes the lattice
after its simulations to quiesce workers before releasing their buffers. A simulation cannot be
advanced after close; its last completed state remains readable.

Disjoint range frames can share the same generation context and execute concurrently. The driver
owns the partition and must ensure complete, non-overlapping destination coverage. It must observe
successful terminal completion of every range before diagnostics or buffer swaps, and all dispatched
frames must be quiescent before failed-generation buffers are reused. All backends use the same configurable ranges and increasing ordinal reductions.

Replace frame inputs only out of flight and submit each frame once per replacement. Status
distinguishes
prepared, executing, body-complete, succeeded, cancelled, and failed work. `isDone()`/
`requireSuccess()`
acquire the terminal publication, after all writes to that range's next populations. A successful
body
alone is not a completion signal. Routing metadata remains unchanged in flight.

An optional `FrameManager` receives the frame after success, cancellation, or failure. The single
manager owner must consume all prior results before reacquiring frames and replacing their context
and bounds. Never replace a pooled frame through an old reference while it remains in the recycler.
Replacement clears the previous failure and resets the frame's own kill switch. Reusing a frame does
not recover a failed simulation or make partially written populations valid. Euhedral consumes results in the terminal hook and recycles through a source-owned manager. Every backend stops permanently on a failed generation.

The factory pattern follows the [quick start](../../QUICK_START.md#recycle-custom-frames). A
whole-volume factory can use the already shared generation context as its input without a per-frame
work descriptor:

```java
var manager = new FrameManager<StepContext, CfdRangeFrame>(capacity, password);
try {
    FrameFactory.FrameReplace<StepContext, CfdRangeFrame> replace = (context, frame) -> {
        var shape = context.shape();
        frame.replace(context, 0, shape.nx(), 0, shape.ny(), 0, shape.nz());
    };
    manager.setFactory(new FrameFactory<>((idHash, context) -> {
        var frame = new CfdRangeFrame(idHash, manager);
        replace.replace(context, frame);
        return frame;
    }, replace));
    /// The owner obtains work with manager.getOrCreate(context, password), dispatches it,
    /// and consumes its terminal result before obtaining the next generation's work.
} finally {
    manager.close();
}
```

Other partitions can supply different scalar bounds in the same replacement callback. The frame
retains its scratch across replacements, including replacements after cancellation or an error.

The field extractor reduces mass/density/speed diagnostics in X-fastest order, using compensated
summation for mass. Public simulation-state access returns values and completed diagnostics, not
mutable arrays; callers must follow the frame ownership boundary when execution is asynchronous.

Density is the stored population sum, velocity is `momentum/density - acceleration/2`, and gauge
pressure is `(density-referenceDensity)/3`. The gather/collision body uses the positive half-force
correction on incoming populations. Initialization uses `equilibrium + GuoSource/2`, so field
extraction returns the configured velocity at time zero. Solid cells are excluded from diagnostics.
These conventions refer to the completed timestep and follow [NUMERICS.md](NUMERICS.md).

## Failure behavior

Non-finite incoming/stored/collided populations, non-positive or non-finite density, and non-finite
macroscopic fields fail the simulation with a timestep and cell location. Initialization is step 0;
a failed update identifies the attempted step. Configurable Mach and relative-density guards run
in the kernel on every step and during initial field analysis. Optional intermediate diagnostic
scans cannot disable them. A guarded finite-state pass does not certify numerical accuracy.

The per-step deadline covers queue submission, waiting for terminal completion, the frame body,
and completed-field validation. The driver checks deadline and interruption while waiting. The
body checks cancellation, deadline, and interruption at least once per row and every 256 destination
cells in a long row; a final driver check precedes the swap. JVM population allocation,
initialization,
and runtime startup are outside the per-step deadline. Initialization also checks interruption, and
frame execution preserves the interrupt flag.

A failed generation may partially overwrite the next buffer. It never swaps that buffer into the
completed state, increments completed time, or replaces completed diagnostics. The serial simulation
is
then failed and cannot be advanced again; its last completed state remains available to the caller.
A killed or structurally cancelled frame never acknowledges a successful range, even though
Euhedral invokes `doFinally()` for structured cancellation. Execution exceptions are retained by
`doFinallyWithError()`. JVM `Error` instances remain outside the runtime's ordinary exception
boundary; they must not be treated as successful frame completion.
The CLI records failure status with the last completed/exported steps. It returns 3 for numerical
and deadline failures, 130 for interruption, 4 for output errors, 2 for usage/configuration errors,
and 0 for success. Completed field files remain available after a failed or interrupted run.

## Verification

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:integrationTest :benchmarks:cfd:spotlessCheck
```

The solver tests cover:

- D3Q19 direction uniqueness, opposite symmetry, isotropic moments through fourth order, and
  equilibrium density/momentum.
- Uniform-flow preservation at several viscosities on non-cubic grids, including exact zero-force
  equivalence.
- Cell-center masks for boxes, spheres, axial and oblique finite cylinders; overlap IDs, empty-fluid
  rejection, wall/periodic intersections, and physical geometry coordinates.
- All 18 moving directions reflect from solid neighbors and exterior walls, including diagonal
  links;
  solid populations are never gathered or collided.
- Force-aware initialization/extraction, Guo mass/first/second moments, reversed acceleration,
  stationary preservation, and closed/periodic fluid-mass conservation.
- Planar Poiseuille profiles at viscosities 0.05, 0.1, and 0.2 and heights 8 and 16. Relative L2
  errors must remain below 3% and 0.8%, respectively, and refinement must reduce error by a factor
  greater than 2.5. Each run lasts at least `12*H^2/(pi^2*nu)` steps to bound the initial transient.
  Diffusive refinement halves lattice speed, divides acceleration by eight, and preserves viscosity.
- Physical/lattice round trips, SI field extraction, preserved Reynolds number and duration under
  refinement, invalid/overflowing conversions, and guards with diagnostic scans disabled.
- All 18 moving directions across periodic faces, edges, and corners.
- One and multiple timesteps against an independent cell-major push-stream/collide implementation,
  including irregular destination ranges and untouched source buffers.
- Analytical shear decay `exp(-nu*(ky^2+kz^2)*t)` under diffusive refinement. The `4x12x16`, 10-step
  case allows 4% relative amplitude error; the `4x24x32`, 40-step case allows 1% and must reduce
  the error by more than a factor of 2.5. Transverse finite-amplitude residuals are bounded relative
  to the square of the initial amplitude. These tolerances describe local discretization checks.
- Production executor hooks, cancellation before execution or after the body, exception retention,
  manager recycling and replacement after every terminal outcome, changed contexts and all six
  bounds,
  disjoint range execution through the lattice, and terminal result visibility across threads.
- Invalid density/populations, overflow during collision, unsupported configuration rejection,
  deadline expiration before publication, and interruption after a successful step.
- Live integration checks for FIFO execution on one worker with equal ordered IDs,
  independent-worker
  progress with mixed hashes, and completion/deadline handling while a simulation's lane is
  occupied.
  The parallel-progress check requires at least two active physical-core workers.

These are local numerical and lifecycle checks. Independent OpenLB validation is available through [validate](VALIDATION.md);
backend equivalence and scored performance comparisons belong to later phases. No performance
claim follows from these tests or the small simulation fixtures.

## Open boundaries and force ownership

`GeometryMask` resolves the open-face plan and sorted, compact obstacle-ID slots once. Each
`CfdRangeFrame` retains its own force array (three doubles per declared obstacle) and primitive
mass/flux totals. Replacement resets these values; only a larger obstacle set grows the array.
An obstacle-free frame shares an immutable empty force array. Open-boundary helpers use the
existing 19-value incoming scratch; the body and terminal hooks create no numerical objects.

Range IDs are replaceable primitives alongside the six bounds. The overload without an ID uses
zero for standalone whole-volume callers. The shared range plan assigns increasing stable IDs and calls
`FlowDiagnostics.reduce(step, ranges)` in that order, after every range has succeeded. It validates
terminal status, generation, ID order, and obstacle slot correspondence before changing its totals.
It does not establish complete/non-overlapping domain coverage; partition ownership remains with
the driver. No shared force counter or per-link object is used. Reduction copies values into
reusable driver storage before the manager owner may reacquire and replace any frame.

The shared driver reduces into pending storage, checks the deadline, then publishes flow totals
and swaps populations together. Two reusable driver slots preserve the preceding totals when
reduction fails or the final deadline check expires.
`SimulationState.flowDiagnostics()` is a borrowed, driver-owned view of the last completed update;
read values before the next `step()`. The initial view is step zero with zero exchanged force and
flux. It updates every timestep even when full-field diagnostic scans are disabled. The CLI prints
the final view. [NUMERICS.md](NUMERICS.md) specifies the distinction between discrete boundary
flux and estimated macroscopic flux.

Coverage includes all six inlet orientations with reconstructed density/velocity moments,
uniform through-flow, ramped ducts with solid wall perimeters, per-step mass balance, physical
unit equivalence, geometry/configuration rejection, force symmetry and reversal, two-obstacle
attribution, fluid momentum balance, reusable force slots, and split ranges crossing obstacles and
open-face edges/corners. Failed generations retain their preceding flow diagnostics.

The curved-geometry checks measure voxel-volume error and force resolution/domain sensitivity.
See [scene metadata](scenes/README.md) for the observed values and limits. These checks do not
replace Phase 07 external-reference validation or establish performance results.
