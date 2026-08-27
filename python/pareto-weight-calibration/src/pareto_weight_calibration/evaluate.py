"""Evaluation metrics, regret calculation, and baseline comparison models."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
import numpy as np

from pareto_weight_calibration.audit import get_physical_family_id
from pareto_weight_calibration.loss import unclipped_binary_cross_entropy
from pareto_weight_calibration.types import (
  LabelEvidenceBasis,
  Outcome,
  PairRecord,
)


@dataclass(frozen=True)
class ObservationLoss:
  """Detailed loss and regret components for a single validation observation."""
  pair_id: str
  family_id: str
  z: float
  y: float
  predicted_action: str  # "PARTICIPATE" or "CACHE"
  effective_outcome: Outcome
  label_evidence_basis: LabelEvidenceBasis
  t_k: float
  t_k_minus_1: float
  delta: float
  uncertainty: float
  supported_advantage: float  # a_i = max(0, |delta| - uncertainty)
  is_decisive: bool
  is_correct: bool
  supported_loss: float  # ops/sec
  observed_loss: float  # ops/sec
  supported_rel_loss: float  # fraction
  observed_rel_loss: float  # fraction
  bce_loss: float
  is_stable_tie: bool
  is_tie_neutral: bool  # True if stable tie and |z| <= neutral_threshold


@dataclass(frozen=True)
class EvaluationMetrics:
  """Aggregated evaluation and regret metrics across validation observations."""
  supported_rel_regret: float  # Primary selection metric: weighted mean supported rel regret
  worst_family_rel_regret: float  # Worst-family relative regret across families
  observed_abs_regret: float  # Weighted mean raw observed regret (lost ops/s)
  observed_rel_regret: float  # Weighted mean raw observed relative regret
  weighted_bce: float  # Weighted Binary Cross Entropy
  winner_accuracy: float  # Weighted classification accuracy on decisive rows
  raw_winner_accuracy: float  # Unweighted count accuracy on decisive rows
  decisive_count: int
  stable_tie_count: int
  total_count: int
  stable_tie_neutral_fraction: float  # Fraction of stable ties inside neutral band |z| <= delta_z
  total_weight: float
  family_rel_regrets: Dict[str, float] = field(default_factory=dict)
  family_bces: Dict[str, float] = field(default_factory=dict)
  family_counts: Dict[str, int] = field(default_factory=dict)
  observation_losses: List[ObservationLoss] = field(default_factory=list)


def extract_record_evidence_basis(
    record: PairRecord,
) -> Tuple[float, float, float, float, Outcome, LabelEvidenceBasis]:
  """Extracts throughputs, delta, uncertainty, outcome, and evidence basis for a record."""
  outcome = record.effective_outcome
  if outcome == Outcome.INCONCLUSIVE or outcome is None:
    if record.y == 0.0:
      outcome = Outcome.K_WINS
    elif record.y == 1.0:
      outcome = Outcome.K_MINUS_1_WINS
    elif record.y == 0.5:
      outcome = Outcome.STABLE_TIE
    else:
      outcome = record.whole_outcome

  basis = record.label_evidence_basis
  if basis == LabelEvidenceBasis.NONE or basis is None:
    if outcome == Outcome.STABLE_TIE:
      basis = LabelEvidenceBasis.STABLE_TIE
    else:
      basis = LabelEvidenceBasis.WHOLE_AGREEMENT

  if record.basis_throughput_k > 0.0 or record.basis_throughput_k_minus_1 > 0.0:
    t_k = record.basis_throughput_k
    t_k_minus_1 = record.basis_throughput_k_minus_1
    delta = record.basis_delta
    uncertainty = record.basis_uncertainty
  else:
    if basis == LabelEvidenceBasis.LATE_CONVERGENCE:
      t_k = record.perf_k.late_mean
      t_k_minus_1 = record.perf_k_minus_1.late_mean
    else:
      t_k = record.perf_k.mean
      t_k_minus_1 = record.perf_k_minus_1.mean
    delta = t_k_minus_1 - t_k
    uncertainty = record.uncertainty

  return t_k, t_k_minus_1, delta, uncertainty, outcome, basis


def compute_observation_loss(
    record: PairRecord,
    z: float,
    neutral_threshold: float = 0.5,
) -> ObservationLoss:
  """Computes all loss, regret, and decision components for one observation and logit z."""
  t_k, t_k_minus_1, delta, uncertainty, outcome, basis = extract_record_evidence_basis(
    record)
  family_id = get_physical_family_id(record)

  # Runtime decision: z <= 0 => PARTICIPATE (arm K), z > 0 => CACHE (arm K-1)
  predicted_action = "PARTICIPATE" if z <= 0.0 else "CACHE"

  is_decisive = outcome in (Outcome.K_WINS, Outcome.K_MINUS_1_WINS)
  is_stable_tie = (outcome == Outcome.STABLE_TIE)

  # Uncertainty-supported advantage: a_i = max(0, |delta_i| - uncertainty_i)
  if is_decisive:
    supported_advantage = max(0.0, abs(delta) - uncertainty)
  else:
    supported_advantage = 0.0

  # Decision correctness and regret
  is_correct = False
  supported_loss = 0.0
  observed_loss = 0.0

  if is_decisive:
    if outcome == Outcome.K_WINS:
      # y = 0.0, participate preferred
      if z <= 0.0:
        is_correct = True
      else:
        # Wrong prediction: model predicted CACHE
        is_correct = False
        supported_loss = supported_advantage
        observed_loss = abs(delta)
    elif outcome == Outcome.K_MINUS_1_WINS:
      # y = 1.0, CACHE preferred
      if z > 0.0:
        is_correct = True
      else:
        # Wrong prediction: model predicted PARTICIPATE
        is_correct = False
        supported_loss = supported_advantage
        observed_loss = abs(delta)
  elif is_stable_tie:
    is_correct = True
    supported_loss = 0.0
    observed_loss = 0.0
  else:
    is_correct = False
    supported_loss = 0.0
    observed_loss = 0.0

  # Relative regret: loss / max(T(K), T(K-1))
  t_max = max(t_k, t_k_minus_1)
  if t_max > 0.0:
    supported_rel_loss = supported_loss / t_max
    observed_rel_loss = observed_loss / t_max
  else:
    supported_rel_loss = 0.0
    observed_rel_loss = 0.0

  # Weighted Binary Cross Entropy
  bce = float(
      unclipped_binary_cross_entropy(np.array([record.y]), np.array([z]))[0])

  is_tie_neutral = is_stable_tie and (abs(z) <= neutral_threshold)

  return ObservationLoss(
      pair_id=record.pair_id,
      family_id=family_id,
      z=z,
      y=record.y,
      predicted_action=predicted_action,
      effective_outcome=outcome,
      label_evidence_basis=basis,
      t_k=t_k,
      t_k_minus_1=t_k_minus_1,
      delta=delta,
      uncertainty=uncertainty,
      supported_advantage=supported_advantage,
      is_decisive=is_decisive,
      is_correct=is_correct,
      supported_loss=supported_loss,
      observed_loss=observed_loss,
      supported_rel_loss=supported_rel_loss,
      observed_rel_loss=observed_rel_loss,
      bce_loss=bce,
      is_stable_tie=is_stable_tie,
      is_tie_neutral=is_tie_neutral,
  )


def evaluate_predictions(
    records: List[PairRecord],
    logits: np.ndarray,
    weights: np.ndarray,
    families: Optional[List[str]] = None,
    neutral_threshold: float = 0.5,
) -> EvaluationMetrics:
  """Computes pooled and per-family evaluation and regret metrics."""
  n = len(records)
  if len(logits) != n or len(weights) != n:
    raise ValueError(
      f"Length mismatch: records={n}, logits={len(logits)}, weights={len(weights)}")

  if families is None:
    families = [get_physical_family_id(r) for r in records]

  obs_losses: List[ObservationLoss] = []
  for i in range(n):
    obs = compute_observation_loss(records[i], float(logits[i]),
                                   neutral_threshold=neutral_threshold)
    obs_losses.append(obs)

  total_weight = float(np.sum(weights))
  if total_weight <= 0.0:
    raise ValueError(
      f"Total evaluation weight must be positive, got {total_weight}")

  # Weighted pooled metrics
  supp_rel_losses = np.array([obs.supported_rel_loss for obs in obs_losses],
                             dtype=np.float64)
  obs_losses_raw = np.array([obs.observed_loss for obs in obs_losses],
                            dtype=np.float64)
  obs_rel_losses_raw = np.array([obs.observed_rel_loss for obs in obs_losses],
                                dtype=np.float64)
  bces = np.array([obs.bce_loss for obs in obs_losses], dtype=np.float64)

  pooled_supp_rel_regret = float(
    np.sum(weights * supp_rel_losses) / total_weight)
  pooled_obs_abs_regret = float(np.sum(weights * obs_losses_raw) / total_weight)
  pooled_obs_rel_regret = float(
    np.sum(weights * obs_rel_losses_raw) / total_weight)
  pooled_weighted_bce = float(np.sum(weights * bces) / total_weight)

  # Winner accuracy on decisive rows
  decisive_indices = [i for i, obs in enumerate(obs_losses) if obs.is_decisive]
  decisive_count = len(decisive_indices)
  if decisive_count > 0:
    dec_weights = weights[decisive_indices]
    dec_correct = np.array(
        [1.0 if obs_losses[i].is_correct else 0.0 for i in decisive_indices],
        dtype=np.float64)
    dec_weight_total = float(np.sum(dec_weights))
    winner_acc = float(np.sum(
      dec_weights * dec_correct) / dec_weight_total) if dec_weight_total > 0 else 0.0
    raw_winner_acc = float(np.mean(dec_correct))
  else:
    winner_acc = 1.0
    raw_winner_acc = 1.0

  # Stable tie neutral fraction
  tie_indices = [i for i, obs in enumerate(obs_losses) if obs.is_stable_tie]
  stable_tie_count = len(tie_indices)
  if stable_tie_count > 0:
    tie_neutral_count = sum(
        1 for i in tie_indices if obs_losses[i].is_tie_neutral)
    tie_neutral_fraction = float(tie_neutral_count / stable_tie_count)
  else:
    tie_neutral_fraction = 1.0

  # Per-family metrics
  distinct_families = sorted(list(set(families)))
  family_rel_regrets: Dict[str, float] = {}
  family_bces: Dict[str, float] = {}
  family_counts: Dict[str, int] = {}

  for fam in distinct_families:
    fam_idx = [i for i, f in enumerate(families) if f == fam]
    fam_weights = weights[fam_idx]
    fam_w_sum = float(np.sum(fam_weights))
    fam_counts = len(fam_idx)
    family_counts[fam] = fam_counts

    if fam_w_sum > 0:
      fam_regret = float(
        np.sum(fam_weights * supp_rel_losses[fam_idx]) / fam_w_sum)
      fam_bce = float(np.sum(fam_weights * bces[fam_idx]) / fam_w_sum)
    else:
      fam_regret = 0.0
      fam_bce = 0.0

    family_rel_regrets[fam] = fam_regret
    family_bces[fam] = fam_bce

  worst_family_rel_regret = float(
    max(family_rel_regrets.values())) if family_rel_regrets else 0.0

  return EvaluationMetrics(
      supported_rel_regret=pooled_supp_rel_regret,
      worst_family_rel_regret=worst_family_rel_regret,
      observed_abs_regret=pooled_obs_abs_regret,
      observed_rel_regret=pooled_obs_rel_regret,
      weighted_bce=pooled_weighted_bce,
      winner_accuracy=winner_acc,
      raw_winner_accuracy=raw_winner_acc,
      decisive_count=decisive_count,
      stable_tie_count=stable_tie_count,
      total_count=n,
      stable_tie_neutral_fraction=tie_neutral_fraction,
      total_weight=total_weight,
      family_rel_regrets=family_rel_regrets,
      family_bces=family_bces,
      family_counts=family_counts,
      observation_losses=obs_losses,
  )


# -----------------------------------------------------------------------------
# Baseline Policies
# -----------------------------------------------------------------------------

def evaluate_always_participate(
    records: List[PairRecord],
    weights: np.ndarray,
    families: Optional[List[str]] = None,
    neutral_threshold: float = 0.5,
) -> EvaluationMetrics:
  """Baseline policy: Always participate (z <= 0 for all rows, e.g. z = -1.0)."""
  logits = np.full(len(records), -1.0, dtype=np.float64)
  return evaluate_predictions(records, logits, weights, families=families,
                              neutral_threshold=neutral_threshold)


def evaluate_always_cache(
    records: List[PairRecord],
    weights: np.ndarray,
    families: Optional[List[str]] = None,
    neutral_threshold: float = 0.5,
) -> EvaluationMetrics:
  """Baseline policy: Always CACHE / withdraw (z > 0 for all rows, e.g. z = +1.0)."""
  logits = np.full(len(records), 1.0, dtype=np.float64)
  return evaluate_predictions(records, logits, weights, families=families,
                              neutral_threshold=neutral_threshold)


def compute_fixed_cutoff_logits(records: List[PairRecord],
    k0: int) -> np.ndarray:
  """Computes synthetic logits for fixed cutoff policy K0: participate if K <= min(K0, R)."""
  logits = np.zeros(len(records), dtype=np.float64)
  for i, r in enumerate(records):
    r_workers = int(r.features.R)
    cutoff = min(k0, r_workers)
    # If K <= cutoff => participate (z = -1.0), else withdraw (z = +1.0)
    logits[i] = -1.0 if r.K <= cutoff else 1.0
  return logits


def evaluate_fixed_cutoff(
    records: List[PairRecord],
    k0: int,
    weights: np.ndarray,
    families: Optional[List[str]] = None,
    neutral_threshold: float = 0.5,
) -> EvaluationMetrics:
  """Evaluates fixed cutoff policy K0 (participates when K <= min(K0, R))."""
  logits = compute_fixed_cutoff_logits(records, k0)
  return evaluate_predictions(records, logits, weights, families=families,
                              neutral_threshold=neutral_threshold)


def select_best_fixed_cutoff_on_train(
    train_records: List[PairRecord],
    train_weights: np.ndarray,
    candidate_k0s: Optional[List[int]] = None,
) -> int:
  """Selects the best fixed cutoff K0 inside training fold using training relative regret.

  Deterministic tie-breaking:
      1. lower training supported relative regret
      2. lower K0
  """
  if candidate_k0s is None:
    # Default candidate cutoffs 2..32
    candidate_k0s = list(range(2, 33))

  best_k0 = candidate_k0s[0]
  best_regret = float("inf")

  for k0 in candidate_k0s:
    metrics = evaluate_fixed_cutoff(train_records, k0, train_weights)
    regret = metrics.supported_rel_regret
    if (best_regret - regret) > 1e-12:
      best_regret = regret
      best_k0 = k0
    elif abs(best_regret - regret) <= 1e-12:
      # Tie: prefer smaller K0
      if k0 < best_k0:
        best_regret = regret
        best_k0 = k0

  return best_k0
