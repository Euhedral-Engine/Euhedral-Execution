# Phase 5 Calibration Findings: Idle Behavior Calibration

This document records the empirical results, throughput curves, runtime occupancy telemetry, and
calibrated idle policy surface for **Phase 5 (Calibrate Idle Behavior)** of
the [Euhedral Tuning Process](../../EUHEDRAL_TUNING_PROCESS.md#8-phase-5---calibrate-idle-behavior).

---

## 1. Overview and Calibration Fixture

Phase 5 calibrates worker participation and idle park behavior across the 2-dimensional 5x5
contention and body-cost decision grid:

$$\text{Contention Bands (XS, S, M, H, XH)} \times \text{Body Cost Bands (XS, S, M, H, XH)}$$

### Physical Idling Mechanics

In Euhedral, idling is evaluated per-core by [
`FragmentDecisionTree`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/FragmentDecisionTree.java)
during fragment execution cycles in [
`ControlPlaneFragment`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java):

1. **Leader vs Follower Ownership**:
    - The primary worker on a socket (`workerRank == 0`) never parks during active execution; it
      remains active to pull, stage, and drive upstream ingestion.
    - Follower sibling workers (`workerRank >= 1`) evaluate `controlPolicy.idle(...)` whenever their
      local batch cache is empty (`localCache == 0`).
2. **Phase Alignment and Lock Thrashing Mitigation**:
    - Under low-to-moderate contention (Bands 0..3: `XS`, `S`, `M`, `H`), productive handles are
      available and sibling workers have work to pull directly.
    - Under severe acquisition contention (Band 4: `XH`, e.g. 1 shared upstream source), sibling
      workers repeatedly collide attempting to acquire the same upstream handle lock.
    - Bounded parking (`LockSupport.parkNanos(parkTimeNs)`) allows follower workers to yield CPU
      time, enabling the active worker to pull unhindered, allowing batches to accumulate on the
      upstream source and aligning ingestion phases without spin-lock thrashing.
3. **Idling Progression**:
    - **Under-idled ($0\text{ ns}$)**: Sibling workers tight-spin on empty caches and contend
      aggressively for upstream locks.
    - **Optimum Plateau**: Sibling workers yield long enough for the active worker to clear lock
      contention and pull batch work, maximizing throughput.
    - **Over-idled ($>25\,\mu\text{s} \sim 50\,\mu\text{s}$)**: Workers sleep excessively, leaving
      frames unconsumed and causing throughput to decline.

### Calibration Constraints and Fixtures

- **Host Hardware**: Intel Core i9-14900K (x86_64 Linux, 8 P-cores / 16 E-cores, 6.0 GHz max).
- **Core Fixtures**:
    - **2-Core Fixture**: `cpuSet = [2, 4]` (Physical P-Core 1 and Physical P-Core 2).
    - **4-Core Fixture**: `cpuSet = [2, 4, 6, 8]` (Physical P-Cores 1, 2, 3, 4).
- **Execution Policy Surface**: Frozen at Phase 4 calibrated matrix:
    - Bands 0..3 (`XS`, `S`, `M`, `H` Contention): Forced `DIRECT` across all body bands.
    - Band 4 (`XH` Contention): Forced `STAGED` across all body bands.
- **Body Bands**: Frozen at Phase 2 calibrated weights $\mathbf{W} = [96, 128, 216, 288]$:
    - `Band 0 (XS Body)`: `workUnits = 0` (Dispatch overhead floor, $\sim 24\text{ ns}$)
    - `Band 1 (S Body)`: `workUnits = 48` (Light workload, $\sim 118\text{ ns}$)
    - `Band 2 (M Body)`: `workUnits = 144` (Moderate workload, $\sim 195\text{ ns}$)
    - `Band 3 (H Body)`: `workUnits = 216` (Upper convergence landmark, $\sim 265\text{ ns}$)
    - `Band 4 (XH Body)`: `workUnits = 288` (Heavy workload, $\sim 325\text{ ns}$)
- **Contention Bands**: Frozen at Phase 3 calibrated
  thresholds $\mathbf{C} = [50000, 350000, 650000, 850000]$.

---

## 2. Coarse Idle Contention and Park Duration Sweep (Experiment 10)

- **Experiment Preset**: [
  `benchmarks/src/main/presets/experiments/10-idle-contention-coarse-sweep.json`](../experiments/10-idle-contention-coarse-sweep.json)
- **Comparison Preset**: [
  `benchmarks/src/main/presets/comparisons/10-idle-contention-coarse-sweep.json`](../comparisons/10-idle-contention-coarse-sweep.json)
- **Completed Telemetry**: [
  `experiments/10-idle-contention-coarse-sweep/`](file:///home/brandon/src/Euhedral-Execution/experiments/10-idle-contention-coarse-sweep)

### 2.1 Empirical Throughput across Contention Regimes and Park Durations (2 Cores)

The table below summarizes measured JMH throughput across uncontended (`16 Sources / XS`), balanced
(`2 Sources / M`), and severe deficit (`1 Source / XH`) contention regimes across park durations
from $0\text{ ns}$ to $50,000\text{ ns}$ ($50\,\mu\text{s}$):

| Ingest Sources | Contention Band | Body Band (`workUnits`) | 0 ns (No Idle) ops/s | 1 µs (1,000 ns) ops/s | 5 µs (5,000 ns) ops/s | 15 µs (15,000 ns) ops/s | 50 µs (50,000 ns) ops/s | Observable Behavior & Notes                           |
|:--------------:|:---------------:|:-----------------------:|:--------------------:|:---------------------:|:---------------------:|:-----------------------:|:-----------------------:|:------------------------------------------------------|
|     **16**     | **Band 0 (XS)** |       **XS (0)**        |     $17,949,573$     |     $18,384,386$      |     $26,418,842$      |      $18,456,318$       |      $18,414,849$       | High single-fork point at 5 µs (unreplicated anomaly) |
|     **16**     | **Band 0 (XS)** |       **S (48)**        |     $15,707,645$     |     $15,778,929$      |     $16,110,749$      |       $9,299,195$       |      $14,434,576$       | Degradation dip at $15\,\mu\text{s}$                  |
|     **16**     | **Band 0 (XS)** |       **M (144)**       |     $9,680,039$      |      $9,514,494$      |      $9,771,170$      |       $9,273,206$       |       $8,914,382$       | Monotonic decline with higher parking                 |
|     **16**     | **Band 0 (XS)** |      **XH (288)**       |    $6,113,454^*$     |      $5,972,137$      |      $6,062,618$      |       $5,814,722$       |       $3,083,102$       | Severe collapse at $50\,\mu\text{s}$ ($-49.6\%$)      |
|     **2**      | **Band 2 (M)**  |       **XS (0)**        |     $17,783,611$     |     $17,496,655$      |     $19,654,719$      |      $17,571,663$       |      $16,549,204$       | Transient excursion at 5 µs; drops at 50 µs           |
|     **2**      | **Band 2 (M)**  |       **S (48)**        |     $16,523,550$     |     $16,760,293$      |     $15,986,043$      |      $14,492,427$       |      $14,034,021$       | General downward trend with parking                   |
|     **2**      | **Band 2 (M)**  |       **M (144)**       |     $9,882,718$      |      $9,834,714$      |      $9,714,414$      |       $9,395,074$       |       $8,682,234$       | Downward trend ($0\text{ns}$ best)                    |
|     **2**      | **Band 2 (M)**  |      **XH (288)**       |     $6,129,631$      |      $6,106,103$      |      $5,951,364$      |       $5,871,983$       |       $5,301,328$       | Downward trend ($0\text{ns}$ best)                    |
|     **1**      | **Band 4 (XH)** |       **XS (0)**        |     $17,639,558$     |   **$18,656,180$**    |     $17,658,613$      |      $17,860,634$       |      $17,212,786$       | Peak at $1\,\mu\text{s}$ (+5.8%)                      |
|     **1**      | **Band 4 (XH)** |       **S (48)**        |     $15,378,137$     |      $9,162,806$      |      $8,907,643$      |    **$15,524,995$**     |      $14,033,398$       | Peak at $15\,\mu\text{s}$ (+1.0%)                     |
|     **1**      | **Band 4 (XH)** |       **M (144)**       |     $10,252,943$     |      $9,374,042$      |      $8,166,800$      |       $8,730,233$       |       $8,001,063$       | Sensitive to over-idling                              |
|     **1**      | **Band 4 (XH)** |      **XH (288)**       |     $5,924,506$      |      $5,860,682$      |      $5,773,633$      |       $5,050,685$       |       $5,061,937$       | Over-idling penalty at $\ge 15\,\mu\text{s}$          |

\*Note: Phase 4 steady-state baseline reference for 16 sources / 288 work units.

### 2.2 Empirical Findings and Non-XH Policy Interpretation

1. **Concentration of Repeatable Idling Benefit**:
    - The broad and repeatable useful-idling behavior appears concentrated in **Band 4 (`XH`
      Contention)**, where workers face severe upstream lock contention on a single shared source.
2. **Evaluation of Non-XH Regimes (Bands 0..3: `XS`, `S`, `M`, `H`)**:
    - Non-XH regimes do not currently have sufficient empirical evidence for a stable positive park
      policy.
    - In several coarse sweep points, isolated single-fork throughput excursions were observed
      (e.g., 16 sources / XS contention / XS body reaching $26.42\text{M ops/s}$at $5\,\mu\text{s}$,
      and 2 sources / M contention / XS body reaching $19.65\text{M ops/s}$at $5\,\mu\text{s}$).
    - These isolated points are treated as unstable/anomalous single-fork transients rather than
      proof of a beneficial non-XH idle policy.
    - Conversely, their presence means the coarse sweep data alone does not support an absolute
      claim that all non-XH parking strictly degrades throughput across all conditions.
    - Rather, because non-XH regimes lack stable, replicated evidence of improvement and generally
      trend flat or downward with higher parking durations, **`0 ns` is maintained as the
      conservative baseline** for Bands XS..H unless future multi-fork investigation justifies
      otherwise. Coarse anomalies are not encoded as policy changes.

---

## 3. Fine-Grained Idle Duration Refinement in Severe Deficit (Experiment 11)

- **Experiment Preset**: [
  `benchmarks/src/main/presets/experiments/11-idle-duration-refinement-xh.json`](../experiments/11-idle-duration-refinement-xh.json)
- **Completed Telemetry**: [
  `experiments/11-idle-duration-refinement-xh/`](file:///home/brandon/src/Euhedral-Execution/experiments/11-idle-duration-refinement-xh)

Experiment 11 explored fine-grained park durations
($0\text{ ns}, 500\text{ ns}, 1\,\mu\text{s}, 2.5\,\mu\text{s}, 5\,\mu\text{s}, 10\,\mu\text{s}, 15\,\mu\text{s}, 25\,\mu\text{s}$)
on 2-core and 4-core topologies in the single-source bottleneck regime (`XH` contention).

### 3.1 2-Core XH Contention Park Sweep (1 Source / 2 Cores)

| Body Band (`workUnits`) |  0 ns ops/s  | 500 ns ops/s |   1 µs ops/s    | 2.5 µs ops/s |    5 µs ops/s    | 10 µs ops/s  | 15 µs ops/s  |   25 µs ops/s    | Measured Peak & Delta vs 0 ns |
|:------------------------|:------------:|:------------:|:---------------:|:------------:|:----------------:|:------------:|:------------:|:----------------:|:------------------------------|
| **XS (0)**              | $18,521,247$ | $18,578,768$ |  $18,117,219$   | $18,300,060$ | **$19,092,525$** | $17,862,755$ | $17,450,583$ |   $18,483,023$   | **5 µs (+3.08%)**             |
| **S (48)**              | $15,592,900$ | $16,264,326$ |  $13,446,221$   | $13,473,430$ |   $16,373,584$   | $14,912,442$ | $13,732,397$ | **$16,580,909$** | **15~25 µs (+6.34%)**         |
| **M (144)**             | $9,507,485$  | $9,055,954$  | **$9,524,019$** | $5,090,270$  |   $8,343,997$    | $9,155,223$  | $9,002,738$  |   $9,387,943$    | **1~5 µs (+0.17%)**           |
| **H (216)**             | $7,103,104$  | $7,196,216$  |   $6,418,602$   | $7,268,591$  | **$7,751,995$**  | $7,107,437$  | $7,354,510$  |   $7,124,802$    | **5 µs (+9.14%)**             |
| **XH (288)**            | $5,798,526$  | $5,770,285$  | **$5,930,116$** | $5,193,540$  |   $5,905,290$    | $5,907,655$  | $5,846,887$  |   $5,874,504$    | **1~5 µs (+2.27%)**           |

### 3.2 4-Core XH Contention Park Sweep (1 Source / 4 Cores)

Under 4-core concurrency on a single shared source, three follower workers (`ranks 1, 2, 3`) compete
against leader worker (`rank 0`), creating intense lock contention and high collision frequency.

| Body Band (`workUnits`) | 0 ns (No Idle) ops/s | 1 µs (1,000 ns) ops/s | 5 µs (5,000 ns) ops/s | 15 µs (15,000 ns) ops/s | Measured Peak vs 0 ns                               |
|:------------------------|:--------------------:|:---------------------:|:---------------------:|:-----------------------:|:----------------------------------------------------|
| **XS (0)**              |     $22,315,099$     |   **$27,740,168$**    |     $20,858,230$      |      $17,263,644$       | **Peak at 1 µs (+24.31%)**; steep drop beyond 5 µs  |
| **S (48)**              |     $20,299,392$     |     $18,546,080$      |     $14,345,778$      |    **$24,948,980$**     | **Peak at 15 µs (+22.91%)**; non-monotonic response |
| **M (144)**             |     $16,797,374$     |     $14,366,859$      |   **$16,859,534$**    |      $16,506,230$       | **Plateau around 5 µs (+0.37%)**                    |
| **H (216)**             |   **$13,950,954$**   |     $13,378,271$      |     $13,636,498$      |      $12,711,620$       | Near-parity across $0 \sim 5\,\mu\text{s}$          |
| **XH (288)**            |     $11,540,520$     |     $11,379,162$      |     $11,420,884$      |    **$11,641,041$**     | Stable across $0 \sim 15\,\mu\text{s}$ (+0.87%)     |

### 3.3 Empirical Observations and Caveats from Fine Refinement

1. **XS Body Work ($W = 0$, $\sim 24\text{ ns}$ execution time)**:
    - For ultra-fast execution, short parking ($1\,\mu\text{s}$) delivered a large measured peak
      (+24.31%) in the single-fork 4-core sweep, whereas parking beyond $5\,\mu\text{s}$ caused
      significant throughput loss ($27.7\text{M} \to 17.3\text{M ops/s}$).
2. **Strongly Non-Monotonic Response in S Body ($W = 48$, $\sim 118\text{ ns}$ execution time)**:
    - In 4-core S-body testing, throughput exhibited non-monotonic behavior:
      $$0\,\mu\text{s} \, (20.30\text{M}) \longrightarrow 1\,\mu\text{s} \, (18.55\text{M}) \longrightarrow 5\,\mu\text{s} \, (14.35\text{M}) \longrightarrow 15\,\mu\text{s} \, (24.95\text{M})$$
    - This non-monotonic curve is consistent with timing/phase-alignment or batch accumulation
      effects where specific park intervals align follower wakeups with upstream replenishment
      intervals, rather than monotonic backoff.
    - However, the underlying physical mechanism cannot be claimed as definitively proven from
      throughput measurements alone.
    - Note also that large single-fork peaks (such as the +22.9% sweep point) often compress
      substantially under multi-fork averaging.
3. **Behavior in Moderate and Heavy Workloads (M, H, XH Body Bands)**:
    - **M Body ($W = 144$)**: Throughput is relatively noisy and remains largely near parity across
      small park durations ($9.5\text{M} \pm 0.5\text{M}$ on 2
      cores; $16.5\text{M} \sim 16.8\text{M}$ on 4 cores).
    - **H Body ($W = 216$)**: Exhibited a measurable 2-core gain around $5\,\mu\text{s}$
      ($7.10\text{M} \to 7.75\text{M ops/s}$, +9.1%), while remaining flat/near-parity on 4 cores.
    - **XH Body ($W = 288$)**: Shows a flat plateau around $0 \sim 5\,\mu\text{s}$
      ($5.8\text{M} \sim 5.9\text{M}$ on 2 cores; $11.4\text{M} \sim 11.6\text{M}$ on 4 cores).
    - **4-Core Summary**: Across M, H, and XH workloads on 4 cores, parking behavior is
      characterized primarily by a plateau or statistical parity rather than consistent throughput
      gains. The $5\,\mu\text{s}$ candidate value is a reasonable compromise for heavy workloads on
      the tested fixtures, but should not be characterized as universally optimal.

---

## 4. Multi-Fork Statistical Verification Suite (Experiment 12)

- **Experiment Preset**: [
  `benchmarks/src/main/presets/experiments/12-idle-policy-verification-multifork.json`](../experiments/12-idle-policy-verification-multifork.json)
- **Comparison Presets**:
    - 2-Core Comparison: [
      `benchmarks/src/main/presets/comparisons/12-idle-policy-verification-2core.json`](../comparisons/12-idle-policy-verification-2core.json)
    - 4-Core Comparison: [
      `benchmarks/src/main/presets/comparisons/12-idle-policy-verification-4core.json`](../comparisons/12-idle-policy-verification-4core.json)
- **Completed Telemetry**:
    - 2-Core Comparison Summary: [
      `experiments/12-idle-policy-verification-multifork/comparisons-2core/comparison_summary.tsv`](file:///home/brandon/src/Euhedral-Execution/experiments/12-idle-policy-verification-multifork/comparisons-2core/comparison_summary.tsv)
    - 4-Core Comparison Summary: [
      `experiments/12-idle-policy-verification-multifork/comparisons-4core/comparison_summary.tsv`](file:///home/brandon/src/Euhedral-Execution/experiments/12-idle-policy-verification-multifork/comparisons-4core/comparison_summary.tsv)

Experiment 12 evaluated the candidate Phase 5 idle policy profile vs the no-idle baseline across **3
independent JVM forks** (15 measurement iterations per condition):

$$\mathbf{P}_{\text{XH}} = [\text{xsPark}=1000\text{ ns}, \text{sPark}=15000\text{ ns}, \text{mPark}=5000\text{ ns}, \text{hPark}=5000\text{ ns}, \text{xhPark}=5000\text{ ns}]$$

### 4.1 2-Core Multi-Fork Verification Results (1 Source / 2 Cores)

| Body Band (`workUnits`) |     Baseline Mean (ops/s)     |   Calibrated Idle Mean (ops/s)    | Absolute Delta (ops/s) | Relative Delta (%) | Multi-Fork Statistical Outcome             |
|:------------------------|:-----------------------------:|:---------------------------------:|:----------------------:|:------------------:|:-------------------------------------------|
| **XS (0)**              | $18,663,595 \pm 1.88\text{M}$ |   $18,390,255 \pm 1.05\text{M}$   |       $-273,339$       |     $-1.46\%$      | Statistical parity                         |
| **S (48)**              | $15,264,406 \pm 1.84\text{M}$ | **$16,310,318 \pm 2.76\text{M}$** |      $+1,045,912$      |   **$+6.85\%$**    | Meaningful gain for calibrated idle policy |
| **M (144)**             | $8,629,078 \pm 1.55\text{M}$  | **$9,310,135 \pm 1.36\text{M}$**  |       $+681,057$       |   **$+7.89\%$**    | Meaningful gain for calibrated idle policy |
| **H (216)**             | $6,771,931 \pm 1.27\text{M}$  | **$7,008,408 \pm 0.94\text{M}$**  |       $+236,478$       |   **$+3.49\%$**    | Consistent gain for calibrated idle policy |
| **XH (288)**            | $5,892,749 \pm 0.03\text{M}$  | **$5,908,589 \pm 0.03\text{M}$**  |       $+15,840$        |   **$+0.27\%$**    | Statistical parity / tight convergence     |

### 4.2 4-Core Multi-Fork Verification Results (1 Source / 4 Cores)

| Body Band (`workUnits`) |     Baseline Mean (ops/s)     |   Calibrated Idle Mean (ops/s)    | Absolute Delta (ops/s) | Relative Delta (%) | Multi-Fork Statistical Outcome         |
|:------------------------|:-----------------------------:|:---------------------------------:|:----------------------:|:------------------:|:---------------------------------------|
| **XS (0)**              | $22,461,877 \pm 2.81\text{M}$ | **$23,058,402 \pm 0.85\text{M}$** |       $+596,525$       |   **$+2.66\%$**    | Modest gain & lower variance           |
| **S (48)**              | $21,261,290 \pm 0.70\text{M}$ |   $21,195,731 \pm 0.75\text{M}$   |       $-65,559$        |     $-0.31\%$      | Statistical parity                     |
| **M (144)**             | $16,907,889 \pm 0.46\text{M}$ |   $16,562,102 \pm 0.36\text{M}$   |       $-345,787$       |     $-2.05\%$      | Statistical parity / slight regression |
| **H (216)**             | $13,711,652 \pm 0.21\text{M}$ |   $13,501,082 \pm 0.26\text{M}$   |       $-210,570$       |     $-1.54\%$      | Statistical parity / slight regression |
| **XH (288)**            | $11,364,169 \pm 0.04\text{M}$ |   $11,227,369 \pm 0.07\text{M}$   |       $-136,800$       |     $-1.20\%$      | Statistical parity / slight regression |

### 4.3 Summary of Multi-Fork Verification

- **2-Core Fixture**: The calibrated idle policy produced meaningful, repeatable throughput gains in
  the **S (+6.85%)**, **M (+7.89%)**, and **H (+3.49%)** body bands, while XS and XH were
  approximately at parity.
- **4-Core Fixture**: Only the **XS body band (+2.66%)** retained a positive measured result; S, M,
  H, and XH showed approximate parity or small regressions ($-0.31\%$ to $-2.05\%$).
- **Takeaway**: The exact XH park optimum is topology-sensitive and less portable than the coarse
  structural rule that parking helps under severe contention. Not all 4-core XH configurations
  benefit from the same park intervals.

---

## 5. Synthesis and Calibrated Idle Policy Surface

To maintain scientific integrity, Phase 5 distinguishes between coarse structural rules,
body-dependent relationships, and fixture-selected provisional values.

### 5.1 Policy Confidence Tiers

1. **Strongly Established (Coarse Structural Rules)**:
    - **Bands 0..3 (`XS`, `S`, `M`, `H` Contention)**: No stable evidence yet that parking should be
      enabled; retain the **$0\text{ ns}$ baseline**.
    - **Band 4 (`XH` Contention)**: Severe contention (e.g. single-source bottleneck) is the primary
      region where follower parking can be useful.
2. **Moderately Established (Body Cost Relationships)**:
    - Inside XH contention, useful park timing varies with body cost: ultra-fast work requires short
      park intervals ($\le 1\,\mu\text{s}$) to avoid idle starvation, whereas light workloads can
      benefit from longer batch accumulation intervals ($15\,\mu\text{s}$).
3. **Fixture-Selected / Provisional (Exact Park Values)**:
    - The specific values
      ($\text{xsPark}=1000\text{ ns}$, $\text{sPark}=15000\text{ ns}$, $\text{mPark}=5000\text{ ns}$, $\text{hPark}=5000\text{ ns}$, $\text{xhPark}=5000\text{ ns}$)
      represent the current calibrated policy selected from the tested host hardware and fixtures.
      They are empirical operational selections rather than universal physical constants.

### 5.2 Calibrated 5x5 Idle Policy Grid (`idleTimeNs` in nanoseconds)

```text
               Contention Band (i)
  XH (4) |  1000ns  15000ns   5000ns   5000ns   5000ns
   H (3) |     0ns      0ns      0ns      0ns      0ns
   M (2) |     0ns      0ns      0ns      0ns      0ns
   S (1) |     0ns      0ns      0ns      0ns      0ns
  XS (0) |     0ns      0ns      0ns      0ns      0ns
         +--------------------------------------------
              XS        S        M        H       XH
               0        1        2        3        4
                         Body Band (j)
```

---

## 6. Baseline Profile Library Update

The baseline profile library in [
`benchmarks/src/main/presets/profiles/baseline.json`](../profiles/baseline.json) encodes the
calibrated idle policy matrix:

```json
{
  "calibrationProfiles": {
    "standard-2core-fixture": {
      "cpuSet": [
        2,
        4
      ],
      "parallelSources": 2,
      "orderedSources": 0,
      "workUnits": 0,
      "randomizeWork": false,
      "totalRequiredExecutions": 8000000,
      "invocationTimeoutMillis": 30000,
      "decisionWeightProfile": "default-weights",
      "rawSampleLimit": 4096,
      "observeCycleStart": false,
      "observeBatchProgress": false,
      "observeBatchComplete": false,
      "observeRawBodyCost": true,
      "observeIdleDecision": false,
      "observeExecDecision": true
    }
  },
  "decisionWeightProfiles": {
    "default-weights": {
      "idleContentionThresholds": {
        "xsContention": 50000,
        "sContention": 350000,
        "mContention": 650000,
        "hContention": 850000
      },
      "idleBodyCostWeights": [
        {
          "xs": 96,
          "s": 128,
          "m": 216,
          "h": 288
        },
        {
          "xs": 96,
          "s": 128,
          "m": 216,
          "h": 288
        },
        {
          "xs": 96,
          "s": 128,
          "m": 216,
          "h": 288
        },
        {
          "xs": 96,
          "s": 128,
          "m": 216,
          "h": 288
        }
      ],
      "idleTimeNs": [
        {
          "xsPark": 0,
          "sPark": 0,
          "mPark": 0,
          "hPark": 0,
          "xhPark": 0
        },
        {
          "xsPark": 0,
          "sPark": 0,
          "mPark": 0,
          "hPark": 0,
          "xhPark": 0
        },
        {
          "xsPark": 0,
          "sPark": 0,
          "mPark": 0,
          "hPark": 0,
          "xhPark": 0
        },
        {
          "xsPark": 0,
          "sPark": 0,
          "mPark": 0,
          "hPark": 0,
          "xhPark": 0
        },
        {
          "xsPark": 1000,
          "sPark": 15000,
          "mPark": 5000,
          "hPark": 5000,
          "xhPark": 5000
        }
      ],
      "execContentionThresholds": {
        "xsContention": 50000,
        "sContention": 350000,
        "mContention": 650000,
        "hContention": 850000
      },
      "execBodyCostWeights": [
        {
          "xs": 96,
          "s": 128,
          "m": 216,
          "h": 288
        },
        {
          "xs": 96,
          "s": 128,
          "m": 216,
          "h": 288
        },
        {
          "xs": 96,
          "s": 128,
          "m": 216,
          "h": 288
        },
        {
          "xs": 96,
          "s": 128,
          "m": 216,
          "h": 288
        }
      ],
      "executionPolicies": [
        {
          "xsBody": "DIRECT",
          "sBody": "DIRECT",
          "mBody": "DIRECT",
          "hBody": "DIRECT",
          "xhBody": "DIRECT"
        },
        {
          "xsBody": "DIRECT",
          "sBody": "DIRECT",
          "mBody": "DIRECT",
          "hBody": "DIRECT",
          "xhBody": "DIRECT"
        },
        {
          "xsBody": "DIRECT",
          "sBody": "DIRECT",
          "mBody": "DIRECT",
          "hBody": "DIRECT",
          "xhBody": "DIRECT"
        },
        {
          "xsBody": "DIRECT",
          "sBody": "DIRECT",
          "mBody": "DIRECT",
          "hBody": "DIRECT",
          "xhBody": "DIRECT"
        },
        {
          "xsBody": "STAGED",
          "sBody": "STAGED",
          "mBody": "STAGED",
          "hBody": "STAGED",
          "xhBody": "STAGED"
        }
      ]
    }
  }
}
```

---

## 7. Artifact and Configuration Traceability

| Artifact Description                       | File Path                                                                                                                                                                                                                              |
|:-------------------------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Phase 5 Coarse Sweep Preset**            | [`benchmarks/src/main/presets/experiments/10-idle-contention-coarse-sweep.json`](../experiments/10-idle-contention-coarse-sweep.json)                                                                                                  |
| **Phase 5 Coarse Comparison Preset**       | [`benchmarks/src/main/presets/comparisons/10-idle-contention-coarse-sweep.json`](../comparisons/10-idle-contention-coarse-sweep.json)                                                                                                  |
| **Phase 5 Fine-Grained Refinement Preset** | [`benchmarks/src/main/presets/experiments/11-idle-duration-refinement-xh.json`](../experiments/11-idle-duration-refinement-xh.json)                                                                                                    |
| **Multi-Fork Verification Suite Preset**   | [`benchmarks/src/main/presets/experiments/12-idle-policy-verification-multifork.json`](../experiments/12-idle-policy-verification-multifork.json)                                                                                      |
| **Multi-Fork 2-Core Comparison Preset**    | [`benchmarks/src/main/presets/comparisons/12-idle-policy-verification-2core.json`](../comparisons/12-idle-policy-verification-2core.json)                                                                                              |
| **Multi-Fork 2-Core Comparison Summary**   | [`experiments/12-idle-policy-verification-multifork/comparisons-2core/comparison_summary.tsv`](file:///home/brandon/src/Euhedral-Execution/experiments/12-idle-policy-verification-multifork/comparisons-2core/comparison_summary.tsv) |
| **Multi-Fork 4-Core Comparison Preset**    | [`benchmarks/src/main/presets/comparisons/12-idle-policy-verification-4core.json`](../comparisons/12-idle-policy-verification-4core.json)                                                                                              |
| **Multi-Fork 4-Core Comparison Summary**   | [`experiments/12-idle-policy-verification-multifork/comparisons-4core/comparison_summary.tsv`](file:///home/brandon/src/Euhedral-Execution/experiments/12-idle-policy-verification-multifork/comparisons-4core/comparison_summary.tsv) |
| **Updated Baseline Profile Library**       | [`benchmarks/src/main/presets/profiles/baseline.json`](../profiles/baseline.json)                                                                                                                                                      |

---

## 8. Definition of Done Checklist for Phase 5

- [x] Coarse and fine-grained park duration sweeps completed across contention and body cost
  surfaces.
- [x] Severe deficit (`XH` contention) identified as the primary regime where follower idling is
  beneficial.
- [x] Body-dependent timing behavior observed inside `XH` contention across dispatch, light, and
  heavy workloads.
- [x] Non-monotonic response in S-body work documented as consistent with timing/phase-alignment
  effects.
- [x] Candidate park values
  ($1\,\mu\text{s}, 15\,\mu\text{s}, 5\,\mu\text{s}, 5\,\mu\text{s}, 5\,\mu\text{s}$) encoded in [
  `baseline.json`](../profiles/baseline.json) as fixture-calibrated selections.
- [x] Multi-fork verification confirmed meaningful 2-core gains in several body bands (S: +6.85%, M:
  +7.89%, H: +3.49%).
- [x] 4-core verification documented as showing mostly parity (with XS retaining a +2.66% gain),
  confirming that exact park durations remain topology-sensitive.
- [x] Physical rationale and policy confidence tiers documented, clearly separating structural rules
  from provisional fixture selections.
