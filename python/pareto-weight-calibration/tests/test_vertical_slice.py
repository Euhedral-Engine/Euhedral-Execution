"""Vertical Slice End-to-End Validation Test."""

import json
from pathlib import Path
import pytest

from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.loader import DataLoader
from pareto_weight_calibration.manifest import load_manifest
from pareto_weight_calibration.model import JavaParetoWeights, MarginalModel
from pareto_weight_calibration.types import Outcome, TrajectoryStatus


def test_vertical_slice_end_to_end():
    repo_root = Path(__file__).resolve().parents[3]
    experiment_dir = repo_root / "experiments" / "02-vertical-slice-k4-vs-k3"
    manifest_path = experiment_dir / "dataset_manifest.json"

    if not manifest_path.exists():
        pytest.skip(f"Vertical slice experiment artifacts not found at {manifest_path}")

    # 1. Manifest structure & Actuator identity
    manifest = load_manifest(manifest_path)
    assert manifest.schema_version == 1
    assert manifest.cache_actuator_version == "cache-v1"
    assert manifest.cache_park_ns == 15000
    assert len(manifest.pairs) == 1

    # 2. Checksum sidecar validation on all source files
    for pair in manifest.pairs:
        k_dir = pair.k_run_path
        k_minus_1_dir = pair.k_minus_1_run_path

        for d in (k_dir, k_minus_1_dir):
            for tsv_file in d.glob("**/*.tsv"):
                sidecar = tsv_file.parent / (tsv_file.name + ".sha256")
                if sidecar.exists():
                    digest = ChecksumVerifier.verify_file(tsv_file)
                    assert len(digest) == 64

    # 3. Load pair and produce verified PairRecord
    records = DataLoader.load_dataset(
        manifest_path=manifest_path,
        verify_checksums=True,
        min_weight=0.0,
        strict_compatibility=True,
    )
    assert len(records) == 1
    record = records[0]

    # Verify candidate rank and physical parameters
    assert record.K == 4
    assert record.cache_actuator_version == "cache-v1"
    assert record.cache_park_ns == 15000
    assert record.perf_k.fork_count == 2
    assert record.perf_k_minus_1.fork_count == 2

    # Verify active state features for rank K=4
    assert 0.0 <= record.features.c <= 1.0
    assert record.features.smoothed_body_cost_ns > 0
    assert record.features.b > 0
    assert record.features.P > 0
    assert record.features.R >= 7
    assert record.features.K == 4

    # Verify withdrawn diagnostics in Arm B (K-1=3 where rank 4 was forced to CACHE)
    assert record.withdrawn_diagnostics.execution_path == "CACHE"
    assert record.withdrawn_diagnostics.acquisitions_attempted == 0

    # 4. Java evaluator parity on the active feature coordinates
    model = MarginalModel(logical_weights=JavaParetoWeights(
        phrWeight=1.0, contentionPhrWeight=1.0, bodyPhrWeight=1.0, registeredWorkersPhrWeight=1.0,
        activeWorkersWeight=1.0, contentionWorkersWeight=1.0, bodyWorkersWeight=1.0, registeredActiveWorkersWeight=1.0
    ).to_logical_weights())

    assert model.verify_evaluator_parity(
        c=record.features.c,
        smoothed_body_cost_ns=record.features.smoothed_body_cost_ns,
        P=record.features.P,
        R=record.features.R,
        K=record.features.K,
    )

    # 5. Model save/load and Java ParetoWeights export round-trip
    tmp_model_file = experiment_dir / "vertical_slice_model.json"
    model.save(tmp_model_file)
    assert tmp_model_file.exists()

    loaded_model = MarginalModel.load(tmp_model_file)
    assert loaded_model.logical_weights == model.logical_weights

    exported_weights = loaded_model.export_java_weights()
    assert len(exported_weights) == 8
    assert "phrWeight" in exported_weights
    assert "activeWorkersWeight" in exported_weights
