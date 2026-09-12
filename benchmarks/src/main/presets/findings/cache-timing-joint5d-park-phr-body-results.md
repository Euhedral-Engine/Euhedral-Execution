# CACHE joint 5D park-PHR-body: findings and next steps

## Finding

**2531 is the most promising interaction proposal measured here, but none of the three improves
scarcity over matched 7931 across topologies. Neither joint 5D stage has established a deployable
winner or demonstrated that a fifth coefficient earns its complexity.**

2531 gains +2.58% geometrically across scarce workloads versus POLICY_OFF, but loses -1.54% versus
same-campaign 7931. Its scarce aggregates are -0.84% at R7, +4.08% at R15 and +4.60% at R23;
all three trail 7931. Six of nine scarce workloads improve versus OFF, five in both blocks.
R7/S1/W96 loses -10.98%, including losses in both blocks. R7/S7/W96 loses -7.88% in plentiful
execution, also in both blocks. Approximately neutral plentiful aggregates conceal that material loss.

Both planned joint-5D stages are now complete. The primary park-body stage measured four proposals
in 252 forks; this secondary park-PHR-body stage measured three proposals in 216 forks. They are
separate matched campaigns, with separate OFF/4D references. This report supersedes the pending-secondary
next step in [the park-body results](cache-timing-joint5d-park-body-results.md) and completes the
execution status of [the offline preparation](cache-timing-joint5d-v1.md).

**Recommendation: prioritize the park-body region around measured 3381 for the next offline update;
retain interaction 2531 as a secondary tradeoff region.** This is development prioritization, not a
cross-campaign claim of superiority. Incorporate the seven new measured proposals before generating
more JVM work. Do not repeat the one-anchor screen, manually set coefficients, combine fifth terms,
or advance to dynamics. This analysis ran no new JMH trials, tournament or candidate generator.

## Evidence and provenance

Verified exactly **216 independent JVM forks and 1,080 nested measurement windows**: six arms x
18 workloads x two balanced blocks. Arms are POLICY_OFF, original live-25, exact 4D anchor 7931,
and generated samples 2531, 1812 and 6756. Every trial config/log hash, declared treatment, fixture,
schedule, labels, launch lock, harness, source and topology identity matches. All 26 current
distribution JARs match launch hashes. Logs report JMH 1.37 and OpenJDK 21.0.2.

All five measurement windows independently reproduce each fork mean. A fresh collection into a
temporary directory reproduced all seven original evidence files byte-for-byte. Pooled workload
throughput, class summaries and both observed minima also reproduce independently. All slow outcomes
are retained; no bad fork was rerun or removed.

R7/R15/R23 use the previously resolved CPU placements: logical CPU lists 2..15, 2..23 and 2..31,
respectively, resolving to 7/15/23 physical workers, with harness CPU 0. Each topology uses S1 and S=R
crossed with W0/W96/W576. Work units remain fixture definitions, not runtime body nanoseconds or PHR.
AUTO participation has no forced cutoff; observers are disabled. CONTINUOUS is static here, not a
persistent regime transition. Schedule is three 2-second warmups, five 2-second measurements,
one million executions per invocation and the 120-second guard, without wall-clock guarantees.

The primary reference is same-campaign POLICY_OFF with inference bypassed and fixed 15,000/1,000,000 ns.
Both live references and the live candidates retain the frozen runtime semantics and bounds. A local
fragment chooses how to idle; effective collective participation emerges without a global target.

## Measured throughput

Pool two fork means per workload, divide by the corresponding pooled reference, and take the log.
Geometric changes average workload logs within each class/topology. Block results instead use the
corresponding block's reference fork. Pooling and logging do not commute. Windows remain nested;
blocks are ordering groups, not simultaneous experiments. Scarce and plentiful results are separate;
no equal-weight overall score or CV determines advancement.

All changes in this table are versus POLICY_OFF. Positive/both-block counts refer to nine scarce
workloads and describe replication consistency; every individual fork need not be positive.

| Policy | R7 scarce | R15 scarce | R23 scarce | Scarce geometric | Positive / both blocks | R7 plentiful | R15 plentiful | R23 plentiful |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| live-25-center | -3.57% | +3.32% | -2.52% | -0.97% | 5/9 / 2/9 | +0.68% | -1.42% | -0.04% |
| anchor-7931 | +0.77% | +5.90% | +5.99% | +4.19% | 5/9 / 3/9 | -0.26% | -2.61% | -0.28% |
| 2531 | -0.84% | +4.08% | +4.60% | +2.58% | 6/9 / 5/9 | -1.12% | -0.73% | -0.80% |
| 1812 | -7.98% | +3.26% | +5.36% | +0.04% | 5/9 / 2/9 | +0.75% | -1.48% | -0.00% |
| 6756 | -7.46% | -1.31% | +2.52% | -2.17% | 4/9 / 3/9 | -0.67% | -0.61% | +0.26% |

Matched reference comparisons:

| Proposal | Scarce vs OFF | Scarce vs 7931 | Scarce vs original live-25 |
| --- | --- | --- | --- |
| 2531 | +2.58% | -1.54% | +3.59% |
| 1812 | +0.04% | -3.99% | +1.02% |
| 6756 | -2.17% | -6.11% | -1.21% |

2531's +3.59% versus the original live-25 center should not be presented as a gain over the strongest
matched 4D reference: 7931 is better on each topology's scarcity aggregate. The original center has a
large R23 expensive-body loss in this campaign, which makes it an easier comparator.

| Scarce fixture | live-25-center | anchor-7931 | 2531 | 1812 | 6756 |
| --- | --- | --- | --- | --- | --- |
| R7-S1-W0 | +0.77% | +9.37% | +7.78% | -0.18% | +2.30% |
| R7-S1-W96 | -11.99% | -6.85% | -10.98% | -21.74% | -22.41% |
| R7-S1-W576 | +1.11% | +0.44% | +1.63% | -0.26% | -0.15% |
| R15-S1-W0 | +10.31% | +13.66% | +10.03% | +9.54% | +8.75% |
| R15-S1-W96 | -0.20% | +4.62% | +2.02% | +0.50% | -11.48% |
| R15-S1-W576 | +0.18% | -0.12% | +0.43% | +0.01% | -0.16% |
| R23-S1-W0 | +13.15% | +22.42% | +17.09% | +15.79% | +12.12% |
| R23-S1-W96 | -3.77% | -2.66% | -1.01% | +1.75% | +0.05% |
| R23-S1-W576 | -14.92% | -0.07% | -1.27% | -0.71% | -3.95% |

- **2531:** R7 no-op scarcity +7.78% and expensive-body +1.63% are consistent across blocks, but
  medium-body -10.98% prevents broad R7 improvement. R15 scarcity improves across all three pooled
  bodies, with medium-body +2.02% split +4.56%/-0.60%. R23 gains are concentrated in W0 (+17.09%);
  W96 loses -1.01% and W576 -1.27%. Its +4.61% R7 plentiful no-op result is welcome, but accompanies
  -7.88% R7 plentiful medium-body and -3.27% R15 plentiful no-op losses, both consistently negative.
- **1812:** scarce aggregate +0.04% conceals -7.98% at R7. All three R7 scarce means are negative;
  W96 loses -21.74%, including -37.29% in block 0. Positive R15/R23 aggregates do not compensate.
  Plentiful aggregates are comparatively mild, but R15/S15/W0 still loses -5.32% in both blocks.
- **6756:** exploration returns -2.17% scarcity overall and -6.11% versus 7931. R7/S1/W96 loses
  -22.41% and R15/S1/W96 -11.48%, both blocks negative in each fixture. This sampled region does not
  warrant a matched repeat simply because the model had predicted upside.

The selection roles were fixed before measurement: 2531 and 1812 were consensus representatives
of two computational basins; 6756 was the disagreement/exploration proposal. These labels do not
confer measured quality. Negative outcomes remain valid training evidence.

## Replication and observed lower outcomes

| 2531 fixture | Pooled vs OFF | Block 0 | Block 1 |
| --- | --- | --- | --- |
| R7-S1-W0 | +7.78% | +6.28% | +9.27% |
| R7-S1-W96 | -10.98% | -16.27% | -4.79% |
| R7-S1-W576 | +1.63% | +1.57% | +1.70% |
| R7-S7-W0 | +4.61% | +1.25% | +8.15% |
| R7-S7-W96 | -7.88% | -14.40% | -1.43% |
| R15-S1-W0 | +10.03% | +17.94% | +2.79% |
| R15-S1-W96 | +2.02% | +4.56% | -0.60% |
| R15-S1-W576 | +0.43% | +0.46% | +0.40% |
| R15-S15-W0 | -3.27% | -5.09% | -1.47% |
| R23-S1-W0 | +17.09% | +15.42% | +18.86% |
| R23-S1-W96 | -1.01% | -3.82% | +2.01% |
| R23-S1-W576 | -1.27% | -0.35% | -2.20% |

Observed minima below are millions of executions/s: minimum fork mean / minimum measurement window.
They are observations from two forks and ten windows, not lower quantile estimates or guarantees.

| Fixture | OFF min fork/window | 7931 min fork/window | 2531 min fork/window |
| --- | --- | --- | --- |
| R7-S1-W96 | 21.986 / 20.581 | 22.183 / 22.062 | 20.933 / 20.825 |
| R7-S7-W96 | 64.685 / 64.475 | 64.973 / 64.829 | 55.368 / 55.327 |
| R15-S15-W0 | 769.687 / 721.042 | 717.828 / 679.048 | 730.532 / 700.200 |
| R23-S1-W576 | 25.132 / 24.916 | 25.040 / 24.425 | 24.621 / 24.050 |

Two exploratory forks per treatment do not settle small practical differences. Large repeated
losses at individual scarce/plentiful fixtures remain relevant even when aggregate changes are mild.
The complete tables retain every fork, all windows, raw pooled throughput, and reference minima.

## What the two stages establish together

Park-body 3381 gained +4.54% scarce versus its OFF and +0.84% versus its matched 7931, with all three
scarce topology aggregates positive. Interaction 2531 gained +2.58% versus its OFF and lost -1.54%
versus its matched 7931, with negative R7 scarcity. This favors **park-body as the first development
priority**, while retaining 2531's different plentiful/medium-body behavior for comparison.

These are not head-to-head candidate measurements. Reference behavior moved substantially:

- 7931 R7 plentiful no-op: -24.68% in the park-body campaign, -1.05% in this campaign.
- 7931 R7 scarce medium-body: +9.56% previously, -6.85% here.
- 7931 R15 scarce medium-body: -10.18% previously, +4.62% here.
- Original live-25 R23 scarce expensive-body: +4.00% previously, -14.92% here, including -28.50%
  in the second block.

Matching source/build/topology identities does not explain these differences or remove their
uncertainty. Do not infer a mechanism, pool OFF baselines across campaigns, or claim that interaction
caused the disappearance of the earlier R7 no-op harm: the 4D reference also changed. Keep repeated
centers campaign-qualified and use them to evaluate transfer in the next offline fit.

All seven new policies changed the four original coordinates as well as their fifth coordinate.
Neither stage contains a zero-fifth counterpart at each proposed point's exact four base coordinates.
They measure achievable complete-policy tradeoffs; they do not causally isolate the fifth term.
A matched 4D counterpart remains the cleanest next complexity comparison for any finalist.

## Frozen predictions and dimensional support

The 144-row prediction comparison uses mean matched-block log return, matching the model target.
Percentages here can differ slightly from the pooled table. All 48 outputs retain predictions,
observations, reliability roles and disagreement; disagreement is not a confidence interval.

- 2531 R7 scarce medium-body: predicted +2.58%, measured -10.71%.
- 6756 R7 scarce medium-body: predicted +15.60%, measured -22.64%.
- 2531 R7 plentiful medium-body: predicted -0.50%, measured -8.15%.
- 2531 R7 plentiful no-op: predicted -8.43%, measured +4.64%.
- 2531 R15 scarcity aggregate: predicted +5.05%, measured +4.07%.

The frozen secondary reliability counts remain 0 HARD / 26 SOFT / 22 UNRESOLVED. In particular,
R7 scarce medium-body was UNRESOLVED. Permitting uncertain proposals correctly allowed measurement;
that does not imply success or safety. The forecasts provided mixed direction and missed important
fixture behavior. There is no matched blind/random selection arm, so this small selected batch does
not establish better search efficiency than random exploration.

Compatible park-PHR-body support now contains **31 unique theta, five with nonzero fifth delta**.
Its normalized second-order design rank rises from 17/21 to 20/21. Park-body has 32 unique theta,
six nonzero fifth points and rank 21/21. Those counts share much of the 4D history and must not be
summed as disjoint datasets. The interaction rank deficiency is expected from three new joint points;
it is not evidence that the family failed. Full rank in park-body likewise does not prove a reliable
response model. This turn computed support only; it did not refit either surrogate.

## Next steps

1. **Update the two compatible datasets in a new offline revision before more JVMs.** Add each
   stage only to its own five-parameter family, retain common 4D history on the zero plane, all
   repeated references, same-campaign OFF/7931 log targets, exact theta grouping and nested windows.
   Use campaign-transfer diagnostics to expose the reference variation above. Never mix the two
   nonzero fifth terms into one clean 5D representation.
2. **Run the existing JSON-driven broad tournaments and matched 4D ablations on that evidence.**
   Preserve the bounds, reliability-aware constraints and known-useful-region coverage. Evaluate
   held-theta ranking/regret and retrospective search efficiency, not training error alone. Treat
   these now-analyzed trials as development evidence. Rank deficiency or weak guardrail predictors
   must not become automatic reasons to abandon an entire family.
3. **Prioritize park-body 3381's region; retain interaction 2531 conditionally.** Let Python choose
   any subsequent coefficients. Do not manually recenter, expand the box, repeat the one-anchor
   screen, or broadly rerun 1812/6756. Retain 7931 as the canonical 4D development reference;
   neither its older reputation nor the original center's weaker current result establishes a
   universally best or production-safe point. If both families retain distinct useful predicted
   tradeoffs, keep them separate in a small reviewed round.
4. **Make the next expensive comparison explicit about 4D versus 5D.** For a finalist, include
   POLICY_OFF, the exact 4D reference and its same-base zero-fifth counterpart in a compact matched
   design. If 3381 and 2531 both remain finalists, measure them in the same campaign. Prioritize
   R7/S1/W96, R15/S1/W96, expensive-body scarcity, R7/S7/W96 and R15/S15/W0 while preserving
   cross-topology scarce breadth and the R7 no-op guardrail. Strengthen replication only for those
   bounded finalists or unresolved practical comparisons; keep every slow outcome.
5. **Require measured value for added complexity, then confirm.** Continue tuning if a fifth term
   gives a materially better scarcity/plentiful tradeoff than comparable 4D behavior. If neither
   does, return to 4D. Do not add a sixth term or combine extensions. Dynamics and production
   changes remain deferred until a useful steady-state tradeoff survives confirmation.

There is no pending benchmark stage after this cleanup. The next recommended work is the bounded
offline evidence update and family comparison, followed by human review of any new measurement plan.
This request did not prepare or execute a new campaign, change runtime semantics or production defaults.

## Artifacts, aggregation and cleanup

- [Independent provenance audit](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/audit.json)
- [Reproducible analysis](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/analyze.py)
- [All policy/reference summaries](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/policy_summary.tsv)
- [Pooled throughput and minima](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/workload_comparisons.tsv)
- [Every matched block and nested windows](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/matched_blocks.tsv)
- [Topology/class and block summaries](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/topology_class_summary.tsv)
- [All forecast consequences](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/prediction_comparison.tsv)
- [Both stages with separate references](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/family_comparison.tsv)
- [Repeated 4D/control observations](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/repeated_reference_observations.tsv)
- [Numeric design support](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/support.json)
- [Recollection and validation](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/validation.json)
- [Lossless cleanup inventory](../../../../../experiments/cache-live25-joint5d-v1-park-phr-body-analysis/cleanup.json)

The single [cache-run-data.tsv](../../../../../experiments/cache-run-data.tsv) now contains
**3,186 run rows, 16,430 nested windows and 6,927 original artifacts**.
This append adds 216 forks and 1,080 windows. Every previous row is byte-for-byte preserved.
The archive SHA-256 is `bafcc0ce64ae4d64f4187238ba460ce6c612cd4b8aa117d6e0688da10b721f52`.

`recordType=run` contains queryable fork data and full nested windows. `recordType=artifact` contains
lossless, checksummed compressed originals. Both campaigns' raw data and frozen inputs/results are
recoverable, including models, datasets, predictions, manifests, source locks and executable presets.
After verifying every new artifact and an on-disk restoration, removed **584 redundant files**
from these completed directories:

- `experiments/cache-live25-joint5d-v1-park-phr-body-measurement`
- `experiments/cache-live25-joint5d-v1-park-phr-body-measurement-evidence`
- `experiments/cache-live25-joint5d-v1`
- `benchmarks/src/main/presets/cache-timing/joint5d-v1`

Retained both readable measured-analysis directories, all findings, source code and JSON tasks/inputs
used by the joint-5D tests. No runtime files or unrelated worktree changes were removed. Earlier report
links into the completed frozen directory refer to historical archived artifacts; restore them to a
separate directory if needed. Analysis/audit scripts read the archive automatically after cleanup.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 experiments/cache-live25-joint5d-v1-park-phr-body-analysis/analyze.py
PYTHONDONTWRITEBYTECODE=1 python3 experiments/cache-live25-joint5d-v1-park-phr-body-analysis/audit.py
python3 -I python/pareto-weight-calibration/src/pareto_weight_calibration/run_archive.py verify --archive experiments/cache-run-data.tsv
```

For a full restore, use the same archive tool's `restore --archive experiments/cache-run-data.tsv
--destination <new-directory>` command; it refuses to overwrite existing files. Restore old task
inputs and frozen source identities together when replaying a historical tournament. Do not use the
completed benchmark handoffs to rerun individual bad outcomes.

Validation: four joint-5D JSON contract tests passed; collection replay, independent raw provenance
and throughput audits, archive restoration and archive-only result replay passed. `git diff --check`
passed. Older confirmation tests were not rerun: their pre-existing missing live-v2 test fixture was
already reported in the primary analysis and is outside this cleanup. No Java/generated Java changed;
Gradle and JMH were not run.
