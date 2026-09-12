#!/usr/bin/env python3
"""Record a successful generated-candidate JVM parity run in the Step 5 artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def main() -> int:
  parser = argparse.ArgumentParser()
  parser.add_argument("--candidate", type=Path, required=True)
  parser.add_argument("--summary", type=Path, required=True)
  args = parser.parse_args()

  payload = json.loads(args.candidate.read_text(encoding="utf-8"))
  payload["javaActionParity"] = "PASSED"
  payload.setdefault("diagnostics", {})["javaActionParity"] = {
    "status": "PASSED",
    "test": (
      "io.euhedral_execution.core.control_plane."
      "FragmentDecisionTreeRuntimeParityTest.generatedStep5CandidateActionParity"
    ),
    "boundary": "m <= 0 participates; m > 0 selects CACHE",
  }
  content = json.dumps(payload, indent=2, sort_keys=True,
                       allow_nan=False) + "\n"
  args.candidate.write_text(content, encoding="utf-8")
  digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
  args.candidate.with_name(args.candidate.name + ".sha256").write_text(
      digest + "\n", encoding="utf-8"
  )
  summary = json.loads(args.summary.read_text(encoding="utf-8"))
  summary["candidateModelSha256"] = digest
  summary_content = json.dumps(summary, indent=2, sort_keys=True,
                               allow_nan=False) + "\n"
  args.summary.write_text(summary_content, encoding="utf-8")
  summary_digest = hashlib.sha256(summary_content.encode("utf-8")).hexdigest()
  args.summary.with_name(args.summary.name + ".sha256").write_text(
      summary_digest + "\n", encoding="utf-8"
  )
  print(digest)
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
