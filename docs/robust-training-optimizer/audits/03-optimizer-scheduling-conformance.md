# Phase 3 Blueprint Conformance Audit

Audited branch: `agent/phase3a-optimizer-scheduling-blueprint`, at implementation commit
`6056ef0` and the Prompt 3C rerun record in
`docs/robust-training-optimizer/blueprints/03-optimizer-scheduling.md`.

## Result

**Deviated.** Prompt 3B is not complete and Prompt 3C's remaining-verification note cannot be
resolved by calling the existing 87-test suite sufficient. The new-path classes compile, but the
required Phase 3 scheduler, strict artifact codecs, native benchmark execution, and closed-loop
state machine are largely scaffolds. Per the Prompt 3D contract, this audit reports the violations
and stops; it does not propose a different architecture.

## Evidence reviewed

- The approved Phase 3 blueprint and both Prompt 3B/3C completion records.
- The implementation under `euhedral-training/src/main/java/io/euhedral_execution/training/` and
  its `optimization`, `scheduling`, `checkpoint`, and `benchmark` packages.
- `mvn -B -pl euhedral-training test`: passed, 87 tests, 0 failures/errors, 1 skipped opt-in DJL
  integration test.
- `mvn -B -pl euhedral-training -Dtest=ScoreBandSamplerTest,CmaEsOptimizerTest test`: passed,
  3 tests. Those tests retain pooled-v0 adapter coverage and do not cover the Phase 3 contracts.
- `git diff --check`: passed. Pre-existing user-owned staged/untracked training inputs and outputs
  were not changed.

## Conformance classification

### Satisfied

- Deterministic `BenchmarkFrame` routing-seed overloads and their focused core test are present.
- Predicted-policy primitives, fixed-band sampling, Hamilton allocation support, basic
  carry/rotation records, legacy pooled-v0 boundary classes, and a VarHandle-based consumed-counter
  accessor are present.
- New-path stale-reference searches found no prohibited pooled names or legacy workspace paths in
  the searched new-path roots.

### Deviated

- `ClosedLoopRunner.run(ClosedLoopConfig)` only acquires a lock and writes an otherwise empty
  checkpoint. It uses a constant all-zero configuration hash, does not bootstrap, merge, train,
  schedule, execute a benchmark, reconcile carry state, advance rotation, post-merge, resume, or
  adopt crash-window evidence. This violates the blueprint's closed-loop, checkpoint/restart, and
  final-post-merge requirements.
- `CheckpointSnapshotCodec` serializes only a six-field `state.csv`; every other required checkpoint
  file is header-only. It neither round-trips nor validates required scenarios, cursors, evidence,
  carry rows, predictions, pending runs, artifacts, fingerprints, revisions, or stage transitions.
  It also silently filters checkpoints by the expected hash rather than diagnosing an incompatible
  frozen configuration.
- `ScheduleCodec.read` validates only `COMPLETE` and one header, then returns an empty schedule.
  `write` emits `unknown` for `training_run_id`, does not encode/verify the settled run identities
  or frame seeds, does not implement strict CSV escaping/validation, and does not reject the
  blueprint's corrupt, changed, traversal, or symlink cases. It therefore cannot reproduce a
  pending schedule.
- `BenchmarkRunner.runV1` is a synthetic writer, not the required native benchmark path. It creates
  only `SUCCESS` observations from configured counts and duration; it does not run sources, use
  monotonic counter deltas, pause before reset/evidence writes, emit timeout/failed/skipped states,
  preserve incomplete attempts, enforce CPU-mask/seed invariants, or publish retry attempts under
  the required stable-run semantics.
- `CandidateScheduler` does not implement the settled availability transfers, common audit set,
  overflow-prefix allocation, deterministic trial order, anchor midpoint positions, or per-role
  transfer accounting. In particular, audit predictions are labelled `SCORE_BAND`, carry predictions
  are labelled `MEASURED_LEADER`, and all transfer columns are emitted as zero.
- `CarryForwardQueue.reconcile` only removes entries once eligible; it does not admit new/audit
  policies, reconstruct coverage, record attempts from completed schedules, apply per-scenario
  backoff, or preserve the required queue serialization semantics. `rescore` does not update the
  required iteration metadata.
- `SequenceFinder` retains a screen list up to `maximumPredictionRows`, does not implement the
  settled bounded streaming selectors/tranches and exclusion inputs, and does not perform the
  prescribed audit/shortfall/overflow accounting.
- `OptimizationCorpusReader` does not strictly join and validate the Phase 1 datasets/vector files;
  it constructs an empty eligible-policy list instead.
- The Phase 3 focused fixtures and acceptance tests named by the blueprint are absent:
  `PredictedPolicyComparatorTest`, `BudgetAllocatorTest`, `CandidateSchedulerTest`,
  `SequenceFinderTest`, `CarryForwardQueueTest`, `ScenarioRotationTest`, `ScheduleCodecTest`,
  `CheckpointSnapshotCodecTest`, `BenchmarkRunnerV1Test`, and `ClosedLoopRunnerTest`.
  The existing `CmaEsOptimizerTest` and first `ScoreBandSamplerTest` still exercise retained
  pooled-v0 adapters rather than the approved new-path APIs.

### Unverified

- No deterministic fake-native benchmark test exists, so pause/reset ownership and evidence-write
  ordering are unverified. The full suite cannot establish those runtime invariants.
- No restart/interruption matrix exists, so byte-identical schedules/final Phase 1 output, expected
  evidence adoption, unexpected-evidence rejection, incomplete-attempt retention, and rotating
  carry completion are unverified.
- No native lattice smoke test was run. This is an environmental limitation separate from the
  deterministic tests that are themselves missing.

### Ambiguous

- None. The approved blueprint settles the missing behavior; the issue is implementation absence,
  not an unresolved design choice.

## Audit boundary

This report evaluates conformance only. The deviations return the work to Prompt 3B implementation
and then Prompt 3C verification; a passing Prompt 3D audit cannot be issued for this revision.
