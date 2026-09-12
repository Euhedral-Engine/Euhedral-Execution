"""JSON-controlled measured-region coverage for arbitrary parameter/response dimensions."""

from copy import deepcopy
import json
from pathlib import Path
import hashlib
import numpy as np
import pytest
from pareto_weight_calibration.known_regions import ensure_coverage, \
  useful_history
from pareto_weight_calibration.surrogate_spec import SurrogateTask
from pareto_weight_calibration.surrogate_tournament import run_task
from tests.test_surrogate_tournament import spec, data


def task(tmp_path, **coverage):
  raw = spec(tmp_path, dimensions=1).model_dump()
  raw['proposal'].update(count=1, minProposalDistance=0.05,
                         minMeasuredDistance=0.01,
                         reliability={'enabled': True})
  raw['proposal']['knownRegionCoverage'] = dict(enabled=True, radius=0.12,
                                                maxProposals=3, maxRegions=3,
                                                usefulness=dict(
                                                  primaryTargets=['off:y0',
                                                                  'off:y1'],
                                                  topologyTargets=['off:y2'],
                                                  minBlocksPerTarget=2,
                                                  selection='pareto_tradeoffs'),
                                                rejectedRegionPolicy=dict(
                                                  campaignOrder=['earlier',
                                                                 'later']))
  raw['proposal']['knownRegionCoverage'].update(coverage)
  return SurrogateTask.model_validate(json.loads(json.dumps(raw)))


def measured(points, campaign='earlier', value=0.1):
  return [
    dict(thetaId=f'theta-{p}', theta=[p], campaignId=campaign, blockId=str(b),
         targets={f'off:y{j}': value for j in range(3)}) for p in points for b
    in range(2)]


def select(t, rows, points=(0.1, 0.3, 0.8), chosen=(2,), frontier=(0, 1, 2),
    role='model_disagreement'):
  return ensure_coverage(t, dict(rows=rows), np.array(points)[:, None],
                         np.array([i in frontier for i in range(len(points))]),
                         np.array(frontier, int),
                         list(chosen), {i: role for i in chosen},
                         np.zeros(len(points), int),
                         np.arange(len(points), dtype=float),
                         np.ones(len(points)))


def test_useful_region_supported_and_automatic_diverse_expansion(tmp_path):
  t = task(tmp_path)
  chosen, roles, report = select(t, measured([0.2]))
  assert chosen == [2,
                    0]  # Automatic consensus tie-break; no coefficient supplied by policy.
  assert roles[2] == 'model_disagreement' and roles[0] == 'known_useful_region'
  assert report['regions'][0]['beforeSelected'] == []
  assert report['regions'][0][
           'beforeNearestDistance'] > t.proposal.knownRegionCoverage.radius
  assert report['regions'][0]['coverageStatus'] == 'represented'
  assert select(t, measured([0.2])) == (chosen, roles, report)
  assert 0.2 not in [(.1, .3, .8)[i] for i in chosen]


def test_no_pareto_support_not_forced_and_measured_point_ineligible(tmp_path):
  t = task(tmp_path)
  chosen, _, report = select(t, measured([0.2]), points=(0.2, .21, .8),
                             frontier=(2,))
  assert chosen == [2]
  assert report['regions'][0]['eligibleParetoPoints'] == 0
  assert report['regions'][0][
           'reasonIfUncovered'] == 'insufficient_eligible_pareto_support'


def test_later_consistent_bad_region_not_forced_but_same_campaign_is_not_rejection(
    tmp_path):
  t = task(tmp_path)
  rows = measured([.2]) + measured([.22], campaign='later', value=-.1)
  chosen, _, report = select(t, rows)
  assert chosen == [2] and report['regions'][0]['rejected']
  rows = measured([.2]) + measured([.22], value=-.1)
  assert len(select(t, rows)[0]) == 2
  # A later repeat of the original theta must not erase its historical qualification.
  rows = measured([.2]) + measured([.2], campaign='later', value=-.2)
  assert useful_history(t, dict(rows=rows))[0]['thetaId'] == 'theta-0.2'
  assert select(t, rows)[2]['regions'][0]['rejected']
  rows[-1]['targets'] = {f'off:y{j}': .1 for j in range(3)}
  assert not select(t, rows)[2]['regions'][0]['rejected']


def test_overlap_shares_one_representative_without_transitive_basin_coverage(
    tmp_path):
  t = task(tmp_path)
  chosen, _, report = select(t, measured([.2, .25]), points=(.21, .4, .8))
  assert chosen == [2, 0]
  assert len(report['sharedRepresentatives'][0]['thetaIds']) == 2
  assert all(r['selectedDistance'] <= .12 for r in report['regions'])


def test_deterministic_conflict_resolution_and_capacity_failure(tmp_path):
  t = task(tmp_path, **{'maxProposals': 1})
  # Exploration is protected, so fail explicitly instead of omitting a supported region.
  with pytest.raises(ValueError, match='coverage infeasible'):
    select(t, measured([.2]))
  # A replaceable role in the same computational basin may move to satisfy local coverage.
  result = select(t, measured([.2]), role='broad_scarcity')
  assert result[0] == [0] and result[2]['removed'][0]['sampleIndex'] == 2
  assert select(t, measured([.2]), role='broad_scarcity') == result


def test_backtracking_resolves_diversity_and_shared_region_budget(tmp_path):
  t = task(tmp_path, **{'maxRegions': 1})
  # Candidate .1 covers only first anchor, .25 covers both. maxRegions forces backtracking.
  chosen, _, report = select(t, measured([.2, .3]), points=(.1, .25, .8))
  assert 1 in chosen
  assert all(r['selectedProposal'] == 1 for r in report['regions'])


def test_json_schema_validates_targets_and_capacity(tmp_path):
  raw = task(tmp_path).model_dump()
  raw['proposal']['knownRegionCoverage']['usefulness']['primaryTargets'] = [
    'unknown']
  with pytest.raises(ValueError, match='unknown'):
    SurrogateTask.model_validate(raw)
  raw = task(tmp_path).model_dump()
  raw['proposal']['knownRegionCoverage']['maxProposals'] = 0
  with pytest.raises(ValueError):
    SurrogateTask.model_validate(raw)


def test_json_only_verified_replay_and_changed_contract_rejected(tmp_path):
  data(tmp_path)
  raw = spec(tmp_path).model_dump()
  raw['proposal']['reliability']['enabled'] = True
  path = tmp_path / 'task.json'
  path.write_text(json.dumps(raw))
  run_task(path)
  parent = tmp_path / 'result'
  raw['reuseFit'] = dict(directory='result', lockSha256=hashlib.sha256(
    (parent / 'lock.json').read_bytes()).hexdigest())
  raw['id'] = 'next'
  raw['outputDirectory'] = 'replayed'
  path.write_text(json.dumps(raw))
  result = run_task(path)
  assert result['reuseFit']['modelRefit'] is False
  for name in ['models.joblib', 'output_reliability.json', 'dataset.json']:
    assert (parent / name).read_bytes() == (
          tmp_path / 'replayed' / name).read_bytes()
  old = json.loads((parent / 'proposals.json').read_text())
  new = json.loads((tmp_path / 'replayed/proposals.json').read_text())
  assert [r['theta'] for r in old['selected']] == [r['theta'] for r in
                                                   new['selected']]
  raw['outputDirectory'] = 'invalid'
  raw['proposal']['minMeasuredDistance'] = .2
  path.write_text(json.dumps(raw))
  with pytest.raises(ValueError, match='unchanged'):
    run_task(path)
