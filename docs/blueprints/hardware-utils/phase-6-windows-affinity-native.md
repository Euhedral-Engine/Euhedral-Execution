# Phase 6-C Windows Affinity, Multi-Group Processor Sets & Native ABI Blueprint

## 1. Status and Authority

- **Parent Plan**: [
  `docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md)
- **Parent Blueprint**: [
  `docs/blueprints/hardware-utils/phase-6-windows-platform.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-6-windows-platform.md)
- **P6 Root Branch**: `hardware-utils-overhaul/phase-6-windows`
- **Child Blueprint Branch**: `hardware-utils-overhaul/phase-6-windows-affinity-native-blueprint`
- **Child Implementation Branch**:
  `hardware-utils-overhaul/phase-6-windows-affinity-native-implementation`
- **Audit File Target**: `docs/audits/hardware-utils/phase-6-windows-affinity-native-conformance.md`
- **Owning Module**: `euhedral-hardware-utils`
- **Selected Blueprint Model**: `gpt-5.6-sol` with `max` reasoning effort
- **Status**: Implementation-ready child blueprint. Pending developer review and merge into the P6
  root before child implementation begins.

This child blueprint is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`,
`docs/ARCHITECTURE.md`, and the parent P6 blueprint (`phase-6-windows-platform.md`). It translates
parent native affinity, timer resolution, VLA elimination, and PE ABI contracts into an explicit,
implementable specification for `WindowsAffinity`, `WindowsAffinityCalls`, `windows_affinity.cpp`,
`windows_resources.cpp`, `windows_system_layout.cpp`, `windows_hardening.cpp`, and `windows_jni.h`.

## 2. Objective & Core Defects Addressed

The objective of **Phase 6-C** is to deliver a robust, thread-safe, VLA-free native Windows
affinity, current processor query, timer resolution manager, and hardened PE ABI integration
(`WindowsAffinity`, `windows_affinity.cpp`) operating reliably across Windows 10/11 x86-64 and ARM64
architectures.

### Core Defect Corrections & Technical Objectives

- **Defect A03 Correction (Deterministic Multi-Group Affinity Rejection)**:
    - Multi-group affinity requests spanning multiple processor groups use
      `SetThreadSelectedCpuSetMasks` on Windows 10 1607+ / Server 2016+.
    - If `SetThreadSelectedCpuSetMasks` is unavailable or fails, the call MUST return `false`
      (deterministic rejection).
    - It MUST NOT apply a partial mask to Group 0 while reporting success.
- **Defect N01 Correction (C++ VLA Elimination & JNI Symbol Mismatch)**:
    - Complete elimination of Variable Length Arrays (`BYTE buffer[length]`,
      `GROUP_AFFINITY affinities[len]`) across all Windows native C++ files (`windows_affinity.cpp`,
      `windows_system_layout.cpp`, `windows_resources.cpp`). Replaced by fixed stack buffers bounded
      by maximum system limits (e.g. `GROUP_AFFINITY affinities[64]`) or dynamic heap buffers
      (`std::vector<BYTE>` / `malloc`) with explicit null checks and guaranteed cleanup.
    - Fix JNI export class mismatch for `ntSetTimerResolution` in `windows_affinity.cpp`: correct
      symbol from
      `Java_io_euhedral_1execution_hardware_1utils_windows_WindowsTimerResolution_ntSetTimerResolution`
      to `Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_ntSetTimerResolution`.
- **Original Thread Group Affinity Restoration**:
    - Capture original thread group affinity before applying lease modifications using
      `GetThreadGroupAffinity(GetCurrentThread(), &previousGroupAffinity)`.
    - Restore original thread group affinity on lease release/close using
      `SetThreadGroupAffinity(GetCurrentThread(), &previousGroupAffinity, NULL)`.
- **Global Logical CPU Query (`GetCurrentProcessorNumberEx`)**:
    - `getCpu()` invokes `GetCurrentProcessorNumberEx(&procNum)` and converts processor group
      coordinates bijectively into Euhedral global logical CPU ID:
      `(int)procNum.Group * 64 + (int)procNum.Number`.
    - Fallback to `(int)GetCurrentProcessorNumber()` on legacy single-group hosts.
- **Dynamic Timer Resolution (`NtSetTimerResolution`)**:
    - Function pointer `pfnNtSetTimerResolution` dynamically resolved from `ntdll.dll` with
      thread-safe `std::atomic<bool>` / `atomic_exchange` initialization.
    - Java `WindowsAffinity.setTimerResolution(long nanos)` converts nanoseconds to 100-ns units
      (`nanos / 100L`), enforces non-negative bounds, and registers JVM shutdown hook
      (`win-timer-release`) that calls `ntSetTimerResolution(appliedResolution, false)` for
      idempotent cleanup.
- **JNI Array Safety & Pointer Pinning**:
    - Verify array nullness and capacity before native dereferencing (`GetArrayLength`, null check
      on `GetLongArrayElements`).
    - Pointer release using `ReleaseLongArrayElements(maskArray, masks, JNI_ABORT)` for read-only
      setters to prevent unnecessary Java heap copy-back.
- **PE ABI Hardening & Zero C++ Runtime Dependencies**:
    - Target floors: Windows 10 / Server 2016 (x86-64) and Windows 11 (ARM64).
    - Compile flags: `-fno-exceptions -fno-rtti -fvisibility=hidden`.
    - Forbidden runtime dependencies: `msvcrt.dll`, `vcruntime140.dll`, `msvcp140.dll`,
      `ucrtbase.dll`.
    - Allowed DLL imports: `kernel32.dll`, `psapi.dll`, `ntdll.dll`.
    - Provide stack protector ABI state and symbol stubs in `windows_hardening.cpp`
      (`__stack_chk_guard`, `__stack_chk_fail`, `___chkstk_ms`, `__chkstk`).

## 3. Scope & Non-Goals

### 3.1. In Scope

- **Primary Source Files**:
    - [
      `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/WindowsAffinity.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/WindowsAffinity.java)
    - [
      `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/WindowsAffinityCalls.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/WindowsAffinityCalls.java)
    - [
      `euhedral-hardware-utils/src/main/native/windows/windows_affinity.cpp`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/windows/windows_affinity.cpp)
    - [
      `euhedral-hardware-utils/src/main/native/windows/windows_resources.cpp`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/windows/windows_resources.cpp)
    - [
      `euhedral-hardware-utils/src/main/native/windows/windows_system_layout.cpp`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/windows/windows_system_layout.cpp)
    - [
      `euhedral-hardware-utils/src/main/native/windows/windows_hardening.cpp`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/windows/windows_hardening.cpp)
    - [
      `euhedral-hardware-utils/src/main/native/windows/windows_jni.h`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/windows/windows_jni.h)
- **Win32 & NtDll System Calls**:
    - `SetThreadSelectedCpuSetMasks`: Dynamic resolution from `kernel32.dll` for multi-group CPU
      sets.
    - `SetThreadGroupAffinity` & `GetThreadGroupAffinity`: Win32 group affinity calls.
    - `GetCurrentProcessorNumberEx`: Dynamic/linked resolution from `kernel32.dll` returning
      `PROCESSOR_NUMBER`.
    - `NtSetTimerResolution`: Dynamic resolution from `ntdll.dll`.
- **Testing & Validation**:
    - Unit tests in [
      `WindowsAffinityTest.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/windows/WindowsAffinityTest.java).
    - Native binary inspection and JNI load tests (`NativeBinaryInspectionIT`,
      `NativeCompatibilityTest`).

### 3.2. Non-Goals

- Modifying GLPIEx binary topology structure parsing in Java (owned by P6-A).
- Modifying Job Object CPU rate control or working set memory snapshot calculations (owned by P6-B).
- Modifying common P3 `AffinityController` or P1 Zig build graph logic.
- Modifying Linux or macOS native affinity implementations (reserved for P5/P7).
- Any inspection, build, or test activity under `euhedral-training`.

## 4. Architectural Contracts & Implementation Checklist

```text
WindowsAffinity.setAffinity(masks) / captureAffinity() / restoreExact(masks)
                   |
                   v
WindowsAffinityCalls.apply(masks, RawCall)  [Mask Canonicalization & Group Count Check]
                   |
                   v
       Java Native Method (JNI Boundary)
  setThreadAffinity / getCpu / ntSetTimerResolution
                   |
                   v
  windows_affinity.cpp (Native C++)
  - Null & Array Length Checks (GetArrayLength, GetLongArrayElements check)
  - VLA-Free Allocation (Fixed GROUP_AFFINITY affinities[64] stack buffer)
  - Dynamic Kernel32 Lookup: SetThreadSelectedCpuSetMasks / GetCurrentProcessorNumberEx
  - Dynamic NtDll Lookup: NtSetTimerResolution (std::atomic<bool> Thread-Safe Init)
  - Multi-Group Rejection: Return error code if multi-group fails; no partial Group 0 fallback
  - ReleaseLongArrayElements(..., JNI_ABORT)
                   |
                   v
  Return jint Success (0) or Error Code to Java
```

### 4.1. Checklist Item 1: Multi-Group Affinity Application & Deterministic Rejection (Defect A03 Correction)

- [ ] **Dynamic `SetThreadSelectedCpuSetMasks` Symbol Resolution**:
    - Dynamically resolve `SetThreadSelectedCpuSetMasks` from `kernel32.dll`:
      ```cpp
      typedef BOOL(WINAPI *pSetThreadSelectedCpuSetMasks)(HANDLE, PGROUP_AFFINITY, USHORT);
      ```
- [ ] **Multi-Group Mask Population & VLA Elimination**:
    - Inspect input `long[] masks` array in
      `Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_setThreadAffinity`.
    - Validate array length `len = env->GetArrayLength(maskArray)`. If `len <= 0`, return `-1`.
    - Check `masks != NULL`. If `NULL`, return `-1`.
    - Use fixed stack buffer: `GROUP_AFFINITY affinities[64];` (since Windows supports up to 64
      processor groups).
    - If `len > 64`, clamp or dynamically allocate `std::vector<GROUP_AFFINITY>` with explicit
      allocation safety.
    - Count non-zero mask entries and populate `affinities[active_count++]` with
      `ga.Mask = (KAFFINITY)masks[i]` and `ga.Group = (WORD)i`.
- [ ] **Deterministic Multi-Group Rejection Semantics**:
    - If `active_count > 1` (multi-group request):
        - If `pSetThreadSelectedCpuSetMasks` function pointer is non-null:
            - Call
              `pSetThreadSelectedCpuSetMasks(GetCurrentThread(), affinities, (USHORT)active_count)`.
            - If call succeeds, return `0`.
            - If call fails, return `(jint)GetLastError()`.
        - If `pSetThreadSelectedCpuSetMasks` function pointer is NULL (older OS baseline):
            - Return `-1` or non-zero error code indicating multi-group affinity is unsupported.
        - MUST NOT fall through to `SetThreadGroupAffinity` on Group 0 when `active_count > 1`.
          Partial group application is strictly forbidden (fixing defect A03).
- [ ] **Single-Group Handling**:
    - If `active_count == 1`:
        - Call `SetThreadGroupAffinity(GetCurrentThread(), &affinities[0], NULL)`.
        - Return `0` on success, or `(jint)GetLastError()` on failure.
    - If `active_count == 0`:
        - Return `-1` (invalid empty mask request).

### 4.2. Checklist Item 2: Original Thread Group Affinity Lease Capture & Restoration

- [ ] **Capture Original Affinity**:
    - Before modifying a thread's affinity mask, capture its original group binding:
      ```cpp
      GROUP_AFFINITY previousAffinity = {0};
      if (GetThreadGroupAffinity(GetCurrentThread(), &previousAffinity)) {
          // previousAffinity holds previous Group and Mask
      }
      ```
- [ ] **Lease Release Restoration**:
    - Expose native or Java restoration logic integrated with P3's `AffinityController`.
    - On lease release/close, invoke
      `SetThreadGroupAffinity(GetCurrentThread(), &previousAffinity, NULL)` to restore the exact
      original group mask and group ID.

### 4.3. Checklist Item 3: Global Logical CPU ID Query via `GetCurrentProcessorNumberEx`

- [ ] **Dynamic `GetCurrentProcessorNumberEx` Resolution**:
    - Dynamically resolve `GetCurrentProcessorNumberEx` from `kernel32.dll`:
      ```cpp
      typedef VOID(WINAPI *pGetCurrentProcessorNumberEx)(PPROCESSOR_NUMBER);
      ```
- [ ] **Bijective Logical ID Mapping**:
    - In `Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_getCpu`:
      ```cpp
      PROCESSOR_NUMBER procNum = {0};
      if (pGetCurrentProcessorNumberEx) {
          pGetCurrentProcessorNumberEx(&procNum);
          return (jint)((int)procNum.Group * 64 + (int)procNum.Number);
      }
      return (jint)GetCurrentProcessorNumber(); // Fallback for single-group hosts
      ```

### 4.4. Checklist Item 4: Dynamic Timer Resolution (`NtSetTimerResolution`) & Shutdown Hook

- [ ] **JNI Native Function Name Correction (Defect N01 Fix)**:
    - Correct export signature in `windows_affinity.cpp`:
      ```cpp
      JNIEXPORT jint JNICALL
      Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_ntSetTimerResolution(
          JNIEnv *env, jclass clazz, jint resolution, jboolean set)
      ```
- [ ] **Thread-Safe Native Initialization**:
    - Protect `ntdll.dll` handle and `pfnNtSetTimerResolution` function pointer resolution using
      `std::atomic<bool>` or `atomic_exchange` once-only execution.
    - Call `NtSetTimerResolution((ULONG)resolution, (BOOLEAN)set, &currentResolution)`.
    - Return `(jint)currentResolution` on success (`status >= 0`), or `(jint)status` on error.
- [ ] **Java `WindowsAffinity.setTimerResolution(long nanos)` Lifecycle**:
    - Enforce atomic once-only setting via `WIN_RES_SET.compareAndSet(false, true)`.
    - Validate bounds: if `nanos < 0`, throw `IllegalArgumentException` ("Cannot set negative
      resolution: ...").
    - Clamp minimum: `nanos = Math.max(nanos, 1L)`.
    - Convert nanoseconds to 100-ns units:
      `int res = (int)(Math.min(Integer.MAX_VALUE, nanos) / 100L)`.
    - Invoke native `ntSetTimerResolution(res, true)`.
    - Store applied resolution into `windowsResolution100ns`.
    - Register JVM shutdown hook `win-timer-release`:
      ```java
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          try {
              ntSetTimerResolution(this.windowsResolution100ns.getAcquire(), false);
          } catch (Exception ignored) {}
      }, "win-timer-release"));
      ```

### 4.5. Checklist Item 5: Complete Elimination of VLAs in C++ Native Code (Defect N01 Correction)

- [ ] **`windows_affinity.cpp` Audit & Remediation**:
    - Replace `GROUP_AFFINITY affinities[len];` with fixed stack buffer
      `GROUP_AFFINITY stackAffinities[64];`.
    - If `len > 64`, allocate dynamic vector or heap buffer (`std::vector<GROUP_AFFINITY>` /
      `malloc`) with explicit NULL check and guaranteed cleanup before returning.
- [ ] **`windows_system_layout.cpp` Audit & Remediation**:
    - Replace `BYTE buffer[length];` VLA in
      `Java_io_euhedral_1execution_hardware_1utils_windows_WindowsSystemLayout_getRawTopologyInfo`:
      ```cpp
      std::vector<BYTE> buffer(length);
      if (!GetLogicalProcessorInformationEx(RelationAll, (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)buffer.data(), &length)) {
          return nullptr;
      }
      jbyteArray result = env->NewByteArray(length);
      if (result) {
          env->SetByteArrayRegion(result, 0, length, (const jbyte *)buffer.data());
      }
      return result;
      ```
- [ ] **`windows_resources.cpp` Audit & Remediation**:
    - Verify `ULONG64 stackIdleTimes[256]` is a fixed compile-time stack buffer.
    - Verify heap fallback (`malloc`) checks for NULL return and calls `free()` on all exit paths.

### 4.6. Checklist Item 6: JNI Buffer Validation, Array Pinning & Memory Safety

- [ ] **JNI Input Checking**:
    - All native functions taking array arguments (`jlongArray`, `jdoubleArray`) MUST verify
      non-null and minimum array length before accessing elements.
- [ ] **Pointer Pinning & Unpinning**:
    - Verify return of `GetLongArrayElements` / `GetDoubleArrayElements` against `NULL`.
    - For read-only array access (e.g. `setThreadAffinity`), use
      `ReleaseLongArrayElements(maskArray, masks, JNI_ABORT)` to skip copy-back.
    - For write-back array access (e.g. `getCpuTimes`, `getMemorySnapshot`), use
      `ReleaseLongArrayElements(..., 0)` to commit changes.

### 4.7. Checklist Item 7: PE ABI, Import Floor, Hardening & Zero C++ Runtime Policy

- [ ] **Target OS & Architecture Floors**:
    - Windows x86-64: Windows 10 / Server 2016 baseline floor.
    - Windows ARM64: Windows 11 baseline floor.
- [ ] **Compiler & CRT Policy**:
    - Compile via Zig using `-fno-exceptions -fno-rtti -fvisibility=hidden`.
    - Forbidden DLL imports: `msvcrt.dll`, `vcruntime140.dll`, `msvcp140.dll`, `ucrtbase.dll`.
    - Allowed DLL imports: `kernel32.dll`, `psapi.dll`, `ntdll.dll`.
- [ ] **Stack Protector & ABI Hardening**:
    - Provide stack protector ABI state and symbol stubs in `windows_hardening.cpp`
      (`__stack_chk_guard`, `__stack_chk_fail`, `___chkstk_ms`, `__chkstk`).

## 5. Sizing & Split Gate Assessment

### Sizing Evaluation

1. **Context Load**: The implementation is strictly bounded to `WindowsAffinity.java`,
   `WindowsAffinityCalls.java`, `windows_affinity.cpp`, `windows_resources.cpp`,
   `windows_system_layout.cpp`, `windows_hardening.cpp`, `windows_jni.h`, and
   `WindowsAffinityTest.java`. The context covers Win32 dynamic symbol resolution, multi-group
   affinity math, timer resolution shutdown hooks, VLA elimination, and PE import hardening. This
   comfortably fits within the working memory of a single implementation pass.
2. **Single Responsibility**: `WindowsAffinity` and native Windows JNI files own thread affinity,
   CPU queries, timer resolution, VLA safety, and PE ABI hardening. Topology parsing (P6-A) and Job
   Object resource collection (P6-B) are cleanly separated.
3. **Independent Validation**: Native affinity and ABI features can be fully validated via JNI unit
   tests, PE binary inspection tools, and Windows test fixtures.

**Conclusion**: Child P6-C is irreducible, correctly sized, and ready for implementation in a single
pass.

## 6. Implementation Model Reassessment

- **Required Capabilities**: Intricate Win32 dynamic API resolution, multi-group processor group
  mask math, JNI array safety and memory release modes, complete C++ VLA elimination, PE import
  hardening gate validation, and timer resolution shutdown hook lifecycle management.
- **Selected Model**: **`gpt-5.6-sol` with `high` reasoning effort**.
- **Justification**: Preserving thread safety across Win32 APIs, eliminating all C++ VLAs safely,
  enforcing zero CRT dependencies, and guaranteeing exact multi-group affinity rejection requires
  high reasoning effort.

## 7. Developer-Review Summary

| Item                   | Details                                                                                                                                                                                                                                                                                                                                                                                                                                          |
|------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Purpose**            | Deliver multi-group Windows thread affinity with deterministic rejection, global CPU ID queries (`GetCurrentProcessorNumberEx`), dynamic timer resolution (`NtSetTimerResolution`) with shutdown hook cleanup, complete VLA elimination, JNI array safety, and PE ABI hardening (`WindowsAffinity`, `windows_affinity.cpp`) for Windows 10/11 platforms.                                                                                         |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.windows.WindowsAffinity`, `WindowsAffinityCalls` (Java), `src/main/native/windows/*` (C++).                                                                                                                                                                                                                                                                                                                |
| **Key Invariants**     | Multi-group affinity uses `SetThreadSelectedCpuSetMasks` or rejects deterministically (`false`) without partial Group 0 application; original group affinity restored on lease release; `getCpu()` returns `group * 64 + processor`; timer resolution hook handles `win-timer-release` cleanup; zero VLAs in C++ native code; JNI array null/length checks enforced; PE ABI hardened with `-fno-exceptions -fno-rtti` and zero CRT dependencies. |
| **Child Action Items** | P6-C implementation: `hardware-utils-overhaul/phase-6-windows-affinity-native-implementation`.                                                                                                                                                                                                                                                                                                                                                   |
| **Selected Model**     | `gpt-5.6-sol` with `high` reasoning effort for implementation and conformance audit.                                                                                                                                                                                                                                                                                                                                                             |
| **Principal Risks**    | Partial mask application on multi-group systems; VLA stack frame corruption; JNI class/method descriptor mismatches; illegal CRT imports in compiled DLLs.                                                                                                                                                                                                                                                                                       |
| **Unresolved Items**   | None. Win32 function signatures, JNI method names, VLA replacements, timer hooks, and PE import rules are fully settled.                                                                                                                                                                                                                                                                                                                         |

## 8. Verification & Acceptance Criteria

### 8.1. Acceptance Criteria

1. **Deterministic Multi-Group Rejection (Defect A03 Fix)**:
    - Multi-group affinity requests spanning multiple processor groups invoke
      `SetThreadSelectedCpuSetMasks`.
    - If `SetThreadSelectedCpuSetMasks` fails or is unavailable, the call returns `false`
      deterministically. No partial mask is applied to Group 0.
2. **Original Thread Group Affinity Restoration**:
    - Acquiring and releasing an affinity lease captures original thread group affinity via
      `GetThreadGroupAffinity` and restores it via `SetThreadGroupAffinity`.
3. **Global Logical CPU Query**:
    - `getCpu()` invokes `GetCurrentProcessorNumberEx` and returns `group * 64 + processor`.
4. **Timer Resolution & Shutdown Hook Cleanup**:
    - `setTimerResolution(nanos)` rejects negative values, converts nanoseconds to 100-ns units,
      invokes native `ntSetTimerResolution`, and registers JVM shutdown hook `win-timer-release`.
    - Native JNI function name is correctly matched to `WindowsAffinity`.
5. **Zero VLA Compliance (Defect N01 Fix)**:
    - All native C++ code across `windows_affinity.cpp`, `windows_system_layout.cpp`, and
      `windows_resources.cpp` contains zero Variable Length Arrays. Stack allocations use fixed
      bounds (`[64]` or `[256]`) or dynamic vector allocation.
6. **JNI Array Safety**:
    - Native functions check array nullness and length before dereferencing, releasing read-only
      arrays with `JNI_ABORT`.
7. **PE ABI Hardening**:
    - Compiled DLL binaries carry zero imports from CRT or C++ runtime libraries (`msvcrt.dll`,
      `vcruntime140.dll`, `msvcp140.dll`).

### 8.2. Verification Commands

```bash
# Build hardware-utils module and run Windows affinity tests
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.windows.WindowsAffinityTest"

# Run native binary compatibility and inspection tests
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.compatibility.*"

# Run all hardware-utils tests
gradle :euhedral-hardware-utils:test
```

## 9. Completion Record

- **Date**: 2026-08-07
- **Branch**: `hardware-utils-overhaul/phase-6-windows-affinity-native-implementation`
- **Implementation Highlights**:
    - Corrected JNI symbol export in `windows_affinity.cpp` to match `WindowsAffinity`
      (`Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_ntSetTimerResolution`).
    - Added `getThreadAffinity` native JNI method and Java facade methods (`captureAffinity()`,
      `restoreExact(long[] mask)`, `applyExact(long[] mask)`) in `WindowsAffinity.java` and
      `windows_affinity.cpp`.
    - Implemented `SetThreadSelectedCpuSetMasks` multi-group affinity handling in
      `windows_affinity.cpp` with deterministic rejection (`-1`) when unsupported or failed,
      preventing partial Group 0 mask application (fixing Defect A03).
    - Implemented dynamic global CPU ID lookup via `GetCurrentProcessorNumberEx(&procNum)` in
      `windows_affinity.cpp` with fallback to `GetCurrentProcessorNumber()`.
    - Implemented `NtSetTimerResolution` dynamic resolution with thread-safe once-only init and
      registered shutdown hook `win-timer-release` in `WindowsAffinity.java`.
    - Enforced zero C++ VLAs and zero CRT dependencies across `windows_affinity.cpp`,
      `windows_system_layout.cpp`, and `windows_resources.cpp` using fixed stack arrays and C
      `malloc`/`free`.
    - Enforced JNI null/length checks and `JNI_ABORT` unpinning for read-only array parameters.
    - Updated `WindowsAffinityTest.java` and `NativeBinaryInspectionIT.java`.
- **Verification Results**:
    - `gradle :euhedral-hardware-utils:test` (148 tests passed cleanly, including affinity matrix
      tests, multi-group rejection tests, lease restoration tests, VLA compliance checks, PE binary
      import gates, and JNI load smoke tests).
    - `gradle build` across all workspace modules completed with zero failures.
