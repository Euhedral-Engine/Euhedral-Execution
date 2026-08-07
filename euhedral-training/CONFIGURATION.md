# Euhedral Training Configuration Guide

`euhedral-training` is the offline policy optimization and surrogate model training subsystem for the Euhedral pull-driven execution engine. It optimizes the runtime's 28 policy weights (`weight_00` through `weight_27`) across hardware topologies and execution scenarios using closed-loop benchmarking, log-space anchor calibration, surrogate machine learning model training, and evolutionary candidate search.

This document provides a comprehensive, human-friendly guide to all command-line entry points, lifecycle options, benchmark execution settings, anchor calibration parameters, candidate generation rules, surrogate model hyperparameters, and evaluation threshold gates.

---

## Table of Contents

1. [Architecture Overview & Configuration Model](#1-architecture-overview--configuration-model)
2. [Configuration File Syntax & Validation Rules](#2-configuration-file-syntax--validation-rules)
3. [Command-Line Entry Points & Operations](#3-command-line-entry-points--operations)
4. [Run Lifecycle & Workspace Configurations (`run.*`, `scenario.*`)](#4-run-lifecycle--workspace-configurations-run-scenario)
5. [Benchmark Execution Configurations (`benchmark.*`)](#5-benchmark-execution-configurations-benchmark)
6. [Anchor Selection & Log-Space Calibration (`anchors.*`, `calibration.*`)](#6-anchor-selection--log-space-calibration-anchors-calibration)
7. [Evidence Aggregation & Quality Filters (`aggregation.*`)](#7-evidence-aggregation--quality-filters-aggregation)
8. [Policy Budget Allocation (`budget.*`)](#8-policy-budget-allocation-budget)
9. [Candidate Generation & CMA-ES Optimization (`candidate.*`)](#9-candidate-generation--cma-es-optimization-candidate)
10. [Surrogate Model Training Configurations (`training.*`)](#10-surrogate-model-training-configurations-training)
11. [Model Evaluation & Quality Acceptance Gates (`evaluation.*`)](#11-model-evaluation--quality-acceptance-gates-evaluation)
12. [Complete Configuration Master Reference Table](#12-complete-configuration-master-reference-table)

---

## 1. Architecture Overview & Configuration Model

The closed-loop training process optimizes engine performance across physical compute hardware. It follows a multi-stage iterative workflow:

```text
+-----------------------------------------------------------------------------------+
|                               Closed-Loop Iteration                               |
|                                                                                   |
|  +--------------------+      +--------------------+      +---------------------+  |
|  | Candidate          | ---> | Physical           | ---> | Evidence Merging    |  |
|  | Scheduling         |      | Benchmarking       |      | & Log Calibration   |  |
|  | (Sobol / CMA-ES)   |      | (Native Engine)    |      | (Anchor Alignment)  |  |
|  +--------------------+      +--------------------+      +---------------------+  |
|                                                                     |             |
|  +--------------------+      +--------------------+                 v             |
|  | Package            | <--- | Surrogate Model    | <-----------------------------+  |
|  | Publication        |      | Retraining         |                               |
|  | (Artifacts & CSV)  |      | (TensorFlow/DJL)   |                               |
|  +--------------------+      +--------------------+                               |
+-----------------------------------------------------------------------------------+
```

### Configuration Record Hierarchy

Configuration settings in `euhedral-training` are parsed by [`ClosedLoopConfigCodec`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/config/ClosedLoopConfigCodec.java) into typed Java records:

- [`ClosedLoopConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/config/ClosedLoopConfig.java): Top-level lifecycle, workspace, and scenario configuration record.
- [`BenchmarkExecutionConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/benchmark/config/BenchmarkExecutionConfig.java): Controls physical native benchmark runs.
- [`AnchorSelectionConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/config/AnchorSelectionConfig.java): Governs anchor policy selection for cross-environment calibration.
- [`CalibrationConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/config/CalibrationConfig.java): Defines log-space alignment and residual acceptance criteria.
- [`AggregationConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/config/AggregationConfig.java): Sets bootstrap confidence interval parameters and evidence inclusion rules.
- [`CandidateBudgetConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/scheduling/config/CandidateBudgetConfig.java): Dictates budget splitting across exploration, carry-forward, leader revalidation, and audit.
- [`CandidateGenerationConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/optimization/config/CandidateGenerationConfig.java): Controls Sobol, Score-Band, and CMA-ES candidate search strategies.
- [`CmaEsConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/optimization/config/CmaEsConfig.java): Continuous evolutionary search parameters.
- [`ScenarioTrainingConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/config/ScenarioTrainingConfig.java): Neural network training hyperparameters, split rules, and seeds.
- [`EvaluationThresholds`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/config/EvaluationThresholds.java): Acceptance gates for model validation and feature selection ablation.

---

## 2. Configuration File Syntax & Validation Rules

Configuration files use standard `key=value` property text format with strict validation rules enforced prior to execution.

### Syntax Rules
- **Encoding & Line Endings**: File must be valid UTF-8 without BOM. Line endings must be LF (`\n` only; CR `\r` is rejected). A final trailing LF is required.
- **Comments & Whitespace**: Lines starting with `#` (after trimming leading whitespace) are comments. Keys and values are trimmed of leading/trailing whitespace.
- **Escapes & Duplicates**: Values cannot contain backslashes (`\`) or NUL (`\0`) characters. Unknown keys or duplicate singleton keys are immediately rejected.
- **Repeated Keys**: Allowed ONLY for `scenario.required`, `run.initial_observation_bundle`, and `calibration.reference_override`.
- **Booleans & Hex Seeds**: Booleans must be exactly `true` or `false` (case-sensitive). Unsigned 64-bit random seeds must be exactly 16 lowercase hexadecimal digits (e.g., `6a09e667f3bcc909`).

---

## 3. Command-Line Entry Points & Operations

The `euhedral-training` JAR CLI entry point [`Runner.java`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/Runner.java) provides four operational subcommands:

### 3.1 `closed-loop --config <path>`
- **Description**: Runs or resumes an iterative closed-loop workspace.
- **Arguments**: `--config <path>` (Required path to the configuration file).
- **Execution Flow**: Parsed by [`ClosedLoopConfigCodec`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/config/ClosedLoopConfigCodec.java). Handled by [`ClosedLoopRunner.java`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/ClosedLoopRunner.java). Manages multi-stage state transitions: candidate generation $\rightarrow$ benchmarking $\rightarrow$ calibration $\rightarrow$ model training $\rightarrow$ packaging.
- **Example**:
  ```bash
  java -jar euhedral-training.jar closed-loop --config closed-loop.conf
  ```

### 3.2 `training-info`
- **Description**: Reports hardware acceleration and framework environment details.
- **Arguments**: None.
- **Execution Flow**: Invokes `TrainingEnvironment.print()`. Checks visible TensorFlow C-API libraries, CUDA runtime/driver compatibility, and available CPU/GPU devices. Performs no workspace loading or benchmarking.
- **Example**:
  ```bash
  java -jar euhedral-training.jar training-info
  ```

### 3.3 `package-run --workspace <path> --inputs <path> --output-root <path>`
- **Description**: Reproduces and publishes a standalone, immutable training package from recorded evidence without re-executing physical benchmarks.
- **Arguments**:
  - `--workspace <path>`: Source workspace directory.
  - `--inputs <path>`: Path to `provenance/package-inputs.properties`.
  - `--output-root <path>`: Destination directory for the published package.
- **Execution Flow**: Handled by `TrainingRunPackager.publish()`. Re-assembles vector CSVs, measurement tables, model binaries, and evaluation reports.
- **Example**:
  ```bash
  java -jar euhedral-training.jar package-run \
    --workspace output/robust-closed-loop \
    --inputs output/robust-closed-loop/provenance/package-inputs.properties \
    --output-root output/published-packages
  ```

### 3.4 `merge-calibration-plan --workspace <path> [...] --output <path>`
- **Description**: Combines calibration plans from multiple single-environment workspaces into a unified multi-environment plan.
- **Arguments**:
  - Repeat `--workspace <path>` for each source workspace.
  - `--output <path>`: Destination directory for the merged plan.
- **Execution Flow**: Handled by [`DataMerger.mergeCalibrationPlans(...)`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/DataMerger.java). Merges anchor policy sets, verifies vector agreement, and builds a consolidated plan for multi-node runs.
- **Example**:
  ```bash
  java -jar euhedral-training.jar merge-calibration-plan \
    --workspace output/run-machine-a \
    --workspace output/run-machine-b \
    --output output/merged-calibration-plan
  ```

---

## 4. Run Lifecycle & Workspace Configurations (`run.*`, `scenario.*`)

These settings control workspace persistence, machine environment binding, iteration limits, and scenario definitions.

### `run.workspace`
- **Type**: `Path` | **Default**: *Required*
- **Validation**: Relative paths resolve against the config file parent directory and normalize to absolute paths. Cannot contain NUL or backslash.
- **Code Reference**: [`ClosedLoopConfig.workspace()`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/config/ClosedLoopConfig.java#L18), [`WorkspaceLock`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/checkpoint/WorkspaceLock.java)
- **Explanation**: The root directory for all persistent state, including checkpoints, evidence bundles, schedules, model binaries, and published packages.
- **Practical Guidance**: Ensure sufficient disk space (typically 10–50 GB for long runs with extensive evidence logs).

### `run.training_run_id`
- **Type**: `String` | **Default**: *Required*
- **Validation**: Lowercase alphanumeric string matching `[a-z0-9][a-z0-9._-]{0,95}`.
- **Code Reference**: [`ClosedLoopConfig.trainingRunId()`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/config/ClosedLoopConfig.java#L18)
- **Explanation**: The logical identifier for the training campaign. Appears in output directory names, logs, metadata, and published package manifests.

### `run.iterations`
- **Type**: `int` | **Default**: *Required*
- **Validation**: Strictly positive decimal integer ($> 0$).
- **Code Reference**: [`ClosedLoopRunner.java`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/ClosedLoopRunner.java)
- **Explanation**: Number of full closed-loop iterations to execute. Each iteration runs scheduling, benchmarking, calibration, model training, and checkpointing.

### `run.candidate_budget`
- **Type**: `int` | **Default**: *Required*
- **Validation**: Strictly positive decimal integer ($> 0$). Must be greater than the anchor count.
- **Code Reference**: [`BudgetAllocator`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/scheduling/BudgetAllocator.java), [`SequenceFinder`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/SequenceFinder.java)
- **Explanation**: Maximum number of policy vectors scheduled and benchmarked per iteration across all active scenarios.
- **Practical Guidance**: Set to `1024` or `2048` for typical runs. Larger budgets improve search coverage but increase physical benchmarking duration per iteration.

### `run.active_environment_id`
- **Type**: `String` | **Default**: *Required*
- **Validation**: Lowercase string matching `[a-z0-9][a-z0-9._-]{0,63}`. Must match the environment component of at least one `scenario.required`.
- **Code Reference**: [`ScenarioRotation`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/scheduling/ScenarioRotation.java)
- **Explanation**: Identifies the local machine host. Allows multiple machines to share a single workspace sequentially by changing only this key.

### `scenario.required` *(Repeated Key)*
- **Type**: `SortedSet<SourceScenario>` | **Default**: *Required (At least 1)*
- **Validation**: Must follow canonical format `s1-<environmentId>-src<sources>-core<cores>-r<numerator>of<denominator>`. The ratio `rNofM` must equal the simplified fraction of `sources/cores`.
- **Code Reference**: [`SourceScenario.parse(...)`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/data/SourceScenario.java)
- **Explanation**: Declares all hardware topology scenarios that candidate policies must evaluate across to achieve "Robust Leader" status.
- **Example**:
  ```properties
  scenario.required=s1-machine-a-src1-core32-r1of32
  scenario.required=s1-machine-a-src32-core32-r1of1
  ```

### `run.bootstrap_policies`
- **Type**: `Optional<Path>` | **Default**: `empty`
- **Validation**: Mutually exclusive with `run.initial_calibration_plan`.
- **Code Reference**: [`AnchorBootstrapper`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/AnchorBootstrapper.java)
- **Explanation**: CSV file containing explicit initial policy vectors to benchmark natively during cold-start bootstrap. If neither this nor `run.initial_calibration_plan` is set, bootstrap vectors are generated automatically via Sobol progression starting at index 1024.

### `run.initial_calibration_plan`
- **Type**: `Optional<Path>` | **Default**: `empty`
- **Validation**: Path to directory containing `fixed-anchors.csv` and `reference-runs.csv`. Mutually exclusive with `run.bootstrap_policies`.
- **Code Reference**: [`ClosedLoopRunner.stageInitialCalibrationPlan()`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/ClosedLoopRunner.java)
- **Explanation**: Bypasses initial cold-start native bootstrap benchmarking by importing a pre-existing calibration plan.

### `run.initial_observation_bundle_directory`
- **Type**: `Optional<Path>` | **Default**: `empty`
- **Validation**: Allowed ONLY when `run.initial_calibration_plan` is provided.
- **Code Reference**: [`InitialObservationBundleResolver`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/InitialObservationBundleResolver.java)
- **Explanation**: Directory containing pre-collected benchmark observation bundles matching `reference-runs.csv`.

### `run.initial_observation_bundle` *(Repeated Key)*
- **Type**: `List<Path>` | **Default**: `empty`
- **Validation**: Allowed ONLY when `run.initial_calibration_plan` is provided.
- **Explanation**: Explicit file paths to individual pre-collected observation bundles.

### `calibration.reference_override` *(Repeated Key)*
- **Type**: `Map<SourceScenario, String>` | **Default**: `empty`
- **Validation**: Format `<canonical-scenario>|<benchmark-run-id>`. Scenario must exist in `scenario.required`.
- **Code Reference**: [`AnchorBootstrapper`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/AnchorBootstrapper.java)
- **Explanation**: Overrides automatic reference benchmark run selection for specified scenarios during initial calibration creation.

### `run.commit_sha`
- **Type**: `String` | **Default**: *Required*
- **Validation**: Exactly 40 or 64 lowercase hex digits (`[0-9a-f]{40}` or `[0-9a-f]{64}`).
- **Explanation**: Git commit hash recorded in manifests to guarantee provenance and auditability of evidence.

### `run.dirty_working_tree`
- **Type**: `boolean` | **Default**: *Required*
- **Validation**: `true` or `false`.
- **Explanation**: Indicates whether the source tree had uncommitted modifications when evidence was generated.

### `run.resume`
- **Type**: `boolean` | **Default**: `true`
- **Validation**: `true` or `false`.
- **Explanation**: When `true`, automatically detects and loads the highest valid checkpoint in `run.workspace`. When `false`, rejects existing complete checkpoints to prevent accidental overwrites.

### `run.scenarios_per_iteration`
- **Type**: `int` | **Default**: `2`
- **Validation**: Integer $> 0$.
- **Code Reference**: [`ScenarioRotation`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/scheduling/ScenarioRotation.java)
- **Explanation**: Maximum number of benchmark scenarios scheduled for physical execution per iteration.

### `run.scheduler_seed_hex`
- **Type**: `long` | **Default**: `6a09e667f3bcc909`
- **Validation**: Exactly 16 lowercase hex digits.
- **Explanation**: Master random seed driving candidate selection, scenario rotation, and Sobol sampling.

### `run.initial_sobol_cursor`
- **Type**: `long` | **Default**: `131072` (128K)
- **Validation**: Integer $\ge 0$.
- **Explanation**: Starting offset index in the quasi-random Sobol space-filling sequence.

### `run.stop_file`
- **Type**: `Path` | **Default**: `<workspace>/STOP`
- **Explanation**: Location of the graceful shutdown file. Creating a regular file at this path causes `ClosedLoopRunner` to complete the current iteration and stop safely at the next checkpoint boundary.

---

## 5. Benchmark Execution Configurations (`benchmark.*`)

Configured by [`BenchmarkExecutionConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/benchmark/config/BenchmarkExecutionConfig.java), these parameters govern native physical benchmark execution via `BenchmarkRunner`.

```text
+-----------------------------------------------------------------------------------+
|                            Physical Benchmark Execution                           |
|                                                                                   |
|  +--------------------+      +--------------------+      +---------------------+  |
|  | Warm-up & Lattice  | ---> | Frame Processing   | ---> | Performance Metric  |  |
|  | Flush (Reset Timeout)    | Active Window      |      | Computation         |  |
|  | (reset_timeout)    |      | (sample_duration)  |      | (Repetitions)       |  |
|  +--------------------+      +--------------------+      +---------------------+  |
|                                         |                                         |
|                                         v                                         |
|                              +--------------------+                               |
|                              | Liveness Monitor   |                               |
|                              | (liveness_timeout) |                               |
|                              +--------------------+                               |
+-----------------------------------------------------------------------------------+
```

### `benchmark.expected_repetitions`
- **Type**: `int` | **Default**: `10`
- **Validation**: Integer $> 0$.
- **Code Reference**: [`BenchmarkRunner`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/BenchmarkRunner.java), [`RunAggregator`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/RunAggregator.java)
- **Explanation**: Number of independent benchmark repetition runs executed per policy/scenario combination.
- **Practical Guidance**: Higher values (e.g. 10–15) reduce variance and provide stable median throughputs. Lower values (e.g. 3–5) speed up physical benchmarking at the risk of higher noise.

### `benchmark.frames_per_source`
- **Type**: `int` | **Default**: `100000`
- **Validation**: Integer $> 0$.
- **Code Reference**: [`BenchmarkRunner`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/BenchmarkRunner.java)
- **Explanation**: Number of synthetic frames pre-generated per source before commencing execution, eliminating allocation overhead during measurement.

### `benchmark.liveness_timeout_nanos`
- **Type**: `long` | **Default**: `50000000` (50 ms)
- **Validation**: Nanoseconds $> 0$.
- **Code Reference**: [`BenchmarkRunner.NativeBackend`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/BenchmarkRunner.java)
- **Explanation**: Maximum permitted elapsed time without a single frame completion before declaring the benchmark run deadlocked or stalled (`ObservationStatus.TIMEOUT`).

### `benchmark.ordered_frames`
- **Type**: `boolean` | **Default**: `false`
- **Validation**: `true` or `false`.
- **Explanation**: When `true`, forces strict ordered routing (`idHash == routingHash`). When `false`, randomizes routing hashes for multi-core parallel distribution.

### `benchmark.reset_timeout_nanos`
- **Type**: `long` | **Default**: `2000000000` (2.0 seconds)
- **Validation**: Nanoseconds $> 0$.
- **Code Reference**: [`ControlPlaneLattice.clear(...)`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneLattice.java)
- **Explanation**: Maximum time allowed to flush internal queues, reset worker caches, and drain pending frames between policy evaluation switches.

### `benchmark.sample_duration_nanos`
- **Type**: `long` | **Default**: `200000000` (200 ms)
- **Validation**: Nanoseconds $> 0$.
- **Code Reference**: [`BenchmarkRunner.NativeBackend`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/BenchmarkRunner.java)
- **Explanation**: Active duration of each benchmark measurement window used to calculate frames-per-second throughput.

---

## 6. Anchor Selection & Log-Space Calibration (`anchors.*`, `calibration.*`)

These settings govern how raw performance metrics from different physical machines are normalized into unified, cross-environment quality scores.

```text
+-----------------------------------------------------------------------------------+
|                         Log-Space Anchor Calibration Pipeline                     |
|                                                                                   |
|  +--------------------+      +--------------------+      +---------------------+  |
|  | Shared Anchors     | ---> | Log-Space Delta    | ---> | Calibration Status  |  |
|  | (min 5, max cap)   |      | Weighted Median    |      | (Strong vs Weak)    |  |
|  | (fixed_fraction)   |      | (min_log_sigma)    |      | (max_residual)      |  |
|  +--------------------+      +--------------------+      +---------------------+  |
+-----------------------------------------------------------------------------------+
```

### `anchors.allow_imported_bootstrap`
- **Type**: `boolean` | **Default**: `false`
- **Validation**: `true` or `false`.
- **Code Reference**: [`AnchorBootstrapper`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/AnchorBootstrapper.java)
- **Explanation**: Controls whether imported external observation bundles can serve as reference anchor runs during initial bootstrap calibration.

### `anchors.fixed_fraction`
- **Type**: `double` | **Default**: `0.02` (2%)
- **Validation**: Decimal in $(0, 1.0]$.
- **Code Reference**: [`AnchorSelectionConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/config/AnchorSelectionConfig.java)
- **Explanation**: Fraction of candidate budget reserved for fixed anchor policies across runs.

### `anchors.maximum_bootstrap_non_success_rate`
- **Type**: `double` | **Default**: `0.10` (10%)
- **Validation**: Decimal in $[0.0, 1.0]$.
- **Explanation**: Maximum allowable failure/timeout rate for a candidate policy to qualify as a bootstrap anchor.

### `anchors.maximum_bootstrap_relative_iqr`
- **Type**: `double` | **Default**: `0.25` (25%)
- **Validation**: Decimal $\ge 0.0$.
- **Explanation**: Maximum relative interquartile range ($\text{IQR} / \text{Median}$) permitted for bootstrap anchor candidates. Filters out volatile policies.

### `anchors.minimum_fixed_anchors`
- **Type**: `int` | **Default**: `5`
- **Validation**: Integer $\ge 1$.
- **Explanation**: Minimum number of fixed anchor policies required to form a valid calibration baseline.

### `calibration.maximum_anchor_weight_share`
- **Type**: `double` | **Default**: `0.25` (25%)
- **Validation**: Decimal in $(0, 1.0]$.
- **Code Reference**: [`RunCalibrator`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/RunCalibrator.java)
- **Explanation**: Maximum weight share any single anchor policy can contribute to the weighted median log-space scaling factor.

### `calibration.maximum_strong_residual`
- **Type**: `double` | **Default**: `0.05` (5%)
- **Validation**: Decimal $\ge 0.0$.
- **Code Reference**: [`RunCalibrator`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/RunCalibrator.java)
- **Explanation**: Maximum log-space alignment residual permitted to achieve `STRONG` calibration status.

### `calibration.maximum_weak_residual`
- **Type**: `double` | **Default**: `0.15` (15%)
- **Validation**: Decimal $\ge \text{maximum\_strong\_residual}$.
- **Code Reference**: [`RunCalibrator`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/RunCalibrator.java)
- **Explanation**: Maximum residual accepted for `WEAK` calibration status. Runs with higher residuals are flagged `UNCALIBRATED` and discarded.

### `calibration.minimum_log_sigma`
- **Type**: `double` | **Default**: `0.01` (1%)
- **Validation**: Decimal $> 0.0$.
- **Explanation**: Lower bound on log-space standard error used during weighted anchor median calculation. Prevents division by zero for low-variance runs.

### `calibration.minimum_strong_anchors`
- **Type**: `int` | **Default**: `5`
- **Validation**: Integer $\ge \text{minimum\_weak\_anchors}$.
- **Explanation**: Minimum shared anchors required for `STRONG` calibration status.

### `calibration.minimum_weak_anchors`
- **Type**: `int` | **Default**: `3`
- **Validation**: Integer $\ge 1$.
- **Explanation**: Absolute minimum number of shared anchors needed to calibrate a run against scenario references.

---

## 7. Evidence Aggregation & Quality Filters (`aggregation.*`)

Managed by [`AggregationConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/config/AggregationConfig.java), these parameters control multi-run statistical aggregation and bootstrap resampling.

### `aggregation.bootstrap_replicates`
- **Type**: `int` | **Default**: `1000`
- **Validation**: Integer $\ge 1$.
- **Code Reference**: [`HierarchicalAggregator`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/HierarchicalAggregator.java)
- **Explanation**: Number of Monte Carlo resamples generated to derive 95% confidence intervals ($[p_{2.5}, p_{97.5}]$) for scenario median throughputs.

### `aggregation.bootstrap_seed_hex`
- **Type**: `long` | **Default**: `6a09e667f3bcc909`
- **Validation**: Exactly 16 lowercase hex digits.
- **Explanation**: Unsigned 64-bit random seed ensuring bit-for-bit reproducible bootstrap resampling.

### `aggregation.calibration_acceptance`
- **Type**: `CalibrationAcceptance` Enum | **Default**: `STRONG_ONLY`
- **Validation**: Enum constant: `STRONG_ONLY` or `INCLUDE_WEAK`.
- **Code Reference**: [`HierarchicalAggregator`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/HierarchicalAggregator.java)
- **Explanation**: Governs whether weakly calibrated evidence is incorporated into scenario quality rankings.

### `aggregation.minimum_success_fraction`
- **Type**: `double` | **Default**: `0.5` (50%)
- **Validation**: Decimal in $(0, 1.0]$.
- **Code Reference**: [`RunAggregator`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/RunAggregator.java)
- **Explanation**: Minimum fraction of planned benchmark repetitions that must complete successfully for an observation to be usable.

### `aggregation.minimum_successful_repetitions`
- **Type**: `int` | **Default**: `3`
- **Validation**: Integer $\ge 1$.
- **Code Reference**: [`RunAggregator`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/merge/RunAggregator.java)
- **Explanation**: Minimum count of successful repetitions required per policy to compute median and interquartile metrics.

---

## 8. Policy Budget Allocation (`budget.*`)

Managed by [`CandidateBudgetConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/scheduling/config/CandidateBudgetConfig.java), these keys determine how the policy vector budget is divided across four active sampling channels using Hamilton / Largest Remainder allocation.

```text
               Total Per-Iteration Policy Budget (run.candidate_budget)
                                          |
                         - Fixed Anchors (anchors.fixed_fraction)
                                          |
                                          v
                                Residual Policy Budget
                                          |
        +------------------+--------------+---------------+------------------+
        | (68%)            | (25%)                        | (2%)             | (5%)
        v                  v                              v                  v
  New Exploration    Carry-Forward Completion       Leader Revalidation  Disagreement Audit
  (budget.           (budget.                       (budget.             (budget.
   exploration_       carry_forward_                 leader_              disagreement_
   weight)            weight)                        revalidation_        audit_weight)
                                                     weight)
```

### `budget.exploration_weight`
- **Type**: `int` | **Default**: `68`
- **Validation**: Non-negative integer $\ge 0$. Sum of all 4 budget weights must be $> 0$.
- **Code Reference**: [`BudgetAllocator`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/scheduling/BudgetAllocator.java), [`CandidateScheduler`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/scheduling/CandidateScheduler.java)
- **Explanation**: Share of residual budget allocated to generating and benchmarking brand-new candidate vectors (`PolicyRole.EXPLORATION`).

### `budget.carry_forward_weight`
- **Type**: `int` | **Default**: `25`
- **Validation**: Non-negative integer $\ge 0$.
- **Code Reference**: [`CandidateScheduler`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/scheduling/CandidateScheduler.java)
- **Explanation**: Share of budget reserved for completing multi-scenario benchmark evaluations for candidates evaluated in prior iterations (`PolicyRole.CARRY_FORWARD`).

### `budget.leader_revalidation_weight`
- **Type**: `int` | **Default**: `2`
- **Validation**: Non-negative integer $\ge 0$.
- **Explanation**: Share of budget allocated to re-benchmarking top robust leader policies to confirm stability and guard against noise (`PolicyRole.LEADER_REVALIDATION`).

### `budget.disagreement_audit_weight`
- **Type**: `int` | **Default**: `5`
- **Validation**: Non-negative integer $\ge 0$.
- **Code Reference**: [`BoundedAuditSelector`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/optimization/BoundedAuditSelector.java)
- **Explanation**: Active learning budget share dedicated to benchmarking candidates where ensemble surrogate models show the highest prediction uncertainty (`PolicyRole.DISAGREEMENT_AUDIT`).

---

## 9. Candidate Generation & CMA-ES Optimization (`candidate.*`)

Governed by [`CandidateGenerationConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/optimization/config/CandidateGenerationConfig.java) and [`CmaEsConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/optimization/config/CmaEsConfig.java), these keys configure quasi-random sampling, surrogate model screening, and evolutionary search.

```text
                     2^21 Raw Sobol Sequence Vectors (screen_rows)
                                          |
                                          v
                       Batch ML Surrogate Model Screening
                           (maximum_prediction_rows)
                                          |
        +---------------------------------+---------------------------------+
        |                                 |                                 |
        v                                 v                                 v
  Direct Sobol                     Score-Band Stratified            CMA-ES Evolutionary
  Space-Filling                    Sampling (10 Bands)              Multi-Island Search
  (direct_sobol_weight)            (score_band_weight)              (cma_weight)
        |                                 |                                 |
        +---------------------------------+---------------------------------+
                                          |
                                          v
                              Combined Exploration Pool
```

### `candidate.direct_sobol_weight`
- **Type**: `int` | **Default**: `1`
- **Validation**: Non-negative integer $\ge 0$. Combined generation weight sum must be $> 0$.
- **Code Reference**: [`SequenceFinder`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/SequenceFinder.java)
- **Explanation**: Weight assigned to direct low-discrepancy Sobol sequence exploration candidates.

### `candidate.cma_weight`
- **Type**: `int` | **Default**: `8`
- **Validation**: Non-negative integer $\ge 0$.
- **Code Reference**: [`CmaEsOptimizer`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/optimization/CmaEsOptimizer.java)
- **Explanation**: Weight assigned to candidates produced by Covariance Matrix Adaptation Evolution Strategy (CMA-ES) search.

### `candidate.score_band_weight`
- **Type**: `int` | **Default**: `7`
- **Validation**: Non-negative integer $\ge 0$.
- **Code Reference**: [`ScoreBandSampler`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/optimization/ScoreBandSampler.java)
- **Explanation**: Weight assigned to quality-stratified candidates sampled across 10 predicted score bands.

### `candidate.score_band_weights`
- **Type**: `int[]` | **Default**: `1,1,1,1,2,2,3,5,8,16`
- **Validation**: Comma-separated list of exactly 10 non-negative integers.
- **Code Reference**: [`ScoreBandSampler`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/optimization/ScoreBandSampler.java)
- **Explanation**: Distribution of candidate capacity across quality bands 0 (lowest, 0.0–0.1) through 9 (highest, 0.9–1.0). The default Fibonacci-like weighting emphasizes high-performing candidates while preserving low-band exploration.

### `candidate.screen_rows`
- **Type**: `int` | **Default**: `2097152` ($2^{21}$)
- **Validation**: Integer $> 0$.
- **Code Reference**: [`SequenceFinder`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/SequenceFinder.java)
- **Explanation**: Total candidate vectors screened through surrogate model inference per iteration.

### `candidate.maximum_prediction_rows`
- **Type**: `int` | **Default**: `16384` ($2^{14}$)
- **Validation**: Integer $> 0$.
- **Explanation**: Batch size chunking during surrogate model inference screening.

### `candidate.cma.enabled`
- **Type**: `boolean` | **Default**: `true`
- **Validation**: `true` or `false`.
- **Code Reference**: [`CmaEsOptimizer`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/optimization/CmaEsOptimizer.java)
- **Explanation**: Master toggle enabling continuous CMA-ES evolutionary candidate optimization.

### `candidate.cma.generations`
- **Type**: `int` | **Default**: `12`
- **Validation**: Integer $\ge 1$.
- **Explanation**: Number of evolutionary generations executed per CMA-ES island.

### `candidate.cma.initial_sigma`
- **Type**: `double` | **Default**: `0.20`
- **Validation**: Finite decimal in $[0.005, 1.0]$.
- **Explanation**: Initial mutation step size (standard deviation) around seed policy vectors.

### `candidate.cma.islands`
- **Type**: `int` | **Default**: `4`
- **Validation**: Integer $\ge 1$.
- **Explanation**: Number of independent multi-start CMA-ES optimization populations.

### `candidate.cma.minimum_seed_policies`
- **Type**: `int` | **Default**: `10`
- **Validation**: Integer $\ge 2$.
- **Explanation**: Minimum distinct benchmarked policies required in historical evidence before CMA-ES search activates.

### `candidate.cma.population_size`
- **Type**: `int` | **Default**: `96`
- **Validation**: Integer $\ge 8$.
- **Explanation**: Candidate population size evaluated per generation in each CMA-ES island.

---

## 10. Surrogate Model Training Configurations (`training.*`)

Managed by [`ScenarioTrainingConfig`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/config/ScenarioTrainingConfig.java), these keys define neural network model architecture, Adam optimizer settings, cross-validation parameters, and dataset splitting rules.

### `training.device`
- **Type**: `String` | **Default**: `auto`
- **Validation**: String: `auto`, `cpu`, or matching regex `gpu[0-9]+`.
- **Code Reference**: [`ScenarioOrdinalNetwork`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/ScenarioOrdinalNetwork.java)
- **Explanation**: Execution device for model training. `auto` automatically uses GPU if available; `cpu` forces CPU training.

### `training.learning_rate`
- **Type**: `float` | **Default**: `0.001`
- **Validation**: Finite float $> 0.0$.
- **Code Reference**: Adam Optimizer in `ScenarioOrdinalNetwork`
- **Explanation**: Learning rate step size during neural network gradient optimization.

### `training.weight_decay`
- **Type**: `float` | **Default**: `0.0001`
- **Validation**: Finite float $\ge 0.0$.
- **Explanation**: $L_2$ weight regularization penalty to prevent overfitting.

### `training.batch_size`
- **Type**: `int` | **Default**: `0` (Auto: 4096 on GPU, 512 on CPU)
- **Validation**: Integer $\ge 0$.
- **Explanation**: Number of sample rows per mini-batch gradient step. Setting `0` selects hardware-optimized defaults.

### `training.max_epochs`
- **Type**: `int` | **Default**: `250`
- **Validation**: Integer $> 0$.
- **Explanation**: Hard upper limit on training epochs per model.

### `training.patience`
- **Type**: `int` | **Default**: `20`
- **Validation**: Integer $> 0$.
- **Code Reference**: [`ScenarioOrdinalNetwork.fit(...)`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/ScenarioOrdinalNetwork.java)
- **Explanation**: Epoch patience window for early stopping. Training stops if validation loss fails to improve for `patience` consecutive epochs.

### `training.ensemble_members`
- **Type**: `int` | **Default**: `5`
- **Validation**: Odd integer between 1 and 9 inclusive ($1, 3, 5, 7, 9$).
- **Code Reference**: [`ScenarioModelTrainer`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/ScenarioModelTrainer.java)
- **Explanation**: Number of member models trained to form the production ensemble. Must be odd to guarantee unambiguous tie-breaking.

### `training.ablation_members`
- **Type**: `int` | **Default**: `3`
- **Validation**: Odd integer $\ge 3$ and $\le \text{ensemble\_members}$.
- **Explanation**: Ensemble size used during feature ablation evaluations (`RATIO_ONLY` vs `RATIO_AND_COUNTS`).

### `training.loso_evaluation_members`
- **Type**: `int` | **Default**: `1`
- **Validation**: Integer $\ge 1$ and $\le \text{ensemble\_members}$.
- **Explanation**: Ensemble member count evaluated during Leave-One-Scenario-Out cross-validation.

### `training.feature_selection_mode`
- **Type**: `FeatureSelectionMode` Enum | **Default**: `RATIO_ONLY`
- **Validation**: Enum constant: `RATIO_ONLY`, `ALLOW_COUNTS`, or `REQUIRE_COUNTS`.
- **Code Reference**: [`ScenarioAblationPlanner`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/ScenarioAblationPlanner.java)
- **Explanation**: Feature schema selection policy. Dictates whether models rely solely on core/source ratios or incorporate hardware performance counters.

### `training.label_smoothing`
- **Type**: `float` | **Default**: `0.02`
- **Validation**: Finite float in $[0.0, 0.5)$.
- **Code Reference**: [`BalancedScenarioOrdinalLoss`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/BalancedScenarioOrdinalLoss.java)
- **Explanation**: Softens target binary classification labels to prevent neural network overconfidence on noisy benchmark scores.

### `training.include_weak_calibration_rows`
- **Type**: `boolean` | **Default**: `false`
- **Validation**: `true` or `false`.
- **Explanation**: Controls whether weakly calibrated evidence is included in the model training dataset.

### `training.require_target_variation`
- **Type**: `boolean` | **Default**: `true`
- **Validation**: `true` or `false`.
- **Code Reference**: [`PolicyGroupedSplitter`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/PolicyGroupedSplitter.java)
- **Explanation**: Enforces target quality score variation within validation/test splits. Disabled automatically during cold-start iterations.

### `training.model_seed_hex`
- **Type**: `long` | **Default**: `13198a2e03707344`
- **Validation**: Exactly 16 lowercase hex digits.
- **Code Reference**: [`ScenarioMemberSeeds`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/config/ScenarioMemberSeeds.java)
- **Explanation**: Unsigned 64-bit seed used to derive deterministic network weight initialization seeds for each ensemble member.

### `training.split_seed_hex`
- **Type**: `long` | **Default**: `243f6a8885a308d3`
- **Validation**: Exactly 16 lowercase hex digits.
- **Code Reference**: [`PolicyGroupedSplitter`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/PolicyGroupedSplitter.java)
- **Explanation**: Master random seed for deterministic 80/10/10 policy-grouped train/validation/test dataset splitting.

### `training.minimum_train_policy_groups`
- **Type**: `int` | **Default**: `40`
- **Validation**: Integer $\ge 1$.
- **Explanation**: Minimum distinct policy groups required in the training split.

### `training.minimum_train_rows_per_scenario`
- **Type**: `int` | **Default**: `30`
- **Validation**: Integer $\ge 1$.
- **Explanation**: Minimum training evidence rows required for every required scenario.

### `training.minimum_validation_policy_groups`
- **Type**: `int` | **Default**: `10`
- **Validation**: Integer $\ge 2$ (if `require_target_variation` is true) or $\ge 1$.
- **Explanation**: Minimum policy groups required in the validation split.

### `training.minimum_validation_rows_per_scenario`
- **Type**: `int` | **Default**: `5`
- **Validation**: Integer $\ge 1$.
- **Explanation**: Minimum validation rows required per scenario.

### `training.minimum_test_policy_groups`
- **Type**: `int` | **Default**: `10`
- **Validation**: Integer $\ge 1$.
- **Explanation**: Minimum policy groups required in the held-out test split.

### `training.minimum_test_rows_per_scenario`
- **Type**: `int` | **Default**: `5`
- **Validation**: Integer $\ge 1$.
- **Explanation**: Minimum test rows required per scenario.

---

## 11. Model Evaluation & Quality Acceptance Gates (`evaluation.*`)

Managed by [`EvaluationThresholds`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/config/EvaluationThresholds.java), these 13 thresholds define quality pass/fail criteria for surrogate models.

```text
+-----------------------------------------------------------------------------------+
|                        Model Evaluation & Quality Acceptance                      |
|                                                                                   |
|  +--------------------+      +--------------------+      +---------------------+  |
|  | Grouped Test Gate  | ---> | LOSO Generalization| ---> | Feature Ablation    |  |
|  | MAE <= 0.20        |      | MAE <= 0.25        |      | Gate Checks         |  |
|  | Spearman >= 0.50   |      | Spearman >= 0.35   |      | (Context & Counts)  |  |
|  | Precision@10>= 0.20|      | Worst MAE <= 0.35  |      +---------------------+  |
|  +--------------------+      +--------------------+                 |             |
|                                                                     v             |
|                                                          Accept/Reject Model      |
+-----------------------------------------------------------------------------------+
```

### `evaluation.maximum_grouped_macro_mae`
- **Type**: `double` | **Default**: `0.20` | **Range**: $[0.0, 1.0]$
- **Code Reference**: [`ScenarioModelTrainer.acceptance()`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/ScenarioModelTrainer.java)
- **Explanation**: Upper bound on Mean Absolute Error across grouped test set policy quality predictions.

### `evaluation.minimum_grouped_macro_spearman`
- **Type**: `double` | **Default**: `0.50` | **Range**: $[-1.0, 1.0]$
- **Explanation**: Minimum acceptable Spearman rank correlation on the held-out test split. Guarantees correct relative ranking of policy performance.

### `evaluation.minimum_grouped_macro_precision_at_ten`
- **Type**: `double` | **Default**: `0.20` | **Range**: $[0.0, 1.0]$
- **Explanation**: Minimum fraction of top-10 model predictions that fall within actual top-10 performance.

### `evaluation.maximum_loso_macro_mae`
- **Type**: `double` | **Default**: `0.25` | **Range**: $[0.0, 1.0]$
- **Explanation**: Maximum acceptable MAE during Leave-One-Scenario-Out (LOSO) cross-validation across unseen scenarios.

### `evaluation.minimum_loso_macro_spearman`
- **Type**: `double` | **Default**: `0.35` | **Range**: $[-1.0, 1.0]$
- **Explanation**: Minimum rank correlation on unseen scenarios during LOSO cross-validation.

### `evaluation.maximum_loso_worst_scenario_mae`
- **Type**: `double` | **Default**: `0.35` | **Range**: $[0.0, 1.0]$
- **Explanation**: Worst-case MAE bound across any single scenario during LOSO evaluation. Prevents accepting models that fail on specific workloads.

### `evaluation.minimum_context_mae_improvement`
- **Type**: `double` | **Default**: `0.01` | **Range**: $\ge 0.0$
- **Code Reference**: [`ScenarioAblationPlanner`](file:///home/brandon/src/Euhedral-Execution/euhedral-training/src/main/java/io/euhedral_execution/training/learning/ScenarioAblationPlanner.java)
- **Explanation**: Minimum MAE improvement required to justify adding scenario ratio context features over policy-only features.

### `evaluation.minimum_context_spearman_improvement`
- **Type**: `double` | **Default**: `0.05` | **Range**: $\ge 0.0$
- **Explanation**: Minimum Spearman rank correlation gain required when incorporating scenario context features.

### `evaluation.maximum_context_mae_regression`
- **Type**: `double` | **Default**: `0.01` | **Range**: $\ge 0.0$
- **Explanation**: Maximum allowable MAE regression when adding scenario context features.

### `evaluation.maximum_context_spearman_regression`
- **Type**: `double` | **Default**: `0.02` | **Range**: $\ge 0.0$
- **Explanation**: Maximum allowable drop in Spearman rank correlation when adding scenario context features.

### `evaluation.minimum_counts_cross_environment_mae_improvement`
- **Type**: `double` | **Default**: `0.01` | **Range**: $\ge 0.0$
- **Explanation**: Minimum cross-environment MAE improvement required to justify using hardware performance counter features (`RATIO_AND_COUNTS`).

### `evaluation.maximum_counts_spearman_regression`
- **Type**: `double` | **Default**: `0.02` | **Range**: $\ge 0.0$
- **Explanation**: Maximum allowable Spearman correlation drop when incorporating hardware count features.

### `evaluation.maximum_counts_worst_environment_mae_regression`
- **Type**: `double` | **Default**: `0.02` | **Range**: $\ge 0.0$
- **Explanation**: Maximum allowable MAE degradation in the worst held-out environment when using count features. Protects against hardware-counter overfitting.

---

## 12. Complete Configuration Master Reference Table

| Key Name | Type | Default | Config Record Class | Functional Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `run.workspace` | `Path` | *Required* | `ClosedLoopConfig` | Workspace output directory |
| `run.training_run_id` | `String` | *Required* | `ClosedLoopConfig` | Training run logical name |
| `run.iterations` | `int` | *Required* | `ClosedLoopConfig` | Closed-loop iteration count |
| `run.candidate_budget` | `int` | *Required* | `ClosedLoopConfig` | Policy candidate vector budget |
| `run.active_environment_id` | `String` | *Required* | `ClosedLoopConfig` | Machine host identifier |
| `scenario.required` | `Set` | *Required* | `ClosedLoopConfig` | Topology scenarios (repeated) |
| `run.bootstrap_policies` | `Path` | `empty` | `ClosedLoopConfig` | Cold-start vector CSV |
| `run.initial_calibration_plan` | `Path` | `empty` | `ClosedLoopConfig` | Pre-built calibration plan directory |
| `run.initial_observation_bundle_directory` | `Path` | `empty` | `ClosedLoopConfig` | Observation bundle folder |
| `run.initial_observation_bundle` | `List` | `empty` | `ClosedLoopConfig` | Explicit bundle paths (repeated) |
| `calibration.reference_override` | `Map` | `empty` | `ClosedLoopConfig` | Reference run override (repeated) |
| `run.commit_sha` | `String` | *Required* | `ClosedLoopConfig` | Source code git commit hash |
| `run.dirty_working_tree` | `boolean` | *Required* | `ClosedLoopConfig` | Uncommitted git state flag |
| `run.resume` | `boolean` | `true` | `ClosedLoopConfig` | Auto-resume checkpoint flag |
| `run.scenarios_per_iteration` | `int` | `2` | `ClosedLoopConfig` | Max scheduled scenarios/iteration |
| `run.scheduler_seed_hex` | `long` | `6a09e667f3bcc909` | `ClosedLoopConfig` | Scheduler random seed |
| `run.initial_sobol_cursor` | `long` | `131072` | `ClosedLoopConfig` | Sobol sequence start index |
| `run.stop_file` | `Path` | `<ws>/STOP` | `ClosedLoopConfig` | Safe shutdown marker file |
| `benchmark.expected_repetitions` | `int` | `10` | `BenchmarkExecutionConfig` | Benchmark repetition count |
| `benchmark.frames_per_source` | `int` | `100000` | `BenchmarkExecutionConfig` | Frames generated per source |
| `benchmark.liveness_timeout_nanos` | `long` | `50000000` | `BenchmarkExecutionConfig` | Progress stall timeout (ns) |
| `benchmark.ordered_frames` | `boolean` | `false` | `BenchmarkExecutionConfig` | Strict frame ordering flag |
| `benchmark.reset_timeout_nanos` | `long` | `2000000000` | `BenchmarkExecutionConfig` | Lattice flush timeout (ns) |
| `benchmark.sample_duration_nanos` | `long` | `200000000` | `BenchmarkExecutionConfig` | Sample window duration (ns) |
| `anchors.allow_imported_bootstrap` | `boolean` | `false` | `AnchorSelectionConfig` | Permit external anchors flag |
| `anchors.fixed_fraction` | `double` | `0.02` | `AnchorSelectionConfig` | Budget fraction for fixed anchors |
| `anchors.maximum_bootstrap_non_success_rate` | `double` | `0.10` | `AnchorSelectionConfig` | Max failure rate for anchors |
| `anchors.maximum_bootstrap_relative_iqr` | `double` | `0.25` | `AnchorSelectionConfig` | Max relative IQR for anchors |
| `anchors.minimum_fixed_anchors` | `int` | `5` | `AnchorSelectionConfig` | Floor count for fixed anchors |
| `calibration.maximum_anchor_weight_share` | `double` | `0.25` | `CalibrationConfig` | Max weight share per anchor |
| `calibration.maximum_strong_residual` | `double` | `0.05` | `CalibrationConfig` | Max residual for strong status |
| `calibration.maximum_weak_residual` | `double` | `0.15` | `CalibrationConfig` | Max residual for weak status |
| `calibration.minimum_log_sigma` | `double` | `0.01` | `CalibrationConfig` | Min log-space standard error |
| `calibration.minimum_strong_anchors` | `int` | `5` | `CalibrationConfig` | Min anchors for strong status |
| `calibration.minimum_weak_anchors` | `int` | `3` | `CalibrationConfig` | Min anchors for weak status |
| `aggregation.bootstrap_replicates` | `int` | `1000` | `AggregationConfig` | Bootstrap resample count |
| `aggregation.bootstrap_seed_hex` | `long` | `6a09e667f3bcc909` | `AggregationConfig` | Bootstrap resample seed |
| `aggregation.calibration_acceptance` | `Enum` | `STRONG_ONLY` | `AggregationConfig` | Calibration level filter |
| `aggregation.minimum_success_fraction` | `double` | `0.5` | `AggregationConfig` | Min repetition success rate |
| `aggregation.minimum_successful_repetitions` | `int` | `3` | `AggregationConfig` | Min successful repetitions |
| `budget.exploration_weight` | `int` | `68` | `CandidateBudgetConfig` | New exploration budget share |
| `budget.carry_forward_weight` | `int` | `25` | `CandidateBudgetConfig` | Carry-forward budget share |
| `budget.leader_revalidation_weight` | `int` | `2` | `CandidateBudgetConfig` | Leader revalidation share |
| `budget.disagreement_audit_weight` | `int` | `5` | `CandidateBudgetConfig` | Active learning audit share |
| `candidate.direct_sobol_weight` | `int` | `1` | `CandidateGenerationConfig` | Direct Sobol search weight |
| `candidate.cma_weight` | `int` | `8` | `CandidateGenerationConfig` | CMA-ES search weight |
| `candidate.score_band_weight` | `int` | `7` | `CandidateGenerationConfig` | Score-band search weight |
| `candidate.score_band_weights` | `int[]` | `1,..,16` | `CandidateGenerationConfig` | 10-band distribution weights |
| `candidate.screen_rows` | `int` | `2097152` | `CandidateGenerationConfig` | Screened candidate pool size |
| `candidate.maximum_prediction_rows` | `int` | `16384` | `CandidateGenerationConfig` | Inference screening batch size |
| `candidate.cma.enabled` | `boolean` | `true` | `CmaEsConfig` | CMA-ES master toggle |
| `candidate.cma.generations` | `int` | `12` | `CmaEsConfig` | CMA-ES generations per island |
| `candidate.cma.initial_sigma` | `double` | `0.20` | `CmaEsConfig` | CMA-ES initial step size |
| `candidate.cma.islands` | `int` | `4` | `CmaEsConfig` | Parallel search islands |
| `candidate.cma.minimum_seed_policies` | `int` | `10` | `CmaEsConfig` | Min policies required for CMA |
| `candidate.cma.population_size` | `int` | `96` | `CmaEsConfig` | Population size per generation |
| `training.device` | `String` | `auto` | `ScenarioTrainingConfig` | Hardware device (`auto`, `cpu`, `gpu0`) |
| `training.learning_rate` | `float` | `0.001` | `ScenarioTrainingConfig` | Adam optimizer learning rate |
| `training.weight_decay` | `float` | `0.0001` | `ScenarioTrainingConfig` | $L_2$ weight regularization |
| `training.batch_size` | `int` | `0` | `ScenarioTrainingConfig` | Training batch size (0 = auto) |
| `training.max_epochs` | `int` | `250` | `ScenarioTrainingConfig` | Maximum training epochs |
| `training.patience` | `int` | `20` | `ScenarioTrainingConfig` | Early stopping epoch patience |
| `training.ensemble_members` | `int` | `5` | `ScenarioTrainingConfig` | Production ensemble size |
| `training.ablation_members` | `int` | `3` | `ScenarioTrainingConfig` | Feature ablation ensemble size |
| `training.loso_evaluation_members` | `int` | `1` | `ScenarioTrainingConfig` | LOSO cross-validation size |
| `training.feature_selection_mode` | `Enum` | `RATIO_ONLY` | `ScenarioTrainingConfig` | Feature selection strategy |
| `training.label_smoothing` | `float` | `0.02` | `ScenarioTrainingConfig` | Target classification smoothing |
| `training.include_weak_calibration_rows` | `boolean` | `false` | `ScenarioTrainingConfig` | Include weak evidence in training |
| `training.require_target_variation` | `boolean` | `true` | `ScenarioTrainingConfig` | Enforce target variation in splits |
| `training.model_seed_hex` | `long` | `13198a2e03707344` | `ScenarioTrainingConfig` | Master model initialization seed |
| `training.split_seed_hex` | `long` | `243f6a8885a308d3` | `ScenarioTrainingConfig` | Dataset 80/10/10 splitting seed |
| `training.minimum_train_policy_groups` | `int` | `40` | `ScenarioTrainingConfig` | Min training policy groups |
| `training.minimum_train_rows_per_scenario` | `int` | `30` | `ScenarioTrainingConfig` | Min training rows per scenario |
| `training.minimum_validation_policy_groups` | `int` | `10` | `ScenarioTrainingConfig` | Min validation policy groups |
| `training.minimum_validation_rows_per_scenario` | `int` | `5` | `ScenarioTrainingConfig` | Min validation rows/scenario |
| `training.minimum_test_policy_groups` | `int` | `10` | `ScenarioTrainingConfig` | Min test policy groups |
| `training.minimum_test_rows_per_scenario` | `int` | `5` | `ScenarioTrainingConfig` | Min test rows per scenario |
| `evaluation.maximum_grouped_macro_mae` | `double` | `0.20` | `EvaluationThresholds` | Max grouped test MAE |
| `evaluation.minimum_grouped_macro_spearman` | `double` | `0.50` | `EvaluationThresholds` | Min grouped test Spearman |
| `evaluation.minimum_grouped_macro_precision_at_ten` | `double` | `0.20` | `EvaluationThresholds` | Min precision@10 on test split |
| `evaluation.maximum_loso_macro_mae` | `double` | `0.25` | `EvaluationThresholds` | Max LOSO macro MAE |
| `evaluation.minimum_loso_macro_spearman` | `double` | `0.35` | `EvaluationThresholds` | Min LOSO macro Spearman |
| `evaluation.maximum_loso_worst_scenario_mae` | `double` | `0.35` | `EvaluationThresholds` | Max MAE in worst LOSO scenario |
| `evaluation.minimum_context_mae_improvement` | `double` | `0.01` | `EvaluationThresholds` | Min MAE gain for scenario context |
| `evaluation.minimum_context_spearman_improvement` | `double` | `0.05` | `EvaluationThresholds` | Min Spearman gain for context |
| `evaluation.maximum_context_mae_regression` | `double` | `0.01` | `EvaluationThresholds` | Max MAE regression for context |
| `evaluation.maximum_context_spearman_regression` | `double` | `0.02` | `EvaluationThresholds` | Max Spearman drop for context |
| `evaluation.minimum_counts_cross_environment_mae_improvement` | `double` | `0.01` | `EvaluationThresholds` | Min MAE gain for count features |
| `evaluation.maximum_counts_spearman_regression` | `double` | `0.02` | `EvaluationThresholds` | Max Spearman drop for counts |
| `evaluation.maximum_counts_worst_environment_mae_regression` | `double` | `0.02` | `EvaluationThresholds` | Max worst-env MAE drop for counts |
