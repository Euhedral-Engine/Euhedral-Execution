"""Deterministic constrained optimizer for scale-normalized Pareto weights."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple
import numpy as np
import scipy.optimize

from pareto_weight_calibration.constraints import (
  MODEL_STRUCTURES,
  build_physical_corner_matrix,
  build_projected_scaled_constraint_matrix,
  check_corner_constraints,
)
from pareto_weight_calibration.loss import (
  loss_gradient,
  loss_hessian,
  weighted_regularized_loss,
)
from pareto_weight_calibration.scaling import (
  back_transform_weights,
  verify_scale_invariance,
)
from pareto_weight_calibration.types import DomainConfig


@dataclass(frozen=True)
class OptimizationResult:
  """Detailed record of a single deterministic constrained fit."""
  structure_name: str
  l2_reg: float
  active_indices: List[int]
  w_scaled: np.ndarray
  w_phys_active: np.ndarray
  w_phys_full: np.ndarray
  success: bool
  status: int
  termination_reason: str
  iterations: int
  final_loss: float
  constraint_violation: float
  kkt_residual: float
  gradient_norm: float
  corner_values: np.ndarray


def compute_kkt_stationarity_residual(
    grad: np.ndarray,
    C_tilde: np.ndarray,
    w_scaled: np.ndarray,
    active_tol: float = 1e-6,
) -> Tuple[float, np.ndarray]:
  """Computes KKT stationarity residual ||grad + C_tilde^T * mu||_2 for C_tilde * w <= 0.

  Finds non-negative dual multipliers mu >= 0 via Non-Negative Least Squares (NNLS).

  Args:
      grad: Objective gradient at the solution of shape (d,).
      C_tilde: Scaled constraint matrix of shape (8, d).
      w_scaled: Scaled weight vector of shape (d,).
      active_tol: Inactive constraint tolerance.

  Returns:
      (kkt_residual, mu)
  """
  # Solve min ||C_tilde.T @ mu - (-grad)||_2 subject to mu >= 0
  mu, _ = scipy.optimize.nnls(C_tilde.T, -grad)

  # Zero out dual multipliers for strictly inactive constraints (C_tilde @ w < -active_tol)
  corner_vals = np.dot(C_tilde, w_scaled)
  inactive_mask = (corner_vals < -active_tol)
  mu_active = mu.copy()
  mu_active[inactive_mask] = 0.0

  stationarity_res = float(np.linalg.norm(grad + np.dot(C_tilde.T, mu_active)))
  return stationarity_res, mu_active


def fit_constrained_model(
    X_train: np.ndarray,
    y_train: np.ndarray,
    u_train: np.ndarray,
    scales: np.ndarray,
    domain: DomainConfig,
    structure_name: str = "M8",
    l2_reg: float = 1e-3,
    active_indices: Optional[List[int]] = None,
    max_iter: int = 1000,
    ftol: float = 1e-15,
) -> OptimizationResult:
  """Performs deterministic constrained fitting for a candidate structure and lambda.

  Optimizer Contract:
      - Primary optimizer: scipy.optimize.minimize with method='SLSQP'
      - Initialization: Feasible zero vector w_0 = 0
      - Scaled objective: L_scaled(w_scaled; lambda)
      - Scaled corner constraints: C_tilde_{A, M} @ w_scaled <= 0
      - Back-transform: w_phys = w_scaled / scales
      - Embedding: Inactive coefficients are exact 0.0 in 8-element vector

  Args:
      X_train: Unscaled training feature matrix of shape (N, 8) or (N, d).
      y_train: Training labels of shape (N,).
      u_train: Positive sample influence weights of shape (N,).
      scales: Active feature scales of shape (8,) or (d,).
      domain: Frozen deployment domain configuration.
      structure_name: Canonical model structure name (e.g. 'M2', 'M4-C', 'M8').
      l2_reg: L2 regularization penalty parameter lambda >= 0.
      active_indices: Optional active coefficient indices (defaults from structure_name).
      max_iter: Maximum solver iterations.
      ftol: Objective function convergence tolerance.

  Returns:
      OptimizationResult with full provenance and diagnostic residuals.
  """
  if active_indices is None:
    if structure_name not in MODEL_STRUCTURES:
      raise ValueError(
        f"Unknown structure {structure_name}, must be one of {list(MODEL_STRUCTURES.keys())}")
    active_indices = MODEL_STRUCTURES[structure_name]

  d_active = len(active_indices)
  if X_train.shape[1] == 8:
    X_sub = X_train[:, active_indices]
  elif X_train.shape[1] == d_active:
    X_sub = X_train
  else:
    raise ValueError(
      f"X_train columns {X_train.shape[1]} does not match active {d_active} or full 8")

  if len(scales) == 8:
    scales_active = scales[active_indices]
  elif len(scales) == d_active:
    scales_active = scales
  else:
    raise ValueError(
      f"Scales length {len(scales)} does not match active {d_active} or full 8")

  # Scale training features: X_tilde = X / scales
  X_scaled = X_sub / scales_active
  U = float(np.sum(u_train))

  # Build scaled projected corner constraint matrix C_tilde_{A, M} of shape (8, d_active)
  C_tilde = build_projected_scaled_constraint_matrix(
      domain=domain,
      scales=scales,
      active_indices=active_indices,
  )

  # Constraint definition for SLSQP: ineq constraint g(w) >= 0 => -C_tilde @ w >= 0
  constraints = {
    "type": "ineq",
    "fun": lambda w: -np.dot(C_tilde, w),
    "jac": lambda w: -C_tilde,
  }

  def objective(w: np.ndarray) -> float:
    return weighted_regularized_loss(w, X_scaled, y_train, u_train, l2_reg, U=U)

  def gradient(w: np.ndarray) -> np.ndarray:
    return loss_gradient(w, X_scaled, y_train, u_train, l2_reg, U=U)

  # Feasible zero initialization
  w0 = np.zeros(d_active, dtype=np.float64)

  # Execute deterministic constrained optimization
  opt_res = scipy.optimize.minimize(
      fun=objective,
      x0=w0,
      jac=gradient,
      constraints=constraints,
      method="SLSQP",
      options={
        "ftol": ftol,
        "maxiter": max_iter,
        "disp": False,
      },
  )

  w_scaled = np.array(opt_res.x, dtype=np.float64)
  final_loss = float(opt_res.fun)
  success = bool(opt_res.success)
  status = int(opt_res.status)
  termination_reason = str(opt_res.message)
  iterations = int(getattr(opt_res, "nit", 0))

  # Analytical back-transform to physical coordinates
  w_phys_active = w_scaled / scales_active

  # Full 8-element embedding with exact zeroes
  w_phys_full = np.zeros(8, dtype=np.float64)
  for i, idx in enumerate(active_indices):
    w_phys_full[idx] = w_phys_active[i]

  # Verify scale invariance on training set
  if X_train.shape[1] == 8:
    verify_scale_invariance(
        X=X_train,
        w_phys=w_phys_full,
        X_scaled=X_scaled,
        w_scaled=w_scaled,
        atol=1e-12,
        rtol=1e-12,
    )

  # Check corner constraints
  corner_sat, corner_values = check_corner_constraints(w_phys_full, domain,
                                                       tolerance=1e-12)
  constraint_violation = float(max(0.0, float(np.max(corner_values))))

  # Compute gradient and KKT residuals
  grad_at_solution = gradient(w_scaled)
  gradient_norm = float(np.linalg.norm(grad_at_solution))
  kkt_residual, _ = compute_kkt_stationarity_residual(grad_at_solution, C_tilde,
                                                      w_scaled)

  return OptimizationResult(
      structure_name=structure_name,
      l2_reg=l2_reg,
      active_indices=active_indices,
      w_scaled=w_scaled,
      w_phys_active=w_phys_active,
      w_phys_full=w_phys_full,
      success=success,
      status=status,
      termination_reason=termination_reason,
      iterations=iterations,
      final_loss=final_loss,
      constraint_violation=constraint_violation,
      kkt_residual=kkt_residual,
      gradient_norm=gradient_norm,
      corner_values=corner_values,
  )
