"""Unit tests for trial config loading and strict compatibility analysis."""

from __future__ import annotations

import json
from pathlib import Path
import pytest

from pareto_weight_calibration.config import CompatibilityAnalyzer, load_trial_config
from tests.conftest import generate_mock_trial_config


def test_compatible_pair_succeeds(tmp_path: Path):
    cfg_a_data = generate_mock_trial_config(k_cutoff=8, cpu_count=8)
    cfg_b_data = generate_mock_trial_config(k_cutoff=7, cpu_count=8)

    path_a = tmp_path / "cfg_a.json"
    path_b = tmp_path / "cfg_b.json"
    path_a.write_text(json.dumps(cfg_a_data), encoding="utf-8")
    path_b.write_text(json.dumps(cfg_b_data), encoding="utf-8")

    cfg_a = load_trial_config(path_a)
    cfg_b = load_trial_config(path_b)

    is_compat, reasons = CompatibilityAnalyzer.check_compatibility(cfg_a, cfg_b, expected_k=8)
    assert is_compat is True
    assert len(reasons) == 0


def test_mismatched_lifecycle_rejected(tmp_path: Path):
    cfg_a_data = generate_mock_trial_config(k_cutoff=8, lifecycle="RESET")
    cfg_b_data = generate_mock_trial_config(k_cutoff=7, lifecycle="CONTINUOUS")

    path_a = tmp_path / "cfg_a.json"
    path_b = tmp_path / "cfg_b.json"
    path_a.write_text(json.dumps(cfg_a_data), encoding="utf-8")
    path_b.write_text(json.dumps(cfg_b_data), encoding="utf-8")

    cfg_a = load_trial_config(path_a)
    cfg_b = load_trial_config(path_b)

    is_compat, reasons = CompatibilityAnalyzer.check_compatibility(cfg_a, cfg_b, expected_k=8)
    assert is_compat is False
    assert any("lifecycleMode" in r for r in reasons)


def test_mismatched_actuator_rejected(tmp_path: Path):
    cfg_a_data = generate_mock_trial_config(k_cutoff=8, actuator_version="legacy-unspecified")
    cfg_b_data = generate_mock_trial_config(k_cutoff=7, actuator_version="cache-v1")

    path_a = tmp_path / "cfg_a.json"
    path_b = tmp_path / "cfg_b.json"
    path_a.write_text(json.dumps(cfg_a_data), encoding="utf-8")
    path_b.write_text(json.dumps(cfg_b_data), encoding="utf-8")

    cfg_a = load_trial_config(path_a)
    cfg_b = load_trial_config(path_b)

    is_compat, reasons = CompatibilityAnalyzer.check_compatibility(cfg_a, cfg_b, expected_k=8)
    assert is_compat is False
    assert any("cacheActuatorVersion" in r for r in reasons)


def test_mismatched_cutoffs_rejected(tmp_path: Path):
    cfg_a_data = generate_mock_trial_config(k_cutoff=8)
    cfg_b_data = generate_mock_trial_config(k_cutoff=6)  # Should be 7!

    path_a = tmp_path / "cfg_a.json"
    path_b = tmp_path / "cfg_b.json"
    path_a.write_text(json.dumps(cfg_a_data), encoding="utf-8")
    path_b.write_text(json.dumps(cfg_b_data), encoding="utf-8")

    cfg_a = load_trial_config(path_a)
    cfg_b = load_trial_config(path_b)

    is_compat, reasons = CompatibilityAnalyzer.check_compatibility(cfg_a, cfg_b, expected_k=8)
    assert is_compat is False
    assert any("forcedActiveParticipantCount" in r for r in reasons)
