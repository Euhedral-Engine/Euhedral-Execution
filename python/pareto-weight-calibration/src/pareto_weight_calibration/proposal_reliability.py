"""Validation-based proposal authority, soft risks, and held-theta search diagnostics.

No runtime policy names, parameter counts, or response names are encoded here.
"""

from collections import Counter, defaultdict
import math
import numpy as np
from scipy.spatial.distance import cdist
from .parameter_tuning import normalize, pareto_indices


def validation_evidence(y, prediction, groups, campaigns, baselines, top_k):
  from .surrogate_tournament import metrics

  groups = np.asarray(groups)
  campaigns = np.asarray(campaigns)
  result = []
  for j in range(y.shape[1]):
    observed = np.isfinite(y[:, j])
    valid = observed & np.isfinite(prediction[:, j])
    error = prediction[valid, j] - y[valid, j]
    campaign_bias = {
      str(c): float(
          np.mean(
              prediction[valid & (campaigns == c), j]
              - y[valid & (campaigns == c), j]
          )
      )
      for c in sorted(set(campaigns[valid]))
    }
    baseline = {
      name: metrics(y[:, j], p[:, j], groups, top_k)
      for name, p in baselines.items()
    }
    result.append(
        dict(
            metrics=metrics(y[:, j], prediction[:, j], groups, top_k),
            baselines=baseline,
            coverage=float(
              valid.sum() / observed.sum()) if observed.any() else 0.0,
            biasLog=float(np.mean(error)) if len(error) else None,
            campaignBiasLog=campaign_bias,
            observedRows=int(observed.sum()),
            predictedRows=int(valid.sum()),
        )
    )
  return result


def classify(evidence, policy):
  m = evidence["metrics"]
  mean = evidence["baselines"].get("mean", {})
  linear = evidence["baselines"].get("linear", {})
  ranking = policy.ranking
  absolute = policy.absolute
  finite = lambda x: x is not None and np.isfinite(x)

  def ratio(value, base):
    return (
      value / max(base, 1e-12) if finite(value) and finite(base) else float(
        "inf")
    )

  regret_ratio = ratio(m.get("regret"), mean.get("regret"))
  # A zero-variation/zero-regret baseline is not evidence of learned ordering.
  regret_skill = (
      finite(mean.get("regret"))
      and mean["regret"] > 1e-12
      and regret_ratio <= ranking.maxRegretRatioToMean
  )
  rank_skill = finite(m.get("spearman")) and m[
    "spearman"] >= ranking.minSpearman
  top_skill = finite(m.get("topKRecall")) and m[
    "topKRecall"] >= ranking.minTopKRecall
  rank_checks = dict(
      theta=m.get("thetas", 0) >= ranking.minTheta,
      coverage=evidence["coverage"] >= ranking.minCoverage,
      ordering=bool(rank_skill or regret_skill or top_skill),
  )
  useful = all(rank_checks.values())
  rmse_ratio = ratio(m.get("rmse"), mean.get("rmse"))
  linear_ratio = ratio(m.get("rmse"), linear.get("rmse"))
  biases = evidence.get("campaignBiasLog", {})
  hard_checks = dict(
      ranking=useful,
      theta=m.get("thetas", 0) >= absolute.minTheta,
      coverage=evidence["coverage"] >= absolute.minCoverage,
      meanError=rmse_ratio <= absolute.maxRmseRatioToMean,
      linearError=linear_ratio <= absolute.maxRmseRatioToLinear,
      rmse=finite(m.get("rmse")) and m["rmse"] <= absolute.maxRmseLog,
      mae=finite(m.get("mae")) and m["mae"] <= absolute.maxMaeLog,
      bias=finite(evidence.get("biasLog"))
           and abs(evidence["biasLog"]) <= absolute.maxAbsBiasLog,
      campaigns=len(biases) >= absolute.minCampaigns,
      campaignBias=bool(biases)
                   and max(
          abs(v) for v in biases.values()) <= absolute.maxCampaignBiasLog,
  )
  authority = (
    "HARD" if all(hard_checks.values()) else "SOFT" if useful else "UNRESOLVED"
  )
  # Used only to choose between alternate direction signals, never as a deployment score.
  direction_quality = (
    max(
        m.get("spearman") or 0.0,
        1 - min(regret_ratio, 1.0),
        m.get("topKRecall") or 0.0,
    )
    if useful
    else 0.0
  )
  return dict(
      reliabilityClass=authority,
      directionUseful=useful,
      directionQuality=float(direction_quality),
      rmseRatioToMean=float(rmse_ratio),
      rmseRatioToLinear=float(linear_ratio),
      regretRatioToMean=float(regret_ratio),
      rankingChecks=rank_checks,
      absoluteChecks=hard_checks,
      evidence=evidence,
      interpretation="Authority inferred from validation, not measured production acceptance",
  )


def evidence_for_choices(y, groups, campaigns, choices, predictions, configs,
    top_k):
  selected = np.full_like(y, np.nan)
  for j, choice in enumerate(choices):
    if choice:
      a = choice["selected"]
      selected[:, j] = sum(
          w * predictions[m][:, j] for m, w in zip(a["members"], a["weights"])
      )
  baselines = {
    family: predictions[
      next(c["id"] for c in configs if
           c["family"] == family and c["degree"] == 1)
    ]
    for family in ["mean", "linear"]
  }
  return validation_evidence(y, selected, groups, campaigns, baselines, top_k)


def authority_table(spec, evidence, names):
  return {
    name: dict(output=name, **classify(e, spec.proposal.reliability))
    for name, e in zip(names, evidence)
  }


def policy_view(spec, prediction, disagreement, names, authority, old=False):
  pc = spec.proposal
  rp = pc.reliability
  n = len(prediction)
  valid = np.ones(n, bool)
  risk = {}
  treatments = {}
  failures = {}
  unresolved = []
  adjusted = prediction - pc.disagreementPenalty * disagreement
  for name, floor in pc.floorPercent.items():
    column = names.index(name)
    finite = np.isfinite(adjusted[:, column])
    shortfall = np.maximum(0.0, math.log1p(floor / 100) - adjusted[:, column])
    cls = authority[name]["reliabilityClass"]
    system = name.split(":", 1)[0]
    if old or (cls == "HARD" and system in rp.hardSystems):
      bad = ~finite | (shortfall > 0)
      valid &= ~bad
      failures[name] = bad
      treatments[name] = "hard_constraint"
    elif cls in ["HARD", "SOFT"]:
      # Keep one objective/penalty per output. Never turn their sum into a production score.
      risk[name] = np.where(
          finite,
          np.minimum(shortfall / rp.softPenaltyScaleLog, rp.softPenaltyCap),
          0.0,
      )
      treatments[name] = "soft_penalty"
    else:
      treatments[name] = "unresolved_annotation"
      unresolved.append(name)
  for name in names:
    treatments.setdefault(
        name,
        (
          "direction_only"
          if authority[name]["directionUseful"]
          else "unresolved_annotation"
        ),
    )
    if treatments[name] == "unresolved_annotation" and name not in unresolved:
      unresolved.append(name)
  # Unknown outputs remain NaN in metadata; only validated signals enter ranking.
  direction = np.full_like(adjusted, np.nan)
  sources = {}
  cap = math.log1p(rp.directionCapPercent / 100)
  for name in names:
    choices = rp.directionAlternatives.get(name, (name,))
    useful = [a for a in choices if authority[a]["directionUseful"]]
    if useful:
      chosen = max(useful, key=lambda a: authority[a]["directionQuality"])
      direction[:, names.index(name)] = np.minimum(
          adjusted[:, names.index(chosen)], cap
      )
      sources[name] = chosen
    else:
      sources[name] = None
  if old:
    direction = adjusted.copy()
    sources = {n: n for n in names}
  return dict(
      eligible=valid,
      risk=risk,
      treatments=treatments,
      unresolved=unresolved,
      failures=failures,
      direction=direction,
      directionSources=sources,
      adjusted=adjusted,
  )


def rule_scores(rules, view, names):
  columns = []
  labels = []
  for rule in rules:
    js = [
      names.index(n)
      for n in rule.targets
      if view["directionSources"].get(n) is not None
    ]
    if not js:
      continue
    v = getattr(np, rule.reduce)(view["direction"][:, js], axis=1)
    if rule.cap is not None:
      v = np.minimum(v, rule.cap)
    columns.append(v)
    labels.append(rule.name)
  return (
    np.column_stack(columns) if columns else np.zeros(
        (len(view["eligible"]), 0))
  ), labels


def search_scores(spec, view, names):
  scores, labels = rule_scores(spec.proposal.objectives, view, names)
  risks = [-r for r in view["risk"].values()]
  if risks:
    scores = np.column_stack([scores, *risks])
    labels += ["soft_risk:" + n for n in view["risk"]]
  # No usable models means a disclosed diversity/exploration design, not an invented safety prediction.
  if not scores.shape[1]:
    scores = np.zeros((len(view["eligible"]), 1))
    labels = ["unresolved_exploration"]
  return scores, labels


def history_exclusions(spec, rows, unit):
  points = np.array([normalize(spec.parameters, r["theta"]) for r in rows])
  nearest = cdist(unit, points).argmin(axis=1)
  distance = np.linalg.norm(unit - points[nearest], axis=1)
  valid = distance >= spec.proposal.minMeasuredDistance
  h = spec.proposal.historyRisk
  grouped = defaultdict(list)
  for r in rows:
    grouped[(r["campaignId"], r["thetaId"])].append(r)
  bad = []
  for (campaign, theta), observations in sorted(grouped.items()):
    failures = {}
    for name, floor in h.measuredFloors.items():
      blocks = {
        str(r.get("blockId", r["rowId"]))
        for r in observations
        if r["targets"].get(name) is not None
           and r["targets"][name] < math.log1p(floor / 100)
      }
      if len(blocks) >= h.minBlocks:
        failures[name] = sorted(blocks)
    if failures:
      bad.append(
          dict(
              campaign=campaign,
              thetaId=theta,
              theta=observations[0]["theta"],
              failedOutputs=failures,
          )
      )
  bad_distance = np.full(len(unit), np.inf)
  if bad:
    bad_unit = np.array([normalize(spec.parameters, r["theta"]) for r in bad])
    bad_distance = cdist(unit, bad_unit).min(axis=1)
    if h.radius > 0:
      valid &= bad_distance >= h.radius
  return dict(
      eligible=valid,
      distance=distance,
      nearest=[rows[i]["thetaId"] for i in nearest],
      distanceToRepeatedBad=bad_distance,
      repeatedBad=bad,
  )


def retrospective(spec, data, result, names):
  """Selection uses held predictions and training-only authority/history within each fold."""
  rc = spec.proposal.retrospective
  rows = data["rows"]
  y = np.array([[r["targets"].get(n, np.nan) for n in names] for r in rows],
               float)
  groups = np.array([r["thetaId"] for r in rows])
  all_theta = sorted(set(groups))
  measured = {}
  units = {}
  for g in all_theta:
    ix = np.flatnonzero(groups == g)
    measured[g] = np.nanmean(y[ix], axis=0)
    units[g] = normalize(spec.parameters, rows[ix[0]]["theta"])
  primary = [names.index(n) for n in rc.primaryTargets]
  topology = [names.index(n) for n in rc.topologyTargets]
  cap = math.log1p(rc.positiveCapPercent / 100)
  truth = {}
  for g, value in measured.items():
    scarce = value[primary]
    tops = value[topology] if topology else scarce
    guard = {n: float(value[names.index(n)]) for n in
             rc.measuredGuardrailFloors}
    truth[g] = dict(
        positiveScarce=int(np.sum(scarce > 0)),
        scarceLog=float(np.mean(scarce)),
        minimumTopologyLog=float(np.min(tops)),
        broadScarceLog=float(np.mean(np.minimum(scarce, cap))),
        worstPlentifulLog=min(guard.values()) if guard else None,
        measuredGuardrailFailures=[
          n
          for n, f in rc.measuredGuardrailFloors.items()
          if guard[n] < math.log1p(f / 100)
        ],
    )
  ordered = sorted(
      all_theta,
      key=lambda g: (
        -truth[g]["positiveScarce"],
        -truth[g]["minimumTopologyLog"],
        -truth[g]["broadScarceLog"],
        g,
      ),
  )
  useful = set(ordered[: max(1, math.ceil(len(ordered) * rc.usefulFraction))])
  bad = {g for g in all_theta if truth[g]["measuredGuardrailFailures"]}
  methods = ["blind_random", "old_hard_floor", "reliability_aware"]
  retained = {m: set() for m in methods}
  selected = {m: [] for m in methods}
  trials = []
  audit = []
  rng = np.random.default_rng(rc.seed)
  random_draws = [[] for _ in range(rc.randomRepeats)]
  for fold_id, fold in enumerate(result["outer"]):
    train = np.array(fold["trainRows"])
    held = np.array(fold["heldRows"])
    theta = sorted(set(groups[held]))
    unit = np.array([units[g] for g in theta])
    pred = np.array(
        [
          np.nanmean(result["outerPrediction"][held[groups[held] == g]], axis=0)
          for g in theta
        ]
    )
    authority = authority_table(spec, fold["proposalEvidence"], names)
    geometry = history_exclusions(spec, [rows[i] for i in train], unit)
    views = {
      m: policy_view(
          spec,
          pred,
          np.zeros_like(pred),
          names,
          authority,
          old=m == "old_hard_floor",
      )
      for m in methods[1:]
    }
    base = np.flatnonzero(
      geometry["distance"] >= spec.proposal.minMeasuredDistance)
    retained["blind_random"].update(theta[i] for i in base)
    for draw in random_draws:
      draw.extend(theta[i] for i in rng.permutation(base)[: rc.batchPerFold])
    for method, view in views.items():
      geometry_valid = (
        geometry["eligible"]
        if method == "reliability_aware"
        else geometry["distance"] >= spec.proposal.minMeasuredDistance
      )
      ids = np.flatnonzero(geometry_valid & view["eligible"])
      retained[method].update(theta[i] for i in ids)
      scores, _ = search_scores(spec, view, names)
      # Non-dominated sorting first; broad/min-topology role ranks break ties.
      pool = ids.tolist()
      ranked = []
      role, role_names = rule_scores(spec.proposal.roles, view, names)
      while pool and len(ranked) < rc.batchPerFold:
        front = [pool[i] for i in pareto_indices(scores[pool])]
        front.sort(key=lambda i: (*[-float(x) for x in role[i]], theta[i]))
        ranked.extend(front)
        pool = [i for i in pool if i not in front]
      chosen = ranked[: rc.batchPerFold]
      selected[method].extend(theta[i] for i in chosen)
      for i, g in enumerate(theta):
        audit.append(
            dict(
                fold=fold_id,
                method=method,
                thetaId=g,
                retained=bool(i in ids),
                selected=bool(i in chosen),
                scarcityUseful=g in useful,
                measuredGuardrailBad=g in bad,
                failedPredictedFloors=[
                  n for n, v in view["failures"].items() if v[i]
                ],
                unresolvedOutputs=view["unresolved"],
                trainingTheta=fold["trainTheta"],
            )
        )
    trials.append(
        dict(
            fold=fold_id,
            heldTheta=theta,
            trainingTheta=fold["trainTheta"],
            reliability={n: a["reliabilityClass"] for n, a in
                         authority.items()},
        )
    )

  def outcomes(chosen, kept):
    return dict(
        selectedCount=len(chosen),
        retained=len(kept),
        usefulRetained=len(kept & useful),
        badRemoved=len(bad - kept),
        usefulCount=len(useful),
        badCount=len(bad),
        hitRate=sum(g in useful for g in chosen) / len(
          chosen) if chosen else None,
        medianScarcePercent=(
          100
          * math.expm1(
            float(np.median([truth[g]["scarceLog"] for g in chosen])))
          if chosen
          else None
        ),
        worstPlentifulPercent=(
          100 * math.expm1(min(truth[g]["worstPlentifulLog"] for g in chosen))
          if chosen and rc.measuredGuardrailFloors
          else None
        ),
        measuredBadSelected=sum(g in bad for g in chosen),
        searchSpaceReduction=1 - len(kept) / len(all_theta),
    )

  comparison = [
    dict(method=m, **outcomes(selected[m], retained[m])) for m in methods[1:]
  ]
  random_stats = [outcomes(draw, retained["blind_random"]) for draw in
                  random_draws]
  random_mean = {
    k: (
      float(np.mean([r[k] for r in random_stats]))
      if random_stats[0][k] is not None
      else None
    )
    for k in random_stats[0]
  }
  comparison.insert(0, dict(method="blind_random", **random_mean))
  policies = defaultdict(set)
  for fork in data.get("forks", []):
    policies[fork["policyId"]].add(fork["thetaId"])
  known = [
    dict(
        policyId=p,
        thetaId=g,
        **truth[g],
        retained={m: g in retained[m] for m in methods},
        selected={m: g in selected[m] for m in methods[1:]}
    )
    for p in rc.knownPolicyIds
    for g in sorted(policies[p])
    if g in truth
  ]
  weak_veto = Counter()
  for a in audit:
    if a["method"] == "old_hard_floor" and a["scarcityUseful"]:
      authority = trials[a["fold"]]["reliability"]
      for name in a["failedPredictedFloors"]:
        if authority[name] != "HARD":
          weak_veto[name] += 1
  return dict(
      comparison=comparison,
      folds=trials,
      heldActions=audit,
      knownPolicyRecovery=known,
      truth={
        g: dict(thetaId=g, scarcityUseful=g in useful, **v)
        for g, v in truth.items()
      },
      weakVetoOfUseful=dict(weak_veto),
      randomSeed=rc.seed,
      randomRepeats=rc.randomRepeats,
      randomDistribution={
        k: [r[k] for r in random_stats]
        for k in ["hitRate", "medianScarcePercent", "worstPlentifulPercent"]
      },
      labelDefinition="Top declared fraction by positive scarce-body count, minimum topology return, then capped broad scarcity; this is scarcity usefulness, not production acceptance",
      interpretation="Retrospective development diagnostic. Each action uses outer-held theta predictions and inner-training-only authority. No fresh benchmark evidence; random means are Monte Carlo comparisons, not confidence intervals.",
  )
