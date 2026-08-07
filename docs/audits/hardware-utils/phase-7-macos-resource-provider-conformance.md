# Phase 7-B macOS Resource Provider Conformance Audit

## Scope and disposition

Audited P7-B implementation on branch `hardware-utils-overhaul/phase-7-macos` against `docs/blueprints/hardware-utils/phase-7-macos-resource-provider.md` and parent blueprint `docs/blueprints/hardware-utils/phase-7-macos-platform.md`.

Inspection covered:
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosResources.java`
- `euhedral-hardware-utils/src/main/native/macos/osx_resources.cpp`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/osx/OSXResources.java`
- `euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/macos/MacosResourcesTest.java`

**Disposition: review-ready; P7-B child action complete.** All 6 acceptance criteria are satisfied. Implementation correctly enforces telemetry pressure isolation, working set memory underflow protection, zero-division guards on Mach timebase calculations, and dynamic Objective-C NSProcessInfo signal dispatch.

## Acceptance criteria matrix

| Acceptance criterion | Classification | Evidence |
|---|---|---|
| 1. proc_pid_rusage Nanosecond CPU & Disk I/O Bytes | satisfied | `proc_pid_rusage(getpid(), RUSAGE_INFO_V3)` sums `ri_user_time + ri_system_time` for nanosecond CPU usage and `ri_diskio_bytesread + ri_diskio_byteswritten` for cumulative disk I/O bytes, falling back to `getrusage(RUSAGE_SELF)`. Verified by `MacosResourcesTest.testProcessCpuTimeAccumulation` and `testDiskIoByteAccumulation`. |
| 2. Telemetry Rule & Pressure Isolation | satisfied | Process CPU nanoseconds and disk I/O bytes are strictly isolated as telemetry counters. CPU pressure (`scopePsiStallNs`, `scopeReportedSchedulerStallRatio`, per-CPU `psiStall`) and I/O pressure (`ioSignals.stallNs`) are tagged with `SignalValidity.UNSUPPORTED` and default to neutral 0.0. Verified by `MacosResourcesTest.testTelemetryPressureIsolation`. |
| 3. Resident Memory & Underflow Guard | satisfied | Total physical RAM queried via sysctl `hw.memsize`. Resident size (`info.resident_size`) and virtual size (`info.virtual_size`) queried via `task_info(mach_task_self(), MACH_TASK_BASIC_INFO)`. Shared memory guarded against negative values via `Math.max(0L, virtual - resident)`. Verified by `MacosResourcesTest.testMemorySnapshotAndUnderflowProtection`. |
| 4. NSProcessInfo Thermal Severity Mapping | satisfied | Objective-C runtime dynamic dispatch via `dlsym` queries `[NSProcessInfo processInfo].thermalState`, mapping states 0..3 to `ThermalSeverity` (`NOMINAL`, `FAIR`, `SERIOUS`, `CRITICAL`) with `SignalValidity.VALID`. Verified by `MacosResourcesTest.testThermalSeverityStateMapping`. |
| 5. NSProcessInfo Low-Power Mode Mapping | satisfied | Objective-C runtime dynamic dispatch queries `[NSProcessInfo processInfo].isLowPowerModeEnabled`, returning `BooleanSignal` with `SignalValidity.VALID`. Verified by `MacosResourcesTest.testLowPowerModeSignalMapping`. |
| 6. Mach Timebase Zero-Division Protection | satisfied | `ticksToNanos` validates `timebaseDenom > 0` before division. If `timebaseDenom == 0` or query fails, logs rate-limited warning and uses 1:1 fallback ratio. Verified by `MacosResourcesTest.testMachTimebaseZeroDivisionProtection`. |

## Detailed independent audit

### 1. proc_pid_rusage process CPU nanoseconds and disk I/O bytes

`osx_resources.cpp` executes `proc_pid_rusage(getpid(), RUSAGE_INFO_V3, &rusage)`.
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

### 4. Objective-C NSProcessInfo dynamic dispatch

`osx_resources.cpp` uses dynamic symbol lookup via `dlsym(RTLD_DEFAULT, ...)` (`objc_getClass`, `sel_registerName`, `objc_msgSend`) to interact with `Foundation.framework`:
- Queries `[NSProcessInfo processInfo].thermalState` and maps enum values 0 (Nominal), 1 (Fair), 2 (Serious), 3 (Critical) to `ThermalSeverity`.
- Queries `[NSProcessInfo processInfo].isLowPowerModeEnabled` for battery saver status.
- Both signals are wrapped with `SignalValidity.VALID` and timestamp `requestedAtNs` on 5-second slow cadence samples.

### 5. Mach timebase conversion and zero-division guard

- Native `getMachTimebaseNative` queries `mach_timebase_info(&timebase)`.
- Java helper `ticksToNanos` checks `timebaseDenom > 0` before calculating `(ticks * numer) / denom`.
- If `denom == 0` or timebase query fails, `MacosResources` emits a rate-limited log warning (at most once every 60 seconds) and falls back to a safe 1:1 tick-to-nanosecond ratio.

## Verification evidence

### Commands run and results

```bash
# Focused MacosResources unit test suite
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.macos.MacosResourcesTest"
# Output: SUCCESS (8/8 tests passed)

# Full hardware-utils module unit test suite
gradle :euhedral-hardware-utils:test --rerun-tasks
# Output: SUCCESS (164/164 tests passed)

# Full repository build
gradle build
# Output: BUILD SUCCESSFUL
```

### Environmental limits

Live macOS native JNI platform calls require a macOS host. Unit tests use `MacosResourceProbe` mocks to verify `MacosResources` behavior deterministically on Linux hosts.
