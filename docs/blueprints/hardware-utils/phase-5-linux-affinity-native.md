# Phase 5-C Linux Native ABI, Syscalls, & Affinity Blueprint

## 1. Status and Authority

- **Parent Plan**: [`docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md)
- **Parent Blueprint**: [`docs/blueprints/hardware-utils/phase-5-linux-platform.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-5-linux-platform.md)
- **P5 Root Branch**: `hardware-utils-overhaul/phase-5-linux`
- **Child Blueprint Branch**: `hardware-utils-overhaul/phase-5-linux-affinity-native-blueprint`
- **Child Implementation Branch**: `hardware-utils-overhaul/phase-5-linux-affinity-native-implementation`
- **Audit File Target**: `docs/audits/hardware-utils/phase-5-linux-affinity-native-conformance.md`
- **Owning Module**: `euhedral-hardware-utils`
- **Selected Blueprint Model**: `gpt-5.6-sol` with `high` reasoning effort
- **Status**: Implementation-ready child blueprint. Pending developer review and merge into the P5 root before child implementation begins.

This child blueprint is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, and the parent P5 blueprint (`phase-5-linux-platform.md`). It translates parent native ABI and affinity syscall contracts into an explicit, implementable specification for `LinuxAffinity`, `LinuxAffinityCalls`, `linux_affinity.cpp`, and `linux_jni.h`.

## 2. Objective & Core Defects Addressed

The objective of **Phase 5-C** is to deliver a rock-solid, portable native Linux affinity and system call integration (`LinuxAffinity`, `linux_affinity.cpp`) operating via direct Linux system calls across x86-64 and AArch64 architectures.

### Core Defect Corrections & Technical Objectives

- **Direct Linux Syscalls**: Execute Linux thread affinity (`sys_sched_setaffinity`, `sys_sched_getaffinity`), current CPU discovery (`sys_getcpu`), and process control timer slack (`sys_prctl`) via direct `syscall()` invocations, ensuring ABI stability across glibc and musl runtimes.
- **Kernel Floor Verification (Linux 3.10 Baseline)**: Prove Linux 3.10 as the lowest practical runtime floor, verifying that all required system calls (`SYS_sched_setaffinity`, `SYS_sched_getaffinity`, `SYS_getcpu`, `SYS_prctl`) have been immutable kernel ABI since Linux 2.6.28.
- **Dual ELF Binary Gates & libc Portability**: Target dual ELF JNI artifacts compiled via Zig for `glibc 2.17` (`x86_64-linux-gnu.2.17`, `aarch64-linux-gnu.2.17`) and `musl` (`x86_64-linux-musl`, `aarch64-linux-musl`) with zero `libstdc++`, `libc++`, or `libgcc_s` runtime dependencies using `-fno-exceptions -fno-rtti`.
- **JNI Array Pinning & Memory Safety**: Enforce strict null checks, array length verification, and zero-copy pinning (`GetLongArrayElements` / `ReleaseLongArrayElements` with `JNI_ABORT`) inside native C++ handlers.
- **Errno Handling & Translation**: Translate native return codes cleanly (`0` for success, non-zero POSIX `errno` on failure) into Java boolean success indicators.
- **Timer Slack Granularity**: Expose `prctl(PR_SET_TIMER_SLACK)` for non-negative nanosecond resolutions, clamping values to at least 1 ns.
- **Affinity Lease Capture & Restoration**: Support exact thread affinity capture (`sys_sched_getaffinity`) and restoration (`sys_sched_setaffinity`) integrated with P3's `AffinityController` lease protocol.

## 3. Scope & Non-Goals

### 3.1. In Scope

- **Primary Source Files**:
    - [`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxAffinity.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxAffinity.java)
    - [`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxAffinityCalls.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxAffinityCalls.java)
    - [`euhedral-hardware-utils/src/main/native/linux/linux_affinity.cpp`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/linux/linux_affinity.cpp)
    - [`euhedral-hardware-utils/src/main/native/linux/linux_jni.h`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/native/linux/linux_jni.h)
- **Native System Calls & Numbers**:
    - `SYS_sched_setaffinity`: Syscall 203 (x86-64), 122 (AArch64).
    - `SYS_sched_getaffinity`: Syscall 204 (x86-64), 123 (AArch64).
    - `SYS_getcpu`: Syscall 309 (x86-64), 168 (AArch64).
    - `SYS_prctl`: Syscall 157 (x86-64), 167 (AArch64) with `PR_SET_TIMER_SLACK = 29`.
- **P3 Affinity Controller Integration**:
    - `LinuxAffinity` extending `ThreadPinner` with `AffinityCapability.EXACT`.
    - Native binding methods: `getThreadAffinity(long[] masks)`, `setThreadAffinity(long[] masks)`, `getCpu()`, `prctl(long nanos)`.
    - Capturing original thread mask into `AffinityController` lease snapshot before applying new masks.
- **Testing & Validation**:
    - Unit tests in [`LinuxAffinityTest.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/linux/LinuxAffinityTest.java).
    - JNI boundary validations and error handling tests.

### 3.2. Non-Goals

- Modifying Linux CPU topology layout or sysfs parsing (owned by P5-A).
- Modifying cgroup resource collection or `/proc/stat` parsing (owned by P5-B).
- Modifying common P3 `AffinityController` or P1 Zig build script logic.
- Modifying Windows or macOS native affinity implementations (reserved for P6/P7).
- Any inspection, build, or test activity under `euhedral-training`.

## 4. Architectural Contracts & Implementation Checklist

```text
LinuxAffinity.setAffinity(masks) / captureAffinity() / restoreExact(masks)
                   |
                   v
LinuxAffinityCalls.apply(masks, RawCall)  [Mask Canonicalization & Validation]
                   |
                   v
       Java Native Method (JNI Boundary)
  setThreadAffinity / getThreadAffinity / getCpu / prctl
                   |
                   v
  linux_affinity.cpp (Native C++)
  - GetLongArrayElements (Pinned Memory Access)
  - Direct syscall(SYS_..., 0, cpusetsize, mask)
  - ReleaseLongArrayElements(..., JNI_ABORT)
  - Return (jint) errno or 0
```

### 4.1. Checklist Item 1: Direct Linux System Calls & Architecture Numbers

- [ ] **Syscall Number Definitions**:
    - Include `<sys/syscall.h>` and `<unistd.h>`.
    - Ensure system call macro resolution:
        - `SYS_sched_setaffinity`: 203 on x86-64, 122 on AArch64.
        - `SYS_sched_getaffinity`: 204 on x86-64, 123 on AArch64.
        - `SYS_getcpu`: 309 on x86-64, 168 on AArch64.
        - `SYS_prctl`: 157 on x86-64, 167 on AArch64.
        - `PR_SET_TIMER_SLACK`: define as `29` if not present in standard system headers.
- [ ] **Native Syscall Wrappers in `linux_affinity.cpp`**:
    - `setThreadAffinity`: `syscall(SYS_sched_setaffinity, 0, (size_t)(len * 8), (unsigned long *)masks)`.
    - `getThreadAffinity`: `syscall(SYS_sched_getaffinity, 0, (size_t)(len * 8), (unsigned long *)masks)`.
    - `getCpu`: `unsigned cpu = 0; long res = syscall(SYS_getcpu, &cpu, NULL, NULL); return (res == 0) ? (jint)cpu : -1;`.
    - `prctl`: `long res = syscall(SYS_prctl, PR_SET_TIMER_SLACK, (unsigned long)nanos, 0UL, 0UL, 0UL); return (res == 0) ? 0 : (jint)errno;`.

### 4.2. Checklist Item 2: Linux 3.10 Kernel Floor Verification & Proof

- [ ] **Kernel Floor Proof Documentation**:
    - All four required system calls (`sys_sched_setaffinity`, `sys_sched_getaffinity`, `sys_getcpu`, `sys_prctl`) have maintained stable kernel ABI parameters since Linux 2.6.28.
    - Practical runtime floor is proven as **Linux 3.10** (CentOS 7 / RHEL 7 baseline).
    - Runtime features present on newer kernels (cgroup v2 in 3.16+/4.5+, PSI in 4.20+) are feature-detected independently by P5-B without breaking on 3.10.

### 4.3. Checklist Item 3: Dual ELF Gates & libc Portability

- [ ] **Compiler & Linker Flags**:
    - Compile with `-fno-exceptions -fno-rtti -fvisibility=hidden`.
    - Zero dependencies on `libstdc++`, `libc++`, or `libgcc_s`.
- [ ] **Binary Gate Allowlist & Targets**:
    - Targets: `x86_64-linux-gnu.2.17`, `aarch64-linux-gnu.2.17`, `x86_64-linux-musl`, `aarch64-linux-musl`.
    - Allowed libraries: `libc.so.6` (glibc 2.17) or `libc.so` (musl).
    - Exported symbols: `JNI_OnLoad` and JNI native entries for `LinuxAffinity`.

### 4.4. Checklist Item 4: JNI Array Pinning Safety & Memory Checks

- [ ] **Input Pointer & Length Validation**:
    - Verify `maskArray != NULL`. If `NULL`, return `EINVAL`.
    - Check array length `len = env->GetArrayLength(maskArray)`. If `len <= 0`, return `EINVAL`.
- [ ] **Array Pinning & Release**:
    - Obtain direct native pointer: `jlong *masks = env->GetLongArrayElements(maskArray, NULL);`.
    - Handle allocation failure: If `masks == NULL`, return `ENOMEM` or `EINVAL`.
    - Execute syscall with `masks`.
    - Release pointer immediately using `env->ReleaseLongArrayElements(maskArray, masks, JNI_ABORT);`.
    - `JNI_ABORT` guarantees that array contents are not copied back to Java heap, saving overhead since `setThreadAffinity` reads the mask array without modifying it.
    - For `getThreadAffinity`, release pointer using `0` (mode 0) to commit updated affinity bits back to the Java `long[]` array.

### 4.5. Checklist Item 5: Errno Handling & Java Return Translation

- [ ] **Native Error Code Return**:
    - Evaluate syscall result: `int err = (result == 0) ? 0 : errno;`.
    - Return `(jint)err` to Java caller.
- [ ] **Java Layer Translation**:
    - `LinuxAffinityCalls.apply()` converts `0` return code to boolean `true`, and non-zero return code (e.g. `EINVAL`, `EPERM`, `ESRCH`) to boolean `false`.
    - Handle `RuntimeException` and `LinkageError` safely inside `LinuxAffinityCalls.apply()`, returning `false` on failure without propagating exceptions.

### 4.6. Checklist Item 6: Timer Slack Configuration

- [ ] **Nanosecond Granularity Contract**:
    - `LinuxAffinity.setTimerResolution(long nanos)`:
        - If `nanos < 0`, throw `IllegalArgumentException` / `RuntimeException` ("Cannot set negative resolution: ...").
        - Clamp positive `nanos` to `Math.max(1L, nanos)`.
        - Invoke native `prctl(nanos)`.
        - Return `true` if native call returns `0`, otherwise log diagnostic error and return `false`.

### 4.7. Checklist Item 7: Affinity Lease Capture & Restoration

- [ ] **ThreadPinner / AffinityProvider Contract Implementation**:
    - Update `LinuxAffinity` to declare `AffinityCapability.EXACT` when native JNI is successfully loaded.
    - Implement `captureAffinity()`:
        - Allocate target `long[]` mask array bounded by `SystemInfo.getCpuCount()`.
        - Invoke native `getThreadAffinity(maskArray)`.
        - Return `maskArray` on success, or `null` if native call fails.
    - Implement `applyExact(long[] mask)`:
        - Pass canonical little-endian mask to native `setThreadAffinity(mask)`.
    - Implement `restoreExact(long[] mask)`:
        - Pass saved snapshot mask to native `setThreadAffinity(mask)`.
    - Integrate with P3 `AffinityController`:
        - Acquiring an exact affinity lease captures the current mask via `captureAffinity()`.
        - Releasing the lease restores original binding via `restoreExact(snapshot)`.

## 5. Sizing & Split Gate Assessment

### Sizing Evaluation

1. **Context Load**: The implementation is strictly bounded to `LinuxAffinity.java`, `LinuxAffinityCalls.java`, `linux_affinity.cpp`, `linux_jni.h`, and `LinuxAffinityTest.java`. The total context required involves JNI array pinning, direct Linux system call invocation, errno mapping, timer slack, and exact affinity lease capture/restoration. This fits comfortably within the working memory of a single implementation pass.
2. **Single Responsibility**: `LinuxAffinity` and `linux_affinity.cpp` own native Linux thread affinity, CPU queries, and timer slack. Topology (P5-A) and cgroups (P5-B) are cleanly separated.
3. **Independent Validation**: Native affinity calls can be validated via JNI mocks, native unit tests, and smoke execution on Linux test hosts.

**Conclusion**: Child P5-C is irreducible, correctly sized, and ready for implementation in a single pass.

## 6. Implementation Model Reassessment

- **Required Capabilities**: JNI array memory management, direct Linux system call ABI correctness across x86-64 and AArch64, errno translation, dual ELF glibc/musl binary gate validation, and affinity lease restoration safety.
- **Selected Model**: **`gpt-5.6-sol` with `high` reasoning effort**.
- **Justification**: Ensuring C++ pointer safety, zero copy array release semantics, POSIX error handling, and exact affinity lease restoration requires high reasoning effort.

## 7. Developer-Review Summary

| Item | Details |
|---|---|
| **Purpose** | Deliver direct Linux system call affinity, current CPU discovery, timer slack, and exact affinity lease capture/restoration (`LinuxAffinity`, `linux_affinity.cpp`) with glibc 2.17 / musl dual ELF ABI portability on Linux 3.10+. |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.linux.LinuxAffinity`, `LinuxAffinityCalls` (Java), `src/main/native/linux/*` (C++). |
| **Key Invariants** | System calls use direct `syscall()` numbers across x86-64 and AArch64; Linux 3.10 kernel floor proven; dual ELF compilation with zero C++ runtimes (`-fno-exceptions -fno-rtti`); JNI long arrays pinned safely and released with `JNI_ABORT` (set) or mode `0` (get); errno translated to boolean success; timer slack clamped to at least 1 ns; exact affinity leases capture and restore original thread masks. |
| **Child Action Items** | P5-C implementation: `hardware-utils-overhaul/phase-5-linux-affinity-native-implementation`. |
| **Selected Model** | `gpt-5.6-sol` with `high` reasoning effort for implementation and conformance audit. |
| **Principal Risks** | Incorrect syscall numbers per architecture; JNI memory leaks or invalid pointer dereference; array length mismatches; non-zero errno causing uncaught exceptions. |
| **Unresolved Items** | None. Syscall numbers, JNI method descriptors, array pinning rules, and error return paths are fully settled. |

## 8. Verification & Acceptance Criteria

### 8.1. Acceptance Criteria

1. **Direct Linux System Calls**:
    - Native functions invoke `sys_sched_setaffinity`, `sys_sched_getaffinity`, `sys_getcpu`, and `sys_prctl` using exact system call numbers for x86-64 and AArch64.
2. **JNI Memory Safety**:
    - Native `setThreadAffinity` and `getThreadAffinity` handle `NULL` array inputs cleanly, returning `EINVAL`.
    - Long array elements are released via `ReleaseLongArrayElements` with `JNI_ABORT` for setter calls and `0` for getter calls.
3. **Errno Translation**:
    - Native functions return `0` on success and POSIX `errno` (e.g., `EINVAL`, `EPERM`) on failure. `LinuxAffinityCalls` maps `0` to `true` and non-zero to `false`.
4. **Timer Slack Granularity**:
    - `setTimerResolution(nanos)` rejects negative values and passes clamped positive values to `prctl(PR_SET_TIMER_SLACK)`.
5. **Affinity Lease Capture & Restoration**:
    - `captureAffinity()` retrieves the calling thread's current affinity mask.
    - `restoreExact(snapshot)` successfully restores the captured mask when releasing an exact affinity lease.

### 8.2. Verification Commands

```bash
# Build hardware-utils module and run Linux affinity tests
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.linux.LinuxAffinityTest"

# Build full hardware-utils module including Zig native JNI artifacts
gradle :euhedral-hardware-utils:build

# Run all hardware-utils tests
gradle :euhedral-hardware-utils:test
```

## 9. Completion Record

### Changed Files

- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxAffinity.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxAffinityCalls.java`
- `euhedral-hardware-utils/src/main/native/linux/linux_affinity.cpp`
- `euhedral-hardware-utils/src/main/native/linux/linux_jni.h`
- `euhedral-hardware-utils/src/main/native/common/jni_onload.cpp`
- `euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/linux/LinuxAffinityTest.java`

### Commands Run & Results

- `gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.linux.LinuxAffinityTest"`: PASSED (4/4 tests clean).
- `gradle :euhedral-hardware-utils:zigBuild --rerun-tasks`: PASSED (built dual ELF glibc 2.17 / musl binaries for x86-64 and AArch64).
- `gradle :euhedral-hardware-utils:test`: PASSED (133/133 tests clean, including NativeBinaryGateTest, NativeBinaryInspectionIT, NativeLoadSmokeIT, NativePackagingIT).
- `gradle build`: PASSED (full multi-module build and verification clean).

### Acceptance Evidence

- **Direct Linux Syscalls**: Direct `syscall()` wrappers implemented in `linux_affinity.cpp` using exact numbers for x86-64 (`SYS_sched_setaffinity=203`, `SYS_sched_getaffinity=204`, `SYS_getcpu=309`, `SYS_prctl=157`) and AArch64 (`SYS_sched_setaffinity=122`, `SYS_sched_getaffinity=123`, `SYS_getcpu=168`, `SYS_prctl=167`).
- **JNI Array Safety**: Null pointer checks return `EINVAL`, `GetLongArrayElements` failures return `ENOMEM`, setter calls release via `JNI_ABORT` (no heap copy-back), getter calls release via mode `0` (commit results back to Java heap).
- **Errno Translation**: `LinuxAffinityCalls.apply()` converts `0` return code to `true` and non-zero to `false`, swallowing `RuntimeException` / `LinkageError` safely.
- **Timer Slack Granularity**: Negative `nanos` rejected with `RuntimeException`, positive values clamped to at least `1L` and sent via `SYS_prctl` with `PR_SET_TIMER_SLACK=29`.
- **Affinity Lease Capture & Restoration**: `captureAffinity()` retrieves calling thread's current exact CPU mask trimmed to logical CPU count span, and `restoreExact()` successfully restores captured masks via `AffinityController`.

### Approved Deviations

- None.

### Environmental Limits

- Native JNI execution requires Linux platform; non-Linux platforms fallback safely to `AffinityCapability.UNSUPPORTED` without linkage failures.
