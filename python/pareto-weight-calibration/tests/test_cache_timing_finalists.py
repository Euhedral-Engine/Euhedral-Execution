"""Frozen finalist numeric parity, including the actual primitive runtime evaluator."""
import json, math, subprocess
import pytest
from pareto_weight_calibration.cache_timing import ROOT, evaluate
from pareto_weight_calibration.runtime_export import render_cache_timing


@pytest.mark.parametrize('policy_id', ['live-25', 'live-12'])
def test_frozen_finalist_runtime_export_rounding_clamping_and_fallback(tmp_path,
    policy_id):
  manifest = json.loads((
                            ROOT / 'benchmarks/src/test/resources/cache-timing/live-v2/candidate_manifest.json').read_text())
  config = next(
      p['function'] for p in manifest['policies'] if p['id'] == policy_id)
  export = render_cache_timing(config)
  assert export == (
      ROOT / f'benchmarks/src/test/resources/cache-timing/live-v2/evaluators/{policy_id}/CacheTimingEvaluator.java').read_text()
  (tmp_path / 'CacheTimingEvaluator.java').write_text(export)
  inputs = [(c, p, math.expm1(b)) for c, p, b in
            manifest['grid'] + manifest['interior']]
  inputs += [(float('nan'), 1, 0), (2, 1, 0), (.5, 5, 0), (.5, -1, 0),
             (.5, 1, -1), (.5, 1, float('inf'))]
  literal = lambda x: 'Double.NaN' if math.isnan(
    x) else 'Double.POSITIVE_INFINITY' if math.isinf(x) else repr(float(x))
  args = [json.dumps(config['normalizationVersion'])] + [
    'java.util.List.of(' + ','.join(literal(x) for x in config[k]) + ')' for k
    in ['means', 'scales', 'supportMin', 'supportMax', 'parkCoefficients',
        'halfLifeCoefficients']] + [str(config[k]) + 'L' for k in
                                    ['parkReferenceNanos',
                                     'halfLifeReferenceNanos', 'parkMinNanos',
                                     'parkMaxNanos', 'halfLifeMinNanos',
                                     'halfLifeMaxNanos']]
  source = '''import io.euhedral_execution.core.config.CacheTimingFunctionConfig;
class Check { public static void main(String[] args) {
var config=new CacheTimingFunctionConfig(''' + ','.join(args) + ''');
double[][] inputs={''' + ','.join(
      '{' + ','.join(literal(x) for x in point) + '}' for point in inputs) + '''};
for(var x:inputs) System.out.println(config.parkNanos(x[0],x[1],x[2],15000L)+","+config.halfLifeNanos(x[0],x[1],x[2],1000000L)+","+CacheTimingEvaluator.parkNanos(x[0],x[1],x[2],15000L)+","+CacheTimingEvaluator.halfLifeNanos(x[0],x[1],x[2],1000000L));
}}'''
  (tmp_path / 'Check.java').write_text(source)
  subprocess.run(['mise', 'exec', '--', 'javac', '-d', str(tmp_path), str(
    ROOT / 'euhedral-core/src/main/java/io/euhedral_execution/core/config/CacheTimingFunctionConfig.java'),
                  str(tmp_path / 'CacheTimingEvaluator.java'),
                  str(tmp_path / 'Check.java')], cwd=ROOT, check=True,
                 capture_output=True)
  result = subprocess.check_output(
      ['mise', 'exec', '--', 'java', '-cp', str(tmp_path), 'Check'], cwd=ROOT,
      text=True)
  expected = [evaluate(config, *point) for point in inputs]
  assert [tuple(map(int, line.split(','))) for line in result.splitlines()] == [
    pair + pair for pair in expected]
  assert all(
      15000 <= p <= 814375 and 250000 <= h <= 2000000 for p, h in expected)
