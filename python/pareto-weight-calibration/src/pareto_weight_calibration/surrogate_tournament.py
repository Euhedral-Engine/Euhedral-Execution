"""Nested JSON-driven arbitrary-parameter tournaments, ensembles and bounded Pareto search."""

from collections import defaultdict
from pathlib import Path
import csv
import json
import math
import os
import platform
import time
import warnings
import importlib.metadata
import joblib
import numpy as np
from scipy.stats import qmc, spearmanr
from scipy.spatial.distance import cdist
from sklearn.cluster import KMeans
from threadpoolctl import threadpool_limits
from .surrogate_spec import SurrogateTask
from .surrogate_models import Model, expand
from .historical_response import build, sha, read
from .parameter_tuning import map_parameters, normalize, construct, \
  pareto_indices
from .training_spec import canonical, digest


def write(path, value):
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def clean(x):
  if isinstance(x, dict):
    return {str(k): clean(v) for k, v in x.items()}
  if isinstance(x, (list, tuple)):
    return [clean(v) for v in x]
  if isinstance(x, np.ndarray):
    return clean(x.tolist())
  if isinstance(x, (float, np.floating)):
    return float(x) if np.isfinite(x) else None
  if isinstance(x, np.integer):
    return int(x)
  return x


def tsv(path, rows):
  if not rows:
    path.write_text("status\nno_rows\n")
    return
  with path.open("w") as f:
    writer = csv.DictWriter(f, fieldnames=list(rows[0]), delimiter="\t")
    writer.writeheader()
    for row in rows:
      writer.writerow(
          {
            k: (
              json.dumps(v, sort_keys=True)
              if isinstance(v, (dict, list))
              else v
            )
            for k, v in clean(row).items()
          }
      )


def folds(groups, n, seed):
  unique = np.array(sorted(set(groups)))
  rng = np.random.default_rng(seed)
  rng.shuffle(unique)
  if len(unique) < 2:
    return []
  result = []
  for held in np.array_split(unique, min(n, len(unique))):
    mask = np.isin(groups, held)
    result.append((np.flatnonzero(~mask), np.flatnonzero(mask)))
  return result


def metrics(y, p, groups, k=2):
  valid = np.isfinite(y) & np.isfinite(p)
  if not valid.any():
    return dict(
        rmse=float("inf"),
        mae=float("inf"),
        regret=float("inf"),
        rankLoss=float("inf"),
        spearman=None,
        topKRecall=None,
        rows=0,
        thetas=0,
    )
  yy = y[valid]
  pp = p[valid]
  gg = np.asarray(groups)[valid]
  unique = sorted(set(gg))
  ys = np.array([np.mean(yy[gg == g]) for g in unique])
  ps = np.array([np.mean(pp[gg == g]) for g in unique])
  ranking = len(unique) > 1 and np.ptp(ys) > 1e-12
  corr = float(spearmanr(ys, ps).statistic) if ranking and np.ptp(
    ps) > 1e-12 else 0.0
  best = ps >= ps.max() - 1e-12
  regret = float(ys.max() - ys[best].mean()) if ranking else 0.0
  # Fractional credit at prediction ties avoids a lexicographic timing pair becoming a learned action.
  count = min(k, len(ys))
  true_top = set(np.argsort(-ys, kind="stable")[:count])
  cut = np.sort(ps)[-count]
  above = np.flatnonzero(ps > cut)
  tied = np.flatnonzero(ps == cut)
  recall = (
    (
        len(true_top.intersection(above))
        + (count - len(above)) * len(true_top.intersection(tied)) / len(tied)
    )
    / count
    if ranking
    else None
  )
  return dict(
      rmse=float(np.sqrt(np.mean((yy - pp) ** 2))),
      mae=float(np.mean(abs(yy - pp))),
      regret=regret,
      rankLoss=1 - corr,
      spearman=corr if ranking else None,
      topKRecall=recall,
      rows=len(yy),
      thetas=len(unique),
  )


def sort_key(m, names):
  return tuple(m[k] for k in names)


def fit_job(job):
  """Pure worker result. Only the parent merges caches, warnings and failures."""
  c, x, y, train, test, weights, seed, execution, keep = job
  report = dict(model=c['id'], trainingRows=len(train))
  try:
    try:
      model = Model(c, seed, execution).fit(x[train], y[train], weights)
    except Exception as error:
      if not execution.get('nativeCuda'): raise
      execution = dict(execution, nativeCuda=False)
      model = Model(c, seed, execution).fit(x[train], y[train], weights)
      model.backend_fallback = type(error).__name__ + ': ' + str(error)
    prediction = model.predict(x[test])
    ws = [w for _, _, values in model.parts for w in values]
    return dict(prediction=prediction, model=model if keep else None,
                warnings=ws,
                backend=getattr(model, 'actual_backend', 'cpu'),
                fallback=getattr(model, 'backend_fallback', None), failure=None)
  except Exception as error:
    return dict(prediction=np.full((len(test), y.shape[1]), np.nan), model=None,
                warnings=[], backend='failed',
                failure=dict(report,
                             error=type(error).__name__ + ': ' + str(error)),
                fallback=None)


class Tournament:
  def __init__(self, spec, x, y, groups, campaigns, output, pool=None,
      reference_groups=None):
    self.spec = spec
    self.x = x
    self.y = y
    self.groups = np.asarray(groups)
    self.reference_groups = np.asarray(
      reference_groups) if reference_groups is not None else None
    self.campaigns = np.asarray(campaigns)
    self.output = output
    self.configs = expand(spec.models)
    self.failures = []
    self.warning_counts = defaultdict(int)
    self.prediction_cache = {}
    self.started = time.time()
    from .execution_resources import ExecutionPool, execution_config
    self.owns_pool = pool is None
    self.pool = pool or ExecutionPool(execution_config(spec))
    self.backends = []

  def batch(self, requests, keep=False):
    unique = {}
    for c, train, test in requests:
      key = (c['id'], tuple(train), tuple(test))
      if keep or key not in self.prediction_cache: unique[key] = (c, train,
                                                                  test)
    jobs = []
    for c, train, test in unique.values():
      weights = None
      if self.spec.validation.sampleWeight == 'inverse_theta_frequency':
        g = self.groups[train];
        weights = np.array([1 / np.sum(g == v) for v in g]);
        weights *= len(weights) / weights.sum()
      execution = dict(self.pool.resources)
      execution['nativeCuda'] = execution['nativeCuda'] and c['family'] in [
        'xgboost', 'catboost'] and (
                                    execution[
                                      'requestedDevice'] == 'cuda' or len(
                                  train) >= self.pool.config.cudaMinFitRows)
      jobs.append(
          (c, self.x, self.y, train, test, weights, self.spec.seed, execution,
           keep))
    results = self.pool.map_fit(fit_job, jobs,
                                gpu_predicate=lambda j: j[7]['nativeCuda'])
    models = {}
    for key, result in zip(unique, results):
      self.prediction_cache[key] = result['prediction']
      if result['failure']: self.failures.append(result['failure'])
      for warning in result['warnings']: self.warning_counts[
        key[0] + ': ' + warning] += 1
      self.backends.append(dict(model=key[0], backend=result['backend'],
                                fallback=result['fallback']))
      if keep and result['model'] is not None: models[key[0]] = result['model']
    if keep: return models
    return [self.prediction_cache[(c['id'], tuple(train), tuple(test))] for
            c, train, test in requests]

  def fit_predict(self, c, train, test):
    return self.batch([(c, train, test)])[0]

  def grouped_folds(self, indices, n, seed):
    plan = folds(self.groups[indices], n, seed)
    if self.reference_groups is None: return plan
    # A measured held policy must not leak back into training as a ratio denominator.
    return [(train[~np.isin(self.reference_groups[indices[train]],
                            self.groups[indices[test]])], test)
            for train, test in plan]

  def cv(self, indices, n, seed):
    plan = self.grouped_folds(indices, n, seed)
    requests = [(c, indices[train], indices[test]) for c in self.configs for
                train, test in plan]
    predictions = iter(self.batch(requests));
    result = {}
    for c in self.configs:
      out = np.full((len(indices), self.y.shape[1]), np.nan)
      for train, test in plan: out[test] = next(predictions)
      result[c['id']] = out
    return result

  def choose(self, indices, oof):
    choice = []
    leader = []
    v = self.spec.validation
    for j in range(self.y.shape[1]):
      ranked = []
      for c in self.configs:
        pred = oof[c["id"]][:, j]
        observed = np.isfinite(self.y[indices, j])
        # A partially failing model cannot win by being evaluated on its easy subset.
        if not observed.any() or not np.isfinite(pred[observed]).all():
          continue
        m = metrics(self.y[indices, j], pred, self.groups[indices], v.topK)
        ranked.append((c, m))
        leader.append(
            dict(
                output=j,
                model=c["id"],
                family=c["family"],
                degree=c["degree"],
                mode=c["mode"],
                **m,
            )
        )
      ranked.sort(
        key=lambda a: (sort_key(a[1], v.selectionMetrics), a[0]["id"]))
      if not ranked:
        choice.append(None)
        continue
      best = ranked[0]
      members = []
      seen = set()
      for c, mm in ranked:
        family = (c["family"], c["degree"])
        if family in seen:
          continue
        seen.add(family)
        members.append((c, mm))
        if len(members) == v.ensembleSize:
          break
      options = [
        dict(
            kind="single",
            members=[best[0]["id"]],
            weights=[1.0],
            metrics=best[1],
        )
      ]
      if len(members) >= 2:
        for method in v.ensembleStrategies:
          weights = (
            np.ones(len(members))
            if method == "mean"
            else np.array(
                [
                  1 / max(mm[v.weightMetric], 1e-8) ** 2
                  for _, mm in members
                ]
            )
          )
          weights /= weights.sum()
          prediction = sum(
              w * oof[c["id"]][:, j] for w, (c, _) in zip(weights, members)
          )
          mm = metrics(
              self.y[indices, j], prediction, self.groups[indices], v.topK
          )
          options.append(
              dict(
                  kind=method,
                  members=[c["id"] for c, _ in members],
                  weights=weights.tolist(),
                  metrics=mm,
              )
          )
      options.sort(
          key=lambda o: (sort_key(o["metrics"], v.selectionMetrics), o["kind"])
      )
      choice.append(
          dict(
              selected=options[0],
              alternatives=options,
              ranked=[dict(model=c["id"], metrics=mm) for c, mm in ranked],
          )
      )
    return choice, leader

  def evaluate_selection(self, choice, train, test):
    needed = {
      m for o in choice if o for a in o["alternatives"] for m in a["members"]
    }
    configs = [c for c in self.configs if c['id'] in needed]
    predictions = dict(zip([c['id'] for c in configs],
                           self.batch([(c, train, test) for c in configs])))
    selected = np.full((len(test), self.y.shape[1]), np.nan)
    alternatives = {
      kind: selected.copy()
      for kind in ["single", *self.spec.validation.ensembleStrategies]
    }
    for j, o in enumerate(choice):
      if o is None:
        continue
      for a in o["alternatives"]:
        pred = sum(
            w * predictions[m][:, j] for m, w in zip(a["members"], a["weights"])
        )
        alternatives[a["kind"]][:, j] = pred
        if a == o["selected"]:
          selected[:, j] = pred
    return selected, alternatives

  def run(self):
    try:
      return self._run()
    finally:
      if self.owns_pool: self.pool.close()

  def _run(self):
    all_indices = np.arange(len(self.x))
    outer = self.grouped_folds(np.arange(len(self.groups)),
                               self.spec.validation.outerFolds, self.spec.seed)
    outer_prediction = np.full_like(self.y, np.nan)
    individual = {c["id"]: np.full_like(self.y, np.nan) for c in self.configs}
    ensemble_oof = {
      k: np.full_like(self.y, np.nan)
      for k in ["single", *self.spec.validation.ensembleStrategies]
    }
    plans = []
    for i, (train, test) in enumerate(outer):
      print(
          f"outer {i + 1}/{len(outer)}: {len(train)} train, {len(test)} held rows",
          flush=True,
      )
      oof = self.cv(
          train, self.spec.validation.innerFolds, self.spec.seed + 100 + i
      )
      choice, _ = self.choose(train, oof)
      proposal_evidence = None
      if self.spec.proposal.reliability.enabled:
        from .proposal_reliability import evidence_for_choices
        proposal_evidence = evidence_for_choices(self.y[train],
                                                 self.groups[train],
                                                 self.campaigns[train], choice,
                                                 oof, self.configs,
                                                 self.spec.validation.topK)
      predictions, alternatives = self.evaluate_selection(choice, train, test)
      outer_prediction[test] = predictions
      for kind, p in alternatives.items():
        ensemble_oof[kind][test] = p

      for c, p in zip(self.configs,
                      self.batch([(c, train, test) for c in self.configs])):
        individual[c['id']][test] = p
      plans.append(
          dict(
              trainRows=train.tolist(),
              heldRows=test.tolist(),
              trainTheta=sorted(set(self.groups[train])),
              heldTheta=sorted(set(self.groups[test])),
              selection=choice,
              proposalEvidence=proposal_evidence,
          )
      )
    print("final development selection", flush=True)
    oof = self.cv(
        all_indices, self.spec.validation.innerFolds, self.spec.seed + 1000
    )
    choice, leader = self.choose(all_indices, oof)
    transfer = []
    if self.spec.validation.campaignTransfer:
      for campaign in sorted(set(self.campaigns)):
        test = np.flatnonzero(self.campaigns == campaign)
        train = np.flatnonzero(self.campaigns != campaign)
        if len(set(self.groups[train])) < 2:
          transfer.append(
              dict(campaign=campaign, status="insufficient training theta")
          )
          continue
        print("campaign transfer " + campaign, flush=True)
        cv = self.cv(
            train, self.spec.validation.innerFolds, self.spec.seed + 2000
        )
        selected, _ = self.choose(train, cv)
        p, _ = self.evaluate_selection(selected, train, test)
        transfer.append(
            dict(
                campaign=campaign,
                status="evaluated",
                heldRows=test.tolist(),
                trainingRows=train.tolist(),
                sharedTheta=sorted(
                    set(self.groups[train]) & set(self.groups[test])
                ),
                metrics=[
                  metrics(
                      self.y[test, j],
                      p[:, j],
                      self.groups[test],
                      self.spec.validation.topK,
                  )
                  for j in range(self.y.shape[1])
                ],
            )
        )
    # Final estimators are selected solely by full-development inner CV, never by outer test ranking.
    needed = {
      name
      for o in choice
      if o
      for a in o["alternatives"]
      for name in a["members"]
    }
    for o in choice:
      if o:
        floor = (
            o["ranked"][0]["metrics"]["rmse"]
            * self.spec.proposal.ensembleTolerance
        )
        needed.update(
            r["model"] for r in o["ranked"][:8] if r["metrics"]["rmse"] <= floor
        )
    models = self.batch([(c, all_indices, all_indices) for c in self.configs if
                         c['id'] in needed], keep=True)
    joblib.dump(
        dict(
            models=models,
            selection=choice,
            parameters=[p.model_dump() for p in self.spec.parameters],
        ),
        self.output / "models.joblib",
    )
    # Reload only our freshly written local artifact and verify prediction parity.
    restored = joblib.load(self.output / "models.joblib")
    checks = self.pool.map_predict(lambda k: (models[k].predict(self.x),
                                              restored['models'][k].predict(
                                                self.x)), list(models))
    for actual, expected in checks: np.testing.assert_allclose(actual, expected,
                                                               equal_nan=True)
    return dict(
        choice=choice,
        leader=leader,
        outer=plans,
        outerPrediction=outer_prediction,
        individualPrediction=individual,
        ensemblePrediction=ensemble_oof,
        transfer=transfer,
        models=models,
        failures=self.failures,
        warnings=dict(self.warning_counts),
        execution=dict(resources=self.pool.resources, backends=self.backends),
    )


def rule_value(rule, pred, names):
  values = pred[:, [names.index(n) for n in rule.targets]]
  out = getattr(np, rule.reduce)(values, axis=1)
  return np.minimum(out, rule.cap) if rule.cap is not None else out


def proposal_search(spec, root, data, result, names, output):
  pc = spec.proposal
  if pc.denseInference.enabled:
    from .dense_search import search
    return search(spec, root, data, result, names, output)
  unit = qmc.Sobol(len(spec.parameters), scramble=True,
                   seed=pc.seed).random_base2(
      pc.power
  )
  theta = np.array([map_parameters(spec.parameters, x) for x in unit])
  measured = np.unique(np.array([r["theta"] for r in data["rows"]]), axis=0)
  measured_unit = np.array([normalize(spec.parameters, x) for x in measured])
  distance = cdist(unit, measured_unit).min(axis=1)
  # Models use declared transforms of the actual parameters, fitted/scaled within each training fold.
  features = theta.copy()
  for j, p in enumerate(spec.parameters):
    if p.transform == "log":
      features[:, j] = np.log(features[:, j])
  from .execution_resources import ExecutionPool, execution_config
  from .cuda_predict import CudaPredictor
  from .bulk_predict import predict_columns
  with ExecutionPool(execution_config(spec)) as pool:
    accelerator = CudaPredictor(pool)
    try:
      items = list(result['models'].items())
      accelerator.begin_batch()
      values = pool.map_predict(
        lambda item: predict_columns(item[1], features, list(range(len(names))),
                                     accelerator), items)
      allpred = dict(zip([name for name, _ in items], values))
    finally:
      accelerator.close()
  prediction = np.full((len(unit), len(names)), np.nan)
  disagreement = prediction.copy()
  members_by_output = {}
  for j, selection in enumerate(result["choice"]):
    if selection is None:
      continue
    chosen = selection["selected"]
    prediction[:, j] = sum(
        w * allpred[m][:, j] for m, w in
        zip(chosen["members"], chosen["weights"])
    )
    floor = selection["ranked"][0]["metrics"]["rmse"] * pc.ensembleTolerance
    members = [
      r["model"]
      for r in selection["ranked"][:8]
      if r["model"] in allpred and r["metrics"]["rmse"] <= max(floor, 1e-12)
    ]
    members = sorted(set(members + chosen["members"]))
    members_by_output[names[j]] = members
    disagreement[:, j] = np.std([allpred[m][:, j] for m in members], axis=0)
  valid = np.isfinite(prediction).all(axis=1) & (
        distance >= pc.minMeasuredDistance)
  reasons = np.array(["eligible"] * len(unit), dtype=object)
  reasons[distance < pc.minMeasuredDistance] = "measured proximity"
  support_bad = np.zeros(len(unit), bool)
  functions = {}
  if pc.support:
    center = read(root / spec.dataset.centerArtifact)
    if pc.support.get("adapter") != "cache_timing":
      raise ValueError("unregistered support adapter")
    from .cache_timing import evaluate

    for i, values in enumerate(theta):
      try:
        cfg = construct(spec.parameters, center, values.tolist())
        outputs = np.array(
            [
              evaluate(cfg, c, p, math.expm1(b))
              for c, p, b in pc.support["points"]
            ]
        )
        limits = [
          (cfg["parkMinNanos"], cfg["parkMaxNanos"]),
          (cfg["halfLifeMinNanos"], cfg["halfLifeMaxNanos"]),
        ]
        support_bad[i] = any(
            max(np.mean(outputs[:, j] == lo), np.mean(outputs[:, j] == hi))
            > pc.support["maxSingleBoundaryFraction"]
            for j, (lo, hi) in enumerate(limits)
        )
        functions[i] = cfg
      except (ValueError, OverflowError):
        support_bad[i] = True
  valid &= ~support_bad
  reasons[support_bad] = "support clamp filter"
  if pc.reliability.enabled:
    from .reliable_proposals import search
    return search(spec, root, data, result, names, output, unit, theta,
                  prediction, disagreement, allpred, members_by_output,
                  functions, support_bad)
  adjusted = prediction - pc.disagreementPenalty * disagreement
  for name, floor in pc.floorPercent.items():
    bad = adjusted[:, names.index(name)] < math.log1p(floor / 100)
    reasons[valid & bad] = "predicted floor"
    valid &= ~bad
  max_disagreement = np.nanmax(disagreement, axis=1)
  if pc.maxDisagreementLog is not None:
    bad = max_disagreement > pc.maxDisagreementLog
    reasons[valid & bad] = "disagreement cap"
    valid &= ~bad
  ids = np.flatnonzero(valid)
  scores = np.column_stack(
      [rule_value(rule, adjusted, names) for rule in pc.objectives]
  )
  frontier = ids[pareto_indices(scores[ids])] if len(ids) else np.array([],
                                                                        dtype=int)
  basins = np.full(len(unit), -1, dtype=int)
  if len(frontier):
    k = min(pc.clusters, len(frontier))
    cluster = KMeans(n_clusters=k, random_state=pc.seed, n_init=10).fit(
        unit[frontier]
    )
    basins[frontier] = cluster.labels_
  chosen = []
  roles = {}

  def add(i, role):
    if i in chosen:
      return False
    if chosen and cdist(unit[[i]], unit[chosen]).min() < pc.minProposalDistance:
      return False
    chosen.append(int(i))
    roles[int(i)] = role
    return True

  # Preserve separated non-dominated regions first; never average their coefficient vectors.
  for basin in sorted(set(basins[frontier])):
    members = frontier[basins[frontier] == basin]
    if len(chosen) >= pc.count:
      break
    for best in sorted(members, key=lambda i: (max_disagreement[i], int(i))):
      if add(best, "basin_consensus"):
        break
  for rule in pc.roles:
    if len(chosen) >= pc.count - pc.explorationCount:
      break
    values = rule_value(rule, adjusted, names)
    for i in sorted(
        frontier, key=lambda i: (-values[i], max_disagreement[i], int(i))
    ):
      if add(i, rule.name):
        break
  if pc.explorationCount:
    for i in sorted(frontier, key=lambda i: (-max_disagreement[i], int(i))):
      if len(chosen) >= pc.count:
        break
      if (
          add(i, "model_disagreement")
          and sum(r == "model_disagreement" for r in roles.values())
          >= pc.explorationCount
      ):
        break
  while len(chosen) < pc.count:
    pool = [i for i in frontier if i not in chosen]
    if not pool:
      break
    pool.sort(
        key=lambda i: (
          -cdist(unit[[i]], unit[chosen]).min() if chosen else 0,
          int(i),
        )
    )
    if not add(pool[0], "diverse_tradeoff"):
      break
  selected = []
  for i in chosen:
    selected.append(
        dict(
            id=f"{spec.id}-round{pc.round}-sample{i}",
            sampleIndex=i,
            theta=theta[i].tolist(),
            parameters=dict(
                zip([p.name for p in spec.parameters], theta[i].tolist())
            ),
            normalizedCoordinates=unit[i].tolist(),
            basin=int(basins[i]),
            role=roles[i],
            distanceToMeasured=float(distance[i]),
            predictions=dict(zip(names, prediction[i].tolist())),
            modelDisagreement=dict(zip(names, disagreement[i].tolist())),
            function=functions.get(i),
            predictionOnly=True,
        )
    )
  fields = [
    dict(
        sampleIndex=int(i),
        status=str(reasons[i]),
        basin=int(basins[i]),
        distanceToMeasured=distance[i],
        theta=theta[i].tolist(),
        predictedLogReturns=dict(zip(names, prediction[i].tolist())),
        disagreementLog=dict(zip(names, disagreement[i].tolist())),
    )
    for i in range(len(unit))
  ]
  tsv(output / "dense_predictions.tsv", fields)
  tsv(output / "pareto_frontier.tsv", [fields[i] for i in frontier])
  tsv(
      output / "model_consensus.tsv",
      [dict(output=k, members=v) for k, v in members_by_output.items()],
  )
  manifest = dict(
      schemaVersion=1,
      round=pc.round,
      parentDataset=pc.parentDataset,
      taskHash=digest(spec.model_dump()),
      bounds=[p.model_dump() for p in spec.parameters],
      measuredTheta=measured.tolist(),
      activeBasins=[
        dict(id=int(b), members=int(np.sum(basins == b)))
        for b in sorted(set(basins[frontier]))
      ],
      searched=len(unit),
      eligible=len(ids),
      paretoSize=len(frontier),
      selected=selected,
      stopReason=(
        None
        if selected
        else "No candidate satisfied declared predictive constraints; no automatic relaxation"
      ),
      uncertaintyMeaning="model disagreement only; not a calibrated confidence interval",
      convergence=pc.convergence,
      benchmarkExecution=False,
  )
  write(output / "proposals.json", clean(manifest))
  return manifest


def run_task(path, output_override=None, dry_run=False,
    execution_overrides=None):
  spec = SurrogateTask.model_validate(read(path))
  if execution_overrides:
    from .execution_resources import execution_config
    spec = spec.model_copy(
      update={'execution': execution_config(spec, execution_overrides),
              'jobs': None})
  root = (path.resolve().parent / spec.root).resolve()
  output = Path(
    output_override) if output_override else root / spec.outputDirectory
  if dry_run:
    return dict(
        task=spec.id,
        configurations=len(expand(spec.models)),
        parameters=len(spec.parameters),
        outputs=len(spec.responses) * len(spec.systems),
        output=str(output),
    )
  if output.exists():
    raise ValueError(
        "output must be a new directory; preserve previous tournament artifacts"
    )
  output.mkdir(parents=True)
  write(output / "task.json", spec.model_dump())
  write(output / "schema.json", SurrogateTask.model_json_schema())
  if spec.reuseFit is not None:
    from .proposal_reuse import replay
    return replay(spec, root, output)
  data = build(spec, root)
  write(output / "dataset.json", data)
  tsv(output / "historical_audit.tsv", data["audit"])
  write(
      output / "dataset_manifest.json",
      dict(
          schemaVersion=1,
          dataset="dataset.json",
          sha256=sha(output / "dataset.json"),
          inputHashes=data["inputHashes"],
          rows=len(data["rows"]),
          forks=len(data["forks"]),
          controls=len(data["controls"]),
          theta=len({r["thetaId"] for r in data["rows"]}),
      ),
  )
  names = [system + ":" + r.name for system in spec.systems for r in
           spec.responses]
  rows = data["rows"]
  for name in spec.requireVaryingParameters:
    index = [p.name for p in spec.parameters].index(name)
    if len({r['theta'][index] for r in rows}) < 3:
      raise ValueError(
        'measurement required: fewer than three observed values for ' + name)
  x = np.array([r["theta"] for r in rows])
  y = np.array(
      [[r["targets"].get(n, np.nan) for n in names] for r in rows], dtype=float
  )
  for j, p in enumerate(spec.parameters):
    if p.transform == "log":
      x[:, j] = np.log(x[:, j])
  groups = [r["thetaId"] for r in rows]
  campaigns = [r["campaignId"] for r in rows]
  print(
      f"dataset: {len(rows)} bundles, {len(set(groups))} theta, {len(names)} outputs",
      flush=True,
  )
  tournament = Tournament(spec, x, y, groups, campaigns, output)
  result = tournament.run()
  leaderboard = []
  for c in tournament.configs:
    for j, name in enumerate(names):
      leaderboard.append(
          dict(
              output=name,
              model=c["id"],
              family=c["family"],
              degree=c["degree"],
              mode=c["mode"],
              **metrics(
                  y[:, j],
                  result["individualPrediction"][c["id"]][:, j],
                  groups,
                  spec.validation.topK,
              ),
          )
      )
  tsv(output / "model_leaderboard.tsv", leaderboard)
  ensemble_rows = []
  for kind, pred in result["ensemblePrediction"].items():
    for j, name in enumerate(names):
      ensemble_rows.append(
          dict(
              output=name,
              strategy=kind,
              **metrics(y[:, j], pred[:, j], groups, spec.validation.topK),
          )
      )
  tsv(output / "ensemble_results.tsv", ensemble_rows)
  selected_metrics = [
    dict(
        output=name,
        **metrics(
            y[:, j], result["outerPrediction"][:, j], groups,
            spec.validation.topK
        ),
    )
    for j, name in enumerate(names)
  ]
  tsv(output / "held_theta_metrics.tsv", selected_metrics)
  if spec.proposal.reliability.enabled:
    from .proposal_reliability import validation_evidence, authority_table, \
      retrospective
    baselines = {family: result['individualPrediction'][
      next(c['id'] for c in tournament.configs
           if c['family'] == family and c['degree'] == 1)] for family in
                 ['mean', 'linear']}
    evidence = validation_evidence(y, result['outerPrediction'], groups,
                                   campaigns, baselines, spec.validation.topK)
    result['proposalAuthority'] = authority_table(spec, evidence, names)
    write(output / 'reliability_policy.json',
          spec.proposal.reliability.model_dump())
    write(output / 'output_reliability.json',
          clean(result['proposalAuthority']))
    tsv(output / 'output_reliability.tsv',
        list(result['proposalAuthority'].values()))
    if spec.proposal.retrospective.enabled:
      diagnostics = retrospective(spec, data, result, names)
      result['retrospective'] = diagnostics
      write(output / 'search_efficiency.json', clean(diagnostics))
      tsv(output / 'retrospective_comparison.tsv', diagnostics['comparison'])
      tsv(output / 'retrospective_held_actions.tsv', diagnostics['heldActions'])
  transfer_rows = []
  for fold in result["transfer"]:
    for j, mm in enumerate(fold.get("metrics", [])):
      transfer_rows.append(
          dict(
              campaign=fold["campaign"],
              output=names[j],
              sharedTheta=len(fold.get("sharedTheta", [])),
              **mm,
          )
      )
  tsv(output / "campaign_transfer.tsv", transfer_rows)
  tsv(
      output / "quadratic_comparison.tsv",
      [
        r
        for r in leaderboard
        if r["family"] in ["mean", "linear"]
           or (r["family"] == "ridge" and r["degree"] in [1, 2])
      ],
  )
  write(output / "selection.json", clean(dict(zip(names, result["choice"]))))
  write(
      output / "validation.json",
      clean(
          dict(
              outer=result["outer"],
              rowIds=[r["rowId"] for r in rows],
              campaignTransfer=result["transfer"],
              observed=y,
              selectedPredictions=result["outerPrediction"],
              individualPredictions=result["individualPrediction"],
              interpretation="Nested inner selection; outer rows never used to select their model or ensemble. Campaign transfer allows repeated theta and is a separate diagnostic.",
          )
      ),
  )
  write(output / "model_registry.json", tournament.configs)
  proposals = None
  if spec.proposal.enabled:
    print("dense proposal search", flush=True)
    proposals = proposal_search(spec, root, data, result, names, output)
  summary = dict(
      schemaVersion=1,
      taskId=spec.id,
      execution=result.get('execution'),
      taskHash=digest(spec.model_dump()),
      rows=len(rows),
      uniqueTheta=len(set(groups)),
      forkObservations=len(data["forks"]),
      controls=len(data["controls"]),
      outputs=len(names),
      configurations=len(tournament.configs),
      families=sorted({c["family"] for c in tournament.configs}),
      failures=result["failures"],
      warnings=result["warnings"],
      finalSelectedModels={
        name: o["selected"] if o else None
        for name, o in zip(names, result["choice"])
      },
      proposalSearch=(
        None
        if proposals is None
        else {
          k: proposals[k]
          for k in ["searched", "eligible", "paretoSize", "stopReason"]
        }
      ),
      selectedProposals=0 if proposals is None else len(proposals["selected"]),
      elapsedSeconds=time.time() - tournament.started,
      python=platform.python_version(),
      packages={
        name: importlib.metadata.version(name)
        for name in [
          "numpy",
          "scipy",
          "scikit-learn",
          "pydantic",
          "joblib",
          "threadpoolctl",
          "xgboost",
          "catboost",
          "torch",
        ]
        if importlib.util.find_spec(
            name.replace("-", "_") if name != "scikit-learn" else "sklearn"
        )
           is not None
      },
      sourceHashes={
        str(p.relative_to(root)): sha(p)
        for p in (
            root / "python/pareto-weight-calibration/src/pareto_weight_calibration"
        ).rglob("*.py")
      },
      benchmarkExecuted=False,
  )
  write(output / "run.json", clean(summary))
  write(
      output / "lock.json",
      dict(
        files={str(p.relative_to(output)): sha(p) for p in output.rglob("*") if
               p.is_file()}),
  )
  return summary
