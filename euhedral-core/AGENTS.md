# Working in `euhedral-core`

This guide applies to `euhedral-core`. Read the repository-wide [AGENTS.md](../AGENTS.md) first,
then use this file when the task changes or depends on core. Read another module's guide only when
that module is also relevant to the task.

`euhedral-core` owns the execution engine: source demand, two-level routing, per-core worker
control,
frame execution and recycling, ingest helpers, configuration, and core metrics. The checked-out
implementation and nearest tests are authoritative.
Use [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)
as context, not as a substitute for verifying current code; portions of its policy description can
lag the implementation.

## Module boundary

The module applies the shared Java conventions from `build-logic` and uses the repository's Java 21
toolchain. Its Gradle API dependencies are `euhedral-data-structures`, `euhedral-hardware-utils`,
`euhedral-hashing`, SLF4J, Micrometer, and JSpecify. Jackson is an implementation dependency. See
[build.gradle.kts](build.gradle.kts) and [module-info.java](src/main/java/module-info.java) before
changing dependencies or public packages.

Every core package except `io.euhedral_execution.core.internal` is exported. In particular, `impl`
and `utils` are public and consumed outside this module despite their names. Public API changes can
affect Reactor, Spring, benchmarks, and CFD; compile or test the relevant consumers rather than
assuming a passing core test task proves compatibility.

| Area            | Source ownership                                                                              |
|-----------------|-----------------------------------------------------------------------------------------------|
| `config`        | Public lattice, fragment, cache, clone, idle, and timing configuration                        |
| `control_plane` | Lattice lifecycle, socket shards, per-core fragments, caches, requests, and scheduling policy |
| `flow_control`  | Edges, vertices, routing, upstream handles, and worker-local upstream queues                  |
| `frames`        | Frame identity, execution data, cancellation, routing metadata, and pipeline stages           |
| `generics`      | Source, receiver, terminal, interceptor, executor, and clone contracts                        |
| `impl`          | Default executor, clone wiring, frame factories, and frame recycling                          |
| `ingest`        | Array, queue, single-use, and pipeline input sources                                          |
| `metrics`       | Micrometer execution/cache instruments and aggregation helpers                                |
| `utils`         | Flow context, VarHandle helpers, calibration, timing, spinning, and math                      |
| `internal`      | Non-exported module constants only                                                            |

## Read by change, not by directory sweep

Start with the implementation and tests nearest the changed contract:

| Change                                        | Primary implementation                                                                                                 | Executable specifications                                                                                                                                       |
|-----------------------------------------------|------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Lattice lifecycle or global topology          | `control_plane/ControlPlaneLattice.java`                                                                               | `control_plane/ControlPlaneLatticeTest.java`                                                                                                                    |
| Socket/core remapping or clone lifecycle      | `control_plane/ControlPlaneShard.java`, `impl/BaseCloneableObject.java`                                                | `control_plane/ControlPlaneShardTest.java`, `impl/BaseCloneableObjectTest.java`                                                                                 |
| Worker loop, cache, demand, or policy         | `control_plane/ControlPlaneFragment.java`, `ControlPlaneCache.java`, `WorkRequester.java`, `FragmentDecisionTree.java` | `ControlPlaneFragmentTest.java`, `ControlPlaneFragmentThreadTest.java`, `ControlPlaneCacheTest.java`, `WorkRequesterTest.java`, `FragmentDecisionTreeTest.java` |
| Routing or source scheduling                  | `flow_control/LatticeEdge.java`, `LatticeVertex.java`, `UpstreamQueue.java`                                            | `flow_control/LatticeEdgeTest.java`, `LatticeVertexTest.java`, `UpstreamQueueTest.java`                                                                         |
| Frame lifecycle or pipelines                  | `frames/`, `impl/FrameFactory.java`, `impl/FrameManager.java`, `generics/AbstractExecutor.java`                        | `frames/AbstractFrameTest.java`, `PipelineFrameTest.java`, `impl/FrameManagerTest.java`, `DefaultExecutorTest.java`                                             |
| Ingest behavior                               | `ingest/`                                                                                                              | `ingest/ArrayIngestSinkTest.java` plus the source/flow tests that exercise the affected path                                                                    |
| Idle timing or generated participation policy | `config/IdlePolicy.java`, `control_plane/ParticipationLogisticModel.java`                                              | `config/ProductionIdlePolicyTest.java`, `IdleTimingFunctionTest.java`, `control_plane/ParticipationLogisticModelTest.java`                                      |
| Metrics                                       | `metrics/` and metric call sites in `control_plane/`                                                                   | Relevant fragment/cache tests; add direct metric tests when changing meter lifecycle or aggregation                                                             |

Paths in the table are relative to `src/main/java/io/euhedral_execution/core/` or
`src/test/java/io/euhedral_execution/core/` as appropriate.

## Runtime ownership and lifecycle

The normal data path is:

```text
ingest source
  -> global LatticeVertex (physical socket selection)
  -> ControlPlaneShard LatticeVertex (physical core selection)
  -> ControlPlaneFragment cache/worker loop
  -> AbstractExecutor
```

- `ControlPlaneLattice` is the JVM-wide runtime owner. `getOrCreate` returns the singleton;
  `addUpstream` starts it lazily and waits for readiness before attaching the source.
- A lattice owns its resource monitor, global distributor, shards, control-plane executor, shutdown
  hook, and singleton slot. `close()` is permanent and idempotent and releases the singleton.
- Lattice close calls process-wide `PinnedThreadExecutor.closeAll()`. Tests sharing a JVM must
  isolate
  lattice lifecycle and restore global/static state.
- A shard owns one socket distributor and clones one pipeline for each effective physical core. A
  clone joins a `ControlPlaneFragment` to an executor through `BaseCloneableObject`.
- `start()` and `ready()` are different contracts. Fragment startup publishes owner-local policy and
  upstream state only after pinned-thread initialization; do not publish work merely because a
  component reports started.
- Global topology changes drain ingest, publish the new socket mapping, start or update active
  shards, retire old shards, then resume ingest. Shard changes similarly remap physical cores and
  drain, close, or replace clones. Preserve timeout, shutdown, and partial-retirement paths.
- `ControlPlaneLattice.clear(Duration[, Runnable])` is a destructive trial-reset boundary, not
  normal
  shutdown. Pause producers first. It freezes ingest, waits out rebalancing, asks every shard to
  reset owner-local state under one deadline, runs the callback while drained, and resumes ingest in
  `finally`.
- Running fragment caches, policy state, flow context, and upstream scheduling state are
  owner-thread
  data. Reset them through the fragment reset/acknowledgment protocol, not by clearing them from the
  caller thread.

Always close lattices, fragments, pinned executors, metric resources, and test fixtures at the
ownership boundary. Tests that touch singleton state, affinity, topology mocks, system properties,
static routing registries, or work-steal slots must not race one another.

## Routing, sparse IDs, and source contracts

Work moves downstream through `push`; demand moves upstream through `request` and `pull`.
`LatticeEdge` provides connectivity and shared upstream-handle registration. `LatticeVertex` adds
fan-out routing and compact active-index mappings. The topology distributors use those mappings for
physical socket/core IDs; fragment-local vertices can use logical lane IDs instead.

### Physical IDs versus active indexes

For the global/socket and shard/core topology distributors, this distinction is mandatory:

- `LatticeVertex.downstreams` and handle arrays are indexed by physical socket/core ID and can
  contain inactive gaps.
- The `BitSet` passed to `setDownstreamMapping` contains physical IDs.
- `RoutingState.mappings` converts a compact active routing index to a physical ID.
- `getActiveDownstreamIndex(physicalId)` performs the reverse conversion.
- A `RoutingFunction` returns a compact index in `[0, mapSize)`, never a physical ID.
- Publish both mapping directions together and only while the vertex is drained.

Do not assume socket or core IDs are contiguous. Preserve sparse IDs, remaps, inactive origins, and
unknown origins in routing tests. Do not reopen ingest with an empty active mapping.

`ControlPlaneCache` and the optional SMT split inside `ControlPlaneFragment` are exceptions: their
local vertices map logical lane IDs `0` and `1`, not physical socket/core IDs. Preserve the compact
index contract without imposing topology semantics on those local mappings.

### Policies, hashes, and origins

- `ANYWHERE` uses hash fallback at both topology levels.
- `SOCKET_LOCAL` uses the captured physical socket when active, then hash-routes within the shard.
- `CACHE_LOCAL` uses the captured physical socket and core when both remain active.
- Null, negative, out-of-range, absent, or inactive origins fall back to hash routing.
- Global hash fallback uses `routingHash`; shard fallback rotates the hash before selecting a core.
  Do not describe current global routing as CPU-weighted merely because a weighted map is retained.
- `AbstractFrame.idHash` is immutable. `routingHash == idHash` means ordered routing;
  `randomizeHash(seed)` enables parallel placement and `resetHash()` restores ordered routing.
- Ordering is a stable-lane property under a stable mapping. It does not restore input order after
  parallel stages, cross independent sources, override locality, or survive arbitrary topology
  changes as an end-to-end guarantee.
- `FrameFactory` captures hardware origin once at construction and reapplies it during create/reuse.
  Treat a factory as owner-thread/origin-affine unless deliberately redesigning that behavior.

### Pull and request

- `pull` is synchronous consumption of already-available work. It must not accumulate demand,
  manufacture frames, or route through the registered downstream.
- A pull must leave the first stop-matching frame unconsumed. Core uses this to stop direct
  execution
  at ordered frames so they can take the request-and-route path.
- `request` is the routed path and may accumulate demand at a source.
- Sources must ignore non-positive demand, respect demand bounds, complete downstream once, and
  preserve ownership when a consumer stops.
- `SingleUseSource.pull()` currently ignores its stop condition, consumes the frame, and completes.
  Do not rely on it to preserve an ordered frame at the source. Treat this as a known contract gap
  and add a dedicated `SingleUseSourceTest` before changing or depending on ordered direct pulls.
- `LatticeVertex.UpstreamInterceptor` serializes `request`/`pull` for one shared registration. It
  does
  not protect direct calls to the source or duplicate registrations.
- `UpstreamQueue` keeps worker-local scheduling/productivity observations. A stopped direct pull
  uses
  a pending request preference so the same source can progress through routing without starving
  other sources under contention.

When changing source or queue behavior, cover demand bounds, stop preservation, completion,
contention/acquisition failure, reset, and multiple registered sources. Existing ingest coverage is
not complete for every source type; add focused tests instead of inferring behavior from another
source implementation.

## Fragment loop, caches, and decision policy

`ControlPlaneFragment` owns a pinned hot loop. Its primary fragment can own an optional SMT buddy.
The owner thread initializes `FragmentDecisionTree`, `UpstreamQueue`, `FlowContext`, and related
plain fields; the `initialized` readiness publication makes that state visible to other threads.

The active loop order is:

1. Handle a pending reset and sample upstream/cache state.
2. Drain local cached work first.
3. Select `DIRECT`, `STAGED`, or `IDLE`.
4. Pull direct work or issue staged/routed demand.
5. Try one cursor-selected worker-cache steal when still unproductive.
6. Park/yield only when no work progressed.

There is no active `CACHE` execution-path enum. `DIRECT` can pull already-available unordered work
and requests routed work after a miss. `STAGED` requests routed work. `IDLE` is guarded
participation
withdrawal, not a cache-consumption mode.

- `ControlPlaneCache` is partitioned MPSC: routed producers are concurrent, while the fragment is
  the
  normal single consumer. Work stealing must retain partition locking so owner and thief do not
  concurrently consume the same single-consumer partition.
- Cache pressure and adaptive caps limit demand/batch targets; they are not hard memory limits for a
  growable queue.
- Direct-pull sources must leave ordered frames for request-and-route. `ControlPlaneCache` supplies
  that stop predicate, but `SingleUseSource` currently violates it as noted above.
- `FragmentDecisionTree` defaults to direct execution until it has enough body-cost history.
  Physical
  guards run before the generated participation model can select `IDLE`.
- `ParticipationLogisticModel` decides only whether an eligible worker should idle. It does not
  choose `DIRECT` versus `STAGED`.
- Direct-versus-staged choice uses productive handles, contention, and calibrated body-cost
  thresholds. `MicroCalibrator` converts synthetic-work weights into worker-local nanosecond
  thresholds; the configured weights are not literal durations.
- Batch sizing changes at completed batches using measured service time, powers of two, adaptive
  caps, and bounded growth/shrink. Preserve these boundaries when changing scheduling.
- The no-upstream/no-local-work fast park uses `FragmentControlConfig.DEFAULT_PARK_NS`; adaptive
  `IdlePolicy` timing applies after an `IDLE` policy decision.

Keep the hot loop allocation-free and nonblocking. Avoid streams, formatting, routine logging,
blocking I/O, and new shared-state reads in per-cycle, per-frame, cache-drain, routing, and
finalization paths.

## Frames, execution, and recycling

`AbstractExecutor` owns a received frame from liveness check through one finalization path:

- live success or `AbstractFrame.CancelSignal` -> `doFinally()`;
- another caught `Exception` -> `doFinallyWithError(Throwable)`;
- exceptions from finalization are logged and swallowed.

The boundary catches `Exception`, not arbitrary JVM `Error`. `CancelSignal` is a shared stackless
internal control-flow sentinel; do not allocate replacements or turn it into an application error.
A kill switch changes liveness but does not interrupt a currently executing body.

- Default frame finalizers recycle through `FrameManager`. Recycling is best-effort and exactly-once
  ownership remains the caller/subclass contract.
- `FrameManager` is MPSC only on the return path. Its checkout buffer, factory, `get`,
  `getOrCreate`,
  and `dump` have one consumer/owner.
- `FrameFactory.replace()` restores the base hash, runs the replacement callback, reapplies parallel
  routing when required, and restores its captured origin. Replacement callbacks must overwrite all
  run-specific payload and metadata before the frame is republished.
- Recycling does not automatically sanitize arbitrary subclass state or reset a shared kill switch.
  Prior consumers must not retain a frame after ownership is transferred or it is recycled.
- `CallbackFrame` intentionally does not recycle on successful finalization; its response owner
  controls eventual reuse. Its error path still recycles.
- `PipelineFrame` schedules every stage as a separate frame. The root owns recycling and liveness;
  completion, filtering, cancellation, and execution errors recycle the root chain.
- `fanIn` creates ordered routing for that stage; `fanOut` randomizes routing. Builder definitions
  are
  immutable and reusable.
- Array/collection wrappers execute children inline; they are not nested executor/finalizer
  boundaries.

Treat every successful work-delivery queue offer as ownership transfer. On delivery-offer failure,
the producer still owns the frame and must retry, reject, or recycle it without creating a silent
leak/drop. Bounded recycler offers are different: `FrameManager.recycle()` may reject an object from
the pool, and `AbstractFrame.recycle()` intentionally permits that frame to become garbage
collectable. Do not spin or retry indefinitely to force an object back into the pool.

## Ingest and completion

`AbstractIngestSink` permits one downstream and saturates accumulated demand at `Long.MAX_VALUE`.
Immediate `complete()` disconnects and completes once. Queue completion does not clear queued
frames.

- `QueueIngestSink.completeGracefully()` sets a finish flag. Completion occurs only when a later
  pull/request observes the queue empty, which can require another drain after the final non-empty
  batch.
- Graceful input completion means only that the ingest queue drained. It is not an end-to-end
  barrier
  for pipeline stages or terminal callbacks, because stages can enqueue later work back into the
  same sink.
- Inherited `completeGracefully()` is unsafe for an active multi-stage `PipelineRunner` unless all
  producers and in-flight stage chains are quiesced or externally acknowledged. A transiently empty
  queue can complete the sink; a later stage then attempts to enqueue its successor into a completed
  sink and receives `IllegalAccessError`. Changes here require a regression for this race.
- `PipelineRunner.complete()` trips the shared kill switch and disconnects immediately; inherited
  graceful completion does not trip it.
- Current `PipelineRunner.run(...)` methods do not expose or handle a failed bounded offer. Any
  change
  around saturation requires direct regression coverage for ownership, recycling, and delivery.
- `ArrayIngestSink` retains the caller's array by reference. Do not mutate that array or in-flight
  frames after publication.

Use application-level acknowledgments when callers need terminal completion. Do not close a runner
or lattice based only on an empty ingest queue.

## Generated policy and configuration provenance

The participation artifacts carry generated provenance and must not be edited or regenerated in
isolation:

- Tracked Java model:
  `src/main/java/io/euhedral_execution/core/control_plane/ParticipationLogisticModel.java`
- Tracked parity fixture:
  `src/test/resources/io/euhedral_execution/core/control_plane/participation_logistic_parity.tsv`
- Intended exporter:
  `../python/pareto-weight-calibration/src/pareto_weight_calibration/logistic_runtime.py`

The intended exporter is currently stale: it emits `shouldCache(...)` while the tracked Java and
runtime caller use `shouldIdle(...)`, and its fixture/test still use removed `CACHE` terminology.
Running it can overwrite the model with compile-incompatible source, and byte-identical Python
parity cannot currently pass. Do not run the exporter or a finalize path until an explicitly scoped
cross-module change reconciles the exporter, terminology, tracked Java, fixture, Python parity test,
and required training artifact.

Idle timing is a separate frozen/manual production chain; `logistic_runtime.py` does not generate
`IdlePolicy.java`:

- Frozen timing evidence:
  `../python/pareto-weight-calibration/policies/cache-scarce-v1.json`
- Runtime timing projection:
  `../python/pareto-weight-calibration/policies/cache-scarce-v1-runtime.json`
- Production timing constants:
  `src/main/java/io/euhedral_execution/core/config/IdlePolicy.java`

The core test task passes both policy JSON paths as system properties. `ProductionIdlePolicyTest`
compares runtime fields exactly, and `ParticipationLogisticModelTest` checks the tracked Java model
against the tracked parity fixture. Model/policy changes therefore cross into the Python package:
read its module guide, preserve artifact provenance, and do not run training, finalization,
regeneration, or benchmark campaigns without explicit authorization.

System properties used during static initialization can make tests order-sensitive. Isolate and
restore policy properties such as `euhedral.fixed.idle.policy`, `euhedral.expensive.body.weight`,
and
`euhedral.direct.exec.bw` when relevant.

## Metrics and diagnostics

Metrics belong outside correctness decisions. Preserve low-overhead recording in hot paths and keep
meter registration/removal paired with lifecycle ownership. Current metrics coverage is mostly
indirect through fragment/cache tests; changes to meter lookup, aggregation, close behavior, missing
registries, or missing clone configuration need direct tests under `metrics`.

`FragmentObserver` callbacks are benchmark diagnostics and can be called concurrently by fragments.
Do not make benchmark observation required for production behavior or add removed calibration
surfaces without an explicit cross-module design change.

## Validation

Use Mise from the repository root. The normal focused gate is:

```bash
mise exec -- gradle :euhedral-core:test :euhedral-core:spotlessCheck
```

Useful focused selectors include:

```bash
mise exec -- gradle :euhedral-core:test \
  --tests 'io.euhedral_execution.core.control_plane.*' \
  --tests 'io.euhedral_execution.core.flow_control.*'

mise exec -- gradle :euhedral-core:test \
  --tests 'io.euhedral_execution.core.frames.*' \
  --tests 'io.euhedral_execution.core.impl.*' \
  --tests 'io.euhedral_execution.core.ingest.*'

mise exec -- gradle :euhedral-core:test \
  --tests 'io.euhedral_execution.core.config.*'
```

Core tests compile lower dependencies and can traverse the hardware native build, so missing Zig,
SDK, signing, or inspection prerequisites must be reported separately from a core source failure.

For public API or JPMS changes, compile relevant consumers:

```bash
mise exec -- gradle \
  :euhedral-core:compileJava \
  :euhedral-reactor-core:compileJava \
  :euhedral-spring-core:compileJava \
  :benchmarks:compileJava \
  :benchmarks:cfd:compileJava
```

Run consumer tests when behavior, not just compilation, can change. Routing, scheduling, frame,
completion, or runtime-lifecycle changes generally justify the repository-wide command from the root
guide:

```bash
mise exec -- gradle build integrationTest
```

### Current task caveats

- `runtimeParityTest` selects `FragmentDecisionTreeRuntimeParityTest`, but that test class is
  absent.
  Do not run or cite the task as parity evidence until the source exists.
- Core currently has no integration-tagged tests. A successful `:euhedral-core:integrationTest` can
  execute no test bodies.
- `test` explicitly excludes the same absent runtime-parity class.
- Several public paths lack dedicated tests, including `PipelineRunner`, `QueueIngestSink`,
  `FrameFactory`, and parts of metrics. Add focused tests when modifying them.
- Existing cache tests do not establish every multi-partition drain/limit behavior. Add
  multi-partition coverage when changing cache drain or work stealing.
- Comments and architecture prose that mention `FragmentActionPicker`, a `CACHE` execution path, or
  a `CacheTimingConfig` describe removed behavior. Verify names against current source.
- `AbstractExecutor` documentation can imply a completion channel, but the current default executor
  terminal only executes and finalizes frames. Verify implementation before relying on comments.

For documentation-only edits to this file, validate links and `git diff --check`; do not run native
or behavioral suites solely for the guide. For code changes, finish with the root handoff checklist
and report exact tests, skipped bodies, environmental blockers, and unverified cross-platform scope.
