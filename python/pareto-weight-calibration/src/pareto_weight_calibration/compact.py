"""Strict reconstruction of retained Step 4 rows after raw artifacts were removed."""

from __future__ import annotations

import csv
import math
from pathlib import Path
import re

from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.pipeline import load_frozen_training_manifest
from pareto_weight_calibration.types import (
  ActiveStateFeatures,
  ArmPerformance,
  ArtifactEligibility,
  LabelEvidenceBasis,
  Outcome,
  PairRecord,
  TrajectoryStatus,
  WithdrawnDiagnosticState,
)

_FAMILY_PATTERN = re.compile(r"Fam_R(?P<r>\d+)_S(?P<s>\d+)_WU(?P<wu>\d+)")


def load_compact_frozen_records(pairs_path: Path, manifest_path: Path) -> list[
  PairRecord]:
  """Loads checksum-validated compact evidence without reconstructing missing raw data."""
  ChecksumVerifier.verify_file(pairs_path, require_sidecar=True)
  manifest = load_frozen_training_manifest(manifest_path)
  declarations = {pair.pair_id: pair for pair in manifest.pairs}

  with pairs_path.open("r", encoding="utf-8", newline="") as stream:
    rows = list(csv.DictReader(stream, delimiter="\t"))
  if not rows:
    raise ValueError("Compact Step 4 evidence contains no rows")
  row_ids = [row["pairId"] for row in rows]
  if len(row_ids) != len(set(row_ids)):
    raise ValueError("Compact Step 4 evidence contains duplicate pairId values")
  if set(row_ids) != set(declarations):
    raise ValueError(
      "Compact Step 4 evidence does not exactly match its frozen manifest")

  records: list[PairRecord] = []
  for row in rows:
    pair_id = row["pairId"]
    declaration = declarations[pair_id]
    evidence = declaration.metadata["frozenStep4Evidence"]
    family_match = _FAMILY_PATTERN.fullmatch(row["familyId"])
    if family_match is None:
      raise ValueError(
        f"{pair_id}: invalid compact familyId {row['familyId']!r}")

    outcome = Outcome(row["effectiveOutcome"])
    basis = LabelEvidenceBasis(row["labelEvidenceBasis"])
    numeric_matches = {
      "y": float(row["y"]),
      "pairWeight": float(row["pairWeight"]),
      "basisThroughputK": float(row["basisThroughputK"]),
      "basisThroughputKMinus1": float(row["basisThroughputKMinus1"]),
      "basisDelta": float(row["basisDelta"]),
      "basisUncertainty": float(row["basisUncertainty"]),
    }
    for field_name, compact_value in numeric_matches.items():
      frozen_value = float(evidence[field_name])
      tolerance = max(1e-6, abs(frozen_value) * 1e-6)
      if not math.isclose(compact_value, frozen_value, rel_tol=1e-6,
                          abs_tol=tolerance):
        raise ValueError(
          f"{pair_id}: compact {field_name} differs from frozen evidence")
    if outcome.value != evidence["effectiveOutcome"] or basis.value != evidence[
      "labelEvidenceBasis"]:
      raise ValueError(f"{pair_id}: compact label differs from frozen evidence")

    mean_k = float(evidence["basisThroughputK"])
    mean_k_minus_1 = float(evidence["basisThroughputKMinus1"])
    variance_k = float(evidence["basisVarianceK"])
    variance_k_minus_1 = float(evidence["basisVarianceKMinus1"])
    delta = float(evidence["basisDelta"])
    uncertainty = float(evidence["basisUncertainty"])
    perf_k = ArmPerformance(
        mean=mean_k,
        variance=variance_k,
        std_dev=math.sqrt(variance_k),
        cv=math.sqrt(variance_k) / mean_k,
        fork_count=4,
        late_mean=mean_k,
        late_variance=variance_k,
        late_cv=math.sqrt(variance_k) / mean_k,
    )
    perf_k_minus_1 = ArmPerformance(
        mean=mean_k_minus_1,
        variance=variance_k_minus_1,
        std_dev=math.sqrt(variance_k_minus_1),
        cv=math.sqrt(variance_k_minus_1) / mean_k_minus_1,
        fork_count=4,
        late_mean=mean_k_minus_1,
        late_variance=variance_k_minus_1,
        late_cv=math.sqrt(variance_k_minus_1) / mean_k_minus_1,
    )
    features = ActiveStateFeatures(
        c=float(row["c"]),
        smoothed_body_cost_ns=float(row["smoothedBodyCostNs"]),
        b=float(row["b"]),
        P=float(row["P"]),
        R=int(row["registeredWorkers"]),
        K=int(row["K"]),
    )
    practical_margin = 0.01 * max(mean_k, mean_k_minus_1)
    records.append(
        PairRecord(
            pair_id=pair_id,
            topology_id=manifest.topology_id,
            runtime_commit=manifest.runtime_commit,
            cache_actuator_version=manifest.cache_actuator_version,
            cache_park_ns=manifest.cache_park_ns,
            K=features.K,
            registered_workers=features.R,
            work_units=int(family_match.group("wu")),
            parallel_sources=int(family_match.group("s")),
            features=features,
            withdrawn_diagnostics=WithdrawnDiagnosticState(
                c_stale=0.0,
                P_stale=0.0,
                local_cache_count=0,
                execution_path="UNKNOWN_COMPACT_EVIDENCE",
                acquisitions_attempted=0,
            ),
            perf_k=perf_k,
            perf_k_minus_1=perf_k_minus_1,
            delta=delta,
            rel_delta_percent=100.0 * delta / mean_k,
            uncertainty=uncertainty,
            practical_margin=practical_margin,
            governing_margin=uncertainty,
            whole_outcome=outcome,
            late_outcome=outcome,
            trajectory_status=TrajectoryStatus.STABLE_AGREEMENT,
            effective_outcome=outcome,
            label_evidence_basis=basis,
            y=float(evidence["y"]),
            pair_weight=float(evidence["pairWeight"]),
            basis_throughput_k=mean_k,
            basis_throughput_k_minus_1=mean_k_minus_1,
            basis_delta=delta,
            basis_variance_k=variance_k,
            basis_variance_k_minus_1=variance_k_minus_1,
            basis_uncertainty=uncertainty,
            eligibility=ArtifactEligibility.ELIGIBLE,
            k_run_path=declaration.k_run_path,
            k_minus_1_run_path=declaration.k_minus_1_run_path,
            k_sample_paths=declaration.k_sample_paths,
            k_minus_1_sample_paths=declaration.k_minus_1_sample_paths,
            artifact_checksums={
              "compact_pairs_sha256": ChecksumVerifier.compute_sha256(
                pairs_path),
              "frozen_manifest_sha256": ChecksumVerifier.compute_sha256(
                manifest_path),
            },
        )
    )
  return records
