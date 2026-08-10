# Phase 2 Topology and Snapshot Foundation Conformance

## Scope and evidence

Audit branch: `hardware-utils-overhaul/phase-2-topology-snapshot-audit` from P2 root commit
`274a6f0b`. Reviewed the P2 parent blueprint, P0/P1 indexed closeouts, P2-A and P2-B
blueprint/completion/conformance triples, owned diffs, topology/snapshot tests, module descriptor,
and read-only core compatibility boundary. Training was neither inspected nor run.

The P2-B child conformance file was present as an untracked worktree artifact although the stated
P2-root merge commit does not track it; its content was used as evidence and this discrepancy must
be resolved when the child audit is committed/merged.

## Parent acceptance classifications

| Criterion                                    | Classification | Evidence                                                                                                                                                                          |
|----------------------------------------------|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1. API/module/mask preservation              | unverified     | P0 report has zero removals and the record access-flag correction removed P2 drift; Java 17 module-version metadata and six authorized additions prevent a green baseline report. |
| 2. Complete deterministic active projection  | satisfied      | Normalizer/model fixtures validate complete CPU/core/socket/cache projections.                                                                                                    |
| 3. Cross-platform identity and fallback      | satisfied      | Sparse Linux, Windows group/bit-63, and common fallback fixtures pass; detailed provider parity remains P5-P7.                                                                    |
| 4. Public count/index/ID contract            | satisfied      | P2-A model and sparse-index fixtures preserve logical CPU spans, dense global IDs, and null holes.                                                                                |
| 5. Cache fallback and L3 aggregation         | satisfied      | Cache fallback and ownership fixtures pass with canonical masks and null-safe L3 aggregation.                                                                                     |
| 6. Deep ownership                            | satisfied      | Model/provider, wrapper, mapper, and snapshot constructor/accessor mutation boundaries are covered.                                                                               |
| 7. Value equality/hash                       | satisfied      | Wrapper content equality and socket/core nested-array equality/hash are covered.                                                                                                  |
| 8. Complete indexed snapshots                | satisfied      | Sparse active-entry/named-field/timestamp fixture passes.                                                                                                                         |
| 9. Owned allowed masks/unknown bits          | satisfied      | Constructor intersection and deterministic mapper fixture cover caller mutation and unknown-bit removal.                                                                          |
| 10. Core-zero reservation                    | satisfied      | P0 and P2-B core-zero-only fixtures pass.                                                                                                                                         |
| 11. Coalescing and volatile publication      | unverified     | Source implements greatest-sequence pending replacement and one volatile graph boundary, but the required latch-controlled R2-R12 matrix is absent.                               |
| 12. Global/socket versions                   | satisfied      | First, identical, change, deactivation, reactivation, and pressure-independent cases pass.                                                                                        |
| 13. Whole-model fallback/resource separation | satisfied      | Fallback fixture and bootstrap/model boundary cover independent topology/resource outcomes.                                                                                       |
| 14. Scope containment                        | satisfied      | P2 changes remain hardware topology/snapshot/tests and authorized documentation; no training scope was examined.                                                                  |
| 15. Required gates and hygiene               | unverified     | 24 focused tests and diff checks pass; pinned API, Zig-backed hardware verify, and read-only core gate remain unavailable.                                                        |

## Common T01-T06 classifications

| Item                             | Classification | P2 disposition                                                                                                        |
|----------------------------------|----------------|-----------------------------------------------------------------------------------------------------------------------|
| T01 safe initialization          | satisfied      | Common whole-model fallback is complete; detailed macOS discovery is P7.                                              |
| T02 Linux identity/sparse safety | satisfied      | Normalization handles sparse IDs and duplicate local cores; detailed Linux collection is P5.                          |
| T03 Windows identity             | satisfied      | Group/bit-63 mapping is deterministic; bounded native parser work is P6.                                              |
| T04 remap/version/publication    | unverified     | Mask ownership, fixed graphs, and versions pass; deterministic concurrent coalescing/publication evidence is missing. |
| T05 immutable snapshots          | satisfied      | Defensive copies, equality/hash, named values, indexes, and timestamps pass; detailed pressure/value parity is P4-P7. |
| T06 core-zero-only membership    | satisfied      | Alternatives remove global core zero; sole-core and empty cases retain their settled meanings.                        |

## Verification and correction

- Passed: combined direct hardware fixture loop, 24 tests, zero failures.
- Re-ran `ApiCompatibilityTest` after a minor settled correction: restored baseline `final` flags on
  `SocketSnapshot.equals/hashCode` and `CoreSnapshot.hashCode`; `CoreSnapshot.equals` correctly
  remains non-final. The report now has zero removals and only Java-17 module-version metadata plus
  six authorized additions.
- `gradle :euhedral-hardware-utils:build` and the read-only
  `gradle :euhedral-core:test` stop at the hardware `zig-build` lifecycle because `ZIG` is unset.
  `mise` is unavailable; this host supplies OpenJDK 17.0.19 and Gradle 3.6.3 rather than the pinned
  Java 21/Gradle 3.9.16 toolchain.

## Handoff

This audit is ready for review. It does not close P2: merge/closeout authorization is still needed,
the child conformance artifact must be tracked, and the deterministic concurrency plus pinned
native/core gates remain outstanding.
