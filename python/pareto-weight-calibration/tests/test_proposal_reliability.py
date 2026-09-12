"""Proposal authority and retrospective selection are generic and separate from benchmark truth."""

from copy import deepcopy
import json
from pathlib import Path
import numpy as np
import pytest
from pareto_weight_calibration.surrogate_spec import ReliabilityPolicy, \
  SurrogateTask
from pareto_weight_calibration.proposal_reliability import (
  classify,
  policy_view,
  history_exclusions,
  authority_table,
)
from pareto_weight_calibration.reliable_proposals import (
  nondominated,
  choose_regions,
  prepare_benchmarks,
)
from pareto_weight_calibration.surrogate_tournament import run_task
from tests.test_surrogate_tournament import spec, data


def evidence(rmse=0.01, rank=0.6, regret=0.005, recall=0.7):
  return dict(
      metrics=dict(
          rmse=rmse,
          mae=0.008,
          regret=regret,
          spearman=rank,
          topKRecall=recall,
          thetas=19,
      ),
      baselines=dict(mean=dict(rmse=0.03, regret=0.05), linear=dict(rmse=0.02)),
      coverage=1.0,
      biasLog=0.002,
      campaignBiasLog={"a": 0.002, "b": 0.003},
  )


def test_hard_soft_unresolved():
  p = ReliabilityPolicy(enabled=True)
  assert classify(evidence(), p)["reliabilityClass"] == "HARD"
  assert classify(evidence(rmse=0.2), p)["reliabilityClass"] == "SOFT"
  assert (
      classify(evidence(rmse=0.2, rank=-0.1, regret=0.05, recall=0), p)[
        "reliabilityClass"
      ]
      == "UNRESOLVED"
  )
  missing = evidence()
  missing["coverage"] = 0.2
  assert classify(missing, p)["reliabilityClass"] == "UNRESOLVED"


def task_with_authority(tmp_path):
  raw = spec(tmp_path).model_dump()
  raw["proposal"]["reliability"]["enabled"] = True
  raw["proposal"]["floorPercent"] = {"off:y0": -3, "off:y1": -3, "off:y2": -3}
  task = SurrogateTask.model_validate(raw)
  values = [
    evidence(),
    evidence(rmse=0.2),
    evidence(rmse=0.2, rank=-0.1, regret=0.05, recall=0),
  ]
  names = ["off:y0", "off:y1", "off:y2"]
  return task, names, authority_table(task, values, names)


def test_only_reliable_floor_vetoes_and_soft_risk_visible(tmp_path):
  task, names, a = task_with_authority(tmp_path)
  pred = np.array([[0.0, -0.2, -0.8], [-0.1, 0.0, 0.0]])
  v = policy_view(task, pred, np.zeros_like(pred), names, a)
  assert v["eligible"].tolist() == [True, False]
  assert v["risk"]["off:y1"][0] > 0 and "off:y2" not in v["risk"]
  assert v["treatments"]["off:y2"] == "unresolved_annotation"
  assert v["directionSources"]["off:y2"] is None
  assert policy_view(task, pred, np.zeros_like(pred), names, a, old=True)[
           "eligible"
         ].tolist() == [False, False]


def test_center_direction_and_off_authority_are_independent(tmp_path):
  raw = spec(tmp_path).model_dump()
  raw["systems"] = ["center", "off"]
  raw["proposal"]["reliability"].update(
      enabled=True, directionAlternatives={"off:y0": ["center:y0", "off:y0"]}
  )
  raw["proposal"]["floorPercent"] = {"off:y0": -3, "center:y0": -2}
  t = SurrogateTask.model_validate(raw)
  names = [s + ":" + r.name for s in t.systems for r in t.responses]
  weak = evidence(0.2, -0.1, 0.05, 0)
  ev = [evidence()] + [weak] * 5
  a = authority_table(t, ev, names)
  p = np.full((2, 6), -0.2)
  p[:, 0] = [0.1, -0.2]
  v = policy_view(t, p, np.zeros_like(p), names, a)
  assert v["eligible"].all() and v["directionSources"]["off:y0"] == "center:y0"
  assert (
      v["treatments"]["center:y0"] == "soft_penalty"
      and v["treatments"]["off:y0"] == "unresolved_annotation"
  )


def test_pareto_ties_keep_separated_theta_and_diverse_basins(tmp_path):
  assert nondominated([[1, 0], [0, 1], [0, 0], [1, 0]]).tolist() == [0, 1, 3]
  assert nondominated(np.zeros((4, 2))).tolist() == list(range(4))
  task, names, a = task_with_authority(tmp_path)
  u = np.array([[0.0, 0.0], [0.1, 0.1], [0.8, 0.8], [1.0, 1.0]])
  v = policy_view(task, np.zeros((4, 3)), np.zeros((4, 3)), names, a)
  chosen, roles, basins = choose_regions(task, u, np.arange(4), v, names,
                                         np.zeros(4))
  assert len(set(basins[chosen])) == 2
  assert all(
      np.linalg.norm(u[i] - u[j]) >= task.proposal.minProposalDistance
      for ix, i in enumerate(chosen)
      for j in chosen[ix + 1:]
  )


def test_history_excludes_measured_and_repeated_bad_without_discarding_rows(
    tmp_path):
  raw = spec(tmp_path).model_dump()
  raw["proposal"]["historyRisk"] = dict(
      radius=0.2, minBlocks=2, measuredFloors={"off:y0": -3}
  )
  t = SurrogateTask.model_validate(raw)
  rows = [
    dict(
        rowId=f"a/{i}",
        campaignId="a",
        thetaId="theta",
        theta=[0.5, 0.5],
        blockId=i,
        targets={"off:y0": -0.2},
    )
    for i in range(2)
  ]
  result = history_exclusions(
      t, rows, np.array([[0.5, 0.5], [0.65, 0.5], [0.9, 0.9]])
  )
  assert result["eligible"].tolist() == [False, False, True]
  assert len(rows) == 2 and len(result["repeatedBad"]) == 1


def test_json_offline_pipeline_retrospective_and_determinism(tmp_path):
  raw = spec(tmp_path).model_dump()
  raw["proposal"]["reliability"]["enabled"] = True
  raw["proposal"]["retrospective"] = dict(
      enabled=True,
      seed=42,
      randomRepeats=10,
      batchPerFold=1,
      primaryTargets=["off:y0", "off:y1", "off:y2"],
      topologyTargets=["off:y0", "off:y1"],
      measuredGuardrailFloors={"off:y2": -3},
  )
  path = tmp_path / "task.json"
  path.write_text(json.dumps(raw))
  data(tmp_path)
  run_task(path)
  run_task(path, tmp_path / "second")
  for name in [
    "proposals.json",
    "output_reliability.json",
    "search_efficiency.json",
    "basins.json",
  ]:
    assert json.loads((tmp_path / "result" / name).read_text()) == json.loads(
        (tmp_path / "second" / name).read_text()
    )
  r = json.loads((tmp_path / "result/search_efficiency.json").read_text())
  assert {x["method"] for x in r["comparison"]} == {
    "blind_random",
    "old_hard_floor",
    "reliability_aware",
  }
  for fold in r["folds"]:
    assert not set(fold["heldTheta"]) & set(fold["trainingTheta"])
  p = json.loads((tmp_path / "result/proposals.json").read_text())
  assert p["selected"] and all(
      x["distanceToMeasured"] >= 0.1 for x in p["selected"])
  assert all(len(x["responses"]) == 3 for x in p["selected"])


def test_round4_retains_frozen_data_models_parameters_and_generates_only_json(
    tmp_path):
  root = Path(__file__).resolve().parents[3]
  parent = root / "python/pareto-weight-calibration/tasks"
  old = json.loads((parent / "live25-historical-tournament.json").read_text())
  new = json.loads((parent / "live25-reliability-round4.json").read_text())
  for key in ["dataset", "models", "parameters", "validation"]:
    assert old[key] == new[key]
  for key in [
    "seed",
    "power",
    "count",
    "minMeasuredDistance",
    "minProposalDistance",
    "floorPercent",
  ]:
    assert old["proposal"][key] == new["proposal"][key]
  # Completed presets were consolidated; materialize only this test's inputs in its temp directory.
  from pareto_weight_calibration.run_archive import read_file
  def available(name):
    if (root / name).exists(): return name
    archived = name.replace('src/test/resources/cache-timing',
                            'src/main/presets/cache-timing')
    target = tmp_path / 'historical-inputs' / Path(name).name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(read_file(root / new['dataset']['archive'], archived))
    return str(target)

  new['dataset']['centerArtifact'] = available(new['dataset']['centerArtifact'])
  benchmark = new['proposal']['benchmark']
  benchmark['templateHarness'] = available(benchmark['templateHarness'])
  for control in benchmark['controls']:
    if control.get('functionArtifact'): control['functionArtifact'] = available(
        control['functionArtifact'])
  s = SurrogateTask.model_validate(new)
  function = json.loads((root / s.dataset.centerArtifact).read_text())
  # Export an existing center only in this unit test; production proposals are optimizer outputs.
  result = prepare_benchmarks(
      s,
      root,
      tmp_path,
      [
        dict(
            id="synthetic-parity",
            function=function,
            theta=[p.center for p in s.parameters],
        )
      ],
  )
  assert result["jvmCount"] == 3 * 18 * 2
  harness = json.loads((tmp_path / "benchmark/harness.json").read_text())
  for trial in harness["trials"]:
    fixture = next(
        f
        for f in s.dataset.fixtures
        if f["workloadId"] == trial["labels"]["workloadId"]
    )
    assert trial["calibrationConfig"]["cpuSet"] == fixture["cpuSet"]
    if trial["labels"]["policyId"] == "POLICY_OFF":
      assert trial["calibrationConfig"]["cacheTimingFunction"] is None
    else:
      assert trial["calibrationConfig"]["cacheTimingFunction"] == function
  assert not list(tmp_path.rglob("*.java"))
  from pareto_weight_calibration.cache_timing_confirmation import check
  checked = check(tmp_path / 'benchmark', 'topologies')
  assert checked['stages']['topologies']['expectedForks'] == result['jvmCount']
  assert (tmp_path / 'benchmark/HANDOFF.md').is_file()
