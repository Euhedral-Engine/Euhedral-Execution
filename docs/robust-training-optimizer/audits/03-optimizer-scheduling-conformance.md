# Phase 3 Blueprint Conformance Audit

Audited branch: `agent/phase3a-optimizer-scheduling-blueprint`, including the remediation and
subsequent naming/package cleanup.

Blueprint:
`docs/robust-training-optimizer/blueprints/03-optimizer-scheduling.md`.

## Result

**Conforms.** The deviations reported by the first Prompt 3D pass are resolved. The Phase 3 path now
implements the scenario-aware optimizer, exact budget and scheduling rules, strict schedule and
checkpoint artifacts, deterministic native-v1 benchmark contract, and typed closed-loop
bootstrap/resume state machine. Blueprint type and file references were updated only to match the
final domain-named package structure.

## Remediation verified

- `CmaEsOptimizer` now uses eligible non-anchor Phase 1 summaries, diversity-selected islands,
  measured full covariance, complete curve predictions, the authoritative predicted robust
  comparator, exact stagnation detection, settled restart mechanics, deterministic island seeds, and
  deterministic island/generation/member production order.
- `ScoreBandSampler` now exposes only the `PredictedCandidate` API. It uses ten fixed worst-quality
  bands, exact Hamilton capacities, unsigned hash-priority bottom-k retention, and a bounded
  exact-comparator overflow heap. Pooled scalar compatibility is isolated under
  `training.legacy`.
- `SequenceFinder` streams bounded prediction batches, maintains separate bounded audit and
  score-band selectors, reranks CMA proposals before admission, applies base and overflow tranches
  independently, transfers shortfalls to direct Sobol, and advances the cursor for every direct
  point consumed.
- `scheduling.io.OptimizationCorpusReader` strictly joins both Phase 1 vector files, robust ranking,
  coverage report, and full scenario-result grid. It reconstructs policy bits and identities,
  validates deterministic ordering, recomputes coverage, and rejects incomplete joins.
- Budget allocation, carry admission/reconciliation, per-scenario attempt backoff, carry priority,
  exact scenario rotation, stable IDs/seeds, anchor midpoint placement, and unsigned non-anchor
  trial ordering implement the settled rules.
- `scheduling.io.ScheduleCodec` owns the exact five CSV files plus empty `COMPLETE`, uses
  `data.io.CanonicalCsv` for canonical UTF-8/LF RFC 4180 parsing, rejects unexpected inventory and
  symlinks, recomputes run/cohort/frame identity, verifies anchor/trial order, validates prediction
  summaries and budget/admission semantics, and requires atomic directory publication.
- `CheckpointSnapshotCodec` owns all seven checkpoint files plus empty `COMPLETE`, validates sidecar
  hashes, contiguous revisions, the transition graph, scenario/carry/pending invariants, complete
  evidence bundle identity, and workspace-relative artifact fingerprints. It forces files before
  strict read-back and has no non-atomic publication fallback.
- `BenchmarkRunner.runV1` validates exact topology, CPU mask, cohort/run/frame identity, preserves
  stable IDs across retry attempts, measures monotonic counter deltas, pauses before reset and
  evidence writes, emits explicit success/timeout/failure/skipped rows, retains incomplete attempts,
  strictly reads its output, and atomically publishes the final bundle.
- `ClosedLoopRunner` now implements multi-environment bootstrap, calibration and merge zero,
  accepted/rejected model transitions, deterministic scheduling, pending evidence adoption,
  unexpected evidence rejection, post-benchmark merge, carry reconciliation, rotation advancement,
  final post-merge completion, frozen configuration hashing, and strict resume validation. A
  complete schedule published in the model-ready crash window is accepted only after deterministic
  reconstruction matches its persisted contents; its reconstructed next Sobol cursor is checkpointed
  without changing the exact schedule schema.
- Configurations, immutable state, enums, and I/O codecs are separated into `config`, `data`,
  `enums`, and `io` subpackages beneath benchmark, checkpoint, optimization, and scheduling.
  Operational algorithms remain in their owning packages.

## Memory, concurrency, and mathematical semantics

- The optimizer and selectors are sequential by contract; no parallel floating-point reduction or
  order-dependent reservoir state was introduced.
- CMA arithmetic uses copied vectors and reusable bounded generation state. `StrictMath` is used for
  decomposition, norms, exponentials, powers, and trigonometry. Predicted means use compensated
  summation, and exact comparators contain no scalar-score or stagnation epsilon.
- Candidate screening retains bounded prediction batches and bounded selectors rather than the full
  Sobol screen. Persistent corpus and carry objects remain immutable defensive copies.
- The benchmark sink consumed counter retains release publication and acquire observation. The pause
  barrier orders source quiescence before counter reset, picker replacement, and evidence writes;
  reset does not race flowing work or carry measurements across policies.
- Trial keys and score-band keys compare as unsigned 64-bit values. Budget and midpoint arithmetic
  uses checked integer operations. Throughput remains frames per second using monotonic elapsed
  nanoseconds.

## Validation evidence

The blueprint-prescribed commands passed on 2026-07-28:

```text
mvn -B -pl euhedral-training -am install -Dmaven.test.skip=true
  BUILD SUCCESS (6 reactor modules)

mvn -B -pl euhedral-core -Dtest=BenchmarkFrameTest test
  3 tests, 0 failures, 0 errors, 0 skipped

mvn -B -pl euhedral-training test
  105 tests, 0 failures, 0 errors, 1 skipped
```

The skipped test is the pre-existing opt-in
`ScenarioOrdinalNetworkIntegrationTest`; it is not part of the deterministic Phase 3 acceptance
surface.

The required Phase 3 named tests are present and passed:

- `PredictedPolicyComparatorTest`
- `BudgetAllocatorTest`
- `CmaEsOptimizerTest`
- `ScoreBandSamplerTest`
- `CandidateSchedulerTest`
- `SequenceFinderTest`
- `CarryForwardQueueTest`
- `ScenarioRotationTest`
- `ScheduleCodecTest`
- `CheckpointSnapshotCodecTest`
- `BenchmarkRunnerV1Test`
- `ClosedLoopRunnerTest`
- core `BenchmarkFrameTest`

Additional verification passed:

- real `DataMerger` output is accepted by the strict Phase 3 optimization-corpus join;
- both new-path stale-boundary searches returned no matches;
- `git diff --check` returned no errors;
- the blueprint and this audit use the final domain names and package paths.

## Environmental limitation

No live native-lattice throughput smoke run was performed. Such a run depends on host affinity and
the active topology. The deterministic fake backend exercises pause/reset/evidence ordering and
status publication, while the focused core test covers deterministic frame identity. This
environmental limitation does not affect the required deterministic verification results.

## Workspace boundary

The pre-existing staged training inputs, untracked current-workspace training data/output, and the
unrelated untracked core utility test were not edited or removed. Only Phase 3 implementation,
focused tests, and this audit report are included in the remediation commit.

## Naming and package cleanup verification

The 2026-07-29 cleanup is rename-only:

- `CanonicalCsv` replaces the phase-numbered CSV helper in the shared `training.data.io` package.
- `SchedulingFixtures` replaces the phase-numbered test fixture name.
- Configurations, immutable data, enums, and I/O types now occupy their documented subpackages.
- Seed/fingerprint material uses domain names without changing the settled fields or ordering.
- No lifecycle, memory-ordering, queue, scheduling, comparator, floating-point, or benchmark
  behavior was changed.

`mvn -B -pl euhedral-training -DskipTests compile` and
`mvn -B -pl euhedral-training test` both passed with 105 tests, no failures or errors, and the one
pre-existing opt-in integration test skipped.
