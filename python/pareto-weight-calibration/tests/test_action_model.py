from __future__ import annotations

from dataclasses import replace
import hashlib
from pathlib import Path

import numpy as np
import pytest

from pareto_weight_calibration import action_pipeline
from pareto_weight_calibration.action_model import (
  BOUNDARY_STRUCTURES,
  CACHE,
  DEFAULT,
  INDETERMINATE,
  ActionRow,
  BoundaryFit,
  FeatureScaler,
  action_loss,
  bounded_mu,
  complexity_admissible,
  expected_supported_action_loss,
  feature_values,
  fit_scaler,
  fold_influence,
  grouped_family_folds,
  predict_boundary,
  validate_runtime_feature_names,
  verify_structure_heredity,
)
from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.types import DomainConfig

REPO_ROOT = Path(__file__).resolve().parents[3]


def row(
    pair_id: str = "pair",
    family_id: str = "family",
    action: str = DEFAULT,
    cost: float = 10.0,
    evidence: float = 1.0,
    k: int = 4,
    r: int = 7,
    contention: float = 0.25,
) -> ActionRow:
  return ActionRow(
      pair_id=pair_id,
      family_id=family_id,
      current_k=k,
      registered_workers=r,
      source_count=3,
      work_units=64,
      productive_handles=3.0,
      body_log=4.0,
      body_cost_ns=54.0,
      contention=contention,
      observed_action=action,
      supported_wrong_action_loss=0.0 if action == INDETERMINATE else cost,
      observed_wrong_action_loss=0.0 if action == INDETERMINATE else cost + 2.0,
      supported_relative_wrong_action_loss=0.0 if action == INDETERMINATE else cost / 100.0,
      observed_relative_wrong_action_loss=0.0 if action == INDETERMINATE else (
                                                                                    cost + 2.0) / 100.0,
      evidence_weight=evidence,
      family_scale=1.0,
      influence_weight=evidence,
      effective_outcome=
      {DEFAULT: "K_WINS", CACHE: "K_MINUS_1_WINS", INDETERMINATE: "STABLE_TIE"}[
        action],
      evidence_basis="WHOLE_AGREEMENT" if action != INDETERMINATE else "STABLE_TIE",
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


def fixed_fit(mu: float, r: int = 7) -> BoundaryFit:
  eta = np.log((mu - 1.0) / (r - mu))
  return BoundaryFit(
      structure="A_PR",
      feature_names=BOUNDARY_STRUCTURES["A_PR"],
      l2=1e-3,
      temperature=1.0,
      scaler=FeatureScaler(("pRatio", "logR"), (3 / 7, np.log(7)), (1.0, 1.0)),
      coefficients=(float(eta), 0.0, 0.0),
      success=True,
      objective=0.0,
      iterations=1,
  )


def test_left_of_peak_maps_to_default() -> None:
  prediction = predict_boundary(fixed_fit(5.0), [row(k=4)])[0]
  assert prediction["score"] < 0.0
  assert prediction["action"] == DEFAULT


def test_right_of_peak_maps_to_cache() -> None:
  prediction = predict_boundary(fixed_fit(3.0), [row(k=4)])[0]
  assert prediction["score"] > 0.0
  assert prediction["action"] == CACHE


def test_score_is_monotonic_in_k_for_fixed_state() -> None:
  predictions = predict_boundary(fixed_fit(3.5),
                                 [row(pair_id=str(k), k=k) for k in
                                  (2, 3, 4, 5)])
  assert [item["score"] for item in predictions] == sorted(
      item["score"] for item in predictions)


def test_increasing_k_cannot_change_cache_to_default() -> None:
  actions = [item["action"] for item in predict_boundary(fixed_fit(3.5), [
    row(pair_id=str(k), k=k) for k in range(2, 8)])]
  assert "DEFAULT" not in actions[actions.index("CACHE"):]


def test_supported_loss_comes_from_frozen_evidence() -> None:
  rows, _ = action_pipeline.build_action_rows(
      REPO_ROOT / "experiments/pareto_training_step5/training_pairs.tsv",
      REPO_ROOT / "experiments/pareto_peak_training/family_curves.json",
  )
  decisive = next(item for item in rows if item.decisive)
  assert decisive.supported_wrong_action_loss == pytest.approx(
      abs(decisive.basis_delta) - decisive.basis_uncertainty
  )


def test_high_cost_wrong_prediction_contributes_more_loss() -> None:
  low = row(pair_id="low", cost=1.0)
  high = row(pair_id="high", cost=100.0)
  assert expected_supported_action_loss(high,
                                        1.0) > expected_supported_action_loss(
    low, 1.0)


def test_false_default_uses_its_own_measured_loss() -> None:
  actual = row(action=CACHE, cost=37.0)
  assert action_loss(actual, DEFAULT)["supportedLoss"] == 37.0


def test_false_cache_uses_its_own_measured_loss() -> None:
  actual = row(action=DEFAULT, cost=13.0)
  assert action_loss(actual, CACHE)["supportedLoss"] == 13.0


def test_indeterminate_has_no_decisive_regret() -> None:
  actual = row(action=INDETERMINATE, cost=999.0)
  assert expected_supported_action_loss(actual, 0.75) == 0.0
  assert action_loss(actual, CACHE)["correct"] is None


def test_family_influence_and_action_cost_remain_separate() -> None:
  rows = [row("a", "f", cost=1.0, evidence=0.25),
          row("b", "f", cost=100.0, evidence=0.75)]
  influence, scales = fold_influence(rows)
  assert scales["f"] == 1.0
  assert influence.tolist() == [0.25, 0.75]
  assert rows[0].supported_wrong_action_loss != rows[
    1].supported_wrong_action_loss


def test_outer_heldout_family_does_not_influence_scaling() -> None:
  training = [row("a", "a", k=2), row("b", "b", k=4)]
  holdout = replace(row("z", "z", k=7, r=23), productive_handles=1.0)
  scaler = fit_scaler(training, "A_PR")
  changed = fit_scaler(training + [holdout], "A_PR")
  assert scaler != changed
  assert scaler == fit_scaler(
      [item for item in training + [holdout] if item.family_id != "z"], "A_PR")


def _exercise_outer_exclusion(monkeypatch: pytest.MonkeyPatch) -> tuple[
  list[str], dict[str, object]]:
  seen: list[str] = []
  selected = {"candidateId": "A", "structure": "A_PR", "l2": 1e-3,
              "temperature": 1.0, "metrics": {}}
  monkeypatch.setattr(action_pipeline, "inner_validate_boundary", lambda rows: (
        seen.extend(item.family_id for item in rows) or {"folds": [],
                                                         "selected": selected,
                                                         "admissions": []}))
  monkeypatch.setattr(action_pipeline, "fit_boundary",
                      lambda rows, *args: fixed_fit(3.0))
  monkeypatch.setattr(action_pipeline, "inner_select_m4c",
                      lambda rows, domain: {"folds": [],
                                            "selected": {"candidateId": "M",
                                                         "l2": 1e-3}})
  monkeypatch.setattr(action_pipeline, "fit_m4c",
                      lambda rows, l2, domain: {"l2": l2, "weights": [0.0] * 8,
                                                "scales": [1.0] * 8})
  monkeypatch.setattr(action_pipeline, "inner_select_fixed_boundary",
                      lambda rows: {
                        "selected": {"candidateId": "F", "fraction": 0.5}})
  result = action_pipeline._outer_task(
      ("held", [row("a", "train"), row("h", "held")], DomainConfig()))
  return seen, result


def test_outer_heldout_family_does_not_influence_feature_selection(
    monkeypatch: pytest.MonkeyPatch) -> None:
  seen, result = _exercise_outer_exclusion(monkeypatch)
  assert seen == ["train"]
  assert result["boundaryFit"]["structure"] == "A_PR"


def test_outer_heldout_family_does_not_influence_hyperparameter_selection(
    monkeypatch: pytest.MonkeyPatch) -> None:
  seen, result = _exercise_outer_exclusion(monkeypatch)
  assert seen == ["train"]
  assert result["m4cSelection"]["selectedL2"] == 1e-3


def test_inner_validation_is_grouped_by_physical_family() -> None:
  folds = grouped_family_folds(["a", "a", "b", "c", "c", "d"])
  flattened = [family for fold in folds for family in fold]
  assert sorted(flattened) == ["a", "b", "c", "d"]


def test_current_state_contention_is_allowed() -> None:
  validate_runtime_feature_names(["contention"])
  assert feature_values(row(contention=0.75))["contention"] == 0.75


def test_counterfactual_contention_is_rejected() -> None:
  with pytest.raises(ValueError, match="counterfactual"):
    validate_runtime_feature_names(["futureContention"])


def test_mu_is_always_inside_legal_runtime_domain() -> None:
  eta = np.asarray([-1e9, -10.0, 0.0, 10.0, 1e9])
  workers = np.asarray([2.0, 7.0, 15.0, 23.0, 32.0])
  mu = bounded_mu(eta, workers)
  assert np.all(mu >= 1.0)
  assert np.all(mu <= workers)


def test_candidate_interactions_obey_heredity() -> None:
  for structure in BOUNDARY_STRUCTURES:
    verify_structure_heredity(structure)


def test_complexity_cannot_be_admitted_by_fixing_one_family() -> None:
  assert not complexity_admissible(True, True, 1)
  assert complexity_admissible(True, True, 2)


def test_m4c_lofo_training_excludes_heldout_family(
    monkeypatch: pytest.MonkeyPatch) -> None:
  seen: list[str] = []
  monkeypatch.setattr(action_pipeline, "inner_validate_boundary",
                      lambda rows: {"folds": [],
                                    "selected": {"candidateId": "A",
                                                 "structure": "A_PR",
                                                 "l2": 1e-3, "temperature": 1.0,
                                                 "metrics": {}},
                                    "admissions": []})
  monkeypatch.setattr(action_pipeline, "fit_boundary",
                      lambda rows, *args: fixed_fit(3.0))
  monkeypatch.setattr(action_pipeline, "inner_select_m4c",
                      lambda rows, domain: {"folds": [],
                                            "selected": {"candidateId": "M",
                                                         "l2": 1e-3}})
  monkeypatch.setattr(action_pipeline, "fit_m4c", lambda rows, l2, domain: (
        seen.extend(item.family_id for item in rows) or {"l2": l2,
                                                         "weights": [0.0] * 8,
                                                         "scales": [1.0] * 8}))
  monkeypatch.setattr(action_pipeline, "inner_select_fixed_boundary",
                      lambda rows: {
                        "selected": {"candidateId": "F", "fraction": 0.5}})
  action_pipeline._outer_task(
      ("held", [row("a", "train"), row("h", "held")], DomainConfig()))
  assert seen == ["train"]


def test_action_model_serialization_is_deterministic() -> None:
  first = action_pipeline._canonical_json({"b": 2, "a": [1.0, "x"]})
  second = action_pipeline._canonical_json({"a": [1.0, "x"], "b": 2})
  assert first == second
  assert hashlib.sha256(first.encode()).hexdigest() == hashlib.sha256(
    second.encode()).hexdigest()


def test_previous_frozen_artifact_hashes_are_unchanged() -> None:
  expected = {
    "experiments/pareto_training_step5/step5_candidate_model.json": "f2270a6ad9de88f547a0307661af9926798b9366ab8aa045f58c1d7a3e491d0f",
    "experiments/pareto_training_step5/training_pairs.tsv": "0bddba6fcbb6501ee2f511fde04c57349b97a6940d7e1af206c78f84c55cadaf",
    "experiments/pareto_training_step5/identifiability_audit.json": "62ecfb96c720231ad43edfe5659ebb9ad85efaa3582c043f59ba5887ad7ca3f4",
    "experiments/pareto_training_step5/pipeline_summary.json": "11e54f11f0f843985707ec4adef0801550ab06687030f182be704d3229d12a3f",
  }
  for relative, digest in expected.items():
    path = REPO_ROOT / relative
    ChecksumVerifier.verify_file(path, require_sidecar=True)
    assert ChecksumVerifier.compute_sha256(path) == digest
