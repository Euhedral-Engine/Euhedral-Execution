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

Run these prompts in order. The labels use the available reasoning range, but the sequence starts
with the most reasoning-intensive architecture and statistical work so later implementation prompts
inherit settled contracts.

### Prompt 1 - MAX - data model, calibration, and robust objective

> Read AGENTS.md, docs/ML_CLOSED_LOOP_ARCHITECTURE.md, this plan, and the current training module.
> Implement the versioned observation identity, fixed-anchor calibration model, hierarchical
> aggregation records, scenario-relative percentile quality, and lexicographic robust comparator.
> Preserve scenario rows. Do not change the predictor or CMA-ES yet. Add deterministic synthetic
> tests for moving candidate cohorts, anchor drift, missing anchors, unequal repetitions, ties,
> timeouts, incomplete coverage, and a policy that is consistently second-best beating a specialist
> that wins one scenario and fails another. Document any plan deviation with evidence.

### Prompt 2 - MAX - scenario-conditioned learning and validation

> Read the completed data/merger contracts and implement the scenario-conditioned ordinal dataset,
> predictor inputs, grouped-by-policy validation, and leave-one-source-scenario-out reporting.
> Candidate inference must predict every configured scenario and expose uncertainty/disagreement.
> Prevent train/validation leakage. Add ablation-friendly context configuration and tests proving
> source context affects predictions while policy identity cannot leak through row splitting.

### Prompt 3 - HIGH - optimizer and scheduling integration

> Upgrade SequenceFinder, CmaEsOptimizer, ScoreBandSampler, and ClosedLoopRunner to consume robust
> summaries and per-scenario predictions. Add explicit budget partitions for exploration,
> carry-forward coverage completion, fixed anchors, leader revalidation, and disagreement audits.
> Persist the carry-forward queue in checkpoints. Keep incomplete policies out of robust-leader
> promotion. Add restart, rotating-scenario, budget-accounting, and deterministic-selection tests.

### Prompt 4 - HIGH - final artifact packaging

> Implement atomic end-of-run packaging exactly as defined in this plan. Generate the manifest,
> checksums, shallow directory layout, clearly named vector-only and measured datasets, readable
> Markdown reports, model metadata, checkpoints, and raw-data index. Package recoverable partial
> runs with an explicit incomplete status. Add tests for naming, semantic manifest types,
> reproducibility metadata, collision avoidance, checksum validation, atomic publication, and
> failure cleanup.

### Prompt 5 - MEDIUM - compatibility, CLI, and documentation

> Add the isolated, one-way temporary importer for relevant vectors and measurements from the
> current pre-upgrade workspace layout. Do not carry over historical trained models, optimizer model
> state, or old checkpoints, and do not build a general legacy migration framework. Convert accepted
> files immediately to the new records, produce an accepted/skipped/rejected import report, and add
> one searchable removal marker plus deletion instructions. Also add configuration properties for
> anchors, robust ranking, budget partitions, and package location, plus CLI/help text and updates to
> CLOSED_LOOP.md, the training README, and ML_CLOSED_LOOP_ARCHITECTURE.md. Include exact commands for
> importing the current workspace once, resuming new-format runs, and locating final results. Keep
> defaults safe and deterministic.

### Prompt 6 - MEDIUM - integration verification and statistical audit

> Run the focused training-module test sequence and create an end-to-end synthetic closed loop whose
> known robust winner is not the winner of every individual scenario. Verify calibration invariance
> when candidate cohorts change, restart equivalence, complete artifact packaging, and deterministic
> reruns. Inspect every produced report as a user would. Fix defects within scope and report
> environmental limitations separately.

### Prompt 7 - LIGHT - cleanup and handoff

> Search for stale pooled-policy assumptions, per-file P99 normalization, ambiguous output names,
> accidental legacy-format dependencies outside the temporary importer, temporary workflows, and
> obsolete documentation. Verify the importer can be removed by deleting only the documented
> temporary files, tests, command registration, and documentation block. Run formatting/diff checks, confirm only intended
> files changed, summarize schema and behavior changes, and provide the final result-package path
> and validation commands.

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
