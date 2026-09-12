# CACHE fixed-timing surface: findings and suggested next steps

## Finding

The completed fixed surface supports a workload-dependent timing search, but does not support
changing the production default or calling the local timing function learned.

The highest pooled workload-normalized score belongs to **500,000 ns park / 1,000,000 ns H**:
`J = 0.037961`, equivalent to a **+3.87% geometric throughput ratio** against the configured
15,000 ns / 1,000,000 ns baseline. It improves four of nine workload means and regresses five;
its worst regression is **-15.93% at 17 sources / 96 work units**. Both screening passes show
that regression. The second-ranked pair, 814,375 ns / 2,000,000 ns, gains +3.07% overall but
loses 14.76% at 23 sources / zero work. Neither is suitable as a universal replacement.

The aggregate is also sensitive to the pass: the leading pair scores **+7.99% in pass 0 and
+0.06% in pass 1**. Its single-source improvements survive both passes, but a durable aggregate
improvement has not been established by these two forks per treatment.

The offline fit selected a **constant throughput predictor**, not a state-dependent timing
policy. Its apparent recommendation of 15,000 ns / 250,000 ns is the scorer's tie-break among
equal predictions. It is not a learned optimum. The measured surface is useful evidence for
controls and exploration; the present surrogate is not reliable enough to direct deployment.

## Evidence checked

This analysis reads the completed fixed-surface campaign and its fit. It launches no new trials,
changes no runtime policy and does not discard slow forks. It supersedes the preparation-only
status in the handoff for the fixed surface; later policy and dynamic validation stages remain
unmeasured in the supplied timing artifacts.

Sources: [retained runs and launch identity](../../../../../experiments/cache-timing-fixed-surface/identity.json), [fork evidence](../../../../../experiments/cache-timing-fixed-evidence/arms.json), [per-workload table](../../../../../experiments/cache-timing-fixed-evidence/throughput_by_workload.tsv), [matched-pass table](../../../../../experiments/cache-timing-fixed-evidence/matched_pass.tsv), [policy scores](../../../../../experiments/cache-timing-fixed-evidence/policy_score.tsv), [fit and outer predictions](../../../../../experiments/cache-timing-fixed-fit/run.json), [execution handoff](../cache-timing/HANDOFF.md).

Verified directly from the retained files:

- All 288 expected workload/timing/pass combinations are present exactly once: 16 timing pairs,
  nine workloads, two one-fork passes. All 1,440 measurement windows are retained.
- Recomputed each fork mean from its five JMH `executions` windows; verified each raw trial/log
  hash and original-trial copy; independently reproduced all 144 workload rows, 288 matched-pass
  rows and 16 policy scores. Warmups are excluded.
- Within each workload, only the declared park and half-life treatment values differ. Every
  trial has automatic participation, no forced cutoff, CONTINUOUS lifecycle and disabled
  observers. `cacheTimingFunction` is null in every run: these trials exercised **fixed H**,
  not prospective adaptive-H inference.
- The schedule is three 2-second warmups, five 2-second measurement windows, one million
  executions per invocation and a 120-second invocation guard. The harness uses CPU 0.
  Retained socket/core topology resolves the 30 listed logical CPUs to 23 physical workers.
- All logs report JMH 1.37 and OpenJDK 21.0.2. The launch identity's 26 binary hashes match
  the current retained distribution, and the frozen participation/decision-tree source hashes
  match. The prepared provenance's commit field is the older preparation commit, not a complete
  executed-code identity; the launch binary hashes provide the more precise evidence.
- The fit checksum, task hash and evidence provenance match. All 288 rows appear in exactly
  one outer held set, and outer training/held workload families are disjoint. No refit was
  needed for this analysis.

A JVM is the replication unit. Its five continuous windows are dependent observations, not five
independent trials. This is steady-state throughput evidence; there are no runtime
contention/PHR/body samples or dynamic transition results from this campaign. Configured sources
and synthetic work units cannot be substituted for measured PHR and body nanoseconds.

## Fixed policies ranked by the requested score

For each workload, pool its two equal-length fork means before forming the baseline ratio.
`J = mean_workloads(log(candidateMean / baselineMean))`; the percentage below is
`100 * (exp(J) - 1)`. It is not a raw-throughput average, nor the arithmetic mean of workload
percentages. Every workload has equal weight. The zero-work fixture does not dominate through
its much larger executions/s. Positive counts describe observed means, not significance tests.

| Park / H (ns) | J | Geometric change | Worst workload | Positive pooled means | Positive in both passes |
| --- | --- | --- | --- | --- | --- |
| 500,000 / 1,000,000 | 0.037961 | +3.87% | -15.93% | 4/9 | 4/9 |
| 814,375 / 2,000,000 | 0.030218 | +3.07% | -14.76% | 6/9 | 4/9 |
| 125,000 / 2,000,000 | 0.029339 | +2.98% | -10.70% | 4/9 | 3/9 |
| 125,000 / 500,000 | 0.022838 | +2.31% | -14.16% | 4/9 | 2/9 |
| 500,000 / 500,000 | 0.018606 | +1.88% | -25.64% | 4/9 | 3/9 |
| 500,000 / 250,000 | 0.015365 | +1.55% | -11.50% | 4/9 | 4/9 |
| 15,000 / 250,000 | 0.009147 | +0.92% | -15.43% | 5/9 | 4/9 |
| 500,000 / 2,000,000 | 0.006263 | +0.63% | -17.37% | 5/9 | 4/9 |
| 15,000 / 500,000 | 0.002420 | +0.24% | -17.35% | 4/9 | 3/9 |
| 15,000 / 1,000,000 | 0.000000 | +0.00% | +0.00% | 0/9 | 0/9 |
| 814,375 / 500,000 | -0.001401 | -0.14% | -22.85% | 4/9 | 4/9 |
| 125,000 / 1,000,000 | -0.008154 | -0.81% | -25.61% | 2/9 | 2/9 |
| 814,375 / 1,000,000 | -0.013243 | -1.32% | -26.55% | 4/9 | 3/9 |
| 814,375 / 250,000 | -0.016313 | -1.62% | -37.53% | 5/9 | 5/9 |
| 125,000 / 250,000 | -0.025962 | -2.56% | -24.05% | 2/9 | 1/9 |
| 15,000 / 2,000,000 | -0.042536 | -4.16% | -20.96% | 4/9 | 0/9 |

## What the aggregate leader does to each workload

All throughput values below are **million executions/s**. Both observed minima are shown:
minimum fork mean and minimum individual measurement window. B/C means baseline/candidate.
These observed minima describe this sample; they are not future performance guarantees.

| Sources / work units | Baseline mean | 500,000 / 1,000,000 mean | Change | Min fork mean B/C | Min window B/C |
| --- | --- | --- | --- | --- | --- |
| S1 / W0 | 16.441 | 23.338 | +41.95% | 13.863 / 22.915 | 13.615 / 22.265 |
| S1 / W96 | 11.011 | 10.557 | -4.11% | 10.754 / 8.556 | 10.522 / 8.144 |
| S1 / W576 | 18.753 | 24.653 | +31.46% | 15.476 / 24.284 | 14.236 / 22.776 |
| S17 / W0 | 830.475 | 780.018 | -6.08% | 808.265 / 775.447 | 806.749 / 725.431 |
| S17 / W96 | 123.305 | 103.661 | -15.93% | 123.180 / 93.053 | 120.033 / 91.265 |
| S17 / W576 | 25.513 | 25.595 | +0.32% | 25.435 / 25.517 | 24.921 / 25.085 |
| S23 / W0 | 1137.791 | 1048.360 | -7.86% | 1112.995 / 1047.910 | 919.225 / 1043.623 |
| S23 / W96 | 130.310 | 141.018 | +8.22% | 122.261 / 140.595 | 92.325 / 138.877 |
| S23 / W576 | 25.721 | 25.611 | -0.43% | 25.676 / 25.511 | 25.288 / 25.161 |

| Sources / work units | Pass 0 change | Pass 1 change |
| --- | --- | --- |
| S1 / W0 | +65.29% | +24.94% |
| S1 / W96 | -20.44% | +11.47% |
| S1 / W576 | +56.91% | +13.58% |
| S17 / W0 | -4.06% | -7.99% |
| S17 / W96 | -7.23% | -24.61% |
| S17 / W576 | +0.32% | +0.31% |
| S23 / W0 | -5.85% | -9.79% |
| S23 / W96 | +15.00% | +2.23% |
| S23 / W576 | +0.14% | -0.99% |

The single-source zero-work and 576-work cases improve in both passes, and their candidate
minimum windows exceed the corresponding baseline pooled means. The same literal lower-tail
comparison holds at 23 sources / 96 work. Conversely, at 17 sources / 96 work, the baseline's
minimum window (120.033 million/s) exceeds the candidate's mean (103.661 million/s).
At 23 sources / zero work, the candidate improves the observed minimum window while losing
mean throughput and minimum fork mean. That is a lower-tail/mean tradeoff, not a uniform win.

## Best observed timing pair per workload

These are maxima chosen after examining all 16 treatments. Selection makes them optimistic
screening results, not validated fixture-specific optima or a deployable lookup table.

| Sources / work units | Best sampled park / H (ns) | Baseline mean | Best mean | Pooled change | Pass 0 / pass 1 |
| --- | --- | --- | --- | --- | --- |
| S1 / W0 | 814,375 / 2,000,000 | 16.441 | 24.621 | +49.75% | +76.01% / +30.61% |
| S1 / W96 | 125,000 / 500,000 | 11.011 | 12.830 | +16.52% | +19.54% / +13.64% |
| S1 / W576 | 500,000 / 500,000 | 18.753 | 25.162 | +34.17% | +63.08% / +13.87% |
| S17 / W0 | 15,000 / 1,000,000 | 830.475 | 830.475 | +0.00% | +0.00% / +0.00% |
| S17 / W96 | 15,000 / 1,000,000 | 123.305 | 123.305 | +0.00% | +0.00% / +0.00% |
| S17 / W576 | 15,000 / 500,000 | 25.513 | 26.008 | +1.94% | +1.07% / +2.81% |
| S23 / W0 | 15,000 / 250,000 | 1137.791 | 1187.980 | +4.41% | +5.80% / +3.08% |
| S23 / W96 | 500,000 / 1,000,000 | 130.310 | 141.018 | +8.22% | +15.00% / +2.23% |
| S23 / W576 | 500,000 / 2,000,000 | 25.721 | 25.960 | +0.93% | +0.40% / +1.46% |

Several contrasts guide the next stage:

- At **one source / zero work**, every longer-park treatment has a higher pooled mean than the
  baseline. The best sampled pair is 814,375 / 2,000,000 ns: its minimum window is 23.827
  million/s versus a baseline mean of 16.441 million/s. This supports retaining a long-park
  response in the search space, without establishing a state boundary.
- At **one source / 96 work**, 125,000 / 500,000 ns leads (+16.52%, positive in both passes).
  The aggregate leader instead loses 4.11%, with opposite pass signs. Even within the
  one-source fixtures, one timing response does not dominate.
- At **one source / 576 work**, H matters strongly. At a fixed 500,000 ns park, changing H
  across the grid produces pooled changes of -11.50%, +34.17%, +31.46% and -17.37% as H
  rises from 250,000 through 2,000,000 ns. At the older 814,375 / 1,000,000 ns anchor, the
  result is -26.55%, negative in both passes. This is not evidence for a monotonic H rule.
- At **17 sources**, the existing baseline has the highest sampled mean at both zero and
  96 work. At **23 sources / zero work**, 15,000 / 250,000 ns is promising (+4.41%, both
  passes positive); its minimum window, 1,173.159 million/s, exceeds the baseline mean,
  1,137.791 million/s. This favors retaining short-park controls.
- The best changes at **17 and 23 sources / 576 work** are +1.94% and +0.93%. They are
  modest screening differences and should not drive broad range refinement.

Equal ratios do not imply interchangeable outcomes. The pairs 125,000 / 500,000 ns and
500,000 / 2,000,000 ns both have park/H = 0.25. The latter's pooled mean is 12.10% higher
at one source / zero work, but 13.36% lower at one source / 96 work and 13.64% lower at
23 sources / zero work. These observed differences support keeping both absolute timing axes;
they do not by themselves isolate the scheduler mechanism.

## Pass variation and uncertainty

The baseline itself rises from pass 0 to pass 1 on all nine workloads. The largest increases
are one source / zero work (+37.19%), one source / 576 work (+42.34%) and 23 sources / 96 work
(+13.17%). Balanced reversal controls linear order effects by design; it cannot prove the
absence of other changes across a long campaign. No telemetry here identifies their cause.

The best pooled policy's aggregate pass gains are +7.99% and +0.06%. The second-ranked policy's
are +5.97% and +0.25%. The third-ranked policy, 125,000 / 2,000,000 ns, leads pass 0 but does
not lead pass 1. Do not treat the small separation among their pooled scores as a settled rank.

Some individual outcomes are particularly unresolved. At one source / 576 work,
125,000 / 500,000 ns changes +53.63% in pass 0 and -61.78% in pass 1. Its pooled -14.16%
is retained, but is not a stable estimate of a local optimum. Keep the slow fork. Across
23-source / 96-work treatments, many apparent pooled improvements share a baseline whose
first fork contains a 92.325 million/s window; report the second-pass effects as well.
Low CV is not a reward, and high CV is not grounds for exclusion.

## What the fitted surface establishes

The generic runner fitted an offline fixture/timing model using three outer workload-family
folds and two inner folds. Its inputs are resolved workers, configured sources, work units,
log park and log H; all worker counts are R23, so worker-count generalization is untested.
Its final selection uses measured choice throughput divided by the best measured choice within
each workload. That choice-quality metric differs from the campaign's baseline-relative J.

| Inner-CV candidate | Mean fraction of sampled best | Worst workload fraction | Log-throughput MSE |
| --- | --- | --- | --- |
| constant | 91.36% | 62.97% | 2.7212 |
| Quadratic ridge, alpha=10 | 89.26% | 65.88% | 2.9311 |
| Quadratic ridge, alpha=1 | 89.72% | 65.88% | 3.1230 |
| Quadratic ridge, alpha=0.1 | 89.72% | 65.88% | 3.1605 |
| shallow_boost | 89.49% | 65.88% | 3.4155 |

The selected constant tree cannot split (`min_samples_split = 1000000` with 288 total rows).
It predicts equal throughput for every timing choice; the scorer breaks ties toward the
lexicographically smallest pair, 15,000 / 250,000 ns. Thus 91.36% choice quality is the measured
quality of that arbitrary tie choice, not an estimated timing response. This pair's actual
campaign score is only +0.92%, and it loses 15.43% at one source / 96 work.

The outer folds select quadratic ridge once and the constant twice. Their choice-quality means
are 82.62%, 90.37% and 86.68%, or **86.56% across all nine held workloads**. Several held choices
regress the fixed baseline. The worst constant choice recovers only 62.97% of the best sampled
throughput at one source / zero work. No examined nonconstant surrogate beats the constant on
the final mean choice-quality metric. This is evidence of weak useful generalization in this
fit, not proof that timings have no effect.

The exported evaluator is null. The fit supplies neither the eight local timing coefficients
nor an observed mapping from contention/PHR/body to optimal timings. A successful fit command
alone is insufficient reason to rank a large coefficient pool with this model.

## Suggested next steps

1. **Keep production timing and the participation classifier frozen.** Retain the two-dimensional
   bounds (park 15,000-814,375 ns; H 250,000-2,000,000 ns) for the next development stage.
   The extrema and workload reversals do not justify narrowing either interval or choosing a
   universal ratio. Do not translate configured sources/work units into runtime feature labels.
2. **Correct the offline control comparison before trusting surrogate rankings.** Add an empirical
   constant-timing comparator that chooses its pair from training workload families only, using
   the declared workload-normalized objective. Keep the constant throughput regressor as a
   no-response diagnostic, but label its tie choice explicitly. Report baseline-relative J
   alongside choice quality; the saved `lowerTail` metric is a workload-choice quantile, not
   an observed minimum JMH window. This is an offline fitting/evaluation change, not a new
   benchmark campaign or a participation tournament.
3. **Prepare the 32-policy live-function screening batch as exploration, not as a fitted winner.**
   Use the declared three-input normalization and seeded coefficient design. Retain the
   zero-coefficient live baseline plus informative live constants: 500,000 / 1,000,000 ns,
   814,375 / 2,000,000 ns, 125,000 / 500,000 ns and 15,000 / 250,000 ns. Include the fixed
   baseline too. These represent aggregate gain, single-source gain, lighter-work gain and
   short-park behavior. Their inclusion is a design recommendation, not a portable runtime rule.
   A matched fixed baseline is required because these null-function measurements do not establish
   live constant-function throughput parity. Declare allowed compatibility differences explicitly.
4. **Use those controls to resolve the material uncertainties without repeating the whole grid.**
   Prioritize whether the repeated single-source gains persist, whether the one-source / 576-work
   rank reversals recur, and whether 17-source / 96-work and 23-source / zero-work regressions
   remain. Keep two balanced screening forks for exploration; add replication only for finalists
   or a repeatedly material unresolved contrast. The proposed 32 x 9 x 2 search is 576 JVMs
   before controls; freeze its complete manifest and controls before execution.
5. **Fit the policy-return surrogate only after measuring actual local functions.** Use matched
   baseline ratios and complete workload-family holdouts. Never assign fork throughput to
   fragment states as independent rewards. Do not accept a predicted coefficient improvement
   without a scheduler run. Treat this fixed surface as development evidence once it informs
   the new candidate design.
6. **Validate at most two measured finalists on fresh workloads and dynamics.** Include resolved
   R7/R15 profiles and the persistent body-low/high, source-low/high and combined transitions.
   Preserve the scheduler across changes, compare throughput without adding observers, and
   require no unacceptable workload-specific or dynamic regression before considering deployment.
   Stop broad search if bounded new batches do not confirm a practical improvement.

No further campaign is executed or policy enabled by this findings document.

## Complete per-workload mean-change surface

Each cell is percent change from that workload's fixed baseline. S denotes configured parallel
sources; W denotes synthetic work units. R is 23 throughout. All negative cells remain visible.
Raw means, both minima and matched-pass values for every cell are linked in the source tables.

| Park / H (ns) | S1-W0 | S1-W96 | S1-W576 | S17-W0 | S17-W96 | S17-W576 | S23-W0 | S23-W96 | S23-W576 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 15,000 / 250,000 | -5.71% | -15.43% | +30.83% | -6.43% | -0.95% | +0.23% | +4.41% | +6.87% | +0.42% |
| 15,000 / 500,000 | -1.34% | -17.35% | +30.30% | -2.55% | -8.25% | +1.94% | -1.39% | +6.75% | +0.26% |
| 15,000 / 1,000,000 | +0.00% | +0.00% | +0.00% | +0.00% | +0.00% | +0.00% | +0.00% | +0.00% | +0.00% |
| 15,000 / 2,000,000 | -2.83% | -20.96% | +17.72% | -5.16% | -15.68% | +0.39% | -6.30% | +0.18% | +0.08% |
| 125,000 / 250,000 | +25.60% | -24.05% | -14.52% | -4.95% | -0.45% | -0.22% | -2.15% | +5.44% | -0.35% |
| 125,000 / 500,000 | +30.82% | +16.52% | -14.16% | -2.45% | -8.51% | +0.03% | -0.13% | +5.58% | -0.29% |
| 125,000 / 1,000,000 | +32.33% | -2.84% | -25.61% | -2.31% | -3.76% | -0.33% | -2.96% | +6.96% | -0.12% |
| 125,000 / 2,000,000 | +37.13% | -10.70% | +16.97% | -6.04% | -4.67% | +0.28% | -4.70% | +6.35% | -0.13% |
| 500,000 / 250,000 | +36.41% | +14.31% | -11.50% | -7.69% | -7.40% | +0.16% | -7.20% | +4.93% | -0.19% |
| 500,000 / 500,000 | +32.92% | -25.64% | +34.17% | -6.46% | -1.88% | +0.24% | -8.62% | +6.16% | -0.10% |
| 500,000 / 1,000,000 | +41.95% | -4.11% | +31.46% | -6.08% | -15.93% | +0.32% | -7.86% | +8.22% | -0.43% |
| 500,000 / 2,000,000 | +46.65% | +0.95% | -17.37% | -7.08% | -0.88% | +0.70% | -13.76% | +7.13% | +0.93% |
| 814,375 / 250,000 | +45.30% | +11.68% | -37.53% | -8.73% | -4.84% | +0.55% | -9.56% | +7.58% | +0.26% |
| 814,375 / 500,000 | +38.84% | -22.85% | +9.98% | -11.56% | -2.85% | +0.22% | -8.92% | +7.00% | -0.10% |
| 814,375 / 1,000,000 | +34.59% | +8.21% | -26.55% | -10.06% | -6.34% | +0.03% | -7.81% | +6.92% | -0.09% |
| 814,375 / 2,000,000 | +49.75% | +10.39% | +6.18% | -12.90% | -3.78% | +0.37% | -14.76% | +4.30% | +0.01% |

## Artifact fingerprints

| Artifact | Verified SHA-256 |
| --- | --- |
| arms | 6fd300c9087c9fa93328815aca1c6ea5fafe8e1a0cddcc461aafe9f3791fa0a0 |
| fit | 03f2449f30c2bcf76436832263f3282b021c471156c887d239a168a5482d913f |
| harness | b6f975af24f70be7a57e2c42fe1eb31dd69bb8c0be0cc4ebf30b60026ca406d5 |

Prepared from retained artifacts; no measurements were added by this analysis.
