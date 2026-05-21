# Euhedral Execution

Welcome! The goal of this project is to create an adaptive, easy-to-use, high-performance Java
execution framework.

The core of this project is centered on the pull-based, NUMA aware, lock-free, recursive,
load-balancing, self-tuning scheduler
known as euhedral-core. It is a system that automatically detects the layout of the hardware it runs
on and pins 1 thread to each
CPU. It is structured and operates like a distributed system. Think Kubernetes but the CPUs are the
independent pods. It also automatically rebalances
itself when the effective CPU set changes while guaranteeing sequential work maintains its execution
order.

Architecture diagram coming soon.

## Modules

This project features several modules that are independently usable. euhedral-core relies on all of
them.

### euhedral-core

The nexus of the project.

##### Structure:

In its simplest view, it is a straight line.

`ControlPlane -> ControlPlaneShard -> CloneableObject(DRRCacheManager -> ExecutionManager -> AbstractExecutor)`

Everything below the ControlPlane is replicated according to the socket and core topology. This
forms an n-ary tree structure.

Components:

- ControlPlane - The global ingest point and the raw interface for the system. Responsible for
  routing work proportionally to sockets.
- ControlPlaneShard - Cloned once onto every available socket. Responsible for routing work evenly
  to cores and managing the lifecycles of the clones it spawns on them
- CloneableObject - A generic interface for anything that can replicate itself. Any implementation
  of the interface can be passed to the ControlPlane to automatically be replicated, assigned a
  core, and have work routed to them. The ControlPlane does not pin them automatically. It only
  provides information and work relevant to their core while giving them full freedom to use their
  core how they want.
- DRRCacheManager - A deficit round-robin queue that is sized to fit in the L2 cache of the cores it
  serves. Only cores that share L2 can consume from their queue. One is made per L2 cluster and work
  is taken first-come-first-served. Work is enqueued by all cores.
- ExecutionManager - The heart of the system. Runs on a pinned thread on each CPU. It uses TCP Vegas
  and Little's Law to detect throttling and shape its demand signaling.
- AbstractExecutors - Does nothing but hit "execute" on the work item.

### euhedral-data-structures

This module contains the padded atomic classes and queues used throughout the system.

All queues are lock-free and come in every combination of plain, SPSC, SPMC, MPSC, MPMC, bounded,
and unbounded.
They are also padded and partitioned. This allows euhedral-core to create contiguous blocks
of memory while distributing cache contention among the partitions. The partitions, chunk sizes, and
recycling pool are configurable.

The padded atomic classes for singular values are padded with 128 bytes. The other classes can be
configured to be padded by 64 or 128 bytes.

Clases:

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
JNI code is cross-compiled for Linux glibc, Linux musl, Windows, OSX in x64 and arm64.

Core Features:

- PinnedThreadExecutor - An implementation of ExecutorService that automatically creates and binds
  threads to specific CPUs. Available on Linux and Windows
- ThreadTools - Contains the functions for binding a thread to a cpu or setting timer_slack (and
  Windows equivalent). Supports any number of CPUs.
- SystemInfo - A full topology mapping of the host's CPU layout
- ResourceMonitor - An automatic hardware status monitor that periodically polls the hardware
  readings and aggregates statuses per CPU, core, and socket. Uses EWMA to smooth calculations.

##### Topology Mapping

For Linux systems, it maps the container by reading cgroupV2 and sysfs. For Windows, it uses JNI to
call the win32 api and queries the ProcessorRelationships.
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

##### Hardware Monitoring

euhedral-core relies on hardware readings to ensure its dynamic calculations are within the reality
of the system's actual state.

This is the raw snapshot created:

```java
public record SystemSnapshot(long timeNs, int totalCpus, double quotaCpus, long period,
                             long cpuUsage,
                             long cpuThrottle, UnmodifiableBitSet effectiveCpus,
                             UnmodifiableDoubleArray pressurePerCpu, long memoryLimit,
                             long memoryUsage,
                             long inactiveFileMemory, long diskIOBytes) {

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