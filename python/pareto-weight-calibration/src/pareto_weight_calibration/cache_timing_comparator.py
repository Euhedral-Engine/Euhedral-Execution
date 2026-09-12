"""Empirical fixed-policy comparator with complete workload-family holdouts."""
from __future__ import annotations

from collections import defaultdict
import math
import statistics

from .training_runner import split_plan


def workload_stats(records, baseline=(15000, 1000000)):
  groups = defaultdict(list)
  seen = set()
  for r in records:
    key = (r['runId'], r['forkId'])
    if key in seen: raise ValueError('duplicate fork in comparator')
    seen.add(key)
    groups[r['workloadId'], tuple(r['policyParameters'])].append(r)
  stats = {}
  for (workload, policy), rows in groups.items():
    baselines = groups[workload, baseline]
    if not baselines: raise ValueError('missing workload baseline')
    means = [r['outcome']['executionsPerSecond'] for r in rows]
    baseline_means = [r['outcome']['executionsPerSecond'] for r in baselines]
    if min(means + baseline_means) <= 0: raise ValueError(
      'throughput must be positive')
    ratio = statistics.mean(means) / statistics.mean(baseline_means)
    base_pass = {r['passId']: r['outcome']['executionsPerSecond'] for r in
                 baselines}
    stats[workload, policy] = dict(workloadId=workload, policy=list(policy),
                                   baselineMean=statistics.mean(baseline_means),
                                   candidateMean=statistics.mean(means),
                                   logRatio=math.log(ratio),
                                   percentChange=100 * (ratio - 1),
                                   baselineMinimumForkMean=min(baseline_means),
                                   candidateMinimumForkMean=min(means),
                                   baselineMinimumWindow=min(
                                       v for r in baselines for v in
                                       r['outcome']['measurementWindows']),
                                   candidateMinimumWindow=min(
                                       v for r in rows for v in
                                       r['outcome']['measurementWindows']),
                                   matchedPass=[dict(passId=r['passId'],
                                                     percentChange=100 * (
                                                           r['outcome'][
                                                             'executionsPerSecond'] /
                                                           base_pass[
                                                             r['passId']] - 1))
                                                for r in rows])
  return stats


def score(stats, families, policy):
  rows = [stats[w, policy] for w in sorted(families)]
  j = statistics.mean(r['logRatio'] for r in rows)
  return dict(J=j, geometricChangePercent=100 * math.expm1(j),
              worstWorkloadPercent=min(r['percentChange'] for r in rows),
              perWorkload=rows)


def choose(stats, training_families):
  families = sorted(training_families)
  policies = sorted({p for w, p in stats if w in families})
  if any((w, p) not in stats for w in families for p in policies):
    raise ValueError('incomplete training policy coverage')
  scores = [(score(stats, families, p)['J'], p) for p in policies]
  best = max(s for s, _ in scores)
  tied = [p for s, p in scores if abs(s - best) <= 1e-12]
  return min(tied), [list(p) for p in tied]


def compare(spec, data):
  records = list(data.metadata)
  stats = workload_stats(records)

  def fold(training, held):
    tf = set(training.groups);
    hf = set(held.groups)
    if tf & hf: raise ValueError('workload-family leakage')
    policy, ties = choose(stats, tf)
    return dict(trainingFamilies=sorted(tf), heldFamilies=sorted(hf),
                selectedPolicy=list(policy),
                tiedPolicies=ties,
                tieBreak='lexicographic pair within 1e-12 J; not a learned timing response',
                training=score(stats, tf, policy),
                held=score(stats, hf, policy))

  outer = []
  for ti, hi in split_plan(data, spec.validation.outerFolds,
                           spec.validation.seed):
    training, held = data.subset(ti), data.subset(hi)
    result = fold(training, held)
    result['inner'] = [fold(training.subset(i), training.subset(j)) for i, j in
                       split_plan(training, spec.validation.innerFolds,
                                  spec.validation.seed)]
    outer.append(result)
  held_rows = [r for f in outer for r in f['held']['perWorkload']]
  j = statistics.mean(r['logRatio'] for r in held_rows)
  return dict(schemaVersion=1, kind='empirical_constant_policy_comparator',
              taskHash=spec.schema_hash,
              evidenceProvenance=data.provenance,
              selectionObjective='mean workload log baseline ratio',
              outer=outer, heldJ=j,
              heldGeometricChangePercent=100 * math.expm1(j),
              worstHeldWorkloadPercent=min(
                  r['percentChange'] for r in held_rows),
              responseRegressorDiagnostic='constant throughput predictor retained only as no-response diagnostic; no timing inference',
              validationMeaning='retrospective development evidence from the completed fixed surface, not fresh validation; fixed algorithm; inner results describe stability, not a choice made using outer outcomes')
