"""Tests for PyTorch CUDA GPU acceleration and CPU fallback parity."""

from __future__ import annotations

import copy
from pathlib import Path
from unittest.mock import patch
import numpy as np
import pytest
import torch

from pareto_weight_calibration.action_model import (
  BOUNDARY_L2_GRID,
  BOUNDARY_STRUCTURES,
  TEMPERATURE_GRID,
  ActionRow,
  fit_boundary,
  inner_validate_boundary,
)
from pareto_weight_calibration.action_pipeline import (
  _paired_bootstrap,
  build_action_rows,
)
from pareto_weight_calibration.cuda_backend import (
  batched_boundary_loss_and_grad,
  batched_inner_validate_boundary,
  batched_paired_bootstrap,
)
from pareto_weight_calibration.device import (
  DTYPE,
  is_cuda,
  is_cuda_available,
  resolve_device,
  to_numpy,
  to_tensor,
)
from pareto_weight_calibration.loss import (
  compute_probabilities,
  loss_gradient,
  loss_hessian,
  unclipped_binary_cross_entropy,
  weighted_regularized_loss,
)


@pytest.fixture
def sample_action_rows() -> list[ActionRow]:
  repo_root = Path(__file__).resolve().parent.parent.parent.parent
  pairs_tsv = repo_root / "experiments/pareto_training_step5/training_pairs.tsv"
  family_curves_json = repo_root / "experiments/pareto_peak_training/family_curves.json"
  if pairs_tsv.is_file() and family_curves_json.is_file():
    rows, _ = build_action_rows(pairs_tsv, family_curves_json)
    return rows

  # Synthetic fallback for isolated test environments
  synthetic_rows = []
  rng = np.random.default_rng(42)
  for i in range(120):
    fam = f"fam_{i % 10}"
    p_ratio = float(rng.uniform(0.1, 1.0))
    log_r = float(np.log(rng.choice([2, 4, 8, 16])))
    body = float(rng.uniform(1.0, 10.0))
    r = int(np.exp(log_r))
    current_k = int(rng.integers(1, r + 1))
    y = float(rng.choice([0.0, 1.0]))
    cost = float(rng.uniform(0.01, 0.5))
    weight = float(rng.uniform(0.5, 1.0))
    synthetic_rows.append(
        ActionRow(
            pair_id=f"pair_{i}",
            family_id=fam,
            active_features={"pRatio": p_ratio, "logR": log_r, "body": body},
            registered_workers=r,
            current_k=current_k,
            y_cache=y,
            supported_wrong_action_loss=cost,
            evidence_weight=weight,
            family_weight_sum=1.0,
            family_influence=weight,
        )
    )
  return synthetic_rows


def test_device_resolution_cpu():
  dev = resolve_device("cpu")
  assert dev.type == "cpu"
  assert not is_cuda("cpu")


def test_device_resolution_auto():
  dev = resolve_device("auto")
  if torch.cuda.is_available():
    assert dev.type == "cuda"
    assert is_cuda("auto")
  else:
    assert dev.type == "cpu"


def test_device_resolution_cuda_unavailable_mock():
  with patch("torch.cuda.is_available", return_value=False):
    dev = resolve_device("auto")
    assert dev.type == "cpu"
    with pytest.raises(RuntimeError,
                       match="CUDA was requested, but CUDA is not available"):
      resolve_device("cuda")


@pytest.mark.skipif(not torch.cuda.is_available(),
                    reason="CUDA not available on host")
def test_device_resolution_cuda():
  dev = resolve_device("cuda")
  assert dev.type == "cuda"
  assert is_cuda("cuda")
  assert is_cuda(dev)


def test_tensor_conversion():
  arr = np.array([1.0, 2.0, 3.0], dtype=np.float64)
  t = to_tensor(arr, device="cpu")
  assert isinstance(t, torch.Tensor)
  assert t.dtype == torch.float64
  back = to_numpy(t)
  np.testing.assert_array_equal(arr, back)


@pytest.mark.skipif(not torch.cuda.is_available(),
                    reason="CUDA not available on host")
def test_loss_functions_cuda_parity():
  rng = np.random.default_rng(123)
  N, D = 100, 5
  X = rng.standard_normal((N, D))
  w = rng.standard_normal(D)
  y = rng.uniform(0.0, 1.0, size=N)
  u = rng.uniform(0.1, 1.0, size=N)
  l2_reg = 1e-3

  # CPU evaluations
  cpu_loss = weighted_regularized_loss(w, X, y, u, l2_reg)
  cpu_grad = loss_gradient(w, X, y, u, l2_reg)
  cpu_hess = loss_hessian(w, X, y, u, l2_reg)

  # CUDA evaluations
  w_cuda = torch.tensor(w, device="cuda", dtype=DTYPE)
  X_cuda = torch.tensor(X, device="cuda", dtype=DTYPE)
  y_cuda = torch.tensor(y, device="cuda", dtype=DTYPE)
  u_cuda = torch.tensor(u, device="cuda", dtype=DTYPE)

  cuda_loss = weighted_regularized_loss(w_cuda, X_cuda, y_cuda, u_cuda, l2_reg)
  cuda_grad = to_numpy(loss_gradient(w_cuda, X_cuda, y_cuda, u_cuda, l2_reg))
  cuda_hess = to_numpy(loss_hessian(w_cuda, X_cuda, y_cuda, u_cuda, l2_reg))

  assert np.isclose(cpu_loss, cuda_loss, atol=1e-12, rtol=1e-12)
  np.testing.assert_allclose(cpu_grad, cuda_grad, atol=1e-12, rtol=1e-12)
  np.testing.assert_allclose(cpu_hess, cuda_hess, atol=1e-12, rtol=1e-12)


@pytest.mark.skipif(not torch.cuda.is_available(),
                    reason="CUDA not available on host")
def test_batched_boundary_loss_and_grad_parity(sample_action_rows):
  from pareto_weight_calibration.action_model import (
    design_matrix,
    fit_scaler,
    fold_influence,
  )
  from scipy.special import expit

  rows = sample_action_rows[:50]
  struct = "B_PR_BODY"
  scaler = fit_scaler(rows, struct)
  x_np = design_matrix(rows, scaler)
  weights_np, _ = fold_influence(rows)
  y_np = np.asarray([r.y_cache for r in rows], dtype=np.float64)
  costs_np = np.asarray([r.supported_wrong_action_loss for r in rows],
                        dtype=np.float64)
  workers_np = np.asarray([r.registered_workers for r in rows],
                          dtype=np.float64)
  k_np = np.asarray([r.current_k for r in rows], dtype=np.float64)
  cost_scale = float(np.sum(weights_np * costs_np))
  l2 = 1e-3
  temp = 0.5
  D = x_np.shape[1]

  beta_np = np.array([0.5, -0.2, 0.1, 0.4])

  # CPU reference computation
  eta = x_np @ beta_np
  fraction = expit(eta)
  mu = 1.0 + (workers_np - 1.0) * fraction
  p_cache = expit((k_np - mu) / temp)
  expected = costs_np * ((1.0 - y_np) * p_cache + y_np * (1.0 - p_cache))
  ref_loss = float(np.sum(weights_np * expected) / cost_scale) + l2 * float(
    np.dot(beta_np[1:], beta_np[1:]))

  d_loss_d_p = costs_np * (1.0 - 2.0 * y_np)
  d_p_d_score = p_cache * (1.0 - p_cache)
  d_score_d_eta = -(workers_np - 1.0) * fraction * (1.0 - fraction) / temp
  ref_grad = x_np.T @ (
        weights_np * d_loss_d_p * d_p_d_score * d_score_d_eta) / cost_scale
  ref_grad[1:] += 2.0 * l2 * beta_np[1:]

  # CUDA batched computation
  X_t = torch.tensor(x_np[np.newaxis, :, :], device="cuda", dtype=DTYPE)
  w_t = torch.tensor(weights_np[np.newaxis, :], device="cuda", dtype=DTYPE)
  y_t = torch.tensor(y_np[np.newaxis, :], device="cuda", dtype=DTYPE)
  c_t = torch.tensor(costs_np[np.newaxis, :], device="cuda", dtype=DTYPE)
  r_t = torch.tensor(workers_np[np.newaxis, :], device="cuda", dtype=DTYPE)
  k_t = torch.tensor(k_np[np.newaxis, :], device="cuda", dtype=DTYPE)
  cs_t = torch.tensor([[cost_scale]], device="cuda", dtype=DTYPE)
  l2_t = torch.tensor([[l2]], device="cuda", dtype=DTYPE)
  T_t = torch.tensor([[temp]], device="cuda", dtype=DTYPE)
  beta_t = torch.tensor(beta_np[np.newaxis, :], device="cuda", dtype=DTYPE)

  loss_t, grad_t = batched_boundary_loss_and_grad(
      X_t, w_t, y_t, c_t, r_t, k_t, cs_t, l2_t, T_t, beta_t
  )

  cuda_loss = float(loss_t.item())
  cuda_grad = to_numpy(grad_t).squeeze()

  assert np.isclose(ref_loss, cuda_loss, atol=1e-14, rtol=1e-14)
  np.testing.assert_allclose(ref_grad, cuda_grad, atol=1e-14, rtol=1e-14)


@pytest.mark.skipif(not torch.cuda.is_available(),
                    reason="CUDA not available on host")
def test_fit_boundary_parity(sample_action_rows):
  rows = sample_action_rows
  struct = "B_PR_BODY"
  l2 = 1e-3
  temp = 0.5

  cpu_fit = fit_boundary(rows, struct, l2, temp, device="cpu")
  cuda_fit = fit_boundary(rows, struct, l2, temp, device="cuda")

  assert cpu_fit.success
  assert cuda_fit.success
  # Both should find identical or essentially identical minimal loss objectives
  assert np.isclose(cpu_fit.objective, cuda_fit.objective, atol=1e-4, rtol=1e-4)
  np.testing.assert_allclose(cpu_fit.coefficients, cuda_fit.coefficients,
                             atol=1e-2, rtol=1e-2)


@pytest.mark.skipif(not torch.cuda.is_available(),
                    reason="CUDA not available on host")
def test_inner_validate_boundary_selected_model_parity(sample_action_rows):
  rows = sample_action_rows
  cpu_result = inner_validate_boundary(rows, device="cpu")
  cuda_result = inner_validate_boundary(rows, device="cuda")

  assert cpu_result["selected"]["candidateId"] == cuda_result["selected"][
    "candidateId"]
  assert cpu_result["selected"]["structure"] == cuda_result["selected"][
    "structure"]
  assert cpu_result["selected"]["l2"] == cuda_result["selected"]["l2"]
  assert cpu_result["selected"]["temperature"] == cuda_result["selected"][
    "temperature"]

  # Check best candidate by structure agreement
  for struct in BOUNDARY_STRUCTURES:
    cpu_best = cpu_result["bestByStructure"][struct]
    cuda_best = cuda_result["bestByStructure"][struct]
    assert cpu_best["candidateId"] == cuda_best["candidateId"]


@pytest.mark.skipif(not torch.cuda.is_available(),
                    reason="CUDA not available on host")
def test_paired_bootstrap_parity():
  families = [f"family_{i:02d}" for i in range(49)]
  rng = np.random.default_rng(999)
  first = {
    fam: {
      "supportedRelativeRegret": float(rng.uniform(0.01, 0.2)),
      "weightedActionAccuracy": float(rng.uniform(0.8, 0.99)),
    }
    for fam in families
  }
  second = {
    fam: {
      "supportedRelativeRegret": float(rng.uniform(0.01, 0.2)),
      "weightedActionAccuracy": float(rng.uniform(0.8, 0.99)),
    }
    for fam in families
  }

  cpu_boot = _paired_bootstrap(first, second, families, device="cpu")
  cuda_boot = _paired_bootstrap(first, second, families, device="cuda")

  assert cpu_boot["familyCount"] == cuda_boot["familyCount"]
  assert cpu_boot["replicates"] == cuda_boot["replicates"]
  assert cpu_boot["seed"] == cuda_boot["seed"]

  # Means and 95% CI intervals should match to high precision
  np.testing.assert_allclose(
      cpu_boot["familyBalancedSupportedRelativeRegret"]["bootstrapMean"],
      cuda_boot["familyBalancedSupportedRelativeRegret"]["bootstrapMean"],
      atol=1e-14,
  )
  np.testing.assert_allclose(
      cpu_boot["familyBalancedSupportedRelativeRegret"]["percentile95Interval"],
      cuda_boot["familyBalancedSupportedRelativeRegret"][
        "percentile95Interval"],
      atol=1e-14,
  )
  np.testing.assert_allclose(
      cpu_boot["familyBalancedWeightedActionAccuracy"]["bootstrapMean"],
      cuda_boot["familyBalancedWeightedActionAccuracy"]["bootstrapMean"],
      atol=1e-14,
  )
  np.testing.assert_allclose(
      cpu_boot["p90FamilySupportedRelativeRegret"]["bootstrapMean"],
      cuda_boot["p90FamilySupportedRelativeRegret"]["bootstrapMean"],
      atol=1e-14,
  )


@pytest.mark.skipif(not torch.cuda.is_available(),
                    reason="CUDA not available on host")
def test_deterministic_cuda_runs(sample_action_rows):
  rows = sample_action_rows
  run1 = inner_validate_boundary(rows, device="cuda")
  run2 = inner_validate_boundary(rows, device="cuda")

  assert run1["selected"]["candidateId"] == run2["selected"]["candidateId"]
  for c1, c2 in zip(run1["candidates"], run2["candidates"]):
    assert c1["candidateId"] == c2["candidateId"]
    assert np.isclose(
        c1["metrics"]["supportedRelativeRegret"],
        c2["metrics"]["supportedRelativeRegret"],
        atol=1e-15,
    )
