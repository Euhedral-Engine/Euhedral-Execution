"""Grouped candidate lattice, embedding, lambda selection, and procedural parsimony."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Tuple
import numpy as np

from pareto_weight_calibration.constraints import MODEL_STRUCTURES
from pareto_weight_calibration.evaluate import EvaluationMetrics, \
  ObservationLoss
from pareto_weight_calibration.types import Outcome


@dataclass(frozen=True)
class ModelStructure:
  """Specification of a candidate grouped model structure."""
  name: str
  param_count: int
  active_indices: List[int]
  added_groups: List[str]


# 8 candidate structures in the grouped candidate lattice
STRUCTURE_SPECIFICATIONS: Dict[str, ModelStructure] = {
  "M2": ModelStructure(
      name="M2",
      param_count=2,
      active_indices=[0, 4],
      added_groups=["intercepts"],
  ),
  "M4-C": ModelStructure(
      name="M4-C",
      param_count=4,
      active_indices=[0, 1, 4, 5],
      added_groups=["intercepts", "contention"],
  ),
  "M4-B": ModelStructure(
      name="M4-B",
      param_count=4,
      active_indices=[0, 2, 4, 6],
      added_groups=["intercepts", "body"],
  ),
  "M4-R": ModelStructure(
      name="M4-R",
      param_count=4,
      active_indices=[0, 3, 4, 7],
      added_groups=["intercepts", "registered_workers"],
  ),
  "M6-CB": ModelStructure(
      name="M6-CB",
      param_count=6,
      active_indices=[0, 1, 2, 4, 5, 6],
      added_groups=["intercepts", "contention", "body"],
  ),
  "M6-CR": ModelStructure(
      name="M6-CR",
      param_count=6,
      active_indices=[0, 1, 3, 4, 5, 7],
      added_groups=["intercepts", "contention", "registered_workers"],
  ),
  "M6-BR": ModelStructure(
      name="M6-BR",
      param_count=6,
      active_indices=[0, 2, 3, 4, 6, 7],
      added_groups=["intercepts", "body", "registered_workers"],
  ),
  "M8": ModelStructure(
      name="M8",
      param_count=8,
      active_indices=[0, 1, 2, 3, 4, 5, 6, 7],
      added_groups=["intercepts", "contention", "body", "registered_workers"],
  ),
}

STRUCTURE_SIZES: Dict[int, List[str]] = {
  2: ["M2"],
  4: ["M4-C", "M4-B", "M4-R"],
  6: ["M6-CB", "M6-CR", "M6-BR"],
  8: ["M8"],
}

STANDARD_LAMBDA_GRID: List[float] = [1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1.0]


def embed_active_weights(w_active: np.ndarray,
    active_indices: List[int]) -> np.ndarray:
  """Embeds active coefficient array into full 8-element physical vector.

  Inactive coefficients are embedded as exact zeroes.
  """
  if len(w_active) != len(active_indices):
    raise ValueError(
        f"Length mismatch: w_active has {len(w_active)} elements, active_indices has {len(active_indices)}"
    )
  w_full = np.zeros(8, dtype=np.float64)
  for i, idx in enumerate(active_indices):
    w_full[idx] = float(w_active[i])
  return w_full


def extract_active_weights(w_full: np.ndarray,
    active_indices: List[int]) -> np.ndarray:
  """Extracts active coefficients from an 8-element vector."""
  if len(w_full) != 8:
    raise ValueError(f"Expected 8-element vector, got {len(w_full)}")
  return w_full[active_indices].copy()


# -----------------------------------------------------------------------------
# Deterministic Lambda Selection
# -----------------------------------------------------------------------------

def compare_candidate_metrics(
    metrics_a: EvaluationMetrics,
    l2_a: float,
    name_a: str,
    metrics_b: EvaluationMetrics,
    l2_b: float,
    name_b: str,
    tol: float = 1e-12,
) -> int:
  """Deterministic comparator for two candidate evaluations.

  Lexicographic comparison tuple:
      1. lower pooled relative regret (tol = 1e-12)
      2. lower worst-family relative regret (tol = 1e-12)
      3. lower weighted BCE (tol = 1e-12)
      4. larger lambda (prefer stronger regularization)
      5. lexicographic name (name_a < name_b)

  Returns:
      -1 if A is strictly preferred over B
      +1 if B is strictly preferred over A
       0 if completely equivalent
  """
  # 1. Lower pooled relative regret
  diff_regret = metrics_a.supported_rel_regret - metrics_b.supported_rel_regret
  if abs(diff_regret) > tol:
    return -1 if diff_regret < 0 else 1

  # 2. Lower worst-family relative regret
  diff_worst = metrics_a.worst_family_rel_regret - metrics_b.worst_family_rel_regret
  if abs(diff_worst) > tol:
    return -1 if diff_worst < 0 else 1

  # 3. Lower weighted BCE
  diff_bce = metrics_a.weighted_bce - metrics_b.weighted_bce
  if abs(diff_bce) > tol:
    return -1 if diff_bce < 0 else 1

  # 4. Larger lambda
  diff_l2 = l2_a - l2_b
  if abs(diff_l2) > tol:
    return -1 if diff_l2 > 0 else 1

  # 5. Final tie break: lexicographic name
  if name_a < name_b:
    return -1
  elif name_a > name_b:
    return 1

  return 0


def select_best_lambda_for_structure(
    eval_by_lambda: Dict[float, EvaluationMetrics],
    structure_name: str,
    tol: float = 1e-12,
) -> Tuple[float, EvaluationMetrics]:
  """Selects the best regularization parameter lambda for a single structure.

  Args:
      eval_by_lambda: Dict mapping lambda value to its aggregated LOFO EvaluationMetrics.
      structure_name: Canonical name of the model structure.
      tol: Equivalence tolerance (1e-12).

  Returns:
      (best_lambda, best_metrics)
  """
  if not eval_by_lambda:
    raise ValueError("Cannot select lambda from empty results")

  sorted_lambdas = sorted(list(eval_by_lambda.keys()))
  best_l2 = sorted_lambdas[0]
  best_metrics = eval_by_lambda[best_l2]

  for l2 in sorted_lambdas[1:]:
    cand_metrics = eval_by_lambda[l2]
    cmp = compare_candidate_metrics(
        metrics_a=cand_metrics,
        l2_a=l2,
        name_a=structure_name,
        metrics_b=best_metrics,
        l2_b=best_l2,
        name_b=structure_name,
        tol=tol,
    )
    if cmp < 0:
      best_l2 = l2
      best_metrics = cand_metrics

  return best_l2, best_metrics


# -----------------------------------------------------------------------------
# Frozen Structure Parsimony Rule
# -----------------------------------------------------------------------------

@dataclass(frozen=True)
class ParsimonyComparison:
  """Detailed audit record of comparing a candidate structure against the incumbent."""
  candidate_name: str
  incumbent_name: str
  candidate_l2: float
  incumbent_l2: float
  candidate_pooled_regret: float
  incumbent_pooled_regret: float
  pooled_regret_diff: float
  condition_1_passed: bool
  candidate_worst_fam_regret: float
  incumbent_worst_fam_regret: float
  worst_fam_diff: float
  condition_2_passed: bool
  improving_family_count: int
  condition_3_passed: bool
  differing_decisions_count: int
  corrected_decisive_count: int
  condition_4_passed: bool
  is_admissible: bool
  rejection_reasons: List[str] = field(default_factory=list)


@dataclass(frozen=True)
class ParsimonySelectionResult:
  """Complete trace and result of the procedural structure parsimony rule."""
  selected_structure: str
  selected_l2_reg: float
  selected_metrics: EvaluationMetrics
  history: List[ParsimonyComparison] = field(default_factory=list)
  incumbent_progression: List[str] = field(default_factory=list)


def evaluate_parsimony_conditions(
    candidate_name: str,
    candidate_metrics: EvaluationMetrics,
    candidate_l2: float,
    incumbent_name: str,
    incumbent_metrics: EvaluationMetrics,
    incumbent_l2: float,
    tol: float = 1e-12,
) -> ParsimonyComparison:
  """Evaluates all 4 parsimony conditions for candidate replacing incumbent.

  Conditions:
      1. pooled relative regret is lower by > 1e-12
      2. worst-family relative regret does not worsen by > 1e-12
      3. at least two families have strictly lower relative regret by > 1e-12
      4. at least one corrected differing decision has decisive evidence (a_i > 0)
  """
  rejection_reasons: List[str] = []

  # Condition 1: pooled relative regret is lower by > tol
  pooled_diff = incumbent_metrics.supported_rel_regret - candidate_metrics.supported_rel_regret
  c1_passed = bool(pooled_diff > tol)
  if not c1_passed:
    rejection_reasons.append(
        f"Condition 1 failed: pooled regret diff {pooled_diff:.14e} <= {tol:.14e} "
        f"(cand={candidate_metrics.supported_rel_regret:.6e}, inc={incumbent_metrics.supported_rel_regret:.6e})"
    )

  # Condition 2: worst-family relative regret does not worsen by > tol
  worst_diff = candidate_metrics.worst_family_rel_regret - incumbent_metrics.worst_family_rel_regret
  c2_passed = bool(worst_diff <= tol)
  if not c2_passed:
    rejection_reasons.append(
        f"Condition 2 failed: worst-family regret worsened by {worst_diff:.14e} > {tol:.14e} "
        f"(cand={candidate_metrics.worst_family_rel_regret:.6e}, inc={incumbent_metrics.worst_family_rel_regret:.6e})"
    )

  # Condition 3: at least two families have strictly lower relative regret by > tol
  all_families = sorted(
      list(set(candidate_metrics.family_rel_regrets.keys()) | set(
        incumbent_metrics.family_rel_regrets.keys()))
  )
  improving_fam_count = 0
  for fam in all_families:
    c_fam_regret = candidate_metrics.family_rel_regrets.get(fam, 0.0)
    i_fam_regret = incumbent_metrics.family_rel_regrets.get(fam, 0.0)
    if (i_fam_regret - c_fam_regret) > tol:
      improving_fam_count += 1

  c3_passed = bool(improving_fam_count >= 2)
  if not c3_passed:
    rejection_reasons.append(
        f"Condition 3 failed: only {improving_fam_count} families improved by > {tol:.14e} (required >= 2)"
    )

  # Condition 4: at least one corrected differing decision has decisive evidence (a_i > 0)
  cand_obs = candidate_metrics.observation_losses
  inc_obs = incumbent_metrics.observation_losses

  differing_count = 0
  corrected_decisive_count = 0

  if len(cand_obs) == len(inc_obs):
    for c_o, i_o in zip(cand_obs, inc_obs):
      # Check differing decision
      if c_o.predicted_action != i_o.predicted_action:
        differing_count += 1
        # Check if decisive, candidate is correct, incumbent is wrong, and a_i > tol
        if c_o.is_decisive and c_o.is_correct and not i_o.is_correct:
          if c_o.supported_advantage > tol:
            corrected_decisive_count += 1

  c4_passed = bool(corrected_decisive_count >= 1)
  if not c4_passed:
    rejection_reasons.append(
        f"Condition 4 failed: found {corrected_decisive_count} corrected decisive observations "
        f"with a_i > {tol:.14e} across {differing_count} differing decisions (required >= 1)"
    )

  is_admissible = c1_passed and c2_passed and c3_passed and c4_passed

  return ParsimonyComparison(
      candidate_name=candidate_name,
      incumbent_name=incumbent_name,
      candidate_l2=candidate_l2,
      incumbent_l2=incumbent_l2,
      candidate_pooled_regret=candidate_metrics.supported_rel_regret,
      incumbent_pooled_regret=incumbent_metrics.supported_rel_regret,
      pooled_regret_diff=pooled_diff,
      condition_1_passed=c1_passed,
      candidate_worst_fam_regret=candidate_metrics.worst_family_rel_regret,
      incumbent_worst_fam_regret=incumbent_metrics.worst_family_rel_regret,
      worst_fam_diff=worst_diff,
      condition_2_passed=c2_passed,
      improving_family_count=improving_fam_count,
      condition_3_passed=c3_passed,
      differing_decisions_count=differing_count,
      corrected_decisive_count=corrected_decisive_count,
      condition_4_passed=c4_passed,
      is_admissible=is_admissible,
      rejection_reasons=rejection_reasons,
  )


def execute_procedural_parsimony(
    selected_by_structure: Dict[str, Tuple[float, EvaluationMetrics]],
    tol: float = 1e-12,
) -> ParsimonySelectionResult:
  """Executes the frozen structure-parsimony rule across parameter sizes 2 -> 4 -> 6 -> 8.

  Starting with incumbent M2:
  At each parameter size (4, 6, 8):
      - Compare each candidate of that size against the current simpler incumbent
      - An admissible candidate must satisfy all 4 parsimony conditions
      - If multiple candidates are admissible, select the best by standard metric tuple
      - If admissible candidate selected, update incumbent
      - Continue evaluating larger sizes even if intermediate size failed
  """
  if "M2" not in selected_by_structure:
    raise ValueError(
      "Base model structure M2 must be present in evaluated structures")

  incumbent_name = "M2"
  incumbent_l2, incumbent_metrics = selected_by_structure["M2"]
  history: List[ParsimonyComparison] = []
  incumbent_progression = ["M2"]

  # Parameter size tiers: 4, 6, 8
  for size in [4, 6, 8]:
    candidate_names = STRUCTURE_SIZES.get(size, [])
    admissible_candidates: List[Tuple[str, float, EvaluationMetrics]] = []

    for cand_name in candidate_names:
      if cand_name not in selected_by_structure:
        continue

      cand_l2, cand_metrics = selected_by_structure[cand_name]
      comp = evaluate_parsimony_conditions(
          candidate_name=cand_name,
          candidate_metrics=cand_metrics,
          candidate_l2=cand_l2,
          incumbent_name=incumbent_name,
          incumbent_metrics=incumbent_metrics,
          incumbent_l2=incumbent_l2,
          tol=tol,
      )
      history.append(comp)

      if comp.is_admissible:
        admissible_candidates.append((cand_name, cand_l2, cand_metrics))

    # If one or more candidates are admissible at this size, pick the best
    if admissible_candidates:
      # Sort admissible candidates by standard metric tuple
      best_cand_name, best_cand_l2, best_cand_metrics = admissible_candidates[0]
      for cand_name, cand_l2, cand_metrics in admissible_candidates[1:]:
        cmp = compare_candidate_metrics(
            metrics_a=cand_metrics,
            l2_a=cand_l2,
            name_a=cand_name,
            metrics_b=best_cand_metrics,
            l2_b=best_cand_l2,
            name_b=best_cand_name,
            tol=tol,
        )
        if cmp < 0:
          best_cand_name = cand_name
          best_cand_l2 = cand_l2
          best_cand_metrics = cand_metrics

      # Update incumbent
      incumbent_name = best_cand_name
      incumbent_l2 = best_cand_l2
      incumbent_metrics = best_cand_metrics
      incumbent_progression.append(incumbent_name)

  return ParsimonySelectionResult(
      selected_structure=incumbent_name,
      selected_l2_reg=incumbent_l2,
      selected_metrics=incumbent_metrics,
      history=history,
      incumbent_progression=incumbent_progression,
  )
