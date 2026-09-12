# CACHE joint 5D tuning: offline findings and benchmark handoff

## Recommendation

**Park-body first.** Its measured signed screen established a useful scarcity/plentiful tradeoff
axis; the next round should explore that axis jointly with the four base coordinates. Python
selected four park-body proposals and three secondary park-PHR x body proposals. Run the primary
252-fork stage after review; keep the secondary 216-fork stage prepared and review the primary
result before deciding whether to spend that budget.

The controlled screen completed its identifying purpose. It did not need to repair the scheduler
while holding four coefficients at 7931. Its material plentiful endpoint losses remain measured
evidence, but do not reject the full joint 5D families. This note supersedes the prior results
report's conditional next-step interpretation; its measured tables remain unchanged.

The new tournaments do **not** show uniform held-theta superiority of 5D over the same-evidence
4D ablations. Retrospective search performance is mixed and park-body does not beat random
historical selection on the reported hit rate. This batch is bounded joint-tuning exploration,
not a surrogate-certified improvement. No model prediction is a production safety verdict.

No JMH ran. No runtime/participation/CACHE semantics, bounds, defaults or runtime terms changed.
Exactly two independent hypotheses advance: park body (`/parkCoefficients/3`) and park PHR x body
(`/parkCoefficients/6`). No half-life/body family, sixth coefficient, combined extension,
contention term or dynamic campaign is included.

## Center, bounds and evidence

Both tasks use **exact sample 7931 as the current 4D development center**, as requested. Its active
four coordinates are
`[1.539667508421986, -1.1628579390224267, -0.6064873212242595, 0.21406430608786822]`.
The fifth delta is zero at that center and adds to the exact frozen residual; every inactive
coefficient remains bit-for-bit unchanged. The original live-25 center remains a measured
benchmark control. Neither center is claimed to be a universally best policy.

The existing search box is unchanged:

| Parameter                          |                Lower |               Upper |
|------------------------------------|---------------------:|--------------------:|
| parkIntercept                      |    0.908719344891745 |  1.8873401778520855 |
| parkPhrSlope                       |  -1.1863281117927396 | -0.5711950167890968 |
| half-life intercept (`hIntercept`) |  -1.1696858671949082 | -0.5631820842049557 |
| half-life PHR slope (`hPhrSlope`)  |  0.20274555031378416 | 0.42108691219016703 |
| added park-term delta              | -0.30010459245033816 | 0.30010459245033816 |

Each compatible family dataset contains **28 measured theta, 72 policy/block response panels,
1,176 live forks and 204 OFF forks**, from six campaigns. The families share the historical
4D plane; their fork counts must not be added as if disjoint evidence. Of the 28 theta, 26 are
on the zero-fifth plane and two are that family's independently measured signed endpoints.
All repeated centers, slow forks, nested windows and campaign-qualified identities remain.

The live fork inventory per family is: 612 automatic-tuning, 108 measured-proposal, 288 round5,
24 R23-confirmation, 48 topology-confirmation and 96 controlled-screen observations. Exact
compatibility excludes other live-function families, including live-12 and the other added
coefficient paths. Fixed timing is a matched control, not a fake 5D input vector.

OFF matching is same campaign/workload/block for every live fork. Same-campaign 7931 matching is
available for 384 forks (round5 and the screen); earlier observations retain OFF returns without
invented 7931-relative labels. The original live-25 reference forks and their provenance remain
in the data. This preserves the user-selected development center without borrowing measurements
across campaigns.

Each task retains 48 response slots: 24 workload/class outputs for OFF and 24 for the 7931 center.
Missing responses stay missing. Nonzero fifth evidence covers nine scarce workloads, three
individual plentiful guardrails, and three complete scarce aggregates. It does not cover any
complete plentiful aggregate. The full 21-column quadratic design has rank 17 because the fifth
coordinate varies at one 4D location. That is a reason to measure joint movement, not to stop.

The unchanged archive is `experiments/cache-run-data.tsv`, SHA-256
`42dc5cb78090553a3cb3a2cee1438e908589225257d23715b5ad06e90248b53c`.
Each tournament has `dataset.json`, `dataset_manifest.json` and `historical_audit.tsv` recording
inclusions, exclusions, original windows and input hashes.

## Paired tournaments

Four JSON tasks ran through the generic training runner: one full 5D and one same-evidence 4D
ablation for each family. Each tested **80 configurations across 14 registry families**, with
linear/ridge/elastic, quadratic and cubic regularization, local/nearest models, RBF/Matern kernels,
RBF/Matern/ARD Gaussian processes, forests/extra trees, gradient boosting, XGBoost, CatBoost,
small MLPs, and per-output ensembles. No runtime coefficients or model weights were chosen manually.

The ablation keeps the five-coordinate theta/group identity and removes only the fifth model
input. Exact data bytes, observed targets, row ordering, four outer held-theta folds, three inner
folds, scoring and campaign-transfer memberships were verified identical within each pair.
Repeated theta never crosses a primary train/held boundary through another campaign or block.

Final choices use inner CV; outer leaderboards are descriptive. Ranking/regret, RMSE/MAE,
Spearman correlation and top-k recall are retained per output. Training error does not select a
winner. The best fixed-model leaderboard entry is not an unbiased estimate of selecting that
entry after examining 80 models.

| Family        | Outputs with lower 5D regret | Higher | Tied | Final single / weighted ensemble / mean ensemble | Full-fit failures |
|---------------|-----------------------------:|-------:|-----:|-------------------------------------------------:|------------------:|
| park-body     |                           13 |     14 |   21 |                                      35 / 11 / 2 |                 0 |
| park-phr-body |                           12 |     13 |   23 |                                       37 / 9 / 2 |                 0 |

These output counts are diagnostics, not a new global scheduler objective. Each ablation recorded
four CatBoost configuration failures on a training subset whose retained four inputs were
constant. The failures remain visible; partially failing models cannot win by dropping difficult
rows. Other model families remain available, and no measurement was excluded. All 14 full-5D
families completed without fit failures.

Selected examples below show nested held-theta regret and rank correlation (5D / ablated 4D).
Regret is in log-return units, not percent throughput.

| Family        | Output         | Regret 5D / 4D | Spearman 5D / 4D | Top-2 recall 5D / 4D |
|---------------|----------------|---------------:|-----------------:|---------------------:|
| park-body     | off:R7_scarce  |  0.301 / 0.301 |  -0.345 / -0.214 |        0.000 / 0.000 |
| park-body     | off:R15_scarce |  0.032 / 0.000 |  -0.169 / -0.167 |        0.000 / 0.500 |
| park-body     | off:R23_scarce |  0.057 / 0.057 |  -0.184 / -0.181 |        0.000 / 0.000 |
| park-body     | off:R7_S7_W0   |  0.000 / 0.000 |    0.554 / 0.585 |        0.500 / 0.500 |
| park-body     | off:R15_S1_W96 |  0.111 / 0.050 |    0.451 / 0.236 |        0.000 / 0.000 |
| park-phr-body | off:R7_scarce  |  0.301 / 0.301 |  -0.332 / -0.359 |        0.000 / 0.000 |
| park-phr-body | off:R15_scarce |  0.032 / 0.065 |    0.086 / 0.071 |        0.000 / 0.500 |
| park-phr-body | off:R23_scarce |  0.057 / 0.057 |  -0.256 / -0.044 |        0.000 / 0.000 |
| park-phr-body | off:R7_S7_W0   |  0.142 / 0.000 |    0.454 / 0.663 |        0.500 / 0.500 |
| park-phr-body | off:R15_S1_W96 |  0.111 / 0.050 |    0.292 / 0.355 |        0.000 / 0.000 |

All 96 output/family comparisons, including center-relative returns and RMSE/MAE, are in
[paired_5d_vs_4d.tsv](../../../../../experiments/cache-live25-joint5d-v1/comparison/paired_5d_vs_4d.tsv).
The
[best held fixed model per output](../../../../../experiments/cache-live25-joint5d-v1/comparison/best_fixed_model_by_output.tsv)
and
[actual final selections](../../../../../experiments/cache-live25-joint5d-v1/comparison/selected_models.tsv)
are separate artifacts.
For example, final OFF R7 scarcity uses a small MLP, R15 scarcity and R15/S1/W96 use nearest
neighbors, R23 scarcity uses a random forest, R7/S7/W0 uses extra trees, and center-relative
scarce aggregates use quadratic ridge. This does not give those outputs equal reliability.

The endpoint-only held diagnostic has only two nonzero theta per family. The full park-body
model orders the two endpoints correctly for R23 scarcity and R7/S7/W0 where its ablation does
not; both miss the ordering for R7/R15 scarcity. The interaction model recovers R23 endpoint
ordering but still misses R7/R15 scarcity and R7/S7/W0. These small conditional tests do not
establish the whole 5D surface. See
[nonzero endpoint metrics](../../../../../experiments/cache-live25-joint5d-v1/comparison/nonzero_endpoint_held_metrics.tsv).

Six leave-one-campaign-out diagnostics ran for every tournament. They explicitly permit shared
reference theta as a secondary diagnostic. Holding the controlled screen out removes all
nonzero fifth training evidence. Full-model OFF scarcity RMSE for that held screen is
0.054/0.123/0.063 at R7/R15/R23 for park-body and 0.060/0.124/0.064 for park-PHR x body.
R7/S7/W0 RMSE is 0.115 and 0.134 despite useful within-screen ranking for that output. This
supports caution about absolute calibration. Full per-output transfer and shared-theta coverage
are
in [campaign_transfer_comparison.tsv](../../../../../experiments/cache-live25-joint5d-v1/comparison/campaign_transfer_comparison.tsv).

## Reliability and search efficiency

Reliability thresholds, floors and soft-risk rules are unchanged. No output qualified as HARD.
Park-body has **22 SOFT / 26 UNRESOLVED** outputs; park-PHR x body has **26 SOFT / 22 UNRESOLVED**.
R7/S7/W0 and R15/S1/W96 provide SOFT direction/risk. R7/S7/W96 and R15/S15/W0 remain UNRESOLVED.
Full per-output classes and checks are
in [output_reliability.tsv](../../../../../experiments/cache-live25-joint5d-v1/comparison/output_reliability.tsv).

Both searches use the unchanged 5D bounds and the same scrambled Sobol seed 20260911, power 13:
**8,192 points**. The minimum normalized measured distance is 0.10; proposal separation is 0.15.
Both exclude 48 points for measured-history proximity and retain 8,144 eligible points. No
candidate was rejected by a weak guardrail forecast or support/clamp failure. Pareto construction
retains **640 park-body points** and **763 park-PHR x body points**, each clustered into two
computational tradeoff regions. These clusters are not measured physical scheduler regimes.

The historical retrospective does not establish a general search-efficiency win:

| Family        | Method            | Useful hit rate | Median selected scarce change | Worst plentiful outcome | Useful retained |
|---------------|-------------------|----------------:|------------------------------:|------------------------:|----------------:|
| park-body     | blind_random      |          24.16% |                        +3.03% |                 -18.05% |           7 / 7 |
| park-body     | old_hard_floor    |            none |                          none |                    none |           0 / 7 |
| park-body     | reliability_aware |          12.50% |                        +2.32% |                 -16.13% |           6 / 7 |
| park-phr-body | blind_random      |          24.25% |                        +3.06% |                 -18.13% |           7 / 7 |
| park-phr-body | old_hard_floor    |            none |                          none |                    none |           0 / 7 |
| park-phr-body | reliability_aware |          25.00% |                        +2.90% |                 -19.23% |           6 / 7 |

The old hard-floor policy again selects nothing. Reliability-aware retrospective filtering
retains 26/28 historical theta and 6/7 useful theta per family; both remove only 2/27 points
classified as measured-bad by those diagnostic floors. Useful scarcity and measured guardrail
failure may overlap. Random rows are averages over 1,000 seeded trials, not new measurements.
The dense Pareto reduction is computational narrowing, not proof that discarded unmeasured
points are poor. Do not sell either tournament as a throughput oracle.

## Automatically selected joint proposals

All seven points move every base coordinate plus the added coordinate. None is a hand-picked
weight, a repeat of a measured theta or a repeat of the one-anchor signed screen. Exact vectors,
all 14 runtime coefficients and per-model predictions are preserved in each `proposals.json`
and benchmark policy config.

The table shows predicted OFF scarce aggregates and the most problematic plentiful no-op output.
R7 and R23 aggregate predictions are UNRESOLVED, not evidence of safety or improvement. R7's
nearly constant forecast is especially uninformative. The R7/S7/W0 forecasts are SOFT risks;
losses below the nominal floor remain eligible for measurement.

| Family / sample      | Role               | Basin | OFF scarce R7 / R15 / R23 | OFF R7/S7/W0 | Max model spread (log) | Nearest measured distance |
|----------------------|--------------------|------:|--------------------------:|-------------:|-----------------------:|--------------------------:|
| park-body / 3381     | basin_consensus    |     0 |  +2.00% / +4.65% / +5.32% |       -8.39% |                 0.0266 |                    0.2635 |
| park-body / 611      | basin_consensus    |     1 |  +2.00% / +5.05% / +7.11% |       -4.12% |                 0.0389 |                    0.4581 |
| park-body / 274      | broad_scarcity     |     0 |  +2.00% / +3.08% / +4.68% |       -5.68% |                 0.0622 |                    0.5889 |
| park-body / 4598     | model_disagreement |     0 |  +2.00% / +2.45% / +3.47% |       -5.48% |                 0.1795 |                    0.6892 |
| park-phr-body / 2531 | basin_consensus    |     0 |  +1.93% / +5.05% / +6.35% |       -8.43% |                 0.0284 |                    0.4053 |
| park-phr-body / 1812 | basin_consensus    |     1 |  +1.93% / +2.55% / +4.77% |       -6.60% |                 0.0273 |                    0.3363 |
| park-phr-body / 6756 | model_disagreement |     0 |  +1.93% / +0.58% / +5.52% |       -5.29% |                 0.1670 |                    0.7315 |

Center-relative predictions are separate, and can disagree with the apparent OFF benefit:

| Family / sample      | Predicted vs 7931 scarce R7 / R15 / R23 | Predicted OFF plentiful R7 / R15 / R23 | Nearest measured policy                         |
|----------------------|----------------------------------------:|---------------------------------------:|-------------------------------------------------|
| park-body / 3381     |                -4.94% / -0.07% / -0.61% |               -3.09% / -1.47% / -0.77% | live25-auto-sample-0006                         |
| park-body / 611      |                +2.45% / +2.32% / -0.42% |               -2.67% / -1.56% / -0.75% | live25-auto-sample-0008                         |
| park-body / 274      |                -7.93% / +8.35% / +1.91% |               -1.64% / -1.29% / -0.61% | live25-region-coverage-round5-round5-sample6321 |
| park-body / 4598     |               -10.15% / +2.97% / +1.68% |               -1.09% / -1.03% / -0.41% | live25-auto-sample-0001                         |
| park-phr-body / 2531 |                -0.35% / +1.62% / -0.15% |               -4.19% / -1.56% / -0.81% | live25-auto-sample-0008                         |
| park-phr-body / 1812 |                -1.88% / +1.51% / -0.07% |               -2.07% / -1.40% / -0.87% | live25-auto-sample-0004                         |
| park-phr-body / 6756 |               +11.88% / +5.58% / +1.69% |               -1.40% / -1.31% / -0.50% | live25-region-coverage-round5-round5-sample136  |

These plentiful aggregates have no nonzero-fifth measured coverage and must not conceal the
individual R7/S7/W0 risk. The complete 48-output consensus/disagreement records retain model
members, weights, per-model forecasts, soft penalties, direction sources and unresolved annotations.
Disagreement is model spread, not a confidence interval. The primary sample 4598 and secondary
6756 deliberately explore disagreement; 6756 also forecasts -8.67% at R15/S1/W96 and is not a
consensus improvement claim. No proposal is accepted into production by these forecasts.

[Exact theta and nearest references](../../../../../experiments/cache-live25-joint5d-v1/comparison/proposed_theta.tsv)
includes all five
coordinates without rounding them for reuse. Sample numbers are indexes into the seeded pool,
not manually assigned timing values.

## Prepared measurement and next decision

The [runner handoff](../cache-timing/joint5d-v1/HANDOFF.md) stages park-body first:
**four proposals + OFF + original live-25 + 7931 = seven arms x 18 workloads x two blocks =
252 JVM forks**. The secondary interaction handoff is three proposals plus the same controls, **216
forks**, and requires a separate review after the primary result. Both together would
be 468 forks; no unbounded loop or automatic secondary execution is configured.

Every stage covers R7/R15/R23, S1 and S=R, and W0/W96/W576 using the established CPU placements.
This fills the previously missing plentiful body/topology panel. Standard throughput is the
result; retain every independent fork, every nested window and both observed minima. Compare
scarcity/body breadth and plentiful harm against same-campaign OFF and both measured 4D controls.
A better achievable tradeoff justifies continued tuning even before final production readiness.

After measurement and review: consolidate evidence, rerun these JSON tasks and paired ablations
under a new revision, preserve distinct useful basins, then consider tuning or zooming. Do not
widen the bounds now, combine added terms, deploy endpoint coefficients or advance to dynamics.

## Reproduction and validation

The four tasks are in `python/pareto-weight-calibration/tasks/live25-joint5d-v1/`:
`park-body.json`, `park-body-4d-ablation.json`, `park-phr-body.json`, and
`park-phr-body-4d-ablation.json`. The generic entrypoint is:

```bash
export PYTHONPATH="$PWD/python/pareto-weight-calibration/src"
/tmp/euhedral-round5-analysis-mise-venv/bin/python -m pareto_weight_calibration.training_runner \
  --task python/pareto-weight-calibration/tasks/live25-joint5d-v1/park-body.json \
  --output-dir /tmp/new-joint5d-park-body-fit
```

Use a fresh output directory. All dataset, models, grids, grouping, reliability, search and output
choices are JSON. No shared trainer implementation or runtime change was needed. Two legacy
regression tests now resolve consolidated historical inputs through temporary/archive paths,
without restoring old presets to the repository.

Validation passed: **41 relevant Python tests**, with the removed old-screen Java-artifact test
deselected; exact Java config/shared-export parity was instead run for the newly selected policies
and center through Mise. Paired data/target/fold equality, fresh model serialization/reload,
seeded Sobol reconstruction, all-five-coordinate movement, exact inactive coefficients, measured
and proposal distances, all output locks, source identity and topology checks passed.
`git diff --check` passed. No Gradle build was needed for unchanged Java sources; no JMH ran.

Both family directories retain dataset audits, run/package/source identities, fitted model
artifacts, validation, 48-output leaderboards, ensemble results, dense predictions, Pareto
frontiers, basins, selected policies, benchmark configs and locks. Comparison artifacts and
[validation checks](../../../../../experiments/cache-live25-joint5d-v1/comparison/validation_checks.json)
are retained alongside them.
