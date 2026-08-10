# High-scale scheduler comparison

In the 8K Mandelbrot workload, **Reactor Parallel took 4.7x to 5.8x as much time per operation as
Euhedral Core**, while **Reactor BoundedElastic took 6.5x to 7.4x as much**, across the three tested
systems.

These are results for one CPU-heavy, fine-grained workload, not a claim that one scheduler will have
the same advantage everywhere. The comparison, scheduling setup, and known limitations are
documented below.

[Jump to methodology](#methodology) | [Detailed results](#detailed-results) |
[Reproduce the benchmark](#reproducing-the-benchmark)

## Results at a glance

Lower `ns/op` is better. The relative column divides each Reactor result by the Euhedral result on
the same machine.

| Processor     | Physical cores | Euhedral Core | Reactor Parallel | Relative | Reactor BoundedElastic | Relative |
|---------------|---------------:|--------------:|-----------------:|---------:|-----------------------:|---------:|
| Intel Xeon 6  |             96 |  82.838 ns/op |    401.638 ns/op |    4.85x |          600.406 ns/op |    7.25x |
| AMD EPYC 9R45 |             96 |  86.208 ns/op |    403.559 ns/op |    4.68x |          559.905 ns/op |    6.49x |
| AWS Graviton5 |            192 |  83.792 ns/op |    483.897 ns/op |    5.78x |          618.313 ns/op |    7.38x |

Euhedral also allocated about 24 bytes per operation on each system. Reactor Parallel allocated
81-90 bytes per operation, while Reactor BoundedElastic allocated 151-179 bytes per operation.

![Average time per operation across the three systems](../data/high_scale_mandelbrot_ns_op.png)

![Allocation rate across the three systems](../data/high_scale_mandelbrot_allocations.png)

## What the benchmark does

The benchmark renders a randomized region of the Mandelbrot set at 8K resolution:

- 7,680 by 4,320 pixels
- 33,177,600 pre-allocated work items
- four measured operations per work item, or 132,710,400 operations per invocation
- up to 5,000 iterations per pixel
- randomized pixel order to create an irregular CPU workload

All work items are created before measurement so the results focus on scheduling, routing, and
execution overhead. Reactor's `Mono` tasks are also pre-allocated.

The benchmark source is
[
`MandelbrotBenchmark.java`](./src/main/java/io/euhedral_execution/benchmarks/core_benchmarks/MandelbrotBenchmark.java).

## Test systems

|                  | Intel system                  | AMD system         | Arm system         |
|------------------|-------------------------------|--------------------|--------------------|
| Instance         | AWS c8i.metal-48xl            | AWS c8a.metal-24xl | AWS c9g.metal-48xl |
| Operating system | Amazon Linux                  | Amazon Linux       | Amazon Linux       |
| Processor        | Intel Xeon 6 (Granite Rapids) | AMD EPYC 9R45      | AWS Graviton5      |
| Architecture     | x86_64                        | x86_64             | arm64              |
| vCPUs            | 192                           | 96                 | 192                |
| Physical cores   | 96                            | 96                 | 192                |

The benchmark JVM used:

```text
-XX:+UseThreadPriorities
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
--add-exports java.base/jdk.internal.platform=ALL-UNNAMED
--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED
```

## Methodology

JMH runs the benchmark in average-time mode and reports nanoseconds per operation. The benchmark
configuration uses one 10-second warmup iteration, one 40-second measurement iteration, and one
fork. GC profiling supplies the allocation and collection figures.

Euhedral receives the pre-allocated frames through a subscribed `EuhedralSubscriber`, then adds that
source to the control plane:

```java
Flux.fromArray(pixels).subscribe(subscriber);
controlPlane.addUpstream(subscriber);
```

A direct Reactor pipeline using `parallel().runOn(...)` did not fully use the available cores when
fed in the same shape. It also introduced more allocation and produced results above two
microseconds per operation in preliminary runs.

For the recorded comparison, each pre-allocated Reactor task is scheduled independently and the
`flatMap` concurrency matches the JVM's available processor count:

```java
Flux.fromArray(monos)
        .flatMap(
                task -> task.subscribeOn(Schedulers.parallel()),
                Runtime.getRuntime().availableProcessors())
        .subscribe();
```

The BoundedElastic measurement uses the same structure with `Schedulers.boundedElastic()`.

This makes all cores available to the Reactor workloads, but it does not make the execution models
identical. Euhedral routes reusable frames through persistent pinned workers. Reactor schedules
pre-created `Mono` tasks through its native scheduler. The results therefore compare the complete
scheduling paths needed by this workload, not isolated queue primitives.

### Known limitations

- The cloud environment did not expose every requested Linux perf event. Missing measurements are
  marked `N/A`.
- The Intel run has aggregate cycles, instructions, and cache misses, but no L1 or TLB breakdown.
- A single fork and measurement iteration make this a focused comparison rather than a broad
  statistical study.
- Results apply to these machines, JVM settings, library versions, and benchmark implementation.

## Detailed results

### Time, allocation, and GC

Lower time and bytes per operation are better. Allocation rate is workload throughput multiplied by
allocation per operation, so a faster implementation can show a high MB/s rate while allocating
fewer bytes for each operation.

| Processor     | Scheduler              |   ns/op | Allocation (MB/s) | Bytes/op | GC count | GC time (ms) |
|---------------|------------------------|--------:|------------------:|---------:|---------:|-------------:|
| Intel Xeon 6  | Euhedral Core          |  82.838 |           270.985 |   24.049 |        1 |           24 |
| Intel Xeon 6  | Reactor Parallel       | 401.638 |           192.680 |   81.148 |        5 |           42 |
| Intel Xeon 6  | Reactor BoundedElastic | 600.406 |           284.855 |  179.338 |        5 |           55 |
| AMD EPYC 9R45 | Euhedral Core          |  86.208 |           261.732 |   24.030 |        2 |           49 |
| AMD EPYC 9R45 | Reactor Parallel       | 403.559 |           212.683 |   90.000 |       10 |           20 |
| AMD EPYC 9R45 | Reactor BoundedElastic | 559.905 |           262.778 |  154.279 |        5 |            8 |
| AWS Graviton5 | Euhedral Core          |  83.792 |           268.057 |   24.057 |       14 |           64 |
| AWS Graviton5 | Reactor Parallel       | 483.897 |           177.372 |   90.000 |        5 |           31 |
| AWS Graviton5 | Reactor BoundedElastic | 618.313 |           233.101 |  151.132 |        8 |           59 |

### CPU time

Times are in seconds.

| Processor     | Scheduler              | Wall clock |      User |  System |
|---------------|------------------------|-----------:|----------:|--------:|
| Intel Xeon 6  | Euhedral Core          |     57.394 | 5,579.035 | 141.775 |
| Intel Xeon 6  | Reactor Parallel       |    104.231 | 2,920.635 | 644.114 |
| Intel Xeon 6  | Reactor BoundedElastic |    158.336 | 3,228.513 | 380.155 |
| AMD EPYC 9R45 | Euhedral Core          |     53.618 | 5,606.848 |  23.558 |
| AMD EPYC 9R45 | Reactor Parallel       |     95.132 | 2,191.680 | 318.022 |
| AMD EPYC 9R45 | Reactor BoundedElastic |    140.763 | 2,364.622 | 289.495 |
| AWS Graviton5 | Euhedral Core          |     58.610 | 8,316.135 | 815.434 |
| AWS Graviton5 | Reactor Parallel       |    112.914 | 2,753.357 | 291.640 |
| AWS Graviton5 | Reactor BoundedElastic |    152.547 | 3,015.122 | 309.997 |

### Hardware-counter summary

`N/A` indicates that the host did not expose the event.

| Processor     | Scheduler              |  IPC | L1 D-cache miss | L1 I-cache miss | dTLB miss | iTLB miss | Branch miss |
|---------------|------------------------|-----:|----------------:|----------------:|----------:|----------:|------------:|
| Intel Xeon 6  | Euhedral Core          | 2.93 |             N/A |             N/A |       N/A |       N/A |         N/A |
| Intel Xeon 6  | Reactor Parallel       | 2.13 |             N/A |             N/A |       N/A |       N/A |         N/A |
| Intel Xeon 6  | Reactor BoundedElastic | 2.25 |             N/A |             N/A |       N/A |       N/A |         N/A |
| AMD EPYC 9R45 | Euhedral Core          | 2.89 |           0.05% |           6.87% |    57.08% |     3.56% |   0.000101% |
| AMD EPYC 9R45 | Reactor Parallel       | 2.44 |           0.41% |          12.95% |    19.72% |     1.16% |   0.000567% |
| AMD EPYC 9R45 | Reactor BoundedElastic | 2.33 |           0.48% |           7.70% |    11.67% |     0.04% |   0.000588% |
| AWS Graviton5 | Euhedral Core          | 2.43 |           0.75% |           0.18% |     0.47% |     0.02% |   0.000304% |
| AWS Graviton5 | Reactor Parallel       | 2.68 |           0.42% |           0.38% |     0.26% |     0.05% |   0.000553% |
| AWS Graviton5 | Reactor BoundedElastic | 2.44 |           0.42% |           0.56% |     0.29% |     0.07% |   0.000323% |

<details>
<summary>Raw hardware counters</summary>

| Processor     | Scheduler              |             Cycles |       Instructions |   Cache misses |       Branch loads | Branch misses |
|---------------|------------------------|-------------------:|-------------------:|---------------:|-------------------:|--------------:|
| Intel Xeon 6  | Euhedral Core          | 20,625,266,011,737 | 60,353,132,304,660 |  4,148,144,539 |                N/A |   823,916,196 |
| Intel Xeon 6  | Reactor Parallel       | 11,326,830,843,340 | 24,128,366,443,003 | 30,766,280,575 |                N/A | 6,140,131,887 |
| Intel Xeon 6  | Reactor BoundedElastic | 11,087,824,431,848 | 24,945,324,094,678 | 13,451,026,332 |                N/A | 1,847,667,259 |
| AMD EPYC 9R45 | Euhedral Core          | 18,829,091,755,720 | 54,343,556,258,338 |  2,600,888,370 |  9,984,062,395,403 | 1,012,646,708 |
| AMD EPYC 9R45 | Reactor Parallel       |  9,484,597,705,145 | 23,109,727,897,133 |  7,753,919,138 |  4,257,709,312,958 | 2,425,579,777 |
| AMD EPYC 9R45 | Reactor BoundedElastic | 10,302,281,428,853 | 23,956,975,516,816 |  8,230,497,977 |  4,408,035,293,741 | 2,590,251,618 |
| AWS Graviton5 | Euhedral Core          | 27,429,847,872,535 | 66,649,183,214,364 | 81,970,475,585 | 12,648,339,885,278 | 3,840,342,402 |
| AWS Graviton5 | Reactor Parallel       |  8,518,881,468,865 | 22,840,422,013,665 | 13,402,537,594 |  4,282,035,677,859 | 2,369,065,003 |
| AWS Graviton5 | Reactor BoundedElastic |  9,669,934,328,418 | 23,571,013,534,443 | 14,458,504,329 |  4,433,174,053,585 | 1,430,985,887 |

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
