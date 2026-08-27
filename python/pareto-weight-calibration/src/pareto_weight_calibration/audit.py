"""Phase 0B dataset assembly, bounded family influence, and identifiability audit."""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple
import numpy as np

from pareto_weight_calibration.types import (
  ArtifactEligibility,
  Dataset,
  IdentifiabilityAuditResult,
  Outcome,
  PairRecord,
)


def get_physical_family_id(record: PairRecord) -> str:
  """Constructs the canonical 9-family physical key: Fam_R{R}_S{S}_WU{WU}.

  Args:
      record: PairRecord containing physical coordinates and trial configuration.

  Returns:
      Canonical family identifier string.
  """
  r_val = int(record.features.R)
  s_val = int(record.parallel_sources)
  wu_val = int(record.work_units)
  return f"Fam_R{r_val}_S{s_val}_WU{wu_val}"


def compute_bounded_family_influence(
    records: List[PairRecord],
) -> Tuple[np.ndarray, np.ndarray, List[str], Dict[str, float], Dict[
  str, Dict[str, Any]], float, float]:
  """Computes bounded family influence scales and observation weights.

  Formula:
      familyScale(F) = 1.0 / max(1.0, sum_{j in F} v_j)
      u_i = v_i * familyScale(F(i))
      U = sum(u_i)
      N_eff = (sum u_i)^2 / sum(u_i^2)

  Args:
      records: List of eligible PairRecord objects.

  Returns:
      Tuple of:
        - v: raw Step 4 weights array (N,)
        - u: bounded influence weights array (N,)
        - families: list of family strings (N,)
        - family_scales: dict mapping family ID to scale factor
        - family_counts: dict mapping family ID to summary stats
        - U: total dataset weight sum(u_i)
        - n_eff: effective sample size
  """
  n = len(records)
  v = np.array([r.pair_weight for r in records], dtype=np.float64)
  families = [get_physical_family_id(r) for r in records]

  # Compute family sum of v
  family_v_sum: Dict[str, float] = {}
  family_counts: Dict[str, Dict[str, Any]] = {}

  for i, r in enumerate(records):
    fam = families[i]
    w = float(v[i])
    family_v_sum[fam] = family_v_sum.get(fam, 0.0) + w
    if fam not in family_counts:
      family_counts[fam] = {
        "total_rows": 0,
        "k_wins": 0,
        "k_minus_1_wins": 0,
        "stable_tie": 0,
        "raw_weight_sum": 0.0,
        "scaled_weight_sum": 0.0,
        "r": r.features.R,
        "s": r.parallel_sources,
        "wu": r.work_units,
      }
    fc = family_counts[fam]
    fc["total_rows"] += 1
    fc["raw_weight_sum"] += w
    if r.effective_outcome == Outcome.K_WINS:
      fc["k_wins"] += 1
    elif r.effective_outcome == Outcome.K_MINUS_1_WINS:
      fc["k_minus_1_wins"] += 1
    elif r.effective_outcome == Outcome.STABLE_TIE:
      fc["stable_tie"] += 1

  # familyScale(F) = 1 / max(1.0, sum(v_j))
  family_scales: Dict[str, float] = {}
  for fam, v_tot in family_v_sum.items():
    scale = 1.0 / max(1.0, v_tot)
    family_scales[fam] = scale
    family_counts[fam]["family_scale"] = scale
    family_counts[fam]["scaled_weight_sum"] = v_tot * scale

  # u_i = v_i * familyScale(F(i))
  u = np.zeros(n, dtype=np.float64)
  for i in range(n):
    fam = families[i]
    u[i] = v[i] * family_scales[fam]

  U = float(np.sum(u))
  sum_u_sq = float(np.sum(u ** 2))
  n_eff = (U ** 2) / sum_u_sq if sum_u_sq > 0.0 else 0.0

  return v, u, families, family_scales, family_counts, U, n_eff


def build_dataset(
    records: List[PairRecord],
    min_weight: float = 0.0,
    require_eligible_only: bool = True,
    require_all_records_retained: bool = False,
) -> Dataset:
  """Assembles X, y, v, u, and physical families from PairRecord list.

  Args:
      records: List of candidate PairRecord items.
      min_weight: Minimum weight threshold (default 0.0 filters out 0-weight pairs).
      require_eligible_only: If True, filters only ArtifactEligibility.ELIGIBLE records.

  Returns:
      Dataset dataclass.
  """
  eligible: List[PairRecord] = []
  excluded: List[str] = []
  for r in records:
    if require_eligible_only and r.eligibility != ArtifactEligibility.ELIGIBLE:
      excluded.append(
        f"{r.pair_id}: artifact eligibility is {r.eligibility.value}")
      continue
    if not np.isfinite(r.pair_weight) or r.pair_weight <= min_weight:
      excluded.append(
        f"{r.pair_id}: confidence v={r.pair_weight!r} is not > {min_weight}")
      continue
    expected_y = {
      Outcome.K_WINS: 0.0,
      Outcome.K_MINUS_1_WINS: 1.0,
      Outcome.STABLE_TIE: 0.5,
    }.get(r.effective_outcome)
    if expected_y is None:
      excluded.append(
          f"{r.pair_id}: effective outcome {r.effective_outcome.value} is not a training outcome"
      )
      continue
    if r.y != expected_y:
      excluded.append(
          f"{r.pair_id}: y={r.y} does not match {r.effective_outcome.value} (expected {expected_y})"
      )
      continue
    if r.label_evidence_basis.value == "NONE":
      excluded.append(f"{r.pair_id}: retained row has no label evidence basis")
      continue
    eligible.append(r)

  if require_all_records_retained and excluded:
    raise ValueError(
        "Frozen training manifest contains excluded rows: " + "; ".join(
          excluded)
    )

  if not eligible:
    raise ValueError(
        f"No eligible records with pair_weight > {min_weight} available for dataset assembly"
    )

  X = np.vstack([r.features.feature_vector for r in eligible])
  y = np.array([r.y for r in eligible], dtype=np.float64)

  v, u, families, family_scales, family_counts, U, n_eff = compute_bounded_family_influence(
      eligible
  )

  return Dataset(
      records=eligible,
      X=X,
      y=y,
      v=v,
      u=u,
      families=families,
      family_scales=family_scales,
      family_counts=family_counts,
      U=U,
      n_eff=n_eff,
  )


def compute_lapack_rank_and_singular_spectrum(
    X: np.ndarray,
) -> Tuple[int, np.ndarray, float]:
  """Computes numerical matrix rank and singular spectrum using LAPACK tolerance.

  LAPACK-style threshold:
      tau = sigma_1 * max(N, d) * eps_mach

  Returns:
      (rank, singular_values, threshold)
  """
  n, d = X.shape
  eps_mach = np.finfo(np.float64).eps
  s = np.linalg.svd(X, compute_uv=False)
  sigma_1 = s[0] if len(s) > 0 else 0.0
  threshold = sigma_1 * max(n, d) * eps_mach
  rank = int(np.sum(s > threshold))
  return rank, s, float(threshold)


def compute_nonconstant_vifs_and_collinearity(
    X: np.ndarray,
    nonconstant_indices: Optional[List[int]] = None,
    constant_index: int = 4,
    tol: float = 1e-12,
) -> Tuple[Dict[int, float], List[str]]:
  """Computes Variance Inflation Factors (VIF) and linear dependencies for nonconstant columns.

  Args:
      X: Feature matrix of shape (N, d).
      nonconstant_indices: List of column indices to evaluate (default [0, 1, 2, 3, 5, 6, 7]).
      constant_index: Index of constant column (-1.0).
      tol: Numerical tolerance for singularity.

  Returns:
      (vifs_dict, collinear_dependencies_list)
  """
  if nonconstant_indices is None:
    nonconstant_indices = [i for i in range(X.shape[1]) if i != constant_index]

  vifs: Dict[int, float] = {}
  dependencies: List[str] = []

  # Include constant column as explicit intercept in auxiliary regressions
  const_col = X[:, constant_index:constant_index + 1] if constant_index < \
                                                         X.shape[
                                                           1] else np.ones(
      (X.shape[0], 1))

  for target_idx in nonconstant_indices:
    other_indices = [i for i in nonconstant_indices if i != target_idx]
    X_others = np.hstack([const_col, X[:, other_indices]])
    y_target = X[:, target_idx]

    # Auxiliary regression y_target ~ X_others
    try:
      # Solve least squares
      coef, residuals, rank, s = np.linalg.lstsq(X_others, y_target, rcond=None)
      pred = np.dot(X_others, coef)
      ss_tot = np.sum((y_target - np.mean(y_target)) ** 2)
      ss_res = np.sum((y_target - pred) ** 2)

      if ss_tot <= tol or ss_res <= tol:
        r_sq = 1.0
      else:
        r_sq = max(0.0, min(1.0, 1.0 - (ss_res / ss_tot)))

      if (1.0 - r_sq) <= tol or rank < X_others.shape[1]:
        vif = float("inf")
        dep_msg = (
          f"Collinear dependency: column {target_idx} is linearly determined by columns "
          f"{other_indices} (R^2={r_sq:.6f}, rank={rank}/{X_others.shape[1]})"
        )
        dependencies.append(dep_msg)
      else:
        vif = float(1.0 / (1.0 - r_sq))
    except Exception as e:
      vif = float("inf")
      dependencies.append(f"Column {target_idx} regression failed: {e}")

    vifs[target_idx] = vif

  return vifs, dependencies


def analyze_coordinate_coverage(
    records: List[PairRecord],
) -> Dict[str, Dict[str, Any]]:
  """Analyzes physical coordinate observed ranges, distinct values, and distributions.

  Coordinates inspected: c, b, measured P, configured S, R, K, q.
  """
  c_vals = [r.features.c for r in records]
  b_vals = [r.features.b for r in records]
  p_vals = [r.features.P for r in records]
  s_vals = [r.parallel_sources for r in records]
  r_vals = [r.features.R for r in records]
  k_vals = [r.K for r in records]
  q_vals = [r.features.q for r in records]

  coords = {
    "c": c_vals,
    "b": b_vals,
    "P_measured": p_vals,
    "S_configured": s_vals,
    "R": r_vals,
    "K": k_vals,
    "q": q_vals,
  }

  coverage: Dict[str, Dict[str, Any]] = {}
  for name, vals in coords.items():
    arr = np.array(vals, dtype=np.float64)
    distinct = sorted(list(set(vals)))
    coverage[name] = {
      "min": float(np.min(arr)),
      "max": float(np.max(arr)),
      "mean": float(np.mean(arr)),
      "std": float(np.std(arr)),
      "distinct_count": len(distinct),
      "distinct_values": distinct,
    }
  return coverage


def analyze_class_conditional_coverage(
    dataset: Dataset,
) -> Dict[str, Dict[str, Any]]:
  """Computes class-conditional distributions (y=0 participate, y=1 withdraw, y=0.5 tie)."""
  class_summary: Dict[str, Dict[str, Any]] = {}
  classes = [0.0, 1.0, 0.5]
  class_names = {0.0: "K_WINS_participate", 1.0: "K_MINUS_1_WINS_withdraw",
                 0.5: "STABLE_TIE"}

  for cls_val in classes:
    cls_name = class_names[cls_val]
    mask = (dataset.y == cls_val)
    subset_records = [dataset.records[i] for i in range(len(dataset.records)) if
                      mask[i]]
    count = int(np.sum(mask))
    if count == 0:
      class_summary[cls_name] = {"count": 0}
      continue

    cov = analyze_coordinate_coverage(subset_records)
    cov["count"] = count
    class_summary[cls_name] = cov

  return class_summary


def detect_separation(dataset: Dataset) -> Tuple[bool, Dict[str, Any]]:
  """Checks whether any feature or linear coordinate achieves complete or quasi separation."""
  y0_mask = (dataset.y == 0.0)
  y1_mask = (dataset.y == 1.0)

  if np.sum(y0_mask) == 0 or np.sum(y1_mask) == 0:
    return True, {"complete_class_absence": True}

  details: Dict[str, Any] = {}
  separation_found = False

  # Check 1D coordinate separation
  feature_names = [
    "q", "c*q", "b*q", "R*q", "constant_-1", "-c", "-b", "-R"
  ]
  for j in range(dataset.X.shape[1]):
    x_y0 = dataset.X[y0_mask, j]
    x_y1 = dataset.X[y1_mask, j]

    max_y0, min_y0 = float(np.max(x_y0)), float(np.min(x_y0))
    max_y1, min_y1 = float(np.max(x_y1)), float(np.min(x_y1))

    if max_y0 < min_y1:
      separation_found = True
      details[feature_names[j]] = {
        "separated": True,
        "direction": "y0 < y1",
        "max_y0": max_y0,
        "min_y1": min_y1,
      }
    elif max_y1 < min_y0:
      separation_found = True
      details[feature_names[j]] = {
        "separated": True,
        "direction": "y1 < y0",
        "max_y1": max_y1,
        "min_y0": min_y0,
      }

  return separation_found, details


def perform_identifiability_audit(
    dataset: Dataset) -> IdentifiabilityAuditResult:
  """Executes the full Phase 0B unregularized identifiability audit.

  Does NOT use a regularized Hessian as evidence of empirical identifiability.
  """
  n, d = dataset.X.shape

  # 1. LAPACK-style numerical rank and singular spectrum of raw X
  rank_raw, s_raw, lapack_thresh = compute_lapack_rank_and_singular_spectrum(
    dataset.X)

  # 2. Singular spectrum of confidence-weighted design X_u = diag(sqrt(u)) @ X
  X_u = np.diag(np.sqrt(dataset.u)) @ dataset.X
  rank_weighted, s_weighted, lapack_thresh_weighted = compute_lapack_rank_and_singular_spectrum(
    X_u)

  # 3. Condition number handling
  is_deficient = (rank_raw < d)
  if is_deficient:
    condition_number = float("inf")
    nonzero_s = [val for val in s_raw if val > lapack_thresh]
    smallest_nonzero = float(min(nonzero_s)) if nonzero_s else 0.0
  else:
    condition_number = float(s_raw[0] / s_raw[-1]) if s_raw[-1] > 0 else float(
      "inf")
    smallest_nonzero = float(s_raw[-1])

  # 4. Nonconstant VIFs and collinearity
  vifs, dependencies = compute_nonconstant_vifs_and_collinearity(dataset.X)

  # 5. Physical coordinate coverage
  coverage = analyze_coordinate_coverage(dataset.records)

  # 6. Class-conditional coverage
  class_coverage = analyze_class_conditional_coverage(dataset)

  # 7. Separation detection
  sep_detected, sep_details = detect_separation(dataset)

  # 8. Identify unsupported feature groups
  unsupported_groups: List[str] = []
  # If R has only 2 distinct values or low variation in withdrawals, note it
  if coverage["R"]["distinct_count"] < 3:
    unsupported_groups.append(
        f"Registered workers R has only {coverage['R']['distinct_count']} distinct values "
        f"({coverage['R']['distinct_values']})"
    )
  # Check if withdrawals occur in only a subset of families
  y1_families = set(
      dataset.families[i] for i in range(len(dataset.records)) if
      dataset.y[i] == 1.0
  )
  if len(y1_families) < len(dataset.family_counts):
    unsupported_groups.append(
        f"Withdrawal decisions (y=1) occur in only {len(y1_families)} / "
        f"{len(dataset.family_counts)} physical families: {sorted(list(y1_families))}"
    )

  return IdentifiabilityAuditResult(
      numerical_rank=rank_raw,
      dimension=d,
      singular_values_raw=s_raw,
      singular_values_weighted=s_weighted,
      lapack_threshold=lapack_thresh,
      condition_number=condition_number,
      smallest_nonzero_singular_value=smallest_nonzero,
      is_rank_deficient=is_deficient,
      vifs=vifs,
      collinear_dependencies=dependencies,
      coordinate_coverage=coverage,
      class_conditional_coverage=class_coverage,
      separation_detected=sep_detected,
      separation_details=sep_details,
      unsupported_feature_groups=unsupported_groups,
      effective_sample_size=dataset.n_eff,
      total_influence_weight=dataset.U,
  )
