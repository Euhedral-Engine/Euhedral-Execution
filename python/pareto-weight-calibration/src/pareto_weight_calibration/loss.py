"""Numerically stable unclipped loss, gradient, and Hessian for Pareto-weight fitting."""

from __future__ import annotations

from typing import Optional, Tuple, Union
import numpy as np
from scipy.special import expit
import torch

from pareto_weight_calibration.device import DTYPE, is_cuda, resolve_device, \
  to_numpy, to_tensor


def unclipped_binary_cross_entropy(
    y: Union[np.ndarray, torch.Tensor],
    z: Union[np.ndarray, torch.Tensor],
) -> Union[np.ndarray, torch.Tensor]:
  """Computes unclipped, smooth binary cross-entropy loss.

  Formula:
      ell(y, z) = logaddexp(0, z) - y * z

  Args:
      y: Target label array or tensor of shape (N,) in [0.0, 1.0].
      z: Linear predictor margin array or tensor of shape (N,).

  Returns:
      Array or tensor of per-sample losses of shape (N,).
  """
  if isinstance(y, torch.Tensor) or isinstance(z, torch.Tensor):
    y_t = to_tensor(y)
    z_t = to_tensor(z)
    zero_t = torch.tensor(0.0, dtype=z_t.dtype, device=z_t.device)
    return torch.logaddexp(zero_t, z_t) - y_t * z_t
  return np.logaddexp(0.0, z) - y * z


def compute_probabilities(
    z: Union[np.ndarray, torch.Tensor],
) -> Union[np.ndarray, torch.Tensor]:
  """Computes sigmoid probabilities p = expit(z) = 1 / (1 + exp(-z))."""
  if isinstance(z, torch.Tensor):
    return torch.sigmoid(z)
  return expit(z)


def weighted_regularized_loss(
    w: Union[np.ndarray, torch.Tensor],
    X: Union[np.ndarray, torch.Tensor],
    y: Union[np.ndarray, torch.Tensor],
    u: Union[np.ndarray, torch.Tensor],
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
  if isinstance(w, torch.Tensor) or isinstance(X, torch.Tensor):
    w_t = to_tensor(w)
    X_t = to_tensor(X, device=w_t.device)
    y_t = to_tensor(y, device=w_t.device)
    u_t = to_tensor(u, device=w_t.device)
    total_u = U if U is not None else float(torch.sum(u_t).item())
    z_t = X_t @ w_t
    per_sample_loss = unclipped_binary_cross_entropy(y_t, z_t)
    weighted_loss = float(torch.sum(u_t * per_sample_loss).item()) / total_u
    reg_loss = float(l2_reg * torch.sum(w_t ** 2).item())
    return weighted_loss + reg_loss

  total_u = U if U is not None else float(np.sum(u))
  z = np.dot(X, w)
  per_sample_loss = unclipped_binary_cross_entropy(y, z)
  weighted_loss = float(np.sum(u * per_sample_loss)) / total_u
  reg_loss = float(l2_reg * np.sum(w ** 2))
  return weighted_loss + reg_loss


def loss_gradient(
    w: Union[np.ndarray, torch.Tensor],
    X: Union[np.ndarray, torch.Tensor],
    y: Union[np.ndarray, torch.Tensor],
    u: Union[np.ndarray, torch.Tensor],
    l2_reg: float,
    U: Optional[float] = None,
) -> Union[np.ndarray, torch.Tensor]:
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
  if isinstance(w, torch.Tensor) or isinstance(X, torch.Tensor):
    w_t = to_tensor(w)
    X_t = to_tensor(X, device=w_t.device)
    y_t = to_tensor(y, device=w_t.device)
    u_t = to_tensor(u, device=w_t.device)
    total_u = U if U is not None else float(torch.sum(u_t).item())
    z_t = X_t @ w_t
    p_t = compute_probabilities(z_t)
    residual = u_t * (p_t - y_t)
    grad_loss = (X_t.T @ residual) / total_u
    grad_reg = 2.0 * l2_reg * w_t
    return grad_loss + grad_reg

  total_u = U if U is not None else float(np.sum(u))
  z = np.dot(X, w)
  p = compute_probabilities(z)
  residual = u * (p - y)
  grad_loss = np.dot(X.T, residual) / total_u
  grad_reg = 2.0 * l2_reg * w
  return grad_loss + grad_reg


def loss_hessian(
    w: Union[np.ndarray, torch.Tensor],
    X: Union[np.ndarray, torch.Tensor],
    y: Union[np.ndarray, torch.Tensor],
    u: Union[np.ndarray, torch.Tensor],
    l2_reg: float,
    U: Optional[float] = None,
) -> Union[np.ndarray, torch.Tensor]:
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
  if isinstance(w, torch.Tensor) or isinstance(X, torch.Tensor):
    w_t = to_tensor(w)
    X_t = to_tensor(X, device=w_t.device)
    y_t = to_tensor(y, device=w_t.device)
    u_t = to_tensor(u, device=w_t.device)
    total_u = U if U is not None else float(torch.sum(u_t).item())
    z_t = X_t @ w_t
    p_t = compute_probabilities(z_t)
    d = len(w_t)
    diag_weights = u_t * p_t * (1.0 - p_t)
    hess_loss = (X_t.T @ (diag_weights.unsqueeze(1) * X_t)) / total_u
    hess_reg = 2.0 * l2_reg * torch.eye(d, dtype=DTYPE, device=w_t.device)
    return hess_loss + hess_reg

  total_u = U if U is not None else float(np.sum(u))
  z = np.dot(X, w)
  p = compute_probabilities(z)
  d = len(w)
  diag_weights = u * p * (1.0 - p)
  hess_loss = np.dot(X.T, diag_weights[:, np.newaxis] * X) / total_u
  hess_reg = 2.0 * l2_reg * np.eye(d, dtype=np.float64)
  return hess_loss + hess_reg
