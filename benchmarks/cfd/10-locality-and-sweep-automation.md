# Phase 10 - Locality and sweep automation

Prerequisite: 09. Read the [master plan](README.md), current hardware/routing APIs in [REPOSITORY_MAP.md](REPOSITORY_MAP.md), and the benchmark identity/timing contract from phase 09.

## Deliverable

A bounded automated experiment runner that measures useful brick sizes and placement strategies, plus only those solver/adapter optimizations supported by real results.

## Changes

- Add `scripts/run-cfd-sweep.py` using the repository-compatible Python toolchain and standard library where sufficient. It builds once or accepts an already built distribution, enumerates a finite suite, runs child JVMs, records status, and invokes the existing comparison path.
- Default to one benchmark process at a time. Multiple full-machine simulations running together invalidate core/cache/bandwidth comparisons. Do not parallelize benchmark execution merely because the harness machine has spare logical threads.
- Support a dry-run showing exact commands and estimated memory, overall/per-process deadlines, graceful interruption with child cleanup, explicit fail-fast/continue behavior, and restart that skips only completed matching cases. Keep human-readable case configuration; do not create a checksum registry or job-orchestration service.
- Sweep a small declared set of brick shapes, such as `8x8x8`, `16x16x16`, `32x32x32`, and an X-contiguous rectangular shape. Include fewer/equal/more bricks than workers and partial-edge dimensions. Test source count only as a Euhedral adapter parameter.
- Add topology-aware initialization and optional Euhedral `SOCKET_LOCAL`/`CACHE_LOCAL` routing as separately labeled experiments. Set frame origins only while not in flight. Verify actual execution samples outside scored timing: direct pull/fallback behavior means origin and routing settings are not strict ownership guarantees.
- Provide comparable worker affinity and initialization policies for FJP/static baselines. Distinguish requested page placement from observed NUMA placement: on-heap allocation/zeroing, GC, and OS policy can defeat intended first touch. Do not claim NUMA gains from affinity configuration alone.
- Profile before optimizing. Candidate changes are boundary/interior kernel separation, precomputed constant neighbor offsets, fewer coordinate/modulo operations, bulk submissions, and less per-step bookkeeping. Apply numerical-kernel improvements to every backend. Preserve a scalar/reference path and full-field tests.
- Keep the static baseline competent: persistent workers, contiguous work, no unnecessary per-cell synchronization, and a documented partition rule. Include uniform and naturally irregular geometry cases instead of adding fake sleeps or synthetic CPU loops to force a scheduler win.
- Report wall time/MLUPS, fork variability, allocation/GC observations when available, and scaling plateaus. Regular LBM may be limited by memory bandwidth; describe measured evidence rather than assigning every plateau to scheduling.
- Add `RUNBOOK.md` with build, inspect, serial demo, STL demo, JMH smoke, and explicit larger sweep commands. Link it from the master plan after implementation. Store only small presets, not large datasets or generated VTI files.

## Acceptance

Unit-test matrix generation, matching/restart behavior, dry-run, error reporting, and child-process cleanup. Run a tiny two-configuration sweep; validate every numerical output before comparing it.

An optimization is retained only after equal-work/equal-result comparisons show its effect, including regressions. No particular backend is required to win, and no production policy change is authorized. Do not block completion on proving a universal performance claim.

```bash
python3 benchmarks/cfd/scripts/run-cfd-sweep.py --suite benchmarks/cfd/suites/smoke.json --dry-run
python3 benchmarks/cfd/scripts/run-cfd-sweep.py --suite benchmarks/cfd/suites/smoke.json
```

## Implementation prompt

```text
Implement benchmarks/cfd phase 10 only. Build a simple bounded sweep script around the existing JMH runner, then measure brick geometry, source count, and explicitly labeled placement variants. Run real benchmarks serially across configurations, preserve numerical equality and strong static/FJP baselines, and profile before changing the kernel. Apply shared numerical optimizations to all backends. Do not modify production policy, create a checksum system, or launch an unattended large campaign. Finish with runnable documentation and honest measured findings.
```
