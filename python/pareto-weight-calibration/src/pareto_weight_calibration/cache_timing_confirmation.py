"""Bounded CACHE idle-policy confirmation through the existing calibration harness."""
from __future__ import annotations

import argparse
from copy import deepcopy
import csv
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess

from .cache_timing import ROOT, sha, write, resolve_topology
from .cache_timing_policy import source_hashes, freeze
from .cache_timing_evidence import binaries

ARMS = ('POLICY_OFF', 'live-25', 'live-12')
WORK = (0, 96, 576)
BLOCKS = 4
DYNAMIC = {
  'BODY_LOW_TO_HIGH': ((0, 1), (768, 1)),
  'BODY_HIGH_TO_LOW': ((768, 1), (0, 1)),
  'SOURCES_LOW_TO_HIGH': ((172, 1), (172, 23)),
  'SOURCES_HIGH_TO_LOW': ((172, 23), (172, 1)),
  'COMBINED': ((0, 1), (768, 23)),
  'COMBINED_HIGH_TO_LOW': ((768, 23), (0, 1)),
}
STAGES = ('r23', 'topologies', 'dynamic')


def read(path):
  return json.loads(path.read_text())


def prepare(parent, output):
  if output.exists():
    raise ValueError('prepare into a new version; never replace frozen inputs')
  previous = parent / 'live-v2'
  old_lock = read(previous / 'lock.json')
  for name, expected in old_lock['files'].items():
    if sha(previous / name) != expected:
      raise ValueError('screened candidate provenance changed')
  provenance = read(parent / 'provenance.json')
  for name, expected in provenance['sourceHashes'].items():
    if sha(ROOT / name) != expected:
      raise ValueError('participation scorer/decision tree changed')
  screened = read(previous / 'candidate_manifest.json')
  functions = {p['id']: p['function'] for p in screened['policies'] if
               p['id'] in ARMS}
  functions['POLICY_OFF'] = None
  template = read(parent / 'fixed_harness.json')['trials'][0]
  topologies = {
    str(r): resolve_topology(list(range(2, {7: 16, 15: 24, 23: 32}[r]))) for r
    in (7, 15, 23)}
  if 0 not in os.sched_getaffinity(0) or any(
      t['resolvedWorkers'] != int(r) for r, t in topologies.items()):
    raise ValueError('frozen R7/R15/R23 host placement unavailable')
  fixtures = {}
  for stage, workers in [('r23', (23,)), ('topologies', (7, 15))]:
    fixtures[stage] = [dict(workloadId=f'R{r}-S{s}-W{w}', resolvedWorkers=r,
                            parallelSources=s, workUnits=w,
                            workloadClass='PRIMARY' if s == 1 else 'GUARDRAIL',
                            bodyRegime=
                            {0: 'cheap', 96: 'medium', 576: 'expensive'}[w])
                       for r in workers for s in (1, r) for w in WORK]
  fixtures['dynamic'] = [dict(workloadId='R23-' + scenario, resolvedWorkers=23,
                              parallelSources=max(s for _, s in phases),
                              workUnits=phases[0][0],
                              workloadClass='DYNAMIC', bodyRegime='transition',
                              scenario=scenario,
                              phases=[
                                dict(name=name, workUnits=w, enabledSources=s,
                                     workloadClass='PRIMARY' if s == 1 else 'GUARDRAIL',
                                     measurementWindows=list(
                                       range(start, start + 4)))
                                for (w, s), name, start in
                                zip(phases, ('before', 'after'), (1, 5))])
                         for scenario, phases in DYNAMIC.items()]
  stage_info = {}
  for stage in STAGES:
    trials = []
    for index, (fixture, arm) in enumerate(
        (f, a) for f in fixtures[stage] for a in ARMS):
      for block in range(BLOCKS):
        trial = deepcopy(template)
        cfg = trial['calibrationConfig']
        r = fixture['resolvedWorkers']
        cfg.update(cpuSet=list(range(2, {7: 16, 15: 24, 23: 32}[r])),
                   parallelSources=fixture['parallelSources'],
                   workUnits=fixture['workUnits'],
                   cacheTimingFunction=deepcopy(functions[arm]),
                   cacheParkNs=15000,
                   contentionHalfLifeNanos=1000000)
        trial.update(id=f'cache-confirm-{stage}-{index}-pass-{block}',
                     enabled=True,
                     origin=dict(type='SWEEP',
                                 sourceId=f'cache-confirm-{stage}',
                                 seed=20260909,
                                 candidateIndex=index, sampleIndex=block),
                     labels=dict(policyId=arm, workloadId=fixture['workloadId'],
                                 workloadClass=fixture['workloadClass']),
                     jvmArgs=template['jvmArgs'] + [
                       '-Deuhedral.calibration.throughputOnly=true'])
        if stage == 'dynamic':
          trial.update(warmups=4, iterations=8)
          trial['jvmArgs'].append(
            '-Deuhedral.calibration.participationDynamicScenario=' + fixture[
              'scenario'])
        trials.append(trial)
    run_dir = 'experiments/cache-timing-confirmation-v1-' + stage
    harness = dict(schemaVersion=1, id='cache-confirm-' + stage,
                   runOptions=dict(balancedTrialOrder=True,
                                   randomizeTrialOrder=False, failFast=False,
                                   repeatCount=1),
                   artifacts=dict(outputDirectory=run_dir,
                                  retainExpandedConfig=True,
                                  retainRawBenchmarkOutput=True,
                                  retainObserverData=False,
                                  retainPerForkResults=True,
                                  retainPerIterationResults=False),
                   trials=trials)
    write(output / (stage + '_harness.json'), harness)
    stage_info[stage] = dict(harness=stage + '_harness.json',
                             fixtures=fixtures[stage],
                             expectedForks=len(trials),
                             windowsPerFork=8 if stage == 'dynamic' else 5,
                             outputDirectory=run_dir,
                             evidenceDirectory=run_dir + '-evidence',
                             requiredReviews=[] if stage == 'r23' else [
                               'r23'] if stage == 'topologies' else ['r23',
                                                                     'topologies'])
  manifest = dict(schemaVersion=1, id='cache-timing-confirmation-v1',
                  status='unexecuted',
                  baseline='POLICY_OFF', independentUnit='JVM fork',
                  blocks=BLOCKS,
                  arms=[dict(id=a, mode='OFF' if a == 'POLICY_OFF' else 'ON',
                             function=functions[a],
                             fixedParkNanos=15000, fixedHalfLifeNanos=1000000)
                        for a in ARMS],
                  screenedManifestSha256=sha(
                    previous / 'candidate_manifest.json'),
                  screenedLockSha256=sha(previous / 'lock.json'),
                  topologies=topologies, stages=stage_info,
                  allowedTimingDifferences=[
                    '/calibrationConfig/cacheTimingFunction'],
                  compatibility='exact frozen full trial/config match; function difference only among arms in each fixture',
                  sourceHashes=source_hashes())
  write(output / 'candidate_manifest.json', manifest)
  write(output / 'analysis_config.json',
        dict(schemaVersion=1, baseline='POLICY_OFF',
             hierarchy=['broad material PRIMARY gain',
                        'no material GUARDRAIL harm',
                        'fork/topology robustness', 'overall descriptive only'],
             classes=dict(PRIMARY='scarce, one enabled source',
                          GUARDRAIL='plentiful, enabled sources equal resolved workers',
                          DYNAMIC='compare matched phases and windows without treating windows as replicates'),
             guardrail='positive/near-neutral good; slight negative can be acceptable; material or consistently negative requires rejection/review',
             thresholds='No automatic numerical acceptance cutoff or scalar winner score; review each workload and independent block',
             stages=stage_info, retainEveryFork=True, retainEveryWindow=True,
             automaticWinner=False))
  freeze(output)
  return manifest


def check(handoff, stage=None, current_sources=True):
  lock = read(handoff / 'lock.json')
  for name, expected in lock['files'].items():
    if sha(handoff / name) != expected:
      raise ValueError('frozen input changed: ' + name)
  if current_sources and lock['sourceHashes'] != source_hashes():
    raise ValueError('source identity changed; prepare a reviewed new version')
  manifest = read(handoff / 'candidate_manifest.json')
  for r, topology in manifest['topologies'].items():
    cpus = [c for core in topology['physicalCores'] for c in
            core['logicalCpus']]
    if resolve_topology(cpus) != topology:
      raise ValueError('physical topology changed: R' + r)
  if 0 not in os.sched_getaffinity(0):
    raise ValueError('harness CPU 0 unavailable')
  for s in (manifest['stages'] if stage is None else [stage]):
    info = manifest['stages'][s]
    harness = read(handoff / info['harness'])
    actual = [(t['labels']['policyId'], t['labels']['workloadId'],
               t['origin']['sampleIndex']) for t in harness['trials']]
    expected = {(a, f['workloadId'], b) for a in
                (arm['id'] for arm in manifest['arms']) for f in
                info['fixtures']
                for b in range(manifest['blocks'])}
    if len(actual) != len(set(actual)) or set(actual) != expected or len(
        actual) != info['expectedForks']:
      raise ValueError('incomplete/duplicate arm/fixture/block inventory')
  return manifest


def review_gate(handoff, stage, reviews):
  manifest = check(handoff, stage)
  required = manifest['stages'][stage]['requiredReviews']
  supplied = {read(p)['stage']: (p, read(p)) for p in reviews}
  for prior in required:
    if prior not in supplied:
      raise ValueError('review required before ' + stage + ': ' + prior)
    _, review = supplied[prior]
    receipt = ROOT / manifest['stages'][prior][
      'evidenceDirectory'] / 'evidence_manifest.json'
    if (review.get('decision') != 'proceed' or not review.get(
        'reviewer') or not review.get('rationale')
        or review.get('evidenceManifestSha256') != sha(receipt)
        or read(receipt)['handoffLockSha256'] != sha(handoff / 'lock.json')
        or read(receipt).get('stage') != prior):
      raise ValueError(
        'review must explicitly approve matching collected evidence: ' + prior)
    for name, expected in read(receipt)['files'].items():
      if sha(receipt.parent / name) != expected:
        raise ValueError('reviewed evidence changed: ' + prior + '/' + name)
  return manifest


def launch(handoff, stage, reviews):
  for name in ('JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
               '_JAVA_OPTIONS'):
    if os.environ.get(name):
      raise ValueError(name + ' must be unset')
  manifest = review_gate(handoff, stage, reviews)
  info = manifest['stages'][stage]
  output = ROOT / info['outputDirectory']
  if output.exists():
    raise ValueError('preserve existing partial/completed run directory')
  subprocess.run(['mise', 'exec', '--', 'gradle', ':benchmarks:assemble'],
                 cwd=ROOT, check=True)
  check(handoff, stage)
  write(output / 'identity.json',
        dict(stage=stage, handoffLockSha256=sha(handoff / 'lock.json'),
             harnessSha256=sha(handoff / info['harness']),
             sourceHashes=source_hashes(), binaryHashes=binaries(),
             topologies=manifest['topologies'],
             reviews={str(p): sha(p) for p in reviews},
             java=subprocess.check_output(
                 ['mise', 'exec', '--', 'java', '-version'], cwd=ROOT,
                 stderr=subprocess.STDOUT, text=True)))
  subprocess.run(['mise', 'exec', '--', 'bash',
                  'benchmarks/build/bin/euhedral-calibration', 'run',
                  str((handoff / info['harness']).resolve())], cwd=ROOT,
                 check=True)


def measurement_windows(text, count):
  found = [(int(i), float(v)) for i, v in re.findall(
      r'^Iteration\s+(\d+):[^\n]*\n\s+executions:\s+([0-9.eE+-]+) ops/s$', text,
      re.M)]
  if ([i for i, _ in found] != list(
      range(1, count + 1)) or '# Fork: 1 of 1' not in text
      or any(not math.isfinite(v) or v <= 0 for _, v in found)
      or re.search(r'<failure>|Exception|ERROR', text)):
    raise ValueError(
      'require one complete fork; preserve failures and all slow windows')
  return [v for _, v in found]


def collect(handoff, stage, output=None):
  manifest = check(handoff, stage, current_sources=False)
  info = manifest['stages'][stage]
  output = output or ROOT / info['evidenceDirectory']
  if output.exists():
    raise ValueError('collection output must be new')
  run = ROOT / info['outputDirectory']
  identity = read(run / 'identity.json')
  lock = read(handoff / 'lock.json')
  if (identity['stage'] != stage or identity['handoffLockSha256'] != sha(
      handoff / 'lock.json')
      or identity['harnessSha256'] != sha(handoff / info['harness'])
      or identity['sourceHashes'] != lock['sourceHashes'] or identity[
        'topologies'] != manifest['topologies']):
    raise ValueError('launch provenance mismatch')
  expected = {t['id']: t for t in read(handoff / info['harness'])['trials']}
  fixtures = {f['workloadId']: f for f in info['fixtures']}
  records = [];
  seen = set();
  headers = set();
  actuators = set()
  for path in sorted(run.glob('*/trial_config.json')):
    trial = read(path)
    if trial['id'] not in expected or trial['id'] in seen:
      raise ValueError('unknown/duplicate trial')
    seen.add(trial['id'])
    reference = deepcopy(expected[trial['id']])
    cfg = trial['calibrationConfig'];
    actuators.add(cfg['cacheActuatorVersion'])
    reference['calibrationConfig']['cacheActuatorVersion'] = cfg[
      'cacheActuatorVersion']
    for key in ('calibrationConfig', 'forks', 'warmups', 'iterations',
                'warmupTime', 'measurementTime', 'jvmArgs', 'labels', 'origin'):
      if trial[key] != reference[key]:
        raise ValueError('frozen configuration/identity changed: ' + key)
    log = path.parent / 'benchmark_output.log';
    text = log.read_text()
    values = measurement_windows(text, info['windowsPerFork'])
    header = tuple(l for l in text.splitlines() if
                   l.startswith(('# JMH version:', '# VM version:')))
    if len(header) != 2:
      raise ValueError('missing JVM/JMH identity')
    headers.add(header)
    records.append(dict(runId=path.parent.name, forkId='1',
                        passId=str(trial['origin']['sampleIndex']),
                        policyId=trial['labels']['policyId'],
                        workloadId=trial['labels']['workloadId'],
                        fixture=fixtures[trial['labels']['workloadId']],
                        config=cfg,
                        executionsPerSecond=statistics.mean(values),
                        measurementWindows=values,
                        provenance=dict(configPath=str(path),
                                        configSha256=sha(path),
                                        logPath=str(log), logSha256=sha(log))))
  if seen != set(expected) or len(headers) != 1 or len(actuators) != 1:
    raise ValueError(
      'incomplete campaign or mixed runtime identity; preserve all trials')
  write(output / 'arms.json', records)
  summarize(records, output, tuple(arm['id'] for arm in manifest['arms']))
  if (handoff / 'collection.json').exists():
    comparisons = read(handoff / 'collection.json').get('sameBaseComparisons',
                                                        [])
    if comparisons:
      summarize_pairs(records, comparisons, output)
  write(output / 'evidence_manifest.json',
        dict(schemaVersion=1, stage=stage, forkCount=len(records),
             windowCount=sum(len(r['measurementWindows']) for r in records),
             handoffLockSha256=sha(handoff / 'lock.json'),
             identity=identity, runtimeHeaders=list(next(iter(headers))),
             acceptance='REVIEW_REQUIRED',
             files={p.name: sha(p) for p in output.iterdir() if p.is_file()}))


def tsv(output, name, rows):
  if not rows:
    return
  with (output / name).open('x') as stream:
    writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t');
    writer.writeheader();
    writer.writerows(rows)


def summarize_pairs(records, comparisons, output):
  """Matched same-base actuator comparisons declared in the collection JSON."""
  lookup = {}
  for r in records:
    key = (r['policyId'], r['workloadId'], r['passId'], r['forkId'])
    if key in lookup:
      raise ValueError('duplicate independent fork identity')
    lookup[key] = r
  forks = [];
  pooled = []
  for comparison in comparisons:
    full, zero = comparison['fullPolicyId'], comparison['zeroPolicyId']
    selected = [r for r in records if r['policyId'] == full]
    if not selected:
      raise ValueError('missing full policy for declared pair')
    for r in selected:
      other = lookup[(zero, r['workloadId'], r['passId'], r['forkId'])]
      forks.append(dict(fullPolicyId=full, zeroPolicyId=zero,
                        workloadId=r['workloadId'], passId=r['passId'],
                        forkId=r['forkId'],
                        fullRunId=r['runId'], zeroRunId=other['runId'],
                        fullThroughput=r['executionsPerSecond'],
                        zeroThroughput=other['executionsPerSecond'],
                        logRatio=math.log(r['executionsPerSecond'] / other[
                          'executionsPerSecond']),
                        fullMinimumWindow=min(r['measurementWindows']),
                        zeroMinimumWindow=min(other['measurementWindows'])))
    for workload in sorted({r['workloadId'] for r in selected}):
      rows = [r for r in forks if
              r['fullPolicyId'] == full and r['workloadId'] == workload]
      fm = statistics.mean(r['fullThroughput'] for r in rows)
      zm = statistics.mean(r['zeroThroughput'] for r in rows)
      fixture = next(
          r['fixture'] for r in selected if r['workloadId'] == workload)
      pooled.append(
        dict(fullPolicyId=full, zeroPolicyId=zero, workloadId=workload,
             resolvedWorkers=fixture['resolvedWorkers'],
             workloadClass=fixture['workloadClass'],
             workUnits=fixture['workUnits'], fullThroughput=fm,
             zeroThroughput=zm,
             percentChange=100 * (fm / zm - 1), logRatio=math.log(fm / zm),
             fullMinimumForkMean=min(r['fullThroughput'] for r in rows),
             zeroMinimumForkMean=min(r['zeroThroughput'] for r in rows),
             fullMinimumWindow=min(r['fullMinimumWindow'] for r in rows),
             zeroMinimumWindow=min(r['zeroMinimumWindow'] for r in rows)))
  tsv(output, 'same_base_forks.tsv', forks)
  tsv(output, 'same_base_workloads.tsv', pooled)
  classes = []
  for full, zero, topology, kind in sorted({(r['fullPolicyId'],
                                             r['zeroPolicyId'],
                                             r['resolvedWorkers'],
                                             r['workloadClass']) for r in
                                            pooled}):
    rows = [r for r in pooled if
            (r['fullPolicyId'], r['zeroPolicyId'], r['resolvedWorkers'],
             r['workloadClass']) == (full, zero, topology, kind)]
    worst = min(rows, key=lambda r: r['percentChange'])
    classes.append(
      dict(fullPolicyId=full, zeroPolicyId=zero, resolvedWorkers=topology,
           workloadClass=kind, geometricChangePercent=100 * math.expm1(
          statistics.mean(r['logRatio'] for r in rows)),
           positiveWorkloads=sum(r['percentChange'] > 0 for r in rows),
           workloadCount=len(rows),
           worstWorkload=worst['workloadId'],
           worstWorkloadPercent=worst['percentChange'],
           assessment='REVIEW_REQUIRED'))
  tsv(output, 'same_base_classes.tsv', classes)


def summarize(records, output, arms=ARMS):
  # Dynamic phases are nested views of a fork; they do not create new independent replicates.
  views = []
  window_rows = []
  for r in records:
    f = r['fixture']
    for i, value in enumerate(r['measurementWindows'], 1):
      window_rows.append(
        dict(policyId=r['policyId'], workloadId=r['workloadId'],
             passId=r['passId'],
             runId=r['runId'], window=i, executionsPerSecond=value))
    phases = f.get('phases', [dict(name='steady', measurementWindows=list(
      range(1, len(r['measurementWindows']) + 1)),
                                   workloadClass=f['workloadClass'],
                                   workUnits=f['workUnits'],
                                   enabledSources=f['parallelSources'])])
    for phase in phases:
      vals = [r['measurementWindows'][i - 1] for i in
              phase['measurementWindows']]
      views.append(dict(policyId=r['policyId'],
                        workloadId=r['workloadId'] + ':' + phase['name'],
                        resolvedWorkers=f['resolvedWorkers'],
                        workloadClass=phase['workloadClass'],
                        workUnits=phase['workUnits'],
                        enabledSources=phase['enabledSources'],
                        passId=r['passId'], runId=r['runId'],
                        mean=statistics.mean(vals), minimumWindow=min(vals)))
  baseline = {(r['workloadId'], r['passId']): r for r in views if
              r['policyId'] == 'POLICY_OFF'}
  per_fork = []
  for r in views:
    b = baseline[r['workloadId'], r['passId']]
    per_fork.append(dict(r, baselineMean=b['mean'],
                         percentChange=100 * (r['mean'] / b['mean'] - 1),
                         baselineMinimumWindow=b['minimumWindow']))
  pooled = []
  for arm in arms:
    for w in sorted({r['workloadId'] for r in views}):
      selected = [r for r in per_fork if
                  r['workloadId'] == w and r['policyId'] == arm]
      bs = [baseline[w, r['passId']] for r in selected]
      cm = statistics.mean(r['mean'] for r in selected);
      bm = statistics.mean(r['mean'] for r in bs)
      pooled.append(dict(policyId=arm, workloadId=w,
                         resolvedWorkers=selected[0]['resolvedWorkers'],
                         workloadClass=selected[0]['workloadClass'],
                         workUnits=selected[0]['workUnits'],
                         enabledSources=selected[0]['enabledSources'],
                         pooledThroughput=cm, baselineThroughput=bm,
                         percentChange=100 * (cm / bm - 1),
                         logRatio=math.log(cm / bm),
                         positiveEveryBlock=all(
                             r['percentChange'] > 0 for r in selected),
                         minimumForkMean=min(r['mean'] for r in selected),
                         minimumMeasurementWindow=min(
                             r['minimumWindow'] for r in selected),
                         baselineMinimumForkMean=min(r['mean'] for r in bs),
                         baselineMinimumMeasurementWindow=min(
                             r['minimumWindow'] for r in bs)))
  classes = [];
  overall = []
  for arm in arms[1:]:
    for topology in sorted({r['resolvedWorkers'] for r in views}):
      subset = [r for r in pooled if
                r['policyId'] == arm and r['resolvedWorkers'] == topology]
      overall.append(
        dict(policyId=arm, resolvedWorkers=topology, descriptiveOnly=True,
             geometricChangePercent=100 * math.expm1(
               statistics.mean(r['logRatio'] for r in subset))))
      for kind in sorted({r['workloadClass'] for r in subset}):
        rows = [r for r in subset if r['workloadClass'] == kind]
        worst = min(rows, key=lambda r: r['percentChange'])
        classes.append(
          dict(policyId=arm, resolvedWorkers=topology, workloadClass=kind,
               geometricChangePercent=100 * math.expm1(
                 statistics.mean(r['logRatio'] for r in rows)),
               positiveWorkloads=sum(r['percentChange'] > 0 for r in rows),
               workloadCount=len(rows),
               positiveEveryBlockWorkloads=sum(
                   r['positiveEveryBlock'] for r in rows),
               worstWorkload=worst['workloadId'],
               worstWorkloadPercent=worst['percentChange'],
               assessment='REVIEW_REQUIRED'))
  # Matched individual windows expose first-post-change and subsequent recovery without new observers.
  wb = {(r['workloadId'], r['passId'], r['window']): r for r in window_rows if
        r['policyId'] == 'POLICY_OFF'}
  for r in window_rows:
    b = wb[r['workloadId'], r['passId'], r['window']]
    r.update(baselineThroughput=b['executionsPerSecond'], percentChange=100 * (
          r['executionsPerSecond'] / b['executionsPerSecond'] - 1))
  for name, rows in [('per_fork.tsv', per_fork), ('per_workload.tsv', pooled),
                     ('class_summary.tsv', classes),
                     ('overall_descriptive.tsv', overall),
                     ('measurement_windows.tsv', window_rows)]:
    tsv(output, name, rows)


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument('command', choices=['prepare', 'check', 'run', 'collect'])
  parser.add_argument('--handoff', type=Path, required=True)
  parser.add_argument('--stage', choices=STAGES)
  parser.add_argument('--output-dir', type=Path)
  parser.add_argument('--review', type=Path, action='append', default=[])
  args = parser.parse_args()
  if args.command == 'prepare':
    if args.output_dir is None: parser.error('prepare requires --output-dir')
    m = prepare(args.handoff, args.output_dir)
    print({s: i['expectedForks'] for s, i in m['stages'].items()})
  elif args.command == 'check':
    check(args.handoff, args.stage);
    print('Frozen confirmation inputs verified; no benchmarks executed.')
  else:
    if args.stage is None: parser.error(
      '--stage required; stages never auto-chain')
    if args.command == 'run':
      launch(args.handoff, args.stage, args.review)
    else:
      collect(args.handoff, args.stage, args.output_dir)


if __name__ == '__main__': main()
