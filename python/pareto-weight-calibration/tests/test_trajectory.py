"""Unit tests for trajectory parsing and stability qualification."""

from __future__ import annotations

from pathlib import Path
import pytest

from pareto_weight_calibration.trajectory import (
    TrajectoryAnalyzer,
    WindowRecord,
)
from pareto_weight_calibration.types import ForkThroughput
from tests.conftest import generate_mock_trajectory_tsv, write_with_checksum


def test_parse_and_analyze_stable_trajectory(tmp_path: Path):
    tsv_content = generate_mock_trajectory_tsv(
        fork_count=2, windows_per_fork=8, base_throughput=50000.0, cv_noise=0.005
    )
    tsv_path = tmp_path / "trajectory_windows.tsv"
    write_with_checksum(tsv_path, tsv_content)

    forks, is_arm_stable = TrajectoryAnalyzer.analyze_run_directory(tmp_path)
    assert is_arm_stable is True
    assert len(forks) == 2
    for f in forks:
        assert f.is_late_stable is True
        assert f.late_cv < 0.05
        assert abs(f.late_slope) < (0.01 * f.late_mean_ops_per_sec)


def test_starvation_in_late_window_fails():
    windows = [
        WindowRecord("jvm-0", "CONTINUOUS", 0, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 1, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 2, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 3, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 4, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 5, 50000.0, False),  # Starved!
    ]
    analysis = TrajectoryAnalyzer.analyze_fork(windows)
    assert analysis.is_stable is False
    assert "starvation" in analysis.rejection_reason.lower()


def test_high_cv_improving_trajectory_remains_eligible():
    windows = [
        WindowRecord("jvm-0", "CONTINUOUS", 0, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 1, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 2, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 3, 40000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 4, 60000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 5, 80000.0, True),
    ]
    analysis = TrajectoryAnalyzer.analyze_fork(windows, cv_threshold=0.05)
    assert analysis.late_cv > 0.05
    assert analysis.is_stable is False
    assert analysis.is_improving is True
    assert analysis.rejection_reason is None


def test_steep_upward_slope_is_improving():
    windows = [
        WindowRecord("jvm-0", "CONTINUOUS", 0, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 1, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 2, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 3, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 4, 52000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 5, 54000.0, True),  # Rapid upward drift (> 1% per window)
    ]
    analysis = TrajectoryAnalyzer.analyze_fork(windows, slope_threshold_pct=0.01)
    assert analysis.is_stable is False
    assert analysis.is_improving is True
    assert analysis.rejection_reason is None


def test_steep_downward_slope_fails():
    windows = [
        WindowRecord("jvm-0", "CONTINUOUS", 0, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 1, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 2, 50000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 3, 54000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 4, 52000.0, True),
        WindowRecord("jvm-0", "CONTINUOUS", 5, 50000.0, True),
    ]
    analysis = TrajectoryAnalyzer.analyze_fork(windows, slope_threshold_pct=0.01)
    assert analysis.is_stable is False
    assert analysis.is_improving is False
    assert "declining" in analysis.rejection_reason.lower()


def test_arm_trajectory_uses_aggregate_normalized_slope():
    forks = [
        ForkThroughput(
            fork_index=0,
            mean_ops_per_sec=50000.0,
            late_mean_ops_per_sec=50000.0,
            late_slope=-600.0,
        ),
        ForkThroughput(
            fork_index=1,
            mean_ops_per_sec=50000.0,
            late_mean_ops_per_sec=50000.0,
            late_slope=400.0,
            is_late_improving=True,
        ),
    ]

    assert TrajectoryAnalyzer.arm_is_stable_or_improving(forks) is True
