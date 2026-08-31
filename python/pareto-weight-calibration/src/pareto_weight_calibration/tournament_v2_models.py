"""Model adapters for the frozen side-of-peak Model Tournament V2.

Every adapter consumes only the five frozen current-state features. Positive
scores and probabilities above the selected threshold mean RIGHT/CACHE.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
import math
import os
from typing import Sequence
import warnings

import numpy as np
from scipy.special import expit
from sklearn.base import BaseEstimator, TransformerMixin
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import (ExtraTreesClassifier,
                              HistGradientBoostingClassifier,
                              RandomForestClassifier)
from sklearn.exceptions import ConvergenceWarning
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import SplineTransformer, StandardScaler
from sklearn.svm import SVC
from sklearn.tree import DecisionTreeClassifier

from pareto_weight_calibration.action_model import ActionRow, CACHE, \
  fold_influence
from pareto_weight_calibration.direct_side import (BASE_FEATURES,
                                                   direct_design_matrix,
                                                   fit_direct_classifier)
from pareto_weight_calibration.tournament_models import runtime_matrix
from pareto_weight_calibration.types import DomainConfig

WEIGHT_TRANSFORMS = ("raw", "sqrt", "log", "mixed")
MONOTONICITY = {
  "direct_linear": "enforced",
  "logistic": "not enforced",
  "gam_spline": "not enforced",
  "cart": "enforced",
  "random_forest": "enforced",
  "extra_trees": "enforced",
  "hist_boost": "enforced",
  "xgboost": "enforced",
  "catboost": "enforced",
  "svm": "not enforced",
  "mlp": "not enforced",
}


def transformed_training_weights(rows: Sequence[ActionRow],
    transform: str) -> tuple[np.ndarray, np.ndarray]:
  """Apply a fold-local transform without changing frozen evaluation costs."""
  if transform not in WEIGHT_TRANSFORMS:
    raise ValueError(f"unknown weight transform {transform}")
  if not rows or any(not row.decisive for row in rows):
    raise ValueError("training weights require nonempty decisive rows")
  influence, _ = fold_influence(rows)
  loss = np.asarray([row.supported_wrong_action_loss for row in rows],
                    dtype=np.float64)
  if (not np.isfinite(loss).all() or np.any(loss <= 0)
      or not np.isfinite(influence).all() or np.any(influence <= 0)):
    raise ValueError("invalid frozen training loss or influence")
  if transform == "raw":
    cost = loss
  elif transform == "sqrt":
    cost = np.sqrt(loss)
  elif transform == "log":
    cost = np.log1p(loss)
  else:
    physical = loss / np.mean(loss)
    cost = 0.9 * physical + 0.1
  weights = influence * cost
  weights = weights / np.mean(weights)
  if not np.isfinite(weights).all() or np.any(weights <= 0):
    raise ValueError("non-finite transformed training weights")
  return influence, weights


class ControlledSecondOrder(TransformerMixin, BaseEstimator):
  """Frozen main effects plus the explicitly allowed second-order terms."""

  def __init__(self, geometry: str = "main"):
    self.geometry = geometry

  def fit(self, x, y=None):
    if self.geometry not in {"main", "interactions", "quadratic"}:
      raise ValueError("unknown feature geometry")
    return self

  def transform(self, x):
    if self.geometry == "main":
      return x
    pairs = ((0, 1), (0, 3), (0, 4), (3, 1), (4, 1), (3, 4))
    columns = [x, *[(x[:, i] * x[:, j])[:, None] for i, j in pairs]]
    if self.geometry == "quadratic":
      columns += [(x[:, i] ** 2)[:, None] for i in (0, 1, 3, 4)]
    return np.hstack(columns)


class V2Classifier(ABC):
  family: str

  def __init__(self, device: str = "cpu"):
    if device not in {"cpu", "cuda", "auto"}:
      raise ValueError("device must be cpu, cuda, or auto")
    self.requested_device = device
    self.actual_device = "cpu"
    self.candidate = None
    self.constant_probability = None

  def fit(self, rows: Sequence[ActionRow], candidate: dict):
    ordered = sorted(rows, key=lambda row: row.pair_id)
    if candidate["modelFamily"] != self.family:
      raise ValueError("candidate/model-family mismatch")
    self.candidate = candidate
    influence, sample_weight = transformed_training_weights(
        ordered, candidate["weightTransform"])
    x = runtime_matrix(ordered)
    y = np.asarray([int(row.observed_action == CACHE) for row in ordered])
    self.constant_probability = None
    if len(np.unique(y)) == 1:
      self.constant_probability = float(y[0])
    else:
      self._fit(ordered, x, y, influence, sample_weight)
    if self.family == "svm":
      scores = self.predict_score(ordered)
      self.score_thresholds = {
        str(threshold): float(np.quantile(scores, threshold))
        for threshold in candidate["thresholdPolicy"]["values"]
      }
    return self

  @abstractmethod
  def _fit(self, rows, x, y, influence, sample_weight):
    raise NotImplementedError

  @abstractmethod
  def _score(self, rows):
    raise NotImplementedError

  def predict_score(self, rows: Sequence[ActionRow]) -> np.ndarray:
    if self.candidate is None:
      raise ValueError("classifier has not been fitted")
    if not rows:
      return np.empty(0, dtype=np.float64)
    if self.constant_probability is not None:
      value = 35.0 if self.constant_probability else -35.0
      return np.full(len(rows), value, dtype=np.float64)
    result = np.asarray(self._score(rows), dtype=np.float64).reshape(-1)
    if result.shape != (len(rows),) or not np.isfinite(result).all():
      raise ValueError("invalid model score")
    return result

  def predict_probability(self, rows: Sequence[ActionRow]) -> np.ndarray | None:
    if self.family == "svm":
      return None
    if self.constant_probability is not None:
      return np.full(len(rows), self.constant_probability)
    return expit(self.predict_score(rows))

  def actions_at_threshold(self, rows: Sequence[ActionRow], threshold: float) -> \
  list[str]:
    probabilities = self.predict_probability(rows)
    if probabilities is not None:
      mask = probabilities >= threshold
    else:
      mask = self.predict_score(rows) >= self.score_thresholds[str(threshold)]
    return np.where(mask, CACHE, "DEFAULT").tolist()

  def metadata(self) -> dict:
    return {
      "modelFamily": self.family,
      "candidateId": self.candidate["id"],
      "actualDevice": self.actual_device,
      "monotonicityK": MONOTONICITY[self.family],
    }


class DirectLinearClassifier(V2Classifier):
  family = "direct_linear"

  def _fit(self, rows, x, y, influence, sample_weight):
    params = self.candidate["params"]
    self.fit_result = fit_direct_classifier(
        rows, "S0_BASE", domain=DomainConfig(), l2=params["l2"],
        temperature=params["temperature"])

  def _score(self, rows):
    return direct_design_matrix(rows, self.fit_result.structure,
                                self.fit_result.scaler) @ np.asarray(
        self.fit_result.coefficients)

  def predict_probability(self, rows):
    if self.constant_probability is not None:
      return super().predict_probability(rows)
    return expit(self.predict_score(rows) / self.fit_result.temperature)


class SklearnClassifier(V2Classifier):
  @abstractmethod
  def _pipeline(self) -> Pipeline:
    raise NotImplementedError

  def _fit(self, rows, x, y, influence, sample_weight):
    self.pipeline = self._pipeline()
    kwargs = {"model__sample_weight": sample_weight}
    if "scale" in self.pipeline.named_steps:
      kwargs["scale__sample_weight"] = influence
    with warnings.catch_warnings():
      warnings.simplefilter("error", ConvergenceWarning)
      self.pipeline.fit(x, y, **kwargs)

  def _score(self, rows):
    x = runtime_matrix(rows)
    if hasattr(self.pipeline, "decision_function"):
      return self.pipeline.decision_function(x)
    probability = np.clip(self.pipeline.predict_proba(x)[:, 1], 1e-15,
                          1 - 1e-15)
    return np.log(probability) - np.log1p(-probability)

  def predict_probability(self, rows):
    if self.family == "svm":
      return None
    if self.constant_probability is not None or not rows:
      return super().predict_probability(rows)
    return self.pipeline.predict_proba(runtime_matrix(rows))[:, 1]


class LogisticClassifier(SklearnClassifier):
  family = "logistic"

  def _pipeline(self):
    params = self.candidate["params"]
    penalty = params["penalty"]
    solver = "saga" if penalty == "elasticnet" else "lbfgs"
    model = LogisticRegression(
        C=params["C"], penalty=penalty, solver=solver,
        l1_ratio=params.get("l1_ratio"), max_iter=10000, tol=1e-8,
        random_state=20260830, class_weight=None)
    return Pipeline([
      ("scale", StandardScaler()),
      ("geometry", ControlledSecondOrder(params["geometry"])),
      ("model", model),
    ])


class GamSplineClassifier(SklearnClassifier):
  family = "gam_spline"

  def _pipeline(self):
    params = self.candidate["params"]
    basis = ColumnTransformer([
      ("smooth", SplineTransformer(
          n_knots=params["n_knots"], degree=params["degree"],
          knots=params["knots"], extrapolation="linear",
          include_bias=False), [0, 1, 3, 4]),
      ("logR", "passthrough", [2]),
    ])
    model = LogisticRegression(C=params["C"], penalty="l2", solver="lbfgs",
                               max_iter=5000, tol=1e-8,
                               random_state=20260830)
    return Pipeline([("scale", StandardScaler()), ("basis", basis),
                     ("model", model)])


class CartClassifier(SklearnClassifier):
  family = "cart"

  def _pipeline(self):
    return Pipeline([("model", DecisionTreeClassifier(
        **self.candidate["params"], random_state=20260830,
        class_weight=None, monotonic_cst=[1, 0, 0, 0, 0]))])


class RandomForestV2Classifier(SklearnClassifier):
  family = "random_forest"

  def _pipeline(self):
    return Pipeline([("model", RandomForestClassifier(
        **self.candidate["params"], random_state=20260830, n_jobs=1,
        class_weight=None, monotonic_cst=[1, 0, 0, 0, 0]))])


class ExtraTreesV2Classifier(SklearnClassifier):
  family = "extra_trees"

  def _pipeline(self):
    return Pipeline([("model", ExtraTreesClassifier(
        **self.candidate["params"], random_state=20260830, n_jobs=1,
        class_weight=None, monotonic_cst=[1, 0, 0, 0, 0]))])


class HistBoostClassifier(SklearnClassifier):
  family = "hist_boost"

  def _pipeline(self):
    return Pipeline([("model", HistGradientBoostingClassifier(
        **self.candidate["params"], random_state=20260830,
        class_weight=None, monotonic_cst=[1, 0, 0, 0, 0],
        early_stopping=False))])


class XGBoostClassifier(V2Classifier):
  family = "xgboost"

  def _fit(self, rows, x, y, influence, sample_weight):
    from xgboost import XGBClassifier

    params = dict(self.candidate["params"])
    use_cuda = self.requested_device in {"cuda", "auto"}
    self.actual_device = "cuda" if use_cuda else "cpu"
    self.model = XGBClassifier(
        **params, objective="binary:logistic", eval_metric="logloss",
        tree_method="hist", device=self.actual_device, n_jobs=1,
        random_state=20260830, seed=20260830, verbosity=0,
        monotone_constraints=(1, 0, 0, 0, 0))
    self.model.fit(x, y, sample_weight=sample_weight, verbose=False)

  def _score(self, rows):
    probability = np.clip(self.model.predict_proba(runtime_matrix(rows))[:, 1],
                          1e-15, 1 - 1e-15)
    return np.log(probability) - np.log1p(-probability)

  def predict_probability(self, rows):
    if self.constant_probability is not None:
      return super().predict_probability(rows)
    return self.model.predict_proba(runtime_matrix(rows))[:, 1]


class CatBoostClassifier(V2Classifier):
  family = "catboost"

  def _fit(self, rows, x, y, influence, sample_weight):
    from catboost import CatBoostClassifier as NativeCatBoost

    params = dict(self.candidate["params"])
    # CatBoost 1.2.x rejects monotone_constraints with task_type="GPU".
    # Preserve the physical K constraint instead of silently dropping it.
    use_cuda = False
    self.actual_device = "cpu"
    if params.pop("subsample") < 1:
      params.update(bootstrap_type="Bernoulli", subsample=self.candidate[
        "params"]["subsample"])
    self.model = NativeCatBoost(
        **params, loss_function="Logloss", random_seed=20260830,
        random_strength=0, allow_writing_files=False, verbose=False,
        thread_count=1, task_type="CPU", monotone_constraints={0: 1})
    self.model.fit(x, y, sample_weight=sample_weight, verbose=False)

  def _score(self, rows):
    return self.model.predict(runtime_matrix(rows),
                              prediction_type="RawFormulaVal").reshape(-1)

  def predict_probability(self, rows):
    if self.constant_probability is not None:
      return super().predict_probability(rows)
    return self.model.predict_proba(runtime_matrix(rows))[:, 1]


class SvmClassifier(SklearnClassifier):
  family = "svm"

  def _pipeline(self):
    params = self.candidate["params"]
    return Pipeline([
      ("scale", StandardScaler()),
      ("model", SVC(**params, probability=False, class_weight=None,
                    random_state=20260830, tol=1e-6)),
    ])


class MlpClassifier(V2Classifier):
  family = "mlp"

  def _fit(self, rows, x, y, influence, sample_weight):
    import torch
    from pareto_weight_calibration.device import resolve_device

    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    device = resolve_device(self.requested_device)
    self.actual_device = str(device)
    self.scaler = StandardScaler().fit(x, sample_weight=influence)
    dtype = torch.float32
    xt = torch.as_tensor(self.scaler.transform(x), dtype=dtype, device=device)
    yt = torch.as_tensor(y, dtype=dtype, device=device)
    wt = torch.as_tensor(sample_weight, dtype=dtype, device=device)
    params = self.candidate["params"]
    layers = []
    width = 5
    for hidden in params["hidden"]:
      layers.extend([torch.nn.Linear(width, hidden), torch.nn.Tanh()])
      if params["dropout"]:
        layers.append(torch.nn.Dropout(params["dropout"]))
      width = hidden
    layers.append(torch.nn.Linear(width, 1))
    devices = [torch.cuda.current_device()] if device.type == "cuda" else []
    old = torch.are_deterministic_algorithms_enabled()
    try:
      torch.use_deterministic_algorithms(True)
      with torch.random.fork_rng(devices=devices):
        torch.manual_seed(20260830)
        self.net = torch.nn.Sequential(*layers).to(device=device,
                                                   dtype=dtype)
        optimizer = torch.optim.AdamW(
            self.net.parameters(), lr=params["learning_rate"],
            weight_decay=params["weight_decay"])
        self.net.train()
        for _ in range(params["epochs"]):
          optimizer.zero_grad(set_to_none=True)
          logits = self.net(xt).squeeze(1)
          loss = torch.nn.functional.binary_cross_entropy_with_logits(
              logits, yt, weight=wt, reduction="sum") / wt.sum()
          if not torch.isfinite(loss):
            raise ValueError("non-finite MLP loss")
          loss.backward()
          optimizer.step()
        self.net = self.net.cpu().eval()
    finally:
      torch.use_deterministic_algorithms(old)

  def _score(self, rows):
    import torch

    x = torch.as_tensor(self.scaler.transform(runtime_matrix(rows)),
                        dtype=torch.float32)
    with torch.inference_mode():
      return self.net(x).squeeze(1).numpy().astype(np.float64)


MODEL_CLASSES = {cls.family: cls for cls in (
  DirectLinearClassifier, LogisticClassifier, GamSplineClassifier,
  CartClassifier, RandomForestV2Classifier, ExtraTreesV2Classifier,
  HistBoostClassifier, XGBoostClassifier, CatBoostClassifier, SvmClassifier,
  MlpClassifier,
)}


def create_model(family: str, device: str = "cpu") -> V2Classifier:
  if family not in MODEL_CLASSES:
    raise ValueError(f"unknown V2 model family {family}")
  return MODEL_CLASSES[family](device)
