# Robust Cross-Source Training Optimizer Plan

This document is the implementation plan for making the closed-loop trainer identify policies that
remain strong across source counts, correlate measurements across iterations, and package the useful
outputs at the end of a run.

The current implementation must be treated as a panel of:

```text
policy x source scenario x run/iteration
```

Do not pool it prematurely into one distribution per policy.

## Non-negotiable outcomes

The completed system must:

- preserve policy, source scenario, run, iteration, cohort, and environment identity;
- compare iterations on a stable scale even as candidate cohorts improve;
- rank policies for robust performance across all required source scenarios;
- allow partially observed policies to contribute without treating them as fully validated;
- carry promising policies forward until their scenario coverage is complete;
- produce a shallow, self-describing final results package;
- keep raw evidence available behind every human-readable conclusion;
- import only useful vectors and measurement data from the current pre-upgrade workspace layout;
- explicitly leave historical trained models and old checkpoints behind;
- isolate that one-way workspace import as temporary compatibility code that can be deleted cleanly.

## Recommended mathematical model

### Stable policy and scenario identity

Use the vector hash as the stable policy identity. Define a source scenario with all of:

- absolute source count;
- available physical core count;
- reduced source/core ratio;
- machine/environment identifier.

Use source/core ratio as the portable model coordinate, but retain the absolute values. Equal ratios
on different core counts are comparable observations, not automatically interchangeable scenarios.

### Anchor-based run calibration

Every benchmark iteration must include a small fixed anchor set plus rolling robust leaders. Reserve
approximately 1-3 percent of the candidate budget for fixed anchors and a separately configurable
small fraction for leader revalidation.

For each source scenario, choose a reference run. Estimate the multiplicative run scale in log space
from anchors shared with the reference:

```text
delta_run = weightedMedian_a(log(y_run,a) - log(y_ref,a))
calibrated_y = exp(log(y) - delta_run)
```

Use the weighted median because a single unstable anchor must not move an entire run. Derive weights
from repeat count and within-anchor uncertainty, with a cap so one anchor cannot dominate. Report:

- shared anchor count;
- median absolute calibration residual;
- calibration confidence/status.

Do not silently calibrate a run with inadequate overlap. Mark it `UNCALIBRATED` or
`WEAKLY_CALIBRATED` and exclude it from robust-leader promotion unless explicitly overridden.

This replaces per-file P99 normalization. A file's candidate cohort must never define the scale used
to compare that file with another iteration.

### Hierarchical aggregation

Aggregate in three stages so raw sample count does not accidentally become scenario weight:

1. Within a run: combine repetitions for `policy + run + scenario`. Use the median as the point
   estimate and retain P25, P75, IQR, repeat count, and failure/timeout rate.
2. Within a scenario: combine run-level estimates for `policy + scenario` using a median-of-runs.
   Give each run one vote by default. Bootstrap runs, not individual samples, for uncertainty.
3. Across scenarios: calculate the robust policy summary while retaining every scenario row.

Emit both the scenario dataset and the robust summary. Never make the summary the only surviving
representation.

### Scenario-relative quality

Within each calibrated source scenario, convert policy performance to an empirical percentile
quality `q_s` in `[0, 1]`, using midranks for ties. Higher must always mean better. Percentiles make
scenario ceilings comparable without assuming equal throughput scale or variance.

When uncertainty intervals overlap heavily, retain the continuous percentile but record the
uncertainty. Do not replace the primary score with a brittle top-N membership flag.

### Robust cross-source ranking

Coverage is a gate. A policy is eligible for robust-leader status only after it has valid
measurements for every required source scenario.

For eligible policies, compare lexicographically in this order:

1. minimum scenario quality: `min(q_s)`;
2. lower-tail scenario quality: the type-7 P25 of `q_s`;
3. geometric mean quality:
   `exp(mean(log(max(q_s, epsilon))))`, with a documented epsilon;
4. scenario instability: lower median absolute deviation of `q_s` is better;
5. measurement stability: lower aggregate within-scenario IQR and timeout rate are better.

Before eligibility, rank candidates in a separate incomplete pool by coverage, pessimistic predicted
quality on missing scenarios, and uncertainty. Never let an incomplete policy outrank a fully
validated robust leader in the published result.

Use the lexicographic objective as the authoritative comparator. A scalarized score may be emitted
for visualization or optimizer convenience, but it must not replace the comparator unless tests
demonstrate that it preserves the ordering.

### Scenario-conditioned predictor

Train the ordinal predictor on:

```text
28 policy weights + source ratio + optional hardware context
    -> scenario-specific ordinal quality
```

The minimum source context is normalized source/core ratio. Add absolute core/source counts or
hardware class only when ablation tests show improved held-out cross-machine calibration.

For each candidate:

1. predict quality for every configured source scenario;
2. construct its predicted performance curve;
3. aggregate with the same robust comparator;
4. retain per-scenario uncertainty/disagreement;
5. allocate benchmark budget to strong candidates and informative uncertain candidates.

Use grouped validation splits by policy hash, and additionally report leave-one-source-scenario-out
validation. Random row splits leak the same policy across train and validation and are insufficient.

### Candidate scheduling

Partition each iteration's candidate budget explicitly among:

- new exploration;
- carry-forward completion of missing source scenarios;
- fixed anchors;
- robust-leader revalidation;
- disagreement/uncertainty audits.

With rotating source configurations, a promising new policy enters a carry-forward queue until all
required scenarios are measured. Persist this queue in checkpoint state so restart does not lose
coverage progress.

## Versioned data contract

Every raw benchmark record or sidecar manifest must contain:

- schema version;
- policy hash and all 28 weights;
- source count, available core count, and source/core ratio;
- closed-loop iteration and benchmark run ID;
- candidate cohort ID;
- machine/environment ID;
- commit SHA;
- repetition number, sample duration, timeout/failure status, and benchmark parameters;
- timestamps sufficient to audit a run.

Prefer embedded record metadata when the format supports it. A run-level manifest may hold fields
that are genuinely constant, but joining it to measurements must be deterministic and validated.

The merger must reject ambiguous duplicate identities and incompatible schemas rather than guessing
from directory names.

### Temporary current-workspace import

Support exactly one compatibility source: the workspace layout that exists when this upgrade begins.
Import only data that remains useful to the new optimizer, such as policy vectors, raw measurements,
run/source metadata that can be established unambiguously, and completed benchmark results.

Do not migrate or package trained model artifacts, optimizer model state, or old checkpoints from
that workspace. Those models describe the old pooled objective and data contract and are cheaper and
safer to retrain. This exclusion does not prevent packaging a new model produced by the upgraded
training run.

Treat the importer as a temporary bootstrap feature, not a permanent legacy framework:

- put it behind one explicit command or configuration switch that is off during normal new-format
  operation;
- keep it in a dedicated package/module boundary with no legacy-layout branches in the new merger,
  optimizer, predictor, checkpoint, or packager;
- convert accepted inputs immediately into the new immutable observation records;
- use a small declarative mapping of known current paths and semantic file types rather than a
  general version registry or speculative format detection;
- emit an import report listing every accepted, skipped, and rejected file and the reason;
- mark the temporary entry point and its focused tests with a single searchable removal marker;
- document the exact files and tests to delete after the current workspaces have been converted.

If current-layout metadata cannot be recovered without guessing, reject that item and report it.
Do not broaden the importer to older layouts merely because a file looks similar.

## Merger outputs

Refactor `DataMerger` around explicit immutable records for raw observations, run aggregates,
scenario aggregates, and robust summaries. Keep calibration separate from aggregation.

Required logical outputs:

- `scenario-results`: one record per policy and source scenario with run count, calibrated
  statistics, uncertainty, and calibration status;
- `robust-ranking`: one record per policy with coverage, worst-source quality, P25, geometric mean,
  cross-source MAD, stability, and eligibility;
- `calibration-report`: anchor overlap, scale factors, residuals, and weak/failed runs;
- `coverage-report`: measured and missing scenarios per policy;
- machine-readable vector files for downstream benchmarking;
- human-readable tables that label every column and explain the ranking order.

Preserve deterministic ordering and deterministic filenames.

## Final training-run package

At successful completion, and also for a recoverable partial completion, create one shallow
self-describing package:

```text
training-run-<run-id>/
+-- README.md
+-- manifest.json
+-- robust-ranking.csv
+-- scenario-results.csv
+-- calibration-report.csv
+-- coverage-report.csv
+-- vectors/
|   +-- robust-leaders.vectors.csv
|   +-- benchmark-ready.vectors.csv
|   +-- incomplete-promising.vectors.csv
+-- reports/
|   +-- robust-ranking.md
|   +-- source-scenario-comparison.md
+-- model/
|   +-- model artifact(s)
|   +-- model-metadata.json
+-- checkpoints/
|   +-- latest checkpoint and carry-forward state
+-- raw-data/
    +-- index or links/copies to immutable raw inputs
```

The manifest must identify each file's semantic type, schema version, row count, checksum, producing
stage, source run IDs, and whether it is complete. It must also distinguish artifacts produced by
this upgraded run from data imported out of the current pre-upgrade workspace. Filenames must distinguish:

- vectors only;
- vectors with measurements;
- machine-readable datasets;
- human-readable reports.

The top-level README must state the winning policies, required source scenarios, coverage rules,
calibration health, exact reproduction command, and where to find each artifact. Do not require a
user to open files to discover what they contain.

Packaging must be atomic: write to a temporary sibling directory, validate checksums and required
files, then rename to the final directory. Never overwrite a previous package; use a unique run ID.
A failed packaging step must leave the original training outputs untouched.

## Implementation order and prompt sequence

This plan follows the reusable [staged agent workflow](AGENT_WORKFLOW.md). Blueprint, verification,
and audit prompts select an exact model and reasoning effort. An implementation selection is
provisional until its completed blueprint performs the required post-blueprint capability
reassessment:

| Stage | Selected model | Reasoning effort |
| --- | --- | --- |
| Complex blueprint | `gpt-5.6-sol` | `max` or `high`, as labeled |
| Bounded blueprint | `gpt-5.6-sol` | `medium` |
| Implementation | Blueprint-selected coding model | `low`, `medium`, or `high` from the completed blueprint |
| Implementation verification and blueprint-conformance audit | Phase-selected coding/audit model | `high`, as labeled |

The model names are this plan's current selections, not permanent workflow requirements. The
completed Phase 3 history demonstrates why implementation is not fixed at `gpt-5.5 / low`: the
first pass omitted the main state-machine and acceptance surface and required frontier-level
remediation. Planning and blueprint prompts may make design decisions. Implementation and
verification may only execute settled decisions. Conformance audits report drift without reopening
the architecture.

Run the phases in order. Each phase is deliberately split into:

1. a `gpt-5.6-sol` **blueprint prompt** at the stated reasoning effort that investigates, makes the difficult decisions, and
   writes a self-contained implementation blueprint;
2. an **implementation prompt** whose model and effort are finalized after the blueprint evaluates
   its actual context load, systems coupling, repair breadth, test matrix, and prior model evidence;
3. a phase-selected **implementation-verification and blueprint-conformance-audit prompt** at
   `high` effort that executes tests, makes minor blueprint-settled fixes (including deterministic
   coverage, naming, formatting, and local validation corrections), then checks the blueprint,
   implementation, and completion record and writes a phase audit report.

The blueprint prompts are ordered from most to least demanding: `max`, then `high`, then
`medium`. This front-loads design judgment, but it does not prove that implementation is
low-effort. A detailed blueprint removes ambiguity while the code change may still require
frontier-level long-context systems reasoning.

### Combined C/D prompt rule

For every phase whose detailed prompt list still shows a `C` verification prompt followed by a
`D` conformance-audit prompt, those two bodies are one combined step: run them in a single prompt,
on one branch, with one completion record and one commit/push. The combined prompt first executes
the C verification work, may make minor blueprint-settled fixes such as deterministic coverage,
naming, formatting, or local validation corrections, and then performs the D classification and
writes its audit report. It must not make an architectural decision. The Phase 4 wording below is
the canonical merged form; later plan maintenance should fold the historical C/D text into that
same form rather than reintroducing separate stages.

### Blueprint handoff contract

Every blueprint prompt must create or update a phase blueprint under
`docs/robust-training-optimizer/blueprints/`. A blueprint is an implementation artifact, not a loose
essay. It must contain:

- the exact scope and explicit non-goals;
- settled mathematical definitions, invariants, defaults, and failure behavior;
- current classes/files involved and the intended ownership after the change;
- new or changed types, fields, method signatures, formats, and configuration keys;
- data flow and dependency boundaries;
- compatibility and deletion boundaries where applicable;
- a file-by-file implementation checklist in dependency order;
- deterministic test fixtures, assertions, and acceptance criteria;
- validation commands;
- an implementation-model reassessment that selects the exact implementation model, reasoning
  effort, and minimal exact context envelope from the completed blueprint's real demands; and
- risks or unresolved blockers that genuinely require another reasoning pass.

A blueprint pass must inspect enough current code to make the blueprint executable, but must not
implement production code. It may add or edit only its blueprint and closely related planning
documentation. It should reference prior blueprints instead of repeating their full analysis, and
must keep stable decisions in the earliest applicable blueprint. Before handoff it must replace
the plan's provisional implementation prompt label and body with the selected model and effort. If
that model is unavailable, stop for an explicit alternative rather than silently downgrade.

Every implementation pass must use the model and effort selected by its completed blueprint. It
must read `AGENTS.md`, this plan, its phase blueprint, and the exact prior context named by the
blueprint. It should then implement only the checklist, run the specified tests, and append a
concise completion record to the blueprint containing changed files, commands run, results, and
deviations. If implementation reveals a decision that the blueprint did not settle, stop and
record the question; do not invent a new architecture in implementation mode.

Every combined verification-and-conformance pass must rerun the blueprint's validation commands,
check each acceptance criterion, fix only minor defects whose resolution is already determined by
the blueprint, and append its evidence to the completion record. It must write
`docs/robust-training-optimizer/audits/<PHASE>-<FEATURE>-conformance.md`, classify requirements as
satisfied, deviated, unverified, or ambiguous, and avoid architectural suggestions unless the
implementation violates the approved blueprint.

Start each prompt on a dedicated `agent/...` branch from the completed previous prompt's branch.
When authorized, uncommitted changes from the current or immediately previous prompt may be added
to that prompt's commit or amend the immediately previous commit after inspecting the diff; never
include pre-existing user-owned changes. Commit and push after each completed prompt so the next
prompt can rely on that branch as its complete context. Temporary workflows remain permitted under
the restrictions at the end of this document.

### Phase 1 - foundational data, calibration, and robust ranking

#### Prompt 1A - BLUEPRINT - gpt-5.6-sol / max

> Read AGENTS.md, docs/ML_CLOSED_LOOP_ARCHITECTURE.md, this plan, and all current trainer, merger,
> ranking, serialization, and related test code. Do not implement production code. Write
> `docs/robust-training-optimizer/blueprints/01-data-calibration-ranking.md` following the blueprint
> handoff contract. Fully settle the versioned observation identity, scenario identity, fixed-anchor
> selection and reference-run rules, weighted-median log calibration, confidence thresholds,
> hierarchical aggregation, midrank percentile quality, lexicographic comparator, epsilon and
> quantile conventions, timeout treatment, deterministic ordering, and migration boundary. Specify
> exact Java types/APIs, ownership, file changes, synthetic fixtures, and compatibility seams.
> Reconcile every choice with current code so Prompt 1B can implement without broad rediscovery.
> Commit and push the blueprint only.

#### Prompt 1B - IMPLEMENTATION - gpt-5.5 / low

> Read AGENTS.md, this plan, and
> `docs/robust-training-optimizer/blueprints/01-data-calibration-ranking.md`. Implement that
> blueprint exactly: versioned observation records, anchor calibration, hierarchical merger outputs,
> scenario percentiles, and the authoritative robust comparator. Preserve scenario rows and do not
> modify the predictor, CMA-ES, or scheduling beyond compile-safe adapters explicitly listed in the
> blueprint. Add and run every specified deterministic test. If an unstated design choice appears,
> stop and record it in the blueprint instead of redesigning. Append completion notes, commit, and
> push.

#### Prompt 1C - IMPLEMENTATION VERIFICATION - gpt-5.5 / medium

> Read docs/AGENT_WORKFLOW.md, AGENTS.md, this plan, the Phase 1 blueprint, and its completion notes.
> Execute every Phase 1 validation command and check every acceptance criterion. Fix only defects
> whose resolution is already settled by the blueprint; do not redesign data identity, calibration,
> aggregation, or ranking. Append verification commands, results, skipped checks, and environmental
> limits to the Phase 1 completion record. Commit and push the verified corrections.

#### Prompt 1D - BLUEPRINT CONFORMANCE AUDIT - gpt-5.6-terra / high

> Read the approved Phase 1 blueprint, its implementation, completion notes, and verification
> record. Verify every blueprint requirement, identify deviations, undocumented assumptions,
> ambiguous or missing acceptance criteria, and write
> `docs/robust-training-optimizer/audits/01-data-calibration-ranking-conformance.md`. Do not propose
> architectural improvements unless the implementation violates the blueprint. If it does, report
> the violation and stop. Commit and push the audit report only.

### Phase 2 - scenario-conditioned learning

#### Prompt 2A - BLUEPRINT - gpt-5.6-sol / max

> Read the Phase 1 blueprint and completion notes, then inspect the current ordinal dataset,
> predictor, training/evaluation split, inference, and model serialization code. Do not implement
> production code. Write
> `docs/robust-training-optimizer/blueprints/02-scenario-conditioned-learning.md`. Settle the exact
> feature schema, normalization, scenario enumeration, targets, uncertainty/disagreement output,
> grouped-by-policy validation, leave-one-source-scenario-out evaluation, model metadata/versioning,
> ablation switches, deterministic seeds, and compatibility with Phase 1 records. Specify exact
> APIs, tensor/table shapes, file edits, tests, and acceptance thresholds. Make the handoff sufficient
> for `low`-effort implementation without rereading unrelated trainer code. Commit and push the blueprint
> only.

#### Prompt 2B - IMPLEMENTATION - gpt-5.5 / low

> Read AGENTS.md, this plan, both completed phase blueprints, and especially
> `02-scenario-conditioned-learning.md`. Implement only its checklist: scenario-conditioned ordinal
> data, predictor inputs and outputs, grouped validation, leave-one-scenario-out reporting,
> uncertainty/disagreement, metadata, and configured ablations. Run the blueprint's tests proving
> that source context affects predictions and that policy rows cannot leak across splits. Record any
> blocker rather than making a new modeling choice. Append completion notes, commit, and push.

#### Prompt 2C - IMPLEMENTATION VERIFICATION - gpt-5.5 / medium

> Read docs/AGENT_WORKFLOW.md, AGENTS.md, this plan, both completed blueprints, and the Phase 2 completion
> notes. Execute every Phase 2 validation command, including the opt-in DJL integration smoke when
> its environment is available, and check every acceptance criterion. Fix only implementation
> defects already settled by the blueprint. Append exact evidence and environmental limitations to
> the completion record. Commit and push the verified corrections.

#### Prompt 2D - BLUEPRINT CONFORMANCE AUDIT - gpt-5.6-terra / high

> Read the approved Phase 2 blueprint, its implementation, completion notes, and verification
> record. Verify every blueprint requirement, identify deviations, undocumented assumptions,
> ambiguous or missing acceptance criteria, and write
> `docs/robust-training-optimizer/audits/02-scenario-conditioned-learning-conformance.md`. Do not
> propose architectural improvements unless the implementation violates the blueprint. If it does,
> report the violation and stop. Commit and push the audit report only.

### Phase 3 - optimizer and closed-loop scheduling

#### Prompt 3A - BLUEPRINT - gpt-5.6-sol / high

> Read the Phase 1 and 2 blueprints and completion notes, then inspect SequenceFinder,
> CmaEsOptimizer, ScoreBandSampler, ClosedLoopRunner, checkpointing, and source-configuration
> rotation. Do not implement production code. Write
> `docs/robust-training-optimizer/blueprints/03-optimizer-scheduling.md`. Settle how robust predicted
> curves are compared, how islands and bands are seeded, exact candidate-budget partitions and
> rounding, carry-forward eligibility and prioritization, anchor and leader selection, disagreement
> audits, deduplication, deterministic tie-breaking, checkpoint schema, restart behavior, and
> incomplete-policy isolation. Include exact APIs, state transitions, file edits, fixtures, and
> acceptance tests. Commit and push the blueprint only.

#### Prompt 3B - IMPLEMENTATION - gpt-5.5 / low

> Read AGENTS.md, this plan, prior completion notes, and
> `docs/robust-training-optimizer/blueprints/03-optimizer-scheduling.md`. Implement its settled
> integration for SequenceFinder, CmaEsOptimizer, ScoreBandSampler, ClosedLoopRunner, and
> checkpoints. Add the explicit budget partitions and carry-forward queue, and keep incomplete
> policies out of robust-leader promotion. Run restart, rotating-scenario, budget-accounting,
> deduplication, and deterministic-selection tests from the blueprint. Append completion notes,
> commit, and push.

#### Prompt 3C - IMPLEMENTATION VERIFICATION - gpt-5.5 / medium

> Read docs/AGENT_WORKFLOW.md, AGENTS.md, this plan, the completed Phase 3 blueprint, and its completion
> notes. Execute its validation commands and check restart, rotation, budget, carry-forward,
> deduplication, checkpoint, and deterministic-selection acceptance criteria. Fix only defects with
> blueprint-settled resolutions. Append evidence and limitations, commit, and push.

#### Prompt 3D - BLUEPRINT CONFORMANCE AUDIT - gpt-5.6-terra / high

> Read the approved Phase 3 blueprint, implementation, completion notes, and verification record.
> Verify every requirement and write
> `docs/robust-training-optimizer/audits/03-optimizer-scheduling-conformance.md`, identifying all
> deviations, undocumented assumptions, and ambiguous or missing acceptance criteria. Do not
> propose architectural improvements unless implementation violates the blueprint. Commit and push
> the audit report only.

### Phase 4 - final result packaging

#### Prompt 4A - BLUEPRINT - gpt-5.6-sol / high

> Read all completed blueprints and outputs, then inspect current workspace paths, artifact writers,
> checkpoint/model output, shutdown and partial-failure behavior, and user-facing reports. Do not
> implement production code. Write
> `docs/robust-training-optimizer/blueprints/04-final-packaging.md`. Map every current artifact to
> the required shallow package, and settle manifest schemas and semantic types, filenames, checksums,
> provenance, raw-data indexing versus copying, README/report contents, complete versus partial
> status, atomic filesystem protocol, collision behavior, reproducibility command, deterministic
> bytes, cleanup, and failure recovery. Specify exact classes/APIs, file edits, golden fixtures, and
> acceptance tests. Make vector-only, vectors-with-measurements, machine-readable, and human-readable
> files unmistakable without opening them. After completing the blueprint, reassess the
> implementation model and effort from its actual context load, systems coupling, failure/test
> matrix, and the Phase 3 implementation evidence; record the selection and update Prompt 4B
> before handoff. Commit and push the blueprint only.

#### Prompt 4B - IMPLEMENTATION - gpt-5.6-sol / medium

> Use `gpt-5.6-sol` at `medium` reasoning effort. Read AGENTS.md, docs/AGENT_WORKFLOW.md, this
> plan's Phase 4 section, the Phase 3 conformance audit, and
> `docs/robust-training-optimizer/blueprints/04-final-packaging.md`; follow the blueprint's exact
> context envelope instead of preloading all prior blueprints. Implement the exact atomic package
> layout, manifest and checksums, descriptive datasets and vector files, Markdown reports, model
> metadata, checkpoint inclusion, raw-data index, partial-run status, and collision-safe
> publication. Preserve the settled memory, deterministic-byte, provenance, cleanup, and failure
> semantics across the complete lifecycle matrix. Run every naming, schema, checksum,
> determinism, atomicity, and failure-cleanup test specified by the blueprint. Append completion
> notes, commit, and push.

#### Prompt 4C - IMPLEMENTATION VERIFICATION AND BLUEPRINT-CONFORMANCE AUDIT - gpt-5.6-terra / high

> Use `gpt-5.6-terra` at `high` reasoning effort. Read docs/AGENT_WORKFLOW.md, AGENTS.md, this plan,
> the Phase 4 blueprint, and completion notes. Execute every packaging validation command and
> check schema, naming, checksum, determinism, atomicity, collision, partial-run, memory-pollution,
> and cleanup acceptance criteria. Fix minor blueprint-settled defects, including deterministic
> test coverage, naming, formatting, and local validation omissions. Then write
> `docs/robust-training-optimizer/audits/04-final-packaging-conformance.md`, classifying every
> requirement as satisfied, deviated, unverified, or ambiguous. Append evidence, fixes, and
> environmental limitations, commit, and push.

### Phase 5 - temporary current-workspace importer and user interface

#### Prompt 5A - BLUEPRINT - gpt-5.6-sol / medium

> Read prior blueprints and inspect only the current pre-upgrade workspace structure plus current
> CLI/configuration/documentation code. Do not implement production code. Write
> `docs/robust-training-optimizer/blueprints/05-temporary-importer-cli.md`. Inventory the exact
> useful current paths and file semantics that can be recognized without guessing. Settle the
> one-way mapping into new records, accepted/skipped/rejected reasons, import report, explicit
> off-by-default command or switch, searchable removal marker, package/module isolation, and exact
> deletion recipe. Historical trained models, optimizer state, and old checkpoints must be skipped;
> this must not become a general migration framework. Also specify final configuration keys,
> validation rules, CLI help, and documentation changes for anchors, ranking, budgets, importing,
> resuming, and locating packages. After completing the blueprint, perform and record the
> post-blueprint implementation-model reassessment required by docs/AGENT_WORKFLOW.md, then replace
> Prompt 5B's provisional model and effort before handoff. Commit and push the blueprint only.

#### Prompt 5B - IMPLEMENTATION - gpt-5.6-sol / medium

> Use `gpt-5.6-sol` at `medium` reasoning effort. Read AGENTS.md, docs/AGENT_WORKFLOW.md, this
> plan's Phase 5 section, `docs/robust-training-optimizer/blueprints/05-temporary-importer-cli.md`,
> and the blueprint's exact minimal context envelope. Implement the isolated vector-only importer,
> import report, strict typed configuration, stop/resume integration, CLI/help, and documentation
> exactly as specified. Keep all current-layout knowledge inside the removable boundary and
> immediately convert accepted vector rows to immutable `PolicyVector` records. Reject legacy
> measurements whose observation identity is unrecoverable; do not import old models, optimizer
> state, or checkpoints. Run focused acceptance, rejection, disabled-path, atomicity,
> configuration, command, and deletion-boundary tests. Append completion notes, commit, and push.

#### Prompt 5C - IMPLEMENTATION VERIFICATION - gpt-5.5 / medium

> Read docs/AGENT_WORKFLOW.md, AGENTS.md, this plan, the Phase 5 blueprint, and completion notes. Execute
> every importer, CLI, configuration, documentation, rejection, disabled-path, and deletion-boundary
> validation. Fix only blueprint-settled implementation defects. Append evidence and environmental
> limitations, commit, and push.

#### Prompt 5D - BLUEPRINT CONFORMANCE AUDIT - gpt-5.6-terra / high

> Read the approved Phase 5 blueprint, implementation, completion notes, and verification record.
> Verify every requirement and write
> `docs/robust-training-optimizer/audits/05-temporary-importer-cli-conformance.md`. Identify
> deviations, undocumented assumptions, and ambiguous or missing acceptance criteria. Do not
> propose architectural improvements unless implementation violates the blueprint. Commit and push
> the audit report only.

### Phase 6 - end-to-end verification and audit

#### Prompt 6A - BLUEPRINT - gpt-5.6-sol / high

> Read every blueprint and completion record. Inspect the resulting integrated test surface and
> generated package, but do not change production code. Write
> `docs/robust-training-optimizer/blueprints/06-verification-audit.md` as an executable audit plan.
> Define the end-to-end synthetic experiment, exact known rankings, calibration transformations,
> restart interruption point, deterministic seeds, expected package inventory/checksums, report
> inspection checklist, full validation command sequence, and criteria separating code defects from
> environment limitations. Include a targeted search list for stale pooled-policy and P99
> assumptions. Commit and push the audit blueprint only.
> Before handoff, perform and record the post-blueprint implementation-model reassessment required
> by docs/AGENT_WORKFLOW.md, then replace Prompt 6B's provisional model and effort.

#### Prompt 6B - IMPLEMENTATION - gpt-5.6-sol / high

> Use `gpt-5.6-sol` at `high` reasoning effort. Read AGENTS.md, docs/AGENT_WORKFLOW.md, this plan's
> Phase 6 section, the Phase 3-5 conformance audits, and
> `docs/robust-training-optimizer/blueprints/06-verification-audit.md`. Execute the audit exactly.
> Follow its exact minimal context envelope. Add only its fixtures, golden resources, and minimal
> package-private test seams; run the focused, end-to-end, lifecycle, failure, and full regression
> suites; inspect generated packages and reports; and fix implementation defects only where an
> existing blueprint already settles the correct behavior. Do not make new statistical or
> architectural decisions. Record exact results, package fingerprints, fixes, and environmental
> limitations in the audit blueprint. Commit and push all validated corrections.

#### Prompt 6C - IMPLEMENTATION VERIFICATION - gpt-5.5 / medium

> Read docs/AGENT_WORKFLOW.md, AGENTS.md, this plan, the Phase 6 executable audit blueprint, and its
> recorded results. Repeat its focused and end-to-end validation sequence, inspect the package and
> reports, and check every audit acceptance criterion. Fix only defects already settled by a prior
> blueprint. Append exact evidence and limitations, commit, and push.

#### Prompt 6D - BLUEPRINT CONFORMANCE AUDIT - gpt-5.6-terra / high

> Read the approved Phase 6 blueprint, test harness, recorded results, and verification record.
> Verify that the executable audit was performed exactly as specified and write
> `docs/robust-training-optimizer/audits/06-verification-audit-conformance.md`. Identify deviations,
> undocumented assumptions, and ambiguous or missing acceptance criteria without proposing new
> architecture. Commit and push the audit report only.

### Phase 7 - cleanup and handoff

#### Prompt 7A - BLUEPRINT - gpt-5.6-sol / medium

> Read all blueprints and completion records, then review the complete branch diff. Do not implement
> cleanup yet. Write
> `docs/robust-training-optimizer/blueprints/07-cleanup-handoff.md` with an exact deletion and
> cleanup checklist: stale pooled-policy assumptions, per-file P99 normalization, ambiguous names,
> accidental legacy dependencies outside the importer, temporary workflows, obsolete docs,
> formatting, generated files, and importer removal proof. Identify each target by file and symbol,
> list final validation commands, and define the final handoff summary and result-package evidence.
> After completing the blueprint, perform and record the post-blueprint implementation-model
> reassessment required by docs/AGENT_WORKFLOW.md, then replace Prompt 7B's provisional model and
> effort. Commit and push the blueprint only.

#### Prompt 7B - IMPLEMENTATION - PROVISIONAL gpt-5.5 / low; Prompt 7A must replace

> Do not execute while this prompt is labeled provisional. Use the model and effort selected and
> written here by Prompt 7A. Read AGENTS.md, this plan, and
> `docs/robust-training-optimizer/blueprints/07-cleanup-handoff.md`. Perform only its enumerated
> cleanup. Remove temporary workflows, verify the importer can be deleted using only the documented
> boundary, run formatting and the final validation sequence, inspect the final diff, and record
> exact commands and results. Do not broaden scope. Append final completion notes, commit, and push.

#### Prompt 7C - IMPLEMENTATION VERIFICATION - gpt-5.5 / medium

> Read docs/AGENT_WORKFLOW.md, AGENTS.md, this plan, the Phase 7 blueprint, and final completion notes.
> Execute the final validation sequence, inspect the full diff and package evidence, and confirm
> every enumerated cleanup without broadening scope. Fix only blueprint-settled defects. Append
> exact evidence and limitations, commit, and push.

#### Prompt 7D - BLUEPRINT CONFORMANCE AUDIT - gpt-5.6-terra / high

> Read every approved blueprint, completion note, conformance report, the final implementation, and
> the Phase 7 verification record. Verify final conformance and write
> `docs/robust-training-optimizer/audits/07-cleanup-handoff-conformance.md`. Identify deviations,
> undocumented assumptions, and ambiguous or missing acceptance criteria. Do not propose
> architectural improvements unless the implementation violates an approved blueprint. Commit and
> push the final audit report only.

## Test strategy

At minimum, include deterministic synthetic cases for:

- the robust winner being second-best in every scenario;
- a specialist dominating one scenario but failing the minimum-quality criterion;
- iteration cohorts improving while anchors remain constant;
- global machine speed changing while relative anchor performance remains stable;
- one unstable anchor among several stable anchors;
- insufficient anchor overlap;
- unequal repetition counts across runs;
- missing scenarios and later carry-forward completion;
- tied measurements and percentile midranks;
- timeouts and failed measurements;
- resume producing the same schedule and ranking as an uninterrupted run;
- current-workspace import accepting relevant vectors/measurements while skipping old models and
  checkpoints;
- deleting or disabling the temporary importer without affecting new-format training, merging, or
  packaging;
- package publication failing without corrupting prior outputs;
- identical inputs producing byte-stable machine-readable outputs where timestamps are excluded or
  controlled.

For implementation work, use a new `agent/...` branch. The user has authorized commits and pushes
to that branch. Temporary GitHub Actions workflows may be created when the normal workflow cannot
exercise the required environment, but they must be narrowly scoped, must not expose secrets or
publish artifacts externally, and must be removed before handoff unless the user explicitly asks to
keep them.
