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
`benchmarks/src/main/presets/example_harness_config.json`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/presets/example_harness_config.json)
  is the reference configuration illustrating all available schema features and options.
- [
`benchmarks/src/main/presets/exec_contention_band_calibration.json`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/presets/exec_contention_band_calibration.json)
  is an example calibration preset for sweeping execution policies under heavy contention.

### Configuration Schema Overview

A harness configuration file is parsed into
[`HarnessConfig`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/config/HarnessConfig.java)
and consists of four primary sections:

```text
HarnessConfig
+-- runOptions: global execution control (randomization, repeats, failFast)
+-- artifacts: output paths and retention toggles
+-- sweeps: parameter variation declarations (Cartesian expansion)
+-- trials: explicit trial definitions and benchmark configurations
```

#### Key Configuration Fields

1. **`runOptions`**
   ([`HarnessRunOptions`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/config/HarnessRunOptions.java)):
   - `randomizeTrialOrder` (`boolean`): Shuffles trial execution order across the run.
   - `randomSeed` (`long`): Deterministic PRNG seed for reproducibility.
   - `failFast` (`boolean`): If `true`, aborts the entire suite on first trial failure; otherwise accumulates failures.
   - `repeatCount` (`int`): Number of full passes through all resolved trials.

2. **`artifacts`**
   ([`ArtifactConfig`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/config/ArtifactConfig.java)):
   - `outputDirectory` (`string`): Target directory for benchmark telemetry exports.
   - `retainExpandedConfig` (`boolean`): Writes `trial_config.json` into each trial's output folder.
   - `retainRawBenchmarkOutput` (`boolean`): Writes raw benchmark console output (`benchmark_output.log`) into each trial's output folder.
   - `retainObserverData` (`boolean`): Retains observer metric telemetry TSV exports (`raw_observations.tsv`, `statistics.tsv`, etc.).
   - `retainPerForkResults` (`boolean`): Retains dedicated per-fork subdirectories (`fork-.../`).
   - `retainPerIterationResults` (`boolean`): Retains dedicated per-iteration subdirectories (`iteration-.../`).

3. **`sweeps`**
   ([`SweepConfig`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/config/SweepConfig.java)):
   - Declares parameter sweeps against a `baseTrialId`.
   - Expanded into concrete trial variations via Cartesian product by
     [`TrialSweepExpander`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/config/TrialSweepExpander.java).
   - Parameters use JSON Pointers (e.g. `/calibrationConfig/decisionWeights/executionPolicies` or `/calibrationConfig/parallelSources`).

4. **`trials`**
   ([`TrialConfig`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/config/TrialConfig.java)):
   - `id`, `name`, `group`, `description`, `hypothesis`: Trial metadata and grouping.
   - `enabled` (`boolean`): Allows selective disabling of individual base trials.
   - `forks`, `warmups`, `iterations`: JMH execution parameters.
   - `warmupTime`, `measurementTime`: JMH duration strings (e.g. `"2s"`, `"5s"`).
   - `jvmArgs`: Extra JVM flags passed to forked benchmark processes.
   - `calibrationConfig`
     ([`CalibrationBenchmarkConfig`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/config/CalibrationBenchmarkConfig.java)):
     - `cpuSet`: List of CPU IDs for fragment worker pinning (e.g. `[1, 2, 3, 4]`).
     - `parallelSources`: Number of unordered, parallel frame sources.
     - `orderedSources`: Number of sequential, ordered frame sources.
     - `workUnits`: Artificial synthetic load per frame.
     - `randomizeWork`: Whether frame load varies uniformly.
     - `totalRequiredExecutions`: Frame execution target per JMH invocation.
     - `invocationTimeoutMillis`: Execution timeout guard.
     - `rawSampleLimit`: Circular buffer capacity for raw samples (power-of-two).
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
[`ExecutionPath`](file:///home/brandon/src/Euhedral-Execution/euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/FragmentControlConfig.java#L65-L70)
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
[`exec_contention_band_calibration.json`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/presets/exec_contention_band_calibration.json))
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
[`CalibrationRunner`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/CalibrationRunner.java)
executes the following workflow:

1. **Config Loading & Validation**: Parses and validates the configuration schema.
2. **Sweep Expansion**: Expands all enabled sweeps into concrete trial variants.
3. **Directory Preparation**: Creates unique output folders per trial and repeat index:
   `<outputDirectory>/<trialId>_repeat_<repeatIndex>/`
4. **JMH Forking**: Launches
   [`CalibrationBenchmark`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/benchmarks/CalibrationBenchmark.java)
   with required JVM arguments, system properties, and core affinities.
5. **Observation Recording**:
   [`BenchmarkObserver`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/infra/BenchmarkObserver.java)
   collects per-core execution events into lock-free buffers.
6. **Statistical Processing**:
   [`HighSpeedMetricsStatistics`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/HighSpeedMetricsStatistics.java)
   computes descriptive summaries, quantile statistics, 5x5 occupancy grids, 25-state Markov
   transitions, displacement vector fields, and correlation matrices.
7. **Artifact Export**:
   [`TrialExport`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/io/TrialExport.java)
   writes TSV files and corresponding `.sha256` checksums into each invocation directory.

---

## 3. Iteration Statistics Reference (`statistics/iteration`)

The classes in
[`calibration.statistics.iteration`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/)
represent the core structured results computed per physical core for each measurement iteration.

```text
CoreIterationResult
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

- **`head`** (warmup window): The first $N \le \text{rawSampleLimit}$ samples captured immediately
  after iteration start. Captures initial cache warming and transient queue ramp-up.
- **`steadyState`** (tail window): The most recent $N \le \text{rawSampleLimit}$ samples aligned at
  iteration teardown. Captures equilibrium behavior.
- **`combined`**: The concatenation of head and steady-state observations representing the full
  sample window.

---

### Core Data Structures & Models

#### 1. [`CoreIterationResult`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/CoreIterationResult.java)
The top-level container for all statistics gathered on a single physical core during one JMH
measurement iteration. Contains total observation counters, category summaries, and the
`centroidDistance` metric ($L_2$ distance between idle and execution occupancy centroids).

#### 2. [`ScalarSummary`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/ScalarSummary.java)
Combines parametric descriptive statistics and non-parametric quantile statistics for a continuous
scalar variable:
- **Descriptive**: `count`, `mean`, `variance`, `standardDeviation`, `coefficientOfVariation` ($\sigma / \mu$), `min`, `max`, `median`.
- **Quantiles**: `p25`, `p50`, `p75`, `p95`.
- **Spread & Tail Skew**:
  - `iqr`: Interquartile range ($P_{75} - P_{25}$).
  - `normalizedIqr`: Normalized IQR ($\text{IQR} / P_{50}$).
  - `p95ToP50Ratio`: Tail latency indicator ($P_{95} / P_{50}$).

#### 3. [`CycleStartStatistics`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/CycleStartStatistics.java) & [`CycleStartScalars`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/CycleStartScalars.java)
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

#### 4. [`BatchProgressStatistics`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/BatchProgressStatistics.java) & [`BatchProgressScalars`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/BatchProgressScalars.java)
Sampled during batch execution progress.
- **Variables**: `upstreamCount`, `registeredWorkers`, `workerRank`, `contention`, and `avgServiceTime` (nanoseconds per frame).
- Evaluates correlation between `contention` and `avgServiceTime`.

#### 5. [`BatchCompleteStatistics`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/BatchCompleteStatistics.java) & [`BatchCompleteScalars`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/BatchCompleteScalars.java)
Sampled upon batch completion.
- **Variables**: `upstreamCount`, `registeredWorkers`, `workerRank`, `contention`, `avgServiceTime`, `throughput`.
- Evaluates correlations across `[contention, avgServiceTime, throughput]`.

#### 6. [`RawBodyCostStatistics`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/RawBodyCostStatistics.java)
Captures raw, unfiltered execution body cost samples (execution time in nanoseconds/cycles) across
`head`, `steadyState`, and `combined` windows, along with cumulative `totalCost` and `totalObservations`.

#### 7. [`DecisionStatistics`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/DecisionStatistics.java) & [`DecisionScalars`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/DecisionScalars.java)
Comprehensive telemetry for fragment action decisions (idle actions and execution actions).
- **Scalars**: `contention` and `smoothedBodyCost` (EMA of body execution cost).
- **Occupancy**: 5x5 branch decision grid.
- **Transitions**: 25-state Markov chain transitions.
- **Vector Fields**: 5x5 policy gradient vector fields.
- **Correlations**: Aligned correlation matrices across `[contentionPolicy, bodyPolicy, smoothedBodyCost]`.

---

### Policy Grid, Markov Transitions, and Vector Fields

Fragment action evaluation maps system state into a 2-dimensional 5x5 discrete grid ($25$ states):
- **Contention Bands (0..4)**: `XS` (0), `S` (1), `M` (2), `H` (3), `XH` (4)
- **Body Cost Bands (0..4)**: `XS` (0), `S` (1), `M` (2), `H` (3), `XH` (4)
- **State Index**: $\text{state} = \text{contentionBand} \times 5 + \text{bodyBand}$ ($0 \le \text{state} \le 24$).

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

#### 1. [`BranchOccupancyResult`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/BranchOccupancyResult.java), [`OccupancySummary`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/OccupancySummary.java), and [`OccupancyMesh`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/OccupancyMesh.java)
Tracks the frequency with which the fragment evaluates decisions in each cell of the 5x5 space.
- `exactCounts`: 5x5 array of raw decision counts.
- `probabilities`: Normalized 5x5 probability distribution ($\sum p_{ij} = 1.0$).
- `contentionCentroid` ($\mu_C$) & `bodyCentroid` ($\mu_B$): Center of mass in grid coordinates ($[0.0, 4.0]$).
- `contentionVariance` ($\sigma_C^2$), `bodyVariance` ($\sigma_B^2$), `contentionBodyCovariance` ($\sigma_{CB}$): Dispersion of decisions across bands.
- `radius`: Root-mean-square dispersion radius $\sqrt{\sigma_C^2 + \sigma_B^2}$.
- `distanceTo(other)`: Euclidean distance between two occupancy centroids.

#### 2. [`TransitionAnalysis`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/TransitionAnalysis.java)
Analyzes temporal state transitions across the 25 states.
- `transitionCounts`: 25x25 matrix where $M[A][B]$ is the count of direct transitions from state $A \to B$.
- `transitionProbabilities`: Row-stochastic transition probability matrix $P(B \mid A)$.
- `selfTransitionRate(state)`: Probability of remaining in the same state ($P(A \mid A)$), measuring decision stability.
- `dominantOutgoingState(state)` & `dominantOutgoingProbability(state)`: Most probable target state and its likelihood.
- `oscillation(stateA, stateB)`: Two-cell flapping score:
  $$\text{oscillation}(A, B) = \frac{M[A][B] + M[B][A]}{\sum_{k} (M[A][k] + M[k][A] + M[B][k] + M[k][B])}$$
  Values close to $1.0$ indicate rapid policy oscillation between state $A$ and state $B$.

#### 3. [`VectorField`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/VectorField.java) & [`VectorCell`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/VectorCell.java)
Computes a 5x5 spatial displacement field showing the direction and magnitude of policy movement from each cell.
- For each source cell $(i, j)$:
  - $\text{meanDeltaContention} = \frac{1}{N} \sum (\Delta i \cdot \text{count})$
  - $\text{meanDeltaBody} = \frac{1}{N} \sum (\Delta j \cdot \text{count})$
  - $\text{magnitude} = \sqrt{(\text{meanDeltaContention})^2 + (\text{meanDeltaBody})^2}$
- Identifies stable attractor cells (low magnitude, high self-transition) vs unstable gradient flows (high magnitude pointing toward attractors).

#### 4. [`CorrelationResult`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/CorrelationResult.java)
Contains paired Pearson (linear) and Spearman (rank-order) correlation matrices for aligned
multi-variable observations, enabling detection of linear dependencies and non-linear monotonic
relationships.

---

## 4. Exported Artifacts Summary

When an output directory is configured, each trial invocation produces the following TSV files
alongside their SHA-256 integrity checksums:

| Export File | Description | Source Structure |
|-------------|-------------|------------------|
| `trial_config.json` | Snapshot of the expanded trial configuration | [`TrialConfig`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/config/TrialConfig.java) |
| `benchmark_output.log` | Raw benchmark console output when retained | [`CalibrationRunner`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/CalibrationRunner.java) |
| `raw_observations.tsv` | Total event observation counts per iteration and core | [`CoreIterationResult`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/CoreIterationResult.java) |
| `statistics.tsv` | Descriptive & quantile scalar statistics across variables and segments | [`ScalarSummary`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/ScalarSummary.java) |
| `occupancy.tsv` | 5x5 branch occupancy counts, probabilities, centroids, and variances | [`BranchOccupancyResult`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/BranchOccupancyResult.java) |
| `transitions.tsv` | 25x25 Markov transition counts, probabilities, self-rates, and dominant targets | [`TransitionAnalysis`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/TransitionAnalysis.java) |
| `vector_fields.tsv` | 5x5 displacement vectors ($\Delta_C, \Delta_B$) and magnitudes | [`VectorField`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/VectorField.java) |
| `correlations.tsv` | Aligned Pearson and Spearman correlation matrices across observation dimensions | [`CorrelationResult`](file:///home/brandon/src/Euhedral-Execution/benchmarks/src/main/java/calibration/statistics/iteration/CorrelationResult.java) |
