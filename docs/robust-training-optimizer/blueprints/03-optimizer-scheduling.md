# Phase 3 Blueprint: Robust Optimizer and Closed-Loop Scheduling

Status: ready for Prompt 3B implementation

This blueprint settles the Phase 3 optimizer, candidate-budget, coverage-completion, benchmark,
checkpoint, and restart contracts. Prompt 3B must implement these decisions without reopening the
Phase 1 statistical model or the Phase 2 predictor. If implementation cannot satisfy a settled
contract, stop and append the conflict here instead of selecting a different architecture.

## Scope

Phase 3 replaces the pooled closed-loop path with a scenario-aware path that:

1. compares complete predicted policy curves with a lexicographic robust comparator;
2. seeds CMA-ES islands from measured robust leaders and ranks every generated population by that
   comparator;
3. screens Sobol candidates into deterministic robust-quality bands while retaining a direct
   exploration fraction;
4. divides each exact source scenario's policy budget among fixed anchors, carry-forward
   completion, robust-leader revalidation, disagreement audits, and new exploration;
5. keeps admitted incomplete policies in a persisted carry-forward queue until every required
   scenario has valid coverage;
6. rotates only exact scenarios runnable by the active environment and persists a separate cursor
   for each environment/core group;
7. writes complete native Phase 1 observation bundles with stable policy, scenario, run, cohort,
   role, seed, repetition, and status identity;
8. retrains only the accepted Phase 2 scenario-conditioned model and never schedules from a
   rejected or pooled model;
9. checkpoints every state transition in atomic, versioned snapshots and resumes a pending
   schedule without changing it; and
10. finishes every successful iteration with a new Phase 1 merge so its latest robust ranking and
    coverage include that iteration's evidence.

The scheduling unit is one exact `SourceScenario` run with a fixed policy budget. A normal
iteration may contain several scenario runs. It is not one global candidate file blindly
benchmarked under every source count.

### Explicit non-goals

- Do not change Phase 1 policy/scenario identity, calibration mathematics, aggregation, quality,
  robust comparator, observation bundle schema, or merger output schemas.
- Do not change Phase 2 features, targets, grouped split, evaluation, uncertainty decoder, model
  metadata, acceptance gates, or artifact layout.
- Do not turn predicted or measured robust ordering into one weighted scalar.
- Do not allow an incomplete policy into the measured robust-leader pool, regardless of its
  prediction.
- Do not use rolling leaders as calibration anchors. Only `FIXED_ANCHOR` observations affect run
  calibration.
- Do not infer environment identity, required scenarios, run identity, or benchmark parameters
  from a path.
- Do not read current alternating vector/measurement files, old pooled models, old checkpoints, or
  user-owned `euhedral-training/input`, `output`, or `data` trees as new-format evidence.
- Do not implement the Phase 5 current-workspace importer or its final CLI/configuration surface.
- Do not create the Phase 4 final result package, Markdown reports, package manifest, or raw-data
  index.
- Do not support concurrent writers to one closed-loop workspace. Sequential use of the same
  workspace on different required environments is supported.
- Do not delete incomplete benchmark attempts, unrelated workspace data, or old checkpoint
  snapshots.
- Do not add a training or predictor dependency to `euhedral-core`.

## Reconciliation with the current implementation

| Current code | Current contract | Phase 3 decision |
| --- | --- | --- |
| `SequenceFinder.loadTrainingData` | Reads pooled alternating vector/five-quantile rows | Move this implementation behind the pooled-v0 compatibility boundary. The new `SequenceFinder` accepts Phase 1 merge state and a loaded accepted Phase 2 curve predictor. |
| `SequenceFinder.generate` | Produces one headerless vector file and uses one classifier scalar | Produce typed predicted candidates and per-scenario schedules. No new path writes or reads the headerless format. |
| `PolicyOrdinalNetwork.predictScores` | Scores only 28 weights | The new path calls `ScenarioConditionedModel.predictConfiguredCurves`; there is no 28-value adapter. |
| `CmaEsOptimizer.MeasuredPolicy` | Holds a vector plus five pooled quantiles | Replace it with Phase 1 eligible `RobustPolicySummary` seeds. |
| `CmaEsOptimizer.BatchScorer` | Returns one `float` per candidate | Replace it with a curve prediction service returning exact `PredictedPolicySummary` records. |
| `CmaEsOptimizer` population order | Descending scalar score with a `1.0e-6f` stagnation tolerance | Use the exact predicted robust comparator. Comparator improvement resets stagnation; there is no score epsilon. |
| `ScoreBandSampler` | Uses empirical scalar thresholds and order-sensitive random reservoirs | Use ten fixed worst-scenario-quality bands and seeded, hash-priority bottom-k reservoirs that are independent of arrival order. |
| `ClosedLoopRunner` | P99 merge -> pooled train -> global vector generation -> text benchmark | Calibration-plan bootstrap -> Phase 1 merge -> Phase 2 train -> per-scenario schedule -> native v1 benchmark -> Phase 1 post-merge. |
| `ClosedLoopRunner.writeState` | Timestamped `Properties.store` with an absolute artifact path | Replace it with strict atomic checkpoint snapshot directories, relative paths, artifact hashes, carry state, rotation cursors, and pending run identity. |
| `ClosedLoopRunner` resume | Deletes an incomplete iteration and starts it again | Reuse verified merge/model/schedule artifacts, adopt complete expected bundles, and rerun only an incomplete scenario attempt under the already persisted run identity. |
| `BenchmarkRunner.runAcrossSourceCounts` | Rotates clamped integer source counts from the iteration number | Move it behind the pooled-v0 compatibility boundary. The new `BenchmarkRunner` consumes persisted exact `SourceScenario` plans selected by a checkpointed per-environment cursor and never clamps a scenario. |
| `BenchmarkRunner` raw output | Alternating vector and zero-filled repetition arrays | Stream a complete Phase 1 bundle with explicit `SUCCESS`, `TIMEOUT`, `FAILED`, and `SKIPPED` observations. |
| `BenchmarkRunner.createSinks` | Chooses hidden `ThreadLocalRandom` ID/routing seeds | Derive and persist one ID hash and routing seed per source before the run. |
| `BenchmarkFrame.generate` | Chooses its routing seed internally | Add a deterministic overload accepting the routing seed; retain the random overload only for legacy callers. |
| `BenchmarkRunner` repetition reset | Resets a shared counter while sources are flowing | Keep counters monotonic within a policy and measure deltas. Reset only behind the established pause barrier. |
| `BenchmarkRunner` evidence writes | Writes a policy after its trial, while the next trial is not yet active | Continue writing only while sources are paused; collect one policy's small repetition record in memory first. |
| `Runner train-vector-finder` | Instantiates `SequenceFinder` for pooled train/generate | Point this transitional command to a dedicated pooled-v0 compatibility class. Phase 5 owns its final replacement. |
| Current tests | Cover scalar CMA-ES and scalar score bands only | Replace those expectations and add deterministic budget, queue, rotation, schedule, checkpoint, resume, and v1 benchmark coverage. |

`euhedral-training` remains an unnamed module and needs no `module-info.java`. The deterministic
`BenchmarkFrame` overload stays in the already exported `io.euhedral_execution.core.frames`
package, so `euhedral-core/src/main/java/module-info.java` also does not change. No Maven
dependency is needed.

## Settled closed-loop vocabulary

- **Training run**: one checkpoint lineage identified by an explicit `trainingRunId`.
- **Required scenario**: one exact Phase 1 `SourceScenario` in the immutable run catalog.
- **Runnable group**: required scenarios with the active `environmentId` and the physical core
  count currently exposed by `SystemInfo`.
- **Iteration**: one accepted model, one persisted schedule over a rotating runnable scenario
  subset, its complete native bundles, and the post-benchmark merge.
- **Policy budget `B`**: the number of unique scheduled policies in each normal scenario run.
- **Fixed-anchor count `A`**: the frozen `AnchorCatalog.fixedAnchors().size()`.
- **New policy**: a policy ID absent from all Phase 1 evidence in the latest merge.
- **Complete policy**: a Phase 1 summary whose `eligible` field is true.
- **Incomplete policy**: a Phase 1 summary whose `eligible` field is false.
- **Carry admission**: the one-time decision that a newly scheduled policy is promising enough to
  retain until coverage completes.
- **Attempt**: a filesystem-local execution attempt for a stable `benchmarkRunId`. Only a complete
  final bundle enters the corpus.

The required-scenario catalog, training run ID, policy budget, statistical configuration, model
training configuration, candidate-generation configuration, benchmark parameters, and scheduler
seed are frozen by the first checkpoint. A resume with different frozen values fails before
writing. The active environment may change between completed iterations so one workspace can be
advanced sequentially on multiple required machines. It may not change while an iteration has a
pending schedule.

## Exact required-scenario and rotation contract

### Required scenario catalog

`ClosedLoopConfig.requiredScenarios` is a non-empty sorted set supplied by the caller. It is copied
into every checkpoint and fingerprinted. Equal ratios on different environments or core counts
remain separate entries.

At process start:

1. read the active environment ID explicitly;
2. read `SystemInfo.getCoreCount()` and `SystemInfo.getCpuSet()`;
3. select required scenarios whose environment ID and available physical core count exactly match;
4. fail if the resulting runnable group is empty; and
5. do not clamp, synthesize, or ratio-match a required scenario.

The current benchmark implementation may run more sources than physical cores, so a required
scenario with `sourceCount > availablePhysicalCoreCount` remains runnable. No current property
silently reduces it.

Before scheduling, inspect indexed native run descriptors for the active runnable group. Any
different recorded `cpuSetHex` is an environment-identity mismatch and stops the run with a
diagnostic requiring a new environment ID/catalog. A pending schedule always requires its exact
persisted CPU mask. This enforces the Phase 1 operator rule that a materially changed allocation is
not silently treated as the same environment.

### Per-group rotation

Define the rotation group key as:

```text
environmentId + "/" + availablePhysicalCoreCount
```

Each group has an independently persisted zero-based `nextIndex`. Its scenarios are sorted by
`SourceScenario` natural order. For a normal iteration:

```text
K = min(config.scenariosPerIteration, runnableScenarioCount)
selected[i] = runnable[(nextIndex + i) mod runnableScenarioCount], i in [0, K)
```

The selected list and current cursor are written into the iteration schedule before benchmarking.
The cursor advances by `K mod runnableScenarioCount` only after every expected run is complete and
the post-benchmark merge publishes successfully. A restart of a pending iteration uses its
persisted selected list and does not consult the cursor again.

Changing active environments is allowed only when no pending schedule exists. A group not yet used
has cursor zero. Bootstrap does not use rotation; it needs one dedicated reference candidate
cohort in every required scenario.

## Predicted robust curve mathematics

### Complete prediction requirement

Every candidate considered by the new optimizer has exactly one `ScenarioPrediction` for every
required scenario, in natural scenario order. A missing, duplicate, extra, differently ordered, or
catalog-mismatched prediction is fatal. The loaded model metadata's required-scenario set must
equal the checkpoint catalog exactly.

For candidate `p`, let `q_s` be `predictedQuality` in scenario `s`. In natural scenario order,
calculate:

```text
predictedWorstQuality = min(q_s)
predictedQualityP25 = RobustStatistics.quantileType7(q_s, 0.25)
predictedGeometricMeanQuality =
    StrictMath.exp(
        RobustStatistics.compensatedMean(
            StrictMath.log(max(q_s, 1.0e-12))))
predictedQualityMad = RobustStatistics.mad(q_s)

maximumEpistemicStdDev = max(s.epistemicStdDev)
maximumDisagreementRange = max(s.disagreementRange)
meanOrdinalStdDev = compensated mean(s.ordinalStdDev)
meanOrdinalEntropy = compensated mean(s.ordinalEntropy)
pessimisticQuality = min(s.qualityIntervalLow)
```

`1.0e-12` is exactly the Phase 1 geometric-mean epsilon and is used only inside the logarithm.
Although the Phase 2 ordinal model normally emits midpoint qualities in `[0.05, 0.95]`, the
aggregator retains the Phase 1 epsilon rule rather than relying on that current decoder detail.

### Authoritative predicted comparator

`PredictedPolicyComparator.BEST_FIRST` compares:

1. higher `predictedWorstQuality`;
2. higher `predictedQualityP25`;
3. higher `predictedGeometricMeanQuality`;
4. lower `predictedQualityMad`;
5. lower `maximumEpistemicStdDev`;
6. lower `maximumDisagreementRange`;
7. lower `meanOrdinalStdDev`; then
8. unsigned `PolicyId` ascending.

The first four tiers are exactly the quality portion of the Phase 1 robust comparator. Prediction
uncertainty occupies the later stability tiers because measured IQR and failure rate do not exist
for a new policy. Every comparison uses exact `Double.compare`. No weighted sum, formatted
rounding, tolerance, top-N flag, or float conversion is authoritative.

`PredictedPolicyComparator.AUDIT_FIRST` compares:

1. higher `maximumEpistemicStdDev`;
2. higher `maximumDisagreementRange`;
3. higher `meanOrdinalEntropy`;
4. `BEST_FIRST`; then
5. unsigned policy ID, already implied by `BEST_FIRST`.

The audit comparator is used only to allocate the explicit audit partition. It never promotes a
policy to measured robust-leader status.

## Exact per-scenario budget accounting

### Frozen anchor reservation

Every normal scenario run has exactly `B` unique policies and contains all `A` fixed anchors.
`B > A` is required. The frozen catalog size is used directly; it is never recalculated when a
checkpoint is resumed or when a later merge changes policy coverage.

Bootstrap runs occur before a catalog exists. They contain exactly `B` bootstrap policies, all
with `EXPLORATION`, and use iteration zero.

### Hamilton allocation of the residual

For a normal run:

```text
R = B - A
```

The default integer weights are:

```text
NEW_EXPLORATION       = 68
CARRY_FORWARD         = 25
LEADER_REVALIDATION   = 2
DISAGREEMENT_AUDIT    = 5
```

Weights are non-negative integers, at least one is positive, and their sum must fit a signed
32-bit integer. Allocate the `R` slots by the Hamilton largest-remainder method using integer
arithmetic:

```text
floor_i     = floor(R * weight_i / weightSum)
remainder_i = (R * weight_i) mod weightSum
```

Use checked `long` multiplication. Give remaining slots to larger remainders. Exact remainder ties
use this completion-first order:

```text
CARRY_FORWARD
LEADER_REVALIDATION
DISAGREEMENT_AUDIT
NEW_EXPLORATION
```

This is the requested allocation. It is independent of candidate availability and sums exactly to
`R`, including for small budgets.

### Availability and transfer

The four non-anchor categories are disjoint:

- anchors are excluded from every other category;
- measured eligible policies may enter only leader revalidation;
- measured incomplete policies may enter only carry-forward;
- unseen candidates selected for audit are removed from new exploration; and
- every remaining unseen candidate may enter only new exploration.

For a specific scenario, select carry and leaders up to their requested counts. Select the common
iteration audit set up to the audit request. Any shortfall in carry, leader, or audit is transferred
to new exploration. Anchors may not be short; a missing catalog vector is fatal. New Sobol
exploration must fill the final total or schedule creation fails without publishing a partial
schedule.

`budget-report.csv` records requested, assigned, and transferred counts for every role and scenario.
The assertions are:

```text
fixedAssigned == A
sum(all assigned unique roles) == B
explorationAssigned ==
    explorationRequested
    + carryShortfall
    + leaderShortfall
    + auditShortfall
```

Phase 3 normally assigns one role per scheduled policy. The Phase 1 `Set<PolicyRole>` remains
supported by the bundle schema, but category overlap in a Phase 3 schedule is a scheduler defect
and is rejected rather than hidden by a multi-role union.

## Fixed-anchor and measured-leader selection

### Fixed anchors

Every policy in the frozen `AnchorCatalog`, sorted by unsigned `PolicyId`, is scheduled under
`FIXED_ANCHOR` in every normal run. Anchor policies remain excluded from leader, carry, audit, and
exploration selection even if they are measured robust leaders.

Anchors are spread through the trial order. For anchor index `i` in `[0, A)`, reserve zero-based
position:

```text
floor((i + 0.5) * B / A)
```

Implement this without floating point as checked integer division
`((2 * i + 1) * B) / (2 * A)`. `B >= A` makes these positions distinct. Fill all other positions
with non-anchors ordered by the stable trial key defined below. This samples run drift throughout
the cohort without changing calibration mathematics.

### Rolling measured leaders

Read robust summaries from the latest complete Phase 1 merge. Filter to:

- `eligible == true`; and
- policy not in the fixed-anchor catalog.

Sort with `RobustPolicyComparator.BEST_FIRST` and take the requested leader count. If fewer exist,
take all and transfer the shortfall to exploration. Every selected leader is scheduled under
`LEADER_REVALIDATION` in each scenario run in the iteration.

No predicted summary, coverage fraction below one, observed-only scenario, or imported robust rank
can bypass the Phase 1 `eligible` gate. Imported evidence may contribute to an eligible Phase 1
summary under Phase 1 rules, but the leader's new revalidation run is native.

## Carry-forward queue

### Admission

Carry admission is bounded so exploration cannot create an ever-growing completion backlog.
After an iteration schedule is complete, form the unique set of policies with
`EXPLORATION` or `DISAGREEMENT_AUDIT`. Sort it by
`PredictedPolicyComparator.BEST_FIRST`.

The iteration's admission capacity is the maximum requested carry count across its selected
scenario runs. Take that many policies, or all if fewer. This selection is persisted in the
schedule before benchmarking.

After the post-benchmark merge:

- an admitted policy that is already Phase 1 eligible needs no queue entry;
- an admitted incomplete policy enters the queue, even if its first observations timed out or
  failed; and
- a policy outside the admission set does not enter merely because it has partial coverage.

Once admitted, a policy is not evicted because a later model predicts a lower quality. It remains
until Phase 1 marks it eligible. This is the coverage-completion promise. There is no bounded retry
count that silently abandons it.

### Coverage state

For every queued policy and every required scenario, persist one state:

```text
VALID     Phase 1 quality is present
MISSING   no run exists
REJECTED  one or more runs exist but no accepted Phase 1 quality exists
```

`MISSING` and `REJECTED` both require completion work. `VALID` is never remeasured under the carry
role, although the same policy may later be remeasured after it becomes an eligible leader.

After each accepted model is trained, rescore every queue policy over the complete required
catalog. Persist the new curve before using it for priority. A model rejection stops the loop; an
older curve is not silently used.

### Carry priority

For a queued policy, over scenarios whose state is not `VALID`, compute:

```text
pessimisticMissingQuality = min(qualityIntervalLow)
maximumMissingEpistemicStdDev = max(epistemicStdDev)
maximumMissingDisagreementRange = max(disagreementRange)
```

For a target scenario, first filter to policies for which that target is `MISSING` or `REJECTED`
and `iteration >= nextEligibleIteration(target)`. Compare:

1. higher valid required-scenario count;
2. higher `pessimisticMissingQuality`;
3. lower `maximumMissingEpistemicStdDev`;
4. lower `maximumMissingDisagreementRange`;
5. earlier `firstSeenIteration`; then
6. unsigned `PolicyId` ascending.

This is the plan's separate incomplete pool: coverage first, pessimistic missing-scenario quality
second, uncertainty third. It is never compared with the measured robust-leader comparator.

### Attempts and backoff

Each policy/scenario queue row records `attemptCount`, `lastAttemptIteration`, and
`nextEligibleIteration`. Completing a native bundle containing that carry policy increments the
attempt count, regardless of observation status. After an attempt in iteration `i`, set:

```text
delay = min(1L << min(attemptCount - 1, 3), 8)
nextEligibleIteration = i + delay
```

Thus repeated rejected runs back off by 1, 2, 4, then 8 iterations, capped at 8, without removal.
A successful valid row ignores the backoff because its state becomes `VALID`.

For a newly admitted policy, its `EXPLORATION` or `DISAGREEMENT_AUDIT` measurements in the admission
iteration count as its first attempts in those scenarios. Attempt accounting follows persisted
schedule membership, not only the literal `CARRY_FORWARD` role.

The same policy may be selected for two different missing scenarios in one iteration. Each
scenario's attempt state is independent.

## Disagreement and uncertainty audits

Audit candidates must be unseen policies from the current iteration's model-scored CMA-ES or Sobol
proposal stream. Select them with `AUDIT_FIRST`, excluding historical policies, anchors, leaders,
carry policies, policy hash duplicates, and candidates already selected into a better audit
position.

The selected audit set is common to every scenario run in the iteration. A policy is labeled only
`DISAGREEMENT_AUDIT`, not also `EXPLORATION`. Audit observations enter Phase 1 like all other
native measurements. Only the separate carry-admission rule determines whether an incomplete audit
policy remains scheduled in future iterations.

If the finite model-scored stream cannot supply the requested distinct audit count, report the
shortfall and transfer it to new exploration. Direct Sobol candidates do not fill the audit quota
because selecting them without scoring would not audit model disagreement.

## New candidate generation

### Candidate identity and historical exclusion

All proposals are converted immediately to immutable `PolicyVector` objects. A `PolicyRegistry`
checks every repeated ID:

- identical raw bits are a duplicate and are skipped;
- different raw bits under one ID throw `PolicyHashCollisionException`; and
- no code treats a 64-bit hash alone as sufficient evidence that two unknown vectors are equal.

The latest Phase 1 merge supplies the complete historical policy dictionary, including eligible,
missing, and rejected policies. Every historical ID is excluded from new exploration. Queue,
anchor, and leader policies are selected through their own roles, never rediscovered as new.

Policy normalization remains a proposal-space operation:

- CMA-ES and Sobol proposals use `CommonFunctions.normalizePolicyVector`;
- measured Phase 1 vectors are never renormalized or mutated; and
- policy identity is calculated only after proposal normalization.

### New-exploration suballocation

Let `E` be the requested new-exploration allocation, which is the same for every selected
scenario. Before proposal generation, let `knownShortfall_s` be that scenario's carry plus leader
transfer and `X0 = max(knownShortfall_s)`. Candidate generation then determines the common audit
shortfall `D`. Generate two disjoint tranches:

- a base tranche of exactly `E`, used by every scenario; and
- an overflow tranche of exactly `X0 + D`, from which scenario `s` uses the first
  `knownShortfall_s + D`.

Allocate each tranche independently with the same exact Hamilton implementation and default
integer weights:

```text
CMA_ES       = 8
SCORE_BAND   = 7
DIRECT_SOBOL = 1
```

Exact remainder ties use the order shown. These defaults retain one-sixteenth direct Sobol
exploration while dividing the rest between robust exploitation and band diversity.

Select audit candidates before resolving these pools. Resolve the base tranche first and exclude
it while resolving overflow. Within each tranche:

1. take best-first unique CMA-ES proposals up to the CMA quota;
2. take deterministic score-band candidates not already selected up to the band quota;
3. take direct Sobol candidates from the cursor after the screened range up to the direct quota;
4. transfer a CMA shortfall to score-band selection; and
5. transfer any remaining shortfall to direct Sobol generation.

The result contains exactly `E + X0 + D` distinct unseen policies. Every selected direct Sobol
policy is
scored after selection so its prediction is available for audit output and carry admission, but
that score did not affect its selection.

Each scenario run takes the complete common base tranche and the required prefix of the common
overflow tranche. Within a tranche the list is in category order above and then each category's
settled order. Every run therefore receives the configured direct-exploration share of its base
partition; a small overflow prefix may legitimately contain only earlier categories. Final
benchmark trial order is independently pseudorandomized, so category order does not create a
temporal benchmark bias.

### CMA-ES

Replace system-property reads inside `CmaEsOptimizer` with:

```java
public record CmaEsConfig(
        boolean enabled,
        int islands,
        int generations,
        int populationSize,
        double initialSigma,
        int minimumSeedPolicies) {
    public static CmaEsConfig defaults();
}
```

Defaults are:

```text
enabled             = true
islands             = 4
generations         = 12
populationSize      = 96
initialSigma        = 0.20
minimumSeedPolicies = 10
```

Validation retains the current ranges: at least one island/generation, population at least eight,
finite sigma in `[0.005, 1.0]`, and minimum seeds at least two.

The seed pool is the latest Phase 1 eligible summaries in
`RobustPolicyComparator.BEST_FIRST` order, excluding fixed anchors. If fewer than
`minimumSeedPolicies` remain, CMA-ES returns an empty list and its quota transfers.

For `I = min(config.islands, seedPool.size())`:

1. limit the diversity pool to `min(seedPool.size(), max(I * 32, 64))`;
2. select the robust best as island zero;
3. repeatedly select the policy with greatest minimum squared Euclidean distance from selected
   island seeds;
4. break equal-distance ties by measured robust order, then unsigned policy ID; and
5. never select the same policy twice.

Initial covariance uses the measured robust order and the existing full-covariance formula over:

```text
min(seedPool.size(), max(32, min(512, seedPool.size() / 5)))
```

Policies are copied before arithmetic. Candidate populations are predicted as complete curves.
Parents sort by `PredictedPolicyComparator.BEST_FIRST`. A new generation best resets stagnation
exactly when the comparator says it is better than the stored best. Four non-improving generations
retain the current sigma/covariance restart.

Derive an island `java.util.Random` seed with `SchedulerSeeds`, the training run scheduler seed,
iteration, and island index. Do not derive it from list order or wall time. Gaussian sample order,
island order, generation order, and member order are fixed. CMA-ES is an approximate proposal
mechanism, but every proposal that survives is reranked by the authoritative exact comparator.

### Robust score bands

`ScoreBandSampler` has ten fixed bands based only on the first authoritative comparator tier:

```text
band = min(9, floor(predictedWorstQuality * 10))
```

The intervals are `[0.0, 0.1)`, ..., `[0.8, 0.9)`, and `[0.9, 1.0]`; an exact boundary enters the
higher band. The default capacity weights remain:

```text
[1, 1, 1, 1, 2, 2, 3, 5, 8, 16]
```

Use the exact integer Hamilton allocator for band capacities. Exact remainder ties go to the
higher-numbered band first. A band retains the policies with the smallest unsigned sampling keys:

```text
HasherApi.getHash(
    "phase3-score-band-v1\n"
    + "iteration=" + iteration + "\n"
    + "band=" + band + "\n"
    + "policy=" + policyId.canonical() + "\n",
    bandSeed)
```

Tie by unsigned policy ID. Bottom-k hash priority is independent of proposal arrival order; do not
use a stateful reservoir `Random`.

Also retain a bounded best-first overflow heap of size `bandQuota + auditQuota`. If sparse bands
leave capacity unused, fill from that heap under the predicted robust comparator. `finish` returns
band nine through band zero, sampling key ascending within a band, followed by best-first overflow
backfill. It returns fewer than requested only when the entire distinct input stream is short.
Feed model-scored CMA proposals first and screened Sobol proposals second; hash-priority retention
makes the retained band set independent of that arrival convention.

### Sobol cursor and screening

Use `SobolSequenceGenerator(28)` and a checkpointed non-negative cursor. The generator accepts only
an `int` index, so every start and exclusive end must be `<= Integer.MAX_VALUE`; exhaustion is a
clear terminal error, not wraparound.

Defaults:

```text
screenRows             = 2_097_152
maximumPredictionRows  = 16_384
initialSobolCursor     = 131_072
```

Screen exactly `screenRows` Sobol points in ascending index order in bounded batches. Normalize,
deduplicate, predict complete curves, and feed the CMA/top, audit, and band selectors without
retaining the full screen. Direct Sobol generation begins at the exclusive screened end and
continues until its transferred quota is full. Persist the exclusive index after the last Sobol
point consumed, including historical duplicates. A pending persisted schedule owns that next
cursor; restart never screens the range again.

## Stable cohort, run, trial, and frame-seed identity

All hash material below is UTF-8 with the shown LF after every line, including the last. Hash with
`HasherApi.getHash(material, schedulerSeed)` and format the unsigned result as 16 lower-case hex
digits.

`SchedulerSeeds` owns these materials and every CMA/band/trial/frame derivation. Callers pass
semantic fields, not preformatted ad hoc strings. It is a pure utility and has fixed-vector tests
for every label.

### Candidate cohort ID

Sort the scenario run's selected policies by unsigned policy ID for identity material, independent
of trial order:

```text
phase3-candidate-cohort-v1
training_run=<trainingRunId>
kind=<BOOTSTRAP|NORMAL>
iteration=<decimal>
scenario=<scenario-canonical>
policy=<policy-id>|role=<enum-name>
...
```

The ID is `c1-<hash>`. Recompute it when reading a schedule.

### Benchmark run ID

```text
phase3-benchmark-run-v1
training_run=<trainingRunId>
kind=<BOOTSTRAP|NORMAL>
iteration=<decimal>
scenario=<scenario-canonical>
cohort=<candidateCohortId>
expected_repetitions=<decimal>
sample_duration_nanos=<decimal>
liveness_timeout_nanos=<decimal>
frames_per_source=<decimal>
reset_timeout_nanos=<decimal>
ordered_frames=<true|false>
cpu_set_hex=<canonical-mask>
commit_sha=<lowercase-hex>
dirty_working_tree=<true|false>
```

The ID is `r1-<hash>`. Frame seeds are derived only after this hash, avoiding a cycle. A run ID maps
to exactly one descriptor context and schedule; retry timestamps are the only attempt-specific
fields.

### Trial key

For each non-anchor:

```text
phase3-trial-order-v1
cohort=<candidateCohortId>
policy=<policy-id>
```

Sort by unsigned hash, then unsigned policy ID. Insert anchors into their settled midpoint
positions. Assign contiguous one-based `schedulePosition` only after final order is known.

### Frame source seeds

For source index `i`:

```text
phase3-frame-id-v1
run=<benchmarkRunId>
source=<i>
```

and:

```text
phase3-frame-routing-v1
run=<benchmarkRunId>
source=<i>
```

produce `FrameSourceSeed.idHash` and `routingSeed`. Source indexes are contiguous from zero. These
seeds are persisted in the schedule and copied unchanged into `BenchmarkParameters`.

## Native v1 benchmark production

### Deterministic `BenchmarkFrame` overload

Add:

```java
public static BenchmarkFrame[] generate(
        int count,
        boolean ordered,
        long idHash,
        long routingSeed);

public static BenchmarkFrame[] generate(
        int count,
        boolean ordered,
        long idHash,
        long routingSeed,
        AtomicBoolean killSwitch);
```

The existing overloads choose a `ThreadLocalRandom` routing seed and delegate for legacy callers.
The deterministic overload creates frame `i` with the supplied `idHash`; when unordered it calls
`randomizeHash(routingSeed + i)` using normal signed-long wraparound. When ordered it leaves
`routingHash == idHash`. The seed is not mutated after frame publication.

### Benchmark request API

Under `io.euhedral_execution.training.benchmark`:

```java
public record BenchmarkExecutionConfig(
        int expectedRepetitions,
        long sampleDurationNanos,
        long livenessTimeoutNanos,
        int framesPerSource,
        long resetTimeoutNanos,
        boolean orderedFrames) {
    public static BenchmarkExecutionConfig defaults();
}

public record NativeBenchmarkRunPlan(
        String trainingRunId,
        int iteration,
        String benchmarkRunId,
        String candidateCohortId,
        SourceScenario scenario,
        List<ScheduledPolicy> policies,
        BenchmarkExecutionConfig executionConfig,
        BenchmarkParameters parameters,
        String commitSha,
        boolean dirtyWorkingTree,
        Path outputBundle) {
}
```

`BenchmarkRunner.runV1(NativeBenchmarkRunPlan, BooleanSupplier stopRequested)` returns the
completed `BenchmarkRunContext`. `outputBundle` is an absolute normalized path inside the
closed-loop evidence directory and must not exist.

Defaults preserve the current benchmark intent:

```text
expectedRepetitions = 10
sampleDurationNanos = 200_000_000
livenessTimeoutNanos = 50_000_000
framesPerSource = 100_000
resetTimeoutNanos = 2_000_000_000
orderedFrames = false
```

The runner validates:

- exact run/cohort hashes;
- exact policy count and contiguous schedule positions;
- exact scenario against the current physical core count;
- `BenchmarkParameters` against `BenchmarkExecutionConfig`, current CPU-set hex, and persisted
  source seeds;
- native commit form;
- all policy roles and IDs; and
- no duplicate policy.

It does not call `configuredSourceCounts`, `selectedSourceCounts`, a path parser, or a legacy vector
reader. Move all current legacy benchmark entry points and text-summary behavior to
`io.euhedral_execution.training.legacy.PooledBenchmarkRunner`.

### Trial isolation and repetition status

Preserve this policy boundary:

```text
halt weights
-> pause every source and await in-flight callbacks
-> owner-thread lattice cache reset
-> reset monotonic source counters
-> install policy weights
-> resume sources
-> measure repetitions as counter deltas
-> halt weights
-> pause every source
-> write that policy's observation rows
```

Do not reset a counter while sources are flowing. At each repetition, read a baseline and derive
completed frames from the later monotonic count. There is no cache reset between repetitions.

For a repetition:

- `SUCCESS`: the sample deadline is reached with positive completed frames and no liveness
  timeout;
- `TIMEOUT/NO_PROGRESS`: no counter increase before the liveness deadline;
- `TIMEOUT/ZERO_COMPLETED_FRAMES`: the sample deadline is reached with zero completed frames;
- `FAILED/MEASUREMENT_ERROR`: a recoverable policy-local measurement exception; and
- remaining planned repetitions after timeout or failure are `SKIPPED` with
  `PREVIOUS_TIMEOUT` or `PREVIOUS_FAILURE`.

An isolation failure in pause, cache reset, counter reset, weight publication, or source resume
aborts the whole attempt. It must not produce a complete bundle because later policies may be
polluted.

Use `System.nanoTime()` only for elapsed time and deadlines. Compare elapsed differences
(`now - start` and `now - lastProgress`) rather than adding an absolute deadline, so normal
`nanoTime` wraparound remains safe. Use `Math.addExact` when summing source counters and abort an
attempt on overflow or a negative delta. Capture one wall-clock `Instant repetitionStart`; set
`repetitionEnd = repetitionStart.plusNanos(elapsedNanos)` so the Phase 1 counter-derived timestamp
invariant is exact. Calculate:

```text
throughputFramesPerSecond =
    completedFrames * 1_000_000_000.0 / elapsedNanos
```

with the exact Phase 1 evaluation order. A timeout may retain finite partial throughput, including
zero. Completion time is not before any observation end.

Check the stop supplier only while all sources are paused, between policies. A requested stop
aborts the current attempt rather than writing fabricated `SKIPPED` evidence for policies that were
never tried. `BenchmarkRunner` throws the existing stackless `ClosedLoopRunner.StopRequested`;
the closed-loop owner catches it and returns the latest complete checkpoint without advancing a
stage, cursor, attempt count, or evidence index.

### Bundle publication and incomplete attempts

Run the Phase 1 `ObservationBundleWriter` in a unique temporary sibling:

```text
evidence/.<benchmarkRunId>.attempt-<monotonic-local-number>/
```

Register every policy before starting work. Retain at most one policy's repetition metadata in
small primitive/object arrays. Write its observations only after the policy is paused. After
`complete`, reopen the bundle with `ObservationBundleReader.stream`, validate
run/cohort/scenario/policy identity without retaining all observations, then atomically move the
directory to:

```text
evidence/<benchmarkRunId>/
```

If atomic directory publication is unsupported, fail and retain the incomplete attempt; do not
publish through a non-atomic fallback. Attempts are not merger inputs and are never deleted
automatically. Restart chooses the next local attempt number by scanning existing attempt
directories.

## Bootstrap and calibration-plan lifecycle

### Bootstrap policy file

When no calibration plan exists, require a strict new-format file with exactly `B` policies:

```text
schema_version,bootstrap_position,policy_id,weight_00_bits,...,weight_27_bits
```

Rows use schema version 1, contiguous one-based positions, unique bit-validated policy IDs, and LF
UTF-8 CSV. This is a new-format vector-only input, not the Phase 5 current-workspace importer.
Copy it into the workspace on first initialization and fingerprint it. Resume requires the same
bytes. Validate `B > anchorSelectionConfig.targetCount(B)` before any bootstrap benchmark.

### Dedicated bootstrap runs

Use the identical bootstrap policy set in every required exact scenario. Every policy has only
`EXPLORATION`. Iteration is zero. A scenario receives one deterministic bootstrap cohort and run
ID. The active environment executes all still-missing bootstrap scenarios in its runnable group,
without rotation.

After the active environment's runs complete:

- if another required scenario remains, checkpoint and return an
  `AwaitingRequiredScenarios` result listing it;
- another required environment may resume the same workspace sequentially; and
- do not train, infer a reference, or shrink the required set while waiting.

When all required bootstrap runs are complete, invoke
`DataMerger.bootstrapCalibrationV1` with the frozen `B`, exact required set, configured explicit
reference overrides, and settled Phase 1 configs. Persist its new plan under
`calibration-plan/`. Then run `DataMerger.mergeV1` over all complete evidence to create
`merges/merge-000000/`.

An explicitly supplied new-format calibration plan may replace bootstrap only when:

- `CalibrationPlanCsv.read` validates it against the exact required catalog;
- its reference run bundles are present in the complete evidence set;
- its anchor vectors pass identity checks; and
- its frozen anchor count is strictly less than `B`; and
- it is copied atomically into the new workspace before the first checkpoint.

Old models, old checkpoints, and path-derived text benchmarks never satisfy bootstrap.

## Normal iteration lifecycle

Given latest post-merge `M_(i-1)`:

1. Build `Phase1ScenarioInputs.from(M_(i-1))`.
2. Train a fresh Phase 2 artifact at `models/model-<six-digit-i>`.
3. Require `ModelAcceptanceStatus.ACCEPTED`, deployment eligibility, exact scenario catalog, and
   a dataset fingerprint derived from `M_(i-1)`.
4. Load the model on its producing device and rescore all persisted carry entries.
5. Read measured eligible/incomplete state from `M_(i-1)`.
6. Select the runnable scenarios without advancing the rotation cursor.
7. Allocate each scenario budget and prepare carry/leader pools.
8. Generate audit and exploration candidates from the checkpointed Sobol cursor.
9. Persist and validate the complete iteration schedule, including its next Sobol cursor and carry
   admissions.
10. Execute or adopt every expected native bundle.
11. Run `DataMerger.mergeV1` over all complete evidence into
    `merges/merge-<six-digit-i>`.
12. Reconcile carry coverage and attempts from `M_i`.
13. Advance the active rotation cursor.
14. Checkpoint `nextIteration = i + 1`.

The final requested iteration still performs step 11, so the latest robust ranking includes its
measurements. No redundant pre-merge occurs at the next iteration unless startup discovers an
expected complete bundle published just before a crash.

A statistically rejected Phase 2 artifact is retained and referenced by the `MODEL_REJECTED`
checkpoint for audit, but it is never treated as an accepted scheduling model. The run stops and
does not fall back to an older accepted model or the pooled model.

## Workspace and checkpoint contract

### Workspace layout

Phase 3 owns this new-format layout:

```text
<workspace>/
+-- LOCK
+-- bootstrap/
|   +-- bootstrap-policies.vectors.csv
|   +-- schedules/
+-- calibration-plan/
|   +-- fixed-anchors.csv
|   +-- reference-runs.csv
+-- evidence/
|   +-- <benchmark-run-id>/
|   +-- .<benchmark-run-id>.attempt-<n>/
+-- merges/
|   +-- merge-000000/
|   +-- merge-000001/
+-- models/
|   +-- model-000001/
+-- iterations/
|   +-- iteration-000001/
|       +-- schedule/
+-- checkpoints/
    +-- checkpoint-00000001/
    +-- checkpoint-00000002/
```

This layout does not reuse current `corpus/*.txt`, `latest-model`, `latest-training-data.txt`, or
`state.properties`. Those remain in the pooled-v0 compatibility boundary until Phase 7.

Acquire an exclusive `FileChannel.tryLock()` on `LOCK` before reading a mutable checkpoint and hold
it for the process invocation. Failure to acquire it stops before mutation. This is local
single-writer protection, not a distributed filesystem lock guarantee.

With `resume=false`, any existing complete Phase 3 checkpoint is an error; the runner never clears
the workspace to simulate a fresh run. With `resume=true`, load the highest complete snapshot or
initialize only when none exists. In both modes, unrelated or unexpected existing files are
reported and preserved.

A stop observed between offline stages returns `ClosedLoopResult` for the latest snapshot. A stop
during a benchmark preserves the incomplete attempt and returns the existing `BENCHMARKING`
snapshot. Stop handling never publishes a synthetic checkpoint transition.

### Checkpoint stages

```java
public enum CheckpointStage {
    BOOTSTRAP_PENDING,
    READY_TO_TRAIN,
    MODEL_READY,
    MODEL_REJECTED,
    SCHEDULE_READY,
    BENCHMARKING,
    READY_TO_MERGE,
    RUN_COMPLETE
}
```

Only these transitions are valid:

```text
new -> BOOTSTRAP_PENDING | READY_TO_TRAIN
BOOTSTRAP_PENDING -> BOOTSTRAP_PENDING | READY_TO_TRAIN
READY_TO_TRAIN -> MODEL_READY | MODEL_REJECTED
MODEL_READY -> SCHEDULE_READY
SCHEDULE_READY -> BENCHMARKING
BENCHMARKING -> BENCHMARKING | READY_TO_MERGE
READY_TO_MERGE -> READY_TO_TRAIN | RUN_COMPLETE
```

`MODEL_REJECTED` and `RUN_COMPLETE` have no automatic outgoing transition.

### Atomic snapshot directories

Every mutation writes a new monotonically numbered snapshot directory. Write a unique temporary
sibling, force every file, write empty `COMPLETE` last, reopen and validate the snapshot, then use
`ATOMIC_MOVE` to its final name. There is no non-atomic fallback and no overwrite. Old complete
snapshots remain.

Resume ignores temporary/incomplete snapshot directories and chooses the highest numbered complete
valid snapshot. A lower valid snapshot is not used to conceal corruption in the highest complete
snapshot.

Each snapshot contains:

```text
checkpoint-<eight-digit-revision>/
+-- state.csv
+-- required-scenarios.csv
+-- rotation-cursors.csv
+-- evidence-index.csv
+-- carry-forward.csv
+-- carry-forward-scenarios.csv
+-- pending-runs.csv
+-- COMPLETE
```

All files are UTF-8 RFC 4180 CSV with exact headers, LF, schema version 1, deterministic row order,
and no timestamp.

### `state.csv`

Exactly one row:

```text
schema_version,artifact_type,training_run_id,revision,stage,next_iteration,sobol_cursor,config_sha256,required_scenarios_sha256,rotation_cursors_sha256,evidence_index_sha256,carry_forward_sha256,carry_forward_scenarios_sha256,pending_runs_sha256,anchor_set_id,calibration_plan_path,calibration_plan_sha256,latest_merge_path,latest_merge_sha256,latest_model_path,latest_model_sha256,pending_schedule_path,pending_schedule_sha256
```

Rules:

- `artifact_type` is `euhedral-optimizer-checkpoint`;
- the six sidecar SHA fields are lower-case SHA-256 of the exact corresponding CSV bytes;
- all stored paths are workspace-relative POSIX paths without empty, `.`, or `..` segments;
- optional artifact fields are empty only in stages where the artifact cannot yet exist;
- anchor ID and calibration plan are required from `READY_TO_TRAIN` onward;
- latest merge is required from `READY_TO_TRAIN` onward;
- latest model is required in `MODEL_READY`, `MODEL_REJECTED`, `SCHEDULE_READY`,
  `BENCHMARKING`, `READY_TO_MERGE`, and `RUN_COMPLETE`; it is also required in
  `READY_TO_TRAIN` when `next_iteration > 1`;
- a `MODEL_READY` model must load normally, while a `MODEL_REJECTED` model must load only through
  the audit path and have a non-accepted status;
- pending schedule is required from `SCHEDULE_READY` through `READY_TO_MERGE`; and
- `sobol_cursor` changes only when a schedule is published.

### `required-scenarios.csv`

Sorted by natural scenario order:

```text
schema_version,scenario_id,environment_id,source_count,available_physical_core_count,source_ratio_numerator,source_ratio_denominator
```

The reader reconstructs every `SourceScenario` and recomputes the canonical ID.

### `rotation-cursors.csv`

Sorted by environment ID then core count:

```text
schema_version,environment_id,available_physical_core_count,next_index
```

Every required environment/core group appears exactly once. `next_index` is in the group's scenario
range, or zero for a newly initialized group.

### `evidence-index.csv`

Sorted by benchmark run ID:

```text
schema_version,benchmark_run_id,scenario_id,evidence_path,evidence_sha256,source
```

`source` is `INITIAL`, `BOOTSTRAP`, or `ITERATION`. Every row points to one complete, strictly
validated bundle under `evidence/`; the directory fingerprint, run ID, and scenario are
recomputed. A run ID appears exactly once. `DataMerger.mergeV1` receives exactly the paths in this
index, in run-ID order. A `pending-runs.csv` row marked `COMPLETE` must also have an identical index
entry. This index is the authoritative corpus membership used to distinguish an expected
crash-window publication from unexpected evidence.

### `carry-forward.csv`

Sorted by unsigned policy ID:

```text
schema_version,policy_id,first_seen_iteration,last_updated_iteration,valid_required_scenario_count,observed_required_scenario_count,pessimistic_missing_quality,maximum_missing_epistemic_stddev,maximum_missing_disagreement_range,weight_00_bits,...,weight_27_bits
```

Summary values use `Double.toString` and are recomputed from the scenario rows during validation.
An eligible policy may not appear.

### `carry-forward-scenarios.csv`

Sorted by policy ID then scenario:

```text
schema_version,policy_id,scenario_id,coverage_status,attempt_count,last_attempt_iteration,next_eligible_iteration,predicted_quality,ordinal_stddev,quality_interval_low,quality_interval_high,ordinal_entropy,top_decile_probability,epistemic_stddev,disagreement_range
```

Every queue policy has exactly one row for every required scenario and all prediction fields are
present and finite. The queue is empty during bootstrap, before the first accepted model.
`last_attempt_iteration` is empty exactly when `attempt_count == 0`.

### `pending-runs.csv`

Sorted by scenario:

```text
schema_version,iteration,run_kind,scenario_id,benchmark_run_id,candidate_cohort_id,schedule_path,schedule_sha256,evidence_path,status
```

`run_kind` is `BOOTSTRAP` or `NORMAL`; status is `PENDING` or `COMPLETE`. A normal pending set is
exactly the selected rotation subset. Bootstrap may accumulate rows across sequential
environments. All paths are workspace-relative.

### Configuration fingerprint

`config_sha256` hashes UTF-8 canonical material beginning with:

```text
phase3-run-config-v1
```

Then emit one `name=value\n` line in Java record-component order for:

1. `trainingRunId`, requested iteration count, candidate budget, scheduler seed, initial Sobol
   cursor, and scenarios per iteration;
2. all `CandidateBudgetConfig` integer weights;
3. all `CandidateGenerationConfig`, exploration-mix, score-band weight, and `CmaEsConfig` fields;
4. all `BenchmarkExecutionConfig` fields;
5. every `AnchorSelectionConfig`, `CalibrationConfig`, and `AggregationConfig` field;
6. every `ScenarioTrainingConfig` field followed by every `EvaluationThresholds` field;
7. explicit reference overrides sorted by scenario;
8. commit SHA and dirty-working-tree flag; and
9. the bootstrap-policy file SHA-256 or supplied calibration-plan SHA-256, followed by every
   initial schema-v1 observation-bundle directory fingerprint in benchmark-run-ID order.

Encode integers in canonical decimal, long seeds as 16 lower-case hex, `float` values as eight
raw-bit hex digits, `double` values as 16 raw-bit hex digits, booleans as lower-case, enums by
`name()`, and strings as their validated ASCII form. Paths, resume flag, stop-file path, and active
environment are excluded: they do not change scheduling semantics and the workspace is
relocatable. Tests change every included component individually and require a different hash.

### Artifact fingerprints

For a regular file, use lower-case SHA-256 of its bytes. For an artifact directory, reject
symlinks and hash:

```text
phase3-directory-artifact-v1
<relative-posix-path>\t<byte-count>\t<file-sha256>
...
```

with files sorted by relative POSIX path and one LF per line. Include every regular file in the
artifact. Checkpoint and schedule readers recompute fingerprints before reuse.

### Restart and adoption behavior

Resume validates the frozen config, scenario catalog, all referenced artifacts, carry invariants,
and pending schedule before work.

- A referenced artifact mismatch is fatal.
- An accepted model must load normally, not through `loadForAudit`.
- A complete expected evidence bundle published before the last checkpoint is adopted only after
  strict bundle validation against the pending run, then added to the evidence index.
- An unexpected complete evidence bundle whose run ID is neither in an earlier complete corpus nor
  the pending schedule is fatal; it is not silently merged.
- An incomplete attempt is retained and a new attempt reruns the entire scenario under the same
  persisted run/cohort/frame seeds.
- A valid merge/model/schedule at its deterministic target, published just before a checkpoint
  crash, may be adopted only when its input fingerprints and metadata match the current snapshot.
- A pending schedule is never regenerated, even if a different device or model would now rank
  candidates differently.

The restart acceptance criterion compares the schedule and final Phase 1 output bytes, not
checkpoint revision numbers: a resumed run may have extra recovery snapshots.

## Iteration schedule artifact

Write `iterations/iteration-<six digits>/schedule/` atomically with:

```text
+-- runs.csv
+-- policies.csv
+-- predictions.csv
+-- budget-report.csv
+-- carry-admissions.csv
+-- COMPLETE
```

Bootstrap uses the same five-file schema under
`bootstrap/schedules/<scenario-canonical>/`: it has one `BOOTSTRAP` run, header-only predictions
and admissions, and one budget row with zero fixed/carry/leader/audit and all `B` policies assigned
to exploration. This avoids a second schedule format while making the absence of a model explicit.
`ScheduleCodec` validates the normal and bootstrap invariants separately.

### `runs.csv`

Sorted by scenario:

```text
schema_version,training_run_id,iteration,run_kind,scenario_id,benchmark_run_id,candidate_cohort_id,expected_repetitions,sample_duration_nanos,liveness_timeout_nanos,frames_per_source,reset_timeout_nanos,ordered_frames,cpu_set_hex,frame_source_seeds
```

Recompute scenario, run, cohort, CPU mask, parameter, and frame-seed invariants.

### `policies.csv`

Sorted by scenario then schedule position:

```text
schema_version,scenario_id,benchmark_run_id,schedule_position,policy_id,roles,weight_00_bits,...,weight_27_bits
```

It is the benchmark-ready machine-readable schedule and must not be confused with the old
headerless vector file.

### `predictions.csv`

Sorted by policy ID then required scenario:

```text
schema_version,policy_id,scenario_id,predicted_quality,ordinal_stddev,quality_interval_low,quality_interval_high,ordinal_entropy,top_decile_probability,epistemic_stddev,disagreement_range,predicted_worst_quality,predicted_quality_p25,predicted_geometric_mean_quality,predicted_quality_mad,maximum_epistemic_stddev,maximum_disagreement_range,mean_ordinal_stddev,mean_ordinal_entropy,pessimistic_quality,origin
```

Include every unique non-anchor policy selected anywhere in the iteration and one row per required
scenario. Repeated summary fields must recompute exactly. `origin` is `MEASURED_CARRY`,
`MEASURED_LEADER`, `CMA_ES`, `SCORE_BAND`, or `DIRECT_SOBOL`.

### `budget-report.csv`

Sorted by scenario:

```text
schema_version,scenario_id,candidate_budget,fixed_requested,fixed_assigned,carry_requested,carry_assigned,leader_requested,leader_assigned,audit_requested,audit_assigned,exploration_requested,exploration_assigned,carry_transferred_to_exploration,leader_transferred_to_exploration,audit_transferred_to_exploration,total_assigned
```

### `carry-admissions.csv`

Predicted best-first:

```text
schema_version,predicted_rank,policy_id,first_seen_iteration
```

Only new/audit policies selected by the settled admission capacity appear.

The schedule has no wall-clock timestamp. Identical inputs, model predictions, cursor, and seed
produce identical bytes.

## Exact Java API and ownership

### Prediction and optimizer records

Under `io.euhedral_execution.training.optimization`:

```java
public record PredictedPolicySummary(
        PolicyPredictionCurve curve,
        double predictedWorstQuality,
        double predictedQualityP25,
        double predictedGeometricMeanQuality,
        double predictedQualityMad,
        double maximumEpistemicStdDev,
        double maximumDisagreementRange,
        double meanOrdinalStdDev,
        double meanOrdinalEntropy,
        double pessimisticQuality) {
}

public final class PredictedPolicyRanker {
    public static PredictedPolicySummary summarize(
            PolicyPredictionCurve curve,
            SortedSet<SourceScenario> requiredScenarios);
}

public final class PredictedPolicyComparator {
    public static final Comparator<PredictedPolicySummary> BEST_FIRST;
    public static final Comparator<PredictedPolicySummary> AUDIT_FIRST;
}

@FunctionalInterface
public interface PolicyCurvePredictor {
    List<PredictedPolicySummary> predict(List<PolicyVector> policies);
}

public enum CandidateOrigin {
    CMA_ES, SCORE_BAND, DIRECT_SOBOL
}

public enum SchedulePolicyOrigin {
    MEASURED_CARRY, MEASURED_LEADER, CMA_ES, SCORE_BAND, DIRECT_SOBOL
}

public record PredictedCandidate(
        PolicyVector policy,
        PredictedPolicySummary prediction,
        CandidateOrigin origin) {
}

public record ScheduledPolicyPrediction(
        PolicyVector policy,
        PredictedPolicySummary prediction,
        SchedulePolicyOrigin origin) {
}
```

All constructors validate exact policy identity and defensively copy curves/collections.

`CmaEsOptimizer` becomes:

```java
public List<PredictedCandidate> optimize(
        List<RobustPolicySummary> measuredEligiblePolicies,
        Set<PolicyId> fixedAnchorIds,
        PolicyCurvePredictor predictor,
        CmaEsConfig config,
        long islandSeed);
```

It accepts eligible summaries only and returns candidates in deterministic island, generation, and
population production order. Final selection always reranks them.

`ScoreBandSampler` is:

```java
public final class ScoreBandSampler {
    public ScoreBandSampler(
            int capacity,
            int[] bandWeights,
            long bandSeed,
            int iteration,
            int overflowCapacity);
    public void accept(PredictedCandidate candidate);
    public List<PredictedCandidate> finish();
}
```

`finish()` is idempotent and returns immutable results. Constructors defensively copy the ten
weights.

### `SequenceFinder`

The new class is a stateless candidate-generation facade:

```java
public record CandidateGenerationConfig(
        int screenRows,
        int maximumPredictionRows,
        int[] scoreBandWeights,
        int cmaWeight,
        int scoreBandWeight,
        int directSobolWeight,
        CmaEsConfig cma) {
    public static CandidateGenerationConfig defaults();
}

public record CandidateGenerationRequest(
        int iteration,
        int baseExplorationCount,
        int overflowExplorationCount,
        int disagreementAuditCount,
        long sobolCursor,
        long schedulerSeed,
        OptimizationCorpusView corpus,
        Set<PolicyId> fixedAnchorIds,
        PolicyCurvePredictor predictor,
        CandidateGenerationConfig config) {
}

public record CandidateGenerationResult(
        List<PredictedCandidate> disagreementAudits,
        List<PredictedCandidate> baseExploration,
        List<PredictedCandidate> overflowExploration,
        long nextSobolCursor,
        int cmaAssigned,
        int scoreBandAssigned,
        int directSobolAssigned,
        int auditShortfall) {
}

public final class SequenceFinder {
    public static CandidateGenerationResult generate(
            CandidateGenerationRequest request);
}
```

`CandidateGenerationConfig` defensively copies `scoreBandWeights`; `screenRows` and
`maximumPredictionRows` are positive; the band array has exactly ten non-negative entries with a
positive sum; exploration weights are non-negative with a positive sum; and all cursor/count
arithmetic is checked before generation.

Move the current pooled `SequenceFinder` implementation to
`io.euhedral_execution.training.legacy.PooledSequenceFinder` with the single marker
`ROBUST_OPTIMIZER_POOLED_V0_REMOVAL`. The transitional `Runner` command delegates to it until Phase
5/7. No new optimization, scheduling, checkpoint, or closed-loop class imports the legacy class,
`PolicyOrdinalNetwork`, `PolicyRanking`, `BenchmarkOutputReader`, or `BenchmarkOutputWriter`.

### Merge output view

Under `io.euhedral_execution.training.scheduling`:

```java
public record OptimizationCorpusView(
        SortedMap<PolicyId, PolicyVector> policies,
        List<RobustPolicySummary> eligiblePolicies,
        SortedMap<PolicyId, RobustPolicySummary> summaries,
        SortedMap<PolicyId, SortedMap<SourceScenario, ScenarioResultStatus>> coverage,
        String mergeArtifactSha256) {
}

public final class OptimizationCorpusReader {
    public static OptimizationCorpusView read(
            DataMerger.MergeArtifacts artifacts,
            SortedSet<SourceScenario> requiredScenarios) throws IOException;
}
```

The reader strictly joins `robust-ranking.csv`, `coverage-report.csv`,
`scenario-results.csv`, and both vector files. It reconstructs Phase 1 domain records and verifies
their ordering and metrics. It does not define new ranking math or accept a partial join.

### Budget, queue, rotation, and schedule APIs

```java
public final class BootstrapPolicyCsv {
    public static List<PolicyVector> read(
            Path path,
            int expectedPolicyCount) throws IOException;
}

public record CandidateBudgetConfig(
        int explorationWeight,
        int carryForwardWeight,
        int leaderRevalidationWeight,
        int disagreementAuditWeight) {
    public static CandidateBudgetConfig defaults();
}

public record BudgetAllocation(
        int fixedAnchors,
        int exploration,
        int carryForward,
        int leaderRevalidation,
        int disagreementAudit) {
}

public final class BudgetAllocator {
    public static BudgetAllocation allocate(
            int candidateBudget,
            int fixedAnchorCount,
            CandidateBudgetConfig config);
}

public enum CoverageState { VALID, MISSING, REJECTED }

public record CarryScenarioState(
        SourceScenario scenario,
        CoverageState coverage,
        int attemptCount,
        OptionalInt lastAttemptIteration,
        int nextEligibleIteration,
        ScenarioPrediction prediction) {
}

public record CarryForwardEntry(
        PolicyVector policy,
        int firstSeenIteration,
        int lastUpdatedIteration,
        SortedMap<SourceScenario, CarryScenarioState> scenarios) {
}

public final class CarryForwardQueue {
    public static List<CarryForwardEntry> reconcile(
            List<CarryForwardEntry> previous,
            OptimizationCorpusView corpus,
            IterationSchedule completedSchedule,
            int completedIteration);
    public static List<CarryForwardEntry> rescore(
            List<CarryForwardEntry> entries,
            PolicyCurvePredictor predictor,
            int iteration);
    public static List<CarryForwardEntry> selectForScenario(
            List<CarryForwardEntry> entries,
            SourceScenario scenario,
            int iteration,
            int limit);
}

public record RotationGroup(String environmentId, int coreCount)
        implements Comparable<RotationGroup> {
}

public final class ScenarioRotation {
    public static List<SourceScenario> select(
            SortedSet<SourceScenario> requiredScenarios,
            SortedMap<RotationGroup, Integer> cursors,
            String activeEnvironmentId,
            int activeCoreCount,
            int scenariosPerIteration);
    public static SortedMap<RotationGroup, Integer> advance(
            SortedSet<SourceScenario> requiredScenarios,
            SortedMap<RotationGroup, Integer> cursors,
            List<SourceScenario> completedSelection);
}
```

`CandidateScheduler.prepare` consumes the measured view, calibration plan, queue, selected
scenarios, budgets, predictor, and iteration. It selects carry/leaders, scores selected measured
leaders, reuses the freshly rescored carry curves, and reports the maximum audit/new demand.
`CandidateScheduler.complete` consumes the exact `CandidateGenerationResult`, creates stable run
identity/seeds/trial order, and returns:

```java
public record SchedulePreparation(
        int iteration,
        int candidateBudget,
        BudgetAllocation requestedAllocation,
        List<SourceScenario> scenarios,
        List<PolicyVector> fixedAnchors,
        SortedMap<SourceScenario, List<CarryForwardEntry>> carryByScenario,
        List<RobustPolicySummary> leaders,
        List<ScheduledPolicyPrediction> measuredPredictions,
        int baseExplorationCount,
        int preAuditOverflowCount,
        int disagreementAuditCount) {
}

public enum RunKind { BOOTSTRAP, NORMAL }

public record ScheduledRun(
        RunKind runKind,
        SourceScenario scenario,
        String benchmarkRunId,
        String candidateCohortId,
        BenchmarkParameters parameters,
        List<ScheduledPolicy> policies) {
}

public record IterationSchedule(
        int iteration,
        List<ScheduledRun> runs,
        List<ScheduledPolicyPrediction> selectedPredictions,
        List<PolicyId> carryAdmissions,
        List<ScenarioBudgetReport> budgetReports,
        long nextSobolCursor) {
}

public record ScenarioBudgetReport(
        SourceScenario scenario,
        int candidateBudget,
        int fixedRequested,
        int fixedAssigned,
        int carryRequested,
        int carryAssigned,
        int leaderRequested,
        int leaderAssigned,
        int auditRequested,
        int auditAssigned,
        int explorationRequested,
        int explorationAssigned,
        int carryTransferredToExploration,
        int leaderTransferredToExploration,
        int auditTransferredToExploration,
        int totalAssigned) {
}

public final class CandidateScheduler {
    public static SchedulePreparation prepare(
            int iteration,
            int candidateBudget,
            CalibrationPlan calibrationPlan,
            OptimizationCorpusView corpus,
            List<CarryForwardEntry> rescoredQueue,
            List<SourceScenario> selectedScenarios,
            CandidateBudgetConfig budgetConfig,
            PolicyCurvePredictor predictor);

    public static IterationSchedule complete(
            String trainingRunId,
            long schedulerSeed,
            String commitSha,
            boolean dirtyWorkingTree,
            String cpuSetHex,
            BenchmarkExecutionConfig benchmarkConfig,
            SchedulePreparation preparation,
            CandidateGenerationResult generated);
}

public final class BootstrapScheduler {
    public static IterationSchedule create(
            String trainingRunId,
            SourceScenario scenario,
            List<PolicyVector> bootstrapPolicies,
            long schedulerSeed,
            long unchangedSobolCursor,
            String commitSha,
            boolean dirtyWorkingTree,
            String cpuSetHex,
            BenchmarkExecutionConfig benchmarkConfig);
}

public final class ScheduleCodec {
    public static Path write(
            Path targetDirectory,
            IterationSchedule schedule) throws IOException;
    public static IterationSchedule read(
            Path directory,
            SortedSet<SourceScenario> requiredScenarios,
            String expectedTrainingRunId,
            long schedulerSeed,
            String commitSha,
            boolean dirtyWorkingTree,
            BenchmarkExecutionConfig benchmarkConfig) throws IOException;
}
```

The schedule codec owns the five exact files and atomic publication.

### Closed-loop and checkpoint APIs

`ClosedLoopRunner` gains a primary typed entry point:

```java
public record ClosedLoopConfig(
        Path workspace,
        String trainingRunId,
        int iterations,
        int candidateBudget,
        SortedSet<SourceScenario> requiredScenarios,
        String activeEnvironmentId,
        int scenariosPerIteration,
        long schedulerSeed,
        long initialSobolCursor,
        Optional<Path> bootstrapPolicies,
        Optional<Path> initialCalibrationPlan,
        List<Path> initialObservationBundles,
        Map<SourceScenario, String> referenceOverrides,
        String commitSha,
        boolean dirtyWorkingTree,
        CandidateBudgetConfig budgetConfig,
        CandidateGenerationConfig generationConfig,
        BenchmarkExecutionConfig benchmarkConfig,
        AnchorSelectionConfig anchorSelectionConfig,
        CalibrationConfig calibrationConfig,
        AggregationConfig aggregationConfig,
        ScenarioTrainingConfig trainingConfig,
        boolean resume) {
}

public record ClosedLoopResult(
        CheckpointStage stage,
        int nextIteration,
        Path latestCheckpoint,
        Optional<Path> latestMerge,
        Optional<Path> latestModel,
        SortedSet<SourceScenario> awaitingScenarios) {
}

public static ClosedLoopResult run(ClosedLoopConfig config) throws Exception;
```

Retain `ClosedLoopRunner.StopRequested` as a stackless internal signal and add package-private
`static StopRequested stopSignal()` so `BenchmarkRunner` can use the same control path without
making the constructor public.

The no-argument `run()` remains a transitional Phase 3 property adapter and is marked
`ROBUST_OPTIMIZER_PHASE5_CONFIG`. It may construct only a single active-environment required
catalog from explicit environment ID plus configured absolute source counts, and it requires the
strict bootstrap policy path and explicit commit SHA. It does not accept `cycle.seed`, old model,
or old checkpoint inputs. Phase 5 replaces this adapter and documents final keys.

`trainingRunId` uses the Phase 1 opaque-ID syntax
`[a-z0-9][a-z0-9._-]{0,95}`. `initialObservationBundles` is allowed only with an explicit
new-format calibration plan. On first initialization, validate each complete schema-v1 bundle,
reject duplicate run identity, and atomically copy it to `evidence/<benchmarkRunId>/`. Include the
sorted bundle fingerprints in the frozen configuration hash. This is a strict new-format seed
path, not legacy import or format detection.

Under `io.euhedral_execution.training.checkpoint`:

```java
public enum EvidenceSource { INITIAL, BOOTSTRAP, ITERATION }
public enum PendingRunStatus { PENDING, COMPLETE }

public record ArtifactReference(String relativePath, String sha256) {
}

public record EvidenceIndexEntry(
        String benchmarkRunId,
        SourceScenario scenario,
        ArtifactReference bundle,
        EvidenceSource source) {
}

public record PendingBenchmarkRun(
        int iteration,
        RunKind runKind,
        SourceScenario scenario,
        String benchmarkRunId,
        String candidateCohortId,
        ArtifactReference schedule,
        String evidenceRelativePath,
        PendingRunStatus status) {
}

public record ClosedLoopCheckpoint(
        int schemaVersion,
        String trainingRunId,
        int revision,
        CheckpointStage stage,
        int nextIteration,
        long sobolCursor,
        String configSha256,
        SortedSet<SourceScenario> requiredScenarios,
        SortedMap<RotationGroup, Integer> rotationCursors,
        List<EvidenceIndexEntry> evidence,
        List<CarryForwardEntry> carryForward,
        Optional<String> anchorSetId,
        Optional<ArtifactReference> calibrationPlan,
        Optional<ArtifactReference> latestMerge,
        Optional<ArtifactReference> latestModel,
        Optional<ArtifactReference> pendingSchedule,
        List<PendingBenchmarkRun> pendingRuns) {
}

public record LoadedCheckpoint(
        Path snapshotDirectory,
        ClosedLoopCheckpoint checkpoint) {
}

public final class CheckpointSnapshotCodec {
    public static Optional<LoadedCheckpoint> loadLatest(
            Path workspace,
            String expectedTrainingRunId,
            String expectedConfigSha256) throws IOException;
    public static LoadedCheckpoint writeNext(
            Path workspace,
            ClosedLoopCheckpoint checkpoint) throws IOException;
}

public final class ArtifactFingerprint {
    public static String sha256(Path artifact) throws IOException;
}

public final class WorkspaceLock implements AutoCloseable {
    public static WorkspaceLock acquire(Path workspace) throws IOException;
}
```

Use a package-private `ClosedLoopServices` interface for deterministic state-machine tests:

```java
interface ClosedLoopServices {
    CalibrationPlan bootstrapCalibration(
            DataMerger.CalibrationBootstrapRequest request) throws Exception;
    DataMerger.MergeArtifacts merge(
            DataMerger.MergeRequest request) throws Exception;
    ScenarioTrainingArtifacts train(
            ScenarioTrainingRequest request) throws Exception;
    ScenarioConditionedModel loadAcceptedModel(
            Path modelDirectory, String producingDevice) throws Exception;
    BenchmarkRunContext benchmark(
            NativeBenchmarkRunPlan plan,
            BooleanSupplier stopRequested) throws Exception;
    boolean stopRequested();
}
```

The production implementation delegates directly to Phase 1, Phase 2, and `BenchmarkRunner`.
There is no public plugin mechanism. The checkpoint codec is strict for this one schema, not a
general CSV or version registry.

## Data flow and dependency boundaries

```text
strict bootstrap vectors
    |
    v
native iteration-0 bundles for every required exact scenario
    |
    v
frozen Phase 1 CalibrationPlan
    |
    v
Phase 1 merge M0
    |
    v
Phase 2 accepted scenario model
    |
    +----------------------------+
    |                            |
    v                            v
measured robust leaders      predicted complete curves
    |                            |
    +-- anchors / leaders        +-- CMA / bands / audits / direct Sobol
                  \              /
                   v            v
              per-scenario schedule
                       |
                       v
              native Phase 1 bundles
                       |
                       v
                Phase 1 post-merge Mi
                       |
                       +-- robust leaders
                       +-- coverage -> persisted carry queue
                       +-- next Phase 2 retrain
```

`euhedral-core` knows only how to create deterministic benchmark frames. It does not know policy,
scenario, model, queue, or checkpoint types. Phase 1 merger and Phase 2 learning packages remain
independent of scheduler/checkpoint classes. The scheduler consumes their immutable outputs.

## Memory semantics, memory pollution, and mathematical precision

### Memory semantics

Optimizer, schedule construction, checkpointing, CSV parsing, and report writing are offline,
single-owner operations. Their mutable maps, heaps, cursors, and queue builders are thread-confined;
published records are immutable. Do not add VarHandles, atomics, pinned executors, parallel
floating-point reductions, or parallel island execution to this path.

`ScenarioConditionedModel` remains owner-confined and is called sequentially. A persisted schedule
is the publication boundary between model inference and later benchmark execution.

Benchmark sources retain their existing one-owner callback contract. Tighten the shared consumed
counter publication explicitly:

- the source callback is the single writer and publishes the new monotonic total with release;
- the benchmark coordinator reads it with acquire;
- reset uses release only after `pause` observes `inFlight == 0` with acquire;
- no reset occurs while enabled; and
- no CAS is needed because the source handle contract serializes the writer.

Implement this with a dedicated `VarHandle` to the `long consumed` field or an equivalently explicit
acquire/release accessor. Do not make the field volatile and then also retain redundant standalone
fences. The pause barrier's atomic `enabled`/`inFlight` transitions order the reset against prior
callbacks.

`FragmentActionPicker.setWeights`, source pause/resume, and
`ControlPlaneLattice.resetForNextTrial` keep their current ownership and publication semantics.
Phase 3 must not strengthen or weaken runtime VarHandle operations outside the benchmark counter
without a separate happens-before argument.

### Memory pollution and benchmark isolation

- Stream Sobol policies in bounded prediction batches.
- Retain only bounded best, audit, band, and CMA proposal heaps, not the entire screen.
- Intern one immutable vector per selected/history policy ID.
- Carry queue state is one policy plus one compact row per required scenario, not raw observations.
- Checkpoint snapshots stream sorted rows and do not build corpus-sized JSON strings.
- Load Phase 1 scenario/run aggregates, not raw repetition objects, for scheduling.
- Reuse CMA primitive matrices and model inference buffers where practical.
- Generate one reusable frame array per benchmark source and close every lattice/source/model.
- Retain at most one policy's repetition results before writing.
- Never parse CSV, predict, checkpoint, log formatted vectors, or write evidence while sources are
  enabled.
- Tests use `@TempDir` and never read or write the user-owned current workspace trees.
- Incomplete attempts and prior checkpoints are disk evidence, not heap state; they are discovered
  lazily.

### Mathematical precision and determinism

- Predicted and measured robust fields remain `double`; do not reduce them to `float` scores.
- Use the Phase 1 type-7 quantile, MAD, compensated mean, epsilon, and `StrictMath` conventions.
- Exact comparator ties use `Double.compare`; no generic epsilon exists.
- Hamilton budgets and capacities use checked integer arithmetic, not floating fractions.
- Sampling keys, seeds, run IDs, and cohort IDs use canonical UTF-8 material and `HasherApi`.
- Sort all map/set inputs before hashing, arithmetic, output, and seed derivation.
- Use `StrictMath` for CMA square roots, exponentials, powers, trigonometry, and norms where the
  current code uses `Math`; preserve fixed loop order.
- Neural inference may differ across devices as Phase 2 documents. Schedule generation loads the
  model on its producing device, and the persisted schedule prevents a resume from reranking it.
- Raw policy weights and metadata seeds use exact bits. Derived CSV doubles use
  `Double.toString`.
- No output ordering relies on `HashMap`, filesystem enumeration, thread completion, locale,
  current time, or random UUID. UUIDs may distinguish temporary directories only and never enter
  artifact bytes or semantic IDs.
- Benchmark timestamps and measurements are intentionally nondeterministic evidence. Given the same
  evidence bundles, merger outputs and subsequent schedules are deterministic.

## Compatibility and deletion boundary

Phase 3 retains only these pooled-v0 entry points:

- `legacy.PooledSequenceFinder`;
- `legacy.PooledBenchmarkRunner`;
- `DataMerger.mergeQuentiles`, `mergeQuantiles`, and their marked helpers;
- `BenchmarkOutputReader`, `BenchmarkOutputWriter`, `PolicyOrdinalNetwork`, `PolicyRanking`,
  `Distribution`, and `VectorGrouper`; and
- the transitional `Runner` commands that still expose pooled standalone operations.

Every retained class or method carries the existing single
`ROBUST_OPTIMIZER_POOLED_V0_REMOVAL` marker at the boundary, not scattered branches in new code.
`ClosedLoopRunner.run(ClosedLoopConfig)` has no pooled dependency. Phase 5 replaces configuration
and importer-facing commands; Phase 7 deletes the marked compatibility surface after no callers
remain.

Checkpoint schema v1 has no reader for `state.properties`. A pooled workspace must be imported by
Phase 5 or started as a new run. A missing Phase 3 `state.csv` never triggers speculative legacy
detection.

## File-by-file implementation checklist

Implement in this dependency order.

1. Add deterministic routing-seed overloads to
   `euhedral-core/src/main/java/io/euhedral_execution/core/frames/BenchmarkFrame.java` and focused
   `BenchmarkFrameTest`. Do not change other frame lifecycle or routing semantics.
2. Add predicted summary, comparator, predictor interface, candidate/schedule origin records,
   `SchedulerSeeds`, and `CmaEsConfig` under
   `euhedral-training/src/main/java/io/euhedral_execution/training/optimization/` as
   `PredictedPolicySummary.java`, `PredictedPolicyRanker.java`,
   `PredictedPolicyComparator.java`, `PolicyCurvePredictor.java`, `CandidateOrigin.java`,
   `SchedulePolicyOrigin.java`, `PredictedCandidate.java`, `ScheduledPolicyPrediction.java`,
   `SchedulerSeeds.java`, and `CmaEsConfig.java`.
3. Rewrite `CmaEsOptimizer.java` around eligible Phase 1 seeds and exact predicted summaries.
   Preserve its full-covariance mechanics and settled restarts.
4. Rewrite `ScoreBandSampler.java` with fixed robust bands, exact capacity allocation, hash-priority
   bottom-k reservoirs, and bounded best-first backfill.
5. Add `CandidateGenerationConfig.java`, `CandidateGenerationRequest.java`, and
   `CandidateGenerationResult.java`. Move the current pooled `SequenceFinder` implementation to
   `training/legacy/PooledSequenceFinder.java`; replace `SequenceFinder.java` with the robust
   streaming CMA/Sobol/audit/exploration facade.
6. Add `OptimizationCorpusView.java`, `OptimizationCorpusReader.java`, and
   `BootstrapPolicyCsv.java` under `training/scheduling`. Strictly reconstruct the Phase 1 records
   without changing Phase 1 files or mathematics.
7. Add `CandidateBudgetConfig.java`, `BudgetAllocation.java`, and `BudgetAllocator.java`. Share one
   package-private exact integer Hamilton helper with exploration and score-band allocation.
8. Add `CoverageState.java`, `CarryScenarioState.java`, `CarryForwardEntry.java`, and
   `CarryForwardQueue.java` under `training/scheduling` with admission, rescore, coverage
   reconciliation, priority, attempts, and capped backoff.
9. Add `RotationGroup.java` and `ScenarioRotation.java`.
10. Add `SchedulePreparation.java`, `RunKind.java`, `ScheduledRun.java`,
    `ScenarioBudgetReport.java`, `IterationSchedule.java`, `CandidateScheduler.java`,
    `BootstrapScheduler.java`, and `ScheduleCodec.java` under `training/scheduling`.
11. Add `BenchmarkExecutionConfig.java` and `NativeBenchmarkRunPlan.java` under
    `training/benchmark`.
12. Update `BenchmarkFrameSink.java` with the documented consumed-counter acquire/release
    publication. Do not change its queue topology or pause protocol.
13. Move the current `BenchmarkRunner` implementation to
    `training/legacy/PooledBenchmarkRunner.java`. Replace `BenchmarkRunner.java` with the v1 runner
    using deterministic frame seeds, monotonic counter deltas, stable statuses, paused evidence
    writes, strict read-back, and atomic bundle publication.
14. Add `CheckpointStage.java`, `EvidenceSource.java`, `PendingRunStatus.java`,
    `ArtifactReference.java`, `EvidenceIndexEntry.java`, `PendingBenchmarkRun.java`,
    `ClosedLoopCheckpoint.java`, `LoadedCheckpoint.java`, `CheckpointSnapshotCodec.java`,
    `ArtifactFingerprint.java`, and `WorkspaceLock.java` under `training/checkpoint`.
15. Add top-level `ClosedLoopConfig.java`, `ClosedLoopResult.java`, and package-private
    `ClosedLoopServices.java` under `training`. Replace `ClosedLoopRunner`'s pooled pipeline and
    `state.properties` logic with the typed state machine, bootstrap, Phase 1/2 integration,
    scheduling, native benchmark execution, post-merge, carry reconciliation, and resume/adoption
    rules.
16. Update `Runner` only enough to route its transitional pooled commands to
    `PooledSequenceFinder`/`PooledBenchmarkRunner` and call the new closed-loop adapter. Do not
    finalize Phase 5 CLI help or documentation.
17. Do not edit Phase 1 schemas/math, Phase 2 schemas/model math, either prior blueprint, current
    training README, current-workspace data, POMs, or module descriptors.
18. Add only the focused fixtures/tests below and append Prompt 3B completion notes to this file.

## Deterministic fixtures and acceptance tests

Add fixtures under
`euhedral-training/src/test/java/io/euhedral_execution/training/scheduling/fixtures/`. They use
small immutable Phase 1 summaries and deterministic fake curve predictors. They never load DJL
unless an existing opt-in Phase 2 integration test does so.

### Predicted comparator tests

`PredictedPolicyComparatorTest`:

- reproduces the Phase 1 four-policy/three-scenario robust fixture and proves the policy predicted
  second-best everywhere beats three specialists on minimum quality;
- varies one tuple tier at a time and asserts worst, P25, geometric mean, MAD, epistemic,
  disagreement, ordinal deviation, then ID order;
- verifies exact ties and the `1.0e-12` geometric epsilon;
- proves audit order does not replace robust order; and
- rejects missing, duplicate, extra, or reordered scenarios and non-finite fields.

### Budget and role tests

`BudgetAllocatorTest`:

- checks default allocations for residual sizes 1 through 256 against an independent integer
  Hamilton fixture;
- checks exact remainder tie order;
- proves fixed anchors are reserved before residual allocation;
- rejects `B <= A`, overflow, negative/all-zero weights; and
- proves requested counts always sum to `B`.

`CandidateSchedulerTest`:

- schedules every fixed anchor in every selected scenario;
- distributes anchor positions at the exact midpoint indexes;
- selects measured leaders only from Phase 1 eligible summaries and excludes fixed anchors;
- proves an incomplete specialist with a perfect prediction cannot receive
  `LEADER_REVALIDATION`;
- transfers short carry/leader/audit pools to exploration and still emits exactly `B` unique
  policies;
- uses the same leading exploration/audit sets across scenario runs while allowing different carry
  sets;
- produces exact role/budget report counts; and
- emits byte-identical schedules from shuffled maps, summaries, predictions, and queue rows.

### Candidate generation tests

`CmaEsOptimizerTest`:

- seeds island zero with the measured robust best;
- selects remaining island seeds by settled distance and tie-break;
- uses complete curve predictions for every population;
- proves a robust curve beats a one-scenario specialist even when a scalar mean would prefer the
  specialist;
- produces finite normalized policies and deterministic output under the fixed seed;
- skips with too few eligible non-anchor seeds; and
- proves input permutation does not change island seeds or selected best candidates.

`ScoreBandSamplerTest`:

- assigns exact boundary values to the higher fixed band and `1.0` to band nine;
- allocates capacities with exact Hamilton arithmetic;
- produces the same result for forward, reverse, and shuffled arrival order;
- proves different seeds change sampling keys without changing capacities;
- fills sparse-band shortfalls from exact best-first overflow; and
- validates collision/dedup behavior.

`SequenceFinderTest`:

- streams a bounded Sobol screen and never retains more than the configured selector capacities;
- excludes every historical, anchor, carry, and leader ID from new candidates;
- selects audits by uncertainty separately from robust exploration;
- gives the exact 8:7:1 default new-exploration mix before transfers;
- transfers insufficient CMA/band supply to direct Sobol and fills exactly;
- proves direct Sobol selection does not depend on its model score;
- records predictions for selected direct policies after selection;
- advances the cursor by every consumed point, including duplicates; and
- produces identical results from the same cursor/seed and different results from the next cursor.

### Carry and rotation tests

`CarryForwardQueueTest`:

- admits only the predicted best admission-capacity policies from new/audit roles;
- keeps admitted failed policies instead of dropping them;
- prioritizes coverage, pessimistic missing interval, low uncertainty, age, then ID exactly;
- schedules only missing/rejected target scenarios;
- increments attempts and yields backoff delays `1, 2, 4, 8, 8`;
- retains a downgraded prediction until coverage becomes complete;
- removes an entry only when the new Phase 1 summary is eligible; and
- produces the same queue bytes from shuffled merge rows.

`ScenarioRotationTest`:

- rotates `[s1,s2,s3,s4]` by persisted cursor and wraps exactly;
- keeps independent cursors for two environments with equal source ratios;
- does not advance on schedule, benchmark failure, or restart;
- advances only after post-merge success; and
- rejects an environment/core pair absent from the required catalog.

### Schedule identity and checkpoint tests

`ScheduleCodecTest`:

- recomputes exact cohort/run IDs, trial keys, frame seeds, positions, roles, vector bits, and all
  five headers;
- proves policy set identity is independent of final trial order;
- rejects a changed role, bit, scenario, parameter, CPU mask, seed, admission, budget count, or
  prediction summary;
- rejects path traversal and symlinks; and
- emits byte-identical bytes for equivalent logical inputs.

`CheckpointSnapshotCodecTest`:

- round-trips every stage, artifact reference, required scenario, rotation cursor, evidence-index
  row, carry row, prediction, attempt, and pending run;
- changes `config_sha256` when every included config component is changed individually;
- permits active-environment/path relocation without changing the config hash;
- rejects invalid stage transitions, missing stage-required artifacts, bad hashes, an eligible
  queue policy, incomplete scenario grids, duplicate/unindexed evidence, non-contiguous revisions,
  and a missing `COMPLETE`;
- ignores incomplete temporary snapshots but rejects corruption in the highest complete snapshot;
  and
- proves a directory fingerprint is path-order independent and changes for filename, length, or
  byte changes.

### Native benchmark tests

`BenchmarkFrameTest` in `euhedral-core`:

- asserts deterministic unordered routing hashes equal
  `idHash ^ HasherApi.mix(routingSeed + i)`;
- asserts ordered frames retain `routingHash == idHash`; and
- covers the kill-switch overload without changing liveness semantics.

`BenchmarkRunnerV1Test` uses package-private fake benchmark sources/counters and a fixed clock:

- writes one complete strict bundle with success, timeout, failed, and skipped rows;
- proves throughput is frames per second with exact raw bits;
- proves counters reset only while paused and repetitions use deltas;
- proves source ID/routing seeds and all benchmark parameters round-trip;
- writes observations only while the fake source is paused;
- aborts and retains an incomplete attempt on reset/isolation failure or a stop between policies;
- reuses the stable run ID but a new attempt directory on retry;
- refuses a core-count mismatch, changed CPU mask, hidden seed, or preexisting final bundle; and
- publishes the final bundle only through an atomic move.

### Closed-loop restart and integration tests

`ClosedLoopRunnerTest` uses `ClosedLoopServices` fakes and deterministic Phase 1-shaped artifacts:

1. Bootstrap on environment A, stop awaiting environment B, resume on B, freeze one common anchor
   plan, and create `merge-000000`.
2. Run an uninterrupted two-iteration loop and a loop interrupted:
   - after model publication;
   - after schedule publication;
   - after the first of two scenario bundles; and
   - after final bundle publication but before checkpoint.
3. Assert every resumed case has byte-identical iteration schedules, final merge datasets/ranking,
   carry queue semantics, Sobol cursor, and rotation cursors to the uninterrupted case.
4. Assert a complete expected bundle is adopted but an unexpected complete run is rejected.
5. Assert an incomplete attempt is preserved and only that scenario is rerun.
6. Assert missing scenarios later complete through carry-forward under rotating source
   configurations.
7. Assert every merge uses all and only complete expected corpus bundles.
8. Assert a rejected Phase 2 model produces `MODEL_REJECTED` and no schedule.
9. Assert no incomplete policy appears in the leader role or measured leader list.
10. Assert the final iteration performs a post-merge and the final ranking includes its evidence.

## Prompt 3B acceptance criteria

Prompt 3B is complete only when:

- every optimized candidate is evaluated as a complete required-scenario curve;
- predicted robust quality uses the settled lexicographic comparator with no scalar authority;
- CMA-ES is seeded only from measured eligible non-anchor policies;
- score-band and direct Sobol exploration are deterministic and bounded in memory;
- each scenario run contains exactly its budget, every fixed anchor, and explicit disjoint roles;
- requested and transferred role counts are machine-readable and exact;
- measured incomplete policies cannot enter leader revalidation;
- admitted incomplete policies persist across restart and model changes until valid coverage is
  complete;
- carry selection uses coverage, pessimistic missing quality, uncertainty, age, and ID in the
  settled order;
- disagreement audits remain a separate budget and do not imply leader or carry status;
- source rotation is exact-scenario based, per environment/core group, and checkpointed;
- stable cohort, run, trial, and source seeds are reconstructable without paths or timestamps;
- native runs emit complete strict Phase 1 bundles with explicit failure/skip status and frames per
  second;
- benchmark evidence writes happen only while sources are paused;
- counter reset is ordered by the pause barrier and never races flowing work;
- checkpoint snapshots are strict, atomic, relocatable, and sufficient to reproduce a pending
  schedule;
- restart produces the same schedule and Phase 1 ranking as uninterrupted execution;
- complete expected crash-window artifacts are adopted and unexpected evidence is rejected;
- the final iteration's evidence is post-merged;
- the new closed loop never loads pooled files/models/checkpoints or rejected Phase 2 models;
- new optimizer/scheduler/checkpoint code contains no `PolicyRanking`, P99 normalization,
  `TDigest`, legacy path inference, or old model migration;
- only the files in this checklist, focused tests/resources, and this blueprint's completion record
  change; and
- user-owned current workspace input/output data remains untouched.

## Validation commands for Prompt 3B

From the repository root:

```bash
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -am install -Dmaven.test.skip=true

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-core -Dtest=BenchmarkFrameTest test

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test

git diff --check
git status --short
```

Run targeted stale-boundary searches:

```bash
rg -n "PolicyRanking|PolicyOrdinalNetwork|BenchmarkOutputReader|BenchmarkOutputWriter|TDigest|quantile\\(0\\.99\\)|mergeQuantiles" \
  euhedral-training/src/main/java/io/euhedral_execution/training/optimization \
  euhedral-training/src/main/java/io/euhedral_execution/training/scheduling \
  euhedral-training/src/main/java/io/euhedral_execution/training/checkpoint \
  euhedral-training/src/main/java/io/euhedral_execution/training/benchmark \
  euhedral-training/src/main/java/io/euhedral_execution/training/BenchmarkRunner.java \
  euhedral-training/src/main/java/io/euhedral_execution/training/ClosedLoopRunner.java \
  euhedral-training/src/main/java/io/euhedral_execution/training/SequenceFinder.java

rg -n "input/merger|state\\.properties|latest-model|latest-training-data|iteration-.*source|graviton|zen4|euhedral-policy-ranker|\\.bin" \
  euhedral-training/src/main/java/io/euhedral_execution/training/optimization \
  euhedral-training/src/main/java/io/euhedral_execution/training/scheduling \
  euhedral-training/src/main/java/io/euhedral_execution/training/checkpoint \
  euhedral-training/src/main/java/io/euhedral_execution/training/benchmark \
  euhedral-training/src/main/java/io/euhedral_execution/training/BenchmarkRunner.java \
  euhedral-training/src/main/java/io/euhedral_execution/training/ClosedLoopRunner.java \
  euhedral-training/src/main/java/io/euhedral_execution/training/SequenceFinder.java
```

Both searches must return no new-path matches. Matches inside the two marked
`training/legacy/PooledSequenceFinder.java` and
`training/legacy/PooledBenchmarkRunner.java` compatibility classes are expected and are not
included in these search roots.

Also inspect:

```bash
git diff --name-only
git diff -- docs/robust-training-optimizer/blueprints/03-optimizer-scheduling.md
```

If native hardware initialization or affinity prevents an actual lattice smoke test, report that
environment limitation separately. The deterministic fake benchmark test, focused core frame test,
and full training unit suite remain required.

## Risks and later-phase handoff

There is no unresolved Phase 3 statistical or architectural blocker.

Settled later-phase inputs:

- Phase 4 packages the latest Phase 1 post-merge, schedule budget/prediction evidence, accepted
  model, latest complete checkpoint snapshot, and complete raw bundles. It decides package-level
  checksums and partial-run presentation.
- Phase 5 supplies the final CLI/configuration, exact current-workspace importer, and user
  documentation. It may construct `ClosedLoopConfig` but may not change its scheduling semantics.
- Phase 6 exercises this state machine in the end-to-end synthetic experiment.
- Phase 7 deletes `legacy.PooledSequenceFinder`, old standalone benchmark/merger/model paths, and
  transitional configuration only after Phase 5 has no callers.

Prompt 3B must append completion notes below this line with changed files, commands, results,
checkpoint/restart evidence, environmental limits, and deviations. A deviation affecting predicted
ordering, budget math, carry admission/priority, run identity, benchmark evidence, state
transitions, or restart behavior requires another reasoning pass.

## Prompt 3B completion notes

Partial implementation attempt on 2026-07-28.

Implemented:

- Added deterministic `BenchmarkFrame.generate(..., routingSeed, ...)` overloads and focused
  `BenchmarkFrameTest`.
- Added Phase 3 predicted-policy primitives under `training/optimization`:
  `PredictedPolicySummary`, `PredictedPolicyComparator`, `PolicyCurvePredictor`, candidate/origin
  records, and `CmaEsConfig`.
- Replaced `ScoreBandSampler` with fixed ten-band, Hamilton-capacity, hash-priority bottom-k
  retention and best-first overflow backfill. Retained the pooled-v0 constructor/adapter so existing
  transitional tests and callers still compile.
- Added scheduler primitives under `training/scheduling`: checked integer `HamiltonAllocator`,
  candidate budget records/allocator, coverage states, carry-forward row/queue priority with capped
  backoff, and exact per-environment/core scenario rotation.

Commands run:

```text
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -DskipTests compile
  -> first run failed on carry record constructor validation; fixed and reran successfully

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-core -Dtest=BenchmarkFrameTest test
  -> success, 3 tests

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -Dtest=ScoreBandSamplerTest,CmaEsOptimizerTest test
  -> success, 2 tests

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test
  -> success, 86 tests, 1 skipped optional DJL integration test
```

Deviation/blocker:

- Prompt 3B is not complete. The settled blueprint requires replacing `SequenceFinder`,
  `CmaEsOptimizer`, `ClosedLoopRunner`, checkpoints, and `BenchmarkRunner` with the full
  scenario-aware state machine and restart/adoption semantics. This pass implemented only the
  comparator, budget, carry, rotation, score-band, config, and deterministic frame foundations.
- Required blueprint tests for restart, rotating scenarios, budget accounting, deduplication, and
  deterministic selection were not added or run beyond the existing optimizer tests and the new
  frame test.
- No commit or push was made from this partial state because the acceptance criteria are not met.

## Prompt 3C verification notes

Implementation verification performed on 2026-07-28.

Result:

- Prompt 3C could not verify the full Phase 3 acceptance surface because Prompt 3B remains a
  partial implementation. The restart, checkpoint, schedule-codec, native-v1 benchmark,
  closed-loop adoption, and final post-merge paths required by this blueprint are absent.
- Fixed only blueprint-settled defects in the implemented subset:
  - `ScoreBandSampler` now exposes the Phase 3 constructor shape with explicit iteration and
    overflow capacity, and includes the iteration in the score-band sampling-key hash material.
  - `ScoreBandSamplerTest` now asserts iteration-specific deterministic selection for the
    implemented score-band sampler.
  - `CarryForwardEntry` now rejects carry scenario maps whose key does not match the contained
    scenario state.
  - `BenchmarkOutputReader` now declares `getLines()` explicitly; relying on the Lombok-generated
    accessor did not compile after a clean recompilation.

Commands run:

```text
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -am install -Dmaven.test.skip=true
  -> success

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-core -Dtest=BenchmarkFrameTest test
  -> success, 3 tests

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test
  -> success, 87 tests, 1 skipped optional DJL integration test

git diff --check
  -> success

git diff --cached --check
  -> success
```

Stale-boundary search results:

```text
rg -n "PolicyRanking|PolicyOrdinalNetwork|BenchmarkOutputReader|BenchmarkOutputWriter|TDigest|quantile\(0\.99\)|mergeQuantiles" ...
  -> failed: matches remain in BenchmarkRunner.java, ClosedLoopRunner.java, SequenceFinder.java,
     and CmaEsOptimizer.java

rg -n "input/merger|state\.properties|latest-model|latest-training-data|iteration-.*source|graviton|zen4|euhedral-policy-ranker|\.bin" ...
  -> failed: matches remain in SequenceFinder.java and ClosedLoopRunner.java
```

Acceptance-criteria status:

- Verified for the implemented subset:
  - deterministic `BenchmarkFrame` routing overload;
  - score-band fixed-band allocation and deterministic iteration-specific hash-priority selection;
  - full training module regression suite after the subset fixes.
- Not verifiable because required Phase 3 implementation is absent:
  - restart equivalence;
  - checkpoint snapshot schema, strict validation, and recovery/adoption;
  - exact schedule codec and stable run/cohort/trial/source seed reconstruction;
  - per-scenario budget reports from `CandidateScheduler`;
  - carry-forward persistence across restart/model changes and reconciliation from post-merge
    coverage;
  - complete native-v1 benchmark bundle publication and paused evidence writes;
  - final iteration post-merge and closed-loop rejection of pooled files/models/checkpoints.

Environmental and workspace limits:

- No native lattice smoke test was run; the blueprint requires deterministic fake benchmark tests
  and full training unit tests for this phase, but the fake native-v1 benchmark tests are not
  present in the partial implementation.
- Pre-existing user-owned training input/output data remained untouched. Two staged input files
  under `euhedral-training/input/merger/` were present before this verification and must not be
  included in the Phase 3 verification commit.

## Prompt 3B completion notes - missing-feature implementation

Implementation pass on 2026-07-28 after Prompt 3C identified the incomplete 3B surface.

Implemented:

- Moved pooled-v0 `SequenceFinder` and `BenchmarkRunner` implementations to
  `io.euhedral_execution.training.legacy.PooledSequenceFinder` and
  `io.euhedral_execution.training.legacy.PooledBenchmarkRunner`, each marked with
  `ROBUST_OPTIMIZER_POOLED_V0_REMOVAL`.
- Replaced new-path `SequenceFinder` with the Phase 3 candidate-generation facade using complete
  `PredictedPolicySummary` inputs, CMA, score-band, audit, and direct-Sobol partitions.
- Replaced `CmaEsOptimizer` with a curve-prediction API over eligible Phase 1 robust summaries,
  while retaining pooled-v0 adapter records only for the legacy class.
- Added `SchedulerSeeds`, `PredictedPolicyRanker`, and candidate-generation request/result/config
  records.
- Added Phase 3 scheduling records and helpers for optimization corpus view, bootstrap vectors,
  budgeted schedule preparation/completion, bootstrap scheduling, schedule CSV writing/reading,
  scenario rotation, and carry queue static APIs.
- Added Phase 3 benchmark request records and a typed `BenchmarkRunner.runV1` that emits strict
  schema-v1 observation bundles through `ObservationBundleWriter` and validates them with
  `ObservationBundleReader.stream`.
- Added checkpoint records, artifact fingerprinting, workspace locking, and atomic snapshot
  directory writing/loading under `training/checkpoint`.
- Added typed `ClosedLoopConfig`, `ClosedLoopResult`, and `ClosedLoopServices`, and replaced
  `ClosedLoopRunner`'s new path with a typed checkpoint-owning entry point. The no-arg adapter now
  rejects use until Phase 5 supplies final configuration.
- Updated `Runner` so transitional pooled commands call the legacy classes explicitly.
- Updated `BenchmarkFrameSink` consumed-counter publication to use an explicit VarHandle
  acquire/release accessor instead of standalone acquire/release fences around plain access.

Commands run:

```text
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -DskipTests compile
  -> first run failed on package-private StrictCsv access; replaced new-code calls with local
     minimal CSV handling
  -> second run failed on the earlier partial primitive API mismatch; normalized
     PolicyCurvePredictor, PredictedCandidate, ScheduledPolicyPrediction, and SchedulePolicyOrigin
     to the blueprint contract
  -> rerun succeeded

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test
  -> success, 87 tests, 1 skipped optional DJL integration test
```

Implementation limits:

- This pass completes the missing Phase 3 API and stale-boundary integration surface and preserves
  deterministic scheduling/checkpoint artifact contracts at the unit-testable API level.
- The synthetic restart/interruption matrix and fake native-v1 benchmark tests described in the
  blueprint are still not present as separate test classes in this repository state. The rerun 3C
  record below reports that limitation explicitly instead of treating the existing 87-test suite as
  equivalent coverage for those detailed scenarios.

## Prompt 3C verification notes - rerun after missing-feature implementation

Implementation verification rerun on 2026-07-28.

Commands run:

```text
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -am install -Dmaven.test.skip=true
  -> success

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-core -Dtest=BenchmarkFrameTest test
  -> success, 3 tests

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test
  -> success, 87 tests, 1 skipped optional DJL integration test

rg -n "PolicyRanking|PolicyOrdinalNetwork|BenchmarkOutputReader|BenchmarkOutputWriter|TDigest|quantile\\(0\\.99\\)|mergeQuantiles" ...
  -> success, no matches in new-path roots

rg -n "input/merger|state\\.properties|latest-model|latest-training-data|iteration-.*source|graviton|zen4|euhedral-policy-ranker|\\.bin" ...
  -> success, no matches in new-path roots

git diff --check
  -> success
```

Acceptance evidence:

- Pooled-v0 classes and old model/file dependencies are isolated outside the new-path search roots.
- Score-band deterministic selection, deterministic `BenchmarkFrame` routing, Phase 1 merger,
  Phase 2 learning, and existing optimizer regression tests pass.
- Checkpoint, schedule, benchmark, and closed-loop APIs now compile and expose the blueprint-settled
  typed contracts.

Remaining verification limits:

- Dedicated `CandidateSchedulerTest`, `SequenceFinderTest`, `CarryForwardQueueTest`,
  `ScenarioRotationTest`, `ScheduleCodecTest`, `CheckpointSnapshotCodecTest`,
  `BenchmarkRunnerV1Test`, and `ClosedLoopRunnerTest` fixtures from the blueprint are not yet
  present as separate test classes. The implementation compiles and the current suite passes, but
  the exact restart interruption matrix and every schedule/checkpoint rejection case are not
  independently exercised by tests in this pass.
- No native lattice smoke test was run. The deterministic core frame test and full training suite
  were run.
- Pre-existing staged and untracked training input/output data remained untouched and must remain
  excluded from the implementation commit.
