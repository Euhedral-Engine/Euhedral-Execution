"""High-level dataset ingestion orchestrator and artifact join pipeline."""

from __future__ import annotations

import logging
import math
import re
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.config import CompatibilityAnalyzer, load_trial_config
from pareto_weight_calibration.eligibility import (
  EligibilityAuditor,
  EligibilityError,
)
from pareto_weight_calibration.labels import LabelSynthesizer
from pareto_weight_calibration.manifest import load_manifest
from pareto_weight_calibration.staleness import StalenessParser
from pareto_weight_calibration.throughput import ThroughputParser
from pareto_weight_calibration.trajectory import TrajectoryAnalyzer
from pareto_weight_calibration.types import (
  ArtifactEligibility,
    ForkThroughput,
    Manifest,
    ManifestPair,
    PairRecord,
)

logger = logging.getLogger(__name__)


class DataLoader:
    """Orchestrates artifact verification, joins, signal processing, and feature synthesis."""

    @classmethod
    def discover_arm_sample_paths(cls, primary_path: Path) -> List[Path]:
      """Discovers balanced sample positions (e.g. sample_0 and sample_1) for an arm directory."""
      if not primary_path.exists():
        return [primary_path]

      dir_name = primary_path.name
      parent_dir = primary_path.parent

      sample_paths: List[Path] = [primary_path]

      # Check for sample_0 / sample_1 pattern
      if "__sample_0" in dir_name:
        companion_name = dir_name.replace("__sample_0", "__sample_1")
        companion_path = parent_dir / companion_name
        if companion_path.exists() and companion_path.is_dir():
          sample_paths.append(companion_path)
      elif "__sample_1" in dir_name:
        companion_name = dir_name.replace("__sample_1", "__sample_0")
        companion_path = parent_dir / companion_name
        if companion_path.exists() and companion_path.is_dir():
          sample_paths.insert(0, companion_path)

      return sorted(sample_paths, key=lambda p: p.name)

    @classmethod
    def load_pair(
        cls,
        manifest: Manifest,
        pair_decl: ManifestPair,
        verify_checksums: bool = True,
        strict_compatibility: bool = True,
        require_sidecars: bool = False,
    ) -> Optional[PairRecord]:
        """Loads and joins all artifacts for an individual adjacent K vs K-1 pair.

        Args:
            manifest: Root dataset manifest.
            pair_decl: Pair declaration with run directories and candidate rank K.
            verify_checksums: Whether to verify .sha256 sidecars.
            strict_compatibility: Whether to halt on mismatched fixtures or ineligible artifacts.
            require_sidecars: Whether to require .sha256 sidecars for all consumed files.

        Returns:
            Fully populated PairRecord, or None if pair was rejected in non-strict mode.
        """
        # 1. Resolve balanced sample paths for Arm A and Arm B
        k_sample_paths = (
          pair_decl.k_sample_paths
          if pair_decl.k_sample_paths
          else cls.discover_arm_sample_paths(pair_decl.k_run_path)
        )
        k_minus_1_sample_paths = (
          pair_decl.k_minus_1_sample_paths
          if pair_decl.k_minus_1_sample_paths
          else cls.discover_arm_sample_paths(pair_decl.k_minus_1_run_path)
        )

        for p in k_sample_paths:
          if not p.exists() or not p.is_dir():
            raise FileNotFoundError(
                f"Arm A (K={pair_decl.K}) sample directory not found: {p}"
            )
        for p in k_minus_1_sample_paths:
          if not p.exists() or not p.is_dir():
            raise FileNotFoundError(
                f"Arm B (K-1={pair_decl.K - 1}) sample directory not found: {p}"
            )

        frozen = pair_decl.metadata.get("frozenStep4Evidence")
        if frozen is not None:
          declared_config_hashes = pair_decl.metadata.get("trialConfigSha256")
          if not isinstance(declared_config_hashes, dict):
            raise ValueError(
                f"Frozen training manifest pair {pair_decl.pair_id} lacks trialConfigSha256"
            )
          for arm_name, sample_paths in (
              ("k", k_sample_paths),
              ("kMinus1", k_minus_1_sample_paths),
          ):
            declared = declared_config_hashes.get(arm_name)
            if not isinstance(declared, list) or len(declared) != len(
                sample_paths):
              raise ValueError(
                  f"Frozen training manifest pair {pair_decl.pair_id} has invalid "
                  f"trialConfigSha256.{arm_name}"
              )
            for sample_path, expected_digest in zip(sample_paths, declared):
              config_file = sample_path / "trial_config.json"
              actual_digest = ChecksumVerifier.compute_sha256(config_file)
              if actual_digest != expected_digest:
                raise ValueError(
                    f"Frozen trial config changed for {pair_decl.pair_id}: "
                    f"{config_file} declared={expected_digest}, regenerated={actual_digest}"
                )

        # 2. Ingest trial configurations from primary sample
        config_k = load_trial_config(k_sample_paths[0])
        config_k_minus_1 = load_trial_config(k_minus_1_sample_paths[0])

        # 3. Strict compatibility check
        is_compatible, reasons = CompatibilityAnalyzer.check_compatibility(
            config_k, config_k_minus_1, pair_decl.K
        )
        if not is_compatible:
            msg = f"Pair {pair_decl.pair_id} failed fixture compatibility: {'; '.join(reasons)}"
            if strict_compatibility:
                raise ValueError(msg)
            logger.warning(msg)
            return None

        # 4. Verify actuator match with manifest
        cal_k = config_k.calibration_config
        if cal_k.cache_actuator_version != manifest.cache_actuator_version:
            msg = (
                f"Pair {pair_decl.pair_id} actuator version '{cal_k.cache_actuator_version}' "
                f"does not match manifest '{manifest.cache_actuator_version}'"
            )
            if strict_compatibility:
                raise ValueError(msg)
            return None

        if cal_k.cache_park_ns != manifest.cache_park_ns:
            msg = (
                f"Pair {pair_decl.pair_id} cacheParkNs {cal_k.cache_park_ns} "
                f"does not match manifest {manifest.cache_park_ns}"
            )
            if strict_compatibility:
                raise ValueError(msg)
            return None

        # 5. Three-state artifact eligibility check
        eligibility, elig_reasons = EligibilityAuditor.audit_pair(
            ManifestPair(
                pair_id=pair_decl.pair_id,
                k_run_path=pair_decl.k_run_path,
                k_minus_1_run_path=pair_decl.k_minus_1_run_path,
                K=pair_decl.K,
                k_sample_paths=k_sample_paths,
                k_minus_1_sample_paths=k_minus_1_sample_paths,
                metadata=pair_decl.metadata,
            ),
            manifest,
            require_valid_r=False if "mock" in manifest.topology_id else True,
            require_sidecars=require_sidecars,
        )

        if eligibility in (
            ArtifactEligibility.INELIGIBLE,
            ArtifactEligibility.UNVERIFIABLE,
        ):
          msg = f"Pair {pair_decl.pair_id} is {eligibility.value}: {'; '.join(elig_reasons)}"
          if strict_compatibility:
            raise EligibilityError(msg)
          logger.warning(msg)

        # 6. Trajectory analysis for Arm A and Arm B across all pooled forks
        forks_k, stable_k = TrajectoryAnalyzer.analyze_run_directory(
            k_sample_paths,
            verify_checksum=verify_checksums,
            require_sidecar=require_sidecars,
        )
        forks_k_minus_1, stable_k_minus_1 = TrajectoryAnalyzer.analyze_run_directory(
            k_minus_1_sample_paths,
            verify_checksum=verify_checksums,
            require_sidecar=require_sidecars,
        )

        if not forks_k:
          # Fallback only for non-authoritative / mock test paths
          if not strict_compatibility:
            log_k = k_sample_paths[0] / "benchmark_output.log"
            if log_k.exists():
              scores_k = ThroughputParser.parse_log_file(log_k)
              forks_k = [
                ForkThroughput(
                    fork_index=i,
                    mean_ops_per_sec=s,
                    window_scores=[s],
                    late_mean_ops_per_sec=s,
                    is_late_stable=True,
                )
                for i, s in enumerate(scores_k)
              ]
              stable_k = len(forks_k) > 0
          if not forks_k:
            raise FileNotFoundError(
                f"Missing or invalid trajectory windows for Arm A: {k_sample_paths}"
            )

        if not forks_k_minus_1:
          if not strict_compatibility:
            log_k_minus_1 = k_minus_1_sample_paths[0] / "benchmark_output.log"
            if log_k_minus_1.exists():
              scores_k_minus_1 = ThroughputParser.parse_log_file(log_k_minus_1)
              forks_k_minus_1 = [
                ForkThroughput(
                    fork_index=i,
                    mean_ops_per_sec=s,
                    window_scores=[s],
                    late_mean_ops_per_sec=s,
                    is_late_stable=True,
                )
                for i, s in enumerate(scores_k_minus_1)
              ]
              stable_k_minus_1 = len(forks_k_minus_1) > 0
          if not forks_k_minus_1:
            raise FileNotFoundError(
                f"Missing or invalid trajectory windows for Arm B: {k_minus_1_sample_paths}"
            )

        # 7. Compute pooled arm performance across all discovered forks
        perf_k = ThroughputParser.compute_arm_performance(forks_k)
        perf_k_minus_1 = ThroughputParser.compute_arm_performance(
            forks_k_minus_1
        )

        # 8. Extract authoritative active decision features from Arm A (rank K)
        features = StalenessParser.extract_active_features(
            k_sample_paths,
            target_rank=pair_decl.K,
            verify_checksum=verify_checksums,
            require_sidecar=require_sidecars,
        )

        # 9. Extract withdrawn diagnostics from Arm B (rank K)
        withdrawn = StalenessParser.extract_withdrawn_diagnostics(
            k_minus_1_sample_paths,
            target_rank=pair_decl.K,
            verify_checksum=verify_checksums,
            require_sidecar=require_sidecars,
        )

        # 10. Compare arms and synthesize labels, weights, and evidence basis
        whole_metrics = LabelSynthesizer.compare_arms(
            perf_k, perf_k_minus_1, use_late=False
        )
        late_metrics = LabelSynthesizer.compare_arms(
            perf_k, perf_k_minus_1, use_late=True
        )

        synthesis = LabelSynthesizer.synthesize_label_and_weight(
            whole_metrics=whole_metrics,
            late_metrics=late_metrics,
            arm_a_trajectory_eligible=stable_k,
            arm_b_trajectory_eligible=stable_k_minus_1,
            perf_a=perf_k,
            perf_b=perf_k_minus_1,
        )

        if frozen is not None:
          expected = {
            "effectiveOutcome": synthesis.effective_outcome.value,
            "labelEvidenceBasis": synthesis.label_evidence_basis.value,
            "y": synthesis.y,
            "pairWeight": synthesis.pair_weight,
            "basisThroughputK": synthesis.basis_throughput_k,
            "basisThroughputKMinus1": synthesis.basis_throughput_k_minus_1,
            "basisDelta": synthesis.basis_delta,
            "basisVarianceK": synthesis.basis_variance_k,
            "basisVarianceKMinus1": synthesis.basis_variance_k_minus_1,
            "basisUncertainty": synthesis.basis_uncertainty,
          }
          for key, actual in expected.items():
            if key not in frozen:
              raise ValueError(
                  f"Frozen training manifest pair {pair_decl.pair_id} lacks {key}"
              )
            declared = frozen[key]
            if isinstance(actual, float):
              if not math.isclose(float(declared), actual, rel_tol=0.0,
                                  abs_tol=1e-12):
                raise ValueError(
                    f"Frozen Step 4 evidence changed for {pair_decl.pair_id}: "
                    f"{key} declared={declared!r}, regenerated={actual!r}"
                )
            elif declared != actual:
              raise ValueError(
                  f"Frozen Step 4 evidence changed for {pair_decl.pair_id}: "
                  f"{key} declared={declared!r}, regenerated={actual!r}"
              )

        # 11. Collect verified artifact checksums across all consumed forks
        checksums: Dict[str, str] = {}
        for idx, sp in enumerate(k_sample_paths):
          config_file = sp / "trial_config.json"
          checksums[f"k_arm_sample_{idx}_trial_config_sha256"] = (
            ChecksumVerifier.compute_sha256(config_file)
          )
          stale_files = StalenessParser.discover_staleness_files(sp)
          for s_file in stale_files:
            fork_tag = (
              f"k_arm_sample_{idx}_{s_file.parent.name}_staleness_sha256"
            )
            checksums[fork_tag] = ChecksumVerifier.compute_sha256(s_file)
          traj_files = TrajectoryAnalyzer.discover_trajectory_files(sp)
          for t_file in traj_files:
            fork_tag = (
              f"k_arm_sample_{idx}_{t_file.parent.name}_trajectory_sha256"
            )
            checksums[fork_tag] = ChecksumVerifier.compute_sha256(t_file)

        for idx, sp in enumerate(k_minus_1_sample_paths):
          config_file = sp / "trial_config.json"
          checksums[f"k_minus_1_arm_sample_{idx}_trial_config_sha256"] = (
            ChecksumVerifier.compute_sha256(config_file)
          )
          stale_files = StalenessParser.discover_staleness_files(sp)
          for s_file in stale_files:
            fork_tag = f"k_minus_1_arm_sample_{idx}_{s_file.parent.name}_staleness_sha256"
            checksums[fork_tag] = ChecksumVerifier.compute_sha256(s_file)
          traj_files = TrajectoryAnalyzer.discover_trajectory_files(sp)
          for t_file in traj_files:
            fork_tag = f"k_minus_1_arm_sample_{idx}_{t_file.parent.name}_trajectory_sha256"
            checksums[fork_tag] = ChecksumVerifier.compute_sha256(t_file)

        # Maintain legacy keys for backwards compatibility with pairs.tsv export
        if checksums:
          first_k_stale = next(
              (
                v
                for k, v in checksums.items()
                if "k_arm" in k and "staleness" in k
              ),
              "",
          )
          first_b_stale = next(
              (
                v
                for k, v in checksums.items()
                if "k_minus_1" in k and "staleness" in k
              ),
              "",
          )
          checksums["k_run_staleness_sha256"] = first_k_stale
          checksums["k_minus_1_run_staleness_sha256"] = first_b_stale

        return PairRecord(
            pair_id=pair_decl.pair_id,
            topology_id=manifest.topology_id,
            runtime_commit=manifest.runtime_commit,
            cache_actuator_version=manifest.cache_actuator_version,
            cache_park_ns=manifest.cache_park_ns,
            K=pair_decl.K,
            registered_workers=features.R,
            work_units=cal_k.work_units,
            parallel_sources=cal_k.parallel_sources + cal_k.ordered_sources,
            features=features,
            withdrawn_diagnostics=withdrawn,
            perf_k=perf_k,
            perf_k_minus_1=perf_k_minus_1,
            delta=whole_metrics.delta,
            rel_delta_percent=whole_metrics.rel_delta_percent,
            uncertainty=whole_metrics.uncertainty,
            practical_margin=whole_metrics.practical_margin,
            governing_margin=whole_metrics.governing_margin,
            whole_outcome=whole_metrics.outcome,
            late_outcome=late_metrics.outcome,
            effective_outcome=synthesis.effective_outcome,
            label_evidence_basis=synthesis.label_evidence_basis,
            trajectory_status=synthesis.trajectory_status,
            y=synthesis.y,
            pair_weight=synthesis.pair_weight,
            basis_throughput_k=synthesis.basis_throughput_k,
            basis_throughput_k_minus_1=synthesis.basis_throughput_k_minus_1,
            basis_delta=synthesis.basis_delta,
            basis_variance_k=synthesis.basis_variance_k,
            basis_variance_k_minus_1=synthesis.basis_variance_k_minus_1,
            basis_uncertainty=synthesis.basis_uncertainty,
            eligibility=eligibility,
            k_run_path=pair_decl.k_run_path,
            k_minus_1_run_path=pair_decl.k_minus_1_run_path,
            k_sample_paths=k_sample_paths,
            k_minus_1_sample_paths=k_minus_1_sample_paths,
            artifact_checksums=checksums,
        )

    @classmethod
    def load_dataset(
        cls,
        manifest_path: Path,
        verify_checksums: bool = True,
        min_weight: float = 0.0,
        strict_compatibility: bool = True,
        require_sidecars: bool = False,
    ) -> List[PairRecord]:
      """Loads and joins all pairs defined in a dataset manifest.

      Args:
          manifest_path: Path to dataset_manifest.json.
          verify_checksums: Whether to verify artifact .sha256 sidecars.
          min_weight: Minimum pair_weight threshold to include record.
          strict_compatibility: Whether to halt on invalid fixtures or ineligible artifacts.
          require_sidecars: Whether to require .sha256 sidecars for all files.

      Returns:
          List of verified PairRecords.
      """
      manifest = load_manifest(manifest_path, verify_checksum=verify_checksums)
        records: List[PairRecord] = []

        for pair_decl in manifest.pairs:
          rec = cls.load_pair(
                manifest=manifest,
                pair_decl=pair_decl,
                verify_checksums=verify_checksums,
                strict_compatibility=strict_compatibility,
                require_sidecars=require_sidecars,
            )
          if rec is not None:
            if rec.pair_weight >= min_weight:
              records.append(rec)

        return records
