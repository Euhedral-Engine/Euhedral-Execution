# Euhedral Execution

Welcome to Euhedral. A NUMA-aware, lock-free, self-tuning execution framework for Java.

At the center of the project is euhedral-core:

A pull-based execution engine that treats CPUs like a distributed system. Threads are pinned to
hardware. Work is routed by deterministic hashing. Queues are sized around cache topology.
Scheduling adapts continuously based on observed runtime pressure.

Think:

- Reactive pipelines without the scheduler guesswork
- Lock-free execution without thread pools fighting each other
- Distributed-system-style routing, but entirely inside a single process
- Hardware-aware scheduling that understands the machine it runs on

The system automatically:

- Detects CPU, core, cache, and NUMA topology
- Pins workers to CPUs
- Rebalances under topology changes
- Preserves ordering guarantees for related work
- Tunes dispatch and concurrency dynamically under load

The result is a scheduler designed for extremely low latency, high throughput, and stable behavior
under chaotic workloads.

---

## [Quick Start](./QUICK_START.md)

---

## Architecture

Architecture documentation:

- [Architecture.md](./docs/ARCHITECTURE.md)

Architecture diagrams coming soon.

## Amazon EC2 Benchmarks

These benchmarks test performance in a cloud environment on server hardware. They feature
comparisons with the standard Reactor schedulers.

- [Amazon EC2 32 vCPU Graviton4](./benchmarks/AMAZON_GRAVITON_4_BENCHMARKS.md)
- [Amazon EC2 32 vCPU Intel Xeon 6](./benchmarks/AMAZON_XEON_6_BENCHMARKS.md)
- [Amazon EC2 192 vCPU Graviton4](./benchmarks/AMAZON_GRAVITON_4_192_CORES_BENCHMARKS.md)

The benchmarks in the sections below were performed on a consumer desktop.

---

## Quick Mental Model

```
    SYSTEM VIEW                               IMPLEMENTATION VIEW
--------------------------------------------------------------------------------

ControlPlaneLattice            ->        Edge Load Balancer (Global L7 Router)
↓
ControlPlaneShard              ->        Shard Load Balancer (Partition Router)
↓
ControlPlaneCache              ->        Ingress Gateway / Stateful Cache Layer
↓
ControlPlaneFragment           ->        Task Scheduler
↓
AbstractExecutor               ->        Worker Runtime
↓
AbstractFrame.execute()        ->        Compute Kernel
```

Everything below the ControlPlaneLattice is replicated according to hardware topology.

- Sockets become shards
- L2-sharing CPUs cooperate through localized queues (ControlPlaneCache)
- CPUs become pinned execution scheduling loops (ControlPlaneFragment)

---

## Why Euhedral Exists

General use schedulers do not fully take advantage of the hardware they run on.

**Euhedral optimizes for**:

- Stable latency
- Predictable execution
- Cache locality
- Hardware awareness
- Minimal coordination overhead
- Sustained throughput under pressure

It continuously adapts execution behavior using runtime feedback and scales to match the
physical reality of the system.

**Euhedral reacts to**:

- Queue residency
- CPU pressure
- Memory pressure
- Backpressure
- Drain rates
- Topology changes
- Effective CPU availability

**Goals**:

- Keep the machine busy without making it angry.
- Abstract complexity away from the user.
- Make it easy to use and compatible with what exists.

---

## Core Ideas

### Frames are the unit of execution

Work is represented as lightweight reusable AbstractFrame instances.

Frames are intentionally small:

```java
public abstract void execute();
```

They are designed to be:

- Recyclable
- Routable
- Composable
- Cache-friendly
- Cheap to schedule

Pipelines emerge naturally by chaining them in stages together.

### CPUs are treated like independent workers

Euhedral pins one execution loop per CPU and routes work deterministically.

Ordered work stays ordered because hashes stay stable.

Parallel work spreads evenly because hashes can be mixed.

```java
frame.randomizeHash(HasherApi.combine(idHash, seed++));
```

### Scheduling is adaptive

The ControlPlaneFragment continuously tunes execution behavior based on observed conditions.

It adjusts:

- Concurrency
- Dispatch pacing
- Idle behavior
- Demand signaling
- SMT coordination

Using:

- TCP Vegas-style latency modeling
- Little's Law
- Hardware pressure signals
- Queue residency observations

### Queues are topology-aware

ControlPlaneCache creates deficit round-robin queues sized around L2 cache topology.

Only CPUs sharing L2 consume from the same queue.

This dramatically reduces unnecessary cache traffic while maintaining balanced throughput.

## Benchmarks

Benchmarks were performed on:

- Intel i9-14900K
- 64GB DDR5
- Ubuntu 24.04.4 LTS
- Performance power mode
- Stock BIOS settings
- No overclocking

All work items were pre-allocated to measure scheduler and routing overhead.

JMH was used for all benchmarking.

#### VM Flags

All tests were ran with these same flags.

```
-XX:+UseThreadPriorities
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
--add-exports java.base/jdk.internal.platform=ALL-UNNAMED
--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
```

Euhedral itself only requires

```
-XX:+UseThreadPriorities
--add-opens java.base/java.util=ALL-UNNAMED
```

The remaining flags silence warnings from JMH and temporary dependencies.

### End to End Latency

Measures:

- Routing
- Scheduling
- Queue residency
- Dispatch overhead

Each invocation executes **100K** no-op frames.

#### E-Core Cluster (4 cores with shared L2)

```
Alloc Rate:  0.542  B/op
p50:         34.000 ns/op
p99:         38.000 ns/op
p9999        67.239 ns/op
```

#### P-Core Cluster (2 cores)

```
Alloc Rate: 0.192 B/op
p50:        61.000 ns/op
p99:        68.000 ns/op
p9999:      88.000 ns/op
```

### Throughput

32 million no-op frames per invocation utilizing all cores.

```
Throughput:   0.037 ops/ns = 37,000,000 ops/s
Alloc Rate:   2.746 B/op
p50:          27.000 ns/op
p99:          30.000 ns/op
p9999:        30.000 ns/op
```

### Mandelbrot

A deliberately chaotic workload

Rendered an 8K [Mandelbrot set](https://en.wikipedia.org/wiki/Mandelbrot_set) using:

- 2X SSAA
- 5,000 max iterations
- randomized pixel ordering

This benchmark intentionally destroys locality and creates highly irregular execution behavior.

Total tasks: 132,710,400
Average time: 410ns/op

## Modules

### euhedral-core

The execution engine.

#### Major Components

| Component                                                     | Description                                    |
|:--------------------------------------------------------------|:-----------------------------------------------|
| <span style="white-space: nowrap">ControlPlaneLattice</span>  | Global orchestration and topology management   |
| <span style="white-space: nowrap">ControlPlaneShard</span>    | Per-socket orchestration and worker management |
| <span style="white-space: nowrap">ControlPlaneCache</span>    | Cache-local deficit round-robin queue          |
| <span style="white-space: nowrap">ControlPlaneFragment</span> | Adaptive pinned execution scheduling loop      |
| <span style="white-space: nowrap">AbstractExecutors</span>    | Thin execution wrapper                         |
| <span style="white-space: nowrap">AbstractFrame</span>        | Base unit of work                              |

### euhedral-data-structures

Lock-free queues and padded atomics.

Includes partitioned, bounded, and unbounded queue variants of:

- SPSC
- SPMC
- MPSC
- MPMC

Designed specifically for high-core-count contention scenarios.

### euhedral-hardware-utils

Hardware topology and monitoring utilities.

Includes:

| Feature                                                       | Description                      |
|:--------------------------------------------------------------|:---------------------------------|
| <span style="white-space: nowrap">PinnedThreadExecutor</span> | CPU-pinned executor.             |
| <span style="white-space: nowrap">ResourceMonitor</span>      | Hardware telemetry aggregation   |
| <span style="white-space: nowrap">SystemInfo</span>           | Full topology discovery          |
| <span style="white-space: nowrap">ThreadTools</span>          | CPU affinity and timer utilities |

Supports:

- Linux
- Windows
- macOS (WIP)
- x64
- arm64

### euhedral-hashing

Fast deterministic hashing based on xxHash64.

Used for:

- Routing
- Ordering guarantees
- Load distribution
- Parallel fan-out

### euhedral-reactor-core

Reactor integration layer.

Provides:

- Reactor Scheduler support
- .transform() integration
- flatMap / map compatibility
- Euhedral-backed execution pipeline

---

## Project Status

The core runtime is operational and benchmarked, but documenation and API refinement are ongoing.

Current focus areas:

- Benchmark on EC2 bare-metal with 192 cpus on arm64 and x64
- More stress testing
- Additional architecture docs
- More integration examples
- Dependency cleanup
- Reactor integration improvements
