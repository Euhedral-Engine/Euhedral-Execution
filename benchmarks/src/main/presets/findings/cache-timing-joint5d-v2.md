# CACHE joint-5D v2: offline findings and matched proposal handoff

## Finding

**Park-body first.** The completed joint evidence supports continued bounded measurement, but
neither fifth dimension consistently improves held-theta search quality over its same-evidence 4D
ablation. The additional runtime coefficient has not yet earned deployment. The next prepared
experiment isolates that question by pairing each proposed 5D function with its exact same-base
zero-fifth function. The benchmark remains the oracle.

Python selected four new functions: two park-body and two park-PHR-body. The interaction candidates
retain distinct alternative regions for comparison; their retrospective guidance is weaker and they
are not equal-priority winners. The preparation contains no sixth term, runtime changes,
production-default changes or new JMH results. No coefficient vector was manually chosen.

## Historical evidence and compatibility

The unchanged `experiments/cache-run-data.tsv` archive contains 3,186 run/fork records, 16,430
nested windows and 6,927 original artifacts. Its SHA-256 is
`bafcc0ce64ae4d64f4187238ba460ce6c612cd4b8aa117d6e0688da10b721f52`. Both completed joint campaigns
were already consolidated there; this revision ingested them without rewriting the archive.

The audit discovered 16 campaigns and included compatible observations from eight: original R23 and
R7/R15 confirmation, automatic Sobol tuning, the measured 2997/3853 proposal campaign,
region-coverage round5 including 7931, the controlled fifth-term screen, and both completed joint-5D
campaigns. The union is **1,620 live-policy forks plus 276 OFF forks = 1,896 independent forks**,
with all **9,480 nested windows** retained.

| Dataset       | Unique theta | Nonzero fifth theta | Live forks | Shared OFF forks | Response bundles | Quadratic design rank |
|---------------|-------------:|--------------------:|-----------:|-----------------:|-----------------:|----------------------:|
| Park-body     |           32 |                   6 |      1,464 |              276 |               88 |                 21/21 |
| Park-PHR-body |           31 |                   5 |      1,428 |              276 |               86 |                 20/21 |

The families share 26 measured 4D theta and 1,272 live forks. Their zero-plane views are not
independent replication. Park-body includes its two signed screen endpoints and joint proposals
3381, 611, 274 and 4598; park-PHR-body includes its two endpoints and 2531, 1812 and 6756. An
opposite-family campaign contributes compatible 4D controls, never its incompatible nonzero
fifth-term policies.

Fixed timing runs, the earlier non-throughputOnly screen, incompatible inactive coefficients, other
families and incompatible fixture/runtime identities remain contextual or excluded with explicit
reasons. No fixed pair or multi-term policy became a fake 5D observation. Every compatible fork
keeps campaign-qualified IDs, exact theta, throughput, nested windows and matched control
identities.

All live forks have same-campaign OFF matching. Exactly 672 park-body and 636 interaction forks also
have an exact same-campaign 7931 reference. Earlier runs without that reference contribute OFF
targets only. Missing reference targets and incomplete workload aggregates remain missing; a
reference is never borrowed across campaigns. The target layout remains 24 reference-relative plus
24 OFF-relative outputs. Each response bundle retains its constituent independent forks; measurement
windows never become replicates.

See [shared inclusion audit](../../../../../experiments/cache-live25-joint5d-v2/comparison-v2/shared_inclusion_audit.tsv), [deduplicated counts](../../../../../experiments/cache-live25-joint5d-v2/comparison-v2/evidence_summary.json), [park-body dataset](../../../../../experiments/cache-live25-joint5d-v2/park-body-fit/dataset.json)
and [interaction dataset](../../../../../experiments/cache-live25-joint5d-v2/park-phr-body-fit/dataset.json).

## Broad tournaments and matched 4D ablations

All four tournaments completed: **80 configurations across 14 model families each**, with no
model-fit failures. Mean, linear/ridge/elastic, quadratic/cubic, neighbors/local linear, RBF/Matern
kernels, RBF/Matern/ARD GPs, forests, extra trees, gradient boosting, XGBoost, CatBoost and small
MLPs competed, with mean and validation-weighted ensembles. The selected family may differ by
output.

The 4D ablations use exactly the same observations, targets and full-theta outer-fold memberships as
their 5D counterparts; only model input indices omit the fifth coordinate. Repeated theta stay
together across campaigns and blocks. Campaign-transfer diagnostics are separate and explicitly
record shared theta. Full quadratic rank does not establish dense support or eliminate uncertainty
in fifth-by-base interactions.

| 5D versus same-evidence 4D                            |    Park-body | Park-PHR-body |
|-------------------------------------------------------|-------------:|--------------:|
| Outputs with better / worse / tied held action regret | 12 / 14 / 22 |  16 / 19 / 13 |
| Outputs with better / worse held RMSE                 |      21 / 27 |       16 / 32 |
| Final single / weighted / mean selections             |   37 / 8 / 3 |   37 / 11 / 0 |
| HARD / SOFT / UNRESOLVED                              |  0 / 24 / 24 |   0 / 26 / 22 |

Important final models include park-body reference-relative R7 scarcity: weighted
quadratic/RBF/cubic; R15: XGBoost; R23: mean linear/ridge/cubic. Interaction reference-relative R7
scarcity selects cubic, R15 XGBoost and R23 a small MLP. Park-body OFF-relative scarcity selects
MLP, weighted forest/Matern/elastic and neighbors for R7/R15/R23; the interaction selects CatBoost,
forest and MLP. These selections are not claims of uniformly useful held ranking.

All eight included campaigns received transfer diagnostics, with 246 available campaign/output cells
per fit. Median transfer RMSE is 0.04298 versus 0.04378 (park-body 5D/4D), and 0.04145 versus
0.04249 (interaction). Median action regret is 0.00472 versus 0.00691 and 0.00532 versus 0.00486
respectively, in log-return units. These descriptive medians hide substantial output-specific
variation; they are not a new selection objective.

Full
evidence: [per-output model selections](../../../../../experiments/cache-live25-joint5d-v2/comparison-v2/selected_models.tsv), [matched ablation metrics](../../../../../experiments/cache-live25-joint5d-v2/comparison-v2/matched_ablation.tsv), [campaign transfer](../../../../../experiments/cache-live25-joint5d-v2/comparison-v2/campaign_transfer.tsv),
and each fit directory's `model_leaderboard.tsv`, `held_theta_metrics.tsv` and `validation.json`.

## Does guidance narrow search better than blind sampling?

These are retrospective development diagnostics using outer-held predictions and training-only model
authority/history, not fresh benchmark validation. At the same eight-action retrospective budget:

| Strategy / family                       | Useful hit rate | Median measured scarcity | Worst measured plentiful | Useful retained | Bad removed |
|-----------------------------------------|----------------:|-------------------------:|-------------------------:|----------------:|------------:|
| Random, park-body                       |          24.85% |                   +2.91% |                  -18.90% |             8/8 |        0/31 |
| Selected reliability-aware, park-body   |          37.50% |                   +3.05% |                  -19.88% |             8/8 |        3/31 |
| Random, interaction                     |          24.80% |                   +2.88% |                  -17.78% |             8/8 |        0/30 |
| Selected reliability-aware, interaction |          25.00% |                   +2.83% |                  -19.23% |             8/8 |        1/30 |

Park-body recovered three useful historical actions versus about two from random selection; its 4D
ablation also recovered three. Interaction recovered two, approximately random performance. All
eight selected historical actions in each full-model comparison violate at least one declared
measured guardrail. The old all-hard-floor strategy selects zero. Reliability-aware filtering
retained 29/32 park-body and 30/31 interaction theta. It avoids deadlock but still discriminates
plentiful risk poorly.

For selected / best-single / mean / validation-weighted prediction variants, useful hit rates are
37.5 / 37.5 / 25 / 37.5% for park-body 5D versus 37.5 / 37.5 / 50 / 50% for its 4D ablation;
interaction is 25 / 25 / 37.5 / 37.5% versus 25 / 25 / 12.5 / 37.5%. Thus neither fifth coordinate
demonstrates a general search-quality win. Ensembles can help particular outputs without proving a
new runtime dependency useful.

See the four
`../../../../../experiments/cache-live25-joint5d-v2/comparison-v2/*-retrospective/retrospective-strategies.tsv`
tables and their JSON per-fold audits. Scarcity usefulness and plentiful failures remain separate
labels; a high scarcity hit rate is not production acceptance.

## Dense search, convergence and landmarks

Each family scored **16,777,216 scrambled Sobol points**, using the unchanged 5D bounds, frozen seed
and all 48 selected output predictions. Checkpoints use the identical 2^20, 2^22 and 2^24 prefixes.
Every eligible point enters exact Pareto filtering; no objective quantization or sampled dominance
approximation was used.

| Search                                                       |                  Park-body |             Park-PHR-body |
|--------------------------------------------------------------|---------------------------:|--------------------------:|
| Eligible after history exclusions                            |                 16,659,969 |                16,672,796 |
| History exclusions                                           |                    117,247 |                   104,420 |
| Support / HARD rejections                                    |                      0 / 0 |                     0 / 0 |
| Exact frontier at 2^20 / 2^22 / 2^24                         | 52,847 / 138,329 / 379,894 | 15,001 / 38,078 / 101,968 |
| Active computational basins                                  |                          2 |                         2 |
| Selected-stack inference seconds                             |                   1,516.35 |                  1,469.32 |
| Search seconds including geometry, before final lock hashing |                   1,919.27 |                  1,781.39 |
| Peak resident memory                                         |                  17.01 GiB |                 16.33 GiB |

Bulk working-memory estimates exceeded 100 GiB per family; the JSON budget is 24 GiB per search. The
runner therefore used 262,144-point vectorized batches (estimated working arrays about 1.8 GiB, with
larger actual peak during geometry). Both jobs ran concurrently. Float64 predictions, disagreement,
coordinates, status and objective arrays are retained. Sparse files reserve optional 2^26 capacity;
**only the valid 2^24 prefix is evidence of scoring**. `dense_manifest.json` defines that prefix.

Park-body stabilized under the declared tolerances: final basin shift 0.03053, maximum objective
change 0.00114 log units. Interaction basin shift was 0.02148, but an objective moved 0.00916, above
the 0.005 tolerance. No 2^26 search ran: projected totals were 7,637 and 7,087 seconds, above the
3,600-second expansion budget; park-body was also stable. This does not claim that interaction
convergence was established.

Within normalized radius 0.3, 3381 retains 199,915 eligible points and 8,600 Pareto points; 2531
retains 184,218 eligible points and 833 Pareto points. Both measured regions survive. The
family-level pool contains a 3381-neighborhood representative at distance 0.18468; its
2531-neighborhood counterpart is absent (nearest family proposal 0.64117). These landmarks did not
recenter the box. The compact cross-family selector preserves broad basin coverage rather than
imposing landmark IDs as mandatory local representatives. Neither landmark has a final four-point
batch member within radius 0.3: final nearest distances are 0.38873 for 3381 and 0.64117 for 2531.
Their surviving local Pareto neighborhoods remain recorded for subsequent reviewed search.

See each search directory's `dense_manifest.json`, `proposals.json`, `search_convergence.json`,
`known_region_recovery.json`, `basins.json`
and [consensus stability diagnostics](../../../../../experiments/cache-live25-joint5d-v2/comparison-v2/park-body-search-diagnostics/consensus_convergence.json).
Disagreement is model spread, not a confidence interval.

## Automatically selected matched experiment

The generic cross-family combiner now preserves declared family/basin coverage and minimum
consensus/exploration roles within the four-point budget. This corrects an initial combiner draft
that dropped a whole basin. No tournament or dense inference was rerun for that correction.
`comparison/` is superseded intermediate preparation; **only `comparison-v2/benchmark/` is the
current handoff**.

| Family / Sobol index     | Role               |     Fifth delta | Nearest measured distance | Predicted OFF scarcity R7 / R15 / R23 | Predicted OFF plentiful R7 / R15 / R23 |
|--------------------------|--------------------|----------------:|--------------------------:|---------------------------------------|----------------------------------------|
| park-body / 15741765     | basin_consensus    | +0.011490885098 |                   0.16103 | +1.96% / +2.28% / +6.66%              | -2.79% / -1.47% / -0.29%               |
| park-body / 2595095      | broad_scarcity     | -0.299385896640 |                   0.35600 | +1.96% / +1.35% / +6.56%              | -3.56% / -2.19% / -0.04%               |
| park-phr-body / 11952826 | basin_consensus    | -0.101321446487 |                   0.18678 | -1.87% / +1.81% / +5.50%              | -2.22% / -1.40% / -0.18%               |
| park-phr-body / 1619069  | model_disagreement | +0.295497196267 |                   0.53615 | -0.63% / +2.21% / +4.06%              | -2.22% / -1.40% / +0.00%               |

All predictions above remain developmental, including SOFT and UNRESOLVED outputs. Qualified ranking
coverage differs by family: unavailable topology directions are not assertions of neutrality.
Boundary-seeking proposals do not authorize widening the box. Some predicted fifth-term scarcity
marginals are zero or negative; the matched ablations are essential, and neither positive OFF
prediction nor a Pareto label proves that the fifth term earns its complexity.

Exact full theta, full 14-coefficient functions, zero-fifth functions, 48
full/counterfactual/marginal predictions, reliability, disagreement, measured distances and
unresolved guardrails are in
the [selected manifest](../../../../../experiments/cache-live25-joint5d-v2/comparison-v2/selected_theta.json).
Setting the declared fifth **delta** to zero restores its exact frozen residual; the other four
coordinates and ten inactive coefficients are preserved.
The [support catalog](../../../../../experiments/cache-live25-joint5d-v2/comparison-v2/surface_catalog.tsv)
and [surface summary](../../../../../experiments/cache-live25-joint5d-v2/comparison-v2/surface_summary.json)
retain requested integer timings and clamp occupancy.

Prepared arms: POLICY_OFF (real inference bypass, 15,000/1,000,000 ns), exact 7931, four nonzero 5D
policies, and their four exact same-base 4D counterparts. This is **10 arms x 18 workloads x 2
balanced independent blocks = 360 JVM forks**, retaining 1,800 measurement windows. There is no
zero-coefficient live baseline or combined sixth-term family.

Fixtures at each R7/R15/R23: S1 and S=R, each with W0/W96/W576. Frozen CPU placements are logical
CPUs 2..15 for R7, 2..23 for R15 and 2..31 for R23, resolved as 7/15/23 physical workers, with
harness CPU 0. Configured sources/work remain fixture inputs only. The existing throughputOnly
CONTINUOUS harness, AUTO participation, three 2-second warmups, five 2-second measurements, one
million executions/invocation and timeout guard are preserved. These are not wall-clock guarantees.

Collection retains all slow outcomes, OFF-relative summaries and exact matched
`same_base_forks.tsv`, `same_base_workloads.tsv`, `same_base_classes.tsv`. Evaluate scarce breadth
across body regimes first, then plentiful harm and repeat/topology robustness; do not choose by one
all-workload geometric average.

## Next steps and reproducibility

1. Review the compact matched handoff, with park-body the stronger priority. The interaction is a
   separate exploratory alternative; its added value is unproven. Execute no partial or modified
   frozen campaign without preparing a reviewed revision.
2. If this handoff is authorized, measure the declared complete arms and compare every 5D function
   to both OFF/7931 and its exact zero-fifth partner. Keep R7/R15 medium scarcity, expensive
   scarcity and the three problematic plentiful fixtures explicit.
3. Add the measured pairs to their separate family histories. Retain a fifth term only if it
   improves the matched achievable tradeoff. If the zero-fifth partner performs as well or better,
   retain that simpler region. Refine only measured useful regions; no sixth term, dynamics or
   production change follows automatically.

The complete offline sequence is runnable from JSON through
`training_runner --task python/pareto-weight-calibration/tasks/live25-joint5d-v2/study.json`.
Existing fits/searches are lock-verified; new output revisions are required rather than overwriting
frozen outputs. The family fit, ablation, search, memory, counterfactual and global selection
contracts all live in that task directory. No experiment-specific Python edit or manual
coefficient-selection step is required.

Generic changes add vectorized fitted-model inference, configurable dense Sobol/memory/convergence
handling, exact large Pareto filtering, arbitrary-parameter counterfactuals, multi-family study
orchestration, basin/role-preserving final selection and declared same-base collection. The runtime
evaluator, participation and production defaults remain unchanged.

Validation: 40 focused generic Python tests passed. A broader trainer run passed 40 and skipped
four, with three failures/two setup errors caused by previously removed
`benchmarks/src/test/resources/cache-timing/live-v2/lock.json` and
`experiments/pareto_direct_side_training/direct_side_candidate.json`. The separate legacy
confirmation module also has five setup errors from that same missing live-v2 fixture. These are
reported limits, not passing coverage. Generated pair/config checks, frozen handoff verification and
`git diff --check` passed. No Java/generated Java changes were needed, so Gradle was not run. No new
JMH benchmark was executed.

Runner
handoff: [comparison-v2/benchmark/HANDOFF.md](../../../../../experiments/cache-live25-joint5d-v2/comparison-v2/benchmark/HANDOFF.md).
The benchmark run output is reserved at `experiments/cache-live25-joint5d-v2-matched-measurement`;
it does not exist yet.
