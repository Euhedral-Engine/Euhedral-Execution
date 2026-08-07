# Working on Euhedral

This file is the practical guide for making changes in this repository. Read
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) when a task touches routing, worker lifecycle,
topology, or frame semantics. The implementation and tests remain the final authority when a
document and the code disagree.

Euhedral is a pull-driven execution engine. Persistent workers are pinned to CPUs and ask upstream
sources for frames. Work moves down through a socket and core routing graph; demand moves back up.
Most performance and correctness constraints follow from that shape.

## Before changing anything

1. Run `git status --short` and preserve unrelated changes, generated data, and local benchmark
   output.
2. Identify the owning module and inspect its `build.gradle.kts` and `module-info.java`.
3. Read the nearest tests before changing a concurrency or lifecycle contract.
4. Use the repository toolchain from [`mise.toml`](mise.toml).
5. Do not commit, push, delete user data, or rewrite unrelated files unless the task explicitly asks
   for it.
6. Read [AGENT_WORKFLOW.md](docs/AGENT_WORKFLOW.md)

Several directories under `euhedral-training/input`, `euhedral-training/output`, and `data` may
contain expensive local runs. Treat them as user-owned even when they are untracked.

## Modules and language levels

All Java and Gradle build commands default to the exact tool versions selected by
[`mise.toml`](mise.toml), currently Java 21 and Gradle 9.6.1. Individual artifacts retain lower
release targets where possible; a lower target does not authorize a different default JDK or Gradle
version. A restricted-environment fallback is allowed only under the documented exception below
and must report the substituted versions and resulting limits.

| Module                     | Release | Main responsibility                                   |
|----------------------------|--------:|-------------------------------------------------------|
| `euhedral-hashing`         |      11 | xxHash64-based hashing and mixing                     |
| `euhedral-data-structures` |      11 | Concurrent queues and padded atomics                  |
| `euhedral-hardware-utils`  |      17 | Topology, resource monitoring, affinity, and JNI      |
| `euhedral-core`            |      21 | Control plane, frames, routing, ingest, and execution |
| `euhedral-spring-core`     |      21 | Spring Boot, Kafka, and gRPC integration              |
| `euhedral-training`        |      21 | Offline policy tuning and candidate benchmarking      |
| `euhedral-reactor-core`    |      25 | Reactor scheduler and operators                       |
| `benchmarks`               |      25 | JMH benchmarks                                        |

The dependency direction is broadly:

```text
hashing + data-structures + hardware-utils
                  -> core
                  -> reactor-core
                  -> spring-core

core + supporting modules -> training and benchmarks
```

Keep lower-level modules independent of `euhedral-core`. Integration code belongs in the Reactor or
Spring modules, not in the queue, hashing, or hardware layers.

## Build and test

Install and activate the pinned tools:

```bash
mise install
mise exec -- java -version
gradle --version
```

The normal repository check is the same one used by CI:

```bash
gradle build
```

To run integration tests
```bash
gradle build integrationTest
```


For a focused Java change, select the module and include its required upstream modules:

```bash
gradle :euhedral-core:test
gradle :euhedral-data-structures:test
gradle :euhedral-spring-core:test
```

Use `build`, not only `test`, when the change affects native packaging, generated protobuf code, or
integration-test lifecycle bindings:

```bash
gradle :euhedral-hardware-utils:build
gradle :euhedral-spring-core:build
```

The hardware module invokes Zig during Gradle's initialization phase, even for an ordinary compile. It
cross-builds native libraries for Linux, Windows, and macOS. A missing `zig`, JNI platform header,
or macOS SDK can fail the build before Java compilation begins. Use
[`.github/workflows/build.yaml`](.github/workflows/build.yaml) as the reference setup; it prepares
the cross-target JNI headers and macOS SDK before running `gradle build`.

Hardware resource tests use Testcontainers and need a working Docker daemon. Affinity tests also
depend on the CPUs exposed by the host or container. Report those environmental limits separately
from Java compilation failures.

For focused trainer work, the documented sequence builds upstream artifacts without compiling
their tests, then runs trainer tests:

```bash
gradle :euhedral-training:build -x test
gradle :euhedral-training:test
```

See [`euhedral-training/CLOSED_LOOP.md`](euhedral-training/CLOSED_LOOP.md) for packaging and runtime
properties. CUDA is not needed for ordinary compilation or CPU tests. The packaged GPU launcher
expects the exact PyTorch and CUDA versions described in
[`euhedral-training/GPU_SETUP_UBUNTU.md`](euhedral-training/GPU_SETUP_UBUNTU.md).

## Runtime invariants

### Control plane ownership

[
`ControlPlaneLattice`](euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneLattice.java)
is a JVM-wide singleton. It owns the resource monitor, global socket distributor, and shard
lifecycle. Tests and applications that create it must close it.

Each
[
`ControlPlaneShard`](euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneShard.java)
owns one socket and clones one worker pipeline per active physical core. The default
[
`BaseCloneableObject`](euhedral-core/src/main/java/io/euhedral_execution/core/impl/BaseCloneableObject.java)
connects a `ControlPlaneFragment` to an `AbstractExecutor`.

Do not change a routing table while work is flowing. The established sequence is:

1. Enter drain mode.
2. Prepare handles or clones.
3. Publish the complete new mapping.
4. Drain and close removed workers.
5. Resume ingest.

Keep socket changes in the lattice and core changes in the shard. Do not make fragments discover or
rebuild global topology themselves.

### Pull graph

[
`LatticeEdge`](euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeEdge.java)
links sources and receivers. Frames move downstream; `request` and `pull` travel upstream.
[
`LatticeVertex`](euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeVertex.java)
adds fan-out and optional remote caches.

Worker-local `UpstreamQueue` objects serialize access to each upstream handle. Preserve that
single-owner handoff when adding a new `LatticeSource`. A source must:

- ignore non-positive demand;
- stop after its requested limit;
- honor the pull stop condition;
- complete its downstream once;
- avoid generating work from `pull`, which may only consume work already available.

Use the existing ingest implementations and
[
`LatticeEdgeTest`](euhedral-core/src/test/java/io/euhedral_execution/core/flow_control/LatticeEdgeTest.java)
as templates.

### Routing and frame identity

Every `AbstractFrame` has an immutable `idHash` and a mutable `routingHash`.

- `idHash == routingHash` means ordered routing.
- `randomizeHash(seed)` marks the frame for parallel placement.
- Equal routing hashes use the same active socket and core mapping.
- Ordering is scoped to one source and routing lane, not to the whole process.
- Routing metadata and payload must not change after ingestion.

`SOCKET_LOCAL` and `CACHE_LOCAL` depend on `frame.origin`. `FrameFactory` captures an origin for
managed frames. A manually constructed frame has no origin unless the caller sets one.

Recycled frames pass through `FrameFactory.replace()`, which first restores `routingHash` to
`idHash`. A parallel replacement callback must make the frame unordered again. Test both the fresh
and recycled paths when changing frame creation.

### Frame lifecycle

The normal terminal path in
[
`AbstractExecutor`](euhedral-core/src/main/java/io/euhedral_execution/core/generics/AbstractExecutor.java)
is:

```text
isAlive -> execute -> doFinally
                   -> doFinallyWithError on an uncaught execution error
```

`AbstractFrame.CancelSignal` is a stackless internal control signal. If frame code catches broad
exceptions, it must not turn cancellation into an application error. `kill()` prevents future work;
`throwCancelSignal()` exits work already running.

Most frame implementations recycle in `doFinally()`. `CallbackFrame` deliberately does not, because
its response owner decides when reuse is safe. Preserve that distinction.

### Per-core policy and caches

`ControlPlaneFragment` is the hot per-core loop. Through its base classes it owns local cache
draining, remote pulls, direct upstream pulls, and demand generation. Its
`FragmentActionPicker` evaluates four actions from six normalized measurements plus a bias. That is
four groups of seven weights, or 28 values.

The runtime evaluates fixed weights only. Neural-network training belongs in `euhedral-training`.
Do not add DJL, PyTorch, corpus handling, or candidate search dependencies to `euhedral-core`.

Local fragment caches are MPSC structures with an owner consumer. A reset that clears one must run
on the owner thread and acknowledge completion. The `clear` path demonstrates this
handoff.

## Concurrency rules

Memory access modes are part of the design, not style:

- Plain access is for thread-confined or externally ordered state.
- Opaque access is for weakly ordered polling where freshness is enough.
- Acquire and release form publication boundaries.
- Volatile access and CAS are for state transitions that need total visibility.

Do not replace a VarHandle access with a stronger or weaker operation without explaining the
happens-before argument. Stronger is not automatically harmless in a hot loop.

Use normal JDK atomics for lifecycle and low-frequency coordination. Use the padded atomic types
from
`euhedral-data-structures` for hot shared counters where false sharing matters. The repository uses
both intentionally.

Choose a queue from the actual producer and consumer topology:

| Producers | Consumers | Queue family |
|----------:|----------:|--------------|
|         1 |         1 | SPSC         |
|         1 |      many | SPMC         |
|      many |         1 | MPSC         |
|      many |      many | MPMC         |

Use bounded variants when backpressure is part of the contract, chunked variants when growth is
allowed, and partitioned variants when contention should be spread across lanes. Prefer batch
`drain` and `fill` operations on hot paths. Do not rely on unsupported collection behavior such as
iteration on partitioned queues, and use `sizeLong()` when the exact type provides it.

Avoid allocations, streams, blocking I/O, string formatting, and info-level logging inside:

- `ControlPlaneFragment.cycle()`;
- `LatticeVertex.push()` and `pull()`;
- queue offer, poll, fill, and drain methods;
- per-frame `execute()` and completion paths.

Use `SpinWait`, `Thread.onSpinWait()`, bounded `LockSupport.parkNanos()`, or Awaitility in tests
according to the existing ownership pattern. Never add an unbounded busy wait without a shutdown or
timeout condition.

CPU affinity must go through `PinnedThreadExecutor` or `ThreadTools`. New per-core state that
allocates arrays or queues should participate in `firstTouch()` so its pages are touched near the
CPU that will own them.

## Source conventions

- Use four-space Java indentation and follow the surrounding file's layout.
- Use SLF4J parameter placeholders. Pass a throwable as the final logging argument.
- Use JSpecify annotations on public nullness contracts where the module already does so.
- Validate record and constructor invariants at the boundary.
- Use ordinary ASCII characters in comments and documentation; avoid uncommon Unicode symbols.
  Represent data flows with ASCII art such as `->`, `|`, and `+--` unless the user explicitly asks
  for another notation.
- Keep comments focused on ownership, ordering, memory semantics, or a non-obvious performance
  reason.
- If a public package is added or removed, update the module's `module-info.java`.
- Add tests in the owning module and name them after observable behavior.

The generated gRPC classes
`GrpcTransportServiceGrpc.java` and `GrpcTransportServiceMd.java` come from
[
`GrpcTransportService.proto`](euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/transport/grpc/protos/GrpcTransportService.proto).
Edit the proto, run the Spring module's generation phase, and review the generated diff. Do not hand
edit generated Java.

Native binaries under `euhedral-hardware-utils/src/main/resources/bin`, Zig caches, Gradle `build`
directories, training outputs, and benchmark output are build or run artifacts. Do not add or
remove them as part of an unrelated source change.

## Testing changes well

- Routing changes need stable-hash, randomized-hash, inactive-target, and remap coverage.
- Source changes need request, pull, completion, zero-demand, and concurrent-handle coverage.
- Frame changes need success, cancellation, error, and recycling coverage.
- Queue changes need the matching producer-consumer test and boundary cases around chunk rollover.
- Topology changes need startup, add/remove, drain timeout, and close coverage.
- Spring transport changes should cover unary and streaming behavior or Kafka partition and commit
  behavior as appropriate.
- Performance claims require JMH. Do not infer throughput from a unit test or one wall-clock run.

Prefer deterministic synchronization to arbitrary sleeps. Close lattices, pinned executors, native
monitors, channels, and containers in teardown so static state does not leak into the next test.

Before handing work back:

1. Search for stale names and references.
2. Run the narrowest meaningful tests, then `gradle build` for cross-module or native changes.
3. Inspect `git diff --check`.
4. Inspect `git status --short` and confirm only intended files changed.
5. Report tests that could not run and the exact environmental reason.

## Temporary hardware-utils overhaul status (P7)

- P0-P6 are complete.
- P7 is in progress.
  - P7 parent blueprint is complete ([`phase-7-macos-platform.md`](docs/blueprints/hardware-utils/phase-7-macos-platform.md)).
  - Child P7-A (Topology & Sysctl) implementation is complete ([`phase-7-macos-topology-model.md`](docs/blueprints/hardware-utils/phase-7-macos-topology-model.md)).
  - Child P7-B (Resource Provider & Signals) implementation and conformance audit are complete ([`phase-7-macos-resource-provider.md`](docs/blueprints/hardware-utils/phase-7-macos-resource-provider.md) and [`phase-7-macos-resource-provider-conformance.md`](docs/audits/hardware-utils/phase-7-macos-resource-provider-conformance.md)).
  - Child P7-C (Affinity, Timer & Native ABI) implementation and conformance audit are complete ([`phase-7-macos-affinity-native.md`](docs/blueprints/hardware-utils/phase-7-macos-affinity-native.md) and [`phase-7-macos-affinity-native-conformance.md`](docs/audits/hardware-utils/phase-7-macos-affinity-native-conformance.md)).
