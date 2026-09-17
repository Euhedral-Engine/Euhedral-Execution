# High-scale scheduler comparison

In the 8K Mandelbrot workload across the three high-scale systems:

- **AMD EPYC 9R45 (Dual-socket NUMA, 192 cores):** Euhedral Core is **10.92x faster** than Reactor
  Parallel (82.2 ns vs 897.3 ns) and **12.97x faster** than Reactor BoundedElastic (1,066.1 ns).
  The result reflects the topology Euhedral was designed for: pinned, socket-local worker shards
  preserve ownership and execution locality, while Reactor's unpinned shared scheduling model pays
  severe cross-socket coordination and cache-locality costs.
- **Intel Xeon 6 (Single-socket, 96 physical cores / 192 vCPUs):** Euhedral Core is **1.10x faster**
  than Reactor Parallel (73.1 ns vs 80.6 ns) and **1.60x faster** than Reactor BoundedElastic (116.6
  ns).
- **AWS Graviton5 (Single-socket, 192 cores):** All three schedulers run with high efficiency within
  **0.94x – 1.07x** of each other (45.3 – 51.5 ns/op), with Graviton5 delivering the lowest
  execution latency and highest overall throughput.

These results reflect a CPU-heavy, fine-grained workload measuring scheduling, coordination, and
execution paths. The comparison also shows where topology-aware execution begins to matter: the
single-socket systems remain relatively close, while the dual-socket NUMA system exposes a large
difference between Euhedral's deliberate socket-local ownership model and Reactor's shared
scheduling model.

[Jump to methodology](#methodology) | [Detailed results](#detailed-results) | [Reproduce the benchmark](#reproducing-the-benchmark)

## Results at a glance

![Average time per operation across the three systems](../data/high_scale_mandelbrot_ns_op.png)

![Allocation rate across the three systems](../data/high_scale_mandelbrot_allocations.png)

Lower `ns/op` is better.

| Processor     | Instance           | Sockets | Physical Cores | Euhedral Core | Reactor Parallel | Reactor BoundedElastic |
|:--------------|:-------------------|--------:|---------------:|--------------:|-----------------:|-----------------------:|
| Intel Xeon 6  | AWS c8i.metal-48xl |       1 |             96 |  73.105 ns/op |     80.636 ns/op |          116.613 ns/op |
| AMD EPYC 9R45 | AWS c8a.metal-24xl |       2 |            192 |  82.175 ns/op |    897.263 ns/op |        1,066.103 ns/op |
| AWS Graviton5 | AWS c9g.metal-48xl |       1 |            192 |  48.132 ns/op |     51.507 ns/op |           45.339 ns/op |

Euhedral Core maintained a consistent 24.2 – 25.2 bytes per operation across all systems. Reactor
matched this on Graviton5 (24.0 – 24.5 bytes/op) and Intel (26.1 – 28.4 bytes/op), but increased to
64.0 bytes/op on AMD EPYC.

## What the benchmark does

The benchmark renders a randomized region of the Mandelbrot set at 8K resolution:

- 7,680 by 4,320 pixels (33,177,600 pre-allocated `MandelbrotPixel` work items)
- 2X SSAA (four measured subpixel evaluations per pixel, yielding 132,710,400 operations per
  invocation)
- Up to 5,000 iterations per subpixel sample, with a bailout radius squared of 1,000,000.0
- Randomized pixel order using a fixed seed (`HasherApi.BASE_SEED`) to eliminate spatial cache
  locality and create highly irregular execution times

All pixel work items and pipelines are constructed during trial setup so measurement focuses
strictly on scheduling, routing, and execution overhead rather than allocation.

The benchmark source is [
`MandelbrotBenchmark.java`](./src/main/java/io/euhedral_execution/benchmarks/core_benchmarks/MandelbrotBenchmark.java).

## Test systems

|                  | Intel system                  | AMD system         | Arm system         |
|------------------|-------------------------------|--------------------|--------------------|
| Instance         | AWS c8i.metal-48xl            | AWS c8a.metal-24xl | AWS c9g.metal-48xl |
| Operating system | Amazon Linux                  | Amazon Linux       | Amazon Linux       |
| Processor        | Intel Xeon 6 (Granite Rapids) | AMD EPYC 9R45      | AWS Graviton5      |
| Architecture     | x86_64                        | x86_64             | arm64              |
| vCPUs            | 192                           | 192                | 192                |
| Physical cores   | 96                            | 192                | 192                |
| Sockets          | 1                             | 2                  | 1                  |

The benchmark JVM used:

```text
-XX:+UseThreadPriorities
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
--add-exports java.base/jdk.internal.platform=ALL-UNNAMED
--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED
```

## Methodology

JMH runs the benchmark in average-time mode (`Mode.AverageTime`) and reports nanoseconds per
operation (`@OperationsPerInvocation(132_710_400)`). The benchmark configuration uses one 10-second
warmup iteration, one 40-second measurement iteration, and one fork. GC profiling supplies the
allocation and collection figures.

#### Euhedral Core pipeline

Euhedral partitions the 33,177,600 pre-allocated `MandelbrotPixel` items evenly across
`sourceCount = Runtime.getRuntime().availableProcessors()` (96 or 192) independent `Flux` streams,
one per CPU core. Each stream is subscribed to by a dedicated `EuhedralSubscriber` and added
upstream to the `ControlPlaneLattice`:

```java
for (int i = 0; i < this.sourceCount; i++) {
    this.sources[i].subscribe(this.subscribers[i]);
}
for (EuhedralSubscriber subscriber : this.subscribers) {
    this.controlPlane.addUpstream(subscriber);
}

waitOnRender(this.counters);
```

The `ControlPlaneLattice` coordinates work across its core-pinned shards until all 132,710,400
operations finish.

#### Reactor pipelines

Reactor constructs parallel pipelines during trial setup using `Flux.parallel(parallelism)` where
`parallelism` matches available processors:

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

During measurement, each invocation blocks on the pipeline and verifies completion against the
monotonic operation counter:

```java
this.parallelPipeline.block();
MandelbrotCompletion.verify(this.counters, EXPECTED_OPERATIONS);
```

#### Execution models

Both frameworks execute the identical 132,710,400 pre-allocated subpixel operations with concurrency
matching the host processor count:

- **Euhedral Core** deliberately assigns dedicated, core-pinned worker shards with topology-local
  ownership and private queue structures. On multi-socket systems, work remains partitioned by
  socket so normal execution does not depend on a global shared scheduler.
- **Reactor** schedules tasks across its parallel rails via `Schedulers.parallel()` or
  `Schedulers.boundedElastic()`. On the single-socket Intel and Graviton5 systems, this remains
  efficient and the schedulers stay relatively close. On the dual-socket AMD EPYC system, the
  combination of unpinned scheduling, cross-socket coordination, and degraded locality corresponds
  with the 10.92x-12.97x slowdown relative to Euhedral. The perf counters and the two execution
  models are consistent with NUMA coordination and locality being the dominant cause of that
  collapse.

### Known limitations

- The cloud environment did not expose every requested Linux perf event. Missing measurements are
  marked `N/A`.
- The Intel run has aggregate cycles, instructions, and cache misses, but no L1 or TLB breakdown.
- A single fork and measurement iteration make this a focused comparison rather than a broad
  statistical study.
- Results apply to these machines, JVM settings, library versions, and benchmark implementation.

## Detailed results

Lower time and bytes per operation are better. Allocation rate (MB/s) represents throughput
multiplied by per-operation allocation.

### Intel Xeon 6 (AWS c8i.metal-48xl)

- **Architecture:** x86_64 · 1 socket · 96 physical cores · 192 vCPUs

#### Performance, allocation, and GC

| Scheduler              |   ns/op | Relative | Alloc (MB/s) | Bytes/op | GC count | GC time (ms) |
|:-----------------------|--------:|---------:|-------------:|---------:|---------:|-------------:|
| Euhedral Core          |  73.105 |    1.00x |      323.626 |   25.230 |        3 |           41 |
| Reactor Parallel       |  80.636 |    1.10x |      308.329 |   26.071 |        1 |           13 |
| Reactor BoundedElastic | 116.613 |    1.60x |      232.379 |   28.415 |        2 |           12 |

#### CPU time and profiling

| Scheduler              | Wall clock (s) |   User (s) | System (s) |  IPC | L1 D-cache miss | L1 I-cache miss | dTLB miss | iTLB miss | Branch miss |
|:-----------------------|---------------:|-----------:|-----------:|-----:|----------------:|----------------:|----------:|----------:|------------:|
| Euhedral Core          |         74.988 | 13,287.247 |  1,196.694 | 1.76 |           0.14% |             N/A |     0.01% |       N/A |   0.010506% |
| Reactor Parallel       |         61.786 |  8,276.441 |    216.424 | 2.35 |           0.04% |             N/A |     0.00% |       N/A |   0.009193% |
| Reactor BoundedElastic |         72.122 |  7,769.529 |    220.613 | 2.64 |           0.04% |             N/A |     0.00% |       N/A |   0.008302% |

---

### AMD EPYC 9R45 (AWS c8a.metal-24xl)

- **Architecture:** x86_64 · 2 sockets · 192 physical cores · 192 vCPUs

#### Performance, allocation, and GC

| Scheduler              |     ns/op | Relative | Alloc (MB/s) | Bytes/op | GC count | GC time (ms) |
|:-----------------------|----------:|---------:|-------------:|---------:|---------:|-------------:|
| Euhedral Core          |    82.175 |    1.00x |      287.687 |   25.234 |        2 |           26 |
| Reactor Parallel       |   897.263 |   10.92x |       68.025 |   64.002 |        1 |            9 |
| Reactor BoundedElastic | 1,066.103 |   12.97x |       57.257 |   64.007 |        1 |            8 |

#### CPU time and profiling

| Scheduler              | Wall clock (s) |   User (s) | System (s) |  IPC | L1 D-cache miss | L1 I-cache miss | dTLB miss | iTLB miss | Branch miss |
|:-----------------------|---------------:|-----------:|-----------:|-----:|----------------:|----------------:|----------:|----------:|------------:|
| Euhedral Core          |         69.010 | 10,822.828 |  1,733.885 | 1.50 |           0.32% |          10.54% |    24.57% |     2.70% |   0.027304% |
| Reactor Parallel       |        236.524 |  2,228.218 |  1,849.992 | 2.58 |           0.37% |          11.05% |    10.06% |     9.18% |   0.032825% |
| Reactor BoundedElastic |        251.580 |  2,231.230 |  1,843.499 | 2.73 |           0.37% |          14.93% |    10.55% |     2.59% |   0.034119% |

---

### AWS Graviton5 (AWS c9g.metal-48xl)

- **Architecture:** arm64 · 1 socket · 192 physical cores · 192 vCPUs

#### Performance, allocation, and GC

| Scheduler              |  ns/op | Relative | Alloc (MB/s) | Bytes/op | GC count | GC time (ms) |
|:-----------------------|-------:|---------:|-------------:|---------:|---------:|-------------:|
| Euhedral Core          | 48.132 |    1.00x |      480.106 |   24.254 |        4 |           19 |
| Reactor Parallel       | 51.507 |    1.07x |      444.418 |   24.003 |        4 |           32 |
| Reactor BoundedElastic | 45.339 |    0.94x |      514.478 |   24.459 |        4 |           12 |

#### CPU time and profiling

| Scheduler              | Wall clock (s) |   User (s) | System (s) |  IPC | L1 D-cache miss | L1 I-cache miss | dTLB miss | iTLB miss | Branch miss |
|:-----------------------|---------------:|-----------:|-----------:|-----:|----------------:|----------------:|----------:|----------:|------------:|
| Euhedral Core          |         57.224 | 10,857.675 |    295.376 | 2.93 |           0.05% |           0.00% |     0.03% |     0.00% |   0.010169% |
| Reactor Parallel       |         53.359 |  9,285.544 |     38.338 | 3.22 |           0.02% |           0.00% |     0.01% |     0.00% |   0.008550% |
| Reactor BoundedElastic |         53.715 | 10,308.269 |     43.746 | 3.06 |           0.01% |           0.00% |     0.01% |     0.00% |   0.008106% |

---

### Raw hardware counters

<details>
<summary>View raw hardware counters across all systems</summary>

| Processor     | Scheduler              |             Cycles |        Instructions |   Cache misses |       Branch loads | Branch misses |
|:--------------|:-----------------------|-------------------:|--------------------:|---------------:|-------------------:|--------------:|
| Intel Xeon 6  | Euhedral Core          | 48,133,967,955,213 |  84,927,335,988,322 | 16,260,895,701 | 15,284,935,069,349 | 1,605,883,006 |
| Intel Xeon 6  | Reactor Parallel       | 26,406,857,367,000 |  62,141,649,319,708 |  5,120,097,377 | 11,401,867,160,225 | 1,048,138,834 |
| Intel Xeon 6  | Reactor BoundedElastic | 25,891,195,697,924 |  68,341,823,038,428 |  5,338,689,561 | 11,831,979,143,531 |   982,263,823 |
| AMD EPYC 9R45 | Euhedral Core          | 41,590,452,118,571 |  62,256,596,693,633 | 20,167,262,424 | 11,019,573,537,581 | 3,008,767,975 |
| AMD EPYC 9R45 | Reactor Parallel       |  8,862,488,297,968 |  22,895,118,648,542 |  3,235,380,322 |  4,228,909,143,961 | 1,388,122,070 |
| AMD EPYC 9R45 | Reactor BoundedElastic |  8,902,443,560,497 |  24,326,003,400,325 |  3,242,371,680 |  4,233,896,651,569 | 1,444,562,537 |
| AWS Graviton5 | Euhedral Core          | 34,710,581,589,533 | 101,569,077,357,680 |  6,054,639,234 | 19,495,383,907,321 | 1,982,442,395 |
| AWS Graviton5 | Reactor Parallel       | 29,314,957,885,380 |  94,477,495,609,726 |  2,348,678,771 | 16,693,651,630,229 | 1,427,352,342 |
| AWS Graviton5 | Reactor BoundedElastic | 32,797,099,852,975 | 100,520,568,212,065 |  1,681,392,658 | 18,880,118,433,728 | 1,530,363,103 |

</details>

## Reproducing the benchmark

Package the benchmark distribution from the repository root:

```bash
gradle :benchmarks:build -x test
```

Run the Mandelbrot comparison with GC profiling:

```bash
JAVA_TOOL_OPTIONS="-Dgc=true" \
    benchmarks/build/bin/euhedral-benchmarks mandelbrot
```

On Linux hosts that expose the required perf events, enable both profilers:

```bash
JAVA_TOOL_OPTIONS="-Dgc=true -Dperf=true" \
    benchmarks/build/bin/euhedral-benchmarks mandelbrot
```

See the [benchmark guide](./README.md) for packaging details, launcher options, and additional
workloads.
