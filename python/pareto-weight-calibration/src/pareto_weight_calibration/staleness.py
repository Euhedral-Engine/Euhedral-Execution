"""Contention staleness telemetry parser, rank-K filtering, and 2-stage median aggregation."""

from __future__ import annotations

import csv
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple
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
    ) -> List[StalenessSample]:
        """Parses a contention_staleness.tsv file into structured samples."""
        if not tsv_path.exists() or not tsv_path.is_file():
            raise FileNotFoundError(f"Staleness file not found: {tsv_path}")

        if verify_checksum:
            ChecksumVerifier.verify_file(tsv_path, require_sidecar=False)

        samples: List[StalenessSample] = []
        with open(tsv_path, "r", encoding="utf-8", errors="replace") as f:
            reader = csv.DictReader(f, delimiter="\t")
            if not reader.fieldnames:
                raise StalenessParseError(f"Empty or headerless TSV: {tsv_path}")

            for row_idx, row in enumerate(reader):
                try:
                    iter_idx = int(row.get("iteration", 0))
                    core = int(row.get("core", 0))
                    segment = row.get("segment", "steadyState")
                    sample_idx = int(row.get("sampleIndex", row_idx))
                    contention = int(row["measuredContention"])
                    raw_contention = int(row.get("lastRawContention", contention))
                    obs_count = int(row.get("contentionObservationCount", 1))
                    exec_path = row.get("executionPath", "UNKNOWN")
                    cache_count = int(row.get("localCacheCount", 0))
                    prod_handles = int(row.get("productiveHandleCount", 0))
                    reg_workers = int(row.get("registeredWorkers", 0))
                    rank = int(row.get("workerRank", 0))
                    body_cost = float(row.get("smoothedBodyCostNs", 0.0))
                    
                    ready_raw = row.get("bodyHistoryReady", "1").strip().lower()
                    ready = ready_raw in ("1", "true", "t", "yes")

                    attempts = int(row.get("totalAcquisitionAttempts", 0))

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
                except (KeyError, ValueError) as e:
                    raise StalenessParseError(
                        f"Malformed row #{row_idx} in {tsv_path}: {e}"
                    ) from e

        return samples

    @classmethod
    def discover_staleness_files(cls, run_dir: Path) -> List[Path]:
        """Discovers all contention_staleness.tsv files across root or fork subdirectories."""
        files: List[Path] = []
        direct = run_dir / "contention_staleness.tsv"
        if direct.exists():
            files.append(direct)
        else:
            fork_subdirs = [p for p in run_dir.iterdir() if p.is_dir() and p.name.startswith("fork-")]
            for f_dir in sorted(fork_subdirs, key=lambda p: p.name):
                f_tsv = f_dir / "contention_staleness.tsv"
                if f_tsv.exists():
                    files.append(f_tsv)
        return files

    @classmethod
    def extract_active_features(
        cls,
        run_dir: Path,
        target_rank: int,
        verify_checksum: bool = True,
    ) -> ActiveStateFeatures:
        """Extracts decision-point features for rank == target_rank in the active arm (Arm A)."""
        files = cls.discover_staleness_files(run_dir)
        if not files:
            raise StalenessParseError(f"No contention_staleness.tsv found in {run_dir}")

        per_fork_c: List[float] = []
        per_fork_body: List[float] = []
        per_fork_p: List[float] = []
        registered_workers_observed: Optional[int] = None

        for file_path in files:
            samples = cls.parse_staleness_file(file_path, verify_checksum=verify_checksum)
            # Filter for target rank, steady state segment, ready body history, and initialized contention
            eligible = [
                s for s in samples
                if s.worker_rank == target_rank
                and s.body_history_ready
                and s.contention_observation_count > 0
                and s.smoothed_body_cost_ns > 0
                and math.isfinite(s.smoothed_body_cost_ns)
            ]

            # Prefer steadyState segment if present
            steady_eligible = [s for s in eligible if s.segment == "steadyState"]
            used_samples = steady_eligible if steady_eligible else eligible

            if not used_samples:
                continue

            if registered_workers_observed is None:
                registered_workers_observed = used_samples[0].registered_workers

            c_vals = [s.measured_contention / 1_000_000.0 for s in used_samples]
            body_vals = [s.smoothed_body_cost_ns for s in used_samples]
            p_vals = [float(s.productive_handle_count) for s in used_samples]

            per_fork_c.append(float(np.median(c_vals)))
            per_fork_body.append(float(np.median(body_vals)))
            per_fork_p.append(float(np.median(p_vals)))

        if not per_fork_c:
            raise StalenessParseError(
                f"No eligible steady-state rank-{target_rank} telemetry with ready body history found in {run_dir}"
            )

        # Cross-fork medians
        c_median = float(np.median(per_fork_c))
        body_median = float(np.median(per_fork_body))
        p_median = float(np.median(per_fork_p))
        b_median = float(np.log1p(body_median))
        r_val = registered_workers_observed or 0

        return ActiveStateFeatures(
            c=c_median,
            smoothed_body_cost_ns=body_median,
            b=b_median,
            P=p_median,
            R=r_val,
            K=target_rank,
        )

    @classmethod
    def extract_withdrawn_diagnostics(
        cls,
        run_dir: Path,
        target_rank: int,
        verify_checksum: bool = True,
    ) -> WithdrawnDiagnosticState:
        """Extracts post-treatment diagnostic state for rank == target_rank in Arm B (CACHE)."""
        files = cls.discover_staleness_files(run_dir)
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
                cls.parse_staleness_file(file_path, verify_checksum=verify_checksum)
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
