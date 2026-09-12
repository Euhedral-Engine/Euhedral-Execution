"""Generic Sobol parameter generation, response construction and Pareto proposals.

Runtime geometry is supplied by an adapter; shared code has no parameter/output width assumptions.
"""
from copy import deepcopy
import itertools
import math
import numpy as np
from scipy.stats import qmc
from .training_spec import Basis, TrainingTaskSpec, digest
from .runtime_export import predict


def pointer(config, path, value=None, assign=False):
  parts = [p.replace('~1', '/').replace('~0', '~') for p in path[1:].split('/')]
  node = config
  for p in parts[:-1]:
    node = node[int(p)] if isinstance(node, list) else node[p]
  key = int(parts[-1]) if isinstance(node, list) else parts[-1]
  if assign:
    node[key] = value
  return node[key]


def map_parameters(parameters, unit):
  unit = np.asarray(unit, dtype=float)
  if unit.shape != (len(parameters),) or not np.isfinite(unit).all() or np.any(
      (unit < 0) | (unit > 1)):
    raise ValueError('invalid normalized coordinates')
  result = []
  for p, x in zip(parameters, unit):
    lo, hi = p.bounds
    result.append(float(math.exp(math.log(lo) + x * math.log(hi / lo))
                        if p.transform == 'log' else lo + x * (hi - lo)))
  return result


def normalize(parameters, values):
  return [(math.log(x / p.bounds[0]) / math.log(p.bounds[1] / p.bounds[0])
           if p.transform == 'log' else (x - p.bounds[0]) / (
        p.bounds[1] - p.bounds[0]))
          for p, x in zip(parameters, values)]


def construct(parameters, center, values):
  if len(values) != len(parameters):
    raise ValueError('parameter width mismatch')
  config = deepcopy(center)
  for p, value in zip(parameters, values):
    if pointer(center, p.path) != p.center + p.offset:
      raise ValueError('center artifact does not match parameter declaration')
    if not math.isfinite(value) or not p.bounds[0] <= value <= p.bounds[1]:
      raise ValueError('parameter outside declared bounds')
    if p.sign == 'negative' and value >= 0 or p.sign == 'positive' and value <= 0:
      raise ValueError('parameter sign violation')
    pointer(config, p.path, float(value) + p.offset, True)
  return config


def sobol_points(parameters, design):
  return qmc.Sobol(d=len(parameters), scramble=design.scramble,
                   seed=design.seed).random_base2(design.poolPower)


def candidate(spec, center, values, sample_index, role, inspect):
  config = construct(spec.parameters, center, values)
  info = inspect(config)
  return dict(
    id=spec.centerId if role == 'center' else f'{spec.id}-sample-{sample_index:04d}',
    sourcePolicyId=spec.sourcePolicyId, role=role, sampleIndex=sample_index,
    parameters=dict(zip((p.name for p in spec.parameters), values)),
    parameterVector=values,
    delta=[x - p.center for p, x in zip(spec.parameters, values)],
    normalizedCoordinates=normalize(spec.parameters, values),
    coordinateDistance=float(
      np.linalg.norm(np.array(normalize(spec.parameters, values)) -
                     np.array(normalize(spec.parameters,
                                        [p.center for p in spec.parameters])))),
    function=config, behavior=info)


def initial_candidates(spec, center, inspect):
  exact = candidate(spec, center, [p.center for p in spec.parameters], None,
                    'center', inspect)
  selected, rejected = [exact], []
  seen = {digest(exact['function'])}
  for index, point in enumerate(sobol_points(spec.parameters, spec.initial)):
    if index < spec.initial.startIndex:
      continue
    try:
      item = candidate(spec, center, map_parameters(spec.parameters, point),
                       index, 'sobol', inspect)
      if digest(item['function']) in seen:
        raise ValueError('duplicate complete function')
    except ValueError as error:
      rejected.append(dict(sampleIndex=index, reason=str(error)))
      continue
    selected.append(item);
    seen.add(digest(item['function']))
    if len(selected) == spec.initial.count + 1:
      return selected, dict(rejected=rejected, nextSampleIndex=index + 1,
                            design=spec.initial.model_dump(mode='json'))
  raise ValueError(
    'Sobol pool exhausted; change the reviewed specification, not individual weights')


def quadratic_basis(names):
  terms = [
    dict(name=name + '_squared', expression=dict(op='square', args=[name])) for
    name in names]
  terms += [
    dict(name=a + '_by_' + b, expression=dict(op='product', args=[a, b]))
    for a, b in itertools.combinations(names, 2)]
  return Basis(terms=terms, scaling='standard', scalingOrder='before_expansion')


def training_task(spec, manifest='dataset.json'):
  names = [p.name for p in spec.parameters]
  return TrainingTaskSpec.model_validate(
    dict(schemaVersion=1, id=spec.id + '-response', kind='regression',
         dataset=dict(adapter='policy_response', manifest=manifest,
                      familyKey='policyId', blockKey='passId'),
         features=[
           dict(name=p.name, source='parameters.' + p.name, bounds=p.bounds,
                transform=p.transform) for p in spec.parameters],
         targets=[dict(name=r.name, source='responses.' + r.name,
                       units='log throughput ratio') for r in spec.responses],
         basis=quadratic_basis(names).model_dump(mode='json'),
         objective=dict(adapter='masked_mse'),
         validation=dict(policy='parameter_holdout', outerFolds=4, innerFolds=3,
                         seed=spec.initial.seed),
         candidates=[dict(id='regularized_quadratic', family='ridge',
                          grid=dict(alpha=[.1, 1., 10.]))],
         export=dict(kind='linear',
                     outputNames=[r.name for r in spec.responses])))


def response_rows(spec, policies, records):
  """One policy/block response bundle contains whole forks, never fragment-state rewards."""
  by_key = {}
  for r in records:
    key = (r['policyId'], r['workloadId'], str(r['passId']))
    if key in by_key:
      raise ValueError('duplicate policy/workload/block fork')
    if not r['measurementWindows'] or not all(
        math.isfinite(x) and x > 0 for x in r['measurementWindows']):
      raise ValueError('invalid measurement windows')
    if not math.isclose(np.mean(r['measurementWindows']),
                        r['executionsPerSecond'], rel_tol=1e-12):
      raise ValueError('fork mean differs from all retained windows')
    by_key[key] = r
  workloads = {w for response in spec.responses for w in response.workloads}
  expected = {(p, w, str(b)) for p in
              ['POLICY_OFF'] + [p['id'] for p in policies]
              for w in workloads for b in range(spec.blocks)}
  if set(by_key) != expected:
    raise ValueError('incomplete or unexpected tuning fork inventory')
  rows = []
  for policy in policies:
    for block in range(spec.blocks):
      outcomes, references = {}, {}
      for response in spec.responses:
        ratios = []
        for w in response.workloads:
          a, b = by_key[policy['id'], w, str(block)], by_key[
            'POLICY_OFF', w, str(block)]
          ratios.append(
            math.log(a['executionsPerSecond'] / b['executionsPerSecond']))
          references[w] = dict(candidateRunId=a['runId'],
                               baselineRunId=b['runId'])
        outcomes[response.name] = float(np.mean(ratios))
      rows.append(
        dict(rowId=f"{policy['id']}/block-{block}", policyId=policy['id'],
             passId=str(block),
             parameters=policy['parameters'], responses=outcomes,
             sourceForks=references))
  return rows


def pareto_indices(values):
  """Maximize each coordinate; equal predictions retain the first deterministic pool index."""
  values = np.asarray(values, dtype=float)
  if values.ndim != 2 or not np.isfinite(values).all():
    raise ValueError('finite objective matrix required')
  keep = []
  for i, row in enumerate(values):
    if any(np.array_equal(row, values[j]) for j in keep):
      continue
    if not np.any(np.all(values >= row, axis=1) & np.any(values > row, axis=1)):
      keep.append(i)
  return keep


def rule_values(rule, predictions, names):
  x = predictions[:, [names.index(name) for name in rule.targets]]
  result = np.min(x, axis=1) if rule.reduction == 'min' else np.mean(x, axis=1)
  return np.minimum(result, 0) if rule.capAtZero else result


def propose(spec, center, evaluator, measured, inspect):
  """Search a deterministic bounded Sobol pool, then select distinct predicted Pareto tradeoffs."""
  pc = spec.proposal;
  names = [r.name for r in spec.responses]
  if [x['name'] for x in evaluator['inputs']] != [p.name for p in
                                                  spec.parameters] or [x['name']
                                                                       for x in
                                                                       evaluator[
                                                                         'targets']] != names:
    raise ValueError('fitted model does not match parameter/response spec')
  points = sobol_points(spec.parameters, pc.design)
  measured_unit = np.array([normalize(spec.parameters, x) for x in measured])
  center_prediction = \
  predict(evaluator, np.array([[p.center for p in spec.parameters]]))[0]
  candidates, filtered = [], dict(measured=0, surface=0, feasibility=0)
  for index, point in enumerate(points):
    if index < pc.design.startIndex: continue
    if len(measured_unit) and np.min(
        np.linalg.norm(measured_unit - point, axis=1)) < pc.minMeasuredDistance:
      filtered['measured'] += 1;
      continue
    try:
      item = candidate(spec, center, map_parameters(spec.parameters, point),
                       index, 'proposal', inspect)
    except ValueError:
      filtered['surface'] += 1;
      continue
    candidates.append(item)
  if not candidates: return [], dict(filtered=filtered, paretoCount=0,
                                     reason='no admissible unmeasured surfaces')
  predictions = predict(evaluator,
                        np.array([p['parameterVector'] for p in candidates]))
  valid = np.isfinite(predictions).all(axis=1)
  for name, floor in pc.floors.items(): valid &= predictions[:, names.index(
    name)] >= floor
  for name, fraction in pc.centerRetention.items():
    valid &= predictions[:, names.index(name)] >= max(0, center_prediction[
      names.index(name)]) * fraction
  improvement = [names.index(n) for n in pc.improvementTargets]
  valid &= np.any(
    predictions[:, improvement] > center_prediction[improvement] + 1e-9, axis=1)
  filtered['feasibility'] = int(np.sum(~valid))
  candidates = [c for c, ok in zip(candidates, valid) if ok];
  predictions = predictions[valid]
  if not candidates: return [], dict(filtered=filtered, paretoCount=0,
                                     reason='no predicted improvement meets declared proposal floors')
  objectives = np.column_stack(
      [rule_values(rule, predictions, names) for rule in pc.objectives])
  front = pareto_indices(objectives)
  selected = []
  units = np.array([c['normalizedCoordinates'] for c in candidates])

  def allowed(i):
    return i not in selected and (not selected or min(
        np.linalg.norm(units[i] - units[j]) for j in
        selected) >= pc.minProposalDistance)

  labels = {}
  for role in pc.roles:
    values = rule_values(role, predictions, names)
    ranked = sorted(front, key=lambda i: (-float(values[i]),
                                          candidates[i]['sampleIndex']))
    best = next((i for i in ranked if allowed(i)), None)
    if best is not None:
      selected.append(best);
      labels[best] = role.name
    if len(selected) >= pc.count: break
  while len(selected) < pc.count:
    choices = [i for i in front if allowed(i)]
    if not choices: break
    best = min(choices, key=lambda i: (
      -min((np.linalg.norm(units[i] - units[j]) for j in selected), default=1),
      candidates[i]['sampleIndex']))
    selected.append(best);
    labels[best] = 'diverse_pareto_tradeoff'
  result = []
  for i in selected:
    result.append(
      dict(candidates[i], prediction=dict(zip(names, predictions[i].tolist())),
           proposalRole=labels[i], predictionOnly=True))
  return result, dict(filtered=filtered, paretoCount=len(front),
                      selectedCount=len(result),
                      centerPrediction=dict(
                        zip(names, center_prediction.tolist())),
                      validation='predictions require matched JVM measurement; no automatic acceptance')
