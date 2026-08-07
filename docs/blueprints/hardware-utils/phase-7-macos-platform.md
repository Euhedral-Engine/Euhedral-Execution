# Phase 7 macOS Platform Blueprint: Topology, Resource Collection, Locality Affinity, and Mach-O Parity

## 1. Executive Summary & Objective

This blueprint establishes the architecture, technical contracts, data flows, and child responsibilities for **Phase 7 (macOS Parity, Public Sysctl Topology, Locality Affinity, and Mach-O ABI)** of the `euhedral-hardware-utils` overhaul, as governed by [`docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md).

The objective of Phase 7 is to deliver a robust, truthful, read-only macOS hardware and resource provider supporting both Intel x86_64 (Macs with SMT hyperthreading) and Apple Silicon ARM64 (SoCs with heterogeneous P-cores and E-cores). The implementation strictly uses public macOS APIs and frameworks (`sysctlbyname`, `proc_pid_rusage`, `task_info`, `mach_timebase_info`, `NSProcessInfo`), enforces a macOS 11 deployment target floor, adheres to the Phase 4 pressure engine, reports honest `LOCALITY_HINT` affinity tag semantics, and delivers universal binaries (`x86_64` + `arm64`) with zero C++ runtime dependencies (`-fno-exceptions -fno-rtti`).

## 2. Scope & Non-Goals

### 2.1. In Scope

- Deterministic topology discovery via public `sysctlbyname` keys (`hw.logicalcpu`, `hw.physicalcpu`, `hw.packages`, `hw.nperflevels`, `hw.perflevel0.logicalcpu`, `hw.perflevel0.cpusperl2`, `hw.perflevel1.logicalcpu`, `hw.perflevel1.cpusperl2`, `hw.l1icachesize`, `hw.l1dcachesize`, `hw.l2cachesize`, `hw.l3cachesize`, `hw.cachelinesize`, `hw.memsize`).
- Conservative fallback topology modeling for missing sysctl keys (Intel Macs or older macOS versions) using `machdep.cpu.brand_string` or SMT calculation (`logicalcpu > physicalcpu`).
- Heterogeneous P-core vs E-core classification (`CoreKind.PERFORMANCE` vs `CoreKind.EFFICIENCY`) on Apple Silicon, and homogeneous `CoreKind.UNKNOWN` classification on Intel Macs.
- Bijective logical CPU ID mapping (`0..availableProcessors-1`) with stable socket, die, core, and cache domain assignments.
- Process CPU cumulative nanoseconds (`ri_user_time + ri_system_time`) and cumulative I/O bytes (`ri_diskio_bytesread + ri_diskio_byteswritten`) via `proc_pid_rusage(RUSAGE_INFO_V3)`.
- Telemetry rule: host/process CPU and process I/O counters are treated strictly as **telemetry**. They do NOT become CPU or I/O pressure without a separately documented public wait, stall, or capacity-loss signal; unsupported pressure metrics remain `SignalValidity.UNSUPPORTED` / neutral ($0.0$).
- Process resident memory (working set) and virtual memory via `task_info(MACH_TASK_BASIC_INFO)` and total physical RAM via `hw.memsize`.
- `NSProcessInfo` public thermal state (`NSProcessInfoThermalState`) mapped to `ThermalSeverity` (`NOMINAL`, `FAIR`, `SERIOUS`, `CRITICAL`), and low-power mode (`isLowPowerModeEnabled`) mapped to `BooleanSignal`.
- Fast (200 ms) and slow (5 s) hardware sample collection cadences.
- Managed thread affinity reporting `AffinityCapability.LOCALITY_HINT`. Legacy `setAffinity(long[] masks)` returns `true` ONLY for single-locality requests (single set bit `c`, mapped to Mach thread affinity tag `c + 1`), and returns `false` (deterministic rejection) for multi-locality requests. Tag `0` clears locality preference.
- Physical current CPU query returns `-1` (unsupported on macOS outside managed ownership).
- Safe idempotent timer policy converting nanoseconds to Mach absolute time ticks via `mach_timebase_info`, strictly avoiding `THREAD_TIME_CONSTRAINT_POLICY` realtime scheduling.
- Mach-O universal binary packaging (`x86_64` + `arm64`) targeting macOS 11 deployment floor, compiled with `-fno-exceptions -fno-rtti -fvisibility=hidden`, linked against `Foundation`, `CoreFoundation`, `IOKit`, `libSystem.B.dylib`, with `codesign -v` verification.

### 2.2. Non-Goals

- Modifying or redesigning the common sampling or pressure normalization curves established in Phase 4.
- Using undocumented or private macOS APIs or private Mach traps.
- Fabricating hard CPU affinity or physical current CPU IDs when not exposed by macOS.
- Using `THREAD_TIME_CONSTRAINT_POLICY` or installing realtime scheduling policies.
- Linux or Windows implementation changes (owned by Phase 5 and Phase 6).
- Modifying `euhedral-core` fragment loops or execution schedulers.
- Any inspection, build, or test activity involving `euhedral-training`.

## 3. Core Architectural & Technical Contracts

### 3.1. sysctl Key Discovery, Heterogeneous Cores, & Conservative Fallbacks

- **sysctl Queries & Standard Keys**:
  ```c
  // Standard macOS sysctl queries via sysctlbyname()
  hw.logicalcpu            // Total logical processor count (int)
  hw.physicalcpu           // Total physical core count (int)
  hw.packages              // Physical CPU socket count (int, default 1 if missing)
  hw.nperflevels           // Performance levels (int, e.g., 2 for Apple Silicon M-series)
  hw.perflevel0.logicalcpu // Number of P-cores (Performance cores)
  hw.perflevel0.cpusperl2  // P-core count sharing L2 cache
  hw.perflevel1.logicalcpu // Number of E-cores (Efficiency cores)
  hw.perflevel1.cpusperl2  // E-core count sharing L2 cache
  hw.l1icachesize          // L1 Instruction cache size in bytes (long)
  hw.l1dcachesize          // L1 Data cache size in bytes (long)
  hw.l2cachesize           // L2 cache size in bytes (long)
  hw.l3cachesize           // L3 cache size in bytes (long, 0 if absent)
  hw.cachelinesize         // CPU cache line size in bytes (int, e.g., 64 or 128)
  hw.memsize               // Total physical memory in bytes (long)
  ```
- **Apple Silicon Heterogeneous Core Modeling**:
  - If `hw.nperflevels >= 2`:
    - `perflevel0` represents P-cores (`CoreKind.PERFORMANCE`).
    - `perflevel1` represents E-cores (`CoreKind.EFFICIENCY`).
    - On macOS, E-cores are indexed first (`0 .. E_count - 1`), followed by P-cores (`E_count .. total - 1`), or derived directly from `hw.perflevel*.logicalcpu`.
- **Intel Mac SMT & Fallback Modeling**:
  - If `hw.nperflevels` is missing or `<= 1`:
    - Query `machdep.cpu.brand_string` (e.g. "Intel(R) Core(TM) i9...").
    - All cores are classified as `CoreKind.UNKNOWN` (homogeneous system).
    - If `hw.logicalcpu > hw.physicalcpu`, SMT / hyperthreading is present (threads per core = `logicalcpu / physicalcpu`).
- **Missing-Key Conservative Fallback**:
  - If any sysctl query fails, return safe default: `logicalcpu` defaults to `Runtime.getRuntime().availableProcessors()`, `physicalcpu` defaults to `logicalcpu`, `packages` defaults to 1.
  - `TopologyBootstrap.normalize()` automatically synthesizes a valid fallback topology (1 socket, 1 die, cores = physicalcpu, cpus = logicalcpu).

### 3.2. Bijective Managed Logical CPU Ownership

- **Logical ID Assignment**:
  - Global Logical CPU IDs map bijectively to contiguous indices `0 .. availableProcessors - 1`.
  - Reverse mapping: `packageId = "macos:package:0"`, `dieId = "macos:die:0"`, `coreId = "macos:core:" + String.format("%08x", coreIdx)`.
- **Cache Domain Assembly**:
  - L1 Data Cache: `CacheDomain(1, l1dSizeBytes, lineSizeBytes, bitsetPerCore)`.
  - L2 Cache: Assembled using `cpusperl2` grouping or global L2 size. `BitSet` masks cover CPU IDs sharing each L2 domain.
  - L3 Cache: If `hw.l3cachesize > 0`, single L3 `BitSet` covers all logical CPUs. Instruction caches (`hw.l1icachesize`) are strictly excluded.

### 3.3. Public Cumulative CPU/Process/I/O Telemetry Rules

- **Process CPU Times**:
  - Query `proc_pid_rusage(getpid(), RUSAGE_INFO_V3, &rusage)`.
  - Sum user and system time in nanoseconds:
    $$\text{cpuUsageNs} = \text{rusage.ri\_user\_time} + \text{rusage.ri\_system\_time}$$
  - Fallback if `proc_pid_rusage` fails: `getrusage(RUSAGE_SELF, &usage)` converting `tv_sec * 1e9 + tv_usec * 1000`.
- **Cumulative I/O Bytes**:
  - From `proc_pid_rusage`:
    $$\text{ioBytes} = \text{rusage.ri\_diskio\_bytesread} + \text{rusage.ri\_diskio\_byteswritten}$$
- **Telemetry Rule (Pressure Isolation)**:
  - macOS does not expose public wait/stall/capacity-loss signals equivalent to Linux PSI or Windows JobObject rates.
  - Process CPU time and process I/O bytes are reported as **telemetry counters** in `FastHardwareSample`.
  - They MUST NOT be converted into artificial CPU or I/O pressure metrics.
  - Unsupported pressure fields MUST carry `SignalValidity.UNSUPPORTED` and default to $0.0$.

### 3.4. Host & Task Memory Semantics & Mach Cleanup

- **System Memory**:
  - sysctl `hw.memsize` returns total physical RAM in bytes (`jlong`).
- **Process Resident Memory**:
  - Call `task_info(mach_task_self(), MACH_TASK_BASIC_INFO, (task_info_t)&info, &count)`.
  - `WorkingSetSize` = `info.resident_size` (bytes).
  - `VirtualSize` = `info.virtual_size` (bytes).
  - Shared memory = `Math.max(0L, info.virtual_size - info.resident_size)`.
- **Mach Timebase Conversion**:
  - Call `mach_timebase_info(&timebase)`.
  - Validate `timebase.denom > 0` before division to prevent divide-by-zero traps.
  - Convert Mach absolute time ticks to nanoseconds:
    $$\text{nanos} = \frac{\text{ticks} \times \text{timebase.numer}}{\text{timebase.denom}}$$

### 3.5. `NSProcessInfo` Thermal & Low-Power Signals

- **Objective-C / Foundation Integration**:
  - Access `[NSProcessInfo processInfo]`.
- **Thermal State Mapping**:
  - Query `NSProcessInfo.processInfo.thermalState`:
    - `NSProcessInfoThermalStateNominal` (0) $\rightarrow$ `ThermalSeverity.NOMINAL`
    - `NSProcessInfoThermalStateFair` (1) $\rightarrow$ `ThermalSeverity.FAIR`
    - `NSProcessInfoThermalStateSerious` (2) $\rightarrow$ `ThermalSeverity.SERIOUS`
    - `NSProcessInfoThermalStateCritical` (3) $\rightarrow$ `ThermalSeverity.CRITICAL`
  - Tagged with `SignalValidity.VALID` and timestamp `requestedAtNs`.
- **Low-Power Mode Mapping**:
  - Query `NSProcessInfo.processInfo.isLowPowerModeEnabled`:
    - Returns `BOOL` (`true` if Battery Saver / Low Power Mode active).
    - Map to `BooleanSignal(enabled, requestedAtNs, SignalValidity.VALID)`.
- **Collection Cadences**:
  - Fast Cadence (200 ms): Process CPU times, resident memory, cumulative I/O bytes.
  - Slow Cadence (5 s): `NSProcessInfo` thermal severity, low-power mode flag.

### 3.6. Locality Affinity & Tag Semantics

- **Capability Declaration**:
  - `MacosAffinity` reports `AffinityCapability.LOCALITY_HINT`.
- **Locality Tag Semantics**:
  - Mach kernel supports thread affinity tags via `thread_policy_set(pthread_mach_thread_np(pthread_self()), THREAD_AFFINITY_POLICY, &policy, 1)`.
  - Tag `0` is the reserved release tag (clears locality preference).
  - Non-zero integer tags ($1, 2, 3, \dots$) advise the macOS scheduler to colocate threads sharing the same tag.
- **Single-Locality Mask Enforcement (Defect Prevention)**:
  - `MacosAffinityCalls.applyOrdinal(long[] masks)` checks requested mask array.
  - If `masks` has EXACTLY ONE set bit (ordinal `c`), it maps `c` to Mach affinity tag `c + 1` and calls native `setThreadAffinity(tag)`. Returns `true`.
  - If `masks` has ZERO set bits, or MORE THAN ONE set bit (arbitrary multi-locality request), it returns `false` (deterministic rejection).
- **Physical Current CPU Query**:
  - macOS kernel does not expose a public API to query the physical CPU ID currently executing the thread.
  - `MacosAffinity.getCpu()` returns `-1` (`UNSUPPORTED`) outside managed logical ownership.

### 3.7. Safe Idempotent Timer Policy

- **No Realtime Scheduling Policy**:
  - Legacy implementations invoked `THREAD_TIME_CONSTRAINT_POLICY` with hardcoded computation ratios, which converted the calling thread into a macOS realtime thread.
  - This blueprint strictly forbids `THREAD_TIME_CONSTRAINT_POLICY` or realtime thread policy creation.
- **Safe Timer Policy**:
  - `setTimerResolution(nanos)` validates `nanos >= 0` and tracks requested resolution.
  - On macOS, timer resolution is governed system-wide by Grand Central Dispatch (GCD) and high-precision `kdebug` / `nanosleep` primitives.
  - `setTimerResolution` completes safely and idempotently without mutating thread scheduling constraints.

### 3.8. Mach-O Universal Binary, Deployment Floor & Hardening

- **Target Deployment Floor**:
  - macOS 11.0 (Big Sur) baseline floor for both `x86_64` and `arm64`.
- **Compiler & Framework Policy**:
  - Compiled via Zig `zig build-lib -target x86_64-macos -target aarch64-macos`.
  - C++ flags: `-fno-exceptions -fno-rtti -fvisibility=hidden`.
  - Allowed dynamic libraries/frameworks: `Foundation.framework`, `CoreFoundation.framework`, `IOKit.framework`, `/usr/lib/libSystem.B.dylib`.
  - Forbidden libraries: libstdc++, libc++ dynamic links outside libSystem, private frameworks.
- **Universal Binary Packaging**:
  - Native artifact packaged as a universal Mach-O fat binary (`libmacos.dylib` containing `x86_64` and `arm64` slices) or architecture-specific dylibs in `native-products.json`.
- **Codesign Verification**:
  - CI build and native loader execute `codesign -v` verification on native `.dylib` binaries.

## 4. Data Surface, Package Ownership, & Data Flow

### 4.1. Package & Source Ownership

- **Java Sources**: `io.euhedral_execution.hardware_utils.macos.*`
  - `MacosSystemLayout`: Topology provider, sysctl parser, heterogeneous core normalization.
  - `MacosResources`: Resource provider, `proc_pid_rusage`, `task_info`, `NSProcessInfo` thermal/low-power signals.
  - `MacosAffinity`: Locality-hint ThreadPinner implementation, single-locality enforcement, safe timer resolution.
  - `MacosAffinityCalls`: Ordinal-to-tag mapping and raw call dispatcher.
  - `sysctl.*`: Type-safe sysctl parsers (`SysctlLong`, `SysctlInt`, `SysctlString`).
- **Native Sources**: `euhedral-hardware-utils/src/main/native/macos/`
  - `macos_jni.h`: Mach, sysctl, proc_info, and Foundation JNI function declarations.
  - `macos_system_layout.cpp`: Native `sysctlbyname` wrappers (`getSysctlLong`, `getSysctlInt`, `getSysctlString`).
  - `macos_resources.cpp`: Native `proc_pid_rusage`, `task_info`, `NSProcessInfo` thermal state and low-power queries.
  - `macos_affinity.cpp`: Native `thread_policy_set` affinity tag wrapper, `mach_timebase_info`.
- **Manifest & CI Metadata**: `native-products.json`, `.github/workflows/` macOS matrix.

### 4.2. macOS Native-to-Java Data Flow Diagram

```text
macOS Kernel APIs / sysctl / proc_pid_rusage / NSProcessInfo
(sysctlbyname, proc_pid_rusage, task_info, NSProcessInfo.thermalState)
                      |
                      v
      Native C++ JNI Wrappers (macos_*.cpp)
      - Universal Mach-O Binary (x86_64 + arm64)
      - Zero C++ Runtime Overhead (-fno-exceptions -fno-rtti)
      - Mach Timebase & Deallocation Protection
                      |
                      v
        MacosSystemLayout / MacosResources / MacosAffinity
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

### 5.1. Child P7-A: macOS Topology Model & Sysctl Provider (`phase-7-macos-topology`)

- **Required Inputs**: P2 Topology Model contracts (`TopologyInput`, `LogicalCpu`, `TopologyBootstrap`), sysctl key documentation.
- **Owned Outputs**: `MacosSystemLayout`, `sysctl.*` parsers, Apple Silicon P/E-core classification (`hw.nperflevels`, `hw.perflevel*`), Intel SMT hyperthreading discovery, cache domain BitSet assembly, conservative missing-key fallback topology.

### 5.2. Child P7-B: macOS Resource Provider & Signals (`phase-7-macos-resources`)

- **Required Inputs**: P4 Sampling Engine (`DetailedSystemSnapshotProvider`, `FastHardwareSample`, `SlowHardwareSample`, `SignalValidity`), `proc_pid_rusage`, `task_info`, `NSProcessInfo`.
- **Owned Outputs**: `MacosResources`, `proc_pid_rusage` nanosecond CPU times and disk I/O bytes, `task_info` resident memory, `NSProcessInfo` thermal severity and low-power mode signals, telemetry pressure isolation (`SignalValidity.UNSUPPORTED` for unmeasured pressure).

### 5.3. Child P7-C: macOS Locality Affinity, Timer & Native ABI (`phase-7-macos-affinity-native`)

- **Required Inputs**: P3 Affinity contracts (`ThreadPinner`, `AffinityCapability`, `AffinityMasks`), P1 Zig native build graph (`native-products.json`).
- **Owned Outputs**: `MacosAffinity`, `MacosAffinityCalls`, `macos_affinity.cpp`, `macos_resources.cpp`, `macos_system_layout.cpp`, `macos_jni.h`, Mach thread affinity tag mapping with tag `0` release, single-locality mask enforcement with deterministic multi-locality rejection (`false`), physical current CPU returning `-1`, safe idempotent timer policy without realtime constraints, Mach-O universal binary ABI gates, codesign validation.

## 6. Sizing & Split Gate Assessment

### 6.1. Sizing Evaluation

Evaluating Phase 7 against the workflow sizing gate:

1. **Context Load**: Combining macOS sysctl topology parsing, Apple Silicon vs Intel core modeling, `proc_pid_rusage` and `task_info` metrics, `NSProcessInfo` thermal/low-power Objective-C signals, Mach thread affinity tag mapping, safe timer policy, universal binary ABI gates, and codesign checks exceeds the working memory of a single non-frontier implementation agent.
2. **Independent Responsibilities**: Topology parsing (`MacosSystemLayout`), resource metric collection (`MacosResources`), and native locality affinity/ABI (`MacosAffinity`) have clear package and operational boundaries.
3. **Independent Validation**: Topology parsing can be fully validated via sysctl fixture suites; resource collection via mock process metrics; and native affinity via JNI boundary unit tests and Mach-O binary inspection tools.

### 6.2. Action Plan & Child Branches

Phase 7 is split into three responsibility-scoped child action items:

1. **P7-A (macOS Topology Model & Sysctl Provider)**:
   - Branch: `hardware-utils-overhaul/phase-7-macos-topology-blueprint`
   - Blueprint: `docs/blueprints/hardware-utils/phase-7-macos-topology-model.md`
   - Implementation: `hardware-utils-overhaul/phase-7-macos-topology-implementation`
   - Audit: `docs/audits/hardware-utils/phase-7-macos-topology-model-conformance.md`
2. **P7-B (macOS Resource Provider & Signals)**:
   - Branch: `hardware-utils-overhaul/phase-7-macos-resources-blueprint`
   - Blueprint: `docs/blueprints/hardware-utils/phase-7-macos-resource-provider.md`
   - Implementation: `hardware-utils-overhaul/phase-7-macos-resources-implementation`
   - Audit: `docs/audits/hardware-utils/phase-7-macos-resource-provider-conformance.md`
3. **P7-C (macOS Locality Affinity, Timer & Native ABI)**:
   - Branch: `hardware-utils-overhaul/phase-7-macos-affinity-native-blueprint`
   - Blueprint: `docs/blueprints/hardware-utils/phase-7-macos-affinity-native.md`
   - Implementation: `hardware-utils-overhaul/phase-7-macos-affinity-native-implementation`
   - Audit: `docs/audits/hardware-utils/phase-7-macos-affinity-native-conformance.md`
4. **Root Phase Audit**:
   - Branch: `hardware-utils-overhaul/phase-7-macos-audit`
   - Audit: `docs/audits/hardware-utils/phase-7-macos-platform-conformance.md`

Only after this parent blueprint child is merged may child branches be created from the updated P7 root. Each child blueprint must rerun the sizing gate.

## 7. Mandatory Implementation Model Reassessment

Reassessing implementation model requirements across the child responsibilities:

- **Child P7-A (Topology)**: High context requirements around sysctl key extraction, Apple Silicon P/E-core mapping, Intel SMT discovery, cache domain BitSet assembly, and conservative fallback topology generation. Selected implementation model: **`gpt-5.6-sol` with `high` reasoning**.
- **Child P7-B (Resources)**: State machine across `proc_pid_rusage`, `task_info`, `NSProcessInfo` thermal/low-power signals, and telemetry pressure isolation. Selected implementation model: **`gpt-5.6-sol` with `high` reasoning**.
- **Child P7-C (Affinity & Native ABI)**: JNI array safety, Mach thread affinity tag mapping, tag `0` release, single-locality enforcement with deterministic rejection, safe timer resolution without realtime policy, universal Mach-O binary compilation, and codesign validation. Selected implementation model: **`gpt-5.6-sol` with `high` reasoning**.
- **Child & Root Audits**: Strong coding/audit model: **`gpt-5.6-sol` with `high` reasoning**.

Downgrading to low or medium reasoning is not supported due to coupled macOS system contracts, Objective-C / Mach kernel boundaries, and universal binary rules.

## 8. Developer-Review Summary

| Item | Details |
|---|---|
| **Purpose** | Deliver public sysctl topology discovery, Apple Silicon P/E-core and Intel SMT modeling, `proc_pid_rusage` CPU/IO telemetry, `task_info` memory tracking, `NSProcessInfo` thermal/low-power signals, locality-hint thread affinity with release tag `0`, safe timer policy without realtime constraints, and Mach-O universal binary ABI hardening for macOS 11+ platforms. |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.macos.*` (Java), `src/main/native/macos/*` (C++), `native-products.json` (Manifest). |
| **Key Contracts** | Public sysctl discovery (`hw.logicalcpu`, `hw.nperflevels`, `hw.perflevel*`); bijective logical CPU mapping (`0..N-1`); telemetry rule (process CPU/IO counters do not become artificial pressure); resident memory via `task_info`; thermal state (`NSProcessInfoThermalState`) and low-power mode (`isLowPowerModeEnabled`); locality-hint affinity with single-locality enforcement and tag `0` release; physical current CPU returns `-1`; safe timer policy without `THREAD_TIME_CONSTRAINT_POLICY` realtime constraints; universal Mach-O binaries (`x86_64` + `arm64`) with zero C++ runtimes (`-fno-exceptions -fno-rtti`) and codesign verification. |
| **Child Action Items** | P7-A (Topology & Sysctl), P7-B (Resources & Signals), P7-C (Affinity & Native ABI). |
| **Selected Model** | `gpt-5.6-sol` with `high` reasoning effort for all implementation and audit action items. |
| **Principal Risks** | Missing sysctl keys on older macOS releases or Intel hardware; Objective-C runtime linkage in native C++; `THREAD_TIME_CONSTRAINT_POLICY` realtime scheduling traps; Mach timebase divide-by-zero. |
| **Unresolved Items** | None. sysctl keys, unit scaling, core classification, thermal state mapping, locality tag rules, timer policy, and Mach-O ABI floors are fully settled. |
