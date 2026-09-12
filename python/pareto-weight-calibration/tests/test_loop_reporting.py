"""Operator output reads existing measured decisions; cleanup follows durable UPDATE."""
from copy import deepcopy
from pathlib import Path
import csv
import json
import math
import pytest
from .test_parameter_loop import task
from pareto_weight_calibration.parameter_loop import load_task, run_task, atomic
from pareto_weight_calibration.loop_data import ForkStore, policy_id, \
  compatible_families
from pareto_weight_calibration.loop_search import measured_scores, seed_regions, \
  update_regions
from pareto_weight_calibration.loop_reporting import (Progress, duration,
                                                      policy_view,
                                                      round_summary,
                                                      summary_text,
                                                      cleanup_round,
                                                      publish_current, startup)


def test_progress_actual_plan_counts_eta_and_retry(tmp_path, capsys):
  path, _ = task(tmp_path)
  spec, _ = load_task(path)
  trial = dict(status='PENDING', arm={'policyId': 'auto'},
               fixture={'workloadId': 'fixture'}, blockId='1')
  state = dict(round=1, trials=[deepcopy(trial) for _ in range(40)],
               progress={'trialSeconds': [30, 32]})
  for t in state['trials'][:36]: t['status'] = 'COMPLETE'
  progress = Progress(spec, state)
  progress.phase('BENCHMARK')
  progress.trial(37, trial, 'RETRY', {'number': 1})
  progress.trial(37, trial, 'FAILED', {'number': 1}, 31.4,
                 'failure\nshort reason')
  text = capsys.readouterr().out
  assert 'Round 2/2: BENCHMARK' in text
  assert '[Round 2/2] [Trial 37/40] RETRY' in text
  assert 'attempt=2/2' in text and 'policy=auto workload=fixture block=1' in text
  assert 'FAILED attempt=2/2 31.4s' in text
  assert 'round ETA ~2m 04s' in text
  assert duration(None) == 'estimating'


def measured_panel(spec, candidate_gain):
  records = []
  for block in ['0', '1']:
    for f in spec.fixtures:
      for theta, value in [(None, 100),
                           ([0.] * len(spec.families[0].parameters), 104),
                           ([.1] * len(spec.families[0].parameters),
                            104 * candidate_gain)]:
        fn = None if theta is None else {'weights': theta}
        pid = policy_id(fn)
        records.append(dict(rowId=f'campaign/{block}/{f["workloadId"]}/{pid}',
                            campaignId='campaign',
                            policyId=pid, function=fn,
                            families=compatible_families(spec, fn),
                            workloadId=f['workloadId'],
                            blockId=block, rawThroughput=value,
                            windows=[value]))
  return records


def test_round_best_distinct_from_accepted_incumbent_and_exact_display(
    tmp_path):
  path, history = task(tmp_path, 5)
  spec, _ = load_task(path)
  previous = seed_regions(spec, history)
  records = measured_panel(spec, 1.001)
  accepted, improved, ranked = update_regions(spec, previous, records,
                                              'campaign')
  assert not improved
  summary = round_summary(spec, 0, 'campaign', ranked, previous, accepted,
                          accepted, improved, 1)
  assert summary['roundBest']['policyId'] != summary['currentIncumbent'][
    'policyId']
  assert not summary['roundBest']['acceptedAsIncumbent']
  assert summary['currentIncumbent']['acceptedIncumbent']
  assert len(summary['currentIncumbent']['activeParameters']) == 5
  assert summary['roundBest']['broadScarcePercent'] == pytest.approx(4.104)
  assert summary['currentIncumbent']['broadScarcePercent'] == pytest.approx(4.)
  text = summary_text(summary)
  assert 'ROUND BEST' in text and 'CURRENT INCUMBENT' in text and 'accepted as incumbent: NO' in text
  assert 'p4: 0.0' in text and 'Broad: +4.10%' in text and 'Broad: +4.00%' in text
  publish_current(tmp_path, summary, atomic)
  current = json.loads((tmp_path / 'current-best.json').read_text())
  assert current['policyId'] == previous[0]['policyId']
  assert json.loads((tmp_path / 'current-best-config.json').read_text()) == \
         previous[0]['function']


def test_percentage_breadth_and_worst_derive_from_existing_measured_returns(
    tmp_path):
  path, _ = task(tmp_path, 2)
  raw = json.loads(path.read_text())
  raw['fixtures'] = [dict(raw['fixtures'][0], workloadId=w) for w in
                     ['cheap', 'costly', 'healthy-a', 'healthy-b']]
  raw['responses'] = [dict(name=name, workloads=workloads, role=role) for
                      name, workloads, role in [
                        ('scarce', ['cheap', 'costly'], 'scarce'),
                        ('plentiful', ['healthy-a', 'healthy-b'], 'plentiful'),
                        ('cheap', ['cheap'], 'scarce_body'),
                        ('costly', ['costly'], 'scarce_body'),
                        ('a', ['healthy-a'], 'plentiful_body'),
                        ('b', ['healthy-b'], 'plentiful_body')]]
  raw['preference'].update(primaryTargets=['cheap', 'costly'],
                           topologyTargets=['scarce'],
                           guardrailTargets=['a', 'b'])
  path.write_text(json.dumps(raw));
  spec, _ = load_task(path)
  records = measured_panel(spec, 1.01)
  ratios = {'cheap': 1.2, 'costly': .98, 'healthy-a': .97, 'healthy-b': 1.01}
  candidate_id = policy_id({'weights': [.1, .1]})
  for r in records:
    if r['policyId'] == candidate_id:
      r['rawThroughput'] = 100 * ratios[r['workloadId']]
  row = next(r for r in measured_scores(spec, records) if
             r['policyId'] == candidate_id)
  view = policy_view(spec, row)
  assert view['broadScarcePercent'] == pytest.approx(
    100 * (math.sqrt(1.2 * .98) - 1))
  assert view['broadPlentifulPercent'] == pytest.approx(
    100 * (math.sqrt(.97 * 1.01) - 1))
  assert view['scarceTopologyPercent']['scarce'] == pytest.approx(
      view['broadScarcePercent'])
  assert view['positiveScarceWorkloads'] == 1
  assert view['worstScarce'] == {'workloadId': 'costly',
                                 'percent': pytest.approx(-2)}
  assert view['worstPlentiful'] == {'workloadId': 'healthy-a',
                                    'percent': pytest.approx(-3)}
  assert view['measuredScore'] == row['score']


def test_fake_two_round_output_cleanup_artifacts_and_resume(tmp_path, capsys,
    monkeypatch):
  from pareto_weight_calibration import loop_reporting as reporting
  path, _ = task(tmp_path)
  original = reporting.shutil.rmtree
  removed = []

  def audited(folder):
    directory = folder.parents[2]
    state = json.loads((tmp_path / 'out/state.json').read_text())
    summary = json.loads((directory / 'round-summary.json').read_text())
    assert state['round'] > summary['roundIndex']
    assert (directory / 'throughput.tsv').exists() and (
          directory / 'forks.json').exists()
    assert (directory / 'round-summary.txt').exists() and (
          directory / 'measured-ranking.json').exists()
    db = ForkStore(tmp_path / 'out/forks.sqlite')
    records = {r['rowId']: r for r in db.rows()};
    db.close()
    assert all(records[r['rowId']] == r for r in
               json.loads((directory / 'forks.json').read_text()))
    removed.append(str(folder));
    original(folder)

  monkeypatch.setattr(reporting.shutil, 'rmtree', audited)
  result = run_task(path)
  assert result['completedRounds'] == 2 and len(removed) == 40
  text = capsys.readouterr().out
  for phase in ['FIT', 'PROPOSE', 'BENCHMARK', 'COLLECT',
                'UPDATE']: assert f'Round 2/2: {phase}' in text
  assert '[Round 1/2] [Trial 1/20] START' in text and '[Round 2/2] [Trial 20/20] COMPLETE' in text
  assert 'ROUND 2/2 COMPLETE' in text and 'exact active parameters' in text and 'round ETA' in text
  root = tmp_path / 'out'
  current = json.loads((root / 'current-best.json').read_text())
  assert json.loads((root / 'current-best-config.json').read_text()) == current[
    'function']
  assert current['round'] == 2
  leaderboard = list(
    csv.DictReader((root / 'leaderboard.tsv').open(), delimiter='\t'))
  assert len(leaderboard) == 8 and {r['round'] for r in leaderboard} == {'1',
                                                                         '2'}
  assert sum(r['roundWinner'] == 'True' for r in leaderboard) == 2
  for directory in sorted(root.glob('round-*')):
    summary = json.loads((directory / 'round-summary.json').read_text())
    assert summary['cleanup']['successfulTrialDirectoriesRemoved'] == 20
    assert summary['cleanup']['bytesRemoved'] > 0 and summary['cleanup'][
      'status'] == 'complete'
    assert (directory / 'trial-plan.json').exists()
    assert not list((directory / 'trials').rglob('benchmark_output.log'))
    assert (directory / 'round-summary.txt').read_text() == summary_text(
      summary)
  before = (root / 'leaderboard.tsv').read_text()
  run_task(path, resume=True)
  assert len(removed) == 40 and (root / 'leaderboard.tsv').read_text() == before


def test_cleanup_failure_is_nonfatal_and_retried_without_remeasurement(tmp_path,
    monkeypatch):
  from pareto_weight_calibration import loop_reporting as reporting
  path, _ = task(tmp_path)
  original = reporting.shutil.rmtree

  def fail(folder): raise PermissionError('synthetic cleanup denial')

  monkeypatch.setattr(reporting.shutil, 'rmtree', fail)
  result = run_task(path, max_rounds=1)
  assert result['completedRounds'] == 1
  root = tmp_path / 'out';
  report = json.loads((root / 'round-000/round-summary.json').read_text())
  assert report['cleanup']['status'] == 'partial' and report['cleanup'][
    'errors']
  assert (root / 'current-best-config.json').exists()
  monkeypatch.setattr(reporting.shutil, 'rmtree', original)
  result = run_task(path, resume=True, max_rounds=1)
  assert result['attempts'] == 20
  report = json.loads((root / 'round-000/round-summary.json').read_text())
  assert report['cleanup']['successfulTrialDirectoriesRemoved'] == 20


def test_summary_failure_preserves_raw_and_update_decision_on_resume(tmp_path,
    monkeypatch):
  from pareto_weight_calibration import parameter_loop
  path, _ = task(tmp_path)
  original = parameter_loop.persist_summary

  def fail(*args): raise OSError('synthetic summary write failure')

  monkeypatch.setattr(parameter_loop, 'persist_summary', fail)
  with pytest.raises(OSError, match='summary write failure'):
    run_task(path, max_rounds=1)
  root = tmp_path / 'out'
  state = json.loads((root / 'state.json').read_text())
  assert state['phase'] == 'UPDATE' and state['round'] == 0
  assert len(list((root / 'round-000/trials').rglob('launcher.log'))) == 20
  decision = json.loads((root / 'round-000/update.json').read_text())
  monkeypatch.setattr(parameter_loop, 'persist_summary', original)
  result = run_task(path, resume=True, max_rounds=1)
  assert result['attempts'] == 20
  report = json.loads((root / 'round-000/round-summary.json').read_text())
  assert report['improved'] == decision['improved']
  assert report['acceptedRegions'] == decision['regions']
  assert report['cleanup']['successfulTrialDirectoriesRemoved'] == 20


def test_cleanup_defaults_smoke_retention_and_json_override(tmp_path):
  path, _ = task(tmp_path)
  spec, _ = load_task(path)
  assert spec.cleanup.successfulRawTrials and spec.cleanup.retainFailedTrials
  smoke, _ = load_task(path, smoke=True)
  assert not smoke.cleanup.successfulRawTrials and smoke.cleanup.retainFailedTrials
  raw = json.loads(path.read_text())
  raw.setdefault('smoke', {})['cleanup'] = {'successfulRawTrials': True}
  path.write_text(json.dumps(raw))
  smoke, _ = load_task(path, smoke=True)
  assert smoke.cleanup.successfulRawTrials


def test_cleanup_interruption_after_removal_recovers_from_durable_ledger(
    tmp_path, monkeypatch):
  from pareto_weight_calibration import loop_reporting as reporting
  path, _ = task(tmp_path);
  original = reporting.shutil.rmtree

  def interrupt(folder):
    original(folder)
    raise KeyboardInterrupt()

  monkeypatch.setattr(reporting.shutil, 'rmtree', interrupt)
  with pytest.raises(KeyboardInterrupt): run_task(path, max_rounds=1)
  monkeypatch.setattr(reporting.shutil, 'rmtree', original)
  result = run_task(path, resume=True, max_rounds=1)
  assert result['attempts'] == 20 and result['completedRounds'] == 1
  report = json.loads(
    (tmp_path / 'out/round-000/round-summary.json').read_text())
  assert report['cleanup']['successfulTrialDirectoriesRemoved'] == 20 and \
         report['cleanup']['bytesRemoved'] > 0


def test_cleanup_gate_and_failed_interrupted_attempt_preservation(tmp_path):
  path, _ = task(tmp_path);
  raw = json.loads(path.read_text());
  raw['cleanup'] = {'successfulRawTrials': False};
  path.write_text(json.dumps(raw))
  run_task(path, max_rounds=1)
  spec, _ = load_task(path);
  spec = spec.model_copy(update={
    'cleanup': spec.cleanup.model_copy(update={'successfulRawTrials': True})})
  root = tmp_path / 'out';
  directory = root / 'round-000';
  report = json.loads((directory / 'round-summary.json').read_text())
  plan = json.loads((directory / 'trial-plan.json').read_text());
  trial = plan['trials'][0]
  for number, status in [(8, 'FAILED'), (9, 'INTERRUPTED')]:
    folder = directory / 'trials' / trial['id'] / f'attempt-{number}';
    folder.mkdir()
    (folder / 'launcher.log').write_text(status)
    trial['attempts'].insert(0, dict(number=number, status=status,
                                     harness=str(folder / 'harness.json'),
                                     directory=str(folder / 'output')))
  atomic(directory / 'trial-plan.json', plan)
  db = ForkStore(root / 'forks.sqlite');
  records = db.rows();
  db.close()
  cleanup_round(spec, directory, report, records, 0, atomic)
  assert list((directory / 'trials').rglob('benchmark_output.log'))
  saved = records.pop(next(i for i, r in enumerate(records) if
                           r['campaignId'] == report['campaignId']))
  cleanup_round(spec, directory, report, records, 1, atomic)
  assert report['cleanup']['status'] == 'blocked'
  assert list((directory / 'trials').rglob('benchmark_output.log'))
  cleanup_round(spec, directory, report, records + [saved], 1, atomic)
  assert report['cleanup']['status'] == 'complete' and report['cleanup'][
    'failedAttemptDirectoriesRetained'] == 2
  assert len(list((directory / 'trials').rglob('launcher.log'))) == 2


def test_atomic_current_answer_and_resume_progress(tmp_path, monkeypatch,
    capsys):
  from pareto_weight_calibration import parameter_loop
  path, history = task(tmp_path);
  spec, _ = load_task(path);
  prev = seed_regions(spec, history)
  records = measured_panel(spec, 1.02);
  accepted, improved, ranked = update_regions(spec, prev, records, 'campaign')
  summary = round_summary(spec, 0, 'campaign', ranked, prev, accepted, accepted,
                          improved, 0)
  calls = [];
  original = parameter_loop.os.replace

  def replace(src, dest):
    assert Path(src).suffix == '.tmp'
    json.loads(Path(src).read_text())
    calls.append(Path(dest).name);
    original(src, dest)

  monkeypatch.setattr(parameter_loop.os, 'replace', replace)
  publish_current(tmp_path, summary, atomic)
  assert calls == ['current-best-config.json', 'current-best.json']
  state = dict(round=0, phase='BENCHMARK', attempts=4, regions=accepted,
               trials=[{'status': 'COMPLETE'}, {'status': 'PENDING'},
                       {'status': 'COMPLETE'}])
  startup(spec, dict(resources=spec.execution.resolved(), newPoliciesPerRound=3,
                     projectedMaximumJvmForks=40, output=str(tmp_path)), state,
          52, True)
  text = capsys.readouterr().out
  assert 'completed trials in current round: 2/3' in text and 'remaining rounds: 2 | attempts recorded: 4' in text
