"""Exact dense Sobol scoring with array artifacts, adaptive memory use and matched counterfactuals."""
from collections import Counter
import math
import time
import resource
import numpy as np
from scipy.stats import qmc
from scipy.spatial.distance import cdist
from sklearn.cluster import KMeans
from .bulk_predict import PredictionStack
from .parameter_tuning import construct, normalize
from .proposal_reliability import policy_view, search_scores, rule_scores, \
  history_exclusions
from .reliable_proposals import nondominated, choose_regions


def map_matrix(parameters, unit):
  result = np.empty_like(unit, dtype=float)
  for j, p in enumerate(parameters):
    lo, hi = p.bounds
    result[:, j] = (np.exp(math.log(lo) + unit[:, j] * math.log(hi / lo))
                    if p.transform == 'log' else lo + unit[:, j] * (hi - lo))
  return result


def features(parameters, theta):
  x = theta.copy()
  for j, p in enumerate(parameters):
    if p.transform == 'log':
      x[:, j] = np.log(x[:, j])
  return x


def support_mask(spec, center, theta):
  """Vectorized existing CACHE support adapter; no work-unit substitution or runtime changes."""
  support = spec.proposal.support
  if not support:
    return np.zeros(len(theta), bool)
  if support.get('adapter') != 'cache_timing':
    raise ValueError('unregistered bulk support adapter')
  raw = np.array(support['points'], float)
  z = (raw - center['means']) / center['scales']
  phi = np.column_stack([np.ones(len(z)), z])
  width = len(center['parkCoefficients'])
  if width == 7:
    phi = np.column_stack(
        [phi, z[:, 0] * z[:, 1], z[:, 0] * z[:, 2], z[:, 1] * z[:, 2]])
  invalid = np.zeros(len(theta), bool)
  for prefix in ['park', 'halfLife']:
    coeff = np.tile(center[prefix + 'Coefficients'], (len(theta), 1))
    for j, parameter in enumerate(spec.parameters):
      parts = parameter.path.strip('/').split('/')
      if parts[0] == prefix + 'Coefficients':
        coeff[:, int(parts[1])] = theta[:, j] + parameter.offset
      elif parts[0] not in ['parkCoefficients', 'halfLifeCoefficients']:
        raise ValueError(
          'bulk CACHE support requires coefficient parameter paths')
    # Preserve the evaluator's two ordered sums.
    values = sum(coeff[:, i, None] * phi[None, :, i] for i in range(4))
    if width == 7:
      values += sum(coeff[:, i, None] * phi[None, :, i] for i in range(4, 7))
    values = np.floor(
      center[prefix + 'ReferenceNanos'] * np.exp(np.minimum(700, values)) + .5)
    lo, hi = center[prefix + 'MinNanos'], center[prefix + 'MaxNanos']
    invalid |= np.maximum(np.mean(values <= lo, axis=1),
                          np.mean(values >= hi, axis=1)) > support[
                 'maxSingleBoundaryFraction']
  return invalid


def memory_plan(points, dimensions, outputs, training_rows, config):
  # Includes resident predictions/views, kernel temporaries, polynomial transforms and geometry.
  bytes_per_point = 8 * (
        dimensions * 3 + outputs * 9 + training_rows * 4 + math.comb(
      dimensions + 3, 3) * 2)
  estimate = points * bytes_per_point
  budget = config.memoryBudgetGiB * 2 ** 30
  bulk = estimate <= budget
  batch = points if bulk else min(2 ** config.chunkPower,
                                  max(1, int(budget / bytes_per_point)))
  return dict(mode='bulk' if bulk else 'memory_budget_chunked', points=points,
              estimatedBulkBytes=estimate, declaredBudgetBytes=budget,
              batchPoints=batch,
              estimatedWorkingBytes=batch * bytes_per_point)


def counterfactual(spec, center, theta):
  values = list(theta)
  for j, p in enumerate(spec.parameters):
    if p.name in spec.proposal.counterfactual.values:
      values[j] = spec.proposal.counterfactual.values[p.name]
  return values, construct(spec.parameters, center, values)


def search(spec, root, data, result, names, output):
  from .execution_resources import ExecutionPool, execution_config
  with ExecutionPool(execution_config(spec)) as pool:
    return _search(spec, root, data, result, names, output, pool)


def _search(spec, root, data, result, names, output, pool):
  from .surrogate_tournament import read, write, tsv, clean
  from .known_regions import ensure_coverage
  pc = spec.proposal;
  dc = pc.denseInference
  if not pc.reliability.enabled:
    raise ValueError('dense search requires explicit reliability policy')
  started = time.perf_counter();
  center = read(root / spec.dataset.centerArtifact)
  stack = PredictionStack(result, names, pc.ensembleTolerance,
                          dc.maxDisagreementMembers, pool=pool)
  maximum = max(pc.power, dc.expansionPower or pc.power);
  capacity = 2 ** maximum
  arrays = {}

  def array(name, shape, dtype='float64'):
    value = np.lib.format.open_memmap(output / (name + '.npy'), mode='w+',
                                      dtype=dtype, shape=shape)
    arrays[name] = value;
    return value

  unit = array('dense_unit', (capacity, len(spec.parameters)))
  pred = array('dense_predictions', (capacity, len(names)))
  spread = array('dense_disagreement', (capacity, len(names)))
  status = array('dense_status', (capacity,), 'uint8')
  scores = None;
  plan = memory_plan(2 ** pc.power, len(spec.parameters), len(names),
                     len(data['rows']), dc)
  write(output / 'memory_plan.json', plan)
  generator = qmc.Sobol(len(spec.parameters), scramble=True, seed=pc.seed)
  checkpoints = sorted(
    {p for p in dc.checkpoints if p <= pc.power} | {pc.power})
  progress = [];
  front = np.array([], int);
  unconstrained = front;
  done = 0;
  inference_seconds = 0.;
  support_seconds = 0.
  authority = result['proposalAuthority'];
  score_names = []

  def snapshot(power):
    n = 2 ** power;
    then = time.perf_counter()
    base = np.flatnonzero((status[:n] & 3) == 0)
    eligible = np.flatnonzero(status[:n] == 0)
    uf = base[nondominated(np.asarray(scores[base]))]
    ff = eligible[nondominated(np.asarray(scores[eligible]))]
    # All points are Pareto-tested. Only the stability diagnostic's KMeans fit is sampled.
    sample = ff[
      np.linspace(0, len(ff) - 1, min(len(ff), 10000), dtype=int)] if len(
      ff) else ff
    centers = KMeans(n_clusters=min(pc.clusters, len(sample)),
                     random_state=pc.seed, n_init=10).fit(
        unit[sample]).cluster_centers_ if len(sample) else np.empty(
        (0, len(spec.parameters)))
    best = np.max(scores[eligible], axis=0) if len(eligible) else np.full(
      len(score_names), np.nan)
    entry = dict(power=power, points=n, eligible=len(eligible),
                 paretoSize=len(ff), unconstrainedParetoSize=len(uf),
                 cumulativeSeconds=time.perf_counter() - started,
                 inferenceSeconds=inference_seconds,
                 paretoSeconds=time.perf_counter() - then,
                 basinCenters=centers.tolist(), bestObjectives=best.tolist())
    if progress and len(centers) and progress[-1]['basinCenters']:
      distance = cdist(centers, progress[-1]['basinCenters'])
      shift = max(distance.min(axis=0).max(), distance.min(axis=1).max())
      delta = np.abs(best - np.array(progress[-1]['bestObjectives']))
      entry.update(basinShift=float(shift), objectiveChange=delta.tolist(),
                   stable=bool(shift <= dc.basinDistanceTolerance and np.all(
                     delta <= dc.stabilityToleranceLog)))
    progress.append(entry)
    np.save(output / f'pareto_indices_2p{power}.npy', ff)
    np.save(output / f'unconstrained_indices_2p{power}.npy', uf)
    write(output / 'search_convergence.json',
          clean(dict(checkpoints=progress, configuration=dc.model_dump())))
    print(
      f'dense 2^{power}: {len(eligible)} eligible, {len(ff)} exact Pareto; {entry["cumulativeSeconds"]:.1f}s',
      flush=True)
    return ff, uf

  checkpoint_index = 0;
  expansion = None
  while checkpoint_index < len(checkpoints):
    power = checkpoints[checkpoint_index];
    target = 2 ** power
    while done < target:
      n = min(plan['batchPoints'], target - done);
      u = generator.random(n);
      theta = map_matrix(spec.parameters, u)
      tick = time.perf_counter();
      y, d, _ = stack.predict(features(spec.parameters, theta));
      inference_seconds += time.perf_counter() - tick
      tick = time.perf_counter();
      bad = support_mask(spec, center, theta);
      support_seconds += time.perf_counter() - tick
      history = history_exclusions(spec, data['rows'], u)
      view = policy_view(spec, y, d, names, authority);
      sc, score_names = search_scores(spec, view, names)
      if scores is None: scores = array('dense_objectives',
                                        (capacity, sc.shape[1]))
      if not np.isfinite(y).all() or not np.isfinite(
          sc).all(): raise ValueError('nonfinite dense prediction')
      state = (~history['eligible']).astype('uint8') + 2 * bad.astype(
        'uint8') + 4 * (~view['eligible']).astype('uint8')
      if pc.maxDisagreementLog is not None: state |= 8 * (
            np.max(d, axis=1) > pc.maxDisagreementLog).astype('uint8')
      unit[done:done + n] = u;
      pred[done:done + n] = y;
      spread[done:done + n] = d;
      status[done:done + n] = state;
      scores[done:done + n] = sc
      done += n
      if done % (2 ** 20) == 0: print(
        f'scored {done:,}: inference {inference_seconds:.1f}s', flush=True)
    front, unconstrained = snapshot(power);
    checkpoint_index += 1
    if power == pc.power and dc.expansionPower and dc.expansionPower > power:
      projected = (time.perf_counter() - started) * (
            2 ** (dc.expansionPower - power))
      expand = projected <= dc.expansionMaxSeconds and not progress[-1].get(
        'stable', False)
      expansion = dict(evaluated=expand, projectedTotalSeconds=projected,
                       limitSeconds=dc.expansionMaxSeconds,
                       reason='cheap and changing' if expand else (
                         'stable at declared resolution' if progress[-1].get(
                           'stable') else 'measured runtime exceeds expansion budget'))
      if expand: checkpoints.append(dc.expansionPower)
  # Unused expansion capacity is sparse file space; metadata specifies the valid prefix.
  for a in arrays.values(): a.flush()
  valid = status[:done] == 0
  u = np.asarray(unit[front]);
  y = np.asarray(pred[front]);
  d = np.asarray(spread[front]);
  theta = map_matrix(spec.parameters, u)
  view = policy_view(spec, y, d, names, authority)
  useful = [j for j, n in enumerate(names) if authority[n]['directionUseful']]
  uncertainty = np.max(
    d[:, useful] if useful and pc.reliability.disagreementUsefulOnly else d,
    axis=1) if len(front) else np.array([])
  local_front = np.arange(len(front));
  chosen, roles, basins = choose_regions(spec, u, local_front, view, names,
                                         uncertainty)
  role_values, _ = rule_scores(pc.roles, view, names)
  direction = role_values[:, 0] if role_values.shape[1] else np.zeros(len(u))
  chosen, roles, coverage = ensure_coverage(spec, data, u,
                                            np.ones(len(u), bool), local_front,
                                            chosen, roles, basins, uncertainty,
                                            direction)
  if coverage:
    for r in coverage['regions']:
      point = np.array(normalize(spec.parameters, r['theta']));
      count = 0
      for lo in range(0, done, plan['batchPoints']):
        hi = min(done, lo + plan['batchPoints']);
        count += int(np.sum((np.linalg.norm(unit[lo:hi] - point,
                                            axis=1) <= pc.knownRegionCoverage.radius) & valid[
                              lo:hi]))
      r['eligibleDensePoints'] = count
      r['selectedProposalIds'] = [
        f'{spec.id}-round{pc.round}-sample{int(front[i])}' for i in
        r['selectedProposals']]
    write(output / 'known_region_coverage.json', clean(coverage));
    tsv(output / 'known_region_coverage.tsv', coverage['regions'])
  selected = [];
  consensus = [];
  classes = {n: a['reliabilityClass'] for n, a in authority.items()}
  if chosen:
    sy, sd, raw = stack.predict(features(spec.parameters, theta[chosen]), True)
    history = history_exclusions(spec, data['rows'], u[chosen])
    for k, i in enumerate(chosen):
      sample = int(front[i]);
      values = theta[i].tolist();
      cfg = construct(spec.parameters, center, values)
      if support_mask(spec, center, theta[[i]])[0]: raise ValueError(
        'selected support failure')
      item = dict(id=f'{spec.id}-round{pc.round}-sample{sample}',
                  sampleIndex=sample, theta=values,
                  parameters=dict(
                    zip([p.name for p in spec.parameters], values)),
                  normalizedCoordinates=u[i].tolist(),
                  function=cfg, role=roles[i], basin=int(basins[i]),
                  nearestMeasuredTheta=history['nearest'][k],
                  distanceToMeasured=float(history['distance'][k]),
                  predictions=dict(zip(names, sy[k].tolist())),
                  modelDisagreement=dict(zip(names, sd[k].tolist())),
                  reliability=classes, contribution=view['treatments'],
                  unresolvedGuardrails=[n for n in view['unresolved'] if
                                        n in pc.floorPercent],
                  predictionOnly=True, requiresMatchedBenchmark=True)
      if pc.counterfactual.enabled:
        zero, zero_cfg = counterfactual(spec, center, values);
        zy, zd, _ = stack.predict(features(spec.parameters, np.array([zero])))
        item['counterfactual'] = dict(
          id=item['id'] + '-' + pc.counterfactual.suffix, theta=zero,
          function=zero_cfg,
          predictions=dict(zip(names, zy[0].tolist())),
          modelDisagreement=dict(zip(names, zd[0].tolist())),
          marginalPredictions=dict(zip(names, (sy[k] - zy[0]).tolist())),
          measured=False)
      selected.append(item)
      for j, n in enumerate(names): consensus.append(
        dict(policyId=item['id'], output=n, prediction=sy[k, j],
             disagreement=sd[k, j], reliability=classes[n],
             contribution=view['treatments'][n],
             models={m: raw[m][k, j] for m in stack.members[n]}))
  landmarks = []
  for policy in pc.retrospective.knownPolicyIds:
    matching = [r for r in data['forks'] if r['policyId'] == policy]
    if not matching: continue
    landmark = np.array(normalize(spec.parameters, matching[0]['theta']));
    counts = [0, 0]
    for lo in range(0, done, plan['batchPoints']):
      hi = min(done, lo + plan['batchPoints']);
      near = np.linalg.norm(unit[lo:hi] - landmark,
                            axis=1) <= pc.retrospective.neighborhoodRadius
      counts[0] += int(near.sum());
      counts[1] += int(np.sum(near & valid[lo:hi]))
    distance = np.linalg.norm(u - landmark, axis=1)
    landmarks.append(dict(policyId=policy, theta=matching[0]['theta'],
                          radius=pc.retrospective.neighborhoodRadius,
                          denseNeighbors=counts[0], eligibleNeighbors=counts[1],
                          paretoNeighbors=int(np.sum(
                            distance <= pc.retrospective.neighborhoodRadius)),
                          selectedNeighbors=[selected[k]['id'] for k, i in
                                             enumerate(chosen) if distance[
                                               i] <= pc.retrospective.neighborhoodRadius],
                          nearestProposalDistance=float(
                            min(distance[chosen])) if chosen else None))
  write(output / 'known_region_recovery.json', landmarks)
  basin_report = [dict(id=int(b), frontierMembers=int(np.sum(basins == b)),
                       representatives=[p for p in selected if p['basin'] == b])
                  for b in sorted(set(basins))]
  write(output / 'basins.json', dict(basins=basin_report,
                                     interpretation='Computational normalized-parameter regions, not measured physical regimes'))
  tsv(output / 'proposal_consensus.tsv', clean(consensus))
  manifest = dict(schemaVersion=3, taskId=spec.id, searched=done,
                  eligible=int(valid.sum()), paretoSize=len(front),
                  unconstrainedParetoSize=len(unconstrained), selected=selected,
                  stopReason=None if selected else 'no eligible region',
                  authorityCounts=dict(Counter(classes.values())),
                  activeBasins=basin_report, knownRegionCoverage=coverage,
                  bounds=[p.model_dump() for p in spec.parameters],
                  rankingObjectives=score_names, benchmarkExecution=False,
                  historyExcluded=int(np.sum((status[:done] & 1) != 0)),
                  supportRejected=int(np.sum((status[:done] & 2) != 0)),
                  reliableHardRejected=int(np.sum((status[:done] & 4) != 0)),
                  inferenceSeconds=inference_seconds,
                  supportSeconds=support_seconds,
                  elapsedSeconds=time.perf_counter() - started,
                  peakRssBytes=resource.getrusage(
                    resource.RUSAGE_SELF).ru_maxrss * 1024, memoryPlan=plan,
                  expansion=expansion)
  write(output / 'dense_manifest.json',
        dict(validRows=done, allocatedRows=capacity, outputs=names,
             objectives=score_names,
             arrays={n: str(output / (n + '.npy')) for n in arrays},
             statusBits={'1': 'history exclusion', '2': 'support invalid',
                         '4': 'reliable hard floor', '8': 'disagreement cap'},
             paretoMethod='Exact nondominance of all eligible points, including prediction ties; no objective quantization',
             configuration=dc.model_dump()))
  write(output / 'search_convergence.json', clean(
    dict(checkpoints=progress, expansion=expansion,
         configuration=dc.model_dump())))
  write(output / 'proposals.json', clean(manifest))
  return manifest
