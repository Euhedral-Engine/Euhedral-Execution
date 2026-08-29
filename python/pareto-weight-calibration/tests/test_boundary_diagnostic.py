from __future__ import annotations

from dataclasses import replace
import hashlib
import json
import math
from pathlib import Path

import pytest

from pareto_weight_calibration.action_model import (
  CACHE,
  DEFAULT,
  INDETERMINATE,
  ActionRow,
  BoundaryFit,
  FeatureScaler,
)
from pareto_weight_calibration.boundary_diagnostic import (
  _canonical_json,
  _write_json,
  boundary_drift,
  derive_family_cutoff_constraint,
  family_level_representation,
  fit_coverage_scaler,
  predict_family_mu,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
EXPECTED_HASHES = {
  "experiments/pareto_action_model_training/action_training_dataset.json":
    "d0e59e5cce68630a157b055d7b4958c1a46eb7f45bc3fe125b71a43b5ee89f02",
  "experiments/pareto_action_model_training/action_model_outer_lofo.json":
    "461225bad75f31d14eb4ea07a9c148963f3bba52c21673c97e3526e35ac9f6f8",
  "experiments/pareto_integer_cutoff_evaluation/integer_cutoff_results.json":
    "1270040c516d62a9270eea77eac00b42c2d5304251d23dcab8c465ad523a0f26",
  "experiments/pareto_peak_training/family_curves.json":
    "a516cd4b93a8dc477d70137e4c4e77f498715e07487af7fd8d7068ae166de9d3",
}


def _row(
    pair_id: str,
    family_id: str,
    k: int,
    action: str,
    productive_handles: float = 3.0,
    body: float = 4.0,
    contention: float = 0.25,
    workers: int = 7,
) -> ActionRow:
  decisive = action != INDETERMINATE
  return ActionRow(
      pair_id=pair_id,
      family_id=family_id,
      current_k=k,
      registered_workers=workers,
      source_count=3,
      work_units=64,
      productive_handles=productive_handles,
      body_log=body,
      body_cost_ns=math.expm1(body),
      contention=contention,
      observed_action=action,
      supported_wrong_action_loss=1.0 if decisive else 0.0,
      observed_wrong_action_loss=2.0 if decisive else 0.0,
      supported_relative_wrong_action_loss=0.01 if decisive else 0.0,
      observed_relative_wrong_action_loss=0.02 if decisive else 0.0,
      evidence_weight=1.0,
      family_scale=1.0,
      influence_weight=1.0,
      effective_outcome={
        DEFAULT: "K_WINS", CACHE: "K_MINUS_1_WINS",
        INDETERMINATE: "STABLE_TIE",
      }[action],
      evidence_basis="STABLE_TIE" if not decisive else "WHOLE_AGREEMENT",
      basis_throughput_k=100.0,
      basis_throughput_k_minus_1=99.0,
      basis_delta=-2.0 if action == DEFAULT else 2.0,
      basis_uncertainty=1.0,
      runtime_commit="commit",
      topology_id="topology",
      k_run_path="k",
      k_run_sha256="a" * 64,
      k_minus_1_run_path="k-1",
      k_minus_1_run_sha256="b" * 64,
  )


def _fit_with_pr_slope() -> BoundaryFit:
  return BoundaryFit(
      structure="A_PR",
      feature_names=("pRatio", "logR"),
      l2=1e-3,
      temperature=1.0,
      scaler=FeatureScaler(("pRatio", "logR"), (0.5, math.log(7)), (0.1, 1.0)),
      coefficients=(0.0, 1.0, 0.0),
      success=True,
      objective=0.0,
      iterations=1,
  )


def test_monotonic_default_cache_sequence_produces_exact_cutoff() -> None:
  result = derive_family_cutoff_constraint([
    _row("k4", "f", 4, DEFAULT),
    _row("k5", "f", 5, DEFAULT),
    _row("k6", "f", 6, CACHE),
    _row("k7", "f", 7, CACHE),
  ])
  assert result["status"] == "EXACT"
  assert (result["observedFirstCacheKMin"],
          result["observedFirstCacheKMax"]) == (6, 6)
  assert (result["continuousMuMin"], result["continuousMuMax"]) == (5.0, 6.0)


def test_indeterminate_k_widens_cutoff_interval() -> None:
  result = derive_family_cutoff_constraint([
    _row("k4", "f", 4, DEFAULT),
    _row("k5", "f", 5, INDETERMINATE),
    _row("k6", "f", 6, CACHE),
  ])
  assert result["status"] == "INTERVAL"
  assert (result["observedFirstCacheKMin"],
          result["observedFirstCacheKMax"]) == (5, 6)


def test_cache_to_default_reversal_is_contradictory() -> None:
  result = derive_family_cutoff_constraint([
    _row("k3", "f", 3, CACHE),
    _row("k5", "f", 5, DEFAULT),
  ])
  assert result["status"] == "CONTRADICTORY"
  assert result["reversalCount"] == 1
  assert result["observedFirstCacheKMin"] is None


def test_one_sided_evidence_retains_domain_bound() -> None:
  result = derive_family_cutoff_constraint([
    _row("k3", "f", 3, DEFAULT),
    _row("k5", "f", 5, DEFAULT),
  ])
  assert result["status"] == "ONE_SIDED"
  assert (result["observedFirstCacheKMin"],
          result["observedFirstCacheKMax"]) == (6, 8)


def test_predicted_mu_uses_each_k_rows_actual_telemetry() -> None:
  low = _row("low", "f", 3, DEFAULT, productive_handles=2.8)
  high = _row("high", "f", 4, CACHE, productive_handles=4.2)
  predictions = predict_family_mu(_fit_with_pr_slope(), [low, high])
  by_pair = {item["pairId"]: item for item in predictions}
  assert by_pair["low"]["mu"] != by_pair["high"]["mu"]
  assert by_pair["low"]["mu"] < by_pair["high"]["mu"]


def test_within_family_boundary_drift_is_measured_from_mu_range() -> None:
  result = boundary_drift([
    {"pairId": "a", "currentK": 3, "mu": 3.1},
    {"pairId": "b", "currentK": 4, "mu": 4.6},
  ])
  assert result["predictedMuRange"] == pytest.approx(1.5)
  assert result["predictedMuDriftBucket"] == "1-2"
  assert result["integerCutoffStability"] == "CHANGES_BY_1"


def test_family_rows_are_one_independent_physical_family() -> None:
  rows = {
    "f": [_row("k4", "f", 4, DEFAULT), _row("k5", "f", 5, CACHE)]
  }
  constraints = {"f": derive_family_cutoff_constraint(rows["f"])}
  result = family_level_representation(rows, constraints)
  assert result["rowCount"] == 2
  assert result["decisiveRowCount"] == 2
  assert result["physicalFamilyCount"] == 1
  assert result["independentConstrainedFamilyCount"] == 1


def test_k_dependent_telemetry_is_not_static_or_silently_averaged() -> None:
  rows = {
    "f": [
      _row("k4", "f", 4, DEFAULT, productive_handles=2.0, body=3.0),
      _row("k5", "f", 5, CACHE, productive_handles=4.0, body=5.0),
    ]
  }
  constraints = {"f": derive_family_cutoff_constraint(rows["f"])}
  family = family_level_representation(rows, constraints)["families"][0]
  assert "productiveHandles" not in family["staticFeatures"]
  assert "body" not in family["staticFeatures"]
  assert family["kDependentTelemetryAveragedIntoStaticFeatures"] is False
  assert family["kDependentTelemetry"]["productiveHandles"]["range"] == 2.0


def test_lofo_coverage_scaling_excludes_held_family() -> None:
  training = [
    _row("a", "a", 3, DEFAULT, productive_handles=2.0),
    _row("b", "b", 3, DEFAULT, productive_handles=3.0),
  ]
  first = training + [_row("h1", "held", 3, DEFAULT, productive_handles=1.0)]
  second = training + [_row("h2", "held", 3, DEFAULT, productive_handles=7.0)]
  scaler_first = fit_coverage_scaler(first, "held")
  scaler_second = fit_coverage_scaler(second, "held")
  assert "held" not in scaler_first["trainingFamilyIds"]
  assert scaler_first["means"].tolist() == scaler_second["means"].tolist()
  assert scaler_first["scales"].tolist() == scaler_second["scales"].tolist()


def test_frozen_evidence_and_previous_artifacts_remain_unchanged() -> None:
  for relative, expected in EXPECTED_HASHES.items():
    path = REPO_ROOT / relative
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected
    assert path.with_name(path.name + ".sha256").read_text().strip() == expected


def test_new_artifact_serialization_is_deterministic(tmp_path: Path) -> None:
  first = tmp_path / "first.json"
  second = tmp_path / "second.json"
  payload_a = {"families": ["b", "a"], "counts": {"exact": 2, "interval": 1}}
  payload_b = {"counts": {"interval": 1, "exact": 2}, "families": ["b", "a"]}
  assert _canonical_json(payload_a) == _canonical_json(payload_b)
  assert _write_json(first, payload_a) == _write_json(second, payload_b)
  assert first.read_bytes() == second.read_bytes()
  assert json.loads(first.read_text()) == payload_a
