# Euhedral Core Architecture

## TOC

<!-- TOC -->
* [Euhedral Core Architecture](#euhedral-core-architecture)
* [Overview](#overview)
* [Core Components](#core-components)
  * [ControlPlane](#controlplane)
    * [System-wide orchestration layer](#system-wide-orchestration-layer)
    * [Responsibilities](#responsibilities)
    * [Behavior](#behavior)
  * [ControlPlaneShard](#controlplaneshard)
    * [Per-socket execution and coordination layer](#per-socket-execution-and-coordination-layer)
    * [Responsibilities](#responsibilities-1)
    * [Relationship to ControlPlane](#relationship-to-controlplane)
  * [ExecutionManager](#executionmanager)
    * [Core Execution Control Loop](#core-execution-control-loop)
    * [Responsibilities](#responsibilities-2)
    * [Control model](#control-model)
    * [Design goals](#design-goals)
    * [Execution behavior](#execution-behavior)
  * [AbstractFrame](#abstractframe)
  * [Base unit of execution](#base-unit-of-execution)
    * [Identity and routing](#identity-and-routing)
    * [Ordering and parallelism](#ordering-and-parallelism)
    * [Lifecycle](#lifecycle)
    * [Error handling](#error-handling)
  * [ScaffoldingEdge](#scaffoldingedge)
    * [Structural routing and execution backbone](#structural-routing-and-execution-backbone)
    * [Behavior](#behavior-1)
    * [Role in the system](#role-in-the-system)
  * [ScaffoldingNode (ScaffoldingEdge variant)](#scaffoldingnode-scaffoldingedge-variant)
    * [Multi-branch routing implementation](#multi-branch-routing-implementation)
    * [Routing model](#routing-model)
    * [Behavior](#behavior-2)
* [Summary](#summary)
<!-- TOC -->

# Overview

Euhedral Core is a low-level execution framework built around **frames, adaptive scheduling, and
topology-aware control**.

It is designed to:

- Execute work efficiently on pinned cores
- Adapt continuously to runtime and hardware conditions
- Prevent queue buildup and latency instability
- Maintain low overhead control loops

The system is composed of a small number of core components that each manage a specific layer of
execution responsibility. Together, they form a hierarchical control structure spanning from
system-wide orchestration down to per-core execution.

---

# Core Components

## [ControlPlane](../euhedral-core/src/main/java/euhedral/io/control_plane/ControlPlane.java)

### System-wide orchestration layer

`ControlPlane` is the global coordination layer for Euhedral Core. It manages system topology, shard
placement, and cross-socket workload distribution.

It is responsible for keeping the execution system aligned with the current hardware configuration.

### Responsibilities

- System topology discovery and monitoring
- Global shard lifecycle management
    - creation
    - startup
    - rebalance
    - shutdown
- CPU and socket-aware routing decisions
- Ingress distribution across shards
- Global rebalancing in response to hardware changes
- Global resource utilization reports

### Behavior

The ControlPlane operates continuously in the background and reacts to changes in system utilization
and topology. When hardware conditions change, it performs a coordinated rebalance across all
affected shards.

At runtime, it remains minimally intrusive, delegating execution details to lower layers unless
structural changes require intervention.

---

## [ControlPlaneShard](../euhedral-core/src/main/java/euhedral/io/control_plane/ControlPlaneShard.java)

### Per-socket execution and coordination layer

ControlPlaneShard is the per-socket counterpart to the ControlPlane. Each shard is responsible for
managing execution resources associated with a single CPU socket.

It mirrors the responsibilities of the ControlPlane, but scoped to its subset of cores.

### Responsibilities

- Managing execution within a single socket domain
- Coordinating ExecutionManagers assigned to cores on their socket
- Distributing socket-level resource and utilization reports
- Participating in system-wide rebalancing operations
- Maintaining local routing and execution consistency

### Relationship to ControlPlane

- The ControlPlane manages the entire system
- Each ControlPlaneShard manages one socket
- Together they form a hierarchical control structure:
    - ControlPlane → system-wide decisions
    - ControlPlaneShard → socket-local enforcement
    - ExecutionManager → per-core execution control

This separation allows the system to scale across NUMA domains while keeping coordination overhead
localized.

---

## [ExecutionManager](../euhedral-core/src/main/java/euhedral/io/ExecutionManager.java)

### Core Execution Control Loop

`ExecutionManager` is responsible for managing execution on a single core. It sits directly between
ingress and execution and continuously adjusts runtime behavior based on observed system conditions.

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

### Execution behavior

Under load, the system increases throughput aggressively. Under contention, it reduces concurrency
and dispatch rate. When idle, it transitions progressively through:
spin → yield → park

Frames are the fundamental unit of execution. They represent small, composable units of work that
are processed in a streaming fashion through the execution pipeline.

---

## [AbstractFrame](../euhedral-core/src/main/java/euhedral/io/frames/AbstractFrame.java)

## Base unit of execution

`AbstractFrame` represents the fundamental unit of work in Euhedral Core. It encapsulates execution
state, routing metadata, and lifecycle management.

Frames are designed for reuse and are typically managed by a `FrameManager` to reduce allocation
overhead through recycling.

### Identity and routing

Each frame maintains:

- a stable identity hash (`idHash`)
- a mutable routing hash (`combinedHash`)

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
long idHash = frame.getIdHash();
frame.randomizeHash(HasherApi.combine(idHash, seed++));
```

### Lifecycle

Frames follow a simple lifecycle:

- isAlive() → determines whether execution should continue
- kill() → forcefully terminates execution
- execute() → main execution logic
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

## [ScaffoldingEdge](../euhedral-core/src/main/java/euhedral/io/flow_control/ScaffoldingEdge.java)

### Structural routing and execution backbone

ScaffoldingEdge is responsible for building and maintaining the execution graph. It dynamically
constructs a chain of routing and execution nodes as components are connected.

### Behavior

- Upstream handles propagate up through the chain
- Terminal nodes define execution boundaries
- Demand signals flow upward toward upstream producers
- Work flows downward through the structure

### Role in the system

ScaffoldingEdge forms the structural layer between ingestion and execution. It defines how
components are connected without dictating execution semantics.

It acts as the foundational routing infrastructure for the system.

---

## [ScaffoldingNode](../euhedral-core/src/main/java/euhedral/io/flow_control/ScaffoldingNode.java) (ScaffoldingEdge variant)

### Multi-branch routing implementation

This variant of ScaffoldingEdge supports fan-out routing across multiple downstream consumers.

### Routing model

Work is distributed deterministically using frame hash:

```java
int idx = (int) unsignedMultiplyHigh(frame.getCombinedHash(), mapSize);
this.downstreams[idx].onNext(frame);
```

### Behavior

- Enables parallel fan-out across downstream branches
- Maintains deterministic routing via hashing
- Used for load distribution across consumers

---

# Summary

The system is organized into a hierarchical control structure:

- ControlPlane → system-wide orchestration and topology management
- ControlPlaneShard → per-socket execution domain management
- ExecutionManager → per-core execution control loop
- ScaffoldingEdge → structural routing and graph construction
- AbstractFrame → unit of work and execution state

Each layer is intentionally narrow in responsibility, allowing the system to scale while keeping
local reasoning simple.