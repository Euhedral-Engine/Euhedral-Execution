# Serial CFD simulation

Build and run the bundled launcher from the repository root:

```bash
mise exec -- gradle :benchmarks:cfd:build
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-smoke.json --backend serial
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-shear.json --backend serial
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/forced-channel.json --backend serial
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-obstacle.json --backend serial
```

`serial` is the default backend; `--config` and `--backend` may appear in either order. The smoke
case advances a uniform velocity on a `12x10x8` grid for 20 timesteps. The shear case uses a
`12x24x20` grid and evolves for 100 timesteps, with spatial variation in both Y and Z. These
commands create no output files. The final report includes completed steps/time, fluid-cell count,
fluid mass, density
range, maximum lattice speed and Mach number, and finite/positive-density validity.

Periodic/walled flow supports stationary boxes, spheres, finite cylinders, and uniform acceleration.
`forced-channel.json` has a `4x12x4` periodic X/Z channel between Y wall planes, with viscosity 0.1
and X acceleration 0.0001, evolved for 2000 steps. Its analytical peak speed is 0.018; cell centers
sample the parabola between the wall planes. `periodic-obstacle.json` places a sphere in a periodic
`12x10x8` grid and applies X acceleration for 100 steps. Open boundaries, obstacle forces, export,
and nonserial backends remain later work.

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
then performs BGK collision with the Guo source inside its six primitive, half-open bounds. A solid
source or exterior wall reflects `current[opposite(i)][destination]`. Every fluid destination writes
its own 19 populations; solid destinations are skipped. Range boundaries never restrict source
reads. Directional scratch is allocated
once by each reusable frame; replacing work inputs and normal body execution allocate no range or
scratch objects. Direction tables are exposed through immutable accessors.

Initialization is ordinary setup in `SerialSimulation`: resolve the immutable geometry mask,
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

`SerialSimulation` registers a `QueueIngestSink` with the caller's `ControlPlaneLattice` and offers
one reusable whole-volume range frame per timestep. The frame retains the same `idHash` and
`routingHash`, so work from that sink follows one ordered lane in insertion order under a stable
routing topology. Lattice workers execute it through the standard terminal, which checks liveness,
invokes the body, and selects `doFinally()` or `doFinallyWithError()`. The driver waits for
successful
terminal completion, computes diagnostics when scheduled, checks the deadline, and swaps buffers.
An empty ingest queue is not a timestep completion signal.

The serial backend uses normal lattice workers; serial execution comes from ordered routing. To
dispatch independent ranges in parallel, call `randomizeHash(seed)` with a changing seed on each
frame before offering it to the same sink. The range inputs and routing metadata stay fixed while
in flight. Independent range dispatch and a multi-range generation barrier are planned in
[Phase 08](08-parallel-execution-backends.md), together with configurable source count and plain
round-robin submission. Source acquisition and distribution remain runtime responsibilities.

The CLI owns the lattice and closes it on success or failure. Library callers supply the lattice:

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

Closing a simulation cancels its frame and completes its sink. The runtime owner closes the lattice
after its simulations to quiesce workers before releasing their buffers. A simulation cannot be
advanced after close; its last completed state remains readable.

Disjoint range frames can share the same generation context and execute concurrently. The driver
owns the partition and must ensure complete, non-overlapping destination coverage. It must observe
successful terminal completion of every range before diagnostics or buffer swaps, and all dispatched
frames must be quiescent before failed-generation buffers are reused. The current serial driver
uses one range and one sink; configurable ranges and source counts remain Phase 08 work.

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
not recover a failed simulation or make partially written populations valid. The serial driver uses
one frame without a manager and stops permanently on a failed generation.

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
The CLI returns 3 for numerical, deadline, or interruption failures, with the last completed step
when initialization succeeded. Usage/configuration failures return 2; successful runs return 0.

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

These are local numerical and lifecycle checks. Independent OpenLB validation belongs to Phase 07;
backend equivalence and scored performance comparisons belong to later phases. No performance
claim follows from these tests or the small simulation fixtures.
