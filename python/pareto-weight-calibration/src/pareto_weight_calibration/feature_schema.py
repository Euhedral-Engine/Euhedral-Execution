"""Named expression compiler and fold-local preprocessing."""
from __future__ import annotations

import numpy as np
from sklearn.preprocessing import StandardScaler

from .training_spec import Basis, Expression


def expression(value):
  return Expression(op=value) if isinstance(value, str) else value


def evaluate(expr, values, default=None):
  expr = expression(expr)
  args = [values[name] for name in expr.args] if expr.args else [default]
  x = args[0]
  with np.errstate(divide="raise", invalid="raise", over="raise"):
    if expr.op == "identity":
      return x
    if expr.op == "scale":
      return x * expr.factor
    if expr.op == "ratio":
      return np.divide(x, args[1])
    if expr.op == "log":
      return np.log(x)
    if expr.op == "log1p":
      return np.log1p(x)
    if expr.op == "product":
      return x * args[1]
    if expr.op == "square":
      return x * x
    return np.clip(x, *expr.bounds)


def source(record, path):
  value = record
  for part in path.split("."):
    value = value[part]
  return value


def extract(record, variable):
  try:
    raw = source(record, variable.source)
    if raw is None:
      raise KeyError(variable.source)
    raw = float(raw)
    if variable.type == "int" and not raw.is_integer():
      raise ValueError(f"{variable.name}: expected integer")
    if variable.bounds and not variable.bounds[0] <= raw <= variable.bounds[1]:
      raise ValueError(f"{variable.name}: outside bounds")
    expr = expression(variable.transform)
    values = {name: float(source(record, name)) for name in expr.args}
    result = float(evaluate(expr, values, raw))
    if not np.isfinite(result):
      raise ValueError(f"{variable.name}: non-finite value")
    return result
  except KeyError:
    if variable.missing == "zero":
      return 0.0
    if variable.missing == "mask":
      return np.nan
    raise ValueError(f"missing source {variable.source}") from None


class FeatureSchema:
  def __init__(self, names, basis: Basis):
    self.names = tuple(names)
    self.basis = basis
    available = set(self.names)
    for term in basis.terms:
      if not set(term.expression.args) <= available or term.name in available:
        raise ValueError("unknown or duplicate basis term")
      available.add(term.name)
    self.output_names = (self.names if basis.mainEffects else ()) + tuple(
        t.name for t in basis.terms)
    self.scaler = None

  def expand(self, x):
    if x.ndim != 2 or x.shape[1] != len(self.names):
      raise ValueError("feature width mismatch")
    values = {name: x[:, i] for i, name in enumerate(self.names)}
    for term in self.basis.terms:
      values[term.name] = evaluate(term.expression, values)
    return np.column_stack([values[name] for name in self.output_names])

  def fit(self, x, weights=None):
    z = x if self.basis.scalingOrder == "before_expansion" else self.expand(x)
    self.scaler = StandardScaler().fit(z,
                                       sample_weight=weights) if self.basis.scaling == "standard" else None
    return self

  def transform(self, x):
    z = x if self.basis.scalingOrder == "before_expansion" else self.expand(x)
    if self.scaler is not None:
      z = self.scaler.transform(z)
    z = self.expand(z) if self.basis.scalingOrder == "before_expansion" else z
    if not np.isfinite(z).all():
      raise ValueError("non-finite expanded features")
    return z

  def serialize(self):
    return {"inputNames": self.names, "outputNames": self.output_names,
            "basis": self.basis.model_dump(mode="json"),
            "mean": None if self.scaler is None else self.scaler.mean_.tolist(),
            "scale": None if self.scaler is None else self.scaler.scale_.tolist()}


def participation_basis(geometry):
  """The historical V2 recipe; scaling occurs before named expansion."""
  if geometry not in {"main", "interactions", "quadratic"}:
    raise ValueError("unknown participation geometry")
  terms = []
  if geometry != "main":
    for a, b in (("K", "pRatio"), ("K", "body"), ("K", "contention"),
                 ("body", "pRatio"), ("contention", "pRatio"),
                 ("body", "contention")):
      terms.append(
          {"name": f"{a}*{b}", "expression": {"op": "product", "args": [a, b]}})
  if geometry == "quadratic":
    for name in ("K", "pRatio", "body", "contention"):
      terms.append(
          {"name": f"{name}^2", "expression": {"op": "square", "args": [name]}})
  return Basis(terms=terms)
