# Offline Policy-Training Architecture

This document describes the slower feedback loop in `euhedral-training`: how benchmark results
become training data, how the ordinal model selects new policies, and how successful measurements are
committed back to the corpus.

It does not duplicate the execution-engine design. The pull graph, routing, frame lifecycle,
topology, queue ownership, and 28-weight runtime contract are documented in
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md). Commands, system properties, distribution layout, and
GPU setup belong to [`euhedral-training/README.md`](../euhedral-training/README.md) and
[`GPU_SETUP_UBUNTU.md`](../euhedral-training/GPU_SETUP_UBUNTU.md).

## Boundary with the runtime

The training module is an offline policy search tool. It produces vectors for the runtime to
benchmark; it does not load a neural network into `euhedral-core`. At runtime,
`ControlPlaneFragment` evaluates a selected 28-value policy with its fixed weighted action picker.
The model and DJL dependencies remain in `euhedral-training`.

The policy vector is the runtime's input contract:

```text
28 weights = 4 action groups x 7 values
```

The four action groups control requesting upstream work, executing remote cached work, executing
directly from upstream, and sleeping. Their measurements and routing semantics are runtime concerns;
this document only describes how candidate vectors are learned and selected.

## Closed-loop stages

`ClosedLoopRunner` owns orchestration but not model or benchmark internals:

```text
seed benchmark files
        |
        v
bootstrap corpus
        |
        v
DataMerger.mergeQuantiles
        |
        v
SequenceFinder.train
        |
        v
SequenceFinder.generateCandidates
        |
        v
BenchmarkRunner.runAcrossSourceCounts
        |
        v
atomic promotion into corpus
```

Each stage consumes paths and writes an explicit artifact:

| Stage | Input | Output | Responsibility |
| --- | --- | --- | --- |
| Bootstrap | seed directory | `workspace/corpus/` | Copy seed benchmark files into the durable corpus when it is empty |
| Merge | corpus benchmark files | `merge/training-data.txt` | Normalize measurements and combine equal policy vectors |
| Train | merged vectors and quantiles | `model/best/` | Fit or continue the ordinal DJL model |
| Generate | merged data and model | `candidates/vectors.txt` | Select a bounded candidate population for measurement |
| Benchmark | candidate vectors | raw files and summaries | Measure candidates across configured source counts |
| Promote | successful raw benchmark files | new corpus files | Publish the next training evidence atomically |

The same operations are independently available through `Runner`; the README is the source of truth
for their command-line forms and properties.

## Benchmark data contract

Every benchmark record is a pair of arrays:

```text
[28 policy weights]
[P10, P25, P50, P75, P90 measurements]
```

`DataMerger` processes each source file independently. It scales measurements by that file's 99th
percentile of observed means and clamps them to `[0, 1]`, so machines and source configurations can
contribute comparable evidence. Equal vectors are then grouped and their measurements merged into
the five output quantiles. `merge-vectors` is the vector-only variant for deduplicating candidate
files without measurements.

Training rows remain path-based text artifacts rather than serialized model state. The merged corpus
is therefore inspectable, portable between machines, and sufficient to rebuild a model.

## Deterministic training split and ranking

`SequenceFinder` hashes each 28-weight vector with a fixed seed before assigning it to partitions.
The split is deterministic as the corpus grows: 80% of vectors are used for training, 10% for
validation, and 10% for testing. Decile thresholds are calculated from the training partition only,
so validation and test data cannot influence labels.

Policies are ordered by the shared `PolicyRanking` comparator:

1. higher P50 throughput;
2. lower interquartile range, `P75 - P25`;
3. lower tail range, `P90 - P10`.

Nine cumulative labels indicate whether a policy reaches the 10th through 90th percentile of that
training-only ordering. This is ordinal classification rather than regression: candidate selection
needs a useful ranking and top-decile signal, not precise predictions of noisy absolute quantiles.

## Ordinal model

The model is a compact DJL PyTorch multilayer perceptron:

```text
28 policy weights
      |
      v
128 GELU
      |
      v
96 GELU
      |
      v
48 GELU
      |
      v
9 cumulative decile logits
```

Training uses contiguous row-major `float[]` matrices and batches the data on the selected device.
The loss is class-balanced cumulative binary cross entropy, with additional weight on the top-decile
output. AdamW, label smoothing, and early stopping provide regularization. Checkpoint selection
prioritizes validation top-decile precision, then weighted validation loss.

The nine outputs are scored independently by the network. Candidate scoring projects their sigmoid
probabilities onto a monotonic sequence before combining expected ordinal quality and top-decile
confidence.

An existing ordinal DJL directory may seed the next iteration. Legacy DL4J `.bin` checkpoints are not
loadable and are intentionally not migrated; the benchmark corpus is the durable input for a fresh
ordinal model.

## Candidate generation

Candidate generation balances exploitation and exploration rather than trusting one model-ranked
list. It combines:

1. CMA-ES proposals scored by the ordinal model;
2. Sobol points screened in large inference batches and sampled across score bands, with capacity
   weighted toward high bands;
3. a direct Sobol exploration fraction that bypasses the classifier.

Vectors are normalized in the policy coordinate system before hashing and deduplication. The Sobol
offset advances between closed-loop iterations, preventing every iteration from replaying the same
low-index sequence. The final candidate file contains only vectors; measurements are produced by the
next benchmark stage.

## Benchmark feedback and commit semantics

`BenchmarkRunner` measures each candidate across the configured source-count scenarios and writes raw
measurements plus summaries under the iteration directory. It pauses and resets the lattice between
policy trials so buffered work and controller state do not leak between candidates.

The corpus commit point is after benchmarking succeeds:

1. write the iteration model, candidates, raw measurements, summaries, and `state.properties`;
2. write `COMPLETE`;
3. copy each raw benchmark file to a temporary sibling;
4. atomically move the temporary file into `workspace/corpus/`.

If training, generation, benchmarking, or promotion stops early, the previous corpus remains the
input for the next run. Resume can reuse a completed iteration, repair a promotion from retained raw
files, and publish `latest-model` and `latest-training-data.txt` for inspection.

The resulting two-level feedback system is intentional:

```text
Fast runtime loop:  state -> fixed policy actions -> execution effects -> new state
Slow tuning loop:   corpus -> ordinal model -> candidate policies -> measurements -> corpus
```

The runtime remains deterministic and topology-aware while the training module automates the
experimental loop around it. For operational examples, property tables, workspace layout, and
launcher behavior, use the training README rather than maintaining a second copy here.

## Checkpoint-backed final packaging

The upgraded typed closed loop treats one validated `ClosedLoopCheckpoint` revision as the sole
authority for final packaging. Its referenced Phase 1 merge, Phase 2 model, Phase 3 schedule, raw
evidence, and checkpoint sidecars are streamed into a shallow `training-run-<package-id>`
directory. Package-derived CSVs add a clearly named vectors-with-measurements view and a
scenario/run-aware benchmark-ready vector view without changing source artifact bytes.

The packager writes deterministic UTF-8/LF datasets, reports, reproduction inputs, and a canonical
manifest. It validates checksums, logical row counts, provenance, lifecycle omissions, model
members, schedule identity, raw bundles, and detached checkpoint references before publishing the
directory with `ATOMIC_MOVE`. Mutable builders and digest buffers are confined to the packaging
thread; the atomic rename is the publication boundary. Raw observations and model parameter files
are streamed, so repetition-scale evidence and model members do not pollute the Java heap.

Complete runs and recoverable or terminal partial checkpoints use distinct deterministic package
IDs. Repeating an identical request is idempotent, while a conflicting target is never overwritten.
The `package-run` command rebuilds the package from its recorded immutable checkpoint and
`provenance/package-inputs.properties`; it does not rerun the physical experiment.
