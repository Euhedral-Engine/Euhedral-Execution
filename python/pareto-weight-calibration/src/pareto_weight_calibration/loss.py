"""Numerically stable unclipped loss, gradient, and Hessian for Pareto-weight fitting."""

from __future__ import annotations

from typing import Optional, Tuple
import numpy as np
from scipy.special import expit


def unclipped_binary_cross_entropy(y: np.ndarray, z: np.ndarray) -> np.ndarray:
  """Computes unclipped, smooth binary cross-entropy loss.

  Formula:
      ell(y, z) = logaddexp(0, z) - y * z

  Args:
      y: Target label array of shape (N,) in [0.0, 1.0].
      z: Linear predictor margin array of shape (N,).

  Returns:
      Array of per-sample losses of shape (N,).
  """
  return np.logaddexp(0.0, z) - y * z


def compute_probabilities(z: np.ndarray) -> np.ndarray:
  """Computes sigmoid probabilities p = expit(z) = 1 / (1 + exp(-z))."""
  return expit(z)


def weighted_regularized_loss(
    w: np.ndarray,
    X: np.ndarray,
    y: np.ndarray,
    u: np.ndarray,
    l2_reg: float,
    U: Optional[float] = None,
) -> float:
  """Computes the weighted regularized negative log-likelihood loss.

  Objective:
      L(w; lambda) = (1 / U) * sum(u_i * ell(y_i, x_i^T w)) + lambda * sum(w_j^2)

  Args:
      w: Weight vector of shape (d,).
      X: Design matrix of shape (N, d).
      y: Labels of shape (N,).
      u: Sample influence weights of shape (N,).
      l2_reg: L2 regularization penalty parameter lambda >= 0.
      U: Optional precomputed sum(u_i).

  Returns:
      Scalar loss value.
  """
  total_u = U if U is not None else float(np.sum(u))
  z = np.dot(X, w)
  per_sample_loss = unclipped_binary_cross_entropy(y, z)
  weighted_loss = float(np.sum(u * per_sample_loss)) / total_u
  reg_loss = float(l2_reg * np.sum(w ** 2))
  return weighted_loss + reg_loss


def loss_gradient(
    w: np.ndarray,
    X: np.ndarray,
    y: np.ndarray,
    u: np.ndarray,
    l2_reg: float,
    U: Optional[float] = None,
) -> np.ndarray:
  """Computes the analytic gradient of the weighted regularized loss.

  Gradient:
      grad = (1 / U) * X^T (u * (p - y)) + 2 * lambda * w

  Args:
      w: Weight vector of shape (d,).
      X: Design matrix of shape (N, d).
      y: Labels of shape (N,).
      u: Sample influence weights of shape (N,).
      l2_reg: L2 regularization penalty parameter lambda >= 0.
      U: Optional precomputed sum(u_i).

  Returns:
      Gradient vector of shape (d,).
  """
  total_u = U if U is not None else float(np.sum(u))
  z = np.dot(X, w)
  p = compute_probabilities(z)
  residual = u * (p - y)
  grad_loss = np.dot(X.T, residual) / total_u
  grad_reg = 2.0 * l2_reg * w
  return grad_loss + grad_reg


def loss_hessian(
    w: np.ndarray,
    X: np.ndarray,
    y: np.ndarray,
    u: np.ndarray,
    l2_reg: float,
    U: Optional[float] = None,
) -> np.ndarray:
  """Computes the analytic Hessian matrix of the weighted regularized loss.

  Hessian:
      H = (1 / U) * X^T diag(u * p * (1 - p)) X + 2 * lambda * I_d

  Args:
      w: Weight vector of shape (d,).
      X: Design matrix of shape (N, d).
      y: Labels of shape (N,).
      u: Sample influence weights of shape (N,).
      l2_reg: L2 regularization penalty parameter lambda >= 0.
      U: Optional precomputed sum(u_i).

  Returns:
      Hessian matrix of shape (d, d).
  """
  total_u = U if U is not None else float(np.sum(u))
  z = np.dot(X, w)
  p = compute_probabilities(z)
  d = len(w)
  diag_weights = u * p * (1.0 - p)
  # X.T @ diag(diag_weights) @ X
  hess_loss = np.dot(X.T, diag_weights[:, np.newaxis] * X) / total_u
  hess_reg = 2.0 * l2_reg * np.eye(d, dtype=np.float64)
  return hess_loss + hess_reg
