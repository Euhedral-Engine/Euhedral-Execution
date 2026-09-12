"""Preparation and synthetic collection checks; never launches a scheduler benchmark."""
from copy import deepcopy
import json
import math
from pathlib import Path

import numpy as np
import pytest

from pareto_weight_calibration import cache_timing_policy as campaign
from pareto_weight_calibration.cache_timing import ROOT, evaluate, sha, write
from pareto_weight_calibration.cache_timing_surfaces import (
  select_surfaces, normalized_outputs, distance, FAMILIES, DESIGN)
from pareto_weight_calibration.cache_timing_comparator import choose, \
  workload_stats
from pareto_weight_calibration.task_adapters import load_dataset, metrics
from pareto_weight_calibration.training_spec import TrainingTaskSpec, digest


@pytest.fixture(scope='module')
def prepared(tmp_path_factory):
  output = tmp_path_factory.mktemp('cache-prep') / 'handoff'
  evidence = ROOT / 'experiments/cache-timing-fixed-evidence/tasks/cache_timing_fixed_surface.json'
  if not evidence.exists() and (
      ROOT / 'experiments/cache-run-data.tsv').exists():
    from pareto_weight_calibration.run_archive import restore
    archived = tmp_path_factory.mktemp('cache-evidence')
    restore(ROOT / 'experiments/cache-run-data.tsv', archived)
    evidence = archived / evidence.relative_to(ROOT)
  campaign.prepare(ROOT / 'benchmarks/src/test/resources/cache-timing',
                   evidence,
                   output)
  return output


def test_behavior_manifest_spans_families_and_reproduces_outputs(prepared):
  manifest, harness = campaign.verify(prepared)
  selected = [p for p in manifest['policies'] if not p['control']]
  assert len(selected) == 32
  assert {p['family'] for p in selected} == set(FAMILIES)
  points = np.array(manifest['grid'] + manifest['interior'])
  surfaces = [normalized_outputs(p['function'], points) for p in selected]
  assert all(np.min(s) >= -1e-12 and np.max(s) <= 1 + 1e-12 for s in surfaces)
  assert min(distance(a, b) for i, a in enumerate(surfaces) for b in
             surfaces[i + 1:]) >= DESIGN['minSurfaceRmsDistance']
  assert all(max(p['span']) >= DESIGN['minOutputSpan'] for p in selected)
  assert all(max(v for x in p['clampOccupancy'] for v in x.values()) <= DESIGN[
    'maxSingleBoundaryFraction'] for p in selected)
  assert [digest(s.tolist()) for s in surfaces] == [p['surfaceSignature'] for p
                                                    in selected]
  # Selection depends on state geometry only, and is deterministic.
  assert select_surfaces()['policies'] == selected
  assert len(harness['trials']) == 684
  controls = [p for p in manifest['policies'] if p['control']]
  assert len(controls) == 6
  zero = next(p['function'] for p in controls if p['kind'] == 'live')
  assert zero['parkCoefficients'] == zero['halfLifeCoefficients'] == [0] * 7
  assert evaluate(zero, 1, 4, math.expm1(16)) == (15000, 1000000)


def test_empirical_constant_choice_uses_training_families_and_reports_ties():
  baseline = (15000, 1000000);
  alternate = (500000, 1000000)
  stats = {
    (w, p): dict(workloadId=w, logRatio=j, percentChange=100 * math.expm1(j))
    for w in ('train', 'held') for p, j in
    [(baseline, 0), (alternate, .1 if w == 'train' else -10)]}
  assert choose(stats, ['train'])[0] == alternate
  stats['held', alternate]['logRatio'] = 100
  assert choose(stats, ['train'])[0] == alternate
  stats['train', alternate]['logRatio'] = 0
  pair, ties = choose(stats, ['train'])
  assert pair == baseline and len(ties) == 2
  row = dict(runId='one', forkId='1', workloadId='train', passId='0',
             policyParameters=list(baseline),
             outcome=dict(executionsPerSecond=3,
                          measurementWindows=[1, 2, 3, 4, 5]))
  with pytest.raises(ValueError, match='duplicate fork'): workload_stats(
      [row, row])
  measured = workload_stats([row])['train', baseline]
  assert measured['candidateMinimumForkMean'] == 3 and measured[
    'candidateMinimumWindow'] == 1


def test_frozen_manifest_tamper_is_detected(prepared, tmp_path):
  import shutil
  copy = tmp_path / 'handoff';
  shutil.copytree(prepared, copy)
  p = copy / 'policy_harness.json';
  p.write_text(p.read_text() + ' ')
  with pytest.raises(ValueError,
                     match='frozen handoff changed'): campaign.verify(copy)


def test_synthetic_collection_retains_forks_windows_controls_and_rejects_drift(
    prepared, tmp_path, monkeypatch):
  manifest, harness = campaign.verify(prepared)
  lock = json.loads((prepared / 'lock.json').read_text())
  run = tmp_path / harness['artifacts']['outputDirectory'];
  run.mkdir(parents=True)
  write(run / 'identity.json',
        dict(handoffLockSha256=sha(prepared / 'lock.json'),
             harnessSha256=sha(prepared / 'policy_harness.json'),
             sourceHashes=lock['sourceHashes'],
             topology=manifest['topology'],
             binaryHashes={'synthetic': 'test-only'}))
  for t in harness['trials']:
    p = run / t['id'];
    p.mkdir()
    trial = deepcopy(t);
    trial['calibrationConfig']['cacheActuatorVersion'] = 'test-only'
    write(p / 'trial_config.json', trial)
    # Two 5-window JVMs are two replicates, including the deliberately slow first fork.
    scale = 1 if t['origin']['sampleIndex'] == 0 else 10
    log = '# JMH version: synthetic\n# VM version: synthetic\n# Fork: 1 of 1\n'
    for i in range(1,
                   6): log += f'Iteration {i}: 100 ops/s\n executions: {scale * i} ops/s\n'
    (p / 'benchmark_output.log').write_text(log)
  monkeypatch.setattr(campaign, 'ROOT', tmp_path)
  output = tmp_path / 'evidence';
  campaign.collect(prepared, output)
  records = json.loads((output / 'arms.json').read_text())
  assert len(records) == 684 and sum(
      len(r['outcome']['measurementWindows']) for r in records) == 3420
  spec = TrainingTaskSpec.load(output / 'tasks/cache_timing_policy_search.json')
  data = load_dataset(spec, output / 'tasks')
  assert len(data.row_ids) == 594 and len(set(data.groups)) == 9
  result = metrics(spec, data, np.zeros_like(data.Y))
  assert result['baselineRelativeJ'] == pytest.approx(0)
  assert result['geometricChangePercent'] == pytest.approx(0)
  # An equal-workload log objective does not let a large positive family hide losses.
  target = data.Y.copy()
  for i, g in enumerate(data.groups): target[i, 0] += math.log(
    2 if g == sorted(set(data.groups))[0] else .5)
  changed = data.__class__(**dict(data.__dict__, Y=target))
  result = metrics(spec, changed, np.zeros_like(target))
  assert result['baselineRelativeJ'] == pytest.approx(-7 * math.log(2) / 9)
  assert result['worstHeldWorkloadPercent'] == pytest.approx(-50)
  bad = run / harness['trials'][0]['id'] / 'trial_config.json'
  trial = json.loads(bad.read_text());
  trial['calibrationConfig']['randomizeWork'] = True;
  bad.write_text(json.dumps(trial))
  with pytest.raises(ValueError, match='configuration changed'):
    campaign.collect(prepared, tmp_path / 'bad-evidence')
