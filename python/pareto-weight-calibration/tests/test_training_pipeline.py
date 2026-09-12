"""Mechanical checks; no production training or candidate selection."""
from copy import deepcopy
import json
from pathlib import Path
import subprocess

import numpy as np
import pytest

from pareto_weight_calibration.training_spec import TrainingTaskSpec, Basis, \
  canonical
from pareto_weight_calibration.feature_schema import FeatureSchema, \
  participation_basis
from pareto_weight_calibration.training_data import ArmCache, compatible_configs
from pareto_weight_calibration.task_adapters import load_dataset, \
  training_weights
from pareto_weight_calibration.training_runner import FitCache, run, split_plan
from pareto_weight_calibration.training_models import ArrayModel
from pareto_weight_calibration.runtime_export import predict, render_java, \
  timing_index, evaluator

PACKAGE = Path(__file__).resolve().parents[1]
ROOT = PACKAGE.parents[1]


def timing_task(tmp_path, *, controls=2, targets=1):
  spec = json.loads(
    (PACKAGE / 'tasks/cache-timing-fixed-surface.json').read_text())
  manifest = json.loads(
    (PACKAGE / 'datasets/cache-timing-synthetic.json').read_text())
  if controls == 3:
    spec['export']['outputNames'].append('coefficient')
    spec['controls'].append(
      dict(name='coefficient', source='treatment.coefficient',
           configPath='/calibrationConfig/coefficient'))
    for arm in manifest['arms']:
      arm['record']['treatment']['coefficient'] = .2
      arm['record']['config']['calibrationConfig']['coefficient'] = .2
  if targets == 2:
    spec['targets'].append(
      dict(name='second', source='outcome.second', missing='mask'))
    for i, arm in enumerate(manifest['arms']):
      arm['record']['outcome']['second'] = None if i % 3 == 0 else i + 1
    spec['candidates'][0]['family'] = 'independent_ridge'
  spec['dataset']['manifest'] = 'manifest.json'
  (tmp_path / 'manifest.json').write_text(json.dumps(manifest))
  return TrainingTaskSpec.model_validate(spec)


@pytest.mark.parametrize('width', [3, 5, 9])
def test_named_recipe_reordering_and_fold_scaling(width):
  names = [f'f{i}' for i in range(width)]
  basis = Basis(
    terms=[dict(name='pair', expression=dict(op='product', args=['f0', 'f2']))])
  x = np.arange(6 * width, dtype=float).reshape(6, width)
  schema = FeatureSchema(names, basis).fit(x[:3])
  np.testing.assert_array_equal(schema.scaler.mean_, x[:3].mean(axis=0))
  order = list(reversed(range(width)))
  reordered = FeatureSchema([names[i] for i in order], basis).fit(x[:3, order])
  np.testing.assert_allclose(schema.transform(x)[:, -1],
                             reordered.transform(x[:, order])[:, -1])


def test_frozen_adapter_and_fixed_v2_candidate_parity():
  from pareto_weight_calibration.model_tournament import load_frozen_dataset
  from pareto_weight_calibration.tournament_models import runtime_matrix
  from pareto_weight_calibration.tournament_v2_models import LogisticClassifier, \
    transformed_training_weights
  spec = TrainingTaskSpec.load(PACKAGE / 'tasks/participation-binary.json')
  data = load_dataset(spec, PACKAGE / 'tasks')
  rows, _ = load_frozen_dataset(ROOT)
  assert data.row_ids == tuple(r.pair_id for r in rows)
  np.testing.assert_array_equal(data.X, runtime_matrix(rows))
  np.testing.assert_array_equal(data.Y[:, 0],
                                [int(r.observed_action == 'CACHE') for r in
                                 rows])
  for transform in ('raw', 'sqrt', 'log', 'mixed'):
    for a, b in zip(training_weights(spec, data, transform),
                    transformed_training_weights(rows, transform)):
      np.testing.assert_array_equal(a, b)
  for geometry in ('main', 'interactions', 'quadratic'):
    candidate = spec.candidates[0].model_copy(
      update={'basis': participation_basis(geometry)})
    fit = FitCache().fit(spec, candidate, data.subset(np.arange(12)), 'cpu')
    legacy = LogisticClassifier('cpu').fit(rows[:12], dict(id='fixture',
                                                           modelFamily='logistic',
                                                           params=dict(C=1.,
                                                                       penalty='l2',
                                                                       geometry=geometry),
                                                           weightTransform='raw',
                                                           thresholdPolicy=dict(
                                                             values=[.5])))
    np.testing.assert_allclose(fit.predict(data)[:, 1],
                               legacy.predict_probability(rows), atol=1e-12)
    np.testing.assert_allclose(predict(evaluator(spec, fit, .5), data.X),
                               fit.predict(data), atol=1e-12)


@pytest.mark.parametrize('controls,targets', [(2, 1), (3, 1), (2, 2)])
def test_shared_runner_controls_are_not_outputs(tmp_path, controls, targets):
  spec = timing_task(tmp_path, controls=controls, targets=targets)
  data = load_dataset(spec, tmp_path)
  assert data.X.shape[1] == 3 + controls
  assert data.Y.shape[1] == targets
  assert data.U.shape[1] == controls
  assert len(run(spec, data, 'cpu', True)['controlNames']) == controls
  result = run(spec, data, 'cpu')
  for train, held in split_plan(data, 3, 17):
    assert set(data.groups[i] for i in train).isdisjoint(
        data.groups[i] for i in held)
  assert canonical(result) == canonical(run(spec, data, 'cpu'))
  assert all(0 < fold['metrics']['normalizedThroughput'] <= 1 for fold in
             result['outer'])
  assert 0 <= timing_index(result['evaluator'], [[2., 1., 0.]])[0] < len(data.U)


def test_duplicate_arms_masks_and_cache(tmp_path):
  spec = timing_task(tmp_path, targets=2)
  path = tmp_path / 'manifest.json'
  manifest = json.loads(path.read_text())
  manifest['arms'].append(deepcopy(manifest['arms'][0]))
  path.write_text(json.dumps(manifest))
  cache = ArmCache()
  data = load_dataset(spec, tmp_path, cache)
  assert len(data.row_ids) == cache.parse_count == 36
  load_dataset(spec, tmp_path, cache)
  assert cache.parse_count == 36
  assert not data.mask[:, 1].all()
  manifest['arms'][-1]['record']['outcome']['executionsPerSecond'] += 1
  path.write_text(json.dumps(manifest))
  with pytest.raises(ValueError, match='conflicting duplicate'):
    load_dataset(spec, tmp_path)


def test_exact_compatibility_paths():
  a = {'calibrationConfig': {'cacheParkNs': 1, 'contentionHalfLifeNanos': 2,
                             'weights': [1, 2]}, 'model': 'a'}
  b = deepcopy(a)
  b['calibrationConfig']['cacheParkNs'] = 5
  assert compatible_configs(a, b, ['/calibrationConfig/cacheParkNs'])
  assert not compatible_configs(a, b,
                                ['/calibrationConfig/contentionHalfLifeNanos'])
  b['model'] = 'b'
  assert not compatible_configs(a, b, ['/calibrationConfig/cacheParkNs'])
  with pytest.raises(ValueError, match='scalar'):
    compatible_configs(a, b, ['/calibrationConfig'])


def test_arbitrary_labels_one_class_and_capabilities(tmp_path):
  payload = timing_task(tmp_path).model_dump(mode='json')
  payload.update(kind='classification', labels=['slow', 'middle', 'fast'],
                 controls=[],
                 targets=[dict(name='label', source='outcome.label')],
                 objective=dict(adapter='supported_wrong_action_regret'),
                 candidates=[dict(id='logistic', family='logistic')],
                 export=dict(kind='linear'))
  spec = TrainingTaskSpec.model_validate(payload)
  for classes in ([2] * 6, [0, 1, 2, 0, 1, 2]):
    model = ArrayModel(spec, spec.candidates[0], 'cpu').fit(
      np.arange(18).reshape(6, 3),
      np.array(classes)[:, None], np.ones((6, 1), bool), np.ones(6),
      ['a', 'b', 'c'])
    probabilities = model.predict(np.ones((2, 3)))
    assert probabilities.shape == (2, 3)
    np.testing.assert_allclose(probabilities.sum(axis=1), 1)
  with pytest.raises(ValueError, match='CUDA'):
    ArrayModel(spec, spec.candidates[0], 'cuda')
  with pytest.raises(ValueError, match='monotonic'):
    ArrayModel(spec, spec.candidates[0].model_copy(
      update={'monotonic': {'workers': 1}}), 'cpu')
  regression = timing_task(tmp_path, targets=2)
  with pytest.raises(ValueError, match='multioutput'):
    ArrayModel(regression, regression.candidates[0].model_copy(
      update={'family': 'hist_boost'}), 'cpu')


def test_model_cache_keys(tmp_path):
  spec = timing_task(tmp_path)
  data = load_dataset(spec, tmp_path)
  cache = FitCache()
  candidate = spec.candidates[0].model_copy(update={'grid': {}})
  fit = cache.fit(spec, candidate, data, 'cpu')
  assert cache.fit(spec, candidate.model_copy(update={'thresholds': (.2, .8)}),
                   data, 'cpu') is fit
  changed = spec.model_copy(
    update={'validation': spec.validation.model_copy(update={'seed': 19})})
  assert cache.fit(changed, candidate, data, 'cpu').cache_key != fit.cache_key


def test_java_python_timing_parity(tmp_path):
  spec = timing_task(tmp_path)
  artifact = run(spec, load_dataset(spec, tmp_path), 'cpu')['evaluator']
  (tmp_path / 'TaskEvaluator.java').write_text(render_java(artifact))
  (tmp_path / 'Check.java').write_text('''public class Check { public static void main(String[] args) {
        System.out.println(TaskEvaluator.output0(2, 1, 0, 15000, 1000000));
        System.out.println(TaskEvaluator.timingIndex(2, 1, 0));
    }}''')
  subprocess.run(
      ['mise', 'exec', '--', 'javac', str(tmp_path / 'TaskEvaluator.java'),
       str(tmp_path / 'Check.java')], cwd=ROOT, check=True, capture_output=True)
  output = subprocess.check_output(
      ['mise', 'exec', '--', 'java', '-cp', str(tmp_path), 'Check'], cwd=ROOT,
      text=True).splitlines()
  np.testing.assert_allclose(float(output[0]),
                             predict(artifact, [[2, 1, 0, 15000, 1000000]])[
                               0, 0], rtol=1e-12)
  assert int(output[1]) == timing_index(artifact, [[2, 1, 0]])[0]


def test_schema_rejects_executable_expressions(tmp_path):
  spec = timing_task(tmp_path).model_dump(mode='json')
  spec['features'][0]['transform'] = '__import__("os")'
  with pytest.raises(ValueError):
    TrainingTaskSpec.model_validate(spec)


@pytest.mark.parametrize('controls,targets', [(2, 1), (3, 2)])
def test_cuda_cpu_masked_regression_parity(tmp_path, controls, targets):
  import torch
  if not torch.cuda.is_available():
    pytest.skip('CUDA unavailable')
  spec = timing_task(tmp_path, controls=controls, targets=targets)
  data = load_dataset(spec, tmp_path)
  candidate = spec.candidates[0].model_copy(
    update={'params': {'alpha': .1}, 'grid': {}})
  cpu = FitCache().fit(spec, candidate, data, 'cpu')
  cuda = FitCache().fit(spec, candidate, data, 'cuda')
  assert cuda.model.device == 'cuda'
  np.testing.assert_allclose(cpu.predict(data), cuda.predict(data), rtol=1e-10,
                             atol=1e-10)


def test_participation_task_uses_shared_runner_on_synthetic_evidence(tmp_path):
  import hashlib
  from .test_direct_side import _row
  payload = {'rows': [_row(f'{f}-{k}', f'f{f}', k=k,
                           action='CACHE' if k > 3 else 'DEFAULT').to_dict()
                      for f in range(6) for k in (2, 3, 4, 5)]}
  content = json.dumps(payload)
  (tmp_path / 'data.json').write_text(content)
  (tmp_path / 'manifest.json').write_text(
    json.dumps(dict(schemaVersion=1, dataset='data.json',
                    hashes={'data.json': hashlib.sha256(
                      content.encode()).hexdigest()})))
  task = json.loads((PACKAGE / 'tasks/participation-binary.json').read_text())
  task['dataset'].update(manifest='manifest.json', inventory={})
  task['validation'].update(outerFolds=3, innerFolds=2)
  spec = TrainingTaskSpec.model_validate(task)
  data = load_dataset(spec, tmp_path)
  result = run(spec, data, 'cpu')
  assert len(result['outer']) == 3
  assert all(
      'supportedRelativeRegret' in fold['metrics'] for fold in result['outer'])


def test_half_life_provenance_and_legacy_compatibility(tmp_path):
  from pareto_weight_calibration.config import load_trial_config, \
    CompatibilityAnalyzer
  from .conftest import generate_mock_trial_config
  a, b = generate_mock_trial_config(k_cutoff=8,
                                    cpu_count=8), generate_mock_trial_config(
    k_cutoff=7, cpu_count=8)
  b['calibrationConfig']['contentionHalfLifeNanos'] = 2000000
  (tmp_path / 'a.json').write_text(json.dumps(a))
  (tmp_path / 'b.json').write_text(json.dumps(b))
  first, second = load_trial_config(tmp_path / 'a.json'), load_trial_config(
    tmp_path / 'b.json')
  assert first.calibration_config.contention_half_life_nanos == 1000000
  assert not first.calibration_config.contention_half_life_explicit
  assert second.calibration_config.contention_half_life_explicit
  assert 'contentionHalfLifeNanos' not in first.raw_json['calibrationConfig']
  assert any('contentionHalfLifeNanos' in reason for reason in
             CompatibilityAnalyzer.check_compatibility(first, second, 8)[1])


def test_native_two_output_regression_and_missing_action_cost(tmp_path):
  from dataclasses import replace
  from pareto_weight_calibration.task_adapters import metrics
  spec = timing_task(tmp_path, targets=2)
  data = load_dataset(spec, tmp_path)
  complete = replace(data, Y=np.nan_to_num(data.Y),
                     mask=np.ones_like(data.mask))
  candidate = spec.candidates[0].model_copy(
    update={'family': 'ridge', 'params': {'alpha': 1}, 'grid': {}})
  fitted = FitCache().fit(spec, candidate, complete, 'cpu')
  assert fitted.predict(complete).shape == (len(data.row_ids), 2)
  with pytest.raises(ValueError, match='different output masks'):
    FitCache().fit(spec, candidate, data, 'cpu')
  payload = spec.model_dump(mode='json')
  payload.update(kind='classification', controls=[],
                 targets=[dict(name='action', source='outcome.action')],
                 labels=['a', 'b'],
                 objective=dict(adapter='supported_wrong_action_regret'),
                 candidates=[dict(id='lr', family='logistic')],
                 export=dict(kind='none'))
  categorical = TrainingTaskSpec.model_validate(payload)
  costs = np.zeros((len(data.row_ids), 2))
  valid = np.ones_like(costs, bool)
  valid[0, 1] = False
  classification = replace(complete, Y=np.zeros((len(data.row_ids), 1)),
                           mask=np.ones((len(data.row_ids), 1), bool),
                           U=None, C=costs, cost_mask=valid)
  with pytest.raises(ValueError, match='no valid observed cost'):
    metrics(categorical, classification,
            np.tile([0., 1.], (len(data.row_ids), 1)))


def test_export_cannot_round_into_an_untested_control_tuple(tmp_path):
  spec = timing_task(tmp_path, controls=3)
  spec = spec.model_copy(
    update={'export': spec.export.model_copy(update={'rounding': 'nearest'})})
  data = load_dataset(spec, tmp_path)
  fit = FitCache().fit(spec, spec.candidates[0].model_copy(update={'grid': {}}),
                       data, 'cpu')
  with pytest.raises(ValueError, match='unmeasured control tuple'):
    evaluator(spec, fit, .5, data.U)


def test_selection_provenance_and_digest_validation(tmp_path):
  spec = timing_task(tmp_path)
  manifest = json.loads((tmp_path / 'manifest.json').read_text())
  row = manifest['arms'][0]['record']
  cache = ArmCache()
  with pytest.raises(ValueError, match='selection'):
    cache.read(json.dumps(row).encode(), {'window': 'retained'})
  row['provenance']['selection'] = {'window': 'retained'}
  content = json.dumps(row).encode()
  cache.read(content, {'window': 'retained'})
  cache.read(content, {'window': 'retained'})
  assert cache.parse_count == 1
  (tmp_path / 'arm.json').write_bytes(content)
  (tmp_path / 'manifest.json').write_text(json.dumps(dict(schemaVersion=1,
                                                          arms=[dict(
                                                            path='arm.json',
                                                            sha256='0' * 64)])))
  with pytest.raises(ValueError, match='digest mismatch'):
    load_dataset(spec, tmp_path)
