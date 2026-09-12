import json
from copy import deepcopy
from pathlib import Path
import numpy as np
import pytest
from scipy.stats import qmc
from pareto_weight_calibration.surrogate_models import Model, expand
from pareto_weight_calibration.surrogate_spec import ModelConfig, SurrogateTask, \
  DenseInference
from pareto_weight_calibration.bulk_predict import predict_columns
from pareto_weight_calibration.dense_search import map_matrix, memory_plan, \
  counterfactual, support_mask
from pareto_weight_calibration.parameter_tuning import map_parameters, construct
from pareto_weight_calibration.historical_response import compatible_function
from pareto_weight_calibration.reliable_proposals import nondominated
from pareto_weight_calibration.cache_timing import evaluate



@pytest.mark.parametrize('family,params', [('local_linear', {'alpha': .1}),
                                           ('kernel_ridge',
                                            {'kernel': 'matern', 'nu': 1.5}),
                                           ('kernel_ridge',
                                            {'kernel': 'matern', 'nu': 2.5}),
                                           ('ridge', {}), ('random_forest',
                                                           {'n_estimators': 4}),
                                           ('gp', {'normalize_y': True})])
def test_bulk_prediction_matches_fitted_pipeline(family, params):
  rng = np.random.default_rng(2);
  x = rng.random((20, 5));
  y = rng.random((20, 4));
  y[0, 2] = np.nan
  c = expand([ModelConfig(id='m', family=family, params=params)])[0]
  model = Model(c, 4).fit(x, y);
  test = rng.random((23, 5))
  np.testing.assert_allclose(predict_columns(model, test, [0, 2]),
                             model.predict(test)[:, [0, 2]], rtol=1e-9,
                             atol=1e-10)




def test_memory_budget_uses_bulk_when_possible():
  config = DenseInference(memoryBudgetGiB=24)
  assert memory_plan(64, 5, 48, 100, config)['mode'] == 'bulk'
  assert memory_plan(2 ** 24, 5, 48, 100, config)[
           'mode'] == 'memory_budget_chunked'
  assert memory_plan(2 ** 24, 5, 48, 100, config)[
           'estimatedWorkingBytes'] <= 24 * 2 ** 30


def test_fast_exact_pareto_preserves_ties():
  values = np.tile([[1., 0.], [0., 1.], [-1., -1.]], (800, 1))
  assert np.array_equal(nondominated(values),
                        np.flatnonzero(np.arange(len(values)) % 3 != 2))


def test_dense_json_pipeline_determinism_and_counterpart(tmp_path):
  import runpy
  helpers = runpy.run_path(
    str(Path(__file__).with_name("test_surrogate_tournament.py")))
  spec, data = helpers["spec"], helpers["data"]
  from pareto_weight_calibration.surrogate_tournament import run_task
  task = spec(tmp_path, 5).model_dump();
  data(tmp_path, 5)
  (tmp_path / 'center.json').write_text(json.dumps({'coefficients': [.5] * 5}))
  task['dataset']['centerArtifact'] = 'center.json'
  task['proposal'].update(reliability={'enabled': True},
                          denseInference={'enabled': True,
                                          'checkpoints': [4, 6]},
                          counterfactual={'enabled': True, 'values': {'p4': 0}})
  path = tmp_path / 'task.json';
  path.write_text(json.dumps(task))
  run_task(path, tmp_path / 'first');
  run_task(path, tmp_path / 'second')
  a = json.loads((tmp_path / 'first/proposals.json').read_text());
  b = json.loads((tmp_path / 'second/proposals.json').read_text())
  assert a['selected'] == b['selected'] and a['searched'] == 64
  assert len(
      json.loads((tmp_path / 'first/search_convergence.json').read_text())[
        'checkpoints']) == 2
  for p in a['selected']:
    assert p['distanceToMeasured'] >= task['proposal']['minMeasuredDistance']
    assert p['counterfactual']['theta'][:-1] == p['theta'][:-1]
    assert p['counterfactual']['theta'][-1] == 0
  for out in ['first', 'second']:
    scores = np.load(tmp_path / out / 'dense_objectives.npy');
    status = np.load(tmp_path / out / 'dense_status.npy')
    ids = np.flatnonzero(status == 0);
    expected = ids[nondominated(scores[ids])]
    assert np.array_equal(expected,
                          np.load(tmp_path / out / 'pareto_indices_2p6.npy'))


def test_generic_study_selects_tradeoffs_and_preserves_exact_pairs():
  from pareto_weight_calibration.tournament_study import StudySelection, \
    select_candidates
  policy = StudySelection(count=3, primaryTargets=['off:a', 'off:b'],
                          topologyTargets=['off:a', 'off:b'],
                          plentifulTargets=['off:g'])
  families = []
  for family, sign in [('first', 1), ('second', -1)]:
    items = []
    for i in range(3):
      values = [.2 + i * .2, .4, .1 * sign]
      items.append(
        dict(id=f'{family}{i}', theta=values, normalizedCoordinates=values,
             predictions={'off:a': .02 + i * .01, 'off:b': .04 - i * .01,
                          'off:g': -.01 * i},
             counterfactual=dict(theta=values[:-1] + [0.],
                                 marginalPredictions={'off:a': .01,
                                                      'off:b': .01,
                                                      'off:g': 0}),
             contribution={'off:g': 'soft_penalty'}))
    families.append((family, {'selected': items}))
  a, summary = select_candidates(families, policy);
  b, _ = select_candidates(families, policy)
  assert a == b and 2 <= len(a) <= 3
  assert {p['family'] for p in a} == {'first', 'second'}
  for p in a: assert p['counterfactual']['theta'][:-1] == p['theta'][:-1]


def test_study_combiner_preserves_family_basins_and_exploration():
  from pareto_weight_calibration.tournament_study import StudySelection, \
    select_candidates
  policy = StudySelection(count=4, primaryTargets=['off:a'],
                          topologyTargets=['off:a'],
                          plentifulTargets=['off:g'],
                          minimumRoles={'basin_consensus': 1,
                                        'model_disagreement': 1})
  families = []
  for family in ['first', 'second']:
    items = []
    for i in range(4):
      items.append(
        dict(id=f'{family}{i}', theta=[i / 4.], normalizedCoordinates=[i / 4.],
             basin=i % 2,
             role=['basin_consensus', 'broad_scarcity', 'broad_scarcity',
                   'model_disagreement'][i],
             predictions={'off:a': .01 + i * .01, 'off:g': -.01},
             contribution={'off:g': 'soft_penalty'},
             counterfactual=dict(
               marginalPredictions={'off:a': .01, 'off:g': 0})))
    families.append((family, dict(selected=items)))
  chosen, summary = select_candidates(families, policy)
  assert len({(p['family'], p['basin']) for p in chosen}) == 4
  assert any(p['role'] == 'model_disagreement' for p in chosen)
  assert any(p['role'] == 'basin_consensus' for p in chosen)
  assert select_candidates(families, policy)[0] == chosen








def test_study_shared_history_is_not_counted_as_new_replication(tmp_path):
  from pareto_weight_calibration.tournament_study import evidence_summary
  common = dict(rowId='campaign/fork-1', campaignId='campaign')
  control = dict(rowId='campaign/off-1', campaignId='campaign')
  a = dict(rows=[{'theta': [0., 0.]}], forks=[common], controls=[control])
  b = dict(rows=[{'theta': [0., 0.]}],
           forks=[common, dict(rowId='campaign/fork-2', campaignId='campaign')],
           controls=[control])
  report = evidence_summary([('first', a), ('second', b)], tmp_path)
  assert report['uniqueLiveForks'] == 2 and report['uniqueControlForks'] == 1
  assert report['sharedLiveForks'] == 1


def test_collection_compares_same_base_forks_without_using_off(tmp_path):
  from pareto_weight_calibration.cache_timing_confirmation import \
    summarize_pairs
  import csv, math
  records = []
  for policy, means in [('full', [120., 60.]), ('zero', [100., 100.]),
                        ('POLICY_OFF', [80., 80.])]:
    for block, mean in enumerate(means):
      records.append(
        dict(policyId=policy, workloadId='fixture', passId=str(block),
             forkId='1',
             runId=f'{policy}-{block}', executionsPerSecond=mean,
             measurementWindows=[mean - 2, mean + 2],
             fixture=dict(resolvedWorkers=3, workloadClass='PRIMARY',
                          workUnits=96)))
  summarize_pairs(records, [dict(fullPolicyId='full', zeroPolicyId='zero')],
                  tmp_path)
  rows = list(
    csv.DictReader((tmp_path / 'same_base_forks.tsv').open(), delimiter='\t'))
  pooled = list(csv.DictReader((tmp_path / 'same_base_workloads.tsv').open(),
                               delimiter='\t'))
  assert len(rows) == 2 and float(rows[1]['fullThroughput']) == 60
  assert float(pooled[0]['percentChange']) == pytest.approx(-10)
  assert float(pooled[0]['logRatio']) == pytest.approx(math.log(.9))
  assert float(pooled[0]['fullMinimumWindow']) == 58
  classes = list(
    csv.DictReader((tmp_path / 'same_base_classes.tsv').open(), delimiter='\t'))
  assert classes[0]['workloadClass'] == 'PRIMARY'
  assert float(classes[0]['geometricChangePercent']) == pytest.approx(-10)
