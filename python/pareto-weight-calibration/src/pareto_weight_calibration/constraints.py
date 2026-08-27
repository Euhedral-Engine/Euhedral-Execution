"""Deployment-domain corner constraints and fixed-state prefix monotonicity verification."""

from __future__ import annotations

import itertools
import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
import numpy as np

from pareto_weight_calibration.types import DomainConfig

MODEL_STRUCTURES: Dict[str, List[int]] = {
  "M2": [0, 4],
  "M4-C": [0, 1, 4, 5],
  "M4-B": [0, 2, 4, 6],
  "M4-R": [0, 3, 4, 7],
  "M6-CB": [0, 1, 2, 4, 5, 6],
  "M6-CR": [0, 1, 3, 4, 5, 7],
  "M6-BR": [0, 2, 3, 4, 6, 7],
  "M8": [0, 1, 2, 3, 4, 5, 6, 7],
}


def load_domain_config(path: Optional[Path] = None) -> DomainConfig:
  """Loads DomainConfig from a JSON file, or returns default deployment configuration."""
  if path is not None and path.exists():
    data = json.loads(path.read_text(encoding="utf-8"))
    return DomainConfig.from_dict(data)
  return DomainConfig()


def save_domain_config(domain: DomainConfig, path: Path) -> None:
  """Saves DomainConfig to a JSON file."""
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(json.dumps(domain.to_dict(), indent=2) + "\n",
                  encoding="utf-8")


def get_domain_corners(domain: DomainConfig) -> List[
  Tuple[float, float, float]]:
  """Returns the 8 corner vertices (c, b, R) of the declared deployment domain."""
  c_vals = [domain.c_min, domain.c_max]
  b_vals = [domain.b_min, domain.b_max]
  r_vals = [float(domain.r_min), float(domain.r_max)]
  return list(itertools.product(c_vals, b_vals, r_vals))


def build_physical_corner_matrix(domain: DomainConfig) -> np.ndarray:
  """Builds the 8x8 physical corner constraint matrix C_A for A(c, b, R) <= 0.

  Each row v of C_A represents a corner (c_v, b_v, R_v):
      [1.0, c_v, b_v, R_v, 0.0, 0.0, 0.0, 0.0]

  Constraint:
      C_A @ w_phys <= 0
  """
  corners = get_domain_corners(domain)
  C_A = np.zeros((8, 8), dtype=np.float64)
  for idx, (c_v, b_v, r_v) in enumerate(corners):
    C_A[idx, 0] = 1.0
    C_A[idx, 1] = c_v
    C_A[idx, 2] = b_v
    C_A[idx, 3] = r_v
  return C_A


def build_projected_scaled_constraint_matrix(
    domain: DomainConfig,
    scales: np.ndarray,
    active_indices: Optional[List[int]] = None,
) -> np.ndarray:
  """Builds the projected, scaled constraint matrix C_tilde_{A, M} for candidate model M.

  Formula:
      C_tilde_{A, M} = C_{A, M} @ S_M^{-1}

  Args:
      domain: Versioned deployment domain configuration.
      scales: Feature scales array for full 8 features (or active subset).
      active_indices: List of active coefficient indices J_M (default all 8).

  Returns:
      Matrix of shape (8, |J_M|).
  """
  full_C_A = build_physical_corner_matrix(domain)
  if active_indices is None:
    active_indices = list(range(8))

  # Select columns corresponding to active indices
  C_A_M = full_C_A[:, active_indices]

  # Get active scales
  if len(scales) == len(active_indices):
    active_scales = scales
  elif len(scales) == 8:
    active_scales = scales[active_indices]
  else:
    raise ValueError(
        f"Scales length {len(scales)} matches neither active count "
        f"{len(active_indices)} nor full count 8"
    )

  if np.any(active_scales <= 0.0) or not np.all(np.isfinite(active_scales)):
    raise ValueError("Active feature scales must be positive and finite")

  # C_A_M @ diag(1 / active_scales)
  return C_A_M / active_scales


def check_corner_constraints(
    w_phys: np.ndarray,
    domain: DomainConfig,
    tolerance: float = 1e-12,
) -> Tuple[bool, np.ndarray]:
  """Checks whether A(c_v, b_v, R_v) <= tolerance at all 8 domain corners.

  Returns:
      (is_satisfied, corner_values)
  """
  if len(w_phys) != 8:
    raise ValueError(f"Expected 8 physical weights, got {len(w_phys)}")
  C_A = build_physical_corner_matrix(domain)
  corner_values = np.dot(C_A, w_phys)
  is_satisfied = bool(np.all(corner_values <= tolerance))
  return is_satisfied, corner_values


def verify_fixed_state_prefix_monotonicity(
    w_phys: np.ndarray,
    domain: DomainConfig,
    c_steps: int = 5,
    body_steps: int = 5,
    p_steps: int = 5,
    tolerance: float = 1e-12,
) -> bool:
  """Verifies that for any fixed state (c, b, P, R) in the domain, m(K+1) >= m(K) for discrete K.

  Under A(c, b, R) <= 0:
      m(K+1) - m(K) = - A(c, b, R) * 2P / (K * (K^2 - 1)) >= 0

  Args:
      w_phys: 8-element physical weight vector.
      domain: DomainConfig declaring bounds.
      c_steps: Number of grid steps for contention c.
      body_steps: Number of grid steps for body cost.
      p_steps: Number of grid steps for productive handles P.
      tolerance: Numerical tolerance for non-negativity.

  Returns:
      True if monotonicity holds across all grid points and discrete ranks.
  """
  if len(w_phys) != 8:
    raise ValueError(f"Expected 8 physical weights, got {len(w_phys)}")

  # Check that corner constraints hold first
  sat, vals = check_corner_constraints(w_phys, domain, tolerance=tolerance)
  if not sat:
    raise ValueError(
        f"Cannot guarantee prefix monotonicity: corner constraint violated (max A = {np.max(vals):.6e})"
    )

  c_grid = np.linspace(domain.c_min, domain.c_max, c_steps)
  b_grid = np.linspace(domain.b_min, domain.b_max, body_steps)
  p_grid = np.linspace(domain.p_min, domain.p_max, p_steps)
  r_vals = [domain.r_min, domain.r_max]

  for c in c_grid:
    for b in b_grid:
      for r in r_vals:
        A = w_phys[0] + w_phys[1] * c + w_phys[2] * b + w_phys[3] * float(r)
        B = w_phys[4] + w_phys[5] * c + w_phys[6] * b + w_phys[7] * float(r)
        for p in p_grid:
          for k in range(domain.k_min, r):
            # m(K) = A * P / (K*(K-1)) - B
            m_k = A * p / float(k * (k - 1)) - B
            m_next = A * p / float((k + 1) * k) - B
            diff = m_next - m_k
            if diff < -tolerance:
              raise ValueError(
                  f"Prefix monotonicity violation at c={c:.3f}, b={b:.3f}, R={r}, P={p:.2f}, "
                  f"K={k}: m(K+1) - m(K) = {diff:.14e} < -{tolerance}"
              )

  return True
