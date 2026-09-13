# Repository integration map

Inspected source: `Euhedral-Engine/Euhedral-Execution`, main commit `3a4f5e8e46709b193b36cca61c021d313e4ccf2c`. Paths are relative to the repository root. The CFD names in the phase plans are new proposals; the paths below are existing integration references.

## Build and packaging

- `settings.gradle.kts`: explicitly includes each project. Add `include(":benchmarks:cfd")` in phase 01; the conventional directory is `benchmarks/cfd`.
- `build-logic/src/main/kotlin/buildlogic.java-conventions.gradle.kts`: Java 21, Spotless/Palantir formatting, JUnit, parallel test execution, and separate `integrationTest` tagging. It also adds Maven publishing/signing; disable publication tasks for the new benchmark application locally rather than changing shared conventions.
- `benchmarks/build.gradle.kts`: existing JMH annotation processor, Jackson, Commons Math, dependency distribution, and logging-resource examples. Reuse version-catalog aliases, not the parent benchmark artifact or its manifest.
- `AGENTS.md`: run through Mise, preserve local output, isolate singleton-sensitive tests, and limit changes to requested scope.
- `benchmarks/AGENTS.md`: calibration configuration and measurement conventions. Some historical execution-policy descriptions are stale; this is not a reason to import the calibration machinery into CFD.

## Existing Mandelbulb workload

`benchmarks/src/main/java/io/euhedral_execution/benchmarks/core_benchmarks/HighScaleBenchmark.java` constructs a lattice, prepares `MandelbulbFrame` arrays, groups work in the batched variant, registers `ArrayIngestSink` sources, and waits on a counter. It also contains socket-local allocation examples using `PinnedThreadExecutor`.

Reuse the idea of reusable work units and explicit runtime ownership. Do not copy its fixed image size, unbounded progress loop, or recreate/register sources every CFD timestep. Those are not a timestep-completion protocol.

## Euhedral adapter integration points

All paths in this section are beneath `euhedral-core/src/main/java/io/euhedral_execution/core/`.

| File | Observed contract | CFD use |
| --- | --- | --- |
| `control_plane/ControlPlaneLattice.java` and `docs/ARCHITECTURE.md` | One live JVM singleton; `addUpstream` starts lazily; close at the owner boundary | One lattice per Euhedral run/fork, not per step |
| `config/LatticeConfig.java` | Record containing allowed CPUs, shutdown timeout, and base shard | Construct from current defaults with the resolved CPU mask |
| `impl/BaseCloneableObject.java` | Default fragment and `DefaultExecutor` wiring | Keep production wiring/default policy |
| `impl/DefaultExecutor.java` | Calls `AbstractFrame.execute()` | No custom CFD executor required |
| `frames/AbstractFrame.java` | `execute`, `doFinally`, `doFinallyWithError`, optional shared kill switch; equal ID/routing hashes mean ordered | Reusable brick frame, explicit success/error/cancel completion |
| `generics/AbstractExecutor.java` | Successful and structured-cancel execution reach `doFinally`; exceptions reach the error hook; arbitrary JVM Errors are not caught there | Do not equate a finally callback with successful computation; give failures a bounded escape path |
| `ingest/QueueIngestSink.java` | Persistent `offer` API; sealed class; graceful completion observes draining | Compose sinks, never subclass them; register once and do not use drain as the timestep barrier |

`docs/ARCHITECTURE.md` documents routing and ownership. Physical core 0 may be reserved, logical siblings do not imply separate workers, and origin-based routing has fallback behavior. Neither a stable hash nor a locality preference proves physical placement.

## Affinity

`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/ThreadTools.java` provides `getAffinityCapability()`, `getCpu()`, `getCpuInfo()`, `setAffinity(...)`, and `releaseAffinity()`.

Use these existing public APIs for baseline worker affinity. Distinguish `EXACT`, `LOCALITY_HINT`, and `UNSUPPORTED`. Do not invent CPU IDs, assume contiguous topology, or close another component's registered pinned executor. Run backend comparisons sequentially in isolated processes.

## Change boundary

Production modules are dependencies, not editing targets. An unexpected missing contract should be reported before expanding scope. Keep CFD Java, tests, scenes, scripts, and documentation inside `benchmarks/cfd`; root changes should normally be only project inclusion and a small documentation link.
