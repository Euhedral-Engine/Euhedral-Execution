# Phase 07 - Parallel execution backends

Prerequisite: 04. Phases 05-06 are not required to implement this phase. Read the [master plan](README.md), [NUMERICS.md](NUMERICS.md), and the affinity section of [REPOSITORY_MAP.md](REPOSITORY_MAP.md).

## Deliverable

The same 3D solver runs through serial, ForkJoinPool, and persistent static-worker backends, with explicit timestep completion and equivalent numerical results.

## Changes

- Add immutable `Brick`/`BrickPlan` descriptors with stable IDs, half-open XYZ ranges, active-cell counts, and partial-edge bricks. Default to `16x16x16`, but support independent XYZ dimensions. Skip fully solid bricks consistently for every backend and count actual fluid updates.
- Define a small `ExecutionBackend` ownership boundary with start/prepare, execute-step, and close operations. `executeStep` returns normally only after all work succeeded and all writes are visible. No backend swaps buffers or changes physics.
- Keep a stable `StepContext` for the duration of a dispatch. Preallocate descriptors, per-brick scratch, and reduction slots. Publish a new generation only after the previous one has finished. Do not allocate cell data or closures in the inner loops.
- Adapt the serial driver to the same brick plan and kernel. Preserve the independent tiny-grid numerical reference in tests.
- Implement ForkJoinPool with a dedicated pool and a reusable range-splitting task tree or equivalent bulk task design. Submit a root task; split at brick-range boundaries and join normally. Never block every worker on a global latch while unscheduled bricks remain. Reinitialize reusable tasks only after actual completion, not merely after their last numerical write.
- Implement a persistent static-worker backend that assigns fixed contiguous ranges of the same brick list to workers. Workers wait only between generations, outside their numerical work. This is the low-scheduling-overhead baseline; do not recreate threads each step or deliberately cripple its partitioning.
- Keep the driver outside the numerical worker budget. Use dedicated worker threads; avoid the common ForkJoinPool and unrelated application work. Add optional worker-start affinity through public `ThreadTools` APIs, restoring it on worker exit. Record actual capability and failures.
- Use bounded waits with error propagation. Cancellation or task failure invalidates the generation. Quiesce/close before releasing buffers; do not continue with a partially written grid. A timeout must identify outstanding work rather than spin forever.

## Acceptance

Compare complete populations and derived fields against serial across many steps with periodic, forced-wall, and inlet/outlet scenes. Since the per-cell operation order is shared, exact agreement should normally be achievable on one JVM; any tolerance must be explicit and justified. Reduce global diagnostics in stable brick order.

Test fewer bricks than workers, non-cubic/partial bricks, diagonal dependencies crossing brick corners, all-solid bricks, injected exceptions, submission failure, driver interruption, timeout, repeated runs, and shutdown. Use deterministic synchronization instead of sleeps.

Run at least two worker counts. Confirm the driver does not execute an uncounted share of the numerical work. Do not publish a speedup from unit-test timings.

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend fjp --workers 4
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend static --workers 4
```

## Implementation prompt

```text
Implement benchmarks/cfd phase 07 only. Introduce destination-owned 3D bricks, a completion-safe execution interface, and serial/ForkJoinPool/persistent-static backends over the exact same kernel. Use a real bulk ForkJoinPool decomposition and correctly reuse tasks. Keep the driver outside the worker budget and make every failure path bounded. Test cross-brick numerical equivalence, lifecycle, cancellation, and partial bricks. Do not integrate Euhedral yet or change solver equations to favor a backend.
```
