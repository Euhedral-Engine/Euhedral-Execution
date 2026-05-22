# Euhedral Execution

Welcome! The goal of this project is to create an adaptive, easy-to-use, high-performance Java
execution framework.

The core of this project is centered around the pull-based, NUMA aware, lock-free, recursive,
load-balancing, self-tuning scheduler
known as **euhedral-core**. It is a system that automatically detects the layout of the hardware it
runs
on and pins one thread to each
CPU. It is structured and operates like a distributed system. Think Kubernetes but the CPUs are the
independent pods. It automatically rebalances
itself when the effective CPU set changes while guaranteeing sequential work maintains its execution
order.

Architecture diagram coming soon.

## TOC

<!-- TOC -->
* [Euhedral Execution](#euhedral-execution)
  * [TOC](#toc)
  * [Benchmarks](#benchmarks)
      * [VM Flags](#vm-flags)
    * [End to End Latency](#end-to-end-latency)
    * [Throughput](#throughput)
    * [Mandelbrot](#mandelbrot)
  * [Modules](#modules)
    * [euhedral-core](#euhedral-core)
      * [Structure](#structure)
      * [Components](#components)
    * [euhedral-data-structures](#euhedral-data-structures)
        * [Classes](#classes)
    * [euhedral-hardware-utils](#euhedral-hardware-utils)
      * [Core Features](#core-features)
      * [Topology Mapping](#topology-mapping)
      * [Hardware Monitoring](#hardware-monitoring)
    * [euhedral-hashing](#euhedral-hashing)
    * [euhedral-reactor-core](#euhedral-reactor-core)
  * [TODO](#todo)
<!-- TOC -->

## Benchmarks

These were performed on a desktop with an **Intel i9-14900K** and **64GB DDR5** memory on
**Ubuntu 24.04.4 LTS** with power mode set to **Performance**. Stock BIOS
settings and no overclocking. All work
items were pre-allocated to test the allocation and memory efficiency of the scheduler itself. JMH
was used as the test suite

Euhedral Core has a system for recycling task wrappers. It is meant to reduce or eliminate
allocations during steady state. Benchmarks with that feature in-use are coming soon.

#### VM Flags

All tests were ran with these same flags.

```
-XX:+UseThreadPriorities
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
--add-exports java.base/jdk.internal.platform=ALL-UNNAMED
--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
```

Euhedral only needs -XX:+UseThreadPriorities and --add-opens java.base/java.util=ALL-UNNAMED. The
others were to silence warnings from JMH and external libraries that are in the process of being
removed.

### End to End Latency

This tests the latency from ingest to execution. It measures the latency of routing, scheduling, and
queue residency. The test executes 100K no-op work items per invocation. Executors update a global
PaddedLongAdder cell assigned by cpu. There are 2 tests. One targets 2 P cores
only. One targets 4 E cores that share L2 cache.

```
Benchmark                                                     Mode    Cnt   Score   Error   Units
EndToEndLatencyBenchmark.ECore.endToEnd                     sample  37610  34.442 ± 0.029   ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:gc.alloc.rate       sample     15  12.928 ± 1.861  MB/sec
EndToEndLatencyBenchmark.ECore.endToEnd:gc.alloc.rate.norm  sample     15   0.542 ± 0.077    B/op
EndToEndLatencyBenchmark.ECore.endToEnd:gc.count            sample     15   4.000          counts
EndToEndLatencyBenchmark.ECore.endToEnd:gc.time             sample     15  11.000              ms
EndToEndLatencyBenchmark.ECore.endToEnd:p0.00               sample         29.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.50               sample         34.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.90               sample         36.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.95               sample         37.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.99               sample         38.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.999              sample         42.000           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p0.9999             sample         67.239           ns/op
EndToEndLatencyBenchmark.ECore.endToEnd:p1.00               sample         69.000           ns/op
EndToEndLatencyBenchmark.PCore.endToEnd                     sample  22856  60.443 ± 0.074   ns/op
EndToEndLatencyBenchmark.PCore.endToEnd:gc.alloc.rate       sample     15   2.782 ± 0.063  MB/sec
EndToEndLatencyBenchmark.PCore.endToEnd:gc.alloc.rate.norm  sample     15   0.192 ± 0.001    B/op
EndToEndLatencyBenchmark.PCore.endToEnd:gc.count            sample     15     ≈ 0          counts
EndToEndLatencyBenchmark.PCore.endToEnd:p0.00               sample         45.000           ns/op
EndToEndLatencyBenchmark.PCore.endToEnd:p0.50               sample         61.000           ns/op
EndToEndLatencyBenchmark.PCore.endToEnd:p0.90               sample         64.000           ns/op
EndToEndLatencyBenchmark.PCore.endToEnd:p0.95               sample         66.000           ns/op
EndToEndLatencyBenchmark.PCore.endToEnd:p0.99               sample         68.000           ns/op
EndToEndLatencyBenchmark.PCore.endToEnd:p0.999              sample         80.000           ns/op
EndToEndLatencyBenchmark.PCore.endToEnd:p0.9999             sample         88.000           ns/op
EndToEndLatencyBenchmark.PCore.endToEnd:p1.00               sample         98.000           ns/op
```

### Throughput

This tests throughput using the same no-op execution with a global counter. 32 million
work items per invocation. Utilizes all cores.

0.037 ops/ns = 37,000,000 ops/s

```
Benchmark                                                              Mode  Cnt   Score    Error   Units
TrueThroughputBenchmark.ingest32million32sources                      thrpt    5   0.037 ±  0.001  ops/ns
TrueThroughputBenchmark.ingest32million32sources:gc.alloc.rate        thrpt    5  95.366 ± 12.030  MB/sec
TrueThroughputBenchmark.ingest32million32sources:gc.alloc.rate.norm   thrpt    5   2.746 ±  0.330    B/op
TrueThroughputBenchmark.ingest32million32sources:gc.count             thrpt    5   5.000           counts
TrueThroughputBenchmark.ingest32million32sources:gc.time              thrpt    5  12.000               ms
TrueThroughputBenchmark.ingest32million32sources                     sample   60  26.633 ±  0.308   ns/op
TrueThroughputBenchmark.ingest32million32sources:gc.alloc.rate       sample    5  99.458 ±  8.600  MB/sec
TrueThroughputBenchmark.ingest32million32sources:gc.alloc.rate.norm  sample    5   2.838 ±  0.124    B/op
TrueThroughputBenchmark.ingest32million32sources:gc.count            sample    5   5.000           counts
TrueThroughputBenchmark.ingest32million32sources:gc.time             sample    5  13.000               ms
TrueThroughputBenchmark.ingest32million32sources:p0.00               sample       26.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.50               sample       27.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.90               sample       27.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.95               sample       27.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.99               sample       30.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.999              sample       30.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p0.9999             sample       30.000            ns/op
TrueThroughputBenchmark.ingest32million32sources:p1.00               sample       30.000            ns/op
```

### Mandelbrot

This is a stress test to simulate performance under highly irregular workloads. The test was to
render the [Mandelbrot fractal set](https://en.wikipedia.org/wiki/Mandelbrot_set) in 8K with 2X SSAA
pixel-by-pixel. When a pixel is in the Mandelbrot set, the calculation loop will run infinitely. If
it is outside the set, the calculation loop will terminate almost immediately. Max iterations was
set to 5,000. The pixels were
shuffled randomly before the test to maximize chaos

[This picture specifically](https://en.wikipedia.org/wiki/Mandelbrot_set#/media/File:Mandel_zoom_08_satellite_antenna.jpg)

**7680 * 4320 * 4 = 132,710,400 distinct tasks**

```
Benchmark                                                                                Mode  Cnt     Score   Error   Units
MandelbrotBenchmark.EuhedralMandelbrot.render                                            avgt        403.900           ns/op
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate                              avgt         49.720          MB/sec
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.alloc.rate.norm                         avgt         25.001            B/op
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.count                                   avgt          1.000          counts
MandelbrotBenchmark.EuhedralMandelbrot.render:gc.time                                    avgt         13.000              ms
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic                     avgt       2260.589           ns/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate       avgt         13.070          MB/sec
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.alloc.rate.norm  avgt         31.203            B/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.count            avgt          4.000          counts
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersBoundedElastic:gc.time             avgt         11.000              ms
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel                           avgt       2260.566           ns/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate             avgt         13.833          MB/sec
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.alloc.rate.norm        avgt         33.028            B/op
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.count                  avgt          5.000          counts
MandelbrotBenchmark.ReactorMandelbrot.renderSchedulersParallel:gc.time                   avgt         14.000              ms
```

## Modules

This project features several modules that are independently usable. euhedral-core relies on all of
them.

### euhedral-core

The nexus of the project.

#### Structure

In its simplest view, it is a straight line.

`ControlPlane -> ControlPlaneShard -> CloneableObject(DRRCacheManager -> ExecutionManager -> AbstractExecutor)`

Everything below the ControlPlane is replicated according to the socket and core topology. This
forms an n-ary tree structure.

#### Components

| Component                                                  | Description                                                                                                                                                                                                                                                                                                                                                                                     |
|:-----------------------------------------------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <span style="white-space: nowrap">ControlPlane</span>      | The global ingest point and the raw interface for the system. Responsible for routing work proportionally to sockets.                                                                                                                                                                                                                                                                           |
| <span style="white-space: nowrap">ControlPlaneShard</span> | Cloned once onto every available socket. Responsible for routing work evenly to cores and managing the lifecycles of the clones it spawns on them.                                                                                                                                                                                                                                              |
| <span style="white-space: nowrap">CloneableObject</span>   | A generic interface for anything that can replicate itself. Any implementation of the interface can be passed to the ControlPlane to automatically be replicated, assigned a core, and have work routed to them. The ControlPlane does not pin them automatically. It only provides information and work relevant to their core while giving them full freedom to use their core how they want. |
| <span style="white-space: nowrap">DRRCacheManager</span>   | A deficit round-robin queue that is sized to fit in the L2 cache of the cores it serves. Only cores that share L2 can consume from their queue. One is made per L2 cluster and work is taken first-come-first-served. Work is enqueued by all cores.                                                                                                                                            |
| <span style="white-space: nowrap">ExecutionManager</span>  | The heart of the system. Runs on a pinned thread on each CPU. It uses TCP Vegas and Little's Law to detect throttling and shape its demand signaling.                                                                                                                                                                                                                                           |
| <span style="white-space: nowrap">AbstractExecutors</span> | Does nothing but hit "execute" on the work item.                                                                                                                                                                                                                                                                                                                                                |

### euhedral-data-structures

This module contains the padded atomic classes and queues used throughout the system.

All queues are lock-free and come in every combination of plain, SPSC, SPMC, MPSC, MPMC, bounded,
and unbounded. They are also padded and partitioned. This allows euhedral-core to create contiguous
blocks of memory while distributing cache contention among the partitions. The partitions, chunk
sizes, and recycling pool are configurable.

The padded atomic classes for singular values are padded with 128 bytes. The other atomic classes
can be configured to be padded by 64 or 128 bytes.

##### Classes

- AtomicDouble
- PaddedAtomicDouble
- PaddedAtomicDoubleArray
- PaddedAtomicLong
- PaddedAtomicLongArray
- PaddedAtomicReference
- PaddedAtomicReferenceArray
- PaddedLongAdder

### euhedral-hardware-utils

This is the most important module in the system and is central to its efficiency. All the necessary
JNI code is cross-compiled for Linux glibc, Linux musl, Windows, and OSX with binaries for x64 and
arm64.

#### Core Features

| Feature                                                       | Description                                                                                                                                                             |
|:--------------------------------------------------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <span style="white-space: nowrap">PinnedThreadExecutor</span> | An implementation of ExecutorService that automatically creates and binds threads to specific CPUs. Available on Linux and Windows.                                     |
| <span style="white-space: nowrap">ResourceMonitor</span>      | An automatic hardware status monitor that periodically polls the hardware readings and aggregates statuses per CPU, core, and socket. Uses EWMA to smooth calculations. |
| <span style="white-space: nowrap">SystemInfo</span>           | A full topology mapping of the host's CPU layout.                                                                                                                       |
| <span style="white-space: nowrap">ThreadTools</span>          | Contains the functions for binding a thread to a cpu or setting timer_slack (and Windows equivalent). Supports any number of CPUs.                                      |

#### Topology Mapping

For Linux systems, it maps the container by reading cgroupV2 and sysfs. For Windows, it uses JNI to
call the win32 api and query the ProcessorRelationships.
MacOS support is a work in progress.

Example of the topology mapping:

```
SystemInfo
SocketInfo: [cpuHexMask = "3fff", coreHexMask = "3,11ff", socket = 0]
--------------------------------------------------------------------------------
CoreInfo: [cpuHexMask = "3", pCore = true, core = 8, socket = 0]
CoreInfo: [cpuHexMask = "c", pCore = true, core = 12, socket = 0]
--------------------------------------------------------------------------------
CpuInfo: [cpu = 0, core = 8, socket = 0]
CpuInfo: [cpu = 1, core = 8, socket = 0]
CpuInfo: [cpu = 2, core = 12, socket = 0]
CpuInfo: [cpu = 3, core = 12, socket = 0]
--------------------------------------------------------------------------------
CpuCacheLayout: [cpu = 0, bytesL1 = 48KB, bytesL2 = 2MB, bytesL3 = 12MB, sharesL1 = 2, sharesL2 = 2, sharesL3 = 12, maskL1 = "0003", maskL2 = "0003", maskL3 = "0fff", cacheLineBytes = 64]
CpuCacheLayout: [cpu = 1, bytesL1 = 48KB, bytesL2 = 2MB, bytesL3 = 12MB, sharesL1 = 2, sharesL2 = 2, sharesL3 = 12, maskL1 = "0003", maskL2 = "0003", maskL3 = "0fff", cacheLineBytes = 64]
CpuCacheLayout: [cpu = 2, bytesL1 = 48KB, bytesL2 = 2MB, bytesL3 = 12MB, sharesL1 = 2, sharesL2 = 2, sharesL3 = 12, maskL1 = "000c", maskL2 = "000c", maskL3 = "0fff", cacheLineBytes = 64]
CpuCacheLayout: [cpu = 3, bytesL1 = 48KB, bytesL2 = 2MB, bytesL3 = 12MB, sharesL1 = 2, sharesL2 = 2, sharesL3 = 12, maskL1 = "000c", maskL2 = "000c", maskL3 = "0fff", cacheLineBytes = 64]
```

#### Hardware Monitoring

euhedral-core relies on hardware readings to ensure its dynamic calculations are within the reality
of the system's actual state.

This is the raw snapshot created:

```java
public record SystemSnapshot(long timeNs, int totalCpus, double quotaCpus, long period,
                             long cpuUsage, long cpuThrottle, UnmodifiableBitSet effectiveCpus,
                             UnmodifiableDoubleArray pressurePerCpu, long memoryLimit,
                             long memoryUsage, long inactiveFileMemory, long diskIOBytes) {

}
```

These are the EWMA smoothed utilization reports generated by the ResourceMonitor:

```java
public record HardwareUtilization(long timestampNs, double quotaCpus, double quotaCpuUsage,
                                  long period, UnmodifiableBitSet globalEffectiveCpus,
                                  double cpuThrottleRatio,
                                  UnmodifiableDoubleArray perQuotaCpuThrottleRatio,
                                  UnmodifiableDoubleArray perQuotaCpuPressure,
                                  long globalMemoryPool, long perCpuMemoryPool,
                                  double totalMemoryUtilization, long memPerCpuUsageBytes,
                                  double diskIOBytesPerSecond, double diskIOPressure,
                                  SystemSnapshot snapshot) {

}
```

### euhedral-hashing

A Java-only implementation of the xxHash64 algorithm. This is used for routing work from ingest to
the pinned workers. It provides high-speed deterministic hashing with high avalanche properties
which evenly distributes load. Because it is deterministic, ordered work can be guaranteed to hit
the same socket, queue, and core.

### euhedral-reactor-core

An API layer for compatibility with Reactor. This module contains a wrapper that makes euhedral-core
usable as a standard Reactor scheduler. It also contains an operator wrapper to use it in
`.transform()` chains with logic for `.flatMap()`, `.flatMapSequential()`, and `.map()`.

## TODO

- Add more unit tests. The end-to-end tests are not enough.
- Add more comments and documentation.
- Cleanup dependencies.