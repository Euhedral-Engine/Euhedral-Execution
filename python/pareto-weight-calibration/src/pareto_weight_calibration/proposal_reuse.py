"""Verified JSON proposal-only replay of a frozen local tournament; never refits models."""

import importlib.metadata
import shutil
from pathlib import Path
import joblib
from threadpoolctl import threadpool_limits
from .surrogate_spec import SurrogateTask
from .training_spec import digest


def replay(spec, root, output):
  from .surrogate_tournament import read, write, sha, clean, proposal_search
  parent = (root / spec.reuseFit.directory).resolve()
  lock = parent / 'lock.json'
  if sha(lock) != spec.reuseFit.lockSha256:
    raise ValueError('reuse tournament lock identity changed')
  files = read(lock)['files']
  for name, expected in files.items():
    path = (parent / name).resolve()
    if not path.is_relative_to(parent) or sha(path) != expected:
      raise ValueError('reuse tournament artifact identity changed: ' + name)
  old = SurrogateTask.model_validate(read(parent / 'task.json'))
  # This mode changes coverage/preparation only. All modeling and predictive policy are frozen.
  a, b = old.model_dump(), spec.model_dump()
  for value in (a, b):
    for key in ('id', 'root', 'outputDirectory', 'provenance', 'reuseFit'):
      value.pop(key)
    if spec.reuseFit.allowProposalChanges:
      value.pop('proposal')
      continue
    for key in ('knownRegionCoverage', 'round', 'parentDataset'):
      value['proposal'].pop(key)
    bp = value['proposal'].get('benchmark')
    if bp:
      bp.pop('runDirectory')
  if a != b:
    raise ValueError(
      'proposal-only replay requires unchanged dataset/model/validation/search contracts')
  previous = read(parent / 'run.json')
  for package, version in previous['packages'].items():
    if importlib.metadata.version(package) != version:
      raise ValueError('reuse package version changed: ' + package)
  copied = ['dataset.json', 'dataset_manifest.json', 'historical_audit.tsv',
            'models.joblib',
            'selection.json', 'validation.json', 'model_registry.json',
            'model_leaderboard.tsv',
            'ensemble_results.tsv', 'held_theta_metrics.tsv',
            'campaign_transfer.tsv',
            'quadratic_comparison.tsv', 'reliability_policy.json',
            'output_reliability.json',
            'output_reliability.tsv', 'search_efficiency.json',
            'retrospective_comparison.tsv',
            'retrospective_held_actions.tsv']
  for name in copied:
    if name in files:
      shutil.copyfile(parent / name, output / name)
  # Only deserialize a local model covered by the explicitly pinned parent lock.
  restored = joblib.load(output / 'models.joblib')
  data = read(output / 'dataset.json')
  result = dict(models=restored['models'], choice=restored['selection'],
                proposalAuthority=read(output / 'output_reliability.json'),
                retrospective=read(output / 'search_efficiency.json')
                if (output / 'search_efficiency.json').exists() else {})
  names = [s + ':' + r.name for s in spec.systems for r in spec.responses]
  print('reusing verified frozen tournament; running proposal selection only',
        flush=True)
  with threadpool_limits(limits=1):
    proposals = proposal_search(spec, root, data, result, names, output)
  reuse = dict(directory=spec.reuseFit.directory, lockSha256=sha(lock),
               artifacts={n: files[n] for n in copied if n in files},
               parentSourceHashes=previous['sourceHashes'], modelRefit=False,
               interpretation='Prior dataset, fit, nested validation, reliability and retrospective results reused byte-for-byte')
  write(output / 'reuse_provenance.json', reuse)
  summary = dict(previous)
  summary.update(taskId=spec.id, taskHash=digest(spec.model_dump()),
                 reuseFit=reuse, elapsedSeconds=None, benchmarkExecuted=False,
                 selectedProposals=len(proposals['selected']),
                 proposalSearch={k: proposals[k] for k in
                                 ('searched', 'eligible', 'paretoSize',
                                  'stopReason')},
                 sourceHashes={str(p.relative_to(root)): sha(p) for p in
                               (
                                     root / 'python/pareto-weight-calibration/src/pareto_weight_calibration').rglob(
                                 '*.py')})
  write(output / 'run.json', clean(summary))
  write(output / 'lock.json', dict(files={str(p.relative_to(output)): sha(p)
                                          for p in output.rglob('*') if
                                          p.is_file()}))
  return summary
