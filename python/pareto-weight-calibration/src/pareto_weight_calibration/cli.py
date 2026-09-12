"""Command-line interface for the Pareto-weight calibration data pipeline."""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

from pareto_weight_calibration.export import export_pairs_tsv
from pareto_weight_calibration.loader import DataLoader
from pareto_weight_calibration.manifest import load_manifest


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="pareto-calibration",
        description="Pareto-weight calibration dataset ingestion, validation, and export.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # validate subcommand
    val_p = subparsers.add_parser("validate", help="Validate manifest, artifact checksums, and arm compatibility.")
    val_p.add_argument("--manifest", type=Path, required=True, help="Path to dataset_manifest.json")
    val_p.add_argument("--no-strict", action="store_true", help="Do not exit on compatibility failures")

    # load subcommand
    load_p = subparsers.add_parser("load", help="Ingest manifest, join all artifacts, and export pairs.tsv")
    load_p.add_argument("--manifest", type=Path, required=True, help="Path to dataset_manifest.json")
    load_p.add_argument("--output", type=Path, required=True, help="Target path for exported pairs.tsv")
    load_p.add_argument("--min-weight", type=float, default=0.0, help="Minimum pair weight filter")

    # summary subcommand
    sum_p = subparsers.add_parser("summary", help="Display summary statistics for an exported pairs.tsv")
    sum_p.add_argument("--pairs", type=Path, required=True, help="Path to pairs.tsv")

    return parser


def cmd_validate(args: argparse.Namespace) -> int:
    manifest_path: Path = args.manifest
    try:
        manifest = load_manifest(manifest_path)
        print(f"Manifest: {manifest_path} (schema v{manifest.schema_version})")
        print(f"Topology: {manifest.topology_id}, Commit: {manifest.runtime_commit}")
        print(f"Actuator: {manifest.cache_actuator_version} (park={manifest.cache_park_ns} ns)")
        print(f"Total declared pairs: {len(manifest.pairs)}")

        valid_count = 0
        for p in manifest.pairs:
            try:
                record = DataLoader.load_pair(
                    manifest=manifest,
                    pair_decl=p,
                    verify_checksums=True,
                    strict_compatibility=not args.no_strict,
                )
                if record:
                    valid_count += 1
                    print(f"  [OK] Pair {p.pair_id}: K={p.K}, outcome={record.whole_outcome.value}, y={record.y}, weight={record.pair_weight:.4f}")
            except Exception as e:
                print(f"  [FAIL] Pair {p.pair_id}: {e}")

        print(f"Validation summary: {valid_count}/{len(manifest.pairs)} valid pairs.")
        return 0 if valid_count == len(manifest.pairs) else 1
    except Exception as e:
        print(f"Validation error: {e}", file=sys.stderr)
        return 1


def cmd_load(args: argparse.Namespace) -> int:
    manifest_path: Path = args.manifest
    output_path: Path = args.output
    min_weight: float = args.min_weight

    try:
        records = DataLoader.load_dataset(
            manifest_path=manifest_path,
            verify_checksums=True,
            min_weight=min_weight,
            strict_compatibility=True,
        )
        print(f"Loaded {len(records)} eligible pairs from {manifest_path} (min_weight={min_weight})")
        export_pairs_tsv(records, output_path)
        print(f"Exported to {output_path} (along with .sha256 sidecar)")
        return 0
    except Exception as e:
        print(f"Load failed: {e}", file=sys.stderr)
        return 1


def cmd_summary(args: argparse.Namespace) -> int:
    pairs_path: Path = args.pairs
    if not pairs_path.exists():
        print(f"File not found: {pairs_path}", file=sys.stderr)
        return 1

    with open(pairs_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        rows = list(reader)

    print(f"Dataset Summary: {pairs_path} ({len(rows)} pairs)")
    print("-" * 78)
    print(f"{'Pair ID':<20} {'K':<4} {'y':<4} {'Weight':<8} {'Delta':<10} {'Whole Outcome':<15} {'Trajectory':<15}")
    print("-" * 78)
    for r in rows:
        print(
            f"{r.get('pairId', ''):<20} "
            f"{r.get('K', ''):<4} "
            f"{r.get('y', ''):<4} "
            f"{float(r.get('pairWeight', 0)):<8.4f} "
            f"{float(r.get('deltaThroughput', 0)):<10.1f} "
            f"{r.get('wholeRunOutcome', ''):<15} "
            f"{r.get('trajectoryStatus', ''):<15}"
        )
    print("-" * 78)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "validate":
        return cmd_validate(args)
    elif args.command == "load":
        return cmd_load(args)
    elif args.command == "summary":
        return cmd_summary(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
