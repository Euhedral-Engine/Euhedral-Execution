# Phase 08 - Euhedral execution backend

Prerequisite: 07. Read [REPOSITORY_MAP.md](REPOSITORY_MAP.md) and re-read the actual `AbstractFrame`, `AbstractExecutor`, `QueueIngestSink`, `LatticeConfig`, and default-executor implementations before editing.

## Deliverable

The same CFD simulation runs on persistent Euhedral workers using reusable brick frames and a correct application-owned timestep barrier.

## Changes

- Add the `:euhedral-core` dependency and any directly used hardware/hash dependencies. Implement `EuhedralBackend` inside this module. Do not modify production policy, routing, calibration, queues, or executor classes.
- Own one `ControlPlaneLattice` for the backend lifetime. Use current default `BaseCloneableObject`/`DefaultExecutor` behavior with a resolved allowed-CPU configuration and bounded shutdown. Do not create a new lattice or call `clear()` per timestep.
- Compose a small configured number of persistent `QueueIngestSink` instances, register each once, and retain them until shutdown. `QueueIngestSink` is sealed; do not subclass it. Source count is a backend parameter, not one source per brick or timestep.
- Implement a reusable `CfdBrickFrame extends AbstractFrame`. Its body calls the shared kernel for exactly one brick and writes its private reduction slot. Preallocate one frame per brick; no cell frames and no extra PipelineFrame stage chain are needed.
- Set distinct, deterministic parallel routing hashes before publication. Equal ID/routing hashes mean ordered work; do not accidentally serialize the workload. A stable hash is not a guarantee of core locality.
- Before publishing any frames, initialize the generation ID, expected completion count, error state, and stable context. Offer each frame exactly once; an unsuccessful offer is an error or a bounded retry, never a dropped brick. Enqueue-to-terminal-completion time belongs to execution cost.
- Signal success only from a terminal path after numerical writes and reduction writes are complete. Record exceptions through `doFinallyWithError`. Structured cancellation may also reach `doFinally`; track whether the body completed successfully so cancellation cannot masquerade as success.
- Make the signal the frame's last access to mutable generation state. Reuse frames only after every terminal acknowledgement and a documented happens-before edge. Do not use `PaddedLongAdder.sum()`, sink size, or `completeGracefully()` as proof of success or publication.
- Give fatal failures a bounded escape path too: the engine's executor catches Exception, not arbitrary JVM Error. Record/report failure where safe and terminate/quiesce the run; do not pretend an OOM is recoverable or reuse buffers while a worker may still write them.
- Wait from the external driver, never from a Euhedral worker. On success the driver reduces, swaps, and starts the next generation. On failure stop production, cancel cooperatively, drain/close within a deadline, and mark the run failed without swapping.

## Acceptance

Add pure tests for frame terminal hooks and generation accounting. Add isolated integration tests for the real JVM-wide lattice: serial field equivalence, several source counts, repeated timesteps, partial bricks, normal close/recreate, failure, skipped execution/cancellation, and bounded timeout.

Use the repository's integration tag and JUnit isolation so unrelated singleton tests do not run concurrently. Report native-affinity limitations separately from solver failures. Record the effective worker/core selection, not just requested logical CPU count.

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:integrationTest :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend euhedral --workers 4
```

## Implementation prompt

```text
Implement benchmarks/cfd phase 08 only. Inspect current Euhedral APIs, then add an adapter using one owned lattice, persistent registered QueueIngestSink instances, and reusable AbstractFrame brick tasks over the existing CFD kernel. Implement success/error/cancel-aware terminal completion with explicit visibility and bounded failure handling. Never use queue draining as the timestep barrier or clear the engine between steps. Preserve production defaults and keep all code changes in the CFD module. Run pure tests plus isolated real-engine integration tests and report actual results.
```
