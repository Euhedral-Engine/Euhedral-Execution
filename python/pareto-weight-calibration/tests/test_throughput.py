"""Unit tests for throughput parsing and statistical aggregation."""

from __future__ import annotations

from pathlib import Path
import numpy as np
import pytest

from pareto_weight_calibration.throughput import ThroughputParser
from pareto_weight_calibration.types import ForkThroughput


def test_parse_log_file(tmp_path: Path):
    log_content = """
# JMH version: 1.37
# VM version: JDK 21.0.2
Iteration   1: 50100.123 ops/s
Iteration   2: 49950.456 ops/s
Iteration   3: 50200.789 ops/s
"""
    log_file = tmp_path / "benchmark_output.log"
    log_file.write_text(log_content, encoding="utf-8")

    scores = ThroughputParser.parse_log_file(log_file)
    assert len(scores) == 3
    assert np.isclose(scores[0], 50100.123)
    assert np.isclose(scores[1], 49950.456)
    assert np.isclose(scores[2], 50200.789)


def test_compute_arm_performance():
    forks = [
        ForkThroughput(fork_index=0, mean_ops_per_sec=100.0),
        ForkThroughput(fork_index=1, mean_ops_per_sec=110.0),
        ForkThroughput(fork_index=2, mean_ops_per_sec=90.0),
    ]
    late_means = [102.0, 108.0, 96.0]

    perf = ThroughputParser.compute_arm_performance(forks, late_means=late_means)
    assert perf.fork_count == 3
    assert np.isclose(perf.mean, 100.0)
    assert np.isclose(perf.variance, 100.0)
    assert np.isclose(perf.std_dev, 10.0)
    assert np.isclose(perf.cv, 0.10)

    assert np.isclose(perf.late_mean, 102.0)
    assert perf.late_variance > 0.0
