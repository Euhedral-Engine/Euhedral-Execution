"""Load hash-locked whole-policy response bundles for parameter-space interpolation."""
import json
import numpy as np
from ..feature_schema import extract, source
from ..training_data import TrainingDataset


def load(spec, manifest, base):
  if spec.validation.policy != 'parameter_holdout':
    raise ValueError(
      'policy response bundles require explicit parameter holdout')
  name = manifest['rows']
  if name not in manifest.get('hashes', {}):
    raise ValueError('policy response rows must be hash-locked')
  records = json.loads((base / name).read_text())
  x = np.array([[extract(r, v) for v in spec.features] for r in records])
  y = np.array([[extract(r, v) for v in spec.targets] for r in records])
  parameters = {}
  for r, row in zip(records, x):
    key = str(source(r, spec.dataset.familyKey))
    if key in parameters and parameters[key] != row.tolist():
      raise ValueError('parameter group contains different vectors')
    parameters[key] = row.tolist()
    if not r.get('sourceForks'):
      raise ValueError(
        'policy bundle must retain underlying whole-fork references')
  return TrainingDataset(tuple(r['rowId'] for r in records),
                         tuple(v.name for v in spec.features),
                         x, y, np.isfinite(y), np.ones(len(records)),
                         tuple(str(source(r, spec.dataset.familyKey)) for r in
                               records),
                         tuple(str(source(r, spec.dataset.blockKey)) for r in
                               records), metadata=tuple(records),
                         provenance=dict(manifest.get('provenance', {}),
                                         replicationUnit='JVM fork; a row bundles multiple forks, never a new independent trial',
                                         validationScope='unseen complete parameter vectors on fixed workload outputs',
                                         sharedBaseline='same OFF fork per workload/block; response outputs are correlated, not independent replicates'))
