"""CUDA evaluation of fitted estimators, with explicit per-estimator CPU fallback."""
import numpy as np


class CudaPredictor:
  def __init__(self, pool):
    self.pool = pool;
    self.cache = {};
    self.disabled = {};
    self.backends = {};
    self.inputs = {}
    self.batch_active = False
    self.closed = False
    self.available = False
    if pool.resources['nativeCuda']:
      try:
        import torch
        self.torch = torch;
        self.available = torch.cuda.is_available()
      except ImportError:
        pass
    self.fallback = None if self.available else 'CUDA tensor backend unavailable; parallel CPU prediction'
    # Every adapter belongs to the pool, including direct predict_columns callers.
    pool.cleanups.append(self.close)

  def tensor(self, value):
    return self.torch.as_tensor(np.asarray(value), dtype=self.torch.float64,
                                device='cuda')

  def kernel(self, k, x, y):
    t = self.torch;
    kind = type(k).__name__
    if kind == 'Product': return self.kernel(k.k1, x, y) * self.kernel(k.k2, x,
                                                                       y)
    if kind == 'Sum': return self.kernel(k.k1, x, y) + self.kernel(k.k2, x, y)
    if kind == 'ConstantKernel': return k.constant_value
    if kind == 'WhiteKernel': return 0.
    scale = self.tensor(k.length_scale)
    distance = t.cdist(x / scale, y / scale)
    if kind == 'RBF': return t.exp(-.5 * distance ** 2)
    if kind in ['Matern', 'MaternKernel']:
      if k.nu == .5: return t.exp(-distance)
      if k.nu == 1.5:
        a = 3 ** .5 * distance;
        return (1 + a) * t.exp(-a)
      if k.nu == 2.5:
        a = 5 ** .5 * distance;
        return (1 + a + a * a / 3) * t.exp(-a)
    raise NotImplementedError('unsupported fitted kernel ' + kind)

  def predict(self, pipeline, x):
    if self.closed: raise RuntimeError('CUDA predictor is closed')
    if not self.available: return None
    estimator = pipeline.steps[-1][1];
    key = id(pipeline)
    if key in self.disabled: return None
    supported = ['DummyRegressor', 'LinearRegression', 'Ridge', 'ElasticNet',
                 'MLPRegressor', 'KernelRidge', 'GaussianProcessRegressor',
                 'XGBRegressor', 'CatBoostRegressor', 'LocalRegressor']
    if type(estimator).__name__ not in supported:
      self.backends[key] = 'cpu_native:' + type(estimator).__name__;
      return None
    with self.pool.gpu_slots:
      try:
        # Cache by fitted object identity, never model/config ID. Input tensors are batch-local.
        entry = self.cache.setdefault(key, dict(pipeline=pipeline, objects={}))

        def tensor(name, value):
          if name not in entry['objects']: entry['objects'][name] = self.tensor(
            value)
          return entry['objects'][name]

        if self.batch_active:
          if id(x) not in self.inputs: self.inputs[id(x)] = (x, self.tensor(x))
          z = self.inputs[id(x)][1]
        else:
          # Unbatched callers must not retain every candidate cloud until close.
          z = self.tensor(x)
        t = self.torch
        for index, (_, transform) in enumerate(pipeline.steps[:-1]):
          kind = type(transform).__name__
          if kind == 'StandardScaler':
            if transform.with_mean: z = z - tensor(f'mean{index}',
                                                   transform.mean_)
            if transform.with_std: z = z / tensor(f'scale{index}',
                                                  transform.scale_)
          elif kind == 'PolynomialFeatures':
            # Preserve fitted term order and multiplication order, but launch one
            # matrix operation per degree instead of kernels for every monomial.
            key_indices = f'polynomial_indices{index}'
            if key_indices not in entry['objects']:
              powers = transform.powers_
              degree = int(powers.sum(axis=1).max())
              indices = np.full((len(powers), degree), z.shape[1], dtype=np.int64)
              for row, term in enumerate(powers):
                columns = np.repeat(np.arange(len(term)), term)
                indices[row, :len(columns)] = columns
              entry['objects'][key_indices] = t.as_tensor(indices, device='cuda')
            indices = entry['objects'][key_indices]
            padded = t.cat([z, t.ones((len(z), 1), device='cuda', dtype=t.float64)], dim=1)
            expanded = t.ones((len(z), len(indices)), device='cuda', dtype=t.float64)
            for column in range(indices.shape[1]):
              expanded *= padded.index_select(1, indices[:, column])
            z = expanded
          else:
            raise NotImplementedError('unsupported transform ' + kind)
        kind = type(estimator).__name__;
        backend = 'torch_cuda_float64'
        if kind == 'DummyRegressor':
          v = tensor('constant', estimator.constant_).expand(len(z), -1)
        elif kind in ['LinearRegression', 'Ridge', 'ElasticNet']:
          coef = np.asarray(estimator.coef_).reshape(-1, z.shape[1]);
          v = z @ tensor('coef', coef).T + tensor('intercept',
                                                  estimator.intercept_)
        elif kind == 'MLPRegressor':
          v = z
          for j, (a, b) in enumerate(
              zip(estimator.coefs_, estimator.intercepts_)):
            v = v @ tensor(f'coef{j}', a) + tensor(f'intercept{j}', b)
            activation = estimator.activation if j < len(
              estimator.coefs_) - 1 else estimator.out_activation_
            if activation == 'relu':
              v = t.relu(v)
            elif activation == 'tanh':
              v = t.tanh(v)
            elif activation == 'logistic':
              v = t.sigmoid(v)
            elif activation != 'identity':
              raise NotImplementedError('MLP activation ' + activation)
        elif kind == 'LocalRegressor':
          origin = tensor('origin', estimator.x_.mean(axis=0))
          if 'products' not in entry['objects']:
            train = tensor('train', estimator.x_) - origin
            entry['objects']['centered_train'] = train
            phi = t.cat([t.ones((len(train), 1), device='cuda', dtype=t.float64), train], dim=1)
            entry['objects']['products'] = (phi[:, :, None] * phi[:, None, :]).reshape(len(train), -1)
            entry['objects']['targets'] = (phi[:, :, None] * tensor('target', estimator.y_)[:, None, :]).reshape(len(train), -1)
          train = entry['objects']['centered_train']
          width = train.shape[1] + 1
          reg = t.eye(width, device='cuda', dtype=t.float64) * estimator.alpha
          reg[0, 0] = 1e-12
          # Bound temporary memory independently of the outer CPU search batch.
          available = self.pool.config.gpuMemoryBudgetGiB * 2 ** 30 * .25 / self.pool.config.gpuConcurrency
          batch = max(1, min(16384, int(available / (8 * (len(train) * 3 + width * width * 8)))))
          chunks = []
          for start in range(0, len(z), batch):
            points = z[start:start + batch] - origin
            weights = t.exp(-t.cdist(points, train) ** 2 / (2 * estimator.bandwidth ** 2)) + 1e-12
            lhs = (weights @ entry['objects']['products']).reshape(len(points), width, width)
            rhs = (weights @ entry['objects']['targets']).reshape(len(points), width, -1)
            first = lhs[:, 0, 1:].clone()
            mass = lhs[:, 0, 0]
            lhs[:, 1:, 1:] += (mass[:, None, None] * points[:, :, None] * points[:, None, :]
                                - points[:, :, None] * first[:, None, :]
                                - first[:, :, None] * points[:, None, :])
            lhs[:, 0, 1:] = first - mass[:, None] * points
            lhs[:, 1:, 0] = lhs[:, 0, 1:]
            rhs[:, 1:, :] -= points[:, :, None] * rhs[:, :1, :]
            chunks.append(t.linalg.solve(lhs + reg, rhs)[:, 0])
          v = t.cat(chunks)
        elif kind == 'KernelRidge':
          train = tensor('train', estimator.X_fit_);
          kernel = estimator.kernel
          if kernel == 'rbf':
            k = t.exp(
              -(estimator.gamma or 1 / z.shape[1]) * t.cdist(z, train) ** 2)
          elif kernel == 'linear':
            k = z @ train.T
          elif type(kernel).__name__ == 'MaternKernel':
            k = self.kernel(kernel, z, train)
          else:
            raise NotImplementedError('kernel ridge kernel')
          v = k @ tensor('dual', estimator.dual_coef_)
        elif kind == 'GaussianProcessRegressor':
          train = tensor('train', estimator.X_train_)
          v = self.kernel(estimator.kernel_, z, train) @ tensor('alpha',
                                                                estimator.alpha_)
          v = v * tensor('ystd', estimator._y_train_std) + tensor('ymean',
                                                                  estimator._y_train_mean)
        elif kind == 'XGBRegressor':
          import xgboost
          if 'booster' not in entry:
            entry['booster'] = estimator.get_booster().copy();
            entry['booster'].set_param({'device': 'cuda',
                                        'nthread': self.pool.resources[
                                          'nativeThreadsPerWorker']})
          result = entry['booster'].predict(xgboost.DMatrix(z.cpu().numpy()))
          backend = 'xgboost_cuda';
          self.backends[key] = backend;
          return result
        else:
          result = estimator.predict(z.cpu().numpy(), task_type='GPU',
                                     thread_count=self.pool.resources[
                                       'nativeThreadsPerWorker'])
          backend = 'catboost_cuda';
          self.backends[key] = backend;
          return result
        result = v.cpu().numpy();
        self.backends[key] = backend;
        return result
      except Exception as error:
        self.cache.pop(key, None)
        self.disabled[key] = type(error).__name__ + ': ' + str(error)
        self.backends[key] = 'cpu_fallback';
        return None

  def begin_batch(self):
    if self.closed: raise RuntimeError('CUDA predictor is closed')
    self.inputs = {}
    self.batch_active = True

  def end_batch(self):
    self.inputs = {}
    self.batch_active = False
    if self.available: self.torch.cuda.synchronize()

  def close(self):
    if self.closed: return
    self.inputs = {};
    self.cache = {}
    self.batch_active = False
    self.closed = True
    if self.available:
      self.torch.cuda.synchronize();
      self.torch.cuda.empty_cache()
