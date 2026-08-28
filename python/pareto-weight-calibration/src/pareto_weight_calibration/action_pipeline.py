"""Cost-sensitive CACHE-vs-DEFAULT training with nested physical-family LOFO."""

from __future__ import annotations

import argparse
from concurrent.futures import ProcessPoolExecutor
import csv
from dataclasses import replace
import hashlib
import json
import math
import os
from pathlib import Path
from typing import Any, Sequence

import numpy as np

from pareto_weight_calibration.action_model import (
  BOUNDARY_STRUCTURES,
  CACHE,
  DEFAULT,
  INDETERMINATE,
  ActionRow,
  attach_losses,
  evaluate_action_predictions,
  feature_values,
  fit_boundary,
  fit_m4c,
  fold_influence,
  inner_select_fixed_boundary,
  inner_select_m4c,
  inner_validate_boundary,
  predict_boundary,
  predict_fixed_boundary,
  predict_m4c,
)
from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.types import DomainConfig

SCHEMA_VERSION = 1
TRAINER_VERSION = "cost-sensitive-action-boundary-v1"
BOOTSTRAP_SEED = 0x455548454452414C
BOOTSTRAP_REPLICATES = 10_000
FROZEN_INPUTS = (
  "experiments/pareto_training_step5/training_pairs.tsv",
  "experiments/pareto_training_step5/step5_candidate_model.json",
  "experiments/pareto_training_step5/identifiability_audit.json",
  "experiments/pareto_training_step5/pipeline_summary.json",
  "experiments/pareto_peak_training/family_curves.json",
  "experiments/pareto_peak_training/lofo_peak_results.json",
  "experiments/pareto_peak_training/observed_peaks.json",
  "experiments/pareto_peak_training/peak_model_candidate.json",
  "experiments/pareto_peak_training/plateau_escape_cases.json",
  "experiments/pareto_peak_training/shape_fit_results.json",
  "experiments/pareto_side_of_peak_evaluation/side_of_peak_results.json",
  "experiments/productivity_participation_domain.json",
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
  return json.dumps(_jsonable(payload), indent=2, sort_keys=True,
                    allow_nan=False) + "\n"


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


def _verified_json(path: Path) -> dict[str, Any]:
  ChecksumVerifier.verify_file(path, require_sidecar=True)
  return json.loads(path.read_text(encoding="utf-8"))


def frozen_hashes(repo_root: Path) -> dict[str, str]:
  result = {}
  for relative in FROZEN_INPUTS:
    path = repo_root / relative
    ChecksumVerifier.verify_file(path, require_sidecar=True)
    result[relative] = ChecksumVerifier.compute_sha256(path)
  return result


def _action_for_outcome(outcome: str) -> str:
  mapping = {
    "K_WINS": DEFAULT,
    "K_MINUS_1_WINS": CACHE,
    "STABLE_TIE": INDETERMINATE,
  }
  if outcome not in mapping:
    raise ValueError(f"unsupported frozen outcome {outcome!r}")
  return mapping[outcome]


def build_action_rows(
    training_pairs_path: Path, family_curves_path: Path
) -> tuple[list[ActionRow], dict[str, Any]]:
  ChecksumVerifier.verify_file(training_pairs_path, require_sidecar=True)
  curves = _verified_json(family_curves_path)
  context_by_pair: dict[str, dict[str, Any]] = {}
  for family in curves["families"]:
    for context in family["current_contexts"]:
      pair_id = context["pair_id"]
      if pair_id in context_by_pair:
        raise ValueError(f"duplicate family context {pair_id}")
      context_by_pair[pair_id] = {
        "familyId": family["family_id"],
        "sourceCount": int(family["source_count"]),
        "workUnits": int(family["work_units"]),
        "context": context,
      }
  with training_pairs_path.open("r", encoding="utf-8", newline="") as stream:
    source_rows = list(csv.DictReader(stream, delimiter="\t"))
  if len(source_rows) != 135 or len(context_by_pair) != 135:
    raise ValueError("canonical frozen inventory must contain exactly 135 rows")
  if {row["pairId"] for row in source_rows} != set(context_by_pair):
    raise ValueError("training-pair and family-curve inventories disagree")

  preliminary: list[ActionRow] = []
  for source in source_rows:
    indexed = context_by_pair[source["pairId"]]
    context = indexed["context"]
    for key, observed, expected in (
        ("current K", int(source["K"]), int(context["current_k"])),
        ("registered workers", int(source["registeredWorkers"]),
         int(context["registered_workers"])),
    ):
      if observed != expected:
        raise ValueError(f"{source['pairId']}: {key} mismatch")
    for key, observed, expected in (
        ("current contention", float(source["c_active"]),
         float(context["contention"])),
        ("current productive handles", float(source["P_active"]),
         float(context["productive_handles"])),
        ("current body transform", float(source["b_active"]),
         float(context["body_log"])),
    ):
      if not math.isclose(observed, expected, rel_tol=0.0, abs_tol=1e-9):
        raise ValueError(f"{source['pairId']}: {key} mismatch")
    outcome = source["effectiveOutcome"]
    action = _action_for_outcome(outcome)
    expected_y = {DEFAULT: 0.0, CACHE: 1.0, INDETERMINATE: 0.5}[action]
    if float(source["y"]) != expected_y:
      raise ValueError(
        f"{source['pairId']}: frozen label/action mapping mismatch")
    delta = float(source["basisDeltaThroughput"])
    uncertainty = float(source["basisUncertainty"])
    observed_loss = 0.0 if action == INDETERMINATE else abs(delta)
    supported_loss = 0.0 if action == INDETERMINATE else max(0.0, abs(
      delta) - uncertainty)
    denominator = max(
        float(source["basisThroughput_K"]),
        float(source["basisThroughput_KMinus1"]),
    )
    if action != INDETERMINATE and supported_loss <= 0.0:
      raise ValueError(
        f"{source['pairId']}: decisive row has no supported advantage")
    preliminary.append(
        ActionRow(
            pair_id=source["pairId"],
            family_id=indexed["familyId"],
            current_k=int(source["K"]),
            registered_workers=int(source["registeredWorkers"]),
            source_count=indexed["sourceCount"],
            work_units=indexed["workUnits"],
            productive_handles=float(source["P_active"]),
            body_log=float(source["b_active"]),
            body_cost_ns=float(source["smoothedBodyCostNs_active"]),
            contention=float(source["c_active"]),
            observed_action=action,
            supported_wrong_action_loss=supported_loss,
            observed_wrong_action_loss=observed_loss,
            supported_relative_wrong_action_loss=supported_loss / denominator,
            observed_relative_wrong_action_loss=observed_loss / denominator,
            evidence_weight=float(source["pairWeight"]),
            family_scale=0.0,
            influence_weight=0.0,
            effective_outcome=outcome,
            evidence_basis=source["labelEvidenceBasis"],
            basis_throughput_k=float(source["basisThroughput_K"]),
            basis_throughput_k_minus_1=float(source["basisThroughput_KMinus1"]),
            basis_delta=delta,
            basis_uncertainty=uncertainty,
            runtime_commit=source["runtimeCommit"],
            topology_id=source["topologyId"],
            k_run_path=source["kRunPath"],
            k_run_sha256=source["kRunSha256"],
            k_minus_1_run_path=source["kMinus1RunPath"],
            k_minus_1_run_sha256=source["kMinus1RunSha256"],
        )
    )
  preliminary.sort(key=lambda row: row.pair_id)
  influence, scales = fold_influence(preliminary)
  rows = [
    replace(
        row,
        family_scale=float(scales[row.family_id]),
        influence_weight=float(weight),
    )
    for row, weight in zip(preliminary, influence, strict=True)
  ]
  total = float(np.sum(influence))
  n_eff = total * total / float(np.dot(influence, influence))
  summary = {
    "rawRowCount": len(rows),
    "decisiveRowCount": sum(row.decisive for row in rows),
    "indeterminateRowCount": sum(not row.decisive for row in rows),
    "physicalFamilyCount": len({row.family_id for row in rows}),
    "totalInfluence": total,
    "effectiveSampleSize": n_eff,
    "actionCounts": {
      action: sum(row.observed_action == action for row in rows)
      for action in (DEFAULT, CACHE, INDETERMINATE)
    },
    "telemetryRule": "Only active/current-K P, body cost, and contention are predictive inputs.",
  }
  return rows, summary


def _compact_metrics(metrics: dict[str, Any]) -> dict[str, Any]:
  return {key: value for key, value in metrics.items() if key != "families"}


def _compact_boundary_selection(selection: dict[str, Any]) -> dict[str, Any]:
  return {
    "folds": selection["folds"],
    "selected": {
      **{key: selection["selected"][key] for key in
         ("candidateId", "structure", "l2", "temperature")},
      "metrics": _compact_metrics(selection["selected"]["metrics"]),
    },
    "admissions": selection["admissions"],
  }


def _compact_grid(selection: dict[str, Any]) -> dict[str, Any]:
  return {
    "folds": selection["folds"],
    "candidates": [
      {**{key: value for key, value in candidate.items() if key != "metrics"},
       "metrics": _compact_metrics(candidate["metrics"])}
      for candidate in selection["candidates"]
    ],
    "admissions": selection.get("admissions", []),
    "selectedCandidateId": selection["selected"]["candidateId"],
  }


def _outer_task(args: tuple[str, list[ActionRow], DomainConfig]) -> dict[
  str, Any]:
  held_family, rows, domain = args
  train = [row for row in rows if row.family_id != held_family]
  held = [row for row in rows if row.family_id == held_family]
  if not held or any(row.family_id == held_family for row in train):
    raise ValueError("invalid outer family partition")

  boundary_selection = inner_validate_boundary(train)
  selected = boundary_selection["selected"]
  boundary_fit = fit_boundary(
      train, selected["structure"], selected["l2"], selected["temperature"]
  )
  boundary_predictions = predict_boundary(boundary_fit, held)

  m4c_selection = inner_select_m4c(train, domain)
  m4c_fit = fit_m4c(train, m4c_selection["selected"]["l2"], domain)
  m4c_predictions = predict_m4c(m4c_fit, held)

  fixed_selection = inner_select_fixed_boundary(train)
  fixed_predictions = predict_fixed_boundary(
      held, fixed_selection["selected"]["fraction"]
  )
  return {
    "heldOutFamily": held_family,
    "trainingFamilyCount": len({row.family_id for row in train}),
    "trainingPairIdsHash": hashlib.sha256(
        "\n".join(sorted(row.pair_id for row in train)).encode()
    ).hexdigest(),
    "boundarySelection": _compact_boundary_selection(boundary_selection),
    "boundaryFit": boundary_fit.serialize(),
    "boundaryPredictions": boundary_predictions,
    "m4cSelection": {
      "folds": m4c_selection["folds"],
      "selectedCandidateId": m4c_selection["selected"]["candidateId"],
      "selectedL2": m4c_selection["selected"]["l2"],
    },
    "m4cFit": m4c_fit,
    "m4cPredictions": m4c_predictions,
    "fixedSelection": {
      "selectedCandidateId": fixed_selection["selected"]["candidateId"],
      "selectedFraction": fixed_selection["selected"]["fraction"],
    },
    "fixedPredictions": fixed_predictions,
  }


def _ordered_predictions(folds: Sequence[dict[str, Any]], key: str) -> list[
  dict[str, Any]]:
  return sorted(
      [prediction for fold in folds for prediction in fold[key]],
      key=lambda prediction: prediction["pairId"],
  )


def _evaluate_reference(
    rows: Sequence[ActionRow], side_results: dict[str, Any], model_key: str
) -> tuple[list[ActionRow], list[dict[str, Any]], dict[str, Any]]:
  rows_by_pair = {row.pair_id: row for row in rows}
  reference_rows = []
  predictions = []
  for case in side_results["cases"]:
    row = rows_by_pair[case["pairId"]]
    reference_rows.append(row)
    prediction = {
      "pairId": row.pair_id,
      "familyId": row.family_id,
      "currentK": row.current_k,
      "action": case[model_key]["action"],
    }
    if model_key == "logistic":
      mu = float(case[model_key]["predictedPeakContinuous"])
      prediction.update(
          {"mu": mu, "score": row.current_k - mu,
           "boundaryMargin": abs(row.current_k - mu)}
      )
    else:
      prediction["margin"] = float(case[model_key]["margin"])
    predictions.append(prediction)
  ordered = sorted(zip(reference_rows, predictions, strict=True),
                   key=lambda item: item[0].pair_id)
  reference_rows = [item[0] for item in ordered]
  predictions = [item[1] for item in ordered]
  return reference_rows, predictions, evaluate_action_predictions(
    reference_rows, predictions)


def _subset_diagnostic(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]],
    pair_ids: set[str]
) -> dict[str, Any]:
  indexed = {prediction["pairId"]: prediction for prediction in predictions}
  subset_rows = [row for row in rows if row.pair_id in pair_ids]
  subset_predictions = [indexed[row.pair_id] for row in subset_rows]
  return evaluate_action_predictions(subset_rows,
                                     subset_predictions) if subset_rows else {
    "rowCount": 0}


def _side_diagnostics(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]],
    side_results: dict[str, Any]
) -> dict[str, Any]:
  distance_buckets = {"1": set(), "2-3": set(), "4-6": set(), "7+": set()}
  flat_ids = set()
  for case in side_results["cases"]:
    distance = int(case["distanceToObservedPeakInterval"])
    if distance == 1:
      distance_buckets["1"].add(case["pairId"])
    elif 2 <= distance <= 3:
      distance_buckets["2-3"].add(case["pairId"])
    elif 4 <= distance <= 6:
      distance_buckets["4-6"].add(case["pairId"])
    elif distance >= 7:
      distance_buckets["7+"].add(case["pairId"])
    if case["localStatus"]["isFlat"] and case["observedSide"] != INDETERMINATE:
      flat_ids.add(case["pairId"])
  return {
    "distanceFromObservedPeak": {
      bucket: _subset_diagnostic(rows, predictions, pair_ids)
      for bucket, pair_ids in distance_buckets.items()
    },
    "flatLocal": _subset_diagnostic(rows, predictions, flat_ids),
    "flatLocalScopeNote": (
      "Small retained subset only; local flatness and decisive global side come from the "
      "frozen peak evaluation, while action cost remains the frozen adjacent supported loss."
    ),
  }


def _error_rows(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]],
    wrong_type: str
) -> list[dict[str, Any]]:
  attached = attach_losses(rows, predictions)
  rows_by_pair = {row.pair_id: row for row in rows}
  result = []
  for item in attached:
    if item["loss"]["wrongType"] != wrong_type:
      continue
    row = rows_by_pair[item["pairId"]]
    result.append(
        {
          "familyId": row.family_id,
          "pairId": row.pair_id,
          "currentK": row.current_k,
          "registeredWorkers": row.registered_workers,
          "sourceCount": row.source_count,
          "workUnits": row.work_units,
          "productiveHandles": row.productive_handles,
          "productiveHandleRatio": row.p_ratio,
          "bodyCostNs": row.body_cost_ns,
          "bodyLog": row.body_log,
          "currentContention": row.contention,
          "observedAction": row.observed_action,
          "predictedAction": item["action"],
          "supportedWrongActionLoss": item["loss"]["supportedLoss"],
          "supportedRelativeWrongActionLoss": item["loss"][
            "supportedRelativeLoss"],
          "observedWrongActionLoss": item["loss"]["observedLoss"],
          "mu": item.get("mu"),
          "scoreKMinusMu": item.get("score"),
          "boundaryMargin": item.get("boundaryMargin"),
          "evidenceWeight": row.evidence_weight,
          "familyInfluenceWeight": row.influence_weight,
        }
    )
  return sorted(
      result,
      key=lambda item: (-item["supportedWrongActionLoss"], item["familyId"],
                        item["currentK"]),
  )


def _paired_bootstrap(
    first: dict[str, Any], second: dict[str, Any],
    common_families: Sequence[str]
) -> dict[str, Any]:
  families = tuple(sorted(common_families))
  if len(families) < 2:
    return {"familyCount": len(families), "status": "INSUFFICIENT_FAMILIES"}
  rng = np.random.default_rng(BOOTSTRAP_SEED)
  first_regret = np.asarray(
      [first[family]["supportedRelativeRegret"] for family in families])
  second_regret = np.asarray(
      [second[family]["supportedRelativeRegret"] for family in families])
  first_accuracy = np.asarray(
      [first[family]["weightedActionAccuracy"] or 0.0 for family in families])
  second_accuracy = np.asarray(
      [second[family]["weightedActionAccuracy"] or 0.0 for family in families])
  regret_differences = np.empty(BOOTSTRAP_REPLICATES)
  accuracy_differences = np.empty(BOOTSTRAP_REPLICATES)
  tail_differences = np.empty(BOOTSTRAP_REPLICATES)
  for index in range(BOOTSTRAP_REPLICATES):
    sampled = rng.integers(0, len(families), len(families))
    regret_differences[index] = float(
      np.mean(first_regret[sampled] - second_regret[sampled]))
    accuracy_differences[index] = float(
      np.mean(first_accuracy[sampled] - second_accuracy[sampled]))
    tail_differences[index] = float(
        np.quantile(first_regret[sampled], 0.9) - np.quantile(
            second_regret[sampled], 0.9)
    )

  def estimate(values: np.ndarray, observed: float) -> dict[str, Any]:
    return {
      "differenceFirstMinusSecond": observed,
      "bootstrapMean": float(np.mean(values)),
      "percentile95Interval": [float(np.quantile(values, 0.025)),
                               float(np.quantile(values, 0.975))],
    }

  return {
    "familyCount": len(families),
    "replicates": BOOTSTRAP_REPLICATES,
    "seed": BOOTSTRAP_SEED,
    "resamplingUnit": "physical family",
    "familyBalancedSupportedRelativeRegret": estimate(
        regret_differences, float(np.mean(first_regret - second_regret))
    ),
    "familyBalancedWeightedActionAccuracy": estimate(
        accuracy_differences, float(np.mean(first_accuracy - second_accuracy))
    ),
    "p90FamilySupportedRelativeRegret": estimate(
        tail_differences,
        float(np.quantile(first_regret, 0.9) - np.quantile(second_regret, 0.9)),
    ),
    "note": "The p90 bootstrap is descriptive; 49 or fewer independent families limit tail precision.",
  }


def _family_diagnostics(
    rows: Sequence[ActionRow], predictions: Sequence[dict[str, Any]],
    metrics: dict[str, Any], folds: Sequence[dict[str, Any]]
) -> list[dict[str, Any]]:
  rows_by_family: dict[str, list[ActionRow]] = {}
  pred_by_id = {prediction["pairId"]: prediction for prediction in predictions}
  fold_by_family = {fold["heldOutFamily"]: fold for fold in folds}
  for row in rows:
    rows_by_family.setdefault(row.family_id, []).append(row)
  ordered = sorted(
      metrics["families"],
      key=lambda family: (
        -metrics["families"][family]["supportedRelativeRegret"], family),
  )
  result = []
  for family in ordered[:10]:
    fold = fold_by_family[family]
    fit = fold["boundaryFit"]
    beta = fit["coefficients"]
    scaler = fit["scaler"]
    cases = []
    for row in sorted(rows_by_family[family], key=lambda item: item.current_k):
      prediction = pred_by_id[row.pair_id]
      values = feature_values(row)
      contributions = {"intercept": beta[0]}
      for index, name in enumerate(scaler["names"]):
        contributions[name] = beta[index + 1] * (
            (values[name] - scaler["means"][index]) / scaler["scales"][index]
        )
      cases.append(
          {
            "pairId": row.pair_id,
            "currentK": row.current_k,
            "observedAction": row.observed_action,
            "predictedAction": prediction["action"],
            "mu": prediction["mu"],
            "supportedWrongActionLoss": row.supported_wrong_action_loss,
            "physicalFeatures": values,
            "etaContributions": contributions,
          }
      )
    training_rows = [row for row in rows if row.family_id != family]
    feature_ranges = {
      name: (min(feature_values(row)[name] for row in training_rows),
             max(feature_values(row)[name] for row in training_rows))
      for name in BOUNDARY_STRUCTURES[fit["structure"]]
    }
    extrapolated = any(
        any(value < feature_ranges[name][0] or value > feature_ranges[name][1]
            for name, value in feature_values(row).items() if
            name in feature_ranges)
        for row in rows_by_family[family]
    )
    result.append(
        {
          "familyId": family,
          "metrics": metrics["families"][family],
          "heldOutSelectedStructure": fit["structure"],
          "failureGeometry": "EXTRAPOLATION" if extrapolated else "INTERPOLATION",
          "cases": cases,
        }
    )
  return result


def _findings(
    dataset: dict[str, Any], boundary: dict[str, Any], m4c: dict[str, Any],
    logistic: dict[str, Any],
    fixed: dict[str, Any], full_selection: dict[str, Any],
    diagnostics: dict[str, Any], bootstrap: dict[str, Any],
    acceptance: dict[str, Any]
) -> str:
  selected = full_selection["selected"]
  features = ", ".join(BOUNDARY_STRUCTURES[selected["structure"]])
  flat = diagnostics["flatLocal"]
  poor = sorted(boundary["families"], key=lambda family: (
    -boundary["families"][family]["supportedRelativeRegret"], family))[:5]
  return "\n".join(
      [
        "# Cost-sensitive local participation action model",
        "",
        "No benchmarks were rerun and no production Java was changed. All targets and row-level costs come from the checksum-verified frozen Step 5 evidence.",
        "",
        "## Result",
        "",
        f"- Full-data selected structure: `{selected['structure']}` with features {features}.",
        f"- Nested outer-LOFO supported relative action regret: {boundary['supportedRelativeRegret']:.8f}.",
        f"- Proper M4-C-LOFO supported relative action regret: {m4c['supportedRelativeRegret']:.8f}.",
        f"- Existing logistic-LOFO reference regret on its {logistic['rowCount']}-row common cohort: {logistic['supportedRelativeRegret']:.8f}.",
        f"- Fixed physical-boundary LOFO regret: {fixed['supportedRelativeRegret']:.8f}.",
        f"- Boundary / M4-C worst-family regrets: {boundary['worstFamilySupportedRelativeRegret']:.8f} / {m4c['worstFamilySupportedRelativeRegret']:.8f}.",
        f"- Boundary weighted action accuracy: {boundary['weightedActionAccuracy']:.6f}.",
        f"- False CACHE: rate {boundary['falseCache']['rate']:.6f}, supported relative regret {boundary['falseCache']['supportedRelativeRegret']:.8f}.",
        f"- False DEFAULT: rate {boundary['falseDefault']['rate']:.6f}, supported relative regret {boundary['falseDefault']['supportedRelativeRegret']:.8f}.",
        f"- Largest false CACHE / false DEFAULT supported relative losses: {boundary['falseCache']['largestSingleSupportedRelativeLoss']:.8f} / {boundary['falseDefault']['largestSingleSupportedRelativeLoss']:.8f}.",
        f"- Flat-local evaluable subset: {flat.get('decisiveCount', 0)} decisive rows, weighted accuracy {flat.get('weightedActionAccuracy')}.",
        f"- Highest-regret held-out families: {', '.join(poor)}.",
        f"- Advancement decision: {acceptance['decision']}.",
        "",
        "## Interpretation",
        "",
        "The model is a bounded single-crossing boundary: increasing K at fixed current-state telemetry cannot change CACHE back to DEFAULT. Current-state productive-handle ratio, worker count, body cost, and contention are the only candidate inputs; family identity and counterfactual telemetry are excluded.",
        "",
        "Complexity is admitted only when grouped inner validation lowers pooled supported regret, does not materially worsen the worst family, and improves more than one family. The full-data selection and every outer fold apply that same rule.",
        "",
        "Paired uncertainty uses deterministic physical-family resampling, not adjacent-row resampling. Tail intervals remain descriptive because the number of independent families is limited.",
        "",
        "## Acceptance",
        "",
        *[f"- {item['criterion']}: {item['status']} - {item['detail']}" for item
          in acceptance["criteria"]],
        "",
        "The candidate artifact is training output only. It is not installed into the runtime.",
      ]
  )


def run_action_pipeline(repo_root: Path, output_dir: Path,
    worker_count: int | None = None) -> dict[str, Any]:
  before_hashes = frozen_hashes(repo_root)
  paths = {relative: repo_root / relative for relative in FROZEN_INPUTS}
  rows, dataset_summary = build_action_rows(
      paths["experiments/pareto_training_step5/training_pairs.tsv"],
      paths["experiments/pareto_peak_training/family_curves.json"],
  )
  domain = DomainConfig.from_dict(
    _verified_json(paths["experiments/productivity_participation_domain.json"]))
  side_results = _verified_json(paths[
                                  "experiments/pareto_side_of_peak_evaluation/side_of_peak_results.json"])
  input_hash = hashlib.sha256(
    _canonical_json([row.to_dict() for row in rows]).encode()).hexdigest()
  source_hash = hashlib.sha256(
      (
          (
                repo_root / "python/pareto-weight-calibration/src/pareto_weight_calibration/action_model.py").read_bytes()
          + (
                repo_root / "python/pareto-weight-calibration/src/pareto_weight_calibration/action_pipeline.py").read_bytes()
      )
  ).hexdigest()
  dataset_payload = {
    "schemaVersion": SCHEMA_VERSION,
    "trainerVersion": TRAINER_VERSION,
    "objective": "minimize frozen supported throughput regret from a wrong local CACHE-vs-DEFAULT action",
    "actionMapping": {"K_WINS": DEFAULT, "K_MINUS_1_WINS": CACHE,
                      "STABLE_TIE": INDETERMINATE},
    "familyInfluence": "u_i = evidenceWeight_i / max(1, sum evidenceWeight within physical family)",
    "summary": dataset_summary,
    "rows": [row.to_dict() for row in rows],
    "inputArtifactHashes": before_hashes,
  }

  families = sorted({row.family_id for row in rows})
  tasks = [(family, rows, domain) for family in families]
  workers = min(32, max(1, worker_count or os.cpu_count() or 1), len(tasks))
  cache_path = Path("/tmp/euhedral_action_outer_cache.json")
  cache_key = hashlib.sha256(f"{input_hash}:{source_hash}".encode()).hexdigest()
  outer_folds = None
  if cache_path.is_file():
    cached = json.loads(cache_path.read_text(encoding="utf-8"))
    if cached.get("cacheKey") == cache_key:
      outer_folds = cached["folds"]
  if outer_folds is None:
    with ProcessPoolExecutor(max_workers=workers) as executor:
      outer_folds = list(executor.map(_outer_task, tasks))
    _write_json(cache_path, {"cacheKey": cache_key, "folds": outer_folds})
  outer_folds.sort(key=lambda fold: fold["heldOutFamily"])
  boundary_predictions = _ordered_predictions(outer_folds,
                                              "boundaryPredictions")
  m4c_predictions = _ordered_predictions(outer_folds, "m4cPredictions")
  fixed_predictions = _ordered_predictions(outer_folds, "fixedPredictions")
  boundary_metrics = evaluate_action_predictions(rows, boundary_predictions)
  m4c_metrics = evaluate_action_predictions(rows, m4c_predictions)
  fixed_metrics = evaluate_action_predictions(rows, fixed_predictions)

  logistic_rows, logistic_predictions, logistic_metrics = _evaluate_reference(
    rows, side_results, "logistic")
  fullfit_rows, fullfit_predictions, fullfit_metrics = _evaluate_reference(rows,
                                                                           side_results,
                                                                           "m4c")
  diagnostics = _side_diagnostics(rows, boundary_predictions, side_results)
  full_selection = inner_validate_boundary(rows)
  selected = full_selection["selected"]
  final_fit = fit_boundary(rows, selected["structure"], selected["l2"],
                           selected["temperature"])
  full_m4c_selection = inner_select_m4c(rows, domain)
  full_fixed_selection = inner_select_fixed_boundary(rows)

  common_boundary_logistic = _subset_diagnostic(rows, boundary_predictions,
                                                {row.pair_id for row in
                                                 logistic_rows})
  uncertainty = {
    "boundaryMinusM4CLofo": _paired_bootstrap(
        boundary_metrics["families"], m4c_metrics["families"], families
    ),
    "boundaryMinusLogisticLofoCommonCohort": _paired_bootstrap(
        common_boundary_logistic["families"], logistic_metrics["families"],
        sorted(logistic_metrics["families"])
    ),
  }
  improved_families = sum(
      boundary_metrics["families"][family]["supportedRelativeRegret"]
      < m4c_metrics["families"][family]["supportedRelativeRegret"] - 1e-12
      for family in families
  )
  criteria = [
    {"criterion": "pooled regret competitive with M4-C-LOFO",
     "status": "PASS" if boundary_metrics["supportedRelativeRegret"] <=
                         m4c_metrics[
                           "supportedRelativeRegret"] + 1e-12 else "FAIL",
     "detail": f"boundary={boundary_metrics['supportedRelativeRegret']:.8f}; M4-C={m4c_metrics['supportedRelativeRegret']:.8f}"},
    {"criterion": "worst-family regret competitive with M4-C-LOFO",
     "status": "PASS" if boundary_metrics[
                           "worstFamilySupportedRelativeRegret"] <= m4c_metrics[
                           "worstFamilySupportedRelativeRegret"] + 1e-12 else "FAIL",
     "detail": f"boundary={boundary_metrics['worstFamilySupportedRelativeRegret']:.8f}; M4-C={m4c_metrics['worstFamilySupportedRelativeRegret']:.8f}"},
    {"criterion": "false DEFAULT regret controlled",
     "status": "PASS" if boundary_metrics["falseDefault"][
                           "supportedRelativeRegret"] <=
                         m4c_metrics["falseDefault"][
                           "supportedRelativeRegret"] + 1e-12 else "FAIL",
     "detail": f"boundary={boundary_metrics['falseDefault']['supportedRelativeRegret']:.8f}; M4-C={m4c_metrics['falseDefault']['supportedRelativeRegret']:.8f}"},
    {"criterion": "false CACHE regret controlled",
     "status": "PASS" if boundary_metrics["falseCache"][
                           "supportedRelativeRegret"] <=
                         m4c_metrics["falseCache"][
                           "supportedRelativeRegret"] + 1e-12 else "FAIL",
     "detail": f"boundary={boundary_metrics['falseCache']['supportedRelativeRegret']:.8f}; M4-C={m4c_metrics['falseCache']['supportedRelativeRegret']:.8f}"},
    {"criterion": "improvement spans multiple physical families",
     "status": "PASS" if improved_families > 1 else "FAIL",
     "detail": f"improved family count={improved_families}"},
    {"criterion": "current-state telemetry only", "status": "PASS",
     "detail": "features derive only from active/current-K P, R, body, and contention"},
    {"criterion": "deterministic family-clustered uncertainty",
     "status": "PASS",
     "detail": f"{BOOTSTRAP_REPLICATES} paired physical-family resamples with frozen seed"},
  ]
  acceptance = {
    "decision": "ADVANCE_TO_SEPARATE_INTEGRATION_PHASE" if all(
        item["status"] == "PASS" for item in criteria) else "DO_NOT_INTEGRATE",
    "productionJavaChanged": False,
    "criteria": criteria,
  }

  false_cache = _error_rows(rows, boundary_predictions, "FALSE_CACHE")
  false_default = _error_rows(rows, boundary_predictions, "FALSE_DEFAULT")
  family_diagnostics = _family_diagnostics(rows, boundary_predictions,
                                           boundary_metrics, outer_folds)
  candidate = {
    "schemaVersion": SCHEMA_VERSION,
    "trainerVersion": TRAINER_VERSION,
    "status": "TRAINING_ONLY_NOT_INSTALLED",
    "productionAuthorized": False,
    "model": {
      **final_fit.serialize(),
      "form": "score = K - mu(state); mu = 1 + (R - 1) * sigmoid(eta(state))",
      "featureDefinitions": {
        "pRatio": "current productive handles P / registered workers R",
        "logR": "ln(current registered workers R)",
        "body": "ln(1 + current smoothed body cost ns)",
        "contention": "current-K contention scaled in [0,1]",
        "bodyDeficit": "body * (1 - P/R)",
        "contentionDeficit": "contention * (1 - P/R)",
      },
      "legalParticipationDomain": {"minimumK": 1,
                                   "maximumK": "registeredWorkers"},
      "actionMapping": {"score>0": CACHE, "score<=0": DEFAULT},
    },
    "selectedHyperparameters": {"structure": selected["structure"],
                                "l2": selected["l2"],
                                "temperature": selected["temperature"]},
    "trainingDatasetHash": input_hash,
    "trainingFamilyCount": len(families),
    "nestedOuterValidationMetrics": _compact_metrics(boundary_metrics),
    "acceptance": acceptance,
    "provenance": {"inputArtifactHashes": before_hashes,
                   "counterfactualTelemetryUsed": False},
  }

  output_dir.mkdir(parents=True, exist_ok=True)
  digests: dict[str, str] = {}
  digests["dataset"] = _write_json(output_dir / "action_training_dataset.json",
                                   dataset_payload)
  digests["grid"] = _write_json(
      output_dir / "action_model_grid_results.json",
      {"schemaVersion": SCHEMA_VERSION,
       "boundary": _compact_grid(full_selection),
       "m4c": _compact_grid(full_m4c_selection),
       "fixed": _compact_grid(full_fixed_selection)},
  )
  digests["outer"] = _write_json(
      output_dir / "action_model_outer_lofo.json",
      {"schemaVersion": SCHEMA_VERSION, "trainerVersion": TRAINER_VERSION,
       "outerUnit": "physical family", "workerCount": workers,
       "folds": outer_folds, "predictions": boundary_predictions,
       "metrics": _compact_metrics(boundary_metrics),
       "diagnostics": diagnostics, "bootstrap": uncertainty},
  )
  digests["families"] = _write_json(
      output_dir / "action_model_family_metrics.json",
      {"schemaVersion": SCHEMA_VERSION,
       "boundaryFamilies": boundary_metrics["families"],
       "m4cLofoFamilies": m4c_metrics["families"],
       "fixedFamilies": fixed_metrics["families"],
       "highRegretDiagnostics": family_diagnostics},
  )
  digests["falseCache"] = _write_json(
    output_dir / "action_model_false_cache.json",
    {"schemaVersion": SCHEMA_VERSION, "count": len(false_cache),
     "cases": false_cache})
  digests["falseDefault"] = _write_json(
    output_dir / "action_model_false_default.json",
    {"schemaVersion": SCHEMA_VERSION, "count": len(false_default),
     "cases": false_default})
  digests["m4c"] = _write_json(
      output_dir / "action_model_m4c_lofo.json",
      {"schemaVersion": SCHEMA_VERSION,
       "status": "TRUE_OUTER_PHYSICAL_FAMILY_LOFO",
       "predictions": m4c_predictions, "metrics": m4c_metrics,
       "outerSelections": [{"heldOutFamily": fold["heldOutFamily"],
                            "trainingPairIdsHash": fold["trainingPairIdsHash"],
                            "selection": fold["m4cSelection"],
                            "fit": fold["m4cFit"]} for fold in outer_folds],
       "existingFullFitReference": {"cohort": "existing logistic common cohort",
                                    "metrics": fullfit_metrics}},
  )
  digests["candidate"] = _write_json(output_dir / "action_model_candidate.json",
                                     candidate)
  findings = _findings(dataset_summary, boundary_metrics, m4c_metrics,
                       logistic_metrics, fixed_metrics, full_selection,
                       diagnostics, uncertainty, acceptance)
  digests["findings"] = _write_text(output_dir / "action_model_findings.md",
                                    findings)
  after_hashes = frozen_hashes(repo_root)
  if before_hashes != after_hashes:
    raise ValueError(
      "a frozen input artifact changed during action-model training")
  summary = {
    "schemaVersion": SCHEMA_VERSION,
    "trainerVersion": TRAINER_VERSION,
    "dataset": dataset_summary,
    "selected": {"structure": selected["structure"],
                 "features": list(BOUNDARY_STRUCTURES[selected["structure"]]),
                 "l2": selected["l2"], "temperature": selected["temperature"]},
    "boundaryOuterLofo": _compact_metrics(boundary_metrics),
    "m4cOuterLofo": _compact_metrics(m4c_metrics),
    "logisticExistingLofoReference": _compact_metrics(logistic_metrics),
    "fixedOuterLofo": _compact_metrics(fixed_metrics),
    "boundaryLogisticCommonCohort": _compact_metrics(common_boundary_logistic),
    "diagnostics": diagnostics,
    "bootstrap": uncertainty,
    "acceptance": acceptance,
    "frozenInputHashesBefore": before_hashes,
    "frozenInputHashesAfter": after_hashes,
    "artifactHashes": digests,
  }
  digests["summary"] = _write_json(output_dir / "action_model_summary.json",
                                   summary)
  return summary


def main() -> None:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--repo-root", type=Path, default=Path.cwd())
  parser.add_argument("--output-dir", type=Path,
                      default=Path("experiments/pareto_action_model_training"))
  parser.add_argument("--workers", type=int, default=None)
  args = parser.parse_args()
  root = args.repo_root.resolve()
  output = args.output_dir if args.output_dir.is_absolute() else root / args.output_dir
  summary = run_action_pipeline(root, output, args.workers)
  print(json.dumps({"outputDir": str(output), "selected": summary["selected"],
                    "acceptance": summary["acceptance"]["decision"]},
                   sort_keys=True))


if __name__ == "__main__":
  main()
