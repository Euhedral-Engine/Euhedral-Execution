"""9-family Leave-One-Family-Out (LOFO) cross-validation and grid search."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
import numpy as np

from pareto_weight_calibration.audit import get_physical_family_id
from pareto_weight_calibration.constraints import (
  MODEL_STRUCTURES,
  DomainConfig,
  check_corner_constraints,
)
from pareto_weight_calibration.evaluate import (
  EvaluationMetrics,
  compute_fixed_cutoff_logits,
  evaluate_always_cache,
  evaluate_always_participate,
  evaluate_predictions,
  select_best_fixed_cutoff_on_train,
)
from pareto_weight_calibration.nested import (
  STANDARD_LAMBDA_GRID,
  STRUCTURE_SPECIFICATIONS,
  ParsimonySelectionResult,
  execute_procedural_parsimony,
  select_best_lambda_for_structure,
)
from pareto_weight_calibration.optimizer import (
  OptimizationResult,
  fit_constrained_model,
)
from pareto_weight_calibration.scaling import compute_training_scales
from pareto_weight_calibration.types import Dataset, PairRecord


@dataclass(frozen=True)
class FoldResult:
  """Evaluation result for a single held-out validation family fold."""
  fold_index: int
  held_out_family: str
  train_size: int
  val_size: int
  opt_result: OptimizationResult
  val_metrics: EvaluationMetrics
  val_logits: np.ndarray
  val_weights: np.ndarray
  baseline_k0: int
  baseline_k0_val_logits: np.ndarray


@dataclass(frozen=True)
class LOFOResult:
  """Aggregated 9-family cross-validation result for a structure and lambda."""
  structure_name: str
  l2_reg: float
  active_indices: List[int]
  pooled_metrics: EvaluationMetrics
  fold_results: List[FoldResult]
  is_valid: bool
  rejection_reason: Optional[str] = None


@dataclass(frozen=True)
class LOFOGridResult:
  """Full grid search, baseline evaluation, and parsimony selection results."""
  results: Dict[Tuple[str, float], LOFOResult]
  selected_by_structure: Dict[str, Tuple[float, EvaluationMetrics]]
  baseline_always_participate: EvaluationMetrics
  baseline_always_cache: EvaluationMetrics
  baseline_training_selected_fixed_cutoff: EvaluationMetrics
  parsimony_result: ParsimonySelectionResult
  all_structures: List[str]
  all_lambdas: List[float]


def compute_fold_influence_weights(
    records: List[PairRecord],
) -> Tuple[np.ndarray, Dict[str, float]]:
  """Computes bounded family influence weights on a subset of records without leakage.

  Formula:
      familyScale(F) = 1.0 / max(1.0, sum_{j in F} v_j)
      u_i = v_i * familyScale(F(i))

  Args:
      records: Subset of PairRecords (e.g. training set or held-out validation family).

  Returns:
      (u_weights, family_scales)
  """
  n = len(records)
  v = np.array([r.pair_weight for r in records], dtype=np.float64)
  families = [get_physical_family_id(r) for r in records]

  # Compute family sums of raw Step 4 weights v
  family_v_sum: Dict[str, float] = {}
  for i, r in enumerate(records):
    fam = families[i]
    family_v_sum[fam] = family_v_sum.get(fam, 0.0) + float(v[i])

  family_scales: Dict[str, float] = {}
  for fam, v_tot in family_v_sum.items():
    family_scales[fam] = 1.0 / max(1.0, v_tot)

  u = np.zeros(n, dtype=np.float64)
  for i in range(n):
    fam = families[i]
    u[i] = v[i] * family_scales[fam]

  return u, family_scales


def run_single_lofo_candidate(
    dataset: Dataset,
    domain: DomainConfig,
    structure_name: str,
    l2_reg: float,
    distinct_families: Optional[List[str]] = None,
    neutral_threshold: float = 0.5,
) -> LOFOResult:
  """Executes 9-family LOFO for a single candidate structure and lambda.

  Guarantees:
      - No physical family leakage
      - Fold-local training RMS feature scales
      - Fold-local training family influence
      - Held-out family weights computed independently from that family
      - Fold-local training baseline selection
  """
  if distinct_families is None:
    # Canonical order of distinct families in dataset
    distinct_families = sorted(list(set(dataset.families)))

  active_indices = MODEL_STRUCTURES[structure_name]
  fold_results: List[FoldResult] = []

  all_val_records: List[PairRecord] = []
  all_val_logits: List[float] = []
  all_val_weights: List[float] = []
  all_val_families: List[str] = []

  all_k0_val_logits: List[float] = []

  is_valid = True
  rejection_reason: Optional[str] = None

  for fold_idx, val_fam in enumerate(distinct_families):
    # 1. Partition dataset records into train (family != val_fam) and val (family == val_fam)
    train_indices = [i for i, f in enumerate(dataset.families) if f != val_fam]
    val_indices = [i for i, f in enumerate(dataset.families) if f == val_fam]

    train_records = [dataset.records[i] for i in train_indices]
    val_records = [dataset.records[i] for i in val_indices]

    # 2. Training fold bounded influence weights
    u_train, _ = compute_fold_influence_weights(train_records)
    X_train = np.vstack([r.features.feature_vector for r in train_records])
    y_train = np.array([r.y for r in train_records], dtype=np.float64)

    # 3. Training fold RMS scales (strictly on training fold)
    try:
      scales_train = compute_training_scales(
          X=X_train,
          u=u_train,
          active_indices=active_indices,
          constant_column_index=4,
      )
    except Exception as e:
      is_valid = False
      rejection_reason = f"Scale computation failed on fold {val_fam}: {e}"
      break

    # 4. Solve deterministic constrained optimization on training fold
    try:
      opt_res = fit_constrained_model(
          X_train=X_train,
          y_train=y_train,
          u_train=u_train,
          scales=scales_train,
          domain=domain,
          structure_name=structure_name,
          l2_reg=l2_reg,
          active_indices=active_indices,
      )
    except Exception as e:
      is_valid = False
      rejection_reason = f"Optimization error on fold {val_fam}: {e}"
      break

    if not opt_res.success:
      # Solver failed to converge
      is_valid = False
      rejection_reason = f"Solver failed on fold {val_fam}: {opt_res.termination_reason}"

    if opt_res.constraint_violation > 1e-9:
      is_valid = False
      rejection_reason = (
        f"Constraint violation {opt_res.constraint_violation:.6e} on fold {val_fam}"
      )

    # 5. Held-out validation family weights (computed independently from that family alone)
    u_val, _ = compute_fold_influence_weights(val_records)
    X_val = np.vstack([r.features.feature_vector for r in val_records])

    # Validation logits in physical coordinates: z_val = X_val @ w_phys_full
    val_logits = np.dot(X_val, opt_res.w_phys_full)

    val_metrics = evaluate_predictions(
        records=val_records,
        logits=val_logits,
        weights=u_val,
        families=[val_fam] * len(val_records),
        neutral_threshold=neutral_threshold,
    )

    # 6. Select best fixed cutoff K0 on training fold
    best_k0 = select_best_fixed_cutoff_on_train(train_records, u_train)
    k0_val_logits = compute_fixed_cutoff_logits(val_records, best_k0)

    fold_res = FoldResult(
        fold_index=fold_idx,
        held_out_family=val_fam,
        train_size=len(train_records),
        val_size=len(val_records),
        opt_result=opt_res,
        val_metrics=val_metrics,
        val_logits=val_logits,
        val_weights=u_val,
        baseline_k0=best_k0,
        baseline_k0_val_logits=k0_val_logits,
    )
    fold_results.append(fold_res)

    # Collect for pooled evaluation
    all_val_records.extend(val_records)
    all_val_logits.extend(val_logits.tolist())
    all_val_weights.extend(u_val.tolist())
    all_val_families.extend([val_fam] * len(val_records))
    all_k0_val_logits.extend(k0_val_logits.tolist())

  # 7. Aggregate pooled validation metrics across all 9 folds
  pooled_logits = np.array(all_val_logits, dtype=np.float64)
  pooled_weights = np.array(all_val_weights, dtype=np.float64)

  pooled_metrics = evaluate_predictions(
      records=all_val_records,
      logits=pooled_logits,
      weights=pooled_weights,
      families=all_val_families,
      neutral_threshold=neutral_threshold,
  )

  return LOFOResult(
      structure_name=structure_name,
      l2_reg=l2_reg,
      active_indices=active_indices,
      pooled_metrics=pooled_metrics,
      fold_results=fold_results,
      is_valid=is_valid,
      rejection_reason=rejection_reason,
  )


def execute_lofo_grid_search(
    dataset: Dataset,
    domain: DomainConfig,
    structures: Optional[List[str]] = None,
    lambdas: Optional[List[float]] = None,
    neutral_threshold: float = 0.5,
    tol: float = 1e-12,
) -> LOFOGridResult:
  """Executes full 9-family LOFO grid search across structures and lambdas."""
  if structures is None:
    structures = list(STRUCTURE_SPECIFICATIONS.keys())
  if lambdas is None:
    lambdas = STANDARD_LAMBDA_GRID

  distinct_families = sorted(list(set(dataset.families)))

  results: Dict[Tuple[str, float], LOFOResult] = {}
  eval_by_structure_and_lambda: Dict[str, Dict[float, EvaluationMetrics]] = {
    s: {} for s in structures
  }

  # Execute LOFO for every structure and lambda
  for struct in structures:
    for l2 in lambdas:
      res = run_single_lofo_candidate(
          dataset=dataset,
          domain=domain,
          structure_name=struct,
          l2_reg=l2,
          distinct_families=distinct_families,
          neutral_threshold=neutral_threshold,
      )
      results[(struct, l2)] = res
      if res.is_valid:
        eval_by_structure_and_lambda[struct][l2] = res.pooled_metrics

  # Deterministic lambda selection for each structure
  selected_by_structure: Dict[str, Tuple[float, EvaluationMetrics]] = {}
  for struct in structures:
    if eval_by_structure_and_lambda[struct]:
      best_l2, best_met = select_best_lambda_for_structure(
          eval_by_lambda=eval_by_structure_and_lambda[struct],
          structure_name=struct,
          tol=tol,
      )
      selected_by_structure[struct] = (best_l2, best_met)

  # Compute baseline metrics across LOFO validation pool
  # Build validation-weighted pooled records
  all_val_records: List[PairRecord] = []
  all_val_weights: List[float] = []
  all_val_families: List[str] = []
  all_k0_logits: List[float] = []

  # Use first valid structure result's folds to extract identical held-out validation weighting
  ref_lofo = results[(structures[0], lambdas[0])]
  for fold in ref_lofo.fold_results:
    val_fam = fold.held_out_family
    val_indices = [i for i, f in enumerate(dataset.families) if f == val_fam]
    val_recs = [dataset.records[i] for i in val_indices]
    u_val = fold.val_weights

    all_val_records.extend(val_recs)
    all_val_weights.extend(u_val.tolist())
    all_val_families.extend([val_fam] * len(val_recs))
    all_k0_logits.extend(fold.baseline_k0_val_logits.tolist())

  pooled_val_weights = np.array(all_val_weights, dtype=np.float64)
  k0_logits_arr = np.array(all_k0_logits, dtype=np.float64)

  baseline_participate = evaluate_always_participate(
      records=all_val_records,
      weights=pooled_val_weights,
      families=all_val_families,
      neutral_threshold=neutral_threshold,
  )
  baseline_cache = evaluate_always_cache(
      records=all_val_records,
      weights=pooled_val_weights,
      families=all_val_families,
      neutral_threshold=neutral_threshold,
  )
  baseline_k0 = evaluate_predictions(
      records=all_val_records,
      logits=k0_logits_arr,
      weights=pooled_val_weights,
      families=all_val_families,
      neutral_threshold=neutral_threshold,
  )

  # Execute procedural structure parsimony rule
  parsimony_res = execute_procedural_parsimony(
      selected_by_structure=selected_by_structure,
      tol=tol,
  )

  return LOFOGridResult(
      results=results,
      selected_by_structure=selected_by_structure,
      baseline_always_participate=baseline_participate,
      baseline_always_cache=baseline_cache,
      baseline_training_selected_fixed_cutoff=baseline_k0,
      parsimony_result=parsimony_res,
      all_structures=structures,
      all_lambdas=lambdas,
  )
