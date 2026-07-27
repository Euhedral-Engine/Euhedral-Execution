# Benchmarks in Amazon ECS using Graviton4

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
    * [Disclaimer](#disclaimer)
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
* [Throughput](#throughput)
    * [Results](#results-2)
    * [Latency Percentiles (ns/op)](#latency-percentiles-nsop)
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

- 2X SSAA
- An iteration cap of 5,000 per pixel
- Randomized pixel ordering

Total operations: 132,710,400

---

### Disclaimer

Feeding the Reactor schedulers the exact same way as Euhedral does not make them fully utilize the
available cores automatically.

Ideally, they would be fed like this:

```java
Flux.fromArray(this.pixels)
    .parallel()
    .runOn(Schedulers.parallel())
    .subscribe(this.subscriber);

Flux.fromArray(this.pixels)
    .parallel()
    .runOn(Schedulers.boundedElastic())
    .subscribe(this.subscriber);

// Euhedral Core
Flux.fromArray(this.pixels).subscribe(this.subscriber);
this.controlPlane.ingest(this.subscriber);
```

Using `.parallel()` lead to higher allocations, significantly lower throughput, and latencies
exceeding 2 microseconds per operation.

To have a fairer comparison, Reactor needs to be forced into using all cores with flatMap and their
native tasking constructs (Mono). To avoid extra allocations for Reactor, the Mono objects were also
pre-allocated.

How Reactor was fed the tasks:

```java
Flux.fromArray(this.monos)
    .flatMap(m ->m.subscribeOn(Schedulers.parallel()), Runtime.getRuntime().availableProcessors())
    .subscribe(this.subscriber);
```

---

## Mandelbrot (1-by-1)

Pixels are ingested one at a time. This is to simulate a singular heavy stream of irregular work.

This benchmark intentionally destroys locality and creates highly irregular memory access and
execution behavior. It also causes massive contention because 1 source is being accessed by 32
cores.

**Total tasks: 132,710,400**

---

### Results

![](../data/ec2_1b1_mandelbrot_ns_op.png)
![](../data/ec2_1b1_mandelbrot_allocations.png)

| Scheduler              |   ns/op | Alloc mb/sec | bytes/op | GC Counts | GC Time |
|:-----------------------|--------:|-------------:|---------:|----------:|--------:|
| Euhedral Core          | 283.812 |       77.656 |   24.035 |         5 |      19 |
| Reactor Parallel       | 664.955 |      124.799 |   87.017 |         6 |      14 |
| Reactor BoundedElastic | 868.591 |      194.548 |  177.191 |         9 |      18 |

---

#### Perf Counter Comparison

| Scheduler              | IPC  | L1 D-Cache Miss | L1 I-Cache Miss | dTLB Miss | iTLB Miss % | Branch Miss % |
|------------------------|------|----------------:|----------------:|----------:|------------:|--------------:|
| Euhedral Core          | 2.92 |           0.05% |           0.02% |     0.03% |       0.00% |     0.000102% |
| Reactor Parallel       | 2.82 |           0.47% |           0.52% |     0.24% |       0.06% |     0.000291% |
| Reactor BoundedElastic | 2.79 |           0.63% |           0.72% |     0.33% |       0.10% |     0.000436% |

---

#### Raw Hardware Counters

| Scheduler              |             Cycles |       Instructions |   Cache Misses |      Branch Loads | Branch Misses |
|------------------------|-------------------:|-------------------:|---------------:|------------------:|--------------:|
| Euhedral Core          | 11,130,922,893,025 | 32,479,411,801,407 |  2,225,993,935 | 6,236,768,652,083 |   633,301,423 |
| Reactor Parallel       |  8,011,251,233,288 | 22,614,170,852,422 | 14,993,849,584 | 4,407,051,354,077 | 1,283,987,696 |
| Reactor BoundedElastic |  7,959,340,583,294 | 22,176,155,589,759 | 21,270,349,800 | 4,556,484,617,144 | 1,987,694,382 |

---

#### CPU Time

| Runtime                | Wall Clock Runtime | User Seconds | System Time |
|------------------------|-------------------:|-------------:|------------:|
| Euhedral Core          |            110.690 |         3630 |          75 |
| Reactor Parallel       |            168.379 |         2726 |        1423 |
| Reactor BoundedElastic |            219.246 |         2860 |        2609 |

---

## Batched Mandelbrot

Pixels are ingested in sub-arrays of 1024. This significantly reduces the number of work items while
increasing the density of them. This is to test execution efficiency and the ability to fan work
out. Because the pixel order is randomized, the chunks of 1024 have relatively uniform execution
time.

**Work Items: 32,400**

**Pixels: 33,177,600**

**Total operations: 132,710,400**

---

### Results

![](../data/ec2_batched_mandelbrot_ns_op.png)
![](../data/ec2_batched_mandelbrot_allocations.png)

| Scheduler              |    ns/op | Alloc mb/sec | bytes/op | GC Counts | GC Time |
|:-----------------------|---------:|-------------:|---------:|----------:|--------:|
| Euhedral Core          |  333.278 |       68.595 |   24.021 |         1 |       4 |
| Reactor Parallel       | 2260.563 |        5.308 |   12.582 |         4 |      18 |
| Reactor BoundedElastic | 2260.563 |        5.334 |   12.644 |         3 |       7 |

---

#### Perf Counter Comparison

| Scheduler              | IPC  | L1 D-Cache Miss | L1 I-Cache Miss | dTLB Miss | iTLB Miss % | Branch Miss % |
|------------------------|------|----------------:|----------------:|----------:|------------:|--------------:|
| Euhedral Core          | 2.91 |           0.05% |           0.10% |     0.03% |       0.01% |     0.000115% |
| Reactor Parallel       | 2.29 |           0.03% |           0.00% |     0.01% |       0.00% |     0.000296% |
| Reactor BoundedElastic | 2.30 |           0.03% |           0.01% |     0.01% |       0.00% |     0.000299% |

---

#### Raw Hardware Counters

| Scheduler              |            Cycles |       Instructions |  Cache Misses |      Branch Loads | Branch Misses |
|------------------------|------------------:|-------------------:|--------------:|------------------:|--------------:|
| Euhedral Core          | 7,107,328,150,610 | 20,710,163,173,819 | 1,458,884,193 | 3,970,345,393,377 |   457,737,387 |
| Reactor Parallel       | 4,877,537,924,722 | 11,189,795,111,439 |   622,401,498 | 2,030,715,134,267 |   600,430,908 |
| Reactor BoundedElastic | 4,889,719,144,445 | 11,243,845,827,464 |   596,484,125 | 2,034,718,702,391 |   607,760,308 |

---

#### CPU Time

| Runtime                | Wall Clock Runtime | User Seconds | System Time |
|------------------------|-------------------:|-------------:|------------:|
| Euhedral Core          |                 82 |         2425 |         266 |
| Reactor Parallel       |                593 |         1507 |          21 |
| Reactor BoundedElastic |                593 |         1506 |          23 |

---

# Throughput

32 million pre-allocated no-op frames per invocation utilizing all cores.

---

#### Results

| Scheduler     | ops/ns |     ops/sec | Avg ns/op |
|---------------|-------:|------------:|----------:|
| Euhedral Core |  0.140 | 140,000,000 |     7.009 |

---

#### Latency Percentiles (ns/op)

These are the average amortized latencies.

| Scheduler     | p0 | p50 | p90 | p95 |  p99 | p999 | p9999 | p100 |
|---------------|---:|----:|----:|----:|-----:|-----:|------:|-----:|
| Euhedral Core |  7 |   7 |   7 |   7 | 7.89 |    8 |     8 |    8 |

---

#### Allocations

| Scheduler     | Alloc mb/sec | bytes/op | GC Count | GC Time ms |
|---------------|-------------:|---------:|---------:|-----------:|
| Euhedral Core |        0.197 |    0.001 |        0 |          0 |

---

# End-to-End Latency

Each invocation executes **100K** pre-allocated no-op frames. This tests end-to-end latency using
only one core. Includes routing, scheduling, queue residency, and execution.

---

#### Results

| Scheduler     | Avg ns/op |
|---------------|----------:|
| Euhedral Core |   105.774 |

---

#### Percentiles (ns/op)

| Scheduler     | p0 | p50 | p90 | p95 | p99 | p999 | p9999 | p100 |
|---------------|---:|----:|----:|----:|----:|-----:|------:|-----:|
| Euhedral Core | 67 | 106 | 133 | 134 | 137 |  137 |   145 |  145 |

---

#### Allocations

| Scheduler     | Alloc mb/sec | bytes/op | GC Count | GC Time ms |
|---------------|-------------:|---------:|---------:|-----------:|
| Euhedral Core |        0.041 |    0.005 |        0 |          0 |
