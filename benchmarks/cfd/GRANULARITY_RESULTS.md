# CFD lazy-source granularity check - 2026-09-14

Historical measurements of allocation-on-miss sources, before full frame preallocation. See
[PREALLOCATION_RESULTS.md](PREALLOCATION_RESULTS.md) for the subsequent same-brick comparison.

Correctness: 281 CFD unit tests, 16 integration tests and two Python sweep tests passed, with no skips. Integration enabled the pinned OpenLB installation and VTK reader. All 36 small-sweep forks and both million-range forks verified complete fields against the matching FJP reference. External representative periodic-shear coverage passed; it is not direct OpenLB validation of every larger domain.

Host: Intel(R) Core(TM) i9-14900K; Java 21.0.2. Affinity enabled. The small sweep used four physical workers and four Euhedral sources. The large check used all 23 available workers and sources. No core policy or physics changes were made.

## Six-size smoke sweep

32^3 cells, five timesteps per invocation, two independent JVM forks, one warmup and two measurement iterations (100 ms requested per iteration). JMH average time excludes reset, reference comparison, and final VTI export. The 32^3 brick has only one logical range on this small grid. These short results locate behavior worth testing; they do not establish the best brick size for the stock 256^3 workload.

| Brick | Ranges/step | Backend | Sources | Mean step (ms) | MLUPS | Fork CV | Fork means (ms/invocation) | Fork processes total (s) | Allocation/invocation (MiB), forks | GC count, forks |
|---|---:|---|---:|---:|---:|---:|---|---:|---|---|
| 32x32x32 | 1 | euhedral | 4 | 5.509 | 5.95 | 9.7% | 25.661, 29.426 | 2.16 | 0.392, 0.383 | 0, 0 |
| 32x32x32 | 1 | fjp | 0 | 4.926 | 6.65 | 0.5% | 24.536, 24.721 | 2.04 | 0.157, 0.157 | 0, 0 |
| 32x32x32 | 1 | static | 0 | 5.133 | 6.38 | 5.4% | 26.652, 24.676 | 2.09 | 0.157, 0.157 | 0, 0 |
| 16x16x16 | 8 | euhedral | 4 | 1.938 | 16.91 | 0.3% | 9.666, 9.713 | 2.18 | 0.289, 0.289 | 0, 0 |
| 16x16x16 | 8 | fjp | 0 | 2.119 | 15.47 | 3.2% | 10.832, 10.355 | 2.01 | 0.137, 0.137 | 0, 0 |
| 16x16x16 | 8 | static | 0 | 2.040 | 16.07 | 10.1% | 10.925, 9.472 | 2.01 | 0.137, 0.137 | 0, 0 |
| 8x8x8 | 64 | euhedral | 4 | 2.008 | 16.32 | 4.1% | 10.328, 9.752 | 2.19 | 0.301, 0.314 | 0, 0 |
| 8x8x8 | 64 | fjp | 0 | 1.931 | 16.97 | 10.8% | 10.394, 8.915 | 2.05 | 0.137, 0.137 | 0, 0 |
| 8x8x8 | 64 | static | 0 | 1.800 | 18.20 | 0.8% | 8.949, 9.055 | 1.96 | 0.137, 0.137 | 0, 0 |
| 4x4x4 | 512 | euhedral | 4 | 1.824 | 17.97 | 2.4% | 9.273, 8.963 | 2.15 | 0.296, 0.298 | 0, 0 |
| 4x4x4 | 512 | fjp | 0 | 1.941 | 16.88 | 3.6% | 9.948, 9.459 | 2.05 | 0.138, 0.138 | 0, 0 |
| 4x4x4 | 512 | static | 0 | 1.899 | 17.26 | 2.4% | 9.655, 9.330 | 2.01 | 0.137, 0.137 | 0, 0 |
| 2x2x2 | 4,096 | euhedral | 4 | 2.381 | 13.76 | 2.6% | 12.119, 11.688 | 2.21 | 0.459, 0.422 | 0, 0 |
| 2x2x2 | 4,096 | fjp | 0 | 2.140 | 15.31 | 6.4% | 10.216, 11.184 | 1.97 | 0.138, 0.137 | 0, 0 |
| 2x2x2 | 4,096 | static | 0 | 1.987 | 16.49 | 5.0% | 10.288, 9.582 | 2.05 | 0.137, 0.137 | 0, 0 |
| 1x1x1 | 32,768 | euhedral | 4 | 6.601 | 4.96 | 0.6% | 32.863, 33.142 | 2.25 | 0.675, 0.763 | 0, 0 |
| 1x1x1 | 32,768 | fjp | 0 | 4.222 | 7.76 | 2.3% | 21.456, 20.768 | 2.05 | 0.157, 0.157 | 0, 0 |
| 1x1x1 | 32,768 | static | 0 | 2.732 | 11.99 | 11.4% | 14.764, 12.555 | 2.11 | 0.157, 0.137 | 0, 0 |

Total elapsed across the six suites: 44.67 seconds, including validation, reference generation, JVM setup, all variants, and export. This is not the JMH score.

The lowest observed timestep times were at 4^3 for Euhedral and 8^3 for FJP/static. The nearby sizes need longer runs to distinguish reliably. One-cell bricks were slower: Euhedral 6.601 ms/step, FJP 4.222 ms/step, static 2.732 ms/step. Euhedral created 7,998 and 7,270 frames over each complete one-cell fork rather than retaining a 32,768-element frame plan. Frame creation occurs only on manager misses; this observation is workload dependent.

## Million-range check

128^3 cells, 1^3 bricks, one timestep per invocation, two forks, one warmup and two measurement iterations. Each fork executed three generations, or 6,291,456 logical ranges. All 23 workers were available, covering this host's P/E topology; neither utilization nor per-core tail time was instrumented.

Mean timestep: **404.179 ms**, **5.189 MLUPS**. Fork CV: **14.55%**; variance: 0.003460400 s^2. Total suite duration: 19.11 seconds.

| Fork | JMH timestep (ms) | Created frames across three generations | Allocation/invocation (MiB) | GC count / time (ms) | VTI export (s), outside timing |
|---|---:|---:|---:|---:|---:|
| 0 | 362.583 | 5,396,452 | 617.74 | 6 / 64 | 0.592 |
| 1 | 445.774 | 5,419,116 | 590.18 | 11 / 164 | 0.450 |

The driver performs O(source count) publication and never visits logical ranges before workers start. It still performs the required deterministic numeric reduction after completion. The large run confirms execution of millions of lazy ranges, but also demonstrates considerable allocation churn with the bounded recycler and runtime demand. There is no evidence here that one-cell granularity resolves P-core starvation or is preferable for sustained workloads. Frame creation counts are cumulative, not peak live-frame counts.

JMH GC profiler allocation includes invocation fixtures such as reset and full-field comparison; it is not a kernel-only measurement. Intermediate FieldExtractor scans were disabled in every benchmark. Final completed-state diagnostics and full-field checks remained enabled. No CPU utilization or per-core tail measurements were collected. No 256^3 / 1000-step campaign or before/after performance comparison was run.

## Reproduction and local evidence

```bash
OPENLB_HOME="$HOME/.local/opt/euhedral-openlb-1.9.0" mise exec -- \
  benchmarks/cfd/build/bin/euhedral-cfd-sweep \
  --suite benchmarks/cfd/suites/normal.json --short --workers 4

OPENLB_HOME="$HOME/.local/opt/euhedral-openlb-1.9.0" mise exec -- \
  benchmarks/cfd/build/bin/euhedral-cfd-sweep \
  --suite benchmarks/cfd/suites/normal.json --short --grid 128 --steps 1 \
  --sizes 1 --heap-gib 4 --variants euhedral-workers
```

Raw evidence is retained locally under `build/granularity/sweep-plqdn9wb/` and `build/granularity/sweep-s_obxb5y/`. Each contains `sweep.csv`, `sweep.json`, per-size suites, raw JMH results, trial metadata, validation reports and VTI files. Build outputs are not tracked in Git. Earlier exploratory outputs are also preserved. The tables above use the final source implementation and completed reporting runs.

Measured JAR SHA-256: `75e306d0a379dfec58a8f97047fbbb5e92fea962b0569933941805a27a3a3eaa`.
