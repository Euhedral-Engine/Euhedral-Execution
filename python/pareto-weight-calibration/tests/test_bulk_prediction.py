import gc
import threading
import weakref

import numpy as np
import pytest

from pareto_weight_calibration.bulk_predict import PredictionStack, local_predict, predict_columns
from pareto_weight_calibration.execution_resources import ExecutionConfig, ExecutionPool
from pareto_weight_calibration.cuda_predict import CudaPredictor
from pareto_weight_calibration.surrogate_models import LocalRegressor, Model


@pytest.mark.parametrize('dimension,alpha,bandwidth', [(2, .1, 1.), (5, 1., .2), (12, .01, 3.)])
def test_weighted_moments_match_original_local_regression(dimension, alpha, bandwidth):
  rng = np.random.default_rng(71)
  x = rng.normal(size=(120, dimension))
  y = rng.normal(size=(120, 3))
  cloud = rng.normal(size=(41, dimension)) * 2
  model = LocalRegressor(alpha=alpha, bandwidth=bandwidth).fit(x, y)
  np.testing.assert_allclose(local_predict(model, cloud, batch=13), model.predict(cloud),
                             rtol=1e-7, atol=1e-9)


@pytest.mark.parametrize('dimension', [4, 12])
def test_cuda_local_regression_preserves_fitted_pipeline(dimension):
  rng = np.random.default_rng(3)
  x = rng.normal(size=(100, dimension))
  y = np.column_stack([np.sin(x[:, 0]), x[:, 1]])
  cloud = rng.normal(size=(79, dimension))
  model = Model(dict(id='local', family='local_linear', degree=1, mode='native',
                     params={'alpha': .1, 'bandwidth': 1.}), 17).fit(x, y)
  with ExecutionPool(ExecutionConfig(device='cuda', fitWorkers=1)) as pool:
    accelerator = CudaPredictor(pool)
    actual = predict_columns(model, cloud, [0, 1], accelerator)
    np.testing.assert_allclose(actual, model.predict(cloud), rtol=1e-7, atol=1e-8)
    if accelerator.available:
      assert set(accelerator.backends.values()) == {'torch_cuda_float64'}
      assert not accelerator.disabled


def result_for(model, width):
  return dict(models={'model': model}, choice=[dict(
    selected=dict(members=['model'], weights=[1.]),
    ranked=[dict(model='model', metrics=dict(rmse=.1))]) for _ in range(width)])


def test_independent_output_fits_run_concurrently_and_multiconsumers_deduplicate(monkeypatch):
  import pareto_weight_calibration.bulk_predict as bulk
  rng = np.random.default_rng(2)
  x = rng.random((20, 3))
  model = Model(dict(id='model', family='ridge', degree=1, mode='separate', params={}), 1).fit(x, x)
  barrier = threading.Barrier(3)
  seen = []
  original = bulk.predict_pipeline

  def check(pipeline, data, accelerator):
    seen.append(id(pipeline))
    barrier.wait(timeout=5)
    return original(pipeline, data, accelerator)

  monkeypatch.setattr(bulk, 'predict_pipeline', check)
  with ExecutionPool(ExecutionConfig(device='cpu', predictWorkers=3)) as pool:
    stack = PredictionStack(result_for(model, 3), ['a', 'b', 'c'], 1.5, pool=pool)
    actual, spread, raw = stack.predict(x, detailed=True)
    np.testing.assert_allclose(actual, model.predict(x), atol=1e-12)
    assert len(seen) == len(set(seen)) == 3
    assert len(stack.report()['predictionTimings']) == 3
    assert raw.keys() == {'model'}
    stack.close()


def test_native_multioutput_fit_runs_once_and_stack_releases_models(monkeypatch):
  import pareto_weight_calibration.bulk_predict as bulk
  x = np.random.default_rng(1).random((20, 2))
  model = Model(dict(id='model', family='ridge', degree=1, mode='native', params={}), 1).fit(x, x)
  reference = weakref.ref(model)
  result = result_for(model, 2)
  result['models']['unused'] = object()
  with ExecutionPool(ExecutionConfig(device='cpu', predictWorkers=2)) as pool:
    stack = PredictionStack(result, ['a', 'b'], 1.5, pool=pool)
    del result, model
    assert set(stack.models) == {'model'}
    predicted, _, _ = stack.predict(x)
    assert len(stack.timings) == 1
    snapshot = stack.report()
    stack.predict(x)
    assert snapshot['predictionTimings'][0]['calls'] == 1
    assert stack.report()['predictionTimings'][0]['calls'] == 2
    np.testing.assert_allclose(predicted, reference().predict(x), atol=1e-12)
    stack.close()
    gc.collect()
    assert reference() is None
    stack.close()
    with pytest.raises(RuntimeError, match='closed'): stack.predict(x)


def test_cpu_gpu_memory_budgets_are_independent():
  x = np.random.default_rng(2).random((100, 4))
  model = Model(dict(id='model', family='local_linear', degree=1, mode='native', params={}), 1).fit(x, x)
  with ExecutionPool(ExecutionConfig(device='cpu', predictWorkers=8,
                                     memoryBudgetGiB=1, gpuMemoryBudgetGiB=.001)) as pool:
    stack = PredictionStack(result_for(model, 4), list('abcd'), 1.5, pool=pool)
    cpu_plan = stack.batch_plan(2**20, 2**18)
    stack.accelerator.available = True
    gpu_plan = stack.batch_plan(2**20, 2**18)
    stack.accelerator.available = False
    assert gpu_plan['batchRows'] < cpu_plan['batchRows']
    assert gpu_plan['estimatedGpuWorkingBytes'] <= .001 * 2**30 * .5
    assert cpu_plan['estimatedWorkingBytes'] <= 2**30 * .5
    assert not cpu_plan['estimatedGpuWorkingBytes']
    stack.close()
