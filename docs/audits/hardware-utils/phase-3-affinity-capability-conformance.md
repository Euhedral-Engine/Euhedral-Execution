# Phase 3-A Affinity Capability Conformance Audit

## Scope and disposition

Audited `hardware-utils-overhaul/phase-3-affinity-capability-audit` from the updated P3 root at
`0faaee70` (`Implemented Phase 3A blueprint`), against the P3-A blueprint/completion record, the
applicable P3 parent contracts, the P0-P2 closeout summaries, and the owned affinity sources and
tests. This audit did not inspect training, executor internals, native implementation bodies, or
detailed platform work.

Developer review exposed a Linux runtime compatibility regression in the frozen exact-only
current-CPU rule. The corrected contract now treats truthful current-CPU querying independently of
affinity mutation/restoration capability: Linux remains `UNSUPPORTED` for common affinity mutation
but reports its validated native logical CPU; Windows and macOS remain unavailable until their
platform phases. The controller, provider adapter, deterministic tests, and stale read-only core
fixtures were corrected and all required gates pass. The audit is review-ready; P3-B must still
wait for its merge.

## Child acceptance matrix

| P3-A criterion                                       | Classification | Evidence                                                                                                                                                                                                                                                                                               |
|------------------------------------------------------|----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1. Additive API and unchanged compatibility surfaces | satisfied      | The Java 21 P0 API gate passed. It reports only `AffinityCapability`, its normal enum members, and `ThreadTools.getAffinityCapability()` as P3-A additions; the native and module checks passed.                                                                                                       |
| 2. Stable truthful capability                        | satisfied      | `AffinityController` selects one final capability during construction. Linux and Windows inherit `UNSUPPORTED`; `ThreadPinner` only gives macOS `LOCALITY_HINT`, never `EXACT`.                                                                                                                        |
| 3. Owned unsigned masks and bounds                   | satisfied      | `AffinityMasks.canonical` clones before inspection, trims only trailing zero words, uses trailing/leading-zero operations for bit 63, rejects oversized, empty, high, and unsupported/hole bits, and preserves the complete canonical request.                                                         |
| 4. Complete apply matrix and zero partial calls      | satisfied      | Controller review confirms exact full-mask application, whole-request locality resolution before one hint, unsupported no-calls, and pending-lease removal. The facade helpers reject Windows cross-group masks and macOS multi-ordinal masks before their raw calls.                                  |
| 5. Non-destructive base discovery                    | satisfied      | Exact discovery performs one capture only; successful capture becomes an owned base mask and failed discovery falls back only to the copied supported topology. No setter or release occurs in construction.                                                                                           |
| 6. Original restoration and locality release         | satisfied      | The first exact snapshot is retained per calling thread, later sets reuse it, and `finally` removes the lease after restore. Locality maps all bits first and releases through the tag-zero provider hook once after successful acquisition.                                                           |
| 7. Managed ownership                                 | satisfied      | `bindManagedCpu` validates supported IDs before changing state. Its creator-thread, LIFO, nested, idempotent token restores the predecessor or removes the outer value.                                                                                                                                |
| 8. Truthful current CPU                              | satisfied      | Current CPU is independent of mutation capability. Linux enables its truthful native query while remaining mutation-`UNSUPPORTED`; Windows/macOS adapters return unavailable. Results are span/mask validated before managed-owner fallback or `-1`/`null`.                                            |
| 9. Recoverable failure safety                        | satisfied      | Selection, capture, mapping, apply, restore/release, raw facade calls, and physical CPU queries normalize only `RuntimeException`/`LinkageError`. New pending leases are removed in `finally` even when fatal errors propagate; unsupported paths do not dereference a provider.                       |
| 10. Ownership and memory semantics                   | satisfied      | Provider, capability, supported mask, and base mask are final construction/class-initialization state. Requests and snapshots are cloned, and lease/owner state is ordinary thread-confined `ThreadLocal` state; no registry, atomics, VarHandles, or cross-thread mutable state was added.            |
| 11. Deterministic test sufficiency                   | satisfied      | The expanded focused Java 21 suite covers A01, all overload ownership, maximum bounds, bit 63/cross-word masks, exact/locality/unsupported matrix behavior, original restoration, release/owner cleanup, independent current CPU, recoverable/fatal provider behavior, and facade zero/one-call seams. |
| 12. Excluded scope remains excluded                  | satisfied      | No executor implementation, native body, topology/resource/core production, or training file changed. Two core test fixtures were updated only to supply the P2-required non-null immutable topology/snapshot inputs and clone before mutation.                                                        |
| 13. Required verification and hygiene                | satisfied      | Focused suite, P0 gates, cache-disabled hardware verify, full read-only core tests, `git diff --check`, and scope checks pass under `mise` Java 21.0.2/Maven 3.9.16.                                                                                                                                   |

## Applicable parent criteria and A01

| Requirement              | Classification | Evidence                                                                                                                                                                                                                                                    |
|--------------------------|----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Parent 1                 | satisfied      | P0 API, native, mask, and executor compatibility gates passed with the reviewed additive surface.                                                                                                                                                           |
| Parent 2                 | satisfied      | The final capability is immutable after construction and follows the conservative production table.                                                                                                                                                         |
| Parent 3                 | satisfied      | Canonical validation and facade/controller matrix review show no rejected or unrepresentable request reaches a partial setter.                                                                                                                              |
| Parent 4                 | satisfied      | macOS accepts only one representable ordinal, rejects multi-ordinal requests before mutation, and releases with raw tag zero.                                                                                                                               |
| Parent 5                 | satisfied      | Base discovery has one non-mutating exact capture; first exact acquisition retains the original snapshot and never restores the fallback base.                                                                                                              |
| Parent 6                 | satisfied      | Managed ownership conforms; the corrected parent prefers an independently truthful provider CPU and otherwise uses the managed owner or `-1`/`null`.                                                                                                        |
| Parent 13 (P3-A portion) | satisfied      | Affinity leases and managed owners are removed on their specified success/failure exits; no command/executor reference is introduced.                                                                                                                       |
| Parent 14 (P3-A portion) | satisfied      | Final-field/class-initialization publication and thread-confinement arguments match the implementation.                                                                                                                                                     |
| Parent 15                | satisfied      | Detailed platform work, executor lifecycle, topology/resource/core production, and training remain outside scope.                                                                                                                                           |
| Parent 16                | satisfied      | Both child/P0 gates, cache-disabled hardware verification, full core tests, and hygiene/scope checks pass.                                                                                                                                                  |
| A01                      | satisfied      | `ThreadToolsAffinityTest#discoversAndRestoresTheOriginalMask` passed with distinct sparse initialization/per-thread snapshots containing bit 63, one non-mutating initialization capture, repeated apply, and restoration of the first per-thread snapshot. |

## Commands, results, skips, and limits

All recorded commands used the project-default `mise` environment: OpenJDK 21.0.2 and Maven
3.9.16. `mise` emitted unrelated warnings for unavailable user-level tool entries but selected the
project Java/Maven defaults correctly.

-
`mise exec -- mvn -B -pl euhedral-hardware-utils ... -Dtest='ThreadToolsAffinityTest,LinuxAffinityTest,WindowsAffinityTest,OSXAffinityTest' surefire:test`:
passed, 14 tests.
-
`mise exec -- mvn -B -pl euhedral-hardware-utils ... -Dtest='ApiCompatibilityTest,NativeCompatibilityTest,MaskFormattingCompatibilityTest,PinnedThreadExecutorCompatibilityTest' surefire:test`:
passed, 4 tests.
- `mise exec -- mvn -B -Dmaven.build.cache.enabled=false -pl euhedral-hardware-utils -am verify`:
  passed. Native Zig build, 63 unit tests, and native packaging/load integration checks passed.
- `mise exec -- mvn -B -Dmaven.build.cache.enabled=false -pl euhedral-core -am test`: passed. The
  hardware module ran 63 tests and the core module ran 99 tests, all green; the Linux unmanaged
  current-CPU caller paths and corrected immutable fixtures are included.
- `git diff --check 7d3abea7..HEAD` and P3-A scope diffs for `euhedral-training`,
  `euhedral-core/src/main`, `module-info.java`, and `src/main/native`: passed/empty.

Corrections made: decoupled provider current-CPU availability from affinity mutation capability;
enabled Linux's existing truthful native query; centralized all `ThreadTools.setAffinity`
overloads in the owned controller; removed pending leases in `finally` for fatal apply propagation;
expanded deterministic provider/facade coverage; and repaired two stale core test fixtures for P2
immutability/non-null construction. The audit did not add host-affinity timing assertions.
