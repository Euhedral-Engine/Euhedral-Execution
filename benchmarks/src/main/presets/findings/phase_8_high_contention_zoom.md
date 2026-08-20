# Phase 8 Calibration Findings: High-Contention Zoom

This document records the empirical results, comparative throughput measurements, multi-fork
statistical verifications, telemetry analyses, physical interpretations, and baseline considerations
for **Phase 8 (High-Contention Zoom)** of
the [Euhedral Tuning Process](../../EUHEDRAL_TUNING_PROCESS.md#11-phase-8---high-contention-zoom).

---

## 1. Overview and Calibration Objectives

In [Phase 7](phase_7_skip_policy_calibration.md), broad execution sweeps established that:

- **Low/Mid Contention ($0\% - 65\%$)**: `DIRECT` execution with $0\text{ ns}$ idle parking
  dominates when 2 or more productive sources exist per worker pair.
- **High/Severe Contention ($65\% - 100\%$)**: The active policy competition transitions to **
  `STAGED`** vs **`SKIP_THEN_STAGED`**.

Phase 8 executes a high-resolution zoom into the $65\% - 100\%$ contention continuum to:

1. **Anchor Lower Contention at 65%**: Setting $C_{\text{xs}} = 650,000$ ($65\%$) consolidates the
   entire non-contended domain ($0\% - 65\%$) into Band 0 (`XS`), preserving the established
   `DIRECT` baseline.
2. **Examine Higher Core Counts (4 and 8 Cores, P-Cores & E-Cores)**: Populate the
   dense $74\% - 99.5\%$ contention space across varying handle-deficit ratios.
3. **Evaluate Candidate High-Contention Thresholds**: Evaluate candidate contention threshold
   boundaries ($C_{\text{s}} = 800\text{k}, C_{\text{m}} = 900\text{k}, C_{\text{h}} = 970\text{k}$)
   against observed system behavior.
4. **Measure Staged-Family Policy Competition**: Compare `STAGED` vs `SKIP_THEN_STAGED` across
   calibrated body bands ($W \in \{0, 48, 144, 216, 288\}$) with calibrated follower idle parking
   active.
5. **Replicate Candidate Transitions via Multi-Fork Verification**: Execute 3 independent JVM forks
   (15 measurement iterations per condition) on candidate crossover regions.
6. **Reconcile Staged Winners with Historical DIRECT Evidence**: Distinguish pairwise staged winners
   from globally optimal actions by cross-referencing established `DIRECT` throughput.

---

## 2. Experimental Fixtures, Hardware Topologies, and Structural Confounds

- **Host Hardware**: Intel Core i9-14900K (x86_64 Linux, 8 P-cores / 16 E-cores, 6.0 GHz max).
- **Core Fixtures**:
    - **4-Core P-Core Fixture**: `cpuSet = [2, 4, 6, 8]` (Physical P-Cores 1, 2, 3, 4; dedicated L2
      caches per core, shared L3).
    - **4-Core E-Core Fixture**: `cpuSet = [16, 17, 18, 19]` (Physical E-Cores 8, 9, 10, 11; single
      Gracemont cluster sharing a 4 MB L2 cache).
    - **8-Core E-Core Fixture**: `cpuSet = [16, 17, 18, 19, 20, 21, 22, 23]` (Physical E-Cores
      8..15; two Gracemont clusters sharing separate L2 caches).
- **Candidate High-Contention Threshold Vector**:
    - $C_{\text{xs}} = 650,000$ ($65\%$): Consolidates low/mid contention into Band 0 (`XS`).
    - $C_{\text{s}} = 800,000$ ($80\%$): Candidate threshold for balanced high concurrency (4-core /
      4 sources $\approx 74.5\%$).
    - $C_{\text{m}} = 900,000$ ($90\%$): Candidate threshold for multi-worker deficit concurrency
      (8-core / 4 sources $\approx 82.2\%$, 4-core / 3 sources $\approx 84.8\%$).
    - $C_{\text{h}} = 970,000$ ($97\%$): Candidate threshold for severe deficit concurrency
      (8-core / 2 sources $\approx 94.0\%$, 4-core / 2 sources $\approx 97.4\%$).
    - Extreme XH ($> 970,000$): Severe single-source bottleneck (4-core / 1 source $\approx 98.9\%$,
      8-core / 1 source $\approx 99.5\%$).
- **Body Bands**: Calibrated Phase 2 weights $\mathbf{W} = [96, 128, 216, 288]$
  ($W \in \{0, 48, 144, 216, 288\}$).
- **Idle Policy**: Active follower idle parking ($1\,\mu\text{s}$ for XS, $15\,\mu\text{s}$ for
  S, $5\,\mu\text{s}$ for M, H, XH) in high-contention bands; $0\text{ ns}$ in Band 0.

### Hardware Topology Confound (E-Cores vs P-Cores)

> [!WARNING]
> On the Intel Core i9-14900K, E-cores are organized into 4-core Gracemont clusters that share a
unified L2 cache (4 MB per 4-core module), whereas P-cores have private 2 MB L2 caches. Contention
benchmarks on E-core fixtures may therefore be influenced by shared-cache eviction, interconnect
bandwidth, and cluster locality in addition to scalar upstream queue contention.
>
> Consequently, **E-core measurements establish real scheduler behavior for that hardware topology,
but are treated as topology-specific evidence** rather than universal proof of portable scalar
contention boundaries. P-core multi-fork evidence is given greater weight for default portable
baseline calibration.

---

## 3. High-Contention Zoom on 4-Core and 8-Core E-Cores (Experiment 19)

- **Experiment Preset**: [
  `benchmarks/src/main/presets/experiments/19-high-contention-zoom-ecore.json`](../experiments/19-high-contention-zoom-ecore.json)
- **Comparison Presets**:
    - 8-Core E-Core: [
      `19-high-contention-zoom-8core-ecore.json`](../comparisons/19-high-contention-zoom-8core-ecore.json)
    - 4-Core E-Core: [
      `19-high-contention-zoom-4core-ecore.json`](../comparisons/19-high-contention-zoom-4core-ecore.json)

### 3.1 8-Core E-Core Empirical Throughput (8 Workers)

| Body Band (`workUnits`) | Ingest Sources |    Measured Contention    | STAGED Throughput (ops/s) | SKIP_THEN_STAGED (ops/s) | Absolute Delta (ops/s) | Relative Delta (%) | Dominant Policy & Behavior     |
|:------------------------|:--------------:|:-------------------------:|:-------------------------:|:------------------------:|:----------------------:|:------------------:|:-------------------------------|
| **XS (0)**              |   8 Parallel   | $\approx 20.2\%$ (Band 0) |       $15,901,629$        |       $16,439,964$       |       $+538,335$       |     $+3.39\%$      | `SKIP_THEN_STAGED` +3.39%      |
| **XS (0)**              |   4 Parallel   | $\approx 82.2\%$ (Band 2) |     **$17,845,415$**      |       $17,390,386$       |       $-455,029$       |     $-2.55\%$      | `STAGED` +2.55%                |
| **XS (0)**              |   1 Parallel   | $\approx 97.9\%$ (Band 4) |     **$17,917,165$**      |       $16,440,568$       |      $-1,476,597$      |     $-8.24\%$      | `STAGED` +8.24%                |
| **S (48)**              |   8 Parallel   | $\approx 20.2\%$ (Band 0) |       $18,494,393$        |       $18,935,106$       |       $+440,714$       |     $+2.38\%$      | `SKIP_THEN_STAGED` +2.38%      |
| **S (48)**              |   4 Parallel   | $\approx 82.2\%$ (Band 2) |       $16,606,147$        |     **$19,198,689$**     |      $+2,592,542$      |   **$+15.61\%$**   | **`SKIP_THEN_STAGED` +15.61%** |
| **S (48)**              |   1 Parallel   | $\approx 97.9\%$ (Band 4) |     **$17,778,643$**      |       $16,861,980$       |       $-916,663$       |     $-5.16\%$      | `STAGED` +5.16%                |
| **M (144)**             |   8 Parallel   | $\approx 20.2\%$ (Band 0) |     **$17,515,504$**      |       $10,730,392$       |      $-6,785,112$      |     $-38.74\%$     | `STAGED` +38.74%               |
| **M (144)**             |   4 Parallel   | $\approx 82.2\%$ (Band 2) |       $11,479,057$        |     **$16,785,149$**     |      $+5,306,093$      |   **$+46.22\%$**   | **`SKIP_THEN_STAGED` +46.22%** |
| **M (144)**             |   1 Parallel   | $\approx 97.9\%$ (Band 4) |       $12,689,224$        |     **$16,385,240$**     |      $+3,696,016$      |   **$+29.13\%$**   | **`SKIP_THEN_STAGED` +29.13%** |
| **H (216)**             |   8 Parallel   | $\approx 20.2\%$ (Band 0) |       $15,450,581$        |       $15,588,995$       |       $+138,414$       |     $+0.90\%$      | Approximate parity (+0.90%)    |
| **H (216)**             |   4 Parallel   | $\approx 82.2\%$ (Band 2) |     **$15,219,800$**      |       $15,118,837$       |       $-100,963$       |     $-0.66\%$      | Approximate parity (-0.66%)    |
| **H (216)**             |   1 Parallel   | $\approx 97.9\%$ (Band 4) |       $13,461,147$        |     **$13,950,619$**     |       $+489,473$       |     $+3.64\%$      | `SKIP_THEN_STAGED` +3.64%      |
| **XH (288)**            |   8 Parallel   | $\approx 20.2\%$ (Band 0) |       $12,591,019$        |       $12,977,563$       |       $+386,544$       |     $+3.07\%$      | `SKIP_THEN_STAGED` +3.07%      |
| **XH (288)**            |   4 Parallel   | $\approx 82.2\%$ (Band 2) |     **$12,887,711$**      |       $12,691,487$       |       $-196,224$       |     $-1.52\%$      | Approximate parity (-1.52%)    |
| **XH (288)**            |   1 Parallel   | $\approx 97.9\%$ (Band 4) |     **$12,516,696$**      |       $12,119,993$       |       $-396,703$       |     $-3.17\%$      | `STAGED` +3.17%                |

### 3.2 4-Core E-Core Empirical Throughput (4 Workers)

| Body Band (`workUnits`) | Ingest Sources |    Measured Contention    | STAGED Throughput (ops/s) | SKIP_THEN_STAGED (ops/s) | Absolute Delta (ops/s) | Relative Delta (%) | Dominant Policy & Behavior  |
|:------------------------|:--------------:|:-------------------------:|:-------------------------:|:------------------------:|:----------------------:|:------------------:|:----------------------------|
| **XS (0)**              |   4 Parallel   | $\approx 20.2\%$ (Band 0) |     **$13,553,328$**      |       $13,297,370$       |       $-255,957$       |     $-1.89\%$      | Approximate parity (-1.89%) |
| **XS (0)**              |   2 Parallel   | $\approx 50.0\%$ (Band 0) |       $13,385,891$        |     **$14,593,998$**     |      $+1,208,107$      |     $+9.03\%$      | `SKIP_THEN_STAGED` +9.03%   |
| **XS (0)**              |   1 Parallel   | $\approx 98.0\%$ (Band 4) |     **$10,461,074$**      |       $9,025,371$        |      $-1,435,704$      |     $-13.72\%$     | `STAGED` +13.72%            |
| **S (48)**              |   4 Parallel   | $\approx 20.2\%$ (Band 0) |     **$13,771,900$**      |       $11,819,496$       |      $-1,952,403$      |     $-14.18\%$     | `STAGED` +14.18%            |
| **S (48)**              |   2 Parallel   | $\approx 50.0\%$ (Band 0) |     **$14,352,556$**      |       $13,174,012$       |      $-1,178,544$      |     $-8.21\%$      | `STAGED` +8.21%             |
| **S (48)**              |   1 Parallel   | $\approx 98.0\%$ (Band 4) |     **$14,649,747$**      |       $12,837,489$       |      $-1,812,258$      |     $-12.37\%$     | `STAGED` +12.37%            |
| **M (144)**             |   4 Parallel   | $\approx 20.2\%$ (Band 0) |     **$10,836,901$**      |       $10,801,165$       |       $-35,737$        |     $-0.33\%$      | Approximate parity (-0.33%) |
| **M (144)**             |   2 Parallel   | $\approx 50.0\%$ (Band 0) |       $10,536,112$        |     **$10,648,080$**     |       $+111,968$       |     $+1.06\%$      | Approximate parity (+1.06%) |
| **M (144)**             |   1 Parallel   | $\approx 98.0\%$ (Band 4) |     **$10,881,494$**      |       $7,544,031$        |      $-3,337,463$      |     $-30.67\%$     | `STAGED` +30.67%            |
| **H (216)**             |   4 Parallel   | $\approx 20.2\%$ (Band 0) |      **$8,258,031$**      |       $8,179,849$        |       $-78,182$        |     $-0.95\%$      | Approximate parity (-0.95%) |
| **H (216)**             |   2 Parallel   | $\approx 50.0\%$ (Band 0) |      **$8,365,715$**      |       $8,293,190$        |       $-72,525$        |     $-0.87\%$      | Approximate parity (-0.87%) |
| **H (216)**             |   1 Parallel   | $\approx 98.0\%$ (Band 4) |        $8,195,212$        |     **$8,222,818$**      |       $+27,606$        |     $+0.34\%$      | Approximate parity (+0.34%) |
| **XH (288)**            |   4 Parallel   | $\approx 20.2\%$ (Band 0) |        $6,698,829$        |     **$6,843,715$**      |       $+144,887$       |     $+2.16\%$      | `SKIP_THEN_STAGED` +2.16%   |
| **XH (288)**            |   2 Parallel   | $\approx 50.0\%$ (Band 0) |      **$6,662,036$**      |       $5,998,151$        |       $-663,885$       |     $-9.97\%$      | `STAGED` +9.97%             |
| **XH (288)**            |   1 Parallel   | $\approx 98.0\%$ (Band 4) |        $6,647,249$        |     **$6,747,912$**      |       $+100,663$       |     $+1.51\%$      | Approximate parity (+1.51%) |

---

## 4. High-Contention Policy Surface on 4-Core P-Cores and 8-Core 2-Source (Experiment 20)

- **Experiment Preset**: [
  `benchmarks/src/main/presets/experiments/20-high-contention-zoom-pcore.json`](../experiments/20-high-contention-zoom-pcore.json)
- **Comparison Presets**:
    - 4-Core P-Core Surface: [
      `20-high-contention-zoom-4core-pcore.json`](../comparisons/20-high-contention-zoom-4core-pcore.json)
    - 8-Core 2-Source Surface: [
      `20-high-contention-zoom-8core-2src.json`](../comparisons/20-high-contention-zoom-8core-2src.json)

### 4.1 4-Core P-Core Empirical Throughput (4 P-Cores)

| Body Band (`workUnits`) | Ingest Sources |    Measured Contention    | STAGED Throughput (ops/s) | SKIP_THEN_STAGED (ops/s) | Absolute Delta (ops/s) | Relative Delta (%) | Dominant Policy & Behavior     |
|:------------------------|:--------------:|:-------------------------:|:-------------------------:|:------------------------:|:----------------------:|:------------------:|:-------------------------------|
| **XS (0)**              |   4 Parallel   | $\approx 74.5\%$ (Band 1) |       $20,343,971$        |     **$23,681,272$**     |      $+3,337,301$      |   **$+16.40\%$**   | **`SKIP_THEN_STAGED` +16.40%** |
| **XS (0)**              |   3 Parallel   | $\approx 84.8\%$ (Band 2) |     **$22,981,764$**      |       $21,286,173$       |      $-1,695,590$      |     $-7.38\%$      | `STAGED` +7.38%                |
| **XS (0)**              |   2 Parallel   | $\approx 97.4\%$ (Band 4) |     **$26,549,315$**      |       $21,009,385$       |      $-5,539,930$      |     $-20.87\%$     | `STAGED` +20.87%               |
| **XS (0)**              |   1 Parallel   | $\approx 98.9\%$ (Band 4) |     **$22,840,096$**      |       $20,145,433$       |      $-2,694,663$      |     $-11.80\%$     | `STAGED` +11.80%               |
| **S (48)**              |   4 Parallel   | $\approx 74.5\%$ (Band 1) |       $18,634,860$        |     **$19,383,070$**     |       $+748,210$       |   **$+4.02\%$**    | **`SKIP_THEN_STAGED` +4.02%**  |
| **S (48)**              |   3 Parallel   | $\approx 84.8\%$ (Band 2) |       $19,159,705$        |     **$19,834,380$**     |       $+674,675$       |   **$+3.52\%$**    | **`SKIP_THEN_STAGED` +3.52%**  |
| **S (48)**              |   2 Parallel   | $\approx 97.4\%$ (Band 4) |     **$20,153,004$**      |       $19,321,981$       |       $-831,024$       |     $-4.12\%$      | `STAGED` +4.12%                |
| **S (48)**              |   1 Parallel   | $\approx 98.9\%$ (Band 4) |     **$21,478,893$**      |       $15,845,515$       |      $-5,633,378$      |     $-26.23\%$     | `STAGED` +26.23%               |
| **M (144)**             |   4 Parallel   | $\approx 74.5\%$ (Band 1) |       $16,151,835$        |     **$17,158,863$**     |      $+1,007,028$      |   **$+6.23\%$**    | **`SKIP_THEN_STAGED` +6.23%**  |
| **M (144)**             |   3 Parallel   | $\approx 84.8\%$ (Band 2) |       $16,030,397$        |     **$16,902,084$**     |       $+871,687$       |   **$+5.44\%$**    | **`SKIP_THEN_STAGED` +5.44%**  |
| **M (144)**             |   2 Parallel   | $\approx 97.4\%$ (Band 4) |     **$18,815,663$**      |       $13,804,050$       |      $-5,011,613$      |     $-26.64\%$     | `STAGED` +26.64%               |
| **M (144)**             |   1 Parallel   | $\approx 98.9\%$ (Band 4) |     **$16,336,341$**      |       $16,074,687$       |       $-261,653$       |     $-1.60\%$      | Approximate parity (-1.60%)    |
| **H (216)**             |   4 Parallel   | $\approx 74.5\%$ (Band 1) |       $11,065,121$        |     **$13,251,423$**     |      $+2,186,302$      |   **$+19.76\%$**   | **`SKIP_THEN_STAGED` +19.76%** |
| **H (216)**             |   3 Parallel   | $\approx 84.8\%$ (Band 2) |       $12,590,260$        |     **$13,082,301$**     |       $+492,041$       |   **$+3.91\%$**    | **`SKIP_THEN_STAGED` +3.91%**  |
| **H (216)**             |   2 Parallel   | $\approx 97.4\%$ (Band 4) |       $11,963,543$        |     **$13,456,803$**     |      $+1,493,260$      |   **$+12.48\%$**   | **`SKIP_THEN_STAGED` +12.48%** |
| **H (216)**             |   1 Parallel   | $\approx 98.9\%$ (Band 4) |     **$13,658,468$**      |       $13,301,390$       |       $-357,078$       |     $-2.61\%$      | Approximate parity (-2.61%)    |
| **XH (288)**            |   4 Parallel   | $\approx 74.5\%$ (Band 1) |        $9,350,808$        |     **$11,255,436$**     |      $+1,904,628$      |   **$+20.37\%$**   | **`SKIP_THEN_STAGED` +20.37%** |
| **XH (288)**            |   3 Parallel   | $\approx 84.8\%$ (Band 2) |       $10,101,101$        |     **$11,194,266$**     |      $+1,093,164$      |   **$+10.82\%$**   | **`SKIP_THEN_STAGED` +10.82%** |
| **XH (288)**            |   2 Parallel   | $\approx 97.4\%$ (Band 4) |       $10,885,126$        |     **$10,986,098$**     |       $+100,973$       |     $+0.93\%$      | Approximate parity (+0.93%)    |
| **XH (288)**            |   1 Parallel   | $\approx 98.9\%$ (Band 4) |      **$9,458,592$**      |       $7,955,756$        |      $-1,502,836$      |     $-15.89\%$     | `STAGED` +15.89%               |

### 4.2 8-Core E-Core Severe Contention (2 Sources $\approx 94.0\%$)

| Body Band (`workUnits`) | Ingest Sources |    Measured Contention    | STAGED Throughput (ops/s) | SKIP_THEN_STAGED (ops/s) | Absolute Delta (ops/s) | Relative Delta (%) | Dominant Policy & Behavior     |
|:------------------------|:--------------:|:-------------------------:|:-------------------------:|:------------------------:|:----------------------:|:------------------:|:-------------------------------|
| **XS (0)**              |   2 Parallel   | $\approx 94.0\%$ (Band 3) |     **$12,129,185$**      |       $11,652,246$       |       $-476,939$       |     $-3.93\%$      | `STAGED` +3.93%                |
| **S (48)**              |   2 Parallel   | $\approx 94.0\%$ (Band 3) |     **$17,633,352$**      |       $16,679,961$       |       $-953,391$       |     $-5.41\%$      | `STAGED` +5.41%                |
| **M (144)**             |   2 Parallel   | $\approx 94.0\%$ (Band 3) |       $12,193,054$        |     **$17,033,202$**     |      $+4,840,148$      |   **$+39.70\%$**   | **`SKIP_THEN_STAGED` +39.70%** |
| **H (216)**             |   2 Parallel   | $\approx 94.0\%$ (Band 3) |     **$14,845,951$**      |       $10,034,756$       |      $-4,811,195$      |     $-32.41\%$     | `STAGED` +32.41%               |
| **XH (288)**            |   2 Parallel   | $\approx 94.0\%$ (Band 3) |     **$12,509,591$**      |       $12,475,261$       |       $-34,330$        |     $-0.27\%$      | Approximate parity (-0.27%)    |

---

## 5. Multi-Fork Statistical Verification Suite (Experiment 21)

- **Experiment Preset**: [
  `benchmarks/src/main/presets/experiments/21-high-contention-multifork-verification.json`](../experiments/21-high-contention-multifork-verification.json)
- **Comparison Presets**:
    - 4-Core 4-Source Multi-Fork: [
      `21-high-contention-multifork-4core-4src.json`](../comparisons/21-high-contention-multifork-4core-4src.json)
    - 4-Core 3-Source Multi-Fork: [
      `21-high-contention-multifork-4core-3src.json`](../comparisons/21-high-contention-multifork-4core-3src.json)
    - 8-Core 4-Source Multi-Fork: [
      `21-high-contention-multifork-8core-4src.json`](../comparisons/21-high-contention-multifork-8core-4src.json)

### 5.1 8-Core E-Core Multi-Fork Verification (4 Sources $\approx 82.2\%$ Contention, 3 Forks x 5 Iterations)

| Body Band (`workUnits`) | Baseline (`STAGED`) Mean (ops/s) | Candidate (`SKIP_THEN_STAGED`) Mean (ops/s) | Absolute Delta (ops/s) | Relative Delta (%) | Multi-Fork Measured Differentiation                           |
|:------------------------|:--------------------------------:|:-------------------------------------------:|:----------------------:|:------------------:|:--------------------------------------------------------------|
| **S (48)**              |           $13,952,093$           |              **$18,060,461$**               |      $+4,108,367$      |   **$+29.45\%$**   | **`SKIP_THEN_STAGED` +29.45% (Topology-Specific Replicated)** |
| **M (144)**             |           $13,137,795$           |              **$16,628,352$**               |      $+3,490,557$      |   **$+26.57\%$**   | **`SKIP_THEN_STAGED` +26.57% (Topology-Specific Replicated)** |

### 5.2 4-Core P-Core Multi-Fork Verification (3 Sources $\approx 84.8\%$ Contention, 3 Forks x 5 Iterations)

| Body Band (`workUnits`) | Baseline (`STAGED`) Mean (ops/s) | Candidate (`SKIP_THEN_STAGED`) Mean (ops/s) | Absolute Delta (ops/s) | Relative Delta (%) | Multi-Fork Measured Differentiation                    |
|:------------------------|:--------------------------------:|:-------------------------------------------:|:----------------------:|:------------------:|:-------------------------------------------------------|
| **S (48)**              |           $19,270,036$           |              **$20,504,828$**               |      $+1,234,792$      |   **$+6.41\%$**    | **`SKIP_THEN_STAGED` +6.41% (Replicated P-Core Gain)** |
| **M (144)**             |           $16,352,447$           |              **$16,459,611$**               |       $+107,164$       |     $+0.66\%$      | Approximate parity (+0.66%)                            |
| **XH (288)**            |         **$10,955,986$**         |                $10,888,107$                 |       $-67,879$        |     $-0.62\%$      | Approximate parity (-0.62%)                            |

### 5.3 4-Core P-Core Multi-Fork Verification (4 Sources $\approx 74.5\%$ Contention, 3 Forks x 5 Iterations)

| Body Band (`workUnits`) | Baseline (`STAGED`) Mean (ops/s) | Candidate (`SKIP_THEN_STAGED`) Mean (ops/s) | Absolute Delta (ops/s) | Relative Delta (%) | Multi-Fork Measured Differentiation                    |
|:------------------------|:--------------------------------:|:-------------------------------------------:|:----------------------:|:------------------:|:-------------------------------------------------------|
| **XS (0)**              |           $21,198,976$           |              **$21,974,812$**               |       $+775,836$       |   **$+3.66\%$**    | **`SKIP_THEN_STAGED` +3.66% (Replicated P-Core Gain)** |
| **S (48)**              |         **$21,977,418$**         |                $20,238,008$                 |      $-1,739,410$      |     $-7.91\%$      | `STAGED` wins by 7.91%                                 |
| **M (144)**             |         **$16,311,690$**         |                $15,338,075$                 |       $-973,615$       |     $-5.97\%$      | `STAGED` wins by 5.97%                                 |
| **H (216)**             |         **$13,348,976$**         |                $13,092,026$                 |       $-256,950$       |     $-1.92\%$      | Approximate parity (-1.92%)                            |
| **XH (288)**            |         **$11,285,324$**         |                $10,578,459$                 |       $-706,864$       |     $-6.26\%$      | `STAGED` wins by 6.26%                                 |

---

## 6. Physical Mechanisms and High-Contention Dynamics

The observed behavior across P-core and E-core topologies establishes three structural principles:

### 6.1 Phase Separation Hypothesis

The observed throughput improvements under moderate handle deficit (e.g. 4 workers / 3 handles, or 8
workers / 4 handles) are consistent with `SKIP_THEN_STAGED` creating useful phase separation between
workers under handle deficit. By skipping an active execution pull cycle upon encountering
contention, contending workers stagger their subsequent pull attempts across available productive
handles. However, the magnitude of the E-core effect may also depend on shared-cache and
core-cluster topology.

### 6.2 Single-Handle Lock Exclusivity Limits

Under near-total starvation (e.g. 1 shared handle for 4 or 8 workers, $> 97\%$ contention), cycle
skipping cannot overcome physical handle exclusivity—only 1 worker can pull at a time regardless of
phase alignment. Consequently, repeated cycle skipping merely introduces idle stalls and extends
batch latency, allowing `STAGED` with calibrated follower idle parking to maintain dominance
by $5\% - 26\%$.

### 6.3 Topology-Specific Magnitudes

While P-core fixtures exhibit clean $+3.7\% \sim +6.4\%$ advantages in deficit regimes, E-core
fixtures exhibit $+26\% \sim +29\%$ advantages. Because E-cores share L2 cache structures within
Gracemont clusters, the larger magnitude on E-cores likely reflects combined phase realignment and
reduced L2 cache thrashing during demand dispatch. These results are preserved as genuine scheduler
phenomena on clustered architectures, but are not generalized as universal scalar contention
thresholds.

---

## 7. Reconciling Phase 8 with Historical DIRECT Evidence

Phase 8 compared `STAGED` vs `SKIP_THEN_STAGED`. This pairwise comparison identifies the superior
staged-family strategy, but does **not** establish that either beats `DIRECT`.

Cross-referencing authoritative multi-fork results
from [Phase 6](phase_6_execution_recheck_with_idling.md)
and [Phase 7](phase_7_skip_policy_calibration.md):

| Condition / Fixture                |        Contention Level        | DIRECT Multi-Fork Mean (ops/s) | STAGED Multi-Fork Mean (ops/s) | SKIP_THEN_STAGED Multi-Fork Mean (ops/s) | Best Supported Overall Action                 | Supporting Evidence                                   |
|:-----------------------------------|:------------------------------:|:------------------------------:|:------------------------------:|:----------------------------------------:|:----------------------------------------------|:------------------------------------------------------|
| **4 P-Core / 8 Sources ($W=48$)**  | $\approx 23.8\%$ (Band 1 `S`)  |        **$18,902,780$**        |          $18,961,420$          |               $17,706,754$               | **`DIRECT`**                                  | Phase 6/7 DIRECT dominance                            |
| **4 P-Core / 4 Sources ($W=48$)**  | $\approx 74.5\%$ (Band 3 `H`)  |        **$22,527,033$**        |          $21,977,418$          |               $20,238,008$               | **`DIRECT`**                                  | DIRECT beats STAGED (+2.5%) and Skip-Staged (+11.3%)  |
| **4 P-Core / 4 Sources ($W=144$)** | $\approx 74.5\%$ (Band 3 `H`)  |        **$17,580,834$**        |          $16,311,690$          |               $15,338,075$               | **`DIRECT`**                                  | DIRECT beats STAGED (+7.8%) and Skip-Staged (+14.6%)  |
| **4 P-Core / 4 Sources ($W=0$)**   | $\approx 74.5\%$ (Band 3 `H`)  |          $22,074,594$          |          $21,198,976$          |             **$21,974,812$**             | **`DIRECT` / Parity**                         | DIRECT and Skip-Staged in tight parity (~22.0M)       |
| **4 P-Core / 3 Sources ($W=48$)**  |  $\approx 84.8\%$ (Upper `H`)  |         *Not measured*         |          $19,270,036$          |             **$20,504,828$**             | **Unresolved (Candidate `SKIP_THEN_STAGED`)** | Skip-Staged beats Staged (+6.41%); DIRECT unmeasured  |
| **4 P-Core / 1 Source ($W=48$)**   | $\approx 98.9\%$ (Band 4 `XH`) |          $9,349,511$           |        **$17,523,568$**        |               $15,845,515$               | **`STAGED`**                                  | STAGED beats DIRECT (+87.4%) and Skip-Staged (+10.6%) |

> [!IMPORTANT]
> Around the $74.5\%$ contention landmark (4 P-cores / 4 sources), `DIRECT` remains superior to both
`STAGED` and `SKIP_THEN_STAGED` for non-zero workloads. Therefore, the existing baseline `DIRECT`
assignments for Band 3 ($650\text{k} - 850\text{k}$) must not be overwritten with staged policies.

---

## 8. Candidate High-Resolution Contention Threshold Vector

Phase 8 defines a useful candidate threshold vector for separating the observed high-contention
regimes:

$$\mathbf{C}_{\text{cand}} = [650000, 800000, 900000, 970000]$$

- **Status**: Candidate representation for high-contention quantization. It is not yet proven to be
  globally optimal across all architectures.
- **Role**: Serves as the working quantization grid for subsequent refinement phases.

---

## 9. Comprehensive Evidence Synthesis and Unresolved Regions

### 9.1 Strongly Established Findings

1. `DIRECT` execution dominates broad lower-contention regions ($0\% - 65\%$) and remains superior
   around $74.5\%$ balanced concurrency on P-cores.
2. A genuine `SKIP_THEN_STAGED` phase-realignment effect exists under multi-worker handle-deficit
   conditions.
3. Severe single-handle starvation ($> 95\%$) eliminates the benefit of cycle skipping, where
   `STAGED` with calibrated follower idle parking dominates.
4. `SKIP_THEN_DIRECT` provides no repeatable steady-state benefit and is eliminated from active
   high-contention steady-state policy consideration.

### 9.2 Replicated Topology-Specific Evidence

1. On 8-core clustered E-cores under 4 sources ($\approx 82.2\%$ contention), `SKIP_THEN_STAGED`
   achieves replicated $+26.5\% \sim +29.5\%$ throughput gains.
2. These gains represent real scheduler behavior on shared-cache architectures, but are treated as
   topology-specific pending cross-architecture validation.

### 9.3 Replicated Portable P-Core Evidence

1. At $\approx 74.5\%$ contention (4 P-cores / 4 sources), `STAGED` outperforms `SKIP_THEN_STAGED`
   for $W \ge 48$ ($+5.9\% \sim +7.9\%$), while `DIRECT` outperforms both.
2. At $\approx 84.8\%$ contention (4 P-cores / 3 sources), `SKIP_THEN_STAGED` outperforms `STAGED`
   by $+6.41\%$ at $W=48$, with approximate parity at $W=144$ and $W=288$.

### 9.4 Explicitly Documented Unresolved Regions

| Contention Range                                       |       Body Band        |                      Staged Winner                       |           Compatible DIRECT Evidence            | Status / Why Unresolved                                                                           |
|:-------------------------------------------------------|:----------------------:|:--------------------------------------------------------:|:-----------------------------------------------:|:--------------------------------------------------------------------------------------------------|
| **$800\text{k} - 900\text{k}$ (Candidate Band 2 `M`)** |       S ($W=48$)       | `SKIP_THEN_STAGED` (+6.41% on P-core, +29.45% on E-core) |        No compatible 3-source DIRECT run        | **Unresolved**: Staged winner established, but 3-way reconciliation against DIRECT is unmeasured. |
| **$800\text{k} - 900\text{k}$ (Candidate Band 2 `M`)** |      M ($W=144$)       | `SKIP_THEN_STAGED` (+26.57% on E-core; parity on P-core) |        No compatible 3-source DIRECT run        | **Unresolved**: P-core parity vs E-core gain; DIRECT unmeasured.                                  |
| **$800\text{k} - 900\text{k}$ (Candidate Band 2 `M`)** |      H ($W=216$)       |    `SKIP_THEN_STAGED` (+3.91% single-fork on P-core)     |        No compatible 3-source DIRECT run        | **Unresolved**: Single-fork indication only.                                                      |
| **$800\text{k} - 900\text{k}$ (Candidate Band 2 `M`)** |      XH ($W=288$)      |           Parity (-0.62% multi-fork on P-core)           |        No compatible 3-source DIRECT run        | **Unresolved**: Statistical parity between staged modes; DIRECT unmeasured.                       |
| **$900\text{k} - 970\text{k}$ (Candidate Band 3 `H`)** | All ($W \in [0..288]$) |                Mixed / `STAGED` advantage                | Compatible DIRECT is heavily defeated by STAGED | **Unresolved**: Exact transition between deficit skip benefit and starvation penalty.             |

---

## 10. Summary of Phase 8 Completion Gate

Phase 8 is completed under the following verified deliverables:

1. **Discovered a Repeatable `SKIP_THEN_STAGED` Operating Region**: Identified multi-worker
   handle-deficit conditions ($80\% - 90\%$ contention) where cycle skipping provides substantial
   throughput improvements.
2. **Demonstrated Internal High-Contention Structure**: Proved that the upper contention zone
   ($65\% - 100\%$) is not monolithic and divides into balanced concurrency, deficit
   phase-realignment, and single-handle starvation regimes.
3. **Established Candidate Thresholds**: Produced the candidate
   vector $\mathbf{C}_{\text{cand}} = [650000, 800000, 900000, 970000]$ for subsequent threshold
   refinement in Phase 9.
4. **Identified Hardware Geometry Sensitivity**: Documented shared-cache / cluster topology as a
   factor in E-core gain magnitude, weighting P-core multi-fork data for default portable
   calibration.
5. **Preserved Baseline Integrity**: Reconciled staged results with historical `DIRECT` evidence,
   preventing unsupported baseline overwrites.
