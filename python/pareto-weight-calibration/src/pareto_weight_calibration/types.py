"""Type definitions and data models for the Pareto-weight calibration pipeline."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
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
    artifact_checksums: Dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class ManifestPair:
    """Declaration of an adjacent pair within a dataset manifest."""
    pair_id: str
    k_run_path: Path
    k_minus_1_run_path: Path
    K: int
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
