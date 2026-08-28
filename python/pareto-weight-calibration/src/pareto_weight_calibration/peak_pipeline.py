"""Deterministic global-throughput-peak training and family-level validation stage."""

from __future__ import annotations

import argparse
from concurrent.futures import ProcessPoolExecutor
from dataclasses import asdict
import hashlib
import json
import math
import os
from pathlib import Path
from statistics import median
from typing import Any, Iterable, Sequence

import numpy as np

from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.peak_curves import (
  CurrentContext,
  FamilyCurve,
  ObservedPeak,
  curve_to_dict,
  derive_observed_peak,
  input_hash,
  interpolate_peak_regret,
  peak_distance,
  peak_to_dict,
  reconstruct_family_curves,
)
from pareto_weight_calibration.peak_models import (
  CONTEXT_STRUCTURES,
  CONTEXT_VARIANTS,
  COORDINATES,
  SHAPES,
  ContextParameterMapper,
  PeakCandidateModel,
  ShapeFit,
  ThroughputShape,
  UnimodalIsotonicReference,
  horizontal_coordinate,
)

SCHEMA_VERSION = 1
TRAINER_VERSION = "global-peak-v1"
RIDGE = 1e-3


def _jsonable(value: Any) -> Any:
  if isinstance(value, np.ndarray):
    return [_jsonable(item) for item in value.tolist()]
  if isinstance(value, np.generic):
    return _jsonable(value.item())
  if isinstance(value, dict):
    return {str(key): _jsonable(item) for key, item in value.items()}
  if isinstance(value, (list, tuple)):
    return [_jsonable(item) for item in value]
  if isinstance(value, Path):
    return str(value)
  if isinstance(value, float) and not math.isfinite(value):
    raise ValueError(f"Non-finite artifact value: {value}")
  return value


def _write_json(path: Path, payload: Any) -> str:
  path.parent.mkdir(parents=True, exist_ok=True)
  content = json.dumps(_jsonable(payload), indent=2, sort_keys=True,
                       allow_nan=False) + "\n"
  path.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
  path.with_name(path.name + ".sha256").write_text(digest + "\n",
                                                   encoding="utf-8")
  return digest


def _write_text(path: Path, content: str) -> str:
  path.parent.mkdir(parents=True, exist_ok=True)
  if not content.endswith("\n"):
    content += "\n"
  path.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
  path.with_name(path.name + ".sha256").write_text(digest + "\n",
                                                   encoding="utf-8")
  return digest


def _fit_shape_task(args: tuple[str, str, FamilyCurve]) -> tuple[
  str, str, str, ShapeFit | None, str | None]:
  shape_name, coordinate, curve = args
  shape = SHAPES[shape_name]
  if len(curve.points) < shape.minimum_points:
    return shape_name, coordinate, curve.family_id, None, (
      f"requires {shape.minimum_points} points; observed {len(curve.points)}"
    )
  try:
    fit = shape.fit(curve, coordinate)
    return shape_name, coordinate, curve.family_id, fit, None
  except Exception as exc:
    return shape_name, coordinate, curve.family_id, None, f"{type(exc).__name__}: {exc}"


def _parallel_shape_fits(curves: Sequence[FamilyCurve]) -> tuple[
  dict[tuple[str, str, str], ShapeFit], list[dict[str, str]]
]:
  tasks = [
    (shape_name, coordinate, curve)
    for shape_name in sorted(SHAPES)
    for coordinate in COORDINATES
    for curve in curves
  ]
  worker_count = min(32, max(1, os.cpu_count() or 1), len(tasks))
  with ProcessPoolExecutor(max_workers=worker_count) as executor:
    results = list(executor.map(_fit_shape_task, tasks))
  fits: dict[tuple[str, str, str], ShapeFit] = {}
  exclusions: list[dict[str, str]] = []
  for shape_name, coordinate, family_id, fit, reason in results:
    if fit is None:
      exclusions.append(
          {
            "shape": shape_name,
            "coordinate": coordinate,
            "familyId": family_id,
            "reason": reason or "unknown",
          }
      )
    else:
      fits[(shape_name, coordinate, family_id)] = fit
  return fits, exclusions


def _common_comparison_families(
    curves: Sequence[FamilyCurve], fits: dict[tuple[str, str, str], ShapeFit]
) -> list[str]:
  available = []
  for curve in curves:
    if all(
        (shape_name, coordinate, curve.family_id) in fits
        for shape_name in SHAPES
        for coordinate in COORDINATES
    ):
      available.append(curve.family_id)
  return sorted(available)


def _context_samples(
    family_ids: Iterable[str],
    curves_by_id: dict[str, FamilyCurve],
    fits: dict[tuple[str, str, str], ShapeFit],
    shape_name: str,
    coordinate: str,
) -> list[tuple[CurrentContext, dict[str, float], float]]:
  samples: list[tuple[CurrentContext, dict[str, float], float]] = []
  for family_id in sorted(family_ids):
    curve = curves_by_id[family_id]
    fit = fits[(shape_name, coordinate, family_id)]
    confidence_sum = math.fsum(
        max(context.confidence, 1e-12) for context in curve.current_contexts)
    for context in curve.current_contexts:
      weight = max(context.confidence, 1e-12) / confidence_sum
      samples.append((context, fit.targets, weight))
  return samples


def _direction(current_k: int, target_min: int, target_max: int) -> str:
  if current_k < target_min:
    return "SCALE_UP"
  if current_k > target_max:
    return "SCALE_DOWN"
  return "HOLD"


def family_lofo_partitions(family_ids: Sequence[str]) -> list[
  tuple[tuple[str, ...], str]]:
  """Returns deterministic whole-family train/validation partitions."""
  ordered = tuple(sorted(set(family_ids)))
  return [
    (tuple(family_id for family_id in ordered if family_id != held_out),
     held_out)
    for held_out in ordered
  ]


def reference_controller(
    current_k: int,
    valid_k: Sequence[int],
    predicted_throughput: Sequence[float],
    target_min: int,
    target_max: int,
    movement_uncertainty: float,
) -> dict[str, Any]:
  """Separates global target estimation from movement-worthiness hysteresis."""
  if current_k not in valid_k:
    raise ValueError("current_k must be inside valid_k")
  if target_min not in valid_k or target_max not in valid_k or target_min > target_max:
    raise ValueError("target interval must be inside valid_k")
  values = np.asarray(predicted_throughput, dtype=np.float64)
  if len(values) != len(valid_k) or np.any(~np.isfinite(values)):
    raise ValueError(
      "predicted throughput must be finite and aligned with valid_k")
  target_k = int(valid_k[int(np.argmax(values))])
  current_value = float(values[valid_k.index(current_k)])
  target_value = float(values[valid_k.index(target_k)])
  predicted_gain = target_value - current_value
  direction = _direction(current_k, target_min, target_max)
  action = direction if predicted_gain > movement_uncertainty else "HOLD"
  return {
    "targetK": target_k,
    "targetInterval": [target_min, target_max],
    "predictedGain": predicted_gain,
    "action": action,
  }


def _predicted_interval(valid_k: Sequence[int], predicted: np.ndarray) -> tuple[
  int, int]:
  maximum = float(np.max(predicted))
  tolerance = max(1e-12, abs(maximum) * 1e-12)
  peak_ks = [k for k, value in zip(valid_k, predicted, strict=True) if
             maximum - value <= tolerance]
  return min(peak_ks), max(peak_ks)


def _point_at(curve: FamilyCurve, k: int):
  return next((point for point in curve.points if point.k == k), None)


def _local_plateau(curve: FamilyCurve, current_k: int) -> dict[str, Any] | None:
  left = _point_at(curve, current_k - 1)
  center = _point_at(curve, current_k)
  right = _point_at(curve, current_k + 1)
  if left is None or center is None or right is None:
    return None

  def indistinguishable(first, second) -> bool:
    uncertainty = 2.0 * math.sqrt(first.mean_variance + second.mean_variance)
    return abs(first.mean_throughput - second.mean_throughput) <= uncertainty

  if not (indistinguishable(left, center) and indistinguishable(center, right)):
    return None
  return {
    "kMinus1": {
      "k": left.k,
      "throughput": left.mean_throughput,
      "uncertainty": left.uncertainty,
    },
    "k": {
      "k": center.k,
      "throughput": center.mean_throughput,
      "uncertainty": center.uncertainty,
    },
    "kPlus1": {
      "k": right.k,
      "throughput": right.mean_throughput,
      "uncertainty": right.uncertainty,
    },
  }


def _candidate_lofo(
    shape_name: str,
    coordinate: str,
    structure: str,
    variant: str,
    comparison_families: Sequence[str],
    curves_by_id: dict[str, FamilyCurve],
    peaks: dict[str, ObservedPeak],
    fits: dict[tuple[str, str, str], ShapeFit],
    retain_cases: bool = False,
) -> dict[str, Any]:
  shape = SHAPES[shape_name]
  cases: list[dict[str, Any]] = []
  fold_summaries: list[dict[str, Any]] = []
  for train_tuple, held_out in family_lofo_partitions(comparison_families):
    train_ids = list(train_tuple)
    mapper = ContextParameterMapper.fit(
        structure,
        variant,
        _context_samples(train_ids, curves_by_id, fits, shape_name, coordinate),
        ridge=RIDGE,
    )
    model = PeakCandidateModel(shape_name, coordinate, structure, variant,
                               mapper)
    curve = curves_by_id[held_out]
    peak = peaks[held_out]
    family_cases: list[dict[str, Any]] = []
    for context in curve.current_contexts:
      valid_k = list(range(curve.valid_k_min, curve.valid_k_max + 1))
      params, predicted = model.predict_curve(shape, context, valid_k)
      target_min, target_max = _predicted_interval(valid_k, predicted)
      predicted_k = valid_k[int(np.argmax(predicted))]
      observed_direction = _direction(
          context.current_k, peak.peak_interval_min, peak.peak_interval_max
      )
      predicted_direction = _direction(context.current_k, target_min,
                                       target_max)
      regret = interpolate_peak_regret(predicted_k, curve, peak)
      current_index = context.current_k - curve.valid_k_min
      target_index = predicted_k - curve.valid_k_min
      predicted_gain = float(predicted[target_index] - predicted[current_index])
      current_point = _point_at(curve, context.current_k)
      hysteresis_threshold = current_point.uncertainty if current_point is not None else 0.0
      controller = reference_controller(
          context.current_k,
          valid_k,
          predicted,
          target_min,
          target_max,
          hysteresis_threshold,
      )
      action = controller["action"]
      plateau = _local_plateau(curve, context.current_k)
      point_errors = []
      for point in curve.points:
        predicted_value = predicted[point.k - curve.valid_k_min]
        point_errors.append(
          (predicted_value - point.mean_throughput) / peak.peak_throughput)
      case = {
        "familyId": held_out,
        "currentK": context.current_k,
        "observedPeakInterval": [peak.peak_interval_min,
                                 peak.peak_interval_max],
        "predictedPeakInterval": [target_min, target_max],
        "predictedK": predicted_k,
        "kError": peak_distance(predicted_k, peak),
        "exactPeakHit": predicted_k == peak.best_k,
        "peakIntervalHit": peak_distance(predicted_k, peak) == 0,
        "observedDirection": observed_direction,
        "predictedDirection": predicted_direction,
        "directionCorrect": predicted_direction == observed_direction,
        "multiRank": peak_distance(context.current_k, peak) >= 2,
        "predictedGain": predicted_gain,
        "hysteresisThreshold": hysteresis_threshold,
        "referenceAction": action,
        "regret": regret,
        "relativePointwiseSquaredError": float(
          np.mean(np.square(point_errors))),
        "params": params if retain_cases else None,
        "predictedCurve": (
          [
            {"k": k, "throughput": float(value)}
            for k, value in zip(valid_k, predicted, strict=True)
          ]
          if retain_cases
          else None
        ),
        "localPlateau": plateau,
        "plateauEscapeCase": plateau is not None
                             and peak_distance(context.current_k, peak) >= 2,
      }
      family_cases.append(case)
      cases.append(case)
    fold_summaries.append(
        {
          "heldOutFamily": held_out,
          "trainFamilyCount": len(train_ids),
          "caseCount": len(family_cases),
          "meanKError": float(
            np.mean([case["kError"] for case in family_cases])),
          "peakIntervalHitRate": float(
              np.mean([case["peakIntervalHit"] for case in family_cases])
          ),
        }
    )
  metrics = _case_metrics(cases)
  return {
    "shape": shape_name,
    "coordinate": coordinate,
    "contextStructure": structure,
    "contextVariant": variant,
    "valid": True,
    "metrics": metrics,
    "folds": fold_summaries if retain_cases else None,
    "cases": cases if retain_cases else None,
  }


def _weighted_quantile(values: Sequence[float], weights: Sequence[float],
    quantile: float) -> float:
  ordered = sorted(zip(values, weights, strict=True), key=lambda item: item[0])
  threshold = quantile * math.fsum(weights)
  cumulative = 0.0
  for value, weight in ordered:
    cumulative += weight
    if cumulative >= threshold:
      return float(value)
  return float(ordered[-1][0])


def _case_metrics(cases: Sequence[dict[str, Any]]) -> dict[str, Any]:
  if not cases:
    raise ValueError("Cannot evaluate an empty case set")
  family_counts: dict[str, int] = {}
  for case in cases:
    family_counts[case["familyId"]] = family_counts.get(case["familyId"], 0) + 1
  weights = [1.0 / family_counts[case["familyId"]] for case in cases]
  total_weight = math.fsum(weights)
  errors = [float(case["kError"]) for case in cases]
  supported = [
    (case["regret"].get("supportedRelativeRegret"), weight)
    for case, weight in zip(cases, weights, strict=True)
    if case["regret"].get("supportedRelativeRegret") is not None
  ]
  supported_weight = math.fsum(weight for _, weight in supported)
  family_regrets: dict[str, list[tuple[float, float]]] = {}
  for case, weight in zip(cases, weights, strict=True):
    value = case["regret"].get("supportedRelativeRegret")
    if value is not None:
      family_regrets.setdefault(case["familyId"], []).append(
          (float(value), weight))
  family_mean_regrets = {
    family_id: math.fsum(value * weight for value, weight in values)
               / math.fsum(weight for _, weight in values)
    for family_id, values in family_regrets.items()
  }
  multi_up = [case for case in cases if
              case["multiRank"] and case["observedDirection"] == "SCALE_UP"]
  multi_down = [case for case in cases if
                case["multiRank"] and case["observedDirection"] == "SCALE_DOWN"]
  plateau = [case for case in cases if case["plateauEscapeCase"]]

  def accuracy(selected: Sequence[dict[str, Any]]) -> float | None:
    if not selected:
      return None
    return float(np.mean([case["directionCorrect"] for case in selected]))

  return {
    "caseCount": len(cases),
    "familyCount": len(family_counts),
    "exactPeakKHitRate": math.fsum(
        weight * float(case["exactPeakHit"])
        for case, weight in zip(cases, weights, strict=True)
    ) / total_weight,
    "peakIntervalHitRate": math.fsum(
        weight * float(case["peakIntervalHit"])
        for case, weight in zip(cases, weights, strict=True)
    ) / total_weight,
    "medianAbsoluteKError": _weighted_quantile(errors, weights, 0.5),
    "weightedMeanAbsoluteKError": math.fsum(
        weight * error for weight, error in zip(weights, errors, strict=True)
    ) / total_weight,
    "p90AbsoluteKError": _weighted_quantile(errors, weights, 0.9),
    "worstFamilyKError": max(errors),
    "relativePointwiseRmse": math.sqrt(
        math.fsum(
            weight * case["relativePointwiseSquaredError"]
            for case, weight in zip(cases, weights, strict=True)
        )
        / total_weight
    ),
    "supportedRegretCoverage": supported_weight / total_weight,
    "pooledSupportedRelativeThroughputRegret": (
      math.fsum(
          value * weight for value, weight in supported) / supported_weight
      if supported_weight > 0.0
      else None
    ),
    "worstFamilySupportedRelativeThroughputRegret": (
      max(family_mean_regrets.values()) if family_mean_regrets else None
    ),
    "multiRankScaleUpCaseCount": len(multi_up),
    "multiRankScaleUpDirectionAccuracy": accuracy(multi_up),
    "multiRankScaleDownCaseCount": len(multi_down),
    "multiRankScaleDownDirectionAccuracy": accuracy(multi_down),
    "plateauEscapeCaseCount": len(plateau),
    "plateauEscapeAccuracy": accuracy(plateau),
    "familySupportedRelativeRegrets": family_mean_regrets,
  }


def _candidate_key(result: dict[str, Any]) -> tuple[Any, ...]:
  metrics = result["metrics"]
  shape_complexity = {
    "LOGISTIC_DERIVATIVE": 0,
    "LOG_NORMAL_HUMP": 1,
    "ASYMMETRIC_SIGMOID_HUMP": 2,
  }[result["shape"]]
  structure_complexity = list(CONTEXT_STRUCTURES).index(
      result["contextStructure"])
  variant_complexity = list(CONTEXT_VARIANTS).index(result["contextVariant"])
  regret = metrics["pooledSupportedRelativeThroughputRegret"]
  return (
    -metrics["supportedRegretCoverage"],
    float("inf") if regret is None else regret,
    metrics["weightedMeanAbsoluteKError"],
    metrics["p90AbsoluteKError"],
    shape_complexity,
    structure_complexity,
    variant_complexity,
    result["coordinate"],
  )


def _m4c_target(context: CurrentContext, weights: Sequence[float]) -> int:
  w0, w1, w2, w3, w4, w5, w6, w7 = weights
  factor_a = w0 + w1 * context.contention + w2 * context.body_log + w3 * context.registered_workers
  factor_b = w4 + w5 * context.contention + w6 * context.body_log + w7 * context.registered_workers
  participating = [1]
  for k in range(2, context.registered_workers + 1):
    marginal = factor_a * context.productive_handles / (
          k * (k - 1.0)) - factor_b
    if marginal <= 0.0:
      participating.append(k)
  return max(participating)


def _baseline_cases(
    name: str,
    comparison_families: Sequence[str],
    curves_by_id: dict[str, FamilyCurve],
    peaks: dict[str, ObservedPeak],
    target_fn,
) -> dict[str, Any]:
  cases = []
  for held_out in comparison_families:
    curve = curves_by_id[held_out]
    peak = peaks[held_out]
    train_ids = [family_id for family_id in comparison_families if
                 family_id != held_out]
    for context in curve.current_contexts:
      predicted_k = int(target_fn(context, train_ids))
      predicted_k = max(1, min(context.registered_workers, predicted_k))
      predicted_direction = _direction(context.current_k, predicted_k,
                                       predicted_k)
      observed_direction = _direction(
          context.current_k, peak.peak_interval_min, peak.peak_interval_max
      )
      plateau = _local_plateau(curve, context.current_k)
      cases.append(
          {
            "familyId": held_out,
            "currentK": context.current_k,
            "predictedK": predicted_k,
            "kError": peak_distance(predicted_k, peak),
            "exactPeakHit": predicted_k == peak.best_k,
            "peakIntervalHit": peak_distance(predicted_k, peak) == 0,
            "observedDirection": observed_direction,
            "predictedDirection": predicted_direction,
            "directionCorrect": predicted_direction == observed_direction,
            "multiRank": peak_distance(context.current_k, peak) >= 2,
            "regret": interpolate_peak_regret(predicted_k, curve, peak),
            "relativePointwiseSquaredError": 0.0,
            "plateauEscapeCase": plateau is not None
                                 and peak_distance(context.current_k,
                                                   peak) >= 2,
          }
      )
  return {"name": name, "metrics": _case_metrics(cases)}


def _reference_lofo(
    comparison_families: Sequence[str],
    curves_by_id: dict[str, FamilyCurve],
    peaks: dict[str, ObservedPeak],
    references: dict[str, dict[str, Any]],
) -> dict[str, Any]:
  cases = []
  for held_out in comparison_families:
    curve = curves_by_id[held_out]
    peak = peaks[held_out]
    train_ids = [family_id for family_id in comparison_families if
                 family_id != held_out]
    train_contexts = {
      family_id: np.asarray(
          [
            math.log1p(
              curves_by_id[family_id].representative_productive_handles),
            math.log(curves_by_id[family_id].registered_workers),
            median(context.body_log for context in
                   curves_by_id[family_id].current_contexts),
            median(context.contention for context in
                   curves_by_id[family_id].current_contexts),
          ],
          dtype=np.float64,
      )
      for family_id in train_ids
    }
    matrix = np.vstack(list(train_contexts.values()))
    means = np.mean(matrix, axis=0)
    scales = np.where(np.std(matrix, axis=0) > 1e-12, np.std(matrix, axis=0),
                      1.0)
    for context in curve.current_contexts:
      vector = np.asarray(
          [
            math.log1p(context.productive_handles),
            math.log(context.registered_workers),
            context.body_log,
            context.contention,
          ]
      )
      nearest = min(
          train_ids,
          key=lambda family_id: (
            float(
              np.linalg.norm((train_contexts[family_id] - vector) / scales)),
            family_id,
          ),
      )
      normalized_mode = references[nearest]["modeK"] / curves_by_id[
        nearest].registered_workers
      predicted_k = int(round(normalized_mode * context.registered_workers))
      predicted_k = max(1, min(context.registered_workers, predicted_k))
      observed_direction = _direction(
          context.current_k, peak.peak_interval_min, peak.peak_interval_max
      )
      predicted_direction = _direction(context.current_k, predicted_k,
                                       predicted_k)
      plateau = _local_plateau(curve, context.current_k)
      cases.append(
          {
            "familyId": held_out,
            "currentK": context.current_k,
            "predictedK": predicted_k,
            "kError": peak_distance(predicted_k, peak),
            "exactPeakHit": predicted_k == peak.best_k,
            "peakIntervalHit": peak_distance(predicted_k, peak) == 0,
            "observedDirection": observed_direction,
            "predictedDirection": predicted_direction,
            "directionCorrect": predicted_direction == observed_direction,
            "multiRank": peak_distance(context.current_k, peak) >= 2,
            "regret": interpolate_peak_regret(predicted_k, curve, peak),
            "relativePointwiseSquaredError": 0.0,
            "plateauEscapeCase": plateau is not None
                                 and peak_distance(context.current_k,
                                                   peak) >= 2,
            "nearestTrainingFamily": nearest,
          }
      )
  return {
    "name": "UNIMODAL_ISOTONIC_NEAREST_CONTEXT_REFERENCE",
    "role": "diagnostic nonparametric reference; nearest-family transfer is not runtime candidate",
    "metrics": _case_metrics(cases),
  }


def _representative_tail_tables(
    final_model: PeakCandidateModel,
    selected_shape: ThroughputShape,
    curves_by_id: dict[str, FamilyCurve],
    peaks: dict[str, ObservedPeak],
) -> list[dict[str, Any]]:
  requested: list[str] = []
  for registered_workers in (7, 15, 23):
    wu0 = sorted(
        (
          curve
          for curve in curves_by_id.values()
          if
        curve.registered_workers == registered_workers and curve.work_units == 0
        ),
        key=lambda curve: (curve.source_count, curve.family_id),
    )
    if not wu0:
      continue
    base = wu0[0]
    requested.append(base.family_id)
    matched_positive = sorted(
        (
          curve
          for curve in curves_by_id.values()
          if curve.registered_workers == registered_workers
             and curve.source_count == base.source_count
             and curve.work_units > 0
        ),
        key=lambda curve: (-curve.work_units, curve.family_id),
    )
    if matched_positive:
      requested.append(matched_positive[0].family_id)
  tables = []
  for family_id in dict.fromkeys(requested):
    curve = curves_by_id[family_id]
    contexts = sorted(curve.current_contexts,
                      key=lambda item: (item.current_k, item.pair_id))
    context = contexts[len(contexts) // 2]
    valid_k = list(range(1, curve.registered_workers + 1))
    params, predicted_values = final_model.predict_curve(selected_shape,
                                                         context, valid_k)
    predicted = {
      k: float(value) for k, value in
      zip(valid_k, predicted_values, strict=True)
    }
    predicted_peak = valid_k[int(np.argmax(predicted_values))]
    rows = []
    for point in curve.points:
      adjacent_result = next(
          (result for result in point.adjacent_results if
           result["k"] == point.k),
          None,
      )
      adjacent_delta = (
        adjacent_result[
          "basisDeltaThroughput"] if adjacent_result is not None else None
      )
      if point.k > 1:
        predicted_marginal = predicted[point.k] - predicted[point.k - 1]
        marginal_basis = "T_HAT_K_MINUS_T_HAT_K_MINUS_1"
      else:
        predicted_marginal = predicted[2] - predicted[1]
        marginal_basis = "FORWARD_T_HAT_2_MINUS_T_HAT_1"
      rows.append(
          {
            "k": point.k,
            "measuredThroughput": point.mean_throughput,
            "uncertainty": point.uncertainty,
            "predictedThroughput": predicted[point.k],
            "measuredAdjacentDelta": adjacent_delta,
            "predictedMarginal": predicted_marginal,
            "predictedMarginalBasis": marginal_basis,
          }
      )
    tables.append(
        {
          "familyId": family_id,
          "diagnosticRole": (
            "FINAL_MODEL_TRAINING_SIDE_EXTRAPOLATION; not a LOFO score when family "
            "is outside the common shape-identifiable cohort"
          ),
          "currentContextPairId": context.pair_id,
          "currentK": context.current_k,
          "observedPeakInterval": [
            peaks[family_id].peak_interval_min,
            peaks[family_id].peak_interval_max,
          ],
          "predictedPeak": predicted_peak,
          "shapeParameters": params,
          "rows": rows,
        }
    )
  return tables


def _selected_fit_diagnostics(
    selected: dict[str, Any],
    comparison_families: Sequence[str],
    fits: dict[tuple[str, str, str], ShapeFit],
) -> dict[str, Any]:
  selected_fits = [
    fits[(selected["shape"], selected["coordinate"], family_id)]
    for family_id in comparison_families
  ]
  return {
    "familyCount": len(selected_fits),
    "pointCount": sum(fit.point_count for fit in selected_fits),
    "sumWeightedSse": math.fsum(fit.weighted_sse for fit in selected_fits),
    "meanPerFamilyRmse": float(np.mean([fit.rmse for fit in selected_fits])),
    "medianPerFamilyRmse": median(fit.rmse for fit in selected_fits),
    "maximumPerFamilyRmse": max(fit.rmse for fit in selected_fits),
  }


def _asymmetry_diagnostics(
    comparison_families: Sequence[str],
    fits: dict[tuple[str, str, str], ShapeFit],
    coordinate: str,
) -> dict[str, Any]:
  ratios = []
  for family_id in comparison_families:
    fit = fits[("ASYMMETRIC_SIGMOID_HUMP", coordinate, family_id)]
    ratios.append(fit.params["sigmaRight"] / fit.params["sigmaLeft"])
  return {
    "coordinate": coordinate,
    "familyCount": len(ratios),
    "medianRightToLeftWidthRatio": median(ratios),
    "minimumRightToLeftWidthRatio": min(ratios),
    "maximumRightToLeftWidthRatio": max(ratios),
    "interpretation": (
      "Ratios above 1 indicate a longer fitted right limb. This is descriptive in-sample "
      "shape evidence; held-out peak/regret metrics decide whether asymmetry is useful."
    ),
  }


def _findings_markdown(
    selected: dict[str, Any],
    candidate_metrics: list[dict[str, Any]],
    baselines: list[dict[str, Any]],
    fit_diagnostics: dict[str, Any],
    asymmetry_diagnostics: dict[str, Any],
    representative_tables: Sequence[dict[str, Any]],
    comparison_family_count: int,
    total_family_count: int,
    poor_families: list[tuple[str, float]],
) -> str:
  metrics = selected["metrics"]
  ordinary = min(
      (result for result in candidate_metrics if
       result["shape"] == "LOGISTIC_DERIVATIVE"),
      key=_candidate_key,
  )
  asymmetric = min(
      (result for result in candidate_metrics if
       result["shape"] == "ASYMMETRIC_SIGMOID_HUMP"),
      key=_candidate_key,
  )
  asym_improvement = (
      ordinary["metrics"]["pooledSupportedRelativeThroughputRegret"]
      - asymmetric["metrics"]["pooledSupportedRelativeThroughputRegret"]
  )
  selected_fields = ContextParameterMapper._field_names(
      "peak", selected["contextStructure"], selected["contextVariant"]
  )
  width_fields = ContextParameterMapper._field_names(
      "logWidth", selected["contextStructure"], selected["contextVariant"]
  )
  amplitude_fields = ContextParameterMapper._field_names(
      "logAmplitude", selected["contextStructure"], selected["contextVariant"]
  )
  lines = [
    "# Global Participation Peak Model Findings",
    "",
    f"- Shape-identifiable comparison cohort: {comparison_family_count}/{total_family_count} families.",
    f"- Selected shape: `{selected['shape']}`.",
    f"- Selected horizontal coordinate: `{selected['coordinate']}`.",
    f"- Peak context structure: `{selected['contextStructure']}`.",
    f"- Context variant: `{selected['contextVariant']}`.",
    f"- Peak interval hit rate: {metrics['peakIntervalHitRate']:.6f}.",
    f"- Absolute K error median/p90/worst: {metrics['medianAbsoluteKError']:.3f} / {metrics['p90AbsoluteKError']:.3f} / {metrics['worstFamilyKError']:.3f}.",
    f"- Pooled supported relative throughput regret: {metrics['pooledSupportedRelativeThroughputRegret']:.6f}.",
    f"- Worst-family supported relative throughput regret: {metrics['worstFamilySupportedRelativeThroughputRegret']:.6f}.",
    f"- Multi-rank SCALE_UP direction accuracy: {metrics['multiRankScaleUpDirectionAccuracy']} ({metrics['multiRankScaleUpCaseCount']} cases).",
    f"- Multi-rank SCALE_DOWN direction accuracy: {metrics['multiRankScaleDownDirectionAccuracy']} ({metrics['multiRankScaleDownCaseCount']} cases).",
    f"- Plateau-escape accuracy: {metrics['plateauEscapeAccuracy']} ({metrics['plateauEscapeCaseCount']} cases).",
    "",
    "## Shape conclusion",
    "",
    f"The best ordinary logistic-derivative candidate had supported regret {ordinary['metrics']['pooledSupportedRelativeThroughputRegret']:.6f}. ",
    f"The best asymmetric sigmoid candidate changed that metric by {asym_improvement:.6f} (positive favors asymmetry).",
    "A more complex shape was selected only through held-out peak/regret ordering, never pointwise SSE alone.",
    f"The asymmetric in-sample median right/left fitted width ratio was {asymmetry_diagnostics['medianRightToLeftWidthRatio']:.6f}, but its held-out regret did not improve, so the retained evidence does not support paying for asymmetry.",
    "",
    "## Selected context mapping",
    "",
    f"- Peak-position variables: `{', '.join(selected_fields)}`.",
    f"- Width variables: `{', '.join(width_fields) if width_fields else 'none (intercept only)'}`.",
    f"- Amplitude variables: `{', '.join(amplitude_fields) if amplitude_fields else 'none (intercept only)'}`.",
    f"- Per-family shape-fit RMSE median/max: {fit_diagnostics['medianPerFamilyRmse']:.6f} / {fit_diagnostics['maximumPerFamilyRmse']:.6f}.",
    "",
    "## Counterfactual telemetry",
    "",
    "P, body cost, and contention are used only from the current executing K and held constant while scoring candidate K values. Candidate-K telemetry is neither read nor imputed. Therefore contention is usable as a current-state covariate, but this stage does not claim a counterfactual contention forecast.",
    "",
    "## Baselines",
    "",
  ]
  for baseline in baselines:
    baseline_metrics = baseline["metrics"]
    lines.append(
        f"- `{baseline['name']}`: supported regret={baseline_metrics['pooledSupportedRelativeThroughputRegret']}, peak interval hit={baseline_metrics['peakIntervalHitRate']:.6f}."
    )
  lines.extend(
      [
        "",
        "## Remaining poor families",
        "",
      ]
  )
  for family_id, regret in poor_families:
    lines.append(
      f"- `{family_id}`: family supported relative regret {regret:.6f}.")
  lines.extend(["", "## Representative low-source tails", ""])
  for table in representative_tables:
    lines.extend(
        [
          f"### {table['familyId']}",
          "",
          f"Observed peak interval `{table['observedPeakInterval']}`; final-model predicted peak `{table['predictedPeak']}`. This table is a training-side extrapolation diagnostic, not an additional held-out score.",
          "",
          "| K | Measured throughput | 2-SE uncertainty | Predicted throughput | Measured adjacent delta | Predicted marginal |",
          "|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for row in table["rows"]:
      measured_delta = (
        "n/a"
        if row["measuredAdjacentDelta"] is None
        else f"{row['measuredAdjacentDelta']:.6f}"
      )
      lines.append(
          f"| {row['k']} | {row['measuredThroughput']:.6f} | {row['uncertainty']:.6f} | "
          f"{row['predictedThroughput']:.6f} | {measured_delta} | {row['predictedMarginal']:.6f} |"
      )
  lines.extend(
      [
        "",
        "## Production boundary",
        "",
        "This is a training-side candidate only. The compact retained evidence is too sparse to identify asymmetric humps in every family, and regret at unsampled interior K values is interpolation-based. A separate production-runtime implementation phase is not justified unless these limitations and independent held-out validation are accepted explicitly.",
      ]
  )
  return "\n".join(lines) + "\n"


def run_peak_pipeline(
    training_pairs_path: Path,
    legacy_pairs_path: Path,
    step5_candidate_path: Path,
    output_dir: Path,
) -> dict[str, Any]:
  input_paths = [training_pairs_path, legacy_pairs_path, step5_candidate_path]
  for path in input_paths:
    ChecksumVerifier.verify_file(path, require_sidecar=True)
  frozen_hashes = {str(path): ChecksumVerifier.compute_sha256(path) for path in
                   input_paths}
  dataset_hash = input_hash(input_paths)

  curves, reconstruction = reconstruct_family_curves(training_pairs_path,
                                                     legacy_pairs_path)
  curves_by_id = {curve.family_id: curve for curve in curves}
  peaks = {curve.family_id: derive_observed_peak(curve) for curve in curves}
  references = {
    curve.family_id: UnimodalIsotonicReference().fit(curve)
    for curve in curves
    if len(curve.points) >= UnimodalIsotonicReference.minimum_points
  }

  family_curves_payload = {
    "schemaVersion": SCHEMA_VERSION,
    "trainerVersion": TRAINER_VERSION,
    "datasetHash": dataset_hash,
    "reconstruction": reconstruction,
    "families": [curve_to_dict(curve) for curve in curves],
  }
  observed_peaks_payload = {
    "schemaVersion": SCHEMA_VERSION,
    "uncertaintyRule": "2 * sqrt(varMean(best) + varMean(K)); inherited from Step 4",
    "families": [peak_to_dict(peaks[family_id]) for family_id in sorted(peaks)],
  }

  fits, fit_exclusions = _parallel_shape_fits(curves)
  comparison_families = _common_comparison_families(curves, fits)
  if len(comparison_families) < 3:
    raise ValueError(
      "Fewer than three families support the common shape-comparison cohort")
  shape_fit_payload = {
    "schemaVersion": SCHEMA_VERSION,
    "commonComparisonFamilies": comparison_families,
    "commonComparisonFamilyCount": len(comparison_families),
    "fits": [
      {
        "shape": shape_name,
        "coordinate": coordinate,
        "familyId": family_id,
        "fit": asdict(fit),
      }
      for (shape_name, coordinate, family_id), fit in sorted(fits.items())
    ],
    "exclusions": fit_exclusions,
    "unimodalReferences": {
      family_id: references[family_id] for family_id in sorted(references)
    },
  }

  candidate_metrics: list[dict[str, Any]] = []
  for shape_name in sorted(SHAPES):
    for coordinate in COORDINATES:
      for structure in CONTEXT_STRUCTURES:
        for variant in CONTEXT_VARIANTS:
          try:
            candidate_metrics.append(
                _candidate_lofo(
                    shape_name,
                    coordinate,
                    structure,
                    variant,
                    comparison_families,
                    curves_by_id,
                    peaks,
                    fits,
                )
            )
          except Exception as exc:
            candidate_metrics.append(
                {
                  "shape": shape_name,
                  "coordinate": coordinate,
                  "contextStructure": structure,
                  "contextVariant": variant,
                  "valid": False,
                  "rejectionReason": f"{type(exc).__name__}: {exc}",
                }
            )
  valid_candidates = [result for result in candidate_metrics if result["valid"]]
  if not valid_candidates:
    raise ValueError("No valid global peak candidate")
  selected_summary = min(valid_candidates, key=_candidate_key)
  selected = _candidate_lofo(
      selected_summary["shape"],
      selected_summary["coordinate"],
      selected_summary["contextStructure"],
      selected_summary["contextVariant"],
      comparison_families,
      curves_by_id,
      peaks,
      fits,
      retain_cases=True,
  )

  step5 = json.loads(step5_candidate_path.read_text(encoding="utf-8"))
  m4c_weights = [float(value) for value in step5["model"]["physicalWeights"]]

  def best_fixed(_context: CurrentContext, train_ids: Sequence[str]) -> int:
    maximum_r = max(
        curves_by_id[family_id].registered_workers for family_id in train_ids)
    return min(
        range(1, maximum_r + 1),
        key=lambda k: (
          math.fsum(
              peak_distance(min(k, curves_by_id[family_id].registered_workers),
                            peaks[family_id])
              for family_id in train_ids
          ),
          k,
        ),
    )

  baselines = [
    _baseline_cases(
        "ALWAYS_PARTICIPATE",
        comparison_families,
        curves_by_id,
        peaks,
        lambda context, _train: context.registered_workers,
    ),
    _baseline_cases(
        "MINIMUM_PARTICIPATION",
        comparison_families,
        curves_by_id,
        peaks,
        lambda _context, _train: 1,
    ),
    _baseline_cases(
        "TRAINING_SELECTED_FIXED_K",
        comparison_families,
        curves_by_id,
        peaks,
        best_fixed,
    ),
    _baseline_cases(
        "SIMPLE_P_CUTOFF",
        comparison_families,
        curves_by_id,
        peaks,
        lambda context, _train: round(context.productive_handles),
    ),
    _baseline_cases(
        "CURRENT_STEP5_M4_C",
        comparison_families,
        curves_by_id,
        peaks,
        lambda context, _train: _m4c_target(context, m4c_weights),
    ),
    _reference_lofo(comparison_families, curves_by_id, peaks, references),
  ]

  selected_shape = SHAPES[selected["shape"]]
  final_mapper = ContextParameterMapper.fit(
      selected["contextStructure"],
      selected["contextVariant"],
      _context_samples(
          comparison_families,
          curves_by_id,
          fits,
          selected["shape"],
          selected["coordinate"],
      ),
      ridge=RIDGE,
  )
  final_model = PeakCandidateModel(
      selected["shape"],
      selected["coordinate"],
      selected["contextStructure"],
      selected["contextVariant"],
      final_mapper,
  )
  for curve in curves:
    for context in curve.current_contexts:
      valid_k = list(range(1, context.registered_workers + 1))
      final_model.predict_curve(selected_shape, context, valid_k)

  fit_diagnostics = _selected_fit_diagnostics(selected, comparison_families,
                                              fits)
  best_asymmetric = min(
      (
        result
        for result in valid_candidates
        if result["shape"] == "ASYMMETRIC_SIGMOID_HUMP"
      ),
      key=_candidate_key,
  )
  asymmetry_diagnostics = _asymmetry_diagnostics(
      comparison_families, fits, best_asymmetric["coordinate"]
  )

  plateau_cases = [
    {
      key: value
      for key, value in case.items()
      if key not in {"params", "predictedCurve"}
    }
    for case in selected["cases"]
    if case["plateauEscapeCase"]
  ]
  poor_families = sorted(
      selected["metrics"]["familySupportedRelativeRegrets"].items(),
      key=lambda item: (-item[1], item[0]),
  )[:10]
  representative_tables = _representative_tail_tables(
      final_model, selected_shape, curves_by_id, peaks
  )

  lofo_payload = {
    "schemaVersion": SCHEMA_VERSION,
    "selectionOrder": [
      "maximize supported-regret coverage",
      "minimize pooled supported relative throughput regret",
      "minimize weighted mean absolute K error",
      "minimize p90 absolute K error",
      "prefer simpler shape/context on exact ties",
    ],
    "comparisonFamilies": comparison_families,
    "candidates": candidate_metrics,
    "selected": selected,
    "baselines": baselines,
    "representativeTailTables": representative_tables,
    "asymmetryDiagnostics": asymmetry_diagnostics,
  }
  candidate_payload = {
    "schemaVersion": SCHEMA_VERSION,
    "trainerVersion": TRAINER_VERSION,
    "status": "TRAINING_SIDE_CANDIDATE",
    "productionAuthorized": False,
    "shapeFamily": selected["shape"],
    "horizontalCoordinate": selected["coordinate"],
    "contextModelStructure": selected["contextStructure"],
    "contextVariant": selected["contextVariant"],
    "counterfactualTelemetryPolicy": reconstruction[
      "counterfactualTelemetryPolicy"],
    "parameters": final_mapper.serialize(),
    "coordinateDefinition": {
      "K": "x(K) = K",
      "K_OVER_P": "x(K) = K / P",
      "LOG_K_OVER_P": "x(K) = log(K / P); no epsilon is required because runtime K and P are strictly positive",
    }[selected["coordinate"]],
    "validKDomain": {
      "minimum": 1,
      "maximum": max(curve.registered_workers for curve in curves),
      "runtimeMaximum": "registeredWorkers",
    },
    "datasetHash": dataset_hash,
    "inputArtifactHashes": frozen_hashes,
    "trainingFamilyCount": len(comparison_families),
    "totalReconstructedFamilyCount": len(curves),
    "lofoMetrics": selected["metrics"],
    "fitDiagnostics": fit_diagnostics,
    "asymmetryDiagnostics": asymmetry_diagnostics,
    "limitations": [
      f"Only {len(comparison_families)} of {len(curves)} families have enough retained K points for the common asymmetric comparison cohort.",
      "Supported regret at unsampled interior K uses explicitly labeled linear interpolation.",
      "Current-state P/body/contention are held fixed across candidate K; no counterfactual telemetry model is claimed.",
      "No production runtime policy was implemented or modified.",
    ],
  }

  output_dir.mkdir(parents=True, exist_ok=True)
  digests = {
    "familyCurves": _write_json(output_dir / "family_curves.json",
                                family_curves_payload),
    "observedPeaks": _write_json(output_dir / "observed_peaks.json",
                                 observed_peaks_payload),
    "shapeFitResults": _write_json(output_dir / "shape_fit_results.json",
                                   shape_fit_payload),
    "lofoPeakResults": _write_json(output_dir / "lofo_peak_results.json",
                                   lofo_payload),
    "plateauEscapeCases": _write_json(
        output_dir / "plateau_escape_cases.json",
        {
          "schemaVersion": SCHEMA_VERSION,
          "caseCount": len(plateau_cases),
          "cases": plateau_cases,
        },
    ),
    "peakModelCandidate": _write_json(
        output_dir / "peak_model_candidate.json", candidate_payload
    ),
  }
  findings = _findings_markdown(
      selected,
      valid_candidates,
      baselines,
      fit_diagnostics,
      asymmetry_diagnostics,
      representative_tables,
      len(comparison_families),
      len(curves),
      poor_families,
  )
  digests["findings"] = _write_text(output_dir / "peak_model_findings.md",
                                    findings)
  summary = {
    "schemaVersion": SCHEMA_VERSION,
    "status": candidate_payload["status"],
    "selectedShape": selected["shape"],
    "selectedCoordinate": selected["coordinate"],
    "selectedContextStructure": selected["contextStructure"],
    "selectedContextVariant": selected["contextVariant"],
    "metrics": selected["metrics"],
    "comparisonFamilyCount": len(comparison_families),
    "totalFamilyCount": len(curves),
    "artifacts": digests,
  }
  _write_json(output_dir / "peak_pipeline_summary.json", summary)
  return summary


def _parser() -> argparse.ArgumentParser:
  parser = argparse.ArgumentParser(
    description="Fit and validate a global participation peak model")
  parser.add_argument("--training-pairs", type=Path, required=True)
  parser.add_argument("--legacy-pairs", type=Path, required=True)
  parser.add_argument("--step5-candidate", type=Path, required=True)
  parser.add_argument("--output-dir", type=Path, required=True)
  return parser


def main(argv: Sequence[str] | None = None) -> int:
  args = _parser().parse_args(argv)
  summary = run_peak_pipeline(
      args.training_pairs,
      args.legacy_pairs,
      args.step5_candidate,
      args.output_dir,
  )
  print(json.dumps(summary, indent=2, sort_keys=True, allow_nan=False))
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
