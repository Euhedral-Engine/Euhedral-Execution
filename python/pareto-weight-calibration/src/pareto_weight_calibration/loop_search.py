"""Measured beam preferences and bounded local heuristic search with genuine exploration."""
from collections import defaultdict
from pathlib import Path
import json, math, time, os
import joblib
import numpy as np
from scipy.stats import qmc
from scipy.spatial.distance import cdist
from .parameter_tuning import normalize, construct, pareto_indices
from .dense_search import map_matrix, features
from .loop_data import bundles, policy_id, compatible_families
from .surrogate_spec import SurrogateTask
from .surrogate_tournament import Tournament, clean, write
from .bulk_predict import PredictionStack
from .execution_resources import ExecutionPool
from .loop_references import (scored_panels, elite_archive, matched_panels,
                              model_parameters, model_features,
                              reference_features,
                              lineage, radius_update)


def preference(spec, prediction):
  """Same measured and predicted preference; losses stay visible, never a feasibility veto."""
  names = [r.name for r in spec.responses];
  p = spec.preference
  a = prediction[:, [names.index(n) for n in p.primaryTargets]]
  t = prediction[:, [names.index(n) for n in p.topologyTargets]]
  g = prediction[:, [names.index(n) for n in p.guardrailTargets]]
  cap = math.log1p(p.positiveCapPercent / 100);
  floor = math.log1p(p.materialLossPercent / 100)
  broad = np.mean(np.minimum(a, cap), axis=1)
  if p.objective == 'scarce':
    # Capped gain prevents a single large win masking holes. Keep all four
    # scarce objectives in the measured beam; plentiful is never consulted.
    worst = a.min(axis=1)
    scalar = broad + np.minimum(t.min(axis=1), 0) - p.harmPenalty * np.maximum(
      0, floor - worst)
    return scalar, np.column_stack(
        [np.mean(a, axis=1), (a > 0).mean(axis=1), t.min(axis=1), worst])
  harm = np.mean(np.maximum(0, floor - g), axis=1)
  # Vector retains distinct breadth, minimum topology, and plentiful risk objectives.
  vector = np.column_stack(
      [(a > 0).mean(axis=1), broad - p.harmPenalty * harm, t.min(axis=1),
       -harm])
  scalar = broad + np.minimum(t.min(axis=1), 0) - p.harmPenalty * harm
  return scalar, vector


def measured_scores(spec, records, campaign=None):
  if spec.benchmark.referenceMode == 'incumbent':
    return scored_panels(spec, records, campaign)
  panels = bundles(spec, records);
  groups = defaultdict(list)
  for p in panels:
    if campaign is None or p['campaignId'] == campaign: groups[
      (p['campaignId'], p['policyId'])].append(p)
  functions = {r['policyId']: r['function'] for r in records};
  result = []
  for (camp, policy), ps in groups.items():
    # Pool raw fork throughput against pooled matched OFF; never average different campaigns.
    rr = [r for r in records if
          r['campaignId'] == camp and r['policyId'] == policy]
    off = [r for r in records if
           r['campaignId'] == camp and r['function'] is None]
    w = {}
    for fixture in spec.fixtures:
      wid = fixture['workloadId'];
      a = [r['rawThroughput'] for r in rr if r['workloadId'] == wid]
      b = [r['rawThroughput'] for r in off if r['workloadId'] == wid]
      if a and b: w[wid] = math.log(np.mean(a) / np.mean(b))
    values = np.array([np.mean([w[x] for x in r.workloads]) if all(
        x in w for x in r.workloads) else np.nan for r in spec.responses])
    if not np.isfinite(values).all(): continue
    score, vector = preference(spec, values[None, :])
    result.append(
      dict(policyId=policy, campaignId=camp, function=functions[policy],
           score=float(score[0]),
           objectives=vector[0].tolist(),
           targets=dict(zip([r.name for r in spec.responses], values.tolist())),
           workloadReturns=w, blocks=len(ps)))
  return sorted(result, key=lambda r: (-r['score'], -r['objectives'][0],
                                       -r['objectives'][2], r['policyId'],
                                       r['campaignId']))


def region_for(spec, row, radius=None, search_family=None):
  compatible = compatible_families(spec, row['function'])
  search_family = search_family or row.get('searchFamily')
  # Shared zero-plane points may seed either hypothesis; no duplicate incumbent function.
  family = next(f for f in spec.families if (
        search_family is None or f.id == search_family) and f.id in compatible and
                all(p.bounds[0] <= v <= p.bounds[1] for p, v in
                    zip(f.parameters, compatible[f.id])))
  return dict(policyId=row['policyId'], function=row['function'],
              family=family.id,
              theta=compatible[family.id],
              radius=radius or spec.search.initialRadius, score=row['score'],
              failures=0)


def seed_regions(spec, records, exclude=()):
  selected = []
  for row in (elite_archive(spec,
                            records) if spec.benchmark.referenceMode == "incumbent" else measured_scores(
      spec, records)):
    if row['policyId'] in exclude: continue
    if any(r['policyId'] == row['policyId'] for r in selected): continue
    try:
      region = region_for(spec, row)
    except StopIteration:
      continue
    family = next(f for f in spec.families if f.id == region['family'])
    u = np.array(normalize(family.parameters, region['theta']))
    if any(r['family'] == region['family'] and np.linalg.norm(
        u - normalize(family.parameters,
                      r['theta'])) < spec.preference.beamSeparation for r in
           selected): continue
    selected.append(region)
    if len(selected) == spec.budgets.beamWidth: break
  if not selected and not exclude: raise ValueError(
    'no complete compatible measured policy panel available to seed the loop')
  return selected


def update_regions(spec, regions, records, campaign, proposals=()):
  ranked = measured_scores(spec, records, campaign)
  by = {r['policyId']: r for r in ranked}
  old = {r['policyId']: r for r in regions}
  origins = {r['policyId']: r.get('lineage') for r in proposals}
  if any(p not in by for p in old):
    raise ValueError('round lacks complete remeasured incumbent panel')
  current = max((by[p] for p in old), key=lambda r: r['score'])
  best = ranked[0]
  improved = best['score'] > current['score'] + spec.preference.minimumGainLog

  def retain(r):
    event = radius_update(spec, r, retained=True)
    return dict(r, score=by[r['policyId']]['score'], failures=r['failures'] + 1,
                radius=event['newRadius'], searchUpdate=event)
  if not improved:
    return [retain(r) for r in regions], False, ranked
  # Retain measured tradeoffs rather than a beam of nearly identical scalar winners.
  frontier = set(pareto_indices([r['objectives'] for r in ranked]))
  ordered = [best] + [r for i, r in enumerate(ranked) if
                      i in frontier and r is not best]
  chosen = []
  for row in ordered:
    if row['score'] < best[
      'score'] - spec.preference.competitiveToleranceLog: continue
    pid = row['policyId'];
    origin = None if pid in old else origins.get(pid)
    if origin and origin.get('parentPolicyId'):
      parent = old.get(origin['parentPolicyId'])
      if parent is None or parent['radius'] != origin['parentRadius']:
        raise ValueError(
          'proposal parent/radius does not match saved search region')
    family = old[pid]['family'] if pid in old else (origin or {}).get(
      'searchFamily')
    try:
      r = region_for(spec, row, search_family=family)
    except StopIteration:
      continue
    f = next(f for f in spec.families if f.id == r['family'])
    u = np.array(normalize(f.parameters, r['theta']))
    if origin and origin.get('parentPolicyId'):
      parent = old[origin['parentPolicyId']]
      parent_theta = compatible_families(spec, parent['function']).get(f.id)
      parent_u = np.array(normalize(f.parameters, parent_theta))
      delta = u - parent_u
      if (origin['parentFamily'] != parent['family'] or
          not np.allclose(origin['candidateNormalizedCoordinates'], u, rtol=0,
                          atol=1e-12) or
          not np.allclose(origin['parentNormalizedCoordinates'], parent_u,
                          rtol=0, atol=1e-12) or
          not math.isclose(origin['normalizedEdgeFraction'],
                           float(np.max(abs(delta)) / parent['radius']),
                           rel_tol=1e-12, abs_tol=1e-12)):
        raise ValueError('proposal coordinates do not match explicit lineage')
    if any(c['policyId'] == pid or (c['family'] == f.id and np.linalg.norm(
        u - normalize(f.parameters,
                      c['theta'])) < spec.preference.beamSeparation * min(
        r['radius'], c['radius'])) for c in chosen): continue
    event = radius_update(spec, old[pid],
                          retained=True) if pid in old else radius_update(spec,
                                                                          r,
                                                                          origin)
    r.update(radius=event['newRadius'], searchUpdate=event)
    if origin: r['lineage'] = origin
    chosen.append(r)
    if len(chosen) == spec.budgets.beamWidth: break
  # A non-improving secondary incumbent remains a legitimate alternate center.
  for r in regions:
    if len(chosen) >= spec.budgets.beamWidth: break
    if r['policyId'] not in {c['policyId'] for c in chosen}: chosen.append(
      retain(r))
  return chosen, True, ranked


def fit(spec, records, directory, progress=None):
  directory.mkdir(parents=True, exist_ok=True);
  reports = {}
  notify = progress or (lambda message: None)
  started = time.monotonic()
  with ExecutionPool(spec.execution) as pool:
    for family_index, family in enumerate(spec.families, 1):
      notify(f'FIT family {family_index}/{len(spec.families)}: {family.id}')
      dest = directory / family.id;
      dest.mkdir(exist_ok=True)
      if (dest / 'result.joblib').exists() and (dest / 'summary.json').exists():
        try:
          cached = joblib.load(dest / 'result.joblib')
          if 'choice' not in cached or 'models' not in cached: raise ValueError(
            'incomplete model cache')
          reports[family.id] = json.loads((dest / 'summary.json').read_text());
          del cached
          notify(f'FIT {family.id}: cached models loaded')
          continue
        except (EOFError, ValueError, OSError):
          pass
      paired = spec.benchmark.referenceMode == 'incumbent'
      data = matched_panels(spec, records, family,
                            training=True) if paired else bundles(spec, records,
                                                                  family)
      parameters = model_parameters(spec,
                                    family) if paired else family.parameters
      x = features(family.parameters, np.array([r['theta'] for r in data]))
      if paired: x = np.column_stack([x, np.array(
          [reference_features(spec, r['referenceFunction']) for r in data])])
      y = np.array([[r['targets'][o.name] if r['targets'][
                                               o.name] is not None else np.nan
                     for o in spec.responses] for r in data])
      groups = [r['thetaId'] for r in data];
      campaigns = [r['campaignId'] for r in data]
      if len(set(groups)) < 3:
        reports[family.id] = dict(status='insufficient theta; exploration');
        continue
      ts = SurrogateTask.model_validate(
        dict(id=spec.id + '-' + family.id, outputDirectory=str(dest),
             seed=spec.seed,
             parameters=[p.model_dump() for p in parameters],
             dataset={'discovery': []}, systems=['center' if paired else 'off'],
             responses=[r.model_dump() for r in spec.responses],
             models=[m.model_dump() for m in spec.models],
             validation=spec.validation.model_dump(),
             execution=spec.execution.model_dump(),
             proposal=dict(enabled=False, seed=spec.seed, objectives=[],
                           roles=[])))
      from .surrogate_models import expand
      notify(
        f'  {len(data)} response panels, {len(set(groups))} theta, {len(expand(spec.models))} model configurations')
      result = Tournament(ts, x, y, groups, campaigns, dest, pool=pool,
                          reference_groups=[r['referencePolicyId'] for r in
                                            data] if paired else None).run()
      joblib.dump(result, dest / 'result.joblib.tmp')
      write(dest / 'dataset.json', data)
      summary = clean({k: v for k, v in result.items() if
                       k not in ['models', 'individualPrediction',
                                 'ensemblePrediction', 'outerPrediction']})
      write(dest / 'summary.json', summary);
      os.replace(dest / 'result.joblib.tmp', dest / 'result.joblib');
      reports[family.id] = dict(rows=len(data), thetas=len(set(groups)),
                                failures=len(result['failures']))
      del result
      from .loop_reporting import duration
      elapsed = time.monotonic() - started
      eta = elapsed / family_index * (len(spec.families) - family_index)
      notify(
        f'FIT {family.id} complete | elapsed {duration(elapsed)} | remaining-family ETA ~{duration(eta)}')
  return reports


def propose(spec, records, regions, directory, round_index, progress=None):
  notify = progress or (lambda message: None)
  names = [r.name for r in spec.responses];
  uncertainty_columns = [names.index(n) for n in dict.fromkeys(
    spec.preference.primaryTargets + spec.preference.topologyTargets)] if spec.preference.objective == 'scarce' else list(
    range(len(names)))
  rng = np.random.default_rng(spec.seed + round_index * 1009)
  history = {f.id: np.array(
      [normalize(f.parameters, r['families'][f.id]) for r in records if
       f.id in r['families']]) for f in spec.families}
  measured = {r['policyId'] for r in records};
  selected = [];
  reservoir = [];
  reports = []
  family_by = {f.id: f for f in spec.families};

  def inputs(f, theta):
    return model_features(spec, f, theta, regions[0][
      'function']) if spec.benchmark.referenceMode == 'incumbent' else features(
      f.parameters, theta)
  count = spec.search.guided + spec.search.localRandom + spec.search.globalRandom

  def valid(f, unit, radius):
    if len(history[f.id]) and np.min(np.linalg.norm(history[f.id] - unit,
                                                    axis=1)) < radius * spec.search.historySpacingFraction: return None
    theta = map_matrix(f.parameters, np.asarray(unit)[None, :])[0].tolist()
    try:
      fn = construct(f.parameters, f.template, theta)
    except ValueError:
      return None
    pid = policy_id(fn)
    if pid in measured or any(
        s['policyId'] == pid for s in selected): return None
    if any(s['family'] == f.id and np.linalg.norm(
        np.asarray(s['unit']) - unit) < radius * spec.search.spacingFraction for
           s in selected): return None
    return dict(policyId=pid, family=f.id, theta=theta, unit=list(unit),
                function=fn, radius=radius)

  with ExecutionPool(spec.execution) as pool:
    stacks = {}
    try:
      for f in spec.families:
        p = directory / 'fit' / f.id / 'result.joblib'
        if p.exists():
          result = joblib.load(p)
          if all(choice is not None for choice in result['choice']): stacks[
            f.id] = PredictionStack(result, names, 1.5, pool=pool)
          del result
      # A shared measured center seeds distinct compatible hypotheses without multiplying the point budget.
      searches = []
      for region in regions:
        for f in spec.families:
          values = compatible_families(spec, region['function']).get(f.id)
          if values and all(p.bounds[0] <= v <= p.bounds[1] for p, v in
                            zip(f.parameters, values)):
            searches.append(
                (region, f, np.array(normalize(f.parameters, values))))
      total = 2 ** spec.search.power;
      spent = 0
      for i, (region, f, u) in enumerate(searches):
        if f.id not in stacks or spec.search.guided == 0: continue
        stack = stacks[f.id];
        lo = np.maximum(0, u - region['radius']);
        hi = np.minimum(1, u + region['radius'])
        n = 2 ** max(0, int(math.log2(total // len(searches))));
        sobol = qmc.Sobol(len(u), scramble=True,
                          seed=spec.seed + round_index * 101 + i)
        memory = stack.batch_plan(n, spec.search.maxBatchRows)
        batch = memory['batchRows']
        started = time.monotonic();
        best = -math.inf;
        checkpoints = [];
        processed = 0
        last_progress = started
        notify(
          f'PROPOSE region {i + 1}/{len(searches)}: family={f.id} | scoring {n:,} points | ETA estimating')
        while processed < n:
          size = min(batch, n - processed);
          unit = lo + sobol.random(size) * (hi - lo);
          theta = map_matrix(f.parameters, unit)
          prediction, spread, _ = stack.predict(inputs(f, theta));
          score, vector = preference(spec, prediction)
          if spec.search.saveDense:
            artifact = directory / 'dense' / f'{i}-{processed:010d}.npz';
            artifact.parent.mkdir(exist_ok=True)
            np.savez(artifact, theta=theta, prediction=prediction,
                     disagreement=spread)
          score -= spec.search.disagreementPenalty * np.nanmean(
              spread[:, uncertainty_columns], axis=1)
          ok = np.isfinite(score)
          if len(history[f.id]):
            from scipy.spatial import cKDTree
            if processed == 0: tree = cKDTree(history[f.id])
            ok &= tree.query(unit)[0] >= region[
              'radius'] * spec.search.historySpacingFraction
          indexes = np.flatnonzero(ok)
          # Preserve tradeoff extremes as well as the composite heuristic in a bounded reservoir.
          keep = set()
          for values in [score, *vector.T]:
            order = indexes[np.argsort(-values[indexes], kind='stable')[
              :max(8, spec.search.reservoir // 16)]]
            keep.update(order.tolist())
          for j in sorted(keep):
            reservoir.append(
              dict(family=f.id, unit=unit[j].tolist(), score=float(score[j]),
                   objectives=vector[j].tolist(),
                   predictions=prediction[j].tolist(),
                   disagreement=spread[j].tolist(), radius=region['radius'],
                   sample=processed + j,
                   searchSeed=spec.seed + round_index * 101 + i,
                   centerPolicyId=region['policyId'], parent=region,
                   parentUnit=u.tolist()))
          if len(reservoir) > spec.search.reservoir * 2:
            reservoir = prune_reservoir(reservoir, spec.search.reservoir)
          processed += size;
          now = time.monotonic()
          if now - last_progress >= 30:
            from .loop_reporting import duration
            eta = (now - started) / processed * (n - processed)
            notify(
              f'PROPOSE {f.id}: {processed:,}/{n:,} points | region ETA ~{duration(eta)}')
            last_progress = now
          newbest = float(np.max(score[ok])) if ok.any() else best
          if processed == n:
            checkpoints.append(
              dict(points=processed, bestScore=max(best, newbest),
                   seconds=time.monotonic() - started))
            # Grow only if cheap and the last block still changes the useful predicted frontier.
            if spent + 2 * n + max(0, len(searches) - i - 1) * (total // len(
                searches)) <= 2 ** spec.search.maxPower and time.monotonic() - started < spec.search.growBelowSeconds and newbest > best + spec.search.growOnImprovementLog:
              n *= 2
          best = max(best, newbest)
        from .loop_reporting import duration
        notify(
          f'PROPOSE {f.id}: {processed:,} points complete in {duration(time.monotonic() - started)}')
        if stack.timings:
          slowest = max(stack.timings.values(), key=lambda row: row['seconds'])
          notify(f"  Largest prediction cost: {slowest['model']} ({slowest['estimator']}); "
                 f"{slowest['seconds']:.1f}s accumulated worker time")
        spent += processed
        reports.append(
          dict(family=f.id, center=region['policyId'], points=processed,
               seconds=time.monotonic() - started,
               **memory,
               convergence=checkpoints, execution=stack.report()))
      reservoir = prune_reservoir(reservoir, spec.search.reservoir)
      # Greedy diversity retains multiple regions; predictions never prohibit random slots.
      frontier = [reservoir[i] for i in pareto_indices(
          [r['objectives'] for r in reservoir])] if reservoir else []
      available = frontier or reservoir
      roles = ['broad_scarcity', 'minimum_topology', 'consensus',
               'alternate_region']
      for slot in range(spec.search.guided):
        role = roles[slot % len(roles)]

        def priority(r):
          if role == 'minimum_topology':
            value = r['objectives'][2]
          elif role == 'consensus':
            value = r['score'] - .2 * np.mean(
                np.asarray(r['disagreement'])[uncertainty_columns])
          elif role == 'alternate_region' and selected:
            value = min((np.linalg.norm(np.array(r['unit']) - s['unit']) if r[
                                                                              'family'] ==
                                                                            s[
                                                                              'family'] else 1.)
                        for s in selected)
          else:
            value = r['score']
          return (-value, -r['score'], r['family'], r['sample'])

        for r in sorted(available, key=priority):
          candidate = valid(family_by[r['family']], np.array(r['unit']),
                            r['radius'])
          if candidate:
            candidate.update(role='guided', selectionRole=role,
                             sampleIndex=r['sample'],
                             searchSeed=r['searchSeed'],
                             centerPolicyId=r['centerPolicyId'],
                             predictions=dict(zip(names, r['predictions'])),
                             disagreement=dict(zip(names, r['disagreement'])))
            candidate['lineage'] = lineage(family_by[r['family']], candidate,
                                           r['parent'], r['parentUnit'], role)
            selected.append(candidate);
            break
      fallback = max(0, spec.search.guided - len(selected))
      if fallback: print(
        f'Guided shortlist short by {fallback}; filling with valid local exploration.',
        flush=True)
      roles = ['guided_fallback'] * fallback + [
        'local_random'] * spec.search.localRandom + [
                'global_random'] * spec.search.globalRandom
      for role in roles:
        for attempt in range(10000):
          if role == 'global_random':
            f = spec.families[int(rng.integers(len(spec.families)))];
            unit = rng.random(len(f.parameters));
            radius = min(r['radius'] for r in regions)
          else:
            region, f, u = searches[int(rng.integers(len(searches)))];
            radius = region['radius'];
            unit = rng.uniform(np.maximum(0, u - radius),
                               np.minimum(1, u + radius))
          candidate = valid(f, unit, radius)
          if candidate:
            candidate['lineage'] = lineage(f, candidate,
                                           None if role == 'global_random' else region,
                                           None if role == 'global_random' else u,
                                           role)
            candidate['role'] = role;
            candidate['randomSeed'] = spec.seed + round_index * 1009;
            candidate['drawAttempt'] = attempt
            if f.id in stacks:
              pred, spread, _ = stacks[f.id].predict(
                  inputs(f, np.array([candidate['theta']])))
              candidate.update(predictions=dict(zip(names, pred[0].tolist())),
                               disagreement=dict(
                                 zip(names, spread[0].tolist())))
            selected.append(candidate);
            break
        else:
          raise ValueError(
            'no valid unmeasured point after bounded exploration attempts')
      if len(selected) != count: raise ValueError('proposal count mismatch')
    finally:
      for stack in stacks.values(): stack.close()
      stacks.clear()
  return dict(candidates=selected, search=reports, round=round_index,
              seed=spec.seed, randomState=rng.bit_generator.state,
              selection='bounded diverse heuristic reservoir plus independent random exploration')


def prune_reservoir(rows, limit):
  if len(rows) <= limit: return rows
  # Coordinate bins prevent a huge nearly-identical optimum from evicting alternate basins.
  cells = {}
  for r in sorted(rows, key=lambda r: (-r['score'], r['family'], r['sample'])):
    key = (r['family'], tuple(np.floor(np.array(r['unit']) * 16).astype(int)))
    cells.setdefault(key, r)
  kept = list(cells.values())[:limit]
  if len(kept) < limit:
    ids = {id(r) for r in kept};
    kept.extend(
        r for r in sorted(rows, key=lambda r: -r['score']) if id(r) not in ids)
  return kept[:limit]
