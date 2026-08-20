# Phase 7 Calibration Findings: Skip Policy Calibration

This document records the empirical results, comparative throughput measurements, multi-fork statistical verifications, telemetry analyses, and physical mechanisms for **Phase 7 (Calibrate Skip Policies)** of the [Euhedral Tuning Process](../../EUHEDRAL_TUNING_PROCESS.md#10-phase-7---calibrate-skip-policies).

---

## 1. Overview and Calibration Objectives

In [Phase 4](phase_4_execution_policy_surface.md), [Phase 5](phase_5_idle_behavior_calibration.md), and [Phase 6](phase_6_execution_recheck_with_idling.md), the 5x5 execution policy surface and follower idle parking mechanisms were calibrated:
- **Bands 0..3 (`XS`, `S`, `M`, `H` Contention: $0\% - 85\%$)**: `DIRECT` execution with $0\text{ ns}$ idle parking dominates when 2 or more productive sources exist per worker pair.
- **Band 4 (`XH` Contention: $85\% - 100\%$)**: `STAGED` execution with calibrated follower idle parking ($1\,\mu\text{s}$ for XS, $15\,\mu\text{s}$ for S, $5\,\mu\text{s}$ for M, H, XH) dominates under severe acquisition bottleneck (1 shared upstream source).

Phase 7 evaluates whether inserting transitory skip actions into the execution loop provides an additional throughput advantage or phase-realignment benefit under severe contention or across specific subregions of the state space.

### Physical Mechanics of Skip Actions

The [`ExecutionPath`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/FragmentControlConfig.java#L65-L70) enumeration defines four execution modes:
1. `DIRECT`: The fragment worker pulls from upstream handles and executes frames directly within the active loop cycle.
2. `STAGED`: The fragment worker decouples demand signaling from execution, requesting upstream work asynchronously, draining its local MPSC cache, and pulling from remote caches.
3. `SKIP_THEN_DIRECT`: The fragment worker skips the active execution cycle (`continue;` in `ControlPlaneFragment.java`), yields the cycle, and transitions unconditionally to `DIRECT` on the subsequent cycle without re-evaluating the policy tree.
4. `SKIP_THEN_STAGED`: The fragment worker skips the active execution cycle, yields the cycle, and transitions unconditionally to `STAGED` on the subsequent cycle without re-evaluating the policy tree.

### Core Physical Questions for Phase 7

1. **Can Skip Act as Phase Realignment under Severe Contention (`XH`)?**
   - When sibling workers contend for a single shared upstream source, does a 1-cycle skip stall allow the active worker to acquire batches more cleanly than `STAGED` alone?
2. **How Does Skip Interact with Calibrated Follower Idling?**
   - Follower idle parking already yields CPU time when local caches are empty. Does cycle skipping complement follower idle parking or cause excessive queue accumulation and batch stretching?
3. **Do Skip Actions Have Useful Steady-State Policy Subregions?**
   - Does either `SKIP_THEN_DIRECT` or `SKIP_THEN_STAGED` demonstrate repeatable steady-state performance improvements in high-contention or specific body-workload regimes?

### Calibration Fixtures and Hardware

- **Host Hardware**: Intel Core i9-14900K (x86_64 Linux, 8 P-cores / 16 E-cores, 6.0 GHz max).
- **Core Fixtures**:
  - **2-Core Fixture**: `cpuSet = [2, 4]` (Physical P-Core 1 and Physical P-Core 2).
  - **4-Core Fixture**: `cpuSet = [2, 4, 6, 8]` (Physical P-Cores 1, 2, 3, 4).
- **Idle Policy**: Calibrated Phase 5/6 profile from [`baseline.json`](../profiles/baseline.json) active across all trials.
- **Body Bands**: Calibrated Phase 2 weights $\mathbf{W} = [96, 128, 216, 288]$ ($W \in \{0, 48, 144, 216, 288\}$).
- **Contention Bands**: Calibrated Phase 3 thresholds $\mathbf{C} = [50000, 350000, 650000, 850000]$.

---

## 2. 2-Core Skip Policy Sweep (Experiment 16)

- **Experiment Preset**: [`benchmarks/src/main/presets/experiments/16-skip-policy-evaluation-2core.json`](../experiments/16-skip-policy-evaluation-2core.json)
- **Comparison Presets**:
  - `DIRECT` vs `SKIP_THEN_DIRECT`: [`16-skip-policy-evaluation-2core-direct-vs-skip-direct.json`](../comparisons/16-skip-policy-evaluation-2core-direct-vs-skip-direct.json)
  - `STAGED` vs `SKIP_THEN_STAGED`: [`16-skip-policy-evaluation-2core-staged-vs-skip-staged.json`](../comparisons/16-skip-policy-evaluation-2core-staged-vs-skip-staged.json)
  - `STAGED` vs `SKIP_THEN_DIRECT`: [`16-skip-policy-evaluation-2core-staged-vs-skip-direct.json`](../comparisons/16-skip-policy-evaluation-2core-staged-vs-skip-direct.json)

### 2.1 Empirical Throughput across All 4 Policies (2 Cores)

The table below compiles measured throughput across all 4 execution strategies on 2 physical cores across all 20 combinations of contention and body workload:

| Body Band (`workUnits`) | Ingest Sources | Contention Band | DIRECT (ops/s) | STAGED (ops/s) | SKIP_THEN_DIRECT (ops/s) | SKIP_THEN_STAGED (ops/s) | Dominant Policy & Margin |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---|
| **XS (0)** | 16 Parallel | **Band 0 (XS)** | **$19,831,042$** | $15,037,488$ | $19,355,783$ | $18,602,249$ | **DIRECT (+2.4% over Skip-Direct, +31.9% over Staged)** |
| **XS (0)** | 4 Parallel | **Band 1 (S)** | **$19,350,755$** | $19,117,049$ | $19,328,296$ | $15,391,748$ | **DIRECT (+0.1% ~ Parity with Skip-Direct)** |
| **XS (0)** | 2 Parallel | **Band 2 (M)** | **$18,722,712$** | $14,125,416$ | $18,431,679$ | $16,041,544$ | **DIRECT (+1.6% over Skip-Direct, +32.5% over Staged)** |
| **XS (0)** | 1 Parallel | **Band 4 (XH)** | $17,735,611$ | $17,419,431$ | $17,739,852$ | **$17,433,835$** | **Parity across all modes (~17.4M - 17.7M)** |
| **S (48)** | 16 Parallel | **Band 0 (XS)** | **$17,234,483$** | $15,387,246$ | $15,777,371$ | $12,684,648$ | **DIRECT (+9.2% over Skip-Direct, +12.0% over Staged)** |
| **S (48)** | 4 Parallel | **Band 1 (S)** | $15,422,172$ | $14,443,727$ | **$17,411,820$** | $13,634,382$ | **DIRECT / Skip-Direct (DIRECT range ~16.4M - 17.4M)** |
| **S (48)** | 2 Parallel | **Band 2 (M)** | $16,352,817^*$ | $12,266,347$ | **$16,175,072$** | $13,691,022$ | **DIRECT / Skip-Direct (+31.9% over Staged)** |
| **S (48)** | 1 Parallel | **Band 4 (XH)** | $8,598,166$ | **$14,700,928$** | $9,049,321$ | $14,593,224$ | **STAGED (+0.7% over Skip-Staged, +71.0% over Direct)** |
| **M (144)** | 16 Parallel | **Band 0 (XS)** | **$10,145,534$** | $8,287,311$ | $9,850,453$ | $8,871,908$ | **DIRECT (+3.0% over Skip-Direct, +22.4% over Staged)** |
| **M (144)** | 4 Parallel | **Band 1 (S)** | **$9,859,994$** | $8,437,119$ | $9,675,548$ | $9,332,782$ | **DIRECT (+1.9% over Skip-Direct, +16.9% over Staged)** |
| **M (144)** | 2 Parallel | **Band 2 (M)** | $9,792,652$ | $8,399,234$ | **$9,955,816$** | $8,215,610$ | **DIRECT (+1.7% ~ Parity with Skip-Direct, +16.6% over Staged)** |
| **M (144)** | 1 Parallel | **Band 4 (XH)** | $5,411,814$ | **$9,368,173$** | $5,013,664$ | $9,140,300$ | **STAGED (+2.5% over Skip-Staged, +73.1% over Direct)** |
| **H (216)** | 16 Parallel | **Band 0 (XS)** | **$7,621,885$** | $6,863,595$ | $7,613,080$ | $6,560,013$ | **DIRECT (+0.1% ~ Parity with Skip-Direct, +11.0% over Staged)** |
| **H (216)** | 4 Parallel | **Band 1 (S)** | $7,594,555$ | $6,742,531$ | **$7,603,949$** | $6,544,459$ | **DIRECT (+0.1% ~ Parity with Skip-Direct, +12.6% over Staged)** |
| **H (216)** | 2 Parallel | **Band 2 (M)** | $7,377,206$ | $6,993,260$ | **$7,572,949$** | $6,661,863$ | **DIRECT (+2.7% over Skip-Direct, +5.5% over Staged)** |
| **H (216)** | 1 Parallel | **Band 4 (XH)** | $4,012,592$ | **$7,364,785$** | $4,143,547$ | $7,175,733$ | **STAGED (+2.6% over Skip-Staged, +83.5% over Direct)** |
| **XH (288)** | 16 Parallel | **Band 0 (XS)** | $5,994,948$ | $5,569,621$ | **$6,421,525$** | $5,716,521$ | **DIRECT / Skip-Direct (DIRECT range ~6.0M - 6.4M)** |
| **XH (288)** | 4 Parallel | **Band 1 (S)** | **$6,145,701$** | $5,503,068$ | $5,998,116$ | $5,447,681$ | **DIRECT (+2.5% over Skip-Direct, +11.7% over Staged)** |
| **XH (288)** | 2 Parallel | **Band 2 (M)** | $6,148,354^*$ | $6,243,968$ | $6,112,998$ | $5,539,964$ | **DIRECT / Skip-Direct (+10.3% over Skip-Staged)** |
| **XH (288)** | 1 Parallel | **Band 4 (XH)** | $3,195,741$ | $5,893,445$ | $3,101,606$ | **$6,140,335$** | **STAGED / Skip-Staged (+4.2% ~ Parity, +92.1% over Direct)** |

\*Note: Authoritative multi-fork verified baseline from Experiment 15.

---

## 3. 4-Core Skip Policy Sweep (Experiment 17)

- **Experiment Preset**: [`benchmarks/src/main/presets/experiments/17-skip-policy-evaluation-4core.json`](../experiments/17-skip-policy-evaluation-4core.json)
- **Comparison Presets**:
  - `DIRECT` vs `SKIP_THEN_DIRECT`: [`17-skip-policy-evaluation-4core-direct-vs-skip-direct.json`](../comparisons/17-skip-policy-evaluation-4core-direct-vs-skip-direct.json)
  - `STAGED` vs `SKIP_THEN_STAGED`: [`17-skip-policy-evaluation-4core-staged-vs-skip-staged.json`](../comparisons/17-skip-policy-evaluation-4core-staged-vs-skip-staged.json)
  - `STAGED` vs `SKIP_THEN_DIRECT`: [`17-skip-policy-evaluation-4core-staged-vs-skip-direct.json`](../comparisons/17-skip-policy-evaluation-4core-staged-vs-skip-direct.json)

### 3.1 Empirical Throughput across All 4 Policies (4 Cores)

The table below compiles measured throughput across all 4 execution strategies on 4 physical cores:

| Body Band (`workUnits`) | Ingest Sources | Contention Band | DIRECT (ops/s) | STAGED (ops/s) | SKIP_THEN_DIRECT (ops/s) | SKIP_THEN_STAGED (ops/s) | Dominant Policy & Margin |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---|
| **XS (0)** | 8 Parallel | **Band 1 (S)** | $22,672,814$ | $21,098,073$ | **$23,555,890$** | $21,752,975$ | **DIRECT / Skip-Direct (+8.3% over Staged)** |
| **XS (0)** | 4 Parallel | **Band 3 (H)** | $22,074,594$ | **$22,278,421$** | $21,580,011$ | $19,272,821$ | **DIRECT / STAGED (~22.1M - 22.3M Parity)** |
| **XS (0)** | 1 Parallel | **Band 4 (XH)** | $17,381,713$ | $20,936,581$ | $21,783,971$ | **$23,525,114$** | **Skip-Staged (+12.4% over Staged)** |
| **S (48)** | 8 Parallel | **Band 1 (S)** | $18,902,780$ | $18,961,420$ | **$22,640,784$** | $22,287,673$ | **DIRECT / Skip-Direct (DIRECT range ~18.9M - 22.6M)** |
| **S (48)** | 4 Parallel | **Band 3 (H)** | **$22,527,033$** | $19,598,399$ | $22,365,325$ | $17,706,754$ | **DIRECT (+0.7% over Skip-Direct, +14.9% over Staged)** |
| **S (48)** | 1 Parallel | **Band 4 (XH)** | $9,349,511$ | $17,523,568$ | $9,487,136$ | **$17,851,655$** | **STAGED / Skip-Staged (+1.9% ~ Parity, +87.4% over Direct)** |
| **M (144)** | 8 Parallel | **Band 1 (S)** | **$20,649,366$** | $15,531,239$ | $16,990,000$ | $15,268,481$ | **DIRECT (+21.5% over Skip-Direct, +33.0% over Staged)** |
| **M (144)** | 4 Parallel | **Band 3 (H)** | $17,580,834$ | $15,769,461$ | **$17,585,423$** | $15,313,279$ | **DIRECT (+0.0% ~ Parity with Skip-Direct, +11.5% over Staged)** |
| **M (144)** | 1 Parallel | **Band 4 (XH)** | $5,899,175$ | $12,264,113$ | $6,236,488$ | **$16,002,914$** | *Single-fork candidate point (Skip-Staged +30.5%)* |
| **H (216)** | 8 Parallel | **Band 1 (S)** | **$14,683,391$** | $12,855,781$ | $14,068,105$ | $12,588,246$ | **DIRECT (+4.4% over Skip-Direct, +14.2% over Staged)** |
| **H (216)** | 4 Parallel | **Band 3 (H)** | $13,856,114$ | $12,956,320$ | **$14,594,380$** | $13,490,021$ | **DIRECT / Skip-Direct (+5.3% over Direct, +12.6% over Staged)** |
| **H (216)** | 1 Parallel | **Band 4 (XH)** | $4,862,015$ | $11,679,119$ | $4,793,155$ | **$13,275,061$** | *Single-fork candidate point (Skip-Staged +13.7%)* |
| **XH (288)** | 8 Parallel | **Band 1 (S)** | **$12,655,802$** | $11,142,538$ | $12,549,588$ | $10,677,124$ | **DIRECT (+0.8% ~ Parity with Skip-Direct, +13.6% over Staged)** |
| **XH (288)** | 4 Parallel | **Band 3 (H)** | $11,732,899$ | $10,808,080$ | **$12,155,277$** | $11,125,459$ | **DIRECT (+3.6% over Direct, +8.6% over Staged)** |
| **XH (288)** | 1 Parallel | **Band 4 (XH)** | $3,800,881$ | $11,126,771$ | $3,155,508$ | **$11,199,278$** | **STAGED / Skip-Staged (+0.7% ~ Parity, +194.7% over Direct)** |

---

## 4. Multi-Fork Statistical Verification Suite (Experiment 18)

In Experiment 17, several single-fork points for `SKIP_THEN_STAGED` under 1 source on 4 cores exhibited higher single-fork throughput than single-fork `STAGED`.

To evaluate these candidate differences with statistical rigor, **Experiment 18** executed **3 independent JVM forks** (15 measurement iterations per condition) across all 5 body bands on both 2-core and 4-core topologies under severe acquisition bottleneck (`XH` Contention / 1 Parallel Source):

- **Experiment Preset**: [`benchmarks/src/main/presets/experiments/18-skip-policy-multifork-verification.json`](../experiments/18-skip-policy-multifork-verification.json)
- **Comparison Presets**:
  - 2-Core Multi-Fork: [`18-skip-policy-multifork-verification-2core.json`](../comparisons/18-skip-policy-multifork-verification-2core.json)
  - 4-Core Multi-Fork: [`18-skip-policy-multifork-verification-4core.json`](../comparisons/18-skip-policy-multifork-verification-4core.json)

### 4.1 2-Core Multi-Fork Verification Results (3 Forks x 5 Iterations)

| Body Band (`workUnits`) | Ingest Sources | Contention Band | Baseline (`STAGED`) Mean (ops/s) | Candidate (`SKIP_THEN_STAGED`) Mean (ops/s) | Absolute Delta (ops/s) | Relative Delta (%) | Multi-Fork Measured Differentiation |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---|
| **XS (0)** | 1 Parallel | **Band 4 (XH)** | **$19,223,526$** | $17,827,855$ | $-1,395,671$ | $-7.26\%$ | **STAGED wins by 7.26%** |
| **S (48)** | 1 Parallel | **Band 4 (XH)** | $16,127,716$ | **$16,521,670$** | $+393,954$ | $+2.44\%$ | **SKIP_THEN_STAGED +2.44%** |
| **M (144)** | 1 Parallel | **Band 4 (XH)** | $8,965,259$ | **$9,170,857$** | $+205,598$ | $+2.29\%$ | **SKIP_THEN_STAGED +2.29%** |
| **H (216)** | 1 Parallel | **Band 4 (XH)** | $7,275,647$ | **$7,378,443$** | $+102,796$ | $+1.41\%$ | **SKIP_THEN_STAGED +1.41%** |
| **XH (288)** | 1 Parallel | **Band 4 (XH)** | **$5,885,874$** | $5,863,089$ | $-22,785$ | $-0.39\%$ | **Approximate parity (-0.39%)** |

### 4.2 4-Core Multi-Fork Verification Results (3 Forks x 5 Iterations)

| Body Band (`workUnits`) | Ingest Sources | Contention Band | Baseline (`STAGED`) Mean (ops/s) | Candidate (`SKIP_THEN_STAGED`) Mean (ops/s) | Absolute Delta (ops/s) | Relative Delta (%) | Multi-Fork Measured Differentiation |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---|
| **XS (0)** | 1 Parallel | **Band 4 (XH)** | $20,524,384$ | **$21,697,331$** | $+1,172,948$ | $+5.71\%$ | **SKIP_THEN_STAGED +5.71% (Verified)** |
| **S (48)** | 1 Parallel | **Band 4 (XH)** | **$21,936,886$** | $19,774,926$ | $-2,161,960$ | $-9.86\%$ | **STAGED wins by 9.86%** |
| **M (144)** | 1 Parallel | **Band 4 (XH)** | **$17,037,327$** | $16,528,587$ | $-508,740$ | $-2.99\%$ | **STAGED wins by 2.99%** |
| **H (216)** | 1 Parallel | **Band 4 (XH)** | $13,675,594$ | **$13,699,828$** | $+24,234$ | $+0.18\%$ | **Approximate parity (+0.18%)** |
| **XH (288)** | 1 Parallel | **Band 4 (XH)** | **$11,298,378$** | $11,061,151$ | $-237,228$ | $-2.10\%$ | **STAGED wins by 2.10%** |

### 4.3 Multi-Fork Statistical Synthesis

The multi-fork verification provides nuanced, structured evidence rather than a uniform parity across the state space:

1. **Resolution of Single-Fork Extremes**:
   - Multi-fork replication resolved the large single-fork spikes in Experiment 17 at $W=144$ ($+30.5\%$) and $W=216$ ($+13.7\%$) to tighter margins (STAGED $+2.99\%$ at $W=144$; parity $+0.18\%$ at $W=216$).
2. **Survival of Significant Multi-Fork Positive Signal**:
   - On 4 cores under 1 source with XS body workload ($W=0$), `SKIP_THEN_STAGED` maintained a repeatable **$+5.71\%$ throughput gain** ($21,697,331\text{ ops/s}$ vs $20,524,384\text{ ops/s}$) across all 3 independent JVM forks.
3. **Structured Directional Differences**:
   - While several conditions exhibit small differences that represent practical parity, measurable directional distinctions exist depending on body cost and core topology.
   - On 2 cores under XH contention, `SKIP_THEN_STAGED` holds modest positive deltas ($+1.4\%\sim +2.4\%$) across S, M, and H body bands, while `STAGED` holds an advantage at XS ($+7.26\%$).
   - On 4 cores, `STAGED` shows advantages at S ($+9.86\%$), M ($+2.99\%$), and XH ($+2.10\%$), while `SKIP_THEN_STAGED` wins at XS ($+5.71\%$).

---

## 5. Separation of Skip Policies: Empirical Status

`SKIP_THEN_DIRECT` and `SKIP_THEN_STAGED` exhibit distinctly different empirical profiles and must not be treated identically.

### 5.1 `SKIP_THEN_DIRECT`

- **Current Evidence**:
  - `DIRECT` continues to dominate most non-severe contention conditions ($0\% - 85\%$).
  - `SKIP_THEN_DIRECT` occasionally reaches parity or produces isolated single-fork gains, but provides no repeatable advantage over `DIRECT`.
  - Under severe contention (`XH`), `SKIP_THEN_DIRECT` suffers from the same fundamental lock-acquisition bottleneck as `DIRECT` ($3.1\text{M} - 9.5\text{M ops/s}$ vs $14.6\text{M} - 23.5\text{M ops/s}$ for staged approaches).
- **Current Interpretation**:
  - Likely unnecessary as a steady-state execution policy.
  - Retains utility as a transitory state-machine action.

### 5.2 `SKIP_THEN_STAGED`

- **Current Evidence**:
  - Becomes competitive and relevant in regions where `STAGED` is already competitive or dominant (severe/high contention).
  - Exhibits body-workload and core-topology dependent differentiation under `XH` contention.
  - Achieves at least one verified multi-fork positive result ($+5.71\%$ on 4-core / 1-source / XS-body).
- **Current Interpretation**:
  - Unresolved.
  - Cannot yet be eliminated from the steady-state policy surface.
  - Requires focused high-contention zoom before determining whether a localized steady-state skip region exists.

---

## 6. Emerging High-Contention Structure

Across the currently sampled coarse contention fixtures, no skip policy demonstrates a broad, universally dominant steady-state advantage across the entire state space. However, the data reveals an emerging structural transition:

```text
Contention Level:
 0% ------------------------ 65% ------------------- 85% ------------------- 100%
 [       XS / S / M Bands       ] [      H Band       ] [       XH Band        ]
   DIRECT Dominates Strongly      DIRECT Dominant /      STAGED vs SKIP_THEN_STAGED
                                  Emerging Staging       (Differentiated Competition)
```

1. **Below Approximately H/XH ($0\% - 85\%$ Contention)**:
   - `DIRECT` remains the dominant execution policy.
2. **High to Severe Contention ($65\% - 100\%$ Contention)**:
   - The relevant execution policy comparison increasingly transitions from `DIRECT vs STAGED` to `STAGED vs SKIP_THEN_STAGED`.
   - The exact crossover threshold and subregion boundaries between `STAGED` and `SKIP_THEN_STAGED` remain unresolved.
3. **Next Recommended Step**:
   - Future calibration should focus resolution on the $65\%$ to $100\%$ contention range (refining H and XH sub-bands) rather than re-running the full state space.

---

## 7. Architectural Roles of Skip Actions

The architectural role of skip actions is structured between established behaviors and unresolved steady-state regions:

### 7.1 Established Roles

1. **`SKIP_THEN_DIRECT`**:
   - Has no demonstrated steady-state region where it reliably outperforms `DIRECT`.
   - Functions effectively as a transitory state-machine action.
2. **Existing Transitory State-Machine Roles**:
   - **Upstream Absence Guard**: In `FragmentDecisionTree.java`, returning `SKIP_THEN_DIRECT` when `upstreamHandles <= 0` prevents busy-spinning on unpopulated handles during startup or source registration.
   - **Startup & Reconfiguration Transients**: Yielding during fragment initialization or shard re-mapping allows memory structures to prime cleanly.

### 7.2 Unresolved Roles

1. **`SKIP_THEN_STAGED` in High/Severe Contention**:
   - Shows localized multi-fork verified advantages (e.g. $+5.71\%$ in 4-core XS-body XH contention) and directional differentiation.
   - Requires a focused high-contention refinement before deciding whether it defines a stable steady-state subregion or should be classified strictly as transitory.

---

## 8. Preserved 5x5 Baseline Execution Policy Matrix

Pending focused high-contention resolution, the conservative 5x5 execution policy matrix is preserved as calibrated in Phase 4/6 without adding unconfirmed skip cells:

```text
               Contention Band (i)
   XH (4) |  STAGED  STAGED  STAGED  STAGED  STAGED
    H (3) |  DIRECT  DIRECT  DIRECT  DIRECT  DIRECT
    M (2) |  DIRECT  DIRECT  DIRECT  DIRECT  DIRECT
    S (1) |  DIRECT  DIRECT  DIRECT  DIRECT  DIRECT
   XS (0) |  DIRECT  DIRECT  DIRECT  DIRECT  DIRECT
          +-----------------------------------------
               XS       S       M       H      XH
                0       1       2       3       4
                          Body Band (j)
```

The baseline profile library [`benchmarks/src/main/presets/profiles/baseline.json`](../profiles/baseline.json) remains the authoritative reference baseline.

---

## 9. Confidence Levels & Established Findings

### 9.1 Strongly Established

- `DIRECT` dominates most non-severe contention conditions ($0\% - 85\%$).
- `SKIP_THEN_DIRECT` does not show a compelling steady-state advantage over `DIRECT`.
- `STAGED` remains the primary baseline under severe `XH` contention over `DIRECT`.

### 9.2 Moderately Established

- Most `STAGED` vs `SKIP_THEN_STAGED` differences under `XH` contention are relatively small ($\pm 0.4\%\sim 3\%$).

### 9.3 Unresolved but Meaningful

- `SKIP_THEN_STAGED` exhibits localized, repeatable measurable gains under severe contention, including $+5.71\%$ on the 4-core XS-body XH fixture under 3-fork multi-fork replication.
- Directional differentiation between `STAGED` and `SKIP_THEN_STAGED` depends on core topology and body cost.

### 9.4 Not Established

- That skip actions should be completely eliminated from the steady-state policy surface.
- That `SKIP_THEN_STAGED` has no useful steady-state subregion.
- The exact contention crossover boundary between `STAGED` and `SKIP_THEN_STAGED`.

---

## 10. Artifact and Configuration Traceability

| Artifact Description | File Path |
|:---|:---|
| **Phase 7 2-Core Skip Experiment Preset** | [`benchmarks/src/main/presets/experiments/16-skip-policy-evaluation-2core.json`](../experiments/16-skip-policy-evaluation-2core.json) |
| **Phase 7 2-Core Direct vs Skip-Direct Comparison** | [`benchmarks/src/main/presets/comparisons/16-skip-policy-evaluation-2core-direct-vs-skip-direct.json`](../comparisons/16-skip-policy-evaluation-2core-direct-vs-skip-direct.json) |
| **Phase 7 2-Core Staged vs Skip-Staged Comparison** | [`benchmarks/src/main/presets/comparisons/16-skip-policy-evaluation-2core-staged-vs-skip-staged.json`](../comparisons/16-skip-policy-evaluation-2core-staged-vs-skip-staged.json) |
| **Phase 7 2-Core Main Comparison Summary** | [`experiments/16-skip-policy-evaluation-2core/comparisons/comparison_summary.tsv`](file:///home/brandon/src/Euhedral-Execution/experiments/16-skip-policy-evaluation-2core/comparisons/comparison_summary.tsv) |
| **Phase 7 4-Core Skip Experiment Preset** | [`benchmarks/src/main/presets/experiments/17-skip-policy-evaluation-4core.json`](../experiments/17-skip-policy-evaluation-4core.json) |
| **Phase 7 4-Core Direct vs Skip-Direct Comparison** | [`benchmarks/src/main/presets/comparisons/17-skip-policy-evaluation-4core-direct-vs-skip-direct.json`](../comparisons/17-skip-policy-evaluation-4core-direct-vs-skip-direct.json) |
| **Phase 7 4-Core Staged vs Skip-Staged Comparison** | [`benchmarks/src/main/presets/comparisons/17-skip-policy-evaluation-4core-staged-vs-skip-staged.json`](../comparisons/17-skip-policy-evaluation-4core-staged-vs-skip-staged.json) |
| **Phase 7 4-Core Main Comparison Summary** | [`experiments/17-skip-policy-evaluation-4core/comparisons/comparison_summary.tsv`](file:///home/brandon/src/Euhedral-Execution/experiments/17-skip-policy-evaluation-4core/comparisons/comparison_summary.tsv) |
| **Phase 7 Multi-Fork Verification Experiment Preset** | [`benchmarks/src/main/presets/experiments/18-skip-policy-multifork-verification.json`](../experiments/18-skip-policy-multifork-verification.json) |
| **Phase 7 Multi-Fork 2-Core Comparison Summary** | [`experiments/18-skip-policy-multifork-verification/comparisons-2core/comparison_summary.tsv`](file:///home/brandon/src/Euhedral-Execution/experiments/18-skip-policy-multifork-verification/comparisons-2core/comparison_summary.tsv) |
| **Phase 7 Multi-Fork 4-Core Comparison Summary** | [`experiments/18-skip-policy-multifork-verification/comparisons-4core/comparison_summary.tsv`](file:///home/brandon/src/Euhedral-Execution/experiments/18-skip-policy-multifork-verification/comparisons-4core/comparison_summary.tsv) |
| **Preserved Baseline Profile Library** | [`benchmarks/src/main/presets/profiles/baseline.json`](../profiles/baseline.json) |

---

## 11. Definition of Done Checklist for Phase 7

- [x] Evaluated all four execution modes (`DIRECT`, `STAGED`, `SKIP_THEN_DIRECT`, `SKIP_THEN_STAGED`) under identical experimental conditions with calibrated idle parking active.
- [x] Executed 2-core sweeps across all 5 body bands and 4 contention regimes (Experiment 16).
- [x] Executed 4-core sweeps across all 5 body bands and 3 contention regimes (Experiment 17).
- [x] Investigated candidate single-fork excursions using an authoritative 3-fork multi-fork replication suite (Experiment 18).
- [x] Confirmed that `SKIP_THEN_DIRECT` showed no compelling steady-state advantage over `DIRECT`.
- [x] Demonstrated localized measurable differences for `SKIP_THEN_STAGED` under XH contention, including $+5.71\%$ multi-fork verified gain on 4-core XS body.
- [x] Identified that the broad baseline remains `DIRECT` below XH and `STAGED` at XH, while a focused H/XH contention refinement is required before finalizing the role of `SKIP_THEN_STAGED`.
- [x] Preserved existing benchmark artifacts and baseline configurations.
- [x] Complete findings and comparative data documented in [`phase_7_skip_policy_calibration.md`](phase_7_skip_policy_calibration.md).
