# CACHE joint 5D park-body: measured findings and next steps

## Finding and completed scope

**Sample 3381 is the most promising measured park-body proposal in this batch, but no candidate
establishes a sufficiently broad scarcity/plentiful tradeoff for advancement to dynamics or production.**
3381 gains +4.54% geometrically across the nine scarce workloads versus POLICY_OFF, +0.84% versus
same-campaign 7931, and +1.23% versus the original live-25 center. Its three scarce topology aggregates
are positive, but only five of nine scarce workload means improve. R15/S1/W96 still loses -6.97%.
R7/S7/W0 loses -11.90%, and R15/S15/W0 loses -7.47% with both blocks worse.
Those are material individual plentiful regressions; a favorable aggregate cannot make them neutral.

This completes the **primary park-body stage only**: four generated 5D proposals plus POLICY_OFF,
exact original live-25 and exact 4D anchor 7931. The secondary park-PHR-body stage is still prepared,
with no corresponding measurement directory. This report supersedes the primary-stage next steps
in [the offline joint-5D findings](cache-timing-joint5d-v1.md); its frozen predictions and historical
results remain unchanged. No JMH, new model fit, new candidate generation or runtime change was
performed during this analysis.

Joint movement has now been measured away from the identifying screen's single 4D anchor. That is
useful development evidence. It does **not** isolate the contribution of the fifth coefficient:
all four base coordinates also changed, and none of the four proposals has a matched zero-fifth
version at its own base coordinates. The result neither proves that the fifth term earned its cost
nor establishes that the park-body family failed.

## Evidence and accounting

Verified 252 independent JVM forks = seven arms x 18 workloads x two balanced blocks; all 1,260
nested measurement windows remain. Each fork mean was independently recomputed from its five raw JMH
`executions` windows. Every trial config/log hash, all frozen trial fields, launch identity, harness,
source hashes and topology matched. All 26 current distribution JAR hashes match the launch;
logs report JMH 1.37 and OpenJDK 21.0.2. Recollection into a temporary directory reproduced all seven
collected evidence files byte-for-byte. No slow fork/window was removed or rerun.

R7/R15/R23 use the established CPU placements (logical CPU lists 2..15, 2..23 and 2..31,
respectively), resolved to 7/15/23 physical workers; harness CPU is 0. At each topology, fixtures are
S1 and S=R crossed with W0/W96/W576. W is synthetic work, not measured body nanoseconds or runtime PHR.
AUTO participation has no forced cutoff, observers are disabled, and the static lifecycle is
CONTINUOUS. Schedule: three 2-second warmups, five 2-second measurements, one million executions
per invocation, and the 120-second invocation guard. This is not dynamic validation or a wall-clock
guarantee. No runtime state occupancy or mechanistic inference follows from throughput-only logs.

For a workload, pool the two fork means for each arm, divide by the same-campaign reference mean,
and take the log. Topology/class geometric changes average those workload logs before exponentiating.
Blocks use their own matched reference fork. Pooling and logging do not commute. Windows are nested
observations, not independent replicates; matched blocks are ordering blocks, not simultaneous runs.
Scarce breadth and plentiful harm are reported separately. No equal-weight overall winner or CV
objective is used.

## Measured results versus POLICY_OFF

| Policy | R7 scarce | R15 scarce | R23 scarce | Scarce pooled / positive / both blocks | R7 plentiful | R15 plentiful | R23 plentiful |
| --- | --- | --- | --- | --- | --- | --- | --- |
| live-25-center | +1.29% | -1.29% | +10.14% | +3.27% / 5/9 / 3/9 | -1.96% | -1.59% | +1.51% |
| anchor-7931 | +5.06% | +0.09% | +5.95% | +3.67% / 7/9 / 4/9 | -7.34% | -1.13% | +0.49% |
| 3381 | +3.77% | +1.77% | +8.17% | +4.54% / 5/9 / 5/9 | -3.10% | -2.30% | +2.16% |
| 611 | +3.55% | -1.27% | +4.56% | +2.25% / 5/9 / 3/9 | -5.15% | -1.99% | +1.03% |
| 274 | +1.74% | -1.59% | +2.65% | +0.91% / 6/9 / 5/9 | -2.51% | -1.53% | +1.62% |
| 4598 | +0.81% | -2.88% | -1.03% | -1.04% / 3/9 / 3/9 | -6.09% | -0.71% | +0.81% |

"Both blocks" counts workloads with positive changes in each independent matched block; it is a
robustness description, not a requirement that every individual fork improve.

| Scarce fixture | live-25-center | anchor-7931 | 3381 | 611 | 274 | 4598 |
| --- | --- | --- | --- | --- | --- | --- |
| R7-S1-W0 | -4.26% | +5.99% | -0.10% | -0.48% | -4.49% | -0.95% |
| R7-S1-W96 | +9.77% | +9.56% | +12.32% | +12.38% | +9.69% | +4.34% |
| R7-S1-W576 | -1.12% | -0.15% | -0.42% | -0.73% | +0.52% | -0.87% |
| R15-S1-W0 | +7.63% | +11.49% | +13.48% | +5.71% | +7.75% | -1.13% |
| R15-S1-W96 | -10.53% | -10.18% | -6.97% | -9.17% | -11.67% | -7.64% |
| R15-S1-W576 | -0.13% | +0.12% | -0.16% | +0.24% | +0.14% | +0.33% |
| R23-S1-W0 | +9.08% | +12.65% | +10.08% | +8.92% | +8.35% | +3.79% |
| R23-S1-W96 | +17.77% | +1.63% | +8.32% | +8.52% | +16.57% | -1.51% |
| R23-S1-W576 | +4.00% | +3.88% | +6.14% | -3.28% | -14.37% | -5.17% |

- **3381:** improves all three R23 scarce regimes in both blocks. Its R7 no-op (-0.10%) and
  R7/R15 expensive (-0.42%/-0.16%) misses are near neutral, but the R15 medium-body miss is not.
  Against 7931, scarce aggregates change -1.23% at R7, +1.68% at R15 and +2.09% at R23.
  R7 plentiful improves +4.58% relative to 7931, while R15 plentiful worsens -1.19%.
  This is a tradeoff improvement in some regions, not dominance of the 4D reference.
- **611:** gains +2.25% overall scarcity versus OFF but loses -1.37% versus 7931; R15 scarcity is
  negative and R7 no-op plentiful loses -19.06%. The second computational basin is not presently
  a better measured region.
- **274:** six positive scarce workload means conceal R23/S1/W576 at -14.37% pooled, combining
  +10.49% and -37.62% by block. Its +16.57% R23 medium-body gain cannot hide that loss.
- **4598:** the disagreement/exploration point loses -1.04% scarce versus OFF and -4.54% versus 7931;
  only three scarce means improve. Retain the negative result as evidence against this sampled region.
- **4D references:** 7931 itself loses -24.68% at R7/S7/W0 and -10.18% at R15/S1/W96; original
  live-25 loses -9.32% and -10.53%, respectively. Neither reference is a proven safe production
  alternative. Their repeated measurements must remain campaign-qualified; do not pool OFF
  baselines across campaigns or silently select a new universal center.

All four proposals lose R7/S7/W0 in both blocks. Every proposal's R15/S1/W96 pooled mean is negative.
These repeated problematic fixtures remain explicit development constraints even when a topology's
three-workload geometric aggregate is positive.

## Block variation and observed lower outcomes

| 3381 fixture | Pooled vs OFF | Block 0 | Block 1 |
| --- | --- | --- | --- |
| R7-S1-W0 | -0.10% | +4.43% | -4.43% |
| R7-S1-W96 | +12.32% | +25.61% | +1.23% |
| R7-S1-W576 | -0.42% | +0.23% | -1.08% |
| R7-S7-W0 | -11.90% | -4.08% | -20.08% |
| R7-S7-W96 | +4.54% | +11.13% | -1.21% |
| R15-S1-W0 | +13.48% | +10.18% | +16.62% |
| R15-S1-W96 | -6.97% | -17.99% | +6.16% |
| R15-S1-W576 | -0.16% | -0.01% | -0.31% |
| R15-S15-W0 | -7.47% | -7.84% | -7.07% |
| R23-S1-W0 | +10.08% | +14.47% | +5.59% |
| R23-S1-W96 | +8.32% | +13.73% | +3.34% |
| R23-S1-W576 | +6.14% | +10.55% | +2.01% |

For 3381, R15/S1/W96 is -17.99% in block 0 and +6.16% in block 1. Its R7/S7/W0 loss is -4.08%
and -20.08%; R15/S15/W0 loses -7.84% and -7.07%. These results do not support treating the guardrail
problem as only a single slow outcome. Two discovery forks still leave small aggregate advantages
inconclusive, especially after selecting among four proposals.

The following minima are millions of executions/s: minimum fork mean / minimum nested window.
They are observed minima from two forks and ten windows, not estimated quantiles or future guarantees.

| Fixture | OFF minimum fork/window | 7931 minimum fork/window | 3381 minimum fork/window |
| --- | --- | --- | --- |
| R7-S7-W0 | 423.967 / 420.886 | 317.308 / 306.666 | 338.832 / 333.963 |
| R15-S1-W96 | 13.778 / 13.432 | 12.690 / 10.481 | 13.457 / 13.244 |
| R15-S15-W0 | 740.693 / 709.086 | 723.178 / 690.692 | 688.300 / 657.582 |
| R23-S1-W576 | 22.836 / 18.769 | 23.936 / 19.925 | 24.908 / 24.402 |

## Frozen forecasts versus measurement

The 48 frozen outputs per proposal are compared to the mean of matched block log returns, matching
the response representation used by the model. These measured percentages differ slightly from the
pooled workload tables above. The complete 192-row table retains prediction, observed return,
reliability, contribution and model disagreement. Disagreement is not a confidence interval.

The models gave direction worth testing, but remained imperfect:

- 3381 R15 scarcity: predicted +4.65%, measured +1.83%; R15/S1/W96 predicted +2.02%, measured -6.69%.
- 611 R15 scarcity: predicted +5.05%, measured -1.16%; R7/S7/W0 predicted -4.12%, measured -19.30%.
- 274 R15 scarcity relative to 7931: predicted +8.35%, measured -1.63%.
- The nearly constant R7 scarce forecast (+2.00% for each proposal) did not provide useful ordering.

The frozen 0 HARD / 22 SOFT / 26 UNRESOLVED classifications explain why uncertain regions were
measured rather than vetoed. They do not soften the measured losses. This four-proposal round cannot
establish that guided search beats blind selection: there is no matched random-search arm.
Do not use these selected points as an untouched validation set after adding them to training.

The compatible park-body design now has **32 unique theta, six with nonzero fifth delta**, versus
28/two before this round. The normalized quadratic design rank rises from 17/21 to 21/21, with
condition number approximately 2,166. Full rank removes the exact algebraic deficiency; it does not
make four new joint points dense interaction evidence or establish a reliable quadratic surrogate.

## Next steps, in order

1. **Review and run the existing bounded secondary park-PHR-body stage next.** Park-body has not
   clearly dominated the 4D controls or resolved the material guardrails, so the already prepared
   secondary hypothesis remains worth measuring. It contains samples 2531, 1812 and 6756 plus
   POLICY_OFF, exact original live-25 and exact 7931: six arms x 18 workloads x two blocks =
   **216 JVM forks**. It has not been executed by this task. Use its frozen handoff and perform
   the existing source/hash/topology checks; do not bypass a mismatch or automatically refreeze.
2. **Preserve 3381 as a park-body development region, alongside the measured 4D references.** Its
   matched gains warrant retaining the hypothesis, but do not promote it, manually move coefficients,
   or advance to dynamics. The other three measured proposals remain training evidence, including
   all bad forks. Do not repeat the identifying one-anchor screen.
3. **After the bounded family comparison, ingest the new evidence into a new JSON-driven round.**
   Keep 4D history on each family's zero plane, add this park-body evidence only to its compatible
   family, retain same-campaign OFF/7931 targets, and keep exact theta grouped across campaigns.
   Refit the broad offline tournament and same-evidence 4D ablation, retain reliability-aware
   proposals and useful-region coverage, and let Python choose any subsequent five coordinates.
   Do not widen the box, combine the fifth terms or activate a sixth coefficient.
4. **Measure whether the fifth term earns its complexity.** If 3381 or a later 5D region remains
   promising, include its exact zero-fifth counterpart in a future compact matched comparison,
   together with OFF and the comparable 4D references. This distinguishes the fifth term's benefit
   from movement of the original four coordinates. Prioritize R15 medium-body scarcity and
   R7/R15 no-op plentiful behavior; do not let one large body-regime gain conceal those losses.
   Add replication only to measured finalists or unresolved practical comparisons.
5. **Keep acceptance evidence-based.** Continue a family only if joint tuning improves the achievable
   scarce/plentiful tradeoff; retain at most the two separate hypotheses. If neither earns the
   fifth coefficient, return to 4D. Final confirmation and persistent dynamics follow an acceptable
   steady-state tradeoff. Production defaults, participation, contention evidence, CACHE idle
   semantics and timing bounds remain unchanged.

The local function decides how each fragment idles; collective participation emerges from those
independent decisions. No global worker-count target or coordination is introduced.

## Reproduction, aggregation and cleanup

- [Independent provenance audit](../../../../../experiments/cache-live25-joint5d-v1-park-body-analysis/audit.json)
- [Reproducible analysis](../../../../../experiments/cache-live25-joint5d-v1-park-body-analysis/analyze.py)
- [Policy summaries against all three references](../../../../../experiments/cache-live25-joint5d-v1-park-body-analysis/policy_summary.tsv)
- [Pooled workload throughput and both minima](../../../../../experiments/cache-live25-joint5d-v1-park-body-analysis/workload_comparisons.tsv)
- [Every matched fork and nested windows](../../../../../experiments/cache-live25-joint5d-v1-park-body-analysis/matched_blocks.tsv)
- [Per-topology class and block comparisons](../../../../../experiments/cache-live25-joint5d-v1-park-body-analysis/topology_class_summary.tsv)
- [All frozen prediction consequences](../../../../../experiments/cache-live25-joint5d-v1-park-body-analysis/prediction_comparison.tsv)
- [Numeric support/rank](../../../../../experiments/cache-live25-joint5d-v1-park-body-analysis/support.json)
- [Validation and recollection](../../../../../experiments/cache-live25-joint5d-v1-park-body-analysis/validation.json)

The completed raw run and collected evidence are consolidated losslessly into
[cache-run-data.tsv](../../../../../experiments/cache-run-data.tsv). `recordType=run` provides
queryable fork throughput and nested windows; `recordType=artifact` preserves checksummed,
compressed originals including trial configs, logs, launch identity and collected tables.

Archive total: **2,970 run rows, 15,350 nested windows and 6,343 original artifacts**. This append adds 252 forks and 1,260 windows; every prior row was preserved exactly. SHA-256: `e6be0018e9ae465c79b4f52c71dbeb9fa9165d7e2b3c569459037d3f461feecd`.

Removed 512 verified redundant files from the completed raw-run and collected-evidence directories after byte comparison and on-disk restore verification. The [cleanup receipt](../../../../../experiments/cache-live25-joint5d-v1-park-body-analysis/cleanup.json) records every removed path and hash.

Retained readable findings/analysis, the current offline tournaments and locked task/model outputs,
and the shared joint5d preset wrapper: these still support the pending secondary campaign and paired
family/ablation review. No pending secondary preset was removed or changed. The primary handoff's
historical `prepared` status stays frozen; this report is its current execution status. Generated
Python bytecode caches were removed; no source, user-staged deletion, or unrelated preset was touched.

Reproduce the measured analysis/audit after cleanup with:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 experiments/cache-live25-joint5d-v1-park-body-analysis/analyze.py
PYTHONDONTWRITEBYTECODE=1 python3 experiments/cache-live25-joint5d-v1-park-body-analysis/audit.py
```

Validation: four joint-5D tests passed. Five older confirmation tests failed during setup because
`benchmarks/src/test/resources/cache-timing/live-v2/lock.json` was already missing; that path is
outside the cleanup inventory. Actual collection replay, independent audit and the pending secondary
handoff check passed. `git diff --check` passed. No Java/generated Java changed, so Gradle was not run.

Both scripts read archived originals when raw files have been removed. The next secondary handoff is
[park-PHR-body HANDOFF.md](../../../../../experiments/cache-live25-joint5d-v1/park-phr-body/benchmark/HANDOFF.md).
Execution requires the existing stage review; this analysis did not start it.
