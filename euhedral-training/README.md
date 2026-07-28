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

The distribution is under `euhedral-training/target/trainer/`. Run its jar directly for CPU work,
or use `target/trainer/bin/euhedral-training-gpu` after following
[GPU_SETUP_UBUNTU.md](GPU_SETUP_UBUNTU.md). Java 21 and the native Euhedral library are required for
physical benchmarks.

The stable commands are:

```text
closed-loop --config <path>
import-current-workspace --source-root <path> --output <path> --bootstrap-count <count>
package-run --workspace <path> --inputs <path> --output-root <path>
```

`closed-loop` reads only its typed configuration file. It does not read `-Dcycle.*` properties.

## One-time current-workspace vector import

The temporary importer preserves useful policy vectors from the workspace layout present before
the robust optimizer upgrade:

```bash
java -jar euhedral-training/target/trainer/euhedral-training-0.0.7-SNAPSHOT.jar \
  import-current-workspace \
  --source-root . \
  --output imported-current-workspace \
  --bootstrap-count 1024
```

The only alternating vector/measurement files it recognizes are:

```text
euhedral-training/input/merger/graviton5-32core-1.txt
euhedral-training/input/merger/graviton5-32core-2.txt
euhedral-training/input/merger/graviton5-32core-3.txt
euhedral-training/input/merger/laptop-1.txt
euhedral-training/input/merger/raw_data.txt
euhedral-training/input/merger/zen4-32core-1.txt
euhedral-training/input/merger/zen4-32core-2.txt
euhedral-training/input/merger/zen4-32core-3.txt
euhedral-training/input/temp/laptop-1.txt
euhedral-training/input/temp/laptop-2.txt
```

It also recognizes `euhedral-training/output/temp_data` as vector-only, and skips these derived
human-readable summaries without parsing them:

```text
euhedral-training/input/temp/graviton5-32core-1.txt
euhedral-training/input/temp/graviton5-32core-3.txt
euhedral-training/input/temp/zen4-32core-1.txt
euhedral-training/input/temp/zen4-32core-2.txt
euhedral-training/output/results.txt
```

Every other file under `euhedral-training/input` or `euhedral-training/output` is rejected and
reported. Filenames are never interpreted as machine, source-count, or run metadata. Legacy
measurement rows lack the required observation identity and are always rejected; zero-filled
repetitions are not guessed to mean success, failure, or timeout. Old models, optimizer state, and
checkpoints are not opened or copied.

The atomically published directory contains:

```text
imported-policies.vectors.csv
bootstrap-policies.vectors.csv
import-report.csv
COMPLETE
```

The complete catalog preserves every unique bit-exact policy. The bootstrap file contains the
requested number in unsigned policy-ID order and is supplied as `run.bootstrap_policies`.
Preserve `import-report.csv`; it is the audit record for accepted, skipped, duplicate, and rejected
content. This command is off by default and is intended to be run once per current workspace.

## Typed closed-loop operation

Create a UTF-8/LF configuration such as:

```properties
run.workspace=output/robust-closed-loop
run.training_run_id=trial-2026-07
run.iterations=3
run.candidate_budget=1024
run.active_environment_id=machine-a
run.bootstrap_policies=imported-current-workspace/bootstrap-policies.vectors.csv
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

Imported vectors are benchmarked natively in every exact required scenario before they can inform
calibration or learning. For required environments on different machines, point each invocation at
the same workspace, change only `run.active_environment_id`, and resume sequentially. The
checkpoint waits until native bootstrap evidence exists for all required scenarios.

Fixed anchors calibrate runs directly in log space. Strong calibration needs five shared anchors
and residual at most `0.05`; weak calibration needs three and residual at most `0.15` by default.
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

`run.resume=true` validates and loads the highest complete checkpoint. Frozen semantic
configuration must match, and a persisted pending schedule is reused. `run.resume=false` rejects an
existing complete checkpoint. Creating `run.stop_file` (default `<workspace>/STOP`) requests a
checkpoint-safe stop at an existing stage boundary; a symlink is not a stop request.

On every normal return the command prints `stage`, `checkpoint`, `package`, and any
`awaiting_scenario` rows.

## Configuration keys

Repeated keys are allowed only for `scenario.required`, `run.initial_observation_bundle`, and
`calibration.reference_override`. A reference override is
`<canonical-scenario>|<benchmark-run-id>`.

| Lifecycle key | Default |
| --- | --- |
| `run.workspace`, `run.training_run_id`, `run.iterations`, `run.candidate_budget` | required |
| `run.active_environment_id`, `run.commit_sha`, `run.dirty_working_tree` | required |
| `scenario.required` | required, repeated |
| `run.scenarios_per_iteration` | `2` |
| `run.scheduler_seed_hex` | `6a09e667f3bcc909` |
| `run.initial_sobol_cursor` | `131072` |
| `run.resume` | `true` |
| `run.stop_file` | `<workspace>/STOP` |
| `run.bootstrap_policies` / `run.initial_calibration_plan` | exactly one required |
| `run.initial_observation_bundle`, `calibration.reference_override` | repeated, empty |

| Budget and generation key | Default |
| --- | --- |
| `budget.exploration_weight`, `budget.carry_forward_weight` | `68`, `25` |
| `budget.leader_revalidation_weight`, `budget.disagreement_audit_weight` | `2`, `5` |
| `candidate.screen_rows`, `candidate.maximum_prediction_rows` | `2097152`, `16384` |
| `candidate.score_band_weights` | `1,1,1,1,2,2,3,5,8,16` |
| `candidate.cma_weight`, `candidate.score_band_weight`, `candidate.direct_sobol_weight` | `8`, `7`, `1` |
| `candidate.cma.enabled`, `.islands`, `.generations`, `.population_size` | `true`, `4`, `12`, `96` |
| `candidate.cma.initial_sigma`, `.minimum_seed_policies` | `0.20`, `10` |

| Evidence key | Default |
| --- | --- |
| `benchmark.expected_repetitions` | `10` |
| `benchmark.sample_duration_nanos`, `benchmark.liveness_timeout_nanos` | `200000000`, `50000000` |
| `benchmark.frames_per_source`, `benchmark.reset_timeout_nanos` | `100000`, `2000000000` |
| `benchmark.ordered_frames` | `false` |
| `anchors.fixed_fraction`, `anchors.minimum_fixed_anchors` | `0.02`, `5` |
| `anchors.maximum_bootstrap_non_success_rate`, `anchors.maximum_bootstrap_relative_iqr` | `0.10`, `0.25` |
| `anchors.allow_imported_bootstrap` | `false` |
| `calibration.minimum_strong_anchors`, `calibration.minimum_weak_anchors` | `5`, `3` |
| `calibration.maximum_strong_residual`, `calibration.maximum_weak_residual` | `0.05`, `0.15` |
| `calibration.minimum_log_sigma`, `calibration.maximum_anchor_weight_share` | `0.01`, `0.25` |
| `aggregation.minimum_successful_repetitions`, `aggregation.minimum_success_fraction` | `3`, `0.5` |
| `aggregation.bootstrap_replicates`, `aggregation.bootstrap_seed_hex` | `1000`, `6a09e667f3bcc909` |
| `aggregation.calibration_acceptance` | `STRONG_ONLY` |

Training keys are `training.split_seed_hex`, `training.model_seed_hex`, `training.device`,
`training.ensemble_members`, `training.loso_evaluation_members`, `training.ablation_members`,
`training.max_epochs`, `training.patience`, `training.batch_size`, `training.learning_rate`,
`training.weight_decay`, `training.label_smoothing`, the six
`training.minimum_{train,validation,test}_{policy_groups,rows_per_scenario}` keys,
`training.include_weak_calibration_rows`, and `training.feature_selection_mode`. Their defaults are
respectively `243f6a8885a308d3`, `13198a2e03707344`, `auto`, `5`, `1`, `3`, `250`, `20`, `0`,
`0.001`, `0.0001`, `0.02`, `40/10/10`, `30/5/5`, `false`, and `RATIO_ONLY`.

Evaluation keys map directly to the immutable thresholds:
`maximum_grouped_macro_mae` (`0.20`), `minimum_grouped_macro_spearman` (`0.50`),
`minimum_grouped_macro_precision_at_ten` (`0.20`), `maximum_loso_macro_mae` (`0.25`),
`minimum_loso_macro_spearman` (`0.35`), `maximum_loso_worst_scenario_mae` (`0.35`),
the four context improvement/regression thresholds (`0.01/0.05/0.01/0.02`), and the three counts
cross-environment thresholds (`0.01/0.02/0.02`). Prefix each with `evaluation.`.

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

## Legacy compatibility

`merge-quantiles`, `merge-vectors`, `train-vector-finder`, and `benchmark` retain the pooled-v0
property interface during Phase 5 only. They do not call the typed closed loop or the importer and
must not be used as evidence for the robust ranking. Phase 7 removes these commands and the pooled
normalization path.

## Removing the temporary importer

TEMPORARY_CURRENT_WORKSPACE_IMPORT_REMOVAL

After all desired workspaces have complete import artifacts:

1. preserve every `import-report.csv`;
2. point configurations at generated bootstrap files or native Phase 3 checkpoints;
3. delete `src/main/java/io/euhedral_execution/training/importer/currentworkspace/`;
4. delete `src/test/java/io/euhedral_execution/training/importer/currentworkspace/`;
5. remove the `import-current-workspace` dispatch, parser, and help from `Runner`;
6. remove this current-workspace import and deletion section;
7. remove importer-only assertions from `RunnerTest`;
8. run the Phase 5 validation commands; and
9. require the following search to return no matches:

```bash
rg -n "TEMPORARY_CURRENT_WORKSPACE_IMPORT_REMOVAL|importer\\.currentworkspace|import-current-workspace" \
  euhedral-training docs
```

Deleting the importer changes no Phase 1-4 schema, comparator, model, scheduler, checkpoint, or
package contract.
