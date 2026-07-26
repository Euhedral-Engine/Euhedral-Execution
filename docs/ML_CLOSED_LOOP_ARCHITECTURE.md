# ML Closed-Loop Architecture Review

This review covers `euhedral-core`, `euhedral-data-structures`, `euhedral-hardware-utils`,
`euhedral-hashing`, and `euhedral-training`. Spring, Reactor, and benchmark comparison modules are
intentionally excluded.

## Runtime architecture

Euhedral treats the machine as a hierarchy of independently controlled execution domains:

```text
ControlPlaneLattice       system / cross-socket ownership
        ↓
ControlPlaneShard         socket / NUMA ownership
        ↓
LatticeVertex + caches    topology-aware routing and shared-cache buffering
        ↓
ControlPlaneFragment      one pinned per-core feedback controller
        ↓
AbstractFrame.execute()   user work
```

### ControlPlaneLattice

The lattice owns global topology, the resource monitor, socket-level routing, shard lifecycle, and
system-wide rebalance. It is deliberately not the scheduler. It changes graph structure only when the
hardware topology or available CPU set changes.

### ControlPlaneShard

A shard owns one socket. It creates and drains per-core clones, maps the socket distributor to active
cores, forwards hardware snapshots, and preserves the drain-before-remap invariant during topology
changes.

### LatticeEdge and LatticeVertex

Demand travels upward while frames travel downward. `LatticeEdge` provides the pull chain and
upstream-handle serialization. `LatticeVertex` adds deterministic fan-out, optional remote caches,
and hash-based routing. The routing graph is structural; execution policy remains inside fragments.

### ControlPlaneCache and WorkRequester

`ControlPlaneCache` owns the fragment-local partitioned MPSC cache and its capacity controls.
`WorkRequester` converts cache occupancy and socket capacity into pull/request demand. This keeps
queue ownership local and prevents a central dispatcher from becoming a coherence hotspot.

### ControlPlaneFragment

Each fragment is pinned to one core and executes the online feedback loop. Its state vector is:

```text
completed
batch size
throughput
throughput coefficient of variation
upstream availability
remote-cache occupancy
bias
```

The 28 policy weights are four independently normalized seven-weight action vectors:

```text
request upstream work
execute remote cached work
execute directly from upstream
sleep
```

The policy chooses whether each action is enabled; the fragment still owns batch accounting,
cache-local execution, telemetry, pressure caps, and lifecycle. This is an important separation: the
ML policy changes decisions inside a bounded control surface rather than replacing the execution
engine.

The shipped runtime does not load a neural network. It receives a selected 28-weight policy and
inferences it with the existing dot products immediately. All expensive learning remains offline.

## Frame and identity model

`AbstractFrame` carries a stable `idHash` and mutable `routingHash`. Equal routing hashes preserve
ordering; randomized routing hashes allow parallel placement. Frames are reusable and may return to a
`FrameManager`, so the hot path avoids allocation and retains explicit lifecycle hooks for success,
error, cancellation, and recycling.

## Data-structure layer

The data-structure module supplies padded atomics and specialized SPSC/SPMC/MPSC/MPMC queues,
including partitioned and batch-drain variants. These are not generic implementation details: their
partitioning and cache-line isolation are part of the runtime architecture. Queue counters are allowed
to be eventually aggregated because the control loop consumes pressure signals, not transactional
inventory.

## Hardware layer

`SystemInfo`, `TopologyMapper`, `ResourceMonitor`, `ThreadTools`, and `PinnedThreadExecutor` translate
native topology into Java-level socket/core/cache ownership. The lattice receives topology and quota
changes, shards receive socket snapshots, and fragments receive core snapshots. This preserves a
single direction of hardware-state propagation and keeps native concerns out of routing and training.

## Hashing layer

`HasherApi` is the common deterministic identity and distribution primitive. The closed-loop work adds
an allocation-free `double[]` xxHash64 path so policy vectors can be identified without constructing
byte buffers. The same hash is used for corpus deduplication and deterministic dataset partitioning.

At roughly 69,000 vectors, the previous 32-bit `Arrays.hashCode` identity had a meaningful collision
risk. A 64-bit vector hash makes accidental corpus merging negligible while remaining faster than
serialization-based identity.

## Training architecture before the change

The training module already contained every stage, but the stages were manually connected:

```text
DataMerger
SequenceFinder.train
SequenceFinder.generate
BenchmarkRunner
manual file movement back into input/merger
```

Paths were mostly hard-coded, model generation and training were constructor side effects, benchmark
state was static, and a completed benchmark was not automatically promoted into the next corpus.
This made the process an open pipeline rather than a feedback system.

The original DL4J model also regressed all five benchmark quantiles with MSE. That objective spent
capacity fitting noisy absolute values even though the downstream operation only needed a ranking of
which policies should be benchmarked next.

## Closed-loop architecture

`ClosedLoopRunner` now owns only outer-loop orchestration:

```text
raw corpus
   ↓
DataMerger.mergeQuantiles
   ↓
SequenceFinder.train
   ↓
SequenceFinder.generateCandidates
   ↓
BenchmarkRunner.run
   ↓
transactional promotion into raw corpus
   └───────────────────────────────────────┘
```

The orchestrator does not reach into the lattice, shards, fragments, queues, or neural-network
internals. Each stage exposes a path-based API and remains independently runnable.

### Iteration commit point

The benchmark output is copied into `corpus/` only after merge, training, generation, and benchmarking
all succeed. A partial iteration therefore cannot alter the next training set. `state.properties` and
a `COMPLETE` marker make the workspace resumable.

### Model continuity

The first iteration may start from an existing ordinal DJL model directory. Every later iteration
trains from the previous best checkpoint against the expanded corpus, then uses that checkpoint to
screen the next candidate population.

The previous DL4J `.bin` checkpoints are intentionally not migrated. The raw and merged benchmark
corpus is the durable artifact; one fresh ordinal training pass produces the new checkpoint format.

### Exploration and exploitation

Candidate generation retains three populations:

1. Model-selected candidates from a large Sobol screen.
2. Global Sobol exploration candidates.
3. Gaussian perturbations around the best historical cluster centroid.

Local perturbations are normalized in the existing policy coordinate system. They are not remapped as
though they were fresh `[0, 1]` Sobol coordinates.

## Neural-network objective

The learning problem is ordinal policy classification, not scheduler inference and not raw throughput
regression.

The training partition is sorted with the same policy ordering used by candidate selection:

1. maximize P50 throughput
2. minimize P75-P25
3. minimize P90-P10

Nine cumulative labels are then assigned from training-only decile thresholds. The outputs represent
whether a policy clears the 10th, 20th, through 90th percentile of policy quality. This gives every
sample dense supervision while retaining a dedicated top-decile decision.

The network is deliberately small:

```text
28 policy weights
      ↓
128 GELU
      ↓
96 GELU
      ↓
48 GELU
      ↓
9 cumulative decile logits
```

The hidden widths are sufficient for nonlinear interactions between the four action vectors while
remaining cheap enough to screen millions of policies. GELU preserves information on both sides of
zero, which matters because normalized policy weights are signed.

There is no batch normalization. The input coordinate system is already normalized, and persistent
normalization statistics complicate continuation across an expanding corpus. There is no dropout:
the model is small relative to the corpus and dropout adds training noise and cost to a ranking
problem. AdamW, light label smoothing, and early stopping provide regularization instead.

The loss is class-balanced cumulative binary cross entropy. The top-decile threshold receives extra
weight. Checkpoint selection first maximizes validation top-10% precision and then minimizes weighted
validation loss when precision ties.

Independent cumulative outputs can cross. Candidate scoring projects their sigmoid probabilities onto
a monotonic sequence before combining expected decile and top-decile confidence.

## Training and screening data path

Training matrices are contiguous row-major `float[]` buffers rather than nested `double[][]` values or
one `DataSet` object per sample. The dataset is transferred to the selected device once and batched by
DJL.

Candidate screening reuses one feature buffer, one score buffer, one vector-reference batch, and a
pool of retained candidate objects. Network output is copied once per large batch instead of producing
a `double[]` for every candidate.

GPU defaults use batches of 4,096 for training and 65,536 for screening. CPU defaults are smaller.
Both remain configurable because the best size depends on corpus size, JVM heap, PCIe behavior, and
the particular machine.

## GPU and packaging boundary

The trainer moved from DL4J/ND4J platform artifacts to DJL's PyTorch engine because the RTX 5080 is a
Blackwell GPU and requires a CUDA 12.8-capable runtime.

The Maven distribution contains only the Java-side runtime:

```text
pytorch-engine 0.36.0
pytorch-jni 2.7.1-0.36.0
```

CUDA-enabled PyTorch 2.7.1 is installed once from the official CUDA 12.8 pip index. The packaged GPU
launcher discovers the virtual environment's `torch/lib` directory and exports
`PYTORCH_LIBRARY_PATH`, `PYTORCH_VERSION=2.7.1`, and `PYTORCH_FLAVOR=cu128` before starting DJL.

This is a stronger packaging boundary than a Linux-only native Maven artifact. No Maven Shade uber jar
is built, no libtorch binaries are copied into the trainer, and Java rebuilds never recompress the
multi-gigabyte CUDA runtime. The native runtime is isolated in one Ubuntu virtual environment and can
be upgraded or replaced independently from the trainer jar.

The trainer owns the only root `logback.xml`. Core and hardware-utils expose includeable Logback
fragments, which Maven copies into the trainer resources. This produces one deterministic logging
configuration without relying on shade-time resource collision behavior.

## Correctness issues found during integration

- The all-zero benchmark sentinel did not restore `FragmentActionPicker.halt` after an active policy.
- Candidate and cluster comparisons used P10 as P50 even though labels are stored as
  `[P10, P25, P50, P75, P90]`.
- Cluster score digests were created but never populated, so centroid exploitation was ranked from
  empty distributions.
- Train/validation/test assignment depended on read order. It now hashes vector identity with a fixed
  seed, keeping each vector in the same partition as the corpus grows.
- `Arrays.hashCode(double[])` was used as corpus identity. The loop now uses 64-bit `HasherApi` vector
  hashing.
- Fresh DL4J networks declared the output layer input width from the wrong hidden-layer index.
- The test-set top-decile overlap loops selected one fewer vector than requested.
- The benchmark halt barrier now represents actual policy state and static top-score state is reset
  between runs.
- Decile thresholds account for inclusive comparison so a distinct 100-sample corpus labels exactly
  ten policies as top-decile.

## Resulting control hierarchy

Euhedral now has two nested feedback systems with distinct time scales:

```text
Fast loop, per core, continuously:
state → fixed weighted actions → execution/cache effects → new state

Slow loop, only while tuning:
corpus → ordinal classifier → candidate policies → hardware measurements → expanded corpus
```

The fast loop remains deterministic, topology-aware, and allocation-conscious. The slow loop automates
the experimental reasoning process without moving training dependencies or global optimization logic
into the runtime engine.
