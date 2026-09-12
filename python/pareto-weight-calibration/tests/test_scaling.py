"""Tests for feature scale normalization without mean centering and back-transforms."""

from __future__ import annotations

import numpy as np
import pytest

from pareto_weight_calibration.scaling import (
  back_transform_weights,
  compute_training_scales,
  scale_features,
  transform_weights_to_scaled,
  verify_scale_invariance,
)


def test_confidence_weighted_rms_scales():
  """Validates confidence-weighted RMS scaling formula and constant column scale."""
  n = 10
  X = np.zeros((n, 8), dtype=np.float64)
  # Column 0: all 2.0
  X[:, 0] = 2.0
  # Column 4: constant -1.0
  X[:, 4] = -1.0
  # Column 1: values 1..10
  X[:, 1] = np.arange(1, 11, dtype=np.float64)
  # Other columns: 1.0
  for j in [2, 3, 5, 6, 7]:
    X[:, j] = 1.0

  u = np.ones(n, dtype=np.float64)
  scales = compute_training_scales(X, u)

  # Column 0: RMS = sqrt(10 * 4 / 10) = 2.0
  assert scales[0] == pytest.approx(2.0)
  # Column 4: constant column scale must be 1.0
  assert scales[4] == pytest.approx(1.0)
  # Column 1: sum(i^2) = 385 -> RMS = sqrt(38.5)
  assert scales[1] == pytest.approx(np.sqrt(38.5))


def test_zero_or_nonfinite_scale_raises_error():
  """Validates that a candidate with a zero nonconstant column is rejected as unidentified."""
  n = 5
  X = np.zeros((n, 8), dtype=np.float64)
  X[:, 4] = -1.0
  # Column 0 is all 0.0
  u = np.ones(n, dtype=np.float64)

  with pytest.raises(ValueError, match="non-positive or non-finite scale"):
    compute_training_scales(X, u)


def test_scale_invariance_invariant():
  """Validates exact numerical invariance: X @ w_phys == X_scaled @ w_scaled."""
  rng = np.random.default_rng(777)
  n = 30
  X = np.abs(rng.normal(loc=2.0, scale=1.0, size=(n, 8)))
  X[:, 4] = -1.0
  u = rng.uniform(0.1, 1.0, size=n)

  scales = compute_training_scales(X, u)
  X_scaled = scale_features(X, scales)

  w_phys = rng.normal(scale=1.0, size=8)
  w_scaled = transform_weights_to_scaled(w_phys, scales)
  w_phys_back = back_transform_weights(w_scaled, scales)

  assert np.allclose(w_phys, w_phys_back, atol=1e-14)
  assert verify_scale_invariance(X, w_phys, X_scaled, w_scaled) is True
