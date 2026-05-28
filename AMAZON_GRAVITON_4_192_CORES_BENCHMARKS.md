# Benchmarks in Amazon ECS using Graviton4

| Config           | Values             |
|:-----------------|:-------------------|
| Instance         | AWS c8g.metal-48xl |
| Operating System | Amazon Linux       |
| Processor        | AWS Graviton4      |
| Architecture     | arm64              |
| vCPUs            | 192                |
| Cores            | 192                |
| Sockets          | 2                  |

#### VM Flags

```
-Xms120g
-Xmx120g
-XX:+UseThreadPriorities
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
--add-exports java.base/jdk.internal.platform=ALL-UNNAMED
--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
```

Work items were pre-allocated for all benchmarks to only measure scheduling overhead. A large heap
size was needed to fit the working set.

JMH was used for all benchmarking.

---

# Mandelbulb

Like the [Mandelbrot](https://en.wikipedia.org/wiki/Mandelbulb) benchmarks, this is a chaotic
benchmark with irregular execution times per pixel and destroys locality.

The Mandelbulb is a 3-dimensional fractal. In this test, an 8th degree Mandelbulb is rendered
using [ray marching](https://en.wikipedia.org/wiki/Ray_marching). This involves significant floating-point math for converting between
Cartesian and Spherical coordinates, and running the 8th degree Mandelbrot calculations where the ray
makes contact with the surface.

The reason this strategy was chosen was because the 2-dimensional Mandelbrot test was too light of a
workload for a 192 core system. The cost of transferring the memory from RAM over the interconnect
was higher than executing it. Also, the volume of work was not enough to fully saturate the system.

#### Mission

Render 2 9800x9800 Mandelbulb fractals

Using:

- 2X SSAA
- A max ray step of 200
- An iteration cap of 120 per step
- Randomized pixel ordering

Total pixels: 192,080,000

Operations per Invocation: 768,320,000

Max Mandelbrot iterations per pixel : 24,000

---

## Results

With Euhedral, you are able to set the routing policy individually for a task's execution. For one
set of tests, it uses the SOCKET_LOCAL policy. This forces frames to execute on the socket they were
allocated on.

| Test    | Socket Local |  ns/op | Alloc mb/sec | bytes/op | GC Counts | GC Time (ms) |
|:--------|-------------:|-------:|-------------:|---------:|----------:|-------------:|
| 1-by-1  |        false | 79.583 |      284.336 |   3.9626 |         3 |          196 |
| 1-by-1  |         true | 87.793 |      131.976 |   1.7366 |         2 |           63 |
| batched |        false | 97.321 |        0.945 |   0.0241 |         0 |            0 |
| batched |         true | 96.372 |        0.974 |   0.0247 |         0 |            0 |

Note: The raw data at the end of this doc shows the sum of bytes allocated during one
invocation (768,320,000 ops). Not per op.

---

#### Perf Counters

You'll notice the extremely high LLC misses on each test while every other counter is below 1%. My
interpretation of this is that the cores are consuming work faster than the cache hierarchy
can keep up.

A socket local ingest feature is on the list of future improvements. Euhedral distributes work too
evenly in this context.

Current socket local routing does not prevent cores on other sockets from interacting with the tasks
or queues. It only stops the remote cores from distributing that work across the interconnect. The
remote cores will still distribute the work for the intended socket.

Basically, load balancing too balancy for compute-heavy work like this.

| Test    | Socket Local | IPC  | L1 D-Cache Miss | L1 I-Cache Miss | dTLB Miss | iTLB Miss | LLC Miss | Branch Miss % |
|---------|-------------:|------|----------------:|----------------:|----------:|----------:|---------:|--------------:|
| 1-by-1  |        false | 2.10 |           0.62% |           0.01% |     0.63% |     0.00% |   53.77% |  0.000557623% |
| 1-by-1  |         true | 2.14 |           0.62% |           0.01% |     0.67% |     0.00% |   67.08% |  0.000575961% |
| batched |        false | 2.29 |           0.07% |           0.00% |     0.01% |      0.00 |   91.66% |  0.000315590% |
| batched |         true | 2.37 |           0.09% |            0.00 |     0.01% |      0.00 |   88.65% |  0.000320798% |

---

#### Raw Hardware Counters

| Test    | Socket Local |             Cycles |        Instructions |    Cache Misses |       Branch Loads | Branch Misses |
|---------|-------------:|-------------------:|--------------------:|----------------:|-------------------:|--------------:|
| 1-by-1  |        false | 42,245,104,709,920 |  88,880,739,438,153 | 113,563,534,546 | 11,506,515,812,263 | 6,416,295,638 |
| 1-by-1  |         true | 44,964,638,651,129 |  96.207.951.217.177 | 123,337,481,930 | 12,155,722,174,471 | 7,001,227,182 |
| batched |        false | 48,155,879,420,452 | 110,080,891,903,044 |  23,745,610,648 | 17,258,895,191,955 | 5,446,729,036 |
| batched |         true | 48,939,279,111,425 | 116,027,448,153,106 |  32,099,255,801 | 18,408,091,139,245 | 5,905,269,699 |

---

#### CPU Time

| Test    | Socket Local | Wall Clock Runtime | User Seconds | System Time |
|---------|-------------:|-------------------:|-------------:|------------:|
| 1-by-1  |        false |            105.600 |   15,618.765 |     212.211 |
| 1-by-1  |         true |            109.995 |   16,627.982 |     191.102 |
| batched |        false |            118.313 |   17,825.543 |     187.688 |
| batched |         true |            120.282 |   18,096.239 |     190.548 |

---

# Raw Data

---

## Mandelbulb

---

### Results

```
Benchmark                                                         Mode  Cnt            Score   Error   Units
HighScaleBenchmark.Batched.render                                 avgt       18693408617.250           ns/op
HighScaleBenchmark.Batched.render:gc.alloc.rate                   avgt                 0.945          MB/sec
HighScaleBenchmark.Batched.render:gc.alloc.rate.norm              avgt          18527618.000            B/op
HighScaleBenchmark.Batched.render:gc.count                        avgt                   ≈ 0          counts
HighScaleBenchmark.Batched.render:operations                      avgt                97.321           ns/op
HighScaleBenchmark.BatchedSocketLocal.render                      avgt       18511057633.750           ns/op
HighScaleBenchmark.BatchedSocketLocal.render:gc.alloc.rate        avgt                 0.974          MB/sec
HighScaleBenchmark.BatchedSocketLocal.render:gc.alloc.rate.norm   avgt          18954012.000            B/op
HighScaleBenchmark.BatchedSocketLocal.render:gc.count             avgt                   ≈ 0          counts
HighScaleBenchmark.BatchedSocketLocal.render:operations           avgt                96.372           ns/op
HighScaleBenchmark.OneByOne.render                                avgt       10190913059.333           ns/op
HighScaleBenchmark.OneByOne.render:gc.alloc.rate                  avgt               284.336          MB/sec
HighScaleBenchmark.OneByOne.render:gc.alloc.rate.norm             avgt        3044530974.667            B/op
HighScaleBenchmark.OneByOne.render:gc.count                       avgt                 3.000          counts
HighScaleBenchmark.OneByOne.render:gc.time                        avgt               196.000              ms
HighScaleBenchmark.OneByOne.render:operations                     avgt                79.583           ns/op  
HighScaleBenchmark.OneByOneSocketLocal.render                     avgt        9636182312.000           ns/op
HighScaleBenchmark.OneByOneSocketLocal.render:gc.alloc.rate       avgt               131.976          MB/sec
HighScaleBenchmark.OneByOneSocketLocal.render:gc.alloc.rate.norm  avgt        1334234603.429            B/op
HighScaleBenchmark.OneByOneSocketLocal.render:gc.count            avgt                 2.000          counts
HighScaleBenchmark.OneByOneSocketLocal.render:gc.time             avgt                63.000              ms
HighScaleBenchmark.OneByOneSocketLocal.render:operations          avgt                87.793           ns/op 
```

---

### Mandelbulb (1-by-1) Perf

```
Perf stats:
--------------------------------------------------

    42245104709920      cycles                                                               (33.37%)
    88880739438153      instructions                     #    2.10  insn per cycle           (33.37%)
      113563534546      cache-misses                                                         (33.37%)
    18378937730936      L1-dcache-loads                                                      (33.37%)
      113706323195      L1-dcache-load-misses            #    0.62% of all L1-dcache accesses  (33.36%)
    15581590825721      L1-icache-loads                                                      (33.36%)
        1176587849      L1-icache-load-misses            #    0.01% of all L1-icache accesses  (33.37%)
    18394213646597      dTLB-loads                                                           (28.60%)
      116481916297      dTLB-load-misses                 #    0.63% of all dTLB cache accesses  (28.60%)
    11506515812263      branch-loads                                                         (28.60%)
        6416295638      branch-misses                                                        (28.61%)
    18380729828221      L1-dcache-loads                                                      (28.62%)
      114246881237      L1-dcache-load-misses            #    0.62% of all L1-dcache accesses  (28.62%)
       78286667269      LLC-loads                                                            (28.62%)
       42094030077      LLC-load-misses                  #   53.77% of all LL-cache accesses  (28.62%)
    15573755452644      L1-icache-loads                                                      (28.61%)
        1106457885      L1-icache-load-misses            #    0.01% of all L1-icache accesses  (28.60%)
    18368352953836      dTLB-loads                                                           (28.60%)
      116158903642      dTLB-load-misses                 #    0.63% of all dTLB cache accesses  (28.59%)
    19531446228503      iTLB-loads                                                           (28.58%)
          63801440      iTLB-load-misses                 #    0.00% of all iTLB cache accesses  (28.60%)

     105.600399438 seconds time elapsed

   15618.765049000 seconds user
     212.211311000 seconds sys
```

### Mandelbulb Socket-Local (1-by-1) Perf

```
Perf stats:
--------------------------------------------------

    44964638651129      cycles                                                               (33.35%)
    96207951217177      instructions                     #    2.14  insn per cycle           (33.35%)
      123337481930      cache-misses                                                         (33.35%)
    19707682543892      L1-dcache-loads                                                      (33.35%)
      123118113897      L1-dcache-load-misses            #    0.62% of all L1-dcache accesses  (33.35%)
    16976726121416      L1-icache-loads                                                      (33.36%)
        1205217558      L1-icache-load-misses            #    0.01% of all L1-icache accesses  (33.36%)
    19707972131054      dTLB-loads                                                           (28.61%)
      130387414571      dTLB-load-misses                 #    0.66% of all dTLB cache accesses  (28.63%)
    12155722174471      branch-loads                                                         (28.63%)
        7001227182      branch-misses                                                        (28.62%)
    19709667761313      L1-dcache-loads                                                      (28.62%)
      122776671933      L1-dcache-load-misses            #    0.62% of all L1-dcache accesses  (28.61%)
       69154917533      LLC-loads                                                            (28.61%)
       46390235497      LLC-load-misses                  #   67.08% of all LL-cache accesses  (28.60%)
    16975874858901      L1-icache-loads                                                      (28.60%)
        1191843392      L1-icache-load-misses            #    0.01% of all L1-icache accesses  (28.59%)
    19712709522666      dTLB-loads                                                           (28.59%)
      131152487830      dTLB-load-misses                 #    0.67% of all dTLB cache accesses  (28.58%)
    17264569176010      iTLB-loads                                                           (28.59%)
          87958370      iTLB-load-misses                 #    0.00% of all iTLB cache accesses  (28.59%)

     109.994582561 seconds time elapsed

   16627.982423000 seconds user
     191.101631000 seconds sys
```

### Mandelbulb (batched) Perf

```
Perf stats:
--------------------------------------------------

    48155879420452      cycles                                                               (33.35%)
   110080891903044      instructions                     #    2.29  insn per cycle           (33.35%)
       23745610648      cache-misses                                                         (33.36%)
    32430279525406      L1-dcache-loads                                                      (33.37%)
       23804697156      L1-dcache-load-misses            #    0.07% of all L1-dcache accesses  (33.38%)
    18395598978620      L1-icache-loads                                                      (33.38%)
         858413068      L1-icache-load-misses            #    0.00% of all L1-icache accesses  (33.39%)
    32393730082899      dTLB-loads                                                           (28.62%)
        3123787330      dTLB-load-misses                 #    0.01% of all dTLB cache accesses  (28.62%)
    17258895191955      branch-loads                                                         (28.61%)
        5446729036      branch-misses                                                        (28.61%)
    32394583434102      L1-dcache-loads                                                      (28.61%)
       23674481234      L1-dcache-load-misses            #    0.07% of all L1-dcache accesses  (28.60%)
       14918574949      LLC-loads                                                            (28.60%)
       13674539845      LLC-load-misses                  #   91.66% of all LL-cache accesses  (28.60%)
    18400044747424      L1-icache-loads                                                      (28.59%)
         789606435      L1-icache-load-misses            #    0.00% of all L1-icache accesses  (28.59%)
    32434774599540      dTLB-loads                                                           (28.58%)
        2907779906      dTLB-load-misses                 #    0.01% of all dTLB cache accesses  (28.57%)
    25877961612470      iTLB-loads                                                           (28.56%)
          37555267      iTLB-load-misses                 #    0.00% of all iTLB cache accesses  (28.56%)

     118.313067165 seconds time elapsed

   17825.542608000 seconds user
     187.688932000 seconds sys
```

### Mandelbulb Socket-Local (batched) Perf

```
Perf stats:
--------------------------------------------------

    48939279111425      cycles                                                               (33.35%)
   116027448153106      instructions                     #    2.37  insn per cycle           (33.34%)
       32099255801      cache-misses                                                         (33.34%)
    33873547608570      L1-dcache-loads                                                      (33.33%)
       32125002769      L1-dcache-load-misses            #    0.09% of all L1-dcache accesses  (33.33%)
    19859202286368      L1-icache-loads                                                      (33.36%)
         777385072      L1-icache-load-misses            #    0.00% of all L1-icache accesses  (33.37%)
    33841969580774      dTLB-loads                                                           (28.60%)
        3052626965      dTLB-load-misses                 #    0.01% of all dTLB cache accesses  (28.61%)
    18408091139245      branch-loads                                                         (28.61%)
        5905269699      branch-misses                                                        (28.61%)
    33827268800314      L1-dcache-loads                                                      (28.61%)
       32068424382      L1-dcache-load-misses            #    0.09% of all L1-dcache accesses  (28.61%)
       10453218815      LLC-loads                                                            (28.61%)
        9267157255      LLC-load-misses                  #   88.65% of all LL-cache accesses  (28.61%)
    19853003585147      L1-icache-loads                                                      (28.61%)
         705504347      L1-icache-load-misses            #    0.00% of all L1-icache accesses  (28.61%)
    33839960834138      dTLB-loads                                                           (28.61%)
        2923111278      dTLB-load-misses                 #    0.01% of all dTLB cache accesses  (28.60%)
    30082830852724      iTLB-loads                                                           (28.60%)
          32073036      iTLB-load-misses                 #    0.00% of all iTLB cache accesses  (28.59%)

     120.281842724 seconds time elapsed

   18096.239273000 seconds user
     190.547779000 seconds sys
```