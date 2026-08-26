"""Pytest configuration and synthetic mock fixture generators."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Dict, List
import pytest


def write_with_checksum(file_path: Path, content: str) -> None:
    """Helper to write a file and its .sha256 sidecar."""
    file_path.parent.mkdir(parents=True, exist_ok=True)
    file_path.write_text(content, encoding="utf-8")
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
    sidecar_path = file_path.parent / (file_path.name + ".sha256")
    sidecar_path.write_text(f"{digest}\n", encoding="utf-8")


def generate_mock_trial_config(
    k_cutoff: int,
    cpu_count: int = 8,
    lifecycle: str = "CONTINUOUS",
    actuator_version: str = "cache-v1",
    park_ns: int = 15000,
    work_units: int = 16,
) -> Dict[str, Any]:
    """Generates synthetic trial_config.json dict."""
    return {
        "id": f"mock-trial-k{k_cutoff}",
        "name": f"Mock Trial K={k_cutoff}",
        "group": "calibration-test",
        "forks": 2,
        "warmups": 2,
        "iterations": 6,
        "warmupTime": "1s",
        "measurementTime": "2s",
        "jvmArgs": ["-Xms4g", "-Xmx4g"],
        "calibrationConfig": {
            "cpuSet": list(range(cpu_count)),
            "parallelSources": 4,
            "orderedSources": 0,
            "workUnits": work_units,
            "randomizeWork": True,
            "totalRequiredExecutions": 100000,
            "invocationTimeoutMillis": 60000,
            "rawSampleLimit": 1024,
            "observeCycleStart": True,
            "observeBatchProgress": True,
            "observeBatchComplete": True,
            "observeRawBodyCost": True,
            "observeIdleDecision": True,
            "observeExecDecision": True,
            "observeContentionStaleness": True,
            "forcedActiveParticipantCount": k_cutoff,
            "cacheParkNs": park_ns,
            "cacheActuatorVersion": actuator_version,
            "lifecycleMode": lifecycle,
        },
    }


def generate_mock_trajectory_tsv(
    fork_count: int = 2,
    windows_per_fork: int = 8,
    base_throughput: float = 50000.0,
    cv_noise: float = 0.01,
) -> str:
    """Generates synthetic trajectory_windows.tsv content."""
    lines = [
        "jvmId\tlifecycleMode\twindowIndex\ttrajectoryElapsedNs\twindowElapsedNs\tcompletedExecutions\tthroughputExecutionsPerSecond\tcontinuouslyFed\tdominantDecisionType\tdominantState\tdominantContentionBand\tdominantBodyBand\tdominantStateProbability\tcontentionCentroid\tbodyCentroid\tsuccessfulAcquisitions\tfailedAcquisitions\tacquisitionSuccessRatio\tidleSelectedFraction\tordinaryIdleSelectedFraction\tproductivityExclusions\tproductivityExcludedFraction\tproductiveHandleRatio"
    ]
    for fork in range(fork_count):
        jvm_id = f"jvm-fork-{fork}"
        for win in range(windows_per_fork):
            noise = (win % 3 - 1) * cv_noise * base_throughput
            thrpt = base_throughput + noise
            lines.append(
                f"{jvm_id}\tCONTINUOUS\t{win}\t{win*2000000000}\t2000000000\t100000\t{thrpt:.2f}\ttrue\texec\t1\t1\t1\t0.9\t0.5\t0.5\t1000\t10\t0.99\t0.0\t0.0\t0\t0.0\t0.8"
            )
    return "\n".join(lines) + "\n"


def generate_mock_staleness_tsv(
    target_rank: int,
    contention: int = 500000,
    body_cost: float = 120.0,
    prod_handles: int = 12,
    reg_workers: int = 8,
    is_active: bool = True,
) -> str:
    """Generates synthetic contention_staleness.tsv content."""
    lines = [
        "iteration\tcore\tsegment\tsampleIndex\tcycleEpoch\tbatchEpoch\tmeasuredContention\tlastRawContention\tcontentionObservationCount\tlastContentionObservationNs\tcyclesSinceContentionObservation\tnanosSinceContentionObservation\tconsecutiveIdleDecisions\tidleDurationSelectedNs\tsuccessfulAcquisitionCount\tfailedAcquisitionCount\ttotalAcquisitionAttempts\texecutionPath\tlocalCacheCount\tproductiveHandleCount\tregisteredWorkers\tworkerRank\tproductivityExcluded\tproductivityExclusionCount\tproductivityThresholdNs\tsmoothedBodyCostNs\tbodyHistoryReady"
    ]
    exec_path = "STAGED" if is_active else "CACHE"
    attempts = 1000 if is_active else 0
    cache_count = 0 if is_active else 5

    for iteration in range(2):
        for sample_idx in range(10):
            lines.append(
                f"{iteration}\t{target_rank-1}\tsteadyState\t{sample_idx}\t100\t10\t{contention}\t{contention}\t50\t1000\t0\t100\t0\t1000\t500\t10\t{attempts}\t{exec_path}\t{cache_count}\t{prod_handles}\t{reg_workers}\t{target_rank}\t0\t0\t0\t{body_cost:.2f}\t1"
            )
    return "\n".join(lines) + "\n"


@pytest.fixture
def mock_run_pair(tmp_path: Path) -> Dict[str, Any]:
    """Creates a complete synthetic adjacent pair fixture under tmp_path."""
    k_dir = tmp_path / "run_k8"
    k_minus_1_dir = tmp_path / "run_k7"

    k_config = generate_mock_trial_config(k_cutoff=8, cpu_count=8)
    k_minus_1_config = generate_mock_trial_config(k_cutoff=7, cpu_count=8)

    write_with_checksum(k_dir / "trial_config.json", json.dumps(k_config, indent=2))
    write_with_checksum(k_minus_1_dir / "trial_config.json", json.dumps(k_minus_1_config, indent=2))

    # Trajectory windows: Arm B achieves higher throughput (e.g. 60000 vs 50000)
    write_with_checksum(k_dir / "trajectory_windows.tsv", generate_mock_trajectory_tsv(base_throughput=50000.0))
    write_with_checksum(k_minus_1_dir / "trajectory_windows.tsv", generate_mock_trajectory_tsv(base_throughput=60000.0))

    # Staleness logs
    write_with_checksum(
        k_dir / "contention_staleness.tsv",
        generate_mock_staleness_tsv(target_rank=8, contention=600000, body_cost=150.0, prod_handles=12, is_active=True),
    )
    write_with_checksum(
        k_minus_1_dir / "contention_staleness.tsv",
        generate_mock_staleness_tsv(target_rank=8, contention=600000, body_cost=150.0, prod_handles=12, is_active=False),
    )

    manifest_data = {
        "schemaVersion": 1,
        "runtimeCommit": "testcommit123",
        "cacheActuatorVersion": "cache-v1",
        "cacheParkNs": 15000,
        "topologyId": "mock-topology",
        "pairs": [
            {
                "pairId": "pair-k8-k7-mock",
                "kRunPath": str(k_dir),
                "kMinus1RunPath": str(k_minus_1_dir),
                "K": 8,
                "metadata": {"workUnits": 16},
            }
        ],
    }

    manifest_path = tmp_path / "dataset_manifest.json"
    write_with_checksum(manifest_path, json.dumps(manifest_data, indent=2))

    return {
        "manifest_path": manifest_path,
        "k_dir": k_dir,
        "k_minus_1_dir": k_minus_1_dir,
        "K": 8,
    }
