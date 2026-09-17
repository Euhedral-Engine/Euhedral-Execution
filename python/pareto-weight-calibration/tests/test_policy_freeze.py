from copy import deepcopy
import contextlib
import json
import sqlite3
import pytest

from tests.test_parameter_loop import task
from pareto_weight_calibration.loop_data import ForkStore, bundles
from pareto_weight_calibration.loop_references import matched_panels
from pareto_weight_calibration.parameter_loop import load_task, run_task
from pareto_weight_calibration.policy_freeze import (
    migrate_database, verify_database_copy, lookup_policy, open_session_store, database_inventory, freeze)


def test_freeze_writes_db_function_exactly_and_closes_static_session(tmp_path):
  path, records = task(tmp_path)
  source = tmp_path / 'out/forks.sqlite'
  with contextlib.closing(ForkStore(source)) as store: store.append(records)
  artifact = tmp_path / 'policies/chosen.json'
  result = freeze(path, source, tmp_path / 'datasets/history.sqlite', records[1]['policyId'], artifact)
  assert result['status'] == 'STATIC_FROZEN_DYNAMIC_UNVALIDATED'
  assert json.loads(artifact.read_text())['runtimeTimingFunction'] == records[1]['function']
  assert json.loads((tmp_path / 'policies/chosen-runtime.json').read_text()) == records[1]['function']
  assert json.loads((tmp_path / 'out/static-frozen.json').read_text())['policyId'] == records[1]['policyId']
  with pytest.raises(ValueError, match='frozen historical'): run_task(path, resume=True)


def test_backup_preserves_all_rows_windows_provenance_and_live_wal(tmp_path):
  _, records = task(tmp_path)
  source, destination = tmp_path / 'source.sqlite', tmp_path / 'durable/history.sqlite'
  with contextlib.closing(ForkStore(source)) as store:
    store.db.execute('PRAGMA journal_mode=WAL')
    store.append(records)
    result = migrate_database(source, destination)
    assert result['rowCount'] == len(records)
    assert result['campaignCount'] == 2
    assert result['policyCount'] == 4
    assert result['integrityCheck'] == 'ok' and result['allStoredValuesIdentical']
    assert source.with_name('source.sqlite-wal').exists()
    with contextlib.closing(ForkStore(destination)) as migrated:
      assert migrated.rows() == sorted(records, key=lambda r: r['rowId'])
      migrated.append(records)
      assert len(migrated.rows()) == len(records)
      assert database_inventory(migrated.db)['rowCount'] == len(records)
    assert not destination.with_name('history.sqlite-wal').exists()
    assert not destination.with_name('history.sqlite-shm').exists()


def test_changed_copy_and_corrupt_database_fail_verification(tmp_path):
  _, records = task(tmp_path)
  source, destination = tmp_path / 'a.sqlite', tmp_path / 'b.sqlite'
  with contextlib.closing(ForkStore(source)) as store: store.append(records)
  migrate_database(source, destination)
  with sqlite3.connect(destination) as db: db.execute('DELETE FROM forks WHERE id=?', (records[0]['rowId'],))
  with pytest.raises(ValueError): verify_database_copy(source, destination)
  bad = tmp_path / 'bad.sqlite'
  bad.write_bytes(b'not sqlite')
  with pytest.raises(sqlite3.DatabaseError): migrate_database(bad, tmp_path / 'bad-copy.sqlite')


def test_exact_id_lookup_preserves_function_and_active_values(tmp_path):
  path, records = task(tmp_path)
  spec, _ = load_task(path)
  requested = records[1]['policyId']
  artifact = lookup_policy(spec, records, requested)
  assert artifact['policyId'] == requested
  assert artifact['runtimeTimingFunction'] == records[1]['function']
  assert list(artifact['activeParameters'].values()) == records[1]['function']['weights']
  assert artifact['staticEvidence']['measuredForkCount'] == 8
  assert artifact['staticEvidence']['method'].startswith('OFF-anchored')
  altered = deepcopy(records)
  altered[1]['function']['weights'][0] += .1
  with pytest.raises(ValueError, match='exactly one'): lookup_policy(spec, altered, requested)
  with pytest.raises(ValueError, match='not found'): lookup_policy(spec, records, 'policy-absent')


def test_external_store_resume_and_static_training(tmp_path):
  path, _ = task(tmp_path)
  run_task(path, max_rounds=1)
  source, destination = tmp_path / 'out/forks.sqlite', tmp_path / 'datasets/history.sqlite'
  migrate_database(source, destination)
  raw = json.loads(path.read_text())
  raw['storePath'] = str(destination)
  path.write_text(json.dumps(raw))
  result = run_task(path, resume=True)
  assert result['completedRounds'] == 2
  with contextlib.closing(ForkStore(destination)) as store:
    rows = store.rows()
    assert len(rows) == len({r['rowId'] for r in rows})
    spec, _ = load_task(path)
    assert bundles(spec, rows, spec.families[0])
  run_task(path, resume=True)
  with contextlib.closing(ForkStore(destination)) as store: assert store.rows() == rows
  with pytest.raises(ValueError, match='missing'):
    open_session_store(tmp_path / 'out', tmp_path / 'missing.sqlite', True)
  (tmp_path / 'out/static-frozen.json').write_text('{}')
  with pytest.raises(ValueError, match='frozen historical'): run_task(path, resume=True)


@pytest.mark.parametrize('change', [dict(recordType='dynamic_window'), dict(recordType='dynamic_fork'),
                                  dict(workloadClass='DYNAMIC'), dict(phases=[{}]),
                                  dict(jvmArgs=['-Deuhedral.calibration.dynamicSchedule={}'])])
def test_dynamic_rows_rejected_by_store_and_training(tmp_path, change):
  path, records = task(tmp_path)
  spec, _ = load_task(path)
  records[1].update(change)
  with contextlib.closing(ForkStore(tmp_path / 'static.sqlite')) as store:
    with pytest.raises(ValueError, match='dynamic'): store.append(records)
    assert store.rows() == []
  with pytest.raises(ValueError, match='dynamic'): bundles(spec, records)
  with pytest.raises(ValueError, match='dynamic'): matched_panels(spec, records, training=True)
