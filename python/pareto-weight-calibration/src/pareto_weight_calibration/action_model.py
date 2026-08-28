"""Cost-sensitive bounded participation-boundary model and grouped validation helpers."""

from __future__ import annotations

from dataclasses import asdict, dataclass
import hashlib
import math
from typing import Any, Iterable, Sequence

import numpy as np
from scipy.optimize import minimize
from scipy.special import expit

from pareto_weight_calibration.constraints import MODEL_STRUCTURES
from pareto_weight_calibration.optimizer import fit_constrained_model
from pareto_weight_calibration.scaling import compute_training_scales
from pareto_weight_calibration.types import DomainConfig

DEFAULT = "DEFAULT"
CACHE = "CACHE"
INDETERMINATE = "INDETERMINATE"
TOLERANCE = 1e-12
TEMPERATURE_GRID = (0.5, 1.0, 2.0)
BOUNDARY_L2_GRID = (1e-4, 1e-3, 1e-2)
M4C_L2_GRID = (1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1.0)
FIXED_BOUNDARY_FRACTIONS = (0.25, 0.5, 0.75)

BOUNDARY_STRUCTURES: dict[str, tuple[str, ...]] = {
  "A_PR": ("pRatio", "logR"),
  "B_PR_BODY": ("pRatio", "logR", "body"),
  "C_PR_CONTENTION": ("pRatio", "logR", "contention"),
  "D_PR_BODY_CONTENTION": ("pRatio", "logR", "body", "contention"),
  "E_PR_BODY_BODY_DEFICIT": ("pRatio", "logR", "body", "bodyDeficit"),
  "F_PR_CONTENTION_CONTENTION_DEFICIT": (
    "pRatio",
    "logR",
    "contention",
    "contentionDeficit",
  ),
  "G_PR_BODY_CONTENTION_BOTH_DEFICIT": (
    "pRatio",
    "logR",
    "body",
    "contention",
    "bodyDeficit",
    "contentionDeficit",
  ),
}

INTERACTION_PARENTS: dict[str, tuple[str, ...]] = {
  "bodyDeficit": ("body", "pRatio"),
  "contentionDeficit": ("contention", "pRatio"),
}
RUNTIME_FEATURE_NAMES = frozenset(
    {"pRatio", "logR", "body", "contention", "bodyDeficit", "contentionDeficit"}
)


@dataclass(frozen=True)
class ActionRow:
  pair_id: str
  family_id: str
  current_k: int
  registered_workers: int
  source_count: int
  work_units: int
  productive_handles: float
  body_log: float
  body_cost_ns: float
  contention: float
  observed_action: str
  supported_wrong_action_loss: float
  observed_wrong_action_loss: float
  supported_relative_wrong_action_loss: float
  observed_relative_wrong_action_loss: float
  evidence_weight: float
  family_scale: float
  influence_weight: float
  effective_outcome: str
  evidence_basis: str
  basis_throughput_k: float
  basis_throughput_k_minus_1: float
  basis_delta: float
  basis_uncertainty: float
  runtime_commit: str
  topology_id: str
  k_run_path: str
  k_run_sha256: str
  k_minus_1_run_path: str
  k_minus_1_run_sha256: str

  @property
  def decisive(self) -> bool:
    return self.observed_action in {DEFAULT, CACHE}

  @property
  def y_cache(self) -> float:
    if self.observed_action == CACHE:
      return 1.0
    if self.observed_action == DEFAULT:
      return 0.0
    return 0.5

  @property
  def p_ratio(self) -> float:
    return self.productive_handles / self.registered_workers

  def to_dict(self) -> dict[str, Any]:
    return asdict(self)


@dataclass(frozen=True)
class FeatureScaler:
  names: tuple[str, ...]
  means: tuple[float, ...]
  scales: tuple[float, ...]

  def serialize(self) -> dict[str, Any]:
    return asdict(self)


@dataclass(frozen=True)
class BoundaryFit:
  structure: str
  feature_names: tuple[str, ...]
  l2: float
  temperature: float
  scaler: FeatureScaler
  coefficients: tuple[float, ...]
  success: bool
  objective: float
  iterations: int

  def serialize(self) -> dict[str, Any]:
    return {
      "structure": self.structure,
      "featureNames": list(self.feature_names),
      "l2": self.l2,
      "temperature": self.temperature,
      "scaler": self.scaler.serialize(),
      "coefficients": list(self.coefficients),
      "success": self.success,
      "objective": self.objective,
      "iterations": self.iterations,
    }


def verify_structure_heredity(structure: str) -> None:
  if structure not in BOUNDARY_STRUCTURES:
    raise ValueError(f"unknown boundary structure {structure!r}")
  names = set(BOUNDARY_STRUCTURES[structure])
  for interaction, parents in INTERACTION_PARENTS.items():
    if interaction in names and not set(parents).issubset(names):
      raise ValueError(
          f"{structure}: interaction {interaction} lacks parents {parents}"
      )


def validate_runtime_feature_names(names: Iterable[str]) -> None:
  rejected = sorted(set(names) - RUNTIME_FEATURE_NAMES)
  if rejected:
    raise ValueError(
        "counterfactual, future, withdrawn-arm, or unknown telemetry is not allowed: "
        + ", ".join(rejected)
    )


for _structure_name in BOUNDARY_STRUCTURES:
  verify_structure_heredity(_structure_name)
  validate_runtime_feature_names(BOUNDARY_STRUCTURES[_structure_name])


def feature_values(row: ActionRow) -> dict[str, float]:
  ratio = row.p_ratio
  if not math.isfinite(ratio) or ratio <= 0.0:
    raise ValueError(f"{row.pair_id}: productive-handle ratio must be positive")
  if not 0.0 <= row.contention <= 1.0:
    raise ValueError(f"{row.pair_id}: current contention is outside [0, 1]")
  deficit = 1.0 - ratio
  return {
    "pRatio": ratio,
    "logR": math.log(float(row.registered_workers)),
    "body": row.body_log,
    "contention": row.contention,
    "bodyDeficit": row.body_log * deficit,
    "contentionDeficit": row.contention * deficit,
  }


def fold_influence(rows: Sequence[ActionRow]) -> tuple[
  np.ndarray, dict[str, float]]:
  family_totals: dict[str, float] = {}
  for row in rows:
    family_totals[row.family_id] = family_totals.get(row.family_id,
                                                     0.0) + row.evidence_weight
  scales = {
    family: 1.0 / max(1.0, total) for family, total in family_totals.items()
  }
  return (
    np.asarray([row.evidence_weight * scales[row.family_id] for row in rows]),
    scales,
  )


def fit_scaler(rows: Sequence[ActionRow], structure: str) -> FeatureScaler:
  verify_structure_heredity(structure)
  names = BOUNDARY_STRUCTURES[structure]
  weights, _ = fold_influence(rows)
  matrix = np.asarray(
      [[feature_values(row)[name] for name in names] for row in rows],
      dtype=np.float64
  )
  total = float(np.sum(weights))
  means = np.sum(matrix * weights[:, None], axis=0) / total
  variance = np.sum(weights[:, None] * (matrix - means) ** 2, axis=0) / total
  scales = np.where(np.sqrt(variance) > 1e-12, np.sqrt(variance), 1.0)
  return FeatureScaler(
      names=names,
      means=tuple(float(value) for value in means),
      scales=tuple(float(value) for value in scales),
  )


def design_matrix(rows: Sequence[ActionRow],
    scaler: FeatureScaler) -> np.ndarray:
  raw = np.asarray(
      [[feature_values(row)[name] for name in scaler.names] for row in rows],
      dtype=np.float64,
  )
  normalized = (raw - np.asarray(scaler.means)) / np.asarray(scaler.scales)
  return np.column_stack([np.ones(len(rows)), normalized])


def bounded_mu(eta: np.ndarray | float,
    registered_workers: np.ndarray | float) -> np.ndarray:
  eta_values = np.asarray(eta, dtype=np.float64)
  workers = np.asarray(registered_workers, dtype=np.float64)
  mu = 1.0 + (workers - 1.0) * expit(eta_values)
  if np.any(~np.isfinite(mu)) or np.any(mu < 1.0) or np.any(mu > workers):
    raise ValueError("bounded boundary produced an invalid mu")
  return mu


def fit_boundary(
    rows: Sequence[ActionRow], structure: str, l2: float, temperature: float
) -> BoundaryFit:
  if temperature not in TEMPERATURE_GRID:
    raise ValueError("temperature must come from the frozen grid")
  if l2 not in BOUNDARY_L2_GRID:
    raise ValueError("regularization must come from the frozen grid")
  scaler = fit_scaler(rows, structure)
  x = design_matrix(rows, scaler)
  weights, _ = fold_influence(rows)
  y = np.asarray([row.y_cache for row in rows], dtype=np.float64)
  costs = np.asarray([row.supported_wrong_action_loss for row in rows],
                     dtype=np.float64)
  workers = np.asarray([row.registered_workers for row in rows],
                       dtype=np.float64)
  k = np.asarray([row.current_k for row in rows], dtype=np.float64)
  cost_scale = float(np.sum(weights * costs))
  if cost_scale <= 0.0:
    raise ValueError("training fold has no supported action cost")

  def objective(beta: np.ndarray) -> tuple[float, np.ndarray]:
    eta = x @ beta
    fraction = expit(eta)
    mu = 1.0 + (workers - 1.0) * fraction
    p_cache = expit((k - mu) / temperature)
    expected = costs * ((1.0 - y) * p_cache + y * (1.0 - p_cache))
    primary = float(np.sum(weights * expected) / cost_scale)
    regularized = primary + l2 * float(np.dot(beta[1:], beta[1:]))
    d_loss_d_p = costs * (1.0 - 2.0 * y)
    d_p_d_score = p_cache * (1.0 - p_cache)
    d_score_d_eta = -(workers - 1.0) * fraction * (1.0 - fraction) / temperature
    gradient = x.T @ (weights * d_loss_d_p * d_p_d_score * d_score_d_eta)
    gradient = gradient / cost_scale
    gradient[1:] += 2.0 * l2 * beta[1:]
    return regularized, gradient

  starts = [
    np.concatenate(([intercept], np.zeros(x.shape[1] - 1)))
    for intercept in (-2.0, 0.0, 2.0)
  ]
  best = None
  bounds = [(-12.0, 12.0)] * x.shape[1]
  for start_index, start in enumerate(starts):
    result = minimize(
        fun=lambda beta: objective(beta)[0],
        x0=start,
        jac=lambda beta: objective(beta)[1],
        method="L-BFGS-B",
        bounds=bounds,
        options={"ftol": 1e-13, "gtol": 1e-9, "maxiter": 1000},
    )
    candidate = (
      not bool(result.success),
      float(result.fun),
      start_index,
      np.asarray(result.x, dtype=np.float64),
      bool(result.success),
      int(getattr(result, "nit", 0)),
    )
    if best is None or candidate[:2] < best[:2]:
      best = candidate
  assert best is not None
  if not best[4] or np.any(~np.isfinite(best[3])):
    raise ValueError(
      f"boundary optimizer failed for {structure}@{l2}@{temperature}")
  return BoundaryFit(
      structure=structure,
      feature_names=BOUNDARY_STRUCTURES[structure],
      l2=l2,
      temperature=temperature,
      scaler=scaler,
      coefficients=tuple(float(value) for value in best[3]),
      success=best[4],
      objective=best[1],
      iterations=best[5],
  )


def predict_boundary(fit: BoundaryFit, rows: Sequence[ActionRow]) -> list[
  dict[str, Any]]:
  x = design_matrix(rows, fit.scaler)
  eta = x @ np.asarray(fit.coefficients)
  workers = np.asarray([row.registered_workers for row in rows],
                       dtype=np.float64)
  mu = bounded_mu(eta, workers)
  predictions = []
  for row, eta_value, mu_value in zip(rows, eta, mu, strict=True):
    score = row.current_k - float(mu_value)
    p_cache = float(expit(score / fit.temperature))
    action = CACHE if score > 0.0 else DEFAULT
    predictions.append(
        {
          "pairId": row.pair_id,
          "familyId": row.family_id,
          "currentK": row.current_k,
          "mu": float(mu_value),
          "eta": float(eta_value),
          "score": score,
          "boundaryMargin": abs(score),
          "pCache": p_cache,
          "action": action,
        }
    )
  return predictions


def action_loss(row: ActionRow, predicted_action: str) -> dict[str, Any]:
  if not row.decisive:
    return {
      "correct": None,
      "wrongType": None,
      "supportedLoss": 0.0,
      "observedLoss": 0.0,
      "supportedRelativeLoss": 0.0,
      "observedRelativeLoss": 0.0,
    }
  correct = predicted_action == row.observed_action
  wrong_type = None
  if not correct:
    wrong_type = "FALSE_CACHE" if predicted_action == CACHE else "FALSE_DEFAULT"
  return {
    "correct": correct,
    "wrongType": wrong_type,
    "supportedLoss": 0.0 if correct else row.supported_wrong_action_loss,
    "observedLoss": 0.0 if correct else row.observed_wrong_action_loss,
    "supportedRelativeLoss": (
      0.0 if correct else row.supported_relative_wrong_action_loss
    ),
    "observedRelativeLoss": 0.0 if correct else row.observed_relative_wrong_action_loss,
  }


def expected_supported_action_loss(row: ActionRow, p_cache: float) -> float:
  if not 0.0 <= p_cache <= 1.0:
    raise ValueError("p_cache must be in [0, 1]")
  if not row.decisive:
    return 0.0
  wrong_probability = p_cache if row.observed_action == DEFAULT else 1.0 - p_cache
  return row.influence_weight * row.supported_wrong_action_loss * wrong_probability


def attach_losses(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]]
) -> list[dict[str, Any]]:
  by_id = {row.pair_id: row for row in rows}
  if len(by_id) != len(rows) or len(predictions) != len(rows):
    raise ValueError("prediction/row inventory mismatch")
  result = []
  for prediction in predictions:
    row = by_id[prediction["pairId"]]
    result.append({**prediction, "observedAction": row.observed_action,
                   "loss": action_loss(row, prediction["action"])})
  return result


def _ratio(numerator: float, denominator: float) -> float | None:
  return numerator / denominator if denominator > 0.0 else None


def evaluate_action_predictions(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]]
) -> dict[str, Any]:
  attached = attach_losses(rows, predictions)
  weights, _ = fold_influence(rows)
  total_weight = float(np.sum(weights))
  decisive_indices = [index for index, row in enumerate(rows) if row.decisive]
  decisive_weight = float(np.sum(weights[decisive_indices]))
  defaults = [index for index in decisive_indices if
              rows[index].observed_action == DEFAULT]
  caches = [index for index in decisive_indices if
            rows[index].observed_action == CACHE]
  false_cache = [index for index in decisive_indices if
                 attached[index]["loss"]["wrongType"] == "FALSE_CACHE"]
  false_default = [index for index in decisive_indices if
                   attached[index]["loss"]["wrongType"] == "FALSE_DEFAULT"]

  def weighted_sum(field: str, indices: Sequence[int] | None = None) -> float:
    selected = range(len(rows)) if indices is None else indices
    return math.fsum(
        weights[index] * float(attached[index]["loss"][field]) for index in
        selected)

  family_metrics: dict[str, Any] = {}
  for family_id in sorted({row.family_id for row in rows}):
    indices = [index for index, row in enumerate(rows) if
               row.family_id == family_id]
    fam_weight = float(np.sum(weights[indices]))
    decisive_fam = [index for index in indices if rows[index].decisive]
    decisive_fam_weight = float(np.sum(weights[decisive_fam]))
    family_metrics[family_id] = {
      "rowCount": len(indices),
      "decisiveCount": len(decisive_fam),
      "supportedRelativeRegret": weighted_sum("supportedRelativeLoss",
                                              indices) / fam_weight,
      "supportedAbsoluteRegret": weighted_sum("supportedLoss",
                                              indices) / fam_weight,
      "weightedActionAccuracy": (
        math.fsum(
            weights[index] * float(attached[index]["loss"]["correct"])
            for index in decisive_fam
        )
        / decisive_fam_weight
        if decisive_fam_weight > 0.0
        else None
      ),
      "wrongActionCount": sum(
          not attached[index]["loss"]["correct"] for index in decisive_fam),
    }
  family_regrets = [metrics["supportedRelativeRegret"] for metrics in
                    family_metrics.values()]

  def error_summary(indices: Sequence[int], possible_count: int) -> dict[
    str, Any]:
    return {
      "count": len(indices),
      "rate": _ratio(len(indices), possible_count),
      "supportedRelativeRegret": weighted_sum("supportedRelativeLoss",
                                              indices) / total_weight,
      "supportedAbsoluteRegret": weighted_sum("supportedLoss",
                                              indices) / total_weight,
      "observedRelativeRegret": weighted_sum("observedRelativeLoss",
                                             indices) / total_weight,
      "largestSingleSupportedRelativeLoss": max(
          (attached[index]["loss"]["supportedRelativeLoss"] for index in
           indices),
          default=0.0,
      ),
      "largestSingleSupportedLoss": max(
          (attached[index]["loss"]["supportedLoss"] for index in indices),
          default=0.0
      ),
    }

  margin_buckets = {
    "0-0.5": [index for index, item in enumerate(attached) if
              item.get("boundaryMargin", math.inf) <= 0.5],
    "0.5-1": [index for index, item in enumerate(attached) if
              0.5 < item.get("boundaryMargin", math.inf) <= 1.0],
    "1-3": [index for index, item in enumerate(attached) if
            1.0 < item.get("boundaryMargin", math.inf) <= 3.0],
    "3+": [index for index, item in enumerate(attached) if
           item.get("boundaryMargin", -math.inf) > 3.0],
  }
  return {
    "rowCount": len(rows),
    "decisiveCount": len(decisive_indices),
    "indeterminateCount": len(rows) - len(decisive_indices),
    "familyCount": len(family_metrics),
    "totalInfluence": total_weight,
    "supportedRelativeRegret": weighted_sum(
      "supportedRelativeLoss") / total_weight,
    "supportedAbsoluteRegret": weighted_sum("supportedLoss") / total_weight,
    "observedRelativeRegret": weighted_sum(
      "observedRelativeLoss") / total_weight,
    "familyBalancedSupportedRelativeRegret": float(np.mean(family_regrets)),
    "worstFamilySupportedRelativeRegret": max(family_regrets),
    "weightedActionAccuracy": math.fsum(
        weights[index] * float(attached[index]["loss"]["correct"])
        for index in decisive_indices
    )
                              / decisive_weight,
    "defaultAccuracy": _ratio(
        math.fsum(
            weights[index] * float(attached[index]["loss"]["correct"])
            for index in defaults
        ),
        float(np.sum(weights[defaults])),
    ),
    "cacheAccuracy": _ratio(
        math.fsum(
            weights[index] * float(attached[index]["loss"]["correct"])
            for index in caches
        ),
        float(np.sum(weights[caches])),
    ),
    "falseCache": error_summary(false_cache, len(defaults)),
    "falseDefault": error_summary(false_default, len(caches)),
    "families": family_metrics,
    "boundaryMarginBuckets": {
      name: _bucket_metrics(rows, attached, weights, indices)
      for name, indices in margin_buckets.items()
    },
  }


def _bucket_metrics(
    rows: Sequence[ActionRow],
    attached: Sequence[dict[str, Any]],
    weights: np.ndarray,
    indices: Sequence[int],
) -> dict[str, Any]:
  decisive = [index for index in indices if rows[index].decisive]
  if not decisive:
    return {"rowCount": len(indices), "decisiveCount": 0,
            "weightedActionAccuracy": None, "supportedRelativeRegret": 0.0,
            "falseCacheSupportedRegret": 0.0,
            "falseDefaultSupportedRegret": 0.0}
  total = float(np.sum(weights[indices]))
  decisive_total = float(np.sum(weights[decisive]))
  return {
    "rowCount": len(indices),
    "decisiveCount": len(decisive),
    "weightedActionAccuracy": math.fsum(
        weights[i] * float(attached[i]["loss"]["correct"]) for i in
        decisive) / decisive_total,
    "supportedRelativeRegret": math.fsum(
        weights[i] * float(attached[i]["loss"]["supportedRelativeLoss"]) for i
        in indices) / total,
    "falseCacheSupportedRegret": math.fsum(
        weights[i] * float(attached[i]["loss"]["supportedRelativeLoss"]) for i
        in indices if
        attached[i]["loss"]["wrongType"] == "FALSE_CACHE") / total,
    "falseDefaultSupportedRegret": math.fsum(
        weights[i] * float(attached[i]["loss"]["supportedRelativeLoss"]) for i
        in indices if
        attached[i]["loss"]["wrongType"] == "FALSE_DEFAULT") / total,
  }


def grouped_family_folds(families: Iterable[str], fold_count: int = 4) -> list[
  tuple[str, ...]]:
  ordered = sorted(set(families),
                   key=lambda name: (hashlib.sha256(name.encode()).hexdigest(),
                                     name))
  count = min(fold_count, len(ordered))
  return [tuple(ordered[index::count]) for index in range(count)]


def predict_fixed_boundary(
    rows: Sequence[ActionRow], fraction: float
) -> list[dict[str, Any]]:
  if fraction not in FIXED_BOUNDARY_FRACTIONS:
    raise ValueError("fixed boundary fraction must come from the frozen grid")
  result = []
  for row in rows:
    mu = 1.0 + (row.registered_workers - 1.0) * fraction
    score = row.current_k - mu
    result.append(
        {
          "pairId": row.pair_id,
          "familyId": row.family_id,
          "currentK": row.current_k,
          "mu": mu,
          "score": score,
          "boundaryMargin": abs(score),
          "action": CACHE if score > 0.0 else DEFAULT,
        }
    )
  return result


def inner_select_fixed_boundary(rows: Sequence[ActionRow]) -> dict[str, Any]:
  folds = grouped_family_folds(row.family_id for row in rows)
  candidates = []
  for fraction in FIXED_BOUNDARY_FRACTIONS:
    validation_rows = []
    predictions = []
    for held_families in folds:
      validation = [row for row in rows if row.family_id in held_families]
      validation_rows.extend(validation)
      predictions.extend(predict_fixed_boundary(validation, fraction))
    ordered = sorted(
        zip(validation_rows, predictions, strict=True),
        key=lambda item: item[0].pair_id
    )
    validation_rows = [item[0] for item in ordered]
    predictions = [item[1] for item in ordered]
    metrics = evaluate_action_predictions(validation_rows, predictions)
    candidates.append(
        {
          "candidateId": f"FIXED_FRACTION@{fraction:g}",
          "fraction": fraction,
          "metrics": metrics,
        }
    )
  selected = min(
      candidates,
      key=lambda candidate: (
        candidate["metrics"]["supportedRelativeRegret"],
        candidate["metrics"]["worstFamilySupportedRelativeRegret"],
        candidate["metrics"]["falseDefault"]["supportedRelativeRegret"],
        candidate["metrics"]["falseCache"]["supportedRelativeRegret"],
        candidate["candidateId"],
      ),
  )
  return {
    "folds": [list(fold) for fold in folds],
    "candidates": candidates,
    "selected": selected,
  }


def _candidate_key(result: dict[str, Any]) -> tuple[Any, ...]:
  metrics = result["metrics"]
  return (
    metrics["supportedRelativeRegret"],
    metrics["worstFamilySupportedRelativeRegret"],
    metrics["falseDefault"]["supportedRelativeRegret"],
    metrics["falseCache"]["supportedRelativeRegret"],
    len(BOUNDARY_STRUCTURES[result["structure"]]),
    -result["l2"],
    result["candidateId"],
  )


def complexity_admissible(
    lower_pooled_regret: bool,
    worst_family_guard: bool,
    improved_family_count: int,
) -> bool:
  return lower_pooled_regret and worst_family_guard and improved_family_count > 1


def inner_validate_boundary(rows: Sequence[ActionRow]) -> dict[str, Any]:
  folds = grouped_family_folds(row.family_id for row in rows)
  candidates = []
  for structure in BOUNDARY_STRUCTURES:
    for l2 in BOUNDARY_L2_GRID:
      for temperature in TEMPERATURE_GRID:
        predictions = []
        validation_rows = []
        for held_families in folds:
          train = [row for row in rows if row.family_id not in held_families]
          validation = [row for row in rows if row.family_id in held_families]
          fit = fit_boundary(train, structure, l2, temperature)
          predictions.extend(predict_boundary(fit, validation))
          validation_rows.extend(validation)
        ordered = sorted(zip(validation_rows, predictions, strict=True),
                         key=lambda item: item[0].pair_id)
        validation_rows = [item[0] for item in ordered]
        predictions = [item[1] for item in ordered]
        metrics = evaluate_action_predictions(validation_rows, predictions)
        candidates.append(
            {
              "candidateId": f"{structure}@l2={l2:.0e}@temperature={temperature:g}",
              "structure": structure,
              "l2": l2,
              "temperature": temperature,
              "metrics": metrics,
            }
        )
  best_by_structure = {
    structure: min(
        (candidate for candidate in candidates if
         candidate["structure"] == structure),
        key=_candidate_key,
    )
    for structure in BOUNDARY_STRUCTURES
  }
  complexity_groups: dict[int, list[dict[str, Any]]] = {}
  for candidate in best_by_structure.values():
    complexity_groups.setdefault(
      len(BOUNDARY_STRUCTURES[candidate["structure"]]), []).append(candidate)
  ordered_complexities = sorted(complexity_groups)
  incumbent = min(complexity_groups[ordered_complexities[0]],
                  key=_candidate_key)
  admissions = []
  for complexity in ordered_complexities[1:]:
    candidate = min(complexity_groups[complexity], key=_candidate_key)
    candidate_families = candidate["metrics"]["families"]
    incumbent_families = incumbent["metrics"]["families"]
    improved = sum(
        candidate_families[family]["supportedRelativeRegret"]
        < incumbent_families[family]["supportedRelativeRegret"] - TOLERANCE
        for family in candidate_families
    )
    lower_pooled = candidate["metrics"]["supportedRelativeRegret"] < \
                   incumbent["metrics"]["supportedRelativeRegret"] - TOLERANCE
    guarded_worst = candidate["metrics"][
                      "worstFamilySupportedRelativeRegret"] <= \
                    incumbent["metrics"][
                      "worstFamilySupportedRelativeRegret"] + TOLERANCE
    admitted = complexity_admissible(lower_pooled, guarded_worst, improved)
    admissions.append(
        {
          "incumbent": incumbent["candidateId"],
          "candidate": candidate["candidateId"],
          "lowerPooledRegret": lower_pooled,
          "worstFamilyGuard": guarded_worst,
          "improvedFamilyCount": improved,
          "admitted": admitted,
        }
    )
    if admitted:
      incumbent = candidate
  return {
    "folds": [list(fold) for fold in folds],
    "candidates": candidates,
    "bestByStructure": best_by_structure,
    "admissions": admissions,
    "selected": incumbent,
  }


def _m4c_matrix(rows: Sequence[ActionRow]) -> np.ndarray:
  result = []
  for row in rows:
    q = row.productive_handles / (row.current_k * (row.current_k - 1.0))
    result.append(
        [q, row.contention * q, row.body_log * q, row.registered_workers * q,
         -1.0, -row.contention, -row.body_log, -float(row.registered_workers)])
  return np.asarray(result, dtype=np.float64)


def fit_m4c(rows: Sequence[ActionRow], l2: float, domain: DomainConfig) -> dict[
  str, Any]:
  x = _m4c_matrix(rows)
  y = np.asarray([row.y_cache for row in rows], dtype=np.float64)
  weights, _ = fold_influence(rows)
  active = MODEL_STRUCTURES["M4-C"]
  scales = compute_training_scales(x, weights, active_indices=active,
                                   constant_column_index=4)
  fit = fit_constrained_model(x, y, weights, scales, domain, "M4-C", l2, active)
  if not fit.success or fit.constraint_violation > 1e-9:
    raise ValueError(f"M4-C fit failed at lambda={l2}")
  return {"l2": l2, "weights": fit.w_phys_full.tolist(),
          "scales": scales.tolist()}


def predict_m4c(fit: dict[str, Any], rows: Sequence[ActionRow]) -> list[
  dict[str, Any]]:
  margins = _m4c_matrix(rows) @ np.asarray(fit["weights"], dtype=np.float64)
  return [
    {"pairId": row.pair_id, "familyId": row.family_id,
     "currentK": row.current_k, "margin": float(margin),
     "action": CACHE if margin > 0.0 else DEFAULT}
    for row, margin in zip(rows, margins, strict=True)
  ]


def inner_select_m4c(rows: Sequence[ActionRow], domain: DomainConfig) -> dict[
  str, Any]:
  folds = grouped_family_folds(row.family_id for row in rows)
  candidates = []
  for l2 in M4C_L2_GRID:
    validation_rows = []
    predictions = []
    for held_families in folds:
      train = [row for row in rows if row.family_id not in held_families]
      validation = [row for row in rows if row.family_id in held_families]
      fit = fit_m4c(train, l2, domain)
      validation_rows.extend(validation)
      predictions.extend(predict_m4c(fit, validation))
    ordered = sorted(zip(validation_rows, predictions, strict=True),
                     key=lambda item: item[0].pair_id)
    validation_rows = [item[0] for item in ordered]
    predictions = [item[1] for item in ordered]
    metrics = evaluate_action_predictions(validation_rows, predictions)
    candidates.append(
        {"candidateId": f"M4-C@l2={l2:.0e}", "l2": l2, "metrics": metrics})
  selected = min(
      candidates,
      key=lambda candidate: (
        candidate["metrics"]["supportedRelativeRegret"],
        candidate["metrics"]["worstFamilySupportedRelativeRegret"],
        candidate["metrics"]["falseDefault"]["supportedRelativeRegret"],
        candidate["metrics"]["falseCache"]["supportedRelativeRegret"],
        -candidate["l2"],
        candidate["candidateId"],
      ),
  )
  return {"folds": [list(fold) for fold in folds], "candidates": candidates,
          "selected": selected}
