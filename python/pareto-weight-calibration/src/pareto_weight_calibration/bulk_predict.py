"""Vectorized offline prediction of only the requested response columns."""
import numpy as np
import time
from scipy.spatial.distance import cdist
from .surrogate_models import LocalRegressor, MaternKernel


def local_predict(model, x, batch=2048):
  # Weighted sufficient statistics turn the per-query training-row contractions
  # into GEMMs. Recenter the moments at each query to preserve the fitted penalty
  # (intercept unpenalized, slopes alpha); do not substitute a global regression.
  origin = model.x_.mean(axis=0)
  train = model.x_ - origin
  phi = np.column_stack([np.ones(len(train)), train])
  width = phi.shape[1]
  products = (phi[:, :, None] * phi[:, None, :]).reshape(len(train), -1)
  targets = (phi[:, :, None] * model.y_[:, None, :]).reshape(len(train), -1)
  reg = np.eye(width) * model.alpha
  reg[0, 0] = 1e-12
  outputs = []
  for start in range(0, len(x), batch):
    points = x[start:start + batch] - origin
    weights = np.exp(-cdist(points, train, 'sqeuclidean') /
                     (2 * model.bandwidth ** 2)) + 1e-12
    lhs = (weights @ products).reshape(len(points), width, width)
    rhs = (weights @ targets).reshape(len(points), width, -1)
    first = lhs[:, 0, 1:].copy()
    mass = lhs[:, 0, 0]
    lhs[:, 1:, 1:] += (mass[:, None, None] * points[:, :, None] * points[:, None, :]
                        - points[:, :, None] * first[:, None, :]
                        - first[:, :, None] * points[:, None, :])
    lhs[:, 0, 1:] = first - mass[:, None] * points
    lhs[:, 1:, 0] = lhs[:, 0, 1:]
    rhs[:, 1:, :] -= points[:, :, None] * rhs[:, :1, :]
    outputs.append(np.linalg.solve(lhs + reg, rhs)[:, 0])
  return np.concatenate(outputs)


def predict_pipeline(pipeline, x, accelerator=None):
  value = accelerator.predict(pipeline, x) if accelerator else None
  if value is not None: return value
  z = x
  for _, transform in pipeline.steps[:-1]:
    z = transform.transform(z)
  estimator = pipeline.steps[-1][1]
  if isinstance(estimator, LocalRegressor):
    return local_predict(estimator, z)
  if isinstance(getattr(estimator, 'kernel', None), MaternKernel):
    kernel = estimator.kernel
    distance = cdist(z, estimator.X_fit_) / kernel.length_scale
    if kernel.nu == 1.5:
      a = np.sqrt(3) * distance
      k = (1 + a) * np.exp(-a)
    else:
      a = np.sqrt(5) * distance
      k = (1 + a + a * a / 3) * np.exp(-a)
    return k @ estimator.dual_coef_
  return estimator.predict(z)


def predict_columns(model, x, columns, accelerator=None):
  """Preserve fitted pipelines; skip unrelated independent estimators and scalar kernels."""
  x = model.inputs(x)
  out = np.full((len(x), len(columns)), np.nan)
  positions = {j: i for i, j in enumerate(columns)}
  for js, pipeline, _ in model.parts:
    active = [j for j in js if j in positions]
    if not active: continue
    value = np.asarray(predict_pipeline(pipeline, x, accelerator)).reshape(len(x), len(js))
    for j in active: out[:, positions[j]] = value[:, js.index(j)]
  return out


class PredictionStack:
  def __init__(self, result, names, tolerance, strong_limit=3, pool=None,
      execution=None):
    from .execution_resources import ExecutionPool, ExecutionConfig
    from .cuda_predict import CudaPredictor
    self.owns_pool = pool is None
    self.pool = pool or ExecutionPool(execution or ExecutionConfig())
    self.accelerator = CudaPredictor(self.pool)
    self.choices = result['choice']
    self.names = names
    self.members = {}
    self.requests = {}
    for j, choice in enumerate(self.choices):
      if choice is None:
        raise ValueError(
          'dense search requires a fitted selection for each response')
      members = list(choice['selected']['members'])
      floor = max(choice['ranked'][0]['metrics']['rmse'] * tolerance, 1e-12)
      strong = [r['model'] for r in choice['ranked'] if
                r['metrics']['rmse'] <= floor]
      members = sorted(set(members + strong[:strong_limit]))
      self.members[names[j]] = members
      for m in members:
        self.requests.setdefault(m, []).append(j)
    self.models = {m: result['models'][m] for m in self.requests}
    self.timings = {}
    self.closed = False

  def batch_plan(self, count, maximum_rows):
    """Separate host worker temporaries from the bounded CUDA concurrency budget."""
    cpu, gpu = [], []
    for name, columns in self.requests.items():
      for js, pipeline, _ in self.models[name].parts:
        if not set(js).intersection(columns): continue
        estimator = pipeline.steps[-1][1]
        width = max(1, getattr(pipeline, 'n_features_in_', 1))
        for _, transform in pipeline.steps[:-1]:
          width = getattr(transform, 'n_output_features_', width)
        train = max(len(getattr(estimator, attr, [])) for attr in ['X_fit_', 'X_train_', 'x_', '_fit_X'])
        temporary = 8 * (width * 8 + len(js) * 4 + max(256, train * 4))
        if isinstance(estimator, LocalRegressor): temporary += 8 * (width + 1) ** 2 * 8
        # CPU allowance includes fallback. GPU allowance uses actual GPU slots,
        # never predictWorkers copies of a cloud serialized through one GPU slot.
        cpu.append(temporary)
        if self.accelerator.available: gpu.append(temporary)
    retained = 8 * (len(self.names) * 8 + sum(map(len, self.requests.values())) * 4)
    cpu_per_row = retained + sum(sorted(cpu, reverse=True)[:self.pool.resources['predictWorkers']])
    gpu_per_row = sum(sorted(gpu, reverse=True)[:self.pool.resources['gpuConcurrency']])
    cpu_budget = self.pool.resources['memoryBudgetBytes'] * .5
    gpu_budget = self.pool.config.gpuMemoryBudgetGiB * 2 ** 30 * .5
    rows = min(count, maximum_rows, max(1, int(cpu_budget / max(1, cpu_per_row))))
    if gpu_per_row: rows = min(rows, max(1, int(gpu_budget / gpu_per_row)))
    rows = 2 ** (int(rows).bit_length() - 1)
    return dict(batchRows=rows, estimatedWorkingBytes=rows * cpu_per_row,
                estimatedGpuWorkingBytes=rows * gpu_per_row)

  def predict(self, x, detailed=False):
    if self.closed: raise RuntimeError('prediction stack is closed')
    prediction = np.zeros((len(x), len(self.names)))
    total = np.zeros_like(prediction)
    squares = np.zeros_like(prediction)
    raw = {}
    accelerator = self.accelerator if (self.pool.config.device == 'cuda' or len(
      x) >= self.pool.config.cudaMinPredictRows) else None
    self.accelerator.begin_batch()
    # Requests are deduplicated by fitted-model identity in this stack, not by a cross-fit config cache.
    items = list(self.requests.items())
    jobs = []
    # Independent output fits are independent jobs too. A native multi-output fit
    # is still evaluated once, even when several selected/ensemble consumers use it.
    for name, columns in items:
      model = self.models[name]
      model_x = model.inputs(x)
      for part, (js, pipeline, _) in enumerate(model.parts):
        active = [j for j in js if j in columns]
        if active: jobs.append((name, part, js, active, pipeline, model_x))

    def evaluate(job):
      name, part, js, active, pipeline, model_x = job
      started = time.perf_counter()
      values = np.asarray(predict_pipeline(pipeline, model_x, accelerator)).reshape(len(x), len(js))
      return values[:, [js.index(j) for j in active]], time.perf_counter() - started

    workers = self.pool.resources['predictWorkers']
    try:
      results = self.pool.map_predict(evaluate, jobs, workers)
    finally:
      self.accelerator.end_batch()
    values_by_name = {name: np.full((len(x), len(columns)), np.nan) for name, columns in items}
    # Only the owner merges metrics and caches. Worker completion order cannot
    # change ensemble summation order or requested-column deduplication.
    for job, (values, seconds) in zip(jobs, results):
      name, part, js, active, pipeline, _ = job
      positions = [self.requests[name].index(j) for j in active]
      values_by_name[name][:, positions] = values
      key = f'{name}/part-{part}'
      metric = self.timings.setdefault(key, dict(model=name, estimator=type(pipeline.steps[-1][1]).__name__,
                                                calls=0, rows=0, seconds=0.))
      metric['calls'] += 1
      metric['rows'] += len(x)
      metric['seconds'] += seconds
    values_by_model = [values_by_name[name] for name, _ in items]
    for (name, columns), values in zip(items, values_by_model):
      if detailed:
        full = np.full_like(prediction, np.nan)
        full[:, columns] = values
        raw[name] = full
      for k, j in enumerate(columns):
        c = self.choices[j]['selected']
        if name in c['members']:
          prediction[:, j] += c['weights'][c['members'].index(name)] * values[
            :, k]
        total[:, j] += values[:, k]
        squares[:, j] += values[:, k] ** 2
    count = np.array([len(self.members[n]) for n in self.names])
    disagreement = np.sqrt(
      np.maximum(0, squares / count - (total / count) ** 2))
    return prediction, disagreement, raw

  def report(self):
    return dict(resources=self.pool.resources,
                cudaAvailable=self.accelerator.available,
                backends=list(self.accelerator.backends.values()),
                fallbacks=list(self.accelerator.disabled.values()),
                fallback=self.accelerator.fallback,
                predictionTimings=[dict(row) for row in self.timings.values()])

  def close(self):
    if self.closed: return
    self.closed = True
    self.models.clear()
    self.accelerator.close()
    if self.owns_pool: self.pool.close()
