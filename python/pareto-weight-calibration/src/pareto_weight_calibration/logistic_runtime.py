"""Finalize and export the selected full-data V2 logistic runtime model."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from typing import Sequence

import numpy as np
import sklearn

from pareto_weight_calibration.direct_side import BASE_FEATURES
from pareto_weight_calibration.model_tournament import (DATASET_PATH, fold_plan,
                                                        load_frozen_dataset)
from pareto_weight_calibration.tournament_v2 import (MANIFEST_NAME,
                                                     TRAINER_VERSION,
                                                     select_inner)
from pareto_weight_calibration.tournament_v2_manifest import (build_manifest,
                                                              canonical_bytes)
from pareto_weight_calibration.tournament_v2_models import create_model

ARTIFACT_SCHEMA_VERSION = 1
FINALIZER_VERSION = "logistic-runtime-finalizer-v1"
EXPORTER_VERSION = "logistic-java-exporter-v1"
DEFAULT_RESULTS = Path("experiments/pareto_model_tournament_v2/results")
DEFAULT_ARTIFACT = DEFAULT_RESULTS / "logistic_runtime_candidate.json"
DEFAULT_JAVA = Path(
  "euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ParticipationLogisticModel.java")
DEFAULT_FIXTURE = Path(
  "euhedral-core/src/test/resources/io/euhedral_execution/core/control_plane/participation_logistic_parity.tsv")

INTERACTIONS = ((0, 1), (0, 3), (0, 4), (3, 1), (4, 1), (3, 4))
QUADRATICS = (0, 1, 3, 4)
RAW_DEFINITIONS = {
  "K": "current worker rank K",
  "pRatio": "productiveHandles / (double) registeredWorkers",
  "logR": "natural log of registeredWorkers",
  "body": "log1p(current smoothed body cost in nanoseconds)",
  "contention": "effective time-decayed fixed-point contention / 1000000.0",
}


def _sha256(path: Path) -> str:
  return hashlib.sha256(path.read_bytes()).hexdigest()


def _feature_definitions(geometry: str) -> list[dict]:
  result = [{"name": name, "operation": "scaled_raw", "rawFeature": name}
            for name in BASE_FEATURES]
  if geometry != "main":
    result.extend({
                    "name": f"scaled({BASE_FEATURES[left]})*scaled({BASE_FEATURES[right]})",
                    "operation": "product", "inputs": [left, right],
                  } for left, right in INTERACTIONS)
  if geometry == "quadratic":
    result.extend({
                    "name": f"scaled({BASE_FEATURES[index]})^2",
                    "operation": "square", "input": index,
                  } for index in QUADRATICS)
  return result


def finalize(repo_root: Path, output: Path) -> dict:
  """Repeat V2's grouped logistic selection, then fit it once on all 102 rows."""
  repo_root = repo_root.resolve()
  rows, input_hashes = load_frozen_dataset(repo_root)
  manifest = build_manifest(input_hashes)
  candidates = [item for item in manifest["baseCandidates"]
                if item["modelFamily"] == "logistic"]
  selection = select_inner(rows, candidates, fold_plan(rows, 4), "cpu")
  candidate = selection["selectedCandidate"]
  fitted = create_model("logistic", "cpu").fit(rows, candidate)
  pipeline = fitted.pipeline
  scaler = pipeline.named_steps["scale"]
  classifier = pipeline.named_steps["model"]
  threshold = float(selection["selectedThreshold"])
  geometry = candidate["params"]["geometry"]

  results = repo_root / DEFAULT_RESULTS
  source_paths = [
    repo_root / DATASET_PATH,
    results / MANIFEST_NAME,
    results / "tournament_v2_summary.json",
    results / "tournament_v2_model_results.json",
  ]
  payload = {
    "schemaVersion": ARTIFACT_SCHEMA_VERSION,
    "candidateId": candidate["id"],
    "modelFamily": "logistic",
    "configuration": candidate["params"],
    "weightTransform": candidate["weightTransform"],
    "selectionProcedure": "V2 four-fold grouped physical-family selection on all decisive rows",
    "trainingRowCount": len(rows),
    "featureNames": list(BASE_FEATURES),
    "rawFeatureDefinitions": [
      {"name": name, "definition": RAW_DEFINITIONS[name]}
      for name in BASE_FEATURES
    ],
    "transformOrder": [
      "construct raw features in featureNames order",
      "standardize each raw feature with exported mean and scale",
      f"apply controlled {geometry} expansion to standardized features",
      "evaluate intercept plus coefficients dot expanded features",
    ],
    "geometry": geometry,
    "expandedFeatures": _feature_definitions(geometry),
    "scaler": {
      "means": [float(value) for value in scaler.mean_],
      "scales": [float(value) for value in scaler.scale_],
      "sampleWeight": "full-data fold influence (not transformed training loss)",
    },
    "coefficients": [float(value) for value in classifier.coef_[0]],
    "intercept": float(classifier.intercept_[0]),
    "probabilityThreshold": threshold,
    "logitThreshold": math.log(threshold / (1.0 - threshold)),
    "actionConvention": "score >= logitThreshold selects CACHE; otherwise DEFAULT",
    "trainerVersion": TRAINER_VERSION,
    "finalizerVersion": FINALIZER_VERSION,
    "libraryVersions": {"numpy": np.__version__,
                        "sklearn": sklearn.__version__},
    "sourceArtifactHashes": {
      str(path.relative_to(repo_root)): _sha256(path) for path in source_paths
    },
    "trainingDatasetSha256": _sha256(repo_root / DATASET_PATH),
    "frozenInputHashes": input_hashes,
  }
  data = canonical_bytes(payload)
  output.parent.mkdir(parents=True, exist_ok=True)
  output.write_bytes(data)
  output.with_name(output.name + ".sha256").write_text(
      hashlib.sha256(data).hexdigest() + "\n", encoding="ascii")
  return payload


def _literal(value: float) -> str:
  if not math.isfinite(value):
    raise ValueError("model constants must be finite")
  return repr(float(value))


def _design_names(artifact: dict) -> list[str]:
  names = ["zK", "zPRatio", "zLogR", "zBody", "zContention"]
  for definition in artifact["expandedFeatures"][5:]:
    if definition["operation"] == "product":
      left, right = definition["inputs"]
      names.append(f"{names[left]} * {names[right]}")
    elif definition["operation"] == "square":
      name = names[definition["input"]]
      names.append(f"{name} * {name}")
    else:
      raise ValueError("unsupported expanded feature")
  return names


def render_java(artifact: dict, artifact_sha256: str) -> str:
  if artifact["schemaVersion"] != ARTIFACT_SCHEMA_VERSION:
    raise ValueError("unsupported logistic runtime artifact schema")
  means = artifact["scaler"]["means"]
  scales = artifact["scaler"]["scales"]
  coefficients = artifact["coefficients"]
  terms = _design_names(artifact)
  if len(terms) != len(coefficients):
    raise ValueError("expanded feature/coefficient count mismatch")
  normalizers = [
    ("zK", "k"), ("zPRatio", "pRatio"), ("zLogR", "logR"),
    ("zBody", "body"), ("zContention", "contention"),
  ]
  lines = [
    "package io.euhedral_execution.core.control_plane;", "",
    "/// Generated by pareto_weight_calibration.logistic_runtime; do not edit.",
    f"/// Candidate: {artifact['candidateId']}",
    f"/// Training artifact SHA-256: {artifact_sha256}",
    f"/// Dataset SHA-256: {artifact['trainingDatasetSha256']}",
    f"/// Exporter version: {EXPORTER_VERSION}",
    "final class ParticipationLogisticModel {",
    f"    static final String CANDIDATE_ID = \"{artifact['candidateId']}\";",
    f"    static final String TRAINING_ARTIFACT_SHA256 = \"{artifact_sha256}\";",
    f"    static final String DATASET_SHA256 = \"{artifact['trainingDatasetSha256']}\";",
    f"    static final String EXPORTER_VERSION = \"{EXPORTER_VERSION}\";",
    f"    static final double PROBABILITY_THRESHOLD = {_literal(artifact['probabilityThreshold'])};",
    f"    static final double LOGIT_THRESHOLD = {_literal(artifact['logitThreshold'])};",
    f"    static final double INTERCEPT = {_literal(artifact['intercept'])};",
    "",
    "    private ParticipationLogisticModel() {}", "",
    "    static boolean shouldCache(",
    "            int k, long productiveHandles, int registeredWorkers, double bodyCostNs, double contention) {",
    "        return score(k, productiveHandles, registeredWorkers, bodyCostNs, contention) >= LOGIT_THRESHOLD;",
    "    }", "",
    "    static double score(int k, long productiveHandles, int registeredWorkers, double bodyCostNs, double contention) {",
    "        double pRatio = (double) productiveHandles / registeredWorkers;",
    "        double logR = Math.log(registeredWorkers);",
    "        double body = Math.log1p(bodyCostNs);",
  ]
  for index, (name, raw) in enumerate(normalizers):
    lines.append(
        f"        double {name} = ({raw} - {_literal(means[index])}) / {_literal(scales[index])};")
  lines.extend(["", "        double result = INTERCEPT;"])
  for coefficient, term in zip(coefficients, terms):
    lines.append(f"        result += {_literal(coefficient)} * {term};")
  lines.extend(["        return result;", "    }", "}", ""])
  return "\n".join(lines)


def _python_score(artifact: dict, k: int, productive: int, workers: int,
    body_cost: float, contention: float) -> tuple[
  list[float], list[float], float]:
  raw = [float(k), productive / float(workers), math.log(workers),
         math.log1p(body_cost), contention]
  scaled = [(value - mean) / scale for value, mean, scale in zip(
      raw, artifact["scaler"]["means"], artifact["scaler"]["scales"])]
  design = list(scaled)
  for definition in artifact["expandedFeatures"][5:]:
    if definition["operation"] == "product":
      left, right = definition["inputs"]
      design.append(scaled[left] * scaled[right])
    else:
      design.append(scaled[definition["input"]] ** 2)
  score = artifact["intercept"] + sum(
      coefficient * feature for coefficient, feature in zip(
          artifact["coefficients"], design))
  return raw, design, score


def _fixture_states(artifact: dict) -> list[
  tuple[int, int, int, float, float, str]]:
  states = []
  levels = (("low", .15, 15.0, .05),
            ("medium", .5, 250.0, .5),
            ("high", .85, 5000.0, .95))
  for workers in (7, 15, 23):
    for ratio_name, ratio, _, _ in levels:
      productive = max(1, min(workers, round(workers * ratio)))
      k = max(2, min(workers, productive + 1))
      for body_name, _, body, _ in levels:
        for contention_name, _, _, contention in levels:
          states.append((
            k, productive, workers, body, contention,
            f"R{workers}-pr-{ratio_name}-body-{body_name}-contention-{contention_name}"))
  states.extend([
    (2, 1, 7, 1.0, .05, "R7-S1-low"),
    (3, 1, 7, 64.0, .4, "R7-S1-mid"),
    (6, 1, 7, 768.0, .9, "R7-S1-high"),
  ])
  threshold = artifact["logitThreshold"]
  for workers in (7, 15, 23):
    found = None
    for k in range(2, workers + 1):
      for productive in (1, max(1, workers // 2), workers):
        for body in (1.0, 64.0, 384.0, 5000.0):
          _, _, low = _python_score(artifact, k, productive, workers, body, 0.0)
          _, _, high = _python_score(artifact, k, productive, workers, body,
                                     1.0)
          if (low - threshold) * (high - threshold) <= 0 and low != high:
            boundary = (threshold - low) / (high - low)
            found = (k, productive, workers, body, boundary)
            break
        if found:
          break
      if found:
        break
    if found:
      k, productive, _, body, boundary = found
      states.append((k, productive, workers, body, max(0.0, boundary - 1e-10),
                     f"R{workers}-boundary-default"))
      states.append((k, productive, workers, body, min(1.0, boundary + 1e-10),
                     f"R{workers}-boundary-cache"))
  return states


def render_fixture(artifact: dict) -> str:
  design_count = len(artifact["coefficients"])
  header = ["case", "k", "productiveHandles", "registeredWorkers",
            "bodyCostNs", "contention", "score", "action"]
  header += [f"feature{i}" for i in range(design_count)]
  lines = ["\t".join(header)]
  for k, productive, workers, body, contention, name in _fixture_states(
      artifact):
    _, design, score = _python_score(
        artifact, k, productive, workers, body, contention)
    action = "CACHE" if score >= artifact["logitThreshold"] else "DEFAULT"
    values = [name, str(k), str(productive), str(workers), _literal(body),
              _literal(contention), _literal(score), action]
    values += [_literal(value) for value in design]
    lines.append("\t".join(values))
  return "\n".join(lines) + "\n"


def export(repo_root: Path, artifact_path: Path, java_path: Path,
    fixture_path: Path) -> None:
  data = artifact_path.read_bytes()
  artifact = json.loads(data)
  digest = hashlib.sha256(data).hexdigest()
  java = render_java(artifact, digest)
  fixture = render_fixture(artifact)
  java_path.parent.mkdir(parents=True, exist_ok=True)
  fixture_path.parent.mkdir(parents=True, exist_ok=True)
  java_path.write_text(java, encoding="ascii", newline="\n")
  fixture_path.write_text(fixture, encoding="ascii", newline="\n")


def main(argv: Sequence[str] | None = None) -> None:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--repo-root", type=Path, default=Path.cwd())
  parser.add_argument("--finalize", action="store_true")
  parser.add_argument("--artifact", type=Path, default=DEFAULT_ARTIFACT)
  parser.add_argument("--java", type=Path, default=DEFAULT_JAVA)
  parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
  args = parser.parse_args(argv)
  root = args.repo_root.resolve()
  resolve = lambda path: path if path.is_absolute() else root / path
  artifact = resolve(args.artifact)
  if args.finalize:
    finalize(root, artifact)
  export(root, artifact, resolve(args.java), resolve(args.fixture))


if __name__ == "__main__":
  main()
