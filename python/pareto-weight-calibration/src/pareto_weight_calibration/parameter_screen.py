"""JSON-declared, one-term-at-a-time measurement preparation over arbitrary parameter vectors."""
from copy import deepcopy
from collections import defaultdict
from itertools import product
import json
import math
from pathlib import Path
import numpy as np
from pydantic import Field, model_validator
from .training_spec import SpecModel, digest
from .tuning_spec import Parameter
from .surrogate_spec import SurrogateTask
from .parameter_tuning import construct, pointer
from .historical_response import build, compatible_function
from .surrogate_tournament import write, tsv, sha


class Extension(SpecModel):
  id: str
  name: str
  path: str
  featureIndices: tuple[int, ...]
  outputPrefix: str


class BoundRecipe(SpecModel):
  maxUnclampedFactor: float = Field(default=1.35, gt=1)
  minimumUnclampedFactor: float = Field(default=1.10, gt=1)
  maxSingleBoundaryFraction: float = Field(default=0.5, gt=0, lt=1)
  shrinkFactor: float = Field(default=0.8, gt=0, lt=1)
  maxAttempts: int = Field(default=30, ge=1)


class ScreenTask(SpecModel):
  kind: str = 'parameter_screen'
  id: str
  root: str = '../../..'
  outputDirectory: str
  taskTemplate: str
  anchorArtifact: str
  anchorPointer: str = ''
  anchorSha256: str
  anchorId: str
  families: tuple[Extension, ...]
  bounds: BoundRecipe = BoundRecipe()
  levels: tuple[float, ...] = (-1., 0., 1.)
  fixtures: tuple[str, ...]
  blocks: int = Field(default=2, ge=2)
  supportPoints: tuple[tuple[float, ...], ...]
  representativePoints: tuple[tuple[float, ...], ...]
  bodyLabels: tuple[str, ...] = ('cheap_support', 'medium_support',
                                 'expensive_support')
  provenance: dict = Field(default_factory=dict)

  @model_validator(mode='after')
  def valid(self):
    if self.kind != 'parameter_screen' or not self.families:
      raise ValueError('parameter screen families required')
    if not 0 in self.levels or not min(self.levels) < 0 < max(
        self.levels) or any(abs(x) > 1 for x in self.levels):
      raise ValueError(
        'screen levels require negative, exact zero, positive within [-1,1]')
    if len(set(self.levels)) != len(self.levels):
      raise ValueError('duplicate levels')
    if len({f.path for f in self.families}) != len(self.families) or len(
        {f.id for f in self.families}) != len(self.families):
      raise ValueError('duplicate extension path or ID')
    return self


def surface(config, points):
  # Existing runtime adapter; points are raw contention/PHR/log1p(ns), never fixture work units.
  from .cache_timing import evaluate
  values = np.array(
      [evaluate(config, c, p, math.expm1(b)) for c, p, b in points])
  occupancy = {}
  for j, prefix in enumerate(('park', 'halfLife')):
    lo, hi = config[prefix + 'MinNanos'], config[prefix + 'MaxNanos']
    if np.any((values[:, j] < lo) | (values[:, j] > hi)):
      raise ValueError('invalid paired timing outputs')
    occupancy[prefix] = dict(lower=float(np.mean(values[:, j] == lo)),
                             upper=float(np.mean(values[:, j] == hi)),
                             min=int(values[:, j].min()),
                             max=int(values[:, j].max()))
  return values, occupancy


def derive_bound(base, parameters, extension, points, recipe):
  # Verify the declared expression against the existing CACHE basis before deriving bounds.
  basis = ((), (0,), (1,), (2,), (0, 1), (0, 2), (1, 2))
  parts = extension.path.strip('/').split('/')
  if (len(parts) != 2 or parts[0] != extension.outputPrefix + 'Coefficients'
      or not parts[1].isdigit() or int(parts[1]) >= len(basis)
      or extension.featureIndices != basis[int(parts[1])]):
    raise ValueError(
      'coefficient path does not match declared runtime basis expression')
  if any(len(p) != 3 or any(x < lo or x > hi for x, lo, hi in
                            zip(p, base['supportMin'], base['supportMax'])) for
         p in points):
    raise ValueError('surface point outside runtime support')
  normalized = (np.asarray(points) - base['means']) / base['scales']
  if not extension.featureIndices or any(
      i < 0 or i >= normalized.shape[1] for i in extension.featureIndices):
    raise ValueError('invalid normalized feature expression')
  feature = np.prod(normalized[:, extension.featureIndices], axis=1)
  maximum = float(np.max(np.abs(feature)))
  if maximum == 0: raise ValueError('unexcited support expression')
  magnitude = math.log(recipe.maxUnclampedFactor) / maximum
  offset = pointer(base, extension.path)
  attempts = []
  # Validate the allowed box, not only the development anchor.
  corners = list(product(*(p.bounds for p in parameters)))
  for attempt in range(recipe.maxAttempts):
    peak = 0.
    for values in corners:
      for sign in (-1, 1):
        config = construct(parameters, base, values)
        pointer(config, extension.path, offset + sign * magnitude, True)
        _, occ = surface(config, points)
        peak = max(peak,
                   *(v[k] for v in occ.values() for k in ('lower', 'upper')))
    attempts.append(dict(magnitude=magnitude, maxBoundaryFraction=peak))
    if peak <= recipe.maxSingleBoundaryFraction:
      if math.exp(magnitude * maximum) < recipe.minimumUnclampedFactor:
        raise ValueError('no meaningful nonsaturated extension range')
      return (-magnitude, magnitude), dict(featureMin=float(feature.min()),
                                           featureMax=float(feature.max()),
                                           maxUnclampedFactor=math.exp(
                                             magnitude * maximum),
                                           attempts=attempts,
                                           coefficientOffset=offset,
                                           boxCorners=len(corners),
                                           recipe=recipe.model_dump())
    magnitude *= recipe.shrinkFactor
  raise ValueError('no admissible extension bound')


def levels(parameters, anchor, extension_index, coordinates):
  """Controlled initial slice; full parameter box remains available to later JSON tuning."""
  p = parameters[extension_index]
  for index, level in enumerate(coordinates):
    values = [q.center for q in parameters]
    values[extension_index] = p.center if level == 0 else (p.bounds[
                                                             1] if level > 0 else -
    p.bounds[0]) * level
    yield index, level, values, construct(parameters, anchor, values)


def historical_audit(spec, root, output, template, anchor, family_parameters):
  from .run_archive import rows, read_file
  from fnmatch import fnmatch
  archive = root / template.dataset.archive
  raw = [r for r in rows(archive) if r['recordType'] == 'run']
  reference = json.loads(read_file(archive, template.dataset.referenceIdentity))
  identities = {}
  grouped = defaultdict(list)
  for r in raw:
    cfg = json.loads(r['calibrationConfigJson']);
    fun = cfg.get('cacheTimingFunction')
    grouped[(r['campaignId'], r['policyId'], digest(fun))].append((r, cfg, fun))
  baseline = defaultdict(list)
  for r in raw:
    cfg = json.loads(r['calibrationConfigJson'])
    if cfg.get('cacheTimingFunction') is None and all(
        cfg.get(k) == v for k, v in template.dataset.fixedControl.items()) and \
        r['executionsPerSecond']:
      baseline[r['campaignId'], r['workloadId']].append(
        float(r['executionsPerSecond']))
  audit = []
  for (campaign, policy, _), items in sorted(grouped.items()):
    r, cfg, fun = items[0]
    if campaign not in identities:
      try:
        identities[campaign] = json.loads(
          read_file(archive, 'experiments/' + campaign + '/identity.json'))
      except FileNotFoundError:
        identities[campaign] = None
    ident = identities[campaign]
    runtime_mismatches = []
    if ident is not None:
      for path, h in reference['sourceHashes'].items():
        if any(fnmatch(path, pat) for pat in
               template.dataset.runtimeSourcePatterns):
          if ident.get('sourceHashes', {}).get(path) != h and ident.get(
              'sourceHashes', {}).get(
              path) not in template.dataset.acceptedSourceHashes.get(path,
                                                                     ()): runtime_mismatches.append(
            path)
    by_workload = defaultdict(list)
    for obs, _, _ in items:
      if obs['executionsPerSecond']: by_workload[obs['workloadId']].append(
        float(obs['executionsPerSecond']))
    returns = {w: math.log(np.mean(v) / np.mean(baseline[campaign, w])) for w, v
               in by_workload.items() if baseline[campaign, w]}
    contextual_change = 100 * math.expm1(
      float(np.mean(list(returns.values())))) if returns and len(
      returns) == len(by_workload) else None
    active = {} if fun is None else {f'/{name}/{i}': value for name in
                                     ('parkCoefficients',
                                      'halfLifeCoefficients') for i, value in
                                     enumerate(fun[name]) if abs(value) > 1e-12}
    # The tolerance only describes active shapes. Exact compatibility below never uses it.
    for family, params in family_parameters.items():
      theta, reason = compatible_function(fun, anchor, params)
      throughput = all(
          '-Deuhedral.calibration.throughputOnly=true' in json.loads(
              x[0]['jmhConfigJson']).get('jvmArgs', []) for x in items)
      path = params[-1].path
      audit.append(dict(family=family, campaign=campaign, policy=policy,
                        coefficientPath=path,
                        coefficientValue=None if fun is None else pointer(fun,
                                                                          path),
                        materiallyActive=fun is not None and abs(
                          pointer(fun, path)) > 1e-12,
                        otherActiveCoefficients={k: v for k, v in active.items()
                                                 if k != path},
                        exactFamilyCompatible=theta is not None,
                        functionReason=reason,
                        runtimeCompatible=ident is not None and not runtime_mismatches,
                        runtimeMismatches=runtime_mismatches,
                        throughputOnlyCompatible=throughput,
                        contextualGeometricChangePercent=contextual_change,
                        contextualWorstWorkloadPercent=100 * math.expm1(
                          min(returns.values())) if returns else None,
                        forks=len(items),
                        workloads=sorted({x[0]['workloadId'] for x in items}),
                        cpus=sorted(
                            {json.dumps(x[1]['cpuSet']) for x in items}),
                        use='candidate_for_exact_adapter' if theta is not None and throughput and ident is not None and not runtime_mismatches else 'context_only'))
  write(output / 'historical_fifth_term_audit.json', audit);
  tsv(output / 'historical_fifth_term_audit.tsv', audit)
  return audit


def run_task(path, output_override=None, dry_run=False):
  spec = ScreenTask.model_validate_json(path.read_text());
  root = (path.resolve().parent / spec.root).resolve()
  if dry_run: return dict(taskId=spec.id, families=len(spec.families),
                          newPoints=len(spec.families) * (len(spec.levels) - 1),
                          measurementOnly=True)
  output = Path(
    output_override).resolve() if output_override else root / spec.outputDirectory
  if output.exists(): raise ValueError('screen output must be a new revision')
  template = SurrogateTask.model_validate_json(
    (root / spec.taskTemplate).read_text())
  source = root / spec.anchorArtifact
  if sha(source) != spec.anchorSha256: raise ValueError(
    'anchor artifact identity changed')
  anchor = json.loads(source.read_text());
  anchor = pointer(anchor, spec.anchorPointer) if spec.anchorPointer else anchor
  if any(f.path in {p.path for p in template.parameters} for f in
         spec.families): raise ValueError('extension already active')
  output.mkdir(parents=True)
  write(output / 'task.json', spec.model_dump());
  write(output / 'anchor.json', anchor)
  base_parameters = [
    p.model_copy(update={'center': pointer(anchor, p.path) - p.offset}) for p in
    template.parameters]
  # Validate new centers against the unchanged box and strict signs.
  base_parameters = [Parameter.model_validate(p.model_dump()) for p in
                     base_parameters]
  raw_template = template.model_dump(mode="json");
  raw_template['reuseFit'] = None;
  raw_template['proposal']['knownRegionCoverage']['enabled'] = False
  family_parameters = {};
  selected = [];
  catalog = [];
  bounds_report = {};
  zero_functions = {};
  tasks = {}
  for family in spec.families:
    bounds, info = derive_bound(anchor, base_parameters, family,
                                spec.supportPoints, spec.bounds)
    added = Parameter(name=family.name, path=family.path, center=0.,
                      offset=pointer(anchor, family.path), bounds=bounds)
    params = (*base_parameters, added);
    family_parameters[family.id] = params;
    bounds_report[family.id] = info
    task = deepcopy(raw_template);
    task.update(id=spec.id + '-' + family.id,
                parameters=[p.model_dump() for p in params],
                requireVaryingParameters=[added.name], root=str(root),
                outputDirectory='experiments/' + spec.id + '-' + family.id + '-fit')
    task['dataset']['centerArtifact'] = str(output / 'anchor.json')
    task['proposal'].update(round=1, parentDataset=str(
      output / 'historical' / family.id / 'dataset.json'))
    task['proposal']['benchmark'][
      'runDirectory'] = 'experiments/' + spec.id + '-' + family.id + '-proposals'
    task['proposal']['benchmark']['controls'].append(
      dict(id=spec.anchorId, function=anchor))
    # A 4D ablation sees the SAME 5D-grouped evidence/folds, omitting the new model input only.
    ablation = []
    for model in task['models']:
      reduced = deepcopy(model);
      reduced['id'] = 'without_extension-' + model['id'];
      reduced['inputIndices'] = list(range(len(base_parameters)));
      ablation.append(reduced)
    task['models'] += ablation
    SurrogateTask.model_validate(task);
    tasks[family.id] = task
    write(output / 'families' / family.id / 'tournament.json', task)
    for index, level, values, config in levels(params, anchor, len(params) - 1,
                                               spec.levels):
      pid = spec.anchorId if level == 0 else spec.id + '-' + family.id + (
        '-negative' if level < 0 else '-positive') + f'-{index}'
      timing, occupancy = surface(config, spec.supportPoints)
      if max(v[k] for v in occupancy.values() for k in ('lower',
                                                        'upper')) > spec.bounds.maxSingleBoundaryFraction: raise ValueError(
        'generated clamp-dominated surface')
      if level == 0:
        assert config == anchor;
        zero_functions[family.id] = pid
      else:
        selected.append(
          dict(id=pid, function=config, theta=values, family=family.id,
               level=level))
      write(output / 'families' / family.id / 'policies' / f'{pid}.json',
            config)
      rep, _ = surface(config, spec.representativePoints)
      for point, pair in zip(spec.representativePoints, rep):
        catalog.append(dict(policyId=pid, family=family.id, level=level,
                            parameters=json.dumps(values),
                            coefficientPath=added.path,
                            rawCoefficient=pointer(config, added.path),
                            delta=values[-1], contention=point[0], phr=point[1],
                            logBodyNs=point[2],
                            normalizedBody=(point[2] - anchor['means'][2]) /
                                           anchor['scales'][2],
                            parkNanos=int(pair[0]), halfLifeNanos=int(pair[1]),
                            clampOccupancy=json.dumps(occupancy)))
  write(output / 'bounds.json', bounds_report);
  tsv(output / 'surface_catalog.tsv', catalog)
  audit = historical_audit(spec, root, output, template, anchor,
                           family_parameters)
  comparison = []
  for family, task in tasks.items():
    data = build(SurrogateTask.model_validate(task), root)
    write(output / 'historical' / family / 'dataset.json', data)
    tsv(output / 'historical' / family / 'compatibility.tsv', data['audit'])
    vals = sorted({r['theta'][-1] for r in data['rows']})
    comparison.append(dict(family=family, compatibleTheta=len(
        {r['thetaId'] for r in data['rows']}), forks=len(data['forks']),
                           fifthValues=vals, identifiable=len(vals) >= 3,
                           heldThetaQuality='not fitted: no independent fifth-term variation' if len(
                             vals) < 3 else 'fit required', searchRegret=None,
                           searchReduction=None,
                           improvementOver4D=None,
                           decision='measure both signs; historical context does not rule out extension'))
  write(output / 'dimension_comparison.json', comparison);
  tsv(output / 'dimension_comparison.tsv', comparison)
  measured_spec = deepcopy(raw_template);
  measured_spec.update(id=spec.id,
                       parameters=[p.model_dump() for p in base_parameters])
  measured_spec['dataset']['fixtures'] = [f for f in
                                          measured_spec['dataset']['fixtures']
                                          if f['workloadId'] in spec.fixtures]
  if {f['workloadId'] for f in measured_spec['dataset']['fixtures']} != set(
      spec.fixtures): raise ValueError('unknown screen fixture')
  measured_spec['proposal']['benchmark'].update(blocks=spec.blocks,
                                                runDirectory='experiments/' + spec.id)
  measured_spec['proposal']['benchmark']['controls'].append(
    dict(id=spec.anchorId, function=anchor))
  from .reliable_proposals import prepare_benchmarks
  prep = prepare_benchmarks(SurrogateTask.model_validate(measured_spec), root,
                            output, selected,
                            selection_note="This is a controlled one-term measurement screen. See the parent candidate_manifest.json, bounds.json and dimension_comparison.json. No surrogate ranked these signed levels; historical evidence does not identify the added coefficient.\n\n")
  write(output / 'candidate_manifest.json',
        dict(id=spec.id, anchorId=spec.anchorId, anchor=anchor,
             baseParameters=[p.model_dump() for p in base_parameters],
             families={k: [p.model_dump() for p in v] for k, v in
                       family_parameters.items()}, zeroAliases=zero_functions,
             policies=selected, benchmark=prep, execution=False))
  write(output / 'analysis_config.json',
        dict(references=['POLICY_OFF', spec.anchorId, 'live-25-center'],
             primary='scarce body breadth across topologies',
             guardrail='measured plentiful harm; report individually',
             replicationUnit='JVM fork', keepAllWindows=True,
             modelComparisons='full 5D and inputIndices ablation on identical rows and held full-theta folds',
             missingOutputs='unmeasured plentiful fixtures and incomplete aggregates stay null',
             sequence=['collect existing confirmation harness',
                       'archive new raw and collected outcomes',
                       'run selected family tournament JSON',
                       'review measured tradeoffs; retain at most two separate extensions',
                       'generate bounded follow-up; no dynamics yet']))
  task_links = '\n'.join(
      f"- `{family}`: `families/{family}/tournament.json`" for family in tasks)
  (output / 'HANDOFF.md').write_text(
      '# One-term extension screening handoff\n\n'
      f'Prepared only: {prep["arms"]} arms x {prep["workloads"]} workloads x {prep["blocks"]} independent blocks = {prep["jvmCount"]} JVM forks.\n\n'
      f'Controls: POLICY_OFF (actual bypass), exact original live-25-center, exact {spec.anchorId}. '
      'Each nonzero arm changes only its declared new coefficient. The four existing parameters '
      'stay at the measured anchor for this identifying slice; all remain tunable in the family JSON tasks. '
      'Signed levels and bounds are generated automatically. The zero level is shared across families '
      'and exactly reproduces the anchor, including every inactive floating-point residual.\n\n'
      'See `benchmark/HANDOFF.md` for the existing check/run/collect commands; run only after review. '
      'No benchmark is launched by this preparation or the model runner. Retain every fork and window.\n\n'
      'Compare each candidate to OFF AND the matched anchor, separately by topology, scarcity/body '
      'and plentiful fixture. Do not pick on a global average. Missing plentiful fixtures remain '
      'unmeasured; this small screen cannot establish a production guardrail pass or dynamic behavior.\n\n'
      'After collection, consolidate the new campaign into the declared archive before fitting. '
      'The family tasks require three measured values of the new coordinate and refuse a fit while '
      'history keeps it fixed. Full models and models omitting the added input use identical rows '
      'and held full-theta groups, including repeated parameter vectors across campaigns. '
      'Each task includes the broad offline registry, independent output selection, ensembles and '
      'reliability-aware Pareto proposals; no weights are chosen by hand.\n\n'
      + task_links + '\n\n'
                     'Run a family using the generic entrypoint from the repository root, replacing FAMILY: \n\n'
                     f'`python -m pareto_weight_calibration.training_runner --task {output}/families/FAMILY/tournament.json --output-dir /tmp/new-family-fit`\n\n'
                     'Fit metrics are not production evidence. If one extension earns a useful measured tradeoff, '
                     'advance its five-parameter search; if several do, retain at most two separate families. '
                     'If none does, return to the four-parameter family. Do not combine new terms or run dynamics yet. '
                     'Any subsequent proposal batch requires review before JMH execution.\n')
  write(output / 'provenance.json', dict(anchorSha256=sha(source),
                                         templateSha256=sha(
                                           root / spec.taskTemplate),
                                         archiveSha256=sha(
                                           root / template.dataset.archive),
                                         sourceHashes={
                                           str(p.relative_to(root)): sha(p) for
                                           p in (
                                                 root / 'python/pareto-weight-calibration/src/pareto_weight_calibration').rglob(
                                             '*.py')}, noJmhExecuted=True,
                                         noFifthTermFitWithoutVariation=True))
  write(output / 'lock.json', dict(
    files={str(p.relative_to(output)): sha(p) for p in output.rglob('*') if
           p.is_file()}))
  return dict(taskId=spec.id, benchmark=prep, comparison=comparison)
