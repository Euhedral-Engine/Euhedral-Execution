"""Immutable fork evidence and dimension-independent training arrays."""
from __future__ import annotations

from dataclasses import dataclass, field
import hashlib
import json
from pathlib import Path

import numpy as np

from .training_spec import canonical, digest


@dataclass(frozen=True)
class ArmRecord:
  run_id: str
  fork_id: str
  workload_id: str
  pass_id: str
  content: str
  content_digest: str

  def record(self):
    # Each consumer gets a copy; cached evidence cannot be mutated.
    return json.loads(self.content)


class ArmCache:
  parser_version = "fork-json-v1"

  def __init__(self):
    self.parsed = {}
    self.parse_count = 0

  def read(self, content: bytes, selection=None):
    key = (hashlib.sha256(content).hexdigest(), self.parser_version,
           digest(selection))
    if key not in self.parsed:
      value = json.loads(content)
      records = value if isinstance(value, list) else [value]
      arms = []
      for row in records:
        for name in ("runId", "forkId", "workloadId", "passId", "identity",
                     "fixture", "treatment", "outcome", "config", "provenance"):
          if name not in row:
            raise ValueError(f"fork record missing {name}")
        if selection is not None and row["provenance"].get(
            "selection") != selection:
          raise ValueError(
            "evidence/window selection must match the fork record provenance")
        arms.append(ArmRecord(str(row["runId"]), str(row["forkId"]),
                              str(row["workloadId"]),
                              str(row["passId"]), canonical(row), key[0]))
      self.parsed[key] = tuple(arms)
      self.parse_count += 1
    return self.parsed[key]


def load_arms(manifest, base: Path, cache=None):
  cache = cache or ArmCache()
  unique = {}
  files = {}
  for entry in manifest.get("arms", []):
    if "path" in entry:
      key = ((base / entry["path"]).resolve(), entry["sha256"])
      if key not in files:
        content = key[0].read_bytes()
        if hashlib.sha256(content).hexdigest() != entry["sha256"]:
          raise ValueError(f"arm digest mismatch: {entry['path']}")
        files[key] = content
      content = files[key]
    else:
      content = canonical(entry["record"]).encode()
    for arm in cache.read(content, entry.get("selection")):
      key = (arm.run_id, arm.fork_id)
      if key in unique and unique[key].content != arm.content:
        raise ValueError(f"conflicting duplicate arm {key}")
      unique[key] = arm
  return tuple(unique[key] for key in sorted(unique))


def canonical_config(config, treatment_paths):
  value = json.loads(canonical(config))
  for pointer in treatment_paths:
    parts = [x.replace("~1", "/").replace("~0", "~") for x in
             pointer[1:].split("/")]
    node = value
    for part in parts[:-1]:
      if isinstance(node, list):
        node = node[int(part)]
      else:
        node = node.get(part, {})
    if isinstance(node, list):
      node[int(parts[-1])] = {"treatment": True}
    elif parts[-1] in node:
      if isinstance(node[parts[-1]], (dict, list)):
        raise ValueError("treatment paths must identify scalar leaves")
      del node[parts[-1]]
  return canonical(value)


def compatible_configs(left, right, treatment_paths):
  return canonical_config(left, treatment_paths) == canonical_config(right,
                                                                     treatment_paths)


@dataclass(frozen=True)
class TrainingDataset:
  row_ids: tuple[str, ...]
  feature_names: tuple[str, ...]
  X: np.ndarray
  Y: np.ndarray
  mask: np.ndarray
  weights: np.ndarray
  groups: tuple[str, ...]
  blocks: tuple[str, ...]
  U: np.ndarray | None = None
  C: np.ndarray | None = None
  cost_mask: np.ndarray | None = None
  metadata: tuple[dict, ...] = ()
  provenance: dict = field(default_factory=dict)

  def __post_init__(self):
    n = len(self.row_ids)
    if not n or len(set(self.row_ids)) != n or len(
        set(self.feature_names)) != len(self.feature_names):
      raise ValueError("empty dataset or duplicate row/feature IDs")
    if self.X.shape != (n, len(self.feature_names)) or self.Y.ndim != 2 or \
        self.Y.shape[0] != n or self.Y.shape[1] == 0:
      raise ValueError("invalid X/Y dimensions")
    if self.mask.shape != self.Y.shape or self.mask.dtype != bool:
      raise ValueError("invalid outcome mask")
    if self.weights.shape != (n,) or not np.isfinite(
        self.weights).all() or np.any(self.weights <= 0):
      raise ValueError("invalid sample weights")
    if len(self.groups) != n or len(self.blocks) != n or len(
        self.metadata) != n:
      raise ValueError("row metadata length mismatch")
    if not np.isfinite(self.X).all() or not np.isfinite(
        self.Y[self.mask]).all():
      raise ValueError("non-finite observed data")
    if self.U is not None and (
        self.U.ndim != 2 or not np.isfinite(self.U).all()):
      raise ValueError("invalid candidate controls")
    if self.C is not None:
      if self.C.ndim != 2 or self.C.shape[
        0] != n or self.cost_mask is None or self.cost_mask.shape != self.C.shape:
        raise ValueError("invalid cost table")
      if self.cost_mask.dtype != bool or (
          self.U is not None and self.C.shape[1] != len(self.U)):
        raise ValueError("cost validity/control width mismatch")
      if not np.isfinite(self.C[self.cost_mask]).all():
        raise ValueError("non-finite valid costs")
    for name in ("X", "Y", "mask", "weights", "U", "C", "cost_mask"):
      array = getattr(self, name)
      if array is not None:
        array = np.array(array, copy=True)
        array.setflags(write=False)
        object.__setattr__(self, name, array)

  def subset(self, indices):
    indices = np.asarray(indices, dtype=int)
    return TrainingDataset(
        tuple(self.row_ids[i] for i in indices), self.feature_names,
        self.X[indices],
        self.Y[indices], self.mask[indices], self.weights[indices],
        tuple(self.groups[i] for i in indices),
        tuple(self.blocks[i] for i in indices),
        self.U, None if self.C is None else self.C[indices],
        None if self.cost_mask is None else self.cost_mask[indices],
        tuple(self.metadata[i] for i in indices), self.provenance)
