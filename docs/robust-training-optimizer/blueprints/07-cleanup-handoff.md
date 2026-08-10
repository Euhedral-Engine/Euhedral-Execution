# Phase 7 Blueprint: Cleanup and Final Handoff

This blueprint is the implementation contract for Prompt 7B. It was prepared from the completed
Phase 1-6 blueprints, completion records, conformance reports, the full
`main...agent/phase6d-verification-audit` file inventory, and the live source-reference graph on
2026-07-29. Prompt 7B performs only the deletions, extractions, documentation corrections, fixture
changes, and validation listed here. It must not change a statistical definition, persisted schema,
scheduler decision, checkpoint transition, package layout, benchmark lifecycle, or runtime memory
contract.

## Scope

Phase 7:

1. removes the temporary current-workspace importer after its Phase 6 acceptance evidence;
2. removes every pooled-v0 benchmark, merger, optimizer, model, codec, ranking, and command path;
3. retains `training-info` as a useful diagnostic while moving it out of the obsolete pooled
   network;
4. makes the end-to-end audit start from the strict new-format bootstrap contract rather than the
   deleted importer;
5. removes the trainer's now-unused direct t-digest dependency;
6. removes obsolete importer, pooled-command, system-property, and old-model documentation;
7. proves that no temporary workflow or generated build output entered the branch;
8. reruns the deterministic mathematical, lifecycle, package, CLI, and DJL validation surfaces; and
9. records a concise final handoff with the complete result-package evidence.

### Explicit non-goals

- Do not change anchor selection, calibration thresholds, weighted-median arithmetic, hierarchical
  aggregation, type-7 quantiles, midrank quality, robust comparison, or coverage eligibility.
- Do not change scenario features, ordinal targets, model architecture, grouped splits, LOSO/LOEO
  evaluation, acceptance thresholds, model metadata, or member serialization.
- Do not change candidate-budget allocation, CMA-ES, score-band selection, Sobol advancement,
  carry-forward priority, scenario rotation, stable IDs/seeds, checkpoint schemas, or restart
  adoption.
- Do not change native benchmark pause/reset/publication ordering, the
  `BenchmarkFrameSink` acquire/release counter, frame routing, affinity, topology, or hot-loop
  behavior.
- Do not change final-package schemas, names, checksums, inventories, reports, atomic publication,
  collision handling, cleanup ownership, or reproduction semantics.
- Do not delete or inspect the user-owned untracked `euhedral-training/input`,
  `euhedral-training/output`, or
  `euhedral-core/src/test/java/io/euhedral_execution/core/utils` trees.
- Do not delete historical blueprints, completion records, or conformance reports. Their references
  to removed compatibility code are audit history, not live documentation.
- Do not delete the hand-authored Phase 6 golden inventories, checksums, or scenario-model metadata.
- Do not add a replacement importer, legacy format reader, migration SPI, format detector, or
  measurement metadata override.
- Do not add or retain a temporary GitHub Actions workflow.
- Do not rename schema-v1 types or tests merely because their names contain `V1`; that suffix
  identifies a persisted data contract and is not an ambiguous implementation-phase name.

## Preconditions and workspace boundary

The user request to perform Phase 7 is the repository-side signal that desired workspaces have
already been converted or that their useful import artifacts will be preserved externally.
Implementation must not inspect the real current-workspace trees to prove this. Existing
`bootstrap-policies.vectors.csv` and `import-report.csv` files remain usable evidence after the
importer source is deleted; no package or checkpoint embeds a dependency on the importer class.

Before editing, Prompt 7B must run `git status --short`. The three currently visible untracked trees
are user-owned. It must stage only paths enumerated by this blueprint and must not use a recursive
deletion command against a workspace, input, output, data, target, or repository root.

The branch lineage is:

```text
main (Phase 1 integrated)
  -> Phase 2
  -> Phase 3
  -> Phase 4
  -> Phase 5
  -> Phase 6: agent/phase6d-verification-audit
  -> Phase 7A: agent/phase7a-cleanup-handoff-blueprint
  -> Phase 7B: new agent/phase7b-cleanup-handoff branch
```

## Integrated-state review

The full branch diff against `main` contains 208 paths and approximately 32,910 insertions. The
review covered:

- Phase 2 learning and metadata;
- Phase 3 benchmark, optimization, scheduling, checkpoint, and closed-loop state;
- Phase 4 packaging and package validation;
- Phase 5 importer, typed configuration, CLI, and user documentation;
- Phase 6 audit fixtures, lifecycle tests, and hand-authored resources;
- the core deterministic `BenchmarkFrame` overload and test;
- every blueprint and conformance report; and
- both permanent workflows under `.github/workflows`.

No temporary workflow differs from `main`. The only tracked workflows are `build.yaml` and
`deploy.yaml`; both are permanent and must remain unchanged. No target directory, Zig cache, model
member, native binary, current-workspace input/output, or generated audit package is tracked by the
branch. Ignored build outputs may exist locally and are not Phase 7 targets.

Phase 6 proves the integrated robust path conforms. Its only permitted live stale boundary is:

- pooled methods and imports in `DataMerger`;
- `training.legacy`;
- `training.networks.PolicyOrdinalNetwork`;
- five pooled-only utilities under `training.utils`;
- pooled and importer dispatch in `Runner`;
- the isolated `training.importer.currentworkspace` package;
- `PolicyRankingTest`, importer tests, and importer portions of `RunnerTest` and `AuditFixtures`;
- the trainer README, GPU guide, and ML architecture text that describe those temporary paths; and
- the direct trainer t-digest dependency used only by the pooled implementation.

The phrase `pooled 28-input artifact` in
`learning/metadata/ScenarioModelMetadataCodec.java` and its assertion in
`learning/ScenarioConditionedModelTest.java` are not stale behavior. They are a required rejection
diagnostic and must remain.

## Settled deletion boundary

### Temporary current-workspace importer

Delete this complete production subtree:

```text
euhedral-training/src/main/java/io/euhedral_execution/training/importer/currentworkspace/
+-- CurrentWorkspaceFileShape.java
+-- CurrentWorkspaceImportReportRow.java
+-- CurrentWorkspaceImportRequest.java
+-- CurrentWorkspaceImportResult.java
+-- CurrentWorkspaceImportStatus.java
+-- CurrentWorkspaceImporter.java
+-- CurrentWorkspaceLayout.java
+-- CurrentWorkspaceMapping.java
+-- CurrentWorkspaceSemanticType.java
+-- LegacyDecimalBitReader.java
```

Delete its complete focused test subtree:

```text
euhedral-training/src/test/java/io/euhedral_execution/training/importer/currentworkspace/
+-- CurrentWorkspaceImporterTest.java
```

Do not copy any importer type, path mapping, decimal-bit grammar, report reason, or removal marker
elsewhere. Historical Phase 5/6 documentation remains the evidence that the temporary conversion
worked. The current application source graph must have no import of the removed package.

### Pooled-v0 implementation

Delete the complete production compatibility package:

```text
euhedral-training/src/main/java/io/euhedral_execution/training/legacy/
+-- LegacyCmaEsOptimizer.java
+-- LegacyScoreBandSampler.java
+-- PooledBenchmarkRunner.java
+-- PooledSequenceFinder.java
```

Delete the obsolete pooled ordinal network:

```text
euhedral-training/src/main/java/io/euhedral_execution/training/networks/PolicyOrdinalNetwork.java
```

Delete these utilities, all of whose non-test callers disappear with the pooled merger and
compatibility package:

```text
euhedral-training/src/main/java/io/euhedral_execution/training/utils/BenchmarkOutputReader.java
euhedral-training/src/main/java/io/euhedral_execution/training/utils/BenchmarkOutputWriter.java
euhedral-training/src/main/java/io/euhedral_execution/training/utils/Distribution.java
euhedral-training/src/main/java/io/euhedral_execution/training/utils/PolicyRanking.java
euhedral-training/src/main/java/io/euhedral_execution/training/utils/VectorGrouper.java
```

Delete the pooled ranking test:

```text
euhedral-training/src/test/java/io/euhedral_execution/training/utils/PolicyRankingTest.java
```

Retain:

- `training.utils.CommonFunctions`, because the robust `SequenceFinder`,
  `optimization.CmaEsOptimizer`, and deterministic scheduling fixtures use its bit-preserving policy
  normalization;
- `training.utils.BenchmarkFrameSink`, because the native v1 `BenchmarkRunner` owns it and its
  acquire/release counter is part of the settled benchmark memory contract;
- all `training.learning.statistics.*` distribution records, which are scenario-conditioned ordinal
  outputs and have no relationship to pooled `training.utils.Distribution`; and
- Apache Commons Math, which remains required by the robust Sobol and optimizer paths.

### `DataMerger` symbol-level cleanup

Keep `DataMerger` as the Phase 1 v1 facade because `ClosedLoopRunner`, `ClosedLoopServices`,
learning inputs, optimization-corpus loading, package selection, and tests use its request/result
records and `bootstrapCalibrationV1`/`mergeV1`.

Remove exactly these public pooled methods:

```text
DataMerger.mergeVectors()
DataMerger.mergeVectors(Path, Path)
DataMerger.mergeQuentiles()
DataMerger.mergeQuantiles(Path, Path, String)
```

Remove exactly these private pooled helpers and record:

```text
DataMerger.listRegularFiles
DataMerger.normalize(PlainQueue<File>[], AtomicInteger, AtomicReference<Throwable>, Path)
DataMerger.normalize(PlainQueue<File>, Path)
DataMerger.mergeMeans
DataMerger.merge
DataMerger.timeFormat
DataMerger.MergedResult
```

Retain `deleteRecursively(Path)`: both v1 publication failure paths use it. After removing the
pooled section, remove only imports, the logger, and `@SuppressWarnings("unchecked")` that become
unused. In particular, remove the t-digest, `SpinWait`, `PlainQueue`, pinned-executor,
`SystemInfo`, legacy codec, `File`, `Duration`, `AtomicInteger`, and `AtomicReference` imports.
Retain `HasherApi` only if a live v1 reference remains; the expected reference graph says it does
not.

Do not change the v1 publication fallback, validation, request constructors, artifact names, or
record shapes during cleanup. Any concern about those settled Phase 1 semantics is outside Phase 7.

### Dependency cleanup

Remove the direct `com.tdunning:t-digest` dependency from `euhedral-training/pom.xml`. Keep the root
dependency-management property and entry because `euhedral-core` still uses t-digest in
`FlowDistribution`. Do not change the core dependency or root POM.

## Retained training diagnostics

`training-info` is useful to the supported GPU workflow and is not itself a pooled data contract. Do
not delete the command. Extract only the environment-reporting body from
`PolicyOrdinalNetwork.printEnvironment()` into:

```text
euhedral-training/src/main/java/io/euhedral_execution/training/learning/TrainingEnvironment.java
```

`TrainingEnvironment` is a public final utility with:

```java
public static void print();
```

It owns the constant engine name `PyTorch`, obtains that engine, logs engine/version and GPU count,
logs CPU as the default when no GPU is visible, and otherwise logs CUDA runtime plus each GPU's
compute capability and committed/maximum memory. Preserve SLF4J placeholders and the old diagnostic
meaning. It has no model, tensor, feature, training, system-property, or filesystem state.

`Runner` dispatches `training-info` through `CommandServices.printTrainingEnvironment()`. The
production service calls `TrainingEnvironment.print()`; the test service increments a diagnostic
counter. This keeps `RunnerTest` deterministic and avoids loading DJL in the normal unit suite.
`training-info` becomes a supported command in usage and documentation, not a legacy command.

This extraction is the only new production type in Phase 7.

## `Runner` cleanup

Remove imports of the importer, pooled package, and `PolicyOrdinalNetwork`. Keep only these command
cases:

```text
closed-loop --config <path>
training-info
package-run --workspace <path> --inputs <path> --output-root <path>
```

Remove these cases and aliases:

```text
merge-metadata
merge-quantiles
merge-vectors
train-vector-finder
benchmark
import-current-workspace
```

Remove both `importCurrentWorkspace` methods, their flag/count parser, importer result logging, the
two temporary removal-marker comments, `CommandServices.importWorkspace`, and the production
implementation of that service method.

The `training-info` command takes no additional arguments. Reject extras before invoking the service
with:

```text
training-info does not accept arguments
```

Rewrite usage to contain only the three supported commands. Do not retain a `Legacy compatibility`
heading or mention pooled files, `-Dcycle.*` configuration beyond the existing statement that
`closed-loop` does not read it, current-workspace import, or old standalone benchmarking.

Unknown removed commands must continue through the existing `Unknown command: <name>` failure. Do
not silently alias them to the robust closed loop.

## Test-fixture cleanup

### `RunnerTest`

Remove importer imports, the real importer command test, importer argument matrices, import log
assertions, `RecordingServices.lastImport`, the importer counter, and the fake importer service.

Retain and strengthen the supported-command tests:

- exact `closed-loop` dispatch and result logs remain unchanged;
- exact `package-run` dispatch and output remain unchanged;
- exact `training-info` dispatch calls the fake diagnostic once and no other service;
- `training-info extra` fails without a service call;
- missing, duplicate, reordered, value-as-flag, and extra forms for `closed-loop` and `package-run`
  still fail without a service call;
- each removed command name is rejected as unknown;
- help contains exactly the supported command names and meanings, identifies `training-info` as
  scenario-model/DJL hardware diagnostics, and contains none of the removed commands, importer
  terms, or `Legacy compatibility`; and
- the stop-file regular/missing/symlink/I/O boundary remains unchanged.

Update `RecordingServices.totalCalls()` to include the diagnostic count and no importer count.

### `AuditFixtures`

Phase 6 intentionally used the importer to prove its final acceptance surface. After source removal,
the end-to-end robust audit must start at the permanent Phase 3 bootstrap contract.

Remove:

- importer imports and result types;
- `writeCurrentWorkspace`;
- `verifyImport`;
- `decimalRecord`;
- importer source/output setup in `execute`; and
- the `imported` and `reverseImported` fields from `Experiment`.

Add one thread-confined test helper:

```java
private static Path writeBootstrap(Path path, Corpus corpus) throws IOException;
```

It writes the exact `BootstrapPolicyCsv` schema:

```text
schema_version,bootstrap_position,policy_id,weight_00_bits,...,weight_27_bits
```

Rows use the existing naturally sorted `Corpus.meanings()` order, positions `1..10`, canonical
policy IDs, and 16-digit lower-case raw-bit lanes. Build rows with `CanonicalCsv.row`, write
UTF-8/LF once under the audit `@TempDir`, then read back through
`BootstrapPolicyCsv.read(path, 10)`. Assert policy IDs, order, and every lane with
`PolicyVector.bitwiseEquals` before running the closed loop.

The helper must reproduce the bytes previously used by
`bootstrap-policies.vectors.csv`. All control, resumed, and rejected configurations use this one
validated bootstrap path. This preserves the frozen configuration hash and the Phase 6 package
oracle. If the fingerprint changes, treat it as a defect in the fixture translation rather than
updating golden package checksums.

Do not replace the importer test with a new migration test. Importer acceptance remains recorded in
the Phase 5/6 completion and conformance artifacts.

### Unchanged audit surface

`EndToEndTest`, `PackageLifecycleAuditTest`, the golden package resources, and production
package/checkpoint code should require no semantic edit. Imports or record accessor compile repair
caused solely by the `Experiment` field removal is permitted, but current reference searches show no
external use of those two fields.

## Documentation cleanup

### `euhedral-training/README.md`

Make these exact changes:

1. List `closed-loop`, `training-info`, and `package-run` as the supported commands.
2. Delete the complete `One-time current-workspace vector import` section.
3. In the typed-config example, replace
   `imported-current-workspace/bootstrap-policies.vectors.csv` with a neutral existing strict
   bootstrap artifact such as `bootstrap/bootstrap-policies.vectors.csv`.
4. State that `run.bootstrap_policies` is a strict schema-v1 vector file and that policies require
   native exact-scenario evidence before calibration or learning.
5. Retain all typed configuration, robust ranking, coverage, resume, cross-environment, packaging,
   and reproduction documentation.
6. Add one short `training-info` example that says it reports DJL/PyTorch/CUDA device visibility and
   does not train or benchmark.
7. Delete the complete `Legacy compatibility` section.
8. Delete the complete `Removing the temporary importer` section and its marker/search recipe.

Do not claim that historical current-workspace measurements are usable evidence.

### `docs/ML_CLOSED_LOOP_ARCHITECTURE.md`

Replace the importer paragraph under `Evidence and identity` with the permanent boundary:

- the closed loop accepts a strict schema-v1 bootstrap vector file or an explicit native
  calibration/evidence state;
- bootstrap vectors carry no measurements and must be benchmarked natively;
- no current-layout names, alternating rows, old model, or old checkpoint is a live input format.

Retain the implemented flow, calibration, aggregation, complete/incomplete pools, lifecycle, memory
ownership, and package descriptions. Do not add Phase 7 mechanics to this architecture document.

### `euhedral-training/GPU_SETUP_UBUNTU.md`

Keep the versioned Ubuntu/PyTorch/CUDA installation guidance and `training-info` diagnostic
examples. Correct the supported operation:

- update the repository toolchain line from stale Maven `3.9.6` to the `mise.toml` selection, Maven
  `3.9.16`;
- identify `training-info` as the new `TrainingEnvironment` diagnostic, not an old model command;
- remove the `train-vector-finder` command and the old `-Ddata`, `-Dmodel.output`, and
  `-Dtraining.batchSize` example;
- show `closed-loop --config <path>` through the packaged GPU launcher;
- state that the config selects `training.device=gpu0` and the typed training keys;
- state that application configuration is not supplied through old `-D` trainer properties; and
- replace advice to retrain from an existing merged pooled corpus with advice to start/resume the
  robust closed loop from strict bootstrap vectors plus native evidence.

### GPU launcher

Update
`euhedral-training/src/main/scripts/euhedral-training-gpu`:

- retain Python/PyTorch/CUDA validation, `PYTORCH_LIBRARY_PATH`, PyTorch version/flavor,
  `JAVA_OPTS`, safe argument arrays, default DJL engine, and jar discovery;
- remove `TRAINING_DEVICE`, the injected `-Dtraining.device`, and the old
  `train-vector-finder`/trainer-property comment;
- document that `training.device` belongs in the typed closed-loop configuration;
- retain leading general JVM options only for JVM concerns such as heap sizing and add-opens.

The script remains shell-safe and must not use `eval`.

### Historical documents

Do not rewrite:

- `docs/ROBUST_TRAINING_OPTIMIZER_PLAN.md` beyond Prompt 7B's model/effort selection made by 7A;
- Phase 1-6 blueprints and completion records; or
- Phase 2-6 conformance reports.

Their stale-term occurrences describe old code, rejection behavior, removal intent, or audit
evidence. Removing those references would destroy the handoff trail.

## Ambiguous-name review

The prior naming passes already replaced phase-numbered shared helpers and organized configuration,
data, enum, and I/O packages. Phase 7 settles the remaining ambiguous names as follows:

| Current name                                 | Disposition    | Reason                                                            |
|----------------------------------------------|----------------|-------------------------------------------------------------------|
| `merge-metadata`                             | delete alias   | It actually invokes misspelled pooled quantile merging.           |
| `mergeQuentiles`                             | delete symbol  | Misspelled, pooled, and caller-free after CLI cleanup.            |
| `mergeQuantiles` / `mergeVectors`            | delete symbols | Names conceal legacy alternating-row semantics.                   |
| `benchmark`                                  | delete command | It is the pooled standalone benchmark, not native v1 scheduling.  |
| `train-vector-finder`                        | delete command | It selects the pooled model through system properties.            |
| `PolicyOrdinalNetwork`                       | delete class   | Its name collides conceptually with scenario-conditioned v1.      |
| `training-info`                              | retain, re-own | The command is unambiguous once backed by `TrainingEnvironment`.  |
| `DataMerger`                                 | retain         | It is the established v1 facade used across the robust path.      |
| `BenchmarkRunner` / `SequenceFinder`         | retain         | They now exclusively own native-v1 and robust candidate APIs.     |
| `DataMergerV1Test` / `BenchmarkRunnerV1Test` | retain         | `V1` denotes persisted schema version, not implementation phase.  |
| `AuditFixtures` / `EndToEndTest`             | retain         | Phase-number prefixes were already removed; names state behavior. |

No other production class, package, command, artifact, or report name requires a Phase 7 rename.

## Memory semantics, memory pollution, precision, and safety

### Memory semantics

The cleanup deletes the old pinned-thread P99 merger; it does not replace it. The v1 merger remains
offline and single-owner. The new `TrainingEnvironment` only performs synchronous diagnostic reads.
The direct-bootstrap audit helper is test-thread-confined and publishes one immutable file before
parsing it.

Do not edit `BenchmarkFrameSink`, VarHandles, atomics, queues, worker ownership, pause/reset
barriers, or checkpoint/package atomic moves. No stronger or weaker memory access is justified by
this phase.

### Memory pollution

Removing the importer, pooled network, pooled t-digests, and alternating-row codecs reduces retained
buffers and model state. The audit bootstrap helper holds only ten policies and one small
`StringBuilder`; it must not read the real workspace or load DJL. `training-info` may initialize the
DJL engine only when explicitly invoked.

Do not load raw evidence, package payloads, or model members into memory as part of cleanup.
Existing streaming package and observation tests remain authoritative.

### Mathematical precision

No robust arithmetic changes. Bootstrap lanes are serialized from
`Double.doubleToRawLongBits` with `%016x`, policy IDs remain canonical, and order remains unsigned
`PolicyId` order. Do not normalize, round, clamp, parse legacy decimals, or recompute package
metrics.

The direct-bootstrap bytes and the Phase 6 configuration/package fingerprints must remain stable.
The Phase 2 pooled-artifact rejection text remains exact.

### Filesystem and deletion safety

Delete only the tracked files listed above. Do not follow symlinks or clean user directories. Do not
delete ignored `target`, Zig, IDE, native-resource, input, output, or data paths. Maven may write
normal ignored build output. Package tests continue to use `@TempDir`.

## File-by-file implementation checklist

Implement in this dependency order:

1. Create a new `agent/phase7b-cleanup-handoff` branch from the committed Phase 7A branch and
   inspect `git status --short`.
2. Add `learning/TrainingEnvironment.java` by extracting only the diagnostic body from
   `PolicyOrdinalNetwork.printEnvironment`.
3. Update `Runner` and `RunnerTest` to the exact supported command/service/test contract.
4. Refactor `AuditFixtures` to write and validate the strict bootstrap CSV directly.
5. Delete the importer production/test subtrees.
6. Delete `training/legacy`, `PolicyOrdinalNetwork`, the five pooled utilities, and
   `PolicyRankingTest`.
7. Remove the enumerated pooled methods/helpers/imports from `DataMerger`.
8. Remove the direct trainer t-digest dependency.
9. Update the GPU launcher, trainer README, GPU setup guide, and ML architecture document.
10. Run the static deletion/dependency/name/workflow/generated-file proofs before compiling.
11. Run focused compilation/tests, fix only mechanical references determined by this blueprint, and
    do not change golden expectations.
12. Run the complete validation sequence and inspect the generated package evidence through the
    existing audit assertions.
13. Append the completion record described below to this blueprint.
14. Inspect `git diff --check`, `git status --short`, the Phase 7 diff, and the complete
    `main...HEAD` diff inventory.
15. Commit and push only Phase 7 source/test/docs cleanup plus this completion record.

If compilation reveals a live dependency from a robust production package to a deleted type, stop
and append the exact reference here. Do not preserve the legacy type or invent an adapter without
returning to a blueprint decision.

## Deterministic acceptance criteria

Prompt 7B is complete only when:

- the importer main/test packages and all three importer marker/name patterns are absent from live
  source and user-facing documentation;
- the pooled package, old network, five pooled utilities, pooled `DataMerger` methods, pooled test,
  and every pooled command are absent;
- `DataMerger` exposes only v1 calibration/merge behavior;
- `euhedral-training` no longer declares t-digest directly while `euhedral-core` retains its
  required dependency;
- `training-info` reports through `TrainingEnvironment` and no pooled model class remains;
- `Runner` exposes exactly the three supported commands;
- the GPU launcher and docs use typed closed-loop configuration rather than old training properties;
- the end-to-end audit begins from a strict validated bootstrap CSV and does not depend on a
  migration package;
- bootstrap bytes, frozen configuration hash, robust winner, scenario rows, checkpoint restart
  behavior, package inventories, package checksums, and package reproduction remain unchanged;
- the new-path search has no P99 normalization, pooled ranking, alternating-row codec, old model,
  old checkpoint, or current-layout dependency;
- the only live `pooled` source match is the required Phase 2 incompatible-artifact diagnostic and
  its focused test;
- no temporary workflow differs from `main`;
- no generated or user-owned file is staged;
- all focused, full trainer, opt-in DJL, and selected-reactor verification commands pass, subject
  only to the already documented Docker/native full-repository limitation; and
- the final completion record contains exact evidence and no unsupported performance claim.

## Validation commands

Use the pinned toolchain:

```bash
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn
```

Record `java -version` and `mvn -version` for that prefix. Then run:

```bash
<PINNED_MVN> -B -pl euhedral-training -am install -Dmaven.test.skip=true

<PINNED_MVN> -B -pl euhedral-core -Dtest=BenchmarkFrameTest test

<PINNED_MVN> -B -pl euhedral-training \
  -Dtest=PolicyIdentityTest,ObservationBundleCodecTest,RunCalibratorTest,AnchorBootstrapperTest,HierarchicalAggregatorTest,ScenarioQualityRankerTest,PolicyComparatorTest,DataMergerV1Test,PolicyGroupedSplitterTest,ScenarioConditionedModelTest,PredictedPolicyComparatorTest,BudgetAllocatorTest,CandidateSchedulerTest,SequenceFinderTest,CarryForwardQueueTest,ScenarioRotationTest,ScheduleCodecTest,CheckpointSnapshotCodecTest,BenchmarkRunnerV1Test,ClosedLoopRunnerTest \
  test

<PINNED_MVN> -B -pl euhedral-training \
  -Dtest=ArtifactFingerprintTest,TrainingRunPackageInputsCodecTest,PackageManifestCodecTest,PackageDatasetWriterTest,PackageReportWriterTest,TrainingRunPackagerTest,TrainingRunPackageValidatorTest,PackageLifecycleAuditTest,ClosedLoopConfigCodecTest,RunnerTest,EndToEndTest \
  test

<PINNED_MVN> -B -pl euhedral-training test

<PINNED_MVN> -B -pl euhedral-training -Dtraining.djlIntegration=true \
  -Dtest=ScenarioOrdinalNetworkIntegrationTest test

<PINNED_MVN> -B -pl euhedral-training -am verify
```

The normal training suite may skip only the deliberately opt-in
`ScenarioOrdinalNetworkIntegrationTest`; its explicit invocation must pass.

Run deletion proofs over live code and user-facing documentation:

```bash
rg -n "ROBUST_OPTIMIZER_POOLED_V0_REMOVAL|TEMPORARY_CURRENT_WORKSPACE_IMPORT_REMOVAL|importer\\.currentworkspace|import-current-workspace" \
  euhedral-training/src/main euhedral-training/src/test \
  euhedral-training/README.md euhedral-training/GPU_SETUP_UBUNTU.md \
  docs/ML_CLOSED_LOOP_ARCHITECTURE.md

rg -n "training\\.legacy|PooledSequenceFinder|PooledBenchmarkRunner|LegacyCmaEsOptimizer|LegacyScoreBandSampler|PolicyOrdinalNetwork|BenchmarkOutputReader|BenchmarkOutputWriter|PolicyRanking|VectorGrouper|mergeQuentiles|mergeQuantiles|merge-metadata|merge-quantiles|merge-vectors|train-vector-finder" \
  euhedral-training/src/main euhedral-training/src/test \
  euhedral-training/README.md euhedral-training/GPU_SETUP_UBUNTU.md \
  docs/ML_CLOSED_LOOP_ARCHITECTURE.md
```

Both commands must return no matches. `Distribution` is intentionally omitted from the textual
search because the supported ordinal records include that word; prove the obsolete file is absent
directly:

```bash
test ! -e euhedral-training/src/main/java/io/euhedral_execution/training/utils/Distribution.java
test ! -e euhedral-training/src/main/java/io/euhedral_execution/training/networks/PolicyOrdinalNetwork.java
test ! -d euhedral-training/src/main/java/io/euhedral_execution/training/legacy
test ! -d euhedral-training/src/main/java/io/euhedral_execution/training/importer/currentworkspace
test ! -d euhedral-training/src/test/java/io/euhedral_execution/training/importer/currentworkspace
```

The stale-math search:

```bash
rg -n -i "p99|quantile\\(0\\.99\\)|TDigest|per-file.*normal" \
  euhedral-training/src/main/java \
  euhedral-training/src/test/java \
  euhedral-training/README.md \
  euhedral-training/GPU_SETUP_UBUNTU.md \
  docs/ML_CLOSED_LOOP_ARCHITECTURE.md
```

must return no stale match. Numeric test data such as `0.99` without `quantile(...)` is not a P99
assumption and is deliberately excluded.

The word-level rejection proof:

```bash
rg -n -i "pooled" \
  euhedral-training/src/main/java \
  euhedral-training/src/test/java \
  euhedral-training/README.md \
  euhedral-training/GPU_SETUP_UBUNTU.md \
  docs/ML_CLOSED_LOOP_ARCHITECTURE.md
```

may match only `ScenarioModelMetadataCodec` and `ScenarioConditionedModelTest`, where the schema-v1
loader rejects a pooled 28-input artifact.

Dependency, script, workflow, and generated-file proofs:

```bash
rg -n "com\\.tdunning|t-digest" euhedral-training/pom.xml \
  euhedral-training/src/main euhedral-training/src/test

rg -n "TRAINING_DEVICE|-Dtraining\\.device|train-vector-finder|-Ddata=|-Dmodel\\.output=" \
  euhedral-training/src/main/scripts/euhedral-training-gpu

git diff --name-only main...HEAD -- .github/workflows
git diff -- .github/workflows

git ls-files | rg '(^|/)(target|\\.zig-cache|zig-out)/|euhedral-training/(input|output)/|\\.class$|\\.params$'
git status --short --ignored
```

The first two searches and both workflow diffs must be empty. The tracked-generated-file search must
be empty for the named build/training artifacts. `git status --short --ignored` is an inspection
command: ignored local build products are permitted but must not be staged or deleted.

Final repository review:

```bash
git diff --check
git diff --cached --check
git status --short
git diff --stat
git diff --name-status
git diff --stat main...HEAD
git diff --name-status main...HEAD
```

Attempt full-repository `mvn -B verify` only if Docker and the native cross-build prerequisites are
available. Otherwise repeat and record the exact `docker info` failure as an environment limit. The
selected six-project `euhedral-training -am verify` remains mandatory.

## Final result-package evidence

The existing `EndToEndTest` and `PackageLifecycleAuditTest` are the executable package proof. Prompt
7B must record that they still assert:

- robust winner `R`, policy `p1-4e8bd733c51b5dab`, at published rank 1;
- all four required exact scenarios and native scenario evidence;
- `RUN_COMPLETE` revision 24;
- 70 complete-package paths and 69 manifest entries;
- 58 interrupted-package paths and 57 manifest entries;
- byte-identical control, resumed, and package-run reproduction packages;
- source/checksum joins, reports, collision behavior, owned cleanup, and tamper rejection; and
- the incomplete failed policy remains outside published robust leadership.

The pre-cleanup Phase 6 canonical fingerprints are:

```text
config_sha256            2b88841c805c63eb6a0495de2b983ab6c621f428f68e8541e3a1c3034e31dacf
checkpoint_sha256        1a5562686a8dbfc7c68cff4a6ca0a969e2dde78591a586a21bd80bceb54d7b18
manifest_file_sha256     03b2a91981e41a56ffa745852b71fa078086f2dd749f286d452f51b16755ff2d
recursive_package_sha256 f1734475fd29f0abb2a95c181777e312713dd12363f8a627a020d06c6ebe2b44
first_bundle_sha256      852dc47e69bfdc8e0d57e3f17b2aa0b5389d5df11b87bb6c4c295777214601f6
```

The direct-bootstrap fixture must preserve them. Do not update golden checksums or expected package
bytes to make a changed bootstrap pass. If the test harness does not print hashes, a passing
independent golden comparison plus the unchanged expected constants is sufficient evidence; do not
add logging solely for handoff.

## Final handoff summary contract

The Prompt 7B completion record and user-facing handoff must state:

1. the exact deleted production/test boundaries and retained `TrainingEnvironment` diagnostic;
2. that importer artifacts already produced remain usable, but no importer command remains;
3. that only strict v1 bootstrap/native evidence and typed configuration are supported;
4. focused/full/DJL/selected-reactor command results with test counts and skips;
5. package winner, scenarios, revision, inventories, deterministic reproduction, and fingerprints;
6. static deletion, dependency, workflow, formatting, generated-file, and full-diff results;
7. the exact Docker/native limitation if full-repository verification remains unavailable;
8. confirmation that user-owned untracked paths were untouched and excluded; and
9. any deviation. A changed schema, comparator, package checksum, lifecycle result, or golden
   inventory is a blocker, not an acceptable cleanup deviation.

Do not claim live native throughput performance. The handoff may say that deterministic native-v1
contract tests passed and that live lattice throughput was not measured.

## Implementation model reassessment

### Actual implementation demands

The cleanup removes 22 production/test files and edits the v1 facade, command/service surface, GPU
launcher, three user-facing documents, POM, runner tests, and the 700-line integrated audit fixture.
It crosses:

- CLI ownership and deterministic test services;
- temporary migration and pooled dependency deletion;
- exact IEEE-754 bootstrap serialization;
- configuration fingerprint and golden-package byte stability;
- DJL environment diagnostics without normal-suite engine initialization;
- shell argument/property behavior;
- filesystem deletion safety and user-owned workspace isolation; and
- a broad compile/test/reference-repair surface across the training module.

There is no new statistical, scheduler, checkpoint, package, concurrency, or lifecycle design. Most
edits are deletion or exact extraction, but a low-effort pass is inappropriate because the audit
fixture translation must preserve configuration/package fingerprints while the deletion removes
widely referenced compatibility types.

Earlier Phase 3 evidence shows that a lower-capability/low-effort implementation omitted a coupled
state-machine surface even with a detailed blueprint. Phase 6 demonstrates that frontier-level
integration work and complete acceptance matrices are available and stable. Verification cannot
repair a cleanup pass that silently changes bootstrap bytes or drops part of the supported CLI/GPU
surface.

### Selected model and effort

Use `gpt-5.6-sol` at `medium` reasoning effort for Prompt 7B.

The exact minimal context envelope is:

1. `AGENTS.md`;
2. `docs/AGENT_WORKFLOW.md`;
3. the Phase 7 section of `docs/ROBUST_TRAINING_OPTIMIZER_PLAN.md`;
4. this blueprint;
5. `docs/robust-training-optimizer/audits/06-verification-audit-conformance.md`;
6. the Phase 6 completion/fingerprint and static-search sections at
   `docs/robust-training-optimizer/blueprints/06-verification-audit.md`;
7. the current files explicitly enumerated by this blueprint; and
8. compile errors or reference-search results produced during implementation.

Do not preload all prior blueprints in 7B. Stable decisions are summarized here, and the Phase 6
audit is the integrated baseline. If `gpt-5.6-sol` at `medium` is unavailable, stop and request an
explicit alternative rather than silently downgrading.

## Prompt 7B completion record

Prompt 7B appends:

- branch and commit context;
- deleted, added, and edited files;
- direct-bootstrap byte/fingerprint evidence;
- exact commands, results, counts, and skipped tests;
- static deletion/dependency/workflow/generated-file proofs;
- complete diff/status review;
- package evidence and fingerprints;
- environment limitations;
- user-owned path confirmation; and
- deviations or `none`.

### Completion record (2026-07-29)

#### Branch and implementation

Implemented on `agent/phase7b-cleanup-handoff` from Phase 7A commit `2cc606a6`.

Deleted the complete ten-file
`training.importer.currentworkspace` production package and its focused test, the four-file
`training.legacy` package, `PolicyOrdinalNetwork`, the five pooled-only utilities, and
`PolicyRankingTest`. Removed the pooled merger API/helpers from `DataMerger` and the trainer's
direct t-digest dependency. Added only
`learning/TrainingEnvironment.java`, preserving the former DJL/PyTorch/CUDA environment diagnostic
without retaining a pooled model.

Edited `Runner`/`RunnerTest`, `AuditFixtures`, the GPU launcher, the trainer README and GPU guide,
and `docs/ML_CLOSED_LOOP_ARCHITECTURE.md` exactly within the enumerated cleanup boundary. `Runner`
now exposes only `closed-loop`, `training-info`, and `package-run`. The audit fixture writes and
reopens the strict schema-v1 bootstrap CSV directly, checks natural policy-ID order and all 28 raw
lanes, and uses that one path for control, resumed, and rejected runs.

Importer output directories produced before this removal remain usable as strict bootstrap-vector
artifacts and audit records, but no importer command or current-layout parser remains. Only strict
schema-v1 bootstrap/native evidence and typed closed-loop configuration are supported.

#### Validation and package evidence

All commands used Oracle OpenJDK 21.0.2 and Maven 3.9.16 from the pinned explicit prefix.

```text
-B -pl euhedral-training -am install -Dmaven.test.skip=true
  BUILD SUCCESS; six selected reactor projects

-B -pl euhedral-core -Dtest=BenchmarkFrameTest test
  3 tests, 0 failures, 0 errors, 0 skipped

-B -pl euhedral-training -Dtest=<Phase 1-3 focused list> test
  67 tests, 0 failures, 0 errors, 0 skipped

-B -pl euhedral-training -Dtest=<Phase 4-7 package/CLI/audit list> test
  26 tests, 0 failures, 0 errors, 0 skipped

-B -pl euhedral-training test
  132 tests, 0 failures, 0 errors, 1 skipped
  (the deliberately opt-in DJL integration test)

-B -pl euhedral-training -Dtraining.djlIntegration=true
  -Dtest=ScenarioOrdinalNetworkIntegrationTest test
  1 test, 0 failures, 0 errors, 0 skipped

-B -pl euhedral-training -am verify
  BUILD SUCCESS; six selected reactor projects
```

The first package/CLI/audit-list attempt exposed one fixture-only path setup defect:
`writeBootstrap` had not created its `@TempDir` child parent when
`PackageLifecycleAuditTest` supplied a not-yet-created `experiment` directory. Adding the
blueprint-required parent creation fixed it; the complete command was rerun and passed as recorded
above. No production or golden output changed.

`EndToEndTest` and `PackageLifecycleAuditTest` continue to prove winner `R`
(`p1-4e8bd733c51b5dab`) at rank 1 across all four exact scenarios, `RUN_COMPLETE` revision 24, 70
complete-package paths with 69 manifest entries, 58 interrupted-package paths with 57 manifest
entries, byte-identical control/resumed/package-run reproduction, source/checksum joins, report
contents, collision/cleanup/tamper behavior, and exclusion of the incomplete failed policy from
robust leadership. The unchanged golden comparisons preserve:

```text
config_sha256            2b88841c805c63eb6a0495de2b983ab6c621f428f68e8541e3a1c3034e31dacf
checkpoint_sha256        1a5562686a8dbfc7c68cff4a6ca0a969e2dde78591a586a21bd80bceb54d7b18
manifest_file_sha256     03b2a91981e41a56ffa745852b71fa078086f2dd749f286d452f51b16755ff2d
recursive_package_sha256 f1734475fd29f0abb2a95c181777e312713dd12363f8a627a020d06c6ebe2b44
first_bundle_sha256      852dc47e69bfdc8e0d57e3f17b2aa0b5389d5df11b87bb6c4c295777214601f6
```

#### Static and repository review

The importer/removal-marker, pooled implementation/name, stale P99/t-digest, trainer dependency, and
obsolete GPU property searches are empty. The only live `pooled` matches are the required schema-v1
incompatible-artifact diagnostic and its focused test. Direct path checks prove all enumerated
deleted files/directories are absent.

Both permanent workflow diffs are empty. The tracked generated/build/training-artifact search is
empty. `git diff --check` is clean. The Phase 7 diff contains only the enumerated source, test, POM,
script, documentation, deletion, and completion-record paths. Ignored Maven/Zig/IDE outputs remain
unstaged.

`docker info` reports:

```text
permission denied while trying to connect to the docker API at
unix:///run/user/911603815/docker.sock
```

The Docker/Testcontainers and full native repository verification path is therefore unavailable; the
mandatory six-project `euhedral-training -am verify` passed. No live lattice throughput was measured
or claimed.

The pre-existing untracked `euhedral-training/input`, `euhedral-training/output`, and
`euhedral-core/src/test/java/io/euhedral_execution/core/utils` trees were not inspected, edited,
deleted, or staged.

Deviations: none.

### Verification record (2026-07-29, Prompt 7C)

#### Branch and scope

Verification ran on `agent/phase7c-cleanup-handoff-verification` from Phase 7B commit
`8cc4f195`. No blueprint-settled implementation defect was found and no production, test, script,
POM, package-resource, or user-facing documentation change was needed.

At verification start the index already contained a one-line Phase 7 blueprint wording correction
and an added `euhedral-core/src/test/java/io/euhedral_execution/core/utils/FlowThreadTest.java`. The
core utils test file is outside the Phase 7 cleanup boundary and was not edited, deleted, or
included as Phase 7 evidence. The Phase 7 verification commit stages only the Phase 7 blueprint
file; it does not include the core utils test.

#### Toolchain

The pinned explicit toolchain reported:

```text
openjdk version "21.0.2" 2024-01-16
OpenJDK Runtime Environment (build 21.0.2+13-58)
OpenJDK 64-Bit Server VM (build 21.0.2+13-58, mixed mode, sharing)

Apache Maven 3.9.16
Java version: 21.0.2, vendor: Oracle Corporation
```

#### Validation commands

All Maven commands used:

```text
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn
```

Results:

```text
-B -pl euhedral-training -am install -Dmaven.test.skip=true
  BUILD SUCCESS; six selected reactor projects

-B -pl euhedral-core -Dtest=BenchmarkFrameTest test
  3 tests, 0 failures, 0 errors, 0 skipped

-B -pl euhedral-training -Dtest=<Phase 1-3 focused list> test
  67 tests, 0 failures, 0 errors, 0 skipped

-B -pl euhedral-training -Dtest=<Phase 4-7 package/CLI/audit list> test
  26 tests, 0 failures, 0 errors, 0 skipped

-B -pl euhedral-training test
  132 tests, 0 failures, 0 errors, 1 skipped
  (the deliberately opt-in DJL integration test)

-B -pl euhedral-training -Dtraining.djlIntegration=true
  -Dtest=ScenarioOrdinalNetworkIntegrationTest test
  1 test, 0 failures, 0 errors, 0 skipped

-B -pl euhedral-training -am verify
  BUILD SUCCESS; six selected reactor projects
```

#### Static proofs and package evidence

The importer/removal-marker search, pooled implementation/name search, stale P99/t-digest search,
trainer dependency search, obsolete GPU property search, and tracked generated/build/training
artifact search returned no prohibited matches. The direct path checks proved the deleted
`Distribution`, `PolicyOrdinalNetwork`, `training.legacy`, and current-workspace importer
production/test paths remain absent.

The word-level `pooled` search returned only the required schema-v1 incompatible-artifact diagnostic
in `ScenarioModelMetadataCodec` and its focused assertion in
`ScenarioConditionedModelTest`.

Workflow review was clean:

```text
git diff --name-only main...HEAD -- .github/workflows
git diff -- .github/workflows
```

both returned no output. `git diff --check` and `git diff --cached --check` were clean.

`EndToEndTest` and `PackageLifecycleAuditTest` passed in both the focused package/CLI/audit list and
the full `euhedral-training test` run. They continue to prove winner `R`
(`p1-4e8bd733c51b5dab`) at rank 1 across all four exact scenarios, `RUN_COMPLETE` revision 24, 70
complete-package paths with 69 manifest entries, 58 interrupted-package paths with 57 manifest
entries, byte-identical control/resumed/package-run reproduction, source/checksum joins, report
contents, collision/cleanup/tamper behavior, and exclusion of the incomplete failed policy from
robust leadership. The unchanged golden comparisons preserve the recorded Phase 6 fingerprints.

#### Repository and environment limits

`git status --short --ignored` showed only the pre-existing staged blueprint/core-utils entries and
ignored local IDE/Maven/Zig/build output. No generated, training input/output, or user-owned file
was staged for the Phase 7 verification commit.

`docker info` still cannot reach the rootless Docker server:

```text
permission denied while trying to connect to the docker API at
unix:///run/user/911603815/docker.sock
```

The Docker/Testcontainers and full native repository verification path remains unavailable for this
environment. The mandatory six-project `euhedral-training -am verify` passed. No live native
throughput measurement was run or claimed.

Deviations: none.
