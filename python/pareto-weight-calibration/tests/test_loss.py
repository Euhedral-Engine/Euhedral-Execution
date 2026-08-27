"""Tests for numerically stable unclipped loss, analytic gradient, and analytic Hessian."""

from __future__ import annotations

import numpy as np
import pytest

from pareto_weight_calibration.loss import (
  loss_gradient,
  loss_hessian,
  unclipped_binary_cross_entropy,
  weighted_regularized_loss,
)


def _numerical_gradient(w: np.ndarray, X: np.ndarray, y: np.ndarray,
    u: np.ndarray, l2_reg: float, eps: float = 1e-7) -> np.ndarray:
  grad = np.zeros_like(w)
  for i in range(len(w)):
    w_plus = w.copy()
    w_plus[i] += eps
    w_minus = w.copy()
    w_minus[i] -= eps
    l_plus = weighted_regularized_loss(w_plus, X, y, u, l2_reg)
    l_minus = weighted_regularized_loss(w_minus, X, y, u, l2_reg)
    grad[i] = (l_plus - l_minus) / (2.0 * eps)
  return grad


def _numerical_hessian(w: np.ndarray, X: np.ndarray, y: np.ndarray,
    u: np.ndarray, l2_reg: float, eps: float = 1e-5) -> np.ndarray:
  d = len(w)
  hess = np.zeros((d, d), dtype=np.float64)
  for i in range(d):
    for j in range(d):
      w_pp = w.copy()
      w_pp[i] += eps
      w_pp[j] += eps

      w_pm = w.copy()
      w_pm[i] += eps
      w_pm[j] -= eps

      w_mp = w.copy()
      w_mp[i] -= eps
      w_mp[j] += eps

      w_mm = w.copy()
      w_mm[i] -= eps
      w_mm[j] -= eps

      f_pp = weighted_regularized_loss(w_pp, X, y, u, l2_reg)
      f_pm = weighted_regularized_loss(w_pm, X, y, u, l2_reg)
      f_mp = weighted_regularized_loss(w_mp, X, y, u, l2_reg)
      f_mm = weighted_regularized_loss(w_mm, X, y, u, l2_reg)

      hess[i, j] = (f_pp - f_pm - f_mp + f_mm) / (4.0 * eps * eps)
  return hess


def test_unclipped_loss_extreme_values():
  """Validates that unclipped loss avoids NaN/overflow on very large positive/negative logits."""
  y = np.array([0.0, 1.0, 0.5, 0.0, 1.0])
  z = np.array([-500.0, 500.0, 0.0, 1000.0, -1000.0])
  loss = unclipped_binary_cross_entropy(y, z)

  assert np.all(np.isfinite(loss))
  assert np.all(loss >= 0.0)
  # At z=0, ell(0.5, 0) = log(2) - 0 = ln(2)
  assert loss[2] == pytest.approx(np.log(2.0))


def test_analytic_gradient_matches_finite_differences():
  """Validates analytic gradient matches two-point central finite differences."""
  rng = np.random.default_rng(42)
  n, d = 30, 8
  X = rng.normal(loc=0.0, scale=1.0, size=(n, d))
  y = rng.choice([0.0, 1.0, 0.5], size=n)
  u = rng.uniform(0.1, 1.0, size=n)
  l2_reg = 0.05

  w_test_points = [
    np.zeros(d),
    rng.normal(scale=1.0, size=d),
    np.array([10.0, -5.0, 2.0, -1.0, 0.5, 0.0, -3.0, 1.5]),
  ]

  for w in w_test_points:
    analytic_grad = loss_gradient(w, X, y, u, l2_reg)
    numeric_grad = _numerical_gradient(w, X, y, u, l2_reg, eps=1e-7)
    assert np.allclose(analytic_grad, numeric_grad, atol=1e-6, rtol=1e-5)


def test_analytic_hessian_matches_finite_differences():
  """Validates analytic Hessian matches central finite-difference Hessian."""
  rng = np.random.default_rng(99)
  n, d = 25, 8
  X = rng.normal(loc=0.0, scale=1.0, size=(n, d))
  y = rng.choice([0.0, 1.0], size=n)
  u = rng.uniform(0.1, 1.0, size=n)
  l2_reg = 0.01

  w = rng.normal(scale=0.5, size=d)
  analytic_h = loss_hessian(w, X, y, u, l2_reg)
  numeric_h = _numerical_hessian(w, X, y, u, l2_reg, eps=1e-4)

  assert np.allclose(analytic_h, numeric_h, atol=1e-5, rtol=1e-4)
  # Check symmetry
  assert np.allclose(analytic_h, analytic_h.T, atol=1e-12)
  # Check strict positive definiteness with l2_reg > 0
  eigenvalues = np.linalg.eigvalsh(analytic_h)
  assert np.all(eigenvalues >= 2.0 * l2_reg)
