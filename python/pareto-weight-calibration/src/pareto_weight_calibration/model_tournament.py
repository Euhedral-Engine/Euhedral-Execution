"""Explicit execution entry point for the frozen direct-side model tournament.

Importing this module and parsing the CLI do not fit models or run validation.
The only empirical comparison is nested, physical-family-grouped validation.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import platform
import sys
from typing import Sequence

import numpy as np
import scipy
import sklearn
from threadpoolctl import threadpool_limits

from pareto_weight_calibration.action_model import (
  ActionRow, CACHE, DEFAULT, INDETERMINATE, action_loss, grouped_family_folds,
)
from pareto_weight_calibration.direct_side import (
  BASE_FEATURES, _rows_from_dataset, _write_json, _write_text,
  evaluate_direct_predictions, side_for_action,
)
from pareto_weight_calibration.tournament_config import (
  GRID_PATH, MODEL_ORDER, grid_sha256, load_grid, parse_models,
)
from pareto_weight_calibration.tournament_models import (
  MONOTONICITY, WEIGHTING, create_model, runtime_matrix, training_weights,
)

INPUT_LOCK = Path(__file__).with_name("tournament_inputs.json")
DATASET_PATH = Path(
  "experiments/pareto_direct_side_training/direct_side_training_dataset.json")
REFERENCE_PATH = Path(
  "experiments/pareto_direct_side_training/direct_side_outer_lofo.json")
DEFAULT_OUTPUT = Path("experiments/pareto_model_tournament/results")
TRAINER_VERSION = "direct-side-family-tournament-v1"


def verify_frozen_inputs(repo_root: Path) -> dict[str, str]:
  expected = json.loads(INPUT_LOCK.read_text(encoding="utf-8"))
  for relative, digest in expected.items():
    path = repo_root / relative
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
      raise ValueError(f"frozen input changed: {relative}")
  return expected


def load_frozen_dataset(repo_root: Path) -> tuple[list[ActionRow], dict]:
  hashes = verify_frozen_inputs(repo_root)
  dataset = json.loads((repo_root / DATASET_PATH).read_text(encoding="utf-8"))
  rows = _rows_from_dataset(dataset)
  indeterminate = _rows_from_dataset({"rows": dataset["indeterminateRows"]})
  if (len(rows) != 102 or len(indeterminate) != 33
      or dataset["decisiveRowCount"] != 102 or dataset[
        "indeterminateRowCount"] != 33
      or len({row.family_id for row in rows}) != 43
      or len({row.family_id for row in rows + indeterminate}) != 49
      or any(not row.decisive for row in rows)
      or any(row.observed_action != INDETERMINATE for row in indeterminate)
      or len({row.pair_id for row in rows + indeterminate}) != 135
      or dataset["features"] != list(BASE_FEATURES)
      or dataset["counterfactualTelemetryUsed"]
      or dataset["indeterminateRowsForcedIntoLabels"]):
    raise ValueError(
      "frozen 102 decisive / 33 indeterminate dataset contract changed")
  runtime_matrix(rows)
  training_weights(rows)
  return rows, hashes


def partition_families(rows: Sequence[ActionRow], held_families: Sequence[str]):
  held_set = set(held_families)
  if not held_set or not held_set.issubset({row.family_id for row in rows}):
    raise ValueError("unknown or empty held-out family set")
  train = sorted((row for row in rows if row.family_id not in held_set),
                 key=lambda r: r.pair_id)
  held = sorted((row for row in rows if row.family_id in held_set),
                key=lambda r: r.pair_id)
  if not train or not held:
    raise ValueError("family split must contain training and held-out rows")
  return train, held


def pair_ids_hash(rows: Sequence[ActionRow]) -> str:
  return hashlib.sha256(
    "\n".join(sorted(row.pair_id for row in rows)).encode()).hexdigest()


def fold_plan(rows: Sequence[ActionRow], fold_count: int) -> list[dict]:
  folds = grouped_family_folds((row.family_id for row in rows),
                               fold_count=fold_count)
  result = []
  for index, families in enumerate(folds):
    train, held = partition_families(rows, families)
    result.append({
      "trainingFold": f"inner-{index:02d}",
      "trainingFamilies": sorted({row.family_id for row in train}),
      "heldOutFamilies": sorted(families),
      "trainingPairIds": [row.pair_id for row in train],
      "heldOutPairIds": [row.pair_id for row in held],
    })
  return result


def prediction_records(model, rows, *, training_fold, config) -> list[dict]:
  scores = model.predict_score(rows)
  probabilities = model.predict_probability(rows)
  actions = model.predict_action(rows)
  result = []
  for index, row in enumerate(rows):
    action = actions[index]
    result.append({
      "modelFamily": model.family, "candidateId": config["id"],
      "selectedHyperparameters": config["params"],
      "trainingFold": training_fold,
      "heldOutFamily": row.family_id, "familyId": row.family_id,
      "pairId": row.pair_id,
      "currentK": row.current_k, "observedAction": row.observed_action,
      "observedSide": side_for_action(row.observed_action),
      "score": float(scores[index]),
      "probabilityRight": None if probabilities is None else float(
          probabilities[index]),
      "predictedSide": side_for_action(action), "action": action,
      "loss": action_loss(row, action),
      "supportedWrongActionLoss": row.supported_wrong_action_loss,
      "supportedRelativeWrongActionLoss": row.supported_relative_wrong_action_loss,
      "evidenceWeight": row.evidence_weight,
      "frozenFamilyInfluenceWeight": row.influence_weight,
    })
  return result


def evaluate_predictions(rows, predictions) -> dict:
  # Existing metrics assume positional alignment. Validate and reorder explicitly.
  by_id = {prediction["pairId"]: prediction for prediction in predictions}
  if (len(by_id) != len(predictions) or len(
      {row.pair_id for row in rows}) != len(rows)
      or set(by_id) != {row.pair_id for row in rows}):
    raise ValueError("predictions must cover each held-out row exactly once")
  ordered = []
  for row in rows:
    item = by_id[row.pair_id]
    if item["familyId"] != row.family_id or item["action"] not in {DEFAULT,
                                                                   CACHE}:
      raise ValueError("invalid prediction family/action")
    ordered.append(item)
  metrics = evaluate_direct_predictions(rows, ordered)
  # No boundary/cutoff interpretation is part of this tournament.
  metrics.pop("boundaryMarginBuckets", None)
  return metrics


def inner_rank_key(candidate: dict) -> tuple:
  """Training-only candidate selection; this never chooses a production family."""
  m = candidate["metrics"]
  return (m["supportedRelativeRegret"], m["worstFamilySupportedRelativeRegret"],
          m["falseDefault"]["supportedRelativeRegret"],
          m["falseCache"]["supportedRelativeRegret"],
          -m["familyBalancedEvidenceWeightedSideAccuracy"],
          candidate["config"]["complexity"], candidate["config"]["id"])


def select_inner(train_rows, model_family, candidates, plan, device) -> dict:
  records = []
  for config in candidates:
    predictions = []
    fold_metrics = []
    for split in plan:
      train, held = partition_families(train_rows, split["heldOutFamilies"])
      model = create_model(model_family, device).fit(train, config)
      fold_predictions = prediction_records(model, held,
                                            training_fold=split["trainingFold"],
                                            config=config)
      predictions.extend(fold_predictions)
      fold_metrics.append({"trainingFold": split["trainingFold"],
                           "metrics": evaluate_predictions(held,
                                                           fold_predictions)})
    records.append({"modelFamily": model_family, "config": config,
                    "metrics": evaluate_predictions(train_rows, predictions),
                    "foldMetrics": fold_metrics})
  selected = min(records, key=inner_rank_key)
  return {"selectedConfig": selected["config"], "candidates": records}


def run_outer_fold(rows, held_family, model_family, grid, device) -> dict:
  train, held = partition_families(rows, [held_family])
  plan = fold_plan(train, grid["innerFolds"])
  selection = select_inner(train, model_family, grid["models"][model_family],
                           plan, device)
  config = selection["selectedConfig"]
  model = create_model(model_family, device).fit(train, config)
  training_fold = f"outer:{held_family}"
  predictions = prediction_records(model, held, training_fold=training_fold,
                                   config=config)
  return {
    "modelFamily": model_family, "heldOutFamily": held_family,
    "trainingFold": training_fold,
    "trainingFamilies": sorted({r.family_id for r in train}),
    "trainingPairIdsHash": pair_ids_hash(train),
    "trainingPairIds": [r.pair_id for r in train],
    "innerPlan": plan, "selection": selection, "model": model.serialize_model(),
    "predictions": predictions,
    "metrics": evaluate_predictions(held, predictions),
  }


def reference_predictions(repo_root: Path, rows) -> list[dict]:
  """Use existing held-out predictions only; never refit the stored candidate."""
  payload = json.loads((repo_root / REFERENCE_PATH).read_text(encoding="utf-8"))
  expected_families = {row.family_id for row in rows}
  folds = payload["folds"]
  if len(folds) != len(expected_families) or {f["heldOutFamily"] for f in
                                              folds} != expected_families:
    raise ValueError("reference outer family inventory changed")
  fold_predictions = []
  for fold in folds:
    train, held = partition_families(rows, [fold["heldOutFamily"]])
    if fold["trainingPairIdsHash"] != pair_ids_hash(train):
      raise ValueError("reference training cohort differs from tournament")
    evaluate_predictions(held, fold["predictions"])
    fold_predictions.extend(fold["predictions"])
  by_id = {p["pairId"]: p for p in fold_predictions}
  if any(by_id.get(p["pairId"]) != p for p in payload["predictions"]):
    raise ValueError("reference aggregate and fold predictions differ")
  evaluate_predictions(rows, payload["predictions"])
  return sorted(payload["predictions"], key=lambda item: item["pairId"])


def compare_reference(metrics, reference) -> dict:
  families = metrics["families"]
  deltas = {family: families[family]["supportedRelativeRegret"]
                    - reference["families"][family]["supportedRelativeRegret"]
            for family in sorted(families)}
  return {
    "supportedRelativeRegretDelta": metrics["supportedRelativeRegret"] -
                                    reference["supportedRelativeRegret"],
    "worstFamilyRegretDelta": metrics["worstFamilySupportedRelativeRegret"] -
                              reference["worstFamilySupportedRelativeRegret"],
    "familyRegretDeltas": deltas,
    "improvedFamilies": [f for f, delta in deltas.items() if delta < -1e-12],
    "worsenedFamilies": [f for f, delta in deltas.items() if delta > 1e-12],
    "unchangedFamilies": [f for f, delta in deltas.items() if
                          abs(delta) <= 1e-12],
  }


def _findings(model_results, reference) -> str:
  lines = ["# Frozen side-of-peak model tournament", "",
           "All results below are outer physical-family holdouts. No production winner is selected.",
           "Rows are in frozen model-family order, not a ranking. Probabilities are cost-sensitive and uncalibrated.",
           "",
           "| Model | Supported regret | Worst-family regret | False DEFAULT | False CACHE | Family-balanced accuracy |",
           "| --- | ---: | ---: | ---: | ---: | ---: |"]
  entries = [("current_direct_frozen", reference)] + [
    (r["modelFamily"], r["metrics"]) for r in model_results]
  for name, m in entries:
    lines.append(
      f"| {name} | {m['supportedRelativeRegret']:.9g} | {m['worstFamilySupportedRelativeRegret']:.9g} | "
      f"{m['falseDefault']['supportedRelativeRegret']:.9g} | {m['falseCache']['supportedRelativeRegret']:.9g} | "
      f"{m['familyBalancedEvidenceWeightedSideAccuracy']:.9g} |")
  lines.extend(["",
                "Interpretation is deferred to the execution model. Review paired family deltas as well as aggregate regret.",
                "Recommended review order: supported regret, worst-family regret, false-DEFAULT regret, false-CACHE regret, family-balanced accuracy, simplicity.",
                "Do not change grids, add models, rerun benchmarks, or modify frozen evidence after seeing these results.",
                "If no model clearly improves on the frozen current direct classifier, report that result and stop."])
  return "\n".join(lines) + "\n"


def validate_output_dir(repo_root: Path, output_dir: Path) -> Path:
  output = output_dir.resolve()
  frozen = (repo_root / DATASET_PATH.parent).resolve()
  if output == frozen or frozen in output.parents or output in frozen.parents:
    raise ValueError("output must not overlap frozen direct-side artifacts")
  if output.exists() and (not output.is_dir() or any(output.iterdir())):
    raise ValueError(
      "output directory must be new or empty; existing results are never overwritten")
  return output


def write_result_artifacts(output: Path, results: list[dict], outer: list[dict],
    reference: dict) -> dict:
  """Pure artifact emission, independently testable without running validation."""
  model_results = {"schemaVersion": 1, "models": results,
                   "currentDirectFrozenMetrics": reference}
  hashes = {}
  hashes["modelResults"] = _write_json(output / "tournament_model_results.json",
                                       model_results)
  hashes["outerLofo"] = _write_json(output / "tournament_outer_lofo.json",
                                    {"folds": outer})
  hashes["familyMetrics"] = _write_json(
    output / "tournament_family_metrics.json", {
        "models": [{"modelFamily": r["modelFamily"],
                    "families": r["metrics"]["families"],
                    "comparisonToCurrentDirect": r["comparisonToCurrentDirect"]}
                   for r in results],
        "currentDirectFrozen": reference["families"],
      })
  for name, wrong_type in (("false_cache", "FALSE_CACHE"),
                           ("false_default", "FALSE_DEFAULT")):
    errors = [p for r in results for p in r["predictions"] if
              p["loss"]["wrongType"] == wrong_type]
    # Family order, largest physical relative loss, then stable physical identity.
    errors.sort(key=lambda p: (MODEL_ORDER.index(p["modelFamily"]),
                               -p["supportedRelativeWrongActionLoss"],
                               p["familyId"], p["pairId"]))
    hashes[name] = _write_json(output / f"tournament_{name}.json",
                               {"wrongType": wrong_type, "rows": errors})
  hashes["findings"] = _write_text(output / "tournament_findings.md",
                                   _findings(results, reference))
  return hashes


def run_tournament(repo_root: Path, output_dir: Path, models=MODEL_ORDER,
    device="auto") -> dict:
  repo_root = repo_root.resolve()
  output = validate_output_dir(repo_root, output_dir)
  models = parse_models(",".join(models))
  if device not in {"auto", "cpu", "cuda"}:
    raise ValueError("device must be auto, cpu, or cuda")
  grid = load_grid()
  rows, input_hashes = load_frozen_dataset(repo_root)
  reference = evaluate_predictions(rows, reference_predictions(repo_root, rows))
  import torch
  from pareto_weight_calibration.device import resolve_device

  if "mlp" in models:
    resolve_device(
      device)  # Fail explicitly before any fitting when CUDA was requested but unavailable.
  families = sorted({row.family_id for row in rows})
  outer = []
  results = []
  previous_threads = torch.get_num_threads()
  output.mkdir(parents=True, exist_ok=True)
  try:
    torch.set_num_threads(1)
    with threadpool_limits(limits=1):
      for model_family in models:
        predictions = []
        selected_ids = []
        for index, family in enumerate(families):
          print(
            f"{model_family}: outer family {index + 1}/{len(families)} ({family})",
            file=sys.stderr, flush=True)
          fold = run_outer_fold(rows, family, model_family, grid, device)
          selected_ids.append(fold["selection"]["selectedConfig"]["id"])
          predictions.extend(fold["predictions"])
          # Checkpoints preserve completed folds on failure; no automatic resume/retry.
          relative = Path("folds") / model_family / f"{index:03d}.json"
          digest = _write_json(output / relative, fold)
          outer.append({**{k: v for k, v in fold.items() if k != "model"},
                        "actualDevice": fold["model"]["actualDevice"],
                        "serializedFoldPath": str(relative),
                        "serializedFoldSha256": digest})
        predictions.sort(key=lambda p: p["pairId"])
        metrics = evaluate_predictions(rows, predictions)
        results.append({
          "modelFamily": model_family,
          "monotonicity": MONOTONICITY[model_family],
          "metrics": metrics, "selectedCandidateCounts": dict(
            sorted(Counter(selected_ids).items())),
          "predictions": predictions,
          "comparisonToCurrentDirect": compare_reference(metrics, reference),
        })
  finally:
    torch.set_num_threads(previous_threads)
  verify_frozen_inputs(repo_root)
  hashes = write_result_artifacts(output, results, outer, reference)
  summary = {
    "schemaVersion": 1, "trainerVersion": TRAINER_VERSION, "status": "COMPLETE",
    "productionWinner": None, "fullDatasetRefitPerformed": False,
    "models": list(models), "decisiveRows": len(rows),
    "excludedIndeterminateRows": 33,
    "physicalFamilies": len(families), "outerFolds": len(families),
    "innerFolds": grid["innerFolds"],
    "grid": grid, "gridSha256": grid_sha256(), "seed": grid["seed"],
    "fitCount": len(families) * (
          sum(len(grid["models"][m]) for m in models) * grid[
        "innerFolds"] + len(models)),
    "inputHashes": input_hashes, "inputsUnchanged": True,
    "requestedDevice": device,
    "actualDevices": {
      m: sorted({f["actualDevice"] for f in outer if f["modelFamily"] == m}) for
      m in models},
    "weighting": WEIGHTING,
    "rankingGuidance": ["supportedRelativeRegret",
                        "worstFamilySupportedRelativeRegret",
                        "falseDefault.supportedRelativeRegret",
                        "falseCache.supportedRelativeRegret",
                        "descending familyBalancedEvidenceWeightedSideAccuracy",
                        "simpler model"],
    "versions": {"python": platform.python_version(), "numpy": np.__version__,
                 "scipy": scipy.__version__, "sklearn": sklearn.__version__,
                 "torch": torch.__version__},
    "sourceHashes": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in
                     [
                       Path(__file__), GRID_PATH, INPUT_LOCK,
                       *[Path(__file__).with_name(name) for name in (
                         "tournament_config.py", "tournament_models.py",
                         "direct_side.py", "action_model.py", "device.py")]]},
    "artifactHashes": hashes,
  }
  _write_json(output / "tournament_summary.json", summary)
  return summary


def build_parser() -> argparse.ArgumentParser:
  parser = argparse.ArgumentParser(
    description="Execute the frozen, nested physical-family model tournament.")
  parser.add_argument("--repo-root", type=Path, default=Path.cwd())
  parser.add_argument("--models", default="all",
                      help="all or linear,polynomial,spline,tree,forest,boosted_tree,svm,mlp")
  parser.add_argument("--device", choices=("auto", "cpu", "cuda"),
                      default="auto")
  parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
  return parser


def main(argv=None) -> None:
  parser = build_parser()
  args = parser.parse_args(argv)
  try:
    models = parse_models(args.models)
  except ValueError as error:
    parser.error(str(error))
  output = args.output_dir if args.output_dir.is_absolute() else args.repo_root / args.output_dir
  run_tournament(args.repo_root, output, models=models, device=args.device)


if __name__ == "__main__":
  main()
