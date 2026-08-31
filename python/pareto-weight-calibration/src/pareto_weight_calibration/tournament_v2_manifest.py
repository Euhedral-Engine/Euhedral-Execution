"""Deterministic, data-independent candidate manifest for Tournament V2."""

from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path

from pareto_weight_calibration.tournament_v2_models import WEIGHT_TRANSFORMS

SEED = 20260830
INNER_FOLDS = 4
THRESHOLDS = (0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40,
              0.45, 0.50, 0.55, 0.60, 0.65, 0.70)
MODEL_ORDER = (
  "direct_linear", "logistic", "gam_spline", "cart", "random_forest",
  "extra_trees", "hist_boost", "xgboost", "catboost", "svm", "mlp",
)


def _lattices() -> dict[str, list[dict]]:
  """Representative frozen lattice; deliberately not a Cartesian product."""
  return {
    "direct_linear": [
      {"l2": 0.001, "temperature": 0.5},
      {"l2": 0.01, "temperature": 1.0},
    ],
    "logistic": [
      {"geometry": "main", "penalty": "l2", "C": 0.03},
      {"geometry": "main", "penalty": "l2", "C": 0.1},
      {"geometry": "main", "penalty": "l2", "C": 1.0},
      {"geometry": "interactions", "penalty": "l2", "C": 0.1},
      {"geometry": "quadratic", "penalty": "l2", "C": 0.1},
      {"geometry": "interactions", "penalty": "elasticnet", "C": 0.3,
       "l1_ratio": 0.25},
    ],
    "gam_spline": [
      {"n_knots": 3, "degree": 2, "knots": "uniform", "C": 0.03},
      {"n_knots": 3, "degree": 2, "knots": "quantile", "C": 0.1},
      {"n_knots": 4, "degree": 2, "knots": "uniform", "C": 0.1},
      {"n_knots": 4, "degree": 3, "knots": "quantile", "C": 0.03},
      {"n_knots": 5, "degree": 2, "knots": "uniform", "C": 1.0},
    ],
    "cart": [
      {"max_depth": 1, "min_samples_leaf": 2, "ccp_alpha": 0.0},
      {"max_depth": 2, "min_samples_leaf": 4, "ccp_alpha": 0.0},
      {"max_depth": 3, "min_samples_leaf": 6, "ccp_alpha": 0.001},
      {"max_depth": 4, "min_samples_leaf": 8, "ccp_alpha": 0.005},
      {"max_depth": 5, "min_samples_leaf": 12, "ccp_alpha": 0.01},
    ],
    "random_forest": [
      {"n_estimators": 80, "max_depth": 2, "min_samples_leaf": 2,
       "max_features": "sqrt"},
      {"n_estimators": 120, "max_depth": 3, "min_samples_leaf": 4,
       "max_features": 1.0},
      {"n_estimators": 200, "max_depth": 4, "min_samples_leaf": 6,
       "max_features": "sqrt"},
      {"n_estimators": 400, "max_depth": 5, "min_samples_leaf": 8,
       "max_features": 0.8},
      {"n_estimators": 200, "max_depth": 3, "min_samples_leaf": 12,
       "max_features": 1.0},
    ],
    "extra_trees": [
      {"n_estimators": 80, "max_depth": 2, "min_samples_leaf": 2,
       "max_features": "sqrt"},
      {"n_estimators": 120, "max_depth": 3, "min_samples_leaf": 4,
       "max_features": 1.0},
      {"n_estimators": 200, "max_depth": 4, "min_samples_leaf": 6,
       "max_features": "sqrt"},
      {"n_estimators": 400, "max_depth": 5, "min_samples_leaf": 8,
       "max_features": 0.8},
      {"n_estimators": 200, "max_depth": 3, "min_samples_leaf": 12,
       "max_features": 1.0},
    ],
    "hist_boost": [
      {"max_depth": 1, "max_leaf_nodes": 2, "learning_rate": 0.01,
       "max_iter": 400, "min_samples_leaf": 2, "l2_regularization": 0},
      {"max_depth": 1, "max_leaf_nodes": 2, "learning_rate": 0.20,
       "max_iter": 40, "min_samples_leaf": 4, "l2_regularization": 0.1},
      {"max_depth": 2, "max_leaf_nodes": 4, "learning_rate": 0.03,
       "max_iter": 200, "min_samples_leaf": 6, "l2_regularization": 1},
      {"max_depth": 2, "max_leaf_nodes": 4, "learning_rate": 0.05,
       "max_iter": 120, "min_samples_leaf": 8, "l2_regularization": 5},
      {"max_depth": 2, "max_leaf_nodes": 4, "learning_rate": 0.10,
       "max_iter": 80, "min_samples_leaf": 12, "l2_regularization": 10},
      {"max_depth": 3, "max_leaf_nodes": 8, "learning_rate": 0.03,
       "max_iter": 400, "min_samples_leaf": 4, "l2_regularization": 0.1},
      {"max_depth": 3, "max_leaf_nodes": 8, "learning_rate": 0.10,
       "max_iter": 120, "min_samples_leaf": 6, "l2_regularization": 1},
      {"max_depth": 4, "max_leaf_nodes": 12, "learning_rate": 0.05,
       "max_iter": 200, "min_samples_leaf": 8, "l2_regularization": 5},
      {"max_depth": 4, "max_leaf_nodes": 16, "learning_rate": 0.20,
       "max_iter": 40, "min_samples_leaf": 12, "l2_regularization": 10},
      {"max_depth": 5, "max_leaf_nodes": 16, "learning_rate": 0.10,
       "max_iter": 80, "min_samples_leaf": 2, "l2_regularization": 1},
    ],
    "xgboost": [
      {"max_depth": 1, "learning_rate": .01, "n_estimators": 400,
       "min_child_weight": 2, "reg_lambda": 0, "reg_alpha": 0,
       "subsample": 1.0, "colsample_bytree": 1.0},
      {"max_depth": 1, "learning_rate": .20, "n_estimators": 40,
       "min_child_weight": 4, "reg_lambda": .1, "reg_alpha": 0,
       "subsample": .9, "colsample_bytree": 1.0},
      {"max_depth": 2, "learning_rate": .03, "n_estimators": 200,
       "min_child_weight": 6, "reg_lambda": 1, "reg_alpha": 0,
       "subsample": 1.0, "colsample_bytree": .8},
      {"max_depth": 2, "learning_rate": .05, "n_estimators": 120,
       "min_child_weight": 8, "reg_lambda": 5, "reg_alpha": .01,
       "subsample": .9, "colsample_bytree": 1.0},
      {"max_depth": 2, "learning_rate": .10, "n_estimators": 80,
       "min_child_weight": 12, "reg_lambda": 10, "reg_alpha": .1,
       "subsample": .8, "colsample_bytree": .8},
      {"max_depth": 3, "learning_rate": .01, "n_estimators": 400,
       "min_child_weight": 4, "reg_lambda": .1, "reg_alpha": 0,
       "subsample": 1.0, "colsample_bytree": 1.0},
      {"max_depth": 3, "learning_rate": .03, "n_estimators": 200,
       "min_child_weight": 6, "reg_lambda": 1, "reg_alpha": .01,
       "subsample": .9, "colsample_bytree": .8},
      {"max_depth": 3, "learning_rate": .05, "n_estimators": 120,
       "min_child_weight": 8, "reg_lambda": 5, "reg_alpha": .1,
       "subsample": .8, "colsample_bytree": 1.0},
      {"max_depth": 3, "learning_rate": .10, "n_estimators": 80,
       "min_child_weight": 2, "reg_lambda": 10, "reg_alpha": 1,
       "subsample": 1.0, "colsample_bytree": .8},
      {"max_depth": 4, "learning_rate": .03, "n_estimators": 400,
       "min_child_weight": 4, "reg_lambda": 0, "reg_alpha": .1,
       "subsample": .8, "colsample_bytree": .8},
      {"max_depth": 4, "learning_rate": .10, "n_estimators": 120,
       "min_child_weight": 6, "reg_lambda": 1, "reg_alpha": 0,
       "subsample": .9, "colsample_bytree": 1.0},
      {"max_depth": 4, "learning_rate": .20, "n_estimators": 40,
       "min_child_weight": 8, "reg_lambda": 5, "reg_alpha": 1,
       "subsample": 1.0, "colsample_bytree": .8},
      {"max_depth": 5, "learning_rate": .05, "n_estimators": 200,
       "min_child_weight": 12, "reg_lambda": 10, "reg_alpha": .01,
       "subsample": .9, "colsample_bytree": 1.0},
      {"max_depth": 5, "learning_rate": .10, "n_estimators": 80,
       "min_child_weight": 2, "reg_lambda": 1, "reg_alpha": .1,
       "subsample": .8, "colsample_bytree": .8},
    ],
    "catboost": [
      {"depth": 1, "learning_rate": .01, "iterations": 400,
       "l2_leaf_reg": 0, "subsample": 1.0},
      {"depth": 2, "learning_rate": .03, "iterations": 200,
       "l2_leaf_reg": .1, "subsample": .9},
      {"depth": 2, "learning_rate": .05, "iterations": 120,
       "l2_leaf_reg": 1, "subsample": 1.0},
      {"depth": 3, "learning_rate": .10, "iterations": 80,
       "l2_leaf_reg": 5, "subsample": .8},
      {"depth": 3, "learning_rate": .20, "iterations": 40,
       "l2_leaf_reg": 10, "subsample": 1.0},
      {"depth": 4, "learning_rate": .03, "iterations": 400,
       "l2_leaf_reg": 1, "subsample": .9},
      {"depth": 4, "learning_rate": .10, "iterations": 120,
       "l2_leaf_reg": 5, "subsample": 1.0},
      {"depth": 5, "learning_rate": .05, "iterations": 200,
       "l2_leaf_reg": 10, "subsample": .8},
    ],
    "svm": [
      {"kernel": "rbf", "C": .1, "gamma": .03},
      {"kernel": "rbf", "C": .1, "gamma": .3},
      {"kernel": "rbf", "C": 1, "gamma": .03},
      {"kernel": "rbf", "C": 1, "gamma": .3},
      {"kernel": "rbf", "C": 10, "gamma": .1},
      {"kernel": "poly", "C": .3, "gamma": .1, "degree": 2,
       "coef0": 1},
      {"kernel": "poly", "C": 1, "gamma": .1, "degree": 3,
       "coef0": 1},
    ],
    "mlp": [
      {"hidden": [8], "learning_rate": .003, "weight_decay": 0,
       "dropout": 0, "epochs": 300},
      {"hidden": [8], "learning_rate": .01, "weight_decay": .001,
       "dropout": .1, "epochs": 300},
      {"hidden": [16], "learning_rate": .003, "weight_decay": .0001,
       "dropout": 0, "epochs": 300},
      {"hidden": [16], "learning_rate": .01, "weight_decay": .01,
       "dropout": .1, "epochs": 300},
      {"hidden": [32], "learning_rate": .003, "weight_decay": .001,
       "dropout": 0, "epochs": 300},
      {"hidden": [32], "learning_rate": .01, "weight_decay": .01,
       "dropout": .2, "epochs": 300},
      {"hidden": [16, 8], "learning_rate": .003,
       "weight_decay": .0001, "dropout": 0, "epochs": 400},
      {"hidden": [16, 8], "learning_rate": .01,
       "weight_decay": .001, "dropout": .1, "epochs": 400},
      {"hidden": [32, 16], "learning_rate": .003,
       "weight_decay": .001, "dropout": .1, "epochs": 400},
      {"hidden": [32, 16], "learning_rate": .01,
       "weight_decay": .01, "dropout": .2, "epochs": 400},
    ],
  }


def build_manifest(input_hashes: dict[str, str] | None = None) -> dict:
  candidates = []
  for family in MODEL_ORDER:
    transforms = ("raw",) if family == "direct_linear" else WEIGHT_TRANSFORMS
    for lattice_index, params in enumerate(_lattices()[family]):
      for transform in transforms:
        candidate_id = f"{family}-{lattice_index:02d}-{transform}"
        candidates.append({
          "id": candidate_id,
          "modelFamily": family,
          "params": deepcopy(params),
          "weightTransform": transform,
          "thresholdPolicy": {
            "kind": "probability_grid" if family != "svm"
            else "training_score_quantile_grid",
            "values": list(THRESHOLDS),
          },
          "complexity": lattice_index + 1,
        })
  pairs = [
    ("direct_linear", "hist_boost"),
    ("direct_linear", "xgboost"),
    ("direct_linear", "gam_spline"),
    ("hist_boost", "xgboost"),
    ("hist_boost", "gam_spline"),
    ("xgboost", "catboost"),
  ]
  ensembles = []
  for left, right in pairs:
    methods = [
      ("average", {"alpha": .5}),
      ("blend", {"alpha": .25}),
      ("blend", {"alpha": .50}),
      ("blend", {"alpha": .75}),
      ("cache_and", {}),
      ("cache_or", {}),
      ("stacked_logistic", {"C": 1.0}),
    ]
    for method, params in methods:
      suffix = method + (f"-{params['alpha']:.2f}" if "alpha" in params
                         else "")
      ensembles.append({
        "id": f"ensemble-{left}-{right}-{suffix}",
        "components": [left, right],
        "method": method,
        "params": params,
        "thresholdPolicy": {"kind": "probability_grid",
                            "values": list(THRESHOLDS)},
      })
  manifest = {
    "schemaVersion": 2,
    "trainerVersion": "frozen-side-of-peak-model-tournament-v2",
    "seed": SEED,
    "innerFolds": INNER_FOLDS,
    "runtimeObjective": {"LEFT_OF_PEAK": "DEFAULT",
                         "RIGHT_OF_PEAK": "CACHE"},
    "evaluationCost": "original frozen supportedWrongActionLoss",
    "modelFamilyOrder": list(MODEL_ORDER),
    "thresholds": list(THRESHOLDS),
    "weightTransforms": list(WEIGHT_TRANSFORMS),
    "baseCandidateCount": len(candidates),
    "ensembleCandidateCount": len(ensembles),
    "baseCandidates": candidates,
    "ensembles": ensembles,
    "frozenInputHashes": dict(sorted((input_hashes or {}).items())),
  }
  validate_manifest(manifest)
  return manifest


def validate_manifest(manifest: dict) -> None:
  if manifest["schemaVersion"] != 2 or manifest["baseCandidateCount"] != 302:
    raise ValueError("invalid V2 candidate count or schema")
  if manifest["ensembleCandidateCount"] != 42:
    raise ValueError("invalid V2 ensemble count")
  ids = [item["id"] for item in manifest["baseCandidates"]]
  ids += [item["id"] for item in manifest["ensembles"]]
  if len(ids) != len(set(ids)):
    raise ValueError("duplicate V2 candidate id")
  if set(item["modelFamily"] for item in manifest["baseCandidates"]) != set(
      MODEL_ORDER):
    raise ValueError("missing V2 model family")
  for item in manifest["baseCandidates"]:
    if item["weightTransform"] not in WEIGHT_TRANSFORMS:
      raise ValueError("invalid V2 weight transform")
    if tuple(item["thresholdPolicy"]["values"]) != THRESHOLDS:
      raise ValueError("invalid V2 threshold policy")


def canonical_bytes(payload: dict) -> bytes:
  return (json.dumps(payload, sort_keys=True, separators=(",", ":"),
                     ensure_ascii=True, allow_nan=False) + "\n").encode()


def manifest_sha256(manifest: dict) -> str:
  return hashlib.sha256(canonical_bytes(manifest)).hexdigest()


def write_frozen_manifest(path: Path, manifest: dict) -> str:
  """Create once; a present manifest must be byte-identical."""
  validate_manifest(manifest)
  data = canonical_bytes(manifest)
  if path.exists() and path.read_bytes() != data:
    raise ValueError("candidate manifest is frozen and cannot be changed")
  path.parent.mkdir(parents=True, exist_ok=True)
  if not path.exists():
    path.write_bytes(data)
  digest = hashlib.sha256(data).hexdigest()
  sidecar = path.with_name(path.name + ".sha256")
  sidecar_data = (digest + "\n").encode()
  if sidecar.exists() and sidecar.read_bytes() != sidecar_data:
    raise ValueError("candidate manifest digest sidecar changed")
  if not sidecar.exists():
    sidecar.write_bytes(sidecar_data)
  return digest


def verify_frozen_manifest(path: Path, expected_digest: str) -> dict:
  data = path.read_bytes()
  if hashlib.sha256(data).hexdigest() != expected_digest:
    raise ValueError("candidate manifest changed after tournament start")
  manifest = json.loads(data)
  validate_manifest(manifest)
  return manifest
