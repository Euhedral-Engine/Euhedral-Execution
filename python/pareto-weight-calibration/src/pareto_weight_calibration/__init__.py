"""Pareto Weight Calibration Package.

Ingestion, validation, and calibration dataset pipeline for Euhedral Execution.
"""

from pareto_weight_calibration.types import (
    ActiveStateFeatures,
    ArmPerformance,
    ArtifactEligibility,
    ForkThroughput,
    LabelEvidenceBasis,
    Manifest,
    ManifestPair,
    Outcome,
    PairRecord,
    TrajectoryStatus,
    TrialConfig,
    WithdrawnDiagnosticState,
)
from pareto_weight_calibration.eligibility import EligibilityAuditor, \
    EligibilityError
from pareto_weight_calibration.loader import DataLoader
from pareto_weight_calibration.export import export_pairs_tsv
from pareto_weight_calibration.model import JavaParetoWeights, LogicalWeights, MarginalModel

__version__ = "0.1.0"
__all__ = [
    "ActiveStateFeatures",
    "ArmPerformance",
    "ArtifactEligibility",
    "DataLoader",
    "EligibilityAuditor",
    "EligibilityError",
    "ForkThroughput",
    "JavaParetoWeights",
    "LabelEvidenceBasis",
    "LogicalWeights",
    "Manifest",
    "ManifestPair",
    "MarginalModel",
    "Outcome",
    "PairRecord",
    "TrajectoryStatus",
    "TrialConfig",
    "WithdrawnDiagnosticState",
    "export_pairs_tsv",
]
