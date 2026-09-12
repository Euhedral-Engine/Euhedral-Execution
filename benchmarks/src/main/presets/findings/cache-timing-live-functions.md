# CACHE live-function screening: findings and suggested next steps

Latest status: [R7/R15 confirmation is complete](cache-timing-topology-confirmation.md).
The newer evidence does not support advancing either finalist; keep POLICY_OFF and stop before
dynamics pending reassessment. Results and recommendations below describe the earlier stage.

The follow-up R23 confirmation is now complete. See [R23 findings and next steps](cache-timing-r23-confirmation.md):
live-25 improves all three pooled scarce workloads (+8.58% geometrically) with -0.46% plentiful
change; live-12 loses expensive-body scarcity in every block. The tables below remain the
original discovery screen and must not be pooled with confirmation forks.

## Finding and corrected objective

The two actually measured finalists remain **live-25** and **live-12**, with their exact frozen
coefficients. The confirmation objective is **broad, material scarce-source improvement while
plentiful-source execution stays approximately neutral**. A small plentiful-source loss can be
acceptable when scarcity gains are useful; a material or consistently negative plentiful result
is a guardrail failure. Do not turn all workloads into one equal-weight deployment objective.

CACHE is the hybrid execution mode. The second-stage timing function is a local idle policy:
each fragment chooses only its park duration and contention half-life after CACHE has been chosen
and available execution paths make no progress. Frequent short CACHE rests preserve limited
participation and more frequent cache draining; long rests approach temporary removal while the
fragment sleeps. Aggregate participation emerges from these independent local decisions. There
is no desired worker count, central participation target or coordination state.

The completed R23 screening results, regrouped against the actual fixed production path, are:

| Policy | Workload class | Geometric change | Positive workloads | Positive in both blocks | Worst workload | Worst change |
| --- | --- | ---: | ---: | ---: | --- | ---: |
| live-25 | PRIMARY | +7.11% | 2/3 | 1/3 | R23-S1-W96 | -1.78% |
| live-25 | GUARDRAIL | +0.19% | 2/3 | 1/3 | R23-S23-W576 | -0.10% |
| live-25 | INTERMEDIATE | +1.88% | 2/3 | 1/3 | R23-S17-W0 | -2.05% |
| live-12 | PRIMARY | +5.24% | 3/3 | 1/3 | R23-S1-W96 | +1.81% |
| live-12 | GUARDRAIL | -0.61% | 2/3 | 1/3 | R23-S23-W0 | -3.22% |
| live-12 | INTERMEDIATE | +3.10% | 3/3 | 2/3 | R23-S17-W0 | +0.34% |

PRIMARY is S1; GUARDRAIL is S23; S17 is intermediate diagnostic evidence. The
[class summary](../../../../../experiments/cache-timing-live-v1-analysis/scarcity_guardrail_summary.tsv)
also retains both pass aggregates. Neither scarcity aggregate can erase a weak body regime:
live-25's S1/W96 loss and -20.83% first pass need confirmation. Live-12 improves all three scarce
pooled workloads, but only one in both passes; its S23/W0 -3.22% pooled loss (-0.35%, -5.96% by
pass) is a specific guardrail concern. Its -0.61% plentiful aggregate alone is not an automatic
rejection. The two-fork screen is discovery evidence, not confirmation.

The old +3.02%/+2.55% overall scores and +1.51%/+1.05% comparisons to fixed 125k/500k remain
historical descriptions. Beating that diagnostic fixed pair is not the confirmation objective.
The failed offline ridge remains a diagnostic and will not propose variants. No coefficients
are refitted and no new policy pool is generated.

The zero-function control loses -5.14% overall and -36.98% at S1/W96. Its likely explanation is
timing-inference work without a behavioral benefit from different outputs. Because this path
is exercised where CACHE behavior makes it relevant, its cost need not be a uniform scheduler
tax. There is no need to isolate or prove that overhead. It is removed from the critical path
and from the new confirmation arms. Existing correctness/parity tests remain; throughput-level
fixed/live parity and unproven timestamp/order concerns are not research prerequisites.

POLICY_OFF means `cacheTimingFunction: null`, fixed production park 15,000 ns / H 1,000,000 ns,
and no live timing inference. POLICY_ON means exactly live-25 or live-12. Participation selection,
CACHE execution, queue ownership, evidence semantics and production defaults remain unchanged.
The [bounded confirmation handoff](../cache-timing/CONFIRMATION_HANDOFF.md) contains the prepared
R23, R7/R15 and gated persistent dynamic stages. R23 has now completed; R7/R15 and dynamics
remain unexecuted. The linked R23 report supersedes the preparation next steps below.

## Evidence and provenance

The executed input revision is `live-v2`; the experiment directory remains `cache-timing-live-v1`.
The earlier source-identity stop was resolved before execution. This report supersedes the
pre-execution status of the live handoff; fixed-surface findings remain historical evidence.

Sources: [frozen candidate manifest](../cache-timing/live-v2/candidate_manifest.json),
[frozen lock](../cache-timing/live-v2/lock.json),
[launch identity](../../../../../experiments/cache-timing-live-v1/identity.json),
[all fork observations](../../../../../experiments/cache-timing-live-v1-evidence/arms.json),
[original per-workload results](../../../../../experiments/cache-timing-live-v1-evidence/throughput_by_workload.tsv),
[matched passes](../../../../../experiments/cache-timing-live-v1-evidence/matched_pass.tsv),
[analysis and provenance audit](../../../../../experiments/cache-timing-live-v1-analysis/audit.json),
[reproducible analysis script](../../../../../experiments/cache-timing-live-v1-analysis/analyze.py),
[offline fit](../../../../../experiments/cache-timing-live-v1-fit/run.json).

Verified against the actual retained files during the original screening analysis (before the
confirmation preparation build; its newer source/JAR hashes are recorded separately):

- Exactly 684 policy/workload/pass observations: 38 policies x nine complete workload families
  x two one-fork passes. All 3,420 measurement windows remain; no slow fork or window was excluded.
- Every raw trial configuration and log matches its collected SHA-256 and original-trial copy.
  Each fork mean was independently recomputed from all five JMH `executions` windows. The complete
  342-row workload table, 684-row matched-pass table and 38-row score table reproduce byte-for-byte.
- The launch lock, harness, source identities and physical topology match live-v2. All 26 current
  distribution JAR hashes match the launch identity. All logs agree on JMH 1.37 and OpenJDK 21.0.2.
- Every trial matches the declared timing policy, schedule, JVM flags, labels and sweep identity;
  non-treatment calibration fields agree with the frozen harness. Actuator identity is uniform.
  Participation is AUTO without a forced cutoff; observers are disabled.
- R23 means 23 resolved physical workers from 30 logical CPU entries, with harness CPU 0.
  Sources are `{1,17,23}` and synthetic work units `{0,96,576}`. Lifecycle is CONTINUOUS, with
  three 2-second warmups, five 2-second measurements, one million executions per invocation and
  the 120-second invocation guard. These settings are not wall-clock performance guarantees.
- The generic fit uses 594 live-path forks: 32 functions plus the zero-function control. The
  other 90 fixed-path forks remain in the complete evidence and comparisons. Dataset/fit hashes
  agree, and every live fork appears in exactly one outer held set. Training and held workload
  families are disjoint; all forks and windows for a workload stay with that family.

JVM forks are independent replication units; their five windows are not five replicates.
Matched passes are ordering blocks, not simultaneous experiments. This screening set has now
been used for analysis and shortlist selection and is development evidence for subsequent work.
No R7/R15 or changing-workload dynamic validation is present in this campaign. No runtime state
traces establish state occupancy, fallback frequency or the timings actually visited by fragments.

## Historical overall score and complete policy comparison

For each workload, pool the two candidate fork means and the two fixed-baseline fork means, then
compute `log(candidateMean / baselineMean)`. `J` is the equal-workload mean of those nine logs;
geometric change is `100 * (exp(J) - 1)`. Pass scores use the corresponding pass's baseline fork.
Pooling and logging do not commute, so pooled J need not equal the mean of the two pass scores.
Raw throughput and both observed minima are retained separately; CV is not a reward.

The table is ordered by the historical pooled J for continuity only; it does not determine the
confirmation winner. PRIMARY and GUARDRAIL decisions use the separated summaries above.
All changes are against fixed production timing. Worst workload
means the worst pooled workload change, which can conceal a much worse individual fork.

| Policy | Response family | J | Geometric change | Pass 0 | Pass 1 | Worst workload change |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| live-25 | low_phr_long_park_short_h | 0.029749 | +3.02% | +2.69% | +2.97% | -2.05% |
| live-12 | phr_body_interaction | 0.025158 | +2.55% | +4.06% | +1.03% | -3.22% |
| fixed-125000-500000 | control | 0.014755 | +1.49% | -0.06% | +2.79% | -2.68% |
| live-02 | high_phr_short_park_short_h | 0.014160 | +1.43% | +5.30% | -3.30% | -13.72% |
| live-26 | contention_long_park | 0.012384 | +1.25% | +4.21% | -1.67% | -23.74% |
| live-22 | body_long_park | 0.008682 | +0.87% | +2.28% | -0.69% | -11.26% |
| live-14 | contention_body_interaction | 0.007392 | +0.74% | +4.40% | -3.20% | -13.14% |
| live-20 | body_short_park | 0.003755 | +0.38% | +0.80% | -0.12% | -19.96% |
| live-13 | contention_body_interaction | 0.003687 | +0.37% | +1.59% | -0.94% | -11.58% |
| live-29 | body_short_park | 0.003482 | +0.35% | -0.19% | +0.57% | -5.63% |
| fixed-15000-1000000 | control | 0.000000 | +0.00% | +0.00% | +0.00% | +0.00% |
| live-04 | contention_short_park | -0.004646 | -0.46% | -0.10% | -1.04% | -9.54% |
| live-17 | contention_long_h | -0.008441 | -0.84% | -3.01% | +0.89% | -11.80% |
| live-24 | contention_short_park | -0.011009 | -1.09% | +0.07% | -3.15% | -13.87% |
| live-05 | contention_long_h | -0.012315 | -1.22% | +4.91% | -8.97% | -22.34% |
| live-30 | body_long_h | -0.012684 | -1.26% | +2.53% | -5.41% | -13.80% |
| live-15 | body_short_h | -0.015880 | -1.58% | -1.01% | -3.17% | -17.64% |
| live-09 | body_short_park | -0.016523 | -1.64% | -0.25% | -4.41% | -13.43% |
| live-21 | contention_long_park | -0.016685 | -1.65% | +5.77% | -10.30% | -15.85% |
| live-19 | low_phr_long_park_long_h | -0.019641 | -1.94% | +3.58% | -8.02% | -15.41% |
| fixed-814375-2000000 | control | -0.021393 | -2.12% | +0.86% | -5.82% | -13.35% |
| live-01 | low_phr_long_park_short_h | -0.021903 | -2.17% | +0.21% | -5.55% | -18.51% |
| live-11 | phr_contention_interaction | -0.029757 | -2.93% | -2.81% | -3.31% | -19.35% |
| live-16 | body_long_h | -0.030002 | -2.96% | +2.32% | -8.56% | -25.76% |
| live-23 | contention_short_park | -0.033175 | -3.26% | -8.82% | +1.12% | -17.45% |
| live-31 | body_short_h | -0.034114 | -3.35% | -2.69% | -5.12% | -19.51% |
| live-28 | contention_short_park | -0.034188 | -3.36% | +0.10% | -7.36% | -17.28% |
| live-06 | contention_short_h | -0.034509 | -3.39% | +1.16% | -8.38% | -20.04% |
| fixed-15000-250000 | control | -0.037388 | -3.67% | -4.67% | -3.01% | -19.18% |
| fixed-500000-1000000 | control | -0.044985 | -4.40% | +0.96% | -10.04% | -17.22% |
| live-08 | body_short_h | -0.049621 | -4.84% | -9.29% | -1.85% | -35.79% |
| live-10 | body_long_park | -0.051051 | -4.98% | -1.98% | -7.92% | -42.93% |
| live-baseline | control | -0.052778 | -5.14% | -3.54% | -6.72% | -36.98% |
| live-07 | body_long_h | -0.060893 | -5.91% | -2.96% | -9.27% | -32.97% |
| live-27 | contention_body_interaction | -0.062011 | -6.01% | +3.11% | -17.81% | -26.33% |
| live-00 | low_phr_long_park_long_h | -0.088017 | -8.43% | -5.19% | -13.75% | -35.76% |
| live-18 | contention_short_h | -0.104474 | -9.92% | -5.33% | -14.99% | -48.36% |
| live-03 | contention_long_park | -0.108281 | -10.26% | -11.54% | -9.41% | -62.30% |

Full identifiers, winning-workload counts, worst matched forks and comparisons with the strongest
fixed control are in [policy_summary.tsv](../../../../../experiments/cache-timing-live-v1-analysis/policy_summary.tsv).
Only nine of the 32 state-dependent functions have a positive pooled J. The fixed 500,000/1,000,000
ns pair that led the earlier fixed surface now scores -4.40%; 814,375/2,000,000 ns scores -2.12%.
This campaign is the matched evidence for current comparisons. Do not pool baselines across the
separate campaigns or infer a universal timing pair from either ordering.

## Shortlist, regressions and lower observations

Raw pooled throughput below is in **millions of executions/s**. S is configured sources and W is
synthetic work units, not measured body nanoseconds.

| Workload | Fixed production | Fixed 125k/500k | live-25 | live-12 | Zero live baseline |
| --- | ---: | ---: | ---: | ---: | ---: |
| R23-S1-W0 | 18.199 | 20.200 | 20.568 | 19.166 | 14.678 |
| R23-S1-W96 | 11.884 | 12.510 | 11.672 | 12.099 | 7.490 |
| R23-S1-W576 | 21.596 | 21.860 | 23.907 | 23.478 | 24.643 |
| R23-S17-W0 | 817.378 | 805.730 | 800.589 | 820.187 | 830.253 |
| R23-S17-W96 | 112.914 | 113.225 | 121.920 | 122.782 | 121.101 |
| R23-S17-W576 | 25.552 | 25.480 | 25.552 | 25.665 | 25.599 |
| R23-S23-W0 | 1151.664 | 1120.765 | 1152.444 | 1114.578 | 1124.987 |
| R23-S23-W96 | 136.638 | 137.659 | 137.466 | 138.528 | 137.015 |
| R23-S23-W576 | 25.454 | 25.434 | 25.428 | 25.468 | 25.527 |

| Workload | live-25 pooled | live-25 pass 0 / pass 1 | live-12 pooled | live-12 pass 0 / pass 1 |
| --- | ---: | ---: | ---: | ---: |
| R23-S1-W0 | +13.02% | +13.95% / +12.11% | +5.32% | +13.79% / -2.96% |
| R23-S1-W96 | -1.78% | -20.83% / +17.10% | +1.81% | -1.09% / +4.68% |
| R23-S1-W576 | +10.70% | +24.12% / -0.96% | +8.71% | +8.69% / +8.73% |
| R23-S17-W0 | -2.05% | -3.94% / -0.14% | +0.34% | -1.25% / +1.96% |
| R23-S17-W96 | +7.98% | +14.21% / +2.38% | +8.74% | +15.08% / +3.04% |
| R23-S17-W576 | +0.00% | +0.65% / -0.64% | +0.44% | +0.86% / +0.03% |
| R23-S23-W0 | +0.07% | +2.39% / -2.15% | -3.22% | -0.35% / -5.96% |
| R23-S23-W96 | +0.61% | +0.59% / +0.62% | +1.38% | +2.07% / +0.69% |
| R23-S23-W576 | -0.10% | -0.25% / +0.05% | +0.06% | +0.30% / -0.19% |

- **live-25** improves six pooled workloads, but only three improve in both passes. The largest
  pooled loss is -2.05% at S17/W0. More concerning is S1/W96: -1.78% pooled hides -20.83% in
  pass 0 and +17.10% in pass 1. It loses **-6.70% pooled against fixed 125k/500k** on that workload.
  Its S1/W576 +10.70% pooled gain similarly combines +24.12% and -0.96% pass changes.
- **live-12** improves eight pooled workloads and four in both passes. S23/W0 loses -3.22%
  pooled, including -5.96% in pass 1. Its S1/W576 gain is consistent across passes (+8.69%,
  +8.73%). It still loses -5.12% and -3.28% pooled to fixed 125k/500k at S1/W0 and S1/W96.
- Both improve S17/W96 in both passes. However, the zero live baseline also gains +7.25%
  there, so the entire gain cannot be attributed to their state-response shape alone.
- **live-02** and **live-26** are not substitutes for this shortlist despite positive aggregate
  scores. Live-02 loses -43.29% in the S1/W576 second pass; live-26 loses -23.74% pooled at
  S23/W96, with both passes worse. Aggregate ranking alone would conceal these failures.

The next table shows **minimum fork mean / minimum measurement window**, in millions of
executions/s. These are observed minima from two forks and ten nested windows per cell, not
estimated quantiles or future throughput guarantees.

| Workload | Fixed production minima | Fixed 125k/500k minima | live-25 minima | live-12 minima |
| --- | ---: | ---: | ---: | ---: |
| R23-S1-W0 | 17.991 / 17.754 | 18.860 / 18.707 | 20.501 / 20.443 | 17.861 / 17.615 |
| R23-S1-W96 | 11.831 / 11.434 | 12.246 / 11.779 | 9.366 / 9.039 | 11.702 / 11.179 |
| R23-S1-W576 | 20.078 / 14.931 | 18.380 / 15.940 | 22.894 / 19.767 | 21.823 / 16.558 |
| R23-S17-W0 | 811.815 / 809.582 | 797.845 / 792.183 | 790.484 / 768.611 | 812.652 / 802.952 |
| R23-S17-W96 | 106.879 / 103.380 | 109.294 / 95.963 | 121.778 / 120.473 | 122.568 / 121.159 |
| R23-S17-W576 | 25.431 / 25.201 | 25.469 / 25.230 | 25.507 / 25.288 | 25.650 / 25.326 |
| R23-S23-W0 | 1125.277 / 1123.014 | 1111.347 / 1106.747 | 1152.149 / 1149.809 | 1107.806 / 1105.323 |
| R23-S23-W96 | 135.761 / 123.973 | 137.388 / 135.657 | 136.608 / 135.190 | 136.702 / 134.101 |
| R23-S23-W576 | 25.330 / 25.121 | 25.196 / 24.431 | 25.343 / 24.716 | 25.282 / 24.699 |

[finalist_comparisons.tsv](../../../../../experiments/cache-timing-live-v1-analysis/finalist_comparisons.tsv)
contains both baselines, exact values, paired changes and both minima for every shortlist/control
workload. The original complete workload table retains minima for all 38 policies.

## What the leading functions do

These are descriptions of the frozen functions, not estimates of which states were visited.
All original coefficients, including floating-point least-squares residuals, remain unchanged.

**live-25**, `low_phr_long_park_short_h`, is effectively PHR-only:

```text
zp = (PHR - 2) / 2
park ~= round(clamp(15000 * exp(1.3980297614 - 0.8787615643*zp), 15000, 814375))
H    ~= round(clamp(1000000 * exp(-0.8664339757 + 0.3119162313*zp), 250000, 2000000))
```

The other terms are numerical residuals near zero. At PHR 0, 1, 2 and 4 its requested pairs
are respectively 146,180/307,786, 94,204/359,733, 60,708/420,448 and 25,212/574,349 ns.
Thus low PHR lengthens park while shortening H. The weaker same-family live-01 scores -2.17%;
one candidate does not establish a winning sign or family across its parameter range.

**live-12**, `phr_body_interaction`, is effectively an interaction between PHR and log body:

```text
zp = (PHR - 2) / 2
zb = (log1p(bodyCostNs) - 8) / 8
park ~= round(clamp(15000 * exp(2.1969039107 - 1.9971853734*zp*zb), 15000, 814375))
H    ~= round(clamp(1000000 * exp(-0.3465735903 + 1.0397207708*zp*zb), 250000, 2000000))
```

At PHR 2, or log-body 8, the pair is 134,957/707,107 ns. At PHR 0, moving log-body from 0 to 16
moves the pair from 18,316/2,000,000 to 814,375/250,000 ns; PHR 4 reverses that behavior.
These are synthetic support-boundary examples, not measured body distributions. Contention
coefficients are negligible in both finalists. That does not establish that contention is
unnecessary generally, or justify removing the input, coupling park/H, or narrowing timing bounds.

## Zero-function result: historical context, not a gate

The zero function requests the production pair but loses -19.35% pooled at S1/W0 and -36.98%
at S1/W96. Conversely S1/W576 gains +14.11% pooled. These observations remain in the original
logs and tables. They neither require a separate explanation campaign nor define a correction
to subtract from useful-policy throughput.

The working interpretation is inference cost without changed idle behavior; its scheduler impact
can depend on workload and on how often the CACHE idle path is reached. No isolation of inference
cost, constant-function overhead, or throughput parity is needed. Judge the useful policy's
complete scheduler throughput against POLICY_OFF. Correctness tests still cover fixed-H decay,
prospective aging, clamping, rounding and exact evaluator outputs; no correctness defect has
been demonstrated by this performance difference.

## Offline fitting and held-workload selection

Ran only the already-declared generic ridge task, alpha grid `{0.1,1,10}`, on the 594 live forks.
It uses three complete-workload outer folds and two inner folds; no windows become training
replicates. All outer folds and the final full-development fit select alpha 10. No additional
model tournament, runtime exporter or candidate generation was run.

| Outer fold | Held workloads | Surrogate-selected complete policy | Geometric change | Worst held workload |
| --- | --- | --- | ---: | ---: |
| 0 | S1/W576, S17/W0, S23/W0 | live-01 | -10.34% | -12.56% |
| 1 | S1/W96, S17/W96, S23/W576 | live-12 | +3.47% | +0.06% |
| 2 | S1/W0, S17/W576, S23/W96 | live-12 | +2.36% | +0.44% |

Across all nine held workloads the surrogate scores `J = -0.017264`, or -1.71% geometrically.
Each action is matched back to an actually benchmarked full function; no throughput value is
assigned independently to fragment-state samples. Full outer predictions and inner selection
results are retained in `run.json`; [held workload actions](../../../../../experiments/cache-timing-live-v1-analysis/surrogate_held_workloads.tsv)
provide the measured consequences.

Two descriptive comparison rules were also evaluated on those same family partitions: choose
one policy using only training-family J, then evaluate it on held families. Ties are explicitly
lexicographic within 1e-12 J (none occurred). The five-fixed-policy pool yields -3.72%
geometrically and worst -19.18%; the 33-live-policy pool yields -6.25% and worst -23.74%.
Their selected policies and train/held memberships are in `audit.json`. These comparisons were
added during analysis and are development diagnostics, not a newly untouched validation set.
The pooled winners live-25/live-12 were selected using all nine workloads and must not be
presented as independently held-out winners.

The frozen ridge has only additive fixture and coefficient inputs. For a fitted model, fixture
terms shift predicted returns but cannot change the ordering of functions within a workload;
there are no fixture-by-policy interactions. This is a representational limitation of the
search surrogate, not evidence that the runtime's 14-coefficient geometry failed. Its poor
held choices warrant stopping surrogate-driven proposals now. They do not justify immediately
adding runtime quadratics, trees or a broad model tournament.

## Historical bounded confirmation plan (R23 now completed)

1. **Run the prepared R23 confirmation through the runner handoff.** Exactly POLICY_OFF,
   live-25 and live-12; S1 and S23 each at W0/W96/W576; four balanced independent fork blocks:
   72 JVMs. S1/W96 is explicitly retained. There is no zero-function arm, extra fixed control,
   S17 expansion, overhead investigation or candidate generation.
2. **Review scarcity first and plentiful-source guardrails separately.** For every body regime,
   inspect pooled throughput, changes against OFF, all four matched fork/block results and both
   observed minima. Require broad useful scarcity gains: one exceptional S1/W0 result cannot hide
   weak or negative S1/W96/W576 behavior. Positive or near-neutral plentiful performance is good;
   a small negative can be acceptable, but material or consistently negative behavior fails the
   guardrail. No automatic numeric threshold, CV target or combined winner scalar is introduced.
3. **After R23 review, run fresh topology confirmation.** At resolved R7 use S1/S7 and at R15
   use S1/S15, each at W0/W96/W576 with the same three exact arms and four fork blocks: 144 JVMs.
   CPU/source/body fixtures are frozen in the handoff. Report each topology separately; source
   count constructs the fixture and is never substituted for runtime measured PHR.
4. **After successful steady-state review, run persistent dynamics.** Six existing transition
   types in both directions, including the added reverse combined scenario, use the same three
   arms at R23: 72 JVMs in four blocks. Each JVM keeps the same scheduler across four pre-change
   and four post-change measurement windows. Compare phase means and every matched window,
   especially the first post-change and later recovery windows, without treating phases/windows
   as independent replicates. The requirement is no material harm, stickiness or recovery failure;
   improvement is welcome, not required. Two-second windows cannot prove sub-window recovery time.
5. **Stop on the bounded evidence.** Review broad scarce gains, no material plentiful harm,
   robustness across blocks/topologies and dynamics, then overall scores only as supporting
   information. Keep inconclusive cases explicit. No broad search, nearby coefficient variants,
   quadratic extension, model tournament or surrogate-generated candidates follow automatically.
   Production changes require a separate reviewed decision.

The null-function production bypass already existed. Its two owner-local branches were extracted
into package-private helpers to test OFF explicitly without changing their behavior or public API.
Tests show configured fixed park/H use, no timing input read/inference or adaptive-H installation
on OFF, and exact Java/runtime/export parity with the frozen finalist functions. The preparation
adds only the reverse combined benchmark scenario and the bounded configuration/collection tools.
No queue/evidence semantics, participation classifier, default, hot-path allocation or observer
was added. Executable manifests are frozen after tests; execution remains a runner handoff.

## Cleanup of superseded preparation files

Removed six unreferenced-by-lock preparation files under `benchmarks/src/main/presets/cache-timing`:

- `baseline_function.json` and the top-level `CacheTimingEvaluator.java`: obsolete four-term
  unmeasured controls, superseded by the actual seven-term zero baseline in live-v2.
- Parent `tasks/cache_timing_policy_search.json` and `datasets/cache_timing_policy_search.json`:
  duplicate preparation pointers; the collected task/evidence is authoritative for fitting.
- Parent `tasks/cache_timing_closed_loop_validation.json` and
  `datasets/cache_timing_closed_loop_validation.json`: duplicate placeholders; the canonical
  frozen live-v2 validation schema remains available, still gated on measured finalists.

Retained both live-v1/live-v2 frozen trees and locks, source-refresh audit, original fixed-stage
configs, all raw JVM logs, collected evidence, prior findings and fitted results. Their hashes
and historical references remain useful to reproduce this experiment; they are not expendable
scratch output. Removal hashes and reasons are recorded in the [cleanup inventory](../../../../../experiments/cache-timing-live-v1-analysis/cleanup.json).
