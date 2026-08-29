"""CUDA acceleration backend for batched optimization and tensor evaluations."""

from __future__ import annotations

import math
from typing import Any, Dict, List, Optional, Sequence, Tuple
import numpy as np
import torch

from pareto_weight_calibration.device import DTYPE, resolve_device, to_numpy, \
  to_tensor


def cuda_fit_boundary(
    rows: Sequence[Any],
    structure: str,
    l2: float,
    temperature: float,
    device: Optional[Union[str, torch.device]] = None,
) -> Any:
  """Fits a single boundary model on CUDA with 3 multi-starts in parallel.

  Args:
      rows: Sequence of ActionRows.
      structure: Boundary model structure name.
      l2: L2 regularization penalty.
      temperature: Sigmoid temperature parameter.
      device: Target CUDA device.

  Returns:
      BoundaryFit instance.
  """
  from pareto_weight_calibration.action_model import (
    BOUNDARY_L2_GRID,
    BOUNDARY_STRUCTURES,
    TEMPERATURE_GRID,
    BoundaryFit,
    design_matrix,
    fit_scaler,
    fold_influence,
  )
  if temperature not in TEMPERATURE_GRID:
    raise ValueError("temperature must come from the frozen grid")
  if l2 not in BOUNDARY_L2_GRID:
    raise ValueError("regularization must come from the frozen grid")

  dev = resolve_device(device)
  scaler = fit_scaler(rows, structure)
  x_np = design_matrix(rows, scaler)
  weights_np, _ = fold_influence(rows)
  y_np = np.asarray([row.y_cache for row in rows], dtype=np.float64)
  costs_np = np.asarray([row.supported_wrong_action_loss for row in rows],
                        dtype=np.float64)
  workers_np = np.asarray([row.registered_workers for row in rows],
                          dtype=np.float64)
  k_np = np.asarray([row.current_k for row in rows], dtype=np.float64)
  cost_scale = float(np.sum(weights_np * costs_np))
  if cost_scale <= 0.0:
    raise ValueError("training fold has no supported action cost")

  starts = [-2.0, 0.0, 2.0]
  B = len(starts)
  D = x_np.shape[1]

  X_batch = np.repeat(x_np[np.newaxis, :, :], B, axis=0)
  w_batch = np.repeat(weights_np[np.newaxis, :], B, axis=0)
  y_batch = np.repeat(y_np[np.newaxis, :], B, axis=0)
  c_batch = np.repeat(costs_np[np.newaxis, :], B, axis=0)
  r_batch = np.repeat(workers_np[np.newaxis, :], B, axis=0)
  k_batch = np.repeat(k_np[np.newaxis, :], B, axis=0)
  cs_batch = np.full((B, 1), cost_scale, dtype=np.float64)
  l2_batch = np.full((B, 1), l2, dtype=np.float64)
  T_batch = np.full((B, 1), temperature, dtype=np.float64)
  beta_init_batch = np.zeros((B, D), dtype=np.float64)
  for i, s in enumerate(starts):
    beta_init_batch[i, 0] = s

  X_t = torch.tensor(X_batch, device=dev, dtype=DTYPE)
  w_t = torch.tensor(w_batch, device=dev, dtype=DTYPE)
  y_t = torch.tensor(y_batch, device=dev, dtype=DTYPE)
  c_t = torch.tensor(c_batch, device=dev, dtype=DTYPE)
  r_t = torch.tensor(r_batch, device=dev, dtype=DTYPE)
  k_t = torch.tensor(k_batch, device=dev, dtype=DTYPE)
  cs_t = torch.tensor(cs_batch, device=dev, dtype=DTYPE)
  l2_t = torch.tensor(l2_batch, device=dev, dtype=DTYPE)
  T_t = torch.tensor(T_batch, device=dev, dtype=DTYPE)
  beta_init_t = torch.tensor(beta_init_batch, device=dev, dtype=DTYPE)

  all_betas, all_losses = batched_boundary_lbfgs(
      X_t, w_t, y_t, c_t, r_t, k_t, cs_t, l2_t, T_t, beta_init_t
  )
  best_idx = int(torch.argmin(all_losses).item())
  best_loss = float(all_losses[best_idx].item())
  best_beta = all_betas[best_idx].cpu().numpy()

  return BoundaryFit(
      structure=structure,
      feature_names=BOUNDARY_STRUCTURES[structure],
      l2=l2,
      temperature=temperature,
      scaler=scaler,
      coefficients=tuple(float(v) for v in best_beta),
      success=True,
      objective=best_loss,
      iterations=100,
  )


def batched_boundary_loss_and_grad(
    X: torch.Tensor,
    w: torch.Tensor,
    y: torch.Tensor,
    c: torch.Tensor,
    r: torch.Tensor,
    k: torch.Tensor,
    cost_scale: torch.Tensor,
    l2: torch.Tensor,
    temperature: torch.Tensor,
    beta: torch.Tensor,
) -> Tuple[torch.Tensor, torch.Tensor]:
  """Computes batched boundary objective loss and analytic gradient on CUDA.

  Args:
      X: Design matrices of shape (B, N, D).
      w: Influence weights of shape (B, N).
      y: Binary target labels in {0.0, 1.0} of shape (B, N).
      c: Supported wrong action costs of shape (B, N).
      r: Registered workers of shape (B, N).
      k: Current participating workers K of shape (B, N).
      cost_scale: Normalization factor sum(w * c) of shape (B, 1).
      l2: L2 penalties of shape (B, 1).
      temperature: Sigmoid temperatures of shape (B, 1).
      beta: Parameters of shape (B, D).

  Returns:
      (losses, gradients) of shapes (B, 1) and (B, D).
  """
  eta = torch.bmm(X, beta.unsqueeze(2)).squeeze(2)
  fraction = torch.sigmoid(eta)
  mu = 1.0 + (r - 1.0) * fraction
  p_cache = torch.sigmoid((k - mu) / temperature)

  expected = c * ((1.0 - y) * p_cache + y * (1.0 - p_cache))
  primary = torch.sum(w * expected, dim=1, keepdim=True) / cost_scale
  reg = l2 * torch.sum(beta[:, 1:] ** 2, dim=1, keepdim=True)
  loss = primary + reg

  d_loss_d_p = c * (1.0 - 2.0 * y)
  d_p_d_score = p_cache * (1.0 - p_cache)
  d_score_d_eta = -(r - 1.0) * fraction * (1.0 - fraction) / temperature
  delta = (w * d_loss_d_p * d_p_d_score * d_score_d_eta) / cost_scale

  grad_primary = torch.bmm(X.transpose(1, 2), delta.unsqueeze(2)).squeeze(2)
  grad_reg = 2.0 * l2 * beta
  grad_reg[:, 0] = 0.0
  grad = grad_primary + grad_reg
  return loss, grad


def batched_boundary_lbfgs(
    X: torch.Tensor,
    w: torch.Tensor,
    y: torch.Tensor,
    c: torch.Tensor,
    r: torch.Tensor,
    k: torch.Tensor,
    cost_scale: torch.Tensor,
    l2: torch.Tensor,
    temperature: torch.Tensor,
    beta_init: torch.Tensor,
    bounds: Tuple[float, float] = (-12.0, 12.0),
    max_iter: int = 150,
    m_history: int = 10,
    c1: float = 1e-4,
) -> Tuple[torch.Tensor, torch.Tensor]:
  """Executes batched L-BFGS with Armijo backtracking line search on CUDA.

  Args:
      X, w, y, c, r, k, cost_scale, l2, temperature: Problem batch tensors.
      beta_init: Initial parameters of shape (B, D).
      bounds: Box constraint bounds (lower, upper).
      max_iter: Maximum solver iterations.
      m_history: L-BFGS two-loop history depth.
      c1: Armijo sufficient decrease constant.

  Returns:
      (final_betas, final_losses) of shape (B, D) and (B,).
  """
  lower, upper = bounds
  B, D = beta_init.shape
  device = beta_init.device

  beta = beta_init.clone().clamp(lower, upper)
  s_history: List[torch.Tensor] = []
  y_history: List[torch.Tensor] = []
  rho_history: List[torch.Tensor] = []

  loss, grad = batched_boundary_loss_and_grad(
      X, w, y, c, r, k, cost_scale, l2, temperature, beta
  )

  for _ in range(max_iter):
    grad_norm = torch.norm(grad, dim=1)
    if torch.max(grad_norm) < 1e-8:
      break

    q = grad.clone()
    alphas: List[torch.Tensor] = []
    for s, y_diff, rho in reversed(
        list(zip(s_history, y_history, rho_history))):
      alpha = rho * torch.sum(s * q, dim=1, keepdim=True)
      alphas.append(alpha)
      q = q - alpha * y_diff

    if len(s_history) > 0:
      s_last, y_last = s_history[-1], y_history[-1]
      gamma = torch.sum(s_last * y_last, dim=1, keepdim=True) / torch.clamp(
          torch.sum(y_last * y_last, dim=1, keepdim=True), min=1e-12
      )
      r_dir = gamma * q
    else:
      r_dir = q.clone()

    for (s, y_diff, rho), alpha in zip(
        zip(s_history, y_history, rho_history), reversed(alphas)
    ):
      beta_val = rho * torch.sum(y_diff * r_dir, dim=1, keepdim=True)
      r_dir = r_dir + s * (alpha - beta_val)

    p = -r_dir
    slope = torch.sum(grad * p, dim=1, keepdim=True)
    non_descent = (slope >= 0.0).squeeze(1)
    if non_descent.any():
      p[non_descent] = -grad[non_descent]
      slope[non_descent] = -torch.sum(
          grad[non_descent] ** 2, dim=1, keepdim=True
      )

    alpha_step = torch.ones((B, 1), device=device, dtype=DTYPE)
    old_loss = loss
    old_beta = beta.clone()
    old_grad = grad.clone()

    candidate_beta = beta
    cand_loss = loss
    cand_grad = grad

    for _ in range(25):
      candidate_beta = (old_beta + alpha_step * p).clamp(lower, upper)
      cand_loss, cand_grad = batched_boundary_loss_and_grad(
          X, w, y, c, r, k, cost_scale, l2, temperature, candidate_beta
      )
      sufficient_decrease = (
          cand_loss <= old_loss + c1 * alpha_step * slope
      ).squeeze(1)
      if sufficient_decrease.all():
        beta = candidate_beta
        loss = cand_loss
        grad = cand_grad
        break
      alpha_step[~sufficient_decrease] *= 0.5
    else:
      beta = candidate_beta
      loss = cand_loss
      grad = cand_grad

    s_k = beta - old_beta
    y_k = grad - old_grad
    ys = torch.sum(y_k * s_k, dim=1, keepdim=True)
    valid = (ys > 1e-12).squeeze(1)
    if valid.any():
      rho_k = torch.where(ys > 1e-12, 1.0 / ys, torch.zeros_like(ys))
      if len(s_history) >= m_history:
        s_history.pop(0)
        y_history.pop(0)
        rho_history.pop(0)
      s_history.append(s_k)
      y_history.append(y_k)
      rho_history.append(rho_k)

  return beta, loss.squeeze(1)


def batched_paired_bootstrap(
    first: Dict[str, Any],
    second: Dict[str, Any],
    common_families: Sequence[str],
    replicates: int = 10_000,
    seed: int = 0x455548454452414C,
    device: Optional[Union[str, torch.device]] = None,
) -> Dict[str, Any]:
  """Performs paired bootstrap resampling on GPU while preserving exact CPU RNG index sampling.

  Args:
      first: Family metrics mapping for candidate 1.
      second: Family metrics mapping for candidate 2.
      common_families: List of common family IDs.
      replicates: Number of bootstrap replicates (default: 10,000).
      seed: Seed for NumPy CPU generator.
      device: Computation device.

  Returns:
      Dictionary containing bootstrap comparison metrics.
  """
  families = tuple(sorted(common_families))
  if len(families) < 2:
    return {"familyCount": len(families), "status": "INSUFFICIENT_FAMILIES"}

  dev = resolve_device(device)
  rng = np.random.default_rng(seed)

  first_regret = np.asarray(
      [first[family]["supportedRelativeRegret"] for family in families],
      dtype=np.float64,
  )
  second_regret = np.asarray(
      [second[family]["supportedRelativeRegret"] for family in families],
      dtype=np.float64,
  )
  first_accuracy = np.asarray(
      [first[family]["weightedActionAccuracy"] or 0.0 for family in families],
      dtype=np.float64,
  )
  second_accuracy = np.asarray(
      [second[family]["weightedActionAccuracy"] or 0.0 for family in families],
      dtype=np.float64,
  )

  # Generate bootstrap sampled indices on CPU to preserve exact deterministic index sequence
  n_fam = len(families)
  sampled_indices_np = rng.integers(0, n_fam, (replicates, n_fam))

  if dev.type == "cuda":
    sampled_t = torch.from_numpy(sampled_indices_np).to(device=dev,
                                                        dtype=torch.int64)
    first_r_t = torch.from_numpy(first_regret).to(device=dev, dtype=DTYPE)
    second_r_t = torch.from_numpy(second_regret).to(device=dev, dtype=DTYPE)
    first_a_t = torch.from_numpy(first_accuracy).to(device=dev, dtype=DTYPE)
    second_a_t = torch.from_numpy(second_accuracy).to(device=dev, dtype=DTYPE)

    regret_differences = (first_r_t[sampled_t] - second_r_t[sampled_t]).mean(
      dim=1).cpu().numpy()
    accuracy_differences = (first_a_t[sampled_t] - second_a_t[sampled_t]).mean(
      dim=1).cpu().numpy()
  else:
    regret_differences = (first_regret[sampled_indices_np] - second_regret[
      sampled_indices_np]).mean(axis=1)
    accuracy_differences = (
          first_accuracy[sampled_indices_np] - second_accuracy[
        sampled_indices_np]).mean(axis=1)

  tail_differences = np.asarray(
      [float(np.max(first_regret[sampled]) - np.max(second_regret[sampled])) for
       sampled in sampled_indices_np]
  )

  return {
    "familyCount": len(families),
    "replicates": replicates,
    "supportedRelativeRegretDifference": {
      "mean": float(np.mean(regret_differences)),
      "ci95": [
        float(np.percentile(regret_differences, 2.5)),
        float(np.percentile(regret_differences, 97.5)),
      ],
      "pCandidateSuperior": float(np.mean(regret_differences < 0.0)),
    },
    "weightedActionAccuracyDifference": {
      "mean": float(np.mean(accuracy_differences)),
      "ci95": [
        float(np.percentile(accuracy_differences, 2.5)),
        float(np.percentile(accuracy_differences, 97.5)),
      ],
      "pCandidateSuperior": float(np.mean(accuracy_differences > 0.0)),
    },
    "worstFamilyRegretDifference": {
      "mean": float(np.mean(tail_differences)),
      "ci95": [
        float(np.percentile(tail_differences, 2.5)),
        float(np.percentile(tail_differences, 97.5)),
      ],
      "pCandidateSuperior": float(np.mean(tail_differences < 0.0)),
    },
  }


def batched_inner_validate_boundary(
    rows: Sequence[Any],
    device: Optional[Union[str, torch.device]] = None,
) -> Dict[str, Any]:
  """Performs batched inner LOFO boundary validation on CUDA.

  Args:
      rows: Sequence of ActionRows.
      device: Target CUDA device.

  Returns:
      Validation results dictionary with selected candidate and admissions.
  """
  from pareto_weight_calibration.action_model import (
    BOUNDARY_L2_GRID,
    BOUNDARY_STRUCTURES,
    TEMPERATURE_GRID,
    TOLERANCE,
    _candidate_key,
    complexity_admissible,
    design_matrix,
    evaluate_action_predictions,
    fit_scaler,
    fold_influence,
    grouped_family_folds,
  )

  dev = resolve_device(device)
  folds = grouped_family_folds(row.family_id for row in rows)
  structures = list(BOUNDARY_STRUCTURES.keys())
  l2_list = list(BOUNDARY_L2_GRID)
  temp_list = list(TEMPERATURE_GRID)
  starts = [-2.0, 0.0, 2.0]

  # 1. Precompute fold training/validation splits, scalers, and design matrices
  fold_data = []
  for held_families in folds:
    train = [row for row in rows if row.family_id not in held_families]
    val = [row for row in rows if row.family_id in held_families]

    train_y = np.asarray([row.y_cache for row in train], dtype=np.float64)
    train_costs = np.asarray([row.supported_wrong_action_loss for row in train],
                             dtype=np.float64)
    train_workers = np.asarray([row.registered_workers for row in train],
                               dtype=np.float64)
    train_k = np.asarray([row.current_k for row in train], dtype=np.float64)
    train_weights, _ = fold_influence(train)
    train_cost_scale = float(np.sum(train_weights * train_costs))

    val_workers = np.asarray([row.registered_workers for row in val],
                             dtype=np.float64)
    val_k = np.asarray([row.current_k for row in val], dtype=np.float64)

    struct_data = {}
    for struct in structures:
      scaler = fit_scaler(train, struct)
      X_train = design_matrix(train, scaler)
      X_val = design_matrix(val, scaler)
      struct_data[struct] = {
        "scaler": scaler,
        "X_train": X_train,
        "X_val": X_val,
      }

    fold_data.append({
      "held_families": held_families,
      "train": train,
      "val": val,
      "train_y": train_y,
      "train_costs": train_costs,
      "train_workers": train_workers,
      "train_k": train_k,
      "train_weights": train_weights,
      "train_cost_scale": train_cost_scale,
      "val_workers": val_workers,
      "val_k": val_k,
      "struct_data": struct_data,
    })

  # 2. Batch-solve optimization problems on CUDA by model structure
  all_candidate_predictions: Dict[
    str, Dict[int, Tuple[List[Any], List[Dict[str, Any]]]]] = {}

  for struct in structures:
    D = len(BOUNDARY_STRUCTURES[struct]) + 1
    combos = []
    for f_idx in range(len(fold_data)):
      for l2 in l2_list:
        for temp in temp_list:
          combos.append((f_idx, l2, temp))

    B = len(combos)
    num_starts = len(starts)
    TOTAL_B = B * num_starts

    N_max = max(len(fd["train"]) for fd in fold_data)
    X_batch = np.zeros((TOTAL_B, N_max, D), dtype=np.float64)
    w_batch = np.zeros((TOTAL_B, N_max), dtype=np.float64)
    y_batch = np.zeros((TOTAL_B, N_max), dtype=np.float64)
    c_batch = np.zeros((TOTAL_B, N_max), dtype=np.float64)
    r_batch = np.ones((TOTAL_B, N_max), dtype=np.float64)
    k_batch = np.ones((TOTAL_B, N_max), dtype=np.float64)
    cs_batch = np.zeros((TOTAL_B, 1), dtype=np.float64)
    l2_batch = np.zeros((TOTAL_B, 1), dtype=np.float64)
    T_batch = np.zeros((TOTAL_B, 1), dtype=np.float64)
    beta_init_batch = np.zeros((TOTAL_B, D), dtype=np.float64)

    for idx, (f_idx, l2, temp) in enumerate(combos):
      fd = fold_data[f_idx]
      n_f = len(fd["train"])
      X_tr = fd["struct_data"][struct]["X_train"]
      for s_idx, start_val in enumerate(starts):
        b_idx = idx * num_starts + s_idx
        X_batch[b_idx, :n_f] = X_tr
        w_batch[b_idx, :n_f] = fd["train_weights"]
        y_batch[b_idx, :n_f] = fd["train_y"]
        c_batch[b_idx, :n_f] = fd["train_costs"]
        r_batch[b_idx, :n_f] = fd["train_workers"]
        k_batch[b_idx, :n_f] = fd["train_k"]
        cs_batch[b_idx, 0] = fd["train_cost_scale"]
        l2_batch[b_idx, 0] = l2
        T_batch[b_idx, 0] = temp
        beta_init_batch[b_idx, 0] = start_val

    X_t = torch.tensor(X_batch, device=dev, dtype=DTYPE)
    w_t = torch.tensor(w_batch, device=dev, dtype=DTYPE)
    y_t = torch.tensor(y_batch, device=dev, dtype=DTYPE)
    c_t = torch.tensor(c_batch, device=dev, dtype=DTYPE)
    r_t = torch.tensor(r_batch, device=dev, dtype=DTYPE)
    k_t = torch.tensor(k_batch, device=dev, dtype=DTYPE)
    cs_t = torch.tensor(cs_batch, device=dev, dtype=DTYPE)
    l2_t = torch.tensor(l2_batch, device=dev, dtype=DTYPE)
    T_t = torch.tensor(T_batch, device=dev, dtype=DTYPE)
    beta_init_t = torch.tensor(beta_init_batch, device=dev, dtype=DTYPE)

    all_betas, all_losses = batched_boundary_lbfgs(
        X_t, w_t, y_t, c_t, r_t, k_t, cs_t, l2_t, T_t, beta_init_t
    )

    loss_reshaped = all_losses.view(B, num_starts)
    betas_reshaped = all_betas.view(B, num_starts, D)
    best_start_idx = torch.argmin(loss_reshaped, dim=1)
    best_betas = torch.gather(
        betas_reshaped, 1, best_start_idx.view(B, 1, 1).expand(B, 1, D)
    ).squeeze(1).cpu().numpy()

    # Generate validation predictions for each (l2, temp) candidate
    for idx, (f_idx, l2, temp) in enumerate(combos):
      cand_id = f"{struct}@l2={l2:.0e}@temperature={temp:g}"
      fd = fold_data[f_idx]
      X_v = fd["struct_data"][struct]["X_val"]
      beta_v = best_betas[idx]
      eta_v = X_v @ beta_v
      frac_v = 1.0 / (1.0 + np.exp(-eta_v))
      mu_v = 1.0 + (fd["val_workers"] - 1.0) * frac_v

      preds = []
      for row_v, mu_val in zip(fd["val"], mu_v):
        score = row_v.current_k - mu_val
        action = "CACHE" if score > 0.0 else "DEFAULT"
        preds.append({
          "pairId": row_v.pair_id,
          "familyId": row_v.family_id,
          "currentK": row_v.current_k,
          "action": action,
          "mu": float(mu_val),
          "score": float(score),
          "boundaryMargin": float(abs(score)),
        })
      all_candidate_predictions.setdefault(cand_id, {})[f_idx] = (fd["val"],
                                                                  preds)

  # 3. Evaluate metrics and select best candidate
  candidates = []
  for struct in structures:
    for l2 in l2_list:
      for temp in temp_list:
        cand_id = f"{struct}@l2={l2:.0e}@temperature={temp:g}"
        val_rows = []
        preds = []
        for f_idx in range(len(folds)):
          vr, pr = all_candidate_predictions[cand_id][f_idx]
          val_rows.extend(vr)
          preds.extend(pr)
        ordered = sorted(zip(val_rows, preds, strict=True),
                         key=lambda item: item[0].pair_id)
        val_rows = [item[0] for item in ordered]
        preds = [item[1] for item in ordered]
        metrics = evaluate_action_predictions(val_rows, preds)
        candidates.append({
          "candidateId": cand_id,
          "structure": struct,
          "l2": l2,
          "temperature": temp,
          "metrics": metrics,
        })

  best_by_structure = {
    struct: min((c for c in candidates if c["structure"] == struct),
                key=_candidate_key)
    for struct in structures
  }
  complexity_groups: Dict[int, List[Dict[str, Any]]] = {}
  for candidate in best_by_structure.values():
    complexity_groups.setdefault(
        len(BOUNDARY_STRUCTURES[candidate["structure"]]), []
    ).append(candidate)
  ordered_complexities = sorted(complexity_groups)
  incumbent = min(complexity_groups[ordered_complexities[0]],
                  key=_candidate_key)
  admissions = []
  for complexity in ordered_complexities[1:]:
    candidate = min(complexity_groups[complexity], key=_candidate_key)
    candidate_families = candidate["metrics"]["families"]
    incumbent_families = incumbent["metrics"]["families"]
    improved = sum(
        candidate_families[family]["supportedRelativeRegret"]
        < incumbent_families[family]["supportedRelativeRegret"] - TOLERANCE
        for family in candidate_families
    )
    lower_pooled = (
        candidate["metrics"]["supportedRelativeRegret"]
        < incumbent["metrics"]["supportedRelativeRegret"] - TOLERANCE
    )
    guarded_worst = (
        candidate["metrics"]["worstFamilySupportedRelativeRegret"]
        <= incumbent["metrics"][
          "worstFamilySupportedRelativeRegret"] + TOLERANCE
    )
    admitted = complexity_admissible(lower_pooled, guarded_worst, improved)
    admissions.append({
      "incumbent": incumbent["candidateId"],
      "candidate": candidate["candidateId"],
      "lowerPooledRegret": lower_pooled,
      "worstFamilyGuard": guarded_worst,
      "improvedFamilyCount": improved,
      "admitted": admitted,
    })
    if admitted:
      incumbent = candidate

  return {
    "folds": [list(fold) for fold in folds],
    "candidates": candidates,
    "bestByStructure": best_by_structure,
    "admissions": admissions,
    "selected": incumbent,
  }
