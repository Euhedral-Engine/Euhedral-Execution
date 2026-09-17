# Benchmarks in Amazon ECS using Graviton5

| Config           | Value           |
|:-----------------|:----------------|
| Instance         | AWS c9g.8xlarge |
| Operating System | Amazon Linux    |
| Processor        | AWS Graviton5   |
| Architecture     | arm64           |
| vCPUs            | 32              |

#### VM Flags

```
-XX:+UseThreadPriorities
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
--add-exports java.base/jdk.internal.platform=ALL-UNNAMED
--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED
```

Work items were pre-allocated for all benchmarks to only measure scheduling overhead.

JMH was used for all benchmarking.

---

# TOC

<!-- TOC -->

* [Mandelbrot](#mandelbrot)
  * [Pipeline Architecture](#pipeline-architecture)
    * [Mandelbrot (1-by-1)](#mandelbrot-1-by-1)
        * [Results](#results)
            * [Perf Counter Comparison](#perf-counter-comparison)
            * [Raw Hardware Counters](#raw-hardware-counters)
            * [CPU Time](#cpu-time)
    * [Batched Mandelbrot](#batched-mandelbrot)
        * [Results](#results-1)
            * [Perf Counter Comparison](#perf-counter-comparison-1)
            * [Raw Hardware Counters](#raw-hardware-counters-1)
            * [CPU Time](#cpu-time-1)
* [High-Contention Throughput](#high-contention-throughput)
    * [Results](#results-2)
    * [Allocations](#allocations)
* [End-to-End Latency](#end-to-end-latency)
    * [Results](#results-3)
    * [Percentiles (ns/op)](#percentiles-nsop)
    * [Allocations](#allocations-1)

<!-- TOC -->

---

# Mandelbrot

A deliberately chaotic workload. These benchmarks test performance under highly irregular execution
times. The pixel order is randomized using the same seed for all benchmark runs.

Mission:

Render an 8K [Mandelbrot set](https://en.wikipedia.org/wiki/Mandelbrot_set)

Using:

- 2X SSAA (4 subpixel samples per pixel)
- An iteration cap of 5,000 per subpixel
- Randomized pixel ordering using a fixed seed (`HasherApi.BASE_SEED`)
- Bailout radius squared of 1,000,000.0

Total operations: 132,710,400 (7,680 x 4,320 x 4 SSAA points)

---

### Pipeline Architecture

Work items are pre-allocated during trial setup to focus measurement strictly on scheduling,
coordination, and execution.

#### 1-by-1 Mandelbrot (`MandelbrotBenchmark`)

For the unbatched workload, 33,177,600 `MandelbrotPixel` frames (each computing 4 SSAA points,
yielding 132,710,400 total operations) are pre-allocated and globally shuffled.

- **Reactor (`ReactorMandelbrot`)**:
  Builds pre-constructed parallel pipelines in trial setup using `parallel(parallelism)` where
  `parallelism` matches available processors (32 on this instance):

  ```java
  int parallelism = Runtime.getRuntime().availableProcessors();
  this.parallelPipeline = Flux.fromArray(this.pixels)
          .parallel(parallelism)
          .runOn(Schedulers.parallel())
          .doOnNext(frame -> execute(frame, blackhole))
          .then();

  this.boundedElasticPipeline = Flux.fromArray(this.pixels)
          .parallel(parallelism)
          .runOn(Schedulers.boundedElastic())
          .doOnNext(frame -> execute(frame, blackhole))
          .then();
  ```

  Each benchmark invocation calls `.block()` on the pipeline and verifies completion against the
  operation counter:

  ```java
  this.parallelPipeline.block();
  MandelbrotCompletion.verify(this.counters, EXPECTED_OPERATIONS);
  ```

- **Euhedral Core (`EuhedralMandelbrot`)**:
  Partitions the shuffled pixel canvas evenly across `sourceCount = availableProcessors()` (32)
  independent `Flux` sources, one per CPU core. Each source is subscribed to with a dedicated
  `EuhedralSubscriber` and added upstream to the `ControlPlaneLattice`:

  ```java
  for (int i = 0; i < this.sourceCount; i++) {
      this.sources[i].subscribe(this.subscribers[i]);
  }
  for (EuhedralSubscriber subscriber : this.subscribers) {
      this.controlPlane.addUpstream(subscriber);
  }

  waitOnRender(this.counters);
  ```

#### Batched Mandelbrot (`BatchedMandelbrotBenchmark`)

For the batched workload, the 33,177,600 randomized pixels are bundled into 32,400 `BenchArrayFrame`
containers of 1,024 pixels each:

- **Reactor Parallel**:
  Fans out the 32,400 frames across 32 rails using
  `parallel(parallelism).runOn(Schedulers.parallel())`:

  ```java
  this.parallelFluxPipeline = Flux.fromArray(this.frames)
          .parallel(parallelism)
          .runOn(Schedulers.parallel())
          .doOnNext(frame -> execute(frame, blackhole))
          .then();
  ```

- **Reactor BoundedElastic**:
  Wraps each batch into a `Mono.fromRunnable(...).subscribeOn(Schedulers.boundedElastic())` and
  schedules
  them concurrently via `Flux.flatMap`:

  ```java
  Flux.fromArray(tasks)
          .flatMap(Function.identity(), Runtime.getRuntime().availableProcessors())
          .then();
  ```

- **Euhedral Core**:
  Subscribes a single `EuhedralSubscriber` to the 32,400 batched frames and registers it upstream
  with the
  control plane lattice, which distributes the batches across worker shards:

  ```java
  Flux.fromArray(this.frames).subscribe(subscriber);
  this.controlPlane.addUpstream(this.subscriber);
  waitOnRender(this.counters);
  ```

---

## Mandelbrot (1-by-1)

Source: [
`MandelbrotBenchmark.java`](./src/main/java/io/euhedral_execution/benchmarks/core_benchmarks/MandelbrotBenchmark.java)
(selector: `mandelbrot`)

Pixels are ingested one at a time as individual `MandelbrotPixel` frames. This benchmark tests
fine-grained
scheduling overhead and execution behavior when handling a high volume of small, irregular tasks.
The
pixel order is pseudo-randomly shuffled using `HasherApi.BASE_SEED` to eliminate spatial locality
and
induce chaotic branch and iteration patterns.

- For Euhedral Core, the 33,177,600 pixels are partitioned across 32 upstream `Flux` sources (one
  per core),
  each fed via a dedicated `EuhedralSubscriber` into the control plane lattice shards.
- For Reactor, the complete pixel array is parallelized across 32 rails using
  `Flux.parallel().runOn(...)`.

- **Work items:** 33,177,600 frames
- **Operations per work item:** 4 (2X SSAA subpixels)
- **Total operations per invocation:** 132,710,400

---

### Results

![](../data/ec2_1b1_mandelbrot_ns_op.png)
![](../data/ec2_1b1_mandelbrot_allocations.png)

| Scheduler              |   ns/op | Alloc mb/sec | bytes/op | GC Counts | GC Time |
|:-----------------------|--------:|-------------:|---------:|----------:|--------:|
| Euhedral Core          | 278.291 |       81.950 |   24.209 |         4 |      10 |
| Reactor Parallel       | 275.052 |       83.217 |   24.001 |         4 |      12 |
| Reactor BoundedElastic | 274.233 |       83.466 |   24.001 |         4 |      13 |

---

#### Perf Counter Comparison

| Scheduler              | IPC  | L1 D-Cache Miss | L1 I-Cache Miss | dTLB Miss | iTLB Miss % | Branch Miss % |
|------------------------|------|----------------:|----------------:|----------:|------------:|--------------:|
| Euhedral Core          | 2.96 |           0.09% |           0.02% |     0.06% |       0.00% |     0.012654% |
| Reactor Parallel       | 3.12 |           0.02% |           0.00% |     0.01% |       0.00% |     0.008132% |
| Reactor BoundedElastic | 3.06 |           0.02% |           0.00% |     0.01% |       0.00% |     0.008270% |

---

#### Raw Hardware Counters

| Scheduler              |             Cycles |       Instructions |  Cache Misses |      Branch Loads | Branch Misses |
|------------------------|-------------------:|-------------------:|--------------:|------------------:|--------------:|
| Euhedral Core          | 11,490,391,032,597 | 33,959,052,728,843 | 3,946,345,627 | 6,529,102,819,591 |   826,186,868 |
| Reactor Parallel       | 11,111,260,138,690 | 34,642,328,112,461 |   971,602,309 | 6,388,791,265,005 |   519,523,951 |
| Reactor BoundedElastic | 11,104,417,504,910 | 34,020,221,711,784 | 1,009,529,444 | 6,403,638,286,663 |   529,564,867 |

---

#### CPU Time

| Runtime                | Wall Clock Runtime | User Seconds | System Time |
|------------------------|-------------------:|-------------:|------------:|
| Euhedral Core          |            129.628 |         3580 |         437 |
| Reactor Parallel       |            107.502 |         3520 |           3 |
| Reactor BoundedElastic |            107.431 |         3509 |           3 |

---

## Batched Mandelbrot

Source: [
`BatchedMandelbrotBenchmark.java`](./src/main/java/io/euhedral_execution/benchmarks/core_benchmarks/BatchedMandelbrotBenchmark.java)
(selector: `batched-mandelbrot`)

Pixels are ingested in batches of 1,024 using `BenchArrayFrame` containers. Grouping pixels into
sub-arrays reduces the total frame count while increasing the work density per dispatched item,
measuring execution efficiency and batch fan-out capability. Because pixels are randomized before
batching,
each chunk of 1,024 pixels exhibits relatively uniform execution times.

- For Euhedral Core, a single upstream `Flux` stream containing the 32,400 batch frames is
  subscribed to by
  a `EuhedralSubscriber` and ingested by `ControlPlaneLattice`, which distributes the frames across
  its shards.
- For Reactor Parallel, the frames are dispatched using
  `Flux.fromArray(frames).parallel().runOn(Schedulers.parallel())`.
- For Reactor BoundedElastic, each frame is wrapped in a `Mono` scheduled on
  `Schedulers.boundedElastic()` and
  concurrency-limited via `flatMap`.

- **Work items:** 32,400 batch frames (1,024 pixels each)
- **Total pixels:** 33,177,600
- **Total operations per invocation:** 132,710,400

---

### Results

![](../data/ec2_batched_mandelbrot_ns_op.png)
![](../data/ec2_batched_mandelbrot_allocations.png)

| Scheduler              |   ns/op | Alloc mb/sec | bytes/op | GC Counts | GC Time |
|:-----------------------|--------:|-------------:|---------:|----------:|--------:|
| Euhedral Core          | 275.753 |       83.666 |   24.193 |         4 |       5 |
| Reactor Parallel       | 281.244 |       81.383 |   24.001 |         4 |       5 |
| Reactor BoundedElastic | 277.520 |       82.984 |   24.148 |         3 |       9 |

---

#### Perf Counter Comparison

| Scheduler              | IPC  | L1 D-Cache Miss | L1 I-Cache Miss | dTLB Miss | iTLB Miss % | Branch Miss % |
|------------------------|------|----------------:|----------------:|----------:|------------:|--------------:|
| Euhedral Core          | 2.98 |           0.03% |           0.00% |     0.02% |       0.00% |     0.009055% |
| Reactor Parallel       | 2.99 |           0.02% |           0.00% |     0.01% |       0.00% |     0.008219% |
| Reactor BoundedElastic | 2.99 |           0.02% |           0.00% |     0.01% |       0.00% |     0.008892% |

---

#### Raw Hardware Counters

| Scheduler              |             Cycles |       Instructions |  Cache Misses |      Branch Loads | Branch Misses |
|------------------------|-------------------:|-------------------:|--------------:|------------------:|--------------:|
| Euhedral Core          | 11,183,710,810,951 | 33,369,463,581,220 | 1,330,882,727 | 6,410,646,955,568 |   580,494,811 |
| Reactor Parallel       | 11,094,601,800,985 | 33,133,501,306,257 |   959,962,406 | 6,363,130,410,595 |   522,989,971 |
| Reactor BoundedElastic | 11,124,109,477,405 | 33,245,012,969,681 | 1,002,857,115 | 6,382,424,563,911 |   567,498,368 |

---

#### CPU Time

| Runtime                | Wall Clock Runtime | User Seconds | System Time |
|------------------------|-------------------:|-------------:|------------:|
| Euhedral Core          |            108.458 |         3524 |          11 |
| Reactor Parallel       |            109.614 |         3512 |           3 |
| Reactor BoundedElastic |            108.892 |         3510 |           6 |

---

# High-Contention Throughput

Source: [
`HighContentionThroughput.java`](./src/main/java/io/euhedral_execution/benchmarks/core_benchmarks/HighContentionThroughput.java)
(selector: `core-hc-throughput`)

Measures sustained peak ingest and execution throughput under heavy multi-source contention using
pre-allocated
`NoOpFrame` objects:

- **Workload:** 32,000,000 tasks (`TASKS`) per JMH invocation executed via `NoOpExecutor`.
- **Contention topology:** Work is continuously pushed through
  `SOURCES = Math.max(1, SystemInfo.getCoreCount() - 1)`
  (31 concurrent upstream sinks on a 32-core instance) independent `RepeatingSink` sources, each
  repeatedly supplying
  batches of 2,048 pre-allocated `NoOpFrame`s.
- **Harness core isolation:** To prevent JMH harness measurement loop interference,
  `isolateHarnessCore()` pins the
  benchmark harness thread to an isolated core (the highest physical core on homogeneous
  architectures like Graviton).
  The remaining 31 physical cores (`workerCpuSet`) are dedicated exclusively to the
  `ControlPlaneLattice` worker shards.
- **Measurement mode:** JMH `Mode.Throughput` recording operations per nanosecond and operations per
  second to complete
  each 32,000,000 task target.

---

#### Results

| Scheduler     | ops/ns |     ops/sec | Avg ns/op |
|---------------|-------:|------------:|----------:|
| Euhedral Core |  0.720 | 720,000,000 |     1.389 |

---

#### Allocations

| Scheduler     | Alloc mb/sec | bytes/op | GC Count | GC Time ms |
|---------------|-------------:|---------:|---------:|-----------:|
| Euhedral Core |        0.677 |    0.001 |        0 |          0 |

---

# End-to-End Latency

Source: [
`EndToEndLatencyBenchmark.java`](./src/main/java/io/euhedral_execution/benchmarks/core_benchmarks/EndToEndLatencyBenchmark.java)
(selector: `core-latency`)

Measures the end-to-end sample time latency distribution for frames passing through the complete
Euhedral Core
pipeline: ingestion, routing, scheduling, queue residency, shard dispatch, and execution via
`NoOpExecutor`.

- **Workload:** 100,000 (`BATCH_SIZE`) pre-allocated `NoOpFrame` objects per invocation.
- **Topology:** A single upstream `RepeatingSink` feeds frames to the `ControlPlaneLattice`. The
  lattice is
  configured across **two physical cores** (`cores.nextSetBit(1)` and its adjacent core from
  `SystemInfo.getPCoreSet()`),
  measuring inter-core shard dispatch and coordination without whole-socket interference.
- **Measurement mode:** JMH `Mode.SampleTime` measuring the sampled latency distribution (average
  and percentiles)
  per operation.

---

#### Results

| Scheduler     | Avg ns/op |
|---------------|----------:|
| Euhedral Core |    41.622 |

---

#### Percentiles (ns/op)

| Scheduler     | p0 | p50 | p90 | p95 | p99 | p999 |   p9999 | p100 |
|---------------|---:|----:|----:|----:|----:|-----:|--------:|-----:|
| Euhedral Core | 33 |  40 |  42 |  63 |  81 |   88 | 119.752 |  216 |

---

#### Allocations

| Scheduler     | Alloc mb/sec | bytes/op | GC Count | GC Time ms |
|---------------|-------------:|---------:|---------:|-----------:|
| Euhedral Core |        0.526 |    0.023 |        0 |          0 |
