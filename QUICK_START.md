# Euhedral Core quick start

This guide builds a small asynchronous pipeline with `euhedral-core`, then introduces routing,
metrics, direct frame ingestion, and recycling. For Reactor applications, start with the
[Reactor quick start](./REACTOR_QUICK_START.md).

## Prerequisites

euhedral-core uses Java 21.

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

## Run a pipeline

`PipelineRunner` converts input values into executable frames, runs your pipeline, and passes
each result to a consumer. The following is a complete example:

```java
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.frames.PipelineFrame;
import io.euhedral_execution.core.ingest.PipelineRunner;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CoreExample {

    public static void main(String[] args) throws InterruptedException {
        ControlPlaneLattice lattice = ControlPlaneLattice.getOrCreate();
        CountDownLatch finished = new CountDownLatch(4);

        PipelineFrame.Builder<Integer, Integer> builder = PipelineFrame.<Integer>builder()
                .fanIn(value -> value * value);

        PipelineRunner<Integer> runner = new PipelineRunner<>(
                builder,
                result -> {
                    System.out.println(result);
                    finished.countDown();
                },
                false);

        try {
            lattice.addUpstream(runner);
            List.of(2, 4, 8, 16).forEach(runner::run);
            runner.completeGracefully();

            if (!finished.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for Euhedral");
            }
        } finally {
            runner.complete();
            lattice.close();
        }
    }
}
```

With `consumeInParallel` set to `false`, the results are emitted in input order:

```text
4
16
64
256
```

`addUpstream` starts the lattice lazily. Calling `lattice.start()` first is also an option when
explicit startup is a better fit for your application lifecycle.

## Observe completion and close a pipeline

`run` is the allocation-light submission path: it does not create a future per input.
Use `submit` when each input needs an outcome, including inputs that never reach the consumer:

```java
var outcome = runner.submit(42);
runner.completeGracefully(); // closes admission, not in-flight continuations
var result = outcome.get(10, TimeUnit.SECONDS);
switch (result.status()) {
    case SUCCESS -> System.out.println("Consumer returned normally");
    case FILTERED -> System.out.println("A predicate rejected this input");
    case CANCELLED -> System.out.println("Execution was cancelled");
    case FAILED -> result.failure().printStackTrace();
}
```

- `completeGracefully()` rejects new inputs and completes the source only after every accepted
  chain has reached a terminal outcome. An empty queue alone does not mean the pipeline finished.
- `complete()` closes admission immediately, trips the shared kill switch, and cancels queued work.
  It does not interrupt a running function. Work already handed to an executor settles when that
  executor finalizes it; cleanup of a queue being drained is deferred until its consumer releases
  it.
  Runtime completion through the source delegate uses this same lifecycle.
- Admission after either close throws `IllegalStateException`. An input admitted just before an
  immediate close can report `CANCELLED` even if it was not yet published.
- Outcomes are published **after** clearing all chain payloads and recycling, but **before**
  updating
  chain accounting. Graceful downstream completion can therefore observe/join every accepted
  outcome,
  even when chains finalize concurrently. Future callbacks can run inline on the finalizing worker;
  keep them nonblocking and do not wait for pipeline progress or graceful source completion there.
  Future cancellation only cancels observation; it does not cancel the pipeline input.
- Submission (`run` and `submit`) still requires one owner: `FrameManager` checkout is not
  thread-safe. Do not submit concurrently, including from future callbacks on workers. Marshal
  those callbacks to the submission owner. Lifecycle close may race that owner safely.

The existing constructor uses one unbounded queue partition. An optional fourth argument configures
more partitions, for example `new PipelineRunner<>(builder, resultConsumer, true, 3)`.
`run(partition, input)` and `submit(partition, input)` validate the partition before checkout;
`run(longSeed, input)` and `submit(longSeed, input)` select a partition from the seed.
Use `run`/`submit` rather than the inherited raw-frame `offer`/`clear` operations for managed
chains.

`filterOutput` applies at the builder's current type boundary, including before its first transform.
Repeated filters at the same boundary compose with short-circuit AND; later transforms or filters
cannot move or erase an earlier predicate. A `CancelSignal` from a function, predicate, or terminal
consumer stops that chain without publishing a successor.

## Choose ordered or distributed execution

The `consumeInParallel` boolean constructor argument controls the routing of the terminal consumer.
Transformations use `fanIn` (ordered/serialized) or `fanOut` (distributed) on the
`PipelineFrame.Builder`:

```java
PipelineFrame.Builder<Integer, String> builder = PipelineFrame.<Integer>builder()
        .fanIn(function) // orders frames of this stage
        .filterOutput(filterFunction) // filters output of the previous stage
        .fanOut(function2); // parallelizes frames of this stage

new PipelineRunner<>(builder, resultConsumer, false); // seriallized terminal consumer
new PipelineRunner<>(builder, resultConsumer, true);  // parallel terminal consumer
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
import io.euhedral_execution.core.frames.RunnableFrame;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import io.euhedral_execution.hashing.HasherApi;

long idHash = HasherApi.mix(12345);

RunnableFrame first = new RunnableFrame(idHash, () -> System.out.println("2"));
RunnableFrame second = new RunnableFrame(idHash, () -> System.out.println("4"));

QueueIngestSink sink = new QueueIngestSink();
if (!sink.offer(first) || !sink.offer(second)) {
    throw new IllegalStateException("Ingest queue is full");
}

lattice.addUpstream(sink);
sink.completeGracefully();
```

Built-in frame types include `ArrayFrame`, `CollectionFrame`, and `RunnableFrame`.

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
