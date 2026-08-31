from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from pareto_weight_calibration import logistic_runtime as runtime

ROOT = Path(__file__).resolve().parents[3]
ARTIFACT = ROOT / runtime.DEFAULT_ARTIFACT


def test_finalized_artifact_has_exact_selected_configuration_and_provenance():
  artifact = json.loads(ARTIFACT.read_text())
  assert artifact["candidateId"] == "logistic-05-sqrt"
  assert artifact["configuration"] == {
    "C": .3, "geometry": "interactions", "l1_ratio": .25,
    "penalty": "elasticnet",
  }
  assert artifact["weightTransform"] == "sqrt"
  assert artifact["probabilityThreshold"] == .45
  assert artifact["trainingRowCount"] == 102
  assert artifact["featureNames"] == ["K", "pRatio", "logR", "body",
                                      "contention"]
  assert len(artifact["expandedFeatures"]) == len(
      artifact["coefficients"]) == 11
  assert artifact["logitThreshold"] == pytest.approx(-.20067069546215124)
  assert artifact["trainingDatasetSha256"] == hashlib.sha256(
      (
            ROOT / "experiments/pareto_direct_side_training/direct_side_training_dataset.json").read_bytes()
  ).hexdigest()


def test_exporter_is_byte_identical_and_emits_no_sigmoid(tmp_path):
  one_java = tmp_path / "one.java"
  one_fixture = tmp_path / "one.tsv"
  two_java = tmp_path / "two.java"
  two_fixture = tmp_path / "two.tsv"
  runtime.export(ROOT, ARTIFACT, one_java, one_fixture)
  runtime.export(ROOT, ARTIFACT, two_java, two_fixture)
  assert one_java.read_bytes() == two_java.read_bytes()
  assert one_fixture.read_bytes() == two_fixture.read_bytes()
  source = one_java.read_text()
  assert "Math.exp" not in source
  assert "sigmoid" not in source.lower()
  assert "score(k, productiveHandles, registeredWorkers, bodyCostNs, contention) >= LOGIT_THRESHOLD" in source
  assert one_fixture.read_bytes() == (
        ROOT / runtime.DEFAULT_FIXTURE).read_bytes()
  assert one_java.read_bytes() == (ROOT / runtime.DEFAULT_JAVA).read_bytes()


def test_fixture_covers_runtime_dimensions_and_boundary_actions():
  rows = (ROOT / runtime.DEFAULT_FIXTURE).read_text().splitlines()[1:]
  fields = [row.split("\t") for row in rows]
  assert {int(row[3]) for row in fields} == {7, 15, 23}
  assert {row[7] for row in fields} == {"CACHE", "DEFAULT"}
  assert any("R7-S1" in row[0] for row in fields)
  assert sum("boundary" in row[0] for row in fields) == 6
  assert all(len(row) == 19 for row in fields)
