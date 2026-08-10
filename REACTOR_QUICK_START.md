# Euhedral Reactor quick start

`euhedral-reactor-core` connects Reactor pipelines to Euhedral's topology-aware execution engine. It
provides a standard Reactor `Scheduler` implementation and mapping operators that create, route, and
recycle Euhedral frames for you.

For context, the
[high-scale scheduler comparison](./benchmarks/HIGH_SCALE_BENCHMARKS.md) runs an 8K Mandelbrot
workload on 96-core Intel and AMD systems and a 192-core Graviton5 system. In that workload, Reactor
Parallel took 4.7x to 5.8x as much time per operation as Euhedral Core. The report includes the
feeding strategy, allocation results, hardware counters, and limitations.

## Prerequisites

The full repository uses Java 21.

Add the Reactor integration to an application:

```xml
<dependency>
  <groupId>io.euhedral-execution</groupId>
  <artifactId>euhedral-reactor-core</artifactId>
  <version>0.0.7-SNAPSHOT</version>
</dependency>
```

Run the application with:

```text
-XX:+UseThreadPriorities
```

## Map a Flux on Euhedral

Create the process-wide lattice, adapt it as a Reactor scheduler, and construct an operator:

```java
import io.euhedral_execution.reactor.EuhedralOperator;
import io.euhedral_execution.reactor.EuhedralScheduler;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Flux;

public final class ReactorExample {

    public static void main(String[] args) {
        EuhedralScheduler scheduler = EuhedralScheduler.getOrCreate();
        EuhedralOperator operator = new EuhedralOperator(scheduler);

        try {
            List<String> results = Flux.range(1, 100)
                    .transform(operator.flatMapSequential(
                            value -> "item-" + expensiveTransform(value)))
                    .collectList()
                    .block(Duration.ofSeconds(10));

            System.out.println(results);
        } finally {
            scheduler.dispose();
        }
    }

    private static int expensiveTransform(int value) {
        return value * value;
    }
}
```

The scheduler registers its worker sources with the lattice, which starts lazily. The operator
handles the Reactor subscription, backpressure requests, frame creation, routing, response delivery,
cancellation, and recycling.

## Choose an operator

The transformer methods fit directly into `Flux.transform`:

| Operator            | Execution                        | Result order             |
|---------------------|----------------------------------|--------------------------|
| `flatMap`           | Distributed across routing lanes | Completion order         |
| `flatMapSequential` | Distributed across routing lanes | Restored to source order |
| `concatMap`         | Kept on an ordered routing lane  | Source order             |

Examples:

```java
Flux<Result> fastestAvailable = input.transform(
        operator.flatMap(this::calculate));

Flux<Result> parallelButOrdered = input.transform(
        operator.flatMapSequential(this::calculate));

Flux<Result> oneOrderedLane = input.transform(
        operator.concatMap(this::calculate));
```

Choose `flatMap` when values are independent and completion order does not matter.
`flatMapSequential` preserves the input order while still distributing computation.
`concatMap` keeps the stream on an ordered Euhedral route.

The mapper is synchronous and runs on a Euhedral worker. Blocking is supported, but a blocked
Euhedral worker cannot execute other frames until the call returns. That is often an acceptable
tradeoff: the blocked thread still occupies its assigned core, so moving unrelated work onto that
worker would not make the core available to another thread. Use asynchronous Reactor composition
when you want the worker to remain available while waiting, and block when the simpler execution
model fits the workload.

## Use the Scheduler API

`EuhedralScheduler` also implements Reactor's normal `Scheduler` contract:

```java
Flux<Integer> results = Flux.range(1, 100)
        .publishOn(scheduler)
        .map(this::calculate);
```

`publishOn` moves downstream signals onto Euhedral. `subscribeOn` can be used when the subscription
and source execution should begin there:

```java
Flux<Integer> results = Flux.range(1, 100)
        .map(this::calculate)
        .subscribeOn(scheduler);
```

Use `EuhedralScheduler` when you're executing low-volume `Runnable` tasks. Prefer
`EuhedralOperator` for mapping workloads that use Euhedral frame routing, ordered or distributed
lanes, and frame recycling. The operator is more memory efficient than the scheduler.

## Configure names and metrics

The shortest configuration form creates the scheduler and its lattice together:

```java
EuhedralScheduler scheduler = EuhedralScheduler.getOrCreate(
        "PricingEngine",
        "PricingWorker",
        "euhedral.pricing",
        meterRegistry);
```

To share Core and Reactor work on one control plane, create the lattice yourself and pass it to
`getOrCreate`, as in the complete example.

Both `ControlPlaneLattice` and `EuhedralScheduler` are JVM-wide singletons. Configure them once
during application startup rather than creating one per pipeline. Calling `dispose()` on
`EuhedralScheduler` will terminate all of its ingest sources and shutdown the lattice.

## Tune operator buffers

The default operator keeps up to 2,048 recycled frames and uses a 4,096-element response queue:

```java
EuhedralOperator operator = new EuhedralOperator(scheduler);
```

For a measured workload, both capacities can be configured:

```java
int recycleCapacity = 8_192;
int responseQueueSize = 16_384;

EuhedralOperator operator =
        new EuhedralOperator(scheduler, recycleCapacity, responseQueueSize);
```

Treat these as bounded-memory and backpressure controls, not general throughput knobs. Start with
the defaults and change them only after observing the target workload.

## Cancellation and errors

Cancelling the downstream subscription stops new input from being framed and marks outstanding
responses as cancelled. Mapper failures propagate through the returned publisher and stop that
operator pipeline.

Keep ordinary Reactor cleanup in `doFinally`, `using`, or the application's lifecycle hooks. The
lattice itself is shared infrastructure and should only be closed when the whole application is
stopping.

## Shut down cleanly

At application shutdown:

```java
scheduler.dispose();
```

Disposing the scheduler completes its worker sources and closes the lattice. Closing the lattice
stops its pinned workers, resource monitor, and topology state. The lattice adds automatic shutdown
hooks to JVM runtime in the event a crash prevents a graceful shutdown.

In Spring Boot applications, `euhedral-spring-core` auto-configures the lattice, scheduler, and
operator. The application context owns the lattice lifecycle.

## Next steps

- [Core quick start](./QUICK_START.md)
- [Architecture, including Reactor data flow](./docs/ARCHITECTURE.md)
- [`euhedral-reactor-core` source](./euhedral-reactor-core)
