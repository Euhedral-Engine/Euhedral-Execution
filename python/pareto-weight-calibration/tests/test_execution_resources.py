import time
import numpy as np
import pytest
from pareto_weight_calibration.surrogate_models import Model
from pareto_weight_calibration.execution_resources import ExecutionConfig, \
  ExecutionPool
from pareto_weight_calibration.bulk_predict import predict_columns
from pareto_weight_calibration.cuda_predict import CudaPredictor


def square(x): return x * x


def test_process_pool_parity_and_budget():
  with ExecutionPool(
      ExecutionConfig(device='cpu', fitWorkers=2, predictWorkers=2,
                      nativeThreadsPerWorker=1)) as pool:
    assert pool.map_fit(square, range(7)) == [x * x for x in range(7)]
    assert pool.events[-1]['cpuWorkers'] == 2
  assert pool.closed


@pytest.mark.parametrize('family,degree,params', [
  ('linear', 1, {}), ('ridge', 3, {'alpha': 1}),
  ('mlp', 1, {'hidden_layer_sizes': [8], 'max_iter': 30}),
  ('kernel_ridge', 1, {'kernel': 'rbf', 'alpha': 1}),
  ('gp', 1, {'kernel': '1.5'}),
  ('extra_trees', 1, {'n_estimators': 5})])
def test_accelerated_prediction_parity_and_fallback(family, degree, params):
  rng = np.random.default_rng(10);
  x = rng.random((24, 5));
  y = np.column_stack([np.sin(x[:, 0]), x[:, 1] ** 2]);
  cloud = rng.random((100, 5))
  config = dict(id='actual-fit', family=family, degree=degree, mode='native',
                params=params)
  model = Model(config, 10).fit(x, y, None)
  with ExecutionPool(
      ExecutionConfig(device='cuda', fitWorkers=1, predictWorkers=2)) as pool:
    accelerator = CudaPredictor(pool)
    expected = predict_columns(model, cloud, [0, 1]);
    actual = predict_columns(model, cloud, [0, 1], accelerator)
    np.testing.assert_allclose(actual, expected, rtol=1e-7, atol=1e-8)
    if accelerator.available and family != 'extra_trees': assert 'torch_cuda_float64' in accelerator.backends.values()
    accelerator.close()


def test_model_identity_does_not_share_fitted_preprocessing():
  rng = np.random.default_rng(3);
  x = rng.normal(size=(30, 2));
  z = rng.normal(size=(10, 2))
  c = dict(id='same-id', family='ridge', degree=2, mode='native',
           params={'alpha': 1})
  a = Model(c, 1).fit(x, x[:, 0, None], None);
  b = Model(c, 1).fit(x * 3 + 5, x[:, 1, None], None)
  with ExecutionPool(ExecutionConfig(device='cuda', fitWorkers=1)) as pool:
    accelerator = CudaPredictor(pool)
    for model in [a, b]: np.testing.assert_allclose(
      predict_columns(model, z, [0], accelerator), model.predict(z), rtol=1e-8,
      atol=1e-8)
    accelerator.close()


def test_serial_parallel_tournament_fit_and_selection_parity(tmp_path):
  from pareto_weight_calibration.surrogate_spec import SurrogateTask
  from pareto_weight_calibration.surrogate_tournament import Tournament
  rng = np.random.default_rng(17);
  x = rng.random((16, 3));
  y = np.column_stack([np.sin(x[:, 0]), x[:, 2] - x[:, 1]])
  spec = SurrogateTask.model_validate(
    dict(id='parallel', outputDirectory='unused', seed=17,
         parameters=[
           dict(name=f'p{i}', path=f'/w/{i}', center=.5, bounds=[0, 1]) for i in
           range(3)],
         dataset={'discovery': []},
         responses=[dict(name=f'r{i}', workloads=[f'w{i}'], role='scarce') for i
                    in range(2)], systems=['off'],
         models=[dict(id='mean', family='mean'),
                 dict(id='ridge', family='ridge', degree=2,
                      grid={'alpha': [.1, 1]}),
                 dict(id='forest', family='extra_trees',
                      params={'n_estimators': 8})],
         validation=dict(outerFolds=2, innerFolds=2, campaignTransfer=False),
         proposal=dict(enabled=False, seed=17, objectives=[], roles=[])))
  results = []
  for workers in [1, 2]:
    dest = tmp_path / str(workers);
    dest.mkdir()
    actual = spec.model_copy(update={
      'execution': ExecutionConfig(device='cpu', fitWorkers=workers,
                                   predictWorkers=workers)})
    results.append(
      Tournament(actual, x, y, [str(i // 2) for i in range(16)], ['a'] * 16,
                 dest).run())
  np.testing.assert_allclose(results[0]['outerPrediction'],
                             results[1]['outerPrediction'], atol=1e-12)
  assert results[0]['choice'] == results[1]['choice']
  assert not results[0]['failures'] and not results[1]['failures']


@pytest.mark.parametrize('family,params',
                         [('xgboost', {'n_estimators': 4, 'max_depth': 2}),
                          ('catboost', {'iterations': 4, 'depth': 2})])
def test_native_cuda_fit_reports_actual_backend(family, params):
  from pareto_weight_calibration.execution_resources import \
    cuda_driver_available
  from pareto_weight_calibration.surrogate_tournament import fit_job
  if not cuda_driver_available(): pytest.skip('CUDA driver not available')
  x = np.random.default_rng(1).normal(size=(20, 3));
  y = x[:, 0, None]
  config = dict(id='gpu', family=family, degree=1, mode='native', params=params)
  execution = ExecutionConfig(device='cuda', fitWorkers=1).resolved()
  job = (config, x, y, np.arange(16), np.arange(16, 20), None, 1, execution,
         True)
  with ExecutionPool(ExecutionConfig(device='cuda', fitWorkers=1)) as pool:
    result = pool.map_fit(fit_job, [job], lambda _: True)[0]
  assert result['failure'] is None
  assert result['backend'] == 'cuda' or result['fallback']
  assert np.isfinite(result['prediction']).all()


def test_adapter_lifetime_owned_by_pool_even_on_failure():
  with pytest.raises(ValueError, match='failed phase'):
    with ExecutionPool(ExecutionConfig(device='cpu')) as pool:
      adapter = CudaPredictor(pool)
      adapter.cache['temporary'] = object()
      raise ValueError('failed phase')
  assert adapter.closed and not adapter.cache and not adapter.inputs
  adapter.close()
  with pytest.raises(RuntimeError, match='closed'):
    adapter.begin_batch()


def test_failed_prediction_batch_drains_other_workers_before_cleanup():
  import threading
  started = threading.Event()
  finished = threading.Event()

  def predict(index):
    if index == 0:
      assert started.wait(5)
      raise ValueError('synthetic prediction failure')
    started.set()
    time.sleep(.05)
    finished.set()
    return index

  with ExecutionPool(ExecutionConfig(device='cpu', predictWorkers=2)) as pool:
    with pytest.raises(ValueError, match='synthetic prediction failure'):
      pool.map_predict(predict, [0, 1])
    assert finished.is_set()


def test_cuda_input_lifetime_and_phase_release():
  import gc
  torch = pytest.importorskip('torch')
  if not torch.cuda.is_available(): pytest.skip('CUDA unavailable')
  x = np.random.default_rng(1).normal(size=(24, 4))
  model = Model(
    dict(id='memory', family='ridge', degree=1, mode='native', params={}),
    1).fit(x, x[:, 0, None])
  # Warm up the persistent CUDA/cuBLAS context before measuring owned tensors.
  with ExecutionPool(ExecutionConfig(device='cuda', fitWorkers=1)) as pool:
    adapter = CudaPredictor(pool)
    predict_columns(model, x, [0], adapter)
  gc.collect();
  torch.cuda.synchronize();
  torch.cuda.empty_cache()
  baseline = torch.cuda.memory_allocated()
  for _ in range(3):
    with ExecutionPool(ExecutionConfig(device='cuda', fitWorkers=1)) as pool:
      adapter = CudaPredictor(pool)
      allocations = []
      for value in range(4):
        cloud = np.full((65536, 4), value, dtype=float)
        actual = predict_columns(model, cloud, [0], adapter)
        np.testing.assert_allclose(actual, model.predict(cloud), atol=1e-8)
        assert not adapter.inputs
        allocations.append(torch.cuda.memory_allocated())
      assert max(allocations) - min(allocations) < 1024 * 1024
      adapter.begin_batch()
      predict_columns(model, cloud, [0], adapter)
      assert adapter.inputs
      adapter.end_batch()
      assert not adapter.inputs
    assert adapter.closed and not adapter.cache
    assert torch.cuda.memory_allocated() <= baseline + 1024 * 1024
    assert torch.cuda.memory_reserved() <= baseline + 2 * 1024 * 1024
