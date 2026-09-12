"""Tests for 9-family LOFO cross-validation, fold isolation, and grid search."""

from __future__ import annotations

from pathlib import Path
import numpy as np
import pytest

from pareto_weight_calibration.audit import get_physical_family_id
from pareto_weight_calibration.cv import (
  compute_fold_influence_weights,
  execute_lofo_grid_search,
  run_single_lofo_candidate,
)
from pareto_weight_calibration.types import (
  ActiveStateFeatures,
  ArmPerformance,
  ArtifactEligibility,
  Dataset,
  DomainConfig,
  ForkThroughput,
  LabelEvidenceBasis,
  Outcome,
  PairRecord,
  TrajectoryStatus,
  WithdrawnDiagnosticState,
)


def _make_mock_record(
    pair_id: str,
    r: int,
    s: int,
    wu: int,
    k: int = 5,
    y: float = 0.0,
    pair_weight: float = 0.8,
) -> PairRecord:
  perf_k = ArmPerformance(1000.0, 100.0, 10.0, 0.01, 4, 1000.0, 100.0, 0.01)
  perf_km1 = ArmPerformance(900.0 if y == 0.0 else 1100.0, 100.0, 10.0, 0.01, 4,
                            900.0 if y == 0.0 else 1100.0, 100.0, 0.01)
  features = ActiveStateFeatures(
      c=0.2,
      smoothed_body_cost_ns=100.0,
      b=np.log1p(100.0),
      P=4.0,
      R=r,
      K=k,
  )
  withdrawn = WithdrawnDiagnosticState(0.2, 4.0, 100, "CACHE", 100)
  outcome = Outcome.K_WINS if y == 0.0 else (
    Outcome.K_MINUS_1_WINS if y == 1.0 else Outcome.STABLE_TIE)
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
      pair_weight=pair_weight,
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


@pytest.fixture
def mock_9_family_dataset() -> Tuple[Dataset, DomainConfig]:
  """Generates a valid mock Dataset with 9 distinct physical families."""
  family_configs = [
    (23, 2, 112),
    (23, 2, 172),
    (23, 1, 112),
    (23, 6, 112),
    (23, 6, 172),
    (7, 2, 112),
    (7, 6, 16),
    (7, 6, 112),
    (23, 11, 112),
  ]
  records: List[PairRecord] = []
  for r, s, wu in family_configs:
    # Create 2 records per family (one participate, one withdraw or tie)
    records.append(_make_mock_record(f"p_{r}_{s}_{wu}_1", r, s, wu, k=3, y=0.0,
                                     pair_weight=0.6))
    records.append(_make_mock_record(f"p_{r}_{s}_{wu}_2", r, s, wu, k=6, y=1.0,
                                     pair_weight=0.8))

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

  domain = DomainConfig(
      c_min=0.0,
      c_max=1.0,
      body_cost_min_ns=10.0,
      body_cost_max_ns=5000.0,
      r_min=2,
      r_max=32,
  )
  return dataset, domain


def test_lofo_fold_isolation_and_no_leakage(mock_9_family_dataset):
  """Validates that 9-family LOFO has 9 folds, no family leakage, and fold-local weights."""
  dataset, domain = mock_9_family_dataset
  lofo_res = run_single_lofo_candidate(
      dataset=dataset,
      domain=domain,
      structure_name="M2",
      l2_reg=1e-3,
  )

  assert lofo_res.is_valid is True
  assert len(lofo_res.fold_results) == 9

  distinct_families = sorted(list(set(dataset.families)))
  assert len(distinct_families) == 9

  for fold in lofo_res.fold_results:
    # 1. Validation family matches
    assert fold.held_out_family in distinct_families
    assert fold.val_size == 2
    assert fold.train_size == 16  # 18 total - 2

    # 2. Optimization result succeeds
    assert fold.opt_result.success is True
    assert fold.opt_result.constraint_violation <= 1e-9

    # 3. Held-out validation weights sum correctly for that family alone
    # Family has 2 records with weights 0.6 and 0.8 => sum = 1.4 > 1.0 => scale = 1/1.4
    # u_val = [0.6/1.4, 0.8/1.4] => sum(u_val) = 1.0
    assert np.isclose(np.sum(fold.val_weights), 1.0, atol=1e-12)

    # 4. Fold-local baseline K0 is selected
    assert 2 <= fold.baseline_k0 <= 32

  # Aggregated metrics check
  assert lofo_res.pooled_metrics.total_count == 18
  assert lofo_res.pooled_metrics.total_weight == 9.0  # 9 families * 1.0 each


def test_end_to_end_lofo_grid_search(mock_9_family_dataset):
  """Validates full LOFO grid search across candidate structures, lambdas, and parsimony."""
  dataset, domain = mock_9_family_dataset
  grid_res = execute_lofo_grid_search(
      dataset=dataset,
      domain=domain,
      structures=["M2", "M4-C", "M4-B", "M4-R", "M6-CB", "M6-CR", "M6-BR",
                  "M8"],
      lambdas=[1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1.0],
  )

  # 8 structures * 6 lambdas = 48 LOFO evaluations
  assert len(grid_res.results) == 48

  # All 8 structures have a deterministic best lambda selected
  assert len(grid_res.selected_by_structure) == 8
  for struct in ["M2", "M4-C", "M4-B", "M4-R", "M6-CB", "M6-CR", "M6-BR", "M8"]:
    best_l2, best_met = grid_res.selected_by_structure[struct]
    assert best_l2 in [1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1.0]
    assert best_met.total_count == 18

  # Baselines evaluated
  assert grid_res.baseline_always_participate.total_count == 18
  assert grid_res.baseline_always_cache.total_count == 18
  assert grid_res.baseline_training_selected_fixed_cutoff.total_count == 18

  # Parsimony rule executed and returned a valid structure
  parsimony = grid_res.parsimony_result
  assert parsimony.selected_structure in ["M2", "M4-C", "M4-B", "M4-R", "M6-CB",
                                          "M6-CR", "M6-BR", "M8"]
  assert len(parsimony.incumbent_progression) >= 1
  assert parsimony.incumbent_progression[0] == "M2"
