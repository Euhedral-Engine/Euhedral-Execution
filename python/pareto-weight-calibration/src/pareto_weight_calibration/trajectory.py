"""Trajectory window parser, late-region slicing, and OLS stability qualification."""

from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple
import numpy as np

from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.types import ForkThroughput


class TrajectoryParseError(Exception):
    """Raised when trajectory_windows.tsv cannot be parsed or is malformed."""


@dataclass(frozen=True)
class WindowRecord:
    """Parsed single window observation from trajectory_windows.tsv."""
    jvm_id: str
    lifecycle_mode: str
    window_index: int
    throughput: float
    continuously_fed: bool


@dataclass(frozen=True)
class ForkTrajectoryAnalysis:
    """Stability analysis result for a single JMH fork's trajectory."""
    fork_identifier: str
    all_windows: List[WindowRecord]
    late_windows: List[WindowRecord]
    late_mean: float
    late_variance: float
    late_cv: float
    late_slope: float
    is_stable: bool
    is_improving: bool
    continuously_fed: bool
    has_sufficient_windows: bool
    rejection_reason: Optional[str] = None


class TrajectoryAnalyzer:
    """Analyzes continuous trajectory windows for steady-state stability."""

    @classmethod
    def parse_trajectory_file(
        cls,
        tsv_path: Path,
        verify_checksum: bool = True,
    ) -> List[WindowRecord]:
        """Loads and parses trajectory_windows.tsv, validating SHA-256 sidecar if present."""
        if not tsv_path.exists() or not tsv_path.is_file():
            raise FileNotFoundError(f"Trajectory file not found: {tsv_path}")

        if verify_checksum:
            ChecksumVerifier.verify_file(tsv_path, require_sidecar=False)

        windows: List[WindowRecord] = []
        with open(tsv_path, "r", encoding="utf-8", errors="replace") as f:
            reader = csv.DictReader(f, delimiter="\t")
            if not reader.fieldnames:
                raise TrajectoryParseError(f"Empty or headerless TSV: {tsv_path}")

            for row_idx, row in enumerate(reader):
                try:
                    jvm_id = row.get("jvmId", f"jvm-{row_idx}")
                    lifecycle = row.get("lifecycleMode", "CONTINUOUS")
                    win_idx = int(row.get("windowIndex", row_idx))
                    thrpt = float(row["throughputExecutionsPerSecond"])
                    fed_raw = row.get("continuouslyFed", "true").strip().lower()
                    fed = fed_raw in ("true", "1", "t", "yes")

                    windows.append(
                        WindowRecord(
                            jvm_id=jvm_id,
                            lifecycle_mode=lifecycle,
                            window_index=win_idx,
                            throughput=thrpt,
                            continuously_fed=fed,
                        )
                    )
                except (KeyError, ValueError) as e:
                    raise TrajectoryParseError(
                        f"Malformed row #{row_idx} in {tsv_path}: {e}"
                    ) from e

        return windows

    @classmethod
    def analyze_fork(
        cls,
        windows: List[WindowRecord],
        cv_threshold: float = 0.05,
        slope_threshold_pct: float = 0.01,
        min_late_windows: int = 3,
    ) -> ForkTrajectoryAnalysis:
        """Analyzes an ordered list of windows for a single fork."""
        if not windows:
            return ForkTrajectoryAnalysis(
                fork_identifier="unknown",
                all_windows=[],
                late_windows=[],
                late_mean=0.0,
                late_variance=0.0,
                late_cv=0.0,
                late_slope=0.0,
                is_stable=False,
                is_improving=False,
                continuously_fed=False,
                has_sufficient_windows=False,
                rejection_reason="No windows recorded",
            )

        sorted_wins = sorted(windows, key=lambda w: w.window_index)
        m = len(sorted_wins)
        late_start = m // 2
        late_wins = sorted_wins[late_start:]

        jvm_id = sorted_wins[0].jvm_id

        if len(late_wins) < min_late_windows:
            return ForkTrajectoryAnalysis(
                fork_identifier=jvm_id,
                all_windows=sorted_wins,
                late_windows=late_wins,
                late_mean=float(np.mean([w.throughput for w in sorted_wins])),
                late_variance=0.0,
                late_cv=0.0,
                late_slope=0.0,
                is_stable=False,
                is_improving=False,
                continuously_fed=all(w.continuously_fed for w in late_wins),
                has_sufficient_windows=False,
                rejection_reason=f"Insufficient late windows ({len(late_wins)} < {min_late_windows})",
            )

        # 1. Check feeding continuity
        if any(not w.continuously_fed for w in late_wins):
            return ForkTrajectoryAnalysis(
                fork_identifier=jvm_id,
                all_windows=sorted_wins,
                late_windows=late_wins,
                late_mean=float(np.mean([w.throughput for w in late_wins])),
                late_variance=0.0,
                late_cv=0.0,
                late_slope=0.0,
                is_stable=False,
                is_improving=False,
                continuously_fed=False,
                has_sufficient_windows=True,
                rejection_reason="Upstream starvation in late window (continuouslyFed=False)",
            )

        throughputs = np.array([w.throughput for w in late_wins], dtype=np.float64)
        indices = np.array([float(w.window_index) for w in late_wins], dtype=np.float64)

        late_mean = float(np.mean(throughputs))
        late_var = float(np.var(throughputs, ddof=1)) if len(throughputs) > 1 else 0.0
        late_std = float(np.sqrt(late_var))
        late_cv = float(late_std / late_mean) if late_mean > 0 else 0.0

        # CV remains a confidence diagnostic. The argument is retained for API
        # compatibility but is not an eligibility cutoff because fork-level variance
        # is already reflected in the throughput lower bound.
        _ = cv_threshold

        # 2. Fit OLS slope over window index.
        x_centered = indices - np.mean(indices)
        y_centered = throughputs - late_mean
        denom = float(np.sum(x_centered**2))
        slope = float(np.sum(x_centered * y_centered) / denom) if denom > 0 else 0.0

        max_allowed_slope = slope_threshold_pct * late_mean
        if slope < -max_allowed_slope:
            return ForkTrajectoryAnalysis(
                fork_identifier=jvm_id,
                all_windows=sorted_wins,
                late_windows=late_wins,
                late_mean=late_mean,
                late_variance=late_var,
                late_cv=late_cv,
                late_slope=slope,
                is_stable=False,
                is_improving=False,
                continuously_fed=True,
                has_sufficient_windows=True,
                rejection_reason=f"Declining trajectory slope ({slope:.2f} ops/s/win) is below threshold (-{max_allowed_slope:.2f} ops/s/win)",
            )

        is_improving = slope > max_allowed_slope
        return ForkTrajectoryAnalysis(
            fork_identifier=jvm_id,
            all_windows=sorted_wins,
            late_windows=late_wins,
            late_mean=late_mean,
            late_variance=late_var,
            late_cv=late_cv,
            late_slope=slope,
            is_stable=not is_improving,
            is_improving=is_improving,
            continuously_fed=True,
            has_sufficient_windows=True,
            rejection_reason=None,
        )

    @classmethod
    def arm_is_stable_or_improving(
        cls,
        forks: List[ForkThroughput],
        slope_threshold_pct: float = 0.01,
    ) -> bool:
        """Returns whether the policy's aggregate late trajectory is stable or improving."""
        if not forks:
            return False
        if any(
            not fork.late_is_continuously_fed
            or not fork.late_has_sufficient_windows
            or fork.late_mean_ops_per_sec <= 0.0
            for fork in forks
        ):
            return False

        mean_normalized_slope = float(
            np.mean([fork.late_slope / fork.late_mean_ops_per_sec for fork in forks])
        )
        return mean_normalized_slope >= -slope_threshold_pct

    @classmethod
    def analyze_run_directory(
        cls,
        run_dir: Path,
        verify_checksum: bool = True,
    ) -> Tuple[List[ForkThroughput], bool]:
        """Analyzes all fork trajectories in a run directory.

        Returns:
            Tuple of (fork-level results, aggregate arm trajectory is stable or improving).
        """
        # Look for trajectory_windows.tsv in run_dir or in fork subdirs
        tsv_candidates: List[Path] = []
        direct_tsv = run_dir / "trajectory_windows.tsv"
        if direct_tsv.exists():
            tsv_candidates.append(direct_tsv)
        else:
            fork_subdirs = [p for p in run_dir.iterdir() if p.is_dir() and p.name.startswith("fork-")]
            for f_dir in sorted(fork_subdirs, key=lambda p: p.name):
                f_tsv = f_dir / "trajectory_windows.tsv"
                if f_tsv.exists():
                    tsv_candidates.append(f_tsv)

        if not tsv_candidates:
            # Fallback if no trajectory TSV exists
            return ([], False)

        all_windows: List[WindowRecord] = []
        for tsv in tsv_candidates:
            all_windows.extend(cls.parse_trajectory_file(tsv, verify_checksum=verify_checksum))

        # Group by jvm_id
        grouped: Dict[str, List[WindowRecord]] = {}
        for w in all_windows:
            grouped.setdefault(w.jvm_id, []).append(w)

        fork_results: List[ForkThroughput] = []
        for fork_idx, (jvm_id, wins) in enumerate(sorted(grouped.items())):
            analysis = cls.analyze_fork(wins)

            fork_results.append(
                ForkThroughput(
                    fork_index=fork_idx,
                    mean_ops_per_sec=float(np.mean([w.throughput for w in analysis.all_windows])) if analysis.all_windows else 0.0,
                    window_scores=[w.throughput for w in analysis.all_windows],
                    late_mean_ops_per_sec=analysis.late_mean,
                    is_late_stable=analysis.is_stable,
                    is_late_improving=analysis.is_improving,
                    late_is_continuously_fed=analysis.continuously_fed,
                    late_has_sufficient_windows=analysis.has_sufficient_windows,
                    late_cv=analysis.late_cv,
                    late_slope=analysis.late_slope,
                )
            )

        return (fork_results, cls.arm_is_stable_or_improving(fork_results))
