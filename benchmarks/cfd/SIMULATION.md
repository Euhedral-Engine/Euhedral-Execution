# Serial periodic simulation

Build and run the bundled launcher from the repository root:

```bash
mise exec -- gradle :benchmarks:cfd:build
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-smoke.json --backend serial
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/periodic-shear.json --backend serial
```

`serial` is the default backend; `--config` and `--backend` may appear in either order. The smoke
case advances a uniform velocity on a `12x10x8` grid for 20 timesteps. The shear case uses a
`12x24x20` grid and evolves for 100 timesteps, with spatial variation in both Y and Z. Neither
command creates output files. The final report includes completed steps/time, total mass, density
range, maximum lattice speed and Mach number, and finite/positive-density validity.

Only unforced periodic flow is executable. Walls, nonzero acceleration, field export, and nonserial
backends are rejected before population allocation. Phase 01 inspection continues to accept its
broader configuration schema. Physical inputs use the existing unit resolver; reported fields are
in lattice units, and completed physical time is labeled separately. See
[CONFIGURATION.md](CONFIGURATION.md) for defaults, duration, memory limits, and shear settings.

## Numerical state and ownership

The implementation follows [NUMERICS.md](NUMERICS.md). `PopulationGrid` owns two disjoint sets of
19 `double[]` arrays of length `nx*ny*nz`. Checked memory preflight precedes allocation. X varies
fastest. Stored values are post-collision populations; initialization uses local equilibrium at the
configured density and uniform or shear velocity.

`StepContext` supplies the current/next buffers, relaxation frequency, generation, and deadline.
The `CfdRangeFrame` body gathers from the current buffer with periodic wraparound, then performs
BGK collision within its six primitive, half-open destination bounds. Every destination writes its
own 19 populations. Range boundaries never restrict source reads. Directional scratch is allocated
once by each reusable frame; replacing work inputs and normal body execution allocate no range or
scratch objects. Direction tables are exposed through immutable accessors.

Initialization is ordinary setup in `SerialSimulation`: allocate the two population buffers,
fill the initial equilibrium, and compute initial diagnostics once per simulation.

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
terminal completion, computes and validates diagnostics, checks the deadline, and swaps buffers.
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

For the unforced stored state, density is the population sum, velocity is momentum/density, and
gauge pressure is `(density-referenceDensity)/3`. These values refer to the completed timestep.
The next phase adds the half-force convention when forcing is supported.

## Failure behavior

Non-finite incoming/stored/collided populations, non-positive or non-finite density, and non-finite
macroscopic fields fail the simulation with a timestep and cell location. Initialization is step 0;
a failed update identifies the attempted step. Runtime Mach and density-deviation thresholds are
not yet implemented; a finite-state pass does not certify accuracy or low-Mach suitability.

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
- Uniform-flow preservation at several viscosities on non-cubic grids.
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
