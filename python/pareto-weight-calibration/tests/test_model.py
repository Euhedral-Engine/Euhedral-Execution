"""Unit tests for MarginalModel, LogicalWeights, JavaParetoWeights, and evaluator parity."""

import json
import math
from pathlib import Path
import numpy as np
import pytest

from pareto_weight_calibration.model import JavaParetoWeights, LogicalWeights, MarginalModel
from pareto_weight_calibration.types import (
    ActiveStateFeatures,
    ArmPerformance,
    Outcome,
    PairRecord,
    TrajectoryStatus,
    WithdrawnDiagnosticState,
)


def test_logical_weights_array_roundtrip():
    w = LogicalWeights(w0=1.0, w1=2.0, w2=3.0, w3=4.0, w4=5.0, w5=6.0, w6=7.0, w7=8.0)
    arr = w.to_array()
    assert len(arr) == 8
    assert arr[0] == 1.0 and arr[7] == 8.0

    w_restored = LogicalWeights.from_array(arr)
    assert w == w_restored

    d = w.to_dict()
    assert d["w0"] == 1.0
    w_from_dict = LogicalWeights.from_dict(d)
    assert w == w_from_dict


def test_java_pareto_weights_roundtrip():
    lw = LogicalWeights(w0=0.5, w1=1.5, w2=2.5, w3=3.5, w4=4.5, w5=5.5, w6=6.5, w7=7.5)
    jw = lw.to_java_pareto_weights()

    assert jw.phrWeight == 0.5
    assert jw.contentionPhrWeight == 1.5
    assert jw.bodyPhrWeight == 2.5
    assert jw.registeredWorkersPhrWeight == 3.5
    assert jw.activeWorkersWeight == 4.5
    assert jw.contentionWorkersWeight == 5.5
    assert jw.bodyWorkersWeight == 6.5
    assert jw.registeredActiveWorkersWeight == 7.5

    lw_restored = jw.to_logical_weights()
    assert lw == lw_restored

    d = jw.to_dict()
    jw_from_dict = JavaParetoWeights.from_dict(d)
    assert jw == jw_from_dict


def test_marginal_evaluation_and_java_parity():
    # Set default weights matching Java ParetoWeights.DEFAULT (all 1.0)
    jw = JavaParetoWeights(
        phrWeight=1.0,
        contentionPhrWeight=1.0,
        bodyPhrWeight=1.0,
        registeredWorkersPhrWeight=1.0,
        activeWorkersWeight=1.0,
        contentionWorkersWeight=1.0,
        bodyWorkersWeight=1.0,
        registeredActiveWorkersWeight=1.0,
    )
    model = MarginalModel(logical_weights=jw.to_logical_weights())

    test_cases = [
        # (c, smoothedBodyCostNs, P, R, K)
        (0.0, 0.0, 4.0, 8, 4),
        (0.85, 250.0, 4.0, 8, 4),
        (0.50, 1000.0, 2.0, 4, 3),
        (0.10, 50.0, 11.0, 23, 8),
        (0.95, 50000.0, 1.0, 16, 2),
    ]

    for c, body, P, R, K in test_cases:
        assert model.verify_evaluator_parity(c, body, P, R, K)

        features = ActiveStateFeatures(
            c=c,
            smoothed_body_cost_ns=body,
            b=math.log1p(body),
            P=P,
            R=R,
            K=K,
        )
        marginal = model.predict_marginal(features)
        action = model.predict_action(features)

        if marginal > 0.0:
            assert action == "CACHE"
        else:
            assert action == "DIRECT_OR_STAGED"


def test_model_save_load_roundtrip(tmp_path: Path):
    lw = LogicalWeights(w0=1.1, w1=2.2, w2=3.3, w3=4.4, w4=5.5, w5=6.6, w6=7.7, w7=8.8)
    meta = {
        "schemaVersion": 1,
        "modelVersion": "productivity-participation-v1",
        "cacheActuatorVersion": "cache-v1",
        "cacheParkNs": 15000,
        "runtimeCommit": "test-commit-sha",
    }
    model = MarginalModel(logical_weights=lw, metadata=meta)

    save_file = tmp_path / "model.json"
    model.save(save_file)
    assert save_file.exists()

    loaded_model = MarginalModel.load(save_file)
    assert loaded_model.logical_weights == model.logical_weights
    assert loaded_model.metadata["modelVersion"] == "productivity-participation-v1"
    assert loaded_model.metadata["runtimeCommit"] == "test-commit-sha"

    # Export Java weights
    java_export_file = tmp_path / "pareto_weights.json"
    jw_dict = model.export_java_weights(java_export_file)
    assert java_export_file.exists()
    assert jw_dict["phrWeight"] == 1.1


def test_model_fit_synthetic_dataset():
    # Generate synthetic separable records
    np.random.seed(42)
    records = []
    # Ground truth weights
    true_w = np.array([2.0, 1.0, 0.5, 0.1, 1.0, 0.5, 0.2, 0.1])

    for i in range(20):
        c = float(np.random.uniform(0.1, 0.9))
        body = float(np.random.uniform(50.0, 2000.0))
        b = math.log1p(body)
        P = float(np.random.choice([2.0, 4.0, 8.0, 11.0]))
        R = 8
        K = int(np.random.choice([2, 3, 4, 5, 6, 7, 8]))

        features = ActiveStateFeatures(
            c=c, smoothed_body_cost_ns=body, b=b, P=P, R=R, K=K
        )
        x = features.feature_vector
        latent = np.dot(x, true_w)
        y = 1.0 if latent > 0.0 else 0.0

        records.append(
            PairRecord(
                pair_id=f"pair-{i}",
                topology_id="test-topology",
                runtime_commit="test-commit",
                cache_actuator_version="cache-v1",
                cache_park_ns=15000,
                K=K,
                registered_workers=R,
                work_units=16,
                features=features,
                withdrawn_diagnostics=WithdrawnDiagnosticState(
                    c_stale=c, P_stale=P, local_cache_count=0, execution_path="CACHE", acquisitions_attempted=0
                ),
                perf_k=ArmPerformance(100.0, 1.0, 1.0, 0.01, 2, 100.0, 1.0, 0.01),
                perf_k_minus_1=ArmPerformance(110.0 if y == 1.0 else 90.0, 1.0, 1.0, 0.01, 2, 110.0 if y == 1.0 else 90.0, 1.0, 0.01),
                delta=10.0 if y == 1.0 else -10.0,
                rel_delta_percent=10.0,
                uncertainty=1.0,
                practical_margin=1.0,
                governing_margin=1.0,
                whole_outcome=Outcome.K_MINUS_1_WINS if y == 1.0 else Outcome.K_WINS,
                late_outcome=Outcome.K_MINUS_1_WINS if y == 1.0 else Outcome.K_WINS,
                trajectory_status=TrajectoryStatus.STABLE_AGREEMENT,
                y=y,
                pair_weight=1.0,
                k_run_path=Path(f"/tmp/k_{i}"),
                k_minus_1_run_path=Path(f"/tmp/k_minus_1_{i}"),
            )
        )

    model = MarginalModel.fit(records, l2_reg=1e-4)
    assert model.metadata["optimizerSuccess"] is True
    assert model.metadata["sampleCount"] == 20
    assert model.metadata["finalLoss"] < 10.0
