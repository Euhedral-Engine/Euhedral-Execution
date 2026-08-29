#!/usr/bin/env python3
"""Audit whether retained pre-gate rows would change execution path under the new gate."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path


def main() -> int:
  parser = argparse.ArgumentParser()
  parser.add_argument("--pairs", type=Path, required=True)
  parser.add_argument("--output", type=Path, required=True)
  parser.add_argument("--observed-threshold-min-ns", type=float, required=True)
  parser.add_argument("--observed-threshold-max-ns", type=float, required=True)
  parser.add_argument("--observed-threshold-mean-ns", type=float, required=True)
  parser.add_argument("--threshold-samples", type=int, required=True)
  args = parser.parse_args()

  expected = args.pairs.with_name(args.pairs.name + ".sha256").read_text(
      encoding="utf-8"
  ).strip().split()[0]
  actual = hashlib.sha256(args.pairs.read_bytes()).hexdigest()
  if actual != expected:
    raise ValueError("Compact evidence checksum mismatch")

  with args.pairs.open("r", encoding="utf-8", newline="") as stream:
    rows = list(csv.DictReader(stream, delimiter="\t"))
  classifications = []
  for row in rows:
    contention = float(row["c"])
    body_cost = float(row["smoothedBodyCostNs"])
    staged_by_contention = contention > 0.85
    below_observed_threshold = body_cost <= args.observed_threshold_min_ns
    classifications.append(
        {
          "pairId": row["pairId"],
          "contention": contention,
          "smoothedBodyCostNs": body_cost,
          "stagedByContention": staged_by_contention,
          "belowObservedThresholdMinimum": below_observed_threshold,
          "gateInert": staged_by_contention or below_observed_threshold,
        }
    )
  incompatible = [row for row in classifications if not row["gateInert"]]
  low_contention = [row for row in classifications if
                    not row["stagedByContention"]]
  payload = {
    "schemaVersion": 1,
    "status": "VALID_FOR_COMBINED_TRAINING" if not incompatible else "INCOMPATIBLE",
    "compactEvidencePath": str(args.pairs),
    "compactEvidenceSha256": actual,
    "currentExecutionPathRule": {
      "directWhen": "contention <= 0.85 and smoothedBodyCostNs <= calibrated threshold",
      "defaultBodyCostDirectThresholdWeight": 272,
    },
    "currentHostThresholdProbe": {
      "sampleCount": args.threshold_samples,
      "minimumNs": args.observed_threshold_min_ns,
      "meanNs": args.observed_threshold_mean_ns,
      "maximumNs": args.observed_threshold_max_ns,
    },
    "audit": {
      "rowCount": len(rows),
      "gateInertRowCount": len(rows) - len(incompatible),
      "incompatibleRowCount": len(incompatible),
      "lowContentionRowCount": len(low_contention),
      "maximumBodyCostAmongLowContentionRowsNs": max(
          row["smoothedBodyCostNs"] for row in low_contention
      ),
      "stagedByContentionRowCount": sum(
          1 for row in classifications if row["stagedByContention"]
      ),
    },
    "incompatibleRows": incompatible,
    "conclusion": (
      "Every retained row either remains DIRECT by body cost or was already STAGED "
      "by contention, so the added body-cost gate does not change its resolved path."
      if not incompatible
      else "At least one retained row could change resolved execution path."
    ),
    "provenanceLimit": (
      "The original run directories are unavailable; this audit uses the checksum-validated "
      "compact Step 4 evidence and its frozen manifest."
    ),
  }
  args.output.parent.mkdir(parents=True, exist_ok=True)
  content = json.dumps(payload, indent=2, sort_keys=True) + "\n"
  args.output.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
  args.output.with_name(args.output.name + ".sha256").write_text(
      digest + "\n", encoding="utf-8"
  )
  print(json.dumps(payload["audit"], sort_keys=True))
  return 0 if not incompatible else 1


if __name__ == "__main__":
  raise SystemExit(main())
