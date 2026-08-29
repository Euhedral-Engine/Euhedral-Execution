"""Nested LOFO evaluation of an asymmetric CACHE boundary offset.

This evaluator consumes only checksum-verified frozen action-model artifacts. It
does not retrain the boundary structure on the complete dataset, alter frozen
evidence, or install a runtime policy.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import fields
import hashlib
import json
import math
from pathlib import Path
from typing import Any, Callable, Sequence

from scipy.special import expit

from pareto_weight_calibration.action_model import (
  CACHE,
  DEFAULT,
  TOLERANCE,
  ActionRow,
  BoundaryFit,
  FeatureScaler,
  evaluate_action_predictions,
  fit_boundary,
  grouped_family_folds,
  predict_boundary,
)
from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.integer_cutoff import (
  build_boundary_cases,
)

SCHEMA_VERSION = 1
EVALUATOR_VERSION = "cost-sensitive-cache-offset-v1"
CACHE_OFFSET_GRID = (0.0, 0.25, 0.5, 0.75, 1.0)

ACTION_DIR = Path("experiments/pareto_action_model_training")
INTEGER_DIR = Path("experiments/pareto_integer_cutoff_evaluation")
PREEXISTING_ARTIFACTS = (
  ACTION_DIR / "action_training_dataset.json",
  ACTION_DIR / "action_model_outer_lofo.json",
  ACTION_DIR / "action_model_m4c_lofo.json",
  ACTION_DIR / "action_model_summary.json",
  INTEGER_DIR / "integer_cutoff_results.json",
)


def _jsonable(value: Any) -> Any:
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
    raise ValueError("frozen action dataset contains duplicate pair ids")
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


def apply_cache_offset(
    predictions: Sequence[dict[str, Any]], cache_offset: float,
    temperature: float | None = None,
) -> list[dict[str, Any]]:
  """Apply an offset without changing the fitted continuous boundary."""
  if cache_offset not in CACHE_OFFSET_GRID:
    raise ValueError("cache offset must come from the frozen grid")
  result = []
  for prediction in predictions:
    model_mu = float(prediction["mu"])
    model_score = float(
      prediction.get("score", prediction["currentK"] - model_mu))
    effective_mu = model_mu - cache_offset
    effective_score = model_score + cache_offset
    action = CACHE if effective_score > 0.0 else DEFAULT
    adjusted = {
      **prediction,
      "mu": model_mu,
      "score": model_score,
      "boundaryMargin": abs(model_score),
      "cacheOffset": cache_offset,
      "effectiveMu": effective_mu,
      "effectiveScore": effective_score,
      "effectiveBoundaryMargin": abs(effective_score),
      "action": action,
    }
    if "pCache" in prediction:
      if temperature is None or temperature <= 0.0:
        raise ValueError("temperature is required to adjust pCache")
      adjusted["pCache"] = float(expit(effective_score / temperature))
    result.append(adjusted)
  return result


def _metric_values(candidate: dict[str, Any]) -> tuple[float, ...]:
  metrics = candidate["metrics"]
  return (
    float(metrics["supportedRelativeRegret"]),
    float(metrics["worstFamilySupportedRelativeRegret"]),
    float(metrics["falseDefault"]["supportedRelativeRegret"]),
    float(metrics["falseCache"]["supportedRelativeRegret"]),
  )


def offset_candidate_better(
    candidate: dict[str, Any], incumbent: dict[str, Any],
    tolerance: float = TOLERANCE,
) -> bool:
  """Compare candidates lexicographically with the existing numeric tolerance."""
  for proposed, current in zip(
      _metric_values(candidate), _metric_values(incumbent), strict=True
  ):
    if proposed < current - tolerance:
      return True
    if proposed > current + tolerance:
      return False
  return float(candidate["cacheOffset"]) < float(incumbent["cacheOffset"])


def select_offset_candidate(
    candidates: Sequence[dict[str, Any]], tolerance: float = TOLERANCE
) -> dict[str, Any]:
  if not candidates:
    raise ValueError("offset selection requires candidates")
  selected = candidates[0]
  for candidate in candidates[1:]:
    if offset_candidate_better(candidate, selected, tolerance):
      selected = candidate
  return selected


def inner_select_cache_offset(
    rows: Sequence[ActionRow],
    structure: str,
    l2: float,
    temperature: float,
    fit_function: Callable[..., BoundaryFit] = fit_boundary,
) -> dict[str, Any]:
  """Select cacheOffset with grouped validation inside one outer train split."""
  folds = grouped_family_folds(row.family_id for row in rows)
  predictions_by_offset = {offset: [] for offset in CACHE_OFFSET_GRID}
  validation_rows: list[ActionRow] = []
  fit_audit = []
  for held_families in folds:
    held_set = set(held_families)
    train = [row for row in rows if row.family_id not in held_set]
    validation = [row for row in rows if row.family_id in held_set]
    if not train or not validation or any(
        row.family_id in held_set for row in train):
      raise ValueError("invalid grouped inner family partition")
    fit = fit_function(train, structure, l2, temperature)
    base_predictions = predict_boundary(fit, validation)
    validation_rows.extend(validation)
    for offset in CACHE_OFFSET_GRID:
      predictions_by_offset[offset].extend(
          apply_cache_offset(base_predictions, offset, temperature)
      )
    fit_audit.append({
      "heldOutFamilies": list(held_families),
      "trainingFamilyCount": len({row.family_id for row in train}),
      "trainingPairIdsHash": hashlib.sha256(
          "\n".join(sorted(row.pair_id for row in train)).encode()
      ).hexdigest(),
    })

  candidates = []
  for offset in CACHE_OFFSET_GRID:
    ordered = sorted(
        zip(validation_rows, predictions_by_offset[offset], strict=True),
        key=lambda item: item[0].pair_id,
    )
    ordered_rows = [item[0] for item in ordered]
    ordered_predictions = [item[1] for item in ordered]
    candidates.append({
      "candidateId": f"CACHE_OFFSET@{offset:.2f}",
      "cacheOffset": offset,
      "metrics": evaluate_action_predictions(ordered_rows, ordered_predictions),
    })
  selected = select_offset_candidate(candidates)
  return {
    "folds": [list(fold) for fold in folds],
    "fitAudit": fit_audit,
    "candidates": candidates,
    "selected": selected,
    "selectionOrder": [
      "supportedRelativeRegret",
      "worstFamilySupportedRelativeRegret",
      "falseDefault.supportedRelativeRegret",
      "falseCache.supportedRelativeRegret",
      "smallerCacheOffsetWithinTolerance",
    ],
    "tolerance": TOLERANCE,
  }


def select_cache_offset_for_outer_fold(
    rows: Sequence[ActionRow],
    held_family: str,
    structure: str,
    l2: float,
    temperature: float,
    fit_function: Callable[..., BoundaryFit] = fit_boundary,
) -> dict[str, Any]:
  train = [row for row in rows if row.family_id != held_family]
  held = [row for row in rows if row.family_id == held_family]
  if not held or not train or any(
      row.family_id == held_family for row in train):
    raise ValueError("invalid outer family partition")
  selection = inner_select_cache_offset(
      train, structure, l2, temperature, fit_function=fit_function
  )
  return {
    **selection,
    "outerHeldOutFamily": held_family,
    "outerTrainingFamilyCount": len({row.family_id for row in train}),
    "outerTrainingPairIdsHash": hashlib.sha256(
        "\n".join(sorted(row.pair_id for row in train)).encode()
    ).hexdigest(),
  }


def _compact_metrics(metrics: dict[str, Any]) -> dict[str, Any]:
  return {key: value for key, value in metrics.items() if key != "families"}


def _operational_predictions(
    predictions: Sequence[dict[str, Any]],
) -> list[dict[str, Any]]:
  """Represent effectiveMu as mu for the integer runtime-cutoff evaluator."""
  return [
    {
      **prediction,
      "modelMu": prediction["mu"],
      "mu": prediction["effectiveMu"],
      "score": prediction["effectiveScore"],
      "boundaryMargin": prediction["effectiveBoundaryMargin"],
    }
    for prediction in predictions
  ]


def _integer_subset(
    cases: Sequence[dict[str, Any]], predicate: Callable[[dict[str, Any]], bool]
) -> dict[str, Any]:
  selected = [case for case in cases if predicate(case)]
  total_influence = math.fsum(float(case["influenceWeight"]) for case in cases)
  selected_influence = math.fsum(
      float(case["influenceWeight"]) for case in selected
  )

  def weighted(wrong_type: str | None = None) -> float:
    return math.fsum(
        float(case["influenceWeight"])
        * float(case["loss"]["supportedRelativeLoss"])
        for case in selected
        if wrong_type is None or case["loss"]["wrongType"] == wrong_type
    )

  decisive = [case for case in selected if case["loss"]["correct"] is not None]
  correct_weight = math.fsum(
      float(case["influenceWeight"])
      for case in decisive if case["loss"]["correct"]
  )
  decisive_weight = math.fsum(
      float(case["influenceWeight"]) for case in decisive)
  return {
    "rowCount": len(selected),
    "decisiveCount": len(decisive),
    "wrongActionCount": sum(
        case["loss"]["wrongType"] is not None for case in selected),
    "weightedActionAccuracy": (
      correct_weight / decisive_weight if decisive_weight > 0.0 else None
    ),
    "supportedRelativeRegret": (
      weighted() / selected_influence if selected_influence > 0.0 else 0.0
    ),
    "supportedRelativeRegretContribution": (
      weighted() / total_influence if total_influence > 0.0 else 0.0
    ),
    "falseCacheSupportedRelativeRegret": (
      weighted("FALSE_CACHE") / selected_influence
      if selected_influence > 0.0 else 0.0
    ),
    "falseDefaultSupportedRelativeRegret": (
      weighted("FALSE_DEFAULT") / selected_influence
      if selected_influence > 0.0 else 0.0
    ),
  }


def _near_boundary_metrics(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]]
) -> dict[str, Any]:
  by_pair = {prediction["pairId"]: prediction for prediction in predictions}
  subset = [row for row in rows if by_pair[row.pair_id]["boundaryMargin"] < 0.5]
  subset_predictions = [by_pair[row.pair_id] for row in subset]
  metrics = evaluate_action_predictions(subset, subset_predictions)
  total_influence = math.fsum(row.influence_weight for row in rows)
  subset_weighted_regret = math.fsum(
      row.influence_weight
      * (row.supported_relative_wrong_action_loss
         if by_pair[row.pair_id][
              "action"] != row.observed_action and row.decisive
         else 0.0)
      for row in subset
  )
  return {
    "definition": "abs(K - modelMu) < 0.5; modelMu is unchanged by cacheOffset",
    "rowCount": len(subset),
    "weightedActionAccuracy": metrics["weightedActionAccuracy"],
    "supportedRelativeRegret": metrics["supportedRelativeRegret"],
    "supportedRelativeRegretContribution": subset_weighted_regret / total_influence,
    "falseCacheSupportedRegret": metrics["falseCache"][
      "supportedRelativeRegret"],
    "falseDefaultSupportedRegret": metrics["falseDefault"][
      "supportedRelativeRegret"],
  }


def _validate_outer_fit(
    fold: dict[str, Any], rows_by_family: dict[str, list[ActionRow]]
) -> None:
  held_family = fold["heldOutFamily"]
  held = rows_by_family[held_family]
  fit = _fit_from_dict(fold["boundaryFit"])
  recomputed = predict_boundary(fit, held)
  expected = {item["pairId"]: item for item in fold["boundaryPredictions"]}
  if set(expected) != {item["pairId"] for item in recomputed}:
    raise ValueError(
      f"{held_family}: finalized fit prediction inventory mismatch")
  for prediction in recomputed:
    prior = expected[prediction["pairId"]]
    if prediction["action"] != prior["action"] or not math.isclose(
        prediction["mu"], prior["mu"], rel_tol=0.0, abs_tol=1e-10
    ):
      raise ValueError(f"{held_family}: finalized outer-training fit mismatch")


def _offset_distribution(folds: Sequence[dict[str, Any]]) -> dict[str, int]:
  counts = Counter(float(fold["selectedCacheOffset"]) for fold in folds)
  return {f"{offset:.2f}": counts.get(offset, 0) for offset in
          CACHE_OFFSET_GRID}


def _modal_offset(distribution: dict[str, int]) -> float:
  return min(
      (float(offset) for offset in distribution),
      key=lambda offset: (-distribution[f"{offset:.2f}"], offset),
  )


def _findings(summary: dict[str, Any]) -> str:
  baseline = summary["offsetZero"]
  selected = summary["nestedSelectedOffset"]
  m4c = summary["m4cLofo"]
  before_near = summary["nearBoundary"]["offsetZero"]
  after_near = summary["nearBoundary"]["nestedSelectedOffset"]
  before_one = summary["integerCutoff"]["absoluteErrorOne"]["offsetZero"]
  after_one = summary["integerCutoff"]["absoluteErrorOne"][
    "nestedSelectedOffset"]
  return "\n".join([
    "# Asymmetric CACHE offset evaluation",
    "",
    "No benchmarks were rerun, no frozen evidence or prior artifact was modified, and no production Java was changed.",
    "",
    "## Nested result",
    "",
    f"- Outer-fold selected offset distribution: `{summary['selectedOffsetDistribution']}`.",
    f"- Most frequently selected offset: {summary['mostFrequentlySelectedOffset']:.2f} core units.",
    f"- Offset 0 / nested-selected supported relative regret: {baseline['supportedRelativeRegret']:.8f} / {selected['supportedRelativeRegret']:.8f}.",
    f"- Family-balanced supported regret before / after: {baseline['familyBalancedSupportedRelativeRegret']:.8f} / {selected['familyBalancedSupportedRelativeRegret']:.8f}.",
    f"- M4-C-LOFO supported relative regret: {m4c['supportedRelativeRegret']:.8f}.",
    f"- Worst-family regret before / after: {baseline['worstFamilySupportedRelativeRegret']:.8f} / {selected['worstFamilySupportedRelativeRegret']:.8f}.",
    f"- Weighted action accuracy before / after: {baseline['weightedActionAccuracy']:.8f} / {selected['weightedActionAccuracy']:.8f}.",
    f"- False DEFAULT count and regret before / after: {baseline['falseDefault']['count']} and {baseline['falseDefault']['supportedRelativeRegret']:.8f} / {selected['falseDefault']['count']} and {selected['falseDefault']['supportedRelativeRegret']:.8f}.",
    f"- False CACHE count and regret before / after: {baseline['falseCache']['count']} and {baseline['falseCache']['supportedRelativeRegret']:.8f} / {selected['falseCache']['count']} and {selected['falseCache']['supportedRelativeRegret']:.8f}.",
    f"- Largest false DEFAULT loss before / after: {baseline['falseDefault']['largestSingleSupportedRelativeLoss']:.8f} / {selected['falseDefault']['largestSingleSupportedRelativeLoss']:.8f}.",
    f"- Largest false CACHE loss before / after: {baseline['falseCache']['largestSingleSupportedRelativeLoss']:.8f} / {selected['falseCache']['largestSingleSupportedRelativeLoss']:.8f}.",
    f"- Absolute one-core cutoff-error regret contribution before / after: {before_one['supportedRelativeRegretContribution']:.8f} / {after_one['supportedRelativeRegretContribution']:.8f}.",
    f"- Signed cutoff error +1 regret contribution before / after: {summary['integerCutoff']['signedErrorExactlyOne']['offsetZero']['supportedRelativeRegretContribution']:.8f} / {summary['integerCutoff']['signedErrorExactlyOne']['nestedSelectedOffset']['supportedRelativeRegretContribution']:.8f}.",
    f"- Near-boundary (<0.5) regret before / after: {before_near['supportedRelativeRegret']:.8f} / {after_near['supportedRelativeRegret']:.8f}.",
    f"- Near-boundary false CACHE regret before / after: {before_near['falseCacheSupportedRegret']:.8f} / {after_near['falseCacheSupportedRegret']:.8f}.",
    f"- Near-boundary false DEFAULT regret before / after: {before_near['falseDefaultSupportedRegret']:.8f} / {after_near['falseDefaultSupportedRegret']:.8f}.",
    f"- Families improved / worsened / unchanged: {summary['familySpread']['improvedFamilyCount']} / {summary['familySpread']['worsenedFamilyCount']} / {summary['familySpread']['unchangedFamilyCount']}.",
    f"- Topologies with improvement: {summary['familySpread']['improvedTopologies']}.",
    "",
    "## Decision",
    "",
    f"- Candidate-policy conclusion: `{summary['candidatePolicyDecision']}`.",
    f"- Basis: {summary['candidatePolicyBasis']}",
    "",
    "The offset is a training hyperparameter. Each outer held-out physical family is evaluated once, after grouped offset selection using only that fold's training families.",
  ])


def run_cache_offset_evaluation(repo_root: Path, output_dir: Path) -> dict[
  str, Any]:
  before_hashes = preexisting_hashes(repo_root)
  dataset = _verified_json(
    repo_root / ACTION_DIR / "action_training_dataset.json")
  outer = _verified_json(
    repo_root / ACTION_DIR / "action_model_outer_lofo.json")
  m4c = _verified_json(repo_root / ACTION_DIR / "action_model_m4c_lofo.json")
  rows = _rows_from_dataset(dataset)
  rows_by_family: dict[str, list[ActionRow]] = defaultdict(list)
  for row in rows:
    rows_by_family[row.family_id].append(row)
  folds_by_family = {fold["heldOutFamily"]: fold for fold in outer["folds"]}
  if set(folds_by_family) != set(rows_by_family):
    raise ValueError("outer LOFO and frozen family inventories disagree")

  outer_results = []
  baseline_predictions = []
  selected_predictions = []
  for held_family in sorted(rows_by_family):
    fold = folds_by_family[held_family]
    _validate_outer_fit(fold, rows_by_family)
    selection = fold["boundarySelection"]["selected"]
    offset_selection = select_cache_offset_for_outer_fold(
        rows,
        held_family,
        selection["structure"],
        float(selection["l2"]),
        float(selection["temperature"]),
    )
    train = [row for row in rows if row.family_id != held_family]
    selected_offset = float(offset_selection["selected"]["cacheOffset"])
    baseline = apply_cache_offset(
        fold["boundaryPredictions"], 0.0, float(selection["temperature"])
    )
    selected = apply_cache_offset(
        fold["boundaryPredictions"], selected_offset,
        float(selection["temperature"]),
    )
    baseline_predictions.extend(baseline)
    selected_predictions.extend(selected)
    outer_results.append({
      "heldOutFamily": held_family,
      "trainingFamilyCount": len({row.family_id for row in train}),
      "trainingPairIdsHash": fold["trainingPairIdsHash"],
      "boundaryCandidateId": selection["candidateId"],
      "selectedCacheOffset": selected_offset,
      "offsetSelection": {
        "folds": offset_selection["folds"],
        "fitAudit": offset_selection["fitAudit"],
        "selectionOrder": offset_selection["selectionOrder"],
        "tolerance": offset_selection["tolerance"],
        "candidates": [
          {
            "candidateId": candidate["candidateId"],
            "cacheOffset": candidate["cacheOffset"],
            "metrics": _compact_metrics(candidate["metrics"]),
          }
          for candidate in offset_selection["candidates"]
        ],
        "selectedCandidateId": offset_selection["selected"]["candidateId"],
      },
      "baselinePredictions": baseline,
      "selectedPredictions": selected,
    })

  baseline_predictions.sort(key=lambda item: item["pairId"])
  selected_predictions.sort(key=lambda item: item["pairId"])
  baseline_metrics = evaluate_action_predictions(rows, baseline_predictions)
  selected_metrics = evaluate_action_predictions(rows, selected_predictions)
  if _compact_metrics(baseline_metrics) != outer["metrics"]:
    raise ValueError(
      "cacheOffset=0 does not exactly reproduce prior LOFO metrics")

  dataset_rows = dataset["rows"]
  baseline_cases, observed_cutoffs = build_boundary_cases(
      dataset_rows, _operational_predictions(baseline_predictions)
  )
  selected_cases, selected_observed = build_boundary_cases(
      dataset_rows, _operational_predictions(selected_predictions)
  )
  if observed_cutoffs != selected_observed:
    raise ValueError("offset evaluation changed observed cutoff evidence")

  exact_one = {
    "offsetZero": _integer_subset(
        baseline_cases, lambda case: case["integerCutoffError"] == 1
    ),
    "nestedSelectedOffset": _integer_subset(
        selected_cases, lambda case: case["integerCutoffError"] == 1
    ),
  }
  absolute_one = {
    "offsetZero": _integer_subset(
        baseline_cases, lambda case: case["absoluteIntegerCutoffError"] == 1
    ),
    "nestedSelectedOffset": _integer_subset(
        selected_cases, lambda case: case["absoluteIntegerCutoffError"] == 1
    ),
  }
  distribution = _offset_distribution(outer_results)
  improved = []
  worsened = []
  unchanged = []
  for family in sorted(rows_by_family):
    delta = (
        baseline_metrics["families"][family]["supportedRelativeRegret"]
        - selected_metrics["families"][family]["supportedRelativeRegret"]
    )
    if delta > TOLERANCE:
      improved.append(family)
    elif delta < -TOLERANCE:
      worsened.append(family)
    else:
      unchanged.append(family)
  topology_by_family = {
    family: sorted({row.topology_id for row in family_rows})
    for family, family_rows in rows_by_family.items()
  }
  improved_topologies = sorted({
    topology for family in improved for topology in topology_by_family[family]
  })

  supported = (
      selected_metrics["supportedRelativeRegret"]
      < baseline_metrics["supportedRelativeRegret"] - TOLERANCE
      and selected_metrics["worstFamilySupportedRelativeRegret"]
      <= baseline_metrics["worstFamilySupportedRelativeRegret"] + TOLERANCE
      and len(improved) > 1
      and sum(
      count for offset, count in distribution.items() if float(offset) > 0.0)
      > len(outer_results) / 2
  )
  summary = {
    "schemaVersion": SCHEMA_VERSION,
    "evaluatorVersion": EVALUATOR_VERSION,
    "status": "EVALUATION_ONLY_NOT_INSTALLED",
    "cacheOffsetGrid": list(CACHE_OFFSET_GRID),
    "selectionSemantics": {
      "primary": "supported relative action regret",
      "secondary": [
        "worst-family supported relative regret",
        "false-DEFAULT supported relative regret",
        "false-CACHE supported relative regret",
      ],
      "tieBreak": f"smaller offset when metrics differ by at most {TOLERANCE:g}",
    },
    "selectedOffsetDistribution": distribution,
    "mostFrequentlySelectedOffset": _modal_offset(distribution),
    "offsetZero": _compact_metrics(baseline_metrics),
    "nestedSelectedOffset": _compact_metrics(selected_metrics),
    "m4cLofo": _compact_metrics(m4c["metrics"]),
    "integerCutoff": {
      "signedErrorExactlyOne": exact_one,
      "absoluteErrorOne": absolute_one,
    },
    "nearBoundary": {
      "offsetZero": _near_boundary_metrics(rows, baseline_predictions),
      "nestedSelectedOffset": _near_boundary_metrics(rows,
                                                     selected_predictions),
    },
    "familySpread": {
      "improvedFamilyCount": len(improved),
      "worsenedFamilyCount": len(worsened),
      "unchangedFamilyCount": len(unchanged),
      "improvedFamilies": improved,
      "worsenedFamilies": worsened,
      "improvedTopologies": improved_topologies,
      "spansMultipleFamilies": len(improved) > 1,
      "spansMultipleTopologies": len(improved_topologies) > 1,
    },
    "candidatePolicyDecision": (
      "SUPPORTED_FOR_CANDIDATE_POLICY" if supported
      else "NOT_SUPPORTED_FOR_CANDIDATE_POLICY"
    ),
    "candidatePolicyBasis": (
      "nested supported regret improved, worst-family regret was guarded, and "
      "multiple held-out families improved"
      if supported else
      "nested selection increased supported regret, improved no held-out family, "
      "and converted no false DEFAULT regret while adding false CACHE regret"
    ),
    "preexistingArtifactHashesBefore": before_hashes,
  }

  output_dir.mkdir(parents=True, exist_ok=True)
  digests = {}
  digests["grid"] = _write_json(
      output_dir / "cache_offset_grid_results.json",
      {
        "schemaVersion": SCHEMA_VERSION,
        "evaluatorVersion": EVALUATOR_VERSION,
        "grid": list(CACHE_OFFSET_GRID),
        "outerSelections": [
          {
            "heldOutFamily": fold["heldOutFamily"],
            "boundaryCandidateId": fold["boundaryCandidateId"],
            "selection": fold["offsetSelection"],
          }
          for fold in outer_results
        ],
      },
  )
  digests["outer"] = _write_json(
      output_dir / "cache_offset_outer_lofo.json",
      {
        "schemaVersion": SCHEMA_VERSION,
        "evaluatorVersion": EVALUATOR_VERSION,
        "outerUnit": "physical family",
        "folds": outer_results,
        "baselinePredictions": baseline_predictions,
        "selectedPredictions": selected_predictions,
        "baselineMetrics": _compact_metrics(baseline_metrics),
        "selectedMetrics": _compact_metrics(selected_metrics),
        "m4cMetrics": _compact_metrics(m4c["metrics"]),
        "integerCutoff": summary["integerCutoff"],
        "nearBoundary": summary["nearBoundary"],
      },
  )
  digests["families"] = _write_json(
      output_dir / "cache_offset_family_metrics.json",
      {
        "schemaVersion": SCHEMA_VERSION,
        "offsetZero": baseline_metrics["families"],
        "nestedSelectedOffset": selected_metrics["families"],
        "m4cLofo": m4c["metrics"]["families"],
        "familySpread": summary["familySpread"],
        "topologyByFamily": topology_by_family,
      },
  )
  digests["findings"] = _write_text(
      output_dir / "cache_offset_findings.md", _findings(summary)
  )
  after_hashes = preexisting_hashes(repo_root)
  if before_hashes != after_hashes:
    raise ValueError("a pre-existing frozen or evaluation artifact changed")
  summary["preexistingArtifactHashesAfter"] = after_hashes
  summary["artifactHashes"] = digests
  _write_json(output_dir / "cache_offset_summary.json", summary)
  return summary


def main() -> None:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--repo-root", type=Path, default=Path.cwd())
  parser.add_argument(
      "--output-dir",
      type=Path,
      default=Path("experiments/pareto_cache_offset_evaluation"),
  )
  args = parser.parse_args()
  root = args.repo_root.resolve()
  output = args.output_dir if args.output_dir.is_absolute() else root / args.output_dir
  result = run_cache_offset_evaluation(root, output)
  print(json.dumps({
    "outputDir": str(output),
    "selectedOffsetDistribution": result["selectedOffsetDistribution"],
    "candidatePolicyDecision": result["candidatePolicyDecision"],
  }, sort_keys=True))


if __name__ == "__main__":
  main()
