# Phase 6 Windows Platform Blueprint: Topology, Resource Collection, Multi-Group Affinity, and Native Parity

## 1. Executive Summary & Objective

This blueprint establishes the architecture, technical contracts, data flows, and child
responsibilities for **Phase 6 (Windows Parity, Processor Groups, Job Objects, and PE ABI)** of the
`euhedral-hardware-utils` overhaul, as governed
by [docs/plans/hardware-utils-platform-parity-overhaul.md](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md).

The objective of Phase 6 is to deliver a robust, truthful, read-only Windows hardware and resource
provider supporting systems with 1 to 64+ logical processors spanning multiple Windows processor
groups. This implementation eliminates legacy defects (T03, A03, R04, N01, B06), adheres strictly to
the common sampling and pressure engine established in Phase 4, enforces safe Win32 API interactions
with dynamic symbol fallbacks for Windows 10 / Server 2016 baseline environments, and guarantees
thread-safe, VLA-free native JNI operations.

## 2. Scope & Non-Goals

### 2.1. In Scope

- Bounded win32 `GetLogicalProcessorInformationEx` (GLPIEx) binary parsing with exact structure
  offsets, alignment, and bounds validation.
- Handling systems with >64 processors, processor groups, bit 63 in KAFFINITY masks
  (`0x8000000000000000L`), and bijective mapping between `(group, processor)` pairs and Euhedral
  logical CPU IDs.
- Core classification (P-core vs E-core) via `PROCESSOR_RELATIONSHIP.EfficiencyClass` and SMT
  identification.
- Windows Job Object CPU rate control (`JOBOBJECT_CPU_RATE_CONTROL_INFORMATION`) and memory limit
  discovery (`JOBOBJECT_EXTENDED_LIMIT_INFORMATION`), converting `CpuRate` hundredths-of-percent
  values into normalized quota fractions (`CpuRate / 10000.0`).
- Process affinity intersection and effective CPU set determination.
- Canonical cumulative unit extraction (nanoseconds for CPU times, bytes for memory and I/O).
- Fixing working set subtraction underflow (`WorkingSetSize - PrivateUsage` protected by
  `Math.max(0L, ...)`).
- Idle processor cycle time delta calculation (`QueryIdleProcessorCycleTime`) normalized against
  elapsed time or performance counter frequency (`QueryPerformanceFrequency`).
- Multi-group thread affinity application (`SetThreadSelectedCpuSetMasks` on Win10 1607+, fallback
  to `SetThreadGroupAffinity`), with deterministic rejection (`false`) rather than partial
  multi-group application.
- Restoration of original thread group affinity on lease release.
- Current logical processor query via `GetCurrentProcessorNumberEx` (or `GetCurrentProcessorNumber`
  fallback).
- Dynamic JNI timer resolution (`NtSetTimerResolution`) with thread-safe native initialization,
  `std::atomic<bool>` protection, and idempotent JVM shutdown hook release.
- Zero VLA usage in C++ native code, replaced by fixed stack arrays or bounded heap buffers.
- Native PE ABI hardening for x86-64 (Windows 10 / Server 2016 floor) and ARM64 (Windows 11 floor),
  with zero C++ runtime dependencies (`-fno-exceptions -fno-rtti`) and stack protector symbols
  provided in `windows_hardening.cpp`.
- Windows fixture suites (binary GLPIEx blobs) and runtime unit/integration tests.

### 2.2. Non-Goals

- Modifying or redesigning common pressure formulas or normalization curves established in Phase 4.
- Linux or macOS implementation changes (reserved for Phase 5 and Phase 7).
- Modifying core fragment execution or action-picker policies (reserved for Phase 8).
- Any inspection, build, or test activity involving `euhedral-training`.

## 3. Core Architectural & Technical Contracts

### 3.1. Bounded GLPIEx Parsing & Native Alignment

- **Structure Layout**: Windows win32 API returns a sequence of
  `SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX` structures.
  ```c
  typedef struct _SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX {
    LOGICAL_PROCESSOR_RELATIONSHIP Relationship; // DWORD (4 bytes), offset 0
    DWORD                          Size;         // DWORD (4 bytes), offset 4
    union {
      PROCESSOR_RELATIONSHIP Processor;          // offset 8
      NUMA_NODE_RELATIONSHIP NumaNode;           // offset 8
      CACHE_RELATIONSHIP     Cache;              // offset 8
      GROUP_RELATIONSHIP     Group;              // offset 8
    } DUMMYUNIONNAME;
  } SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX;
  ```
- **Header & Offset Rules**:
    - Structure Header = 8 bytes (`Relationship` + `Size`).
    - Total structure size is given by `Size` field (including header).
    - Offset calculation MUST advance buffer position by `Size` bytes per record, NOT `Size + 8`
      bytes (correcting defect T03 where legacy parser double-counted header offset).
- **Sub-Structure Fields & Offsets**:
    - `PROCESSOR_RELATIONSHIP` (at offset 8 of EX record):
        - `Flags`: BYTE at offset 8. Bit 0 (`LTP_PC_SMT` = 0x01) indicates SMT / hyperthreading.
        - `EfficiencyClass`: BYTE at offset 9. Value 0 indicates E-core (or homogeneous system);
          value >0 indicates P-core performance class.
        - `Reserved[20]`: BYTE[20] at offsets 10..29.
        - `GroupCount`: WORD (2 bytes, little-endian) at offsets 30..31.
        - `GroupMask`: Array of `GROUP_AFFINITY` structs starting at offset 32.
    - `CACHE_RELATIONSHIP` (at offset 8 of EX record):
        - `Level`: BYTE at offset 8. (1 = L1, 2 = L2, 3 = L3).
        - `Associativity`: BYTE at offset 9.
        - `LineSize`: WORD (2 bytes) at offsets 10..11.
        - `CacheSize`: DWORD (4 bytes) at offsets 12..15.
        - `Type`: PROCESSOR_CACHE_TYPE enum (4 bytes) at offsets 16..19 (0 = Unified, 1 =
          Instruction, 2 = Data).
        - `Reserved[18]`: BYTE[18] at offsets 20..37.
        - `GroupCount`: WORD (2 bytes) at offsets 38..39 (or present inside GroupMask array header).
        - `GroupMask`: Array of `GROUP_AFFINITY` structs starting at offset 40 (or following
          GroupCount).
    - `GROUP_AFFINITY` Struct (16 bytes total):
        - `Mask`: KAFFINITY (ULONGLONG, 8 bytes) at offsets 0..7.
        - `Group`: WORD (2 bytes) at offsets 8..9.
        - `Reserved[3]`: WORD[3] (6 bytes) at offsets 10..15.
- **Bounds Checking & Truncated Buffer Validation**:
    - Buffer parser MUST validate `buffer.remaining() >= 8` before reading header.
    - Record size MUST satisfy `Size >= 8` and `pos + Size <= buffer.capacity()`.
    - For `PROCESSOR_RELATIONSHIP`, `Size` MUST satisfy `Size >= 32 + GroupCount * 16`.
    - For `CACHE_RELATIONSHIP`, `Size` MUST satisfy `Size >= 40 + GroupCount * 16` (or minimum size
      for single-group header).
    - Malformed or truncated buffers MUST throw an `IllegalArgumentException` with exact position
      and byte offset diagnostics, cleanly caught by fallback logic.

### 3.2. Processor Group Mapping & Bijective Euhedral Logical ID Mapping

- **Windows Processor Groups**:
    - Windows 64-bit limits a single processor group to 64 logical processors.
    - Systems with >64 processors are divided into multiple processor groups (e.g. Group 0, Group
      1).
- **Bijective Logical ID Mapping Invariants**:
    - Global Logical ID Mapping: `logicalId = group * 64 + processor`
    - Reverse Mapping: `group = (short)(logicalId / 64)`, `processor = logicalId % 64`,
      `mask = 1L << (logicalId % 64)`.
- **Bit 63 & Mask Math Safety**:
    - Processor 63 within a group corresponds to KAFFINITY mask bit 63 (`0x8000000000000000L`).
    - Java long bit-shift math MUST use unsigned operations or explicit masks to prevent signed
      sign-extension defects:
      ```java
      long maskBit = 1L << processor; // for processor 63, maskBit is 0x8000000000000000L
      ```
    - Mask testing MUST use `(mask & (1L << bit)) != 0L` rather than `> 0L`.
- **Topology Normalization**:
    - `WindowsSystemLayout` parses GLPIEx records and constructs a `TopologyInput` containing:
        - Package ownership derived from `PROCESSOR_PACKAGE` relationships.
        - Global core keys `(packageId, dieId, coreId)` and SMT classification from `PROCESSOR_CORE`
          relationships.
        - P-core / E-core classification from `EfficiencyClass`.
        - Cache domains (L1, L2, L3) with BitSet masks spanning global logical IDs
          (`group * 64 + processor`).

### 3.3. Multi-Group Affinity, Current Processor Ownership, & Lease Contract

- **Multi-Group Mask Format**:
    - Affinity masks passed across JNI boundary use `long[] masks` where array index `i` represents
      Windows Processor Group `i`, and element `masks[i]` represents the 64-bit KAFFINITY bitmask
      for Group `i`.
- **Affinity Setter Semantics**:
    - If a requested mask spans multiple groups (more than one non-zero word in `masks[]`):
        - Call Win32 `SetThreadSelectedCpuSetMasks` (if available on Windows 10 1607+ / Server
          2016+).
        - If `SetThreadSelectedCpuSetMasks` is unavailable or fails, or if the OS fails multi-group
          affinity, the call MUST return `false` (deterministic rejection).
        - It MUST NOT apply a partial mask to Group 0 while reporting success (correcting defect
          A03).
    - If a requested mask spans exactly one group (index `g` with non-zero mask):
        - Use `SetThreadGroupAffinity` with `GROUP_AFFINITY` struct (`Group = g`,
          `Mask = masks[g]`).
- **Affinity Lease & Restoration**:
    - Before applying thread affinity,
      `GetThreadGroupAffinity(GetCurrentThread(), &previousGroupAffinity)` captures original group
      affinity.
    - On lease release/close,
      `SetThreadGroupAffinity(GetCurrentThread(), &previousGroupAffinity, NULL)` restores the
      original group mask.
- **Current Processor Query**:
    - Native `getCpu()` calls `GetCurrentProcessorNumberEx(&processorNumber)` (returning
      `PROCESSOR_NUMBER` with `Group` and `Number`).
    - Returns global logical ID: `(int)processorNumber.Group * 64 + (int)processorNumber.Number`.
    - Fallback for legacy single-group hosts: `(int)GetCurrentProcessorNumber()`.

### 3.4. Dynamic API Lookup & Fallbacks

- **Dynamic Kernel32 Symbol Resolution**:
    - `SetThreadSelectedCpuSetMasks`: Resolved via
      `GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "SetThreadSelectedCpuSetMasks")`. If NULL,
      multi-group affinity falls back to deterministic rejection unless single-group.
    - `GetLogicalProcessorInformationEx`: Resolved dynamically or linked against Kernel32 (available
      Windows 7+).
    - `GetCurrentProcessorNumberEx`: Resolved dynamically or linked against Kernel32.
- **Dynamic Timer Resolution (`NtSetTimerResolution`)**:
    - Function pointer `pfnNtSetTimerResolution` resolved dynamically from `ntdll.dll`.
    - Native initialization uses `std::atomic<bool>` once-only thread-safe execution.
    - Java `WindowsAffinity.setTimerResolution(long nanos)` converts nanoseconds to 100-ns units
      (`nanos / 100L`), enforces non-negative bounds, and tracks applied resolution.
    - Registers a JVM shutdown hook (`win-timer-release`) that calls
      `ntSetTimerResolution(appliedResolution, false)` for idempotent cleanup.

### 3.5. Job Objects, Quota, & Process Restrictions

- **Job Object CPU Quota (Defect R04 Correction)**:
    - Query Job Object via
      `QueryInformationJobObject(NULL, JobObjectCpuRateControlInformation, &info, sizeof(info), NULL)`.
    - Check `info.ControlFlags & JOB_OBJECT_CPU_RATE_CONTROL_ENABLE`.
    - `info.CpuRate` is expressed in hundredths of a percent (e.g., 10000 = 100.0%, 5000 = 50.0%,
      20000 = 200.0%).
    - Quota fraction calculation:
      $$\text{quotaFraction} = \frac{\text{info.CpuRate}}{10000.0}$$
    - Effective quota CPUs:
      $$\text{quotaCpus} = \begin{cases} \text{quotaFraction} \times \text{availableCpus}, & \text{if Job CpuRate enabled} \\ \text{availableCpus}, & \text{otherwise (-1.0 returned by native caller)} \end{cases}$$
    - Legacy code treated `info.CpuRate` fraction directly as CPU count or used wrong scaling
      factor. The corrected invariant scales by `availableCpus`.
- **Job Memory Limit**:
    - Query `JobObjectExtendedLimitInformation`.
    - If `JOB_OBJECT_LIMIT_JOB_MEMORY` is set, `JobMemoryLimit` defines maximum memory in bytes.
    - Else if `JOB_OBJECT_LIMIT_PROCESS_MEMORY` is set, `ProcessMemoryLimit` defines maximum memory
      in bytes.

### 3.6. Canonical Cumulative Counters & Unit Normalization

- **Process CPU Times**:
    - `GetProcessTimes(GetCurrentProcess(), &createTime, &exitTime, &kernelTime, &userTime)`.
    - `FILETIME` represents 100-nanosecond intervals.
    - Total process CPU time in nanoseconds:
      $$\text{cpuUsageNs} = (\text{kernelTime.QuadPart} + \text{userTime.QuadPart}) \times 100\text{L}$$
- **Per-CPU Idle Cycles vs Time (Defect R04 Correction)**:
    - `QueryIdleProcessorCycleTime(&bufferSize, idleTimes)` returns raw processor cycle counts
      (`ULONG64`).
    - Legacy code divided raw cycle counts directly by nanosecond time ($dt$), producing
      dimensionally invalid numbers.
    - Corrected calculation:
        - Track cycle deltas $\Delta \text{idleCycle}_i$ per processor over interval.
        - Normalize against total elapsed cycles or elapsed nanoseconds converted to cycles via
          `QueryPerformanceFrequency` / CPU nominal clock frequency.
- **Process I/O Bytes**:
    - `GetProcessIoCounters(GetCurrentProcess(), &ioCounters)`.
    - Cumulative bytes read and written:
      $$\text{ioBytes} = \text{ioCounters.ReadTransferCount} + \text{ioCounters.WriteTransferCount}$$

### 3.7. Memory Units & Working Set Underflow Protection

- **Memory Counters Extraction**:
    - `GetProcessMemoryInfo(GetCurrentProcess(), (PROCESS_MEMORY_COUNTERS*)&pmc, sizeof(pmc))`.
    - `WorkingSetSize`: Total working set memory in bytes (`jlong`).
    - `PrivateUsage`: Commit charge / private working set in bytes (`jlong`).
- **Underflow Guard (Defect R04 Correction)**:
    - Shared working set calculation:
      $$\text{sharedMemory} = \text{Math.max} (0\text{L}, (\text{jlong})\text{pmc.WorkingSetSize} - (\text{jlong})\text{pmc.PrivateUsage})$$
    - Prevents negative memory values when `PrivateUsage > WorkingSetSize`.
- **Total System Memory**:
    - Query `GlobalMemoryStatusEx(&memStatus)`.
    - `ullTotalPhys`: Total physical RAM in bytes (`jlong`).
    - Effective total memory uses `JobMemoryLimit` if restricted, otherwise `ullTotalPhys`.

### 3.8. Scheduler, Power, & Thermal Signals

- **Power & Thermal Availability**:
    - Call `GetSystemPowerStatus(&powerStatus)`.
    - `ACLineStatus`: 0 = offline (battery), 1 = online (AC power), 255 = unknown.
    - `SystemStatusFlag`: Bit 0 = battery saver active (low-power mode).
- **Signal Validity & Cadences**:
    - Fast Cadence (200 ms): Process CPU times, job quota, memory working set, I/O bytes.
    - Slow Cadence (5 s): System power status, battery saver state, processor frequency.
    - Validity state tracking (`SignalValidity.VALID`, `STALE`, `UNSUPPORTED`) ensures absent
      sensors contribute neutrally ($0.0$).

### 3.9. Native Memory Safety, Buffer Validation, & Thread-Safe Initialization

- **Zero VLA Rule (Defect N01 Correction)**:
    - C++ native functions MUST NOT use Variable Length Arrays (e.g. `BYTE buffer[length]` or
      `GROUP_AFFINITY affinities[len]`).
    - All native stack allocations MUST use fixed-size arrays bounded by maximum supported limits
      (e.g. `GROUP_AFFINITY affinities[64]`), or dynamically allocated heap buffers
      (`std::vector<BYTE>` / `malloc`) with explicit NULL / allocation failure checks and guaranteed
      cleanup (`free` / RAII).
- **JNI Buffer Validation**:
    - `GetLongArrayElements` / `GetDoubleArrayElements` MUST check for NULL return values before
      dereferencing.
    - JNI array lengths MUST be verified against expected target capacity before writing native data
      (`env->GetArrayLength(buffer) >= expectedLen`).
- **Thread-Safe Native Initialization**:
    - Native global state initialization MUST use `std::atomic<bool>` with
      `std::memory_order_acq_rel` or `atomic_exchange` to ensure once-only, thread-safe execution
      without initialization races.

### 3.10. PE ABI, Import Floor, Hardening, & Target Validation

- **Target OS & Architecture Floors**:
    - Windows x86-64: Windows 10 / Server 2016 baseline.
    - Windows ARM64: Windows 11 baseline.
- **Compiler & CRT Policy**:
    - C++ native code compiled via Zig `zig build-lib` / `zig build`.
    - Compilation flags MUST include `-fno-exceptions -fno-rtti` to eliminate C++ runtime overhead.
    - Forbidden DLL imports: `msvcrt.dll`, `vcruntime140.dll`, `msvcp140.dll`, or any dynamic C++
      runtime.
    - Allowed DLL imports: `kernel32.dll`, `psapi.dll`, `ntdll.dll`.
- **Stack Protector & ABI Hardening**:
    - Stack protector ABI state and symbol stubs provided in `windows_hardening.cpp`
      (`__stack_chk_guard`, `__stack_chk_fail`, `___chkstk_ms`, `__chkstk`).

## 4. Data Surface, Package Ownership, & Data Flow

### 4.1. Package & Source Ownership

- **Java Sources**: `io.euhedral_execution.hardware_utils.windows.*`
    - `WindowsSystemLayout`: Topology provider, GLPIEx parser, processor group normalization.
    - `WindowsResources`: System snapshot provider, job quota, process CPU/memory/IO metrics.
    - `WindowsAffinity`: Native ThreadPinner implementation, multi-group affinity, timer resolution.
    - `WindowsAffinityCalls`: Little-endian mask validation and raw call dispatcher.
    - `win32.*`: Struct representations (`SystemLogicalProcessorInformation`,
      `ProcessorRelationship`, `CacheRelationship`, `GroupAffinity`, `Relationship`).
- **Native Sources**: `euhedral-hardware-utils/src/main/native/windows/`
    - `windows_jni.h`: Win32 JNI function pointer declarations and struct headers.
    - `windows_system_layout.cpp`: Native `getRawTopologyInfo` using GLPIEx.
    - `windows_resources.cpp`: Native process CPU times, job quota, cycle times, memory snapshot,
      I/O counters.
    - `windows_affinity.cpp`: Native `setThreadAffinity`, `getCpu`, `ntSetTimerResolution`.
    - `windows_hardening.cpp`: Stack protector symbols and chkstk ABI helpers.
- **Manifest & CI Metadata**: `native-products.json`, `.github/workflows/` Windows smoke matrix.

### 4.2. Windows Native-to-Java Data Flow Diagram

```text
Win32 Kernel APIs / Job Objects / NtDll
(GetLogicalProcessorInformationEx, GetProcessTimes, QueryInformationJobObject, NtSetTimerResolution)
                      |
                      v
      Native C++ JNI Wrappers (windows_*.cpp)
      - VLA-Free Stack/Heap Buffers
      - Thread-Safe std::atomic<bool> Initialization
      - Null & Array Length Checks
                      |
                      v
        WindowsSystemLayout / WindowsResources / WindowsAffinity
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

### 5.1. Child P6-A: Windows Topology Model & GLPIEx Parser (`phase-6-windows-topology`)

- **Required Inputs**: P2 Topology Model contracts (`TopologyInput`, `LogicalCpu`,
  `TopologyBootstrap`), GLPIEx binary fixtures.
- **Owned Outputs**: `WindowsSystemLayout`, `win32.*` struct parsers, GLPIEx structure offset
  alignment, bit 63 mask math, bijective `(group, processor)` to logical ID mapping, multi-group
  cache domains, P/E core classification.

### 5.2. Child P6-B: Windows Job & Resource Provider (`phase-6-windows-resources`)

- **Required Inputs**: P4 Sampling Engine (`DetailedSystemSnapshotProvider`, `FastHardwareSample`,
  `SlowHardwareSample`, `SignalValidity`), Win32 process/job API contracts.
- **Owned Outputs**: `WindowsResources`, job quota fraction calculation (`CpuRate / 10000.0`),
  effective quota CPUs, working set underflow protection
  (`Math.max(0L, WorkingSetSize - PrivateUsage)`), idle cycle time delta normalization, cumulative
  I/O bytes.

### 5.3. Child P6-C: Windows Affinity, Multi-Group Processor Sets & Native ABI (

`phase-6-windows-affinity-native`)

- **Required Inputs**: P3 Affinity contracts (`ThreadPinner`, `AffinityCapability`,
  `AffinityMasks`), P1 Zig native build graph (`native-products.json`).
- **Owned Outputs**: `WindowsAffinity`, `WindowsAffinityCalls`, `windows_affinity.cpp`,
  `windows_resources.cpp`, `windows_system_layout.cpp`, `windows_hardening.cpp`, `windows_jni.h`,
  multi-group affinity application with deterministic rejection (`false`), lease restoration,
  `NtSetTimerResolution` lifecycle, zero VLA compliance, PE import/ABI hardening gates.

## 6. Sizing & Split Gate Assessment

### 6.1. Sizing Evaluation

Evaluating Phase 6 against the workflow sizing gate:

1. **Context Load**: Combining GLPIEx binary structure parsing, multi-group processor mapping, job
   quota calculation, idle cycle delta normalization, Win32 thread affinity, dynamic symbol loading,
   VLA elimination, and PE import hardening exceeds the working memory of a single non-frontier
   implementation agent.
2. **Independent Responsibilities**: Topology parsing (GLPIEx), resource collection (Job Objects /
   process metrics), and native affinity/PE ABI have clear package and operational boundaries.
3. **Independent Validation**: Topology parsing can be fully validated via binary GLPIEx fixtures;
   resource collection via Win32 process/job mock contracts; and native affinity via JNI boundary
   tests and PE binary gates.

### 6.2. Action Plan & Child Branches

Phase 6 is split into three responsibility-scoped child action items:

1. **P6-A (Windows Topology Model & GLPIEx Parser)**:
    - Branch: `hardware-utils-overhaul/phase-6-windows-topology-blueprint`
    - Blueprint: `docs/blueprints/hardware-utils/phase-6-windows-topology-model.md`
    - Implementation: `hardware-utils-overhaul/phase-6-windows-topology-implementation`
    - Audit: `docs/audits/hardware-utils/phase-6-windows-topology-model-conformance.md`
2. **P6-B (Windows Job & Resource Provider)**:
    - Branch: `hardware-utils-overhaul/phase-6-windows-resources-blueprint`
    - Blueprint: `docs/blueprints/hardware-utils/phase-6-windows-resource-provider.md`
    - Implementation: `hardware-utils-overhaul/phase-6-windows-resources-implementation`
    - Audit: `docs/audits/hardware-utils/phase-6-windows-resource-provider-conformance.md`
3. **P6-C (Windows Affinity, Multi-Group Processor Sets & Native ABI)**:
    - Branch: `hardware-utils-overhaul/phase-6-windows-affinity-native-blueprint`
    - Blueprint: `docs/blueprints/hardware-utils/phase-6-windows-affinity-native.md`
    - Implementation: `hardware-utils-overhaul/phase-6-windows-affinity-native-implementation`
    - Audit: `docs/audits/hardware-utils/phase-6-windows-affinity-native-conformance.md`
4. **Root Phase Audit**:
    - Branch: `hardware-utils-overhaul/phase-6-windows-audit`
    - Audit: `docs/audits/hardware-utils/phase-6-windows-platform-conformance.md`

Only after this parent blueprint child is merged may child branches be created from the updated P6
root. Each child blueprint must rerun the sizing gate.

## 7. Mandatory Implementation Model Reassessment

Reassessing implementation model requirements across the child responsibilities:

- **Child P6-A (Topology)**: High context requirements around GLPIEx structure offsets, bit 63 mask
  math, processor group mapping, and cache fallbacks. Selected implementation model: **`gpt-5.6-sol`
  with `high` reasoning**.
- **Child P6-B (Resources)**: Coupled state machine across Job Objects, process metrics, quota
  fraction scaling, working set underflow protection, and cycle time delta calculation. Selected
  implementation model: **`gpt-5.6-sol` with `high` reasoning**.
- **Child P6-C (Affinity & Native ABI)**: JNI array safety, Win32 dynamic API resolution,
  multi-group deterministic rejection, VLA elimination, timer resolution shutdown hooks, and PE
  import/ABI hardening gates. Selected implementation model: **`gpt-5.6-sol` with `high`
  reasoning**.
- **Child & Root Audits**: Strong coding/audit model: **`gpt-5.6-sol` with `high` reasoning**.

Downgrading to low or medium reasoning is not supported due to coupled Win32 system contracts,
memory safety, and processor group rules.

## 8. Developer-Review Summary

| Item                   | Details                                                                                                                                                                                                                                                                                                                                 |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Purpose**            | Deliver GLPIEx topology parsing, multi-group processor mapping, Job Object quota and memory tracking, multi-group thread affinity with lease restoration, VLA-free native code, and PE ABI hardening for Windows 10/11 platforms.                                                                                                       |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.windows.*` (Java), `src/main/native/windows/*` (C++), `native-products.json` (Manifest).                                                                                                                                                                                                          |
| **Key Contracts**      | Exact GLPIEx structure offsets; bijective `(group, processor)` to logical ID mapping; `CpuRate / 10000.0` quota scaling; `WorkingSetSize - PrivateUsage` underflow protection; deterministic multi-group affinity rejection (`false`); timer resolution shutdown hook cleanup; zero VLAs in C++; PE ABI hardening without C++ runtimes. |
| **Child Action Items** | P6-A (Topology & GLPIEx), P6-B (Resources & Job Objects), P6-C (Affinity & PE ABI).                                                                                                                                                                                                                                                     |
| **Selected Model**     | `gpt-5.6-sol` with `high` reasoning for all implementation and audit action items.                                                                                                                                                                                                                                                      |
| **Principal Risks**    | Win32 structure alignment across 64-bit boundaries; processor group mask bit 63 signed shift bugs; legacy cycle count time division; VLA stack allocations in JNI.                                                                                                                                                                      |
| **Unresolved Items**   | None. GLPIEx offsets, group mapping, units, underflow bounds, affinity fallbacks, timer hooks, PE floors, and CRT policies are fully settled.                                                                                                                                                                                           |
