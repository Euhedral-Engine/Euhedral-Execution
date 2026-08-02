# Phase 3 Affinity Capability and Executor Lifecycle Conformance Audit

## Scope and disposition

Audited `hardware-utils-overhaul/phase-3-affinity-executor-audit` from the updated P3 root at
`d6389711` (`Merge branch 'hardware-utils-overhaul/phase-3-executor-lifecycle-audit' into
hardware-utils-overhaul/phase-3-affinity-executor`). The parent artifacts are the P3 parent
blueprint and the exact P3-A/P3-B blueprint, completion, and conformance/manual-review records
listed in the phase artifact index. There is no P3 validation record.

The audit independently followed the combined request -> capability/lease/managed owner -> fresh
task -> release -> lifecycle/registry-cleanup flow. Inspection was limited to the P3 hardware
sources/tests, the named non-training compatibility callers, the child handoffs and diffs, and the
summarized P0-P2 closeouts. Training was neither inspected nor run.

**Disposition: review-ready; P3 is not yet closed.** No production or blueprint correction is
required. The child audit must be reviewed and explicitly authorized for merge/closeout before the
temporary P3 status block can be removed, a P3 closeout summary can be appended, or P4 can start.

## Parent acceptance matrix

| Parent criterion                                            | Classification | Evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
|-------------------------------------------------------------|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1. Additive P0 API/module/mask/fresh-thread compatibility   | satisfied      | `AffinityCapability` and `ThreadTools.getAffinityCapability()` are the reviewed P3-A additions; the root package was already exported and `module-info.java` is unchanged. The P3-A Java 21 gate records no removals or changed declarations, while the P0 fresh-thread anchor remains green.                                                                                                                                                             |
| 2. Stable truthful capability                               | satisfied      | `AffinityController` selects its final capability at construction. Linux and Windows common mutation stay `UNSUPPORTED`; macOS is only `LOCALITY_HINT`; `EXACT` requires capture, complete apply, and restore. An independently truthful Linux current-CPU query does not overstate mutation capability.                                                                                                                                                  |
| 3. Complete, precise request handling                       | satisfied      | All public overloads route through copy-first canonicalization. It preserves bit 63, rejects empty, overspan, sparse-hole, unsupported, and unrepresentable requests before a provider call, and the facade seams prove one complete call or zero calls rather than partial success.                                                                                                                                                                      |
| 4. Honest macOS locality semantics                          | satisfied      | The controller resolves the complete request before one locality application; the macOS facade accepts one ordinal only, rejects multiple ordinals before mutation, never reports `EXACT`, and clears a successful hint through tag zero.                                                                                                                                                                                                                 |
| 5. Non-destructive discovery and exact restoration          | satisfied      | Initialization makes at most one non-mutating exact capture. Per-thread exact work preserves the first captured original in a lease, restores that snapshot once, and never treats the supported-topology fallback as a restoration value. A01 directly covers sparse bit-63 snapshots.                                                                                                                                                                   |
| 6. Managed ownership/current CPU                            | satisfied      | Managed CPU tokens are supported-ID-only, owner-thread, LIFO, nested, and idempotent at the current top. A truthful provider CPU wins; otherwise the active managed owner is returned and unmanaged unavailable state is `-1`/`null`.                                                                                                                                                                                                                     |
| 7. Singleton identity and exact removal                     | satisfied      | The registry monitor returns the one live entry per CPU, restarts a live SHUTDOWN identity, retains CLOSED-active tombstones, and removes only the mapped `EntryIdentity` after the exact control is empty. E1, E5, E10, and E12 prove these boundaries.                                                                                                                                                                                                  |
| 8. Fresh concurrent tasks                                   | satisfied      | `execute` snapshots configuration/epoch, creates and configures a NEW candidate outside the lifecycle monitor, then registers and starts it under that monitor. E6 and the P0 compatibility anchor prove two concurrent commands get distinct threads with no queue or reuse.                                                                                                                                                                             |
| 9. Lifecycle/state-table linearization                      | satisfied      | One lifecycle monitor owns RUNNING/SHUTDOWN/CLOSED, configuration, epoch, task membership, predicates, and wait/notify. E2-E9 exercise execute/shutdown order, restart, close, `shutdownNow`, task exit, and `closeAll` under the frozen table.                                                                                                                                                                                                           |
| 10. Failure, rejection, interruption, and cleanup coherence | satisfied      | Creator/configuration/start rejection rolls back or retains no task; wrappers bind before affinity/user code and use nested `finally` cleanup for release, owner close, and task removal. E7-E9 cover command, recoverable/fatal cleanup, interruption, deadlines, and interrupt-ignoring work.                                                                                                                                                           |
| 11. Truthful termination and deadline behavior              | satisfied      | `isShutdown`/`isTerminated` read the coherent lifecycle predicate. `awaitTermination` uses saturated budget conversion, elapsed subtraction, predicate rechecks after wakeups/restart, and restores caller interruption. E8-E9 cover zero/negative, saturation, spurious wakeup, restart, completion, and interruption.                                                                                                                                   |
| 12. Cleaner and one-hook ownership                          | satisfied      | A static `CleanupAction` holds only entry/control/hook identities and claims once with CAS; structural E10 inspection excludes executor, factory, command, task-thread, and synthetic outer references. The registry installs one hook for nonempty membership and E11 proves bounded add/remove/restart/failure behavior.                                                                                                                                |
| 13. Deterministic cleanup bounds                            | satisfied      | Leases and owner thread-locals are removed in their `finally` paths. E1-E12 and the 50-round alternating stress assert zero active tasks, tombstones, registry entries, fake cleanables/hooks, owners, leases, and retained command paths at each terminal boundary.                                                                                                                                                                                      |
| 14. Java Memory Model argument                              | satisfied      | Registry synchronization publishes entries, weak references, controls, actions, and hook identity; lifecycle synchronization publishes state/configuration/epoch/task membership and pairs transitions/removals with waiter notification. Registry -> lifecycle is the only nested lock order; register-before-`Thread.start()` publishes the wrapper, and cleanup CAS owns exactly one action. No independent lifecycle flag weakens those edges.        |
| 15. Scope isolation                                         | satisfied      | The aggregate P3 diff changes only P3 hardware implementation/tests and prescribed records, plus two P2-immutability core test fixture corrections from the P3-A review. `euhedral-training`, `euhedral-core/src/main`, and `benchmarks/src/main` diffs are empty; platform-native bodies, resources/pressure, topology production, task serialization, CI, and module directives remain outside P3.                                                      |
| 16. Required verification and hygiene                       | satisfied      | Recorded Java 21 child evidence passes focused affinity/lifecycle, P0, cache-disabled hardware verify, and the 99-test read-only core gate. This audit independently passed the 30-test deterministic combined suite and five repeated lifecycle runs, then confirmed `git diff --check` and prohibited-path scope diffs. The currently unavailable pinned Java 21/Maven 3.9.16/Zig environment is an exact recorded limit, not substituted verification. |

## Ledger anchors

| Item | Classification | Evidence                                                                                                                                                                                                                                                                                                                                                                |
|------|----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A01  | satisfied      | `ThreadToolsAffinityTest#discoversAndRestoresTheOriginalMask` proves one non-mutating initialization capture, sparse bit-63 ownership, repeated exact apply retaining the first per-thread original, exact restoration, and lease removal. The controller/facade matrix additionally covers complete exact, locality, and unsupported requests with zero partial calls. |
| A02  | satisfied      | `PinnedThreadExecutorLifecycleTest#linearizesExecuteShutdownAndCleanup`, E1-E12, and the 50-round stress force singleton acquisition, execute/shutdown registration order, fresh concurrent threads, tombstone/replacement exclusion, cleaner/hook identity cleanup, `closeAll` gating, truthful termination, and final zero-count cleanup.                             |

## Combined-flow and compatibility review

`PinnedThreadExecutor.runCommand` creates the managed binding before attempting affinity or running
the user command. Its nested cleanup attempts release even after a false affinity result, then
closes the managed binding, and its outer `finally` removes the exact task identity and signals
termination. A release or binding-close recoverable failure therefore cannot skip registry/task
cleanup; fatal errors still reach the normal outer removal path.

The named read-only core consumers (`LatticeVertex`, `UpstreamQueue`, and `FrameFactory`) retain
their existing `ThreadTools.getCpu()`/`getCpuInfo()` contracts and the control-plane lifecycle keeps
using the public executor API. P3 does not alter their production source. The P3-A Java 21 core
gate records all 99 core tests green after the Linux truthful-current-CPU correction and P2 fixture
repair.

## Commands, results, and limits

- Available fallback toolchain: OpenJDK 17.0.19 and Maven 3.6.3; `mise`, its Java 21/Maven 3.9.16
  selection, and `zig` are unavailable.
-
`mvn -B -pl euhedral-hardware-utils resources:resources compiler:compile resources:testResources compiler:testCompile -Dtest='ThreadToolsAffinityTest,LinuxAffinityTest,WindowsAffinityTest,OSXAffinityTest,PinnedThreadExecutorLifecycleTest,PinnedThreadExecutorTest,PinnedThreadExecutorCompatibilityTest' surefire:test`:
passed, 30 tests (11 affinity controller, 14 lifecycle, and five facade/compatibility tests).
- Five further `PinnedThreadExecutorLifecycleTest` runs passed, 14 tests each, including the
  50-round bounded stress schedule.
- The fallback P0 direct command ran native, mask-format, and fresh-thread checks successfully.
  `ApiCompatibilityTest` fails only because JDK 17 reports module version metadata and the
  historic baseline treats reviewed P2/P3 additions as additions; this matches the recorded
  fallback limitation. The P3-A Java 21 evidence is the passing P0 API/module result.
- `mvn -B -Dmaven.build.cache.enabled=false -pl euhedral-hardware-utils -am verify` stops before
  test execution at `exec-maven-plugin:zig-build` because the `ZIG` executable is missing.
  `mvn -B -Dmaven.build.cache.enabled=false -pl euhedral-core -am test` consequently stops at the
  same hardware phase after eight upstream data-structure tests; it cannot reach core. No source
  or build configuration was changed to bypass either limit.
- `git diff --check 7d3abea7..HEAD` and scope checks for training, core production, and benchmark
  production are clean. No training command or inspection occurred.

No deviations, ambiguities, or unverified P3 parent criteria remain. The native/platform parity
work explicitly deferred to P5-P7 and the unrelated P2 closeout limits are not reclassified here.
