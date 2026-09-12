"""Focused unit and integration tests for artifact eligibility and multi-sample ingestion.

Validates:
- Three-state artifact eligibility (ELIGIBLE, INELIGIBLE, UNVERIFIABLE)
- Fail-closed behavior on INELIGIBLE or UNVERIFIABLE artifacts
- Balanced four-fork sample pooling across sample_0 and sample_1 (n=4)
- Authoritative registeredWorkers extraction and rejection of len(cpuSet) as R
- Inconsistent R detection across forks
- Fixed late-region joins for staleness telemetry
- Effective outcome and LabelEvidenceBasis persistence (WHOLE_AGREEMENT, LATE_CONVERGENCE, STABLE_TIE, NONE)
- Consumed checksums tracking across all four forks
- Fail-closed behavior on missing or corrupted SHA-256 sidecars
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Tuple
import numpy as np
import pytest

from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.eligibility import EligibilityAuditor, \
  EligibilityError
from pareto_weight_calibration.labels import LabelSynthesizer
from pareto_weight_calibration.loader import DataLoader
from pareto_weight_calibration.manifest import load_manifest
from pareto_weight_calibration.staleness import StalenessParseError, \
  StalenessParser
from pareto_weight_calibration.throughput import ThroughputParser
from pareto_weight_calibration.trajectory import TrajectoryAnalyzer
from pareto_weight_calibration.types import (
  ActiveStateFeatures,
  ArmPerformance,
  ArtifactEligibility,
  ForkThroughput,
  LabelEvidenceBasis,
  Manifest,
  ManifestPair,
  Outcome,
  TrajectoryStatus,
)
from tests.conftest import (
  generate_mock_staleness_tsv,
  generate_mock_trajectory_tsv,
  generate_mock_trial_config,
  write_with_checksum,
)


def create_four_fork_sample_fixture(
    base_dir: Path,
    k_cutoff: int,
    reg_workers: int = 23,
    cpu_set_count: int = 30,
    base_throughput: float = 50000.0,
    is_active: bool = True,
) -> Tuple[Path, Path]:
  """Creates sample_0_repeat_0 and sample_1_repeat_0, each with 2 JMH forks (total 4 forks)."""
  sample_0 = base_dir / f"arm_k{k_cutoff}__sample_0_repeat_0"
  sample_1 = base_dir / f"arm_k{k_cutoff}__sample_1_repeat_0"

  for sample_dir in (sample_0, sample_1):
    sample_dir.mkdir(parents=True, exist_ok=True)
    cfg = generate_mock_trial_config(k_cutoff=k_cutoff, cpu_count=cpu_set_count)
    write_with_checksum(sample_dir / "trial_config.json",
                        json.dumps(cfg, indent=2))

    # 2 forks per sample
    for fork_idx in range(2):
      fork_dir = sample_dir / f"fork-{sample_dir.name}-{fork_idx}"
      write_with_checksum(
          fork_dir / "trajectory_windows.tsv",
          generate_mock_trajectory_tsv(fork_count=1,
                                       base_throughput=base_throughput),
      )
      write_with_checksum(
          fork_dir / "contention_staleness.tsv",
          generate_mock_staleness_tsv(
              target_rank=k_cutoff,
              contention=500000,
              body_cost=150.0,
              prod_handles=12,
              reg_workers=reg_workers,
              is_active=is_active,
          ),
      )

  return (sample_0, sample_1)


def test_balanced_four_fork_sample_pooling(tmp_path: Path):
  """Verifies that sample_0 and sample_1 are discovered and pooled into exactly 4 independent forks."""
  k_s0, k_s1 = create_four_fork_sample_fixture(tmp_path, k_cutoff=8,
                                               base_throughput=50000.0,
                                               is_active=True)
  k_minus_1_s0, k_minus_1_s1 = create_four_fork_sample_fixture(
      tmp_path, k_cutoff=7, base_throughput=60000.0, is_active=False
  )

  manifest_data = {
    "schemaVersion": 1,
    "runtimeCommit": "testcommit",
    "cacheActuatorVersion": "cache-v1",
    "cacheParkNs": 15000,
    "topologyId": "mock-8p16e",
    "pairs": [
      {
        "pairId": "pair-four-fork-test",
        "kRunPath": str(k_s0),
        "kMinus1RunPath": str(k_minus_1_s0),
        "K": 8,
      }
    ],
  }
  manifest_path = tmp_path / "dataset_manifest.json"
  write_with_checksum(manifest_path, json.dumps(manifest_data, indent=2))

  manifest = load_manifest(manifest_path)
  pair_record = DataLoader.load_pair(
      manifest=manifest,
      pair_decl=manifest.pairs[0],
      strict_compatibility=True,
  )

  assert pair_record is not None
  # Verify exactly 4 independent forks pooled per arm
  assert pair_record.perf_k.fork_count == 4
  assert len(pair_record.perf_k.forks) == 4
  assert pair_record.perf_k_minus_1.fork_count == 4
  assert len(pair_record.perf_k_minus_1.forks) == 4

  # Verify sample paths
  assert len(pair_record.k_sample_paths) == 2
  assert len(pair_record.k_minus_1_sample_paths) == 2


def test_authoritative_r_extraction_and_cpu_set_rejection(tmp_path: Path):
  """Verifies that R is extracted from registeredWorkers in telemetry and not len(cpuSet)."""
  # Create arm with len(cpuSet) == 30, but registeredWorkers == 23
  k_s0, k_s1 = create_four_fork_sample_fixture(
      tmp_path, k_cutoff=8, reg_workers=23, cpu_set_count=30, is_active=True
  )

  # Extract features directly
  features = StalenessParser.extract_active_features(
      [k_s0, k_s1],
      target_rank=8,
  )

  # Authoritative R must be 23 (from registeredWorkers), NOT 30 (from cpuSet)
  assert features.R == 23
  assert features.R != 30
  assert features.K == 8

  # Check 8-term feature vector has -R == -23.0 and R*q == 23.0 * q
  vec = features.feature_vector
  assert np.isclose(vec[7], -23.0)
  assert np.isclose(vec[3], 23.0 * features.q)


def test_r15_surface_is_authoritative(tmp_path: Path):
  """Verifies that the intermediate 15-worker training topology is eligible."""
  k_s0, k_s1 = create_four_fork_sample_fixture(
      tmp_path, k_cutoff=8, reg_workers=15, cpu_set_count=15, is_active=True
  )
  k_minus_1_s0, k_minus_1_s1 = create_four_fork_sample_fixture(
      tmp_path, k_cutoff=7, reg_workers=15, cpu_set_count=15, is_active=False
  )
  manifest = Manifest(
      schema_version=1,
      runtime_commit="testcommit",
      cache_actuator_version="cache-v1",
      cache_park_ns=15000,
      topology_id="mock-r15",
      pairs=[],
  )
  pair = ManifestPair(
      pair_id="r15-authoritative",
      k_run_path=k_s0,
      k_minus_1_run_path=k_minus_1_s0,
      K=8,
      k_sample_paths=[k_s0, k_s1],
      k_minus_1_sample_paths=[k_minus_1_s0, k_minus_1_s1],
  )

  eligibility, reasons = EligibilityAuditor.audit_pair(
      pair, manifest, require_sidecars=True
  )

  assert eligibility == ArtifactEligibility.ELIGIBLE
  assert reasons == []


def test_inconsistent_r_across_forks_fails(tmp_path: Path):
  """Verifies that inconsistent registeredWorkers across forks causes fail-closed parse error."""
  s0 = tmp_path / "inconsistent_arm__sample_0_repeat_0"
  s0.mkdir(parents=True, exist_ok=True)
  cfg = generate_mock_trial_config(k_cutoff=8)
  write_with_checksum(s0 / "trial_config.json", json.dumps(cfg, indent=2))

  f0 = s0 / "fork-0"
  f1 = s0 / "fork-1"
  # Fork 0 has R=23, Fork 1 has R=7
  write_with_checksum(
      f0 / "contention_staleness.tsv",
      generate_mock_staleness_tsv(target_rank=8, reg_workers=23,
                                  is_active=True),
  )
  write_with_checksum(
      f1 / "contention_staleness.tsv",
      generate_mock_staleness_tsv(target_rank=8, reg_workers=7, is_active=True),
  )

  with pytest.raises(StalenessParseError,
                     match="Inconsistent registeredWorkers across forks"):
    StalenessParser.extract_active_features([s0], target_rank=8)


def test_late_region_joins_semantics(tmp_path: Path):
  """Verifies that active feature extraction joins only to the fixed late half of samples."""
  tsv_path = tmp_path / "contention_staleness.tsv"
  # Construct 10 samples: first 5 have low contention (100000), last 5 have high contention (900000)
  lines = [
    "iteration\tcore\tsegment\tsampleIndex\tcycleEpoch\tbatchEpoch\tmeasuredContention\tlastRawContention\tcontentionObservationCount\tlastContentionObservationNs\tcyclesSinceContentionObservation\tnanosSinceContentionObservation\tconsecutiveIdleDecisions\tidleDurationSelectedNs\tsuccessfulAcquisitionCount\tfailedAcquisitionCount\ttotalAcquisitionAttempts\texecutionPath\tlocalCacheCount\tproductiveHandleCount\tregisteredWorkers\tworkerRank\tproductivityExcluded\tproductivityExclusionCount\tproductivityThresholdNs\tsmoothedBodyCostNs\tbodyHistoryReady"
  ]
  for i in range(5):
    lines.append(
        f"0\t0\tsteadyState\t{i}\t100\t10\t100000\t100000\t50\t1000\t0\t100\t0\t1000\t500\t10\t1000\tSTAGED\t0\t10\t23\t8\t0\t0\t0\t100.0\t1"
    )
  for i in range(5, 10):
    lines.append(
        f"0\t0\tsteadyState\t{i}\t100\t10\t900000\t900000\t50\t1000\t0\t100\t0\t1000\t500\t10\t1000\tSTAGED\t0\t10\t23\t8\t0\t0\t0\t200.0\t1"
    )
  write_with_checksum(tsv_path, "\n".join(lines) + "\n")

  features = StalenessParser.extract_active_features(tmp_path, target_rank=8)
  # Late half (indices 5-9) has contention 900000 (0.9) and body cost 200.0
  assert np.isclose(features.c, 0.9)
  assert np.isclose(features.smoothed_body_cost_ns, 200.0)


def test_effective_outcome_and_label_evidence_basis_persistence(tmp_path: Path):
  """Verifies that effectiveOutcome, labelEvidenceBasis, and basis metrics are persisted."""
  k_s0, k_s1 = create_four_fork_sample_fixture(tmp_path, k_cutoff=8,
                                               base_throughput=50000.0,
                                               is_active=True)
  k_minus_1_s0, k_minus_1_s1 = create_four_fork_sample_fixture(
      tmp_path, k_cutoff=7, base_throughput=60000.0, is_active=False
  )

  manifest_data = {
    "schemaVersion": 1,
    "runtimeCommit": "testcommit",
    "cacheActuatorVersion": "cache-v1",
    "cacheParkNs": 15000,
    "topologyId": "mock-8p16e",
    "pairs": [
      {
        "pairId": "pair-evidence-basis-test",
        "kRunPath": str(k_s0),
        "kMinus1RunPath": str(k_minus_1_s0),
        "K": 8,
      }
    ],
  }
  manifest_path = tmp_path / "dataset_manifest.json"
  write_with_checksum(manifest_path, json.dumps(manifest_data, indent=2))

  manifest = load_manifest(manifest_path)
  record = DataLoader.load_pair(
      manifest=manifest,
      pair_decl=manifest.pairs[0],
      strict_compatibility=True,
  )

  assert record is not None
  assert record.effective_outcome == Outcome.K_MINUS_1_WINS
  assert record.label_evidence_basis == LabelEvidenceBasis.WHOLE_AGREEMENT
  assert record.y == 1.0
  assert record.pair_weight > 0.5

  # Check basis throughput values
  assert np.isclose(record.basis_throughput_k, record.perf_k.mean)
  assert np.isclose(record.basis_throughput_k_minus_1,
                    record.perf_k_minus_1.mean)
  assert np.isclose(record.basis_delta, record.delta)
  assert record.basis_uncertainty >= 0.0

  # Verify consumed checksums across all 4 forks of Arm A and Arm B
  assert len(record.artifact_checksums) > 0
  k_arm_checksums = [k for k in record.artifact_checksums if "k_arm" in k]
  k_minus_1_arm_checksums = [k for k in record.artifact_checksums if
                             "k_minus_1_arm" in k]
  assert len(k_arm_checksums) >= 4
  assert len(k_minus_1_arm_checksums) >= 4


def test_three_state_eligibility_classification(tmp_path: Path):
  """Verifies ELIGIBLE, INELIGIBLE, and UNVERIFIABLE classification."""
  # 1. Valid ELIGIBLE fixture
  k_s0, k_s1 = create_four_fork_sample_fixture(
      tmp_path / "eligible", k_cutoff=8, reg_workers=23, is_active=True
  )
  k_minus_1_s0, k_minus_1_s1 = create_four_fork_sample_fixture(
      tmp_path / "eligible", k_cutoff=7, reg_workers=23, is_active=False
  )
  manifest = Manifest(
      schema_version=1,
      runtime_commit="testcommit",
      cache_actuator_version="cache-v1",
      cache_park_ns=15000,
      topology_id="r23-s11-wu112-8p16e",
      pairs=[],
  )
  pair = ManifestPair(
      pair_id="eligible-pair",
      k_run_path=k_s0,
      k_minus_1_run_path=k_minus_1_s0,
      K=8,
      k_sample_paths=[k_s0, k_s1],
      k_minus_1_sample_paths=[k_minus_1_s0, k_minus_1_s1],
  )
  elig, reasons = EligibilityAuditor.audit_pair(pair, manifest)
  assert elig == ArtifactEligibility.ELIGIBLE
  assert len(reasons) == 0

  # 2. INELIGIBLE due to mismatched actuator version
  inelig_dir = tmp_path / "ineligible"
  k_inelig_s0, k_inelig_s1 = create_four_fork_sample_fixture(
      inelig_dir, k_cutoff=8, reg_workers=23, is_active=True
  )
  # Modify trial_config to have wrong actuator version
  bad_cfg = generate_mock_trial_config(k_cutoff=8,
                                       actuator_version="superseded-v0")
  write_with_checksum(k_inelig_s0 / "trial_config.json",
                      json.dumps(bad_cfg, indent=2))

  pair_inelig = ManifestPair(
      pair_id="ineligible-pair",
      k_run_path=k_inelig_s0,
      k_minus_1_run_path=k_minus_1_s0,
      K=8,
      k_sample_paths=[k_inelig_s0, k_inelig_s1],
      k_minus_1_sample_paths=[k_minus_1_s0, k_minus_1_s1],
  )
  elig_bad, reasons_bad = EligibilityAuditor.audit_pair(pair_inelig, manifest)
  assert elig_bad == ArtifactEligibility.INELIGIBLE
  assert any("Mismatched actuator" in r for r in reasons_bad)

  # 3. UNVERIFIABLE due to corrupted or missing sidecar
  unverif_dir = tmp_path / "unverifiable"
  k_unv_s0, k_unv_s1 = create_four_fork_sample_fixture(
      unverif_dir, k_cutoff=8, reg_workers=23, is_active=True
  )
  # Corrupt a trajectory sidecar
  sidecar = list(k_unv_s0.glob("fork-*/trajectory_windows.tsv.sha256"))[0]
  sidecar.write_text(
    "0000000000000000000000000000000000000000000000000000000000000000\n")

  pair_unv = ManifestPair(
      pair_id="unverifiable-pair",
      k_run_path=k_unv_s0,
      k_minus_1_run_path=k_minus_1_s0,
      K=8,
      k_sample_paths=[k_unv_s0, k_unv_s1],
      k_minus_1_sample_paths=[k_minus_1_s0, k_minus_1_s1],
  )
  elig_unv, reasons_unv = EligibilityAuditor.audit_pair(pair_unv, manifest,
                                                        require_sidecars=True)
  assert elig_unv == ArtifactEligibility.UNVERIFIABLE
  assert any("Checksum verification failed" in r for r in reasons_unv)

  # 4. Fail-closed behavior: DataLoader halts on INELIGIBLE or UNVERIFIABLE in strict mode
  manifest_unv = Manifest(
      schema_version=1,
      runtime_commit="testcommit",
      cache_actuator_version="cache-v1",
      cache_park_ns=15000,
      topology_id="r23-s11-wu112-8p16e",
      pairs=[pair_unv],
  )
  with pytest.raises(EligibilityError, match="UNVERIFIABLE"):
    DataLoader.load_pair(
        manifest=manifest_unv,
        pair_decl=pair_unv,
        strict_compatibility=True,
        require_sidecars=True,
    )
