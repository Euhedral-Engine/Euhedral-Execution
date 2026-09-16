"""Generic arbitrary-width tuning and end-to-end synthetic policy-return evidence."""
from copy import deepcopy
from pathlib import Path
import json
import math
import numpy as np
import pytest
from pareto_weight_calibration import parameter_tuning as t
from pareto_weight_calibration import cache_timing_tuning as ct
from pareto_weight_calibration import cache_timing_confirmation as c
from pareto_weight_calibration.tuning_spec import Parameter, TuningSpec
from pareto_weight_calibration.training_spec import canonical
from pareto_weight_calibration.training_runner import run, split_plan
from pareto_weight_calibration.task_adapters import load_dataset
from pareto_weight_calibration.runtime_export import predict
from pareto_weight_calibration.cache_timing import write, sha


@pytest.fixture(scope='module')
def spec(): return ct.load_spec(ct.DEFAULT_SPEC)


@pytest.mark.parametrize('width', [1, 3, 7])
def test_arbitrary_parameter_spec_mapping_sign_and_quadratic_width(spec, width):
  payload = spec.model_dump(mode='json')
  payload['parameters'] = [dict(name=f'p{i}', path=f'/coeff/{i}', center=i + 1.,
                                bounds=[(i + 1) * .7, (i + 1) * 1.3],
                                sign='positive') for i in range(width)]
  other = TuningSpec.model_validate(payload);
  center = {'coeff': [p.center for p in other.parameters],
            'untuned': [.001, 99]}
  lo = t.map_parameters(other.parameters, np.zeros(width));
  hi = t.map_parameters(other.parameters, np.ones(width))
  assert lo == [p.bounds[0] for p in other.parameters];
  assert hi == [p.bounds[1] for p in other.parameters]
  assert t.construct(other.parameters, center,
                     [p.center for p in other.parameters]) == center
  assert t.construct(other.parameters, center, lo)['untuned'] == center[
    'untuned']
  task = t.training_task(other);
  assert len(task.basis.terms) + width + 1 == 1 + width + width + width * (
        width - 1) // 2
  with pytest.raises(ValueError): t.map_parameters(other.parameters,
                                                   [-1] * width)
  with pytest.raises(ValueError): t.construct(other.parameters, center,
                                              [0] * width)
  with pytest.raises(ValueError): Parameter(name='bad', path='/x', center=-1,
                                            bounds=[-2, 1], sign='negative')
  logarithmic = Parameter(name='log', path='/x', center=10, bounds=[1, 100],
                          transform='log')
  assert t.map_parameters([logarithmic], [.5])[0] == pytest.approx(10)


def test_deterministic_sobol_exact_center_full_coefficients_and_filter_replacement(
    spec):
  center = ct.center_for(spec);
  inspect = ct.inspector(spec, center)
  a, provenance = t.initial_candidates(spec, center, inspect)
  b, p2 = t.initial_candidates(spec, center, inspect)
  assert canonical(a) == canonical(b);
  assert provenance == p2;
  assert len(a) == 17
  assert a[0]['function'] == center and a[0]['delta'] == [0] * len(
    spec.parameters)
  for item in a:
    assert len(item['function']['parkCoefficients'] + item['function'][
      'halfLifeCoefficients']) == 14
    assert item['behavior']['monotonic']
    restored = deepcopy(item['function'])
    for p in spec.parameters: t.pointer(restored, p.path, p.center, True)
    assert restored == center
  first = a[1]['function']

  def reject_one(config):
    if config == first: raise ValueError('synthetic clamp rejection')
    return inspect(config)

  filtered, log = t.initial_candidates(spec, center, reject_one)
  assert log['rejected'] == [
    dict(sampleIndex=a[1]['sampleIndex'], reason='synthetic clamp rejection')]
  assert filtered[1]['sampleIndex'] == a[2]['sampleIndex'];
  assert len(filtered) == 17
  assert ct.audit_region(spec, center, inspect)['allValid']
  tight = spec.model_copy(
    update={'runtime': dict(spec.runtime, maxSingleBoundaryFraction=0.)})
  corner = t.construct(spec.parameters, center,
                       [p.bounds[0] if i != 3 else p.bounds[1] for i, p in
                        enumerate(spec.parameters)])
  with pytest.raises(ValueError, match='clamp'):
    ct.inspector(tight, center)(corner)


def test_pareto_deterministic_ties_and_conflicting_objectives():
  assert t.pareto_indices([[1, 0], [0, 1], [0, 0], [1, 0]]) == [0, 1]
  assert t.pareto_indices([[2, 2], [1, 0], [0, 1]]) == [0]


@pytest.fixture(scope='module')
def handoff(tmp_path_factory):
  out = tmp_path_factory.mktemp('auto') / 'campaign';
  ct.prepare(ct.DEFAULT_SPEC, out)
  m = c.read(out / 'candidate_manifest.json');
  info = m['stages']['tuning']
  info.update(outputDirectory=str(out.parent / 'raw'),
              evidenceDirectory=str(out.parent / 'evidence'))
  (out / 'candidate_manifest.json').write_text(json.dumps(m))
  h = c.read(out / 'tuning_harness.json');
  h['artifacts']['outputDirectory'] = info['outputDirectory'];
  (out / 'tuning_harness.json').write_text(json.dumps(h))
  task = c.read(out / 'task.json');
  task['dataset']['manifest'] = str(out.parent / 'evidence/dataset.json');
  (out / 'task.json').write_text(json.dumps(task))
  ct.finalize(out)
  return out


def test_catalog_harness_inventory_and_freeze(handoff, spec):
  m = ct.check(handoff)
  assert len(m['arms']) == 18;
  assert m['arms'][0]['function'] is None
  assert m['stages']['tuning']['expectedForks'] == 648;
  assert m['blocks'] == 2
  assert len((handoff / 'surface_catalog.tsv').read_text().splitlines()) == 18
  with pytest.raises(ValueError, match='frozen'): ct.finalize(handoff)
  assert not Path(m['stages']['tuning']['outputDirectory']).exists()


def synthetic_campaign(handoff):
  m = ct.check(handoff);
  info = m['stages']['tuning'];
  raw = Path(info['outputDirectory'])
  write(raw / 'identity.json',
        dict(stage='tuning', handoffLockSha256=sha(handoff / 'lock.json'),
             harnessSha256=sha(handoff / 'tuning_harness.json'),
             sourceHashes=c.read(handoff / 'lock.json')['sourceHashes'],
             topologies=m['topologies']))
  parameters = {p['id']: np.array(p['normalizedCoordinates']) - .5 for p in
                m['policies']}
  for trial in c.read(handoff / 'tuning_harness.json')['trials']:
    trial = deepcopy(trial);
    trial['calibrationConfig']['cacheActuatorVersion'] = 'synthetic'
    path = raw / trial['id'];
    write(path / 'trial_config.json', trial)
    arm = trial['labels']['policyId'];
    cfg = trial['calibrationConfig'];
    scarce = cfg['parallelSources'] == 1
    topology = next(
        f['resolvedWorkers'] for f in m['stages']['tuning']['fixtures'] if
        f['workloadId'] == trial['labels']['workloadId'])
    scale = (1 if scarce else 1000) * (
      1 if trial['origin']['sampleIndex'] == 0 else 3)
    effect = 0.
    if arm != 'POLICY_OFF':
      z = parameters[arm]
      effect = (.07 + .06 * z[0] + .02 * z[1] * z[2] - .015 * z[
        3] ** 2 if scarce else -.006 + .01 * z[1])
      if topology == 7: effect += .01 * z[1]
    values = [scale * math.exp(effect) * (1 + .002 * i) for i in range(5)]
    log = '# JMH version: synthetic\n# VM version: synthetic\n# Fork: 1 of 1\n'
    for i, v in enumerate(values,
                          1): log += f'Iteration {i}: 1 ops/s\n executions: {v} ops/s\n'
    (path / 'benchmark_output.log').write_text(log)
  return m


def test_six_output_construction_quadratic_fit_and_automatic_proposals(handoff,
    spec, tmp_path):
  m = synthetic_campaign(handoff);
  ct.collect(handoff)
  e = Path(m['stages']['tuning']['evidenceDirectory'])
  receipt = c.read(e / 'evidence_manifest.json');
  assert receipt['forkCount'] == 648;
  assert receipt['windowCount'] == 3240
  task = t.training_task(spec, str(e / 'dataset.json'));
  data = load_dataset(task, handoff)
  assert data.X.shape == (34, 4);
  assert data.Y.shape == (34, 24)
  assert [r.name for r in spec.responses[:6]] == ['R7_scarce', 'R15_scarce',
                                                  'R23_scarce', 'R7_plentiful',
                                                  'R15_plentiful',
                                                  'R23_plentiful']
  for a, b in split_plan(data, 4, 17):
    assert set(data.groups[i] for i in a).isdisjoint(data.groups[i] for i in b)
    for i in b: assert len(data.metadata[i]['sourceForks']) == 18
  # Outputs are separate and exactly mean log ratios over each declared workload set.
  records = c.read(e / 'arms.json');
  keys = {(r['policyId'], r['workloadId'], r['passId']): r for r in records}
  for row in c.read(e / 'response_rows.json'):
    for response in spec.responses:
      ys = [math.log(
        keys[row['policyId'], w, row['passId']]['executionsPerSecond'] /
        keys['POLICY_OFF', w, row['passId']]['executionsPerSecond']) for w in
            response.workloads]
      assert row['responses'][response.name] == pytest.approx(np.mean(ys))
  fitted = run(task, data, 'cpu');
  assert len(fitted['evaluator']['schema']['outputNames']) == 14
  assert np.array(fitted['evaluator']['coefficients']).shape == (24, 14)
  assert canonical(fitted) == canonical(run(task, data, 'cpu'))
  measured = [p['parameterVector'] for p in m['policies']];
  inspect = ct.inspector(spec, ct.center_for(spec))
  small = spec.model_copy(update={'proposal': spec.proposal.model_copy(update={
    'design': spec.proposal.design.model_copy(
      update={'poolPower': 7, 'count': 128})})})
  a, report = t.propose(small, ct.center_for(spec), fitted['evaluator'],
                        measured, inspect)
  b, r2 = t.propose(small, ct.center_for(spec), fitted['evaluator'], measured,
                    inspect)
  assert a and canonical(a) == canonical(b) and report == r2
  for p in a:
    assert min(np.linalg.norm(np.array(p['normalizedCoordinates']) - np.array(
      t.normalize(spec.parameters, v))) for v in
               measured) >= small.proposal.minMeasuredDistance
    assert set(p['prediction']) == {r.name for r in spec.responses}
  # Model output rankings can disagree: no additive fixture intercept restriction.
  assert not np.array_equal(np.array(fitted['evaluator']['coefficients'])[0],
                            np.array(fitted['evaluator']['coefficients'])[1])
  fit_dir = tmp_path / 'fit';
  write(fit_dir / 'run.json', fitted)
  # Full adapter emits complete follow-up configs from predictions, no manually chosen vector.
  ct.proposals(handoff, fit_dir, tmp_path / 'next')
  followup = ct.check(tmp_path / 'next')
  assert 3 <= len(followup['arms']) <= 8
  assert not Path(followup['stages']['tuning']['outputDirectory']).exists()


def test_generated_java_runtime_and_export_parity_for_all_surfaces(spec,
    tmp_path):
  center = ct.center_for(spec)
  policies, _ = t.initial_candidates(spec, center, ct.inspector(spec, center))
  assert_java_runtime_and_export_parity(spec, policies, tmp_path)


def test_frozen_proposal_java_runtime_and_export_parity(tmp_path):
  from pareto_weight_calibration.runtime_export import render_cache_timing
  handoff = ct.PARENT / 'live25-auto-proposal-v1'
  if not (handoff / 'lock.json').exists():
    pytest.skip('local frozen proposal campaign not available')
  manifest = ct.check(handoff)
  spec = ct.load_spec(handoff / 'tuning_spec.json')
  assert [a['id'] for a in manifest['arms']] == [
    'POLICY_OFF', 'live-25-center', 'live25-auto-proposal-v1-2997',
    'live25-auto-proposal-v1-3853']
  assert manifest['stages']['tuning']['expectedForks'] == 144
  for policy in manifest['policies']:
    directory = handoff / 'policies' / policy['id']
    assert json.loads((directory / 'config.json').read_text()) == policy[
      'function']
    assert (
                 directory / 'CacheTimingEvaluator.java').read_text() == render_cache_timing(
        policy['function'])
  assert_java_runtime_and_export_parity(spec, manifest['policies'], tmp_path)


def assert_java_runtime_and_export_parity(spec, policies, tmp_path):
  import subprocess
  from pareto_weight_calibration.cache_timing import ROOT, evaluate
  from pareto_weight_calibration.runtime_export import render_cache_timing
  inputs = [(c, p, math.expm1(b)) for c, p, b in spec.runtime['supportPoints']]
  inputs += [(float('nan'), 1, 0), (.5, -1, 0), (.5, 5, 0), (2, 1, 0),
             (.5, 1, -1), (.5, 1, float('inf'))]

  def literal(x):
    return 'Double.NaN' if math.isnan(
      x) else 'Double.POSITIVE_INFINITY' if math.isinf(x) else repr(float(x))

  files = [];
  parts = [];
  expected = []
  for i, item in enumerate(policies):
    cfg = item['function'];
    class_name = f'Evaluator{i}'
    path = tmp_path / (class_name + '.java');
    path.write_text(render_cache_timing(cfg, class_name));
    files.append(str(path))
    args = [json.dumps(cfg['normalizationVersion'])] + [
      'java.util.List.of(' + ','.join(literal(x) for x in cfg[k]) + ')' for k in
      ['means', 'scales', 'supportMin', 'supportMax', 'parkCoefficients',
       'halfLifeCoefficients']] + [str(cfg[k]) + 'L' for k in
                                   ['parkReferenceNanos',
                                    'halfLifeReferenceNanos', 'parkMinNanos',
                                    'parkMaxNanos', 'halfLifeMinNanos',
                                    'halfLifeMaxNanos']]
    parts.append('{ var config=new CacheTimingFunctionConfig(' + ','.join(
      args) + '); for(var x:inputs) System.out.println(config.parkNanos(x[0],x[1],x[2],15000L)+","+config.halfLifeNanos(x[0],x[1],x[2],1000000L)+","+' + class_name + '.parkNanos(x[0],x[1],x[2],15000L)+","+' + class_name + '.halfLifeNanos(x[0],x[1],x[2],1000000L)); }')
    expected.extend([evaluate(cfg, *point) * 2 for point in inputs])
  source = 'import io.euhedral_execution.core.config.IdleTimingFunction; class Check { public static void main(String[] args) { double[][] inputs={' + ','.join(
      '{' + ','.join(literal(x) for x in p) + '}' for p in
      inputs) + '};' + ''.join(parts) + '}}'
  (tmp_path / 'Check.java').write_text(source)
  subprocess.run(['mise', 'exec', '--', 'javac', '-d', str(tmp_path), str(
    ROOT / 'euhedral-core/src/main/java/io/euhedral_execution/core/config/CacheTimingFunctionConfig.java'),
                  str(tmp_path / 'Check.java')] + files, cwd=ROOT, check=True,
                 capture_output=True)
  result = subprocess.check_output(
      ['mise', 'exec', '--', 'java', '-cp', str(tmp_path), 'Check'], cwd=ROOT,
      text=True)
  assert [tuple(map(int, x.split(','))) for x in
          result.splitlines()] == expected
  assert all(
      15000 <= x[0] <= 814375 and 250000 <= x[1] <= 2000000 for x in expected)
