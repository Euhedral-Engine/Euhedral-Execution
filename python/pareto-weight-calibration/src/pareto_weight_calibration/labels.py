"""Winner determination, variance-aware comparison rules, and confidence weighting."""

from __future__ import annotations

import math
from typing import Tuple

from pareto_weight_calibration.types import (
    ArmPerformance,
    ComparisonMetrics,
    Outcome,
    TrajectoryStatus,
)


class LabelSynthesizer:
    """Computes comparison metrics, target labels (y), and confidence weights."""

    @staticmethod
    def _separation_weight(metrics: ComparisonMetrics, stability_factor: float = 1.0) -> float:
        margin = metrics.governing_margin
        if margin <= 0.0:
            return stability_factor if abs(metrics.delta) > 0.0 else 0.0
        separation = max(0.0, abs(metrics.delta) - margin) / margin
        return min(1.0, separation / 2.0) * stability_factor

    @classmethod
    def compare_arms(
        cls,
        perf_a: ArmPerformance,
        perf_b: ArmPerformance,
        use_late: bool = False,
    ) -> ComparisonMetrics:
        """Computes variance-aware comparison metrics between Arm A (K) and Arm B (K-1).

        Args:
            perf_a: Arm A (candidate K active) performance summary.
            perf_b: Arm B (candidate K-1 active) performance summary.
            use_late: If True, uses late-region means and variances.

        Returns:
            ComparisonMetrics with delta, uncertainty, margins, and classified Outcome.
        """
        mean_a = perf_a.late_mean if use_late else perf_a.mean
        mean_b = perf_b.late_mean if use_late else perf_b.mean
        var_a = perf_a.late_variance if use_late else perf_a.variance
        var_b = perf_b.late_variance if use_late else perf_b.variance
        n_a = max(1, perf_a.fork_count)
        n_b = max(1, perf_b.fork_count)

        delta = mean_b - mean_a
        rel_delta_pct = (100.0 * delta / mean_a) if mean_a > 0 else 0.0

        se = math.sqrt((var_a / n_a) + (var_b / n_b))
        uncertainty = 2.0 * se
        practical_margin = 0.01 * max(mean_a, mean_b)
        governing_margin = uncertainty

        # A policy wins when its mean remains above the other policy's mean after
        # subtracting the combined fork-level throughput uncertainty. Variance has
        # no separate hard cutoff and the practical tie band does not suppress a win.
        if delta > uncertainty:
            outcome = Outcome.K_MINUS_1_WINS
        elif delta < -uncertainty:
            outcome = Outcome.K_WINS
        elif uncertainty <= practical_margin and abs(delta) <= practical_margin:
            outcome = Outcome.STABLE_TIE
        else:
            outcome = Outcome.INCONCLUSIVE

        return ComparisonMetrics(
            delta=delta,
            rel_delta_percent=rel_delta_pct,
            uncertainty=uncertainty,
            practical_margin=practical_margin,
            governing_margin=governing_margin,
            outcome=outcome,
        )

    @classmethod
    def synthesize_label_and_weight(
        cls,
        whole_metrics: ComparisonMetrics,
        late_metrics: ComparisonMetrics,
        arm_a_trajectory_eligible: bool,
        arm_b_trajectory_eligible: bool,
    ) -> Tuple[Outcome, TrajectoryStatus, float, float]:
        """Synthesizes whole-run and late-region metrics into final training label and weight.

        Returns:
            Tuple of (effective_outcome: Outcome, trajectory_status: TrajectoryStatus, y: float, pair_weight: float).
        """
        whole = whole_metrics.outcome
        late = late_metrics.outcome

        # Case 1: High-confidence stable agreement
        if whole == late and whole in (Outcome.K_WINS, Outcome.K_MINUS_1_WINS):
            winner_eligible = (
                arm_a_trajectory_eligible if whole == Outcome.K_WINS else arm_b_trajectory_eligible
            )
            if not winner_eligible:
                return (
                    Outcome.INCONCLUSIVE,
                    TrajectoryStatus.INELIGIBLE_TRAJECTORY,
                    0.5,
                    0.0,
                )
            status = TrajectoryStatus.STABLE_AGREEMENT
            effective_outcome = whole
            y = 0.0 if whole == Outcome.K_WINS else 1.0
            weight = cls._separation_weight(whole_metrics)
            return (effective_outcome, status, y, weight)

        # Case 2: Stable tie
        if whole == Outcome.STABLE_TIE and late == Outcome.STABLE_TIE:
            if not (arm_a_trajectory_eligible and arm_b_trajectory_eligible):
                return (
                    Outcome.INCONCLUSIVE,
                    TrajectoryStatus.INELIGIBLE_TRAJECTORY,
                    0.5,
                    0.0,
                )
            status = TrajectoryStatus.STABLE_AGREEMENT
            effective_outcome = Outcome.STABLE_TIE
            y = 0.5
            p = whole_metrics.practical_margin
            weight = max(0.0, 1.0 - abs(whole_metrics.delta) / p) * max(
                0.0, 1.0 - whole_metrics.uncertainty / p
            ) if p > 0 else 0.0
            return (effective_outcome, status, y, weight)

        # Case 3: Late convergence (whole-run inconclusive, but late trajectory shows consistent winner)
        if whole == Outcome.INCONCLUSIVE and late in (Outcome.K_WINS, Outcome.K_MINUS_1_WINS):
            winner_eligible = (
                arm_a_trajectory_eligible if late == Outcome.K_WINS else arm_b_trajectory_eligible
            )
            if not winner_eligible:
                return (
                    Outcome.INCONCLUSIVE,
                    TrajectoryStatus.INELIGIBLE_TRAJECTORY,
                    0.5,
                    0.0,
                )
            status = TrajectoryStatus.LATE_CONVERGENCE
            effective_outcome = late
            y = 0.0 if late == Outcome.K_WINS else 1.0
            weight = cls._separation_weight(late_metrics, stability_factor=0.5)
            return (effective_outcome, status, y, weight)

        # Case 4: Decisive conflict between whole and late
        if (
            whole in (Outcome.K_WINS, Outcome.K_MINUS_1_WINS)
            and late in (Outcome.K_WINS, Outcome.K_MINUS_1_WINS)
            and whole != late
        ):
            return (
                Outcome.INCONCLUSIVE,
                TrajectoryStatus.CONFLICT,
                0.5,
                0.0,
            )

        # Case 5: Other non-decisive combinations
        return (
            Outcome.INCONCLUSIVE,
            TrajectoryStatus.LATE_CONVERGENCE if late == Outcome.STABLE_TIE else TrajectoryStatus.UNSTABLE_DISPERSION,
            0.5,
            0.0,
        )
