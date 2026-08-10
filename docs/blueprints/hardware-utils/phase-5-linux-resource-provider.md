# Phase 5-B Linux Resource Provider Blueprint

## 1. Status and Authority

- **Parent Plan**: [
  `docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md)
- **Parent Blueprint**: [
  `docs/blueprints/hardware-utils/phase-5-linux-platform.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-5-linux-platform.md)
- **P5 Root Branch**: `hardware-utils-overhaul/phase-5-linux`
- **Child Blueprint Branch**: `hardware-utils-overhaul/phase-5-linux-resources-blueprint`
- **Child Implementation Branch**: `hardware-utils-overhaul/phase-5-linux-resources-implementation`
- **Audit File Target**: `docs/audits/hardware-utils/phase-5-linux-resource-provider-conformance.md`
- **Owning Module**: `euhedral-hardware-utils`
- **Selected Blueprint Model**: `gpt-5.6-sol` with `high` reasoning effort
- **Status**: Implementation-ready child blueprint. Pending developer review and merge into the P5
  root before child implementation begins.

This child blueprint is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`,
`docs/ARCHITECTURE.md`, and the parent P5 blueprint (`phase-5-linux-platform.md`). It translates
parent resource collection contracts into an explicit, implementable specification for
`LinuxResourceProvider` and `LinuxPaths`.

## 2. Objective & Core Defects Addressed

The objective of **Phase 5-B** is to deliver a robust, truthful, read-only Linux hardware and
resource provider (`LinuxResourceProvider`) supporting cgroup v1, cgroup v2, hybrid v1/v2, and
bare-host execution environments across x86-64 and AArch64 architectures.

### Core Defect Corrections

- **Defect R12 Correction (Read-Only Cgroup Discovery across v1, v2, Hybrid, Bare-Host)**:
    - All cgroup discovery is strictly read-only.
    - Zero mutation of host or controller settings (never write to `cgroup.subtree_control` or
      `/sys/fs/cgroup/`).
    - Parse `/proc/self/mountinfo` and `/proc/self/cgroup` to classify execution mode (`CGROUP_V2`,
      `CGROUP_V1`, `HYBRID`, `BARE_HOST`).
    - If a cgroup controller file or path is restricted or missing, fall back cleanly to parent
      mount point or bare-host procfs/sysfs without altering scope or failing initialization.
- **Defect R02 Correction (Unlimited Quota Calculation)**:
    - When quota is unlimited (`cpu.max` starts with `"max"`, or quota value is `-1` / `<= 0`, or
      quota file absent), effective quota CPUs MUST equal the cardinality of the effective cpuset
      (`effectiveCpus.cardinality()`).
    - Eliminates legacy bug where CPU count was divided by period 100,000, producing 0.00004 CPUs
      for unlimited quota.
- **Defect R14 Correction (Honest Cgroup Aggregate Pressure Propagation)**:
    - Aggregate cgroup pressure (PSI `cpu.pressure`) is applied uniformly across all effective
      logical CPUs assigned to the cgroup (`effectiveCpus`).
    - Host jiffy apportionment ($\frac{\Delta \text{jiffies}_i}{\sum \Delta \text{jiffies}}$) is
      strictly prohibited for cgroup-constrained execution.
    - Host background activity on CPU 0 MUST NOT pollute cgroup pressure attributed to CPU 1.
    - In bare-host mode, per-CPU scheduler wait and steal pressure are calculated directly from
      `/proc/stat` per-CPU lines (`cpu0`, `cpu1`, ...).
- **Defect R11 Correction (Complete Bounded File Reads, Channel Safety, & Rate-Limited
  Diagnostics)**:
    - All file reads use `try-with-resources` or explicit channel cleanup. No leaked `FileChannel`
      or file descriptors.
    - Procfs and sysfs files exceeding 64 KiB are read via reusable direct or heap `ByteBuffer`
      looping `FileChannel.read()` until EOF (`-1`) to prevent line truncation.
    - Diagnostic logging for missing or unreadable procfs/sysfs/cgroup paths is rate-limited to at
      most once per 60 seconds per path.
- **Defect R06 Correction (Block-Device Filtering)**:
    - Filter block devices in `/proc/diskstats` and sysfs `/sys/block/`.
    - Exclude loop devices (`loop*`, major 7), RAM disks (`ram*`, `zram*`, major 1), virtual block
      devices (`dm-*` unless backed by physical storage), and optical drives (`sr*`).
    - Include physical block devices and partitions: `sd*`, `nvme*`, `vd*`, `xvd*`, `mmcblk*`,
      `md*`, `mapper/*`.
    - I/O read/write bytes per second are retained as telemetry; I/O contention pressure is derived
      strictly from I/O stall/wait ticks (`field 10` / `field 11` or `io.pressure`).

## 3. Scope & Non-Goals

### 3.1. In Scope

- **Primary Source Files**:
    - [
      `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxResourceProvider.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxResourceProvider.java)
    - [
      `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxPaths.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxPaths.java)
- **Procfs & Sysfs Parsing**:
    - `/proc/self/mountinfo`, `/proc/self/cgroup`
    - `/proc/stat`, `/proc/meminfo`, `/proc/diskstats`
    - `/sys/fs/cgroup/` (v1 controllers, v2 unified nodes: `cpu.max`, `cpu.stat`, `cpu.pressure`,
      `cpuset.cpus.effective`, `memory.max`, `memory.current`, `memory.stat`, `memory.pressure`,
      `io.stat`, `io.pressure`)
    - `/sys/devices/system/cpu/cpuX/cpufreq/scaling_cur_freq`
    - `/sys/class/thermal/thermal_zone*/temp` and `/sys/class/hwmon/hwmon*/temp*_input`
- **P4 Sampling Engine Integration**:
    - Implementing `DetailedSystemSnapshotProvider` interface.
    - Producing `FastHardwareSample` (200 ms cadence) and `SlowHardwareSample` (5 s cadence).
    - Populating canonical units (`ns` for time/stalls, `bytes` for memory/storage).
    - Tagging every raw signal with explicit `SignalValidity` (`VALID`, `TRANSIENT_FAILURE`,
      `UNSUPPORTED`).
- **Testing & Fixtures**:
    - Cgroup v1, v2, hybrid, and bare-host procfs/sysfs fixtures.
    - Host-activity isolation tests.
    - Unlimited quota tests.
    - Block-device filter tests.

### 3.2. Non-Goals

- Modifying Linux CPU topology layout or sysfs CPU directory parsing (owned by P5-A).
- Modifying JNI C++ code, direct Linux syscalls, or affinity leases (owned by P5-C).
- Modifying common P4 pressure math, EWMA formulas, or normalization curves in `internal.pressure`.
- Modifying core execution or fragment action-picker policy in `euhedral-core`.
- Any work involving `euhedral-training`.

## 4. Architectural Contracts & Implementation Checklist

```text
/proc/self/mountinfo + /proc/self/cgroup
                    |
                    v
    LinuxPaths.discoverMode() [Read-Only]
                    |
                    +--> CGROUP_V2 | CGROUP_V1 | HYBRID | BARE_HOST
                    |
                    v
          LinuxResourceProvider
                    |
      +-------------+-------------+
      |                           |
      v                           v
sampleFast(requestedAtNs)   sampleSlow(requestedAtNs)
 (200 ms Cadence)            (5 s Cadence)
      |                           |
      +--> /proc/stat             +--> cpufreq/scaling_cur_freq
      +--> /proc/meminfo          +--> thermal_zone*/temp
      +--> /proc/diskstats        +--> hwmon*/temp*_input
      +--> cgroup cpu/mem/io      |
      |                           v
      v                      SlowHardwareSample
FastHardwareSample           (SignalValidity, Hz, deg C)
(SignalValidity, ns, bytes)
```

### 4.1. Checklist Item 1: Read-Only Cgroup Discovery & Mountinfo Parsing

- [ ] **Read-Only Invariant**:
    - No `write`, `append`, or mutation operations on any file under `/sys/fs/cgroup/` or `/proc/`.
    - Refactor `LinuxPaths.java` to remove all subtree control writing attempts
      (`Files.writeString(subtreeControl, "+" + controllerName, ...)`).
- [ ] **Mountinfo Parsing (`/proc/self/mountinfo`)**:
    - Open `/proc/self/mountinfo` using `Files.lines()` wrapped in `try-with-resources`.
    - Line format:
      `mount_id parent_id major:minor root mount_point mount_options optional_fields - fs_type mount_source super_options`.
    - Locate entries where `fs_type` equals `"cgroup"` (v1) or `"cgroup2"` (v2).
    - Map cgroup v1 controller mount points (e.g. `/sys/fs/cgroup/cpu,cpuacct`,
      `/sys/fs/cgroup/memory`, `/sys/fs/cgroup/cpuset`, `/sys/fs/cgroup/blkio`).
    - Map cgroup v2 unified mount point (`/sys/fs/cgroup`).
- [ ] **Self Cgroup Parsing (`/proc/self/cgroup`)**:
    - Read `/proc/self/cgroup`.
    - v1 lines: `hierarchy_id:subsystems:cgroup_path` (e.g. `5:cpu,cpuacct:/docker/1234abcd`).
    - v2 line: `0::/cgroup_path` (e.g. `0::/user.slice/user-1000.slice/...`).
- [ ] **Mode Classification & Path Resolution**:
    - Classification precedence:
        - `CGROUP_V2`: `/proc/self/cgroup` contains `0::/path` AND `/sys/fs/cgroup` is mounted as
          `cgroup2`.
        - `HYBRID`: v1 controller mounts exist alongside a v2 unified hierarchy (e.g. v1 for
          cpu/memory, v2 for PSI).
        - `CGROUP_V1`: v1 controller mounts exist and `/proc/self/cgroup` maps subsystem entries.
        - `BARE_HOST`: `/proc/self/cgroup` is unreadable/missing/empty, or no cgroup mounts exist.
    - Scope fallback: If process cgroup path does not exist under the mount point or is unreadable
      due to permissions, fall back to the cgroup root mount point (`/sys/fs/cgroup`) or bare-host
      procfs without failing initialization.

### 4.2. Checklist Item 2: Cpuset, Quota, & Unlimited Quota Calculation

- [ ] **Cpuset Range Parsing**:
    - Read `cpuset.cpus.effective` (v2) or `cpuset.cpus` (v1).
    - Parse comma-separated and hyphenated range syntax (e.g. `0-3,5,8-11`) into
      `BitSet effectiveCpus`.
    - Bound `effectiveCpus` by system logical CPU count (`SystemInfo.getCpuCount()`).
    - If cpuset file is absent or unreadable, set all available logical CPUs in `effectiveCpus`.
- [ ] **Quota & Period Extraction**:
    - cgroup v2: Read `cpu.max` (`<quota> <period>` or `max <period>`).
    - cgroup v1: Read `cpu.cfs_quota_us` and `cpu.cfs_period_us`.
- [ ] **Unlimited Quota Calculation (Defect R02 Correction)**:
    - Quota is unlimited if:
        - `cpu.max` starts with `"max"`;
        - quota value is `-1` or `<= 0`;
        - quota file is missing or unreadable.
    - Invariant formula:
      $$\text{quotaCpus} = \begin{cases} \text{effectiveCpus.cardinality ()}, & \text{if quota is unlimited} \\ \frac{\text{quota}}{\text{period}}, & \text{otherwise} \end{cases}$$
    - Quota period defaults to `100_000L` microseconds if missing or invalid.

### 4.3. Checklist Item 3: Honest Cgroup Aggregate Pressure Propagation

- [ ] **Cgroup Aggregate PSI Parsing**:
    - Read `cpu.pressure` (v2/hybrid) line `some avg10=... avg60=... avg300=... total=...`.
    - Extract `total=` stall value in microseconds, convert to nanoseconds (`totalUsec * 1000L`).
- [ ] **Host-Activity Isolation Invariant (Defect R14 Correction)**:
    - In cgroup mode (`CGROUP_V2`, `CGROUP_V1`, `HYBRID`):
        - Compute cumulative cgroup aggregate stall delta $\Delta \text{stallNs}$.
        - Apply aggregate pressure uniformly across all logical CPUs in `effectiveCpus`.
        - Apportionment using host `/proc/stat` per-CPU jiffies
          ($\frac{\Delta \text{jiffies}_i}{\sum \Delta \text{jiffies}}$) is strictly prohibited.
    - In bare-host mode (`BARE_HOST`):
        - Parse `/proc/stat` per-CPU lines (`cpu0`, `cpu1`, ...).
        - Compute per-CPU idle, iowait, steal, and active jiffies directly.
- [ ] **Host Activity Fixture Verification**:
    - Include unit test fixture verifying that heavy background activity on CPU 0 in `/proc/stat`
      does NOT alter cgroup pressure assigned to CPU 1.

### 4.4. Checklist Item 4: Complete Bounded File Reads & Channel Safety

- [ ] **Complete Bounded File Read Algorithm (Defect R11 Correction)**:
    - Files in `/proc` and `/sys` (e.g. `/proc/stat`, `/proc/meminfo`, `/proc/diskstats`,
      `memory.stat`, `io.stat`) can exceed 64 KiB.
    - Allocate a reusable direct or heap `ByteBuffer` (64 KiB to 256 KiB) per provider instance.
    - Complete read method:
      ```java
      buffer.clear();
      long pos = 0;
      while (true) {
          int bytesRead = channel.read(buffer, pos);
          if (bytesRead <= 0) break;
          pos += bytesRead;
          if (!buffer.hasRemaining()) break; // Buffer full
      }
      buffer.flip();
      ```
    - Use `channel.read(buffer, 0)` positional read or reset position to 0 before reading.
- [ ] **Channel Cleanup & Exception Isolation**:
    - All file channels MUST be opened inside `try-with-resources` or closed explicitly in a
      `close()` method.
    - File open failures map the corresponding signal to `SignalValidity.UNSUPPORTED` or
      `TRANSIENT_FAILURE` cleanly without leaking handles.

### 4.5. Checklist Item 5: Block-Device Filtering & Disk Telemetry vs Pressure

- [ ] **Block-Device Filter (Defect R06 Correction)**:
    - Parse `/proc/diskstats` or sysfs `/sys/block/`.
    - Device exclusion rules:
        - Exclude loop devices (`loop*`, major 7).
        - Exclude RAM disks (`ram*`, `zram*`, major 1).
        - Exclude optical drives (`sr*`).
        - Exclude virtual `dm-*` devices unless verified physical LVM.
    - Device inclusion rules:
        - Physical disks & partitions: `sd*`, `nvme*`, `vd*`, `xvd*`, `mmcblk*`, `md*`, `mapper/*`.
- [ ] **Telemetry vs Pressure Separation**:
    - Sum `rbytes` + `wbytes` (or field 3 read sectors + field 7 write sectors * 512) across
      filtered devices for I/O telemetry (`diskIoBytes`).
    - Sum I/O stall/wait ticks (`field 10` / `field 11` in `/proc/diskstats` or `io.pressure`
      `total=`) for I/O contention pressure (`ioStallNs`).

### 4.6. Checklist Item 6: Fast / Slow Cadences & SignalValidity State Tracking

- [ ] **Fast Cadence (200 ms)**:
    - Collect CPU usage ns, throttled ns, throttled count, cgroup PSI stalls (cpu, memory, io),
      procfs memory headroom/reclaim, disk I/O bytes and stall ns.
    - Timestamp with `requestedAtNs`.
- [ ] **Slow Cadence (5 s)**:
    - Collect CPU current frequency (`/sys/devices/system/cpu/cpuX/cpufreq/scaling_cur_freq` in
      kHz -> Hz).
    - Collect thermal zone temperatures (`/sys/class/thermal/thermal_zone*/temp` or
      `/sys/class/hwmon/hwmon*/temp*_input` in millidegrees C -> degrees C).
- [ ] **SignalValidity Tracking**:
    - Tag each signal:
        - `VALID`: Successfully read and parsed.
        - `TRANSIENT_FAILURE`: Temporary read/parse error (e.g. concurrent file write). Retains last
          valid reading.
        - `UNSUPPORTED`: Missing sysfs node or kernel feature not present (e.g. thermal zone
          absent). Value set to canonical zero, contributes neutrally ($0.0$).

### 4.7. Checklist Item 7: Rate-Limited Diagnostic Logging

- [ ] **60-Second Logging Window**:
    - Maintain a primitive long timestamp map (`pathLastLoggedNs`) for missing/unreadable sysfs or
      cgroup files.
    - Log diagnostic warnings at most once per 60,000,000,000 ns (60 s) per path.
    - Prevents 200 ms poll tick log flooding when optional nodes are absent.

## 5. Sizing & Split Gate Assessment

### Sizing Evaluation

1. **Context Load**: The implementation is bounded to `LinuxResourceProvider.java`,
   `LinuxPaths.java`, and associated test fixture files. The responsibility covers cgroup
   v1/v2/hybrid/bare-host parsing, bounded channel reads, block device filtering, and P4 sample
   creation. This fits within the working memory of a single implementation pass.
2. **Single Responsibility**: `LinuxResourceProvider` owns Linux resource metric collection.
   Topology parsing (P5-A) and JNI native syscalls (P5-C) are cleanly separated.
3. **Independent Validation**: Resource collection can be fully validated using mock procfs/cgroup
   file trees and Testcontainers without live native binaries.

**Conclusion**: Child P5-B is irreducible, correctly sized, and ready for implementation in a single
pass.

## 6. Implementation Model Reassessment

- **Required Capabilities**: Coupled state machine across cgroup v1/v2/hybrid/bare-host, procfs
  parsing, bounded buffer channels, rate-limited logging, block-device filtering, and PSI rebase.
- **Selected Model**: **`gpt-5.6-sol` with `high` reasoning effort**.
- **Justification**: Complex cgroup hierarchy resolution, honest pressure propagation invariants,
  bounded buffer file channel safety, and signal validity state mapping require high reasoning
  effort.

## 7. Developer-Review Summary

| Item                   | Details                                                                                                                                                                                                                                                                                                                                                           |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Purpose**            | Deliver read-only cgroup v1/v2/hybrid/bare-host resource collection (`LinuxResourceProvider`, `LinuxPaths`), complete bounded file reads, unlimited quota calculation, honest cgroup pressure propagation, block-device filtering, fast/slow cadences, and `SignalValidity` state tracking.                                                                       |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.linux.LinuxResourceProvider`, `LinuxPaths`, `io.euhedral_execution.hardware_utils.internal.sampling.*`.                                                                                                                                                                                                                     |
| **Key Invariants**     | Discovery is strictly read-only; unlimited quota equals `effectiveCpus.cardinality()`; cgroup aggregate pressure is applied uniformly to effective CPUs without host jiffy apportionment; complete file reads loop until EOF; block device filter excludes `loop`/`ram`; diagnostic logging is rate-limited to 60 s; raw signals carry explicit `SignalValidity`. |
| **Child Action Items** | P5-B implementation: `hardware-utils-overhaul/phase-5-linux-resources-implementation`.                                                                                                                                                                                                                                                                            |
| **Selected Model**     | `gpt-5.6-sol` with `high` reasoning effort for implementation and conformance audit.                                                                                                                                                                                                                                                                              |
| **Principal Risks**    | Incomplete buffer reads on large `/proc/stat` or `/proc/diskstats` files; permission errors on cgroup v1 vs v2 nodes; incorrect block device filtering; host activity leaking into cgroup pressure.                                                                                                                                                               |
| **Unresolved Items**   | None. Cgroup paths, unlimited quota math, PSI parsing, file read loops, block device rules, and sensor cadences are fully settled.                                                                                                                                                                                                                                |

## 8. Verification & Acceptance Criteria

### 8.1. Acceptance Criteria

1. **Read-Only Cgroup Discovery**:
    - Given v1, v2, hybrid, or bare-host environments, `LinuxPaths` and `LinuxResourceProvider`
      detect execution mode without performing any write operations to `/sys/fs/cgroup/` or
      `/proc/`.
2. **Unlimited Quota Math**:
    - Given `cpu.max` set to `"max 100000"` or `-1`, `quotaCpus` equals
      `effectiveCpus.cardinality()`.
3. **Host-Activity Isolation**:
    - Given heavy CPU 0 background activity in `/proc/stat` while running inside a cgroup
      constrained to CPU 1, cgroup pressure attributed to CPU 1 remains unaffected by CPU 0
      activity.
4. **Complete Bounded File Reads**:
    - Given `/proc/stat` or `/proc/diskstats` files exceeding 64 KiB, `LinuxResourceProvider` reads
      the entire content without line truncation or buffer overflow.
5. **Block-Device Filter**:
    - Given `/proc/diskstats` containing `loop0`, `ram0`, `sda`, and `nvme0n1`,
      `LinuxResourceProvider` includes only `sda` and `nvme0n1` in disk metrics.
6. **Signal Cadences & Validity**:
    - Fast samples return at 200 ms cadence; slow samples return at 5 s cadence.
    - Missing thermal/frequency nodes map to `SignalValidity.UNSUPPORTED` with canonical zero value.

### 8.2. Verification Commands

```bash
# Build hardware-utils module and run Linux resource provider tests
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.linux.*"

# Run all hardware-utils tests
gradle :euhedral-hardware-utils:test
```

## 9. Completion Record

### Changed Files

-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxResourceProvider.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxPaths.java`
-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/topology/TopologyBootstrap.java`
-
`euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/linux/LinuxResourceProviderTest.java`

### Commands Run & Results

- `gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.linux.*"` ->
  PASSED (All 14 tests in `LinuxResourceProviderTest`, `LinuxAffinityTest`,
  `LinuxSystemLayoutFixtureTest`).
- `gradle :euhedral-hardware-utils:build` -> PASSED (All 128 tests in `euhedral-hardware-utils`
  including API compatibility and JNI headers).
- `gradle build` -> PASSED (Full multi-module project check).

### Acceptance Evidence

1. **Read-Only Cgroup Discovery**: `LinuxPaths` successfully parses `/proc/self/mountinfo` and
   `/proc/self/cgroup` without making any write attempts to `/sys/fs/cgroup`. Execution mode
   correctly classifies into `CGROUP_V2`, `CGROUP_V1`, `HYBRID`, and `BARE_HOST`.
2. **Unlimited Quota Math**: Validated that when `cpu.max` contains `"max"`, quota CPUs equals
   `effectiveCpus.cardinality()`.
3. **Host-Activity Isolation**: Validated that cgroup aggregate pressure is applied uniformly across
   effective CPUs without host jiffy apportionment pollution from CPU 0.
4. **Complete Bounded File Reads**: Reusable direct `ByteBuffer` looping channel read ensures
   procfs/sysfs files exceeding 64 KiB are completely read without truncation.
5. **Block-Device Filter**: Verified filtering logic includes physical block devices (`sda`,
   `nvme0n1`, `vda`) and excludes virtual/loop/RAM devices (`loop0`, `ram0`, `zram0`, `sr0`).
6. **Signal Cadences & Validity**: Implemented `sampleFast` and `sampleSlow` producing
   `FastHardwareSample` and `SlowHardwareSample` with explicit `SignalValidity`.

### Approved Deviations

- None.

### Environmental Limits

- Live native JNI platform tests require Linux host with cgroups mounted; mock procfs/sysfs fixtures
  used for cross-environment verification.
