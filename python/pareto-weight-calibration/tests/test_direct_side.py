from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path

import pytest

from pareto_weight_calibration import direct_side
from pareto_weight_calibration.action_model import (
  CACHE,
  DEFAULT,
  INDETERMINATE,
  ActionRow,
  action_loss,
  grouped_family_folds,
)
from pareto_weight_calibration.direct_side import (
  BASE_FEATURES,
  LEFT,
  RIGHT,
  DirectFit,
  DirectScaler,
  _canonical_json,
  _write_json,
  action_for_score,
  current_state_features,
  direct_design_matrix,
  fit_direct_classifier,
  predict_direct_side,
  side_for_action,
  validate_direct_feature_names,
)
from pareto_weight_calibration.types import DomainConfig

REPO_ROOT = Path(__file__).resolve().parents[3]
EXPECTED_HASHES = {
  "experiments/pareto_action_model_training/action_training_dataset.json":
    "d0e59e5cce68630a157b055d7b4958c1a46eb7f45bc3fe125b71a43b5ee89f02",
  "experiments/pareto_action_model_training/action_model_outer_lofo.json":
    "461225bad75f31d14eb4ea07a9c148963f3bba52c21673c97e3526e35ac9f6f8",
  "experiments/pareto_action_model_training/action_model_m4c_lofo.json":
    "cfae463aca26002c9aaa8366a5f2d632a8a28f95949941347ac9b6f2284ffb82",
  "experiments/pareto_side_of_peak_evaluation/side_of_peak_summary.json":
    "12d145ca332813b75648d8a08f61a3c5378f71b4f13cdbe681e63d1544abf553",
  "experiments/productivity_participation_domain.json":
    "ae61243653103055099f982d2357366d4af4beea1f59a1ac9dd9ca79a0eba52e",
}


def _row(
    pair_id: str = "pair",
    family_id: str = "family",
    k: int = 4,
    action: str = DEFAULT,
    cost: float = 10.0,
    productive_handles: float = 3.0,
    body: float = 4.0,
    contention: float = 0.25,
) -> ActionRow:
  decisive = action != INDETERMINATE
  return ActionRow(
      pair_id=pair_id,
      family_id=family_id,
      current_k=k,
      registered_workers=7,
      source_count=3,
      work_units=64,
      productive_handles=productive_handles,
      body_log=body,
      body_cost_ns=math.expm1(body),
      contention=contention,
      observed_action=action,
      supported_wrong_action_loss=cost if decisive else 0.0,
      observed_wrong_action_loss=cost + 2.0 if decisive else 0.0,
      supported_relative_wrong_action_loss=cost / 100.0 if decisive else 0.0,
      observed_relative_wrong_action_loss=(
                                                cost + 2.0) / 100.0 if decisive else 0.0,
      evidence_weight=1.0,
      family_scale=1.0,
      influence_weight=1.0,
      effective_outcome={
        DEFAULT: "K_WINS", CACHE: "K_MINUS_1_WINS",
        INDETERMINATE: "STABLE_TIE",
      }[action],
      evidence_basis="STABLE_TIE" if not decisive else "WHOLE_AGREEMENT",
      basis_throughput_k=100.0,
      basis_throughput_k_minus_1=90.0,
      basis_delta=-12.0 if action == DEFAULT else 12.0,
      basis_uncertainty=2.0,
      runtime_commit="commit",
      topology_id="topology",
      k_run_path="k",
      k_run_sha256="a" * 64,
      k_minus_1_run_path="k-1",
      k_minus_1_run_sha256="b" * 64,
  )


def _fit(coefficients: tuple[float, ...]) -> DirectFit:
  return DirectFit(
      structure="S0_BASE",
      feature_names=BASE_FEATURES,
      l2=1e-3,
      temperature=1.0,
      scaler=DirectScaler(BASE_FEATURES, (0.0,) * 5, (1.0,) * 5),
      coefficients=coefficients,
      objective=0.0,
      success=True,
      iterations=1,
      minimum_domain_k_slope=coefficients[1],
  )


def test_left_maps_to_default() -> None:
  assert side_for_action(DEFAULT) == LEFT
  assert action_for_score(0.0) == DEFAULT


def test_right_maps_to_cache() -> None:
  assert side_for_action(CACHE) == RIGHT
  assert action_for_score(0.1) == CACHE


def test_current_k_is_a_direct_predictive_input() -> None:
  scaler = DirectScaler(BASE_FEATURES, (0.0,) * 5, (1.0,) * 5)
  matrix = direct_design_matrix(
      [_row("k2", k=2), _row("k5", k=5)], "S0_BASE", scaler
  )
  assert matrix[0, 1] == 2.0
  assert matrix[1, 1] == 5.0


def test_current_state_inputs_come_from_active_row_only() -> None:
  row = _row(productive_handles=2.0, body=5.5, contention=0.75)
  features = current_state_features(row)
  assert features["pRatio"] == pytest.approx(2 / 7)
  assert features["body"] == 5.5
  assert features["contention"] == 0.75
  assert set(features) == set(BASE_FEATURES)


def test_counterfactual_telemetry_is_rejected() -> None:
  with pytest.raises(ValueError, match="counterfactual"):
    validate_direct_feature_names(["futureContention"])


def test_fixed_state_increasing_k_cannot_change_right_to_left() -> None:
  fit = _fit((-2.0, 1.0, 0.0, 0.0, 0.0, 0.0))
  rows = [_row(str(k), k=k) for k in range(2, 8)]
  actions = [item["action"] for item in predict_direct_side(fit, rows)]
  assert DEFAULT not in actions[actions.index(CACHE):]


def test_fitted_interaction_model_is_monotone_across_fixed_state_k() -> None:
  training = []
  for index, family in enumerate(("a", "b", "c", "d")):
    training.extend([
      _row(f"{family}-left", family, k=2, action=DEFAULT,
           productive_handles=2.0 + index * 0.1, body=3.0 + index * 0.1),
      _row(f"{family}-right", family, k=6, action=CACHE,
           productive_handles=2.0 + index * 0.1, body=3.0 + index * 0.1),
    ])
  fit = fit_direct_classifier(
      training, "S6_ALL", 1e-3, 1.0, DomainConfig()
  )
  fixed_state = [
    _row(str(k), "fixed", k=k, productive_handles=2.2, body=3.2)
    for k in range(2, 8)
  ]
  scores = [item["score"] for item in predict_direct_side(fit, fixed_state)]
  assert scores == sorted(scores)
  assert fit.minimum_domain_k_slope > 0.0


def test_changing_state_with_k_may_change_predicted_side() -> None:
  fit = _fit((0.0, 0.5, 0.0, 0.0, -2.0, 0.0))
  low_body = _row("low", k=2, body=0.1)
  high_body = _row("high", k=3, body=3.0)
  predictions = predict_direct_side(fit, [low_body, high_body])
  assert predictions[0]["predictedSide"] == RIGHT
  assert predictions[1]["predictedSide"] == LEFT


def test_cost_sensitive_loss_uses_row_supported_cost() -> None:
  low = action_loss(_row("low", action=CACHE, cost=2.0), DEFAULT)
  high = action_loss(_row("high", action=CACHE, cost=50.0), DEFAULT)
  assert low["supportedLoss"] == 2.0
  assert high["supportedLoss"] == 50.0


def test_false_default_and_false_cache_keep_separate_costs() -> None:
  false_default = action_loss(_row("fd", action=CACHE, cost=17.0), DEFAULT)
  false_cache = action_loss(_row("fc", action=DEFAULT, cost=3.0), CACHE)
  assert (false_default["wrongType"], false_default["supportedLoss"]) == (
    "FALSE_DEFAULT", 17.0
  )
  assert (false_cache["wrongType"], false_cache["supportedLoss"]) == (
    "FALSE_CACHE", 3.0
  )


def test_indeterminate_rows_are_not_forced_into_fit() -> None:
  with pytest.raises(ValueError, match="indeterminate"):
    fit_direct_classifier(
        [_row(action=INDETERMINATE)], "S0_BASE", 1e-3, 1.0, DomainConfig()
    )


def test_grouped_validation_keeps_physical_families_together() -> None:
  folds = grouped_family_folds(["a", "a", "b", "c", "c", "d"])
  assert sorted(family for fold in folds for family in fold) == ["a", "b", "c",
                                                                 "d"]


def test_outer_holdout_does_not_affect_scaling_or_selection(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
  rows = [_row("a", "a"), _row("b", "b"), _row("held", "held", action=CACHE)]
  seen: list[set[str]] = []
  selected = {
    "candidateId": "S0_BASE@test", "structure": "S0_BASE",
    "l2": 1e-3, "temperature": 1.0, "metrics": {},
  }

  def select(training, domain):
    seen.append({row.family_id for row in training})
    return {"folds": [], "candidates": [], "admissions": [],
            "selected": selected}

  def fit(training, *args):
    seen.append({row.family_id for row in training})
    return _fit((-2.0, 1.0, 0.0, 0.0, 0.0, 0.0))

  monkeypatch.setattr(direct_side, "inner_select_direct_side", select)
  monkeypatch.setattr(direct_side, "fit_direct_classifier", fit)
  direct_side._outer_task(("held", rows, DomainConfig()))
  assert seen == [{"a", "b"}, {"a", "b"}]


def test_direct_classifier_has_no_peak_or_cutoff_target() -> None:
  assert "peak" not in {name.lower() for name in BASE_FEATURES}
  assert "cutoff" not in {name.lower() for name in BASE_FEATURES}
  assert set(BASE_FEATURES) == {"K", "pRatio", "logR", "body", "contention"}


def test_existing_artifact_hashes_remain_unchanged() -> None:
  for relative, expected in EXPECTED_HASHES.items():
    path = REPO_ROOT / relative
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected
    assert path.with_name(path.name + ".sha256").read_text().strip() == expected


def test_new_artifact_serialization_is_deterministic(tmp_path: Path) -> None:
  first = tmp_path / "first.json"
  second = tmp_path / "second.json"
  payload_a = {"features": list(BASE_FEATURES), "selected": {"b": 2, "a": 1}}
  payload_b = {"selected": {"a": 1, "b": 2}, "features": list(BASE_FEATURES)}
  assert _canonical_json(payload_a) == _canonical_json(payload_b)
  assert _write_json(first, payload_a) == _write_json(second, payload_b)
  assert first.read_bytes() == second.read_bytes()
  assert json.loads(first.read_text()) == payload_a
