# Phase 6 Windows Platform Conformance Audit: Topology, Resource Collection, Multi-Group Affinity, and PE ABI

## Scope and Disposition

Audited `hardware-utils-overhaul/phase-6-windows-audit` from the updated P6 root branch `hardware-utils-overhaul/phase-6-windows` (at commit `04b0111`). The audit evaluated the end-to-end Windows platform implementation (`WindowsSystemLayout`, `WindowsResources`, `WindowsAffinity`, `win32.*`, and native JNI C++ modules `windows_*.cpp`) against:
- Parent Plan: [`docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md)
- Parent Blueprint: [`docs/blueprints/hardware-utils/phase-6-windows-platform.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-6-windows-platform.md)
- Child Blueprint P6-A (Topology & GLPIEx): [`docs/blueprints/hardware-utils/phase-6-windows-topology-model.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-6-windows-topology-model.md)
- Child Blueprint P6-B (Resources & Job Objects): [`docs/blueprints/hardware-utils/phase-6-windows-resource-provider.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-6-windows-resource-provider.md)
- Child Blueprint P6-C (Affinity & PE ABI): [`docs/blueprints/hardware-utils/phase-6-windows-affinity-native.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-6-windows-affinity-native.md)

Inspection covered Java classes in `io.euhedral_execution.hardware_utils.windows.*`, C++ native sources in `euhedral-hardware-utils/src/main/native/windows/*`, binary GLPIEx fixtures, and unit/integration test suites in `io.euhedral_execution.hardware_utils.windows.*` and `io.euhedral_execution.hardware_utils.compatibility.*`.

**Disposition: Satisfied and ready for merge.** All 5 Windows defect ledger items (**T03, A03, R04, N01, B06**) and all core platform requirements are classified as **`satisfied`**.

## Defect Ledger & Acceptance Matrix

| Requirement / Ledger Item | Classification | Evidence |
|---------------------------|----------------|----------|
| **Defect T03**: GLPIEx Header Offset | satisfied | `SystemLogicalProcessorInformation.java` advances buffer position strictly by record `size` bytes (`pos += size`), eliminating double-advancement of 8-byte header. Verified by `WindowsTopologyFixtureTest.testMultiRecordGlpiexBlob()`. |
| **Defect A03**: Multi-Group Affinity Partial Application | satisfied | `windows_affinity.cpp` invokes `SetThreadSelectedCpuSetMasks` for multi-group requests or returns `-1` (deterministic rejection). `WindowsAffinity.java` converts non-zero native return to `false`, preventing partial mask application on Group 0. Verified by `WindowsAffinityTest.testMultiGroupRejection()`. |
| **Defect R04**: Job Quota & Idle Cycle Normalization | satisfied | `windows_resources.cpp` converts `info.CpuRate` hundredths-of-percent into quota fraction (`info.CpuRate / 10000.0`), and `WindowsResources.java` scales quota fraction by `availableCpus`. Memory shared calculations guard against underflow (`Math.max(0L, ws - priv)`). `computeCpuPressure` normalizes idle cycle deltas against interval time. Verified by `WindowsResourcesTest`. |
| **Defect N01**: C++ Variable Length Array (VLA) | satisfied | All C++ native files (`windows_system_layout.cpp`, `windows_resources.cpp`, `windows_affinity.cpp`) contain zero VLAs. Stack buffers use fixed compile-time sizes (`[64]`, `[256]`) or dynamic `malloc`/`free` heap allocations with NULL checks. Verified by `NativeBinaryInspectionIT.testNoVlaInCppSources()`. |
| **Defect B06**: Non-Portable ABI / CRT Dependency | satisfied | `windows_hardening.cpp` provides stack protector ABI symbols (`__stack_chk_guard`, `__stack_chk_fail`, `___chkstk_ms`, `__chkstk`). C++ compiled with `-fno-exceptions -fno-rtti`. Dynamic DLL inspection confirms zero imports from `msvcrt.dll`, `vcruntime140.dll`, or `msvcp140.dll`. Verified by `NativeBinaryInspectionIT.testPeImportTable()`. |
| **1. Bijective Processor Group ID Mapping** | satisfied | `WindowsSystemLayout.java` bijectively maps `(group, processor)` to `logicalId = group * 64 + processor`, and reverse maps `logicalId / 64` and `logicalId % 64`. Verified by `WindowsTopologyFixtureTest.testMultiGroupLogicalIdMapping()`. |
| **2. Bit 63 KAFFINITY Mask Math Safety** | satisfied | `GroupAffinity.java` and `WindowsSystemLayout.java` perform unsigned mask testing using `(mask & (1L << bit)) != 0L`. Mask for CPU 63 (`0x8000000000000000L`) evaluates correctly without sign-extension traps. Verified by `WindowsTopologyFixtureTest.testBit63KaffinityMask()`. |
| **3. P/E Core & SMT Classification** | satisfied | `ProcessorRelationship.java` parses `EfficiencyClass` (payload offset 1) and SMT `Flags` (payload offset 0). Cores are mapped to `CoreKind.PERFORMANCE` vs `CoreKind.EFFICIENCY` or `CoreKind.UNKNOWN` when scores are homogeneous. Verified by `WindowsTopologyFixtureTest.testPeCoreClassification()`. |
| **4. Process CPU Times 100-ns Scaling** | satisfied | `windows_resources.cpp` reads `FILETIME` kernel and user times, summing them and multiplying by `100L` to convert 100-ns intervals to nanoseconds. Verified by `WindowsResourcesTest.testGetCpuTimesScaling()`. |
| **5. Cumulative I/O Bytes** | satisfied | `windows_resources.cpp` calls `GetProcessIoCounters` and sums `ReadTransferCount + WriteTransferCount`. Verified by `WindowsResourcesTest.testGetIoBytes()`. |
| **6. Thread Group Affinity Restoration** | satisfied | `WindowsAffinity.java` and `windows_affinity.cpp` capture original group affinity via `GetThreadGroupAffinity` during lease acquisition, and restore it via `SetThreadGroupAffinity` on lease release. Verified by `WindowsAffinityTest.testLeaseRestoration()`. |
| **7. Current Processor Ownership Query** | satisfied | `windows_affinity.cpp` queries `GetCurrentProcessorNumberEx(&procNum)` and returns global logical ID `group * 64 + processor`, with fallback to `GetCurrentProcessorNumber()`. Verified by `WindowsAffinityTest.testGetCpu()`. |
| **8. Dynamic Timer Resolution Lifecycle** | satisfied | `WindowsAffinity.java` converts nanoseconds to 100-ns units (`nanos / 100L`), invokes native `NtSetTimerResolution` dynamically resolved from `ntdll.dll` via `std::atomic<bool>` init, and registers JVM shutdown hook `win-timer-release`. Verified by `WindowsAffinityTest.testSetTimerResolution()`. |
| **9. Malformed Buffer Validation & Fallback** | satisfied | `SystemLogicalProcessorInformation.java` enforces buffer capacity checks (`Size >= 8`, `pos + Size <= limit`), throwing `IllegalArgumentException` on malformed buffers to trigger P2 single-socket fallback topology cleanly. Verified by `WindowsTopologyFixtureTest.testMalformedBufferFallback()`. |

## Detailed Technical Audit

### 1. GLPIEx Topology Parsing & Processor Group Mapping (Defect T03 Fix)

`SystemLogicalProcessorInformation.java` parses win32 `GetLogicalProcessorInformationEx` binary records.
- **Record Offset Advancement**: Header size is 8 bytes (`Relationship` DWORD at offset 0, `Size` DWORD at offset 4). Record length is `Size` bytes including header. Outer loop advances `pos += size` bytes per record, strictly resolving Defect T03 where legacy parsers added `Size + 8` bytes.
- **Bijective Multi-Group ID Mapping**: `WindowsSystemLayout.java` translates Windows processor group coordinates `(group, processor)` into Euhedral global logical CPU IDs via `logicalId = group * 64 + processor`.
- **Bit 63 Mask Safety**: KAFFINITY bitmasks spanning processor 63 (`0x8000000000000000L`) use unsigned bit operations `(mask & (1L << bit)) != 0L` to prevent signed shift sign-extension defects.
- **P/E Core Classification**: Cores are classified via `PROCESSOR_RELATIONSHIP.EfficiencyClass` byte (offset 1). Non-zero scores classify cores into `CoreKind.PERFORMANCE` vs `CoreKind.EFFICIENCY`, while uniform scores yield `CoreKind.UNKNOWN`.
- **Cache Domain Assembly**: `CacheRelationship.java` parses cache level, line size, and cache size, excluding `CacheType.INSTRUCTION` (type 1) and assembling `BitSet` cache domains spanning global logical IDs across all active processor groups.
- **Buffer Safety**: Truncated headers or length overruns throw `IllegalArgumentException`, caught by `WindowsSystemLayout` to return a safe single-socket fallback topology.

### 2. Job Objects & Resource Provider (Defect R04 Fix)

`WindowsResources.java` and `windows_resources.cpp` collect system utilization and process metrics.
- **Job Object Quota Scaling**: `windows_resources.cpp` queries `JobObjectCpuRateControlInformation`. If enabled, `info.CpuRate` (expressed in hundredths of a percent, e.g. 5000 = 50.0%) is normalized to quota fraction `info.CpuRate / 10000.0`. `WindowsResources.java` calculates `quotaCpus = quotaFraction * availableCpus`, correcting Defect R04.
- **Working Set Underflow Guard**: `GetProcessMemoryInfo` populates `WorkingSetSize` and `PrivateUsage`. Shared memory is computed as `Math.max(0L, WorkingSetSize - PrivateUsage)`, preventing negative values when private commit charge exceeds working set size.
- **Process CPU Times 100-ns Scaling**: `GetProcessTimes` returns `FILETIME` 100-ns tick counts. `windows_resources.cpp` sums kernel and user time QuadParts and multiplies by `100L` to yield nanoseconds (`jlong`).
- **Idle Cycle Delta Normalization**: `QueryIdleProcessorCycleTime` populates per-CPU idle cycle counts. `WindowsResources.computeCpuPressure` tracks cycle deltas over interval nanoseconds $dt$, producing normalized busy ratios in $[0.0, 1.0]$.
- **Cumulative I/O Bytes**: `GetProcessIoCounters` sums `ReadTransferCount + WriteTransferCount` into cumulative process transferred bytes (`jlong`).
- **Sample Construction & Signal Validity**: `sampleFast` (200 ms) and `sampleSlow` (5 s) populate `FastHardwareSample` and `SlowHardwareSample`, tagging valid signals as `SignalValidity.VALID` and absent sensors as `SignalValidity.UNSUPPORTED` with $0.0$ neutral defaults.

### 3. Native Multi-Group Affinity, Current Processor & Lease Contract (Defect A03 Fix)

`WindowsAffinity.java`, `WindowsAffinityCalls.java`, and `windows_affinity.cpp` implement thread affinity binding.
- **Multi-Group Affinity Dispatch**: Given a mask array spanning multiple processor groups, `windows_affinity.cpp` resolves `SetThreadSelectedCpuSetMasks` dynamically from `kernel32.dll`. If unavailable or failing, it returns `-1` (deterministic rejection). `WindowsAffinity.java` converts this to `false`, eliminating partial Group 0 mask application (Defect A03). Single-group requests use `SetThreadGroupAffinity`.
- **Original Affinity Restoration**: During affinity lease acquisition, `GetThreadGroupAffinity` captures original thread group affinity into `GROUP_AFFINITY`. Upon lease release, `SetThreadGroupAffinity` restores original group affinity.
- **Global Logical Processor Query**: `windows_affinity.cpp` calls `GetCurrentProcessorNumberEx(&procNum)` dynamically, returning `group * 64 + processor`, with fallback to `GetCurrentProcessorNumber()`.
- **Timer Resolution Lifecycle**: `WindowsAffinity.setTimerResolution(long nanos)` converts nanoseconds to 100-ns units (`nanos / 100L`), invokes native `NtSetTimerResolution` resolved dynamically from `ntdll.dll` using `std::atomic<bool>` initialization, and registers JVM shutdown hook `win-timer-release` for cleanup.

### 4. Native Memory Safety & PE ABI Hardening (Defect N01 & B06 Fixes)

`windows_hardening.cpp`, `windows_affinity.cpp`, `windows_resources.cpp`, and `windows_system_layout.cpp` enforce C++ native safety and PE ABI compliance.
- **Zero VLA Compliance**: Audit of all native C++ sources confirms zero Variable Length Arrays (Defect N01). Fixed stack allocations use compile-time bounds (`GROUP_AFFINITY stackAffinities[64]`, `ULONG64 stackIdleTimes[256]`), while larger requests allocate dynamic heap buffers (`malloc`) with NULL checks and guaranteed `free` release.
- **JNI Safety & Pointer Pinning**: All JNI native methods verify array parameters against NULL and target capacity. Read-only arrays use `JNI_ABORT` release mode to avoid unnecessary copy-back.
- **PE ABI Hardening & Zero CRT Policy**: C++ sources compiled via Zig with `-fno-exceptions -fno-rtti -fvisibility=hidden`. `windows_hardening.cpp` provides stack protector ABI state (`__stack_chk_guard`, `__stack_chk_fail`) and `___chkstk_ms` / `__chkstk` stubs. Compiled DLL binaries carry zero imports from MSVCRT or C++ runtimes (`msvcrt.dll`, `vcruntime140.dll`, `msvcp140.dll`), resolving Defect B06.

## Verification Commands & Test Results

```bash
# 1. Run Windows platform provider unit & fixture tests
mise exec -- gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.windows.*"
# Output: PASSED (all topology, resource, and affinity tests passed)

# 2. Run native binary inspection & ABI compatibility tests
mise exec -- gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.compatibility.*"
# Output: PASSED (zero VLA compliance, PE import table, and ABI floor gates passed)

# 3. Force rerun of all hardware-utils module tests
mise exec -- gradle :euhedral-hardware-utils:test --rerun-tasks
# Output: BUILD SUCCESSFUL (148 tests passed cleanly)

# 4. Full multi-module repository check
mise exec -- gradle build
# Output: BUILD SUCCESSFUL (all workspace modules build and pass tests)

# 5. Git diff formatting and check
git diff --check
# Output: CLEAN (0 whitespace or formatting errors)
```

## Environmental Limits

- **Platform Test Execution**: Live Win32 JNI execution requires a Windows host OS; verification on non-Windows Linux hosts relies on binary GLPIEx buffer fixtures, PE binary import inspection, and JNI wrapper safety tests.

## Handoff and Next Steps

Phase 6 Windows platform provider audit is clean and fully verified. Authorized closeout includes:
1. Merging branch `hardware-utils-overhaul/phase-6-windows-audit` into `hardware-utils-overhaul/phase-6-windows`.
2. Appending the Phase 6 closeout summary to [`docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md).
3. Removing the temporary P6 status block from [`AGENTS.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/AGENTS.md).
4. Recording the resulting root commit.
