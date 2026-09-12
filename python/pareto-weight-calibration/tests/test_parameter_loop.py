from copy import deepcopy
from pathlib import Path
import json, os, sys, threading, time, subprocess
import numpy as np
import pytest
from pareto_weight_calibration.loop_spec import LoopTask
from pareto_weight_calibration.parameter_loop import run_task, load_task, \
  owned_command, harness_for, trial_plan, parse_trial
from pareto_weight_calibration.loop_data import policy_id, ForkStore, bundles, \
  compatible_families
from pareto_weight_calibration.loop_search import seed_regions, update_regions, \
  propose
from pareto_weight_calibration.execution_resources import ExecutionConfig, \
  ExecutionPool


def task(tmp_path, dimension=3):
  params = [dict(name=f'p{i}', path=f'/weights/{i}', center=0, bounds=[-1, 1])
            for i in range(dimension)]
  template = dict(weights=[0.] * dimension)
  fixture = dict(workloadId='scarce', parallelSources=1, cpuSet=[0],
                 workUnits=0)
  fixtures = [fixture, dict(fixture, workloadId='plentiful', parallelSources=2)]
  responses = [dict(name=f['workloadId'], workloads=[f['workloadId']],
                    role=f['workloadId']) for f in fixtures]
  trial = dict(id='template', forks=1, warmups=1, iterations=2,
               warmupTime='1ms', measurementTime='1ms', jvmArgs=[],
               calibrationConfig=dict(cacheTimingFunction=None,
                                      cacheParkNs=15000,
                                      contentionHalfLifeNanos=1000000))
  fake = tmp_path / 'fake.py'
  fake.write_text('''import json,sys,pathlib
h=json.load(open(sys.argv[1]));t=h['trials'][0];p=pathlib.Path(h['artifacts']['outputDirectory'])/'trial';p.mkdir(parents=True,exist_ok=True)
(p/'trial_config.json').write_text(json.dumps(t))
f=t['calibrationConfig']['cacheTimingFunction']; x=f['weights'][0] if f else 0
# Both useful and poor candidates; no candidate rejection or retry by score.
v=100 if f is None else 100*(1.08-.4*(x-.2)**2)
(p/'benchmark_output.log').write_text('# Fork: 1 of 1\\n# Warmup Iteration 1: 999999\\n executions: 999999 ops/s\\n'+''.join(f'Iteration {i+1}: throughput\\n executions: {v+i*.01} ops/s\\n' for i in range(t['iterations'])))
''')
  raw = dict(kind='parameter_loop', id='generic-test', root=str(tmp_path),
             outputDirectory='out', seed=42,
             execution=dict(device='cpu', fitWorkers=2, predictWorkers=2,
                            backend='threads'),
             families=[dict(id='family', parameters=params, template=template)],
             fixtures=fixtures, responses=responses,
             historicalRows=['history.json'],
             models=[dict(id='mean', family='mean'),
                     dict(id='ridge', family='ridge', params={'alpha': 1})],
             validation=dict(outerFolds=2, innerFolds=2,
                             campaignTransfer=False),
             preference=dict(primaryTargets=['scarce'],
                             topologyTargets=['scarce'],
                             guardrailTargets=['plentiful']),
             search=dict(power=4, maxPower=4, guided=1, localRandom=1,
                         globalRandom=1, growBelowSeconds=0),
             budgets=dict(rounds=2, beamWidth=1, jvmAttempts=50, wallHours=.1,
                          trialTimeoutSeconds=5),
             benchmark=dict(adapter='command',
                            harness=dict(trials=[trial], artifacts={}),
                            buildCommand=[],
                            command=[sys.executable, str(fake), '{harness}'],
                            blocks=2, requiredJvmOptions=[]))
  spec = LoopTask.model_validate(raw);
  records = []
  for campaign in ['old-a', 'old-b']:
    for block in ['0', '1']:
      for fixture in fixtures:
        for i, x in enumerate([None, -.8, 0., .8]):
          fn = None if x is None else dict(weights=[x] * dimension)
          records.append(
            dict(rowId=f'{campaign}/{block}/{fixture["workloadId"]}/{i}',
                 campaignId=campaign,
                 policyId=policy_id(fn), function=fn,
                 families=compatible_families(spec, fn),
                 workloadId=fixture['workloadId'], blockId=block,
                 rawThroughput=100 if x is None else 104 - 5 * x * x,
                 windows=[100.], provenance={}))
  (tmp_path / 'history.json').write_text(json.dumps(records));
  path = tmp_path / 'task.json';
  path.write_text(json.dumps(raw))
  return path, records


@pytest.mark.parametrize('dimension', [2, 5])
def test_two_round_json_loop(tmp_path, dimension):
  path, history = task(tmp_path, dimension)
  result = run_task(path)
  assert result['completedRounds'] == 2
  output = tmp_path / 'out';
  state = json.loads((output / 'state.json').read_text())
  assert state['phase'] == 'COMPLETE' and state['attempts'] == 40
  first = json.loads((output / 'round-000/proposals.json').read_text())[
    'candidates']
  assert {p['role'] for p in first} == {'guided', 'local_random',
                                        'global_random'}
  assert all(len(p['theta']) == dimension for p in first)
  assert all(
      p['policyId'] not in {r['policyId'] for r in history} for p in first)
  db = ForkStore(output / 'forks.sqlite');
  n = len(db.rows());
  db.close()
  run_task(path, resume=True)
  db = ForkStore(output / 'forks.sqlite');
  assert len(db.rows()) == n;
  db.close()
  assert '999999' not in (output / 'round-000/forks.json').read_text()
  assert len(json.loads((output / 'round-000/arms.json').read_text())) == 5


def test_only_round_budget_limits_execution_and_legacy_resume(tmp_path):
  path, _ = task(tmp_path)
  raw = json.loads(path.read_text())
  raw['budgets'].update(jvmAttempts=1, wallHours=1e-10)
  raw['search']['stagnationPatience'] = 1
  raw['preference']['minimumGainLog'] = 100
  path.write_text(json.dumps(raw))
  first = run_task(path, max_rounds=1)
  assert first['attempts'] == 20 and first['completedRounds'] == 1
  # Existing snapshots may still declare removed limits.
  snapshot = tmp_path / 'out/task.json'
  prior = json.loads(snapshot.read_text())
  prior['search']['stagnationPatience'] = 1
  prior['budgets'].update(jvmAttempts=1, wallHours=1e-10)
  snapshot.write_text(json.dumps(prior))
  result = run_task(path, resume=True)
  assert result['completedRounds'] == 2 and result['attempts'] == 40
  assert result['stopReason'] == 'round budget completed'
  state = json.loads((tmp_path / 'out/state.json').read_text())
  assert state['stagnation'] == 2
  db = ForkStore(tmp_path / 'out/forks.sqlite')
  ids = [r['rowId'] for r in db.rows()]
  db.close()
  assert len(ids) == len(set(ids))
  spec, _ = load_task(path)
  assert 'jvmAttempts' not in spec.budgets.model_dump()
  assert 'wallHours' not in spec.budgets.model_dump()
  assert 'stagnationPatience' not in spec.search.model_dump()


def test_reference_and_grouping(tmp_path):
  path, records = task(tmp_path);
  s, _ = load_task(path)
  fn = records[1]['function'];
  s = s.model_copy(update={
    'benchmark': s.benchmark.model_copy(update={'referenceTemplate': fn})})
  data = bundles(s, records, s.families[0])
  ids = {r['thetaId'] for r in data};
  assert len(ids) == 3
  assert len({r['rowId'] for r in data}) == 12
  assert all(r['matchedOff'] for r in data)
  assert all(r['matchedReference'] for r in data)
  # Other campaign controls cannot create a missing reference.
  removed = [r for r in records if
             not (r['campaignId'] == 'old-b' and r['function'] == fn)]
  assert all(
      not r['matchedReference'] for r in bundles(s, removed, s.families[0]) if
      r['campaignId'] == 'old-b')


def test_parallel_pool_concurrency_and_order():
  barrier = threading.Barrier(3);
  seen = set();
  lock = threading.Lock()

  def work(x):
    with lock: seen.add(threading.get_ident())
    barrier.wait(timeout=5);
    return x * x

  with ExecutionPool(
      ExecutionConfig(device='cpu', fitWorkers=3, predictWorkers=3,
                      backend='threads')) as pool:
    assert pool.map_fit(work, range(3)) == [0, 1, 4]
    assert len(seen) == 3
    assert pool.resources['fitWorkers'] <= len(os.sched_getaffinity(0))
  assert pool.closed


def test_owned_process_timeout_cleanup(tmp_path):
  pid = tmp_path / 'pid'
  code = f'import os,time;open({str(pid)!r},"w").write(str(os.getpid()));time.sleep(30)'
  with pytest.raises(subprocess.TimeoutExpired): owned_command(
      [sys.executable, '-c', code], tmp_path, tmp_path / 'log', .3)
  with pytest.raises(ProcessLookupError): os.kill(int(pid.read_text()), 0)


def test_owned_build_without_session_deadline(tmp_path):
  assert owned_command([sys.executable, '-c', 'print("built")'], tmp_path,
                       tmp_path / 'build.log', None,
                       heartbeat=lambda elapsed: None) == 0
  assert (tmp_path / 'build.log').read_text().strip() == 'built'


def test_off_and_exact_parameter_mapping(tmp_path):
  path, _ = task(tmp_path, 5);
  s, _ = load_task(path)
  plan = trial_plan(s, [dict(policyId='POLICY_OFF', function=None)], tmp_path,
                    0)
  h = harness_for(s, plan[0], tmp_path / 'jmh', 'campaign');
  cfg = h['trials'][0]['calibrationConfig']
  assert cfg['cacheTimingFunction'] is None
  assert cfg['cacheParkNs'] == 15000 and cfg[
    'contentionHalfLifeNanos'] == 1000000
  assert plan[0]['fixture']['workloadId'] != plan[2]['fixture']['workloadId']


def test_measured_zoom_edge_no_improvement_and_radius_scaled_spacing(tmp_path):
  from pareto_weight_calibration.loop_search import measured_scores, region_for
  path, records = task(tmp_path, 2);
  spec, _ = load_task(path)
  centers = seed_regions(spec, records);
  old = centers[0]
  assert old['theta'] == [0., 0.]

  def campaign(values):
    result = []
    for index, (theta, throughput) in enumerate(values):
      fn = None if theta is None else dict(weights=theta)
      for block in ['0', '1']:
        for f in spec.fixtures:
          result.append(dict(rowId=f'new/{index}/{block}/{f["workloadId"]}',
                             campaignId='new', policyId=policy_id(fn),
                             function=fn,
                             families=compatible_families(spec, fn),
                             workloadId=f['workloadId'], blockId=block,
                             rawThroughput=throughput, windows=[throughput]))
    return result

  interior = campaign([(None, 100), ([0, 0], 104), ([.1, .1], 110)])
  from pareto_weight_calibration.loop_references import lineage
  from pareto_weight_calibration.parameter_tuning import normalize
  def origins(theta):
    f = spec.families[0]
    return [dict(policyId=policy_id(dict(weights=theta)),
                 lineage=lineage(f, {'unit': normalize(f.parameters, theta)},
                                 old, normalize(f.parameters, old['theta']),
                                 'guided'))]

  updated, improved, _ = update_regions(spec, centers, interior, 'new',
                                        origins([.1, .1]))
  assert improved and updated[0]['theta'] == [.1, .1]
  assert updated[0]['radius'] == pytest.approx(old['radius'] * .7)
  boundary = campaign([(None, 100), ([0, 0], 104), ([.58, .58], 110)])
  updated, improved, _ = update_regions(spec, centers, boundary, 'new',
                                        origins([.58, .58]))
  assert improved and updated[0]['radius'] == old['radius']
  bad = campaign([(None, 100), ([0, 0], 104), ([.1, .1], 90)])
  updated, improved, _ = update_regions(spec, centers, bad, 'new')
  assert not improved and updated[0]['theta'] == old['theta'] and updated[0][
    'failures'] == 1
  # No models, even very weak or unavailable guidance cannot prevent random exploration.
  directory = tmp_path / 'propose';
  directory.mkdir()
  a = propose(spec, records, [dict(old, radius=.02)], directory, 1)
  b = propose(spec, records, [dict(old, radius=.02)], directory, 1)
  assert a == b and len(a['candidates']) == 3
  assert a['candidates'][0]['role'] == 'guided_fallback'
  assert np.linalg.norm(np.array(a['candidates'][0]['unit']) - .5) < .04


def test_completed_session_can_extend_round_budget(tmp_path):
  path, _ = task(tmp_path);
  run_task(path, max_rounds=1)
  result = run_task(path, resume=True, max_rounds=2)
  assert result['completedRounds'] == 2 and result['attempts'] == 40


def test_partial_launcher_completion_is_ingested_once_on_resume(tmp_path):
  path, _ = task(tmp_path);
  # This simulates a pre-UPDATE crash: keep raw attempts for the recovery exercise.
  payload = json.loads(path.read_text())
  payload['cleanup'] = {'successfulRawTrials': False}
  path.write_text(json.dumps(payload))
  run_task(path, max_rounds=1)
  output = tmp_path / 'out';
  state = json.loads((output / 'state.json').read_text())
  # Simulate crash after a successful child and SQLite commit, before saving trial completion.
  state['phase'] = 'BENCHMARK';
  state['round'] = 0
  state['trials'][-1]['status'] = 'PENDING';
  state['trials'][-1]['attempts'][-1]['status'] = 'RUNNING'
  (output / 'state.json').write_text(json.dumps(state))
  result = run_task(path, resume=True, max_rounds=1)
  assert result['attempts'] == 20
  db = ForkStore(output / 'forks.sqlite');
  ids = [r['rowId'] for r in db.rows()];
  db.close()
  assert len(ids) == len(set(ids))


def test_two_separated_measured_tradeoffs_are_retained(tmp_path):
  path, records = task(tmp_path, 2);
  raw = json.loads(path.read_text());
  raw['budgets']['beamWidth'] = 2;
  path.write_text(json.dumps(raw))
  spec, _ = load_task(path);
  regions = seed_regions(spec, records)
  assert len(regions) == 2 and regions[0]['policyId'] != regions[1]['policyId']
  assert np.linalg.norm(
    np.array(regions[0]['theta']) - regions[1]['theta']) > .15


def test_interrupt_resume_terminates_owned_trial_and_keeps_attempt(tmp_path):
  import signal
  path, _ = task(tmp_path)
  script = tmp_path / 'fake.py';
  old = script.read_text();
  marker = tmp_path / 'started'
  script.write_text('import os,time,pathlib\np=pathlib.Path(' + repr(str(
    marker)) + ')\nif not p.exists():\n p.write_text(str(os.getpid()))\n time.sleep(30)\n' + old)
  command = [sys.executable, '-m', 'pareto_weight_calibration.training_runner',
             '--task', str(path), '--max-rounds', '1']
  with (tmp_path / 'parent.log').open('w') as log:
    process = subprocess.Popen(command, stdout=log, stderr=log,
                               start_new_session=True)
    try:
      deadline = time.monotonic() + 20
      while not marker.exists() and process.poll() is None and time.monotonic() < deadline: time.sleep(
        .02)
      assert marker.exists()
      process.send_signal(signal.SIGINT);
      process.wait(timeout=10)
      with pytest.raises(ProcessLookupError):
        os.kill(int(marker.read_text()), 0)
    finally:
      if process.poll() is None: os.killpg(process.pid,
                                           signal.SIGKILL);process.wait()
  result = run_task(path, resume=True, max_rounds=1)
  assert result['completedRounds'] == 1 and result['attempts'] == 21
  state = json.loads((tmp_path / 'out/state.json').read_text())
  assert state['trials'][0]['attempts'][0]['status'] == 'INTERRUPTED'
  assert state['trials'][0]['attempts'][1]['status'] == 'COMPLETE'


def test_launcher_exit_cleans_descendant_process_group(tmp_path):
  pid = tmp_path / 'child.pid'
  child = 'import os,time;open(' + repr(
    str(pid)) + ',"w").write(str(os.getpid()));time.sleep(30)'
  parent = 'import subprocess,sys,time,pathlib;subprocess.Popen([sys.executable,"-c",' + repr(
    child) + ']);p=pathlib.Path(' + repr(
    str(pid)) + ');\nwhile not p.exists():time.sleep(.01)'
  assert owned_command([sys.executable, '-c', parent], tmp_path,
                       tmp_path / 'log', 5) == 0
  child_pid = int(pid.read_text());
  stat = Path(f'/proc/{child_pid}/stat')
  deadline = time.monotonic() + 2
  while stat.exists() and stat.read_text().split()[
    2] != 'Z' and time.monotonic() < deadline: time.sleep(.01)
  assert not stat.exists() or stat.read_text().split()[2] == 'Z'
