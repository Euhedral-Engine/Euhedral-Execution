"""Internal binary classifiers for frozen side-of-peak evidence, never runtime Java.

Positive score always means RIGHT/CACHE; zero means LEFT/DEFAULT. All fitted
preprocessing lives in the classifier. Only current-state features enter it.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
import base64
from copy import deepcopy
import hashlib
import os
import pickle
import platform
from typing import Sequence
import warnings
import zlib

import numpy as np
import scipy
from scipy.special import expit
import sklearn
from sklearn.base import BaseEstimator, TransformerMixin
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingClassifier, \
  RandomForestClassifier
from sklearn.exceptions import ConvergenceWarning
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import SplineTransformer, StandardScaler
from sklearn.svm import SVC
from sklearn.tree import DecisionTreeClassifier

from pareto_weight_calibration.action_model import ActionRow, CACHE, \
  fold_influence
from pareto_weight_calibration.direct_side import (
  BASE_FEATURES, action_for_score, current_state_features, direct_design_matrix,
  fit_direct_classifier,
)
from pareto_weight_calibration.tournament_config import frozen_candidate, \
  load_grid
from pareto_weight_calibration.types import DomainConfig

MONOTONICITY = {
  "linear": "enforced", "polynomial": "not enforced", "spline": "not enforced",
  "tree": "enforced", "forest": "enforced", "boosted_tree": "enforced",
  "svm": "not enforced", "mlp": "not enforced",
}
WEIGHTING = "fold_influence(train) * supported_wrong_action_loss; mean-one library weights"


def runtime_matrix(rows: Sequence[ActionRow]) -> np.ndarray:
  matrix = np.asarray(
      [[current_state_features(row)[name] for name in BASE_FEATURES]
       for row in rows], dtype=np.float64).reshape(-1, len(BASE_FEATURES))
  if not np.isfinite(matrix).all():
    raise ValueError("non-finite runtime features")
  return matrix


def training_weights(rows: Sequence[ActionRow]) -> tuple[
  np.ndarray, np.ndarray]:
  """Return fold influence for scaling and supported physical costs for learning.

  Fold-local family caps reproduce the direct classifier. Dividing the cost
  vector by its mean preserves every row cost ratio while making C/L2 independent
  of throughput units and overall evidence mass. There is no class multiplier.
  """
  if not rows or any(not row.decisive for row in rows):
    raise ValueError(
      "training requires nonempty decisive rows; indeterminate rows are excluded")
  if len({row.pair_id for row in rows}) != len(rows):
    raise ValueError("duplicate training pair id")
  influence, _ = fold_influence(rows)
  costs = np.asarray([row.supported_wrong_action_loss for row in rows])
  if (not np.isfinite(influence).all() or not np.isfinite(costs).all()
      or np.any(influence <= 0) or np.any(costs <= 0)):
    raise ValueError(
      "decisive evidence and supported physical loss must be finite and positive")
  weighted = influence * costs
  if not np.isfinite(weighted).all() or not np.isfinite(weighted.sum()):
    raise ValueError("non-finite weighted supported loss")
  return influence, weighted / weighted.mean()


class SideClassifier(ABC):
  """Common fit/score/probability/action/serialization contract for every family."""

  family: str

  def __init__(self, device: str = "cpu"):
    if device not in {"auto", "cpu", "cuda"}:
      raise ValueError("device must be auto, cpu, or cuda")
    self.requested_device = device
    self.actual_device = "cpu"
    self.config = None
    self.constant_probability = None

  @property
  def monotonicity(self) -> str:
    return MONOTONICITY[self.family]

  def fit(self, train_rows: Sequence[ActionRow],
      config: dict) -> SideClassifier:
    # Canonical ordering also fixes stochastic estimators' sample ordering.
    rows = sorted(train_rows, key=lambda row: row.pair_id)
    self.config = frozen_candidate(self.family, config)
    self.seed = load_grid()["seed"]
    influence, sample_weight = training_weights(rows)
    x = runtime_matrix(rows)
    y = np.asarray([int(row.observed_action == CACHE) for row in rows])
    self.constant_probability = None
    try:
      if len(np.unique(y)) == 1:
        # A training-only constant handles small one-class folds uniformly.
        self.constant_probability = float(y[0])
      else:
        self._fit(rows, x, y, influence, sample_weight)
    except Exception:
      self.config = None
      raise
    return self

  @abstractmethod
  def _fit(self, rows, x, y, influence, sample_weight) -> None:
    raise NotImplementedError

  def _check_fitted(self) -> None:
    if self.config is None:
      raise ValueError("classifier has not been fitted")

  def predict_score(self, rows: Sequence[ActionRow]) -> np.ndarray:
    self._check_fitted()
    if not rows:
      return np.empty(0, dtype=np.float64)
    if self.constant_probability is not None:
      scores = np.full(len(rows), 1.0 if self.constant_probability else -1.0)
    else:
      scores = np.asarray(self._score(rows), dtype=np.float64)
    if scores.shape != (len(rows),) or not np.isfinite(scores).all():
      raise ValueError("invalid classifier scores")
    return scores

  @abstractmethod
  def _score(self, rows) -> np.ndarray:
    raise NotImplementedError

  def predict_probability(self, rows: Sequence[ActionRow]) -> np.ndarray | None:
    self._check_fitted()
    if self.family == "svm":
      # SVC(probability=True) would introduce ungrouped internal CV.
      return None
    if self.constant_probability is not None:
      return np.full(len(rows), self.constant_probability)
    return expit(self.predict_score(rows))

  def predict_action(self, rows: Sequence[ActionRow]) -> list[str]:
    return [action_for_score(float(score)) for score in
            self.predict_score(rows)]

  def serialize_model(self) -> dict:
    """Versioned trusted-local snapshot including all learned preprocessing.

    Pickle is an internal Python interchange, not a production deployment
    format. Only deserialize snapshots produced by a trusted tournament.
    """
    self._check_fitted()
    payload = zlib.compress(pickle.dumps(self, protocol=5), level=9)
    return {
      "schemaVersion": 1, "format": "trusted-python-pickle-zlib-base64",
      "modelFamily": self.family, "config": deepcopy(self.config),
      "features": list(BASE_FEATURES), "monotonicity": self.monotonicity,
      "probabilityKind": "unavailable" if self.family == "svm" else "uncalibrated-cost-sensitive",
      "scoreSemantics": "score > 0 => RIGHT_OF_PEAK/CACHE; otherwise LEFT_OF_PEAK/DEFAULT",
      "trainingWeightSemantics": WEIGHTING,
      "requestedDevice": self.requested_device,
      "actualDevice": self.actual_device,
      "seed": self.seed,
      "versions": {"python": platform.python_version(), "numpy": np.__version__,
                   "scipy": scipy.__version__, "sklearn": sklearn.__version__,
                   "torch": getattr(self, "torch_version", None)},
      "payloadSha256": hashlib.sha256(payload).hexdigest(),
      "payload": base64.b64encode(payload).decode("ascii"),
    }


def deserialize_model(snapshot: dict, *,
    trusted: bool = False) -> SideClassifier:
  if not trusted:
    raise ValueError("only load a trusted local tournament snapshot")
  if snapshot["schemaVersion"] != 1 or snapshot[
    "format"] != "trusted-python-pickle-zlib-base64":
    raise ValueError("unsupported model snapshot")
  if snapshot["versions"]["sklearn"] != sklearn.__version__:
    raise ValueError("snapshot requires its original scikit-learn version")
  payload = base64.b64decode(snapshot["payload"], validate=True)
  if hashlib.sha256(payload).hexdigest() != snapshot["payloadSha256"]:
    raise ValueError("model snapshot checksum mismatch")
  model = pickle.loads(zlib.decompress(payload))
  if not isinstance(model, SideClassifier) or model.family != snapshot[
    "modelFamily"]:
    raise ValueError("invalid classifier snapshot")
  return model


class LinearClassifier(SideClassifier):
  family = "linear"

  def _fit(self, rows, x, y, influence, sample_weight):
    # Exact existing S0 objective, scaler, bounds and positive K constraint.
    self.direct_fit = fit_direct_classifier(rows, "S0_BASE",
                                            domain=DomainConfig(),
                                            **self.config["params"])

  def _score(self, rows):
    fit = self.direct_fit
    return direct_design_matrix(rows, fit.structure, fit.scaler) @ np.asarray(
      fit.coefficients)

  def predict_probability(self, rows):
    if self.constant_probability is not None:
      return super().predict_probability(rows)
    scores = self.predict_score(rows)
    return expit(scores / self.direct_fit.temperature)


class ConstrainedQuadraticFeatures(TransformerMixin, BaseEstimator):
  """Only the named second-order terms, after train-only main-effect scaling."""

  def __init__(self, geometry="quadratic"):
    self.geometry = geometry

  def fit(self, x, y=None):
    if self.geometry not in {"interactions", "quadratic"}:
      raise ValueError("unknown second-order geometry")
    return self

  def transform(self, x):
    # K, P/R, logR, body, contention. logR never gets polynomial terms.
    pairs = ((0, 1), (0, 3), (0, 4), (3, 1), (4, 1), (3, 4))
    columns = [x, *[(x[:, i] * x[:, j])[:, None] for i, j in pairs]]
    if self.geometry == "quadratic":
      columns += [(x[:, i] ** 2)[:, None] for i in (0, 1, 3, 4)]
    return np.hstack(columns)


class SklearnClassifier(SideClassifier):
  def _fit(self, rows, x, y, influence, sample_weight):
    self.pipeline = self._pipeline()
    params = {"model__sample_weight": sample_weight}
    if "scale" in self.pipeline.named_steps:
      params["scale__sample_weight"] = influence
    with warnings.catch_warnings():
      # A failed fit aborts the run, rather than silently dropping its family.
      warnings.simplefilter("error", ConvergenceWarning)
      self.pipeline.fit(x, y, **params)

  @abstractmethod
  def _pipeline(self) -> Pipeline:
    raise NotImplementedError

  def _score(self, rows):
    x = runtime_matrix(rows)
    if hasattr(self.pipeline, "decision_function"):
      return self.pipeline.decision_function(x)
    probability = self.pipeline.predict_proba(x)[:, 1]
    p = np.clip(probability, 1e-15, 1 - 1e-15)
    return np.log(p) - np.log1p(-p)

  def predict_probability(self, rows):
    self._check_fitted()
    if self.family == "svm":
      return None
    if self.constant_probability is not None or not rows:
      return super().predict_probability(rows)
    return self.pipeline.predict_proba(runtime_matrix(rows))[:, 1]


def _logistic(c, seed):
  return LogisticRegression(C=c, penalty="l2", solver="lbfgs", max_iter=2000,
                            tol=1e-8, random_state=seed, class_weight=None)


class PolynomialClassifier(SklearnClassifier):
  family = "polynomial"

  def _pipeline(self):
    p = self.config["params"]
    return Pipeline([("scale", StandardScaler()),
                     ("geometry", ConstrainedQuadraticFeatures(p["geometry"])),
                     ("model", _logistic(p["C"], self.seed))])


class SplineClassifier(SklearnClassifier):
  family = "spline"

  def _pipeline(self):
    p = self.config["params"]
    basis = ColumnTransformer([
      ("smooth", SplineTransformer(n_knots=p["n_knots"], degree=p["degree"],
                                   knots="uniform", extrapolation="linear",
                                   include_bias=False), [0, 1, 3, 4]),
      ("logR", "passthrough", [2]),
    ])
    return Pipeline([("scale", StandardScaler()), ("basis", basis),
                     ("model", _logistic(p["C"], self.seed))])


class TreeClassifier(SklearnClassifier):
  family = "tree"

  def _pipeline(self):
    return Pipeline([("model", DecisionTreeClassifier(
        **self.config["params"], random_state=self.seed, class_weight=None,
        monotonic_cst=[1, 0, 0, 0, 0]))])


class ForestClassifier(SklearnClassifier):
  family = "forest"

  def _pipeline(self):
    return Pipeline([("model", RandomForestClassifier(
        **self.config["params"], random_state=self.seed, n_jobs=1,
        class_weight=None,
        monotonic_cst=[1, 0, 0, 0, 0]))])


class BoostedTreeClassifier(SklearnClassifier):
  family = "boosted_tree"

  def _pipeline(self):
    return Pipeline([("model", HistGradientBoostingClassifier(
        **self.config["params"], random_state=self.seed, class_weight=None,
        monotonic_cst=[1, 0, 0, 0, 0], early_stopping=False))])


class SVMClassifier(SklearnClassifier):
  family = "svm"

  def _pipeline(self):
    return Pipeline([("scale", StandardScaler()), ("model", SVC(
        **self.config["params"], kernel="rbf", probability=False,
        class_weight=None, random_state=self.seed, tol=1e-6))])


def weighted_binary_loss(logits, labels, sample_weight):
  """Differentiable row-cost loss for the tiny neural baseline."""
  import torch
  losses = torch.nn.functional.binary_cross_entropy_with_logits(logits, labels,
                                                                reduction="none")
  return (sample_weight * losses).sum() / sample_weight.sum()


class MLPClassifier(SideClassifier):
  family = "mlp"

  def _fit(self, rows, x, y, influence, sample_weight):
    # Reuse the project's device resolver. Other families remain CPU models.
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    import torch
    from pareto_weight_calibration.device import DTYPE, resolve_device

    device = resolve_device(self.requested_device)
    self.actual_device = str(device)
    self.torch_version = torch.__version__
    self.scaler = StandardScaler().fit(x, sample_weight=influence)
    x_tensor = torch.as_tensor(self.scaler.transform(x), dtype=DTYPE,
                               device=device)
    y_tensor = torch.as_tensor(y, dtype=DTYPE, device=device)
    w_tensor = torch.as_tensor(sample_weight, dtype=DTYPE, device=device)
    p = self.config["params"]
    devices = [torch.cuda.current_device()] if device.type == "cuda" else []
    deterministic = torch.are_deterministic_algorithms_enabled()
    warn_only = torch.is_deterministic_algorithms_warn_only_enabled()
    try:
      torch.use_deterministic_algorithms(True)
      with torch.random.fork_rng(devices=devices):
        torch.manual_seed(self.seed)
        net = torch.nn.Sequential(
            torch.nn.Linear(5, p["hidden_units"], dtype=DTYPE), torch.nn.Tanh(),
            torch.nn.Linear(p["hidden_units"], 1, dtype=DTYPE),
        ).to(device)
        optimizer = torch.optim.Adam(net.parameters(), lr=p["learning_rate"])
        for _ in range(p["epochs"]):
          optimizer.zero_grad(set_to_none=True)
          logits = net(x_tensor).squeeze(1)
          loss = weighted_binary_loss(logits, y_tensor, w_tensor)
          loss = loss + p["l2"] * sum(
              parameter.square().sum() for name, parameter in
              net.named_parameters()
              if name.endswith("weight"))
          if not torch.isfinite(loss):
            raise ValueError("non-finite MLP training loss")
          loss.backward()
          optimizer.step()
        # Store CPU arrays only: stable serialization, no CUDA context in artifacts.
        self.parameters = [parameter.detach().cpu().numpy().copy()
                           for parameter in net.parameters()]
    finally:
      torch.use_deterministic_algorithms(deterministic, warn_only=warn_only)

  def _score(self, rows):
    x = self.scaler.transform(runtime_matrix(rows))
    w1, b1, w2, b2 = self.parameters
    return (np.tanh(x @ w1.T + b1) @ w2.T + b2).ravel()


MODEL_CLASSES = {cls.family: cls for cls in (
  LinearClassifier, PolynomialClassifier, SplineClassifier, TreeClassifier,
  ForestClassifier, BoostedTreeClassifier, SVMClassifier, MLPClassifier,
)}


def create_model(family: str, device: str = "cpu") -> SideClassifier:
  if family not in MODEL_CLASSES:
    raise ValueError(f"unknown model family {family}")
  return MODEL_CLASSES[family](device=device)
