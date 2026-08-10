# Phase 5-A Linux Topology & Sysfs Parsing Blueprint

## 1. Status and Authority

- **Parent Plan**: [
  `docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md)
- **Parent Blueprint**: [
  `docs/blueprints/hardware-utils/phase-5-linux-platform.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-5-linux-platform.md)
- **P5 Root Branch**: `hardware-utils-overhaul/phase-5-linux`
- **Child Blueprint Branch**: `hardware-utils-overhaul/phase-5-linux-topology-blueprint`
- **Child Implementation Branch**: `hardware-utils-overhaul/phase-5-linux-topology-implementation`
- **Audit File Target**: `docs/audits/hardware-utils/phase-5-linux-topology-model-conformance.md`
- **Owning Module**: `euhedral-hardware-utils`
- **Selected Blueprint Model**: `gpt-5.6-sol` with `high` reasoning effort
- **Status**: Implementation-ready child blueprint. Pending developer review and merge into the P5
  root before child implementation begins.

This child blueprint is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`,
`docs/ARCHITECTURE.md`, and the parent P5 blueprint (`phase-5-linux-platform.md`). It translates the
parent topology contracts into an explicit, implementable specification for `LinuxSystemLayout` and
sysfs parsing.

## 2. Objective & Core Defects Addressed

The objective of **Phase 5-A** is to deliver a complete, deterministic, sysfs-backed Linux CPU
topology provider (`LinuxSystemLayout`) that eliminates legacy defects and adheres strictly to the
P2 topology snapshot model.

### Core Defect Corrections

- **Defect T02 Correction (Multisocket, Multi-Die & Sparse Topology)**:
    - Eliminate assumptions of contiguous OS CPU IDs (`0..N-1`). Logical CPU IDs preserve their OS
      CPU IDs (e.g., CPU 0, 2, 8, 16).
    - Eliminate core ID collisions across physical sockets/dies by constructing compound tuple keys
      `(packageId, dieId, coreId)` for global core uniqueness.
    - Correctly map sparse CPU topologies with explicit `null` holes for offline or unmapped CPU IDs
      in indexed arrays.
- **Cache Extraction & P2 Fallbacks**:
    - Extract detailed L1 data, L1 instruction (ignored), L2, and L3 cache domains from
      `/sys/devices/system/cpu/cpuX/cache/indexY/`.
    - Fall back gracefully to P2's synthesized core-local L1/L2 and socket-local L3 cache domains
      when sysfs cache nodes are missing, incomplete, or unreadable.
- **P/E Core Classification**:
    - Classify hybrid architectures (e.g. Intel Alder Lake / Raptor Lake, ARM big.LITTLE) into
      `CoreKind.PERFORMANCE` vs `CoreKind.EFFICIENCY` using cpufreq max frequency and L1/L2 cache
      capacity scores.
    - Fall back to `CoreKind.UNKNOWN` when scores are homogeneous, incomplete, or unavailable.

## 3. Scope & Non-Goals

### 3.1. In Scope

- **Primary Source File**: [
  `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxSystemLayout.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxSystemLayout.java).
- **Directory Scanning**: Robust, bounded scanning of `/sys/devices/system/cpu/cpuX/`.
- **Attribute Parsing**:
    - `/sys/devices/system/cpu/cpuX/topology/physical_package_id`
    - `/sys/devices/system/cpu/cpuX/topology/die_id` (optional, default 0 if absent)
    - `/sys/devices/system/cpu/cpuX/topology/core_id`
    -
  `/sys/devices/system/cpu/cpuX/cache/indexY/{type, level, size, coherency_line_size, shared_cpu_map}`
    - `/sys/devices/system/cpu/cpuX/cpufreq/cpuinfo_max_freq`
- **Data Model Integration**: Translating raw sysfs observations into `TopologyInput`, containing
  `List<LogicalCpu>` and `List<CacheDomain>`, for normalization via `TopologyBootstrap.normalize()`.
- **Sparse OS CPU ID Mapping**: Supporting non-contiguous logical CPU IDs `[0, maxCpuId]` with
  explicit `null` holes in indexed lookups.
- **Global Core Uniqueness**: Compound keying to guarantee distinct core identities across packages
  and dies.
- **P/E Core Clustering**: Frequency and cache scoring algorithm with maximum gap boundary
  detection.
- **Testing & Fixtures**: Unit tests and sysfs directory fixtures in [
  `LinuxSystemLayoutFixtureTest.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/linux/LinuxSystemLayoutFixtureTest.java).

### 3.2. Non-Goals

- Modifying cgroup resource collection, `/proc/stat` parsing, or PSI metrics (owned by P5-B).
- Modifying JNI C++ code, direct Linux syscalls (`sched_setaffinity`), or affinity leases (owned by
  P5-C).
- Modifying the common `TopologyBootstrap` or `TopologyNormalizer` classes in `internal.topology`.
- Modifying core execution, fragment loops, or scheduling policies in `euhedral-core`.
- Any work involving `euhedral-training`.

## 4. Architectural Contracts & Implementation Checklist

```text
/sys/devices/system/cpu/
   +-- cpu0/
   |     +-- topology/{physical_package_id, die_id, core_id}
   |     +-- cache/index0..3/{type, level, size, coherency_line_size, shared_cpu_map}
   |     +-- cpufreq/cpuinfo_max_freq
   +-- cpu2/
   +-- cpu8/
         |
         v
  [LinuxSystemLayout.collect(cpuRoot)]
         |
         +--> Parse RawCpu (OS CPU ID, packageId, dieId, coreId, freqScore, cacheScore)
         +--> Parse CacheDomain (level, size, line, sharers BitSet)
         +--> CoreTuple(packageId, dieId, coreId) uniqueness check
         +--> CoreKind classification (PERFORMANCE / EFFICIENCY / UNKNOWN)
         |
         v
   TopologyInput("linux", List<LogicalCpu>, List<CacheDomain>)
         |
         v
   TopologyBootstrap.normalize(provider, availableProcessors, logger, "linux")
         |
         v
   TopologyModel (Immutable, Sparse OS CPU ID Maps, Null Holes, P2 Cache Fallbacks)
```

### 4.1. Checklist Item 1: Sysfs Directory Scanning & Directory Iteration

- [ ] **Directory Scanning**:
    - Read `cpuRoot` (`/sys/devices/system/cpu`).
    - Use `Files.list(cpuRoot)` wrapped in `try-with-resources` to ensure directory stream closure.
    - Filter directories matching regex `cpu\d+`.
    - Extract OS CPU ID `X` from directory name substring `cpuX` via
      `Integer.parseInt(name.substring(3))`.
    - Sort directories by ascending numeric OS CPU ID.
- [ ] **Missing / Unreadable sysfs Recovery**:
    - If `cpuRoot` does not exist, is unreadable, or contains no `cpu\d+` directories,
      `collect(cpuRoot)` must return an empty `TopologyInput("linux", List.of(), List.of())`.
    - `TopologyBootstrap.normalize()` will automatically apply the whole-model fallback
      (single-socket, single-core, single-CPU model with conservative L1/L2/L3).

### 4.2. Checklist Item 2: Topology Parsing & Compound Global Core Uniqueness

- [ ] **Sysfs Topology Attribute Parsing**:
    - For each `cpuX` directory, resolve `topology` subdirectory (`cpuX/topology/`).
    - Read `physical_package_id`: parse signed integer (default to 0 if file is missing, empty, or
      unreadable).
    - Read `core_id`: parse signed integer (default to 0 if file is missing, empty, or unreadable).
    - Read `die_id`: check if `die_id` file exists and is a regular file; parse signed integer if
      present, otherwise default to 0.
- [ ] **Compound Key & Identity Formatting**:
    - Construct `CoreTuple` record: `record CoreTuple(int packageId, int dieId, int coreId)`.
    - Construct canonical string identifiers for `LogicalCpu`:
        - `packageIdString` = `"linux:package:" + packageId`
        - `dieIdString` = `"linux:die:" + dieId`
        - `coreIdString` = `"linux:core:" + coreId` (or formatted compound string if required for
          global uniqueness across packages).
- [ ] **Global Core Uniqueness Guarantee**:
    - Ensure that two logical CPUs with the same `core_id` but different `packageId` or `dieId` are
      treated as belonging to distinct physical cores.
    - `TopologyNormalizer` maps logical CPUs sharing identical `(packageId, dieId, coreId)` tuples
      to the same `CoreInfo` instance, while distinct tuples map to separate `CoreInfo` instances.

### 4.3. Checklist Item 3: Cache Domain Extraction & P2 Cache Fallbacks

- [ ] **Sysfs Cache Attribute Parsing**:
    - For each `cpuX` directory, resolve `cache` subdirectory (`cpuX/cache/`).
    - If `cache` is not a directory or is unreadable, skip cache collection for this CPU.
    - Iterate over `index\d+` subdirectories in `cpuX/cache/`.
    - Read `type`: convert to lowercase. If `type.startsWith("instruction")`, skip (only data and
      unified caches are collected).
    - Read `level`: parse integer (1, 2, or 3).
    - Read `size`: parse size string with unit suffix (`K`/`k` for KiB, `M`/`m` for MiB, or plain
      bytes) into `long` byte count.
    - Read `coherency_line_size`: parse integer (typically 64).
    - Read `shared_cpu_map`: parse hex mask string using `MaskCodec.parse(hexString)` into a
      `BitSet` representing logical CPUs sharing this cache.
- [ ] **Cache Domain Record Generation**:
    - For each valid data/unified cache, construct
      `CacheDomain(level, sizeBytes, lineSizeBytes, sharersBitSet)`.
    - Append to target `List<CacheDomain>`.
- [ ] **P2 Cache Fallback Integration**:
    - If sysfs cache nodes are completely absent, partial, or malformed, pass whatever valid
      `CacheDomain` instances were collected (or an empty list) to `TopologyBootstrap.normalize()`.
    - `TopologyNormalizer` evaluates cache coverage per logical CPU:
        - If L1 data cache is missing for a CPU, it synthesizes a core-local 32 KiB L1 data cache.
        - If L2 cache is missing for a CPU, it synthesizes a core-local 512 KiB L2 cache.
        - If L3 cache is missing for a CPU, it synthesizes a socket-local L3 cache.
    - Provider code must NOT throw exceptions on missing cache sysfs files.

### 4.4. Checklist Item 4: P/E Core Classification Algorithm

- [ ] **Signal Collection**:
    - **Frequency Score**: Read `/sys/devices/system/cpu/cpuX/cpufreq/cpuinfo_max_freq` (in kHz). If
      valid and > 0, use as frequency score; otherwise -1.
    - **Cache Score**: Compute combined cache capacity per CPU:
      `cacheScore = saturatedAdd(L1_data * sharers, L2 / sharers)`
      If L1 <= 0 or L2 <= 0, cache score is -1.
- [ ] **Completeness Verification**:
    - Check if all active CPUs have valid frequency scores (`freqScore > 0`).
    - Check if all active CPUs have valid cache scores (`cacheScore > 0`).
    - If frequencies are complete across all CPUs, use frequency score for classification;
      otherwise, if caches are complete across all CPUs, use cache score.
    - If NEITHER frequency nor cache scores are 100% complete, or if total active cores < 2, return
      `CoreKind.UNKNOWN` for all cores.
- [ ] **Core-Level Score Aggregation**:
    - Map each `CoreTuple(packageId, dieId, coreId)` to its score.
    - Validate that sibling logical CPUs (hyperthreads) on the same core produce identical scores.
      If scores differ or are <= 0, mark classification incomplete and fall back to
      `CoreKind.UNKNOWN`.
- [ ] **Clustering by Maximum Score Gap**:
    - Sort distinct core scores in descending order: `S_0 >= S_1 >= ... >= S_{n-1}`.
    - Compute adjacent gaps: `G_i = S_i - S_{i+1}` for `i` in `[0, n-2]`.
    - Find maximum gap `G_max = max(G_i)`.
    - If `G_max > 0`:
        - Cores with score `> S_{boundary+1}` (index `<= boundary`) are classified as
          `CoreKind.PERFORMANCE`.
        - Cores with score `<= S_{boundary+1}` (index `> boundary`) are classified as
          `CoreKind.EFFICIENCY`.
    - If `G_max == 0` (all cores have identical scores), classify all cores as `CoreKind.UNKNOWN`
      (homogeneous topology).

### 4.5. Checklist Item 5: Sparse OS CPU ID Mapping & Null Holes

- [ ] **Logical CPU Assignment**:
    - Construct `LogicalCpu` for each active OS CPU `X`:
      `new LogicalCpu(cpuId, packageIdString, dieIdString, coreIdString, coreKind)`
    - Logical CPU ID `cpuId` MUST equal the sysfs OS CPU ID `X` (e.g. 0, 2, 8, 16).
- [ ] **TopologyModel Null-Hole Invariant**:
    - `TopologyBootstrap.normalize()` receives the `TopologyInput` and builds indexed lookup
      structures.
    - `getCpuInfoMap()` returns a map keyed by OS CPU ID `X`.
    - Public array projections of length `maxCpuId + 1` contain valid `CpuInfo` objects at active
      indices (0, 2, 8, 16) and `null` at offline or unmapped indices (1, 3..7, 9..15).
    - `TopologyMapper` and downstream consumers handle `null` holes safely without
      `NullPointerException`.

### 4.6. Checklist Item 6: Channel Safety, Bounded Reads & Error Isolation

- [ ] **Resource Cleanup**:
    - All file reads MUST use `try-with-resources` or `Files.readString(path)`.
    - Directory iteration streams (`Files.list()`) MUST be closed via `try-with-resources`.
- [ ] **Exception Handling**:
    - Parsing helper methods (`parseSigned`, `parseCpu`, `toBytes`) MUST handle
      `NumberFormatException` cleanly.
    - Individual CPU or cache read failures MUST NOT crash the entire topology collector; unreadable
      optional attributes fall back cleanly to defaults or P2 normalizer fallbacks.

## 5. Sizing & Split Gate Assessment

### Sizing Evaluation

1. **Context Load**: The implementation is strictly bounded to `LinuxSystemLayout.java` and its
   associated unit test file `LinuxSystemLayoutFixtureTest.java`. The total context required
   involves sysfs file scanning, tuple keying, cache domain extraction, P/E score math, and P2
   `TopologyInput` integration. This comfortably fits within the working memory of a single
   implementation pass.
2. **Single Responsibility**: `LinuxSystemLayout` owns Linux CPU topology discovery and sysfs
   parsing. Resource collection (P5-B) and native syscalls (P5-C) are cleanly separated.
3. **Independent Validation**: Linux topology parsing can be fully validated using synthetic sysfs
   file trees, in-memory `TopologyInput` lambdas, and fixture tests without requiring a live Linux
   kernel or native C++ libraries.

**Conclusion**: Child P5-A is irreducible, correctly sized, and ready for implementation in a single
pass.

## 6. Implementation Model Reassessment

- **Required Capabilities**: Intricate sparse array indexing, compound tuple key uniqueness, cache
  domain parsing, P/E core gap math, and JMM/P2 topology model normalization integration.
- **Selected Model**: **`gpt-5.6-sol` with `high` reasoning effort**.
- **Justification**: Preserving strict topology contracts, memory safety, null-hole invariants, and
  accurate P/E core gap clustering across diverse Linux architectures (x86-64, AArch64) requires
  high reasoning effort.

## 7. Developer-Review Summary

| Item                   | Details                                                                                                                                                                                                                                                                                                   |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Purpose**            | Implement sysfs-backed Linux CPU topology parsing (`LinuxSystemLayout`) with sparse OS CPU ID support, compound global core uniqueness, cache domain extraction, P2 cache fallbacks, and cpufreq/cache P/E core classification.                                                                           |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.linux.LinuxSystemLayout` (Java), `io.euhedral_execution.hardware_utils.internal.topology.*` (Integration).                                                                                                                                                          |
| **Key Invariants**     | Logical CPU IDs preserve OS CPU IDs; sparse arrays contain explicit `null` holes for offline CPUs; global cores identified by `(packageId, dieId, coreId)`; missing cache nodes trigger P2 fallbacks; P/E classification uses score gap clustering; file channels strictly closed via try-with-resources. |
| **Child Action Items** | P5-A implementation: `hardware-utils-overhaul/phase-5-linux-topology-implementation`.                                                                                                                                                                                                                     |
| **Selected Model**     | `gpt-5.6-sol` with `high` reasoning effort for implementation and conformance audit.                                                                                                                                                                                                                      |
| **Principal Risks**    | Non-contiguous OS CPU IDs causing array out-of-bounds; duplicate `core_id` across sockets causing core aliasing; missing sysfs cache files causing crash; unhandled `NumberFormatException` on malformed sysfs values.                                                                                    |
| **Unresolved Items**   | None. All sysfs paths, parsing algorithms, fallbacks, tuple keys, and classification rules are fully specified.                                                                                                                                                                                           |

## 8. Verification & Acceptance Criteria

### 8.1. Acceptance Criteria

1. **Sparse Topology & Null Holes**:
    - Given non-contiguous OS CPU IDs (e.g. 0, 2, 8, 10, 16), `LinuxSystemLayout` maps logical CPUs
      to exact OS IDs.
    - `layout.getCpuInfoMap().keySet()` contains exactly `{0, 2, 8, 10, 16}`.
    - Unmapped indices (e.g., 1, 3) return `null`.
2. **Compound Core Uniqueness**:
    - Given multi-socket or multi-die configurations where `core_id = 0` appears on socket 0 and
      socket 1, `LinuxSystemLayout` creates distinct `CoreInfo` instances for each physical
      package/die combination.
3. **Cache Extraction & Fallbacks**:
    - Given sysfs cache index directories, `LinuxSystemLayout` extracts `CacheDomain` objects with
      correct level, size in bytes, line size, and shared CPU `BitSet`.
    - Given missing sysfs cache directories, `LinuxSystemLayout` completes without error, and
      `TopologyBootstrap.normalize()` synthesizes L1/L2/L3 fallbacks.
4. **P/E Core Classification**:
    - Given distinct cpufreq max frequencies or cache scores across cores, `LinuxSystemLayout`
      identifies the largest score gap and classifies higher-scoring cores as `PERFORMANCE` and
      lower-scoring cores as `EFFICIENCY`.
    - Given homogeneous scores or incomplete score coverage, all cores are classified as `UNKNOWN`.
5. **Channel Safety & Build Verification**:
    - Clean execution of all hardware-utils unit tests without file descriptor leaks or exceptions.

### 8.2. Verification Commands

```bash
# Build hardware-utils module and run topology fixture tests
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.linux.LinuxSystemLayoutFixtureTest"

# Run all hardware-utils tests
gradle :euhedral-hardware-utils:test
```

## 9. Completion Record

### Changed Files

-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/linux/LinuxSystemLayout.java`
-
`euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/linux/LinuxSystemLayoutFixtureTest.java`
- `docs/audits/hardware-utils/phase-5-linux-topology-model-conformance.md`

### Commands Run & Results

-
`gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.linux.LinuxSystemLayoutFixtureTest"` -
Passed 6/6 tests cleanly.
- `gradle :euhedral-hardware-utils:test` - Passed all 67 hardware-utils tests cleanly.
- `gradle :euhedral-hardware-utils:test --rerun-tasks` - Passed all 67 hardware-utils tests cleanly
  with fresh execution.
- `gradle :euhedral-hardware-utils:build` - Build successful, native packaging, test compilation,
  and verification passed.

### Acceptance Evidence

- `LinuxSystemLayout` correctly scans sysfs `/sys/devices/system/cpu/` with directory stream closure
  via try-with-resources.
- OS CPU IDs are preserved directly in logical CPU IDs, mapping sparse CPU sets with explicit null
  holes for unmapped indices.
- Multi-socket/multi-die topologies generate unique global core tuples `(packageId, dieId, coreId)`
  preventing core aliasing across packages.
- Cache domains are extracted from sysfs cache index nodes and fall back cleanly to P2 synthesized
  L1/L2/L3 domains if sysfs cache entries are missing or unreadable.
- P/E core gap classification correctly identifies performance vs efficiency cores from cpufreq max
  frequency or cache capacity scores, falling back to `CoreKind.UNKNOWN` when scores are
  homogeneous.
- Missing or unreadable sysfs root directories fall back cleanly to the conservative whole-model
  fallback topology without throwing exceptions.
- Independent conformance audit completed in
  `docs/audits/hardware-utils/phase-5-linux-topology-model-conformance.md` verifying all 5
  acceptance criteria satisfied with 0 deviations.

### Approved Deviations

None.

### Environmental Limits

None.
