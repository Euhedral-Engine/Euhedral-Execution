from copy import deepcopy
import json
from pathlib import Path
import pytest
from pareto_weight_calibration.dynamic_validation import panel, parse_windows, recovery, compare


def config():
  return json.loads((Path(__file__).parents[1] / 'tasks/cache-scarce-dynamic-v1.json').read_text())


def log(values):
  return '# Fork: 1 of 1\n# Warmup Iteration 1: 1000000\n executions: 1000000 ops/s\n' + ''.join(
      f'Iteration {i+1}: 1 ops/s\n executions: {v} ops/s\n' for i, v in enumerate(values))


def test_bounded_gate_body_and_scarcity_return_fixtures():
  fixtures = panel(config())
  assert len(fixtures) * 2 == 14
  for f in fixtures:
    states = [(p['workUnits'], p['enabledSources']) for p in f['phases']]
    assert states[0] == states[-1]
    if f['kind'] == 'gate': assert states == [(0, 1), (0, f['workers']), (0, 1)]
    if f['kind'] == 'body': assert states == [(0, 1), (576, 1), (0, 1)]
    if f['kind'] == 'scarcity': assert states == [(0, 1), (0, 4), (0, 1)]


def test_parser_retains_phase_windows_and_rejects_partial_or_multiple_forks():
  fixture = panel(config())[0]
  parsed = parse_windows(log(list(range(1, 25))), fixture)
  assert len(parsed) == 24
  assert (parsed[8]['phase'], parsed[8]['phaseWindow'], parsed[8]['throughput']) == (1, 1, 9)
  assert parsed[16]['phase'] == 2
  with pytest.raises(ValueError): parse_windows(log([1] * 23), fixture)
  with pytest.raises(ValueError): parse_windows(log([1] * 24) * 2, fixture)


def test_recovery_counts_consecutive_windows_and_can_remain_unrecovered():
  c = config()
  c['steadyWindows'] = 3
  assert recovery([60, 90, 100, 101, 99, 100], c) == 4
  assert recovery([100] * 6, c) == 2
  assert recovery([50, 150, 50, 150, 50, 150], c) is None
  c['recovery']['consecutiveWindows'] = 3
  assert recovery([60, 90, 100, 101, 99, 100], c) == 5


def test_matched_off_and_nested_unit_no_function_mutation():
  c = config()
  manifest = dict(config=c, fixtures=panel(c), function={'weights': [1, 2, 3]})
  original = deepcopy(manifest)
  values = {}
  for fixture in manifest['fixtures']:
    for fork in range(c['forksPerArm']):
      for arm, value in [('POLICY_OFF', 100), ('FROZEN_POLICY', 110)]:
        values[fixture['id'], fork, arm] = parse_windows(log([value] * 24), fixture)
  windows, phases, forks, transitions = compare(manifest, values)
  assert len(forks) == 42 and len(windows) == 42 * 24
  assert all(r['nestedWindows'] == 24 for r in forks)
  assert all(r['deltaVsOffPercent'] == pytest.approx(10) for r in windows if r['mode'] == 'FROZEN_POLICY')
  assert all(r['returnVsInitialPercent'] == 0 for r in forks)
  assert all(r['steadyDeltaVsOffPercent'] == pytest.approx(10) for r in transitions if r['mode'] == 'FROZEN_POLICY')
  assert manifest == original
  del values[manifest['fixtures'][0]['id'], 0, 'POLICY_OFF']
  with pytest.raises(KeyError): compare(manifest, values)
