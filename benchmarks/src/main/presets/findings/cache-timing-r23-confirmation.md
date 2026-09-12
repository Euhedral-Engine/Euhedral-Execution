# CACHE R23 idle-policy confirmation: findings and suggested next steps

Latest status: [R7/R15 confirmation is complete](cache-timing-topology-confirmation.md).
The newer evidence does not support advancing either finalist; keep POLICY_OFF and stop before
dynamics pending reassessment. Results and recommendations below describe the earlier stage.

## Finding

**Live-25 is the stronger candidate for the already-prepared topology confirmation; neither
function is ready for deployment.** Its scarce-source geometric change is **+8.58%** against
POLICY_OFF, with all three body regimes positive when pooled. Plentiful-source throughput is
**-0.46%** geometrically. That small aggregate loss is compatible with the stated objective,
but it does not erase specific fork/window guardrail concerns below.

Live-25's cheap-body scarcity improvement is robust: +19.44% pooled and positive in all four
blocks. Medium-body scarcity gains +5.44% with three positive blocks; expensive-body gains
+1.64% with three positive blocks. Those latter two effects are less consistent and do not yet
establish broad material gains across topologies. Only one scarce workload improves in every
block. The +8.58% aggregate must not substitute for this body-by-body assessment.

**Live-12 does not confirm broad scarce-source improvement.** Its +2.95% scarcity aggregate
combines cheap/medium gains with **-6.02% at S1/W576**, negative in all four blocks and -17.85%
in block 3. Its plentiful-source +0.35% result is useful, but cannot compensate for that primary
failure. Do not advance it as a deployment candidate on the aggregate score.

CACHE remains hybrid execution; timing is an owner-local idle policy choosing independent park
and half-life outputs. Collective participation is emergent, without a central worker target.
POLICY_OFF is the actual fixed production path (15,000/1,000,000 ns), bypassing inference entirely.
Useful policy throughput is the result of interest; no zero-function overhead campaign is needed.
Production defaults, participation and exact finalist coefficients remain unchanged.

## Evidence and audit

Input revision is [confirmation-v2](../cache-timing/confirmation-v2/candidate_manifest.json);
the completed experiment retains the ID `cache-timing-confirmation-v1-r23`. The earlier
formatting/source mismatch was resolved before execution. This report supersedes the R23
pre-execution status in the handoff. The earlier [32-function screen](cache-timing-live-functions.md)
remains discovery evidence; its throughput is not pooled with these new forks.

Verified against retained artifacts:

- 72 independent JVM forks: exactly three arms x six workloads x four balanced blocks.
  All 360 measurement windows remain. No slow fork/window was removed or replaced by this analysis.
- Every raw config/log hash matches its collected provenance. All five `executions` windows per
  fork were independently parsed and their means recomputed. Recollection into a temporary
  directory reproduces every retained data table and `arms.json` byte-for-byte.
- The launch identity matches the frozen lock/harness/source/topology identities. Current source
  checking passes; all 26 current distribution JARs match launch hashes. Logs uniformly report
  JMH 1.37 and OpenJDK 21.0.2. Every trial satisfies the frozen config and arm inventory checks.
- R23 is 23 physical workers from logical CPUs 2..31, harness CPU 0; S1/S23 at synthetic
  W0/W96/W576. Work units are fixture weights, not measured body nanoseconds. AUTO participation,
  CONTINUOUS lifecycle, throughput-only output and disabled observers remain as declared.
- Three 2-second warmups and five 2-second measurements, one million executions per invocation,
  one fork per trial and a 120-second invocation guard. Durations/minima are not guarantees.
- No R7/R15 or dynamic run/evidence directories exist. No runtime traces establish state
  occupancy, inference/fallback frequency or the timings actually visited by fragments.

Sources: [lock](../cache-timing/confirmation-v2/lock.json),
[launch identity](../../../../../experiments/cache-timing-confirmation-v1-r23/identity.json),
[collected receipt](../../../../../experiments/cache-timing-confirmation-v1-r23-evidence/evidence_manifest.json),
[all fork observations](../../../../../experiments/cache-timing-confirmation-v1-r23-evidence/arms.json),
[reproducible audit script](../../../../../experiments/cache-timing-confirmation-v1-r23-analysis/analyze.py),
[audit result](../../../../../experiments/cache-timing-confirmation-v1-r23-analysis/audit.json).

These are fresh replication forks on previously studied R23 workload families, not held-out
workloads or fresh topology validation. Forks are the replication unit; windows are nested.
Matched blocks balance ordering, not simultaneous machine conditions. No causal mechanism or
statistical equivalence is inferred from four forks.

## Scarcity first; plentiful guardrail separately

Pool the four fork means per arm/workload, divide by that workload's pooled OFF mean, and average
log ratios only within the stated class. Geometric change is `100 * (exp(meanLogRatio) - 1)`.
Block summaries instead use each block's matched OFF fork. Pooling and logging do not commute.
No CV reward, numerical acceptance threshold or combined deployment score is introduced.

| Candidate | Class | Geometric change | Positive workloads | Positive in all four blocks | Worst workload/change |
| --- | --- | ---: | ---: | ---: | --- |
| live-25 | PRIMARY S1 | +8.58% | 3/3 | 1/3 | W576: +1.64% |
| live-25 | GUARDRAIL S23 | -0.46% | 1/3 | 0/3 | W96: -1.11% |
| live-12 | PRIMARY S1 | +2.95% | 2/3 | 1/3 | W576: -6.02% |
| live-12 | GUARDRAIL S23 | +0.35% | 2/3 | 1/3 | W0: -0.43% |

| Candidate/class | Block 0 | Block 1 | Block 2 | Block 3 |
| --- | ---: | ---: | ---: | ---: |
| live-25 PRIMARY | +6.49% | +13.90% | +7.57% | +6.50% |
| live-25 GUARDRAIL | -2.05% | -1.81% | -0.03% | +2.03% |
| live-12 PRIMARY | +3.29% | +4.48% | +3.95% | -0.17% |
| live-12 GUARDRAIL | +0.12% | +0.53% | -0.43% | +1.18% |

[Exact class summaries](../../../../../experiments/cache-timing-confirmation-v1-r23-evidence/class_summary.tsv)
and [block summaries](../../../../../experiments/cache-timing-confirmation-v1-r23-analysis/class_by_block.tsv)
retain unrounded values. Overall equal-workload changes are +3.96% for live-25 and +1.64% for
live-12, descriptive only. S17 intermediate diagnostics were not included.

## Raw throughput and body-specific changes

Pooled throughput is in millions of executions/s; changes are against OFF in this campaign.

| Workload | OFF | live-25 | live-25 change | live-12 | live-12 change |
| --- | ---: | ---: | ---: | ---: | ---: |
| R23-S1-W0 | 18.490 | 22.086 | +19.44% | 20.762 | +12.29% |
| R23-S1-W96 | 12.065 | 12.721 | +5.44% | 12.476 | +3.41% |
| R23-S1-W576 | 24.418 | 24.818 | +1.64% | 22.947 | -6.02% |
| R23-S23-W0 | 1126.521 | 1115.382 | -0.99% | 1121.671 | -0.43% |
| R23-S23-W96 | 136.708 | 135.192 | -1.11% | 137.266 | +0.41% |
| R23-S23-W576 | 25.625 | 25.813 | +0.73% | 25.903 | +1.08% |

Each independent matched block's change follows. Every fork mean, OFF counterpart and window
minimum is in [per_fork.tsv](../../../../../experiments/cache-timing-confirmation-v1-r23-evidence/per_fork.tsv).

| Candidate/workload | Block 0 | Block 1 | Block 2 | Block 3 |
| --- | ---: | ---: | ---: | ---: |
| live-25 / R23-S1-W0 | +15.67% | +22.97% | +17.69% | +21.39% |
| live-25 / R23-S1-W96 | +2.87% | +13.09% | +7.04% | -0.78% |
| live-25 / R23-S1-W576 | +1.50% | +6.25% | -1.19% | +0.30% |
| live-25 / R23-S23-W0 | -2.00% | -2.75% | -2.05% | +2.81% |
| live-25 / R23-S23-W96 | -3.31% | -2.97% | +1.37% | +0.46% |
| live-25 / R23-S23-W576 | -0.83% | +0.33% | +0.61% | +2.83% |
| live-12 / R23-S1-W0 | +11.18% | +2.90% | +14.77% | +20.95% |
| live-12 / R23-S1-W96 | +4.89% | +11.02% | -1.94% | +0.12% |
| live-12 / R23-S1-W576 | -5.51% | -0.15% | -0.20% | -17.85% |
| live-12 / R23-S23-W0 | +0.80% | -0.81% | -2.81% | +1.14% |
| live-12 / R23-S23-W96 | -0.54% | +0.80% | +1.42% | -0.05% |
| live-12 / R23-S23-W576 | +0.11% | +1.61% | +0.16% | +2.46% |

- **S1/W96, the original uncertainty:** live-25 now gains +5.44% pooled, with three gains and
  a -0.78% block. The discovery screen's -20.83% bad block did not recur here. This supports a
  useful medium-body effect, but neither erases the original bad outcome nor guarantees recovery
  on a new topology. Live-12 gains +3.41%, also three positive blocks, with a -1.94% block.
- **Expensive scarce execution separates the candidates:** live-25 is modestly positive pooled
  (+1.64%, -1.19% to +6.25% by block), while live-12 loses all four blocks. Live-12's earlier
  +8.71% discovery gain did not reproduce; one body regime cannot be hidden behind cheap gains.
- **Live-25 plentiful behavior warrants continued guardrail scrutiny:** S23/W0 is negative in
  three blocks (about -2.00% to -2.75%) before +2.81% in block 3. S23/W96 has two roughly -3%
  blocks and two positive blocks. These are repeated concerns, not a clean neutrality finding;
  the small pooled losses alone do not establish a material campaign-wide failure. A persistent
  pattern across fresh topologies would weigh against advancement under the stated guardrail.
- **Live-12's better plentiful result does not rescue its primary failure.** Keep scarcity breadth
  ahead of plentiful/overall scores when deciding which policy merits further advancement.

## Observed lower outcomes

Each cell is **minimum fork mean / minimum measurement window**, in millions of executions/s.
There are four fork means and twenty nested windows per cell. These are observed minima, not
estimated quantiles or future guarantees. Minima may arise in different blocks/windows.

| Workload | OFF | live-25 | live-12 |
| --- | ---: | ---: | ---: |
| R23-S1-W0 | 17.823 / 17.344 | 21.343 / 21.205 | 19.715 / 19.541 |
| R23-S1-W96 | 11.531 / 10.726 | 12.203 / 11.826 | 11.978 / 11.822 |
| R23-S1-W576 | 23.455 / 20.173 | 24.359 / 22.861 | 20.599 / 18.329 |
| R23-S23-W0 | 1101.750 / 1094.288 | 1079.767 / 923.844 | 1108.155 / 1101.470 |
| R23-S23-W96 | 134.722 / 133.909 | 130.260 / 129.646 | 133.997 / 132.376 |
| R23-S23-W576 | 25.564 / 25.040 | 25.557 / 25.214 | 25.635 / 25.292 |

Live-25 improves both observed minima in all three scarce fixtures. That supports the primary
interpretation alongside the pooled gains; it does not prove a future lower-tail bound. At
S23/W0, however, its minimum window is 923.844 million/s versus OFF's 1,094.288 million/s
(about -15.58% comparing the two observed minima). The affected candidate fork averages
1,100.189 million/s and loses -2.75% to its matched OFF block. Keep the slow window: a modest
fork-mean loss can coexist with a much worse window. No logger/telemetry establishes its cause.
Live-12's S1/W576 minima also deteriorate substantially, consistent with its negative fork results.

[Exact workload table](../../../../../experiments/cache-timing-confirmation-v1-r23-evidence/per_workload.tsv)
and [all measurement windows](../../../../../experiments/cache-timing-confirmation-v1-r23-evidence/measurement_windows.tsv)
retain the complete observations. No slow outcome was discarded.

## Suggested next steps

Update after user review: the user has reviewed this report and requested the next handoff.
[R23 review receipt](../cache-timing/reviews/r23.json) records that instruction; the
[topology runner handoff](../cache-timing/CONFIRMATION_HANDOFF.md) is ready. The frozen three-arm
R7/R15 stage is next. Dynamics and production changes remain gated.


1. **Retain OFF in production and keep live-25 as the leading experimental candidate.** The R23
   result supports continued bounded confirmation, not deployment or a claim of uniformly material
   scarcity gains. Live-12 has not met the broad-scarcity criterion because W576 regresses in every
   block. Do not refit either function or generate nearby variants.
2. **Review R23, then use the existing frozen R7/R15 steady-state stage.** Recommended next
   execution is the already-prepared 144-JVM topology stage: OFF/live-25/live-12, four blocks,
   R7 S1/S7 and R15 S1/S15 at W0/W96/W576. Retain live-12 as the already-frozen comparison arm to
   learn whether its expensive-body failure generalizes; its inclusion is not advancement.
   Keep per-topology scarcity and plentiful summaries separate. Prioritize live-25's medium/
   expensive scarcity breadth, its repeated S23 cheap/medium losses, and observed lower windows.
   No new R23 repeats or broad policy screen are recommended automatically.
3. **Keep dynamics gated on successful steady-state review.** Only if topology evidence supports
   useful scarcity gains without material plentiful harm should the frozen six persistent
   transitions proceed. Compare OFF and both exact functions using the existing 72-JVM stage;
   the same scheduler stays alive. Evaluate post-change throughput/recovery without telemetry,
   global coordination or promoting nested windows to independent forks.
4. **Stop or reassess if the leading policy fails to generalize.** Small plentiful negatives are
   not automatic rejection, but repeated material losses or collapsed windows must remain part
   of the decision. Weak/negative medium or expensive scarcity behavior cannot be offset by a
   large W0 gain. If bounded topology/dynamic evidence fails these requirements, keep OFF and
   stop rather than expanding the model tournament or investigating the zero-function path.

The original analysis issued no execution receipt or new benchmark configuration. The subsequent
user review is now recorded in the linked R23 receipt and satisfies the existing topology gate.
No coefficients or production behavior changed. No benchmarks were run during analysis or this
handoff update; the runner executes only the topology stage next.
