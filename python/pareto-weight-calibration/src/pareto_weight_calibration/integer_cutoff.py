"""Whole-core runtime-cutoff evaluation of frozen action-model LOFO predictions."""

from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
from statistics import median
from typing import Any, Iterable, Sequence

import numpy as np

from pareto_weight_calibration.checksum import ChecksumVerifier

SCHEMA_VERSION = 1
EVALUATOR_VERSION = "integer-runtime-cutoff-v1"
REPOSITORY_ROOT = Path(__file__).resolve().parents[4]
DEFAULT = "DEFAULT"
CACHE = "CACHE"
INDETERMINATE = "INDETERMINATE"
SUPPORTED = "SUPPORTED_INTERVAL"
UNINFORMATIVE = "INDETERMINATE_ONLY"
CONTRADICTORY = "NO_MONOTONE_CUTOFF"


def first_cache_k(mu: float) -> int:
  if not math.isfinite(mu):
    raise ValueError("mu must be finite")
  return math.floor(mu) + 1


def runtime_action(current_k: int, predicted_first_cache_k: int) -> str:
  if not isinstance(current_k, int) or not isinstance(predicted_first_cache_k,
                                                      int):
    raise TypeError("runtime cutoff evaluation requires integer K values")
  return CACHE if current_k >= predicted_first_cache_k else DEFAULT


def derive_supported_cutoff(rows: Sequence[dict[str, Any]]) -> dict[str, Any]:
  if not rows:
    raise ValueError("cutoff derivation requires at least one row")
  workers = {int(row["registered_workers"]) for row in rows}
  if len(workers) != 1:
    raise ValueError(
      "one physical family must have one registered-worker count")
  registered_workers = workers.pop()
  default_ks = sorted(
      int(row["current_k"]) for row in rows if row["observed_action"] == DEFAULT
  )
  cache_ks = sorted(
      int(row["current_k"]) for row in rows if row["observed_action"] == CACHE
  )
  unexpected = sorted({
    row["observed_action"] for row in rows
    if row["observed_action"] not in {DEFAULT, CACHE, INDETERMINATE}
  })
  if unexpected:
    raise ValueError(f"unexpected frozen actions: {unexpected}")
  lower = max([2, *[current_k + 1 for current_k in default_ks]])
  upper = min([registered_workers + 1, *cache_ks])
  if not default_ks and not cache_ks:
    status = UNINFORMATIVE
  elif lower > upper:
    status = CONTRADICTORY
  else:
    status = SUPPORTED
  return {
    "status": status,
    "observedFirstCacheKMin": lower if status != CONTRADICTORY else None,
    "observedFirstCacheKMax": upper if status != CONTRADICTORY else None,
    "lowerConstraintFromDefault": max(default_ks) if default_ks else None,
    "upperConstraintFromCache": min(cache_ks) if cache_ks else None,
    "defaultKs": default_ks,
    "cacheKs": cache_ks,
    "indeterminateKs": sorted(
        int(row["current_k"])
        for row in rows if row["observed_action"] == INDETERMINATE
    ),
    "registeredWorkers": registered_workers,
  }


def derive_predicted_cutoff_interval(
    actions: Sequence[tuple[int, str]], registered_workers: int
) -> dict[str, Any]:
  rows = [
    {
      "current_k": current_k,
      "registered_workers": registered_workers,
      "observed_action": action,
    }
    for current_k, action in actions
  ]
  result = derive_supported_cutoff(rows)
  if result["status"] == UNINFORMATIVE:
    raise ValueError("predicted runtime actions must be decisive")
  return result


def signed_cutoff_error(
    predicted: int, observed_min: int, observed_max: int
) -> int:
  if observed_min > observed_max:
    raise ValueError("invalid observed cutoff interval")
  if predicted < observed_min:
    return predicted - observed_min
  if predicted > observed_max:
    return predicted - observed_max
  return 0


def interval_cutoff_error(
    predicted_min: int,
    predicted_max: int,
    observed_min: int,
    observed_max: int,
) -> int:
  if predicted_min > predicted_max or observed_min > observed_max:
    raise ValueError("invalid cutoff interval")
  if predicted_max < observed_min:
    return predicted_max - observed_min
  if predicted_min > observed_max:
    return predicted_min - observed_max
  return 0


def frozen_action_loss(row: dict[str, Any], predicted_action: str) -> dict[
  str, Any]:
  observed_action = row["observed_action"]
  if observed_action == INDETERMINATE:
    return {
      "correct": None,
      "wrongType": None,
      "supportedLoss": 0.0,
      "observedLoss": 0.0,
      "supportedRelativeLoss": 0.0,
      "observedRelativeLoss": 0.0,
    }
  correct = predicted_action == observed_action
  return {
    "correct": correct,
    "wrongType": (
      None if correct else "FALSE_CACHE" if predicted_action == CACHE
      else "FALSE_DEFAULT"
    ),
    "supportedLoss": 0.0 if correct else float(
        row["supported_wrong_action_loss"]),
    "observedLoss": 0.0 if correct else float(
        row["observed_wrong_action_loss"]),
    "supportedRelativeLoss": (
      0.0 if correct else float(row["supported_relative_wrong_action_loss"])
    ),
    "observedRelativeLoss": (
      0.0 if correct else float(row["observed_relative_wrong_action_loss"])
    ),
  }


def _bucket(error: int | None) -> str:
  if error is None:
    return "NOT_EVALUABLE"
  absolute = abs(error)
  if absolute <= 2:
    return str(absolute)
  return "3+"


def _ratio(numerator: float, denominator: float) -> float | None:
  return numerator / denominator if denominator > 0.0 else None


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


def _ratio_bucket(productive_handles: float, registered_workers: int) -> str:
  ratio = productive_handles / registered_workers
  if ratio <= 0.25:
    return "(0,0.25]"
  if ratio <= 0.5:
    return "(0.25,0.5]"
  if ratio <= 0.75:
    return "(0.5,0.75]"
  return "(0.75,1+]"


def _loss_metrics(cases: Sequence[dict[str, Any]], total_influence: float) -> \
dict[str, Any]:
  influence = math.fsum(float(case["influenceWeight"]) for case in cases)

  def weighted(field: str, wrong_type: str | None = None) -> float:
    return math.fsum(
        float(case["influenceWeight"]) * float(case["loss"][field])
        for case in cases
        if wrong_type is None or case["loss"]["wrongType"] == wrong_type
    )

  supported_relative = weighted("supportedRelativeLoss")
  observed_relative = weighted("observedRelativeLoss")
  return {
    "rowCount": len(cases),
    "decisiveRowCount": sum(
        case["loss"]["correct"] is not None for case in cases),
    "wrongActionCount": sum(
        case["loss"]["wrongType"] is not None for case in cases),
    "influence": influence,
    "supportedRelativeRegretContribution": _ratio(supported_relative,
                                                  total_influence),
    "observedRelativeRegretContribution": _ratio(observed_relative,
                                                 total_influence),
    "withinBucketSupportedRelativeRegret": _ratio(supported_relative,
                                                  influence),
    "withinBucketObservedRelativeRegret": _ratio(observed_relative, influence),
    "falseCacheSupportedRelativeRegretContribution": (
      _ratio(weighted("supportedRelativeLoss", "FALSE_CACHE"), total_influence)
    ),
    "falseDefaultSupportedRelativeRegretContribution": (
      _ratio(weighted("supportedRelativeLoss", "FALSE_DEFAULT"),
             total_influence)
    ),
  }


def _cutoff_metrics(cases: Sequence[dict[str, Any]]) -> dict[str, Any]:
  total_influence = math.fsum(float(case["influenceWeight"]) for case in cases)
  evaluable = [case for case in cases if case["integerCutoffError"] is not None]
  errors = [abs(int(case["integerCutoffError"])) for case in evaluable]
  evaluable_influence = math.fsum(
      float(case["influenceWeight"]) for case in evaluable)

  def rate_within(limit: int) -> dict[str, Any]:
    selected = [case for case in evaluable if
                abs(int(case["integerCutoffError"])) <= limit]
    return {
      "count": len(selected),
      "rawRate": _ratio(len(selected), len(evaluable)),
      "influenceWeightedRate": _ratio(
          math.fsum(float(case["influenceWeight"]) for case in selected),
          evaluable_influence,
      ),
    }

  buckets = {
    name: _loss_metrics(
        [case for case in cases if case["integerCutoffErrorBucket"] == name],
        total_influence,
    )
    for name in ("0", "1", "2", "3+", "NOT_EVALUABLE")
  }
  return {
    "rowCount": len(cases),
    "evaluableRowCount": len(evaluable),
    "nonEvaluableRowCount": len(cases) - len(evaluable),
    "exactHit": rate_within(0),
    "withinOneCore": rate_within(1),
    "withinTwoCores": rate_within(2),
    "medianAbsoluteCutoffError": median(errors) if errors else None,
    "p90AbsoluteCutoffError": float(
      np.quantile(errors, 0.9)) if errors else None,
    "worstAbsoluteCutoffError": max(errors) if errors else None,
    "regretByIntegerCutoffError": buckets,
    "overallActionRegret": _loss_metrics(cases, total_influence),
  }


def _breakdowns(cases: Sequence[dict[str, Any]], field: str) -> dict[str, Any]:
  groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
  for case in cases:
    groups[str(case[field])].append(case)
  return {name: _cutoff_metrics(group) for name, group in
          sorted(groups.items())}


def build_boundary_cases(
    rows: Sequence[dict[str, Any]], predictions: Sequence[dict[str, Any]]
) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
  rows_by_pair = {row["pair_id"]: row for row in rows}
  predictions_by_pair = {prediction["pairId"]: prediction for prediction in
                         predictions}
  if set(rows_by_pair) != set(predictions_by_pair):
    raise ValueError("boundary prediction inventory does not match frozen rows")
  family_rows: dict[str, list[dict[str, Any]]] = defaultdict(list)
  for row in rows:
    family_rows[row["family_id"]].append(row)
  observed = {
    family: derive_supported_cutoff(family_data)
    for family, family_data in family_rows.items()
  }
  cases = []
  for pair_id in sorted(rows_by_pair):
    row = rows_by_pair[pair_id]
    prediction = predictions_by_pair[pair_id]
    mu = float(prediction["mu"])
    predicted_cutoff = first_cache_k(mu)
    action = runtime_action(int(row["current_k"]), predicted_cutoff)
    if action != prediction["action"]:
      raise ValueError(
        f"{pair_id}: continuous and integer runtime actions disagree")
    cutoff = observed[row["family_id"]]
    error = (
      signed_cutoff_error(
          predicted_cutoff,
          int(cutoff["observedFirstCacheKMin"]),
          int(cutoff["observedFirstCacheKMax"]),
      )
      if cutoff["status"] == SUPPORTED else None
    )
    cases.append({
      "pairId": pair_id,
      "familyId": row["family_id"],
      "currentK": int(row["current_k"]),
      "registeredWorkers": int(row["registered_workers"]),
      "sourceCount": int(row["source_count"]),
      "workUnits": int(row["work_units"]),
      "productiveHandles": float(row["productive_handles"]),
      "productiveHandleRatio": float(row["productive_handles"]) / int(
          row["registered_workers"]),
      "currentBodyCostNs": float(row["body_cost_ns"]),
      "currentContention": float(row["contention"]),
      "sourceDeficitRegime": _source_regime(int(row["source_count"]),
                                            int(row["registered_workers"])),
      "bodyBucket": _body_bucket(int(row["work_units"])),
      "productiveHandleRatioBucket": _ratio_bucket(
        float(row["productive_handles"]), int(row["registered_workers"])),
      "observedAction": row["observed_action"],
      "predictedAction": action,
      "continuousMu": mu,
      "continuousBoundaryMargin": abs(int(row["current_k"]) - mu),
      "predictedFirstCacheK": predicted_cutoff,
      "observedCutoff": cutoff,
      "integerCutoffError": error,
      "absoluteIntegerCutoffError": abs(error) if error is not None else None,
      "integerCutoffErrorBucket": _bucket(error),
      "evidenceWeight": float(row["evidence_weight"]),
      "influenceWeight": float(row["influence_weight"]),
      "loss": frozen_action_loss(row, action),
    })
  return cases, observed


def _sub_core_diagnostics(cases: Sequence[dict[str, Any]]) -> dict[str, Any]:
  groups: dict[tuple[str, int], list[dict[str, Any]]] = defaultdict(list)
  for case in cases:
    groups[(case["familyId"], case["predictedFirstCacheK"])].append(case)
  equivalent_groups = []
  equivalent_pair_count = 0
  equivalent_row_ids: set[str] = set()
  for (family, cutoff), group in sorted(groups.items()):
    distinct_mu = sorted({float(case["continuousMu"]) for case in group})
    if len(distinct_mu) < 2:
      continue
    equivalent_pair_count += len(group) * (len(group) - 1) // 2
    equivalent_row_ids.update(case["pairId"] for case in group)
    equivalent_groups.append({
      "familyId": family,
      "predictedFirstCacheK": cutoff,
      "rowCount": len(group),
      "continuousMuMin": min(distinct_mu),
      "continuousMuMax": max(distinct_mu),
      "pairIds": sorted(case["pairId"] for case in group),
    })
  integer_hits_with_fractional_mu = [
    case for case in cases
    if case["integerCutoffError"] == 0
       and not math.isclose(case["continuousMu"], round(case["continuousMu"]),
                            abs_tol=1e-12)
  ]
  near_boundary = [case for case in cases if
                   case["continuousBoundaryMargin"] <= 0.5]
  total_influence = math.fsum(float(case["influenceWeight"]) for case in cases)
  return {
    "equivalentIntegerCutoffGroupCount": len(equivalent_groups),
    "rowsInEquivalentGroups": len(equivalent_row_ids),
    "pairwiseContinuousMuComparisonsWithSameCutoff": equivalent_pair_count,
    "integerHitsWithNonIntegerMuCount": len(integer_hits_with_fractional_mu),
    "groups": equivalent_groups,
    "priorZeroToHalfBoundaryMargin": _cutoff_metrics(near_boundary),
    "priorZeroToHalfBoundaryMarginGlobalRegretContribution": _loss_metrics(
        near_boundary, total_influence
    ),
    "actionErrorsRemovedByIntegerization": 0,
    "semanticIdentity": (
      "For integer K, K > mu is exactly equivalent to "
      "K >= floor(mu) + 1; integerization cannot change action regret."
    ),
  }


def _family_metrics(
    cases: Sequence[dict[str, Any]], observed: dict[str, dict[str, Any]]
) -> dict[str, Any]:
  by_family: dict[str, list[dict[str, Any]]] = defaultdict(list)
  for case in cases:
    by_family[case["familyId"]].append(case)
  total_influence = math.fsum(float(case["influenceWeight"]) for case in cases)
  result = {}
  for family, family_cases in sorted(by_family.items()):
    result[family] = {
      "observedCutoff": observed[family],
      "metrics": _cutoff_metrics(family_cases),
      "globalActionRegretContribution": _loss_metrics(family_cases,
                                                      total_influence),
      "rows": sorted(family_cases,
                     key=lambda case: (case["currentK"], case["pairId"])),
    }
  return result


def _m4c_cutoff_metrics(
    rows: Sequence[dict[str, Any]], predictions: Sequence[dict[str, Any]],
    observed: dict[str, dict[str, Any]],
) -> dict[str, Any]:
  rows_by_pair = {row["pair_id"]: row for row in rows}
  predictions_by_pair = {prediction["pairId"]: prediction for prediction in
                         predictions}
  if set(rows_by_pair) != set(predictions_by_pair):
    raise ValueError("M4-C prediction inventory does not match frozen rows")
  family_actions: dict[str, list[tuple[int, str]]] = defaultdict(list)
  cases = []
  for pair_id in sorted(rows_by_pair):
    row = rows_by_pair[pair_id]
    prediction = predictions_by_pair[pair_id]
    family_actions[row["family_id"]].append(
        (int(row["current_k"]), prediction["action"]))
    cases.append({
      "pairId": pair_id,
      "familyId": row["family_id"],
      "influenceWeight": float(row["influence_weight"]),
      "loss": frozen_action_loss(row, prediction["action"]),
    })
  predicted_intervals = {}
  comparisons = []
  for family, actions in sorted(family_actions.items()):
    registered_workers = int(next(row["registered_workers"] for row in rows if
                                  row["family_id"] == family))
    predicted = derive_predicted_cutoff_interval(actions, registered_workers)
    predicted_intervals[family] = predicted
    truth = observed[family]
    error = None
    if truth["status"] == SUPPORTED and predicted["status"] == SUPPORTED:
      error = interval_cutoff_error(
          int(predicted["observedFirstCacheKMin"]),
          int(predicted["observedFirstCacheKMax"]),
          int(truth["observedFirstCacheKMin"]),
          int(truth["observedFirstCacheKMax"]),
      )
    comparisons.append({
      "familyId": family,
      "observedCutoff": truth,
      "predictedCutoffIntervalFromSampledActions": predicted,
      "integerCutoffIntervalError": error,
      "absoluteIntegerCutoffIntervalError": abs(
        error) if error is not None else None,
    })
  evaluable = [item for item in comparisons if
               item["integerCutoffIntervalError"] is not None]
  errors = [abs(int(item["integerCutoffIntervalError"])) for item in evaluable]
  total = math.fsum(float(case["influenceWeight"]) for case in cases)
  action_cases_by_family: dict[str, list[dict[str, Any]]] = defaultdict(list)
  for case in cases:
    action_cases_by_family[case["familyId"]].append(case)
  family_action_regret = {
    family: _loss_metrics(family_cases, total)
    for family, family_cases in sorted(action_cases_by_family.items())
  }
  highest_regret_families = [
    family for family, _ in sorted(
        (
          (family, metrics["supportedRelativeRegretContribution"])
          for family, metrics in family_action_regret.items()
        ),
        key=lambda item: (-item[1], item[0]),
    )[:10]
  ]
  return {
    "cutoffScope": (
      "M4-C has no continuous family boundary. Its reported cutoff interval is the "
      "set of monotone integer cutoffs compatible with sampled held-out actions."
    ),
    "familyCount": len(comparisons),
    "evaluableFamilyCount": len(evaluable),
    "exactIntervalOverlapRate": _ratio(sum(error == 0 for error in errors),
                                       len(errors)),
    "withinOneCoreRate": _ratio(sum(error <= 1 for error in errors),
                                len(errors)),
    "withinTwoCoresRate": _ratio(sum(error <= 2 for error in errors),
                                 len(errors)),
    "medianAbsoluteCutoffIntervalError": median(errors) if errors else None,
    "p90AbsoluteCutoffIntervalError": float(
      np.quantile(errors, 0.9)) if errors else None,
    "worstAbsoluteCutoffIntervalError": max(errors) if errors else None,
    "actionRegret": _loss_metrics(cases, total),
    "highestRegretFamilies": highest_regret_families,
    "actionRegretByFamily": family_action_regret,
    "families": comparisons,
  }


def _jsonable(value: Any) -> Any:
  if isinstance(value, np.generic):
    return _jsonable(value.item())
  if isinstance(value, dict):
    return {str(key): _jsonable(item) for key, item in value.items()}
  if isinstance(value, (list, tuple)):
    return [_jsonable(item) for item in value]
  if isinstance(value, float) and not math.isfinite(value):
    raise ValueError(f"non-finite artifact value: {value}")
  return value


def _write_json(path: Path, payload: Any) -> str:
  content = json.dumps(_jsonable(payload), indent=2, sort_keys=True,
                       allow_nan=False) + "\n"
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode()).hexdigest()
  path.with_name(path.name + ".sha256").write_text(digest + "\n",
                                                   encoding="utf-8")
  return digest


def _write_text(path: Path, content: str) -> str:
  if not content.endswith("\n"):
    content += "\n"
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode()).hexdigest()
  path.with_name(path.name + ".sha256").write_text(digest + "\n",
                                                   encoding="utf-8")
  return digest


def _verified_json(path: Path) -> dict[str, Any]:
  ChecksumVerifier.verify_file(path, require_sidecar=True)
  return json.loads(path.read_text(encoding="utf-8"))


def _findings(metrics: dict[str, Any], m4c: dict[str, Any],
    sub_core: dict[str, Any],
    observed_counts: dict[str, int], worst_families: Sequence[str]) -> str:
  buckets = metrics["regretByIntegerCutoffError"]
  near = sub_core["priorZeroToHalfBoundaryMargin"]
  return "\n".join([
    "# Whole-core integer cutoff re-evaluation",
    "",
    "No model was retrained, no benchmark was rerun, and no production Java was changed.",
    "",
    "## Result",
    "",
    f"- Informative supported cutoff families: {observed_counts.get(SUPPORTED, 0)}; indeterminate-only: {observed_counts.get(UNINFORMATIVE, 0)}; contradictory single-cutoff evidence: {observed_counts.get(CONTRADICTORY, 0)}.",
    f"- Exact integer cutoff hit rate: {metrics['exactHit']['rawRate']} ({metrics['exactHit']['count']}/{metrics['evaluableRowCount']}).",
    f"- Within one / two cores: {metrics['withinOneCore']['rawRate']} / {metrics['withinTwoCores']['rawRate']}.",
    f"- Median / p90 / worst absolute cutoff error: {metrics['medianAbsoluteCutoffError']} / {metrics['p90AbsoluteCutoffError']} / {metrics['worstAbsoluteCutoffError']} cores.",
    f"- Boundary supported action regret: {metrics['overallActionRegret']['supportedRelativeRegretContribution']}.",
    f"- M4-C supported action regret: {m4c['actionRegret']['supportedRelativeRegretContribution']}.",
    f"- Continuous-mu equivalence groups: {sub_core['equivalentIntegerCutoffGroupCount']}; rows: {sub_core['rowsInEquivalentGroups']}.",
    f"- Previous 0-0.5 continuous-margin rows: {near['rowCount']}; their global supported regret contribution remains {sub_core['priorZeroToHalfBoundaryMarginGlobalRegretContribution']['supportedRelativeRegretContribution']}.",
    f"- Highest-regret meaningful cutoff-failure families: {', '.join(worst_families)}.",
    "",
    "## Interpretation",
    "",
    "For integer K, the original continuous rule `K > mu` and the integer rule `K >= floor(mu) + 1` are exactly equivalent. Integerization therefore removes meaningless fractional precision but cannot erase any action error or supported regret already measured with the correct runtime inequality.",
    "",
    "Families with incompatible decisive actions are reported as `NO_MONOTONE_CUTOFF`; ties never create a fake exact cutoff. Regret from non-evaluable cutoff families remains in overall action metrics but is not assigned to a numerical cutoff-error bucket.",
    "",
    "The 0-0.5 continuous-margin concern is operationally real wherever it caused an action error: crossing the integer boundary changes the runtime decision. It is not made harmless by representing the same rule as an integer cutoff.",
  ])


def run_integer_cutoff_evaluation(
    dataset_path: Path,
    boundary_lofo_path: Path,
    m4c_lofo_path: Path,
    output_dir: Path,
) -> dict[str, Any]:
  input_paths = (dataset_path, boundary_lofo_path, m4c_lofo_path)
  inputs_before = {str(path): ChecksumVerifier.compute_sha256(path) for path in
                   input_paths}
  dataset = _verified_json(dataset_path)
  boundary = _verified_json(boundary_lofo_path)
  m4c = _verified_json(m4c_lofo_path)
  rows = dataset["rows"]
  boundary_cases, observed = build_boundary_cases(rows, boundary["predictions"])
  boundary_metrics = _cutoff_metrics(boundary_cases)
  family_metrics = _family_metrics(boundary_cases, observed)
  sub_core = _sub_core_diagnostics(boundary_cases)
  m4c_metrics = _m4c_cutoff_metrics(rows, m4c["predictions"], observed)
  observed_counts: dict[str, int] = defaultdict(int)
  for cutoff in observed.values():
    observed_counts[cutoff["status"]] += 1
  worst_families = [
    family for family, _ in sorted(
        (
          (family, data["globalActionRegretContribution"][
            "supportedRelativeRegretContribution"])
          for family, data in family_metrics.items()
          if data["observedCutoff"]["status"] == SUPPORTED
             and data["globalActionRegretContribution"][
               "supportedRelativeRegretContribution"] > 0.0
        ),
        key=lambda item: (-item[1], item[0]),
    )[:10]
  ]
  breakdowns = {
    "registeredWorkers": _breakdowns(boundary_cases, "registeredWorkers"),
    "sourceDeficitRegime": _breakdowns(boundary_cases, "sourceDeficitRegime"),
    "bodyBucket": _breakdowns(boundary_cases, "bodyBucket"),
    "workUnits": _breakdowns(boundary_cases, "workUnits"),
    "productiveHandleRatio": _breakdowns(boundary_cases,
                                         "productiveHandleRatioBucket"),
  }
  inputs_after = {str(path): ChecksumVerifier.compute_sha256(path) for path in
                  input_paths}
  if inputs_before != inputs_after:
    raise ValueError(
      "an existing frozen action artifact changed during evaluation")
  common = {
    "schemaVersion": SCHEMA_VERSION,
    "evaluatorVersion": EVALUATOR_VERSION,
    "inputArtifactHashes": inputs_before,
    "observedCutoffStatusCounts": dict(sorted(observed_counts.items())),
  }
  output_dir.mkdir(parents=True, exist_ok=True)
  results_payload = {
    **common,
    "runtimeSemantics": "K >= floor(mu) + 1 -> CACHE; otherwise DEFAULT",
    "metrics": boundary_metrics,
    "subCoreDiagnostics": sub_core,
    "breakdowns": breakdowns,
    "cases": boundary_cases,
  }
  family_payload = {
    **common,
    "familyCount": len(family_metrics),
    "highestRegretSupportedCutoffFamilies": worst_families,
    "families": family_metrics,
  }
  comparison_payload = {
    **common,
    "comparisonRule": "Action regret uses identical sampled integer K decisions; cutoff accuracy uses supported intervals and excludes non-monotone or indeterminate-only truth.",
    "boundary": boundary_metrics,
    "m4cLofo": m4c_metrics,
  }
  digests = {
    "results": _write_json(output_dir / "integer_cutoff_results.json",
                           results_payload),
    "families": _write_json(output_dir / "integer_cutoff_family_metrics.json",
                            family_payload),
    "comparison": _write_json(output_dir / "integer_cutoff_comparison_m4c.json",
                              comparison_payload),
  }
  findings = _findings(
      boundary_metrics, m4c_metrics, sub_core, dict(observed_counts),
      worst_families
  )
  digests["findings"] = _write_text(output_dir / "integer_cutoff_findings.md",
                                    findings)
  return {
    "metrics": boundary_metrics,
    "m4c": m4c_metrics,
    "subCore": sub_core,
    "observedCutoffStatusCounts": dict(observed_counts),
    "worstFamilies": worst_families,
    "artifactHashes": digests,
  }


def main() -> None:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument(
      "--dataset", type=Path,
      default=REPOSITORY_ROOT / "experiments/pareto_action_model_training/action_training_dataset.json",
  )
  parser.add_argument(
      "--boundary-lofo", type=Path,
      default=REPOSITORY_ROOT / "experiments/pareto_action_model_training/action_model_outer_lofo.json",
  )
  parser.add_argument(
      "--m4c-lofo", type=Path,
      default=REPOSITORY_ROOT / "experiments/pareto_action_model_training/action_model_m4c_lofo.json",
  )
  parser.add_argument(
      "--output-dir", type=Path,
      default=REPOSITORY_ROOT / "experiments/pareto_integer_cutoff_evaluation",
  )
  args = parser.parse_args()
  result = run_integer_cutoff_evaluation(
      args.dataset.resolve(), args.boundary_lofo.resolve(),
      args.m4c_lofo.resolve(),
      args.output_dir.resolve(),
  )
  print(json.dumps({
    "outputDir": str(args.output_dir.resolve()),
    "exactHitRate": result["metrics"]["exactHit"]["rawRate"],
    "supportedRegret": result["metrics"]["overallActionRegret"][
      "supportedRelativeRegretContribution"],
  }, sort_keys=True))


if __name__ == "__main__":
  main()
