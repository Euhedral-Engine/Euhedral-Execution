"""Per-center trust-region narrowing, durable reporting and resume."""
from copy import deepcopy
import json

import numpy as np
import pytest

from tests.test_scarce_loop import scarce_task
from pareto_weight_calibration import parameter_loop
from pareto_weight_calibration.loop_data import compatible_families, policy_id
from pareto_weight_calibration.loop_search import seed_regions, update_regions, propose
from pareto_weight_calibration.loop_references import lineage, radius_update
from pareto_weight_calibration.parameter_tuning import normalize, construct, map_parameters


def observations(spec, centers, candidates=(), campaign='round'):
  rows = []
  for block in ['0', '1']:
    for function, throughput in [(r['function'], 100.) for r in centers] + list(candidates):
      pid = policy_id(function)
      rows.append(dict(rowId=f'{campaign}/{block}/{pid}', campaignId=campaign,
                       policyId=pid, function=function,
                       families=compatible_families(spec, function),
                       workloadId='scarce', blockId=block,
                       rawThroughput=throughput, windows=[throughput],
                       referencePolicyId=centers[0]['policyId']))
  return rows


def candidate(spec, parent, edge_fraction, role='guided'):
  family = next(f for f in spec.families if f.id == parent['family'])
  unit = np.array(normalize(family.parameters, parent['theta']))
  # Always move into the domain, including a center close to a hard boundary.
  sign = np.where(unit <= .5, 1., -1.)
  next_unit = unit + sign * parent['radius'] * edge_fraction
  theta = map_parameters(family.parameters, next_unit)
  function = construct(family.parameters, family.template, theta)
  row = dict(policyId=policy_id(function), function=function, family=family.id,
              theta=theta, unit=next_unit.tolist(), role=role)
  row['lineage'] = lineage(family, row, parent if role != 'global_random' else None,
                           unit, role)
  return row


def test_retained_radius_is_stored_repeatedly_without_mutating_old_region(tmp_path):
  _, spec, history = scarce_task(tmp_path, 2)
  centers = seed_regions(spec, history)
  original = deepcopy(centers)
  for expected in [.21, .147]:
    rows = observations(spec, centers)
    previous = centers
    centers, improved, _ = update_regions(spec, centers, rows, 'round')
    assert not improved
    assert centers[0]['policyId'] == original[0]['policyId']
    assert centers[0]['radius'] == pytest.approx(expected)
    event = centers[0]['searchUpdate']
    assert event['decision'] == 'RETAIN_SHRINK'
    assert event['newRadius'] == centers[0]['radius']
    assert event['oldRadius'] == previous[0]['radius']
    assert event['parentPolicyId'] == event['candidatePolicyId']
    assert event['normalizedDistanceFromParent'] == event['normalizedEdgeFraction'] == 0
  assert centers[0]['failures'] == 2
  assert original[0]['radius'] == .3


def test_minimum_radius_and_custom_shrink(tmp_path):
  _, spec, history = scarce_task(tmp_path)
  spec = spec.model_copy(update={'search': spec.search.model_copy(update={'shrink': .5})})
  region = seed_regions(spec, history)[0]
  region['radius'] = .003
  regions, improved, _ = update_regions(spec, [region], observations(spec, [region]), 'round')
  assert regions[0]['radius'] == spec.search.minimumRadius == .002
  assert regions[0]['searchUpdate']['decision'] == 'RETAIN_SHRINK'
  regions, _, _ = update_regions(spec, regions, observations(spec, regions), 'round')
  event = regions[0]['searchUpdate']
  assert event['decision'] == 'RETAIN_MIN_RADIUS'
  assert event['oldRadius'] == event['newRadius'] == regions[0]['radius'] == .002


@pytest.mark.parametrize('fraction,decision,expected', [(.5, 'INTERIOR_SHRINK', .147),
                                                        (.95, 'EDGE_MOVE', .21)])
def test_two_center_beam_moves_one_center_and_shrinks_other_independently(tmp_path, fraction, decision, expected):
  _, spec, history = scarce_task(tmp_path, 2)
  spec = spec.model_copy(update={'budgets': spec.budgets.model_copy(update={'beamWidth': 2})})
  centers = seed_regions(spec, history)
  centers[0]['radius'] = .147
  centers[1]['radius'] = .21
  child = candidate(spec, centers[1], fraction)
  rows = observations(spec, centers, [(child['function'], 110.)])
  updated, improved, _ = update_regions(spec, centers, rows, 'round', [child])
  assert improved and len(updated) == 2
  by = {r['policyId']: r for r in updated}
  assert by[centers[0]['policyId']]['radius'] == pytest.approx(.1029)
  assert by[centers[0]['policyId']]['searchUpdate']['decision'] == 'RETAIN_SHRINK'
  moved = by[child['policyId']]
  assert moved['searchUpdate']['decision'] == decision
  assert moved['radius'] == pytest.approx(expected)
  assert moved['lineage'] == child['lineage']


def test_global_basin_is_initialized_then_only_shrinks_on_later_round(tmp_path):
  _, spec, history = scarce_task(tmp_path, 2)
  spec = spec.model_copy(update={'search': spec.search.model_copy(update={'newBasinRadius': .25})})
  center = seed_regions(spec, history)[0]
  center['radius'] = .147
  child = candidate(spec, center, .9, 'global_random')
  updated, improved, _ = update_regions(spec, [center],
      observations(spec, [center], [(child['function'], 110.)]), 'round', [child])
  assert improved and updated[0]['radius'] == .25
  assert updated[0]['searchUpdate']['decision'] == 'NEW_BASIN'
  updated, improved, _ = update_regions(spec, updated, observations(spec, updated), 'round')
  assert not improved and updated[0]['radius'] == pytest.approx(.175)


def setup_fake_loop(tmp_path, monkeypatch, restart_patience=99, beam=1):
  path, spec, history = scarce_task(tmp_path, 2)
  raw = json.loads(path.read_text())
  raw['budgets'].update(rounds=3, beamWidth=beam)
  raw['search']['restartPatience'] = restart_patience
  path.write_text(json.dumps(raw))
  seen = []

  def offline(module, name, *args, progress=None):
    if name == 'fit': return {}
    actual_spec, records, regions, directory, index = args
    seen.append(deepcopy(regions))
    result = propose(*args, progress=progress)
    for row in result['candidates']:
      if row['lineage']['parentPolicyId']:
        parent = next(r for r in regions if r['policyId'] == row['lineage']['parentPolicyId'])
        assert row['lineage']['parentRadius'] == parent['radius']
        assert np.max(np.abs(np.array(row['unit']) - row['lineage']['parentNormalizedCoordinates'])) <= parent['radius'] + 1e-12
    if index == 2:
      # Deterministic improved edge challenger solely for the fake benchmark.
      result['candidates'][0] = candidate(actual_spec, regions[0], .95)
    return result

  monkeypatch.setattr(parameter_loop, 'run_offline', offline)
  fake = tmp_path / 'fake.py'
  script = fake.read_text()
  # Only the predetermined round-three edge challenger improves on the center.
  edge_weight = 2 * spec.search.initialRadius * spec.search.shrink ** 2 * .95
  script = script.replace('v=100 if f is None else 100*(1.08-.4*(x-.2)**2)',
      f"v=110 if t['id'].startswith('r2-') and abs(x-{edge_weight!r})<1e-12 else (100 if f is None or abs(x)<1e-12 else 90)")
  fake.write_text(script)
  return path, seen


def test_fake_three_round_radius_trace_resume_and_all_reports(tmp_path, monkeypatch, capsys):
  path, seen = setup_fake_loop(tmp_path, monkeypatch)
  parameter_loop.run_task(path, max_rounds=2)
  output = tmp_path / 'out'
  saved = json.loads((output / 'state.json').read_text())
  original = saved['regions'][0]['policyId']
  assert saved['regions'][0]['radius'] == pytest.approx(.147)
  assert saved['stagnation'] == 2
  # Resume must use the stored radius, without seeding again from initialRadius.
  parameter_loop.run_task(path, resume=True, max_rounds=3)
  assert [r[0]['radius'] for r in seen] == pytest.approx([.3, .21, .147])
  state = json.loads((output / 'state.json').read_text())
  assert state['regions'][0]['radius'] == pytest.approx(.147)
  assert state['regions'][0]['policyId'] != original
  assert state['stagnation'] == 0
  trace = []
  for index, (before, after, decision) in enumerate([
      (.3, .21, 'RETAIN_SHRINK'), (.21, .147, 'RETAIN_SHRINK'), (.147, .147, 'EDGE_MOVE')]):
    directory = output / f'round-{index:03d}'
    summary = json.loads((directory / 'round-summary.json').read_text())
    update = json.loads((directory / 'update.json').read_text())
    text = (directory / 'round-summary.txt').read_text()
    event = update['searchUpdates'][0]
    assert event == summary['searchUpdates'][0]
    assert event['decision'] == decision
    assert event['oldRadius'] == pytest.approx(before)
    assert event['newRadius'] == pytest.approx(after)
    assert summary['currentIncumbent']['searchRadius'] == pytest.approx(after)
    assert summary['nextSearchRegions'] == update['nextRegions']
    assert all(r['radius'] == pytest.approx(after) for r in update['nextRegions'])
    assert f'decision: {decision}' in text
    assert f'radius={after:.6g}' in text
    assert f'radius: {before:.6g} -> {after:.6g}' in text
    trace.append(f'round {index+1}: {before:g} -> {after:g} {decision}')
  assert state['regions'] == update['nextRegions']
  assert json.loads((output / 'current-best.json').read_text())['searchRadius'] == pytest.approx(.147)
  terminal = capsys.readouterr().out
  assert 'RETAIN_SHRINK' in terminal and 'EDGE_MOVE' in terminal
  with capsys.disabled(): print('\nFAKE RADIUS TRACE\n' + '\n'.join(trace))


def test_restart_preserves_shrunken_center_and_does_not_shrink_new_alternate(tmp_path, monkeypatch):
  path, seen = setup_fake_loop(tmp_path, monkeypatch, restart_patience=1, beam=2)
  parameter_loop.run_task(path, max_rounds=1)
  output = tmp_path / 'out'
  summary = json.loads((output / 'round-000/round-summary.json').read_text())
  state = json.loads((output / 'state.json').read_text())
  primary = summary['previousSearchRegions'][0]
  retained = next(r for r in state['regions'] if r['policyId'] == primary['policyId'])
  assert retained['radius'] == pytest.approx(.21)
  assert retained['searchUpdate']['decision'] == 'RETAIN_SHRINK'
  inserted = [r for r in state['regions'] if r['policyId'] not in {c['policyId'] for c in summary['previousSearchRegions']}]
  assert len(inserted) == 1
  assert inserted[0]['radius'] == .3
  assert inserted[0]['searchUpdate']['decision'] == 'ALTERNATE_BASIN'
  assert len({r['policyId'] for r in state['regions']}) == 2
