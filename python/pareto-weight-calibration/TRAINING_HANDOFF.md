# Parameter-driven training pipeline

Implementation and experiment execution are separate. No production model, benchmark evidence,
frozen participation artifact, or runtime Java source is replaced by this pipeline. The checked-in
timing manifest contains **synthetic mechanical fixtures only**. It must be replaced with reviewed
fork evidence supplied by an external handoff before an actual timing experiment.

Only reusable configuration templates are checked in under `tasks/`: the participation and
synthetic timing training tasks, plus the nested benchmark harness templates. Concrete campaign,
tuning, study, lock, anchor, and provenance configurations belong in external experiment handoffs.

## Install and inspect

From `python/pareto-weight-calibration`, use Python 3.12 or newer:

```sh
python -m pip install -e '.[tournament,dev]'
pareto-train --task tasks/participation-binary.json --device cpu --dry-run
pareto-train --task tasks/cache-timing-fixed-surface.json --device auto --dry-run
```

Dry runs verify evidence hashes, inventory assertions, compatibility, array shapes, candidate
capabilities, and nested group plans. They do not fit a model. Without `--output-dir`, they print
deterministic JSON. The historical
`pareto-model-tournament` and `pareto-model-tournament-v2` entry points also accept
`--task` and delegate to this runner. Their old diagnostic modes remain available.

After separately authorizing an experiment and supplying real evidence:

```sh
pareto-train --task tasks/cache-timing-fixed-surface.json --device cuda \
  --output-dir ../../experiments/cache-timing-fit
```

The output directory must not exist. Outputs are `run.json`, its SHA-256 sidecar, and, for supported
exporters, `evaluator.json`, its sidecar, and
`TaskEvaluator.java`. The run records outer predictions and inner selections, final selection,
preprocessing, cache keys, actual backend and evidence hashes. The Java file is an offline
deliverable; production integration is separate.

## Task and evidence contracts

`TrainingTaskSpec` version 1 rejects unknown sections and invalid names, expressions, treatment
paths and model capabilities. Features and controls are ordered separately. For response fitting the
design inputs concatenate features and controls; target width is independent of both. A third
control needs another control definition and corresponding measured treatment/config values, without
an engine or model edit. Tests also cover 3, 5 and 9 feature inputs, reordering, and two regression
targets with different missing-outcome masks.

Manifest paths are relative to the task file. Artifact paths and hash-lock paths are relative to the
manifest. A manifest has `schemaVersion: 1`, optional
`hashes: {path: sha256}`, and either:

- Participation: `dataset: path` plus a required hash for that artifact. The shipped manifest copies
  the existing frozen input lock. Its 102/33/43 counts are task inventory assertions, not engine
  constants. The adapter retains IDs, labels, exclusions, transformed runtime inputs, physical
  training costs, fold-local influence and the existing supported-relative-regret metrics.
- Fork outcomes: `arms: [{path: ..., sha256: ...}]`. Each file is one record or an array of records.
  Inline `{record: ...}` entries are also supported for fixtures. See
  `datasets/cache-timing-synthetic.json` for the exact structure.

Each fork record contains `runId`, `forkId`, `workloadId`, `passId`, `identity`,
`fixture`, `treatment`, `config`, `outcome`, and `provenance`. Store runtime and model identities in
`identity`; resolved topology and authoritative physical worker counts in `fixture`; original
canonical configuration in `config`; measured JMH execution score and retained windows in `outcome`.
Optional state samples belong alongside these fields with acquisition time and basis. Predictive
columns are included only through named sources in the task.

This adapter consumes the normalized fork table, not arbitrary raw JMH report formats. A new
report/evidence format needs an ingestion adapter. A score is one fork measurement; retained windows
are provenance, never extra independent rows. A small or inconclusive binary difference does not
exclude a response measurement. Missing regression outcomes use explicit `missing: "mask"`;
malformed or out-of-domain values are errors, not missing measurements.

The same `(runId, forkId)` is retained once. Conflicting duplicates fail. Files are read once per
manifest load; immutable parsed content is cached by SHA-256, parser version and evidence selection.
If an entry declares `selection`, its value must match `provenance.selection` in the already
selected fork record. Window extraction/score calculation is an upstream evidence operation, never
an implicit recalculation by the training runner.

Within a workload family, non-treatment configuration, identity and resolved fixture must match.
Only exact scalar JSON Pointer leaves declared by controls may differ. Lifecycle, model hashes,
weights, topology, observation mode and measurement accounting remain compared. Explicit controls
must agree with their configuration values. Historical defaults require
`resolvedDefaults: {"/exact/config/path": value}`; the original JSON is retained. No worker count is
inferred from CPU-list length. The historical adjacent-pair compatibility API remains separate and
retains its existing checks, now including contention half-life equality and explicit/default
provenance.

## Features, models and validation

The expression vocabulary is identity, scale, ratio, log, log1p, product, square and clamp. Sources
use dotted paths; expression arguments name sources for raw transforms and feature names for basis
terms. There is no Python `eval`. Standardization can occur before or after expansion. The legacy V2
recipe uses standardization before expansion and now shares the named basis compiler.
Candidate-specific basis and training-weight choices and Cartesian parameter grids are expanded
inside the nested selection process.

The initial array adapters use the same scikit-learn estimator families as V2:
logistic, CART, random forest, extra trees and histogram boosting. Ridge supports regression with
common output masks. `independent_ridge` explicitly fits separate outputs when their observation
masks differ. Hist boosting has no native multioutput capability here. Named monotonic constraints
are accepted only where the adapter and unexpanded schema support them. Timing tasks declare none.
Specialized Pareto/bounded-boundary objectives and historical diagnostic models remain in their
existing adapters; this refactor does not reinterpret their loss. Splines and additional estimator
families are not initial generic export kinds.

All forks, controls and retained windows from a family stay together in workload holdouts. Each
outer training set performs its own candidate/threshold selection using inner family folds. Outer
results are evaluation, not a model-family selection leaderboard. Final selection uses full-data
inner folds and then fits once on the full dataset. One-class classification folds return an
explicit constant class while preserving the complete declared label vocabulary.

Timing evaluation groups replicate forks by their complete measured control vector. It chooses one
coupled vector using predicted throughput and reports measured throughput normalized to the best
measured choice per workload, its lower tail, worst workload and per-workload regressions. The
throughput decision output is named explicitly when regression has several outputs. Other regression
tasks use masked MSE. Held-out prediction MSE is also retained for timing.

These outer results describe unseen workloads. They do **not** establish interpolation quality
between treatment settings within a seen workload. Such an experiment needs a separately declared
treatment holdout and must not be reported as new-workload validation. Repeated development choices
based on outer results also do not create an untouched final performance estimate. See the
[scikit-learn nested-validation explanation](https://scikit-learn.org/stable/auto_examples/model_selection/plot_nested_cross_validation_iris.html).

## Devices, caching and export

`auto` chooses CUDA for the dimension-independent ridge adapter when available; CPU-only estimators
stay on CPU. An explicit unsupported CUDA request fails. Ridge uses float64 masked least squares on
CPU/CUDA. Device-resident weighted fold arrays are reused across regularization candidates. Every
fit/output owns its objective and mask. The specialized boundary CUDA loss remains unchanged. Caches
are process-local and keyed by task/schema, evidence, training IDs, arrays, preprocessing, weights,
candidate, seed and actual backend/version. Threshold sweeps reuse predictions without refitting. No
performance improvement is claimed.

Linear evaluator artifacts carry ordered inputs, transforms, named basis terms, scaling
order/statistics, coefficients, intercepts, output interpretation and bounds/rounding. Python
evaluates variable widths. Generated Java uses primitive methods with compiled expressions; it has
no runtime parsing, maps or per-call allocation. `timingIndex` chooses one index into the
preallocated measured control table, with primitive component accessors. Exporting this offline
surface does not turn configured sources/work units into live PHR or body-time measurements.

The existing participation Java format remains byte-preserved through
`runtime_export.participation_java`, which delegates to its existing exporter. The new task artifact
is a separate format; neither path rewrites
`ParticipationLogisticModel`. Unsupported model/export pairs fail explicitly. New evidence
semantics, new losses and unsupported evaluators require adapters; configuration alone cannot define
those decisions.

## Verification

```sh
python -m pytest -q tests/test_training_pipeline.py
python -m pytest -q tests
```

Tests fit fixed fixture candidates and synthetic tasks only. They check frozen input/weight parity,
fixed V2 prediction parity, nested grouping, arbitrary class labels and one-class folds, multiple
controls versus multiple targets, masks, duplicate arms, declarative compatibility, cache reuse and
deterministic output. CPU/CUDA numeric checks run when CUDA is present. Java parity tests use the
repository's Mise-selected JDK. These checks are not benchmarks, a production training run or
evidence that the timing policy improves throughput.
