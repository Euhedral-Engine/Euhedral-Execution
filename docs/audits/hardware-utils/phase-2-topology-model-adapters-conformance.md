# Phase 2-A Topology Model and Adapters Conformance Audit

## Scope and result

- Audit branch: `hardware-utils-overhaul/phase-2-topology-model-audit`
- Audited implementation: P2 root commit `0e41bb8b`
- Parent artifact: `docs/blueprints/hardware-utils/phase-2-topology-model-adapters.md`
- Validation artifact: none; this is the required first conformance record.

The P2-A implementation conforms to the settled topology-model/adapters contract. No production
correction was required. This audit read the P2-A blueprint and completion record, summarized P2
parent contract, P0 compatibility contract/ledger, P2-A diff/tests, relevant hardware code, and
read-only core ID consumers. It did not inspect training or begin P2-B work.

## Requirement classification

| Checklist                                                | Classification | Evidence                                                                                                                                                                                                    |
|----------------------------------------------------------|----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1. P0 module/API preservation                            | unverified     | No removal or descriptor change appears in the diff; the comparator reports only four authorized macOS additions, but cannot pass its Java-21 baseline comparison on this Java-17 host.                     |
| 2. One internal provider/input/model/bootstrap lifecycle | satisfied      | The unexported package provides the sole selected collection-normalization-projection path; no registry or second model is present.                                                                         |
| 3. Complete mutually consistent active topology          | satisfied      | The normalizer creates CPU/core/socket/cache entries for every active ID and uses exact per-level completion.                                                                                               |
| 4. Linux sparse, socket/die-distinct identity            | satisfied      | Numeric `cpuN` scan retains holes and settled keys; the sparse multi-socket/die fixture passes.                                                                                                             |
| 5. Windows unsigned group and bit-63 bijection           | satisfied      | Unsigned group/bit enumeration and 16-digit lower-case signatures map `0,63,64,127`; fixture passes.                                                                                                        |
| 6. macOS ordinal and exact common fallback               | satisfied      | Unknown ordinal cores and absent caches normalize to a complete model; fallback fixture passes.                                                                                                             |
| 7. Count/index/max/null-hole contract                    | satisfied      | CPU count is highest active ID plus one; dense core/socket IDs and inactive map holes are retained.                                                                                                         |
| 8. Invalid-input/allocation bounds                       | satisfied      | Logical-ID, active-count, sibling-kind, and core-index-sum gates precede ID-indexed topology structures.                                                                                                    |
| 9. Cache canonicalization and fallback                   | satisfied      | Domains are cloned, constrained, deduplicated, selected deterministically or replaced with exact fallbacks.                                                                                                 |
| 10. Sparse/null-safe unique L3 aggregation               | satisfied      | `socketL3Cache` returns zero for missing sockets and deduplicates by canonical L3 mask.                                                                                                                     |
| 11. Provider and projection ownership                    | satisfied      | Input/model copy boundaries, final owner-carrying immutable maps, and copied active-ID arrays resist mutation.                                                                                              |
| 12. Independent topology/resource failures               | satisfied      | Topology fallback is complete; resource selection occurs after topology assignment and can return null independently.                                                                                       |
| 13. Safe one-time publication                            | satisfied      | Class initialization publishes the final immutable facade graph; no mutable update state was added.                                                                                                         |
| 14. Required gates                                       | unverified     | Eleven focused fixtures pass and P0 mask/core-zero tests pass; Java-21 P0/API and Zig-backed verify/core gates cannot run locally. Successful implementation evidence is recorded in the completion record. |
| 15. Scope containment                                    | satisfied      | Audit diff contains no mapper/snapshot/wrapper, resource/monitor, affinity, native, core production, training, or P2-B change.                                                                              |

There is no deviation or design decision to return to the blueprint.

## Common P2 ledger portions

| Ledger item                               | Classification | Boundary                                                                                                         |
|-------------------------------------------|----------------|------------------------------------------------------------------------------------------------------------------|
| T01 safe `SystemInfo` initialization      | satisfied      | Complete macOS/common fallback topology is covered; P7 retains real macOS discovery quality.                     |
| T02 Linux deterministic complete topology | satisfied      | P2-A covers sparse/global identity/cache completion; P5 retains online/cgroup/hotplug parity.                    |
| T03 Windows group/bit-63 identity         | satisfied      | P2-A adapter mapping passes; parser bounds/offset correction remains P6.                                         |
| T05 immutable published snapshots         | unverified     | P2-A owns provider/model projection ownership only; wrapper/snapshot equality and publication are P2-B/P4 scope. |
| T06 core-zero-only behavior               | unverified     | Existing P0 core-zero test passes, but remap/publication ownership is P2-B and was not changed.                  |

## Verification and limits

- Passed:
  `gradle -B -pl euhedral-hardware-utils surefire:test -Dtest=SystemInfoFallbackTest,TopologyCacheFallbackTest,TopologyOwnershipTest,TopologyNormalizerTest,LinuxSystemLayoutFixtureTest,WindowsTopologyFixtureTest` --
  11 tests.
- Passed: P0 mask-formatting and core-zero tests.
- Unverified: P0 API comparison reports zero removals and four expected macOS additions, but fails
  because Java 17.0.19 embeds module-requires versions that differ from the Java-21 baseline. This
  is not an implementation API change.
- Unverified: the normal hardware lifecycle cannot start because `zig` is unavailable; its bound
  `zig-build` fails before test compilation. The P2-A completion record documents a successful
  Java-21/Zig verify and read-only core gate.

`git diff --check` passes. No training or P2-B scope was inspected or changed.

## Handoff

P2-A is ready for developer review and merge of this audit. P2-B remains blocked until the audited
implementation and this conformance record are reviewed and merged into the P2 root.
