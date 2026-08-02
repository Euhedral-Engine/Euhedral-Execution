# Euhedral training

Euhedral training searches for robust values for the runtime's 28 policy weights. The closed loop
keeps policy, source scenario, benchmark run, iteration, cohort, and environment identity attached
to native evidence through the final package. DJL and model training remain offline and are never
loaded by `euhedral-core`.

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

The command-line entry point provides these commands:

```text
closed-loop --config <path>
training-info
package-run --workspace <path> --inputs <path> --output-root <path>
```

`closed-loop` runs benchmarking, evidence merging, candidate selection, and model training from a
typed configuration file. It does not read `-Dcycle.*` properties.

### Commands and arguments

`closed-loop --config <path>`

Runs or resumes a training workspace.

- `--config <path>`: required path to the UTF-8/LF typed configuration file. Relative paths inside
  that file are resolved relative to the configuration file.
- The command writes checkpoints, schedules, evidence, models, and packages below
  `run.workspace`.
- On success it logs the latest `stage`, `checkpoint`, `package`, and any scenarios still waiting
  for evidence. `run.resume` controls whether an existing complete checkpoint is loaded, and
  `run.stop_file` requests a safe stop at the next checkpoint boundary.

`training-info`

Reports the visible DJL, PyTorch, CUDA, and device environment. It takes no arguments and does not
train models or run benchmarks.

`package-run --workspace <path> --inputs <path> --output-root <path>`

Publishes a package from an existing checkpoint and its recorded inputs without rerunning physical
benchmarks.

- `--workspace <path>`: closed-loop workspace containing the checkpoint and source artifacts named
  by the input record.
- `--inputs <path>`: `provenance/package-inputs.properties` (or an equivalent package-inputs file)
  identifying the checkpoint, package ID, configuration, and source metadata to publish.
- `--output-root <path>`: writable directory under which the reproduced package directory is
  created. Existing conflicting packages are not overwritten.

The three `package-run` options are required and must appear in this order.

## Configure and run the closed loop

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
`1024`, using `run.candidate_budget` rows, and persists them under the workspace. For required
environments on different machines, point each invocation at the same workspace, change only
`run.active_environment_id`, and resume sequentially. The workspace is ready to train once native
bootstrap evidence exists for all required scenarios. If a cold-start model is rejected, the loop
uses deterministic neutral predictions while it collects more evidence; sparse-data retries follow
the same relaxed training configuration.

### Calibration plan example

`run.initial_calibration_plan` points to a directory containing a fixed anchor set and one reference
benchmark run for each required scenario. It is useful when calibration was prepared elsewhere or
when a run must use a known, repeatable set of anchors rather than selecting them from newly
collected bootstrap evidence. It cannot be combined with `run.bootstrap_policies`.

For example:

```text
calibration-plan/
+-- fixed-anchors.csv
`-- reference-runs.csv
```

The reference file maps each scenario to the benchmark run whose measurements define the reference
in log-space:

```csv
schema_version,anchor_set_id,scenario_id,benchmark_run_id
1,anchors-2026-07,s1-machine-a-src1-core32-r1of32,bootstrap-machine-a-001
1,anchors-2026-07,s1-machine-a-src32-core32-r1of1,bootstrap-machine-a-002
```

`fixed-anchors.csv` contains the same `anchor_set_id` and the selected policy vectors. Its header is
`schema_version,anchor_set_id,policy_id` followed by `weight_00_bits` through `weight_27_bits`;
each weight is encoded as 16 lower-case hexadecimal digits containing the raw IEEE-754 bits of one
policy weight. The anchor policies are benchmarked in every listed scenario, and their measured
values are used to align runs so that results from different environments are comparable. The
scenario IDs in the plan must also appear as repeated `scenario.required` settings. If observations
are supplied with `run.initial_observation_bundle`, they must be used together with this plan.

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

| Lifecycle key                    | Expected type                 | Default                   | Description                                                                 |
|----------------------------------|-------------------------------|---------------------------|-----------------------------------------------------------------------------|
| `calibration.reference_override` | repeated scenario/run mapping | empty                     | Selects the benchmark run used as the calibration reference for a scenario. |
| `run.active_environment_id`      | environment identifier        | required                  | Identifies the machine/environment for this invocation.                     |
| `run.bootstrap_policies`         | path                          | empty; used when provided | Supplies policy vectors to benchmark before normal candidate generation.    |
| `run.candidate_budget`           | decimal integer               | required                  | Total policy-vector budget available for the run.                           |
| `run.commit_sha`                 | commit hash                   | required                  | Records the source revision associated with the evidence.                   |
| `run.dirty_working_tree`         | boolean                       | required                  | Records whether the source tree had uncommitted changes.                    |
| `run.initial_calibration_plan`   | path                          | empty; used when provided | Supplies an explicit initial calibration plan.                              |
| `run.initial_observation_bundle` | repeated path                 | empty                     | Adds previously captured observations to an initial calibration plan.       |
| `run.initial_sobol_cursor`       | decimal integer (`long`)      | `131072`                  | Starting Sobol sequence index for generated vectors.                        |
| `run.iterations`                 | decimal integer               | required                  | Number of closed-loop iterations to execute.                                |
| `run.resume`                     | boolean                       | `true`                    | Resumes the highest complete checkpoint when one exists.                    |
| `run.scenarios_per_iteration`    | decimal integer               | `2`                       | Maximum pending scenarios scheduled per iteration.                          |
| `run.scheduler_seed_hex`         | unsigned 64-bit seed          | `6a09e667f3bcc909`        | Seed for deterministic candidate and scenario scheduling.                   |
| `run.stop_file`                  | path                          | `<workspace>/STOP`        | File whose creation requests a checkpoint-safe stop.                        |
| `run.training_run_id`            | identifier                    | required                  | Stable logical name for the training run and packages.                      |
| `run.workspace`                  | path                          | required                  | Directory containing checkpoints, evidence, schedules, and packages.        |
| `scenario.required`              | canonical scenario ID         | required, repeated        | Declares the exact source/core/environment scenarios that must be covered.  |

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

| Budget and generation key             | Expected type                | Default                | Description                                                   |
|---------------------------------------|------------------------------|------------------------|---------------------------------------------------------------|
| `budget.carry_forward_weight`         | decimal integer              | `25`                   | Share for completing policies with missing scenario coverage. |
| `budget.disagreement_audit_weight`    | decimal integer              | `5`                    | Share for auditing prediction disagreements.                  |
| `budget.exploration_weight`           | decimal integer              | `68`                   | Share for exploring new candidate policies.                   |
| `budget.leader_revalidation_weight`   | decimal integer              | `2`                    | Share for rechecking the current leader.                      |
| `candidate.cma.enabled`               | boolean                      | `true`                 | Enables CMA-ES candidate generation.                          |
| `candidate.cma.generations`           | decimal integer              | `12`                   | Generations produced by each CMA-ES island.                   |
| `candidate.cma.initial_sigma`         | finite decimal (`double`)    | `0.20`                 | Initial CMA-ES mutation scale.                                |
| `candidate.cma.islands`               | decimal integer              | `4`                    | Number of independent CMA-ES populations.                     |
| `candidate.cma.minimum_seed_policies` | decimal integer              | `10`                   | Minimum existing policies needed to seed CMA-ES.              |
| `candidate.cma.population_size`       | decimal integer              | `96`                   | Candidates in each CMA-ES generation.                         |
| `candidate.cma_weight`                | decimal integer              | `8`                    | Relative share assigned to CMA-ES.                            |
| `candidate.direct_sobol_weight`       | decimal integer              | `1`                    | Relative share assigned to direct Sobol candidates.           |
| `candidate.maximum_prediction_rows`   | decimal integer              | `16384`                | Maximum rows scored by the prediction model.                  |
| `candidate.score_band_weight`         | decimal integer              | `7`                    | Relative share assigned to score-band sampling.               |
| `candidate.score_band_weights`        | comma-separated integer list | `1,1,1,1,2,2,3,5,8,16` | Allocation across score bands from low to high.               |
| `candidate.screen_rows`               | decimal integer              | `2097152`              | Number of generated candidates screened before selection.     |

| Evidence key                                 | Expected type             | Default            | Description                                                           |
|----------------------------------------------|---------------------------|--------------------|-----------------------------------------------------------------------|
| `aggregation.bootstrap_replicates`           | decimal integer           | `1000`             | Bootstrap resamples used to estimate aggregate quality.               |
| `aggregation.bootstrap_seed_hex`             | unsigned 64-bit seed      | `6a09e667f3bcc909` | Seed for deterministic aggregation resampling.                        |
| `aggregation.calibration_acceptance`         | enum constant             | `STRONG_ONLY`      | Which calibration strength is eligible for aggregation.               |
| `aggregation.minimum_success_fraction`       | finite decimal (`double`) | `0.5`              | Minimum successful repetition fraction for usable evidence.           |
| `aggregation.minimum_successful_repetitions` | decimal integer           | `3`                | Minimum successful repetitions for usable evidence.                   |
| `anchors.allow_imported_bootstrap`           | boolean                   | `false`            | Allows imported, rather than native, bootstrap evidence as anchors.   |
| `anchors.fixed_fraction`                     | finite decimal (`double`) | `0.02`             | Fraction of the policy budget reserved for fixed anchors.             |
| `anchors.maximum_bootstrap_non_success_rate` | finite decimal (`double`) | `0.10`             | Maximum non-success rate permitted among bootstrap anchors.           |
| `anchors.maximum_bootstrap_relative_iqr`     | finite decimal (`double`) | `0.25`             | Maximum relative interquartile range permitted for bootstrap anchors. |
| `anchors.minimum_fixed_anchors`              | decimal integer           | `5`                | Minimum number of fixed anchors selected.                             |
| `benchmark.expected_repetitions`             | decimal integer           | `10`               | Benchmark repetitions collected per policy/scenario.                  |
| `benchmark.frames_per_source`                | decimal integer           | `100000`           | Frames generated by each benchmark source.                            |
| `benchmark.liveness_timeout_nanos`           | decimal integer (`long`)  | `50000000`         | Maximum wait for benchmark progress before declaring it stalled.      |
| `benchmark.ordered_frames`                   | boolean                   | `false`            | Preserves ordered frame routing during benchmarks.                    |
| `benchmark.reset_timeout_nanos`              | decimal integer (`long`)  | `2000000000`       | Maximum wait for benchmark reset completion.                          |
| `benchmark.sample_duration_nanos`            | decimal integer (`long`)  | `200000000`        | Duration of each benchmark measurement sample.                        |
| `calibration.maximum_anchor_weight_share`    | finite decimal (`double`) | `0.25`             | Maximum share contributed by one anchor.                              |
| `calibration.maximum_strong_residual`        | finite decimal (`double`) | `0.05`             | Largest residual accepted for strong calibration.                     |
| `calibration.maximum_weak_residual`          | finite decimal (`double`) | `0.15`             | Largest residual accepted for weak calibration.                       |
| `calibration.minimum_log_sigma`              | finite decimal (`double`) | `0.01`             | Lower bound on fitted log-space uncertainty.                          |
| `calibration.minimum_strong_anchors`         | decimal integer           | `5`                | Minimum anchors required for strong calibration.                      |
| `calibration.minimum_weak_anchors`           | decimal integer           | `3`                | Minimum anchors needed for weak calibration.                          |

| Training key                                    | Expected type            | Default            | Description                                                                              |
|-------------------------------------------------|--------------------------|--------------------|------------------------------------------------------------------------------------------|
| `training.ablation_members`                     | decimal integer          | `3`                | Models trained for feature-ablation analysis.                                            |
| `training.batch_size`                           | decimal integer          | `0`                | Training batch size; zero selects the runtime default.                                   |
| `training.device`                               | string                   | `auto`             | Device selection: auto, CPU, or a numbered GPU.                                          |
| `training.ensemble_members`                     | decimal integer          | `5`                | Models trained and combined in the production ensemble.                                  |
| `training.feature_selection_mode`               | enum constant            | `RATIO_ONLY`       | Feature schema used by the scenario model.                                               |
| `training.include_weak_calibration_rows`        | boolean                  | `false`            | Includes weakly calibrated rows in model training.                                       |
| `training.label_smoothing`                      | finite decimal (`float`) | `0.02`             | Softens labels to reduce model overconfidence.                                           |
| `training.learning_rate`                        | finite decimal (`float`) | `0.001`            | Optimizer step size.                                                                     |
| `training.loso_evaluation_members`              | decimal integer          | `1`                | Ensemble members used for leave-one-scenario-out evaluation.                             |
| `training.max_epochs`                           | decimal integer          | `250`              | Maximum training epochs.                                                                 |
| `training.minimum_test_policy_groups`           | decimal integer          | `10`               | Minimum distinct policy groups in the test split.                                        |
| `training.minimum_test_rows_per_scenario`       | decimal integer          | `5`                | Minimum test rows per scenario.                                                          |
| `training.minimum_train_policy_groups`          | decimal integer          | `40`               | Minimum distinct policy groups in the training split.                                    |
| `training.minimum_train_rows_per_scenario`      | decimal integer          | `30`               | Minimum training rows per scenario.                                                      |
| `training.minimum_validation_policy_groups`     | decimal integer          | `10`               | Minimum distinct policy groups in the validation split.                                  |
| `training.minimum_validation_rows_per_scenario` | decimal integer          | `5`                | Minimum validation rows per scenario.                                                    |
| `training.model_seed_hex`                       | unsigned 64-bit seed     | `13198a2e03707344` | Seed for deterministic model initialization.                                             |
| `training.patience`                             | decimal integer          | `20`               | Epochs without improvement before early stopping.                                        |
| `training.split_seed_hex`                       | unsigned 64-bit seed     | `243f6a8885a308d3` | Seed for deterministic dataset splitting.                                                |
| `training.weight_decay`                         | finite decimal (`float`) | `0.0001`           | L2 penalty applied to model weights.                                                     |
| `training.require_target_variation`             | boolean                  | `true`             | Requires training partitions to contain target variation; disables this for cold starts. |

| Evaluation key                                                | Expected type             | Default | Description                                                    |
|---------------------------------------------------------------|---------------------------|---------|----------------------------------------------------------------|
| `evaluation.maximum_context_mae_regression`                   | finite decimal (`double`) | `0.01`  | Maximum MAE regression allowed for context features.           |
| `evaluation.maximum_context_spearman_regression`              | finite decimal (`double`) | `0.02`  | Maximum Spearman regression allowed for context features.      |
| `evaluation.maximum_counts_spearman_regression`               | finite decimal (`double`) | `0.02`  | Maximum Spearman regression allowed for count features.        |
| `evaluation.maximum_counts_worst_environment_mae_regression`  | finite decimal (`double`) | `0.02`  | Maximum worst-environment MAE regression for count features.   |
| `evaluation.maximum_grouped_macro_mae`                        | finite decimal (`double`) | `0.20`  | Maximum grouped macro MAE accepted for the model.              |
| `evaluation.maximum_loso_macro_mae`                           | finite decimal (`double`) | `0.25`  | Maximum leave-one-scenario-out macro MAE accepted.             |
| `evaluation.maximum_loso_worst_scenario_mae`                  | finite decimal (`double`) | `0.35`  | Maximum leave-one-scenario-out MAE in the worst scenario.      |
| `evaluation.minimum_context_mae_improvement`                  | finite decimal (`double`) | `0.01`  | Minimum MAE improvement required from context features.        |
| `evaluation.minimum_context_spearman_improvement`             | finite decimal (`double`) | `0.05`  | Minimum Spearman improvement required from context features.   |
| `evaluation.minimum_counts_cross_environment_mae_improvement` | finite decimal (`double`) | `0.01`  | Minimum cross-environment MAE improvement from count features. |
| `evaluation.minimum_grouped_macro_precision_at_ten`           | finite decimal (`double`) | `0.20`  | Minimum grouped macro precision among the top ten predictions. |
| `evaluation.minimum_grouped_macro_spearman`                   | finite decimal (`double`) | `0.50`  | Minimum grouped macro Spearman correlation.                    |
| `evaluation.minimum_loso_macro_spearman`                      | finite decimal (`double`) | `0.35`  | Minimum leave-one-scenario-out macro Spearman correlation.     |

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
