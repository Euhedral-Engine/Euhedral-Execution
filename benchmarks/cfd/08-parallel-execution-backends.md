# Phase 08 - Parallel execution backends

Status: implemented. Includes the remaining Euhedral execution work formerly assigned to Phase 09.

Dependencies: [04](04-open-boundaries-and-forces.md). Related components: [numerical model](NUMERICS.md), [runtime integration](REPOSITORY_MAP.md).

## Feature

Serial, parallel Euhedral, ForkJoinPool, and persistent static workers advance the same fluid
simulation over independent destination ranges.

## Work and ownership

Phase 02 already provides reusable `CfdRangeFrame` bodies, queue ingest, lattice execution, and
frame completion hooks. This phase extends the driver to submit multiple independent ranges and
await their collective completion. Serial and parallel Euhedral execution use the same frames and
runtime path; routing hashes select ordered or parallel execution.

Every destination cell belongs to exactly one range per timestep. Half-open XYZ bounds are stored
directly in reusable frames, with stable range ordinals for deterministic diagnostics. Separate
`Brick` and `BrickPlan` objects are optional; no worker-specific grouping or assignment is required
for Euhedral. Configurable range dimensions support partial edges, with `16x16x16` as the initial
brick-shape default. Fully solid ranges are excluded consistently across backends. Range size is a
work-granularity parameter to evaluate through measurement.

`ExecutionBackend` describes preparation, step execution, and shutdown for the comparison backends.
A stable `StepContext` supplies generation inputs. Frames retain private scratch across
replacements; any private reduction slots are allocated during setup. The driver owns generation
publication, diagnostics, and buffer swaps. Initialization remains ordinary setup.

## Euhedral dispatch and sources

The runtime owner keeps one `ControlPlaneLattice` for its lifetime, using the existing default
fragment and executor pipeline. Allowed CPUs and shutdown timeout configure the lattice. No separate
executor or Euhedral scheduling implementation is introduced.

Serial execution uses one persistent lazy source with equal identity/routing hashes; parallel
execution uses mixed routing hashes and a configurable positive source count or `workers`.
The driver publishes the context once per generation. Source `i` lazily claims ordinals
`i, i + sourceCount, ...`, computes bounds, and obtains a frame through its own `FrameManager`.
No source is assigned to a worker; Euhedral owns acquisition, randomization and work distribution.

Source-local cursors and recycler consumption are plain under serialized source handles. Completion
producers publish disjoint compact results, recycle frames, then acknowledge source completion.
Setup preallocates every source's full generation working set, with reusable numerical scratch.
Logical assignments remain lazy; recycler misses fail instead of allocating frames.
The driver combines results in ordinal order at the generation barrier. Cancellation freezes
publication and waits for source calls and issued frames. See [SIMULATION.md](SIMULATION.md) for
the ownership and memory publication contract.

## Comparison backends and worker budgets

All backends execute the same numerical body over the same destination ranges. A dedicated
ForkJoinPool uses a bulk range-splitting task tree with range leaves. Task reuse follows completed
task lifecycle and reinitialization. An external driver submits the root and awaits completion.

Persistent static workers retain fixed contiguous sequences of ranges across generations. They wait
between steps and execute their assigned ranges without per-range dispatch. This provides a
bulk-synchronous baseline alongside task-based scheduling.

Worker budgets exclude the driver. Optional startup affinity uses the existing public `ThreadTools`
APIs and is restored on worker exit. Reports describe requested/effective CPU IDs, effective
physical workers, and exact, hinted, or unavailable affinity. Euhedral reports both requested and
resolved source counts.

## Generation completion and failure

Before dispatch, the driver publishes the stable context, generation ID, expected terminal count,
and error state. Each range is submitted exactly once. Inputs and routing metadata stay fixed while
frames are in flight.

A step succeeds only after every range has acknowledged successful terminal completion and its
numerical writes are visible. A completed body, an empty source queue, or a cancelled frame is
insufficient. Diagnostics are reduced in deterministic range order before buffer swaps. The
completion protocol preserves result visibility and observes the ownership boundary before frame
replacement or manager reacquisition.

Generation accounting handles submission failure, structured cancellation, execution exceptions,
interruption, deadlines, missing or duplicate acknowledgements, and worker loss or fatal errors
outside the ordinary executor exception boundary. Failure invalidates the next state and retains the
last completed state. The driver stops submission, requests cancellation, and establishes bounded
worker quiescence before buffers are reused or released. Wait diagnostics identify outstanding
ranges. The runtime owner closes sources and the lattice at their ownership boundaries.

## Verification

Complete populations and derived fields are compared against serial across periodic, forced-wall,
and open-boundary scenes. Per-cell arithmetic is shared; deterministic range-order reductions make
comparison independent of completion order. On the same JVM, equal operation order supports exact
population agreement, with any tolerance explicitly identified.

Coverage includes multiple worker counts, fewer ranges than workers or sources, non-cubic partial
ranges, diagonal neighbors across range corners, solid ranges, injected failures, repeated
timesteps, frame recycling, shutdown, and close/recreate behavior. Live lattice checks cover ordered
FIFO execution, mixed-hash parallel execution, source count `1`, source count `workers`, and other
explicit positive counts.

Dispatch checks verify setup preallocation, lazy assignment, ordinal coverage, frame recycling
across generations,
partial bricks, and deadline cancellation before any work has been pulled. Config
validation rejects zero and negative source counts. Source-count variants must produce equivalent
complete fields. Coordination tests use deterministic synchronization. External physical correctness
comes from [phase 07](07-external-solver-validation.md); backend checks establish execution
equivalence.

## Interface

The bundled launcher accepts:

```bash
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend fjp --workers 4
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend static --workers 4
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend euhedral --workers 4 --sources 1
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend euhedral --workers 4 --sources workers
```

## Implementation and validation

`Simulation` owns initialization, shared generation contexts, ordered reductions, and population
publication. `SerialSimulation` remains a compatibility entry point for a caller-owned lattice.
`RangePlan` creates fixed frames once and reserves their incoming/force scratch during setup.
`EuhedralBackend`, `ForkJoinBackend`, and `StaticBackend` implement `ExecutionBackend`; the latter
two reuse a task tree and persistent contiguous-range workers respectively. No Euhedral executor
or producer threads are added. The driver retains frames directly, without recycler handoffs.

Settings live in `execution.backendOptions` and are retained in replay configuration. CPU budgets
select available logical CPUs directly. The benchmark driver can share a selected CPU when every
available CPU participates. Requested workers are capped to available eligible CPUs. Euhedral uses
its ordinary managed affinity; `--affinity true`
also requests driver and comparison-worker affinity through `ThreadTools`. Capability labels do
not assert exact physical placement. `resolved.json` records requested/effective CPUs, worker
count, affinity capability, and requested/resolved sources. Geometry workers close before a FJP
or static run starts.

Focused coverage compares every population and derived fluid field exactly over multiple steps
for periodic shear, forced walls, and an open duct with an obstacle. It varies worker counts,
source counts, non-cubic partial bricks, and a single brick with more workers/sources than ranges.
Additional checks cover solid-brick exclusion, frame identity reuse, terminal errors, fatal body
errors, interruption, and deterministic rejected-offer retries. Existing routing/recycler and
external OpenLB integration checks remain part of the CFD suite. These tests establish execution
correctness; throughput measurement belongs to Phase 10.

Deadlines identify outstanding range ordinals. Failure cancels the generation and waits up to the
configured shutdown timeout for terminal acknowledgments. If a worker cannot quiesce, the run
fails with that condition reported; its buffers and frames are never reused. Backend close stops
its owned workers, while callers close borrowed Euhedral lattices at their ownership boundary.
