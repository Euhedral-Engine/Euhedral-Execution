# Repository integration map

The existing integration points below describe `Euhedral-Engine/Euhedral-Execution` at commit `3a4f5e8e46709b193b36cca61c021d313e4ccf2c`. Paths are relative to the repository root. CFD component names describe the planned application.

## Build and packaging

| Component | Role |
| --- | --- |
| `settings.gradle.kts` | Project registration, including the planned `:benchmarks:cfd` subproject |
| `build-logic/src/main/kotlin/buildlogic.java-conventions.gradle.kts` | Java 21, Spotless/Palantir formatting, JUnit, and integration-test tagging |
| `benchmarks/build.gradle.kts` | Existing examples of JMH processing, catalog aliases, logging resources, and distribution packaging |
| `benchmarks/cfd/build.gradle.kts` | Planned standalone `euhedral-cfd` application distribution |
| `mise.toml` | Repository Java, Gradle, and native-build toolchain |

CFD uses project dependencies on the runtime libraries and its own application entry point. Its packages separate numerical computation from execution adapters, reference-solver integration, and reporting. Publication tasks are disabled for the benchmark application.

## Existing numerical workload

`benchmarks/src/main/java/io/euhedral_execution/benchmarks/core_benchmarks/HighScaleBenchmark.java` constructs a lattice, prepares `MandelbulbFrame` arrays, groups work in its batched variant, and registers `ArrayIngestSink` sources. Its setup also demonstrates socket-local allocation through `PinnedThreadExecutor`.

CFD extends the reusable-work-unit pattern to repeated generations. Persistent sources feed brick frames, and application-owned terminal acknowledgements establish timestep completion.

## Euhedral runtime integration

The following paths are beneath `euhedral-core/src/main/java/io/euhedral_execution/core/`.

| File | Runtime behavior | CFD integration |
| --- | --- | --- |
| `control_plane/ControlPlaneLattice.java` | JVM-wide singleton; lazy startup through `addUpstream`; owned shutdown | One lattice per backend lifetime |
| `config/LatticeConfig.java` | Allowed CPUs, shutdown timeout, and base shard | Resolved worker mask with default runtime wiring |
| `impl/BaseCloneableObject.java` | Default fragment and executor composition | Production execution pipeline |
| `impl/DefaultExecutor.java` | Calls `AbstractFrame.execute()` | Shared brick-kernel invocation |
| `frames/AbstractFrame.java` | Execution and terminal hooks, optional kill switch, routing metadata | Reusable brick frame with generation accounting |
| `generics/AbstractExecutor.java` | Success and structured cancellation reach `doFinally`; exceptions reach the error hook | Separate numerical-success, cancellation, and failure states |
| `ingest/QueueIngestSink.java` | Sealed persistent source with `offer` and draining lifecycle | Composed sources registered once |

`docs/ARCHITECTURE.md` describes the routing graph and ownership model. Physical core 0 can be reserved, logical siblings share physical cores, and origin-based routing includes fallback behavior. The adapter records effective workers and observed placement alongside requested settings.

## Affinity and completion

`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/ThreadTools.java` exposes affinity capability, CPU identity, affinity application, and restoration. Baseline workers use these APIs at thread startup and shutdown. Reported capabilities distinguish exact placement, locality hints, and unsupported affinity.

Brick completion publishes numerical writes and private diagnostic reductions. The external driver advances the generation after all successful terminal acknowledgements. Queue draining describes source state; the application barrier describes completed computation.

## External reference integration

`benchmarks/cfd/validation/openlb` contains the planned OpenLB case adapter and build configuration. A separately installed, pinned OpenLB release performs reference computation in a child process. Case translation and field export connect that process to the validation runner. The Java numerical kernel and OpenLB retain separate implementations.

`benchmarks/cfd/validation/suites` contains matched cases and comparison tolerances. Validation artifacts associate reference output with resolved physical settings, solver versions, comparison locations, and times. The benchmark runner consumes these reports as numerical-eligibility evidence.
