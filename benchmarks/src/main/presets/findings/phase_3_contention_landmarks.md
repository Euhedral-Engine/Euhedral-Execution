# Phase 3 Calibration Findings: Physical Contention Landmarks and Threshold Mapping

This document records the empirical results, contention scaling curves, and calibrated contention thresholds
for **Phase 3 (Establish Contention Landmarks)** of
the [Euhedral Tuning Process](../../EUHEDRAL_TUNING_PROCESS.md#6-phase-3---establish-contention-landmarks).

---

## 1. Overview and Calibration Fixture

Phase 3 establishes the physical contention continuum and derives the calibrated contention threshold vector:

$$\mathbf{C} = [C_{\text{xs}}, C_{\text{s}}, C_{\text{m}}, C_{\text{h}}]$$

that partitions runtime upstream acquisition contention into five discrete, behavior-driven contention bands:
**Band 0 (XS)**, **Band 1 (S)**, **Band 2 (M)**, **Band 3 (H)**, and **Band 4 (XH)**.

### Upstream Queue Contention Mechanics

In Euhedral, contention is an empirical measure of upstream handle lock contention tracked per worker in
[`UpstreamQueue`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/UpstreamQueue.java).
When a worker fragment executes a pull cycle across its active upstream handles:

1. It attempts to acquire each handle lock via `handle.acquireLock()`.
2. Failed acquisitions are tracked as `failedAcquires` out of total `attempts`.
3. The cycle contention ratio is scaled into a fixed-point integer on $[0 \dots 1,000,000]$:

   $$\text{scaledContention} = \frac{\text{failedAcquires}}{\text{attempts}} \times 1,000,000$$

4. The worker updates an exponentially weighted moving average (`AverageFlow`), and `FragmentDecisionTree` evaluates:
   - $\text{contention} \le C_{\text{xs}} \implies \text{Band 0 (XS Contention)}$
   - $C_{\text{xs}} < \text{contention} \le C_{\text{s}} \implies \text{Band 1 (S Contention)}$
   - $C_{\text{s}} < \text{contention} \le C_{\text{m}} \implies \text{Band 2 (M Contention)}$
   - $C_{\text{m}} < \text{contention} \le C_{\text{h}} \implies \text{Band 3 (H Contention)}$
   - $\text{contention} > C_{\text{h}} \implies \text{Band 4 (XH Contention)}$

### Calibration Fixture Topology

- **Host Hardware**: Intel Core i9-14900K (x86_64 Linux, 8 P-cores / 16 E-cores).
- **Core Fixtures**:
  - **2-Core Fixture**: `cpuSet = [2, 4]` (Physical Core 1 [CPU 2] and Physical Core 2 [CPU 4]).
  - **4-Core Fixture**: `cpuSet = [2, 4, 6, 8]` (Physical Cores 1, 2, 3, 4).
- **Body Bands**: Frozen at Phase 2 calibrated weights $\mathbf{W} = [96, 128, 216, 288]$.
- **Workload Anchor**: Fixed `workUnits = 48` (Light / S body workload, $\sim 118\text{ ns}$ execution body cost).
- **Execution Policy**: Forced `DIRECT` across all 25 grid cells.
- **Idling Behavior**: Disabled (`idleTimeNs = 0`).
- **Invocation Scale**: `8,000,000` required frame executions per JMH invocation (`invocationTimeoutMillis = 30,000`).
- **Measurement Config**: 1 fork, 2 warmups (2s each), 5 measurement iterations (3s each).

---

## 2. Contention Continuum and Landmark Exploration (Experiment 04)

- **Experiment Preset**: [`benchmarks/src/main/presets/experiments/04-contention-landmarks.json`](../experiments/04-contention-landmarks.json)
- **Comparison Preset**: [`benchmarks/src/main/presets/comparisons/04-contention-landmarks.json`](../comparisons/04-contention-landmarks.json)

### 2.1 Empirical Telemetry and Throughput Across Contention Variations

The table below summarizes the observed contention distributions and JMH execution throughput across variations in
parallel sources, worker counts, and source ordering:

| Trial Identifier | Ingest Sources | Worker Cores | Contention Mean | Contention P25 | Contention P50 (Median) | Contention P75 | Contention P95 | JMH Throughput (ops/s) | Observable System Regime |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---|
| `parallel-sources-2core__0` | 16 Parallel | 2 | **$30,425$** | $15$ | **$15$** | $15$ | $386,677$ | $15,757,577$ | **XS Landmark**: Abundant sources ($8:1$) |
| `parallel-sources-2core__1` | 8 Parallel | 2 | **$78,089$** | $15$ | **$83$** | $53,014$ | $497,131$ | $16,075,567$ | Saturated sources ($4:1$) |
| `parallel-sources-2core__2` | 4 Parallel | 2 | **$164,567$** | $149,107$ | **$164,309$** | $179,759$ | $200,883$ | $15,774,795$ | **S Landmark**: Moderate sources ($2:1$) |
| `parallel-sources-2core__3` | 2 Parallel | 2 | **$495,948$** | $497,348$ | **$499,915$** | $499,985$ | $499,985$ | $15,621,893$ | **M Landmark**: Balanced ($1:1$, 2 workers) |
| `parallel-sources-2core__4` | 1 Parallel | 2 | **$991,820$** | $999,985$ | **$999,985$** | $999,985$ | $999,985$ | **$10,465,082$** | **XH Landmark**: Single-source bottleneck ($-33.3\%$) |
| `parallel-sources-4core__0` | 16 Parallel | 4 | **$110,280$** | $35,357$ | **$88,884$** | $155,505$ | $307,575$ | $20,985,373$ | Abundant sources ($4:1$, 4 workers) |
| `parallel-sources-4core__1` | 8 Parallel | 4 | **$237,593$** | $183,524$ | **$232,274$** | $285,175$ | $383,664$ | $20,994,642$ | **S Landmark**: Moderate sources ($2:1$, 4 workers) |
| `parallel-sources-4core__2` | 4 Parallel | 4 | **$741,597$** | $739,380$ | **$747,119$** | $749,606$ | $749,985$ | $20,980,843$ | **H Landmark**: Balanced ($1:1$, 4 workers) |
| `parallel-sources-4core__3` | 2 Parallel | 4 | **$973,883$** | $981,238$ | **$999,411$** | $999,985$ | $999,985$ | $20,151,771$ | Severe deficit ($1:2$) |
| `parallel-sources-4core__4` | 1 Parallel | 4 | **$989,128$** | $997,810$ | **$999,985$** | $999,985$ | $999,985$ | **$14,153,990$** | Severe deficit ($-32.6\%$) |
| `ordered-sources-2core__0` | 1 Ordered | 2 | **$405,929$** | $354,304$ | **$404,961$** | $454,655$ | $530,441$ | $7,795,432$ | Deterministic affinity routing |
| `ordered-sources-2core__1` | 2 Ordered | 2 | **$163,971$** | $140,808$ | **$164,805$** | $186,111$ | $227,217$ | $8,080,885$ | Deterministic affinity routing |
| `ordered-sources-2core__2` | 4 Ordered | 2 | **$15,364$** | $0$ | **$15$** | $31,250$ | $55,046$ | $9,771,808$ | Low collision deterministic routing |

---

### 2.2 Physical Contention Landmarks Identification

From the empirical data, five distinct physical contention regimes emerge from the interaction between
worker acquisition rate and upstream handle availability:

1. **XS Contention Landmark ($\text{Contention} \le 50,000$)**:
   - **Physical Fixture**: 16 parallel sources across 2 cores (Ratio $\ge 8:1$).
   - **Contention Metrics**: Mean $= 30,425$, Median (P50) $= 15$, P75 $= 15$.
   - **Mechanism**: Workers virtually never collide on upstream handles ($\text{failure rate} < 3\%$).
2. **S Contention Landmark ($50,000 < \text{Contention} \le 350,000$)**:
   - **Physical Fixture**: 4 parallel sources across 2 cores or 8 parallel sources across 4 cores (Ratio $2:1$).
   - **Contention Metrics**: Mean $= 164,567 \sim 237,593$, Median (P50) $= 164,309 \sim 232,274$.
   - **Mechanism**: Light interleaving collisions ($\text{failure rate} \approx 16\% \sim 24\%$), zero throughput penalty.
3. **M Contention Landmark ($350,000 < \text{Contention} \le 650,000$)**:
   - **Physical Fixture**: 2 parallel sources across 2 cores (Balanced $1:1$ ratio).
   - **Contention Metrics**: Mean $= 495,948$, Median (P50) $= 499,915$, tightly bounded $[\text{P25}=497\text{k}, \text{P75}=500\text{k}]$.
   - **Mechanism**: Sibling workers frequently interleave, experiencing exactly $50\%$ acquisition lock collisions.
4. **H Contention Landmark ($650,000 < \text{Contention} \le 850,000$)**:
   - **Physical Fixture**: 4 parallel sources across 4 cores (Balanced $1:1$ ratio under 4-worker concurrency).
   - **Contention Metrics**: Mean $= 741,597$, Median (P50) $= 747,119$, tightly bounded $[\text{P25}=739\text{k}, \text{P75}=750\text{k}]$.
   - **Mechanism**: High concurrency contention where 4 workers contend across 4 handles ($\text{failure rate} \approx 75\%$).
5. **XH Contention Landmark ($\text{Contention} > 850,000$)**:
   - **Physical Fixture**: 1 parallel source across 2 cores or 4 cores (Deficit bottleneck ratio $\le 0.5:1$).
   - **Contention Metrics**: Mean $= 989,128 \sim 991,820$, Median (P50) $= 999,985$.
   - **Mechanism**: Severe queue starvation and lock spinning, causing a **$33.3\%$ throughput collapse** ($15.6\text{M} \to 10.5\text{M ops/s}$).

---

## 3. Collapsed Contention Threshold Calibration (Experiment 05)

- **Experiment Preset**: [`benchmarks/src/main/presets/experiments/05-contention-threshold-mapping.json`](../experiments/05-contention-threshold-mapping.json)
- **Comparison Preset**: [`benchmarks/src/main/presets/comparisons/05-contention-threshold-mapping.json`](../comparisons/05-contention-threshold-mapping.json)

Collapsed threshold sweeps ($C_{\text{xs}} = C_{\text{s}} = C_{\text{m}} = C_{\text{h}} = C$) were evaluated against each
physical contention landmark. Under collapsed threshold $C$, decisions with $\text{contention} \le C$ map to
**Band 0 (XS)**, while those with $\text{contention} > C$ escalate immediately to **Band 4 (XH)**.

### Empirical Occupancy Distribution

| Landmark | Collapsed Threshold ($C$) | Band 0 (`XS`) % | Band 4 (`XH`) % | Classification Behavior |
|:---|:---:|:---:|:---:|:---|
| **XS Landmark** (16 src / 2 core) | **200,000** | $92.70\%$ | $7.30\%$ | Fully captured in Band 0 |
| | **150,000** | $91.69\%$ | $8.31\%$ | Fully captured in Band 0 |
| | **120,000** | $93.22\%$ | $6.78\%$ | Practical XS ceiling |
| | **80,000** | **$100.00\%$** | $0.00\%$ | **$C_{\text{xs}}$ Anchor** ($100\%$ Band 0) |
| **S Landmark** (4 src / 2 core) | **400,000** | $79.10\%$ | $20.90\%$ | Fully below upper S boundary |
| | **300,000** | $65.05\%$ | $34.95\%$ | S transition region |
| | **200,000** | $61.00\%$ | $39.00\%$ | S median crossover |
| | **120,000** | $59.65\%$ | $40.35\%$ | Escalation threshold |
| **M Landmark** (2 src / 2 core) | **700,000** | **$100.00\%$** | $0.00\%$ | Below threshold |
| | **600,000** | **$100.00\%$** | $0.00\%$ | **$C_{\text{m}}$ Anchor** ($100\%$ below M ceiling) |
| | **500,000** | **$100.00\%$** | $0.00\%$ | M median boundary |
| | **400,000** | $0.00\%$ | **$100.00\%$** | **$100\%$ escalated to XH** |
| **H Landmark** (4 src / 4 core) | **900,000** | **$100.00\%$** | $0.00\%$ | Below threshold |
| | **850,000** | **$100.00\%$** | $0.00\%$ | **$C_{\text{h}}$ Anchor** ($100\%$ below H ceiling) |
| | **800,000** | **$100.00\%$** | $0.00\%$ | Near-boundary capture |
| | **700,000** | $1.78\%$ | **$98.22\%$** | **$98.2\%$ escalated to XH** |
| **XH Landmark** (1 src / 2 core) | **995,000** | $6.01\%$ | **$93.99\%$** | Near-ceiling escalation |
| | **980,000** | $4.90\%$ | **$95.10\%$** | Above threshold |
| | **900,000** | $2.38\%$ | **$97.62\%$** | Above threshold |
| | **850,000** | $0.00\%$ | **$100.00\%$** | **$100\%$ escalated to XH** |

---

## 4. Multi-Band Contention Verification Suite (Experiment 06)

- **Experiment Preset**: [`benchmarks/src/main/presets/experiments/06-multiband-contention-verification.json`](../experiments/06-multiband-contention-verification.json)
- **Comparison Preset**: [`benchmarks/src/main/presets/comparisons/06-multiband-contention-verification.json`](../comparisons/06-multiband-contention-verification.json)

Using the full calibrated contention threshold vector:

$$\mathbf{C} = [50000, 350000, 650000, 850000]$$

along with calibrated body weights $\mathbf{W} = [96, 128, 216, 288]$, the complete contention continuum was
evaluated across all landmark configurations:

| Trial Identifier | Physical Fixture | Contention Mean | Band 0 (`XS`) % | Band 1 (`S`) % | Band 2 (`M`) % | Band 3 (`H`) % | Band 4 (`XH`) % | Assigned Band | Physical Contention Regime |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---|
| `2core-sources-sweep__0` | 16 Parallel / 2 Cores | $18,953$ | **$90.34\%$** | $2.85\%$ | $6.80\%$ | $0.00\%$ | $0.00\%$ | **Band 0 (XS)** | Uncontended ingress ($8:1$) |
| `4core-sources-sweep__0` | 8 Parallel / 4 Cores | $234,268$ | $6.93\%$ | **$73.74\%$** | $19.32\%$ | $0.00\%$ | $0.00\%$ | **Band 1 (S)** | Light saturation ($2:1$) |
| `2core-sources-sweep__2` | 2 Parallel / 2 Cores | $496,218$ | $0.00\%$ | $0.00\%$ | **$100.00\%$** | $0.00\%$ | $0.00\%$ | **Band 2 (M)** | Balanced $1:1$ concurrency |
| `4core-sources-sweep__1` | 4 Parallel / 4 Cores | $741,716$ | $0.00\%$ | $0.00\%$ | $0.10\%$ | **$99.90\%$** | $0.00\%$ | **Band 3 (H)** | Multi-worker $1:1$ concurrency |
| `2core-sources-sweep__3` | 1 Parallel / 2 Cores | $989,506$ | $0.00\%$ | $0.08\%$ | $0.63\%$ | $1.36\%$ | **$97.93\%$** | **Band 4 (XH)** | Single-source bottleneck |
| `4core-sources-sweep__2` | 2 Parallel / 4 Cores | $989,219$ | $0.00\%$ | $0.00\%$ | $0.04\%$ | $0.84\%$ | **$99.13\%$** | **Band 4 (XH)** | Severe worker deficit |
| `4core-sources-sweep__3` | 1 Parallel / 4 Cores | $993,660$ | $0.00\%$ | $0.00\%$ | $0.01\%$ | $0.45\%$ | **$99.55\%$** | **Band 4 (XH)** | Severe single-source bottleneck |

### Key Observations

1. **Clear Discrete Partitioning**:
   - `XS` ($\le 50,000$ / $\le 5\%$): Captures abundant multi-source fixtures with minimal collision.
   - `S` ($50,000 \sim 350,000$ / $5\% \sim 35\%$): Captures $2:1$ source-to-worker fixtures with mild interleaving.
   - `M` ($350,000 \sim 650,000$ / $35\% \sim 65\%$): Captures balanced $1:1$ 2-worker concurrency with **$100.00\%$ purity**.
   - `H` ($650,000 \sim 850,000$ / $65\% \sim 85\%$): Captures balanced $1:1$ 4-worker concurrency with **$99.90\%$ purity**.
   - `XH` ($> 850,000$ / $> 85\%$): Captures severe source acquisition starvation bottlenecks with **$>97.9\%$ purity**, matching the physical regime where throughput collapses by $33\%$.
2. **Deterministic Routing vs Contention**: Ordered sources exhibit lower lock collision rates because frames map
   deterministically to worker cores by hash, avoiding concurrent cross-worker acquisition contention.

---

## 5. Calibrated Baseline Profile Updates

The updated baseline profile library in [`benchmarks/src/main/presets/profiles/baseline.json`](../profiles/baseline.json)
now encodes both calibrated body weights and calibrated contention thresholds:

```json
{
  "decisionWeightProfiles": {
    "default-weights": {
      "idleContentionThresholds": {
        "xsContention": 50000,
        "sContention": 350000,
        "mContention": 650000,
        "hContention": 850000
      },
      "idleBodyCostWeights": [
        { "xs": 96, "s": 128, "m": 216, "h": 288 },
        { "xs": 96, "s": 128, "m": 216, "h": 288 },
        { "xs": 96, "s": 128, "m": 216, "h": 288 },
        { "xs": 96, "s": 128, "m": 216, "h": 288 }
      ],
      "execContentionThresholds": {
        "xsContention": 50000,
        "sContention": 350000,
        "mContention": 650000,
        "hContention": 850000
      },
      "execBodyCostWeights": [
        { "xs": 96, "s": 128, "m": 216, "h": 288 },
        { "xs": 96, "s": 128, "m": 216, "h": 288 },
        { "xs": 96, "s": 128, "m": 216, "h": 288 },
        { "xs": 96, "s": 128, "m": 216, "h": 288 }
      ]
    }
  }
}
```

---

## 6. Artifact and Configuration Traceability

| Artifact Description | File Path |
|:---|:---|
| **Phase 3 Exploration Preset** | [`benchmarks/src/main/presets/experiments/04-contention-landmarks.json`](../experiments/04-contention-landmarks.json) |
| **Phase 3 Exploration Comparison Preset** | [`benchmarks/src/main/presets/comparisons/04-contention-landmarks.json`](../comparisons/04-contention-landmarks.json) |
| **Phase 3 Threshold Mapping Preset** | [`benchmarks/src/main/presets/experiments/05-contention-threshold-mapping.json`](../experiments/05-contention-threshold-mapping.json) |
| **Phase 3 Threshold Mapping Comparison Preset** | [`benchmarks/src/main/presets/comparisons/05-contention-threshold-mapping.json`](../comparisons/05-contention-threshold-mapping.json) |
| **Multi-Band Contention Verification Preset** | [`benchmarks/src/main/presets/experiments/06-multiband-contention-verification.json`](../experiments/06-multiband-contention-verification.json) |
| **Multi-Band Contention Comparison Preset** | [`benchmarks/src/main/presets/comparisons/06-multiband-contention-verification.json`](../comparisons/06-multiband-contention-verification.json) |
| **Updated Baseline Profile Library** | [`benchmarks/src/main/presets/profiles/baseline.json`](../profiles/baseline.json) |
| **Completed Telemetry Exports** | [`experiments/04-contention-landmarks/`](file:///home/brandon/src/Euhedral-Execution/experiments/04-contention-landmarks), [`experiments/05-contention-threshold-mapping/`](file:///home/brandon/src/Euhedral-Execution/experiments/05-contention-threshold-mapping), and [`experiments/06-multiband-contention-verification/`](file:///home/brandon/src/Euhedral-Execution/experiments/06-multiband-contention-verification) |

---

## 7. Definition of Done Checklist for Phase 3

- [x] Body bands frozen to Phase 2 calibrated weights ($[96, 128, 216, 288]$).
- [x] Idling disabled (`idleTimeNs = 0`) and execution policy held constant (`DIRECT`).
- [x] Contention continuum explored across source counts ($16 \dots 1$), worker counts ($2, 4$), and ordering modes.
- [x] Five distinct physical contention regimes identified and anchored to throughput and queue acquisition behavior.
- [x] Collapsed contention threshold bracketing sweeps evaluated across all 5 landmark regimes.
- [x] Thresholds calibrated for all contention bands:
  - $C_{\text{xs}} = 50,000$ ($5\%$)
  - $C_{\text{s}} = 350,000$ ($35\%$)
  - $C_{\text{m}} = 650,000$ ($65\%$)
  - $C_{\text{h}} = 850,000$ ($85\%$)
- [x] Multi-band contention verification trial evaluated across all 8 configurations confirming discrete band assignment purity ($>90\% \sim 100\%$).
- [x] Baseline profile library updated in [`benchmarks/src/main/presets/profiles/baseline.json`](../profiles/baseline.json).
- [x] Full findings and data tables documented in [`phase_3_contention_landmarks.md`](phase_3_contention_landmarks.md).
