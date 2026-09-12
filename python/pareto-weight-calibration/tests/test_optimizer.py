"""Tests for deterministic constrained optimization and scale normalization back-transform."""

from __future__ import annotations

import numpy as np
import pytest

from pareto_weight_calibration.constraints import (
  MODEL_STRUCTURES,
  check_corner_constraints,
  load_domain_config,
)
from pareto_weight_calibration.optimizer import (
  OptimizationResult,
  compute_kkt_stationarity_residual,
  fit_constrained_model,
)
from pareto_weight_calibration.scaling import compute_training_scales
from pareto_weight_calibration.types import DomainConfig


@pytest.fixture
def synthetic_training_data():
  """Generates synthetic training feature matrix, labels, and influence weights."""
  rng = np.random.default_rng(42)
  n = 30
  # True underlying linear generator
  # x = [q, c*q, b*q, R*q, -1, -c, -b, -R]
  q = rng.uniform(0.1, 2.0, size=n)
  c = rng.uniform(0.0, 1.0, size=n)
  b = rng.uniform(2.0, 8.0, size=n)
  r = rng.choice([7.0, 23.0], size=n)

  X = np.column_stack([
    q,
    c * q,
    b * q,
    r * q,
    np.full(n, -1.0),
    -c,
    -b,
    -r,
  ])

  # Labels
  z_true = -2.0 * q + 1.0 * (c * q) - 0.5 * (-1.0)
  p_true = 1.0 / (1.0 + np.exp(-z_true))
  y = np.where(rng.uniform(0.0, 1.0, size=n) < p_true, 1.0, 0.0)

  # Weights
  v = rng.uniform(0.2, 1.0, size=n)
  u = v / np.max(v)

  domain = DomainConfig(
      c_min=0.0,
      c_max=1.0,
      body_cost_min_ns=10.0,
      body_cost_max_ns=5000.0,
      r_min=2,
      r_max=32,
  )

  return X, y, u, domain


def test_fit_constrained_model_all_structures_and_lambdas(
    synthetic_training_data):
  """Validates deterministic constrained fitting across all 8 structures and standard lambdas."""
  X, y, u, domain = synthetic_training_data
  lambdas = [1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1.0]

  for struct_name, active_indices in MODEL_STRUCTURES.items():
    scales = compute_training_scales(X, u, active_indices=active_indices)
    for l2 in lambdas:
      res = fit_constrained_model(
          X_train=X,
          y_train=y,
          u_train=u,
          scales=scales,
          domain=domain,
          structure_name=struct_name,
          l2_reg=l2,
      )

      # Check optimization result properties
      assert isinstance(res, OptimizationResult)
      assert res.success is True
      assert res.structure_name == struct_name
      assert res.l2_reg == l2
      assert res.iterations > 0
      assert np.isfinite(res.final_loss)
      assert np.isfinite(res.kkt_residual)
      assert np.isfinite(res.gradient_norm)
      assert res.kkt_residual >= 0.0

      # Constraint violation must be negligible
      assert res.constraint_violation <= 1e-9

      # Full 8-element vector checks
      assert len(res.w_phys_full) == 8
      # Inactive coefficients must be exact 0.0
      inactive_indices = [i for i in range(8) if i not in active_indices]
      for idx in inactive_indices:
        assert res.w_phys_full[idx] == 0.0

      # Corner constraints on physical vector
      corner_sat, corner_vals = check_corner_constraints(res.w_phys_full,
                                                         domain, tolerance=1e-9)
      assert corner_sat is True
      assert np.all(corner_vals <= 1e-9)


def test_deterministic_reproducibility(synthetic_training_data):
  """Validates that repeated fits with zero initialization produce identical results."""
  X, y, u, domain = synthetic_training_data
  scales = compute_training_scales(X, u)

  res1 = fit_constrained_model(X, y, u, scales, domain, structure_name="M8",
                               l2_reg=1e-3)
  res2 = fit_constrained_model(X, y, u, scales, domain, structure_name="M8",
                               l2_reg=1e-3)

  assert res1.final_loss == res2.final_loss
  assert np.array_equal(res1.w_scaled, res2.w_scaled)
  assert np.array_equal(res1.w_phys_full, res2.w_phys_full)
  assert res1.iterations == res2.iterations
  assert res1.kkt_residual == res2.kkt_residual
