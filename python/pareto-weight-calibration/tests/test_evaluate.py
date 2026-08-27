"""Tests for regret calculation, evidence basis handling, and baseline selection."""

from __future__ import annotations

from pathlib import Path
import numpy as np
import pytest

from pareto_weight_calibration.evaluate import (
  compute_fixed_cutoff_logits,
  compute_observation_loss,
  evaluate_always_cache,
  evaluate_always_participate,
  evaluate_fixed_cutoff,
  evaluate_predictions,
  extract_record_evidence_basis,
  select_best_fixed_cutoff_on_train,
)
from pareto_weight_calibration.types import (
  ActiveStateFeatures,
  ArmPerformance,
  ArtifactEligibility,
  ForkThroughput,
  LabelEvidenceBasis,
  Outcome,
  PairRecord,
  TrajectoryStatus,
  WithdrawnDiagnosticState,
)


def _make_test_record(
    pair_id: str,
    k: int = 5,
    r: int = 23,
    y: float = 0.0,
    effective_outcome: Outcome = Outcome.K_WINS,
    label_evidence_basis: LabelEvidenceBasis = LabelEvidenceBasis.WHOLE_AGREEMENT,
    whole_mean_k: float = 1000.0,
    whole_mean_k_minus_1: float = 900.0,
    late_mean_k: float = 1200.0,
    late_mean_k_minus_1: float = 1100.0,
    uncertainty: float = 20.0,
    pair_weight: float = 1.0,
    parallel_sources: int = 2,
    work_units: int = 112,
) -> PairRecord:
  perf_k = ArmPerformance(
      mean=whole_mean_k,
      variance=100.0,
      std_dev=10.0,
      cv=0.01,
      fork_count=4,
      late_mean=late_mean_k,
      late_variance=100.0,
      late_cv=0.01,
  )
  perf_k_minus_1 = ArmPerformance(
      mean=whole_mean_k_minus_1,
      variance=100.0,
      std_dev=10.0,
      cv=0.01,
      fork_count=4,
      late_mean=late_mean_k_minus_1,
      late_variance=100.0,
      late_cv=0.01,
  )
  features = ActiveStateFeatures(
      c=0.2,
      smoothed_body_cost_ns=100.0,
      b=np.log1p(100.0),
      P=4.0,
      R=r,
      K=k,
  )
  withdrawn = WithdrawnDiagnosticState(
      c_stale=0.2,
      P_stale=4.0,
      local_cache_count=100,
      execution_path="CACHE",
      acquisitions_attempted=100,
  )

  if label_evidence_basis == LabelEvidenceBasis.LATE_CONVERGENCE:
    basis_t_k = late_mean_k
    basis_t_km1 = late_mean_k_minus_1
  else:
    basis_t_k = whole_mean_k
    basis_t_km1 = whole_mean_k_minus_1
  basis_delta = basis_t_km1 - basis_t_k

  return PairRecord(
      pair_id=pair_id,
      topology_id="topo_1",
      runtime_commit="abc1234",
      cache_actuator_version="v1",
      cache_park_ns=100,
      K=k,
      registered_workers=r,
      work_units=work_units,
      features=features,
      withdrawn_diagnostics=withdrawn,
      perf_k=perf_k,
      perf_k_minus_1=perf_k_minus_1,
      delta=whole_mean_k_minus_1 - whole_mean_k,
      rel_delta_percent=-10.0,
      uncertainty=uncertainty,
      practical_margin=10.0,
      governing_margin=10.0,
      whole_outcome=Outcome.K_WINS,
      late_outcome=Outcome.K_WINS,
      trajectory_status=TrajectoryStatus.STABLE_AGREEMENT,
      y=y,
      pair_weight=pair_weight,
      k_run_path=Path("/tmp/k"),
      k_minus_1_run_path=Path("/tmp/km1"),
      parallel_sources=parallel_sources,
      effective_outcome=effective_outcome,
      label_evidence_basis=label_evidence_basis,
      basis_throughput_k=basis_t_k,
      basis_throughput_k_minus_1=basis_t_km1,
      basis_delta=basis_delta,
      basis_variance_k=100.0,
      basis_variance_k_minus_1=100.0,
      basis_uncertainty=uncertainty,
      eligibility=ArtifactEligibility.ELIGIBLE,
  )


def test_regret_evidence_basis_whole_vs_late():
  """Validates that regret uses the exact evidence basis (WHOLE vs LATE) that produced the label."""
  # Whole-run agreement record: T(K)=1000, T(K-1)=900, delta=-100, uncertainty=20 => a = max(0, 100 - 20) = 80
  rec_whole = _make_test_record(
      pair_id="p_whole",
      y=0.0,
      effective_outcome=Outcome.K_WINS,
      label_evidence_basis=LabelEvidenceBasis.WHOLE_AGREEMENT,
      whole_mean_k=1000.0,
      whole_mean_k_minus_1=900.0,
      late_mean_k=2000.0,
      late_mean_k_minus_1=1800.0,
      uncertainty=20.0,
  )
  # Late-convergence record: late T(K)=2000, late T(K-1)=1800, delta=-200, uncertainty=30 => a = max(0, 200 - 30) = 170
  rec_late = _make_test_record(
      pair_id="p_late",
      y=0.0,
      effective_outcome=Outcome.K_WINS,
      label_evidence_basis=LabelEvidenceBasis.LATE_CONVERGENCE,
      whole_mean_k=1000.0,
      whole_mean_k_minus_1=900.0,
      late_mean_k=2000.0,
      late_mean_k_minus_1=1800.0,
      uncertainty=30.0,
  )

  # If model predicts wrong decision: z > 0 (CACHE)
  obs_w = compute_observation_loss(rec_whole, z=1.0)
  assert obs_w.is_decisive is True
  assert obs_w.is_correct is False
  assert obs_w.t_k == 1000.0
  assert obs_w.delta == -100.0
  assert obs_w.supported_advantage == 80.0
  assert obs_w.supported_loss == 80.0
  assert obs_w.observed_loss == 100.0
  assert obs_w.supported_rel_loss == 80.0 / 1000.0

  obs_l = compute_observation_loss(rec_late, z=1.0)
  assert obs_l.is_decisive is True
  assert obs_l.is_correct is False
  assert obs_l.t_k == 2000.0
  assert obs_l.delta == -200.0
  assert obs_l.supported_advantage == 170.0
  assert obs_l.supported_loss == 170.0
  assert obs_l.observed_loss == 200.0
  assert obs_l.supported_rel_loss == 170.0 / 2000.0

  # If model predicts correct decision: z <= 0 (PARTICIPATE)
  obs_correct = compute_observation_loss(rec_whole, z=-1.0)
  assert obs_correct.is_correct is True
  assert obs_correct.supported_loss == 0.0
  assert obs_correct.observed_loss == 0.0
  assert obs_correct.supported_rel_loss == 0.0


def test_stable_ties_zero_regret():
  """Validates that stable ties incur zero winner regret and evaluate neutral-band metrics."""
  rec_tie = _make_test_record(
      pair_id="p_tie",
      y=0.5,
      effective_outcome=Outcome.STABLE_TIE,
      label_evidence_basis=LabelEvidenceBasis.STABLE_TIE,
      whole_mean_k=1000.0,
      whole_mean_k_minus_1=1000.0,
      uncertainty=10.0,
  )
  # Prediction with |z| <= 0.5 is neutral
  obs_tie_neutral = compute_observation_loss(rec_tie, z=0.1,
                                             neutral_threshold=0.5)
  assert obs_tie_neutral.is_stable_tie is True
  assert obs_tie_neutral.supported_loss == 0.0
  assert obs_tie_neutral.supported_rel_loss == 0.0
  assert obs_tie_neutral.is_tie_neutral is True

  # Prediction with |z| > 0.5 is not inside neutral band, but still zero winner regret
  obs_tie_non_neutral = compute_observation_loss(rec_tie, z=2.0,
                                                 neutral_threshold=0.5)
  assert obs_tie_non_neutral.supported_loss == 0.0
  assert obs_tie_non_neutral.is_tie_neutral is False


def test_baseline_selection_on_training_fold():
  """Validates deterministic selection of best fixed cutoff K0 inside training fold."""
  # Build synthetic training records with varying K (2..6)
  # Suppose true optimal cutoff is K=4:
  # For K <= 4, K_WINS (participate is better)
  # For K > 4, K_MINUS_1_WINS (CACHE is better)
  train_records = [
    _make_test_record("r2", k=2, y=0.0, effective_outcome=Outcome.K_WINS,
                      whole_mean_k=1000.0, whole_mean_k_minus_1=800.0),
    _make_test_record("r3", k=3, y=0.0, effective_outcome=Outcome.K_WINS,
                      whole_mean_k=1000.0, whole_mean_k_minus_1=850.0),
    _make_test_record("r4", k=4, y=0.0, effective_outcome=Outcome.K_WINS,
                      whole_mean_k=1000.0, whole_mean_k_minus_1=900.0),
    _make_test_record("r5", k=5, y=1.0,
                      effective_outcome=Outcome.K_MINUS_1_WINS,
                      whole_mean_k=900.0, whole_mean_k_minus_1=1000.0),
    _make_test_record("r6", k=6, y=1.0,
                      effective_outcome=Outcome.K_MINUS_1_WINS,
                      whole_mean_k=800.0, whole_mean_k_minus_1=1000.0),
  ]
  u_train = np.ones(len(train_records), dtype=np.float64)

  best_k0 = select_best_fixed_cutoff_on_train(train_records, u_train,
                                              candidate_k0s=[2, 3, 4, 5, 6])
  assert best_k0 == 4

  # Evaluate best cutoff on validation
  val_records = [
    _make_test_record("v3", k=3, y=0.0, effective_outcome=Outcome.K_WINS,
                      whole_mean_k=1000.0, whole_mean_k_minus_1=850.0),
    _make_test_record("v5", k=5, y=1.0,
                      effective_outcome=Outcome.K_MINUS_1_WINS,
                      whole_mean_k=900.0, whole_mean_k_minus_1=1000.0),
  ]
  u_val = np.ones(len(val_records), dtype=np.float64)
  val_met = evaluate_fixed_cutoff(val_records, best_k0, u_val)
  # For K=3 <= 4, participates (correct); for K=5 > 4, caches (correct) => 0 regret
  assert val_met.supported_rel_regret == 0.0
  assert val_met.winner_accuracy == 1.0
