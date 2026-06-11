# Euhedral Execution

Welcome to **Euhedral**, an execution system designed to adapt to both the workloads it receives and
the hardware it runs on.

At it's core is **euhedral-core**, a pull-based execution engine that treats CPUs like a
distributed system.

Instead of assigning work from the top down, Euhedral lets execution emerge from demand. Persistent
workers continuously pull, route, and redistribute work through a structure shaped around cache and
NUMA boundaries.

### What it does automatically

- Detects CPU, core, cache, and NUMA topology
- Pins execution loops to specific CPUs
- Routes work using deterministic partitioning
- Rebalances as topology or load changes
- Preserves ordering for related work streams
- Tunes dispatch rate and concurrency based on observed runtime pressure

---

## [Quick Start](./QUICK_START.md)

---

## Benchmarks

Euhedral was built by measuring execution behavior rather than optimizing for theoretical scheduling
strategies. The benchmarks below focus on end-to-end latency, throughput, and irregular workloads,
with comparisons against existing schedulers where appropriate.

### Amazon EC2 (server workloads)

- [Graviton4 (32 vCPU)](./benchmarks/AMAZON_GRAVITON_4_BENCHMARKS.md)
- [Intel Xeon 6 (32 vCPU)](./benchmarks/AMAZON_XEON_6_BENCHMARKS.md)
- [Graviton4 (192 vCPU)](./benchmarks/AMAZON_GRAVITON_4_192_CORES_BENCHMARKS.md)

---

### Intel i9-14900K

Setup:

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

Euhedral only requires

```
-XX:+UseThreadPriorities
--add-opens java.base/java.util=ALL-UNNAMED
```

### End to End Latency

Each invocation executes 100K no-op frames and measures routing, scheduling, and dispatch overhead.

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

32M no-op frames per run across all cores.

```
Throughput:   0.037 ops/ns = 37,000,000 ops/s
Alloc Rate:   2.746 B/op
p50:          27.000 ns/op
p99:          30.000 ns/op
p9999:        30.000 ns/op
```

### Mandelbrot

A deliberately chaotic workload designed to stress memory locality.

Renders an 8K [Mandelbrot set](https://en.wikipedia.org/wiki/Mandelbrot_set) using:

- 2X SSAA
- 5,000 max iterations
- randomized pixel ordering

Workload:

- 33,177,600 **frames**
- 132,710,400 **total operations**

```Average time: 410ns/op```

---

## Execution Flow

```
    SYSTEM VIEW                               HARDWARE ANALGOY
--------------------------------------------------------------------------------

ControlPlaneLattice            ->        VCCSA / System Agent (global uncore)
↓
ControlPlaneShard              ->        Socket / NUMA Partition
↓
ControlPlaneCache              ->        L2/L3 Cache + Cache Controller
↓
WorkRequester                  ->        L1 Data Cache + Prefetcher + Cache Refill Logic
↓
ControlPlaneFragment           ->        Core Power & Demand Generation (the active execution loop)
↓
AbstractExecutor               ->        Execution Pipeline
↓
AbstractFrame.execute()        ->        Compute Kernel
```

There is no central scheduler assigning tasks to threads. Work is pulled through the system based on
demand.

---

## Core Model

### Frames are the unit of execution

Work is represented as lightweight reusable `AbstractFrame` instances:

```java
public abstract void execute();
```

Frames are intentionally small and designed to move easily through the system. They are cheap to
schedule, cache-friendly, and composable. When chained together, they naturally form pipelines
without needing a central coordinator.

### CPUs are treated as independent execution units

Each CPU runs its own pinned execution loop. Work is routed deterministically so that ordering is
preserved when needed and evenly distributed when it is not.

### Scheduling is adaptive

The system adjusts execution behavior based on observed conditions:

- queue pressure
- CPU load
- memory pressure
- backpressure
- drain rates
- topology changes

This happens continuously at runtime rather than through static configuration.

### Queues are topology-aware

Queues are sized and partitioned around cache boundaries. Higher-level queues align with shared
cache regions, while execution buffers are designed to stay close to L1.

The goal is to keep contention low and keep data moving through the cache hierarchy efficiently.

---

## Modules

### euhedral-core

The execution engine. It manages how work moves through the system and how execution loops stay
saturated without central scheduling.

#### Major Components

| Component                                                     | Description                                    |
|:--------------------------------------------------------------|:-----------------------------------------------|
| <span style="white-space: nowrap">ControlPlaneLattice</span>  | Global orchestration and topology management   |
| <span style="white-space: nowrap">ControlPlaneShard</span>    | Per-socket orchestration and worker management |
| <span style="white-space: nowrap">ControlPlaneCache</span>    | Cache-local work distribution queue            |
| <span style="white-space: nowrap">ControlPlaneFragment</span> | Adaptive pinned execution loop                 |
| <span style="white-space: nowrap">AbstractExecutors</span>    | Thin execution wrapper                         |
| <span style="white-space: nowrap">AbstractFrame</span>        | Base unit of work                              |

### euhedral-data-structures

Lock-free queues and padded atomics.

Includes SPSC, SPMC, MPSC, and MPMC queues in partitioned, bounded, and unbounded variants.

Optimized specifically for batch operations on the consumer side in high contention scenarios.

### euhedral-reactor-core

Reactor integration layer.

Provides:

- publishOn() / subscribeOn() support
- flatMap / flatMapSequential / concatMap compatibility
- Euhedral-backed execution pipeline

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

Fast deterministic hashing (xxHash64-based) used for routing, ordering, and load distribution.

---

## Architecture

Architecture documentation:

- [Architecture.md](./docs/ARCHITECTURE.md)

Architecture diagrams coming soon.

---

## Project Status

The core runtime is stable and benchmarked, but evolving.

Current focus areas:

- Benchmarks on high core count multi-socket systems
- Real-world workload testing
- More integration examples
- Dependency cleanup
- Documentation
