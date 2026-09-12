"""Task-owned evidence, weighting, decoding and evaluation semantics."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np

from ..feature_schema import extract, source, expression
from ..training_data import TrainingDataset, load_arms, compatible_configs
from ..training_spec import digest


def load_dataset(spec, task_dir: Path, cache=None):
  path = (task_dir / spec.dataset.manifest).resolve()
  manifest = json.loads(path.read_text())
  if manifest.get("schemaVersion") != 1:
    raise ValueError("unsupported dataset manifest version")
  hashes = {}
  for name, expected in manifest.get("hashes", {}).items():
    actual = hashlib.sha256((path.parent / name).read_bytes()).hexdigest()
    if actual != expected:
      raise ValueError(f"dataset digest mismatch: {name}")
    hashes[name] = actual
  if spec.dataset.adapter == "participation_binary":
    dataset = _participation(spec, manifest, path.parent)
  elif spec.dataset.adapter == "policy_response":
    from .policy_response import load
    dataset = load(spec, manifest, path.parent)
  else:
    dataset = _surface(spec, manifest, path.parent, cache)
  inventory = {"rows": len(dataset.row_ids),
               "families": len(set(dataset.groups)),
               "excludedRows": dataset.provenance.get("excludedRows", 0)}
  for name, expected in spec.dataset.inventory.items():
    if name not in inventory or inventory[name] != expected:
      raise ValueError(f"inventory assertion failed: {name}")
  dataset.provenance.update(
    manifestSha256=hashlib.sha256(path.read_bytes()).hexdigest(), hashes=hashes)
  return dataset


def _participation(spec, manifest, base):
  from ..direct_side import _rows_from_dataset, current_state_features
  from ..action_model import DEFAULT, CACHE
  if spec.labels != (DEFAULT, CACHE):
    raise ValueError("participation adapter requires DEFAULT,CACHE labels")
  payload = json.loads((base / manifest["dataset"]).read_text())
  if manifest["dataset"] not in manifest.get("hashes", {}):
    raise ValueError("participation dataset must be hash-locked")
  rows = _rows_from_dataset(payload)
  excluded = _rows_from_dataset({"rows": payload.get("indeterminateRows", [])})
  if any(not r.decisive for r in rows) or any(r.decisive for r in excluded):
    raise ValueError("participation evidence label/exclusion mismatch")
  if len({r.pair_id for r in rows + excluded}) != len(rows) + len(excluded):
    raise ValueError("duplicate participation evidence ID")
  if payload.get("counterfactualTelemetryUsed") or payload.get(
      "indeterminateRowsForcedIntoLabels"):
    raise ValueError("unsupported participation evidence")
  records = [dict(r.to_dict(), state=current_state_features(r)) for r in rows]
  x = np.array(
      [[extract(record, v) for v in spec.features] for record in records])
  y = np.array([[spec.labels.index(r.observed_action)] for r in rows])
  costs = np.array(
      [[0 if r.observed_action == label else r.supported_wrong_action_loss
        for label in spec.labels] for r in rows])
  return TrainingDataset(tuple(r.pair_id for r in rows),
                         tuple(v.name for v in spec.features), x, y,
                         np.ones_like(y, dtype=bool),
                         np.array([r.evidence_weight for r in rows]),
                         tuple(str(source(r, spec.dataset.familyKey)) for r in
                               records),
                         tuple(str(source(r, spec.dataset.blockKey)) for r in
                               records),
                         C=costs, cost_mask=np.ones_like(costs, dtype=bool),
                         metadata=tuple(records),
                         provenance={"excludedRows": len(excluded),
                                     "excludedIds": [r.pair_id for r in
                                                     excluded]})


def _surface(spec, manifest, base, cache):
  arms = load_arms(manifest, base, cache)
  records = [arm.record() for arm in arms]
  paths = [v.configPath for v in spec.controls]
  reference = {}
  for record in records:
    family = str(source(record, spec.dataset.familyKey))
    if family in reference:
      prior = reference[family]
      if not compatible_configs(prior["config"], record["config"], paths):
        raise ValueError(f"incompatible non-treatment configuration: {family}")
      if prior["identity"] != record["identity"] or prior["fixture"] != record[
        "fixture"]:
        raise ValueError(f"incompatible runtime/topology/fixture: {family}")
    reference[family] = record
    # Explicit topology remains evidence; never infer physical workers from cpuSet.
    for control in spec.controls:
      node = record["config"]
      try:
        for part in control.configPath[1:].split("/"):
          key = part.replace("~1", "/").replace("~0", "~")
          node = node[int(key)] if isinstance(node, list) else node[key]
      except (KeyError, IndexError):
        resolved = record.get("resolvedDefaults", {})
        if control.configPath not in resolved:
          raise ValueError(
            f"missing explicit/resolved control: {control.configPath}") from None
        node = resolved[control.configPath]
      if node != source(record, control.source):
        raise ValueError(f"treatment/config mismatch: {control.name}")
  variables = spec.features + spec.controls
  x = np.array([[extract(r, v) for v in variables] for r in records])
  if spec.kind == "classification":
    y = np.array(
        [[spec.labels.index(str(source(r, spec.targets[0].source)))] for r in
         records])
  else:
    y = np.array([[extract(r, v) for v in spec.targets] for r in records])
  controls = np.array(
      [[float(source(r, v.source)) for v in spec.controls] for r in records])
  u = np.unique(controls, axis=0) if spec.controls else None
  return TrainingDataset(tuple(f"{a.run_id}/{a.fork_id}" for a in arms),
                         tuple(v.name for v in variables),
                         x, y, np.isfinite(y),
                         np.array([float(r.get("weight", 1)) for r in records]),
                         tuple(str(source(r, spec.dataset.familyKey)) for r in
                               records),
                         tuple(str(source(r, spec.dataset.blockKey)) for r in
                               records),
                         U=u, metadata=tuple(records),
                         provenance={
                           "armDigests": [a.content_digest for a in arms],
                           "replicationUnit": "fork"})


def training_weights(spec, data, transform):
  if spec.dataset.adapter == "participation_binary":
    totals = {}
    for family, weight in zip(data.groups, data.weights):
      totals[family] = totals.get(family, 0) + weight
    influence = np.array([w * (1.0 / max(1.0, totals[g])) for g, w in
                          zip(data.groups, data.weights)])
    cost = np.max(data.C, axis=1)
    if np.any(cost <= 0):
      raise ValueError(
        "participation training requires positive supported loss")
  else:
    influence = data.weights.copy()
    cost = np.ones(len(data.row_ids))
  if transform == "sqrt":
    cost = np.sqrt(cost)
  elif transform == "log":
    cost = np.log1p(cost)
  elif transform == "mixed":
    physical = cost / np.mean(cost)
    cost = 0.9 * physical + 0.1
  weights = influence * cost
  return influence, weights / np.mean(weights)


def decode(spec, prediction, threshold):
  if spec.kind != "classification":
    return prediction
  if len(spec.labels) == 2:
    return (prediction[:, 1] >= threshold).astype(int)
  return np.argmax(prediction, axis=1)


def metrics(spec, data, prediction, threshold=0.5):
  if spec.kind == "classification":
    actions = decode(spec, prediction, threshold)
    losses = (actions != data.Y[:, 0]).astype(float)
    if data.C is not None:
      valid = data.cost_mask[np.arange(len(actions)), actions]
      if not valid.all():
        raise ValueError("decoded action has no valid observed cost")
      losses = data.C[np.arange(len(actions)), actions]
    if spec.dataset.adapter == "participation_binary":
      from ..direct_side import _rows_from_dataset
      from ..model_tournament import evaluate_predictions
      rows = _rows_from_dataset({"rows": data.metadata})
      predictions = [{"pairId": data.row_ids[i], "familyId": data.groups[i],
                      "action": spec.labels[int(action)]} for i, action in
                     enumerate(actions)]
      legacy = evaluate_predictions(rows, predictions)
      return dict(legacy, regret=legacy["supportedRelativeRegret"],
                  worstFamilyRegret=legacy[
                    "worstFamilySupportedRelativeRegret"],
                  falseDefaultRegret=legacy["falseDefault"][
                    "supportedRelativeRegret"],
                  falseCacheRegret=legacy["falseCache"][
                    "supportedRelativeRegret"],
                  error=1 - legacy[
                    "familyBalancedEvidenceWeightedSideAccuracy"])
    influence, _ = training_weights(spec, data, "raw")
    return {"regret": float(np.average(losses, weights=influence)),
            "error": float(
              np.average(actions != data.Y[:, 0], weights=influence))}
  valid = data.mask
  if not valid.any():
    raise ValueError("no observed outcomes for evaluation")
  result = {"mse": float(np.average((prediction[valid] - data.Y[valid]) ** 2,
                                    weights=
                                    np.broadcast_to(data.weights[:, None],
                                                    valid.shape)[valid]))}
  result["perOutputMse"] = {
    target.name: float(np.average(
      (prediction[data.mask[:, i], i] - data.Y[data.mask[:, i], i]) ** 2,
      weights=data.weights[data.mask[:, i]]))
    for i, target in enumerate(spec.targets) if data.mask[:, i].any()}
  if spec.objective.adapter not in {"workload_normalized_throughput",
                                    "baseline_relative_throughput"}:
    return result
  t = [v.name for v in spec.targets].index(spec.objective.decisionOutput)
  per_family = {}
  for family in sorted(set(data.groups)):
    choices = {}
    for i, group in enumerate(data.groups):
      if group != family or not data.mask[i, t]:
        continue
      key = tuple(
          float(source(data.metadata[i], c.source)) for c in spec.controls)
      choices.setdefault(key, []).append(i)
    measured = []
    for key, indices in sorted(choices.items()):
      target = spec.targets[t]
      op = expression(target.transform).op
      values = data.Y[indices, t]
      if op == "log":
        values = np.exp(values)
      elif op == "log1p":
        values = np.expm1(values)
      elif op != "identity":
        raise ValueError("throughput target supports identity/log/log1p")
      if np.any(values <= 0):
        raise ValueError("throughput must be positive")
      measured.append(
          (float(np.mean(prediction[indices, t])), float(np.mean(values)), key))
    if measured:
      selected = max(measured, key=lambda v: (v[0], tuple(-x for x in v[2])))
      best = max(v[1] for v in measured)
      per_family[family] = {"normalizedThroughput": selected[1] / best,
                            "regression": 1 - selected[1] / best,
                            "controls": selected[2]}
      if spec.objective.adapter == "baseline_relative_throughput":
        per_family[family].update(baselineRatio=selected[1],
                                  logBaselineRatio=float(np.log(selected[1])),
                                  baselinePercentChange=100 * (selected[1] - 1))
  if not per_family:
    raise ValueError("no observed decision output")
  ratios = [v["normalizedThroughput"] for v in per_family.values()]
  result.update(normalizedThroughput=float(np.mean(ratios)),
                lowerTail=float(np.quantile(ratios, .1)),
                worstWorkload=float(min(ratios)), perWorkload=per_family)
  if spec.objective.adapter == "baseline_relative_throughput":
    j = float(np.mean([v["logBaselineRatio"] for v in per_family.values()]))
    result.update(baselineRelativeJ=j,
                  geometricChangePercent=100 * float(np.expm1(j)),
                  worstHeldWorkloadPercent=min(
                      v["baselinePercentChange"] for v in per_family.values()))
  return result


def ranking(spec, result):
  defaults = {"supported_wrong_action_regret": ("regret", "error"),
              "workload_normalized_throughput": ("normalizedThroughput",
                                                 "lowerTail", "worstWorkload"),
              "baseline_relative_throughput": ("baselineRelativeJ",
                                               "worstHeldWorkloadPercent"),
              "masked_mse": ("mse",)}
  names = spec.objective.metrics or defaults[spec.objective.adapter]
  if not spec.objective.metrics and spec.dataset.adapter == "participation_binary":
    names = ("regret", "worstFamilyRegret", "falseDefaultRegret",
             "falseCacheRegret", "error")
  maximize = {"normalizedThroughput", "lowerTail", "worstWorkload",
              "baselineRelativeJ", "worstHeldWorkloadPercent"}
  return tuple(
      -result[name] if name in maximize else result[name] for name in names)
