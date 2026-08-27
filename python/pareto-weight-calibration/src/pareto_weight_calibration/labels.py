"""Winner determination, variance-aware comparison rules, and confidence weighting."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional, Tuple

from pareto_weight_calibration.types import (
    ArmPerformance,
    ComparisonMetrics,
    LabelEvidenceBasis,
    Outcome,
    TrajectoryStatus,
)


@dataclass(frozen=True)
class SynthesisResult:
  """Complete result of label and weight synthesis."""
  effective_outcome: Outcome
  trajectory_status: TrajectoryStatus
  y: float
  pair_weight: float
  label_evidence_basis: LabelEvidenceBasis
  basis_throughput_k: float = 0.0
  basis_throughput_k_minus_1: float = 0.0
  basis_delta: float = 0.0
  basis_variance_k: float = 0.0
  basis_variance_k_minus_1: float = 0.0
  basis_uncertainty: float = 0.0

  def __iter__(self):
    """Allows unpacking as (outcome, status, y, weight) for backwards compatibility."""
    return iter((self.effective_outcome, self.trajectory_status, self.y,
                 self.pair_weight))


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
        perf_a: Optional[ArmPerformance] = None,
        perf_b: Optional[ArmPerformance] = None,
    ) -> SynthesisResult:
      """Synthesizes whole-run and late-region metrics into final training label, weight, and evidence basis.

      Returns:
          SynthesisResult containing effective_outcome, trajectory_status, y, pair_weight,
          label_evidence_basis, and basis throughput metrics.
      """
        whole = whole_metrics.outcome
        late = late_metrics.outcome

      # Default basis metrics from whole-run if perfs not supplied
      mean_a = perf_a.mean if perf_a else 0.0
      mean_b = perf_b.mean if perf_b else 0.0
      var_a = perf_a.variance if perf_a else 0.0
      var_b = perf_b.variance if perf_b else 0.0
      late_mean_a = perf_a.late_mean if perf_a else 0.0
      late_mean_b = perf_b.late_mean if perf_b else 0.0
      late_var_a = perf_a.late_variance if perf_a else 0.0
      late_var_b = perf_b.late_variance if perf_b else 0.0

        # Case 1: High-confidence stable agreement
        if whole == late and whole in (Outcome.K_WINS, Outcome.K_MINUS_1_WINS):
            winner_eligible = (
                arm_a_trajectory_eligible if whole == Outcome.K_WINS else arm_b_trajectory_eligible
            )
            if not winner_eligible:
              return SynthesisResult(
                  effective_outcome=Outcome.INCONCLUSIVE,
                  trajectory_status=TrajectoryStatus.INELIGIBLE_TRAJECTORY,
                  y=0.5,
                  pair_weight=0.0,
                  label_evidence_basis=LabelEvidenceBasis.NONE,
                  basis_throughput_k=mean_a,
                  basis_throughput_k_minus_1=mean_b,
                  basis_delta=whole_metrics.delta,
                  basis_variance_k=var_a,
                  basis_variance_k_minus_1=var_b,
                  basis_uncertainty=whole_metrics.uncertainty,
                )
            status = TrajectoryStatus.STABLE_AGREEMENT
            effective_outcome = whole
            y = 0.0 if whole == Outcome.K_WINS else 1.0
            weight = cls._separation_weight(whole_metrics)
            return SynthesisResult(
                effective_outcome=effective_outcome,
                trajectory_status=status,
                y=y,
                pair_weight=weight,
                label_evidence_basis=LabelEvidenceBasis.WHOLE_AGREEMENT,
                basis_throughput_k=mean_a,
                basis_throughput_k_minus_1=mean_b,
                basis_delta=whole_metrics.delta,
                basis_variance_k=var_a,
                basis_variance_k_minus_1=var_b,
                basis_uncertainty=whole_metrics.uncertainty,
            )

        # Case 2: Stable tie
        if whole == Outcome.STABLE_TIE and late == Outcome.STABLE_TIE:
            if not (arm_a_trajectory_eligible and arm_b_trajectory_eligible):
              return SynthesisResult(
                  effective_outcome=Outcome.INCONCLUSIVE,
                  trajectory_status=TrajectoryStatus.INELIGIBLE_TRAJECTORY,
                  y=0.5,
                  pair_weight=0.0,
                  label_evidence_basis=LabelEvidenceBasis.NONE,
                  basis_throughput_k=mean_a,
                  basis_throughput_k_minus_1=mean_b,
                  basis_delta=whole_metrics.delta,
                  basis_variance_k=var_a,
                  basis_variance_k_minus_1=var_b,
                  basis_uncertainty=whole_metrics.uncertainty,
                )
            status = TrajectoryStatus.STABLE_AGREEMENT
            effective_outcome = Outcome.STABLE_TIE
            y = 0.5
            p = whole_metrics.practical_margin
            weight = (
              max(0.0, 1.0 - abs(whole_metrics.delta) / p)
              * max(0.0, 1.0 - whole_metrics.uncertainty / p)
              if p > 0 else 0.0
            )
            return SynthesisResult(
                effective_outcome=effective_outcome,
                trajectory_status=status,
                y=y,
                pair_weight=weight,
                label_evidence_basis=LabelEvidenceBasis.STABLE_TIE,
                basis_throughput_k=mean_a,
                basis_throughput_k_minus_1=mean_b,
                basis_delta=whole_metrics.delta,
                basis_variance_k=var_a,
                basis_variance_k_minus_1=var_b,
                basis_uncertainty=whole_metrics.uncertainty,
            )

        # Case 3: Late convergence (whole-run inconclusive, but late trajectory shows consistent winner)
        if whole == Outcome.INCONCLUSIVE and late in (Outcome.K_WINS, Outcome.K_MINUS_1_WINS):
            winner_eligible = (
                arm_a_trajectory_eligible if late == Outcome.K_WINS else arm_b_trajectory_eligible
            )
            if not winner_eligible:
              return SynthesisResult(
                  effective_outcome=Outcome.INCONCLUSIVE,
                  trajectory_status=TrajectoryStatus.INELIGIBLE_TRAJECTORY,
                  y=0.5,
                  pair_weight=0.0,
                  label_evidence_basis=LabelEvidenceBasis.NONE,
                  basis_throughput_k=late_mean_a,
                  basis_throughput_k_minus_1=late_mean_b,
                  basis_delta=late_metrics.delta,
                  basis_variance_k=late_var_a,
                  basis_variance_k_minus_1=late_var_b,
                  basis_uncertainty=late_metrics.uncertainty,
                )
            status = TrajectoryStatus.LATE_CONVERGENCE
            effective_outcome = late
            y = 0.0 if late == Outcome.K_WINS else 1.0
            weight = cls._separation_weight(late_metrics, stability_factor=0.5)
            return SynthesisResult(
                effective_outcome=effective_outcome,
                trajectory_status=status,
                y=y,
                pair_weight=weight,
                label_evidence_basis=LabelEvidenceBasis.LATE_CONVERGENCE,
                basis_throughput_k=late_mean_a,
                basis_throughput_k_minus_1=late_mean_b,
                basis_delta=late_metrics.delta,
                basis_variance_k=late_var_a,
                basis_variance_k_minus_1=late_var_b,
                basis_uncertainty=late_metrics.uncertainty,
            )

        # Case 4: Decisive conflict between whole and late
        if (
            whole in (Outcome.K_WINS, Outcome.K_MINUS_1_WINS)
            and late in (Outcome.K_WINS, Outcome.K_MINUS_1_WINS)
            and whole != late
        ):
          return SynthesisResult(
              effective_outcome=Outcome.INCONCLUSIVE,
              trajectory_status=TrajectoryStatus.CONFLICT,
              y=0.5,
              pair_weight=0.0,
              label_evidence_basis=LabelEvidenceBasis.NONE,
              basis_throughput_k=mean_a,
              basis_throughput_k_minus_1=mean_b,
              basis_delta=whole_metrics.delta,
              basis_variance_k=var_a,
              basis_variance_k_minus_1=var_b,
              basis_uncertainty=whole_metrics.uncertainty,
            )

        # Case 5: Other non-decisive combinations
      return SynthesisResult(
          effective_outcome=Outcome.INCONCLUSIVE,
          trajectory_status=(
            TrajectoryStatus.LATE_CONVERGENCE
            if late == Outcome.STABLE_TIE
            else TrajectoryStatus.UNSTABLE_DISPERSION
          ),
          y=0.5,
          pair_weight=0.0,
          label_evidence_basis=LabelEvidenceBasis.NONE,
          basis_throughput_k=mean_a,
          basis_throughput_k_minus_1=mean_b,
          basis_delta=whole_metrics.delta,
          basis_variance_k=var_a,
          basis_variance_k_minus_1=var_b,
          basis_uncertainty=whole_metrics.uncertainty,
        )
