"""High-level dataset ingestion orchestrator and artifact join pipeline."""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Dict, List, Optional

from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.config import CompatibilityAnalyzer, load_trial_config
from pareto_weight_calibration.labels import LabelSynthesizer
from pareto_weight_calibration.manifest import load_manifest
from pareto_weight_calibration.staleness import StalenessParser
from pareto_weight_calibration.throughput import ThroughputParser
from pareto_weight_calibration.trajectory import TrajectoryAnalyzer
from pareto_weight_calibration.types import (
    ForkThroughput,
    Manifest,
    ManifestPair,
    PairRecord,
)

logger = logging.getLogger(__name__)


class DataLoader:
    """Orchestrates artifact verification, joins, signal processing, and feature synthesis."""

    @classmethod
    def load_pair(
        cls,
        manifest: Manifest,
        pair_decl: ManifestPair,
        verify_checksums: bool = True,
        strict_compatibility: bool = True,
    ) -> Optional[PairRecord]:
        """Loads and joins all artifacts for an individual adjacent K vs K-1 pair.

        Args:
            manifest: Root dataset manifest.
            pair_decl: Pair declaration with run directories and candidate rank K.
            verify_checksums: Whether to verify .sha256 sidecars.
            strict_compatibility: Whether to reject pairs with mismatched fixtures.

        Returns:
            Fully populated PairRecord, or None if pair was rejected due to incompatibility.
        """
        k_dir = pair_decl.k_run_path
        k_minus_1_dir = pair_decl.k_minus_1_run_path

        if not k_dir.exists() or not k_dir.is_dir():
            raise FileNotFoundError(f"Arm A (K={pair_decl.K}) run directory not found: {k_dir}")
        if not k_minus_1_dir.exists() or not k_minus_1_dir.is_dir():
            raise FileNotFoundError(
                f"Arm B (K-1={pair_decl.K-1}) run directory not found: {k_minus_1_dir}"
            )

        # 1. Ingest trial configurations
        config_k = load_trial_config(k_dir)
        config_k_minus_1 = load_trial_config(k_minus_1_dir)

        # 2. Strict compatibility check
        is_compatible, reasons = CompatibilityAnalyzer.check_compatibility(
            config_k, config_k_minus_1, pair_decl.K
        )
        if not is_compatible:
            msg = f"Pair {pair_decl.pair_id} failed fixture compatibility: {'; '.join(reasons)}"
            if strict_compatibility:
                raise ValueError(msg)
            logger.warning(msg)
            return None

        # 3. Verify actuator match with manifest
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

        # 4. Trajectory analysis for Arm A and Arm B
        forks_k, stable_k = TrajectoryAnalyzer.analyze_run_directory(
            k_dir, verify_checksum=verify_checksums
        )
        forks_k_minus_1, stable_k_minus_1 = TrajectoryAnalyzer.analyze_run_directory(
            k_minus_1_dir, verify_checksum=verify_checksums
        )

        # Fallback to benchmark log if trajectory TSV had no windows
        if not forks_k:
            log_k = k_dir / "benchmark_output.log"
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

        if not forks_k_minus_1:
            log_k_minus_1 = k_minus_1_dir / "benchmark_output.log"
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

        # Compute arm performances
        late_means_k = [f.late_mean_ops_per_sec for f in forks_k]
        late_means_k_minus_1 = [f.late_mean_ops_per_sec for f in forks_k_minus_1]

        perf_k = ThroughputParser.compute_arm_performance(forks_k, late_means=late_means_k)
        perf_k_minus_1 = ThroughputParser.compute_arm_performance(
            forks_k_minus_1, late_means=late_means_k_minus_1
        )

        # 5. Extract active decision features from Arm A (rank K)
        features = StalenessParser.extract_active_features(
            k_dir, target_rank=pair_decl.K, verify_checksum=verify_checksums
        )

        # 6. Extract withdrawn diagnostics from Arm B (rank K)
        withdrawn = StalenessParser.extract_withdrawn_diagnostics(
            k_minus_1_dir, target_rank=pair_decl.K, verify_checksum=verify_checksums
        )

        # 7. Compare arms and synthesize labels & weights
        whole_metrics = LabelSynthesizer.compare_arms(perf_k, perf_k_minus_1, use_late=False)
        late_metrics = LabelSynthesizer.compare_arms(perf_k, perf_k_minus_1, use_late=True)

        effective_outcome, traj_status, y, pair_weight = LabelSynthesizer.synthesize_label_and_weight(
            whole_metrics, late_metrics, stable_k, stable_k_minus_1
        )

        # 8. Collect verified artifact checksums for provenance
        checksums: Dict[str, str] = {}
        staleness_files_k = StalenessParser.discover_staleness_files(k_dir)
        if staleness_files_k:
            checksums["k_run_staleness_sha256"] = ChecksumVerifier.compute_sha256(
                staleness_files_k[0]
            )

        staleness_files_b = StalenessParser.discover_staleness_files(k_minus_1_dir)
        if staleness_files_b:
            checksums["k_minus_1_run_staleness_sha256"] = ChecksumVerifier.compute_sha256(
                staleness_files_b[0]
            )

        return PairRecord(
            pair_id=pair_decl.pair_id,
            topology_id=manifest.topology_id,
            runtime_commit=manifest.runtime_commit,
            cache_actuator_version=manifest.cache_actuator_version,
            cache_park_ns=manifest.cache_park_ns,
            K=pair_decl.K,
            registered_workers=len(cal_k.cpu_set),
            work_units=cal_k.work_units,
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
            trajectory_status=traj_status,
            y=y,
            pair_weight=pair_weight,
            k_run_path=k_dir,
            k_minus_1_run_path=k_minus_1_dir,
            artifact_checksums=checksums,
        )

    @classmethod
    def load_dataset(
        cls,
        manifest_path: Path,
        verify_checksums: bool = True,
        min_weight: float = 0.0,
        strict_compatibility: bool = True,
    ) -> List[PairRecord]:
        """Loads and processes all pairs in a dataset manifest.

        Args:
            manifest_path: Path to dataset_manifest.json.
            verify_checksums: Whether to enforce SHA-256 sidecars.
            min_weight: Filter out pairs with pair_weight below this threshold.
            strict_compatibility: Reject mismatched arms.

        Returns:
            List of validated PairRecord objects.
        """
        manifest = load_manifest(manifest_path)
        records: List[PairRecord] = []

        for pair_decl in manifest.pairs:
            record = cls.load_pair(
                manifest=manifest,
                pair_decl=pair_decl,
                verify_checksums=verify_checksums,
                strict_compatibility=strict_compatibility,
            )
            if record is not None and record.pair_weight >= min_weight:
                records.append(record)

        return records
