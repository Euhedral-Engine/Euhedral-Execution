# Phase 7-A macOS Topology Model Conformance Audit

## Scope and disposition

Audited `hardware-utils-overhaul/phase-7-macos-topology-audit` from the P7 root branch `hardware-utils-overhaul/phase-7-macos`. The parent artifacts are the P7 parent blueprint (`docs/blueprints/hardware-utils/phase-7-macos-platform.md`) and the P7-A child blueprint (`docs/blueprints/hardware-utils/phase-7-macos-topology-model.md`).

Inspection was limited to `MacosSystemLayout.java`, `SysctlProvider.java`, `SysctlNative.java`, `SysctlInt.java`, `SysctlLong.java`, `SysctlString.java`, `MacosTopologyFixtureTest.java`, and integration contracts with `TopologyBootstrap` and `TopologyNormalizer` in `euhedral-hardware-utils`.

**Disposition: review-ready; P7-A child action complete.** All 5 acceptance criteria are classified as `satisfied`. No production or blueprint defects were found. P7-A is ready to be merged into the P7 root branch.

## Acceptance criteria matrix

| Acceptance criterion | Classification | Evidence |
|---|---|---|
| 1. sysctl Key Discovery & Type-Safe Parsing | satisfied | `SysctlInt`, `SysctlLong`, `SysctlString` query sysctl keys via `SysctlProvider` and return type-safe optionals (`OptionalInt`, `OptionalLong`, `Optional<String>`) without throwing unchecked native exceptions. Tested by `MacosTopologyFixtureTest.testTypeSafeSysctlParsers`. |
| 2. Apple Silicon Heterogeneous Core Classification | satisfied | `MacosSystemLayout` inspects `hw.nperflevels >= 2`. Logical CPUs `0 .. E_count - 1` are classified as `CoreKind.EFFICIENCY` and `E_count .. E_count + P_count - 1` as `CoreKind.PERFORMANCE`. Tested by `MacosTopologyFixtureTest.testAppleSiliconHeterogeneousCoreClassification` and `testAppleSiliconMultipleL2Clusters`. |
| 3. Intel SMT Hyperthreading Discovery | satisfied | `MacosSystemLayout` evaluates `hw.nperflevels < 2`. SMT hyperthreading computes `threadsPerCore = logicalcpu / physicalcpu` when `logicalcpu > physicalcpu`, grouping logical CPUs bijectively into physical core buckets with `CoreKind.UNKNOWN`. Tested by `MacosTopologyFixtureTest.testIntelSmtHyperthreadingDiscovery`. |
| 4. Cache Domain BitSet Assembly | satisfied | L1 data cache domains are constructed per physical core. L2 cache domains are assembled using `cpusperl2` cluster counts on Apple Silicon or system-wide on Intel. Socket-local L3 cache domain is constructed if `hw.l3cachesize > 0`. L1 instruction cache (`hw.l1icachesize`) is strictly excluded. Tested by `MacosTopologyFixtureTest.testAppleSiliconMultipleL2Clusters` and `testIntelSmtHyperthreadingDiscovery`. |
| 5. Missing-Key Conservative Fallbacks | satisfied | Missing, zero, or unreadable sysctl keys return safe defaults (`availableProcessors`) and delegate to `TopologyBootstrap.normalize()` to synthesize fallback topology models cleanly. Tested by `MacosTopologyFixtureTest.testMissingKeyConservativeFallback`. |

## Detailed independent audit

### 1. sysctl key discovery and type-safe parsing

macOS exposes hardware parameters via `sysctlbyname()`. `SysctlProvider` defines functional contracts for raw object, integer, long, and string queries. `SysctlInt`, `SysctlLong`, and `SysctlString` wrap queries with null-safe optional return types.

`SysctlNative` delegates sysctl calls to native `MacosSystemLayout` JNI bindings without adding unapproved native declarations, preserving the binary JNI compatibility baseline.

```java
OptionalInt intVal = SysctlInt.query(provider, "hw.logicalcpu");
OptionalLong longVal = SysctlLong.query(provider, "hw.memsize");
Optional<String> strVal = SysctlString.query(provider, "machdep.cpu.brand_string");
```

### 2. Apple Silicon heterogeneous core classification

Apple Silicon M-series SoCs expose performance levels (`hw.nperflevels >= 2`):
- `hw.perflevel0` represents P-cores (Performance cores).
- `hw.perflevel1` represents E-cores (Efficiency cores).

`MacosSystemLayout` assigns logical CPU indices `0 .. eCount - 1` to E-cores (`CoreKind.EFFICIENCY`) and `eCount .. eCount + pCount - 1` to P-cores (`CoreKind.PERFORMANCE`). `TopologyNormalizer` maps E-cores into `eCpuSet` and P-cores into `pCpuSet`.

### 3. Intel SMT hyperthreading discovery

Intel Macs expose `hw.nperflevels == 1` or missing `hw.nperflevels`. When `hw.logicalcpu > hw.physicalcpu`, SMT hyperthreading is present:
- `threadsPerCore = logicalcpu / physicalcpu`
- Logical CPU `i` belongs to physical core `i / threadsPerCore`.
- All cores are assigned `CoreKind.UNKNOWN`, which `TopologyNormalizer` maps to `pCpuSet` (homogeneous execution context).

### 4. Cache domain BitSet assembly

- **L1 Data Cache**: Core-local `CacheDomain(1, l1dSize, lineSize, bitsetCore)`. Instruction cache (`hw.l1icachesize`) is strictly excluded.
- **L2 Cache**: Assembled using `cpusperl2` cluster grouping (`hw.perflevel1.cpusperl2` for E-cores, `hw.perflevel0.cpusperl2` for P-cores) on Apple Silicon, or a single socket-wide BitSet on Intel.
- **L3 Cache**: Socket-wide `CacheDomain(3, l3Size, lineSize, bitsetAll)` constructed if `hw.l3cachesize > 0`.

### 5. Missing-key conservative fallback logic

If sysctl queries fail or return invalid parameters (`logicalcpu <= 0`), `MacosSystemLayout` falls back to `Runtime.getRuntime().availableProcessors()` and passes `TopologyInput` to `TopologyBootstrap.normalize()`, which synthesizes conservative P2 fallback cache domains (32 KiB L1D, 512 KiB L2, socket-local L3).

## Verification evidence

### Commands run and results

```bash
# Focused fixture test
mise exec -- gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.macos.MacosTopologyFixtureTest"
# Result: PASSED (6/6 tests)

# Full hardware-utils test suite
mise exec -- gradle :euhedral-hardware-utils:test
# Result: PASSED (154/154 tests)

# Full repository build
mise exec -- gradle build
# Result: BUILD SUCCESSFUL
```

### Environmental limits

None. All macOS sysctl interactions are tested via mock `SysctlProvider` fixtures and JNI fallbacks.
