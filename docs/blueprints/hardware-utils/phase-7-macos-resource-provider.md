# Phase 7-B macOS Resource Provider Blueprint

## 1. Status and Authority

- **Parent Plan**: [`docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md)
- **Parent Blueprint**: [`docs/blueprints/hardware-utils/phase-7-macos-platform.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-7-macos-platform.md)
- **P7 Root Branch**: `hardware-utils-overhaul/phase-7-macos`
- **Child Blueprint Branch**: `hardware-utils-overhaul/phase-7-macos-resources-blueprint`
- **Child Implementation Branch**: `hardware-utils-overhaul/phase-7-macos-resources-implementation`
- **Audit File Target**: `docs/audits/hardware-utils/phase-7-macos-resource-provider-conformance.md`
- **Owning Module**: `euhedral-hardware-utils`
- **Selected Blueprint Model**: `gpt-5.6-sol` with `high` reasoning effort
- **Status**: Completed child implementation on branch `hardware-utils-overhaul/phase-7-macos-resources-implementation`. Verified via unit tests and provider contract test suites.

This child blueprint is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, and the parent P7 blueprint (`phase-7-macos-platform.md`). It translates parent resource collection contracts into an explicit, implementable specification for `MacosResources` and native resource probes.

## 2. Objective & Core Defects Addressed

The objective of **Phase 7-B** is to deliver a robust, truthful, read-only macOS hardware and resource provider (`MacosResources`) supporting process CPU nanoseconds, cumulative disk I/O bytes, resident working-set memory, virtual memory, Objective-C thermal severity, low-power mode, and telemetry pressure isolation across Intel and Apple Silicon Macs running macOS 11+.

### Core Defect Corrections

- **Defect R03 Correction (Cumulative Process CPU Nanoseconds & Disk I/O Bytes via `proc_pid_rusage`)**:
    - Query process metrics via `proc_pid_rusage(getpid(), RUSAGE_INFO_V3, &rusage)`.
    - Sum user and system CPU times in nanoseconds:
      $$\text{cpuUsageNs} = \text{rusage.ri\_user\_time} + \text{rusage.ri\_system\_time}$$
    - Compute cumulative transferred disk I/O bytes:
      $$\text{ioBytes} = \text{rusage.ri\_diskio\_bytesread} + \text{rusage.ri\_diskio\_byteswritten}$$
    - If `proc_pid_rusage` returns a non-zero error code or is unreadable, fall back to `getrusage(RUSAGE_SELF, &usage)`, converting timevals to nanoseconds ($tv\_sec \times 10^9 + tv\_usec \times 1000$).
- **Telemetry Rule & Pressure Isolation (Defect R03 / R01 Correction)**:
    - Host CPU, process CPU, and process disk I/O counters are strictly treated as **telemetry**.
    - macOS does not expose public wait, stall, or capacity-loss signals equivalent to Linux PSI or Windows JobObject rate control.
    - Process CPU time and disk I/O counters MUST NOT be converted into artificial CPU or I/O pressure metrics.
    - Unsupported pressure fields MUST carry `SignalValidity.UNSUPPORTED` and default to neutral values ($0.0$).
- **Defect R03 Correction (Task & Host Memory Semantics via `task_info` & `hw.memsize`)**:
    - Total physical system RAM is queried via sysctl `hw.memsize` in bytes (`jlong`).
    - Query process working set memory via `task_info(mach_task_self(), MACH_TASK_BASIC_INFO, (task_info_t)&info, &count)`.
    - Extract `WorkingSetSize` from `info.resident_size` (bytes).
    - Extract `VirtualSize` from `info.virtual_size` (bytes).
    - Compute shared memory / non-private working set with explicit underflow protection:
      $$\text{sharedMemory} = \text{Math.max}(0\text{L}, \text{info.virtual\_size} - \text{info.resident\_size})$$
    - Prevents negative memory values when resident size exceeds virtual size accounting.
- **Defect R03 / R13 Correction (`NSProcessInfo` Thermal & Low-Power Signals)**:
    - Query Objective-C Foundation framework via `[NSProcessInfo processInfo]`.
    - Map `NSProcessInfo.processInfo.thermalState` to `ThermalSeverity`:
        - `NSProcessInfoThermalStateNominal` (0) -> `ThermalSeverity.NOMINAL`
        - `NSProcessInfoThermalStateFair` (1) -> `ThermalSeverity.FAIR`
        - `NSProcessInfoThermalStateSerious` (2) -> `ThermalSeverity.SERIOUS`
        - `NSProcessInfoThermalStateCritical` (3) -> `ThermalSeverity.CRITICAL`
      - Tagged with `SignalValidity.VALID` and timestamp `requestedAtNs`.
    - Map `NSProcessInfo.processInfo.isLowPowerModeEnabled` to `BooleanSignal`:
        - Returns `BOOL` (`true` if Low Power Mode / Battery Saver is active).
        - Tagged with `SignalValidity.VALID` and timestamp `requestedAtNs`.
- **Mach Timebase Conversion & Zero-Division Guard (Defect N02 Correction)**:
    - Query Mach absolute time conversion factors via `mach_timebase_info(&timebase)`.
    - Validate `timebase.denom > 0` before division to prevent divide-by-zero arithmetic traps.
    - Convert Mach absolute time ticks to nanoseconds:
      $$\text{nanos} = \frac{\text{ticks} \times \text{timebase.numer}}{\text{timebase.denom}}$$

## 3. Scope & Non-Goals

### 3.1. In Scope

- **Primary Source Files**:
    - [`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosResources.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosResources.java)
    - [`euhedral-hardware-utils/src/main/native/macos/macos_resources.cpp`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/macos/macos_resources.cpp)
- **macOS System & Mach APIs**:
    - `proc_pid_rusage(RUSAGE_INFO_V3)` for process CPU time and disk I/O bytes.
    - `task_info(MACH_TASK_BASIC_INFO)` for resident size and virtual size.
    - `mach_timebase_info` for nanosecond scaling.
    - `sysctlbyname("hw.memsize")` for total system physical memory.
    - `NSProcessInfo` thermal state (`thermalState`) and low-power mode (`isLowPowerModeEnabled`).
- **P4 Sampling Engine Integration**:
    - Implementing `DetailedSystemSnapshotProvider` interface for integration with Phase 4 sampling engine (`FastHardwareSample`, `SlowHardwareSample`).
    - Fast cadence (200 ms): process CPU usage ns, resident memory, cumulative disk I/O bytes.
    - Slow cadence (5 s): `NSProcessInfo` thermal severity, low-power mode flag.
    - Tagging every raw signal with explicit `SignalValidity` (`VALID`, `TRANSIENT_FAILURE`, `UNSUPPORTED`).
- **Testing & Fixtures**:
    - macOS process metrics mock fixtures and unit test suites.
    - Working set underflow protection unit tests.
    - `NSProcessInfo` thermal state and low-power mapping tests.
    - Telemetry pressure isolation tests (`SignalValidity.UNSUPPORTED`).

### 3.2. Non-Goals

- Modifying macOS sysctl topology parsing, Intel SMT, or Apple Silicon P/E-core classification (owned by P7-A).
- Modifying locality affinity, Mach thread affinity tags, safe timer policy, universal binary linkage, or Mach-O build graphs (owned by P7-C).
- Modifying common P4 pressure math, EWMA formulas, or normalization curves in `internal.pressure`.
- Modifying core fragment execution loops or action-picker policy in `euhedral-core`.
- Any work involving `euhedral-training`.

## 4. Architectural Contracts & Implementation Checklist

```text
macOS Kernel APIs / proc_pid_rusage / task_info / NSProcessInfo
(proc_pid_rusage, task_info, sysctl hw.memsize, NSProcessInfo.thermalState)
                      |
                      v
      Native C++ JNI Wrappers (macos_resources.cpp)
      - Objective-C Foundation Linkage ([NSProcessInfo processInfo])
      - Mach Timebase & Deallocation Safety
      - 64-Bit Memory & Nanosecond Scaling
                      |
                      v
                MacosResources
                      |
      +---------------+---------------+
      |                               |
      v                               v
sampleFast(requestedAtNs)       sampleSlow(requestedAtNs)
 (200 ms Cadence)                (5 s Cadence)
      |                               |
      +--> Process CPU Times (ns)     +--> Thermal State (NSProcessInfo)
      +--> Resident Memory (bytes)    +--> Low Power Mode Flag
      +--> Cumulative I/O (bytes)     +--> Nominal CPU Frequency
      +--> Unsupported Pressure       |
      |    (SignalValidity.UNSUPPORTED) v
      v                          SlowHardwareSample
FastHardwareSample               (SignalValidity, ThermalSeverity)
(SignalValidity, ns, bytes)
```

### 4.1. Checklist Item 1: `proc_pid_rusage` Process CPU Nanoseconds & Disk I/O Bytes

- [ ] **`proc_pid_rusage` Call**:
    - Call `proc_pid_rusage(getpid(), RUSAGE_INFO_V3, &rusage)`.
    - Check return value: `0` indicates success; non-zero value indicates failure.
- [ ] **Nanosecond CPU Times**:
    - Extract `ri_user_time` (`uint64_t`) and `ri_system_time` (`uint64_t`).
    - Sum user and system time:
      $$\text{cpuUsageNs} = \text{rusage.ri\_user\_time} + \text{rusage.ri\_system\_time}$$
    - Store as `jlong` for fast sample consumption.
- [ ] **Cumulative Disk I/O Bytes**:
    - Extract `ri_diskio_bytesread` (`uint64_t`) and `ri_diskio_byteswritten` (`uint64_t`).
    - Sum read and written bytes:
      $$\text{ioBytes} = \text{rusage.ri\_diskio\_bytesread} + \text{rusage.ri\_diskio\_byteswritten}$$
    - Store as `jlong` for fast sample consumption.
- [ ] **Fallback Path**:
    - If `proc_pid_rusage` fails, execute `getrusage(RUSAGE_SELF, &usage)`.
    - Convert timevals to nanoseconds: $(usage.ru\_utime.tv\_sec + usage.ru\_stime.tv\_sec) \times 10^9 + (usage.ru\_utime.tv\_usec + usage.ru\_stime.tv\_usec) \times 1000$.

### 4.2. Checklist Item 2: Telemetry Rule & Pressure Isolation

- [ ] **Telemetry Rule Enforcement**:
    - Treat process CPU usage nanoseconds and disk I/O bytes strictly as **telemetry counters**.
    - Do NOT convert process CPU time or disk I/O bytes into artificial CPU or I/O pressure metrics.
- [ ] **SignalValidity.UNSUPPORTED Propagation**:
    - Set CPU pressure signal validity to `SignalValidity.UNSUPPORTED` with canonical value $0.0$.
    - Set I/O pressure signal validity to `SignalValidity.UNSUPPORTED` with canonical value $0.0$.
    - Ensure unsupported pressure signals contribute neutrally ($0.0$) in the P4 composite pressure engine without masquerading as zero stall.

### 4.3. Checklist Item 3: Host & Task Memory via `task_info` and sysctl `hw.memsize`

- [ ] **System Physical RAM**:
    - Query sysctl `hw.memsize` via `sysctlbyname("hw.memsize", &memsize, &size, NULL, 0)`.
    - Store as total physical memory in bytes (`jlong`).
- [ ] **Process Working Set Memory**:
    - Call `task_info(mach_task_self(), MACH_TASK_BASIC_INFO, (task_info_t)&info, &count)`.
    - Verify return status `KERN_SUCCESS`.
    - Extract resident memory `WorkingSetSize = (jlong)info.resident_size`.
    - Extract virtual memory `VirtualSize = (jlong)info.virtual_size`.
- [ ] **Shared Memory Underflow Protection (Defect R03 Correction)**:
    - Compute shared memory / non-private working set with explicit lower bound:
      $$\text{sharedMemory} = \text{Math.max}(0\text{L}, \text{info.virtual\_size} - \text{info.resident\_size})$$
    - Prevents negative memory values when resident size exceeds virtual size.

### 4.4. Checklist Item 4: `NSProcessInfo` Thermal State & Low-Power Mode Mapping

- [ ] **Objective-C / Foundation Dispatch**:
    - In `macos_resources.cpp`, link against `Foundation.framework`.
    - Access `NSProcessInfo *processInfo = [NSProcessInfo processInfo]`.
- [ ] **Thermal State Mapping**:
    - Query `processInfo.thermalState`:
        - `NSProcessInfoThermalStateNominal` (0) -> `ThermalSeverity.NOMINAL`
        - `NSProcessInfoThermalStateFair` (1) -> `ThermalSeverity.FAIR`
        - `NSProcessInfoThermalStateSerious` (2) -> `ThermalSeverity.SERIOUS`
        - `NSProcessInfoThermalStateCritical` (3) -> `ThermalSeverity.CRITICAL`
    - Wrap in `ThermalSignal(severity, requestedAtNs, SignalValidity.VALID)`.
- [ ] **Low-Power Mode Mapping**:
    - Query `processInfo.isLowPowerModeEnabled`:
        - Returns `BOOL` (`true` if Battery Saver / Low Power Mode active).
    - Wrap in `BooleanSignal(enabled, requestedAtNs, SignalValidity.VALID)`.

### 4.5. Checklist Item 5: Mach Timebase Conversion & Zero-Division Guard

- [ ] **Mach Timebase Query**:
    - Call `mach_timebase_info(&timebase)`.
    - Check return status `KERN_SUCCESS`.
- [ ] **Zero-Division Guard (Defect N02 Correction)**:
    - Validate `timebase.denom > 0` before any division operation.
    - If `timebase.denom == 0`, log a rate-limited diagnostic warning and fall back to $1:1$ tick-to-nanosecond ratio.
- [ ] **Tick-to-Nanosecond Scaling**:
    - Convert Mach absolute time ticks to nanoseconds:
      $$\text{nanos} = \frac{\text{ticks} \times \text{timebase.numer}}{\text{timebase.denom}}$$

### 4.6. Checklist Item 6: Fast / Slow Cadences & SignalValidity State Tracking

- [ ] **DetailedSystemSnapshotProvider Implementation**:
    - Implement `sampleFast(long requestedAtNs)` and `sampleSlow(long requestedAtNs)` in `MacosResources`.
- [ ] **Fast Hardware Sample (200 ms Cadence)**:
    - Collect process CPU usage nanoseconds, resident working set memory, cumulative disk I/O bytes.
    - Tag CPU pressure and I/O pressure as `SignalValidity.UNSUPPORTED`.
    - Construct `FastHardwareSample` with timestamp `requestedAtNs`.
- [ ] **Slow Hardware Sample (5 s Cadence)**:
    - Collect `NSProcessInfo` thermal severity and low-power mode flag.
    - Query nominal CPU frequency.
    - Construct `SlowHardwareSample` with timestamp `requestedAtNs`.
- [ ] **SignalValidity State Tracking**:
    - Tag each signal:
        - `VALID`: Successfully queried from `proc_pid_rusage`, `task_info`, or `NSProcessInfo`.
        - `TRANSIENT_FAILURE`: Temporary API failure. Retains last valid reading.
        - `UNSUPPORTED`: Signal not available or telemetry isolated (e.g. CPU/IO pressure). Value set to canonical zero, contributes neutrally ($0.0$).

### 4.7. Checklist Item 7: JNI Native Layer Ownership & Cleanup Safety

- [ ] **JNI Declaration**:
    - Declare native JNI methods in `MacosResources`:
      ```java
      private static native boolean getProcessRusageNative(long[] outCpuAndIoBytes);
      private static native boolean getTaskMemoryNative(long[] outMemory);
      private static native int getThermalStateNative();
      private static native boolean isLowPowerModeNative();
      private static native boolean getMachTimebaseNative(int[] outNumerDenom);
      ```
- [ ] **Array & Pointer Validation**:
    - Verify `GetLongArrayElements`, `GetIntArrayElements` return non-NULL pointers before accessing memory.
    - Validate output array lengths match expectations (`outCpuAndIoBytes` length >= 2, `outMemory` length >= 2).
    - Guarantee `ReleaseLongArrayElements` / `ReleaseIntArrayElements` calls on all execution paths.

## 5. Sizing & Split Gate Assessment

### Sizing Evaluation

1. **Context Load**: The implementation is strictly bounded to `MacosResources.java`, `macos_resources.cpp`, and associated unit test fixture files. The responsibility covers `proc_pid_rusage` parsing, `task_info` memory collection, `NSProcessInfo` Objective-C signal mapping, Mach timebase conversion, and P4 sample construction. This fits within the working memory of a single implementation pass.
2. **Single Responsibility**: `MacosResources` owns macOS resource metric collection. Sysctl topology parsing (P7-A) and locality affinity / Mach thread tags / timer policy / native ABI (P7-C) are cleanly separated.
3. **Independent Validation**: Resource collection can be fully validated using mock process metrics and unit tests without requiring live Apple Silicon hardware or Mach affinity leases.

**Conclusion**: Child P7-B is irreducible, correctly sized, and ready for implementation in a single pass.

## 6. Implementation Model Reassessment

- **Required Capabilities**: Coupled state machine across `proc_pid_rusage`, `task_info`, Objective-C `NSProcessInfo` runtime linkage, Mach timebase conversion, telemetry pressure isolation, and `SignalValidity` state mapping.
- **Selected Model**: **`gpt-5.6-sol` with `high` reasoning effort**.
- **Justification**: macOS C++/Objective-C JNI boundaries, `proc_pid_rusage` vs `getrusage` fallbacks, working set underflow protection, `NSProcessInfo` thermal state translation, Mach timebase zero-division safety, and telemetry pressure isolation require high reasoning effort.

## 7. Developer-Review Summary

| Item | Details |
|---|---|
| **Purpose** | Deliver macOS process resource collection (`MacosResources`, `macos_resources.cpp`), `proc_pid_rusage` nanosecond CPU times and disk I/O bytes, `task_info` resident working set memory, `NSProcessInfo` thermal severity and low-power mode, Mach timebase nanosecond conversion, fast/slow cadences, and `SignalValidity` state tracking. |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.macos.MacosResources`, `src/main/native/macos/macos_resources.cpp`, `io.euhedral_execution.hardware_utils.internal.sampling.*`. |
| **Key Invariants** | Process CPU nanoseconds equals `ri_user_time + ri_system_time`; disk I/O bytes equals `ri_diskio_bytesread + ri_diskio_byteswritten`; telemetry rule ensures CPU and I/O counters do NOT create artificial pressure (`SignalValidity.UNSUPPORTED`); resident memory equals `info.resident_size`; shared memory guarded by `Math.max(0L, virtual - resident)`; thermal state mapped from `NSProcessInfoThermalState`; Mach timebase guarded against `denom == 0`; raw signals carry explicit `SignalValidity`. |
| **Child Action Items** | P7-B implementation: `hardware-utils-overhaul/phase-7-macos-resources-implementation`. |
| **Selected Model** | `gpt-5.6-sol` with `high` reasoning effort for implementation and conformance audit. |
| **Principal Risks** | Objective-C runtime linkage in C++ JNI code; Mach timebase divide-by-zero; negative working set calculation on resident/virtual mismatch; converting telemetry counters into false pressure metrics. |
| **Unresolved Items** | None. `proc_pid_rusage` fields, `task_info` memory structures, `NSProcessInfo` thermal enum mappings, timebase math, and signal cadences are fully settled. |

## 8. Verification & Acceptance Criteria

### 8.1. Acceptance Criteria

1. **`proc_pid_rusage` Nanosecond CPU & Disk I/O Bytes**:
    - Given `proc_pid_rusage` returning `ri_user_time` = 1,000,000 ns, `ri_system_time` = 500,000 ns, `ri_diskio_bytesread` = 2,000,000 bytes, and `ri_diskio_byteswritten` = 1,000,000 bytes, `MacosResources` returns `cpuUsageNs = 1,500,000L` and `ioBytes = 3,000,000L`.
2. **Telemetry Rule & Pressure Isolation**:
    - `FastHardwareSample` generated on macOS marks CPU pressure and I/O pressure signals with `SignalValidity.UNSUPPORTED` and canonical value $0.0$, preventing process counters from creating artificial pressure.
3. **Resident Memory & Underflow Guard**:
    - Given `task_info` returning `resident_size` = 100 MB and `virtual_size` = 80 MB, shared memory calculation saturates to `0L` (guarded by `Math.max(0L, ...)`), avoiding negative memory values.
4. **`NSProcessInfo` Thermal Severity Mapping**:
    - Given `NSProcessInfo.processInfo.thermalState` returning `NSProcessInfoThermalStateSerious` (2), `sampleSlow` returns `ThermalSignal` with `ThermalSeverity.SERIOUS` and `SignalValidity.VALID`.
5. **`NSProcessInfo` Low-Power Mode Mapping**:
    - Given `NSProcessInfo.processInfo.isLowPowerModeEnabled` returning `YES`, `sampleSlow` returns `BooleanSignal` with `value = true` and `SignalValidity.VALID`.
6. **Mach Timebase Zero-Division Protection**:
    - Given `mach_timebase_info` with `denom = 0`, conversion logic logs a rate-limited diagnostic warning and falls back to $1:1$ ratio without throwing an ArithmeticException.

### 8.2. Verification Commands

```bash
# Build hardware-utils module and run macOS resource tests
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.macos.*"

# Run all hardware-utils tests
gradle :euhedral-hardware-utils:test
```

## 9. Completion Record

### Changed Files

- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosResources.java`
- `euhedral-hardware-utils/src/main/native/macos/macos_resources.cpp`
- `euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/macos/MacosResourcesTest.java`
- `docs/blueprints/hardware-utils/phase-7-macos-resource-provider.md`

### Commands Run & Results

- `gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.macos.MacosResourcesTest"` - SUCCESS (Passed all 8 unit tests: process CPU accumulation, disk I/O bytes, working set memory underflow guard, NSProcessInfo thermal severity states, low-power mode signal, telemetry pressure isolation, Mach timebase zero-division protection, and provider contract getSnapshot).
- `gradle :euhedral-hardware-utils:test` - SUCCESS (Passed all 164 tests in euhedral-hardware-utils).
- `gradle build` - SUCCESS (Passed repository build and full test suite across all modules in 13s).

### Acceptance Evidence

- Process CPU nanoseconds (`ri_user_time + ri_system_time`) and cumulative disk I/O bytes (`ri_diskio_bytesread + ri_diskio_byteswritten`) collected via `proc_pid_rusage(RUSAGE_INFO_V3)` with `getrusage(RUSAGE_SELF)` fallback.
- Telemetry rule & pressure isolation enforced (`SignalValidity.UNSUPPORTED` for CPU/IO pressure signals with canonical neutral 0.0 value).
- Resident working set memory (`info.resident_size`), virtual memory (`info.virtual_size`), total physical RAM (`hw.memsize`), and shared memory underflow protection (`Math.max(0L, virtual - resident)`).
- `NSProcessInfo` thermal severity mapped to `ThermalSeverity` (`NOMINAL`, `FAIR`, `SERIOUS`, `CRITICAL`) with `SignalValidity.VALID`.
- `NSProcessInfo` low-power mode mapped to `BooleanSignal` with `SignalValidity.VALID`.
- Mach timebase conversion guarded against zero division (`denom == 0` fallback to 1:1 scale with rate-limited warning).

### Approved Deviations

- None.

### Environmental Limits

- Live macOS native JNI platform tests require macOS host; mock process and Mach fixtures used for Linux host verification. Native binaries cross-compiled with Zig 0.16.0 for x86_64-macos and aarch64-macos and verified via codesign.
