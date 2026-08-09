# Phase 7-A macOS Topology & Sysctl Model Blueprint

## 1. Status and Authority

- **Parent Plan**: [`docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md)
- **Parent Blueprint**: [`docs/blueprints/hardware-utils/phase-7-macos-platform.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-7-macos-platform.md)
- **P7 Root Branch**: `hardware-utils-overhaul/phase-7-macos`
- **Child Blueprint Branch**: `hardware-utils-overhaul/phase-7-macos-topology-blueprint`
- **Child Implementation Branch**: `hardware-utils-overhaul/phase-7-macos-topology-implementation`
- **Audit File Target**: `docs/audits/hardware-utils/phase-7-macos-topology-model-conformance.md`
- **Owning Module**: `euhedral-hardware-utils`
- **Selected Blueprint Model**: `gpt-5.6-sol` with `high` reasoning effort
- **Status**: Implementation-ready child blueprint. Pending developer review and merge into the P7 root before child implementation begins.

This child blueprint is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, and the parent P7 blueprint (`phase-7-macos-platform.md`). It translates parent topology contracts into an explicit specification for `MacosSystemLayout` and `sysctl.*` type-safe parsers.

## 2. Objective & Core Defects Addressed

The objective of **Phase 7-A** is to deliver a complete, deterministic, sysctl-backed macOS CPU topology provider (`MacosSystemLayout`) and `sysctl.*` parsers that eliminate legacy assumptions and satisfy the P2 topology snapshot model.

### Core Requirements & Invariants

- **Exact sysctl Key Discovery**: Query public macOS sysctl keys (`hw.logicalcpu`, `hw.physicalcpu`, `hw.packages`, `hw.nperflevels`, `hw.perflevel0.logicalcpu`, `hw.perflevel0.cpusperl2`, `hw.perflevel1.logicalcpu`, `hw.perflevel1.cpusperl2`, `hw.l1icachesize`, `hw.l1dcachesize`, `hw.l2cachesize`, `hw.l3cachesize`, `hw.cachelinesize`, `hw.memsize`, `machdep.cpu.brand_string`).
- **Apple Silicon Heterogeneous Core Classification**: On Apple Silicon SoCs (`hw.nperflevels >= 2`), classify E-cores (`0 .. E_count - 1`) as `CoreKind.EFFICIENCY` and P-cores (`E_count .. total - 1`) as `CoreKind.PERFORMANCE`.
- **Intel SMT Hyperthreading Discovery**: On Intel Macs (`hw.nperflevels < 2`), detect SMT hyperthreading when `hw.logicalcpu > hw.physicalcpu` and group logical CPUs bijectively into physical core buckets with `CoreKind.UNKNOWN`.
- **Cache Domain BitSet Assembly**: Construct `CacheDomain` bitsets spanning logical CPUs for L1D, L2 (using `cpusperl2` clustering on Apple Silicon), and L3 (if present). Exclude L1 instruction caches (`hw.l1icachesize`).
- **Conservative Fallback Generation**: If sysctl keys are missing, incomplete, or unreadable, fall back to safe default parameters and delegate to `TopologyBootstrap.normalize()` to synthesize a single-socket fallback topology model.

## 3. Scope & Non-Goals

### 3.1. In Scope

- **Primary Source Files**:
  - `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosSystemLayout.java`
  - `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/sysctl/SysctlInt.java`
  - `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/sysctl/SysctlLong.java`
  - `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/sysctl/SysctlString.java`
- **sysctl Parameter Queries**: Type-safe sysctl parsing via JNI native methods `getSysctlInt`, `getSysctlLong`, `getSysctlString`.
- **Heterogeneous & Homogeneous Core Modeling**: `CoreKind.PERFORMANCE` vs `CoreKind.EFFICIENCY` mapping for Apple Silicon, `CoreKind.UNKNOWN` for Intel Macs.
- **Cache Domain Construction**: L1 data cache per physical core, L2 cache per core cluster or die, L3 cache per socket.
- **Fallback Topology Model**: Safe handling of missing keys via `TopologyBootstrap.normalize()`.
- **Testing & Fixtures**: Fixture unit test suite in `MacosTopologyFixtureTest.java`.

### 3.2. Non-Goals

- Modifying process CPU usage, disk I/O bytes, resident memory, or `NSProcessInfo` signals (owned by P7-B).
- Modifying JNI C++ code (`macos_affinity.cpp`), Mach thread affinity tag mapping, or safe timer policy (owned by P7-C).
- Modifying common `TopologyBootstrap` or `TopologyNormalizer` logic in `internal.topology`.
- Modifying `euhedral-core` fragment loops or execution schedulers.
- Any inspection or modification of `euhedral-training`.

## 4. Architectural Contracts & Implementation Checklist

```text
 macOS Kernel Public sysctl Interface (sysctlbyname)
                         |
                         v
       MacosSystemLayout.collect(sysctlProvider)
                         |
      +------------------+------------------+
      |                                     |
      v                                     v
sysctl Keys (Apple Silicon M-series)   sysctl Keys (Intel Macs)
- hw.nperflevels >= 2                  - hw.nperflevels missing or < 2
- perflevel0: P-cores                  - machdep.cpu.brand_string
- perflevel1: E-cores                  - SMT: logicalcpu > physicalcpu
- cpusperl2 cluster counts             - homogeneous CoreKind.UNKNOWN
      |                                     |
      +------------------+------------------+
                         |
                         v
          Cache Domain BitSet Assembly
          - L1D: Core-local BitSet (excl L1I)
          - L2: Cluster BitSet (cpusperl2)
          - L3: Socket BitSet (if hw.l3cachesize > 0)
                         |
                         v
  TopologyInput("macos", List<LogicalCpu>, List<CacheDomain>)
                         |
                         v
  TopologyBootstrap.normalize(provider, availableProcessors, logger, "macos")
                         |
                         v
  TopologyModel (Immutable, Bijective Logical IDs 0..N-1, P2 Cache Fallbacks)
```

### 4.1. Checklist Item 1: Type-Safe sysctl Parsers (`sysctl.*`) & Native JNI Handshake

- [ ] **`SysctlInt` Parser**:
  - Method `SysctlInt.query(String key)` invokes native `MacosSystemLayoutNative.getSysctlInt(key)`.
  - Returns `OptionalInt` containing parsed integer value, or `OptionalInt.empty()` if key is missing, invalid, or returns non-zero sysctl error.
- [ ] **`SysctlLong` Parser**:
  - Method `SysctlLong.query(String key)` invokes native `MacosSystemLayoutNative.getSysctlLong(key)`.
  - Returns `OptionalLong` containing parsed long value, or `OptionalLong.empty()` if key is missing or invalid.
- [ ] **`SysctlString` Parser**:
  - Method `SysctlString.query(String key)` invokes native `MacosSystemLayoutNative.getSysctlString(key)`.
  - Returns `Optional<String>` containing string value trimmed of trailing null characters, or `Optional.empty()` on failure.
- [ ] **Native Error Isolation**:
  - Native sysctl functions MUST NOT throw unchecked native exceptions or crash on missing sysctl keys.
  - Non-existent sysctl keys return -1 or error code, causing Java wrappers to return empty optionals.

### 4.2. Checklist Item 2: sysctl Key Discovery & Parameter Extraction

- [ ] **Standard sysctl Key Queries**:
  - `hw.logicalcpu` (int): Total logical processor count.
  - `hw.physicalcpu` (int): Total physical core count.
  - `hw.packages` (int): Physical CPU socket count (default 1 if missing or <= 0).
  - `hw.nperflevels` (int): Number of performance levels (2 for Apple Silicon M1/M2/M3/M4).
  - `hw.perflevel0.logicalcpu` (int): Number of P-cores (Performance cores).
  - `hw.perflevel0.cpusperl2` (int): P-core count sharing each L2 cache instance.
  - `hw.perflevel1.logicalcpu` (int): Number of E-cores (Efficiency cores).
  - `hw.perflevel1.cpusperl2` (int): E-core count sharing each L2 cache instance.
  - `hw.l1icachesize` (long): L1 Instruction cache size in bytes (strictly excluded from data cache domains).
  - `hw.l1dcachesize` (long): L1 Data cache size in bytes.
  - `hw.l2cachesize` (long): L2 cache size in bytes.
  - `hw.l3cachesize` (long): L3 cache size in bytes (0 if absent).
  - `hw.cachelinesize` (int): CPU cache line size in bytes (typically 64 or 128).
  - `hw.memsize` (long): Total physical system memory in bytes.
  - `machdep.cpu.brand_string` (String): CPU brand string on x86_64 Intel Macs.

### 4.3. Checklist Item 3: Apple Silicon Heterogeneous Core Classification (`CoreKind`)

- [ ] **Performance Level Inspection**:
  - Check `hw.nperflevels >= 2`.
  - Query `perflevel0.logicalcpu` (`pCount`) and `perflevel1.logicalcpu` (`eCount`).
- [ ] **Logical CPU Indexing Order**:
  - On macOS Apple Silicon ARM64, logical CPU indices `0 .. eCount - 1` correspond to E-cores (`perflevel1`).
  - Logical CPU indices `eCount .. eCount + pCount - 1` correspond to P-cores (`perflevel0`).
- [ ] **`CoreKind` Assignment**:
  - For logical IDs `0 .. eCount - 1`: create `LogicalCpu` with `CoreKind.EFFICIENCY`.
  - For logical IDs `eCount .. eCount + pCount - 1`: create `LogicalCpu` with `CoreKind.PERFORMANCE`.
  - If `hw.nperflevels < 2` or `eCount == 0` or `pCount == 0`: set all cores to `CoreKind.UNKNOWN`.

### 4.4. Checklist Item 4: Intel SMT Hyperthreading Discovery & Homogeneous Core Modeling

- [ ] **Intel Mac Detection**:
  - If `hw.nperflevels` is missing or `< 2`: system is Intel x86_64 or homogeneous ARM64.
  - Query `machdep.cpu.brand_string` for identification logging.
- [ ] **SMT Hyperthreading Calculation**:
  - Check `logicalcpu > physicalcpu`.
  - Calculate `threadsPerCore = (physicalcpu > 0 && logicalcpu > physicalcpu) ? (logicalcpu / physicalcpu) : 1`.
- [ ] **Physical Core Grouping**:
  - Group logical CPUs into physical core buckets: logical CPUs `c * threadsPerCore .. (c + 1) * threadsPerCore - 1` belong to physical core `c` (`0 .. physicalcpu - 1`).
  - All logical CPUs assigned `CoreKind.UNKNOWN`.

### 4.5. Checklist Item 5: Cache Domain BitSet Assembly

- [ ] **Instruction Cache Exclusion**:
  - Read `hw.l1icachesize`. Instruction cache is strictly excluded from `CacheDomain` output list.
- [ ] **L1 Data Cache Assembly**:
  - Read `hw.l1dcachesize` and `hw.cachelinesize` (default 64).
  - For each physical core `c`, construct a `BitSet` containing its logical CPU IDs.
  - Create `CacheDomain(1, l1dSizeBytes, lineSize, coreBitset)`.
- [ ] **L2 Cache Domain Assembly**:
  - Read `hw.l2cachesize`.
  - On Apple Silicon:
    - E-cores grouped into L2 clusters of size `perflevel1.cpusperl2`.
    - P-cores grouped into L2 clusters of size `perflevel0.cpusperl2`.
    - Construct `BitSet` for each cluster and create `CacheDomain(2, l2SizeBytes, lineSize, clusterBitset)`.
  - On Intel or missing cluster info:
    - Construct single `BitSet` covering all logical CPUs and create `CacheDomain(2, l2SizeBytes, lineSize, allCpusBitset)`.
- [ ] **L3 Cache Domain Assembly**:
  - Read `hw.l3cachesize`.
  - If `l3SizeBytes > 0`, construct single `BitSet` covering all logical CPUs across the socket and create `CacheDomain(3, l3SizeBytes, lineSize, allCpusBitset)`.
  - If `l3SizeBytes <= 0`, omit L3 cache domain.

### 4.6. Checklist Item 6: Conservative Missing-Key Fallback Topology Generation

- [ ] **Default Parameter Fallbacks**:
  - If `hw.logicalcpu` is missing or `<= 0`: default `logicalcpu = Runtime.getRuntime().availableProcessors()`.
  - If `hw.physicalcpu` is missing or `<= 0`: default `physicalcpu = logicalcpu`.
  - If `hw.packages` is missing or `<= 0`: default `packages = 1`.
- [ ] **P2 Fallback Integration**:
  - Construct fallback `TopologyInput("macos", logicalCpusList, cacheDomainsList)`.
  - Pass `TopologyInput` to `TopologyBootstrap.normalize()`.
  - `TopologyNormalizer` handles missing or synthesized cache domains (32 KiB L1D, 512 KiB L2, socket-local L3) and generates an immutable, valid `TopologyModel`.

## 5. Sizing & Split Gate Assessment

### Sizing Evaluation

1. **Context Load**: The implementation is strictly bounded to `MacosSystemLayout.java`, `sysctl.*` parsers, and `MacosTopologyFixtureTest.java`. The context covers sysctl key queries, Apple Silicon E/P core indexing, Intel SMT math, cache domain BitSet construction, and P2 `TopologyInput` integration. This fits comfortably within the working memory of a single implementation pass.
2. **Single Responsibility**: `MacosSystemLayout` owns macOS CPU topology parsing and sysctl discovery. Resource metrics (P7-B) and native Mach affinity/ABI (P7-C) are cleanly decoupled.
3. **Independent Validation**: macOS topology parsing can be fully validated using synthetic sysctl dictionary fixtures and unit tests without requiring direct macOS kernel execution during unit tests.

**Conclusion**: Child P7-A is irreducible, correctly sized, and ready for implementation in a single pass.

## 6. Implementation Model Reassessment

- **Required Capabilities**: Intricate sysctl key extraction, Apple Silicon E-core first indexing, Intel SMT hyperthreading math, L2/L3 cache BitSet assembly, and P2 fallback topology normalization integration.
- **Selected Model**: **`gpt-5.6-sol` with `high` reasoning effort**.
- **Justification**: Preserving strict macOS sysctl discovery contracts, Apple Silicon heterogeneous core mapping, Intel hyperthreading rules, and cache domain BitSet assembly across macOS 11+ systems requires high reasoning effort.

## 7. Developer-Review Summary

| Item | Details |
|---|---|
| **Purpose** | Deliver sysctl-backed macOS CPU topology parsing (`MacosSystemLayout`) supporting Apple Silicon P/E cores, Intel SMT hyperthreading, cache domain BitSet assembly, and P2 fallback topology model normalization. |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.macos.MacosSystemLayout` (Java), `io.euhedral_execution.hardware_utils.macos.sysctl.*` (Java Parsers). |
| **Key Invariants** | Bijective logical CPU mapping `0..N-1`; Apple Silicon E-cores indexed first (`0..E-1`) followed by P-cores (`E..E+P-1`); Intel SMT grouping `logicalcpu > physicalcpu`; L1 instruction cache excluded; missing sysctl keys trigger P2 fallback topology normalization. |
| **Child Action Items** | P7-A implementation: `hardware-utils-overhaul/phase-7-macos-topology-implementation`. |
| **Selected Model** | `gpt-5.6-sol` with `high` reasoning effort for implementation and conformance audit. |
| **Principal Risks** | Missing sysctl keys on older macOS versions; incorrect E-core / P-core index boundaries; divide-by-zero on physicalcpu; instruction cache inclusion. |
| **Unresolved Items** | None. sysctl keys, core classification indexing, SMT formulas, cache domain rules, and fallbacks are fully specified. |

## 8. Verification & Acceptance Criteria

### 8.1. Acceptance Criteria

1. **sysctl Key Discovery & Type-Safe Parsing**:
   - `SysctlInt`, `SysctlLong`, `SysctlString` correctly query sysctl keys and return type-safe optional values without throwing unchecked native exceptions.
2. **Apple Silicon Heterogeneous Core Classification**:
   - Given `hw.nperflevels >= 2`, logical CPUs `0 .. E_count - 1` are classified as `CoreKind.EFFICIENCY` and logical CPUs `E_count .. E_count + P_count - 1` as `CoreKind.PERFORMANCE`.
3. **Intel SMT Hyperthreading Discovery**:
   - Given `hw.nperflevels < 2` and `logicalcpu > physicalcpu`, logical CPUs are grouped into physical core buckets of size `logicalcpu / physicalcpu` with `CoreKind.UNKNOWN`.
4. **Cache Domain BitSet Assembly**:
   - L1 data cache domains created per physical core; L1 instruction cache excluded.
   - L2 cache domains assembled using `cpusperl2` cluster counts on Apple Silicon.
   - L3 cache domain assembled covering all logical CPUs if `hw.l3cachesize > 0`.
5. **Missing-Key Conservative Fallbacks**:
   - Missing or invalid sysctl keys return safe defaults (`Runtime.getRuntime().availableProcessors()`), delegating to `TopologyBootstrap.normalize()` for fallback topology synthesis.

### 8.2. Verification Commands

```bash
# Build hardware-utils module and run macOS topology fixture tests
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.macos.MacosTopologyFixtureTest"

# Run all hardware-utils tests
gradle :euhedral-hardware-utils:test
```

## 9. Completion Record

### Changed Files

- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosSystemLayout.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/sysctl/SysctlProvider.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/sysctl/SysctlNative.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/sysctl/SysctlInt.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/sysctl/SysctlLong.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/sysctl/SysctlString.java`
- `euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/macos/MacosTopologyFixtureTest.java`

### Commands Run & Results

- `mise exec -- gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.macos.MacosTopologyFixtureTest"` - Passed all 6 fixture tests.
- `mise exec -- gradle :euhedral-hardware-utils:test` - Passed all 154 hardware-utils unit tests cleanly.
- `mise exec -- gradle build` - Passed full repository build and test verification.

### Acceptance Evidence

- `MacosSystemLayout` queries public macOS sysctl keys (`hw.logicalcpu`, `hw.physicalcpu`, `hw.nperflevels`, `hw.perflevel*`, `hw.l1dcachesize`, `hw.l2cachesize`, `hw.l3cachesize`, `hw.cachelinesize`).
- Apple Silicon SoCs with `hw.nperflevels >= 2` correctly index E-cores (`0..E-1`) as `CoreKind.EFFICIENCY` and P-cores (`E..E+P-1`) as `CoreKind.PERFORMANCE`.
- Intel Macs with `hw.nperflevels < 2` compute SMT hyperthreading `logicalcpu > physicalcpu` and group logical CPUs bijectively into physical core buckets with `CoreKind.UNKNOWN`.
- Cache domain BitSet assembly constructs L1D core-local domains, L2 cluster domains based on `cpusperl2`, and socket-local L3 domain (if present), while strictly excluding L1 instruction cache (`hw.l1icachesize`).
- Missing sysctl keys default to `availableProcessors` and delegate to `TopologyBootstrap.normalize()` for fallback topology synthesis.
- JNI binary compatibility baseline maintained without adding unapproved native declarations.

### Approved Deviations

None.

### Environmental Limits

None.
