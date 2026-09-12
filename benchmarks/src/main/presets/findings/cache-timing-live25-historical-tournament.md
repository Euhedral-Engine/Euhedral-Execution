# CACHE live-25 historical surrogate tournament: findings and next steps

The full tournament now runs from JSON through the generic Python training runner. It used all
identified compatible CACHE history, completed 80 model configurations across 14 families, evaluated
nested held-theta selection and campaign transfer, and searched 8,192 automatically generated
parameter vectors. No model fit failed. No Java/runtime code, participation logic, defaults, or
coefficients were manually changed.

**No benchmark proposal passed the declared prediction guardrails.** The selected R7 plentiful/no-op
model predicts approximately -6.263% versus OFF throughout the search box, below the JSON floor of
-3%. Thus the eligible set, constrained Pareto frontier, and proposed batch are all empty. This is a
model-guided readiness result, not proof that the family failed or that every actual parameter
vector has that regression. The model is nearly constant over this box; its inner RMSE is 0.09970
log units and rank correlation is -0.03684. Those diagnostics do not support treating its
pessimistic forecast as a demonstrated scheduler law.

The global quadratic is not a uniformly adequate model. In the descriptive held-theta fixed-model
leaderboard, the best nonlinear/local configuration has lower action regret than the best quadratic
in 37 of 48 outputs and ties in 11. Its RMSE is lower in 38 outputs. These are comparisons after
inspecting the leaderboard, not unbiased estimates of choosing those winners. The honest nested
selection results remain mixed, especially for R7 scarcity. More data has not established that the
previous quadratic can reliably direct the next benchmark.

## Consolidation and evidence audit

All 26 historical CACHE run/evidence/analysis/fit directories were consolidated
into [one TSV](../../../../../experiments/cache-run-data.tsv), verified against every original byte,
and removed. The TSV contains 2,130 queryable run rows, 11,150 nested window values, and lossless
copies of 4,359 original artifacts. Its SHA-256 is
`de4b278ebd38204034acc958a7e64f1d6020fe0a225469b74611d4b2adb98196`. Source, frozen benchmark
definitions, existing findings and unrelated Pareto experiments remain. Original artifact paths in
earlier findings are archive keys and can be restored temporarily; see
the [workflow documentation](../../../../../python/pareto-weight-calibration/HISTORICAL_TOURNAMENT.md).

Twelve historical campaigns were discovered. Four contribute 792 compatible live forks plus 144
matched OFF forks (936 total); 1,194 other run records remain archived and excluded. The model
contains 19 distinct theta: the exact center, 16 Sobol vectors and measured proposals 2997/3853.
Repeats remain as 48 campaign-qualified policy/block panels, referencing original forks. They were
not merged into one averaged point before validation.

| Campaign                                | Included forks, including OFF | Excluded run records | Reason                                                                                                                           |
|-----------------------------------------|------------------------------:|---------------------:|----------------------------------------------------------------------------------------------------------------------------------|
| cache-park-auto-validation              |                             0 |                   72 | Fixed timing evidence; no live-25 parameter vector.                                                                              |
| cache-park-coarse                       |                             0 |                    6 | Fixed timing evidence; no live-25 parameter vector.                                                                              |
| cache-park-confirmation                 |                             0 |                   16 | Fixed timing evidence; no live-25 parameter vector.                                                                              |
| cache-park-stability                    |                             0 |                   20 | Fixed timing evidence; no live-25 parameter vector.                                                                              |
| cache-park-throughput                   |                             0 |                   20 | Fixed timing evidence; no live-25 parameter vector.                                                                              |
| cache-park-zoom                         |                             0 |                   16 | Fixed timing evidence; no live-25 parameter vector.                                                                              |
| cache-timing-confirmation-v1-r23        |                            48 |                   24 | Center + OFF retained; live-12 has incompatible inactive coefficients/shape.                                                     |
| cache-timing-confirmation-v1-topologies |                            96 |                   48 | Center + OFF retained; live-12 has incompatible inactive coefficients/shape.                                                     |
| cache-timing-fixed-surface              |                             0 |                  288 | Fixed timing evidence; no live-25 parameter vector.                                                                              |
| cache-timing-live-v1                    |                             0 |                  684 | Old throughput-only JVM mode differs; S17 is outside this panel; other functions/fixed arms are not exact compatible 4D vectors. |
| cache-timing-live25-auto-proposal-v1    |                           144 |                    0 | Exact center + measured 2997/3853 + OFF; full panel.                                                                             |
| cache-timing-live25-auto-v2             |                           648 |                    0 | Exact center + 16 Sobol policies + OFF; full 18-workload panel.                                                                  |

The original live-screen center is present in the archive but excluded from fitting because that
campaign lacks the required `throughputOnly=true` mode. Including it as a compatible original center
would override a real fixture difference. The exact center observations from both confirmations and
both tuning campaigns are included. Full per-policy reasons, theta, workload coverage and
same-campaign control availability are in
the [inclusion audit](../../../../../experiments/surrogate-live25-round3/historical_audit.tsv).

Compatibility checks verify exact active-coordinate meaning and inactive coefficients, transforms,
timing bounds and rounding, Java source identities with two explicitly reviewed historical hash
exceptions, CPU/source/work fixtures, JMH schedule/options/headers, raw trial/log checksums, all
retained windows and their recomputed means. The frozen participation implementation is preserved.
Earlier failed proposal predictions and all original source/build identities remain recoverable from
the TSV.

Each response system retains 6 topology/class aggregates and 18 individual workload outputs, for
**48 targets: 24 center-relative and 24 OFF-relative**. A target is the mean log return across its
declared complete workload panel within one campaign/block. Partial historical panels remain
missing, not imputed. Matching uses the same campaign/workload/block; no center or OFF is borrowed
across campaigns. Individual throughput, matched IDs, fork mean, all windows and provenance remain
in the [combined dataset](../../../../../experiments/surrogate-live25-round3/dataset.json).

## Validation and model comparison

The four outer folds hold every repeat of an exact theta together, including the center across
campaigns. Each has three inner folds for model, grid and ensemble selection. Final models use
separate full-development inner validation. Scaling and polynomial fitting happen inside the
training fold. Every fork is retained; five windows are never treated as five independent trials.
The old proposal misses and very large valid R7/S1/W96 changes remain untrimmed.

The 80 configurations cover mean and linear baselines; ridge, elastic net, quadratic and strongly
regularized cubic; RBF/Matern kernel ridge; nearest neighbors and local linear regression;
RBF/Matern 3/2/Matern 5/2 Gaussian processes with scalar and ARD length scales; random forest, extra
trees, gradient boosting, XGBoost, CatBoost; and small MLPs. Native multi-output and independent
output modes both compete. Exact grids and seeds are in
the [JSON task](../../../../../python/pareto-weight-calibration/tasks/live25-historical-tournament.json)
and [expanded registry](../../../../../experiments/surrogate-live25-round3/model_registry.json).

RMSE and regret below are log-return units, not throughput percentages. Regret compares measured
theta means with the measured consequences of the OOF-predicted best action; it is a selection
diagnostic, not a new benchmark. The fixed-model leaderboard is descriptive. The nested result
measures the model/ensemble selection procedure without choosing on its outer-held responses.

| Output               | Nested RMSE | Nested regret | Best fixed-model family (descriptive) | Its regret | Best quadratic regret | Mean baseline regret |
|----------------------|------------:|--------------:|---------------------------------------|-----------:|----------------------:|---------------------:|
| center:R7_scarce     |     0.12988 |       0.28518 | extra_trees (degree 1)                |    0.28518 |               0.28518 |              0.32443 |
| center:R15_scarce    |     0.02455 |       0.03151 | ridge (degree 1)                      |    0.01259 |               0.02628 |              0.03355 |
| center:R23_scarce    |     0.03594 |       0.00000 | mlp (degree 1)                        |    0.00000 |               0.00964 |              0.04066 |
| center:R7_plentiful  |     0.02579 |       0.08420 | extra_trees (degree 1)                |    0.00000 |               0.02131 |              0.04921 |
| center:R15_plentiful |     0.01844 |       0.02408 | mlp (degree 1)                        |    0.00417 |               0.00929 |              0.02354 |
| center:R23_plentiful |     0.01469 |       0.02675 | gradient_boosting (degree 1)          |    0.00268 |               0.00268 |              0.02006 |
| off:R7_scarce        |     0.12162 |       0.36709 | extra_trees (degree 1)                |    0.28518 |               0.36709 |              0.31372 |
| off:R15_scarce       |     0.02572 |       0.01858 | ridge (degree 1)                      |    0.01259 |               0.01259 |              0.03355 |
| off:R23_scarce       |     0.05646 |       0.02920 | catboost (degree 1)                   |    0.00526 |               0.01490 |              0.04592 |
| off:R7_plentiful     |     0.04807 |       0.05358 | random_forest (degree 1)              |    0.00000 |               0.00064 |              0.07353 |
| off:R15_plentiful    |     0.02037 |       0.02408 | mlp (degree 1)                        |    0.00417 |               0.00929 |              0.02488 |
| off:R23_plentiful    |     0.01413 |       0.01049 | ridge (degree 3)                      |    0.00268 |               0.00268 |              0.01499 |

The two R7 scarcity systems remain difficult: nested regret is 0.28520 relative to center and
0.36712 relative to OFF. A flexible family winning an individual output does not establish regime
separation as the cause of the large workload jumps. The archive retains those jumps for further
offline review; no outlier filter was
used. [Full held-theta metrics](../../../../../experiments/surrogate-live25-round3/held_theta_metrics.tsv)
also report MAE, Spearman correlation, top-k recall and
coverage; [quadratic comparison](../../../../../experiments/surrogate-live25-round3/quadratic_comparison.tsv)
preserves the prior model class explicitly.

## Ensembles and campaign transfer

Final full-development selection chooses 32 single models, 12 validation-weighted ensembles and 4
mean ensembles across the 48 outputs. Averaged across outputs only as model-error diagnostics,
nested mean ensembles have RMSE 0.05745/regret 0.09201; validation-weighted ensembles
0.05601/0.09025; inner-selected single models 0.05707/0.08576. Allowing inner selection among them
gives 0.05658/0.08434. Weighted ensembles slightly improve average prediction error, but do not
uniformly improve action selection. These averages are not a scheduler deployment score.

| Held campaign                           | Held policy/block panels | Outputs with evidence | Shared theta with training | Mean covered-output RMSE |
|-----------------------------------------|-------------------------:|----------------------:|---------------------------:|-------------------------:|
| cache-timing-confirmation-v1-r23        |                        4 |                    16 |                          1 |                  0.02698 |
| cache-timing-confirmation-v1-topologies |                        4 |                    32 |                          1 |                  0.03039 |
| cache-timing-live25-auto-proposal-v1    |                        6 |                    48 |                          1 |                  0.07786 |
| cache-timing-live25-auto-v2             |                       34 |                    48 |                          1 |                  0.06587 |

Each campaign transfer fold shares only the repeated center theta; this is deliberately a secondary
diagnostic, not primary held-theta validation. Confirmation campaigns cover only their observed
topology outputs. Holding out the initial Sobol campaign leaves very few distinct training vectors;
its 0.06587 RMSE cannot establish broad campaign robustness. The proposal campaign has 0.07786 RMSE.
Coverage, row memberships and per-output results are retained
in [campaign transfer](../../../../../experiments/surrogate-live25-round3/campaign_transfer.tsv).

## Search outcome and next steps

The unchanged four-dimensional local box was searched with 8,192 scrambled Sobol points (seed
20260911). Sixty-two were too near an already measured theta (normalized distance <0.10). No point
was removed by the support clamp filter. The remaining 8,130 failed at least one declared prediction
floor. All 8,192 predict the R7/S7/W0 OFF response in the narrow range -6.26368% to -6.26317%, so
its -3% floor alone blocks the batch. Other workload constraints also fail; they remain visible in
all 48 predictions for each point.

No floors were relaxed, no model was substituted after inspecting this failure, and no theta was
manually chosen.
The [proposal manifest](../../../../../experiments/surrogate-live25-round3/proposals.json) contains
zero selected points and zero active basins.
The [Pareto frontier](../../../../../experiments/surrogate-live25-round3/pareto_frontier.tsv) is
empty because it is constrained; this does not claim there is no unconstrained mathematical
frontier. Model disagreement membership and all predictions remain
in [consensus](../../../../../experiments/surrogate-live25-round3/model_consensus.tsv)
and [dense search](../../../../../experiments/surrogate-live25-round3/dense_predictions.tsv). The
synthetic tests cover multi-region retention, but this real run has no eligible regions to retain.

1. Review model reliability for OFF R7/S7/W0 before authorizing another JVM round. The selected
   nearly constant MLP is a weak absolute-response gate; inspect its held errors against mean/linear
   diagnostics and measured campaign variation. Do not mistake its -6.263% prediction for a proven
   domain-wide regression.
2. If refining offline selection, declare the change in JSON and a new output revision. A useful
   next offline comparison is an explicit prediction-error/mean-baseline eligibility requirement for
   models used as guardrails, alongside action regret for scarcity. Treat the current results as
   development evidence once used to redesign that criterion. Do not secretly relax guardrails
   merely to force a batch.
3. Re-evaluate center-relative direction and OFF-relative usefulness together. Keep individual
   body/topology predictions visible, including the failed 2997/3853 regions. Preserve the current
   runtime family and bounds; no evidence here justifies adding runtime terms or returning to a
   policy tournament.
4. Emit a small diverse benchmark batch only if a reviewed offline selection produces candidates
   with acceptable predicted tradeoffs and disclosed disagreement. A future measured run is still
   required to confirm any prediction. There is currently no new executable JMH handoff and no
   recommended deployable winner.

## Reproduction and verification

From the repository root:

```bash
export PYTHONPATH="$PWD/python/pareto-weight-calibration/src"
/tmp/euhedral-surrogate-tournament-venv/bin/python -m pareto_weight_calibration.training_runner \
  --task python/pareto-weight-calibration/tasks/live25-historical-tournament.json \
  --output-dir /tmp/live25-historical-review
```

The full task is JSON-driven, with no experiment-specific Python edits between data ingestion,
tournament, fit and proposal search. The CACHE support/evidence adapters describe the runtime
artifact format; they contain no live-25-specific four-column tuner. Model implementations use the
generic registry. The task JSON, exported schema, data hashes, fitted-model reload check, package
versions, model selections, validation memberships, predictions and file lock are retained
in [the output directory](../../../../../experiments/surrogate-live25-round3).

Validation: 11 new focused tests passed. The relevant trainer/archive/tuning suite passed 53 tests
with 2 skips and 2 explicitly deselected historical artifact tests. The broader suite finished with
369 passed, 12 skipped and 13 failed: 12 require pre-existing missing Pareto artifacts
(`pareto_training_step5`, `pareto_integer_cutoff_evaluation` or `pareto_direct_side_training`); one
historical proposal test rejects the intentional Python source changes against its frozen source
lock. The old lock was preserved. The archive-dependent CACHE policy tests now restore evidence
temporarily and pass. No Gradle was needed because this task changed no runtime or generated Java.
No JMH trials ran. `git diff --check` passed. Pre-existing Java and parameter-tuning test edits were
preserved.

## Per-output descriptive leaderboard and final model selection

The left model is the best fixed configuration by outer OOF regret then RMSE, shown descriptively.
The final model on the right was selected separately by full-development inner validation. Ensemble
weights are in `selection.json`; they were fitted from validation metrics, not hand-chosen.

| Output               | Best fixed configuration      | Held regret | Held RMSE | Final inner-selected model(s)                                                                         |
|----------------------|-------------------------------|------------:|----------:|-------------------------------------------------------------------------------------------------------|
| center:R7_scarce     | extra_multi-38e321e1e6        |     0.28518 |   0.11154 | single: neighbors-19f172e340                                                                          |
| center:R15_scarce    | linear_ridge-3d63895060       |     0.01259 |   0.02317 | single: forest_separate-04943d4262                                                                    |
| center:R23_scarce    | small_mlp-858ab48aa3          |     0.00000 |   0.03668 | single: previous_quadratic-be3dcc4cd8                                                                 |
| center:R7_plentiful  | extra_multi-2c03e795af        |     0.00000 |   0.02453 | validation_weighted: matern_kernel-887cae8cb4, small_mlp-c36613dc24, local_linear-9e664afe36          |
| center:R15_plentiful | small_mlp-7f40877664          |     0.00417 |   0.02067 | validation_weighted: small_mlp-7f40877664, rbf_kernel-ba2d04a853, local_linear-8544206242             |
| center:R23_plentiful | gradient-a0f291ab1a           |     0.00268 |   0.01357 | validation_weighted: gaussian_process-606b4aefcb, linear_ridge-3d63895060, extra_multi-e11e37708a     |
| center:R7_S1_W0      | gaussian_process-02b72102b7   |     0.00000 |   0.10021 | single: local_linear-55d9a1c016                                                                       |
| center:R7_S1_W96     | extra_multi-38e321e1e6        |     0.84008 |   0.28343 | single: neighbors-19f172e340                                                                          |
| center:R7_S1_W576    | linear_ridge-3d63895060       |     0.00000 |   0.01210 | single: small_mlp-c36613dc24                                                                          |
| center:R7_S7_W0      | matern_kernel-887cae8cb4      |     0.00000 |   0.06191 | validation_weighted: matern_kernel-e673403e1c, local_linear-55d9a1c016, elastic-31d995e253            |
| center:R7_S7_W96     | gradient-dc6622ee43           |     0.00381 |   0.05479 | single: xgb-2bc6c02dc3                                                                                |
| center:R7_S7_W576    | small_mlp-7edeeaf1c3          |     0.00216 |   0.00915 | single: small_mlp-7f40877664                                                                          |
| center:R15_S1_W0     | previous_quadratic-08feb6e6ef |     0.01631 |   0.03735 | single: gaussian_process-8b8b4ac944                                                                   |
| center:R15_S1_W96    | linear_ridge-3d63895060       |     0.00000 |   0.07003 | validation_weighted: neighbors-19f172e340, rbf_kernel-5e425f6616, previous_quadratic-08feb6e6ef       |
| center:R15_S1_W576   | neighbors-19f172e340          |     0.00203 |   0.00392 | validation_weighted: small_mlp-c36613dc24, previous_quadratic-08feb6e6ef, gaussian_process-606b4aefcb |
| center:R15_S15_W0    | gaussian_process-6b8aeb572e   |     0.01379 |   0.03755 | single: small_mlp-858ab48aa3                                                                          |
| center:R15_S15_W96   | small_mlp-7edeeaf1c3          |     0.00040 |   0.03010 | single: rbf_kernel-5e425f6616                                                                         |
| center:R15_S15_W576  | rbf_kernel-f29fe3c204         |     0.00000 |   0.00404 | mean: small_mlp-7f40877664, extra_multi-38e321e1e6, gradient-82dc07f51b                               |
| center:R23_S1_W0     | strong_cubic-b09b219fd1       |     0.00724 |   0.05462 | mean: strong_cubic-864694bd79, small_mlp-85f75d1b09, elastic-b46f53ca6d                               |
| center:R23_S1_W96    | small_mlp-a14126eb0c          |     0.08831 |   0.06186 | single: gaussian_process-6b8aeb572e                                                                   |
| center:R23_S1_W576   | small_mlp-858ab48aa3          |     0.00000 |   0.06766 | single: cat-a04694a87f                                                                                |
| center:R23_S23_W0    | gradient-a0f291ab1a           |     0.00000 |   0.04781 | single: xgb-2bc6c02dc3                                                                                |
| center:R23_S23_W96   | small_mlp-7edeeaf1c3          |     0.00000 |   0.01599 | single: elastic-8938911217                                                                            |
| center:R23_S23_W576  | matern_kernel-f863c18b10      |     0.00000 |   0.00649 | validation_weighted: strong_cubic-b09b219fd1, small_mlp-c36613dc24, gaussian_process-b14d524907       |
| off:R7_scarce        | extra_multi-38e321e1e6        |     0.28518 |   0.10908 | validation_weighted: gaussian_process-8b8b4ac944, neighbors-c060925487, xgb-1f3f4017b5                |
| off:R15_scarce       | linear_ridge-3d63895060       |     0.01259 |   0.02512 | single: gradient-a0f291ab1a                                                                           |
| off:R23_scarce       | cat-a04694a87f                |     0.00526 |   0.05007 | single: matern_kernel-35e8131f90                                                                      |
| off:R7_plentiful     | forest_multi-0f33533a1c       |     0.00000 |   0.04101 | single: xgb-ca16debdf7                                                                                |
| off:R15_plentiful    | small_mlp-7f40877664          |     0.00417 |   0.02014 | single: small_mlp-858ab48aa3                                                                          |
| off:R23_plentiful    | strong_cubic-864694bd79       |     0.00268 |   0.01436 | single: rbf_kernel-a0170bbf79                                                                         |
| off:R7_S1_W0         | gaussian_process-02b72102b7   |     0.00000 |   0.10464 | single: strong_cubic-864694bd79                                                                       |
| off:R7_S1_W96        | extra_multi-3d07e9bc02        |     0.84008 |   0.27894 | validation_weighted: gaussian_process-8b8b4ac944, neighbors-c060925487, xgb-1f3f4017b5                |
| off:R7_S1_W576       | linear_ridge-ee2a517d90       |     0.00000 |   0.01198 | validation_weighted: gradient-82dc07f51b, forest_separate-04943d4262, xgb-0566b4f7c3                  |
| off:R7_S7_W0         | cat-a1f393ac9e                |     0.00000 |   0.08964 | single: small_mlp-85f75d1b09                                                                          |
| off:R7_S7_W96        | matern_kernel-03177c1678      |     0.06912 |   0.06568 | single: linear_ridge-3d63895060                                                                       |
| off:R7_S7_W576       | gaussian_process-6b8aeb572e   |     0.00000 |   0.00612 | single: small_mlp-7f40877664                                                                          |
| off:R15_S1_W0        | gaussian_process-8b8b4ac944   |     0.00000 |   0.04459 | validation_weighted: rbf_kernel-f29fe3c204, previous_quadratic-97e7805a0b, small_mlp-858ab48aa3       |
| off:R15_S1_W96       | xgb-0566b4f7c3                |     0.00000 |   0.07536 | mean: extra_multi-3d07e9bc02, neighbors-fcb269cd81, rbf_kernel-5e425f6616                             |
| off:R15_S1_W576      | elastic-8938911217            |     0.00158 |   0.00356 | single: cat-a1f393ac9e                                                                                |
| off:R15_S15_W0       | gaussian_process-6b8aeb572e   |     0.02421 |   0.04053 | single: gradient-dc6622ee43                                                                           |
| off:R15_S15_W96      | small_mlp-85f75d1b09          |     0.00040 |   0.02738 | single: small_mlp-7edeeaf1c3                                                                          |
| off:R15_S15_W576     | small_mlp-c36613dc24          |     0.00000 |   0.00647 | single: neighbors-4ecbbce85e                                                                          |
| off:R23_S1_W0        | strong_cubic-b09b219fd1       |     0.01379 |   0.06543 | single: elastic-b46f53ca6d                                                                            |
| off:R23_S1_W96       | small_mlp-858ab48aa3          |     0.00000 |   0.08729 | mean: previous_quadratic-be3dcc4cd8, neighbors-4ecbbce85e, gradient-82dc07f51b                        |
| off:R23_S1_W576      | rbf_kernel-5675ab47b5         |     0.00000 |   0.09741 | single: previous_quadratic-97e7805a0b                                                                 |
| off:R23_S23_W0       | previous_quadratic-d341a80c94 |     0.00000 |   0.03817 | single: linear-c7a23848f0                                                                             |
| off:R23_S23_W96      | gaussian_process-b14d524907   |     0.00000 |   0.01721 | single: small_mlp-85f75d1b09                                                                          |
| off:R23_S23_W576     | forest_multi-cb87d103be       |     0.00000 |   0.00668 | validation_weighted: elastic-8938911217, strong_cubic-b09b219fd1, small_mlp-7edeeaf1c3                |
