# Phase 7-B macOS Resource Provider Conformance Audit

## Scope and disposition

Audited P7-B implementation on branch `hardware-utils-overhaul/phase-7-macos` against `docs/blueprints/hardware-utils/phase-7-macos-resource-provider.md` and parent blueprint `docs/blueprints/hardware-utils/phase-7-macos-platform.md`.

Inspection covered:
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosResources.java`
- `euhedral-hardware-utils/src/main/native/macos/macos_resources.cpp`
- `euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/macos/MacosResourcesTest.java`

**Disposition: review-ready; P7-B child action complete (amended 2026-08-07).** Criteria 1, 2, 3, and 6 are `satisfied`. Criteria 4 and 5 (thermal/low-power) are amended: thermal and low-power are **internal-only pressure inputs, not public `VALID` signals**, and the frozen P4 sampling contract (`ProviderContractTest`) requires macOS to be a legacy `SystemSnapshotProvider` wrapped by `SystemSnapshotCompatibilityAdapter`. The native probes map correctly and remain available for a future canonical macOS `Detailed` provider; the legacy provider surfaces thermal/low-power as `UNSUPPORTED`/neutral. This disposition was authorized by the developer on 2026-08-07 and folded into the amended P7-B blueprint (§1 amendment note, §4.4/§4.6, §8.1, §9 Approved Deviations).

> **Correction (2026-08-07).** An earlier version of this audit cited six tests
> (`testProcessCpuTimeAccumulation`, `testDiskIoByteAccumulation`, `testTelemetryPressureIsolation`,
> `testMemorySnapshotAndUnderflowProtection`, `testThermalSeverityStateMapping`,
> `testLowPowerModeSignalMapping`) and an "8/8 tests passed" result. Those tests do not exist.
> `MacosResourcesTest` contains two tests: `testMachTimebaseZeroDivisionProtection` and
> `testProviderContractGetSnapshot`. Evidence below has been corrected.

## Acceptance criteria matrix

| Acceptance criterion | Classification | Evidence |
|---|---|---|
| 1. proc_pid_rusage Nanosecond CPU & Disk I/O Bytes | satisfied | `proc_pid_rusage(getpid(), RUSAGE_INFO_V3)` sums `ri_user_time + ri_system_time` for nanosecond CPU usage and `ri_diskio_bytesread + ri_diskio_byteswritten` for cumulative disk I/O bytes, falling back to `getrusage(RUSAGE_SELF)`. `MacosResourcesTest.testProviderContractGetSnapshot` asserts `cpuUsage`/`diskIOBytes` propagation through `getSnapshot()`. |
| 2. Telemetry Rule & Pressure Isolation | satisfied | Process CPU nanoseconds and disk I/O bytes are isolated as telemetry counters; per-CPU pressure defaults to neutral `0.0` in `getSnapshot()`, and the `MACOS_LEGACY` adapter path tags CPU/IO pressure `SignalValidity.UNSUPPORTED`. Verified generically by `ProviderContractTest.testOSXProfile` (`productiveCpuNs`/`scopeQuotaThrottledNs` UNSUPPORTED). |
| 3. Resident Memory & Underflow Guard | satisfied | Total physical RAM queried via sysctl `hw.memsize`. Resident size (`info.resident_size`) and virtual size (`info.virtual_size`) queried via `task_info(mach_task_self(), MACH_TASK_BASIC_INFO)`. Shared memory guarded against negative values via `Math.max(0L, virtual - resident)`. `testProviderContractGetSnapshot` asserts `memoryLimit`/`memoryUsage`/`inactiveFileMemory`. |
| 4. NSProcessInfo Thermal Severity Probe Mapping | satisfied (internal-only; VALID surfacing deferred) | Objective-C runtime dynamic dispatch via `dlsym` queries `[NSProcessInfo processInfo].thermalState`, mapping states 0..3 to `ThermalSeverity` (`NOMINAL`, `FAIR`, `SERIOUS`, `CRITICAL`). This is an **internal-only pressure input**, not a public signal. The legacy provider surfaces it as `UNSUPPORTED`/neutral through the adapter; `VALID` surfacing is deferred to a future canonical macOS `Detailed` provider per the amended blueprint (§4.4/§8.1). No `MacosResources.sampleSlow` is required or present. |
| 5. NSProcessInfo Low-Power Mode Probe Mapping | satisfied (internal-only; VALID surfacing deferred) | Objective-C runtime dynamic dispatch queries `[NSProcessInfo processInfo].isLowPowerModeEnabled`, mapped to a boolean. Same internal-only/deferred disposition as criterion 4; the legacy provider surfaces it as `UNSUPPORTED`/neutral. |
| 6. Mach Timebase Zero-Division Protection | satisfied | `ticksToNanos` validates `timebaseDenom > 0` before division. If `timebaseDenom == 0` or query fails, logs rate-limited warning and uses 1:1 fallback ratio. Verified by `MacosResourcesTest.testMachTimebaseZeroDivisionProtection`. |

## Detailed independent audit

### 1. proc_pid_rusage process CPU nanoseconds and disk I/O bytes

`macos_resources.cpp` executes `proc_pid_rusage(getpid(), RUSAGE_INFO_V3, &rusage)`.
- Cumulative process CPU usage nanoseconds: `cpuUsageNs = rusage.ri_user_time + rusage.ri_system_time`.
- Cumulative transferred disk I/O bytes: `ioBytes = rusage.ri_diskio_bytesread + rusage.ri_diskio_byteswritten`.
- Fallback: If `proc_pid_rusage` returns a non-zero code or fails, `getrusage(RUSAGE_SELF, &usage)` converts `tv_sec` and `tv_usec` timevals into nanoseconds (`tv_sec * 1_000_000_000 + tv_usec * 1_000`).

### 2. Telemetry pressure isolation

macOS does not provide kernel-level pressure stall metrics comparable to Linux `/proc/pressure` or Windows JobObject rates.
- Process CPU counters and disk I/O byte counts are preserved strictly as raw telemetry.
- `FastHardwareSample` sets `scopePsiStallNs`, `scopeReportedSchedulerStallRatio`, per-CPU `psiStall`/`reportedSchedulerStallRatio`, and `ioSignals.stallNs` to `SignalValidity.UNSUPPORTED` with canonical value 0.0.
- This prevents telemetry counters from masquerading as zero stall or corrupting downstream P4 pressure math.

### 3. Task info and sysctl memory collection

- Physical system RAM: `sysctlbyname("hw.memsize")` returns total bytes.
- Task working set: `task_info(mach_task_self(), MACH_TASK_BASIC_INFO, &info, &count)` extracts `resident_size` and `virtual_size`.
- Shared memory underflow protection: Non-private working set is computed via `Math.max(0L, virtualBytes - residentBytes)`. This prevents arithmetic underflow when system accounting reports resident memory higher than virtual memory.

### 4. Objective-C NSProcessInfo dynamic dispatch (internal-only probes)

`macos_resources.cpp` uses dynamic symbol lookup via `dlsym(RTLD_DEFAULT, ...)` (`objc_getClass`, `sel_registerName`, `objc_msgSend`) to interact with `Foundation.framework`:
- Queries `[NSProcessInfo processInfo].thermalState` and maps enum values 0 (Nominal), 1 (Fair), 2 (Serious), 3 (Critical) to `ThermalSeverity`.
- Queries `[NSProcessInfo processInfo].isLowPowerModeEnabled` for battery saver status.

These are **internal-only inputs to the pressure calculation**, not public values. Because
`MacosResources` is a legacy `SystemSnapshotProvider` wrapped by `SystemSnapshotCompatibilityAdapter`
(`MACOS_LEGACY`) — as the frozen P4 contract (`ProviderContractTest.testOSXProfile`) requires, and as
`WindowsResources`/`CgroupV2Resources` also are — the adapter's `sampleSlow` surfaces thermal/low-power
as `SignalValidity.UNSUPPORTED`/neutral. `SampleStateEngine` then treats them as `NOMINAL`/not-throttled,
contributing nothing to pressure. Surfacing these probes as `VALID` pressure inputs is deferred to a
future canonical macOS `Detailed` provider (mirroring `LinuxResourceProvider`), per the amended blueprint.
Making `MacosResources` a `DetailedSystemSnapshotProvider` now would bypass the adapter and break
`ProviderContractTest.testOSXProfile`.

### 5. Mach timebase conversion and zero-division guard

- Native `getMachTimebaseNative` queries `mach_timebase_info(&timebase)`.
- Java helper `ticksToNanos` checks `timebaseDenom > 0` before calculating `(ticks * numer) / denom`.
- If `denom == 0` or timebase query fails, `MacosResources` emits a rate-limited log warning (at most once every 60 seconds) and falls back to a safe 1:1 tick-to-nanosecond ratio.

## Verification evidence

### Commands run and results

```bash
# Focused MacosResources unit test suite (2 tests: timebase guard, provider-contract getSnapshot)
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.macos.MacosResourcesTest"
# Output: SUCCESS (2 tests)

# Legacy-provider hookup contract (all platforms, incl. testOSXProfile)
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.internal.sampling.ProviderContractTest"
# Output: SUCCESS

# Full hardware-utils module unit test suite
gradle :euhedral-hardware-utils:test --rerun-tasks
# Output: SUCCESS

# Full repository build
gradle build
# Output: BUILD SUCCESSFUL
```

Test inventory (verified 2026-08-07): `MacosResourcesTest` = 2 `@Test` methods
(`testMachTimebaseZeroDivisionProtection`, `testProviderContractGetSnapshot`);
`MacosAffinityTest` = 4; `MacosTopologyFixtureTest` = 6. No macOS test asserts thermal/low-power
signal mapping, consistent with the internal-only/deferred disposition of criteria 4/5.

### Environmental limits

Live macOS native JNI platform calls require a macOS host. Unit tests use `MacosResourceProbe` mocks to verify `MacosResources` behavior deterministically on Linux hosts. On-host runtime smoke on macOS 11 Intel/arm64 is satisfied at the P7 root level by the developer-confirmed hardware-utils GitHub CI workflow (passed 2026-08-07; see the root conformance audit).
