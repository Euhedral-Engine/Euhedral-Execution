# Phase 5 Blueprint: Temporary Current-Workspace Importer and Typed CLI

Status: ready for Prompt 5B implementation

This blueprint settles the only supported pre-upgrade workspace import, the final typed
closed-loop configuration surface, command help, stop/resume behavior, and user documentation.
Prompt 5B must implement these decisions without reopening Phase 1-4 data, statistical,
scheduling, checkpoint, or packaging contracts.

The implementation-model reassessment at the end selects the model and exact context envelope for
Prompt 5B. The provisional model in the original plan is replaced as part of this blueprint.

## Scope

Phase 5:

1. adds one explicit, off-by-default command that reads only the current workspace layout
   inventoried below;
2. preserves every valid 28-weight policy vector from recognized files as a Phase 1 `PolicyVector`;
3. emits a deterministic complete vector catalog and an exact Phase 3 bootstrap-policy file;
4. rejects legacy measurement rows because their required v1 identity cannot be recovered without
   guessing;
5. emits a deterministic report for every recognized and unexpected file;
6. keeps every legacy path, token, and format rule inside one removable package;
7. replaces the transitional no-argument closed-loop command with a strict typed configuration
   file that constructs `ClosedLoopConfig`;
8. supplies a stop file to the production closed-loop services without changing the Phase 3
   checkpoint fingerprint or state machine;
9. documents anchors, fixed robust ranking, budgets, importing, resume, cross-environment
   bootstrap, packages, and package-only reproduction; and
10. provides an exact deletion recipe for the temporary importer.

### Explicit non-goals

- Do not import a legacy measurement by inferring source count, core count, environment, run,
  iteration, cohort, commit, benchmark duration, timestamps, timeout, or failure status.
- Do not reinterpret a zero-filled repetition as a successful zero-throughput observation,
  timeout, failure, or skipped repetition.
- Do not create an imported Phase 1 observation bundle from the current files.
- Do not import or copy a pooled model, DJL directory, `.bin`, optimizer state,
  `state.properties`, `latest-model`, `latest-training-data.txt`, checkpoint, Maven output, or
  result summary.
- Do not add format detection, a version registry, a provider interface, a migration SPI, glob
  discovery, hostname parsing, or support for any older layout.
- Do not change Phase 1 identity, calibration, aggregation, ranking, or CSV schemas.
- Do not change Phase 2 features, model schema, evaluation, or acceptance rules.
- Do not change Phase 3 budgets, scheduling, rotation, run identity, checkpoint schema, or config
  fingerprint material.
- Do not change Phase 4 package identity, manifest, layout, provenance, or the meaning of
  `package-run`.
- Do not remediate the remaining Phase 4 audit test-coverage findings. They remain Phase 4 work.
- Do not delete the pooled-v0 implementation in this phase. Phase 7 owns that deletion after the
  new CLI has replaced its callers.
- Do not read the real user-owned input/output trees from tests or modify them during
  implementation.

## Reconciliation with the completed phases

| Existing contract | Phase 5 decision |
| --- | --- |
| `PolicyVector` validates 28 finite weights and derives exact `p1` identity from raw IEEE-754 lanes. | Every accepted legacy vector is converted immediately to `PolicyVector` and registered with `PolicyRegistry`. |
| `ObservationBundle` requires explicit scenario, run, cohort, iteration, environment, parameters, timestamps, repetitions, statuses, and provenance. | No current file has enough information. Phase 5 emits no observation bundle. |
| `MeasurementEncoding.DIRECT_THROUGHPUT` permits imported throughput only when the rest of the run identity is established. | The encoding is not a license to invent missing metadata; current measurements are rejected. |
| `BootstrapPolicyCsv` requires schema 1, exact raw-bit vector columns, contiguous positions, and exactly the configured policy budget. | The importer writes an exact compatible bootstrap file selected deterministically from the complete imported catalog. |
| `ClosedLoopConfig` is the complete Phase 3 typed input; `ClosedLoopRunner.run()` is a rejecting transitional adapter. | A strict config codec creates the record and `Runner closed-loop --config ...` calls the typed public entry point. The no-argument adapter is removed. |
| `ClosedLoopConfigFingerprint` deliberately excludes active environment, resume, paths, and stop-file path. | Phase 5 does not add stop-file material to the fingerprint. |
| Public `ClosedLoopRunner.run(ClosedLoopConfig)` packages every normal return under `<workspace>/packages`. | The CLI prints the returned checkpoint stage, checkpoint path, awaiting scenarios, and package path. It does not repackage independently. |
| `package-run` rebuilds an immutable package from recorded inputs. | Its exact flags and meaning remain unchanged. |
| Phase 4 conformance is still missing parts of its test matrix. | Phase 5 tests only its own integration and does not claim those Phase 4 findings are resolved. |

`euhedral-training` remains an unnamed Java module. No `module-info.java` or Maven dependency
change is required.

## Current workspace inventory and settled semantics

The inventory was taken on 2026-07-28 from the repository root. Paths below are relative to the
repository root supplied as `--source-root`. They are the entire accepted layout.

### Alternating vector/measurement files

These ten files have an even number of lines. Every odd line has exactly 28 signed-decimal
`Double.doubleToLongBits` tokens and every even line has exactly 10 tokens:

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

The importer validates the whole file before accepting any vector from it. Odd lines are converted
to `PolicyVector`. Even lines are validated as ten finite encoded doubles but are not converted to
observations. The import report contains two rows for each file:

- `POLICY_VECTORS / ACCEPTED / POLICY_VECTORS_IMPORTED`; and
- `LEGACY_MEASUREMENTS / REJECTED / REQUIRED_OBSERVATION_IDENTITY_UNRECOVERABLE`.

The filename fragments `graviton5`, `zen4`, `laptop`, `32core`, `1`, `2`, and `3` have no
normative meaning. They are never parsed as scenario or run metadata.

### Vector-only file

```text
euhedral-training/output/temp_data
```

This file has one 28-token vector per line. Validate the complete file and import all rows as
`PolicyVector`. Its report row is
`POLICY_VECTORS / ACCEPTED / POLICY_VECTORS_IMPORTED`.

### Human-readable summaries

```text
euhedral-training/input/temp/graviton5-32core-1.txt
euhedral-training/input/temp/graviton5-32core-3.txt
euhedral-training/input/temp/zen4-32core-1.txt
euhedral-training/input/temp/zen4-32core-2.txt
euhedral-training/output/results.txt
```

These contain `Top Throughput`, formatted quantiles, and source-code snippets. They are not raw
evidence or machine-readable policy catalogs. Do not parse them. Report each as
`HUMAN_READABLE_SUMMARY / SKIPPED / DERIVED_SUMMARY_NOT_EVIDENCE`.

`euhedral-training/output/merger/` is currently empty and creates no report row.

### Unexpected files and excluded artifacts

Walk only these two known roots, without following symlinks:

```text
euhedral-training/input
euhedral-training/output
```

Every regular file not exactly listed above receives
`UNKNOWN / REJECTED / UNMAPPED_CURRENT_WORKSPACE_PATH` and is never opened as a legacy format.
Every symlink or unsupported file type receives
`UNKNOWN / REJECTED / UNSUPPORTED_OR_SYMLINK_PATH`.

This fail-closed rule excludes any model, `.bin`, `.params`, `state.properties`, checkpoint,
`latest-model`, `latest-training-data.txt`, Maven artifact, or later-created file without adding
speculative path families. Directories themselves are not report records. A missing known file is
not an error and does not create a fictional file row.

### Why legacy measurements are rejected

The alternating files preserve direct throughput values in frames per nanosecond and use a
pre-zeroed ten-element repetition array. They do not preserve all of:

- absolute source count and available physical core count;
- trustworthy machine/environment ID;
- closed-loop iteration, benchmark run ID, and candidate cohort ID;
- commit SHA and dirty state;
- sample duration, liveness timeout, frame count, reset timeout, ordering, or source seeds;
- repetition timestamps; or
- whether a zero slot is a timeout, failure, skipped repetition, or actual zero.

Recovering any of these from filenames, current defaults, file modification times, Git history, or
the position of a line would be guessing. Therefore no measurement is eligible for Phase 1,
calibration, ranking, packaging provenance, or model training. The report makes this loss explicit.

## Temporary package and removal marker

All compatibility production code lives under:

```text
io.euhedral_execution.training.importer.currentworkspace
```

Use this exact marker on the command dispatch, package entry point, focused tests, and README
deletion section:

```text
TEMPORARY_CURRENT_WORKSPACE_IMPORT_REMOVAL
```

No class in `data`, `data.io`, `merge`, `learning`, `optimization`, `scheduling`, `checkpoint`,
`benchmark`, or `packaging` may import this package. `Runner` is the only production caller.
The importer may depend on `PolicyVector`, `PolicyRegistry`, and JDK APIs. It must not depend on
`DataMerger`, the legacy `BenchmarkOutputReader`, a model, checkpoint, scheduler, or packager.
Keeping its decimal-token reader local makes deletion independent of pooled-v0 cleanup.

## Import command and API

The exact command is:

```text
import-current-workspace --source-root <path> --output <path> --bootstrap-count <positive-int>
```

Flags occur exactly once and in that order. The command is off by default: no other command,
startup path, config read, merge, or resume probes for the current workspace.

Add:

```java
public record CurrentWorkspaceImportRequest(
        Path sourceRoot,
        Path outputDirectory,
        int bootstrapPolicyCount) {
}

public record CurrentWorkspaceImportResult(
        Path directory,
        Path policyCatalog,
        Path bootstrapPolicies,
        Path importReport,
        int uniquePolicyCount,
        int bootstrapPolicyCount) {
}

public final class CurrentWorkspaceImporter {
    public static CurrentWorkspaceImportResult importWorkspace(
            CurrentWorkspaceImportRequest request) throws IOException;
}
```

Constructors normalize absolute paths, require an existing non-symlink source root, require a
positive bootstrap count, reject an output inside either scanned input/output tree, and require
that the final output path not exist. The source root may itself contain unrelated repository
files; only the two known subtrees are walked.

Internal immutable types are:

```java
enum CurrentWorkspaceSemanticType {
    POLICY_VECTORS,
    LEGACY_MEASUREMENTS,
    HUMAN_READABLE_SUMMARY,
    UNKNOWN
}

enum CurrentWorkspaceImportStatus {
    ACCEPTED,
    SKIPPED,
    REJECTED
}

record CurrentWorkspaceMapping(
        String relativePath,
        CurrentWorkspaceFileShape shape) {
}

enum CurrentWorkspaceFileShape {
    ALTERNATING_VECTOR_MEASUREMENTS,
    VECTOR_ONLY,
    HUMAN_SUMMARY
}

record CurrentWorkspaceImportReportRow(
        String relativePath,
        CurrentWorkspaceSemanticType semanticType,
        CurrentWorkspaceImportStatus status,
        long recordCount,
        long acceptedCount,
        long duplicateCount,
        long rejectedCount,
        String reason) {
}
```

`CurrentWorkspaceLayout` owns one immutable, lexicographically sorted list containing the sixteen
exact file mappings above. It has no extension API.

## Import parsing and validation

Use a streaming ASCII token reader with a fixed 128 KiB direct or heap buffer.

- Accept only optional leading `-` followed by decimal digits.
- Reject `+`, commas, decimal points, exponent notation, whitespace other than space/tab between
  tokens and LF/optional CRLF at record boundaries, a BOM, empty records, and missing final LF.
- Parse with checked signed-long semantics, including `Long.MIN_VALUE`.
- Convert through `Double.longBitsToDouble`.
- Require all 28 policy values and all ten legacy measurement values to be finite.
- Require exact record widths and the exact alternating shape.
- Convert vector rows immediately to `PolicyVector`; register through one `PolicyRegistry`.
- A repeated bit-identical policy increments `duplicateCount`; a hash collision is fatal.
- Validate one file into a file-local list before merging it into the global registry. A malformed
  mapped file gets one `UNKNOWN / REJECTED / MALFORMED_CURRENT_WORKSPACE_FILE` row and contributes
  no policies. The diagnostic includes the relative path and one-based line/token position, but
  the canonical report reason remains stable.
- Continue processing other files after a mapped-file format rejection. Filesystem/I/O failure,
  policy hash collision, unsafe path, or output publication failure aborts the import.

No measurement value affects vector selection, ordering, ranking, or acceptance.

## Import artifact

Publication produces exactly:

```text
<output>/
+-- imported-policies.vectors.csv
+-- bootstrap-policies.vectors.csv
+-- import-report.csv
+-- COMPLETE
```

All text is UTF-8, RFC 4180 where applicable, LF-terminated, and contains no timestamp or absolute
path. `COMPLETE` is empty.

### Complete policy catalog

`imported-policies.vectors.csv` has:

```text
schema_version,policy_id,weight_00_bits,...,weight_27_bits
```

It contains every unique accepted `PolicyVector` in unsigned `PolicyId` order. Raw bits are
16 lower-case hexadecimal digits. This is a vector-only audit catalog; no measurement or inferred
scenario column is present.

### Bootstrap file

`bootstrap-policies.vectors.csv` uses the exact `BootstrapPolicyCsv` header:

```text
schema_version,bootstrap_position,policy_id,weight_00_bits,...,weight_27_bits
```

Select the first `bootstrapPolicyCount` entries from the unsigned-`PolicyId` sorted complete
catalog and assign contiguous one-based positions. Require
`bootstrapPolicyCount <= uniquePolicyCount`. Reopen through `BootstrapPolicyCsv.read` before
publication. Selection is deliberately independent of rejected measurements, source filenames,
filesystem order, and input duplication.

This file is supplied to `closed-loop` through `run.bootstrap_policies`; its row count must equal
`run.candidate_budget`.

### Import report

`import-report.csv` has:

```text
schema_version,path,semantic_type,status,record_count,accepted_count,duplicate_count,rejected_count,reason
```

Rows sort by path, then semantic type enum order. Counts are non-negative `long` values.
For an alternating file, vector `record_count` is its vector row count and measurement
`record_count` is its measurement row count. Rejected legacy measurements have
`rejected_count == record_count`. Summary and unexpected-file rows use one file record.

The accepted vector count is the number first registered from that file in global mapping order;
duplicates include policies already seen in earlier paths or earlier rows. The catalog count equals
the sum of accepted vector counts.

### Atomic publication and memory

Create a unique sibling named `.<target>.tmp-<UUID>`, write all three CSVs, force them, write and
force `COMPLETE` last, reopen and validate the inventory, report totals, catalog, and bootstrap
file, then publish only with `ATOMIC_MOVE`. There is no fallback and no overwrite.

On failure, remove only the exact temporary sibling created by this invocation, without following
links. Never modify source files or an existing target. Do not retain legacy measurements or
corpus-sized duplicate weight arrays. After file validation, retain only the global
`PolicyRegistry`, file-local vectors for the current file, and report rows.

## Final closed-loop configuration file

The exact command is:

```text
closed-loop --config <path>
```

Add:

```java
package io.euhedral_execution.training.config;

public final class ClosedLoopConfigCodec {
    public static ClosedLoopConfig read(Path path) throws IOException;
    public static String example();
}
```

The file is UTF-8 with a required final LF, no BOM or CR, and one `key=value` per nonblank line.
Lines whose first non-whitespace character is `#` are comments. Keys and values are trimmed.
Unknown keys, duplicate singleton keys, duplicate list values, missing required values, empty
values, malformed escapes, and trailing junk are rejected with line numbers.

There is no environment-variable, `~`, shell, or system-property interpolation. Relative paths
resolve against the config file's parent and are normalized. Paths may not contain NUL. Existence
and artifact validation remain with record constructors and the consuming Phase 1-4 codecs.

Repeated keys are permitted only for:

```text
scenario.required
run.initial_observation_bundle
calibration.reference_override
```

`scenario.required` is a canonical `SourceScenario` string.
`calibration.reference_override` is
`<canonical-scenario>|<benchmark-run-id>`.

Booleans are exactly `true` or `false`. Decimal integers have no leading `+`; seeds are exactly
16 lower-case hexadecimal digits. Floats/doubles use finite Java decimal syntax and are passed to
the target record constructor without rounding. Comma lists contain no empty element.
Enum values use exact Java enum names.

### Required keys

```text
run.workspace
run.training_run_id
run.iterations
run.candidate_budget
run.active_environment_id
run.commit_sha
run.dirty_working_tree
scenario.required
```

Exactly one of `run.bootstrap_policies` and `run.initial_calibration_plan` is required.
`run.initial_observation_bundle` is permitted only with
`run.initial_calibration_plan`. At least one required scenario must match the active environment;
the typed runner also requires its exact visible physical-core count at execution.

### Top-level and lifecycle keys

| Key | Default / mapping |
| --- | --- |
| `run.workspace` | required -> `ClosedLoopConfig.workspace` |
| `run.training_run_id` | required -> `trainingRunId` |
| `run.iterations` | required -> `iterations` |
| `run.candidate_budget` | required -> `candidateBudget` |
| `run.active_environment_id` | required -> `activeEnvironmentId` |
| `run.scenarios_per_iteration` | `2` |
| `run.scheduler_seed_hex` | `6a09e667f3bcc909` |
| `run.initial_sobol_cursor` | `131072` |
| `run.bootstrap_policies` | mutually exclusive optional path |
| `run.initial_calibration_plan` | mutually exclusive optional path |
| `run.initial_observation_bundle` | repeated path, default empty |
| `run.commit_sha` | required lower-case 40/64 hex |
| `run.dirty_working_tree` | required |
| `run.resume` | `true` |
| `run.stop_file` | `<workspace>/STOP`; excluded from config fingerprint |
| `scenario.required` | repeated canonical scenario, natural-order set |
| `calibration.reference_override` | repeated scenario/run mapping, default empty |

`run.scheduler_seed_hex` is interpreted as unsigned raw long bits. The default equals the Phase 3
scheduler/config seed convention; it is distinct from aggregation, split, and model seeds.

### Budget and candidate-generation keys

| Key | Default |
| --- | ---: |
| `budget.exploration_weight` | 68 |
| `budget.carry_forward_weight` | 25 |
| `budget.leader_revalidation_weight` | 2 |
| `budget.disagreement_audit_weight` | 5 |
| `candidate.screen_rows` | 2097152 |
| `candidate.maximum_prediction_rows` | 16384 |
| `candidate.score_band_weights` | `1,1,1,1,2,2,3,5,8,16` |
| `candidate.cma_weight` | 8 |
| `candidate.score_band_weight` | 7 |
| `candidate.direct_sobol_weight` | 1 |
| `candidate.cma.enabled` | true |
| `candidate.cma.islands` | 4 |
| `candidate.cma.generations` | 12 |
| `candidate.cma.population_size` | 96 |
| `candidate.cma.initial_sigma` | 0.20 |
| `candidate.cma.minimum_seed_policies` | 10 |

Map directly to `CandidateBudgetConfig`, `CandidateGenerationConfig`, and `CmaEsConfig`. Their
constructors are authoritative for range and overflow validation.

### Benchmark, anchor, calibration, and aggregation keys

| Key | Default |
| --- | ---: |
| `benchmark.expected_repetitions` | 10 |
| `benchmark.sample_duration_nanos` | 200000000 |
| `benchmark.liveness_timeout_nanos` | 50000000 |
| `benchmark.frames_per_source` | 100000 |
| `benchmark.reset_timeout_nanos` | 2000000000 |
| `benchmark.ordered_frames` | false |
| `anchors.fixed_fraction` | 0.02 |
| `anchors.minimum_fixed_anchors` | 5 |
| `anchors.maximum_bootstrap_non_success_rate` | 0.10 |
| `anchors.maximum_bootstrap_relative_iqr` | 0.25 |
| `anchors.allow_imported_bootstrap` | false |
| `calibration.minimum_strong_anchors` | 5 |
| `calibration.minimum_weak_anchors` | 3 |
| `calibration.maximum_strong_residual` | 0.05 |
| `calibration.maximum_weak_residual` | 0.15 |
| `calibration.minimum_log_sigma` | 0.01 |
| `calibration.maximum_anchor_weight_share` | 0.25 |
| `aggregation.minimum_successful_repetitions` | 3 |
| `aggregation.minimum_success_fraction` | 0.5 |
| `aggregation.bootstrap_replicates` | 1000 |
| `aggregation.bootstrap_seed_hex` | `6a09e667f3bcc909` |
| `aggregation.calibration_acceptance` | `STRONG_ONLY` |

Map directly to `BenchmarkExecutionConfig`, `AnchorSelectionConfig`, `CalibrationConfig`, and
`AggregationConfig`. Imported current-workspace vectors are benchmarked natively during bootstrap;
`anchors.allow_imported_bootstrap` remains false. The key exists for exact typed completeness, not
as an importer shortcut.

### Training and evaluation keys

| Key | Default |
| --- | ---: |
| `training.split_seed_hex` | `243f6a8885a308d3` |
| `training.model_seed_hex` | `13198a2e03707344` |
| `training.device` | `auto` |
| `training.ensemble_members` | 5 |
| `training.loso_evaluation_members` | 1 |
| `training.ablation_members` | 3 |
| `training.max_epochs` | 250 |
| `training.patience` | 20 |
| `training.batch_size` | 0 |
| `training.learning_rate` | 0.001 |
| `training.weight_decay` | 0.0001 |
| `training.label_smoothing` | 0.02 |
| `training.minimum_train_policy_groups` | 40 |
| `training.minimum_validation_policy_groups` | 10 |
| `training.minimum_test_policy_groups` | 10 |
| `training.minimum_train_rows_per_scenario` | 30 |
| `training.minimum_validation_rows_per_scenario` | 5 |
| `training.minimum_test_rows_per_scenario` | 5 |
| `training.include_weak_calibration_rows` | false |
| `training.feature_selection_mode` | `RATIO_ONLY` |

Map directly to `ScenarioTrainingConfig`. `POLICY_ONLY` is not a value of
`FeatureSelectionMode` and is never exposed as deployable configuration.

| Evaluation key | Default |
| --- | ---: |
| `evaluation.maximum_grouped_macro_mae` | 0.20 |
| `evaluation.minimum_grouped_macro_spearman` | 0.50 |
| `evaluation.minimum_grouped_macro_precision_at_ten` | 0.20 |
| `evaluation.maximum_loso_macro_mae` | 0.25 |
| `evaluation.minimum_loso_macro_spearman` | 0.35 |
| `evaluation.maximum_loso_worst_scenario_mae` | 0.35 |
| `evaluation.minimum_context_mae_improvement` | 0.01 |
| `evaluation.minimum_context_spearman_improvement` | 0.05 |
| `evaluation.maximum_context_mae_regression` | 0.01 |
| `evaluation.maximum_context_spearman_regression` | 0.02 |
| `evaluation.minimum_counts_cross_environment_mae_improvement` | 0.01 |
| `evaluation.maximum_counts_spearman_regression` | 0.02 |
| `evaluation.maximum_counts_worst_environment_mae_regression` | 0.02 |

Map directly to `EvaluationThresholds`.

## Stop, resume, and result reporting

Add `Path stopFile` to `ClosedLoopConfig` after `resume`. Normalize it in the constructor. This is
an operational path and remains excluded from `ClosedLoopConfigFingerprint`, as Phase 3 already
settled.

Replace the enum-only production service with a private instance configured with the stop path.
`stopRequested()` returns
`Files.isRegularFile(stopFile, LinkOption.NOFOLLOW_LINKS)`. A symlink at the stop path is not a
stop request. An I/O inspection failure is wrapped in `UncheckedIOException` and propagates before
a new stage rather than being treated as false; the `ClosedLoopServices` signature does not
change. The existing benchmark stop boundaries and checkpoint transitions remain unchanged.

Resume is explicit through `run.resume=true`. The codec does not inspect the workspace to choose
the value. The Phase 3 runner remains authoritative:

- `false` rejects an existing complete checkpoint;
- `true` loads and validates the highest complete checkpoint;
- frozen configuration mismatches fail before mutation; and
- a pending schedule is reused rather than regenerated.

After a normal return, log one concise line each for:

```text
stage=<CheckpointStage>
checkpoint=<absolute path>
package=<absolute path>
awaiting_scenario=<canonical scenario>   [repeated, if any]
```

The package is under `<workspace>/packages/training-run-<package-id>` unless the stable
`package-run --output-root` command is used. Exceptions remain failures; do not print a success
package path when packaging fails.

## CLI help

`Runner` usage must list:

```text
closed-loop --config <path>
import-current-workspace --source-root <path> --output <path> --bootstrap-count <count>
package-run --workspace <path> --inputs <path> --output-root <path>
```

Help states:

- `closed-loop` uses only the typed config file and does not read `-Dcycle.*`;
- the import command preserves vectors only and rejects legacy measurements;
- the importer output is supplied as `run.bootstrap_policies`;
- resume is controlled by `run.resume`;
- creating the configured stop file requests a checkpoint-safe stop;
- package location is printed on return; and
- `package-run` reproduces a package, not the physical benchmark.

Keep pooled standalone commands during Phase 5 but label them `legacy compatibility` in help and
documentation. They must not call the importer or typed closed loop. Phase 7 deletes them.

## Documentation changes

### `euhedral-training/README.md`

Replace the stale pooled closed-loop sequence, `cycle.*` properties, old workspace tree, old
resume/model instructions, and recommendation to pool different machines.

Document:

1. build and launcher instructions that remain valid;
2. `import-current-workspace`, its exact recognized paths, vector-only result, rejection reasons,
   report, and one-time nature;
3. an example typed configuration using two exact scenarios;
4. cross-environment bootstrap: run the same workspace sequentially on each required environment
   until all bootstrap scenarios exist;
5. fixed anchors, direct reference calibration, strong/weak status, and why imported vectors are
   benchmarked natively;
6. the authoritative robust comparator order and full-coverage eligibility;
7. exact budget categories and default 68/25/2/5 weights after anchor reservation;
8. candidate generation defaults and complete predicted curves;
9. `run.resume`, frozen-config validation, persisted schedules, and `run.stop_file`;
10. package location, partial/final IDs, artifact naming, and `package-run`;
11. final config key tables and validation rules; and
12. a clearly marked legacy compatibility section pending Phase 7.

Do not claim that current legacy measurements were imported or that Phase 4's outstanding audit
matrix is complete.

### `docs/ML_CLOSED_LOOP_ARCHITECTURE.md`

Replace the pooled/P99 architecture with the implemented flow:

```text
strict bootstrap vectors
    -> native exact-scenario evidence
    -> fixed anchor calibration
    -> hierarchical Phase 1 merge
    -> scenario-conditioned model
    -> robust predicted scheduling and carry completion
    -> native evidence and post-merge
    -> checkpoint-backed package
```

Explain the policy/scenario/run panel, calibration boundary, three-stage aggregation, complete
versus incomplete pools, schedule/checkpoint publication, memory ownership, and the temporary
vector-only importer boundary. Link to the plan/blueprints instead of duplicating every schema.

### Exact importer deletion recipe

Both the blueprint and README removal section state:

1. confirm every desired current workspace has a complete import artifact and preserve its
   `import-report.csv`;
2. update configs to point at the generated bootstrap file or a native Phase 3 checkpoint;
3. delete
   `euhedral-training/src/main/java/io/euhedral_execution/training/importer/currentworkspace/`;
4. delete
   `euhedral-training/src/test/java/io/euhedral_execution/training/importer/currentworkspace/`;
5. remove the `import-current-workspace` case, parser, and help text from `Runner`;
6. remove the current-workspace import section from `euhedral-training/README.md`;
7. remove importer-only assertions from `RunnerTest`;
8. run the validation commands below; and
9. require
   `rg -n "TEMPORARY_CURRENT_WORKSPACE_IMPORT_REMOVAL|importer\\.currentworkspace|import-current-workspace" euhedral-training docs`
   to return no matches.

No Phase 1-4 class, schema, package manifest, or checkpoint changes during deletion.

## Exact file changes and ownership

Implement in this dependency order:

1. Add importer enums/records and `CurrentWorkspaceLayout` under
   `training/importer/currentworkspace`.
2. Add the package-local streaming decimal-bit reader and deterministic CSV writer/validator.
3. Add `CurrentWorkspaceImporter` with exact mapping, report, catalog, bootstrap selection,
   owned cleanup, and atomic publication.
4. Add `ClosedLoopConfigCodec` under `io.euhedral_execution.training.config`, beside
   `ClosedLoopConfig`; it contains no legacy layout knowledge.
5. Add `stopFile` to `ClosedLoopConfig` and update constructor call sites/tests.
6. Change `ClosedLoopRunner` production services only enough to poll that configured file.
7. Update `Runner` with exact command parsing, typed execution, result logging, and help.
8. Update `euhedral-training/README.md` and `docs/ML_CLOSED_LOOP_ARCHITECTURE.md`.
9. Add the focused tests below and append Prompt 5B completion notes here.
10. Do not edit any Phase 1-4 persisted schema, comparator, scheduler, checkpoint codec, package
    manifest, POM, module descriptor, current workspace input/output file, or generated artifact.

## Memory semantics, memory pollution, and precision

### Memory semantics

Import and config parsing are offline, single-owner operations. Mutable token buffers, file-local
vectors, registry state, report builders, and config maps are thread-confined. Immutable records
are published through ordinary return, and the import directory through atomic rename. No
VarHandle, volatile field, CAS, padded atomic, executor, parallel stream, or pinned thread belongs
in this code.

The stop file is polled only at the existing Phase 3 stage and paused-policy boundaries. It does
not add shared Java state or alter benchmark counter acquire/release semantics.

### Memory pollution

- Stream the large current files with a fixed 128 KiB buffer.
- Retain no legacy measurement arrays or observation objects.
- Retain at most the current file's validated policies plus one global interned policy per unique
  ID.
- Do not use `Files.readAllLines`, `readAllBytes`, memory mapping, streams over all numeric
  tokens, DJL, or `TDigest`.
- Config files are small and may retain parsed scalar strings, but path artifacts are validated by
  their owning codecs.
- Import tests use small fixtures under `@TempDir`, never the real current workspace.

### Mathematical precision

- Legacy signed decimal tokens are treated as exact 64-bit encodings, not decimal floating-point
  values.
- Policy output uses `Double.doubleToRawLongBits` and 16-digit lower-case hex.
- Do not normalize, round, clamp, reorder weights, or use rejected measurements.
- Policy ordering is unsigned `PolicyId`; bootstrap positions are integers.
- The importer performs no throughput conversion, calibration, percentile, quantile, prediction,
  or ranking arithmetic.
- Config decimal values use the target Java primitive parser and the target record constructor's
  exact validation. Seeds preserve raw 64-bit hex.

## Deterministic fixtures and tests

### `CurrentWorkspaceImporterTest`

Use a miniature source root with all three known shapes and unexpected files.

Assert:

- exact mapped alternating vectors become bit-identical `PolicyVector` rows;
- `+0.0` and `-0.0` bits survive and produce their Phase 1 identities;
- ten legacy measurements are validated but all are rejected with the settled reason;
- a vector-only file is accepted;
- summaries are skipped without parsing their contents;
- an unexpected `.bin`, `state.properties`, model member, and ordinary text file are rejected
  without being copied;
- duplicate vectors across and within files are counted once and a collision aborts;
- malformed widths, odd alternating rows, invalid signed decimal, overflow, non-finite vector,
  non-finite measurement, BOM, missing final LF, symlink, and unsupported file type receive the
  settled rejection or fatal behavior;
- one malformed mapped file contributes no partial vectors while other mapped files are reported;
- catalog and bootstrap raw bits, headers, order, and contiguous positions are exact;
- bootstrap count above the unique count fails before publication;
- shuffled directory creation order produces byte-identical output;
- two empty output parents produce recursively byte-identical artifacts;
- an existing target is untouched and rejected;
- injected write/read-back/atomic-move failures remove only the owned temporary sibling; and
- a multi-buffer fixture proves streaming behavior without reading the real workspace.

### `ClosedLoopConfigCodecTest`

Assert:

- a minimal file plus defaults creates the exact expected `ClosedLoopConfig`;
- every key above overrides exactly its corresponding record component;
- repeated scenarios become natural-order immutable entries;
- repeated initial bundles and reference overrides retain deterministic semantics;
- relative paths resolve against the config parent;
- default stop path is `<workspace>/STOP`;
- raw seed bits round-trip, including a value above `Long.MAX_VALUE`;
- comments and blank lines are accepted;
- unknown/duplicate/missing keys, duplicate list values, bad boolean/enum/seed/number, NaN,
  infinity, BOM, CR, missing final LF, malformed scenario/reference, invalid path relation,
  both/neither bootstrap sources, bundle without plan, and config constructor failures are
  rejected with the key and line;
- changing `run.stop_file`, active environment, or resume does not change
  `ClosedLoopConfigFingerprint`, while every frozen semantic key does; and
- no system property or environment variable changes parsed output.

### `RunnerTest`

Use package-private command helpers and fakes; do not run native benchmarks.

Assert:

- exact `closed-loop`, import, and package-run flag forms dispatch once;
- unknown, missing, duplicate, reordered, or extra flags fail;
- `closed-loop` passes the decoded typed config and prints stage/checkpoint/package/awaiting rows;
- a stop-file fake is observed only through the settled service boundary;
- import reports the absolute output plus unique/bootstrap counts;
- help labels legacy commands and explains vector-only import and package-only reproduction; and
- no command implicitly invokes the importer.

### Deletion-boundary validation

The focused test package contains the removal marker. In addition to unit tests, use `rg` to prove:

- only `Runner` outside the temporary package imports it;
- no Phase 1-4 package contains a legacy current path or marker; and
- deleting the temporary main/test directories plus the enumerated Runner/docs lines leaves the
  typed config, closed loop, and package command source graph intact.

## Prompt 5B acceptance criteria

Prompt 5B is complete only when:

- the importer runs only through its explicit command;
- only the sixteen exact current paths receive recognized semantics;
- every unexpected or unsafe file is reported and never guessed;
- every valid vector is converted immediately to exact `PolicyVector` identity;
- no current legacy measurement becomes an observation, scenario result, rank, model row, or
  packaged imported bundle;
- old models, optimizer state, and checkpoints are never opened as import sources;
- output contains the exact complete catalog, bootstrap file, report, and completion marker;
- bootstrap selection and all output bytes are deterministic;
- publication is atomic, collision-safe, and never mutates source or an existing target;
- all legacy knowledge and marker occurrences are inside the removable boundary, Runner dispatch,
  focused tests, and the documented deletion section;
- `closed-loop --config` constructs the complete typed Phase 3 configuration without system
  properties or hidden defaults beyond those enumerated here;
- anchor, calibration, aggregation, budget, candidate, benchmark, training, and evaluation
  settings map exactly to their owning immutable records;
- stop and resume preserve the Phase 3 state-machine and fingerprint rules;
- successful returns disclose the exact checkpoint, package, and awaiting scenarios;
- help and documentation describe fixed robust ranking, coverage, importer limitations, resume,
  cross-environment execution, and package locations;
- `package-run` retains its Phase 4 meaning;
- Phase 1-4 regression tests remain green; and
- user-owned current input/output data remains untouched and excluded from the commit.

## Validation commands for Prompt 5B

From the repository root:

```bash
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -am install -Dmaven.test.skip=true

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training \
    -Dtest=CurrentWorkspaceImporterTest,ClosedLoopConfigCodecTest,RunnerTest,ClosedLoopRunnerTest \
    test

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test

git diff --check
git status --short
```

Run:

```bash
rg -n "graviton5|zen4-32core|input/temp|output/temp_data|TEMPORARY_CURRENT_WORKSPACE_IMPORT_REMOVAL" \
  euhedral-training/src/main/java

rg -n "importer\\.currentworkspace" \
  euhedral-training/src/main/java/io/euhedral_execution/training/data \
  euhedral-training/src/main/java/io/euhedral_execution/training/merge \
  euhedral-training/src/main/java/io/euhedral_execution/training/learning \
  euhedral-training/src/main/java/io/euhedral_execution/training/optimization \
  euhedral-training/src/main/java/io/euhedral_execution/training/scheduling \
  euhedral-training/src/main/java/io/euhedral_execution/training/checkpoint \
  euhedral-training/src/main/java/io/euhedral_execution/training/benchmark \
  euhedral-training/src/main/java/io/euhedral_execution/training/packaging

rg -n "System\\.getProperty|Integer\\.getInteger|Long\\.getLong" \
  euhedral-training/src/main/java/io/euhedral_execution/training/config/ClosedLoopConfigCodec.java \
  euhedral-training/src/main/java/io/euhedral_execution/training/importer/currentworkspace
```

The first search may match only the temporary package and `Runner` marker/dispatch. The second and
third searches must return no matches.

Also inspect:

```bash
git diff --name-only
git diff -- docs/robust-training-optimizer/blueprints/05-temporary-importer-cli.md
git diff -- docs/ROBUST_TRAINING_OPTIMIZER_PLAN.md
```

During Prompt 5B, only the enumerated Phase 5 source/tests/docs and this blueprint completion record
may be new changes, in addition to the pre-existing user-owned workspace files.

## Risks and later-phase handoff

There is no unresolved Phase 5 design blocker.

- The current workspace contributes vectors, not evidence. Users must run native bootstrap
  scenarios before calibration or learning.
- A user who can establish missing measurement metadata from an external authoritative source
  needs a new explicit data-ingestion blueprint; this importer must not grow a metadata override
  language.
- Phase 6 must exercise the typed config, imported bootstrap vectors, multi-environment wait/resume,
  final post-merge, and package in its end-to-end synthetic audit.
- Phase 7 deletes the temporary importer using the exact recipe above and separately removes the
  pooled-v0 compatibility code.
- The remaining Phase 4 test-matrix findings remain visible and are not reclassified by Phase 5.

Prompt 5B must append completion notes below this line with changed files, commands, results,
fixture evidence, environmental limitations, and deviations. Any deviation that imports
measurements, changes the recognized mapping, broadens format discovery, changes config keys,
changes stop/resume semantics, or changes Phase 1-4 contracts requires another blueprint pass.

## Implementation model reassessment

### Actual implementation demands

The implementation crosses one module but several ownership boundaries:

- a removable compatibility package with sixteen exact mappings and two strict legacy shapes;
- streaming parsing of multi-megabyte files, exact bit preservation, global deduplication, and
  per-file transactional acceptance;
- collision-safe atomic directory publication and deterministic CSV validation;
- a strict configuration codec mapping more than sixty keys into nine nested immutable records;
- `ClosedLoopConfig`, production stop polling, `Runner`, and two user-facing documents;
- focused parser, filesystem-failure, config-mapping, command, and deletion-boundary tests; and
- regression compatibility with Phase 1-4.

The importer performs no concurrency or statistical mathematics, and the blueprint eliminates the
largest migration ambiguity by rejecting measurements. Nevertheless, this is not a local
low-effort edit: compile repair spans constructors and tests, while filesystem safety, deterministic
serialization, complete config mapping, and the removable dependency boundary interact.

Earlier Phase 3 evidence shows that `gpt-5.5 / low` omitted broad state-machine and acceptance
surfaces even with a detailed blueprint. Phase 5 is narrower than Phase 3 or Phase 4 but still
requires reliable long-context integration.

### Selected implementation model and effort

Prompt 5B must use **`gpt-5.6-sol` at `medium` reasoning effort**. This provides the required coding
and integration capability without using `high` for a design that is now fully settled. Do not use
the provisional `gpt-5.5 / low` selection.

The exact minimal context envelope is:

1. `AGENTS.md`;
2. `docs/AGENT_WORKFLOW.md`;
3. the Phase 5 section of `docs/ROBUST_TRAINING_OPTIMIZER_PLAN.md`;
4. this blueprint in full;
5. `docs/robust-training-optimizer/audits/04-final-packaging-conformance.md` only to preserve its
   outstanding findings;
6. the production files named in the implementation checklist;
7. `PolicyVector`, `PolicyRegistry`, `BootstrapPolicyCsv`, all config record types, and their
   focused tests; and
8. the current `Runner`, training README, and ML architecture document.

Do not preload all prior blueprints. This blueprint contains their settled Phase 5 mappings.
Consult a prior blueprint only if implementation finds a direct contract conflict, and stop rather
than choose a new architecture.

If `gpt-5.6-sol / medium` is unavailable, request an explicit alternative instead of silently
downgrading.

## Naming and package cleanup record

The 2026-07-29 organization pass places `ClosedLoopConfigCodec` and its focused test in the
existing `training.config` package beside `ClosedLoopConfig`. The temporary importer remains a
single isolated `training.importer.currentworkspace` subtree because that package is its explicit
removal boundary. No importer mapping, configuration key, stop/resume rule, memory ownership,
numeric parsing, or precision contract changes.
