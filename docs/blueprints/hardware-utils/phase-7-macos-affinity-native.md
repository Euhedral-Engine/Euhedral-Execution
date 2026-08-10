# Phase 7-C macOS Locality Affinity, Timer & Native ABI Blueprint

## 1. Status and Authority

- **Parent Plan**: [
  `docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md)
- **Parent Blueprint**: [
  `docs/blueprints/hardware-utils/phase-7-macos-platform.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-7-macos-platform.md)
- **P7 Root Branch**: `hardware-utils-overhaul/phase-7-macos`
- **Child Blueprint Branch**: `hardware-utils-overhaul/phase-7-macos-affinity-native-blueprint`
- **Child Implementation Branch**:
  `hardware-utils-overhaul/phase-7-macos-affinity-native-implementation`
- **Audit File Target**: `docs/audits/hardware-utils/phase-7-macos-affinity-native-conformance.md`
- **Owning Module**: `euhedral-hardware-utils`
- **Selected Blueprint Model**: `gpt-5.6-sol` with `high` reasoning effort
- **Status**: Implementation-ready child blueprint. Pending developer review and merge into the P7
  root before child implementation begins.

This child blueprint is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`,
`docs/ARCHITECTURE.md`, and the parent P7 blueprint (`phase-7-macos-platform.md`). It translates
parent native locality affinity, safe timer policy, physical CPU query semantics, and Mach-O
universal binary ABI contracts into an explicit, implementable specification for `MacosAffinity`,
`MacosAffinityCalls`, `macos_affinity.cpp`, `macos_resources.cpp`, `macos_system_layout.cpp`, and
`macos_jni.h`.

## 2. Objective & Core Defects Addressed

The objective of **Phase 7-C** is to deliver a robust, thread-safe macOS thread locality affinity
provider, physical current CPU query, safe idempotent timer policy, and hardened Mach-O universal
binary integration (`MacosAffinity`, `macos_affinity.cpp`) operating reliably across Apple Silicon
(arm64) and Intel (x86_64) Macs targeting macOS 11+.

### Core Defect Corrections & Technical Objectives

- **Defect A04 Correction (Mach Thread Affinity Tag Mapping & Tag 0 Release Preference)**:
    - macOS kernel supports thread affinity hints via
      `thread_policy_set(pthread_mach_thread_np(pthread_self()), THREAD_AFFINITY_POLICY, (thread_policy_t)&policy, THREAD_AFFINITY_POLICY_COUNT)`.
    - Tag `0` is the reserved release tag that clears locality preference.
    - Non-zero integer tags ($1, 2, 3, \dots$) advise the macOS scheduler to colocate threads
      sharing the same tag.
    - Ordinal-to-tag mapping: requested logical CPU ordinal `c` maps bijectively to Mach affinity
      tag `c + 1`.
- **Single-Locality Mask Enforcement & Deterministic Rejection**:
    - `MacosAffinityCalls.applyOrdinal(long[] masks)` checks requested mask array.
    - If `masks` contains EXACTLY ONE set bit (cardinality 1, ordinal `c`), it maps `c` to Mach
      affinity tag `c + 1` and calls native `setThreadAffinity(tag)`. Returns `true`.
    - If `masks` contains ZERO set bits or MORE THAN ONE set bit (arbitrary multi-locality request),
      it returns `false` (deterministic rejection). Partial placement or unproven multi-locality
      mapping is strictly forbidden.
- **Physical Current CPU Query (`getCpu()` Returning -1)**:
    - The macOS kernel does not expose a public API to query the physical CPU ID currently executing
      the thread.
    - `MacosAffinity.getCpu()` returns `-1` (`UNSUPPORTED`) on all macOS platforms (x86_64 and
      arm64), replacing legacy unportable CPUID initial APIC ID queries.
- **Elimination of `THREAD_TIME_CONSTRAINT_POLICY` Realtime Scheduling Trap**:
    - Legacy native implementations invoked `THREAD_TIME_CONSTRAINT_POLICY` with hardcoded
      computation ratios (e.g. 90% of period), which converted calling threads into macOS realtime
      threads.
    - This blueprint strictly forbids `THREAD_TIME_CONSTRAINT_POLICY` or realtime thread policy
      creation.
- **Safe Idempotent Timer Policy**:
    - `setTimerResolution(long nanos)` validates `nanos >= 0` (throws `IllegalArgumentException` on
      negative values).
    - Clamps minimum resolution `nanos = Math.max(1L, nanos)`.
    - Completes safely and idempotently without altering thread scheduling constraints.
- **Modernized Package**:
    - Primary Java source: `io.euhedral_execution.hardware_utils.macos.MacosAffinity` and
      `MacosAffinityCalls` replacing legacy `OSX` classes.
- **Mach-O Universal Binary, Deployment Floor & Hardening**:
    - Baseline deployment floor: macOS 11.0 (Big Sur) for both `x86_64` and `arm64`.
    - Universal Mach-O fat binary packaging (`x86_64` + `arm64`) compiled with
      `-fno-exceptions -fno-rtti -fvisibility=hidden`.
    - Allowed dynamic libraries: `Foundation.framework`, `CoreFoundation.framework`,
      `IOKit.framework`, `/usr/lib/libSystem.B.dylib`. Forbidden: libstdc++, libc++ dynamic links
      outside `libSystem`.
    - CI build and native loader execute `codesign -v` verification on native `.dylib` binaries.

## 3. Scope & Non-Goals

### 3.1. In Scope

- **Primary Source Files**:
    - [
      `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosAffinity.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosAffinity.java)
    - [
      `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosAffinityCalls.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosAffinityCalls.java)
    - [
      `euhedral-hardware-utils/src/main/native/macos/macos_affinity.cpp`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/macos/macos_affinity.cpp)
    - [
      `euhedral-hardware-utils/src/main/native/macos/macos_resources.cpp`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/macos/macos_resources.cpp)
    - [
      `euhedral-hardware-utils/src/main/native/macos/macos_system_layout.cpp`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/macos/macos_system_layout.cpp)
    - [
      `euhedral-hardware-utils/src/main/native/macos/macos_jni.h`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/macos/macos_jni.h)
- **Mach & POSIX System Calls**:
    - `thread_policy_set(pthread_mach_thread_np(pthread_self()), THREAD_AFFINITY_POLICY, ...)` for
      thread affinity locality tags.
    - `pthread_mach_thread_np(pthread_self())` for Mach port lookup.
    - `mach_timebase_info` for nanosecond scaling.
- **Testing & Validation**:
    - Unit tests in `MacosAffinityTest.java`.
    - Native binary inspection and JNI load tests (`NativeBinaryInspectionIT`,
      `NativeCompatibilityTest`).

### 3.2. Non-Goals

- Modifying macOS sysctl topology parsing, Intel SMT, or Apple Silicon P/E-core classification
  (owned by P7-A).
- Modifying `proc_pid_rusage`, `task_info`, `NSProcessInfo` thermal/low-power signals, or telemetry
  pressure rules (owned by P7-B).
- Modifying common P3 `AffinityController` or P1 Zig build graph logic.
- Modifying Linux or Windows native affinity implementations (owned by P5/P6).
- Any inspection, build, or test activity under `euhedral-training`.

## 4. Architectural Contracts & Implementation Checklist

```text
MacosAffinity.setAffinity(masks) / captureAffinity() / release()
                   |
                   v
MacosAffinityCalls.applyOrdinal(masks, RawCall)  [Mask Canonicalization & Cardinality Check]
                   |
                   v
       Java Native Method (JNI Boundary)
  setThreadAffinityNative / getCpu / setThreadTickPolicyNative
                   |
                   v
   macos_affinity.cpp (Native C++)
   - JNI Pointer Pinning & Safety (GetArrayLength, GetLongArrayElements check)
   - Mach Thread Affinity Tag Mapping (thread_policy_set THREAD_AFFINITY_POLICY)
   - Tag 0 Release Preference Handling
   - Return -1 for getCpu()
   - Safe Idempotent Timer Policy (No THREAD_TIME_CONSTRAINT_POLICY)
   - ReleaseLongArrayElements(..., JNI_ABORT)
                   |
                   v
   Return jint Success (0) or Mach kern_return_t Error to Java
```

### 4.1. Checklist Item 1: Mach Thread Affinity Tag Mapping & Tag 0 Release Preference

- [ ] **Mach Thread Policy Set Wrapper**:
    - Native JNI implementation in `macos_affinity.cpp`:
      ```cpp
      JNIEXPORT jint JNICALL
      Java_io_euhedral_1execution_hardware_1utils_macos_MacosAffinity_setThreadAffinityNative(
          JNIEnv *env, jclass clazz, jlongArray maskArray) {
        if (!maskArray) return -1;
        jsize len = env->GetArrayLength(maskArray);
        if (len == 0) return -1;
  
        jlong *masks = env->GetLongArrayElements(maskArray, NULL);
        if (!masks) return -1;
  
        int affinityTag = 0;
        bool found = false;
  
        for (int i = 0; i < len; i++) {
          if (masks[i] != 0 && masks[i] != -1L) {
            for (int bit = 0; bit < 64; bit++) {
              if ((masks[i] >> bit) & 1ULL) {
                affinityTag = (i * 64) + bit + 1; // Ordinal c maps to Tag c + 1
                found = true;
                break;
              }
            }
          }
          if (found) break;
        }
  
        env->ReleaseLongArrayElements(maskArray, masks, JNI_ABORT);
  
        thread_affinity_policy_data_t policy = {affinityTag};
        kern_return_t kr = thread_policy_set(
            pthread_mach_thread_np(pthread_self()),
            THREAD_AFFINITY_POLICY,
            (thread_policy_t)&policy,
            THREAD_AFFINITY_POLICY_COUNT);
  
        return (kr == KERN_SUCCESS) ? 0 : (jint)kr;
      }
      ```
- [ ] **Tag 0 Release Preference**:
    - Passing mask `{0L}` resolves `affinityTag = 0`, which sends tag `0` to Mach
      `thread_policy_set`, clearing locality preference.

### 4.2. Checklist Item 2: Single-Locality Mask Enforcement & Deterministic Rejection

- [ ] **Cardinality Verification in `MacosAffinityCalls`**:
    - `MacosAffinityCalls.applyOrdinal(long[] masks, RawCall call)`:
        - Canonicalize input mask array against supported system CPU count and cpu set using
          `AffinityMasks.canonical`.
        - Convert canonical mask array to `BitSet`.
        - Check `bits.cardinality()`.
        - If `bits.cardinality() != 1`, return `false` (deterministic rejection for 0 set bits or >1
          set bits).
        - If `bits.cardinality() == 1`, extract single set bit `ordinal = bits.nextSetBit(0)`.
        - Construct encoded tag mask `new long[]{ordinal + 1L}` and invoke raw native call.
- [ ] **No Partial Placement**:
    - Rejecting multi-locality requests ensures threads are never assigned partial or incorrect
      locality hints.

### 4.3. Checklist Item 3: Physical Current CPU Query Returning `-1` (`UNSUPPORTED`)

- [ ] **Native `getCpu()` Implementation**:
    - Implement JNI function signature in `macos_affinity.cpp`:
      ```cpp
      JNIEXPORT jint JNICALL
      Java_io_euhedral_1execution_hardware_1utils_macos_MacosAffinity_getCpu(
          JNIEnv *env, jobject object) {
        return -1; // UNSUPPORTED on macOS outside managed logical ownership
      }
      ```
    - Eliminates unportable CPUID initial APIC ID queries on x86_64.
    - Returns `-1` consistently on both x86_64 and arm64 architectures.

### 4.4. Checklist Item 4: Safe Idempotent Timer Policy without
`THREAD_TIME_CONSTRAINT_POLICY` Realtime Scheduling

- [ ] **Eliminate `THREAD_TIME_CONSTRAINT_POLICY` Realtime Traps**:
    - Remove all native invocations of `THREAD_TIME_CONSTRAINT_POLICY` and `thread_policy_set`
      realtime constraint structures.
- [ ] **Native Safe Timer Policy**:
    - Implement `setThreadTickPolicyNative` in `macos_affinity.cpp`:
      ```cpp
      JNIEXPORT jboolean JNICALL
      Java_io_euhedral_1execution_hardware_1utils_macos_MacosAffinity_setThreadTickPolicyNative(
          JNIEnv *env, jclass clazz, jlong nanos) {
        if (nanos < 0) return JNI_FALSE;
        return JNI_TRUE; // Idempotent safe policy completion on macOS
      }
      ```
- [ ] **Java `MacosAffinity.setTimerResolution(long nanos)` Lifecycle**:
    - Check bounds:
      `if (nanos < 0) throw new IllegalArgumentException("Cannot set negative resolution: " + nanos);`.
    - Clamp minimum: `nanos = Math.max(1L, nanos);`.
    - Complete safely and idempotently, returning `true`.

### 4.5. Checklist Item 5: JNI Buffer Validation, Array Pinning & Memory Safety

- [ ] **JNI Input Validation**:
    - Check array nullness (`maskArray == NULL`) and length (`GetArrayLength == 0`) before
      dereferencing.
- [ ] **Pointer Release Modes**:
    - Release native array pointers using `ReleaseLongArrayElements(maskArray, masks, JNI_ABORT)`
      for read-only parameters, skipping unnecessary Java heap copy-back.

### 4.6. Checklist Item 6: Mach-O Universal Binary Compilation, Deployment Floor (11.0) & Zero C++ Runtime Policy

- [ ] **Target OS & Architecture Floors**:
    - macOS baseline deployment floor: macOS 11.0 (Big Sur) for both `x86_64` and `arm64`.
- [ ] **Universal Fat Binary Packaging**:
    - Compiled via Zig using `-target x86_64-macos.11.0` and `-target aarch64-macos.11.0`.
    - C++ compile flags: `-fno-exceptions -fno-rtti -fvisibility=hidden`.
- [ ] **Library & Framework Import Policy**:
    - Allowed dynamic libraries/frameworks: `Foundation.framework`, `CoreFoundation.framework`,
      `IOKit.framework`, `/usr/lib/libSystem.B.dylib`.
    - Forbidden dynamic libraries: `libstdc++`, `libc++` dynamic links outside `libSystem.B.dylib`.

### 4.7. Checklist Item 7: Codesign Verification & Native Loader Security

- [ ] **Codesign Verification**:
    - Execute `codesign -v` verification on native `.dylib` binaries during build and JNI loading
      (`NativeLoader`).

## 5. Sizing & Split Gate Assessment

### Sizing Evaluation

1. **Context Load**: The implementation is strictly bounded to `MacosAffinity.java`,
   `MacosAffinityCalls.java`, `macos_affinity.cpp`, `macos_resources.cpp`,
   `macos_system_layout.cpp`, `macos_jni.h`, and `MacosAffinityTest.java`. The context covers Mach
   thread affinity tag mapping, single-locality cardinality checking, returning `-1` for physical
   CPU queries, safe idempotent timer resolution, universal Mach-O binary rules, and codesign
   checks. This comfortably fits within the working memory of a single implementation pass.
2. **Single Responsibility**: `MacosAffinity` owns macOS thread affinity hints, CPU queries, timer
   policy, and native Mach-O ABI integration. Topology parsing (P7-A) and resource metrics (P7-B)
   are cleanly decoupled.
3. **Independent Validation**: Native affinity and ABI features can be fully validated via JNI unit
   tests, Mach-O binary inspection tools, and fixture tests.

**Conclusion**: Child P7-C is irreducible, correctly sized, and ready for implementation in a single
pass.

## 6. Implementation Model Reassessment

- **Required Capabilities**: Intricate Mach thread policy set integration, single-locality mask
  enforcement, safe timer resolution policy eliminating realtime thread traps, JNI array safety and
  memory release modes, universal Mach-O binary compilation, and codesign verification.
- **Selected Model**: **`gpt-5.6-sol` with `high` reasoning effort**.
- **Justification**: Preserving thread safety across Mach kernel APIs, enforcing single-locality
  mask rules deterministically, eliminating realtime thread traps safely, and guaranteeing exact
  Mach-O universal binary gates requires high reasoning effort.

## 7. Developer-Review Summary

| Item                   | Details                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
|------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Purpose**            | Deliver Mach thread affinity tag mapping (`thread_policy_set`), tag `0` release preference, single-locality mask enforcement with deterministic rejection (`false`), physical current CPU query returning `-1`, safe idempotent timer policy without `THREAD_TIME_CONSTRAINT_POLICY` realtime scheduling, macOS 11 deployment floor, universal Mach-O binary compilation (`x86_64` + `arm64`) with zero C++ runtimes (`-fno-exceptions -fno-rtti`), and bundled `codesign -v` verification. |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.macos.MacosAffinity`, `MacosAffinityCalls` (Java), `src/main/native/macos/*` (C++).                                                                                                                                                                                                                                                                                                                                                                   |
| **Key Invariants**     | Mach thread affinity maps logical CPU ordinal `c` to tag `c + 1`; tag `0` releases locality preference; single-locality mask enforced (cardinality != 1 returns `false`); physical CPU query returns `-1`; safe timer policy avoids realtime thread scheduling traps; JNI arrays checked for nullness and unpinned with `JNI_ABORT`; universal Mach-O binary targeting macOS 11+ with `-fno-exceptions -fno-rtti` and codesign verification.                                                |
| **Child Action Items** | P7-C implementation: `hardware-utils-overhaul/phase-7-macos-affinity-native-implementation`.                                                                                                                                                                                                                                                                                                                                                                                                |
| **Selected Model**     | `gpt-5.6-sol` with `high` reasoning effort for implementation and conformance audit.                                                                                                                                                                                                                                                                                                                                                                                                        |
| **Principal Risks**    | Multi-locality mask misinterpretation; `THREAD_TIME_CONSTRAINT_POLICY` realtime scheduling traps; JNI array pointer leaks; Mach-O universal binary slice mismatches.                                                                                                                                                                                                                                                                                                                        |
| **Unresolved Items**   | None. Mach thread policy calls, single-locality mask rules, timer policy, physical CPU query semantics, and Mach-O build gates are fully settled.                                                                                                                                                                                                                                                                                                                                           |

## 8. Verification & Acceptance Criteria

### 8.1. Acceptance Criteria

1. **Mach Thread Affinity Tag Mapping & Tag 0 Release**:
    - Single-locality mask request for logical CPU `c` maps to Mach thread affinity tag `c + 1` via
      `thread_policy_set`.
    - Releasing affinity or passing tag `0` sends tag `0` to Mach `thread_policy_set`, clearing
      locality preference.
2. **Single-Locality Mask Enforcement & Deterministic Rejection**:
    - `MacosAffinity.setAffinity(masks)` returns `true` ONLY for single-locality requests
      (cardinality 1).
    - Multi-locality requests (>1 set bits) or empty requests (0 set bits) return `false`
      deterministically without partial placement.
3. **Physical Current CPU Query**:
    - `getCpu()` returns `-1` (`UNSUPPORTED`) on all macOS platforms (x86_64 and arm64).
4. **Safe Idempotent Timer Policy**:
    - `setTimerResolution(nanos)` rejects negative values with `IllegalArgumentException`, clamps
      minimum resolution to `1L`, avoids `THREAD_TIME_CONSTRAINT_POLICY` realtime scheduling traps,
      and returns `true`.
5. **JNI Array Safety**:
    - Native functions check array nullness and length before dereferencing, releasing read-only
      arrays with `JNI_ABORT`.
6. **Universal Mach-O Binary & ABI Gates**:
    - Compiled `.dylib` binaries are Mach-O universal fat binaries targeting macOS 11.0 baseline
      floor for `x86_64` and `arm64`.
    - Binaries carry zero C++ runtime dependencies (`-fno-exceptions -fno-rtti`) and pass
      `codesign -v` verification.

### 8.2. Verification Commands

```bash
# Build hardware-utils module and run macOS affinity tests
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.macos.MacosAffinityTest"

# Run native binary compatibility and inspection tests
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.compatibility.*"

# Run all hardware-utils tests
gradle :euhedral-hardware-utils:test
```

## 9. Completion Record

- **Date**: 2026-08-07
- **Branch**: `hardware-utils-overhaul/phase-7-macos-affinity-native-implementation`
- **Implementation Highlights**:
    - Implemented `MacosAffinity` and `MacosAffinityCalls` in
      `io.euhedral_execution.hardware_utils.macos`.
    - Enforced single-locality mask cardinality check (`cardinality == 1`) in
      `MacosAffinityCalls.applyOrdinal`, deterministically rejecting multi-locality requests (`> 1`
      set bits) and empty requests (`0` set bits).
    - Mapped requested logical CPU ordinal `c` bijectively to Mach thread affinity tag `c + 1` via
      `thread_policy_set(pthread_mach_thread_np(pthread_self()), THREAD_AFFINITY_POLICY, ...)`.
    - Implemented Tag `0` release preference in `MacosAffinityCalls.raw` passing tag `0` to clear
      scheduler locality hints.
    - Implemented `getCpu()` returning `-1` (`UNSUPPORTED`) on all macOS architectures, replacing
      legacy APIC CPUID queries.
    - Implemented safe timer resolution policy (`setTimerResolution(long nanos)`), validating
      `nanos >= 0` (throwing `IllegalArgumentException`), clamping minimum `nanos` to `1L`, and
      eliminating `THREAD_TIME_CONSTRAINT_POLICY` realtime thread traps.
    - Modernized native files (`macos_affinity.cpp`, `macos_resources.cpp`,
      `macos_system_layout.cpp`, `macos_jni.h`).
    - Replaced legacy `OSX` classes with modernized `Macos` implementations under package
      `io.euhedral_execution.hardware_utils.macos`.
    - Updated `ThreadPinner` sealed permits clause to permit `MacosAffinity` and `ThreadTools` to
      select `MacosAffinity.INSTANCE`.
- **Verification Results**:
    - `MacosAffinityTest` passed: capability validation, `getCpu()` returning `-1`, single-locality
      mask acceptance, multi-locality mask rejection, tag `0` release, safe timer policy, negative
      argument rejection (`IllegalArgumentException`).
    - `NativeCompatibilityTest` passed: preserved aggregate native products and JNI declarations
      baseline contract (`native-contract-900d8c50.tsv`).
    - `NativeBinaryGateTest` passed: universal Mach-O binary gates (`x86_64` + `arm64`) targeting
      macOS 11.0.
    - `NativeSigningTest` passed: codesign verification (`codesign -v`).
    - `NativeBinaryInspectionIT` passed.
    - Full `gradle build` succeeded with clean output across all workspace modules.
- **Audit Summary**:
    - Independent audit report completed: [
      `docs/audits/hardware-utils/phase-7-macos-affinity-native-conformance.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/audits/hardware-utils/phase-7-macos-affinity-native-conformance.md).
    - Audited tag decoding in `macos_affinity.cpp`: extracted raw tag integer `masks[0]` directly
      with `JNI_ABORT` pointer release mode.
    - All 6 acceptance criteria verified and classified as **satisfied**.
