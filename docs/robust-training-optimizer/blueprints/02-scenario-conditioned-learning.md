# Phase 2 Blueprint: Scenario-Conditioned Ordinal Learning

Status: implementation complete; conformance recheck passed

This blueprint settles the Phase 2 data, feature, target, validation, inference, uncertainty, and
model-artifact contracts. Prompt 2B must implement these decisions without reopening the modeling
choices or rediscovering the pooled trainer. If an implementation detail cannot satisfy this
document, stop and record the conflict here instead of silently selecting another contract.

## Scope

Phase 2 adds a new learning path in `euhedral-training` that:

1. reads the strict Phase 1 `scenario-results.csv` and joins it to the two Phase 1 vector
   dictionaries;
2. creates one learning row per valid `policy + exact source scenario`;
3. conditions an ordinal predictor on the 28 policy weights and source/core ratio;
4. keeps all rows for one `PolicyId` in the same train, validation, or test partition;
5. evaluates every configured exact scenario with both grouped-policy and
   leave-one-source-scenario-out protocols;
6. emits scenario-specific quality, ordinal uncertainty, and ensemble disagreement;
7. provides a count-feature ablation without making absolute counts part of the default model;
8. serializes a versioned, self-describing ensemble with its normalizer, scenarios, seeds,
   fingerprints, metrics, and member checksums; and
9. exposes a policy-curve inference API for Phase 3 without deciding optimizer ordering or
   scheduling.

The production baseline is:

```text
28 policy weights + normalized source/core ratio
    -> 9 cumulative scenario-quality logits
    -> per-scenario quality distribution and ensemble disagreement
```

The model remains an offline trainer artifact. No network or learning dependency enters
`euhedral-core`.

### Explicit non-goals

- Do not change Phase 1 identity, calibration, aggregation, quality, comparator, or CSV schemas.
- Do not recompute Phase 1 scenario quality from throughput or define a second quality scale.
- Do not read alternating legacy vector/quantile files, current workspace benchmark files, old
  checkpoints, or old model directories.
- Do not modify `ClosedLoopRunner`, `BenchmarkRunner`, `CmaEsOptimizer`, `ScoreBandSampler`,
  candidate-budget allocation, carry-forward state, or checkpoint state. Phase 3 owns their
  migration.
- Do not aggregate predicted curves into an optimizer score or decide how incomplete candidates
  compare. Phase 3 owns predicted robust ordering.
- Do not use environment ID as a hardware class or one-hot feature. Phase 1 records no portable
  hardware-class taxonomy.
- Do not add throughput, run count, calibration status, uncertainty, or environment identity as an
  inference feature. Those values do not exist for an unmeasured candidate.
- Do not warm-start from the pooled 28-input model. Scenario model v1 trains from scratch.
- Do not promise byte-identical neural weights across different DJL engines, devices, native
  libraries, or thread implementations. Deterministic records, splits, seeds, row order, reports,
  and inference from one saved artifact are required.
- Do not add final CLI names or rewrite user documentation. Phase 5 owns final configuration and
  commands after Phase 3 has integrated the model.
- Do not package the final training run. Phase 4 owns the final package.

## Reconciliation with the current implementation

| Current code | Current contract | Phase 2 decision |
| --- | --- | --- |
| `SequenceFinder.loadTrainingData` | Reads alternating 28-weight and five-quantile rows | Leave it as a pooled v0 compatibility seam. The new reader accepts only the three explicit Phase 1 CSV paths. |
| `SequenceFinder` split | Hashes each pooled vector into 80/10/10 | Add a stable `PolicyId` split. Every scenario row for a policy follows the policy group. |
| `PolicyRanking.buildDecileThresholds` | Learns nine cohort thresholds from training quantiles | Do not use it. Phase 1 quality already lies in `[0, 1]`; fixed thresholds are `0.1` through `0.9`. |
| `PolicyRanking.compare` | Ranks pooled P50/IQR/tail values after four-decimal rounding | Do not use it anywhere in the new learner or evaluator. Phase 1 `quality` is the target. |
| `PolicyOrdinalNetwork` | `28 -> 128 -> 96 -> 48 -> 9`, one model | Add a separate dynamic-input network with the same hidden widths and a five-member production ensemble. |
| `PolicyOrdinalNetwork.rankingScore` | Produces one scalar with an extra top-decile multiplier | Replace it in the new path with an ordinal probability distribution, mean quality, interval, entropy, and member disagreement. |
| `PolicyOrdinalNetwork.trainWithEarlyStopping` | Selects by pooled validation top-10 precision, then BCE | Select each new member by validation macro scenario MAE, then macro scenario Spearman, then weighted BCE. |
| `PolicyOrdinalNetwork.save/load` | Saves `euhedral-policy-ranker-0000.params` with no external schema | New artifacts require `model-metadata.json`, fixed member paths, checksums, and matching properties inside each DJL file. |
| `DataMerger.MergeArtifacts` | Returns `scenario-results.csv` plus eligible and incomplete vector files | Join all three. The two vector files are disjoint and together contain the dictionary for every Phase 1 policy. |
| `scenario-results.csv` | Contains scenario and policy ID but not the 28 weights | Preserve the exact Phase 1 header. Never add weights to it in Phase 2. |
| `MergeRecords.ScenarioResult` | Retains quality, throughput interval, run counts, and calibration status | Use `quality` as the only target. Retain the other fields for audit only; do not make them inputs or loss weights. |
| `ClosedLoopRunner` and `Runner` | Call pooled merge, train, scalar inference, and candidate generation | Leave them unchanged until Phase 3 switches the whole workflow. |
| `euhedral-training/pom.xml` | Already provides DJL PyTorch and test libraries | No dependency or module descriptor change is needed. Use a purpose-built strict metadata codec and JDK APIs. |

The old `PolicyOrdinalNetwork`, `SequenceFinder`, and `PolicyRanking` remain intentionally usable by
the old closed loop during Phase 2. No new Phase 2 class may import them. They remain part of the
`ROBUST_OPTIMIZER_POOLED_V0_REMOVAL` boundary for Phase 7 after Phase 3 migrates all callers.

## Phase 1 compatibility and learning-table contract

### Required persisted inputs

The persisted reader accepts exactly:

```java
public record ScenarioInputs(
        Path scenarioResults,
        Path robustLeaderVectors,
        Path incompletePolicyVectors) {
    public static ScenarioInputs from(DataMerger.MergeArtifacts artifacts);
}
```

`from` maps:

- `artifacts.scenarioResults()`;
- `artifacts.robustLeaderVectors()`; and
- `artifacts.incompleteVectors()`.

All three paths must be regular files. The reader accepts only `schema_version=1` and the exact
Phase 1 headers. It does not search a directory, infer filenames, accept reordered columns, or
recognize a legacy text file.

The reader first builds one `PolicyRegistry` from the vector files:

- `robust-leaders.vectors.csv` must use the exact `robust_rank` header;
- `incomplete-policies.vectors.csv` must use the exact valid/observed-count header;
- a policy may occur in exactly one of the files;
- all 28 raw bit fields are parsed and passed to `PolicyVector.of`;
- the recomputed `PolicyId` must equal the declared ID; and
- `robust-leaders.vectors.csv` rows are validated in contiguous Phase 1 robust-rank order;
- `incomplete-policies.vectors.csv` rows are validated in Phase 1 valid-count descending,
  observed-count descending, unsigned `PolicyId` tie-break order; and
- after the Phase 1 vector files are validated and joined, the learner stores its registry in
  unsigned `PolicyId` order. Rank and count fields are audited metadata, not learning targets.

It then parses `scenario-results.csv`, reconstructs `SourceScenario`, verifies its canonical ID,
joins `policy_id` to the registry, and constructs the Phase 1 `ScenarioResult` invariants before
creating a learning row. A missing vector, extra vector with no scenario row, duplicate
`policy + scenario`, invalid optional field, mixed schema, or incomplete Cartesian grid is fatal.

For `P` dictionary policies and `S` required scenarios, the file must contain exactly one row for
every member of `P x S`. Rows for observed non-required scenarios may also exist. They are parsed
and audited but are not used for Phase 2 fitting or evaluation.

### Included and excluded rows

The default row policy is:

| Phase 1 status | Default action |
| --- | --- |
| `VALID_STRONG` | Include. |
| `VALID_WEAK_OVERRIDE` | Exclude and count as `WEAK_EXCLUDED`. |
| `MISSING` | Exclude and count as `MISSING`. |
| `NO_VALID_RUN` | Exclude and count as `NO_VALID_RUN`. |
| `NO_ACCEPTED_CALIBRATION` | Exclude and count as `NO_ACCEPTED_CALIBRATION`. |
| valid row for a non-required scenario | Exclude and count as `NOT_REQUIRED`. |

`includeWeakCalibrationRows=true` is an explicit data ablation. It includes
`VALID_WEAK_OVERRIDE`, records the choice in metadata, and never changes the meaning of
`VALID_STRONG`. It defaults to `false`.

Every included row is:

```java
public record ScenarioLearningRow(
        PolicyVector policy,
        SourceScenario scenario,
        ScenarioResultStatus sourceStatus,
        double quality,
        double throughputMedian,
        double bootstrapMedianCiLow,
        double bootstrapMedianCiHigh,
        int acceptedRunCount,
        double medianWithinRunRelativeIqr,
        double meanNonSuccessRate) implements Comparable<ScenarioLearningRow> {
}
```

Rows are ordered by unsigned `PolicyId`, then `SourceScenario`. All numeric fields must satisfy the
Phase 1 record invariants. The only learning target is `quality`. Throughput and evidence-health
fields remain on the row so audit reports can trace a target back to Phase 1, but they are not
features, label weights, or alternative targets.

The in-memory and persisted paths converge:

```java
public final class ScenarioLearningReader {
    public static ScenarioLearningTable read(
            ScenarioInputs inputs,
            SortedSet<SourceScenario> requiredScenarios,
            boolean includeWeakCalibrationRows) throws IOException;

    public static ScenarioLearningTable fromScenarioResults(
            Collection<ScenarioResult> results,
            SortedSet<SourceScenario> requiredScenarios,
            boolean includeWeakCalibrationRows);
}
```

`ScenarioLearningTable` contains immutable sorted rows, the complete policy dictionary, required
scenarios, and audit counts:

```java
public record ScenarioLearningTable(
        List<ScenarioLearningRow> rows,
        SortedMap<PolicyId, PolicyVector> policies,
        SortedSet<SourceScenario> requiredScenarios,
        ScenarioDatasetAudit audit,
        String datasetFingerprintSha256) {
}

public record ScenarioDatasetAudit(
        int policyCount,
        int requiredScenarioCount,
        int rowCount,
        int includedStrongRowCount,
        int includedWeakRowCount,
        int weakExcludedRowCount,
        int missingRowCount,
        int noValidRunRowCount,
        int noAcceptedCalibrationRowCount,
        int nonRequiredRowCount) {
}
```

The table must contain at least one included row for every required scenario and at least one
included row for every policy that reaches a partition. A dictionary policy with no valid required
row remains in the audit but is not assigned to a learning partition.

### Dataset fingerprint

`datasetFingerprintSha256` is lower-case SHA-256 over UTF-8 bytes in this exact order:

```text
scenario-learning-table-v1\n
required:<scenario-canonical>\n
...
policy:<policy-id>|<weight-00-raw-hex>|...|<weight-27-raw-hex>\n
...
row:<policy-id>|<scenario-canonical>|<source-status>|<quality-raw-hex>|<throughput-median-raw-hex>|<ci-low-raw-hex>|<ci-high-raw-hex>|<accepted-run-count>|<relative-iqr-raw-hex>|<non-success-raw-hex>\n
...
```

Required scenarios use natural scenario order, policies use unsigned ID order, and rows use the
settled row order. Raw hex fields are exactly 16 lower-case digits from
`Double.doubleToRawLongBits`. There is one LF after every line, including the last. Excluded
required-scenario rows affect the audit but not the fingerprint row section; the include-weak flag
and audit counts are stored separately in metadata. Reordering CSV rows or input paths cannot
change the fingerprint.

Phase 1 quality is frozen before the policy split. Although its empirical percentile population
contains policies that later land in validation or test, it is the authoritative scenario-relative
target, not a learned label threshold. Recomputing it per partition would create incompatible
quality scales and makes a true held-out-scenario target undefined. No target statistic is fitted
after seeing validation or test features.

## Exact scenario enumeration

- `requiredScenarios` is a non-empty caller-supplied `SortedSet<SourceScenario>`.
- It is never inferred from filenames, environment names, source ratios, or model contents.
- Training and primary evaluation use only required exact scenarios.
- Equal reduced ratios with different absolute counts or environments remain different evaluation
  groups and different prediction-curve entries.
- The baseline ratio-only encoder intentionally gives equal raw context features to equal ratios.
  The count ablation may distinguish absolute counts; neither mode distinguishes environments with
  equal counts and ratio.
- Required scenarios are serialized in natural order in the model metadata.
- The model also records the sorted set of required scenarios with at least one included training
  row. For a deployable model the two sets must be equal.

Inference has two forms:

1. `predictConfiguredCurves` enumerates the exact required scenarios stored in the model; and
2. `predictCurves` accepts an explicit non-empty scenario set and supports a deliberate future
   scenario without retraining, provided the selected feature schema can encode it.

Both forms sort scenarios naturally. Policies retain caller order after duplicate-ID validation.
The logical inference table is policy-major and scenario-minor:

```text
row = policyIndex * scenarioCount + scenarioIndex
```

## Exact feature schemas

### Feature-set enum

```java
public enum ScenarioFeatureSet {
    POLICY_ONLY("policy-only-v1", 28, true),
    RATIO_ONLY("policy-ratio-v1", 29, false),
    RATIO_AND_COUNTS("policy-ratio-counts-v1", 31, false);

    public String schemaId();
    public int width();
    public boolean ablationOnly();
    public List<String> featureNames();
}
```

`POLICY_ONLY` is a negative-control ablation and can never be deployment eligible.
`RATIO_ONLY` is the default and minimum production schema. `RATIO_AND_COUNTS` is experimental and
may be selected only by the cross-environment gate below.

The exact raw feature order is:

```text
index 00..27  policy_weight_00 .. policy_weight_27
index 28      source_core_ratio
index 29      source_count_log1p                    [RATIO_AND_COUNTS only]
index 30      available_physical_core_count_log1p  [RATIO_AND_COUNTS only]
```

Raw values are:

```text
policy_weight_i                    = policy.weight(i)
source_core_ratio                  = sourceCount / (double) availablePhysicalCoreCount
source_count_log1p                 = StrictMath.log1p(sourceCount)
available_physical_core_count_log1p =
    StrictMath.log1p(availablePhysicalCoreCount)
```

The ratio must compare exactly to `SourceRatio.asDouble()`. Policy weights are not L2-normalized,
rounded, clipped, or canonicalized by the learner. Phase 1 identity remains authoritative.

No feature uses:

- `environmentId`;
- raw or calibrated throughput;
- Phase 1 quality or uncertainty;
- run, repetition, timeout, failure, or calibration counts;
- iteration or cohort;
- policy role; or
- a model prediction from another scenario.

An explicit portable hardware-class field would require a later versioned evidence and feature
schema. `environmentId` must not be relabeled as one.

### Training-only normalization

All raw features are standardized with a persisted `FeatureNormalizer`:

```java
public record FeatureNormalizer(
        String featureSchemaId,
        List<String> featureNames,
        double[] means,
        double[] scales,
        boolean[] constantFeatures) {

    public static FeatureNormalizer fit(
            List<ScenarioLearningRow> trainingRows,
            ScenarioFeatureSet featureSet);

    public void encode(
            PolicyVector policy,
            SourceScenario scenario,
            float[] destination,
            int offset);
}
```

Normalization is fitted only after the grouped policy split and only from the training partition.
Validation, test, held-out scenario, and held-out environment rows cannot change it.

The fitting population is deliberately de-duplicated:

- each distinct training `PolicyId` contributes once to each of the 28 policy-coordinate moments;
  and
- each distinct exact training `SourceScenario` contributes once to each context-coordinate
  moment.

This prevents a well-covered policy or scenario from silently receiving more normalization weight.
Both populations are sorted before arithmetic.

For each coordinate:

```text
mean     = compensatedSum(x) / N
variance = compensatedSum((x - mean) * (x - mean)) / N
stddev   = StrictMath.sqrt(max(variance, 0))

if stddev < 1.0e-12:
    scale = 1.0
    constantFeature = true
else:
    scale = stddev
    constantFeature = false

encoded = (raw - mean) / scale
```

The variance is the population variance. Means and squared-deviation sums use Neumaier
compensation. A tiny negative variance caused only by the last rounding operation is replaced with
zero; any other non-finite value is fatal. There is no clipping or winsorization. An encoded value
that is not finite as a `float` is rejected with the policy and scenario identity.

The complete normalizer, including raw double bits and constant flags, is serialized. A loaded
model never refits normalization from inference inputs.

## Targets, row weights, and table/tensor shapes

### Fixed ordinal targets

For Phase 1 scenario quality `q` in `[0, 1]`, output `k` in `[0, 9)` uses:

```text
threshold_k = (k + 1) / 10.0
label_k     = q >= threshold_k ? 1.0f : 0.0f
```

Threshold comparison is inclusive and exact. The threshold array is exactly:

```text
[0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]
```

There is no training-set percentile calculation, `PolicyRanking` comparator, quantile vector,
four-decimal rounding, label interpolation, or missing-label sentinel.

### Equal scenario contribution

Every included policy/scenario row is one observation. Raw repetition count, accepted run count,
throughput interval width, and scenario coverage do not become label weights.

To prevent a better-covered scenario from dominating the objective, training row weights are:

```text
R   = total training row count
S   = number of required scenarios
n_s = training row count for exact scenario s

rowWeight(i in s) = R / (S * n_s)
```

Every required scenario must have `n_s > 0`. The weights have arithmetic mean one and each exact
scenario has equal total weight. They are recomputed independently for a LOSO or LOEO fold from the
fold's fitting rows.

Class balance is fitted from hard training labels and row weights:

```text
positiveRate_k = sum_i(rowWeight_i * label_i,k) / sum_i(rowWeight_i)
floor          = 1.0 / R
rate_k         = clamp(positiveRate_k, floor, 1.0 - floor)
positiveWeight_k = 0.5 / rate_k
negativeWeight_k = 0.5 / (1.0 - rate_k)
```

There is no extra top-decile multiplier. All nine quality thresholds matter to a predicted curve.

### Primitive matrix contract

For `R` rows, feature width `F`, and output width `K=9`:

```java
public record ScenarioLearningMatrix(
        int rows,
        int featureWidth,
        float[] features,
        float[] ordinalLabels,
        float[] rowWeights,
        double[] qualities,
        PolicyId[] policyIds,
        SourceScenario[] scenarios) {
}
```

Shapes and lengths are:

| Value | Logical shape | Flat length |
| --- | --- | --- |
| `features` | `[R, F]` | `R * F` |
| `ordinalLabels` | `[R, 9]` | `R * 9` |
| `rowWeights` | `[R, 1]` | `R` |
| `qualities` | `[R]` | `R` |
| `policyIds` | `[R]` | `R` |
| `scenarios` | `[R]` | `R` |

Rows use policy-major/scenario-minor table order. Features and labels are contiguous row-major
`float[]`; authoritative evaluation qualities remain `double[]`.

The DJL dataset contains one data array `[R,F]` and two label arrays `[R,9]` and `[R,1]`.
The balanced binary cross-entropy applies label smoothing only to the BCE target:

```text
smoothed = hardLabel * (1 - 2 * 0.02) + 0.02
```

Class selection still uses the hard label. Per-batch loss is the sum of
`rowWeight * classWeight * stableBCE`, divided by `sum(rowWeight) * 9`. A zero or non-finite
denominator is fatal.

```text
stableBCE(logit, smoothed) =
    max(logit, 0)
    - logit * smoothed
    + StrictMath.log1p(StrictMath.exp(-abs(logit)))
```

## Network and ensemble contract

### Member architecture

Each member is a DJL PyTorch `SequentialBlock`:

```text
F inputs
  -> Linear(128) -> GELU
  -> Linear(96)  -> GELU
  -> Linear(48)  -> GELU
  -> Linear(9)
```

There is no dropout, batch normalization, scenario embedding, residual branch, or output sigmoid
inside the block. Xavier initialization, AdamW, gradient clipping at `5.0`, and the stable balanced
ordinal BCE are retained from the current network.

The exact DJL optimizer/configuration construction is:

```java
Optimizer optimizer = Optimizer.adamW()
        .optLearningRateTracker(Tracker.fixed(config.learningRate()))
        .optWeightDecays(config.weightDecay())
        .optClipGrad(5.0f)
        .build();

DefaultTrainingConfig training = new DefaultTrainingConfig(loss)
        .optOptimizer(optimizer)
        .optDevices(new Device[]{resolvedDevice})
        .optInitializer(new XavierInitializer(), Parameter.Type.WEIGHT);
```

Use DJL 0.36.0 defaults for AdamW parameters not shown here. Do not read training hyperparameters
from system properties inside the network.

Production uses five independently initialized members. Different member initialization and batch
order provide epistemic disagreement while every member sees the same fitting rows. There is no
row bootstrap in v1 because sparse scenarios must not disappear from an ensemble member.

### Training defaults

```java
public record ScenarioTrainingConfig(
        long splitSeed,
        long modelSeed,
        String device,
        int ensembleMembers,
        int losoEvaluationMembers,
        int ablationMembers,
        int maxEpochs,
        int patience,
        int batchSize,
        float learningRate,
        float weightDecay,
        float labelSmoothing,
        int minimumTrainPolicyGroups,
        int minimumValidationPolicyGroups,
        int minimumTestPolicyGroups,
        int minimumTrainRowsPerScenario,
        int minimumValidationRowsPerScenario,
        int minimumTestRowsPerScenario,
        boolean includeWeakCalibrationRows,
        FeatureSelectionMode featureSelectionMode,
        EvaluationThresholds thresholds) {

    public static ScenarioTrainingConfig defaults();
}
```

Defaults are:

```text
splitSeed                       = 0x243f6a8885a308d3L
modelSeed                       = 0x13198a2e03707344L
device                          = auto
ensembleMembers                 = 5
losoEvaluationMembers           = 1
ablationMembers                 = 3
maxEpochs                       = 250
patience                        = 20
batchSize                       = 0  (device recommendation)
learningRate                    = 0.001f
weightDecay                     = 0.0001f
labelSmoothing                  = 0.02f
minimumTrainPolicyGroups        = 40
minimumValidationPolicyGroups   = 10
minimumTestPolicyGroups         = 10
minimumTrainRowsPerScenario     = 30
minimumValidationRowsPerScenario = 5
minimumTestRowsPerScenario      = 5
includeWeakCalibrationRows      = false
featureSelectionMode            = RATIO_ONLY
```

`ensembleMembers` must be odd and in `[3, 9]` for a deployable artifact.
`losoEvaluationMembers` is in `[1, ensembleMembers]`; one bounds final fold cost.
`ablationMembers` must be odd and in `[3, ensembleMembers]` so feature selection is not decided by
one initialization. Tests may use non-deployable one-member configurations through a
package-private test factory. Production validation never weakens these public invariants.

`device` accepts `auto`, `cpu`, or a DJL device name such as `gpu0`. `auto` resolves once before
any member is created and the resolved device is used for production and temporary folds.
Device-recommended batch sizes remain 512 for CPU and 4,096 for GPU, capped at fitting row count.
Inference defaults remain 16,384 rows for CPU and 65,536 rows for GPU.

### Deterministic seeds and batch order

The grouped split seed and model seed are separate. Every member seed uses one canonical material
string:

```text
scenario-ordinal-member-seed-v1
kind=<training-kind>
feature=<feature-schema-id>
fold=<fold-id>
member=<four-digit-member-index>
```

There is one LF after every line, including the last. The allowed training kinds and fold IDs are:

```text
PRODUCTION              fold=all
TEST_LOSO               fold=<held-out-scenario-canonical>
VALIDATION_CONTEXT_LOSO fold=<held-out-scenario-canonical>
VALIDATION_COUNTS_LOEO  fold=<held-out-environment-id>
```

The member index is formatted with `String.format(Locale.ROOT, "%04d", m)`.

```text
memberSeed64 = HasherApi.getHash(seedMaterial, modelSeed)

engineSeed32 = (int) (memberSeed64 ^ (memberSeed64 >>> 32))
```

Seeds are stored as 16-digit lower-case unsigned hex. This makes production, LOSO, context
ablation, count ablation, feature set, fold, and member identity independently reproducible.

DJL `Engine.setRandomSeed(int)` is called immediately before trainer initialization. Because that
seed is engine-global, member fitting is sequential and guarded by one private static monitor.
The monitor is the publication boundary for the global seed and model initialization; no two Phase
2 training calls may fit concurrently.

Do not use DJL's global `RandomSampler`. Add a deterministic sampler that, for epoch `e`, performs
Fisher-Yates over `[0, R)` with:

```text
epochSeed = HasherApi.getHash(
    "scenario-ordinal-v1/epoch/" + e,
    memberSeed64)
random = new java.util.Random(epochSeed)

for i from R - 1 down to 1:
    swap(index[i], index[random.nextInt(i + 1)])
```

Prefetch is zero on both CPU and GPU. One device trains one member at a time. These rules make
splits, initialization seeds, and sample order reproducible. Native floating-point kernels may
still differ across supported engine/device combinations; metadata records the producing engine,
version, and device.

### Early stopping

After every epoch, evaluate the entire validation matrix in fixed row order and compute the metrics
defined below. A checkpoint improves when:

1. validation macro scenario MAE is lower by more than `1.0e-9`; or
2. MAE is tied within `1.0e-9` and macro scenario Spearman is higher by more than `1.0e-9`; or
3. both are tied within `1.0e-9` and weighted BCE is lower.

The best epoch is saved as epoch `0000`; its actual zero-based training epoch is retained in
metadata and training history. Test rows, held-out-scenario rows, ablation scoring rows, and
acceptance thresholds never select an epoch.

Validation macro MAE must be present. When macro Spearman is temporarily absent because predictions
are constant, it is treated as negative infinity only for the tie-break; the epoch may still be the
initial MAE checkpoint but cannot make the final model pass evaluation.

Scenario model v1 does not warm-start. A changed training corpus normally changes both the
normalizer and objective population, so copying first-layer weights from another normalizer would
silently change their meaning. Phase 3 retrains from the Phase 1 corpus and may checkpoint the
complete new model artifact.

## Inference, ordinal uncertainty, and disagreement

### Monotonic projection

For each member and row, apply this stable sigmoid locally; the new package must not import
`PolicyRanking`:

```text
if logit >= 0:
    probability = 1 / (1 + StrictMath.exp(-logit))
else:
    e = StrictMath.exp(logit)
    probability = e / (1 + e)
```

Project the nine probabilities onto a non-increasing sequence with equal-weight
pool-adjacent-violators (PAV):

```text
p_0 >= p_1 >= ... >= p_8
```

Build singleton blocks left to right. While a left block mean is less than the next block mean,
merge them and use their combined arithmetic mean. Exact `Double.compare` decides a violation; no
tolerance is used. Expand final block means to their members. Clamp only a sigmoid's possible
last-bit excursion to `[0,1]`; a materially non-finite value is fatal.

### Member distribution

The projected cumulative probabilities define ten ordinal bins:

```text
mass_0 = 1 - p_0
mass_b = p_(b - 1) - p_b       for b in [1, 8]
mass_9 = p_8

center_b = 0.05 + 0.10 * b
```

Mass is non-negative and sums to one within floating-point rounding. Make `mass_9` absorb only the
final compensated-sum remainder after validating that no mass is below `-1.0e-15`; smaller negative
roundoff is set to `+0.0`.

For member `m`:

```text
memberMean_m = sum_b(mass_m,b * center_b)
```

This is equivalent to `0.05 + 0.10 * sum_k(p_m,k)` but the mass form is authoritative for
uncertainty. The `[0.05,0.95]` midpoint range is intentional: nine ordinal boundaries identify ten
decile bins, not an exact endpoint regression.

### Ensemble output

Average bin mass in ascending member index using compensated sums. From the resulting mixture:

```text
predictedQuality   = sum_b(meanMass_b * center_b)
ordinalVariance    = sum_b(meanMass_b * (center_b - predictedQuality)^2)
ordinalStdDev      = StrictMath.sqrt(max(ordinalVariance, 0))
qualityIntervalLow = lower inverse-CDF bin center at 0.025
qualityIntervalHigh = lower inverse-CDF bin center at 0.975
ordinalEntropy     = -sum_b(meanMass_b * log(meanMass_b)) / log(10)
topDecileProbability = mean projected p_8
```

Zero masses do not enter the logarithm. The interval is a discrete ordinal predictive interval,
not a Gaussian confidence interval.

Ensemble disagreement is:

```text
epistemicStdDev =
    sample standard deviation(memberMean_m), denominator M - 1

disagreementRange =
    max(memberMean_m) - min(memberMean_m)
```

Both are zero for the package-private one-member test/ablation path. Do not fold disagreement into
`predictedQuality`; Phase 3 receives it separately.

The public immutable outputs are:

```java
public record ScenarioPrediction(
        SourceScenario scenario,
        double predictedQuality,
        double ordinalStdDev,
        double qualityIntervalLow,
        double qualityIntervalHigh,
        double ordinalEntropy,
        double topDecileProbability,
        double epistemicStdDev,
        double disagreementRange) {
}

public record PolicyPredictionCurve(
        PolicyVector policy,
        List<ScenarioPrediction> scenarios) {
}

public record OrdinalDistribution(
        double[] cumulativeProbabilities,
        double[] binMasses,
        double meanQuality,
        double variance,
        double entropy,
        double topDecileProbability) {
}

public record EnsembleOrdinalDistribution(
        double[] meanBinMasses,
        double predictedQuality,
        double ordinalStdDev,
        double qualityIntervalLow,
        double qualityIntervalHigh,
        double ordinalEntropy,
        double topDecileProbability,
        double epistemicStdDev,
        double disagreementRange) {
}
```

All rates and qualities lie in `[0,1]`; standard deviation and range are finite and non-negative;
interval endpoints enclose `predictedQuality` only when the discrete mixture makes that true, so
the record validates ordered endpoints but does not force enclosure. Both distribution records
defensively copy their arrays.

### Batched inference and memory

```java
public final class ScenarioConditionedModel implements AutoCloseable {
    public static ScenarioConditionedModel load(Path modelDirectory) throws IOException;
    public static ScenarioConditionedModel load(
            Path modelDirectory, String device) throws IOException;
    public static ScenarioConditionedModel loadForAudit(Path modelDirectory) throws IOException;
    public static ScenarioConditionedModel loadForAudit(
            Path modelDirectory, String device) throws IOException;

    public ScenarioModelMetadata metadata();
    public List<PolicyPredictionCurve> predictConfiguredCurves(
            List<PolicyVector> policies);
    public List<PolicyPredictionCurve> predictCurves(
            List<PolicyVector> policies,
            SortedSet<SourceScenario> scenarios,
            int maximumBatchRows);
}
```

`load` requires an accepted, deployment-eligible artifact. `loadForAudit` also opens a rejected
artifact so reports can be reproduced. One-argument forms use `auto`; two-argument forms accept the
same device values as `ScenarioTrainingConfig`.

Inference batches contain whole policy curves. If `S` scenarios are requested:

```text
policiesPerBatch = max(1, maximumBatchRows / S)
actualRows       = policiesInBatch * S
```

One primitive feature batch and aggregate arrays are retained. Members run sequentially into a
reused logit buffer; per-member corpus-sized predictions are never retained. Each member call uses
a short-lived NDManager submanager. Output curve records are created only after all members have
contributed to that batch.

No public `predictScores(float[28], ...)` adapter is added. Phase 3 must deliberately enumerate
scenarios and consume curves.

## Grouped-policy split and evaluation protocols

### Stable 80/10/10 split

Every distinct policy with at least one included row receives one partition:

```text
splitHash = HasherApi.getHash(policyId.canonical(), splitSeed)
bucket    = (int) Math.unsignedMultiplyHigh(splitHash, 10L)

bucket 0..7 -> TRAIN
bucket 8    -> VALIDATION
bucket 9    -> TEST
```

The bucket is in `[0,9]`. Assignment depends only on stable policy identity and the split seed, so
adding rows or scenarios cannot move an existing policy. All rows for one policy must be in exactly
one partition.

```java
public enum LearningPartition { TRAIN, VALIDATION, TEST }

public record PolicyGroupedSplit(
        SortedMap<PolicyId, LearningPartition> policyPartitions,
        List<ScenarioLearningRow> trainingRows,
        List<ScenarioLearningRow> validationRows,
        List<ScenarioLearningRow> testRows,
        SortedSet<PolicyId> ablationEarlyStopPolicies,
        SortedSet<PolicyId> ablationScorePolicies,
        List<ScenarioLearningRow> ablationEarlyStopRows,
        List<ScenarioLearningRow> ablationScoreRows) {
}

public final class PolicyGroupedSplitter {
    public static PolicyGroupedSplit split(
            ScenarioLearningTable table,
            long splitSeed,
            ScenarioTrainingConfig config);
}
```

Before training, enforce the configured minimum distinct policy groups and per-required-scenario
row counts in every partition. Each validation and test scenario must also contain at least two
distinct target qualities, at least one `q >= 0.9`, and at least one `q < 0.9`. Failure is
`INSUFFICIENT_DATA`, not a fallback row split.

### Validation-only ablation subdivision

Feature selection must not inspect the final test partition. Divide validation policy groups into
two stable, disjoint halves using:

```text
ablationHash = HasherApi.getHash(
    policyId.canonical(),
    splitSeed ^ 0x9e3779b97f4a7c15L)

unsigned low bit 0 -> ABLATION_EARLY_STOP
unsigned low bit 1 -> ABLATION_SCORE
```

All rows for the policy follow the half. Both halves must satisfy the relevant fold minimums.
`ABLATION_EARLY_STOP` selects temporary fold epochs; `ABLATION_SCORE` selects a feature schema.
The full validation partition selects epochs only after the schema has been selected.

Each half requires at least
`ceil(minimumValidationPolicyGroups / 2.0)` distinct policies and, for every row set used by a
fold, at least `max(2, ceil(minimumValidationRowsPerScenario / 2.0))` rows. A scoring row set also
requires two target qualities and both top-decile classes. Failure of a required
validation-context fold is a pre-training `InsufficientScenarioLearningDataException`. Failure
specific to an optional count LOEO fold makes counts non-evaluable: `AUTO_COUNTS_IF_VALIDATED`
falls back to ratio-only and `REQUIRE_COUNTS` records a rejected gate. Policies never move between
halves.

### Primary grouped evaluation

The production ensemble fits training policies, uses validation policies for early stopping, and
is evaluated once on test policies across all required scenarios.

Per exact scenario report:

- row and distinct-policy count;
- MAE;
- RMSE;
- mean signed bias, `predicted - actual`;
- Spearman rank correlation with exact midranks;
- actual `q >= 0.9` count;
- `K = max(1, ceil(0.10 * rowCount))`;
- precision and recall among the `K` highest predicted qualities;
- mean ordinal interval width;
- observed coverage of the ordinal 95 percent interval; and
- mean epistemic standard deviation and disagreement range.

Predicted top-K ordering is higher `predictedQuality`, lower `epistemicStdDev`, then unsigned
`PolicyId`. Exactly K rows are selected. Actual top-decile membership uses `q >= 0.9`; it does not
select the test set's own top K.

Spearman assigns exact midranks independently to actual and predicted ties, then calculates Pearson
correlation of the ranks with compensated sums. It is blank with status `CONSTANT_RANK` when either
rank variance is zero. No tie tolerance is used.

Ordinal interval coverage counts a row when
`qualityIntervalLow <= actualQuality <= qualityIntervalHigh`, with both comparisons inclusive.
Status precedence is `INSUFFICIENT_ROWS`, `INSUFFICIENT_CONTEXT_VARIATION`,
`NO_TOP_DECILE_TARGET`, `CONSTANT_RANK`, then `OK`. Metrics that remain mathematically defined are
still reported on a non-`OK` row, but only `OK` rows enter acceptance macros.

Macro metrics give every exact scenario one vote. Micro metrics give every row one vote and are
reported for diagnosis only. Acceptance uses macro values and the worst exact-scenario MAE.

### Leave-one-source-scenario-out evaluation

Create one fold for every required exact `SourceScenario h`:

```text
fit rows:
    primary TRAIN policies
    AND scenario != h

early-stop rows:
    primary VALIDATION policies
    AND scenario != h

score rows:
    primary TEST policies
    AND scenario == h
```

Fit a fresh normalizer and `losoEvaluationMembers` fresh model members for each fold. Do not save
these temporary models in the production artifact. The held-out exact scenario cannot affect
normalization, class weights, initialization, epoch selection, or fitting.

Policy groups remain disjoint across fitting, early stopping, and scoring. A test policy may have
rows in non-held scenarios, but none enters fold fitting or early stopping.

A ratio-conditioned LOSO fold additionally requires at least two distinct source/core ratio values
in its fitting rows. Otherwise the fold status is `INSUFFICIENT_CONTEXT_VARIATION`; it is not
silently treated as a passing fold. Every required scenario must produce an `OK` fold for a
deployment-eligible model.

Equal ratios on other environments or core counts remain available unless their exact scenario is
the held-out one. The report therefore records:

- held-out exact scenario;
- held-out raw ratio;
- whether the ratio appeared in another fitting scenario;
- fitting scenario count and distinct fitting-ratio count; and
- all per-scenario metrics.

This is exact-scenario generalization. It does not claim that a duplicated ratio is an unseen
numeric coordinate.

### Metric records and reports

```java
public enum EvaluationStatus {
    OK,
    INSUFFICIENT_ROWS,
    INSUFFICIENT_CONTEXT_VARIATION,
    NO_TOP_DECILE_TARGET,
    CONSTANT_RANK
}

public record ScenarioEvaluationMetrics(
        String evaluationKind,
        String foldId,
        ScenarioFeatureSet featureSet,
        SourceScenario scenario,
        int rowCount,
        int policyCount,
        double mae,
        double rmse,
        double meanBias,
        OptionalDouble spearman,
        int actualTopDecileCount,
        int selectedCount,
        OptionalDouble precisionAtTen,
        OptionalDouble recallAtTen,
        double meanIntervalWidth,
        double intervalCoverage95,
        double meanEpistemicStdDev,
        double meanDisagreementRange,
        EvaluationStatus status) {
}

public record EvaluationSummary(
        String evaluationKind,
        ScenarioFeatureSet featureSet,
        List<ScenarioEvaluationMetrics> scenarios,
        OptionalDouble macroMae,
        OptionalDouble macroRmse,
        OptionalDouble macroSpearman,
        OptionalDouble macroPrecisionAtTen,
        OptionalDouble macroRecallAtTen,
        OptionalDouble worstScenarioMae,
        OptionalDouble microMae) {
}
```

`ScenarioModelEvaluator` owns all metric and fold calculations. The trainer and network do not
duplicate metric math. Evaluation joins rows and predictions by exact `PolicyId + SourceScenario`,
requires a one-to-one complete match, and rejects duplicate or extra prediction entries.

## Ablations and feature selection

### Feature-selection modes

```java
public enum FeatureSelectionMode {
    RATIO_ONLY,
    AUTO_COUNTS_IF_VALIDATED,
    REQUIRE_COUNTS
}
```

All modes run the `POLICY_ONLY` validation LOSO negative control. `RATIO_ONLY` selects the baseline.
The other modes compare `RATIO_ONLY` with `RATIO_AND_COUNTS`.

Temporary ablation models use `ablationMembers`, the same hidden architecture, split, fold
rows, target, loss, hyperparameters, and member-seed derivation rule. The feature schema ID makes
the actual member seeds distinct; only the feature schema, its normalizer, and the deterministic
seed identity differ.

### Scenario-context gate

For each required exact scenario `h`, the validation-only context fold is:

```text
fit:
    primary TRAIN policies
    AND scenario != h

early stop:
    ABLATION_EARLY_STOP policies
    AND scenario != h

score:
    ABLATION_SCORE policies
    AND scenario == h
```

No primary test policy participates. The same fold identities, rows, member count, and derived
seeds are used for `POLICY_ONLY` and `RATIO_ONLY`.

On validation-only LOSO folds, the ratio model demonstrates useful scenario context when:

```text
ratio macro MAE <= policy-only macro MAE - 0.01
OR
ratio macro Spearman >= policy-only macro Spearman + 0.05
```

and both non-regression guards hold:

```text
ratio macro MAE <= policy-only macro MAE + 0.01
ratio macro Spearman >= policy-only macro Spearman - 0.02
```

All compared metrics must be present and all required folds must be `OK`. A failed context gate
makes the final artifact non-deployable. It does not cause fallback to a policy-only model.

### Absolute-count cross-environment gate

Absolute counts may be selected only when at least two environment IDs are present and a
validation-only leave-one-environment-out (LOEO) comparison succeeds.

For held-out environment `e`:

```text
fit:
    primary TRAIN policies
    AND scenario.environmentId != e

early stop:
    ABLATION_EARLY_STOP policies
    AND scenario.environmentId != e

score:
    ABLATION_SCORE policies
    AND scenario.environmentId == e
```

All scenarios of the held-out environment are absent from fitting and normalization. Compare
macro-over-environment metrics. Counts pass only when:

```text
counts macro MAE <= ratio-only macro MAE - 0.01
counts macro Spearman >= ratio-only macro Spearman - 0.02
counts worst-environment MAE <= ratio-only worst-environment MAE + 0.02
```

All environment folds must be `OK`.

- `AUTO_COUNTS_IF_VALIDATED` selects counts only on a pass and otherwise selects ratio-only.
- `REQUIRE_COUNTS` trains the count-feature production ensemble for audit but creates a rejected,
  non-deployable artifact when the gate fails.
- `RATIO_ONLY` may still report that counts were not requested.

With fewer than two environments, `AUTO_COUNTS_IF_VALIDATED` selects ratio-only with reason
`INSUFFICIENT_ENVIRONMENTS`; `REQUIRE_COUNTS` is rejected with the same reason.

Environment ID is used only to form this holdout. It is never encoded.

### Weak-calibration ablation

`includeWeakCalibrationRows` is recorded but does not automatically select a model variant.
Prompt 2B tests both reader modes. A production run that deliberately enables it is deployment
eligible only if all ordinary data and evaluation gates pass; the explicit Phase 1 override remains
visible in metadata and later packaging.

## Acceptance thresholds

```java
public record EvaluationThresholds(
        double maximumGroupedMacroMae,
        double minimumGroupedMacroSpearman,
        double minimumGroupedMacroPrecisionAtTen,
        double maximumLosoMacroMae,
        double minimumLosoMacroSpearman,
        double maximumLosoWorstScenarioMae,
        double minimumContextMaeImprovement,
        double minimumContextSpearmanImprovement,
        double maximumContextMaeRegression,
        double maximumContextSpearmanRegression,
        double minimumCountsCrossEnvironmentMaeImprovement,
        double maximumCountsSpearmanRegression,
        double maximumCountsWorstEnvironmentMaeRegression) {

    public static EvaluationThresholds defaults();
}
```

Defaults are:

```text
maximumGroupedMacroMae                    = 0.20
minimumGroupedMacroSpearman               = 0.50
minimumGroupedMacroPrecisionAtTen         = 0.20
maximumLosoMacroMae                       = 0.25
minimumLosoMacroSpearman                  = 0.35
maximumLosoWorstScenarioMae               = 0.35
minimumContextMaeImprovement              = 0.01
minimumContextSpearmanImprovement         = 0.05
maximumContextMaeRegression               = 0.01
maximumContextSpearmanRegression          = 0.02
minimumCountsCrossEnvironmentMaeImprovement = 0.01
maximumCountsSpearmanRegression           = 0.02
maximumCountsWorstEnvironmentMaeRegression = 0.02
```

The context and count guards are the exact rules in the ablation section, including their
additional non-regression values. Thresholds are serialized. Changing them creates a different
training request and different canonical metadata bytes but not a new feature schema or dataset
fingerprint.

The final selected production model is `ACCEPTED` only when:

1. all split and per-scenario data minimums pass;
2. the validation-only scenario-context gate passes;
3. a requested/required counts gate behaves as specified;
4. every grouped test scenario is `OK`;
5. grouped macro MAE, Spearman, and precision meet their thresholds;
6. every LOSO test fold is `OK`;
7. LOSO macro MAE and Spearman meet their thresholds;
8. worst LOSO scenario MAE meets its threshold; and
9. every prediction, uncertainty field, report metric, checksum, and metadata invariant is finite
   and valid.

Uncertainty width and disagreement are reported but are not rejected by an arbitrary smallness
threshold. High disagreement is useful Phase 3 audit information.

```java
public enum ModelAcceptanceStatus {
    ACCEPTED,
    SCENARIO_CONTEXT_GATE_FAILED,
    REQUIRED_COUNTS_GATE_FAILED,
    GROUPED_QUALITY_GATE_FAILED,
    LOSO_QUALITY_GATE_FAILED
}
```

When several statistical gates fail, `acceptanceReasons` contains every stable reason sorted in the
gate order above, and `acceptanceStatus` is the first failing enum in that order. Stable reason
codes include the failing metric name and threshold but no formatted prose.

Input, schema, split, or minimum-data failure throws a typed
`InsufficientScenarioLearningDataException` before member training and leaves no target directory.
Artifact validation failure also removes the temporary directory and leaves no target. A
statistically rejected attempt that completed valid member training is atomically published as an
auditable model directory with metadata, reports, and member artifacts. Normal `load` rejects it;
`loadForAudit` permits it. Phase 3 must never schedule from a rejected model.

## Model metadata, versioning, and serialization

### Artifact layout

One model directory is:

```text
<model-directory>/
+-- model-metadata.json
+-- grouped-evaluation.csv
+-- loso-evaluation.csv
+-- ablation-evaluation.csv
+-- training-history.csv
+-- members/
    +-- member-000/
    |   +-- euhedral-scenario-ordinal-0000.params
    +-- member-001/
    |   +-- euhedral-scenario-ordinal-0000.params
    +-- ...
```

The target directory must not exist. Training writes a unique temporary sibling, writes member
files and reports, calculates checksums, writes metadata last, reopens the complete artifact with
`loadForAudit`, reproduces a fixed metadata probe, then moves the directory into place. Use
`ATOMIC_MOVE` when supported and a same-filesystem move otherwise. Failure never leaves a partial
target and never overwrites another model.

### Version identities

The exact identities are:

```text
artifact_type                 = euhedral-scenario-conditioned-ordinal-model
schema_version                = 1
objective_version             = scenario-quality-ordinal-v1
policy_id_scheme              = p1
learning_schema_version       = 1
member_model_name             = euhedral-scenario-ordinal
architecture                  = F-128-96-48-9-gelu
ordinal_thresholds            = 0.1 through 0.9
```

The schema version covers directory layout, JSON fields, normalization encoding, member properties,
and report headers. The objective version covers target and decoding semantics. A future feature
order, output meaning, target boundary, normalizer rule, or uncertainty decoder requires a new
feature/objective/schema identity as appropriate.

### Canonical metadata

`model-metadata.json` is UTF-8, two-space-indented, LF-terminated, and emitted in one fixed key
order by `ScenarioModelMetadataCodec`. It contains no timestamp or absolute path.

The top-level fields, in order, are:

```text
artifact_type                         string
schema_version                        integer
objective_version                     string
learning_schema_version               integer
policy_id_scheme                      string
policy_width                          integer
feature_schema_id                     string
feature_width                         integer
feature_names                         array<string>
feature_mean_bits                     array<16-hex-string>
feature_scale_bits                    array<16-hex-string>
feature_constant                      array<boolean>
output_width                          integer
ordinal_threshold_bits                array<16-hex-string>
architecture                          string
ensemble_members                      integer
member_model_name                     string
members                               array<object>
split_algorithm                       string
split_seed_hex                        16-hex-string
model_seed_hex                        16-hex-string
dataset_fingerprint_sha256            64-hex-string
include_weak_calibration_rows         boolean
required_scenarios                    array<object>
training_scenarios                    array<object>
partition_counts                      object
training_config                       object
evaluation_thresholds                 object
feature_selection                     object
evaluation_summary                    object
acceptance_status                     enum string
acceptance_reasons                    array<string>
producer                              object
metadata_probe                        object
```

Each member object contains `index`, `seed_hex`, `best_epoch`, artifact-relative `path`, and
lower-case SHA-256 `sha256`. Scenario objects contain canonical ID, environment ID, source count,
available physical core count, ratio numerator, and ratio denominator. Numeric normalization and
threshold values are strings of raw double bits, not rounded JSON numbers.

Member `index=i` must use exactly
`members/member-<three-digit-i>/euhedral-scenario-ordinal-0000.params`. The codec recomputes this
path rather than trusting an absolute path, separator variant, `.` segment, or `..` segment from
metadata.

`partition_counts` records distinct policies and rows for train, validation, test,
`ABLATION_EARLY_STOP`, and `ABLATION_SCORE`, plus per-scenario row counts.
`training_config` records every settled hyperparameter and producing effective batch size.
`feature_selection` records requested mode, every ablation metric, chosen feature set, and reason.
`evaluation_summary` records grouped and LOSO macro/worst metrics and the report filenames.
`producer` records Git commit SHA, dirty flag, DJL engine, engine version, and training device.
The commit and dirty values are explicit request inputs; they are not inferred by running Git in
the learner.

`metadata_probe` contains the smallest unsigned `PolicyId` in the training table, the first required
scenario, and all eight numeric `ScenarioPrediction` fields as raw double bits. Reopening the
artifact must reproduce each field bit-for-bit on the same device used during the immediate
validation. Normal cross-device loading validates finite predictions and metadata but does not
require probe-bit identity.

The public metadata model is:

```java
public record ScenarioModelMetadata(
        int schemaVersion,
        String objectiveVersion,
        ScenarioFeatureSet featureSet,
        FeatureNormalizer normalizer,
        List<String> ordinalThresholdBits,
        String architecture,
        String memberModelName,
        List<MemberMetadata> members,
        String splitAlgorithm,
        long splitSeed,
        long modelSeed,
        String datasetFingerprintSha256,
        boolean includeWeakCalibrationRows,
        SortedSet<SourceScenario> requiredScenarios,
        SortedSet<SourceScenario> trainingScenarios,
        PartitionCounts partitionCounts,
        ScenarioTrainingConfig trainingConfig,
        FeatureSelectionDecision featureSelection,
        EvaluationSummaryMetadata evaluationSummary,
        ModelAcceptanceStatus acceptanceStatus,
        List<String> acceptanceReasons,
        ProducerMetadata producer,
        MetadataProbe metadataProbe) {
}

public record MemberMetadata(
        int index,
        long seed,
        int bestEpoch,
        String relativePath,
        String sha256) {
}

public record PartitionCounts(
        SortedMap<String, Integer> policyCounts,
        SortedMap<String, Integer> rowCounts,
        SortedMap<String, SortedMap<SourceScenario, Integer>> scenarioRowCounts) {
}

public record FeatureSelectionDecision(
        FeatureSelectionMode requestedMode,
        ScenarioFeatureSet selectedFeatureSet,
        List<AblationMetric> metrics,
        String reason) {
}

public record AblationMetric(
        String evaluationKind,
        String foldId,
        ScenarioFeatureSet featureSet,
        ScenarioFeatureSet comparisonFeatureSet,
        String scenarioOrEnvironment,
        int rowCount,
        OptionalDouble mae,
        OptionalDouble spearman,
        OptionalDouble maeDelta,
        OptionalDouble spearmanDelta,
        boolean selected,
        String gateStatus,
        String reason) {
}

public record EvaluationSummaryMetadata(
        String groupedReport,
        String losoReport,
        OptionalDouble groupedMacroMae,
        OptionalDouble groupedMacroSpearman,
        OptionalDouble groupedMacroPrecisionAtTen,
        OptionalDouble losoMacroMae,
        OptionalDouble losoMacroSpearman,
        OptionalDouble losoWorstScenarioMae) {
}

public record ProducerMetadata(
        String commitSha,
        boolean dirtyWorkingTree,
        String djlEngine,
        String djlEngineVersion,
        String trainingDevice) {
}

public record MetadataProbe(
        PolicyId policyId,
        SourceScenario scenario,
        List<String> predictionRawBits,
        String producingDevice) {
}
```

`AblationMetric` is the immutable row model corresponding to
`ablation-evaluation.csv`. These records make sorted immutable copies and validate the fixed
artifact/objective/policy/Phase 1 identities even where those constants are not constructor
parameters.

`ScenarioModelMetadataCodec` is a strict codec for this one generated shape, not a general JSON
library. It escapes JSON strings, rejects unknown, duplicate, or missing fields and unknown schema
versions, requires full input consumption, and validates every raw-bit array length. No dependency
is added.

### Member properties and loading

Before `Model.save`, each member sets:

```text
Epoch=0
artifact_type=euhedral-scenario-conditioned-ordinal-model
schema_version=1
objective_version=scenario-quality-ordinal-v1
feature_schema_id=<selected schema>
feature_width=<F>
output_width=9
member_index=<zero-based integer>
member_seed_hex=<16 hex>
architecture=F-128-96-48-9-gelu
```

Loading reads metadata first, validates all checksums, builds the declared block, loads the fixed
model name from each member directory, and verifies every embedded property. A missing metadata
file, old `euhedral-policy-ranker` directory, legacy `.bin`, wrong feature width, changed normalizer,
unknown schema/objective, missing member, checksum mismatch, or acceptance rejection fails before
inference.

The diagnostic for a metadata-less current model must state that the pooled 28-input artifact is
not compatible and must be retrained from Phase 1 scenario records. There is no migration or
best-effort load.

### Deterministic CSV reports

All reports are UTF-8 RFC 4180 CSV, LF-terminated, exact-header, and sorted. Derived doubles use
`Double.toString`; absent optional metrics are empty.

`grouped-evaluation.csv`:

```text
schema_version,evaluation_kind,feature_schema_id,fold_id,scenario_id,row_count,policy_count,mae,rmse,mean_bias,spearman,actual_top_decile_count,selected_count,precision_at_10,recall_at_10,mean_interval_width,interval_coverage_95,mean_epistemic_stddev,mean_disagreement_range,status
```

`loso-evaluation.csv` adds context audit columns:

```text
schema_version,evaluation_kind,feature_schema_id,fold_id,scenario_id,held_out_ratio,ratio_seen_in_fit,fitting_scenario_count,fitting_distinct_ratio_count,row_count,policy_count,mae,rmse,mean_bias,spearman,actual_top_decile_count,selected_count,precision_at_10,recall_at_10,mean_interval_width,interval_coverage_95,mean_epistemic_stddev,mean_disagreement_range,status
```

`ablation-evaluation.csv`:

```text
schema_version,evaluation_kind,fold_id,feature_schema_id,comparison_schema_id,scenario_or_environment,row_count,mae,spearman,mae_delta,spearman_delta,selected,gate_status,reason
```

`training-history.csv`:

```text
schema_version,training_kind,fold_id,feature_schema_id,member_index,member_seed_hex,epoch,validation_macro_mae,validation_macro_spearman,validation_weighted_bce,selected_epoch
```

Grouped rows sort by scenario. LOSO rows sort by held-out scenario. Ablation rows sort by evaluation
kind, fold ID, and feature schema. History sorts by training kind, fold, feature schema, member, and
epoch. `selected_epoch` is exactly `true` on the saved best epoch and `false` otherwise.

## Exact public API and ownership

All new code lives under `io.euhedral_execution.training.learning`. The existing
`training.networks` package remains the pooled v0 boundary during Phase 2. No lower module depends
on the new package.

### Training facade

```java
public record ScenarioTrainingRequest(
        ScenarioInputs inputs,
        SortedSet<SourceScenario> requiredScenarios,
        Path modelDirectory,
        String commitSha,
        boolean dirtyWorkingTree,
        ScenarioTrainingConfig config) {
}

public record ScenarioTrainingArtifacts(
        Path modelDirectory,
        Path metadata,
        Path groupedEvaluation,
        Path losoEvaluation,
        Path ablationEvaluation,
        Path trainingHistory,
        ModelAcceptanceStatus acceptanceStatus,
        ScenarioFeatureSet selectedFeatureSet) {
}

public final class ScenarioModelTrainer {
    public static ScenarioTrainingArtifacts train(
            ScenarioTrainingRequest request) throws Exception;
}
```

The request constructor copies and validates every collection and path. Commit SHA uses the Phase 1
native SHA rule. Imported-only training still records the producing trainer commit.

### Feature, split, target, and evaluator services

```java
public final class ScenarioFeatureEncoder {
    public static FeatureNormalizer fit(
            List<ScenarioLearningRow> trainingRows,
            ScenarioFeatureSet featureSet);
    public static ScenarioLearningMatrix matrix(
            List<ScenarioLearningRow> rows,
            SortedSet<SourceScenario> activeScenarios,
            FeatureNormalizer normalizer);
}

public final class ScenarioOrdinalTargets {
    public static final int OUTPUT_WIDTH = 9;
    public static double threshold(int outputIndex);
    public static void encode(double quality, float[] destination, int offset);
    public static OrdinalDistribution decode(double[] logits);
    public static EnsembleOrdinalDistribution combine(List<OrdinalDistribution> members);
}

public final class ScenarioModelEvaluator {
    public static EvaluationSummary evaluate(
            String evaluationKind,
            ScenarioFeatureSet featureSet,
            List<ScenarioLearningRow> rows,
            List<PolicyPredictionCurve> predictions);
}
```

Records defensively copy arrays and collections. Missing values use `OptionalDouble`, never NaN.
`ScenarioModelTrainer` owns temporary LOSO/LOEO member fitting through package-private fold helpers;
`ScenarioModelEvaluator` remains pure metric code over settled rows and predictions.

### Internal network boundary

```java
interface OrdinalMember extends AutoCloseable {
    int featureWidth();
    void predictLogits(float[] features, int rows, float[] destination);
}
```

The DJL implementation is package-private to the trainer/model package. The interface permits
deterministic inference and evaluator tests without loading PyTorch. It is not a plugin or public
extension point. `ScenarioConditionedModel` has a package-private `forTest` factory accepting
validated metadata and `List<OrdinalMember>`; production constructors remain private.

`ScenarioConditionedModel` owns all loaded members and their NDManagers. Closing the ensemble closes
each member exactly once. Calls after close fail. Concurrent inference is not promised in v1;
callers use one owner or external ordering.

## Data flow and dependency boundaries

```text
Phase 1 MergeArtifacts
    |
    +-- scenario-results.csv
    +-- robust-leaders.vectors.csv
    +-- incomplete-policies.vectors.csv
    |
    v
strict ScenarioLearningReader
    |
    v
ScenarioLearningTable + audit + fingerprint
    |
    v
PolicyGroupedSplitter
    |
    +-- validation-only feature ablations
    |       +-- POLICY_ONLY negative control
    |       +-- optional absolute-count LOEO
    |
    +-- selected train/validation matrices
    |       +-- training-only normalizer
    |       +-- scenario-balanced row weights
    |       +-- five sequential ordinal members
    |
    +-- grouped test evaluation
    +-- LOSO test evaluation
    |
    v
atomic versioned model artifact
    |
    v
ScenarioConditionedModel.predictConfiguredCurves
    |
    v
Phase 3 robust predicted-curve comparison and scheduling
```

No arrow returns to Phase 1 or the runtime. Phase 2 reads immutable merger outputs and creates a
separate model artifact.

## Memory semantics, memory pollution, and mathematical precision

### Memory semantics

Dataset parsing, splitting, normalization, evaluation, and report generation are offline,
single-owner operations. Mutable maps and accumulators are thread-confined, and published domain
records are immutable.

The only global mutable operation is DJL engine seeding. One private monitor encloses seed setting,
model initialization, and member fitting. Monitor exit/entry supplies the required happens-before
edge. Do not introduce VarHandles, padded atomics, pinned threads, or parallel floating-point
reductions.

Inference model objects are owner-confined. Model metadata and feature normalizers are immutable.
No claim of lock-free concurrent inference is made.

### Memory pollution

- Intern one `PolicyVector` per Phase 1 `PolicyId`.
- Retain one learning row per valid policy/scenario, not raw observations or repetitions.
- Use flat primitive arrays for matrices and inference batches.
- Fit ensemble members sequentially and reuse immutable matrices.
- Do not retain member-by-row prediction matrices after aggregation.
- Batch whole policy curves and reuse logit/accumulator buffers.
- Close every `Batch`, `Trainer`, `NDArray`, NDManager submanager, `Model`, and ensemble.
- Do not read CSV, train, normalize, evaluate, or serialize while benchmark work is flowing.
- Do not write model artifacts under user-owned `euhedral-training/input`, `output`, or `data`
  during tests; use `@TempDir`.

### Mathematical precision

- Phase 1 `quality` remains a `double` until label encoding.
- Feature moments, metrics, PAV block means, distributions, uncertainty, and ensemble reductions
  use `double`.
- Cast only normalized features and hard/smoothed training labels to `float`.
- Means, variances, weighted rates, and macro metrics use fixed-order Neumaier compensation.
- Use `StrictMath.sqrt`, `StrictMath.log`, and `StrictMath.log1p` where specified.
- Exact target and tie comparisons use `Double.compare`; no general epsilon exists.
- `1.0e-12` is only the normalizer scale floor.
- `1.0e-9` is only the early-stopping metric tie threshold.
- `1.0e-15` is only the permitted last-bit negative ordinal-mass cleanup.
- Acceptance deltas are model-quality thresholds, not floating-point equality tolerances.
- Missing metrics use `OptionalDouble`; NaN and infinity are never serialized as missing.

## File-by-file implementation checklist

Implement in this dependency order.

1. Add the immutable records and enums under
   `euhedral-training/src/main/java/io/euhedral_execution/training/learning/`:
   `ScenarioFeatureSet.java`, `FeatureSelectionMode.java`, `ModelAcceptanceStatus.java`,
   `EvaluationStatus.java`, `LearningPartition.java`, `ScenarioInputs.java`,
   `ScenarioLearningRow.java`, `ScenarioDatasetAudit.java`, `ScenarioLearningTable.java`,
   `PolicyGroupedSplit.java`, `FeatureNormalizer.java`, `ScenarioLearningMatrix.java`,
   `OrdinalDistribution.java`, `EnsembleOrdinalDistribution.java`,
   `ScenarioTrainingConfig.java`, `EvaluationThresholds.java`,
   `ScenarioTrainingRequest.java`, `ScenarioTrainingArtifacts.java`,
   `ScenarioPrediction.java`, `PolicyPredictionCurve.java`,
   `ScenarioEvaluationMetrics.java`, `EvaluationSummary.java`,
   `InsufficientScenarioLearningDataException.java`, and `AblationMetric.java`.
2. Add `ScenarioLearningReader.java` and package-private `LearningCsvReader.java`. Join both
   vector dictionaries before parsing scenario rows. Implement the in-memory overload and SHA-256
   table fingerprint in the same ownership boundary.
3. Add `PolicyGroupedSplitter.java`. Implement stable 80/10/10 groups, the validation ablation
   halves, minimum checks, and helpers that build LOSO/LOEO row sets without copying policy
   vectors.
4. Add `FeatureNormalizer.java` and `ScenarioFeatureEncoder.java`. Implement the three exact
   feature schemas, de-duplicated training-only moments, scenario-balanced row weights, and flat
   matrices.
5. Add `ScenarioOrdinalTargets.java`, immutable distribution records, PAV, fixed target encoding,
   member distribution decoding, and ensemble aggregation before any DJL code.
6. Add package-private `OrdinalMember.java`, `DeterministicBatchSampler.java`,
   `BalancedScenarioOrdinalLoss.java`, and `ScenarioOrdinalNetwork.java` under `training/learning`.
   Do not import the pooled `PolicyOrdinalNetwork` or `PolicyRanking`.
7. Add `ScenarioModelEvaluator.java`. Implement exact midrank Spearman, top-decile selection,
   macro/micro metrics, status handling, grouped evaluation, LOSO, and validation-only LOEO.
8. Add `ScenarioModelMetadata.java`, `MemberMetadata.java`, `PartitionCounts.java`,
   `FeatureSelectionDecision.java`, `EvaluationSummaryMetadata.java`,
   `ProducerMetadata.java`, `MetadataProbe.java`, `ScenarioModelMetadataCodec.java`, and
   `ScenarioLearningReportWriter.java`. Implement the exact JSON fields, raw-bit arrays, four CSV
   schemas, member SHA-256 validation, and deterministic ordering.
9. Add `ScenarioConditionedModel.java`. Implement strict load/load-for-audit, batched whole-curve
   inference, sequential member aggregation, metadata probe validation, and close semantics.
10. Add package-private `ScenarioFoldRunner.java` and public `ScenarioModelTrainer.java`.
    Orchestrate strict input, split, validation-only ablations, selected-schema training,
    grouped/LOSO test evaluation, acceptance, member save/checksums, reports, metadata, re-open
    validation, and atomic publication.
11. Do not edit `DataMerger.java`, `MergeRecords.java`, `MergeCsvWriter.java`, Phase 1 codecs, or
    Phase 1 tests. Their persisted contract is an input.
12. Do not edit `SequenceFinder.java`, `PolicyOrdinalNetwork.java`, `PolicyRanking.java`,
    `CmaEsOptimizer.java`, `ScoreBandSampler.java`, `ClosedLoopRunner.java`,
    `BenchmarkRunner.java`, or `Runner.java`. Phase 3 migrates callers.
13. Do not edit `pom.xml`, module descriptors, the current training README, architecture docs,
    current-workspace inputs, outputs, or models.
14. Add only the focused tests and small golden metadata resource below. Prompt 2B appends its
    completion record to this blueprint.

## Deterministic tests and fixtures

Add
`euhedral-training/src/test/java/io/euhedral_execution/training/learning/fixtures/ScenarioLearningFixtures.java`.
It creates Phase 1-shaped CSVs and in-memory rows under `@TempDir`; it never reads the workspace
input/output trees.

Its main fixture has 160 policies, four required exact scenarios, two environments, and ratios
`0.25`, `0.50`, `0.75`, and `1.00`. Policy quality changes ordering with ratio:

```text
latent(policy, ratio) =
    (1 - ratio) * policy.weight(0)
    + ratio * policy.weight(1)
    + 0.20 * (2 * ratio - 1) * policy.weight(2)
```

Within each exact scenario, convert latent values to the exact Phase 1 midrank quality rule. This
creates policies whose quality curves cross, so a policy-only predictor is an inadequate negative
control. Fixed deterministic fake ordinal members may encode this formula for non-DJL unit tests.

### Reader and Phase 1 compatibility tests

`ScenarioLearningReaderTest` must:

- join eligible and incomplete vector dictionaries and recover all raw weight bits;
- produce exactly one sorted included row per strong policy/required scenario;
- exclude weak rows by default and include them only under the explicit flag;
- audit missing, invalid, weak, and non-required rows without turning them into targets;
- return the same table and fingerprint for shuffled file rows;
- reject a missing vector, duplicated policy across vector files, changed policy bits, duplicate
  scenario row, missing Cartesian row, wrong canonical scenario, unknown/mixed schema, changed
  header, non-finite target, and a legacy alternating text file; and
- produce the same learning table from equivalent in-memory `ScenarioResult` records.

### Split and leakage tests

`PolicyGroupedSplitterTest` must:

- prove every row for one `PolicyId` has one partition across all four scenarios;
- prove train, validation, test, and both validation ablation halves have disjoint policy sets;
- assert exact bucket assignments for at least ten fixed `p1` IDs and the default seed;
- produce identical assignments for shuffled rows;
- prove adding a new policy or scenario does not move an existing policy;
- prove every LOSO fold contains no held-out exact scenario in fit or early-stop rows;
- prove every LOSO score policy is absent from fitting and early stopping;
- prove every LOEO fit/early-stop row excludes the held environment;
- reject insufficient group counts, missing per-scenario partitions, constant targets, and no
  top-decile target rather than falling back to a row split.

### Feature and target tests

`ScenarioFeatureEncoderTest` must:

- assert exact feature names, widths 28/29/31, raw order, and matrix lengths;
- prove policy coordinates are fitted once per distinct training policy and context coordinates
  once per distinct exact training scenario;
- prove validation/test outliers do not change means or scales;
- verify ratio and `StrictMath.log1p` count features exactly;
- verify constant features use scale `1.0` and encoded `+0.0`;
- prove equal ratios with different environment IDs encode identically under `RATIO_ONLY`;
- prove equal ratios with different counts differ only in indices 29 and 30 under
  `RATIO_AND_COUNTS`;
- prove no policy-vector mutation, L2 normalization, or clipping occurs; and
- assert every scenario's row weights sum to the same value.

`ScenarioOrdinalTargetsTest` must:

- assert labels immediately below, at, and immediately above all nine thresholds;
- assert `q=0` is all zero and `q=1` is all one;
- project crossing probabilities with exact equal-weight PAV;
- decode one-hot ordinal bins to centers `0.05` through `0.95`;
- verify masses are non-negative and sum to one;
- verify lower inverse-CDF 2.5/97.5 endpoints, normalized entropy, and top-decile probability;
- combine three known members and assert predicted quality, sample epistemic standard deviation,
  disagreement range, and compensated member order; and
- reject NaN, infinity, wrong widths, materially negative mass, and non-monotonic post-projection
  state.

### Evaluator and ablation tests

`ScenarioModelEvaluatorTest` must:

- calculate exact MAE, RMSE, signed bias, interval coverage, and uncertainty means;
- assign midranks to exact target and prediction ties;
- leave Spearman blank for a constant rank;
- select exactly `ceil(0.10 * N)` predictions with the settled uncertainty/ID tie-break;
- treat actual `q >= 0.9` as top decile;
- give each exact scenario one macro vote despite unequal row counts;
- produce byte-identical metrics for shuffled input records; and
- enforce every default acceptance boundary with paired just-below/at/just-above fixtures.

`ScenarioAblationPlanTest` uses recording fake trainers and must prove:

- `POLICY_ONLY` receives width 28 and is never selectable;
- the context gate uses only the two validation halves, never test policies;
- the counts gate removes every scenario from the held environment;
- `AUTO_COUNTS_IF_VALIDATED` selects counts exactly at the improvement boundaries and otherwise
  selects ratio-only;
- `REQUIRE_COUNTS` becomes rejected on the same failed gate; and
- all fold seeds are stable and distinct by feature set, fold ID, and member index.

### Inference and serialization tests

`ScenarioConditionedModelTest` uses injected deterministic `OrdinalMember` implementations and
must:

- show the same policy receives different predictions at different ratios;
- show `POLICY_ONLY` gives the same prediction for every scenario;
- preserve caller policy order and natural scenario order;
- produce identical output for one large batch and curve-aligned small batches;
- aggregate members in member-index order;
- retain finite uncertainty and disagreement without scalarizing them;
- reject duplicate policy IDs, an empty scenario set, invalid batch size, and use after close; and
- close every member exactly once after a member failure.

`ScenarioModelMetadataCodecTest` and the tiny golden resource
`src/test/resources/robust-training/v1/scenario-model-metadata.json` must:

- round-trip every field and raw normalizer bit;
- emit byte-identical JSON and CSV reports from shuffled logical inputs;
- validate member SHA-256 values and the metadata probe;
- reject missing/duplicate fields, malformed JSON escapes, trailing data, wrong feature names or
  widths, changed thresholds, unknown schema/objective, wrong member property, checksum mismatch,
  missing member, and a rejected model through normal `load`; and
- reject a metadata-less pooled DJL directory with the required retraining diagnostic.

### Optional DJL integration smoke

Add `ScenarioOrdinalNetworkIntegrationTest`, guarded by
`-Dtraining.djlIntegration=true`, because ordinary CI may not have compatible PyTorch native
libraries. With the flag and a working CPU/GPU engine it:

- trains one package-private test member for at most five epochs on the deterministic fixture;
- asserts `[R,F] -> [R,9]` shape;
- saves and reloads the member;
- asserts reloaded logits and decoded predictions within `1.0e-6`; and
- verifies the context feature changes predictions for the crossing-curve fixture.

The default suite must prove feature propagation, curve decoding, split isolation, and artifact
semantics without loading a native engine. Prompt 2B must run the optional smoke when the local
PyTorch environment is available and report an exact native-library limitation otherwise.

## Prompt 2B acceptance criteria

Prompt 2B is complete only when:

- only strict Phase 1 v1 records enter the new learner;
- vector bits are joined and validated without changing Phase 1 files;
- every learning row is one valid policy/exact-scenario target;
- required scenarios are explicit and deterministically ordered;
- baseline input is exactly 28 weights plus normalized ratio;
- absolute counts remain gated by held-out-environment evidence;
- environment ID is never encoded;
- normalization is training-only, persisted, unclipped, and de-duplicated by policy/scenario;
- all rows for a policy remain in one primary partition and one validation ablation half;
- fixed ordinal labels come directly from Phase 1 quality;
- exact scenarios receive equal total training weight;
- grouped and LOSO evaluations use disjoint policy groups and no held-out scenario fitting data;
- final test data does not select features, thresholds, normalization, epochs, or hyperparameters;
- inference emits the full scenario curve plus ordinal uncertainty and ensemble disagreement;
- no scalar score replaces the curve;
- model artifacts are versioned, checksummed, self-describing, atomically published, and strict on
  load;
- pooled models and legacy files are explicitly incompatible rather than migrated;
- deterministic input permutations produce identical table fingerprints, splits, metadata, and
  report bytes;
- focused tests prove source context affects inference and policy rows cannot leak across splits;
  and
- no scheduler, optimizer, benchmark, Phase 1, workspace-data, CLI, or user-facing documentation
  file is changed; this blueprint, its completion record, and its conformance report are the only
  permitted planning-documentation changes.

## Validation commands for Prompt 2B

From the repository root:

```bash
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -am install -Dmaven.test.skip=true

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test

git diff --check
git status --short
```

When compatible PyTorch native libraries are configured:

```bash
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training \
    -Dtraining.djlIntegration=true \
    -Dtest=ScenarioOrdinalNetworkIntegrationTest test
```

Run these targeted searches:

```bash
rg -n "PolicyRanking|mergeQuantiles|BenchmarkOutputReader|TDigest|quantile\\(0\\.99\\)" \
  euhedral-training/src/main/java/io/euhedral_execution/training/learning

rg -n "input/merger|output/|iteration-.*source|graviton|zen4|euhedral-policy-ranker|\\.bin" \
  euhedral-training/src/main/java/io/euhedral_execution/training/learning

rg -n "environmentId.*feature|feature.*environmentId|one.?hot" \
  euhedral-training/src/main/java/io/euhedral_execution/training/learning
```

The first two searches must return no new-path matches other than a diagnostic string explicitly
rejecting the pooled model. The third may match LOEO grouping code but must not match feature
encoding.

Also inspect:

```bash
git diff --name-only
git diff -- docs/robust-training-optimizer/blueprints/02-scenario-conditioned-learning.md
```

During Prompt 2B the first command may list only the new Phase 2 source/tests/resource and this
blueprint completion record, in addition to pre-existing user-owned workspace changes.

## Risks and later-phase handoff

There is no unresolved Phase 2 statistical or architectural blocker.

The following are deliberate handoff facts:

- Phase 3 receives `PolicyPredictionCurve` in caller policy order with naturally ordered exact
  scenarios. It must apply its settled robust predicted comparator and must use uncertainty and
  disagreement as separate scheduling inputs.
- Phase 3 must migrate `SequenceFinder`, CMA-ES scoring, source enumeration, and the closed loop as
  one coherent change. It must not add a 28-value scalar adapter to this model.
- Phase 3 must persist the exact required-scenario catalog and reject a model whose metadata catalog
  disagrees with scheduler state.
- Phase 4 can copy this model directory under `model/`; member and metadata checksums are already
  available, but the package manifest remains authoritative for package-level checksums.
- Phase 5 maps final CLI/configuration keys to `ScenarioTrainingConfig`. It must not expose
  `POLICY_ONLY` as a deployable mode or invent environment features.
- Phase 7 removes the pooled `PolicyOrdinalNetwork`, `PolicyRanking` training labels, alternating
  dataset reader, scalar scoring path, and old model diagnostic only after Phase 3 has no callers.

Prompt 2B must append completion notes below this line with changed files, commands, results,
acceptance status of deterministic fixtures, optional DJL smoke status, and any deviation. A
deviation that changes features, target thresholds, split grouping, evaluation folds, uncertainty,
acceptance gates, model schema, or Phase 1 compatibility requires another reasoning pass.

## Prompt 2B completion notes

Implementation completed on 2026-07-27.

Scope:

- Added the Phase 2-only scenario-conditioned ordinal learning package under
  `euhedral-training/src/main/java/io/euhedral_execution/training/learning/`.
- Added deterministic Phase 2 tests and fixtures under
  `euhedral-training/src/test/java/io/euhedral_execution/training/learning/`.
- Added the golden metadata resource
  `euhedral-training/src/test/resources/robust-training/v1/scenario-model-metadata.json`.
- Updated this blueprint's Phase 1 compatibility text and completion notes.
- Did not edit Phase 1 merger code/tests, optimizer, scheduler, benchmark, pooled-model,
  workspace-data, POM, module descriptor, CLI, or runtime files.

Phase 1 compatibility resolution:

- The reader validates `robust-leaders.vectors.csv` in Phase 1 robust-rank order.
- The reader validates `incomplete-policies.vectors.csv` in Phase 1 valid-count descending,
  observed-count descending, unsigned `PolicyId` tie-break order.
- After those persisted Phase 1 contracts are validated, Phase 2 stores the joined policy registry
  in unsigned `PolicyId` order for deterministic learning. Rank and count fields are audited only;
  `quality` remains the sole learning target.

Implemented checklist:

- Strict scenario-conditioned ordinal data ingestion from the three explicit Phase 1 CSV paths,
  including schema/header/order/count/fingerprint checks and a fatal policy-scenario Cartesian-grid
  requirement for required scenarios.
- Predictor inputs include the 28 raw policy weights plus normalized scenario context features;
  no environment ID feature or hardware one-hot is used.
- Predictor outputs are monotonic ordinal distributions decoded to quality curves with ensemble
  uncertainty and member-disagreement summaries.
- Policy-grouped train/validation/test splits, grouped validation, and leave-one-scenario-out /
  leave-one-environment-out fold reports prevent measured rows for one policy from crossing split
  boundaries.
- Metadata records producer/device/framework, config, seeds, feature schema, normalizer, scenario
  catalog, partition counts, acceptance gates, ablations, fold reports, training history, and model
  probes. Accepted metadata is fail-closed against the configured thresholds.
- Configured ablations require paired fold identities for source context and exact scenario-count
  coverage for the count ablation.

Deterministic fixture acceptance:

- The deterministic source-context test passes and verifies that changing source context changes
  predictions for the same policy.
- The grouped split tests pass and verify that policy rows cannot leak across primary splits,
  validation-ablation halves, leave-one-scenario-out folds, or leave-one-environment-out folds.
- Sparse curve evaluation now retains only measured rows before joining, so fold reports remain
  strict without requiring synthetic predictions for unmeasured policy-scenario pairs.

Commands run:

```text
env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -Dtest=ScenarioModelMetadataCodecTest,ScenarioConditionedModelTest,ScenarioLearningReaderTest test
  -> BUILD SUCCESS; 16 tests, 0 failures, 0 errors

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -am install -Dmaven.test.skip=true
  -> BUILD SUCCESS

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training test
  -> BUILD SUCCESS; 86 tests, 0 failures, 0 errors, 1 skipped

env JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
    PATH=/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
    /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
    -B -pl euhedral-training -Dtraining.djlIntegration=true -Dtest=ScenarioOrdinalNetworkIntegrationTest test
  -> BUILD SUCCESS; 1 test, 0 failures, 0 errors, 0 skipped
```

Stale-reference checks:

- No Phase 2 learner/test references to `PolicyRanking`, `mergeQuantiles`,
  `BenchmarkOutputReader`, `TDigest`, or `quantile(0.99)`.
- No Phase 2 learner/test references to workspace `input/merger` or `output` paths, source-specific
  host names, the pooled `euhedral-policy-ranker` artifact, or serialized `.bin` model files. The
  `.bin` search only matched the in-memory `binMasses` method name.
- No environment-ID feature or hardware one-hot feature is present; the remaining `one-hot` matches
  describe ordinal bins and this validation command.

Deviation/blocker status:

- No unresolved blocker remains. The only compatibility issue discovered during implementation was
  resolved within Phase 2 by validating native Phase 1 vector ordering before creating Phase 2's
  unsigned-ID policy registry.

### Conformance correction - 2026-07-27

- Removed the non-atomic publication fallback from `ScenarioModelTrainer.publish`. A filesystem
  that cannot provide `ATOMIC_MOVE` now fails publication; the existing failure path removes the
  temporary artifact and leaves the target absent.
- Clarified that the blueprint, completion record, and conformance report are permitted planning
  documentation changes while user-facing documentation remains outside Phase 2 scope.
- Updated the blueprint status after the repeated validation and conformance audit passed.

### Repackage compatibility addendum (2026-07-29)

The Phase 2 class names and contracts remain valid. The original flat `training.learning` checklist
now resolves through these subpackages: `config`, `data`, `enums`, `inputs`, `metadata`, `output`,
`statistics`, and `utils`. `ScenarioOrdinalNetwork`, `ScenarioConditionedModel`,
`ScenarioModelEvaluator`, `ScenarioFoldRunner`, `ScenarioModelTrainer`, and `OrdinalMember` remain
directly in `io.euhedral_execution.training.learning`; `PartitionCounts` is the shared
`io.euhedral_execution.training.data.PartitionCounts` type. Test support remains at
`io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures`, and the opt-in
`ScenarioOrdinalNetworkIntegrationTest` remains in `io.euhedral_execution.training.learning`.

No learning feature, metadata, persistence, memory, or precision rule changed; later phases must
use these package locations rather than recreating flat-package imports.
