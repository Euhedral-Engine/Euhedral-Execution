#!/usr/bin/env python3
"""Freeze the completed Pareto surface into authoritative Step 5 training rows."""

from __future__ import annotations

import csv
from concurrent.futures import ProcessPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.eligibility import EligibilityError
from pareto_weight_calibration.loader import DataLoader
from pareto_weight_calibration.manifest import save_manifest
from pareto_weight_calibration.staleness import StalenessParseError
from pareto_weight_calibration.types import (
  ArtifactEligibility,
  LabelEvidenceBasis,
  Manifest,
  ManifestPair,
  Outcome,
)

BASE_DIR = Path(__file__).resolve().parents[1]
EXPERIMENT_ROOTS = (
  BASE_DIR / "experiments/36-pareto-training-surface-r7",
  BASE_DIR / "experiments/37-pareto-training-surface-r15",
  BASE_DIR / "experiments/38-pareto-training-surface-r23",
)
TOPOLOGY_ID = "linux-x86_64-i9-14900k-pareto-surface"


def _write_checksum(path: Path) -> None:
  digest = hashlib.sha256(path.read_bytes()).hexdigest()
  path.with_name(path.name + ".sha256").write_text(digest + "\n",
                                                   encoding="utf-8")


def _runtime_commit() -> str:
  return subprocess.check_output(
      ["git", "rev-parse", "HEAD"], cwd=BASE_DIR, text=True
  ).strip()


def _load_cutoff(path: Path) -> int:
  payload = json.loads((path / "trial_config.json").read_text(encoding="utf-8"))
  return int(payload["calibrationConfig"]["forcedActiveParticipantCount"])


def discover_pairs(runtime_commit: str) -> Manifest:
  pairs: list[ManifestPair] = []
  for root in EXPERIMENT_ROOTS:
    comparison_dirs = sorted(
        (root / "comparisons").glob("k*-vs-k*"),
        key=lambda path: int(re.match(r"k(\d+)-", path.name).group(1)),
    )
    for comparison_dir in comparison_dirs:
      match = re.fullmatch(r"k(\d+)-vs-k(\d+)", comparison_dir.name)
      if match is None:
        continue
      k = int(match.group(1))
      if int(match.group(2)) != k - 1:
        raise ValueError(f"Non-adjacent comparison: {comparison_dir}")

      payload = json.loads(
          (comparison_dir / "comparison_manifest.json").read_text(
            encoding="utf-8")
      )
      grouped: dict[tuple[int, int], dict[int, tuple[Path, Path]]] = {}
      for item in payload["pairs"]:
        sample, sources, work_units = json.loads(item["key"])
        grouped.setdefault((int(sources), int(work_units)), {})[int(sample)] = (
          Path(item["baselineSourcePath"]),
          Path(item["candidateSourcePath"]),
        )

      if len(grouped) != 15:
        raise ValueError(
            f"Expected 15 source/body cells in {comparison_dir}, found {len(grouped)}"
        )

      for (sources, work_units), samples in sorted(grouped.items()):
        if set(samples) != {0, 1}:
          raise ValueError(
              f"Missing balanced samples in {comparison_dir}: "
              f"sources={sources} workUnits={work_units} samples={sorted(samples)}"
          )
        k_paths = [samples[index][0] for index in (0, 1)]
        k_minus_1_paths = [samples[index][1] for index in (0, 1)]
        if any(_load_cutoff(path) != k for path in k_paths):
          raise ValueError(f"Baseline cutoff mismatch in {comparison_dir}")
        if any(_load_cutoff(path) != k - 1 for path in k_minus_1_paths):
          raise ValueError(f"Candidate cutoff mismatch in {comparison_dir}")

        pair_id = f"{root.name}__k{k}-vs-k{k - 1}__s{sources}__wu{work_units}"
        pairs.append(
            ManifestPair(
                pair_id=pair_id,
                k_run_path=k_paths[0],
                k_minus_1_run_path=k_minus_1_paths[0],
                K=k,
                k_sample_paths=k_paths,
                k_minus_1_sample_paths=k_minus_1_paths,
                metadata={
                  "experiment": root.name,
                  "comparison": comparison_dir.name,
                  "workUnits": work_units,
                  "parallelSources": sources,
                  "lifecycleMode": "CONTINUOUS",
                  "executionPathSemantics": "default-threshold-fully-resolved",
                },
            )
        )

  if len(pairs) != 240:
    raise ValueError(
      f"Expected 240 physical adjacent pairs, found {len(pairs)}")
  return Manifest(
      schema_version=1,
      runtime_commit=runtime_commit,
      cache_actuator_version="cache-v1",
      cache_park_ns=15000,
      topology_id=TOPOLOGY_ID,
      pairs=pairs,
  )


def _frozen_pair(pair: ManifestPair, record) -> ManifestPair:
  evidence = {
    "effectiveOutcome": record.effective_outcome.value,
    "labelEvidenceBasis": record.label_evidence_basis.value,
    "y": record.y,
    "pairWeight": record.pair_weight,
    "basisThroughputK": record.basis_throughput_k,
    "basisThroughputKMinus1": record.basis_throughput_k_minus_1,
    "basisDelta": record.basis_delta,
    "basisVarianceK": record.basis_variance_k,
    "basisVarianceKMinus1": record.basis_variance_k_minus_1,
    "basisUncertainty": record.basis_uncertainty,
  }
  metadata = dict(pair.metadata)
  metadata["frozenStep4Evidence"] = evidence
  metadata["trialConfigSha256"] = {
    "k": [
      ChecksumVerifier.compute_sha256(path / "trial_config.json")
      for path in pair.k_sample_paths
    ],
    "kMinus1": [
      ChecksumVerifier.compute_sha256(path / "trial_config.json")
      for path in pair.k_minus_1_sample_paths
    ],
  }
  return ManifestPair(
      pair_id=pair.pair_id,
      k_run_path=pair.k_run_path,
      k_minus_1_run_path=pair.k_minus_1_run_path,
      K=pair.K,
      k_sample_paths=pair.k_sample_paths,
      k_minus_1_sample_paths=pair.k_minus_1_sample_paths,
      metadata=metadata,
  )


def _process_pair(candidate: Manifest, index: int, pair: ManifestPair):
  try:
    record = DataLoader.load_pair(
        manifest=candidate,
        pair_decl=pair,
        verify_checksums=True,
        strict_compatibility=True,
        require_sidecars=True,
    )
  except StalenessParseError as exc:
    return index, None, {
      "pairId": pair.pair_id,
      "status": "EXCLUDED_FEATURE_GUARD",
      "outcome": "INCONCLUSIVE",
      "pairWeight": 0.0,
      "reason": str(exc),
    }, None
  except EligibilityError as exc:
    return index, None, {
      "pairId": pair.pair_id,
      "status": "INELIGIBLE",
      "outcome": "INCONCLUSIVE",
      "pairWeight": 0.0,
      "reason": str(exc),
    }, None
  except Exception as exc:
    message = f"{pair.pair_id}: {type(exc).__name__}: {exc}"
    return index, None, {
      "pairId": pair.pair_id,
      "status": "ERROR",
      "outcome": "INCONCLUSIVE",
      "pairWeight": 0.0,
      "reason": message,
    }, message

  if record is None:
    message = f"{pair.pair_id}: loader returned no record"
    return index, None, {
      "pairId": pair.pair_id,
      "status": "ERROR",
      "outcome": "INCONCLUSIVE",
      "pairWeight": 0.0,
      "reason": message,
    }, message
  training_outcome = record.effective_outcome in {
    Outcome.K_WINS,
    Outcome.K_MINUS_1_WINS,
    Outcome.STABLE_TIE,
  }
  retain = (
      record.eligibility == ArtifactEligibility.ELIGIBLE
      and training_outcome
      and record.label_evidence_basis != LabelEvidenceBasis.NONE
      and record.pair_weight > 0.0
      and record.features.P > 0.0
  )
  reason = ""
  if not retain:
    if record.features.P <= 0.0:
      reason = "productiveHandles <= 0 guard bypasses the fitted marginal"
    elif record.pair_weight <= 0.0:
      reason = "nonpositive confidence weight"
    else:
      reason = (
        f"outcome={record.effective_outcome.value} "
        f"evidence={record.label_evidence_basis.value} "
        f"eligibility={record.eligibility.value}"
      )
  inventory = {
    "pairId": pair.pair_id,
    "status": "RETAINED" if retain else "EXCLUDED",
    "outcome": record.effective_outcome.value,
    "pairWeight": record.pair_weight,
    "reason": reason,
  }
  return index, _frozen_pair(pair, record) if retain else None, inventory, None


def freeze_surface(output_manifest: Path, inventory_path: Path) -> None:
  candidate = discover_pairs(_runtime_commit())
  retained_by_index: dict[int, ManifestPair] = {}
  inventory_by_index: dict[int, dict[str, object]] = {}
  fatal: list[str] = []

  worker_count = min(24, max(1, os.cpu_count() or 1), len(candidate.pairs))
  print(
    f"Processing {len(candidate.pairs)} pairs with {worker_count} processes",
    flush=True)
  with ProcessPoolExecutor(max_workers=worker_count) as executor:
    futures = {
      executor.submit(_process_pair, candidate, index, pair): (index, pair)
      for index, pair in enumerate(candidate.pairs)
    }
    completed = 0
    for future in as_completed(futures):
      index, frozen_pair, inventory, fatal_message = future.result()
      pair = candidate.pairs[index]
      completed += 1
      print(
          f"[{completed:03d}/{len(candidate.pairs)}] {pair.pair_id}",
          flush=True,
      )
      inventory_by_index[index] = inventory
      if fatal_message is not None:
        fatal.append(fatal_message)
      if frozen_pair is not None:
        retained_by_index[index] = frozen_pair

  inventory = [inventory_by_index[index] for index in
               range(len(candidate.pairs))]
  retained = [retained_by_index[index] for index in sorted(retained_by_index)]

  inventory_path.parent.mkdir(parents=True, exist_ok=True)
  with inventory_path.open("w", encoding="utf-8", newline="") as stream:
    writer = csv.DictWriter(
        stream,
        fieldnames=("pairId", "status", "outcome", "pairWeight", "reason"),
        delimiter="\t",
        lineterminator="\n",
    )
    writer.writeheader()
    writer.writerows(inventory)
  _write_checksum(inventory_path)

  if fatal:
    raise RuntimeError("Surface processing failed:\n" + "\n".join(fatal))
  if not retained:
    raise RuntimeError("Surface produced no positive-weight training rows")

  frozen = Manifest(
      schema_version=candidate.schema_version,
      runtime_commit=candidate.runtime_commit,
      cache_actuator_version=candidate.cache_actuator_version,
      cache_park_ns=candidate.cache_park_ns,
      topology_id=candidate.topology_id,
      pairs=retained,
  )
  save_manifest(frozen, output_manifest)
  print(
      f"Frozen {len(retained)} positive-weight rows from {len(candidate.pairs)} physical pairs "
      f"to {output_manifest}",
      flush=True,
  )


def main() -> int:
  output_manifest = BASE_DIR / "experiments/pareto_training_surface_manifest.json"
  inventory_path = BASE_DIR / "experiments/pareto_training_surface_inventory.tsv"
  freeze_surface(output_manifest, inventory_path)
  return 0


if __name__ == "__main__":
  sys.exit(main())
