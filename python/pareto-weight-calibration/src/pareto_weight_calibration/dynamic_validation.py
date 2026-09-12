"""Bounded validation of a frozen function using continuous JMH phase windows; no fitting."""
from collections import defaultdict
from copy import deepcopy
from pathlib import Path
import argparse
import contextlib
import csv
import json
import math
import os
import statistics
import subprocess

from .cache_timing_confirmation import measurement_windows
from .loop_data import ForkStore, policy_id
from .parameter_loop import atomic, load_task, owned_command
from .policy_freeze import lookup_policy, verify_database_copy

SCHEDULE_PROPERTY = '-Deuhedral.calibration.dynamicSchedule='
ARMS = ('POLICY_OFF', 'FROZEN_POLICY')


def read(path):
  return json.loads(Path(path).read_text())


def panel(config):
  fixtures = []
  n = config['windowsPerPhase']
  for r in config['topologies']:
    for kind, states in [('gate', [(0, 1), (0, r), (0, 1)]),
                         ('body', [(0, 1), (576, 1), (0, 1)])]:
      fixtures.append(dict(id=f'R{r}-{kind}-return', workers=r, kind=kind,
                           phases=[dict(name=f'{kind}-{i}', windows=n, workUnits=w, enabledSources=s)
                                   for i, (w, s) in enumerate(states)]))
  r = config['scarcityTopology']
  fixtures.append(dict(id=f'R{r}-scarcity-return', workers=r, kind='scarcity',
                       phases=[dict(name=f'scarcity-{i}', windows=n, workUnits=0, enabledSources=s)
                               for i, s in enumerate([1, config['lessScarceSources'], 1])]))
  if any(p['enabledSources'] >= r for p in fixtures[-1]['phases']):
    raise ValueError('within-scarce fixture must remain scarce')
  if n < config['steadyWindows'] + config['recovery']['consecutiveWindows']:
    raise ValueError('phase needs distinct recovery and steady windows')
  if not 0 < config['recovery']['tolerancePercent'] < 100:
    raise ValueError('invalid recovery tolerance')
  if config['forksPerArm'] < 2 or not 12 <= len(fixtures) * 2 <= 24:
    raise ValueError('require replicated bounded panel of 12-24 fixture/arm combinations')
  return fixtures


def verify_frozen(config, root):
  frozen = read(root / config['policyArtifact'])
  if frozen['policyId'] != config['policyId']:
    raise ValueError('configured policy differs from frozen policy')
  spec, _ = load_task(root / config['staticTask'])
  migration = verify_database_copy(root / config['sourceDatabase'], root / frozen['canonicalStaticDatabase'])
  with contextlib.closing(ForkStore(root / frozen['canonicalStaticDatabase'])) as store:
    actual = lookup_policy(spec, store.rows(), config['policyId'])
  for k in ['policyId', 'family', 'activeParameters', 'runtimeTimingFunction', 'staticEvidence']:
    if frozen[k] != actual[k]:
      raise ValueError('frozen artifact differs from database: ' + k)
  if read(root / frozen['runtimeArtifact']) != actual['runtimeTimingFunction']:
    raise ValueError('runtime-only function differs from database')
  print(json.dumps(dict(migration, frozenPolicyId=actual['policyId'], **actual['staticEvidence']), indent=2), flush=True)
  return frozen, spec, migration


def prepare(config, root, output):
  frozen, spec, migration = verify_frozen(config, root)
  fixtures = panel(config)
  from .cache_timing import resolve_topology
  topologies = {}
  for r in {f['workers'] for f in fixtures}:
    cpus = next(f['cpuSet'] for f in spec.fixtures if f['resolvedWorkers'] == r)
    topologies[str(r)] = resolve_topology(cpus)
    if topologies[str(r)]['resolvedWorkers'] != r or 0 not in os.sched_getaffinity(0):
      raise ValueError('required physical topology/harness CPU unavailable')
  template = deepcopy(spec.benchmark.harness['trials'][0])
  trials = []
  # Adjacent matched arms, reversing order each independent replicate.
  for fork in range(config['forksPerArm']):
    for fixture in fixtures:
      for arm in ARMS if fork % 2 == 0 else ARMS[::-1]:
        trial = deepcopy(template)
        cfg = trial['calibrationConfig']
        cfg.update(cpuSet=next(f['cpuSet'] for f in spec.fixtures if f['resolvedWorkers'] == fixture['workers']),
                   parallelSources=max(p['enabledSources'] for p in fixture['phases']), orderedSources=0,
                   workUnits=fixture['phases'][0]['workUnits'], lifecycleMode='CONTINUOUS',
                   cacheTimingFunction=deepcopy(frozen['runtimeTimingFunction']) if arm == 'FROZEN_POLICY' else None,
                   cacheScarcityGateEnabled=True, **spec.benchmark.fixedControl)
        trial_id = f"{fixture['id']}-{arm}-fork-{fork}"
        trial.update(id=trial_id, forks=1, warmups=config['warmups'],
                     iterations=sum(p['windows'] for p in fixture['phases']),
                     warmupTime=config['warmupTime'], measurementTime=config['measurementTime'],
                     origin=None, labels=dict(policyId=config['policyId'] if arm == 'FROZEN_POLICY' else arm,
                                             mode=arm, workloadId=fixture['id'], workloadClass='DYNAMIC', fork=str(fork)))
        trial['jvmArgs'] = [a for a in template['jvmArgs'] if not a.startswith((SCHEDULE_PROPERTY,
                                          '-Deuhedral.calibration.participationDynamicScenario='))]
        trial['jvmArgs'].append(SCHEDULE_PROPERTY + json.dumps(dict(phases=fixture['phases']), separators=(',', ':')))
        trials.append(trial)
  manifest = dict(schemaVersion=1, recordType='dynamic_validation', policyId=frozen['policyId'],
                  config=deepcopy(config), fixtures=fixtures, topologies=topologies, trials=trials,
                  independentJvmForks=len(trials), fixtureArmCombinations=len(fixtures) * 2,
                  independentUnit='JVM fork; windows nested, matched by fixture and replicate block',
                  migrationVerification=migration)
  if output.exists():
    if not (output / 'manifest.json').exists() or read(output / 'manifest.json') != manifest:
      raise ValueError('output exists with different/incomplete inputs; choose a new output directory')
  else:
    output.mkdir(parents=True)
    atomic(output / 'manifest.json', manifest)
    atomic(output / 'frozen-policy.json', frozen)
    atomic(output / 'POLICY_OFF.json', dict(policyId='POLICY_OFF', cacheTimingFunction=None,
           cacheScarcityGateEnabled=True, **spec.benchmark.fixedControl))
  seconds = len(trials) * (config['warmups'] * duration(config['warmupTime']) +
                         len(fixtures[0]['phases']) * config['windowsPerPhase'] * duration(config['measurementTime']))
  print(f"Dynamic panel: {len(fixtures)} fixtures, {len(fixtures)*2} fixture/arm combinations, "
        f"{len(trials)} independent JVM forks; {seconds/60:.1f} minutes timed windows plus JVM/setup overhead.", flush=True)
  return manifest


def duration(value):
  if value.endswith('ms'): return float(value[:-2]) / 1000
  if value.endswith('s'): return float(value[:-1])
  raise ValueError('duration must use ms or s')


def parse_windows(text, fixture):
  values = measurement_windows(text, sum(p['windows'] for p in fixture['phases']))
  rows = []
  window = 0
  for phase_index, phase in enumerate(fixture['phases']):
    for phase_window in range(1, phase['windows'] + 1):
      rows.append(dict(recordType='dynamic_window', window=window + 1, phase=phase_index,
                       phaseWindow=phase_window, workUnits=phase['workUnits'], enabledSources=phase['enabledSources'],
                       throughput=values[window]))
      window += 1
  return rows


def recovery(values, config):
  target = statistics.mean(values[-config['steadyWindows']:])
  tolerance = config['recovery']['tolerancePercent'] / 100
  consecutive = config['recovery']['consecutiveWindows']
  if consecutive < 1: raise ValueError('positive recovery window count required')
  for end in range(consecutive, len(values) + 1):
    if all(abs(v / target - 1) <= tolerance for v in values[end-consecutive:end]):
      return end
  return None


def trial_windows(output, trial, fixture):
  directory = output / 'runs' / trial['id']
  paths = list(directory.glob('output/*/benchmark_output.log'))
  if len(paths) != 1:
    raise ValueError('expected exactly one retained JVM log: ' + trial['id'])
  resolved = read(paths[0].parent / 'trial_config.json')
  # Runner materializes defaults; verify the treatment fields and exact schedule independently.
  for key, value in trial['calibrationConfig'].items():
    if resolved['calibrationConfig'].get(key) != value:
      raise ValueError('resolved treatment differs: ' + key)
  if not set(trial['jvmArgs']) <= set(resolved['jvmArgs']):
    raise ValueError('resolved JVM schedule differs')
  for key in ['forks', 'iterations', 'warmups', 'warmupTime', 'measurementTime']:
    if resolved[key] != trial[key]: raise ValueError('resolved measurement contract differs: ' + key)
  return parse_windows(paths[0].read_text(), fixture)


def run(config, root, output):
  manifest = prepare(config, root, output)
  fixtures = {f['id']: f for f in manifest['fixtures']}
  identity = output / 'runtime-identity.json'
  current = dict(java=subprocess.check_output(['mise', 'exec', '--', 'java', '-version'], cwd=root,
                                             stderr=subprocess.STDOUT, text=True),
                 revision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip())
  if not identity.exists(): atomic(identity, current)
  for i, trial in enumerate(manifest['trials']):
    directory = output / 'runs' / trial['id']
    if (directory / 'complete.json').exists():
      trial_windows(output, trial, fixtures[trial['labels']['workloadId']])
      print(f"Verified completed fork {i+1}/{len(manifest['trials'])}: {trial['id']}", flush=True)
      continue
    if directory.exists():
      raise ValueError('incomplete attempt preserved; use a new output for rerun: ' + str(directory))
    harness = dict(schemaVersion=1, id=trial['id'],
                   runOptions=dict(randomizeTrialOrder=False, failFast=True, repeatCount=1),
                   artifacts=dict(outputDirectory=str(directory / 'output'), retainExpandedConfig=True,
                                  retainRawBenchmarkOutput=True, retainObserverData=False,
                                  retainPerForkResults=True, retainPerIterationResults=True), trials=[trial])
    atomic(directory / 'harness.json', harness)
    print(f"Running dynamic fork {i+1}/{len(manifest['trials'])}: {trial['id']}", flush=True)
    command = ['mise', 'exec', '--', 'bash', 'benchmarks/build/bin/euhedral-calibration',
               'run', str(directory / 'harness.json')]
    rc = owned_command(command, root, directory / 'launcher.log', config['trialTimeoutSeconds'],
                       heartbeat=lambda elapsed: print(f"  {trial['id']}: {elapsed:.0f}s", flush=True))
    if rc: raise ValueError(f"dynamic launcher failed ({rc}): {directory}")
    values = trial_windows(output, trial, fixtures[trial['labels']['workloadId']])
    atomic(directory / 'complete.json', dict(independentForks=1, windows=len(values)))
  collect(output)


def tsv(path, rows):
  with path.open('w') as f:
    writer = csv.DictWriter(f, fieldnames=list(rows[0]), delimiter='\t')
    writer.writeheader()
    writer.writerows(rows)


def compare(manifest, fork_windows):
  config = manifest['config']
  windows, phases, forks, transitions = [], [], [], []
  pct = lambda a, b: 100 * (a / b - 1)
  for fixture in manifest['fixtures']:
    fid = fixture['id']
    for fork in range(config['forksPerArm']):
      arm_values = {arm: fork_windows[fid, fork, arm] for arm in ARMS}
      for arm in ARMS:
        vv = arm_values[arm]
        if len(vv) != sum(p['windows'] for p in fixture['phases']):
          raise ValueError('incomplete nested window trajectory')
        identity = dict(fixture=fid, fork=fork, mode=arm)
        off = arm_values['POLICY_OFF']
        rows = [dict(identity, **r, deltaVsOffPercent=pct(r['throughput'], b['throughput'])) for r, b in zip(vv, off)]
        if any((r['phase'], r['phaseWindow']) != (b['phase'], b['phaseWindow']) for r, b in zip(vv, off)):
          raise ValueError('POLICY_OFF windows are not matched')
        windows.extend(rows)
        steady = []
        for phase, stimulus in enumerate(fixture['phases']):
          current = [r for r in rows if r['phase'] == phase]
          values = [r['throughput'] for r in current]
          baselines = [r['throughput'] for r in off if r['phase'] == phase]
          target = statistics.mean(values[-config['steadyWindows']:])
          base_target = statistics.mean(baselines[-config['steadyWindows']:])
          steady.append(target)
          row = dict(identity, phase=phase, workUnits=stimulus['workUnits'], enabledSources=stimulus['enabledSources'],
                     firstThroughput=values[0], minimumThroughput=min(values), steadyThroughput=target,
                     firstDeltaVsOffPercent=current[0]['deltaVsOffPercent'],
                     minimumWindowDeltaVsOffPercent=min(r['deltaVsOffPercent'] for r in current),
                     steadyDeltaVsOffPercent=pct(target, base_target), recoveredByWindow=recovery(values, config),
                     recoverySeconds=None, overshootPercent=pct(max(values), target),
                     withinPhaseCv=statistics.pstdev(values)/statistics.mean(values),
                     windowThroughputs=json.dumps(values))
          row['recoverySeconds'] = row['recoveredByWindow'] * duration(config['measurementTime']) if row['recoveredByWindow'] else None
          phases.append(row)
          if phase:
            prior = fixture['phases'][phase-1]
            kind = ('low body -> high body' if stimulus['workUnits'] > prior['workUnits'] else 'high body -> low body') if fixture['kind'] == 'body' else (
                ('scarce -> plentiful' if stimulus['enabledSources'] >= fixture['workers'] else 'plentiful -> scarce') if fixture['kind'] == 'gate' else
                ('very scarce -> less scarce' if stimulus['enabledSources'] > prior['enabledSources'] else 'less scarce -> very scarce'))
            transitions.append(dict(row, transition=kind, preTransitionSteadyThroughput=steady[-2],
                                    firstVsOwnPrePercent=pct(values[0], steady[-2]),
                                    steadyVsOwnPrePercent=pct(target, steady[-2]),
                                    returnVsInitialPercent=pct(target, steady[0]) if phase == len(fixture['phases'])-1 else None))
        forks.append(dict(identity, recordType='dynamic_fork', nestedWindows=len(vv),
                          initialSteadyThroughput=steady[0], returnSteadyThroughput=steady[-1],
                          returnVsInitialPercent=pct(steady[-1], steady[0])))
  return windows, phases, forks, transitions


def collect(output):
  manifest = read(output / 'manifest.json')
  frozen = read(output / 'frozen-policy.json')
  original = deepcopy(frozen['runtimeTimingFunction'])
  fixtures = {f['id']: f for f in manifest['fixtures']}
  values = {}
  for trial in manifest['trials']:
    label = trial['labels']
    key = label['workloadId'], int(label['fork']), label['mode']
    if key in values: raise ValueError('duplicate independent fork identity')
    values[key] = trial_windows(output, trial, fixtures[label['workloadId']])
  windows, phases, forks, transitions = compare(manifest, values)
  for name, rows in [('per-window.tsv', windows), ('per-phase.tsv', phases),
                     ('per-fork.tsv', forks), ('transition-summary.tsv', transitions)]:
    tsv(output / name, rows)
  grouped = defaultdict(list)
  for t in transitions:
    if t['mode'] == 'FROZEN_POLICY': grouped[t['fixture'], t['transition']].append(t)
  summaries = []
  for (fixture, transition), rows in grouped.items():
    recoveries = [r['recoveredByWindow'] for r in rows]
    summaries.append(dict(fixture=fixture, transition=transition, independentFrozenForks=len(rows),
                          immediateDeltaPercent=statistics.mean(r['firstDeltaVsOffPercent'] for r in rows),
                          minimumDeltaPercent=min(r['minimumWindowDeltaVsOffPercent'] for r in rows),
                          recoveredByWindows=recoveries,
                          postSteadyDeltaPercent=statistics.mean(r['steadyDeltaVsOffPercent'] for r in rows),
                          steadyDeltaPerFork=[r['steadyDeltaVsOffPercent'] for r in rows],
                          returnVsInitialPerFork=[r['returnVsInitialPercent'] for r in rows],
                          overshootPerFork=[r['overshootPercent'] for r in rows]))
  review = read(output / 'review.json') if (output / 'review.json').exists() else dict(result='NEEDS FOLLOW-UP',
          findings='Descriptive review pending. No automatic production gate or aggregate winner score.')
  summary = dict(policyId=frozen['policyId'], staticEstimate=frozen['staticEvidence'],
                 dynamicResult=review['result'], review=review, independentJvmForks=len(forks),
                 nestedWindows=len(windows), transitions=summaries,
                 limitations=['Throughput-only windows do not directly measure CPU participation, gate state or control chatter.',
                              'Recovery is relative to the final phase tail, so it does not by itself exclude persistent deficits vs OFF.',
                              'Window resolution is configured measurement time; sub-window transients and JMH inter-window gaps are unresolved.'])
  if frozen['runtimeTimingFunction'] != original or policy_id(original) != frozen['policyId']:
    raise ValueError('frozen function changed during dynamic analysis')
  atomic(output / 'dynamic-summary.json', summary)
  lines = ['FROZEN POLICY', '  ' + frozen['policyId'], '', 'STATIC ESTIMATE']
  lines += [f'  {k}: {v:+.3f}%' for k, v in frozen['staticEvidence']['topologyPercent'].items()]
  lines += [f"  broad scarce: {frozen['staticEvidence']['broadScarcePercent']:+.3f}%", '',
            'DYNAMIC RESULT', '  ' + review['result'], '', str(review['findings']), '',
            f"{len(forks)} independent JVM forks; {len(windows)} nested windows. Deltas match fixture, replicate block and window against POLICY_OFF.", '',
            '| Fixture | Transition | Immediate vs OFF | Worst window vs OFF | Recovered by window (forks) | Steady vs OFF |',
            '|---|---|---:|---:|---|---:|']
  for s in summaries:
    lines.append(f"| {s['fixture']} | {s['transition']} | {s['immediateDeltaPercent']:+.2f}% | {s['minimumDeltaPercent']:+.2f}% | {s['recoveredByWindows']} | {s['postSteadyDeltaPercent']:+.2f}% |")
  lines += ['', f"Recovery: within {manifest['config']['recovery']['tolerancePercent']}% of final "
            f"{manifest['config']['steadyWindows']}-window mean for {manifest['config']['recovery']['consecutiveWindows']} consecutive windows. "
            'Reported window is the end of the qualifying run, counted from 1 after the transition.', '',
            'Repeated transition return relative to initial steady state (per independent fork):', '']
  for row in forks:
    lines.append(f"- {row['fixture']} {row['mode']} fork {row['fork']}: {row['returnVsInitialPercent']:+.2f}%")
  lines += ['', *summary['limitations'], '']
  (output / 'dynamic-summary.md').write_text('\n'.join(lines))
  print('\n'.join(lines[:14]), flush=True)
  return summary


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument('command', choices=['prepare', 'run', 'collect'])
  parser.add_argument('--config', type=Path, required=True)
  parser.add_argument('--output', type=Path)
  args = parser.parse_args()
  config = read(args.config)
  root = (args.config.resolve().parent / config['root']).resolve()
  output = args.output.resolve() if args.output else root / config['outputDirectory']
  if args.command == 'prepare': prepare(config, root, output)
  elif args.command == 'run': run(config, root, output)
  else: collect(output)


if __name__ == '__main__':
  main()
