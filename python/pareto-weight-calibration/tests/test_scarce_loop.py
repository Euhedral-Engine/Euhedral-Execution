"""Scarce reference semantics and explicit ancestry, independent of CACHE dimensions."""

from copy import deepcopy
import json, math
from pathlib import Path
import numpy as np
import pytest
from tests.test_parameter_loop import task
from pareto_weight_calibration.parameter_loop import (
  load_task,
  run_task,
  trial_plan,
  harness_for,
)
from pareto_weight_calibration.loop_data import (
  compatible_families,
  policy_id,
  ForkStore,
)
from pareto_weight_calibration.loop_search import (
  preference,
  seed_regions,
  propose,
  update_regions,
)
from pareto_weight_calibration.loop_references import (
  matched_panels,
  scored_panels,
  elite_archive,
  lineage,
  radius_update,
  model_features,
  model_parameters,
)
from pareto_weight_calibration.parameter_tuning import normalize


def scarce_task(tmp_path, dimension=3):
  path, records = task(tmp_path, dimension)
  raw = json.loads(path.read_text())
  raw["fixtures"] = raw["fixtures"][:1]
  raw["responses"] = raw["responses"][:1]
  raw["preference"].update(objective="scarce", guardrailTargets=[])
  raw["benchmark"].update(referenceMode="incumbent", offSentinel="scarce")
  path.write_text(json.dumps(raw))
  spec, _ = load_task(path)
  return path, spec, records


def test_plentiful_never_changes_scarce_fitness(tmp_path):
  path, records = task(tmp_path)
  spec, _ = load_task(path)
  spec = spec.model_copy(
      update={
        "preference": spec.preference.model_copy(update={"objective": "scarce"})
      }
  )
  x = np.array([[0.1, -0.95], [0.03, 0.95]])
  a = preference(spec, x)
  x[:, 1] = [100, -100]
  b = preference(spec, x)
  np.testing.assert_equal(a, b)
  assert a[1].shape == (2, 4)


def test_exactly_one_off_sentinel_and_full_live_blocks(tmp_path):
  _, spec, records = scarce_task(tmp_path)
  center = seed_regions(spec, records)[0]
  arms = [dict(policyId="POLICY_OFF", function=None), center]
  plan = trial_plan(spec, arms, tmp_path, 0)
  assert len(plan) == 3
  assert sum(t["arm"]["function"] is None for t in plan) == 1
  assert {t["blockId"] for t in plan if t["arm"]["function"]} == {"0", "1"}


def test_pairwise_references_are_matched_and_model_inputs_identify_them(
    tmp_path):
  _, spec, records = scarce_task(tmp_path, 5)
  center = seed_regions(spec, records)[0]
  new = []
  for r in records:
    if r["workloadId"] != "scarce" or r["function"] is None:
      continue
    q = deepcopy(r)
    q["referencePolicyId"] = center["policyId"]
    new.append(q)
  panels = matched_panels(spec, new, spec.families[0], training=True)
  assert panels and all(p["referencePolicyId"] != "POLICY_OFF" for p in panels)
  f = spec.families[0]
  theta = np.array([center["theta"]])
  off = model_features(spec, f, theta, None)
  live = model_features(spec, f, theta, center["function"])
  assert off.shape[1] == len(model_parameters(spec, f)) == 11
  assert not np.array_equal(off, live)
  # Exact theta stays grouped across campaigns and distinct reference descriptions.
  assert (
      len({p["thetaId"] for p in panels if
           p["policyId"] == center["policyId"]}) == 1
  )
  assert all(p["campaignId"] in p["rowId"] for p in panels)


def test_lineage_radius_and_shared_family_are_explicit(tmp_path):
  _, spec, records = scarce_task(tmp_path, 2)
  old = seed_regions(spec, records)[0]
  old["radius"] = 0.21
  family = spec.families[0]
  parent = np.array(normalize(family.parameters, old["theta"]))
  for delta, decision, radius in [
    (0.10, "INTERIOR_SHRINK", 0.147),
    (0.20, "EDGE_MOVE", 0.21),
  ]:
    child = dict(unit=(parent + delta).tolist())
    origin = lineage(family, child, old, parent, "guided")
    event = radius_update(spec, dict(old, policyId="child"), origin)
    assert event["decision"] == decision
    assert event["newRadius"] == pytest.approx(radius)
  event = radius_update(
      spec,
      dict(old, policyId="global"),
      lineage(family, {"unit": [0.2, 0.3]}, None, None, "global_random"),
  )
  assert (
      event["decision"] == "NEW_BASIN"
      and event["newRadius"] == spec.search.newBasinRadius
  )
  assert radius_update(spec, old, retained=True)["newRadius"] == pytest.approx(0.147)
  # A zero-plane function compatible with two schemas retains its generating schema.
  other = family.model_copy(update={"id": "other"})
  spec = spec.model_copy(update={"families": (family, other)})
  childfn = {"weights": [0.1, 0.1]}
  pid = policy_id(childfn)
  origin = lineage(other, {"unit": [0.55, 0.55]}, old, parent, "guided")
  rr = []
  for block in ["0", "1"]:
    for fn, v in [(old["function"], 100), (childfn, 110)]:
      rr.append(
          dict(
              rowId=f"new/{block}/{policy_id(fn)}",
              campaignId="new",
              policyId=policy_id(fn),
              function=fn,
              families=compatible_families(spec, fn),
              workloadId="scarce",
              blockId=block,
              rawThroughput=v,
              windows=[v],
              referencePolicyId=old["policyId"],
          )
      )
  updated, improved, _ = update_regions(
      spec, [old], rr, "new", [dict(policyId=pid, lineage=origin)]
  )
  assert improved and updated[0]["family"] == "other"
  assert updated[0]["radius"] == pytest.approx(0.147)


@pytest.mark.parametrize("dimension", [2, 5])
def test_two_round_scarce_loop_resume_lineage_elites_and_sentinel(
    tmp_path, capsys, dimension
):
  path, spec, records = scarce_task(tmp_path, dimension)
  result = run_task(path)
  assert (
      result["attempts"] == 18
  )  # (one incumbent + three new) * one fixture * two blocks + sentinel, twice
  state = json.loads((tmp_path / "out/state.json").read_text())
  assert state["elites"] and len(state["elites"]) <= 5
  for n in range(2):
    directory = tmp_path / f"out/round-{n:03d}"
    ps = json.loads((directory / "proposals.json").read_text())["candidates"]
    assert all("lineage" in p for p in ps)
    summary = json.loads((directory / "round-summary.json").read_text())
    assert summary["searchUpdates"] and summary["offSentinel"]["throughput"] > 0
    assert summary["currentIncumbent"]["referencePolicyId"] != "POLICY_OFF"
    assert summary["currentIncumbent"]["broadPlentifulPercent"] is None
  before = (tmp_path / "out/forks.sqlite").stat().st_size
  run_task(path, resume=True)
  assert (tmp_path / "out/forks.sqlite").stat().st_size == before
  output = capsys.readouterr().out
  assert (
      "CACHE scarce-policy tuner" in output
      and "SEARCH UPDATE" in output
      and "ELITE ARCHIVE" in output
  )


def test_network_elites_retain_dropped_policy_without_borrowing_off(tmp_path):
  _, spec, records = scarce_task(tmp_path, 2)
  initial = elite_archive(spec, records)
  old = initial[0]
  later = []
  for block in ["0", "1"]:
    for fn, v in [(old["function"], 200), ({"weights": [0.1, 0.1]}, 210)]:
      later.append(
          dict(
              rowId=f"later/{block}/{policy_id(fn)}",
              campaignId="later",
              policyId=policy_id(fn),
              function=fn,
              families=compatible_families(spec, fn),
              workloadId="scarce",
              blockId=block,
              rawThroughput=v,
              windows=[v],
              referencePolicyId=old["policyId"],
          )
      )
  result = elite_archive(spec, records + later)
  assert {r["policyId"] for r in initial} <= {r["policyId"] for r in result}
  assert result[0]["policyId"] == policy_id({"weights": [0.1, 0.1]})
  assert result[0]["workloadReturns"]["scarce"] == pytest.approx(
      math.log(1.04 * 1.05)
  )
  assert scored_panels(spec, later)[0]["workloadReturns"][
           "scarce"] == pytest.approx(
      math.log(1.05)
  )


def test_proposals_record_parent_radius_with_no_models(tmp_path):
  _, spec, records = scarce_task(tmp_path, 4)
  old = seed_regions(spec, records)[0]
  old["radius"] = 0.147
  p = propose(spec, records, [old], tmp_path, 1)
  for row in p["candidates"]:
    origin = row["lineage"]
    if row["role"] == "global_random":
      assert origin["parentPolicyId"] is None
    else:
      assert (
          origin["parentPolicyId"] == old["policyId"]
          and origin["parentRadius"] == 0.147
      )
      assert origin["searchFamily"] == row["family"]
      assert origin["normalizedEdgeFraction"] <= 1


def test_real_task_defaults_off_bypass_and_gate():
  root = Path(__file__).resolve().parents[3]
  spec, _ = load_task(
      root / "python/pareto-weight-calibration/tasks/cache-scarce-loop.json"
  )
  assert len(spec.fixtures) == 9 and not spec.preference.guardrailTargets
  assert (
      spec.benchmark.blocks == 2
      and spec.budgets.beamWidth == 2
      and spec.budgets.eliteSize == 5
  )
  arms = [dict(policyId="POLICY_OFF", function=None)] + [
    dict(policyId=str(i), function=spec.families[0].template) for i in range(9)
  ]
  plan = trial_plan(spec, arms, root, 0)
  assert len(plan) == 163
  for trial in plan[:3]:
    cfg = harness_for(spec, trial, root / "unused", "test")["trials"][0][
      "calibrationConfig"
    ]
    assert cfg["cacheScarcityGateEnabled"] == (
          trial["arm"]["function"] is not None)
    assert cfg["forcedActiveParticipantCount"] is None


def test_reference_denominator_cannot_leak_held_theta_into_training(tmp_path):
  from pareto_weight_calibration.surrogate_tournament import Tournament

  tournament = object.__new__(Tournament)
  tournament.groups = np.array(["a", "a", "b", "b", "c", "c", "d", "d"])
  tournament.reference_groups = np.array(
      ["OFF", "b", "OFF", "a", "OFF", "d", "OFF", "c"]
  )
  for train, held in tournament.grouped_folds(np.arange(8), 2, 42):
    assert not set(tournament.groups[train]) & set(tournament.groups[held])
    assert not set(tournament.reference_groups[train]) & set(
        tournament.groups[held]
    )
    assert len(train) > 0


def test_import_sqlite_keeps_old_database_and_rejects_wrong_mode(tmp_path):
  from pareto_weight_calibration.loop_references import import_stores

  _, spec, records = scarce_task(tmp_path)
  cfg = spec.benchmark.harness["trials"][0]["calibrationConfig"]
  rows = []
  for r in records:
    q = deepcopy(r)
    q["config"] = dict(
        cfg,
        cpuSet=[0],
        parallelSources=1 if q["workloadId"] == "scarce" else 2,
        workUnits=0,
        orderedSources=0,
    )
    q["windows"] = [q["rawThroughput"]] * 2
    rows.append(q)
  old = ForkStore(tmp_path / "old.sqlite")
  old.append(rows)
  old.close()
  source = (tmp_path / "old.sqlite").read_bytes()
  spec = spec.model_copy(update={"historicalStores": ("old.sqlite",)})
  new = ForkStore(tmp_path / "new.sqlite")
  audit = import_stores(spec, tmp_path, new)
  assert audit["included"] == len(records) // 2
  assert all(r["workloadId"] == "scarce" for r in new.rows())
  assert (tmp_path / "old.sqlite").read_bytes() == source
  assert import_stores(spec, tmp_path, new) == audit  # idempotent
  new.close()


def test_lineage_validation_rejects_wrong_parent_radius(tmp_path):
  _, spec, records = scarce_task(tmp_path, 2)
  old = seed_regions(spec, records)[0]
  child = {"weights": [0.1, 0.1]}
  pid = policy_id(child)
  rr = []
  for fn, v in [(old["function"], 100), (child, 110)]:
    rr.append(
        dict(
            rowId=policy_id(fn),
            campaignId="new",
            policyId=policy_id(fn),
            function=fn,
            families=compatible_families(spec, fn),
            workloadId="scarce",
            blockId="0",
            rawThroughput=v,
            referencePolicyId=old["policyId"],
        )
    )
  origin = lineage(
      spec.families[0],
      {"unit": [0.55, 0.55]},
      dict(old, radius=0.99),
      [0.5, 0.5],
      "guided",
  )
  with pytest.raises(ValueError, match="parent/radius"):
    update_regions(spec, [old], rr, "new", [dict(policyId=pid, lineage=origin)])


def test_two_center_beam_and_no_radius_reset_on_retention(tmp_path):
  _, spec, records = scarce_task(tmp_path, 2)
  spec = spec.model_copy(
      update={"budgets": spec.budgets.model_copy(update={"beamWidth": 2})}
  )
  centers = seed_regions(spec, records)
  assert len(centers) == 2
  centers[0]["radius"] = 0.147
  centers[1]["radius"] = 0.21
  rows = []
  for block in ["0", "1"]:
    for r in centers:
      rows.append(
          dict(
              rowId=f"new/{block}/{r['policyId']}",
              campaignId="new",
              policyId=r["policyId"],
              function=r["function"],
              families=compatible_families(spec, r["function"]),
              workloadId="scarce",
              blockId=block,
              rawThroughput=100,
              referencePolicyId=centers[0]["policyId"],
          )
      )
  updated, improved, ranked = update_regions(spec, centers, rows, "new")
  assert not improved and len(updated) == 2
  assert [r["radius"] for r in updated] == pytest.approx([0.1029, 0.147])
  assert all(r["searchUpdate"]["decision"] == "RETAIN_SHRINK" for r in updated)


def test_remeasured_incumbent_does_not_reapply_its_original_ancestry(tmp_path):
  _, spec, records = scarce_task(tmp_path, 2)
  spec = spec.model_copy(
      update={"budgets": spec.budgets.model_copy(update={"beamWidth": 2})}
  )
  centers = seed_regions(spec, records)
  centers[0]["radius"] = 0.147
  old_origin = dict(
      parentPolicyId="no-longer-in-beam", parentRadius=0.3,
      searchFamily="family"
  )
  centers[0]["lineage"] = old_origin
  fn = {"weights": [0.15, 0.15]}
  pid = policy_id(fn)
  family = spec.families[0]
  child_origin = lineage(
      family,
      {"unit": normalize(family.parameters, [0.15, 0.15])},
      centers[0],
      normalize(family.parameters, centers[0]["theta"]),
      "guided",
  )
  rows = []
  for block in ["0", "1"]:
    for function, v in [(r["function"], 100) for r in centers] + [(fn, 101)]:
      rows.append(
          dict(
              rowId=f"new/{block}/{policy_id(function)}",
              campaignId="new",
              policyId=policy_id(function),
              function=function,
              families=compatible_families(spec, function),
              workloadId="scarce",
              blockId=block,
              rawThroughput=v,
              referencePolicyId=centers[0]["policyId"],
          )
      )
  updated, improved, _ = update_regions(
      spec, centers, rows, "new",
      centers + [dict(policyId=pid, lineage=child_origin)]
  )
  assert improved and len(updated) == 2
  incumbent = next(
      r for r in updated if r["policyId"] == centers[0]["policyId"])
  assert incumbent["searchUpdate"]["decision"] == "RETAIN_SHRINK"
  assert incumbent["radius"] == pytest.approx(0.1029)
