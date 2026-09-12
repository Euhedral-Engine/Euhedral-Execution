"""Versioned task contracts. No executable expressions or implicit dimensions."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


def canonical(value) -> str:
  return json.dumps(value, sort_keys=True, separators=(",", ":"),
                    allow_nan=False)


def digest(value) -> str:
  return hashlib.sha256(canonical(value).encode()).hexdigest()


class SpecModel(BaseModel):
  model_config = ConfigDict(extra="forbid", frozen=True, allow_inf_nan=False)


class Expression(SpecModel):
  op: Literal[
    "identity", "scale", "ratio", "log", "log1p", "product", "square", "clamp"] = "identity"
  args: tuple[str, ...] = ()
  factor: float = 1.0
  bounds: tuple[float, float] | None = None

  @model_validator(mode="after")
  def validate_expression(self):
    if self.op in {"ratio", "product"} and len(self.args) != 2:
      raise ValueError("ratio/product require two named arguments")
    if self.op not in {"ratio", "product"} and len(self.args) > 1:
      raise ValueError("unary expression accepts at most one argument")
    if self.op == "clamp" and self.bounds is None:
      raise ValueError("clamp requires bounds")
    if self.bounds is not None and self.bounds[0] > self.bounds[1]:
      raise ValueError("inverted bounds")
    return self


class Variable(SpecModel):
  name: str = Field(pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
  source: str
  type: Literal["float", "int"] = "float"
  units: str = "dimensionless"
  transform: Expression | Literal[
    "identity", "log", "log1p", "square"] = "identity"
  missing: Literal["error", "zero", "mask"] = "error"
  bounds: tuple[float, float] | None = None
  configPath: str | None = None

  @model_validator(mode="after")
  def validate_variable(self):
    if self.bounds and self.bounds[0] > self.bounds[1]:
      raise ValueError("inverted variable bounds")
    if self.configPath is not None and (
        not self.configPath.startswith("/") or self.configPath.endswith("/")):
      raise ValueError("configPath must be an exact JSON Pointer")
    return self


class Term(SpecModel):
  name: str
  expression: Expression


class Basis(SpecModel):
  mainEffects: bool = True
  terms: tuple[Term, ...] = ()
  scaling: Literal["standard", "none"] = "standard"
  scalingOrder: Literal[
    "before_expansion", "after_expansion"] = "before_expansion"


class DatasetSpec(SpecModel):
  manifest: str
  adapter: Literal[
    "participation_binary", "jmh_response_surface", "policy_response"]
  familyKey: str = "workloadId"
  blockKey: str = "passId"
  inventory: dict[str, int] = Field(default_factory=dict)


class Objective(SpecModel):
  adapter: Literal[
    "supported_wrong_action_regret", "workload_normalized_throughput", "baseline_relative_throughput", "masked_mse"]
  direction: Literal["maximize", "minimize"] = "minimize"
  metrics: tuple[str, ...] = ()
  trainingWeight: Literal["raw", "sqrt", "log", "mixed"] = "raw"
  decisionOutput: str | None = None


class Validation(SpecModel):
  outerFolds: int = Field(default=4, ge=2)
  innerFolds: int = Field(default=4, ge=2)
  seed: int = 20260830
  policy: Literal["workload_holdout", "parameter_holdout"] = "workload_holdout"


class Candidate(SpecModel):
  id: str
  family: Literal[
    "logistic", "ridge", "independent_ridge", "cart", "random_forest", "extra_trees", "hist_boost"]
  params: dict = Field(default_factory=dict)
  grid: dict[str, tuple] = Field(default_factory=dict)
  thresholds: tuple[float, ...] = (0.5,)
  trainingWeight: Literal["raw", "sqrt", "log", "mixed"] | None = None
  basis: Basis | None = None
  monotonic: dict[str, Literal[-1, 0, 1]] = Field(default_factory=dict)

  @model_validator(mode="after")
  def validate_candidate(self):
    if not self.thresholds or any(not 0 < x < 1 for x in self.thresholds):
      raise ValueError("thresholds must be in (0,1)")
    if any(not values for values in self.grid.values()):
      raise ValueError("empty hyperparameter grid")
    return self


class Export(SpecModel):
  kind: Literal["none", "linear", "timing_table"] = "none"
  outputNames: tuple[str, ...] = ()
  bounds: dict[str, tuple[float, float]] = Field(default_factory=dict)
  rounding: Literal["none", "nearest", "floor"] = "none"


class TrainingTaskSpec(SpecModel):
  schemaVersion: Literal[1]
  id: str
  kind: Literal["classification", "response_surface", "regression"]
  dataset: DatasetSpec
  features: tuple[Variable, ...]
  targets: tuple[Variable, ...]
  labels: tuple[str, ...] = ()
  controls: tuple[Variable, ...] = ()
  basis: Basis = Field(default_factory=Basis)
  objective: Objective
  validation: Validation = Field(default_factory=Validation)
  candidates: tuple[Candidate, ...]
  export: Export = Field(default_factory=Export)

  @model_validator(mode="after")
  def validate_task(self):
    inputs = self.features + self.controls
    names = [x.name for x in inputs]
    if not self.features or not self.targets or not self.candidates:
      raise ValueError("features, targets and candidates must be nonempty")
    for values in (names, [x.name for x in self.targets],
                   [x.id for x in self.candidates], list(self.labels)):
      if len(values) != len(set(values)):
        raise ValueError("duplicate names/IDs")
    if any(x.missing == "mask" for x in inputs):
      raise ValueError("only targets may use missing=mask")
    if any(x.configPath is None for x in self.controls):
      raise ValueError("controls require exact configPath")
    paths = [x.configPath for x in self.controls]
    if len(paths) != len(set(paths)) or any(
        a != b and b.startswith(a + "/") for a in paths for b in paths):
      raise ValueError("overlapping treatment paths")
    if self.kind == "classification" and (
        len(self.targets) != 1 or len(self.labels) < 2):
      raise ValueError("classification requires one target and explicit labels")
    if self.kind != "classification" and self.labels:
      raise ValueError("regression cannot declare labels")
    if self.objective.adapter not in {"workload_normalized_throughput",
                                      "baseline_relative_throughput"} and self.objective.direction != "minimize":
      raise ValueError("regret and MSE objectives must be minimized")
    if self.objective.adapter == "supported_wrong_action_regret" and self.kind != "classification":
      raise ValueError("regret requires classification")
    if self.objective.adapter in {"workload_normalized_throughput",
                                  "baseline_relative_throughput"}:
      if self.kind != "response_surface" or not self.controls or self.objective.direction != "maximize":
        raise ValueError(
          "throughput objective requires response surface, controls and maximize")
      if self.objective.decisionOutput not in [x.name for x in self.targets]:
        raise ValueError("throughput objective requires named decisionOutput")
    for basis in (self.basis, *(x.basis for x in self.candidates if x.basis)):
      available = set(names)
      for term in basis.terms:
        if term.name in available or not term.expression.args or not set(
            term.expression.args) <= available:
          raise ValueError("invalid named basis expression")
        available.add(term.name)
      if not basis.mainEffects and not basis.terms:
        raise ValueError("empty basis")
    exported_names = [v.name for v in (
      self.controls if self.export.kind == "timing_table" else self.targets)]
    if self.export.kind == "timing_table" and (
        self.kind != "response_surface" or not self.controls):
      raise ValueError("timing_table requires a response surface with controls")
    if self.export.outputNames and list(
        self.export.outputNames) != exported_names:
      raise ValueError(
        "export outputNames must match ordered target/control names")
    if not set(self.export.bounds) <= set(exported_names):
      raise ValueError("unknown export bound output")
    if any(lo > hi for lo, hi in self.export.bounds.values()):
      raise ValueError("inverted export bounds")
    if self.kind == "classification" and (
        self.export.bounds or self.export.rounding != "none"):
      raise ValueError(
        "classification bounds/rounding belong in a decoding adapter")
    for candidate in self.candidates:
      if "monotonic_cst" in candidate.params or "monotonic_cst" in candidate.grid:
        raise ValueError("declare monotonic constraints by feature name")
      if not set(candidate.monotonic) <= set(names):
        raise ValueError("unknown monotonic feature")
    return self

  @property
  def schema_hash(self):
    return digest(self.model_dump(mode="json"))

  @classmethod
  def load(cls, path: Path):
    return cls.model_validate_json(path.read_text())
