# Euhedral Core quick start

This guide builds a small asynchronous pipeline with `euhedral-core`, then introduces routing,
metrics, direct frame ingestion, and recycling. For Reactor applications, start with the
[Reactor quick start](./REACTOR_QUICK_START.md).

## Prerequisites

The full repository uses Java 21.

Add the Core artifact:

```xml
<dependency>
  <groupId>io.euhedral-execution</groupId>
  <artifactId>euhedral-core</artifactId>
  <version>0.0.7-SNAPSHOT</version>
</dependency>
```

Run your application with:

```text
-XX:+UseThreadPriorities
```

Thread-priority and affinity behavior still depends on the host operating system and process
permissions.

## Run a function pipeline

`FunctionIngestSink` converts input values into recyclable frames, executes a function, and passes
each result to a consumer. The following is a complete example:

```java
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.ingest.FunctionIngestSink;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CoreExample {

    public static void main(String[] args) throws InterruptedException {
        ControlPlaneLattice lattice = ControlPlaneLattice.getOrCreate();
        CountDownLatch finished = new CountDownLatch(4);

        FunctionIngestSink<Integer, Integer> squares = new FunctionIngestSink<>(
                value -> value * value,
                result -> {
                    System.out.println(result);
                    finished.countDown();
                },
                false);

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
    }
}
```

With `parallel` set to `false`, the results are emitted in input order:

```text
4
16
64
256
```

`addUpstream` starts the lattice lazily. Calling `lattice.start()` first is also an option when
explicit startup is a better fit for your application lifecycle.

`ConsumerIngestSink` provides the same setup for a `Consumer<T>` function that does not produce
results.

## Choose ordered or distributed execution

The final `FunctionIngestSink` constructor argument controls routing:

```java
new FunctionIngestSink<>(function, resultConsumer, false); // ordered lane
new FunctionIngestSink<>(function, resultConsumer, true);  // distributed work
```

- `false` leaves `routingHash` equal to `idHash`. Frames from this source share a stable routing
  lane and execute in input order.
- `true` randomizes the routing hash of every fresh and recycled frame. Work can spread across
  active cores, so result order is not guaranteed.

Ordering is scoped to one ingest source and routing lane. It is not a process-wide ordering
guarantee.

Do not modify a frame's routing metadata or payload after ingestion.

## Configure names and metrics

Euhedral supports one active `ControlPlaneLattice` per JVM. Use `LatticeConfig` to name it and
publish per-CPU metrics:

```java
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.micrometer.core.instrument.MeterRegistry;

MeterRegistry registry = createRegistry();

LatticeConfig config = LatticeConfig.ofDefaults(
        "OrderEngine",
        "OrderWorker",
        "euhedral.orders",
        registry);

ControlPlaneLattice lattice = ControlPlaneLattice.getOrCreate(config);
```

The metric prefix defaults to `euhedral` when a registry is supplied with a blank prefix.

## Ingest frames directly

Use `QueueIngestSink` when you want to construct frames yourself. Frames with the same `idHash`
start with the same `routingHash` and use the same ordered lane:

```java
import io.euhedral_execution.core.frames.FunctionFrame;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import io.euhedral_execution.hashing.HasherApi;

long idHash = HasherApi.mix(12345);

FunctionFrame<Integer, Integer> first =
        new FunctionFrame<>(idHash, value -> value * value, System.out::println, 2);
FunctionFrame<Integer, Integer> second =
        new FunctionFrame<>(idHash, value -> value * value, System.out::println, 4);

QueueIngestSink sink = new QueueIngestSink();
if (!sink.offer(first) || !sink.offer(second)) {
    throw new IllegalStateException("Ingest queue is full");
}

lattice.addUpstream(sink);
sink.completeGracefully();
```

Built-in frame types include `ArrayFrame`, `CollectionFrame`, `FunctionFrame`, `ConsumerFrame`, and
`RunnableFrame`.

To distribute independent frames, randomize each routing hash before offering the frame:

```java
long seed = HasherApi.mix(54321);

first.randomizeHash(seed++);
second.randomizeHash(seed++);
```

Use a changing, well-mixed seed. `randomizeHash` mixes the seed with the frame identity; it does not
change `idHash`.

## Recycle custom frames

Recycling is useful for sustained, high-volume workloads where per-item allocation matters.
`FrameManager` owns recycled frames and `FrameFactory` defines their fresh and replacement paths:

```java
long password = HasherApi.mix(1234);
long[] seed = {HasherApi.mix(5678)};
AtomicBoolean killSwitch = new AtomicBoolean();

FrameManager<String, MessageFrame> manager = new FrameManager<>(2_048, password);

FrameFactory.FrameCreate<String, MessageFrame> create = (idHash, message) -> {
    MessageFrame frame =
            new MessageFrame(idHash, message, manager, killSwitch);
    frame.randomizeHash(seed[0]++);
    return frame;
};

FrameFactory.FrameReplace<String, MessageFrame> replace = (message, frame) -> {
    frame.replace(message);
    frame.randomizeHash(seed[0]++);
};

manager.setFactory(new FrameFactory<>(create, replace));
```

A minimal matching frame is:

```java
final class MessageFrame extends AbstractFrame {

    private String message;

    MessageFrame(long idHash, String message,
            FrameManager<String, MessageFrame> manager,
            AtomicBoolean killSwitch) {
        super(idHash, manager, killSwitch);
        this.message = message;
    }

    @Override
    public void execute() {
        System.out.println(message);
    }

    @Override
    public void doFinallyWithError(Throwable error) {
        try {
            error.printStackTrace();
        } finally {
            recycle();
        }
    }

    void replace(String message) {
        this.message = message;
    }
}
```

`AbstractFrame` already implements the normal liveness, cancellation, and recycling behavior when it
receives a manager and kill switch. Override those methods only when your frame needs a different
contract.

`FrameFactory.replace()` restores `routingHash` to `idHash` before invoking the replacement
callback. A recycled parallel frame must therefore call `randomizeHash` again in that callback, as
the example does.

The manager can then feed a queue sink without allocating a new frame for every item:

```java
QueueIngestSink sink = new QueueIngestSink();
lattice.addUpstream(sink);

for (int i = 0; i < 1_000_000; i++) {
    while (!sink.offer(manager.getOrCreate("message-" + i, password))) {
        Thread.onSpinWait();
    }
}

sink.completeGracefully();
```

## Shut down cleanly

Euhedral owns persistent workers and hardware monitoring resources. Application shutdown should:

1. Stop producing new values.
2. Call `completeGracefully()` on each source and wait for application-level completion.
3. Call `ControlPlaneLattice.close()`.

Use `complete()` when queued work should be cancelled instead of drained. Always close the lattice
in tests as well; it is a JVM-wide singleton, and leaking it can affect the next test.

## Next steps

- [Reactor quick start](./REACTOR_QUICK_START.md)
- [Architecture and runtime invariants](./docs/ARCHITECTURE.md)
- [Module and contributor guidance](./AGENTS.md)
