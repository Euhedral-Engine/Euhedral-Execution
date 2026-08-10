# System Signal and Data Flow Architecture Map

This document provides a comprehensive, end-to-end map of **Signal Flow** (control, demand, pressure, backpressure, and lifecycle signals) and **Data Flow** (frames, payloads, hashes, queues, and recycling) across the key modules of the **Euhedral Execution Engine**:

1. [`euhedral-hardware-utils`](file:///home/brandon/src/Euhedral-Execution/euhedral-hardware-utils/) - Hardware topology, thread affinity, and resource/pressure monitoring.
2. [`euhedral-core`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/) - Pull-driven control plane, shard routing, fragment worker loops, and frame execution.
3. [`euhedral-reactor-core`](file:///home/brandon/src/Euhedral-Execution/euhedral-reactor-core/) - Reactive Streams scheduler, operators, demand bridging, and frame sequencing.
4. [`euhedral-spring-core`](file:///home/brandon/src/Euhedral-Execution/euhedral-spring-core/) - Spring Boot integration, Kafka transport, and gRPC streaming handlers.

---

## 1. Architectural Overview: The Dual-Loop Engine

Euhedral is a **pull-driven**, topology-aware execution graph. Unlike traditional push-based or thread-pool systems, work moves downstream (Left -> Right) while demand signals flow upstream (Right -> Left). Concurrently, a hardware control loop feeds pressure and topology updates from bottom to top.

```text
========================================================================================================
                                      DATA FLOW (Payloads & Frames)
--------------------------------------------------------------------------------------------------------
[Ingest / Transport]   --->   [LatticeVertex]   --->   [Socket Shard]   --->   [Core Fragment Cache]   --->   [Executor / Execute()]
(Kafka, gRPC, Reactor)        (Global Routing)        (Socket Routing)         (L1/L2 Partitioned)           (Frame Recycling)
========================================================================================================
                                     SIGNAL FLOW (Demand & Controls)
--------------------------------------------------------------------------------------------------------
[Ingest / Transport]   <---   [LatticeEdge]     <---   [UpstreamQueue]  <---   [WorkRequester / Policy] <---   [Core Worker Loop]
(Backpressure/Commits)        (Demand Prop.)          (Handle Aggregation)     (Action Picker & Quota)       (Pinned Thread)
========================================================================================================
                                HARDWARE CONTROL LOOP (200ms Ticks)
--------------------------------------------------------------------------------------------------------
[OS Native Topology]   --->   [ResourceMonitor] --->   [SystemUtilization]---> [Lattice / Shard Rebalance]---> [Fragment Quota Tuning]
(JNI / SysFS / Win32)         (Background Tick)       (Pressure Snapshots)     (Drain & Atomic Remap)        (Batch Size Modulation)
========================================================================================================
```

---

## 2. Comprehensive Module Signal and Data Flow

```text
+------------------------------------------------------------------------------------------------------+
|                                        EUHEDRAL-SPRING-CORE                                          |
|                                                                                                      |
|  Kafka Broker                 gRPC Client                     Reactive Streams Publisher             |
|       |                           |                                       |                          |
|       v                           v                                       v                          |
|  [KafkaIngestSource]        [EuhedralGrpcServerHandler]           [EuhedralSubscriber]               |
|  - ConsumerRecord -> Frame  - GrpcMessage -> GrpcFrame            - Reactive item -> TaskFrame       |
|  - Heartbeat Thread         - StreamObserver & Backpressure       - Subscription.request(n)          |
|  - OffsetCollector          - responseQueue & onReady()           - FrameSequencer                   |
+-------|---------------------------|---------------------------------------|--------------------------+
        |                           |                                       |
        +---------------------------+---------------------------------------+
                                    | (addUpstream / LatticeSource)
                                    v
+------------------------------------------------------------------------------------------------------+
|                                          EUHEDRAL-CORE                                               |
|                                                                                                      |
|  [ControlPlaneLattice] (JVM Singleton)                                                               |
|   +-- Ingest Controller ([LatticeVertex] - Multiply-High Hash / Socket Locality)                     |
|   +-- Shard Handles ([LatticeEdge] -> Drain Flags & Upstream Demand)                                 |
|                                                                                                      |
|         | (Socket Route)                                                                             |
|         v                                                                                            |
|  [ControlPlaneShard] (1 per Physical Socket)                                                         |
|   +-- Core Distributor ([LatticeVertex] - Rotated Hash / Core Locality / L3 Cache)                   |
|   +-- Core Handles ([LatticeEdge])                                                                   |
|                                                                                                      |
|         | (Core Route)                                                                               |
|         v                                                                                            |
|  [ControlPlaneFragment] (1 per Physical Core, bound to Pinned Thread)                                |
|   +-- [ControlPlaneCache] (L1/L2 Partitioned MPSC Cache)                                             |
|   +-- [WorkRequester] (Evaluates [FragmentActionPicker] - 28 Weights / Policy Actions)               |
|   +-- Execution Loop:                                                                                |
|         1. Drain Local Cache -> [AbstractExecutor.execute()] -> [FrameManager.recycle()]             |
|         2. Pull Remote Cache (L3)                                                                    |
|         3. Pull Direct Upstream ([UpstreamQueue] -> [LatticeSource.pull()])                          |
+-----------------------------------^------------------------------------------------------------------+
                                    |
                                    | Periodic Utilization Snapshot (200ms) & Core Snapshots
+-----------------------------------|---------------------------------------------------------------------+
|                                   |     EUHEDRAL-HARDWARE-UTILS                                         |
|                                   |                                                                     |
|  [SystemInfo]  <--->  [TopologyMapper]  <--->  [ResourceMonitor]  --->  [PinnedThreadExecutor]          |
|  (CPU/Socket Topology) (Effective Cores)      (Pressure Sampling)    (Thread Pinning & NUMA First-Touch)|
+---------------------------------------------------------------------------------------------------------+
```

---

## 3. Data Flow Deep Dive

### 3.1 Frame Creation & Identity Lifecycle
- **Ingest Wrappers**: Inbound data items (Java objects, Kafka records, gRPC messages, Reactive events) are wrapped into specialized subclasses of [`AbstractFrame`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/frames/AbstractFrame.java#L40) using [`FrameFactory`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/impl/FrameFactory.java#L20).
- **Dual Hash Mechanics**:
  - `idHash`: Fixed 64-bit identifier produced via [`HasherApi`](file:///home/brandon/src/Euhedral-Execution/euhedral-hashing/src/main/java/io/euhedral_execution/hashing/HasherApi.java).
  - `routingHash`: Mutable 64-bit hash initialized to `idHash`.
  - **Ordered vs Unordered**: If `idHash == routingHash`, the frame is **ordered** and routed deterministically to a fixed lane. If parallel distribution is desired, `frame.randomizeHash(seed)` alters `routingHash` without modifying `idHash`.
- **Origin & Locality Policy**: [`RoutingPolicy`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/RoutingPolicy.java#L15) dictates routing preferences:
  - `ANYWHERE`: Default hash-based routing.
  - `SOCKET_LOCAL`: Binds execution to the frame's origin socket if active.
  - `CACHE_LOCAL`: Binds execution to the frame's origin physical core if active.

### 3.2 Two-Tier Routing Mathematics
1. **Global/Socket Level**: [`ControlPlaneLattice`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneLattice.java#L247) maps `routingHash` to an active socket index:
   Socket Index = unsignedMultiplyHigh(frame.getRoutingHash(), activeSocketCount)
2. **Shard/Core Level**: [`ControlPlaneShard`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneShard.java#L129) rotates the hash bits before mapping to avoid alias collisions:
   Rotated Hash = Long.rotateLeft(frame.getRoutingHash(), 31)
   Core Index = unsignedMultiplyHigh(Rotated Hash, activeCoreCount)

### 3.3 Core Execution & Recycling Loop
- **Partitioned L1/L2 Cache**: [`ControlPlaneCache`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneCache.java) buffers incoming frames into 8 partitioned MPSC queues sized to 70% of L1/L2 cache capacity.
- **Terminal Execution**: [`AbstractExecutor`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/generics/AbstractExecutor.java#L30) executes the frame lifecycle:
  1. `isAlive()` verification.
  2. `frame.execute()` processing.
  3. `doFinally()` cleanup (or `doFinallyWithError()` on uncaught failure).
- **Recycling Path**: Upon `doFinally()`, standard frames pass to [`FrameManager`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/impl/FrameManager.java#L25) for payload recycling via an MPSC queue, avoiding JVM GC allocations.

---

## 4. Signal Flow Deep Dive

### 4.1 Upstream Demand & Pull Propagation
Euhedral uses a hybrid **demand-request / direct-pull** model:
1. **Demand Generation**: [`WorkRequester`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/WorkRequester.java#L30) monitors fragment cache occupancy. When capacity is available, it emits demand up through worker-local [`UpstreamQueue`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/UpstreamQueue.java#L20).
2. **Upstream Request**: Demand flows through [`LatticeEdge.request(demand)`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeEdge.java#L45) to [`LatticeVertex`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeVertex.java#L100) and down to [`LatticeSource.request(demand)`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/generics/LatticeSource.java#L25).
3. **Direct Worker Pull**: During worker execution cycles, [`ControlPlaneFragment`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java#L200) invokes `pull()` directly on source handles to consume pre-buffered work without intermediary queue handoffs.

### 4.2 Action Picker Policy & Batch Modulation
Each core's [`ControlPlaneFragment`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java) evaluates [`FragmentActionPicker`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/config/FragmentActionPicker.java#L20) on every cycle.
- **Normalized Inputs (6 Measurements + 1 Bias)**:
  1. Completed work in current batch
  2. Current batch size
  3. Recent throughput
  4. Throughput variation
  5. Upstream work per worker
  6. Remote cache occupancy
  7. Bias constant (1.0)
- **Selected Action (28 Fixed Weights)**:
  - Action 0: Request Upstream Work
  - Action 1: Execute Remote Cached Work
  - Action 2: Execute Direct Upstream Work
  - Action 3: Park Thread (`LockSupport.parkNanos`)

### 4.3 Hardware Resource & Pressure Signal Loop
- **Sampling Tick**: [`ResourceMonitor`](file:///home/brandon/src/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/ResourceMonitor.java#L40) runs a background thread every 200ms sampling OS/JNI counters via [`SystemSnapshotProvider`](file:///home/brandon/src/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/common/SystemSnapshotProvider.java).
- **Pressure Calculation**: Emits `HardwareUtilization` containing CPU pressure, socket pressure, and process quota.
- **Dynamic Quota & Batch Tuning**: [`ControlPlaneLattice.update()`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneLattice.java#L264) receives utilization updates, recalculating per-shard and per-fragment batch limits. Under high CPU pressure, usable fragment cache capacity shrinks rapidly to prevent queuing congestion.

### 4.4 Topology Change, Drain, & Atomic Rebalance Signals
When physical CPU topology or process core availability changes:
1. **Topology Version Bump**: [`TopologyMapper`](file:///home/brandon/src/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/TopologyMapper.java) detects version discrepancy.
2. **Drain Signal**: `ingestController.setDrain(true)` sets the drain flag on [`LatticeVertex`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeVertex.java). Work ingest freezes.
3. **Atomic Remap**: New [`ControlPlaneShard`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneShard.java) and [`ControlPlaneFragment`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java) pipelines are constructed and initialized with NUMA `firstTouch()`.
4. **Decommission & Resume**: Retired shards/fragments drain remaining buffered frames, then close. The drain flag is cleared (`setDrain(false)`), resuming active ingest.

---

## 5. Subsystem Integrations: Reactor & Spring Transports

### 5.1 Reactor Integration (`euhedral-reactor-core`)
- **Bridge Architecture**:
  - [`EuhedralScheduler`](file:///home/brandon/src/Euhedral-Execution/euhedral-reactor-core/src/main/java/io/euhedral_execution/reactor/EuhedralScheduler.java#L29) adapts the lattice to Project Reactor's `Scheduler` interface.
  - [`EuhedralSubscriber`](file:///home/brandon/src/Euhedral-Execution/euhedral-reactor-core/src/main/java/io/euhedral_execution/reactor/common/EuhedralSubscriber.java#L20) implements Reactive Streams `Subscriber` and Euhedral `LatticeSource`.
- **Backpressure Translation**: When Euhedral per-core workers request work (`request(n)`), `EuhedralSubscriber` delegates demand directly to Reactive Streams `Subscription.request(n)`.
- **Frame Sequencing**: [`FrameSequencer`](file:///home/brandon/src/Euhedral-Execution/euhedral-reactor-core/src/main/java/io/euhedral_execution/reactor/common/FrameSequencer.java#L22) manages out-of-order execution across parallel core fragments while emitting results downstream in exact original sequence using a `PartitionedSpscQueue`.

### 5.2 Spring Kafka Transport (`euhedral-spring-core`)
- **Ingest Pipeline**: [`KafkaIngestSource`](file:///home/brandon/src/Euhedral-Execution/euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/transport/kafka/KafkaIngestSource.java#L41) wraps Kafka `ConsumerRecord` items into [`KafkaFrame`](file:///home/brandon/src/Euhedral-Execution/euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/frames/KafkaFrame.java#L15) objects, hashing topic/partition for partition-local core alignment.
- **Control Signals**:
  - **Liveness Heartbeat Thread**: Runs a dedicated thread checking `lastPollNs`. If poll delay exceeds 75% of heartbeat interval, it pauses consumer partitions, calls `poll(Duration.ZERO)` to keep Kafka consumer group membership alive, and resumes partitions.
  - **Asynchronous Offset Commit**: [`OffsetCollector`](file:///home/brandon/src/Euhedral-Execution/euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/transport/kafka/OffsetCollector.java#L20) tracks completed frame hashes per partition. Offsets are committed only after all frames up to that offset complete execution.
  - **Partition Rebalance**: [`RebalanceListener`](file:///home/brandon/src/Euhedral-Execution/euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/transport/kafka/RebalanceListener.java#L15) flushes pending offset collectors on partition revoke/assign events.

### 5.3 Spring gRPC Transport (`euhedral-spring-core`)
- **Streaming Pipeline**: [`EuhedralGrpcServerHandler`](file:///home/brandon/src/Euhedral-Execution/euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/transport/grpc/EuhedralGrpcServerHandler.java#L28) converts incoming gRPC messages into [`GrpcFrame`](file:///home/brandon/src/Euhedral-Execution/euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/frames/GrpcFrame.java#L15) instances.
- **Control Signals**:
  - **Inbound Demand**: `request(demand)` forwards engine demand directly to gRPC HTTP/2 stream observer via `ServerCallStreamObserver.request(request)`.
  - **Outbound Flow Control**: Responses offer to `responseQueue` (MpmcQueue). `onReady()` verifies `client.isReady()` before draining responses to the network, avoiding gRPC buffer overflow.
  - **Cancellation Signals**: `client.setOnCancelHandler()` triggers an atomic `KillSwitch`, causing in-flight frames to exit early via stackless `AbstractFrame.CancelSignal`.

---

## 6. Primary File & Class Reference Table

| Module | Primary Symbol / Class | Category | Role & Functionality |
| :--- | :--- | :--- | :--- |
| `euhedral-hardware-utils` | [`SystemInfo`](file:///home/brandon/src/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/SystemInfo.java) | Topology | Reads physical OS CPU, socket, L1/L2/L3 cache topology via JNI. |
| `euhedral-hardware-utils` | [`ResourceMonitor`](file:///home/brandon/src/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/ResourceMonitor.java) | Monitoring | 200ms periodic timer sampling CPU/system pressure snapshots. |
| `euhedral-hardware-utils` | [`PinnedThreadExecutor`](file:///home/brandon/src/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/PinnedThreadExecutor.java) | Execution | Binds core worker threads to specific physical CPUs. |
| `euhedral-core` | [`ControlPlaneLattice`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneLattice.java) | Control Plane | Top-level JVM singleton orchestrating socket shards and global ingest. |
| `euhedral-core` | [`ControlPlaneShard`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneShard.java) | Control Plane | Per-socket controller managing core distributors and fragment workers. |
| `euhedral-core` | [`ControlPlaneFragment`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java) | Execution | Per-core pinned worker executing local cache, remote pulls, and upstream pulls. |
| `euhedral-core` | [`WorkRequester`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/WorkRequester.java) | Signal Flow | Translates cache occupancy into upstream demand signals. |
| `euhedral-core` | [`FragmentActionPicker`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/config/FragmentActionPicker.java) | Policy | Evaluates 28 weights across 6 normalized inputs to select per-cycle actions. |
| `euhedral-core` | [`AbstractFrame`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/frames/AbstractFrame.java) | Data Flow | Base executable unit containing `idHash`, `routingHash`, and payload logic. |
| `euhedral-core` | [`FrameManager`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/impl/FrameManager.java) | Recycling | MPSC recycling queue managing zero-allocation frame reuse. |
| `euhedral-reactor-core` | [`EuhedralScheduler`](file:///home/brandon/src/Euhedral-Execution/euhedral-reactor-core/src/main/java/io/euhedral_execution/reactor/EuhedralScheduler.java) | Reactor | Adapts Euhedral execution graph to Project Reactor `Scheduler`. |
| `euhedral-reactor-core` | [`FrameSequencer`](file:///home/brandon/src/Euhedral-Execution/euhedral-reactor-core/src/main/java/io/euhedral_execution/reactor/common/FrameSequencer.java) | Sequencing | Restores in-order results for parallelized reactor operator streams. |
| `euhedral-spring-core` | [`KafkaIngestSource`](file:///home/brandon/src/Euhedral-Execution/euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/transport/kafka/KafkaIngestSource.java) | Ingest | Maps Kafka records to frames; manages liveness heartbeats and offset commits. |
| `euhedral-spring-core` | [`EuhedralGrpcServerHandler`](file:///home/brandon/src/Euhedral-Execution/euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/transport/grpc/EuhedralGrpcServerHandler.java) | Transport | Bridges gRPC stream observers to Euhedral demand and response queues. |
