"""Budgeted automatic measured parameter optimization through the generic training CLI."""
from copy import deepcopy
from pathlib import Path
import contextlib, fcntl, json, math, os, signal, subprocess, time, uuid, \
  platform
from importlib import metadata
import numpy as np
from .loop_spec import LoopTask
from .loop_data import ForkStore, ingest, policy_id, compatible_families
from .loop_search import seed_regions, update_regions, \
  measured_scores
from .offline_phase import run_offline
from .parameter_tuning import pointer
from .cache_timing_confirmation import measurement_windows
from .surrogate_tournament import clean
from .loop_reporting import (Progress, startup, round_summary, persist_summary,
                             summary_text, publish_current, leaderboard,
                             cleanup_round)


def atomic(path, value):
  path.parent.mkdir(parents=True, exist_ok=True)
  tmp = path.with_name(path.name + '.tmp')
  with tmp.open('w') as f:
    json.dump(clean(value), f, indent=2, allow_nan=False);
    f.write('\n');
    f.flush();
    os.fsync(f.fileno())
  os.replace(tmp, path)


class OperationalStop(RuntimeError): pass


def terminate(process):
  # The launcher can exit before a descendant; reap the entire owned group, not only its PID.
  with contextlib.suppress(ProcessLookupError):
    os.killpg(process.pid, signal.SIGTERM)
  try:
    process.wait(timeout=5)
  except subprocess.TimeoutExpired:
    with contextlib.suppress(ProcessLookupError):
      os.killpg(process.pid, signal.SIGKILL)
    process.wait()
  with contextlib.suppress(ProcessLookupError):
    os.killpg(process.pid, signal.SIGKILL)


def owned_command(command, cwd, log, timeout, heartbeat=None):
  env = os.environ.copy()
  for key in ['JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
              '_JAVA_OPTIONS']: env.pop(key, None)
  log.parent.mkdir(parents=True, exist_ok=True)
  with log.open('w') as stream:
    process = subprocess.Popen(command, cwd=cwd, env=env, stdout=stream,
                               stderr=subprocess.STDOUT, start_new_session=True)
    started = time.monotonic()
    try:
      while True:
        left = (timeout - (time.monotonic() - started)
                if timeout is not None else None)
        if left is not None and left <= 0:
          raise subprocess.TimeoutExpired(command, timeout)
        try:
          wait_seconds = left
          if heartbeat:
            wait_seconds = min(30, left) if left is not None else 30
          return process.wait(timeout=wait_seconds)
        except subprocess.TimeoutExpired:
          if not heartbeat or (
              timeout is not None and time.monotonic() - started >= timeout):
            raise
          heartbeat(time.monotonic() - started)
    finally:
      terminate(process)


def trial_plan(spec, arms, directory, round_index):
  candidates = [(f, a) for f in spec.fixtures for a in arms if
                not (spec.benchmark.offSentinel and a['function'] is None)];
  plan = [];
  n = len(candidates)
  for block in range(spec.benchmark.blocks):
    for position in range(n):
      index = ((block // 2) + (
        position if block % 2 == 0 else n - 1 - position)) % n
      fixture, arm = candidates[index];
      tid = f'r{round_index}-b{block}-c{index}'
      plan.append(dict(id=tid, blockId=str(block), arm=arm, fixture=fixture,
                       status='PENDING', attempts=[]))
  if spec.benchmark.offSentinel:
    fixture = next(f for f in spec.fixtures if
                   f['workloadId'] == spec.benchmark.offSentinel)
    off = next(a for a in arms if a['function'] is None)
    plan.insert((round_index * 17) % (len(plan) + 1),
                dict(id=f'r{round_index}-sentinel', blockId='sentinel', arm=off,
                     fixture=fixture, status='PENDING', attempts=[]))
  return plan


def harness_for(spec, trial, dest, campaign):
  harness = deepcopy(spec.benchmark.harness);
  base = deepcopy(harness['trials'][0]);
  base['id'] = trial['id'];
  base['forks'] = 1
  base.pop('sweep', None);
  base.pop('sweeps', None)
  cfg = base['calibrationConfig']
  for key, source in spec.benchmark.fixtureBindings.items(): cfg[key] = \
  trial['fixture'][source]
  cfg.update(spec.benchmark.fixedControl)
  if spec.benchmark.adapter == 'cache_jmh':
    cfg.setdefault('cacheScarcityGateEnabled', False)
    if trial['arm']['function'] is None: cfg['cacheScarcityGateEnabled'] = False
  pointer(cfg, spec.benchmark.functionPath, trial['arm']['function'], True)
  base['labels'] = dict(policyId=trial['arm']['policyId'],
                        workloadId=trial['fixture']['workloadId'],
                        passId=trial['blockId'], campaignId=campaign)
  base['origin'] = dict(type='SWEEP', sourceId=campaign, seed=spec.seed,
                        candidateIndex=0, sampleIndex=0)
  harness['id'] = campaign;
  harness['trials'] = [base]
  harness['runOptions'] = dict(balancedTrialOrder=False,
                               randomizeTrialOrder=False, failFast=True,
                               repeatCount=1)
  harness['artifacts'].update(outputDirectory=str(dest),
                              retainRawBenchmarkOutput=True,
                              retainExpandedConfig=True,
                              retainPerIterationResults=True)
  return harness


def parse_trial(spec, trial, attempt, campaign):
  dest = Path(attempt['directory']);
  logs = list(dest.rglob('benchmark_output.log'))
  if len(logs) != 1: raise ValueError('expected one raw benchmark output')
  configs = list(dest.rglob('trial_config.json'))
  if len(configs) != 1: raise ValueError('expected one expanded trial config')
  actual = json.loads(configs[0].read_text());
  expected = json.loads(Path(attempt['harness']).read_text())['trials'][0]
  for key in ['forks', 'iterations', 'warmups', 'measurementTime', 'warmupTime',
              'jvmArgs', 'calibrationConfig', 'labels']:
    if actual.get(key) != expected.get(key): raise ValueError(
      'trial configuration mismatch: ' + key)
  windows = measurement_windows(logs[0].read_text(), expected['iterations'])
  arm = trial['arm'];
  fn = arm['function'];
  fixture = trial['fixture']
  return dict(rowId=campaign + '/' + trial['id'], campaignId=campaign,
              policyId=arm['policyId'], originalPolicyId=arm['policyId'],
              function=fn, families=compatible_families(spec, fn),
              referencePolicyId=arm.get('referencePolicyId'),
              lineage=arm.get('lineage'), searchFamily=arm.get('family'),
              workloadId=fixture['workloadId'], blockId=trial['blockId'],
              forkId='0',
              rawThroughput=float(np.mean(windows)), windows=windows,
              config=actual['calibrationConfig'],
              provenance=dict(logPath=str(logs[0]), configPath=str(configs[0]),
                              command=attempt['command'],
                              attempt=attempt['number']))


def report_round(spec, records, campaign, dest, ranked):
  import csv
  rows = []
  incumbent_ids = [a['policyId'] for a in
                   json.loads((dest / 'arms.json').read_text()) if
                   a['role'] == 'measured_incumbent']
  for row in ranked:
    for fixture in spec.fixtures:
      wid = fixture['workloadId'];
      rs = [r for r in records if
            r['campaignId'] == campaign and r['policyId'] == row['policyId'] and
            r['workloadId'] == wid]
      off = [r for r in records if
             r['campaignId'] == campaign and r['policyId'] == row.get(
               'referencePolicyId', 'POLICY_OFF') and r[
               'workloadId'] == wid]
      passes = []
      for r in rs:
        baseline = [o for o in off if o['blockId'] == r['blockId']]
        matched = {pid: [q for q in records if
                         q['campaignId'] == campaign and q[
                           'policyId'] == pid and q['workloadId'] == wid and q[
                           'blockId'] == r['blockId']] for pid in incumbent_ids}
        passes.append(dict(block=r['blockId'], throughput=r['rawThroughput'],
                           changePercent=100 * (r['rawThroughput'] / np.mean(
                               [o['rawThroughput'] for o in baseline]) - 1),
                           windows=r['windows'],
                           incumbentComparisons={pid: dict(
                             referenceForkIds=[q['rowId'] for q in rr],
                             logReturn=math.log(r['rawThroughput'] / np.mean(
                                 [q['rawThroughput'] for q in rr]))) for pid, rr
                                                 in matched.items() if rr}))
      rows.append(dict(policy=row['policyId'],
                       referencePolicyId=row.get('referencePolicyId',
                                                 'POLICY_OFF'), workload=wid,
                       throughput=float(
        np.mean([r['rawThroughput'] for r in rs])),
                       changePercent=100 * math.expm1(
                           row['workloadReturns'][wid]),
                       minimumFork=min(r['rawThroughput'] for r in rs),
                       minimumWindow=min(min(r['windows']) for r in rs),
                       passes=json.dumps(passes)))
  with (dest / 'throughput.tsv').open('w') as f:
    writer = csv.DictWriter(f, fieldnames=list(rows[0]) if rows else ['status'],
                            delimiter='\t');
    writer.writeheader();
    writer.writerows(rows)
  atomic(dest / 'measured-ranking.json', ranked)


def load_task(path, overrides=None, smoke=False, max_rounds=None):
  raw = json.loads(path.read_text());
  root = (path.resolve().parent / raw.get('root', '.')).resolve()
  if smoke:
    changes = raw.get('smoke', {})
    raw['cleanup'] = dict(
        {'successfulRawTrials': False, 'retainFailedTrials': True},
        **changes.get('cleanup', {}))
    if 'offSentinel' in changes: raw['benchmark']['offSentinel'] = changes[
      'offSentinel']
    for key in ['fixtures', 'responses', 'models', 'preference']:
      if key in changes: raw[key] = changes[key]
    for key in ['search', 'budgets', 'validation']:
      raw[key] = {**raw.get(key, {}), **changes.get(key, {})}
    raw['benchmark']['blocks'] = changes.get('blocks', 1)
    base = raw['benchmark']['harness']['trials'][0]
    base.update(changes.get('trial', {}));
    base['calibrationConfig'].update(changes.get('calibration', {}))
  raw['execution'] = {**raw.get('execution', {}),
                      **{k: v for k, v in (overrides or {}).items() if
                         v is not None}}
  if max_rounds is not None: raw['budgets']['rounds'] = max_rounds
  spec = LoopTask.model_validate(raw)
  return spec, root


def run_task(path, output_override=None, dry_run=False, resume=False,
    max_rounds=None, smoke=False, execution_overrides=None):
  spec, root = load_task(Path(path), execution_overrides, smoke, max_rounds)
  output = Path(output_override).resolve() if output_override else root / (
        spec.outputDirectory + ('-smoke' if smoke else ''))
  if (output / 'static-frozen.json').exists():
    raise ValueError('static session is frozen historical evidence; create a new session for future work')
  new_count = spec.search.guided + spec.search.localRandom + spec.search.globalRandom
  forks_per_round = (new_count + spec.budgets.beamWidth + (
    0 if spec.benchmark.offSentinel else 1)) * len(
    spec.fixtures) * spec.benchmark.blocks + (
                      1 if spec.benchmark.offSentinel else 0)
  projection = forks_per_round * spec.budgets.rounds
  summary = dict(task=spec.id, smoke=smoke, rounds=spec.budgets.rounds,
                 newPoliciesPerRound=new_count,
                 maximumArms=1 + spec.budgets.beamWidth + new_count,
                 fixtures=len(spec.fixtures), blocks=spec.benchmark.blocks,
                 projectedMaximumJvmForks=projection,
                 projectedForksPerRound=forks_per_round,
                 resources=spec.execution.resolved(), output=str(output))
  if spec.benchmark.adapter == 'cache_jmh':
    from .cache_timing import resolve_topology
    identities = {}
    for fixture in spec.fixtures:
      key = tuple(fixture['cpuSet'])
      if key not in identities: identities[key] = resolve_topology(list(key))
      if identities[key]['resolvedWorkers'] != fixture[
        'resolvedWorkers']: raise ValueError('physical topology changed')
    if 0 not in os.sched_getaffinity(0): raise ValueError(
      'configured harness CPU unavailable')
    summary['physicalTopologies'] = list(identities.values())
  if dry_run:
    startup(spec, summary)
    return summary
  if output.exists() and not resume: raise ValueError(
    'output exists; use --resume or another directory')
  output.mkdir(parents=True, exist_ok=True)
  with (output / 'session.pid').open('a+') as lock:
    try:
      fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
      raise ValueError('this tuning session is already running')
    versions = dict(python=platform.python_version(),
                    platform=platform.platform(), packages={})
    for package in ['numpy', 'scipy', 'scikit-learn', 'joblib', 'torch',
                    'xgboost', 'catboost']:
      with contextlib.suppress(metadata.PackageNotFoundError):
        versions['packages'][package] = metadata.version(package)
    revision = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root,
                              capture_output=True, text=True)
    versions[
      'gitRevision'] = revision.stdout.strip() if revision.returncode == 0 else None
    atomic(output / ('versions-resume.json' if resume else 'versions.json'),
           versions)
    from .policy_freeze import open_session_store
    store_path = (root / spec.storePath).resolve() if spec.storePath and not smoke else output / 'forks.sqlite'
    store = open_session_store(output, store_path, resume)
    state_path = output / 'state.json'
    if resume and not state_path.exists(): raise ValueError(
      'no session state to resume')
    state = json.loads(state_path.read_text()) if state_path.exists() else dict(
      phase='INGEST', round=0, attempts=0,
      built=False, smoke=smoke, sessionId=uuid.uuid4().hex[:12],
      elapsedSeconds=0, consecutiveFailures=0, regions=[],
      historyImported=False, stagnation=0)
    state.setdefault('sessionId', output.name)
    if state['phase'] == 'COMPLETE' and state['round'] < spec.budgets.rounds:
      state['phase'] = 'FIT';
      state.pop('stopReason', None)
    if state['smoke'] != smoke: raise ValueError(
      'smoke and real history must use separate sessions')
    # Session task inputs stay immutable; no checksums, source allowlists or historical locks.
    task_path = output / 'task.json'
    if task_path.exists():
      prior = json.loads(task_path.read_text());
      now = spec.model_dump(mode='json')
      for key in ['execution', 'budgets', 'cleanup', 'storePath']: prior.pop(key,
                                                                None);now.pop(
        key,
                                                                        None)
      prior.get('search', {}).pop('stagnationPatience', None)
      if prior != now: raise ValueError(
        'task semantics changed; start a separate session')
    else:
      atomic(task_path, spec.model_dump(mode='json'))
    startup(spec, summary, state, len(store.rows()), resume)
    progress = Progress(spec, state)
    started = time.monotonic();
    base_elapsed = state['elapsedSeconds']

    def save():
      state['elapsedSeconds'] = base_elapsed + time.monotonic() - started;
      atomic(state_path, state)

    def check_failures():
      if state['consecutiveFailures'] >= spec.budgets.consecutiveFailures:
        raise OperationalStop('consecutive failure limit reached')

    oldterm = signal.getsignal(signal.SIGTERM)

    def interrupted(*args):
      raise KeyboardInterrupt()

    signal.signal(signal.SIGTERM, interrupted)
    try:
      save()
      # Finish/retry post-commit reporting and cleanup without re-reading deleted raw logs.
      for report_path in sorted(output.glob('round-*/round-summary.json')):
        recovered = json.loads(report_path.read_text())
        if recovered['roundIndex'] < state['round']:
          try:
            cleanup_round(spec, report_path.parent, recovered, store.rows(),
                          state['round'], atomic)
          except OSError as error:
            print(f'Cleanup warning: {error}', flush=True)
          progress.detail(
            f"Round {recovered['round']} cleanup: {recovered['cleanup']['status']}")
          for error in recovered['cleanup'].get('errors', []):
            progress.detail('Cleanup warning: ' + error)
          publish_current(output, recovered, atomic)
      if list(output.glob('round-*/round-summary.json')):
        leaderboard(spec, output)
      while state['round'] < spec.budgets.rounds:
        check_failures();
        index = state['round'];
        directory = output / f'round-{index:03d}';
        directory.mkdir(exist_ok=True)
        campaign = spec.id + '-' + state['sessionId'] + (
          '-smoke' if smoke else '') + f'-{index:03d}'
        if state['phase'] == 'INGEST':
          progress.phase('INGEST')
          phase_started = time.monotonic()
          if not state['historyImported']:
            # Smoke reduces runtime settings only. Import history under the normal compatibility contract.
            history_spec, _ = load_task(Path(path), execution_overrides, False,
                                        max_rounds)
            audit = ingest(history_spec, root, store);
            atomic(output / 'history-audit.json', audit);
            state['historyImported'] = True;
            save()
          state['regions'] = seed_regions(spec, store.rows());
          state['bestObserved'] = measured_scores(spec, store.rows())
          if spec.preference.objective == 'scarce':
            from .loop_references import elite_archive
            state['elites'] = elite_archive(spec, store.rows())[
              :spec.budgets.eliteSize]
            atomic(output / 'elite-archive.json', state['elites'])
          atomic(output / 'initial-regions.json', state['regions'])
          progress.detail(
            f'Imported {len(store.rows())} historical/session forks')
          progress.phase_done('INGEST', time.monotonic() - phase_started)
          state['phase'] = 'FIT';
          save()
        if state['phase'] == 'FIT':
          progress.phase('FIT')
          phase_started = time.monotonic()
          report = run_offline('pareto_weight_calibration.loop_search', 'fit',
                               spec, store.rows(), directory / 'fit',
                               progress=progress.detail);
          atomic(directory / 'fit-summary.json', report)
          progress.phase_done('FIT', time.monotonic() - phase_started)
          state['phase'] = 'PROPOSE';
          save()
        if state['phase'] == 'PROPOSE':
          progress.phase('PROPOSE')
          phase_started = time.monotonic()
          if (directory / 'proposals.json').exists():
            proposal = json.loads((directory / 'proposals.json').read_text())
          else:
            proposal = run_offline('pareto_weight_calibration.loop_search', 'propose',
                                   spec, store.rows(), state['regions'], directory,
                                   index, progress=progress.detail);
            atomic(directory / 'proposals.json', proposal)
          arms = [
            dict(policyId='POLICY_OFF', function=None, role='fixed_production')]
          arms.extend(
              dict(r, role='measured_incumbent') for r in state['regions'])
          arms.extend(proposal['candidates']);
          arms = list({a['policyId']: a for a in arms}.values())
          if spec.benchmark.referenceMode == 'incumbent':
            for arm in arms:
              if arm['function'] is not None: arm['referencePolicyId'] = \
              state['regions'][0]['policyId']
          atomic(directory / 'arms.json', arms)
          state['trials'] = trial_plan(spec, arms, directory, index);
          atomic(directory / 'trial-plan.json',
                 dict(round=index + 1, campaignId=campaign,
                      benchmark=spec.benchmark.model_dump(mode='json'),
                      trials=state['trials']))
          progress.detail(
            f"Selected {len(proposal['candidates'])} new policies; {len(state['trials'])} trials in this round")
          progress.phase_done('PROPOSE', time.monotonic() - phase_started)
          state['phase'] = 'BENCHMARK';
          save()
        if state['phase'] == 'BENCHMARK':
          progress.phase('BENCHMARK')
          phase_started = time.monotonic()
          # No fit/prediction pool is alive here. Build and all JMH trials are serial.
          if spec.benchmark.adapter == 'cache_jmh':
            from .cache_timing import resolve_topology
            for f in spec.fixtures:
              topology = resolve_topology(f['cpuSet'])
              if topology['resolvedWorkers'] != f[
                'resolvedWorkers']: raise ValueError(
                'physical topology changed')
          if not state['built'] and spec.benchmark.buildCommand:
            command = list(spec.benchmark.buildCommand);
            atomic(output / 'build-command.json', command)
            progress.detail(
              'Building benchmark distribution once for this session')
            if owned_command(command, root, output / 'build.log',
                             None,
                             heartbeat=lambda elapsed: progress.detail(
                                 f'Benchmark build running: {elapsed:.0f}s | ETA estimating')) != 0: raise RuntimeError(
              'benchmark distribution build failed; see build.log')
            state['built'] = True;
            save()
          for trial_number, trial in enumerate(state['trials'], 1):
            if trial['status'] == 'COMPLETE': continue
            # A terminated launcher may have completed its child before the parent saved state.
            if trial['attempts']:
              last = trial['attempts'][-1]
              if last['status'] in ['RUNNING', 'INTERRUPTED', 'FINISHED']:
                try:
                  row = parse_trial(spec, trial, last, campaign);
                  store.append([row]);
                  last['status'] = 'COMPLETE';
                  trial['status'] = 'COMPLETE';
                  save();
                  progress.trial(trial_number, trial, 'COMPLETE (recovered)',
                                 last, last.get('elapsedSeconds'))
                  continue
                except ValueError:
                  last['status'] = 'INTERRUPTED';save()
            while trial['status'] != 'COMPLETE':
              check_failures()
              if len(trial[
                       'attempts']) >= spec.budgets.attemptsPerTrial: raise RuntimeError(
                'trial attempts exhausted: ' + trial['id'])
              attemptdir = directory / 'trials' / trial[
                'id'] / f'attempt-{len(trial["attempts"])}';
              attemptdir.mkdir(parents=True, exist_ok=True)
              hp = attemptdir / 'harness.json';
              atomic(hp,
                     harness_for(spec, trial, attemptdir / 'output', campaign))
              command = [s.replace('{harness}', str(hp)) for s in
                         spec.benchmark.command]
              attempt = dict(number=len(trial['attempts']),
                             directory=str(attemptdir / 'output'),
                             harness=str(hp), command=command, status='RUNNING')
              trial['attempts'].append(attempt);
              state['attempts'] += 1;
              save()
              attempt_started = time.monotonic()
              progress.trial(trial_number, trial,
                             'RETRY' if attempt['number'] else 'START', attempt)
              try:
                rc = owned_command(command, root, attemptdir / 'launcher.log',
                                   spec.budgets.trialTimeoutSeconds,
                                   heartbeat=lambda elapsed: progress.trial(
                                     trial_number, trial, 'RUNNING', attempt,
                                     elapsed))
                attempt['exitCode'] = rc;
                attempt['status'] = 'FINISHED';
                save()
                if rc: raise ValueError(f'launcher exited {rc}')
                row = parse_trial(spec, trial, attempt, campaign);
                store.append([row]);
                trial['status'] = 'COMPLETE';
                attempt['status'] = 'COMPLETE';
                state['consecutiveFailures'] = 0
                attempt['elapsedSeconds'] = time.monotonic() - attempt_started
                progress.attempt_done(attempt['elapsedSeconds'])
                progress.trial(trial_number, trial, 'COMPLETE', attempt,
                               attempt['elapsedSeconds'])
              except KeyboardInterrupt:
                attempt['status'] = 'INTERRUPTED';
                attempt['elapsedSeconds'] = time.monotonic() - attempt_started
                progress.trial(trial_number, trial, 'INTERRUPTED', attempt,
                               attempt['elapsedSeconds'])
                save();
                raise
              except (ValueError, subprocess.TimeoutExpired, OSError) as error:
                attempt['status'] = 'FAILED';
                attempt['error'] = str(error);
                state['consecutiveFailures'] += 1
                attempt['elapsedSeconds'] = time.monotonic() - attempt_started
                progress.attempt_done(attempt['elapsedSeconds'])
                progress.trial(trial_number, trial, 'FAILED', attempt,
                               attempt['elapsedSeconds'], error)
              save()
          progress.phase_done('BENCHMARK', time.monotonic() - phase_started)
          state['phase'] = 'COLLECT';
          save()
        if state['phase'] == 'COLLECT':
          progress.phase('COLLECT')
          phase_started = time.monotonic()
          # Idempotent even after a crash between SQLite commit and state save.
          for trial in state['trials']: store.append(
              [parse_trial(spec, trial, trial['attempts'][-1], campaign)])
          atomic(directory / 'forks.json',
                 [r for r in store.rows() if r['campaignId'] == campaign])
          atomic(directory / 'trial-plan.json',
                 dict(round=index + 1, campaignId=campaign,
                      benchmark=spec.benchmark.model_dump(mode='json'),
                      trials=state['trials']))
          progress.phase_done('COLLECT', time.monotonic() - phase_started)
          state['phase'] = 'UPDATE';
          save()
        if state['phase'] == 'UPDATE':
          progress.phase('UPDATE')
          phase_started = time.monotonic()
          records = store.rows();
          previous_regions = deepcopy(state['regions'])
          regions, improved, ranked = update_regions(spec, state['regions'],
                                                     records, campaign,
                                                     json.loads((
                                                                      directory / 'arms.json').read_text()))
          next_regions = regions
          next_stagnation = 0 if improved else state['stagnation'] + 1
          report_round(spec, records, campaign, directory, ranked)
          atomic(directory / 'update.json',
                 dict(improved=improved, regions=regions,
                      stagnation=next_stagnation,
                      searchUpdates=[r.get('searchUpdate') for r in regions]))
          if next_stagnation and next_stagnation % spec.search.restartPatience == 0:
            # Bounded restart from another measured region, never from an optimistic prediction.
            alternates = seed_regions(spec, records,
                                      exclude=[r['policyId'] for r in regions])
            for r in alternates:
              if not any(c['policyId'] == r['policyId'] for c in regions):
                from .loop_references import radius_update
                r['radius'] = spec.search.newBasinRadius
                r['searchUpdate'] = dict(radius_update(spec, r),
                                         decision='ALTERNATE_BASIN',
                                         reason='bounded restart from measured archive')
                next_regions = (regions[:spec.budgets.beamWidth - 1] + [r]);
                break
          report = round_summary(spec, index, campaign, ranked,
                                 previous_regions,
                                 regions, next_regions, improved,
                                 next_stagnation)
          if spec.preference.objective == 'scarce':
            from .loop_references import elite_archive
            state['elites'] = elite_archive(spec, records)[
              :spec.budgets.eliteSize]
            atomic(output / 'elite-archive.json', state['elites'])
            report['eliteArchive'] = [{k: v for k, v in e.items() if
                                       k not in ['function', 'workloadReturns',
                                                 'targets']} for e in
                                      state['elites']]
          report['searchUpdates'] = [r.get('searchUpdate') for r in regions if
                                     r.get('searchUpdate')] + [r['searchUpdate']
                                                               for r in
                                                               next_regions if
                                                               r[
                                                                 'policyId'] not in {
                                                                 c['policyId']
                                                                 for c in
                                                                 regions}]
          atomic(directory / 'update.json',
                 dict(improved=improved, regions=regions,
                      nextRegions=next_regions, stagnation=next_stagnation,
                      searchUpdates=report['searchUpdates']))
          sentinel = next((r for r in records if
                           r['campaignId'] == campaign and r[
                             'blockId'] == 'sentinel' and r[
                             'function'] is None), None)
          if sentinel:
            previous = state.get('offSentinel')
            report['offSentinel'] = dict(workloadId=sentinel['workloadId'],
                                         throughput=sentinel['rawThroughput'],
                                         previousThroughput=previous[
                                           'throughput'] if previous else None,
                                         changePercent=100 * (
                                               sentinel['rawThroughput'] /
                                               previous[
                                                 'throughput'] - 1) if previous else None)
            state['offSentinel'] = report['offSentinel']
          persist_summary(directory, report, atomic)
          publish_current(output, report, atomic)
          leaderboard(spec, output)
          progress.phase_done('UPDATE', time.monotonic() - phase_started)
          # Commit the unchanged optimizer decision after its compact reports are durable.
          state['regions'] = next_regions
          state['stagnation'] = next_stagnation
          state.setdefault('bestObserved', []).extend(ranked)
          state['round'] += 1;
          state['phase'] = 'FIT';
          save()
          try:
            cleanup_round(spec, directory, report, records, state['round'],
                          atomic)
          except OSError as error:
            print(f'Cleanup warning (collected evidence retained): {error}',
                  flush=True)
          print(summary_text(report), flush=True)
          if spec.search.stopPatience and next_stagnation >= spec.search.stopPatience and all(
              r['radius'] <= spec.search.minimumRadius for r in next_regions):
            state['stopReason'] = 'measured stagnation at minimum search radii'
            break
      state.setdefault('stopReason', 'round budget completed');
      state['phase'] = 'COMPLETE';
      save()
    except OperationalStop as error:
      state['stopReason'] = str(error);
      save()
    except BaseException as error:
      state['lastError'] = type(error).__name__ + ': ' + str(error);
      save();
      raise
    finally:
      signal.signal(signal.SIGTERM, oldterm);
      store.close()
    atomic(output / 'best-measured.json',
           dict(regions=state['regions'], archive=state.get('bestObserved', []),
                productionChanged=False))
    print(
      f"Tuner stopped: {state.get('stopReason')}; completed rounds={state['round']}/{spec.budgets.rounds}; attempts={state['attempts']}",
      flush=True)
    return dict(summary, state=str(state_path),
                stopReason=state.get('stopReason'),
                completedRounds=state['round'], attempts=state['attempts'])
