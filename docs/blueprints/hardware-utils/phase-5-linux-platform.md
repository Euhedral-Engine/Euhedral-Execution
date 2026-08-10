# Phase 5 Linux Platform Blueprint: Topology, Resource Collection, and Native Parity

## 1. Executive Summary & Objective

This blueprint establishes the architecture, technical contracts, data flows, and child
responsibilities for **Phase 5 (Linux Parity, Cgroups, and libc Portability)** of the
`euhedral-hardware-utils` overhaul, as governed by [
`docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md).

The objective of Phase 5 is to deliver a robust, truthful, read-only Linux hardware and resource
provider supporting cgroup v1, cgroup v2, hybrid, and bare-host execution environments across x86-64
and AArch64 architectures. This implementation eliminates legacy defects (T02, R02, R06, R11, R12,
R14, B06), adheres strictly to the common sampling and pressure engine established in Phase 4,
enforces safe Linux kernel direct syscalls with proven glibc 2.17 and musl ABI compatibility, and
guarantees zero mutation of host or controller cgroup settings.

## 2. Scope & Non-Goals

### 2.1. In Scope

- Read-only discovery and metric collection for cgroup v1, cgroup v2, hybrid v1/v2, and bare-host
  Linux systems.
- Robust parsing of `/proc/self/cgroup`, `/proc/self/mountinfo`, `/proc/stat`, `/proc/meminfo`,
  `/proc/diskstats`, sysfs `/sys/devices/system/cpu/`, `/sys/fs/cgroup/`, and `/sys/class/`.
- Sparse and offline multisocket CPU topology parsing, ensuring global `(packageId, dieId, coreId)`
  core uniqueness and dense logical indexing with explicit null holes for offline/unmapped cores.
- Canonical cumulative unit extraction (nanoseconds for time, bytes for memory) and counter
  rebase/reset handling using P4's `CounterSignal` / `CounterDelta` engine.
- Honest cgroup-aggregate pressure propagation to effective cgroup CPUs without host jiffy
  apportionment.
- Complete, bounded file reads avoiding single-read truncation and resource leaks.
- Correct block-device filtering (excluding `loop`, `ram`, `zram`, and virtual devices; including
  `sd`, `nvme`, `vd`, `xvd`, `mmcblk`, `md`, `mapper`).
- Frequency and thermal signal sampling at slow (5 s) cadences with explicit `SignalValidity` state
  tracking.
- Stable direct Linux system calls for affinity (`sys_sched_setaffinity`, `sys_sched_getaffinity`,
  `sys_getcpu`) and timer slack (`prctl(PR_SET_TIMER_SLACK)`).
- Kernel floor derivation and proof for **Linux 3.10** on x86-64 and AArch64.
- Portable JNI shared libraries (`liblinux_jni.so`) compiled via Zig for `glibc 2.17` and `musl`
  with zero `libstdc++`, `libc++`, or `libgcc_s` dependencies.
- Linux fixture suites, Testcontainers integration, and binary gate validations.

### 2.2. Non-Goals

- Modifying or redesigning the common pressure formulas or normalization curves established in Phase
    4.
- Windows or macOS implementation changes (reserved for Phase 6 and Phase 7).
- Modifying core fragment execution or action-picker policies (reserved for Phase 8).
- Writing or mutating host cgroup settings (e.g., writing to `cgroup.subtree_control`).
- Any inspection, build, or test activity involving `euhedral-training`.

## 3. Core Architectural & Technical Contracts

### 3.1. Read-Only Cgroup Discovery (v1, v2, Hybrid, Bare-Host)

- **Defect R12 Correction**: All Linux cgroup and system discovery must be **strictly read-only**.
  The provider must NEVER attempt to write to `/sys/fs/cgroup/` or `/proc/` (e.g., writing `+cpu` to
  `cgroup.subtree_control` is strictly prohibited).
- **Discovery Flow**:
    1. Parse `/proc/self/mountinfo` to locate cgroup v1 controller mount points (e.g.,
       `/sys/fs/cgroup/cpu`, `/sys/fs/cgroup/cpuacct`, `/sys/fs/cgroup/cpuset`,
       `/sys/fs/cgroup/memory`, `/sys/fs/cgroup/blkio`) and cgroup v2 unified mount points
       (`/sys/fs/cgroup`).
    2. Parse `/proc/self/cgroup` to extract the process's relative cgroup path for each subsystem
       (v1) or unified hierarchy (v2 `0::/path`).
    3. Classify execution mode:
        - **cgroup v2 (unified)**: If `/proc/self/cgroup` contains `0::/path` and `/sys/fs/cgroup`
          is mounted as `cgroup2`.
        - **cgroup v1**: If `/proc/self/cgroup` contains subsystem entries (e.g.
          `5:cpu,cpuacct:/docker/123`) mapping to v1 mounts.
        - **Hybrid**: If v1 controller mounts exist alongside a v2 unified hierarchy (e.g., v1 for
          cpu/memory, v2 for PSI).
        - **Bare-Host**: If `/proc/self/cgroup` is unreadable, missing, or empty, or if
          `/sys/fs/cgroup` is not mounted.
    4. If a cgroup controller file or path is unreadable, missing, or restricted, the provider falls
       back cleanly to the parent cgroup mount point or bare-host procfs/sysfs without altering the
       scope or failing initialization.

### 3.2. Cpuset, Quota, & Unlimited Quota Calculations

- **Cpuset Parsing**: Read `cpuset.cpus.effective` (v2) or `cpuset.cpus` (v1). Parse comma-separated
  list and hyphenated range syntax (e.g., `0-3,5,8-11`) into a `BitSet` bounded by system logical
  CPU count.
- **Quota & Period Extraction**:
    - **cgroup v2**: Read `cpu.max` (`<quota> <period>` or `max <period>`).
    - **cgroup v1**: Read `cpu.cfs_quota_us` and `cpu.cfs_period_us`.
- **Unlimited Quota Handling (Defect R02 Correction)**:
    - Quota is unlimited if `cpu.max` starts with `"max"`, or quota value is `-1` / `<= 0`, or the
      quota file is absent.
    - When quota is unlimited, effective quota CPUs MUST equal the cardinality of the effective
      cpuset (`effectiveCpus.cardinality()`).
    - Legacy code divided CPU count by `100_000` period, resulting in `0.00004` CPUs for unlimited
      quota. The corrected invariant ensures:
      $$\text{quotaCpus} = \begin{cases} \text{effectiveCpus.cardinality ()}, & \text{if quota is unlimited} \\ \frac{\text{quota}}{\text{period}}, & \text{otherwise} \end{cases}$$

### 3.3. Canonical Cumulative Units & Pressure Reset Semantics

- **Unit Conventions**:
    - All time metrics MUST be converted to **nanoseconds** (`long`).
    - All memory values MUST be reported in **bytes** (`long`).
    - All PSI stall counters (`total=`) MUST be converted from microseconds to nanoseconds
      (`totalUsec * 1000L`).
- **Cumulative Counters**:
    - `cpuUsageNs`: `cpu.stat` `usage_usec * 1000` (v2), `cpuacct.usage` (v1), or `/proc/stat` total
      jiffies converted to ns.
    - `throttledNs`: `cpu.stat` `throttled_usec * 1000` (v2), `cpu.stat` `throttled_time` (v1).
    - `throttledCount`: `cpu.stat` `nr_throttled` (v1/v2).
    - PSI accumulators: `cpuSomeStallNs`, `cpuFullStallNs`, `memorySomeStallNs`,
      `memoryFullStallNs`, `ioSomeStallNs`, `ioFullStallNs`.
- **Reset & Counter Rebase**:
    - If a cumulative counter regresses or resets to 0 (e.g., container restart or 64-bit wrap), it
      MUST be rebased through P4's `CounterSignal` / `CounterDelta` mechanism. A counter reset MUST
      establish a new baseline and MUST NOT emit a false pressure spike.

### 3.4. Process vs. Host Scope & Honest Cgroup Pressure Propagation

- **Defect R14 Correction**:
    - Cgroup PSI (e.g., `cpu.pressure`) is an aggregate measurement for all tasks within the cgroup.
    - Legacy code multiplied total cgroup stall by host per-CPU jiffy fractions from `/proc/stat`
      ($\frac{\Delta \text{jiffies}_i}{\sum \Delta \text{jiffies}}$), which incorrectly attributed
      host background activity on unassigned cores as container per-CPU pressure.
    - **Honest Attribution Invariant**:
        - Aggregate cgroup pressure is applied uniformly to all effective logical CPUs assigned to
          the cgroup.
        - Host jiffy apportionment is strictly prohibited for cgroup-constrained execution.
        - In bare-host mode (no cgroup restrictions), per-CPU scheduler wait and steal pressure are
          calculated directly from `/proc/stat` per-CPU lines (`cpu0`, `cpu1`, ...).
        - A specific fixture MUST verify that host background jiffies on CPU 0 do not alter cgroup
          pressure attributed to CPU 1.

### 3.5. Sparse & Offline Multisocket Topology

- **Defect T02 Correction**:
    - Parse sysfs CPU directories `/sys/devices/system/cpu/cpuX/` where $X$ represents the OS CPU
      ID.
    - Read topology attributes:
      `/sys/devices/system/cpu/cpuX/topology/{physical_package_id, die_id, core_id}`.
    - Global Core Uniqueness: Construct compound tuple keys `(packageId, dieId, coreId)` to
      guarantee that cores with the same local `core_id` on different physical packages or dies
      remain distinct global cores.
    - Sparse CPU Handling: Logical CPU IDs retain their OS CPU IDs (e.g., CPU 0, 2, 8, 16). Indexed
      arrays use the span $[0, \text{maxCpuId}]$ with `null` holes for offline or unmapped CPU IDs.
    - Missing Cache Fallbacks: Parse `/sys/devices/system/cpu/cpuX/cache/indexY/`. If cache sysfs
      nodes are missing or malformed, apply P2's exact core-local L1/L2 and socket-local L3
      fallbacks.

### 3.6. Complete Bounded File Reads, Channel Safety, & Rate-Limited Diagnostics

- **Defect R11 Correction**:
    - **Channel Safety**: All file reads MUST use `try-with-resources` or explicit channel closure.
      No `FileChannel` or file descriptor may be leaked across evaluation ticks.
    - **Complete Bounded Reads**: Files in `/proc` and `/sys` (e.g., `/proc/stat`, `/proc/meminfo`,
      `/proc/diskstats`, `memory.stat`, `io.stat`) can exceed 64 KiB or single-buffer boundaries.
      Reads MUST use a reusable direct or heap `ByteBuffer` (64 KiB to 256 KiB) and loop
      `FileChannel.read()` until EOF (`-1`) to prevent line truncation.
    - **Rate-Limited Diagnostics**: If a procfs/sysfs/cgroup path is missing or unreadable,
      diagnostic logging MUST be rate-limited to at most once per 60 seconds per path, preventing
      200 ms poll tick log spam.

### 3.7. Block-Device Filtering & Disk Pressure

- **Defect R06 Correction**:
    - Read `/proc/diskstats` or sysfs `/sys/block/`.
    - **Excluded Devices**: Loop devices (`loop*`, major 7), RAM disks (`ram*`, `zram*`, major 1),
      virtual block devices (`dm-*` unless backed by physical storage), and optical drives (`sr*`).
    - **Included Devices**: Physical block devices and partitions: `sd*` (SATA/SAS), `nvme*` (NVMe),
      `vd*` (virtio-blk), `xvd*` (Xen), `mmcblk*` (SD/eMMC), `md*` (software RAID), and `mapper/*`
      (LVM physical volumes).
    - **Pressure vs Telemetry**: I/O read/write bytes per second are retained as pure telemetry. I/O
      stall/wait ticks (`field 10` / `field 11` in `/proc/diskstats` or `io.pressure`) represent I/O
      contention pressure.

### 3.8. Signal Cadences & Validity Tracking

- **Fast Cadence (200 ms)**: CPU usage, quota throttle, cgroup PSI (cpu, memory, io), procfs
  `/proc/stat` (steal, iowait), `/proc/meminfo` (headroom, reclaim).
- **Slow Cadence (5 s)**: CPU frequency (`cpufreq/scaling_cur_freq`, `cpuinfo_max_freq`) and thermal
  zone temperatures (`/sys/class/thermal/thermal_zone*/temp` or
  `/sys/class/hwmon/hwmon*/temp*_input`).
- **Validity Tracking**: Every raw signal MUST maintain an explicit `SignalValidity` state (`VALID`,
  `STALE`, `UNSUPPORTED`). Missing sysfs nodes (e.g. absent thermal zone) transition to
  `UNSUPPORTED` and contribute neutrally ($0.0$) without triggering exceptions or errors.

### 3.9. Stable Linux Affinity Syscalls & Lease Contract

- **Direct Linux Syscalls**:
    - `sys_sched_setaffinity`: Syscall 203 (x86-64), 122 (AArch64).
    - `sys_sched_getaffinity`: Syscall 204 (x86-64), 123 (AArch64).
    - `sys_getcpu` / `sched_getcpu`: Syscall 309 (x86-64), 168 (AArch64) or VDSO fallback.
    - `prctl(PR_SET_TIMER_SLACK)`: Syscall 157 (x86-64), 167 (AArch64) with
      `PR_SET_TIMER_SLACK = 29`.
- **Lease Contract**:
    - Before applying a thread affinity mask, `sys_sched_getaffinity(0, ...)` captures the thread's
      original mask.
    - On lease release/close, `sys_sched_setaffinity(0, ...)` restores the original mask.

### 3.10. Kernel Floor Derivation & libc Portability Proof

- **Kernel Floor Derivation**:
    - Required Linux System Calls: `sys_sched_setaffinity`, `sys_sched_getaffinity`, `sys_getcpu`,
      `sys_prctl`, `sys_openat`, `sys_read`, `sys_close`.
    - System call availability: All named syscalls have been stable in the Linux kernel since kernel
      2.6.28 (`PR_SET_TIMER_SLACK` added in 2.6.28).
    - Practical Runtime Floor: **Linux 3.10** (standard baseline for enterprise distributions like
      RHEL 7 / CentOS 7).
    - Kernel floor requirement: **Linux 3.10** is proven as the lowest practical floor. Runtime
      features in newer kernels (cgroup v2 in 3.16+/4.5+, PSI in 4.20+) are feature-detected at
      runtime and fall back gracefully on Linux 3.10.
- **libc Portability Proof**:
    - Direct Linux syscall numbers are stable kernel ABI.
    - Dynamic JNI library loading requires standard C ABI dynamic export symbols (`JNIEXPORT`,
      `JNI_OnLoad`).
    - P1 settled dual ELF JNI artifacts: `glibc 2.17` (`x86_64-linux-gnu.2.17`,
      `aarch64-linux-gnu.2.17`) and `musl` (`x86_64-linux-musl`, `aarch64-linux-musl`).
    - Gate policy enforcement: Forbidden symbols include `libstdc++`, `libc++`, and `libgcc_s`.
      Compilation uses `-fno-exceptions -fno-rtti` to eliminate C++ runtime overhead.
    - Imported symbol allowlist: `syscall`, `errno`, `memset`, `memcpy`, `JNI_*`.

## 4. Data Surface, Package Ownership, & Data Flow

### 4.1. Package & Source Ownership

- **Java Sources**: `io.euhedral_execution.hardware_utils.linux.*`
    - `LinuxSystemLayout`: Topology provider, sysfs parser, global core classification.
    - `LinuxResourceProvider`: Read-only cgroup v1/v2/hybrid/bare-host metrics provider, complete
      bounded file reads, block-device filter, PSI parser.
    - `LinuxAffinity`: Native JNI facade, affinity leases, timer slack.
    - `LinuxAffinityCalls`: Little-endian mask validation and raw call dispatcher.
    - `LinuxPaths`: Read-only cgroup and procfs path resolver.
- **Native Sources**: `euhedral-hardware-utils/src/main/native/linux/`
    - `linux_jni.h`: ABI header definitions.
    - `linux_affinity.cpp`: JNI implementations of `setThreadAffinity`, `getCpu`, and `prctl`.
- **Manifest & CI Metadata**: `native-products.json`, `.github/workflows/` Linux smoke matrix.

### 4.2. File-to-Sample Data Flow

```text
sysfs / procfs / cgroup files
(/sys/devices/system/cpu/, /sys/fs/cgroup/, /proc/stat, /proc/meminfo, /proc/diskstats)
                      |
                      v
      Complete Bounded File Read (FileChannel + Direct ByteBuffer)
                      |
                      v
        LinuxResourceProvider / LinuxSystemLayout
                      |
                      v
       Internal Raw Hardware Sample (Canonical ns/bytes, SignalValidity)
                      |
                      v
           SampleStateEngine (P4 Delta & Rebase Engine)
                      |
                      v
          PressureEvaluator / SystemUtilization (P4 Composite Pressure)
```

## 5. Bounded Implementation Context Envelopes

### 5.1. Child A: Linux Topology & Sysfs Parsing (`phase-5-linux-topology`)

- **Required Inputs**: P2 Topology Model contracts (`TopologyInput`, `LogicalCpu`,
  `TopologyBootstrap`), sysfs CPU fixtures.
- **Owned Outputs**: `LinuxSystemLayout`, sparse OS CPU ID mapping, compound
  `(packageId, dieId, coreId)` global core uniqueness, cache fallbacks, P/E classification.

### 5.2. Child B: Linux Cgroup & Resource Metrics Provider (`phase-5-linux-resources`)

- **Required Inputs**: P4 Sampling Engine (`DetailedSystemSnapshotProvider`, `FastHardwareSample`,
  `SlowHardwareSample`, `SignalValidity`), `/proc` and `/sys/fs/cgroup` fixtures.
- **Owned Outputs**: `LinuxResourceProvider`, `LinuxPaths`, read-only cgroup v1/v2/hybrid/bare-host
  discovery, complete bounded file reads, rate-limited diagnostics, block-device filter,
  cgroup-aggregate pressure propagation without host jiffy apportionment.

### 5.3. Child C: Linux Native ABI, Syscalls, & Affinity (`phase-5-linux-affinity-native`)

- **Required Inputs**: P3 Affinity contracts (`ThreadPinner`, `AffinityCapability`,
  `AffinityMasks`), P1 Zig native build graph (`native-products.json`).
- **Owned Outputs**: `LinuxAffinity`, `LinuxAffinityCalls`, `linux_affinity.cpp`, `linux_jni.h`,
  direct syscall wrappers (`sys_sched_setaffinity`, `sys_sched_getaffinity`, `sys_getcpu`,
  `sys_prctl`), Linux 3.10 kernel floor verification, glibc 2.17 / musl binary gates, affinity lease
  capture/restoration.

## 6. Sizing & Split Gate Assessment

### 6.1. Sizing Evaluation

Evaluating Phase 5 against the workflow sizing gate:

1. **Context Load**: Combining sysfs topology parsing, cgroup v1/v2/hybrid/bare-host discovery,
   procfs/sysfs metrics parsing, PSI, block device filtering, JNI C++ direct syscalls, and binary
   gate validation exceeds the working memory of a single non-frontier implementation agent.
2. **Independent Responsibilities**: Topology discovery, cgroup/resource collection, and native
   affinity/syscalls have clear package and operational boundaries.
3. **Independent Validation**: Topology parsing can be fully validated via sysfs directory fixtures;
   resource collection via procfs/cgroup file fixtures; and native affinity via JNI boundary mocks
   and Linux runner smoke tests.

### 6.2. Action Plan & Child Branches

Phase 5 is split into three responsibility-scoped child action items:

1. **P5-A (Linux Topology & Sysfs Parsing)**:
    - Branch: `hardware-utils-overhaul/phase-5-linux-topology-blueprint`
    - Blueprint: `docs/blueprints/hardware-utils/phase-5-linux-topology-model.md`
    - Implementation: `hardware-utils-overhaul/phase-5-linux-topology-implementation`
    - Audit: `docs/audits/hardware-utils/phase-5-linux-topology-model-conformance.md`
2. **P5-B (Linux Cgroup & Resource Metrics Provider)**:
    - Branch: `hardware-utils-overhaul/phase-5-linux-resources-blueprint`
    - Blueprint: `docs/blueprints/hardware-utils/phase-5-linux-resource-provider.md`
    - Implementation: `hardware-utils-overhaul/phase-5-linux-resources-implementation`
    - Audit: `docs/audits/hardware-utils/phase-5-linux-resource-provider-conformance.md`
3. **P5-C (Linux Native ABI, Syscalls, & Affinity)**:
    - Branch: `hardware-utils-overhaul/phase-5-linux-affinity-native-blueprint`
    - Blueprint: `docs/blueprints/hardware-utils/phase-5-linux-affinity-native.md`
    - Implementation: `hardware-utils-overhaul/phase-5-linux-affinity-native-implementation`
    - Audit: `docs/audits/hardware-utils/phase-5-linux-affinity-native-conformance.md`
4. **Root Phase Audit**:
    - Branch: `hardware-utils-overhaul/phase-5-linux-audit`
    - Audit: `docs/audits/hardware-utils/phase-5-linux-platform-conformance.md`

Only after this parent blueprint child is merged may child branches be created from the updated P5
root. Each child blueprint must rerun the sizing gate.

## 7. Mandatory Implementation Model Reassessment

Reassessing implementation model requirements across the child responsibilities:

- **Child P5-A (Topology)**: High context requirements around sparse array indexing, compound tuple
  keys, cache fallbacks, and sysfs parsing. Selected implementation model: **`gpt-5.6-sol` with
  `high` reasoning**.
- **Child P5-B (Resources)**: Coupled state machine across cgroup v1/v2/hybrid/bare-host, procfs
  parsing, bounded buffer channels, rate-limited logging, block-device filtering, and PSI rebase.
  Selected implementation model: **`gpt-5.6-sol` with `high` reasoning**.
- **Child P5-C (Native ABI & Affinity)**: JNI array handling, direct Linux system calls, errno
  translation, kernel floor verification, glibc 2.17 / musl binary gates, and affinity lease
  restoration. Selected implementation model: **`gpt-5.6-sol` with `high` reasoning**.
- **Child & Root Audits**: Strong coding/audit model: **`gpt-5.6-sol` with `high` reasoning**.

Downgrading to low or medium reasoning is not supported due to coupled Linux system call semantics,
memory safety, and cgroup discovery rules.

## 8. Developer-Review Summary

| Item                   | Details                                                                                                                                                                                                                                                                                                                                                                       |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Purpose**            | Deliver read-only cgroup v1/v2/hybrid/bare-host resource collection, sparse multisocket Linux topology parsing, direct syscall affinity, and glibc 2.17 / musl ABI portability on Linux 3.10+.                                                                                                                                                                                |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.linux.*` (Java), `src/main/native/linux/*` (C++), `native-products.json` (Manifest).                                                                                                                                                                                                                                                    |
| **Key Contracts**      | Read-only discovery (zero controller writes); unlimited quota equals effective cpuset cardinality; honest cgroup pressure propagation without host jiffy apportionment; Linux 3.10 kernel floor; glibc 2.17 + musl dual ELF artifacts without C++ runtimes; complete bounded file reads with channel cleanup; block-device loop filter; 60 s rate-limited diagnostic logging. |
| **Child Action Items** | P5-A (Topology), P5-B (Resources), P5-C (Affinity & Native ABI).                                                                                                                                                                                                                                                                                                              |
| **Selected Model**     | `gpt-5.6-sol` with `high` reasoning for all implementation and audit action items.                                                                                                                                                                                                                                                                                            |
| **Principal Risks**    | Sysfs path variations across Linux distros; cgroup v1 vs v2 permission differences; host vs container CPU ID mismatches; JNI array pin safety.                                                                                                                                                                                                                                |
| **Unresolved Items**   | None. Cgroup scope, units, file-read bounds, device filters, sensor cadences, syscalls, libc targets, and fallbacks are fully settled.                                                                                                                                                                                                                                        |
