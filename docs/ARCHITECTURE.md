# How Euhedral Works

Euhedral is easiest to understand as a pull-driven execution graph, not as a thread pool.
Long-lived workers are pinned to CPUs. Those workers ask for work, route it through a graph shaped
around sockets and cores, and execute small reusable objects called frames.

The shortest useful view of the runtime is:

```text
LatticeSource -> ControlPlaneLattice -> ControlPlaneShard
              -> ControlPlaneFragment -> AbstractExecutor -> AbstractFrame.execute()
```

Frames move from left to right. Demand moves from right to left. The cache and routing classes sit
between those named stages and keep most coordination local to the worker that needs the data.

## Repository map

| Module | What it owns |
| --- | --- |
| [`euhedral-core`](../euhedral-core/) | The control plane, routing graph, frames, ingest sources, execution boundary, and metrics |
| [`euhedral-data-structures`](../euhedral-data-structures/) | Concurrent queue families, partitioned queues, padded atomics, and adders |
| [`euhedral-hardware-utils`](../euhedral-hardware-utils/) | CPU topology, resource snapshots, affinity, pinned executors, and JNI loading |
| [`euhedral-hashing`](../euhedral-hashing/) | xxHash64-based hashing used for identity and routing |
| [`euhedral-reactor-core`](../euhedral-reactor-core/) | Reactor scheduler, operators, subscribers, and frame sequencing |
| [`euhedral-spring-core`](../euhedral-spring-core/) | Spring Boot auto-configuration plus Kafka and gRPC transports |
| [`euhedral-training`](../euhedral-training/) | Offline policy training, candidate generation, and closed-loop tuning |
| [`benchmarks`](../benchmarks/) | JMH benchmarks for the engine and queue implementations |

The Java module descriptors are a good quick check of the public package boundaries. Start with
[`euhedral-core/module-info.java`](../euhedral-core/src/main/java/module-info.java) and follow the
same file in the other library modules.

## Starting the runtime

[`ControlPlaneLattice`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneLattice.java)
is the top-level runtime object. It is a JVM-wide singleton created through `getOrCreate()`. Calling
`addUpstream()` starts it lazily, although applications can call `start()` themselves.

At startup the lattice:

1. Reads the machine layout through
   [`SystemInfo`](../euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/SystemInfo.java).
2. Intersects the configured CPU set with the CPUs currently available to the process through
   [`TopologyMapper`](../euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/TopologyMapper.java).
3. Creates one
   [`ControlPlaneShard`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneShard.java)
   for each known socket.
4. Starts the active shards and their per-core worker pipelines.
5. Starts a
   [`ResourceMonitor`](../euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/ResourceMonitor.java)
   that samples the system every 200 ms and feeds topology and pressure updates back into the
   control plane.

A normal entry point is deliberately small:

```java
ControlPlaneLattice lattice = ControlPlaneLattice.getOrCreate();
lattice.start();

FunctionIngestSink<Integer, Integer> squares =
        new FunctionIngestSink<>(value -> value * value, result -> handle(result), true);

lattice.addUpstream(squares);
squares.push(List.of(2, 4, 8));
squares.completeGracefully();
```

The last constructor argument enables per-item hash randomization in the built-in sink so independent
work can spread across workers. Execution is asynchronous, so application shutdown still needs its
own completion coordination.

For a restricted CPU set or a custom execution stage, construct a
[`LatticeConfig`](../euhedral-core/src/main/java/io/euhedral_execution/core/config/LatticeConfig.java)
and pass it to `getOrCreate(config)`.

## How work enters and moves

An input implements
[`LatticeSource`](../euhedral-core/src/main/java/io/euhedral_execution/core/generics/LatticeSource.java).
A source supports two ways of satisfying demand:

- `request(long)` lets the source push frames downstream.
- `pull(consumer, stopCondition, demand)` lets a worker consume already available frames directly.

The built-in sources live under
[`core/ingest`](../euhedral-core/src/main/java/io/euhedral_execution/core/ingest/).
`QueueIngestSink` is useful for a live producer, `ArrayIngestSink` wraps a finite set of frames, and
`FunctionIngestSink` and `ConsumerIngestSink` add frame creation and recycling around common Java
functions.

When the lattice ingests a source,
[`LatticeVertex`](../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeVertex.java)
wraps it in an `UpstreamInterceptor`. Worker-local
[`UpstreamQueue`](../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/UpstreamQueue.java)
instances aggregate those source handles and share demand between them. A handle is acquired by one
worker at a time, so one source is not pulled concurrently by several workers.

[`LatticeEdge`](../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeEdge.java)
provides the chain itself. It sends frames down to a receiver and sends demand up to a source.
`LatticeVertex` adds fan-out, routing tables, and optional shared remote caches. This split matters:
edges describe connectivity, while vertices decide which branch receives a frame.

## Routing, ordering, and locality

Every
[`AbstractFrame`](../euhedral-core/src/main/java/io/euhedral_execution/core/frames/AbstractFrame.java)
has two hashes:

- `idHash` is fixed for the life of the frame.
- `routingHash` starts as `idHash` and may be changed before ingestion.

The default vertex route is:

```java
int logicalIndex =
        (int) unsignedMultiplyHigh(frame.getRoutingHash(), activeDownstreamCount);
```

Unsigned multiply-high maps the full 64-bit hash range onto the active downstream count without a
modulo operation. The lattice uses it to select a socket. The shard rotates the same hash before
selecting a core, which avoids reusing exactly the same high bits at both levels.

A frame is considered ordered when `idHash == routingHash`. Ordered frames take the direct route
through shared routing vertices instead of entering their remote caches. Frames from the same source
with the same routing hash therefore keep the same lane. This is not a global ordering guarantee
across independent sources.

To allow parallel placement, change the routing hash before the frame is visible to the lattice:

```java
long idHash = HasherApi.mix(customerId);
long seed = HasherApi.mix(batchId);

FunctionFrame<Input, Output> frame =
        new FunctionFrame<>(idHash, this::process, this::accept, input);
frame.randomizeHash(seed++);
```

Do not change either routing metadata or payload while a frame is in flight.

[`RoutingPolicy`](../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/RoutingPolicy.java)
adds an origin-local preference:

| Policy | Placement behavior |
| --- | --- |
| `ANYWHERE` | Use normal hash routing |
| `SOCKET_LOCAL` | Stay on the frame origin's socket when that socket is active |
| `CACHE_LOCAL` | Stay on the frame origin's core when that core is active |

If the origin is missing or no longer active, routing falls back to the hash. `FrameFactory` records
an origin for managed frames. Code that constructs frames manually must set an origin itself if it
expects a locality policy to take effect.

## From a socket to a core

A shard owns one socket. Its core distributor is another `LatticeVertex`, sized from part of the
socket's L3 capacity. It routes frames to the active physical cores and may hold unordered work in
per-destination remote caches.

For each active core, the shard clones a
[`CloneableObject`](../euhedral-core/src/main/java/io/euhedral_execution/core/generics/CloneableObject.java)
with a `CloneConfig` containing the physical core ID and its usable logical CPUs. The default clone
is
[`BaseCloneableObject`](../euhedral-core/src/main/java/io/euhedral_execution/core/impl/BaseCloneableObject.java).
It joins two pieces:

```text
ControlPlaneFragment -> AbstractExecutor
```

`BaseCloneableObject` creates its clone on a temporary pinned executor and calls `firstTouch()`
before returning it to the shard. The shard then starts it, connects it, and updates it with that
core's latest hardware snapshot. `firstTouch()` exists so queue pages and other state can be
allocated on the NUMA node that will use them.

`AbstractExecutor` is intentionally thin. The default implementation calls `frame.execute()`.
Applications can supply another executor through `LatticeConfig` when execution needs a custom
terminal stage.

## The per-core control loop

[`ControlPlaneFragment`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java)
is the active worker. It runs on a
[`PinnedThreadExecutor`](../euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/PinnedThreadExecutor.java)
bound to one logical CPU from its physical core.

The class hierarchy describes its data path:

```text
ControlPlaneFragment
  extends WorkRequester
  extends ControlPlaneCache
  extends LatticeVertex
```

[`ControlPlaneCache`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneCache.java)
owns a partitioned MPSC cache local to the fragment. Its default budget is 70 percent of the
available L1 and L2 capacity, split into eight partitions. The usable capacity shrinks quickly under
CPU pressure and recovers more slowly when pressure falls.

[`WorkRequester`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/WorkRequester.java)
turns cache occupancy into upstream demand. The fragment then chooses work in this order:

1. Execute from its local cache.
2. Optionally pull from a shared remote cache.
3. Optionally pull directly from an upstream source.

Those optional choices, along with requesting more work and briefly parking the thread, are selected
by
[`FragmentActionPicker`](../euhedral-core/src/main/java/io/euhedral_execution/core/config/FragmentActionPicker.java).
It is a small linear policy, not a neural network in the runtime. Four actions each have six input
weights and one bias, for 28 weights in total:

The policy terms are completed work in the current batch, current batch size, recent throughput,
throughput variation, upstream work per registered worker, remote-cache occupancy, and a bias. Each
set of seven weights decides one action: request upstream work, execute remote cached work, execute
directly from an upstream, or sleep briefly.

The fragment normalizes the six measured inputs before evaluating the actions. It also adjusts batch
size from observed throughput and caps that batch using CPU pressure, the configured maximum, and
the local frame quota. The default maximum is 4,096 frames.

This division keeps the learned or hand-tuned policy inside a narrow control surface. Routing,
queue ownership, lifecycle, pressure limits, and error handling remain ordinary runtime code.

## Frame completion, cancellation, and reuse

[`AbstractExecutor`](../euhedral-core/src/main/java/io/euhedral_execution/core/generics/AbstractExecutor.java)
owns the final frame lifecycle:

1. Check `isAlive()`.
2. Call `execute()`.
3. Call `doFinally()` after success or structured cancellation.
4. Call `doFinallyWithError(Throwable)` after an uncaught execution error.

`throwCancelSignal()` throws a stackless internal exception that the executor treats as
cancellation, not as a failure. `kill()` normally flips a shared kill switch so work that has not
started can fail its liveness check.

[`FrameManager`](../euhedral-core/src/main/java/io/euhedral_execution/core/impl/FrameManager.java)
recycles frames through an MPSC queue. Its companion
[`FrameFactory`](../euhedral-core/src/main/java/io/euhedral_execution/core/impl/FrameFactory.java)
either creates a frame or replaces the payload of a recycled one. Replacement starts by restoring
`routingHash` to `idHash`, so a parallel frame factory must make the frame unordered again during
replacement.

Most frames recycle from `doFinally()`. `CallbackFrame` is intentionally different: its response
owner must decide when it is safe to recycle the frame.

## Topology changes and draining

The control plane can change its graph while the process is running, but it does not remap live
traffic in place.

When the effective CPU or socket set changes:

1. The lattice or shard enters drain mode.
2. New routing handles and clones are prepared.
3. The routing table is published as one new state.
4. Removed workers are allowed to drain, then closed.
5. Ingest resumes after the transition.

The lattice handles socket changes. Each shard handles core changes inside its socket.

`resetForNextTrial()` is a stronger benchmark operation. It freezes ingest and clears routing and
fragment caches so the next policy trial does not inherit buffered work from the previous one. It
is not part of normal application flow.

## Supporting modules

### Concurrent data structures

The engine does not use one general-purpose queue everywhere. The
[`queues`](../euhedral-data-structures/src/main/java/io/euhedral_execution/data_structures/queues/)
package has SPSC, SPMC, MPSC, and MPMC variants, with bounded, chunked, and partitioned forms. The
consumer APIs favor batch drains because the control loop normally handles more than one frame at a
time.

The
[`atomics`](../euhedral-data-structures/src/main/java/io/euhedral_execution/data_structures/atomics/)
package provides padded scalar and array types for hot counters. Their `getOpaque`, `getAcquire`,
`setRelease`, and volatile methods expose intentional Java Memory Model choices used throughout the
engine.

### Hardware and native code

The hardware module keeps OS details out of the control plane:

- `SystemInfo` describes sockets, cores, logical CPUs, and cache sharing.
- `ThreadTools` exposes affinity and timer operations.
- `PinnedThreadExecutor` owns affinity-aware worker threads.
- `ResourceMonitor` turns OS snapshots into system, socket, core, and CPU pressure records.
- `JNIClassLoader` extracts and loads the native library packaged for the current platform.

[`build.zig`](../euhedral-hardware-utils/src/main/resources/build.zig) builds Linux glibc and musl,
Windows, and macOS libraries for x64 and arm64. Maven runs that build during the hardware module's
`initialize` phase. The exact tool versions live in [`mise.toml`](../mise.toml).

### Hashing

[`HasherApi`](../euhedral-hashing/src/main/java/io/euhedral_execution/hashing/HasherApi.java)
provides deterministic hashes for bytes, strings, and policy vectors, plus mixing and hash
combination helpers. Routing depends on well-distributed 64-bit values, so use this API instead of
ad hoc low-bit masks.

### Reactor and Spring

[`EuhedralScheduler`](../euhedral-reactor-core/src/main/java/io/euhedral_execution/reactor/EuhedralScheduler.java)
adapts the lattice to Reactor's `Scheduler` API.
[`EuhedralOperator`](../euhedral-reactor-core/src/main/java/io/euhedral_execution/reactor/EuhedralOperator.java)
turns Reactor values into recyclable callback or sequenced frames for parallel, sequential, and
concatenated mapping.

[`EuhedralConfiguration`](../euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/configuration/EuhedralConfiguration.java)
auto-configures the lattice, scheduler, and operator in Spring Boot. The same module contains
demand-aware gRPC handlers and a Kafka source that maps records to frames, preserves partition
liveness, and commits offsets after frame completion.

### Offline training and benchmarks

The production fragment evaluates a fixed 28-weight policy. Model training stays in
[`euhedral-training`](../euhedral-training/). Its closed loop normalizes benchmark data, trains an
ordinal policy ranker, generates candidates, benchmarks them, and promotes only completed runs back
into the corpus. See the
[`training README`](../euhedral-training/README.md) for commands and properties, and
[`ML_CLOSED_LOOP_ARCHITECTURE.md`](ML_CLOSED_LOOP_ARCHITECTURE.md) for the slower feedback loop’s
design.

The separate [`benchmarks`](../benchmarks/) module packages JMH tests for end-to-end latency,
throughput, irregular compute workloads, and queue comparisons. Benchmark code is evidence and
diagnostic tooling; it is not on the production runtime path.

## Invariants worth keeping

- There is one `ControlPlaneLattice` singleton per JVM.
- Workers pull work. Do not introduce a central dispatcher into the hot path.
- Publish a new routing map only while its vertex is draining.
- Treat frame payload, origin, and routing metadata as immutable after ingestion.
- Ordered work is ordered within a source and lane, not across unrelated sources.
- Perform owner-thread queue resets on the owner thread.
- Keep allocation, blocking I/O, and verbose logging out of per-frame loops.
- Preserve the chosen VarHandle memory semantics unless the synchronization argument changes too.
- Close sources and the lattice explicitly so worker threads, native state, and metrics are released.

For concrete behavior, the core tests under
[`euhedral-core/src/test`](../euhedral-core/src/test/) are a better companion to this document than a
class diagram. They show routing, draining, source demand, cloning, and rebalance boundaries in
executable form.
