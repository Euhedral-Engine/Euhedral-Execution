"""Unit tests for dataset manifest loader."""

from __future__ import annotations

import json
from pathlib import Path
import pytest

from pareto_weight_calibration.manifest import ManifestLoadError, load_manifest


def test_valid_manifest_load(tmp_path: Path):
    manifest_data = {
        "schemaVersion": 1,
        "runtimeCommit": "c01ce91b",
        "cacheActuatorVersion": "cache-v1",
        "cacheParkNs": 15000,
        "topologyId": "test-topo",
        "pairs": [
            {
                "pairId": "pair-1",
                "kRunPath": "runs/run_k8",
                "kMinus1RunPath": "runs/run_k7",
                "K": 8,
                "metadata": {"workUnits": 16},
            }
        ],
    }
    p = tmp_path / "manifest.json"
    p.write_text(json.dumps(manifest_data), encoding="utf-8")

    manifest = load_manifest(p)
    assert manifest.schema_version == 1
    assert manifest.runtime_commit == "c01ce91b"
    assert manifest.cache_actuator_version == "cache-v1"
    assert manifest.cache_park_ns == 15000
    assert manifest.topology_id == "test-topo"
    assert len(manifest.pairs) == 1

    pair = manifest.pairs[0]
    assert pair.pair_id == "pair-1"
    assert pair.K == 8
    assert pair.k_run_path == (tmp_path / "runs/run_k8").resolve()
    assert pair.k_minus_1_run_path == (tmp_path / "runs/run_k7").resolve()
    assert pair.metadata == {"workUnits": 16}


def test_invalid_schema_version_fails(tmp_path: Path):
    manifest_data = {
        "schemaVersion": 2,
        "runtimeCommit": "c01ce91b",
        "cacheActuatorVersion": "cache-v1",
        "cacheParkNs": 15000,
        "topologyId": "test-topo",
        "pairs": [{"pairId": "p1", "kRunPath": "a", "kMinus1RunPath": "b", "K": 4}],
    }
    p = tmp_path / "manifest.json"
    p.write_text(json.dumps(manifest_data), encoding="utf-8")

    with pytest.raises(ManifestLoadError, match="Unsupported schemaVersion"):
        load_manifest(p)


def test_missing_fields_fails(tmp_path: Path):
    manifest_data = {
        "schemaVersion": 1,
        # missing runtimeCommit
        "cacheActuatorVersion": "cache-v1",
        "cacheParkNs": 15000,
        "topologyId": "test-topo",
        "pairs": [],
    }
    p = tmp_path / "manifest.json"
    p.write_text(json.dumps(manifest_data), encoding="utf-8")

    with pytest.raises(ManifestLoadError, match="missing 'runtimeCommit'"):
        load_manifest(p)
