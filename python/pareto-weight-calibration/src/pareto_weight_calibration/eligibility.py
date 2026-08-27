"""Three-state artifact eligibility classification and verification."""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

from pareto_weight_calibration.checksum import ChecksumVerifier, \
  ChecksumVerificationError
from pareto_weight_calibration.config import load_trial_config
from pareto_weight_calibration.staleness import StalenessParser, \
  StalenessParseError
from pareto_weight_calibration.trajectory import TrajectoryAnalyzer, \
  TrajectoryParseError
from pareto_weight_calibration.types import (
  ArtifactEligibility,
  Manifest,
  ManifestPair,
  TrialConfig,
)

logger = logging.getLogger(__name__)

VALID_AUTHORITATIVE_R: Set[int] = {7, 23}


class EligibilityError(Exception):
  """Raised when an artifact fails eligibility verification under strict mode."""


class EligibilityAuditor:
  """Audits experimental artifacts and classifies them into ELIGIBLE, INELIGIBLE, or UNVERIFIABLE."""

  @classmethod
  def check_file_checksum(
      cls, file_path: Path, require_sidecar: bool = False
  ) -> Tuple[bool, Optional[str]]:
    """Verifies a file and its .sha256 sidecar.

    Returns:
        Tuple of (is_valid, error_reason).
    """
    if not file_path.exists():
      return False, f"Missing file: {file_path}"
    sidecar = file_path.parent / (file_path.name + ".sha256")
    if not sidecar.exists():
      if require_sidecar:
        return False, f"Missing SHA-256 sidecar for {file_path.name}"
      return True, None
    try:
      ChecksumVerifier.verify_file(file_path, require_sidecar=True)
      return True, None
    except (ChecksumVerificationError, Exception) as e:
      return False, f"Checksum verification failed for {file_path.name}: {e}"

  @classmethod
  def audit_arm(
      cls,
      sample_paths: List[Path],
      expected_k: int,
      expected_commit: Optional[str] = None,
      expected_actuator: str = "cache-v1",
      expected_park_ns: int = 15000,
      expected_topology_r: Optional[int] = None,
      require_valid_r: bool = True,
      require_sidecars: bool = False,
      is_withdrawn_arm: bool = False,
  ) -> Tuple[ArtifactEligibility, List[str], Dict[str, Any]]:
    """Audits all sample positions and forks of an experimental arm.

    Returns:
        Tuple of (eligibility: ArtifactEligibility, reasons: List[str], details: Dict[str, Any]).
    """
    reasons: List[str] = []
    unverifiable_reasons: List[str] = []
    details: Dict[str, Any] = {
      "sample_count": len(sample_paths),
      "fork_count": 0,
      "observed_r": None,
      "commit": None,
      "actuator": None,
    }

    if not sample_paths:
      return ArtifactEligibility.UNVERIFIABLE, [
        "No sample paths provided for arm"], details

    # 1. Inspect sample directories and configs
    all_fork_dirs: List[Path] = []
    for s_path in sample_paths:
      if not s_path.exists() or not s_path.is_dir():
        return ArtifactEligibility.UNVERIFIABLE, [
          f"Sample path not found: {s_path}"], details

      config_file = s_path / "trial_config.json"
      valid_chk, chk_err = cls.check_file_checksum(config_file,
                                                   require_sidecar=False)
      if not valid_chk:
        unverifiable_reasons.append(
          f"Config checksum error at {s_path.name}: {chk_err}")
        continue

      try:
        cfg = load_trial_config(s_path)
      except Exception as e:
        unverifiable_reasons.append(
          f"Failed to parse trial_config.json at {s_path.name}: {e}")
        continue

      cal = cfg.calibration_config
      details["commit"] = cfg.raw_json.get("runtimeCommit") or expected_commit
      details["actuator"] = cal.cache_actuator_version

      # Check configuration compatibility
      if cal.cache_actuator_version != expected_actuator:
        reasons.append(
            f"Mismatched actuator '{cal.cache_actuator_version}' (expected '{expected_actuator}')"
        )
      if cal.cache_park_ns != expected_park_ns:
        reasons.append(
            f"Mismatched cacheParkNs {cal.cache_park_ns} (expected {expected_park_ns})"
        )
      if cal.lifecycle_mode != "CONTINUOUS":
        reasons.append(
            f"Invalid lifecycleMode '{cal.lifecycle_mode}' (expected 'CONTINUOUS')"
        )
      if cal.forced_active_participant_count != expected_k:
        reasons.append(
            f"Mismatched forced cutoff {cal.forced_active_participant_count} (expected K={expected_k})"
        )

      # Discover fork directories inside sample
      fork_subdirs = [p for p in s_path.iterdir() if
                      p.is_dir() and p.name.startswith("fork-")]
      if fork_subdirs:
        all_fork_dirs.extend(sorted(fork_subdirs, key=lambda p: p.name))
      else:
        # Single directory without fork subdirectories
        if (s_path / "contention_staleness.tsv").exists() or (
            s_path / "trajectory_windows.tsv").exists():
          all_fork_dirs.append(s_path)

    details["fork_count"] = len(all_fork_dirs)
    if not all_fork_dirs:
      unverifiable_reasons.append(
        "No fork directories or telemetry found in arm samples")

    # 2. Check telemetry files and checksums for each fork
    observed_r_values: Set[int] = set()
    target_rank_observed = False

    for f_dir in all_fork_dirs:
      # Trajectory TSV
      traj_tsv = f_dir / "trajectory_windows.tsv"
      if traj_tsv.exists():
        valid_chk, chk_err = cls.check_file_checksum(traj_tsv,
                                                     require_sidecar=require_sidecars)
        if not valid_chk:
          unverifiable_reasons.append(
            f"Trajectory sidecar error at {f_dir.name}: {chk_err}")
        else:
          try:
            wins = TrajectoryAnalyzer.parse_trajectory_file(
                traj_tsv, verify_checksum=True, require_sidecar=require_sidecars
            )
            if not wins:
              unverifiable_reasons.append(
                f"Empty trajectory file at {f_dir.name}")
          except Exception as e:
            unverifiable_reasons.append(
              f"Unparseable trajectory at {f_dir.name}: {e}")
      else:
        # Check for benchmark log fallback
        bench_log = f_dir / "benchmark_output.log"
        if not bench_log.exists():
          unverifiable_reasons.append(
            f"Missing trajectory_windows.tsv at {f_dir.name}")

      # Staleness TSV
      stale_tsv = f_dir / "contention_staleness.tsv"
      if stale_tsv.exists():
        valid_chk, chk_err = cls.check_file_checksum(stale_tsv,
                                                     require_sidecar=require_sidecars)
        if not valid_chk:
          unverifiable_reasons.append(
            f"Staleness sidecar error at {f_dir.name}: {chk_err}")
        else:
          try:
            samples = StalenessParser.parse_staleness_file(
                stale_tsv, verify_checksum=True,
                require_sidecar=require_sidecars
            )
            # Check target rank telemetry
            rank_rows = [s for s in samples if s.worker_rank == expected_k]
            if rank_rows:
              target_rank_observed = True
              for s in rank_rows:
                observed_r_values.add(s.registered_workers)
            elif is_withdrawn_arm:
              # For withdrawn arm, any staleness telemetry confirms recording
              if samples:
                target_rank_observed = True
                for s in samples:
                  if s.registered_workers > 0:
                    observed_r_values.add(s.registered_workers)
          except Exception as e:
            unverifiable_reasons.append(
              f"Unparseable staleness at {f_dir.name}: {e}")

    # 3. Check Authoritative R
    if observed_r_values:
      if len(observed_r_values) > 1:
        reasons.append(
            f"Inconsistent registeredWorkers observed across rows/forks: {observed_r_values}"
        )
      else:
        r_val = next(iter(observed_r_values))
        details["observed_r"] = r_val
        if require_valid_r and r_val not in VALID_AUTHORITATIVE_R:
          reasons.append(
              f"Observed registeredWorkers R={r_val} not in authoritative set {VALID_AUTHORITATIVE_R}"
          )
        if expected_topology_r is not None and r_val != expected_topology_r:
          reasons.append(
              f"Observed R={r_val} does not match expected topology R={expected_topology_r}"
          )
    else:
      if not is_withdrawn_arm and expected_k > 0:
        unverifiable_reasons.append(
          f"No rank-{expected_k} staleness observations found")

    # Evaluate final status
    if unverifiable_reasons:
      return ArtifactEligibility.UNVERIFIABLE, unverifiable_reasons + reasons, details
    if reasons:
      return ArtifactEligibility.INELIGIBLE, reasons, details
    return ArtifactEligibility.ELIGIBLE, [], details

  @classmethod
  def audit_pair(
      cls,
      pair: ManifestPair,
      manifest: Manifest,
      require_valid_r: bool = True,
      require_sidecars: bool = False,
  ) -> Tuple[ArtifactEligibility, List[str]]:
    """Audits a declared ManifestPair against the Manifest contract.

    Returns:
        Tuple of (eligibility: ArtifactEligibility, reasons: List[str]).
    """
    all_reasons: List[str] = []
    k_samples = pair.k_sample_paths if pair.k_sample_paths else [
      pair.k_run_path]
    k_minus_1_samples = (
      pair.k_minus_1_sample_paths if pair.k_minus_1_sample_paths else [
        pair.k_minus_1_run_path]
    )

    expected_r = 23 if "8p16e" in manifest.topology_id or "r23" in manifest.topology_id else (
      7 if "7p" in manifest.topology_id or "r7" in manifest.topology_id else None
    )

    # Audit Arm A (K)
    elig_a, reasons_a, det_a = cls.audit_arm(
        sample_paths=k_samples,
        expected_k=pair.K,
        expected_commit=manifest.runtime_commit,
        expected_actuator=manifest.cache_actuator_version,
        expected_park_ns=manifest.cache_park_ns,
        expected_topology_r=expected_r,
        require_valid_r=require_valid_r,
        require_sidecars=require_sidecars,
        is_withdrawn_arm=False,
    )

    # Audit Arm B (K-1)
    elig_b, reasons_b, det_b = cls.audit_arm(
        sample_paths=k_minus_1_samples,
        expected_k=pair.K - 1,
        expected_commit=manifest.runtime_commit,
        expected_actuator=manifest.cache_actuator_version,
        expected_park_ns=manifest.cache_park_ns,
        expected_topology_r=expected_r,
        require_valid_r=require_valid_r,
        require_sidecars=require_sidecars,
        is_withdrawn_arm=True,
    )

    if elig_a == ArtifactEligibility.UNVERIFIABLE or elig_b == ArtifactEligibility.UNVERIFIABLE:
      reasons = [f"[Arm A] {r}" for r in reasons_a] + [f"[Arm B] {r}" for r in
                                                       reasons_b]
      return ArtifactEligibility.UNVERIFIABLE, reasons

    if elig_a == ArtifactEligibility.INELIGIBLE or elig_b == ArtifactEligibility.INELIGIBLE:
      reasons = [f"[Arm A] {r}" for r in reasons_a] + [f"[Arm B] {r}" for r in
                                                       reasons_b]
      return ArtifactEligibility.INELIGIBLE, reasons

    return ArtifactEligibility.ELIGIBLE, []
