# JSON-driven historical parameter tournaments

Run from the repository root with the tournament dependencies in an isolated Python environment:

```bash
export PYTHONPATH="$PWD/python/pareto-weight-calibration/src"
/tmp/euhedral-round5-analysis-mise-venv/bin/python -m pareto_weight_calibration.training_runner \
  --task python/pareto-weight-calibration/tasks/live25-joint5d-v1/park-body.json
```

The JSON declares the data sources, arbitrary parameter vector, output panels, transformations,
model families and grids, nested validation, ensembles, constraints, Sobol search, and output
location. `--dry-run` validates the specification and reports dimensions and expanded model count.
An existing output directory is refused. For another offline run, set a new `outputDirectory` in
JSON or supply `--output-dir`. No command in this workflow launches JMH.

The current environment was created separately from the frozen benchmark environments. Install
`python/pareto-weight-calibration[tournament]` plus pytest in a Python 3.12 environment to create
another environment. Exact versions used by a completed fit are recorded in its `run.json`. The
exported JSON schema is `schemas/parameter_tournament.schema.json`.

## Consolidated CACHE history

All historical CACHE run output is now in `experiments/cache-run-data.tsv`. Completed run/evidence directories and superseded CACHE presets were removed only after
byte-for-byte verification. Source, readable findings and current preparation remain. Original
configs and older analyses can be recovered from the archive.

The TSV has three record types:

- `run`: 2,718 queryable run rows, with policy/workload identity, full calibration and JMH config
  JSON, execution throughput and 14,090 nested measurement-window values. A run row is not a claim
  that every older log contains exactly one JVM; inspect its JMH configuration and original log.
- `artifact`: 5,831 original files, stored losslessly as zlib-compressed base64 with original path,
  byte count and SHA-256. This preserves all raw logs, original trial configs, source identities,
  collected evidence, old failed fits, analyses and receipts, including excluded measurements.
- `metadata`: the original root and directory inventory.

The enriched archive is 68,938,619 bytes, SHA-256
`42dc5cb78090553a3cb3a2cee1438e908589225257d23715b5ad06e90248b53c`. Use a TSV parser with a
sufficiently large field limit; archive payloads can exceed CSV defaults. Filter on
`recordType == "run"` for the flat run table. The full original configs are available without
decoding compressed artifacts in `calibrationConfigJson` and `jmhConfigJson`.

```bash
python -m pareto_weight_calibration.run_archive verify \
  --archive experiments/cache-run-data.tsv
python -m pareto_weight_calibration.run_archive restore \
  --archive experiments/cache-run-data.tsv --destination /tmp/cache-history-review
```

Restore refuses to overwrite files. Historical findings still contain original artifact paths; those
paths are the keys inside the TSV and are recreated under the restore destination. The new history
loader restores into a disposable directory automatically, verifies provenance there, and keeps
original paths in the resulting evidence ledger. It does not recreate loose history in the
repository. The archive checksum is included in the dataset manifest through `dataset.json`.

## Generic contracts and selection

`surrogate_spec.py` defines the task schema. `surrogate_models.py` registers estimator factories;
adding a new implementation may require a registry entry, while selecting it and all of its grid
values requires JSON only. The current registry has 14 families. Native multi-output models and
independent per-output models are both supported. Output-specific missing-data masks are preserved.

The scheduler-history adapter verifies full inactive coefficient/transform/output equality after
substituting the declared active coordinates. It checks runtime source identities, reviewed hash
exceptions, actual fixtures, JMH mode/schedule/headers, raw hashes, windows and means. Fixed
policies are controls or excluded context, never invented parameter vectors. Other datasets can use
the
`response_rows` adapter without CACHE-specific Python changes. Each row then declares `rowId`,
`campaignId`, exact `theta`, canonical `thetaId`, and its target map.

One scheduler fork remains one replication unit. Model rows are policy/block panels referencing
those forks, with an output absent unless all of its workloads are present. Log returns are matched
within the same campaign, workload and block to OFF and, where available, the exact center. No
center is borrowed from another campaign. The center-relative self-return is zero; OFF-relative
center repeats retain campaign variation. Neither windows nor fragment states become reward rows.

Primary validation groups every repeat of an exact theta across campaigns. Outer folds assess
inner-fold model/ensemble selection. Scaling and polynomial bases are trained inside each fold.
Final models use full-development inner validation, never outer-test results. Fixed-model outer
leaderboards are descriptive comparisons; the best entry in a large leaderboard is not an unbiased
estimate of selecting that model. `held_theta_metrics.tsv` reports the nested selection procedure.

Metric definitions are in log-return units. RMSE/MAE use held panel observations; ranks, top-k
recall and regret compare the mean held response for each theta. OOF action metrics combine held
predictions from their respective fold models; they are not the result of a fresh benchmark.
Prediction ties receive fractional top-k credit and mean tied-action regret. No arbitrary tie
becomes learned timing. Selection order and ensemble weighting are JSON fields. Mean and
validation-weighted ensembles use training-validation-selected distinct families/bases. Disagreement
is dispersion among qualified models, not a confidence interval. Campaign transfer is secondary and
deliberately allows the same center theta on both sides; its coverage and shared theta are recorded
explicitly.

The archive retains all extreme valid returns. There is no clipping, CV reward, per-window reward,
or raw-throughput scalar combining scarce and plentiful workloads. The current task predicts 24
panels for each of two reference systems (48 outputs).

## Proposal search and review boundary

The historical reliability-policy task is `tasks/live25-reliability-round4.json`. Round3's all-floor policy is retained
in its original task and artifacts. Reliability-aware behavior is enabled explicitly in JSON.

`proposal.reliability` declares ranking skill and absolute-calibration requirements. Outputs with
both qualify as HARD; useful ordering without absolute skill is SOFT; the remainder are UNRESOLVED.
Only qualified OFF outputs can veto predicted floors. SOFT shortfalls remain separate risk
objectives.
Unresolved outputs retain predictions and disagreement but cannot imply safety, failure, or rank.
`directionAlternatives` chooses center-relative direction where its validation skill is stronger.
No output names or parameter counts are special-cased in the generic proposal implementation.

Authority for retrospective outer-held actions is derived only from that fold's training/inner
predictions. Final search uses nested held-theta evidence. `proposal.retrospective` declares random
comparison budgets, usefulness labels, measured guardrail diagnostics and named region audits.
These are development diagnostics; the benchmark remains the oracle. Campaign-transfer results
remain a separate diagnostic from campaign bias within grouped OOF prediction errors.

The task searches the same 8,192 seeded Sobol points and unchanged parameter bounds. It excludes
measured proximity and a JSON-declared radius around repeated measured bad outcomes, validates
support, predicts all outputs, and constructs per-output risk/Pareto tradeoffs. Distinct theta are
retained on prediction ties; equal predictions are not measured timing equivalence. Parameter-space
clusters and role/diversity selection produce a small batch. Clusters do not establish physical
scheduler regimes. Disagreement is model spread, not a confidence interval.

The benchmark adapter writes opaque policy JSON and the existing calibration harness, candidate
manifest and freeze lock. The existing confirmation check/run/collect functions accept the generated
stage; no benchmark is executed by the training runner. Exact OFF and center controls remain. Source
or topology mismatches require a reviewed new freeze, not editing a lock. No Java is generated here.

If no candidate survives reliable constraints, the runner reports that result without silent
relaxation. Weak absolute predictions alone cannot veto a region. New proposals still require actual
matched scheduler measurements and review before any production change. Round4 selected six points;
its retrospective scarcity ranking improved modestly while plentiful discrimination remained weak.
The full per-output classifications and limitations are in the linked findings.

The runtime Java controller and production defaults are untouched. No experiment-specific Python
edits are needed to rerun this tournament with compatible evidence, configure another model/grid, or
search another declared parameter domain.

Completed live-25
results: [findings](../../benchmarks/src/main/presets/findings/cache-timing-live25-historical-tournament.md).

Historical round4
results: [findings and handoff](../../benchmarks/src/main/presets/findings/cache-timing-live25-reliability-round4.md).

## Known useful measured regions

`proposal.knownRegionCoverage` adds a local coverage constraint after history exclusion,
config support checks, reliability filtering and Pareto construction. It does not alter
model authority, floors, soft penalties or production acceptance. Normal basin/role selection
runs first; bounded augmentation preserves its proposals, especially disagreement exploration.

The JSON `usefulness` declares primary and topology targets, minimum positive fraction,
minimum topology/broad returns, positive-return cap, per-target block coverage and optional
plentiful floors. `selection: "pareto_tradeoffs"` retains measured non-dominated tradeoffs in
positive primary workload count, minimum topology return and capped broad return.
`evidenceScope: "campaign"` qualifies each campaign separately and unions historical useful
theta, so later bad repeats cannot erase earlier qualification before rejection is assessed.
These are development-data selection criteria, not fresh validation or deployment thresholds.

`radius` uses Euclidean distance after the existing parameter normalization. Each required
anchor must have at least `minEligibleParetoPoints` within that radius. A distant basin member
never counts. Overlapping anchors may share a point only if that point is inside both radii.
`maxRegions` limits the number of representatives needed to cover supported useful anchors;
it never silently drops an anchor. `maxProposals` bounds total batch expansion. If coverage,
protected roles and basin/diversity constraints cannot fit, preparation fails explicitly.

`selectionPriority` orders generic coverage, consensus, broad-direction and distance criteria;
sample index breaks remaining ties. The selector first tries adding points while retaining
all normal proposals. If necessary it considers removing unprotected roles in reverse normal
selection order, using the fewest removals first. Every original Pareto basin remains represented.
A bounded deterministic search resolves overlap/diversity conflicts, with the JSON
`maxSearchNodes` preventing unbounded preparation work. No coefficient vector or historical ID
is a policy input. `known_region_coverage.tsv/json` records qualification, support, rejection,
before/after distances, shared representatives and any replacements.

`rejectedRegionPolicy` requires explicit campaign chronology and later same-campaign matched
responses inside the neighborhood. All later observed points must have sufficient complete
blocks, and every such block must satisfy the declared poor positive-fraction, minimum-topology
and broad-return criteria. Missing chronology, insufficient replication, or contradictory
later evidence does not establish rejection. Prediction alone never rejects a measured region.

A new task can reuse a frozen local tournament through:

```json
"reuseFit": {
  "directory": "experiments/prior-tournament",
  "lockSha256": "<pinned SHA-256>"
}
```

The existing `training_runner --task ... --output-dir ...` checks the parent lock and every
artifact before loading models, requires identical dataset/model/validation/predictive-search
contracts and package versions, and copies fit/validation/reliability evidence byte-for-byte.
Only coverage and revision/output preparation may change in this mode. It regenerates the
same dense predictions and selects proposals without refitting the tournament. A fresh output
directory is required. `reuse_provenance.json` retains parent artifacts and source identities;
new source and benchmark locks describe the new revision.

## Controlled one-term extension screens

`training_runner --task <json>` also accepts `kind: "parameter_screen"`. The JSON declares an
exact anchor artifact/hash, a tournament task template, extension paths and normalized basis
expressions, a timing-effect bound recipe, signed normalized levels, fixtures and support points.
It derives symmetric bounds from `log(maxUnclampedFactor) / max(abs(feature))`, then tests all
corners of the existing parameter box and both endpoints, shrinking deterministically if needed.
No experiment-specific parameter count or candidate coefficient is encoded in the generator.
The existing CACHE adapter validates the expression against its seven-term runtime basis.

The initial screen holds existing parameters at the anchor to isolate one new dependency. Its
family tasks retain the entire old box plus one new coordinate for subsequent joint tuning.
`Parameter.offset` permits an exact delta coordinate: runtime coefficient = offset + parameter.
Zero thus preserves tiny frozen residuals, with no runtime semantic change or approximate reset.
Default offset is zero, preserving existing absolute-parameter tasks. Historical compatibility
remains exact after restoring declared active paths; descriptive near-zero labels never relax it.

`requireVaryingParameters` prevents a full task from fitting a proposed dimension with fewer than
three observed values. Family JSON tasks include full models and `ModelConfig.inputIndices`
ablations: omit the added input while retaining identical evidence, targets and full-theta folds.
This compares information added by the coordinate rather than comparing different evidence sets.
Incomplete workload aggregates remain missing. A small first slice cannot establish the full
five-dimensional response geometry or validate unmeasured plentiful fixtures.

The screen audits both raw archived policy shapes and the exact historical adapter's inclusion
rules. A coefficient fixed throughout compatible history is not identified, regardless of how
many repeated forks exist. It emits benchmark preparation, future tournament/proposal tasks,
behavior catalogs, bounds, compatibility audits and locks; it never launches JMH or fits a
meaningless extra dimension to fixed-coordinate history.

## Current joint 5D offline study

Both v1 benchmark stages are complete and preserved in `experiments/cache-run-data.tsv`.
The new revision is `tasks/live25-joint5d-v2/`. Park-body and park-PHR-body remain independent
five-coordinate families; the exact 7931 reference and all parameter bounds are unchanged.
Repeated 4D history appears on each family's zero plane as shared evidence, not extra forks.

Run the entire declared sequence through the generic entrypoint:

```bash
PYTHONPATH=python/pareto-weight-calibration/src python -m pareto_weight_calibration.training_runner \
  --task python/pareto-weight-calibration/tasks/live25-joint5d-v2/study.json
```

A `parameter_study` declares family fit/ablation/search tasks, retrospective strategies, a global
proposal budget and benchmark preparation. Each fit is an ordinary `parameter_tournament` task.
Existing outputs are reused only after lock and task verification. Fresh fits resolve their new
lock identities automatically before proposal replay. Completed study outputs require a new
revision; no benchmark loop or JMH execution is part of this command.

An explicit `reuseFit.allowProposalChanges: true` permits a reviewed new search over identical
frozen dataset, model and validation contracts. It does not refit or alter measured evidence.
Default replay remains restricted to coverage/output changes. All parent model hashes and package
versions are checked before deserialization.

### Dense inference and exact Pareto search

`proposal.power` defaults to 24; powers above 20 require `denseInference.enabled`. The JSON declares
memory budget, fallback batch power, checkpoints, optional expansion, runtime budget and stability
tolerances. Bulk inference is used when its conservative estimate fits. Otherwise vectorized
batches retain float64 arrays on disk. Scoring evaluates selected output models plus a declared
number of strong disagreement members, rather than every rejected tournament estimator.

The v2 tasks search at least 2^24 points per family and compare 2^20/2^22/2^24 prefixes. Expansion
to 2^26 is automatic only when measured projected runtime fits the budget and basin/objective
structure remains unstable. `dense_manifest.json` defines the valid array prefix, output columns
and exclusion bits. Optional expansion reserves sparse capacity; unused rows are not observations.
`search_convergence.json` records actual evaluated counts and the expansion decision.

All eligible points enter exact Pareto filtering, including prediction ties, using moocore for
large arrays. There is no objective quantization or sampled dominance approximation. Only the
KMeans stability diagnostic samples up to 10,000 frontier points; final basin selection uses the
complete frontier. Reliability thresholds and hard/soft/unresolved semantics are unchanged.

### Same-base runtime ablations

`proposal.counterfactual` maps any declared parameter names to fixed values. For example, setting
one delta parameter to zero creates the exact original residual at that coefficient path while
preserving all other coordinates. No fixed parameter index or family name is embedded in the
counterfactual generator. Each proposal records full and counterpart functions, their predicted
responses and predicted log marginal effects. Prediction is development evidence only.

The study selects a small Pareto tradeoff set across family proposals. Its JSON controls the total
budget, `preserveBasins` and `minimumRoles`; unresolved outputs cannot become a hidden hard veto.
Bounded deterministic combination selection preserves family/basin coverage and declared consensus
or exploration roles before comparing qualified objective extrema. A second cross-family ranking
cannot silently discard all representatives of an existing family Pareto basin. Benchmark preparation
includes the selected nonzero policies, automatic same-base counterparts, OFF and the declared 4D
reference. `collection.json` records matched counterpart IDs. The collector emits
`same_base_forks.tsv`, `same_base_workloads.tsv` and `same_base_classes.tsv`, preserving block
matching and both observed minima. Identical counterpart functions are deduplicated with an
explicit alias record. Scarce and plentiful outcomes remain separate.

The runner emits opaque coefficient/config artifacts. Java runtime policy, participation, evidence
aging, timing bounds and production defaults are not changed. Final inference and support checks
use the existing runtime transform/round/clamp semantics. JMH remains a separately reviewed step.
