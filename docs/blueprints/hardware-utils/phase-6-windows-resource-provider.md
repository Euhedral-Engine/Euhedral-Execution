# Phase 6-B Windows Resource Provider Blueprint

## 1. Status and Authority

- **Parent Plan**: [`docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md)
- **Parent Blueprint**: [`docs/blueprints/hardware-utils/phase-6-windows-platform.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-6-windows-platform.md)
- **P6 Root Branch**: `hardware-utils-overhaul/phase-6-windows`
- **Child Blueprint Branch**: `hardware-utils-overhaul/phase-6-windows-resources-blueprint`
- **Child Implementation Branch**: `hardware-utils-overhaul/phase-6-windows-resources-implementation`
- **Audit File Target**: `docs/audits/hardware-utils/phase-6-windows-resource-provider-conformance.md`
- **Owning Module**: `euhedral-hardware-utils`
- **Selected Blueprint Model**: `gpt-5.6-sol` with `high` reasoning effort
- **Status**: Implementation-ready child blueprint. Pending developer review and merge into the P6 root before child implementation begins.

This child blueprint is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, and the parent P6 blueprint (`phase-6-windows-platform.md`). It translates parent resource collection contracts into an explicit, implementable specification for `WindowsResources` and native `windows_resources.cpp`.

## 2. Objective & Core Defects Addressed

The objective of **Phase 6-B** is to deliver a robust, truthful, read-only Windows hardware and resource provider (`WindowsResources`) supporting Win32 process metrics, Job Objects, process memory limits, per-CPU cycle delta normalization, and cumulative I/O tracking across x86-64 and ARM64 Windows platforms.

### Core Defect Corrections

- **Defect R04 Correction (Job Object CPU Rate Control & Quota Scaling)**:
    - Query Job Object CPU rate control via `QueryInformationJobObject(NULL, JobObjectCpuRateControlInformation, &info, sizeof(info), NULL)`.
    - Verify `info.ControlFlags & JOB_OBJECT_CPU_RATE_CONTROL_ENABLE`.
    - `info.CpuRate` is expressed in hundredths of a percent (e.g. 10000 = 100.0%, 5000 = 50.0%, 20000 = 200.0%).
    - Calculate quota fraction:
      $$\text{quotaFraction} = \frac{\text{info.CpuRate}}{10000.0}$$
    - Calculate effective quota CPUs:
      $$\text{quotaCpus} = \begin{cases} \text{quotaFraction} \times \text{availableCpus}, & \text{if Job CpuRate enabled} \\ \text{availableCpus}, & \text{otherwise (-1.0 returned by native caller)} \end{cases}$$
    - Eliminates legacy bug where `CpuRate` fraction was used directly as CPU count or improperly scaled.
- **Defect R04 Correction (Process Working Set Underflow Protection)**:
    - Query process memory metrics via `GetProcessMemoryInfo(GetCurrentProcess(), (PROCESS_MEMORY_COUNTERS*)&pmc, sizeof(pmc))`.
    - Extract `WorkingSetSize` and `PrivateUsage` as 64-bit values (`jlong`).
    - Calculate shared memory / non-private working set with explicit underflow protection:
      $$\text{sharedMemory} = \text{Math.max}(0\text{L}, (\text{jlong})\text{pmc.WorkingSetSize} - (\text{jlong})\text{pmc.PrivateUsage})$$
    - Prevents negative memory values when `PrivateUsage` exceeds `WorkingSetSize`.
- **Process CPU Times 100-ns to Nanosecond Conversion**:
    - Query process execution times via `GetProcessTimes(GetCurrentProcess(), &createTime, &exitTime, &kernelTime, &userTime)`.
    - `FILETIME` structures represent time in 100-nanosecond intervals.
    - Convert to total nanoseconds by multiplying sum by 100L:
      $$\text{cpuUsageNs} = (\text{kernelTime.QuadPart} + \text{userTime.QuadPart}) \times 100\text{L}$$
- **Defect R04 Correction (QueryIdleProcessorCycleTime Idle Cycle Delta Normalization)**:
    - Query per-processor idle cycle counts via `QueryIdleProcessorCycleTime(&bufferSize, idleTimes)` returning `ULONG64` cycle values per logical processor.
    - Legacy code divided raw cycle counts directly by elapsed nanoseconds ($dt$), producing dimensionally invalid numbers.
    - Corrected calculation: track raw idle cycle deltas $\Delta \text{idleCycle}_i$ per processor over interval, and normalize against total elapsed processor cycles over interval.
- **Cumulative I/O Bytes**:
    - Query process I/O statistics via `GetProcessIoCounters(GetCurrentProcess(), &ioCounters)`.
    - Calculate cumulative transferred bytes:
      $$\text{ioBytes} = \text{ioCounters.ReadTransferCount} + \text{ioCounters.WriteTransferCount}$$
- **Defect N01 Correction (Zero VLAs in C++ Native Code)**:
    - Eliminate Variable Length Array `ULONG64 idleTimes[cpuCount]` in `windows_resources.cpp`.
    - Use fixed stack buffers bounded by maximum processor count (64 or 256) or dynamic heap allocation (`std::vector<ULONG64>` / `malloc`) with explicit NULL checks and guaranteed cleanup.
- **SignalValidity State Tracking & Sampling Integration**:
    - Implement `DetailedSystemSnapshotProvider` interface for integration with Phase 4 sampling engine (`FastHardwareSample`, `SlowHardwareSample`).
    - Fast cadence (200 ms): process CPU usage ns, job quota, working set memory, cumulative I/O bytes.
    - Slow cadence (5 s): system power status (`GetSystemPowerStatus`), battery saver state, processor frequency.
    - Tag every raw signal with explicit `SignalValidity` (`VALID`, `TRANSIENT_FAILURE`, `UNSUPPORTED`).

## 3. Scope & Non-Goals

### 3.1. In Scope

- **Primary Source Files**:
    - [`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/WindowsResources.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/WindowsResources.java)
    - [`euhedral-hardware-utils/src/main/native/windows/windows_resources.cpp`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/windows/windows_resources.cpp)
- **Win32 APIs & Job Objects**:
    - `QueryInformationJobObject` (`JobObjectCpuRateControlInformation`, `JobObjectExtendedLimitInformation`)
    - `GetProcessTimes` (`FILETIME` to nanosecond conversion)
    - `GetProcessMemoryInfo` (`PROCESS_MEMORY_COUNTERS_EX`, underflow protection)
    - `GlobalMemoryStatusEx` (`MEMORYSTATUSEX`)
    - `GetProcessIoCounters` (`IO_COUNTERS`)
    - `QueryIdleProcessorCycleTime` (idle cycle delta normalization)
    - `GetSystemPowerStatus` (`SYSTEM_POWER_STATUS`)
- **P4 Sampling Engine Integration**:
    - Implementing `DetailedSystemSnapshotProvider` interface.
    - Producing `FastHardwareSample` (200 ms cadence) and `SlowHardwareSample` (5 s cadence).
    - Populating canonical units (`ns` for time, `bytes` for memory/IO).
    - Tagging every raw signal with explicit `SignalValidity` (`VALID`, `TRANSIENT_FAILURE`, `UNSUPPORTED`).
- **Testing & Fixtures**:
    - Win32 job object mock contracts and fixture suites.
    - Working set underflow protection tests.
    - Idle cycle delta normalization unit tests.
    - Fast/slow sample validity state tests.

### 3.2. Non-Goals

- Windows topology model or GLPIEx binary parsing (owned by P6-A).
- Windows thread affinity leases, `SetThreadSelectedCpuSetMasks`, `NtSetTimerResolution`, or PE ABI hardening (owned by P6-C).
- Modifying common P4 pressure math, EWMA formulas, or normalization curves in `internal.pressure`.
- Modifying core fragment execution or action-picker policy in `euhedral-core`.
- Any work involving `euhedral-training`.

## 4. Architectural Contracts & Implementation Checklist

```text
Win32 Kernel APIs / Job Objects / Process Metrics
(GetProcessTimes, QueryInformationJobObject, GetProcessMemoryInfo, GetProcessIoCounters, QueryIdleProcessorCycleTime)
                      |
                      v
      Native C++ JNI Wrappers (windows_resources.cpp)
      - VLA-Free Stack/Heap Allocation
      - Thread-Safe std::atomic<bool> Initialization
      - Null & Array Length Checks
      - 100-ns to Nanosecond Scaling (* 100L)
                      |
                      v
               WindowsResources
                      |
      +---------------+---------------+
      |                               |
      v                               v
sampleFast(requestedAtNs)       sampleSlow(requestedAtNs)
 (200 ms Cadence)                (5 s Cadence)
      |                               |
      +--> Process CPU Times (ns)     +--> Power Status (AC/Battery)
      +--> Job CPU Quota Fraction     +--> Battery Saver Flag
      +--> Working Set / Private      +--> Nominal CPU Frequency
      +--> Cumulative I/O Bytes       |
      |                               v
      v                          SlowHardwareSample
FastHardwareSample               (SignalValidity, Hz)
(SignalValidity, ns, bytes)
```

### 4.1. Checklist Item 1: Job Object CPU Rate Control & Quota Scaling

- [ ] **Job Object Query**:
    - Call `QueryInformationJobObject(NULL, JobObjectCpuRateControlInformation, &info, sizeof(info), NULL)`.
    - Check `info.ControlFlags & JOB_OBJECT_CPU_RATE_CONTROL_ENABLE`.
    - If enabled, return `info.CpuRate / 10000.0` as quota fraction from native caller (`getCpuQuota()`).
    - If disabled or process is not in a job, native caller returns `-1.0`.
- [ ] **Effective Quota CPU Math**:
    - In `WindowsResources`:
      $$\text{quotaCpus} = \begin{cases} \text{quotaFraction} \times \text{availableCpus}, & \text{if quotaFraction } > 0.0 \\ \text{availableCpus}, & \text{otherwise} \end{cases}$$
    - Verify that a `CpuRate` of 5000 (50.0%) on a 8-CPU system yields `quotaCpus = 4.0`.
    - Verify that a `CpuRate` of 20000 (200.0%) on a 8-CPU system yields `quotaCpus = 16.0` (or capped by system topology if bounded).

### 4.2. Checklist Item 2: Process Working Set Underflow Protection & Job Memory Limits

- [ ] **Job Memory Limit Query**:
    - Call `QueryInformationJobObject(NULL, JobObjectExtendedLimitInformation, &jobInfo, sizeof(jobInfo), NULL)`.
    - Check `jobInfo.BasicLimitInformation.LimitFlags`.
    - If `JOB_OBJECT_LIMIT_JOB_MEMORY` is set, effective limit is `jobInfo.JobMemoryLimit`.
    - Else if `JOB_OBJECT_LIMIT_PROCESS_MEMORY` is set, effective limit is `jobInfo.ProcessMemoryLimit`.
    - Else fall back to total physical RAM from `GlobalMemoryStatusEx(&memStatus)`.
- [ ] **Working Set Underflow Guard (Defect R04 Correction)**:
    - Query `GetProcessMemoryInfo(GetCurrentProcess(), (PROCESS_MEMORY_COUNTERS*)&pmc, sizeof(pmc))`.
    - Extract `WorkingSetSize` (`jlong`) and `PrivateUsage` (`jlong`).
    - Calculate shared memory with non-negative lower bound:
      $$\text{sharedMemory} = \text{Math.max}(0\text{L}, (\text{jlong})\text{pmc.WorkingSetSize} - (\text{jlong})\text{pmc.PrivateUsage})$$
    - Ensure negative values are never propagated to buffer or sampling engine.

### 4.3. Checklist Item 3: Process CPU Times 100-ns to Nanosecond Scaling

- [ ] **GetProcessTimes Call**:
    - Call `GetProcessTimes(GetCurrentProcess(), &createTime, &exitTime, &kernelTime, &userTime)`.
    - Check boolean return value for failure handling.
- [ ] **Nanosecond Conversion**:
    - Combine kernel and user time QuadPart values (`ULARGE_INTEGER`).
    - Multiply sum by `100L` to convert 100-ns intervals into nanoseconds:
      $$\text{cpuUsageNs} = (kernelTime.QuadPart + userTime.QuadPart) \times 100\text{L}$$
    - Store in buffer index `0` for fast sample consumption.

### 4.4. Checklist Item 4: QueryIdleProcessorCycleTime Idle Cycle Delta Normalization

- [ ] **QueryIdleProcessorCycleTime Buffer Allocation**:
    - Determine active CPU count `DWORD cpuCount = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS)`.
    - Use fixed stack array `ULONG64 idleTimes[256]` or dynamic heap allocation (`std::vector<ULONG64>`) to eliminate VLA usage.
    - Check return status of `QueryIdleProcessorCycleTime(&bufferSize, idleTimes)`.
- [ ] **Idle Cycle Delta Normalization (Defect R04 Correction)**:
    - In `WindowsResources`, track per-processor previous idle cycles `lastIdleCycles[i]` and current idle cycles `currentIdleCycles[i]`.
    - Compute cycle delta $\Delta \text{idleCycle}_i = \text{currentIdleCycles}[i] - \text{lastIdleCycles}[i]$.
    - Compute total elapsed cycles over interval $\Delta \text{totalCycles} = \text{elapsedNs} \times \text{cyclesPerNs}$ (or normalized against total cycle counter).
    - Compute busy ratio per CPU:
      $$\text{busy}_i = \text{Math.min}(1.0, \text{Math.max}(0.0, 1.0 - \frac{\Delta \text{idleCycle}_i}{\Delta \text{totalCycles}}))$$
    - Store normalized pressure values in `pressure[i]`.

### 4.5. Checklist Item 5: Cumulative I/O Bytes

- [ ] **GetProcessIoCounters Query**:
    - Call `GetProcessIoCounters(GetCurrentProcess(), &ioCounters)`.
    - Sum read and write transfer counts:
      $$\text{ioBytes} = \text{ioCounters.ReadTransferCount} + \text{ioCounters.WriteTransferCount}$$
    - Return as cumulative 64-bit byte count (`jlong`).

### 4.6. Checklist Item 6: Zero VLA Compliance & JNI Safety in C++ Native Code

- [ ] **VLA Elimination (Defect N01 Correction)**:
    - Audit `windows_resources.cpp` to ensure no `ULONG64 idleTimes[cpuCount]` stack allocations exist.
    - Replace with bounded stack array (`ULONG64 idleTimes[256]`) or heap buffer (`std::vector<ULONG64>`) with explicit size check (`cpuCount <= 256`).
- [ ] **JNI Pointer & Array Safety**:
    - Verify `GetLongArrayElements` and `GetDoubleArrayElements` return non-NULL pointers before writing.
    - Verify target Java array lengths match expected CPU count before writing.
    - Guarantee `ReleaseLongArrayElements` / `ReleaseDoubleArrayElements` calls in all execution branches.

### 4.7. Checklist Item 7: DetailedSystemSnapshotProvider & SignalValidity Integration

- [ ] **DetailedSystemSnapshotProvider Implementation**:
    - Implement `sampleFast(long requestedAtNs)` and `sampleSlow(long requestedAtNs)`.
- [ ] **Fast Hardware Sample (200 ms Cadence)**:
    - Collect CPU usage ns, job quota fraction, working set memory, cumulative I/O bytes.
    - Construct `FastHardwareSample` with timestamp `requestedAtNs`.
- [ ] **Slow Hardware Sample (5 s Cadence)**:
    - Query `GetSystemPowerStatus(&powerStatus)` for AC line status and battery saver flag.
    - Query nominal CPU frequency.
    - Construct `SlowHardwareSample` with timestamp `requestedAtNs`.
- [ ] **SignalValidity State Tracking**:
    - Tag each signal:
        - `VALID`: Successfully queried and parsed.
        - `TRANSIENT_FAILURE`: Temporary API failure. Retains last valid reading.
        - `UNSUPPORTED`: API not supported on OS version (e.g. Job object not active). Value set to canonical zero, contributes neutrally ($0.0$).

## 5. Sizing & Split Gate Assessment

### Sizing Evaluation

1. **Context Load**: The implementation is strictly bounded to `WindowsResources.java`, `windows_resources.cpp`, and associated test fixtures. The responsibility covers Win32 process API calls, Job Object rate control, working set underflow protection, cycle delta normalization, and P4 sample creation. This fits within the working memory of a single implementation pass.
2. **Single Responsibility**: `WindowsResources` owns Windows resource metric collection. Topology GLPIEx parsing (P6-A) and multi-group thread affinity / PE ABI hardening (P6-C) are cleanly separated.
3. **Independent Validation**: Resource collection can be fully validated using Win32 process mock fixtures and unit tests without requiring multi-group hardware or live native DLL binaries.

**Conclusion**: Child P6-B is irreducible, correctly sized, and ready for implementation in a single pass.

## 6. Implementation Model Reassessment

- **Required Capabilities**: Coupled state machine across Win32 process APIs, Job Object rate control math, working set underflow protection, idle cycle delta normalization, JNI array safety, zero VLA compliance, and signal validity state mapping.
- **Selected Model**: **`gpt-5.6-sol` with `high` reasoning effort**.
- **Justification**: Win32 API contracts, 100-ns to nanosecond unit scaling, working set underflow bounds, dimensional normalization of processor cycle deltas, and zero-VLA native memory safety require high reasoning effort.

## 7. Developer-Review Summary

| Item | Details |
|---|---|
| **Purpose** | Deliver Win32 resource collection (`WindowsResources`, `windows_resources.cpp`), Job Object CPU rate control scaling (`CpuRate / 10000.0`), effective quota CPUs, working set underflow protection (`Math.max(0L, WorkingSetSize - PrivateUsage)`), 100-ns to ns scaling, idle cycle delta normalization, cumulative I/O bytes, zero VLAs in C++, and `SignalValidity` state tracking. |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.windows.WindowsResources`, `src/main/native/windows/windows_resources.cpp`, `io.euhedral_execution.hardware_utils.internal.sampling.*`. |
| **Key Invariants** | Job quota fraction equals `info.CpuRate / 10000.0`; `quotaCpus` equals `quotaFraction * availableCpus`; working set underflow guarded by `Math.max(0L, WorkingSetSize - PrivateUsage)`; `GetProcessTimes` scaled by `100L`; idle cycle deltas normalized against elapsed cycles; cumulative I/O equals `ReadTransferCount + WriteTransferCount`; C++ native code contains zero VLAs; signals carry explicit `SignalValidity`. |
| **Child Action Items** | P6-B implementation: `hardware-utils-overhaul/phase-6-windows-resources-implementation`. |
| **Selected Model** | `gpt-5.6-sol` with `high` reasoning effort for implementation and conformance audit. |
| **Principal Risks** | Dimensionally invalid cycle count division; negative working set values on high private commit charge; VLA stack allocation in JNI; missing JNI NULL checks; 100-ns to ns multiplication overflow. |
| **Unresolved Items** | None. Win32 APIs, Job Object scaling, memory underflow math, time unit scaling, cycle delta normalization, and signal cadences are fully settled. |

## 8. Verification & Acceptance Criteria

### 8.1. Acceptance Criteria

1. **Job Object CPU Quota Scaling**:
    - Given Job Object with `CpuRate` = 5000 (50.0%) on an 8-CPU system, `getCpuQuota()` returns `0.50` and `quotaCpus` equals `4.0`.
2. **Working Set Underflow Protection**:
    - Given `WorkingSetSize` = 100 MB and `PrivateUsage` = 120 MB, shared memory calculation returns `0L` (guarded by `Math.max(0L, ...)`), avoiding negative memory values.
3. **Process CPU Times 100-ns Scaling**:
    - Given `GetProcessTimes` returning `kernelTime` = 1,000,000 (100-ns) and `userTime` = 2,000,000 (100-ns), `getCpuTimes` returns `300,000,000L` nanoseconds.
4. **Idle Cycle Delta Normalization**:
    - Given consecutive `QueryIdleProcessorCycleTime` calls over interval $\Delta t$, per-CPU busy ratios are calculated from cycle deltas normalized against elapsed cycles, producing valid values in $[0.0, 1.0]$.
5. **Cumulative I/O Bytes**:
    - Given `ReadTransferCount` = 500,000 and `WriteTransferCount` = 300,000, `getIoBytes()` returns `800,000L`.
6. **Zero VLA Compliance & Signal Validity**:
    - `windows_resources.cpp` contains zero VLAs. Fast and slow hardware samples include explicit `SignalValidity` state tags.

### 8.2. Verification Commands

```bash
# Build hardware-utils module and run Windows resource tests
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.windows.*"

# Run all hardware-utils tests
gradle :euhedral-hardware-utils:test
```

## 9. Completion Record

### Changed Files

- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/WindowsResources.java`
- `euhedral-hardware-utils/src/main/native/windows/windows_resources.cpp`
- `euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/windows/WindowsResourcesTest.java`

### Commands Run & Results

- `gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.windows.*"` -> (To be populated by implementation pass)
- `gradle :euhedral-hardware-utils:build` -> (To be populated by implementation pass)

### Acceptance Evidence

- (To be populated by implementation pass)

### Approved Deviations

- None.

### Environmental Limits

- Live native JNI platform tests require Windows OS host; mock Win32 fixtures used for cross-environment verification.
