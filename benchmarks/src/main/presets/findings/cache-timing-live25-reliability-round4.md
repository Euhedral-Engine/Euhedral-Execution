# CACHE live-25 reliability-aware proposals: findings and handoff

**The zero-candidate dead end is resolved. Python selected six theta across three separated tradeoff regions, and prepared eight benchmark arms including POLICY_OFF and the exact live-25 center. No JMH benchmarks ran.** The benchmark remains the oracle; these are candidates worth measuring, not policies certified safe by a surrogate.

The runtime policy, four active parameter definitions/bounds, inactive coefficients, historical dataset, 80 model configurations across 14 families, validation folds and Sobol pool are unchanged. Dataset JSON is identical to round3. Round3 artifacts remain intact. All model fits completed without failure. Two offline preparations produced exactly the same six theta; only the final preparation is frozen here.

## Reliability rules and their effect

The [JSON task](../../../../../python/pareto-weight-calibration/tasks/live25-reliability-round4.json) now declares reliability, risk, history distance, retrospective evaluation and benchmark preparation. There is no special case for live-25, R7, or any named output in the generic proposal code.

- Direction requires at least 8 held theta, 90% prediction coverage, and either Spearman >=0.20, action regret <=0.65 times the mean baseline, or top-k recall >=0.50.
- HARD authority additionally requires at least 12 theta, full observed-target coverage, at least two campaigns, RMSE <=0.95 times mean and <=1.20 times linear, absolute RMSE <=0.025 and MAE <=0.020 log units, overall absolute bias <=0.010 and maximum campaign bias <=0.025.
- Useful ranking without those absolute checks is SOFT. Failing direction checks is UNRESOLVED. Only OFF-system HARD outputs can veto a predicted floor. Center-relative direction can use a different model and is preferred where its ranking quality is stronger.
- SOFT shortfall below the unchanged floor contributes its own capped risk objective: `min(shortfallLog / 0.03, 3)`. Risks are not summed into a global deployment score. Positive predicted direction is capped at +25% for ranking so one enormous body-work prediction cannot dominate. Training targets and reported measured returns are not clipped.
- UNRESOLVED outputs remain fully visible, with their raw predictions and disagreement, but contribute neither a safety assertion nor a failure assertion. Direction rules omit unresolved components and record the remaining signal sources; missing R7 evidence is not silently called neutral.

These are explicit initial search thresholds, chosen before the updated run using the existing diagnostic scale. A 2.5% log-error ceiling is intentionally demanding for an absolute veto near a 3% guardrail. It is not a production tolerance or a confidence interval. Final authority uses the nested held-theta prediction procedure; retrospective fold authority uses only inner-validation data from that fold's training theta. Campaign bias is computed from grouped OOF errors; separate leave-campaign-out diagnostics remain available and are not mislabeled as that bias check.

**Classification: 0 HARD, 20 SOFT, 28 UNRESOLVED.** `off:R7_S7_W0` is UNRESOLVED through the generic rules: held RMSE 0.10468 exceeds mean-baseline RMSE 0.10113, Spearman is -0.03684, and held top-k recall is zero. Its roughly -6.26% flat forecast is preserved, but cannot veto the domain.

| Response | Center-relative | OFF-relative |
| --- | --- | --- |
| R7_scarce | UNRESOLVED | UNRESOLVED |
| R15_scarce | UNRESOLVED | SOFT |
| R23_scarce | SOFT | SOFT |
| R7_plentiful | UNRESOLVED | UNRESOLVED |
| R15_plentiful | UNRESOLVED | UNRESOLVED |
| R23_plentiful | UNRESOLVED | UNRESOLVED |
| R7_S1_W0 | SOFT | UNRESOLVED |
| R7_S1_W96 | UNRESOLVED | UNRESOLVED |
| R7_S1_W576 | SOFT | SOFT |
| R7_S7_W0 | SOFT | UNRESOLVED |
| R7_S7_W96 | SOFT | UNRESOLVED |
| R7_S7_W576 | UNRESOLVED | UNRESOLVED |
| R15_S1_W0 | UNRESOLVED | SOFT |
| R15_S1_W96 | UNRESOLVED | SOFT |
| R15_S1_W576 | UNRESOLVED | UNRESOLVED |
| R15_S15_W0 | UNRESOLVED | UNRESOLVED |
| R15_S15_W96 | SOFT | SOFT |
| R15_S15_W576 | UNRESOLVED | UNRESOLVED |
| R23_S1_W0 | SOFT | SOFT |
| R23_S1_W96 | UNRESOLVED | UNRESOLVED |
| R23_S1_W576 | SOFT | SOFT |
| R23_S23_W0 | SOFT | SOFT |
| R23_S23_W96 | UNRESOLVED | UNRESOLVED |
| R23_S23_W576 | SOFT | SOFT |

The [per-output reliability table](../../../../../experiments/surrogate-live25-round4/output_reliability.tsv) includes every threshold check, baseline comparison, bias and validation metric. The [policy config](../../../../../experiments/surrogate-live25-round4/reliability_policy.json) contains the exact rules.

## Retrospective search efficiency

This is development evidence, not fresh benchmark validation. Each theta is held out completely across campaigns. Ranking uses only its outer-held predictions; reliability and measured-history exclusions use training data only. Blind selection samples the same held historical pool without replacement, with 1,000 seeded trials. It approximates blind exploration over measured points; it does not claim throughput knowledge for unmeasured dense Sobol points.

Each method has a budget of two actions per outer fold (eight across four folds). The five scarcity-useful historical points are the top quarter by positive scarce-body count, then minimum-topology scarcity, then capped broad scarcity. This label is deliberately distinct from production acceptance. Eighteen of nineteen theta violate at least one pooled -3% plentiful workload threshold used by this retrospective diagnostic. Repeated-bad neighborhood exclusions require at least two failing independent blocks in the same campaign, not just that pooled label.

| Policy | Selected historical actions | Scarcity-useful hit rate | Median measured scarce change | Worst plentiful change | Useful retained | Pooled guardrail-bad removed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| blind_random | 8 | 27.88% | +2.30% | -15.88% | 5/5 | 0/18 |
| old_hard_floor | 0 | n/a | n/a (no action) | n/a (no action) | 0/5 | 18/18 |
| reliability_aware | 8 | 37.50% | +2.74% | -16.86% | 5/5 | 0/18 |

Blind values are Monte Carlo means, including the average worst selected plentiful outcome. New-policy values are the actual retrospective selection. New ranking selects 3/8 scarcity-useful actions versus 2.23/8 on average for blind selection, and raises median scarcity return from +2.30% to +2.74%. This is modest evidence of directional value in a tiny development set, not a demonstrated generalization guarantee.

**Guardrail discrimination remains weak.** The new policy retains all nineteen historical points before ranking, removes none of the eighteen pooled guardrail-bad points, and selects eight actions with at least one measured plentiful violation. Its worst plentiful outcome is -16.86%, versus -15.88% averaged over blind draws. Therefore this result does not establish improved production-risk screening. The old policy removes everything, including all five scarcity-useful points; weak `off:R7_S7_W0` vetoes all five. Retrospective ranking is an abbreviated small-pool Pareto/direction diagnostic, not a claim to validate dense-space clustering or future consensus proposals.

[Comparison table](../../../../../experiments/surrogate-live25-round4/retrospective_comparison.tsv), [held actions](../../../../../experiments/surrogate-live25-round4/retrospective_held_actions.tsv), and [full search-efficiency report](../../../../../experiments/surrogate-live25-round4/search_efficiency.json) retain fold memberships, labels, random distributions, per-output weak vetoes and measured outcomes.

## Dense search and selected regions

- 8,192 scrambled Sobol points, seed 20260911, in the unchanged local box.
- 62 points fall within normalized distance 0.10 of measured theta. Another 170 are excluded by the 0.15 radius around repeated bad observations: 232 history exclusions in total. No support/clamp failures.
- Zero hard predicted vetoes; 7,960 points remain eligible. Per-output soft risks and direction objectives leave 2,486 non-dominated tradeoffs, eliminating 5,474 dominated eligible points.
- The unconstrained-with-respect-to-predicted-floors frontier and reliability-aware frontier coincide because no output qualifies as HARD. Both include soft-risk objectives and honor geometry/history exclusions.
- Three parameter-space clusters contain 1,102, 739 and 645 frontier points. These are computational tradeoff regions, not claims of measured physical basins.
- Six proposals are chosen automatically; their minimum pairwise normalized distance is 0.20133, above the required 0.15. No coefficient was manually selected. The old hard-floor policy still yields zero points on this same pool.

| Sobol index | Role | Region | Distance to nearest measured theta | Unresolved outputs requiring measurement |
| --- | --- | ---: | ---: | ---: |
| 2574 | basin_consensus | 0 | 0.16433 | 28 |
| 4755 | basin_consensus | 1 | 0.16331 | 28 |
| 6321 | basin_consensus | 2 | 0.23390 | 28 |
| 3799 | broad_scarcity | 1 | 0.26376 | 28 |
| 7931 | minimum_topology | 2 | 0.23396 | 28 |
| 6904 | model_disagreement | 0 | 0.66687 | 28 |

Every proposal carries the same 28 unresolved output models listed above. For production plentiful checks, these include all three topology aggregates and the individual OFF responses R7/S7/W0, W96 and W576; R15/S15/W0 and W576; and R23/S23/W96. Every candidate must measure these, including the former blocking R7 no-op case. No proposal is labeled safe.

The [proposal manifest](../../../../../experiments/surrogate-live25-round4/proposals.json) contains exact theta/full runtime configs, all 48 selected predictions, each model's predictions, disagreement, reliability, hard/soft/unresolved contribution, nearest measured theta, history distances and role. [Consensus rows](../../../../../experiments/surrogate-live25-round4/proposal_consensus.tsv) provide one row per proposal/output. Disagreement uses the retained validation-qualified comparison models and is not a confidence interval; unresolved spread cannot dominate the consensus choice.

Known regions: sample 0008 is retained and selected in the retrospective comparison. Its radius-0.30 neighborhood has 132 eligible dense points and 116 Pareto points; proposal 3799 represents it at distance 0.26376. Sample 0015 is also retained retrospectively, with 138 eligible neighbors and 58 Pareto points, but no chosen proposal lies within that radius (nearest 0.72061). Its region remains available; this six-point batch does not represent every known useful neighborhood. [Region recovery](../../../../../experiments/surrogate-live25-round4/known_region_recovery.json) records these limits.

[Dense predictions](../../../../../experiments/surrogate-live25-round4/dense_predictions.tsv), [unconstrained frontier](../../../../../experiments/surrogate-live25-round4/unconstrained_pareto.tsv), [reliability-aware frontier](../../../../../experiments/surrogate-live25-round4/pareto_frontier.tsv), and [basin report](../../../../../experiments/surrogate-live25-round4/basins.json) are retained separately.

## Executable preparation and next step

The existing calibration runner handoff is [benchmark/HANDOFF.md](../../../../../experiments/surrogate-live25-round4/benchmark/HANDOFF.md). Arms are POLICY_OFF (actual production inference bypass), the exact live-25 center, and Sobol proposals 2574, 4755, 6321, 3799, 7931 and 6904. All other runtime coefficients, output bounds, transforms, rounding and fallback semantics are unchanged.

Fixtures are the established R7/R15/R23 physical placements, each with scarce S1 and plentiful S=R, at W0/W96/W576: eighteen workloads. CPU lists remain R7=2..15 (7 physical workers), R15=2..23 (15), R23=2..31 (23), with harness CPU 0. Logical-list length is not used as worker count. Two balanced independent blocks produce **8 arms x 18 workloads x 2 = 288 JVM forks**, each retaining five JMH measurement windows. The prior CONTINUOUS fixture, three 2-second warmups, five 2-second measurements, execution target and timeout guard remain unchanged; these are not wall-clock guarantees.

1. Review the six-point batch and its unresolved guardrails. The direction diagnostic supports trying a bounded batch, while its poor plentiful discrimination requires explicit matched measurements; no prediction advances a production policy.
2. After authorization, execute and collect through the existing calibration runner commands in the handoff. Keep all slow outcomes and windows. Compare each candidate against both OFF and its same-campaign center, with scarce bodies/topologies and plentiful guardrails reported separately.
3. Review actual returns before another tuning round. If measurements show a region is poor, add it to compatible history rather than relying on an unskilled guardrail model. Preserve alternate non-dominated regions, including the unrepresented 0015 neighborhood, until measured evidence rules them out. No dynamics, bounds expansion or runtime model change is authorized by this preparation.

## Reproduction and verification

```bash
export PYTHONPATH="$PWD/python/pareto-weight-calibration/src"
/tmp/euhedral-surrogate-tournament-venv/bin/python -m pareto_weight_calibration.training_runner \
  --task python/pareto-weight-calibration/tasks/live25-reliability-round4.json \
  --output-dir /tmp/live25-round4-review
```

The task remains entirely JSON-driven. New generic code handles validation authority, per-output risks, retrospective evaluation, distinct tied predictions and adapter-based benchmark preparation. The existing calibration freeze/check/run/collect workflow is reused; no experiment-specific coefficient code or new runtime model was added.

Validation: 18 focused tests passed; the final relevant generic/archive/tuning suite passed 60 tests, with 2 skips and 2 explicitly deselected historical tests (missing old artifact and stale frozen source identity). The real benchmark handoff check passed its hashes, source identity, physical topology and arm/fixture/block inventory. Final output/source hashes passed; round3 files are unchanged; combined dataset JSON is identical; all six repeated-generation theta match exactly. `git diff --check` passed. No Java/generated-Java changes, Gradle builds or JMH execution occurred.
