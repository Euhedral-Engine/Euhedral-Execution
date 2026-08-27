"""JMH benchmark output log parser and fork throughput metrics."""

from __future__ import annotations

import re
from pathlib import Path
from typing import List, Optional, Union
import numpy as np

from pareto_weight_calibration.types import ArmPerformance, ForkThroughput


class ThroughputParseError(Exception):
    """Raised when throughput logs cannot be parsed or contain no scores."""


FORK_SCORE_PATTERN = re.compile(
    r"(?:Fork\s+\d+:\s+|Iteration\s+\d+:\s+)?([0-9]+(?:\.[0-9]+)?)\s*(?:±\s*[0-9]+(?:\.[0-9]+)?\s*)?ops/s",
    re.IGNORECASE,
)
JMH_RESULT_LINE_PATTERN = re.compile(
    r"CalibrationBenchmark\.\w+\s+(?:thrpt|avgt)\s+\d+\s+([0-9]+(?:\.[0-9]+)?)\s+ops/s",
    re.IGNORECASE,
)
FORK_RUN_PATTERN = re.compile(r"#\s*Fork:\s*(\d+)\s*of\s*(\d+)")


class ThroughputParser:
    """Extracts fork-level and whole-run throughput from benchmark artifacts."""

    @classmethod
    def find_fork_dirs(cls, paths: Union[Path, List[Path]]) -> List[Path]:
      """Finds sorted JMH fork subdirectories across one or more sample directories."""
      path_list = [paths] if isinstance(paths, Path) else paths
      fork_dirs: List[Path] = []

      for p in path_list:
        if not p.is_dir():
          continue
        subdirs = [
          sub for sub in p.iterdir()
          if sub.is_dir() and sub.name.startswith("fork-")
        ]
        if subdirs:
          fork_dirs.extend(sorted(subdirs, key=lambda d: d.name))
        else:
          if (p / "benchmark_output.log").exists() or (
              p / "trajectory_windows.tsv"
          ).exists():
            fork_dirs.append(p)

      return fork_dirs

    @classmethod
    def parse_log_file(cls, log_path: Path) -> List[float]:
        """Parses JMH benchmark output log and extracts per-fork primary scores."""
        if not log_path.exists() or not log_path.is_file():
            raise FileNotFoundError(f"Benchmark log not found: {log_path}")

        scores: List[float] = []
        text = log_path.read_text(encoding="utf-8", errors="replace")

        for line in text.splitlines():
            line_str = line.strip()
            if "ops/s" in line_str:
                match = re.search(r"Iteration\s+\d+:\s+([0-9]+(?:\.[0-9]+)?)\s+ops/s", line_str)
                if match:
                    try:
                        scores.append(float(match.group(1)))
                    except ValueError:
                        pass

        if not scores:
            for match in JMH_RESULT_LINE_PATTERN.finditer(text):
                try:
                    scores.append(float(match.group(1)))
                except ValueError:
                    pass

        return scores

    @classmethod
    def compute_arm_performance(
        cls,
        forks: List[ForkThroughput],
        late_means: Optional[List[float]] = None,
    ) -> ArmPerformance:
      """Computes statistical summary across all fork results ($n=4$ in 4-fork runs).

      Args:
          forks: List of ForkThroughput results.
          late_means: Optional list of late-window mean throughput per fork.
      """
        if not forks:
            raise ThroughputParseError("Cannot compute performance for empty fork list")

        fork_scores = np.array([f.mean_ops_per_sec for f in forks], dtype=np.float64)
        n = len(fork_scores)
        mean_val = float(np.mean(fork_scores))
        var_val = float(np.var(fork_scores, ddof=1)) if n > 1 else 0.0
        std_val = float(np.sqrt(var_val))
        cv_val = float(std_val / mean_val) if mean_val > 0 else 0.0

        if late_means and len(late_means) > 0:
            late_arr = np.array(late_means, dtype=np.float64)
            late_n = len(late_arr)
            late_mean_val = float(np.mean(late_arr))
            late_var_val = float(np.var(late_arr, ddof=1)) if late_n > 1 else 0.0
            late_cv_val = float(np.sqrt(late_var_val) / late_mean_val) if late_mean_val > 0 else 0.0
        else:
          late_means_from_forks = [f.late_mean_ops_per_sec for f in forks]
          late_arr = np.array(late_means_from_forks, dtype=np.float64)
          late_n = len(late_arr)
          late_mean_val = float(np.mean(late_arr))
          late_var_val = float(np.var(late_arr, ddof=1)) if late_n > 1 else 0.0
          late_cv_val = (
            float(np.sqrt(
              late_var_val) / late_mean_val) if late_mean_val > 0 else 0.0
          )

        return ArmPerformance(
            mean=mean_val,
            variance=var_val,
            std_dev=std_val,
            cv=cv_val,
            fork_count=n,
            late_mean=late_mean_val,
            late_variance=late_var_val,
            late_cv=late_cv_val,
            forks=forks,
        )
