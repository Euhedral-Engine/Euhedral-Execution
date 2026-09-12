import hashlib
import json
from pathlib import Path

import pytest

from pareto_weight_calibration.integer_cutoff import (
  CACHE,
  CONTRADICTORY,
  DEFAULT,
  INDETERMINATE,
  SUPPORTED,
  UNINFORMATIVE,
  _cutoff_metrics,
  _write_json,
  derive_supported_cutoff,
  first_cache_k,
  frozen_action_loss,
  interval_cutoff_error,
  runtime_action,
  signed_cutoff_error,
)

ROOT = Path(__file__).resolve().parents[3]
ACTION_DIR = ROOT / "experiments" / "pareto_action_model_training"
EXPECTED_HASHES = {
  "action_training_dataset.json": "d0e59e5cce68630a157b055d7b4958c1a46eb7f45bc3fe125b71a43b5ee89f02",
  "action_model_outer_lofo.json": "461225bad75f31d14eb4ea07a9c148963f3bba52c21673c97e3526e35ac9f6f8",
  "action_model_m4c_lofo.json": "cfae463aca26002c9aaa8366a5f2d632a8a28f95949941347ac9b6f2284ffb82",
}


def _row(current_k: int, action: str, workers: int = 7) -> dict:
  return {
    "current_k": current_k,
    "registered_workers": workers,
    "observed_action": action,
  }


def _loss_row(action: str, supported_loss: float = 0.75) -> dict:
  return {
    "observed_action": action,
    "supported_wrong_action_loss": supported_loss,
    "observed_wrong_action_loss": supported_loss / 2,
    "supported_relative_wrong_action_loss": supported_loss,
    "observed_relative_wrong_action_loss": supported_loss / 2,
  }


def test_floor_plus_one_collapses_fractional_boundaries() -> None:
  assert [first_cache_k(value) for value in (7.05, 7.49, 7.99)] == [8, 8, 8]


def test_same_integer_interval_produces_identical_actions() -> None:
  for current_k in range(1, 12):
    expected = runtime_action(current_k, 8)
    assert (CACHE if current_k > 7.05 else DEFAULT) == expected
    assert (CACHE if current_k > 7.99 else DEFAULT) == expected


def test_crossing_integer_boundary_changes_cutoff() -> None:
  assert first_cache_k(7.99) == 8
  assert first_cache_k(8.0) == 9
  assert runtime_action(8, 8) == CACHE
  assert runtime_action(8, 9) == DEFAULT


def test_signed_cutoff_error_is_zero_inside_supported_interval() -> None:
  assert signed_cutoff_error(5, 4, 6) == 0
  assert signed_cutoff_error(3, 4, 6) == -1
  assert signed_cutoff_error(8, 4, 6) == 2
  assert interval_cutoff_error(3, 5, 4, 6) == 0


def test_indeterminate_only_family_does_not_invent_exact_cutoff() -> None:
  result = derive_supported_cutoff(
      [_row(2, INDETERMINATE), _row(7, INDETERMINATE)])
  assert result["status"] == UNINFORMATIVE
  assert (result["observedFirstCacheKMin"],
          result["observedFirstCacheKMax"]) == (2, 8)


def test_contradictory_decisive_actions_are_not_scored_as_cutoff() -> None:
  result = derive_supported_cutoff([_row(5, DEFAULT), _row(2, CACHE)])
  assert result["status"] == CONTRADICTORY
  assert result["observedFirstCacheKMin"] is None
  assert result["observedFirstCacheKMax"] is None


def test_runtime_action_requires_whole_core_values() -> None:
  assert runtime_action(7, 8) == DEFAULT
  assert runtime_action(8, 8) == CACHE
  with pytest.raises(TypeError):
    runtime_action(8.0, 8)


def test_equal_integer_cutoff_has_equal_action_loss() -> None:
  row = _loss_row(CACHE)
  for current_k in range(1, 12):
    first = runtime_action(current_k, first_cache_k(7.05))
    second = runtime_action(current_k, first_cache_k(7.99))
    assert frozen_action_loss(row, first) == frozen_action_loss(row, second)


def test_one_core_cutoff_error_can_retain_large_regret() -> None:
  case = {
    "integerCutoffError": 1,
    "integerCutoffErrorBucket": "1",
    "influenceWeight": 1.0,
    "loss": frozen_action_loss(_loss_row(DEFAULT, 0.9), CACHE),
  }
  metrics = _cutoff_metrics([case])
  assert metrics["withinOneCore"]["rawRate"] == 1.0
  assert metrics["overallActionRegret"][
           "supportedRelativeRegretContribution"] == 0.9


def test_frozen_input_hashes_and_sidecars_are_unchanged() -> None:
  for name, expected in EXPECTED_HASHES.items():
    path = ACTION_DIR / name
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    assert actual == expected
    assert path.with_name(path.name + ".sha256").read_text().strip() == expected


def test_json_artifact_serialization_is_deterministic(tmp_path: Path) -> None:
  payload = {"z": [3, 2, 1], "a": {"right": CACHE, "left": DEFAULT}}
  first = tmp_path / "first.json"
  second = tmp_path / "second.json"
  assert _write_json(first, payload) == _write_json(second, payload)
  assert first.read_bytes() == second.read_bytes()
  assert json.loads(first.read_text()) == payload


def test_supported_interval_respects_only_decisive_constraints() -> None:
  result = derive_supported_cutoff([
    _row(3, DEFAULT), _row(4, INDETERMINATE), _row(6, CACHE)
  ])
  assert result["status"] == SUPPORTED
  assert (result["observedFirstCacheKMin"],
          result["observedFirstCacheKMax"]) == (4, 6)
