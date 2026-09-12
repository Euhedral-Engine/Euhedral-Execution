"""Unit tests for comparison rules, outcome classification, and confidence weighting."""

from __future__ import annotations

import numpy as np
import pytest

from pareto_weight_calibration.labels import LabelSynthesizer
from pareto_weight_calibration.types import (
    ArmPerformance,
    ForkThroughput,
    Outcome,
    TrajectoryStatus,
)


def create_arm_perf(mean: float, var: float = 100.0, fork_count: int = 3) -> ArmPerformance:
    return ArmPerformance(
        mean=mean,
        variance=var,
        std_dev=np.sqrt(var),
        cv=np.sqrt(var) / mean,
        fork_count=fork_count,
        late_mean=mean,
        late_variance=var,
        late_cv=np.sqrt(var) / mean,
        forks=[
            ForkThroughput(fork_index=i, mean_ops_per_sec=mean, late_mean_ops_per_sec=mean, is_late_stable=True)
            for i in range(fork_count)
        ],
    )


def test_k_wins_decisive():
    # Arm A (K) achieves 60000, Arm B (K-1) achieves 50000 -> delta = -10000 (A is better)
    perf_a = create_arm_perf(60000.0, var=100.0)
    perf_b = create_arm_perf(50000.0, var=100.0)

    whole_metrics = LabelSynthesizer.compare_arms(perf_a, perf_b, use_late=False)
    late_metrics = LabelSynthesizer.compare_arms(perf_a, perf_b, use_late=True)

    assert whole_metrics.outcome == Outcome.K_WINS
    assert late_metrics.outcome == Outcome.K_WINS

    outcome, status, y, weight = LabelSynthesizer.synthesize_label_and_weight(
        whole_metrics,
        late_metrics,
        arm_a_trajectory_eligible=True,
        arm_b_trajectory_eligible=True,
    )
    assert outcome == Outcome.K_WINS
    assert status == TrajectoryStatus.STABLE_AGREEMENT
    assert y == 0.0
    assert weight > 0.5  # High confidence


def test_k_minus_1_wins_decisive():
    # Arm A (K) achieves 50000, Arm B (K-1) achieves 60000 -> delta = +10000 (B is better)
    perf_a = create_arm_perf(50000.0, var=100.0)
    perf_b = create_arm_perf(60000.0, var=100.0)

    whole_metrics = LabelSynthesizer.compare_arms(perf_a, perf_b, use_late=False)
    late_metrics = LabelSynthesizer.compare_arms(perf_a, perf_b, use_late=True)

    assert whole_metrics.outcome == Outcome.K_MINUS_1_WINS
    assert late_metrics.outcome == Outcome.K_MINUS_1_WINS

    outcome, status, y, weight = LabelSynthesizer.synthesize_label_and_weight(
        whole_metrics,
        late_metrics,
        arm_a_trajectory_eligible=True,
        arm_b_trajectory_eligible=True,
    )
    assert outcome == Outcome.K_MINUS_1_WINS
    assert status == TrajectoryStatus.STABLE_AGREEMENT
    assert y == 1.0
    assert weight > 0.5


def test_stable_tie():
    # Both arms achieve almost identical throughput with minimal variance
    perf_a = create_arm_perf(50000.0, var=1.0)
    perf_b = create_arm_perf(50001.0, var=1.0)

    whole_metrics = LabelSynthesizer.compare_arms(perf_a, perf_b, use_late=False)
    late_metrics = LabelSynthesizer.compare_arms(perf_a, perf_b, use_late=True)

    assert whole_metrics.outcome == Outcome.STABLE_TIE
    assert late_metrics.outcome == Outcome.STABLE_TIE

    outcome, status, y, weight = LabelSynthesizer.synthesize_label_and_weight(
        whole_metrics,
        late_metrics,
        arm_a_trajectory_eligible=True,
        arm_b_trajectory_eligible=True,
    )
    assert outcome == Outcome.STABLE_TIE
    assert status == TrajectoryStatus.STABLE_AGREEMENT
    assert y == 0.5
    assert weight > 0.8  # Strong tie confidence


def test_late_convergence_discounted_weight():
    # Whole run is inconclusive (e.g. higher initial variance), but late is decisive K-1 wins
    perf_a = create_arm_perf(50000.0, var=25000000.0)  # High variance makes uncertainty > delta
    perf_b = create_arm_perf(54000.0, var=25000000.0)

    # Override late to be tightly decisive
    perf_a_late = create_arm_perf(50000.0, var=100.0)
    perf_b_late = create_arm_perf(60000.0, var=100.0)

    whole_metrics = LabelSynthesizer.compare_arms(perf_a, perf_b, use_late=False)
    late_metrics = LabelSynthesizer.compare_arms(perf_a_late, perf_b_late, use_late=True)

    assert whole_metrics.outcome == Outcome.INCONCLUSIVE
    assert late_metrics.outcome == Outcome.K_MINUS_1_WINS

    outcome, status, y, weight = LabelSynthesizer.synthesize_label_and_weight(
        whole_metrics,
        late_metrics,
        arm_a_trajectory_eligible=True,
        arm_b_trajectory_eligible=True,
    )
    assert outcome == Outcome.K_MINUS_1_WINS
    assert status == TrajectoryStatus.LATE_CONVERGENCE
    assert y == 1.0
    # Scaled down by 0.5 stabilityFactor
    assert weight <= 0.5


def test_winner_uses_uncertainty_lower_bound_without_practical_effect_cutoff():
    perf_a = create_arm_perf(100000.0, var=1.0)
    perf_b = create_arm_perf(100500.0, var=1.0)

    metrics = LabelSynthesizer.compare_arms(perf_a, perf_b)

    assert metrics.delta < metrics.practical_margin
    assert metrics.delta > metrics.uncertainty
    assert metrics.outcome == Outcome.K_MINUS_1_WINS


def test_winner_must_remain_higher_after_subtracting_uncertainty():
    perf_a = create_arm_perf(50000.0, var=25_000_000.0)
    perf_b = create_arm_perf(54000.0, var=25_000_000.0)

    metrics = LabelSynthesizer.compare_arms(perf_a, perf_b)

    assert metrics.delta > 0.0
    assert metrics.delta <= metrics.uncertainty
    assert metrics.outcome == Outcome.INCONCLUSIVE


def test_only_winning_policy_must_have_stable_or_improving_trajectory():
    perf_a = create_arm_perf(50000.0, var=100.0)
    perf_b = create_arm_perf(60000.0, var=100.0)
    whole_metrics = LabelSynthesizer.compare_arms(perf_a, perf_b)
    late_metrics = LabelSynthesizer.compare_arms(perf_a, perf_b, use_late=True)

    outcome, status, y, weight = LabelSynthesizer.synthesize_label_and_weight(
        whole_metrics,
        late_metrics,
        arm_a_trajectory_eligible=False,
        arm_b_trajectory_eligible=True,
    )

    assert outcome == Outcome.K_MINUS_1_WINS
    assert status == TrajectoryStatus.STABLE_AGREEMENT
    assert y == 1.0
    assert weight > 0.0


def test_declining_winning_policy_is_inconclusive():
    perf_a = create_arm_perf(50000.0, var=100.0)
    perf_b = create_arm_perf(60000.0, var=100.0)
    whole_metrics = LabelSynthesizer.compare_arms(perf_a, perf_b)
    late_metrics = LabelSynthesizer.compare_arms(perf_a, perf_b, use_late=True)

    outcome, status, y, weight = LabelSynthesizer.synthesize_label_and_weight(
        whole_metrics,
        late_metrics,
        arm_a_trajectory_eligible=True,
        arm_b_trajectory_eligible=False,
    )

    assert outcome == Outcome.INCONCLUSIVE
    assert status == TrajectoryStatus.INELIGIBLE_TRAJECTORY
    assert y == 0.5
    assert weight == 0.0
