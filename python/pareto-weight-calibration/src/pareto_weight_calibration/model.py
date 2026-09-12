"""Mathematical model, marginal prediction, optimization, serialization, and Java export."""

from __future__ import annotations

import json
import math
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Union
import numpy as np
from scipy.optimize import minimize

from pareto_weight_calibration.types import ActiveStateFeatures, PairRecord


@dataclass(frozen=True)
class JavaParetoWeights:
    """Java-compatible ParetoWeights record matching io.euhedral_execution.core.config.FragmentDecisionWeights$ParetoWeights."""
    phrWeight: float                     # w0
    contentionPhrWeight: float           # w1
    bodyPhrWeight: float                 # w2
    registeredWorkersPhrWeight: float    # w3
    activeWorkersWeight: float           # w4
    contentionWorkersWeight: float       # w5
    bodyWorkersWeight: float             # w6
    registeredActiveWorkersWeight: float # w7

    def to_dict(self) -> Dict[str, float]:
        return {
            "activeWorkersWeight": self.activeWorkersWeight,
            "contentionPhrWeight": self.contentionPhrWeight,
            "contentionWorkersWeight": self.contentionWorkersWeight,
            "phrWeight": self.phrWeight,
            "bodyPhrWeight": self.bodyPhrWeight,
            "bodyWorkersWeight": self.bodyWorkersWeight,
            "registeredWorkersPhrWeight": self.registeredWorkersPhrWeight,
            "registeredActiveWorkersWeight": self.registeredActiveWorkersWeight,
        }

    @classmethod
    def from_dict(cls, d: Dict[str, float]) -> JavaParetoWeights:
        return cls(
            phrWeight=float(d["phrWeight"]),
            contentionPhrWeight=float(d["contentionPhrWeight"]),
            bodyPhrWeight=float(d["bodyPhrWeight"]),
            registeredWorkersPhrWeight=float(d["registeredWorkersPhrWeight"]),
            activeWorkersWeight=float(d["activeWorkersWeight"]),
            contentionWorkersWeight=float(d["contentionWorkersWeight"]),
            bodyWorkersWeight=float(d["bodyWorkersWeight"]),
            registeredActiveWorkersWeight=float(d["registeredActiveWorkersWeight"]),
        )

    def to_logical_weights(self) -> LogicalWeights:
        return LogicalWeights(
            w0=self.phrWeight,
            w1=self.contentionPhrWeight,
            w2=self.bodyPhrWeight,
            w3=self.registeredWorkersPhrWeight,
            w4=self.activeWorkersWeight,
            w5=self.contentionWorkersWeight,
            w6=self.bodyWorkersWeight,
            w7=self.registeredActiveWorkersWeight,
        )


@dataclass(frozen=True)
class LogicalWeights:
    """Logical coefficient vector w = [w0..w7] for the participation marginal equation."""
    w0: float = 0.0 # phr intercept
    w1: float = 0.0 # contention * phr
    w2: float = 0.0 # log-body * phr
    w3: float = 0.0 # registered-workers * phr
    w4: float = 0.0 # active-workers intercept
    w5: float = 0.0 # contention * active-workers
    w6: float = 0.0 # log-body * active-workers
    w7: float = 0.0 # registered-workers * active-workers

    def to_array(self) -> np.ndarray:
        return np.array([
            self.w0, self.w1, self.w2, self.w3,
            self.w4, self.w5, self.w6, self.w7,
        ], dtype=np.float64)

    @classmethod
    def from_array(cls, arr: Union[np.ndarray, List[float]]) -> LogicalWeights:
        if len(arr) != 8:
            raise ValueError(f"Expected 8 weights, got {len(arr)}")
        return cls(
            w0=float(arr[0]),
            w1=float(arr[1]),
            w2=float(arr[2]),
            w3=float(arr[3]),
            w4=float(arr[4]),
            w5=float(arr[5]),
            w6=float(arr[6]),
            w7=float(arr[7]),
        )

    def to_dict(self) -> Dict[str, float]:
        return {
            "w0": self.w0, "w1": self.w1, "w2": self.w2, "w3": self.w3,
            "w4": self.w4, "w5": self.w5, "w6": self.w6, "w7": self.w7,
        }

    @classmethod
    def from_dict(cls, d: Dict[str, float]) -> LogicalWeights:
        return cls(
            w0=float(d["w0"]), w1=float(d["w1"]), w2=float(d["w2"]), w3=float(d["w3"]),
            w4=float(d["w4"]), w5=float(d["w5"]), w6=float(d["w6"]), w7=float(d["w7"]),
        )

    def to_java_pareto_weights(self) -> JavaParetoWeights:
        return JavaParetoWeights(
            phrWeight=self.w0,
            contentionPhrWeight=self.w1,
            bodyPhrWeight=self.w2,
            registeredWorkersPhrWeight=self.w3,
            activeWorkersWeight=self.w4,
            contentionWorkersWeight=self.w5,
            bodyWorkersWeight=self.w6,
            registeredActiveWorkersWeight=self.w7,
        )

    @classmethod
    def from_java_pareto_weights(cls, jw: JavaParetoWeights) -> LogicalWeights:
        return jw.to_logical_weights()


@dataclass
class MarginalModel:
    """Predictive model evaluating the participation marginal and action choice."""
    logical_weights: LogicalWeights
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def java_pareto_weights(self) -> JavaParetoWeights:
        return self.logical_weights.to_java_pareto_weights()

    def predict_marginal(self, features: Union[ActiveStateFeatures, np.ndarray]) -> float:
        """Evaluates marginal(K) = dot(x, w). Positive implies CACHE; non-positive implies DIRECT/STAGED."""
        if isinstance(features, ActiveStateFeatures):
            x = features.feature_vector
        else:
            x = np.asarray(features, dtype=np.float64)
        w = self.logical_weights.to_array()
        return float(np.dot(x, w))

    def predict_action(self, features: Union[ActiveStateFeatures, np.ndarray]) -> str:
        """Returns 'CACHE' if marginal > 0.0, else 'DIRECT_OR_STAGED'."""
        marginal = self.predict_marginal(features)
        return "CACHE" if marginal > 0.0 else "DIRECT_OR_STAGED"

    def evaluate_java_marginal(
        self,
        c: float,
        smoothed_body_cost_ns: float,
        P: float,
        R: int,
        K: int,
    ) -> float:
        """Evaluates marginal using the exact unrolled Java formula in FragmentDecisionTree."""
        if K <= 1:
            return 0.0
        b = math.log1p(smoothed_body_cost_ns)
        jw = self.java_pareto_weights
        phr_factor = (
            jw.phrWeight
            + jw.contentionPhrWeight * c
            + jw.bodyPhrWeight * b
            + jw.registeredWorkersPhrWeight * float(R)
        )
        worker_factor = (
            jw.activeWorkersWeight
            + jw.contentionWorkersWeight * c
            + jw.bodyWorkersWeight * b
            + jw.registeredActiveWorkersWeight * float(R)
        )
        phr = P / float(K * (K - 1))
        return phr_factor * phr - worker_factor

    def verify_evaluator_parity(
        self,
        c: float,
        smoothed_body_cost_ns: float,
        P: float,
        R: int,
        K: int,
        tolerance: float = 1e-12,
    ) -> bool:
        """Verifies that vector dot-product prediction exactly matches the Java unrolled evaluation."""
        features = ActiveStateFeatures(
            c=c,
            smoothed_body_cost_ns=smoothed_body_cost_ns,
            b=math.log1p(smoothed_body_cost_ns),
            P=P,
            R=R,
            K=K,
        )
        vec_marginal = self.predict_marginal(features)
        java_marginal = self.evaluate_java_marginal(c, smoothed_body_cost_ns, P, R, K)
        diff = abs(vec_marginal - java_marginal)
        if diff > tolerance:
            raise ValueError(
                f"Evaluator parity violation: dot(x,w)={vec_marginal:.14e}, java={java_marginal:.14e}, diff={diff:.14e}"
            )
        return True

    @classmethod
    def fit(
        cls,
        records: List[PairRecord],
        l2_reg: float = 1e-4,
        initial_weights: Optional[LogicalWeights] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> MarginalModel:
        """Fits the eight logical weights on adjacent pair records using regularized weighted binary cross-entropy."""
        eligible = [r for r in records if r.pair_weight > 0.0]
        if not eligible:
            raise ValueError("No eligible records with positive pair_weight provided for fitting")

        X = np.vstack([r.features.feature_vector for r in eligible])
        y = np.array([r.y for r in eligible], dtype=np.float64)
        weights = np.array([r.pair_weight for r in eligible], dtype=np.float64)

        w0 = (
            initial_weights.to_array()
            if initial_weights is not None
            else np.zeros(8, dtype=np.float64)
        )

        def objective(w: np.ndarray) -> float:
            marginals = np.dot(X, w)
            # Clip for numerical stability in sigmoid
            clipped = np.clip(marginals, -30.0, 30.0)
            p = 1.0 / (1.0 + np.exp(-clipped))
            eps = 1e-15
            p = np.clip(p, eps, 1.0 - eps)
            # Weighted binary cross-entropy: -w * [y*log(p) + (1-y)*log(1-p)]
            bce = -(y * np.log(p) + (1.0 - y) * np.log(1.0 - p))
            loss = np.sum(weights * bce) + l2_reg * np.sum(w ** 2)
            return float(loss)

        def gradient(w: np.ndarray) -> np.ndarray:
            marginals = np.dot(X, w)
            clipped = np.clip(marginals, -30.0, 30.0)
            p = 1.0 / (1.0 + np.exp(-clipped))
            # d(loss)/dw = X.T @ (weights * (p - y)) + 2 * l2_reg * w
            grad = np.dot(X.T, weights * (p - y)) + 2.0 * l2_reg * w
            return grad

        res = minimize(objective, w0, jac=gradient, method="BFGS", options={"maxiter": 1000})

        fitted_weights = LogicalWeights.from_array(res.x)
        meta = metadata.copy() if metadata else {}
        meta["optimizerSuccess"] = bool(res.success)
        meta["optimizerMessage"] = str(res.message)
        meta["finalLoss"] = float(res.fun)
        meta["l2Regularization"] = float(l2_reg)
        meta["sampleCount"] = len(eligible)

        return cls(logical_weights=fitted_weights, metadata=meta)

    def save(self, path: Path) -> None:
        """Serializes model to versioned JSON matching the specification schema."""
        path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "schemaVersion": self.metadata.get("schemaVersion", 1),
            "modelVersion": self.metadata.get("modelVersion", "productivity-participation-v1"),
            "equation": self.metadata.get("equation", "pareto-marginal-v1"),
            "cacheActuatorVersion": self.metadata.get("cacheActuatorVersion", "cache-v1"),
            "cacheParkNs": self.metadata.get("cacheParkNs", 15000),
            "runtimeCommit": self.metadata.get("runtimeCommit", "unspecified"),
            "trainingConfigSha256": self.metadata.get("trainingConfigSha256", ""),
            "sourceArtifacts": self.metadata.get("sourceArtifacts", []),
            "logicalWeights": self.logical_weights.to_dict(),
            "javaParetoWeights": self.java_pareto_weights.to_dict(),
            "splitGroups": self.metadata.get("splitGroups", {}),
            "validationMetrics": self.metadata.get("validationMetrics", {}),
            "testMetrics": self.metadata.get("testMetrics", {}),
        }
        path.write_text(json.dumps(data, indent=2), encoding="utf-8")

    @classmethod
    def load(cls, path: Path) -> MarginalModel:
        """Loads model artifact from JSON and validates parity."""
        text = path.read_text(encoding="utf-8")
        data = json.loads(text)

        if "logicalWeights" in data:
            lw = LogicalWeights.from_dict(data["logicalWeights"])
        elif "javaParetoWeights" in data:
            jw = JavaParetoWeights.from_dict(data["javaParetoWeights"])
            lw = jw.to_logical_weights()
        else:
            raise ValueError("Model artifact lacks 'logicalWeights' or 'javaParetoWeights'")

        model = cls(logical_weights=lw, metadata=data)
        # Verify internal parity on test coordinates
        model.verify_evaluator_parity(c=0.5, smoothed_body_cost_ns=500.0, P=4.0, R=8, K=4)
        return model

    def export_java_weights(self, output_path: Optional[Path] = None) -> Dict[str, float]:
        """Returns the eight named Java fields and optionally writes them to a JSON file."""
        jw_dict = self.java_pareto_weights.to_dict()
        if output_path is not None:
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text(json.dumps(jw_dict, indent=2), encoding="utf-8")
        return jw_dict
