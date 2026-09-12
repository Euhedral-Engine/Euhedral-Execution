"""Offline estimator registry. All family/grid choices are task data, never runtime policy."""

from dataclasses import dataclass
from itertools import product
import warnings
import numpy as np
from scipy.spatial.distance import cdist
from sklearn.base import BaseEstimator, RegressorMixin
from sklearn.dummy import DummyRegressor
from sklearn.linear_model import LinearRegression, Ridge, ElasticNet
from sklearn.kernel_ridge import KernelRidge
from sklearn.neighbors import KNeighborsRegressor
from sklearn.gaussian_process import GaussianProcessRegressor
from sklearn.gaussian_process.kernels import RBF, Matern, ConstantKernel, \
  WhiteKernel
from sklearn.ensemble import (
  RandomForestRegressor,
  ExtraTreesRegressor,
  GradientBoostingRegressor,
)
from sklearn.neural_network import MLPRegressor
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler, PolynomialFeatures
from .training_spec import digest


class LocalRegressor(RegressorMixin, BaseEstimator):
  def __init__(self, bandwidth=1.0, alpha=0.1):
    self.bandwidth = bandwidth
    self.alpha = alpha

  def fit(self, x, y):
    self.x_ = np.asarray(x)
    self.y_ = np.asarray(y).reshape(len(x), -1)
    return self

  def predict(self, x):
    result = []
    for point in x:
      design = np.column_stack([np.ones(len(self.x_)), self.x_ - point])
      weights = (
          np.exp(
              -np.sum((self.x_ - point) ** 2, axis=1) / (
                    2 * self.bandwidth ** 2)
          )
          + 1e-12
      )
      reg = np.eye(design.shape[1]) * self.alpha
      reg[0, 0] = 1e-12
      coef = np.linalg.solve(
          design.T @ (weights[:, None] * design) + reg,
          design.T @ (weights[:, None] * self.y_),
      )
      result.append(coef[0])
    return np.asarray(result)


class MaternKernel(BaseEstimator):
  def __init__(self, length_scale=1.0, nu=1.5):
    self.length_scale = length_scale
    self.nu = nu

  def __call__(self, x, y):
    d = np.linalg.norm(x - y) / self.length_scale
    if self.nu == 1.5:
      return (1 + np.sqrt(3) * d) * np.exp(-np.sqrt(3) * d)
    return (1 + np.sqrt(5) * d + 5 * d * d / 3) * np.exp(-np.sqrt(5) * d)


@dataclass(frozen=True)
class Registration:
  constructor: object
  native: bool = True


REGISTRY = {}


def register(name, constructor, native=True):
  if name in REGISTRY:
    raise ValueError("duplicate estimator: " + name)
  REGISTRY[name] = Registration(constructor, native)


def plain(cls):
  return lambda p, n, seed: cls(**p)


def seeded(cls):
  return lambda p, n, seed: cls(random_state=seed, **p)


register("mean", plain(DummyRegressor))
register("linear", plain(LinearRegression))
register("ridge", plain(Ridge))
register("elastic_net", seeded(ElasticNet), False)
register("knn", plain(KNeighborsRegressor))
register("local_linear", plain(LocalRegressor))


def kernel(p, n, seed):
  p = dict(p)
  kind = p.pop("kernel", "rbf")
  if kind == "matern":
    p["kernel"] = MaternKernel(p.pop("length_scale", 1.0), p.pop("nu", 1.5))
  else:
    p["kernel"] = kind
  return KernelRidge(**p)


register("kernel_ridge", kernel)


def gp(p, n, seed):
  p = dict(p)
  kind = p.pop("kernel", "rbf")
  ard = p.pop("ard", False)
  length = p.pop("length_scale", 1.0)
  k = (
    RBF(np.full(n, length) if ard else length)
    if kind == "rbf"
    else Matern(np.full(n, length) if ard else length, nu=float(kind))
  )
  return GaussianProcessRegressor(
      kernel=ConstantKernel(1.0) * k, random_state=seed, **p
  )


register("gp", gp)
register("random_forest", seeded(RandomForestRegressor))
register("extra_trees", seeded(ExtraTreesRegressor))
register("gradient_boosting", seeded(GradientBoostingRegressor), False)
register("mlp", seeded(MLPRegressor))


def xgb(p, n, seed):
  from xgboost import XGBRegressor

  p = dict(p);
  p.setdefault('n_jobs', 1)
  return XGBRegressor(random_state=seed, **p)


register("xgboost", xgb)


def cat(p, n, seed):
  from catboost import CatBoostRegressor

  p = dict(p);
  p.setdefault('thread_count', 1)
  return CatBoostRegressor(
      random_seed=seed, verbose=False,
      allow_writing_files=False, **p
  )


register("catboost", cat, False)


def expand(configs):
  result = []
  for c in configs:
    if c.family not in REGISTRY:
      raise ValueError("unknown estimator " + c.family)
    keys = sorted(c.grid)
    for values in product(*(c.grid[k] for k in keys)):
      row = c.model_dump()
      row["params"] = dict(c.params, **dict(zip(keys, values)))
      row["grid"] = {}
      row["id"] = c.id + "-" + digest(row)[:10]
      result.append(row)
  return result


class Model:
  def __init__(self, config, seed, execution=None):
    self.config = config
    self.seed = seed
    self.parts = []
    self.execution = execution or dict(nativeThreadsPerWorker=1,
                                       nativeCuda=False)
    self.actual_backend = 'cpu'

  def inputs(self, x):
    indices = self.config.get('inputIndices')
    return x if indices is None else x[:, indices]

  def fit(self, x, y, weights=None):
    x = self.inputs(x)
    masks = np.isfinite(y)
    reg = REGISTRY[self.config["family"]]
    groups = {}
    for j in range(y.shape[1]):
      valid = masks[:, j]
      if not valid.any():
        continue
      key = (
        valid.tobytes()
        if reg.native and self.config["mode"] == "native"
        else (j, valid.tobytes())
      )
      groups.setdefault(key, []).append(j)
    self.width = y.shape[1]
    for js in groups.values():
      valid = masks[:, js[0]]
      p = dict(self.config["params"])
      native = self.execution['nativeThreadsPerWorker']
      if self.config['family'] in ['random_forest', 'extra_trees', 'knn',
                                   'xgboost']: p['n_jobs'] = native
      if self.config['family'] == 'xgboost':
        p['device'] = 'cuda' if self.execution.get('nativeCuda') else 'cpu'
        self.actual_backend = p['device']
      if self.config['family'] == 'catboost':
        p['thread_count'] = native
        p['task_type'] = 'GPU' if self.execution.get('nativeCuda') else 'CPU'
        self.actual_backend = 'cuda' if p['task_type'] == 'GPU' else 'cpu'
      if self.config["family"] == "knn":
        p["n_neighbors"] = min(p.get("n_neighbors", 5), int(valid.sum()))
      estimator = reg.constructor(p, x.shape[1], self.seed)
      degree = self.config["degree"]
      steps = [StandardScaler()]
      if degree > 1:
        steps += [PolynomialFeatures(degree, include_bias=False)]
      # All scaling is fitted on training data only. Polynomial expansion follows input scaling.
      pipeline = make_pipeline(*steps, estimator)
      target = y[valid][:, js]
      if not reg.native or len(js) == 1:
        target = target[:, 0]
      kw = {}
      if weights is not None and not np.allclose(weights[valid], 1):
        import inspect

        if "sample_weight" not in inspect.signature(estimator.fit).parameters:
          raise ValueError(
              "sample weights unsupported by " + self.config["family"]
          )
        kw[pipeline.steps[-1][0] + "__sample_weight"] = weights[valid]
      with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        pipeline.fit(x[valid], target, **kw)
      self.parts.append(
          (js, pipeline, sorted(set(str(w.message) for w in caught)))
      )
    return self

  def predict(self, x):
    x = self.inputs(x)
    out = np.full((len(x), self.width), np.nan)
    for js, pipeline, _ in self.parts:
      values = np.asarray(pipeline.predict(x)).reshape(len(x), len(js))
      out[:, js] = values
    return out
