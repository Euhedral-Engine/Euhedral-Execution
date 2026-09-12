# Working on Euhedral

This guide covers repository work. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before changing
routing, worker lifecycle, topology, frames, or fragment policy. Implementation and tests are the
final authority when documentation disagrees with code. Read any nearer `AGENTS.md` for the area
you are changing, including [benchmarks/AGENTS.md](benchmarks/AGENTS.md) for benchmark work.

Euhedral is a pull-driven execution engine. Persistent workers request upstream frames through a
socket/core routing graph. Workers have managed logical CPU associations and request affinity;
exact physical placement depends on the platform.

## Start with scope and ownership

1. Run `git status --short`. Preserve unrelated edits, staged changes, generated files, and local
   benchmark output.
2. Identify the owning module, read its `build.gradle.kts`, and inspect its active
   `src/main/java/module-info.java` where present.
3. Read the implementation and nearest behavioral tests before changing a contract. Trace callers
   before treating a configuration field, comment, or helper as active runtime behavior.
4. Use the toolchain in [mise.toml](mise.toml). Keep changes within the requested task or phase.
5. Do not commit, push, delete user data, or rewrite unrelated files unless requested.

Data directories and experiment outputs can contain expensive local runs even when untracked.
Do not clean, regenerate, or overwrite them as incidental build preparation. Distinguish preparing
an experiment, validating its configuration, executing benchmarks, fitting models, and changing
production defaults; authorization for one does not automatically include the others.

## Modules and toolchain

The shared Java toolchain is Java 21. Mise currently selects Java 21, Gradle 9.6.1, Zig 0.16.0,
and apple-codesign 0.29.0. Verify the checked-in configuration rather than using system defaults.
Lower artifact targets do not authorize substituting another default JDK or Gradle version.

| Module                     | Java target | Ownership                                                   |
|----------------------------|------------:|-------------------------------------------------------------|
| `euhedral-hashing`         |          11 | xxHash64, mixing, and hash helpers                          |
| `euhedral-data-structures` |          11 | Concurrent queues, padded atomics, and adders               |
| `euhedral-hardware-utils`  |          17 | Topology, sampling, pressure, affinity, and JNI             |
| `euhedral-core`            |          21 | Control plane, routing, frames, ingest, policy, and metrics |
| `euhedral-reactor-core`    |          21 | Reactor scheduler, operators, and sequencing                |
| `euhedral-spring-core`     |          21 | Spring Boot, Kafka, and gRPC integration                    |
| `benchmarks`               |          21 | JMH suites and calibration harnesses                        |

Lower libraries feed into core; Reactor builds on core; Spring integrates core and Reactor. Keep
lower-level modules independent of core. Spring currently keeps `module-info.java.disabled` rather
than an active descriptor; benchmarks has no descriptor.

[python/pareto-weight-calibration](python/pareto-weight-calibration/) owns offline fitting and
export
tools. Its [pyproject.toml](python/pareto-weight-calibration/pyproject.toml) requires Python 3.12 or
newer. Use a suitable existing environment and run Python tests from that package directory.

## Build and validation

Run Java and Gradle commands through Mise:

```bash
mise install
mise exec -- java -version
mise exec -- gradle --version
```

Choose focused checks for the owning module:

```bash
mise exec -- gradle :euhedral-core:test :euhedral-core:spotlessCheck
mise exec -- gradle :euhedral-data-structures:test :euhedral-data-structures:spotlessCheck
mise exec -- gradle :euhedral-spring-core:test :euhedral-spring-core:spotlessCheck
```

A module test task builds its required dependencies; it does not run all upstream module tests.
Select upstream tests explicitly when their behavior is affected. Use `--tests` to narrow a test
run when appropriate. Shared JUnit configuration enables parallel tests, so isolate tests that
mutate process-wide state using the repository's existing JUnit patterns.

The normal build and the CI verification command are:

```bash
mise exec -- gradle build
mise exec -- gradle build integrationTest
```

`test` excludes the `integration` tag; `integrationTest` runs those tagged tests. Run the owning
module's `build` and relevant integration tests for native packaging or transport lifecycle changes.
Run the full build for cross-module changes, and include integration tests when their contracts are
affected. Documentation-only edits normally need link, reference, and diff checks rather than Java
compilation. Do not describe an empty or filtered test selection as successful behavioral coverage.

### Native build and environment

The hardware build follows this dependency chain:

```text
compileJava (JNI declarations) -> zigBuild -> copyNativeResources -> classes
```

Native sources and the product manifest live in
[euhedral-hardware-utils/src/main/native](euhedral-hardware-utils/src/main/native/).
The manifest drives platform products and the generated runtime catalog. Outputs go under
`euhedral-hardware-utils/build/generated-resources/native`; JNI declarations go under
`build/generated-jni`. Do not hand-edit generated headers, libraries, or catalogs.

The build cross-compiles Linux glibc/musl, Windows, and macOS products for x86_64 and arm64. Consult
[.github/workflows/build.yaml](.github/workflows/build.yaml) for SDK and tool setup. Mise configures
`SDKROOT`, `RCODESIGN`, and `ZIG`; native inspection checks also use LLVM tools. Missing native
tools,
SDKs, or platform capabilities must be reported separately from Java compilation failures. Hardware
unit tests include injected providers and clocks; do not assume they all require Docker or that a
host-only test proves every packaged native target works.

## Runtime contracts

### Control plane and topology

- `ControlPlaneLattice` owns the live JVM singleton, resource monitor, socket distributor, and
  shards. `addUpstream()` starts it lazily. Close it at its ownership boundary; closing releases
  the singleton for subsequent creation.
- Each shard owns one socket and clones one pipeline per active physical core. The default
  `BaseCloneableObject` connects a `ControlPlaneFragment` to an `AbstractExecutor`.
- Keep socket changes in the lattice and core changes in the shard. Fragments must not discover
  or rebuild global topology.
- Publish routing maps only while the relevant vertex is draining. Prepare handles and publish
  the mapping, connect new clones, drain existing workers, close removed workers, replace retained
  workers that miss the drain deadline, then resume ingest. Preserve timeout and shutdown paths.
- Effective CPUs come from configured, discovered, and currently available membership. The mapper
  reserves all logical siblings of physical core 0 when another usable physical core exists.
  Do not assume CPU IDs are dense or that logical CPU count equals worker count.
- `ControlPlaneLattice.clear(Duration[, Runnable])` is a destructive cache reset for trial
  isolation.
  Pause producers first. Fragment state must reset on its owner thread and acknowledge completion;
  the optional callback runs while ingest is frozen. It is not normal application shutdown.

### Sources, routing, and ordering

`LatticeEdge` provides connectivity; `LatticeVertex` adds routing and optional shared remote caches.
Worker-local `UpstreamQueue` instances share registered interceptor handles. Handle acquisition
serializes `request` and `pull` for that registration; it does not protect arbitrary direct calls
to the source or duplicate registrations.

A source must ignore non-positive demand, stop at its demand limit, honor pull stop conditions,
and complete its downstream once. `pull` consumes work already available; it must not generate new
work. Preserve direct-pull stopping at ordered frames so they use the request-and-route path.

`AbstractFrame.idHash` is immutable. Equality of `routingHash` and `idHash` marks ordered routing;
`randomizeHash(seed)` enables parallel placement. Keep routing metadata, origin, and payload stable
while a frame is in flight. Equal routing hashes share a lane under a stable routing policy and
mapping; ordering does not extend across independent sources or restore original input order after
parallel pipeline stages.

`SOCKET_LOCAL` and `CACHE_LOCAL` depend on the captured frame origin. Missing or inactive origins
fall back to hash routing. Test sparse active socket/core sets: the current locality callbacks
return physical IDs where the vertex expects dense indexes, so those policies can misroute or fail.
Do not assume locality is correct merely because contiguous-ID tests pass.

### Completion and recycling

`AbstractExecutor` checks liveness, executes the frame, and calls `doFinally()` on success or
structured cancellation. An uncaught execution `Exception` selects `doFinallyWithError()` instead;
this boundary does not catch arbitrary JVM `Error` instances.

`AbstractFrame.CancelSignal` is stackless internal control flow. Broad exception handling must not
turn it into an application error. `kill()` changes an optional shared kill switch; it neither
interrupts a running body nor affects frames without a switch. `throwCancelSignal()` exits the
current execution.

`FrameFactory.replace()` remembers prior parallel state, restores `routingHash` to `idHash`, runs
the replacement callback, and randomizes again when the old frame or callback requests parallel
routing. Creation and replacement apply factory seeds and the origin captured at factory
construction. Test fresh and recycled routing behavior. Factory consumption has one owner;
workers may return frames concurrently through the bounded MPSC recycler.

Most frames recycle through completion hooks. `PipelineFrame` schedules each stage separately and
recycles the root when the chain terminates, filters, cancels, or errors.
`CallbackFrame.doFinally()`
is intentionally empty: the response owner controls successful reuse; its inherited error hook
still recycles. Do not unify these lifecycles.

Wait for terminal results before completing a `PipelineRunner`. Its stages enqueue subsequent work
back into the same sink; `completeGracefully()` seeing an empty queue is not an end-to-end
completion
barrier. `EuhedralScheduler.dispose()` closes its lattice, so coordinate shared-runtime ownership.

### Fragment policy and caches

`ControlPlaneFragment.cycle()` drains local work, selects a path with `FragmentDecisionTree`, and
tries remote cached work before further source participation:

- `DIRECT` pulls upstream work directly and requests routed work on a miss.
- `STAGED` requests routed work and drains caches again.
- `CACHE` consumes cached work without initiating upstream source acquisition and parks only when
  the cycle makes no progress.

Preserve body-history guards, rank semantics, scarcity gating, and work-before-park behavior.
Decisions occur per cycle; batch sizing updates at completed batches using measured service time.
Local MPSC caches are growable; their soft capacity factor reduces demand targets rather than
imposing a hard memory bound. Batch pressure caps are separate.

`FragmentDecisionWeights` holds configured thresholds. `MicroCalibrator` converts synthetic-work
weights to worker-local nanoseconds; a weight is not a literal duration. Participation coefficients
come from the generated `ParticipationLogisticModel`, and default CACHE timing coefficients trace
to [cache-scarce-v1.json](python/pareto-weight-calibration/policies/cache-scarce-v1.json).
`CacheTimingConfig.DEFAULT` includes a bounded adaptive timing function and scarcity gate; the
fixed-timing constructor is a separate configuration. The retained `paretoWeights` field does not
supply the live participation coefficients, and the ordinary idle-band helper has no active-loop
caller.

Keep fitting and candidate search in the offline tools. Regenerate learned model code through its
exporter and preserve model/dataset provenance. When changing policy, inspect the tree tests,
`ParticipationLogisticModelTest`, `ProductionCacheTimingConfigTest`, and `CacheScarcityGateTest`.
Preserve Java/Python parity and frozen fixtures when the task affects exported policies. Do not
promote a benchmark finding to a production default without that scope being requested.

## Concurrency and hardware rules

Memory access modes are part of the design:

| Mode            | Intended use                                         |
|-----------------|------------------------------------------------------|
| Plain           | Thread-confined or externally ordered state          |
| Opaque          | Weakly ordered polling where freshness is sufficient |
| Acquire/release | Publication and visibility boundaries                |
| Volatile/CAS    | Coordinated state transitions                        |

Explain the happens-before argument when changing VarHandle access modes. Stronger operations can
add hot-path cost; weaker operations can break publication. Use ordinary JDK atomics for lifecycle
coordination and the data-structures module's padded types for hot shared counters.

Choose SPSC, SPMC, MPSC, or MPMC queues from the actual producer/consumer topology. Use bounded
queues where backpressure is required, chunked queues where growth is allowed, and partitioned
queues where contention should be distributed. Prefer batch `drain` and `fill`; do not assume
partitioned queues support iteration, and use `sizeLong()` where the type provides it.

Avoid allocations, streams, blocking I/O, string formatting, and info-level logging in fragment
cycles, vertex routing, queue operations, and per-frame execution/completion. Every spin or wait
must have a progress, shutdown, or timeout condition. Prefer deterministic synchronization to
arbitrary sleeps in tests.

Use `PinnedThreadExecutor` or `ThreadTools` for affinity. Distinguish `EXACT`, `LOCALITY_HINT`, and
`UNSUPPORTED` capabilities; managed CPU identity is not proof of physical placement. Include new
owner-local arrays and queues in `firstTouch()` where appropriate, preserving executor ownership.

Keep OS sampling, counter-delta/freshness processing, pressure evaluation, and topology mapping in
the hardware module. Preserve signal validity and the monitor's bounded latest-value listener
dispatch. Use injected providers and clocks to test missing signals, membership changes, and slow
listeners without relying solely on the host's current state.

## Editing and testing conventions

- Follow surrounding Java layout and the configured Spotless formatter. Scope formatting to
  intended files; do not reformat unrelated work.
- Use SLF4J placeholders and pass a throwable last. Preserve JSpecify contracts where present.
- Validate constructor and record invariants at boundaries. Update active module exports when
  public package boundaries change.
- Use ordinary ASCII in comments and docs. Keep comments focused on ownership, ordering, memory
  semantics, or non-obvious performance constraints.
- Generated gRPC classes come from
  [GrpcTransportService.proto](euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/transport/grpc/protos/GrpcTransportService.proto).
  Edit the schema and regenerate with matching tooling; do not hand-edit generated Java. The
  current Spring Gradle file does not configure protobuf generation, so `build` alone does not
  regenerate these checked-in classes.
- Preserve native outputs, Zig caches, Gradle build directories, and benchmark artifacts unless
  their cleanup is explicitly part of the task.

Match regression coverage to the changed contract:

| Change           | Required behavioral coverage                                                             |
|------------------|------------------------------------------------------------------------------------------|
| Routing          | Stable/randomized hashes, inactive targets, sparse IDs, and remapping                    |
| Sources          | Demand bounds, pull stopping, completion, and concurrent handles                         |
| Frames           | Success, cancellation, exceptions, filtering where applicable, and reuse                 |
| Queues           | Matching producer/consumer topology, capacity edges, and chunk rollover                  |
| Topology/workers | Startup, add/remove, drain timeouts, reset acknowledgments, and close                    |
| Hardware/native  | Sampling validity, affinity capability, packaging/catalogs, and relevant platform checks |
| Transports       | gRPC readiness/terminal behavior or Kafka partition liveness and offset ordering         |

Close lattices, pinned executors, native monitors, channels, and any containers in teardown. Kafka
completion marks acknowledgments ready; commits must not pass unfinished earlier offsets. Reactor
parallel execution and ordered output are different contracts and need corresponding assertions.
Performance claims require JMH evidence; unit tests and isolated wall-clock runs do not establish
throughput improvements.

## Before handing work back

1. Search changed areas for stale names, dead links, and outdated callers.
2. Run focused validation, then the broader checks justified by the changed contracts.
3. Inspect `git diff --check`, the full intended diff, and `git status --short`.
4. Confirm unrelated state and user-owned outputs are preserved.
5. Report what changed, actual tests run, and any failures or untested scope with precise reasons.
   Keep uncommitted work, unexecuted benchmarks, and proposed default changes labeled accurately.
