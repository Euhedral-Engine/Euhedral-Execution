"""Generic extension design, JSON dimensionality and same-evidence model ablation."""
from copy import deepcopy
from itertools import product
import json
import math
from pathlib import Path
import numpy as np
import pytest
from pareto_weight_calibration.parameter_screen import Extension, BoundRecipe, \
  derive_bound, levels, surface, ScreenTask
from pareto_weight_calibration.tuning_spec import Parameter
from pareto_weight_calibration.historical_response import compatible_function, \
  theta_id
from pareto_weight_calibration.surrogate_models import Model, expand
from pareto_weight_calibration.surrogate_spec import ModelConfig
from pareto_weight_calibration.surrogate_tournament import run_task
from tests.test_surrogate_tournament import spec, data


def runtime():
  return dict(normalizationVersion='test', means=[.5, 2, 8], scales=[.5, 2, 8],
              supportMin=[0, 0, 0], supportMax=[1, 4, 16],
              parkReferenceNanos=15000, halfLifeReferenceNanos=1000000,
              parkMinNanos=15000, parkMaxNanos=814375, halfLifeMinNanos=250000,
              halfLifeMaxNanos=2000000,
              parkCoefficients=[1.5, 1e-16, -.8, -3e-17, 4e-18, 5e-18, 6e-18],
              halfLifeCoefficients=[-.6, 1e-18, .2, 2e-18, 3e-18, 4e-18, 5e-18])


def parameters(base):
  return [Parameter(name='p' + str(i), path=f'/{key}/{index}',
                    center=base[key][index], bounds=bounds) for
          i, (key, index, bounds) in enumerate(
        [('parkCoefficients', 0, (1.2, 1.8)),
         ('parkCoefficients', 2, (-1, -.6)),
         ('halfLifeCoefficients', 0, (-.8, -.4)),
         ('halfLifeCoefficients', 2, (.15, .3))])]


@pytest.mark.parametrize('prefix,index,feature',
                         [('park', 3, (2,)), ('halfLife', 3, (2,)),
                          ('park', 6, (1, 2)), ('halfLife', 6, (1, 2)),
                          ('park', 1, (0,))])
def test_bounds_zero_parity_exact_inactive_coefficients_and_determinism(prefix,
    index, feature):
  base = runtime();
  params = parameters(base);
  key = prefix + 'Coefficients';
  path = f'/{key}/{index}'
  e = Extension(id='extension', name='extra', path=path, featureIndices=feature,
                outputPrefix=prefix)
  points = list(product([0, .5, 1], [0, 1, 2, 4], [0, 8, 16]))
  bounds, audit = derive_bound(base, params, e, points, BoundRecipe())
  assert bounds == pytest.approx((-math.log(1.35), math.log(1.35)))
  assert (audit['featureMin'], audit['featureMax']) == (-1, 1)
  params.append(Parameter(name='extra', path=path, center=0, bounds=bounds,
                          offset=base[key][index]))
  generated = list(levels(params, base, 4, [-1, 0, 1]));
  assert generated == list(levels(params, base, 4, [-1, 0, 1]))
  assert generated[1][3] == base and generated[1][2][-1] == 0
  for _, level, values, cfg in generated:
    restored = deepcopy(cfg);
    restored[key][index] = base[key][index];
    assert restored == base
    theta, reason = compatible_function(cfg, base, params);
    assert reason is None and theta[-1] == pytest.approx(values[-1])
    vals, occ = surface(cfg, points);
    assert vals.shape == (36, 2)
    assert max(x[k] for x in occ.values() for k in ['lower', 'upper']) <= .5
  bad = deepcopy(base);
  bad['halfLifeCoefficients'][5] += .1
  assert compatible_function(bad, base, params)[0] is None


def test_support_path_validation_and_automatic_shrink():
  base = runtime();
  params = parameters(base);
  e = Extension(id='b', name='b', path='/parkCoefficients/3',
                featureIndices=(1, 2), outputPrefix='park')
  with pytest.raises(ValueError, match='basis'): derive_bound(base, params, e,
                                                              [(0, 0, 0)],
                                                              BoundRecipe())
  e = e.model_copy(update={'featureIndices': (2,)})
  with pytest.raises(ValueError, match='support'): derive_bound(base, params, e,
                                                                [(0, 0, 99)],
                                                                BoundRecipe())
  bounds, audit = derive_bound(base, params, e,
                               list(product([0, 1], [0, 4], [0, 8, 16])),
                               BoundRecipe(maxUnclampedFactor=100,
                                           minimumUnclampedFactor=1.1,
                                           maxSingleBoundaryFraction=.3))
  assert len(audit['attempts']) > 1 and bounds[1] < math.log(100)


@pytest.mark.parametrize('dimensions', [4, 5])
def test_arbitrary_dimensions_json_tournament_and_proposals(tmp_path,
    dimensions):
  data(tmp_path, dimensions);
  raw = spec(tmp_path, dimensions).model_dump(mode='json')
  raw['models'] = [dict(id='mean', family='mean'),
                   dict(id='full', family='ridge', grid={'alpha': [1, 10]}),
                   dict(id='ablated', family='ridge',
                        inputIndices=list(range(dimensions - 1)),
                        grid={'alpha': [1, 10]})]
  task = tmp_path / 'task.json';
  task.write_text(json.dumps(raw));
  result = run_task(task)
  assert result['uniqueTheta'] == 12 and result['outputs'] == 3 and (
        tmp_path / 'result/proposals.json').exists()
  validation = json.loads((tmp_path / 'result/validation.json').read_text())
  for fold in validation['outer']: assert not set(fold['trainTheta']) & set(
      fold['heldTheta'])


def test_same_rows_four_vs_five_ablation_and_variation_gate(tmp_path):
  rng = np.random.default_rng(7);
  x = rng.normal(size=(100, 5));
  y = x[:, 4:5]
  configs = expand([ModelConfig(id='full', family='linear'),
                    ModelConfig(id='ablated', family='linear',
                                inputIndices=(0, 1, 2, 3))])
  full = Model(configs[0], 1).fit(x, y);
  ablated = Model(configs[1], 1).fit(x, y)
  assert np.mean((full.predict(x) - y) ** 2) < 1e-20 and np.mean(
    (ablated.predict(x) - y) ** 2) > .5
  data(tmp_path, 5);
  raw = spec(tmp_path, 5).model_dump(mode='json');
  raw['requireVaryingParameters'] = ['p4']
  records = json.loads((tmp_path / 'rows.json').read_text())
  for row in records: row['theta'][4] = .5;row['thetaId'] = theta_id(
      row['theta'])
  (tmp_path / 'rows.json').write_text(json.dumps(records));
  path = tmp_path / 'fixed.json';
  path.write_text(json.dumps(raw))
  with pytest.raises(ValueError, match='measurement required'): run_task(path)


def test_json_screen_declares_families_not_candidate_weights():
  p = Path(__file__).resolve().parents[
        1] / 'tasks/live25-fifth-term-screen.json';
  task = ScreenTask.model_validate_json(p.read_text())
  assert len(task.families) == 4 and task.levels == (-1, 0, 1) and len(
    task.fixtures) == 12 and task.blocks == 2


def test_prepared_extensions_java_config_and_export_parity(tmp_path):
  from types import SimpleNamespace
  from tests.test_parameter_tuning import assert_java_runtime_and_export_parity
  root = Path(__file__).resolve().parents[3]
  task = root / 'python/pareto-weight-calibration/tasks/live25-fifth-term-screen.json'
  definition = json.loads(task.read_text())
  manifest_path = root / definition[
    'outputDirectory'] / 'candidate_manifest.json'
  if not manifest_path.exists():
    pytest.skip('local prepared screen unavailable')
  manifest = json.loads(manifest_path.read_text())
  policies = manifest['policies'] + [dict(function=manifest['anchor'])]
  runtime_spec = SimpleNamespace(
    runtime=dict(supportPoints=definition['supportPoints']))
  assert_java_runtime_and_export_parity(runtime_spec, policies, tmp_path)
