"""CACHE adapter for the generic parameter tuner; no online or JMH execution during preparation."""
import argparse
from copy import deepcopy
import csv
import importlib.metadata
import itertools
import json
import math
import os
from pathlib import Path
import platform
import tempfile
import numpy as np
from . import cache_timing_confirmation as campaign
from .cache_timing import ROOT, evaluate, sha, write
from .cache_timing_policy import freeze, source_hashes
from .runtime_export import render_cache_timing
from .training_spec import digest
from .tuning_spec import TuningSpec
from . import parameter_tuning as tuner

PARENT = ROOT / 'benchmarks/src/test/resources/cache-timing'


def read(path): return json.loads(path.read_text())


def versions():
  return dict(python=platform.python_version(),
              **{name: importlib.metadata.version(name)
                 for name in
                 ('numpy', 'scipy', 'scikit-learn', 'pydantic', 'torch')})


def verify_original():
  old = PARENT / 'live-v2';
  lock = read(old / 'lock.json')
  for name, h in lock['files'].items():
    if sha(old / name) != h: raise ValueError(
      'frozen screening input changed: ' + name)
  # Participation and runtime code must match the completed confirmation campaign.
  prior = PARENT / 'confirmation-v2';
  identity = read(prior / 'lock.json')
  for name, h in identity['sourceHashes'].items():
    if name.endswith('.java') and sha(ROOT / name) != h:
      raise ValueError('runtime/classifier changed since confirmation: ' + name)
  for name, h in identity['files'].items():
    if sha(prior / name) != h: raise ValueError(
      'frozen confirmation input changed')
  return read(old / 'candidate_manifest.json'), read(
    prior / 'candidate_manifest.json')


def load_spec(path): return TuningSpec.model_validate_json(path.read_text())


def center_for(spec):
  source, _ = verify_original()
  expected = spec.provenance['screenedManifestSha256']
  if sha(PARENT / 'live-v2/candidate_manifest.json') != expected:
    raise ValueError('source manifest differs from tuning spec')
  return next(p['function'] for p in source['policies'] if
              p['id'] == spec.sourcePolicyId)


def inspector(spec, center):
  points = np.array(spec.runtime['supportPoints'])

  def timings(config, selected):
    return np.array(
        [evaluate(config, float(c), float(p), math.expm1(float(b))) for c, p, b
         in selected])

  center_out = timings(center, points)
  ranges = np.log([center['parkMaxNanos'] / center['parkMinNanos'],
                   center['halfLifeMaxNanos'] / center['halfLifeMinNanos']])

  def inspect(config):
    # Carry every untuned scalar and residual through exactly.
    restored = deepcopy(config)
    for p in spec.parameters: tuner.pointer(restored, p.path, p.center, True)
    if restored != center: raise ValueError('untuned runtime field changed')
    out = timings(config, points)
    bounds = [(center['parkMinNanos'], center['parkMaxNanos']),
              (center['halfLifeMinNanos'], center['halfLifeMaxNanos'])]
    occupancy = []
    for j, (lo, hi) in enumerate(bounds):
      if np.any((out[:, j] < lo) | (out[:, j] > hi)): raise ValueError(
        'output bounds violated')
      occupancy.append(dict(lower=float(np.mean(out[:, j] == lo)),
                            upper=float(np.mean(out[:, j] == hi))))
    if max(x for axis in occupancy for x in axis.values()) > spec.runtime[
      'maxSingleBoundaryFraction']:
      raise ValueError('surface dominated by one clamp boundary')
    for c, b in itertools.product([0., 1.], [0., 16.]):
      pairs = timings(config, [(c, p, b) for p in np.linspace(0, 4, 65)])
      if np.any(np.diff(pairs[:, 0]) > 0) or np.any(np.diff(pairs[:, 1]) < 0):
        raise ValueError('PHR monotonic direction violated')
    representative = [dict(phr=p, parkNanos=int(t[0]), halfLifeNanos=int(t[1]))
                      for p, t in zip(spec.runtime['representativePhr'],
                                      timings(config, [(.5, p, 8.) for p in
                                                       spec.runtime[
                                                         'representativePhr']]))]
    distance = float(np.sqrt(np.mean((np.log(out / center_out) / ranges) ** 2)))
    grid = out[:spec.runtime['gridCount']]
    return dict(representative=representative, clampOccupancy=occupancy,
                gridClampOccupancy=[dict(lower=float(np.mean(grid[:, j] == lo)),
                                         upper=float(np.mean(grid[:, j] == hi)))
                                    for j, (lo, hi) in enumerate(bounds)],
                outputRange=[dict(minimum=int(out[:, j].min()),
                                  maximum=int(out[:, j].max())) for j in
                             range(2)],
                surfaceDistance=distance, monotonic=True,
                surfaceSignature=digest(out.tolist()))

  return inspect


def audit_region(spec, center, inspect):
  # Boundary corners are numerical support checks only, never benchmark candidates.
  corners = []
  for unit in itertools.product([0., 1.], repeat=len(spec.parameters)):
    config = tuner.construct(spec.parameters, center,
                             tuner.map_parameters(spec.parameters, unit))
    corners.append(inspect(config))
  return dict(
    purpose='bound verification only; corner values are not candidates',
    cornersChecked=len(corners), allValid=True,
    maxSingleBoundaryFraction=max(
        v for x in corners for axis in x['clampOccupancy'] for v in
        axis.values()),
    outputRanges=[
      dict(minimum=min(x['outputRange'][j]['minimum'] for x in corners),
           maximum=max(x['outputRange'][j]['maximum'] for x in corners)) for j
      in range(2)],
    chosenBounds='35 percent of absolute center magnitude in every active coordinate',
    claim='bounded numeric family check only, no measured throughput evidence')


def catalog(output, policies):
  rows = []
  for p in policies:
    b = p['behavior']
    row = dict(policyId=p['id'], sourcePolicyId=p['sourcePolicyId'],
               role=p['role'], sampleIndex=p['sampleIndex'],
               coordinateDistance=p['coordinateDistance'],
               surfaceDistance=b['surfaceDistance'], monotonic=b['monotonic'])
    for i, (name, v) in enumerate(p['parameters'].items()):
      row[name] = v;
      row[name + '_delta'] = p['delta'][i];
      row[name + '_unit'] = p['normalizedCoordinates'][i]
    for point in b['representative']:
      for axis in ['parkNanos', 'halfLifeNanos']: row[
        axis + '_phr' + str(point['phr'])] = point[axis]
    for j, axis in enumerate(['park', 'H']):
      for k, v in b['clampOccupancy'][j].items(): row[axis + '_clamp_' + k] = v
      for k, v in b['outputRange'][j].items(): row[axis + '_' + k] = v
    row['fullCoefficients'] = json.dumps(
      p['function']['parkCoefficients'] + p['function']['halfLifeCoefficients'],
      separators=(',', ':'))
    rows.append(row)
  campaign.tsv(output, 'surface_catalog.tsv', rows)


def prepare(spec_path, output, policies=None, proposal_provenance=None):
  if output.exists(): raise ValueError(
    'prepare into a new revision; preserve all frozen artifacts')
  spec = load_spec(spec_path);
  center = center_for(spec);
  inspect = inspector(spec, center)
  if policies is None:
    policies, generation = tuner.initial_candidates(spec, center, inspect)
  else:
    generation = proposal_provenance
    if not policies: raise ValueError(
      'no predicted proposals; do not emit an empty follow-up')
    policies = [tuner.candidate(spec, center,
                                [p.center for p in spec.parameters], None,
                                'center', inspect)] + policies
  for p in policies: inspect(p['function'])
  output.mkdir(parents=True)
  write(output / 'tuning_spec.json', spec.model_dump(mode='json'))
  write(output / 'bounds_audit.json', audit_region(spec, center, inspect))
  write(output / 'generation.json', generation)
  write(output / 'proposal_config.json', spec.proposal.model_dump(mode='json'))
  write(output / 'environment.json', versions())
  (output / 'requirements.txt').write_text('\n'.join(sorted(
      f'{dist.metadata["Name"]}=={dist.version}' for dist in
      importlib.metadata.distributions()
      if dist.metadata['Name'].lower() != 'pip')) + '\n')
  for p in policies:
    write(output / 'policies' / p['id'] / 'config.json', p['function'])
    (output / 'policies' / p['id'] / 'CacheTimingEvaluator.java').write_text(
      render_cache_timing(p['function']))
  write(output / 'POLICY_OFF.json',
        dict(cacheTimingFunction=None, cacheParkNs=15000,
             contentionHalfLifeNanos=1000000))
  catalog(output, policies)
  _, prior = verify_original()
  topologies = prior['topologies']
  template = read(PARENT / 'confirmation-v2/r23_harness.json')['trials'][0]
  arms = [dict(id='POLICY_OFF', mode='OFF', function=None)] + [
    dict(id=p['id'], mode='ON', function=p['function']) for p in policies]
  trials = []
  for index, (fixture, arm) in enumerate(
      itertools.product(spec.fixtures, arms)):
    for block in range(spec.blocks):
      trial = deepcopy(template);
      cfg = trial['calibrationConfig'];
      r = str(fixture['resolvedWorkers'])
      cpus = sorted(cpu for core in topologies[r]['physicalCores'] for cpu in
                    core['logicalCpus'])
      cfg.update(cpuSet=cpus, parallelSources=fixture['parallelSources'],
                 workUnits=fixture['workUnits'],
                 cacheTimingFunction=arm['function'])
      trial.update(id=f'{output.name}-{index}-pass-{block}',
                   origin=dict(type='SWEEP', sourceId=output.name,
                               seed=spec.initial.seed, candidateIndex=index,
                               sampleIndex=block),
                   labels=dict(policyId=arm['id'],
                               workloadId=fixture['workloadId'],
                               workloadClass=fixture['workloadClass']))
      trials.append(trial)
  run_dir = 'experiments/cache-timing-' + output.name
  info = dict(harness='tuning_harness.json', fixtures=list(spec.fixtures),
              expectedForks=len(trials), windowsPerFork=5,
              outputDirectory=run_dir, evidenceDirectory=run_dir + '-evidence',
              requiredReviews=[])
  harness = read(PARENT / 'confirmation-v2/r23_harness.json')
  harness.update(id=output.name, trials=trials)
  harness['artifacts']['outputDirectory'] = run_dir
  write(output / 'tuning_harness.json', harness)
  manifest = dict(schemaVersion=1, id=output.name, status='unexecuted',
                  baseline='POLICY_OFF', blocks=spec.blocks,
                  arms=arms, policies=policies, topologies=topologies,
                  stages=dict(tuning=info),
                  sourceHashes=source_hashes(),
                  tuningSpecHash=digest(spec.model_dump(mode='json')),
                  allowedTimingDifferences=[
                    '/calibrationConfig/cacheTimingFunction'],
                  provenance=spec.provenance,
                  measuredParameterHistory=(proposal_provenance or {}).get(
                    'measuredParameterVectors', []))
  write(output / 'candidate_manifest.json', manifest)
  task = tuner.training_task(spec, os.path.relpath(
    ROOT / info['evidenceDirectory'] / 'dataset.json', output))
  write(output / 'task.json', task.model_dump(mode='json'))
  write(output / 'dataset_config.json',
        dict(schemaVersion=1, adapter='policy_response',
             output=info['evidenceDirectory'] + '/dataset.json',
             expectedBundles=len(policies) * spec.blocks,
             targets=[r.model_dump(mode='json') for r in spec.responses],
             independentUnit='JVM fork',
             rowMeaning='one complete policy/block bundle across fixed workloads',
             groupBy='policyId',
             validation='hold all blocks and all constituent workload families of each held parameter vector together; no unseen-workload claim'))
  write(output / 'collection_config.json',
        dict(schemaVersion=1, stage='tuning', **info,
             retainEveryFork=True, retainEveryWindow=True,
             baseline='POLICY_OFF'))
  write(output / 'analysis_config.json',
        dict(schemaVersion=1, objectives=spec.proposal.model_dump(mode='json'),
             report='per topology scarce/plentiful; each scarce body; worst scarce/plentiful; positive scarce count; minimum topology scarcity; both minima; coordinate distance',
             overall='descriptive only', automaticDeployment=False))
  return manifest


def finalize(output):
  if (output / 'lock.json').exists(): raise ValueError(
    'frozen revision already exists')
  spec = load_spec(output / 'tuning_spec.json');
  m = read(output / 'candidate_manifest.json')
  if m['sourceHashes'] != source_hashes(): raise ValueError(
    'sources changed after preparation; regenerate a new draft')
  center = center_for(spec);
  inspect = inspector(spec, center)
  expected, generation = tuner.initial_candidates(spec, center, inspect)
  if read(output / 'generation.json').get('design') == spec.initial.model_dump(
      mode='json'):
    if expected != m['policies'] or generation != read(
        output / 'generation.json'):
      raise ValueError('initial candidate regeneration mismatch')
  for p in m['policies']:
    if read(output / 'policies' / p['id'] / 'config.json') != p['function'] or (
        output / 'policies' / p[
      'id'] / 'CacheTimingEvaluator.java').read_text() != render_cache_timing(
        p['function']):
      raise ValueError('policy/export mismatch')
    if inspect(p['function']) != p['behavior']: raise ValueError(
      'surface mismatch')
  freeze(output);
  check(output)


def check(output):
  m = campaign.check(output, 'tuning')
  if read(output / 'environment.json') != versions(): raise ValueError(
    'Python dependency identity changed; restore frozen versions')
  return m


def collect(output):
  m = read(output / 'candidate_manifest.json');
  spec = load_spec(output / 'tuning_spec.json')
  campaign.collect(output, 'tuning')
  e = ROOT / m['stages']['tuning']['evidenceDirectory']
  records = read(e / 'arms.json');
  rows = tuner.response_rows(spec, m['policies'], records)
  write(e / 'response_rows.json', rows)
  write(e / 'dataset.json', dict(schemaVersion=1, rows='response_rows.json',
                                 hashes={name: sha(e / name) for name in
                                         ('response_rows.json', 'arms.json')},
                                 provenance=dict(
                                   handoffLockSha256=sha(output / 'lock.json'),
                                   tuningSpecHash=m['tuningSpecHash'],
                                   baseline='POLICY_OFF',
                                   source='all retained whole scheduler forks')))
  pooled = list(csv.DictReader((e / 'per_workload.tsv').open(), delimiter='\t'))
  classes = list(
    csv.DictReader((e / 'class_summary.tsv').open(), delimiter='\t'))
  summary = []
  for p in m['policies']:
    selected = [r for r in pooled if r['policyId'] == p['id']]
    scarce = [r for r in selected if r['workloadClass'] == 'PRIMARY'];
    plenty = [r for r in selected if r['workloadClass'] == 'GUARDRAIL']
    row = dict(policyId=p['id'], coordinateDistance=p['coordinateDistance'],
               surfaceDistance=p['behavior']['surfaceDistance'],
               positiveScarceWorkloads=sum(
                   float(r['percentChange']) > 0 for r in scarce),
               worstScarceWorkload=
               min(scarce, key=lambda r: float(r['percentChange']))[
                 'workloadId'],
               worstScarceChange=min(float(r['percentChange']) for r in scarce),
               worstPlentifulWorkload=
               min(plenty, key=lambda r: float(r['percentChange']))[
                 'workloadId'],
               worstPlentifulChange=min(
                   float(r['percentChange']) for r in plenty))
    aggregates = []
    for r in classes:
      if r['policyId'] == p['id']:
        row['R' + r['resolvedWorkers'] + '_' + r['workloadClass']] = float(
            r['geometricChangePercent'])
        if r['workloadClass'] == 'PRIMARY': aggregates.append(
          float(r['geometricChangePercent']))
    row['minimumTopologyScarcity'] = min(aggregates);
    summary.append(row)
  campaign.tsv(e, 'tuning_summary.tsv', summary)
  receipt = read(e / 'evidence_manifest.json')
  receipt['files'] = {p.name: sha(p) for p in e.iterdir() if
                      p.is_file() and p.name != 'evidence_manifest.json'}
  (e / 'evidence_manifest.json').write_text(
    json.dumps(receipt, indent=2) + '\n')


def proposals(handoff, fit_dir, output):
  check(handoff);
  spec = load_spec(handoff / 'tuning_spec.json');
  m = read(handoff / 'candidate_manifest.json')
  e = ROOT / m['stages']['tuning']['evidenceDirectory'];
  receipt = read(e / 'evidence_manifest.json')
  for name, h in receipt['files'].items():
    if sha(e / name) != h: raise ValueError('collected evidence changed')
  fit = read(fit_dir / 'run.json')
  if fit['taskHash'] != tuner.training_task(spec, read(handoff / 'task.json')[
    'dataset']['manifest']).schema_hash or fit['provenance'][
    'manifestSha256'] != sha(e / 'dataset.json'):
    raise ValueError('fit does not belong to frozen task/evidence')
  center = center_for(spec)
  measured = m.get('measuredParameterHistory', []) + [p['parameterVector'] for p
                                                      in m['policies']]
  selected, report = tuner.propose(spec, center, fit['evaluator'], measured,
                                   inspector(spec, center))
  if not selected: raise ValueError(
    'no admissible predicted improvement; do not relax gates automatically: ' + json.dumps(
      report))
  for p in selected: p['id'] = output.name + '-' + str(p['sampleIndex'])
  report.update(parentHandoff=str(handoff.resolve()),
                parentLockSha256=sha(handoff / 'lock.json'),
                fitPath=str(fit_dir.resolve()), evidencePath=str(e.resolve()),
                measuredParameterVectors=measured,
                fitSha256=sha(fit_dir / 'run.json'),
                evidenceReceiptSha256=sha(e / 'evidence_manifest.json'))
  prepare(handoff / 'tuning_spec.json', output, selected, report)
  finalize(output)


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument('command',
                      choices=['prepare', 'freeze', 'check', 'run', 'collect',
                               'propose'])
  parser.add_argument('--spec', type=Path, required=True)
  parser.add_argument('--handoff', type=Path, required=True)
  parser.add_argument('--fit-dir', type=Path);
  parser.add_argument('--output-dir', type=Path)
  a = parser.parse_args()
  if a.command == 'prepare':
    m = prepare(a.spec, a.handoff);
    print('Draft generated; JVMs:', m['stages']['tuning']['expectedForks'])
  elif a.command == 'freeze':
    finalize(a.handoff);print('Frozen; no benchmarks executed')
  elif a.command == 'check':
    check(a.handoff);print(
      'Frozen inputs, sources, topology and Python environment verified')
  elif a.command == 'run':
    check(a.handoff);campaign.launch(a.handoff, 'tuning', [])
  elif a.command == 'collect':
    collect(a.handoff)
  else:
    if a.fit_dir is None or a.output_dir is None: parser.error(
      'propose requires --fit-dir and --output-dir')
    proposals(a.handoff, a.fit_dir, a.output_dir)


if __name__ == '__main__': main()
