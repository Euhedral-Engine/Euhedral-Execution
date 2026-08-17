# Running Calibration Benchmarks in Euhedral

This guide is the practical manual for configuring, executing, and analyzing calibration
benchmarks in the `benchmarks` module.

Calibration benchmarks measure, observe, and optimize fragment action decision policies under
synthetic and realistic contention scenarios. During execution, low-overhead in-memory observers
record lifecycle events and branch decisions without taking locks or allocating objects. The
resulting telemetry is processed by a dedicated statistical engine into occupancy grids, Markov
transition matrices, displacement vector fields, and correlation analyses.

---

## 1. Calibration Configuration

Before creating or modifying trial configurations, inspect the canonical example:

- [
`src/main/presets/example_harness_config.json`](src/main/presets/example_harness_config.json)
  is the reference configuration illustrating all available schema features and options.
- [
`src/main/presets/example_profile_library.json`](src/main/presets/example_profile_library.json)
  is an example reusable profile library containing calibration and decision weight profiles.
- [
`src/main/presets/exec_contention_band_calibration.json`](src/main/presets/exec_contention_band_calibration.json)
  is an example calibration preset for sweeping execution policies under heavy contention.

### Configuration Schema Overview

A harness configuration file is parsed into
[`HarnessConfig`](src/main/java/calibration/config/HarnessConfig.java)
and consists of the following primary sections:

```text
HarnessConfig
+-- imports: external profile library imports (namespaced)
+-- runOptions: global execution control (randomization, repeats, failFast)
+-- artifacts: output paths and retention toggles
+-- calibrationProfiles: local reusable calibration configurations
+-- decisionWeightProfiles: local reusable decision weight matrices
+-- sweeps: parameter variation declarations (Cartesian expansion)
+-- trials: explicit trial definitions and benchmark configurations
```

#### Key Configuration Fields

1. **`imports`**
   ([`ProfileImport`](src/main/java/calibration/config/ProfileImport.java)):
   - Declares external profile library files imported under explicit namespaces.
   - Each import specifies `path` (relative to the declaring JSON file or absolute) and a non-blank `namespace` without dots.
   - Imported libraries are parsed into
     [`ProfileLibrary`](src/main/java/calibration/config/ProfileLibrary.java)
     which may contain nested `imports`, `calibrationProfiles`, and `decisionWeightProfiles`.
   - Imported symbols are referenced as `<namespace>.<profileName>` (e.g. `common.uniform-xs` or `host.baseline`). Nested library imports compose namespaces explicitly (e.g. `common.base.baseline`).
   - Import cycles (`a.json -> b.json -> a.json`) are detected via canonical paths and rejected. Loaded libraries are cached per resolution operation.

2. **`runOptions`**
   ([`HarnessRunOptions`](src/main/java/calibration/config/HarnessRunOptions.java)):
   - `randomizeTrialOrder` (`boolean`): Shuffles trial execution order across the run.
   - `randomSeed` (`long`): Deterministic PRNG seed for reproducibility.
   - `failFast` (`boolean`): If `true`, aborts the entire suite on first trial failure; otherwise accumulates failures.
   - `repeatCount` (`int`): Number of full passes through all resolved trials.

3. **`artifacts`**
   ([`ArtifactConfig`](src/main/java/calibration/config/ArtifactConfig.java)):
   - `outputDirectory` (`string`): Target directory for benchmark telemetry exports.
   - `retainExpandedConfig` (`boolean`): Writes `trial_config.json` into each trial's output folder.
   - `retainRawBenchmarkOutput` (`boolean`): Writes raw benchmark console output (`benchmark_output.log`) into each trial's output folder.
   - `retainObserverData` (`boolean`): Retains observer metric telemetry TSV exports (`raw_observations.tsv`, `statistics.tsv`, etc.).
   - `retainPerForkResults` (`boolean`): Retains dedicated per-fork subdirectories (`fork-.../`).
   - `retainPerIterationResults` (`boolean`): Retains dedicated per-iteration subdirectories (`iteration-.../`).

4. **`sweeps`**
   ([`SweepConfig`](src/main/java/calibration/config/SweepConfig.java)):
   - Declares parameter sweeps against a `baseTrialId`.
   - Expanded into concrete trial variations via Cartesian product by
     [`TrialSweepExpander`](src/main/java/calibration/config/TrialSweepExpander.java).
   - Parameters use JSON Pointers (e.g. `/calibrationConfig/decisionWeights/executionPolicies` or `/calibrationConfig/parallelSources`).

5. **`trials`**
   ([`TrialConfig`](src/main/java/calibration/config/TrialConfig.java)):
   - `id`, `name`, `group`, `description`, `hypothesis`: Trial metadata and grouping.
   - `enabled` (`boolean`): Allows selective disabling of individual base trials.
   - `forks`, `warmups`, `iterations`: JMH execution parameters.
   - `warmupTime`, `measurementTime`: JMH duration strings (e.g. `"2s"`, `"5s"`).
   - `jvmArgs`: Extra JVM flags passed to forked benchmark processes.
   - `calibrationProfile` (`string`): Reference to local or namespaced imported calibration profile (e.g. `"common.uniform-xs"`).
   - `calibrationConfig`
     ([`CalibrationBenchmarkConfig`](src/main/java/calibration/config/CalibrationBenchmarkConfig.java)):
     - `cpuSet`: List of CPU IDs for fragment worker pinning (e.g. `[1, 2, 3, 4]`).
     - `parallelSources`: Number of unordered, parallel frame sources.
     - `orderedSources`: Number of sequential, ordered frame sources.
     - `workUnits`: Artificial synthetic load per frame.
     - `randomizeWork`: Whether frame load varies uniformly.
     - `totalRequiredExecutions`: Frame execution target per JMH invocation.
     - `invocationTimeoutMillis`: Execution timeout guard.
     - `rawSampleLimit`: Circular buffer capacity for raw samples (power-of-two).
     - `decisionWeightProfile` (`string`): Reference to local or namespaced imported decision weight profile (e.g. `"host.baseline"`).
     - `decisionWeights`: 28 fixed weights defining thresholds, costs, park times, and execution policies.
     - Observation Toggles:
       - `observeCycleStart`: Fragment cycle boundary metrics.
       - `observeBatchProgress`: In-flight batch service metrics.
       - `observeBatchComplete`: Batch completion metrics.
       - `observeRawBodyCost`: Unfiltered execution cost samples.
       - `observeIdleDecision`: Idle action decision evaluations.
       - `observeExecDecision`: Execution action decision evaluations.

#### Execution Path Strategies (`ExecutionPath`)

The
[`ExecutionPath`](../euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/FragmentControlConfig.java#L65-L70)
enum defines the execution strategies selected by a fragment worker at completed-batch boundaries
based on the 5x5 contention and body-cost matrix:

- **`DIRECT`**:
  The fragment worker immediately pulls from remote caches and upstream handles and executes frames
  directly within the active cycle. If no frames were available, it issues a combined `requestAndPull`.
  Optimized for low-to-moderate contention where direct execution minimizes end-to-end latency.
- **`STAGED`**:
  The fragment worker decouples demand signaling from execution. It issues an asynchronous upstream
  `request`, drains its local MPSC cache first, and then executes remaining work. Staging isolates
  upstream contention from the hot execution path and prevents queue thrashing under high concurrency.
- **`SKIP_THEN_DIRECT`**:
  A transitory state that skips the current cycle's execution attempt and transitions to `DIRECT` for
  the subsequent cycle. Also used when no upstream handles are available or during fragment initialization.
- **`SKIP_THEN_STAGED`**:
  A transitory state that skips the current cycle's execution attempt and transitions to `STAGED` for
  the subsequent cycle.

In trial configurations, `decisionWeights.executionPolicies` specifies a 5x5 grid of `ExecutionPath`
values across all contention bands (`XS`..`XH`) and body-cost bands (`xsBody`..`xhBody`). Calibration
sweeps (such as
[`exec_contention_band_calibration.json`](src/main/presets/exec_contention_band_calibration.json))
vary these mappings to find the empirical boundary where switching from `DIRECT` to `STAGED` maximizes
throughput.

---

## 2. Using the `euhedral-calibration` Script

The calibration suite is packaged into a standalone distribution and executed using the
`euhedral-calibration` launcher script.

### Building the Distribution

Package the benchmark distribution from the repository root:

```bash
gradle :benchmarks:assemble
```

This compiles the code and generates the runtime distribution under `benchmarks/build/`:
- `benchmarks/build/euhedral-benchmark.jar`: Benchmark classes and manifest.
- `benchmarks/build/lib/`: All runtime dependencies.
- `benchmarks/build/bin/euhedral-calibration`: The calibration execution script.

### Script Usage

Run the launcher script by providing the path to a harness JSON configuration:

```bash
benchmarks/build/bin/euhedral-calibration <path-to-config.json>
```

#### Command-Line Flags and Environment Variables

- `--minimal`:
  Runs the benchmark without loading `benchmark-logback.xml`.
  ```bash
  benchmarks/build/bin/euhedral-calibration --minimal path/to/config.json
  ```
- `JAVA_OPTS` or `JAVA_TOOL_OPTIONS`:
  Pass standard JVM options (e.g. GC logging, heap settings, profiling agents) to the launcher:
  ```bash
  JAVA_OPTS="-Xms4g -Xmx4g" benchmarks/build/bin/euhedral-calibration path/to/config.json
  ```

### Execution Lifecycle

When invoked,
[`CalibrationRunner`](src/main/java/calibration/CalibrationRunner.java)
executes the following workflow:

1. **Config Loading & Validation**: Parses and validates the configuration schema.
2. **Sweep Expansion**: Expands all enabled sweeps into concrete trial variants.
3. **Directory Preparation**: Creates unique output folders per trial and repeat index:
   `<outputDirectory>/<trialId>_repeat_<repeatIndex>/`
4. **JMH Forking**: Launches
   [`CalibrationBenchmark`](src/main/java/calibration/benchmarks/CalibrationBenchmark.java)
   with required JVM arguments, system properties, and core affinities.
5. **Observation Recording**:
   [`BenchmarkObserver`](src/main/java/calibration/infra/BenchmarkObserver.java)
   collects per-core execution events into lock-free buffers.
6. **Statistical Processing**:
   [`HighSpeedMetricsStatistics`](src/main/java/calibration/statistics/HighSpeedMetricsStatistics.java)
   computes descriptive summaries, quantile statistics, 5x5 occupancy grids, 25-state Markov
   transitions, displacement vector fields, and correlation matrices.
7. **Artifact Export**:
   [`TrialExport`](src/main/java/calibration/io/TrialExport.java)
   writes TSV files and corresponding `.sha256` checksums into each invocation directory.

---

## 3. Calibration Statistics Reference

The classes in
[`calibration.statistics.fork`](src/main/java/calibration/statistics/fork/)
and
[`calibration.statistics.iteration`](src/main/java/calibration/statistics/iteration/)
represent the structured telemetry results computed across JMH measurement iterations.

The calibration telemetry hierarchy is:
- **`SystemForkResult`** (scope `FORK`): Authoritative whole-fork calibration summary aggregated across all measurement iterations and participating cores.
- **`SystemIterationResult`** (scope `ITERATION`): Diagnostic within-fork iteration view aggregated across all participating cores for one measurement iteration.
- **`CoreIterationResult`** (scope `CORE`): Diagnostic per-core view for one iteration.

```text
ForkCalculationResult
+-- system: SystemForkResult (authoritative whole-fork summary)
+-- iterations: List<IterationResult> (per-iteration breakdowns)
      +-- iterationIndex
      +-- system: SystemIterationResult (within-fork iteration summary)
      +-- cores: List<CoreIterationResult> (per-core diagnostics)

SystemForkResult / SystemIterationResult / CoreIterationResult
+-- cycleStart: CycleStartStatistics (completed, batchSize, upstreamCount, workers, rank, contention, throughput)
+-- batchProgress: BatchProgressStatistics (upstreamCount, workers, rank, contention, avgServiceTime)
+-- batchComplete: BatchCompleteStatistics (upstreamCount, workers, rank, contention, avgServiceTime, throughput)
+-- rawBodyCost: RawBodyCostStatistics (unfiltered frame body cost distributions)
+-- idleDecisions: DecisionStatistics (idle policy occupancy, transitions, vector field, correlations)
+-- execDecisions: DecisionStatistics (exec policy occupancy, transitions, vector field, correlations)
+-- centroidDistance: Euclidean distance between idle and exec occupancy centroids
```

### Observation Windows: Head, Steady-State, and Combined

Each metric category captures observations across three distinct temporal windows:

- **`head`** (warmup window): The first $N <= \text{rawSampleLimit}$ samples captured immediately
  after iteration start. Captures initial cache warming and transient queue ramp-up.
- **`steadyState`** (tail window): The most recent $N <= \text{rawSampleLimit}$ samples aligned at
  iteration teardown. Captures equilibrium behavior.
- **`combined`**: The concatenation of head and steady-state observations representing the full
  sample window.

---

### Core Data Structures & Models

#### 1. [`SystemForkResult`](src/main/java/calibration/statistics/fork/SystemForkResult.java), [`SystemIterationResult`](src/main/java/calibration/statistics/iteration/SystemIterationResult.java), and [`CoreIterationResult`](src/main/java/calibration/statistics/iteration/CoreIterationResult.java)
`SystemForkResult` is the authoritative calibration result aggregated across all measurement
iterations and participating cores within an independent JMH fork. `SystemIterationResult` is the
within-fork whole-system aggregation for a single measurement iteration. `CoreIterationResult`
retains the per-core breakdown for diagnostic topology inspection. All three expose exact observation
counters, category summaries, and the `centroidDistance` metric (L2 distance between idle and
execution occupancy centroids). Pairings for the whole fork and each iteration are encapsulated by
[`ForkCalculationResult`](src/main/java/calibration/statistics/fork/ForkCalculationResult.java) and
[`IterationResult`](src/main/java/calibration/statistics/iteration/IterationResult.java).

#### 2. [`ScalarSummary`](src/main/java/calibration/statistics/iteration/ScalarSummary.java)
Combines parametric descriptive statistics and non-parametric quantile statistics for a continuous
scalar variable:
- **Descriptive**: `count`, `mean`, `variance`, `standardDeviation`, `coefficientOfVariation` (sigma / mu), `min`, `max`, `median`.
- **Quantiles**: `p25`, `p50`, `p75`, `p95`.
- **Spread & Tail Skew**:
  - `iqr`: Interquartile range (P75 - P25).
  - `normalizedIqr`: Normalized IQR (IQR / P50).
  - `p95ToP50Ratio`: Tail latency indicator (P95 / P50).

#### 3. [`CycleStartStatistics`](src/main/java/calibration/statistics/iteration/CycleStartStatistics.java) & [`CycleStartScalars`](src/main/java/calibration/statistics/iteration/CycleStartScalars.java)
Sampled at the start of a fragment loop cycle.
- **Variables**:
  - `completed`: Total frames completed by this fragment.
  - `batchSize`: Batch size pulled in current cycle.
  - `upstreamCount`: Number of active upstream sources.
  - `registeredWorkers`: Active sibling workers on the shard.
  - `workerRank`: Relative rank/index of this worker core.
  - `contention`: Measured queue/core contention.
  - `throughput`: Instantaneous operations/sec.
- Includes cross-correlation matrices across all 7 variables for head, steady-state, and combined segments.

#### 4. [`BatchProgressStatistics`](src/main/java/calibration/statistics/iteration/BatchProgressStatistics.java) & [`BatchProgressScalars`](src/main/java/calibration/statistics/iteration/BatchProgressScalars.java)
Sampled during batch execution progress.
- **Variables**: `upstreamCount`, `registeredWorkers`, `workerRank`, `contention`, and `avgServiceTime` (nanoseconds per frame).
- Evaluates correlation between `contention` and `avgServiceTime`.

#### 5. [`BatchCompleteStatistics`](src/main/java/calibration/statistics/iteration/BatchCompleteStatistics.java) & [`BatchCompleteScalars`](src/main/java/calibration/statistics/iteration/BatchCompleteScalars.java)
Sampled upon batch completion.
- **Variables**: `upstreamCount`, `registeredWorkers`, `workerRank`, `contention`, `avgServiceTime`, `throughput`.
- Evaluates correlations across `[contention, avgServiceTime, throughput]`.

#### 6. [`RawBodyCostStatistics`](src/main/java/calibration/statistics/iteration/RawBodyCostStatistics.java)
Captures raw, unfiltered execution body cost samples (execution time in nanoseconds/cycles) across
`head`, `steadyState`, and `combined` windows, along with cumulative `totalCost` and `totalObservations`.

#### 7. [`DecisionStatistics`](src/main/java/calibration/statistics/iteration/DecisionStatistics.java) & [`DecisionScalars`](src/main/java/calibration/statistics/iteration/DecisionScalars.java)
Comprehensive telemetry for fragment action decisions (idle actions and execution actions).
- **Scalars**: `contention` and `smoothedBodyCost` (EMA of body execution cost).
- **Occupancy**: 5x5 branch decision grid.
- **Transitions**: 25-state Markov chain transitions.
- **Vector Fields**: 5x5 policy gradient vector fields.
- **Correlations**: Aligned correlation matrices across `[contentionPolicy, bodyPolicy, smoothedBodyCost]`.

---

### Policy Grid, Markov Transitions, and Vector Fields

Fragment action evaluation maps system state into a 2-dimensional 5x5 discrete grid (25 states):
- **Contention Bands (0..4)**: `XS` (0), `S` (1), `M` (2), `H` (3), `XH` (4)
- **Body Cost Bands (0..4)**: `XS` (0), `S` (1), `M` (2), `H` (3), `XH` (4)
- **State Index**: state = contentionBand * 5 + bodyBand (0 <= state <= 24).

```text
Contention Band (i)
  XH (4) |  20   21   22   23   24
   H (3) |  15   16   17   18   19
   M (2) |  10   11   12   13   14
   S (1) |   5    6    7    8    9
  XS (0) |   0    1    2    3    4
         +-------------------------
            XS    S    M    H   XH
             0    1    2    3    4
                Body Band (j)
```

#### 1. [`BranchOccupancyResult`](src/main/java/calibration/statistics/iteration/BranchOccupancyResult.java), [`OccupancySummary`](src/main/java/calibration/statistics/iteration/OccupancySummary.java), and [`OccupancyMesh`](src/main/java/calibration/statistics/iteration/OccupancyMesh.java)
Tracks the frequency with which the fragment evaluates decisions in each cell of the 5x5 space.
- `exactCounts`: 5x5 array of raw decision counts.
- `probabilities`: Normalized 5x5 probability distribution (sum(p_ij) = 1.0).
- `contentionCentroid` (mu_C) & `bodyCentroid` (mu_B): Center of mass in grid coordinates ([0.0, 4.0]).
- `contentionVariance` (sigma_C^2), `bodyVariance` (sigma_B^2), `contentionBodyCovariance` (sigma_CB): Dispersion of decisions across bands.
- `radius`: Root-mean-square dispersion radius sqrt(sigma_C^2 + sigma_B^2).
- `distanceTo(other)`: Euclidean distance between two occupancy centroids.

#### 2. [`TransitionAnalysis`](src/main/java/calibration/statistics/iteration/TransitionAnalysis.java)
Analyzes temporal state transitions across the 25 states.
- `transitionCounts`: 25x25 matrix where M[A][B] is the count of direct transitions from state A -> B.
- `transitionProbabilities`: Row-stochastic transition probability matrix P(B | A).
- `selfTransitionRate(state)`: Probability of remaining in the same state (P(A | A)), measuring decision stability.
- `dominantOutgoingState(state)` & `dominantOutgoingProbability(state)`: Most probable target state and its likelihood.
- `oscillation(stateA, stateB)`: Two-cell flapping score:
  oscillation(A, B) = (M[A][B] + M[B][A]) / sum_k (M[A][k] + M[k][A] + M[B][k] + M[k][B])
  Values close to 1.0 indicate rapid policy oscillation between state A and state B.

#### 3. [`VectorField`](src/main/java/calibration/statistics/VectorField.java) & [`VectorCell`](src/main/java/calibration/statistics/VectorCell.java)
Computes a 5x5 spatial displacement field showing the direction and magnitude of policy movement from each cell.
- For each source cell (i, j):
  - meanDeltaContention = (1 / N) * sum(delta_i * count)
  - meanDeltaBody = (1 / N) * sum(delta_j * count)
  - magnitude = sqrt((meanDeltaContention)^2 + (meanDeltaBody)^2)
- Identifies stable attractor cells (low magnitude, high self-transition) vs unstable gradient flows (high magnitude pointing toward attractors).

#### 4. [`CorrelationResult`](src/main/java/calibration/statistics/iteration/CorrelationResult.java)
Contains paired Pearson (linear) and Spearman (rank-order) correlation matrices for aligned
multi-variable observations, enabling detection of linear dependencies and non-linear monotonic
relationships.

---

## 4. Exported Artifacts Summary

When an output directory is configured, each trial invocation produces the following TSV files
alongside their SHA-256 integrity checksums:

| Export File | Description | Source Structure |
|-------------|-------------|------------------|
| `trial_config.json` | Snapshot of the expanded trial configuration | [`TrialConfig`](src/main/java/calibration/config/TrialConfig.java) |
| `benchmark_output.log` | Raw benchmark console output when retained | [`CalibrationRunner`](src/main/java/calibration/CalibrationRunner.java) |
| `raw_observations.tsv` | Total event observation counts for FORK, ITERATION, and CORE scopes | [`ForkCalculationResult`](src/main/java/calibration/statistics/fork/ForkCalculationResult.java) |
| `statistics.tsv` | Descriptive & quantile scalar statistics across variables and segments | [`ScalarSummary`](src/main/java/calibration/statistics/iteration/ScalarSummary.java) |
| `occupancy.tsv` | 5x5 branch occupancy counts, probabilities, centroids, and variances | [`BranchOccupancyResult`](src/main/java/calibration/statistics/iteration/BranchOccupancyResult.java) |
| `transitions.tsv` | 25x25 Markov transition counts, probabilities, self-rates, and dominant targets | [`TransitionAnalysis`](src/main/java/calibration/statistics/iteration/TransitionAnalysis.java) |
| `vector_fields.tsv` | 5x5 displacement vectors (Delta_C, Delta_B) and magnitudes | [`VectorField`](src/main/java/calibration/statistics/VectorField.java) |
| `correlations.tsv` | Aligned Pearson and Spearman correlation matrices across observation dimensions | [`CorrelationResult`](src/main/java/calibration/statistics/iteration/CorrelationResult.java) |

---

## 5. Comparison Artifacts Summary

Comparison artifacts are post-run outputs generated when contrasting a baseline calibration run
against one or more candidate runs. Source baseline and candidate run directories remain immutable.
JMH throughput (`comparison_summary.tsv`) remains the authoritative performance result; diagnostic
TSVs explain scheduler and fragment decision behavior without computing synthetic winners.

| Export File | Description | Source Structure |
|-------------|-------------|------------------|
| `comparison_manifest.json` | Provenance manifest with identities, paths, compatibility status, and checksums | [`ComparisonManifest`](src/main/java/calibration/comparisons/schema/ComparisonManifest.java) |
| `comparison_summary.tsv` | Authoritative performance comparison summary across candidates | [`PerformanceComparison`](src/main/java/calibration/comparisons/schema/PerformanceComparison.java) |
| `configuration_differences.tsv` | Structural JSON pointer configuration diffs sorted deterministically | [`ConfigurationDifference`](src/main/java/calibration/comparisons/schema/ConfigurationDifference.java) |
| `scalar_comparisons.tsv` | Aggregate and per-core continuous scalar telemetry deltas across categories | [`ScalarComparison`](src/main/java/calibration/comparisons/schema/ScalarComparison.java) |
| `occupancy_comparisons.tsv` | 5x5 branch occupancy deltas, centroid displacement, and total variation distance | [`OccupancyComparison`](src/main/java/calibration/comparisons/schema/OccupancyComparison.java) |
| `transition_comparisons.tsv` | 25x25 Markov transition count/probability deltas and dominant target shifts | [`TransitionComparison`](src/main/java/calibration/comparisons/schema/TransitionComparison.java) |
| `vector_field_comparisons.tsv` | 5x5 displacement gradient and magnitude deltas for idle and exec policies | [`VectorFieldComparison`](src/main/java/calibration/comparisons/schema/VectorFieldComparison.java) |
| `correlation_comparisons.tsv` | Aligned Pearson and Spearman correlation deltas between observation metrics | [`CorrelationComparison`](src/main/java/calibration/comparisons/schema/CorrelationComparison.java) |
