"""Tests for grouped candidate lattice, embedding, lambda selection, and parsimony."""

from __future__ import annotations

import numpy as np
import pytest

from pareto_weight_calibration.evaluate import EvaluationMetrics, \
  ObservationLoss
from pareto_weight_calibration.nested import (
  STANDARD_LAMBDA_GRID,
  STRUCTURE_SIZES,
  STRUCTURE_SPECIFICATIONS,
  compare_candidate_metrics,
  embed_active_weights,
  evaluate_parsimony_conditions,
  execute_procedural_parsimony,
  extract_active_weights,
  select_best_lambda_for_structure,
)
from pareto_weight_calibration.types import LabelEvidenceBasis, Outcome


def test_candidate_group_mappings_paired_axes():
  """Validates that each physical axis enters A and B as a pair in all 8 candidate structures."""
  # Expected active coefficients for each model:
  # A(c,b,R) = w0 + w1*c + w2*b + w3*R
  # B(c,b,R) = w4 + w5*c + w6*b + w7*R
  # Intercepts: (w0, w4)
  # Contention: (w1, w5)
  # Body: (w2, w6)
  # Registered workers: (w3, w7)

  assert set(STRUCTURE_SPECIFICATIONS.keys()) == {
    "M2", "M4-C", "M4-B", "M4-R", "M6-CB", "M6-CR", "M6-BR", "M8"
  }

  # M2: intercepts only
  assert STRUCTURE_SPECIFICATIONS["M2"].active_indices == [0, 4]
  assert STRUCTURE_SPECIFICATIONS["M2"].param_count == 2

  # M4 models: intercepts + 1 paired physical axis
  assert STRUCTURE_SPECIFICATIONS["M4-C"].active_indices == [0, 1, 4, 5]
  assert STRUCTURE_SPECIFICATIONS["M4-B"].active_indices == [0, 2, 4, 6]
  assert STRUCTURE_SPECIFICATIONS["M4-R"].active_indices == [0, 3, 4, 7]

  # M6 models: intercepts + 2 paired physical axes
  assert STRUCTURE_SPECIFICATIONS["M6-CB"].active_indices == [0, 1, 2, 4, 5, 6]
  assert STRUCTURE_SPECIFICATIONS["M6-CR"].active_indices == [0, 1, 3, 4, 5, 7]
  assert STRUCTURE_SPECIFICATIONS["M6-BR"].active_indices == [0, 2, 3, 4, 6, 7]

  # M8 model: all three physical axes
  assert STRUCTURE_SPECIFICATIONS["M8"].active_indices == [0, 1, 2, 3, 4, 5, 6,
                                                           7]
  assert STRUCTURE_SPECIFICATIONS["M8"].param_count == 8


def test_zero_embedding_and_extraction():
  """Validates that inactive coefficients embed as exact 0.0 in the 8-element physical vector."""
  for name, spec in STRUCTURE_SPECIFICATIONS.items():
    k = spec.param_count
    w_active = np.array([float(i + 1) * 1.5 for i in range(k)],
                        dtype=np.float64)

    w_full = embed_active_weights(w_active, spec.active_indices)
    assert len(w_full) == 8
    assert w_full.dtype == np.float64

    # Active coefficients match w_active
    for i, idx in enumerate(spec.active_indices):
      assert w_full[idx] == w_active[i]

    # Inactive coefficients are exact float 0.0
    inactive_indices = [idx for idx in range(8) if
                        idx not in spec.active_indices]
    for idx in inactive_indices:
      assert w_full[idx] == 0.0

    # Roundtrip extraction
    extracted = extract_active_weights(w_full, spec.active_indices)
    assert np.array_equal(extracted, w_active)


def _make_dummy_metrics(
    supported_rel_regret: float,
    worst_family_rel_regret: float,
    weighted_bce: float,
    family_rel_regrets: dict[str, float] | None = None,
    observation_losses: list[ObservationLoss] | None = None,
) -> EvaluationMetrics:
  return EvaluationMetrics(
      supported_rel_regret=supported_rel_regret,
      worst_family_rel_regret=worst_family_rel_regret,
      observed_abs_regret=100.0,
      observed_rel_regret=0.05,
      weighted_bce=weighted_bce,
      winner_accuracy=0.9,
      raw_winner_accuracy=0.9,
      decisive_count=10,
      stable_tie_count=2,
      total_count=12,
      stable_tie_neutral_fraction=1.0,
      total_weight=1.0,
      family_rel_regrets=family_rel_regrets or {"F1": supported_rel_regret,
                                                "F2": supported_rel_regret},
      family_bces={"F1": weighted_bce, "F2": weighted_bce},
      family_counts={"F1": 6, "F2": 6},
      observation_losses=observation_losses or [],
  )


def test_lambda_tie_breaking_order():
  """Validates deterministic lambda selection ordering and 1e-12 equivalence tolerance."""
  # 1. Lower pooled relative regret wins
  m_low_reg = _make_dummy_metrics(0.010, 0.020, 0.300)
  m_high_reg = _make_dummy_metrics(0.015, 0.015, 0.200)
  assert compare_candidate_metrics(m_low_reg, 1e-3, "M2", m_high_reg, 1e-3,
                                   "M2") == -1

  # 2. Equal pooled regret (within 1e-12) => lower worst-family regret wins
  m_equal_1 = _make_dummy_metrics(0.0100000000000, 0.020, 0.300)
  m_equal_2 = _make_dummy_metrics(0.0100000000000, 0.018, 0.350)
  assert compare_candidate_metrics(m_equal_2, 1e-3, "M2", m_equal_1, 1e-3,
                                   "M2") == -1

  # 3. Equal pooled and worst-family regret => lower weighted BCE wins
  m_bce_low = _make_dummy_metrics(0.010, 0.020, 0.250)
  m_bce_high = _make_dummy_metrics(0.010, 0.020, 0.280)
  assert compare_candidate_metrics(m_bce_low, 1e-3, "M2", m_bce_high, 1e-3,
                                   "M2") == -1

  # 4. Equal pooled regret, worst-family regret, and BCE => larger lambda wins
  m_same_1 = _make_dummy_metrics(0.010, 0.020, 0.250)
  m_same_2 = _make_dummy_metrics(0.010, 0.020, 0.250)
  # lambda = 1.0 preferred over lambda = 0.01
  assert compare_candidate_metrics(m_same_1, 1.0, "M2", m_same_2, 0.01,
                                   "M2") == -1
  assert compare_candidate_metrics(m_same_2, 0.01, "M2", m_same_1, 1.0,
                                   "M2") == 1

  # Test select_best_lambda_for_structure
  eval_dict = {
    1e-4: _make_dummy_metrics(0.020, 0.030, 0.400),
    1e-3: _make_dummy_metrics(0.010, 0.020, 0.300),  # Best regret
    1e-2: _make_dummy_metrics(0.010, 0.020, 0.300),
    # Same regret/worst/BCE, larger lambda (1e-2 > 1e-3)
    1e-1: _make_dummy_metrics(0.015, 0.025, 0.350),
  }
  best_l2, best_met = select_best_lambda_for_structure(eval_dict, "M4-C")
  assert best_l2 == 1e-2


def _make_dummy_obs(
    pair_id: str,
    action: str,
    outcome: Outcome,
    delta: float,
    uncertainty: float,
    is_correct: bool,
) -> ObservationLoss:
  t_k = 1000.0
  t_k_minus_1 = 1000.0 + delta
  is_decisive = outcome in (Outcome.K_WINS, Outcome.K_MINUS_1_WINS)
  adv = max(0.0, abs(delta) - uncertainty) if is_decisive else 0.0
  return ObservationLoss(
      pair_id=pair_id,
      family_id="Fam_1",
      z=-1.0 if action == "PARTICIPATE" else 1.0,
      y=0.0 if outcome == Outcome.K_WINS else (
        1.0 if outcome == Outcome.K_MINUS_1_WINS else 0.5),
      predicted_action=action,
      effective_outcome=outcome,
      label_evidence_basis=LabelEvidenceBasis.WHOLE_AGREEMENT,
      t_k=t_k,
      t_k_minus_1=t_k_minus_1,
      delta=delta,
      uncertainty=uncertainty,
      supported_advantage=adv,
      is_decisive=is_decisive,
      is_correct=is_correct,
      supported_loss=0.0 if is_correct else adv,
      observed_loss=0.0 if is_correct else abs(delta),
      supported_rel_loss=0.0 if is_correct else adv / 1000.0,
      observed_rel_loss=0.0 if is_correct else abs(delta) / 1000.0,
      bce_loss=0.1,
      is_stable_tie=(outcome == Outcome.STABLE_TIE),
      is_tie_neutral=(outcome == Outcome.STABLE_TIE),
  )


def test_all_four_parsimony_conditions():
  """Validates each of the 4 parsimony conditions independently and in combination."""
  # Baseline incumbent observations: 4 rows
  # Row 1: decisive K_WINS, delta=-50, unc=10 (a=40). Incumbent predicts CACHE (wrong).
  # Row 2: decisive K_WINS, delta=-40, unc=10 (a=30). Incumbent predicts PARTICIPATE (correct).
  # Row 3: decisive K_MINUS_1_WINS, delta=+60, unc=10 (a=50). Incumbent predicts CACHE (correct).
  # Row 4: stable tie.
  inc_obs = [
    _make_dummy_obs("p1", "CACHE", Outcome.K_WINS, -50.0, 10.0,
                    is_correct=False),
    _make_dummy_obs("p2", "PARTICIPATE", Outcome.K_WINS, -40.0, 10.0,
                    is_correct=True),
    _make_dummy_obs("p3", "CACHE", Outcome.K_MINUS_1_WINS, 60.0, 10.0,
                    is_correct=True),
    _make_dummy_obs("p4", "PARTICIPATE", Outcome.STABLE_TIE, 0.0, 5.0,
                    is_correct=True),
  ]
  inc_metrics = _make_dummy_metrics(
      supported_rel_regret=0.040,
      worst_family_rel_regret=0.050,
      weighted_bce=0.300,
      family_rel_regrets={"F1": 0.040, "F2": 0.050, "F3": 0.030},
      observation_losses=inc_obs,
  )

  # 1. Candidate satisfies all 4 conditions:
  # - pooled regret: 0.020 < 0.040 (improves by > 1e-12) [Cond 1 PASS]
  # - worst-family: 0.045 <= 0.050 (does not worsen) [Cond 2 PASS]
  # - families F1 and F2 improve: F1 (0.020 < 0.040), F2 (0.045 < 0.050) (>= 2 families) [Cond 3 PASS]
  # - Row 1 corrected: candidate predicts PARTICIPATE (correct), a_1 = 40.0 > 0 [Cond 4 PASS]
  cand_obs_valid = [
    _make_dummy_obs("p1", "PARTICIPATE", Outcome.K_WINS, -50.0, 10.0,
                    is_correct=True),
    _make_dummy_obs("p2", "PARTICIPATE", Outcome.K_WINS, -40.0, 10.0,
                    is_correct=True),
    _make_dummy_obs("p3", "CACHE", Outcome.K_MINUS_1_WINS, 60.0, 10.0,
                    is_correct=True),
    _make_dummy_obs("p4", "PARTICIPATE", Outcome.STABLE_TIE, 0.0, 5.0,
                    is_correct=True),
  ]
  cand_metrics_valid = _make_dummy_metrics(
      supported_rel_regret=0.020,
      worst_family_rel_regret=0.045,
      weighted_bce=0.250,
      family_rel_regrets={"F1": 0.020, "F2": 0.045, "F3": 0.030},
      observation_losses=cand_obs_valid,
  )
  comp = evaluate_parsimony_conditions("M4-C", cand_metrics_valid, 1e-3, "M2",
                                       inc_metrics, 1e-3)
  assert comp.is_admissible is True
  assert comp.condition_1_passed is True
  assert comp.condition_2_passed is True
  assert comp.condition_3_passed is True
  assert comp.condition_4_passed is True

  # 2. Condition 1 fails (pooled regret does not improve)
  cand_metrics_c1_fail = _make_dummy_metrics(
      supported_rel_regret=0.040,  # same as incumbent
      worst_family_rel_regret=0.045,
      weighted_bce=0.250,
      family_rel_regrets={"F1": 0.020, "F2": 0.045, "F3": 0.030},
      observation_losses=cand_obs_valid,
  )
  comp_c1 = evaluate_parsimony_conditions("M4-C", cand_metrics_c1_fail, 1e-3,
                                          "M2", inc_metrics, 1e-3)
  assert comp_c1.is_admissible is False
  assert comp_c1.condition_1_passed is False

  # 3. Condition 2 fails (worst-family regret worsens by > 1e-12)
  cand_metrics_c2_fail = _make_dummy_metrics(
      supported_rel_regret=0.020,
      worst_family_rel_regret=0.055,  # 0.055 > 0.050
      weighted_bce=0.250,
      family_rel_regrets={"F1": 0.020, "F2": 0.055, "F3": 0.030},
      observation_losses=cand_obs_valid,
  )
  comp_c2 = evaluate_parsimony_conditions("M4-C", cand_metrics_c2_fail, 1e-3,
                                          "M2", inc_metrics, 1e-3)
  assert comp_c2.is_admissible is False
  assert comp_c2.condition_2_passed is False

  # 4. Condition 3 fails (only 1 family improves)
  cand_metrics_c3_fail = _make_dummy_metrics(
      supported_rel_regret=0.020,
      worst_family_rel_regret=0.050,
      weighted_bce=0.250,
      family_rel_regrets={"F1": 0.020, "F2": 0.050, "F3": 0.030},
      # only F1 improves
      observation_losses=cand_obs_valid,
  )
  comp_c3 = evaluate_parsimony_conditions("M4-C", cand_metrics_c3_fail, 1e-3,
                                          "M2", inc_metrics, 1e-3)
  assert comp_c3.is_admissible is False
  assert comp_c3.condition_3_passed is False

  # 5. Condition 4 fails (differing decision does not have a_i > 0)
  # Suppose row 1 had delta=-8, unc=10 => a_1 = max(0, 8-10) = 0.0
  cand_obs_c4_fail = [
    _make_dummy_obs("p1", "PARTICIPATE", Outcome.K_WINS, -8.0, 10.0,
                    is_correct=True),
    _make_dummy_obs("p2", "PARTICIPATE", Outcome.K_WINS, -40.0, 10.0,
                    is_correct=True),
    _make_dummy_obs("p3", "CACHE", Outcome.K_MINUS_1_WINS, 60.0, 10.0,
                    is_correct=True),
    _make_dummy_obs("p4", "PARTICIPATE", Outcome.STABLE_TIE, 0.0, 5.0,
                    is_correct=True),
  ]
  cand_metrics_c4_fail = _make_dummy_metrics(
      supported_rel_regret=0.020,
      worst_family_rel_regret=0.045,
      weighted_bce=0.250,
      family_rel_regrets={"F1": 0.020, "F2": 0.045, "F3": 0.030},
      observation_losses=cand_obs_c4_fail,
  )
  comp_c4 = evaluate_parsimony_conditions("M4-C", cand_metrics_c4_fail, 1e-3,
                                          "M2", inc_metrics, 1e-3)
  assert comp_c4.is_admissible is False
  assert comp_c4.condition_4_passed is False


def test_procedural_parsimony_multi_tier_continuation():
  """Validates that parsimony evaluates larger sizes even if size 4 fails."""
  # Incumbent M2
  m2_metrics = _make_dummy_metrics(0.050, 0.060, 0.400)

  # Size 4 candidates: all fail condition 1 (regret >= 0.050)
  m4_c_metrics = _make_dummy_metrics(0.052, 0.062, 0.390)
  m4_b_metrics = _make_dummy_metrics(0.051, 0.061, 0.395)
  m4_r_metrics = _make_dummy_metrics(0.050, 0.060, 0.400)

  # Size 6 candidate M6-CB succeeds against M2:
  # decisive row corrected with a_i > 0
  m6_obs = [
    _make_dummy_obs("p1", "PARTICIPATE", Outcome.K_WINS, -50.0, 10.0,
                    is_correct=True),
  ]
  m2_obs = [
    _make_dummy_obs("p1", "CACHE", Outcome.K_WINS, -50.0, 10.0,
                    is_correct=False),
  ]
  m2_metrics = _make_dummy_metrics(
      0.050, 0.060, 0.400,
      family_rel_regrets={"F1": 0.050, "F2": 0.060},
      observation_losses=m2_obs,
  )
  m6_cb_metrics = _make_dummy_metrics(
      0.020, 0.030, 0.250,
      family_rel_regrets={"F1": 0.020, "F2": 0.030},
      observation_losses=m6_obs,
  )
  m6_cr_metrics = _make_dummy_metrics(0.060, 0.070, 0.450)
  m6_br_metrics = _make_dummy_metrics(0.060, 0.070, 0.450)
  m8_metrics = _make_dummy_metrics(0.025, 0.035, 0.260)  # worse than M6-CB

  selected_dict = {
    "M2": (1e-3, m2_metrics),
    "M4-C": (1e-3, m4_c_metrics),
    "M4-B": (1e-3, m4_b_metrics),
    "M4-R": (1e-3, m4_r_metrics),
    "M6-CB": (1e-3, m6_cb_metrics),
    "M6-CR": (1e-3, m6_cr_metrics),
    "M6-BR": (1e-3, m6_br_metrics),
    "M8": (1e-3, m8_metrics),
  }

  parsimony_result = execute_procedural_parsimony(selected_dict)
  assert parsimony_result.selected_structure == "M6-CB"
  assert parsimony_result.incumbent_progression == ["M2", "M6-CB"]
