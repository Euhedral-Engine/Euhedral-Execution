from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path

import pytest

from pareto_weight_calibration.action_model import CACHE, DEFAULT, \
  INDETERMINATE, ActionRow
from pareto_weight_calibration.direct_side import BASE_FEATURES, DirectFit, \
  DirectScaler
from pareto_weight_calibration.r7_s1_transition import (
  INPUTS,
  additive_sequence_classification,
  direct_score_decomposition,
  matched_body_comparisons,
  normalized_feature_delta,
  ordered_body_rows,
  run_diagnostic,
  transition_repeatability,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
EXPECTED_HASHES = {
  "dataset": "d0e59e5cce68630a157b055d7b4958c1a46eb7f45bc3fe125b71a43b5ee89f02",
  "boundaryOuter": "461225bad75f31d14eb4ea07a9c148963f3bba52c21673c97e3526e35ac9f6f8",
  "boundaryCandidate": "ec9554b019bbfecee6aa4c92bd8504f0ce9473533618a18caf21c3d3dc8e6230",
  "m4cOuter": "cfae463aca26002c9aaa8366a5f2d632a8a28f95949941347ac9b6f2284ffb82",
  "directOuter": "79e4287295f46798084ff0e96226ea62c1588925121b0cc7060b4a26dcfbf4b2",
  "directCandidate": "70b3aa42c389c48d8e7805f656b77af61244305db0372666cdffa02cb95fcffa",
  "logistic": "53d9eafbb6a35130222ce342aac07f7a94659401cd2374ba801fa70194e1d839",
  "trainingPairs": "0bddba6fcbb6501ee2f511fde04c57349b97a6940d7e1af206c78f84c55cadaf",
}


def _row(
    pair_id: str,
    family: str,
    r: int = 7,
    wu: int = 0,
    k: int = 2,
    action: str = CACHE,
    body: float = 3.0,
    p: float = 1.0,
) -> ActionRow:
  decisive = action != INDETERMINATE
  return ActionRow(
      pair_id=pair_id, family_id=family, current_k=k, registered_workers=r,
      source_count=1, work_units=wu, productive_handles=p, body_log=body,
      body_cost_ns=math.expm1(body), contention=0.9, observed_action=action,
      supported_wrong_action_loss=10.0 if decisive else 0.0,
      observed_wrong_action_loss=12.0 if decisive else 0.0,
      supported_relative_wrong_action_loss=0.1 if decisive else 0.0,
      observed_relative_wrong_action_loss=0.12 if decisive else 0.0,
      evidence_weight=1.0, family_scale=1.0, influence_weight=1.0,
      effective_outcome=
      {CACHE: "K_MINUS_1_WINS", DEFAULT: "K_WINS", INDETERMINATE: "STABLE_TIE"}[
        action],
      evidence_basis="WHOLE_AGREEMENT" if decisive else "STABLE_TIE",
      basis_throughput_k=100.0, basis_throughput_k_minus_1=90.0,
      basis_delta=10.0, basis_uncertainty=2.0, runtime_commit="commit",
      topology_id="topology", k_run_path=pair_id + "-k", k_run_sha256="a" * 64,
      k_minus_1_run_path=pair_id + "-km1", k_minus_1_run_sha256="b" * 64,
  )


def _fit() -> DirectFit:
  scaler = DirectScaler(BASE_FEATURES, (0.0,) * 5, (2.0,) * 5)
  return DirectFit(
      structure="S0_BASE", feature_names=BASE_FEATURES, l2=1e-3,
      temperature=1.0, scaler=scaler,
      coefficients=(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), objective=0.0,
      success=True, iterations=1, minimum_domain_k_slope=2.0,
  )


def test_body_series_rows_are_ordered_by_work_then_k() -> None:
  rows = [_row("b", "b", wu=64, k=5), _row("a", "a", wu=0, k=2),
          _row("c", "c", wu=64, k=2)]
  assert [row.pair_id for row in ordered_body_rows(rows)] == ["a", "c", "b"]


def test_matched_comparison_does_not_fabricate_missing_body_levels() -> None:
  rows = [_row("r7", "r7", r=7), _row("r15", "r15", r=15)]
  result = matched_body_comparisons(rows)
  assert not result["exactMatched"]
  assert result["partialMatched"][0]["presentR"] == [7, 15]
  assert result["partialMatched"][0]["missingR"] == [23]


def test_normalized_distance_uses_supplied_training_scaling() -> None:
  first = _row("a", "a", body=2.0)
  second = _row("b", "b", body=4.0)
  result = normalized_feature_delta(first, second, _fit().scaler)
  assert result["normalizedDelta"]["body"] == 1.0
  assert result["scaledEuclideanDistance"] == 1.0


def test_score_decomposition_sums_to_exact_score() -> None:
  result = direct_score_decomposition(_row("a", "a"), _fit())
  assert math.fsum(result["contributions"].values()) == result["totalScore"]


def test_additive_compatibility_does_not_change_coefficients() -> None:
  fit = _fit()
  before = fit.coefficients
  result = additive_sequence_classification([
    _row("low", "low", action=CACHE, body=2.0),
    _row("high", "high", action=DEFAULT, body=4.0),
  ])
  assert result["classification"] == "ADDITIVE_GEOMETRY_COMPATIBLE"
  assert fit.coefficients == before


def test_repeatability_counts_one_matched_group_not_rows() -> None:
  rows = [
    _row("low", "low", wu=0, action=CACHE),
    _row("high", "high", wu=64, action=DEFAULT),
    _row("duplicate", "high", wu=64, action=DEFAULT),
  ]
  result = transition_repeatability(rows)
  assert result["supporting"] == 1
  assert len(result["cases"]) == 1
  assert result["cases"][0]["physicalFamilyIds"] == ["high", "low"]


def test_indeterminate_transition_is_not_supporting_or_opposing() -> None:
  result = transition_repeatability([
    _row("low", "low", wu=0, action=CACHE),
    _row("high", "high", wu=64, action=INDETERMINATE),
  ])
  assert result["supporting"] == 0
  assert result["opposing"] == 0
  assert result["indeterminate"] == 1


def test_model_action_semantics_are_identical() -> None:
  result = direct_score_decomposition(_row("a", "a"), _fit())
  assert result["predictedAction"] == CACHE
  assert result["totalScore"] > 0.0


def test_existing_frozen_artifacts_retain_expected_hashes() -> None:
  for name, relative in INPUTS.items():
    digest = hashlib.sha256((REPO_ROOT / relative).read_bytes()).hexdigest()
    assert digest == EXPECTED_HASHES[name]


def test_provenance_audit_does_not_modify_frozen_evidence(
    tmp_path: Path) -> None:
  before = (REPO_ROOT / INPUTS["trainingPairs"]).read_bytes()
  run_diagnostic(REPO_ROOT, tmp_path / "out")
  assert (REPO_ROOT / INPUTS["trainingPairs"]).read_bytes() == before


def test_new_artifacts_are_deterministic(tmp_path: Path) -> None:
  first = tmp_path / "first"
  second = tmp_path / "second"
  run_diagnostic(REPO_ROOT, first)
  run_diagnostic(REPO_ROOT, second)
  first_files = sorted(path.name for path in first.iterdir())
  assert first_files == sorted(path.name for path in second.iterdir())
  for name in first_files:
    assert (first / name).read_bytes() == (second / name).read_bytes()
