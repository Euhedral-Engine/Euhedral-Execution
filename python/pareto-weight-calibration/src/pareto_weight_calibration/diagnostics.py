"""Coefficient-fitting diagnostics and runtime parity verification.

Diagnostics:
    1. Python evaluator parity (marginal dot-product vs unrolled direct physical formula)
    2. Fixed-state prefix diagnostics (domain corners, grid monotonicity, prefix guarantee)
    3. Common-reference S_ref stability (angular distance, max directional diff, sign stability, factor A/B)
    4. Low-confidence ablation (filtering raw weight v < 0.1)
    5. Adjacent-lambda sensitivity analysis
"""

from __future__ import annotations

from dataclasses import dataclass, field
import math
from typing import Any, Dict, List, Optional, Tuple
import numpy as np

from pareto_weight_calibration.constraints import (
  MODEL_STRUCTURES,
  DomainConfig,
  build_physical_corner_matrix,
  check_corner_constraints,
  get_domain_corners,
  verify_fixed_state_prefix_monotonicity,
)
from pareto_weight_calibration.cv import (
  FoldResult,
  LOFOGridResult,
  LOFOResult,
  compute_fold_influence_weights,
)
from pareto_weight_calibration.evaluate import EvaluationMetrics, \
  evaluate_predictions
from pareto_weight_calibration.nested import STANDARD_LAMBDA_GRID
from pareto_weight_calibration.optimizer import OptimizationResult, \
  fit_constrained_model
from pareto_weight_calibration.scaling import compute_training_scales
from pareto_weight_calibration.types import ActiveStateFeatures, Dataset, \
  PairRecord


# -----------------------------------------------------------------------------
# 1. Python Evaluator Parity
# -----------------------------------------------------------------------------

def evaluate_direct_physical_marginal(
    w_phys: np.ndarray,
    c: float,
    smoothed_body_cost_ns: float,
    P: float,
    R: int,
    K: int,
) -> float:
  """Evaluates the participation marginal using the direct unrolled physical formula.

  Formula:
      b = ln(1 + smoothed_body_cost_ns)
      phrFactor = w0 + w1*c + w2*b + w3*R
      workerFactor = w4 + w5*c + w6*b + w7*R
      phr = P / (K * (K - 1))
      m = phrFactor * phr - workerFactor
  """
  if len(w_phys) != 8:
    raise ValueError(f"Expected 8-element physical vector, got {len(w_phys)}")
  if K <= 1:
    return 0.0

  b = math.log1p(smoothed_body_cost_ns)
  w = w_phys

  phr_factor = w[0] + w[1] * c + w[2] * b + w[3] * float(R)
  worker_factor = w[4] + w[5] * c + w[6] * b + w[7] * float(R)
  phr = float(P) / float(K * (K - 1))

  return phr_factor * phr - worker_factor


def verify_evaluator_parity(
    w_phys: np.ndarray,
    c: float,
    smoothed_body_cost_ns: float,
    P: float,
    R: int,
    K: int,
    tol: float = 1e-12,
) -> Tuple[bool, float, float, float]:
  """Verifies that feature vector dot-product x^T w agrees with direct unrolled formula.

  Returns:
      (passed, vec_marginal, direct_marginal, absolute_difference)
  """
  features = ActiveStateFeatures(
      c=c,
      smoothed_body_cost_ns=smoothed_body_cost_ns,
      b=math.log1p(smoothed_body_cost_ns),
      P=P,
      R=R,
      K=K,
  )
  vec_marginal = float(np.dot(features.feature_vector, w_phys))
  direct_marginal = evaluate_direct_physical_marginal(w_phys, c,
                                                      smoothed_body_cost_ns, P,
                                                      R, K)
  diff = abs(vec_marginal - direct_marginal)
  passed = bool(diff <= tol)
  return passed, vec_marginal, direct_marginal, diff


def verify_evaluator_parity_grid(
    w_phys: np.ndarray,
    domain: DomainConfig,
    grid_points: int = 5,
    tol: float = 1e-12,
) -> bool:
  """Verifies evaluator parity across a deterministic multidimensional domain grid."""
  c_vals = np.linspace(domain.c_min, domain.c_max, grid_points)
  b_vals = np.linspace(domain.body_cost_min_ns, domain.body_cost_max_ns,
                       grid_points)
  p_vals = [1.0, 4.0, 16.0, 64.0]
  r_vals = [domain.r_min, 7, 16, 23, domain.r_max]

  for c in c_vals:
    for b_ns in b_vals:
      for p in p_vals:
        for r in r_vals:
          for k in range(2, r + 1):
            passed, vm, dm, diff = verify_evaluator_parity(
                w_phys, c, b_ns, p, r, k, tol=tol
            )
            if not passed:
              raise ValueError(
                  f"Evaluator parity failure at (c={c}, b={b_ns}, P={p}, R={r}, K={k}): "
                  f"vec={vm:.14e}, direct={dm:.14e}, diff={diff:.14e}"
              )
  return True


# -----------------------------------------------------------------------------
# 2. Fixed-State Prefix Diagnostics
# -----------------------------------------------------------------------------

@dataclass(frozen=True)
class FixedStatePrefixDiagnosticResult:
  """Diagnostic audit of fixed-state prefix monotonicity and corner constraints."""
  all_corners_satisfied: bool
  corner_values: Dict[str, float]
  max_corner_value: float
  grid_monotonicity_satisfied: bool
  reversals_count: int
  grid_states_checked: int
  fixed_state_prefix_verified: bool
  summary: str


def diagnose_fixed_state_prefix(
    w_phys: np.ndarray,
    domain: DomainConfig,
    grid_points_per_axis: int = 6,
    tol: float = 1e-12,
) -> FixedStatePrefixDiagnosticResult:
  """Audits fixed-state prefix monotonicity across declared domain corners and grid.

  Note: This proves that for any FIXED (c, b, P, R) state, decision transitions
  monotonically with rank K (at most one transition from participate to CACHE).
  It does not claim live workers necessarily form a global rank prefix when worker-local
  inputs vary across threads.
  """
  # 1. Check all 8 declared domain corners
  corner_sat, corner_vals = check_corner_constraints(w_phys, domain,
                                                     tolerance=tol)
  corners = get_domain_corners(domain)
  corner_dict: Dict[str, float] = {}
  for i, (c_v, b_v, r_v) in enumerate(corners):
    name = f"c={c_v:.2f}_b={b_v:.2f}_R={int(r_v)}"
    corner_dict[name] = float(corner_vals[i])

  max_corner = float(np.max(corner_vals))

  # 2. Check grid monotonicity
  c_grid = np.linspace(domain.c_min, domain.c_max, grid_points_per_axis)
  b_grid = np.linspace(domain.body_cost_min_ns, domain.body_cost_max_ns,
                       grid_points_per_axis)
  p_grid = [1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0]
  r_grid = [domain.r_min, 7, 12, 16, 23, domain.r_max]

  reversals = 0
  states_checked = 0

  for c in c_grid:
    for b_ns in b_grid:
      for p in p_grid:
        for r in r_grid:
          states_checked += 1
          had_cache = False
          for k in range(2, r + 1):
            m = evaluate_direct_physical_marginal(w_phys, c, b_ns, p, r, k)
            action_is_cache = (m > 0.0)
            if action_is_cache:
              had_cache = True
            elif had_cache:
              # Reversal: previously cached at lower K, but now participates at higher K!
              reversals += 1

  grid_sat = (reversals == 0)
  verified = corner_sat and grid_sat

  summary = (
    f"Fixed-state prefix monotonicity: verified={verified} (corners_satisfied={corner_sat}, "
    f"max_corner_val={max_corner:.6e}, grid_states={states_checked}, reversals={reversals}). "
    f"Guarantees that under any fixed (c, b, P, R) state, decision transitions monotonically "
    f"at a single rank cutoff K*. Worker-local input variations prevent this from proving a "
    f"live global rank prefix across heterogeneous workers."
  )

  return FixedStatePrefixDiagnosticResult(
      all_corners_satisfied=corner_sat,
      corner_values=corner_dict,
      max_corner_value=max_corner,
      grid_monotonicity_satisfied=grid_sat,
      reversals_count=reversals,
      grid_states_checked=states_checked,
      fixed_state_prefix_verified=verified,
      summary=summary,
  )


# -----------------------------------------------------------------------------
# 3. Common-Reference Stability Diagnostics
# -----------------------------------------------------------------------------

@dataclass(frozen=True)
class CommonReferenceRepresentation:
  """Representation of physical weights in common reference coordinates S_ref."""
  w_phys: np.ndarray
  s_ref: np.ndarray
  a: np.ndarray  # a = S_ref * w_phys
  a_hat: np.ndarray  # normalized direction a_hat = a / ||a||
  norm_a: float


@dataclass(frozen=True)
class StabilityComparison:
  """Diagnostic comparison between two candidate weight vectors in common S_ref coordinates."""
  cosine_similarity: float
  angular_distance_deg: float
  max_directional_distance: float
  l2_directional_distance: float
  sign_stability_agreement: bool
  sign_mismatches: List[Tuple[int, str, str]]
  factor_a_corner_diffs: Dict[str, float]
  factor_b_corner_diffs: Dict[str, float]
  max_factor_a_corner_diff: float
  max_factor_b_corner_diff: float


def build_common_reference_scales(
    dataset: Dataset,
    active_indices: Optional[List[int]] = None,
) -> np.ndarray:
  """Computes a single full-dataset confidence-weighted S_ref for diagnostics only."""
  return compute_training_scales(
      X=dataset.X,
      u=dataset.u,
      active_indices=active_indices,
      constant_column_index=4,
  )


def compute_common_reference_representation(
    w_phys: np.ndarray,
    s_ref: np.ndarray,
) -> CommonReferenceRepresentation:
  """Maps physical weight vector into common-reference representation a = S_ref * w_phys."""
  if len(w_phys) != 8 or len(s_ref) != 8:
    raise ValueError("w_phys and s_ref must both have 8 elements")

  a = s_ref * w_phys
  norm_a = float(np.linalg.norm(a))
  if norm_a > 1e-15:
    a_hat = a / norm_a
  else:
    a_hat = np.zeros(8, dtype=np.float64)

  return CommonReferenceRepresentation(
      w_phys=w_phys,
      s_ref=s_ref,
      a=a,
      a_hat=a_hat,
      norm_a=norm_a,
  )


def get_coordinate_sign(val: float, tol: float = 1e-6) -> str:
  """Returns sign representation ('+', '-', or '0' if near zero within tol)."""
  if abs(val) <= tol:
    return "0"
  return "+" if val > 0.0 else "-"


def compare_common_reference_stability(
    w_phys_1: np.ndarray,
    w_phys_2: np.ndarray,
    s_ref: np.ndarray,
    domain: DomainConfig,
    sign_tol: float = 1e-6,
) -> StabilityComparison:
  """Performs complete common-reference stability diagnostics between two weight vectors."""
  rep1 = compute_common_reference_representation(w_phys_1, s_ref)
  rep2 = compute_common_reference_representation(w_phys_2, s_ref)

  # Cosine and angular distance with numerical boundary clamping
  dot_prod = float(np.dot(rep1.a_hat, rep2.a_hat))
  dot_clipped = float(np.clip(dot_prod, -1.0, 1.0))
  if dot_clipped >= 1.0 - 1e-12:
    cosine_sim = 1.0
    angular_deg = 0.0
  elif dot_clipped <= -1.0 + 1e-12:
    cosine_sim = -1.0
    angular_deg = 180.0
  else:
    cosine_sim = dot_clipped
    angular_deg = float(np.degrees(np.arccos(dot_clipped)))

  # Directional distances
  diff_dir = rep1.a_hat - rep2.a_hat
  max_dir_dist = float(np.max(np.abs(diff_dir)))
  l2_dir_dist = float(np.linalg.norm(diff_dir))

  # Sign stability check
  sign_mismatches: List[Tuple[int, str, str]] = []
  for idx in range(8):
    s1 = get_coordinate_sign(rep1.a[idx], tol=sign_tol)
    s2 = get_coordinate_sign(rep2.a[idx], tol=sign_tol)
    if s1 != s2 and s1 != "0" and s2 != "0":
      sign_mismatches.append((idx, s1, s2))

  sign_agreement = (len(sign_mismatches) == 0)

  # Factor A and Factor B corner contribution differences
  corners = get_domain_corners(domain)
  corner_a_diffs: Dict[str, float] = {}
  corner_b_diffs: Dict[str, float] = {}

  for (c_v, b_v, r_v) in corners:
    name = f"c={c_v:.2f}_b={b_v:.2f}_R={int(r_v)}"

    a1 = w_phys_1[0] + w_phys_1[1] * c_v + w_phys_1[2] * b_v + w_phys_1[
      3] * float(r_v)
    a2 = w_phys_2[0] + w_phys_2[1] * c_v + w_phys_2[2] * b_v + w_phys_2[
      3] * float(r_v)
    corner_a_diffs[name] = abs(a1 - a2)

    b1 = w_phys_1[4] + w_phys_1[5] * c_v + w_phys_1[6] * b_v + w_phys_1[
      7] * float(r_v)
    b2 = w_phys_2[4] + w_phys_2[5] * c_v + w_phys_2[6] * b_v + w_phys_2[
      7] * float(r_v)
    corner_b_diffs[name] = abs(b1 - b2)

  max_a_diff = float(max(corner_a_diffs.values())) if corner_a_diffs else 0.0
  max_b_diff = float(max(corner_b_diffs.values())) if corner_b_diffs else 0.0

  return StabilityComparison(
      cosine_similarity=cosine_sim,
      angular_distance_deg=angular_deg,
      max_directional_distance=max_dir_dist,
      l2_directional_distance=l2_dir_dist,
      sign_stability_agreement=sign_agreement,
      sign_mismatches=sign_mismatches,
      factor_a_corner_diffs=corner_a_diffs,
      factor_b_corner_diffs=corner_b_diffs,
      max_factor_a_corner_diff=max_a_diff,
      max_factor_b_corner_diff=max_b_diff,
  )


# -----------------------------------------------------------------------------
# 4. Low-Confidence Ablation
# -----------------------------------------------------------------------------

@dataclass(frozen=True)
class LowConfidenceAblationResult:
  """Audit result of low-confidence ablation (filtering raw weight v < 0.1)."""
  threshold_v: float
  original_count: int
  ablated_count: int
  removed_count: int
  original_weight: float
  ablated_weight: float
  baseline_fit: OptimizationResult
  ablated_fit: OptimizationResult
  stability_comparison: StabilityComparison


def run_low_confidence_ablation(
    dataset: Dataset,
    domain: DomainConfig,
    structure_name: str,
    l2_reg: float,
    threshold_v: float = 0.1,
    s_ref: Optional[np.ndarray] = None,
) -> LowConfidenceAblationResult:
  """Fits candidate model with rows where v < threshold_v removed and reports diagnostic change."""
  if s_ref is None:
    s_ref = build_common_reference_scales(dataset)

  # 1. Baseline fit on full dataset
  active_indices = MODEL_STRUCTURES[structure_name]
  baseline_scales = compute_training_scales(dataset.X, dataset.u,
                                            active_indices=active_indices)
  baseline_fit = fit_constrained_model(
      X_train=dataset.X,
      y_train=dataset.y,
      u_train=dataset.u,
      scales=baseline_scales,
      domain=domain,
      structure_name=structure_name,
      l2_reg=l2_reg,
      active_indices=active_indices,
  )

  # 2. Ablated dataset (remove raw pair_weight v < threshold_v)
  retained_records = [r for r in dataset.records if
                      r.pair_weight >= threshold_v]
  if len(retained_records) < 5:
    raise ValueError(
        f"Ablation with threshold v >= {threshold_v} retained only {len(retained_records)} records"
    )

  u_ablated, _ = compute_fold_influence_weights(retained_records)
  X_ablated = np.vstack([r.features.feature_vector for r in retained_records])
  y_ablated = np.array([r.y for r in retained_records], dtype=np.float64)

  ablated_scales = compute_training_scales(X_ablated, u_ablated,
                                           active_indices=active_indices)
  ablated_fit = fit_constrained_model(
      X_train=X_ablated,
      y_train=y_ablated,
      u_train=u_ablated,
      scales=ablated_scales,
      domain=domain,
      structure_name=structure_name,
      l2_reg=l2_reg,
      active_indices=active_indices,
  )

  # 3. Compare stability in common S_ref coordinates
  stability = compare_common_reference_stability(
      w_phys_1=baseline_fit.w_phys_full,
      w_phys_2=ablated_fit.w_phys_full,
      s_ref=s_ref,
      domain=domain,
  )

  return LowConfidenceAblationResult(
      threshold_v=threshold_v,
      original_count=len(dataset.records),
      ablated_count=len(retained_records),
      removed_count=len(dataset.records) - len(retained_records),
      original_weight=float(np.sum(dataset.u)),
      ablated_weight=float(np.sum(u_ablated)),
      baseline_fit=baseline_fit,
      ablated_fit=ablated_fit,
      stability_comparison=stability,
  )


# -----------------------------------------------------------------------------
# 5. Adjacent-Lambda Sensitivity
# -----------------------------------------------------------------------------

@dataclass(frozen=True)
class AdjacentLambdaSensitivityResult:
  """Sensitivity analysis evaluating stability across neighboring lambda candidates."""
  structure_name: str
  selected_lambda: float
  adjacent_lambdas: List[float]
  comparisons: Dict[float, StabilityComparison]
  regret_deltas: Dict[float, float]
  worst_family_deltas: Dict[float, float]


def run_adjacent_lambda_sensitivity(
    grid_result: LOFOGridResult,
    structure_name: str,
    s_ref: np.ndarray,
    domain: DomainConfig,
) -> AdjacentLambdaSensitivityResult:
  """Evaluates stability metrics between the selected lambda and its neighboring candidates."""
  selected_l2, selected_metrics = grid_result.selected_by_structure[
    structure_name]
  selected_lofo = grid_result.results[(structure_name, selected_l2)]

  # Compute average physical vector across the 9 LOFO folds for selected lambda
  fold_w_selected = np.mean(
      [f.opt_result.w_phys_full for f in selected_lofo.fold_results], axis=0
  )

  # Find adjacent lambdas in STANDARD_LAMBDA_GRID
  grid = sorted(list(set(grid_result.all_lambdas)))
  idx = grid.index(selected_l2) if selected_l2 in grid else -1

  adjacent_lambdas: List[float] = []
  if idx > 0:
    adjacent_lambdas.append(grid[idx - 1])
  if idx >= 0 and idx < len(grid) - 1:
    adjacent_lambdas.append(grid[idx + 1])

  comparisons: Dict[float, StabilityComparison] = {}
  regret_deltas: Dict[float, float] = {}
  worst_family_deltas: Dict[float, float] = {}

  for adj_l2 in adjacent_lambdas:
    adj_lofo = grid_result.results[(structure_name, adj_l2)]
    fold_w_adj = np.mean(
        [f.opt_result.w_phys_full for f in adj_lofo.fold_results], axis=0
    )

    stab = compare_common_reference_stability(
        w_phys_1=fold_w_selected,
        w_phys_2=fold_w_adj,
        s_ref=s_ref,
        domain=domain,
    )
    comparisons[adj_l2] = stab

    diff_regret = adj_lofo.pooled_metrics.supported_rel_regret - selected_metrics.supported_rel_regret
    diff_worst = adj_lofo.pooled_metrics.worst_family_rel_regret - selected_metrics.worst_family_rel_regret

    regret_deltas[adj_l2] = diff_regret
    worst_family_deltas[adj_l2] = diff_worst

  return AdjacentLambdaSensitivityResult(
      structure_name=structure_name,
      selected_lambda=selected_l2,
      adjacent_lambdas=adjacent_lambdas,
      comparisons=comparisons,
      regret_deltas=regret_deltas,
      worst_family_deltas=worst_family_deltas,
  )
