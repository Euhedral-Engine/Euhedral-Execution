# Euhedral Execution

Euhedral is a pull-driven Java execution engine built around the machine it runs on.

Long-lived workers are pinned to CPUs and request work when they have capacity. Frames are routed
through a graph shaped around socket, cache, and core boundaries, so the execution model follows the
hardware instead of hiding it behind one central queue.

[Core quick start](./QUICK_START.md) |
[Reactor quick start](./REACTOR_QUICK_START.md) |
[Architecture](./docs/ARCHITECTURE.md) |
[Benchmarks](./benchmarks/README.md)

## Why Euhedral?

- **Pull-driven execution.** Workers create demand; a central dispatcher does not push tasks at
  threads.
- **Topology-aware routing.** Euhedral discovers the available CPUs, sockets, NUMA nodes, and cache
  groups, then pins persistent workers to that topology.
- **Ordering when it matters.** Related frames can share a stable routing lane. Independent frames
  can spread across the machine.
- **Adaptive per-core control.** Each worker adjusts how it pulls, drains, and executes work from
  current queue and system pressure.
- **Low-allocation pipelines.** Frames and queues are designed for batching, reuse, and predictable
  ownership.

This is not a general-purpose replacement for every executor. It is aimed at sustained,
fine-grained workloads where routing, locality, and coordination overhead are part of the problem.

## The execution model

```text
Frames:  Source -> Lattice -> Socket shard -> Core fragment -> Executor -> Frame.execute()
Demand:  Source <- Lattice <- Socket shard <- Core fragment
```

`ControlPlaneLattice` owns the process-wide topology and lifecycle. It creates a shard for each
active socket and a worker pipeline for each active physical core. Those workers pull frames from
upstream sources and execute them without a global task queue.

The basic unit of work is an `AbstractFrame`:

```java
public abstract void execute();
```

Every frame has two hashes:

- `idHash` is immutable and identifies the frame's ordered lane.
- `routingHash` selects an active socket and core.

They are equal by default, so frames from the same source with the same hash stay ordered on one
lane. Call `randomizeHash(seed)` before ingestion when work can run independently and should be
distributed. Ordering is scoped to a source and routing lane, not the entire JVM.

## A small Core pipeline

The built-in ingest sinks handle frame creation and recycling for common functions and consumers:

```java
ControlPlaneLattice lattice = ControlPlaneLattice.getOrCreate();
CountDownLatch finished = new CountDownLatch(4);

FunctionIngestSink<Integer, Integer> squares = new FunctionIngestSink<>(
        value -> value * value,
        result -> {
            System.out.println(result);
            finished.countDown();
        },
        false); // false preserves input order; true distributes the work

try {
    lattice.addUpstream(squares);
    squares.push(List.of(2, 4, 8, 16));
    squares.completeGracefully();

    if (!finished.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for Euhedral");
    }
} finally {
    squares.complete();
    lattice.close();
}
```

See the [Core quick start](./QUICK_START.md) for imports, setup, metrics, direct frame ingestion,
custom frames, and recycling.

## Reactor integration

`euhedral-reactor-core` implements Reactor's `Scheduler` API and provides Euhedral-backed mapping
operators:

```java
EuhedralScheduler scheduler = EuhedralScheduler.getOrCreate(lattice);
EuhedralOperator operator = new EuhedralOperator(scheduler);

List<String> results = Flux.range(1, 100)
        .transform(operator.flatMapSequential(value -> "item-" + value))
        .collectList()
        .block();
```

Use the scheduler with `publishOn` or `subscribeOn` when standard Reactor scheduling is the right
fit. Use `EuhedralOperator` when you want Euhedral's frame routing and recycling with `flatMap`,
`flatMapSequential`, or `concatMap`.

The [Reactor quick start](./REACTOR_QUICK_START.md) covers setup, operator semantics, cancellation,
and shutdown.

## Choose an entry point

| If you are building... | Start with... |
| --- | --- |
| A direct frame or function pipeline | [`euhedral-core`](./QUICK_START.md) |
| A Reactor application | [`euhedral-reactor-core`](./REACTOR_QUICK_START.md) |
| A Spring Boot service, Kafka consumer, or gRPC transport | [`euhedral-spring-core`](./euhedral-spring-core) |
| A custom queue or atomic-heavy component | [`euhedral-data-structures`](./euhedral-data-structures) |

## Repository modules

| Module | Purpose |
| --- | --- |
| `euhedral-hashing` | xxHash64-based hashing and mixing used by routing |
| `euhedral-data-structures` | SPSC, SPMC, MPSC, and MPMC queues plus padded atomics |
| `euhedral-hardware-utils` | Topology discovery, resource monitoring, affinity, and JNI |
| `euhedral-core` | Frames, ingest, routing, the control plane, and execution |
| `euhedral-reactor-core` | Reactor scheduler and mapping operators |
| `euhedral-spring-core` | Spring Boot, Kafka, and gRPC integration |
| `euhedral-training` | Offline tuning of the fixed runtime scheduling policy |
| `benchmarks` | JMH workloads and comparison harnesses |

The lower-level hashing, data structure, and hardware modules do not depend on the Core runtime.
Reactor and Spring are integration layers above Core. Training and benchmarks remain outside the
runtime path.

## Build from source

Euhedral uses Java 21 for the full repository and [mise](https://mise.jdx.dev/) to pin its build
tools:

```bash
mise install
mise exec -- java -version
mise exec -- mvn -B verify
```

Run applications with:

```text
-XX:+UseThreadPriorities
```

The hardware module cross-builds native libraries during Maven initialization. A full build also
needs Zig, target JNI headers, and a macOS SDK; the setup in
[`.github/workflows/build.yaml`](./.github/workflows/build.yaml) is the reference configuration.
Focused Core and Reactor builds are shown in their quick starts.

Linux and Windows are supported on x64 and arm64. macOS support is in progress.

## Architecture and benchmarks

The [architecture guide](./docs/ARCHITECTURE.md) contains the topology, data-flow, routing, frame
lifecycle, Reactor, and Spring diagrams. The
[closed-loop architecture](./docs/ML_CLOSED_LOOP_ARCHITECTURE.md) explains how scheduling policies
are trained offline and evaluated as fixed weights in the runtime.

Benchmark results and reproduction notes live with the benchmark suite:

- [Benchmark guide](./benchmarks/README.md)
- [Amazon Graviton5 results](./benchmarks/AMAZON_GRAVITON_5_BENCHMARKS.md)
- [High-scale comparison results](./benchmarks/HIGH_SCALE_BENCHMARKS.md)

Performance numbers are hardware- and workload-specific. Treat the published results as measured
reference points and use the included JMH workloads to evaluate your own target system.

## Project status

The Core runtime is stable and benchmarked, while the public APIs and integrations are still
evolving. Current work is focused on real-world workload coverage and integration examples.

Euhedral Execution is licensed under the [Apache License 2.0](./LICENSE).
