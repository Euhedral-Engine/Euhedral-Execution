"""Synthetic unit fits only. Never execute validation/fitting on frozen evidence."""

from copy import deepcopy
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import subprocess
import sys

import numpy as np
import pytest
from sklearn.pipeline import Pipeline
from threadpoolctl import threadpool_limits
import torch

from pareto_weight_calibration import model_tournament as runner
from pareto_weight_calibration import tournament_models as models
from pareto_weight_calibration.action_model import CACHE, DEFAULT, \
  INDETERMINATE, fold_influence
from pareto_weight_calibration.direct_side import (
  LEFT, RIGHT, _canonical_json, _write_json, fit_direct_classifier,
  predict_direct_side,
)
from pareto_weight_calibration.tournament_config import (
  MODEL_ORDER, frozen_candidate, grid_sha256, load_grid, parse_models,
)
from pareto_weight_calibration.types import DomainConfig
from .test_direct_side import _row

ROOT = Path(__file__).resolve().parents[3]


@pytest.fixture(scope="module", autouse=True)
def one_thread():
  previous = torch.get_num_threads()
  torch.set_num_threads(1)
  with threadpool_limits(limits=1):
    yield
  torch.set_num_threads(previous)


@pytest.fixture(scope="module")
def toy_rows():
  return [replace(_row(f"{family}-{k}", family, k=k,
                       action=DEFAULT if k <= 4 else CACHE, cost=5.0 + k,
                       productive_handles=2 + index / 4, body=3 + index / 4,
                       contention=0.1 + index / 10),
                  evidence_weight=0.4 + k / 20)
          for index, family in enumerate(("a", "b", "c", "d")) for k in
          range(2, 8)]


@pytest.fixture(scope="module", params=MODEL_ORDER)
def fitted(request, toy_rows):
  family = request.param
  return models.create_model(family).fit(toy_rows,
                                         load_grid()["models"][family][0])


def test_every_family_common_interface(fitted, toy_rows):
  assert isinstance(fitted, models.SideClassifier)
  score = fitted.predict_score(toy_rows)
  assert score.shape == (len(toy_rows),)
  assert np.isfinite(score).all()
  assert set(fitted.predict_action(toy_rows)) <= {DEFAULT, CACHE}
  probabilities = fitted.predict_probability(toy_rows)
  if fitted.family == "svm":
    assert probabilities is None
    assert fitted.pipeline.named_steps["model"].probability is False
  else:
    assert probabilities.shape == score.shape
    assert np.all((probabilities >= 0) & (probabilities <= 1))
    assert np.array_equal(probabilities > 0.5, score > 0)


def test_identical_action_and_side_semantics(fitted, monkeypatch, toy_rows):
  monkeypatch.setattr(fitted, "predict_score",
                      lambda rows: np.asarray([-1.0, 0.0, 1.0]))
  monkeypatch.setattr(fitted, "predict_probability", lambda rows: None)
  records = runner.prediction_records(fitted, toy_rows[:3],
                                      training_fold="test",
                                      config=fitted.config)
  assert [p["action"] for p in records] == [DEFAULT, DEFAULT, CACHE]
  assert [p["predictedSide"] for p in records] == [LEFT, LEFT, RIGHT]


def test_snapshot_round_trip_contains_preprocessing(fitted, toy_rows):
  snapshot = fitted.serialize_model()
  restored = models.deserialize_model(json.loads(_canonical_json(snapshot)),
                                      trusted=True)
  np.testing.assert_array_equal(restored.predict_score(toy_rows),
                                fitted.predict_score(toy_rows))
  assert restored.predict_action(toy_rows) == fitted.predict_action(toy_rows)
  assert _canonical_json(snapshot) == _canonical_json(fitted.serialize_model())
  assert snapshot["features"] == ["K", "pRatio", "logR", "body", "contention"]
  with pytest.raises(ValueError, match="trusted"):
    models.deserialize_model(snapshot)
  damaged = {**snapshot, "payloadSha256": "0" * 64}
  with pytest.raises(ValueError, match="checksum"):
    models.deserialize_model(damaged, trusted=True)


def test_fitting_is_seed_deterministic(fitted, toy_rows):
  again = models.create_model(fitted.family).fit(list(reversed(toy_rows)),
                                                 fitted.config)
  np.testing.assert_array_equal(again.predict_score(toy_rows),
                                fitted.predict_score(toy_rows))
  assert again.seed == 20260829


def test_held_out_prediction_cannot_mutate_preprocessing(fitted, toy_rows):
  before = fitted.serialize_model()
  held = [replace(toy_rows[0], family_id="held", pair_id="held", body_log=1000,
                  current_k=32, registered_workers=32, productive_handles=30)]
  fitted.predict_score(held)
  assert fitted.serialize_model() == before


def test_enforced_models_are_monotone_at_fixed_state(fitted, toy_rows):
  assert fitted.monotonicity in {"enforced", "partially supported",
                                 "not enforced"}
  if fitted.monotonicity == "enforced":
    fixed = [replace(toy_rows[0], current_k=k) for k in range(1, 8)]
    assert np.all(np.diff(fitted.predict_score(fixed)) >= -1e-12)
  if fitted.family in {"tree", "forest", "boosted_tree"}:
    estimator = fitted.pipeline.named_steps["model"]
    assert estimator.monotonic_cst == [1, 0, 0, 0, 0]
    assert estimator.class_weight is None
  if fitted.family == "boosted_tree":
    assert fitted.pipeline.named_steps["model"].early_stopping is False


@pytest.mark.parametrize("family", MODEL_ORDER)
def test_single_class_fold_and_indeterminate_rejection(family, toy_rows):
  config = load_grid()["models"][family][0]
  for action in (DEFAULT, CACHE):
    model = models.create_model(family).fit(
        [replace(r, observed_action=action) for r in toy_rows], config)
    assert model.predict_action(toy_rows) == [action] * len(toy_rows)
  with pytest.raises(ValueError, match="indeterminate"):
    models.create_model(family).fit([_row(action=INDETERMINATE)], config)


def test_weight_contract_uses_family_cap_and_physical_not_relative_cost():
  rows = [replace(_row("a1", "a", cost=10), evidence_weight=2),
          replace(_row("a2", "a", cost=20), evidence_weight=1),
          replace(_row("b1", "b", cost=40), evidence_weight=0.5)]
  influence, weights = models.training_weights(rows)
  np.testing.assert_allclose(influence, [2 / 3, 1 / 3, 0.5])
  raw = np.asarray([20 / 3, 20 / 3, 20])
  np.testing.assert_allclose(weights, raw / raw.mean())
  irrelevant = [replace(r, supported_relative_wrong_action_loss=100,
                        family_scale=0.01, influence_weight=999) for r in rows]
  np.testing.assert_array_equal(models.training_weights(irrelevant)[1], weights)


@pytest.mark.parametrize("family", ("polynomial", "spline", "tree", "forest",
                                    "boosted_tree", "svm"))
def test_library_sample_weights_and_scaling_weights_are_forwarded(family,
    toy_rows, monkeypatch):
  captured = {}

  def capture(self, x, y, **kwargs):
    captured.update(kwargs)
    return self

  monkeypatch.setattr(Pipeline, "fit", capture)
  models.create_model(family).fit(toy_rows, load_grid()["models"][family][0])
  influence, sample_weight = models.training_weights(toy_rows)
  np.testing.assert_array_equal(captured["model__sample_weight"], sample_weight)
  if family in {"polynomial", "spline", "svm"}:
    np.testing.assert_array_equal(captured["scale__sample_weight"], influence)


def test_linear_baseline_reuses_existing_objective(toy_rows):
  config = load_grid()["models"]["linear"][0]
  baseline = models.create_model("linear").fit(toy_rows, config)
  existing = fit_direct_classifier(toy_rows, "S0_BASE", domain=DomainConfig(),
                                   **config["params"])
  expected = predict_direct_side(existing, toy_rows)
  np.testing.assert_array_equal(baseline.predict_score(toy_rows),
                                [p["score"] for p in expected])
  np.testing.assert_array_equal(baseline.predict_probability(toy_rows),
                                [p["probabilityRight"] for p in expected])


def test_mlp_row_weights_reach_loss_and_gradients(toy_rows, monkeypatch):
  observed = []
  original = models.weighted_binary_loss

  def capture(logits, labels, weights):
    if not observed:
      observed.append(weights.detach().cpu().numpy().copy())
    return original(logits, labels, weights)

  monkeypatch.setattr(models, "weighted_binary_loss", capture)
  models.create_model("mlp").fit(toy_rows, load_grid()["models"]["mlp"][0])
  np.testing.assert_array_equal(observed[0],
                                models.training_weights(toy_rows)[1])
  logits = torch.zeros(2, dtype=torch.float64, requires_grad=True)
  loss = original(logits, torch.tensor([0., 1.]), torch.tensor([1., 9.]))
  loss.backward()
  np.testing.assert_allclose(logits.grad.numpy(), [0.05, -0.45])


def test_scaling_and_spline_knots_use_training_rows_only(toy_rows):
  train, held = runner.partition_families(toy_rows, ["d"])
  model = models.create_model("spline").fit(train,
                                            load_grid()["models"]["spline"][0])
  influence, _ = fold_influence(train)
  expected_mean = np.average(models.runtime_matrix(train), axis=0,
                             weights=influence)
  np.testing.assert_allclose(model.pipeline.named_steps["scale"].mean_,
                             expected_mean)
  extreme = [replace(r, body_log=1000) for r in held]
  before = model.serialize_model()
  model.predict_score(extreme)
  assert model.serialize_model() == before


def test_grouped_nested_plan_is_disjoint_and_exhaustive(toy_rows):
  # Split construction only; no fits or cross-validation execution.
  for family in ("a", "b", "c", "d"):
    outer_train, outer_held = runner.partition_families(toy_rows, [family])
    assert {r.family_id for r in outer_train}.isdisjoint({family})
    assert {r.family_id for r in outer_held} == {family}
    plan = runner.fold_plan(outer_train, 4)
    assert plan == runner.fold_plan(list(reversed(outer_train)), 4)
    ids = []
    for split in plan:
      assert family not in split["trainingFamilies"] + split["heldOutFamilies"]
      assert set(split["trainingFamilies"]).isdisjoint(split["heldOutFamilies"])
      assert set(split["trainingPairIds"]).isdisjoint(split["heldOutPairIds"])
      ids.extend(split["heldOutPairIds"])
    assert sorted(ids) == sorted(r.pair_id for r in outer_train)


def test_outer_holdout_never_reaches_tuning_or_fit(toy_rows, monkeypatch):
  seen = []
  config = load_grid()["models"]["tree"][0]

  def select(rows, family, candidates, plan, device):
    seen.append({r.family_id for r in rows})
    assert all(
        "d" not in p["trainingFamilies"] + p["heldOutFamilies"] for p in plan)
    return {"selectedConfig": config, "candidates": []}

  class Stub:
    family = "tree"

    def fit(self, rows, config):
      seen.append({r.family_id for r in rows})
      return self

    def predict_score(self, rows):
      return np.ones(len(rows))

    def predict_probability(self, rows):
      return np.full(len(rows), 0.75)

    def predict_action(self, rows):
      return [CACHE] * len(rows)

    def serialize_model(self):
      return {"actualDevice": "cpu"}

  monkeypatch.setattr(runner, "select_inner", select)
  monkeypatch.setattr(runner, "create_model", lambda *args: Stub())
  result = runner.run_outer_fold(toy_rows, "d", "tree", load_grid(), "cpu")
  assert seen == [{"a", "b", "c"}, {"a", "b", "c"}]
  assert {p["familyId"] for p in result["predictions"]} == {"d"}


def test_metrics_are_paired_and_directional():
  rows = [_row("a", "a", action=DEFAULT, cost=3),
          _row("b", "b", action=CACHE, cost=17)]
  predictions = [{"pairId": "b", "familyId": "b", "action": DEFAULT},
                 {"pairId": "a", "familyId": "a", "action": CACHE}]
  m = runner.evaluate_predictions(rows, predictions)
  assert m["supportedRelativeRegret"] == pytest.approx(0.1)
  assert m["familyBalancedSupportedRelativeRegret"] == pytest.approx(0.1)
  assert m["worstFamilySupportedRelativeRegret"] == pytest.approx(0.17)
  assert m["falseCache"]["supportedRelativeRegret"] == pytest.approx(0.015)
  assert m["falseDefault"]["supportedRelativeRegret"] == pytest.approx(0.085)
  assert m["falseCache"]["largestSingleSupportedLoss"] == 3
  assert m["falseDefault"]["largestSingleSupportedLoss"] == 17
  assert m["defaultAccuracy"] == m["cacheAccuracy"] == 0
  assert m["pooledEvidenceWeightedSideAccuracy"] == 0
  assert m["familyBalancedEvidenceWeightedSideAccuracy"] == 0
  with pytest.raises(ValueError, match="exactly once"):
    runner.evaluate_predictions(rows, predictions[:1] * 2)


def test_inner_selection_prioritizes_regret_over_accuracy():
  def candidate(regret, accuracy):
    return {"config": {"complexity": 1, "id": "test"}, "metrics": {
      "supportedRelativeRegret": regret,
      "worstFamilySupportedRelativeRegret": 0,
      "falseDefault": {"supportedRelativeRegret": 0},
      "falseCache": {"supportedRelativeRegret": 0},
      "familyBalancedEvidenceWeightedSideAccuracy": accuracy}}

  assert runner.inner_rank_key(candidate(0.01, 0.6)) < runner.inner_rank_key(
    candidate(0.02, 0.99))


def test_frozen_grid_parses_all_candidates_without_fitting():
  grid = load_grid()
  assert sum(map(len, grid["models"].values())) == 28
  assert grid["innerFolds"] == 4
  assert grid_sha256() == grid_sha256()
  for family in MODEL_ORDER:
    for candidate in grid["models"][family]:
      assert frozen_candidate(family, candidate) == candidate
      model = models.create_model(family)
      model.config = candidate
      model.seed = grid["seed"]
      if isinstance(model, models.SklearnClassifier):
        estimator = model._pipeline().named_steps["model"]
        estimator._validate_params()
  mutated = deepcopy(grid["models"]["linear"][0])
  mutated["params"]["l2"] = 0.987
  with pytest.raises(ValueError, match="frozen"):
    frozen_candidate("linear", mutated)
  grid["models"]["linear"][0]["params"]["l2"] = 999
  assert load_grid()["models"]["linear"][0]["params"]["l2"] == 0.001


@pytest.mark.parametrize("value", ["", "all,linear", "linear,linear", "missing",
                                   "linear,"])
def test_invalid_model_lists_rejected(value):
  with pytest.raises(ValueError):
    parse_models(value)


def test_cli_parsing_does_not_execute(monkeypatch):
  assert parse_models("svm,linear,boosted_tree") == ("linear", "boosted_tree",
                                                     "svm")
  assert parse_models("all") == MODEL_ORDER
  args = runner.build_parser().parse_args(
      ["--models", "linear,boosted_tree,svm", "--device", "cuda",
       "--output-dir", "out"])
  assert args.device == "cuda" and args.output_dir == Path("out")
  invoked = []
  monkeypatch.setattr(runner, "run_tournament",
                      lambda *args, **kwargs: invoked.append((args, kwargs)))
  runner.main(["--models", "linear,boosted_tree,svm", "--device", "cpu"])
  assert invoked[0][1]["models"] == ("linear", "boosted_tree", "svm")
  with pytest.raises(SystemExit):
    runner.main(["--models", "unknown"])
  assert len(invoked) == 1


def test_script_help_is_safe():
  result = subprocess.run(
      [sys.executable, str(ROOT / "scripts/run_model_tournament.py"), "--help"],
      capture_output=True, text=True, check=True)
  assert "--models" in result.stdout and "--device" in result.stdout


def test_frozen_artifacts_unchanged_and_cohort_exact():
  rows, hashes = runner.load_frozen_dataset(ROOT)
  assert len(rows) == 102 and len({r.family_id for r in rows}) == 43
  assert all(r.decisive for r in rows)
  assert len(hashes) == 20
  assert hashes[str(
    runner.DATASET_PATH)] == "bee6cdcb324fb1c2c68788bf6cbb2783723fde617521dc09621af7ea9447e4a3"
  # Read the manifest only, never evaluate or refit the frozen reference here.


def test_output_cannot_overwrite_existing_or_frozen_files(tmp_path):
  with pytest.raises(ValueError, match="overlap"):
    runner.validate_output_dir(ROOT, ROOT / runner.DATASET_PATH.parent / "new")
  (tmp_path / "user-data.txt").write_text("keep")
  with pytest.raises(ValueError, match="never overwritten"):
    runner.validate_output_dir(ROOT, tmp_path)
  assert (tmp_path / "user-data.txt").read_text() == "keep"


def test_deterministic_artifact_and_digest_sidecar(tmp_path):
  first, second = tmp_path / "one.json", tmp_path / "two.json"
  _write_json(first, {"z": 1, "a": [1, 2]})
  _write_json(second, {"a": [1, 2], "z": 1})
  assert first.read_bytes() == second.read_bytes()
  assert first.with_name(
    first.name + ".sha256").read_text().strip() == hashlib.sha256(
    first.read_bytes()).hexdigest()


def test_result_artifact_schema_without_executing_runner(tmp_path):
  rows = [_row("a", "a", action=DEFAULT), _row("b", "b", action=CACHE)]
  predictions = [
    {"modelFamily": "linear", "pairId": r.pair_id, "familyId": r.family_id,
     "action": r.observed_action, "loss": {"wrongType": None}}
    for r in rows]
  metrics = runner.evaluate_predictions(rows, predictions)
  results = [
    {"modelFamily": "linear", "metrics": metrics, "predictions": predictions,
     "comparisonToCurrentDirect": runner.compare_reference(metrics, metrics)}]
  hashes = runner.write_result_artifacts(tmp_path, results, [], metrics)
  assert set(hashes) == {"modelResults", "outerLofo", "familyMetrics",
                         "false_cache", "false_default", "findings"}
  for path in sorted(tmp_path.iterdir()):
    if not path.name.endswith(".sha256"):
      assert path.with_name(
        path.name + ".sha256").read_text().strip() == hashlib.sha256(
        path.read_bytes()).hexdigest()
  assert json.loads((tmp_path / "tournament_model_results.json").read_text())[
           "models"][0]["modelFamily"] == "linear"
  assert json.loads((tmp_path / "tournament_false_cache.json").read_text())[
           "rows"] == []
  assert "No production winner" in (
        tmp_path / "tournament_findings.md").read_text()
  before = {p.name: p.read_bytes() for p in tmp_path.iterdir()}
  runner.write_result_artifacts(tmp_path, results, [], metrics)
  assert {p.name: p.read_bytes() for p in tmp_path.iterdir()} == before


def test_reference_uses_exact_saved_outer_cohort(tmp_path, monkeypatch):
  rows = [_row("a", "a", action=DEFAULT), _row("b", "b", action=CACHE)]
  predictions = [
    {"pairId": r.pair_id, "familyId": r.family_id, "action": r.observed_action}
    for r in rows]
  payload = {"predictions": predictions, "folds": [
    {"heldOutFamily": r.family_id,
     "trainingPairIdsHash": runner.pair_ids_hash(
         [other for other in rows if other.family_id != r.family_id]),
     "predictions": [p for p in predictions if p["familyId"] == r.family_id]}
    for r in rows]}
  path = tmp_path / runner.REFERENCE_PATH
  path.parent.mkdir(parents=True)
  path.write_text(json.dumps(payload))
  monkeypatch.setattr(models, "create_model", lambda *a, **k: pytest.fail(
    "reference must never be fitted"))
  assert runner.reference_predictions(tmp_path, rows) == predictions
  payload["folds"][0]["trainingPairIdsHash"] = "bad"
  path.write_text(json.dumps(payload))
  with pytest.raises(ValueError, match="cohort differs"):
    runner.reference_predictions(tmp_path, rows)


def test_only_declared_second_order_geometry(toy_rows):
  x = models.runtime_matrix(toy_rows)
  assert \
  models.ConstrainedQuadraticFeatures("interactions").fit_transform(x).shape[
    1] == 11
  quadratic = models.ConstrainedQuadraticFeatures("quadratic").fit_transform(x)
  assert quadratic.shape[1] == 15
  np.testing.assert_array_equal(quadratic[:, -4:], x[:, [0, 1, 3, 4]] ** 2)


def test_cuda_dispatch_reuses_project_backend_without_cuda_fit(toy_rows,
    monkeypatch):
  from pareto_weight_calibration import device
  requested = []

  def unavailable(value):
    requested.append(value)
    raise RuntimeError("CUDA unavailable for unit test")

  monkeypatch.setattr(device, "resolve_device", unavailable)
  with pytest.raises(RuntimeError, match="CUDA unavailable"):
    models.create_model("mlp", "cuda").fit(toy_rows,
                                           load_grid()["models"]["mlp"][0])
  assert requested == ["cuda"]
  # A CPU-only estimator is allowed when --device cuda is requested.
  tree = models.create_model("tree", "cuda").fit(toy_rows,
                                                 load_grid()["models"]["tree"][
                                                   0])
  assert tree.actual_device == "cpu"
