# Phase 2-B Snapshot, Remap, and Publication Conformance

## Scope and evidence

Audited merged P2-B commit `1c7d7859` on
`hardware-utils-overhaul/phase-2-snapshot-publication-audit`. Evidence was limited to the P2-B
blueprint and completion record, summarized P2/P2-A handoff, P2-B diff, owned production classes,
and focused hardware tests. Training was not inspected.

## Classifications

| Criterion                                        | Classification | Evidence                                                                                                                 |
|--------------------------------------------------|----------------|--------------------------------------------------------------------------------------------------------------------------|
| 1. P0 API/module/mask compatibility              | unverified     | Mask/core-zero tests pass; fallback API report has known baseline deltas.                                                |
| 2. Wrapper ownership, ranges, equality/hash      | satisfied      | Constructors clone, ranges validate, and focused wrapper tests pass.                                                     |
| 3. Canonical record ownership/accessors          | satisfied      | Mutable wrappers/arrays are copied; nested-array accessors clone.                                                        |
| 4. Snapshot-tree equality/hash                   | satisfied      | Socket/core pair overrides include scalars, wrappers, and nested arrays.                                                 |
| 5. Active entries and null holes                 | satisfied      | Sparse-index tests pass; audit fix preserves fixed socket null holes.                                                    |
| 6. Named fields, indexes, arithmetic, timestamps | satisfied      | Explicit helpers and focused named-field/saturation assertions pass.                                                     |
| 7. Neutral inactive CPU and active span failure  | satisfied      | Code stamps neutral entries and rejects uncovered active spans.                                                          |
| 8. Allowed-mask ownership/intersection           | satisfied      | Constructor clones/intersects; update re-intersects without exposing inputs.                                             |
| 9. Core-zero reservation                         | satisfied      | Reservation follows intersections and core-zero-only fixture passes.                                                     |
| 10. Immutable fixed topology graph               | satisfied      | Masks/lists are owned, spans are fixed, and inactive socket holes now publish.                                           |
| 11. Global/socket versions                       | satisfied      | Identity, pressure independence, deactivation, and reactivation cases pass.                                              |
| 12. Greatest-sequence coalescing/no lost newest  | unverified     | Source follows the specified pending-slot/release-recheck protocol, but R2-R8/R12 deterministic race coverage is absent. |
| 13. Volatile publication happens-before          | unverified     | The volatile field and single-read readers provide the stated JMM boundary; R9 reader stress evidence is absent.         |
| 14. Scope boundaries                             | satisfied      | Diff is limited to P2-B-owned production/tests and authorized audit/status documentation; training was excluded.         |
| 15. Required verification/hygiene                | unverified     | Focused tests and `git diff --check` pass; pinned API, Zig-backed verify, and core gate cannot run here.                 |

T04 value ownership/equality and snapshot derivation, T05 remap/index/version behavior, and T06
publication were classified through the same rows above. The only correction was blueprint-settled:
copy the fixed socket list without rejecting its required null holes.

## Review conclusion

The completed behavior is review-ready with the recorded verification limits. Before root audit,
reviewers should merge this audit and arrange the missing deterministic R2-R12 race evidence and
pinned Java 21/Gradle 3.9.16/Zig gates; no architectural redesign is proposed.
