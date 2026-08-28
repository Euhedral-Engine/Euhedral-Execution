"""Unimodal throughput shapes and leakage-safe context-to-parameter mappings."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import asdict, dataclass
import math
from typing import Any, Sequence

import numpy as np
from scipy.optimize import least_squares, minimize_scalar
from scipy.special import expit

from pareto_weight_calibration.peak_curves import CurrentContext, FamilyCurve

COORDINATES = ("K", "K_OVER_P", "LOG_K_OVER_P")
CONTEXT_STRUCTURES: dict[str, tuple[str, ...]] = {
  "P": ("logP",),
  "P_R": ("logP", "logR"),
  "P_B": ("logP", "body"),
  "P_C": ("logP", "contention"),
  "P_B_C": ("logP", "body", "contention"),
  "P_B_C_R": ("logP", "body", "contention", "logR"),
}
CONTEXT_VARIANTS = ("PEAK_ONLY", "PEAK_WIDTH", "PEAK_WIDTH_AMPLITUDE")


def horizontal_coordinate(k: np.ndarray, productive_handles: float,
    kind: str) -> np.ndarray:
  if kind not in COORDINATES:
    raise ValueError(f"Unknown horizontal coordinate {kind!r}")
  if productive_handles <= 0.0 or not math.isfinite(productive_handles):
    raise ValueError("productive_handles must be finite and positive")
  values = np.asarray(k, dtype=np.float64)
  if kind == "K":
    return values
  ratio = values / productive_handles
  if kind == "K_OVER_P":
    return ratio
  if np.any(ratio <= 0.0):
    raise ValueError(
      "LOG_K_OVER_P requires positive K/P; runtime K starts at 1")
  return np.log(ratio)


@dataclass(frozen=True)
class ShapeFit:
  shape: str
  coordinate: str
  params: dict[str, float]
  targets: dict[str, float]
  weighted_sse: float
  rmse: float
  success: bool
  point_count: int


class ThroughputShape(ABC):
  """Internal abstraction for one positive, finite-width unimodal response."""

  name: str
  minimum_points: int

  @abstractmethod
  def fit(self, curve: FamilyCurve, coordinate: str) -> ShapeFit:
    raise NotImplementedError

  @abstractmethod
  def predict_throughput(
      self, k: np.ndarray, productive_handles: float, coordinate: str,
      params: dict[str, float]
  ) -> np.ndarray:
    raise NotImplementedError

  @abstractmethod
  def decode_targets(
      self, targets: dict[str, float], x_values: np.ndarray
  ) -> dict[str, float]:
    raise NotImplementedError

  def predict_curve(
      self,
      valid_k: Sequence[int],
      productive_handles: float,
      coordinate: str,
      params: dict[str, float],
  ) -> np.ndarray:
    values = self.predict_throughput(
        np.asarray(valid_k, dtype=np.float64), productive_handles, coordinate,
        params
    )
    if np.any(~np.isfinite(values)):
      raise ValueError(f"{self.name} produced non-finite throughput")
    return values

  def argmax_k(
      self,
      valid_k: Sequence[int],
      productive_handles: float,
      coordinate: str,
      params: dict[str, float],
  ) -> int:
    if not valid_k:
      raise ValueError("valid K domain is empty")
    values = self.predict_curve(valid_k, productive_handles, coordinate, params)
    return int(valid_k[int(np.argmax(values))])

  def validate_unimodal(
      self,
      valid_k: Sequence[int],
      productive_handles: float,
      coordinate: str,
      params: dict[str, float],
  ) -> None:
    values = self.predict_curve(valid_k, productive_handles, coordinate, params)
    differences = np.diff(values)
    signs = np.sign(differences[np.abs(differences) > 1e-10 * max(1.0, float(
      np.max(values)))])
    transitions = sum(1 for left, right in zip(signs, signs[1:]) if
                      left > 0.0 and right < 0.0)
    forbidden = any(
        left < 0.0 and right > 0.0 for left, right in zip(signs, signs[1:]))
    if transitions > 1 or forbidden:
      raise ValueError(
        f"{self.name} is not unimodal over the declared K domain")


def _fit_least_squares(
    residual,
    starts: list[np.ndarray],
    lower: np.ndarray,
    upper: np.ndarray,
) -> tuple[np.ndarray, float]:
  best: tuple[np.ndarray, float] | None = None
  for start in starts:
    result = least_squares(
        residual,
        np.clip(start, lower + 1e-12, upper - 1e-12),
        bounds=(lower, upper),
        method="trf",
        ftol=1e-12,
        xtol=1e-12,
        gtol=1e-12,
        max_nfev=10_000,
    )
    value = float(np.dot(result.fun, result.fun))
    if result.success and np.all(np.isfinite(result.x)) and (
        best is None or value < best[1]
    ):
      best = (result.x, value)
  if best is None:
    raise ValueError("All deterministic nonlinear starts failed")
  return best


def _curve_arrays(curve: FamilyCurve, coordinate: str) -> tuple[
  np.ndarray, ...]:
  k = np.asarray([point.k for point in curve.points], dtype=np.float64)
  y = np.asarray([point.mean_throughput for point in curve.points],
                 dtype=np.float64)
  uncertainty = np.asarray([point.uncertainty for point in curve.points],
                           dtype=np.float64)
  numerical_floor = max(1.0, float(np.max(y)) * 1e-9)
  uncertainty = np.maximum(uncertainty, numerical_floor)
  p_ref = curve.representative_productive_handles
  x = horizontal_coordinate(k, p_ref, coordinate)
  return k, x, y, uncertainty, np.asarray([p_ref], dtype=np.float64)


class LogisticDerivativeShape(ThroughputShape):
  name = "LOGISTIC_DERIVATIVE"
  minimum_points = 4

  @staticmethod
  def _predict_x(x: np.ndarray, params: dict[str, float]) -> np.ndarray:
    sigma = params["sigma"]
    amplitude = params["amplitude"]
    if sigma <= 0.0 or amplitude < 0.0:
      raise ValueError(
        "Logistic derivative requires sigma > 0 and amplitude >= 0")
    s = expit((x - params["mu"]) / sigma)
    return params["baseline"] + amplitude * s * (1.0 - s)

  def fit(self, curve: FamilyCurve, coordinate: str) -> ShapeFit:
    if len(curve.points) < self.minimum_points:
      raise ValueError(
        f"{self.name} requires at least {self.minimum_points} points")
    _, x, y, uncertainty, _ = _curve_arrays(curve, coordinate)
    span = max(float(np.ptp(x)), 1e-6)
    y_min = max(0.0, float(np.min(y)))
    y_range = max(float(np.ptp(y)), float(np.max(y)) * 1e-3, 1.0)

    def decode(theta: np.ndarray) -> dict[str, float]:
      return {
        "baseline": float(theta[0]),
        "amplitude": float(math.exp(theta[1])),
        "mu": float(theta[2]),
        "sigma": float(math.exp(theta[3])),
      }

    def residual(theta: np.ndarray) -> np.ndarray:
      return (self._predict_x(x, decode(theta)) - y) / uncertainty

    starts = [
      np.array([y_min, math.log(4.0 * y_range), mu, math.log(width)])
      for mu in np.linspace(float(np.min(x)), float(np.max(x)), min(5, len(x)))
      for width in (span / 8.0, span / 3.0, span)
    ]
    lower = np.array(
        [0.0, math.log(max(1e-9, y_range * 1e-6)), float(np.min(x)) - span,
         math.log(span * 1e-4)])
    upper = np.array(
        [float(np.max(y)) * 2.0, math.log(max(y_range * 100.0, 1.0)),
         float(np.max(x)) + span, math.log(span * 100.0)])
    theta, weighted_sse = _fit_least_squares(residual, starts, lower, upper)
    params = decode(theta)
    predicted = self._predict_x(x, params)
    targets = {
      "peak": params["mu"],
      "logWidth": math.log(params["sigma"]),
      "logAmplitude": math.log(max(params["amplitude"], 1e-300)),
      "baseline": params["baseline"],
    }
    return ShapeFit(
        self.name,
        coordinate,
        params,
        targets,
        weighted_sse,
        float(np.sqrt(np.mean((predicted - y) ** 2))),
        True,
        len(x),
    )

  def predict_throughput(self, k, productive_handles, coordinate, params):
    return self._predict_x(
      horizontal_coordinate(k, productive_handles, coordinate), params)

  def decode_targets(self, targets: dict[str, float], x_values: np.ndarray) -> \
  dict[str, float]:
    return {
      "baseline": max(0.0, targets["baseline"]),
      "amplitude": math.exp(np.clip(targets["logAmplitude"], -50.0, 50.0)),
      "mu": targets["peak"],
      "sigma": math.exp(np.clip(targets["logWidth"], -20.0, 20.0)),
    }


class AsymmetricSigmoidDerivativeShape(ThroughputShape):
  """Product of rising and falling logistic limbs; positive with one global peak."""

  name = "ASYMMETRIC_SIGMOID_HUMP"
  minimum_points = 5

  @staticmethod
  def _factor(x: np.ndarray, mu: float, sigma_left: float,
      sigma_right: float) -> np.ndarray:
    return expit((x - mu) / sigma_left) * expit((mu - x) / sigma_right)

  @classmethod
  def _peak_offset(cls, sigma_left: float, sigma_right: float) -> float:
    bound = 40.0 * max(sigma_left, sigma_right)
    result = minimize_scalar(
        lambda value: -float(
            cls._factor(np.asarray([value]), 0.0, sigma_left, sigma_right)[0]),
        bounds=(-bound, bound),
        method="bounded",
        options={"xatol": 1e-12},
    )
    if not result.success or not math.isfinite(result.x):
      raise ValueError("Asymmetric sigmoid peak search failed")
    return float(result.x)

  @classmethod
  def _predict_x(cls, x: np.ndarray, params: dict[str, float]) -> np.ndarray:
    sl = params["sigmaLeft"]
    sr = params["sigmaRight"]
    amplitude = params["amplitude"]
    if sl <= 0.0 or sr <= 0.0 or amplitude < 0.0:
      raise ValueError(
        "Asymmetric sigmoid requires positive widths and amplitude")
    return params["baseline"] + amplitude * cls._factor(x, params["mu"], sl, sr)

  def fit(self, curve: FamilyCurve, coordinate: str) -> ShapeFit:
    if len(curve.points) < self.minimum_points:
      raise ValueError(
        f"{self.name} requires at least {self.minimum_points} points")
    _, x, y, uncertainty, _ = _curve_arrays(curve, coordinate)
    span = max(float(np.ptp(x)), 1e-6)
    y_min = max(0.0, float(np.min(y)))
    y_range = max(float(np.ptp(y)), float(np.max(y)) * 1e-3, 1.0)

    def decode(theta: np.ndarray) -> dict[str, float]:
      return {
        "baseline": float(theta[0]),
        "amplitude": float(math.exp(theta[1])),
        "mu": float(theta[2]),
        "sigmaLeft": float(math.exp(theta[3])),
        "sigmaRight": float(math.exp(theta[4])),
      }

    def residual(theta: np.ndarray) -> np.ndarray:
      return (self._predict_x(x, decode(theta)) - y) / uncertainty

    starts = [
      np.array(
          [y_min, math.log(4.0 * y_range), mu, math.log(left), math.log(right)])
      for mu in np.linspace(float(np.min(x)), float(np.max(x)), min(4, len(x)))
      for left, right in (
        (span / 4.0, span / 4.0),
        (span / 8.0, span / 2.0),
        (span / 2.0, span / 8.0),
      )
    ]
    lower = np.array(
        [0.0, math.log(max(1e-9, y_range * 1e-6)), float(np.min(x)) - span,
         math.log(span * 1e-4), math.log(span * 1e-4)])
    upper = np.array(
        [float(np.max(y)) * 2.0, math.log(max(y_range * 100.0, 1.0)),
         float(np.max(x)) + span, math.log(span * 100.0),
         math.log(span * 100.0)])
    theta, weighted_sse = _fit_least_squares(residual, starts, lower, upper)
    params = decode(theta)
    peak = params["mu"] + self._peak_offset(params["sigmaLeft"],
                                            params["sigmaRight"])
    predicted = self._predict_x(x, params)
    targets = {
      "peak": peak,
      "logWidthLeft": math.log(params["sigmaLeft"]),
      "logWidthRight": math.log(params["sigmaRight"]),
      "logAmplitude": math.log(max(params["amplitude"], 1e-300)),
      "baseline": params["baseline"],
    }
    return ShapeFit(self.name, coordinate, params, targets, weighted_sse,
                    float(np.sqrt(np.mean((predicted - y) ** 2))), True, len(x))

  def predict_throughput(self, k, productive_handles, coordinate, params):
    return self._predict_x(
      horizontal_coordinate(k, productive_handles, coordinate), params)

  def decode_targets(self, targets: dict[str, float], x_values: np.ndarray) -> \
  dict[str, float]:
    sl = math.exp(np.clip(targets["logWidthLeft"], -20.0, 20.0))
    sr = math.exp(np.clip(targets["logWidthRight"], -20.0, 20.0))
    offset = self._peak_offset(sl, sr)
    return {
      "baseline": max(0.0, targets["baseline"]),
      "amplitude": math.exp(np.clip(targets["logAmplitude"], -50.0, 50.0)),
      "mu": targets["peak"] - offset,
      "sigmaLeft": sl,
      "sigmaRight": sr,
    }


class LogNormalHumpShape(ThroughputShape):
  name = "LOG_NORMAL_HUMP"
  minimum_points = 4

  @staticmethod
  def _offset(x: np.ndarray) -> float:
    span = max(float(np.ptp(x)), 1e-6)
    return float(np.min(x)) - max(1e-6, 0.01 * span)

  @classmethod
  def _predict_x(cls, x: np.ndarray, params: dict[str, float]) -> np.ndarray:
    width = params["sigma"]
    amplitude = params["amplitude"]
    shifted = x - params["offset"]
    if width <= 0.0 or amplitude < 0.0 or np.any(shifted <= 0.0):
      raise ValueError(
        "Log-normal hump requires positive shifted x, width, and amplitude")
    z = (np.log(shifted) - params["muLog"]) / width
    return params["baseline"] + amplitude * np.exp(-0.5 * z * z)

  def fit(self, curve: FamilyCurve, coordinate: str) -> ShapeFit:
    if len(curve.points) < self.minimum_points:
      raise ValueError(
        f"{self.name} requires at least {self.minimum_points} points")
    _, x, y, uncertainty, _ = _curve_arrays(curve, coordinate)
    offset = self._offset(x)
    shifted = x - offset
    y_min = max(0.0, float(np.min(y)))
    y_range = max(float(np.ptp(y)), float(np.max(y)) * 1e-3, 1.0)

    def decode(theta: np.ndarray) -> dict[str, float]:
      return {
        "baseline": float(theta[0]),
        "amplitude": float(math.exp(theta[1])),
        "muLog": float(theta[2]),
        "sigma": float(math.exp(theta[3])),
        "offset": offset,
      }

    def residual(theta: np.ndarray) -> np.ndarray:
      return (self._predict_x(x, decode(theta)) - y) / uncertainty

    log_shifted = np.log(shifted)
    span = max(float(np.ptp(log_shifted)), 1e-6)
    starts = [
      np.array([y_min, math.log(y_range), mu, math.log(width)])
      for mu in
      np.linspace(float(np.min(log_shifted)), float(np.max(log_shifted)),
                  min(5, len(x)))
      for width in (span / 8.0, span / 3.0, span)
    ]
    lower = np.array([0.0, math.log(max(1e-9, y_range * 1e-6)),
                      float(np.min(log_shifted)) - span, math.log(span * 1e-4)])
    upper = np.array(
        [float(np.max(y)) * 2.0, math.log(max(y_range * 100.0, 1.0)),
         float(np.max(log_shifted)) + span, math.log(span * 100.0)])
    theta, weighted_sse = _fit_least_squares(residual, starts, lower, upper)
    params = decode(theta)
    peak = offset + math.exp(params["muLog"])
    predicted = self._predict_x(x, params)
    targets = {
      "peak": peak,
      "logWidth": math.log(params["sigma"]),
      "logAmplitude": math.log(max(params["amplitude"], 1e-300)),
      "baseline": params["baseline"],
    }
    return ShapeFit(self.name, coordinate, params, targets, weighted_sse,
                    float(np.sqrt(np.mean((predicted - y) ** 2))), True, len(x))

  def predict_throughput(self, k, productive_handles, coordinate, params):
    return self._predict_x(
      horizontal_coordinate(k, productive_handles, coordinate), params)

  def decode_targets(self, targets: dict[str, float], x_values: np.ndarray) -> \
  dict[str, float]:
    offset = self._offset(x_values)
    peak = max(targets["peak"], offset + 1e-9)
    return {
      "baseline": max(0.0, targets["baseline"]),
      "amplitude": math.exp(np.clip(targets["logAmplitude"], -50.0, 50.0)),
      "muLog": math.log(peak - offset),
      "sigma": math.exp(np.clip(targets["logWidth"], -20.0, 20.0)),
      "offset": offset,
    }


def _pava(values: np.ndarray, weights: np.ndarray,
    increasing: bool) -> np.ndarray:
  source = values if increasing else -values
  blocks: list[list[float]] = []
  for index, (value, weight) in enumerate(zip(source, weights, strict=True)):
    blocks.append([float(value), float(weight), float(index), float(index)])
    while len(blocks) >= 2 and blocks[-2][0] > blocks[-1][0]:
      right = blocks.pop()
      left = blocks.pop()
      total = left[1] + right[1]
      blocks.append([
        (left[0] * left[1] + right[0] * right[1]) / total,
        total,
        left[2],
        right[3],
      ])
  fitted = np.empty_like(source, dtype=np.float64)
  for value, _, start, end in blocks:
    fitted[int(start): int(end) + 1] = value
  return fitted if increasing else -fitted


class UnimodalIsotonicReference:
  name = "UNIMODAL_ISOTONIC_REFERENCE"
  minimum_points = 3

  def fit(self, curve: FamilyCurve) -> dict[str, Any]:
    if len(curve.points) < self.minimum_points:
      raise ValueError(
        f"{self.name} requires at least {self.minimum_points} points")
    y = np.asarray([point.mean_throughput for point in curve.points],
                   dtype=np.float64)
    weights = np.asarray(
        [1.0 / max(point.mean_variance, 1.0) for point in curve.points],
        dtype=np.float64,
    )
    best: tuple[np.ndarray, float, int] | None = None
    for mode in range(len(y)):
      left = _pava(y[: mode + 1], weights[: mode + 1], increasing=True)
      right = _pava(y[mode:], weights[mode:], increasing=False)
      mode_value = (left[-1] * weights[mode] + right[0] * weights[mode]) / (
            2.0 * weights[mode])
      fitted = np.concatenate([left[:-1], np.asarray([mode_value]), right[1:]])
      error = float(np.dot(weights, (fitted - y) ** 2))
      if best is None or error < best[1] - 1e-12 or (
          abs(error - best[1]) <= 1e-12 and mode < best[2]
      ):
        best = (fitted, error, mode)
    assert best is not None
    return {
      "k": [point.k for point in curve.points],
      "fittedThroughput": best[0].tolist(),
      "weightedSse": best[1],
      "modeK": curve.points[best[2]].k,
    }


def _raw_context(context: CurrentContext) -> dict[str, float]:
  if context.productive_handles <= 0.0:
    raise ValueError(
      "Counterfactual runtime context requires productive_handles > 0")
  return {
    "logP": math.log1p(context.productive_handles),
    "body": context.body_log,
    "contention": context.contention,
    "logR": math.log(float(context.registered_workers)),
  }


@dataclass(frozen=True)
class RegressionField:
  names: tuple[str, ...]
  means: tuple[float, ...]
  scales: tuple[float, ...]
  coefficients: tuple[float, ...]


@dataclass(frozen=True)
class ContextParameterMapper:
  structure: str
  variant: str
  fields: dict[str, RegressionField]
  ridge: float = 1e-3

  @staticmethod
  def _field_names(target: str, structure: str, variant: str) -> tuple[
    str, ...]:
    if target == "peak":
      return CONTEXT_STRUCTURES[structure]
    if target.startswith("logWidth") and variant in {
      "PEAK_WIDTH",
      "PEAK_WIDTH_AMPLITUDE",
    }:
      return CONTEXT_STRUCTURES[structure]
    if target == "logAmplitude" and variant == "PEAK_WIDTH_AMPLITUDE":
      return CONTEXT_STRUCTURES[structure]
    return ()

  @classmethod
  def fit(
      cls,
      structure: str,
      variant: str,
      samples: Sequence[tuple[CurrentContext, dict[str, float], float]],
      ridge: float = 1e-3,
  ) -> "ContextParameterMapper":
    if structure not in CONTEXT_STRUCTURES:
      raise ValueError(f"Unknown context structure {structure!r}")
    if variant not in CONTEXT_VARIANTS:
      raise ValueError(f"Unknown context variant {variant!r}")
    if not samples:
      raise ValueError("Cannot fit context mapping without samples")
    target_names = sorted(samples[0][1])
    fields: dict[str, RegressionField] = {}
    for target in target_names:
      names = cls._field_names(target, structure, variant)
      raw = np.asarray(
          [[_raw_context(context)[name] for name in names] for context, _, _ in
           samples],
          dtype=np.float64,
      )
      if names:
        means = np.mean(raw, axis=0)
        scales = np.std(raw, axis=0)
        scales = np.where(scales > 1e-12, scales, 1.0)
        normalized = (raw - means) / scales
      else:
        means = np.zeros(0, dtype=np.float64)
        scales = np.ones(0, dtype=np.float64)
        normalized = np.empty((len(samples), 0), dtype=np.float64)
      design = np.column_stack([np.ones(len(samples)), normalized])
      response = np.asarray([targets[target] for _, targets, _ in samples],
                            dtype=np.float64)
      weights = np.asarray([weight for _, _, weight in samples],
                           dtype=np.float64)
      weighted_design = design * np.sqrt(weights)[:, None]
      weighted_response = response * np.sqrt(weights)
      penalty = np.eye(design.shape[1], dtype=np.float64) * ridge
      penalty[0, 0] = 0.0
      coefficients = np.linalg.solve(
          weighted_design.T @ weighted_design + penalty,
          weighted_design.T @ weighted_response,
      )
      fields[target] = RegressionField(
          names=names,
          means=tuple(float(value) for value in means),
          scales=tuple(float(value) for value in scales),
          coefficients=tuple(float(value) for value in coefficients),
      )
    return cls(structure=structure, variant=variant, fields=fields, ridge=ridge)

  def predict(self, context: CurrentContext) -> dict[str, float]:
    raw = _raw_context(context)
    targets: dict[str, float] = {}
    for target, field in self.fields.items():
      values = [
        (raw[name] - mean) / scale
        for name, mean, scale in zip(
            field.names, field.means, field.scales, strict=True
        )
      ]
      targets[target] = field.coefficients[0] + math.fsum(
          coefficient * value
          for coefficient, value in
          zip(field.coefficients[1:], values, strict=True)
      )
    return targets

  def serialize(self) -> dict[str, Any]:
    return {
      "structure": self.structure,
      "variant": self.variant,
      "ridge": self.ridge,
      "fields": {name: asdict(field) for name, field in
                 sorted(self.fields.items())},
    }


@dataclass(frozen=True)
class PeakCandidateModel:
  shape_name: str
  coordinate: str
  context_structure: str
  context_variant: str
  mapper: ContextParameterMapper

  def predict_curve(
      self,
      shape: ThroughputShape,
      context: CurrentContext,
      valid_k: Sequence[int],
  ) -> tuple[dict[str, float], np.ndarray]:
    if context.registered_workers < 1:
      raise ValueError("registered_workers must be positive")
    if any(k < 1 or k > context.registered_workers for k in valid_k):
      raise ValueError("K outside runtime bounds")
    x_values = horizontal_coordinate(
        np.asarray(valid_k, dtype=np.float64),
        context.productive_handles,
        self.coordinate,
    )
    params = shape.decode_targets(self.mapper.predict(context), x_values)
    shape.validate_unimodal(valid_k, context.productive_handles,
                            self.coordinate, params)
    return params, shape.predict_curve(
        valid_k, context.productive_handles, self.coordinate, params
    )

  def argmax_k(
      self,
      shape: ThroughputShape,
      context: CurrentContext,
      valid_k: Sequence[int],
  ) -> int:
    _, curve = self.predict_curve(shape, context, valid_k)
    return int(valid_k[int(np.argmax(curve))])


SHAPES: dict[str, ThroughputShape] = {
  LogisticDerivativeShape.name: LogisticDerivativeShape(),
  AsymmetricSigmoidDerivativeShape.name: AsymmetricSigmoidDerivativeShape(),
  LogNormalHumpShape.name: LogNormalHumpShape(),
}
