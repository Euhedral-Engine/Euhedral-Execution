from __future__ import annotations

from dataclasses import replace
import hashlib
import json
from pathlib import Path

import numpy as np
import pytest

from pareto_weight_calibration.action_model import CACHE, DEFAULT
from pareto_weight_calibration.model_tournament import fold_plan
from pareto_weight_calibration import tournament_v2 as runner
from pareto_weight_calibration import tournament_v2_manifest as manifest_module
from pareto_weight_calibration import tournament_v2_models as models
from .test_direct_side import _row

ROOT = Path(__file__).resolve().parents[3]


@pytest.fixture(scope="module")
def toy_rows():
  return [
    replace(
        _row(f"{family}-{k}", family, k=k,
             action=DEFAULT if k <= 4 else CACHE, cost=5.0 + k,
             productive_handles=2 + index / 4, body=3 + index / 4,
             contention=0.1 + index / 10),
        evidence_weight=0.4 + k / 20,
    )
    for index, family in enumerate(("a", "b", "c", "d"))
    for k in range(2, 8)
  ]


@pytest.fixture(scope="module")
def manifest():
  return manifest_module.build_manifest({"frozen": "0" * 64})


def first_candidate(manifest, family):
  return next(item for item in manifest["baseCandidates"]
              if item["modelFamily"] == family)


@pytest.mark.parametrize("family", manifest_module.MODEL_ORDER)
def test_every_new_model_adapter_fits_and_predicts(family, manifest, toy_rows):
  candidate = first_candidate(manifest, family)
  fitted = models.create_model(family, "cpu").fit(toy_rows, candidate)
  scores = fitted.predict_score(toy_rows)
  probabilities = fitted.predict_probability(toy_rows)
  assert scores.shape == (len(toy_rows),)
  assert np.isfinite(scores).all()
  if family == "svm":
    assert probabilities is None
    assert set(fitted.score_thresholds) == {str(t) for t in
                                            manifest_module.THRESHOLDS}
  else:
    assert probabilities.shape == scores.shape
    assert np.all((probabilities >= 0) & (probabilities <= 1))
  assert fitted.metadata()["actualDevice"] == "cpu"


@pytest.mark.parametrize("transform", models.WEIGHT_TRANSFORMS)
def test_weight_transforms_are_positive_deterministic_and_distinct(transform,
    toy_rows):
  first = models.transformed_training_weights(toy_rows, transform)[1]
  second = models.transformed_training_weights(list(reversed(toy_rows)),
                                               transform)[1]
  assert np.isfinite(first).all() and np.all(first > 0)
  assert first.mean() == pytest.approx(1)
  # Reversing rows reverses the output; it does not alter the transform.
  np.testing.assert_allclose(first, second[::-1])
  if transform == "mixed":
    raw = models.transformed_training_weights(toy_rows, "raw")[1]
    assert not np.array_equal(first, raw)


def test_manifest_is_complete_deterministic_and_frozen(tmp_path, manifest):
  assert manifest["baseCandidateCount"] == 302
  assert manifest["ensembleCandidateCount"] == 42
  assert len(manifest["modelFamilyOrder"]) == 11
  assert manifest == manifest_module.build_manifest({"frozen": "0" * 64})
  ids = [item["id"] for item in manifest["baseCandidates"]]
  assert len(ids) == len(set(ids))
  path = tmp_path / runner.MANIFEST_NAME
  digest = manifest_module.write_frozen_manifest(path, manifest)
  assert digest == hashlib.sha256(path.read_bytes()).hexdigest()
  assert manifest_module.verify_frozen_manifest(path, digest) == manifest
  changed = json.loads(path.read_text())
  changed["seed"] += 1
  path.write_bytes(manifest_module.canonical_bytes(changed))
  with pytest.raises(ValueError, match="changed"):
    manifest_module.verify_frozen_manifest(path, digest)


def test_deliberate_boost_lattice_covers_requested_ranges(manifest):
  boosted = [item for item in manifest["baseCandidates"]
             if item["modelFamily"] in {"hist_boost", "xgboost"}
             and item["weightTransform"] == "raw"]
  depths = {item["params"]["max_depth"] for item in boosted}
  rates = {item["params"]["learning_rate"] for item in boosted}
  counts = {item["params"].get("max_iter",
                               item["params"].get("n_estimators"))
            for item in boosted}
  assert depths == {1, 2, 3, 4, 5}
  assert rates == {.01, .03, .05, .10, .20}
  assert counts == {40, 80, 120, 200, 400}


def test_thresholds_reuse_each_fit(manifest, toy_rows, monkeypatch):
  candidate = first_candidate(manifest, "cart")
  calls = 0
  original = models.CartClassifier.fit

  def counted(self, rows, config):
    nonlocal calls
    calls += 1
    return original(self, rows, config)

  monkeypatch.setattr(models.CartClassifier, "fit", counted)
  plan = fold_plan(toy_rows, 4)
  result = runner.select_inner(toy_rows, [candidate], plan, "cpu")
  assert calls == 4
  assert result["fitCount"] == 4
  assert result["selectedThreshold"] in manifest_module.THRESHOLDS


def test_threshold_selection_never_sees_outer_family(manifest, toy_rows,
    monkeypatch):
  train, outer = runner.partition_families(toy_rows, ["d"])
  candidate = first_candidate(manifest, "cart")
  seen = []
  original = runner._candidate_inner_predictions

  def checked(rows, *args, **kwargs):
    seen.append({row.family_id for row in rows})
    assert not ({row.pair_id for row in rows}
                & {row.pair_id for row in outer})
    return original(rows, *args, **kwargs)

  monkeypatch.setattr(runner, "_candidate_inner_predictions", checked)
  runner.select_inner(train, [candidate], fold_plan(train, 3), "cpu")
  assert seen == [{"a", "b", "c"}]


def _simple_records(rows, probabilities, threshold=.5):
  result = []
  for row, probability in zip(rows, probabilities):
    result.append({
      "pairId": row.pair_id,
      "familyId": row.family_id,
      "probabilityRight": probability,
      "action": CACHE if probability >= threshold else DEFAULT,
    })
  return result


def test_ensemble_average_blend_and_boolean_semantics(toy_rows):
  rows = toy_rows[:3]
  left = _simple_records(rows, [.2, .8, .8])
  right = _simple_records(rows, [.4, .4, .9])
  average = {"method": "average", "params": {"alpha": .5}}
  blend = {"method": "blend", "params": {"alpha": .25}}
  both = {"method": "cache_and", "params": {}}
  either = {"method": "cache_or", "params": {}}
  np.testing.assert_allclose(runner._ensemble_probability(average, left, right),
                             [.3, .6, .85])
  np.testing.assert_allclose(runner._ensemble_probability(blend, left, right),
                             [.35, .5, .875])
  np.testing.assert_array_equal(runner._ensemble_probability(both, left, right),
                                [0, 0, 1])
  np.testing.assert_array_equal(
    runner._ensemble_probability(either, left, right),
    [0, 1, 1])


def test_stacker_is_group_cross_fitted_without_held_labels(toy_rows):
  left = _simple_records(toy_rows, np.linspace(.1, .9, len(toy_rows)))
  right = _simple_records(toy_rows, np.linspace(.9, .1, len(toy_rows)))
  probabilities, audit = runner._stacker_crossfit(
      toy_rows, left, right, fold_plan(toy_rows, 4))
  assert probabilities.shape == (len(toy_rows),)
  assert np.all((probabilities >= 0) & (probabilities <= 1))
  for split in audit:
    assert set(split["trainingFamilies"]).isdisjoint(
        split["heldOutFamilies"])


@pytest.mark.parametrize("family", [
  family for family, state in models.MONOTONICITY.items()
  if state == "enforced"
])
def test_declared_monotonic_k_constraints(family, manifest, toy_rows):
  fitted = models.create_model(family, "cpu").fit(
      toy_rows, first_candidate(manifest, family))
  fixed = [replace(toy_rows[0], current_k=k) for k in range(1, 9)]
  assert np.all(np.diff(fitted.predict_score(fixed)) >= -1e-8)


def test_physical_family_grouping_is_disjoint(toy_rows):
  for held_family in ("a", "b", "c", "d"):
    train, held = runner.partition_families(toy_rows, [held_family])
    assert {row.family_id for row in train}.isdisjoint(
        {row.family_id for row in held})
    assert {row.family_id for row in held} == {held_family}


def test_deterministic_model_actions(manifest, toy_rows):
  candidate = first_candidate(manifest, "hist_boost")
  one = models.create_model("hist_boost", "cpu").fit(toy_rows, candidate)
  two = models.create_model("hist_boost", "cpu").fit(
      list(reversed(toy_rows)), candidate)
  np.testing.assert_array_equal(one.predict_score(toy_rows),
                                two.predict_score(toy_rows))


def test_cpu_device_routing_and_cuda_threshold_fallback(manifest, toy_rows,
    monkeypatch):
  fitted = models.create_model("xgboost", "cpu").fit(
      toy_rows, first_candidate(manifest, "xgboost"))
  assert fitted.actual_device == "cpu"
  raw = runner._raw_prediction(fitted, toy_rows)
  monkeypatch.setattr("torch.cuda.is_available", lambda: False)
  matrix = runner._threshold_matrix(raw, manifest_module.THRESHOLDS, "cuda")
  assert matrix.shape == (len(manifest_module.THRESHOLDS), len(toy_rows))


def test_frozen_artifacts_are_unchanged_and_exact():
  rows, hashes = runner.load_frozen_dataset(ROOT)
  assert len(rows) == 102
  assert len({row.family_id for row in rows}) == 43
  expected = json.loads((ROOT / runner.INPUT_LOCK).read_text())
  assert hashes == expected
  for relative, digest in expected.items():
    assert hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() == digest


def test_reference_adapter_filters_m4c_to_decisive_cohort():
  rows, _ = runner.load_frozen_dataset(ROOT)
  references = runner._reference_metrics(ROOT, rows)
  assert set(references) == {
    "current_direct_frozen", "v1_linear", "v1_boosted_tree", "M4-C-LOFO"
  }
  assert all(reference["decisiveCount"] == 102
             for reference in references.values())


def test_output_directory_may_resume_from_manifest_only(tmp_path):
  output = tmp_path / "results"
  output.mkdir()
  (output / runner.MANIFEST_NAME).write_text("manifest")
  (output / (runner.MANIFEST_NAME + ".sha256")).write_text("digest")
  assert runner.validate_output_dir(ROOT, output) == output.resolve()
  (output / "unexpected.json").write_text("data")
  with pytest.raises(ValueError, match="manifest"):
    runner.validate_output_dir(ROOT, output)


def test_cli_help_does_not_execute(monkeypatch):
  invoked = []
  monkeypatch.setattr(runner, "run_tournament",
                      lambda *args: invoked.append(args))
  runner.main(["--device", "cpu", "--output-dir", "unused"])
  assert invoked and invoked[0][2] == "cpu"
