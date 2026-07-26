# AGENTS.md - Euhedral Engine Development Guide

## Project Overview

Euhedral is a low-latency, adaptive execution system (Java 21+) designed for CPU-topology-aware work distribution. It treats work (frames) as pull-based streams routed through a three-tier hierarchical control plane aligned with hardware topology: **Lattice** (global) → **Shard** (socket) → **Fragment** (core).

## Architecture & Design Patterns

### Three-Tier Control Plane Architecture

Work flows through a hierarchy mirroring CPU topology:

1. **ControlPlaneLattice** (`euhedral-core`) - System-wide coordinator
   - Manages all ControlPlaneShards (one per socket)
   - Discovers hardware topology using `euhedral-hardware-utils`
   - Handles global rebalancing and topology changes
   - Entry point: `ControlPlaneLattice.getOrCreate()` / `.start()`

2. **ControlPlaneShard** - Per-socket manager
   - Manages ControlPlaneFragments for its cores
   - Distributes ingress across fragments
   - Implements `CloneableObject` pattern for per-core variants

3. **ControlPlaneFragment** - Per-core execution loop
   - Pinned to single CPU core via `euhedral-hardware-utils/ThreadPinner`
   - Adaptive scheduling: adjusts concurrency/dispatch rate based on queue pressure
   - Transitions idle: spin → yield → park

### Hash-Based Deterministic Routing

- Every frame carries `idHash` (stable identity) and mutable `routingHash` (placement)
- LatticeVertex uses unsigned multiply-high: `unsignedMultiplyHigh(frame.routingHash, mapSize)` for consistent fan-out
- Frames with same routingHash execute sequentially; different hashes execute in parallel
- Preserve ordering: keep idHash; parallelize: randomize routingHash via `frame.randomizeHash(seed++)`

### CloneableObject Protocol

All distributed components implement lifecycle:
- `clone(CloneConfig)` - Create per-core variant
- `firstTouch()` - Initialize thread-local state
- `start()` - Begin operation
- `ingestNext(frame)` - Accept work
- `getOutput()` - Retrieve results

See: `/euhedral-core/src/main/java/io/euhedral_execution/core/generics/CloneableObject.java`

### Frame Model

- **AbstractFrame** - Recyclable work unit (extends CloneableObject)
  - Executes: `execute()` method (user-defined)
  - Lifecycle: `isAlive()` → `execute()` → `doFinally()` / `doFinallyWithError()`
  - Cancelable: `throwCancelSignal()` throws internal `CancelSignal` exception
  - Origin tracked: carries `CpuInfo` of creation point

- **Built-in implementations**: FunctionFrame, ConsumerFrame, RunnableFrame, ArrayFrame, CollectionFrame
- **Recycling**: FrameManager reduces GC; resets routingHash after execution (must re-randomize in replace() if parallel needed)

## Coding Conventions

### Memory Semantics - VarHandle Usage

This codebase uses precise JMM control via VarHandle for lock-free concurrency:

```java
// PaddedAtomicLong.java pattern
private static final VarHandle VH_VALUE = ...;

// Access levels: opaque (no ordering) → acquire/release (partial) → volatile
value.get();              // Opaque read
value.getAcquire();       // Acquire semantics
value.setRelease(newVal); // Release semantics
```

Never use `AtomicLong` directly; use `PaddedAtomicLong` from `euhedral-data-structures` to prevent false sharing in high-contention scenarios.

See: `/euhedral-data-structures/src/main/java/io/euhedral_execution/data_structures/atomics/`

### Padded Data Structures

Cache-line padding (128 bytes) to isolate hot fields:
- `PaddedAtomicLong`, `PaddedLong` base classes
- Prevents false-sharing in shared-memory contention

Use for any field accessed from multiple cores in tight loops.

### Hardware Abstraction - Cross-Platform Native Layer

Euhedral abstracts Linux/Windows/macOS topology via JNI (built with Zig):

- `SystemInfo` - Full topology discovery (sockets, cores, cache hierarchy, NUMA)
- `ThreadPinner` - CPU affinity control (linker: `-Xbootclasspath/a:...`)
- `PinnedThreadExecutor` - Executor pinned to specific cores
- `ResourceMonitor` - Per-core hardware telemetry

**Build requirement**: `zig 0.16.0` (via `mise`); produces `.so`/`.dll`/`.dylib` for x64/arm64.

See: `/euhedral-hardware-utils/src/main/resources/build.sh`

### Naming Conventions

| Term | Meaning |
|------|---------|
| **Fragment** | Per-core execution unit (ControlPlaneFragment, FragmentConfig) |
| **Lattice** | Routing graph structure (LatticeVertex, LatticeEdge, LatticeSource) |
| **Clone** | Per-core variant of CloneableObject (via clone(CloneConfig)) |
| **Shard** | Socket-scoped manager (ControlPlaneShard: manages N cores) |
| **Frame** | Recyclable work unit (AbstractFrame subclass) |
| **Sink** | Ingress entry point (FunctionIngestSink, QueueIngestSink, ArrayIngestSink) |

## Common Workflows

### 1. Basic Setup & Execution

```java
// VM flags required:
// -XX:+UseThreadPriorities --add-opens java.base/java.util=ALL-UNNAMED

ControlPlaneLattice lattice = ControlPlaneLattice.getOrCreate();
lattice.start();

// Level 1: Built-in sinks
Function<Integer, Integer> square = x -> x * x;
FunctionIngestSink<Integer, Integer> sink = new FunctionIngestSink<>(square, System.out::println, false);
lattice.addUpstream(sink);
sink.push(Arrays.asList(2, 4, 8));
sink.completeGracefully(); // Graceful drain
```

### 2. Parallel Execution with Hash Randomization

```java
long idHash = HasherApi.mix(123);
long seed = HasherApi.mix(456);

for (int i = 0; i < 1000; i++) {
    FunctionFrame<Data, Result> frame = new FunctionFrame<>(idHash, fn, consumer, data);
    frame.randomizeHash(seed++); // Varies routing → parallel execution
    sink.push(frame);
}
```

### 3. Custom Frames with Recycling

```java
public class MyCustomFrame extends AbstractFrame {
    private AtomicBoolean killSwitch;
    private MyData payload;
    
    public MyCustomFrame(long idHash, MyData payload, FrameManager<MyData, MyCustomFrame> mgr) {
        super(idHash, mgr);
        this.payload = payload;
    }
    
    @Override
    public void execute() {
        // User logic; can call throwCancelSignal() to cancel
    }
    
    @Override
    public boolean isAlive() {
        return !killSwitch.getOpaque(); // Memory semantics
    }
    
    @Override
    public void doFinally() {
        super.recycle(); // Returns to pool
    }
}

// Usage with recycler:
FrameManager<MyData, MyCustomFrame> manager = new FrameManager<>(password);
manager.setFactory(new FrameFactory<>(
    (idHash, data) -> new MyCustomFrame(idHash, data, manager),
    (data, frame) -> { frame.replace(data); frame.randomizeHash(seed++); }
));
```

### 4. Reactor Integration

```java
// euhedral-reactor-core provides Euhedral-backed Scheduler
scheduler = EuhedralSchedulers.fromExecutor(executor);
Mono.just(data)
    .publishOn(scheduler)
    .subscribe(...);
```

### 5. Spring Integration

```java
// euhedral-spring-core integrates with Spring async/scheduling
@Configuration
@EnableAsync
public class EuhedralConfig {
    @Bean
    public Executor euhedralExecutor() {
        return new EuhedralExecutor(lattice);
    }
}
```

## Build & Testing

### Multi-Module Maven Project

```bash
# Java 21+ required; managed via mise.toml
mvn clean compile          # Compile all modules
mvn test                   # Run unit tests (JUnit 5 + Mockito)
mvn install                # Package locally

# Individual modules
mvn -f euhedral-core -DskipTests clean package
mvn -f euhedral-hardware-utils clean compile
```

### Key Dependencies

- **Core framework**: Lombok (annotation processing), SLF4J/Logback (logging), Micrometer (metrics)
- **Concurrency**: Custom internal lock-free queues (SPSC/SPMC/MPSC/MPMC), fastutil (primitive collections), jctools (benchmarking only)
- **Testing**: JUnit 5, Mockito, Awaitility (async verification), Reactor-test
- **Native**: JNI, Zig (cross-platform compilation)

### Test Pattern

```java
// Location: /euhedral-core/src/test/java/io/euhedral_execution/core/.../ModuleTest.java
public class LatticeVertexTest {
    private LatticeVertex vertex;
    private FrameRecorder recorder;
    
    @BeforeEach
    void setup() {
        vertex = new LatticeVertex(3); // 3-way fan-out
    }
    
    @Test
    void testRoutingDistribution() {
        TestFrame frame = new TestFrame(42);
        vertex.onNext(frame);
        assertEquals(1, recorder.recordedFrames().size());
    }
}
```

Use `TestFrame` helper from `test_utils/` package; spy/verify with Mockito.

## Module Overview

### euhedral-core
The execution engine and primary entry point. Contains ControlPlaneLattice, ControlPlaneShard, ControlPlaneFragment, AbstractFrame, and routing infrastructure (LatticeVertex, LatticeEdge).

### euhedral-data-structures
**Custom internal lock-free queue implementations**: SPSC, SPMC, MPSC, MPMC queues in partitioned, bounded, and unbounded variants. Optimized for batch consumption in high-contention scenarios. Also includes PaddedAtomicLong and other padded atomic types for cache-line isolation. (jctools is used only for comparative benchmarking, not as the primary data structure dependency.)

### euhedral-hardware-utils
Cross-platform JNI bridge (Linux, Windows, macOS; x64, arm64). Provides SystemInfo for topology discovery, ThreadPinner for CPU affinity, ResourceMonitor for per-core telemetry. Native code built with Zig 0.16.0.

### euhedral-hashing
xxHash64-based deterministic hashing for frame routing and load distribution.

### euhedral-reactor-core
Reactor Project integration layer. Provides PublisherAdapter and EuhedralScheduler for Reactive Streams compatibility (publishOn/subscribeOn support).

### euhedral-spring-core
Spring Framework integration. Provides EuhedralExecutor implementing Spring's Executor interface for @Async and Spring Task scheduling.

### euhedral-training
Benchmarking and training examples. Includes JMH microbenchmarks and workload demonstrations.

## Critical Considerations

### Performance & Tuning

1. **Concurrency**: System adapts via TCP Vegas-style latency estimation (observe queue pressure, backpressure)
2. **Dispatch rate**: Automatically adjusted; watch `euhedral.metrics.fragment.dispatch_rate`
3. **Memory layout**: Padded atomics essential for > 10M frames/sec
4. **GC pressure**: Use FrameManager recycling for sustained workloads

### Topology Awareness

- RoutingPolicy enum: ANYWHERE > SOCKET_LOCAL > CACHE_LOCAL
- System rebalances automatically on topology changes (CPU hotplug, NUMA reconfiguration)
- Monitor via `SystemInfo.current()` for live topology

### Error Handling

- Frames must catch `AbstractFrame.CancelSignal` and rethrow if custom cancel logic needed
- `doFinallyWithError(Throwable t)` called on execution exception
- Use frame.isAlive() guards to implement cancellation windows

### Integration Points

- **Reactor**: `PublisherAdapter` wraps ingest sinks as Publishers
- **Spring**: `EuhedralExecutor` implements Spring `Executor` interface
- **gRPC**: Spring-gRPC integration available (config in parent pom)
- **Monitoring**: Micrometer integration with `ControlPlaneConfig` constructor

## Directory Structure

```
euhedral-engine/
├── euhedral-core/             # Execution engine (Lattice, Shard, Fragment, Frames)
├── euhedral-data-structures/  # Lock-free queues, padded atomics
├── euhedral-hardware-utils/   # Topology, pinning, monitoring (JNI bridge)
├── euhedral-hashing/          # xxHash64-based routing (deterministic)
├── euhedral-reactor-core/     # Reactor scheduler integration
├── euhedral-spring-core/      # Spring async executor integration
├── euhedral-training/         # Benchmarks & training examples
└── benchmarks/                # JMH benchmark suite
```

## Key Files to Study

1. **ARCHITECTURE.md** - Deep dive into control plane hierarchy and design decisions
2. **QUICK_START.md** - 5 usage levels (Lattice setup → custom frames → recycling)
3. `euhedral-core/.../ControlPlaneLattice.java` - System lifecycle
4. `euhedral-core/.../AbstractFrame.java` - Frame contract & lifecycle
5. `euhedral-data-structures/.../PaddedAtomicLong.java` - Memory semantics pattern
6. `euhedral-hardware-utils/.../SystemInfo.java` - Topology discovery

## Debugging Tips

- Enable `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation` for JIT diagnostics
- Watch `euhedral.metrics.*` gauges for per-core pressure signals
- Use `ThreadTools.getCurrentCore()` to verify pinning
- Frame execution happens asynchronously; use `sink.completeGracefully()` with timeout for controlled shutdown



