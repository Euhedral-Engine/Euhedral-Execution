# Step 5: Participation Model Identifiability, Grouped Fitting & Calibration Design Document

## Executive summary and purpose

This document defines the mathematical formulation, statistical identifiability audit, optimization
algorithms, ingestion pipeline repairs, grouped cross-validation strategy, diagnostics, and test
specification for **Step 5 (Coefficient Fitting)** of the Pareto-weight calibration pipeline.

As established in [
`docs/design/productivity-participation-python-training-plan.md`](productivity-participation-python-training-plan.md),
the goal of Step 5 is to fit the CACHE-participation coefficients for the marginal participation
equation:

$$
\begin{aligned} A (c, b, R) &= w_0 + w_1 \cdot c + w_2 \cdot b + w_3 \cdot R \\ B (c, b, R) &= w_4 + w_5 \cdot c + w_6 \cdot b + w_7 \cdot R \\ \text{marginal} (K) &= A (c, b, R) \cdot \frac{P}{K (K-1)} - B (c, b, R)
\end{aligned}
$$

The fitted model predicts whether an active worker at rank $K$ should remain an upstream participant
($\text{marginal} (K) \le 0 \implies \text{DIRECT/STAGED}$) or withdraw into cache execution
($\text{marginal} (K) > 0 \implies \text{CACHE}$).

### Primary design goal and architecture principles

The primary objective of this calibration pipeline is to **prevent producing eight precise-looking
coefficients when the retained physical evidence may only support a lower-dimensional model**.

The architecture enforces:

1. **Phased ingestion and identifiability audit (Phase 0A $\to$ Phase 1 $\to$ Phase 0B)**:
   Authoritative loader repairs precede dataset assembly. Identifiability diagnostics evaluate
   numerical matrix rank, unregularized singular values, condition number, VIFs, class-conditional
   coverage, and separation on cleanly ingested data.
2. **Three-State Artifact Eligibility (ELIGIBLE, INELIGIBLE, UNVERIFIABLE)**: Rigorously verify that
   the manifest-declared training artifacts from experiments 18-32 belong to the intended
   calibration generation and have verifiable evidence. Any manifest-declared input classified as
   `INELIGIBLE` or `UNVERIFIABLE` halts fitting; an unusable zero-weight comparison outside the
   frozen training manifest is reported but does not invalidate unrelated evidence.
3. **Scale Invariance vs Directional Identifiability**: Acknowledge that the runtime decision
   depends strictly on $\operatorname{sign} (\mathbf{x}^T \mathbf{w})$. The declared loss and
   regularization select a numerical magnitude, while operational stability targets decisions and
   coefficient direction in one common, unit-aware reference coordinate system.
4. **End-to-End Algebraic & Evaluator Parity**: Verify the full mathematical chain:
   $\text{score} (K-1) - \text{score} (K) \equiv \text{marginal} (K) \implies \text{decision semantics} \implies \text{Java/Python evaluator parity}$.
5. **Physical family partitioning and bounded family influence**: Observations are partitioned into
   9 physical families using topology, configured source count, and work fixture. Configured source
   count is kept distinct from the measured productive-handle feature $P_i$. Dense prefix sweeps
   receive capped influence ($\operatorname{familyScale} \le 1.0$) without inflating sparse
   families.
6. **Discrete Monotonicity & Optimizer Corner Constraints**: Monotonic participation prefixes
   ($m (K+1) \ge m (K)$) are guaranteed for fixed $(c,b,P,R)$ and discrete $K \ge 2$, and enforced
   over a separately versioned deployment domain via linear corner constraints in scale-normalized
   optimizer coordinates ($\tilde{C}_A \mathbf{w}_{\text{scaled}} \le \mathbf{0}$).
7. **Unclipped Numerically Stable Objective**: Employ exact `logaddexp(0, z) - y*z` formulation and
   `scipy.special.expit` derivative evaluation.
8. **Feature scale normalization without mean centering**: Scale columns using training-fold-only,
   confidence-weighted root-mean-square magnitudes without subtracting means. This preserves the
   direct transformation $\mathbf{w}_{\text{phys}} = S^{-1}\mathbf{w}_{\text{scaled}}$ and the
   linear corner constraints.
9. **Grouped model hierarchy and procedural relative-regret parsimony**: Fit the base model, every
   paired four- and six-parameter physical-axis extension, and the full eight-parameter model.
   Select complexity with an executable rule based on internal LOFO relative throughput regret and
   retained comparison uncertainty, with deterministic tie-breaking.
10. **Decoupled Sign Semantics Testing**: Separate pre-fit dataset-label mapping checks from
    synthetic runtime evaluator sign checks.

---

## Detailed implementation sequence

To guarantee that identifiability diagnostics run only on authoritative data, the pipeline executes
in strict sequential phases:

```
+---------------------------------------------------------------------------------------------------+
| Phased Implementation Sequence                                                                    |
|                                                                                                   |
| Phase 0A: Three-State Artifact Eligibility Pre-Condition Check                                    |
|           - Audit experiments 18-32 for commit, fixture config, executor, topology, telemetry     |
|           - Classify each artifact as ELIGIBLE, INELIGIBLE, or UNVERIFIABLE                       |
|           - Verify SHA-256 checksums across raw logs and manifests                                 |
|           - Freeze the positive-weight/tie input manifest before inspecting Step 5 fits           |
|           - HALT if any manifest-declared input is INELIGIBLE or UNVERIFIABLE                     |
|                                                                                                   |
| Phase 1:  Loader Repairs & Ingestion Correctness                                                  |
|           - Implement balanced sample discovery & pooling (sample_0 + sample_1 -> 4 JVM forks)    |
|           - Extract authoritative physical R (7 or 23) from rank-K staleness telemetry            |
|           - Join only the fixed late region; remove authoritative-path synthetic fallbacks        |
|           - Persist effective outcome and the whole/late evidence basis used for each label       |
|           - Construct clean ingestion manifests and pair records                                  |
|                                                                                                   |
| Phase 0B: Dataset Assembly, Physical Families & Identifiability Audit                             |
|           - Assemble final X / y / v dataset from repaired loader                                 |
|           - Partition into 9 physical families keyed by topology/source fixture/work fixture      |
|           - Apply bounded family influence capping -> compute normalized weights u_i and N_eff    |
|           - Compute unregularized rank, SVD spectrum, condition number, VIFs, coordinate coverage  |
|           - Execute score-to-marginal algebraic parity test                                       |
|           - Execute pre-fit label semantics test (y=0 participate, y=.5 tie, y=1 withdraw)        |
|           - Execute pre-fit runtime evaluator sign test (synthetic m <= 0 vs m > 0)               |
|                                                                                                   |
| Phase 2:  Numerically Stable Loss, Scale Normalization & Scaled Corner Constraints               |
|           - Implement loss.py with unclipped softplus logaddexp and expit derivatives             |
|           - Formulate scaled corner prefix constraints: C_A * S^-1 * w_scaled <= 0                |
|           - Implement training-fold scale normalization S_train and analytical back-transform    |
|           - Validate analytical gradient and Hessian via finite-difference unit tests             |
|                                                                                                   |
| Phase 3:  Grouped Model Hierarchy                                                                 |
|           - Implement nested.py for base, paired 4/6-parameter extensions, and the full model      |
|           - Implement deterministic constrained optimization with explicit convergence checks     |
|                                                                                                   |
| Phase 4:  Grouped Cross-Validation (LOFO) & Procedural Regret Selection                          |
|           - Implement cv.py with 9 physical partition families and bounded family influence      |
|           - Execute LOFO grid search across models and regularization candidates lambda           |
|           - Apply procedural parsimony selection using internal-validation relative regret        |
|           - Resolve equivalent candidates via deterministic tie-breaking rules                    |
|                                                                                                   |
| Phase 5:  Evaluator, Prefix Invariants, Parity & Diagnostics                                      |
|           - Verify Python numeric parity and actual property-enabled Java action parity            |
|           - Verify corner constraints and zero fixed-state reversals on the versioned domain grid  |
|           - Compute common-reference direction, lambda sensitivity, and ablation diagnostics       |
|                                                                                                   |
| Phase 6:  Final Fit, Artifact Serialization & Findings Export                                     |
|           - Fit selected model on full dataset using bounded family influence & corner bounds     |
|           - Export a Step 5 candidate model artifact with full provenance                         |
|           - Generate STEP-5-COEFFICIENT-FITTING-FINDINGS.md with complete audit tables            |
+---------------------------------------------------------------------------------------------------+
```

---

## Phase 0A: Three-state artifact eligibility check

Before fitting, inventory the experiment 18-32 candidate pool, apply the already frozen Step 4 label
rule, and freeze a Step 5 manifest containing every positive-weight decisive or stable-tie pair. Do
not add or remove a row after inspecting a Step 5 fit. Every arm and fork consumed by that manifest
is classified under this **three-state eligibility model**:

```
+---------------------------------------------------------------------------------------------------+
| Three-State Artifact Eligibility Model                                                            |
|                                                                                                   |
| [ELIGIBLE]     Artifact has positive verifiable evidence matching its declared generation:       |
|                - Runtime commit matches the manifest and both arms                                |
|                - No shared completion-counter contention or pseudo-no-op executor overhead        |
|                - Verified worker topology, core pinning, and active-telemetry R in {7, 23}        |
|                - SHA-256 sidecars valid for all ingested logs and manifests                       |
|                                                                                                   |
| [INELIGIBLE]   Artifact contains confirmed superseded fixture defects or invalid configuration.   |
|                                                                                                   |
| [UNVERIFIABLE] Evidence is missing, incomplete, or ambiguous (e.g. absent telemetry sidecars).    |
+---------------------------------------------------------------------------------------------------+
```

> [!IMPORTANT]
> If any arm or fork named by the frozen Step 5 manifest is `INELIGIBLE` or `UNVERIFIABLE`, fitting
> must halt and report the exact pair and evidence gap. It must not silently drop that row. A
> zero-weight or otherwise excluded comparison outside the manifest remains in the inventory with
> its reason; it requires replacement only if the resulting coverage audit cannot support a model.

---

## Phase 1: Ingestion pipeline & loader repairs

Prior to dataset assembly and audit, loader repairs must be executed and verified:

### 1. Balanced sample pooling (`loader.py` & `manifest.py`)

- Full-prefix sweeps (experiments 27-32) contain forward and reverse treatment arms
  (`sample_0_repeat_0` and `sample_1_repeat_0`), each with 2 JMH forks.
- The loader pools all 4 independent forks into a unified `ArmPerformance` record ($n=4$), ensuring
  sample-order balance and robust variance estimation.

The manifest must identify both sample positions for each cutoff. Discovery is keyed by expanded
trial identity, cutoff, sample index, and fork identity; it must not treat measurement iterations as
forks or combine forward/reverse sample directories by filename coincidence.

### 2. Authoritative physical $R$ resolution (`loader.py` & `staleness.py`)

- `PairRecord.registered_workers` and `ActiveStateFeatures.R` are parsed directly from every
  verified rank-$K$ telemetry row used from `contention_staleness.tsv`.
- Confirmed valid physical values: $R=23$ for the `8p16e` topology (23 active workers), $R=7$ for
  the 7-worker slice. Logical CPU set size ($30$) is explicitly rejected.
- Require $R$ to be constant within and across the four forks, then cross-check it against the
  completed topology metadata. `len(cpuSet)` is not an authoritative substitute.

### 3. Authoritative late-region joins and fail-closed provenance

- Join staleness rows to the fixed late half of each fork's ordered measurement windows. Compute one
  median per fork and then the cross-fork median; all earlier windows remain diagnostics.
- Enforce the existing readiness, initialized-contention, continuous-feeding, finite-value, and
  materially-stable-$P$ rules. Define the material-$P$ tolerance in the dataset config before load.
- Persist `effectiveOutcome`, `labelEvidenceBasis` (`WHOLE_AGREEMENT`, `LATE_CONVERGENCE`, or
  `STABLE_TIE`), and the exact means, variances, uncertainty, and throughputs from that basis.
- Require and verify sidecars for every consumed authoritative artifact. Do not fall back from
  missing trajectories to iteration lines and mark the result stable, and do not record only the
  first discovered staleness digest when four fork files were consumed.
- Cross-check the manifest runtime commit, CACHE actuator identity, park duration, fixture fields,
  and arm cutoffs against each completed trial configuration before constructing a `PairRecord`.

---

## Phase 0B: Dataset assembly, physical families & identifiability audit

### 1. Physical family partition (9 families)

Observations are partitioned into **9 mutually exclusive physical families** based on topology and
registered workers ($R$), configured parallel-source count ($S$), and work fixture ($WU$). Here
$S$ names the fixture. It is not the worker-local productive-handle value $P_i$ used by the model.

| Family ID           | Physical sweep / fixture description | Experiments | Retained count (`K_WINS` / `K_MINUS_1_WINS`) | Withdrawal boundaries |
|:--------------------|:-------------------------------------|:-----------:|:--------------------------------------------:|:----------------------|
| `Fam_R23_S2_WU112`  | $R=23, S=2, WU=112$                  |     28      |                    2 / 3                     | $K=9/8, 17/16, 21/20$ |
| `Fam_R23_S2_WU172`  | $R=23, S=2, WU=172$                  |     30      |                    2 / 1                     | $K=4/3$               |
| `Fam_R23_S1_WU112`  | $R=23, S=1, WU=112$                  |     31      |                    1 / 1                     | $K=14/13$             |
| `Fam_R23_S6_WU112`  | $R=23, S=6, WU=112$                  |     27      |                    6 / 0                     | None                  |
| `Fam_R23_S6_WU172`  | $R=23, S=6, WU=172$                  |     29      |                    6 / 0                     | None                  |
| `Fam_R7_S2_WU112`   | $R=7, S=2, WU=112$                   |     32      |                    2 / 0                     | None                  |
| `Fam_R7_S6_WU16`    | $R=7, S=6, WU=16$                    |    21-23    |                    1 / 0                     | None                  |
| `Fam_R7_S6_WU112`   | $R=7, S=6, WU=112$                   |    24-26    |                    2 / 0                     | None                  |
| `Fam_R23_S11_WU112` | $R=23, S=11, WU=112$                 |    18-20    |                    1 / 0                     | None                  |

> [!NOTE]
> The counts above reproduce the current Step 4 finding and are audit expectations, not loader
> configuration. Phase 0B regenerates row counts, class counts, confidence totals, and family keys
> directly from eligible records and fails on a mismatch. Rows from experiments 21-23, 24-26, and
> 18-20 remain grouped with their common physical sweep even though only a subset has positive
> weight.

### 2. Bounded family influence (family influence capping)

To prevent dense sweeps from dominating the objective without artificially inflating sparse
families:

For family $F$:

$$\operatorname{familyScale} (F) = \frac{1.0}{\max\left (1.0, \sum_{j \in F} v_j\right)}$$

For observation $i$ belonging to physical family $F (i)$ with Step 4 confidence
weight $v_i \in (0, 1]$:

$$u_i = v_i \cdot \operatorname{familyScale} (F (i))$$

Properties:

- If total family confidence $\sum_{j \in F} v_j \le 1.0$, weights are preserved unchanged
  ($\operatorname{familyScale} = 1.0$).
- If total family confidence $\sum_{j \in F} v_j > 1.0$, family total influence is capped at
  exactly $1.0$.
- Total normalized dataset weight: $U = \sum_{i=1}^N u_i$ (computed dynamically, not hardcoded).
- Effective sample size:
  $$N_{\text{eff}} = \frac{\left (\sum_{i=1}^N u_i\right)^2}{\sum_{i=1}^N u_i^2}$$

### 3. Feature matrix rank & unregularized identifiability diagnostics

Before any model optimization, construct the unregularized feature matrix
$X \in \mathbb{R}^{N \times 8}$ from eligible positive-weight records in physical coordinates:

$$
\mathbf{x}_i = \begin{bmatrix} q_i & c_i q_i & b_i q_i & R_i q_i & -1.0 & -c_i & -b_i & -R_i \end{bmatrix}^T, \quad q_i = \frac{P_i}{K_i (K_i - 1)}
$$

The Phase 0B audit computes and reports:

1. **Numerical matrix rank**: $\operatorname{rank} (X)$ using the declared LAPACK-style threshold
   $\tau = \sigma_1 \max (N,d)\epsilon_{\text{mach}}$. Scaling is invertible, so rank must be
   unchanged after scaling; a mismatch is a numerical-audit failure, not new physical information.
2. **Unregularized spectra**: report singular values for $X$ and for the confidence-weighted design
   $X_u = \operatorname{diag} (\sqrt{u_i})X$. The latter shows whether nominal rank depends on rows
   with negligible influence; neither is a regularized-Hessian identifiability claim.
3. **Condition number**: report $\kappa$ for the full scaled matrix. If numerical rank is deficient,
   report $\kappa=\infty$ and the smallest retained nonzero singular value rather than dropping the
   constant column or dividing by zero.
4. **VIF and collinearity matrix**: compute these only for nonconstant columns. Report infinite VIF
   for a rank-deficient auxiliary regression and explicitly list exact or near-linear dependencies.
5. **Class-conditional coverage and separation**: report each coordinate by effective class and
   family, including quasi/complete separation. Full matrix rank alone does not establish that a
   coefficient is empirically identified by both decision classes.
6. **Generated physical coordinate coverage**: report observed min/max and distinct values for
   $c$, $b$, measured $P_i$, configured $S$, $R$, $K$, and
   $q_i=P_i/[K_i (K_i-1)]$. The known fixture coverage is $R\in\{7,23\}$ and
   $S\in\{1,2,6,11\}$; do not substitute $S$ for measured $P_i$ or hardcode unverified $c$, $b$,
   or $q$ ranges.

```
+---------------------------------------------------------------------------------------------------+
| Mathematical distinction: operational scale invariance vs fitted magnitude                       |
|                                                                                                   |
| The physical runtime decision depends ONLY on the sign of m(K) = x^T w.                          |
| Any positive scalar multiple c * w (c > 0) yields the EXACT same physical decision boundary.      |
|                                                                                                   |
| Weighted BCE is magnitude-sensitive, and L2 makes the solution finite and unique when lambda > 0. |
| That magnitude belongs to the declared statistical objective; it is not a separately measured     |
| physical quantity. Changing feature units or lambda changes it even when decisions do not.         |
|                                                                                                   |
| Therefore, the meaningful stability targets are:                                                  |
| 1. Decision boundary stability (identical cutoff ranks across physical coordinates)               |
| 2. Active-group and coefficient sign stability, with near-zero values marked indeterminate        |
| 3. Direction in one common reference-scaled basis, not raw unit-dependent physical coefficients   |
| 4. Predicted factor contributions and margins over the declared physical domain                    |
|                                                                                                   |
| Simple global rescaling across LOFO folds must NOT be flagged as physical instability.            |
+---------------------------------------------------------------------------------------------------+
```

### 4. End-to-end verification chain & decoupled semantics

Validation follows an end-to-end verification chain:

```
[Two-Score Full Equation] ---> [Reduced Marginal Equation] ---> [Runtime Decision Semantics] ---> [Java Parity]
 score(K-1) - score(K)       =  A * P/(K(K-1)) - B              m(K) <= 0 => Participate         |x^T w - Java| <= 1e-12
                                                                m(K) >  0 => CACHE
```

#### A. Score-to-Marginal Algebraic Parity Test (`test_algebra.py`)

Synthetically validates that for arbitrary weight vectors and physical coordinates:

$$\text{score} (K-1) - \text{score} (K) \equiv A (c, b, R) \cdot \frac{P}{K (K-1)} - B (c, b, R)$$

#### B. Dataset Label Semantics Test (`test_labels.py`)

Uses verified raw reference fixtures to assert:

- `K_WINS` (e.g. Exp 27 $K=2$) $\implies y = 0.0$ (upstream participation preferred).
- `K_MINUS_1_WINS` (e.g. Exp 28 $K=21$) $\implies y = 1.0$ (cache withdrawal preferred).
- `STABLE_TIE` $\implies y = 0.5$ (neutral / zero advantage).

The same reference rows must also assert `effectiveOutcome` and `labelEvidenceBasis`. A whole/late
agreement row uses whole-run performance evidence; a late-convergence row uses late-region evidence;
a stable tie uses the whole-run tie evidence. This prevents model-selection regret from silently
using a different winner or throughput interval than label synthesis.

#### C. Runtime Evaluator Sign Semantics Test (`test_sign_semantics.py`)

Uses synthetic, hand-constructed vectors with analytically known marginal values. Python tests cover
numeric vector/formula parity. A dedicated forked Java test JVM, started with
`-Deuhedral.fragment.cacheExecutePath=true` before `FragmentDecisionTree` is initialized, loads the
same named `ParetoWeights` and asserts the actual `shouldCacheExecute` result:

- For synthetic $z = \mathbf{x}^T \mathbf{w} \le 0$:
    - Python evaluator outputs $\text{participate} = \text{True}$.
    - Java `FragmentDecisionTree.shouldCacheExecute` returns `false`.
- For synthetic $z = \mathbf{x}^T \mathbf{w} > 0$:
    - Python evaluator outputs $\text{participate} = \text{False}$ (withdraw).
    - Java `FragmentDecisionTree.shouldCacheExecute` returns `true`.

Use Java-representable inputs in these vectors: fixed-point contention divided by $10^6$, integral
productive handles, integral $R/K$, and body costs that can be installed through the existing body
history path. A Python method that merely rewrites the Java equation is useful algebraic coverage,
but it does not by itself constitute cross-language or runtime parity.

---

## Mathematical formulation, prefix monotonicity & scaled constraints

### 1. Marginal participation equation

For an active worker at candidate cutoff rank $K \ge 2$, with $R$ registered workers, $P$
productive handles, normalized contention $c \in [0, 1]$, and log-body cost
$b = \ln (1 + \text{bodyCostNs})$:

$$
\begin{aligned} A (c, b, R) &= w_0 + w_1 \cdot c + w_2 \cdot b + w_3 \cdot R \\ B (c, b, R) &= w_4 + w_5 \cdot c + w_6 \cdot b + w_7 \cdot R \\ \text{marginal} (K) &= A (c, b, R) \cdot \frac{P}{K (K-1)} - B (c, b, R) = \mathbf{x}^T \mathbf{w} \end{aligned}
$$

### 2. Discrete proof of participation prefix monotonicity

Let $m (K) = \text{marginal} (K) = A (c, b, R) \cdot \frac{P}{K (K-1)} - B (c, b, R)$.

For consecutive integer worker ranks $K \ge 2$:

$$\begin{aligned} m (K+1) - m (K) &= A (c, b, R) \cdot P \cdot \left[ \frac{1}{ (K+1)K} - \frac{1}{K (K-1)} \right] \\ &= A (c, b, R) \cdot P \cdot \left[ \frac{ (K-1) - (K+1)}{K (K+1)(K-1)} \right] \\ &= - A (c, b, R) \cdot \frac{2P}{K (K^2 - 1)} \end{aligned}$$

For all valid physical configurations with $P > 0$ and discrete $K \ge 2$, the
factor $\frac{2P}{K (K^2-1)} > 0$.

Therefore:

$$A (c, b, R) \le 0 \implies m (K+1) - m (K) \ge 0 \implies m (K+1) \ge m (K)$$

#### Fixed-state policy guarantee

For one fixed $(c,b,P,R)$ state, if rank $K$ withdraws to cache ($m (K)>0$), every higher rank
$K'>K$ also withdraws. This does **not** prove that live workers form a prefix because $c$, $b$, and
$P$ are worker-local and can differ by rank. Report both the fixed-state grid result and observed
per-rank runtime decisions; do not describe the former as a global runtime invariant.

### 3. Exact bounded domain corner constraints in scaled optimizer coordinates

The constraint and evaluation domain is a versioned input (`domain.json`), frozen before
cross-validation. It must declare $c_{\min},c_{\max}$, raw body-cost bounds, positive productive-
handle bounds, $R_{\min},R_{\max}$, and the discrete rule $2\le K\le R$ from the intended deployment
contract. Dataset extrema are reported separately and must not silently become extrapolation
guarantees. Convert the raw body bounds with `log1p` to $b_{\min},b_{\max}$. The $A\le0$ corner
constraint itself is independent of positive $P$ and $K$; the fixed-state decision grid is not.

Because $A (c,b,R)=w_0+w_1c+w_2b+w_3R$ is affine linear, its maximum over
$\mathcal{D}=[c_{\min},c_{\max}]\times[b_{\min},b_{\max}]\times[R_{\min},R_{\max}]$ occurs at one of
the eight corners:

$$\mathcal{V}=\left\{ (c,b,R)\mid c\in\{c_{\min},c_{\max}\},\ b\in\{b_{\min},b_{\max}\},\ R\in\{R_{\min},R_{\max}\}\right\}$$

In physical coordinates, $A \le 0$ everywhere is equivalent to the 8 linear inequalities:

$$C_A \mathbf{w}_{\text{phys}} \le \mathbf{0}, \quad C_A \in \mathbb{R}^{8 \times 8}$$

where row $v$ of $C_A$ is $\begin{bmatrix}1&c_v&b_v&R_v&0&0&0&0\end{bmatrix}$.

#### Optimizer Coordinate Transformation:

For candidate model $M$, let $J_M$ be its ordered active coefficient indices, let $C_{A,M}$ be the
columns of $C_A$ selected by $J_M$, and let $S_M$ be that training fold's active-feature scale
matrix. The optimizer searches over $\mathbf{w}_{M,\text{scaled}}$, where
$\mathbf{w}_{M,\text{phys}}=S_M^{-1}\mathbf{w}_{M,\text{scaled}}$.

Substituting the coordinate transformation yields the **exact optimizer constraint matrix**:

$$\tilde{C}_{A,M}\mathbf{w}_{M,\text{scaled}}\le\mathbf{0},\quad \tilde{C}_{A,M}=C_{A,M}S_M^{-1}\in\mathbb{R}^{8\times |J_M|}$$

> [!IMPORTANT]
> The optimizer must enforce the projected, scaled constraint for the candidate model. Full-space
> $C_A$ must never be applied directly to a reduced or scaled coefficient vector.
> An automated unit test (`test_constraints.py`) verifies
> that the projected scaled constraint, the embedded eight-element physical vector, and
> physical-domain $A (c,b,R)\le0$ agree at every declared corner for every candidate structure.

---

## Grouped model hierarchy and scale-aware regularized loss

### 1. Grouped model hierarchy

Each physical axis enters both $A$ and $B$ as a pair. This preserves group heredity and avoids an
arbitrary rule that body must enter before contention or registered workers. The fixed candidate
lattice is small:

| Model   | Added physical groups             | Active coefficients           | Parameters |
|:--------|:----------------------------------|:------------------------------|-----------:|
| `M2`    | intercepts only                   | $\{w_0,w_4\}$                 |          2 |
| `M4-C`  | contention                        | $\{w_0,w_1,w_4,w_5\}$         |          4 |
| `M4-B`  | body                              | $\{w_0,w_2,w_4,w_6\}$         |          4 |
| `M4-R`  | registered workers                | $\{w_0,w_3,w_4,w_7\}$         |          4 |
| `M6-CB` | contention and body               | $\{w_0,w_1,w_2,w_4,w_5,w_6\}$ |          6 |
| `M6-CR` | contention and registered workers | $\{w_0,w_1,w_3,w_4,w_5,w_7\}$ |          6 |
| `M6-BR` | body and registered workers       | $\{w_0,w_2,w_3,w_4,w_6,w_7\}$ |          6 |
| `M8`    | all three groups                  | $\{w_0,\ldots,w_7\}$          |          8 |

All inactive coefficients are exactly zero in the embedded eight-element physical vector. Do not
drop `M4-R` merely because only two $R$ values exist; let the rank, class-conditional coverage, and
LOFO results expose whether it is unsupported.

### 2. Feature scale normalization without mean centering

Physical feature columns have different units and magnitudes, so isotropic $L_2$ regularization on
raw coordinates would impose a unit-dependent penalty.

#### Scaling Protocol (No Leakage, No Mean Centering):

1. For active training-fold column $j$, compute the confidence-weighted root-mean-square scale
   $$s_j=\sqrt{\frac{\sum_{i\in\text{train}}u_i x_{ij}^2}{\sum_{i\in\text{train}}u_i}}.$$
   Set a structurally constant feature column to scale $1.0$ (in the current design this is the
   $-1$ column for $w_4$). The $w_0$ feature is $q$ and must be RMS-scaled like other nonconstant
   columns. If any nonconstant active column has zero or non-finite scale, classify that candidate
   as unidentified in the fold instead of hiding the problem with an arbitrary scale.
2. Form the diagonal scaling matrix $S = \operatorname{diag} (s_0, s_1, \dots, s_{d-1})$.
3. Scale-normalized training features: $\tilde{X} = X S^{-1}$. Mean centering is **not** applied,
   preserving direct linear transforms.
4. Fit scale-normalized weight vector $\mathbf{w}_{\text{scaled}}$ under regularized objective:
   $$\mathcal{L}_{\text{scaled}} (\mathbf{w}_{\text{scaled}}; \lambda) = \frac{1}{U} \sum_{i=1}^N u_i \ell (y_i, \tilde{\mathbf{x}}_i^T \mathbf{w}_{\text{scaled}}) + \lambda \sum_{j=0}^{d-1} w_{\text{scaled}, j}^2$$
5. Analytically transform back to physical coordinates:
   $$\mathbf{w}_{\text{phys}} = S^{-1} \mathbf{w}_{\text{scaled}}$$
6. Exact Invariant:
   Verify $\mathbf{x}_i^T \mathbf{w}_{\text{phys}} = \tilde{\mathbf{x}}_i^T \mathbf{w}_{\text{scaled}}$
   with the common tolerance
   $|\Delta|\le 10^{-12}+10^{-12}\max (|z_{\text{phys}}|,|z_{\text{scaled}}|)$ across all rows.

### 3. Unclipped loss formulation, gradient & Hessian

Let $z_i = \tilde{\mathbf{x}}_i^T \mathbf{w}_{\text{scaled}} = \mathbf{x}_i^T \mathbf{w}_{\text{phys}}$
be the predicted logit for observation $i$. The modeled probability of cache withdrawal
is $p_i = \operatorname{expit} (z_i) = \frac{1}{1 + e^{-z_i}}$.

The sample loss $\ell (y_i, z_i)$ is formulated without logit clipping using `logaddexp`:

$$\ell (y_i, z_i) = \operatorname{logaddexp} (0, z_i) - y_i z_i$$

Scaled
gradient $\nabla_{\mathbf{w}_{\text{scaled}}} \mathcal{L}_{\text{scaled}} \in \mathbb{R}^{d}$:

$$\nabla_{\mathbf{w}_{\text{scaled}}} \mathcal{L}_{\text{scaled}} = \frac{1}{U} \tilde{X}^T \left (\mathbf{u} \odot (\mathbf{p} - \mathbf{y}) \right) + 2 \lambda \mathbf{w}_{\text{scaled}}$$

Scaled Hessian $H_{\text{scaled}} \in \mathbb{R}^{d \times d}$:

$$H_{\text{scaled}} = \frac{1}{U} \tilde{X}^T \operatorname{diag}\left (u_1 p_1 (1 - p_1), \dots, u_N p_N (1 - p_N) \right) \tilde{X} + 2 \lambda I_d$$

For every $\lambda>0$, this objective is strongly convex and the feasible set is linear. Use one
declared deterministic primary solver and a feasible zero initialization. Record primal constraint
violation, gradient/KKT residual, termination reason, and iteration count. A second solver may be a
diagnostic cross-check, but selecting whichever solver gives a preferred result is not permitted.

---

## Grouped cross-validation and model selection

### 1. Leave-one-family-out (LOFO) across 9 physical families

LOFO evaluates internal model-selection generalization without fold leakage. These folds are not the
untouched Step 6 validation set. Once Step 5 refits the selected candidate on all nine families, no
Step 5 observation may be described as held out from that final fit.

```text
For each structure m in {M2, M4-C/B/R, M6-CB/CR/BR, M8}:
  For each lambda in {1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1.0}:
    For each held-out family F_f:
      1. Build training families F minus F_f and validation family F_f.
      2. Compute S_train and family weights using only the training families.
      3. Solve the projected, scaled constrained objective.
      4. Back-transform and embed the physical eight-element vector.
      5. Reject solver, constraint, transform, or fixed-state prefix failures.
      6. Compute validation regret, BCE, accuracy, tie metrics, and baselines.
    Aggregate pooled and per-family metrics across all 9 folds.
```

The confidence weights for a held-out family are normalized from that family alone using the same
frozen capping formula. This uses no training labels and prevents an omitted family from changing
the relative weights of another family.

### 2. Throughput regret metrics

For validation observation $i$, use the performance basis that produced its effective label. Let
$T_i (K)$, $T_i (K-1)$, $\Delta_i=T_i (K-1)-T_i (K)$, and uncertainty $h_i$ come from whole-run
evidence for whole/late agreement, late-region evidence for late convergence, and whole-run evidence
for a stable tie. Define the uncertainty-supported advantage

$$a_i=\max (0,|\Delta_i|-h_i).$$

Do not infer a validation winner from the raw sign of $\Delta_i$ when the effective outcome is a tie
or inconclusive. For a decisive row predicted opposite the effective winner, define both

$$\operatorname{supportedLoss}_i=a_i,\qquad \operatorname{observedLoss}_i=|\Delta_i|;$$

otherwise both are zero. Stable ties have zero winner regret and are evaluated with weighted BCE
plus neutral-band metrics whose logit threshold is frozen in the fitting config before CV.

$$\operatorname{supportedRelLoss}_i= \frac{\operatorname{supportedLoss}_i}{\max (T_i (K),T_i (K-1))}$$

Aggregate:

- Pooled weighted mean relative regret:
  $$\operatorname{Regret}_{\mathrm{rel}}= \frac{\sum_i u_i\operatorname{supportedRelLoss}_i}{\sum_i u_i}.$$
- Worst-family relative regret: $\max_f\operatorname{Regret}_{\mathrm{rel}} (F_f)$.
- Weighted mean observed absolute regret in lost ops/s:
  $$\operatorname{ObservedRegret}_{\mathrm{abs}}= \frac{\sum_i u_i\operatorname{observedLoss}_i}{\sum_i u_i}.$$

Use uncertainty-supported relative regret for selection and parsimony. Report observed raw regret
beside it as the operational effect estimate; do not describe the supported value as the raw loss.

Compute the same fold metrics for `always-participate`, `always-CACHE`, and every declared fixed
cutoff $K_0$. The fixed policy participates when $K\le\min (K_0,R)$ and withdraws otherwise. A
baseline cutoff is selected inside each training fold, never on its validation family.

### 3. Executable procedural parsimony rule

First select $\lambda$ within each structure by the lexicographic tuple: lower pooled relative
regret, lower worst-family relative regret, lower weighted BCE, then larger $\lambda$. Values within
$10^{-12}$ are equivalent for deterministic comparison.

For structure selection, start with `M2`. At each larger parameter count, compare every structure of
that size with the current simpler incumbent. A candidate is admissible if and only if:

1. pooled relative regret is lower by more than $10^{-12}$;
2. worst-family relative regret does not increase by more than $10^{-12}$;
3. at least two families have strictly lower relative regret by more than $10^{-12}$; and
4. at least one corrected differing decision has decisive evidence with $a_i>0$.

If several same-size candidates are admissible, choose among them by the same metric tuple (final
tie: lexicographic model ID). Continue through all larger sizes even when an intermediate size
fails; for example, a six-parameter candidate may replace `M2` if no four-parameter candidate does.
This procedure is frozen before fitting and does not inspect Step 6 evidence.

---

## Coefficient stability and empirical identification diagnostics

Raw physical coefficients have different units, so normalizing
$\mathbf{w}_{\text{phys}}/\|\mathbf{w}_{\text{phys}}\|_2$ is not a unit-invariant diagnostic. Build
one full-dataset, confidence-weighted reference scale $S_{\text{ref}}$ after dataset assembly. It is
used only for diagnostics, never to fit a fold. For fold $k$, embed the physical vector in eight
dimensions and calculate

$$\mathbf{a}^{ (k)}=S_{\text{ref}}\mathbf{w}_{\text{phys}}^{ (k)},\qquad \hat{\mathbf{a}}^{ (k)}=\frac{\mathbf{a}^{ (k)}}{\|\mathbf{a}^{ (k)}\|_2}.$$

Report component ranges, pairwise cosine/angle, maximum $L_2$ directional distance, coefficient
signs with a declared near-zero tolerance, and cutoff decisions over the versioned domain. Do not
use coefficient CV when a mean can be zero, and do not use ratios to a possibly near-zero intercept.

Repeat these diagnostics for the adjacent declared $\lambda$ candidates around $\lambda^*$ and for
an ablation that removes rows with original confidence $v_i<0.1$. Also report family-deletion
changes in decisions and factor contributions $A$ and $B$. Classify each active physical group as:

- **Empirically identified**: supported by numerical and class-conditional coverage, with stable
  reference-scaled direction and decisions under LOFO and ablation.
- **Weakly identified / regularizer-dominated**: limited or one-class coverage, unstable direction,
  or materially different boundaries under small evidence changes.

These classifications are evidence reports, not permission to reinterpret an unstable coefficient as
precise. If the selected candidate contains a weak group, Step 5 findings must prominently state
that limitation and carry the simpler admissible candidate, or `M2` when none exists, forward as the
conservative alternative. Thresholds for near-zero signs, angular change, material factor change,
and cutoff change must be frozen in the fit configuration before fitting; otherwise the diagnostic
remains descriptive and may not be labeled "empirically identified."

---

## Step 5 candidate artifact and Step 7 boundary

Step 5 emits `step5_candidate_model.json` plus a SHA-256 sidecar. It contains:

- schema, model, equation, CACHE actuator, runtime-commit, and loader versions;
- the frozen dataset manifest, domain, fitting config, and every consumed relative path/digest;
- regenerated family inventory, feature audit, class coverage, rank/spectra, and effective sample
  size;
- every candidate structure/lambda LOFO result, baseline result, rejection reason, and the complete
  deterministic selection trace;
- solver status, scales, projected constraints, KKT/constraint residuals, and final full-data fit;
- eight physical weights with inactive coefficients exactly zero, common-reference direction, and
  the explicit named Java-field mapping;
- internal LOFO metrics, tie metrics, fixed-state prefix checks, observed per-rank checks, stability
  diagnostics, and Java action-parity results.

All numeric fields are generated; this design document intentionally contains no precise-looking
example fit, checksum, rank, coefficient, or regret values. The artifact schema rejects missing,
non-finite, placeholder, and unknown required fields.

The embedded named Java mapping is a candidate handoff for parity testing. Step 5 does **not** emit
the standalone `java_pareto_weights.json`, change Java defaults, or authorize production use. Those
remain Step 7 actions after independent Step 6 validation.

---

## Software architecture and current implementation boundary

The package already exists. Step 5 extends it; it does not create a parallel trainer. Before
fitting, replace the current clipped, raw-coordinate, unconstrained eight-weight BFGS path in
`model.py` and repair the authoritative ingestion gaps listed in Phase 1. Preserve the existing
public load/export interfaces where their semantics remain valid.

```text
python/pareto-weight-calibration/
+-- src/
|   +-- pareto_weight_calibration/
|       +-- __init__.py
|       +-- checksum.py           # Existing SHA-256 sidecar validator
|       +-- manifest.py           # Existing parser; add frozen inventory and multi-sample identity
|       +-- config.py             # Config parser and compatibility analyzer
|       +-- throughput.py         # Log parser and pooled arm performance
|       +-- trajectory.py         # Time-series trajectory and late-region OLS
|       +-- staleness.py          # Existing parser; repair late joins and R consistency checks
|       +-- labels.py             # Uncertainty-adjusted comparison & weight synthesizer
|       +-- types.py              # Existing dataclasses; add eligibility and label evidence basis
|       +-- loader.py             # Existing orchestrator; repair four-fork pooling and provenance
|       +-- export.py             # Tabular pairs.tsv export
|       +-- audit.py              # [NEW] Phase 0B dataset, rank, SVD, VIF & identifiability audit
|       +-- loss.py               # [NEW] Numerically stable unclipped loss, gradient & Hessian
|       +-- constraints.py        # [NEW] Scaled domain corner constraints (C_A * S^-1 * w_scaled <= 0)
|       +-- optimizer.py          # [NEW] Scale-aware optimizer with scaled corner constraints
|       +-- nested.py             # [NEW] Grouped candidate lattice and procedural parsimony
|       +-- cv.py                 # [NEW] 9-family LOFO cross-validation & relative regret grid search
|       +-- evaluate.py           # [NEW] Regret, baselines, parity vectors, and prefix checks
|       +-- model.py              # Existing container; replace legacy fit/save with Step 5 contract
|       +-- cli.py                # Existing CLI; add audit, fit, cv, and evaluate commands
+-- tests/
    +-- test_algebra.py           # [NEW] Score(K-1) - Score(K) == marginal(K) algebra parity test
    +-- test_audit.py             # [NEW] Matrix rank, SVD, and collinearity audit tests
    +-- test_labels.py            # Extend existing tests with reference-label/evidence-basis cases
    +-- test_sign_semantics.py    # [NEW] Runtime evaluator sign convention test (synthetic weights)
    +-- test_loss.py              # [NEW] Unclipped loss, gradient, and Hessian vs finite-diff
    +-- test_constraints.py       # [NEW] Scaled vs physical corner constraint equivalence
    +-- test_optimizer.py         # [NEW] Scale-normalized optimization and back-transform parity
    +-- test_nested.py            # [NEW] Group mappings and procedural parsimony rule
    +-- test_cv.py                # [NEW] 9-family LOFO partitioning, leakage checks & influence capping
    +-- test_evaluate.py          # [NEW] Regret basis, baselines, transform parity, prefix checks
    +-- test_step5_fitting.py     # [NEW] End-to-end Step 5 calibration fit pipeline
```

The actual runtime action-parity consumer belongs in
`euhedral-core/src/test/java/io/euhedral_execution/core/control_plane/` and must run in its own
property-enabled JVM. No production Java source change is required for Step 5.

---

## Verification plan and acceptance criteria

### 1. Automated software unit test suite

| Test file                | Verification target                    | Pass condition                                                                                                                                      |
|:-------------------------|:---------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------|
| `test_algebra.py`        | Score-to-marginal identity             | `score(K-1) - score(K)` equals $AP/[K(K-1)]-B$ over generated valid coordinates.                                                                    |
| `test_labels.py`         | Reference labels and evidence basis    | The frozen reference rows reproduce effective outcome, $y$, confidence, and whole/late evidence basis.                                              |
| `test_loader.py`         | Authoritative four-fork ingestion      | Both sample positions and all four distinct JVM forks are consumed; missing/duplicate forks, sidecars, late joins, or inconsistent $R$ fail closed. |
| `test_sign_semantics.py` | Python action mapping                  | $m\le0$ participates and $m>0$ selects CACHE, including exactly zero.                                                                               |
| forked Java parity test  | Actual runtime action mapping          | The named weights and vectors give the same action through property-enabled `FragmentDecisionTree.shouldCacheExecute`.                              |
| `test_loss.py`           | Loss, gradient, and Hessian            | Stable finite values for extreme logits; analytic derivatives match finite differences within the declared tolerance.                               |
| `test_constraints.py`    | Domain and coordinate parity           | Every candidate's scaled constraint, embedded physical constraint, and all eight domain corners agree.                                              |
| `test_optimizer.py`      | Convex fit and back-transform          | Solver/KKT checks pass and logits agree under the common absolute-plus-relative tolerance.                                                          |
| `test_nested.py`         | Candidate mappings and selection       | Group heredity, zero embedding, lambda tie-breaks, and all four parsimony conditions are deterministic.                                             |
| `test_cv.py`             | LOFO leakage and influence             | No artifact/family crosses train/validation; family capping and fold-local baseline selection are exact.                                            |
| `test_evaluate.py`       | Regret and fixed-state prefix behavior | Whole/late evidence basis, ties, uncertainty-supported regret, baselines, and synthetic reversal detection are correct.                             |
| `test_step5_fitting.py`  | Deterministic end-to-end artifact      | Repeated runs produce byte-identical JSON and sidecar after removing nondeterministic timestamps/paths from the schema.                             |

### 2. Mandatory acceptance criteria for Step 5 calibration completion

Before proceeding to Step 6 (held-out validation):

1. **Frozen input eligibility**: Every arm and fork in the pre-fit Step 5 manifest is `ELIGIBLE`;
   all other inventoried comparisons retain an explicit exclusion state and reason.
2. **Repaired ingestion baseline**: Matrices use both balanced sample positions, four independent
   JVM forks per arm, fixed late-region joins, all required checksums, and telemetry-derived $R$.
3. **Generated Family Inventory Documented**: Dynamic family confidence totals and bounded influence
   factors calculated and recorded in audit findings.
4. **Phase 0B audit complete**: Unweighted and confidence-weighted spectra, numerical rank,
   condition number, VIFs, class-conditional coverage, separation, and observed coordinates are
   documented without a regularized-Hessian identifiability claim.
5. **Versioned domain and algebra**: The deployment domain is frozen before CV; score-to-marginal,
   scaling, embedding, and projected-constraint parity pass.
6. **Fixed-state prefix constraint**: $A (c,b,R)\le0$ at all eight domain corners and zero
   fixed-state reversals hold on the declared deterministic grid. Findings separately state that
   worker-local inputs prevent this from proving a live global prefix.
7. **Procedural model selection**: The selected grouped structure and $\lambda$ follow the frozen
   LOFO, uncertainty-supported regret, parsimony, and tie-breaking procedure exactly.
8. **Baseline evidence**: The selected candidate's pooled relative regret is strictly below the
   `always-participate` baseline and no worse than the best training-fold-selected fixed-cutoff
   baseline within $10^{-12}$. Its worst-family regret is no greater than the
   `always-participate` worst-family regret within $10^{-12}$. If these fail, Step 5 reports no
   admissible fitted candidate rather than weakening the thresholds after inspection.
9. **Stability documented in common coordinates**: LOFO, adjacent-$\lambda$, and low-confidence
   ablation results use $S_{\text{ref}}$ direction, sign tolerance, factor contributions, and cutoff
   decisions; every weak active group is explicit and a simpler conservative alternative is
   retained.
10. **Actual Java action parity**: The dedicated property-enabled Java test consumes generated
    vectors and the named candidate mapping and matches Python decisions, including the zero
    boundary.
11. **Deterministic Step 5 artifact**: `step5_candidate_model.json` and its sidecar contain complete
    provenance and byte-stable generated results. No standalone Step 7 Java export or production
    default change occurs.
