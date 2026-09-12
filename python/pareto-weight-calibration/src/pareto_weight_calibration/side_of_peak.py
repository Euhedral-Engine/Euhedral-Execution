"""Read-only evaluation of frozen peak predictions as local CACHE/DEFAULT decisions."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
from typing import Any, Iterable, Sequence

from scipy.special import expit

from pareto_weight_calibration.checksum import ChecksumVerifier

SCHEMA_VERSION = 1
EVALUATOR_VERSION = "side-of-peak-v1"
LEFT = "LEFT_OF_PEAK"
RIGHT = "RIGHT_OF_PEAK"
INDETERMINATE = "INDETERMINATE"
AT_PREDICTED_PEAK = "AT_PREDICTED_PEAK"
DEFAULT = "DEFAULT"
CACHE = "CACHE"


def observed_side(current_k: int, peak_min: int, peak_max: int) -> str:
  if peak_min > peak_max:
    raise ValueError("invalid observed peak interval")
  if current_k < peak_min:
    return LEFT
  if current_k > peak_max:
    return RIGHT
  return INDETERMINATE


def predicted_side(predicted_peak: float, current_k: int,
    tolerance: float = 1e-12) -> str:
  if not math.isfinite(predicted_peak):
    raise ValueError("predicted peak must be finite")
  difference = predicted_peak - current_k
  if difference > tolerance:
    return LEFT
  if difference < -tolerance:
    return RIGHT
  return AT_PREDICTED_PEAK


def action_for_side(side: str) -> str:
  if side == RIGHT:
    return CACHE
  if side in {LEFT, AT_PREDICTED_PEAK}:
    return DEFAULT
  raise ValueError(f"No decisive runtime action for side {side!r}")


def logistic_derivative_slope(current_k: int,
    params: dict[str, float]) -> float:
  sigma = float(params["sigma"])
  amplitude = float(params["amplitude"])
  mu = float(params["mu"])
  if sigma <= 0.0 or amplitude < 0.0:
    raise ValueError("logistic slope requires sigma > 0 and amplitude >= 0")
  s = float(expit((current_k - mu) / sigma))
  return amplitude * s * (1.0 - s) * (1.0 - 2.0 * s) / sigma


def verify_logistic_slope_side(
    current_k: int,
    predicted_peak: float,
    slope: float,
    relative_tolerance: float = 1e-12,
) -> None:
  scale = max(1.0, abs(slope))
  if abs(slope) <= relative_tolerance * scale:
    return
  side = predicted_side(predicted_peak, current_k)
  expected_positive = side == LEFT
  if side == AT_PREDICTED_PEAK or (slope > 0.0) != expected_positive:
    raise ValueError(
        f"logistic derivative/peak-side disagreement at K={current_k}: "
        f"peak={predicted_peak}, slope={slope}"
    )


def m4c_margin(row: dict[str, str], physical_weights: Sequence[float]) -> float:
  if len(physical_weights) != 8:
    raise ValueError("M4-C requires eight physical weights")
  k = int(row["K"])
  if k <= 1:
    return float("-inf")
  p = float(row["P_active"])
  if p <= 0.0:
    return float("inf")
  c = float(row["c_active"])
  b = float(row["b_active"])
  r = int(row["registeredWorkers"])
  w0, w1, w2, w3, w4, w5, w6, w7 = physical_weights
  factor_a = w0 + w1 * c + w2 * b + w3 * r
  factor_b = w4 + w5 * c + w6 * b + w7 * r
  return factor_a * p / (k * (k - 1.0)) - factor_b


def frozen_action_loss(
    observed_side_value: str,
    predicted_action: str,
    effective_outcome: str,
    delta: float,
    uncertainty: float,
    throughput_k: float,
    throughput_k_minus_1: float,
) -> dict[str, Any]:
  """Uses frozen adjacent-arm evidence and never derives a winner from raw sign alone."""
  if observed_side_value == INDETERMINATE:
    return {
      "basis": "OBSERVED_SIDE_INDETERMINATE",
      "supportedLoss": 0.0,
      "observedLoss": 0.0,
      "supportedRelativeLoss": 0.0,
      "observedRelativeLoss": 0.0,
    }
  if predicted_action not in {DEFAULT, CACHE}:
    raise ValueError(f"invalid predicted action {predicted_action!r}")
  expected_action = action_for_side(observed_side_value)
  if predicted_action == expected_action:
    return {
      "basis": "SIDE_CORRECT",
      "supportedLoss": 0.0,
      "observedLoss": 0.0,
      "supportedRelativeLoss": 0.0,
      "observedRelativeLoss": 0.0,
    }

  winner_action = {
    "K_WINS": DEFAULT,
    "K_MINUS_1_WINS": CACHE,
    "STABLE_TIE": None,
  }.get(effective_outcome)
  if winner_action is None or predicted_action == winner_action:
    return {
      "basis": (
        "FROZEN_STABLE_TIE" if effective_outcome == "STABLE_TIE"
        else "WRONG_SIDE_BUT_FROZEN_ADJACENT_EVIDENCE_DOES_NOT_SUPPORT_LOSS"
      ),
      "supportedLoss": 0.0,
      "observedLoss": 0.0,
      "supportedRelativeLoss": 0.0,
      "observedRelativeLoss": 0.0,
    }

  observed_loss = abs(delta)
  supported_loss = max(0.0, observed_loss - uncertainty)
  denominator = max(throughput_k, throughput_k_minus_1)
  return {
    "basis": "FROZEN_DECISIVE_ADJACENT_ADVANTAGE",
    "supportedLoss": supported_loss,
    "observedLoss": observed_loss,
    "supportedRelativeLoss": supported_loss / denominator,
    "observedRelativeLoss": observed_loss / denominator,
  }


def _canonical_json(payload: Any) -> str:
  return json.dumps(payload, indent=2, sort_keys=True, allow_nan=False) + "\n"


def _write_json(path: Path, payload: Any) -> str:
  content = _canonical_json(payload)
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
  path.with_name(path.name + ".sha256").write_text(digest + "\n",
                                                   encoding="utf-8")
  return digest


def _write_text(path: Path, content: str) -> str:
  if not content.endswith("\n"):
    content += "\n"
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
  path.with_name(path.name + ".sha256").write_text(digest + "\n",
                                                   encoding="utf-8")
  return digest


def _verify_and_load_json(path: Path) -> dict[str, Any]:
  ChecksumVerifier.verify_file(path, require_sidecar=True)
  return json.loads(path.read_text(encoding="utf-8"))


def _family_balanced_weights(cases: Sequence[dict[str, Any]]) -> list[float]:
  family_totals: dict[str, float] = {}
  for case in cases:
    family_totals[case["familyId"]] = family_totals.get(case["familyId"],
                                                        0.0) + float(
        case["confidence"]["pairWeight"]
    )
  family_count = len(family_totals)
  if family_count == 0 or any(value <= 0.0 for value in family_totals.values()):
    raise ValueError("family-balanced weights require positive family evidence")
  return [
    float(case["confidence"]["pairWeight"])
    / family_totals[case["familyId"]]
    / family_count
    for case in cases
  ]


def _ratio(numerator: float, denominator: float) -> float | None:
  return numerator / denominator if denominator > 0.0 else None


def _subset_metrics(cases: Sequence[dict[str, Any]], model_key: str) -> dict[
  str, Any]:
  decisive = [case for case in cases if case["observedSide"] != INDETERMINATE]
  if not decisive:
    return {"caseCount": 0, "accuracy": None, "weightedAccuracy": None}
  weights = _family_balanced_weights(decisive)
  correct = [bool(case[model_key]["sideCorrect"]) for case in decisive]
  return {
    "caseCount": len(decisive),
    "familyCount": len({case["familyId"] for case in decisive}),
    "accuracy": sum(correct) / len(correct),
    "weightedAccuracy": math.fsum(
        weight * float(value) for weight, value in
        zip(weights, correct, strict=True)
    ),
    "supportedRelativeRegret": math.fsum(
        weight * float(case[model_key]["loss"]["supportedRelativeLoss"])
        for weight, case in zip(weights, decisive, strict=True)
    ),
    "falseCacheRate": _ratio(
        sum(
            case["observedSide"] == LEFT and case[model_key]["action"] == CACHE
            for case in decisive
        ),
        sum(case["observedSide"] == LEFT for case in decisive),
    ),
    "falseDefaultRate": _ratio(
        sum(
            case["observedSide"] == RIGHT and case[model_key][
              "action"] == DEFAULT
            for case in decisive
        ),
        sum(case["observedSide"] == RIGHT for case in decisive),
    ),
  }


def _breakdown(
    cases: Sequence[dict[str, Any]], model_key: str, field: str
) -> dict[str, dict[str, Any]]:
  values = sorted({str(case[field]) for case in cases})
  return {
    value: _subset_metrics(
        [case for case in cases if str(case[field]) == value], model_key)
    for value in values
  }


def _error_type_metrics(
    decisive: Sequence[dict[str, Any]], weights: Sequence[float],
    model_key: str, error_type: str
) -> dict[str, Any]:
  selected = [
    (case, weight)
    for case, weight in zip(decisive, weights, strict=True)
    if case[model_key]["wrongSideType"] == error_type
  ]
  family_losses: dict[str, float] = {}
  for case, weight in selected:
    family_losses[case["familyId"]] = family_losses.get(case["familyId"],
                                                        0.0) + (
                                          weight * float(
                                          case[model_key]["loss"][
                                            "supportedRelativeLoss"])
                                      )
  return {
    "count": len(selected),
    "evidenceWeightedCount": math.fsum(
        float(case["confidence"]["pairWeight"]) for case, _ in selected
    ),
    "familyBalancedWeightShare": math.fsum(weight for _, weight in selected),
    "supportedRelativeRegret": math.fsum(
        weight * float(case[model_key]["loss"]["supportedRelativeLoss"])
        for case, weight in selected
    ),
    "observedRelativeRegret": math.fsum(
        weight * float(case[model_key]["loss"]["observedRelativeLoss"])
        for case, weight in selected
    ),
    "supportedAbsoluteLoss": math.fsum(
        weight * float(case[model_key]["loss"]["supportedLoss"])
        for case, weight in selected
    ),
    "observedAbsoluteLoss": math.fsum(
        weight * float(case[model_key]["loss"]["observedLoss"])
        for case, weight in selected
    ),
    "worstAffectedFamilies": [
      {"familyId": family_id, "weightedSupportedRelativeRegret": value}
      for family_id, value in sorted(
          family_losses.items(), key=lambda item: (-item[1], item[0])
      )[:10]
    ],
  }


def evaluate_side_metrics(cases: Sequence[dict[str, Any]], model_key: str) -> \
dict[str, Any]:
  decisive = [case for case in cases if case["observedSide"] != INDETERMINATE]
  if not decisive:
    raise ValueError("no decisive observed-side cases")
  weights = _family_balanced_weights(decisive)
  correct = [bool(case[model_key]["sideCorrect"]) for case in decisive]
  left = [case for case in decisive if case["observedSide"] == LEFT]
  right = [case for case in decisive if case["observedSide"] == RIGHT]
  truth_actions = [action_for_side(case["observedSide"]) for case in decisive]
  predicted_actions = [case[model_key]["action"] for case in decisive]

  family_metrics: dict[str, Any] = {}
  for family_id in sorted({case["familyId"] for case in decisive}):
    family_cases = [case for case in decisive if case["familyId"] == family_id]
    total_weight = math.fsum(
        float(case["confidence"]["pairWeight"]) for case in family_cases)
    family_metrics[family_id] = {
      "caseCount": len(family_cases),
      "sideAccuracy": sum(
          case[model_key]["sideCorrect"] for case in family_cases)
                      / len(family_cases),
      "evidenceWeightedSideAccuracy": math.fsum(
          float(case["confidence"]["pairWeight"])
          * float(case[model_key]["sideCorrect"])
          for case in family_cases
      )
                                      / total_weight,
      "wrongSideCount": sum(
          not case[model_key]["sideCorrect"] for case in family_cases),
      "supportedRelativeRegret": math.fsum(
          float(case["confidence"]["pairWeight"])
          * float(case[model_key]["loss"]["supportedRelativeLoss"])
          for case in family_cases
      )
                                 / total_weight,
    }

  def precision_recall(action: str) -> dict[str, float | None]:
    true_positive = sum(
        truth == action and prediction == action
        for truth, prediction in
        zip(truth_actions, predicted_actions, strict=True)
    )
    predicted_positive = sum(
        prediction == action for prediction in predicted_actions)
    actual_positive = sum(truth == action for truth in truth_actions)
    return {
      "precision": _ratio(true_positive, predicted_positive),
      "recall": _ratio(true_positive, actual_positive),
    }

  flat = [case for case in decisive if case["localStatus"]["isFlat"]]
  distance_buckets = {
    "1": [case for case in decisive if
          case["distanceToObservedPeakInterval"] == 1],
    "2-3": [
      case for case in decisive if
      2 <= case["distanceToObservedPeakInterval"] <= 3
    ],
    "4-6": [
      case for case in decisive if
      4 <= case["distanceToObservedPeakInterval"] <= 6
    ],
    "7+": [case for case in decisive if
           case["distanceToObservedPeakInterval"] >= 7],
  }
  supported_regret = math.fsum(
      weight * float(case[model_key]["loss"]["supportedRelativeLoss"])
      for weight, case in zip(weights, decisive, strict=True)
  )
  observed_regret = math.fsum(
      weight * float(case[model_key]["loss"]["observedRelativeLoss"])
      for weight, case in zip(weights, decisive, strict=True)
  )
  nonzero_peak_error = (
    [
      case
      for case in decisive
      if case[model_key].get("predictedIntegerPeakDistanceToObservedInterval",
                             0) > 0
    ]
    if model_key == "logistic"
    else []
  )
  return {
    "totalObservationCount": len(cases),
    "evaluableSideCount": len(decisive),
    "indeterminateCount": len(cases) - len(decisive),
    "familyCount": len(family_metrics),
    "overallSideAccuracy": sum(correct) / len(correct),
    "familyBalancedEvidenceWeightedSideAccuracy": math.fsum(
        weight * float(value) for weight, value in
        zip(weights, correct, strict=True)
    ),
    "pooledEvidenceWeightedSideAccuracy": math.fsum(
        float(case["confidence"]["pairWeight"]) * float(
            case[model_key]["sideCorrect"])
        for case in decisive
    )
                                          / math.fsum(
        float(case["confidence"]["pairWeight"]) for case in decisive),
    "leftOfPeakAccuracy": sum(
        case[model_key]["sideCorrect"] for case in left) / len(left)
    if left
    else None,
    "rightOfPeakAccuracy": sum(
        case[model_key]["sideCorrect"] for case in right) / len(right)
    if right
    else None,
    "default": precision_recall(DEFAULT),
    "cache": precision_recall(CACHE),
    "falseCacheRate": _ratio(
        sum(case[model_key]["action"] == CACHE for case in left), len(left)
    ),
    "falseDefaultRate": _ratio(
        sum(case[model_key]["action"] == DEFAULT for case in right), len(right)
    ),
    "supportedRelativeRegret": supported_regret,
    "observedRelativeRegret": observed_regret,
    "worstFamilySupportedRelativeRegret": max(
        metrics["supportedRelativeRegret"] for metrics in
        family_metrics.values()
    ),
    "peakLocationDiagnostic": (
      {
        "nonzeroPeakErrorCaseCount": len(nonzero_peak_error),
        "correctSideDespiteNonzeroPeakErrorCount": sum(
            case[model_key]["sideCorrect"] for case in nonzero_peak_error
        ),
        "sideAccuracyWhenPeakErrorNonzero": _ratio(
            sum(case[model_key]["sideCorrect"] for case in nonzero_peak_error),
            len(nonzero_peak_error),
        ),
      }
      if model_key == "logistic"
      else None
    ),
    "flatLocalSide": _subset_metrics(flat, model_key),
    "errorTypes": {
      "FALSE_CACHE": _error_type_metrics(decisive, weights, model_key,
                                         "FALSE_CACHE"),
      "FALSE_DEFAULT": _error_type_metrics(
          decisive, weights, model_key, "FALSE_DEFAULT"
      ),
    },
    "distanceBuckets": {
      bucket: _subset_metrics(bucket_cases, model_key)
      for bucket, bucket_cases in distance_buckets.items()
    },
    "breakdowns": {
      "registeredWorkers": _breakdown(decisive, model_key, "registeredWorkers"),
      "sourceDeficitRegime": _breakdown(decisive, model_key,
                                        "sourceDeficitRegime"),
      "bodyBucket": _breakdown(decisive, model_key, "bodyBucket"),
      "productiveHandleRatio": _breakdown(
          decisive, model_key, "productiveHandleRatioBucket"
      ),
    },
    "families": family_metrics,
  }


def _distance_to_interval(k: int, lower: int, upper: int) -> int:
  if lower <= k <= upper:
    return 0
  return min(abs(k - lower), abs(k - upper))


def _source_regime(source_count: int, registered_workers: int) -> str:
  if source_count == 1:
    return "ONE_SOURCE"
  if source_count >= registered_workers - 1:
    return "NEAR_PLENTIFUL"
  return "LOW_SOURCE"


def _body_bucket(work_units: int) -> str:
  if work_units == 0:
    return "WU0"
  if work_units <= 112:
    return "LOW_POSITIVE_WU1_112"
  if work_units < 768:
    return "MEDIUM_WU113_767"
  return "HIGH_WU768_PLUS"


def _phr_bucket(productive_handles: float, registered_workers: int) -> str:
  ratio = productive_handles / registered_workers
  if ratio <= 0.25:
    return "(0,0.25]"
  if ratio <= 0.5:
    return "(0.25,0.5]"
  if ratio <= 0.75:
    return "(0.5,0.75]"
  return "(0.75,1+]"


def _case_index(family_curves: dict[str, Any]) -> dict[
  tuple[str, int], dict[str, Any]]:
  result: dict[tuple[str, int], dict[str, Any]] = {}
  for family in family_curves["families"]:
    for context in family["current_contexts"]:
      key = (family["family_id"], int(context["current_k"]))
      if key in result:
        raise ValueError(f"duplicate family/current-K context: {key}")
      result[key] = {"family": family, "context": context}
  return result


def _validate_lofo(peak_results: dict[str, Any]) -> None:
  families = sorted(peak_results["comparisonFamilies"])
  held_out = sorted(
      fold["heldOutFamily"] for fold in peak_results["selected"]["folds"])
  if held_out != families:
    raise ValueError("LOFO fold inventory does not match comparison families")
  for case in peak_results["selected"]["cases"]:
    if case["familyId"] not in families:
      raise ValueError("held-out case belongs to no comparison family")


def _build_cases(
    peak_results: dict[str, Any],
    family_curves: dict[str, Any],
    observed_peaks: dict[str, Any],
    rows: Sequence[dict[str, str]],
    m4c_weights: Sequence[float],
) -> list[dict[str, Any]]:
  _validate_lofo(peak_results)
  contexts = _case_index(family_curves)
  peaks = {item["family_id"]: item for item in observed_peaks["families"]}
  rows_by_pair = {row["pairId"]: row for row in rows}
  if len(rows_by_pair) != len(rows):
    raise ValueError("duplicate pairId in frozen training pairs")
  cases: list[dict[str, Any]] = []
  for frozen_case in sorted(
      peak_results["selected"]["cases"],
      key=lambda item: (item["familyId"], item["currentK"])
  ):
    family_id = frozen_case["familyId"]
    current_k = int(frozen_case["currentK"])
    indexed = contexts[(family_id, current_k)]
    family = indexed["family"]
    context = indexed["context"]
    row = rows_by_pair[context["pair_id"]]
    peak = peaks[family_id]
    interval_min = int(peak["peak_interval_min"])
    interval_max = int(peak["peak_interval_max"])
    truth = observed_side(current_k, interval_min, interval_max)

    continuous_peak = float(frozen_case["params"]["mu"])
    logistic_side = predicted_side(continuous_peak, current_k)
    slope = logistic_derivative_slope(current_k, frozen_case["params"])
    verify_logistic_slope_side(current_k, continuous_peak, slope)
    logistic_action = action_for_side(logistic_side)

    margin = m4c_margin(row, m4c_weights)
    m4c_action = CACHE if margin > 0.0 else DEFAULT
    m4c_side = RIGHT if m4c_action == CACHE else LEFT

    throughput_k = float(row["basisThroughput_K"])
    throughput_k_minus_1 = float(row["basisThroughput_KMinus1"])
    delta = float(row["basisDeltaThroughput"])
    uncertainty = float(row["basisUncertainty"])

    def model_payload(side: str, action: str, extra: dict[str, Any]) -> dict[
      str, Any]:
      side_correct = None if truth == INDETERMINATE else side == truth
      wrong_type = None
      if truth == LEFT and action == CACHE:
        wrong_type = "FALSE_CACHE"
      elif truth == RIGHT and action == DEFAULT:
        wrong_type = "FALSE_DEFAULT"
      return {
        **extra,
        "predictedSide": side,
        "action": action,
        "sideCorrect": side_correct,
        "wrongSideType": wrong_type,
        "loss": frozen_action_loss(
            truth,
            action,
            row["effectiveOutcome"],
            delta,
            uncertainty,
            throughput_k,
            throughput_k_minus_1,
        ),
      }

    cases.append(
        {
          "familyId": family_id,
          "pairId": row["pairId"],
          "currentK": current_k,
          "registeredWorkers": int(row["registeredWorkers"]),
          "sourceCount": int(family["source_count"]),
          "workUnits": int(family["work_units"]),
          "sourceDeficitRegime": _source_regime(
              int(family["source_count"]), int(row["registeredWorkers"])
          ),
          "bodyBucket": _body_bucket(int(family["work_units"])),
          "productiveHandleRatio": float(row["P_active"])
                                   / int(row["registeredWorkers"]),
          "productiveHandleRatioBucket": _phr_bucket(
              float(row["P_active"]), int(row["registeredWorkers"])
          ),
          "observedPeakInterval": [interval_min, interval_max],
          "observedSide": truth,
          "observedAction": None if truth == INDETERMINATE else action_for_side(
            truth),
          "distanceToObservedPeakInterval": _distance_to_interval(
              current_k, interval_min, interval_max
          ),
          "confidence": {
            "pairWeight": float(row["pairWeight"]),
            "effectiveOutcome": row["effectiveOutcome"],
            "evidenceBasis": row["labelEvidenceBasis"],
          },
          "measured": {
            "throughputK": throughput_k,
            "throughputKMinus1": throughput_k_minus_1,
            "deltaKMinus1MinusK": delta,
            "uncertainty": uncertainty,
          },
          "localStatus": {
            "isFlat": frozen_case["localPlateau"] is not None,
            "kMinus1KPlus1": frozen_case["localPlateau"],
          },
          "logistic": model_payload(
              logistic_side,
              logistic_action,
              {
                "predictedPeakContinuous": continuous_peak,
                "predictedPeakInteger": int(frozen_case["predictedK"]),
                "distanceToPredictedPeak": abs(current_k - continuous_peak),
                "predictedIntegerPeakDistanceToObservedInterval": int(
                    frozen_case["kError"]
                ),
                "throughputSlope": slope,
                "slopeSideAgreement": True,
              },
          ),
          "m4c": model_payload(
              m4c_side,
              m4c_action,
              {
                "margin": margin,
                "baselineStatus": (
                  "CURRENT_FULL_FIT_STEP5_CANDIDATE; not fold-specific because the "
                  "Step 5 artifact does not retain per-fold predictions"
                ),
              },
          ),
        }
    )
  return cases


def _family_diagnostics(cases: Sequence[dict[str, Any]],
    family_metrics: dict[str, Any]) -> list[dict[str, Any]]:
  families = sorted({case["familyId"] for case in cases})
  selected: set[str] = set()
  selectors = (
    lambda family: family.startswith("Fam_R23_S1_WU0"),
    lambda family: family.startswith("Fam_R23_") and "_WU0" in family,
    lambda family: family.startswith("Fam_R23_") and any(
        token in family for token in ("_WU64", "_WU112", "_WU172")
    ),
    lambda family: family.startswith("Fam_R15_"),
    lambda family: family.startswith("Fam_R7_"),
    lambda family: any(
        token in family for token in ("R7_S6_", "R15_S14_", "R23_S22_")),
  )
  for selector in selectors:
    match = next((family for family in families if selector(family)), None)
    if match is not None:
      selected.add(match)
  diagnostics = []
  for family_id in sorted(selected):
    family_cases = [case for case in cases if case["familyId"] == family_id]
    diagnostics.append(
        {
          "familyId": family_id,
          "metrics": family_metrics.get(family_id),
          "rows": [
            {
              "k": case["currentK"],
              "measuredThroughput": case["measured"]["throughputK"],
              "uncertainty": case["measured"]["uncertainty"],
              "observedPeakInterval": case["observedPeakInterval"],
              "predictedPeak": case["logistic"]["predictedPeakContinuous"],
              "observedSide": case["observedSide"],
              "predictedSide": case["logistic"]["predictedSide"],
              "observedAction": case["observedAction"],
              "predictedAction": case["logistic"]["action"],
              "supportedRelativeRegret": case["logistic"]["loss"][
                "supportedRelativeLoss"
              ],
            }
            for case in family_cases
          ],
        }
    )
  return diagnostics


def _findings(
    logistic: dict[str, Any],
    m4c: dict[str, Any],
    costly_families: Sequence[tuple[str, float]],
    acceptance: dict[str, Any],
) -> str:
  distance = logistic["distanceBuckets"]
  lines = [
    "# Side-of-Peak Runtime Objective Re-evaluation",
    "",
    "The global logistic curve is evaluated only as a local binary classifier: LEFT -> DEFAULT and RIGHT -> CACHE. Observed-interval cases are indeterminate and excluded from decisive accuracy/regret.",
    "",
    "## Logistic LOFO result",
    "",
    f"- Overall side accuracy: {logistic['overallSideAccuracy']:.6f}.",
    f"- Family-balanced evidence-weighted accuracy: {logistic['familyBalancedEvidenceWeightedSideAccuracy']:.6f}.",
    f"- LEFT accuracy: {logistic['leftOfPeakAccuracy']:.6f}.",
    f"- RIGHT accuracy: {logistic['rightOfPeakAccuracy']:.6f}.",
    f"- False-CACHE rate: {logistic['falseCacheRate']:.6f}.",
    f"- False-DEFAULT rate: {logistic['falseDefaultRate']:.6f}.",
    f"- Supported relative regret: {logistic['supportedRelativeRegret']:.6f}.",
    f"- Worst-family supported relative regret: {logistic['worstFamilySupportedRelativeRegret']:.6f}.",
    f"- Flat-local-side accuracy: {logistic['flatLocalSide']['accuracy']} ({logistic['flatLocalSide']['caseCount']} cases).",
    f"- Correct side despite nonzero integer peak error: {logistic['peakLocationDiagnostic']['correctSideDespiteNonzeroPeakErrorCount']}/{logistic['peakLocationDiagnostic']['nonzeroPeakErrorCaseCount']}.",
    "",
    "## Distance from observed peak interval",
    "",
    "| Distance | Cases | Side accuracy | Weighted accuracy | Supported regret | False CACHE | False DEFAULT |",
    "|---|---:|---:|---:|---:|---:|---:|",
  ]
  for bucket in ("1", "2-3", "4-6", "7+"):
    metrics = distance[bucket]
    values = [
      bucket,
      str(metrics["caseCount"]),
      str(metrics.get("accuracy")),
      str(metrics.get("weightedAccuracy")),
      str(metrics.get("supportedRelativeRegret")),
      str(metrics.get("falseCacheRate")),
      str(metrics.get("falseDefaultRate")),
    ]
    lines.append("| " + " | ".join(values) + " |")
  lines.extend(
      [
        "",
        "## Same-objective M4-C comparison",
        "",
        "M4-C is the current full-fit Step 5 candidate. Its artifact does not retain fold-specific predictions, so this comparison favors M4-C relative to the genuinely held-out logistic predictions.",
        "",
        "| Model | Weighted accuracy | Supported regret | Worst family | False CACHE | False DEFAULT | Flat-local accuracy |",
        "|---|---:|---:|---:|---:|---:|---:|",
        f"| Logistic LOFO | {logistic['familyBalancedEvidenceWeightedSideAccuracy']:.6f} | {logistic['supportedRelativeRegret']:.6f} | {logistic['worstFamilySupportedRelativeRegret']:.6f} | {logistic['falseCacheRate']:.6f} | {logistic['falseDefaultRate']:.6f} | {logistic['flatLocalSide']['accuracy']} |",
        f"| M4-C current full-fit | {m4c['familyBalancedEvidenceWeightedSideAccuracy']:.6f} | {m4c['supportedRelativeRegret']:.6f} | {m4c['worstFamilySupportedRelativeRegret']:.6f} | {m4c['falseCacheRate']:.6f} | {m4c['falseDefaultRate']:.6f} | {m4c['flatLocalSide']['accuracy']} |",
        "",
        "## Costly logistic wrong-side families",
        "",
      ]
  )
  for family_id, regret in costly_families:
    lines.append(f"- `{family_id}`: supported relative regret {regret:.6f}.")
  lines.extend(
      [
        "",
        "## Acceptance",
        "",
        f"Production integration justified: `{str(acceptance['productionIntegrationJustified']).lower()}`.",
        "",
        "Peak-location error is not used as an acceptance criterion. The production decision is based exclusively on side/action accuracy and frozen adjacent-evidence regret.",
        "",
      ]
  )
  for criterion in acceptance["criteria"]:
    lines.append(
        f"- {criterion['status']}: {criterion['criterion']} - {criterion['evidence']}"
    )
  return "\n".join(lines) + "\n"


def _acceptance(logistic: dict[str, Any], m4c: dict[str, Any]) -> dict[
  str, Any]:
  topology = logistic["breakdowns"]["registeredWorkers"]
  false_default_regret = logistic["errorTypes"]["FALSE_DEFAULT"][
    "supportedRelativeRegret"
  ]
  false_cache_regret = logistic["errorTypes"]["FALSE_CACHE"][
    "supportedRelativeRegret"
  ]
  m4c_false_default_regret = m4c["errorTypes"]["FALSE_DEFAULT"][
    "supportedRelativeRegret"
  ]
  m4c_false_cache_regret = m4c["errorTypes"]["FALSE_CACHE"][
    "supportedRelativeRegret"
  ]
  criteria = [
    {
      "criterion": "Supported relative regret competitive with M4-C",
      "status": "PASS"
      if logistic["supportedRelativeRegret"] <= m4c["supportedRelativeRegret"]
      else "FAIL",
      "evidence": (
        f"logistic={logistic['supportedRelativeRegret']:.6f}, "
        f"M4-C={m4c['supportedRelativeRegret']:.6f}"
      ),
    },
    {
      "criterion": "Worst-family supported regret not materially worse than M4-C",
      "status": "PASS"
      if logistic["worstFamilySupportedRelativeRegret"]
         <= m4c["worstFamilySupportedRelativeRegret"]
      else "FAIL",
      "evidence": (
        f"logistic={logistic['worstFamilySupportedRelativeRegret']:.6f}, "
        f"M4-C={m4c['worstFamilySupportedRelativeRegret']:.6f}"
      ),
    },
    {
      "criterion": "RIGHT/CACHE decisions avoid large false-DEFAULT losses",
      "status": "PASS"
      if false_default_regret <= m4c_false_default_regret
      else "FAIL",
      "evidence": (
        f"false-DEFAULT rate={logistic['falseDefaultRate']:.6f}, "
        f"supported regret={false_default_regret:.6f} vs "
        f"M4-C={m4c_false_default_regret:.6f}"
      ),
    },
    {
      "criterion": "LEFT/DEFAULT decisions avoid costly premature CACHE",
      "status": "PASS" if false_cache_regret <= m4c_false_cache_regret else "FAIL",
      "evidence": (
        f"false-CACHE rate={logistic['falseCacheRate']:.6f}, "
        f"supported regret={false_cache_regret:.6f} vs "
        f"M4-C={m4c_false_cache_regret:.6f}"
      ),
    },
    {
      "criterion": "Flat-local evidence demonstrates global-side value",
      "status": "PASS"
      if logistic["flatLocalSide"]["caseCount"] > 0
         and logistic["flatLocalSide"]["accuracy"]
         > m4c["flatLocalSide"]["accuracy"]
      else "FAIL",
      "evidence": (
        f"logistic={logistic['flatLocalSide']['accuracy']} vs "
        f"M4-C={m4c['flatLocalSide']['accuracy']} across "
        f"{logistic['flatLocalSide']['caseCount']} evaluable cases"
      ),
    },
    {
      "criterion": "Results are stable across R7, R15, and R23",
      "status": "NOT_ESTABLISHED",
      "evidence": (
          "accuracy/regret by R: "
          + ", ".join(
          f"R{r}={metrics['accuracy']:.6f}/{metrics['supportedRelativeRegret']:.6f}"
          for r, metrics in
          sorted(topology.items(), key=lambda item: int(item[0]))
      )
          + "; R15 regret is dominated by one catastrophic WU0 false-DEFAULT"
      ),
    },
    {
      "criterion": "No counterfactual telemetry leakage",
      "status": "PASS",
      "evidence": (
        "side prediction uses each frozen held-out curve's fitted continuous peak and "
        "current K only; candidate-K telemetry is never read"
      ),
    },
  ]
  return {
    "productionIntegrationJustified": all(
        criterion["status"] == "PASS" for criterion in criteria
    ),
    "criteria": criteria,
  }


def run_side_of_peak_evaluation(
    peak_results_path: Path,
    family_curves_path: Path,
    observed_peaks_path: Path,
    training_pairs_path: Path,
    step5_candidate_path: Path,
    output_dir: Path,
) -> dict[str, Any]:
  input_paths = (
    peak_results_path,
    family_curves_path,
    observed_peaks_path,
    training_pairs_path,
    step5_candidate_path,
  )
  peak_results = _verify_and_load_json(peak_results_path)
  family_curves = _verify_and_load_json(family_curves_path)
  observed_peaks = _verify_and_load_json(observed_peaks_path)
  ChecksumVerifier.verify_file(training_pairs_path, require_sidecar=True)
  step5 = _verify_and_load_json(step5_candidate_path)
  with training_pairs_path.open("r", encoding="utf-8", newline="") as stream:
    rows = list(csv.DictReader(stream, delimiter="\t"))
  cases = _build_cases(
      peak_results,
      family_curves,
      observed_peaks,
      rows,
      [float(value) for value in step5["model"]["physicalWeights"]],
  )
  logistic_metrics = evaluate_side_metrics(cases, "logistic")
  m4c_metrics = evaluate_side_metrics(cases, "m4c")
  acceptance = _acceptance(logistic_metrics, m4c_metrics)
  family_diagnostics = _family_diagnostics(cases, logistic_metrics["families"])
  flat_cases = [case for case in cases if case["localStatus"]["isFlat"]]
  costly_families = sorted(
      (
        (family_id, metrics["supportedRelativeRegret"])
        for family_id, metrics in logistic_metrics["families"].items()
        if metrics["supportedRelativeRegret"] > 0.0
      ),
      key=lambda item: (-item[1], item[0]),
  )
  input_hashes = {
    str(path): ChecksumVerifier.compute_sha256(path) for path in input_paths
  }

  output_dir.mkdir(parents=True, exist_ok=True)
  digests = {
    "results": _write_json(
        output_dir / "side_of_peak_results.json",
        {
          "schemaVersion": SCHEMA_VERSION,
          "evaluatorVersion": EVALUATOR_VERSION,
          "inputArtifactHashes": input_hashes,
          "caseCount": len(cases),
          "cases": cases,
        },
    ),
    "families": _write_json(
        output_dir / "side_of_peak_family_metrics.json",
        {
          "schemaVersion": SCHEMA_VERSION,
          "logisticFamilies": logistic_metrics["families"],
          "m4cFamilies": m4c_metrics["families"],
          "selectedFamilyDiagnostics": family_diagnostics,
        },
    ),
    "flatLocal": _write_json(
        output_dir / "side_of_peak_flat_local_cases.json",
        {
          "schemaVersion": SCHEMA_VERSION,
          "caseCount": len(flat_cases),
          "evaluableCaseCount": sum(
              case["observedSide"] != INDETERMINATE for case in flat_cases
          ),
          "logisticMetrics": logistic_metrics["flatLocalSide"],
          "m4cMetrics": m4c_metrics["flatLocalSide"],
          "cases": flat_cases,
        },
    ),
    "comparison": _write_json(
        output_dir / "side_of_peak_comparison_m4c.json",
        {
          "schemaVersion": SCHEMA_VERSION,
          "objective": "LEFT_OF_PEAK -> DEFAULT; RIGHT_OF_PEAK -> CACHE",
          "weighting": (
            "Evidence weights are normalized within physical family, then each family "
            "receives equal aggregate weight."
          ),
          "indeterminatePolicy": (
            "Observed K inside the frozen peak interval is excluded from decisive side "
            "accuracy and receives zero side regret."
          ),
          "atPredictedPeakAction": DEFAULT,
          "logistic": logistic_metrics,
          "m4c": m4c_metrics,
          "m4cComparabilityCaveat": (
            "M4-C uses the current full-fit Step 5 candidate because its artifact does "
            "not retain per-fold held-out predictions; logistic cases are exact LOFO."
          ),
          "acceptance": acceptance,
        },
    ),
  }
  findings = _findings(logistic_metrics, m4c_metrics, costly_families[:10],
                       acceptance)
  digests["findings"] = _write_text(output_dir / "side_of_peak_findings.md",
                                    findings)
  summary = {
    "schemaVersion": SCHEMA_VERSION,
    "evaluatorVersion": EVALUATOR_VERSION,
    "status": "EXISTING_LOFO_PREDICTIONS_REEVALUATED_NO_REFIT",
    "caseCount": len(cases),
    "logistic": {
      key: logistic_metrics[key]
      for key in (
        "evaluableSideCount",
        "indeterminateCount",
        "overallSideAccuracy",
        "familyBalancedEvidenceWeightedSideAccuracy",
        "leftOfPeakAccuracy",
        "rightOfPeakAccuracy",
        "falseCacheRate",
        "falseDefaultRate",
        "supportedRelativeRegret",
        "worstFamilySupportedRelativeRegret",
        "flatLocalSide",
        "distanceBuckets",
      )
    },
    "m4c": {
      key: m4c_metrics[key]
      for key in (
        "overallSideAccuracy",
        "familyBalancedEvidenceWeightedSideAccuracy",
        "falseCacheRate",
        "falseDefaultRate",
        "supportedRelativeRegret",
        "worstFamilySupportedRelativeRegret",
        "flatLocalSide",
      )
    },
    "acceptance": acceptance,
    "artifacts": digests,
  }
  _write_json(output_dir / "side_of_peak_summary.json", summary)
  return summary


def _parser() -> argparse.ArgumentParser:
  parser = argparse.ArgumentParser(
      description="Re-evaluate frozen global-peak LOFO predictions as local binary actions"
  )
  parser.add_argument("--peak-results", type=Path, required=True)
  parser.add_argument("--family-curves", type=Path, required=True)
  parser.add_argument("--observed-peaks", type=Path, required=True)
  parser.add_argument("--training-pairs", type=Path, required=True)
  parser.add_argument("--step5-candidate", type=Path, required=True)
  parser.add_argument("--output-dir", type=Path, required=True)
  return parser


def main(argv: Sequence[str] | None = None) -> int:
  args = _parser().parse_args(argv)
  summary = run_side_of_peak_evaluation(
      args.peak_results,
      args.family_curves,
      args.observed_peaks,
      args.training_pairs,
      args.step5_candidate,
      args.output_dir,
  )
  print(json.dumps(summary, indent=2, sort_keys=True, allow_nan=False))
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
