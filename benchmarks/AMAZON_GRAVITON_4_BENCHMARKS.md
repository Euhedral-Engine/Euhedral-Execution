# Benchmarks in Amazon ECS using Graviton4

| Config           | Value           |
|:-----------------|:----------------|
| Instance         | AWS c8g.8xlarge |
| Operating System | Amazon Linux    |
| Processor        | AWS Graviton4   |
| Architecture     | arm64           |
| vCPUs            | 32              |

#### VM Flags

```
-XX:+UseThreadPriorities
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
--add-exports java.base/jdk.internal.platform=ALL-UNNAMED
--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
```

Work items were pre-allocated for all benchmarks to only measure scheduling overhead.

JMH was used for all benchmarking.

---

# TOC

<!-- TOC -->

* [Mandelbrot](#mandelbrot)
    * [Disclaimer](#disclaimer)
    * [Batched Mandelbrot](#batched-mandelbrot)
        * [Results](#results)
            * [Perf Counter Comparison](#perf-counter-comparison)
            * [Raw Hardware Counters](#raw-hardware-counters)
            * [CPU Time](#cpu-time)
    * [Mandelbrot (1-by-1)](#mandelbrot-1-by-1)
        * [Results](#results-1)
            * [Perf Counter Comparison](#perf-counter-comparison-1)
            * [Raw Hardware Counters](#raw-hardware-counters-1)
            * [CPU Time](#cpu-time-1)
* [Throughput](#throughput)
    * [Results](#results-2)
    * [Percentiles (ns/op)](#percentiles-nsop)
    * [Allocations](#allocations)
* [End-to-End Latency](#end-to-end-latency)
    * [Results](#results-3)
    * [Percentiles (ns/op)](#percentiles-nsop-1)
    * [Allocations](#allocations-1)
* [Raw Data](#raw-data)
    * [Batched Mandelbrot](#batched-mandelbrot-1)
        * [Results](#results-4)
        * [Euhedral Perf](#euhedral-perf)
        * [Reactor Parallel Perf](#reactor-parallel-perf)
        * [Reactor Bounded Elastic Perf](#reactor-bounded-elastic-perf)
    * [Mandelbrot (1-by-1)](#mandelbrot-1-by-1-1)
        * [Results](#results-5)
        * [Euhedral Perf](#euhedral-perf-1)
        * [Reactor Parallel Perf](#reactor-parallel-perf-1)
        * [Reactor Bounded Elastic Perf](#reactor-bounded-elastic-perf-1)
    * [Throughput](#throughput-2)
    * [End-to-End Latency](#end-to-end-latency-1)

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

## Batched Mandelbrot

Pixels are ingested in sub-arrays of 1024. This is to test execution efficiency. Because the pixel
order is randomized, the chunks of 1024 have relatively uniform execution time.

**Total operations: 132,710,400**

---

### Results

![](./data/ec2_batched_mandelbrot_ns_op.png)
![](./data/ec2_batched_mandelbrot_allocations.png)

| Scheduler              | ns/op | Alloc mb/sec | bytes/op | GC Counts | GC Time |
|:-----------------------|------:|-------------:|---------:|----------:|--------:|
| Euhedral Core          | 0.199 |        1.245 |   0.0001 |         0 |       0 |
| Reactor Parallel       | 5.609 |       10.587 |    0.062 |         0 |       0 |
| Reactor BoundedElastic | 7.946 |       12.615 |    0.105 |         0 |       0 |

---

#### Perf Counter Comparison

| Scheduler              | IPC  | L1 D-Cache Miss | L1 I-Cache Miss | dTLB Miss | iTLB Miss % | Branch Miss % |
|------------------------|------|----------------:|----------------:|----------:|------------:|--------------:|
| Euhedral Core          | 1.33 |           3.51% |           0.04% |     3.44% |       0.00% |     0.000270% |
| Reactor Parallel       | 1.07 |           7.43% |           2.59% |     8.62% |       0.15% |     0.001439% |
| Reactor BoundedElastic | 0.63 |           3.86% |           1.59% |     3.84% |       0.08% |     0.001355% |

---

#### Raw Hardware Counters

| Scheduler              |            Cycles |      Instructions |   Cache Misses |      Branch Loads | Branch Misses |
|------------------------|------------------:|------------------:|---------------:|------------------:|--------------:|
| Euhedral Core          | 4,397,227,356,868 | 5,864,940,785,961 | 67,077,127,814 | 1,069,951,802,365 |   288,935,709 |
| Reactor Parallel       |   205,203,955,734 |   173,574,529,223 |  1,984,863,493 |    29,168,881,158 |    43,344,042 |
| Reactor BoundedElastic |   264,271,565,652 |   166,384,961,017 |  1,846,065,066 |    33,920,710,517 |    45,957,753 |

---

#### CPU Time

| Runtime                | Wall Clock Runtime | User Seconds | System Time |
|------------------------|-------------------:|-------------:|------------:|
| Euhedral Core          |             50.241 |     1902.912 |      10.933 |
| Reactor Parallel       |             45.507 |      123.373 |      43.800 |
| Reactor BoundedElastic |             47.346 |      125.700 |      31.288 |

## Mandelbrot (1-by-1)

Pixels are ingested one at a time. This is to simulate a singular heavy stream of irregular work.

This benchmark intentionally destroys locality and creates highly irregular memory access and
execution behavior. It also causes massive contention because 1 source is being accessed by 32
cores.

**Total tasks: 132,710,400**

---

### Results

![](./data/ec2_1b1_mandelbrot_ns_op.png)
![](./data/ec2_1b1_mandelbrot_allocations.png)

| Scheduler              |   ns/op | Alloc mb/sec | bytes/op | GC Counts | GC Time |
|:-----------------------|--------:|-------------:|---------:|----------:|--------:|
| Euhedral Core          | 430.264 |       54.041 |   24.826 |         4 |      13 |
| Reactor Parallel       | 646.991 |      119.276 |   80.920 |         6 |      16 |
| Reactor BoundedElastic | 760.555 |      209.561 |  167.125 |         6 |      14 |

---

#### Perf Counter Comparison

| Scheduler              | IPC  | L1 D-Cache Miss | L1 I-Cache Miss | dTLB Miss | iTLB Miss % | Branch Miss % |
|------------------------|------|----------------:|----------------:|----------:|------------:|--------------:|
| Euhedral Core          | 2.58 |           0.15% |           0.01% |     0.13% |       0.00% |     0.000113% |
| Reactor Parallel       | 2.85 |           0.46% |           0.70% |     0.38% |       0.47% |     0.000323% |
| Reactor BoundedElastic | 2.73 |           0.51% |           1.05% |     0.58% |       0.69% |             % |

---

#### Raw Hardware Counters

| Scheduler              |            Cycles |       Instructions |   Cache Misses |      Branch Loads | Branch Misses |
|------------------------|------------------:|-------------------:|---------------:|------------------:|--------------:|
| Euhedral Core          | 9,878,695,478,235 | 25,444,053,658,043 |  5,760,229,105 | 4,836,077,553,756 |   545,069,336 |
| Reactor Parallel       | 8,110,063,337,883 | 23,112,737,580,904 | 15,325,535,712 | 4,503,866,854,931 | 1,453,853,760 |
| Reactor BoundedElastic | 8,391,004,487,094 | 22,890,240,697,167 | 17,652,619,823 | 4,651,555,201,097 | 1,788,615,702 |

---

#### CPU Time

| Runtime                | Wall Clock Runtime | User Seconds | System Time |
|------------------------|-------------------:|-------------:|------------:|
| Euhedral Core          |            112.477 |     3869.392 |       8.395 |
| Reactor Parallel       |            168.552 |     3132.563 |     760.575 |
| Reactor BoundedElastic |            203.305 |     3280.287 |    1236.926 |

---

# Throughput

32 million pre-allocated no-op frames per invocation utilizing all cores.

---

#### Results

| Scheduler     | ops/ns |    ops/sec | Avg ns/op |
|---------------|-------:|-----------:|----------:|
| Euhedral Core |  0.025 | 25,000,000 |    32.653 |

---

#### Percentiles (ns/op)

| Scheduler     | p0 | p50 | p90 |  p95 | p99 | p999 | p9999 | p100 |
|---------------|---:|----:|----:|-----:|----:|-----:|------:|-----:|
| Euhedral Core | 31 |  32 |  34 | 35.5 |  43 |   43 |    43 |   43 |

---

#### Allocations

| Scheduler     | Alloc mb/sec | bytes/op | GC Count | GC Time ms |
|---------------|-------------:|---------:|---------:|-----------:|
| Euhedral Core |       55.244 |    2.312 |        4 |          8 |

---

# End-to-End Latency

Each invocation executes **100K** pre-allocated no-op frames. This tests end-to-end latency using
only one core. Includes routing, scheduling, queue residency, and execution.

---

#### Results

| Scheduler     | Avg ns/op |
|---------------|----------:|
| Euhedral Core |   301.584 |

---

#### Percentiles (ns/op)

| Scheduler     |  p0 | p50 | p90 | p95 | p99 | p999 | p9999 | p100 |
|---------------|----:|----:|----:|----:|----:|-----:|------:|-----:|
| Euhedral Core | 282 | 309 | 312 | 313 | 315 |  324 |   378 |  378 |

---

#### Allocations

| Scheduler     | Alloc mb/sec | bytes/op | GC Count | GC Time ms |
|---------------|-------------:|---------:|---------:|-----------:|
| Euhedral Core |        1.234 |    0.411 |        0 |          0 |

---

# Raw Data

---

## Batched Mandelbrot

---

### Results

```
Benchmark                                                                                       Mode  Cnt   Score   Error   Units
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render                                            avgt        0.199           ns/op
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate                              avgt        1.245          MB/sec
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate.norm                         avgt       ≈ 10⁻⁴            B/op
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.count                                   avgt          ≈ 0          counts
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic                     avgt        7.946           ns/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate       avgt       12.615          MB/sec
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate.norm  avgt        0.105            B/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.count            avgt          ≈ 0          counts
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel                           avgt        5.609           ns/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate             avgt       10.587          MB/sec
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate.norm        avgt        0.062            B/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.count                  avgt          ≈ 0          counts
```

---

### Euhedral Perf

```
Perf stats:
--------------------------------------------------

     4397227356868      cycles                                                               (15.82%)
     5864940785961      instructions                     #    1.33  insn per cycle           (15.84%)
       67077127814      cache-misses                                                         (15.87%)
     1887783998290      L1-dcache-loads                                                      (10.58%)
       66702373401      L1-dcache-load-misses            #    3.52% of all L1-dcache accesses  (10.58%)
     1017921882762      L1-icache-loads                                                      (10.57%)
         420113241      L1-icache-load-misses            #    0.04% of all L1-icache accesses  (10.56%)
     1880866285049      dTLB-loads                                                           (10.56%)
       64672834210      dTLB-load-misses                 #    3.45% of all dTLB cache accesses  (10.56%)
     1069951802365      branch-loads                                                         (10.56%)
         288935709      branch-misses                                                        (10.55%)
     1899638238154      L1-dcache-loads                                                      (10.54%)
       66424417068      L1-dcache-load-misses            #    3.51% of all L1-dcache accesses  (10.55%)
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
     1020848938772      L1-icache-loads                                                      (10.54%)
         435923340      L1-icache-load-misses            #    0.04% of all L1-icache accesses  (10.54%)
     1864194331668      dTLB-loads                                                           (10.53%)
       64331605464      dTLB-load-misses                 #    3.44% of all dTLB cache accesses  (10.53%)
     1734706130844      iTLB-loads                                                           (10.53%)
          32052332      iTLB-load-misses                 #    0.00% of all iTLB cache accesses  (10.52%)

      50.241104857 seconds time elapsed

    1902.912551000 seconds user
      10.933059000 seconds sys
```

---

### Reactor Parallel Perf

```
Perf stats:
--------------------------------------------------

      205203955734      cycles                                                               (16.57%)
      173574529223      instructions                     #    0.85  insn per cycle           (16.07%)
        1984863493      cache-misses                                                         (15.76%)
       51085906785      L1-dcache-loads                                                      (10.76%)
        2670373554      L1-dcache-load-misses            #    5.22% of all L1-dcache accesses  (10.95%)
       51013090354      L1-icache-loads                                                      (10.74%)
         855491219      L1-icache-load-misses            #    1.67% of all L1-icache accesses  (10.70%)
       51194230559      dTLB-loads                                                           (10.71%)
        2627955283      dTLB-load-misses                 #    5.11% of all dTLB cache accesses  (10.73%)
       30121868177      branch-loads                                                         (10.76%)
          43344042      branch-misses                                                        (10.67%)
       51314449024      L1-dcache-loads                                                      (10.66%)
        2820107163      L1-dcache-load-misses            #    5.51% of all L1-dcache accesses  (10.72%)
       51316962197      L1-icache-loads                                                      (10.61%)
         882421093      L1-icache-load-misses            #    1.72% of all L1-icache accesses  (10.65%)
       51566332682      dTLB-loads                                                           (10.56%)
        2637160841      dTLB-load-misses                 #    5.13% of all dTLB cache accesses  (10.43%)
       72638063006      iTLB-loads                                                           (10.75%)
          72220756      iTLB-load-misses                 #    0.10% of all iTLB cache accesses  (11.05%)

      47.268725820 seconds time elapsed

     123.372548000 seconds user
      43.779824000 seconds sys
```

---

### Reactor Bounded Elastic Perf

```
Perf stats:
--------------------------------------------------

      264271565652      cycles                                                               (15.90%)
      166384961017      instructions                     #    0.63  insn per cycle           (16.01%)
        1846065066      cache-misses                                                         (16.22%)
       48541122567      L1-dcache-loads                                                      (10.85%)
        1713640530      L1-dcache-load-misses            #    3.53% of all L1-dcache accesses  (10.83%)
       49578415617      L1-icache-loads                                                      (10.75%)
         825192465      L1-icache-load-misses            #    1.68% of all L1-icache accesses  (10.77%)
       48199664492      dTLB-loads                                                           (10.87%)
        1855119785      dTLB-load-misses                 #    3.82% of all dTLB cache accesses  (10.55%)
       29168881158      branch-loads                                                         (10.35%)
          45957753      branch-misses                                                        (10.66%)
       48597299127      L1-dcache-loads                                                      (10.72%)
        1875068466      L1-dcache-load-misses            #    3.86% of all L1-dcache accesses  (10.60%)
       48870725182      L1-icache-loads                                                      (10.93%)
         782551364      L1-icache-load-misses            #    1.59% of all L1-icache accesses  (10.87%)
       48932897485      dTLB-loads                                                           (10.61%)
        1862913797      dTLB-load-misses                 #    3.84% of all dTLB cache accesses  (10.59%)
       72521741658      iTLB-loads                                                           (10.57%)
          59056513      iTLB-load-misses                 #    0.08% of all iTLB cache accesses  (10.51%)

      47.345972176 seconds time elapsed

     125.699922000 seconds user
      31.287601000 seconds sys
```

---

## Mandelbrot (1-by-1)

---

### Results

```
Benchmark                                                                                Mode  Cnt    Score   Error   Units
MandelbrotBenchmark.EuhedralMandelbrot.render                                            avgt       430.264           ns/op
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate                              avgt        54.041          MB/sec
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate.norm                         avgt        24.826            B/op
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.count                                   avgt         4.000          counts
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.time                                    avgt        13.000              ms
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic                     avgt       760.555           ns/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate       avgt       209.561          MB/sec
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate.norm  avgt       167.125            B/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.count            avgt         6.000          counts
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.time             avgt        14.000              ms
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel                           avgt       646.991           ns/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate             avgt       119.276          MB/sec
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate.norm        avgt        80.920            B/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.count                  avgt         6.000          counts
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.time                   avgt        16.000              ms
```

---

### Euhedral Perf

```
Perf stats:
--------------------------------------------------

     9878695478235      cycles                                                               (15.80%)
    25444053658043      instructions                     #    2.58  insn per cycle           (15.81%)
        5760229105      cache-misses                                                         (15.83%)
     3894578612998      L1-dcache-loads                                                      (10.55%)
        5804710146      L1-dcache-load-misses            #    0.15% of all L1-dcache accesses  (10.56%)
     3913433454304      L1-icache-loads                                                      (10.56%)
         270467878      L1-icache-load-misses            #    0.01% of all L1-icache accesses  (10.54%)
     3886460790109      dTLB-loads                                                           (10.54%)
        5179129736      dTLB-load-misses                 #    0.13% of all dTLB cache accesses  (10.54%)
     4836077553756      branch-loads                                                         (10.54%)
         545069336      branch-misses                                                        (10.54%)
     3887067710185      L1-dcache-loads                                                      (10.54%)
        5666582775      L1-dcache-load-misses            #    0.15% of all L1-dcache accesses  (10.54%)
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
     3910301148501      L1-icache-loads                                                      (10.54%)
         272440727      L1-icache-load-misses            #    0.01% of all L1-icache accesses  (10.53%)
     3888997344799      dTLB-loads                                                           (10.53%)
        5202796785      dTLB-load-misses                 #    0.13% of all dTLB cache accesses  (10.53%)
     1049705913928      iTLB-loads                                                           (10.53%)
          15420049      iTLB-load-misses                 #    0.00% of all iTLB cache accesses  (10.53%)
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

     112.477389811 seconds time elapsed

    3869.391618000 seconds user
       8.394914000 seconds sys
```

---

### Reactor Parallel Perf

```
Perf stats:
--------------------------------------------------

     8110063337883      cycles                                                               (15.83%)
    23112737580904      instructions                     #    2.85  insn per cycle           (15.86%)
       15325535712      cache-misses                                                         (15.86%)
     3340853027219      L1-dcache-loads                                                      (10.56%)
       15554178771      L1-dcache-load-misses            #    0.47% of all L1-dcache accesses  (10.51%)
     3638844721507      L1-icache-loads                                                      (10.55%)
       25360383139      L1-icache-load-misses            #    0.70% of all L1-icache accesses  (10.62%)
     3333974278336      dTLB-loads                                                           (10.57%)
       12615731690      dTLB-load-misses                 #    0.38% of all dTLB cache accesses  (10.54%)
     4503866854931      branch-loads                                                         (10.54%)
        1453853760      branch-misses                                                        (10.49%)
     3329838477855      L1-dcache-loads                                                      (10.50%)
       15481122803      L1-dcache-load-misses            #    0.46% of all L1-dcache accesses  (10.49%)
     3637033646519      L1-icache-loads                                                      (10.49%)
       25344444523      L1-icache-load-misses            #    0.70% of all L1-icache accesses  (10.53%)
     3325287304375      dTLB-loads                                                           (10.53%)
       12789374529      dTLB-load-misses                 #    0.38% of all dTLB cache accesses  (10.53%)
      492225786589      iTLB-loads                                                           (10.51%)
        2310273994      iTLB-load-misses                 #    0.47% of all iTLB cache accesses  (10.54%)

     168.552052545 seconds time elapsed

    3132.563318000 seconds user
     760.574950000 seconds sys
```

---

### Reactor Bounded Elastic Perf

```
Perf stats:
--------------------------------------------------

     8391004487094      cycles                                                               (15.84%)
    22890240697167      instructions                     #    2.73  insn per cycle           (15.83%)
       17652619823      cache-misses                                                         (15.81%)
     3521407008908      L1-dcache-loads                                                      (10.56%)
       17732455163      L1-dcache-load-misses            #    0.50% of all L1-dcache accesses  (10.57%)
     3730053358244      L1-icache-loads                                                      (10.53%)
       39062924188      L1-icache-load-misses            #    1.05% of all L1-icache accesses  (10.54%)
     3514560902228      dTLB-loads                                                           (10.57%)
       20077258768      dTLB-load-misses                 #    0.57% of all dTLB cache accesses  (10.57%)
     4651555201097      branch-loads                                                         (10.58%)
        1788615702      branch-misses                                                        (10.56%)
     3510368648826      L1-dcache-loads                                                      (10.51%)
       17860671061      L1-dcache-load-misses            #    0.51% of all L1-dcache accesses  (10.52%)
     3725351920201      L1-icache-loads                                                      (10.52%)
       39222614727      L1-icache-load-misses            #    1.05% of all L1-icache accesses  (10.50%)
     3509013701635      dTLB-loads                                                           (10.50%)
       20287555425      dTLB-load-misses                 #    0.58% of all dTLB cache accesses  (10.47%)
      661222753448      iTLB-loads                                                           (10.49%)
        4544464233      iTLB-load-misses                 #    0.69% of all iTLB cache accesses  (10.56%)

     203.305170978 seconds time elapsed

    3280.287469000 seconds user
    1236.925698000 seconds sys
```

---

## Throughput

Running on all cores

```
Benchmark                                                              Mode  Cnt   Score    Error   Units
TrueThroughputBenchmark.ingest32million32sources                      thrpt    5   0.025 ±  0.004  ops/ns
TrueThroughputBenchmark.ingest32million32sources:gc.alloc.rate        thrpt    5  55.244 ± 14.538  MB/sec
TrueThroughputBenchmark.ingest32million32sources:gc.alloc.rate.norm   thrpt    5   2.312 ±  0.973    B/op
TrueThroughputBenchmark.ingest32million32sources:gc.count             thrpt    5   4.000           counts
TrueThroughputBenchmark.ingest32million32sources:gc.time              thrpt    5   8.000               ms
TrueThroughputBenchmark.ingest32million32sources                     sample   49  32.653 ±  0.929   ns/op
TrueThroughputBenchmark.ingest32million32sources:gc.alloc.rate       sample    5  37.710 ± 11.092  MB/sec
TrueThroughputBenchmark.ingest32million32sources:gc.alloc.rate.norm  sample    5   1.317 ±  0.505    B/op
TrueThroughputBenchmark.ingest32million32sources:gc.count            sample    5   3.000           counts
TrueThroughputBenchmark.ingest32million32sources:gc.time             sample    5   9.000               ms
TrueThroughputBenchmark.ingest32million32sources:p0.00               sample       31.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.50               sample       32.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.90               sample       34.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.95               sample       35.500            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.99               sample       43.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.999              sample       43.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.9999             sample       43.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p1.00               sample       43.000            ns/op
```

```
Perf stats:
--------------------------------------------------

     4860023507098      cycles                                                               (15.80%)
     5529247429985      instructions                     #    1.14  insn per cycle           (15.83%)
       73726893375      cache-misses                                                         (15.85%)
     1922286844427      L1-dcache-loads                                                      (10.56%)
       73686182475      L1-dcache-load-misses            #    3.84% of all L1-dcache accesses  (10.56%)
      885686465228      L1-icache-loads                                                      (10.56%)
         179794964      L1-icache-load-misses            #    0.02% of all L1-icache accesses  (10.56%)
     1913194722665      dTLB-loads                                                           (10.55%)
      112934812503      dTLB-load-misses                 #    5.91% of all dTLB cache accesses  (10.56%)
      999112658656      branch-loads                                                         (10.56%)
         389666734      branch-misses                                                        (10.55%)
     1919394841012      L1-dcache-loads                                                      (10.55%)
       73632567590      L1-dcache-load-misses            #    3.83% of all L1-dcache accesses  (10.54%)
      875745886310      L1-icache-loads                                                      (10.54%)
         181762212      L1-icache-load-misses            #    0.02% of all L1-icache accesses  (10.54%)
     1905523454966      dTLB-loads                                                           (10.53%)
      114097506856      dTLB-load-misses                 #    5.98% of all dTLB cache accesses  (10.53%)
     1164695806959      iTLB-loads                                                           (10.53%)
          17873056      iTLB-load-misses                 #    0.00% of all iTLB cache accesses  (10.52%)

      55.112402283 seconds time elapsed

    2685.974259000 seconds user
       5.609988000 seconds sys
```

---

## End-to-End Latency

Running on 1 core

```
Benchmark                                                     Mode   Cnt    Score   Error   Units
EndToEndLatencyBenchmark.ECore.endToEnd                     sample  4758  301.584 ± 0.563   ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:gc.alloc.rate       sample    15    1.234 ± 0.007  MB/sec
EndToEndLatencyBenchmark.ECore.endToEnd:gc.alloc.rate.norm  sample    15    0.411 ± 0.016    B/op
EndToEndLatencyBenchmark.ECore.endToEnd:gc.count            sample    15      ≈ 0          counts
EndToEndLatencyBenchmark.ECore.endToEnd:p0.00               sample        282.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.50               sample        309.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.90               sample        312.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.95               sample        313.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.99               sample        315.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.999              sample        324.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.9999             sample        378.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p1.00               sample        378.000           ns/op
```

```
Perf stats:
--------------------------------------------------

      275621938430      cycles                                                               (15.88%)
      901714714659      instructions                     #    3.27  insn per cycle           (15.92%)
        1190284012      cache-misses                                                         (15.97%)
      184100078119      L1-dcache-loads                                                      (10.63%)
        1172170266      L1-dcache-load-misses            #    0.64% of all L1-dcache accesses  (10.59%)
      125511505082      L1-icache-loads                                                      (10.57%)
          26127669      L1-icache-load-misses            #    0.02% of all L1-icache accesses  (10.57%)
      184271752314      dTLB-loads                                                           (10.55%)
        8254853172      dTLB-load-misses                 #    4.48% of all dTLB cache accesses  (10.57%)
      143874463383      branch-loads                                                         (10.57%)
          74531240      branch-misses                                                        (10.56%)
      184568336811      L1-dcache-loads                                                      (10.54%)
        1169072211      L1-dcache-load-misses            #    0.63% of all L1-dcache accesses  (10.54%)
      125468209019      L1-icache-loads                                                      (10.53%)
          20292379      L1-icache-load-misses            #    0.02% of all L1-icache accesses  (10.54%)
      184049215737      dTLB-loads                                                           (10.52%)
        8314875487      dTLB-load-misses                 #    4.52% of all dTLB cache accesses  (10.50%)
       36023743828      iTLB-loads                                                           (10.51%)
           1549957      iTLB-load-misses                 #    0.00% of all iTLB cache accesses  (10.54%)

      49.704704123 seconds time elapsed

     131.599489000 seconds user
       0.723623000 seconds sys
```