"""Reviewed JSON-only joint search contracts; no scheduler execution."""
from copy import deepcopy
import json
from pathlib import Path
import pytest
from pareto_weight_calibration.parameter_tuning import construct
from pareto_weight_calibration.surrogate_spec import SurrogateTask
from pareto_weight_calibration.training_runner import main

TASKS = Path(__file__).resolve().parents[1] / 'tasks/live25-joint5d-v1'


@pytest.mark.parametrize('family,index',
                         [('park-body', 3), ('park-phr-body', 6)])
def test_joint_five_dimensions_exact_center_and_single_added_term(family,
    index):
  task = SurrogateTask.model_validate_json(
    (TASKS / (family + '.json')).read_text())
  anchor = json.loads((TASKS / 'inputs/anchor-7931.json').read_text())
  assert len(task.parameters) == 5 and task.parameters[
    -1].path == f'/parkCoefficients/{index}'
  assert task.parameters[-1].bounds == (-0.30010459245033816,
                                        0.30010459245033816)
  values = [p.center for p in task.parameters]
  assert values[-1] == 0 and construct(task.parameters, anchor,
                                       values) == anchor
  for end in task.parameters[-1].bounds:
    changed = construct(task.parameters, anchor, values[:-1] + [end])
    changed['parkCoefficients'][index] = anchor['parkCoefficients'][index]
    assert changed == anchor
  assert task.proposal.power == 13 and task.proposal.reliability.enabled
  assert len(
    task.dataset.fixtures) == 18 and task.proposal.benchmark.blocks == 2
  assert [c['id'] for c in task.proposal.benchmark.controls] == ['POLICY_OFF',
                                                                 'live-25-center',
                                                                 'anchor-7931']
  assert task.proposal.benchmark.controls[0]['function'] is None


@pytest.mark.parametrize('family', ['park-body', 'park-phr-body'])
def test_ablation_omits_input_only_and_generic_json_cli_accepts_both(family,
    capsys):
  full = json.loads((TASKS / (family + '.json')).read_text())
  ablated = json.loads((TASKS / (family + '-4d-ablation.json')).read_text())
  for key in ['parameters', 'dataset', 'responses', 'validation',
              'seed']: assert full[key] == ablated[key]
  for f, a in zip(full['models'], ablated['models']):
    a = deepcopy(a);
    assert a.pop('inputIndices') == [0, 1, 2, 3]
    f = deepcopy(f);
    f.pop('inputIndices', None);
    assert a == f
  for suffix in ['', '-4d-ablation']:
    path = TASKS / (family + suffix + '.json')
    main(['--task', str(path), '--dry-run'])
    result = json.loads(capsys.readouterr().out)
    assert result['parameters'] == 5 and result['configurations'] == 80 and \
           result['outputs'] == 48
