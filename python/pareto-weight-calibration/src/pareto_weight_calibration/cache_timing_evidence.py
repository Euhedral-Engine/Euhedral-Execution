"""Retain one observation per JVM and prepare matched timing tables for the generic runner."""
from __future__ import annotations

import argparse
from collections import defaultdict
from copy import deepcopy
import csv
import itertools
import json
import math
from pathlib import Path
import re
import statistics
import subprocess

from .cache_timing import ROOT, PARKS, HALF_LIVES, SOURCES, WORK, sha, write, \
  resolve_topology
from .training_data import canonical_config
from .training_spec import TrainingTaskSpec
from .task_adapters import load_dataset


def windows(text):
  pattern = r'^Iteration\s+(\d+):[^\n]*\n\s+executions:\s+([0-9.eE+-]+) ops/s$'
  values = [(int(i), float(v)) for i, v in re.findall(pattern, text, re.M)]
  if [i for i, _ in values] != list(range(1, 6)) or any(
      not math.isfinite(v) or v <= 0 for _, v in
      values) or '# Fork: 1 of 1' not in text:
    raise ValueError(
      'require one complete fork with all five positive execution windows')
  if re.search(r'<failure>|Exception|ERROR', text):
    raise ValueError('failure in retained benchmark log')
  return [v for _, v in values]


def binaries():
  files = [ROOT / 'benchmarks/build/euhedral-benchmark.jar'] + sorted(
      (ROOT / 'benchmarks/build/lib').glob('*.jar'))
  if len(files) < 2 or not files[0].is_file():
    raise ValueError('build the benchmark distribution first')
  return {str(p.relative_to(ROOT)): sha(p) for p in files}


def launch(handoff):
  harness_path = handoff / 'fixed_harness.json'
  harness = json.loads(harness_path.read_text())
  output = ROOT / harness['artifacts']['outputDirectory']
  if output.exists():
    raise ValueError(
      'run output must not exist; never overwrite completed or partial runs')
  provenance = json.loads((handoff / 'provenance.json').read_text())
  topology = resolve_topology(
      harness['trials'][0]['calibrationConfig']['cpuSet'])
  if topology != provenance['topology']:
    raise ValueError('host topology changed; regenerate the handoff')
  subprocess.run(['mise', 'exec', '--', 'gradle', ':benchmarks:assemble'],
                 cwd=ROOT, check=True)
  identity = dict(binaryHashes=binaries(), harnessSha256=sha(harness_path),
                  topology=topology, sourceHashes=provenance['sourceHashes'],
                  java=subprocess.check_output(
                      ['mise', 'exec', '--', 'java', '-version'],
                      stderr=subprocess.STDOUT, cwd=ROOT, text=True))
  for p, expected in provenance['sourceHashes'].items():
    if sha(ROOT / p) != expected:
      raise ValueError('frozen source changed; regenerate the handoff')
  output.mkdir(parents=True, exist_ok=False)
  write(output / 'identity.json', identity)
  subprocess.run(['mise', 'exec', '--', 'bash',
                  'benchmarks/build/bin/euhedral-calibration',
                  'run', str(harness_path.resolve())], cwd=ROOT, check=True)


def collect(handoff, output):
  if output.exists():
    raise ValueError('evidence output must be new')
  harness_path = handoff / 'fixed_harness.json'
  harness = json.loads(harness_path.read_text())
  runs = ROOT / harness['artifacts']['outputDirectory']
  identity = json.loads((runs / 'identity.json').read_text())
  if identity['harnessSha256'] != sha(harness_path):
    raise ValueError('harness changed after launch')
  records = []
  seen = set()
  paths = ['/calibrationConfig/cacheParkNs',
           '/calibrationConfig/contentionHalfLifeNanos']
  runtime_headers = None
  for config_path in sorted(runs.glob('*/trial_config.json')):
    trial = json.loads(config_path.read_text())
    cfg = trial['calibrationConfig']
    origin = trial['origin']
    key = (cfg['parallelSources'], cfg['workUnits'], cfg['cacheParkNs'],
           cfg['contentionHalfLifeNanos'], origin['sampleIndex'])
    if key in seen:
      raise ValueError('duplicate treatment/pass')
    seen.add(key)
    for name in ('forks', 'warmups', 'iterations', 'warmupTime',
                 'measurementTime', 'jvmArgs'):
      if trial[name] != harness['trials'][0][name]:
        raise ValueError('measurement schedule or JVM args changed')
    original = dict(calibrationConfig=deepcopy(cfg))
    expected = deepcopy(harness['trials'][0]['calibrationConfig'])
    # Workloads vary intentionally; actuator version is resolved by the runner.
    expected.update(parallelSources=key[0], workUnits=key[1],
                    cacheActuatorVersion=cfg['cacheActuatorVersion'])
    if canonical_config(original, paths) != canonical_config(
        dict(calibrationConfig=expected), paths):
      raise ValueError('non-treatment fixture mismatch')
    log_path = config_path.parent / 'benchmark_output.log'
    text = log_path.read_text()
    headers = [line for line in text.splitlines() if
               line.startswith(('# JMH version:', '# VM version:'))]
    if len(headers) != 2 or (
        runtime_headers is not None and headers != runtime_headers):
      raise ValueError('mixed/missing JMH or JVM identity')
    runtime_headers = headers
    values = windows(text)
    fixture = dict(identity['topology'], parallelSources=key[0],
                   workUnits=key[1])
    records.append(dict(runId=config_path.parent.name, forkId='1',
                        workloadId=f'R23-S{key[0]}-W{key[1]}',
                        passId=str(key[4]),
                        identity=dict(identity, runtimeHeaders=headers),
                        fixture=fixture,
                        policyKind='fixed', policyParameters=list(key[2:4]),
                        treatment=dict(parkNs=key[2], halfLifeNs=key[3]),
                        config=original,
                        outcome=dict(
                          executionsPerSecond=statistics.mean(values),
                          measurementWindows=values),
                        provenance=dict(configPath=str(config_path),
                                        configSha256=sha(config_path),
                                        logPath=str(log_path),
                                        logSha256=sha(log_path),
                                        originalTrial=trial)))
  expected = set(itertools.product(SOURCES, WORK, PARKS, HALF_LIVES, range(2)))
  if seen != expected:
    raise ValueError(
      f'incomplete fixed surface: {len(seen)} of 288 forks; retain partial/slow runs')
  write(output / 'arms.json', records)
  write(output / 'datasets/cache_timing_fixed_surface.json',
        dict(schemaVersion=1,
             arms=[dict(path='../arms.json', sha256=sha(output / 'arms.json'))],
             campaignIdentity=identity))
  spec = json.loads(
    (handoff / 'tasks/cache_timing_fixed_surface.json').read_text())
  spec['dataset']['inventory'] = dict(rows=288, families=9)
  write(output / 'tasks/cache_timing_fixed_surface.json', spec)
  load_dataset(TrainingTaskSpec.model_validate(spec), output / 'tasks')
  tables(records, output)


def tables(records, output):
  blocks = defaultdict(dict)
  for r in records:
    blocks[r['workloadId'], r['passId']][tuple(r['policyParameters'])] = \
    r['outcome']['measurementWindows']
  matched = []
  pooled = defaultdict(list)
  for (workload, pass_id), treatments in sorted(blocks.items()):
    baseline = treatments[(15000, 1000000)]
    for (park, half), values in sorted(treatments.items()):
      ratio = statistics.mean(values) / statistics.mean(baseline)
      matched.append(
        dict(workloadId=workload, passId=pass_id, parkNs=park, halfLifeNs=half,
             baselineMean=statistics.mean(baseline),
             candidateMean=statistics.mean(values),
             percentChange=100 * (ratio - 1), logRatio=math.log(ratio),
             baselineMinimumWindow=min(baseline),
             candidateMinimumWindow=min(values)))
      pooled[workload, park, half].append((baseline, values))
  summary = []
  scores = defaultdict(list)
  for (workload, park, half), pairs in sorted(pooled.items()):
    base_means = [statistics.mean(a) for a, _ in pairs]
    means = [statistics.mean(b) for _, b in pairs]
    ratio = statistics.mean(means) / statistics.mean(base_means)
    scores[park, half].append(math.log(ratio))
    summary.append(dict(workloadId=workload, parkNs=park, halfLifeNs=half,
                        baselineMean=statistics.mean(base_means),
                        candidateMean=statistics.mean(means),
                        percentChange=100 * (ratio - 1),
                        logRatio=math.log(ratio),
                        baselineMinimumForkMean=min(base_means),
                        candidateMinimumForkMean=min(means),
                        baselineMinimumWindow=min(min(a) for a, _ in pairs),
                        candidateMinimumWindow=min(min(b) for _, b in pairs)))
  for name, rows in [('matched_pass', matched),
                     ('throughput_by_workload', summary),
                     ('policy_score', [dict(parkNs=p, halfLifeNs=h,
                                            meanLogRatio=statistics.mean(v),
                                            worstWorkloadLogRatio=min(v)) for
                                       (p, h), v in sorted(scores.items())])]:
    with (output / (name + '.tsv')).open('x') as stream:
      writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t')
      writer.writeheader()
      writer.writerows(rows)


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument('command', choices=['run-fixed', 'collect-fixed'])
  parser.add_argument('--handoff', type=Path, required=True)
  parser.add_argument('--output-dir', type=Path)
  args = parser.parse_args()
  if args.command == 'run-fixed':
    launch(args.handoff)
  else:
    if args.output_dir is None:
      parser.error('--output-dir required for collection')
    collect(args.handoff, args.output_dir)


if __name__ == '__main__':
  main()
