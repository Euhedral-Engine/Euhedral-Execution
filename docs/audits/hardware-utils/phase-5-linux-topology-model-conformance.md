# Phase 5-A Linux Topology Model Conformance Audit

## Scope and disposition

Audited `hardware-utils-overhaul/phase-5-linux-topology-audit` from the updated P5 root branch
`hardware-utils-overhaul/phase-5-linux` (at commit `acbcc6d`). The parent artifacts are the P5
parent blueprint (`docs/blueprints/hardware-utils/phase-5-linux-platform.md`) and the P5-A child
blueprint (`docs/blueprints/hardware-utils/phase-5-linux-topology-model.md`).

Inspection was limited to `LinuxSystemLayout.java`, `LinuxSystemLayoutFixtureTest.java`, and
integration contracts with `TopologyBootstrap` and `TopologyNormalizer` in
`euhedral-hardware-utils`.

**Disposition: review-ready; P5-A child action complete.** All 5 acceptance criteria are classified
as `satisfied`. No production or blueprint defects were found. P5-A is ready to be merged into the
P5 root branch.

## Acceptance criteria matrix

| Acceptance criterion                   | Classification | Evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
|----------------------------------------|----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1. Sparse Topology & Null Holes        | satisfied      | `LinuxSystemLayout` preserves raw OS CPU IDs `X` directly in `LogicalCpu`. `TopologyBootstrap.normalize()` maps active CPU IDs into `getCpuInfoMap()`. Unmapped indices in the range `[0, maxCpuId]` return explicit `null` holes without causing array out-of-bounds or NPEs. Verified by `LinuxSystemLayoutFixtureTest.normalizesSparseMultisocketTopology` and `scansSysfsFilesystemAndParsesSparseTopology`.                                                                                                                                                                                       |
| 2. Compound Core Uniqueness            | satisfied      | `LinuxSystemLayout` constructs canonical identifiers `"linux:package:" + packageId`, `"linux:die:" + dieId`, `"linux:core:" + coreId`. `TopologyNormalizer` keys core identity by `CoreKey(socketIndex, dieKey, coreKey)`, ensuring distinct `CoreInfo` instances across sockets/dies even when `core_id` is identical (e.g. core 0 on package 0 vs core 0 on package 1). Verified by `LinuxSystemLayoutFixtureTest.scansSysfsFilesystemAndParsesSparseTopology`.                                                                                                                                      |
| 3. Cache Extraction & Fallbacks        | satisfied      | sysfs `cpuX/cache/indexY` nodes are parsed for `type`, `level`, `size`, `coherency_line_size`, and `shared_cpu_map`. Instruction caches are skipped. Missing, unreadable, or partial sysfs cache entries trigger P2 cache fallback logic in `TopologyNormalizer` (synthesizing 32 KiB L1 data, 512 KiB L2, and socket-local L3 domains). Verified by `LinuxSystemLayoutFixtureTest.handlesMissingOrUnreadableCpuRootGracefully`.                                                                                                                                                                       |
| 4. P/E Core Classification             | satisfied      | `classify()` scores cores using cpufreq `cpuinfo_max_freq` or combined L1/L2 cache capacity. Cores are clustered by maximum score gap ($G_{max}$). Higher-scoring cores map to `CoreKind.PERFORMANCE` and lower-scoring cores map to `CoreKind.EFFICIENCY`. Homogeneous scores, incomplete scores, or single-core systems default cleanly to `CoreKind.UNKNOWN`. Verified by `LinuxSystemLayoutFixtureTest.classifiesHybridPerformanceAndEfficiencyCoresFromCpufreq`, `classifiesHybridCoresFromCacheCapacityScoresWhenFreqUnavailable`, and `fallsBackToUnknownWhenScoresAreHomogeneousOrIncomplete`. |
| 5. Channel Safety & Build Verification | satisfied      | All directory iterations (`Files.list()`) use `try-with-resources` to prevent file descriptor leaks. Optional sysfs attribute parsing errors are caught cleanly without crashing collection. All 67 `euhedral-hardware-utils` tests pass cleanly in Gradle build.                                                                                                                                                                                                                                                                                                                                      |

## Detailed independent audit

### 1. Sparse CPU handling and null holes

Linux sysfs exposes active CPUs under `/sys/devices/system/cpu/cpuX`. On systems with offline CPUs,
NUMA nodes, or sparse CPU assignments (e.g., CPU 0, 2, 8, 10, 16), OS CPU IDs are non-contiguous.

`LinuxSystemLayout.collect()` parses `cpuX` directory names, extracts the integer `X`, and assigns
`cpu.id = X` to `LogicalCpu`. `TopologyNormalizer` maintains `activeIds` and constructs array and
map projections. Indexed lookups contain valid `CpuInfo` objects for active IDs and explicit `null`
holes for unmapped IDs in the `[0, maxCpuId]` range.

```java
// Verified sparse key set ordering and null hole behavior
assertArrayEquals(new Integer[] {
    0, 2, 8, 10, 16
},layout.

getCpuInfoMap().

keySet().

toArray(Integer[]::new));

assertNull(layout.getCpuInfoMap().

get(1));
```

### 2. Global core uniqueness across sockets and dies

In multisocket or multi-die Linux systems, physical cores on different packages often share the same
`core_id` attribute (e.g., `package 0, core 0` vs `package 1, core 0`). Reusing `core_id` alone
causes core aliasing.

`LinuxSystemLayout` formats package, die, and core identifiers as `"linux:package:" + packageId`,
`"linux:die:" + dieId`, and `"linux:core:" + coreId`. `TopologyNormalizer` combines these into a
`CoreKey(socketIndex, dieKey, coreKey)` tuple. As a result, identical `core_id` values on distinct
sockets or dies map to distinct `CoreInfo` instances.

### 3. Cache domain extraction and P2 fallbacks

`LinuxSystemLayout.collectCaches()` scans `/sys/devices/system/cpu/cpuX/cache/indexY/`:

- Skips instruction caches (`type.startsWith("instruction")`).
- Extracts data and unified caches with `level`, `size` (parsed from KiB/MiB strings),
  `coherency_line_size`, and `shared_cpu_map` (parsed via `MaskCodec.parse()`).
- In missing, incomplete, or unreadable sysfs cache scenarios, `collectCaches()` handles
  `IOException` and `NumberFormatException` cleanly, returning valid cache entries (or an empty
  list).
- `TopologyBootstrap.normalize()` evaluates cache coverage and automatically synthesizes core-local
  L1/L2 and socket-local L3 fallbacks.

### 4. P/E core classification algorithm

Hybrid processor architectures require identifying performance (P) vs efficiency (E) cores:

- `frequencyScore`: Read from `/sys/devices/system/cpu/cpuX/cpufreq/cpuinfo_max_freq` (in kHz).
- `cacheScore`: Saturated calculation of L1 data and L2 cache capacity per core.
- **Completeness Check**: Frequency scores take priority if 100% complete across active CPUs.
  Otherwise, cache scores take priority if 100% complete. If neither signal is 100% complete, or if
  total active cores < 2, all cores default to `CoreKind.UNKNOWN`.
- **Sibling Consistency**: Hyperthreads sharing a core tuple `(packageId, dieId, coreId)` must have
  identical scores. If sibling scores disagree or are invalid (`<= 0`), classification falls back to
  `CoreKind.UNKNOWN`.
- **Maximum Score Gap Clustering**: Distinct core scores are sorted descending
  ($S_0 \ge S_1 \ge \dots \ge S_{n-1}$). The adjacent score gap $G_i = S_i - S_{i+1}$ is computed.
  If $G_{max} > 0$, cores at or above the gap boundary are marked `PERFORMANCE`, and cores below are
  marked `EFFICIENCY`. Homogeneous scores ($G_{max} == 0$) fall back to `CoreKind.UNKNOWN`.

### 5. Resource cleanup and channel safety

- `Files.list(cpuRoot)` and `Files.list(root)` are wrapped in `try-with-resources` blocks to ensure
  filesystem directory handles are closed promptly.
- `Files.readString(path)` is used for bounded single-file reads.
- Malformed attribute values or missing sysfs entries return conservative default values (0 for
  package/die/core IDs, -1 for frequencies/caches) without propagating exceptions.

## Verification evidence

### Commands run and results

```bash
# Focused fixture test
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.linux.LinuxSystemLayoutFixtureTest"
# Result: PASSED (6/6 tests)

# Full hardware-utils test suite with task rerun
gradle :euhedral-hardware-utils:test --rerun-tasks
# Result: PASSED (67/67 tests, 10 actionable tasks executed in 8s)

# Full module build
gradle :euhedral-hardware-utils:build
# Result: BUILD SUCCESSFUL
```

### Environmental limits

None. All sysfs interactions are exercised via synthetic filesystem directory trees (`@TempDir`) and
functional `TopologyProvider` lambdas.
