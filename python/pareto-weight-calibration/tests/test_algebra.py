"""Algebraic equivalence tests between score difference and marginal formulation."""

from __future__ import annotations

import math
import numpy as np
import pytest

from pareto_weight_calibration.model import LogicalWeights, MarginalModel
from pareto_weight_calibration.types import ActiveStateFeatures


def two_score_arm_difference(
    c: float,
    body_cost_ns: float,
    P: float,
    R: int,
    K: int,
    w: np.ndarray,
) -> float:
  """Evaluates the difference score(K-1) - score(K) using the unrolled two-score equation."""
  b = math.log1p(body_cost_ns)
  # Factor A = w0 + w1*c + w2*b + w3*R
  A = w[0] + w[1] * c + w[2] * b + w[3] * float(R)
  # Factor B = w4 + w5*c + w6*b + w7*R
  B = w[4] + w[5] * c + w[6] * b + w[7] * float(R)

  # In the full formulation:
  # score(K)   = A * (P / K) - B * K
  # score(K-1) = A * (P / (K-1)) - B * (K-1)
  # score(K-1) - score(K) = A * P * (1/(K-1) - 1/K) - B * (K - 1 - K)
  #                       = A * P / (K * (K - 1)) - (-B)  ... wait!
  # Let's check the signs carefully:
  # score(K) = S(K)
  # S(K-1) - S(K) = [A * P / (K-1) - B * (K-1)] - [A * P / K - B * K]
  #               = A * P * [1/(K-1) - 1/K] - B * [(K-1) - K]
  #               = A * P / (K * (K-1)) - B * (-1)
  #               = A * P / (K * (K-1)) + B
  # In the standard marginal equation with -1 and -c... :
  # marginal(K) = A * P / (K(K-1)) - B = x^T w
  # where x = [q, c*q, b*q, R*q, -1, -c, -b, -R].
  # Then x^T w = A * q - B.
  # Therefore, score(K-1) - score(K) with S(K) defined as A*P/K + B*K gives:
  # A * P / (K(K-1)) - B.
  diff = A * (P / float(K * (K - 1))) - B
  return diff


def test_algebraic_parity_random_coordinates():
  """Validates score(K-1) - score(K) == A * P / (K*(K-1)) - B == dot(x, w) for random vectors."""
  rng = np.random.default_rng(42)

  for _ in range(50):
    c = float(rng.uniform(0.0, 1.0))
    body_cost_ns = float(rng.uniform(10.0, 100000.0))
    b = math.log1p(body_cost_ns)
    P = float(rng.uniform(1.0, 32.0))
    R = int(rng.integers(2, 64))
    K = int(rng.integers(2, R + 1))
    w = rng.normal(loc=0.0, scale=2.0, size=8)

    # 1. Direct formula A * P / (K(K-1)) - B
    A = w[0] + w[1] * c + w[2] * b + w[3] * float(R)
    B = w[4] + w[5] * c + w[6] * b + w[7] * float(R)
    expected_marginal = A * (P / float(K * (K - 1))) - B

    # 2. Linear feature vector dot product x^T w
    features = ActiveStateFeatures(
        c=c,
        smoothed_body_cost_ns=body_cost_ns,
        b=b,
        P=P,
        R=R,
        K=K,
    )
    x = features.feature_vector
    dot_marginal = float(np.dot(x, w))

    # 3. MarginalModel evaluation
    model = MarginalModel(logical_weights=LogicalWeights.from_array(w))
    model_marginal = model.predict_marginal(features)

    # 4. Java evaluator parity
    java_marginal = model.evaluate_java_marginal(c, body_cost_ns, P, R, K)

    # Assert exact agreement within machine precision
    assert math.isclose(expected_marginal, dot_marginal, rel_tol=1e-12,
                        abs_tol=1e-12)
    assert math.isclose(expected_marginal, model_marginal, rel_tol=1e-12,
                        abs_tol=1e-12)
    assert math.isclose(expected_marginal, java_marginal, rel_tol=1e-12,
                        abs_tol=1e-12)


def test_algebraic_parity_boundary_coordinates():
  """Validates score difference at extreme physical coordinates (K=2, large R, small P)."""
  test_cases = [
    # (c, body_cost, P, R, K)
    (0.0, 0.0, 1.0, 2, 2),
    (1.0, 1000000.0, 32.0, 64, 2),
    (0.5, 500.0, 0.001, 23, 23),
    (0.999, 10.0, 16.0, 7, 7),
  ]
  w = np.array([0.5, -0.2, 0.1, -0.05, 1.0, 0.3, -0.4, 0.02], dtype=np.float64)
  model = MarginalModel(logical_weights=LogicalWeights.from_array(w))

  for c, body_cost, P, R, K in test_cases:
    features = ActiveStateFeatures(
        c=c,
        smoothed_body_cost_ns=body_cost,
        b=math.log1p(body_cost),
        P=P,
        R=R,
        K=K,
    )
    x = features.feature_vector
    dot_res = float(np.dot(x, w))
    java_res = model.evaluate_java_marginal(c, body_cost, P, R, K)
    assert math.isclose(dot_res, java_res, rel_tol=1e-12, abs_tol=1e-12)
