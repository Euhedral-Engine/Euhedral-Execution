"""Execute the frozen, nested physical-family Model Tournament V2."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import platform
import sys
import time
from typing import Sequence

import numpy as np
from sklearn.linear_model import LogisticRegression
from threadpoolctl import threadpool_limits

from pareto_weight_calibration.action_model import (ActionRow, CACHE, DEFAULT,
                                                    action_loss)
from pareto_weight_calibration.direct_side import (_write_json, _write_text,
                                                   side_for_action)
from pareto_weight_calibration.model_tournament import (
  DATASET_PATH, INPUT_LOCK, REFERENCE_PATH, compare_reference,
  evaluate_predictions, fold_plan, load_frozen_dataset, pair_ids_hash,
  partition_families, reference_predictions, verify_frozen_inputs,
)
from pareto_weight_calibration.tournament_v2_manifest import (
  INNER_FOLDS, MODEL_ORDER, THRESHOLDS, build_manifest,
  manifest_sha256, verify_frozen_manifest, write_frozen_manifest,
)
from pareto_weight_calibration.tournament_v2_models import (
  MONOTONICITY, create_model, transformed_training_weights,
)

DEFAULT_OUTPUT = Path("experiments/pareto_model_tournament_v2/results")
V1_RESULTS = Path(
    "experiments/pareto_model_tournament/results/tournament_model_results.json")
M4C_RESULTS = Path(
    "experiments/pareto_action_model_training/action_model_m4c_lofo.json")
MANIFEST_NAME = "tournament_v2_candidate_manifest.json"
TRAINER_VERSION = "frozen-side-of-peak-model-tournament-v2"
TOLERANCE = 1e-12


def validate_output_dir(repo_root: Path, output_dir: Path) -> Path:
  output = output_dir.resolve()
  frozen_roots = [
    (repo_root / DATASET_PATH.parent).resolve(),
    (repo_root / V1_RESULTS.parent).resolve(),
    (repo_root / M4C_RESULTS.parent).resolve(),
  ]
  if any(output == root or root in output.parents or output in root.parents
         for root in frozen_roots):
    raise ValueError("V2 output must not overlap a frozen artifact directory")
  if output.exists():
    allowed = {MANIFEST_NAME, MANIFEST_NAME + ".sha256"}
    present = {path.name for path in
               output.iterdir()} if output.is_dir() else set()
    if not output.is_dir() or present - allowed:
      raise ValueError(
        "V2 output directory must be new, empty, or contain only the frozen manifest")
  return output


def _compact_metrics(metrics: dict) -> dict:
  return {
    "supportedRelativeRegret": metrics["supportedRelativeRegret"],
    "familyBalancedSupportedRelativeRegret": metrics[
      "familyBalancedSupportedRelativeRegret"],
    "worstFamilySupportedRelativeRegret": metrics[
      "worstFamilySupportedRelativeRegret"],
    "falseDefaultSupportedRelativeRegret": metrics["falseDefault"][
      "supportedRelativeRegret"],
    "falseCacheSupportedRelativeRegret": metrics["falseCache"][
      "supportedRelativeRegret"],
    "familyBalancedEvidenceWeightedSideAccuracy": metrics[
      "familyBalancedEvidenceWeightedSideAccuracy"],
  }


def _rank_key(metrics: dict, complexity: int, candidate_id: str) -> tuple:
  return (
    metrics["supportedRelativeRegret"],
    metrics["worstFamilySupportedRelativeRegret"],
    metrics["falseDefaultSupportedRelativeRegret"],
    metrics["falseCacheSupportedRelativeRegret"],
    -metrics["familyBalancedEvidenceWeightedSideAccuracy"],
    complexity,
    candidate_id,
  )


def _raw_prediction(model, rows: Sequence[ActionRow]) -> dict:
  score = model.predict_score(rows)
  probability = model.predict_probability(rows)
  return {
    "score": score,
    "probability": probability,
    "model": model,
  }


def _threshold_matrix(raw: dict, thresholds: Sequence[float],
    device: str) -> np.ndarray:
  """Evaluate every threshold from one prediction vector in one batch."""
  model = raw["model"]
  values = raw["probability"]
  cutoffs = np.asarray(thresholds, dtype=np.float64)
  if values is None:
    values = raw["score"]
    cutoffs = np.asarray([model.score_thresholds[str(t)] for t in thresholds])
  values = np.asarray(values, dtype=np.float64)
  if device in {"cuda", "auto"}:
    import torch

    if torch.cuda.is_available():
      value_tensor = torch.as_tensor(values, dtype=torch.float64,
                                     device="cuda")
      cutoff_tensor = torch.as_tensor(cutoffs, dtype=torch.float64,
                                      device="cuda")
      return (value_tensor[None, :] >= cutoff_tensor[:, None]).cpu().numpy()
  return values[None, :] >= cutoffs[:, None]


def _prediction_records(rows: Sequence[ActionRow], raw: dict, actions,
    *, model_family: str, candidate: dict,
    threshold: float, training_fold: str) -> list[dict]:
  probabilities = raw["probability"]
  records = []
  for index, row in enumerate(rows):
    action = str(actions[index])
    records.append({
      "modelFamily": model_family,
      "candidateId": candidate["id"],
      "selectedHyperparameters": candidate.get("params", {}),
      "selectedWeightTransform": candidate.get("weightTransform"),
      "selectedThreshold": threshold,
      "trainingFold": training_fold,
      "heldOutFamily": row.family_id,
      "familyId": row.family_id,
      "pairId": row.pair_id,
      "currentK": row.current_k,
      "observedAction": row.observed_action,
      "observedSide": side_for_action(row.observed_action),
      "score": float(raw["score"][index]),
      "probabilityRight": None if probabilities is None else float(
          probabilities[index]),
      "predictedSide": side_for_action(action),
      "action": action,
      "loss": action_loss(row, action),
      "supportedWrongActionLoss": row.supported_wrong_action_loss,
      "supportedRelativeWrongActionLoss":
        row.supported_relative_wrong_action_loss,
      "evidenceWeight": row.evidence_weight,
      "frozenFamilyInfluenceWeight": row.influence_weight,
    })
  return records


def _candidate_inner_predictions(train_rows, candidate, plan, device,
    thresholds=THRESHOLDS):
  predictions = {threshold: [] for threshold in thresholds}
  fit_count = 0
  for split in plan:
    fit_rows, held_rows = partition_families(
        train_rows, split["heldOutFamilies"])
    model = create_model(candidate["modelFamily"], device).fit(fit_rows,
                                                               candidate)
    fit_count += 1
    raw = _raw_prediction(model, held_rows)
    matrix = _threshold_matrix(raw, thresholds, device)
    for index, threshold in enumerate(thresholds):
      actions = np.where(matrix[index], CACHE, DEFAULT)
      predictions[threshold].extend(_prediction_records(
          held_rows, raw, actions, model_family=candidate["modelFamily"],
          candidate=candidate, threshold=threshold,
          training_fold=split["trainingFold"]))
  return predictions, fit_count


def select_inner(train_rows, candidates, plan, device) -> dict:
  """Select weight transform, model parameters, and threshold on training only."""
  records = []
  fit_count = 0
  failures = []
  for candidate in candidates:
    try:
      predictions, fits = _candidate_inner_predictions(
          train_rows, candidate, plan, device)
      fit_count += fits
      threshold_records = []
      for threshold, values in predictions.items():
        metrics = _compact_metrics(evaluate_predictions(train_rows, values))
        threshold_records.append({"threshold": threshold,
                                  "metrics": metrics})
      selected_threshold = min(
          threshold_records,
          key=lambda item: _rank_key(item["metrics"],
                                     candidate["complexity"],
                                     candidate["id"]))
      records.append({
        "candidateId": candidate["id"],
        "weightTransform": candidate["weightTransform"],
        "selectedThreshold": selected_threshold["threshold"],
        "metrics": selected_threshold["metrics"],
      })
    except Exception as error:
      failures.append({"candidateId": candidate["id"],
                       "errorType": type(error).__name__,
                       "message": str(error)})
  if not records:
    raise RuntimeError("every frozen candidate failed")
  selected_record = min(
      records,
      key=lambda item: _rank_key(
          item["metrics"],
          next(c["complexity"] for c in candidates
               if c["id"] == item["candidateId"]),
          item["candidateId"]))
  selected_candidate = next(c for c in candidates
                            if c["id"] == selected_record["candidateId"])
  return {
    "selectedCandidate": selected_candidate,
    "selectedThreshold": selected_record["selectedThreshold"],
    "selectedInnerMetrics": selected_record["metrics"],
    "candidateResults": records,
    "candidateFailures": failures,
    "fitCount": fit_count,
  }


def _crossfit_selected(train_rows, selection, plan, device):
  candidate = selection["selectedCandidate"]
  threshold = selection["selectedThreshold"]
  records = []
  fit_count = 0
  for split in plan:
    fit_rows, held_rows = partition_families(
        train_rows, split["heldOutFamilies"])
    model = create_model(candidate["modelFamily"], device).fit(fit_rows,
                                                               candidate)
    fit_count += 1
    raw = _raw_prediction(model, held_rows)
    matrix = _threshold_matrix(raw, [threshold], device)[0]
    records.extend(_prediction_records(
        held_rows, raw, np.where(matrix, CACHE, DEFAULT),
        model_family=candidate["modelFamily"], candidate=candidate,
        threshold=threshold, training_fold=split["trainingFold"]))
  return sorted(records, key=lambda item: item["pairId"]), fit_count


def _fit_outer_selected(train_rows, held_rows, selection, device,
    held_family):
  candidate = selection["selectedCandidate"]
  threshold = selection["selectedThreshold"]
  model = create_model(candidate["modelFamily"], device).fit(train_rows,
                                                             candidate)
  raw = _raw_prediction(model, held_rows)
  actions = np.where(_threshold_matrix(raw, [threshold], device)[0], CACHE,
                     DEFAULT)
  records = _prediction_records(
      held_rows, raw, actions, model_family=candidate["modelFamily"],
      candidate=candidate, threshold=threshold,
      training_fold=f"outer:{held_family}")
  return records, model.metadata()


def _probability_vector(records: list[dict]) -> np.ndarray:
  values = [item["probabilityRight"] for item in records]
  if any(value is None for value in values):
    raise ValueError("ensemble component lacks probabilities")
  return np.asarray(values, dtype=np.float64)


def _align_component_records(rows, records):
  by_id = {item["pairId"]: item for item in records}
  if set(by_id) != {row.pair_id for row in rows}:
    raise ValueError("ensemble component cohort mismatch")
  return [by_id[row.pair_id] for row in rows]


def _ensemble_probability(definition, left_records, right_records):
  left = _probability_vector(left_records)
  right = _probability_vector(right_records)
  method = definition["method"]
  if method in {"average", "blend"}:
    alpha = definition["params"]["alpha"]
    return alpha * left + (1 - alpha) * right
  if method == "cache_and":
    return np.asarray([
      float(a["action"] == CACHE and b["action"] == CACHE)
      for a, b in zip(left_records, right_records)
    ])
  if method == "cache_or":
    return np.asarray([
      float(a["action"] == CACHE or b["action"] == CACHE)
      for a, b in zip(left_records, right_records)
    ])
  raise ValueError("stacked logistic requires its grouped fitting path")


def _stacker_fit_predict(train_rows, train_left, train_right,
    predict_left, predict_right):
  x = np.column_stack((_probability_vector(train_left),
                       _probability_vector(train_right)))
  xp = np.column_stack((_probability_vector(predict_left),
                        _probability_vector(predict_right)))
  y = np.asarray([int(row.observed_action == CACHE) for row in train_rows])
  _, weights = transformed_training_weights(train_rows, "raw")
  if len(np.unique(y)) == 1:
    return np.full(len(predict_left), float(y[0]))
  model = LogisticRegression(C=1.0, solver="lbfgs", max_iter=2000,
                             random_state=20260830)
  model.fit(x, y, sample_weight=weights)
  return model.predict_proba(xp)[:, 1]


def _stacker_crossfit(rows, left_records, right_records, plan):
  left = _align_component_records(rows, left_records)
  right = _align_component_records(rows, right_records)
  by_id_left = {item["pairId"]: item for item in left}
  by_id_right = {item["pairId"]: item for item in right}
  probabilities = {}
  audit = []
  for split in plan:
    fit_rows, held_rows = partition_families(rows, split["heldOutFamilies"])
    fit_left = [by_id_left[row.pair_id] for row in fit_rows]
    fit_right = [by_id_right[row.pair_id] for row in fit_rows]
    held_left = [by_id_left[row.pair_id] for row in held_rows]
    held_right = [by_id_right[row.pair_id] for row in held_rows]
    values = _stacker_fit_predict(fit_rows, fit_left, fit_right,
                                  held_left, held_right)
    probabilities.update({row.pair_id: float(value)
                          for row, value in zip(held_rows, values)})
    audit.append({
      "trainingFold": split["trainingFold"],
      "trainingFamilies": sorted({row.family_id for row in fit_rows}),
      "heldOutFamilies": sorted({row.family_id for row in held_rows}),
    })
  if set(probabilities) != {row.pair_id for row in rows}:
    raise ValueError("stacker crossfit did not cover outer-training rows")
  return np.asarray([probabilities[row.pair_id] for row in rows]), audit


def _ensemble_records(rows, probability, definition, threshold,
    training_fold):
  probability = np.asarray(probability, dtype=np.float64)
  raw = {
    "score": np.log(np.clip(probability, 1e-15, 1 - 1e-15))
             - np.log1p(-np.clip(probability, 1e-15, 1 - 1e-15)),
    "probability": probability,
  }
  candidate = {
    "id": definition["id"], "params": definition["params"],
    "weightTransform": None,
  }
  return _prediction_records(
      rows, raw, np.where(probability >= threshold, CACHE, DEFAULT),
      model_family="ensemble", candidate=candidate, threshold=threshold,
      training_fold=training_fold)


def select_and_predict_ensemble(train_rows, held_rows, component_inner,
    component_outer, definitions, plan,
    held_family):
  candidates = []
  fit_count = 0
  stacker_audits = {}
  for definition in definitions:
    left_name, right_name = definition["components"]
    left_inner = _align_component_records(train_rows,
                                          component_inner[left_name])
    right_inner = _align_component_records(train_rows,
                                           component_inner[right_name])
    if definition["method"] == "stacked_logistic":
      probability, audit = _stacker_crossfit(
          train_rows, left_inner, right_inner, plan)
      fit_count += len(plan)
      stacker_audits[definition["id"]] = audit
    else:
      probability = _ensemble_probability(definition, left_inner,
                                          right_inner)
    thresholds = (0.5,) if definition["method"] in {
      "cache_and", "cache_or"} else THRESHOLDS
    threshold_results = []
    for threshold in thresholds:
      records = _ensemble_records(train_rows, probability, definition,
                                  threshold, "inner-ensemble")
      threshold_results.append({
        "threshold": threshold,
        "metrics": _compact_metrics(evaluate_predictions(train_rows,
                                                         records)),
      })
    selected_threshold = min(
        threshold_results,
        key=lambda item: _rank_key(item["metrics"], 1, definition["id"]))
    candidates.append({
      "definition": definition,
      "selectedThreshold": selected_threshold["threshold"],
      "metrics": selected_threshold["metrics"],
    })
  selected = min(candidates, key=lambda item: _rank_key(
      item["metrics"], 1, item["definition"]["id"]))
  definition = selected["definition"]
  left_name, right_name = definition["components"]
  left_outer = _align_component_records(held_rows, component_outer[left_name])
  right_outer = _align_component_records(held_rows,
                                         component_outer[right_name])
  if definition["method"] == "stacked_logistic":
    train_left = _align_component_records(train_rows,
                                          component_inner[left_name])
    train_right = _align_component_records(train_rows,
                                           component_inner[right_name])
    probability = _stacker_fit_predict(train_rows, train_left, train_right,
                                       left_outer, right_outer)
    fit_count += 1
  else:
    probability = _ensemble_probability(definition, left_outer, right_outer)
  records = _ensemble_records(
      held_rows, probability, definition, selected["selectedThreshold"],
      f"outer:{held_family}")
  selection = {
    "selectedDefinition": definition,
    "selectedThreshold": selected["selectedThreshold"],
    "selectedInnerMetrics": selected["metrics"],
    "candidateResults": [{
      "ensembleId": item["definition"]["id"],
      "selectedThreshold": item["selectedThreshold"],
      "metrics": item["metrics"],
    } for item in candidates],
    "stackerLeakageAudit": stacker_audits.get(definition["id"]),
    "fitCount": fit_count,
  }
  return records, selection


def _reference_metrics(repo_root: Path, rows) -> dict[str, dict]:
  current_predictions = reference_predictions(repo_root, rows)
  references = {
    "current_direct_frozen": evaluate_predictions(rows,
                                                  current_predictions),
  }
  v1 = json.loads((repo_root / V1_RESULTS).read_text(encoding="utf-8"))
  for name in ("linear", "boosted_tree"):
    model = next(item for item in v1["models"]
                 if item["modelFamily"] == name)
    references[f"v1_{name}"] = evaluate_predictions(rows,
                                                    model["predictions"])
  m4c = json.loads((repo_root / M4C_RESULTS).read_text(encoding="utf-8"))
  decisive_ids = {row.pair_id for row in rows}
  m4c_decisive = [prediction for prediction in m4c["predictions"]
                  if prediction["pairId"] in decisive_ids]
  references["M4-C-LOFO"] = evaluate_predictions(rows, m4c_decisive)
  return references


def _pairwise(metrics, reference, *, bootstrap_seed=20260830) -> dict:
  family_deltas = {
    family: metrics["families"][family]["supportedRelativeRegret"]
            - reference["families"][family]["supportedRelativeRegret"]
    for family in sorted(metrics["families"])
  }
  values = np.asarray(list(family_deltas.values()), dtype=np.float64)
  rng = np.random.default_rng(bootstrap_seed)
  samples = values[
    rng.integers(0, len(values), size=(10000, len(values)))].mean(
      axis=1)
  return {
    "pooledRegretDelta": metrics["supportedRelativeRegret"]
                         - reference["supportedRelativeRegret"],
    "familyBalancedRegretDelta": metrics[
                                   "familyBalancedSupportedRelativeRegret"]
                                 - reference[
                                   "familyBalancedSupportedRelativeRegret"],
    "worstFamilyDelta": metrics["worstFamilySupportedRelativeRegret"]
                        - reference["worstFamilySupportedRelativeRegret"],
    "falseDefaultDelta": metrics["falseDefault"]["supportedRelativeRegret"]
                         - reference["falseDefault"][
                           "supportedRelativeRegret"],
    "falseCacheDelta": metrics["falseCache"]["supportedRelativeRegret"]
                       - reference["falseCache"]["supportedRelativeRegret"],
    "improvedFamilies": sum(delta < -TOLERANCE
                            for delta in family_deltas.values()),
    "worsenedFamilies": sum(delta > TOLERANCE
                            for delta in family_deltas.values()),
    "unchangedFamilies": sum(abs(delta) <= TOLERANCE
                             for delta in family_deltas.values()),
    "familyDeltas": family_deltas,
    "pairedFamilyBootstrapMeanDelta95Percentile": [
      float(np.quantile(samples, .025)), float(np.quantile(samples, .975))
    ],
  }


def _findings(summary, model_results, pairwise) -> str:
  winner = summary["winner"]
  metrics = next(item["metrics"] for item in model_results
                 if item["modelFamily"] == winner["modelFamily"])
  v1 = pairwise["bestV2_vs_v1_boosted_tree"]
  clear = (v1["pooledRegretDelta"] < -TOLERANCE
           and v1["worstFamilyDelta"] <= TOLERANCE)
  lines = [
    "# Model Tournament V2 findings", "",
    "The complete candidate manifest was frozen and hashed before the first outer fold.",
    "All reported V2 predictions are outer physical-family holdouts; all preprocessing, loss-transform, parameter, threshold, and ensemble selection was training-only.",
    "",
    f"Winner: `{winner['modelFamily']}`.",
    f"Supported relative regret: {metrics['supportedRelativeRegret']:.9g}.",
    f"Family-balanced supported relative regret: {metrics['familyBalancedSupportedRelativeRegret']:.9g}.",
    f"Worst-family supported regret: {metrics['worstFamilySupportedRelativeRegret']:.9g}.",
    f"False-DEFAULT regret: {metrics['falseDefault']['supportedRelativeRegret']:.9g}.",
    f"False-CACHE regret: {metrics['falseCache']['supportedRelativeRegret']:.9g}.",
    "",
  ]
  if clear:
    lines.append(
      "The winner is clearly better than the V1 boosted-tree reference under the primary metric without worsening the worst-family guard, and is the next candidate for independent validation.")
  else:
    lines.append(
      "No V2 candidate clearly improves on the V1 boosted-tree reference under both the primary metric and worst-family guard; stop without inventing another model.")
  lines.extend([
    "",
    "No benchmark was rerun, no production Java was changed, and no frozen experiment artifact was modified.",
  ])
  return "\n".join(lines) + "\n"


def _write_artifacts(output, results, outer_folds, references, pairwise,
    summary_without_hashes):
  hashes = {}
  hashes["modelResults"] = _write_json(
      output / "tournament_v2_model_results.json",
      {"schemaVersion": 2, "models": results, "references": references})
  hashes["outerLofo"] = _write_json(
      output / "tournament_v2_outer_lofo.json",
      {"schemaVersion": 2, "folds": outer_folds})
  hashes["familyMetrics"] = _write_json(
      output / "tournament_v2_family_metrics.json", {
        "models": [{"modelFamily": item["modelFamily"],
                    "families": item["metrics"]["families"],
                    "comparisonToCurrentDirect": item[
                      "comparisonToCurrentDirect"]}
                   for item in results],
        "references": {name: value["families"]
                       for name, value in references.items()},
      })
  for slug, wrong_type in (("false_default", "FALSE_DEFAULT"),
                           ("false_cache", "FALSE_CACHE")):
    errors = [prediction for item in results
              for prediction in item["predictions"]
              if prediction["loss"]["wrongType"] == wrong_type]
    errors.sort(key=lambda item: (
      item["modelFamily"], -item["supportedRelativeWrongActionLoss"],
      item["familyId"], item["pairId"]))
    hashes[slug] = _write_json(
        output / f"tournament_v2_{slug}.json",
        {"wrongType": wrong_type, "rows": errors})
  hashes["pairwise"] = _write_json(
      output / "tournament_v2_pairwise.json", pairwise)
  findings = _findings(summary_without_hashes, results, pairwise)
  hashes["findings"] = _write_text(
      output / "tournament_v2_findings.md", findings)
  return hashes


def run_tournament(repo_root: Path, output_dir: Path, device="cuda") -> dict:
  repo_root = repo_root.resolve()
  output = validate_output_dir(repo_root, output_dir)
  if device not in {"cpu", "cuda", "auto"}:
    raise ValueError("device must be cpu, cuda, or auto")
  rows, input_hashes = load_frozen_dataset(repo_root)
  output.mkdir(parents=True, exist_ok=True)
  manifest = build_manifest(input_hashes)
  manifest_path = output / MANIFEST_NAME
  manifest_digest = write_frozen_manifest(manifest_path, manifest)
  if manifest_digest != manifest_sha256(manifest):
    raise ValueError("manifest serialization mismatch")

  import catboost
  import sklearn
  import torch
  import xgboost
  from pareto_weight_calibration.device import resolve_device

  resolved = resolve_device(device)
  if device == "cuda" and resolved.type != "cuda":
    raise RuntimeError("CUDA was requested but is unavailable")
  references = _reference_metrics(repo_root, rows)
  families = sorted({row.family_id for row in rows})
  candidates_by_family = {
    family: [candidate for candidate in manifest["baseCandidates"]
             if candidate["modelFamily"] == family]
    for family in MODEL_ORDER
  }
  aggregate_predictions = {family: [] for family in MODEL_ORDER}
  aggregate_predictions["ensemble"] = []
  selections = {family: [] for family in MODEL_ORDER}
  selections["ensemble"] = []
  failures = []
  outer_folds = []
  total_fits = 0
  started = time.perf_counter()
  previous_threads = torch.get_num_threads()
  torch.set_num_threads(1)
  try:
    with threadpool_limits(limits=1):
      for outer_index, held_family in enumerate(families):
        verify_frozen_manifest(manifest_path, manifest_digest)
        print(f"V2 outer family {outer_index + 1}/{len(families)} "
              f"({held_family})", file=sys.stderr, flush=True)
        train_rows, held_rows = partition_families(rows, [held_family])
        plan = fold_plan(train_rows, INNER_FOLDS)
        component_inner = {}
        component_outer = {}
        fold_models = []
        for model_family in MODEL_ORDER:
          selection = select_inner(
              train_rows, candidates_by_family[model_family], plan,
              device)
          total_fits += selection["fitCount"]
          failures.extend({"outerFamily": held_family,
                           "modelFamily": model_family, **failure}
                          for failure in selection[
                            "candidateFailures"])
          inner_records, fits = _crossfit_selected(
              train_rows, selection, plan, device)
          total_fits += fits
          outer_records, metadata = _fit_outer_selected(
              train_rows, held_rows, selection, device, held_family)
          total_fits += 1
          component_inner[model_family] = inner_records
          component_outer[model_family] = outer_records
          aggregate_predictions[model_family].extend(outer_records)
          selections[model_family].append({
            "heldOutFamily": held_family,
            "candidateId": selection["selectedCandidate"]["id"],
            "weightTransform": selection["selectedCandidate"][
              "weightTransform"],
            "threshold": selection["selectedThreshold"],
          })
          fold_models.append({
            "modelFamily": model_family,
            "selection": selection,
            "modelMetadata": metadata,
            "metrics": evaluate_predictions(held_rows, outer_records),
            "predictions": outer_records,
          })
        ensemble_records, ensemble_selection = select_and_predict_ensemble(
            train_rows, held_rows, component_inner, component_outer,
            manifest["ensembles"], plan, held_family)
        total_fits += ensemble_selection["fitCount"]
        aggregate_predictions["ensemble"].extend(ensemble_records)
        definition = ensemble_selection["selectedDefinition"]
        selections["ensemble"].append({
          "heldOutFamily": held_family,
          "candidateId": definition["id"],
          "weightTransform": None,
          "threshold": ensemble_selection["selectedThreshold"],
        })
        outer_folds.append({
          "heldOutFamily": held_family,
          "trainingFamilies": sorted({row.family_id for row in train_rows}),
          "trainingPairIdsHash": pair_ids_hash(train_rows),
          "innerPlan": plan,
          "models": fold_models,
          "ensemble": {
            "selection": ensemble_selection,
            "metrics": evaluate_predictions(held_rows,
                                            ensemble_records),
            "predictions": ensemble_records,
          },
        })
  finally:
    torch.set_num_threads(previous_threads)
  runtime = time.perf_counter() - started
  verify_frozen_manifest(manifest_path, manifest_digest)
  verify_frozen_inputs(repo_root)

  results = []
  for model_family in (*MODEL_ORDER, "ensemble"):
    predictions = sorted(aggregate_predictions[model_family],
                         key=lambda item: item["pairId"])
    metrics = evaluate_predictions(rows, predictions)
    selected = selections[model_family]
    results.append({
      "modelFamily": model_family,
      "monotonicityK": MONOTONICITY.get(model_family, "component-defined"),
      "completeDecisiveCohort": len(predictions) == len(rows),
      "predictionCount": len(predictions),
      "selectedCandidateDistribution": dict(sorted(Counter(
          item["candidateId"] for item in selected).items())),
      "selectedWeightTransformDistribution": dict(sorted(Counter(
          item["weightTransform"] for item in selected
          if item["weightTransform"] is not None).items())),
      "selectedThresholdDistribution": {
        str(key): value for key, value in sorted(Counter(
            item["threshold"] for item in selected).items())
      },
      "metrics": metrics,
      "predictions": predictions,
      "comparisonToCurrentDirect": compare_reference(
          metrics, references["current_direct_frozen"]),
    })
  winner_result = min(results, key=lambda item: _rank_key(
      _compact_metrics(item["metrics"]),
      1 if item["modelFamily"] != "ensemble" else 2,
      item["modelFamily"]))
  winner_metrics = winner_result["metrics"]
  pairwise = {
    "bestV2ModelFamily": winner_result["modelFamily"],
    "bestV2_vs_current_direct": _pairwise(
        winner_metrics, references["current_direct_frozen"]),
    "bestV2_vs_v1_boosted_tree": _pairwise(
        winner_metrics, references["v1_boosted_tree"]),
    "bestV2_vs_M4-C": _pairwise(winner_metrics,
                                references["M4-C-LOFO"]),
  }
  best_individual = min(
      (item for item in results if item["modelFamily"] != "ensemble"),
      key=lambda item: _rank_key(_compact_metrics(item["metrics"]), 1,
                                 item["modelFamily"]))
  summary = {
    "schemaVersion": 2,
    "trainerVersion": TRAINER_VERSION,
    "status": "COMPLETE",
    "decisiveRows": len(rows),
    "excludedIndeterminateRows": 33,
    "physicalFamilies": len(families),
    "outerFolds": len(families),
    "innerFolds": INNER_FOLDS,
    "modelFamilyCount": len(MODEL_ORDER) + 1,
    "baseModelFamilyCount": len(MODEL_ORDER),
    "baseCandidateCount": manifest["baseCandidateCount"],
    "ensembleCandidateCount": manifest["ensembleCandidateCount"],
    "totalFrozenCandidates": (manifest["baseCandidateCount"]
                              + manifest["ensembleCandidateCount"]),
    "totalFits": total_fits,
    "runtimeSeconds": runtime,
    "requestedDevice": device,
    "resolvedTorchDevice": str(resolved),
    "gpuAcceleration": {
      "used": any("cuda" in metadata["modelMetadata"]["actualDevice"]
                  for fold in outer_folds
                  for metadata in fold["models"]),
      "deviceName": torch.cuda.get_device_name(0)
      if resolved.type == "cuda" else None,
      "acceleratedFamilies": ["xgboost", "mlp"],
      "catboostCpuReason": "CatBoost 1.2.x GPU rejects monotone_constraints; CPU preserves increasing K",
      "batchedThresholdEvaluation": resolved.type == "cuda",
    },
    "winner": {
      "modelFamily": winner_result["modelFamily"],
      "selectedCandidateDistribution": winner_result[
        "selectedCandidateDistribution"],
      "selectedWeightTransformDistribution": winner_result[
        "selectedWeightTransformDistribution"],
      "selectedThresholdDistribution": winner_result[
        "selectedThresholdDistribution"],
      "metrics": winner_metrics,
    },
    "ensembleBeatsEveryIndividual": (
        winner_result["modelFamily"] == "ensemble"
        and _rank_key(_compact_metrics(winner_result["metrics"]), 2,
                      "ensemble")
        < _rank_key(_compact_metrics(best_individual["metrics"]), 1,
                    best_individual["modelFamily"])),
    "bestIndividualModelFamily": best_individual["modelFamily"],
    "candidateFailures": failures,
    "manifestSha256": manifest_digest,
    "inputHashes": input_hashes,
    "inputsUnchanged": True,
    "versions": {
      "python": platform.python_version(),
      "numpy": np.__version__,
      "sklearn": sklearn.__version__,
      "torch": torch.__version__,
      "xgboost": xgboost.__version__,
      "catboost": catboost.__version__,
    },
  }
  artifact_hashes = _write_artifacts(
      output, results, outer_folds, references, pairwise, summary)
  summary["artifactHashes"] = artifact_hashes
  _write_json(output / "tournament_v2_summary.json", summary)
  return summary


def build_parser() -> argparse.ArgumentParser:
  parser = argparse.ArgumentParser(
      description="Execute frozen Model Tournament V2 with nested family CV.")
  parser.add_argument("--repo-root", type=Path, default=Path.cwd())
  parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
  parser.add_argument("--device", choices=("cpu", "cuda", "auto"),
                      default="cuda")
  return parser


def main(argv=None):
  args = build_parser().parse_args(argv)
  output = args.output_dir if args.output_dir.is_absolute() else (
      args.repo_root / args.output_dir)
  run_tournament(args.repo_root, output, args.device)


if __name__ == "__main__":
  main()
