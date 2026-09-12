"""Array adapters using the tournament's library estimators and numeric contracts."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.tree import DecisionTreeClassifier, DecisionTreeRegressor
from sklearn.ensemble import (RandomForestClassifier, RandomForestRegressor,
                              ExtraTreesClassifier, ExtraTreesRegressor,
                              HistGradientBoostingClassifier,
                              HistGradientBoostingRegressor)


@dataclass(frozen=True)
class Capabilities:
  classification: bool
  regression: bool
  native_multioutput: bool
  sample_weights: bool = True
  monotonic: bool = False
  devices: tuple[str, ...] = ("cpu",)
  exporters: tuple[str, ...] = ()


CAPABILITIES = {
  "logistic": Capabilities(True, False, False, exporters=("linear",)),
  "ridge": Capabilities(False, True, True, devices=("cpu", "cuda"),
                        exporters=("linear", "timing_table")),
  "independent_ridge": Capabilities(False, True, False, devices=("cpu", "cuda"),
                                    exporters=("linear", "timing_table")),
  "cart": Capabilities(True, True, True, monotonic=True),
  "random_forest": Capabilities(True, True, True, monotonic=True),
  "extra_trees": Capabilities(True, True, True, monotonic=True),
  "hist_boost": Capabilities(True, True, False, monotonic=True),
}


def validate_capabilities(spec, candidate, device):
  cap = CAPABILITIES[candidate.family]
  classification = spec.kind == "classification"
  if not (cap.classification if classification else cap.regression):
    raise ValueError(f"{candidate.family} does not support {spec.kind}")
  if not classification and len(
      spec.targets) > 1 and not cap.native_multioutput and candidate.family != "independent_ridge":
    raise ValueError(
      "multioutput requires native capability or named independent wrapper")
  if candidate.monotonic:
    basis = candidate.basis or spec.basis
    if not cap.monotonic or basis.terms or len(spec.targets) > 1 or len(
        spec.labels) > 2:
      raise ValueError(
        "unsupported monotonic constraints/expanded or multioutput schema")
  if device == "cuda" and "cuda" not in cap.devices:
    raise ValueError(f"{candidate.family} does not support CUDA")
  if spec.export.kind != "none" and spec.export.kind not in cap.exporters:
    raise ValueError(f"{candidate.family} cannot export {spec.export.kind}")
  if device not in {"cpu", "auto", "cuda"}:
    raise ValueError("device must be auto|cuda|cpu")
  return cap


class ArrayModel:
  def __init__(self, spec, candidate, device="auto"):
    self.spec, self.candidate = spec, candidate
    cap = validate_capabilities(spec, candidate, device)
    self.device = "cpu"
    if "cuda" in cap.devices and device != "cpu":
      from .device import resolve_device
      self.device = str(resolve_device(device))
    self.estimator = None
    self.constant = None
    self.coefficients = None
    self.intercept = None
    self.classes = None

  def fit(self, x, y, mask, weights, feature_names, tensor_cache=None):
    candidate, spec = self.candidate, self.spec
    params = dict(candidate.params)
    if candidate.family in {"ridge", "independent_ridge"}:
      alpha = float(params.pop("alpha", 1.0))
      if params or alpha < 0 or not np.isfinite(alpha):
        raise ValueError("ridge accepts only finite nonnegative alpha")
      if candidate.family == "ridge" and not np.all(mask == mask[:, :1]):
        raise ValueError("different output masks require independent_ridge")
      coefficients = []
      for output in range(y.shape[1]):
        valid = mask[:, output]
        if not valid.any():
          raise ValueError(f"training fold has no observed output {output}")
        design = np.column_stack((np.ones(valid.sum()), x[valid]))
        w = np.sqrt(weights[valid])
        a, b = design * w[:, None], y[valid, output] * w
        regularizer = np.eye(design.shape[1]) * alpha
        regularizer[0, 0] = 0
        if self.device == "cuda":
          from .device import to_tensor, to_numpy
          import torch
          if tensor_cache is None:
            tensor_cache = {}
          if output not in tensor_cache:
            tensor_cache[output] = to_tensor(a, self.device), to_tensor(b,
                                                                        self.device)
          a, b = tensor_cache[output]
          reg = to_tensor(regularizer, self.device)
          # Each output owns its objective and mask. Fold tensors are reused
          # across candidate regularization values, never across unrelated fits.
          augmented = torch.cat((a, torch.sqrt(reg)))
          target = torch.cat(
              (b, torch.zeros(reg.shape[0], device=a.device, dtype=a.dtype)))
          coef = to_numpy(torch.linalg.pinv(augmented) @ target)
        else:
          coef = np.linalg.lstsq(np.vstack((a, np.sqrt(regularizer))),
                                 np.concatenate(
                                     (b, np.zeros(len(regularizer)))),
                                 rcond=None)[0]
        coefficients.append(coef)
      coefficients = np.array(coefficients)
      self.intercept, self.coefficients = coefficients[:, 0], coefficients[
        :, 1:]
      return self
    if not mask.all():
      raise ValueError(
        "estimator requires complete outcomes; use independent_ridge for output masks")
    classification = spec.kind == "classification"
    target = y[:, 0].astype(int) if classification or y.shape[1] == 1 else y
    if not classification and y.shape[1] == 1:
      target = y[:, 0]
    if classification:
      self.classes = np.unique(target)
      if len(self.classes) == 1:
        self.constant = int(self.classes[0])
        return self
    params.setdefault("random_state", spec.validation.seed)
    if candidate.family == "logistic":
      params.setdefault("max_iter", 10000)
      params.setdefault("tol", 1e-8)
      params.setdefault("solver", "saga" if params.get(
        "penalty") == "elasticnet" else "lbfgs")
      constructor = LogisticRegression
    else:
      constructor = {
        "cart": (DecisionTreeClassifier, DecisionTreeRegressor),
        "random_forest": (RandomForestClassifier, RandomForestRegressor),
        "extra_trees": (ExtraTreesClassifier, ExtraTreesRegressor),
        "hist_boost": (HistGradientBoostingClassifier,
                       HistGradientBoostingRegressor),
      }[candidate.family][0 if classification else 1]
      if candidate.monotonic:
        params["monotonic_cst"] = [candidate.monotonic.get(name, 0) for name in
                                   feature_names]
      if candidate.family == "hist_boost":
        params.setdefault("early_stopping", False)
    self.estimator = constructor(**params).fit(x, target, sample_weight=weights)
    if candidate.family == "logistic":
      self.coefficients = self.estimator.coef_
      self.intercept = self.estimator.intercept_
      self.classes = self.estimator.classes_
    return self

  def predict(self, x):
    if self.spec.kind == "classification":
      result = np.zeros((len(x), len(self.spec.labels)))
      if self.constant is not None:
        result[:, self.constant] = 1
      else:
        result[:, self.estimator.classes_.astype(
          int)] = self.estimator.predict_proba(x)
      return result
    if self.coefficients is not None:
      return x @ self.coefficients.T + self.intercept
    return np.asarray(self.estimator.predict(x)).reshape(len(x), -1)
