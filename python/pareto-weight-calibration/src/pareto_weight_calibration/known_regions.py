"""Measured usefulness and bounded local coverage; independent of experiment identities."""

import math
from collections import defaultdict
from itertools import combinations
import numpy as np
from scipy.spatial.distance import cdist
from .parameter_tuning import normalize


def measured_metrics(rows, policy, min_blocks):
  targets = (*policy.primaryTargets, *policy.topologyTargets,
             *policy.plentifulFloors)
  means, counts = {}, {}
  for name in targets:
    observed = [r for r in rows if r['targets'].get(name) is not None
                and math.isfinite(r['targets'][name])]
    keys = [(r['campaignId'], r['blockId']) for r in observed]
    if len(keys) != len(set(keys)):
      raise ValueError('duplicate campaign/block for measured theta')
    counts[name] = len(keys)
    if len(keys) < min_blocks:
      return None
    means[name] = float(np.mean([r['targets'][name] for r in observed]))
  scarce = np.array([means[n] for n in policy.primaryTargets])
  tops = [means[n] for n in policy.topologyTargets]
  return dict(positiveCount=int(sum(scarce > 0)),
              positiveFraction=float(np.mean(scarce > 0)),
              minimumTopologyLog=min(tops),
              cappedBroadLog=float(np.minimum(scarce, math.log1p(
                policy.positiveCapPercent / 100)).mean()),
              perTargetBlocks=counts, means=means)


def _useful_cohort(spec, data):
  from .reliable_proposals import nondominated
  pc = spec.proposal.knownRegionCoverage
  policy = pc.usefulness
  groups = defaultdict(list)
  for row in data['rows']:
    groups[row['thetaId']].append(row)
  candidates = []
  for tid, rows in sorted(groups.items()):
    m = measured_metrics(rows, policy, policy.minBlocksPerTarget)
    if m is None or m['positiveFraction'] < policy.minPositiveFraction:
      continue
    if m['minimumTopologyLog'] < math.log1p(policy.minTopologyPercent / 100):
      continue
    if m['cappedBroadLog'] < math.log1p(policy.minBroadPercent / 100):
      continue
    if any(m['means'][n] < math.log1p(f / 100) for n, f in
           policy.plentifulFloors.items()):
      continue
    candidates.append(dict(thetaId=tid, theta=rows[0]['theta'], qualification=m,
                           campaigns=sorted({r['campaignId'] for r in rows})))
  if candidates and policy.selection == 'pareto_tradeoffs':
    ids = nondominated([[r['qualification'][k] for k in
                         ['positiveCount', 'minimumTopologyLog',
                          'cappedBroadLog']]
                        for r in candidates])
    candidates = [candidates[i] for i in ids]
  return candidates


def useful_history(spec, data):
  policy = spec.proposal.knownRegionCoverage.usefulness
  if policy.evidenceScope == 'pooled':
    return _useful_cohort(spec, data)
  by_campaign = defaultdict(list)
  for row in data['rows']:
    by_campaign[row['campaignId']].append(row)
  history = {}
  for campaign, rows in sorted(by_campaign.items()):
    for region in _useful_cohort(spec, dict(rows=rows)):
      tid = region['thetaId']
      if tid not in history:
        history[tid] = region
        history[tid]['qualificationByCampaign'] = {}
      history[tid]['qualificationByCampaign'][campaign] = region[
        'qualification']
      history[tid]['campaigns'] = sorted(
          history[tid]['qualificationByCampaign'])
  return [history[k] for k in sorted(history)]


def rejection_evidence(spec, data, region):
  pc = spec.proposal.knownRegionCoverage
  rule = pc.rejectedRegionPolicy
  order = {c: i for i, c in enumerate(rule.campaignOrder)}
  if any(c not in order for c in region['campaigns']):
    return False, [], 'Qualification chronology incomplete; no later rejection established'
  latest = max(order[c] for c in region['campaigns'])
  center = np.array(normalize(spec.parameters, region['theta']))
  later = defaultdict(list)
  for r in data['rows']:
    if order.get(r['campaignId'], -1) <= latest:
      continue
    if np.linalg.norm(
        np.array(normalize(spec.parameters, r['theta'])) - center) <= pc.radius:
      later[(r['campaignId'], r['thetaId'])].append(r)
  evidence = []
  for (campaign, tid), rows in sorted(later.items()):
    m = measured_metrics(rows, pc.usefulness, rule.minBlocksPerTarget)

    def poor_metrics(value):
      return value is not None and (
            value['positiveFraction'] <= rule.maxPositiveFraction
            and value['minimumTopologyLog'] <= math.log1p(
          rule.maxTopologyPercent / 100)
            and value['cappedBroadLog'] <= math.log1p(
          rule.maxBroadPercent / 100))

    blocks = [measured_metrics([r], pc.usefulness, 1) for r in rows]
    poor = poor_metrics(m) and all(poor_metrics(b) for b in blocks)
    evidence.append(dict(campaignId=campaign, thetaId=tid, metrics=m,
                         independentBlockMetrics=blocks, consistentlyPoor=poor))
  rejected = (len({r['campaignId'] for r in evidence}) >= rule.minLaterCampaigns
              and bool(evidence) and all(
          r['consistentlyPoor'] for r in evidence))
  return rejected, evidence, (
    'Later complete matched blocks consistently poor' if rejected
    else 'No sufficient consistently poor later measured evidence')


def ensure_coverage(spec, data, unit, valid, frontier, chosen, roles, basins,
    uncertainty, direction):
  """Preserve normal selection, then deterministic bounded set cover with diversity constraints.

  A shared candidate must be inside every neighborhood it represents. Connected clusters
  are never substituted for distance coverage. Infeasible coverage stops preparation.
  """
  pc = spec.proposal.knownRegionCoverage
  if not pc.enabled:
    return chosen, roles, None
  original = list(chosen)
  regions = useful_history(spec, data)
  required = []
  for region in regions:
    region['measuredPolicyIds'] = sorted(
        {r['policyId'] for r in data.get('forks', [])
         if r['thetaId'] == region['thetaId']})
    center = normalize(spec.parameters, region['theta'])
    distance = np.linalg.norm(unit - center, axis=1)
    nearby = distance <= pc.radius
    rejected, evidence, reason = rejection_evidence(spec, data, region)
    support = [int(i) for i in frontier if nearby[i]]
    active = not rejected and len(support) >= pc.minEligibleParetoPoints
    region.update(radius=pc.radius,
                  eligibleDensePoints=int(sum(nearby & valid)),
                  eligibleParetoPoints=len(support), rejected=rejected,
                  rejectionEvidence=evidence,
                  rejectionReason=reason, required=active,
                  beforeSelected=[int(i) for i in original if nearby[i]],
                  beforeNearestDistance=float(
                    min(distance[original])) if original else None)
    if active:
      required.append((region, set(support), distance))
  # maxRegions bounds the number of local representatives, not overlapping anchor count.
  nodes = 0

  def uncovered(selection):
    return [j for j, (_, support, _) in enumerate(required) if
            not support.intersection(selection)]

  def key(i, missing, selection):
    values = dict(coverage=-sum(i in required[j][1] for j in missing),
                  consensus=float(uncertainty[i]),
                  broad_direction=-float(direction[i]),
                  distance=-float(cdist(unit[[i]], unit[
                    selection]).min()) if selection else 0.)
    return tuple(values[k] for k in pc.selectionPriority) + (int(i),)

  original_basins = {int(basins[i]) for i in original}

  def solve(selection):
    nonlocal nodes
    nodes += 1
    if nodes > pc.maxSearchNodes:
      raise ValueError(
        'known-region coverage search budget exhausted; increase JSON budget or review constraints')
    missing = uncovered(selection)
    missing_basins = original_basins - {int(basins[i]) for i in selection}
    if not missing and not missing_basins:
      # At most maxRegions representatives may be needed to cover all anchors.
      coverers = [i for i in selection if any(i in s for _, s, _ in required)]
      for n in range(min(len(coverers), pc.maxRegions) + 1):
        if any(not uncovered(c) for c in combinations(coverers, n)):
          return selection
      return None
    if len(selection) >= pc.maxProposals:
      return None
    # Branch on the tightest uncovered neighborhood; ties follow sorted theta IDs.
    supports = [required[j][1] for j in missing]
    supports += [{int(i) for i in frontier if basins[i] == b} for b in
                 sorted(missing_basins)]
    eligible = {j: [i for i in support if not selection or
                    cdist(unit[[i]], unit[
                      selection]).min() >= spec.proposal.minProposalDistance]
                for j, support in enumerate(supports)}
    j = min(eligible, key=lambda j: (len(eligible[j]), j))
    for i in sorted(eligible[j], key=lambda i: key(i, missing, selection)):
      found = solve([*selection, i])
      if found is not None:
        return found
    return None

  final = solve(original)
  removed = []
  if final is None:
    # Protect uncertainty exploration and at least one representative of each old basin.
    replaceable = [i for i in reversed(original) if
                   roles[i] not in pc.protectedRoles]
    for count in range(1, len(replaceable) + 1):
      for drop in combinations(replaceable, count):
        kept = [i for i in original if i not in drop]
        final = solve(kept)
        if final is not None:
          removed = [dict(sampleIndex=i, role=roles[i],
                          reason='Coverage/diversity capacity conflict') for i
                     in drop]
          break
      if final is not None:
        break
  if final is None:
    raise ValueError(
      'known-region coverage infeasible within JSON capacity/diversity; no benchmark handoff frozen')
  final_roles = {i: roles.get(i, 'known_useful_region') for i in final}
  region_groups = defaultdict(list)
  for region in regions:
    dist = np.linalg.norm(unit - normalize(spec.parameters, region['theta']),
                          axis=1)
    inside = sorted((i for i in final if dist[i] <= pc.radius),
                    key=lambda i: (dist[i], i))
    region.update(selectedProposals=inside,
                  selectedProposal=inside[0] if inside else None,
                  selectedDistance=float(dist[inside[0]]) if inside else None,
                  coverageStatus='represented' if inside else 'not_required',
                  reasonIfUncovered=None if inside else (
                    'rejected_by_later_evidence' if region['rejected']
                    else 'insufficient_eligible_pareto_support'))
    if region['required'] and not inside:
      raise AssertionError('supported useful region omitted')
    if inside:
      region_groups[inside[0]].append(region['thetaId'])
  return final, final_roles, dict(policy=pc.model_dump(), regions=regions,
                                  sharedRepresentatives=[
                                    dict(sampleIndex=i, thetaIds=t) for i, t in
                                    sorted(region_groups.items())],
                                  originalSelection=original,
                                  finalSelection=final, removed=removed,
                                  added=[i for i in final if i not in original],
                                  searchNodes=nodes,
                                  interpretation='Measured development evidence guides neighborhood coverage; predictions and coverage do not establish production acceptance')
