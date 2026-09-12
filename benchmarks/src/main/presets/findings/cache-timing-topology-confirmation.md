# CACHE R7/R15 idle-policy confirmation: findings and suggested next steps

## Finding

**Neither finalist meets the cross-topology advancement objective. Keep POLICY_OFF and stop
progression to dynamic validation pending reassessment.** The required outcome was broad,
useful scarce-source improvement with approximately neutral plentiful-source execution.
The fresh topology evidence does not establish that combination for either exact function.

**Live-25 does not generalize its R23 scarcity breadth.** At R7, scarce throughput is -1.48%
geometrically: no-op loses -5.46%, medium gains +1.52%, expensive loses -0.35%. At R15,
scarcity is +2.37%, but this is driven by no-op +9.02%; medium loses -1.38% and expensive
loses -0.21%. Only one of three pooled scarce workloads improves at each topology. Do not let
the R15 no-op gain compensate for the other body regimes. Its plentiful aggregates (+3.42%
R7, +0.10% R15) cannot rescue failure to earn its cost broadly under scarcity.

**Live-12 has useful scarcity gains but fails the plentiful-source guardrail.** Scarcity is
+5.15% at R7 (3/3 pooled workloads positive) and +2.87% at R15 (2/3 positive). Yet plentiful
no-op throughput loses **-5.82% at R7 and -5.63% at R15**. R15 loses in every block, from
-3.99% to -6.39%; R7 loses three of four, including -13.20% and -14.38%. This repeated,
workload-specific damage is a guardrail failure under the stated objective, not a small
aggregate negative automatically rejected by an invented threshold. R7's positive plentiful
aggregate is driven by medium-body gains and hides its no-op failure.

The completed [R23 confirmation](cache-timing-r23-confirmation.md) remains valid for its own
fixtures: live-25 gained +8.58% scarce / -0.46% plentiful there; live-12 gained +2.95% scarce /
+0.35% plentiful but lost expensive scarcity in every block. The new results limit generalization;
they do not erase R23 or establish that either function is useless everywhere. No deployed winner,
universal pair, topology cutoff or revised coefficient rule follows from these observations.

## Evidence and provenance

The input remains [confirmation-v2](../cache-timing/confirmation-v2/candidate_manifest.json),
launched into `cache-timing-confirmation-v1-topologies` after the user's R23 review. The same
three arms were measured: actual fixed-production POLICY_OFF (15,000/1,000,000 ns, inference
bypassed), exact live-25 and exact live-12. No coefficients or bounds were changed.

The [reproducible audit](../../../../../experiments/cache-timing-confirmation-v1-topologies-analysis/analyze.py)
and [audit result](../../../../../experiments/cache-timing-confirmation-v1-topologies-analysis/audit.json)
verify:

- Exactly 144 independent JVM forks: three arms x twelve complete workloads x four balanced
  blocks. All 720 measurement windows remain. Every raw trial config/log matches its collected
  hash, every five-window fork mean was independently recomputed, and recollection into a
  temporary directory reproduces every evidence data file byte-for-byte.
- Exact frozen config, arm, schedule and launch identities match. The launch's R23 review hash
  matches the recorded user review, and its evidence-bound gate verifies. Current source and
  topology checks pass; all 26 current distribution JAR hashes match launch. Logs uniformly
  report JMH 1.37 and OpenJDK 21.0.2.
- R7 uses logical CPUs 2..15 inclusive and resolves to seven physical workers; R15 uses 2..23
  and resolves to fifteen. Harness CPU is 0. Each uses S1 and S=resolved workers at W0/96/576.
  R7 is seven P-cores; R15 adds eight E-cores. These are specific host placements, not evidence
  that physical worker count alone explains the differences.
- AUTO participation without forced cutoff, CONTINUOUS lifecycle, explicit throughput-only
  mode, disabled observers; three 2-second warmups, five 2-second measurement windows, one
  million executions per invocation and a 120-second invocation guard. Work units describe
  synthetic fixtures, not measured body nanoseconds. Source counts never replace runtime PHR.
- Dynamic output/evidence directories remain absent. No runtime-state traces establish state
  occupancy, fallback frequency, visited timing pairs or a mechanism for these outcomes.

Sources: [lock](../cache-timing/confirmation-v2/lock.json),
[launch identity](../../../../../experiments/cache-timing-confirmation-v1-topologies/identity.json),
[collected receipt](../../../../../experiments/cache-timing-confirmation-v1-topologies-evidence/evidence_manifest.json),
[all fork observations](../../../../../experiments/cache-timing-confirmation-v1-topologies-evidence/arms.json),
[R23 review](../cache-timing/reviews/r23.json).

JVM forks are replication units; their windows are nested observations. Matched blocks balance
ordering, not simultaneous machine conditions. These are fresh topology fixtures for the two
already-selected functions, now consumed as validation evidence. No held-out surrogate fit,
new model selection, statistical equivalence claim or future lower-tail guarantee was made.
No slow fork/window was discarded or rerun by this analysis.

Validation environment note: the full audit above completed successfully. A repeat after the
report edit failed while importing NumPy (`ModuleNotFoundError`) from the shared
`/tmp/euhedral-tournament-venv` environment. A final standard-library-only check verified that
all frozen source/input hashes, raw logs/configs, evidence files and the audit script remain
unchanged. No dependencies or runtime code were modified to work around the environment change.
Re-running the full audit requires restoring that Python environment's declared dependencies.

## Separate class results

For each workload, pool the four candidate fork means and divide by its pooled OFF mean.
Geometric change is `100 * (exp(mean(log(ratio))) - 1)` within each topology/class. Block summaries
use matched OFF forks within that block. Pooling and logging do not commute. PRIMARY is S1;
GUARDRAIL is S=R. There is no intermediate set and no combined deployment score.

| Topology | Candidate | Class | Geometric change | Positive workloads | Positive in every block | Worst workload/change |
| --- | --- | --- | ---: | ---: | ---: | --- |
| R7 | live-25 | PRIMARY | -1.48% | 1/3 | 0/3 | R7-S1-W0: -5.46% |
| R7 | live-25 | GUARDRAIL | +3.42% | 1/3 | 0/3 | R7-S7-W0: -1.69% |
| R7 | live-12 | PRIMARY | +5.15% | 3/3 | 0/3 | R7-S1-W576: +0.38% |
| R7 | live-12 | GUARDRAIL | +1.78% | 1/3 | 0/3 | R7-S7-W0: -5.82% |
| R15 | live-25 | PRIMARY | +2.37% | 1/3 | 1/3 | R15-S1-W96: -1.38% |
| R15 | live-25 | GUARDRAIL | +0.10% | 1/3 | 0/3 | R15-S15-W576: -0.35% |
| R15 | live-12 | PRIMARY | +2.87% | 2/3 | 0/3 | R15-S1-W576: -0.45% |
| R15 | live-12 | GUARDRAIL | -1.76% | 2/3 | 0/3 | R15-S15-W0: -5.63% |

| Topology/candidate/class | Block 0 | Block 1 | Block 2 | Block 3 |
| --- | ---: | ---: | ---: | ---: |
| R7 / live-25 / PRIMARY | -0.59% | -4.51% | +2.22% | -2.74% |
| R7 / live-25 / GUARDRAIL | +1.99% | +12.28% | +1.35% | -0.39% |
| R7 / live-12 / PRIMARY | +5.57% | +1.37% | +13.55% | +0.27% |
| R7 / live-12 / GUARDRAIL | -4.18% | +12.96% | +1.13% | -1.11% |
| R15 / live-25 / PRIMARY | +8.22% | -1.92% | +2.26% | +1.06% |
| R15 / live-25 / GUARDRAIL | -1.80% | +0.30% | +1.96% | -0.09% |
| R15 / live-12 / PRIMARY | +9.14% | -4.66% | +0.64% | +6.81% |
| R15 / live-12 / GUARDRAIL | -2.01% | -1.69% | -2.26% | -1.08% |

Only live-25's R15/S1/W0 improves in every block among the twelve candidate/scarce-workload
comparisons. Live-12's positive pooled R7 scarcity breadth is promising locally, but none of
those body regimes improves in all four blocks. Live-12's R15 plentiful aggregate is negative
in every block, reinforcing its specific no-op guardrail failure.

Overall equal-workload geometric changes are descriptive only: live-25 +0.94% at R7 and +1.23%
at R15; live-12 +3.45% at R7 and +0.53% at R15. All are positive despite the failures above.
Selecting a winner from these aggregates would answer the wrong question.

## Per-workload throughput and independent blocks

Pooled throughput is in millions of executions/s. Each change is versus the matched campaign's
POLICY_OFF. Do not pool OFF baselines across R23, R7/R15 or the original screen.

| Workload | OFF | live-25 | Change | live-12 | Change |
| --- | ---: | ---: | ---: | ---: | ---: |
| R7-S1-W0 | 30.550 | 28.881 | -5.46% | 32.343 | +5.87% |
| R7-S1-W96 | 20.545 | 20.859 | +1.52% | 22.478 | +9.41% |
| R7-S1-W576 | 12.065 | 12.022 | -0.35% | 12.111 | +0.38% |
| R7-S7-W0 | 384.682 | 378.185 | -1.69% | 362.308 | -5.82% |
| R7-S7-W96 | 57.742 | 64.964 | +12.51% | 64.950 | +12.48% |
| R7-S7-W576 | 12.069 | 12.068 | -0.01% | 12.010 | -0.49% |
| R15-S1-W0 | 22.266 | 24.274 | +9.02% | 23.624 | +6.10% |
| R15-S1-W96 | 13.803 | 13.612 | -1.38% | 14.225 | +3.06% |
| R15-S1-W576 | 19.389 | 19.348 | -0.21% | 19.301 | -0.45% |
| R15-S15-W0 | 727.094 | 734.101 | +0.96% | 686.132 | -5.63% |
| R15-S15-W96 | 100.815 | 100.505 | -0.31% | 101.238 | +0.42% |
| R15-S15-W576 | 19.093 | 19.026 | -0.35% | 19.101 | +0.04% |

| Candidate/workload | Block 0 change | Block 1 change | Block 2 change | Block 3 change |
| --- | ---: | ---: | ---: | ---: |
| live-25 / R7-S1-W0 | -2.20% | -4.60% | -0.08% | -13.89% |
| live-25 / R7-S1-W96 | +1.84% | -8.70% | +8.16% | +5.61% |
| live-25 / R7-S1-W576 | -1.38% | -0.05% | -1.17% | +1.18% |
| live-25 / R7-S7-W0 | +6.01% | +19.48% | -11.86% | -14.49% |
| live-25 / R7-S7-W96 | -0.22% | +16.99% | +19.35% | +16.20% |
| live-25 / R7-S7-W576 | +0.29% | +1.26% | -1.03% | -0.53% |
| live-12 / R7-S1-W0 | +7.61% | +4.16% | +14.26% | -1.40% |
| live-12 / R7-S1-W96 | +7.37% | -0.23% | +29.37% | +1.84% |
| live-12 / R7-S1-W576 | +1.85% | +0.23% | -0.96% | +0.41% |
| live-12 / R7-S7-W0 | -9.68% | +19.20% | -13.20% | -14.38% |
| live-12 / R7-S7-W96 | -1.73% | +19.72% | +21.36% | +13.20% |
| live-12 / R7-S7-W576 | -0.89% | +1.01% | -1.83% | -0.22% |
| live-25 / R15-S1-W0 | +19.38% | +2.89% | +3.90% | +11.34% |
| live-25 / R15-S1-W96 | +6.10% | -7.79% | +2.98% | -7.02% |
| live-25 / R15-S1-W576 | +0.06% | -0.56% | -0.06% | -0.29% |
| live-25 / R15-S15-W0 | -5.39% | +2.97% | +6.24% | +0.21% |
| live-25 / R15-S15-W96 | -0.05% | -1.42% | +0.14% | +0.12% |
| live-25 / R15-S15-W576 | +0.16% | -0.60% | -0.36% | -0.60% |
| live-12 / R15-S1-W0 | +19.03% | -1.80% | -0.45% | +9.44% |
| live-12 / R15-S1-W96 | +9.71% | -10.81% | +3.12% | +10.90% |
| live-12 / R15-S1-W576 | -0.44% | -1.06% | -0.71% | +0.40% |
| live-12 / R15-S15-W0 | -5.97% | -6.39% | -6.15% | -3.99% |
| live-12 / R15-S15-W96 | +0.19% | +1.17% | -0.44% | +0.75% |
| live-12 / R15-S15-W576 | -0.13% | +0.31% | -0.07% | +0.07% |

Every independent candidate/OFF fork mean and window minimum is retained in
[per_fork.tsv](../../../../../experiments/cache-timing-confirmation-v1-topologies-evidence/per_fork.tsv);
[per_workload.tsv](../../../../../experiments/cache-timing-confirmation-v1-topologies-evidence/per_workload.tsv)
contains exact pooled values. Key interpretations:

- **The large R23 live-25 no-op scarcity gain is topology-dependent evidence.** R15 gains
  +9.02% and improves all blocks, but R7 loses -5.46% and is negative in every block (one is
  only -0.08%). Do not infer a worker-count threshold from three host placements.
- **The unresolved medium-body scarcity issue remains.** Live-25 is +1.52% at R7 but has an
  -8.70% block; R15 is -1.38%, with -7.79% and -7.02% blocks. Live-12 is +9.41% at R7 but
  combines a -0.23% block with +29.37%; R15 is +3.06% with a -10.81% block. These are mixed
  outcomes, not reliable improvement in each independent trial.
- **Expensive scarcity earns little here.** Live-25 is slightly negative at both topologies;
  live-12 is +0.38% at R7 and -0.45% at R15. These small effects do not reproduce live-12's
  severe R23 expensive regression, but do not establish broadly material gains either.
- **R7 plentiful/no-op reversals involve changing OFF throughput.** OFF fork means are
  365.599, 326.201, 426.026 and 420.900 million/s. Live-25 is 387.576, 389.743, 375.511,
  359.910; live-12 is 330.224, 388.830, 369.806, 360.371. The last two candidate losses near
  12-14% cannot be discarded, nor can the favorable second block establish neutrality.
  The logs do not identify scheduler versus host/order effects.
- **Both functions gain about 12.5% at R7 plentiful/medium.** OFF is 65.076 million/s in
  block 0 and roughly 54.8-56.1 million/s thereafter; both candidate series remain around
  63.5-66.5 million/s. Both lose slightly in block 0. Keep all these observations; they do
  not establish the idle-policy mechanism and cannot offset plentiful/no-op harm.
- **R15 live-12 plentiful/no-op is the clearer repeated guardrail failure.** All four blocks
  lose approximately 4-6%, while the other plentiful bodies are near-neutral. The -1.76%
  class aggregate understates that specific problem.

## Observed minima

Each cell is minimum fork mean / minimum measurement window, in millions of executions/s.
Four forks and twenty nested measurement windows contribute to each cell. Minima may come from
different blocks; they are observations, not quantiles or guarantees.

| Workload | OFF | live-25 | live-12 |
| --- | ---: | ---: | ---: |
| R7-S1-W0 | 28.728 / 28.123 | 28.411 / 28.073 | 31.838 / 29.921 |
| R7-S1-W96 | 19.571 / 19.487 | 19.932 / 19.688 | 20.749 / 20.704 |
| R7-S1-W576 | 12.034 / 11.904 | 11.868 / 11.811 | 11.969 / 11.893 |
| R7-S7-W0 | 326.201 / 325.016 | 359.910 / 358.708 | 330.224 / 329.668 |
| R7-S7-W96 | 54.803 / 54.687 | 64.363 / 64.246 | 63.473 / 63.331 |
| R7-S7-W576 | 11.959 / 11.828 | 12.013 / 11.871 | 11.954 / 11.856 |
| R15-S1-W0 | 20.616 / 15.135 | 24.035 / 22.631 | 23.023 / 21.556 |
| R15-S1-W96 | 13.144 / 11.113 | 12.222 / 7.941 | 12.647 / 10.334 |
| R15-S1-W576 | 19.362 / 19.312 | 19.315 / 19.225 | 19.219 / 19.152 |
| R15-S15-W0 | 715.365 / 697.092 | 702.980 / 691.430 | 675.160 / 664.033 |
| R15-S15-W96 | 100.254 / 99.611 | 100.037 / 99.338 | 100.469 / 99.587 |
| R15-S15-W576 | 19.069 / 18.857 | 18.954 / 18.815 | 19.056 / 18.925 |

Live-25's R15/S1/W96 minimum window is 7.941 million/s, versus OFF's 11.113 million/s;
its minimum fork mean is also lower (12.222 versus 13.144). Its +2.37% R15 scarcity aggregate
must not hide this medium-body behavior. Conversely R15/S1/W0 improves both observed minima.
Live-12's R15 plentiful/no-op minima fall to 675.160 / 664.033 from OFF's 715.365 / 697.092,
consistent with the repeated fork regression. An improved R7 pooled or minimum comparison does
not erase those R15 observations.

[All windows](../../../../../experiments/cache-timing-confirmation-v1-topologies-evidence/measurement_windows.tsv),
[exact class summaries](../../../../../experiments/cache-timing-confirmation-v1-topologies-evidence/class_summary.tsv)
and [class-by-block summaries](../../../../../experiments/cache-timing-confirmation-v1-topologies-analysis/class_by_block.tsv)
remain available. No CV target or slow-outcome filter was applied.

## Suggested next steps

1. **Keep POLICY_OFF and close the current advancement attempt without selecting a deployable
   winner.** Live-25 fails broad scarcity generalization; live-12 has repeated material plentiful
   no-op harm, in addition to its prior R23 expensive-scarcity failure. Preserve the distinct
   conclusions instead of choosing the largest positive overall score.
2. **Do not advance to dynamics on this evidence.** The existing persistent dynamic stage was
   conditional on successful steady-state confirmation. That condition is not met. Preserve its
   frozen configuration as unexecuted; do not issue a topology `decision: proceed` receipt or
   spend another 72 JVMs merely because configs exist. Dynamic behavior remains unknown, not
   failed. Any later dynamic experiment would require an explicitly revised purpose/review.
3. **Reassess offline before authorizing any more trials.** Compare the exact two output surfaces
   with the workload-specific failures, using existing numerical evaluation only. Runtime state
   occupancy is unavailable, so such inspection can suggest questions, not infer visited states,
   optimal timing labels or a cause. Host composition and effective PHR differ with topology;
   no worker-count rule, removed feature or new controller structure is justified yet.
4. **If future work is requested, require one concrete, bounded hypothesis.** It must address
   scarcity breadth or the repeated plentiful/no-op damage, with an exact fixture/control plan
   and a stopping rule. Do not automatically refit these functions, generate neighbors, launch
   another broad pool, revive the failed ridge, add runtime quadratics/trees, or investigate
   zero-function overhead. Do not rerun bad forks until they disappear.

This analysis ends at the bounded steady-state evidence. No new benchmarks, configs, review
approvals, candidate generation or surrogate fitting were executed. No production defaults,
participation classifier, runtime features, timing bounds or frozen artifacts changed. The
[handoff](../cache-timing/CONFIRMATION_HANDOFF.md) now records topology completion and the stop
recommendation; further experimentation requires review of these findings and a new instruction.
