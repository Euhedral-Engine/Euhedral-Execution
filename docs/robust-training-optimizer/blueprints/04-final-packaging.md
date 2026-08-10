# Phase 4 Blueprint: Atomic Final Training-Run Packaging

This blueprint settles the Phase 4 package layout, lifecycle classification, schemas, provenance,
deterministic serialization, atomic publication, reports, integration, and tests. Prompt 4B must
implement these decisions without reopening filesystem, lifecycle, naming, or manifest design.

The implementation model selection is reassessed at the end of this blueprint. The provisional
`gpt-5.5 / low` selection in the original plan is not sufficient for this phase.

## Scope

Phase 4:

1. publishes one immutable, self-describing package for every normal `ClosedLoopRunner.run`
   return, including recoverable partial returns and terminal model rejection;
2. packages the latest checkpoint-consistent Phase 1 merge, Phase 2 model, Phase 3 schedule,
   complete raw evidence, checkpoint state, and package-derived vector/measurement views;
3. writes a canonical manifest with a file-level inventory, SHA-256 checksums, semantic types,
   schema versions, logical CSV row counts, producing stages, source run IDs, provenance, and
   completeness;
4. writes deterministic Markdown reports and a top-level README that can be understood without
   opening the machine-readable artifacts;
5. validates every source and staged artifact before one atomic directory move; and
6. exposes a package-only reproduction command that rebuilds identical package bytes from the same
   immutable workspace checkpoint.

### Explicit non-goals

- Do not rerun benchmarks, retrain a model, reschedule candidates, or change Phase 1-3 math.
- Do not package pooled-v0 files, historical models, old checkpoints, temporary directories,
  incomplete evidence bundles, logs, Maven output, or the user-owned current workspace data.
- Do not invent the Phase 5 current-workspace importer or its general closed-loop CLI.
- Do not promise byte-identical benchmark measurements or cross-device model training. The
  reproduction command reproduces this package from immutable inputs; it does not repeat the
  physical experiment.
- Do not archive every historical merge, model, schedule, or checkpoint. Package the checkpoint
  snapshot and the latest checkpoint-consistent artifacts described below. Preserve all complete raw
  evidence because it is the durable experimental input.
- Do not use ZIP, TAR, symlinks, hard links, or absolute paths inside package metadata.
- Do not modify, rename, clean, or delete source workspace artifacts during packaging.

## Reconciliation with the completed phases

The stable inputs are:

- Phase 1 merge directories contain exactly `fixed-anchors.csv`, `reference-runs.csv`,
  `calibration-report.csv`, `scenario-results.csv`, `robust-ranking.csv`,
  `coverage-report.csv`, `robust-leaders.vectors.csv`, and
  `incomplete-policies.vectors.csv`.
- Phase 2 model directories contain canonical `model-metadata.json`, four evaluation/history CSV
  files, and `members/member-NNN/*.params`.
- Phase 3 schedules contain five canonical CSV files plus empty `COMPLETE`.
- Phase 3 checkpoints contain seven canonical CSV files plus empty `COMPLETE`, and their artifact
  references are workspace-relative with hashes.
- Complete raw evidence bundles contain `run.csv`, `policies.csv`, `observations.csv`, and empty
  `COMPLETE`.
- `ClosedLoopCheckpoint` is the authoritative snapshot. Package only artifacts selected from one
  exact checkpoint revision; never select a file merely because it is newest by directory listing or
  modification time.

The Phase 3 workspace is append-only after artifact publication. Packaging may therefore release and
reacquire the workspace lock between closed-loop execution and copying: an exact checkpoint and
every artifact it references remain immutable. The packager still acquires `WorkspaceLock`
while resolving and validating its source set so another process cannot create an ambiguous
same-revision package concurrently.

Phase 3 did not retain a `latestSchedule` reference after a completed merge. Phase 4 does not change
checkpoint schema 1. It selects the schedule deterministically:

1. use `pendingSchedule` when present;
2. otherwise, when `nextIteration > 1`, use
   `iterations/iteration-%06d/schedule` for `nextIteration - 1`; and
3. otherwise no normal-iteration schedule exists.

The selected schedule must pass `ScheduleCodec.read` using the frozen packaging inputs and, when
selected by rule 2, every schedule run ID must occur in checkpoint evidence with
`EvidenceSource.ITERATION`. A complete or post-iteration checkpoint fails packaging if this derived
schedule is absent or inconsistent. An initial/bootstrap partial package records a deterministic
schedule omission instead.

## Lifecycle and package identity

### Status

`TrainingRunPackageStatus` belongs to `io.euhedral_execution.training.packaging.enums`.

```java
public enum TrainingRunPackageStatus {
    COMPLETE,
    PARTIAL_RECOVERABLE,
    PARTIAL_TERMINAL
}
```

The exact mapping is:

| Checkpoint stage  | Package status        | Run complete |
|-------------------|-----------------------|--------------|
| `RUN_COMPLETE`    | `COMPLETE`            | `true`       |
| `MODEL_REJECTED`  | `PARTIAL_TERMINAL`    | `false`      |
| every other stage | `PARTIAL_RECOVERABLE` | `false`      |

An exception before `ClosedLoopRunner` returns is not silently converted into a successful partial
result. The last complete checkpoint remains packageable through the explicit `package-run`
command. A clean stop or cross-environment bootstrap wait already returns a checkpoint-backed result
and is packaged normally.

### Identity and target name

The package ID is deterministic:

```text
COMPLETE:             <trainingRunId>
PARTIAL_*:            <trainingRunId>.partial.r<eight-digit-checkpoint-revision>
target directory:     training-run-<packageId>
```

The default output root is `<workspace>/packages`. The output root is not included in any serialized
bytes. A caller may select another output root.

If the target does not exist, publish it. If it exists, validate it fully:

- return it unchanged only when package ID, training run ID, checkpoint revision, checkpoint
  directory hash, producer commit, dirty flag, manifest bytes, and every payload checksum match;
- otherwise throw `PackageCollisionException`;
- never choose a timestamp suffix, overwrite, merge into, or delete the target.

This makes a repeated package request for the same immutable revision idempotent while preserving
the never-overwrite rule. A later partial revision has another package ID. The final complete
package does not collide with any partial package.

## Exact package layout

An available artifact is copied or derived at exactly the path below. Optional groups are absent
only when the lifecycle table permits an omission; the manifest records why.

```text
training-run-<package-id>/
+-- README.md
+-- manifest.json
+-- fixed-anchors.csv
+-- reference-runs.csv
+-- robust-ranking.csv
+-- scenario-results.csv
+-- calibration-report.csv
+-- coverage-report.csv
+-- policy-scenario-measurements.csv
+-- vectors/
|   +-- robust-leaders.vectors.csv
|   +-- benchmark-ready.vectors.csv
|   +-- incomplete-promising.vectors.csv
+-- reports/
|   +-- robust-ranking.md
|   +-- source-scenario-comparison.md
+-- model/
|   +-- model-metadata.json
|   +-- grouped-evaluation.csv
|   +-- loso-evaluation.csv
|   +-- ablation-evaluation.csv
|   +-- training-history.csv
|   +-- members/member-NNN/euhedral-scenario-ordinal-0000.params
+-- scheduler/
|   +-- runs.csv
|   +-- policies.csv
|   +-- predictions.csv
|   +-- budget-report.csv
|   +-- carry-admissions.csv
|   +-- COMPLETE
+-- checkpoints/
|   +-- latest/
|       +-- state.csv
|       +-- required-scenarios.csv
|       +-- rotation-cursors.csv
|       +-- evidence-index.csv
|       +-- carry-forward.csv
|       +-- carry-forward-scenarios.csv
|       +-- pending-runs.csv
|       +-- COMPLETE
+-- provenance/
|   +-- package-inputs.properties
+-- raw-data/
    +-- index.csv
    +-- bundles/
        +-- <benchmark-run-id>/
            +-- run.csv
            +-- policies.csv
            +-- observations.csv
            +-- COMPLETE
```

The layout is intentionally shallow. The two nested directory families are required because model
member paths are part of the Phase 2 schema and raw bundle-local filenames are part of the Phase 1
schema. Do not flatten or rename those schema-owned files.

### Artifact selection by stage

- `checkpoints/latest`, `provenance/package-inputs.properties`, `raw-data/index.csv`, all complete
  checkpoint-indexed evidence bundles, `README.md`, both reports, and `manifest.json` are always
  present.
- The six Phase 1 top-level datasets, the two Phase 1 vector files, and
  `policy-scenario-measurements.csv` are present whenever `latestMerge` is present. Every stage
  except `BOOTSTRAP_PENDING` requires them.
- `model/` is present exactly when `latestModel` is present. A rejected model is copied for audit
  and clearly labeled rejected; it is never called deployable.
- `scheduler/` and `vectors/benchmark-ready.vectors.csv` are present when the selected normal
  schedule exists. `RUN_COMPLETE`, `SCHEDULE_READY`, `BENCHMARKING`, and `READY_TO_MERGE` require
  them.
- `vectors/robust-leaders.vectors.csv` and
  `vectors/incomplete-promising.vectors.csv` are byte-for-byte copies of Phase 1
  `robust-leaders.vectors.csv` and `incomplete-policies.vectors.csv`. The descriptive package
  filenames distinguish their use without changing schema-owned bytes.

No absent artifact is represented by an empty placeholder. The manifest `omissions` array and README
say that it is unavailable at this checkpoint.

## Derived machine-readable artifacts

All derived text uses canonical UTF-8, no BOM, LF line endings, and a final LF. CSV is RFC 4180 with
LF record endings and `CanonicalCsv.row` escaping. No timestamp, absolute path, platform separator,
locale-sensitive value, or filesystem order enters deterministic output.

### `policy-scenario-measurements.csv`

This is the unmistakable vectors-with-measurements dataset. Join every `scenario-results.csv` row to
exactly one vector from the union of both Phase 1 vector files. Reject a missing vector, conflicting
vector bits, duplicate policy, unknown policy, changed scenario order, or extra vector that is
absent from `robust-ranking.csv`.

Rows retain the exact Phase 1 scenario/policy order. The header is:

```text
schema_version,scenario_id,environment_id,source_count,available_physical_core_count,source_ratio_numerator,source_ratio_denominator,policy_id,weight_00_bits,...,weight_27_bits,status,total_run_count,accepted_run_count,weak_run_count,uncalibrated_run_count,successful_repetition_count,planned_repetition_count,throughput_p25,throughput_median,throughput_p75,throughput_iqr,median_within_run_relative_iqr,mean_timeout_rate,mean_failure_rate,mean_non_success_rate,bootstrap_median_ci_low,bootstrap_median_ci_high,quality
```

All fields are copied byte-semantically from Phase 1 except the inserted raw-bit vector columns. Do
not parse and reformat derived doubles.

### `vectors/benchmark-ready.vectors.csv`

This is a vector-only exact scheduled-cohort view, not a measurement dataset. Rows are selected
schedule policies in `SourceScenario` order and ascending `schedule_position`; repeated policies
across scenarios remain repeated because their roles and run placement differ.

```text
schema_version,scenario_id,benchmark_run_id,schedule_position,policy_id,roles,weight_00_bits,...,weight_27_bits
```

Values are copied from the validated `scheduler/policies.csv`. The filename and header make the
scenario/run context explicit. The packager does not emit the old headerless benchmark input.

### `raw-data/index.csv`

Rows are sorted by unsigned benchmark run ID text, which is equivalent to natural order for fixed
`r1-` hexadecimal IDs:

```text
schema_version,benchmark_run_id,closed_loop_iteration,scenario_id,evidence_source,evidence_origin,package_relative_path,artifact_sha256,started_at,completed_at,policy_count,observation_count,complete
```

- `evidence_source` is the checkpoint enum (`INITIAL`, `BOOTSTRAP`, or `ITERATION`).
- `evidence_origin` is the bundle descriptor enum (`NATIVE` or `IMPORTED`). This, rather than
  filename or directory, distinguishes upgraded-run measurements from imported measurements.
- `package_relative_path` is `raw-data/bundles/<benchmarkRunId>`.
- `artifact_sha256` is the Phase 3 directory fingerprint of the source bundle and must equal the
  checkpoint reference before copying.
- `observation_count` is counted through `ObservationBundleReader.stream`; do not retain the
  observation corpus.
- `complete` is always `true`; incomplete bundles are never indexed or copied.

## Manifest schema

`manifest.json` is canonical UTF-8 JSON, two-space indented, LF terminated, with the exact key order
below. It contains no generation time or absolute path.

```text
artifact_type                 "euhedral-training-run-package"
schema_version                1
package_id                    string
training_run_id               string
checkpoint_revision           integer
checkpoint_stage              CheckpointStage name
status                        TrainingRunPackageStatus name
run_complete                  boolean
config_sha256                 64 lower-case hex
checkpoint_sha256             64 lower-case hex
producer                      object
required_scenarios            array<object>
coverage_rule                 "all-required-scenarios-valid-v1"
calibration_acceptance        enum string or null
winning_policy_ids            array<string>
files                         array<object>
omissions                     array<object>
```

`producer` keys are `commit_sha` and `dirty_working_tree`. Required-scenario objects use the Phase 2
canonical field order: `scenario_id`, `environment_id`, `source_count`,
`available_physical_core_count`, `source_ratio_numerator`, and `source_ratio_denominator`.
`winning_policy_ids` contains at most the first ten eligible policies in published rank order; it is
empty when no merge is available.

File entries are sorted by Unicode code-unit order of `/`-separated package-relative path:

```text
path                           string
semantic_type                  ArtifactSemanticType name
media_type                     string
schema_version                 integer or null
row_count                      integer or null
sha256                         64 lower-case hex
producing_stage                ProducingStage name
source_run_ids                 sorted array<string>
origin                         ArtifactOrigin name
complete                       boolean
```

`manifest.json` is not recursively listed in `files`. Every other regular file, including
`README.md` and empty source `COMPLETE` markers, is listed exactly once. Directories are not
entries. Every listed file in a published package has `complete=true`: it is a complete immutable
artifact as of this checkpoint. Run incompleteness is represented by `run_complete=false` and
explicit omissions, never by copying truncated files.

```java
public enum ArtifactSemanticType {
    PACKAGE_README,
    MERGE_DATASET,
    VECTOR_ONLY_DATASET,
    VECTOR_WITH_MEASUREMENTS_DATASET,
    HUMAN_READABLE_REPORT,
    MODEL_METADATA,
    MODEL_EVALUATION_DATASET,
    MODEL_MEMBER_PARAMETERS,
    SCHEDULE_DATASET,
    COMPLETION_MARKER,
    CHECKPOINT_STATE,
    CHECKPOINT_SIDECAR,
    PACKAGE_REPRODUCTION_INPUT,
    RAW_DATA_INDEX,
    RAW_RUN_METADATA,
    RAW_POLICY_CATALOG,
    RAW_OBSERVATIONS
}

public enum ProducingStage {
    PACKAGE,
    MERGE,
    LEARNING,
    SCHEDULING,
    CHECKPOINT,
    BENCHMARK_EVIDENCE
}

public enum ArtifactOrigin {
    UPGRADED_RUN,
    IMPORTED_CURRENT_WORKSPACE,
    MIXED,
    NOT_APPLICABLE
}
```

For a file whose source run IDs are nonempty, origin is:

- `UPGRADED_RUN` when every bundle has `EvidenceOrigin.NATIVE`;
- `IMPORTED_CURRENT_WORKSPACE` when every bundle has `EvidenceOrigin.IMPORTED`; or
- `MIXED` otherwise.

Files without experimental source runs use `NOT_APPLICABLE`. Do not infer origin from
`EvidenceSource.INITIAL`.

CSV `schema_version` is the single exact integer in the first column of all data rows and must agree
with the header; empty data CSVs still have the schema known by contract. Markdown, property files,
binary parameters, and completion markers use `null`. `row_count` is the count of logical data
records excluding a CSV header. It is `null` for non-CSV files and completion markers.

Omissions are sorted by `semantic_group`:

```text
semantic_group                 "MERGE" | "MODEL" | "SCHEDULE"
reason                         stable reason string
required_for_complete_run      boolean
```

Stable reasons are `NOT_YET_CALIBRATED`, `NOT_YET_TRAINED`,
`NO_NORMAL_ITERATION_SCHEDULE_AT_CHECKPOINT`, and `MODEL_REJECTED_BEFORE_SCHEDULING`. A complete
package has an empty omissions array.

`PackageManifestCodec` must reject duplicate/unknown/missing keys, unknown enums or versions,
noncanonical relative paths, unsorted arrays, duplicate files or run IDs, non-lowercase hashes,
negative counts, `run_complete`/status/stage disagreement, and a nonempty complete-run omission
list. Encoding a decoded canonical manifest must reproduce the original bytes.

## File-to-manifest mapping

| Package files                      | Semantic type                      | Producing stage           | Source run IDs                                      |
|------------------------------------|------------------------------------|---------------------------|-----------------------------------------------------|
| `README.md`                        | `PACKAGE_README`                   | `PACKAGE`                 | all checkpoint evidence                             |
| six top-level copied merge CSVs    | `MERGE_DATASET`                    | `MERGE`                   | all evidence in latest merge                        |
| `policy-scenario-measurements.csv` | `VECTOR_WITH_MEASUREMENTS_DATASET` | `PACKAGE`                 | latest-merge evidence                               |
| three files under `vectors/`       | `VECTOR_ONLY_DATASET`              | source phase or `PACKAGE` | merge or selected-schedule evidence                 |
| `reports/*.md`                     | `HUMAN_READABLE_REPORT`            | `PACKAGE`                 | latest-merge evidence                               |
| `model/model-metadata.json`        | `MODEL_METADATA`                   | `LEARNING`                | evidence through the merge used to train that model |
| model CSVs                         | `MODEL_EVALUATION_DATASET`         | `LEARNING`                | same as model metadata                              |
| model `.params`                    | `MODEL_MEMBER_PARAMETERS`          | `LEARNING`                | same as model metadata                              |
| scheduler CSVs                     | `SCHEDULE_DATASET`                 | `SCHEDULING`              | evidence available before that schedule iteration   |
| scheduler `COMPLETE`               | `COMPLETION_MARKER`                | `SCHEDULING`              | same as scheduler CSVs                              |
| checkpoint `state.csv`             | `CHECKPOINT_STATE`                 | `CHECKPOINT`              | all checkpoint evidence                             |
| other checkpoint CSVs              | `CHECKPOINT_SIDECAR`               | `CHECKPOINT`              | all checkpoint evidence                             |
| checkpoint `COMPLETE`              | `COMPLETION_MARKER`                | `CHECKPOINT`              | all checkpoint evidence                             |
| package inputs                     | `PACKAGE_REPRODUCTION_INPUT`       | `PACKAGE`                 | none                                                |
| raw index                          | `RAW_DATA_INDEX`                   | `PACKAGE`                 | all checkpoint evidence                             |
| bundle files                       | matching raw semantic type         | `BENCHMARK_EVIDENCE`      | that one run                                        |
| bundle `COMPLETE`                  | `COMPLETION_MARKER`                | `BENCHMARK_EVIDENCE`      | that one run                                        |

Model source run IDs are determined from the six-digit model iteration in its validated
`models/model-NNNNNN` source path. Model iteration `i` was trained from merge `i - 1`; include
checkpoint evidence whose bundle descriptor `closedLoopIteration < i`. Reject a model path that does
not match this identity. Schedule source run IDs likewise have
`closedLoopIteration < schedule.iteration`. These rules prevent the final post-benchmark merge from
being falsely claimed as model training input.

## README and reports

All Markdown is generated from validated canonical CSV/domain views, not by ad hoc string searches.
Escape `|`, backslash, CR, and LF in table cells. Derived doubles retain their source strings.

### `README.md`

Use these headings in order:

1. `# Euhedral training run <trainingRunId>`
2. `## Status`
3. `## Winning policies`
4. `## Required source scenarios`
5. `## Coverage and ranking rule`
6. `## Calibration health`
7. `## Model`
8. `## Package guide`
9. `## Provenance`
10. `## Reproduce this package`

Status states the checkpoint stage, revision, package status, whether more execution is required,
and every omission. Winning policies is a table of up to ten eligible published ranks with policy
ID, worst quality, quality P25, geometric mean quality, dispersion MAD, mean non-success rate, and
mean timeout rate. If no eligible policy exists, state that explicitly.

The scenario table lists canonical scenario ID, environment, absolute sources, physical cores, and
reduced ratio. Coverage text states that publication eligibility requires a valid result for every
required exact scenario and that missing/rejected scenarios are not imputed. Calibration health
reports reference, strong, weak, and failed run counts by calibration `status`/`reason`, and names
the configured acceptance mode. The model section states absent, accepted/deployable, or rejected
with the exact acceptance reasons and dataset fingerprint.

The package guide labels:

- `vectors/*.vectors.csv` as vector-only;
- `policy-scenario-measurements.csv` as vectors with measurements;
- top-level and model/scheduler/checkpoint/raw CSVs as machine-readable datasets; and
- `README.md` plus `reports/*.md` as human-readable reports.

Provenance shows producer commit and dirty flag, native/imported/mixed evidence counts, config and
checkpoint hashes, and raw-data location.

The reproduction section contains this exact POSIX-shell command, wrapped for Markdown but not
shell-evaluated while generating it:

```text
"$EUHEDRAL_TRAINER" package-run --workspace ../.. --inputs provenance/package-inputs.properties --output-root "$OUTPUT_ROOT"
```

It explains that the command is run from the package directory, `EUHEDRAL_TRAINER` names the built
launcher or `java -jar` wrapper, and `OUTPUT_ROOT` must be a writable destination. The properties
file contains the original package ID and revision, so a reproduced package has byte-identical
payload and manifest bytes. The source workspace and exact checkpoint must remain available. Phase 5
may add a command that reproduces/resumes the physical training run, but it must not change this
package-only command's meaning.

### `reports/robust-ranking.md`

Include:

- the comparator order and complete-coverage rule;
- one table containing every eligible policy in published-rank order; and
- one table containing every incomplete policy in Phase 1 incomplete order, with valid/observed
  counts and missing scenarios.

Do not truncate these tables; the top-level README is the concise view.

### `reports/source-scenario-comparison.md`

For each required scenario in natural order, write one subsection. Its table contains the first ten
eligible policies in global published-rank order, with policy ID, scenario status, throughput P25,
median, P75, quality, within-run relative IQR, non-success rate, and timeout rate. A policy remains
in global order rather than being reranked per scenario. State unavailable values as `n/a`.

## Package reproduction inputs

`provenance/package-inputs.properties` is canonical Java-properties-like text, but it is parsed by
an exact project codec rather than `java.util.Properties` so ordering and escaping cannot vary.
UTF-8, LF, one `key=value` per line, no comments, and this order:

```text
schema_version=1
artifact_type=euhedral-training-run-package-inputs
package_id=<packageId>
training_run_id=<trainingRunId>
checkpoint_revision=<decimal>
scheduler_seed_hex=<16 lower-case hex>
commit_sha=<40 or 64 lower-case hex>
dirty_working_tree=<true|false>
expected_repetitions=<decimal>
sample_duration_nanos=<decimal>
liveness_timeout_nanos=<decimal>
frames_per_source=<decimal>
reset_timeout_nanos=<decimal>
ordered_frames=<true|false>
required_scenario=<canonical scenario, repeated in natural order>
```

The input record belongs to `io.euhedral_execution.training.packaging.config`; its codec belongs to
`io.euhedral_execution.training.packaging.io`:

```java
public record TrainingRunPackageInputs(
        String packageId,
        String trainingRunId,
        int checkpointRevision,
        long schedulerSeed,
        String commitSha,
        boolean dirtyWorkingTree,
        BenchmarkExecutionConfig benchmarkConfig,
        SortedSet<SourceScenario> requiredScenarios) {
}

public final class TrainingRunPackageInputsCodec {
    public static String encode(TrainingRunPackageInputs inputs);
    public static TrainingRunPackageInputs read(Path path) throws IOException;
}
```

The codec rejects unknown, missing, duplicate, out-of-order, malformed, BOM, CR, non-final-LF, or
noncanonical scenario values. It rebuilds `BenchmarkExecutionConfig` and validates all record
constructors.

## Exact Java API and ownership

Add the `io.euhedral_execution.training.packaging` package family. Requests belong to `config`,
published package results to `data`, public enums to `enums`, and the public inputs codec to `io`.
The packager, validator, collision exception, and package-private helpers remain in the owning
`packaging` package.

```java
public record TrainingRunPackageRequest(
        Path workspace,
        Path outputRoot,
        TrainingRunPackageInputs inputs) {
}

public record TrainingRunPackage(
        Path directory,
        Path manifest,
        String packageId,
        TrainingRunPackageStatus status) {
}

public final class TrainingRunPackager {
    public static TrainingRunPackage publish(
            TrainingRunPackageRequest request) throws IOException;
}

public final class TrainingRunPackageValidator {
    public static TrainingRunPackage validate(Path packageDirectory) throws IOException;
}

public final class PackageCollisionException extends IOException {
}
```

`TrainingRunPackager.publish` loads the exact checkpoint with:

```java
public static LoadedCheckpoint loadRevision(
        Path workspace, int revision) throws IOException;
```

Add that method to `CheckpointSnapshotCodec`. It validates the complete contiguous chain through the
requested revision, uses the existing strict snapshot parser and artifact fingerprint checks, and
accepts a historical revision even when later immutable revisions exist. It never accepts a
temporary or marker-less snapshot.

Also add:

```java
public static ClosedLoopCheckpoint readDetachedForAudit(
        Path checkpointDirectory) throws IOException;
```

This audit-only method validates exact inventory, empty `COMPLETE`, sidecar hashes, CSV schemas,
sorting, checkpoint record invariants, and lexical workspace-relative reference paths, but does not
dereference them. It exists because the byte-exact packaged checkpoint retains its original
workspace paths while package artifacts have intentionally clearer paths. Resume code must continue
to use `loadLatest`/`loadRevision`, never the detached reader.

The package validator maps detached references as follows and recomputes every original artifact
fingerprint:

```text
evidence/<runId>                         -> raw-data/bundles/<runId>
calibration-plan                         -> virtual directory of fixed-anchors.csv + reference-runs.csv
merges/merge-NNNNNN                      -> virtual directory of the eight Phase 1 files, mapping
                                            the two renamed vector paths back to source names
models/model-NNNNNN                      -> model/
iterations/iteration-NNNNNN/schedule     -> scheduler/
```

A virtual directory fingerprint feeds the existing directory-artifact framing with the original
relative filenames, exact file sizes, and exact file hashes; it does not create files or read them
whole into memory. Any other detached artifact path is rejected. This proves that the clearer
package layout contains the exact checkpoint-referenced bytes without duplicating the workspace tree
or rewriting checkpoint evidence.

Internal records/classes, with package-private visibility unless tests require otherwise:

```text
PackageSourceSet          resolved checkpoint, merge, model, schedule, and evidence
PackageFile               one canonical manifest entry
PackageOmission           one omission
TrainingRunManifest       exact manifest domain record
PackageManifestCodec      strict canonical JSON codec
PackageDatasetWriter      joined measurement, benchmark vector, and raw index CSVs
PackageReportWriter       README and Markdown reports
CanonicalFileSupport      streaming copy, SHA-256, force, row count, path checks
```

Public package configuration belongs to `training.packaging.config`, the published package result to
`training.packaging.data`, public enums to `training.packaging.enums`, and the public inputs codec
to `training.packaging.io`. Package-private publication/validation helpers remain together in
`training.packaging` so the cleanup does not widen their visibility.

Reuse `CanonicalCsv.read/row`, `ObservationBundleReader.stream`,
`ScenarioModelMetadataCodec.read`, `scheduling.io.ScheduleCodec.read`, and checkpoint validation. Do
not add Jackson, Gson, Commons CSV, an archive library, or a general serialization framework.

### `ClosedLoopRunner` integration

Change the public production entry point only:

1. execute the existing package-private state machine;
2. construct `TrainingRunPackageInputs` from `ClosedLoopConfig` and the returned checkpoint
   revision;
3. publish under `<workspace>/packages`;
4. return the package path.

The package-private `run(ClosedLoopConfig, ClosedLoopServices)` remains packaging-free so Phase 3
state-machine tests do not acquire filesystem reporting concerns. Add to `ClosedLoopResult`:

```java
Optional<Path> packageDirectory
```

Internal results use `Optional.empty()`. Every normal return from public
`ClosedLoopRunner.run(ClosedLoopConfig)` uses a present package path. If packaging fails, propagate
the exception; the checkpoint and all training artifacts remain intact.

Add `Runner` command:

```text
package-run --workspace <path> --inputs <path> --output-root <path>
```

Accept exactly those three flags once each in that order, reject unknown/missing/duplicate flags,
read the inputs codec, publish, and log only the resulting absolute package directory. This narrow
command is not the Phase 5 closed-loop configuration CLI.

## Atomic filesystem protocol

Resolve workspace, output root, checkpoint, and target to absolute normalized paths. Reject symlinks
at every explicitly traversed source or destination component. Package-relative paths must use `/`,
must not be absolute, empty, `.`, `..`, or contain backslashes.

For a new target:

1. acquire `WorkspaceLock`;
2. load and validate the exact checkpoint revision;
3. resolve the exact source set and verify every checkpoint artifact hash before copying;
4. create output root if needed;
5. create a unique sibling
   `.<target-name>.tmp-<UUID>` directory and immediately write
   `.euhedral-package-staging` containing `packageId` plus LF;
6. create only the enumerated directories;
7. stream-copy immutable source files with `NOFOLLOW_LINKS`;
8. generate derived CSV, properties, and Markdown files;
9. validate copied raw bundles, merge joins, schedule, checkpoint, model metadata, and model member
   checksums in the staged paths;
10. delete the staging ownership marker;
11. build manifest entries by streaming staged files, then write `manifest.json` last;
12. force every regular file through `FileChannel.force(true)`;
13. run `TrainingRunPackageValidator.validate` on the staged directory and compare its returned
    manifest domain object with the intended object; and
14. move the staging directory to the nonexistent target with `ATOMIC_MOVE`.

There is no non-atomic fallback. `AtomicMoveNotSupportedException` is a packaging failure.

On any failure, recursively delete only the exact temporary directory created by this invocation;
never follow links and never touch the target or workspace. A later invocation may delete a stale
temporary sibling only when all of these are true:

- its name matches the exact target-specific temporary prefix;
- it is a non-symlink directory;
- its ownership marker is a non-symlink regular file whose entire content is the expected package ID
  plus LF; and
- it is not the current invocation's directory.

Otherwise leave it untouched and report the collision. The final target never contains the staging
marker.

## Validation rules

The validator:

1. rejects a symlink package root, any symlink below it, unsupported file type, staging marker,
   backslash path, or unexpected file not represented by the manifest;
2. strictly decodes and re-encodes `manifest.json`;
3. confirms the actual regular-file inventory equals `files + manifest.json`;
4. streams SHA-256 for every entry and recomputes canonical CSV row counts/schema versions;
5. confirms every `COMPLETE` entry is empty;
6. reads the packaged checkpoint through `readDetachedForAudit`, confirms revision/stage/run/config
   identity, maps every detached reference by the exact rules above, and recomputes its original
   directory/file fingerprint;
7. streams every raw bundle, validates its directory fingerprint, index row, identity, counts,
   provenance, and source-run mapping;
8. strictly joins the packaged merge files and checks both derived vector datasets;
9. strictly reads schedule semantics when present and checks its run/evidence relation;
10. decodes model metadata, validates required scenarios/status, validates exact inventory paths,
    and streams each member checksum without loading DJL or allocating an `NDManager`;
11. recomputes winner IDs, calibration mode, omissions, status, and report inputs from packaged
    data; and
12. rejects an absent artifact that the checkpoint stage requires or a present artifact with no
    matching checkpoint source.

`manifest.json` is trusted only after all of these checks.

## Determinism, memory semantics, memory pollution, and precision

### Determinism

- Sort source run IDs, manifest paths, omissions, scenarios, member paths, and directory walks by
  their documented canonical keys.
- Preserve Phase 1 row order and numeric strings. Do not parse/reformat doubles for copied/joined
  columns.
- Use exact raw-bit vector strings and exact enum names.
- Do not serialize wall-clock packaging time, file timestamps, absolute paths, UUIDs, OS separators,
  locale-formatted values, environment variables, or Java implementation details.
- UUIDs are temporary-directory names only and disappear before publication.
- Publishing the same checkpoint and inputs to two empty output roots must produce identical bytes
  and identical recursive file checksums.

### Memory semantics and ownership

Packaging is offline, single-owner filesystem work under the workspace lifecycle lock. Mutable
builders, digest buffers, CSV rows, and report accumulators are thread-confined. Atomic directory
rename is the publication boundary. No VarHandle, opaque/acquire/release access, CAS, padded atomic,
executor, parallel stream, or pinned thread belongs in this path.

The existing engine pause/reset publication boundary remains unchanged. Packaging runs only after a
checkpoint-backed return and never while benchmark work is flowing.

### Memory pollution

- Replace `ArtifactFingerprint` file hashing via `Files.readAllBytes` with a 128 KiB reusable
  streaming buffer. Preserve its exact Phase 3 directory-hash material and output bytes.
- Stream raw bundle copying, hashing, logical row counting, and observation validation.
- Retain raw bundle metadata/counts, never `ObservationBundle.observations()`.
- Retain only policy ID/vector maps and the Phase 1 aggregate CSV tables needed for joins/reports.
  This is bounded by the policy/scenario result surface, not repetition count.
- Never load DJL model parameters during packaging validation.
- Do not memory-map raw evidence or copy whole binary model members into heap arrays.

### Mathematical precision

Packaging performs no ranking or calibration arithmetic. It preserves Phase 1 numeric strings and
published order. Counts use `long` internally and reject values above the manifest's nonnegative
JSON integer range (`Long.MAX_VALUE`). Winner selection uses the persisted integer published rank,
not recomputed floating-point comparison. Calibration health is exact enum/reason counting.
Checksums use SHA-256 over exact bytes.

## Compatibility and deletion boundaries

- Package schema 1 accepts only Phase 1, model, schedule, and checkpoint schema 1 artifacts.
- Existing Phase 1-3 artifact bytes and paths inside their source directories do not change.
- `ArtifactFingerprint` is mechanically changed to streaming I/O but must produce identical hashes
  for all existing files/directories.
- `ClosedLoopCheckpoint` schema and its seven sidecars do not change.
- `ClosedLoopResult` gains one field; update every constructor/test call.
- Pooled-v0 commands and current stale training documentation remain Phase 5/7 work except for
  documenting the new package-only command.
- No current workspace input/output file is imported, moved, deleted, or committed.

## File-by-file implementation checklist

Implement in this dependency order.

1. `training/checkpoint/ArtifactFingerprint.java`
    - stream file digests with a fixed 128 KiB buffer;
    - retain exact directory fingerprint framing and sorted relative paths.
2. `training/checkpoint/CheckpointSnapshotCodec.java`
    - add strict `loadRevision` and `readDetachedForAudit`;
    - share chain enumeration/validation with `loadLatest`.
3. New `training/packaging/config`, `training/packaging/data`, and
   `training/packaging/enums` public types, with package-private manifest records retained under
   `training/packaging`
    - inputs and request under `config`;
    - the published package result under `data`;
    - status, semantic type, producing stage, and origin under `enums`;
    - manifest entry and omission remain package-private under `training/packaging`, while the
      public collision exception remains beside the operational packager.
4. New `training/packaging/io/TrainingRunPackageInputsCodec`
    - implement the exact property schema and canonical round trip.
5. New `PackageManifestCodec`
    - implement strict schema-1 JSON and canonical round trip without a new dependency.
6. New `CanonicalFileSupport`
    - path validation, streaming SHA-256/copy, force, CSV metadata/counting, safe owned-temp
      cleanup.
7. New `PackageSourceSet`
    - load checkpoint, resolve/fingerprint merge/model/schedule/evidence, derive source-run
      mappings, stage requirements, status, and omissions.
8. New `PackageDatasetWriter`
    - write the exact joined measurement dataset, scheduled vector dataset, and raw index.
9. New `PackageReportWriter`
    - write exact README and both deterministic Markdown reports.
10. New `TrainingRunPackageValidator`
    - implement full inventory, schema, checksum, provenance, artifact, and lifecycle validation.
11. New `TrainingRunPackager`
    - implement collision-safe idempotence, staging, validation, force, atomic publication, and
      failure cleanup.
12. `training/data/ClosedLoopResult.java` and `training/ClosedLoopRunner.java`
    - integrate packaging only in the public production entry point.
13. `Runner.java`
    - add the exact `package-run` argument surface and usage line.
14. `euhedral-training/README.md` and `docs/ML_CLOSED_LOOP_ARCHITECTURE.md`
    - document package status/layout, package-only reproduction, and the distinction between
      vector-only, vector-with-measurements, machine-readable, and human-readable files;
    - remove no unrelated pooled-v0 documentation in this phase.
15. Tests and golden resources listed below.

No `pom.xml` or module descriptor change is expected.

## Deterministic fixtures and tests

Add:

- `training/checkpoint/ArtifactFingerprintTest`
- `training/checkpoint/CheckpointSnapshotCodecTest` cases for exact historical revision
- `training/packaging/TrainingRunPackageInputsCodecTest`
- `training/packaging/PackageManifestCodecTest`
- `training/packaging/PackageDatasetWriterTest`
- `training/packaging/PackageReportWriterTest`
- `training/packaging/TrainingRunPackagerTest`
- `training/packaging/TrainingRunPackageValidatorTest`

Use real Phase 1 bundle/merge, schedule, checkpoint, and metadata codecs with small deterministic
fixtures. Model member files may contain fixed test bytes; metadata checksums must be real. Do not
load DJL in packaging tests.

Required fixtures/assertions:

1. **Complete golden package**
    - two required scenarios, at least three eligible and two incomplete policies;
    - one native and one imported evidence bundle;
    - strong, weak, reference, and failed calibration rows;
    - accepted model, normal schedule, and `RUN_COMPLETE` checkpoint;
    - assert exact inventory, manifest/report/property golden bytes, origin classification, top-ten
      winners, row counts, checksums, model input run IDs, and zero omissions.
2. **Partial stage matrix**
    - `BOOTSTRAP_PENDING`, first `READY_TO_TRAIN`, later `READY_TO_TRAIN`, `MODEL_READY`,
      `MODEL_REJECTED`, `SCHEDULE_READY`, `BENCHMARKING`, and `READY_TO_MERGE`;
    - assert status, deterministic package ID, required/present groups, exact omissions, and that
      only complete checkpoint-indexed evidence is copied.
3. **Naming clarity**
    - assert vector-only files live only under `vectors/` and have `.vectors.csv`;
    - assert the joined file is exactly `policy-scenario-measurements.csv`;
    - assert reports are `.md` under `reports/`.
4. **Join rejection**
    - missing/conflicting vector, duplicate policy, unknown scenario row, changed rank order,
      duplicate schedule position, and schedule/evidence mismatch all fail before target
      publication.
5. **Manifest rejection**
    - unknown/missing/duplicate/out-of-order key, duplicate/unsorted path or run ID, uppercase hash,
      traversal/backslash path, wrong schema, row count, checksum, status, origin, or omission.
6. **Artifact tampering**
    - mutate every artifact family, add an unexpected file, replace a file with symlink, make a
      `COMPLETE` marker nonempty, corrupt member bytes, and corrupt raw bundle identity; validation
      fails with the target unchanged.
7. **Atomicity and cleanup**
    - inject failure after source validation, during copy, before manifest, during staged
      validation, and atomic move;
    - source workspace and existing target hashes remain unchanged;
    - only an owned exact-prefix staging directory is removed;
    - unsupported atomic move is reported without fallback.
8. **Collision**
    - identical existing package returns idempotently;
    - same target with different revision/checkpoint/input/manifest fails and is byte-unchanged.
9. **Deterministic bytes**
    - shuffled source directory enumeration and two distinct empty output roots produce recursively
      identical file bytes;
    - temporary UUID and output-root path do not appear in any payload.
10. **Streaming/memory boundary**
    - a multi-buffer file and directory hash equal the pre-change hash fixture;
    - an observation bundle larger than the buffer is indexed through the streaming visitor;
    - packaging model validation does not instantiate a DJL model.
11. **Runner integration**
    - the package-private fake-service runner remains package-free;
    - a focused packaging integration helper returns `packageDirectory`;
    - `package-run` rejects malformed flags and reproduces identical bytes.

Golden resources go under:

```text
euhedral-training/src/test/resources/robust-training/v1/golden-package/
```

Store only small deterministic text/binary fixtures. Do not copy user workspace output into test
resources.

## Prompt 4B acceptance criteria

Prompt 4B is complete only when:

- every normal public runner return has a validated package path;
- complete and every recoverable/terminal partial checkpoint stage obey the exact status and
  omission matrix;
- the package contains the exact available artifact groups and no stale/temporary/historical group;
- manifest schema, semantic types, schemas, row counts, hashes, source run IDs, provenance,
  completion, winner IDs, and omissions validate from staged bytes;
- vector-only, vector-with-measurements, machine-readable, and human-readable artifacts are
  unmistakable by path/name;
- native versus imported evidence is determined from `EvidenceOrigin`, not filenames;
- reports contain winners, required scenarios, coverage, calibration health, model status,
  provenance, guide, and exact package reproduction command;
- package publication is forced, validated, atomic, collision-safe, idempotent for identical input,
  and never overwrites;
- all injected failures leave source and target untouched and remove only owned staging data;
- same checkpoint plus inputs produces byte-identical packages in distinct roots;
- hashing, copying, raw observation validation, and model-member validation are streaming;
- Phase 1-3 tests remain green and checkpoint/artifact schemas remain unchanged; and
- user-owned staged/untracked training inputs and outputs remain untouched and excluded.

## Validation commands for Prompt 4B

Run the repository toolchain form available in the environment:

```text
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -am install -Dmaven.test.skip=true

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training \
    -Dtest=ArtifactFingerprintTest,CheckpointSnapshotCodecTest,TrainingRunPackageInputsCodecTest,PackageManifestCodecTest,PackageDatasetWriterTest,PackageReportWriterTest,TrainingRunPackagerTest,TrainingRunPackageValidatorTest,ClosedLoopRunnerTest \
    test

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test

git diff --check

rg -n "input/merger|output/results|latest-model|latest-training-data|state\\.properties|euhedral-policy-ranker|\\.bin" \
  euhedral-training/src/main/java/io/euhedral_execution/training/packaging \
  euhedral-training/src/test/java/io/euhedral_execution/training/packaging
```

The stale-boundary search must return no matches. Inspect `git status --short` before and after;
exclude the pre-existing training inputs/outputs and unrelated core test directory from every
commit.

## Risks and later-phase handoff

There is no unresolved Phase 4 architectural blocker.

- Phase 5 may add the current-workspace importer and full typed closed-loop CLI. Imported bundles
  already identify themselves through `EvidenceOrigin.IMPORTED`; package provenance needs no
  filename heuristic.
- Phase 5 may add a physical-run reproduction/resume command, but the Phase 4 package-only
  reproduction command remains stable.
- Phase 6 must exercise complete and partial package validation in its integrated audit.
- Phase 7 may remove pooled-v0 docs/code only after Phase 5 has replaced transitional callers.

Prompt 4B must append completion notes below this line with changed files, commands, results,
failure-injection evidence, environmental limits, and deviations. Any deviation involving package
identity, lifecycle status, manifest schema, provenance, artifact selection, atomicity, collision,
or deterministic bytes requires another blueprint reasoning pass.

## Implementation model reassessment

### Evidence and workload

The original provisional implementation choice was `gpt-5.5 / low`. Phase 3 provides direct contrary
evidence: its first pass implemented only the optimizer/scheduler foundations and omitted the
checkpoint, native benchmark, closed-loop state machine, and specified acceptance matrix. Later
frontier-level remediation was required before conformance.

Phase 4 has a similarly coupled systems surface:

- one exact checkpoint must govern merge, model, schedule, evidence, reports, and package status;
- stage-dependent partial behavior has eight lifecycle cases;
- source schemas from three completed phases must be preserved while a new strict manifest schema
  joins their identities and provenance;
- atomic publication, collision idempotence, symlink safety, owned cleanup, forced I/O, and
  streaming memory behavior interact;
- deterministic bytes constrain filenames, JSON, CSV, Markdown, path handling, and error recovery;
  and
- the acceptance matrix requires cross-artifact tamper and failure-injection reasoning.

This is not a mechanical translation whose complexity was eliminated merely by specifying types. The
implementation agent must hold the cross-file invariants while repairing compile/test failures.

### Selected implementation and verification capability

Prompt 4B must use **`gpt-5.6-sol` at `high` reasoning effort**. Do not dispatch Phase 4
implementation to `gpt-5.5 / low`, and do not lower the effort because the blueprint is long. Prompt
4C should use **`gpt-5.6-sol` at `high`** or a demonstrably equivalent coding/verification model
with comparable long-context systems reasoning. Prompt 4D remains an independent
`gpt-5.6-terra / high` conformance audit.

To keep the implementation context tractable, Prompt 4B should read:

1. `AGENTS.md`;
2. `docs/AGENT_WORKFLOW.md`;
3. the Phase 4 section of `docs/ROBUST_TRAINING_OPTIMIZER_PLAN.md`;
4. this blueprint in full;
5. the Phase 3 conformance audit;
6. only the exact production/test files named in the checklist and referenced stable codecs.

It should use the Phase 1-3 blueprint sections linked by this document only when an implementation
conflict requires the original contract. It must not preload all three 80-112 KB blueprints merely
to rediscover the mappings already settled here.

If `gpt-5.6-sol / high` or an equivalent model is unavailable, stop and ask for an explicit model
selection rather than silently falling back. Verification cannot compensate for knowingly
under-provisioning the implementation pass.

## Prompt 4B completion record

Implemented on branch `agent/phase4b-final-packaging`.

### Changed production and documentation files

- `training/checkpoint/ArtifactFingerprint.java` now streams exact SHA-256 input through a reusable
  128 KiB buffer without changing directory-artifact framing.
- `training/checkpoint/CheckpointSnapshotCodec.java` now loads an exact historical revision and
  supports strict detached audit reads without dereferencing workspace artifacts.
- New `training/packaging/config`, `training/packaging/data`, `training/packaging/enums`, and
  `training/packaging/io` types provide the public package inputs, request, result, lifecycle
  vocabulary, and canonical inputs codec. Operational publication/validation classes and their
  package-private helpers remain together under `training/packaging`; this includes
  `PackageReportWriter`, whose package-private collaborators are intentionally not exposed.
- The packaging implementation provides canonical manifest JSON, streaming file support and CSV
  metadata scans, checkpoint-governed source selection, derived datasets, reports, validation,
  collision handling, owned staging cleanup, forced writes, and atomic publication.
- `ClosedLoopResult`, `ClosedLoopRunner`, and `Runner` integrate packaging at the public lifecycle
  boundary and expose the exact `package-run` command. The package-private state machine remains
  packaging-free.
- `euhedral-training/README.md` and `docs/ML_CLOSED_LOOP_ARCHITECTURE.md` document package identity,
  artifact naming, provenance, streaming ownership, atomic publication, and reproduction.

### Test additions and evidence

- Added the named Phase 4 test classes and extended `ClosedLoopRunnerTest` with a real checkpoint,
  merge artifact, raw evidence, partial package publication, independent validation, idempotent
  collision handling, unexpected-file tamper rejection, naming assertions, and byte-identical
  reproduction into two distinct output roots.
- Extended checkpoint tests for historical revision loading and detached reads.
- The large-file hash test crosses the 128 KiB streaming boundary. Package generation and validation
  count raw observations through the streaming visitor and streaming CSV metadata scanner; no DJL
  model or parameter tensor is instantiated by packaging.

Commands run:

```text
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -am install -Dmaven.test.skip=true
  BUILD SUCCESS (6 reactor modules)

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training \
    -Dtest=ArtifactFingerprintTest,CheckpointSnapshotCodecTest,TrainingRunPackageInputsCodecTest,PackageManifestCodecTest,PackageDatasetWriterTest,PackageReportWriterTest,TrainingRunPackagerTest,TrainingRunPackageValidatorTest,ClosedLoopRunnerTest \
    test
  BUILD SUCCESS; 13 tests, 0 failures, 0 errors, 0 skipped

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test
  BUILD SUCCESS; 115 tests, 0 failures, 0 errors, 1 skipped

git diff --check
  no errors

rg -n "input/merger|output/results|latest-model|latest-training-data|state\\.properties|euhedral-policy-ranker|\\.bin" \
  euhedral-training/src/main/java/io/euhedral_execution/training/packaging \
  euhedral-training/src/test/java/io/euhedral_execution/training/packaging
  no matches
```

The one skipped test is the pre-existing opt-in
`ScenarioOrdinalNetworkIntegrationTest`; packaging tests deliberately do not load DJL.

### Lifecycle, failure, and workspace notes

The exercised real package is a later `READY_TO_TRAIN` recoverable partial with merge and raw
evidence present and model/schedule omissions. Production source selection and validator rules cover
all checkpoint stages, including terminal model rejection and final schedule derivation. Staged
validation failure, copy/generation failure, and atomic-move failure share one owned
temporary-directory cleanup boundary; conflicting/unowned staging directories and final targets are
never deleted. The focused test explicitly proves tampered inventory rejection, source immutability
through deterministic reproduction, and idempotent identical-target behavior.

The pre-existing staged and untracked files under `euhedral-training/input`,
`euhedral-training/output`, and the unrelated untracked core utility test directory were not read as
package inputs, edited, removed, or included in the Phase 4 commit.

No package identity, lifecycle, manifest, provenance, artifact-selection, atomicity, collision, or
deterministic-byte contract was intentionally changed from the approved blueprint.

### Naming and package cleanup record

The 2026-07-29 cleanup changed organization and terminology only:

- public package inputs and requests live under `training.packaging.config`, the published result
  under `training.packaging.data`, public enums under `training.packaging.enums`, and the public
  inputs codec under `training.packaging.io`;
- package-private operational collaborators, including `PackageReportWriter`, remain together under
  `training.packaging` so no visibility or ownership contract changed;
- the shared CSV helper is `training.data.io.CanonicalCsv`, and scheduling/checkpoint references use
  their `data`, `enums`, and `io` packages from the Phase 3 cleanup; and
- `ProducingStage` uses domain values `MERGE`, `LEARNING`, `SCHEDULING`, and `CHECKPOINT` in place
  of phase-prefixed values.

No ranking, calibration, scheduling, checkpoint, packaging, concurrency, memory-access, or
floating-point behavior changed. Package manifest bytes change only where the renamed producing
stage enum is serialized; the identity and validation rules are otherwise unchanged.

## Prompt 4C verification record

Verification ran on branch `agent/phase4c-packaging-verification` against the completed Phase 4B
implementation. No blueprint-settled defect was found, so this pass made no production-code change.

Commands and results:

```text
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -am install -Dmaven.test.skip=true
  BUILD SUCCESS (6 reactor modules)

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training \
    -Dtest=ArtifactFingerprintTest,CheckpointSnapshotCodecTest,TrainingRunPackageInputsCodecTest,PackageManifestCodecTest,PackageDatasetWriterTest,PackageReportWriterTest,TrainingRunPackagerTest,TrainingRunPackageValidatorTest,ClosedLoopRunnerTest \
    test
  BUILD SUCCESS; 13 tests, 0 failures, 0 errors, 0 skipped

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test
  BUILD SUCCESS; 115 tests, 0 failures, 0 errors, 1 skipped

git diff --check
  no errors

rg -n "input/merger|output/results|latest-model|latest-training-data|state\\.properties|euhedral-policy-ranker|\\.bin" \
  euhedral-training/src/main/java/io/euhedral_execution/training/packaging \
  euhedral-training/src/test/java/io/euhedral_execution/training/packaging
  no matches
```

The skipped test is the existing opt-in `ScenarioOrdinalNetworkIntegrationTest`; it is outside the
packaging surface and requires a DJL runtime. The focused package tests cover schema and naming,
streamed checksums and memory-sensitive paths, deterministic output, collision handling, partial-run
lifecycle classification, report contents, cleanup, and tamper rejection. The pre-existing
staged/untracked training data, outputs, and unrelated core test directory remained untouched and
excluded from this record's commit.

### Repackage compatibility addendum (2026-07-29)

The Phase 4 package names and paths remain valid after the Phase 2-and-later repackage.
`TrainingRunPackager`, `TrainingRunPackageValidator`, `PackageManifestCodec`,
`PackageDatasetWriter`, and `PackageReportWriter` remain in
`io.euhedral_execution.training.packaging`; public inputs/request types, result types, enums, and
the inputs codec remain respectively in `packaging.config`, `packaging.data`, `packaging.enums`, and
`packaging.io`. The listed packaging tests remain under the matching test package. No package
layout, manifest, lifecycle, checksum, or publication contract changed.
