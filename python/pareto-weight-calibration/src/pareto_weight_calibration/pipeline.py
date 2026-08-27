"""Mechanical productivity-participation calibration pipeline."""

from __future__ import annotations

import argparse
from dataclasses import asdict, is_dataclass
from enum import Enum
import hashlib
import json
import math
from pathlib import Path
from typing import Any, Sequence

import numpy as np

from pareto_weight_calibration.audit import build_dataset, \
  perform_identifiability_audit
from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.constraints import MODEL_STRUCTURES, \
  load_domain_config
from pareto_weight_calibration.cv import execute_lofo_grid_search
from pareto_weight_calibration.diagnostics import (
  build_common_reference_scales,
  compare_common_reference_stability,
  diagnose_fixed_state_prefix,
  run_adjacent_lambda_sensitivity,
  run_low_confidence_ablation,
  verify_evaluator_parity_grid,
)
from pareto_weight_calibration.export import export_pairs_tsv
from pareto_weight_calibration.loader import DataLoader
from pareto_weight_calibration.manifest import load_manifest
from pareto_weight_calibration.optimizer import fit_constrained_model
from pareto_weight_calibration.scaling import compute_training_scales
from pareto_weight_calibration.types import ArtifactEligibility, Manifest, \
  Outcome


def _jsonable(value: Any) -> Any:
  """Converts pipeline values to deterministic, standards-compliant JSON values."""
  if is_dataclass(value):
    return _jsonable(asdict(value))
  if isinstance(value, np.ndarray):
    return _jsonable(value.tolist())
  if isinstance(value, np.generic):
    return _jsonable(value.item())
  if isinstance(value, Enum):
    return value.value
  if isinstance(value, Path):
    return str(value)
  if isinstance(value, dict):
    return {str(key): _jsonable(item) for key, item in value.items()}
  if isinstance(value, (list, tuple)):
    return [_jsonable(item) for item in value]
  if isinstance(value, float) and not math.isfinite(value):
    if math.isnan(value):
      return "NaN"
    return "Infinity" if value > 0.0 else "-Infinity"
  return value


def _write_json_with_sidecar(path: Path, payload: Any) -> str:
  path.parent.mkdir(parents=True, exist_ok=True)
  content = json.dumps(_jsonable(payload), indent=2, sort_keys=True) + "\n"
  path.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
  path.with_name(path.name + ".sha256").write_text(digest + "\n",
                                                   encoding="utf-8")
  return digest


def load_frozen_training_manifest(path: Path) -> Manifest:
  """Loads a frozen manifest and rejects missing or inconsistent evidence."""
  ChecksumVerifier.verify_file(path, require_sidecar=True)
  manifest = load_manifest(path, verify_checksum=True)
  invalid: list[str] = []
  seen: set[str] = set()
  expected_y = {
    Outcome.K_WINS.value: 0.0,
    Outcome.K_MINUS_1_WINS.value: 1.0,
    Outcome.STABLE_TIE.value: 0.5,
  }

  for pair in manifest.pairs:
    if pair.pair_id in seen:
      invalid.append(f"{pair.pair_id}: duplicate pairId")
    seen.add(pair.pair_id)

    evidence = pair.metadata.get("frozenStep4Evidence")
    if not isinstance(evidence, dict):
      invalid.append(f"{pair.pair_id}: missing frozenStep4Evidence")
      continue

    outcome = evidence.get("effectiveOutcome")
    basis = evidence.get("labelEvidenceBasis")
    try:
      y = float(evidence["y"])
      pair_weight = float(evidence["pairWeight"])
    except (KeyError, TypeError, ValueError):
      invalid.append(f"{pair.pair_id}: invalid frozen y or pairWeight")
      continue

    if outcome not in expected_y:
      invalid.append(
        f"{pair.pair_id}: invalid frozen effectiveOutcome {outcome!r}")
    elif y != expected_y[outcome]:
      invalid.append(f"{pair.pair_id}: frozen y={y} does not match {outcome}")
    if basis in (None, "NONE"):
      invalid.append(f"{pair.pair_id}: missing frozen label evidence basis")
    if not math.isfinite(pair_weight) or pair_weight <= 0.0:
      invalid.append(
        f"{pair.pair_id}: frozen pairWeight must be finite and positive")

    numeric_evidence: dict[str, float] = {}
    for field_name in (
        "basisThroughputK",
        "basisThroughputKMinus1",
        "basisDelta",
        "basisVarianceK",
        "basisVarianceKMinus1",
        "basisUncertainty",
    ):
      try:
        basis_value = float(evidence[field_name])
      except (KeyError, TypeError, ValueError):
        invalid.append(f"{pair.pair_id}: invalid frozen {field_name}")
        continue
      if not math.isfinite(basis_value):
        invalid.append(f"{pair.pair_id}: non-finite frozen {field_name}")
        continue
      numeric_evidence[field_name] = basis_value

    for field_name in ("basisThroughputK", "basisThroughputKMinus1"):
      if field_name in numeric_evidence and numeric_evidence[field_name] <= 0.0:
        invalid.append(f"{pair.pair_id}: frozen {field_name} must be positive")
    for field_name in ("basisVarianceK", "basisVarianceKMinus1",
                       "basisUncertainty"):
      if field_name in numeric_evidence and numeric_evidence[field_name] < 0.0:
        invalid.append(
          f"{pair.pair_id}: frozen {field_name} must be non-negative")

    config_hashes = pair.metadata.get("trialConfigSha256")
    if not isinstance(config_hashes, dict):
      invalid.append(f"{pair.pair_id}: missing trialConfigSha256")
      continue
    for arm_name, sample_paths in (
        ("k", pair.k_sample_paths),
        ("kMinus1", pair.k_minus_1_sample_paths),
    ):
      digests = config_hashes.get(arm_name)
      if not isinstance(digests, list) or len(digests) != len(sample_paths):
        invalid.append(f"{pair.pair_id}: invalid trialConfigSha256.{arm_name}")
        continue
      if any(
          not isinstance(digest, str)
          or len(digest) != 64
          or any(ch not in "0123456789abcdef" for ch in digest)
          for digest in digests
      ):
        invalid.append(
          f"{pair.pair_id}: malformed trialConfigSha256.{arm_name}")

  if not manifest.pairs:
    invalid.append("manifest contains no pairs")
  if invalid:
    raise ValueError("Invalid frozen training manifest: " + "; ".join(invalid))
  return manifest


def _metric_summary(metrics: Any) -> dict[str, Any]:
  return {
    "supportedRelativeRegret": metrics.supported_rel_regret,
    "worstFamilyRelativeRegret": metrics.worst_family_rel_regret,
    "observedAbsoluteRegret": metrics.observed_abs_regret,
    "observedRelativeRegret": metrics.observed_rel_regret,
    "weightedBinaryCrossEntropy": metrics.weighted_bce,
    "winnerAccuracy": metrics.winner_accuracy,
    "rawWinnerAccuracy": metrics.raw_winner_accuracy,
    "decisiveCount": metrics.decisive_count,
    "stableTieCount": metrics.stable_tie_count,
    "totalCount": metrics.total_count,
    "totalWeight": metrics.total_weight,
    "familyRelativeRegrets": metrics.family_rel_regrets,
  }


def run_pipeline(manifest_path: Path, domain_path: Path, output_dir: Path) -> \
dict[str, Any]:
  """Runs strict ingestion, audit, grouped fitting, and deterministic artifact export."""
  manifest = load_frozen_training_manifest(manifest_path)
  ChecksumVerifier.verify_file(domain_path, require_sidecar=True)
  domain = load_domain_config(domain_path)

  records = []
  for pair in manifest.pairs:
    record = DataLoader.load_pair(
        manifest=manifest,
        pair_decl=pair,
        verify_checksums=True,
        strict_compatibility=True,
        require_sidecars=True,
    )
    if record is None or record.eligibility != ArtifactEligibility.ELIGIBLE:
      raise ValueError(f"Frozen pair {pair.pair_id} was not eligible")
    records.append(record)

  dataset = build_dataset(
      records,
      min_weight=0.0,
      require_eligible_only=True,
      require_all_records_retained=True,
  )
  if len(dataset.records) != len(manifest.pairs):
    raise ValueError("Frozen training manifest was not retained exactly")

  output_dir.mkdir(parents=True, exist_ok=True)
  pairs_path = output_dir / "training_pairs.tsv"
  export_pairs_tsv(dataset.records, pairs_path)
  pairs_digest = ChecksumVerifier.compute_sha256(pairs_path)
  pairs_path.with_name(pairs_path.name + ".sha256").write_text(
      pairs_digest + "\n", encoding="utf-8"
  )

  audit = perform_identifiability_audit(dataset)
  audit_path = output_dir / "identifiability_audit.json"
  audit_digest = _write_json_with_sidecar(
      audit_path,
      {
        "manifestPairCount": len(manifest.pairs),
        "datasetRowCount": len(dataset.records),
        "familyCounts": dataset.family_counts,
        "audit": audit,
      },
  )

  grid = execute_lofo_grid_search(dataset, domain)
  selection = grid.parsimony_result
  structure = selection.selected_structure
  l2_reg = selection.selected_l2_reg
  active_indices = MODEL_STRUCTURES[structure]
  scales = compute_training_scales(dataset.X, dataset.u,
                                   active_indices=active_indices)
  fit = fit_constrained_model(
      X_train=dataset.X,
      y_train=dataset.y,
      u_train=dataset.u,
      scales=scales,
      domain=domain,
      structure_name=structure,
      l2_reg=l2_reg,
      active_indices=active_indices,
  )

  python_parity = verify_evaluator_parity_grid(fit.w_phys_full, domain)
  prefix = diagnose_fixed_state_prefix(fit.w_phys_full, domain)
  reference_scales = build_common_reference_scales(dataset)
  selected_lofo = grid.results[(structure, l2_reg)]
  fold_stability = {
    fold.held_out_family: compare_common_reference_stability(
        fit.w_phys_full,
        fold.opt_result.w_phys_full,
        reference_scales,
        domain,
    )
    for fold in selected_lofo.fold_results
  }
  ablation = run_low_confidence_ablation(
      dataset,
      domain,
      structure,
      l2_reg,
      threshold_v=0.1,
      s_ref=reference_scales,
  )
  sensitivity = run_adjacent_lambda_sensitivity(
      grid,
      structure,
      reference_scales,
      domain,
  )

  selected_metrics = selection.selected_metrics
  participate = grid.baseline_always_participate
  fixed_cutoff = grid.baseline_training_selected_fixed_cutoff
  tolerance = 1e-12
  internal_acceptance = (
      fit.success
      and fit.constraint_violation <= tolerance
      and python_parity
      and prefix.fixed_state_prefix_verified
      and selected_metrics.supported_rel_regret
      < participate.supported_rel_regret - tolerance
      and selected_metrics.supported_rel_regret
      <= fixed_cutoff.supported_rel_regret + tolerance
      and selected_metrics.worst_family_rel_regret
      <= participate.worst_family_rel_regret + tolerance
  )

  manifest_digest = ChecksumVerifier.compute_sha256(manifest_path)
  domain_digest = ChecksumVerifier.compute_sha256(domain_path)
  model = {
    "schemaVersion": 1,
    "status": "INTERNAL_ACCEPTANCE_PASSED" if internal_acceptance else "NO_ADMISSIBLE_MODEL",
    "productionAuthorized": False,
    "javaActionParity": "NOT_RUN",
    "model": {
      "structure": structure,
      "lambda": l2_reg,
      "activeIndices": active_indices,
      "physicalWeights": fit.w_phys_full,
      "featureScales": scales,
    },
    "optimizer": fit,
    "selection": {
      "selectedMetrics": _metric_summary(selected_metrics),
      "incumbentProgression": selection.incumbent_progression,
      "history": selection.history,
    },
    "baselines": {
      "alwaysParticipate": _metric_summary(participate),
      "alwaysCache": _metric_summary(grid.baseline_always_cache),
      "trainingSelectedFixedCutoff": _metric_summary(fixed_cutoff),
    },
    "diagnostics": {
      "pythonEvaluatorParity": python_parity,
      "fixedStatePrefix": prefix,
      "commonReferenceScales": reference_scales,
      "foldStability": fold_stability,
      "lowConfidenceAblation": ablation,
      "adjacentLambdaSensitivity": sensitivity,
    },
    "provenance": {
      "manifestPath": str(manifest_path),
      "manifestSha256": manifest_digest,
      "domainPath": str(domain_path),
      "domainSha256": domain_digest,
      "trainingPairsPath": str(pairs_path),
      "trainingPairsSha256": pairs_digest,
      "identifiabilityAuditPath": str(audit_path),
      "identifiabilityAuditSha256": audit_digest,
      "sourceArtifactChecksums": {
        record.pair_id: record.artifact_checksums for record in dataset.records
      },
    },
  }
  model_path = output_dir / "candidate_model.json"
  model_digest = _write_json_with_sidecar(model_path, model)

  grid_results = {
    f"{name}@{l2:g}": {
      "valid": result.is_valid,
      "rejectionReason": result.rejection_reason,
      "metrics": _metric_summary(result.pooled_metrics),
    }
    for (name, l2), result in sorted(grid.results.items())
  }
  summary = {
    "status": model["status"],
    "manifestPairCount": len(manifest.pairs),
    "datasetRowCount": len(dataset.records),
    "physicalFamilyCount": len(dataset.family_counts),
    "totalInfluenceWeight": dataset.U,
    "effectiveSampleSize": dataset.n_eff,
    "selectedStructure": structure,
    "selectedLambda": l2_reg,
    "candidateModelPath": str(model_path),
    "candidateModelSha256": model_digest,
    "gridResults": grid_results,
  }
  _write_json_with_sidecar(output_dir / "pipeline_summary.json", summary)
  return summary


def _build_parser() -> argparse.ArgumentParser:
  parser = argparse.ArgumentParser(
      description="Run the frozen productivity-participation calibration pipeline."
  )
  parser.add_argument("--manifest", type=Path, required=True)
  parser.add_argument("--domain", type=Path, required=True)
  parser.add_argument("--output-dir", type=Path, required=True)
  return parser


def main(argv: Sequence[str] | None = None) -> int:
  args = _build_parser().parse_args(argv)
  summary = run_pipeline(args.manifest, args.domain, args.output_dir)
  print(json.dumps(_jsonable(summary), indent=2, sort_keys=True))
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
