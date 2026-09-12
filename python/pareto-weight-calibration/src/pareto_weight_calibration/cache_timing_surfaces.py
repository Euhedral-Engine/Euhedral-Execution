"""Deterministic selection of paired timing surfaces, with no scheduler outcomes."""
from __future__ import annotations

import itertools
import math
import numpy as np

from .cache_timing import normalization, evaluate, SEED
from .training_spec import digest

BASIS = dict(version='cache-timing-pairwise-v1',
             terms=['1', 'z_c', 'z_p', 'z_b', 'z_c*z_p', 'z_c*z_b', 'z_p*z_b'],
             coefficientOrder=[f'{axis}{i}' for axis in ('a', 'd') for i in
                               range(7)])
DESIGN = dict(version='cache-timing-behavior-v1', seed=SEED, maxCandidates=32,
              c=[0, .25, .5, .75, 1], p=[0, .25, .5, 1, 2, 4],
              b=[0, 4, 8, 12, 16],
              interiorPoints=64, minOutputSpan=.18,
              maxSingleBoundaryFraction=.60,
              minSurfaceRmsDistance=.06, axisWeights=[.5, .5],
              parkLevels=[.35, .55, .75], halfLifeLevels=[.3, .5, .7],
              strengths=[.22, .36, .5], familyMinimum=1,
              selection='family coverage then farthest surface from selected set',
              tieBreak='lexicographic design ID',
              controlExceptions='explicit controls only')
FAMILIES = (
  'low_phr_long_park_long_h', 'low_phr_long_park_short_h',
  'high_phr_short_park_short_h', 'contention_long_park',
  'contention_short_park', 'contention_long_h', 'contention_short_h',
  'body_long_h', 'body_short_h', 'body_short_park', 'body_long_park',
  'phr_contention_interaction', 'phr_body_interaction',
  'contention_body_interaction')


def state_points(design=DESIGN):
  grid = np.array(
    list(itertools.product(design['c'], design['p'], design['b'])), dtype=float)
  rng = np.random.default_rng(design['seed'])
  interior = rng.uniform([0, 0, 0], [1, 4, 16],
                         size=(design['interiorPoints'], 3))
  return grid, interior


def basis(points):
  z = (points - np.array([.5, 2, 8])) / np.array([.5, 2, 8])
  c, p, b = z.T
  return np.column_stack((np.ones(len(z)), c, p, b, c * p, c * b, p * b))


def targets(family, phi, park_level, h_level, strength):
  _, c, p, b, cp, cb, pb = phi.T
  tau = np.full(len(phi), park_level)
  h = np.full(len(phi), h_level)
  if family == 'low_phr_long_park_long_h':
    tau -= strength * p;
    h = .65 - .15 * p
  elif family == 'low_phr_long_park_short_h':
    tau -= strength * p;
    h = .25 + .15 * p
  elif family == 'high_phr_short_park_short_h':
    tau = .4 - strength * p;
    h = h_level - strength * .5 * p
  elif family.startswith('contention_') and family.endswith('_park'):
    tau += (1 if 'long' in family else -1) * strength * c
  elif family.startswith('contention_') and family.endswith('_h'):
    h += (1 if 'long' in family else -1) * strength * c
  elif family.startswith('body_') and family.endswith('_h'):
    h += (1 if 'long' in family else -1) * strength * b
  elif family.startswith('body_') and family.endswith('_park'):
    tau += (1 if 'long' in family else -1) * strength * b
  elif family == 'phr_contention_interaction':
    tau += strength * cp;
    h -= strength * .75 * cp
  elif family == 'phr_body_interaction':
    tau -= strength * pb;
    h += strength * pb
  elif family == 'contention_body_interaction':
    tau += strength * cb;
    h -= strength * cb
  else:
    raise ValueError(f'unknown behavior family: {family}')
  return np.column_stack((tau, h))


def normalized_outputs(config, points):
  timings = np.array(
      [evaluate(config, c, p, math.expm1(b)) for c, p, b in points])
  return np.log(timings / [15000, 250000]) / np.log([814375 / 15000, 8])


def surface_metrics(surface, grid):
  # Rounded outputs can differ from exact boundary coordinates at machine precision.
  clamp = [dict(lower=float(np.mean(surface[:, i] <= 1e-12)),
                upper=float(np.mean(surface[:, i] >= 1 - 1e-12))) for i in
           range(2)]
  spans = np.ptp(surface, axis=0).tolist()
  slices = {}
  for axis, name in enumerate(('contention', 'phr', 'bodyLogNs')):
    for value in sorted(set(grid[:, axis])):
      values = surface[:len(grid)][grid[:, axis] == value]
      slices[f'{name}={value}'] = np.ptp(values, axis=0).tolist()
  return dict(span=spans, clampOccupancy=clamp, sliceSpans=slices)


def distance(a, b):
  return float(np.sqrt(np.mean((a - b) ** 2)))


def select_surfaces(design=DESIGN):
  grid, interior = state_points(design)
  points = np.vstack((grid, interior))
  phi = basis(points)
  pool = []
  rejected = dict(flat=0, clamped=0)
  for family, park, h, strength in itertools.product(
      FAMILIES, design['parkLevels'], design['halfLifeLevels'],
      design['strengths']):
    desired = targets(family, phi, park, h, strength)
    # Fit desired physical log-output values at representative states, not random coefficients.
    log_offsets = desired * np.log([814375 / 15000, 8]) + [0, math.log(.25)]
    coefs = np.linalg.lstsq(phi, log_offsets, rcond=None)[0]
    config = dict(normalization(), parkCoefficients=coefs[:, 0].tolist(),
                  halfLifeCoefficients=coefs[:, 1].tolist())
    surf = normalized_outputs(config, points)
    metrics = surface_metrics(surf, grid)
    if max(metrics['span']) < design['minOutputSpan']:
      rejected['flat'] += 1;
      continue
    if max(v for axis in metrics['clampOccupancy'] for v in axis.values()) > \
        design['maxSingleBoundaryFraction']:
      rejected['clamped'] += 1;
      continue
    pool.append(dict(id=f'{family}-p{park}-h{h}-s{strength}', family=family,
                     target=dict(parkLevel=park, halfLifeLevel=h,
                                 strength=strength),
                     function=config, surface=surf, **metrics))
  selected = []

  def min_distance(candidate):
    return min((distance(candidate['surface'], x['surface']) for x in selected),
               default=1.)

  # Reserve every named physical behavior before adding globally diverse variants.
  for family in FAMILIES:
    choices = [x for x in pool if
               x['family'] == family and min_distance(x) >= design[
                 'minSurfaceRmsDistance']]
    if not choices:
      raise ValueError(
        f'no distinct acceptable surface for family {family}; do not relax thresholds silently')
    selected.append(min(choices, key=lambda x: (-min_distance(x), x['id'])))
  while len(selected) < design['maxCandidates']:
    choices = [x for x in pool if
               min_distance(x) >= design['minSurfaceRmsDistance']]
    if not choices: break
    selected.append(min(choices, key=lambda x: (-min_distance(x), x['id'])))
  policies = []
  for i, candidate in enumerate(selected):
    other = [x for x in selected if x is not candidate]
    neighbors = sorted(
        (distance(candidate['surface'], x['surface']), x['id']) for x in other)
    policy = {k: v for k, v in candidate.items() if k != 'surface'}
    policy.update(id=f'live-{i:02}', designId=candidate['id'], kind='live',
                  control=False,
                  surfaceSignature=digest(candidate['surface'].tolist()),
                  nearestSurfaceDistance=neighbors[0][0],
                  nearestDesignId=neighbors[0][1])
    policies.append(policy)
  return dict(schemaVersion=1, status='unmeasured', basis=BASIS,
              basisHash=digest(BASIS),
              normalization=normalization(),
              normalizationHash=digest(normalization()),
              design=design, designHash=digest(design), grid=grid.tolist(),
              interior=interior.tolist(),
              pointCoordinates=['contention', 'phr', 'log1p(bodyCostNs)'],
              evaluatedPoolSize=len(pool), rejected=rejected, policies=policies)
