"""Frozen tournament configuration; no data-dependent search-space construction."""

from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path

GRID_PATH = Path(__file__).with_name("tournament_grid.json")
MODEL_ORDER = ("linear", "polynomial", "spline", "tree", "forest",
               "boosted_tree", "svm", "mlp")


def load_grid() -> dict:
  grid = json.loads(GRID_PATH.read_text(encoding="utf-8"))
  if grid["schemaVersion"] != 1 or set(grid["models"]) != set(MODEL_ORDER):
    raise ValueError("invalid tournament model grid")
  ids = []
  for family in MODEL_ORDER:
    candidates = grid["models"][family]
    if not candidates or len(candidates) > 4:
      raise ValueError(f"invalid candidate count for {family}")
    for candidate in candidates:
      if set(candidate) != {"id", "params", "complexity"}:
        raise ValueError("invalid candidate schema")
      if candidate["complexity"] < 0 or not isinstance(candidate["params"],
                                                       dict):
        raise ValueError("invalid candidate parameters")
      ids.append(candidate["id"])
  if len(ids) != len(set(ids)):
    raise ValueError("duplicate candidate id")
  return grid


def grid_sha256() -> str:
  return hashlib.sha256(GRID_PATH.read_bytes()).hexdigest()


def parse_models(value: str) -> tuple[str, ...]:
  if value == "all":
    return MODEL_ORDER
  requested = value.split(",")
  if not requested or len(set(requested)) != len(requested) or set(
      requested) - set(MODEL_ORDER):
    raise ValueError(
      f"models must be 'all' or a unique comma-separated subset of {MODEL_ORDER}")
  return tuple(name for name in MODEL_ORDER if name in requested)


def frozen_candidate(family: str, config: dict) -> dict:
  if family not in MODEL_ORDER:
    raise ValueError(f"unknown model family {family}")
  for candidate in load_grid()["models"][family]:
    if config == candidate:
      return deepcopy(candidate)
  raise ValueError(f"configuration is not in the frozen {family} grid")
