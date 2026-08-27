"""Type definitions and data models for the Pareto-weight calibration pipeline."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
import math
from pathlib import Path
from typing import Any, Dict, List, Optional
import numpy as np


class Outcome(str, Enum):
    """Comparison outcome between Arm A (K) and Arm B (K-1)."""
    K_WINS = "K_WINS"
    K_MINUS_1_WINS = "K_MINUS_1_WINS"
    STABLE_TIE = "STABLE_TIE"
    INCONCLUSIVE = "INCONCLUSIVE"


class TrajectoryStatus(str, Enum):
    """Classification of time-series window trajectory stability."""
    STABLE_AGREEMENT = "STABLE_AGREEMENT"
    LATE_CONVERGENCE = "LATE_CONVERGENCE"
    CONFLICT = "CONFLICT"
    STARVATION = "STARVATION"
    INELIGIBLE_TRAJECTORY = "INELIGIBLE_TRAJECTORY"
    UNSTABLE_DISPERSION = "UNSTABLE_DISPERSION"
    INSUFFICIENT_WINDOWS = "INSUFFICIENT_WINDOWS"


class ArtifactEligibility(str, Enum):
  """Three-state classification of calibration artifact eligibility."""
  ELIGIBLE = "ELIGIBLE"
  INELIGIBLE = "INELIGIBLE"
  UNVERIFIABLE = "UNVERIFIABLE"


class LabelEvidenceBasis(str, Enum):
  """Evidence basis used for label synthesis and validation regret."""
  WHOLE_AGREEMENT = "WHOLE_AGREEMENT"
  LATE_CONVERGENCE = "LATE_CONVERGENCE"
  STABLE_TIE = "STABLE_TIE"
  NONE = "NONE"


@dataclass(frozen=True)
class ForkThroughput:
    """Throughput and late-trajectory summary for an individual JMH fork."""
    fork_index: int
    mean_ops_per_sec: float
    window_scores: List[float] = field(default_factory=list)
    late_mean_ops_per_sec: float = 0.0
    is_late_stable: bool = False
    is_late_improving: bool = False
    late_is_continuously_fed: bool = True
    late_has_sufficient_windows: bool = True
    late_cv: float = 0.0
    late_slope: float = 0.0
    fork_identifier: Optional[str] = None


@dataclass(frozen=True)
class ArmPerformance:
    """Aggregated performance metrics across all forks of an experimental arm."""
    mean: float
    variance: float
    std_dev: float
    cv: float
    fork_count: int
    late_mean: float
    late_variance: float
    late_cv: float
    forks: List[ForkThroughput] = field(default_factory=list)


@dataclass(frozen=True)
class ActiveStateFeatures:
    """Decision-point observation coordinates for the active rank-K worker in Arm A."""
    c: float                     # Contention in [0.0, 1.0]
    smoothed_body_cost_ns: float # Raw smoothed body cost in nanoseconds
    b: float                     # ln(1 + smoothedBodyCostNs)
    P: float                     # Productive handle count
    R: int                       # Registered workers count
    K: int                       # Active candidate rank under test

    @property
    def q(self) -> float:
        """Normalized productive handle ratio: P / (K * (K - 1))."""
        if self.K <= 1:
            return 0.0
        return self.P / float(self.K * (self.K - 1))

    @property
    def feature_vector(self) -> np.ndarray:
        """Linear 8-term feature vector corresponding to [w0..w7]."""
        q_val = self.q
        return np.array([
            q_val,
            self.c * q_val,
            self.b * q_val,
            float(self.R) * q_val,
            -1.0,
            -self.c,
            -self.b,
            -float(self.R)
        ], dtype=np.float64)


@dataclass(frozen=True)
class WithdrawnDiagnosticState:
    """Post-treatment observation coordinates for the rank-K worker in Arm B (CACHE)."""
    c_stale: float
    P_stale: float
    local_cache_count: int
    execution_path: str
    acquisitions_attempted: int


@dataclass(frozen=True)
class ComparisonMetrics:
    """Variance-aware comparison metrics between two arms."""
    delta: float
    rel_delta_percent: float
    uncertainty: float
    practical_margin: float
    governing_margin: float
    outcome: Outcome


@dataclass(frozen=True)
class PairRecord:
    """Complete joined, verified record for one adjacent K vs K-1 experimental unit."""
    pair_id: str
    topology_id: str
    runtime_commit: str
    cache_actuator_version: str
    cache_park_ns: int
    K: int
    registered_workers: int
    work_units: int
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
    parallel_sources: int = 1
    effective_outcome: Outcome = Outcome.INCONCLUSIVE
    label_evidence_basis: LabelEvidenceBasis = LabelEvidenceBasis.NONE
    basis_throughput_k: float = 0.0
    basis_throughput_k_minus_1: float = 0.0
    basis_delta: float = 0.0
    basis_variance_k: float = 0.0
    basis_variance_k_minus_1: float = 0.0
    basis_uncertainty: float = 0.0
    eligibility: ArtifactEligibility = ArtifactEligibility.ELIGIBLE
    k_sample_paths: List[Path] = field(default_factory=list)
    k_minus_1_sample_paths: List[Path] = field(default_factory=list)
    artifact_checksums: Dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class ManifestPair:
    """Declaration of an adjacent pair within a dataset manifest."""
    pair_id: str
    k_run_path: Path
    k_minus_1_run_path: Path
    K: int
    k_sample_paths: List[Path] = field(default_factory=list)
    k_minus_1_sample_paths: List[Path] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Manifest:
    """Root dataset manifest definition."""
    schema_version: int
    runtime_commit: str
    cache_actuator_version: str
    cache_park_ns: int
    topology_id: str
    pairs: List[ManifestPair] = field(default_factory=list)


@dataclass(frozen=True)
class TrialCalibrationConfig:
    """Calibration settings from trial_config.json."""
    cpu_set: List[int]
    parallel_sources: int
    ordered_sources: int
    work_units: int
    randomize_work: bool
    total_required_executions: int
    invocation_timeout_millis: int
    raw_sample_limit: int
    observe_cycle_start: bool
    observe_batch_progress: bool
    observe_batch_complete: bool
    observe_raw_body_cost: bool
    observe_idle_decision: bool
    observe_exec_decision: bool
    observe_contention_staleness: bool
    forced_active_participant_count: Optional[int]
    cache_park_ns: int
    cache_actuator_version: str
    lifecycle_mode: str
    decision_weights: Optional[Dict[str, Any]] = None
    decision_weight_profile: Optional[str] = None


@dataclass(frozen=True)
class TrialConfig:
    """Trial configuration loaded from trial_config.json."""
    id: Optional[str]
    name: Optional[str]
    group: Optional[str]
    forks: int
    warmups: int
    iterations: int
    warmup_time: Optional[str]
    measurement_time: Optional[str]
    jvm_args: List[str]
    calibration_config: TrialCalibrationConfig
    raw_json: Dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class DomainConfig:
  """Deployment domain bounds for mathematical constraints and evaluation."""
  c_min: float = 0.0
  c_max: float = 1.0
  body_cost_min_ns: float = 10.0
  body_cost_max_ns: float = 5_000_000.0
  p_min: float = 1.0
  p_max: float = 32.0
  r_min: int = 2
  r_max: int = 32
  k_min: int = 2

  @property
  def b_min(self) -> float:
    return math.log1p(self.body_cost_min_ns)

  @property
  def b_max(self) -> float:
    return math.log1p(self.body_cost_max_ns)

  def to_dict(self) -> Dict[str, Any]:
    return {
      "c_min": self.c_min,
      "c_max": self.c_max,
      "body_cost_min_ns": self.body_cost_min_ns,
      "body_cost_max_ns": self.body_cost_max_ns,
      "p_min": self.p_min,
      "p_max": self.p_max,
      "r_min": self.r_min,
      "r_max": self.r_max,
      "k_min": self.k_min,
    }

  @classmethod
  def from_dict(cls, d: Dict[str, Any]) -> DomainConfig:
    return cls(
        c_min=float(d.get("c_min", 0.0)),
        c_max=float(d.get("c_max", 1.0)),
        body_cost_min_ns=float(d.get("body_cost_min_ns", 10.0)),
        body_cost_max_ns=float(d.get("body_cost_max_ns", 5_000_000.0)),
        p_min=float(d.get("p_min", 1.0)),
        p_max=float(d.get("p_max", 32.0)),
        r_min=int(d.get("r_min", 2)),
        r_max=int(d.get("r_max", 32)),
        k_min=int(d.get("k_min", 2)),
    )


@dataclass
class Dataset:
  """Assembled feature matrix, labels, family groupings, and influence weights."""
  records: List[PairRecord]
  X: np.ndarray  # (N, 8)
  y: np.ndarray  # (N,)
  v: np.ndarray  # (N,) Step 4 confidence weights in (0, 1]
  u: np.ndarray  # (N,) Bounded family influence weights
  families: List[str]  # (N,) Physical family name for each observation
  family_scales: Dict[str, float]
  family_counts: Dict[str, Dict[str, Any]]
  U: float  # sum(u_i)
  n_eff: float  # (sum u_i)^2 / sum(u_i^2)


@dataclass
class IdentifiabilityAuditResult:
  """Complete diagnostics of dataset rank, spectra, collinearity, and coverage."""
  numerical_rank: int
  dimension: int
  singular_values_raw: np.ndarray
  singular_values_weighted: np.ndarray
  lapack_threshold: float
  condition_number: float
  smallest_nonzero_singular_value: float
  is_rank_deficient: bool
  vifs: Dict[int, float]
  collinear_dependencies: List[str]
  coordinate_coverage: Dict[str, Dict[str, Any]]
  class_conditional_coverage: Dict[str, Dict[str, Any]]
  separation_detected: bool
  separation_details: Dict[str, Any]
  unsupported_feature_groups: List[str]
  effective_sample_size: float
  total_influence_weight: float
