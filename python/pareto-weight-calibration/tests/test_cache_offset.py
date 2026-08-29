from __future__ import annotations

from dataclasses import replace
import hashlib
import json
import math
from pathlib import Path

import numpy as np

from pareto_weight_calibration.action_model import (
  CACHE,
  DEFAULT,
  ActionRow,
  BoundaryFit,
  FeatureScaler,
  action_loss,
  predict_boundary,
)
from pareto_weight_calibration.cache_offset import (
  CACHE_OFFSET_GRID,
  _canonical_json,
  _write_json,
  apply_cache_offset,
  inner_select_cache_offset,
  offset_candidate_better,
  select_cache_offset_for_outer_fold,
  select_offset_candidate,
)
from pareto_weight_calibration.integer_cutoff import first_cache_k, \
  runtime_action

REPO_ROOT = Path(__file__).resolve().parents[3]
EXPECTED_PREEXISTING_HASHES = {
  "experiments/pareto_action_model_training/action_training_dataset.json":
    "d0e59e5cce68630a157b055d7b4958c1a46eb7f45bc3fe125b71a43b5ee89f02",
  "experiments/pareto_action_model_training/action_model_outer_lofo.json":
    "461225bad75f31d14eb4ea07a9c148963f3bba52c21673c97e3526e35ac9f6f8",
  "experiments/pareto_action_model_training/action_model_m4c_lofo.json":
    "cfae463aca26002c9aaa8366a5f2d632a8a28f95949941347ac9b6f2284ffb82",
  "experiments/pareto_action_model_training/action_model_summary.json":
    "4bea5d11073fac494ed35cb9e68b7ecae1c8a15d8f617ea70e5c642cc648fa43",
  "experiments/pareto_integer_cutoff_evaluation/integer_cutoff_results.json":
    "1270040c516d62a9270eea77eac00b42c2d5304251d23dcab8c465ad523a0f26",
}


def _row(
    pair_id: str,
    family_id: str,
    current_k: int = 4,
    action: str = DEFAULT,
    supported_loss: float = 1.0,
) -> ActionRow:
  return ActionRow(
      pair_id=pair_id,
      family_id=family_id,
      current_k=current_k,
      registered_workers=7,
      source_count=3,
      work_units=64,
      productive_handles=3.0,
      body_log=4.0,
      body_cost_ns=54.0,
      contention=0.25,
      observed_action=action,
      supported_wrong_action_loss=supported_loss,
      observed_wrong_action_loss=supported_loss + 1.0,
      supported_relative_wrong_action_loss=supported_loss / 100.0,
      observed_relative_wrong_action_loss=(supported_loss + 1.0) / 100.0,
      evidence_weight=1.0,
      family_scale=1.0,
      influence_weight=1.0,
      effective_outcome="K_WINS" if action == DEFAULT else "K_MINUS_1_WINS",
      evidence_basis="WHOLE_AGREEMENT",
      basis_throughput_k=100.0,
      basis_throughput_k_minus_1=90.0,
      basis_delta=-2.0 if action == DEFAULT else 2.0,
      basis_uncertainty=1.0,
      runtime_commit="commit",
      topology_id="topology",
      k_run_path="k",
      k_run_sha256="a" * 64,
      k_minus_1_run_path="k-1",
      k_minus_1_run_sha256="b" * 64,
  )


def _fixed_fit(mu: float = 4.4) -> BoundaryFit:
  eta = math.log((mu - 1.0) / (7.0 - mu))
  return BoundaryFit(
      structure="A_PR",
      feature_names=("pRatio", "logR"),
      l2=1e-3,
      temperature=1.0,
      scaler=FeatureScaler(("pRatio", "logR"), (3 / 7, math.log(7)),
                           (1.0, 1.0)),
      coefficients=(eta, 0.0, 0.0),
      success=True,
      objective=0.0,
      iterations=1,
  )


def _prediction(pair_id: str, current_k: int, mu: float) -> dict:
  score = current_k - mu
  return {
    "pairId": pair_id,
    "familyId": "family",
    "currentK": current_k,
    "mu": mu,
    "score": score,
    "boundaryMargin": abs(score),
    "action": CACHE if score > 0.0 else DEFAULT,
  }


def _metrics(
    pooled: float,
    worst: float = 0.0,
    false_default: float = 0.0,
    false_cache: float = 0.0,
) -> dict:
  return {
    "supportedRelativeRegret": pooled,
    "worstFamilySupportedRelativeRegret": worst,
    "falseDefault": {"supportedRelativeRegret": false_default},
    "falseCache": {"supportedRelativeRegret": false_cache},
  }


def _candidate(offset: float, **values: float) -> dict:
  return {
    "candidateId": f"offset-{offset}",
    "cacheOffset": offset,
    "metrics": _metrics(**values),
  }


def test_cache_offset_zero_exactly_reproduces_current_runtime_actions() -> None:
  base = [_prediction(str(k), k, 4.0) for k in range(2, 8)]
  adjusted = apply_cache_offset(base, 0.0)
  assert [item["action"] for item in adjusted] == [item["action"] for item in
                                                   base]
  assert [item["effectiveMu"] for item in adjusted] == [item["mu"] for item in
                                                        base]


def test_positive_cache_offset_can_only_move_boundary_earlier() -> None:
  base = [_prediction("pair", 4, 4.4)]
  cutoffs = [
    first_cache_k(apply_cache_offset(base, offset)[0]["effectiveMu"])
    for offset in CACHE_OFFSET_GRID
  ]
  assert cutoffs == sorted(cutoffs, reverse=True)
  assert cutoffs[-1] < cutoffs[0]


def test_increasing_offset_cannot_change_cache_to_default() -> None:
  base = [_prediction("pair", 4, 4.2)]
  actions = [apply_cache_offset(base, offset)[0]["action"] for offset in
             CACHE_OFFSET_GRID]
  assert DEFAULT not in actions[actions.index(CACHE):]


def test_offset_selection_uses_training_families_only() -> None:
  rows = [_row(f"{family}-a", family) for family in "abcde"]
  seen: list[set[str]] = []

  def recording_fit(training, *args):
    seen.append({row.family_id for row in training})
    return _fixed_fit()

  selection = select_cache_offset_for_outer_fold(
      rows, "e", "A_PR", 1e-3, 1.0, fit_function=recording_fit
  )
  assert seen
  assert all("e" not in families for families in seen)
  assert selection["outerHeldOutFamily"] == "e"
  assert selection["outerTrainingFamilyCount"] == 4


def test_outer_holdout_values_do_not_affect_offset_selection() -> None:
  training = [_row(f"{family}-a", family) for family in "abcd"]
  first = training + [_row("held-a", "held", current_k=2, action=DEFAULT)]
  second = training + [_row("held-b", "held", current_k=7, action=CACHE,
                            supported_loss=999.0)]
  fit = lambda rows, *args: _fixed_fit()
  first_selection = select_cache_offset_for_outer_fold(
      first, "held", "A_PR", 1e-3, 1.0, fit_function=fit
  )
  second_selection = select_cache_offset_for_outer_fold(
      second, "held", "A_PR", 1e-3, 1.0, fit_function=fit
  )
  assert first_selection["selected"]["cacheOffset"] == \
         second_selection["selected"]["cacheOffset"]
  assert first_selection["outerTrainingPairIdsHash"] == second_selection[
    "outerTrainingPairIdsHash"]


def test_effectively_equal_candidates_prefer_smaller_offset() -> None:
  larger = _candidate(0.5, pooled=1.0)
  smaller = _candidate(0.25, pooled=1.0 + 0.5e-12)
  assert select_offset_candidate([larger, smaller])["cacheOffset"] == 0.25


def test_supported_regret_remains_primary_selection_metric() -> None:
  lower_primary = _candidate(
      0.5, pooled=0.1, worst=10.0, false_default=10.0, false_cache=10.0
  )
  lower_guards = _candidate(
      0.0, pooled=0.2, worst=0.0, false_default=0.0, false_cache=0.0
  )
  assert offset_candidate_better(lower_primary, lower_guards)
  assert select_offset_candidate([lower_guards, lower_primary]) == lower_primary


def test_false_default_and_false_cache_regret_remain_separate() -> None:
  false_default = action_loss(_row("fd", "f", action=CACHE, supported_loss=7.0),
                              DEFAULT)
  false_cache = action_loss(_row("fc", "f", action=DEFAULT, supported_loss=3.0),
                            CACHE)
  assert false_default["wrongType"] == "FALSE_DEFAULT"
  assert false_default["supportedLoss"] == 7.0
  assert false_cache["wrongType"] == "FALSE_CACHE"
  assert false_cache["supportedLoss"] == 3.0


def test_integer_k_runtime_semantics_are_preserved() -> None:
  base = [_prediction(str(k), k, 4.6) for k in range(1, 8)]
  for offset in CACHE_OFFSET_GRID:
    for prediction in apply_cache_offset(base, offset):
      assert prediction["action"] == runtime_action(
          prediction["currentK"], first_cache_k(prediction["effectiveMu"])
      )


def test_existing_frozen_and_prior_artifacts_remain_unchanged() -> None:
  for relative, expected in EXPECTED_PREEXISTING_HASHES.items():
    path = REPO_ROOT / relative
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected
    assert path.with_name(path.name + ".sha256").read_text().strip() == expected


def test_new_artifact_serialization_is_deterministic(tmp_path: Path) -> None:
  first = tmp_path / "first.json"
  second = tmp_path / "second.json"
  payload_a = {"grid": list(CACHE_OFFSET_GRID), "selected": {"b": 2, "a": 1}}
  payload_b = {"selected": {"a": 1, "b": 2}, "grid": list(CACHE_OFFSET_GRID)}
  assert _canonical_json(payload_a) == _canonical_json(payload_b)
  assert _write_json(first, payload_a) == _write_json(second, payload_b)
  assert first.read_bytes() == second.read_bytes()
  assert json.loads(first.read_text()) == payload_a


def test_inner_validation_fits_once_per_group_not_once_per_offset() -> None:
  rows = [_row(f"{family}-a", family) for family in "abcd"]
  calls = 0

  def count_fit(training, *args):
    nonlocal calls
    calls += 1
    return _fixed_fit()

  result = inner_select_cache_offset(
      rows, "A_PR", 1e-3, 1.0, fit_function=count_fit
  )
  assert calls == len(result["folds"])
  assert len(result["candidates"]) == len(CACHE_OFFSET_GRID)
