"""Dataset manifest loader and path resolver."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict

from pareto_weight_calibration.types import Manifest, ManifestPair


class ManifestLoadError(Exception):
    """Raised when a dataset manifest is invalid or malformed."""


def load_manifest(manifest_path: Path) -> Manifest:
    """Loads and validates a dataset manifest JSON file.

    Args:
        manifest_path: Path to dataset_manifest.json.

    Returns:
        Validated Manifest dataclass.

    Raises:
        ManifestLoadError: On invalid schema, missing required fields, or unparseable JSON.
        FileNotFoundError: If manifest_path does not exist.
    """
    if not manifest_path.exists() or not manifest_path.is_file():
        raise FileNotFoundError(f"Manifest file not found: {manifest_path}")

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
    parsed_pairs: list[ManifestPair] = []

    for idx, p in enumerate(raw_pairs):
        if not isinstance(p, dict):
            raise ManifestLoadError(f"Pair entry #{idx} is not an object")

        pair_id = p.get("pairId")
        k_run_raw = p.get("kRunPath") or p.get("kRun")
        k_minus_1_run_raw = p.get("kMinus1RunPath") or p.get("kMinus1Run")
        k_val = p.get("K")
        metadata = p.get("metadata", {})

        if not pair_id:
            raise ManifestLoadError(f"Pair #{idx} missing 'pairId'")
        if not k_run_raw:
            raise ManifestLoadError(f"Pair #{idx} ({pair_id}) missing 'kRunPath'")
        if not k_minus_1_run_raw:
            raise ManifestLoadError(f"Pair #{idx} ({pair_id}) missing 'kMinus1RunPath'")
        if k_val is None or not isinstance(k_val, int) or k_val < 2:
            raise ManifestLoadError(f"Pair #{idx} ({pair_id}) invalid 'K': {k_val}. Must be >= 2.")

        # Resolve relative paths against manifest directory
        k_path = Path(k_run_raw)
        if not k_path.is_absolute():
            k_path = (manifest_dir / k_path).resolve()

        k_minus_1_path = Path(k_minus_1_run_raw)
        if not k_minus_1_path.is_absolute():
            k_minus_1_path = (manifest_dir / k_minus_1_path).resolve()

        parsed_pairs.append(
            ManifestPair(
                pair_id=str(pair_id),
                k_run_path=k_path,
                k_minus_1_run_path=k_minus_1_path,
                K=k_val,
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
