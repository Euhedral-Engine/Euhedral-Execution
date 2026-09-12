"""Staged CACHE timing campaign preparation; never launches scheduler trials."""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import subprocess

from .training_spec import TrainingTaskSpec, digest

PARKS = (15000, 125000, 500000, 814375)
HALF_LIVES = (250000, 500000, 1000000, 2000000)
SOURCES = (1, 17, 23)
WORK = (0, 96, 576)
SEED = 20260908
ROOT = Path(__file__).resolve().parents[4]


def write(path, value):
  path.parent.mkdir(parents=True, exist_ok=True)
  with path.open('x') as stream:
    json.dump(value, stream, indent=2, allow_nan=False)
    stream.write('\n')


def sha(path):
  return hashlib.sha256(path.read_bytes()).hexdigest()


def normalization():
  # Declared before sampling; no calibration observations or work-unit conversion.
  return dict(normalizationVersion='cache-local-bounded-v1', means=[.5, 2, 8],
              scales=[.5, 2, 8], supportMin=[0, 0, 0], supportMax=[1, 4, 16],
              parkReferenceNanos=15000, halfLifeReferenceNanos=1000000,
              parkMinNanos=15000, parkMaxNanos=814375,
              halfLifeMinNanos=250000, halfLifeMaxNanos=2000000)


def evaluate(config, c, p, body):
  if not all(math.isfinite(x) for x in (c, p, body)) or body < 0:
    return (15000, 1000000)
  raw = (c, p, math.log1p(body))
  if any(x < lo or x > hi for x, lo, hi in zip(
      raw, config['supportMin'], config['supportMax'])):
    return (15000, 1000000)
  phi = [1] + [(x - m) / s for x, m, s in
               zip(raw, config['means'], config['scales'])]
  width = len(config['parkCoefficients'])
  if width not in (4, 7) or len(config['halfLifeCoefficients']) != width:
    raise ValueError(
      'timing basis requires matching four or seven coefficient outputs')
  if width == 7:
    phi += [phi[1] * phi[2], phi[1] * phi[3], phi[2] * phi[3]]
  outputs = []
  for prefix in ('park', 'halfLife'):
    coefficients = config[prefix + 'Coefficients']
    u = sum(a * x for a, x in zip(coefficients[:4], phi[:4]))
    if width == 7:
      u += sum(a * x for a, x in zip(coefficients[4:], phi[4:]))
    lo, hi = config[prefix + 'MinNanos'], config[prefix + 'MaxNanos']
    value = config[prefix + 'ReferenceNanos'] * math.exp(min(700, u))
    outputs.append(max(lo, min(hi, math.floor(value + .5))))
  return tuple(outputs)


def resolve_topology(cpus):
  allowed = os.sched_getaffinity(0)
  if not set(cpus) <= allowed:
    raise ValueError('fixture CPUs not available to this process')
  mapping = {}
  for cpu in cpus:
    base = Path(f'/sys/devices/system/cpu/cpu{cpu}/topology')
    key = (int((base / 'physical_package_id').read_text()),
           int((base / 'core_id').read_text()))
    mapping.setdefault(key, []).append(cpu)
  return dict(resolvedWorkers=len(mapping), physicalCores=[
    dict(socket=k[0], core=k[1], logicalCpus=v) for k, v in
    sorted(mapping.items())])


def task(stage, controls):
  if stage != 'fixed_surface':
    from .cache_timing_policy import policy_task
    return policy_task(stage)
  features = [dict(name=n, source='fixture.' + s, transform=t) for n, s, t in (
    ('workers', 'resolvedWorkers', 'log'),
    ('sources', 'parallelSources', 'log1p'),
    ('workUnits', 'workUnits', 'log1p'))]
  terms = []
  names = [c['name'] for c in features + controls]
  for i, a in enumerate(names):
    for b in names[i:]:
      terms.append(
        dict(name=a + '_' + b, expression=dict(op='product', args=[a, b])))
  return dict(schemaVersion=1, id='cache_timing_' + stage,
              kind='response_surface',
              dataset=dict(
                manifest='../datasets/cache_timing_' + stage + '.json',
                adapter='jmh_response_surface'),
              features=features, controls=controls,
              targets=[dict(name='throughput', source='outcome.' + (
                'executionsPerSecond' if stage == 'fixed_surface' else 'throughputRatio'),
                            transform='log')],
              objective=dict(adapter='workload_normalized_throughput',
                             direction='maximize',
                             decisionOutput='throughput'),
              validation=dict(outerFolds=3, innerFolds=2, seed=SEED),
              candidates=[dict(id='constant', family='cart',
                               params=dict(min_samples_split=1000000)),
                          dict(id='quadratic', family='ridge',
                               basis=dict(terms=terms),
                               grid=dict(alpha=[.1, 1, 10])),
                          dict(id='shallow_boost', family='hist_boost',
                               params=dict(max_depth=2, max_iter=100,
                                           min_samples_leaf=5))],
              export=dict(kind='none'))


def controls_fixed():
  return [
    dict(name=name, source='treatment.' + name, units='ns', transform='log',
         configPath='/calibrationConfig/' + path,
         bounds=[min(values), max(values)])
    for name, path, values in [('parkNs', 'cacheParkNs', PARKS),
                               ('halfLifeNs', 'contentionHalfLifeNanos',
                                HALF_LIVES)]]


def controls_policy():
  from .cache_timing_policy import policy_task
  return policy_task()['controls']


def prepare(template_path, output):
  template = json.loads(template_path.read_text())
  cfg = template['calibrationConfig']
  topology = resolve_topology(cfg['cpuSet'])
  if topology['resolvedWorkers'] != 23 or 0 not in os.sched_getaffinity(0):
    raise ValueError(
      'prepared fixture requires resolved R23 and available harness CPU 0')
  if cfg['lifecycleMode'] != 'CONTINUOUS' or cfg[
    'productivityGateMode'] != 'AUTO' \
      or cfg.get('forcedActiveParticipantCount') is not None \
      or cfg['totalRequiredExecutions'] != 1000000 or cfg[
    'invocationTimeoutMillis'] != 120000 \
      or cfg['orderedSources'] != 0 or cfg['randomizeWork'] \
      or any(v for k, v in cfg.items() if k.startswith('observe')):
    raise ValueError('incompatible validation fixture')
  if template['jvmArgs'] != ['-Deuhedral.calibration.harnessCpu=0',
                             '--enable-native-access=ALL-UNNAMED']:
    raise ValueError('unexpected harness placement/JVM flags')
  if output.exists():
    raise ValueError('output directory must be new')
  cfg = deepcopy(cfg)
  cfg.update(cacheParkNs=15000, contentionHalfLifeNanos=1000000,
             cacheTimingFunction=None)
  # Runner resolves the current actuator identity; historical measurements are not reused.
  template = dict(id='cache-timing-template', enabled=False, forks=1, warmups=3,
                  iterations=5, warmupTime='2s', measurementTime='2s',
                  jvmArgs=template['jvmArgs'], calibrationConfig=cfg)
  harness = dict(schemaVersion=1, id='cache-timing-fixed-surface',
                 runOptions=dict(balancedTrialOrder=True,
                                 randomizeTrialOrder=False,
                                 failFast=False, repeatCount=1),
                 artifacts=dict(
                   outputDirectory='experiments/cache-timing-fixed-surface',
                   retainExpandedConfig=True, retainRawBenchmarkOutput=True,
                   retainObserverData=False, retainPerForkResults=True,
                   retainPerIterationResults=False),
                 trials=[template],
                 sweeps=[dict(id='fixed', baseTrialId=template['id'],
                              enabled=True, repetitions=2, parameters=[
                     dict(path='/calibrationConfig/' + p, values=v)
                     for p, v in
                     [('parallelSources', SOURCES), ('workUnits', WORK),
                      ('cacheParkNs', PARKS),
                      ('contentionHalfLifeNanos', HALF_LIVES)]])])
  code_paths = [
    'euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ParticipationLogisticModel.java',
    'euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/FragmentDecisionTree.java']
  provenance = dict(template=str(template_path.resolve()),
                    templateSha256=sha(template_path),
                    commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'],
                                                   cwd=ROOT, text=True).strip(),
                    sourceHashes={p: sha(ROOT / p) for p in code_paths},
                    topology=topology,
                    reuse='none: historical runs lack the new code/build identity',
                    normalization=normalization(),
                    normalizationHash=digest(normalization()))
  policies = [dict(id=f'park-{p}-half-{h}', kind='fixed', parameters=[p, h])
              for p, h in itertools.product(PARKS, HALF_LIVES)]
  for stage in ('fixed_surface', 'policy_search', 'closed_loop_validation'):
    spec = task(stage,
                controls_fixed() if stage == 'fixed_surface' else controls_policy())
    TrainingTaskSpec.model_validate(spec)
    write(output / 'tasks' / f'cache_timing_{stage}.json', spec)
    write(output / 'datasets' / f'cache_timing_{stage}.json', dict(
        schemaVersion=1, arms=[],
        status='prepared' if stage == 'fixed_surface' else 'awaiting_previous_stage',
        candidateManifest=policies if stage == 'fixed_surface' else [],
        allowedConfigDifferences=[c['configPath'] for c in spec['controls']],
        outputDirectory=f'experiments/cache-timing-{stage.replace("_", "-")}',
        plan=dict(sources=SOURCES, workUnits=WORK, workers=23, passes=2,
                  forksPerTreatment=1,
                  expectedJvmRuns=288 if stage == 'fixed_surface' else (
                    None),
                  prerequisite=None if stage == 'fixed_surface' else 'complete and fit previous campaign',
                  validation='new source/body fixtures at R7/R15; persistent dynamic scenarios; at most two finalists'),
        provenance=provenance))
  write(output / 'fixed_harness.json', harness)
  write(output / 'provenance.json', provenance)
  baseline = dict(normalization(), parkCoefficients=[0.0] * 4,
                  halfLifeCoefficients=[0.0] * 4)
  write(output / 'baseline_function.json',
        dict(status='unmeasured parity control',
             function=baseline,
             fallback=dict(cacheParkNs=15000, contentionHalfLifeNanos=1000000)))
  from .runtime_export import render_cache_timing
  with (output / 'CacheTimingEvaluator.java').open('x') as stream:
    stream.write(render_cache_timing(baseline))


def sobol_policies(fit_path, output):
  raise ValueError(
    'coefficient-only Sobol generation is retired; use cache_timing_policy prepare')


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  sub = parser.add_subparsers(dest='command', required=True)
  fixed = sub.add_parser('prepare')
  fixed.add_argument('--template', required=True, type=Path)
  fixed.add_argument('--output-dir', required=True, type=Path)
  policy = sub.add_parser('policies')
  policy.add_argument('--fit', required=True, type=Path)
  policy.add_argument('--output', required=True, type=Path)
  args = parser.parse_args()
  if args.command == 'prepare':
    prepare(args.template, args.output_dir)
  else:
    sobol_policies(args.fit, args.output)


if __name__ == '__main__':
  main()
