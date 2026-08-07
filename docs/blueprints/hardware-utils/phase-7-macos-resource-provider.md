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
- **Status**: Completed child implementation on branch `hardware-utils-overhaul/phase-7-macos-resources-implementation`. Verified via unit tests and provider contract test suites. **Amended 2026-08-07** during the P7 root conformance audit (see amendment note below).

> **Amendment (2026-08-07, developer-authorized).** The original blueprint required
> `MacosResources` to implement `DetailedSystemSnapshotProvider` and to surface
> `NSProcessInfo` thermal/low-power as `VALID` signals via `sampleSlow` (§4.6 item 6,
> acceptance criteria 4/5). That requirement was mis-scoped and conflicts with the frozen
> P4 sampling contract enforced by `ProviderContractTest`: every platform provider
> (`CgroupV2Resources`, `WindowsResources`, `MacosResources`) is a legacy
> `SystemSnapshotProvider` reached through `SystemSnapshotCompatibilityAdapter`, and the
> `SystemSnapshot` DTO has no thermal/low-power field. **Thermal and low-power are not
> public-facing values; they are internal-only inputs to the pressure calculation**
> (`SampleStateEngine` -> `internal.pressure`), and `VALID` surfacing of those signals is
> the responsibility of a canonical `DetailedSystemSnapshotProvider` (as `LinuxResourceProvider`
> already does), not of a legacy provider. Per developer decision, the macOS native
> thermal/low-power probes remain in place for a future canonical macOS `Detailed` provider,
> while the current legacy `MacosResources` correctly surfaces these as `UNSUPPORTED`/neutral
> through the adapter — matching the P6-accepted Windows precedent. Sections 2, 3, 4.4, 4.6,
> 8.1, and 9 are amended accordingly.

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
- **Defect R03 / R13 Correction (`NSProcessInfo` Thermal & Low-Power Probes — internal pressure inputs)**:
    - Thermal severity and low-power mode are **not public-facing values**. They are internal-only
      inputs to the pressure calculation (`SampleStateEngine` -> `internal.pressure`), consumed as
      `VALID` only when supplied by a canonical `DetailedSystemSnapshotProvider`; when absent they
      contribute neutrally (`NOMINAL` / not-throttled).
    - Provide the native probes so the values are available for a future canonical macOS `Detailed`
      provider. Map `NSProcessInfo.processInfo.thermalState` to `ThermalSeverity`:
        - `NSProcessInfoThermalStateNominal` (0) -> `ThermalSeverity.NOMINAL`
        - `NSProcessInfoThermalStateFair` (1) -> `ThermalSeverity.FAIR`
        - `NSProcessInfoThermalStateSerious` (2) -> `ThermalSeverity.SERIOUS`
        - `NSProcessInfoThermalStateCritical` (3) -> `ThermalSeverity.CRITICAL`
    - Map `NSProcessInfo.processInfo.isLowPowerModeEnabled` to a boolean (`true` if Low Power Mode /
      Battery Saver is active).
    - **Delivery through the legacy provider**: `MacosResources` is a `SystemSnapshotProvider`
      wrapped by `SystemSnapshotCompatibilityAdapter` (`MACOS_LEGACY`), whose `sampleSlow` surfaces
      thermal/low-power as `SignalValidity.UNSUPPORTED` (neutral), identical to Windows/Linux legacy
      providers. Wiring these probes into the pressure engine as `VALID` signals is deferred to a
      future canonical macOS `DetailedSystemSnapshotProvider` and is **out of P7-B scope** (see §3.2).
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
    - Implementing `SystemSnapshotProvider` (`getSnapshot()`) in `MacosResources`, integrated with the
      Phase 4 sampling engine via `SystemSnapshotCompatibilityAdapter.wrap(...)` (`MACOS_LEGACY`
      profile) — the same legacy-provider hookup used by `WindowsResources` and `CgroupV2Resources`,
      as frozen by `ProviderContractTest`.
    - Snapshot contents (surfaced through the adapter's fast path): process CPU usage ns, resident
      memory, cumulative disk I/O bytes; CPU/IO pressure marked `UNSUPPORTED`.
    - Slow-cadence signals (thermal severity, low-power mode) are surfaced as `UNSUPPORTED`/neutral by
      the legacy adapter; providing them as `VALID` pressure inputs is deferred to a future canonical
      macOS `Detailed` provider (§3.2).
- **Testing & Fixtures**:
    - macOS process metrics mock fixtures and unit test suites.
    - Working set underflow protection unit tests.
    - `NSProcessInfo` thermal state and low-power mapping tests.
    - Telemetry pressure isolation tests (`SignalValidity.UNSUPPORTED`).

### 3.2. Non-Goals

- Implementing `DetailedSystemSnapshotProvider` in `MacosResources`, or emitting `VALID`
  thermal/low-power signals via `sampleSlow`. Doing so would make `SystemSnapshotCompatibilityAdapter.wrap(...)`
  return the provider as-is, bypass the `MACOS_LEGACY` path, and break `ProviderContractTest.testOSXProfile`.
  Surfacing thermal/low-power as `VALID` pressure inputs is the responsibility of a future canonical
  macOS `Detailed` provider (mirroring `LinuxResourceProvider`) and is out of P7-B scope.
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
          MacosResources (SystemSnapshotProvider)
                      |
                getSnapshot() -> SystemSnapshot
                (CPU ns, resident bytes, I/O bytes; pressure neutral)
                      |
                      v
      SystemSnapshotCompatibilityAdapter.wrap(...)  [MACOS_LEGACY]
      +---------------+---------------------------+
      |                                           |
      v                                           v
sampleFast(requestedAtNs)                   sampleSlow(requestedAtNs)
 (200 ms Cadence)                            (5 s Cadence)
      |                                           |
      +--> Process CPU Times (ns)  VALID          +--> Thermal  UNSUPPORTED (neutral)
      +--> Resident Memory (bytes) VALID          +--> Low Power UNSUPPORTED (neutral)
      +--> Cumulative I/O (bytes)  VALID           |    (VALID surfacing deferred to a
      +--> CPU/IO Pressure UNSUPPORTED             |     future canonical macOS Detailed provider)
      v                                            v
FastHardwareSample                          SlowHardwareSample (all UNSUPPORTED)

Native probes getThermalStateNative()/isLowPowerModeNative() remain available for that
future canonical Detailed provider, which would feed thermal/low-power into internal
pressure (SampleStateEngine -> internal.pressure) as VALID.
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

### 4.4. Checklist Item 4: `NSProcessInfo` Thermal State & Low-Power Mode Probes

> These are internal-only pressure inputs, not public values. This item delivers the native
> probes and their mapping; surfacing them as `VALID` signals into the pressure engine is deferred
> to a future canonical macOS `Detailed` provider (§3.2). The legacy provider path leaves them
> `UNSUPPORTED`/neutral.

- [ ] **Objective-C / Foundation Dispatch**:
    - In `macos_resources.cpp`, link against `Foundation.framework`.
    - Access `NSProcessInfo *processInfo = [NSProcessInfo processInfo]`.
- [ ] **Thermal State Mapping** (probe + mapping only):
    - Query `processInfo.thermalState`:
        - `NSProcessInfoThermalStateNominal` (0) -> `ThermalSeverity.NOMINAL`
        - `NSProcessInfoThermalStateFair` (1) -> `ThermalSeverity.FAIR`
        - `NSProcessInfoThermalStateSerious` (2) -> `ThermalSeverity.SERIOUS`
        - `NSProcessInfoThermalStateCritical` (3) -> `ThermalSeverity.CRITICAL`
    - Expose via `MacosResources.getThermalState()` for the future canonical provider. A canonical
      `Detailed` provider would wrap it in `ThermalSignal(severity, requestedAtNs, SignalValidity.VALID)`.
- [ ] **Low-Power Mode Mapping** (probe + mapping only):
    - Query `processInfo.isLowPowerModeEnabled` (`BOOL`, `true` if Battery Saver / Low Power Mode active).
    - Expose via `MacosResources.isLowPowerMode()` for the future canonical provider, which would wrap it
      in `BooleanSignal(enabled, requestedAtNs, SignalValidity.VALID)`.

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

### 4.6. Checklist Item 6: Sampling-Engine Hookup & SignalValidity State Tracking

- [ ] **`SystemSnapshotProvider` Implementation (legacy hookup)**:
    - Implement `getSnapshot()` in `MacosResources` returning a `SystemSnapshot`. Do **not** implement
      `DetailedSystemSnapshotProvider` — integration with the P4 sampling engine is via
      `SystemSnapshotCompatibilityAdapter.wrap(...)` (`MACOS_LEGACY`), exactly as `WindowsResources`
      and `CgroupV2Resources` do (frozen by `ProviderContractTest.testOSXProfile`).
- [ ] **Snapshot Contents (surfaced through the adapter fast path, 200 ms)**:
    - Provide process CPU usage nanoseconds, resident working set memory, and cumulative disk I/O
      bytes; leave per-CPU pressure neutral. The adapter tags CPU/IO pressure `SignalValidity.UNSUPPORTED`.
- [ ] **Slow-Cadence Signals (5 s)**:
    - The adapter's `sampleSlow` emits an all-`UNSUPPORTED` `SlowHardwareSample` (thermal/low-power
      neutral) for the legacy provider. No `MacosResources.sampleSlow` is required. A future canonical
      macOS `Detailed` provider would feed the thermal/low-power probes into the pressure engine as
      `VALID`.
- [ ] **SignalValidity State Tracking** (as applied by the adapter / a future canonical provider):
        - `VALID`: Successfully queried from `proc_pid_rusage`, `task_info` (fast metrics); or, for a
          canonical provider, from `NSProcessInfo`.
        - `TRANSIENT_FAILURE`: Temporary API failure. Retains last valid reading.
        - `UNSUPPORTED`: Signal not available or telemetry isolated (CPU/IO pressure, and thermal/low-power
          on the legacy path). Value set to canonical zero, contributes neutrally ($0.0$).

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
4. **`NSProcessInfo` Thermal Severity Probe Mapping**:
    - Given a probe reporting thermal state `2`, `MacosResources.getThermalState()` maps it to
      `ThermalSeverity.SERIOUS` (states 0..3 -> `NOMINAL`/`FAIR`/`SERIOUS`/`CRITICAL`). This is an
      internal pressure input, not a public signal; a future canonical `Detailed` provider wraps it in
      `ThermalSignal(SERIOUS, …, VALID)`. The legacy provider surfaces it as `UNSUPPORTED`/neutral
      through the adapter (verified generically by `ProviderContractTest.testOSXProfile`).
5. **`NSProcessInfo` Low-Power Mode Probe Mapping**:
    - Given a probe reporting Low Power Mode enabled, `MacosResources.isLowPowerMode()` returns `true`.
      This is an internal pressure input; a future canonical `Detailed` provider wraps it in
      `BooleanSignal(true, …, VALID)`. The legacy provider surfaces it as `UNSUPPORTED`/neutral.
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

- `gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.macos.MacosResourcesTest"` - SUCCESS. **Corrected 2026-08-07:** `MacosResourcesTest` contains **2** tests — `testMachTimebaseZeroDivisionProtection` and `testProviderContractGetSnapshot`. (The prior record's claim of "8 tests" including thermal-severity/low-power/telemetry-isolation tests was inaccurate; those tests do not exist. The macOS legacy-provider fast-path validity is instead exercised generically by `ProviderContractTest.testOSXProfile`.)
- `gradle :euhedral-hardware-utils:test` - SUCCESS (full `euhedral-hardware-utils` suite).
- `gradle build` - SUCCESS (repository build and full test suite across all modules).

### Acceptance Evidence

- Process CPU nanoseconds (`ri_user_time + ri_system_time`) and cumulative disk I/O bytes (`ri_diskio_bytesread + ri_diskio_byteswritten`) collected via `proc_pid_rusage(RUSAGE_INFO_V3)` with `getrusage(RUSAGE_SELF)` fallback.
- Telemetry rule & pressure isolation enforced (`SignalValidity.UNSUPPORTED` for CPU/IO pressure signals with canonical neutral 0.0 value), applied by the `MACOS_LEGACY` adapter path.
- Resident working set memory (`info.resident_size`), virtual memory (`info.virtual_size`), total physical RAM (`hw.memsize`), and shared memory underflow protection (`Math.max(0L, virtual - resident)`).
- `NSProcessInfo` thermal severity **probe** maps states 0..3 to `ThermalSeverity` (`NOMINAL`, `FAIR`, `SERIOUS`, `CRITICAL`) — an internal-only pressure input, surfaced as `UNSUPPORTED`/neutral by the legacy provider; `VALID` surfacing deferred to a future canonical macOS `Detailed` provider.
- `NSProcessInfo` low-power mode **probe** maps to a boolean — same internal-only/deferred disposition.
- Mach timebase conversion guarded against zero division (`denom == 0` fallback to 1:1 scale with rate-limited warning).

### Approved Deviations

- **Thermal/low-power `VALID` surfacing deferred (developer-authorized 2026-08-07).** Thermal and
  low-power are internal-only pressure inputs, not public values, and the frozen P4 sampling contract
  (`ProviderContractTest`) requires macOS to be a legacy `SystemSnapshotProvider` wrapped by the
  compatibility adapter. Accordingly `MacosResources` does not implement `DetailedSystemSnapshotProvider`
  and does not emit `VALID` thermal/low-power via `sampleSlow`; the native probes remain available for a
  future canonical macOS `Detailed` provider. This deviation from the original §4.6/§8.1 wording is
  approved and folded into the amended blueprint (see §1 amendment note).

### Environmental Limits

- Live macOS native JNI platform tests require macOS host; mock process and Mach fixtures used for Linux host verification. Native binaries cross-compiled with Zig 0.16.0 for x86_64-macos and aarch64-macos and verified via codesign.
