# Euhedral Execution

**A Java runtime for fine-grained work on multicore and NUMA machines.** Euhedral keeps workers
close to the CPUs they use, routes frames through socket and core-local lanes, and lets workers pull
work when they are ready. The normal path does not feed a shared global task queue.

In the included 8K Mandelbrot JMH workload, Euhedral was 10.92x faster than Reactor Parallel on a
192-core dual-socket AMD EPYC system. On the single-socket Intel and Graviton5 systems, the results
were much closer. The [full benchmark report](./benchmarks/HIGH_SCALE_BENCHMARKS.md) includes the
hardware, methodology, raw results, and limitations.

Use Euhedral when scheduling overhead, locality, ordering, or allocation behavior affect the
workload. A general-purpose executor is often the better choice for ordinary background tasks.

[Core quick start](./QUICK_START.md) |
[Reactor quick start](./REACTOR_QUICK_START.md) |
[Architecture](./docs/ARCHITECTURE.md) |
[Benchmarks](./benchmarks/README.md)

## What it does

- **Pull-driven execution.** Workers ask upstream sources for work as capacity becomes available.
- **Topology-aware placement.** The runtime discovers available CPUs, sockets, NUMA nodes, and
  cache groups. On Linux and Windows, workers can use hard CPU affinity; macOS uses locality hints.
- **Lane-scoped ordering.** Related frames can stay on an ordered routing lane while independent
  frames spread across active cores.
- **Per-worker control.** Workers respond to local queue state and hardware-pressure signals rather
  than one central scheduler decision.
- **Reusable work objects.** Frames and queues support batching and recycling for sustained,
  allocation-sensitive workloads.
- **Reactor support.** `EuhedralScheduler` implements Reactor's `Scheduler`, and
  `EuhedralOperator` provides `flatMap`, `flatMapSequential`, and `concatMap`.

## How work moves

```text
Frames:  Source -> Lattice -> Socket shard -> Core fragment -> Executor -> Frame.execute()
Demand:  Source <- Lattice <- Socket shard <- Core fragment
```

`ControlPlaneLattice` is the JVM-wide runtime. It owns the active topology, starts workers lazily
when an upstream source is attached, and manages their lifecycle. A shard represents a socket and
runs a pipeline on each active physical core.

Every frame has two hashes:

| Field         | Purpose                                                |
|---------------|--------------------------------------------------------|
| `idHash`      | Immutable frame identity and the default ordered lane. |
| `routingHash` | Selects the active socket and core for this execution. |

A frame starts with matching hashes. Frames from one source that keep the same routing hash stay on
a stable lane while the topology mapping remains stable. Call `randomizeHash(seed)` before ingestion
for independent work that can run in parallel. This is lane-scoped ordering, not a JVM-wide ordering
guarantee.

## Start with the right API

| You are building                                         | Start here                                               |
|----------------------------------------------------------|----------------------------------------------------------|
| A direct frame or function pipeline                      | [Core quick start](./QUICK_START.md)                     |
| A Reactor application                                    | [Reactor quick start](./REACTOR_QUICK_START.md)          |
| A Spring Boot service, Kafka consumer, or gRPC transport | [`euhedral-spring-core`](./euhedral-spring-core)         |
| A custom queue or atomic-heavy component                 | [`euhedral-data-structures`](./euhedral-data-structures) |

The Core quick start shows a complete `PipelineRunner` example. It covers ordered versus
distributed stages, direct frame ingestion, metrics, recycling, and shutdown. The Reactor quick
start covers operator choice, cancellation, buffers, and lifecycle.

## Modules

| Module                     | Purpose                                                                |
|----------------------------|------------------------------------------------------------------------|
| `euhedral-core`            | Frames, ingest, routing, the control plane, and execution.             |
| `euhedral-reactor-core`    | Reactor scheduler and mapping operators.                               |
| `euhedral-spring-core`     | Spring Boot, Kafka, and gRPC integration.                              |
| `euhedral-hardware-utils`  | Topology discovery, resource monitoring, affinity, and native support. |
| `euhedral-data-structures` | SPSC, SPMC, MPSC, and MPMC queues plus padded atomics.                 |
| `euhedral-hashing`         | Hashing and mixing used by routing.                                    |
| `benchmarks`               | JMH workloads, calibration harnesses, and the CFD application.         |

The lower-level hashing, data-structure, and hardware modules do not depend on Core. Reactor and
Spring sit above Core; benchmarks are outside the runtime path.

## Build from source

Build the repository with the pinned JDK 21 toolchain. Install the declared tools, then run Gradle
from the repository root:

```bash
mise install
gradle build integrationTest
```

The full build includes native libraries. See [BUILD.md](./BUILD.md) for Zig, LLVM inspection tools,
and macOS SDK setup. Native libraries target Linux, Windows, and macOS on x64 and arm64.

Applications using the runtime should include:

```text
-XX:+UseThreadPriorities
```

Thread-priority and affinity behavior also depend on operating-system support and process
permissions.

## Benchmark results

The published comparison uses 132,710,400 pre-allocated Mandelbrot subpixel operations per
invocation. It measures scheduling, routing, and execution overhead rather than setup allocation.

| System                                       | Euhedral Core | Reactor Parallel | Reactor BoundedElastic |
|----------------------------------------------|--------------:|-----------------:|-----------------------:|
| Intel Xeon 6, 96 physical cores              |  73.105 ns/op |     80.636 ns/op |          116.613 ns/op |
| AMD EPYC 9R45, 2 sockets, 192 physical cores |  82.175 ns/op |    897.263 ns/op |        1,066.103 ns/op |
| AWS Graviton5, 192 physical cores            |  48.132 ns/op |     51.507 ns/op |           45.339 ns/op |

Lower is better. These are focused JMH measurements on specific hosts and settings, not a general
ranking of schedulers. Read the [high-scale benchmark report](./benchmarks/HIGH_SCALE_BENCHMARKS.md)
before drawing conclusions or reproducing the workload.

## Status and license

The Core runtime has benchmark coverage. Public APIs and integrations are still evolving, with
current work focused on real-world workload coverage and integration examples, including CFD.

Euhedral Execution is licensed under the [Apache License 2.0](./LICENSE).
