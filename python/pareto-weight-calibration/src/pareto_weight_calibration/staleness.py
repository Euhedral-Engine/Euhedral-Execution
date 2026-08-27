"""Contention staleness telemetry parser, rank-K filtering, and 2-stage median aggregation."""

from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple, Union
import numpy as np

from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.types import ActiveStateFeatures, WithdrawnDiagnosticState


class StalenessParseError(Exception):
    """Raised when contention_staleness.tsv cannot be parsed or lacks eligible observations."""


@dataclass(frozen=True)
class StalenessSample:
    """Individual parsed row from contention_staleness.tsv."""
    iteration: int
    core: int
    segment: str
    sample_index: int
    measured_contention: int
    last_raw_contention: int
    contention_observation_count: int
    execution_path: str
    local_cache_count: int
    productive_handle_count: int
    registered_workers: int
    worker_rank: int
    smoothed_body_cost_ns: float
    body_history_ready: bool
    total_acquisition_attempts: int


class StalenessParser:
    """Extracts active decision-point coordinates and post-treatment diagnostic states."""

    @classmethod
    def parse_staleness_file(
        cls,
        tsv_path: Path,
        verify_checksum: bool = True,
        require_sidecar: bool = False,
    ) -> List[StalenessSample]:
        """Parses a contention_staleness.tsv file into structured samples."""
        if not tsv_path.exists() or not tsv_path.is_file():
            raise FileNotFoundError(f"Staleness file not found: {tsv_path}")

        if verify_checksum:
          ChecksumVerifier.verify_file(tsv_path,
                                       require_sidecar=require_sidecar)

        samples: List[StalenessSample] = []
        with open(tsv_path, "r", encoding="utf-8", errors="replace") as f:
          header_line = f.readline()
          if not header_line:
                raise StalenessParseError(f"Empty or headerless TSV: {tsv_path}")

          headers = [h.strip() for h in header_line.split("\t")]
          col_map = {h: idx for idx, h in enumerate(headers)}

          req_cols = ["measuredContention", "workerRank", "registeredWorkers"]
          for col in req_cols:
            if col not in col_map:
              raise StalenessParseError(
                f"TSV {tsv_path} missing required column: {col}")

          idx_iter = col_map.get("iteration", -1)
          idx_core = col_map.get("core", -1)
          idx_seg = col_map.get("segment", -1)
          idx_sidx = col_map.get("sampleIndex", -1)
          idx_cont = col_map["measuredContention"]
          idx_raw_cont = col_map.get("lastRawContention", -1)
          idx_obscnt = col_map.get("contentionObservationCount", -1)
          idx_path = col_map.get("executionPath", -1)
          idx_cache = col_map.get("localCacheCount", -1)
          idx_prod = col_map.get("productiveHandleCount", -1)
          idx_reg = col_map["registeredWorkers"]
          idx_rank = col_map["workerRank"]
          idx_body = col_map.get("smoothedBodyCostNs", -1)
          idx_ready = col_map.get("bodyHistoryReady", -1)
          idx_attempts = col_map.get("totalAcquisitionAttempts", -1)

          for row_idx, line in enumerate(f):
            line = line.strip()
            if not line:
              continue
            parts = line.split("\t")
                try:
                  iter_idx = int(
                      parts[idx_iter]) if idx_iter != -1 and idx_iter < len(
                    parts) else 0
                  core = int(
                      parts[idx_core]) if idx_core != -1 and idx_core < len(
                    parts) else 0
                  segment = parts[idx_seg] if idx_seg != -1 and idx_seg < len(
                    parts) else "steadyState"
                  sample_idx = int(
                      parts[idx_sidx]) if idx_sidx != -1 and idx_sidx < len(
                    parts) else row_idx
                  contention = int(parts[idx_cont])
                  raw_contention = int(parts[
                                         idx_raw_cont]) if idx_raw_cont != -1 and idx_raw_cont < len(
                    parts) else contention
                  obs_count = int(parts[
                                    idx_obscnt]) if idx_obscnt != -1 and idx_obscnt < len(
                    parts) else 1
                  exec_path = parts[
                    idx_path] if idx_path != -1 and idx_path < len(
                    parts) else "UNKNOWN"
                  cache_count = int(
                      parts[idx_cache]) if idx_cache != -1 and idx_cache < len(
                    parts) else 0
                  prod_handles = int(
                      parts[idx_prod]) if idx_prod != -1 and idx_prod < len(
                    parts) else 0
                  reg_workers = int(parts[idx_reg])
                  rank = int(parts[idx_rank])
                  body_cost = float(
                      parts[idx_body]) if idx_body != -1 and idx_body < len(
                    parts) else 0.0

                  ready_raw = parts[
                    idx_ready].lower() if idx_ready != -1 and idx_ready < len(
                    parts) else "1"
                    ready = ready_raw in ("1", "true", "t", "yes")

                  attempts = int(parts[
                                   idx_attempts]) if idx_attempts != -1 and idx_attempts < len(
                    parts) else 0

                    samples.append(
                        StalenessSample(
                            iteration=iter_idx,
                            core=core,
                            segment=segment,
                            sample_index=sample_idx,
                            measured_contention=contention,
                            last_raw_contention=raw_contention,
                            contention_observation_count=obs_count,
                            execution_path=exec_path,
                            local_cache_count=cache_count,
                            productive_handle_count=prod_handles,
                            registered_workers=reg_workers,
                            worker_rank=rank,
                            smoothed_body_cost_ns=body_cost,
                            body_history_ready=ready,
                            total_acquisition_attempts=attempts,
                        )
                    )
                except (IndexError, ValueError) as e:
                    raise StalenessParseError(
                        f"Malformed row #{row_idx} in {tsv_path}: {e}"
                    ) from e

        return samples

    @classmethod
    def discover_staleness_files(cls, paths: Union[Path, List[Path]]) -> List[
      Path]:
      """Discovers all contention_staleness.tsv files across sample directories or fork subdirectories."""
      path_list = [paths] if isinstance(paths, Path) else paths
        files: List[Path] = []

      for p in path_list:
        if not p.exists():
          continue
        if p.is_file() and p.name == "contention_staleness.tsv":
          files.append(p)
        elif p.is_dir():
          fork_subdirs = [sub for sub in p.iterdir() if
                          sub.is_dir() and sub.name.startswith("fork-")]
          if fork_subdirs:
            for f_dir in sorted(fork_subdirs, key=lambda d: d.name):
              f_tsv = f_dir / "contention_staleness.tsv"
              if f_tsv.exists():
                files.append(f_tsv)
          else:
            direct = p / "contention_staleness.tsv"
            if direct.exists():
              files.append(direct)
        return files

    @classmethod
    def extract_active_features(
        cls,
        run_paths: Union[Path, List[Path]],
        target_rank: int,
        verify_checksum: bool = True,
        require_sidecar: bool = False,
        expected_r: Optional[int] = None,
    ) -> ActiveStateFeatures:
      """Extracts decision-point features for rank == target_rank in the active arm (Arm A).

      Joins only the fixed late half of measurement windows/samples.
      Enforces authoritative physical R extracted from telemetry rows.
      """
      files = cls.discover_staleness_files(run_paths)
        if not files:
          raise StalenessParseError(
            f"No contention_staleness.tsv found in {run_paths}")

        per_fork_c: List[float] = []
        per_fork_body: List[float] = []
        per_fork_p: List[float] = []
      fork_r_values: List[int] = []

        for file_path in files:
          samples = cls.parse_staleness_file(
              file_path, verify_checksum=verify_checksum,
              require_sidecar=require_sidecar
          )
          # Filter for target rank, ready body history, initialized contention, finite positive body cost
            eligible = [
                s for s in samples
                if s.worker_rank == target_rank
                and s.body_history_ready
                and s.contention_observation_count > 0
                and s.smoothed_body_cost_ns > 0
                and math.isfinite(s.smoothed_body_cost_ns)
            ]

          # Prefer steadyState segment
            steady_eligible = [s for s in eligible if s.segment == "steadyState"]
          candidate_samples = steady_eligible if steady_eligible else eligible

          if not candidate_samples:
                continue

          # Join to fixed late half of ordered samples
          sorted_samples = sorted(candidate_samples,
                                  key=lambda s: (s.iteration, s.sample_index))
          m = len(sorted_samples)
          late_start = m // 2
          late_samples = sorted_samples[
            late_start:] if m >= 2 else sorted_samples

          if not late_samples:
            continue

          # Verify registeredWorkers consistency within fork
          fork_r_set = {s.registered_workers for s in late_samples}
          if len(fork_r_set) > 1:
            raise StalenessParseError(
                f"Inconsistent registeredWorkers within fork {file_path}: {fork_r_set}"
            )
          fork_r = next(iter(fork_r_set))
          if fork_r <= 0:
            raise StalenessParseError(
                f"Invalid registeredWorkers R={fork_r} in {file_path}"
            )
          fork_r_values.append(fork_r)

          c_vals = [s.measured_contention / 1_000_000.0 for s in late_samples]
          body_vals = [s.smoothed_body_cost_ns for s in late_samples]
          p_vals = [float(s.productive_handle_count) for s in late_samples]

            per_fork_c.append(float(np.median(c_vals)))
            per_fork_body.append(float(np.median(body_vals)))
            per_fork_p.append(float(np.median(p_vals)))

        if not per_fork_c:
            raise StalenessParseError(
                f"No eligible steady-state rank-{target_rank} telemetry with ready body history found in {run_paths}"
            )

      # Cross-fork consistency check for registeredWorkers
      distinct_r = set(fork_r_values)
      if len(distinct_r) > 1:
        raise StalenessParseError(
            f"Inconsistent registeredWorkers across forks: {distinct_r}"
        )
      authoritative_r = fork_r_values[0]

      if expected_r is not None and authoritative_r != expected_r:
        raise StalenessParseError(
            f"Authoritative R={authoritative_r} does not match expected R={expected_r}"
            )

        # Cross-fork medians
        c_median = float(np.median(per_fork_c))
        body_median = float(np.median(per_fork_body))
        p_median = float(np.median(per_fork_p))
        b_median = float(np.log1p(body_median))

        return ActiveStateFeatures(
            c=c_median,
            smoothed_body_cost_ns=body_median,
            b=b_median,
            P=p_median,
            R=authoritative_r,
            K=target_rank,
        )

    @classmethod
    def extract_withdrawn_diagnostics(
        cls,
        run_paths: Union[Path, List[Path]],
        target_rank: int,
        verify_checksum: bool = True,
        require_sidecar: bool = False,
    ) -> WithdrawnDiagnosticState:
        """Extracts post-treatment diagnostic state for rank == target_rank in Arm B (CACHE)."""
        files = cls.discover_staleness_files(run_paths)
        if not files:
            return WithdrawnDiagnosticState(
                c_stale=0.0,
                P_stale=0.0,
                local_cache_count=0,
                execution_path="UNKNOWN",
                acquisitions_attempted=0,
            )

        all_samples: List[StalenessSample] = []
        for file_path in files:
            all_samples.extend(
                cls.parse_staleness_file(
                    file_path, verify_checksum=verify_checksum,
                    require_sidecar=require_sidecar
                )
            )

        rank_samples = [s for s in all_samples if s.worker_rank == target_rank]
        if not rank_samples:
            return WithdrawnDiagnosticState(
                c_stale=0.0,
                P_stale=0.0,
                local_cache_count=0,
                execution_path="NOT_FOUND",
                acquisitions_attempted=0,
            )

        c_stale = float(np.median([s.measured_contention / 1_000_000.0 for s in rank_samples]))
        p_stale = float(np.median([float(s.productive_handle_count) for s in rank_samples]))
        cache_count = int(np.median([s.local_cache_count for s in rank_samples]))
        paths = [s.execution_path for s in rank_samples]
        dominant_path = max(set(paths), key=paths.count) if paths else "CACHE"
        total_attempts = sum(s.total_acquisition_attempts for s in rank_samples)

        return WithdrawnDiagnosticState(
            c_stale=c_stale,
            P_stale=p_stale,
            local_cache_count=cache_count,
            execution_path=dominant_path,
            acquisitions_attempted=total_attempts,
        )
