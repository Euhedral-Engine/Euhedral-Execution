"""Focused global-throughput-peak training tests."""

from __future__ import annotations

import csv
import hashlib
import json
import math
from pathlib import Path

import numpy as np
import pytest

from pareto_weight_calibration.export import PAIRS_TSV_COLUMNS
from pareto_weight_calibration.peak_curves import (
  ArmEvidence,
  CurrentContext,
  CurvePoint,
  FamilyCurve,
  derive_observed_peak,
  reconstruct_family_curves,
)
from pareto_weight_calibration.peak_models import (
  AsymmetricSigmoidDerivativeShape,
  ContextParameterMapper,
  LogisticDerivativeShape,
  LogNormalHumpShape,
  PeakCandidateModel,
)
from pareto_weight_calibration.peak_pipeline import (
  _direction,
  _local_plateau,
  family_lofo_partitions,
  reference_controller,
)


def _point(k: int, throughput: float, uncertainty: float = 1.0) -> CurvePoint:
  variance = (uncertainty / 2.0) ** 2
  return CurvePoint(k, throughput, variance, uncertainty, (), ())


def _curve(values: list[float]) -> FamilyCurve:
  contexts = tuple(
      CurrentContext(f"p{k}", k, 4.0, math.log1p(100.0), 100.0, 0.3,
                     len(values))
      for k in range(1, len(values) + 1)
  )
  return FamilyCurve(
      "Fam_R12_S4_WU100",
      12,
      4,
      100,
      "commit",
      "topology",
      tuple(_point(k, value) for k, value in enumerate(values, start=1)),
      contexts,
      tuple(context.pair_id for context in contexts),
  )


def _constant_logistic_model(peak: float = 7.0) -> tuple[
  LogisticDerivativeShape, PeakCandidateModel]:
  contexts = [CurrentContext("a", 3, 4.0, 4.0, 54.0, 0.2, 12)]
  targets = {
    "peak": peak,
    "logWidth": math.log(1.5),
    "logAmplitude": math.log(400.0),
    "baseline": 100.0,
  }
  mapper = ContextParameterMapper.fit(
      "P", "PEAK_ONLY", [(contexts[0], targets, 1.0)]
  )
  return LogisticDerivativeShape(), PeakCandidateModel(
      "LOGISTIC_DERIVATIVE", "K", "P", "PEAK_ONLY", mapper
  )


def test_logistic_derivative_has_one_peak_at_center() -> None:
  shape = LogisticDerivativeShape()
  params = {"baseline": 10.0, "amplitude": 100.0, "mu": 7.0, "sigma": 1.5}
  valid_k = list(range(1, 13))
  predicted = shape.predict_curve(valid_k, 4.0, "K", params)
  assert valid_k[int(np.argmax(predicted))] == 7
  assert predicted[6] > params["baseline"]
  shape.validate_unimodal(valid_k, 4.0, "K", params)


def test_global_argmax_drives_multi_rank_directions_and_ignores_local_search() -> None:
  shape, model = _constant_logistic_model(7.0)
  valid_k = list(range(1, 13))
  below = CurrentContext("below", 3, 4.0, 4.0, 54.0, 0.2, 12)
  above = CurrentContext("above", 11, 4.0, 4.0, 54.0, 0.2, 12)
  assert model.argmax_k(shape, below, valid_k) == 7
  assert model.argmax_k(shape, above, valid_k) == 7
  assert _direction(below.current_k, 7, 7) == "SCALE_UP"
  assert _direction(above.current_k, 7, 7) == "SCALE_DOWN"


def test_current_k_does_not_leak_into_counterfactual_curve() -> None:
  shape, model = _constant_logistic_model(7.0)
  valid_k = list(range(1, 13))
  first = CurrentContext("first", 2, 4.0, 4.0, 54.0, 0.2, 12)
  second = CurrentContext("second", 10, 4.0, 4.0, 54.0, 0.2, 12)
  _, first_curve = model.predict_curve(shape, first, valid_k)
  _, second_curve = model.predict_curve(shape, second, valid_k)
  np.testing.assert_array_equal(first_curve, second_curve)


def test_local_plateau_does_not_force_hold_when_global_peak_is_far() -> None:
  values = [10, 30, 60, 90, 120, 150, 180, 140, 105, 100, 100.2, 100.1]
  curve = _curve(values)
  assert _local_plateau(curve, 11) is not None
  assert _direction(11, 7, 7) == "SCALE_DOWN"


def test_hysteresis_does_not_change_target_k() -> None:
  valid_k = [1, 2, 3, 4]
  predicted = [10.0, 11.0, 12.0, 12.1]
  result = reference_controller(2, valid_k, predicted, 4, 4,
                                movement_uncertainty=2.0)
  assert result["targetK"] == 4
  assert result["action"] == "HOLD"


def test_current_k_inside_target_interval_holds() -> None:
  result = reference_controller(3, [1, 2, 3, 4], [1.0, 2.0, 3.0, 3.0], 3, 4,
                                0.0)
  assert result["action"] == "HOLD"


def test_lofo_never_splits_a_family() -> None:
  partitions = family_lofo_partitions(["c", "a", "b", "a"])
  assert [held_out for _, held_out in partitions] == ["a", "b", "c"]
  for train, held_out in partitions:
    assert held_out not in train
    assert set(train) | {held_out} == {"a", "b", "c"}


def _write_sidecar(path: Path) -> None:
  digest = hashlib.sha256(path.read_bytes()).hexdigest()
  path.with_name(path.name + ".sha256").write_text(digest + "\n",
                                                   encoding="utf-8")


def _pair_row(pair_id: str, k: int) -> dict[str, str]:
  row = {column: "" for column in PAIRS_TSV_COLUMNS}
  row.update(
      {
        "pairId": pair_id,
        "runtimeCommit": "commit",
        "topologyId": "topology",
        "lifecycleMode": "CONTINUOUS",
        "cacheActuatorVersion": "cache-v1",
        "cacheParkNs": "15000",
        "K": str(k),
        "registeredWorkers": "7",
        "workUnits": "10",
        "c_active": "0.2",
        "smoothedBodyCostNs_active": "100",
        "b_active": str(math.log1p(100.0)),
        "P_active": "2",
        "c_withdrawn": "0",
        "P_withdrawn": "0",
        "meanThroughput_K": str(100.0 + k),
        "variance_K": "4",
        "cv_K": "0.01",
        "forkCount_K": "4",
        "meanThroughput_KMinus1": str(99.0 + k),
        "variance_KMinus1": "4",
        "cv_KMinus1": "0.01",
        "forkCount_KMinus1": "4",
        "deltaThroughput": "-1",
        "relativeDeltaPercent": "-1",
        "governingMargin": "2",
        "wholeRunOutcome": "K_WINS",
        "lateRegionOutcome": "K_WINS",
        "trajectoryStatus": "STABLE_AGREEMENT",
        "effectiveOutcome": "K_WINS",
        "labelEvidenceBasis": "WHOLE_AGREEMENT",
        "y": "0",
        "pairWeight": "1",
        "basisThroughput_K": str(100.0 + k),
        "basisThroughput_KMinus1": str(99.0 + k),
        "basisDeltaThroughput": "-1",
        "basisVariance_K": "4",
        "basisVariance_KMinus1": "4",
        "basisUncertainty": "2",
        "kRunPath": f"/run/k{k}",
        "kRunSha256": f"sha{k}",
        "kMinus1RunPath": f"/run/k{k - 1}",
        "kMinus1RunSha256": f"sha{k - 1}",
      }
  )
  return row


def _write_pair_fixture(tmp_path: Path, conflict: bool) -> tuple[Path, Path]:
  pairs = tmp_path / "pairs.tsv"
  first = _pair_row("surface__k2-vs-k1__s2__wu10", 2)
  second = _pair_row("surface__k3-vs-k2__s2__wu10", 3)
  second["kMinus1RunPath"] = first["kRunPath"]
  second["kMinus1RunSha256"] = first["kRunSha256"]
  second["meanThroughput_KMinus1"] = "999" if conflict else first[
    "meanThroughput_K"]
  second["variance_KMinus1"] = first["variance_K"]
  second["forkCount_KMinus1"] = first["forkCount_K"]
  with pairs.open("w", encoding="utf-8", newline="") as stream:
    writer = csv.DictWriter(stream, fieldnames=PAIRS_TSV_COLUMNS,
                            delimiter="\t", lineterminator="\n")
    writer.writeheader()
    writer.writerows([first, second])
  _write_sidecar(pairs)
  legacy = tmp_path / "legacy.tsv"
  legacy.write_text("pairId\tfamilyId\n", encoding="utf-8")
  _write_sidecar(legacy)
  return pairs, legacy


def test_duplicate_physical_arms_are_deduplicated(tmp_path: Path) -> None:
  pairs, legacy = _write_pair_fixture(tmp_path, conflict=False)
  curves, diagnostics = reconstruct_family_curves(pairs, legacy)
  assert len(curves) == 1
  assert len(curves[0].points) == 3
  assert diagnostics["exactDuplicateReferenceCount"] == 1


def test_conflicting_duplicate_arm_fails_loudly(tmp_path: Path) -> None:
  pairs, legacy = _write_pair_fixture(tmp_path, conflict=True)
  with pytest.raises(ValueError, match="Conflicting duplicate physical arm"):
    reconstruct_family_curves(pairs, legacy)


def test_peak_interval_preserves_statistically_indistinguishable_neighbors() -> None:
  peak = derive_observed_peak(_curve([100.0, 120.0, 119.5, 90.0]))
  assert peak.best_k == 2
  assert peak.peak_interval_min == 2
  assert peak.peak_interval_max == 3


def test_peak_interval_does_not_span_a_non_supported_gap() -> None:
  peak = derive_observed_peak(_curve([120.0, 90.0, 119.5, 80.0]))
  assert peak.best_k == 1
  assert peak.peak_interval_min == 1
  assert peak.peak_interval_max == 1
  assert peak.per_k[2]["globallyIndistinguishableFromBest"] is True


def test_invalid_shape_parameters_are_rejected() -> None:
  logistic = LogisticDerivativeShape()
  with pytest.raises(ValueError, match="sigma"):
    logistic.predict_curve([1, 2], 1.0, "K",
                           {"baseline": 0, "amplitude": 1, "mu": 1, "sigma": 0})
  with pytest.raises(ValueError, match="amplitude"):
    logistic.predict_curve([1, 2], 1.0, "K",
                           {"baseline": 0, "amplitude": -1, "mu": 1,
                            "sigma": 1})


def test_argmax_cannot_escape_runtime_bounds() -> None:
  shape = LogisticDerivativeShape()
  params = {"baseline": 0.0, "amplitude": 1.0, "mu": 100.0, "sigma": 1.0}
  assert shape.argmax_k([1, 2, 3], 1.0, "K", params) in {1, 2, 3}


@pytest.mark.parametrize(
    ("shape", "params"),
    [
      (
          AsymmetricSigmoidDerivativeShape(),
          {"baseline": 1.0, "amplitude": 10.0, "mu": 7.0, "sigmaLeft": 1.0,
           "sigmaRight": 3.0},
      ),
      (
          LogNormalHumpShape(),
          {"baseline": 1.0, "amplitude": 10.0, "muLog": math.log(7.0),
           "sigma": 0.4, "offset": 0.0},
      ),
    ],
)
def test_asymmetric_shapes_remain_unimodal(shape, params) -> None:
  shape.validate_unimodal(list(range(1, 24)), 1.0, "K", params)


def test_candidate_serialization_is_byte_stable() -> None:
  _, model = _constant_logistic_model()
  first = json.dumps(model.mapper.serialize(), sort_keys=True,
                     separators=(",", ":"))
  second = json.dumps(model.mapper.serialize(), sort_keys=True,
                      separators=(",", ":"))
  assert first.encode("utf-8") == second.encode("utf-8")


def test_existing_step5_candidate_remains_readable_and_checksummed() -> None:
  repository = Path(__file__).resolve().parents[3]
  candidate = repository / "experiments/pareto_training_step5/step5_candidate_model.json"
  if not candidate.exists():
    pytest.skip("Repository Step 5 artifact is not present")
  expected = candidate.with_name(candidate.name + ".sha256").read_text(
    encoding="utf-8").split()[0]
  assert hashlib.sha256(candidate.read_bytes()).hexdigest() == expected
  payload = json.loads(candidate.read_text(encoding="utf-8"))
  assert payload["model"]["structure"] == "M4-C"
