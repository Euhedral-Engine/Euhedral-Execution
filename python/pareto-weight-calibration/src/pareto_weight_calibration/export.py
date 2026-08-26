"""Export joined PairRecord datasets to canonical TSV format with SHA-256 sidecars."""

from __future__ import annotations

import csv
from pathlib import Path
from typing import List

from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.types import PairRecord

PAIRS_TSV_COLUMNS = [
    "pairId",
    "runtimeCommit",
    "topologyId",
    "lifecycleMode",
    "cacheActuatorVersion",
    "cacheParkNs",
    "K",
    "registeredWorkers",
    "workUnits",
    "c_active",
    "smoothedBodyCostNs_active",
    "b_active",
    "P_active",
    "c_withdrawn",
    "P_withdrawn",
    "meanThroughput_K",
    "variance_K",
    "cv_K",
    "forkCount_K",
    "meanThroughput_KMinus1",
    "variance_KMinus1",
    "cv_KMinus1",
    "forkCount_KMinus1",
    "deltaThroughput",
    "relativeDeltaPercent",
    "governingMargin",
    "wholeRunOutcome",
    "lateRegionOutcome",
    "trajectoryStatus",
    "y",
    "pairWeight",
    "kRunPath",
    "kRunSha256",
    "kMinus1RunPath",
    "kMinus1RunSha256",
]


def export_pairs_tsv(pairs: List[PairRecord], output_path: Path) -> None:
    """Exports a list of PairRecord instances to a deterministic TSV and generates .sha256 sidecar.

    Args:
        pairs: List of joined PairRecord instances.
        output_path: Target TSV path (e.g. data/calibration/pairs.tsv).
    """
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(output_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f, delimiter="\t", lineterminator="\n")
        writer.writerow(PAIRS_TSV_COLUMNS)

        for p in pairs:
            writer.writerow([
                p.pair_id,
                p.runtime_commit,
                p.topology_id,
                "CONTINUOUS",
                p.cache_actuator_version,
                p.cache_park_ns,
                p.K,
                p.registered_workers,
                p.work_units,
                f"{p.features.c:.6f}",
                f"{p.features.smoothed_body_cost_ns:.3f}",
                f"{p.features.b:.6f}",
                f"{p.features.P:.3f}",
                f"{p.withdrawn_diagnostics.c_stale:.6f}",
                f"{p.withdrawn_diagnostics.P_stale:.3f}",
                f"{p.perf_k.mean:.3f}",
                f"{p.perf_k.variance:.3f}",
                f"{p.perf_k.cv:.6f}",
                p.perf_k.fork_count,
                f"{p.perf_k_minus_1.mean:.3f}",
                f"{p.perf_k_minus_1.variance:.3f}",
                f"{p.perf_k_minus_1.cv:.6f}",
                p.perf_k_minus_1.fork_count,
                f"{p.delta:.3f}",
                f"{p.rel_delta_percent:.3f}",
                f"{p.governing_margin:.3f}",
                p.whole_outcome.value,
                p.late_outcome.value,
                p.trajectory_status.value,
                f"{p.y:.1f}",
                f"{p.pair_weight:.6f}",
                str(p.k_run_path),
                p.artifact_checksums.get("k_run_staleness_sha256", ""),
                str(p.k_minus_1_run_path),
                p.artifact_checksums.get("k_minus_1_run_staleness_sha256", ""),
            ])

    # Compute and write SHA-256 sidecar
    digest = ChecksumVerifier.compute_sha256(output_path)
    sidecar_path = output_path.parent / (output_path.name + ".sha256")
    sidecar_path.write_text(f"{digest}\n", encoding="utf-8")
