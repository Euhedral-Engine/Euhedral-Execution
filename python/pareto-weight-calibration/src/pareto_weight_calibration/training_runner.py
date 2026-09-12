"""One nested, family-grouped execution engine for registered training tasks."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from itertools import product
from pathlib import Path
import platform

import numpy as np
import sklearn

from .feature_schema import FeatureSchema
from .task_adapters import load_dataset, training_weights, metrics, ranking, \
  decode
from .training_models import ArrayModel, validate_capabilities
from .training_spec import TrainingTaskSpec, canonical, digest


@dataclass
class FittedCandidate:
  schema: FeatureSchema
  model: ArrayModel
  cache_key: str

  def predict(self, data):
    return self.model.predict(self.schema.transform(data.X))


class FitCache:
  def __init__(self):
    self.models = {}
    self.preprocessing = {}
    self.device_data = {}

  def fit(self, spec, candidate, data, device):
    content = {"ids": data.row_ids, "X": data.X.tolist(),
               "Y": np.where(data.mask, data.Y, 0).tolist(),
               "mask": data.mask.tolist(),
               "weights": data.weights.tolist(), "groups": data.groups,
               "costs": None if data.C is None else np.where(data.cost_mask,
                                                             data.C,
                                                             0).tolist(),
               "costMask": None if data.cost_mask is None else data.cost_mask.tolist(),
               "provenance": data.provenance}
    basis = candidate.basis or spec.basis
    weight_transform = candidate.trainingWeight or spec.objective.trainingWeight
    prep_key = digest({"task": spec.schema_hash, "data": content,
                       "basis": basis.model_dump(mode="json"),
                       "weightTransform": weight_transform})
    model = ArrayModel(spec, candidate, device)
    backend_version = sklearn.__version__
    if model.device == "cuda":
      import torch
      backend_version = torch.__version__
    key = digest(
        {"adapterVersion": "array-model-v1", "backendVersion": backend_version,
         "preprocessing": prep_key,
         "candidate": candidate.model_dump(mode="json", exclude={"thresholds"}),
         "seed": spec.validation.seed, "backend": model.device,
         "numpy": np.__version__,
         "sklearn": sklearn.__version__, "python": platform.python_version()})
    if key not in self.models:
      if prep_key not in self.preprocessing:
        influence, weights = training_weights(spec, data, weight_transform)
        schema = FeatureSchema(data.feature_names, basis).fit(data.X, influence)
        self.preprocessing[prep_key] = schema, schema.transform(data.X), weights
      schema, x, weights = self.preprocessing[prep_key]
      model.fit(x, data.Y, data.mask, weights, schema.output_names,
                self.device_data.setdefault((prep_key, model.device), {}))
      self.models[key] = FittedCandidate(schema, model, key)
    return self.models[key]


def candidates(spec):
  expanded = []
  for candidate in spec.candidates:
    names = sorted(candidate.grid)
    for values in product(*(candidate.grid[name] for name in names)):
      params = dict(candidate.params, **dict(zip(names, values)))
      expanded.append(candidate.model_copy(update={"params": params, "grid": {},
                                                   "id": candidate.id + (
                                                     "-" + digest(params)[
                                                       :12] if names else "")}))
  if len({x.id for x in expanded}) != len(expanded):
    raise ValueError("expanded candidate IDs collide")
  return sorted(expanded, key=lambda x: x.id)


def split_plan(data, count, seed):
  families = np.array(sorted(set(data.groups)))
  if len(families) < 2:
    raise ValueError(
      "grouped validation requires at least two workload families")
  np.random.default_rng(seed).shuffle(families)
  result = []
  for held in np.array_split(families, min(count, len(families))):
    held = set(held)
    test = np.array([i for i, group in enumerate(data.groups) if group in held])
    train = np.array(
        [i for i, group in enumerate(data.groups) if group not in held])
    result.append((train, test))
  return result


def select(spec, data, cache, device):
  plan = split_plan(data, spec.validation.innerFolds, spec.validation.seed)
  results = []
  for candidate in candidates(spec):
    width = len(spec.labels) if spec.kind == "classification" else len(
      spec.targets)
    predictions = np.empty((len(data.row_ids), width))
    for train, test in plan:
      fit = cache.fit(spec, candidate, data.subset(train), device)
      predictions[test] = fit.predict(data.subset(test))
    for threshold in candidate.thresholds:
      result = metrics(spec, data, predictions, threshold)
      results.append(
          (ranking(spec, result) + (candidate.id, threshold), candidate,
           threshold, result))
  best = min(results, key=lambda x: x[0])
  return best[1], best[2], [{"candidate": c.id, "threshold": t, "metrics": m}
                            for _, c, t, m in results]


def run(spec, data, device="auto", dry_run=False):
  expected_names = tuple(v.name for v in spec.features + spec.controls)
  if data.feature_names != expected_names or data.Y.shape[1] != len(
      spec.targets):
    raise ValueError("dataset does not match ordered task input/target schema")
  if spec.kind == "classification":
    if not data.mask.all() or np.any(data.Y != data.Y.astype(int)) or np.any(
        data.Y < 0) or np.any(data.Y >= len(spec.labels)):
      raise ValueError("invalid categorical target IDs/mask")
    if data.C is not None and data.C.shape[1] != len(spec.labels):
      raise ValueError("cost table must follow the task's label order")
  expanded = candidates(spec)
  for candidate in expanded:
    validate_capabilities(spec, candidate, device)
    FeatureSchema(data.feature_names, candidate.basis or spec.basis)
  plan = split_plan(data, spec.validation.outerFolds, spec.validation.seed)
  for train, _ in plan:
    split_plan(data.subset(train), spec.validation.innerFolds,
               spec.validation.seed)
  summary = {"schemaVersion": 1, "taskId": spec.id,
             "taskHash": spec.schema_hash,
             "rows": len(data.row_ids), "featureNames": data.feature_names,
             "targetNames": [x.name for x in spec.targets],
             "controlNames": [x.name for x in spec.controls],
             "candidateControls": None if data.U is None else data.U.tolist(),
             "candidates": [x.model_dump(mode="json") for x in expanded],
             "outerPlan": [{"trainingIds": [data.row_ids[i] for i in train],
                            "heldIds": [data.row_ids[i] for i in test]} for
                           train, test in plan],
             "requestedDevice": device, "provenance": data.provenance,
             "validationMeaning": (
               "unseen parameter vectors on fixed workload outputs; all blocks held together; rows bundle independent JVM forks"
               if spec.validation.policy == "parameter_holdout" else
               "unseen workload families; forks are replication units"),
             "dryRun": dry_run}
  if dry_run:
    return summary
  cache = FitCache()
  outer = []
  for train, test in plan:
    training, held = data.subset(train), data.subset(test)
    candidate, threshold, selection = select(spec, training, cache, device)
    fit = cache.fit(spec, candidate, training, device)
    prediction = fit.predict(held)
    outer.append({"heldIds": held.row_ids, "candidate": candidate.id,
                  "threshold": threshold,
                  "prediction": prediction.tolist(),
                  "decision": decode(spec, prediction, threshold).tolist(),
                  "metrics": metrics(spec, held, prediction, threshold),
                  "innerSelection": selection,
                  "cacheKey": fit.cache_key, "actualDevice": fit.model.device})
  # Final choice comes from full-data inner CV, never a ranking of outer results.
  candidate, threshold, selection = select(spec, data, cache, device)
  final = cache.fit(spec, candidate, data, device)
  from .runtime_export import evaluator
  summary.update(outer=outer, finalCandidate=candidate.model_dump(mode="json"),
                 finalThreshold=threshold, finalSelection=selection,
                 actualDevice=final.model.device,
                 finalCacheKey=final.cache_key,
                 evaluator=evaluator(spec, final, threshold, data.U))
  return summary


def main(argv=None):
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--task", type=Path, required=True)
  parser.add_argument("--device", choices=("auto", "cpu", "cuda"),
                      default=None)
  parser.add_argument("--dry-run", action="store_true")
  parser.add_argument("--output-dir", type=Path)
  parser.add_argument("--resume", action="store_true")
  parser.add_argument("--smoke", action="store_true")
  parser.add_argument("--max-rounds", type=int)
  parser.add_argument("--fit-workers",
                      type=lambda s: s if s == 'auto' else int(s))
  parser.add_argument("--predict-workers",
                      type=lambda s: s if s == 'auto' else int(s))
  parser.add_argument("--backend", choices=['auto', 'threads', 'processes'])
  parser.add_argument("--native-threads-per-worker", type=int)
  parser.add_argument("--memory-budget-gib", type=float)
  parser.add_argument("--gpu-concurrency", type=int)
  args = parser.parse_args(argv)
  execution = dict(device=args.device, fitWorkers=args.fit_workers,
                   predictWorkers=args.predict_workers,
                   backend=args.backend,
                   nativeThreadsPerWorker=args.native_threads_per_worker,
                   memoryBudgetGiB=args.memory_budget_gib,
                   gpuConcurrency=args.gpu_concurrency)
  import json
  if json.loads(args.task.read_text()).get("kind") == "parameter_loop":
    from .parameter_loop import run_task
    run_task(args.task, args.output_dir, args.dry_run, args.resume,
             args.max_rounds, args.smoke, execution)
    return
  if json.loads(args.task.read_text()).get("kind") == "parameter_study":
    from .tournament_study import run_task
    print(canonical(run_task(args.task, args.output_dir, args.dry_run,
                             execution_overrides=execution)))
    return
  if json.loads(args.task.read_text()).get("kind") == "parameter_screen":
    from .parameter_screen import run_task
    result = run_task(args.task, args.output_dir, args.dry_run)
    print(canonical(result))
    return
  if json.loads(args.task.read_text()).get("kind") == "parameter_tournament":
    from .surrogate_tournament import run_task
    result = run_task(args.task, args.output_dir, args.dry_run,
                      execution_overrides=execution)
    print(canonical(result if args.dry_run else {
      "taskId": result["taskId"],
      "selectedProposals": result["selectedProposals"]}))
    return
  if not args.dry_run and args.output_dir is None:
    parser.error("--output-dir is required for execution")
  if args.output_dir and args.output_dir.exists():
    parser.error(
      "output directory must be new; existing artifacts are never overwritten")
  spec = TrainingTaskSpec.load(args.task)
  data = load_dataset(spec, args.task.resolve().parent)
  result = run(spec, data, args.device or "auto", args.dry_run)
  if args.output_dir:
    args.output_dir.mkdir(parents=True, exist_ok=False)
    from .direct_side import _write_json
    _write_json(args.output_dir / "run.json", result)
    if result.get("evaluator"):
      _write_json(args.output_dir / "evaluator.json", result["evaluator"])
      from .runtime_export import render_java
      (args.output_dir / "TaskEvaluator.java").write_text(
        render_java(result["evaluator"]))
  else:
    print(canonical(result))


if __name__ == "__main__":
  main()
