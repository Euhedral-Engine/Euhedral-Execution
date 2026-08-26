"""Trial configuration loader and strict multi-arm compatibility analyzer."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from pareto_weight_calibration.types import TrialCalibrationConfig, TrialConfig


class ConfigLoadError(Exception):
    """Raised when trial_config.json cannot be read or parsed."""


class CompatibilityError(Exception):
    """Raised when paired arms fail strict invariant matching."""


def load_trial_config(config_path: Path) -> TrialConfig:
    """Loads and parses trial_config.json.

    Args:
        config_path: Path to trial_config.json (or run directory containing it).

    Returns:
        Structured TrialConfig instance.
    """
    if config_path.is_dir():
        config_path = config_path / "trial_config.json"

    if not config_path.exists() or not config_path.is_file():
        raise FileNotFoundError(f"Trial config file not found: {config_path}")

    try:
        raw: Dict[str, Any] = json.loads(config_path.read_text(encoding="utf-8"))
    except Exception as e:
        raise ConfigLoadError(f"Failed to parse trial_config.json at {config_path}: {e}") from e

    cal_raw = raw.get("calibrationConfig") or {}

    cal_config = TrialCalibrationConfig(
        cpu_set=list(cal_raw.get("cpuSet", [])),
        parallel_sources=int(cal_raw.get("parallelSources", 0)),
        ordered_sources=int(cal_raw.get("orderedSources", 0)),
        work_units=int(cal_raw.get("workUnits", 0)),
        randomize_work=bool(cal_raw.get("randomizeWork", False)),
        total_required_executions=int(cal_raw.get("totalRequiredExecutions", 0)),
        invocation_timeout_millis=int(cal_raw.get("invocationTimeoutMillis", 0)),
        raw_sample_limit=int(cal_raw.get("rawSampleLimit", 1024)),
        observe_cycle_start=bool(cal_raw.get("observeCycleStart", False)),
        observe_batch_progress=bool(cal_raw.get("observeBatchProgress", False)),
        observe_batch_complete=bool(cal_raw.get("observeBatchComplete", False)),
        observe_raw_body_cost=bool(cal_raw.get("observeRawBodyCost", False)),
        observe_idle_decision=bool(cal_raw.get("observeIdleDecision", False)),
        observe_exec_decision=bool(cal_raw.get("observeExecDecision", False)),
        observe_contention_staleness=bool(cal_raw.get("observeContentionStaleness", False)),
        forced_active_participant_count=cal_raw.get("forcedActiveParticipantCount"),
        cache_park_ns=int(cal_raw.get("cacheParkNs", 15000)),
        cache_actuator_version=str(cal_raw.get("cacheActuatorVersion", "legacy-unspecified")),
        lifecycle_mode=str(cal_raw.get("lifecycleMode", "RESET")),
        decision_weights=cal_raw.get("decisionWeights"),
        decision_weight_profile=cal_raw.get("decisionWeightProfile"),
    )

    return TrialConfig(
        id=raw.get("id"),
        name=raw.get("name"),
        group=raw.get("group"),
        forks=int(raw.get("forks", 0)),
        warmups=int(raw.get("warmups", 0)),
        iterations=int(raw.get("iterations", 0)),
        warmup_time=raw.get("warmupTime"),
        measurement_time=raw.get("measurementTime"),
        jvm_args=list(raw.get("jvmArgs", [])),
        calibration_config=cal_config,
        raw_json=raw,
    )


class CompatibilityAnalyzer:
    """Enforces strict multi-arm fixture compatibility for adjacent K vs K-1 pairs."""

    @classmethod
    def check_compatibility(
        cls,
        config_a: TrialConfig,
        config_b: TrialConfig,
        expected_k: int,
    ) -> Tuple[bool, List[str]]:
        """Validates that config_a (arm A, K) and config_b (arm B, K-1) are strictly compatible.

        Returns:
            Tuple of (is_compatible: bool, mismatch_reasons: List[str]).
        """
        reasons: List[str] = []
        cal_a = config_a.calibration_config
        cal_b = config_b.calibration_config

        # 1. Lifecycle mode must be CONTINUOUS for both
        if cal_a.lifecycle_mode != "CONTINUOUS":
            reasons.append(f"Arm A lifecycleMode is '{cal_a.lifecycle_mode}', must be 'CONTINUOUS'")
        if cal_b.lifecycle_mode != "CONTINUOUS":
            reasons.append(f"Arm B lifecycleMode is '{cal_b.lifecycle_mode}', must be 'CONTINUOUS'")

        # 2. Cache actuator version must match and be valid
        if cal_a.cache_actuator_version != cal_b.cache_actuator_version:
            reasons.append(
                f"Mismatched cacheActuatorVersion: A='{cal_a.cache_actuator_version}', B='{cal_b.cache_actuator_version}'"
            )
        elif cal_a.cache_actuator_version == "legacy-unspecified":
            reasons.append("cacheActuatorVersion is 'legacy-unspecified'; training requires explicit version (e.g. 'cache-v1')")

        # 3. Cache park ns must match
        if cal_a.cache_park_ns != cal_b.cache_park_ns:
            reasons.append(
                f"Mismatched cacheParkNs: A={cal_a.cache_park_ns}, B={cal_b.cache_park_ns}"
            )

        # 4. CPU set must match
        if cal_a.cpu_set != cal_b.cpu_set:
            reasons.append(
                f"Mismatched cpuSet: A={cal_a.cpu_set}, B={cal_b.cpu_set}"
            )

        # 5. Worker count check
        registered_workers = len(cal_a.cpu_set)
        if expected_k > registered_workers:
            reasons.append(
                f"Candidate rank K={expected_k} exceeds registered workers R={registered_workers}"
            )
        if expected_k < 2:
            reasons.append(f"Candidate rank K={expected_k} must be >= 2")

        # 6. Forced cutoff matching
        if cal_a.forced_active_participant_count != expected_k:
            reasons.append(
                f"Arm A forcedActiveParticipantCount is {cal_a.forced_active_participant_count}, expected {expected_k}"
            )
        if cal_b.forced_active_participant_count != (expected_k - 1):
            reasons.append(
                f"Arm B forcedActiveParticipantCount is {cal_b.forced_active_participant_count}, expected {expected_k - 1}"
            )

        # 7. Work fixture parameters
        if cal_a.work_units != cal_b.work_units:
            reasons.append(f"Mismatched workUnits: A={cal_a.work_units}, B={cal_b.work_units}")
        if cal_a.randomize_work != cal_b.randomize_work:
            reasons.append(f"Mismatched randomizeWork: A={cal_a.randomize_work}, B={cal_b.randomize_work}")
        if cal_a.total_required_executions != cal_b.total_required_executions:
            reasons.append(
                f"Mismatched totalRequiredExecutions: A={cal_a.total_required_executions}, B={cal_b.total_required_executions}"
            )
        if cal_a.parallel_sources != cal_b.parallel_sources:
            reasons.append(
                f"Mismatched parallelSources: A={cal_a.parallel_sources}, B={cal_b.parallel_sources}"
            )
        if cal_a.ordered_sources != cal_b.ordered_sources:
            reasons.append(
                f"Mismatched orderedSources: A={cal_a.ordered_sources}, B={cal_b.ordered_sources}"
            )

        # 8. Execution iterations & forks
        if config_a.forks != config_b.forks:
            reasons.append(f"Mismatched forks: A={config_a.forks}, B={config_b.forks}")
        if config_a.iterations != config_b.iterations:
            reasons.append(f"Mismatched iterations: A={config_a.iterations}, B={config_b.iterations}")
        if config_a.warmups != config_b.warmups:
            reasons.append(f"Mismatched warmups: A={config_a.warmups}, B={config_b.warmups}")

        # 9. Decision weights (ordinary idle and body cost weights must match)
        if cal_a.decision_weight_profile != cal_b.decision_weight_profile:
            reasons.append(
                f"Mismatched decisionWeightProfile: A={cal_a.decision_weight_profile}, B={cal_b.decision_weight_profile}"
            )

        return (len(reasons) == 0, reasons)
