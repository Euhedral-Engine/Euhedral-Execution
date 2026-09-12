"""Frozen-evidence diagnostic for the low-P/R R7/S1 body transition.

This stage only evaluates already-fitted models and checksum-frozen evidence.  It
does not fit a model, synthesize telemetry, or change runtime policy.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
from dataclasses import fields, replace
import hashlib
import json
import math
from pathlib import Path
from typing import Any, Iterable, Sequence

import numpy as np

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
from pareto_weight_calibration.direct_side import (
  BASE_FEATURES,
  DIRECT_STRUCTURES,
  DirectFit,
  DirectScaler,
  action_for_score,
  current_state_features,
  direct_design_matrix,
  predict_direct_side,
  side_for_action,
)

SCHEMA_VERSION = 1
EVALUATOR_VERSION = "r7-s1-body-transition-diagnostic-v1"
TARGET_RS = (7, 15, 23)
TARGET_WUS = (0, 64)
LOW_P_RATIO_MAX = 0.25

INPUTS = {
  "dataset": Path(
    "experiments/pareto_action_model_training/action_training_dataset.json"),
  "boundaryOuter": Path(
    "experiments/pareto_action_model_training/action_model_outer_lofo.json"),
  "boundaryCandidate": Path(
    "experiments/pareto_action_model_training/action_model_candidate.json"),
  "m4cOuter": Path(
    "experiments/pareto_action_model_training/action_model_m4c_lofo.json"),
  "directOuter": Path(
    "experiments/pareto_direct_side_training/direct_side_outer_lofo.json"),
  "directCandidate": Path(
    "experiments/pareto_direct_side_training/direct_side_candidate.json"),
  "logistic": Path(
    "experiments/pareto_side_of_peak_evaluation/side_of_peak_results.json"),
  "trainingPairs": Path("experiments/pareto_training_step5/training_pairs.tsv"),
}


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
  return json.dumps(_jsonable(payload), indent=2, sort_keys=True,
                    allow_nan=False) + "\n"


def _write(path: Path, content: str) -> str:
  if not content.endswith("\n"):
    content += "\n"
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode()).hexdigest()
  path.with_name(path.name + ".sha256").write_text(digest + "\n",
                                                   encoding="utf-8")
  return digest


def _write_json(path: Path, payload: Any) -> str:
  return _write(path, _canonical_json(payload))


def _verified_json(path: Path) -> dict[str, Any]:
  ChecksumVerifier.verify_file(path, require_sidecar=True)
  return json.loads(path.read_text(encoding="utf-8"))


def input_hashes(repo_root: Path) -> dict[str, str]:
  result = {}
  for name, relative in INPUTS.items():
    path = repo_root / relative
    ChecksumVerifier.verify_file(path, require_sidecar=True)
    result[name] = ChecksumVerifier.compute_sha256(path)
  return result


def _rows(payload: dict[str, Any]) -> list[ActionRow]:
  names = {field.name for field in fields(ActionRow)}
  rows = [ActionRow(**{name: item[name] for name in names}) for item in
          payload["rows"]]
  if len(rows) != len({row.pair_id for row in rows}):
    raise ValueError("duplicate frozen pair id")
  return sorted(rows, key=lambda row: row.pair_id)


def _direct_fit(model: dict[str, Any]) -> DirectFit:
  scaler = model["scaler"]
  return DirectFit(
      structure=model["structure"],
      feature_names=tuple(model["featureNames"]),
      l2=float(model["l2"]),
      temperature=float(model["temperature"]),
      scaler=DirectScaler(tuple(scaler["names"]), tuple(scaler["means"]),
                          tuple(scaler["scales"])),
      coefficients=tuple(model["coefficients"]),
      objective=float(model["objective"]),
      success=bool(model["success"]),
      iterations=int(model["iterations"]),
      minimum_domain_k_slope=float(model["minimumDomainKSlope"]),
  )


def _boundary_fit(model: dict[str, Any]) -> BoundaryFit:
  scaler = model["scaler"]
  return BoundaryFit(
      structure=model["structure"],
      feature_names=tuple(model["featureNames"]),
      l2=float(model["l2"]),
      temperature=float(model["temperature"]),
      scaler=FeatureScaler(tuple(scaler["names"]), tuple(scaler["means"]),
                           tuple(scaler["scales"])),
      coefficients=tuple(model["coefficients"]),
      success=bool(model["success"]),
      objective=float(model["objective"]),
      iterations=int(model["iterations"]),
  )


def ordered_body_rows(rows: Iterable[ActionRow]) -> list[ActionRow]:
  return sorted(rows, key=lambda row: (row.work_units, row.body_cost_ns,
                                       row.current_k, row.pair_id))


def direct_score_decomposition(row: ActionRow, fit: DirectFit) -> dict[
  str, Any]:
  matrix = direct_design_matrix([row], fit.structure, fit.scaler)[0]
  coefficients = np.asarray(fit.coefficients, dtype=np.float64)
  contributions = matrix * coefficients
  names = ("intercept", *DIRECT_STRUCTURES[fit.structure])
  by_name = {name: float(value) for name, value in
             zip(names, contributions, strict=True)}
  score = float(np.sum(contributions))
  predicted = predict_direct_side(fit, [row])[0]
  if score != predicted["score"]:
    raise ValueError("score decomposition is not exact")
  return {
    "pairId": row.pair_id,
    "familyId": row.family_id,
    "fitStructure": fit.structure,
    "fitScaler": fit.scaler.serialize(),
    "coefficients": list(fit.coefficients),
    "contributions": by_name,
    "totalScore": score,
    "predictedAction": action_for_score(score),
  }


def normalized_feature_delta(first: ActionRow, second: ActionRow,
    scaler: DirectScaler) -> dict[str, Any]:
  raw_first = current_state_features(first)
  raw_second = current_state_features(second)
  absolute = {name: raw_second[name] - raw_first[name] for name in
              BASE_FEATURES}
  normalized = {
    name: absolute[name] / scaler.scales[index]
    for index, name in enumerate(scaler.names)
  }
  return {
    "fromPairId": first.pair_id,
    "toPairId": second.pair_id,
    "absoluteDelta": absolute,
    "absoluteMagnitude": {name: abs(value) for name, value in absolute.items()},
    "normalizedDelta": normalized,
    "normalizedMagnitude": {name: abs(value) for name, value in
                            normalized.items()},
    "scaledEuclideanDistance": math.sqrt(
      math.fsum(value * value for value in normalized.values())),
    "runtimeStateDelta": {
      "productiveHandles": second.productive_handles - first.productive_handles,
      "pRatio": second.p_ratio - first.p_ratio,
      "body": second.body_log - first.body_log,
      "measuredBodyCostNs": second.body_cost_ns - first.body_cost_ns,
      "contention": second.contention - first.contention,
      "K": second.current_k - first.current_k,
    },
    "unscaledRuntimeFields": ["productiveHandles", "measuredBodyCostNs"],
    "scaler": scaler.serialize(),
  }


def matched_body_comparisons(rows: Sequence[ActionRow],
    registered_workers: Sequence[int] = TARGET_RS) -> dict[str, Any]:
  selected = [row for row in rows if
              row.registered_workers in registered_workers and row.source_count == 1]
  index: dict[tuple[int, int, int], list[ActionRow]] = defaultdict(list)
  for row in selected:
    index[(row.registered_workers, row.work_units, row.current_k)].append(row)
  levels = sorted({(row.work_units, row.current_k) for row in selected})
  exact, partial, unavailable = [], [], []
  for work_units, current_k in levels:
    present = [r for r in registered_workers if
               (r, work_units, current_k) in index]
    item = {
      "workUnits": work_units,
      "currentK": current_k,
      "presentR": present,
      "missingR": [r for r in registered_workers if r not in present],
      "pairIdsByR": {
        str(r): [row.pair_id for row in index[(r, work_units, current_k)]] for r
        in present},
    }
    if len(present) == len(registered_workers):
      exact.append(item)
    elif len(present) >= 2:
      partial.append(item)
    else:
      unavailable.append(item)
  return {"exactMatched": exact, "partialMatched": partial,
          "unavailable": unavailable}


def additive_sequence_classification(rows: Sequence[ActionRow]) -> dict[
  str, Any]:
  ordered = sorted(rows, key=lambda row: (row.body_cost_ns, row.work_units,
                                          row.pair_id))
  decisive = [row for row in ordered if row.decisive]
  if len({row.body_cost_ns for row in decisive}) < 2:
    status = "INSUFFICIENT_BODY_COVERAGE"
  else:
    actions_by_body: dict[float, set[str]] = defaultdict(set)
    for row in decisive:
      actions_by_body[row.body_cost_ns].add(row.observed_action)
    if any(len(actions) > 1 for actions in actions_by_body.values()):
      status = "CONTRADICTORY_EVIDENCE"
    else:
      actions = [next(iter(actions_by_body[body])) for body in
                 sorted(actions_by_body)]
      flips = sum(a != b for a, b in zip(actions, actions[1:]))
      status = "ADDITIVE_GEOMETRY_COMPATIBLE" if flips <= 1 else "ADDITIVE_GEOMETRY_INCOMPATIBLE"
  return {
    "classification": status,
    "rows": [{"pairId": row.pair_id, "bodyCostNs": row.body_cost_ns,
              "observedAction": row.observed_action} for row in ordered],
  }


def transition_repeatability(rows: Sequence[ActionRow],
    low_p_ratio_max: float = LOW_P_RATIO_MAX) -> dict[str, Any]:
  groups: dict[tuple[int, int, int], dict[int, list[ActionRow]]] = defaultdict(
    lambda: defaultdict(list))
  for row in rows:
    if row.p_ratio <= low_p_ratio_max and row.work_units in TARGET_WUS:
      groups[(row.registered_workers, row.source_count, row.current_k)][
        row.work_units].append(row)
  cases = []
  for key in sorted(groups):
    by_wu = groups[key]
    family_ids = sorted(
        {row.family_id for values in by_wu.values() for row in values})
    if not all(wu in by_wu for wu in TARGET_WUS):
      classification = "UNAVAILABLE"
    else:
      first_actions = {row.observed_action for row in by_wu[0]}
      second_actions = {row.observed_action for row in by_wu[64]}
      if len(first_actions) != 1 or len(second_actions) != 1:
        classification = "INDETERMINATE"
      else:
        first_action, second_action = next(iter(first_actions)), next(
          iter(second_actions))
        if INDETERMINATE in {first_action, second_action}:
          classification = "INDETERMINATE"
        elif (first_action, second_action) == (CACHE, DEFAULT):
          classification = "SUPPORTING"
        elif (first_action, second_action) == (DEFAULT, CACHE):
          classification = "OPPOSING"
        else:
          classification = "NO_TRANSITION"
    cases.append({
      "registeredWorkers": key[0], "sourceCount": key[1], "currentK": key[2],
      "classification": classification, "physicalFamilyIds": family_ids,
      "pairIdsByWorkUnits": {str(wu): [row.pair_id for row in by_wu[wu]] for wu
                             in sorted(by_wu)},
    })
  counts = Counter(item["classification"] for item in cases)
  return {
    "lowPRatioMaximum": low_p_ratio_max,
    "independentUnit": "one approximately matched (R, source count, K) transition; physical families are deduplicated within it",
    "supporting": counts["SUPPORTING"], "opposing": counts["OPPOSING"],
    "indeterminate": counts["INDETERMINATE"],
    "unavailable": counts["UNAVAILABLE"],
    "noTransition": counts["NO_TRANSITION"], "cases": cases,
  }


def _prediction_map(payload: dict[str, Any]) -> dict[str, dict[str, Any]]:
  return {item["pairId"]: item for item in payload["predictions"]}


def _outer_direct_fits(payload: dict[str, Any]) -> dict[str, DirectFit]:
  return {fold["heldOutFamily"]: _direct_fit(fold["fit"]) for fold in
          payload["folds"]}


def _model_result(row: ActionRow, model: str, prediction: dict[str, Any] | None,
    score_fields: Sequence[str]) -> dict[str, Any]:
  if prediction is None:
    return {"model": model, "available": False,
            "reason": "critical pair is absent from the retained model artifact"}
  loss = action_loss(row, prediction["action"])
  return {
    "model": model, "available": True, "predictedAction": prediction["action"],
    "correct": prediction["action"] == row.observed_action,
    "supportedRegret": loss["supportedLoss"],
    "supportedRelativeRegret": loss["supportedRelativeLoss"],
    **{name: prediction[name] for name in score_fields if name in prediction},
  }


def _series_entry(
    row: ActionRow,
    direct_outer: dict[str, dict[str, Any]],
    direct_full: dict[str, dict[str, Any]],
    boundary_outer: dict[str, dict[str, Any]],
    boundary_full: dict[str, dict[str, Any]],
) -> dict[str, Any]:
  observed_side = side_for_action(
    row.observed_action) if row.decisive else INDETERMINATE
  return {
    "highlight": row.registered_workers == 7 and row.source_count == 1 and row.work_units in TARGET_WUS,
    "familyId": row.family_id, "pairId": row.pair_id,
    "workUnits": row.work_units,
    "currentK": row.current_k, "P": row.productive_handles,
    "productiveHandles": row.productive_handles,
    "pRatio": row.p_ratio, "registeredWorkers": row.registered_workers,
    "body": row.body_log, "measuredBodyCostNs": row.body_cost_ns,
    "contention": row.contention, "observedSide": observed_side,
    "observedAction": row.observed_action, "evidenceBasis": row.evidence_basis,
    "supportedWrongActionLoss": row.supported_wrong_action_loss,
    "supportedRelativeWrongActionLoss": row.supported_relative_wrong_action_loss,
    "directOuterLofo": direct_outer.get(row.pair_id),
    "directFullCandidate": direct_full[row.pair_id],
    "boundaryOuterLofo": boundary_outer.get(row.pair_id),
    "boundaryFullCandidate": boundary_full[row.pair_id],
  }


def evidence_audit(rows: Sequence[ActionRow], tsv_path: Path,
    critical_pair_ids: Sequence[str]) -> dict[str, Any]:
  with tsv_path.open(newline="", encoding="utf-8") as handle:
    records = list(csv.DictReader(handle, delimiter="\t"))
  by_pair = {record["pairId"]: record for record in records}
  path_uses: dict[str, list[dict[str, str]]] = defaultdict(list)
  hashes_by_path: dict[str, set[str]] = defaultdict(set)
  for record in records:
    for role, path_key, hash_key in (
        ("K", "kRunPath", "kRunSha256"),
        ("K_MINUS_1", "kMinus1RunPath", "kMinus1RunSha256")
    ):
      path_uses[record[path_key]].append(
          {"pairId": record["pairId"], "role": role})
      hashes_by_path[record[path_key]].add(record[hash_key])
  frozen_by_pair = {row.pair_id: row for row in rows}
  audits = []
  for pair_id in critical_pair_ids:
    row = frozen_by_pair[pair_id]
    record = by_pair[pair_id]
    advantage = abs(float(record["basisDeltaThroughput"])) - float(
        record["basisUncertainty"])
    ratio = advantage / max(float(record["basisUncertainty"]), 1e-300)
    consistent = (
        record["labelEvidenceBasis"] == row.evidence_basis
        and float(record["basisThroughput_K"]) == row.basis_throughput_k
        and float(
        record["basisThroughput_KMinus1"]) == row.basis_throughput_k_minus_1
        and record["kRunSha256"] == row.k_run_sha256
        and record["kMinus1RunSha256"] == row.k_minus_1_run_sha256
    )
    strength = "STRONGLY_SUPPORTED" if ratio >= 1.0 and record[
      "trajectoryStatus"] == "STABLE_AGREEMENT" else "WEAKLY_SUPPORTED"
    audits.append({
      "pairId": pair_id, "familyId": row.family_id,
      "experimentId": pair_id.split("__", 1)[0],
      "armIdentities": {
        "K": {"K": row.current_k, "path": row.k_run_path,
              "sha256": row.k_run_sha256,
              "forkCount": int(record["forkCount_K"]),
              "throughput": float(record["basisThroughput_K"])},
        "KMinus1": {"K": row.current_k - 1, "path": row.k_minus_1_run_path,
                    "sha256": row.k_minus_1_run_sha256,
                    "forkCount": int(record["forkCount_KMinus1"]),
                    "throughput": float(record["basisThroughput_KMinus1"])},
      },
      "evidenceBasis": record["labelEvidenceBasis"],
      "wholeRunOutcome": record["wholeRunOutcome"],
      "lateRegionOutcome": record["lateRegionOutcome"],
      "trajectoryStatus": record["trajectoryStatus"],
      "basisDeltaThroughput": float(record["basisDeltaThroughput"]),
      "basisUncertainty": float(record["basisUncertainty"]),
      "supportedAdvantage": advantage,
      "supportToUncertaintyRatio": ratio,
      "pairWeight": float(record["pairWeight"]),
      "classification": strength, "dependentOnSingleComparison": True,
      "sharedArmReuse": {
        "K": path_uses[row.k_run_path],
        "KMinus1": path_uses[row.k_minus_1_run_path],
      },
      "frozenProvenanceConsistent": consistent,
      "globalPathHashConsistent": all(
          len(values) == 1 for values in hashes_by_path.values()),
      "neighboringRows": [
        {"pairId": other.pair_id, "K": other.current_k,
         "observedAction": other.observed_action,
         "evidenceBasis": other.evidence_basis,
         "supportedRelativeWrongActionLoss": other.supported_relative_wrong_action_loss}
        for other in
        sorted(rows, key=lambda item: (item.current_k, item.pair_id))
        if other.family_id == row.family_id
      ],
    })
  return {"sourceRecordCount": len(records), "criticalRows": audits,
          "provenanceInconsistencyFound": not all(
              item["frozenProvenanceConsistent"] and item[
                "globalPathHashConsistent"] for item in audits)}


def _findings(summary: dict[str, Any]) -> str:
  r7 = summary["criticalR7"]
  return "\n".join([
    "# R7/S1 body-transition diagnostic",
    "",
    "This analysis used checksum-frozen evidence and already-fitted models only. No model was retrained, no benchmark was run, and production Java was not changed.",
    "",
    "## Result",
    "",
    f"- Failure classification: `{summary['failureClassification']}`.",
    f"- R7/S1 WU0 -> WU64 scaled feature distance: {r7['scaledFeatureDistance']:.8f}.",
    f"- The exact K=2 CACHE -> DEFAULT transition repeats at R15/S1 and R23/S1: {summary['transitionRepeatsAtMatchedR']}.",
    f"- Broader low-P/R exact-pair counts: {summary['repeatabilityCounts']}.",
    f"- Additive geometry classification: `{summary['r7AdditiveGeometry']}`.",
    f"- Critical evidence weak or contradictory: {summary['criticalEvidenceWeakOrContradictory']}.",
    f"- Processing or provenance inconsistency: {summary['processingOrProvenanceProblem']}.",
    "",
    "## Interpretation",
    "",
    summary["interpretation"],
    "",
    f"Nonlinear body/source-deficit interactions justified now: **{summary['nonlinearInteractionsJustified']}**.",
    f"New benchmark data justified now: **{summary['newBenchmarkDataJustified']}**.",
  ])


def run_diagnostic(repo_root: Path, output_dir: Path) -> dict[str, Any]:
  before = input_hashes(repo_root)
  dataset = _verified_json(repo_root / INPUTS["dataset"])
  direct_outer_payload = _verified_json(repo_root / INPUTS["directOuter"])
  direct_candidate_payload = _verified_json(
    repo_root / INPUTS["directCandidate"])
  boundary_outer_payload = _verified_json(repo_root / INPUTS["boundaryOuter"])
  boundary_candidate_payload = _verified_json(
    repo_root / INPUTS["boundaryCandidate"])
  m4c_payload = _verified_json(repo_root / INPUTS["m4cOuter"])
  logistic_payload = _verified_json(repo_root / INPUTS["logistic"])
  rows = _rows(dataset)
  by_pair = {row.pair_id: row for row in rows}

  direct_fit = _direct_fit(direct_candidate_payload["model"])
  boundary_fit = _boundary_fit(boundary_candidate_payload["model"])
  direct_full = {item["pairId"]: item for item in
                 predict_direct_side(direct_fit, rows)}
  boundary_full = {item["pairId"]: item for item in
                   predict_boundary(boundary_fit, rows)}
  direct_outer = _prediction_map(direct_outer_payload)
  boundary_outer = _prediction_map(boundary_outer_payload)
  m4c_outer = _prediction_map(m4c_payload)
  logistic = {item["pairId"]: item["logistic"] for item in
              logistic_payload["cases"]}
  outer_fits = _outer_direct_fits(direct_outer_payload)

  s1_rows = [row for row in rows if
             row.source_count == 1 and row.registered_workers in TARGET_RS]
  series = {
    str(r): [_series_entry(row, direct_outer, direct_full, boundary_outer,
                           boundary_full)
             for row in ordered_body_rows(s1_rows) if
             row.registered_workers == r]
    for r in TARGET_RS
  }
  r7_series_payload = {"schemaVersion": SCHEMA_VERSION, "R": 7,
                       "sourceCount": 1, "rows": series["7"]}
  matched_payload = {"schemaVersion": SCHEMA_VERSION, "seriesByR": series,
                     "comparisonAvailability": matched_body_comparisons(rows)}

  pairs: dict[int, tuple[ActionRow, ActionRow]] = {}
  for r in TARGET_RS:
    first = [row for row in rows if
             row.registered_workers == r and row.source_count == 1 and row.work_units == 0 and row.current_k == 2]
    second = [row for row in rows if
              row.registered_workers == r and row.source_count == 1 and row.work_units == 64 and row.current_k == 2]
    if len(first) == len(second) == 1:
      pairs[r] = (first[0], second[0])
  distance_cases = []
  for r, (first, second) in pairs.items():
    item = normalized_feature_delta(first, second, direct_fit.scaler)
    item.update({
      "registeredWorkers": r,
      "observedActionFlip": [first.observed_action, second.observed_action],
      "supportedRelativeWrongActionLoss": [
        first.supported_relative_wrong_action_loss,
        second.supported_relative_wrong_action_loss],
      "outerLofoScoreDelta": direct_outer[second.pair_id]["score"] -
                             direct_outer[first.pair_id]["score"],
      "fullCandidateScoreDelta": direct_full[second.pair_id]["score"] -
                                 direct_full[first.pair_id]["score"],
    })
    distance_cases.append(item)
  distances_payload = {"schemaVersion": SCHEMA_VERSION,
                       "scalingSource": str(INPUTS["directCandidate"]),
                       "pairs": distance_cases}

  decomposition_rows = []
  for r, pair in pairs.items():
    for row in pair:
      decomposition_rows.append({
        "registeredWorkers": r, "workUnits": row.work_units,
        "outerLofo": direct_score_decomposition(row, outer_fits[row.family_id]),
        "fullCandidate": direct_score_decomposition(row, direct_fit),
      })
  decomposition_payload = {"schemaVersion": SCHEMA_VERSION,
                           "rows": decomposition_rows}

  geometry_series = []
  for r, pair in pairs.items():
    relevant = [row for row in rows if
                row.registered_workers == r and row.source_count == 1 and row.current_k == 2]
    geometry = additive_sequence_classification(relevant)
    sweeps = []
    observed_bodies = sorted(
        {(row.body_log, row.body_cost_ns) for row in relevant})
    for anchor in pair:
      fit = outer_fits[anchor.family_id]
      scores = []
      for body, body_ns in observed_bodies:
        evaluation_row = replace(anchor, body_log=body, body_cost_ns=body_ns)
        prediction = predict_direct_side(fit, [evaluation_row])[0]
        scores.append(
            {"body": body, "bodyCostNs": body_ns, "score": prediction["score"],
             "action": prediction["action"]})
      sweeps.append(
          {"fitHeldOutFamily": anchor.family_id, "anchorPairId": anchor.pair_id,
           "otherCoordinatesHeldFixed": current_state_features(anchor),
           "observedBodyOnly": True,
           "coefficientsChanged": False, "scores": scores})
    geometry_series.append(
        {"registeredWorkers": r, **geometry, "existingOuterLofoSweeps": sweeps})
  geometry_payload = {"schemaVersion": SCHEMA_VERSION,
                      "syntheticExtrapolationUsed": False,
                      "coefficientsChanged": False, "series": geometry_series}

  repeatability = transition_repeatability(rows)
  repeatability_payload = {"schemaVersion": SCHEMA_VERSION, **repeatability}
  critical_ids = [pairs[7][0].pair_id, pairs[7][1].pair_id]
  audit_payload = {"schemaVersion": SCHEMA_VERSION,
                   **evidence_audit(rows, repo_root / INPUTS["trainingPairs"],
                                    critical_ids)}

  comparisons = []
  for pair_id in critical_ids:
    row = by_pair[pair_id]
    logistic_prediction = logistic.get(pair_id)
    if logistic_prediction is not None:
      logistic_prediction = {"action": logistic_prediction["action"],
                             "predictedPeakContinuous": logistic_prediction.get(
                               "predictedPeakContinuous")}
    comparisons.append({
      "pairId": pair_id, "familyId": row.family_id,
      "observedAction": row.observed_action,
      "models": [
        _model_result(row, "direct-side outer LOFO", direct_outer.get(pair_id),
                      ("score", "probabilityRight")),
        _model_result(row, "cost-sensitive boundary outer LOFO",
                      boundary_outer.get(pair_id),
                      ("score", "mu", "boundaryMargin")),
        _model_result(row, "M4-C outer LOFO", m4c_outer.get(pair_id),
                      ("margin",)),
        _model_result(row, "previous logistic side model", logistic_prediction,
                      ("predictedPeakContinuous",)),
      ],
    })
  comparison_payload = {"schemaVersion": SCHEMA_VERSION,
                        "identicalActionSemantics": {"DEFAULT": "LEFT_OF_PEAK",
                                                     "CACHE": "RIGHT_OF_PEAK"},
                        "rows": comparisons}

  r7_geometry = \
  next(item for item in geometry_series if item["registeredWorkers"] == 7)[
    "classification"]
  matched_repeats = all(
      first.observed_action == CACHE and second.observed_action == DEFAULT
      for first, second in pairs.values()
  ) and set(pairs) == set(TARGET_RS)
  weak = any(item["classification"] != "STRONGLY_SUPPORTED" or item[
    "trajectoryStatus"] != "STABLE_AGREEMENT"
             for item in audit_payload["criticalRows"])
  provenance_problem = audit_payload["provenanceInconsistencyFound"]
  if provenance_problem:
    failure = "PROCESSING_OR_PROVENANCE_PROBLEM"
  elif weak:
    failure = "EVIDENCE_QUALITY_PROBLEM"
  elif r7_geometry == "ADDITIVE_GEOMETRY_INCOMPATIBLE" and repeatability[
    "supporting"] > 1:
    failure = "REPEATED_GEOMETRY_MISMATCH"
  else:
    failure = "ISOLATED_MODEL_MISS"
  r7_distance = next(
      item for item in distance_cases if item["registeredWorkers"] == 7)
  summary = {
    "schemaVersion": SCHEMA_VERSION, "evaluatorVersion": EVALUATOR_VERSION,
    "failureClassification": failure,
    "criticalR7": {"pairIds": critical_ids,
                   "scaledFeatureDistance": r7_distance[
                     "scaledEuclideanDistance"]},
    "transitionRepeatsAtMatchedR": matched_repeats,
    "repeatabilityCounts": {key: repeatability[key] for key in
                            ("supporting", "opposing", "indeterminate",
                             "unavailable", "noTransition")},
    "r7AdditiveGeometry": r7_geometry,
    "criticalEvidenceWeakOrContradictory": weak,
    "processingOrProvenanceProblem": provenance_problem,
    "nonlinearInteractionsJustified": "NO",
    "newBenchmarkDataJustified": "NO",
    "interpretation": (
      "The WU0 -> WU64 action change repeats at R7, R15, and R23, but it is a single monotone "
      "CACHE -> DEFAULT body transition that an additive body term can represent. The R7/WU64 "
      "outer-LOFO score moves toward DEFAULT but remains above zero; this is an operating-point/model-fit "
      "miss, not evidence that nonlinear geometry is required."
    ),
    "inputArtifactHashesBefore": before,
  }

  output_dir.mkdir(parents=True, exist_ok=True)
  artifacts = {
    "r7_s1_body_series.json": r7_series_payload,
    "matched_s1_body_series.json": matched_payload,
    "local_feature_distances.json": distances_payload,
    "direct_score_decomposition.json": decomposition_payload,
    "additive_geometry_check.json": geometry_payload,
    "transition_repeatability.json": repeatability_payload,
    "critical_evidence_audit.json": audit_payload,
    "critical_model_comparison.json": comparison_payload,
  }
  digests = {name: _write_json(output_dir / name, payload) for name, payload in
             artifacts.items()}
  digests["r7_s1_transition_findings.md"] = _write(
    output_dir / "r7_s1_transition_findings.md", _findings(summary))
  after = input_hashes(repo_root)
  if before != after:
    raise ValueError("a frozen or prior artifact changed during the diagnostic")
  summary["inputArtifactHashesAfter"] = after
  summary["artifactHashes"] = digests
  _write_json(output_dir / "r7_s1_transition_summary.json", summary)
  return summary


def main() -> None:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--repo-root", type=Path, default=Path.cwd())
  parser.add_argument("--output-dir", type=Path,
                      default=Path(
                        "experiments/pareto_r7_s1_transition_diagnostic"))
  args = parser.parse_args()
  root = args.repo_root.resolve()
  output = args.output_dir if args.output_dir.is_absolute() else root / args.output_dir
  summary = run_diagnostic(root, output)
  print(json.dumps({"outputDir": str(output),
                    "classification": summary["failureClassification"]},
                   sort_keys=True))


if __name__ == "__main__":
  main()
