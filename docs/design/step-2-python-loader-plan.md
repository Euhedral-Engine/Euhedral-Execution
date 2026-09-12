# Step 2: Python Loader & Artifact Join Pipeline Design Document

## Executive summary and purpose

This document defines the architecture, data models, compatibility rules, signal processing algorithms, and test specification for **Step 2 (Python Loader)** of the external Pareto-weight calibration pipeline.

As established in [`docs/design/productivity-participation-python-training-plan.md`](productivity-participation-python-training-plan.md), the goal of the training system is to estimate the eight CACHE-participation coefficients ($w_0 \dots w_7$) for the marginal participation equation:

$$
\begin{aligned}
A &= w_0 + w_1 \cdot c + w_2 \cdot b + w_3 \cdot R \\
B &= w_4 + w_5 \cdot c + w_6 \cdot b + w_7 \cdot R \\
\text{marginal}(K) &= A \cdot \frac{P}{K(K-1)} - B
\end{aligned}
$$

**Step 2** is the foundational ingestion engine. It runs entirely outside the JVM runtime and is responsible for:
1. Validating artifact checksums (`.sha256` sidecars) and structural integrity.
2. Enforcing strict multi-arm fixture compatibility between paired adjacent runs ($K$ vs $K-1$).
3. Ingesting and cross-checking fork-level throughput from `benchmark_output.log` and `comparison_summary.tsv`.
4. Analyzing time-series window stability in `trajectory_windows.tsv` to compute late-region steady-state metrics.
5. Ingesting decision-point telemetry in `contention_staleness.tsv` for the active rank-$K$ worker, extracting contention $c$, log-body cost $b = \ln(1 + \text{smoothedBodyCostNs})$, productive handles $P$, and registered workers $R$.
6. Constructing directional training labels ($y \in \{0.0, 0.5, 1.0\}$) and confidence weights based on whole-run and late-trajectory convergence.
7. Emitting an auditable, provenance-rich intermediate dataset (`pairs.tsv` and typed `PairRecord` collections) ready for model fitting (Step 5) and validation (Step 6).

Step 2 does **not** perform model fitting, candidate searching, or Java weight export.

---

## Python module location and environment

The Python module will be located at the root of the repository under:

```text
python/pareto-weight-calibration/
```

### Module structure

```text
python/pareto-weight-calibration/
+-- pyproject.toml
+-- README.md
+-- src/
|   +-- pareto_weight_calibration/
|       +-- __init__.py
|       +-- checksum.py          # Streaming SHA-256 sidecar validation
|       +-- manifest.py          # Manifest JSON schema, path resolution, and pair indexing
|       +-- config.py            # trial_config.json parsing and strict arm compatibility analyzer
|       +-- throughput.py        # benchmark_output.log parser and fork throughput metrics
|       +-- trajectory.py        # trajectory_windows.tsv parsing, late-region slicing, and OLS stability
|       +-- staleness.py         # contention_staleness.tsv parser, rank-K filtering, and median aggregation
|       +-- labels.py            # Whole-run vs late-region comparison rule, outcome, y, and pair confidence
|       +-- loader.py            # High-level join orchestrator producing PairRecord instances
|       +-- types.py             # Strongly typed dataclasses for all intermediate and output schemas
|       +-- export.py            # pairs.tsv tabular export with verified digest provenance
|       +-- cli.py               # Command-line entry points for validation, ingestion, and dataset inspection
+-- tests/
    +-- __init__.py
    +-- conftest.py
    +-- test_checksum.py
    +-- test_manifest.py
    +-- test_config_compatibility.py
    +-- test_throughput.py
    +-- test_trajectory.py
    +-- test_staleness.py
    +-- test_labels.py
    +-- test_loader.py
    +-- fixtures/
        +-- mock_run_k/
        |   +-- trial_config.json
        |   +-- benchmark_output.log
        |   +-- trajectory_windows.tsv
        |   +-- trajectory_windows.tsv.sha256
        |   +-- contention_staleness.tsv
        |   +-- contention_staleness.tsv.sha256
        +-- mock_run_k_minus_1/
        |   +-- trial_config.json
        |   +-- benchmark_output.log
        |   +-- trajectory_windows.tsv
        |   +-- trajectory_windows.tsv.sha256
        |   +-- contention_staleness.tsv
        |   +-- contention_staleness.tsv.sha256
        +-- mock_manifest.json
```

### Toolchain and dependencies

In accordance with [`mise.toml`](../../mise.toml) and repository standards:
- **Python Version**: 3.12+ (standard system toolchain).
- **Core Dependencies**:
  - `numpy >= 1.26.0` (vector math, percentile/median calculations, regression).
  - `scipy >= 1.12.0` (statistical utilities and optimization primitives used in Step 5).
  - `pydantic >= 2.6.0` or standard Python `dataclasses` (strict schema validation and immutability).
- **Development & Testing Dependencies**:
  - `pytest >= 8.0.0`
  - `pytest-cov`
  - `ruff` (formatting and linting).

---

## High-level data flow

```text
[ manifest.json ]
       |
       v
 +-------------------------------------------------------------------------+
 | Manifest Loader & Checksum Verifier                                     |
 | - Resolves run paths (arm A: forcedActive=K, arm B: forcedActive=K-1)   |
 | - Verifies SHA-256 sidecars for all required TSV, JSON, and LOG files   |
 +-------------------------------------------------------------------------+
       |
       +-----------------------+-----------------------+
       |                       |                       |
       v                       v                       v
 +-------------------+   +-------------------+   +-----------------------+
 | trial_config.json |   | benchmark_output  |   | trajectory_windows    |
 | - Strict arm      |   | - Fork scores     |   | - Late-region slice   |
 |   compatibility   |   | - Whole-run means |   | - OLS slope and CV    |
 |   analyzer        |   | - Fork variances  |   | - Continuously-fed    |
 +-------------------+   +-------------------+   +-----------------------+
       |                       |                       |
       |                       v                       v
       |                 +-----------------------------------------------+
       |                 | Label & Confidence Synthesizer                |
       |                 | - Variance-aware margin calculation           |
       |                 | - Outcome agreement (whole-run vs late)       |
       |                 | - Label y in {0, 0.5, 1} and confidence weight|
       |                 +-----------------------------------------------+
       |                                       |
       +-------------------+                   |
                           |                   |
                           v                   v
                 +-----------------------------------+
                 | contention_staleness.tsv          |
                 | - Filter rank == K in arm A       |
                 | - Verify bodyHistoryReady == 1    |
                 | - Fork medians -> Cross-fork med  |
                 | - Features: c, b, P, R            |
                 | - Diagnostic post-treatment state |
                 +-----------------------------------+
                                   |
                                   v
                 +-----------------------------------+
                 | Joined PairRecord / pairs.tsv     |
                 | - Feature vector x                |
                 | - Label y & pair confidence       |
                 | - Full artifact checksum SHA-256  |
                 +-----------------------------------+
```

---

## Detailed component specifications

### 1. Checksum verification (`checksum.py`)

All TSV and JSON files exported by the Java harness have adjacent `.sha256` sidecars. The loader must verify these files before parsing:

- **Algorithm**: Streaming `SHA-256` in chunks of 64 KB to support large staleness logs without unbounded memory allocation.
- **Sidecar Format**: Single line containing hexadecimal digest (64 characters), optionally followed by whitespace and filename.
- **Failure Policy**: Fail closed immediately with `ChecksumMismatchError` if computed digest differs from sidecar. If a sidecar file is missing for an authoritative artifact, raise `MissingChecksumError`.

```python
class ChecksumVerifier:
    @staticmethod
    def verify_file(target_file: Path) -> str:
        """Computes SHA-256 and verifies against <target_file>.sha256 if present.
        Returns the verified SHA-256 hex digest.
        Raises ChecksumMismatchError or MissingChecksumError.
        """
```

---

### 2. Manifest and run pair resolution (`manifest.py`)

The loader accepts a dataset manifest pointing to experiment run roots and pairing adjacent candidate runs.

#### Manifest schema (`dataset_manifest.json`)
```json
{
  "schemaVersion": 1,
  "runtimeCommit": "0389101038ee1adf4fc45b832044144e45ac2ebf",
  "cacheActuatorVersion": "cache-v1",
  "cacheParkNs": 15000,
  "topologyId": "linux-x86_64-8p16e-i9-13900k",
  "pairs": [
    {
      "pairId": "pair-k8-k7-c01ce91b",
      "kRunPath": "experiments/02-productivity-lifecycle-2x2/continuous-cutoff-8_repeat_0",
      "kMinus1RunPath": "experiments/02-productivity-lifecycle-2x2/continuous-cutoff-7_repeat_0",
      "K": 8,
      "metadata": {
        "workUnits": 16,
        "bodyFixture": "m"
      }
    }
  ]
}
```

#### Path resolution semantics
Paths in the manifest may be relative to the manifest location or absolute. If a target path points to an experiment directory containing multiple JMH forks (e.g. `fork-178...`), the loader discovers and groups all matching fork subdirectories.

---

### 3. Strict multi-arm compatibility analyzer (`config.py`)

To ensure that the observed performance delta between arm A ($K$) and arm B ($K-1$) is strictly caused by the participation state of rank $K$, all other execution parameters must be verified for identical configuration.

#### Mandatory match requirements

| Configuration field | Required value / matching rule | Training implication |
|---|---|---|
| `lifecycleMode` | Must be `CONTINUOUS` | `RESET` runs do not preserve steady-state caches or queues; reject if not `CONTINUOUS`. |
| `cacheActuatorVersion` | Must match manifest and be `cache-v1` | Prevents mixing legacy miss loops with the versioned CACHE exhaustion park. |
| `cacheParkNs` | Must match manifest (e.g., `15000`) | Actuator duration cannot vary across paired arms. |
| `cpuSet` | Exact list equality | Physical worker allocation must not change. |
| `registeredWorkers` | Exact equality ($R$) | Worker count must remain constant. |
| `workUnits` | Exact equality | Workload per frame must be identical. |
| `randomizeWork` | Exact equality | Parallel placement properties must match. |
| `totalRequiredExecutions` | Exact equality | Work batch limits must match. |
| `parallelSources` / `orderedSources` | Exact equality | Upstream queue structure must match. |
| `decisionWeights` | Exact equality for all ordinary DIRECT, STAGED, and IDLE weights | Prevents confounding from different base decision parameters. |
| `forks`, `warmups`, `iterations` | Exact equality | Measurement structure must match. |
| `warmupTime`, `measurementTime` | Exact equality | Window duration must match. |
| `jvmArgs` | Exact token equality (ignoring benchmark runner internal properties) | JVM flags must match. |

#### Permitted difference
- `forcedActiveParticipantCount`: Arm A must equal $K$, and Arm B must equal $K - 1$, where $2 \le K \le R$.

If any invariant is violated, the loader flags the pair as `INCOMPATIBLE_FIXTURE` and excludes it from training.

---

### 4. Throughput ingestion and cross-check (`throughput.py`)

The loader ingests throughput from two sources to ensure cross-validation:
1. `benchmark_output.log`: Parsed via regular expressions matching JMH fork summaries (`Iteration <N>: <score> ops/s` and `Fork <N>: <score> ops/s`).
2. `comparisons/comparison_summary.tsv` (if present): Used to cross-check whole-run mean, variance, and standard deviation calculations.

#### Statistical metrics computed per arm
For arm $A$ ($n_A$ forks) and arm $B$ ($n_B$ forks):
- Fork means: $\bar{T}_A, \bar{T}_B$
- Fork sample variances: $s_A^2, s_B^2$
- Coefficients of variation: $CV_A = s_A / \bar{T}_A, CV_B = s_B / \bar{T}_B$
- Absolute delta: $\Delta = \bar{T}_B - \bar{T}_A$
- Relative delta: $\Delta_{\%} = 100 \cdot \Delta / \bar{T}_A$
- Standard error of the difference: $SE = \sqrt{\frac{s_A^2}{n_A} + \frac{s_B^2}{n_B}}$
- Two-sigma uncertainty: $\text{uncertainty} = 2 \cdot SE$
- Practical significance margin: $\text{practical} = 0.01 \cdot \max(\bar{T}_A, \bar{T}_B)$
- Governing decision margin: $\text{margin} = \text{uncertainty}$. The practical margin remains a
  tie diagnostic and does not suppress an uncertainty-adjusted winner.

---

### 5. Trajectory analysis and late-region qualification (`trajectory.py`)

In `CONTINUOUS` mode, transient warm-up effects can occur across early measurement windows. Rather than guessing an arbitrary window, the loader executes a deterministic qualification algorithm.

```text
+---------------------------------------------------------------------+
| All Ordered Measurement Windows in Fork                             |
| [ W_0 | W_1 | W_2 | W_3 | W_4 | W_5 | W_6 | W_7 ]                   |
+---------------------------------------------------------------------+
                          |
                          v (Take final 50%, min 3 windows)
+---------------------------------------------------------------------+
| Late-Region Windows: [ W_4 | W_5 | W_6 | W_7 ]                      |
| - Verify all continuouslyFed == True                                |
| - Compute Late Mean (\mu_{late}) and Late Variance (s^2_{late})     |
| - Compute Late CV = s_{late} / \mu_{late} as a diagnostic           |
| - Compute OLS slope and classify stable, improving, or declining    |
+---------------------------------------------------------------------+
```

#### Late-region qualification criteria
1. **Window Selection**: Let $W = [w_0, \dots, w_{M-1}]$ be the ordered measurement windows for a fork. The late region is $W_{\text{late}} = W[\lfloor M/2 \rfloor : M]$. Require $|W_{\text{late}}| \ge 3$.
2. **Feeding Continuity**: Every window in $W_{\text{late}}$ must have `continuouslyFed == True`. If any window suffered upstream starvation (`continuouslyFed == False`), mark fork as `UNSTABLE_STARVATION`.
3. **Throughput Dispersion**: Compute and retain
   $$CV_{\text{late}} = \frac{\sigma(W_{\text{late}})}{\mu(W_{\text{late}})}$$
   as a confidence diagnostic. It has no hard eligibility threshold.
4. **Trajectory Direction**: Fit an ordinary least-squares line $T(i) = \beta_0 + \beta_1 \cdot i$ over window indices $i \in W_{\text{late}}$. A fork is stable when
   $$|\beta_1| \le 0.01 \cdot \mu(W_{\text{late}})$$
   and improving when $\beta_1$ is above that band. Classify the policy from the mean normalized
   slope across its independent forks; it is eligible when that aggregate is at least $-1\%$ per
   window. Preserve every fork in this calculation.

Compute the arm's late mean $\bar{T}_{\text{late}}$ and late variance across fork late-means. Apply
the uncertainty-adjusted comparison rule to yield the **Late-Region Outcome**. Starvation or
insufficient windows remain hard validity failures; CV does not.

---

### 6. Winner determination and confidence weighting (`labels.py`)

The loader synthesizes whole-run metrics and late-region metrics into a final training target.

#### Outcome classification rules

```text
If delta > uncertainty:
    outcome = "K_MINUS_1_WINS"   (Arm B is superior; rank K should switch to CACHE)
Else if delta < -uncertainty:
    outcome = "K_WINS"           (Arm A is superior; rank K should participate upstream)
Else if uncertainty <= practical and abs(delta) <= practical:
    outcome = "STABLE_TIE"       (Both configurations achieve equivalent throughput)
Else:
    outcome = "INCONCLUSIVE"     (Variance is too high relative to delta)
```

#### Synthesis between Whole-Run and Late-Region Outcomes

| Whole-run outcome | Late-region outcome | Combined decision | Stability factor | Target label $y$ |
|---|---|---|---|---|
| `K_WINS` | `K_WINS` | $K$ wins when its trajectory is stable/improving | $1.0$ | $0.0$ |
| `K_MINUS_1_WINS` | `K_MINUS_1_WINS` | $K-1$ wins when its trajectory is stable/improving | $1.0$ | $1.0$ |
| `INCONCLUSIVE` | `K_WINS` (winner stable/improving) | Late-convergence $K$ wins | $0.5$ | $0.0$ |
| `INCONCLUSIVE` | `K_MINUS_1_WINS` (winner stable/improving) | Late-convergence $K-1$ wins | $0.5$ | $1.0$ |
| `STABLE_TIE` | `STABLE_TIE` | Stable tie | $1.0$ | $0.5$ |
| Decisive ($A$) | Decisive ($B$) | Inconclusive conflict | $0.0$ | Excluded (no row) |
| Any | Winning policy declining / starved | Inconclusive trajectory | $0.0$ | Excluded (no row) |

#### Confidence weight computation
- **Decisive Pairs** ($y \in \{0.0, 1.0\}$):
  $$\text{separation} = \frac{\max(0, |\Delta| - \text{margin})}{\text{margin}}$$
  $$\text{pairWeight} = \min\left(1.0, \frac{\text{separation}}{2.0}\right) \times \text{stabilityFactor}$$
- **Stable Tie Pairs** ($y = 0.5$):
  $$\text{pairWeight} = \max\left(0.0, 1.0 - \frac{|\Delta|}{\text{practical}}\right) \times \max\left(0.0, 1.0 - \frac{\text{uncertainty}}{\text{practical}}\right)$$

Intermediate metrics ($\Delta, SE, \text{margin}, \text{stabilityFactor}, \text{pairWeight}$) are stored in the record for auditability.

---

### 7. Staleness telemetry extraction and feature aggregation (`staleness.py`)

The physical coordinates for the model must represent what the rank-$K$ worker observes at the decision point in the active arm (Arm A, where ranks $1 \dots K$ participate).

#### Filtering and validation of `contention_staleness.tsv`
For Arm A:
1. **Rank Filtering**: Select only rows where `workerRank == K`.
2. **Segment Filtering**: Use `segment == "steadyState"` (or steady-state samples matching the late window indices).
3. **Readiness Check**: Enforce `bodyHistoryReady == 1`. Exclude early samples before the 32-sample body cost window was filled.
4. **Observation Count Check**: Ensure `contentionObservationCount > 0`. Exclude rows with uninitialized contention.
5. **Finite Range Checks**:
   - `measuredContention` $\in [0, 1\_000\_000]$
   - `smoothedBodyCostNs` $> 0$ and finite
   - `productiveHandleCount` $\ge 0$
   - `registeredWorkers` $== R$ (constant across all rows).

#### Two-stage median aggregation
To eliminate outlier noise without treating sub-millisecond per-cycle telemetry as independent replicates:
1. **Per-Fork Median**: For each JMH fork $f$, compute:
   $$c_f = \text{median}(\text{measuredContention} / 1\_000\_000.0)$$
   $$b_f = \text{median}(\ln(1 + \text{smoothedBodyCostNs}))$$
   $$P_f = \text{median}(\text{productiveHandleCount})$$
2. **Cross-Fork Median**: Aggregate across all forks $f \in \{1 \dots n_A\}$:
   $$c = \text{median}(c_f)$$
   $$b = \text{median}(b_f)$$
   $$P = \text{median}(P_f)$$
   $$R = \text{registeredWorkers}$$

#### Diagnostic state for Arm B
Extract the corresponding post-treatment rank-$K$ telemetry in Arm B ($K-1$). In Arm B, rank $K$ is forced into `CACHE`.
- Record $c_{\text{withdrawn}}, P_{\text{withdrawn}}, \text{localCacheCount}, \text{executionPath}$.
- Verify that `executionPath == "CACHE"` and that acquisition attempts ceased.
- Store these fields in the audit record for actuator verification, but **do not** include them in the feature vector for Step 5 fitting.

---

### 8. Feature vector construction (`types.py` / `loader.py`)

For each accepted pair with active-arm state $(c, b, P, R)$ and candidate rank $K$:

$$q = \frac{P}{K(K-1)}$$

The 8-element linear feature vector $\mathbf{x}$ corresponds directly to the eight parameters $[w_0, w_1, w_2, w_3, w_4, w_5, w_6, w_7]$:

$$\mathbf{x} = \begin{bmatrix} q \\ c \cdot q \\ b \cdot q \\ R \cdot q \\ -1.0 \\ -c \\ -b \\ -R \end{bmatrix}$$

Such that:

$$\text{marginal}(K) = \mathbf{x}^T \mathbf{w} = (w_0 + w_1 c + w_2 b + w_3 R) q - (w_4 + w_5 c + w_6 b + w_7 R)$$

---

### 9. Auditable tabular export: `pairs.tsv` (`export.py`)

The loader can write the joined dataset to a single self-contained TSV file (`pairs.tsv`) accompanied by `pairs.tsv.sha256`.

#### Schema of `pairs.tsv`

| Column Name | Type | Description |
|---|---|---|
| `pairId` | string | Unique stable identifier for the adjacent pair |
| `runtimeCommit` | string | Git commit SHA of the engine build |
| `topologyId` | string | Physical topology identifier (e.g. `linux-x86_64-8p16e`) |
| `lifecycleMode` | string | Lifecycle mode (must be `CONTINUOUS`) |
| `cacheActuatorVersion`| string | Actuator version (must be `cache-v1`) |
| `cacheParkNs` | int | CACHE miss park duration in nanoseconds |
| `K` | int | Candidate active rank under test ($2 \le K \le R$) |
| `registeredWorkers` | int | Total registered physical workers ($R$) |
| `workUnits` | int | Work units per task |
| `c_active` | double | Normalized contention in $[0, 1]$ from Arm A ($K$) |
| `smoothedBodyCostNs_active` | double | Raw body cost in ns from Arm A |
| `b_active` | double | $\ln(1 + \text{smoothedBodyCostNs})$ from Arm A |
| `P_active` | double | Productive handles count from Arm A |
| `c_withdrawn` | double | Stale contention observed in Arm B ($K-1$) |
| `P_withdrawn` | double | Productive handles observed in Arm B ($K-1$) |
| `meanThroughput_K` | double | Fork mean throughput for Arm A (ops/sec) |
| `variance_K` | double | Fork variance for Arm A |
| `cv_K` | double | Fork coefficient of variation for Arm A |
| `forkCount_K` | int | Fork count for Arm A |
| `meanThroughput_KMinus1`| double | Fork mean throughput for Arm B (ops/sec) |
| `variance_KMinus1` | double | Fork variance for Arm B |
| `cv_KMinus1` | double | Fork coefficient of variation for Arm B |
| `forkCount_KMinus1` | int | Fork count for Arm B |
| `deltaThroughput` | double | $\bar{T}_{K-1} - \bar{T}_K$ |
| `relativeDeltaPercent` | double | $100 \cdot \Delta / \bar{T}_K$ |
| `governingMargin` | double | $\max(\text{uncertainty}, \text{practical})$ |
| `wholeRunOutcome` | string | `K_WINS`, `K_MINUS_1_WINS`, `STABLE_TIE`, `INCONCLUSIVE` |
| `lateRegionOutcome` | string | `K_WINS`, `K_MINUS_1_WINS`, `STABLE_TIE`, `INCONCLUSIVE` |
| `trajectoryStatus` | string | `STABLE_AGREEMENT`, `LATE_CONVERGENCE`, `CONFLICT`, `STARVATION` |
| `y` | double | Target label ($0.0 = K \text{ wins}, 1.0 = K-1 \text{ wins}, 0.5 = \text{tie}$) |
| `pairWeight` | double | Confidence weight $\in [0.0, 1.0]$ |
| `kRunPath` | string | Relative path to Arm A trial root |
| `kRunSha256` | string | Checksum of Arm A `contention_staleness.tsv` |
| `kMinus1RunPath` | string | Relative path to Arm B trial root |
| `kMinus1RunSha256` | string | Checksum of Arm B `contention_staleness.tsv` |

---

## Data model declarations (`types.py`)

```python
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Dict, List, Optional
import numpy as np

class Outcome(str, Enum):
    K_WINS = "K_WINS"
    K_MINUS_1_WINS = "K_MINUS_1_WINS"
    STABLE_TIE = "STABLE_TIE"
    INCONCLUSIVE = "INCONCLUSIVE"

class TrajectoryStatus(str, Enum):
    STABLE_AGREEMENT = "STABLE_AGREEMENT"
    LATE_CONVERGENCE = "LATE_CONVERGENCE"
    CONFLICT = "CONFLICT"
    STARVATION = "STARVATION"
    UNSTABLE_DISPERSION = "UNSTABLE_DISPERSION"

@dataclass(frozen=True)
class ForkThroughput:
    fork_index: int
    mean_ops_per_sec: float
    window_scores: List[float]
    late_mean_ops_per_sec: float
    is_late_stable: bool

@dataclass(frozen=True)
class ArmPerformance:
    mean: float
    variance: float
    std_dev: float
    cv: float
    fork_count: int
    late_mean: float
    late_variance: float
    late_cv: float
    forks: List[ForkThroughput]

@dataclass(frozen=True)
class ActiveStateFeatures:
    c: float                     # Contention in [0, 1]
    smoothed_body_cost_ns: float # Raw body cost
    b: float                     # ln(1 + body)
    P: float                     # Productive handles
    R: int                       # Registered workers
    K: int                       # Candidate rank under test

    @property
    def q(self) -> float:
        return self.P / (self.K * (self.K - 1))

    @property
    def feature_vector(self) -> np.ndarray:
        q = self.q
        return np.array([
            q,
            self.c * q,
            self.b * q,
            self.R * q,
            -1.0,
            -self.c,
            -self.b,
            -float(self.R)
        ], dtype=np.float64)

@dataclass(frozen=True)
class WithdrawnDiagnosticState:
    c_stale: float
    P_stale: float
    local_cache_count: int
    execution_path: str
    acquisitions_attempted: int

@dataclass(frozen=True)
class PairRecord:
    pair_id: str
    topology_id: str
    runtime_commit: str
    cache_actuator_version: str
    cache_park_ns: int
    K: int
    features: ActiveStateFeatures
    withdrawn_diagnostics: WithdrawnDiagnosticState
    perf_k: ArmPerformance
    perf_k_minus_1: ArmPerformance
    delta: float
    rel_delta_percent: float
    uncertainty: float
    practical_margin: float
    governing_margin: float
    whole_outcome: Outcome
    late_outcome: Outcome
    trajectory_status: TrajectoryStatus
    y: float
    pair_weight: float
    k_run_path: Path
    k_minus_1_run_path: Path
    artifact_checksums: Dict[str, str] = field(default_factory=dict)
```

---

## Command line interface (`cli.py`)

The CLI exposes high-level subcommands for validation and dataset inspection:

```bash
# Validate manifest, run directories, and checksum sidecars without full join
python -m pareto_weight_calibration validate --manifest path/to/dataset_manifest.json

# Ingest manifest and emit verified pairs.tsv table
python -m pareto_weight_calibration load \
    --manifest path/to/dataset_manifest.json \
    --output data/calibration/pairs.tsv \
    --min-weight 0.01

# Inspect pair summary and label distributions
python -m pareto_weight_calibration summary --pairs data/calibration/pairs.tsv
```

---

## Comprehensive test specification

The loader implementation in Step 2 will be backed by a full test suite under `python/pareto-weight-calibration/tests/`.

### 1. Checksum unit tests (`test_checksum.py`)
- `test_valid_checksum_passes`: Correct SHA-256 sidecar returns verified digest.
- `test_corrupted_file_fails`: Mutating one byte in a TSV raises `ChecksumMismatchError`.
- `test_missing_checksum_fails`: File lacking `.sha256` sidecar raises `MissingChecksumError`.

### 2. Configuration compatibility tests (`test_config_compatibility.py`)
- `test_valid_pair_compatibility`: Identical fixtures with $K$ and $K-1$ pass validation.
- `test_mismatched_actuator_fails`: Differing `cacheActuatorVersion` (e.g. `cache-v1` vs `legacy-unspecified`) is rejected.
- `test_mismatched_park_ns_fails`: Differing `cacheParkNs` (e.g. 15000 vs 20000) is rejected.
- `test_non_continuous_lifecycle_fails`: `RESET` lifecycle mode is rejected.
- `test_mismatched_cpu_set_fails`: Differing CPU allocations are rejected.
- `test_invalid_rank_difference_fails`: Arms differing by more than 1 in cutoff (e.g. $K=8$ vs $K=6$) are rejected.

### 3. Trajectory and stability tests (`test_trajectory.py`)
- `test_stable_trajectory_accepted`: Continuous feeding, $CV \le 3\%$, slope $\le 0.5\%$ passes.
- `test_starved_trajectory_rejected`: Window with `continuouslyFed == False` triggers `STARVATION`.
- `test_high_cv_improving_trajectory_accepted`: CV remains diagnostic while an improving slope is eligible.
- `test_declining_trajectory_rejected`: a winning policy declining faster than $1\%$ per window is excluded.
- `test_upward_trajectory_accepted`: Upward drift above $1\%$ per window is classified improving.

### 4. Staleness telemetry tests (`test_staleness.py`)
- `test_rank_k_filtering`: Telemetry for ranks other than $K$ is ignored.
- `test_body_history_unready_rejected`: Rows with `bodyHistoryReady == 0` are excluded.
- `test_uninitialized_contention_rejected`: Rows with 0 contention observations are excluded.
- `test_two_stage_median_math`: Verifies per-fork median followed by cross-fork median against known synthetic values.

### 5. Label and weighting tests (`test_labels.py`)
- `test_decisive_k_wins`: Large negative $\Delta$ produces $y = 0.0$ and positive weight.
- `test_decisive_k_minus_1_wins`: Large positive $\Delta$ produces $y = 1.0$ and positive weight.
- `test_stable_tie`: $|\Delta| \le \text{practical}$ with low variance produces $y = 0.5$.
- `test_inconclusive_excluded`: Large variance producing uncertainty $>$ delta produces zero weight.
- `test_late_convergence_weight_discount`: Disagreement resolved by stable late region receives 0.5 scaling factor.

### 6. End-to-end loader integration tests (`test_loader.py`)
- `test_end_to_end_mock_manifest`: Loads complete synthetic mock fixture, produces verified `PairRecord`, validates linear feature vector $\mathbf{x}$, and exports `pairs.tsv`.

---

## Explicit non-goals of Step 2

- **No Model Fitting**: Step 2 does not evaluate loss functions, compute gradients, or optimize $w_0 \dots w_7$ (reserved for Step 5).
- **No Production Java Runtime Dependency**: Step 2 does not require running a JVM or importing Java classes.
- **No Actuator Modifications**: Step 2 does not alter Java engine code or JMH benchmarks.
- **No Data Generation**: Step 2 only ingests existing artifacts generated by Step 1 and Step 3.
- **No Untrained Runtime Heuristics**: Step 2 produces clean, un-doctored causal training observations.
