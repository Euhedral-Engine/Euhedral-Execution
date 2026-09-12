"""Tests for the mechanical productivity-participation pipeline boundary."""

from __future__ import annotations

from pathlib import Path

import pytest

from pareto_weight_calibration.manifest import save_manifest
from pareto_weight_calibration.pipeline import load_frozen_training_manifest
from pareto_weight_calibration.types import Manifest, ManifestPair


def _write_manifest(tmp_path: Path, evidence: object) -> Path:
  path = tmp_path / "training_manifest.json"
  manifest = Manifest(
      schema_version=1,
      runtime_commit="abc123",
      cache_actuator_version="cache-v1",
      cache_park_ns=15_000,
      topology_id="test-topology",
      pairs=[
        ManifestPair(
            pair_id="pair-k2-vs-k1",
            k_run_path=tmp_path / "k2",
            k_minus_1_run_path=tmp_path / "k1",
            K=2,
            metadata={
              "frozenStep4Evidence": evidence,
              "trialConfigSha256": {
                "k": [],
                "kMinus1": [],
              },
            },
        )
      ],
  )
  save_manifest(manifest, path)
  return path


def test_frozen_training_manifest_accepts_positive_consistent_evidence(
    tmp_path: Path):
  path = _write_manifest(tmp_path, {
    "effectiveOutcome": "K_WINS",
    "labelEvidenceBasis": "WHOLE_AGREEMENT",
    "y": 0.0,
    "pairWeight": 1.0,
    "basisThroughputK": 100.0,
    "basisThroughputKMinus1": 90.0,
    "basisDelta": -10.0,
    "basisVarianceK": 1.0,
    "basisVarianceKMinus1": 1.0,
    "basisUncertainty": 2.0,
  })

  manifest = load_frozen_training_manifest(path)

  assert len(manifest.pairs) == 1


@pytest.mark.parametrize(
    "evidence, message",
    [
      ({}, "invalid frozen y or pairWeight"),
      ({
         "effectiveOutcome": "INCONCLUSIVE",
         "labelEvidenceBasis": "NONE",
         "y": 0.5,
         "pairWeight": 0.0,
       }, "invalid frozen effectiveOutcome"),
      ({
         "effectiveOutcome": "K_WINS",
         "labelEvidenceBasis": "WHOLE_AGREEMENT",
         "y": 1.0,
         "pairWeight": 1.0,
       }, "does not match K_WINS"),
    ],
)
def test_frozen_training_manifest_fails_closed(
    tmp_path: Path,
    evidence: object,
    message: str,
):
  path = _write_manifest(tmp_path, evidence)

  with pytest.raises(ValueError, match=message):
    load_frozen_training_manifest(path)
