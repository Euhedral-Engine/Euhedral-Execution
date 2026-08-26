# Productivity participation Python training plan

## Purpose and decision

This document defines the minimum path from the current calibration harness to an external Python
trainer for the eight CACHE-participation coefficients. The trainer predicts one question:

> From the current state with ranks `1..K` participating upstream, is system performance better if
> rank `K` remains an upstream participant, or if it switches to CACHE so that only ranks `1..K-1`
> participate upstream?

The implementation should reuse the existing Java harness for execution and evidence collection.
Python should only load compatible artifacts, form adjacent `K` versus `K-1` preferences, fit the
existing eight-coefficient equation, save/load the result, and export the Java record fields.

No new scheduler model, benchmark framework, runtime Python dependency, or broad Cartesian campaign
is required. The model is treated as eligible over the full observed contention range.

## Current runtime inference semantics

The relevant implementation is in
[
`FragmentDecisionTree`](../../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/FragmentDecisionTree.java),
[
`ControlPlaneFragment`](../../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java),
[
`UpstreamQueue`](../../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/UpstreamQueue.java),
and [
`LatticeEdge`](../../euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeEdge.java).

### Exact CACHE behavior

The automatic production CACHE path is guarded by the JVM property
`euhedral.fragment.cacheExecutePath`, whose default is `false`. The benchmark-only forced cutoff
bypasses that feature flag for ranks above the cutoff. When the decision tree returns `CACHE`, one
fragment cycle behaves as follows:

1. CACHE selection is previewed before ordinary idle selection; a CACHE worker does not take the
   ordinary idle-policy park.
2. Local cached frames are drained and executed.
3. The execution path is selected. A forced CACHE rank remains CACHE even without body history or
   upstream handles.
4. Remote routing caches are pulled and executed for every path, including CACHE.
5. CACHE does not call the direct upstream pull, upstream request, or request-and-pull paths.
6. If CACHE found no local or remote cached work, it immediately parks for the configured
   `cacheParkNs`, currently `15_000 ns` by default.
7. If CACHE executed at least one cached frame, the loop records progress and continues without the
   CACHE miss park.

Consequently, CACHE is cache-only with respect to upstream acquisition, but it is not inactive. Both
worker-local cache work and remotely routed cache work remain executable. Remote-cache pulls walk
routing-cache parents; they do not call `UpstreamQueue`. Only DIRECT and STAGED reach the
high-contention upstream handle domain.

DIRECT and STAGED retain their existing bounded miss behavior: 64 misses spin and later misses park
for `1_000 ns`, with the streak reset by productive execution. CACHE has a separate, explicit
miss-only park. The completed fixture persists `cacheParkNs` and `cacheActuatorVersion=cache-v1`, so
training data must not pool this actuator with legacy or differently configured loop semantics.

### Which ranks acquire upstream

In automatic mode, each worker evaluates the marginal rule independently. Rank 1 is guarded from
CACHE. Any rank greater than 1 for which the marginal is positive may select CACHE; all other ranks
retain the ordinary DIRECT/STAGED choice. The code does not enforce that participating ranks form a
prefix.

For calibration, Step 1 adds the following treatment:

```text
rank <= forcedActiveParticipantCount:
    use the normal current DIRECT/STAGED decision

rank > forcedActiveParticipantCount:
    force the exact current CACHE actuator
```

All ranks remain registered and alive. Ranks above the cutoff still drain local and remote caches.
The cutoff is accepted only in benchmark mode, must be positive, and is checked against the resolved
physical worker count before a trial starts. Ranks at or below the cutoff cannot be automatically
withdrawn, so the treatment forms an exact participation prefix. This is not represented by
removing CPUs, reducing registered workers, or by the old `ProductivityGateMode.FORCE_ON` treatment.

### Runtime input signals

`contention` is owner-local upstream-handle acquisition contention. In each eligible
`UpstreamQueue.pull`, it is calculated as

```text
floor(failed handle-lock acquisitions * 1_000_000 / acquisition attempts)
```

and then passed through a fixed-point EWMA with divisor 16. The first observation is installed
directly. `getContention()` returns zero before the first observation. The decision tree converts
the fixed-point value to `[0, 1]` by division by `1_000_000.0`.

Because CACHE does not acquire upstream handles, its contention value receives no new observations
while it remains in CACHE. It therefore becomes stale rather than describing a counterfactual newly
admitted participant. `contention_staleness.tsv` already exposes observation count and age, which is
sufficient to detect this condition.

`smoothedBodyCostNs` is a worker-local sparse measurement around the terminal
`LatticeHotSource.push`. The stopwatch starts before terminal push and stops after it returns, so
the sample includes the executor/body work plus terminal completion overhead in that call. The
default stopwatch takes one sample per 256 accepted frames. The decision tree collects 32 sparse
samples and uses the second-smallest value from each non-overlapping window. A value in the most
expensive body region requires two consecutive confirming windows. Despite its name, this signal is
not an EWMA. The CACHE model is not consulted until 32 body samples exist.

`productiveHandles` is also worker-local. It is the number of live upstream handles that this worker
most recently considered productive. New handles start optimistic; successful useful pulls or
synchronous request pushes supply positive evidence; an acquired empty service supplies negative
evidence; a failed handle-lock acquisition supplies no productivity evidence.

`registeredWorkers` is the number of registered workers. The exact decision call uses
`state.registeredWorkers`, refreshed at the preceding completed-batch boundary. It is not replaced
by the active participant cutoff or by `productiveHandles / K`.

`workerRank` is one-based. `LatticeEdge.rankCores()` assigns all active registered P-cores first in
ascending physical-core ID, followed by non-P cores in ascending physical-core ID. It is an ordinal
among registered cores, not a measured active-upstream count. Interpreting rank `K` as candidate
active population `K` is valid only when participation is a rank prefix. The forced calibration
treatment provides that condition by construction.

The decision call obtains productive handles, contention, and rank directly at the decision point,
while registered workers has the completed-batch timing described above. Step 1 passes those exact
decision values into `contention_staleness.tsv` and adds `bodyHistoryReady`; the export no longer
substitutes the fragment's previous completed-batch values for `P`, `R`, or rank.

### Guards and edge cases

| Condition                      | Current behavior                                                         | Training implication                                                                        |
|--------------------------------|--------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| no upstream handles            | Normal ranks use `SKIP_THEN_DIRECT`; forced-above-cutoff ranks remain CACHE | Not a fitted observation. Preserve as actuator validation.                               |
| `registeredWorkers <= 1`       | DIRECT                                                                   | Not a fitted observation.                                                                   |
| `workerRank <= 1`              | CACHE is disallowed                                                      | `K=1` is not a trainable withdrawal decision; the first adjacent pair is `K=2` versus 1.    |
| `productiveHandles <= 0`       | CACHE immediately when the feature is enabled and earlier guards pass    | This bypasses all eight weights. Keep as a guard test, not a coefficient-fitting row.       |
| fewer than 32 body samples     | Normal ranks use DIRECT; forced-above-cutoff ranks remain CACHE           | Exclude the window/pair until body history is ready.                                        |
| no contention history          | contention reads as zero                                                 | The active `K` training context must have an actual observation; reject uninitialized rows. |
| CACHE for a sustained interval | contention stops refreshing                                              | Retain as diagnostic state, but do not use it as if it were an action-independent input.    |

These guards do not invalidate the eight-weight equation for eligible observations. The two
substantive contract risks are actuator identity and prefix behavior, both of which are addressed by
the validation and acceptance rules below.

## Exact eight-weight model

For an eligible rank `K > 1`, define:

```text
c = contention in [0, 1]
b = log1p(smoothedBodyCostNs)
P = productiveHandles
R = registeredWorkers

A = w0 + w1*c + w2*b + w3*R
B = w4 + w5*c + w6*b + w7*R

marginal(K) = A*P/(K*(K-1)) - B
```

The current decision is `CACHE` exactly when `marginal(K) > 0`. Otherwise ordinary DIRECT/STAGED
selection remains available.

The logical coefficient order and Java export names are:

| Logical coefficient | Java `ParetoWeights` field      | Role                                  |
|---------------------|---------------------------------|---------------------------------------|
| `w0`                | `phrWeight`                     | intercept of `A`                      |
| `w1`                | `contentionPhrWeight`           | contention contribution to `A`        |
| `w2`                | `bodyPhrWeight`                 | log-body contribution to `A`          |
| `w3`                | `registeredWorkersPhrWeight`    | registered-worker contribution to `A` |
| `w4`                | `activeWorkersWeight`           | intercept of `B`                      |
| `w5`                | `contentionWorkersWeight`       | contention contribution to `B`        |
| `w6`                | `bodyWorkersWeight`             | log-body contribution to `B`          |
| `w7`                | `registeredActiveWorkersWeight` | registered-worker contribution to `B` |

The record declaration order is not the logical equation order. Export must use field names and an
explicit mapping, not positional serialization.

### Equivalence to a two-score comparison

For the same current `c`, `b`, `P`, and `R`, consider the unreduced score

```text
score(N) = A*(P/N) + B*N
```

Then, for `K > 1`:

```text
score(K-1) - score(K)
    = A*P/(K*(K-1)) - B
    = marginal(K)
```

The reduced inference is therefore exactly equivalent to comparing those two scores, provided both
scores use the same current observation. It is not equivalent if one score uses `state_K` and the
other uses `state_K_minus_1`; those are different post-action physical states.

A common additive bias in both scores is mathematically redundant because it cancels. None of the
eight displayed terms is structurally redundant when `c`, `b`, `P`, `R`, and `K` vary. There are two
identifiability qualifications:

- If training only uses the sign, multiplying all eight weights by the same positive constant does
  not change decisions. Weighted logistic loss plus a fixed L2 penalty supplies a deterministic
  scale convention.
- Within a dataset having only one `R`, each factor's intercept and registered-worker coefficient
  are confounded. Multiple registered-worker counts are required to identify `w3` and `w7`.

### Prefix behavior is not guaranteed

For fixed inputs, write `m(K) = A*P/(K*(K-1)) - B`. If `A > 0`, `m(K)` decreases with rank. Thus the
specific pattern

```text
rank 8 -> CACHE
rank 9 -> DIRECT or STAGED
```

is possible whenever `A*P/72 <= B < A*P/56`. If `A < 0`, the marginal increases with rank, which is
compatible with a suffix of higher ranks selecting CACHE. Since inputs are worker-local,
rank-to-rank signal differences can create additional non-prefix patterns.

This is a concrete limitation of the current mathematical form, but it does not yet justify a new
model. Prefix behavior should be a hard held-out acceptance check for fitted coefficients. If the
eight-weight fit cannot satisfy it over the validated physical domain without losing material winner
accuracy, reject the fit and revisit the model separately; do not repair decisions with an untrained
runtime postprocessor.

## A/B experiment semantics

One experimental unit is a matched adjacent pair under an otherwise identical fixture:

```text
arm A: forcedActiveParticipantCount = K
arm B: forcedActiveParticipantCount = K-1
```

In both arms, all `R` workers stay registered. In arm A, ranks `1..K` use normal DIRECT/STAGED and
ranks above `K` use CACHE. In arm B, ranks `1..K-1` use normal DIRECT/STAGED and rank `K` joins the
higher ranks in CACHE. Only rank `K` changes its upstream participation eligibility between the two
arms.

Step 1 implements this through `CalibrationBenchmarkConfig` and the existing sweep/treatment
plumbing. The nullable `forcedActiveParticipantCount` is passed as an authoritative benchmark JVM
property, classified as `POLICY`, and persisted in the expanded `trial_config.json`. Do not reuse
`ProductivityGateMode.FORCE_ON`: that old threshold gate is currently disconnected from the main
cycle and its historical behavior is not the current CACHE actuator.

Use CONTINUOUS lifecycle for authoritative labels. RESET remains useful for diagnostics but must not
be pooled into the same training unit. Use independent JMH forks as replications. Measurement
iterations/windows inside one persistent JVM describe a trajectory and are not independent
replicates. Use the existing repeated-sweep balanced order. The implementation is complementary
forward/reverse order with rotating starts; it is suitable for countering linear machine drift, but
should not be described as a general arbitrary Williams design.

## Winner and confidence construction

Throughput remains the objective. Variance and trajectory determine whether the throughput
difference is repeatable and settled; they are not separate optimization objectives. Acquisition,
cache, and occupancy fields are explanatory diagnostics only.

### Uncertainty-adjusted comparison rule

The harness already compares independent fork means. For arms A and B it calculates:

```text
delta = mean_B - mean_A
uncertainty = 2*sqrt(variance_A/n_A + variance_B/n_B)
practical = 0.01*max(mean_A, mean_B)
margin = uncertainty
```

It reports B better if `mean_B - uncertainty > mean_A`, and A better if
`mean_A - uncertainty > mean_B`. This is equivalent to `delta > uncertainty` and
`delta < -uncertainty`. Variance has no separate hard cutoff, and the one-percent practical band
does not suppress a winner whose uncertainty-adjusted mean remains higher. If neither policy wins,
the practical band can still identify a stable tie; otherwise the result is inconclusive. The
Python loader reproduces this rule from the exported means, variances, and fork counts.

### Minimal trajectory qualification

Use a fixed, versioned rule rather than selecting a favorable interval after seeing results:

1. The whole-measurement fork means and existing comparison outcome are the primary summary.
2. For each fork, define the late region as the final half of ordered measurement windows, with at
   least three windows. Compute one late mean per fork. Windows remain correlated and are not added
   to the replication count.
3. Require every late region to remain continuously fed. Retain CV as an auditable dispersion
   diagnostic, not an eligibility cutoff. A fork is stable when its least-squares slope is within
   one percent of its late mean per window and improving when its slope is above that band.
4. Classify the policy trajectory from the mean normalized slope across its independent forks. It
   is eligible when the aggregate slope is stable or improving, meaning it is at least negative one
   percent of the late mean per window. Individual noisy forks remain in the aggregate.
5. Apply the uncertainty-adjusted comparison rule to the per-fork late means.
6. If whole-run and late outcomes name the same winner, use that winner when the winning policy's
   aggregate trajectory is stable or improving. The losing policy's trajectory is not a veto. If
   the whole run is non-decisive but the late result names an eligible winner, use it with reduced
   confidence. Decisive conflicts, starvation, insufficient windows, or a declining winning-policy
   trajectory remain inconclusive. A stable equivalent result is a tie.

This gives the requested examples the intended treatment:

- Case A is a high-confidence `K` winner.
- Case B is a tie or inconclusive, never a strong `K-1` label.
- Case C can become a reduced-confidence `K` winner because the fixed late region captures its
  settled state without treating early windows as independent failures.
- Case D is inconclusive or low confidence because across-fork variance inflates the comparison
  margin; the occasional high fork does not dominate a pooled mean.

### Training label and weight

Use `y = 0` for `K` wins (participate), `y = 1` for `K-1` wins (CACHE), and `y = 0.5` for an
effective tie. Inconclusive pairs receive no training row.

For a decisive pair, define a simple separation weight:

```text
separation = max(0, abs(delta) - margin) / margin
pairWeight = min(1, separation / 2)
```

This is zero at the decision boundary and reaches one when the observed delta is at least three
times the governing margin. The governing margin is the across-JVM throughput uncertainty.
Multiply by `1.0` for whole/late agreement or `0.5` for a qualified convergence-only winner.

For a stable equivalent pair, use:

```text
pairWeight = max(0, 1 - abs(delta)/practical)
             * max(0, 1 - uncertainty/practical)
```

This gives high weight only to a small, repeatable difference. Persist the intermediate delta,
uncertainty, practical margin, stability status, and final weight so the label is auditable. Do not
let telemetry diagnostics modify the weight except as validity checks for missing or stale model
inputs.

## Action-dependent contention and body observations

The selected approach is one directional training observation per adjacent pair, using the state
from the `K` arm.

The reason is causal and matches runtime inference: while ranks `1..K` currently participate, rank
`K` observes `state_K` and asks whether to withdraw. The A/B outcome estimates the consequence of
that withdrawal. `state_K_minus_1` is a post-treatment state. Using its contention or body value as
the other half of one feature vector, or averaging the two states, would condition the decision on
its own consequence.

For each accepted pair:

```text
features = state_K for the rank-K worker
label = preference from throughput_K versus throughput_K_minus_1
weight = pair confidence
```

Still retain `state_K_minus_1` in the pair record. It confirms that the actuator changed contention
and helps explain trajectory behavior, cache execution, and treatment failures. It is not fed into
the initial eight-weight fit.

This deliberately trains the withdrawal decision, not an independently identified admission decision
from CACHE. A cached rank's contention is stale by construction, so the existing evidence cannot
cleanly identify a symmetric `K-1 -> K` admission decision. The first implementation should not
invent an average, a counterfactual sensor, or an exploration policy. Production benchmark
verification must check for oscillation or failure to re-admit. If re-entry proves material, it is a
separate runtime-observation problem rather than a reason to contaminate this initial dataset.

For `state_K`, aggregate only steady, body-ready samples for worker rank `K`. Use a deterministic
per-fork median for contention, body cost, and productive handles within the fixed late region, then
take the median across forks. This prevents the much denser per-cycle telemetry from pretending to
be independent replication. Reject a pair if rank `K` has no initialized contention observation, no
body history, non-finite values, changing `R`, or materially changing `P` within its late region.

## Existing harness capability inventory

| Requirement                 | Existing support                                                                        | Existing artifact/config field                                                               | Missing or insufficient piece                                               | Minimum required change                                                             |
|-----------------------------|-----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| Independent JVM trials      | ALREADY EXISTS: JMH forks and fork-aware parsing                                        | `TrialConfig.forks`, `ThroughputResult.forkScores`                                           | None                                                                        | Reuse fork means as replicates.                                                     |
| CONTINUOUS lifecycle        | ALREADY EXISTS: persistent scheduler/caches/source position across windows              | `lifecycleMode=CONTINUOUS`, `trajectory_windows.tsv`                                         | None                                                                        | Make CONTINUOUS mandatory for labels.                                               |
| RESET lifecycle             | ALREADY EXISTS: reset around windows                                                    | `lifecycleMode=RESET`                                                                        | Not suitable for authoritative trajectories                                 | Retain only as diagnostic.                                                          |
| Imports                     | ALREADY EXISTS: recursive namespaced imports with cycle checks                          | `imports`, `ProfileLibraryLoader`                                                            | None                                                                        | Reuse.                                                                              |
| Preset composition          | ALREADY EXISTS: calibration and decision-weight profiles                                | `calibrationProfile`, `decisionWeightProfile`                                                | None                                                                        | Reuse.                                                                              |
| Balanced ordering           | ALREADY EXISTS: complementary forward/reverse repeated-sweep order with rotating starts | `runOptions.balancedTrialOrder`, `TrialOrigin` sample/candidate indices                      | Requires one repeated sweep; not general Williams generation                | Represent each adjacent pair as the candidates of one repeated sweep.               |
| Forced policy treatments    | ALREADY EXISTS after Step 1: rank-prefix CACHE treatment composes through normal profiles/sweeps | `forcedActiveParticipantCount`, runner JVM property                                    | None for Java harness execution                                              | Reuse; do not use the old productivity gate as a substitute.                        |
| Worker ranking              | ALREADY EXISTS in runtime, treatment, and telemetry                                      | `workerRank`, forced cutoff, `contention_staleness.tsv`                                      | End-to-end cache-consumption proof remains for the vertical slice            | Reuse rank and validate the one-pair treatment.                                     |
| Benchmark-mode controls     | ALREADY EXISTS: cutoff is guarded by benchmark mode and validated against physical workers | `FragmentConfig.benchmarkMode`, runner JVM properties, trial-start validation             | None for Step 1                                                             | Reuse.                                                                              |
| Per-window telemetry        | ALREADY EXISTS                                                                          | `trajectory_windows.tsv`, `trajectory_occupancy.tsv`                                         | Exact continuous rank-K inputs require a join to per-core staleness samples | Loader joins on fork/iteration; no new large export.                                |
| Fork-level throughput       | ALREADY EXISTS                                                                          | `benchmark_output.log`, parsed `forkScores`                                                  | None                                                                        | Reuse auxiliary execution throughput selected by `JmhOutputParser`.                 |
| Comparison summaries        | ALREADY EXISTS; adjacent cutoffs compare as compatible POLICY treatments                 | `comparisons/comparison_summary.tsv`, `TrialConfigDiffer`                                    | Python still needs to identify/join adjacent `K/K-1` pairs                  | Use trial identity or the small external manifest.                                  |
| Variance/CV                 | ALREADY EXISTS                                                                          | baseline/candidate variance, stddev, CV, and fork count in comparison summary                | Existing summary omits calculated uncertainty/practical margin              | Recompute exactly in Python from exported fields.                                   |
| Ordered trajectories        | ALREADY EXISTS                                                                          | `jvmId`, `windowIndex`, elapsed time, throughput, feeding status in `trajectory_windows.tsv` | No loader yet                                                               | Read it in Python.                                                                  |
| Deterministic TSV artifacts | ALREADY EXISTS                                                                          | tab-separated exports with stable headers/order                                              | None                                                                        | Reuse.                                                                              |
| Checksums                   | ALREADY EXISTS                                                                          | digest-only `.sha256` sidecars                                                               | No Python validation                                                        | Compare each file's calculated SHA-256 directly to its sidecar text.                |
| Comparison compatibility    | ALREADY EXISTS after Step 1                                                             | cutoff is POLICY; park/version are ACTUATOR and incompatible                                 | Python must repeat strict fixture checks across manually paired run roots    | Reuse Java output and validate again at load time.                                  |
| Fixture metadata            | PARTIAL                                                                                 | expanded config now persists cutoff, CACHE park/version, CPU/work/JMH/lifecycle/JVM fields   | Normalized host topology ID remains external                                | Supply a small dataset manifest with topology/host family.                          |
| Productive handle telemetry | ALREADY EXISTS with exact decision value after Step 1                                   | `productiveHandleCount`, `productiveHandleRatio`, `contention_staleness.tsv`                 | None for Java export                                                        | Use the active-arm rank-K rows.                                                      |
| Registered-worker telemetry | ALREADY EXISTS with exact decision value after Step 1                                   | cycle/batch statistics and `contention_staleness.tsv`                                        | None for Java export                                                        | Use exact staleness-row value and cross-check fixture metadata.                     |
| Contention telemetry        | ALREADY EXISTS                                                                          | measured/raw contention, attempt counters, observation count/age                             | CACHE values go stale, as expected                                          | Use active `K` state for fitting and staleness as a validity check.                 |
| Smoothed-body telemetry     | ALREADY EXISTS with explicit readiness after Step 1                                     | `smoothedBodyCostNs`, `bodyHistoryReady` in `contention_staleness.tsv`                       | None for Java export                                                        | Reject non-ready feature rows.                                                       |
| Python model I/O            | MISSING                                                                                 | None                                                                                         | No Python package/loader                                                    | Add the small external loader/trainer/save/load utilities after harness validation. |

## Minimum dataset and Python input strategy

Do not add a second large Java telemetry export. The Python loader should accept a small training
manifest that names compatible run roots and adjacent arm pairs, then join existing artifacts:

- `trial_config.json` for expanded fixture and treatment identity;
- `benchmark_output.log` or the existing loaded throughput result for per-fork scores;
- `comparisons/comparison_summary.tsv` for whole-run deltas, variance, CV, counts, and outcome;
- `trajectory_windows.tsv` for ordered system throughput and feeding state;
- `contention_staleness.tsv` for rank-K contention, acquisition freshness, body cost, productive
  handles, registered workers, execution path, and cache diagnostics;
- `statistics.tsv` and `trajectory_occupancy.tsv` only when needed for explanatory validation;
- all present checksum sidecars.

The manifest supplies data that is not currently normalized by the harness:

```json
{
  "schemaVersion": 1,
  "runtimeCommit": "<git commit>",
  "cacheActuatorVersion": "cache-v1",
  "cacheParkNs": 15000,
  "topologyId": "<physical host/topology family>",
  "pairs": [
    {
      "pairId": "<stable id>",
      "kRun": "<path>",
      "kMinus1Run": "<path>",
      "K": 8
    }
  ]
}
```

Once loaded, an optional small derived `pairs.tsv` is useful as an audit/cache interface, but it
must contain summaries and source references rather than copied window telemetry. One row per pair
should contain:

| Group                                  | Fields                                                                                                                                                                                                                  |
|----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Fixture identity                       | experiment/family, both trial IDs, runtime commit, topology ID, CPU set/core type, lifecycle, CACHE actuator version, CACHE park duration, `R`, source configuration, work/body fixture, relevant decision/idle weights |
| Candidate identity                     | `K`, `K-1`, cutoff in each arm                                                                                                                                                                                          |
| Active state used by model             | late rank-K `contention_K`, `smoothedBodyCostNs_K`, `productiveHandles_K`, `registeredWorkers_K`, observation count/freshness, body-ready flag                                                                          |
| Withdrawn state retained for diagnosis | the same fields observed for rank K in the `K-1` arm, explicitly marked post-treatment/stale-capable                                                                                                                    |
| Performance evidence                   | whole and late fork means for both arms, variances/CVs/counts, ordered-trajectory source paths, delta, relative delta, existing outcome, late outcome, stability classification                                         |
| Training result                        | `y`, confidence weight, exclusion reason if any                                                                                                                                                                         |
| Provenance                             | every consumed artifact path and verified SHA-256 digest, loader/config version                                                                                                                                         |

Large fork arrays and ordered trajectories remain in their checksummed source artifacts. The derived
row refers to them by relative path and digest.

Compatibility validation must fail closed. Both arms must match in lifecycle, CACHE actuator version
and park duration, topology, CPU set, registered workers, source counts, workload/body fixture,
ring/executor behavior, telemetry controls needed by the loader, JVM/JMH settings, and all ordinary
DIRECT/STAGED/idle weights. The only intended difference is
`forcedActiveParticipantCount`. `PARTIAL` Java compatibility is not sufficient for training unless
the Python manifest explicitly approves the exact difference.

## Python fitting procedure

The external suite should remain deliberately small, for example under a future
`tools/productivity_model/` directory:

```text
load.py       checksum, schema, compatibility, and artifact joins
labels.py     whole/late comparison and confidence construction
model.py      exact marginal, fit, save, and load
evaluate.py   grouped metrics and prefix checks
export.py     Java field-name JSON export
```

NumPy plus `scipy.optimize.minimize` is sufficient. A larger machine-learning framework is not
needed.

### Feature construction

For every accepted adjacent pair, use the rank-K state from the `K` arm and calculate:

```text
b = log1p(smoothedBodyCostNs)
q = productiveHandles / (K*(K-1))

x = [q, c*q, b*q, R*q, -1, -c, -b, -R]
marginal = dot(x, [w0, w1, w2, w3, w4, w5, w6, w7])
```

Contention from fixed-point telemetry is divided by `1_000_000.0`; already normalized columns must
declare their units. Reject values outside `[0, 1]`, negative/non-finite body cost, `K <= 1`,
`K > R`, nonpositive `R`, or inconsistent fixture values. Do not standardize exported runtime
features. The optimizer may use an internal diagonal preconditioner, but it must transform the
result back to raw runtime coefficients and verify exact marginal equality before export.

### Objective

Fit weighted logistic cross-entropy to the fractional labels:

```text
p_cache = sigmoid(marginal)
loss = sum(pairWeight * BCE(y, p_cache)) + lambda * sum(w_i^2)
```

The fixed L2 coefficient prevents scale divergence on separable labels and supplies the scale
convention missing from sign-only inference. Select `lambda` only from a short declared list using
grouped validation. Do not tune the model family. Persist optimizer convergence, seed if any, and
the exact objective configuration.

### Split policy and leakage prevention

All data derived from one adjacent pair, trial repetition, experiment family, or imported fixture
must stay in one split. Never randomly split rows, windows, iterations, or forks.

Create groups from meaningful physical axes and reserve whole groups:

- entire topology/registered-worker-count combinations;
- entire productive-handle/source-count settings;
- entire body-cost regions or work-fixture families;
- an entire experiment family for final test.

A practical first split is grouped cross-validation on the training families, one held-out topology
or source-count family for validation, and a never-touched fixture family for final test. If the
initial dataset is too small to support all three, report that limitation and use
leave-one-family-out validation; do not manufacture independence from windows.

### Metrics and acceptance criteria

Report at least:

- pairwise winner accuracy on decisive pairs;
- confidence-weighted winner accuracy;
- throughput regret when the selected arm is wrong, both absolute and relative to the observed
  winning arm;
- tie behavior: fraction of stable tied pairs on which `abs(marginal)` exceeds a fixed neutral band,
  and false-decisive rate;
- weighted logistic loss;
- coverage by contention, log-body region, `P/R`, `R`, and `K`;
- fixed-state prefix violations across `K=2..R`, plus observed per-rank non-prefix decisions;
- guard behavior for `P <= 0`, insufficient history, no upstream handles, `R <= 1`, and `K <= 1`;
- Java/Python marginal parity on fixed test vectors and exported weights.

Before the broad campaign, acceptance is mechanical: the one-pair vertical slice must pass all
artifact and actuator checks. Before production export, require all of the following:

1. no checksum or compatibility failures;
2. no fixed-state CACHE-to-participate reversal over the declared in-domain validation grid;
3. confidence-weighted accuracy better than an always-participate and a fixed-cutoff baseline;
4. no material regression concentrated in any held-out topology, source-count, or body family;
5. regret dominated by low-confidence/near-tie pairs rather than strong repeatable pairs;
6. stable tied pairs remain near the marginal boundary;
7. exact Python/Java evaluator parity;
8. a final production-style benchmark verifies the exported weights without reusing training runs.

Numeric accuracy/regret thresholds should be set after the vertical slice reveals the achievable
noise floor, then frozen before inspecting the held-out test family. This avoids inventing an
unsupported target now while still defining a falsifiable acceptance process.

## Coefficient persistence and export

The complete model artifact should be a versioned JSON file containing:

```json
{
  "schemaVersion": 1,
  "modelVersion": "productivity-participation-v1",
  "equation": "pareto-marginal-v1",
  "cacheActuatorVersion": "cache-v1",
  "cacheParkNs": 15000,
  "runtimeCommit": "<commit>",
  "trainingConfigSha256": "<digest>",
  "sourceArtifacts": [
    {
      "path": "<relative path>",
      "sha256": "<digest>"
    }
  ],
  "logicalWeights": {
    "w0": 0.0,
    "w1": 0.0,
    "w2": 0.0,
    "w3": 0.0,
    "w4": 0.0,
    "w5": 0.0,
    "w6": 0.0,
    "w7": 0.0
  },
  "javaParetoWeights": {
    "activeWorkersWeight": 0.0,
    "contentionPhrWeight": 0.0,
    "contentionWorkersWeight": 0.0,
    "phrWeight": 0.0,
    "bodyPhrWeight": 0.0,
    "bodyWorkersWeight": 0.0,
    "registeredWorkersPhrWeight": 0.0,
    "registeredActiveWorkersWeight": 0.0
  },
  "splitGroups": {},
  "validationMetrics": {},
  "testMetrics": {}
}
```

The Python suite only needs `fit`, `save`, `load`, `predict_marginal`, `predict_action`, and
`export_java_weights` convenience functions. The export command should emit the eight named Java
fields and fail if a round-trip evaluator test changes any marginal beyond a tight floating-point
tolerance.

## CACHE park-duration compatibility rule

CACHE park behavior is part of the actuator, not a fitted input. Persist both a semantic actuator
version and the configured duration in every completed trial and model artifact. A loader must not
pool differing values or differing park placement under the same actuator version.

Changing CACHE park duration or placement may change the performance surface. Existing models and
data then become conditionally reusable only after a focused compatibility study. The safe default
is to generate affected comparisons again and retrain. Park calibration itself is explicitly outside
this plan.

## Exact minimal change list

| Category       | File/component                                                                                                       | Current behavior                                                                                              | Required behavior                                                                                                                                         | Why training needs it                                          | Approximate scope                                                              |
|----------------|----------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------|--------------------------------------------------------------------------------|
| ALREADY EXISTS | `CalibrationBenchmarkConfig`, config validation, runner property wiring                                              | Step 1 added nullable `forcedActiveParticipantCount`, positive/topology validation, and authoritative JVM handoff | Reuse unchanged                                                                                                                                        | Expresses adjacent participation without disabling workers     | Implemented with focused config/runner tests                                   |
| ALREADY EXISTS | `FragmentDecisionTree` benchmark-only path-selection seam                                                            | Step 1 forces ranks above cutoff to CACHE and confines ranks at/below it to normal DIRECT/STAGED selection    | Reuse unchanged                                                                                                                                           | Makes K and K-1 causal treatments                              | Implemented with decision-tree tests                                            |
| ALREADY EXISTS | `ControlPlaneFragment` CACHE branch and `FragmentControlConfig`                                                      | Step 1 defines `cache-v1`: skip ordinary idle, execute local/remote caches, park configured duration only on exhaustion | Validate under a running multi-rank topology in Step 3                                                                                              | Labels are conditional on actuator semantics                   | Implementation complete; end-to-end actuator proof remains                    |
| ALREADY EXISTS | Expanded `trial_config.json` and comparison differ                                                                   | Step 1 persists cutoff, park, and version; cutoff is POLICY and park/version are incompatible ACTUATOR fields | Reuse unchanged                                                                                                                                           | Prevents silent pooling and permits adjacent comparisons       | Implemented with serialization/differ/compatibility tests                      |
| ALREADY EXISTS | Decision-point staleness observation in `ControlPlaneFragment` / `contention_staleness.tsv`                          | Step 1 records exact decision `P/R/rank`, contention, body value, and `bodyHistoryReady`                      | Reuse unchanged                                                                                                                                           | Feature rows can reproduce inference inputs                    | Implemented by extending the existing TSV, not adding another artifact         |
| REQUIRED       | External Python utility                                                                                              | No Python loader/model                                                                                        | Validate joins/checksums, construct one active-context row per pair, label, fit, save/load, evaluate, export                                              | Produces the requested eight weights                           | Small NumPy/SciPy package; no production dependency                            |
| NICE TO HAVE   | Dataset manifest/run metadata                                                                                        | CPU set exists but host/topology family and runtime commit are not normalized                                 | Record `topologyId`, runtime commit, and fixture family once                                                                                              | Safer grouped splits and provenance                            | Small external JSON manifest; no Java exporter required initially              |
| NICE TO HAVE   | Derived `pairs.tsv`                                                                                                  | Evidence remains distributed across artifacts                                                                 | Cache one auditable summary row per pair                                                                                                                  | Easier review without duplicating telemetry                    | Python output only                                                             |
| NICE TO HAVE   | `trajectory_windows.tsv`                                                                                             | Has ordered throughput and categorical/aggregate telemetry, not exact rank-K continuous state                 | Optionally add rank-K continuous summaries                                                                                                                | Simplifies joins                                               | Skip initially; existing staleness TSV join is sufficient                      |
| ALREADY EXISTS | Lifecycle, forks, trajectories, throughput parsing, comparison summary, variance/CV, deterministic exports/checksums | Mature and usable                                                                                             | Reuse unchanged                                                                                                                                           | Supplies nearly all performance evidence                       | No harness architecture change                                                 |
| ALREADY EXISTS | Imports, profiles, sweeps, repetitions, balanced order, comparison keys                                              | Mature and usable                                                                                             | Reuse unchanged                                                                                                                                           | Generates controlled adjacent pairs efficiently                | Add only the new sweepable cutoff field                                        |
| ALREADY EXISTS | Productive handles, registered workers, contention, body telemetry, execution path, cache counts                     | Exported per core/window at several levels                                                                    | Reuse with exact decision-point correction above                                                                                                          | Supplies physical coordinates and actuator diagnostics         | No new telemetry subsystem                                                     |

## Reuse of retained experiments

Retained artifacts should be classified by actuator semantics before they enter the loader.

| Classification                         | Existing experiment families                                                                                                                                                                                                        | Permitted use                                                                                                                                                                                          |
|----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Directly reusable current-CACHE labels | None found                                                                                                                                                                                                                          | Existing runs predate the rank-cutoff CACHE treatment or use different actuator semantics. Do not claim current adjacent labels from them.                                                             |
| Partially reusable                     | `02-productivity-lifecycle-2x2-c01ce91b-run` CONTINUOUS arms; Phase 1 families `03` through `11`; normal/OFF arms in Phase 2 `12` through `17` where checksums and runtime identity match the intended fixture                      | Reuse harness validation, fixture qualification, body-region coverage, noise estimates, and candidate gap selection. They can reduce new campaign design, but they do not supply current CACHE labels. |
| Invalid as current-CACHE labels        | Old `FORCE_ON`/`FORCE_OFF` productivity comparisons in `00`, `01`, `02`, and `12` through `17`; worker-scale arms that physically change worker topology; RESET arms for trajectory labels; the pre-`c01ce91b` CPU-attribution runs | Preserve for history and diagnostics, but do not train the current actuator from their A/B outcome.                                                                                                    |
| Genuinely missing                      | Adjacent rank-cutoff comparisons using exact current CACHE semantics across useful contention, log-body, `P/R`, raw `R`, and `K` regions                                                                                            | Generate only after the vertical slice passes.                                                                                                                                                         |

Historical JSON may also predate the current `paretoWeights` schema. Java config loading now uses
the current default eight weights when that field is absent so retained harness presets remain
readable. Missing actuator identity remains explicitly `legacy-unspecified`; neither Java comparison
nor the Python loader may silently assign an old completed run the current actuator version.

## Dataset coverage strategy

Do not start with a Cartesian sweep. First inventory retained eligible fixtures and plot their
observed active-arm coordinates:

```text
contention
log1p(smoothedBodyCostNs)
productiveHandles / registeredWorkers
productiveHandles
registeredWorkers
K
```

Use the Phase 1 artifacts to select body fixtures that actually occupy the intended regions, and use
existing normal arms to estimate contention/productivity regions and fork noise. Then add adjacent
`K/K-1` pairs row by row:

1. cover at least two registered-worker counts so registered-worker coefficients are identifiable;
2. cover source/productive-handle counts that produce both surplus and deficit regimes;
3. cover the observed low, middle, and high contention range, with extra density near preference
   changes rather than at obvious extremes;
4. cover the validated body regions without retuning ordinary idle or CACHE park;
5. sample several interior `K` values, including `K=2`, mid-prefix values, and values near `R`;
6. after each small row, update coverage and add only missing or uncertain regions.

Retain surprising forks and non-monotonic trajectories. Do not rerun individual bad forks until they
disappear. Add replication to an entire pair only when its current outcome is important and
inconclusive, and preserve the original evidence.

## Small end-to-end validation

Before any broad data generation, validate one adjacent pair on one already-qualified physical
fixture.

### Deterministic tests first

1. With cutoff `K`, ranks `<= K` can still select both DIRECT and STAGED from the ordinary policy.
2. Ranks `> K` select CACHE.
3. A forced CACHE rank executes a preloaded local cached frame.
4. A forced CACHE rank executes a remotely cached frame.
5. The same CACHE rank performs no upstream request or handle acquisition.
6. The selected CACHE park duration/placement is observable and reset-safe.
7. The exported row contains the exact decision inputs and readiness/freshness state.

### One-pair experiment

Use one interior pair such as `K=4` versus `K-1=3` on a fixture known to generate remote cached
work. Keep all other config equal, use CONTINUOUS lifecycle, balanced two-treatment order, two
independent JVM forks per arm, and only enough warmup/measurement windows to expose a late
trajectory (for example, two warmup and six measurement windows). This is a plumbing validation, not
a performance claim or coefficient campaign.

The slice passes only when:

- completed configs contain cutoff, CACHE actuator version, and park duration;
- ranks above each cutoff report CACHE and no new acquisition attempts;
- at least one above-cutoff rank completes cached work;
- participating ranks show ordinary DIRECT/STAGED behavior;
- `comparison_summary.tsv` contains both fork counts, variance/CV, delta, and outcome;
- each fork has ordered CONTINUOUS trajectory windows;
- rank-K contention, body, `P`, `R`, readiness, and freshness are loadable;
- all consumed checksums verify;
- Python produces exactly one accepted or explicitly inconclusive weighted pair record;
- the Python marginal matches fixed Java test coefficients for the same feature row;
- save/load and named Java-weight export round-trip without changing the decision.

Only after this slice passes should the missing physical surface be enumerated and executed.

## Explicit non-goals

- Implementing the Python model or Steps 2 through 8 as part of the Step 1 change.
- Tuning CACHE park duration.
- Redesigning the calibration harness, scheduler decision tree, contention sensor, or worker-rank
  scheme.
- Adding a second large telemetry export when existing artifacts can be joined.
- Treating contention, body, acquisition success, occupancy, or cache statistics as performance
  objectives.
- Training neural networks, trees, reinforcement learning, Bayesian optimization, Sobol searches, or
  a more complex reference model in the first implementation.
- Random row-level splits or treating windows from one JVM as independent samples.
- Reinterpreting old productivity FORCE_ON evidence as current CACHE evidence.
- Running a broad benchmark campaign before the one-pair vertical slice succeeds.
- Making Python a runtime dependency.

## Minimum implementation sequence

1. **Step 1 - small harness fixes (implemented):** `cache-v1` freezes the CACHE exhaustion park,
   adds the forced CACHE-above-rank treatment, persists/classifies its identity, and exports exact
   decision inputs plus body readiness.
2. **Step 2 - Python loader:** implement checksum/compatibility validation and joins over existing
   comparison, throughput, trajectory, staleness, and config artifacts.
3. **Step 3 - one-pair vertical validation:** run the small CONTINUOUS `K` versus `K-1` slice and
   prove actuator, evidence, loader, label, and evaluator behavior end to end.
4. **Step 4 - generate missing training surface:** qualify retained fixtures, map existing coverage,
   and run only adjacent pairs that fill useful gaps.
5. **Step 5 - fit eight coefficients:** construct active-context features, weighted labels, and fit
   the exact regularized logistic marginal.
6. **Step 6 - held-out validation:** evaluate grouped winner accuracy, weighted accuracy, regret,
   ties, and prefix behavior without row-level leakage.
7. **Step 7 - export weights:** save full provenance/model metadata and emit the eight named Java
   `ParetoWeights` fields after evaluator parity checks.
8. **Step 8 - production benchmark verification:** verify the exported weights on held-out
   production-style fixtures, including stability and re-entry behavior, before any default change.
