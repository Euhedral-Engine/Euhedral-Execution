# CACHE live-25 optimizer proposals: findings and suggested next steps

## Finding

The completed 144-fork follow-up **does not confirm either optimizer proposal as an improvement
for the full scarce-source/guardrail objective**. Proposal **2997** is worse than the unchanged
center on all six pooled topology/class aggregates. Proposal **3853** has a useful R7 medium-work
signal, but it is not broad across scarce bodies and both proposals introduce repeated material
R15 plentiful/no-op losses. Neither should advance to dynamics or production as currently fitted.

This is a failure of these predicted tradeoffs to satisfy the objective, not proof that the
live-25 family has failed. Continue through a reviewed, evidence-preserving update of the local
tuning dataset and response model. Do not blindly chain the old optimizer's predictions, invent
manual weights, widen the parameter region, or restart broad policy search.

The most important observations are:

- **2997:** scarce geometric changes are R7 **-6.17%**, R15 **+1.45%**, R23 **+5.34%**.
  Only **3/9** scarce workload means improve. R7/S1/W0 loses **-13.60%** pooled, including
  **-30.34%** in the second block. R15/S15/W0 loses **-10.03%**, with both blocks about -10%.
- **3853:** scarce changes are R7 **+15.98%**, R15 **+1.73%**, R23 **+6.93%**.
  Only **4/9** scarce means improve. R7/S1/W96 gains **+57.85% pooled**, but splits
  **+111.81% / +3.45%** between blocks. R15/S1/W96 loses **-4.06%** and R15/S15/W0 loses
  **-7.66%**, both negative in both blocks. Its plentiful R15 aggregate is **-3.93%**.
- **The unchanged center also improves in this campaign:** scarce R7 **+1.47%**, R15 **+2.05%**,
  R23 **+7.97%**, with **6/9** scarce means positive. Its R7 plentiful aggregate is **+5.93%**,
  larger than either proposal's. Positive R7 plentiful results do not by themselves establish
  that the optimizer repaired that behavior.

## Evidence and scope

Inputs are the frozen [proposal manifest](../cache-timing/live25-auto-proposal-v1/candidate_manifest.json),
[lock](../cache-timing/live25-auto-proposal-v1/lock.json) and
[runner handoff](../cache-timing/AUTOMATED_PROPOSAL_HANDOFF.md). The completed experiment and evidence
use `cache-timing-live25-auto-proposal-v1`. This report updates its earlier unexecuted status;
[initial tuning findings](cache-timing-live25-auto-tuning.md) remain historical evidence.

The audit verified all **144 JVM forks and 720 measurement windows**: four arms x 18 workloads x
two independent balanced blocks. Recollection from every raw log reproduced `arms.json`,
`per_fork.tsv`, `per_workload.tsv`, `class_summary.tsv`, `overall_descriptive.tsv` and
`measurement_windows.tsv` byte-for-byte. Every retained config/log hash matches, and every trial
matches its frozen treatment, schedule, non-treatment fields, labels and sweep identity.
All six 24-output response bundles reproduce from the **108 live-policy forks plus 36 OFF forks**.

Launch source, harness, lock and topology identities agree. All **26** current distribution JAR
hashes match launch; source and binary hashes also match the initial tuning campaign. Logs agree
on JMH 1.37 and OpenJDK 21.0.2. The frozen proposal handoff passes strict source/input/topology/
Python-environment checking. Different throughput across campaigns therefore cannot be attributed
to an identified source/build change here; the experiment does not isolate its cause.

R7/R15/R23 retain logical CPUs 2..15 / 2..23 / 2..31, physically resolved as before, with harness
CPU 0. Each topology has scarce S1 and plentiful S=R, each crossed with W0/96/576. The fixture is
CONTINUOUS but static: three 2-second warmups, five 2-second measurements, one million executions
per invocation and the 120-second guard. AUTO participation, fixed weights, production OFF
bypass and disabled observers are intact. Work units are fixture weights, not body nanoseconds.
No dynamic behavior, state occupancy, fallback frequency or actual visited park/H distribution
is established by these throughput logs.

Sources: [launch identity](../../../../../experiments/cache-timing-live25-auto-proposal-v1/identity.json),
[evidence receipt](../../../../../experiments/cache-timing-live25-auto-proposal-v1-evidence/evidence_manifest.json),
[audit receipt](../../../../../experiments/cache-timing-live25-auto-proposal-v1-analysis/audit.json),
[reproducible analysis](../../../../../experiments/cache-timing-live25-auto-proposal-v1-analysis/analyze.py).
No new benchmark, fit, candidate generation or runtime change was performed for this analysis.

## Measured objective: scarcity and plentiful remain separate

For each workload, pool the two candidate fork means and two matched OFF means, then take their
log ratio. A topology/class geometric change is `100 * expm1(mean(log ratios))` across the three
body regimes. Block scores use that block's OFF fork. Forks are replication units; five windows
are nested observations, and a policy/block response bundle is not an additional replicate.
Shared OFF denominators correlate candidate comparisons. Pooling before logging and averaging
block log returns are different operations.

There is no combined winner score. Broad scarce-body improvement comes first, with plentiful
harm and robustness across topologies/blocks kept visible. Small plentiful losses may be acceptable;
repeated material losses cannot be hidden by other topology gains. Positive counts below are
out of nine scarce workloads, with the second count positive in both blocks.

| Policy | R7 scarce | R15 scarce | R23 scarce | R7 plentiful | R15 plentiful | R23 plentiful | Scarce positive / both |
| --- | --- | --- | --- | --- | --- | --- | --- |
| center | +1.47% | +2.05% | +7.97% | +5.93% | -1.11% | +1.30% | 6 / 4 |
| 2997 | -6.17% | +1.45% | +5.34% | +2.88% | -3.78% | +0.46% | 3 / 2 |
| 3853 | +15.98% | +1.73% | +6.93% | +3.05% | -3.93% | +0.19% | 4 / 3 |

Relative to the **same-run center**, 2997 loses -7.53%, -0.59%, -2.44% on R7/R15/R23 scarcity
and -2.88%, -2.70%, -0.84% on plentiful. Thus center dominates 2997 on these six measured pooled
coordinates; this is descriptive dominance, not statistical proof of universal inferiority.
3853 beats center only on R7 scarcity (+14.30%); it loses -0.31% / -0.96% on R15/R23 scarcity
and -2.72% / -2.85% / -1.10% on plentiful. The original model's role names describe proposal
selection, not measured success.

[Full policy summaries](../../../../../experiments/cache-timing-live25-auto-proposal-v1-analysis/policy_summary.tsv)
include each topology/class block score, comparisons to center, counts and worst workloads.
No result is excluded for being slow or inconsistent, and CV is not a reward.

## Scarce body regimes and fork variation

Each cell shows **pooled change (block 0 / block 1)** versus OFF.

| Workload | Center | 2997 | 3853 |
| --- | --- | --- | --- |
| R7-S1-W0 | -1.10% (+0.55% / -2.73%) | -13.60% (+3.31% / -30.34%) | -0.97% (-0.88% / -1.05%) |
| R7-S1-W96 | +5.55% (+4.15% / +6.97%) | -3.81% (-6.32% / -1.27%) | +57.85% (+111.81% / +3.45%) |
| R7-S1-W576 | +0.08% (+1.73% / -1.53%) | -0.60% (+0.69% / -1.85%) | -0.20% (+1.60% / -1.96%) |
| R15-S1-W0 | +13.39% (+13.41% / +13.37%) | +12.97% (+10.80% / +15.31%) | +10.25% (+7.87% / +12.83%) |
| R15-S1-W96 | -5.78% (-1.34% / -9.94%) | -7.50% (-8.52% / -6.54%) | -4.06% (-2.73% / -5.31%) |
| R15-S1-W576 | -0.52% (-0.52% / -0.53%) | -0.09% (-0.17% / -0.01%) | -0.46% (-0.94% / +0.02%) |
| R23-S1-W0 | +6.21% (+6.87% / +5.58%) | +8.79% (+10.31% / +7.31%) | +10.55% (+11.18% / +9.95%) |
| R23-S1-W96 | +2.76% (+8.14% / -2.16%) | -5.58% (-0.31% / -10.40%) | -1.00% (+9.03% / -10.16%) |
| R23-S1-W576 | +15.31% (+35.16% / +0.72%) | +13.79% (+34.37% / -1.33%) | +11.72% (+32.90% / -3.85%) |

3853's R7 scarcity aggregate splits **+28.72% / +0.12%**. Its R7/S1/W96 fork means are
**43.932 / 21.284 million executions/s**; the positive second block is much smaller than the
first and is below the same-block center. R7 cheap and expensive scarce means are slightly
negative. This does not meet the intended broad improvement across body regimes, even though
both matched medium-work block changes are positive.

2997 loses on all three pooled R7 scarce fixtures, and R15/S1/W96 is **-8.52% / -6.54%** by
block. 3853 also loses there in both blocks (**-2.73% / -5.31%**). Both R15 scarce aggregates are
positive largely because W0 improves; their W96 losses remain important.

R23 scarce aggregates split **+13.90% / -1.74%** for 2997 and **+17.23% / -1.71%** for 3853.
The center also varies strongly (+16.03% / +1.33%). Expensive-body pooled gains cannot be read
as stable improvement: R23/S1/W576 splits +34.37% / -1.33% for 2997, +32.90% / -3.85% for 3853,
and +35.16% / +0.72% for the center. All slow OFF and candidate outcomes remain in the comparison.

## Plentiful guardrails and observed minima

All changes in this table are pooled against OFF.

| Plentiful workload | Center | 2997 | 3853 |
| --- | --- | --- | --- |
| R7-S7-W0 | +8.32% | +5.64% | +6.94% |
| R7-S7-W96 | +9.87% | +3.36% | +2.33% |
| R7-S7-W576 | -0.13% | -0.27% | -0.00% |
| R15-S15-W0 | -3.76% | -10.03% | -7.66% |
| R15-S15-W96 | +0.37% | -0.88% | -4.11% |
| R15-S15-W576 | +0.12% | -0.12% | +0.13% |
| R23-S23-W0 | +3.37% | +0.91% | +0.40% |
| R23-S23-W96 | +0.88% | +0.33% | +0.00% |
| R23-S23-W576 | -0.31% | +0.14% | +0.16% |

R15/S15/W0 loses **-10.22% / -9.82%** for 2997 and **-8.20% / -7.08%** for 3853. These are
repeated material losses, not slight negative guardrail aggregates to trade away. Both R15
plentiful aggregates are negative in both blocks. 3853 additionally loses **-4.11% pooled**
at S15/W96, including -7.32% in block 1. Its positive R7/R23 plentiful results do not cancel this.

The next table shows **minimum fork mean / minimum measurement window**, in millions of
executions/s. These are observations from two forks and ten nested windows per cell, not
quantiles or promised future performance.

| Workload | OFF | Center | 2997 | 3853 |
| --- | --- | --- | --- | --- |
| R7-S1-W0 | 29.445 / 27.352 | 28.939 / 28.366 | 20.725 / 20.060 | 29.184 / 28.377 |
| R7-S1-W96 | 20.574 / 20.415 | 21.602 / 21.533 | 19.430 / 19.355 | 21.284 / 21.168 |
| R15-S1-W96 | 13.695 / 11.759 | 13.174 / 12.297 | 12.528 / 10.957 | 13.321 / 9.281 |
| R15-S15-W0 | 714.460 / 653.938 | 699.997 / 661.798 | 644.292 / 633.806 | 663.880 / 651.565 |
| R15-S15-W96 | 101.364 / 99.960 | 101.026 / 100.677 | 99.964 / 98.741 | 93.945 / 93.349 |
| R23-S1-W576 | 18.654 / 15.295 | 25.213 / 24.941 | 25.045 / 24.532 | 24.406 / 22.422 |

[All 72 workload cells](../../../../../experiments/cache-timing-live25-auto-proposal-v1-analysis/workload_details.tsv)
retain pooled executions/s, each fork mean, both baselines, per-block changes and both minima.
The [original window table](../../../../../experiments/cache-timing-live25-auto-proposal-v1-evidence/measurement_windows.tsv)
retains all 720 measurements.

## Frozen predictions versus new observations

The pre-existing quadratic model predicted these points before this campaign. The table compares
its forecast with the geometric change derived from the **mean measured block log return**, the
actual fitting target. These measured values intentionally differ from pooled-fork aggregates
above. No model was refitted to these outcomes for this report.

| Response | 2997 predicted / measured | 3853 predicted / measured |
| --- | --- | --- |
| R7_scarce | +23.05% / -6.74% | +14.95% / +13.52% |
| R15_scarce | +2.76% / +1.45% | +2.47% / +1.76% |
| R23_scarce | +7.07% / +5.79% | +6.06% / +7.34% |
| R7_plentiful | -0.97% / +2.96% | -0.92% / +2.89% |
| R15_plentiful | +1.05% / -3.78% | +0.81% / -3.95% |
| R23_plentiful | -0.36% / +0.42% | -0.43% / +0.19% |

2997's predicted R7 scarcity **+23.05%** became **-6.74%** on the fitting target. Its predicted
R7/S1/W96 +76.32% became -3.83%. Both proposals were forecast to have positive R15 plentiful
returns, but measured about -3.8..-3.9%. At R15/S15/W0 the predicted gains of +0.67% / +0.08%
became -10.02% / -7.64% on mean block log returns. Predicted guardrail feasibility did not survive
measurement.

3853's R7/S1/W96 average log response is close to the prediction: +48.02% measured versus
+47.68% predicted, yet it combines +111.81% and +3.45%. An accurate average at one fixture does
not establish robust action ranking or repair the R15 guardrail miss. The original held-parameter
fit was already weak; this prospective check adds direct evidence of forecast error at selected
points. The 72 audited target comparisons (24 each for the two proposals and repeated center),
block residuals and log RMSE are in
[forecast_residuals.tsv](../../../../../experiments/cache-timing-live25-auto-proposal-v1-analysis/forecast_residuals.tsv).
This batch is now development evidence for any subsequent fit, not an untouched validation set.

## The repeated controls changed too

The unchanged center's R7 plentiful aggregate moved from **-1.92%** in the initial tuning batch
to **+5.93%** here. At R7/S7/W0 its raw pooled throughput changed from **368.927 to 382.691 million
executions/s**, while OFF changed from **397.813 to 353.289 million**. Center's relative score
therefore changed from -7.26% to +8.32% without a coefficient or identified binary change.
At R7/S7/W96 center throughput actually decreased (66.774 to 64.829 million), while OFF decreased
more (65.658 to 59.003 million); the relative change rose from +1.70% to +9.87%.

Likewise, center R23/S1/W576 shifted from -0.80% to +15.31% relative to its campaign's OFF:
center throughput was 24.809 then 25.390 million, while OFF was 25.011 then 22.019 million.
These facts do not invalidate retained slow baselines or justify removing them. They show why
matched current controls, both blocks and raw throughput matter, and why cross-campaign relative
rankings must not be treated as causal evidence that a function repaired a topology.
[Repeated control comparisons](../../../../../experiments/cache-timing-live25-auto-proposal-v1-analysis/repeated_controls.tsv)
keep the campaigns separate. No telemetry or overhead investigation is needed to record this
uncertainty, and no particular scheduling or environmental mechanism is established.

## Suggested next steps

1. **Do not advance either exact proposal or the original center yet.** Retain 2997 as a measured
   unsuccessful proposal; its six pooled aggregates are dominated by center and it has repeated
   R15 harm. Retain 3853 as useful evidence about R7 medium-work behavior, with explicit fork
   variation and R15 cost. The center's better result in this batch does not erase prior R7
   misses or its current R15/S1/W96 loss. No dynamics or production-default change is supported.
2. **Prepare the next offline update using both complete campaigns.** The combined evidence is
   **40 policy/block bundles, 19 distinct parameter vectors, 720 live-policy forks and 72 OFF
   forks (792 total)**. Keep campaign-specific OFF matches; use campaign-qualified row/run IDs
   so repeated center/block names do not collide. Group every observation of the same parameter
   vector together for validation, including center across both campaigns. Preserve all workload
   panels, windows and failed tradeoffs. Do not fit just the six new bundles or count shared
   baselines as independent rewards. This combined dataset has not been built by this report.
3. **Refit and audit the small generic response model before another optimization batch.** Keep
   the same four parameters, independent timing outputs, bounds, signs and 24 response targets.
   Compare grouped held-parameter predictions with a training-only mean diagnostic and inspect
   per-body and plentiful forecast error, including the two now-measured proposals. Treat
   cross-campaign prediction errors and repeated-center variation explicitly. Do not widen H's
   search region merely because the failed proposals were near its boundary, and do not add
   runtime complexity or a broad model tournament.
4. **Let the tooling choose the next weights only after that review.** If the updated surface
   provides a useful local direction, generate a small diverse Pareto follow-up using the full
   measured-vector history and unchanged scarce/plentiful decision hierarchy. Measure every
   proposed point against same-run OFF and center. If ranking remains too weak to justify new
   coefficients, use bounded replication of already measured informative points to resolve
   uncertainty instead of inventing manual perturbations or repeatedly trusting optimistic
   forecasts. R7/S1/W96 and R15 plentiful W0/W96 are the specific unresolved effects; retain
   enough scarce body/topology coverage to prevent a one-fixture win from masking regressions.
5. **Confirm a successful measured tradeoff before dynamics.** Additional independent replication
   is needed before promotion; this two-block stage remains exploration. Only a point that
   improves scarcity broadly while avoiding material plentiful harm should receive the existing
   persistent transition checks. Small plentiful losses can be acceptable, but this batch's
   repeated 7..10% no-op losses are not approximately neutral. No desired worker count, global
   controller, hot-path telemetry or inference-overhead campaign is called for.

The immediate next stage is reviewed preparation of the combined offline dataset/fit, followed
by an evidence-directed bounded experiment, not another automatic run of this completed manifest.
This continues investigation of the live-25 family while incorporating the optimizer's misses.

## Reproduction and execution boundary

From repository root:

```bash
PYTHONPATH="$PWD/python/pareto-weight-calibration/src" \
  /tmp/euhedral-auto-tuning-20260909-venv/bin/python \
  experiments/cache-timing-live25-auto-proposal-v1-analysis/analyze.py
```

The script reads retained evidence, recollects into disposable scratch, and writes only derived
analysis tables and its audit receipt. The frozen configs, coefficients, fit and original data
remain intact. Findings links, hashes and `git diff --check` were checked. No scheduler or trainer
source was changed for this report, so Gradle was not rerun. No new benchmarks, fit or candidate
manifest were generated, and production timing and participation remain unchanged.
