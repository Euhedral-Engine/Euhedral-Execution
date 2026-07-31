# Euhedral training

Euhedral training searches for robust values for the runtime's 28 policy weights. The upgraded
closed loop preserves policy, source scenario, benchmark run, iteration, cohort, and environment
identity from native evidence through the final package. DJL and model training remain offline and
are never loaded by `euhedral-core`.

## Build and launch

From the repository root:

```bash
mise install
mise exec -- mvn -B -pl euhedral-training -am install -Dmaven.test.skip=true
mise exec -- mvn -B -pl euhedral-training test
mise exec -- mvn -B -pl euhedral-training -am package -Dmaven.test.skip=true
```

The distribution is under `euhedral-training/target/trainer/`. Run its jar directly for CPU work, or
use `target/trainer/bin/euhedral-training-gpu` after following
[GPU_SETUP_UBUNTU.md](GPU_SETUP_UBUNTU.md). Java 21 and the native Euhedral library are required for
physical benchmarks.

The stable commands are:

```text
closed-loop --config <path>
training-info
package-run --workspace <path> --inputs <path> --output-root <path>
```

`closed-loop` reads only its typed configuration file. It does not read `-Dcycle.*` properties.

## Typed closed-loop operation

Create a UTF-8/LF configuration such as:

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

Then run:

```bash
java -jar euhedral-training/target/trainer/euhedral-training-0.0.7-SNAPSHOT.jar \
  closed-loop --config closed-loop.conf
```

Relative paths resolve against the configuration file. Unknown keys, duplicates, malformed values,
non-finite numbers, ambiguous bootstrap sources, and invalid scenario identities fail before the
run. Booleans are exactly `true` or `false`; seeds are 16 lower-case hexadecimal digits. The file
must have a final LF and no BOM or CR.

`run.bootstrap_policies` is a strict schema-v1 vector file. Its policies carry no measurements and
must be benchmarked natively in every exact required scenario before they can inform calibration or
learning. If neither `run.bootstrap_policies` nor `run.initial_calibration_plan` is provided, the
runner generates deterministic bootstrap vectors from `SequenceFinder`, starting at Sobol index
`1024`, using `run.candidate_budget` rows. For required environments on different machines, point
each invocation at the same workspace, change only `run.active_environment_id`, and resume
sequentially. The checkpoint waits until native bootstrap evidence exists for all required
scenarios.

To inspect the scenario-model hardware environment without training or benchmarking:

```bash
java -jar euhedral-training/target/trainer/euhedral-training-0.0.7-SNAPSHOT.jar training-info
```

This reports DJL, PyTorch, CUDA, and device visibility.

Fixed anchors calibrate runs directly in log space. Strong calibration needs five shared anchors and
residual at most `0.05`; weak calibration needs three and residual at most `0.15` by default.
Aggregation gives each run one vote. Within each scenario, calibrated policies receive percentile
quality with midranks.

A policy is a robust leader only with valid evidence for every required scenario. The authoritative
comparison is lexicographic:

1. higher minimum scenario quality;
2. higher type-7 P25 scenario quality;
3. higher geometric mean quality;
4. lower cross-scenario quality MAD; and
5. lower measurement instability and timeout rate.

Incomplete policies remain in a separate carry-forward pool until coverage is complete. Candidate
selection predicts a complete curve over configured scenarios and reserves budget, after fixed
anchors, for exploration, carry-forward completion, leader revalidation, and disagreement audits.
Their default relative weights are `68/25/2/5`.

`run.resume=true` validates and loads the highest complete checkpoint. Frozen semantic configuration
must match, and a persisted pending schedule is reused. `run.resume=false` rejects an existing
complete checkpoint. Creating `run.stop_file` (default `<workspace>/STOP`) requests a
checkpoint-safe stop at an existing stage boundary; a symlink is not a stop request.

On every normal return the command prints `stage`, `checkpoint`, `package`, and any
`awaiting_scenario` rows.

## Configuration keys

Repeated keys are allowed only for `scenario.required`, `run.initial_observation_bundle`, and
`calibration.reference_override`. A reference override is
`<canonical-scenario>|<benchmark-run-id>`.

### Config syntax and value formats

| Rule                     | Requirement                                                        |
|--------------------------|--------------------------------------------------------------------|
| Comments                 | A line is a comment when its first non-whitespace character is `#` |
| Duplicate singleton keys | Rejected                                                           |
| Escapes                  | Values may not contain NUL or backslash characters                 |
| File encoding            | Valid UTF-8                                                        |
| Line endings             | LF only; final LF required; no CR and no BOM                       |
| Line shape               | One `key=value` pair per nonblank line                             |
| Unknown keys             | Rejected                                                           |
| Whitespace               | Keys and values are trimmed before validation                      |

| Cross-key rule      | Requirement                                                                          |
|---------------------|--------------------------------------------------------------------------------------|
| Bootstrap sources   | `run.bootstrap_policies` and `run.initial_calibration_plan` are mutually exclusive   |
| Observation bundles | `run.initial_observation_bundle` is allowed only with `run.initial_calibration_plan` |
| Reference overrides | Every override scenario must already appear in `scenario.required`                   |
| Scenario coverage   | At least one `scenario.required` entry must match `run.active_environment_id`        |

| Lifecycle key                    | Expected type                 | Default                   |
|----------------------------------|-------------------------------|---------------------------|
| `calibration.reference_override` | repeated scenario/run mapping | empty                     |
| `run.active_environment_id`      | environment identifier        | required                  |
| `run.bootstrap_policies`         | path                          | empty; used when provided |
| `run.candidate_budget`           | decimal integer               | required                  |
| `run.commit_sha`                 | commit hash                   | required                  |
| `run.dirty_working_tree`         | boolean                       | required                  |
| `run.initial_calibration_plan`   | path                          | empty; used when provided |
| `run.initial_observation_bundle` | repeated path                 | empty                     |
| `run.initial_sobol_cursor`       | decimal integer (`long`)      | `131072`                  |
| `run.iterations`                 | decimal integer               | required                  |
| `run.resume`                     | boolean                       | `true`                    |
| `run.scenarios_per_iteration`    | decimal integer               | `2`                       |
| `run.scheduler_seed_hex`         | unsigned 64-bit seed          | `6a09e667f3bcc909`        |
| `run.stop_file`                  | path                          | `<workspace>/STOP`        |
| `run.training_run_id`            | identifier                    | required                  |
| `run.workspace`                  | path                          | required                  |
| `scenario.required`              | canonical scenario ID         | required, repeated        |

| Key                                                           | Expected type                | Format / validation rule                                                                                                                                                  |
|---------------------------------------------------------------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `aggregation.bootstrap_replicates`                            | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `aggregation.bootstrap_seed_hex`                              | unsigned 64-bit seed         | Exactly 16 lower-case hexadecimal digits; preserved as raw bits                                                                                                           |
| `aggregation.calibration_acceptance`                          | enum constant                | Exact Java enum name, case-sensitive                                                                                                                                      |
| `aggregation.minimum_success_fraction`                        | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `aggregation.minimum_successful_repetitions`                  | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `anchors.allow_imported_bootstrap`                            | boolean                      | Exactly `true` or `false`                                                                                                                                                 |
| `anchors.fixed_fraction`                                      | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `anchors.maximum_bootstrap_non_success_rate`                  | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `anchors.maximum_bootstrap_relative_iqr`                      | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `anchors.minimum_fixed_anchors`                               | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `benchmark.expected_repetitions`                              | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `benchmark.frames_per_source`                                 | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `benchmark.liveness_timeout_nanos`                            | decimal integer (`long`)     | Parsed from `-?[0-9]+` as a signed 64-bit decimal                                                                                                                         |
| `benchmark.ordered_frames`                                    | boolean                      | Exactly `true` or `false`                                                                                                                                                 |
| `benchmark.reset_timeout_nanos`                               | decimal integer (`long`)     | Parsed from `-?[0-9]+` as a signed 64-bit decimal                                                                                                                         |
| `benchmark.sample_duration_nanos`                             | decimal integer (`long`)     | Parsed from `-?[0-9]+` as a signed 64-bit decimal                                                                                                                         |
| `budget.carry_forward_weight`                                 | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `budget.disagreement_audit_weight`                            | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `budget.exploration_weight`                                   | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `budget.leader_revalidation_weight`                           | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `calibration.maximum_anchor_weight_share`                     | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `calibration.maximum_strong_residual`                         | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `calibration.maximum_weak_residual`                           | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `calibration.minimum_log_sigma`                               | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `calibration.minimum_strong_anchors`                          | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `calibration.minimum_weak_anchors`                            | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `calibration.reference_override`                              | scenario/run mapping         | Must be `<canonical-scenario>                                                                                                                                             |<benchmark-run-id>`, where the run ID matches `[a-z0-9][a-z0-9._-]{0,95}` |
| `candidate.cma.enabled`                                       | boolean                      | Exactly `true` or `false`                                                                                                                                                 |
| `candidate.cma.generations`                                   | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `candidate.cma.initial_sigma`                                 | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `candidate.cma.islands`                                       | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `candidate.cma.minimum_seed_policies`                         | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `candidate.cma.population_size`                               | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `candidate.cma_weight`                                        | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `candidate.direct_sobol_weight`                               | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `candidate.maximum_prediction_rows`                           | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `candidate.score_band_weight`                                 | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `candidate.score_band_weights`                                | comma-separated integer list | Comma-separated decimal integers with no empty elements, for example `1,1,1,1,2,2,3,5,8,16`                                                                               |
| `candidate.screen_rows`                                       | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `evaluation.maximum_context_mae_regression`                   | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.maximum_context_spearman_regression`              | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.maximum_counts_spearman_regression`               | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.maximum_counts_worst_environment_mae_regression`  | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.maximum_grouped_macro_mae`                        | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.maximum_loso_macro_mae`                           | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.maximum_loso_worst_scenario_mae`                  | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.minimum_context_mae_improvement`                  | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.minimum_context_spearman_improvement`             | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.minimum_counts_cross_environment_mae_improvement` | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.minimum_grouped_macro_precision_at_ten`           | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.minimum_grouped_macro_spearman`                   | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `evaluation.minimum_loso_macro_spearman`                      | finite decimal (`double`)    | Java decimal syntax with optional sign and exponent; must parse to a finite value                                                                                         |
| `run.active_environment_id`                                   | environment identifier       | Must match `[a-z0-9][a-z0-9._-]{0,63}`                                                                                                                                    |
| `run.bootstrap_policies`                                      | path                         | Path text with no NUL or `\`; relative paths resolve against the config file parent and are normalized to absolute paths                                                  |
| `run.candidate_budget`                                        | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `run.commit_sha`                                              | commit hash                  | Exactly 40 or 64 lower-case hexadecimal digits                                                                                                                            |
| `run.dirty_working_tree`                                      | boolean                      | Exactly `true` or `false`                                                                                                                                                 |
| `run.initial_calibration_plan`                                | path                         | Path text with no NUL or `\`; relative paths resolve against the config file parent and are normalized to absolute paths                                                  |
| `run.initial_observation_bundle`                              | path                         | Path text with no NUL or `\`; relative paths resolve against the config file parent and are normalized to absolute paths                                                  |
| `run.initial_sobol_cursor`                                    | decimal integer (`long`)     | Parsed from `-?[0-9]+` as a signed 64-bit decimal                                                                                                                         |
| `run.iterations`                                              | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `run.resume`                                                  | boolean                      | Exactly `true` or `false`                                                                                                                                                 |
| `run.scheduler_seed_hex`                                      | unsigned 64-bit seed         | Exactly 16 lower-case hexadecimal digits; preserved as raw bits                                                                                                           |
| `run.scenarios_per_iteration`                                 | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `run.stop_file`                                               | path                         | Path text with no NUL or `\`; relative paths resolve against the config file parent and are normalized to absolute paths                                                  |
| `run.training_run_id`                                         | identifier                   | Must match `[a-z0-9][a-z0-9._-]{0,95}`                                                                                                                                    |
| `run.workspace`                                               | path                         | Path text with no NUL or `\`; relative paths resolve against the config file parent and are normalized to absolute paths                                                  |
| `scenario.required`                                           | canonical scenario ID        | Must be `s1-<environmentId>-src<positive-int>-core<positive-int>-r<numerator>of<denominator>` and the ratio suffix must exactly match the reduced `sourceCount/coreCount` |
| `training.ablation_members`                                   | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.batch_size`                                         | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.device`                                             | string                       | Free-form string; default is `auto`                                                                                                                                       |
| `training.ensemble_members`                                   | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.feature_selection_mode`                             | enum constant                | Exact Java enum name, case-sensitive                                                                                                                                      |
| `training.include_weak_calibration_rows`                      | boolean                      | Exactly `true` or `false`                                                                                                                                                 |
| `training.label_smoothing`                                    | finite decimal (`float`)     | Java decimal syntax with optional sign and exponent; must parse to a finite `float`                                                                                       |
| `training.learning_rate`                                      | finite decimal (`float`)     | Java decimal syntax with optional sign and exponent; must parse to a finite `float`                                                                                       |
| `training.loso_evaluation_members`                            | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.max_epochs`                                         | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.minimum_test_policy_groups`                         | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.minimum_test_rows_per_scenario`                     | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.minimum_train_policy_groups`                        | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.minimum_train_rows_per_scenario`                    | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.minimum_validation_policy_groups`                   | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.minimum_validation_rows_per_scenario`               | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.model_seed_hex`                                     | unsigned 64-bit seed         | Exactly 16 lower-case hexadecimal digits; preserved as raw bits                                                                                                           |
| `training.patience`                                           | decimal integer              | Parsed from `-?[0-9]+`; leading `+` is not allowed                                                                                                                        |
| `training.split_seed_hex`                                     | unsigned 64-bit seed         | Exactly 16 lower-case hexadecimal digits; preserved as raw bits                                                                                                           |
| `training.weight_decay`                                       | finite decimal (`float`)     | Java decimal syntax with optional sign and exponent; must parse to a finite `float`                                                                                       |

| Budget and generation key             | Expected type                | Default                |
|---------------------------------------|------------------------------|------------------------|
| `budget.carry_forward_weight`         | decimal integer              | `25`                   |
| `budget.disagreement_audit_weight`    | decimal integer              | `5`                    |
| `budget.exploration_weight`           | decimal integer              | `68`                   |
| `budget.leader_revalidation_weight`   | decimal integer              | `2`                    |
| `candidate.cma.enabled`               | boolean                      | `true`                 |
| `candidate.cma.generations`           | decimal integer              | `12`                   |
| `candidate.cma.initial_sigma`         | finite decimal (`double`)    | `0.20`                 |
| `candidate.cma.islands`               | decimal integer              | `4`                    |
| `candidate.cma.minimum_seed_policies` | decimal integer              | `10`                   |
| `candidate.cma.population_size`       | decimal integer              | `96`                   |
| `candidate.cma_weight`                | decimal integer              | `8`                    |
| `candidate.direct_sobol_weight`       | decimal integer              | `1`                    |
| `candidate.maximum_prediction_rows`   | decimal integer              | `16384`                |
| `candidate.score_band_weight`         | decimal integer              | `7`                    |
| `candidate.score_band_weights`        | comma-separated integer list | `1,1,1,1,2,2,3,5,8,16` |
| `candidate.screen_rows`               | decimal integer              | `2097152`              |

| Evidence key                                 | Expected type             | Default            |
|----------------------------------------------|---------------------------|--------------------|
| `aggregation.bootstrap_replicates`           | decimal integer           | `1000`             |
| `aggregation.bootstrap_seed_hex`             | unsigned 64-bit seed      | `6a09e667f3bcc909` |
| `aggregation.calibration_acceptance`         | enum constant             | `STRONG_ONLY`      |
| `aggregation.minimum_success_fraction`       | finite decimal (`double`) | `0.5`              |
| `aggregation.minimum_successful_repetitions` | decimal integer           | `3`                |
| `anchors.allow_imported_bootstrap`           | boolean                   | `false`            |
| `anchors.fixed_fraction`                     | finite decimal (`double`) | `0.02`             |
| `anchors.maximum_bootstrap_non_success_rate` | finite decimal (`double`) | `0.10`             |
| `anchors.maximum_bootstrap_relative_iqr`     | finite decimal (`double`) | `0.25`             |
| `anchors.minimum_fixed_anchors`              | decimal integer           | `5`                |
| `benchmark.expected_repetitions`             | decimal integer           | `10`               |
| `benchmark.frames_per_source`                | decimal integer           | `100000`           |
| `benchmark.liveness_timeout_nanos`           | decimal integer (`long`)  | `50000000`         |
| `benchmark.ordered_frames`                   | boolean                   | `false`            |
| `benchmark.reset_timeout_nanos`              | decimal integer (`long`)  | `2000000000`       |
| `benchmark.sample_duration_nanos`            | decimal integer (`long`)  | `200000000`        |
| `calibration.maximum_anchor_weight_share`    | finite decimal (`double`) | `0.25`             |
| `calibration.maximum_strong_residual`        | finite decimal (`double`) | `0.05`             |
| `calibration.maximum_weak_residual`          | finite decimal (`double`) | `0.15`             |
| `calibration.minimum_log_sigma`              | finite decimal (`double`) | `0.01`             |
| `calibration.minimum_strong_anchors`         | decimal integer           | `5`                |
| `calibration.minimum_weak_anchors`           | decimal integer           | `3`                |

| Training key                                    | Expected type            | Default            |
|-------------------------------------------------|--------------------------|--------------------|
| `training.ablation_members`                     | decimal integer          | `3`                |
| `training.batch_size`                           | decimal integer          | `0`                |
| `training.device`                               | string                   | `auto`             |
| `training.ensemble_members`                     | decimal integer          | `5`                |
| `training.feature_selection_mode`               | enum constant            | `RATIO_ONLY`       |
| `training.include_weak_calibration_rows`        | boolean                  | `false`            |
| `training.label_smoothing`                      | finite decimal (`float`) | `0.02`             |
| `training.learning_rate`                        | finite decimal (`float`) | `0.001`            |
| `training.loso_evaluation_members`              | decimal integer          | `1`                |
| `training.max_epochs`                           | decimal integer          | `250`              |
| `training.minimum_test_policy_groups`           | decimal integer          | `10`               |
| `training.minimum_test_rows_per_scenario`       | decimal integer          | `5`                |
| `training.minimum_train_policy_groups`          | decimal integer          | `40`               |
| `training.minimum_train_rows_per_scenario`      | decimal integer          | `30`               |
| `training.minimum_validation_policy_groups`     | decimal integer          | `10`               |
| `training.minimum_validation_rows_per_scenario` | decimal integer          | `5`                |
| `training.model_seed_hex`                       | unsigned 64-bit seed     | `13198a2e03707344` |
| `training.patience`                             | decimal integer          | `20`               |
| `training.split_seed_hex`                       | unsigned 64-bit seed     | `243f6a8885a308d3` |
| `training.weight_decay`                         | finite decimal (`float`) | `0.0001`           |

| Evaluation key                                                | Expected type             | Default |
|---------------------------------------------------------------|---------------------------|---------|
| `evaluation.maximum_context_mae_regression`                   | finite decimal (`double`) | `0.01`  |
| `evaluation.maximum_context_spearman_regression`              | finite decimal (`double`) | `0.02`  |
| `evaluation.maximum_counts_spearman_regression`               | finite decimal (`double`) | `0.02`  |
| `evaluation.maximum_counts_worst_environment_mae_regression`  | finite decimal (`double`) | `0.02`  |
| `evaluation.maximum_grouped_macro_mae`                        | finite decimal (`double`) | `0.20`  |
| `evaluation.maximum_loso_macro_mae`                           | finite decimal (`double`) | `0.25`  |
| `evaluation.maximum_loso_worst_scenario_mae`                  | finite decimal (`double`) | `0.35`  |
| `evaluation.minimum_context_mae_improvement`                  | finite decimal (`double`) | `0.01`  |
| `evaluation.minimum_context_spearman_improvement`             | finite decimal (`double`) | `0.05`  |
| `evaluation.minimum_counts_cross_environment_mae_improvement` | finite decimal (`double`) | `0.01`  |
| `evaluation.minimum_grouped_macro_precision_at_ten`           | finite decimal (`double`) | `0.20`  |
| `evaluation.minimum_grouped_macro_spearman`                   | finite decimal (`double`) | `0.50`  |
| `evaluation.minimum_loso_macro_spearman`                      | finite decimal (`double`) | `0.35`  |

## Training-run packages

Every normal return publishes an immutable package below
`<workspace>/packages/training-run-<package-id>`. A completed run uses the training-run ID;
recoverable partial runs use `<training-run-id>.partial.r<checkpoint-revision>`.

The manifest records checksums, schemas, row counts, producing stages, source runs, completeness,
and native versus imported provenance. `vectors/*.vectors.csv` are vector-only;
`policy-scenario-measurements.csv` contains vectors with measurements; CSV files are
machine-readable and `README.md` plus `reports/*.md` are human-readable.

Reproduce a package without rerunning the physical experiment:

```bash
java -jar euhedral-training/target/trainer/euhedral-training-0.0.7-SNAPSHOT.jar \
  package-run \
  --workspace output/robust-closed-loop \
  --inputs training-run-<package-id>/provenance/package-inputs.properties \
  --output-root output/reproduced-packages
```

Publication streams large artifacts, validates the staged package, and uses an atomic directory
rename. Existing conflicting packages are never overwritten.
