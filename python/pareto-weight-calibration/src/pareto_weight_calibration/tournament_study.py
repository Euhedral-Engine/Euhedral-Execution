"""JSON orchestration of independent parameter families, model ablations and matched proposals."""
from copy import deepcopy
from pathlib import Path
import json
import math
import numpy as np
from pydantic import Field
from .training_spec import SpecModel
from .surrogate_spec import SurrogateTask, BenchmarkPreparation
from .historical_response import sha


class StudyFamily(SpecModel):
  name: str
  fitTask: str
  ablationTask: str | None = None
  searchTask: str


class StudySelection(SpecModel):
  count: int = Field(default=4, ge=1, le=8)
  primaryTargets: tuple[str, ...]
  topologyTargets: tuple[str, ...]
  plentifulTargets: tuple[str, ...]
  capPercent: float = 25
  minPositiveMarginalOutputs: int = 1
  minNormalizedDistance: float = .15
  preserveBasins: bool = True
  minimumRoles: dict[str, int] = Field(default_factory=dict)


class TournamentStudy(SpecModel):
  kind: str = 'parameter_study'
  id: str
  root: str = '.'
  outputDirectory: str
  families: tuple[StudyFamily, ...]
  selection: StudySelection
  benchmark: BenchmarkPreparation
  retrospectiveStrategies: tuple[str, ...] = ('selected', 'single', 'mean',
                                              'validation_weighted')


def verified(path):
  content = json.loads((path / 'lock.json').read_text())
  for name, h in content['files'].items():
    file = (path / name).resolve()
    if not file.is_relative_to(path.resolve()) or sha(file) != h:
      raise ValueError('study input lock mismatch: ' + str(file))


def compare_models(full, ablated, output, family):
  from .surrogate_tournament import read, tsv
  import csv
  a = read(full / 'dataset.json');
  b = read(ablated / 'dataset.json')
  if a['rows'] != b['rows']: raise ValueError(
    'ablation must use identical observations')
  av = read(full / 'validation.json');
  bv = read(ablated / 'validation.json')
  if len(av['outer']) != len(bv['outer']): raise ValueError(
    'ablation fold count differs')
  for x, y in zip(av['outer'], bv['outer']):
    if x['heldRows'] != y['heldRows'] or x['trainRows'] != y['trainRows']:
      raise ValueError('ablation validation membership differs')
  tables = []
  for d in [full, ablated]:
    with (d / 'held_theta_metrics.tsv').open() as f: tables.append(
        {r['output']: r for r in csv.DictReader(f, delimiter='\t')})
  rows = []
  for name, x in tables[0].items():
    y = tables[1][name];
    row = dict(family=family, output=name)
    for metric in ['rmse', 'mae', 'regret', 'spearman', 'topKRecall', 'rows',
                   'thetas']:
      row['full_' + metric] = x[metric];
      row['ablation_' + metric] = y[metric]
    rows.append(row)
  tsv(output / (family + '-ablation.tsv'), rows)
  return rows


def retrospective_variants(spec, directory, output,
    strategies=('selected', 'single', 'mean', 'validation_weighted')):
  """Use saved outer-held predictions with inner-only model/ensemble choices; never refit."""
  from .surrogate_tournament import read, write, tsv, clean
  from .proposal_reliability import retrospective
  data = read(directory / 'dataset.json');
  v = read(directory / 'validation.json')
  names = [s + ':' + r.name for s in spec.systems for r in spec.responses]
  individual = {k: np.array(a, float) for k, a in
                v['individualPredictions'].items()}
  reports = []
  for strategy in strategies:
    if strategy not in ['selected', 'single', 'mean', 'validation_weighted']:
      raise ValueError('unknown retrospective strategy')
    prediction = np.array(v['selectedPredictions'], float)
    if strategy != 'selected':
      for fold in v['outer']:
        held = fold['heldRows']
        for j, choice in enumerate(fold['selection']):
          if strategy == 'single':
            members = [choice['ranked'][0]['model']];
            weights = [1.]
          else:
            option = next(
                a for a in choice['alternatives'] if a['kind'] == strategy)
            members, weights = option['members'], option['weights']
          prediction[held, j] = sum(
              w * individual[m][held, j] for m, w in zip(members, weights))
    result = dict(outer=v['outer'], outerPrediction=prediction)
    report = retrospective(spec, data, result, names)
    write(output / f'retrospective-{strategy}.json', clean(report))
    for row in report['comparison']:
      reports.append(dict(predictionStrategy=strategy, **row))
  tsv(output / 'retrospective-strategies.tsv', reports)
  return reports


def select_candidates(families, policy):
  from .reliable_proposals import nondominated
  candidates = [];
  scores = []
  for name, manifest in families:
    for p in manifest['selected']:
      prediction = p.get('searchDirection', p['predictions']);
      marg = p.get('counterfactual', {}).get('marginalPredictions',
                                             {k: 0. for k in prediction})
      primary = [t for t in policy.primaryTargets if
                 prediction.get(t) is not None]
      topology = [t for t in policy.topologyTargets if
                  prediction.get(t) is not None]
      sources = p.get('directionSources', {})
      active = sum(marg[sources.get(t) or t] > 0 for t in primary)
      if 'counterfactual' in p and active < policy.minPositiveMarginalOutputs: continue
      values = np.array([prediction[t] for t in primary])
      guard = [p['predictions'][t] for t in policy.plentifulTargets
               if p.get('contribution', {}).get(t) in ['soft_penalty',
                                                       'hard_constraint']]
      disagreement = max(
          (p.get('modelDisagreement', {}).get(sources.get(t) or t, 0.) for t in
           primary), default=0.)
      score = [min((prediction[t] for t in topology), default=0.),
               np.mean(np.minimum(values, math.log1p(policy.capPercent / 100))),
               min(guard, default=0.),
               np.mean([marg[sources.get(t) or t] for t in primary]),
               -disagreement]
      candidates.append(dict(p, family=name, selectionObjectives=score,
                             positiveMarginalOutputs=active,
                             qualifiedPrimaryTargets=primary,
                             qualifiedTopologyTargets=topology,
                             unresolvedPrimaryTargets=[t for t in
                                                       policy.primaryTargets if
                                                       t not in primary],
                             unresolvedTopologyTargets=[t for t in
                                                        policy.topologyTargets
                                                        if t not in topology]))
      scores.append(score)
  front = nondominated(scores) if scores else []
  # Family search already proved each input candidate belongs to its eligible Pareto set.
  # A second cross-family ranking must not erase a whole basin or protected exploration role.
  from itertools import combinations
  size = min(policy.count, len(candidates));
  best = None;
  chosen = []
  if math.comb(len(candidates), size) > 1000000: raise ValueError(
    'study candidate pool too large for bounded combination selection')
  for ids in combinations(range(len(candidates)), size):
    items = [candidates[i] for i in ids]
    if any(a['family'] == b['family'] and np.linalg.norm(
        np.array(a['normalizedCoordinates']) - b[
          'normalizedCoordinates']) < policy.minNormalizedDistance for a, b in
           combinations(items, 2)): continue
    if any(
        sum(p.get('role') == role for p in items) < minimum for role, minimum in
        policy.minimumRoles.items()): continue
    coverage = len({(p['family'], p.get('basin', 0)) for p in
                    items}) if policy.preserveBasins else 0
    # Lexicographic coverage, then distinct objective extrema and consensus. No global throughput scalar.
    key = (len({p['family'] for p in items}), coverage,
           *np.max(np.array([scores[i] for i in ids]), axis=0).tolist(),
           sum(i in front for i in ids), tuple(-i for i in ids))
    if best is None or key > best: best = key;chosen = items
  if candidates and not chosen: raise ValueError(
    'study diversity/role constraints cannot fit the declared proposal budget')
  return chosen, dict(pool=len(candidates), nonDominated=len(front),
                      selected=len(chosen),
                      representedBasins=[dict(family=f, basin=b) for f, b in
                                         sorted(
                                             {(p['family'], p.get('basin', 0))
                                              for p in chosen})],
                      minimumRoles=policy.minimumRoles,
                      objectiveNames=['minimum_qualified_topology_scarcity',
                                      'capped_qualified_scarcity',
                                      'worst_qualified_plentiful',
                                      'mean_qualified_predicted_fifth_marginal',
                                      'qualified_consensus'],
                      interpretation='Predicted tradeoffs over qualified outputs only; coverage differs by family and is not evidence of broad all-topology safety or cross-family superiority. All losses and unresolved outputs remain visible')


def search_diagnostics(directory, output):
  """Compare consensus representatives on saved exact prefix frontiers without rescoring."""
  from .surrogate_tournament import read, write
  from scipy.spatial.distance import cdist
  metadata = read(directory / 'dense_manifest.json')
  authority = read(directory / 'output_reliability.json')
  columns = [j for j, n in enumerate(metadata['outputs']) if
             authority[n]['directionUseful']]
  unit = np.load(directory / 'dense_unit.npy', mmap_mode='r')
  disagreement = np.load(directory / 'dense_disagreement.npy', mmap_mode='r')
  convergence = read(directory / 'search_convergence.json');
  rows = []
  for checkpoint in convergence['checkpoints']:
    ids = np.load(directory / f'pareto_indices_2p{checkpoint["power"]}.npy')
    centers = np.array(checkpoint['basinCenters'])
    if not len(ids): continue
    spread = np.max(disagreement[ids][:, columns],
                    axis=1) if columns else np.zeros(len(ids))
    labels = cdist(unit[ids], centers).argmin(axis=1)
    representatives = []
    for basin in range(len(centers)):
      members = np.flatnonzero(labels == basin)
      if not len(members): continue
      i = members[np.argmin(spread[members])]
      representatives.append(
        dict(basin=basin, sampleIndex=int(ids[i]), unit=unit[ids[i]].tolist(),
             maxUsefulDisagreement=float(spread[i])))
    row = dict(power=checkpoint['power'], representatives=representatives)
    if rows:
      distances = cdist([r['unit'] for r in representatives],
                        [r['unit'] for r in rows[-1]['representatives']])
      row['consensusShift'] = float(
        max(distances.min(axis=0).max(), distances.min(axis=1).max()))
    rows.append(row)
  write(output / 'consensus_convergence.json', dict(checkpoints=rows,
                                                    interpretation='Consensus locations are diagnostic representatives, not confidence bounds or separately measured optima'))


def surface_catalog(spec, proposals, output):
  """Describe selected actuator surfaces through the declared support adapter."""
  from .surrogate_tournament import write, tsv
  support = spec.proposal.support
  if not support: return
  if support.get('adapter') != 'cache_timing': raise ValueError(
    'unregistered surface catalog adapter')
  from .cache_timing import evaluate
  rows = [];
  summary = []
  for proposal in proposals:
    config = proposal['function'];
    values = []
    for c, p, b in support['points']:
      park, h = evaluate(config, c, p, math.expm1(b));
      values.append((park, h))
      rows.append(dict(policyId=proposal['id'], contention=c, phr=p, logBody=b,
                       parkNanos=park, halfLifeNanos=h))
    values = np.array(values)
    item = dict(policyId=proposal['id'], role=proposal.get('role'),
                supportPoints=len(values))
    for j, prefix in enumerate(['park', 'halfLife']):
      lo, hi = config[prefix + 'MinNanos'], config[prefix + 'MaxNanos']
      if np.any((values[:, j] < lo) | (values[:, j] > hi)): raise ValueError(
        'surface outside runtime bounds')
      item[prefix + 'Min'] = int(values[:, j].min());
      item[prefix + 'Max'] = int(values[:, j].max())
      item[prefix + 'LowerClampFraction'] = float(np.mean(values[:, j] == lo))
      item[prefix + 'UpperClampFraction'] = float(np.mean(values[:, j] == hi))
    summary.append(item)
  tsv(output / 'surface_catalog.tsv', rows);
  write(output / 'surface_summary.json', summary)


def evidence_summary(datasets, output):
  """Count shared forks once while describing each parameterization independently."""
  from .surrogate_tournament import write
  from sklearn.preprocessing import PolynomialFeatures
  live = {};
  controls = {};
  families = []
  for name, data in datasets:
    theta = np.unique([r['theta'] for r in data['rows']], axis=0)
    families.append(
      dict(family=name, theta=len(theta), dimensions=theta.shape[1],
           liveForks=len(data['forks']), controlForks=len(data['controls']),
           responseBundles=len(data['rows']),
           quadraticRank=int(
             np.linalg.matrix_rank(PolynomialFeatures(2).fit_transform(theta))),
           quadraticColumns=int(
               PolynomialFeatures(2).fit_transform(theta).shape[1])))
    for record in data['forks']: live[record['rowId']] = record
    for record in data['controls']: controls[record['rowId']] = record
  campaigns = sorted({r['campaignId'] for r in live.values()})
  report = dict(families=families, uniqueLiveForks=len(live),
                uniqueControlForks=len(controls),
                campaigns=campaigns,
                sharedLiveForks=sum(len(d['forks']) for _, d in datasets) - len(
                  live),
                interpretation='Shared historical forks are counted once in the union; each family is a separate view, not new replication')
  write(output / 'evidence_summary.json', report)
  return report


def run_task(path, output_override=None, dry_run=False,
    execution_overrides=None):
  from .surrogate_tournament import run_task as tournament, read, write, tsv, \
    clean
  study = TournamentStudy.model_validate_json(path.read_text())
  root = (path.resolve().parent / study.root).resolve()
  output = Path(
    output_override) if output_override else root / study.outputDirectory
  if dry_run: return dict(id=study.id,
                          families=[f.name for f in study.families],
                          jsonOnly=True)
  output.mkdir(parents=True, exist_ok=True)
  if (output / 'lock.json').exists(): raise ValueError(
    'completed study output exists; use a new revision')
  families = [];
  datasets = [];
  audit = [];
  comparisons = [];
  inputs = {};
  first = None;
  models = [];
  transfer = []
  for family in study.families:
    destinations = []
    for field in ['fitTask', 'ablationTask', 'searchTask']:
      if getattr(family, field) is None:
        destinations.append(None);
        continue
      task_path = root / getattr(family, field);
      task = SurrogateTask.model_validate(read(task_path))
      task_root = (task_path.parent / task.root).resolve();
      dest = task_root / task.outputDirectory
      inputs[str(task_path.relative_to(root))] = sha(task_path)
      if dest.exists():
        verified(dest)
        frozen = SurrogateTask.model_validate(read(dest / 'task.json'))
        expected = task.model_dump()
        if field == 'searchTask' and (output / 'resolved_tasks' / (
            family.name + '-search.json')).exists():
          expected['root'] = str(task_root)
          expected['reuseFit'] = dict(directory=str(destinations[0]),
                                      lockSha256=sha(
                                        destinations[0] / 'lock.json'),
                                      allowProposalChanges=True)
        if frozen.model_dump() != SurrogateTask.model_validate(
            expected).model_dump(): raise ValueError(
          'study task differs from completed task')
      else:
        if field == 'searchTask':
          # Fresh deterministic fits have new run/lock identities (including wall time).
          # Resolve that identity from this study's verified fit instead of editing JSON by hand.
          resolved = task.model_dump();
          resolved['root'] = str(task_root)
          resolved['reuseFit'] = dict(directory=str(destinations[0]),
                                      lockSha256=sha(
                                        destinations[0] / 'lock.json'),
                                      allowProposalChanges=True)
          effective = output / 'resolved_tasks' / (family.name + '-search.json')
          write(effective, resolved);
          tournament(effective, execution_overrides=execution_overrides)
        else:
          tournament(task_path, execution_overrides=execution_overrides)
      destinations.append(dest)
    full, ablated, search = destinations
    diagnostics = output / (family.name + '-search-diagnostics');
    diagnostics.mkdir(exist_ok=True)
    search_diagnostics(search, diagnostics)
    if ablated: comparisons += compare_models(full, ablated, output,
                                              family.name)
    spec = SurrogateTask.model_validate(read(search / 'task.json'))
    for label, directory in [('full', full), ('4d', ablated)]:
      if directory is None: continue
      rd = output / (family.name + '-' + label + '-retrospective');
      rd.mkdir(exist_ok=True)
      retrospective_variants(spec, directory, rd, study.retrospectiveStrategies)
    if first is None: first = spec
    data = read(full / 'dataset.json')
    datasets.append((family.name, data))
    for label, directory in [('full', full), ('4d', ablated)]:
      if directory is None: continue
      run = read(directory / 'run.json');
      validation = read(directory / 'validation.json')
      names = [s + ':' + r.name for s in spec.systems for r in spec.responses]
      choices = run['finalSelectedModels']
      for name in names: models.append(
        dict(family=family.name, representation=label, output=name,
             selection=choices[name]))
      for campaign in validation['campaignTransfer']:
        for name, metric in zip(names, campaign.get('metrics', [])):
          transfer.append(dict(family=family.name, representation=label,
                               campaign=campaign['campaign'],
                               status=campaign['status'], output=name,
                               **metric))
    for row in data['audit']: audit.append(dict(family=family.name, **row))
    manifest = read(search / 'proposals.json')
    from .proposal_reliability import policy_view
    names = [s + ':' + r.name for s in spec.systems for r in spec.responses]
    predictions = np.array(
        [[p['predictions'][n] for n in names] for p in manifest['selected']])
    disagreement = np.array([[p['modelDisagreement'][n] for n in names] for p in
                             manifest['selected']])
    if len(predictions):
      view = policy_view(spec, predictions, disagreement, names,
                         read(search / 'output_reliability.json'))
      for i, p in enumerate(manifest['selected']):
        p['searchDirection'] = {
          n: float(view['direction'][i, j]) if np.isfinite(
              view['direction'][i, j]) else None for j, n in enumerate(names)}
        p['directionSources'] = view['directionSources']
    families.append((family.name, manifest))
  tsv(output / 'shared_inclusion_audit.tsv', audit);
  tsv(output / 'matched_ablation.tsv', comparisons)
  evidence_summary(datasets, output)
  tsv(output / 'selected_models.tsv', models);
  tsv(output / 'campaign_transfer.tsv', transfer)
  selected, selection = select_candidates(families, study.selection)
  if not selected: raise ValueError(
    'no useful predicted marginal region; no benchmark manifest generated')
  arms = []
  for p in selected:
    arms.append(p)
    if 'counterfactual' in p:
      q = p['counterfactual']
      arms.append(dict(id=q['id'], theta=q['theta'], function=q['function'],
                       pairedTo=p['id'], role='same_base_counterfactual'))
  deduplicated = [];
  functions = {}
  for p in arms:
    key = json.dumps(p['function'], sort_keys=True)
    if key not in functions:
      functions[key] = p['id'];deduplicated.append(p)
    else:
      selection.setdefault('sharedCounterparts', []).append(
        dict(id=p['id'], identicalTo=functions[key]))
      if 'pairedTo' in p:
        original = next(q for q in deduplicated if q['id'] == functions[key])
        original.setdefault('alsoPairedTo', []).append(p['pairedTo'])
  payload = first.model_dump();
  payload['id'] = study.id;
  payload['proposal']['benchmark'] = study.benchmark.model_dump()
  combined = SurrogateTask.model_validate(payload)
  from .reliable_proposals import prepare_benchmarks
  surface_catalog(combined, deduplicated, output)
  benchmark = prepare_benchmarks(combined, root, output, deduplicated,
                                 selection_note='Each nonzero proposal has an automatically constructed same-base zero-fifth counterpart. Compare matched fifth-term marginal returns as well as OFF and the 4D reference. No predicted marginal is benchmark evidence. See selected_theta.json.\n\n')
  write(output / 'selected_theta.json', clean(
    dict(selection=selection, selected=selected, benchmark=benchmark)))
  write(output / 'study.json', study.model_dump());
  write(output / 'inputs.json', inputs)
  import importlib.metadata, platform
  packages = {}
  for name in ['numpy', 'scipy', 'scikit-learn', 'moocore', 'xgboost',
               'catboost']:
    try:
      packages[name] = importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
      pass
  write(output / 'environment.json', dict(python=platform.python_version(),
                                          packages=packages,
                                          sourceHashes={
                                            str(p.relative_to(root)): sha(p) for
                                            p in
                                            (
                                                  root / 'python/pareto-weight-calibration/src/pareto_weight_calibration').rglob(
                                              '*.py')},
                                          benchmarkExecuted=False))
  write(output / 'lock.json', dict(
    files={str(p.relative_to(output)): sha(p) for p in output.rglob('*') if
           p.is_file()}))
  return dict(id=study.id, selectedTheta=len(selected), benchmark=benchmark)
