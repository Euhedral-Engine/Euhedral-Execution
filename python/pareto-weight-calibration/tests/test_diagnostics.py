"""Tests for fitting diagnostics: evaluator parity, prefix, stability, ablation, and sensitivity."""

from __future__ import annotations

from pathlib import Path
import numpy as np
import pytest

from pareto_weight_calibration.audit import get_physical_family_id
from pareto_weight_calibration.constraints import (
  MODEL_STRUCTURES,
  DomainConfig,
  load_domain_config,
)
from pareto_weight_calibration.cv import (
  compute_fold_influence_weights,
  execute_lofo_grid_search,
)
from pareto_weight_calibration.diagnostics import (
  build_common_reference_scales,
  compare_common_reference_stability,
  compute_common_reference_representation,
  diagnose_fixed_state_prefix,
  evaluate_direct_physical_marginal,
  run_adjacent_lambda_sensitivity,
  run_low_confidence_ablation,
  verify_evaluator_parity,
  verify_evaluator_parity_grid,
)
from pareto_weight_calibration.model import LogicalWeights, MarginalModel
from pareto_weight_calibration.optimizer import fit_constrained_model
from pareto_weight_calibration.scaling import compute_training_scales
from pareto_weight_calibration.types import (
  ActiveStateFeatures,
  ArmPerformance,
  ArtifactEligibility,
  Dataset,
  LabelEvidenceBasis,
  Outcome,
  PairRecord,
  TrajectoryStatus,
  WithdrawnDiagnosticState,
)


@pytest.fixture
def test_domain() -> DomainConfig:
  return DomainConfig(
      c_min=0.0,
      c_max=1.0,
      body_cost_min_ns=10.0,
      body_cost_max_ns=5000.0,
      r_min=2,
      r_max=32,
  )


@pytest.fixture
def valid_m8_weights() -> np.ndarray:
  # A valid physical vector satisfying corner constraints: A(c,b,R) <= 0
  # w = [w0..w7]
  # w0=-2.0, w1=0.5, w2=0.05, w3=0.01 -> for all (c,b,R), A <= -2.0 + 0.5 + 0.05*8.5 + 0.01*32 = -0.75 <= 0
  # w4=0.8, w5=0.2, w6=0.05, w7=0.01 -> B is positive
  return np.array([-2.0, 0.5, 0.05, 0.01, 0.8, 0.2, 0.05, 0.01],
                  dtype=np.float64)


def test_python_evaluator_parity_and_grid(test_domain, valid_m8_weights):
  """Validates Python vector dot product exactly equals direct unrolled physical formula."""
  # Test single point
  passed, vm, dm, diff = verify_evaluator_parity(
      w_phys=valid_m8_weights,
      c=0.4,
      smoothed_body_cost_ns=300.0,
      P=8.0,
      R=16,
      K=4,
  )
  assert passed is True
  assert diff <= 1e-12
  assert np.isclose(vm, dm, atol=1e-12)

  # Test full domain grid
  grid_passed = verify_evaluator_parity_grid(
      w_phys=valid_m8_weights,
      domain=test_domain,
      grid_points=5,
      tol=1e-12,
  )
  assert grid_passed is True


def test_fixed_state_prefix_diagnostics(test_domain, valid_m8_weights):
  """Validates fixed-state prefix diagnostics over domain corners and deterministic grid."""
  diag = diagnose_fixed_state_prefix(
      w_phys=valid_m8_weights,
      domain=test_domain,
      grid_points_per_axis=5,
      tol=1e-12,
  )

  assert diag.all_corners_satisfied is True
  assert len(diag.corner_values) == 8
  assert diag.max_corner_value <= 1e-12
  assert diag.grid_monotonicity_satisfied is True
  assert diag.reversals_count == 0
  assert diag.grid_states_checked > 0
  assert diag.fixed_state_prefix_verified is True
  assert "Worker-local input variations" in diag.summary


def test_common_reference_stability_diagnostics(test_domain, valid_m8_weights):
  """Validates S_ref scaling, cosine similarity, directional distance, sign stability, and factor A/B diffs."""
  s_ref = np.array([1.5, 0.8, 4.2, 18.0, 1.0, 0.5, 3.1, 15.0], dtype=np.float64)

  # Identity comparison
  stab_self = compare_common_reference_stability(
      w_phys_1=valid_m8_weights,
      w_phys_2=valid_m8_weights,
      s_ref=s_ref,
      domain=test_domain,
  )
  assert np.isclose(stab_self.cosine_similarity, 1.0, atol=1e-12)
  assert np.isclose(stab_self.angular_distance_deg, 0.0, atol=1e-9)
  assert np.isclose(stab_self.max_directional_distance, 0.0, atol=1e-12)
  assert stab_self.sign_stability_agreement is True
  assert len(stab_self.sign_mismatches) == 0
  assert stab_self.max_factor_a_corner_diff <= 1e-12
  assert stab_self.max_factor_b_corner_diff <= 1e-12

  # Perturbed comparison
  perturbed_w = valid_m8_weights.copy()
  perturbed_w[0] += 0.05
  stab_pert = compare_common_reference_stability(
      w_phys_1=valid_m8_weights,
      w_phys_2=perturbed_w,
      s_ref=s_ref,
      domain=test_domain,
  )
  assert stab_pert.cosine_similarity < 1.0
  assert stab_pert.angular_distance_deg > 0.0
  assert stab_pert.max_directional_distance > 0.0
  assert stab_pert.max_factor_a_corner_diff > 0.0


def _make_mock_record(pair_id: str, r: int, s: int, wu: int, k: int, y: float,
    weight: float) -> PairRecord:
  perf_k = ArmPerformance(1000.0, 100.0, 10.0, 0.01, 4, 1000.0, 100.0, 0.01)
  perf_km1 = ArmPerformance(900.0 if y == 0.0 else 1100.0, 100.0, 10.0, 0.01, 4,
                            900.0 if y == 0.0 else 1100.0, 100.0, 0.01)
  features = ActiveStateFeatures(
      c=0.3,
      smoothed_body_cost_ns=150.0,
      b=np.log1p(150.0),
      P=4.0,
      R=r,
      K=k,
  )
  withdrawn = WithdrawnDiagnosticState(0.3, 4.0, 100, "CACHE", 100)
  outcome = Outcome.K_WINS if y == 0.0 else Outcome.K_MINUS_1_WINS
  return PairRecord(
      pair_id=pair_id,
      topology_id="topo_1",
      runtime_commit="abc1234",
      cache_actuator_version="v1",
      cache_park_ns=100,
      K=k,
      registered_workers=r,
      work_units=wu,
      features=features,
      withdrawn_diagnostics=withdrawn,
      perf_k=perf_k,
      perf_k_minus_1=perf_km1,
      delta=perf_km1.mean - perf_k.mean,
      rel_delta_percent=-10.0 if y == 0.0 else 10.0,
      uncertainty=20.0,
      practical_margin=10.0,
      governing_margin=10.0,
      whole_outcome=outcome,
      late_outcome=outcome,
      trajectory_status=TrajectoryStatus.STABLE_AGREEMENT,
      y=y,
      pair_weight=weight,
      k_run_path=Path("/tmp/k"),
      k_minus_1_run_path=Path("/tmp/km1"),
      parallel_sources=s,
      effective_outcome=outcome,
      label_evidence_basis=LabelEvidenceBasis.WHOLE_AGREEMENT,
      basis_throughput_k=perf_k.mean,
      basis_throughput_k_minus_1=perf_km1.mean,
      basis_delta=perf_km1.mean - perf_k.mean,
      basis_variance_k=100.0,
      basis_variance_k_minus_1=100.0,
      basis_uncertainty=20.0,
      eligibility=ArtifactEligibility.ELIGIBLE,
  )


def test_low_confidence_ablation_diagnostics(test_domain):
  """Validates low-confidence ablation removes rows with v < 0.1 and computes diagnostic comparison."""
  # Create dataset with 9 families, some rows with weight < 0.1
  family_configs = [
    (23, 2, 112), (23, 2, 172), (23, 1, 112), (23, 6, 112), (23, 6, 172),
    (7, 2, 112), (7, 6, 16), (7, 6, 112), (23, 11, 112),
  ]
  records = []
  for i, (r, s, wu) in enumerate(family_configs):
    w1 = 0.05 if i == 0 else 0.7  # Family 0 has low-weight record
    records.append(
      _make_mock_record(f"p_{r}_{s}_{wu}_1", r, s, wu, k=3, y=0.0, weight=w1))
    records.append(
      _make_mock_record(f"p_{r}_{s}_{wu}_2", r, s, wu, k=6, y=1.0, weight=0.8))

  X = np.vstack([r.features.feature_vector for r in records])
  y = np.array([r.y for r in records], dtype=np.float64)
  v = np.array([r.pair_weight for r in records], dtype=np.float64)
  u, scales_dict = compute_fold_influence_weights(records)
  families = [get_physical_family_id(r) for r in records]

  dataset = Dataset(
      records=records,
      X=X,
      y=y,
      v=v,
      u=u,
      families=families,
      family_scales=scales_dict,
      family_counts={},
      U=float(np.sum(u)),
      n_eff=len(records),
  )

  ablation = run_low_confidence_ablation(
      dataset=dataset,
      domain=test_domain,
      structure_name="M2",
      l2_reg=1e-3,
      threshold_v=0.1,
  )

  assert ablation.original_count == 18
  assert ablation.removed_count == 1
  assert ablation.ablated_count == 17
  assert ablation.baseline_fit.success is True
  assert ablation.ablated_fit.success is True
  assert ablation.stability_comparison.cosine_similarity > 0.9


def test_adjacent_lambda_sensitivity_diagnostics(test_domain):
  """Validates adjacent lambda sensitivity across neighboring candidates."""
  family_configs = [
    (23, 2, 112), (23, 2, 172), (23, 1, 112), (23, 6, 112), (23, 6, 172),
    (7, 2, 112), (7, 6, 16), (7, 6, 112), (23, 11, 112),
  ]
  records = []
  for r, s, wu in family_configs:
    records.append(
      _make_mock_record(f"p_{r}_{s}_{wu}_1", r, s, wu, k=3, y=0.0, weight=0.8))
    records.append(
      _make_mock_record(f"p_{r}_{s}_{wu}_2", r, s, wu, k=6, y=1.0, weight=0.8))

  X = np.vstack([r.features.feature_vector for r in records])
  y = np.array([r.y for r in records], dtype=np.float64)
  v = np.array([r.pair_weight for r in records], dtype=np.float64)
  u, scales_dict = compute_fold_influence_weights(records)
  families = [get_physical_family_id(r) for r in records]

  dataset = Dataset(
      records=records,
      X=X,
      y=y,
      v=v,
      u=u,
      families=families,
      family_scales=scales_dict,
      family_counts={},
      U=float(np.sum(u)),
      n_eff=len(records),
  )

  s_ref = build_common_reference_scales(dataset)
  grid_res = execute_lofo_grid_search(
      dataset=dataset,
      domain=test_domain,
      structures=["M2"],
      lambdas=[1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1.0],
  )

  sens = run_adjacent_lambda_sensitivity(
      grid_result=grid_res,
      structure_name="M2",
      s_ref=s_ref,
      domain=test_domain,
  )

  assert sens.structure_name == "M2"
  assert len(sens.adjacent_lambdas) in [1, 2]
  for adj_l2 in sens.adjacent_lambdas:
    assert adj_l2 in sens.comparisons
    assert adj_l2 in sens.regret_deltas
    assert adj_l2 in sens.worst_family_deltas
