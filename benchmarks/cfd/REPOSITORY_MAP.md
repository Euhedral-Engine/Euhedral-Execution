# Repository integration map

The runtime integration points below were surveyed at commit
`3a4f5e8e46709b193b36cca61c021d313e4ccf2c`.
Paths are relative to the repository root. Phases 01-08 implement configuration, the periodic/walled
solver, solid geometry, forcing, open boundaries, obstacle forces, physical conversions, ordered
lattice execution, ParaView time-series output, STL import/voxelization, and external OpenLB validation.
[Phase 08](08-parallel-execution-backends.md) adds the shared simulation driver, parallel Euhedral,
FJP/static backends, and configurable source dispatch.

## Build and packaging

| Component                                                            | Role                                                                                                |
|----------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `settings.gradle.kts`                                                | Project registration, including the `:benchmarks:cfd` subproject                                    |
| `build-logic/src/main/kotlin/buildlogic.java-conventions.gradle.kts` | Java 21, Spotless/Palantir formatting, JUnit, and integration-test tagging                          |
| `benchmarks/build.gradle.kts`                                        | Existing examples of JMH processing, catalog aliases, logging resources, and distribution packaging |
| `benchmarks/cfd/build.gradle.kts`                                    | Standalone `euhedral-cfd` application distribution                                                  |
| `benchmarks/cfd/src/main/scripts/euhedral-cfd`                       | Launcher automatically copied to `benchmarks/cfd/build/bin` by assemble/build                       |
| `mise.toml`                                                          | Repository Java, Gradle, and native-build toolchain                                                 |

CFD has its own application entry point, uses Jackson for configuration, and depends on
`euhedral-core` for its `AbstractFrame` work units, queue ingest, and lattice execution. Runtime
libraries are bundled with the launcher. Inspection allocates no populations and starts no workers.
Building the module now includes core and its native-packaging dependencies. Publication tasks are
disabled for the benchmark application, and the root coverage aggregate excludes the entire
benchmarks subtree.

The `benchmarks/cfd/src/main/java/io/euhedral_execution/benchmarks/cfd/solver` package owns the
D3Q19 and open-boundary helpers, population buffers, macroscopic fields, reusable flow/force
reductions, and the shared simulation driver. The
`frames`
package owns reusable range bodies. The `execution` package owns range preparation, worker budgets,
backend options, and dispatch. `QueueIngestSink` feeds serial/parallel frames to the default lattice
with ordered/mixed hashes.
The `geometry` package resolves immutable cell masks before population allocation. `StlReader`
streams mesh metadata and checked coordinates; `TriangleMesh` validates welded surfaces and indexes
triangles; `GeometryRangeFrame` executes intersection checks and voxel ranges on the lattice through
`GeometryWork` queue sources. `Voxelization` reduces completed ranges and analyzes fluid connectivity. Configuration
owns the checked unit-conversion factors and runtime guard settings.
The `output` package streams completed fields to VTI, publishes PVD collections atomically, and
records configuration, CSV metrics, and run status. Output stays on the simulation driver; no
export work is added to numerical frames. See [SIMULATION.md](SIMULATION.md) for ownership and
failure contracts and [VISUALIZATION.md](VISUALIZATION.md) for artifacts and reader verification.

## Existing numerical workload

`benchmarks/src/main/java/io/euhedral_execution/benchmarks/core_benchmarks/HighScaleBenchmark.java` constructs a lattice, prepares `MandelbulbFrame` arrays, groups work in its batched variant, and registers `ArrayIngestSink` sources. Its setup also demonstrates socket-local allocation through `PinnedThreadExecutor`.

CFD extends the reusable-work-unit pattern to repeated generations. Persistent sources feed
independent range frames, and application-owned terminal acknowledgements establish timestep
completion. Phase 08 adds configurable source counts, including one source for the FJP comparison
and one per effective worker, with plain round-robin submission. Euhedral handles concurrent source
acquisition and worker distribution; sources are not assigned to particular workers.

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

`benchmarks/cfd/validation/openlb` contains the OpenLB case adapter and pinned build script. A separately installed, pinned OpenLB release performs reference computation in a child process. Case translation and field export connect that process to the validation runner. The Java numerical kernel and OpenLB retain separate implementations.

`benchmarks/cfd/validation/suites` contains matched cases and comparison tolerances. Validation artifacts associate reference output with resolved physical settings, solver versions, comparison locations, and times. The benchmark runner consumes these reports as numerical-eligibility evidence, verifies complete Java fields outside timing, and runs isolated JMH forks. [VALIDATION.md](VALIDATION.md) describes the implemented runner and qualification boundaries.

## Execution comparison

The `benchmark` package implements [Phase 10](10-jmh-and-comparison-runner.md). JMH core and its
annotation processor use the repository catalog aliases. The application build bundles generated
JMH metadata and runtime libraries; application distributions also include `suites`.
`BenchmarkRunner` starts reference/JMH processes sequentially, and `ValidationProcess` bounds their
process trees. `CfdBenchmark` retains one backend per fork, resets through `Simulation.reset()` in
invocation setup, and checks complete populations/fields in invocation teardown. Raw JMH scores,
per-fork validity, external evidence, and comparisons are separate artifacts.

`ReferenceWorker` can qualify a parallel backend against bounded serial fields before generating
the full-size reference. The stock suite uses FJP for that reference and omits serial timing.
Reference metadata and fork checks keep this correctness role separate from the speedup baseline.
