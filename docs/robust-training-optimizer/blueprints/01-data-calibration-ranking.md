# Phase 1 Blueprint: Versioned Data, Calibration, and Robust Ranking

Status: ready for Prompt 1B implementation

This blueprint settles the Phase 1 contracts. Prompt 1B must implement these decisions without
reopening the statistical model or rediscovering the current trainer. If an implementation detail
cannot satisfy this document, stop and record the conflict here instead of silently choosing a new
contract.

## Scope

Phase 1 adds a strict, versioned representation of benchmark evidence and a separate v1 merge
pipeline that:

1. validates policy, run, cohort, iteration, scenario, environment, repetition, and provenance
   identity;
2. computes exact within-run policy statistics without pooling repetitions across runs;
3. selects and freezes fixed anchors and per-scenario reference runs;
4. calibrates every non-reference run directly to its reference with a capped weighted median in
   natural-log space;
5. aggregates calibrated run estimates one run at a time within each exact scenario;
6. assigns scenario-relative midrank percentile quality;
7. emits scenario rows, calibration health, coverage, and an authoritative robust policy summary;
8. orders eligible policies with the required lexicographic comparator; and
9. keeps a narrow boundary for later benchmark, predictor, scheduler, importer, and packager work.

Phase 1 may change `DataMerger` and add data, codec, calibration, aggregation, ranking, and test
classes. It must not make broad changes to the predictor, optimizer, or closed-loop scheduler.

### Explicit non-goals

- Do not change `SequenceFinder`, `PolicyOrdinalNetwork`, `CmaEsOptimizer`, or
  `ScoreBandSampler`. Phase 2 and Phase 3 will replace their pooled-policy inputs.
- Do not change candidate-budget allocation, rotating scenario scheduling, carry-forward, leader
  revalidation, or checkpoint state. Phase 3 owns those decisions.
- Do not make `BenchmarkRunner` emit v1 bundles yet. Phase 3 will supply stable training-run,
  cohort, role, anchor, and run IDs when it integrates the codec. Phase 1 supplies and tests the
  complete domain and serialization API that it will call.
- Do not package final reports or models. Phase 4 owns the final package.
- Do not read, infer, or convert the current two-line benchmark files. Phase 5 owns the only
  current-workspace importer.
- Do not migrate legacy DJL directories, DL4J files, optimizer state, or checkpoints.
- Do not remove the existing vector-only bit-text reader/writer while current predictor and
  benchmark entry points still require it.
- Do not scalarize the robust comparator.

## Reconciliation with the current implementation

The following current behavior is the reason for each new seam.

| Current code                                                | Current contract                                                                                 | Phase 1 decision                                                                                                                                               |
|-------------------------------------------------------------|--------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `DataMerger.normalize`                                      | Divides each file by its own P99 of all finite measurements and clamps to `[0, 1]`               | The new v1 path never calls it. Calibration uses only fixed anchors shared with a frozen reference run.                                                        |
| `DataMerger.merge`                                          | Pools every normalized repetition for an equal vector into one `TDigest`                         | The new path first creates a policy/run/scenario estimate, then a median of run estimates, and never weights a scenario by raw repetition count.               |
| `DataMerger` maps by `long`                                 | Equal hashes are merged without checking vector bits                                             | A registry verifies all 28 raw IEEE-754 values for every repeated hash and rejects a collision.                                                                |
| `BenchmarkRunner` raw output                                | Alternating 28-weight and repetition-array lines, with scenario and run identity only in paths   | The v1 bundle stores a validated run row, a policy dictionary, and one status-bearing row per planned repetition.                                              |
| `BenchmarkRunner` timeout                                   | Breaks after the first liveness timeout, leaving unused entries in the pre-zeroed `means` array  | V1 has explicit `TIMEOUT` and `SKIPPED` rows. Neither is treated as a zero-throughput success.                                                                 |
| `BenchmarkRunner` throughput                                | Stores frames per nanosecond as a derived `double`                                               | V1 stores completed frames and elapsed nanoseconds and derives frames per second.                                                                              |
| `BenchmarkRunner.createSinks` and `BenchmarkFrame.generate` | Use an outer random ID hash and an unrecorded inner routing seed                                 | The v1 parameter model records both seeds for every source. Phase 3 must add/use a deterministic frame-generation overload before it emits native v1 evidence. |
| `BenchmarkOutputReader` and `BenchmarkOutputWriter`         | Headerless signed decimal encodings of `Double.doubleToLongBits`                                 | They remain only for current vector and pooled-data compatibility. New evidence uses a strict UTF-8 CSV bundle with headers and raw-bit vector columns.        |
| `PolicyRanking`                                             | Higher rounded P50, then lower rounded IQR, then lower rounded tail range                        | It remains temporarily for the current ordinal predictor. `PolicyComparator` is separate and authoritative for v1 summaries.                                   |
| `Distribution` and `VectorGrouper`                          | Duplicate the rounded P50/IQR/tail ordering                                                      | They remain untouched in Phase 1 and are not used by the v1 merger.                                                                                            |
| `SequenceFinder.loadTrainingData`                           | Requires alternating 28-value and five-quantile rows                                             | Phase 1 does not emit a misleading adapter row. Phase 2 consumes `scenario-results.csv` directly.                                                              |
| `CmaEsOptimizer.MeasuredPolicy`                             | Holds only a vector and five pooled quantiles                                                    | It remains untouched until Phase 3 supplies robust seeds and predicted curves.                                                                                 |
| `PolicyOrdinalNetwork.save/load`                            | Serializes a DJL directory named `euhedral-policy-ranker` with the old 28-input pooled objective | It remains untouched in Phase 1 and is never treated as observation data or migrated into the new contract. Phase 2 versions its replacement.                  |
| `ClosedLoopRunner`                                          | Calls `mergeQuantiles`, names raw files by source count, and infers iteration from directories   | It continues using the old path until Phase 3 switches the whole loop to v1. No v1 code may infer identity from these names.                                   |
| `ClosedLoopRunner.writeState`                               | Uses timestamped `Properties.store` state and absolute artifact paths                            | It is checkpoint state, not evidence serialization. Phase 3 replaces/version-controls scheduler state; Phase 1 does not parse it.                              |
| `Runner` and the training README                            | Expose the pooled `merge-quantiles` command and current system properties                        | Phase 1 adds Java APIs only. Phase 5 owns final CLI/configuration names and documentation after all consumers exist.                                           |
| `HasherApi.getHash(double[])`                               | xxHash64 over raw IEEE-754 lanes with seed `0x9e3779b97f4a7c15L`                                 | This exact implementation becomes policy identity scheme `p1`; no re-normalization or decimal reparse occurs before hashing.                                   |
| `BenchmarkRunner.configuredSourceCounts`                    | Uses `SystemInfo.getCoreCount()` and clamps configured counts to available cores                 | V1 records the actual selected source count and the same visible physical core count. The identity model itself permits a ratio above one.                     |

`euhedral-training` is not a named Java module and has no `module-info.java`. No module descriptor
change is required. Its existing Maven dependencies are sufficient; Phase 1 must not add a JSON,
CSV, statistics, or serialization dependency.

The current training test tree contains only `PolicyRankingTest`, `CmaEsOptimizerTest`, and
`ScoreBandSamplerTest`; there is no merger, raw codec, benchmark-output, closed-loop, or model
serialization coverage. Phase 1 keeps those three tests unchanged and adds the focused v1 surface
listed below.

## Settled v1 identity and validation

### Policy identity

`PolicyVector` is the only owner of a policy's weights.

- A policy has exactly 28 finite `double` weights.
- Construction defensively copies the array. Access is through `weight(int)` or
  `copyWeights()`; no internal array is exposed.
- The constructor does not normalize, round, canonicalize signed zero, or change coordinates.
  `+0.0` and `-0.0` therefore remain different bitwise policies, matching current
  `HasherApi.getHash(double[])` behavior.
- Non-finite weights are rejected. This also removes the only difference between
  `doubleToRawLongBits` and the current writer's `doubleToLongBits`.
- `PolicyId` scheme `p1` is
  `HasherApi.getHash(weights)` with the repository's existing base seed and raw lane layout.
- The canonical text is `p1-` followed by exactly 16 lowercase hexadecimal digits representing the
  unsigned 64-bit result, including leading zeroes.
- `PolicyId.compareTo` uses `Long.compareUnsigned`.
- A repeated `PolicyId` must have all 28 `doubleToRawLongBits` values equal. Different bits with the
  same hash are a fatal `PolicyHashCollisionException`; the merger never chooses one.

The policy ID is stable across runs and files. The observation schema version does not change this
hash. A future policy hash algorithm must use a new prefix rather than reinterpret `p1`.

### Exact scenario identity

`SourceScenario` contains:

```text
environmentId
sourceCount
availablePhysicalCoreCount
SourceRatio(numerator, denominator)
```

- `sourceCount` and `availablePhysicalCoreCount` are positive integers.
- `SourceRatio.of(sourceCount, availablePhysicalCoreCount)` divides both values by their greatest
  common divisor. The constructor rejects a ratio that is not reduced or does not equal the absolute
  counts.
- The identity model permits `sourceCount > availablePhysicalCoreCount`, although the current runner
  clamps it. This avoids encoding a current scheduling limitation into stored evidence.
- `EnvironmentId` is an explicitly supplied, stable machine/execution-environment label. It must
  match `[a-z0-9][a-z0-9._-]{0,63}`. It is not inferred from a hostname or path.
- Operators must change `EnvironmentId` when performance-relevant hardware, firmware, container or
  VM allocation, affinity exposure, OS/native runtime, or JVM configuration changes materially. The
  commit SHA and benchmark parameters are stored separately and do not become hidden parts of this
  ID.
- Canonical scenario text is
  `s1-<environmentId>-src<sourceCount>-core<availablePhysicalCoreCount>-r<numerator>of<denominator>`.
- Natural scenario ordering is environment ID, available core count, source count, then the reduced
  numerator and denominator.

Equal reduced ratios with different absolute counts or environment IDs are separate scenarios. Phase
2 may use the ratio as a portable feature, but Phase 1 never pools those rows.

### Run, cohort, and repetition identity

`benchmarkRunId` and `candidateCohortId` are opaque lower-case IDs matching
`[a-z0-9][a-z0-9._-]{0,95}`. A benchmark run covers exactly one `SourceScenario` and one cohort.

The immutable observation primary key is:

```text
ObservationKey(
    benchmarkRunId,
    sourceScenario,
    policyId,
    repetitionNumber
)
```

- `expectedRepetitions` lies in `[1, 999999]`; `repetitionNumber` is one-based and lies in
  `[1, expectedRepetitions]`.
- Canonical text is
  `ob1/<benchmarkRunId>/<scenario-canonical>/<policy-canonical>/rep-<six-digit-number>`.
- A complete run bundle contains exactly one row for every registered policy and every planned
  repetition. There are no gaps.
- Any duplicate `ObservationKey`, even with identical payload, is fatal. This detects copied runs
  and ambiguous evidence rather than silently double-weighting them.
- The same `benchmarkRunId` may not describe two contexts. A repeated run ID with a different
  iteration, cohort, scenario, commit, origin, timestamps, or parameters is fatal.
- `closedLoopIteration` is non-negative. Zero is reserved for a seed/bootstrap run; ordinary
  closed-loop iterations start at one.

The key does not rely on a filename or directory. Run IDs must be globally unique within a corpus;
Phase 3 will generate and checkpoint them.

### Provenance

`EvidenceOrigin` has only `NATIVE` and `IMPORTED`.

- Native observations require a 40- or 64-character lowercase hexadecimal Git commit SHA.
- The run context also records `dirtyWorkingTree` as a boolean; it does not modify identity.
- `IMPORTED` is a general provenance value needed by reports and packaging. It contains no legacy
  path or format knowledge.
- Imported evidence is mathematically usable only after it satisfies every v1 invariant. It is not
  eligible to bootstrap fixed anchors or reference runs unless the explicit
  `allowImportedBootstrap` option is true.
- The later importer may write v1 bundles, but the v1 reader and merger have no legacy-layout branch
  and no schema sniffing.

## V1 observation bundle

A completed benchmark run is one directory named by the caller. Its name is not semantic:

```text
<caller-selected-directory>/
+-- run.csv
+-- policies.csv
+-- observations.csv
+-- COMPLETE
```

The three files are UTF-8 RFC 4180 CSV with an exact header, `\n` line endings, and no byte-order
mark. The writer quotes a field only when RFC 4180 requires it. IDs are already restricted to safe
ASCII. Every file carries `schema_version=1`; readers accept exactly version 1 and reject missing,
mixed, lower, or higher versions. There is no version registry and no legacy auto-detection.

`COMPLETE` is an empty file written last. An explicitly supplied incomplete bundle is rejected.
Phase 3 may retain incomplete directories for audit, but it must not merge or promote them.

The run-level constants and policy dictionary are sidecars by design: repeating 28 weights, source
seeds, and unchanged run metadata in every repetition row would multiply raw storage and allocation
pressure. The join is still exact: a repetition references one declared policy ID, the reader
recomputes its vector hash, and every row inherits one validated run context.

### `run.csv`

This file has exactly one data row and this exact column order:

```text
schema_version,benchmark_run_id,closed_loop_iteration,candidate_cohort_id,scenario_id,environment_id,source_count,available_physical_core_count,source_ratio_numerator,source_ratio_denominator,commit_sha,dirty_working_tree,evidence_origin,started_at,completed_at,expected_repetitions,sample_duration_nanos,liveness_timeout_nanos,frames_per_source,reset_timeout_nanos,ordered_frames,cpu_set_hex,frame_source_seeds
```

Rules:

- `scenario_id` is recomputed from the following scenario columns and must match.
- Timestamps are UTC `Instant.toString()` values. `completed_at` is not before `started_at`.
- All duration/count parameter values are positive.
- `expected_repetitions` is the exact cardinality required for every policy.
- The four benchmark parameters correspond to the current
  `benchmark.sampleMillis`, `benchmark.livenessMillis`, `benchmark.framesPerSource`, and
  `benchmark.resetTimeoutMillis`. They are converted to nanoseconds once in the producer.
- `ordered_frames` records the `BenchmarkFrame.generate` routing mode.
- `cpu_set_hex` is the exact CPU set supplied to `LatticeConfig`, encoded by
  `SystemInfo.toHexMask`.
- `frame_source_seeds` has exactly `sourceCount` semicolon-separated entries in source-index order.
  Each entry is
  `<source-index>:<16-hex-id-hash>:<16-hex-routing-seed>`. Phase 3 must make the currently hidden
  routing seed injectable before it can write native v1 evidence.
- A future benchmark parameter that changes measurement meaning requires a v2 schema unless it can
  be added as an explicitly versioned sidecar field without making v1 ambiguous.

### `policies.csv`

This file is sorted by one-based `schedule_position` and has this exact column order:

```text
schema_version,schedule_position,policy_id,roles,weight_00_bits,...,weight_27_bits
```

- `schedule_position` is one-based, contiguous, unique within the run, and preserves the
  deterministic trial order selected by the scheduler. It is not part of stable policy identity.
- Each weight is exactly 16 lowercase hex digits from `doubleToRawLongBits`.
- `roles` is a semicolon-separated, lexicographically sorted set of enum names.
- Phase 1 defines `FIXED_ANCHOR`, `LEADER_REVALIDATION`, `CARRY_FORWARD`, `EXPLORATION`, and
  `DISAGREEMENT_AUDIT`. Multiple roles are allowed. Phase 1 calibration uses only
  `FIXED_ANCHOR`; rolling leaders never set the run scale.
- A policy appears once. Empty roles are rejected.

### `observations.csv`

This file is sorted by policy `schedule_position`, then `repetition_number`, and has this exact
column order:

```text
schema_version,observation_id,policy_id,repetition_number,status,measurement_encoding,started_at,ended_at,elapsed_nanos,completed_frames,throughput_frames_per_second,failure_code
```

`ObservationStatus` is `SUCCESS`, `TIMEOUT`, `FAILED`, or `SKIPPED`.
`MeasurementEncoding` is `COUNTER_DERIVED` or `DIRECT_THROUGHPUT`.

- `observation_id` is recomputed from the run context, policy, and repetition and must match.
- A non-empty `failure_code` matches `[A-Z][A-Z0-9_]{0,63}`. Raw exception messages and stack traces
  do not enter deterministic evidence files.
- `started_at` and `ended_at` are UTC instants and both lie within the run start/completion
  interval.
- Native evidence must use `COUNTER_DERIVED`. Its elapsed time equals the timestamp difference
  exactly at nanosecond precision and its completed frame count is non-negative.
- For a native `SUCCESS`, elapsed time and completed frames are positive, `failure_code` is empty,
  and throughput is finite, positive, and exactly the result of
  `(completedFrames * 1_000_000_000.0) / elapsedNanos` under Java `double` arithmetic.
- For a native `TIMEOUT` or `FAILED`, elapsed time and completed frames may be zero or positive, the
  partial throughput is stored when elapsed time is positive, and `failure_code` is non-empty.
- `SKIPPED` represents a planned repetition not run after an earlier terminal result for that
  policy. It has equal timestamps, zero elapsed time, zero frames, an empty throughput field, and a
  non-empty failure code such as `PREVIOUS_TIMEOUT`.
- `DIRECT_THROUGHPUT` is accepted only with `EvidenceOrigin.IMPORTED`. A successful direct row has a
  finite positive authoritative throughput and may leave elapsed time and completed frames empty
  when the current workspace did not retain counters. Its run-level configured sample duration and
  audit timestamps are still required and must be established without guessing. Direct rows never
  pretend to have reconstructed counters.
- Timeout, failed, skipped, and partial measurements are diagnostic only. None enters a throughput
  quantile or anchor residual.
- `Double.toString` is used for throughput. Readers recompute and bit-compare counter-derived
  throughput; direct throughput is parsed exactly and is not checked against absent counters. No
  path uses a tolerance.

The streaming bundle writer requires policies in ascending schedule position and observations in
schedule-position/repetition order; it rejects out-of-order calls instead of retaining a whole run
to sort it. The bundle writer writes to caller-owned new files, refuses to overwrite a completed
bundle, forces the three CSV files, writes the final `run.csv` with its completion time, and writes
`COMPLETE` last. Atomic final-package publication remains Phase 4.

## Fixed-anchor and reference-run contract

### Anchor count

`AnchorSelectionConfig.defaults()` is:

```text
fixedFraction                    = 0.02
minimumFixedAnchors              = 5
maximumBootstrapNonSuccessRate   = 0.10
maximumBootstrapRelativeIqr      = 0.25
allowImportedBootstrap           = false
```

For an iteration policy budget `B`, including anchors:

```text
target = max(config.minimumFixedAnchors,
             ceil(config.fixedFraction * B))
```

`B` must be greater than `target`. The five-anchor minimum intentionally overrides the 1-3 percent
guidance for small smoke-test budgets. Phase 3 must reserve exactly the already frozen catalog size,
not recalculate it when a later iteration changes budget.

### Bootstrap selection

Anchor and reference selection occurs once for a training run:

1. Aggregate complete v1 evidence within each run using the run rules below, but do not calibrate
   it. Use native runs only unless `allowImportedBootstrap` is true.
2. For each required exact scenario, use the explicit reference override when supplied. Otherwise
   choose the earliest complete native run with at least `target` valid policy aggregates. Compare
   `run.startedAt`, then `benchmarkRunId`.
3. Intersect valid policy IDs across the chosen runs.
4. Retain a policy only when, in every chosen run:
    - it has at least three successful repetitions;
    - its success fraction is at least 0.5;
    - its total non-success rate is at most 0.10;
    - its raw median throughput is finite and positive; and
    - `(P75 - P25) / median` is at most 0.25.
5. If fewer than `target` policies remain, fail with the chosen run IDs, intersection count, and
   rejection counts. Do not search later runs or silently shrink the anchor set. The caller may
   provide reference overrides or run a dedicated bootstrap cohort.
6. Within each chosen reference run, assign provisional midrank scenario qualities using the exact
   quality convention below. Compare the common policies with the same robust tuple used for final
   ranking.
7. Sort the common policies worst-first by that tuple, with `PolicyId` as the final tie-break.
   Divide the ordered list into `target` equal strata and select index
   `floor((i + 0.5) * N / target)` for `i` in `[0, target)`.
8. Store the selected policies sorted by `PolicyId`. The chosen run for each scenario is the
   immutable reference run.

This stratifies reliable anchors across observed quality without selecting a changing candidate
cohort or only the current leaders. The selection is deterministic and includes neither imported
evidence nor a path-derived legacy record by default.

`anchorSetId` is `a1-<16 lowercase hex digits>`. Hash the UTF-8 bytes of:

```text
fixed-anchor-set-v1
<policy-id-1>
...
<policy-id-k>
```

using `HasherApi.getHash(byte[])`, with policy IDs in unsigned order and one LF after every shown
line, including the last.

The anchor catalog never mutates inside a training run. If an anchor later becomes unstable, that
run loses calibration confidence; the merger does not replace the anchor and thereby move the scale.
A new top-level training run may deliberately create a new catalog.

### Reference runs

- There is exactly one frozen reference run per exact required scenario.
- A reference must contain at least five valid fixed anchors.
- A reference has `deltaLog=0`, `scaleFactor=1`, residual `0`, and status `REFERENCE`.
- A run is calibrated only against its own scenario's reference. There is no ratio-only,
  cross-environment, or transitive run-to-run calibration.
- When a new required scenario is deliberately added after catalog creation, choose its reference as
  the earliest complete native run containing at least five valid fixed anchors from the frozen
  anchor catalog. Persist the addition before merging. Do not reselect existing references.
- `fixed-anchors.csv` and `reference-runs.csv` are durable inputs to later merges, not values
  recomputed whenever the corpus grows.

Rolling robust leaders may be remeasured for drift and confidence reporting in Phase 3, but are not
calibration anchors and never enter `deltaLog` unless they are independently members of the frozen
fixed set.

## Calibration mathematics

All calibration is multiplicative and uses natural logarithms.

### Qualifying anchor estimate

For each fixed anchor in a run, start with only `SUCCESS` observations.

- In a non-reference run, the policy must also carry the `FIXED_ANCHOR` role. The bootstrap
  reference runs are exempt because the catalog did not exist when they were measured.
- At least three successes and a success fraction of at least 0.5 are required.
- Let `y` be the type-7 median of successful throughput.
- Let `logIqr` be type-7 P75 minus type-7 P25 after applying `StrictMath.log` to every successful
  throughput. Do not approximate it as `log(rawP75) - log(rawP25)`.
- Let:

```text
successFraction = successfulRepetitions / plannedRepetitions
effectiveN       = successfulRepetitions * successFraction
robustSigma      = max(logIqr / 1.3489795003921634, 0.01)
medianSE         = 1.2533141373155001 * robustSigma / sqrt(effectiveN)
```

The success fraction penalizes timeout, failed, and skipped repetitions without pretending that they
were zero-throughput samples. The 0.01 log-sigma floor prevents a few exactly repeated values from
receiving infinite weight.

For anchor `a` shared by run `r` and reference `ref`:

```text
d_a       = StrictMath.log(y_r,a / y_ref,a)
rawWeight = 1 / (medianSE_r,a^2 + medianSE_ref,a^2)
```

Non-finite values are rejected from the shared set and reported.

The log ratio is the required floating-point evaluation order. It is algebraically identical to
subtracting the two logs, but produces the exact deterministic fixture values
`deltaLog == StrictMath.log(2)`, `scaleFactor == 2`, and `1000 / scaleFactor == 500` for a global
two-times run. Do not rewrite it as two separate `StrictMath.log` calls: that changes the result by
one ulp for the settled fixture. A non-finite ratio is rejected by the existing non-finite
calibration rule.

### Anchor weight cap

Normalize anchor weights with a deterministic water-filling cap:

```text
maximumShare = max(0.25, 1.0 / sharedAnchorCount)
```

Repeatedly scale all uncapped raw weights into the remaining unit mass. Fix any weight exceeding
`maximumShare` at that share, remove it from the active set, and repeat. Process simultaneous caps
by `PolicyId`. Scale the remaining active weights proportionally and make the final weight absorb
only the last floating-point summation remainder; choose the greatest `PolicyId` with remaining
headroom. No anchor may exceed the cap by more than one ulp.

This cap, rather than an arbitrary repeat-count ceiling, prevents a precise high-repeat anchor from
moving a whole run.

### Weighted median and residual

`weightedMedian` is the lower inverse-CDF convention:

1. sort by value, then `PolicyId`;
2. sum capped weights in that order with Neumaier compensated summation; and
3. return the first value whose cumulative weight is greater than or equal to half the total.

There is no interpolation and no approximate-equality test at half mass.

```text
deltaLog = weightedMedian(d_a)
scaleFactor = StrictMath.exp(deltaLog)
calibratedY = StrictMath.exp(StrictMath.log(rawY) - deltaLog)
residual_a = abs(d_a - deltaLog)
medianAbsoluteResidual = weightedMedian(residual_a, same capped weights)
```

Scaling P25, median, P75, and IQR by `scaleFactor` is equivalent and avoids recalculating raw
quantiles. A non-finite or non-positive scale/result is a fatal validation error, not a clamped
value.

### Confidence thresholds

`CalibrationStatus` is `REFERENCE`, `CALIBRATED`, `WEAKLY_CALIBRATED`, or `UNCALIBRATED`. Threshold
comparisons are inclusive.

| Status              | Exact rule                                                                              |
|---------------------|-----------------------------------------------------------------------------------------|
| `REFERENCE`         | The frozen reference run.                                                               |
| `CALIBRATED`        | At least 5 shared valid anchors and median absolute log residual `<= 0.05`.             |
| `WEAKLY_CALIBRATED` | At least 3 shared valid anchors and residual `<= 0.15`, but the strong rule is not met. |
| `UNCALIBRATED`      | Fewer than 3 shared valid anchors, residual `> 0.15`, or no finite scale estimate.      |

For orientation, the strong residual bound is approximately a 5.13 percent multiplicative error and
the weak bound approximately 16.18 percent. The stored and compared values are exactly `0.05`
and `0.15`; they are not `log(1.05)` and `log(1.15)`.

Default ranking accepts `REFERENCE` and `CALIBRATED` only.
`CalibrationAcceptance.INCLUDE_WEAK` is an explicit API override that additionally accepts
`WEAKLY_CALIBRATED` and marks resulting scenario rows `VALID_WEAK_OVERRIDE`. `UNCALIBRATED` is never
accepted because it has no trustworthy scale. Every override is recorded in output metadata and
cannot be enabled by silently lowering thresholds.

An excessive-residual run may report its finite candidate delta and scale for diagnosis while
remaining `UNCALIBRATED`; aggregation must not apply them. A run with fewer than three qualifying
anchors leaves delta, scale, and residual blank.

The calibration report contains the reference run, fixed-anchor count, qualifying shared count,
delta, scale, weighted median absolute residual, status, and a stable reason code for every run.

An observed non-required scenario may not yet have a frozen reference entry. Its runs are retained
with empty `referenceRunId`, blank calibration numerics, status `UNCALIBRATED`, and reason
`MISSING_SCENARIO_REFERENCE`. This is not an implicit reference-selection path: the scenario rows
remain auditable but rejected, and the scenario can become calibratable only after the deliberate
new-scenario reference procedure persists a catalog entry.

The exact defaults are:

```text
CalibrationConfig:
  minimumStrongAnchors       = 5
  minimumWeakAnchors         = 3
  maximumStrongResidual      = 0.05
  maximumWeakResidual        = 0.15
  minimumLogSigma            = 0.01
  maximumAnchorWeightShare   = 0.25

AggregationConfig:
  minimumSuccessfulRepetitions = 3
  minimumSuccessFraction        = 0.5
  bootstrapReplicates           = 1000
  bootstrapSeed                 = 0x6a09e667f3bcc909L
  calibrationAcceptance         = STRONG_ONLY
```

## Hierarchical aggregation

### Stage 1: within one run

Group by `(PolicyId, benchmarkRunId, SourceScenario)`. Bundle validation guarantees the run and
scenario parts are consistent.

For planned count `m`:

```text
successRate    = SUCCESS / m
timeoutRate    = TIMEOUT / m
failureRate    = (FAILED + SKIPPED) / m
nonSuccessRate = (TIMEOUT + FAILED + SKIPPED) / m
```

A run-level policy estimate is valid when it has at least three successes, success rate at least
0.5, and a finite positive raw median. Its successful measurements produce exact type-7 P25, median,
P75, and IQR. Failed categories never enter these quantiles.

After run calibration, divide the four throughput statistics by the scale factor. Retain raw
statistics, calibrated statistics, counts, rates, log IQR, calibration status, and invalid reason.

### Stage 2: within one exact scenario

For `(PolicyId, SourceScenario)`:

- Consider each valid accepted run's calibrated median once. Repetition count does not change its
  vote.
- The point estimate is the type-7 median of run medians.
- Scenario P25, P75, and IQR are type-7 statistics of run medians.
- `medianWithinRunRelativeIqr` is the type-7 median of
  `(runP75 - runP25) / runMedian`.
- Timeout, failure, and non-success rates are Neumaier-compensated arithmetic means of the
  corresponding run rates. Each run has one vote.
- Runs rejected for calibration remain counted by status in the output but do not enter the point
  estimate or quality population.

Uncertainty is a deterministic 95 percent bootstrap interval of the median:

- resample accepted run medians, not individual repetitions;
- draw `runCount` values with replacement for each replicate;
- use 1,000 replicates by default;
- use `java.util.Random`;
- seed it with `mergeBootstrapSeed ^ policyId.value() ^
  HasherApi.getHash(scenario.canonical(), mergeBootstrapSeed)`;
- sort run IDs before building the source array;
- calculate each replicate median and take type-7 P2.5 and P97.5 of the replicate medians; and
- for one run, set both interval ends to the point estimate without consuming random values.

The default `mergeBootstrapSeed` is `0x6a09e667f3bcc909L`.

Emit a scenario row for the Cartesian product of every known policy and every required scenario,
plus any observed non-required scenario. A missing or rejected row has blank numeric estimates and
an explicit status; it is never dropped.

### Stage 3: across required scenarios

Only required scenarios enter a policy's robust summary. Extra observed scenarios survive in
`scenario-results.csv` but do not affect coverage or rank.

Coverage counts a scenario only when it has a finite quality from an accepted scenario estimate. An
eligible policy has valid quality for every required exact scenario. There is no minimum raw row
count shortcut and no ratio-only substitution.

`observedRequiredScenarioCount` counts required scenarios with at least one run for the policy, even
when all such runs are invalid or uncalibrated. `validRequiredScenarioCount` counts finite accepted
qualities. `coverageFraction` is
`validRequiredScenarioCount / (double) requiredScenarioCount`; the required set must be non-empty. A
required scenario with no run is missing; one with runs but no accepted estimate is rejected.

## Exact quantile, quality, and epsilon conventions

### Type-7 quantile

All unweighted P2.5, P25, P50, P75, and P97.5 calculations use this exact type-7 definition over a
defensive, ascending copy:

```text
n = values.length
if n == 0: no value
if n == 1: values[0]
h = (n - 1) * p
j = floor(h)
g = h - j
Q(p) = values[j] + g * (values[min(j + 1, n - 1)] - values[j])
```

Inputs must be finite. Derived zero is canonicalized to `+0.0`. Do not use `TDigest`,
`Percentile`, nearest-rank quantiles, or `CommonFunctions.round`.

### Scenario-relative midrank quality

For each exact scenario, sort valid accepted point estimates ascending. Throughput ties use exact
`Double.compare`; uncertainty overlap and rounded display values do not create ties.

For a tie group occupying zero-based positions `lo` through `hi` among `N` policies:

```text
if N == 1: q = 0.5
otherwise: q = (lo + hi) / (2.0 * (N - 1))
```

The unique worst value is 0, the unique best is 1, and an all-tied population is 0.5. Every member
of a tie receives the same midrank. Incomplete policies may participate in a scenario's percentile
population when that scenario row is valid; they do not need full cross-scenario coverage first.

The primary `q` is retained even when bootstrap intervals overlap. The scenario throughput interval,
run count, relative IQR, and non-success rates carry the uncertainty; no top-N flag replaces `q`.

### Robust summary metrics

For an eligible policy with qualities `q_s` over required scenarios:

```text
worstQuality          = min(q_s)
qualityP25            = type7(q_s, 0.25)
geometricMeanQuality  = StrictMath.exp(
                            compensatedMean(
                                StrictMath.log(max(q_s, 1.0e-12))))
qualityMedian         = type7(q_s, 0.50)
crossScenarioMad      = type7(abs(q_s - qualityMedian), 0.50)
medianRelativeIqr     = type7(scenario.medianWithinRunRelativeIqr, 0.50)
meanNonSuccessRate    = compensated arithmetic mean across scenarios
meanTimeoutRate       = compensated arithmetic mean across scenarios
```

`QUALITY_EPSILON` is exactly `1.0e-12` and is used only inside the geometric mean logarithm. It is
not a tie tolerance, comparator tolerance, quantile adjustment, or substitute quality. A true zero
remains zero in `worstQuality` and `qualityP25`.

### Authoritative comparator

`PolicyComparator.BEST_FIRST` accepts eligible summaries only and compares:

1. higher `worstQuality`;
2. higher `qualityP25`;
3. higher `geometricMeanQuality`;
4. lower `crossScenarioMad`;
5. lower `medianRelativeIqr`;
6. lower `meanNonSuccessRate`; then
7. unsigned `PolicyId` ascending as the deterministic identity tie-break.

Step 5 and step 6 are the two ordered components of the plan's measurement-stability tier.
`meanTimeoutRate` is reported separately; timeout is already included in non-success and is not
compared a second time.

Every metric comparison uses exact `Double.compare`. Eligible summaries cannot contain NaN or
infinity. The comparator returns a negative value when its first argument is better, matching
ordinary best-first Java sorting.

There is no authoritative comparison between complete and incomplete summaries. Published output
places all eligible policies first under `BEST_FIRST`. It then places incomplete policies by valid
required-scenario count descending, observed required-scenario count descending, and `PolicyId`.
Their `published_rank` is blank. Phase 3 will define the separate predicted incomplete-policy
priority; an incomplete policy never receives a robust-leader rank.

## Exact Java API and ownership

All new code lives in `euhedral-training`; no lower-level module gains a training dependency.

### Immutable data types

Under `io.euhedral_execution.training.data`:

```java
public record PolicyId(long value) implements Comparable<PolicyId> {
    public static PolicyId fromWeights(double[] weights);
    public static PolicyId parse(String text);
    public String canonical();
}

public final class PolicyVector {
    public static final int WIDTH = 28;
    public static PolicyVector of(double[] weights);
    public PolicyId id();
    public double weight(int index);
    public double[] copyWeights();
    public boolean bitwiseEquals(PolicyVector other);
}

public record SourceRatio(int numerator, int denominator) {
    public static SourceRatio of(int sourceCount, int coreCount);
    public double asDouble();
}

public record SourceScenario(
        String environmentId,
        int sourceCount,
        int availablePhysicalCoreCount,
        SourceRatio ratio) implements Comparable<SourceScenario> {
    public static SourceScenario of(String environmentId, int sourceCount, int coreCount);
    public String canonical();
}

public enum EvidenceOrigin { NATIVE, IMPORTED }
public enum ObservationStatus { SUCCESS, TIMEOUT, FAILED, SKIPPED }
public enum MeasurementEncoding { COUNTER_DERIVED, DIRECT_THROUGHPUT }
public enum PolicyRole {
    FIXED_ANCHOR,
    LEADER_REVALIDATION,
    CARRY_FORWARD,
    EXPLORATION,
    DISAGREEMENT_AUDIT
}

public record FrameSourceSeed(
        int sourceIndex,
        long idHash,
        long routingSeed) {
}

public record BenchmarkParameters(
        int expectedRepetitions,
        long sampleDurationNanos,
        long livenessTimeoutNanos,
        int framesPerSource,
        long resetTimeoutNanos,
        boolean orderedFrames,
        String cpuSetHex,
        List<FrameSourceSeed> frameSourceSeeds) {
}

public record BenchmarkRunDescriptor(
        int schemaVersion,
        String benchmarkRunId,
        int closedLoopIteration,
        String candidateCohortId,
        SourceScenario scenario,
        String commitSha,
        boolean dirtyWorkingTree,
        EvidenceOrigin evidenceOrigin,
        Instant startedAt,
        BenchmarkParameters parameters) {
}

public record BenchmarkRunContext(
        BenchmarkRunDescriptor descriptor,
        Instant completedAt) {
}

public record ScheduledPolicy(
        int schedulePosition,
        PolicyVector policy,
        Set<PolicyRole> roles) {
}

public record ObservationKey(
        String benchmarkRunId,
        SourceScenario scenario,
        PolicyId policyId,
        int repetitionNumber) implements Comparable<ObservationKey> {
    public String canonical();
}

public record BenchmarkObservation(
        ObservationKey key,
        BenchmarkRunDescriptor run,
        ScheduledPolicy scheduledPolicy,
        ObservationStatus status,
        MeasurementEncoding measurementEncoding,
        Instant startedAt,
        Instant endedAt,
        OptionalLong elapsedNanos,
        OptionalLong completedFrames,
        OptionalDouble throughputFramesPerSecond,
        String failureCode) {
}
```

All record compact constructors enforce the settled invariants and use `Set.copyOf`. Empty failure
codes are represented by `""`, not `null`. `BenchmarkObservation` validates that nested identities
agree. `PolicyVector` implements bitwise `equals` and `hashCode` consistently; it does not use array
reference equality. `BenchmarkRunContext` additionally verifies that completion is not before its
descriptor's start.

`PolicyRegistry` owns the one canonical `PolicyVector` object per `PolicyId` during a merge:

```java
public final class PolicyRegistry {
    public PolicyVector register(PolicyVector policy);
    public PolicyVector require(PolicyId id);
    public Collection<PolicyVector> policiesInIdOrder();
}
```

### Codec

Under `io.euhedral_execution.training.data.io`:

```java
public final class ObservationBundleWriter implements AutoCloseable {
    public static ObservationBundleWriter open(Path directory, BenchmarkRunDescriptor run);
    public void registerPolicy(ScheduledPolicy policy);
    public void write(BenchmarkObservation observation);
    public BenchmarkRunContext complete(Instant completedAt);
}

public final class ObservationBundleReader {
    public static ObservationBundle read(Path directory);
    public static void stream(Path directory, ObservationVisitor visitor);

    public interface ObservationVisitor {
        void onStart(BenchmarkRunContext run, List<ScheduledPolicy> policies);
        void onObservation(BenchmarkObservation observation);
    }
}

public record ObservationBundle(
        BenchmarkRunContext run,
        List<ScheduledPolicy> policies,
        List<BenchmarkObservation> observations) {
}
```

The public bundle object is appropriate for fixtures and a single benchmark run. `DataMerger`
must use the reader's streaming visitor so it can discard observation objects after updating a
compact accumulator. Do not retain all corpus observations in memory. The visitor is public so Phase
3 can validate native bundles incrementally without adding a second codec; its callbacks are
synchronous, single-owner, and valid only for the duration of the call.

The public writer validates ascending schedule positions and observation order as it streams. The
public reader preserves that stored schedule order. Deterministic merger output never depends on
schedule order: aggregation keys are sorted independently after the run has been reduced. Every
policy must be registered before the first observation. Closing without `complete` closes/forces
open files but deliberately leaves no `run.csv` or `COMPLETE`, so the directory remains unmergeable.

`StrictCsv` is package-private and owns RFC 4180 parsing/writing, exact headers, LF output, raw-bit
hex conversion, and full-consumption checks. It is not a general CSV library.

### Merge records and configuration

Under `io.euhedral_execution.training.merge`:

```java
public enum CalibrationStatus {
    REFERENCE, CALIBRATED, WEAKLY_CALIBRATED, UNCALIBRATED
}

public enum CalibrationAcceptance {
    STRONG_ONLY, INCLUDE_WEAK
}

public record AnchorSelectionConfig(
        double fixedFraction,
        int minimumFixedAnchors,
        double maximumBootstrapNonSuccessRate,
        double maximumBootstrapRelativeIqr,
        boolean allowImportedBootstrap) {
    public static AnchorSelectionConfig defaults();
    public int targetCount(int policyBudget);
}

public record CalibrationConfig(
        int minimumStrongAnchors,
        int minimumWeakAnchors,
        double maximumStrongResidual,
        double maximumWeakResidual,
        double minimumLogSigma,
        double maximumAnchorWeightShare) {
    public static CalibrationConfig defaults();
}

public record AggregationConfig(
        int minimumSuccessfulRepetitions,
        double minimumSuccessFraction,
        int bootstrapReplicates,
        long bootstrapSeed,
        CalibrationAcceptance calibrationAcceptance) {
    public static AggregationConfig defaults();
}

public record AnchorCatalog(
        int schemaVersion,
        String anchorSetId,
        List<PolicyVector> fixedAnchors) {
    public static AnchorCatalog of(List<PolicyVector> fixedAnchors);
}

public record ReferenceRunCatalog(
        int schemaVersion,
        String anchorSetId,
        SortedMap<SourceScenario, String> referenceRunIds) {
}

public record CalibrationPlan(
        AnchorCatalog anchors,
        ReferenceRunCatalog references) {
}
```

`MergeRecords` is a non-instantiable namespace containing public immutable nested records:

```java
public enum RunAggregateStatus {
    VALID, INSUFFICIENT_SUCCESSES, LOW_SUCCESS_FRACTION, NONPOSITIVE_THROUGHPUT
}

public enum ScenarioResultStatus {
    MISSING,
    NO_VALID_RUN,
    NO_ACCEPTED_CALIBRATION,
    VALID_STRONG,
    VALID_WEAK_OVERRIDE
}

public record RunAggregate(
        PolicyVector policy,
        BenchmarkRunContext run,
        SortedSet<PolicyRole> roles,
        int plannedRepetitionCount,
        int successfulRepetitionCount,
        int timeoutCount,
        int failedCount,
        int skippedCount,
        double successRate,
        double timeoutRate,
        double failureRate,
        double nonSuccessRate,
        RunAggregateStatus status,
        OptionalDouble rawP25,
        OptionalDouble rawMedian,
        OptionalDouble rawP75,
        OptionalDouble rawIqr,
        OptionalDouble rawLogIqr) {
}

public record RunCalibration(
        BenchmarkRunContext run,
        String referenceRunId,
        String anchorSetId,
        int fixedAnchorCount,
        int sharedAnchorCount,
        OptionalDouble deltaLog,
        OptionalDouble scaleFactor,
        OptionalDouble weightedMedianAbsoluteResidual,
        CalibrationStatus status,
        String reason,
        SortedMap<PolicyId, Double> cappedAnchorWeights) {
}

public record ScenarioResult(
        SourceScenario scenario,
        PolicyVector policy,
        ScenarioResultStatus status,
        int totalRunCount,
        int acceptedRunCount,
        int weakRunCount,
        int uncalibratedRunCount,
        int successfulRepetitionCount,
        int plannedRepetitionCount,
        OptionalDouble throughputP25,
        OptionalDouble throughputMedian,
        OptionalDouble throughputP75,
        OptionalDouble throughputIqr,
        OptionalDouble medianWithinRunRelativeIqr,
        OptionalDouble meanTimeoutRate,
        OptionalDouble meanFailureRate,
        OptionalDouble meanNonSuccessRate,
        OptionalDouble bootstrapMedianCiLow,
        OptionalDouble bootstrapMedianCiHigh,
        OptionalDouble quality) {
}

public record RobustPolicySummary(
        PolicyVector policy,
        boolean eligible,
        int requiredScenarioCount,
        int observedRequiredScenarioCount,
        int validRequiredScenarioCount,
        double coverageFraction,
        OptionalDouble worstQuality,
        OptionalDouble qualityP25,
        OptionalDouble geometricMeanQuality,
        OptionalDouble crossScenarioQualityMad,
        OptionalDouble medianRelativeIqr,
        OptionalDouble meanNonSuccessRate,
        OptionalDouble meanTimeoutRate,
        SortedSet<SourceScenario> measuredScenarios,
        SortedSet<SourceScenario> missingScenarios,
        SortedSet<SourceScenario> rejectedScenarios) {
}

public record MergeResult(
        CalibrationPlan calibrationPlan,
        List<RunCalibration> calibrations,
        List<ScenarioResult> scenarioResults,
        List<RobustPolicySummary> robustSummaries) {
}
```

These declarations are nested in `MergeRecords`; the enums may be nested there as well. Collections
are sorted immutable copies. Missing numeric values are `OptionalDouble`; domain records never use
NaN as a missing sentinel. Scenario repetition counts and rates cover accepted runs only; total,
weak, and uncalibrated run counts make rejected evidence explicit.

`RunAggregate.roles` is the immutable per-run scheduling provenance copied from the bundle's
`ScheduledPolicy`. It is sorted lexicographically by enum name, must be non-empty, and is not
combined across runs. Keeping it on the Stage 1 aggregate is intentional: calibration can enforce
that a non-reference catalog member was actually scheduled as `FIXED_ANCHOR` without reopening raw
bundles or introducing a parallel metadata index. Future scheduling roles extend `PolicyRole` and
flow through this set without changing the aggregation or calibrator signature; only consumers whose
semantics depend on a new role need modification.

`AnchorCatalog.of` is the canonical construction path: it sorts and deduplicates policies, computes
the settled `a1` hash, and returns the validated record. The public record constructor recomputes
and validates the supplied ID so persisted or manually assembled plans cannot claim an unrelated
anchor-set identity.

### Statistical and pipeline services

```java
public record WeightedValue<K extends Comparable<? super K>>(
        double value,
        double weight,
        K tieBreaker) {
}

public final class VectorStatistics {
    public static double quantileType7(double[] values, double probability);
    public static double median(double[] values);
    public static double mad(double[] values);
    public static double compensatedMean(double[] values);
    public static double weightedMedian(
            List<WeightedValue<PolicyId>> values);
    public static double[] capAndNormalizeWeights(
            double[] rawWeights, double maximumShare);
}

public final class RunAggregator {
    public static List<RunAggregate> aggregate(
            List<Path> bundles,
            PolicyRegistry policies,
            AggregationConfig config);
}

public final class AnchorBootstrapper {
    public static CalibrationPlan bootstrap(
            List<RunAggregate> rawRunAggregates,
            SortedSet<SourceScenario> requiredScenarios,
            int policyBudget,
            Map<SourceScenario, String> referenceOverrides,
            AnchorSelectionConfig anchorConfig,
            AggregationConfig aggregationConfig);
}

public final class RunCalibrator {
    public static List<RunCalibration> calibrate(
            List<RunAggregate> rawRunAggregates,
            CalibrationPlan plan,
            CalibrationConfig config);
}

public final class HierarchicalAggregator {
    public static List<ScenarioResult> aggregateScenarios(
            Collection<PolicyVector> policies,
            List<RunAggregate> runs,
            List<RunCalibration> calibrations,
            SortedSet<SourceScenario> requiredScenarios,
            AggregationConfig config);
}

public final class ScenarioQualityRanker {
    public static List<ScenarioResult> assignQualities(
            List<ScenarioResult> scenarioResults);
    public static List<RobustPolicySummary> summarize(
            Collection<PolicyVector> policies,
            List<ScenarioResult> scenarioResults,
            SortedSet<SourceScenario> requiredScenarios);
}

public final class PolicyComparator {
    public static final Comparator<RobustPolicySummary> BEST_FIRST;
    public static final Comparator<RobustPolicySummary> PUBLISHED_ORDER;
}
```

`RunAggregator` checks global run and observation uniqueness while consuming bundle paths sorted by
absolute normalized path. It interns policies and retains compact successful-throughput arrays and
counts only until each run aggregate is complete. Callers pass raw weight arrays to
`capAndNormalizeWeights` in policy-ID order.

### `DataMerger` facade

Add v1 APIs without changing the existing signatures yet:

```java
public static CalibrationPlan bootstrapCalibrationV1(
        CalibrationBootstrapRequest request) throws Exception;

public static MergeArtifacts mergeV1(MergeRequest request) throws Exception;
```

The request records are nested public records in `DataMerger`:

```java
public record CalibrationBootstrapRequest(
        List<Path> observationBundles,
        SortedSet<SourceScenario> requiredScenarios,
        int policyBudget,
        Map<SourceScenario, String> referenceOverrides,
        Path planDirectory,
        AnchorSelectionConfig anchorSelection,
        AggregationConfig aggregation) {
}

public record MergeRequest(
        List<Path> observationBundles,
        SortedSet<SourceScenario> requiredScenarios,
        CalibrationPlan calibrationPlan,
        Path outputDirectory,
        CalibrationConfig calibration,
        AggregationConfig aggregation) {
}

public record MergeArtifacts(
        Path fixedAnchors,
        Path referenceRuns,
        Path calibrationReport,
        Path scenarioResults,
        Path robustRanking,
        Path coverageReport,
        Path robustLeaderVectors,
        Path incompleteVectors) {
}
```

Bootstrap writes only `fixed-anchors.csv` and `reference-runs.csv` into a new `planDirectory` and
returns the frozen plan. Merge requires that plan, writes a copy of both plan files into a separate
new `outputDirectory`, and never silently bootstraps or changes references. Both operations refuse
to overwrite their target directories. Bootstrap uses the same validated temporary-sibling directory
publication rule as merge.

The existing `mergeQuentiles()`, `mergeQuantiles(...)`, and their P99 implementation remain a
temporary compile seam because `ClosedLoopRunner` still calls them. Mark the two quantile entry
points deprecated with the searchable comment `ROBUST_OPTIMIZER_POOLED_V0_REMOVAL`, but do not route
v1 inputs through them and do not add new callers. `mergeVectors`, current bit-text codecs, and
current ranking classes also remain until their owning later phases migrate them.

## V1 merger outputs

All outputs are UTF-8 RFC 4180 CSV with LF line endings, exact headers, deterministic rows,
`schema_version=1`, no generation timestamp, and `Double.toString` for derived values. Missing
optional values are empty fields. The merger creates a unique temporary sibling directory, writes
and re-reads all eight files there, then moves the directory to the nonexistent final
`outputDirectory`. It refuses to overwrite. If `ATOMIC_MOVE` is unsupported, use a same-filesystem
directory move only after validation; a partially written temporary directory is never a final merge
output. Final-package atomicity and cleanup policy remain Phase 4.

### `fixed-anchors.csv`

Sorted by policy ID:

```text
schema_version,anchor_set_id,policy_id,weight_00_bits,...,weight_27_bits
```

### `reference-runs.csv`

Sorted by scenario:

```text
schema_version,anchor_set_id,scenario_id,benchmark_run_id
```

### `calibration-report.csv`

Sorted by scenario then run ID, with the reference row first naturally when its run ID position
would otherwise differ:

```text
schema_version,calibration_acceptance,scenario_id,benchmark_run_id,reference_run_id,anchor_set_id,fixed_anchor_count,shared_anchor_count,delta_log,scale_factor,weighted_median_absolute_residual,status,reason
```

Stable reason values include `REFERENCE_RUN`, `STRONG`, `WEAK_ANCHOR_COUNT`,
`WEAK_RESIDUAL`, `INSUFFICIENT_SHARED_ANCHORS`, `EXCESSIVE_RESIDUAL`, and
`NONFINITE_SCALE`. `MISSING_SCENARIO_REFERENCE` is emitted only for an observed scenario absent from
the frozen reference catalog.

### `scenario-results.csv`

Sorted by scenario then policy ID:

```text
schema_version,scenario_id,environment_id,source_count,available_physical_core_count,source_ratio_numerator,source_ratio_denominator,policy_id,status,total_run_count,accepted_run_count,weak_run_count,uncalibrated_run_count,successful_repetition_count,planned_repetition_count,throughput_p25,throughput_median,throughput_p75,throughput_iqr,median_within_run_relative_iqr,mean_timeout_rate,mean_failure_rate,mean_non_success_rate,bootstrap_median_ci_low,bootstrap_median_ci_high,quality
```

Status values are `MISSING`, `NO_VALID_RUN`, `NO_ACCEPTED_CALIBRATION`, `VALID_STRONG`, and
`VALID_WEAK_OVERRIDE`.

### `robust-ranking.csv`

Eligible policies are first under `BEST_FIRST`; incomplete policies follow under the incomplete
ordering. Only eligible rows have a published rank:

```text
schema_version,published_rank,policy_id,eligible,required_scenario_count,observed_required_scenario_count,valid_required_scenario_count,coverage_fraction,worst_quality,quality_p25,geometric_mean_quality,cross_scenario_quality_mad,median_relative_iqr,mean_non_success_rate,mean_timeout_rate,missing_scenarios
```

Scenario lists use semicolon-separated canonical IDs sorted by `SourceScenario`.

### `coverage-report.csv`

Sorted by policy ID:

```text
schema_version,policy_id,eligible,required_scenario_count,observed_required_scenario_count,valid_required_scenario_count,measured_scenarios,missing_scenarios,rejected_scenarios
```

### Vector outputs

`robust-leaders.vectors.csv` contains eligible policies in robust rank order:

```text
schema_version,robust_rank,policy_id,weight_00_bits,...,weight_27_bits
```

`incomplete-policies.vectors.csv` contains incomplete policies in the deterministic incomplete
ordering:

```text
schema_version,valid_required_scenario_count,observed_required_scenario_count,policy_id,weight_00_bits,...,weight_27_bits
```

These files are descriptive machine-readable vectors, not the current headerless benchmark input.
Phase 3 will write the separately named benchmark-ready vector file after scheduling.

## Determinism, memory semantics, memory pollution, and precision

### Determinism

- Sort bundle paths by absolute normalized path before validation.
- Sort domain keys by scenario, unsigned policy ID, run ID, and repetition as specified.
- Never rely on `HashMap`, filesystem, stream, or thread completion order for output.
- Seed bootstrap by stable identities, not list indexes.
- Use `java.util.Random` with the documented seed and sorted source arrays.
- Use `StrictMath.log` and `StrictMath.exp`.
- Serialize vector bits exactly and derived doubles with `Double.toString`.
- Do not include wall-clock merge timestamps in deterministic datasets.
- Reordering input bundle paths or observation rows must produce byte-identical merger outputs.

### Memory semantics

The v1 merger is offline, single-owner code. Mutable accumulators are thread-confined; completed
records are immutable and safely published by ordinary task completion if later parallelism is
added. No VarHandle, opaque access, CAS, padded atomic, or pinned executor belongs in this path.

Do not reuse the current `DataMerger` `PlainQueue` and `PinnedThreadExecutor` normalization
machinery. A stronger memory mode is not needed, and parallel floating-point reduction would make
summation order part of the result.

### Memory pollution and benchmark isolation

- Intern one 28-weight `PolicyVector` per policy ID.
- Stream observations into per-run primitive accumulators; do not retain a corpus-sized list of
  `BenchmarkObservation` objects or repeat weight arrays per repetition.
- Release successful repetition arrays after producing `RunAggregate`.
- Retain run and scenario aggregates, not raw observations, for calibration and ranking.
- Do not use `TDigest`; exact arrays are small at the run and scenario levels.
- Phase 3 must record evidence outside engine worker loops. It must preserve the current
  `pause -> owner-thread cache reset -> counter reset -> weights -> resume` trial boundary and must
  not perform calibration, CSV parsing, or report generation while work is flowing.

### Mathematical precision

- No four-decimal rounding is allowed in v1 math or comparison.
- Quantiles are exact type 7 over finite doubles.
- Means and weight totals use Neumaier compensated summation in a fixed order.
- Natural-log calibration uses `StrictMath`.
- The only quality epsilon is `1.0e-12` inside the geometric-mean logarithm.
- The log uncertainty floor `0.01` and confidence thresholds `0.05` and `0.15` are explicit model
  constants, not general floating-point tolerances.
- Exact ties use `Double.compare`; no relative or absolute tie epsilon exists.
- Overflow, underflow to a non-positive calibrated value, NaN, and infinity are validation failures.
  They are never clamped into a plausible rank.

## Migration and deletion boundary

The new merger accepts only complete v1 observation bundle directories explicitly supplied in its
request. It does not:

- recurse through the current workspace;
- inspect names such as `iteration-0001-source-0004.txt`;
- infer a machine from names such as `graviton5-32core-1.txt`;
- assume the current default repetition duration;
- reinterpret zero-filled legacy repetitions as timeouts;
- recognize alternating vector/measurement lines; or
- load any model or checkpoint.

Phase 5's removable importer must either produce fully valid v1 bundles or import vectors through a
separate vector-only path. If run, scenario, duration, status, commit, or environment metadata
cannot be established without guessing, it rejects that measurement and reports the reason. Once a
v1 bundle is written, the merger sees only `EvidenceOrigin.IMPORTED`; it has no current-workspace
format dependency. `DIRECT_THROUGHPUT` is the sole compatibility seam for an otherwise unambiguous
imported repetition whose original format retained throughput but not raw frame/time counters;
native writers cannot use it.

The compatibility code retained after Phase 1 is exactly:

- `DataMerger.mergeQuentiles`, `mergeQuantiles`, `normalize`, `mergeMeans`, and the current pooled
  `merge`;
- `BenchmarkOutputReader` and `BenchmarkOutputWriter`;
- `PolicyRanking`, `Distribution`, and `VectorGrouper`; and
- the current alternating-row consumers in `SequenceFinder` and `CmaEsOptimizer`.

Later phases must migrate callers before Phase 7 removes stale pooled behavior. No new v1 class may
import or call these pooled-data symbols, except `PolicyVector` may call `HasherApi` and tests may
compare old and new vector bit round trips.

## File-by-file implementation checklist

Implement in this dependency order.

1. Add immutable identity and evidence types under
   `euhedral-training/src/main/java/io/euhedral_execution/training/data/`:
   `PolicyId.java`, `PolicyVector.java`, `SourceRatio.java`, `SourceScenario.java`,
   `EvidenceOrigin.java`, `ObservationStatus.java`, `MeasurementEncoding.java`, `PolicyRole.java`,
   `FrameSourceSeed.java`, `BenchmarkParameters.java`, `BenchmarkRunDescriptor.java`,
   `BenchmarkRunContext.java`, `ScheduledPolicy.java`,
   `ObservationKey.java`, `BenchmarkObservation.java`, `PolicyRegistry.java`, and the focused
   identity exceptions.
2. Add `VectorStatistics.java` under `training/merge`. Implement type-7 quantiles, compensated mean,
   lower weighted median, and deterministic water-filling before any calibration code.
3. Add `StrictCsv.java`, `ObservationBundle.java`, `ObservationBundleReader.java`, and
   `ObservationBundleWriter.java` under `training/data/io`. Keep all CSV details in this package.
4. Add `CalibrationStatus.java`, `CalibrationAcceptance.java`,
   `AnchorSelectionConfig.java`, `CalibrationConfig.java`, `AggregationConfig.java`,
   `AnchorCatalog.java`, `ReferenceRunCatalog.java`, `CalibrationPlan.java`, and
   `MergeRecords.java` under `training/merge`.
5. Add `RunAggregator.java`. Validate schemas, joins, expected repetitions, duplicate identities,
   contexts, hashes, statuses, and throughput before calculating raw run statistics.
6. Add `AnchorBootstrapper.java` and a focused `CalibrationPlanCsv.java`. Implement the frozen
   reference and stratified fixed-anchor rules exactly.
7. Add `RunCalibrator.java`. Keep calibration separate from aggregation and emit a result for every
   run, including failed confidence.
8. Add `HierarchicalAggregator.java`. Apply acceptance policy, equal run voting, deterministic run
   bootstrap, missing scenario rows, and stability fields.
9. Add `ScenarioQualityRanker.java` and `PolicyComparator.java`. Keep the comparator independent of
   CSV output and current `PolicyRanking`.
10. Add `MergeCsvWriter.java` for all eight v1 artifacts and their exact ordering.
11. Extend
    `euhedral-training/src/main/java/io/euhedral_execution/training/DataMerger.java` with the two v1
    facade methods and nested request/result records. Preserve current public methods and add the
    stated deprecation/removal marker only.
12. Do not edit `BenchmarkRunner.java`, `ClosedLoopRunner.java`, `Runner.java`,
    `SequenceFinder.java`, `PolicyOrdinalNetwork.java`, `CmaEsOptimizer.java`,
    `ScoreBandSampler.java`, `Distribution.java`, `VectorGrouper.java`, or
    `PolicyRanking.java` in Prompt 1B.
13. Do not edit `pom.xml`; use only JDK APIs and current `HasherApi`.
14. Add the tests and small golden resources below. Do not add generated benchmark output, training
    data, or model files.

## Deterministic synthetic fixtures and assertions

Create
`euhedral-training/src/test/java/io/euhedral_execution/training/fixtures/SyntheticObservations.java`.
It builds normalized, unique 28-weight policies, fixed `Instant` values, contexts, complete
repetition grids, and bundles under `@TempDir`. It must never read the user-owned
`euhedral-training/input` or `output` trees.

### Identity and codec fixtures

`PolicyIdentityTest`:

- round-trips all 28 raw bits, including `+0.0` and `-0.0`;
- proves signed zero creates different `p1` IDs;
- rejects NaN, infinities, a 27- or 29-weight vector, malformed IDs, and a deliberately injected
  declared-ID/vector mismatch;
- proves unsigned policy ordering and fixed 16-digit text.

`ObservationBundleCodecTest`:

- round-trips one five-policy, five-repetition bundle containing success, timeout, failed, and
  skipped rows;
- round-trips an imported direct-throughput success with absent counters and rejects the same row
  under native provenance;
- proves run, scenario, cohort, role, commit, parameters, timestamps, and every weight survive;
- rejects a mismatched scenario string, hash, observation ID, throughput, duration, duplicate
  repetition, missing planned repetition, mixed schema, and missing `COMPLETE`;
- rejects out-of-order schedule positions or observation writes; and
- writes the same logical schedule twice and compares all file bytes.

Keep one tiny golden bundle under
`euhedral-training/src/test/resources/robust-training/v1/golden-bundle/`. It contains no more than
five policies and five repetitions.

### Calibration fixtures

`RunCalibratorTest` uses five anchors with reference medians
`[100, 200, 400, 800, 1600]`.

1. A globally two-times-faster run has anchor medians
   `[200, 400, 800, 1600, 3200]`. Assert `deltaLog == StrictMath.log(2)`, scale `2`, residual `0`,
   status `CALIBRATED`, and a candidate median `1000` calibrates to `500`.
2. Change every non-anchor candidate from mediocre to much faster while keeping anchors fixed.
   Assert calibration bytes are unchanged. This proves cohort improvement cannot define scale.
3. Use ratios `[2, 2, 2, 2, 8]` with equal uncertainty. Assert the unstable anchor does not move
   `deltaLog` from `log(2)` and weighted median absolute residual is zero.
4. Give the stable anchors unequal repetition counts and IQRs and the outlier many precise repeats.
   Assert water-filling limits it to 0.25 and the stable majority still selects `log(2)`.
5. Use four shared stable anchors. Assert `WEAKLY_CALIBRATED`.
6. Use two shared anchors. Assert `UNCALIBRATED` with
   `INSUFFICIENT_SHARED_ANCHORS`.
7. Use at least five anchors whose weighted median absolute residual exceeds `0.15`. Assert
   `UNCALIBRATED` and no accepted scenario estimate.
8. Prove the same run is never calibrated through another non-reference run.

`AnchorBootstrapperTest`:

- selects `max(5, ceil(0.02 * budget))`;
- uses explicit references when supplied and otherwise earliest timestamp then run ID;
- filters unstable/non-successful candidates;
- selects the documented stratum midpoints and stores catalog entries in policy order;
- returns byte-identical catalogs for shuffled inputs;
- refuses imported default references, a short cross-scenario intersection, and mutation of a
  persisted plan.

### Hierarchical and timeout fixtures

`HierarchicalAggregatorTest`:

- Run A has 101 successful repetitions centered at 100; Run B has 3 successful repetitions centered
  at 200. Assert the scenario median is 150, P25 is 125, and P75 is 175. A pooled sample result near
  100 is explicitly not accepted.
- A run with successes `[90, 100, 110]` and two timeouts has median 100, P25 95, P75 105, timeout
  rate 0.4, and no zero in its quantiles.
- A five-repetition run with only two successes is `NO_VALID_RUN`.
- One timeout followed by four skipped repetitions has timeout rate 0.2, failure rate 0.8, and
  non-success rate 1.0.
- Weak calibration is excluded under `STRONG_ONLY`, included and labeled under `INCLUDE_WEAK`, and
  uncalibrated data is excluded in both modes.
- One-run bootstrap bounds equal the point estimate. Multi-run bounds and all output bytes are
  identical for shuffled run order.
- Missing required scenarios produce explicit `MISSING` rows and coverage gaps.

### Midrank and comparator fixtures

`ScenarioQualityRankerTest`:

- Throughputs `[10, 20, 20, 20, 30]` produce qualities `[0, 0.5, 0.5, 0.5, 1]`.
- A single policy and an all-tied population both produce 0.5.
- Uncertainty interval overlap does not change point midranks.
- Equal ratios on different core counts or environments are ranked in separate populations.

`PolicyComparatorTest` uses four policies and three scenarios:

```text
             scenario-1  scenario-2  scenario-3
policy-A        100          40          50
policy-B         50         100          40
policy-C         40          50         100
policy-R         90          90          90
```

With four distinct values per scenario, policy R has quality `2/3` in every scenario and wins on
minimum quality even though it is second-best everywhere. Each specialist wins one scenario and has
worst quality zero.

Add paired summaries that differ at only one comparator tier. Assert the exact priority of worst,
P25, geometric mean, MAD, relative IQR, non-success, and policy ID. Assert the comparator rejects
incomplete summaries and `PUBLISHED_ORDER` always places a complete leader before an incomplete
specialist.

### End-to-end merger fixture

`DataMergerTest` constructs:

- two environments;
- two exact source scenarios per environment;
- one frozen reference and two calibrated runs per scenario;
- stable anchors plus one unstable anchor;
- the robust second-best-everywhere policy;
- a one-scenario specialist;
- one incomplete policy;
- unequal repetitions, ties, timeouts, failed, and skipped rows.

Assert:

- all eight output files and exact headers exist;
- every policy/scenario pair survives;
- calibration scales and statuses match;
- the robust policy is rank 1;
- the specialist and incomplete policy have no published robust rank;
- coverage lists exact canonical missing scenarios;
- vector files contain the correct policy bits and cannot be mistaken for current headerless
  benchmark input;
- running into two fresh directories with reversed bundle order yields byte-identical outputs; and
- a duplicate observation, declared policy hash/vector mismatch, incompatible schema, or ambiguous
  run context aborts before any final output file is published.

## Acceptance criteria

Prompt 1B is complete only when:

- every v1 identity can be reconstructed and validated without a path;
- the fixed anchor catalog and reference map are persisted and never silently recomputed;
- global candidate-cohort improvement does not alter calibration;
- a global machine speed factor is removed by anchors;
- one unstable/high-weight anchor cannot dominate;
- weak and uncalibrated evidence cannot create a default robust leader;
- repetition count cannot become scenario weight;
- timeouts and failures remain visible but never become zero-throughput successes;
- equal scenario values receive exact midranks;
- complete robust leaders and incomplete policies are separate in rank and output;
- the robust comparator is lexicographic and exact, with no scalar replacement;
- scenario rows remain available behind every robust summary;
- v1 code contains no P99 normalization, `TDigest`, four-decimal ranking, legacy path inference,
  model migration, or checkpoint migration;
- shuffled equivalent inputs produce byte-identical v1 outputs; and
- only intended source/tests/resources and this blueprint's completion record are changed.

## Validation commands for Prompt 1B

From the repository root:

```bash
mise exec -- mvn -B -pl euhedral-training -am install -Dmaven.test.skip=true
mise exec -- mvn -B -pl euhedral-training test
git diff --check
git status --short
```

Also run these targeted searches:

```bash
rg -n "quantile\\(0\\.99\\)|CommonFunctions\\.round|TDigest|mergeQuantiles" \
  euhedral-training/src/main/java/io/euhedral_execution/training/data \
  euhedral-training/src/main/java/io/euhedral_execution/training/merge

rg -n "input/merger|iteration-.*source|graviton|zen4|latest-model|\\.bin" \
  euhedral-training/src/main/java/io/euhedral_execution/training/data \
  euhedral-training/src/main/java/io/euhedral_execution/training/merge
```

Both searches must return no v1 implementation matches. If the upstream install fails during the
hardware module's Zig/native initialization, report that environmental failure separately and still
run the narrowest test command possible with already installed upstream artifacts.

## Risks and later-phase handoff

There is no unresolved Phase 1 statistical or architectural blocker.

The following are deliberate later-phase inputs, not open decisions:

- Phase 2 consumes scenario quality and uncertainty and defines scenario-conditioned tensors and
  grouped validation.
- Phase 3 generates stable run/cohort IDs, persists the calibration plan in checkpoint state,
  schedules the frozen anchors and rolling leaders, records complete v1 bundles, and defines the
  incomplete predicted priority. When no plan exists, it first benchmarks a dedicated native
  bootstrap cohort drawn from seed vectors across every required scenario, then invokes the settled
  bootstrap selector; it does not invent anchors before evidence exists.
- Phase 4 packages these deterministic CSVs, provenance, checksums, and raw bundle indexes.
- Phase 5 may convert only unambiguous current-workspace evidence into this exact v1 contract and
  must leave all layout knowledge outside the merger.
- Phase 7 removes `ROBUST_OPTIMIZER_POOLED_V0_REMOVAL` and the other enumerated pooled seams only
  after every caller has moved.

Prompt 1B must append completion notes below this line with changed files, commands, results, and
any deviation. A deviation that changes identity, calibration, aggregation, quality, ranking, or
migration semantics requires another reasoning pass.

## Prompt 1B completion notes

Implementation stopped on 2026-07-27 because the original settled API could not enforce one of the
settled calibration invariants:

- The "Qualifying anchor estimate" section requires a fixed anchor in every non-reference run to
  carry the `FIXED_ANCHOR` role.
- `ScheduledPolicy` contains the roles, but the specified `MergeRecords.RunAggregate` contains only
  the `PolicyVector` and run/statistical fields.
- The specified `RunCalibrator.calibrate(List<RunAggregate>, CalibrationPlan,
  CalibrationConfig)` receives neither observation bundles nor scheduled-policy metadata.
- Consequently, after the required Stage 1 reduction, `RunCalibrator` cannot distinguish a policy
  deliberately scheduled as a fixed anchor from the same catalog policy scheduled under another
  role. Accepting every catalog member would violate the explicit role requirement; adding roles to
  `RunAggregate`, adding a separate run/policy-role index, or changing the calibrator signature
  would each reopen a settled public API.

Reasoning decision made on 2026-07-27: `RunAggregate` owns an immutable, lexicographically sorted
`SortedSet<PolicyRole>` copied from the corresponding `ScheduledPolicy`. This is the narrowest
representation that preserves per-run provenance across the Stage 1 reduction. It avoids retaining
observations, reopening bundles, or maintaining a second run-policy metadata map. It is extensible
because later phases may add enum roles without changing the aggregate/calibrator API; calibration
continues to inspect only `FIXED_ANCHOR`. `RunCalibrator` must reject a non-reference catalog policy
from the shared-anchor set unless this set contains `FIXED_ANCHOR`. Reference runs remain exempt as
already specified. Tests must include a catalog policy measured without that role and prove it does
not increase shared-anchor count.

Work performed before discovery:

- Created branch `agent/phase1-data-calibration-ranking`.
- Added partial identity, observation, strict bundle-codec, statistics, merge-record, quality, and
  comparator implementation files under `euhedral-training`.
- Began `RunAggregator` and `RunCalibrator`; the latter is intentionally not contract-complete
  because it cannot enforce the missing role input.
- The predictor, CMA-ES, scheduling, benchmark runner, and current workspace data were not changed.

Commands run:

```text
mise exec -- mvn -B -pl euhedral-training -am install -Dmaven.test.skip=true
  -> could not start because `mise` was not on PATH

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -am install -Dmaven.test.skip=true
  -> first sandboxed run failed only because the local Maven repository was read-only
  -> approved rerun succeeded; all six selected reactor modules compiled and installed
```

No completion commit or push was made at the original stop because the blueprint explicitly required
implementation to stop rather than invent this design choice. Implementation resumed only after the
user explicitly requested this reasoning amendment.

### Final completion record - 2026-07-27

Phase 1 is implemented.

Changed production files:

- Added the immutable identity, scenario, provenance, scheduling-role, run, observation, registry,
  and validation types under
  `euhedral-training/src/main/java/io/euhedral_execution/training/data/`.
- Added the strict v1 CSV bundle reader/writer under `training/data/io`. The writer streams ordered
  rows, forces evidence files, and writes an empty `COMPLETE` marker last. The merger uses the
  synchronous streaming visitor and retains only per-run successful-throughput arrays and status
  counts.
- Added exact statistics, anchor bootstrap/catalog persistence, direct reference calibration,
  hierarchical aggregation, scenario midranks, robust summaries/comparators, and deterministic CSV
  output under `training/merge`.
- Extended `DataMerger` with `bootstrapCalibrationV1` and `merge`, atomic temporary-sibling
  publication, output validation, and the nested request/artifact records. The pooled v0 entry
  points remain as deprecated `ROBUST_OPTIMIZER_POOLED_V0_REMOVAL` seams.
- Did not change `BenchmarkRunner`, `ClosedLoopRunner`, `Runner`, `SequenceFinder`,
  `PolicyOrdinalNetwork`, `CmaEsOptimizer`, `ScoreBandSampler`, `Distribution`, `VectorGrouper`,
  `PolicyRanking`, `pom.xml`, or any current workspace input/output data.

Added deterministic tests and resources:

- `PolicyIdentityTest`
- `ObservationBundleCodecTest`
- `RunCalibratorTest`
- `AnchorBootstrapperTest`
- `HierarchicalAggregatorTest`
- `ScenarioQualityRankerTest`
- `PolicyComparatorTest`
- `DataMergerTest`
- `fixtures/SyntheticObservations`
- `src/test/resources/robust-training/v1/golden-bundle/`

The suite contains 35 new Phase 1 tests and retains the four existing predictor/optimizer/ranking
tests, for 39 passing tests total. It covers raw-bit identity, strict schemas and grids, direct
imported throughput, role-aware anchors, reference selection and immutable persistence, log-ratio
calibration, water-filling caps, weak/failed calibration, equal-run scenario voting, timeouts and
skips, deterministic bootstraps, missing/rejected scenario rows, exact midranks, every robust
comparator tier, atomic output failure, all eight headers, exact coverage, and byte-identical output
under shuffled bundle order.

Reasoning amendments made before or during implementation are incorporated into the normative
sections above:

- Stage 1 retains immutable per-run policy roles so non-reference anchors must actually carry
  `FIXED_ANCHOR`.
- The log scale uses the explicit log-ratio evaluation order required by the exact two-times
  fixture.
- `AnchorCatalog.of` is the canonical extensible construction path and constructors validate the
  computed anchor-set ID.
- The bundle streaming visitor is public for later native producer validation while remaining
  synchronous and single-owner.
- Observed scenarios absent from the frozen reference catalog remain explicit uncalibrated rows with
  `MISSING_SCENARIO_REFERENCE`; no reference is inferred.

Validation results:

```text
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -am install -Dmaven.test.skip=true
  -> BUILD SUCCESS; all six selected reactor modules succeeded

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test
  -> BUILD SUCCESS; 39 tests, 0 failures, 0 errors, 0 skipped

git diff --check
  -> clean

Both required targeted `rg` searches
  -> no v1 implementation matches
```

The bare `mise` executable was not on the shell `PATH`. A later absolute `mise exec` attempt
inherited a broad home-level tool configuration and began installing unrelated CLIs, so it was
interrupted. Final validation used the already provisioned pinned Java and Maven installations
directly. No repository files were changed by that environment attempt.

There are no unresolved Phase 1 deviations or blockers.
