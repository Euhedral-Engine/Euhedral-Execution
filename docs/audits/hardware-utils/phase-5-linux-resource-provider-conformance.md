# Phase 5-B Linux Resource Provider Conformance Audit

## Scope and Disposition

Audited `hardware-utils-overhaul/phase-5-linux-resources-audit` from the updated P5 root branch `hardware-utils-overhaul/phase-5-linux` (at commit `86b4d1d`). The audit evaluated `LinuxResourceProvider.java` and `LinuxPaths.java` against:
- Parent P5 Platform Blueprint: `docs/blueprints/hardware-utils/phase-5-linux-platform.md`
- Child P5-B Blueprint: `docs/blueprints/hardware-utils/phase-5-linux-resource-provider.md`
- Implementation commit: `86b4d1d`

Inspection covered `io.euhedral_execution.hardware_utils.linux.LinuxResourceProvider`, `LinuxPaths`, and test fixture `LinuxResourceProviderTest`.

**Disposition: Satisfied and ready for merge.** All 6 child acceptance criteria and 5 Linux core defects (R02, R06, R11, R12, R14) are classified as `satisfied`. No production or blueprint corrections are required.

## Acceptance Matrix

| Criterion | Classification | Evidence |
|-----------|----------------|----------|
| 1. Read-Only Cgroup Discovery | satisfied | `LinuxPaths` parses `/proc/self/mountinfo` and `/proc/self/cgroup` without write/append calls. Classifies execution into `CGROUP_V2`, `CGROUP_V1`, `HYBRID`, and `BARE_HOST`. Unreadable or missing paths fall back to root cgroup mount or procfs cleanly. Verified by `testCgroupV2Fixture`, `testCgroupV1Fixture`, `testHybridFixture`, `testBareHostFixture`. |
| 2. Unlimited Quota Math (R02) | satisfied | When `cpu.max` starts with `"max"`, or quota is `-1` / `<= 0`, `quotaCpus` equals `effectiveCpus.cardinality()`. Legacy division bug fixed. Verified by `testUnlimitedQuotaCalculation`. |
| 3. Host-Activity Isolation (R14) | satisfied | Cgroup PSI aggregate pressure (`cpu.pressure`) is applied uniformly across `effectiveCpus`. Host `/proc/stat` jiffy apportionment is strictly eliminated in cgroup mode. Verified by `testHostActivityIsolation`. |
| 4. Complete Bounded File Reads (R11) | satisfied | `readFileBounded` uses `try-with-resources` `FileChannel` reads with a reusable direct `ByteBuffer`, looping until EOF (`-1`). Procfs/sysfs files exceeding 64 KiB read completely without truncation or FD leaks. Log messages rate-limited to 60 s. Verified by `testLargeFileBoundedRead`. |
| 5. Block-Device Filter (R06) | satisfied | `isFilteredBlockDevice` includes physical devices (`sd*`, `nvme*`, `vd*`, `xvd*`, `mmcblk*`, `md*`, `dm-*`) and excludes loop (`loop*`), RAM (`ram*`, `zram*`), and optical drives (`sr*`). Telemetry byte totals separate from PSI I/O stall ns. Verified by `testBlockDeviceFilter`. |
| 6. Sensor Validity and Cadences | satisfied | `sampleFast` (200 ms) collects CPU, memory, and I/O signals; `sampleSlow` (5 s) collects CPU frequency and thermal temperatures. Absent or unparseable nodes report `SignalValidity.UNSUPPORTED` with canonical neutral defaults. Tested by unit test suite. |

## Detailed Technical Audit

### 1. Read-Only Cgroup Discovery (R12)
`LinuxPaths.java` inspects `/proc/self/mountinfo` and `/proc/self/cgroup` to map cgroup v1 controller mounts (`v1ControllerMounts`) and cgroup v2 unified mounts (`cgroupV2UserPath`). All file access is strictly read-only using `Files.lines()`. Zero write or append calls exist on `/sys/fs/cgroup` or `/proc`. Precedence mapping correctly resolves:
- `CGROUP_V2`: `/proc/self/cgroup` contains `0::/path` and unified mount exists.
- `HYBRID`: v1 controller mounts exist alongside v2 unified mount.
- `CGROUP_V1`: v1 controller mounts exist with non-empty subsystem paths.
- `BARE_HOST`: missing mountinfo/cgroup paths or no cgroup mounts present.

If process cgroup directories do not exist or are restricted, `resolveV2Path` and `resolveV1Path` fall back to root mount points cleanly without initialization failure.

### 2. Unlimited Quota Math (R02)
`LinuxResourceProvider.calculateQuotaCpus()` inspects `cpu.max` (v2/hybrid) or `cpu.cfs_quota_us` / `cpu.cfs_period_us` (v1). When quota is unlimited (`"max"` in `cpu.max`, quota `-1` or `<= 0`, or missing quota file), the calculation returns `effectiveCpus.cardinality()`. This eliminates the legacy defect where unlimited quota was divided by 100,000, causing inaccurate quota calculations.

### 3. Host-Activity Isolation (R14)
`LinuxResourceProvider.updateCpuPressure()` enforces strict isolation between host background tasks and cgroup metrics:
- In cgroup modes (`CGROUP_V2`, `CGROUP_V1`, `HYBRID`): reads `cpu.pressure` line `some ... total=...`, extracts stall delta in nanoseconds, and distributes `deltaStall / numEffective` uniformly across all active CPUs in `effectiveCpus`. Host `/proc/stat` jiffies are ignored.
- In `BARE_HOST` mode: per-CPU iowait and steal jiffies are parsed from `/proc/stat` per-CPU lines (`cpu0`, `cpu1`, ...) directly.

Test `testHostActivityIsolation` confirms that heavy activity on CPU 0 does not bleed into cgroup pressure attributed to CPU 1.

### 4. Complete Bounded File Reads (R11)
`LinuxResourceProvider.readFileBounded(Path path)` opens `FileChannel` in a `try-with-resources` block. It uses a reusable direct `ByteBuffer` (64 KiB) and loops `channel.read(buffer, pos)` until `-1` is returned. Read chunks are accumulated into a `ByteArrayOutputStream`. This guarantees complete reads for files exceeding 64 KiB without line truncation or buffer overflow, while closing channels automatically on exit. Warnings for missing or unreadable files are rate-limited per path to once per 60 seconds via `logRateLimited()`.

### 5. Block-Device Filter (R06)
`LinuxResourceProvider.isFilteredBlockDevice(int major, String name)` filters disk devices in `/proc/diskstats`:
- Filtered Out: major 7 (`loop*`), major 1 (`ram*`, `zram*`), and optical drives (`sr*`).
- Retained: `sd*`, `nvme*`, `vd*`, `xvd*`, `mmcblk*`, `md*`, `dm-*`.

`readFilteredDiskIoBytes()` aggregates total sector read/write bytes for telemetry. `readIoStallNs()` collects PSI `io.pressure` total stall time or diskstats field 12 I/O ticks for pressure calculation, maintaining separation between throughput telemetry and pressure metrics.

### 6. Sensor Validity & Concurrency Safety
`sampleFast` and `sampleSlow` populate `FastHardwareSample` and `SlowHardwareSample`. Unreadable hardware sensors (such as missing `scaling_cur_freq` or `thermal_zone*/temp`) map to `SignalValidity.UNSUPPORTED` with `Double.NaN` safe fallbacks.
Internal array updates are protected by a progressive spin lock (`LOCK_STATE` VarHandle CAS, `Thread.onSpinWait()`, `Thread.yield()`, `LockSupport.parkNanos(1000L)`). Multi-threaded execution was verified via `testConcurrentAccess` across 8 concurrent worker threads.

## Commands Run and Test Verification

- `gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.linux.*"` -> PASSED (14/14 tests)
- `gradle :euhedral-hardware-utils:test` -> PASSED (128/128 tests)
- `gradle build` -> PASSED (full repository multi-module check)
- `git diff --check` -> CLEAN (0 whitespace or formatting errors)

## Handoff

Branch `hardware-utils-overhaul/phase-5-linux-resources-audit` is clean and fully verified. Ready to merge into `hardware-utils-overhaul/phase-5-linux`.
