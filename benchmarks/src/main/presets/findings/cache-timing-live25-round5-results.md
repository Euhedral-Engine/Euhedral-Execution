# CACHE live-25 round5: measured findings and next steps

## Finding

**Sample 7931 is the strongest development lead for broad scarce-source behavior, but no
proposal is ready for production or dynamics.** It improves all nine pooled scarce workloads,
six in both independent blocks, with topology scarcity gains of **R7 +4.06%, R15 +6.79%,
R23 +8.97%** against same-campaign POLICY_OFF. Its plentiful aggregates are relatively small
losses (-2.67%, -1.17%, -0.77%), but those averages hide R7/S7/W0 **-6.76%**, with both blocks
negative, and R15/S15/W0 **-5.96%**, combining a much worse first block with a positive second.
Those individual outcomes prevent a clean guardrail pass.

The coverage addition, **sample 136**, successfully tested the previously omitted sample 0015
neighborhood. It did not produce a better practical policy: scarce gains are +2.37%, +5.58%,
+3.83%, while plentiful losses are -5.53%, -3.53%, -2.64%. Its repeated R7/S7/W96 and
R15/S15/W0 losses are material. Coverage supplied useful negative evidence; it did not imply
that the neighborhood was safe or that every point inside it would improve throughput.

Continue bounded, evidence-driven tuning of the family. Feed these outcomes back into the
JSON-driven offline pipeline before buying more JVM time. Keep runtime terms, timing bounds,
production defaults and participation unchanged. No new benchmarks, model refit or proposal
campaign was performed during this analysis.

## Complete policy comparison

All changes below use the current campaign's POLICY_OFF. For each workload, average its two
fork means, divide by the average of its two OFF fork means, and take the log. The topology/class
geometric change is `100 * expm1(mean(workload log ratios))`. Each class has three body fixtures.
Scarce and plentiful are separate decisions; there is no equal-weight overall winner score.
The positive counts distinguish pooled means from positivity in both matched blocks.

| Policy | R7 scarce | R15 scarce | R23 scarce | R7 plentiful | R15 plentiful | R23 plentiful | Scarce positive / both blocks | Worst plentiful workload |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| center | +4.13% | +5.61% | +3.35% | +0.27% | -2.40% | -3.88% | 8/9; 5/9 | -10.75% |
| 2574 | +4.38% | +2.23% | +8.14% | -3.49% | -5.30% | -0.39% | 7/9; 6/9 | -14.02% |
| 4755 | +3.65% | +5.42% | +7.19% | -4.13% | -3.20% | -1.67% | 5/9; 3/9 | -11.63% |
| 6321 | +0.69% | +4.77% | +7.07% | -3.00% | -3.60% | -1.26% | 7/9; 5/9 | -12.00% |
| 3799 | +4.29% | +8.25% | +12.47% | -11.11% | -3.59% | -1.75% | 8/9; 6/9 | -22.08% |
| 7931 | +4.06% | +6.79% | +8.97% | -2.67% | -1.17% | -0.77% | 9/9; 6/9 | -6.76% |
| 6904 | +5.21% | +6.07% | +6.99% | -2.33% | -2.59% | -0.99% | 6/9; 4/9 | -8.21% |
| 136 | +2.37% | +5.58% | +3.83% | -5.53% | -3.53% | -2.64% | 7/9; 4/9 | -11.48% |

All seven proposed functions have positive pooled scarcity aggregates on all three topologies,
but this does not establish broad body-regime improvement or acceptable plentiful behavior.
Sample 3799 has the strongest R23 scarcity gain (+12.47%) and loses -22.08% on R7/S7/W0
(-24.32%, -19.66% by block), plus -12.39% on R15/S15/W0 (-15.45%, -9.00%). Its aggregate
scarcity gains cannot excuse those guardrail failures. Sample 6904's R7 scarcity aggregate also
conceals R7/S1/W0 -4.79%; sample 6321 loses -4.03% on R23/S1/W576.

## Sample 7931: body breadth and remaining risks

| Scarce workload | Pooled change versus OFF | Block 0 / block 1 |
| --- | ---: | ---: |
| R7/S1/W0 | +7.95% | +9.13% / +6.85% |
| R7/S1/W96 | +3.82% | -2.83% / +10.89% |
| R7/S1/W576 | +0.53% | +0.23% / +0.84% |
| R15/S1/W0 | +15.13% | +17.16% / +13.09% |
| R15/S1/W96 | +5.68% | +5.06% / +6.34% |
| R15/S1/W576 | +0.10% | +0.29% / -0.10% |
| R23/S1/W0 | +26.79% | +15.12% / +41.24% |
| R23/S1/W96 | +0.74% | +1.57% / -0.06% |
| R23/S1/W576 | +1.31% | +0.85% / +1.77% |

The no-op gains remain much larger than the expensive-body changes. Small expensive-body
positives are close to neutral, not confirmed practical gains. The unresolved medium-body
behavior remains relevant, especially R7/S1/W96 and the small R23/S1/W96 result.

| Important plentiful workload | OFF / 7931 pooled throughput, M executions/s | Pooled change | Block 0 / block 1 | 7931 minimum fork / window, M executions/s |
| --- | ---: | ---: | ---: | ---: |
| R7/S7/W0 | 397.160 / 370.331 | -6.76% | -7.76% / -5.67% | 359.945 / 358.669 |
| R7/S7/W576 | 12.171 / 11.991 | -1.47% | -2.63% / -0.30% | 11.932 / 11.839 |
| R15/S15/W0 | 774.818 / 728.610 | -5.96% | -15.18% / +4.25% | 690.776 / 672.962 |
| R23/S23/W0 | 1142.277 / 1123.002 | -1.69% | -3.25% / -0.14% | 1100.487 / 1098.236 |

Observed minima are retained observations from two JVMs and ten nested windows per workload,
not quantiles or future guarantees. All policies' raw means, both references, block results,
minimum fork means and minimum windows are in the linked tables below.

Against the **same-campaign live-25 center**, 7931's scarcity changes are **-0.07%, +1.12%,
+5.44%** at R7/R15/R23. R7 therefore shows no overall parameter-tuning improvement over the
center. R15 improves in both blocks; R23 combines +1.08% and +10.52%. The center itself now
scores +4.13%, +5.61%, +3.35% against OFF, and suffers plentiful no-op losses at R15 (-7.86%)
and R23 (-10.75%). Cross-campaign baselines must not be pooled or used to claim that the exact
same center has become topology-robust. These repeats are campaign-variation evidence.

## What the coverage experiment and predictions established

The six original proposals and added point were frozen before this campaign. Sample 136 is
0.141907 normalized distance from sample 0015, inside the 0.30 coverage radius; it is a new
function, not a repeat of the historical point. Its scarce changes against the current center
are **-1.69%, -0.03%, +0.47%** at R7/R15/R23. R7 is worse in both blocks. Against OFF,
R7/S7/W96 loses **-9.00%** (-15.51%, -2.65%) and R15/S15/W0 loses **-11.48%**
(-14.40%, -8.25%). Do not advance this point merely because it represents a known region.
Do not label the entire sample 0015 neighborhood rejected from one measured neighbor either;
the declared later-evidence rejection criteria must be evaluated on retained measurements.

The surrogate narrowed the space to seven measurable choices, including one with broader
scarcity gains, but none demonstrated an unqualified production guardrail pass. This batch
alone does not establish superiority to random exploration: it contains no matched random
proposal control. The coverage rule worked as intended; throughput decided the outcome.

The [prediction comparison](../../../../../experiments/cache-timing-live25-round5-analysis/prediction_comparison.tsv)
retains all 336 candidate/output comparisons (7 x 48), including center-relative and OFF-relative
systems. For model comparisons it averages matched-block log returns, matching the response
representation; those numbers can differ from the pooled-mean ratios in the main tables.
For sample 136, predicted R15/S15/W0 was -0.94% versus measured -11.38% in that log-return
representation. Sample 7931's predicted R23 scarcity was +0.74% versus measured +9.18%.
These are further reasons to use models for direction and sampling, not acceptance.
No reliability class or threshold was changed based on this post-selection analysis.

## Next steps, in order

1. **Ingest round5 as development evidence.** Use the consolidated archive and existing generic
   historical adapter. Preserve every fork, both matched control systems and all 48 outputs;
   verify compatibility and group all repetitions of each exact theta together. Do not overwrite
   round5's frozen predictions or retroactively report these outcomes as held tournament scores.
2. **Refit and evaluate offline before another campaign.** Use the existing JSON tournament,
   fixed four-dimensional bounds and family representation. Reassess held-theta ranking,
   campaign transfer and reliability with these new measured points. In particular, retain
   R7/R15 plentiful no-op and R7 plentiful medium-work losses as individual responses, rather
   than allowing topology aggregates to hide them. No new runtime feature or hand-picked
   coefficient is justified by this batch.
3. **Use 7931 as a measured development anchor, not a deployable winner.** It is the best current
   breadth lead and a useful region for the automatic proposal layer. Preserve other supported
   tradeoff regions; penalize repeatedly bad local neighborhoods using the existing measured
   history rules. The new 136 and 3799 outcomes should reduce enthusiasm for those exact points.
   Do not immediately mark whole broad clusters permanently bad or harden weak surrogate vetoes.
4. **After offline review, prepare one bounded follow-up.** Python should choose any new theta.
   Carry OFF and the exact center; retain 7931 if confirming the current best measured point.
   Prioritize cross-topology scarce bodies plus R7/S7/W0, R7/S7/W96 and R15/S15/W0 guardrails.
   Resolve whether those repeated/variable losses can be avoided before dynamics. Define fixtures
   and replication before execution, keep balanced JVM blocks, and retain all slow outcomes.
5. **Gate dynamics and production on actual returns.** A small plentiful loss may be acceptable,
   but repeat losses of the observed size cannot disappear into an average. Use persistent
   transitions only after a candidate makes an acceptable steady-state tradeoff. Do not abandon
   the live-25 family based on near-neutral body misses, and do not promote this batch directly.

## Verification, aggregation and cleanup

The independent audit verified **324 unique forks = 9 arms x 18 workloads x 2 blocks** and all
**1,620 measurement windows**. Every raw config/log matches its collected SHA-256; every fork
mean was recomputed from all five original execution windows. All declared trial treatments,
schedules, labels and origins match the frozen harness. Independently calculated pooled tables,
class summaries and both observed minima agree with collection. Launch lock, harness, sources
and topology identities agree; all 26 current JAR hashes match launch. Logs uniformly report
JMH 1.37 / OpenJDK 21.0.2. No slow run or window was removed, and no failed/incomplete fork
was silently dropped. These are static CONTINUOUS fixtures, with no dynamic conclusion.

The raw campaign and collected evidence were merged losslessly into the existing
[cache-run-data.tsv](../../../../../experiments/cache-run-data.tsv). It now contains **2,454
queryable run rows, 12,770 nested measurement windows and 5,015 checksummed original artifacts**.
All prior rows were preserved exactly. New rows match the collected fork means/windows;
every newly archived file was restored to a temporary directory and verified before cleanup.
The TSV contains compressed original logs/configs/identities in addition to queryable throughput.
This is a consolidated archive, not a claim that all historical policies are compatible training data.

Removed only the two redundant directories, containing **656 verified archived files**:

- `experiments/cache-timing-live25-region-coverage-round5`
- `experiments/cache-timing-live25-region-coverage-round5-evidence`

Retained readable analysis, scripts, findings, all frozen handoffs, source changes, and the
historical/surrogate datasets, model artifacts and locks needed for the next offline revision.
The archive is 63,818,800 bytes; SHA-256:
`592da0f3e3f2342d1029b3d8fddd219915b627e06df1b2a8896da3740aade2c3`.

- [Policy summary](../../../../../experiments/cache-timing-live25-round5-analysis/policy_summary.tsv)
- [Both-reference workload means and minima](../../../../../experiments/cache-timing-live25-round5-analysis/workload_comparisons.tsv)
- [Every matched block and its nested windows](../../../../../experiments/cache-timing-live25-round5-analysis/matched_blocks.tsv)
- [Separate topology/class and block summaries](../../../../../experiments/cache-timing-live25-round5-analysis/topology_class_summary.tsv)
- [Audit](../../../../../experiments/cache-timing-live25-round5-analysis/audit.json),
  [cleanup inventory](../../../../../experiments/cache-timing-live25-round5-analysis/cleanup.json), and
  [reproducible analysis](../../../../../experiments/cache-timing-live25-round5-analysis/analyze.py)
- [Frozen proposals](../../../../../experiments/surrogate-live25-round5/proposals.json) and
  [original preparation findings](cache-timing-live25-region-coverage-round5.md)

Analysis can run directly from the aggregate after cleanup:

```bash
python3 experiments/cache-timing-live25-round5-analysis/analyze.py
python3 -I python/pareto-weight-calibration/src/pareto_weight_calibration/run_archive.py verify \
  --archive experiments/cache-run-data.tsv
```

Use `-I` for the standalone archive command to avoid local module names shadowing the standard library.
The standalone archive script also supports `restore --archive ... --destination <new-directory>`
for exact historical file recovery. No runtime/Python package source was changed by this task;
validation used independent raw-data calculations, archive preservation/restore checks and
`git diff --check`, rather than a new benchmark or Gradle build.
