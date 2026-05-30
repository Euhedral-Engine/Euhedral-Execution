# Euhedral Core Architecture

## TOC

<!-- TOC -->

* [Overview](#overview)
    * [Structure of Responsibility](#structure-of-responsibility)
* [Core Components](#core-components)
    * [ControlPlaneLattice](#controlplanelattice)
        * [System-wide orchestration layer](#system-wide-orchestration-layer)
        * [Responsibilities](#responsibilities)
        * [Behavior](#behavior)
    * [ControlPlaneShard](#controlplaneshard)
        * [Per-socket execution and coordination layer](#per-socket-execution-and-coordination-layer)
        * [Responsibilities](#responsibilities-1)
    * [ControlPlaneCache](#controlplanecache)
        * [Role Within Euhedral](#role-within-euhedral)
        * [Partitioned Scheduling](#partitioned-scheduling)
        * [Adaptive Weighting](#adaptive-weighting)
        * [Memory-Aware Rate Limiting](#memory-aware-rate-limiting)
    * [ControlPlaneFragment](#controlplanefragment)
        * [Core Execution Control Loop](#core-execution-control-loop)
        * [Responsibilities](#responsibilities-2)
        * [Control model](#control-model)
        * [Design goals](#design-goals)
        * [Scheduling behavior](#scheduling-behavior)
    * [AbstractFrame](#abstractframe)
        * [Base unit of execution](#base-unit-of-execution)
        * [Identity and routing](#identity-and-routing)
            * [Ordering and parallelism](#ordering-and-parallelism)
        * [Lifecycle](#lifecycle)
        * [Error handling](#error-handling)
    * [LatticeEdge](#latticeedge)
        * [Role in the system](#role-in-the-system)
        * [Structural data flow backbone](#structural-data-flow-backbone)
        * [Behavior](#behavior-1)
    * [LatticeVertex](#latticevertex-latticeedge-extension)
        * [Structural multi-branch routing implementation](#structural-multi-branch-routing-implementation)
        * [Routing model](#routing-model)
        * [Behavior](#behavior-2)
* [Summary](#summary)

<!-- TOC -->

# Overview

Euhedral Core is an execution framework built around **frames, adaptive scheduling, and
topology-aware control**.

It is designed to:

- Execute work efficiently on pinned cores
- Adapt continuously to runtime and hardware conditions
- Prevent queue buildup and latency instability
- Maintain low overhead control loops

### Structure of Responsibility

Euhedral forms a hierarchical control structure:

- ControlPlaneLattice → manages the life cycles of shards
- ControlPlaneShard → manages the life cycles of fragments
- ControlPlaneFragment → manages scheduling on one core

This separation allows the system to scale across NUMA domains while keeping coordination overhead
localized.

---

# Core Components

## [ControlPlaneLattice](../euhedral-core/src/main/java/euhedral/io/control_plane/ControlPlaneLattice.java)

### System-wide orchestration layer

`ControlPlaneLattice` is the global coordination layer for Euhedral Core. It manages system
topology, shard placement, and cross-socket workload distribution. It is where work enters the
system.

It is responsible for keeping the execution system aligned with the current hardware configuration.

### Responsibilities

- System topology discovery and monitoring
- Global shard lifecycle management
    - creation
    - startup
    - rebalance
    - shutdown
- Enforcing user routing policies
- Ingress distribution across shards
- Global rebalancing in response to hardware changes
- Distributing socket-level resource utilization reports

### Behavior

The ControlPlaneLattice operates continuously in the background and reacts to changes in system
utilization and topology. When hardware conditions change, it performs a coordinated rebalance
across all affected shards.

At runtime, it remains minimally intrusive, delegating execution details to lower layers unless
structural changes require intervention.

---

## [ControlPlaneShard](../euhedral-core/src/main/java/euhedral/io/control_plane/ControlPlaneShard.java)

### Per-socket execution and coordination layer

ControlPlaneShard is the per-socket counterpart to the ControlPlaneLattice. Each shard is
responsible for managing the topology of a single CPU socket.

It mirrors the responsibilities and behaviors of the lattice, but is scoped to its subset of cores.

### Responsibilities

- Coordinating ControlPlaneFragments assigned to cores on their socket
- Participating in system-wide rebalancing operations
- Ingress distribution across fragments
- Enforcing user routing policies
- Distributing core-level resource and utilization reports

---

## [ControlPlaneCache](../euhedral-core/src/main/java/euhedral/io/control_plane/ControlPlaneCache.java)

The Partitioned Deficit Round-Robin (DRR) Cache Layer

`ControlPlaneCache` is the memory-aware scheduling layer that sits between ingestion and execution.
It is responsible for buffering work, enforcing fairness, and regulating the rate at which execution
fragments receive frames.

Internally, the cache is composed of multiple queue partitions. Each partition maintains independent
scheduling state and is drained using a weighted deficit round-robin (DRR) model. This allows the
system to distribute execution opportunities fairly while remaining efficient under bursty or highly
variable workloads.

The cache does not run on its own dedicated thread. It is accessed manually by the
ControlPlaneFragments.

### Role Within Euhedral

ControlPlaneCache serves as the primary coordination point between ingestion and execution.

Its responsibilities include:

- Buffering incoming frames
- Fairly distributing execution opportunities
- Preventing partition starvation
- Regulating memory consumption
- Smoothing bursty traffic
- Providing execution fragments with work-ready buffers

This layer is the primary memory-aware rate limiter within Euhedral Core and is a major contributor
to the system's ability to maintain high throughput while preserving predictable latency under load.

### Partitioned Scheduling

Each partition tracks its own quota measured in bytes rather than frame count.

```java
int drainCount = queueRing.drain(partition, buffer, quota);
stats.quotaBytes -= drainBuffer.drainedBytes;
```

Using byte-based quotas prevents partitions containing large frames from monopolizing execution time
while still allowing small frames to flow efficiently.

ControlPlaneFragments continuously walk partitions, consuming available quota and refilling it as
needed. Work is therefore drained incrementally across many partitions rather than completely
emptying one partition before moving to the next.

### Adaptive Weighting

The cache continuously adjusts partition weights based on observed workload characteristics.

For each partition, ControlPlaneCache tracks:

- Average frame size
- Frame size variance
- Recent enqueue behavior
- Historical drain behavior

These measurements are used to dynamically adjust the partition's scheduling weight. Stable traffic
adapts quickly, while highly variable traffic is smoothed to avoid oscillation and quota thrashing.

This adaptive weighting allows hot partitions to receive additional service while preventing them
from starving less active partitions.

### Memory-Aware Rate Limiting

Unlike traditional rate limiters that operate on request counts or time intervals, ControlPlaneCache
helps regulate execution using memory pressure and observed workload characteristics.

The cache tracks:

- Total queued frame count
- Total queued bytes
- Per-partition occupancy
- Per-partition drain rates

Execution fragments use these measurements to determine how aggressively they should request
additional work from upstream layers.

As a result, the cache naturally absorbs bursts while letting the fragments apply backpressure when
memory pressure exceeds healthy operating thresholds.

---

## [ControlPlaneFragment](../euhedral-core/src/main/java/euhedral/io/control_plane/ControlPlaneFragment.java)

### Core Execution Control Loop

`ControlPlaneFragment` is responsible for scheduling execution on a single core. It sits directly
between ingress and execution and continuously adjusts runtime behavior based on observed system
conditions.

### Responsibilities

- Concurrency control (in-flight frame limits)
- Dispatch pacing (rate of work consumption)
- Idle behavior management (spin → yield → park transitions)
- SMT coordination when enabled
- Backpressure handling and drain mode support

### Control model

The execution model combines:

- TCP Vegas-style latency estimation
- Little’s Law-based queue inference
- Hardware pressure signals

These are used to estimate how much work a core can safely sustain while maintaining stable latency
characteristics.

### Design goals

- Maintain high core utilization without oversubscription
- Keep queue depth bounded and stable
- Prevent latency amplification under load
- Avoid unnecessary cache contention and migration

### Scheduling behavior

Under load, the system increases throughput aggressively. Under contention, it reduces concurrency
and dispatch rate. When idle, it transitions progressively through:
spin → yield → park

Frames are the fundamental unit of execution. They represent small, composable units of work that
are processed in a streaming fashion through the execution pipeline.

---

## [AbstractFrame](../euhedral-core/src/main/java/euhedral/io/frames/AbstractFrame.java)

### Base unit of execution

`AbstractFrame` represents the fundamental unit of work in Euhedral Core. It encapsulates execution
state, routing metadata, and lifecycle management.

Frames are designed for reuse and are typically managed by a `FrameManager` to reduce allocation
overhead through recycling.

### Identity and routing

Each frame maintains:

- a stable identity hash (`idHash`)
- a mutable routing hash (`routingHash`)

The routing hash determines placement across execution units.

#### Ordering and parallelism

**Ordering**: preserve a stable hash across retries and reprocessing

```java
long seed = 123
long idHash = frame.getIdHash();
frame.randomizeHash(HasherApi.combine(idHash, seed));
```

**Parallelism**: vary the routing hash to distribute load across consumers

```java
long seed = 123;
frame.randomizeHash(seed++);
```

### Lifecycle

Frames follow a simple lifecycle:

- isAlive() → determines whether execution should continue
- kill() → forcefully terminates execution
- execute() → main execution logic
- throwMeAsError() → cancels in-progress execution
- reset() → prepares frame for reuse
- doFinally() → post-execution hook
- recycle() → returns frame to allocator

Frames are expected to be short-lived in execution but long-lived via reuse.

### Error handling

Frames can terminate execution immediately by throwing a controlled cancellation exception:

```java
frame.throwMeAsError();
```

This is handled by the execution layer as a structured cancellation signal.

---

## [LatticeEdge](../euhedral-core/src/main/java/euhedral/io/flow_control/LatticeEdge.java)

### Role in the system

LatticeEdge forms the structural layer between ingestion and execution. It defines how
components are connected without dictating execution semantics.

### Structural data flow backbone

LatticeEdge is responsible for building and maintaining the execution graph. It recursively
constructs a singular chain
of [LatticeInterceptors](../euhedral-core/src/main/java/euhedral/io/generics/LatticeInterceptor.java)
as they are connected.

### Behavior

- Upstream handles propagate up through the chain
- Terminal nodes define the bottom boundary
- Demand signals flow upward toward upstream producers
- Work flows downward through the structure


---

## [LatticeVertex](../euhedral-core/src/main/java/euhedral/io/flow_control/LatticeVertex.java) (LatticeEdge extension)

### Structural multi-branch routing implementation

LatticeVertex is a point where LatticeEdges meet. It is an extension of LatticeEdge and supports
fan-out routing across multiple downstream consumers.

### Routing model

Work is distributed deterministically using the frame's routing hash:

```java
int idx = (int) unsignedMultiplyHigh(frame.getRoutingHash(), mapSize);
this.downstreams[idx].onNext(frame);
```

### Behavior

- Enables parallel fan-out across downstream branches
- Maintains deterministic routing via hashing
- Used for load distribution across receivers

---

# Summary

The system is organized into a hierarchical control structure:

- ControlPlaneLattice → system-wide orchestration and topology management
- ControlPlaneShard → per-socket orchestration and topology management
- ControlPlaneFragment → per-core execution scheduling loop
- LatticeEdge → structural graph construction
- LatticeVertex → data routing and structural graph construction
- AbstractFrame → unit of work and execution state

Each layer is intentionally narrow in responsibility, allowing the system to scale by keeping
local reasoning simple and state ownership private.