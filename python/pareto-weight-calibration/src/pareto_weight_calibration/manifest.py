"""Dataset manifest loader, validator, and frozen training-manifest generator."""

from __future__ import annotations

import hashlib
import json
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

from pareto_weight_calibration.checksum import ChecksumVerifier
from pareto_weight_calibration.types import Manifest, ManifestPair

logger = logging.getLogger(__name__)


class ManifestLoadError(Exception):
    """Raised when a dataset manifest is invalid or malformed."""


def load_manifest(manifest_path: Path,
    verify_checksum: bool = True) -> Manifest:
    """Loads and validates a dataset manifest JSON file.

    Args:
        manifest_path: Path to dataset_manifest.json.
        verify_checksum: Whether to verify manifest's .sha256 sidecar if present.

    Returns:
        Validated Manifest dataclass.

    Raises:
        ManifestLoadError: On invalid schema, missing required fields, or unparseable JSON.
        FileNotFoundError: If manifest_path does not exist.
    """
    if not manifest_path.exists() or not manifest_path.is_file():
        raise FileNotFoundError(f"Manifest file not found: {manifest_path}")

    if verify_checksum:
      sidecar = manifest_path.parent / (manifest_path.name + ".sha256")
      if sidecar.exists():
        ChecksumVerifier.verify_file(manifest_path, require_sidecar=True)

    try:
        data: Dict[str, Any] = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception as e:
        raise ManifestLoadError(f"Failed to parse manifest JSON at {manifest_path}: {e}") from e

    schema_version = data.get("schemaVersion")
    if schema_version != 1:
        raise ManifestLoadError(
            f"Unsupported schemaVersion: {schema_version}. Expected 1."
        )

    runtime_commit = data.get("runtimeCommit")
    cache_actuator_version = data.get("cacheActuatorVersion")
    cache_park_ns = data.get("cacheParkNs")
    topology_id = data.get("topologyId")

    if not runtime_commit:
        raise ManifestLoadError("Manifest missing 'runtimeCommit'")
    if not cache_actuator_version:
        raise ManifestLoadError("Manifest missing 'cacheActuatorVersion'")
    if cache_park_ns is None or not isinstance(cache_park_ns, int):
        raise ManifestLoadError("Manifest missing or invalid 'cacheParkNs'")
    if not topology_id:
        raise ManifestLoadError("Manifest missing 'topologyId'")

    raw_pairs = data.get("pairs")
    if not isinstance(raw_pairs, list) or len(raw_pairs) == 0:
        raise ManifestLoadError("Manifest must contain a non-empty 'pairs' list")

    manifest_dir = manifest_path.parent
    parsed_pairs: List[ManifestPair] = []

    for idx, p in enumerate(raw_pairs):
        if not isinstance(p, dict):
            raise ManifestLoadError(f"Pair entry #{idx} is not an object")

        pair_id = p.get("pairId")
        k_run_raw = p.get("kRunPath") or p.get("kRun")
        k_minus_1_run_raw = p.get("kMinus1RunPath") or p.get("kMinus1Run")
        k_val = p.get("K")
        metadata = p.get("metadata", {})

        k_samples_raw = p.get("kSamplePaths") or p.get("kSamples") or []
        k_minus_1_samples_raw = p.get("kMinus1SamplePaths") or p.get(
          "kMinus1Samples") or []

        if not pair_id:
            raise ManifestLoadError(f"Pair #{idx} missing 'pairId'")
        if not k_run_raw and not k_samples_raw:
          raise ManifestLoadError(
            f"Pair #{idx} ({pair_id}) missing 'kRunPath' / 'kSamplePaths'")
        if not k_minus_1_run_raw and not k_minus_1_samples_raw:
          raise ManifestLoadError(
            f"Pair #{idx} ({pair_id}) missing 'kMinus1RunPath' / 'kMinus1SamplePaths'")
        if k_val is None or not isinstance(k_val, int) or k_val < 2:
            raise ManifestLoadError(f"Pair #{idx} ({pair_id}) invalid 'K': {k_val}. Must be >= 2.")

        # Resolve primary k_path
        if k_run_raw:
          k_path = Path(k_run_raw)
          if not k_path.is_absolute():
            k_path = (manifest_dir / k_path).resolve()
        else:
          first_raw = k_samples_raw[0]
          k_path = Path(first_raw)
          if not k_path.is_absolute():
            k_path = (manifest_dir / k_path).resolve()

        # Resolve primary k_minus_1_path
        if k_minus_1_run_raw:
          k_minus_1_path = Path(k_minus_1_run_raw)
          if not k_minus_1_path.is_absolute():
            k_minus_1_path = (manifest_dir / k_minus_1_path).resolve()
        else:
          first_raw_b = k_minus_1_samples_raw[0]
          k_minus_1_path = Path(first_raw_b)
          if not k_minus_1_path.is_absolute():
            k_minus_1_path = (manifest_dir / k_minus_1_path).resolve()

        # Resolve list of k_sample_paths
        resolved_k_samples: List[Path] = []
        for s in k_samples_raw:
          sp = Path(s)
          if not sp.is_absolute():
            sp = (manifest_dir / sp).resolve()
          resolved_k_samples.append(sp)

        # Resolve list of k_minus_1_sample_paths
        resolved_k_minus_1_samples: List[Path] = []
        for s in k_minus_1_samples_raw:
          sp = Path(s)
          if not sp.is_absolute():
            sp = (manifest_dir / sp).resolve()
          resolved_k_minus_1_samples.append(sp)

        parsed_pairs.append(
            ManifestPair(
                pair_id=str(pair_id),
                k_run_path=k_path,
                k_minus_1_run_path=k_minus_1_path,
                K=k_val,
                k_sample_paths=resolved_k_samples,
                k_minus_1_sample_paths=resolved_k_minus_1_samples,
                metadata=metadata if isinstance(metadata, dict) else {},
            )
        )

    return Manifest(
        schema_version=schema_version,
        runtime_commit=str(runtime_commit),
        cache_actuator_version=str(cache_actuator_version),
        cache_park_ns=cache_park_ns,
        topology_id=str(topology_id),
        pairs=parsed_pairs,
    )


def save_manifest(manifest: Manifest, output_path: Path) -> None:
  """Serializes a Manifest dataclass to JSON with deterministic sorting and SHA-256 sidecar."""
  output_path.parent.mkdir(parents=True, exist_ok=True)

  data = {
    "schemaVersion": manifest.schema_version,
    "runtimeCommit": manifest.runtime_commit,
    "cacheActuatorVersion": manifest.cache_actuator_version,
    "cacheParkNs": manifest.cache_park_ns,
    "topologyId": manifest.topology_id,
    "pairs": [
      {
        "pairId": p.pair_id,
        "kRunPath": str(p.k_run_path),
        "kMinus1RunPath": str(p.k_minus_1_run_path),
        "K": p.K,
        "kSamplePaths": [str(sp) for sp in
                         p.k_sample_paths] if p.k_sample_paths else [],
        "kMinus1SamplePaths": [str(sp) for sp in
                               p.k_minus_1_sample_paths] if p.k_minus_1_sample_paths else [],
        "metadata": p.metadata,
      }
      for p in manifest.pairs
    ],
  }

  content = json.dumps(data, indent=2, sort_keys=True) + "\n"
  output_path.write_text(content, encoding="utf-8")

  digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
  sidecar_path = output_path.parent / (output_path.name + ".sha256")
  sidecar_path.write_text(f"{digest}\n", encoding="utf-8")
