# Phase 09 - Euhedral execution backend

Dependencies: [08](08-parallel-execution-backends.md). Existing APIs: [REPOSITORY_MAP.md](REPOSITORY_MAP.md).

## Feature

Persistent Euhedral workers execute reusable CFD brick frames through the shared numerical kernel and application-owned timestep barrier.

## Components

`EuhedralBackend` owns one `ControlPlaneLattice` for its lifetime. Resolved CPU membership and shutdown timeout configure the default `BaseCloneableObject`/`DefaultExecutor` pipeline. The CFD module depends on the runtime, hardware, and hashing libraries used by the adapter.

A configured number of composed `QueueIngestSink` instances remain registered across timesteps. One preallocated `CfdBrickFrame` per brick extends `AbstractFrame`; its body executes the shared range kernel and writes a private diagnostic slot. Source count is an adapter parameter.

Before dispatch, the driver publishes a stable step context, generation ID, expected terminal count, and error state. Each frame receives deterministic parallel routing metadata while out of flight and is offered exactly once. Rejected offers have bounded retry or failure behavior.

Terminal accounting distinguishes a successfully completed numerical body from structured cancellation and exceptions. Success publication follows the final population and diagnostic writes. The terminal signal is the frame's last access to mutable generation state; the acknowledgement protocol establishes the visibility and ownership boundary for reuse.

`doFinallyWithError` records execution exceptions. The adapter also has a bounded failure path for worker loss or fatal execution errors outside the executor's ordinary Exception handling. Timeout reporting identifies incomplete work.

The external driver awaits all successful terminal acknowledgements, reduces diagnostics, swaps buffers, and publishes the next generation. A failed generation stops production, requests cancellation, and reaches bounded quiescent shutdown before its buffers are reused or released. Source draining and end-to-end numerical completion remain distinct lifecycle states.

## Verification

Pure tests exercise success, cancellation, error hooks, generation publication, duplicate/missing acknowledgements, and safe reuse. Isolated real-lattice integration tests cover multiple source counts, repeated timesteps, partial bricks, serial field equivalence, failure, timeout, and close/recreate behavior.

Integration results record effective physical workers and native-affinity capability. The external reference suite supplies numerical evidence; identical-kernel checks establish that Euhedral preserves the verified result.

## Interface

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:integrationTest :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend euhedral --workers 4
```
