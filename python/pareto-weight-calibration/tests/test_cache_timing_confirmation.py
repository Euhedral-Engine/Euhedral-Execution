"""Bounded handoff checks and synthetic evidence; no scheduler benchmark execution."""
from copy import deepcopy
import csv
import json
from pathlib import Path
import pytest
from pareto_weight_calibration import cache_timing_confirmation as c
from pareto_weight_calibration.cache_timing import ROOT, write, sha


@pytest.fixture(scope='module')
def handoff(tmp_path_factory):
  path = tmp_path_factory.mktemp('confirmation') / 'handoff'
  c.prepare(ROOT / 'benchmarks/src/test/resources/cache-timing', path)
  return path


def test_inventory_exact_frozen_arms_and_review_gates(handoff):
  m = c.check(handoff)
  assert [a['id'] for a in m['arms']] == ['POLICY_OFF', 'live-25', 'live-12']
  assert m['arms'][0]['function'] is None
  assert [m['stages'][s]['expectedForks'] for s in c.STAGES] == [72, 144, 72]
  c.review_gate(handoff, 'r23', [])
  for stage in ('topologies', 'dynamic'):
    with pytest.raises(ValueError, match='review required'): c.review_gate(
      handoff, stage, [])
  assert len(m['stages']['topologies']['fixtures']) == 12
  assert len(m['stages']['dynamic']['fixtures']) == 6


@pytest.mark.parametrize('stage', c.STAGES)
def test_collection_retains_slow_forks_and_separates_scarcity_from_guardrails(
    handoff, tmp_path, monkeypatch, stage):
  m = c.check(handoff);
  info = m['stages'][stage];
  harness = c.read(handoff / info['harness'])
  run = tmp_path / info['outputDirectory'];
  run.mkdir(parents=True)
  write(run / 'identity.json',
        dict(stage=stage, handoffLockSha256=sha(handoff / 'lock.json'),
             harnessSha256=sha(handoff / info['harness']),
             sourceHashes=c.read(handoff / 'lock.json')['sourceHashes'],
             topologies=m['topologies']))
  for t in harness['trials']:
    trial = deepcopy(t);
    trial['calibrationConfig']['cacheActuatorVersion'] = 'synthetic'
    folder = run / trial['id'];
    folder.mkdir();
    write(folder / 'trial_config.json', trial)
    # Deliberately unequal absolute workload scales and slow pass 0; neither may become a reward weight.
    arm = trial['labels']['policyId'];
    primary = trial['labels']['workloadClass'] == 'PRIMARY'
    scale = (1 if primary else 1000) * (
      1 if trial['origin']['sampleIndex'] == 0 else 10)
    ratio = 1 if arm == 'POLICY_OFF' else 1.2 if primary else .99
    text = '# JMH version: synthetic\n# VM version: synthetic\n# Fork: 1 of 1\n'
    for i in range(1, trial[
                        'iterations'] + 1): text += f'Iteration {i}: 100 ops/s\n executions: {scale * ratio * i} ops/s\n'
    (folder / 'benchmark_output.log').write_text(text)
  monkeypatch.setattr(c, 'ROOT', tmp_path)
  output = tmp_path / 'evidence';
  c.collect(handoff, stage, output)
  receipt = c.read(output / 'evidence_manifest.json')
  assert receipt['forkCount'] == info['expectedForks']
  assert receipt['windowCount'] == info['expectedForks'] * info[
    'windowsPerFork']
  assert receipt['acceptance'] == 'REVIEW_REQUIRED'
  rows = list(
    csv.DictReader((output / 'class_summary.tsv').open(), delimiter='\t'))
  if stage != 'dynamic':
    for r in rows:
      assert float(r['geometricChangePercent']) == pytest.approx(
        20 if r['workloadClass'] == 'PRIMARY' else -1)
      assert int(r['positiveEveryBlockWorkloads']) == (
        3 if r['workloadClass'] == 'PRIMARY' else 0)
  else:
    forks = list(
      csv.DictReader((output / 'per_fork.tsv').open(), delimiter='\t'))
    assert len(forks) == 2 * info[
      'expectedForks']  # two nested phases, same fork IDs
    assert len({r['runId'] for r in forks}) == info['expectedForks']
  broken = next(run.glob('*/benchmark_output.log'));
  broken.write_text(
    broken.read_text().replace('Iteration 1:', 'Warmup Iteration 1:'))
  with pytest.raises(ValueError, match='complete fork'):
    c.collect(handoff, stage, tmp_path / 'bad')


def test_review_must_bind_to_this_collected_stage(handoff, tmp_path,
    monkeypatch):
  m = c.check(handoff);
  monkeypatch.setattr(c, 'ROOT', tmp_path)
  receipt = tmp_path / m['stages']['r23'][
    'evidenceDirectory'] / 'evidence_manifest.json'
  write(receipt, dict(stage='r23', files={},
                      handoffLockSha256=sha(handoff / 'lock.json')))
  review = tmp_path / 'review.json'
  write(review, dict(stage='r23', decision='proceed', reviewer='test',
                     rationale='synthetic gate check',
                     evidenceManifestSha256='wrong'))
  with pytest.raises(ValueError,
                     match='matching collected evidence'): c.review_gate(
    handoff, 'topologies', [review])
  data = c.read(review);
  data['evidenceManifestSha256'] = sha(receipt);
  review.write_text(json.dumps(data))
  c.review_gate(handoff, 'topologies', [review])
  with pytest.raises(ValueError, match='topologies'): c.review_gate(handoff,
                                                                    'dynamic',
                                                                    [review])
