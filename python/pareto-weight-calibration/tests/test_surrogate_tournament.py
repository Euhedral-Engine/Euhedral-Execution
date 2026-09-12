"""Offline contracts: arbitrary JSON dimensions, grouped evidence, and recoverable history."""

import json
from pathlib import Path
import tempfile
import numpy as np
import pytest
from pareto_weight_calibration.historical_response import (
  build,
  compatible_function,
  theta_id,
)
from pareto_weight_calibration.run_archive import pack, restore, verify, rows
from pareto_weight_calibration.surrogate_models import Model, expand
from pareto_weight_calibration.surrogate_spec import ModelConfig, SurrogateTask
from pareto_weight_calibration.surrogate_tournament import folds, metrics, \
  run_task
from pareto_weight_calibration.parameter_tuning import pareto_indices


def spec(root, dimensions=2):
  responses = [
    dict(name=f"y{i}", workloads=[f"w{i}"], role="scarce") for i in range(3)
  ]
  return SurrogateTask.model_validate(
      dict(
          id="synthetic",
          root=str(root),
          outputDirectory="result",
          seed=3,
          parameters=[
            dict(
                name=f"p{i}",
                path=f"/coefficients/{i}",
                center=0.5,
                bounds=[0.0, 1.0],
            )
            for i in range(dimensions)
          ],
          dataset=dict(adapter="response_rows", discovery=["rows.json"]),
          responses=responses,
          systems=["off"],
          models=[
            dict(id="mean", family="mean"),
            dict(id="linear", family="linear"),
            dict(id="ridge", family="ridge", degree=2,
                 grid={"alpha": [0.1, 1.0]}),
          ],
          validation=dict(outerFolds=3, innerFolds=2, campaignTransfer=True),
          proposal=dict(
              seed=10,
              power=6,
              count=3,
              clusters=2,
              explorationCount=0,
              objectives=[
                dict(name="first", targets=["off:y0"]),
                dict(name="second", targets=["off:y1"]),
              ],
              roles=[dict(name="recovery", targets=["off:y2"])],
          ),
      )
  )


def data(root, dimensions=2):
  rng = np.random.default_rng(3)
  out = []
  for i, theta in enumerate(rng.random((12, dimensions))):
    for campaign in ["a", "b"]:
      for block in [0, 1]:
        out.append(
            dict(
                rowId=f"{campaign}/{i}/{block}",
                campaignId=campaign,
                thetaId=theta_id(theta),
                theta=theta.tolist(),
                targets={
                  f"off:y{j}": float(
                      (1 if j != 1 else -1) * theta[0]
                      - 0.5 * theta[-1] ** 2
                      + (campaign == "b") * 0.01
                  )
                  for j in range(3)
                },
            )
        )
  (root / "rows.json").write_text(json.dumps(out))
  return out


def test_grouped_theta_keeps_campaign_repeats_together():
  groups = np.array(["same", "other", "same", "third", "fourth", "other"])
  seen = []
  for train, held in folds(groups, 3, 13):
    assert not set(groups[train]) & set(groups[held])
    seen.extend(held)
  assert sorted(seen) == list(range(len(groups)))
  assert all(
      np.array_equal(a, c) and np.array_equal(b, d)
      for (a, b), (c, d) in zip(folds(groups, 3, 13), folds(groups, 3, 13))
  )


@pytest.mark.parametrize("dimensions", [1, 3, 5])
def test_arbitrary_dimensions_json_and_repeated_rows(tmp_path, dimensions):
  task = spec(tmp_path, dimensions)
  records = data(tmp_path, dimensions)
  restored = SurrogateTask.model_validate_json(task.model_dump_json())
  result = build(restored, tmp_path)
  assert result["rows"] == records and len(result["rows"]) == 48
  configs = expand(task.models)
  assert len(configs) == 4
  x = np.array([r["theta"] for r in records])
  y = np.array([list(r["targets"].values()) for r in records])
  for mode in ["native", "independent"]:
    model = Model(dict(configs[-1], mode=mode), 3).fit(x, y)
    assert model.predict(x).shape == (48, 3)
    for _, pipeline, _ in model.parts:
      assert (
          pipeline.named_steps["polynomialfeatures"].n_output_features_
          == dimensions + dimensions * (dimensions + 1) // 2
      )


def test_exact_inactive_coefficient_compatibility(tmp_path):
  task = spec(tmp_path)
  center = {"coefficients": [0.5, 0.5, 1e-16], "bounds": [1, 2]}
  changed = {"coefficients": [0.25, 0.75, 1e-16], "bounds": [1, 2]}
  theta, reason = compatible_function(changed, center, task.parameters)
  assert theta == [0.25, 0.75] and reason is None
  changed["coefficients"][2] = 0.0
  assert compatible_function(changed, center, task.parameters)[0] is None


def test_archive_roundtrip_and_corruption(tmp_path):
  original = tmp_path / "experiments" / "cache-test"
  original.mkdir(parents=True)
  (original / "data.bin").write_bytes(bytes(range(256)) * 3)
  archive = tmp_path / "runs.tsv"
  result = pack(tmp_path, [original], archive)
  assert result["artifactFiles"] == 1
  with pytest.raises(ValueError, match="already exists"):
    pack(tmp_path, [original], archive)
  destination = tmp_path / "restore"
  restore(archive, destination)
  assert (destination / "experiments/cache-test/data.bin").read_bytes() == (
      original / "data.bin"
  ).read_bytes()
  text = archive.read_text().replace(result["archiveSha256"], "bad")
  artifact = next(r for r in rows(archive) if r["recordType"] == "artifact")
  archive.write_text(text.replace(artifact["sha256"], "0" * 64))
  with pytest.raises(ValueError, match="checksum"):
    verify(archive)


def test_mean_ties_are_not_arbitrary_learned_action():
  result = metrics(np.array([0.0, 1.0, 2.0]), np.zeros(3), ["a", "b", "c"], 1)
  assert result["regret"] == 1 and result["topKRecall"] == pytest.approx(1 / 3)
  assert set(pareto_indices(np.array([[1, 0], [0, 1], [0, 0]]))) == {0, 1}


def test_full_json_tournament_and_deterministic_proposals(tmp_path):
  task = spec(tmp_path)
  data(tmp_path)
  path = tmp_path / "task.json"
  path.write_text(task.model_dump_json())
  result = run_task(path)
  assert (
      result["outputs"] == 3
      and result["uniqueTheta"] == 12
      and result["selectedProposals"] > 0
  )
  output = tmp_path / "result"
  validation = json.loads((output / "validation.json").read_text())
  for fold in validation["outer"]:
    assert not set(fold["trainTheta"]) & set(fold["heldTheta"])
  proposals = json.loads((output / "proposals.json").read_text())
  assert all(
      p["distanceToMeasured"] >= task.proposal.minMeasuredDistance
      for p in proposals["selected"]
  )
  assert len({p["basin"] for p in proposals["selected"]}) == 2
  assert (output / "models.joblib").is_file() and (
      output / "ensemble_results.tsv"
  ).is_file()
  second = run_task(path, tmp_path / "second")
  again = json.loads((tmp_path / "second/proposals.json").read_text())
  assert proposals == again
  assert not second["failures"]




def test_generic_cli_accepts_json_without_source_changes(tmp_path, capsys):
  import sys
  from unittest.mock import patch
  from pareto_weight_calibration.training_runner import main

  task = spec(tmp_path, 5)
  data(tmp_path, 5)
  path = tmp_path / "task.json"
  path.write_text(task.model_dump_json())
  with patch.object(sys, "argv",
                    ["pareto-train", "--task", str(path), "--dry-run"]):
    main()
  output = json.loads(capsys.readouterr().out)
  assert (
      output["parameters"] == 5
      and output["outputs"] == 3
      and output["configurations"] == 4
  )


def test_model_registry_families_on_arbitrary_multioutput():
  x = np.random.default_rng(4).normal(size=(12, 3))
  y = np.column_stack([x[:, 0], x[:, 1] ** 2])
  declarations = [
    ("mean", {}),
    ("linear", {}),
    ("ridge", {}),
    ("elastic_net", {}),
    ("knn", {"n_neighbors": 3}),
    ("local_linear", {}),
    ("kernel_ridge", {"kernel": "matern"}),
    ("gp", {"kernel": "1.5", "ard": True, "optimizer": None}),
    ("random_forest", {"n_estimators": 3}),
    ("extra_trees", {"n_estimators": 3}),
    ("gradient_boosting", {"n_estimators": 3}),
    ("mlp", {"hidden_layer_sizes": [4], "max_iter": 10}),
    ("xgboost", {"n_estimators": 3}),
    ("catboost", {"iterations": 3}),
  ]
  for family, params in declarations:
    cfg = expand([ModelConfig(id=family, family=family, params=params)])[0]
    model = Model(cfg, 3).fit(x, y)
    assert np.isfinite(model.predict(x)).all(), family
    assert model.predict(x).shape == y.shape
