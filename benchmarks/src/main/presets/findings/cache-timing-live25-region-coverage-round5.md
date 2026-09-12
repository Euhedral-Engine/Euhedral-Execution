# CACHE live-25 round5: preserve useful measured regions

Completed campaign: [measured findings, aggregation and next steps](cache-timing-live25-round5-results.md). The preparation record below remains historical.

Round5 adds one automatically selected proposal, dense Sobol sample **136**, to the six
unchanged round4 proposals. It covers the previously omitted sample 0015 neighborhood at
normalized distance **0.141907**, within the declared **0.30** radius. The previous nearest
proposal was 0.720606 away. All four qualifying historical neighborhoods are now represented.
No JMH benchmarks were executed, no models were refitted, and no runtime/default changes were made.

## What qualifies and what changed

The new JSON policy generalizes the retrospective audit's three measured usefulness coordinates:
positive scarce workload count, minimum-topology scarcity, and capped broad scarcity. It retains
the non-dominated measured tradeoffs among points with at least half their scarce workloads
positive, nonnegative minimum-topology and capped broad returns, and at least two independent
matched blocks per target. Positive returns are capped at 25% for the broad coordinate.
Qualification is evaluated per campaign and retained across campaigns. No policy ID list is used.
Plentiful floors are not an additional historical qualification veto in this revision; existing
history exclusions, reliability-aware soft risk, floors and measured production guardrails remain
unchanged. Historical usefulness is a reason to explore nearby, not a production acceptance claim.

This definition intentionally differs from the retrospective top-quarter lexicographic label:
that label can omit a useful tradeoff with fewer positive bodies but a stronger broad return.
The old retrospective results remain byte-for-byte intact; they are not relabeled as validation
of the new coverage constraint.

| Historical point     | Qualifying campaign         | Positive scarce bodies | Minimum topology / capped broad change | Eligible / Pareto within radius | Represented before  | Representative now | Distance now |
|----------------------|-----------------------------|-----------------------:|---------------------------------------:|--------------------------------:|---------------------|--------------------|-------------:|
| sample 0015          | initial Sobol tuning        |                    6/9 |                        +0.98% / +6.03% |                        138 / 58 | No                  | sample 136         |     0.141907 |
| sample 0004          | initial Sobol tuning        |                    7/9 |                        +3.42% / +3.85% |                        201 / 76 | Yes, 7931           | sample 7931        |     0.233958 |
| sample 0008          | initial Sobol tuning        |                    8/9 |                        +3.10% / +4.76% |                       132 / 116 | Yes, 3799           | sample 3799        |     0.263761 |
| exact live-25 center | measured proposals campaign |                    6/9 |                        +1.47% / +3.94% |                       268 / 148 | Yes, 2574/4755/6321 | nearest: 4755      |     0.163312 |

No qualifying region has sufficient later rejection evidence. The generic rejection rule requires
explicit campaign order, later measurements inside the radius, two complete blocks per target,
and consistently poor breadth, minimum-topology and broad returns in every later block. A weak
prediction, an earlier poor result, incomplete evidence or contradictory later outcomes cannot
retire a region. Historical qualification remains visible even if later measurements reject it.

Coverage is checked after all existing eligibility and Pareto rules. A shared representative must
be inside each neighborhood it covers; computational basin membership does not count. Normal
selection runs first, followed by deterministic bounded coverage search. Consensus, direction,
coverage and distance priorities are JSON-configured; sample index breaks remaining ties.
The maximum is eight proposals and four required region representatives. This run needed only
one addition, so all existing roles, including sample 6904's disagreement/exploration role,
remain intact. Infeasible capacity/diversity constraints stop preparation instead of silently
omitting a supported useful region.

## Unchanged search and revised batch

- Same 8,192 dense Sobol points; 7,960 eligible, 2,486 non-dominated, three computational basins.
- Same authority: 0 HARD, 20 SOFT, 28 UNRESOLVED; same predictions, soft risks and bounds.
- Original samples 2574, 4755, 6321, 3799, 7931 and 6904 retain their exact functions, roles,
  per-output predictions and basin identities. Sample 136 is the new `known_useful_region` role.
- The added point's nearest measured theta is sample 0015 at distance 0.141907, exceeding the
  existing 0.10 history-distance exclusion. Batch minimum pair distance remains 0.201334,
  exceeding the existing 0.15 diversity requirement. It passed the existing support/clamp checks.
- All 48 outputs, individual selected-model predictions, disagreement, SOFT penalties and
  UNRESOLVED guardrails remain in the consensus/proposal artifacts. Coverage does not establish
  safety or throughput improvement. Matched benchmark throughput remains the oracle.

**Nine arms:** POLICY_OFF (actual inference bypass, 15,000/1,000,000 ns), the exact live-25
center, and seven proposed theta. No zero-function baseline or other historical policy is added.

| Topology | Worker CPU placement                    | Scarce fixtures        | Plentiful fixtures        |
|----------|-----------------------------------------|------------------------|---------------------------|
| R7       | CPU 2..15, resolved 7 physical workers  | S1/W0, S1/W96, S1/W576 | S7/W0, S7/W96, S7/W576    |
| R15      | CPU 2..23, resolved 15 physical workers | S1/W0, S1/W96, S1/W576 | S15/W0, S15/W96, S15/W576 |
| R23      | CPU 2..31, resolved 23 physical workers | S1/W0, S1/W96, S1/W576 | S23/W0, S23/W96, S23/W576 |

Harness CPU remains 0. There are 18 workloads and two balanced independent JVM blocks:
**9 x 18 x 2 = 324 JVM forks**, with five nested measurement windows each (1,620 planned windows).
The existing CONTINUOUS lifecycle, three 2-second warmups, five 2-second measurements,
one-million-execution invocation target and 120-second guard remain. These are experiment
settings, not elapsed-time or future throughput guarantees. Retain every fork and window.
Scarce and plentiful responses remain separate; no overall aggregate decides advancement.

## Reproduction and artifacts

The generic JSON runner reuses the pinned round4 lock, fitted models and validation artifacts:

```bash
export PYTHONPATH="$PWD/python/pareto-weight-calibration/src"
/tmp/euhedral-surrogate-tournament-venv/bin/python -m pareto_weight_calibration.training_runner \
  --task python/pareto-weight-calibration/tasks/live25-region-coverage-round5.json \
  --output-dir /tmp/euhedral-region-coverage-replay
```

The output must be new. Replay verifies every parent artifact, unchanged modeling/search
contracts and dependency versions before deserializing the saved local models. It recomputes
the same dense predictions and coverage without rerunning the 80-configuration/14-family
model tournament. Dataset, fitted models, selection, validation, reliability classification
and retrospective evidence are copied byte-for-byte with explicit reuse provenance.

- [JSON task](../../../../../python/pareto-weight-calibration/tasks/live25-region-coverage-round5.json)
- [Known-region policy](../../../../../experiments/surrogate-live25-round5/known_region_policy.json)
- [Coverage table](../../../../../experiments/surrogate-live25-round5/known_region_coverage.tsv) and
  [complete coverage audit](../../../../../experiments/surrogate-live25-round5/known_region_coverage.json)
- [Proposals](../../../../../experiments/surrogate-live25-round5/proposals.json),
  [consensus](../../../../../experiments/surrogate-live25-round5/proposal_consensus.tsv), and
  [basins](../../../../../experiments/surrogate-live25-round5/basins.json)
- [Reuse provenance](../../../../../experiments/surrogate-live25-round5/reuse_provenance.json) and
  [round5 lock](../../../../../experiments/surrogate-live25-round5/lock.json)
- [Executable handoff](../../../../../experiments/surrogate-live25-round5/benchmark/HANDOFF.md),
  [candidate manifest](../../../../../experiments/surrogate-live25-round5/benchmark/candidate_manifest.json),
  [harness](../../../../../experiments/surrogate-live25-round5/benchmark/harness.json), and
  [collection config](../../../../../experiments/surrogate-live25-round5/benchmark/collection.json)

## Validation and next step

The focused/generic Python selection, tournament, training, parameter, optimizer, CV, nesting
and compatibility suites passed: **69 passed, two CUDA-unavailable skips, two legacy frozen-artifact
checks explicitly deselected**. Nine new generic tests cover supported/unsupported regions,
later rejection, overlapping neighborhoods, distance versus basin membership, automatic choice,
measured-point exclusion, protected exploration, deterministic expansion/conflicts, JSON contracts
and verified model replay. No Gradle was needed; Java and generated Java were unchanged.

The final handoff passed `cache_timing_confirmation check --stage topologies`, including current
source hashes and physical topology. Both round4 and round5 artifact locks verify; round4 was
not overwritten. The six original selected functions/responses/roles/basins match exactly.
`git diff --check` passed. No execution blocker remains.

Next: review the bounded nine-arm handoff, then execute its 324 forks through the existing
runner. Retain all outcomes and assess broad scarce-source improvement with plentiful-source
harm visible separately. This preparation ends here; no benchmark or new model-fit evidence
was generated by the coverage change.
