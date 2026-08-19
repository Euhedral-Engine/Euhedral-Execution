# Phase 1 Calibration Findings: Physical Body Landmarks

This document records the empirical results, supporting data, and calibrated boundaries for **Phase
1 (Choose Physical Body Landmarks)** of
the [Euhedral Tuning Process](../../EUHEDRAL_TUNING_PROCESS.md#4-phase-1---choose-physical-body-landmarks).

---

## 1. Overview and Calibration Fixture

Phase 1 establishes the physical workload anchors that define the body cost continuum independent of
contention. By isolating body execution under low contention, we identify:

1. **Minimum Body Reference (XS Landmark)**: The smallest non-zero decision weight that completely
   classifies a zero-work (`workUnits = 0`) frame within the `XS` band without false escalation to
   higher bands.
2. **Upper Body Landmark (XH Landmark)**: The physical workload (`workUnits`) where the throughput
   advantage of `DIRECT` execution collapses and converges with `STAGED` execution.
3. **Intermediate Body Bands (S, M, H)**: The behavioral continuum between `XS` and `XH` to be
   empirically established in subsequent calibration steps.

### Calibration Fixture Topology

- **Host Hardware**: Linux x86_64, pinned CPU affinity.
- **CPU Set**: `[2, 3]` (isolated 2-core fixture on Socket 0).
- **Ingest Sources**: 2 parallel sources (`parallelSources = 2`), 0 ordered sources
  (`orderedSources = 0`).
- **Invocation Scale**: `1,000,000` required frame executions per JMH invocation
  (`invocationTimeoutMillis = 30,000`).
- **Measurement Config**: 1 fork, 2 warmups (3s each), 5 measurement iterations (3s each).
- **Idling Behavior**: Disabled (`idleTimeNs = 0`) to prevent scheduler sleep artifacts from
  distorting body cost distributions.

---

## 2. Minimum Body Reference (XS Landmark)

### Experiment Configuration

- **Experiment Preset**: [
  `experiments/00-xs-body-boundary.json`](../experiments/00-xs-body-boundary.json)
- **Comparison Preset**: [
  `comparisons/00-xs-body-boundary.json`](../comparisons/00-xs-body-boundary.json)
- **Workload**: Fixed `workUnits = 0` (no-op frame body).
- **Execution Policy**: Forced `DIRECT` across all 25 grid cells.
- **Sweep Design**: Collapsed threshold sweep where $\text{xs} = \text{s} = \text{m} = \text{h} = W$
  for $W \in [512, 256, 128, 96, 64]$. When all thresholds are collapsed to $W$, any body
  observation exceeding the calibrated threshold $T (W)$ escalates immediately from Band 0 (`XS`) to
  Band 4 (`XH`).

### Empirical Occupancy Distribution

| Collapsed Weight ($W$) | Trial Identifier                       | Band 0 (`XS`) % | Band 1 (`S`) % | Band 2 (`M`) % | Band 3 (`H`) % | Band 4 (`XH`) % | Classification Regime     |
|:----------------------:|:---------------------------------------|:---------------:|:--------------:|:--------------:|:--------------:|:---------------:|:--------------------------|
|        **512**         | `xs-body-boundary__body-descending__0` |   **100.00%**   |     0.00%      |     0.00%      |     0.00%      |      0.00%      | Full envelope             |
|        **256**         | `xs-body-boundary__body-descending__1` |   **100.00%**   |     0.00%      |     0.00%      |     0.00%      |      0.00%      | Full envelope             |
|        **128**         | `xs-body-boundary__body-descending__2` |   **100.00%**   |     0.00%      |     0.00%      |     0.00%      |      0.00%      | Full envelope             |
|         **96**         | `xs-body-boundary__body-descending__3` |   **95.58%**    |     0.03%      |     0.00%      |     0.00%      |      4.39%      | Practical boundary anchor |
|         **64**         | `xs-body-boundary__body-descending__4` |    **1.60%**    |     0.13%      |     0.00%      |     0.00%      |   **98.27%**    | Below minimum body cost   |

### Analysis

- At $W = 64$, the micro-calibrated threshold $T (64) \approx 24\text{ ns}$ falls below the base
  engine dispatch overhead of the frame body execution path. As a result, 98.27% of executions
  exceed $T (64)$ and are classified as `XH`.
- At $W = 96$, 95.58% of executions are captured in `XS`, with only 4.39% tail noise spilling into
  `XH`.
- At $W \ge 128$, 100.00% of executions are captured in `XS`.
- **XS Landmark Anchor**: The practical minimum reference weight for `XS` is established at
  **$96 \sim 128$** for `workUnits = 0`.

---

## 3. Upper Body Landmark (XH Landmark)

### Experiment Configuration

- **Experiment Preset**: [
  `experiments/01-xh-body-boundary.json`](../experiments/01-xh-body-boundary.json)
- **Comparison Preset**: [
  `comparisons/01-xh-body-boundary.json`](../comparisons/01-xh-body-boundary.json)
- **Comparison Strategy**: `KEYED` matching baseline (`body-direct`) and candidate (`body-staged`)
  runs along `/calibrationConfig/workUnits`.
- **Sweep Design**: Matched sweeps of forced `DIRECT` and forced `STAGED` across
  `workUnits` $\in [0, 48, 96, 144, 192, 216, 240, 288]$.

### Empirical Comparison Data

From [
`experiments/01-xh-body-boundary/comparisons/comparison_summary.tsv`](file:///home/brandon/src/Euhedral-Execution/experiments/01-xh-body-boundary/comparisons/comparison_summary.tsv):

| `workUnits` | Key Index | Baseline (`DIRECT`) Mean (ops/s) | Candidate (`STAGED`) Mean (ops/s) | Absolute Delta (ops/s) | Relative Delta (%) | Observable Behavior                            |
|:-----------:|:---------:|:--------------------------------:|:---------------------------------:|:----------------------:|:------------------:|:-----------------------------------------------|
|    **0**    |     0     |           $16,569,409$           |           $16,864,684$            |       $+295,275$       |     $+1.78\%$      | `STAGED` +1.78%                                |
|   **48**    |     1     |           $8,870,603$            |            $8,848,298$            |       $-22,304$        |     $-0.25\%$      | Approximately equivalent (`DIRECT` +0.25%)     |
|   **96**    |     2     |           $6,398,421$            |            $6,327,793$            |       $-70,627$        |     $-1.10\%$      | `DIRECT` +1.10%                                |
|   **144**   |     3     |           $5,469,963$            |            $5,102,749$            |       $-367,214$       |     $-6.71\%$      | `DIRECT` +6.71% (meaningful advantage)         |
|   **192**   |     4     |           $4,191,074$            |            $4,170,728$            |       $-20,346$        |     $-0.48\%$      | Approximately equivalent (`DIRECT` +0.48%)     |
|   **216**   |   **5**   |         **$3,795,960$**          |          **$3,797,178$**          |      **$+1,218$**      |   **$+0.032\%$**   | **Exact Throughput Convergence (XH Landmark)** |
|   **240**   |     6     |           $3,466,308$            |            $3,741,493$            |       $+275,185$       |     $+7.94\%$      | `STAGED` +7.94% (favorable beyond crossover)   |
|   **288**   |     7     |           $3,048,365$            |            $3,081,484$            |       $+33,119$        |     $+1.09\%$      | `STAGED` +1.09%                                |

### Analysis

The empirical throughput relationship between `DIRECT` and `STAGED` execution across `workUnits`
demonstrates:

1. **Sub-216 Region**: `DIRECT` execution exhibits a meaningful throughput advantage through parts
   of the sub-216 region, reaching its strongest advantage around `workUnits = 144` (+6.71% over
   `STAGED`). At low-to-intermediate loads (`workUnits = 48` and `workUnits = 96`), `DIRECT`
   provides a slight edge or near-parity.
2. **Advantage Collapse & Convergence**: As body work increases further toward `workUnits = 192`,
   the `DIRECT` advantage shrinks to +0.48% (effectively equivalent), and completely collapses at **
   `workUnits = 216`**, where `DIRECT` ($3,795,960\text{ ops/s}$) and `STAGED`
   ($3,797,178\text{ ops/s}$) reach exact throughput convergence ($\Delta = +0.032\%$).
3. **Post-Crossover Regime**: Beyond `workUnits = 216`, the execution-path overhead is entirely
   dominated by frame body computation, and `STAGED` execution becomes favorable (+7.94% at
   `workUnits = 240`, +1.09% at `workUnits = 288`) by decoupling upstream demand signaling from
   frame consumption.
4. **Physical XH Landmark Anchor**: The physical upper body landmark is anchored at **
   `workUnits = 216`**.

---

## 4. Physical Body Landmarks and Remaining Calibration Scope

### Established Physical Landmarks from Phase 1

Phase 1 has directly and empirically established two physical body anchors:

- **XS Physical Floor**: `workUnits = 0` (practical portable weight
  reference $\approx 96 \sim 128$).
- **XH Physical Landmark**: `workUnits \approx 216` (the point of exact throughput convergence
  between `DIRECT` and `STAGED`).

### Scope for Subsequent Calibration

The intermediate **S**, **M**, and **H** physical boundaries have **not yet been empirically
calibrated**. Rather than assuming arbitrary equidistant points, intermediate bands must be
systematically partitioned and mapped in subsequent calibration steps.

The remaining work following Phase 1 consists of:

1. **Phase 2 Weight Mapping for XH**: Determine the portable decision weight that maps to the
   empirical `workUnits = 216` physical XH landmark.
2. **Intermediate S/M/H Landmark Calibration**: Empirically establish and justify the physical
   workload landmarks for `S`, `M`, and `H` (e.g. by observing shift points in execution cost
   distributions or queue residency behavior), and determine their corresponding decision weights.

---

## 5. Artifact and Configuration Traceability

| Artifact Description             | File Path                                                                                                                                                                              |
|:---------------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **XS Experiment Preset**         | [`benchmarks/src/main/presets/experiments/00-xs-body-boundary.json`](../experiments/00-xs-body-boundary.json)                                                                          |
| **XS Comparison Preset**         | [`benchmarks/src/main/presets/comparisons/00-xs-body-boundary.json`](../comparisons/00-xs-body-boundary.json)                                                                          |
| **XH Experiment Preset**         | [`benchmarks/src/main/presets/experiments/01-xh-body-boundary.json`](../experiments/01-xh-body-boundary.json)                                                                          |
| **XH Comparison Preset**         | [`benchmarks/src/main/presets/comparisons/01-xh-body-boundary.json`](../comparisons/01-xh-body-boundary.json)                                                                          |
| **Baseline Profile Library**     | [`benchmarks/src/main/presets/profiles/baseline.json`](../profiles/baseline.json)                                                                                                      |
| **XH Benchmark Comparison Data** | [`experiments/01-xh-body-boundary/comparisons/comparison_summary.tsv`](file:///home/brandon/src/Euhedral-Execution/experiments/01-xh-body-boundary/comparisons/comparison_summary.tsv) |
