# How Euhedral Works

Euhedral is a pull-driven execution engine designed to get the most out of modern multi-core,
multi-socket hardware. Instead of pushing tasks into a bloated central queue, Euhedral relies on
persistent, CPU-pinned workers that actively pull work downstream through an execution graph
mirrored after your physical sockets and cores. Units of work travel through this graph as
lightweight, recyclable objects called **frames**.

Here is the high-level path work travels:

```text
LatticeSource -> ControlPlaneLattice -> ControlPlaneShard
              -> ControlPlaneFragment -> AbstractExecutor -> AbstractFrame.execute()
```

Frames flow downstream from left to right, while demand signals travel upstream from right to left.
Between these stages, local caches and intelligent routing vertices keep coordination pinned close
to the worker executing the work to minimize cross-socket chatter and cache invalidation.

## Repository Map

| Module                                                                     | What it owns                                                                                                      |
|----------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| [`euhedral-core`](../euhedral-core/)                                       | The control plane, routing graph, frame definitions, ingest sources, execution boundaries, and metrics            |
| [`euhedral-data-structures`](../euhedral-data-structures/)                 | High-performance concurrent queues (SPSC, SPMC, MPSC, MPMC), partitioned variants, padded atomics, and adders     |
| [`euhedral-hardware-utils`](../euhedral-hardware-utils/)                   | Topology discovery, hardware pressure monitors, thread affinity, pinned executors, and JNI cross-platform loaders |
| [`euhedral-hashing`](../euhedral-hashing/)                                 | xxHash64-based hashing utilities tailored for frame identity and high-dispersion routing                          |
| [`euhedral-reactor-core`](../euhedral-reactor-core/)                       | Project Reactor integration: custom `Scheduler`, reactive operators, subscribers, and frame sequencers            |
| [`euhedral-spring-core`](../euhedral-spring-core/)                         | Spring Boot auto-configuration, plus demand-aware Kafka and gRPC transport bindings                               |
| [`benchmarks`](../benchmarks/)                                             | JMH microbenchmarks and offline calibration suites                                                                |
| [`python/pareto-weight-calibration`](../python/pareto-weight-calibration/) | Offline policy fitting, evaluation scripts, and parameter exports                                                 |

For a quick look at public package boundaries, inspect the Java module descriptors, starting with [
`euhedral-core/module-info.java`](../euhedral-core/src/main/java/module-info.java) and following
into the other library modules (Spring currently leaves its descriptor disabled). Architectural
dependencies flow cleanly: utility libraries feed into core, Reactor builds on core, and Spring ties
both together. Benchmark harnesses and offline fitting scripts stay strictly outside the production
runtime path.

## Starting the Runtime

The central orchestrator for the runtime is [
`ControlPlaneLattice`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneLattice.java).
It is a JVM-wide singleton accessed via `ControlPlaneLattice.getOrCreate()`. While you can call
`start()` explicitly, calling `addUpstream()` starts the lattice lazily on demand. Calling `close()`
shuts down the runtime and frees the singleton instance, allowing subsequent calls to create a clean
slate.

When the lattice spins up, it executes a clear bootstrap sequence:

1. **Inspects the hardware topology** using [
   `SystemInfo`](../euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/SystemInfo.java)
   to discover NUMA sockets, physical cores, logical siblings, and cache tiers.
2. **Filters and maps usable CPUs** with [
   `TopologyMapper`](../euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/TopologyMapper.java).
   Whenever more than one physical core is present, it reserves physical core 0 for OS and system
   background threads by omitting all of its logical siblings from the worker pool.
3. **Instantiates a [
   `ControlPlaneShard`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneShard.java)**
   for each detected NUMA socket.
4. **Initializes active shards** and boots up their per-core worker pipelines.
5. **Launches the [
   `ResourceMonitor`](../euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/ResourceMonitor.java)**,
   which by default samples fast system metrics every 200 ms to continuously stream hardware
   pressure and topology changes into the control plane. Slower metrics run on longer sampling
   cadences.

Getting a basic pipeline up and running requires only a few lines:

```java
PipelineFrame.Builder<Integer, Integer> builder =
        PipelineFrame.<Integer>builder().fanOut(value -> value * value);
CountDownLatch completed = new CountDownLatch(3);
AtomicInteger total = new AtomicInteger();
PipelineRunner<Integer> runner = new PipelineRunner<>(builder, result -> {
    total.addAndGet(result);
    completed.countDown();
}, true);

ControlPlaneLattice lattice = ControlPlaneLattice.getOrCreate();
try{
        lattice.

addUpstream(runner);
    List.

of(2,4,8).

forEach(runner::run);
    if(!completed.

await(10,TimeUnit.SECONDS)){
        throw new

IllegalStateException("Pipeline did not complete in time");
    }
            System.out.

println(total.get());
        }finally{
        runner.

complete();
    lattice.

close();
}
```

In this snippet, setting the trailing boolean to `true` allows the terminal consumer to execute
concurrently across workers, while intermediate stages declare parallelism via `fanOut` or
sequential processing via `fanIn`. Each stage transformation and terminal callback runs inside its
own frame, feeding intermediate results directly back into downstream sinks. When shutting down,
ensure downstream work has actually finished before closing `PipelineRunner`: calling
`completeGracefully()` only inspects whether the *input* queue has drained, which can happen while
frames are still executing mid-flight.

If you need custom core allocations or specialized executor pipelines, configure a [
`LatticeConfig`](../euhedral-core/src/main/java/io/euhedral_execution/core/config/LatticeConfig.java)
and pass it to `ControlPlaneLattice.getOrCreate(config)`.

## How Work Enters and Moves

Every data ingress source implements [
`LatticeSource`](../euhedral-core/src/main/java/io/euhedral_execution/core/generics/LatticeSource.java).
Sources satisfy downstream demand in two distinct ways:

- **Reactive push (`request(long)`)**: The downstream vertex requests a number of frames, and the
  source pushes them into the graph.
- **Direct pull (`pull(consumer, stopCondition, demand)`)**: A worker thread directly drains
  ready-to-execute frames straight from the source.

Euhedral provides standard ingress implementations in [
`core/ingest`](../euhedral-core/src/main/java/io/euhedral_execution/core/ingest/): `QueueIngestSink`
connects live producer queues, `ArrayIngestSink` batches static or fixed arrays, and
`PipelineRunner` handles automated lifecycle and frame recycling across chained stages.

When you register a source with the lattice, [
`LatticeVertex`](../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeVertex.java)
wraps it in an `UpstreamInterceptor`. Downstream workers maintain thread-local [
`UpstreamQueue`](../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/UpstreamQueue.java)
instances that bundle these source handles and share demand evenly. The interceptor guarantees that
`request` and `pull` operations on a registered source are properly serialized across threads;
direct method calls to a source bypassing this registration will skip that synchronization.

At the wiring level:

- [
  `LatticeEdge`](../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeEdge.java)
  represents point-to-point connections. It ferries frames downstream and propels demand upstream.
- [
  `LatticeVertex`](../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeVertex.java)
  sits at junction points, handling fan-out routing tables, downstream balancing, and optional
  remote caches.

Edges describe the plumbing; vertices make the routing decisions.

## Routing, Ordering, and Locality

Every [
`AbstractFrame`](../euhedral-core/src/main/java/io/euhedral_execution/core/frames/AbstractFrame.java)
carries two 64-bit hashes:

- `idHash`: The immutable identity hash of the frame, assigned at birth.
- `routingHash`: Starts identical to `idHash`, but can be explicitly randomized or steered prior to
  ingestion.

When distributing frames to downstreams, vertices use an efficient multiply-high mapping:

```java
int logicalIndex = (int) unsignedMultiplyHigh(frame.getRoutingHash(), activeDownstreamCount);
```

Using unsigned multiply-high maps the entire 64-bit integer space onto active lanes smoothly without
incurring the penalty of integer division or modulo operations. The lattice uses this formula to
pick a target socket; the shard then bit-rotates the hash before selecting a core, ensuring high
bits are not repetitively reused across hierarchical routing layers.

### Frame Ordering Semantics

A frame is **ordered** whenever `idHash == routingHash`.

Ordered frames bypass shared remote caches and take direct routing lanes through vertices. This
guarantees that frames originating from the same source with identical routing hashes will always
travel the same path in strict FIFO order under a steady routing topology. When a worker is directly
pulling from an upstream handle, it stops immediately upon encountering an ordered frame, ensuring
it is dispatched through the normal vertex routing pipeline. Keep in mind that ordering is strictly
lane- and source-scoped: independent sources or parallel stages converging into an ordered sink are
not globally ordered across the whole engine.

If you want frames to distribute across cores in parallel, simply randomize the routing hash before
pushing the frame to the lattice:

```java
long idHash = HasherApi.mix(customerId);
long seed = HasherApi.mix(batchId);

RunnableFrame frame = new RunnableFrame(idHash, () -> process(input));
frame.randomizeHash(seed++);
```

> Note: Never alter a frame's routing metadata or payload while it is actively in flight.

### Locality Policies

[
`RoutingPolicy`](../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/RoutingPolicy.java)
allows you to guide execution toward the hardware where the data was produced:

| Policy         | Placement Preference                                                                 |
|----------------|--------------------------------------------------------------------------------------|
| `ANYWHERE`     | Normal hash-based distribution across all available workers                          |
| `SOCKET_LOCAL` | Confine execution to the frame origin's socket as long as that socket remains active |
| `CACHE_LOCAL`  | Confine execution to the frame origin's core as long as that core remains active     |

If an origin core or socket is missing or marked inactive, the engine falls back to hash routing.
Locality callbacks translate the physical origin ID into an active routing index using the vertex's
reverse map. Forward and reverse mappings are published together while drained, so sparse physical
IDs select the correct downstream and remaps remove inactive origins from locality selection.

Frames built via `FrameFactory` automatically capture their origin at factory creation time. On
operating systems without direct per-thread CPU query APIs, this falls back to the worker thread's
assigned CPU mapping. If you instantiate frames by hand, you must assign the origin manually if you
intend to use locality-aware routing.

## From Socket to Core

Each `ControlPlaneShard` is responsible for exactly one NUMA socket. Its core distributor is an
internal `LatticeVertex` scaled against a portion of the socket's shared L3 cache capacity. It
routes frames to active physical cores and holds unordered work in dedicated per-destination remote
caches.

For each active physical core, the shard instantiates a [
`CloneableObject`](../euhedral-core/src/main/java/io/euhedral_execution/core/generics/CloneableObject.java)
configured with a `CloneConfig` that pairs the physical core ID with its logical sibling CPUs. By
default, it creates a [
`BaseCloneableObject`](../euhedral-core/src/main/java/io/euhedral_execution/core/impl/BaseCloneableObject.java),
which ties together two essential components:

```text
ControlPlaneFragment -> AbstractExecutor
```

During construction, `BaseCloneableObject` runs `firstTouch()` on an executor associated with the
target core. This initial access encourages NUMA page allocators to locate queues, buffers, and
working state in memory banks closest to the target core. The shard then connects the clone and
streams hardware telemetry into it.

`AbstractExecutor` acts as the execution harness. The default executor simply invokes
`frame.execute()`, but you can plug in custom executors via `LatticeConfig` if your pipeline
requires special tracing, sandboxing, or lifecycle interception.

## The Per-Core Control Loop

The real muscle of Euhedral is the [
`ControlPlaneFragment`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java).
Each fragment runs on a dedicated [
`PinnedThreadExecutor`](../euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/PinnedThreadExecutor.java)
pinned to a logical CPU belonging to its core (subject to the OS platform's affinity support).

Its inheritance structure shows how it layers responsibilities:

```text
ControlPlaneFragment
  extends WorkRequester
  extends ControlPlaneCache
  extends LatticeVertex
```

- **[
  `ControlPlaneCache`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneCache.java)**:
  Manages an elastic, partitioned MPSC ring cache local to the fragment thread. By default, its
  partitions are sized to occupy up to 70% of the core's combined L1 and L2 cache, divided across
  eight partitions. A dynamic capacity factor tracks system CPU pressure, throttling back demand
  targets during bursts and gradually easing limits as pressure subsides to maintain healthy cache
  residency without hard allocation limits.
- **[
  `WorkRequester`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/WorkRequester.java)**:
  Translates unused cache headroom into upstream work requests. On each iteration of its loop, the
  fragment first sweeps its local cache, selects an execution path, and siphons ready work from
  remote vertex caches if batch headroom remains.
- **[
  `FragmentDecisionTree`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/FragmentDecisionTree.java)**:
  Operates on the fragment's owner thread to select how the worker interacts with upstream sources:

| Path     | Loop Behavior                                                                                                                       |
|----------|-------------------------------------------------------------------------------------------------------------------------------------|
| `DIRECT` | Directly pulls frames from upstream source handles. If no progress was made, issues requests and pulls work into the routing graph. |
| `STAGED` | Issues upstream demand requests into the vertex graph, then returns to draining local and remote caches.                            |
| `CACHE`  | Focuses purely on chewing through cached frames without contending for upstream handles; parks only if no progress was made.        |

The decision tree balances several live inputs: sampled execution runtimes, upstream handle lock
contention, productive handle counts, active worker tallies, and the worker's own rank index. Under
high concurrency, an offline-trained logistic participation model steps in, gracefully shifting
excess workers into the `CACHE` path to avoid destructive lock thrashing on upstream sources. The
primary worker (rank 1) is never forced into CACHE withdrawal.

Freshly started workers default to `DIRECT` until baseline execution samples are gathered. Once
warmed up, switching to `DIRECT` requires low contention (at or below 85%) and execution runtimes
within the configured threshold.

Batch sizes are dynamically budgeted using smoothed execution service times, allowing batch
processing to scale up or down smoothly within bounded limits (defaulting to a maximum of 4,096
frames per batch) while respecting CPU pressure caps.

When no work is found, workers use targeted parking strategies tailored to whether the stall was an
upstream dry-spell or a cache miss. [
`IdlePolicy`](../euhedral-core/src/main/java/io/euhedral_execution/core/config/CacheTimingConfig.java)
defines park backoffs and contention-history decay rates, adapting automatically to real-time
measurements or falling back to fixed timing. Crucially, `CACHE` workers always double-check local
queues for fresh work before entering park states.

The hot worker loop remains identical whether running in production or under JMH benchmarks;
benchmark modes simply attach non-intrusive observation taps.

## Frame Lifecycle, Cancellation, and Recycling

Execution boundaries are maintained by [
`AbstractExecutor`](../euhedral-core/src/main/java/io/euhedral_execution/core/generics/AbstractExecutor.java)
through a predictable lifecycle:

1. Validate `isAlive()`.
2. Run `execute()`.
3. Invoke `doFinally()` on clean completion or structured cancellation.
4. Invoke `doFinallyWithError(Throwable)` if execution throws an unhandled `Exception`.

Cancellation uses `throwCancelSignal()`, which throws a lightweight, stackless sentinel exception
that executors treat strictly as a cancellation signal rather than an unexpected failure. To prevent
pending work from starting, `kill()` trips an optional shared kill switch; frames awaiting execution
fail their upcoming liveness check and exit early without interrupting currently executing tasks.
Because executors specifically trap `Exception`, JVM fatal `Error` instances are left unhindered.

Object allocation churn is kept near zero using an MPSC recycling pool managed by [
`FrameManager`](../euhedral-core/src/main/java/io/euhedral_execution/core/impl/FrameManager.java)
and [
`FrameFactory`](../euhedral-core/src/main/java/io/euhedral_execution/core/impl/FrameFactory.java):

- When recycling an existing frame, the factory checks whether the frame was originally marked
  parallel, resets `routingHash` back to `idHash`, invokes the caller's payload replacement
  callback, and re-randomizes the hash if parallel execution is requested.
- Fresh factory seeds and captured hardware origins are re-applied transparently.
- Factories are strictly thread-confined to single owners, while recycled frames can be returned
  concurrently across workers through the bounded MPSC ring.

Most frame classes automatically hand themselves back to the recycler inside `doFinally()`. In
pipeline chains, the root frame is recycled as soon as its terminal stage completes, errors, or
cancels. `CallbackFrame` is an intentional exception: its `doFinally()` is a no-op so the
asynchronous response receiver can dictate when it is safe to release the underlying memory (though
uncaught execution errors still recycle immediately).

## Dynamic Topology and Draining

Euhedral accommodates CPU hot-plugging, re-allocation, and dynamic core scaling at runtime without
interrupting un-migrated traffic.

When active cores or sockets change:

1. The lattice or shard enters a coordinated **drain mode**.
2. Routing handles and the new mapping are prepared and published atomically while ingress is
   drained.
3. Fresh worker clones are initialized and attached.
4. Existing workers finish their buffered queues. Deprecated workers are shut down, while retained
   workers that stall past the drain deadline are safely replaced.
5. Ingress resumes across the new topology.

Socket-level topology updates are handled by the lattice; core-level rebalancing is handled
internally by each shard.

For benchmarking and test isolation, `ControlPlaneLattice.clear(Duration)` provides an explicit
pipeline flush (with an optional `Runnable` hook executed during the pause). Producers must pause
prior to calling it. The method halts ingress, drains distributor caches, verifies fragment thread
acknowledgments, runs the hook, and cleanly re-enables traffic. This deliberately discards buffered
work between trials and is not part of normal application shutdown.

## Supporting Modules

### Concurrent Data Structures

Rather than relying on one-size-fits-all queues, [
`euhedral-data-structures`](../euhedral-data-structures/src/main/java/io/euhedral_execution/data_structures/queues/)
supplies specialized implementations for specific producer/consumer pairings: SPSC, SPMC, MPSC, and
MPMC. These come in bounded, chunked, and partitioned layouts, prioritizing high-throughput batch
drains (`drain` and `fill`) over single-item polling.

The [
`atomics`](../euhedral-data-structures/src/main/java/io/euhedral_execution/data_structures/atomics/)
package includes cache-line padded variants of standard primitives and counters to prevent false
sharing on contended counters, exposing precise memory access modes (`getOpaque`, `getAcquire`,
`setRelease`, and volatile CAS) aligned with the Java Memory Model.

### Hardware and Native Tooling

The [`euhedral-hardware-utils`](../euhedral-hardware-utils/) module shields the core engine from
OS-specific quirks:

- `SystemInfo` exposes parsed socket, core, logical CPU, and cache topology.
- `ThreadTools` provides thread affinity, managed CPU identity, and high-resolution timing
  utilities.
- `PinnedThreadExecutor` manages thread execution pinned to specific hardware units.
- `ResourceMonitor` aggregates OS statistics into normalized system, socket, and core pressure
  readings.
- `JNIClassLoader` unpacks and links the appropriate native platform library dynamically.

Affinity capabilities vary by environment: Linux and Windows support hard hardware CPU masks, macOS
offers scheduler locality hints, and container environments fall back gracefully. Telemetry
collection is cleanly segregated: raw OS hooks feed into `SampleStateEngine` for delta analysis,
which `PressureEvaluator` turns into normalized pressure metrics. Listener notifications are
coalesced, ensuring slow observers can never build up an unbounded notification backlog.

Native libraries are compiled using Zig across Linux (glibc/musl), Windows, and macOS on x86_64 and
arm64 architectures, driven by [`build.zig`](../euhedral-hardware-utils/src/main/native/build.zig)
and cataloged in [
`native-products.json`](../euhedral-hardware-utils/src/main/native/native-products.json). Gradle
generates JNI headers during compilation, executes `zigBuild`, and packages the resulting libraries
and catalog from `build/generated-resources/native`.

### Deterministic Hashing

[`HasherApi`](../euhedral-hashing/src/main/java/io/euhedral_execution/hashing/HasherApi.java) wraps
xxHash64 to deliver rapid, high-dispersion 64-bit hashes for byte buffers, strings, and parameter
vectors. Because downstream routing relies heavily on even dispersion across bit ranges, this API
replaces crude modulus operations or standard Java hash codes.

### Reactive Extensions & Framework Integrations

- **Reactor**: [
  `EuhedralScheduler`](../euhedral-reactor-core/src/main/java/io/euhedral_execution/reactor/EuhedralScheduler.java)
  bridges the engine with Project Reactor schedulers. [
  `EuhedralOperator`](../euhedral-reactor-core/src/main/java/io/euhedral_execution/reactor/EuhedralOperator.java)
  converts reactive streams into recyclable frames to power concurrent processing via `flatMap`,
  in-order restoration via `flatMapSequential` (using `FrameSequencer`), and lane-ordered execution
  via `concatMap`. Disposing `EuhedralScheduler` closes its lattice, so its lifecycle must align
  with other users sharing the runtime.
- **Spring Boot**: [
  `EuhedralConfiguration`](../euhedral-spring-core/src/main/java/io/euhedral_execution/spring/core/configuration/EuhedralConfiguration.java)
  provides automatic starter beans for the lattice, scheduler, and operators. It also adds transport
  adapters: backpressure-aware gRPC stream handlers whose response queues respect transport
  readiness, and a high-performance Kafka consumer that ties partition liveness directly to frame
  completion and manages reliable batched offset commits.

### Metrics

Euhedral supports Micrometer out of the box. Supplying a `MeterRegistry` automatically exports
per-core cache depths, capacity utilization, in-flight frame counts, and sampled execution
throughput/latency metrics. When fragments or caches are dismantled, their associated meters are
cleanly deregistered.

### Decision Policies & Benchmarking

Worker loops make scheduling choices by evaluating calibrated thresholds alongside lightweight
offline models:

- [
  `FragmentDecisionWeights`](../euhedral-core/src/main/java/io/euhedral_execution/core/config/FragmentDecisionWeights.java)
  holds body-cost thresholds and idle configurations (the current owner loop bypasses the tree's
  idle-band method).
- [
  `MicroCalibrator`](../euhedral-core/src/main/java/io/euhedral_execution/core/utils/MicroCalibrator.java)
  runs during initialization to convert machine-independent synthetic-work weights into precise,
  nanosecond-calibrated limits tailored to the host CPU. For example, the default DIRECT threshold
  weight of 272 represents a synthetic work weight rather than a literal 272 nanoseconds.
- Model coefficients (such as the default `logistic-05-sqrt` model in [
  `ParticipationLogisticModel`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ParticipationLogisticModel.java)
  and cache timing curves in [
  `cache-scarce-v1`](../python/pareto-weight-calibration/policies/cache-scarce-v1.json)) are fitted
  using the offline Python tools in [
  `python/pareto-weight-calibration/`](../python/pareto-weight-calibration/) based on JMH
  calibration runs.

Production workers evaluate these static, pre-fitted parameters without running complex machine
learning code in the hot loop. The legacy `paretoWeights` configuration field remains purely for
backward compatibility.

The independent [`benchmarks`](../benchmarks/) module houses extensive JMH performance suites
evaluating end-to-end latency, queue scalability, and synthetic irregular workloads.

## Core Invariants

When working with or extending the engine, keep these fundamental principles in mind:

- **Single Control Plane**: There is only ever one active `ControlPlaneLattice` instance per JVM.
- **Pull, Don't Push**: Workers pull work. Never place a synchronized central distributor in the hot
  loop.
- **Safe Routing Updates**: Only update and publish routing tables while the corresponding vertex is
  safely draining.
- **Immutable In-Flight Frames**: Once ingested, a frame's routing metadata, origin, and payload
  must remain strictly immutable.
- **Scoped Ordering**: In-order execution is guaranteed within a specific source and lane rather
  than globally across disparate sources.
- **Thread Confinement**: Resets and internal updates to owner-thread queues must execute directly
  on the owning worker thread.
- **Zero-Allocation Hot Loops**: Keep object allocations, blocking calls, and chatty logging
  completely out of per-frame execution cycles.
- **Deliberate Concurrency Semantics**: Respect the chosen `VarHandle` access modes (plain, opaque,
  acquire/release, volatile); do not alter them without benchmarking and happens-before
  justification.
- **Clean Teardown**: Always close sources and lattices to cleanly release worker threads, native
  memory mappings, and monitoring hooks.

For a deeper look into concrete runtime behavior, explore the test suites under [
`euhedral-core/src/test`](../euhedral-core/src/test/). They serve as the executable specification
for routing, draining, demand generation, and rebalancing.
