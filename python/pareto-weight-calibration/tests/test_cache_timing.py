import json
import math
from pathlib import Path
import subprocess

import pytest

from pareto_weight_calibration.cache_timing import (
  ROOT, evaluate, normalization, controls_fixed, controls_policy, task,
  sobol_policies)
from pareto_weight_calibration.cache_timing_evidence import windows, tables
from pareto_weight_calibration.training_spec import TrainingTaskSpec
from pareto_weight_calibration.runtime_export import render_cache_timing


def test_all_stage_tasks_are_generic_and_do_not_export_surrogate():
  for stage, controls in [('fixed_surface', controls_fixed()),
                          ('policy_search', controls_policy()),
                          ('closed_loop_validation', controls_policy())]:
    spec = TrainingTaskSpec.model_validate(task(stage, controls))
    assert spec.export.kind == 'none'
    assert len(spec.controls) == (2 if stage == 'fixed_surface' else 14)
    assert spec.dataset.familyKey == 'workloadId'
    assert {c.family for c in spec.candidates} == (
      {'cart', 'ridge', 'hist_boost'} if stage == 'fixed_surface' else {
        'ridge'})


def test_windows_excludes_warmups_retains_slow_windows_and_rejects_partial_forks():
  log = '# Fork: 1 of 1\n# Warmup Iteration   1: 1 ops/s\n'
  for i, value in enumerate([10, 12, 1, 11, 9], 1):
    log += f'Iteration   {i}: 100 ops/s\n                 executions: {value} ops/s\n\n'
  assert windows(log) == [10, 12, 1, 11, 9]
  with pytest.raises(ValueError):
    windows(log.replace('Iteration   3:', '# Warmup Iteration   3:'))
  with pytest.raises(ValueError):
    windows(log + log)


@pytest.mark.parametrize('interactions', [False, True])
def test_java_export_matches_python_with_clamps_invalid_inputs_and_phr_above_one(
    tmp_path, interactions):
  config = dict(normalization(), parkCoefficients=[.5, -.2, .8, .1],
                halfLifeCoefficients=[-.1, .3, -.5, .2])
  if interactions:
    config['parkCoefficients'] += [.2, -.4, .7]
    config['halfLifeCoefficients'] += [-.5, .3, -.1]
  (tmp_path / 'CacheTimingEvaluator.java').write_text(
    render_cache_timing(config))
  inputs = [(0, 0, 0), (.5, 2, 500), (1, 4, math.expm1(16)), (.2, 1, -1),
            (2, 1, 0)]
  lines = []
  for c, p, b in inputs:
    lines.append(
      f'System.out.println(CacheTimingEvaluator.parkNanos({c}, {p}, {b}, 15000L) + "," + '
      f'CacheTimingEvaluator.halfLifeNanos({c}, {p}, {b}, 1000000L));')
  (tmp_path / 'Check.java').write_text(
    'class Check { public static void main(String[] a) {' + ''.join(
      lines) + '}}')
  subprocess.run(['mise', 'exec', '--', 'javac',
                  str(tmp_path / 'CacheTimingEvaluator.java'),
                  str(tmp_path / 'Check.java')], cwd=ROOT, check=True,
                 capture_output=True)
  result = subprocess.check_output(
      ['mise', 'exec', '--', 'java', '-cp', str(tmp_path), 'Check'], cwd=ROOT,
      text=True)
  assert [tuple(map(int, s.split(','))) for s in result.splitlines()] == [
    evaluate(config, *x) for x in inputs]


def test_policy_generation_cannot_use_synthetic_or_dry_run_result(tmp_path):
  fit = tmp_path / 'fit.json'
  fit.write_text(json.dumps(
    dict(taskId='cache-timing-mechanical-example', rows=288, outer=[{}])))
  with pytest.raises(ValueError,
                     match='coefficient-only Sobol generation is retired'):
    sobol_policies(fit, tmp_path / 'policies.json')
  assert not (tmp_path / 'policies.json').exists()


def test_tables_preserve_both_minima_and_equal_workload_log_score(tmp_path):
  records = []
  for workload, scale in [('fast', 1000), ('slow', 1)]:
    for pass_id in ('0', '1'):
      for policy, multiplier in [([15000, 1000000], 1), ([125000, 500000],
                                                         2 if workload == 'slow' else .5)]:
        records.append(
          dict(workloadId=workload, passId=pass_id, policyParameters=policy,
               outcome=dict(measurementWindows=[scale * multiplier * v for v in
                                                (1, 2, 3, 4, 5)])))
  tables(records, tmp_path)
  import csv
  rows = list(
    csv.DictReader((tmp_path / 'policy_score.tsv').open(), delimiter='\t'))
  assert float(rows[1]['meanLogRatio']) == pytest.approx(0)
  rows = list(csv.DictReader((tmp_path / 'throughput_by_workload.tsv').open(),
                             delimiter='\t'))
  assert all(
      float(r['candidateMinimumForkMean']) > float(r['candidateMinimumWindow'])
      for r in rows)
