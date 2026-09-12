"""Unit tests for staleness parsing and active feature vector construction."""

from __future__ import annotations

import math
from pathlib import Path
import numpy as np
import pytest

from pareto_weight_calibration.staleness import StalenessParser
from pareto_weight_calibration.types import ActiveStateFeatures
from tests.conftest import generate_mock_staleness_tsv, write_with_checksum


def test_extract_active_features(tmp_path: Path):
    tsv_content = generate_mock_staleness_tsv(
        target_rank=8, contention=500000, body_cost=120.0, prod_handles=14, reg_workers=8
    )
    tsv_path = tmp_path / "contention_staleness.tsv"
    write_with_checksum(tsv_path, tsv_content)

    features = StalenessParser.extract_active_features(tmp_path, target_rank=8)
    assert features.K == 8
    assert features.R == 8
    assert np.isclose(features.c, 0.5)
    assert np.isclose(features.smoothed_body_cost_ns, 120.0)
    assert np.isclose(features.b, math.log1p(120.0))
    assert np.isclose(features.P, 14.0)

    # Check q calculation: P / (K * (K - 1)) = 14.0 / (8 * 7) = 14.0 / 56.0 = 0.25
    assert np.isclose(features.q, 0.25)

    # Check 8-term feature vector: [q, c*q, b*q, R*q, -1, -c, -b, -R]
    vec = features.feature_vector
    assert len(vec) == 8
    assert np.isclose(vec[0], 0.25)
    assert np.isclose(vec[1], 0.5 * 0.25)
    assert np.isclose(vec[2], math.log1p(120.0) * 0.25)
    assert np.isclose(vec[3], 8.0 * 0.25)
    assert np.isclose(vec[4], -1.0)
    assert np.isclose(vec[5], -0.5)
    assert np.isclose(vec[6], -math.log1p(120.0))
    assert np.isclose(vec[7], -8.0)


def test_extract_withdrawn_diagnostics(tmp_path: Path):
    tsv_content = generate_mock_staleness_tsv(
        target_rank=8, contention=400000, prod_handles=10, is_active=False
    )
    tsv_path = tmp_path / "contention_staleness.tsv"
    write_with_checksum(tsv_path, tsv_content)

    withdrawn = StalenessParser.extract_withdrawn_diagnostics(tmp_path, target_rank=8)
    assert np.isclose(withdrawn.c_stale, 0.4)
    assert np.isclose(withdrawn.P_stale, 10.0)
    assert withdrawn.execution_path == "CACHE"
    assert withdrawn.acquisitions_attempted == 0
