# Closed-Loop Training

The `closed-loop` command turns the existing manual active-learning stages into one resumable tuning
cycle:

```text
raw benchmark corpus
        ↓
normalize each machine/run and merge by vector
        ↓
train or continue the ordinal policy classifier
        ↓
screen Sobol candidates + inject exploration
        ↓
benchmark the selected policies in Euhedral
        ↓
promote the completed benchmark into the corpus
        └──────────────────────────────────────────┘
```

This is an offline tuning system, not a production runtime dependency. Users receive the scheduler
with the selected 28 weights; the neural network is used only while searching for better weights.

A benchmark is copied into the corpus only after the entire iteration succeeds. Partial iterations do
not affect later training.

## Build

The repository toolchain is defined by `mise.toml`. Build and install dependent modules without
compiling or running their tests, then run only the trainer tests:

```bash
mise install
mise exec -- mvn -B -pl euhedral-training -am install -Dmaven.test.skip=true
mise exec -- mvn -B -pl euhedral-training test
```

Package the thin trainer distribution:

```bash
mise exec -- mvn -B -pl euhedral-training -am package -Dmaven.test.skip=true
```

This produces a small executable jar, Java/JNI dependencies under `target/trainer/lib/`, and a GPU
launcher under `target/trainer/bin/`. CUDA-enabled PyTorch is installed once in an Ubuntu Python
virtual environment and loaded through `PYTORCH_LIBRARY_PATH`; it is not shaded or copied by Maven.
See `GPU_SETUP_UBUNTU.md` for RTX 5080 setup and verification.

## Run

From the repository root after activating the CUDA 12.8 PyTorch virtual environment:

```bash
source ~/.venvs/euhedral-pytorch/bin/activate

JAVA_OPTS="\
  -Dcycle.seed=input/merger \
  -Dcycle.workspace=output/closed-loop \
  -Dcycle.iterations=3 \
  -Dcycle.candidates=32768 \
  -DsourceRatio=0.5" \
euhedral-training/target/trainer/bin/euhedral-training-gpu closed-loop
```

The launcher discovers the Python wheel's `torch/lib`, configures DJL for PyTorch 2.7.1/CUDA 12.8,
and selects GPU 0. The default is one full iteration because candidate benchmarking can be extremely
expensive. Set `cycle.iterations` explicitly for repeated feedback.

For CPU-only execution, invoke the jar directly:

```bash
java \
  -Dtraining.device=cpu \
  -Dcycle.seed=input/merger \
  -Dcycle.workspace=output/closed-loop \
  -jar euhedral-training/target/trainer/euhedral-training-0.0.7-SNAPSHOT.jar \
  closed-loop
```

## Learning objective

The previous network attempted to regress `[P10, P25, P50, P75, P90]`. That solved a harder and less
relevant problem than candidate selection.

The new classifier learns nine cumulative decile decisions:

```text
policy is at or above P10 of policy quality
policy is at or above P20
...
policy is at or above P90 / top 10%
```

Policy quality uses the same lexicographic ordering as benchmark selection:

1. higher P50 throughput
2. lower P75-P25 spread
3. lower P90-P10 spread

The thresholds are derived only from the training partition. Validation and test samples therefore do
not influence label calibration.

The model is:

```text
28 inputs
  → 128 GELU
  → 96 GELU
  → 48 GELU
  → 9 ordinal logits
```

There is no dropout or batch normalization. The benchmark corpus is already large, the model is
small, and both layers add training/inference cost or state without helping the offline ranking goal.
Regularization comes from AdamW weight decay, early stopping, and light label smoothing.

Early stopping prioritizes validation top-10% precision and uses weighted binary cross entropy as the
tie-breaker. The top-decile output receives extra weight while all nine outputs are class-balanced.

## Main properties

| Property | Default | Purpose |
|---|---:|---|
| `cycle.seed` | `input/merger` | Initial directory of raw vector/measurement benchmark files |
| `cycle.workspace` | `output/closed-loop` | Corpus, models, candidates, state, and iteration outputs |
| `cycle.model` | none | Optional ordinal DJL model directory used to start iteration one |
| `cycle.iterations` | `1` | Number of complete feedback iterations |
| `cycle.candidates` | `32768` | Candidates generated and benchmarked per iteration |
| `cycle.resume` | `true` | Skip completed iterations and continue from their model |
| `cycle.stopFile` | `<workspace>/STOP` | Stop before promoting another stage/iteration |
| `sourceRatio` | all cores | Frame-source count as a fraction of core count |

Old DL4J `.bin` models are not compatible with the ordinal classifier. The benchmark corpus is still
fully reusable; train one fresh ordinal checkpoint and continue from that model directory afterward.

## Stage tuning

Training:

- `training.device=auto` — GPU 0 when available, otherwise CPU
- `training.seed=123`
- `training.maxEpochs=250`
- `training.patience=20`
- `training.batchSize=4096` on GPU, `512` on CPU
- `training.learningRate=0.001`
- `training.weightDecay=0.0001`
- `training.topDecileWeight=2.0`
- `training.labelSmoothing=0.02`

Candidate generation now combines three sources:

- a two-pass low-discrepancy Sobol screen, sampled from every empirical classifier-score decile
- multi-island, full-covariance CMA-ES proposals seeded by measured historical winners
- direct unscreened Sobol vectors that bypass the classifier completely

The score-band budget is intentionally top-heavy but nonzero in every band. Candidate order is
shuffled after selection so classifier score does not line up with thermal or temporal benchmark
drift. CMA-ES normalizes each seven-weight action chunk after sampling while retaining a full 28x28
covariance matrix, so it can learn both within-action and cross-action relationships.

Candidate generation:

- `candidate.screenLimit=2097152`
- `candidate.batchSize=65536` on GPU, `16384` on CPU
- `candidate.sobolSkip=131072`
- `candidate.sobolStride=screenLimit+cycle.candidates`
- `candidate.clusters=28`
- `candidate.clusterIterations=500`
- `candidate.localSigma=0.05`
- `candidate.seed=123`

Each closed-loop iteration advances by `candidate.sobolStride`, so it screens a new deterministic
Sobol region rather than rescoring the same population. Model-selected candidates are written in
best-first order before global and local exploration vectors.

Source-count coverage:

- `benchmark.sourceCounts=1,2,4,8` sets explicit absolute source counts
- `benchmark.sourceRatios=0.25,0.5,1.0` derives counts from available cores
- `benchmark.sourceConfigurationsPerIteration=2` controls the rotating subset per iteration

Each source count is benchmarked in a newly constructed lattice and written to a separate raw file.
The merger normalizes those files independently before combining equal policy vectors, preventing
high-source-count raw throughput from dominating the universal policy objective. Between policy
trials, sources are paused behind an in-flight callback barrier and the lattice explicitly resets all
socket and fragment caches.

Benchmarking:

- `benchmark.repetitions=10`
- `benchmark.sampleMillis=200`
- `benchmark.livenessMillis=50`
- `benchmark.framesPerSource=100000`

## Allocation and throughput changes

The training and screening paths now use contiguous row-major `float[]` matrices instead of nested
`double[][]` arrays and per-sample `DataSet` objects. Candidate screening reuses:

- one feature buffer
- one score buffer
- one vector-reference batch
- recycled retained-candidate objects

The GPU sees large batched matrices, while the JVM avoids constructing a prediction array for every
candidate. The unavoidable output transfer is one contiguous logit array per batch.

## Workspace layout

```text
output/closed-loop/
├── corpus/
│   ├── seed-0000-...
│   └── iteration-0001.txt
├── iteration-0001/
│   ├── merge/training-data.txt
│   ├── model/best/
│   │   └── euhedral-policy-ranker-0000.params
│   ├── candidates/vectors.txt
│   ├── benchmark/raw-data.txt
│   ├── benchmark/results.txt
│   ├── state.properties
│   └── COMPLETE
├── latest-model/
│   └── euhedral-policy-ranker-0000.params
└── latest-training-data.txt
```

Vector identity and deterministic train/validation/test partitioning use the allocation-free
`HasherApi.getHash(double[])` path. The same vector therefore remains in the same dataset partition as
the corpus grows.
