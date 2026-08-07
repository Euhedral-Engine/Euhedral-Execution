# Euhedral Training

Euhedral training searches for robust values for the runtime's 28 policy weights. The closed loop keeps policy, source scenario, benchmark run, iteration, cohort, and environment identity attached to native evidence through the final package. Machine learning model training remains offline and is never loaded by `euhedral-core`.

For an exhaustive, field-by-field reference of every configuration key, validation rule, and tuning guide, see [CONFIGURATION.md](file:///home/brandon/src/Euhedral-Execution/euhedral-training/CONFIGURATION.md).

---

## Build and Launch

From the repository root:

```bash
mise install
gradle :euhedral-training:build
```

The distribution artifacts are created under `euhedral-training/build/`:
- `build/libs/euhedral-training.jar`: Standard runnable JAR.
- `build/lib/`: Dependent runtime libraries.
- `build/bin/euhedral-training-gpu`: GPU launcher wrapper script.

Run the JAR directly for CPU work, or use `build/bin/euhedral-training-gpu` for GPU-accelerated training. Java 21 and the native Euhedral library are required for physical benchmarks. For GPU configuration requirements, see [GPU_SETUP_UBUNTU.md](file:///home/brandon/src/Euhedral-Execution/euhedral-training/GPU_SETUP_UBUNTU.md).

---

## CLI Commands and Arguments

The command-line entry point (`io.euhedral_execution.training.Runner`) provides four primary commands:

```text
closed-loop --config <path>
training-info
package-run --workspace <path> --inputs <path> --output-root <path>
merge-calibration-plan --workspace <path> [--workspace <path> ...] --output <path>
```

### `closed-loop --config <path>`
Runs or resumes a training workspace using a typed configuration file.

- `--config <path>`: Required path to the UTF-8/LF configuration file. Relative paths inside the file are resolved relative to the configuration file's directory.
- Writes checkpoints, schedules, evidence, models, and published packages under `run.workspace`.
- On completion or stop, logs the latest `stage`, `checkpoint`, `package`, and any pending scenarios waiting for evidence.
- `run.resume` controls whether an existing complete checkpoint is loaded, and `run.stop_file` requests a safe stop at the next checkpoint boundary.

### `training-info`
Reports the visible TensorFlow native library, CUDA runtime/driver versions, and GPU/CPU device environment. It takes no extra arguments and does not run benchmarks or train models.

### `package-run --workspace <path> --inputs <path> --output-root <path>`
Publishes a standalone package from an existing workspace checkpoint and recorded inputs without re-running physical benchmarks.

- `--workspace <path>`: Closed-loop workspace directory containing the source checkpoint and artifacts.
- `--inputs <path>`: Path to `provenance/package-inputs.properties` identifying the checkpoint, package ID, configuration, and metadata.
- `--output-root <path>`: Writable directory under which the reproduced package directory is created. Existing conflicting packages are not overwritten.

### `merge-calibration-plan --workspace <path> [--workspace <path> ...] --output <path>`
Merges compatible calibration plans from prior single-environment workspaces into one consolidated calibration plan directory.

- Repeat `--workspace <path>` for each source workspace (each must contain a `calibration-plan/` directory).
- Source workspaces may contribute different anchor sets; the merged plan uses the union of anchor policies across inputs.
- If a policy ID appears in multiple workspaces, its vector contents must agree.
- `--output <path>`: Required destination directory for the merged calibration plan (must not already exist).

---

## Configure and Run the Closed Loop

Create a UTF-8/LF configuration file (e.g. `closed-loop.conf`):

```properties
run.workspace=output/robust-closed-loop
run.training_run_id=trial-2026-07
run.iterations=3
run.candidate_budget=1024
run.active_environment_id=machine-a
run.bootstrap_policies=bootstrap/bootstrap-policies.vectors.csv
run.commit_sha=0000000000000000000000000000000000000000
run.dirty_working_tree=false
run.resume=true
scenario.required=s1-machine-a-src1-core32-r1of32
scenario.required=s1-machine-a-src32-core32-r1of1
```

Run the closed loop:

```bash
java -jar euhedral-training/build/libs/euhedral-training.jar \
  closed-loop --config closed-loop.conf
```

### Configuration Syntax Rules
- **Format**: `key=value` pairs, one per line. Whitespace around keys and values is trimmed.
- **File Encoding & Line Endings**: Valid UTF-8 with LF line endings only (no CR, no BOM, final LF required).
- **Comments**: Lines starting with `#` as the first non-whitespace character are comments.
- **Validation**: Unknown keys, duplicate singleton keys, backslashes (`\`), or NUL (`\0`) characters cause immediate validation failure before execution begins.
- **Booleans & Hex Seeds**: Booleans must be strictly `true` or `false`. Seeds are 16 lowercase hexadecimal digits.
- **Repeated Keys**: Allowed ONLY for `scenario.required`, `run.initial_observation_bundle`, and `calibration.reference_override`.

### Bootstrapping & Multi-Node Workspaces
- `run.bootstrap_policies` points to a schema-v1 vector CSV. Its policies carry no measurements and are benchmarked natively across required scenarios before model-guided sampling begins.
- If neither `run.bootstrap_policies` nor `run.initial_calibration_plan` is provided, the runner deterministically generates bootstrap vectors using `SequenceFinder` starting at Sobol index `131072`.
- For multi-machine environments sharing a workspace, point each machine's invocation at the same workspace, set `run.active_environment_id` to match the local machine, and execute sequentially.

### Calibration Plans
If calibration was prepared across multiple prior workspaces, merge those plans before starting:

```bash
java -jar euhedral-training/build/libs/euhedral-training.jar \
  merge-calibration-plan \
  --workspace output/run-machine-a \
  --workspace output/run-machine-b \
  --output output/merged-calibration-plan
```

Point `run.initial_calibration_plan` to the merged directory (`calibration-plan/` containing `fixed-anchors.csv` and `reference-runs.csv`). Import observation bundles using `run.initial_observation_bundle_directory` or repeated `run.initial_observation_bundle` parameters.

### Calibration & Policy Selection Metrics
- Fixed anchors calibrate benchmark runs directly in log space. `STRONG` calibration requires $\ge 5$ shared anchors and residual $\le 0.05$; `WEAK` calibration requires $\ge 3$ shared anchors and residual $\le 0.15$.
- Policy comparison across required scenarios is strictly lexicographic:
  1. Higher minimum scenario quality score.
  2. Higher type-7 P25 scenario quality score.
  3. Higher geometric mean quality score.
  4. Lower cross-scenario quality Mean Absolute Deviation (MAD).
  5. Lower measurement instability and timeout rate.

Candidate budget is split among exploration, carry-forward completion, leader revalidation, and active learning disagreement audits (default relative weights: `68 / 25 / 2 / 5`).

Creating `run.stop_file` (default `<workspace>/STOP`) requests a safe stop at the next checkpoint boundary.

---

## Configuration Reference Summary

For detailed component mappings, default values, and tuning advice, see [CONFIGURATION.md](file:///home/brandon/src/Euhedral-Execution/euhedral-training/CONFIGURATION.md).

### Lifecycle & Workspace (`run.*`, `scenario.*`)
| Key | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `run.workspace` | Path | *Required* | Directory containing checkpoints, evidence, models, and packages. |
| `run.training_run_id` | String | *Required* | Canonical logical identifier for the training run (`[a-z0-9._-]{1,96}`). |
| `run.iterations` | Integer | *Required* | Total closed-loop iterations to execute ($> 0$). |
| `run.candidate_budget` | Integer | *Required* | Total policy-vector evaluation budget per iteration. |
| `run.active_environment_id` | String | *Required* | Local host environment identifier (`[a-z0-9._-]{1,64}`). |
| `scenario.required` | Set | *Required* | Required execution topology scenario IDs (repeated key). |
| `run.bootstrap_policies` | Path | `empty` | Path to CSV supplying cold-start bootstrap policy vectors. |
| `run.initial_calibration_plan` | Path | `empty` | Path to pre-built calibration plan directory. |
| `run.initial_observation_bundle_directory` | Path | `empty` | Directory containing observation bundles matching calibration reference runs. |
| `run.initial_observation_bundle` | Path List | `empty` | Explicit observation bundle paths (repeated key). |
| `calibration.reference_override` | Map | `empty` | Map overriding reference run for a scenario (`<scenario>\|<run-id>`, repeated). |
| `run.commit_sha` | Hex | *Required* | Git commit hash recorded in manifests (40 or 64 hex digits). |
| `run.dirty_working_tree` | Boolean | *Required* | Flag recording whether uncommitted source changes were present. |
| `run.resume` | Boolean | `true` | Resumes from highest complete workspace checkpoint when `true`. |
| `run.scenarios_per_iteration` | Integer | `2` | Maximum pending scenarios scheduled per iteration. |
| `run.scheduler_seed_hex` | Hex Seed | `6a09e667f3bcc909` | 64-bit seed for candidate scheduling and Sobol sequence. |
| `run.initial_sobol_cursor` | Long | `131072` | Starting Sobol sequence cursor index. |
| `run.stop_file` | Path | `<ws>/STOP` | Path to file triggering safe shutdown at next checkpoint boundary. |

### Benchmark Execution (`benchmark.*`)
| Key | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `benchmark.expected_repetitions` | Integer | `10` | Benchmark repetitions collected per policy/scenario. |
| `benchmark.frames_per_source` | Integer | `100000` | Pre-generated frames per source to prevent allocation overhead. |
| `benchmark.liveness_timeout_nanos` | Long (ns) | `50000000` | Maximum wait for frame progress before declaring a run stalled (50 ms). |
| `benchmark.ordered_frames` | Boolean | `false` | Preserves ordered frame routing during benchmarks when `true`. |
| `benchmark.reset_timeout_nanos` | Long (ns) | `2000000000` | Maximum wait time to flush lattice queues between policy runs (2.0s). |
| `benchmark.sample_duration_nanos` | Long (ns) | `200000000` | Active throughput measurement window duration (200 ms). |

### Calibration & Aggregation (`anchors.*`, `calibration.*`, `aggregation.*`)
| Key | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `anchors.allow_imported_bootstrap` | Boolean | `false` | Allows imported evidence to serve as bootstrap anchor references. |
| `anchors.fixed_fraction` | Double | `0.02` | Share of candidate budget reserved for fixed anchor policies (2%). |
| `anchors.maximum_bootstrap_non_success_rate` | Double | `0.10` | Maximum non-success rate allowed for anchor candidates (10%). |
| `anchors.maximum_bootstrap_relative_iqr` | Double | `0.25` | Maximum relative IQR ($\text{IQR}/\text{Median}$) allowed for anchors (25%). |
| `anchors.minimum_fixed_anchors` | Integer | `5` | Floor count for selected fixed anchors. |
| `calibration.maximum_anchor_weight_share` | Double | `0.25` | Weight cap for a single anchor in median log-space scaling (25%). |
| `calibration.maximum_strong_residual` | Double | `0.05` | Maximum residual permitted for `STRONG` calibration status (0.05). |
| `calibration.maximum_weak_residual` | Double | `0.15` | Maximum residual permitted for `WEAK` calibration status (0.15). |
| `calibration.minimum_log_sigma` | Double | `0.01` | Log-space standard error floor to regularize anchor weights. |
| `calibration.minimum_strong_anchors` | Integer | `5` | Minimum shared anchors required for `STRONG` calibration. |
| `calibration.minimum_weak_anchors` | Integer | `3` | Minimum shared anchors required for `WEAK` calibration. |
| `aggregation.bootstrap_replicates` | Integer | `1000` | Monte Carlo bootstrap resamples for 95% confidence intervals. |
| `aggregation.bootstrap_seed_hex` | Hex Seed | `6a09e667f3bcc909` | Master seed for reproducible bootstrap evidence aggregation. |
| `aggregation.calibration_acceptance` | Enum | `STRONG_ONLY` | Acceptance level for evidence aggregation (`STRONG_ONLY` or `INCLUDE_WEAK`). |
| `aggregation.minimum_success_fraction` | Double | `0.5` | Minimum required benchmark repetition completion rate (50%). |
| `aggregation.minimum_successful_repetitions` | Integer | `3` | Minimum successful repetitions required per policy aggregate. |

### Budget & Candidate Generation (`budget.*`, `candidate.*`)
| Key | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `budget.exploration_weight` | Integer | `68` | Share of residual budget for new policy exploration. |
| `budget.carry_forward_weight` | Integer | `25` | Share reserved for completing multi-scenario policy coverage. |
| `budget.leader_revalidation_weight` | Integer | `2` | Share allocated to re-benchmarking top robust leader policies. |
| `budget.disagreement_audit_weight` | Integer | `5` | Active learning share for candidates with high ensemble disagreement. |
| `candidate.direct_sobol_weight` | Integer | `1` | Relative weight for direct low-discrepancy Sobol candidates. |
| `candidate.cma_weight` | Integer | `8` | Relative weight for CMA-ES continuous optimization candidates. |
| `candidate.score_band_weight` | Integer | `7` | Relative weight for score-band quality-stratified candidates. |
| `candidate.score_band_weights` | Int List | `1,..,16` | Capacity allocation across 10 predicted quality bands (`1,1,1,1,2,2,3,5,8,16`). |
| `candidate.screen_rows` | Integer | `2097152` | Raw Sobol candidate pool size screened by model inference ($2^{21}$). |
| `candidate.maximum_prediction_rows` | Integer | `16384` | Model inference batch size during screening ($2^{14}$). |
| `candidate.cma.enabled` | Boolean | `true` | Master toggle for CMA-ES evolutionary candidate optimization. |
| `candidate.cma.generations` | Integer | `12` | Evolutionary search generations per CMA-ES island. |
| `candidate.cma.initial_sigma` | Double | `0.20` | Initial mutation step size for CMA-ES search. |
| `candidate.cma.islands` | Integer | `4` | Number of independent multi-start CMA-ES search populations. |
| `candidate.cma.minimum_seed_policies` | Integer | `10` | Minimum historical policies required to seed CMA-ES search. |
| `candidate.cma.population_size` | Integer | `96` | Candidate population evaluated per CMA-ES generation. |

### Surrogate Model Training & Evaluation (`training.*`, `evaluation.*`)
| Key | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `training.device` | String | `auto` | Model training compute device (`auto`, `cpu`, `gpu0`). |
| `training.learning_rate` | Float | `0.001` | Adam optimizer learning rate step size. |
| `training.weight_decay` | Float | `0.0001` | $L_2$ weight regularization penalty. |
| `training.batch_size` | Integer | `0` | Training mini-batch size (`0` auto-selects 4096 on GPU / 512 on CPU). |
| `training.max_epochs` | Integer | `250` | Maximum outer training epochs. |
| `training.patience` | Integer | `20` | Epoch patience window for early stopping on validation loss. |
| `training.ensemble_members` | Integer | `5` | Number of production ensemble member models (must be odd). |
| `training.ablation_members` | Integer | `3` | Ensemble members evaluated during feature selection ablation. |
| `training.loso_evaluation_members` | Integer | `1` | Ensemble members evaluated during Leave-One-Scenario-Out cross-validation. |
| `training.feature_selection_mode` | Enum | `RATIO_ONLY` | Feature schema selection policy (`RATIO_ONLY`, `ALLOW_COUNTS`, `REQUIRE_COUNTS`). |
| `training.label_smoothing` | Float | `0.02` | Classification label smoothing factor. |
| `training.include_weak_calibration_rows` | Boolean | `false` | Includes weakly calibrated evidence in training data when `true`. |
| `training.require_target_variation` | Boolean | `true` | Enforces score variation in validation/test splits. |
| `training.model_seed_hex` | Hex Seed | `13198a2e03707344` | Master seed deriving network weight initialization seeds. |
| `training.split_seed_hex` | Hex Seed | `243f6a8885a308d3` | Seed driving 80/10/10 policy-grouped dataset splitting. |
| `training.minimum_train_policy_groups` | Integer | `40` | Minimum policy groups required in the training split. |
| `training.minimum_train_rows_per_scenario` | Integer | `30` | Minimum training evidence rows per scenario. |
| `training.minimum_validation_policy_groups` | Integer | `10` | Minimum policy groups required in the validation split. |
| `training.minimum_validation_rows_per_scenario` | Integer | `5` | Minimum validation evidence rows per scenario. |
| `training.minimum_test_policy_groups` | Integer | `10` | Minimum policy groups required in the test split. |
| `training.minimum_test_rows_per_scenario` | Integer | `5` | Minimum test evidence rows per scenario. |
| `evaluation.maximum_grouped_macro_mae` | Double | `0.20` | Upper limit on grouped test set MAE (0.20). |
| `evaluation.minimum_grouped_macro_spearman` | Double | `0.50` | Lower limit on grouped test set Spearman correlation (0.50). |
| `evaluation.minimum_grouped_macro_precision_at_ten` | Double | `0.20` | Lower limit on top-10 precision on test set (0.20). |
| `evaluation.maximum_loso_macro_mae` | Double | `0.25` | Upper limit on Leave-One-Scenario-Out macro MAE (0.25). |
| `evaluation.minimum_loso_macro_spearman` | Double | `0.35` | Lower limit on Leave-One-Scenario-Out macro Spearman (0.35). |
| `evaluation.maximum_loso_worst_scenario_mae` | Double | `0.35` | Worst-case scenario MAE limit during LOSO evaluation (0.35). |
| `evaluation.minimum_context_mae_improvement` | Double | `0.01` | Required MAE gain to accept scenario context features (0.01). |
| `evaluation.minimum_context_spearman_improvement` | Double | `0.05` | Required Spearman gain to accept scenario context features (0.05). |
| `evaluation.maximum_context_mae_regression` | Double | `0.01` | Max MAE regression permitted when adding context features (0.01). |
| `evaluation.maximum_context_spearman_regression` | Double | `0.02` | Max Spearman drop permitted when adding context features (0.02). |
| `evaluation.minimum_counts_cross_environment_mae_improvement` | Double | `0.01` | Required cross-env MAE gain to accept hardware count features (0.01). |
| `evaluation.maximum_counts_spearman_regression` | Double | `0.02` | Max Spearman drop permitted when adding count features (0.02). |
| `evaluation.maximum_counts_worst_environment_mae_regression` | Double | `0.02` | Max worst-environment MAE drop for count features (0.02). |

---

## Training-Run Packages

Every successful run publishes an immutable, reproducible package under `<workspace>/packages/training-run-<package-id>`.

Package directories contain:
- `manifest.json`: Machine-readable package metadata, checksums, and stage provenance.
- `vectors/*.vectors.csv`: Raw 28D policy vectors without performance measurements.
- `policy-scenario-measurements.csv`: Complete policy vectors combined with aggregated performance measurements.
- `models/`: Trained TensorFlow/DJL surrogate model artifacts.
- `reports/`: Markdown and CSV evaluation reports.
- `provenance/package-inputs.properties`: Input properties file enabling bit-for-bit package reproduction via `package-run`.

To reproduce a package without re-running physical benchmarks:

```bash
java -jar euhedral-training/build/libs/euhedral-training.jar \
  package-run \
  --workspace output/robust-closed-loop \
  --inputs output/robust-closed-loop/packages/training-run-trial-2026-07/provenance/package-inputs.properties \
  --output-root output/reproduced-packages
```
