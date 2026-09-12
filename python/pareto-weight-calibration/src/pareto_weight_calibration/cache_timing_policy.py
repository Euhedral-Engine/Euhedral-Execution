"""Prepare, verify, launch explicitly, or collect the frozen live timing campaign."""
from __future__ import annotations

import argparse
from copy import deepcopy
import csv
import json
import math
from importlib.metadata import version
import os
from pathlib import Path
import statistics
import subprocess

from .cache_timing import ROOT, SOURCES, WORK, SEED, normalization, write, sha, \
  resolve_topology
from .cache_timing_evidence import binaries, windows
from .cache_timing_surfaces import BASIS, select_surfaces
from .cache_timing_comparator import compare
from .runtime_export import render_cache_timing
from .training_spec import TrainingTaskSpec, digest
from .task_adapters import load_dataset

CONTROL_PAIRS = [(15000, 1000000), (500000, 1000000), (814375, 2000000),
                 (125000, 500000), (15000, 250000)]


def source_hashes():
  paths = []
  for module in ('euhedral-core', 'euhedral-hardware-utils',
                 'euhedral-data-structures',
                 'euhedral-hashing', 'euhedral-reactor-core', 'benchmarks'):
    paths += list((ROOT / module / 'src/main/java').rglob('*.java'))
    paths += [ROOT / module / 'build.gradle.kts']
  paths += list((
                      ROOT / 'python/pareto-weight-calibration/src/pareto_weight_calibration').rglob(
    '*.py'))
  paths += [ROOT / 'python/pareto-weight-calibration/pyproject.toml',
            ROOT / 'build.gradle.kts', ROOT / 'settings.gradle.kts',
            ROOT / 'gradle/libs.versions.toml',
            ROOT / 'benchmarks/src/main/scripts/euhedral-calibration',
            ROOT / 'mise.toml',
            ROOT / 'build-logic/src/main/kotlin/buildlogic.java-conventions.gradle.kts']
  return {str(p.relative_to(ROOT)): sha(p) for p in sorted(paths) if
          p.is_file()}


def policy_task(stage='policy_search'):
  controls = [dict(name=f'{prefix}{i}', source=f'treatment.{prefix}{i}',
                   configPath=f'/calibrationConfig/cacheTimingFunction/{field}/{i}')
              for prefix, field in
              [('a', 'parkCoefficients'), ('d', 'halfLifeCoefficients')] for i
              in range(7)]
  return dict(schemaVersion=1, id='cache_timing_' + stage,
              kind='response_surface',
              dataset=dict(
                manifest='../datasets/cache_timing_' + stage + '.json',
                adapter='jmh_response_surface'),
              features=[
                dict(name=name, source='fixture.' + key, transform=transform)
                for name, key, transform in
                [('workers', 'resolvedWorkers', 'log'),
                 ('sources', 'parallelSources', 'log1p'),
                 ('workUnits', 'workUnits', 'log1p')]],
              controls=controls,
              targets=[
                dict(name='throughputRatio', source='outcome.throughputRatio',
                     transform='log', units='baseline_ratio')],
              objective=dict(adapter='baseline_relative_throughput',
                             direction='maximize',
                             decisionOutput='throughputRatio'),
              validation=dict(outerFolds=3, innerFolds=2, seed=SEED),
              candidates=[dict(id='policy_return_ridge', family='ridge',
                               grid=dict(alpha=[.1, 1., 10.]))],
              export=dict(kind='none'))


def prepare(parent, evidence_task, output):
  if output.exists(): raise ValueError('handoff output must be new')
  spec = TrainingTaskSpec.load(evidence_task)
  data = load_dataset(spec, evidence_task.parent)
  if spec.id != 'cache_timing_fixed_surface' or len(data.row_ids) != 288 or len(
      set(data.groups)) != 9:
    raise ValueError('complete fixed-surface evidence required')
  comparator = compare(spec, data)
  surfaces = select_surfaces()
  if len(surfaces['policies']) != 32: raise ValueError(
    'design requires 32 distinct accepted surfaces')
  base_harness = json.loads((parent / 'fixed_harness.json').read_text())
  template = deepcopy(base_harness['trials'][0])
  cfg = template['calibrationConfig']
  topology = resolve_topology(cfg['cpuSet'])
  if topology['resolvedWorkers'] != 23 or 0 not in os.sched_getaffinity(0):
    raise ValueError('R23 and harness CPU 0 required')
  provenance = json.loads((parent / 'provenance.json').read_text())
  for p, expected in provenance['sourceHashes'].items():
    if sha(ROOT / p) != expected: raise ValueError(
      'frozen participation source changed')
  if topology != provenance['topology']: raise ValueError(
    'fixture topology changed')
  controls = [dict(id=f'fixed-{p}-{h}', kind='fixed', control=True, parkNs=p,
                   halfLifeNs=h, function=None)
              for p, h in CONTROL_PAIRS]
  zero = dict(normalization(), parkCoefficients=[0.] * 7,
              halfLifeCoefficients=[0.] * 7)
  controls.append(
    dict(id='live-baseline', kind='live', control=True, function=zero,
         declaredConstantPair=[15000, 1000000]))
  policies = controls + surfaces['policies']
  surfaces['policies'] = policies
  configs = {}
  trials = []
  # Explicit Cartesian expansion keeps policies with null functions and scalar timing controls
  # paired in one existing balancedSweepOrder sequence. No new harness execution mechanism.
  for policy in policies:
    configs[policy['id']] = deepcopy(cfg)
    configs[policy['id']].update(cacheTimingFunction=policy['function'],
                                 cacheParkNs=policy.get('parkNs', 15000),
                                 contentionHalfLifeNanos=policy.get(
                                   'halfLifeNs', 1000000))
  index = 0
  for sources in SOURCES:
    for work in WORK:
      for policy in policies:
        for pass_id in range(2):
          trial = deepcopy(template)
          trial.update(id=f'cache-timing-live-{index}-pass-{pass_id}',
                       enabled=True,
                       labels=dict(policyId=policy['id'],
                                   workloadId=f'R23-S{sources}-W{work}'),
                       origin=dict(type='SWEEP',
                                   sourceId='cache-timing-live-v1', seed=SEED,
                                   candidateIndex=index, sampleIndex=pass_id))
          trial['calibrationConfig'] = dict(configs[policy['id']],
                                            parallelSources=sources,
                                            workUnits=work)
          trials.append(trial)
        index += 1
  harness = dict(schemaVersion=1, id='cache-timing-live-v1',
                 runOptions=dict(balancedTrialOrder=True,
                                 randomizeTrialOrder=False, failFast=False,
                                 repeatCount=1),
                 artifacts=dict(base_harness['artifacts'],
                                outputDirectory='experiments/cache-timing-live-v1'),
                 trials=trials)
  surfaces.update(preparationEnvironment={name: version(name) for name in
                                          ('numpy', 'scipy', 'scikit-learn',
                                           'pydantic')},
                  fixedHarnessSha256=sha(parent / 'fixed_harness.json'),
                  findingsSha256=sha(
                    parent.parent / 'findings/cache-timing-fixed-surface.md'),
                  expectedJvmRuns=len(trials), workloadCount=9, passes=2,
                  fixedControls=5, liveControls=1,
                  independentUnit='JVM fork',
                  candidateSelectionUsesThroughput=False,
                  allowedConfigDifferences=dict(
                      liveVsLive=[c['configPath'] for c in
                                  policy_task()['controls']],
                      fixedVsFixed=['/calibrationConfig/cacheParkNs',
                                    '/calibrationConfig/contentionHalfLifeNanos'],
                      fixedVsLive=['/calibrationConfig/cacheTimingFunction',
                                   '/calibrationConfig/cacheParkNs',
                                   '/calibrationConfig/contentionHalfLifeNanos'],
                      enforcement='exact per-policy full config match, then only these explicit differences; all other fixture fields frozen'),
                  completedEvidence=dict(taskPath=str(evidence_task.resolve()),
                                         taskSha256=sha(evidence_task),
                                         provenance=data.provenance),
                  baselinePair=[15000, 1000000],
                  baselinePolicyId=controls[0]['id'],
                  topology=topology, inputIdentityHashes=source_hashes())
  for stage in ('policy_search', 'closed_loop_validation'):
    task = policy_task(stage)
    TrainingTaskSpec.model_validate(task)
    write(output / 'tasks' / f'cache_timing_{stage}.json', task)
    write(output / 'datasets' / f'cache_timing_{stage}.json',
          dict(schemaVersion=1, arms=[],
               status='awaiting_live_measurements' if stage == 'policy_search' else 'gated_on_measured_finalists',
               candidateManifest='../candidate_manifest.json',
               outputDirectory=harness['artifacts'][
                 'outputDirectory'] if stage == 'policy_search' else 'experiments/cache-timing-closed-loop-validation',
               allowedConfigDifferences=surfaces['allowedConfigDifferences'],
               plan=dict(expectedJvmRuns=len(
                 trials) if stage == 'policy_search' else None,
                         workloads=[dict(resolvedWorkers=23, parallelSources=s,
                                         workUnits=w) for s in SOURCES for w in
                                    WORK]
                         if stage == 'policy_search' else [],
                         validationGate='at most two measured finalists; new R7/R15 workloads and persistent dynamic scenarios')))
  write(output / 'empirical_constant_comparator.json', comparator)
  write(output / 'candidate_manifest.json', surfaces)
  write(output / 'policy_harness.json', harness)
  with (output / 'surface_catalog.tsv').open('x') as stream:
    fields = ['policyId', 'kind', 'family', 'control', 'parkNs', 'halfLifeNs',
              'parkLogSpan', 'halfLifeLogSpan', 'nearestSurfaceDistance',
              'maximumBoundaryFraction']
    writer = csv.DictWriter(stream, fieldnames=fields, delimiter='\t');
    writer.writeheader()
    for p in policies:
      row = {k: p.get(k, '') for k in fields}
      row['policyId'] = p['id']
      if not p['control']:
        row.update(parkLogSpan=p['span'][0], halfLifeLogSpan=p['span'][1],
                   maximumBoundaryFraction=max(
                       v for axis in p['clampOccupancy'] for v in
                       axis.values()))
      elif p['kind'] == 'live':
        row.update(parkNs=15000, halfLifeNs=1000000)
      writer.writerow(row)
  for policy in policies:
    if policy['function'] is not None:
      path = output / 'evaluators' / policy['id'] / 'CacheTimingEvaluator.java'
      path.parent.mkdir(parents=True, exist_ok=True)
      path.write_text(render_cache_timing(policy['function']))
  freeze(output)
  return surfaces


def freeze(handoff):
  # Call once after generation; never overwrite a frozen manifest or completed experiment.
  write(handoff / 'lock.json', dict(schemaVersion=1,
                                    files={str(p.relative_to(handoff)): sha(p)
                                           for p in sorted(handoff.rglob('*'))
                                           if p.is_file()},
                                    sourceHashes=source_hashes()))


def verify(handoff, check_sources=True):
  lock = json.loads((handoff / 'lock.json').read_text())
  for p, expected in lock['files'].items():
    if sha(handoff / p) != expected: raise ValueError(
      f'frozen handoff changed: {p}')
  if check_sources:
    if source_hashes() != lock['sourceHashes']: raise ValueError(
      'source identity changed; prepare a new version')
  manifest = json.loads((handoff / 'candidate_manifest.json').read_text())
  harness = json.loads((handoff / 'policy_harness.json').read_text())
  if len(harness['trials']) != manifest['expectedJvmRuns']: raise ValueError(
    'trial inventory mismatch')
  if resolve_topology(harness['trials'][0]['calibrationConfig']['cpuSet']) != \
      manifest['topology']:
    raise ValueError('topology changed')
  expected = {(p['id'], f'R23-S{s}-W{w}', pass_id) for p in manifest['policies']
              for s in SOURCES for w in WORK for pass_id in range(2)}
  actual = [(t['labels']['policyId'], t['labels']['workloadId'],
             t['origin']['sampleIndex'])
            for t in harness['trials']]
  if len(set(actual)) != len(actual) or set(actual) != expected:
    raise ValueError('policy/workload/pass inventory mismatch')
  return manifest, harness


def launch(handoff):
  for name in ('JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
               '_JAVA_OPTIONS'):
    if os.environ.get(name): raise ValueError(
      f'{name} must be unset for the frozen campaign')
  manifest, harness = verify(handoff)
  output = ROOT / harness['artifacts']['outputDirectory']
  if output.exists(): raise ValueError(
    'run directory exists; preserve partial/completed runs')
  subprocess.run(['mise', 'exec', '--', 'gradle', ':benchmarks:assemble'],
                 cwd=ROOT, check=True)
  verify(handoff)
  output.mkdir(parents=True, exist_ok=False)
  write(output / 'identity.json',
        dict(handoffLockSha256=sha(handoff / 'lock.json'),
             harnessSha256=sha(handoff / 'policy_harness.json'),
             sourceHashes=source_hashes(), binaryHashes=binaries(),
             topology=manifest['topology'], java=subprocess.check_output(
              ['mise', 'exec', '--', 'java', '-version'],
              cwd=ROOT, stderr=subprocess.STDOUT, text=True)))
  subprocess.run(['mise', 'exec', '--', 'bash',
                  'benchmarks/build/bin/euhedral-calibration',
                  'run', str((handoff / 'policy_harness.json').resolve())],
                 cwd=ROOT, check=True)


def collect(handoff, output):
  if output.exists(): raise ValueError('collection output must be new')
  manifest, harness = verify(handoff, check_sources=False)
  run = ROOT / harness['artifacts']['outputDirectory']
  identity = json.loads((run / 'identity.json').read_text())
  if identity['handoffLockSha256'] != sha(handoff / 'lock.json') or identity[
    'harnessSha256'] != sha(handoff / 'policy_harness.json'):
    raise ValueError('launch/handoff identity mismatch')
  lock = json.loads((handoff / 'lock.json').read_text())
  if identity['sourceHashes'] != lock['sourceHashes'] or identity['topology'] != \
      manifest['topology']:
    raise ValueError('launch source/topology identity mismatch')
  definitions = {t['id']: t for t in harness['trials']}
  policies = {p['id']: p for p in manifest['policies']}
  records = [];
  seen = set();
  runtime = None;
  actuator = None
  for path in sorted(run.glob('*/trial_config.json')):
    trial = json.loads(path.read_text());
    expected = definitions.get(trial['id'])
    if expected is None or trial['id'] in seen: raise ValueError(
      'unknown/duplicate trial')
    seen.add(trial['id'])
    cfg = trial['calibrationConfig'];
    expected_cfg = deepcopy(expected['calibrationConfig'])
    if actuator is not None and actuator != cfg[
      'cacheActuatorVersion']: raise ValueError('mixed actuator identity')
    actuator = cfg['cacheActuatorVersion'];
    expected_cfg['cacheActuatorVersion'] = actuator
    if cfg != expected_cfg: raise ValueError(
      'policy or non-treatment configuration changed')
    for key in ('forks', 'warmups', 'iterations', 'warmupTime',
                'measurementTime', 'jvmArgs', 'origin', 'labels'):
      if trial[key] != expected[key]: raise ValueError(
        'trial schedule, placement or identity mismatch')
    log = path.parent / 'benchmark_output.log';
    text = log.read_text();
    values = windows(text)
    headers = [l for l in text.splitlines() if
               l.startswith(('# JMH version:', '# VM version:'))]
    if len(
        headers) != 2 or runtime is not None and runtime != headers: raise ValueError(
      'runtime identity mismatch')
    runtime = headers
    policy = policies[trial['labels']['policyId']]
    treatment = {} if policy['kind'] == 'fixed' else {
      f'{prefix}{i}': value for prefix, field in
      [('a', 'parkCoefficients'), ('d', 'halfLifeCoefficients')]
      for i, value in enumerate(cfg['cacheTimingFunction'][field])}
    records.append(dict(runId=path.parent.name, forkId='1',
                        workloadId=trial['labels']['workloadId'],
                        passId=str(trial['origin']['sampleIndex']),
                        policyId=policy['id'], policyKind=policy['kind'],
                        policyParameters=[treatment[k] for k in BASIS[
                          'coefficientOrder']] if treatment else [
                          cfg['cacheParkNs'], cfg['contentionHalfLifeNanos']],
                        identity=dict(identity, runtimeHeaders=headers),
                        fixture=dict(manifest['topology'],
                                     parallelSources=cfg['parallelSources'],
                                     workUnits=cfg['workUnits']),
                        treatment=treatment,
                        config=dict(calibrationConfig=cfg), outcome=dict(
        executionsPerSecond=statistics.mean(values), measurementWindows=values),
                        provenance=dict(configPath=str(path),
                                        configSha256=sha(path),
                                        logPath=str(log), logSha256=sha(log),
                                        originalTrial=trial)))
  if seen != set(definitions): raise ValueError(
    f'incomplete campaign: {len(seen)}/{len(definitions)}; keep slow/partial runs')
  baseline = {w: [r for r in records if
                  r['workloadId'] == w and r['policyId'] == manifest[
                    'baselinePolicyId']]
              for w in {r['workloadId'] for r in records}}
  for r in records:
    base = baseline[r['workloadId']]
    r['outcome']['throughputRatio'] = r['outcome'][
                                        'executionsPerSecond'] / statistics.mean(
        x['outcome']['executionsPerSecond'] for x in base)
  write(output / 'arms.json', records)
  live = [r for r in records if r['policyKind'] == 'live']
  write(output / 'live_arms.json', live)
  task = policy_task()
  task['dataset']['inventory'] = dict(rows=len(live), families=9)
  write(output / 'tasks/cache_timing_policy_search.json', task)
  write(output / 'datasets/cache_timing_policy_search.json',
        dict(schemaVersion=1,
             arms=[dict(path='../live_arms.json',
                        sha256=sha(output / 'live_arms.json'))],
             hashes={'../arms.json': sha(output / 'arms.json')},
             candidateManifestSha256=sha(handoff / 'candidate_manifest.json'),
             status='complete',
             fixedControls='retained in arms.json and score tables; excluded from coefficient-only surrogate design'))
  load_dataset(TrainingTaskSpec.model_validate(task), output / 'tasks')
  policy_tables(records, manifest['baselinePolicyId'], output)


def policy_tables(records, baseline_id, output):
  workloads = sorted({r['workloadId'] for r in records});
  policies = sorted({r['policyId'] for r in records})
  summaries = [];
  matched = [];
  scores = []
  for policy in policies:
    per_workload = []
    for w in workloads:
      selected = [r for r in records if
                  r['workloadId'] == w and r['policyId'] == policy]
      baseline = [r for r in records if
                  r['workloadId'] == w and r['policyId'] == baseline_id]
      cm = [r['outcome']['executionsPerSecond'] for r in selected];
      bm = [r['outcome']['executionsPerSecond'] for r in baseline]
      ratio = statistics.mean(cm) / statistics.mean(bm);
      per_workload.append(math.log(ratio))
      summaries.append(
        dict(policyId=policy, workloadId=w, baselineMean=statistics.mean(bm),
             candidateMean=statistics.mean(cm),
             percentChange=100 * (ratio - 1), logRatio=math.log(ratio),
             baselineMinimumForkMean=min(bm), candidateMinimumForkMean=min(cm),
             baselineMinimumWindow=min(v for r in baseline for v in
                                       r['outcome']['measurementWindows']),
             candidateMinimumWindow=min(v for r in selected for v in
                                        r['outcome']['measurementWindows'])))
      for r in selected:
        b = next(x for x in baseline if x['passId'] == r['passId'])
        ratio = r['outcome']['executionsPerSecond'] / b['outcome'][
          'executionsPerSecond']
        matched.append(dict(policyId=policy, workloadId=w, passId=r['passId'],
                            baselineMean=b['outcome']['executionsPerSecond'],
                            candidateMean=r['outcome']['executionsPerSecond'],
                            percentChange=100 * (ratio - 1),
                            logRatio=math.log(ratio),
                            baselineMinimumWindow=min(
                                b['outcome']['measurementWindows']),
                            candidateMinimumWindow=min(
                                r['outcome']['measurementWindows'])))
    j = statistics.mean(per_workload)
    scores.append(
      dict(policyId=policy, J=j, geometricChangePercent=100 * math.expm1(j),
           worstWorkloadPercent=100 * math.expm1(min(per_workload))))
  for name, rows in [('throughput_by_workload', summaries),
                     ('matched_pass', matched), ('policy_score', scores)]:
    with (output / (name + '.tsv')).open('x') as stream:
      writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t');
      writer.writeheader();
      writer.writerows(rows)


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument('command', choices=['prepare', 'check', 'run', 'collect'])
  parser.add_argument('--handoff', type=Path, required=True)
  parser.add_argument('--output-dir', type=Path)
  parser.add_argument('--fixed-task', type=Path)
  args = parser.parse_args()
  if args.command == 'prepare':
    if args.output_dir is None or args.fixed_task is None: parser.error(
      'prepare requires --fixed-task and --output-dir')
    result = prepare(args.handoff, args.fixed_task, args.output_dir)
    print(
      f"Prepared {len(result['policies'])} policies, {result['expectedJvmRuns']} JVMs; no benchmarks executed.")
  elif args.command == 'check':
    m, _ = verify(args.handoff);
    print(
      f"Verified frozen {m['expectedJvmRuns']}-JVM handoff; no benchmarks executed.")
  elif args.command == 'run':
    launch(args.handoff)
  else:
    if args.output_dir is None: parser.error('collect requires --output-dir')
    collect(args.handoff, args.output_dir)


if __name__ == '__main__': main()
