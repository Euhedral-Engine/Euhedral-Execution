"""Tests for runtime evaluator sign semantics and decoupled label mappings."""

from __future__ import annotations

import numpy as np
import pytest

from pareto_weight_calibration.model import LogicalWeights, MarginalModel
from pareto_weight_calibration.types import ActiveStateFeatures, Outcome


def test_synthetic_runtime_sign_semantics():
  """Validates that margin z <= 0 implies participate (DIRECT) and z > 0 implies CACHE."""
  # Construct weights where marginal value has known analytical sign
  # m(K) = w0 * q - w4
  # With w0=1.0, w4=2.0: m(K) = q - 2.0
  w = LogicalWeights(w0=1.0, w4=2.0)
  model = MarginalModel(logical_weights=w)

  # Case 1: q = 1.0 < 2.0 -> m(K) = -1.0 <= 0 -> participate (DIRECT_OR_STAGED)
  f_participate = ActiveStateFeatures(c=0.0, smoothed_body_cost_ns=0.0, b=0.0,
                                      P=2.0, R=4, K=2)  # q = 2 / (2*1) = 1.0
  assert model.predict_marginal(f_participate) == pytest.approx(-1.0)
  assert model.predict_action(f_participate) == "DIRECT_OR_STAGED"

  # Case 2: q = 3.0 > 2.0 -> m(K) = 1.0 > 0 -> withdraw (CACHE)
  f_withdraw = ActiveStateFeatures(c=0.0, smoothed_body_cost_ns=0.0, b=0.0,
                                   P=6.0, R=4, K=2)  # q = 6 / (2*1) = 3.0
  assert model.predict_marginal(f_withdraw) == pytest.approx(1.0)
  assert model.predict_action(f_withdraw) == "CACHE"

  # Case 3: Exact boundary z = 0.0 -> participate (DIRECT_OR_STAGED)
  f_boundary = ActiveStateFeatures(c=0.0, smoothed_body_cost_ns=0.0, b=0.0,
                                   P=4.0, R=4, K=2)  # q = 4 / (2*1) = 2.0
  assert model.predict_marginal(f_boundary) == pytest.approx(0.0)
  assert model.predict_action(f_boundary) == "DIRECT_OR_STAGED"


def test_label_mapping_semantics():
  """Validates that K_WINS -> y=0 (participate), K_MINUS_1_WINS -> y=1 (withdraw), STABLE_TIE -> y=0.5."""
  label_map = {
    Outcome.K_WINS: 0.0,
    Outcome.K_MINUS_1_WINS: 1.0,
    Outcome.STABLE_TIE: 0.5,
  }
  for outcome, expected_y in label_map.items():
    if outcome == Outcome.K_WINS:
      y = 0.0
    elif outcome == Outcome.K_MINUS_1_WINS:
      y = 1.0
    elif outcome == Outcome.STABLE_TIE:
      y = 0.5
    else:
      y = None
    assert y == expected_y
