"""Tests for domain corner constraints, model projections, and prefix monotonicity."""

from __future__ import annotations

import numpy as np
import pytest

from pareto_weight_calibration.constraints import (
  MODEL_STRUCTURES,
  build_physical_corner_matrix,
  build_projected_scaled_constraint_matrix,
  check_corner_constraints,
  get_domain_corners,
  load_domain_config,
  verify_fixed_state_prefix_monotonicity,
)
from pareto_weight_calibration.scaling import (
  back_transform_weights,
  compute_training_scales,
)
from pareto_weight_calibration.types import DomainConfig


def test_domain_corners_count_and_bounds():
  """Validates 8 domain corner generation and log1p body bounds."""
  domain = DomainConfig(
      c_min=0.0,
      c_max=1.0,
      body_cost_min_ns=10.0,
      body_cost_max_ns=5000.0,
      r_min=2,
      r_max=32,
  )
  corners = get_domain_corners(domain)
  assert len(corners) == 8
  # Check that each corner is valid
  for c, b, r in corners:
    assert c in (0.0, 1.0)
    assert b in (domain.b_min, domain.b_max)
    assert r in (2.0, 32.0)


def test_projected_scaled_corner_constraints_all_model_structures():
  """Validates C_tilde_{A,M} @ w_scaled == C_{A,M} @ w_phys == A(c_v, b_v, R_v) for all models."""
  domain = DomainConfig()
  corners = get_domain_corners(domain)
  rng = np.random.default_rng(1234)

  # Mock synthetic dataset to produce positive scales
  n = 20
  X = np.abs(rng.normal(loc=1.0, scale=0.5, size=(n, 8)))
  X[:, 4] = -1.0
  u = rng.uniform(0.1, 1.0, size=n)

  scales = compute_training_scales(X, u)

  for model_name, active_indices in MODEL_STRUCTURES.items():
    k_active = len(active_indices)
    w_scaled = rng.normal(scale=1.0, size=k_active)
    scales_m = scales[active_indices]
    w_phys_m = w_scaled / scales_m

    # Full 8-element physical vector with inactive coefficients as exactly 0.0
    w_phys_full = np.zeros(8, dtype=np.float64)
    for i, idx in enumerate(active_indices):
      w_phys_full[idx] = w_phys_m[i]

    # 1. Scaled projected matrix evaluation
    C_tilde = build_projected_scaled_constraint_matrix(domain, scales,
                                                       active_indices=active_indices)
    val_scaled = np.dot(C_tilde, w_scaled)

    # 2. Physical full matrix evaluation
    C_A = build_physical_corner_matrix(domain)
    val_phys_full = np.dot(C_A, w_phys_full)

    # 3. Direct formula A(c, b, R) at each corner
    val_formula = np.zeros(8, dtype=np.float64)
    for v_idx, (c, b, r) in enumerate(corners):
      val_formula[v_idx] = (
          w_phys_full[0]
          + w_phys_full[1] * c
          + w_phys_full[2] * b
          + w_phys_full[3] * r
      )

    # Assert exact agreement across all 3 representations
    assert np.allclose(val_scaled, val_phys_full, atol=1e-12)
    assert np.allclose(val_scaled, val_formula, atol=1e-12)


def test_fixed_state_prefix_monotonicity_guarantee():
  """Validates that A(c, b, R) <= 0 guarantees m(K+1) >= m(K) for discrete K."""
  domain = DomainConfig()
  # Construct a valid weight vector where A <= 0 everywhere in domain
  # e.g., w0 = -10.0, w1 = -1.0, w2 = -1.0, w3 = -0.5 (strictly negative A)
  # and arbitrary B
  w_valid = np.array([-10.0, -1.0, -1.0, -0.5, 2.0, 0.5, 0.1, 0.05],
                     dtype=np.float64)
  sat, vals = check_corner_constraints(w_valid, domain)
  assert sat is True
  assert np.all(vals <= 0.0)

  # Monotonicity check should succeed
  is_mono = verify_fixed_state_prefix_monotonicity(w_valid, domain)
  assert is_mono is True

  # Construct invalid weight vector where A > 0 at a corner
  w_invalid = np.array([10.0, 1.0, 1.0, 0.5, 2.0, 0.5, 0.1, 0.05],
                       dtype=np.float64)
  with pytest.raises(ValueError, match="corner constraint violated"):
    verify_fixed_state_prefix_monotonicity(w_invalid, domain)
