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

TASKS = Path(__file__).resolve().parents[1] / 'tasks/live25-joint5d-v2'


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


@pytest.mark.parametrize('family', ['park-body', 'park-phr-body'])
def test_counterfactual_and_bulk_support_are_exact(family):
  task = SurrogateTask.model_validate_json(
    (TASKS / (family + '-fit.json')).read_text());
  anchor = json.loads((TASKS / 'inputs/anchor-7931.json').read_text())
  d = task.model_dump();
  d['proposal']['counterfactual'] = {'enabled': True,
                                     'values': {task.parameters[-1].name: 0.0}};
  task = SurrogateTask.model_validate(d)
  u = qmc.Sobol(5, seed=31).random_base2(5);
  values = map_matrix(task.parameters, u)
  for unit, row in zip(u, values): assert row.tolist() == map_parameters(
    task.parameters, unit)
  bad = support_mask(task, anchor, values)
  for row, rejected in zip(values, bad):
    zero, cfg = counterfactual(task, anchor, row);
    assert zero[:-1] == row[:-1].tolist() and zero[-1] == 0
    assert cfg == construct(task.parameters, anchor, zero)
    original = construct(task.parameters, anchor, row)
    p = task.parameters[-1];
    field, index = p.path.strip('/').split('/');
    original[field][int(index)] = anchor[field][int(index)]
    assert cfg == original
    actual = construct(task.parameters, anchor, row)
    outputs = np.array([evaluate(actual, c, p, np.expm1(b)) for c, p, b in
                        task.proposal.support['points']])
    limits = [(actual[p + 'MinNanos'], actual[p + 'MaxNanos']) for p in
              ['park', 'halfLife']]
    expected = any(
        max(np.mean(outputs[:, j] == lo), np.mean(outputs[:, j] == hi)) >
        task.proposal.support['maxSingleBoundaryFraction'] for j, (lo, hi) in
        enumerate(limits))
    assert rejected == expected
  other = 'park-phr-body' if family == 'park-body' else 'park-body'
  ot = SurrogateTask.model_validate_json(
    (TASKS / (other + '-fit.json')).read_text())
  assert compatible_function(actual, anchor, ot.parameters)[0] is None
  assert compatible_function(anchor, anchor, task.parameters)[0][-1] == 0


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


def test_schema_accepts_dense_counts_and_arbitrary_counterfactual_names():
  from pareto_weight_calibration.tournament_study import TournamentStudy
  for f in ['park-body', 'park-phr-body']:
    task = SurrogateTask.model_validate_json(
      (TASKS / (f + '-search.json')).read_text())
    assert task.proposal.power == 24 and task.proposal.denseInference.expansionPower == 26
    assert set(task.proposal.counterfactual.values) == {
      task.parameters[-1].name}
  study = TournamentStudy.model_validate_json(
    (TASKS / 'study.json').read_text())
  assert study.selection.count == 4 and len(study.families) == 2
  assert [c['id'] for c in study.benchmark.controls] == ['POLICY_OFF',
                                                         'anchor-7931']


def test_benchmark_preparation_serializes_same_base_pair(tmp_path, monkeypatch):
  from pareto_weight_calibration.reliable_proposals import prepare_benchmarks
  from pareto_weight_calibration import cache_timing_policy
  from pareto_weight_calibration.surrogate_spec import BenchmarkPreparation
  root = Path(__file__).resolve().parents[3]
  task = SurrogateTask.model_validate_json(
    (TASKS / 'park-body-fit.json').read_text())
  anchor = json.loads((TASKS / 'inputs/anchor-7931.json').read_text())
  template = TASKS / 'inputs/harness-template.json'
  (tmp_path / 'template.json').write_bytes(template.read_bytes())
  d = task.model_dump();
  d['dataset']['referenceIdentity'] = ''
  d['proposal']['counterfactual'] = {'enabled': True,
                                     'values': {task.parameters[-1].name: 0}}
  d['proposal']['benchmark'] = dict(templateHarness='template.json',
                                    runDirectory='experiments/test',
                                    fixtureBindings={'/cpuSet': 'cpuSet',
                                                     '/parallelSources': 'parallelSources',
                                                     '/workUnits': 'workUnits'},
                                    controls=[
                                      {'id': 'POLICY_OFF', 'function': None},
                                      {'id': 'reference', 'function': anchor}])
  task = SurrogateTask.model_validate(d)
  theta = map_matrix(task.parameters, qmc.Sobol(5, seed=19).random_base2(1))[
    0].tolist()
  zero, cfg = counterfactual(task, anchor, theta)
  selected = [dict(id='full', theta=theta,
                   function=construct(task.parameters, anchor, theta)),
              dict(id='zero', theta=zero, function=cfg, pairedTo='full')]
  monkeypatch.setattr(cache_timing_policy, 'freeze', lambda p: None)
  output = tmp_path / 'result';
  output.mkdir()
  result = prepare_benchmarks(task, tmp_path, output, selected)
  assert result['arms'] == 4 and result['jvmCount'] == 4 * 18 * 2
  collection = json.loads((output / 'benchmark/collection.json').read_text())
  assert collection['sameBaseComparisons'] == [
    {'fullPolicyId': 'full', 'zeroPolicyId': 'zero'}]
  manifest = json.loads(
    (output / 'benchmark/candidate_manifest.json').read_text())
  assert manifest['arms'][0]['function'] is None
  assert manifest['arms'][-1]['function'] == cfg


def test_support_catalog_retains_actual_runtime_rounding(tmp_path):
  from pareto_weight_calibration.tournament_study import surface_catalog
  from pareto_weight_calibration.cache_timing import evaluate
  import csv, math
  task = SurrogateTask.model_validate_json(
    (TASKS / 'park-body-fit.json').read_text())
  anchor = json.loads((TASKS / 'inputs/anchor-7931.json').read_text())
  surface_catalog(task, [dict(id='reference', function=anchor)], tmp_path)
  rows = list(
    csv.DictReader((tmp_path / 'surface_catalog.tsv').open(), delimiter='\t'))
  assert len(rows) == len(task.proposal.support['points'])
  for row in rows:
    expected = evaluate(anchor, float(row['contention']), float(row['phr']),
                        math.expm1(float(row['logBody'])))
    assert (int(row['parkNanos']), int(row['halfLifeNanos'])) == expected
  summary = json.loads((tmp_path / 'surface_summary.json').read_text())[0]
  assert 0 <= summary['parkLowerClampFraction'] <= 1
  assert summary['halfLifeMin'] >= anchor['halfLifeMinNanos']


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
