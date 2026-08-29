"""Cost-sensitive direct side-of-peak classifier with nested family LOFO."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor, as_completed
from dataclasses import asdict, dataclass, fields
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
from typing import Any, Iterable, Sequence

import numpy as np
from scipy.optimize import LinearConstraint, minimize
from scipy.special import expit

from pareto_weight_calibration.action_model import (
  CACHE,
  DEFAULT,
  INDETERMINATE,
  TOLERANCE,
  ActionRow,
  action_loss,
  evaluate_action_predictions,
  fold_influence,
  grouped_family_folds,
)
from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.types import DomainConfig

LEFT = "LEFT_OF_PEAK"
RIGHT = "RIGHT_OF_PEAK"
SCHEMA_VERSION = 1
TRAINER_VERSION = "direct-side-cost-sensitive-v1"
DIRECT_L2_GRID = (1e-3, 1e-2)
DIRECT_TEMPERATURE_GRID = (0.5, 1.0)
MINIMUM_K_SLOPE = 1e-6

BASE_FEATURES = ("K", "pRatio", "logR", "body", "contention")
DIRECT_STRUCTURES: dict[str, tuple[str, ...]] = {
  "S0_BASE": BASE_FEATURES,
  "S1_K_PR": (*BASE_FEATURES, "K*pRatio"),
  "S2_K_BODY": (*BASE_FEATURES, "K*body"),
  "S3_K_CONTENTION": (*BASE_FEATURES, "K*contention"),
  "S4_STATE_PR": (*BASE_FEATURES, "body*pRatio", "contention*pRatio"),
  "S5_K_PR_BODY": (*BASE_FEATURES, "K*pRatio", "K*body"),
  "S6_ALL": (
    *BASE_FEATURES,
    "K*pRatio",
    "K*body",
    "K*contention",
    "body*pRatio",
    "contention*pRatio",
  ),
}
RUNTIME_FEATURES = frozenset(BASE_FEATURES)
INTERACTION_PARENTS = {
  "K*pRatio": ("K", "pRatio"),
  "K*body": ("K", "body"),
  "K*contention": ("K", "contention"),
  "body*pRatio": ("body", "pRatio"),
  "contention*pRatio": ("contention", "pRatio"),
}

PREEXISTING_ARTIFACTS = (
  Path("experiments/pareto_action_model_training/action_training_dataset.json"),
  Path("experiments/pareto_action_model_training/action_model_outer_lofo.json"),
  Path("experiments/pareto_action_model_training/action_model_m4c_lofo.json"),
  Path("experiments/pareto_side_of_peak_evaluation/side_of_peak_results.json"),
  Path("experiments/pareto_side_of_peak_evaluation/side_of_peak_summary.json"),
  Path("experiments/productivity_participation_domain.json"),
  Path("experiments/pareto_training_step5/training_pairs.tsv"),
)


@dataclass(frozen=True)
class DirectScaler:
  names: tuple[str, ...]
  means: tuple[float, ...]
  scales: tuple[float, ...]

  def serialize(self) -> dict[str, Any]:
    return asdict(self)


@dataclass(frozen=True)
class DirectFit:
  structure: str
  feature_names: tuple[str, ...]
  l2: float
  temperature: float
  scaler: DirectScaler
  coefficients: tuple[float, ...]
  objective: float
  success: bool
  iterations: int
  minimum_domain_k_slope: float

  def serialize(self) -> dict[str, Any]:
    return {
      "structure": self.structure,
      "featureNames": list(self.feature_names),
      "l2": self.l2,
      "temperature": self.temperature,
      "scaler": self.scaler.serialize(),
      "coefficients": list(self.coefficients),
      "objective": self.objective,
      "success": self.success,
      "iterations": self.iterations,
      "minimumDomainKSlope": self.minimum_domain_k_slope,
    }


def side_for_action(action: str) -> str:
  if action == DEFAULT:
    return LEFT
  if action == CACHE:
    return RIGHT
  raise ValueError(f"no decisive side for action {action!r}")


def action_for_score(score: float) -> str:
  if not math.isfinite(score):
    raise ValueError("direct side score must be finite")
  return CACHE if score > 0.0 else DEFAULT


def side_for_score(score: float) -> str:
  return RIGHT if action_for_score(score) == CACHE else LEFT


def validate_direct_feature_names(names: Iterable[str]) -> None:
  for name in names:
    if name in RUNTIME_FEATURES:
      continue
    parents = INTERACTION_PARENTS.get(name)
    if parents is None:
      raise ValueError(
          f"counterfactual, future, withdrawn-arm, or unknown telemetry is not allowed: {name}"
      )


def verify_structure_heredity(structure: str) -> None:
  if structure not in DIRECT_STRUCTURES:
    raise ValueError(f"unknown direct-side structure {structure!r}")
  names = set(DIRECT_STRUCTURES[structure])
  for interaction, parents in INTERACTION_PARENTS.items():
    if interaction in names and not set(parents).issubset(names):
      raise ValueError(f"{structure}: interaction {interaction} lacks parents")
  validate_direct_feature_names(names)


for _structure in DIRECT_STRUCTURES:
  verify_structure_heredity(_structure)


def current_state_features(row: ActionRow) -> dict[str, float]:
  if row.current_k < 1 or row.registered_workers < 2:
    raise ValueError("invalid current runtime participation state")
  if row.productive_handles <= 0.0 or row.p_ratio > 1.0 + 1e-12:
    raise ValueError(
      "current productive-handle ratio is outside the runtime domain")
  if not 0.0 <= row.contention <= 1.0:
    raise ValueError("current contention is outside [0,1]")
  return {
    "K": float(row.current_k),
    "pRatio": row.p_ratio,
    "logR": math.log(float(row.registered_workers)),
    "body": row.body_log,
    "contention": row.contention,
  }


def fit_direct_scaler(rows: Sequence[ActionRow]) -> DirectScaler:
  if not rows:
    raise ValueError("direct-side scaling requires rows")
  weights, _ = fold_influence(rows)
  matrix = np.asarray([
    [current_state_features(row)[name] for name in BASE_FEATURES]
    for row in rows
  ], dtype=np.float64)
  total = float(np.sum(weights))
  means = np.sum(matrix * weights[:, None], axis=0) / total
  variance = np.sum(weights[:, None] * (matrix - means) ** 2, axis=0) / total
  scales = np.where(np.sqrt(variance) > 1e-12, np.sqrt(variance), 1.0)
  return DirectScaler(
      names=BASE_FEATURES,
      means=tuple(float(value) for value in means),
      scales=tuple(float(value) for value in scales),
  )


def _scaled_main(row: ActionRow, scaler: DirectScaler) -> dict[str, float]:
  raw = current_state_features(row)
  return {
    name: (raw[name] - scaler.means[index]) / scaler.scales[index]
    for index, name in enumerate(scaler.names)
  }


def _feature_value(name: str, scaled: dict[str, float]) -> float:
  parents = INTERACTION_PARENTS.get(name)
  if parents is None:
    return scaled[name]
  return scaled[parents[0]] * scaled[parents[1]]


def direct_design_matrix(
    rows: Sequence[ActionRow], structure: str, scaler: DirectScaler
) -> np.ndarray:
  verify_structure_heredity(structure)
  names = DIRECT_STRUCTURES[structure]
  return np.asarray([
    [1.0, *[_feature_value(name, _scaled_main(row, scaler)) for name in names]]
    for row in rows
  ], dtype=np.float64)


def _domain_scaled_bounds(
    scaler: DirectScaler, domain: DomainConfig
) -> dict[str, tuple[float, float]]:
  raw_bounds = {
    "K": (float(domain.k_min), float(domain.r_max)),
    "pRatio": (float(domain.p_min) / float(domain.r_max), 1.0),
    "logR": (math.log(float(domain.r_min)), math.log(float(domain.r_max))),
    "body": (
      math.log1p(float(domain.body_cost_min_ns)),
      math.log1p(float(domain.body_cost_max_ns)),
    ),
    "contention": (float(domain.c_min), float(domain.c_max)),
  }
  return {
    name: (
      (raw_bounds[name][0] - scaler.means[index]) / scaler.scales[index],
      (raw_bounds[name][1] - scaler.means[index]) / scaler.scales[index],
    )
    for index, name in enumerate(scaler.names)
  }


def monotonic_constraint_matrix(
    structure: str, scaler: DirectScaler, domain: DomainConfig
) -> np.ndarray:
  """Rows map coefficients to d(score)/d(zK) at every domain corner."""
  names = DIRECT_STRUCTURES[structure]
  k_interactions = [
    name for name in names if name.startswith("K*")
  ]
  varying = [INTERACTION_PARENTS[name][1] for name in k_interactions]
  bounds = _domain_scaled_bounds(scaler, domain)
  corners = list(itertools.product(*[bounds[name] for name in varying]))
  if not corners:
    corners = [()]
  matrix = []
  for corner in corners:
    state = dict(zip(varying, corner, strict=True))
    derivative = np.zeros(1 + len(names), dtype=np.float64)
    derivative[1 + names.index("K")] = 1.0
    for interaction in k_interactions:
      derivative[1 + names.index(interaction)] = state[
        INTERACTION_PARENTS[interaction][1]
      ]
    matrix.append(derivative)
  return np.asarray(matrix)


def fit_direct_classifier(
    rows: Sequence[ActionRow],
    structure: str,
    l2: float,
    temperature: float,
    domain: DomainConfig,
) -> DirectFit:
  if any(not row.decisive for row in rows):
    raise ValueError(
      "indeterminate rows must not be forced into direct-side fitting")
  if l2 not in DIRECT_L2_GRID or temperature not in DIRECT_TEMPERATURE_GRID:
    raise ValueError("direct-side hyperparameters must come from frozen grids")
  scaler = fit_direct_scaler(rows)
  x = direct_design_matrix(rows, structure, scaler)
  weights, _ = fold_influence(rows)
  costs = np.asarray(
      [row.supported_wrong_action_loss for row in rows], dtype=np.float64
  )
  y = np.asarray([1.0 if row.observed_action == CACHE else 0.0 for row in rows])
  weighted_cost = weights * costs
  normalizer = float(np.sum(weighted_cost))
  if normalizer <= 0.0:
    raise ValueError("direct-side training fold has no supported action cost")
  constraint_matrix = monotonic_constraint_matrix(structure, scaler, domain)
  constraint = LinearConstraint(
      constraint_matrix,
      lb=np.full(len(constraint_matrix), MINIMUM_K_SLOPE),
      ub=np.full(len(constraint_matrix), np.inf),
  )

  def objective(beta: np.ndarray) -> tuple[float, np.ndarray]:
    logits = (x @ beta) / temperature
    primary = float(
        np.sum(weighted_cost * (np.logaddexp(0.0, logits) - y * logits))
        / normalizer
    )
    regularized = primary + l2 * float(np.dot(beta[1:], beta[1:]))
    probability = expit(logits)
    gradient = x.T @ (weighted_cost * (probability - y))
    gradient = gradient / normalizer / temperature
    gradient[1:] += 2.0 * l2 * beta[1:]
    return regularized, gradient

  names = DIRECT_STRUCTURES[structure]
  start = np.zeros(x.shape[1], dtype=np.float64)
  start[1 + names.index("K")] = 0.5
  result = minimize(
      fun=lambda beta: objective(beta)[0],
      x0=start,
      jac=lambda beta: objective(beta)[1],
      method="SLSQP",
      bounds=[(-20.0, 20.0)] * x.shape[1],
      constraints=[constraint],
      options={"ftol": 1e-11, "maxiter": 500, "disp": False},
  )
  beta = np.asarray(result.x, dtype=np.float64)
  slopes = constraint_matrix @ beta
  if (
      not result.success or np.any(~np.isfinite(beta))
      or float(np.min(slopes)) < MINIMUM_K_SLOPE - 1e-8
  ):
    raise ValueError(
        f"direct-side optimizer failed for {structure}@{l2}@{temperature}: "
        f"{result.message}"
    )
  return DirectFit(
      structure=structure,
      feature_names=names,
      l2=l2,
      temperature=temperature,
      scaler=scaler,
      coefficients=tuple(float(value) for value in beta),
      objective=float(result.fun),
      success=bool(result.success),
      iterations=int(getattr(result, "nit", 0)),
      minimum_domain_k_slope=float(np.min(slopes)),
  )


def predict_direct_side(
    fit: DirectFit, rows: Sequence[ActionRow]
) -> list[dict[str, Any]]:
  x = direct_design_matrix(rows, fit.structure, fit.scaler)
  scores = x @ np.asarray(fit.coefficients)
  result = []
  for row, score in zip(rows, scores, strict=True):
    action = action_for_score(float(score))
    result.append({
      "pairId": row.pair_id,
      "familyId": row.family_id,
      "currentK": row.current_k,
      "score": float(score),
      "probabilityRight": float(expit(float(score) / fit.temperature)),
      "predictedSide": side_for_action(action),
      "action": action,
      "currentStateFeatures": current_state_features(row),
    })
  return result


def evaluate_direct_predictions(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]]
) -> dict[str, Any]:
  base = evaluate_action_predictions(rows, predictions)
  pred_by_pair = {item["pairId"]: item for item in predictions}
  decisive = [row for row in rows if row.decisive]
  raw_correct = [
    pred_by_pair[row.pair_id]["action"] == row.observed_action for row in
    decisive
  ]
  evidence_total = math.fsum(row.evidence_weight for row in decisive)
  pooled = math.fsum(
      row.evidence_weight * float(correct)
      for row, correct in zip(decisive, raw_correct, strict=True)
  ) / evidence_total
  family_accuracy = []
  for family in sorted({row.family_id for row in decisive}):
    family_rows = [row for row in decisive if row.family_id == family]
    total = math.fsum(row.evidence_weight for row in family_rows)
    family_accuracy.append(math.fsum(
        row.evidence_weight
        * float(pred_by_pair[row.pair_id]["action"] == row.observed_action)
        for row in family_rows
    ) / total)
  return {
    **base,
    "overallSideAccuracy": sum(raw_correct) / len(raw_correct),
    "pooledEvidenceWeightedSideAccuracy": pooled,
    "familyBalancedEvidenceWeightedSideAccuracy": float(
      np.mean(family_accuracy)),
  }


def _candidate_metrics_key(candidate: dict[str, Any]) -> tuple[Any, ...]:
  metrics = candidate["metrics"]
  return (
    metrics["supportedRelativeRegret"],
    metrics["worstFamilySupportedRelativeRegret"],
    metrics["falseDefault"]["supportedRelativeRegret"],
    metrics["falseCache"]["supportedRelativeRegret"],
    -metrics["familyBalancedEvidenceWeightedSideAccuracy"],
    len(DIRECT_STRUCTURES[candidate["structure"]]),
    -candidate["l2"],
    candidate["candidateId"],
  )


def inner_select_direct_side(
    rows: Sequence[ActionRow], domain: DomainConfig
) -> dict[str, Any]:
  decisive = [row for row in rows if row.decisive]
  folds = grouped_family_folds(row.family_id for row in decisive)
  candidates = []
  for structure in DIRECT_STRUCTURES:
    for l2 in DIRECT_L2_GRID:
      for temperature in DIRECT_TEMPERATURE_GRID:
        validation_rows = []
        predictions = []
        for held_families in folds:
          held_set = set(held_families)
          train = [row for row in decisive if row.family_id not in held_set]
          validation = [row for row in decisive if row.family_id in held_set]
          fit = fit_direct_classifier(train, structure, l2, temperature, domain)
          validation_rows.extend(validation)
          predictions.extend(predict_direct_side(fit, validation))
        ordered = sorted(
            zip(validation_rows, predictions, strict=True),
            key=lambda item: item[0].pair_id,
        )
        ordered_rows = [item[0] for item in ordered]
        ordered_predictions = [item[1] for item in ordered]
        candidates.append({
          "candidateId": (
            f"{structure}@l2={l2:.0e}@temperature={temperature:g}"
          ),
          "structure": structure,
          "l2": l2,
          "temperature": temperature,
          "metrics": evaluate_direct_predictions(
              ordered_rows, ordered_predictions
          ),
        })
  best_by_complexity: dict[int, dict[str, Any]] = {}
  for complexity in sorted(
      {len(DIRECT_STRUCTURES[item["structure"]]) for item in candidates}):
    best_by_complexity[complexity] = min(
        (
          candidate for candidate in candidates
          if len(DIRECT_STRUCTURES[candidate["structure"]]) == complexity
        ),
        key=_candidate_metrics_key,
    )
  incumbent = best_by_complexity[min(best_by_complexity)]
  admissions = []
  for complexity in sorted(best_by_complexity)[1:]:
    candidate = best_by_complexity[complexity]
    candidate_families = candidate["metrics"]["families"]
    incumbent_families = incumbent["metrics"]["families"]
    improved = sum(
        candidate_families[family]["supportedRelativeRegret"]
        < incumbent_families[family]["supportedRelativeRegret"] - TOLERANCE
        for family in candidate_families
    )
    better = _candidate_metrics_key(candidate) < _candidate_metrics_key(
      incumbent)
    admitted = better and improved > 1
    admissions.append({
      "incumbent": incumbent["candidateId"],
      "candidate": candidate["candidateId"],
      "candidateRanksBetter": better,
      "improvedFamilyCount": improved,
      "admitted": admitted,
    })
    if admitted:
      incumbent = candidate
  return {
    "folds": [list(fold) for fold in folds],
    "candidates": candidates,
    "admissions": admissions,
    "selected": incumbent,
  }


def _compact_metrics(metrics: dict[str, Any]) -> dict[str, Any]:
  return {key: value for key, value in metrics.items() if key != "families"}


def _compact_selection(selection: dict[str, Any]) -> dict[str, Any]:
  return {
    "folds": selection["folds"],
    "selected": {
      key: selection["selected"][key]
      for key in ("candidateId", "structure", "l2", "temperature")
    },
    "selectedMetrics": _compact_metrics(selection["selected"]["metrics"]),
    "admissions": selection["admissions"],
  }


def _outer_task(args: tuple[Any, ...]) -> dict[str, Any]:
  held_family, rows, domain = args
  decisive = [row for row in rows if row.decisive]
  train = [row for row in decisive if row.family_id != held_family]
  held = [row for row in decisive if row.family_id == held_family]
  if not held or any(row.family_id == held_family for row in train):
    raise ValueError("invalid direct-side outer family partition")
  selection = inner_select_direct_side(train, domain)
  selected = selection["selected"]
  fit = fit_direct_classifier(
      train, selected["structure"], selected["l2"],
      selected["temperature"], domain,
  )
  return {
    "heldOutFamily": held_family,
    "trainingFamilyCount": len({row.family_id for row in train}),
    "trainingPairIdsHash": hashlib.sha256(
        "\n".join(sorted(row.pair_id for row in train)).encode()
    ).hexdigest(),
    "selection": _compact_selection(selection),
    "fit": fit.serialize(),
    "predictions": predict_direct_side(fit, held),
  }


def _rows_from_dataset(payload: dict[str, Any]) -> list[ActionRow]:
  names = {field.name for field in fields(ActionRow)}
  rows = [ActionRow(**{name: item[name] for name in names}) for item in
          payload["rows"]]
  if len({row.pair_id for row in rows}) != len(rows):
    raise ValueError("duplicate frozen action pair id")
  return sorted(rows, key=lambda row: row.pair_id)


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


def _reference_metrics(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]]
) -> dict[str, Any]:
  return evaluate_direct_predictions(rows, predictions)


def _breakdown_name(row: ActionRow, field: str) -> str:
  if field == "R":
    return str(row.registered_workers)
  if field == "bodyBucket":
    if row.work_units == 0:
      return "WU0"
    if row.work_units <= 112:
      return "LOW_POSITIVE_WU1_112"
    if row.work_units < 768:
      return "MEDIUM_WU113_767"
    return "HIGH_WU768_PLUS"
  if field == "pRatioBucket":
    if row.p_ratio <= 0.25:
      return "(0,0.25]"
    if row.p_ratio <= 0.5:
      return "(0.25,0.5]"
    if row.p_ratio <= 0.75:
      return "(0.5,0.75]"
    return "(0.75,1]"
  if field == "contentionBucket":
    if row.contention <= 0.25:
      return "[0,0.25]"
    if row.contention <= 0.5:
      return "(0.25,0.5]"
    if row.contention <= 0.75:
      return "(0.5,0.75]"
    return "(0.75,1]"
  raise ValueError(f"unknown breakdown {field}")


def _breakdowns(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]]
) -> dict[str, Any]:
  pred_by_pair = {item["pairId"]: item for item in predictions}
  result = {}
  for field in ("R", "bodyBucket", "pRatioBucket", "contentionBucket"):
    groups: dict[str, list[ActionRow]] = defaultdict(list)
    for row in rows:
      groups[_breakdown_name(row, field)].append(row)
    result[field] = {
      name: _compact_metrics(evaluate_direct_predictions(
          group, [pred_by_pair[row.pair_id] for row in group]
      ))
      for name, group in sorted(groups.items())
    }
  return result


def _error_rows(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]],
    wrong_type: str
) -> list[dict[str, Any]]:
  pred_by_pair = {item["pairId"]: item for item in predictions}
  result = []
  for row in rows:
    prediction = pred_by_pair[row.pair_id]
    loss = action_loss(row, prediction["action"])
    if loss["wrongType"] != wrong_type:
      continue
    result.append({
      "familyId": row.family_id,
      "pairId": row.pair_id,
      "K": row.current_k,
      "observedSide": side_for_action(row.observed_action),
      "predictedSide": prediction["predictedSide"],
      "score": prediction["score"],
      "supportedWrongActionLoss": row.supported_wrong_action_loss,
      "supportedRelativeWrongActionLoss": row.supported_relative_wrong_action_loss,
      "evidenceWeight": row.evidence_weight,
      "familyInfluenceWeight": row.influence_weight,
      "currentStateFeatures": prediction["currentStateFeatures"],
    })
  return sorted(
      result,
      key=lambda item: (
        -item["supportedRelativeWrongActionLoss"], item["familyId"], item["K"]
      ),
  )


def _contradictory_diagnostics(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]]
) -> list[dict[str, Any]]:
  pred_by_pair = {item["pairId"]: item for item in predictions}
  by_family: dict[str, list[ActionRow]] = defaultdict(list)
  for row in rows:
    by_family[row.family_id].append(row)
  result = []
  for family, family_rows in sorted(by_family.items()):
    ordered = sorted(family_rows, key=lambda row: (row.current_k, row.pair_id))
    decisive = [row for row in ordered if row.decisive]
    reversals = [
      (first, second) for first, second in zip(decisive, decisive[1:])
      if first.observed_action == CACHE and second.observed_action == DEFAULT
    ]
    if not reversals:
      continue
    cases = []
    for row in ordered:
      prediction = pred_by_pair[row.pair_id]
      loss = action_loss(row, prediction["action"])
      cases.append({
        "pairId": row.pair_id,
        "K": row.current_k,
        "currentState": prediction["currentStateFeatures"],
        "score": prediction["score"],
        "predictedSide": prediction["predictedSide"],
        "observedSide": side_for_action(
          row.observed_action) if row.decisive else INDETERMINATE,
        "supportedRelativeRegret": loss["supportedRelativeLoss"],
      })
    explains = all(
        pred_by_pair[first.pair_id]["action"] == CACHE
        and pred_by_pair[second.pair_id]["action"] == DEFAULT
        for first, second in reversals
    )
    result.append({
      "familyId": family,
      "reversalCount": len(reversals),
      "directStateConditioningExplainsEveryReversal": explains,
      "status": "EXPLAINED_BY_CURRENT_STATE" if explains else "UNRESOLVED_EVIDENCE",
      "cases": cases,
    })
  return result


def _common_logistic_reference(
    rows: Sequence[ActionRow], side_results: dict[str, Any]
) -> tuple[list[ActionRow], list[dict[str, Any]]]:
  rows_by_pair = {row.pair_id: row for row in rows}
  selected_rows = []
  predictions = []
  for case in side_results["cases"]:
    row = rows_by_pair.get(case["pairId"])
    if row is None or not row.decisive:
      continue
    selected_rows.append(row)
    action = case["logistic"]["action"]
    predictions.append({
      "pairId": row.pair_id,
      "familyId": row.family_id,
      "currentK": row.current_k,
      "action": action,
      "predictedSide": side_for_action(action),
    })
  ordered = sorted(
      zip(selected_rows, predictions, strict=True),
      key=lambda item: item[0].pair_id
  )
  return [item[0] for item in ordered], [item[1] for item in ordered]


def _findings(summary: dict[str, Any]) -> str:
  direct = summary["directOuterLofo"]
  boundary = summary["boundaryOuterLofo"]
  m4c = summary["m4cOuterLofo"]
  return "\n".join([
    "# Direct side-of-peak classifier",
    "",
    "No benchmarks were rerun, no production Java was changed, and no exact peak or cutoff target was used for training or selection.",
    "",
    "## Held-out result",
    "",
    f"- Direct supported relative regret: {direct['supportedRelativeRegret']:.8f}.",
    f"- Boundary / M4-C supported regret: {boundary['supportedRelativeRegret']:.8f} / {m4c['supportedRelativeRegret']:.8f}.",
    f"- Direct worst-family regret: {direct['worstFamilySupportedRelativeRegret']:.8f}.",
    f"- Direct overall / pooled-weighted side accuracy: {direct['overallSideAccuracy']:.8f} / {direct['pooledEvidenceWeightedSideAccuracy']:.8f}.",
    f"- Direct family-balanced weighted side accuracy: {direct['familyBalancedEvidenceWeightedSideAccuracy']:.8f}.",
    f"- Direct LEFT / RIGHT accuracy: {direct['defaultAccuracy']:.8f} / {direct['cacheAccuracy']:.8f}.",
    f"- Direct false DEFAULT count, rate, regret: {direct['falseDefault']['count']}, {direct['falseDefault']['rate']:.8f}, {direct['falseDefault']['supportedRelativeRegret']:.8f}.",
    f"- Direct false CACHE count, rate, regret: {direct['falseCache']['count']}, {direct['falseCache']['rate']:.8f}, {direct['falseCache']['supportedRelativeRegret']:.8f}.",
    f"- Largest false DEFAULT / false CACHE relative loss: {direct['falseDefault']['largestSingleSupportedRelativeLoss']:.8f} / {direct['falseCache']['largestSingleSupportedRelativeLoss']:.8f}.",
    f"- Families improved versus boundary: {summary['acceptance']['improvedFamilyCount']}.",
    f"- Full-data grouped selected candidate: `{summary['selected']['candidateId']}`.",
    f"- Outer selected-candidate distribution: {summary['outerSelectedCandidateDistribution']}.",
    f"- Previous logistic / direct common-cohort regret: {summary['logisticCommonCohort']['supportedRelativeRegret']:.8f} / {summary['directLogisticCommonCohort']['supportedRelativeRegret']:.8f}.",
    f"- Contradictory families explained / unresolved: {summary['contradictoryExplainedCount']} / {summary['contradictoryUnresolvedCount']}.",
    f"- Acceptance: `{summary['acceptance']['decision']}`.",
    f"- Failure causes: {summary['acceptance']['failureCauses']}.",
    "",
    "## Interpretation",
    "",
    "The direct model optimizes the sign of a current-state score with frozen row-level supported costs. It has no peak-location, cutoff-distance, throughput-amplitude, width, or calibration target.",
    "",
    "For every candidate, the derivative of score with respect to K is constrained positive across the physical domain for fixed P/R, R, body, and contention. State changes along a real family trajectory may still change the predicted side.",
  ])


def run_direct_side_training(
    repo_root: Path, output_dir: Path, worker_count: int | None = None
) -> dict[str, Any]:
  before_hashes = preexisting_hashes(repo_root)
  dataset = _verified_json(repo_root / PREEXISTING_ARTIFACTS[0])
  boundary_outer = _verified_json(repo_root / PREEXISTING_ARTIFACTS[1])
  m4c_outer = _verified_json(repo_root / PREEXISTING_ARTIFACTS[2])
  side_results = _verified_json(repo_root / PREEXISTING_ARTIFACTS[3])
  domain = DomainConfig.from_dict(
    _verified_json(repo_root / PREEXISTING_ARTIFACTS[5]))
  rows = _rows_from_dataset(dataset)
  decisive = [row for row in rows if row.decisive]
  indeterminate = [row for row in rows if not row.decisive]
  families = sorted({row.family_id for row in decisive})
  boundary_predictions_by_pair = {
    item["pairId"]: item for item in boundary_outer["predictions"]
  }
  m4c_predictions_by_pair = {
    item["pairId"]: item for item in m4c_outer["predictions"]
  }
  if set(row.pair_id for row in decisive) - set(boundary_predictions_by_pair):
    raise ValueError("boundary reference is missing direct-side rows")

  workers = min(max(1, worker_count or os.cpu_count() or 1), len(families), 16)
  tasks = [(family, rows, domain) for family in families]
  outer_folds = []
  if workers == 1:
    outer_folds = [_outer_task(task) for task in tasks]
  else:
    with ProcessPoolExecutor(max_workers=workers) as executor:
      futures = {executor.submit(_outer_task, task): task[0] for task in tasks}
      for future in as_completed(futures):
        outer_folds.append(future.result())
  outer_folds.sort(key=lambda item: item["heldOutFamily"])
  direct_predictions = sorted(
      [prediction for fold in outer_folds for prediction in
       fold["predictions"]],
      key=lambda item: item["pairId"],
  )
  direct_metrics = evaluate_direct_predictions(decisive, direct_predictions)
  boundary_predictions = [
    boundary_predictions_by_pair[row.pair_id] for row in decisive
  ]
  m4c_predictions = [m4c_predictions_by_pair[row.pair_id] for row in decisive]
  boundary_metrics = _reference_metrics(decisive, boundary_predictions)
  m4c_metrics = _reference_metrics(decisive, m4c_predictions)
  logistic_rows, logistic_predictions = _common_logistic_reference(rows,
                                                                   side_results)
  logistic_metrics = evaluate_direct_predictions(logistic_rows,
                                                 logistic_predictions)
  direct_by_pair = {item["pairId"]: item for item in direct_predictions}
  direct_common_metrics = evaluate_direct_predictions(
      logistic_rows, [direct_by_pair[row.pair_id] for row in logistic_rows]
  )

  full_selection = inner_select_direct_side(decisive, domain)
  selected = full_selection["selected"]
  final_fit = fit_direct_classifier(
      decisive, selected["structure"], selected["l2"],
      selected["temperature"], domain,
  )
  false_default = _error_rows(decisive, direct_predictions, "FALSE_DEFAULT")
  false_cache = _error_rows(decisive, direct_predictions, "FALSE_CACHE")
  contradictory = _contradictory_diagnostics(decisive, direct_predictions)
  improved = sum(
      direct_metrics["families"][family]["supportedRelativeRegret"]
      < boundary_metrics["families"][family][
        "supportedRelativeRegret"] - TOLERANCE
      for family in direct_metrics["families"]
  )
  criteria = {
    "supportedRegret": direct_metrics["supportedRelativeRegret"]
                       <= boundary_metrics[
                         "supportedRelativeRegret"] + TOLERANCE,
    "worstFamilyRegret": direct_metrics["worstFamilySupportedRelativeRegret"]
                         <= boundary_metrics[
                           "worstFamilySupportedRelativeRegret"] + TOLERANCE,
    "falseDefaultRegret": direct_metrics["falseDefault"][
                            "supportedRelativeRegret"]
                          <= boundary_metrics["falseDefault"][
                            "supportedRelativeRegret"] + TOLERANCE,
    "falseCacheRegret": direct_metrics["falseCache"]["supportedRelativeRegret"]
                        <= boundary_metrics["falseCache"][
                          "supportedRelativeRegret"] + TOLERANCE,
    "familyBalancedSideAccuracy": direct_metrics[
                                    "familyBalancedEvidenceWeightedSideAccuracy"
                                  ] >= boundary_metrics[
                                    "familyBalancedEvidenceWeightedSideAccuracy"] - TOLERANCE,
    "multipleFamiliesImproved": improved > 1,
  }
  accepted = all(criteria.values())
  failure_causes = []
  if not accepted:
    if any(item["status"] == "UNRESOLVED_EVIDENCE" for item in contradictory):
      failure_causes.append("EVIDENCE_INCONSISTENCY")
    if selected["structure"] == "S6_ALL":
      failure_causes.append(
        "INSUFFICIENT_STATE_FEATURES_OR_INTERACTION_LATTICE")
    if not criteria["falseCacheRegret"] and selected["structure"] == "S0_BASE":
      failure_causes.append("WRONG_INTERACTION_STRUCTURE")
    if len(indeterminate) > len(rows) / 5:
      failure_causes.append("IRREDUCIBLE_AMBIGUITY")
    if not failure_causes:
      failure_causes.append("WRONG_INTERACTION_STRUCTURE")
  acceptance = {
    "decision": "ADVANCE_TO_SEPARATE_INTEGRATION_PHASE" if accepted else "DO_NOT_INTEGRATE",
    "criteria": criteria,
    "improvedFamilyCount": improved,
    "failureCauses": failure_causes,
    "productionJavaChanged": False,
  }
  dataset_payload = {
    "schemaVersion": SCHEMA_VERSION,
    "trainerVersion": TRAINER_VERSION,
    "objective": "predict current LEFT/DEFAULT versus RIGHT/CACHE sign using frozen row-level supported costs",
    "decisiveRowCount": len(decisive),
    "indeterminateRowCount": len(indeterminate),
    "allPhysicalFamilyCount": len({row.family_id for row in rows}),
    "decisivePhysicalFamilyCount": len(families),
    "indeterminateRowsForcedIntoLabels": False,
    "features": list(BASE_FEATURES),
    "counterfactualTelemetryUsed": False,
    "rows": [
      {
        **row.to_dict(),
        "observedSide": side_for_action(row.observed_action),
        "currentStateFeatures": current_state_features(row),
      }
      for row in decisive
    ],
    "indeterminateRows": [row.to_dict() for row in indeterminate],
    "inputArtifactHashes": before_hashes,
  }
  candidate = {
    "schemaVersion": SCHEMA_VERSION,
    "trainerVersion": TRAINER_VERSION,
    "status": "TRAINING_ONLY_NOT_INSTALLED",
    "productionAuthorized": False,
    "model": {
      **final_fit.serialize(),
      "form": "score = beta0 + betaK*K + current-state main effects and limited hereditary interactions",
      "actionMapping": {"score<=0": DEFAULT, "score>0": CACHE},
      "sideMapping": {"score<=0": LEFT, "score>0": RIGHT},
      "monotonicity": "d(score)/dK > 0 across the physical domain for fixed state",
      "exactPeakOrCutoffTargetUsed": False,
    },
    "selectedHyperparameters": {
      key: selected[key] for key in ("structure", "l2", "temperature")
    },
    "nestedOuterMetrics": _compact_metrics(direct_metrics),
    "acceptance": acceptance,
    "provenance": {"inputArtifactHashes": before_hashes},
  }

  output_dir.mkdir(parents=True, exist_ok=True)
  digests = {}
  digests["dataset"] = _write_json(
      output_dir / "direct_side_training_dataset.json", dataset_payload
  )
  digests["grid"] = _write_json(
      output_dir / "direct_side_grid_results.json",
      {
        "schemaVersion": SCHEMA_VERSION,
        "structures": {key: list(value) for key, value in
                       DIRECT_STRUCTURES.items()},
        "l2Grid": list(DIRECT_L2_GRID),
        "temperatureGrid": list(DIRECT_TEMPERATURE_GRID),
        "fullDataGroupedSelection": {
          "folds": full_selection["folds"],
          "candidates": [
            {
              **{key: candidate[key] for key in (
                "candidateId", "structure", "l2", "temperature"
              )},
              "metrics": _compact_metrics(candidate["metrics"]),
            }
            for candidate in full_selection["candidates"]
          ],
          "admissions": full_selection["admissions"],
          "selectedCandidateId": selected["candidateId"],
        },
      },
  )
  digests["outer"] = _write_json(
      output_dir / "direct_side_outer_lofo.json",
      {
        "schemaVersion": SCHEMA_VERSION,
        "trainerVersion": TRAINER_VERSION,
        "outerUnit": "physical family",
        "workerCount": workers,
        "folds": outer_folds,
        "predictions": direct_predictions,
        "metrics": _compact_metrics(direct_metrics),
        "breakdowns": _breakdowns(decisive, direct_predictions),
        "contradictoryFamilyDiagnostics": contradictory,
      },
  )
  digests["families"] = _write_json(
      output_dir / "direct_side_family_metrics.json",
      {
        "schemaVersion": SCHEMA_VERSION,
        "direct": direct_metrics["families"],
        "boundary": boundary_metrics["families"],
        "m4c": m4c_metrics["families"],
      },
  )
  digests["falseDefault"] = _write_json(
      output_dir / "direct_side_false_default.json",
      {"schemaVersion": SCHEMA_VERSION, "count": len(false_default),
       "cases": false_default},
  )
  digests["falseCache"] = _write_json(
      output_dir / "direct_side_false_cache.json",
      {"schemaVersion": SCHEMA_VERSION, "count": len(false_cache),
       "cases": false_cache},
  )
  digests["candidate"] = _write_json(
      output_dir / "direct_side_candidate.json", candidate
  )
  summary = {
    "schemaVersion": SCHEMA_VERSION,
    "trainerVersion": TRAINER_VERSION,
    "directOuterLofo": _compact_metrics(direct_metrics),
    "boundaryOuterLofo": _compact_metrics(boundary_metrics),
    "m4cOuterLofo": _compact_metrics(m4c_metrics),
    "logisticCommonCohort": _compact_metrics(logistic_metrics),
    "directLogisticCommonCohort": _compact_metrics(direct_common_metrics),
    "selected": {
      "candidateId": selected["candidateId"],
      "structure": selected["structure"],
      "features": list(DIRECT_STRUCTURES[selected["structure"]]),
      "l2": selected["l2"],
      "temperature": selected["temperature"],
    },
    "comparisonCohort": (
      f"{len(decisive)} frozen decisive action rows; indeterminate rows are not "
      "forced into labels or used as denominator weight"
    ),
    "outerSelectedCandidateDistribution": dict(sorted(Counter(
        fold["selection"]["selected"]["candidateId"] for fold in outer_folds
    ).items())),
    "contradictoryFamilyDiagnostics": contradictory,
    "contradictoryExplainedCount": sum(
        item["status"] == "EXPLAINED_BY_CURRENT_STATE" for item in contradictory
    ),
    "contradictoryUnresolvedCount": sum(
        item["status"] == "UNRESOLVED_EVIDENCE" for item in contradictory
    ),
    "acceptance": acceptance,
    "preexistingArtifactHashesBefore": before_hashes,
  }
  digests["findings"] = _write_text(
      output_dir / "direct_side_findings.md", _findings(summary)
  )
  after_hashes = preexisting_hashes(repo_root)
  if before_hashes != after_hashes:
    raise ValueError(
      "a frozen or prior artifact changed during direct-side training")
  summary["preexistingArtifactHashesAfter"] = after_hashes
  summary["artifactHashes"] = digests
  _write_json(output_dir / "direct_side_summary.json", summary)
  return summary


def main() -> None:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--repo-root", type=Path, default=Path.cwd())
  parser.add_argument(
      "--output-dir", type=Path,
      default=Path("experiments/pareto_direct_side_training"),
  )
  parser.add_argument("--workers", type=int, default=None)
  args = parser.parse_args()
  root = args.repo_root.resolve()
  output = args.output_dir if args.output_dir.is_absolute() else root / args.output_dir
  summary = run_direct_side_training(root, output, args.workers)
  print(json.dumps({
    "outputDir": str(output),
    "selected": summary["selected"]["candidateId"],
    "acceptance": summary["acceptance"]["decision"],
  }, sort_keys=True))


if __name__ == "__main__":
  main()
