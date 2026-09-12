# CACHE fifth-term screen: findings and next steps

## Finding

The screen measures real signed fifth-term effects, but **no fifth term has yet earned runtime
expansion or advancement to dynamics**. Keep the four-parameter family as the baseline and leave
production defaults, participation and runtime semantics unchanged.

The negative park-body endpoint has the strongest pooled scarce aggregate (+5.13% versus OFF;
+7.03% versus the matched 7931 anchor), and the positive park-PHR x body endpoint has positive
scarce aggregates at all three topologies (+4.23% across scarcity; +6.11% versus the anchor).
These are useful development directions, not deployable coefficients. **Every one of the eight
extensions loses R7/S7/W0 in both independent blocks**, with pooled losses from -7.83% to -19.87%.
All also lose R15/S1/W96 pooled. The intended near-neutral plentiful guardrail was not achieved.

The 4D anchor did not reproduce its preceding result. Sample 7931 changes from nine positive
scarce workload means in round5 to four here, with scarce aggregates R7 +1.18%, R15 -10.01%,
R23 +4.09% and -1.77% across scarcity. Its R7/S7/W0 loss is -21.33%. In particular, one
R15/S1/W0 anchor fork loses -40.00% versus OFF. This makes large pooled improvements over the
anchor easier to obtain; neither that fork nor any slow outcome was removed.

## Evidence and scope

The frozen `fifth-term-v1` campaign completed **264 independent JVM forks**: 11 arms x 12
workloads x two balanced blocks, retaining **1,320 nested JMH measurement windows**. Controls
were POLICY_OFF (real fixed 15,000/1,000,000 ns inference bypass), exact original live-25-center,
and exact sample 7931. Each of four families changed just one coefficient by the automatically
derived delta +/-0.30010459245033816 around its frozen residual. The four existing active
coefficients stayed fixed at 7931; zero exactly reproduced the anchor.

The nine primary workloads are S1/W0, W96 and W576 at R7, R15 and R23. Guardrails cover only
R7/S7/W0, R7/S7/W96 and R15/S15/W0. R23 plentiful execution and the other plentiful body regimes
remain unmeasured in this screen. No dynamic evidence or runtime state occupancy was collected.
Fixture work units are not measured body nanoseconds.

The audit verified every frozen artifact, launch/harness/source/topology identity, raw trial
configuration and log hash, all 264 means against their five original windows, and all pooled
throughput/minimum tables independently. Recollection reproduced all eight evidence files
byte-for-byte. All 26 current distribution JARs and current source hashes match launch identity.
Logs uniformly report JMH 1.37 and OpenJDK 21.0.2. The manifest retains the established physical
R7/R15/R23 placements, AUTO participation, disabled observers and throughput-only CONTINUOUS
static execution. The schedule remains three 2-second warmups, five 2-second measurements,
one million executions per invocation and the 120-second guard.

See [audit.json](../../../../../experiments/cache-live25-fifth-term-v1-analysis/audit.json) and
[reproducible audit](../../../../../experiments/cache-live25-fifth-term-v1-analysis/audit.py).
These are completed development measurements. Two forks are two replicates; windows are nested,
and comparing one fork against multiple references does not create more observations.

## Complete policy comparison

Pool the two fork means within each policy/workload, then take the log ratio to the same-campaign
reference. A class geometric change is `100 * (exp(mean(workload log ratios)) - 1)`.
Block comparisons use the corresponding block reference. Pooling and logging do not commute.
Scarcity breadth and individual guardrails determine the interpretation; an overall equal-workload
score is descriptive only. No CV reward, confidence target or throughput guarantee is used.

All changes below are versus POLICY_OFF except the explicitly marked anchor column. R7/R15/R23
columns contain three scarce body regimes each; positive counts are out of nine scarce workloads.

| Policy | R7 scarce | R15 scarce | R23 scarce | Scarce aggregate | Scarce vs anchor | Positive means / both blocks | Worst scarce | Worst measured plentiful |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| live-25-center | +1.57% | -4.01% | +9.17% | +2.10% | +3.94% | 6 / 3 | -12.84% | -6.27% |
| anchor-7931 | +1.18% | -10.01% | +4.09% | -1.77% | +0.00% | 4 / 0 | -14.72% | -21.33% |
| park-body (-) | +4.39% | +1.37% | +9.82% | +5.13% | +7.03% | 7 / 6 | -7.07% | -19.87% |
| park-body (+) | +1.36% | -2.38% | +5.99% | +1.60% | +3.43% | 6 / 5 | -13.14% | -7.83% |
| half-life-body (-) | +3.62% | -0.90% | +8.78% | +3.76% | +5.63% | 5 / 5 | -15.74% | -11.67% |
| half-life-body (+) | +1.11% | +0.59% | +9.16% | +3.55% | +5.41% | 6 / 5 | -8.91% | -16.91% |
| park-phr-body (-) | +0.52% | -2.34% | +9.66% | +2.49% | +4.34% | 5 / 3 | -14.30% | -14.62% |
| park-phr-body (+) | +2.79% | +1.93% | +8.09% | +4.23% | +6.11% | 6 / 4 | -5.59% | -19.09% |
| half-life-phr-body (-) | -2.90% | -3.90% | +7.31% | +0.04% | +1.85% | 5 / 4 | -19.12% | -8.53% |
| half-life-phr-body (+) | +1.73% | -0.43% | +9.89% | +3.64% | +5.51% | 6 / 4 | -12.07% | -13.24% |

The negative park-body endpoint improves all nine pooled scarce workloads versus the anchor,
but only four improve in both matched blocks. Against the original live-25 center it improves
eight pooled scarce cases, with +2.97% across scarcity. The positive park interaction improves
six pooled scarce cases versus the anchor (five in both blocks), and +2.09% across scarcity
versus the original center. Neither comparison establishes an advantage over the best achievable
4D policy across repeat campaigns.

## Body breadth and problematic workloads

This table exposes the body-regime results behind the leading aggregates. Changes are versus OFF.

| Workload | Original live-25 | 7931 anchor | Park body (-) | Park PHR x body (+) |
| --- | ---: | ---: | ---: | ---: |
| R7-S1-W0 | -0.11% | +5.46% | +8.11% | +1.62% |
| R7-S1-W96 | +4.51% | -2.19% | +4.74% | +6.80% |
| R7-S1-W576 | +0.38% | +0.42% | +0.45% | +0.08% |
| R15-S1-W0 | +1.45% | -14.72% | +12.02% | +12.24% |
| R15-S1-W96 | -12.84% | -14.46% | -7.07% | -5.59% |
| R15-S1-W576 | +0.01% | -0.11% | +0.07% | -0.06% |
| R23-S1-W0 | +18.51% | +10.97% | +20.72% | +17.80% |
| R23-S1-W96 | +11.03% | +2.66% | +10.39% | +9.31% |
| R23-S1-W576 | -1.12% | -0.99% | -0.61% | -1.93% |

- **Park body (-)** gains R7/S1/W0 and W96 in both blocks and has strong no-op gains at R15/R23.
  R15/S1/W96 is still -7.07% pooled (-15.70%, +2.80% by block). R23/S1/W576 remains -0.61%
  (-0.51%, -0.72%). Its +31.35% pooled R15/S1/W0 advantage over the anchor combines a first-block
  loss to that anchor with a large second-block gain after the anchor's -40% OFF-relative result.
- **Park PHR x body (+)** improves R7/S1/W96 by +6.80% (+7.05%, +6.53%) and has positive scarce
  aggregates at all three topologies. It still loses R15/S1/W96 by -5.59% (-10.02%, -0.52%) and
  R23/S1/W576 by -1.93% (-2.72%, -1.16%). Its R15/S1/W576 -0.06% is near neutral; that does not
  erase the more material medium-body loss.
- Expensive-body gains remain near neutral in the strongest candidates. This screen does not
  establish the hoped-for mechanism of preserving cheap-body gains while materially improving
  expensive bodies. There are no runtime state observations proving why the effects differ.

Raw pooled throughput below is millions of executions/s for the same nine scarce workloads.

| Workload | OFF | Original live-25 | 7931 anchor | Park body (-) | Park PHR x body (+) |
| --- | ---: | ---: | ---: | ---: | ---: |
| R7-S1-W0 | 29.972 | 29.940 | 31.610 | 32.402 | 30.458 |
| R7-S1-W96 | 20.421 | 21.343 | 19.974 | 21.390 | 21.809 |
| R7-S1-W576 | 12.031 | 12.077 | 12.082 | 12.086 | 12.041 |
| R15-S1-W0 | 22.542 | 22.869 | 19.225 | 25.251 | 25.303 |
| R15-S1-W96 | 15.159 | 13.213 | 12.968 | 14.087 | 14.312 |
| R15-S1-W576 | 19.341 | 19.342 | 19.320 | 19.354 | 19.329 |
| R23-S1-W0 | 18.329 | 21.722 | 20.340 | 22.128 | 21.592 |
| R23-S1-W96 | 11.885 | 13.196 | 12.201 | 13.120 | 12.992 |
| R23-S1-W576 | 25.257 | 24.974 | 25.007 | 25.101 | 24.768 |

The [complete workload table](../../../../../experiments/cache-live25-fifth-term-v1-analysis/workload_comparisons.tsv)
retains raw throughput, both reference minima, minimum fork mean and minimum nested window for
all arms and all three references. [Matched blocks](../../../../../experiments/cache-live25-fifth-term-v1-analysis/matched_blocks.tsv)
retain every run identity, fork mean and original window vector. Observed minima are not quantile
estimates or future performance guarantees.

## Plentiful guardrail

| Policy | R7/S7/W0 | R7/S7/W96 | R15/S15/W0 |
| --- | ---: | ---: | ---: |
| live-25-center | -6.27% | +4.10% | +0.28% |
| anchor-7931 | -21.33% | +7.24% | +0.13% |
| park-body (-) | -19.87% | -0.89% | -3.85% |
| park-body (+) | -7.83% | +5.51% | +5.29% |
| half-life-body (-) | -11.67% | +5.62% | -4.15% |
| half-life-body (+) | -16.91% | -0.77% | -1.65% |
| park-phr-body (-) | -14.62% | +4.03% | +0.55% |
| park-phr-body (+) | -19.09% | +8.78% | +1.79% |
| half-life-phr-body (-) | -8.53% | +5.80% | +1.51% |
| half-life-phr-body (+) | -13.24% | +10.19% | -3.26% |

R7/S7/W0 is a repeated material failure for the anchor and every extension, not a small negative
plentiful average. Park body (-) loses -20.12%/-19.63% in the two blocks; park PHR x body (+)
loses -14.18%/-23.98%. Even the least-negative extension, park body (+), loses -10.05%/-5.63%.
R7/S7/W96 is highly block-sensitive for some arms: park body (-) combines +17.25%/-16.24%.
Do not let its near-neutral pooled -0.89% conceal that spread. No complete plentiful aggregate
across topologies is available because the panel intentionally omitted six guardrail workloads.

## What the fifth dimension now identifies

Unlike the old 4D-only archive, this screen independently changes each proposed term while
preserving the other coefficients. Signed endpoint effects can now be compared at one 4D anchor.
The [endpoint contrasts](../../../../../experiments/cache-live25-fifth-term-v1-analysis/signed_endpoint_contrasts.tsv)
compare positive with negative directly, avoiding dependence on the unusually weak zero anchor.

- Park body shows a scarcity/guardrail tradeoff: its negative endpoint improves no-op scarcity
  more, while its positive endpoint reduces R7 plentiful no-op harm. The positive endpoint still
  loses that guardrail and has a -13.14% R15/S1/W96 loss.
- Park PHR x body's positive endpoint improves medium scarcity at R7/R15 relative to its negative
  endpoint, but worsens R7 plentiful no-op and R23 expensive scarcity.
- Half-life body and half-life PHR x body show measured directional differences too; neither
  demonstrates a sufficiently broad tradeoff to earn priority over the two park hypotheses in
  this batch. This does not prove their entire families are inferior.

These are measured complete-policy effects, not per-state reward labels or evidence that a
particular runtime body distribution was visited. Each nonzero term has only two new theta,
three levels including zero, and one location in the original four-dimensional space. That
supports a local signed screen; it cannot establish how the fifth term interacts with moving
all four base parameters. The [dimensional-support audit](../../../../../experiments/cache-live25-fifth-term-v1-analysis/identification.json)
records 28 compatible theta per family (1,176 live forks and 204 OFF forks; overlapping evidence
across families), missing targets and design rank. The 21-column full quadratic has rank 17: the
fifth-by-base interactions are not independently excited at a single base location. None of the
three complete plentiful aggregates has nonzero-fifth coverage, although three individual
guardrail outputs do. No new model tournament, fit,
manual coefficient selection or benchmark proposal was run for this results analysis.

## Next steps

1. **Keep 4D as the baseline; do not promote these exact 5D endpoints.** Preserve both original
   live-25 and 7931 observations across campaigns. The latest repeated R7/S7/W0 losses and R15/W96
   misses are measured constraints, and must not be replaced by the earlier favorable aggregate.
2. **Use the consolidated evidence for an honest offline 4D-versus-5D comparison.** The next
   preparation can retain at most the park-body and park-PHR x body families as hypotheses.
   Compare their models with input-ablated 4D models on identical rows and held full-theta groups,
   retain center-relative and OFF-relative returns, and mark unavailable plentiful outputs as
   unresolved. Resolve archived task paths before running JSON tasks. The sparse one-anchor
   fifth-term slice limits claims about a general 5D response surface; models remain search aids.
3. **Only propose further JVM work if the comparison identifies a useful tradeoff to test.** Let
   the JSON-driven tuner choose any next weights. A bounded follow-up should test whether moving
   the four base coordinates together with one added term can repair R7/S7/W0 and R15/S1/W96
   without losing scarce no-op gains. Include matched OFF and 4D controls and the expensive-body
   cases; do not spend a new broad campaign only re-establishing a no-op gain.
4. **Require measured added value before retaining the fifth coefficient.** If an extension
   cannot beat comparable 4D behavior on body breadth and plentiful harm, continue with 4D.
   Retain at most two separate 5D hypotheses; never combine both added terms to rescue this screen.
   No dynamics or default change until an acceptable steady-state tradeoff survives confirmation.

No new campaign is authorized or executed by this report. The immediate next stage is offline
comparison and review of what bounded measurement, if any, would distinguish 4D from 5D.

## Consolidation and cleanup

All retained history is in [cache-run-data.tsv](../../../../../experiments/cache-run-data.tsv).
The archive now contains **2,718 run rows, 14,090 nested windows and 5,831 original artifacts**.
This append adds 264 forks, 1,320 windows and 589 artifacts, including raw logs/configs, collected
evidence, and the complete finished fifth-term preset tree. Every prior TSV row was preserved
exactly. Every new artifact was restored and hash-checked before deleting redundant copies.

Archive SHA-256: `42dc5cb78090553a3cb3a2cee1438e908589225257d23715b5ad06e90248b53c`.
The queryable `run` rows carry policy/config identities, means and nested windows; `artifact`
rows preserve lossless compressed originals. Filter by `recordType` before treating it as a table
of independent runs.

Removed the completed raw-run directory, duplicate collected-evidence directory and old
`fifth-term-v1` preset directory after verification. Readable analysis, findings and source/test
files remain. Existing user changes, including the staged Java-test deletion, were preserved.
The [cleanup receipt](../../../../../experiments/cache-live25-fifth-term-v1-analysis/cleanup.json)
contains the exact deletion inventory and restoration checks. Reproducible analysis and audit
scripts read from the archive after cleanup.

To restore original files into a new temporary directory without repopulating old presets:

```bash
python3 -I python/pareto-weight-calibration/src/pareto_weight_calibration/run_archive.py restore \
  --archive experiments/cache-run-data.tsv --destination /tmp/cache-fifth-history
```

Validation consists of independent raw-log recomputation, full recollection, artifact/source/build
identity checks, archive verification and restoration, and an archive-only replay after cleanup.
No Java/runtime code changed, so no Gradle build or JMH run was needed.
