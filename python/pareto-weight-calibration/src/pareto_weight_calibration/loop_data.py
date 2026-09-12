"""Incremental fork store and one-time history import, without historical replay locks."""
from collections import defaultdict, Counter
from pathlib import Path
import base64, json, math, sqlite3, zlib
import numpy as np
from .historical_response import compatible_function, theta_id
from .parameter_tuning import pointer
from .training_spec import digest
from .cache_timing_confirmation import measurement_windows


def policy_id(function):
  def numeric(value):
    if isinstance(value, dict): return {k: numeric(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)): return [numeric(v) for v in value]
    if isinstance(value, float) and value.is_integer(): return int(value)
    return value

  return 'POLICY_OFF' if function is None else 'policy-' + digest(
    numeric(function))[:20]


class ForkStore:
  def __init__(self, path):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    self.db = sqlite3.connect(path)
    self.db.execute(
      'CREATE TABLE IF NOT EXISTS forks (id TEXT PRIMARY KEY, record TEXT NOT NULL)')

  def append(self, records):
    with self.db:
      for r in records:
        require_static(r)
        text = json.dumps(r, sort_keys=True, allow_nan=False)
        old = self.db.execute('SELECT record FROM forks WHERE id=?',
                              (r['rowId'],)).fetchone()
        if old and old[0] != text: raise ValueError(
          'conflicting fork identity: ' + r['rowId'])
        self.db.execute('INSERT OR IGNORE INTO forks VALUES (?,?)',
                        (r['rowId'], text))

  def rows(self):
    rows = [json.loads(x[0]) for x in
            self.db.execute('SELECT record FROM forks ORDER BY id')]
    for row in rows: require_static(row)
    return rows

  def close(self):
    self.db.close()


def require_static(row):
  """Dynamic trajectories have a separate artifact contract, never static responses."""
  config = row.get('config', {})
  if (row.get('recordType', 'static_fork') != 'static_fork'
      or row.get('workloadClass') == 'DYNAMIC'
      or row.get('phases') or row.get('dynamicSchedule')
      or config.get('dynamicSchedule')
      or config.get('participationDynamicScenario', 'NONE') != 'NONE'
      or any('euhedral.calibration.dynamicSchedule=' in a or
             ('euhedral.calibration.participationDynamicScenario=' in a and not a.endswith('=NONE'))
             for a in row.get('jvmArgs', []))):
    raise ValueError('dynamic observations cannot enter the static fork store')


def compatible_families(spec, function):
  result = {}
  for f in spec.families:
    theta, reason = compatible_function(function, f.template, f.parameters)
    if reason is None: result[f.id] = theta
  return result


def ingest(spec, root, store):
  """Read each archive twice once: index relevant runs, then only decode their raw logs."""
  from .run_archive import rows
  audit = Counter();
  from .loop_references import import_stores
  audit.update(import_stores(spec, root, store))
  total = []
  expected = spec.benchmark.harness['trials'][0]['calibrationConfig']
  for source in spec.historicalRows:
    values = json.loads((root / source).read_text())
    for r in values:
      if not np.isfinite(r['rawThroughput']) or r[
        'rawThroughput'] <= 0: raise ValueError('invalid historical throughput')
    store.append(values);
    audit['explicit canonical rows'] += len(values)
  for source in spec.historicalArchives:
    path = root / source;
    pending = []
    for r in rows(path):
      if r['recordType'] != 'run': continue
      cfg = json.loads(r['calibrationConfigJson']);
      trial = json.loads(r['jmhConfigJson'])
      try:
        require_static(dict(config=cfg, jvmArgs=trial.get('jvmArgs', []),
                            workloadClass=(trial.get('labels') or {}).get('workloadClass')))
      except ValueError:
        audit[r['campaignId'] + ' / dynamic validation excluded'] += 1
        continue
      try:
        fn = pointer(cfg, spec.benchmark.functionPath)
      except KeyError:
        fn = None
      families = compatible_families(spec, fn)
      reason = None
      if fn is not None and not families:
        reason = 'incompatible parameter family'
      elif any(cfg.get(k) != v for k, v in expected.items() if
               k not in spec.benchmark.historicalVariableKeys):
        reason = 'runtime/participation/observer configuration differs'
      elif not set(spec.benchmark.requiredJvmOptions) <= set(
          trial.get('jvmArgs', [])):
        reason = 'throughput-only JVM options differ'
      elif any(
          trial.get(k) != spec.benchmark.harness['trials'][0].get(k) for k in
          ['forks', 'warmups', 'iterations', 'warmupTime', 'measurementTime']):
        reason = 'measurement schedule differs'
      fixture = next((f for f in spec.fixtures if all(
          cfg.get(k) == f.get(v) for k, v in
          spec.benchmark.fixtureBindings.items())), None)
      if not fixture: reason = reason or 'outside configured workload panel'
      if reason: audit[r['campaignId'] + ' / ' + reason] += 1;continue
      pending.append((r, cfg, trial, fn, families, fixture))
    wanted = {r['logPath'] for r, *_ in pending};
    logs = {}
    for r in rows(path):
      if r['recordType'] == 'artifact' and r['path'] in wanted:
        logs[r['path']] = zlib.decompress(
          base64.b64decode(r['payload'])).decode()
    for r, cfg, trial, fn, families, fixture in pending:
      try:
        windows = measurement_windows(logs[r['logPath']], trial['iterations'])
      except (KeyError, ValueError) as error:
        audit[r['campaignId'] + ' / incomplete or invalid measurement: ' + str(
          error)[:100]] += 1;
        continue
      row = dict(rowId=r['campaignId'] + '/' + r['runId'] + '/' + r['forkId'],
                 campaignId=r['campaignId'],
                 policyId=policy_id(fn), originalPolicyId=r['policyId'],
                 function=fn, families=families,
                 workloadId=fixture['workloadId'], blockId=str(r['blockId']),
                 forkId=r['forkId'],
                 rawThroughput=float(np.mean(windows)), windows=windows,
                 provenance=dict(archive=str(path), logPath=r['logPath'],
                                 configPath=r['configPath']), config=cfg)
      total.append(row);
      audit[r['campaignId'] + ' / compatible'] += 1
    del logs
  # Controls are identified by exact fixed path, then matched inside campaign and block only.
  keys = {(r['campaignId'], r['workloadId'], r['blockId']) for r in total if
          r['function'] is None}
  kept = []
  for r in total:
    if (r['campaignId'], r['workloadId'], r['blockId']) in keys:
      kept.append(r)
    else:
      audit[r['campaignId'] + ' / no same-campaign OFF'] += 1
  store.append(kept)
  return dict(count=len(store.rows()), audit=dict(sorted(audit.items())),
              unit='independent JVM fork; windows nested')


def bundles(spec, records, family=None):
  """Repeated theta remain separate campaign/block observations; grouping is exact theta."""
  controls = defaultdict(list);
  references = defaultdict(list)
  ref = spec.benchmark.referenceTemplate
  for r in records:
    require_static(r)
    key = (r['campaignId'], r['workloadId'], r['blockId'])
    if r['function'] is None:
      controls[key].append(r)
    elif ref is not None and r['function'] == ref:
      references[key].append(r)
  groups = defaultdict(list)
  for r in records:
    if r['function'] is not None and (
        family is None or family.id in r['families']):
      groups[(r['campaignId'], r['policyId'], r['blockId'])].append(r)
  result = []
  for (campaign, policy, block), forks in sorted(groups.items()):
    by = defaultdict(list)
    for r in forks: by[r['workloadId']].append(r)
    targets = {};
    reference = {};
    off_ids = {};
    reference_ids = {}
    for response in spec.responses:
      offvals = [];
      refvals = []
      for w in response.workloads:
        k = (campaign, w, block);
        rr = by.get(w, [])
        if not rr: continue
        t = float(np.mean([r['rawThroughput'] for r in rr]))
        if controls[k]:
          offvals.append(
            math.log(t / np.mean([r['rawThroughput'] for r in controls[k]])))
          off_ids[w] = [r['rowId'] for r in controls[k]]
        if references[k]:
          refvals.append(
            math.log(t / np.mean([r['rawThroughput'] for r in references[k]])))
          reference_ids[w] = [r['rowId'] for r in references[k]]
      targets[response.name] = float(np.mean(offvals)) if len(offvals) == len(
        response.workloads) else None
      reference[response.name] = float(np.mean(refvals)) if len(refvals) == len(
        response.workloads) else None
    theta = forks[0]['families'][family.id] if family else None
    result.append(
      dict(rowId=campaign + '/' + policy + '/' + block, campaignId=campaign,
           blockId=block, policyId=policy,
           theta=theta, thetaId=theta_id(theta) if theta else policy,
           targets=targets, referenceTargets=reference,
           matchedOff=off_ids, matchedReference=reference_ids,
           sourceForks=[r['rowId'] for r in forks]))
  return result
