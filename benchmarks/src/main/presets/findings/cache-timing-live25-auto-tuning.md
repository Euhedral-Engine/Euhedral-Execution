# CACHE live-25 automated tuning: findings and suggested next steps

## Finding

The first automated local batch shows that changing live-25's four active parameters can recover
R7 scarcity while preserving R15/R23 gains. It has **not** found a policy that demonstrates broad
scarcity improvement together with satisfactory plentiful-source behavior. Continue bounded local
tuning; do not promote the original center or a sampled winner to production or dynamics yet.

Two measured directions are informative:

- **Sample 0008** improves eight of nine scarce workload means, with six positive in both blocks.
  Scarcity gains are **R7 +3.12%, R15 +4.74%, R23 +6.47%**. Its sole scarce loss is -0.28%,
  but R7 plentiful loses **-5.90%** overall, including **-9.69% at S7/W0** and **-7.53% at S7/W96**.
  This is useful evidence of a better scarcity surface with an unresolved guardrail failure.
- **Sample 0015** gains **R7 +34.62%, R15 +1.42%, R23 +7.48%** on scarce aggregates.
  The R7 gain is mostly S1/W96: **+130.40% pooled**, +132.36% / +128.44% by block.
  That large effect is retained evidence, not a reason to discard the run. It does not hide
  **R15/S1/W96 -5.81%** or **R7/S7/W0 -6.45%**. Only six of nine scarce means are positive.

The generic pipeline also completed its regularized quadratic fit and emitted **two automatic
Pareto proposals**. Those proposals are frozen but **unmeasured**. Held-parameter prediction error
is worse than a training-fold mean diagnostic for all six topology/class outputs. The fit is a
hypothesis generator with substantial uncertainty, not a validated ranking or evidence of gain.
A small measured test of the two existing proposals is the appropriate next optimization step;
there is no basis for a broad new model search or abandoning the PHR-only family.

## Evidence and provenance

The completed campaign is `live25-auto-v2`. This report updates the earlier unexecuted status recorded in the
[automated handoff](../cache-timing/AUTOMATED_TUNING_HANDOFF.md), while earlier fixed/live and
confirmation findings remain historical evidence. Reviewed inputs are the
[candidate manifest](../cache-timing/live25-auto-v2/candidate_manifest.json),
[lock](../cache-timing/live25-auto-v2/lock.json) and
[tuning specification](../cache-timing/live25-auto-v2/tuning_spec.json).

The audit verified:

- **648 independent JVM forks**, 18 arms x 18 workload families x two balanced blocks; **3,240**
  nested JMH measurement windows. Every retained fork/window is included.
- Every raw trial matches the frozen treatment, schedule, labels, sweep identity and non-treatment
  calibration fields. Re-reading all raw logs reproduces `arms.json`, `per_fork.tsv`,
  `per_workload.tsv`, `class_summary.tsv`, `overall_descriptive.tsv` and
  `measurement_windows.tsv` byte-for-byte. Config/log SHA-256 values match the collected receipt.
- Launch lock, source hashes and topology agree with the frozen revision. All **26** current
  distribution JAR hashes match the launch identity. Logs consistently report JMH 1.37 and
  OpenJDK 21.0.2. Both initial and proposal handoffs pass strict input/source/topology/environment
  verification.
- R7 uses logical CPUs 2..15, R15 2..23 and R23 2..31; harness CPU 0. Each uses S1 and S=R,
  crossed with W0/96/576. These are fixture coordinates, not substitutions for runtime PHR/body.
  AUTO participation, CONTINUOUS static lifecycle, fixed weights and disabled observers are intact.
  Each fork has three 2-second warmups, five 2-second measurement windows, one million executions
  per invocation and the 120-second guard; these are scheduling settings, not timing guarantees.
- The **34** policy/block response bundles reproduce from the **612** live-policy forks and
  **36** matched OFF denominators. The fit's dataset/task hashes agree. All 17 parameter IDs are
  held out exactly once, with both blocks and complete workload panels kept together.
- The two existing optimizer proposals reproduce exactly from the retained fit and declared
  search settings. Their output directory does not exist: no proposal benchmark was found.

Reproducible audit and extra tables: [analysis script](../../../../../experiments/cache-timing-live25-auto-v2-analysis/analyze.py),
[audit receipt](../../../../../experiments/cache-timing-live25-auto-v2-analysis/audit.json),
[original evidence](../../../../../experiments/cache-timing-live25-auto-v2-evidence/evidence_manifest.json),
[launch identity](../../../../../experiments/cache-timing-live25-auto-v2/identity.json) and
[fit](../../../../../experiments/cache-timing-live25-auto-v2-fit/run.json).
No benchmark, new model fit, new candidate campaign or runtime change was made for this analysis.

## Objective and complete policy comparison

Pool the two candidate fork means and matched POLICY_OFF fork means within each workload, then
compute their log ratio. A topology/class geometric change is `100 * expm1(mean(log ratios))`
over its three body regimes. Block changes use that block's OFF denominator. Pooling before logging
need not equal averaging block log returns. Forks are replication units; windows and policy/block
bundles do not create extra independent experiments. Shared OFF denominators correlate comparisons.

PRIMARY scarcity and GUARDRAIL plentiful remain separate. No equal-weight score across all 18
workloads selects a winner. Broad body-regime improvement, individual plentiful losses and
cross-topology/block robustness take precedence over aggregate size. Small plentiful losses are
not an automatic rejection; a repeated material workload loss cannot be hidden by its class mean.

IDs below abbreviate `live25-auto-sample-NNNN`. Order is the frozen candidate order, not a winner
ranking. Positive counts are out of nine scarce workloads; "both" means positive in both blocks.

| Policy | R7 scarce | R15 scarce | R23 scarce | R7 plentiful | R15 plentiful | R23 plentiful | Scarce positive / both |
| --- | --- | --- | --- | --- | --- | --- | --- |
| center | -2.23% | +2.73% | +6.71% | -1.92% | -0.56% | -1.31% | 5 / 3 |
| 0000 | +1.27% | +1.28% | +5.23% | -5.14% | -0.50% | -0.10% | 5 / 3 |
| 0001 | +16.80% | +1.52% | +3.29% | -1.57% | -0.01% | -1.79% | 6 / 2 |
| 0002 | +0.46% | +2.11% | +6.70% | -6.15% | +0.18% | +0.70% | 7 / 5 |
| 0003 | -0.37% | +1.69% | -1.78% | -1.24% | -1.72% | -0.19% | 4 / 3 |
| 0004 | +3.70% | +3.45% | +4.46% | -4.19% | -1.16% | -0.91% | 7 / 6 |
| 0005 | -4.37% | -0.31% | +4.41% | -2.10% | +0.80% | +0.52% | 3 / 2 |
| 0006 | +0.01% | +4.01% | +5.43% | -2.91% | +1.22% | +0.02% | 6 / 5 |
| 0007 | +0.99% | -0.55% | +4.92% | -7.53% | -2.46% | -1.63% | 6 / 3 |
| 0008 | +3.12% | +4.74% | +6.47% | -5.90% | +0.28% | -0.95% | 8 / 6 |
| 0009 | -4.08% | +3.53% | +1.72% | -1.26% | -0.57% | -0.29% | 5 / 2 |
| 0010 | -8.64% | +2.83% | +3.45% | -2.28% | -0.65% | -1.38% | 6 / 4 |
| 0011 | +0.91% | +1.03% | +7.08% | -5.29% | +0.25% | -0.64% | 8 / 5 |
| 0012 | -0.56% | +4.25% | +0.52% | -4.62% | -1.11% | -1.87% | 4 / 3 |
| 0013 | -0.51% | -0.37% | +4.28% | +0.49% | -1.19% | +0.79% | 5 / 5 |
| 0014 | -0.90% | +2.04% | +3.16% | -2.03% | -0.72% | +0.30% | 5 / 5 |
| 0015 | +34.62% | +1.42% | +7.48% | -2.37% | +0.45% | -0.26% | 6 / 5 |

The exact original center again shows the topology split: **-2.23%, +2.73%, +6.71%** scarcity at
R7/R15/R23, compared with the earlier separate campaigns' -1.48%, +2.37%, +8.58%.
These are separate matched comparisons; their baselines are not pooled. Center plentiful is now
-1.92%, -0.56%, -1.31%. Earlier approximately neutral plentiful aggregates do not establish a
universal guardrail pass for this batch.

Sixteen of 17 live policies lose on pooled **R7/S7/W0**; only sample 0013 is positive (+1.02%).
That sample instead loses -8.90% at R15/S1/W96 and has negative R7/R15 scarcity aggregates.
Sample 0002 nearly eliminates scarce losses (worst -0.27%) but loses -16.86% at R7/S7/W0 in
both blocks. Sample 0011 improves eight scarce means but loses -4.16% at R15/S1/W96 and
-14.81% at R7/S7/W0. The tradeoff is real in the measured comparisons; its mechanism is unknown.

[Complete comparison](../../../../../experiments/cache-timing-live25-auto-v2-analysis/policy_comparison.tsv)
includes each topology/class block score, all scarce body changes, worst scarce/plentiful workload,
minimum topology scarcity, positive counts and coordinate/surface distance for every function.

## Body regimes, blocks and lower observations

Each cell below is pooled change followed by block 0 / block 1 changes, all versus matched OFF.

| Scarce workload | Center pooled (blocks) | 0008 pooled (blocks) | 0015 pooled (blocks) |
| --- | --- | --- | --- |
| R7-S1-W0 | +0.61% (-0.18% / +1.40%) | +6.58% (+9.52% / +3.63%) | +5.91% (+5.93% / +5.89%) |
| R7-S1-W96 | -7.30% (+2.22% / -16.77%) | +1.47% (+3.08% / -0.13%) | +130.40% (+132.36% / +128.44%) |
| R7-S1-W576 | +0.19% (-0.41% / +0.78%) | +1.40% (+2.30% / +0.51%) | -0.02% (-0.52% / +0.47%) |
| R15-S1-W0 | +9.71% (+10.42% / +8.99%) | +11.00% (+13.64% / +8.31%) | +11.42% (+16.36% / +6.38%) |
| R15-S1-W96 | -1.00% (+1.98% / -3.87%) | +3.81% (+5.20% / +2.47%) | -5.81% (-18.57% / +6.47%) |
| R15-S1-W576 | -0.19% (-0.27% / -0.11%) | -0.28% (-0.02% / -0.55%) | -0.60% (-0.91% / -0.30%) |
| R23-S1-W0 | +7.92% (+6.99% / +8.84%) | +7.56% (+11.13% / +4.02%) | +5.74% (+2.63% / +8.82%) |
| R23-S1-W96 | +13.50% (+16.24% / +11.03%) | +12.02% (+14.11% / +10.14%) | +16.92% (+22.85% / +11.57%) |
| R23-S1-W576 | -0.80% (-0.96% / -0.65%) | +0.15% (-0.24% / +0.55%) | +0.43% (+1.24% / -0.37%) |

Sample 0008's topology scarcity scores are positive in both blocks: R7 +4.92% / +1.32%,
R15 +6.13% / +3.35%, R23 +8.15% / +4.83%. Its plentiful losses prevent treating that breadth
as success for the complete objective. Sample 0015's R7 gain is consistent (+34.79% / +34.45%),
but its R15 aggregate changes sign (-2.08% / +4.13%). Two discovery forks do not establish
confirmation, especially after inspecting 17 parameter settings.

Sample 0001 illustrates why a pooled gain alone is insufficient. R7/S1/W96 gains +53.20%
pooled but splits +127.26% / -20.52%; its R7 scarcity aggregate splits +35.10% / -7.50%.
In contrast, sample 0015's R7/S1/W96 windows stay around 48.4..49.7 million executions/s in
both forks, versus OFF's 21.0..21.6 million. This is a sustained observed throughput difference
within those runs. It does not establish which runtime states or actual park/H values caused it.
No occupancy, fallback, state trace or inference-cost diagnosis follows from these logs.

The following cells are **minimum fork mean / minimum measurement window**, in millions of
executions/s. They are observed minima, not quantiles or future throughput guarantees.

| Workload | OFF | Center | 0008 | 0015 |
| --- | --- | --- | --- | --- |
| R7-S1-W96 | 21.270 / 21.045 | 17.786 / 16.939 | 21.342 / 21.308 | 48.819 / 48.421 |
| R7-S7-W0 | 396.462 / 376.252 | 365.422 / 351.496 | 346.074 / 338.752 | 353.284 / 334.507 |
| R7-S7-W96 | 64.940 / 64.801 | 66.163 / 66.043 | 56.034 / 56.007 | 64.588 / 64.568 |
| R15-S1-W96 | 13.901 / 10.756 | 13.885 / 10.373 | 14.625 / 13.699 | 11.320 / 8.429 |
| R23-S1-W576 | 24.924 / 24.040 | 24.685 / 24.241 | 24.863 / 24.426 | 25.005 / 24.727 |

[All workload details](../../../../../experiments/cache-timing-live25-auto-v2-analysis/workload_details.tsv)
retain pooled throughput, both individual fork means and changes, both minima and OFF values for
all 324 policy/workload cells. Every original window remains in the
[window table](../../../../../experiments/cache-timing-live25-auto-v2-evidence/measurement_windows.tsv).
No slow outcome was removed or replaced, and CV is not a reward.

## What the local fit learned, and its limits

The already-completed CPU fit uses the four tuning coordinates and **24 outputs**: six
topology/class log returns plus 18 individual workload log returns. It fits separate response
coefficients for each output, using four linear terms, four squares, six pair interactions and
an intercept. This avoids the earlier additive fixture/policy representation that could not
change parameter ordering by workload. It does not add quadratic terms to the runtime controller.
All four outer folds and the final development fit selected ridge **alpha 10** from `{0.1,1,10}`.

For a diagnostic added during this analysis, predict each held row using that output's mean on
training rows only. The following RMSE values are in **log-return units**, not percent gains,
confidence intervals or acceptance thresholds. They include both blocks of each held parameter.

| Output | Quadratic held RMSE | Training-mean held RMSE |
| --- | --- | --- |
| R7_scarce | 0.1099 | 0.1032 |
| R15_scarce | 0.0267 | 0.0260 |
| R23_scarce | 0.0496 | 0.0435 |
| R7_plentiful | 0.0320 | 0.0285 |
| R15_plentiful | 0.0153 | 0.0143 |
| R23_plentiful | 0.0163 | 0.0142 |

Across all 24 outputs the quadratic RMSE is **0.0801**, versus **0.0743** for the diagnostic.
R7/S1/W96 is particularly uncertain: **0.2735** versus **0.2657**. A smooth local fit has not yet
shown better held-parameter prediction than a no-response mean on any of the six aggregates.
This does not imply constant timing is optimal, erase measured policy effects, or prove that
four runtime parameters are inadequate. It limits trust in the optimizer's predicted improvements.
The diagnostic was added after completion and is development analysis, not an untouched test.

The split tests new parameter vectors on the same fixed 18 workload outputs, not new workload
families or hosts. Every held parameter retains its whole workload panel and both blocks; no
JMH window becomes an independent target. Baseline reuse creates correlated uncertainty. All
outcomes have now informed interpretation and proposals and are development evidence.
[Held predictions and diagnostics](../../../../../experiments/cache-timing-live25-auto-v2-analysis/held_prediction_diagnostics.tsv)
are reproducible from the retained outer predictions without fitting another model.

## Existing automatic proposals: predictions only

The runner already emitted [live25-auto-proposal-v1](../cache-timing/live25-auto-proposal-v1/candidate_manifest.json).
The frozen seed-20260910 search considered 4,096 Sobol points: 35 were too close to measured
points, zero failed surface checks, and 4,058 failed the declared predicted feasibility/improvement
filters. Three points remained on the Pareto frontier; diversity selection retained two.
The audit reproduced both proposals exactly. No coefficient was manually chosen for this report.

| Proposal suffix / role | R7 scarce | R15 scarce | R23 scarce | R7 plentiful | R15 plentiful | R23 plentiful |
| --- | --- | --- | --- | --- | --- | --- |
| 2997 / broad_scarcity | +23.05% | +2.76% | +7.07% | -0.97% | +1.05% | -0.36% |
| 3853 / R7_recovery | +14.95% | +2.47% | +6.06% | -0.92% | +0.81% | -0.43% |

These are full-fit predictions, **not measured results**. Both still predict modest R15 scarce
body losses: W96 about -1.5% and W576 about -0.4..-0.5%. Their large predicted R7 aggregate
improvements rely heavily on W96 (+76.32% and +47.68%). The workload floors prevent very large
predicted losses but do not prove broad improvement. Prediction errors above are large relative
to the plentiful guardrail margins, so predicted feasibility is not a guardrail pass.

Both optimizer choices move H's intercept toward its upper permitted end and increase its PHR
slope. Normalized H-intercept coordinates are 0.962 / 0.945, and H-slope coordinates 0.988 / 0.884.
They also weaken the magnitude of park's negative PHR slope. At PHR 0/1/2/4, proposal 2997 requests
park/H pairs 144707/366187, 100939/451386, 70410/556409, 34259/845445 ns; proposal 3853 requests
129570/370649, 89133/451734, 61316/550558, 29017/817793 ns. Both have zero support-grid clamp
occupancy. These are synthetic function evaluations, not timing occupancy measured during runs.
Their proximity to an H search boundary is a direction to test, not permission to widen it now.

The existing frozen follow-up has exactly **four arms**: POLICY_OFF, exact live-25 center,
proposal 2997 and proposal 3853; the same 18 R7/R15/R23 fixtures and two balanced blocks total
**144 JVMs / 720 measurement windows**. It does not include sample 0015 or 0008, so it can test
improvement over OFF/center, but cannot establish superiority to those sampled policies through
a same-campaign matched comparison. Its [lock](../cache-timing/live25-auto-proposal-v1/lock.json),
[generation receipt](../cache-timing/live25-auto-proposal-v1/generation.json) and
[prediction table](../../../../../experiments/cache-timing-live25-auto-v2-analysis/proposal_predictions.tsv)
remain unchanged and no follow-up output directory exists.

## Suggested next steps, in order

1. **Continue the automated local loop with a bounded measured check.** Review the already-frozen
   two-proposal follow-up, then assign its 144-JVM steady-state campaign to a runner. Retain OFF
   and the exact center, all three topologies and all body regimes. This tests whether the
   optimizer can retain observed scarcity benefits while repairing plentiful behavior. Treat the
   proposals as uncertain experiments, not fitted winners. Do not generate another initial pool,
   manually choose weights, widen bounds or start a model tournament.
2. **Make the next review explicit about the unresolved fixtures.** Check R7/S1/W96 in both
   blocks, R7/S7/W0 and W96 for repeated plentiful losses, R15/S1/W96 for scarcity breadth, and
   R23/S1/W576 for expensive-body regressions. Report all raw fork means, all windows, both
   minima, per-body changes and topology/class aggregates. Do not require every fork to improve,
   but do not let a large W96 gain hide a persistent body or plentiful regression.
3. **Compare predicted and measured returns before trusting another proposal.** After collecting
   the next batch, audit all 24 forecast residuals. Use the generic infrastructure to retain the
   complete measurement history and OFF identities if a further fit is warranted. Do not average
   unrelated campaign baselines or refit only the latest few points. Any later proposals must
   continue excluding every measured vector and remain measured before acceptance. If forecasts
   miss their guardrail repairs, reassess the local fit/evidence before generating another batch;
   that is not proof that the live-25 family failed.
4. **Confirm only the strongest measured tradeoff.** If a proposal improves scarce-source breadth
   without material plentiful harm, prepare additional bounded independent replication against
   OFF and relevant measured references. If claiming it beats sample 0015 or 0008, include that
   reference in a newly reviewed matched confirmation rather than comparing cross-campaign ranks.
   Keep any small negative plentiful results explicit; stronger scarcity gains may justify them.
5. **Gate dynamics and production on that result.** Only after steady-state confirmation use the
   existing persistent body/source/combined transitions in both directions with the same scheduler
   kept alive. Dynamic improvement is optional; material harm, stickiness or failure to recover is
   not acceptable. No current static run establishes dynamic behavior. Production defaults,
   participation, contention evidence, runtime inputs/terms, output bounds and ownership remain
   unchanged. CACHE remains a hybrid execution mode whose local timing function decides how to
   idle; aggregate participation emerges without a global target or coordinator.

This task audited and documented completed measurements, the existing fit and existing proposals.
It did not launch benchmarks, replace frozen inputs, refit coefficients or change runtime code.

To reproduce this read-only evidence audit from the repository root:

```bash
PYTHONPATH="$PWD/python/pareto-weight-calibration/src" \
  /tmp/euhedral-auto-tuning-20260909-venv/bin/python \
  experiments/cache-timing-live25-auto-v2-analysis/analyze.py
```

The script reparses retained logs into disposable scratch, checks exact table reproduction and
re-evaluates the existing proposals without emitting another campaign. It rewrites only its
derived analysis tables and audit receipt. Documentation links, frozen-file hashes and
`git diff --check` passed; no Java or scheduler source was changed, so Gradle was not rerun.
