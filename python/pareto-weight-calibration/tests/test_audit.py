"""Tests for dataset assembly, bounded family influence, and identifiability audit."""

from __future__ import annotations

from pathlib import Path
import numpy as np
import pytest

from pareto_weight_calibration.audit import (
  build_dataset,
  compute_bounded_family_influence,
  compute_lapack_rank_and_singular_spectrum,
  compute_nonconstant_vifs_and_collinearity,
  detect_separation,
  get_physical_family_id,
  perform_identifiability_audit,
)
from pareto_weight_calibration.types import (
  ActiveStateFeatures,
  ArmPerformance,
  ArtifactEligibility,
  LabelEvidenceBasis,
  Outcome,
  PairRecord,
  TrajectoryStatus,
  WithdrawnDiagnosticState,
)


def _make_dummy_record(
    pair_id: str,
    K: int,
    R: int,
    S: int,
    WU: int,
    c: float,
    body_cost: float,
    P: float,
    y: float,
    weight: float,
    eligibility: ArtifactEligibility = ArtifactEligibility.ELIGIBLE,
) -> PairRecord:
  perf = ArmPerformance(100.0, 1.0, 1.0, 0.01, 4, 100.0, 1.0, 0.01)
  diag = WithdrawnDiagnosticState(0.5, 2.0, 10, "DIRECT", 100)
  features = ActiveStateFeatures(
      c=c,
      smoothed_body_cost_ns=body_cost,
      b=np.log1p(body_cost),
      P=P,
      R=R,
      K=K,
  )
  effective_outcome = (
    Outcome.K_WINS
    if y == 0.0
    else Outcome.K_MINUS_1_WINS
    if y == 1.0
    else Outcome.STABLE_TIE
  )
  return PairRecord(
      pair_id=pair_id,
      topology_id=f"top-{R}",
      runtime_commit="test_commit",
      cache_actuator_version="cache-v1",
      cache_park_ns=15000,
      K=K,
      registered_workers=R,
      work_units=WU,
      parallel_sources=S,
      features=features,
      withdrawn_diagnostics=diag,
      perf_k=perf,
      perf_k_minus_1=perf,
      delta=1.0,
      rel_delta_percent=1.0,
      uncertainty=0.1,
      practical_margin=0.01,
      governing_margin=0.01,
      whole_outcome=Outcome.K_WINS if y == 0.0 else Outcome.K_MINUS_1_WINS,
      late_outcome=Outcome.K_WINS if y == 0.0 else Outcome.K_MINUS_1_WINS,
      trajectory_status=TrajectoryStatus.STABLE_AGREEMENT,
      y=y,
      pair_weight=weight,
      k_run_path=Path("/tmp/k"),
      k_minus_1_run_path=Path("/tmp/k_minus_1"),
      eligibility=eligibility,
      effective_outcome=effective_outcome,
      label_evidence_basis=(
        LabelEvidenceBasis.STABLE_TIE
        if effective_outcome == Outcome.STABLE_TIE
        else LabelEvidenceBasis.WHOLE_AGREEMENT
      ),
      basis_throughput_k=perf.mean,
      basis_throughput_k_minus_1=perf.mean,
      basis_delta=0.0,
      basis_variance_k=perf.variance,
      basis_variance_k_minus_1=perf.variance,
      basis_uncertainty=0.1,
  )


def test_physical_family_id_and_separation_from_measured_p():
  """Validates that physical family key uses configured S and is separate from measured P."""
  rec = _make_dummy_record("pair1", K=3, R=23, S=6, WU=112, c=0.4,
                           body_cost=100.0, P=2.5, y=0.0, weight=1.0)
  family_id = get_physical_family_id(rec)
  assert family_id == "Fam_R23_S6_WU112"
  # Ensure S (6) != measured P (2.5)
  assert rec.parallel_sources == 6
  assert rec.features.P == 2.5


def test_bounded_family_influence_capping():
  """Validates bounded family influence: familyScale(F) = 1 / max(1, sum(v_j)) and u_i = v_i * scale."""
  # Family A has 4 records each of weight 1.0 (sum = 4.0 > 1.0 -> scale = 0.25)
  recs_a = [
    _make_dummy_record(f"a_{i}", K=i + 2, R=23, S=6, WU=112, c=0.2,
                       body_cost=50.0, P=3.0, y=0.0, weight=1.0)
    for i in range(4)
  ]
  # Family B has 1 record of weight 0.8 (sum = 0.8 <= 1.0 -> scale = 1.0)
  recs_b = [
    _make_dummy_record("b_0", K=2, R=7, S=2, WU=112, c=0.3, body_cost=50.0,
                       P=1.5, y=1.0, weight=0.8)
  ]
  all_recs = recs_a + recs_b

  v, u, families, family_scales, family_counts, U, n_eff = compute_bounded_family_influence(
    all_recs)

  assert family_scales["Fam_R23_S6_WU112"] == pytest.approx(0.25)
  assert family_scales["Fam_R7_S2_WU112"] == pytest.approx(1.0)
  assert np.allclose(u[:4], [0.25, 0.25, 0.25, 0.25])
  assert u[4] == pytest.approx(0.8)

  # Total U = 0.25 * 4 + 0.8 = 1.8
  assert U == pytest.approx(1.8)
  # Sum u^2 = 4 * 0.25^2 + 0.8^2 = 0.25 + 0.64 = 0.89
  # N_eff = 1.8^2 / 0.89 = 3.24 / 0.89 ≈ 3.6404
  assert n_eff == pytest.approx(3.24 / 0.89)


def test_dataset_assembly_filters_ineligible():
  """Validates that build_dataset filters ineligible and zero-weight records."""
  recs = [
    _make_dummy_record("r1", K=2, R=23, S=2, WU=112, c=0.5, body_cost=100.0,
                       P=2.0, y=0.0, weight=1.0,
                       eligibility=ArtifactEligibility.ELIGIBLE),
    _make_dummy_record("r2", K=3, R=23, S=2, WU=112, c=0.5, body_cost=100.0,
                       P=2.0, y=0.0, weight=0.0,
                       eligibility=ArtifactEligibility.ELIGIBLE),
    _make_dummy_record("r3", K=4, R=23, S=2, WU=112, c=0.5, body_cost=100.0,
                       P=2.0, y=0.0, weight=1.0,
                       eligibility=ArtifactEligibility.INELIGIBLE),
  ]
  ds = build_dataset(recs)
  assert len(ds.records) == 1
  assert ds.records[0].pair_id == "r1"
  assert ds.X.shape == (1, 8)


def test_frozen_dataset_rejects_any_excluded_record():
  """A frozen training manifest must not silently discard a declared row."""
  records = [
    _make_dummy_record(
        "positive", K=2, R=23, S=2, WU=112, c=0.5,
        body_cost=100.0, P=2.0, y=0.0, weight=1.0,
    ),
    _make_dummy_record(
        "zero", K=3, R=23, S=2, WU=112, c=0.5,
        body_cost=100.0, P=2.0, y=0.5, weight=0.0,
    ),
  ]

  with pytest.raises(ValueError, match="zero: confidence v=0.0 is not > 0.0"):
    build_dataset(records, require_all_records_retained=True)


def test_lapack_rank_and_svd():
  """Validates numerical rank computation under full-rank and deficient matrices."""
  # Full rank 8x8 identity
  I8 = np.eye(8, dtype=np.float64)
  rank, s, tau = compute_lapack_rank_and_singular_spectrum(I8)
  assert rank == 8
  assert len(s) == 8
  assert np.allclose(s, 1.0)

  # Rank-deficient matrix (col 1 = col 0)
  A = np.column_stack([I8[:, :7], I8[:, 0]])
  rank_def, s_def, tau_def = compute_lapack_rank_and_singular_spectrum(A)
  assert rank_def == 7
  assert s_def[-1] <= tau_def or np.isclose(s_def[-1], 0.0, atol=1e-12)


def test_vif_and_collinearity_detection():
  """Validates infinite VIF reporting when nonconstant columns have exact linear dependencies."""
  n = 20
  X = np.zeros((n, 8), dtype=np.float64)
  rng = np.random.default_rng(123)
  X[:, 0] = rng.uniform(0.1, 1.0, size=n)
  X[:, 1] = rng.uniform(0.1, 1.0, size=n)
  X[:, 2] = rng.uniform(0.1, 1.0, size=n)
  # Column 3 is exact sum of col 0 and col 1
  X[:, 3] = X[:, 0] + X[:, 1]
  X[:, 4] = -1.0  # constant
  X[:, 5] = rng.uniform(0.1, 1.0, size=n)
  X[:, 6] = rng.uniform(0.1, 1.0, size=n)
  X[:, 7] = rng.uniform(0.1, 1.0, size=n)

  vifs, deps = compute_nonconstant_vifs_and_collinearity(X)
  assert vifs[3] == float("inf")
  assert len(deps) > 0


def test_separation_detection():
  """Validates complete separation detection when a coordinate perfectly separates classes."""
  recs = [
           _make_dummy_record(f"y0_{i}", K=i + 2, R=23, S=6, WU=112, c=0.1,
                              body_cost=50.0, P=3.0, y=0.0, weight=1.0)
           for i in range(5)
         ] + [
           _make_dummy_record(f"y1_{i}", K=i + 10, R=23, S=2, WU=112, c=0.9,
                              body_cost=500.0, P=1.0, y=1.0, weight=1.0)
           for i in range(5)
         ]
  ds = build_dataset(recs)
  sep_found, details = detect_separation(ds)
  assert sep_found is True
