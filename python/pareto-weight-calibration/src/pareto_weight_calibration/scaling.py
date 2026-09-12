"""Feature scale normalization without mean centering and coordinate transformations."""

from __future__ import annotations

from typing import List, Optional
import numpy as np


def compute_training_scales(
    X: np.ndarray,
    u: np.ndarray,
    active_indices: Optional[List[int]] = None,
    constant_column_index: Optional[int] = 4,
) -> np.ndarray:
  """Computes confidence-weighted RMS feature scales without mean centering.

  Formula:
      s_j = sqrt( sum(u_i * X_ij^2) / sum(u_i) )

  Args:
      X: Feature matrix of shape (N, d).
      u: Positive bounded influence weights of shape (N,).
      active_indices: Optional list of active column indices to compute scales for.
      constant_column_index: Index of structurally constant column (-1.0), default 4.

  Returns:
      1D numpy array of scales corresponding to columns of X (or active columns).

  Raises:
      ValueError: If weights are invalid, or if any active nonconstant column has
                  zero or non-finite scale (making candidate unidentified).
  """
  if X.ndim != 2:
    raise ValueError(f"Expected 2D feature matrix, got shape {X.shape}")
  if u.ndim != 1 or len(u) != X.shape[0]:
    raise ValueError(
      f"Weight vector u length {len(u)} does not match X rows {X.shape[0]}")

  total_u = float(np.sum(u))
  if total_u <= 0.0 or not np.isfinite(total_u):
    raise ValueError(
      f"Total influence weight U={total_u} must be positive and finite")

  num_cols = X.shape[1]
  cols_to_check = active_indices if active_indices is not None else list(
    range(num_cols))
  scales = np.zeros(num_cols, dtype=np.float64)

  for j in cols_to_check:
    # Check for structurally constant column (-1.0)
    if constant_column_index is not None and j == constant_column_index:
      scales[j] = 1.0
      continue

    weighted_sq_sum = float(np.sum(u * (X[:, j] ** 2)))
    rms = np.sqrt(weighted_sq_sum / total_u)

    if not np.isfinite(rms) or rms <= 1e-15:
      raise ValueError(
          f"Candidate feature column {j} has non-positive or non-finite scale ({rms:.6e}). "
          f"Candidate is empirically unidentified."
      )
    scales[j] = float(rms)

  # For any columns not in cols_to_check, set scale to 1.0 to avoid division by zero
  for j in range(num_cols):
    if j not in cols_to_check:
      scales[j] = 1.0

  return scales


def scale_features(X: np.ndarray, scales: np.ndarray) -> np.ndarray:
  """Scales feature columns by dividing by scales (X_scaled = X @ diag(1/s))."""
  if np.any(scales <= 0.0) or not np.all(np.isfinite(scales)):
    raise ValueError("Scales must be positive and finite")
  return X / scales


def back_transform_weights(w_scaled: np.ndarray,
    scales: np.ndarray) -> np.ndarray:
  """Transforms scaled optimizer weights to physical weights (w_phys = w_scaled / scales)."""
  if len(w_scaled) != len(scales):
    raise ValueError(
      f"Length mismatch: w_scaled has {len(w_scaled)}, scales has {len(scales)}")
  return w_scaled / scales


def transform_weights_to_scaled(w_phys: np.ndarray,
    scales: np.ndarray) -> np.ndarray:
  """Transforms physical weights to scaled optimizer weights (w_scaled = w_phys * scales)."""
  if len(w_phys) != len(scales):
    raise ValueError(
      f"Length mismatch: w_phys has {len(w_phys)}, scales has {len(scales)}")
  return w_phys * scales


def verify_scale_invariance(
    X: np.ndarray,
    w_phys: np.ndarray,
    X_scaled: np.ndarray,
    w_scaled: np.ndarray,
    atol: float = 1e-12,
    rtol: float = 1e-12,
) -> bool:
  """Verifies invariant: x_i^T w_phys == x_tilde_i^T w_scaled across all rows."""
  z_phys = np.dot(X, w_phys)
  z_scaled = np.dot(X_scaled, w_scaled)
  diff = np.abs(z_phys - z_scaled)
  max_diff = float(np.max(diff))
  if not np.allclose(z_phys, z_scaled, atol=atol, rtol=rtol):
    raise ValueError(
        f"Scale invariance violation: max abs diff = {max_diff:.14e} > atol={atol}"
    )
  return True
