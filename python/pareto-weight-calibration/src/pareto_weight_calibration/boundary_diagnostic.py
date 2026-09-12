"""Diagnostic analysis of family cutoff coherence and boundary stability.

The stage consumes checksum-frozen evidence and existing outer-LOFO predictions.
It does not fit a new candidate model or modify runtime policy.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import fields
import hashlib
import json
import math
from pathlib import Path
from statistics import median
from typing import Any, Iterable, Sequence

import numpy as np
from scipy.stats import pearsonr, spearmanr

from pareto_weight_calibration.action_model import (
  CACHE,
  DEFAULT,
  INDETERMINATE,
  ActionRow,
  BoundaryFit,
  FeatureScaler,
  action_loss,
  predict_boundary,
)
from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.integer_cutoff import (
  first_cache_k,
  signed_cutoff_error,
)

SCHEMA_VERSION = 1
EVALUATOR_VERSION = "family-boundary-processing-diagnostic-v1"
FEATURES = ("productiveHandles", "pRatio", "body", "contention")
COVERAGE_FEATURES = ("R", "pRatio", "body", "contention")
STATUS_ORDER = (
  "EXACT",
  "INTERVAL",
  "ONE_SIDED",
  "INDETERMINATE_ONLY",
  "CONTRADICTORY",
)
DRIFT_BUCKETS = ("<0.5", "0.5-1", "1-2", ">2")
CLASSIFICATIONS = (
  "STABLE_AND_CORRECT",
  "STABLE_BUT_WRONG",
  "TELEMETRY_DRIVEN_DRIFT",
  "CONTRADICTORY_OBSERVED_EVIDENCE",
  "INSUFFICIENT_BOUNDARY_EVIDENCE",
)

PREEXISTING_ARTIFACTS = (
  Path("experiments/pareto_action_model_training/action_training_dataset.json"),
  Path("experiments/pareto_action_model_training/action_model_outer_lofo.json"),
  Path("experiments/pareto_action_model_training/action_model_m4c_lofo.json"),
  Path(
    "experiments/pareto_integer_cutoff_evaluation/integer_cutoff_results.json"),
  Path("experiments/pareto_peak_training/family_curves.json"),
  Path("experiments/pareto_training_step5/training_pairs.tsv"),
)


def _jsonable(value: Any) -> Any:
  if isinstance(value, np.generic):
    return _jsonable(value.item())
  if isinstance(value, np.ndarray):
    return [_jsonable(item) for item in value.tolist()]
  if isinstance(value, dict):
    return {str(key): _jsonable(item) for key, item in value.items()}
  if isinstance(value, (list, tuple)):
    return [_jsonable(item) for item in value]
  if isinstance(value, Path):
    return str(value)
  if isinstance(value, float) and not math.isfinite(value):
    raise ValueError(f"non-finite artifact value: {value}")
  return value


def _canonical_json(payload: Any) -> str:
  return json.dumps(
      _jsonable(payload), indent=2, sort_keys=True, allow_nan=False
  ) + "\n"


def _write_json(path: Path, payload: Any) -> str:
  content = _canonical_json(payload)
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
  path.with_name(path.name + ".sha256").write_text(
      digest + "\n", encoding="utf-8"
  )
  return digest


def _write_text(path: Path, content: str) -> str:
  if not content.endswith("\n"):
    content += "\n"
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
  path.with_name(path.name + ".sha256").write_text(
      digest + "\n", encoding="utf-8"
  )
  return digest


def _verified_json(path: Path) -> dict[str, Any]:
  ChecksumVerifier.verify_file(path, require_sidecar=True)
  return json.loads(path.read_text(encoding="utf-8"))


def preexisting_hashes(repo_root: Path) -> dict[str, str]:
  result = {}
  for relative in PREEXISTING_ARTIFACTS:
    path = repo_root / relative
    ChecksumVerifier.verify_file(path, require_sidecar=True)
    result[str(relative)] = ChecksumVerifier.compute_sha256(path)
  return result


def _rows_from_dataset(payload: dict[str, Any]) -> list[ActionRow]:
  names = {field.name for field in fields(ActionRow)}
  rows = [ActionRow(**{name: item[name] for name in names}) for item in
          payload["rows"]]
  if len({row.pair_id for row in rows}) != len(rows):
    raise ValueError("duplicate pair id in frozen action dataset")
  return sorted(rows, key=lambda row: row.pair_id)


def _fit_from_dict(payload: dict[str, Any]) -> BoundaryFit:
  scaler = payload["scaler"]
  return BoundaryFit(
      structure=payload["structure"],
      feature_names=tuple(payload["featureNames"]),
      l2=float(payload["l2"]),
      temperature=float(payload["temperature"]),
      scaler=FeatureScaler(
          names=tuple(scaler["names"]),
          means=tuple(float(value) for value in scaler["means"]),
          scales=tuple(float(value) for value in scaler["scales"]),
      ),
      coefficients=tuple(float(value) for value in payload["coefficients"]),
      success=bool(payload["success"]),
      objective=float(payload["objective"]),
      iterations=int(payload["iterations"]),
  )


def _row_feature(row: ActionRow, name: str) -> float:
  return {
    "productiveHandles": row.productive_handles,
    "pRatio": row.p_ratio,
    "body": row.body_log,
    "contention": row.contention,
    "R": float(row.registered_workers),
  }[name]


def ordered_action_entry(row: ActionRow) -> dict[str, Any]:
  return {
    "pairId": row.pair_id,
    "K": row.current_k,
    "observedAction": row.observed_action,
    "supportedWrongActionLoss": row.supported_wrong_action_loss,
    "supportedRelativeWrongActionLoss": row.supported_relative_wrong_action_loss,
    "evidenceBasis": row.evidence_basis,
    "productiveHandles": row.productive_handles,
    "pRatio": row.p_ratio,
    "registeredWorkers": row.registered_workers,
    "body": row.body_log,
    "bodyCostNs": row.body_cost_ns,
    "contention": row.contention,
    "basisThroughputK": row.basis_throughput_k,
    "basisThroughputKMinus1": row.basis_throughput_k_minus_1,
    "basisDelta": row.basis_delta,
    "basisUncertainty": row.basis_uncertainty,
    "runtimeCommit": row.runtime_commit,
    "topologyId": row.topology_id,
    "kRunPath": row.k_run_path,
    "kRunSha256": row.k_run_sha256,
    "kMinus1RunPath": row.k_minus_1_run_path,
    "kMinus1RunSha256": row.k_minus_1_run_sha256,
  }


def derive_family_cutoff_constraint(rows: Sequence[ActionRow]) -> dict[
  str, Any]:
  """Derive the strongest integer cutoff interval from frozen actions only."""
  if not rows:
    raise ValueError("family cutoff requires at least one row")
  families = {row.family_id for row in rows}
  workers = {row.registered_workers for row in rows}
  if len(families) != 1 or len(workers) != 1:
    raise ValueError("cutoff rows must belong to one physical family")
  ordered = sorted(rows, key=lambda row: (row.current_k, row.pair_id))
  default_ks = [row.current_k for row in ordered if
                row.observed_action == DEFAULT]
  cache_ks = [row.current_k for row in ordered if row.observed_action == CACHE]
  unexpected = sorted({
    row.observed_action for row in ordered
    if row.observed_action not in {DEFAULT, CACHE, INDETERMINATE}
  })
  if unexpected:
    raise ValueError(f"unexpected frozen actions: {unexpected}")
  registered_workers = next(iter(workers))
  lower = max([2, *[current_k + 1 for current_k in default_ks]])
  upper = min([registered_workers + 1, *cache_ks])
  reversals = []
  decisive = [row for row in ordered if row.observed_action in {DEFAULT, CACHE}]
  for first, second in zip(decisive, decisive[1:]):
    if first.observed_action == CACHE and second.observed_action == DEFAULT:
      reversals.append({
        "lowerK": first.current_k,
        "lowerAction": CACHE,
        "higherK": second.current_k,
        "higherAction": DEFAULT,
        "lowerPairId": first.pair_id,
        "higherPairId": second.pair_id,
      })
  if lower > upper:
    status = "CONTRADICTORY"
    cutoff_min = cutoff_max = None
    continuous_min = continuous_max = None
  elif not decisive:
    status = "INDETERMINATE_ONLY"
    cutoff_min, cutoff_max = 2, registered_workers + 1
    continuous_min, continuous_max = 1.0, float(registered_workers)
  else:
    cutoff_min, cutoff_max = lower, upper
    continuous_min = float(lower - 1)
    continuous_max = float(min(registered_workers, upper))
    if not default_ks or not cache_ks:
      status = "ONE_SIDED"
    elif lower == upper:
      status = "EXACT"
    else:
      status = "INTERVAL"
  return {
    "familyId": next(iter(families)),
    "registeredWorkers": registered_workers,
    "status": status,
    "orderedActionSequence": [ordered_action_entry(row) for row in ordered],
    "observedFirstCacheKMin": cutoff_min,
    "observedFirstCacheKMax": cutoff_max,
    "continuousMuMin": continuous_min,
    "continuousMuMax": continuous_max,
    "continuousMuIntervalSemantics": (
      "lower inclusive; upper exclusive except registered-worker domain maximum"
    ),
    "defaultKs": default_ks,
    "cacheKs": cache_ks,
    "indeterminateKs": [
      row.current_k for row in ordered
      if row.observed_action == INDETERMINATE
    ],
    "reversalCount": len(reversals),
    "reversals": reversals,
  }


def _trend(values: Sequence[float], tolerance: float = 1e-12) -> str:
  deltas = [second - first for first, second in zip(values, values[1:])]
  if not deltas or all(abs(delta) <= tolerance for delta in deltas):
    return "CONSTANT"
  if all(delta >= -tolerance for delta in deltas):
    return "NONDECREASING"
  if all(delta <= tolerance for delta in deltas):
    return "NONINCREASING"
  return "NONMONOTONIC"


def _correlation(x: Sequence[float], y: Sequence[float],
    method: str) -> float | None:
  if len(x) < 2 or np.ptp(x) <= 1e-12 or np.ptp(y) <= 1e-12:
    return None
  result = pearsonr(x, y) if method == "pearson" else spearmanr(x, y)
  return float(result.statistic)


def summarize_feature_by_k(rows: Sequence[ActionRow], name: str) -> dict[
  str, Any]:
  ordered = sorted(rows, key=lambda row: (row.current_k, row.pair_id))
  ks = [float(row.current_k) for row in ordered]
  values = [_row_feature(row, name) for row in ordered]
  mean = float(np.mean(values))
  std = float(np.std(values))
  return {
    "min": min(values),
    "max": max(values),
    "range": max(values) - min(values),
    "mean": mean,
    "coefficientOfVariation": std / abs(mean) if abs(mean) > 1e-12 else None,
    "monotonicTrendWithK": _trend(values),
    "pearsonCorrelationWithK": _correlation(ks, values, "pearson"),
    "spearmanCorrelationWithK": _correlation(ks, values, "spearman"),
    "valuesByK": [
      {"K": row.current_k, "pairId": row.pair_id, "value": value}
      for row, value in zip(ordered, values, strict=True)
    ],
  }


def predict_family_mu(
    fit: BoundaryFit, rows: Sequence[ActionRow]
) -> list[dict[str, Any]]:
  """Recompute mu independently for every row's actual current-K telemetry."""
  return sorted(
      predict_boundary(fit, rows),
      key=lambda item: (item["currentK"], item["pairId"])
  )


def boundary_drift(predictions: Sequence[dict[str, Any]]) -> dict[str, Any]:
  if not predictions:
    raise ValueError("boundary drift requires predictions")
  ordered = sorted(predictions,
                   key=lambda item: (item["currentK"], item["pairId"]))
  mus = [float(item["mu"]) for item in ordered]
  cutoffs = [first_cache_k(mu) for mu in mus]
  drift = max(mus) - min(mus)
  if drift < 0.5:
    bucket = "<0.5"
  elif drift < 1.0:
    bucket = "0.5-1"
  elif drift <= 2.0:
    bucket = "1-2"
  else:
    bucket = ">2"
  span = max(cutoffs) - min(cutoffs)
  decisive_actions = [
    CACHE if item["currentK"] >= cutoff else DEFAULT
    for item, cutoff in zip(ordered, cutoffs, strict=True)
  ]
  reverses = any(
      first == CACHE and second == DEFAULT
      for first, second in zip(decisive_actions, decisive_actions[1:])
  )
  return {
    "minPredictedMu": min(mus),
    "maxPredictedMu": max(mus),
    "predictedMuRange": drift,
    "predictedMuDriftBucket": bucket,
    "predictedFirstCacheKSequence": [
      {
        "K": item["currentK"],
        "pairId": item["pairId"],
        "predictedMu": mu,
        "predictedFirstCacheK": cutoff,
        "predictedAction": action,
      }
      for item, mu, cutoff, action in zip(
          ordered, mus, cutoffs, decisive_actions, strict=True
      )
    ],
    "integerCutoffMin": min(cutoffs),
    "integerCutoffMax": max(cutoffs),
    "integerCutoffSpan": span,
    "integerCutoffStability": (
      "STABLE" if span == 0 else "CHANGES_BY_1" if span == 1
      else "CHANGES_BY_2_PLUS"
    ),
    "reversesAroundCurrentK": reverses,
  }


def compare_cutoff_stability(
    constraint: dict[str, Any],
    drift: dict[str, Any],
    rows: Sequence[ActionRow],
    predictions: Sequence[dict[str, Any]],
) -> dict[str, Any]:
  status = constraint["status"]
  sequence = drift["predictedFirstCacheKSequence"]
  if status == "CONTRADICTORY":
    category = "CONTRADICTORY_OBSERVED_EVIDENCE"
    errors: list[int] = []
  elif status == "INDETERMINATE_ONLY":
    category = "INSUFFICIENT_BOUNDARY_EVIDENCE"
    errors = []
  else:
    lower = int(constraint["observedFirstCacheKMin"])
    upper = int(constraint["observedFirstCacheKMax"])
    errors = [
      signed_cutoff_error(int(item["predictedFirstCacheK"]), lower, upper)
      for item in sequence
    ]
    stable = drift["integerCutoffSpan"] == 0
    if stable and all(error == 0 for error in errors):
      category = "STABLE_AND_CORRECT"
    elif stable:
      category = "STABLE_BUT_WRONG"
    else:
      category = "TELEMETRY_DRIVEN_DRIFT"
  pred_by_pair = {item["pairId"]: item for item in predictions}
  modal_cutoff = Counter(
      item["predictedFirstCacheK"] for item in sequence
  ).most_common(1)[0][0]
  errors_at_nonmodal = 0
  errors_at_modal = 0
  for row in rows:
    prediction = pred_by_pair[row.pair_id]
    if row.decisive and prediction["action"] != row.observed_action:
      cutoff = first_cache_k(float(prediction["mu"]))
      if cutoff == modal_cutoff:
        errors_at_modal += 1
      else:
        errors_at_nonmodal += 1
  lower = constraint["observedFirstCacheKMin"]
  upper = constraint["observedFirstCacheKMax"]
  return {
    "familyId": constraint["familyId"],
    "classification": category,
    "observedStatus": status,
    "observedFirstCacheKMin": lower,
    "observedFirstCacheKMax": upper,
    "predictedCutoffSequence": sequence,
    "signedCutoffErrors": errors,
    "minimumSignedCutoffError": min(errors) if errors else None,
    "maximumSignedCutoffError": max(errors) if errors else None,
    "medianSignedCutoffError": float(median(errors)) if errors else None,
    "everInsideObservedInterval": any(error == 0 for error in errors),
    "crossesFromBelowToAboveObservedInterval": (
        any(error < 0 for error in errors) and any(
        error > 0 for error in errors)
    ),
    "actionErrorCountAtModalPredictedCutoff": errors_at_modal,
    "actionErrorCountAtNonmodalPredictedCutoff": errors_at_nonmodal,
  }


def _coverage_vector(row: ActionRow) -> np.ndarray:
  return np.asarray([
    float(row.registered_workers), row.p_ratio, row.body_log, row.contention
  ], dtype=np.float64)


def fit_coverage_scaler(
    rows: Sequence[ActionRow], held_family: str
) -> dict[str, Any]:
  """Fit equal-family coverage scaling after removing the outer holdout."""
  train = [row for row in rows if row.family_id != held_family]
  if not train or any(row.family_id == held_family for row in train):
    raise ValueError("coverage scaler received an invalid outer split")
  by_family: dict[str, list[ActionRow]] = defaultdict(list)
  for row in train:
    by_family[row.family_id].append(row)
  weighted_vectors = []
  weights = []
  for family in sorted(by_family):
    family_rows = by_family[family]
    for row in family_rows:
      weighted_vectors.append(_coverage_vector(row))
      weights.append(1.0 / len(by_family) / len(family_rows))
  matrix = np.asarray(weighted_vectors)
  weight_array = np.asarray(weights)
  means = np.sum(matrix * weight_array[:, None], axis=0)
  variance = np.sum(weight_array[:, None] * (matrix - means) ** 2, axis=0)
  scales = np.where(np.sqrt(variance) > 1e-12, np.sqrt(variance), 1.0)
  return {
    "featureNames": list(COVERAGE_FEATURES),
    "means": means,
    "scales": scales,
    "trainingFamilyIds": sorted(by_family),
    "trainingFamilyCount": len(by_family),
    "trainingPairIdsHash": hashlib.sha256(
        "\n".join(sorted(row.pair_id for row in train)).encode()
    ).hexdigest(),
    "weighting": "equal physical-family weight; equal row share within family",
  }


def _nearest_other_family_distances(
    family_rows: Sequence[ActionRow],
    training_by_family: dict[str, list[ActionRow]],
    means: np.ndarray,
    scales: np.ndarray,
) -> list[dict[str, Any]]:
  result = []
  for row in sorted(family_rows,
                    key=lambda item: (item.current_k, item.pair_id)):
    normalized = (_coverage_vector(row) - means) / scales
    best: tuple[float, str, str] | None = None
    for family, candidates in training_by_family.items():
      if family == row.family_id:
        continue
      for candidate in candidates:
        other = (_coverage_vector(candidate) - means) / scales
        item = (
          float(np.linalg.norm(normalized - other)), family, candidate.pair_id
        )
        if best is None or item < best:
          best = item
    if best is None:
      raise ValueError("coverage distance requires another training family")
    result.append({
      "pairId": row.pair_id,
      "K": row.current_k,
      "nearestDistance": best[0],
      "nearestFamilyId": best[1],
      "nearestPairId": best[2],
    })
  return result


def lofo_coverage(
    rows: Sequence[ActionRow], held_family: str
) -> dict[str, Any]:
  held = [row for row in rows if row.family_id == held_family]
  train = [row for row in rows if row.family_id != held_family]
  if not held:
    raise ValueError("unknown held family")
  scaler = fit_coverage_scaler(rows, held_family)
  means = np.asarray(scaler["means"])
  scales = np.asarray(scaler["scales"])
  training_by_family: dict[str, list[ActionRow]] = defaultdict(list)
  for row in train:
    training_by_family[row.family_id].append(row)
  reference = []
  for family in sorted(training_by_family):
    distances = _nearest_other_family_distances(
        training_by_family[family], training_by_family, means, scales
    )
    reference.append(min(item["nearestDistance"] for item in distances))
  p90 = float(np.quantile(reference, 0.9))
  maximum = max(reference)
  row_distances = _nearest_other_family_distances(
      held, training_by_family, means, scales
  )
  train_matrix = np.asarray([_coverage_vector(row) for row in train])
  train_min = np.min(train_matrix, axis=0)
  train_max = np.max(train_matrix, axis=0)
  held_by_pair = {row.pair_id: row for row in held}
  for item in row_distances:
    vector = _coverage_vector(held_by_pair[item["pairId"]])
    outside = [
      name for name, value, low, high in zip(
          COVERAGE_FEATURES, vector, train_min, train_max, strict=True
      ) if value < low - 1e-12 or value > high + 1e-12
    ]
    distance = item["nearestDistance"]
    if distance > maximum + 1e-12:
      category = "CLEAR_EXTRAPOLATION"
    elif distance > p90 + 1e-12 or outside:
      category = "EDGE_OF_DOMAIN"
    else:
      category = "INTERPOLATION"
    item["outsideTrainingCoordinateRange"] = outside
    item["coverageCategory"] = category
  rank = {"INTERPOLATION": 0, "EDGE_OF_DOMAIN": 1, "CLEAR_EXTRAPOLATION": 2}
  family_category = max(
      (item["coverageCategory"] for item in row_distances), key=rank.get
  )
  distances = [item["nearestDistance"] for item in row_distances]
  return {
    "familyId": held_family,
    "coverageCategory": family_category,
    "minimumNearestDistance": min(distances),
    "medianNearestDistance": float(median(distances)),
    "maximumNearestDistance": max(distances),
    "trainingNearestFamilyDistanceP90": p90,
    "trainingNearestFamilyDistanceMax": maximum,
    "rowCoverage": row_distances,
    "scaler": {
      **scaler,
      "means": list(means),
      "scales": list(scales),
    },
  }


def _constant_value(rows: Sequence[ActionRow], name: str) -> Any:
  values = {getattr(row, name) for row in rows}
  if len(values) != 1:
    raise ValueError(f"{name} is not family-constant")
  return next(iter(values))


def family_level_representation(
    rows_by_family: dict[str, list[ActionRow]],
    constraints: dict[str, dict[str, Any]],
) -> dict[str, Any]:
  families = []
  for family, rows in sorted(rows_by_family.items()):
    families.append({
      "familyId": family,
      "cutoffConstraint": {
        key: constraints[family][key] for key in (
          "status", "observedFirstCacheKMin", "observedFirstCacheKMax",
          "continuousMuMin", "continuousMuMax",
        )
      },
      "staticFeatures": {
        "registeredWorkers": _constant_value(rows, "registered_workers"),
        "sourceCount": _constant_value(rows, "source_count"),
        "workUnits": _constant_value(rows, "work_units"),
        "runtimeCommit": _constant_value(rows, "runtime_commit"),
        "topologyId": _constant_value(rows, "topology_id"),
      },
      "kDependentTelemetry": {
        name: summarize_feature_by_k(rows, name) for name in FEATURES
      },
      "kDependentTelemetryAveragedIntoStaticFeatures": False,
      "rowCount": len(rows),
      "decisiveRowCount": sum(row.decisive for row in rows),
    })
  status_counts = Counter(
      item["cutoffConstraint"]["status"] for item in families)
  constrained = sum(
      status_counts[status] for status in ("EXACT", "INTERVAL", "ONE_SIDED")
  )
  return {
    "physicalFamilyCount": len(families),
    "rowCount": sum(len(rows) for rows in rows_by_family.values()),
    "decisiveRowCount": sum(
        row.decisive for rows in rows_by_family.values() for row in rows),
    "independentConstrainedFamilyCount": constrained,
    "twoSidedEvaluableFamilyCount": status_counts["EXACT"] + status_counts[
      "INTERVAL"],
    "decisiveRowsPerIndependentConstraint": (
      sum(row.decisive for rows in rows_by_family.values() for row in rows)
      / constrained if constrained else None
    ),
    "statisticalObject": "one physical family with one frozen cutoff interval",
    "families": families,
  }


def _source_regime(row: ActionRow) -> str:
  if row.source_count == 1:
    return "ONE_SOURCE"
  if row.source_count >= row.registered_workers - 1:
    return "NEAR_PLENTIFUL"
  return "LOW_SOURCE"


def _body_bucket(row: ActionRow) -> str:
  if row.work_units == 0:
    return "WU0"
  if row.work_units <= 112:
    return "LOW_POSITIVE_WU1_112"
  if row.work_units < 768:
    return "MEDIUM_WU113_767"
  return "HIGH_WU768_PLUS"


def _ratio_bucket(value: float) -> str:
  if value <= 0.25:
    return "(0,0.25]"
  if value <= 0.5:
    return "(0.25,0.5]"
  if value <= 0.75:
    return "(0.5,0.75]"
  return "(0.75,1+]"


def _group_residuals(records: Sequence[dict[str, Any]], field: str) -> dict[
  str, Any]:
  groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
  for record in records:
    groups[str(record[field])].append(record)
  result = {}
  for value, items in sorted(groups.items()):
    errors = [int(item["signedCutoffError"]) for item in items]
    signs = {int(math.copysign(1, error)) for error in errors if error != 0}
    result[value] = {
      "familyCount": len(items),
      "meanSignedCutoffError": float(np.mean(errors)),
      "medianSignedCutoffError": float(median(errors)),
      "sameNonzeroSignAcrossMultipleFamilies": len(items) >= 2 and len(
        signs) == 1,
      "families": [item["familyId"] for item in items],
    }
  return result


def residual_structure(
    rows_by_family: dict[str, list[ActionRow]],
    comparisons: dict[str, dict[str, Any]],
) -> dict[str, Any]:
  records = []
  for family, comparison in sorted(comparisons.items()):
    if comparison["classification"] != "STABLE_BUT_WRONG":
      continue
    rows = sorted(rows_by_family[family],
                  key=lambda row: (row.current_k, row.pair_id))
    lower = comparison["observedFirstCacheKMin"]
    upper = comparison["observedFirstCacheKMax"]
    midpoint = (float(lower) + float(upper)) / 2.0
    representative = min(
        rows, key=lambda row: (abs(row.current_k - midpoint), row.current_k,
                               row.pair_id)
    )
    error = int(comparison["signedCutoffErrors"][0])
    records.append({
      "familyId": family,
      "signedCutoffError": error,
      "registeredWorkers": representative.registered_workers,
      "productiveHandles": representative.productive_handles,
      "pRatio": representative.p_ratio,
      "sourceDeficit": 1.0 - representative.p_ratio,
      "body": representative.body_log,
      "contention": representative.contention,
      "sourceRegime": _source_regime(representative),
      "bodyBucket": _body_bucket(representative),
      "pRatioBucket": _ratio_bucket(representative.p_ratio),
      "representativeTelemetryRule": "sampled K nearest observed cutoff-interval midpoint",
      "representativePairId": representative.pair_id,
    })
  correlations = {}
  errors = [float(item["signedCutoffError"]) for item in records]
  for field in (
      "registeredWorkers", "productiveHandles", "pRatio", "sourceDeficit",
      "body", "contention",
  ):
    values = [float(item[field]) for item in records]
    correlations[field] = {
      "pearson": _correlation(values, errors, "pearson"),
      "spearman": _correlation(values, errors, "spearman"),
    }
  grouped = {
    field: _group_residuals(records, field) for field in (
      "registeredWorkers", "sourceRegime", "bodyBucket", "pRatioBucket"
    )
  }
  repeated_patterns = []
  for field, groups in grouped.items():
    for value, metrics in groups.items():
      if metrics["sameNonzeroSignAcrossMultipleFamilies"]:
        repeated_patterns.append(
            {"coordinate": field, "value": value, **metrics})
  return {
    "stableButWrongFamilyCount": len(records),
    "records": records,
    "correlations": correlations,
    "groupedTables": grouped,
    "repeatedMultiFamilyPatterns": repeated_patterns,
    "missingGeometryEvidence": bool(repeated_patterns),
  }


def _false_default_diagnostics(
    rows_by_family: dict[str, list[ActionRow]],
    predictions_by_family: dict[str, list[dict[str, Any]]],
    constraints: dict[str, dict[str, Any]],
    comparisons: dict[str, dict[str, Any]],
    coverage: dict[str, dict[str, Any]],
) -> dict[str, Any]:
  result = []
  total_supported = 0.0
  total_influence = math.fsum(
      row.influence_weight for rows in rows_by_family.values() for row in rows
  )
  for family, rows in sorted(rows_by_family.items()):
    predictions = {item["pairId"]: item for item in
                   predictions_by_family[family]}
    false_default_rows = [
      row for row in rows
      if action_loss(row, predictions[row.pair_id]["action"])["wrongType"]
         == "FALSE_DEFAULT"
    ]
    if not false_default_rows:
      continue
    family_supported_numerator = math.fsum(
        row.influence_weight * row.supported_relative_wrong_action_loss
        for row in false_default_rows
    )
    family_supported = family_supported_numerator / total_influence
    total_supported += family_supported
    comparison = comparisons[family]
    mechanisms = []
    if comparison["classification"] == "CONTRADICTORY_OBSERVED_EVIDENCE":
      mechanisms.append("CONTRADICTORY_EVIDENCE")
    if comparison["classification"] == "TELEMETRY_DRIVEN_DRIFT":
      mechanisms.append("WITHIN_FAMILY_TELEMETRY_DRIFT")
    if comparison["classification"] == "STABLE_BUT_WRONG":
      mechanisms.append("STABLE_MODEL_BIAS")
    if coverage[family]["coverageCategory"] == "CLEAR_EXTRAPOLATION":
      mechanisms.append("SPARSE_COVERAGE_EXTRAPOLATION")
    if not mechanisms:
      mechanisms.append("INSUFFICIENT_OR_MIXED_DIAGNOSTIC_EVIDENCE")
    row_coverage = {
      item["pairId"]: item for item in coverage[family]["rowCoverage"]
    }
    details = []
    for row in sorted(rows, key=lambda item: (item.current_k, item.pair_id)):
      prediction = predictions[row.pair_id]
      cutoff = first_cache_k(float(prediction["mu"]))
      constraint = constraints[family]
      error = (
        signed_cutoff_error(
            cutoff,
            int(constraint["observedFirstCacheKMin"]),
            int(constraint["observedFirstCacheKMax"]),
        ) if constraint["status"] not in {
          "CONTRADICTORY", "INDETERMINATE_ONLY"
        } else None
      )
      details.append({
        "pairId": row.pair_id,
        "K": row.current_k,
        "observedAction": row.observed_action,
        "predictedAction": prediction["action"],
        "observedFirstCacheKMin": constraint["observedFirstCacheKMin"],
        "observedFirstCacheKMax": constraint["observedFirstCacheKMax"],
        "predictedMu": prediction["mu"],
        "predictedFirstCacheK": cutoff,
        "signedCutoffError": error,
        "productiveHandles": row.productive_handles,
        "pRatio": row.p_ratio,
        "body": row.body_log,
        "contention": row.contention,
        "supportedWrongActionLoss": row.supported_wrong_action_loss,
        "supportedRelativeWrongActionLoss": row.supported_relative_wrong_action_loss,
        "isFalseDefault": row in false_default_rows,
        "coverage": row_coverage[row.pair_id],
      })
    result.append({
      "familyId": family,
      "classification": comparison["classification"],
      "highlightedMechanisms": mechanisms,
      "supportedRelativeRegretContribution": family_supported,
      "coverageCategory": coverage[family]["coverageCategory"],
      "rows": details,
    })
  result.sort(key=lambda item: (-item["supportedRelativeRegretContribution"],
                                item["familyId"]))
  return {
    "familyCount": len(result),
    "totalSupportedRelativeRegret": total_supported,
    "families": result,
  }


def _coverage_counts(rows_by_family: dict[str, list[ActionRow]]) -> dict[
  str, Any]:
  families = [
    min(rows, key=lambda row: (row.current_k, row.pair_id))
    for _, rows in sorted(rows_by_family.items())
  ]

  def counts(key):
    return dict(sorted(Counter(key(row) for row in families).items(),
                       key=lambda item: str(item[0])))

  return {
    "registeredWorkers": counts(lambda row: str(row.registered_workers)),
    "sourceRegime": counts(_source_regime),
    "bodyBucket": counts(_body_bucket),
    "productiveHandleRatioBucketAtLowestSampledK": counts(
        lambda row: _ratio_bucket(row.p_ratio)
    ),
  }


def _classification_summary(
    comparisons: dict[str, dict[str, Any]],
    costly: dict[str, Any],
    residuals: dict[str, Any],
    coverage: dict[str, dict[str, Any]],
) -> dict[str, Any]:
  costly_families = costly["families"]
  total = math.fsum(
      item["supportedRelativeRegretContribution"] for item in costly_families)
  by_mechanism = defaultdict(float)
  for item in costly_families:
    for mechanism in item["highlightedMechanisms"]:
      by_mechanism[mechanism] += item["supportedRelativeRegretContribution"]
  high_regret = costly_families[:max(1, min(5, len(costly_families)))]
  high_coverage = Counter(item["coverageCategory"] for item in high_regret)
  supported = []
  drift_share = by_mechanism[
                  "WITHIN_FAMILY_TELEMETRY_DRIFT"] / total if total else 0.0
  stable_share = by_mechanism["STABLE_MODEL_BIAS"] / total if total else 0.0
  sparse_share = math.fsum(
      item["supportedRelativeRegretContribution"] for item in costly_families
      if item["coverageCategory"] != "INTERPOLATION"
  ) / total if total else 0.0
  drift_families = [
    item for item in costly_families
    if "WITHIN_FAMILY_TELEMETRY_DRIFT" in item["highlightedMechanisms"]
  ]
  contradictory_families = [
    item for item in costly_families
    if "CONTRADICTORY_EVIDENCE" in item["highlightedMechanisms"]
  ]
  if len(drift_families) >= 2:
    supported.append("PROCESSING_REPRESENTATION")
  if residuals["missingGeometryEvidence"]:
    supported.append("FEATURE_GEOMETRY")
  if sparse_share >= 0.5:
    supported.append("DATA_COVERAGE")
  contradictory_or_insufficient = sum(
      comparison["classification"] in {
        "CONTRADICTORY_OBSERVED_EVIDENCE", "INSUFFICIENT_BOUNDARY_EVIDENCE"
      } for comparison in comparisons.values()
  )
  if (
      contradictory_or_insufficient > len(comparisons) / 2
      or len(contradictory_families) >= 2
  ):
    supported.append("EVIDENCE_QUALITY")
  if not supported:
    supported.append("UNRESOLVED_ISOLATED_STABLE_BIAS")
  final = "MIXED" if len(supported) > 1 else supported[0]
  if "DATA_COVERAGE" in supported:
    more_data = "YES_TARGETED_ONLY"
  elif "EVIDENCE_QUALITY" in supported:
    more_data = "NOT_YET_AUDIT_FROZEN_CONTRADICTIONS_FIRST"
  else:
    more_data = "NO_BEFORE_PROCESSING_OR_GEOMETRY_DIAGNOSIS"
  missing_regions = [
    {
      "familyId": item["familyId"],
      "coverageCategory": item["coverageCategory"],
      "supportedRelativeRegretContribution": item[
        "supportedRelativeRegretContribution"],
      "rows": [
        {
          "K": row["K"],
          "pRatio": row["pRatio"],
          "body": row["body"],
          "contention": row["contention"],
          "coverageCategory": row["coverage"]["coverageCategory"],
          "outsideTrainingCoordinateRange": row["coverage"][
            "outsideTrainingCoordinateRange"],
        }
        for row in item["rows"] if row["isFalseDefault"]
      ],
    }
    for item in costly_families if item["coverageCategory"] != "INTERPOLATION"
  ]
  return {
    "classification": final,
    "materiallySupportedMechanisms": supported,
    "costlyFalseDefaultRegretShares": {
      "telemetryDrivenDrift": drift_share,
      "stableModelBias": stable_share,
      "sparseOrEdgeCoverage": sparse_share,
    },
    "costlyTelemetryDriftFamilyCount": len(drift_families),
    "costlyContradictoryFamilyCount": len(contradictory_families),
    "dominantStableBiasIsolatedWithoutMultiFamilyPattern": (
        stable_share >= 0.5 and not residuals["missingGeometryEvidence"]
    ),
    "featureGeometryPatternSupported": residuals["missingGeometryEvidence"],
    "highRegretCoverageCategoryCounts": dict(sorted(high_coverage.items())),
    "moreBenchmarkDataJustified": more_data,
    "targetedMissingRegionsIfDataIsCollected": missing_regions,
    "frozenEvidenceFamiliesToAuditBeforeAnyRerun": [
      item["familyId"] for item in contradictory_families
    ],
    "rules": {
      "processingRepresentation": "at least two costly false-DEFAULT families have telemetry-driven cutoff drift",
      "featureGeometry": "a same-sign signed-cutoff residual repeats across multiple physical families",
      "dataCoverage": "at least 50% is in edge or extrapolation families",
      "evidenceQuality": "more than half of all families are contradictory/insufficient, or at least two costly false-DEFAULT families are contradictory",
    },
  }


def _findings(summary: dict[str, Any]) -> str:
  statuses = summary["cutoffStatusCounts"]
  drift = summary["predictedMuDriftBuckets"]
  mechanism = summary["decision"]
  representation = summary["familyLevelRepresentation"]
  return "\n".join([
    "# Family boundary processing diagnostic",
    "",
    "No benchmarks were rerun, no model was retrained, no frozen evidence or prior artifact was modified, and no production Java was changed.",
    "",
    "## Evidence inventory",
    "",
    f"- Cutoff status counts: exact={statuses['EXACT']}, interval={statuses['INTERVAL']}, one-sided={statuses['ONE_SIDED']}, indeterminate-only={statuses['INDETERMINATE_ONLY']}, contradictory={statuses['CONTRADICTORY']}.",
    f"- Predicted-mu drift counts: <0.5={drift['<0.5']}, 0.5-1={drift['0.5-1']}, 1-2={drift['1-2']}, >2={drift['>2']}.",
    f"- Decisive adjacent rows / independent constrained families: {representation['decisiveRowCount']} / {representation['independentConstrainedFamilyCount']}.",
    f"- Two-sided evaluable cutoff families: {representation['twoSidedEvaluableFamilyCount']}.",
    "",
    "## Remaining failure",
    "",
    f"- Diagnostic classification: `{mechanism['classification']}`.",
    f"- Materially supported mechanisms: {mechanism['materiallySupportedMechanisms']}.",
    f"- Costly false-DEFAULT regret shares: {mechanism['costlyFalseDefaultRegretShares']}.",
    f"- High-regret coverage categories: {mechanism['highRegretCoverageCategoryCounts']}.",
    f"- Costly telemetry-drift / contradictory families: {mechanism['costlyTelemetryDriftFamilyCount']} / {mechanism['costlyContradictoryFamilyCount']}.",
    f"- Repeated multi-family signed-residual pattern: {mechanism['featureGeometryPatternSupported']}.",
    f"- Dominant stable bias is isolated rather than repeated: {mechanism['dominantStableBiasIsolatedWithoutMultiFamilyPattern']}.",
    f"- More benchmark data justified now: `{mechanism['moreBenchmarkDataJustified']}`.",
    "",
    "## Interpretation",
    "",
    "Family cutoff intervals are derived only from frozen DEFAULT/CACHE/INDETERMINATE actions; raw throughput signs never create labels. K-dependent P, P/R, body, and contention remain per-K telemetry and are not averaged into static family features.",
    "",
    "Coverage distances use scaler means and scales fitted after removing the held-out physical family. The candidate model is not refit or changed by this diagnostic.",
  ])


def run_boundary_diagnostic(repo_root: Path, output_dir: Path) -> dict[
  str, Any]:
  before_hashes = preexisting_hashes(repo_root)
  dataset = _verified_json(repo_root / PREEXISTING_ARTIFACTS[0])
  outer = _verified_json(repo_root / PREEXISTING_ARTIFACTS[1])
  family_curves = _verified_json(repo_root / PREEXISTING_ARTIFACTS[4])
  rows = _rows_from_dataset(dataset)
  rows_by_family: dict[str, list[ActionRow]] = defaultdict(list)
  for row in rows:
    rows_by_family[row.family_id].append(row)
  curve_families = {item["family_id"] for item in family_curves["families"]}
  if curve_families != set(rows_by_family):
    raise ValueError(
      "reconstructed curves and action dataset family inventories differ")
  outer_by_family = {fold["heldOutFamily"]: fold for fold in outer["folds"]}
  if set(outer_by_family) != set(rows_by_family):
    raise ValueError("outer LOFO and action dataset family inventories differ")

  constraints = {
    family: derive_family_cutoff_constraint(family_rows)
    for family, family_rows in sorted(rows_by_family.items())
  }
  status_counts = Counter(item["status"] for item in constraints.values())
  predictions_by_family: dict[str, list[dict[str, Any]]] = {}
  telemetry = {}
  drifts = {}
  monotonicity = {}
  comparisons = {}
  coverage = {}
  global_feature_scales = {
    name: max(float(np.std([_row_feature(row, name) for row in rows])), 1e-12)
    for name in FEATURES
  }
  for family, family_rows in sorted(rows_by_family.items()):
    fold = outer_by_family[family]
    fit = _fit_from_dict(fold["boundaryFit"])
    predictions = predict_family_mu(fit, family_rows)
    prior = {item["pairId"]: item for item in fold["boundaryPredictions"]}
    for prediction in predictions:
      expected = prior[prediction["pairId"]]
      if prediction["action"] != expected["action"] or not math.isclose(
          prediction["mu"], expected["mu"], rel_tol=0.0, abs_tol=1e-10
      ):
        raise ValueError(
          f"{family}: recomputed per-K prediction differs from outer LOFO")
    predictions_by_family[family] = predictions
    telemetry[family] = {
      "familyId": family,
      "features": {
        name: summarize_feature_by_k(family_rows, name) for name in FEATURES
      },
    }
    drifts[family] = {"familyId": family, **boundary_drift(predictions)}
    comparison = compare_cutoff_stability(
        constraints[family], drifts[family], family_rows, predictions
    )
    comparisons[family] = comparison
    reversal_details = []
    rows_by_pair = {row.pair_id: row for row in family_rows}
    for reversal in constraints[family]["reversals"]:
      lower = rows_by_pair[reversal["lowerPairId"]]
      higher = rows_by_pair[reversal["higherPairId"]]
      changes = {
        name: _row_feature(higher, name) - _row_feature(lower, name)
        for name in FEATURES
      }
      reversal_details.append({
        **reversal,
        "featureChanges": changes,
        "materialFeatureChanges": [
          name for name, change in changes.items()
          if abs(change) >= 0.5 * global_feature_scales[name]
        ],
      })
    monotonicity[family] = {
      "familyId": family,
      "monotonic": constraints[family]["status"] != "CONTRADICTORY",
      "reversalCount": constraints[family]["reversalCount"],
      "reversals": reversal_details,
      "orderedActionSequence": constraints[family]["orderedActionSequence"],
    }
    coverage[family] = lofo_coverage(rows, family)

  representation = family_level_representation(rows_by_family, constraints)
  residuals = residual_structure(rows_by_family, comparisons)
  costly = _false_default_diagnostics(
      rows_by_family, predictions_by_family, constraints, comparisons, coverage
  )
  decision = _classification_summary(comparisons, costly, residuals, coverage)
  drift_counts = Counter(
      item["predictedMuDriftBucket"] for item in drifts.values())
  classification_counts = Counter(
      item["classification"] for item in comparisons.values()
  )
  coverage_counts = Counter(
      item["coverageCategory"] for item in coverage.values())
  summary = {
    "schemaVersion": SCHEMA_VERSION,
    "evaluatorVersion": EVALUATOR_VERSION,
    "status": "DIAGNOSTIC_ONLY_NO_MODEL_RETRAINING",
    "cutoffStatusCounts": {
      status: status_counts.get(status, 0) for status in STATUS_ORDER
    },
    "monotonicFamilyCount": sum(
        item["monotonic"] for item in monotonicity.values()),
    "contradictoryFamilyCount": status_counts["CONTRADICTORY"],
    "totalReversalCount": sum(
        item["reversalCount"] for item in monotonicity.values()),
    "predictedMuDriftBuckets": {
      bucket: drift_counts.get(bucket, 0) for bucket in DRIFT_BUCKETS
    },
    "familyClassificationCounts": {
      category: classification_counts.get(category, 0)
      for category in CLASSIFICATIONS
    },
    "coverageCategoryCounts": dict(sorted(coverage_counts.items())),
    "familyLevelRepresentation": {
      key: value for key, value in representation.items() if key != "families"
    },
    "costlyFalseDefaultFamilyCount": costly["familyCount"],
    "stableResidualPatternCount": len(residuals["repeatedMultiFamilyPatterns"]),
    "decision": decision,
    "preexistingArtifactHashesBefore": before_hashes,
  }

  output_dir.mkdir(parents=True, exist_ok=True)
  digests = {}
  digests["constraints"] = _write_json(
      output_dir / "family_cutoff_constraints.json",
      {"schemaVersion": SCHEMA_VERSION, "families": list(constraints.values())},
  )
  digests["monotonicity"] = _write_json(
      output_dir / "family_cutoff_monotonicity.json",
      {
        "schemaVersion": SCHEMA_VERSION,
        "monotonicFamilyCount": summary["monotonicFamilyCount"],
        "contradictoryFamilyCount": summary["contradictoryFamilyCount"],
        "totalReversalCount": summary["totalReversalCount"],
        "families": list(monotonicity.values()),
      },
  )
  digests["telemetry"] = _write_json(
      output_dir / "within_family_telemetry_drift.json",
      {"schemaVersion": SCHEMA_VERSION, "families": list(telemetry.values())},
  )
  digests["boundaryDrift"] = _write_json(
      output_dir / "within_family_boundary_drift.json",
      {
        "schemaVersion": SCHEMA_VERSION,
        "driftBucketCounts": summary["predictedMuDriftBuckets"],
        "classificationCounts": summary["familyClassificationCounts"],
        "families": [
          {**drifts[family], "cutoffComparison": comparisons[family]}
          for family in sorted(drifts)
        ],
      },
  )
  digests["costlyFalseDefault"] = _write_json(
      output_dir / "costly_false_default_diagnostics.json",
      {"schemaVersion": SCHEMA_VERSION, **costly},
  )
  digests["representation"] = _write_json(
      output_dir / "family_level_representation.json",
      {"schemaVersion": SCHEMA_VERSION, **representation},
  )
  digests["residuals"] = _write_json(
      output_dir / "residual_structure.json",
      {"schemaVersion": SCHEMA_VERSION, **residuals},
  )
  digests["coverage"] = _write_json(
      output_dir / "coverage_analysis.json",
      {
        "schemaVersion": SCHEMA_VERSION,
        "distanceDefinition": "nearest other physical-family row in R, P/R, body, contention after outer-training-only scaling",
        "classificationRule": "interpolation <= training p90; edge <= training max or outside coordinate range; clear extrapolation > training max",
        "coverageCounts": _coverage_counts(rows_by_family),
        "categoryCounts": summary["coverageCategoryCounts"],
        "families": list(coverage.values()),
        "highRegretFamilies": [
          {
            "familyId": item["familyId"],
            "supportedRelativeRegretContribution": item[
              "supportedRelativeRegretContribution"],
            "coverageCategory": item["coverageCategory"],
          }
          for item in costly["families"]
        ],
      },
  )
  digests["findings"] = _write_text(
      output_dir / "processing_diagnostic_findings.md", _findings(summary)
  )
  after_hashes = preexisting_hashes(repo_root)
  if before_hashes != after_hashes:
    raise ValueError("a frozen or prior artifact changed during diagnostics")
  summary["preexistingArtifactHashesAfter"] = after_hashes
  summary["artifactHashes"] = digests
  _write_json(output_dir / "processing_diagnostic_summary.json", summary)
  return summary


def main() -> None:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--repo-root", type=Path, default=Path.cwd())
  parser.add_argument(
      "--output-dir", type=Path,
      default=Path("experiments/pareto_boundary_processing_diagnostic"),
  )
  args = parser.parse_args()
  root = args.repo_root.resolve()
  output = args.output_dir if args.output_dir.is_absolute() else root / args.output_dir
  summary = run_boundary_diagnostic(root, output)
  print(json.dumps({
    "outputDir": str(output),
    "classification": summary["decision"]["classification"],
    "cutoffStatusCounts": summary["cutoffStatusCounts"],
  }, sort_keys=True))


if __name__ == "__main__":
  main()
