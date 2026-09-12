"""Freeze measured policies and relocate SQLite history without rewriting observations."""
from pathlib import Path
from copy import deepcopy
import argparse
import contextlib
import fcntl
import json
import math
import sqlite3
import statistics

from .loop_data import ForkStore, compatible_families, policy_id


def connect_readonly(path):
  return sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)


def database_inventory(db):
  integrity = [r[0] for r in db.execute('PRAGMA integrity_check')]
  if integrity != ['ok']:
    raise ValueError(f'SQLite integrity_check failed: {integrity}')
  rows = list(db.execute('SELECT id, record FROM forks ORDER BY id'))
  decoded = [json.loads(r[1]) for r in rows]
  if len({r[0] for r in rows}) != len(rows) or any(
      key != row['rowId'] for (key, _), row in zip(rows, decoded)):
    raise ValueError('duplicate or mismatched row IDs')
  return dict(rowCount=len(rows), policyCount=len({r['policyId'] for r in decoded}),
              campaignCount=len({r['campaignId'] for r in decoded}),
              integrityCheck='ok')


def verify_database_copy(source, destination):
  with contextlib.closing(connect_readonly(source)) as src, contextlib.closing(connect_readonly(destination)) as dst:
    src.execute('BEGIN')
    dst.execute('BEGIN')
    before, after = database_inventory(src), database_inventory(dst)
    schema = 'SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name'
    if list(src.execute(schema)) != list(dst.execute(schema)):
      raise ValueError('database schema changed during migration')
    for (table,) in src.execute("SELECT name FROM sqlite_master WHERE type='table'"):
      quoted = '"' + table.replace('"', '""') + '"'
      # Compare every stored value, including serialized measurement/provenance text.
      if sorted(src.execute('SELECT * FROM ' + quoted)) != sorted(dst.execute('SELECT * FROM ' + quoted)):
        raise ValueError('database rows changed during migration: ' + table)
    if before != after:
      raise ValueError('database inventory differs')
    return dict(source=str(Path(source).resolve()), destination=str(Path(destination).resolve()),
                **after, allStoredValuesIdentical=True)


def migrate_database(source, destination):
  source, destination = Path(source).resolve(), Path(destination).resolve()
  if source == destination:
    raise ValueError('migration requires distinct paths')
  destination.parent.mkdir(parents=True, exist_ok=True)
  if not destination.exists():
    # SQLite backup incorporates committed WAL pages. The original remains historical evidence.
    with contextlib.closing(connect_readonly(source)) as src, contextlib.closing(sqlite3.connect(destination)) as dst:
      src.backup(dst)
      dst.execute('PRAGMA journal_mode=DELETE')
  return verify_database_copy(source, destination)


def open_session_store(output, destination, resume):
  """A relocated resume must contain all previously committed session observations."""
  output, destination = Path(output), Path(destination).resolve()
  previous = output / 'forks.sqlite'
  marker = output / 'store-location.json'
  if marker.exists():
    previous = Path(json.loads(marker.read_text())['path'])
  if resume and previous.resolve() != destination:
    if not destination.exists():
      raise ValueError('resume store is missing; migrate the previous database first')
    with contextlib.closing(connect_readonly(previous)) as src, contextlib.closing(connect_readonly(destination)) as dst:
      for key, record in src.execute('SELECT id, record FROM forks'):
        if dst.execute('SELECT record FROM forks WHERE id=?', (key,)).fetchone() != (record,):
          raise ValueError('resume store is missing or conflicts with previous measurements')
  elif resume and not destination.exists():
    raise ValueError('resume store is missing')
  store = ForkStore(destination)
  from .parameter_loop import atomic
  atomic(marker, dict(path=str(destination)))
  return store


def lookup_policy(spec, records, requested):
  selected = [r for r in records if r['policyId'] == requested]
  if not selected:
    raise ValueError('requested frozen policy not found: ' + requested)
  function = selected[0]['function']
  if policy_id(function) != requested or any(r['function'] != function for r in selected):
    raise ValueError('policy ID does not identify exactly one full function')
  families = compatible_families(spec, function)
  declared = {r.get('searchFamily') for r in selected} - {None}
  family_id = next(iter(declared)) if len(declared) == 1 else next(iter(families), None)
  if family_id not in families:
    raise ValueError('frozen policy has no exact compatible family')
  family = next(f for f in spec.families if f.id == family_id)
  from .loop_references import elite_archive
  estimate = next((r for r in elite_archive(spec, records) if r['policyId'] == requested), None)
  if estimate is None:
    raise ValueError('frozen policy has no complete OFF-anchored measurement network')
  pct = lambda x: 100 * math.expm1(x)
  workloads = sorted({w for r in spec.responses if r.name in spec.preference.primaryTargets for w in r.workloads})
  return dict(policyId=requested, family=family_id,
              activeParameters=dict(zip([p.name for p in family.parameters], families[family_id])),
              runtimeTimingFunction=deepcopy(function),
              staticEvidence=dict(method=estimate['referenceKind'], measuredForkCount=len(selected),
                                  campaigns=sorted({r['campaignId'] for r in selected}),
                                  topologyPercent={n: pct(estimate['targets'][n]) for n in spec.preference.topologyTargets},
                                  broadScarcePercent=pct(statistics.mean(estimate['workloadReturns'][w] for w in workloads)),
                                  workloadPercent={w: pct(v) for w, v in estimate['workloadReturns'].items()},
                                  independentUnit='JVM fork; measurement windows are nested'))


def freeze(task, source, destination, requested, artifact):
  from .parameter_loop import load_task, atomic
  spec, root = load_task(Path(task))
  session = root / spec.outputDirectory
  with (session / 'session.pid').open('a+') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    verification = migrate_database(source, destination)
    with contextlib.closing(ForkStore(source)) as src, contextlib.closing(ForkStore(destination)) as dst:
      original = lookup_policy(spec, src.rows(), requested)
      frozen = lookup_policy(spec, dst.rows(), requested)
    if original != frozen:
      raise ValueError('frozen policy or OFF-anchored estimates changed during migration')
    artifact = Path(artifact)
    runtime = artifact.with_name(artifact.stem + '-runtime.json')
    frozen.update(schemaVersion=1, version=1, status='STATIC_FROZEN_DYNAMIC_UNVALIDATED',
                  sourceTuningSession=str(session.relative_to(root)),
                  canonicalStaticDatabase=str(Path(destination).resolve().relative_to(root)),
                  runtimeArtifact=str(runtime.resolve().relative_to(root)),
                  migrationVerification=verification)
    if artifact.exists():
      old = json.loads(artifact.read_text())
      if old['policyId'] != requested or old['runtimeTimingFunction'] != frozen['runtimeTimingFunction']:
        raise ValueError('refusing to overwrite a different frozen policy')
      return old
    atomic(artifact, frozen)
    atomic(runtime, frozen['runtimeTimingFunction'])
    atomic(session / 'static-frozen.json', dict(policyId=requested, status='STATIC_SEARCH_FROZEN',
           policyArtifact=str(artifact.resolve().relative_to(root)),
           canonicalStore=str(Path(destination).resolve()),
           reason='Static search closed by user; interrupted attempts and all retained evidence preserved'))
    print(json.dumps(dict(verification, frozenPolicyId=requested, **frozen['staticEvidence']), indent=2), flush=True)
    return frozen


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument('--task', required=True)
  parser.add_argument('--source', required=True)
  parser.add_argument('--destination', required=True)
  parser.add_argument('--policy-id', required=True)
  parser.add_argument('--artifact', required=True)
  args = parser.parse_args()
  freeze(args.task, args.source, args.destination, args.policy_id, args.artifact)


if __name__ == '__main__':
  main()
