# Phase 08 - Parallel execution backends

Dependencies: [04](04-open-boundaries-and-forces.md). Related components: [numerical model](NUMERICS.md), [runtime integration](REPOSITORY_MAP.md).

## Feature

Serial, ForkJoinPool, and persistent static workers advance the same fluid simulation through a common destination-owned brick interface.

## Components

Immutable `Brick` and `BrickPlan` descriptors contain stable IDs, half-open XYZ ranges, and active-fluid-cell counts. Configurable independent XYZ dimensions support partial edge bricks. The default shape is `16x16x16`; fully solid bricks are excluded consistently across backends.

`ExecutionBackend` owns preparation, step execution, and shutdown. A step returns successfully after all bricks have completed and their numerical writes are visible. The driver owns buffer swaps. A stable `StepContext` describes the generation; preallocated scratch and private reduction slots belong to each brick.

The serial backend traverses the same brick plan and range kernel. A dedicated ForkJoinPool uses a bulk range-splitting task tree with brick-range leaves. Task reuse follows completed task lifecycle and reinitialization. An external driver submits the root and awaits completion.

Persistent static workers retain fixed contiguous brick ranges across generations. They wait between steps and execute their assigned ranges without per-brick dispatch. This provides a bulk-synchronous baseline alongside task-based scheduling.

Worker budgets exclude the driver. Optional startup affinity uses the existing public `ThreadTools` APIs and is restored on worker exit. Reports describe requested/effective CPUs and exact, hinted, or unavailable affinity.

Generation accounting handles submission failure, cancellation, exceptions, interruption, and deadlines. Failure invalidates the next state; shutdown establishes worker quiescence before buffer release. Wait diagnostics identify outstanding work.

## Verification

Complete populations and derived fields are compared against serial across periodic, forced-wall, and open-boundary scenes. Per-cell arithmetic is shared; deterministic brick-order reductions make comparison independent of completion order. On the same JVM, equal operation order supports exact population agreement, with any tolerance explicitly identified.

Coverage includes multiple worker counts, fewer bricks than workers, non-cubic partial bricks, diagonal neighbors across brick corners, solid bricks, injected failures, repeated runs, and shutdown. Coordination tests use deterministic synchronization. External physical correctness comes from [phase 07](07-external-solver-validation.md); backend checks establish execution equivalence.

## Interface

```bash
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend fjp --workers 4
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend static --workers 4
```
