# Phase 11 - Locality and sweep automation

Dependencies: [10](10-jmh-and-comparison-runner.md). Placement APIs: [REPOSITORY_MAP.md](REPOSITORY_MAP.md).

## Feature

A bounded experiment runner compares brick geometry, worker placement, source count, and scaling while retaining the same validated numerical workload.

## Components

`scripts/run-cfd-sweep.py` builds or selects the application distribution, expands a finite configuration matrix, executes child JVMs sequentially, and combines comparison reports. Dry-run output lists commands and estimated memory. Per-process and overall deadlines, interruption handling, child cleanup, restart matching, and fail-fast/continue modes describe run lifecycle.

The initial matrix includes `8x8x8`, `16x16x16`, `32x32x32`, and X-contiguous rectangular bricks,
plus partial-edge domains and different ratios of bricks to workers. Euhedral source count is the
dispatch parameter implemented in [Phase 08](08-parallel-execution-backends.md). The sweep includes
explicit `1` and `workers` variants, retaining the resolved count in every result. Multiple sinks
receive plain round-robin submissions from one driver; Euhedral manages source acquisition and
internal distribution. Source count does not imply a worker assignment or locality policy.

Placement variants include topology-aware initialization and Euhedral `SOCKET_LOCAL`/`CACHE_LOCAL` routing with origins assigned while frames are out of flight. Execution samples outside scored timing distinguish requested placement from observed behavior, including direct-pull and fallback paths. FJP and static baselines expose corresponding affinity and initialization settings.

NUMA reports distinguish thread placement, initialization policy, and observed page placement. On-heap allocation, zeroing, garbage collection, and operating-system behavior are recorded as factors affecting placement interpretation.

Profiling identifies costs in boundary/interior processing, neighbor addressing, coordinate operations, dispatch, and per-step coordination. Shared kernel variants expose the same arithmetic to all backends. Private scalar/reference tests and the external comparison suite cover numerical changes.

Cases include uniform flow and naturally irregular geometry. Static workers retain persistent contiguous partitions; ForkJoinPool retains bulk decomposition. Each report records its baseline partitioning and worker configuration.

## Results and verification

Sweep results combine wall time, MLUPS, fork variability, allocation/GC observations where available, validation status, and scaling behavior. Every scored variant has current backend-equivalence evidence and applicable external-reference coverage. Bandwidth measurements and profiling accompany interpretations of scaling plateaus.

Tests cover matrix generation, restart matching, dry-run, deadlines, child cleanup, and failed cases. A small two-configuration sweep exercises the complete path. Numerical or execution regressions remain visible in the comparison.

`RUNBOOK.md` describes build, inspection, simulation, STL input, OpenLB validation, JMH suites, and larger sweep configurations. Small presets define campaigns; generated field datasets and run artifacts occupy their configured output directories.

## Interface

```bash
python3 benchmarks/cfd/scripts/run-cfd-sweep.py --suite benchmarks/cfd/suites/smoke.json --dry-run
python3 benchmarks/cfd/scripts/run-cfd-sweep.py --suite benchmarks/cfd/suites/smoke.json
```
