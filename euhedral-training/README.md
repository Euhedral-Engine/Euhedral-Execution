# Euhedral training

The training module searches for good values for Euhedral's 28 policy weights. It can merge
benchmark data, train the ordinal ranker, generate candidate policies, benchmark them, and run all
of those stages as a resumable closed-loop process.

This is an offline tuning tool. The trained network is used to find policies; it is not loaded by
the Euhedral runtime.

## Build

The repository uses the tools in the root `mise.toml`. From the repository root:

```bash
mise install
mise exec -- mvn -B -pl euhedral-training -am install -Dmaven.test.skip=true
mise exec -- mvn -B -pl euhedral-training test
```

To build the runnable distribution:

```bash
mise exec -- mvn -B -pl euhedral-training -am package -Dmaven.test.skip=true
```

The distribution is written to `euhedral-training/target/trainer/`. It contains the trainer jar,
runtime libraries, and the GPU launcher. Java 21 and the native Euhedral library are required when
running benchmarks. The native build uses Zig; `mise install` supplies the configured version.

For an NVIDIA GPU, follow [GPU_SETUP_UBUNTU.md](GPU_SETUP_UBUNTU.md). The GPU launcher expects a
PyTorch 2.7.1 CUDA 12.8 environment and discovers PyTorch's native libraries automatically.

## Commands

The executable jar is:

```text
euhedral-training/target/trainer/euhedral-training-0.0.7-SNAPSHOT.jar
```

The version can change, so the launcher is usually more convenient:

```bash
euhedral-training/target/trainer/bin/euhedral-training-gpu <command>
```

The launcher forwards leading `-D`, `-X`, `--add-opens`, and `--enable-preview` arguments to the
JVM. For CPU-only work, use the jar directly and set `training.device=cpu`.

### Inspect the training environment

```bash
java -Dtraining.device=cpu \
  -jar euhedral-training/target/trainer/euhedral-training-0.0.7-SNAPSHOT.jar \
  training-info
```

With the GPU launcher, `training-info` reports the selected DJL engine, GPU count, CUDA runtime,
compute capability, and memory usage.

### Merge benchmark vectors

`merge-vectors` deduplicates 28-weight vectors from files in a directory. It is useful when the
input files contain policy vectors but their measurements are not needed yet.

```bash
java -Dmerger.input=input/merger \
  -Dmerger.vectors.output=output/merger/merged-vectors \
  -jar euhedral-training/target/trainer/euhedral-training-0.0.7-SNAPSHOT.jar \
  merge-vectors
```

### Merge benchmark measurements

`merge-quantiles` normalizes each benchmark file in parallel, then merges equal policy vectors into
training rows containing one vector followed by five measurements: P10, P25, P50, P75, and P90.
The default input is `input/merger`; the default output directory is `output/merger`.

```bash
java -Dmerger.input=input/merger \
  -Dmerger.output=output/merger \
  -jar euhedral-training/target/trainer/euhedral-training-0.0.7-SNAPSHOT.jar \
  merge-quantiles
```

`merge-data` is retained as an alias for `merge-quantiles`.

### Train the policy ranker

Training reads a merged file through the required `data` property and writes a DJL model directory
through `model.output`:

```bash
euhedral-training/target/trainer/bin/euhedral-training-gpu \
  -Ddata=output/merger/merged-quantiles.txt \
  -Dmodel.output=output/model/best \
  train-vector-finder
```

To continue training from an existing ordinal model:

```bash
euhedral-training/target/trainer/bin/euhedral-training-gpu \
  -Ddata=output/merger/merged-quantiles.txt \
  -Dmodel=output/model/best \
  -Dmodel.output=output/model/next \
  train-vector-finder
```

The model predicts nine cumulative quality bands, from at least P10 through at least P90. A legacy
DL4J `.bin` model cannot be loaded by this ranker; train a fresh ordinal model from the existing
benchmark data instead.

### Generate candidates

Candidate generation uses the same `train-vector-finder` command with `generate` present. It needs
historical training data and optionally an ordinal model. The generator combines classifier-scored
Sobol exploration, CMA-ES proposals, score-band sampling, and direct Sobol exploration that bypasses
the classifier.

```bash
euhedral-training/target/trainer/bin/euhedral-training-gpu \
  -Ddata=output/merger/merged-quantiles.txt \
  -Dmodel=output/model/best \
  -Dgenerate \
  -Dcandidate.output=output/candidates/vectors.txt \
  -Dcandidate.count=32768 \
  train-vector-finder
```

The closed-loop runner calls this API directly and is the recommended way to combine training and
generation.

### Benchmark policies

With no file argument, `benchmark` generates Sobol policies. With a file argument, it benchmarks the
28-weight vectors in that file:

```bash
euhedral-training/target/trainer/bin/euhedral-training-gpu \
  -Dbenchmark.sourceCounts=1,2,4,8 \
  -Dbenchmark.output=output/benchmark/raw.txt \
  -Dbenchmark.results=output/benchmark/results.txt \
  benchmark output/candidates/vectors.txt
```

For a quick CPU smoke test, reduce the workload:

```bash
java -Dtraining.device=cpu \
  -Dbenchmark.sourceCounts=1 \
  -Dbenchmark.repetitions=2 \
  -Dbenchmark.sampleMillis=50 \
  -Dbenchmark.framesPerSource=1000 \
  -jar euhedral-training/target/trainer/euhedral-training-0.0.7-SNAPSHOT.jar \
  benchmark output/candidates/vectors.txt
```

Each policy is run with the configured source count, then the sources are paused and the lattice is
reset before the next policy. Results contain the measured quantiles and the highest-throughput
weight vectors.

### Run the closed loop

`closed-loop` performs this sequence:

```text
seed benchmark files
  -> normalize and merge
  -> train or continue the ordinal ranker
  -> generate candidates
  -> benchmark candidates
  -> promote successful results into the corpus
```

The corpus is only promoted after benchmarking completes. A failed or stopped iteration leaves its
artifacts in place without changing the corpus. This makes it safe to resume:

```bash
euhedral-training/target/trainer/bin/euhedral-training-gpu \
  -Dcycle.seed=input/merger \
  -Dcycle.workspace=output/closed-loop \
  -Dcycle.iterations=3 \
  -Dcycle.candidates=32768 \
  -Dbenchmark.sourceCounts=1,2,4,8 \
  closed-loop
```

For CPU-only training and benchmarking:

```bash
java -Dtraining.device=cpu \
  -Dcycle.seed=input/merger \
  -Dcycle.workspace=output/closed-loop \
  -Dcycle.iterations=1 \
  -Dcycle.candidates=1024 \
  -Dbenchmark.sourceCounts=1 \
  -Dbenchmark.repetitions=2 \
  -Dbenchmark.sampleMillis=50 \
  -Dbenchmark.framesPerSource=1000 \
  -jar euhedral-training/target/trainer/euhedral-training-0.0.7-SNAPSHOT.jar \
  closed-loop
```

Set `cycle.model` to an existing ordinal model directory to start from that model. With
`cycle.resume=true`, completed iterations are skipped and the latest model is reused. Create the
configured stop file, normally `output/closed-loop/STOP`, to stop before the next stage is promoted.

## Properties

All properties are JVM system properties, passed with `-Dname=value` before the command.

### Closed-loop properties

| Property | Default | Meaning |
| --- | --- | --- |
| `cycle.seed` | `input/merger` | Directory containing seed benchmark files |
| `cycle.workspace` | `output/closed-loop` | Directory for corpus, models, candidates, and state |
| `cycle.model` | unset | Existing ordinal model directory |
| `cycle.iterations` | `1` | Number of iterations to run |
| `cycle.candidates` | `32768` | Candidate vectors per iteration |
| `cycle.resume` | `true` | Skip completed iterations and reuse their model |
| `cycle.stopFile` | `<workspace>/STOP` | File that requests a clean stop |

### Training and candidate properties

| Property | Default | Meaning |
| --- | --- | --- |
| `training.device` | `auto` | `auto`, `cpu`, or a DJL device name such as `gpu0` |
| `training.seed` | `123` | Deterministic train/validation/test split and training seed |
| `training.maxEpochs` | `250` | Maximum training epochs |
| `training.patience` | `20` | Early-stopping patience |
| `training.batchSize` | device-dependent | Training batch size; GPU defaults to 4096 and CPU to 512 |
| `training.learningRate` | `0.001` | AdamW learning rate |
| `training.weightDecay` | `0.0001` | AdamW weight decay |
| `training.topDecileWeight` | `2.0` | Extra weight for the top-decile output |
| `training.labelSmoothing` | `0.02` | Ordinal label smoothing |
| `candidate.screenLimit` | `2097152` | Sobol vectors scored during screening |
| `candidate.batchSize` | device-dependent | Candidate scoring batch size; GPU defaults to 65536 and CPU to 16384 |
| `candidate.sobolSkip` | `131072` | Starting Sobol index |
| `candidate.sobolStride` | `screenLimit + cycle.candidates` | Sobol offset between closed-loop iterations |
| `candidate.directSobolFraction` | `0.0625` | Fraction reserved for direct Sobol exploration |
| `candidate.seed` | `123` | Candidate and CMA-ES random seed |
| `candidate.cmaEnabled` | `true` | Enable CMA-ES proposals |
| `candidate.cmaIslands` | `4` | Number of CMA-ES islands |
| `candidate.cmaGenerations` | `12` | Generations per island |
| `candidate.cmaPopulation` | `96` | Population size per generation |
| `candidate.cmaSigma` | `0.20` | Initial CMA-ES sigma |

### Benchmark properties

| Property | Default | Meaning |
| --- | --- | --- |
| `benchmark.sourceCounts` | unset | Comma-separated absolute source counts |
| `benchmark.sourceRatios` | `0.25,0.5,1.0` | Source counts as fractions of available cores |
| `benchmark.sourceConfigurationsPerIteration` | `2` | Rotating source configurations per closed-loop iteration |
| `sourceRatio` | all cores | Legacy single-run source ratio |
| `benchmark.repetitions` | `10` | Repetitions per policy |
| `benchmark.sampleMillis` | `200` | Duration of each repetition |
| `benchmark.livenessMillis` | `50` | No-progress timeout during a repetition |
| `benchmark.framesPerSource` | `100000` | Pre-generated frames per source |
| `benchmark.resetTimeoutMillis` | `2000` | Timeout for pausing and resetting between policies |
| `benchmark.output` | `output/benchmark/raw_data.txt` | Single-run raw output |
| `benchmark.results` | `output/results.txt` | Single-run summary output |

For repeatable experiments, set `benchmark.sourceCounts` explicitly. `sourceRatios` depends on the
machine's available core count.

## Workspace layout

After a closed-loop run, the workspace looks like this:

```text
output/closed-loop/
    corpus/
        seed-0000-...
        iteration-0001-source-....txt
    iteration-0001/
        merge/training-data.txt
        model/best/
        candidates/vectors.txt
        benchmark/raw/
        benchmark/results/
        state.properties
        COMPLETE
    latest-model/
    latest-training-data.txt
```

`state.properties` records the current stage and artifact. `COMPLETE` is written only after the
iteration has produced benchmark output. If a run stops during training or benchmarking, inspect
the iteration directory, remove or keep the workspace as needed, and rerun with `cycle.resume=true`.

## Practical notes

- Start with one source count, a small candidate count, and short samples to validate a new machine.
- Use the GPU launcher for model training and large candidate screens; CPU mode is useful for smoke
  tests and small experiments.
- Keep benchmark files from different machines in the seed directory. The merger normalizes each
  file before combining equal policy vectors.
- The benchmark objective prefers higher P50 throughput, then lower interquartile spread, then lower
  overall spread.
- Do not use a legacy `.bin` model with the ordinal ranker. Reuse its benchmark corpus instead.
- If the machine is interrupted, the corpus remains at the last successful promotion point.

The command implementations are in [Runner.java](src/main/java/io/euhedral_execution/training/Runner.java),
[ClosedLoopRunner.java](src/main/java/io/euhedral_execution/training/ClosedLoopRunner.java),
[BenchmarkRunner.java](src/main/java/io/euhedral_execution/training/BenchmarkRunner.java), and
[SequenceFinder.java](src/main/java/io/euhedral_execution/training/SequenceFinder.java).
