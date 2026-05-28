# Benchmarks in Amazon ECS using Intel Xeon 6

| Config           | Value                         |
|:-----------------|:------------------------------|
| Instance         | AWS c8i.8xlarge               |
| Operating System | Amazon Linux                  |
| Processor        | Intel Xeon 6 (Granite Rapids) |
| Architecture     | x86_64                        |
| vCPUs            | 32                            |

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

# Mandelbrot

These tests are missing perf counters due to the limitations of the EC2 virtualized environment.
While I wait on a quota increase for access to the bare-metal servers, these are the shallow results
from the tests.

With SMT enabled, Euhedral delegates the pulling of work to its hyper thread sibling.
With SMT disabled, Euhedral only places one thread on the core. 16 out of 32 possible threads.

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

| Scheduler                           | ns/op | Alloc mb/sec | bytes/op | GC Counts | GC Time |
|:------------------------------------|------:|-------------:|---------:|----------:|--------:|
| Euhedral Core (SMT disabled)        | 1.382 |        0.228 |    0.001 |         0 |       0 |
| Reactor Parallel (16 threads)       | 4.246 |       13.988 |    0.062 |         1 |      83 |
| Reactor BoundedElastic (16 threads) | 4.275 |       24.304 |    0.109 |         1 |      86 |
| Euhedral Core (SMT enabled)         | 0.580 |       26.677 |    0.016 |         1 |      47 |
| Reactor Parallel (32 threads)       | 4.259 |       13.942 |    0.062 |         1 |      83 |
| Reactor BoundedElastic (32 threads) | 4.036 |       27.317 |    0.116 |         1 |      13 |

| Scheduler                            | IPC  |
|--------------------------------------|------|
| Euhedral Core (SMT disabled)         | 1.62 |
| Reactor Parallel (16 threads)        | 0.50 |
| Reactor Bounded Elastic (16 threads) | 0.50 |
| Euhedral Core (SMT enabled)          | 1.04 |
| Reactor Parallel (32 threads)        | 0.50 |
| Reactor Bounded Elastic (32 threads) | 0.50 |

## Mandelbrot (1-by-1)

| Scheduler                           |    ns/op | Alloc mb/sec | bytes/op | GC Counts | GC Time |
|:------------------------------------|---------:|-------------:|---------:|----------:|--------:|
| Euhedral Core (SMT disabled)        |  558.119 |       42.333 |   25.040 |         1 |       5 |
| Reactor Parallel (16 threads)       | 1004.497 |       84.790 |   89.309 |         5 |      12 |
| Reactor BoundedElastic (16 threads) | 1095.067 |      131.935 |  151.496 |         5 |      14 |
| Euhedral Core (SMT enabled)         |  612.895 |       38.555 |   25.051 |         1 |      11 |
| Reactor Parallel (32 threads)       |  593.117 |      139.796 |   86.944 |         2 |       7 |
| Reactor BoundedElastic (32 threads) |  796.617 |      200.911 |  167.825 |         6 |      13 |

| Scheduler                            | IPC  |
|--------------------------------------|------|
| Euhedral Core (SMT disabled)         | 2.83 |
| Reactor Parallel (16 threads)        | 2.29 |
| Reactor Bounded Elastic (16 threads) | 2.33 |
| Euhedral Core (SMT enabled)          | 1.64 |
| Reactor Parallel (32 threads)        | 2.35 |
| Reactor Bounded Elastic (32 threads) | 2.13 |


### Theory/Explanation of Performance for Euhedral SMT Disabled vs Enabled

SMT caused an increase in allocations in both tests. For the batched test, SMT improved performance. 
For the 1-by-1, it hurt it.

The SMTBuddy class contains the work pulling logic.

When SMT is disabled, the SMTBuddy class is used by the ExecutionManager manually. When it is
enabled, it automatically runs the logic in the background. ExecutionManager does the executing, 
SMTBuddy does the pulling. They do not swap roles.

The ExecutionManager has other things to do besides pulling that take time to execute. It has its 
idling behavior and execution time to naturally slow down its pull rate. But the SMTBuddy class's only 
job is to pull. Even though it has the same backoff logic for pull timing, it is always ready to 
pull when the time allows it.

I believe the extra allocations come from the increased rate of the unbounded queues expanding to 
absorb the increased flow.

In the batched test, the 33.2 million pixel objects are batched in bundles of 1024. This dramatically
reduces the queue depth which stops them from expanding as often. Even though the number of 
tasks/operations is the same.

I have not fully optimized the small control loop in SMTBuddy. It was initially created to separate 
the pull logic from the ExecutionManager class. That cycle loop was essentially thrown together and 
needs better heuristics for backing off.

# Raw Data

## Batched Mandelbrot (Euhedral SMT disabled) (Reactor 16 Threads)

### Results

```
Benchmark                                                                                       Mode  Cnt   Score   Error   Units
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render                                            avgt        1.382           ns/op
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate                              avgt        0.228          MB/sec
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate.norm                         avgt       ≈ 10⁻³            B/op
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.count                                   avgt          ≈ 0          counts
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic                     avgt        4.275           ns/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate       avgt       24.304          MB/sec
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate.norm  avgt        0.109            B/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.count            avgt        1.000          counts
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.time             avgt       86.000              ms
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel                           avgt        4.246           ns/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate             avgt       13.988          MB/sec
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate.norm        avgt        0.062            B/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.count                  avgt        1.000          counts
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.time                   avgt       83.000              ms
```

### Euhedral Perf

```
Perf stats:
--------------------------------------------------

     2931035185921      cycles                                                             
     4741253957178      instructions                     #    1.62  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
         180458662      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

      47.267385582 seconds time elapsed

     928.727521000 seconds user
       6.460300000 seconds sys
```

### Reactor Parallel Perf

```
Perf stats:
--------------------------------------------------

      396104229224      cycles                                                             
      197326111312      instructions                     #    0.50  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
         139596835      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

      45.737766923 seconds time elapsed

     145.629896000 seconds user
      75.480149000 seconds sys
```

### Reactor Bounded Elastic Perf

```
Perf stats:
--------------------------------------------------

      412634230067      cycles                                                             
      204898933180      instructions                     #    0.50  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
         189117679      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

      46.501498158 seconds time elapsed

     149.925896000 seconds user
      78.000733000 seconds sys
```

---

## Batched Mandelbrot (Euhedral SMT enabled) (Reactor 32 Threads)

---

### Results

```
Benchmark                                                                                       Mode  Cnt   Score   Error   Units
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render                                            avgt        0.580           ns/op
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate                              avgt       26.677          MB/sec
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate.norm                         avgt        0.016            B/op
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.count                                   avgt        1.000          counts
BatchedMandelbrotBenchmark.EuhedralMandelbrot.render:gc.time                                    avgt       47.000              ms
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic                     avgt        4.070           ns/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate       avgt       28.312          MB/sec
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate.norm  avgt        0.121            B/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.count            avgt        1.000          counts
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.time             avgt       13.000              ms
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel                           avgt        4.285           ns/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate             avgt       12.552          MB/sec
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate.norm        avgt        0.056            B/op
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.count                  avgt        1.000          counts
BatchedMandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.time                   avgt       87.000              ms
```

### Euhedral Perf

```
Perf stats:
--------------------------------------------------

     5917349064102      cycles                                                             
     6170723224212      instructions                     #    1.04  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
         458736466      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

      49.359478559 seconds time elapsed

    1831.630777000 seconds user
       7.619347000 seconds sys
```

---

### Reactor Parallel Perf

```
Perf stats:
--------------------------------------------------

      413476101345      cycles                                                             
      198003720967      instructions                     #    0.48  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
         147998172      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

      46.510650093 seconds time elapsed

     142.739190000 seconds user
      78.951942000 seconds sys
```

---

### Reactor Bounded Elastic Perf

```
Perf stats:
--------------------------------------------------

      439062203017      cycles                                                             
      220354613716      instructions                     #    0.50  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
         202164421      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

      48.976674876 seconds time elapsed

     155.141197000 seconds user
      81.254058000 seconds sys
```

---

## Mandelbrot (Euhedral SMT disabled) (Reactor 16 Threads)

### Results

```
Benchmark                                                                                Mode  Cnt    Score   Error   Units
MandelbrotBenchmark.EuhedralMandelbrot.render                                            avgt       558.119           ns/op
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate                              avgt        42.333          MB/sec
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate.norm                         avgt        25.040            B/op
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.count                                   avgt         1.000          counts
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.time                                    avgt         5.000              ms
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic                     avgt       1095.067           ns/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate       avgt        131.935          MB/sec
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate.norm  avgt        151.496            B/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.count            avgt          5.000          counts
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.time             avgt         14.000              ms
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel                           avgt       1004.497           ns/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate             avgt         84.790          MB/sec
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate.norm        avgt         89.309            B/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.count                  avgt          5.000          counts
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.time                   avgt         12.000              ms
```

### Euhedral Perf

```
Perf stats:
--------------------------------------------------

     8839670815064      cycles                                                             
    25036704288685      instructions                     #    2.83  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
         577584056      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

     141.274226954 seconds time elapsed

    2443.992089000 seconds user
       9.454304000 seconds sys
```

### Reactor Parallel Perf

```
Perf stats:
--------------------------------------------------

    11225308573840      cycles                                                             
    25731788960324      instructions                     #    2.29  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
        3018067014      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

     261.701872925 seconds time elapsed

    2865.477957000 seconds user
    1546.115680000 seconds sys
```

### Reactor Bounded Elastic Perf

```
Perf stats:
--------------------------------------------------

    11116341152129      cycles                                                             
    25906736198418      instructions                     #    2.33  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
        3620748001      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

     284.864640393 seconds time elapsed

    2817.326272000 seconds user
    1439.987577000 seconds sys
```

## Mandelbrot (Euhedral SMT enabled) (Reactor 32 Threads)

### Results

```
Benchmark                                                                                Mode  Cnt    Score   Error   Units
MandelbrotBenchmark.EuhedralMandelbrot.render                                            avgt       612.895           ns/op
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate                              avgt        38.555          MB/sec
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate.norm                         avgt        25.051            B/op
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.count                                   avgt         1.000          counts
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.time                                    avgt        11.000              ms
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic                     avgt       796.617           ns/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate       avgt       200.911          MB/sec
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate.norm  avgt       167.825            B/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.count            avgt         6.000          counts
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.time             avgt        13.000              ms
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel                           avgt       593.117           ns/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate             avgt       139.796          MB/sec
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate.norm        avgt        86.944            B/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.count                  avgt         2.000          counts
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.time                   avgt         7.000              ms
```

### Euhedral Perf

```
Perf stats:
--------------------------------------------------

    19478667985848      cycles                                                             
    31976041732103      instructions                     #    1.64  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
         711147578      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

     162.241476191 seconds time elapsed

    5306.905784000 seconds user
      11.310072000 seconds sys
```

### Reactor Parallel Perf

```
Perf stats:
--------------------------------------------------

    10785970197517      cycles                                                             
    25391134104167      instructions                     #    2.35  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
        2148863123      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

     154.670418454 seconds time elapsed

    2764.951195000 seconds user
    1023.283620000 seconds sys
```

### Reactor Bounded Elastic Perf

```
Perf stats:
--------------------------------------------------

    12088591231156      cycles                                                             
    25793401205498      instructions                     #    2.13  insn per cycle         
                 0      cache-misses                                                       
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      branch-loads                                                
        3894046342      branch-misses                                                      
   <not supported>      L1-dcache-loads                                             
   <not supported>      L1-dcache-load-misses                                       
   <not supported>      LLC-loads                                                   
   <not supported>      LLC-load-misses                                             
   <not supported>      L1-icache-loads                                             
   <not supported>      L1-icache-load-misses                                       
   <not supported>      dTLB-loads                                                  
   <not supported>      dTLB-load-misses                                            
   <not supported>      iTLB-loads                                                  
   <not supported>      iTLB-load-misses                                            
   <not supported>      L1-dcache-prefetches                                        
   <not supported>      L1-dcache-prefetch-misses                                   

     206.108508452 seconds time elapsed

    2934.392752000 seconds user
    1645.524854000 seconds sys
```