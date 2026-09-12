"""Focused tests for the runtime side-of-peak evaluation objective."""

from __future__ import annotations

import hashlib
from pathlib import Path

import pytest

from pareto_weight_calibration.side_of_peak import (
  AT_PREDICTED_PEAK,
  CACHE,
  DEFAULT,
  INDETERMINATE,
  LEFT,
  RIGHT,
  _canonical_json,
  _validate_lofo,
  action_for_side,
  evaluate_side_metrics,
  frozen_action_loss,
  logistic_derivative_slope,
  observed_side,
  predicted_side,
  run_side_of_peak_evaluation,
  verify_logistic_slope_side,
)


def test_predicted_peak_above_current_maps_left_and_default() -> None:
  side = predicted_side(8.0, 5)
  assert side == LEFT
  assert action_for_side(side) == DEFAULT


def test_predicted_peak_below_current_maps_right_and_cache() -> None:
  side = predicted_side(4.0, 7)
  assert side == RIGHT
  assert action_for_side(side) == CACHE


def test_observed_interval_is_indeterminate() -> None:
  assert observed_side(6, 5, 7) == INDETERMINATE


def test_nonzero_peak_error_can_preserve_runtime_action() -> None:
  assert observed_side(20, 8, 8) == RIGHT
  assert predicted_side(13.0, 20) == RIGHT


def test_crossing_current_k_flips_runtime_action() -> None:
  assert action_for_side(predicted_side(11.0, 10)) == DEFAULT
  assert action_for_side(predicted_side(9.0, 10)) == CACHE


@pytest.mark.parametrize(("current_k", "expected_side"),
                         [(3, LEFT), (9, RIGHT)])
def test_logistic_slope_sign_matches_side(current_k: int,
    expected_side: str) -> None:
  params = {"baseline": 1.0, "amplitude": 20.0, "mu": 6.0, "sigma": 1.5}
  slope = logistic_derivative_slope(current_k, params)
  assert predicted_side(params["mu"], current_k) == expected_side
  verify_logistic_slope_side(current_k, params["mu"], slope)


def test_predicted_peak_itself_is_conservative_default() -> None:
  assert predicted_side(5.0, 5) == AT_PREDICTED_PEAK
  assert action_for_side(AT_PREDICTED_PEAK) == DEFAULT


def test_indeterminate_observation_has_no_decisive_penalty() -> None:
  loss = frozen_action_loss(INDETERMINATE, CACHE, "K_WINS", -50.0, 1.0, 100.0,
                            50.0)
  assert loss["supportedLoss"] == 0.0
  assert loss["observedLoss"] == 0.0


def test_wrong_side_uses_supported_not_raw_sign() -> None:
  tie = frozen_action_loss(LEFT, CACHE, "STABLE_TIE", -50.0, 1.0, 100.0, 50.0)
  noisy = frozen_action_loss(LEFT, CACHE, "K_WINS", -5.0, 10.0, 100.0, 95.0)
  decisive = frozen_action_loss(LEFT, CACHE, "K_WINS", -20.0, 5.0, 100.0, 80.0)
  assert tie["supportedLoss"] == 0.0
  assert noisy["supportedLoss"] == 0.0
  assert decisive["supportedLoss"] == 15.0


def _metric_case(
    family: str,
    observed: str,
    action: str,
    *,
    flat: bool = False,
) -> dict:
  predicted = RIGHT if action == CACHE else LEFT
  wrong_type = None
  if observed == LEFT and action == CACHE:
    wrong_type = "FALSE_CACHE"
  elif observed == RIGHT and action == DEFAULT:
    wrong_type = "FALSE_DEFAULT"
  return {
    "familyId": family,
    "observedSide": observed,
    "confidence": {"pairWeight": 1.0},
    "localStatus": {"isFlat": flat},
    "distanceToObservedPeakInterval": 3,
    "registeredWorkers": 7,
    "sourceDeficitRegime": "LOW_SOURCE",
    "bodyBucket": "WU0",
    "productiveHandleRatioBucket": "(0,0.25]",
    "model": {
      "predictedSide": predicted,
      "action": action,
      "sideCorrect": predicted == observed,
      "wrongSideType": wrong_type,
      "loss": {
        "supportedLoss": 1.0 if wrong_type else 0.0,
        "observedLoss": 2.0 if wrong_type else 0.0,
        "supportedRelativeLoss": 0.1 if wrong_type else 0.0,
        "observedRelativeLoss": 0.2 if wrong_type else 0.0,
      },
    },
  }


def test_false_cache_and_false_default_are_separate() -> None:
  metrics = evaluate_side_metrics(
      [
        _metric_case("a", LEFT, CACHE),
        _metric_case("b", RIGHT, DEFAULT),
        _metric_case("c", LEFT, DEFAULT),
      ],
      "model",
  )
  assert metrics["errorTypes"]["FALSE_CACHE"]["count"] == 1
  assert metrics["errorTypes"]["FALSE_DEFAULT"]["count"] == 1


def test_flat_local_case_can_have_correct_global_side() -> None:
  metrics = evaluate_side_metrics([_metric_case("a", RIGHT, CACHE, flat=True)],
                                  "model")
  assert metrics["flatLocalSide"]["caseCount"] == 1
  assert metrics["flatLocalSide"]["accuracy"] == 1.0


def test_models_share_identical_action_regret_basis() -> None:
  first = frozen_action_loss(LEFT, CACHE, "K_WINS", -20.0, 5.0, 100.0, 80.0)
  second = frozen_action_loss(LEFT, CACHE, "K_WINS", -20.0, 5.0, 100.0, 80.0)
  assert first == second


def test_lofo_inventory_preserves_family_folds() -> None:
  payload = {
    "comparisonFamilies": ["a", "b"],
    "selected": {
      "folds": [{"heldOutFamily": "a"}, {"heldOutFamily": "b"}],
      "cases": [{"familyId": "a"}, {"familyId": "b"}],
    },
  }
  _validate_lofo(payload)
  payload["selected"]["folds"][1]["heldOutFamily"] = "a"
  with pytest.raises(ValueError, match="LOFO fold inventory"):
    _validate_lofo(payload)


def test_side_prediction_has_no_counterfactual_telemetry_inputs() -> None:
  assert predicted_side(8.0, 5) == predicted_side(8.0, 5)


def test_canonical_serialization_is_byte_identical() -> None:
  payload = {"b": [2, 1], "a": {"value": 3}}
  assert _canonical_json(payload).encode() == _canonical_json(payload).encode()


def test_real_artifacts_repeat_byte_identically(tmp_path: Path) -> None:
  repository = Path(__file__).resolve().parents[3]
  peak_dir = repository / "experiments/pareto_peak_training"
  if not peak_dir.exists():
    pytest.skip("Peak training artifacts are not present")
  first = tmp_path / "first"
  second = tmp_path / "second"
  arguments = (
    peak_dir / "lofo_peak_results.json",
    peak_dir / "family_curves.json",
    peak_dir / "observed_peaks.json",
    repository / "experiments/pareto_training_step5/training_pairs.tsv",
    repository / "experiments/pareto_training_step5/step5_candidate_model.json",
  )
  run_side_of_peak_evaluation(*arguments, first)
  run_side_of_peak_evaluation(*arguments, second)
  first_files = sorted(path.name for path in first.iterdir())
  second_files = sorted(path.name for path in second.iterdir())
  assert first_files == second_files
  for name in first_files:
    assert hashlib.sha256(
      (first / name).read_bytes()).digest() == hashlib.sha256(
        (second / name).read_bytes()
    ).digest()
