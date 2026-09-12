"""Reconstruct physical-family throughput curves from compact Step 5 evidence."""

from __future__ import annotations

import csv
from dataclasses import asdict, dataclass, field
import hashlib
import math
from pathlib import Path
import re
from typing import Any, Iterable

from pareto_weight_calibration.checksum import ChecksumVerifier

_SURFACE_PAIR = re.compile(r"__s(?P<s>\d+)__wu(?P<wu>\d+)$")
_FAMILY = re.compile(r"Fam_R(?P<r>\d+)_S(?P<s>\d+)_WU(?P<wu>\d+)")


@dataclass(frozen=True)
class CurrentContext:
  """Telemetry available at the currently executing K only."""

  pair_id: str
  current_k: int
  productive_handles: float
  body_log: float
  body_cost_ns: float
  contention: float
  registered_workers: int
  confidence: float = 1.0
  evidence_basis: str = "UNKNOWN"


@dataclass(frozen=True)
class ArmEvidence:
  """One independently aggregated physical arm."""

  pair_id: str
  arm: str
  k: int
  mean_throughput: float
  fork_variance: float
  fork_count: int
  run_path: str
  run_sha256: str
  runtime_commit: str
  topology_id: str
  outcome: str
  evidence_basis: str
  confidence: float

  @property
  def mean_variance(self) -> float:
    return self.fork_variance / max(1, self.fork_count)


@dataclass(frozen=True)
class CurvePoint:
  """A pooled family/K point retaining every independent arm aggregate."""

  k: int
  mean_throughput: float
  mean_variance: float
  uncertainty: float
  measurements: tuple[ArmEvidence, ...]
  adjacent_results: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class FamilyCurve:
  """Ordered observed response for one compatible physical workload family."""

  family_id: str
  registered_workers: int
  source_count: int
  work_units: int
  runtime_commit: str
  topology_id: str
  points: tuple[CurvePoint, ...]
  current_contexts: tuple[CurrentContext, ...]
  provenance_pair_ids: tuple[str, ...]

  @property
  def valid_k_min(self) -> int:
    return 1

  @property
  def valid_k_max(self) -> int:
    return self.registered_workers

  @property
  def representative_productive_handles(self) -> float:
    values = sorted(
        context.productive_handles for context in self.current_contexts)
    if not values:
      return float(max(1, self.source_count))
    middle = len(values) // 2
    if len(values) % 2:
      return values[middle]
    return 0.5 * (values[middle - 1] + values[middle])


@dataclass(frozen=True)
class ObservedPeak:
  """Uncertainty-supported global peak evidence for one observed curve."""

  family_id: str
  best_k: int
  peak_interval_min: int
  peak_interval_max: int
  peak_supported_ks: tuple[int, ...]
  peak_throughput: float
  peak_uncertainty: float
  per_k: tuple[dict[str, Any], ...]


def _verify_sidecar(path: Path) -> str:
  ChecksumVerifier.verify_file(path, require_sidecar=True)
  return ChecksumVerifier.compute_sha256(path)


def _family_id(row: dict[str, str], legacy_families: dict[str, str]) -> str:
  pair_id = row["pairId"]
  legacy = legacy_families.get(pair_id)
  if legacy is not None:
    return legacy
  match = _SURFACE_PAIR.search(pair_id)
  if match is None:
    raise ValueError(
      f"Cannot derive source configuration from pairId {pair_id!r}")
  return f"Fam_R{int(row['registeredWorkers'])}_S{int(match.group('s'))}_WU{int(match.group('wu'))}"


def _same_float(left: float, right: float) -> bool:
  return math.isclose(left, right, rel_tol=1e-12, abs_tol=1e-9)


def _validate_duplicate(existing: ArmEvidence, candidate: ArmEvidence) -> None:
  fields = (
    existing.k == candidate.k,
    _same_float(existing.mean_throughput, candidate.mean_throughput),
    _same_float(existing.fork_variance, candidate.fork_variance),
    existing.fork_count == candidate.fork_count,
    existing.run_sha256 == candidate.run_sha256,
    existing.runtime_commit == candidate.runtime_commit,
    existing.topology_id == candidate.topology_id,
  )
  if not all(fields):
    raise ValueError(
        f"Conflicting duplicate physical arm at {candidate.run_path}: "
        f"existing={existing!r}, candidate={candidate!r}"
    )


def _pool_measurements(measurements: Iterable[ArmEvidence]) -> tuple[
  float, float]:
  """Pools compatible independent arm aggregates after exact-reference deduplication."""
  values = list(measurements)
  if not values:
    raise ValueError("Cannot pool an empty arm set")
  weights: list[float] = []
  for measurement in values:
    variance = measurement.mean_variance
    floor = max(1.0, abs(measurement.mean_throughput) * 1e-6) ** 2
    weights.append(1.0 / max(variance, floor))
  total = math.fsum(weights)
  mean = math.fsum(
      weight * measurement.mean_throughput
      for weight, measurement in zip(weights, values, strict=True)
  ) / total
  return mean, 1.0 / total


def reconstruct_family_curves(
    training_pairs_path: Path,
    legacy_pairs_path: Path,
) -> tuple[list[FamilyCurve], dict[str, Any]]:
  """Reconstructs deterministic family curves and rejects conflicting shared arms."""
  training_digest = _verify_sidecar(training_pairs_path)
  legacy_digest = _verify_sidecar(legacy_pairs_path)

  with legacy_pairs_path.open("r", encoding="utf-8", newline="") as stream:
    legacy_rows = list(csv.DictReader(stream, delimiter="\t"))
  legacy_families = {row["pairId"]: row["familyId"] for row in legacy_rows}

  with training_pairs_path.open("r", encoding="utf-8", newline="") as stream:
    rows = list(csv.DictReader(stream, delimiter="\t"))
  if not rows:
    raise ValueError("Training pairs contain no rows")

  family_arms: dict[str, dict[int, dict[str, ArmEvidence]]] = {}
  family_contexts: dict[str, list[CurrentContext]] = {}
  family_pairs: dict[str, set[str]] = {}
  family_identity: dict[str, tuple[str, str]] = {}
  adjacent: dict[str, dict[int, list[dict[str, Any]]]] = {}
  exact_duplicates = 0
  independent_replicates = 0

  for row in rows:
    family_id = _family_id(row, legacy_families)
    family_match = _FAMILY.fullmatch(family_id)
    if family_match is None:
      raise ValueError(f"Invalid physical family ID {family_id!r}")
    identity = (row["runtimeCommit"], row["topologyId"])
    previous_identity = family_identity.setdefault(family_id, identity)
    if previous_identity != identity:
      raise ValueError(
          f"Family {family_id} mixes runtime/topology cohorts: "
          f"{previous_identity!r} versus {identity!r}"
      )

    pair_id = row["pairId"]
    k = int(row["K"])
    family_pairs.setdefault(family_id, set()).add(pair_id)
    family_contexts.setdefault(family_id, []).append(
        CurrentContext(
            pair_id=pair_id,
            current_k=k,
            productive_handles=float(row["P_active"]),
            body_log=float(row["b_active"]),
            body_cost_ns=float(row["smoothedBodyCostNs_active"]),
            contention=float(row["c_active"]),
            registered_workers=int(row["registeredWorkers"]),
            confidence=float(row["pairWeight"]),
            evidence_basis=row["labelEvidenceBasis"],
        )
    )

    result = {
      "pairId": pair_id,
      "k": k,
      "kMinus1": k - 1,
      "effectiveOutcome": row["effectiveOutcome"],
      "labelEvidenceBasis": row["labelEvidenceBasis"],
      "confidence": float(row["pairWeight"]),
      "basisDeltaThroughput": float(row["basisDeltaThroughput"]),
      "basisUncertainty": float(row["basisUncertainty"]),
    }
    adjacent.setdefault(family_id, {}).setdefault(k, []).append(result)
    adjacent[family_id].setdefault(k - 1, []).append(result)

    arm_specs = (
      (
        "K",
        k,
        "meanThroughput_K",
        "variance_K",
        "forkCount_K",
        "kRunPath",
        "kRunSha256",
      ),
      (
        "K_MINUS_1",
        k - 1,
        "meanThroughput_KMinus1",
        "variance_KMinus1",
        "forkCount_KMinus1",
        "kMinus1RunPath",
        "kMinus1RunSha256",
      ),
    )
    for arm_name, arm_k, mean_key, variance_key, forks_key, path_key, sha_key in arm_specs:
      evidence = ArmEvidence(
          pair_id=pair_id,
          arm=arm_name,
          k=arm_k,
          mean_throughput=float(row[mean_key]),
          fork_variance=float(row[variance_key]),
          fork_count=int(row[forks_key]),
          run_path=row[path_key],
          run_sha256=row[sha_key],
          runtime_commit=row["runtimeCommit"],
          topology_id=row["topologyId"],
          outcome=row["effectiveOutcome"],
          evidence_basis=row["labelEvidenceBasis"],
          confidence=float(row["pairWeight"]),
      )
      by_path = family_arms.setdefault(family_id, {}).setdefault(arm_k, {})
      existing = by_path.get(evidence.run_path)
      if existing is not None:
        _validate_duplicate(existing, evidence)
        exact_duplicates += 1
        continue
      if by_path:
        independent_replicates += 1
      by_path[evidence.run_path] = evidence

  curves: list[FamilyCurve] = []
  for family_id in sorted(family_arms):
    family_match = _FAMILY.fullmatch(family_id)
    assert family_match is not None
    points: list[CurvePoint] = []
    for k in sorted(family_arms[family_id]):
      measurements = tuple(
          family_arms[family_id][k][path]
          for path in sorted(family_arms[family_id][k])
      )
      mean, mean_variance = _pool_measurements(measurements)
      points.append(
          CurvePoint(
              k=k,
              mean_throughput=mean,
              mean_variance=mean_variance,
              uncertainty=2.0 * math.sqrt(mean_variance),
              measurements=measurements,
              adjacent_results=tuple(
                  sorted(
                      adjacent[family_id].get(k, []),
                      key=lambda item: item["pairId"],
                  )
              ),
          )
      )
    runtime_commit, topology_id = family_identity[family_id]
    curves.append(
        FamilyCurve(
            family_id=family_id,
            registered_workers=int(family_match.group("r")),
            source_count=int(family_match.group("s")),
            work_units=int(family_match.group("wu")),
            runtime_commit=runtime_commit,
            topology_id=topology_id,
            points=tuple(points),
            current_contexts=tuple(
                sorted(
                    family_contexts[family_id],
                    key=lambda context: (context.current_k, context.pair_id),
                )
            ),
            provenance_pair_ids=tuple(sorted(family_pairs[family_id])),
        )
    )

  coverage: dict[int, int] = {}
  for curve in curves:
    coverage[len(curve.points)] = coverage.get(len(curve.points), 0) + 1
  diagnostics = {
    "schemaVersion": 1,
    "trainingPairsSha256": training_digest,
    "legacyPairsSha256": legacy_digest,
    "inputPairCount": len(rows),
    "familyCount": len(curves),
    "pointCountDistribution": {
      str(count): coverage[count] for count in sorted(coverage)
    },
    "exactDuplicateReferenceCount": exact_duplicates,
    "independentCompatibleReplicateCount": independent_replicates,
    "counterfactualTelemetryPolicy": (
      "P, body, and contention are attached only to observed current-K contexts. "
      "They are held constant while evaluating counterfactual K values; no candidate-K "
      "telemetry is read or imputed."
    ),
  }
  return curves, diagnostics


def derive_observed_peak(curve: FamilyCurve) -> ObservedPeak:
  """Derives a global uncertainty-supported peak interval using the Step 4 2-SE rule."""
  if not curve.points:
    raise ValueError(f"Family {curve.family_id} has no curve points")
  best = max(curve.points, key=lambda point: (point.mean_throughput, -point.k))
  per_k: list[dict[str, Any]] = []
  globally_supported: list[int] = []
  for point in curve.points:
    uncertainty = 2.0 * math.sqrt(best.mean_variance + point.mean_variance)
    raw_regret = max(0.0, best.mean_throughput - point.mean_throughput)
    supported_regret = max(0.0, raw_regret - uncertainty)
    if supported_regret == 0.0:
      globally_supported.append(point.k)
    per_k.append(
        {
          "k": point.k,
          "throughput": point.mean_throughput,
          "uncertainty": point.uncertainty,
          "rawRegret": raw_regret,
          "supportedRegret": supported_regret,
          "supportedRelativeRegret": (
            supported_regret / best.mean_throughput
            if best.mean_throughput > 0.0
            else 0.0
          ),
          "globallyIndistinguishableFromBest": supported_regret == 0.0,
        }
    )
  # An interval may contain only neighboring measured K values.  Taking the
  # extrema of every statistically indistinguishable sample would silently
  # classify unsampled gaps (or a measured valley) as part of the peak.
  supported_set = set(globally_supported)
  interval_min = best.k
  interval_max = best.k
  while interval_min - 1 in supported_set:
    interval_min -= 1
  while interval_max + 1 in supported_set:
    interval_max += 1
  interval_ks = tuple(range(interval_min, interval_max + 1))
  return ObservedPeak(
      family_id=curve.family_id,
      best_k=best.k,
      peak_interval_min=interval_min,
      peak_interval_max=interval_max,
      peak_supported_ks=interval_ks,
      peak_throughput=best.mean_throughput,
      peak_uncertainty=best.uncertainty,
      per_k=tuple(per_k),
  )


def peak_distance(k: int, peak: ObservedPeak) -> int:
  if peak.peak_interval_min <= k <= peak.peak_interval_max:
    return 0
  return min(abs(k - peak.peak_interval_min), abs(k - peak.peak_interval_max))


def interpolate_peak_regret(k: int, curve: FamilyCurve, peak: ObservedPeak) -> \
dict[str, Any]:
  """Returns measured or explicitly interpolated evidence at an integer K."""
  points = list(curve.points)
  exact = next((point for point in points if point.k == k), None)
  if exact is not None:
    mean = exact.mean_throughput
    variance = exact.mean_variance
    basis = "MEASURED"
  else:
    lower = max((point for point in points if point.k < k), key=lambda p: p.k,
                default=None)
    upper = min((point for point in points if point.k > k), key=lambda p: p.k,
                default=None)
    if lower is None or upper is None:
      return {
        "basis": "UNSUPPORTED_OUTSIDE_OBSERVED_K_RANGE",
        "supportedRegret": None,
        "supportedRelativeRegret": None,
      }
    fraction = (k - lower.k) / float(upper.k - lower.k)
    mean = lower.mean_throughput + fraction * (
        upper.mean_throughput - lower.mean_throughput
    )
    variance = (
                     1.0 - fraction) ** 2 * lower.mean_variance + fraction ** 2 * upper.mean_variance
    basis = "LINEAR_INTERPOLATION_BETWEEN_MEASURED_K"
  uncertainty = 2.0 * math.sqrt(variance + (peak.peak_uncertainty / 2.0) ** 2)
  supported = max(0.0, peak.peak_throughput - mean - uncertainty)
  return {
    "basis": basis,
    "estimatedThroughput": mean,
    "uncertainty": uncertainty,
    "supportedRegret": supported,
    "supportedRelativeRegret": (
      supported / peak.peak_throughput if peak.peak_throughput > 0.0 else 0.0
    ),
  }


def curve_to_dict(curve: FamilyCurve) -> dict[str, Any]:
  return asdict(curve)


def peak_to_dict(peak: ObservedPeak) -> dict[str, Any]:
  return asdict(peak)


def input_hash(paths: Iterable[Path]) -> str:
  digest = hashlib.sha256()
  for path in paths:
    digest.update(path.name.encode("utf-8"))
    digest.update(b"\0")
    digest.update(path.read_bytes())
    digest.update(b"\0")
  return digest.hexdigest()
