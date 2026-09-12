"""End-to-end integration tests for the dataset loader and export pipeline."""

from __future__ import annotations

import csv
from pathlib import Path
import numpy as np
import pytest

from pareto_weight_calibration.cli import main as cli_main
from pareto_weight_calibration.export import export_pairs_tsv
from pareto_weight_calibration.loader import DataLoader
from pareto_weight_calibration.types import Outcome, TrajectoryStatus


def test_end_to_end_load_dataset(mock_run_pair: dict):
    manifest_path: Path = mock_run_pair["manifest_path"]

    records = DataLoader.load_dataset(manifest_path, verify_checksums=True)
    assert len(records) == 1

    rec = records[0]
    assert rec.pair_id == "pair-k8-k7-mock"
    assert rec.K == 8
    assert rec.registered_workers == 8
    assert rec.work_units == 16
    assert rec.cache_actuator_version == "cache-v1"
    assert rec.cache_park_ns == 15000

    # Arm B has 60000 ops/s vs Arm A 50000 ops/s -> delta = +10000 -> K_MINUS_1_WINS
    assert rec.whole_outcome == Outcome.K_MINUS_1_WINS
    assert rec.late_outcome == Outcome.K_MINUS_1_WINS
    assert rec.trajectory_status == TrajectoryStatus.STABLE_AGREEMENT
    assert rec.y == 1.0
    assert rec.pair_weight > 0.5

    # Features check
    feat = rec.features
    assert feat.K == 8
    assert np.isclose(feat.c, 0.6)
    assert np.isclose(feat.smoothed_body_cost_ns, 150.0)
    assert np.isclose(feat.P, 12.0)
    assert len(feat.feature_vector) == 8

    # Diagnostic check
    diag = rec.withdrawn_diagnostics
    assert diag.execution_path == "CACHE"


def test_export_and_cli(mock_run_pair: dict, tmp_path: Path):
    manifest_path: Path = mock_run_pair["manifest_path"]
    out_tsv = tmp_path / "exported_pairs.tsv"

    # Test CLI validate
    rc_val = cli_main(["validate", "--manifest", str(manifest_path)])
    assert rc_val == 0

    # Test CLI load
    rc_load = cli_main(["load", "--manifest", str(manifest_path), "--output", str(out_tsv)])
    assert rc_load == 0
    assert out_tsv.exists()
    assert (tmp_path / "exported_pairs.tsv.sha256").exists()

    # Read exported TSV
    with open(out_tsv, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        rows = list(reader)

    assert len(rows) == 1
    row = rows[0]
    assert row["pairId"] == "pair-k8-k7-mock"
    assert row["K"] == "8"
    assert row["y"] == "1.0"
    assert float(row["pairWeight"]) > 0.5
    assert row["wholeRunOutcome"] == "K_MINUS_1_WINS"
    assert row["effectiveOutcome"] == "K_MINUS_1_WINS"
    assert row["labelEvidenceBasis"] == "WHOLE_AGREEMENT"
    assert float(row["basisThroughput_K"]) > 0.0
    assert float(row["basisThroughput_KMinus1"]) > 0.0

    # Test CLI summary
    rc_sum = cli_main(["summary", "--pairs", str(out_tsv)])
    assert rc_sum == 0
