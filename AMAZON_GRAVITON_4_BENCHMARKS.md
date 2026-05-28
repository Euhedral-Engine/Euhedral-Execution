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
| Euhedral Core          | 0.231 |        1.332 |    0.001 |         0 |       0 |
| Reactor Parallel       | 3.187 |       16.878 |    0.056 |         1 |      38 |
| Reactor BoundedElastic | 3.181 |       31.507 |    0.105 |         1 |      36 |

---

#### Perf Counter Comparison

| Scheduler              | IPC  | L1 D-Cache Miss | L1 I-Cache Miss | dTLB Miss | iTLB Miss % | Branch Miss % |
|------------------------|------|----------------:|----------------:|----------:|------------:|--------------:|
| Euhedral Core          | 1.42 |           3.06% |           0.03% |     2.61% |       0.00% |     0.000236% |
| Reactor Parallel       | 1.07 |           7.43% |           2.59% |     8.62% |       0.15% |     0.001489% |
| Reactor BoundedElastic | 0.80 |           6.41% |           3.59% |     7.74% |       0.21% |     0.001841% |

---

#### Raw Hardware Counters

| Scheduler              |            Cycles |      Instructions |   Cache Misses |      Branch Loads | Branch Misses |
|------------------------|------------------:|------------------:|---------------:|------------------:|--------------:|
| Euhedral Core          | 4,239,472,949,792 | 6,007,432,848,923 | 59,332,112,835 | 1,096,525,214,106 |   258,691,194 |
| Reactor Parallel       |   184,232,694,519 |   196,944,471,945 |  3,070,893,184 |    34,567,157,069 |    51,463,889 |
| Reactor BoundedElastic |   248,944,553,606 |   200,387,522,676 |  4,249,305,007 |    33,920,710,517 |    62,445,244 |

---

#### CPU Time

| Runtime                | Wall Clock Runtime | User Seconds | System Time |
|------------------------|-------------------:|-------------:|------------:|
| Euhedral Core          |             48.452 |     1849.738 |       9.853 |
| Reactor Parallel       |             45.507 |      120.915 |      71.596 |
| Reactor BoundedElastic |             45.606 |      122.411 |      70.371 |

## Mandelbrot (1-by-1)

Pixels are ingested one at a time. This is to simulate a singular heavy stream of irregular work.

This benchmark intentionally destroys locality and creates highly irregular memory access and
execution behavior.

**Total tasks: 132,710,400**

---

### Results

![](./data/ec2_1b1_mandelbrot_ns_op.png)
![](./data/ec2_1b1_mandelbrot_allocations.png)

| Scheduler              |   ns/op | Alloc mb/sec | bytes/op | GC Counts | GC Time |
|:-----------------------|--------:|-------------:|---------:|----------:|--------:|
| Euhedral Core          | 413.044 |       56.254 |   24.825 |         2 |       9 |
| Reactor Parallel       | 638.708 |      129.743 |   86.894 |         6 |      14 |
| Reactor BoundedElastic | 746.895 |      206.021 |  161.352 |         5 |      10 |

---

#### Perf Counter Comparison

| Scheduler              | IPC  | L1 D-Cache Miss | L1 I-Cache Miss | dTLB Miss | iTLB Miss % | Branch Miss % |
|------------------------|------|----------------:|----------------:|----------:|------------:|--------------:|
| Euhedral Core          | 2.84 |           0.08% |           0.01% |     0.12% |       0.00% |     0.000116% |
| Reactor Parallel       | 2.81 |           0.43% |           0.70% |     0.39% |       0.53% |     0.000304% |
| Reactor BoundedElastic | 2.74 |           0.51% |           1.02% |     0.55% |       0.75% |     0.000384% |

---

#### Raw Hardware Counters

| Scheduler              |            Cycles |       Instructions |   Cache Misses |      Branch Loads | Branch Misses |
|------------------------|------------------:|-------------------:|---------------:|------------------:|--------------:|
| Euhedral Core          | 9,006,559,365,383 | 25,553,507,944,679 |  3,544,026,494 | 4,843,108,949,083 |   559,456,815 |
| Reactor Parallel       | 8,015,091,312,733 | 22,510,415,351,083 | 13,875,033,709 | 4,477,034,119,392 | 1,362,319,191 |
| Reactor BoundedElastic | 8,265,301,063,398 | 22,682,721,999,766 | 17,483,697,023 | 4,613,210,870,784 | 1,769,426,429 |

---

#### CPU Time

| Runtime                | Wall Clock Runtime | User Seconds | System Time |
|------------------------|-------------------:|-------------:|------------:|
| Euhedral Core          |            102.858 |     3562.058 |       8.122 |
| Reactor Parallel       |            165.602 |     3105.775 |     749.313 |
| Reactor BoundedElastic |            193.875 |     3248.631 |    1221.526 |

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
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render                                            avgt        0.231           ns/op
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate                              avgt        1.332          MB/sec
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate.norm                         avgt       ≈ 10⁻³            B/op
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.count                                   avgt          ≈ 0          counts
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic                     avgt        3.181           ns/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate       avgt       31.507          MB/sec
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate.norm  avgt        0.105            B/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.count            avgt        1.000          counts
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.time             avgt       36.000              ms
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel                           avgt        3.187           ns/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate             avgt       16.878          MB/sec
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate.norm        avgt        0.056            B/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.count                  avgt        1.000          counts
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.time                   avgt       38.000              ms
```

---

### Euhedral Perf

```
Perf stats:
--------------------------------------------------

     4239472949792      cycles                                                               (15.81%)
     6007432848923      instructions                     #    1.42  insn per cycle           (15.82%)
       59332112835      cache-misses                                                         (15.85%)
     1910196690174      L1-dcache-loads                                                      (10.57%)
       58395287107      L1-dcache-load-misses            #    3.04% of all L1-dcache accesses  (10.56%)
     1041828109715      L1-icache-loads                                                      (10.56%)
         333681934      L1-icache-load-misses            #    0.03% of all L1-icache accesses  (10.57%)
     1938995674502      dTLB-loads                                                           (10.56%)
       50840106698      dTLB-load-misses                 #    2.62% of all dTLB cache accesses  (10.55%)
     1096525214106      branch-loads                                                         (10.55%)
         258691194      branch-misses                                                        (10.54%)
     1927497594646      L1-dcache-loads                                                      (10.55%)
       58811621135      L1-dcache-load-misses            #    3.06% of all L1-dcache accesses  (10.55%)
     1028689105045      L1-icache-loads                                                      (10.54%)
         331017363      L1-icache-load-misses            #    0.03% of all L1-icache accesses  (10.54%)
     1948546126621      dTLB-loads                                                           (10.53%)
       50640717859      dTLB-load-misses                 #    2.61% of all dTLB cache accesses  (10.52%)
     1762411810135      iTLB-loads                                                           (10.52%)
          23391773      iTLB-load-misses                 #    0.00% of all iTLB cache accesses  (10.52%)

      48.452466814 seconds time elapsed

    1849.738298000 seconds user
       9.853565000 seconds sys
```

---

### Reactor Parallel Perf

```
Perf stats:
--------------------------------------------------

      184232694519      cycles                                                               (17.21%)
      196944471945      instructions                     #    1.07  insn per cycle           (16.13%)
        3070893184      cache-misses                                                         (16.04%)
       68668157834      L1-dcache-loads                                                      (10.88%)
        4427107351      L1-dcache-load-misses            #    7.00% of all L1-dcache accesses  (10.76%)
       54850634097      L1-icache-loads                                                      (10.53%)
        1585451138      L1-icache-load-misses            #    2.89% of all L1-icache accesses  (10.71%)
       57560206580      dTLB-loads                                                           (10.75%)
        4767294650      dTLB-load-misses                 #    8.21% of all dTLB cache accesses  (10.72%)
       34567157069      branch-loads                                                         (10.83%)
          51463889      branch-misses                                                        (10.68%)
       57798358566      L1-dcache-loads                                                      (10.68%)
        4696223104      L1-dcache-load-misses            #    7.43% of all L1-dcache accesses  (10.80%)
       54797175178      L1-icache-loads                                                      (10.45%)
        1419352715      L1-icache-load-misses            #    2.59% of all L1-icache accesses  (10.08%)
       58575201231      dTLB-loads                                                           (10.44%)
        5003239248      dTLB-load-misses                 #    8.62% of all dTLB cache accesses  (10.70%)
       71710572859      iTLB-loads                                                           (11.43%)
         106423240      iTLB-load-misses                 #    0.15% of all iTLB cache accesses  (11.66%)

      45.507859325 seconds time elapsed

     120.915798000 seconds user
      71.596455000 seconds sys
```

---

### Reactor Bounded Elastic Perf

```
Perf stats:
--------------------------------------------------

      248944553606      cycles                                                               (17.07%)
      200387522676      instructions                     #    0.80  insn per cycle           (17.38%)
        4249305007      cache-misses                                                         (17.39%)
       67880226762      L1-dcache-loads                                                      (11.01%)
        4184252069      L1-dcache-load-misses            #    6.77% of all L1-dcache accesses  (10.92%)
       53326433167      L1-icache-loads                                                      (10.73%)
        1747648778      L1-icache-load-misses            #    3.32% of all L1-icache accesses  (10.97%)
       56472511472      dTLB-loads                                                           (10.99%)
        4192116202      dTLB-load-misses                 #    7.36% of all dTLB cache accesses  (10.74%)
       33920710517      branch-loads                                                         (10.93%)
          62445244      branch-misses                                                        (11.36%)
       55757305743      L1-dcache-loads                                                      (11.30%)
        3964317345      L1-dcache-load-misses            #    6.41% of all L1-dcache accesses  (11.43%)
       51836099457      L1-icache-loads                                                      (11.35%)
        1886616883      L1-icache-load-misses            #    3.59% of all L1-icache accesses  (11.17%)
       57379323945      dTLB-loads                                                           (10.94%)
        4404538457      dTLB-load-misses                 #    7.74% of all dTLB cache accesses  (10.85%)
       76006376931      iTLB-loads                                                           (10.91%)
         156965592      iTLB-load-misses                 #    0.21% of all iTLB cache accesses  (11.10%)

      45.606131316 seconds time elapsed

     122.411435000 seconds user
      70.370634000 seconds sys
```

---

## Mandelbrot (1-by-1)

---

### Results

```
Benchmark                                                                                Mode  Cnt    Score   Error   Units
MandelbrotBenchmark.EuhedralMandelbrot.render                                            avgt       413.044           ns/op
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate                              avgt        56.254          MB/sec
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate.norm                         avgt        24.825            B/op
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.count                                   avgt         2.000          counts
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.time                                    avgt         9.000              ms
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic                     avgt       746.895           ns/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate       avgt       206.021          MB/sec
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate.norm  avgt       161.352            B/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.count            avgt         5.000          counts
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.time             avgt        10.000              ms
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel                           avgt       638.708           ns/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate             avgt       129.743          MB/sec
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate.norm        avgt        86.894            B/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.count                  avgt         6.000          counts
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.time                   avgt        14.000              ms
```

---

### Euhedral Perf

```
Perf stats:
--------------------------------------------------

     9006559365383      cycles                                                               (15.80%)
    25553507944679      instructions                     #    2.84  insn per cycle           (15.82%)
        3544026494      cache-misses                                                         (15.84%)
     4086727594821      L1-dcache-loads                                                      (10.56%)
        3430030226      L1-dcache-load-misses            #    0.08% of all L1-dcache accesses  (10.56%)
     3926227484081      L1-icache-loads                                                      (10.56%)
         316459234      L1-icache-load-misses            #    0.01% of all L1-icache accesses  (10.56%)
     4093537819984      dTLB-loads                                                           (10.56%)
        4712606157      dTLB-load-misses                 #    0.12% of all dTLB cache accesses  (10.54%)
     4843108949083      branch-loads                                                         (10.54%)
         559456815      branch-misses                                                        (10.54%)
     4080852767855      L1-dcache-loads                                                      (10.54%)
        3439018132      L1-dcache-load-misses            #    0.08% of all L1-dcache accesses  (10.54%)
     3927441252649      L1-icache-loads                                                      (10.54%)
         301399472      L1-icache-load-misses            #    0.01% of all L1-icache accesses  (10.53%)
     4080540749606      dTLB-loads                                                           (10.53%)
        4898951704      dTLB-load-misses                 #    0.12% of all dTLB cache accesses  (10.53%)
     1204794477529      iTLB-loads                                                           (10.53%)
          15782196      iTLB-load-misses                 #    0.00% of all iTLB cache accesses  (10.53%)

     102.858055185 seconds time elapsed

    3562.057797000 seconds user
       8.121694000 seconds sys
```

---

### Reactor Parallel Perf

```
Perf stats:
--------------------------------------------------

     8015091312733      cycles                                                               (15.88%)
    22510415351083      instructions                     #    2.81  insn per cycle           (15.84%)
       13875033709      cache-misses                                                         (15.85%)
     3316387835496      L1-dcache-loads                                                      (10.54%)
       13995137625      L1-dcache-load-misses            #    0.42% of all L1-dcache accesses  (10.51%)
     3555871758939      L1-icache-loads                                                      (10.50%)
       24974994463      L1-icache-load-misses            #    0.70% of all L1-icache accesses  (10.51%)
     3309271378096      dTLB-loads                                                           (10.53%)
       12861402287      dTLB-load-misses                 #    0.39% of all dTLB cache accesses  (10.52%)
     4477034119392      branch-loads                                                         (10.52%)
        1362319191      branch-misses                                                        (10.54%)
     3306599209218      L1-dcache-loads                                                      (10.54%)
       14165760263      L1-dcache-load-misses            #    0.43% of all L1-dcache accesses  (10.53%)
     3553132144628      L1-icache-loads                                                      (10.52%)
       24990193957      L1-icache-load-misses            #    0.70% of all L1-icache accesses  (10.52%)
     3306347321694      dTLB-loads                                                           (10.54%)
       12985911503      dTLB-load-misses                 #    0.39% of all dTLB cache accesses  (10.55%)
      481813495029      iTLB-loads                                                           (10.56%)
        2564714462      iTLB-load-misses                 #    0.53% of all iTLB cache accesses  (10.58%)

     165.601714107 seconds time elapsed

    3105.774808000 seconds user
     749.313218000 seconds sys
```

---

### Reactor Bounded Elastic Perf

```
Perf stats:
--------------------------------------------------

     8265301063398      cycles                                                               (15.87%)
    22682721999766      instructions                     #    2.74  insn per cycle           (15.82%)
       17483697023      cache-misses                                                         (15.83%)
     3491239796102      L1-dcache-loads                                                      (10.53%)
       17782357071      L1-dcache-load-misses            #    0.51% of all L1-dcache accesses  (10.52%)
     3688623690432      L1-icache-loads                                                      (10.56%)
       37527966135      L1-icache-load-misses            #    1.02% of all L1-icache accesses  (10.56%)
     3483996020462      dTLB-loads                                                           (10.55%)
       19236301501      dTLB-load-misses                 #    0.55% of all dTLB cache accesses  (10.53%)
     4613210870784      branch-loads                                                         (10.51%)
        1769426429      branch-misses                                                        (10.53%)
     3482927545526      L1-dcache-loads                                                      (10.54%)
       17838719488      L1-dcache-load-misses            #    0.51% of all L1-dcache accesses  (10.53%)
     3688363055631      L1-icache-loads                                                      (10.50%)
       37480165429      L1-icache-load-misses            #    1.02% of all L1-icache accesses  (10.50%)
     3478542296873      dTLB-loads                                                           (10.53%)
       19156242775      dTLB-load-misses                 #    0.55% of all dTLB cache accesses  (10.55%)
      640965637510      iTLB-loads                                                           (10.56%)
        4826105479      iTLB-load-misses                 #    0.75% of all iTLB cache accesses  (10.55%)

     193.874608456 seconds time elapsed

    3248.631080000 seconds user
    1221.526222000 seconds sys
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