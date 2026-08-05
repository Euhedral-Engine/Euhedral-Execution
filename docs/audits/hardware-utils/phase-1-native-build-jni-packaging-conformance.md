# Phase 1 Native Build, JNI, Loader, and Packaging Conformance Audit

## Scope and disposition

- Audited root: `hardware-utils-overhaul/phase-1-native-build` at `a5204f8b`, plus the authorized
  audit-record changes
- Inherited P0 root: `03ff2060`
- Date: 2026-08-01
- Result: implementation substantially conforms; P1 complete under conformance-only workflow

At the developer's direction this audit did not run validation. The root integration validation,
Child A blueprint/completion/validation/audit, and Child B validation/audit artifacts are absent.
Their absence is missing evidence, not a reconstructed pass. No production correction was made
and no training path was inspected or run.

P1 is complete by developer direction. Hosted Windows/macOS results remain unverified and the
Child A artifact contract remains ambiguous; these classifications are retained as evidence
limits, not silently converted to passes. No P2 work was created.

## Evidence

The audit read the governing instructions, P0 closeout, parent P1 blueprint/plan, available Child
B blueprint/completion, complete P1 diff, manifest, Zig graph, Gradle build lifecycle, loader/extractor,
workflow, relevant tests, generated resources, catalog, and ordinary jar. The jar contains exactly
eight native products plus `META-INF/euhedral/native-products.tsv`; the staged catalog is 804 bytes
with SHA-256 `1eb24160db409c6f2b69fec9699d8b2cd09748a2b1cc1d9f37bb0a1e60aeeb8b`.

Static inspection found one strict JSON inventory, recursive sorted no-follow discovery, seven
generated JNI headers, target-aware ABI definitions, eight independent product nodes, per-product
macOS sign/inspect/install edges, `ReleaseSafe` hardening, target-local caches, a TSV-driven loader,
bounded owner-private extraction, exact fallback taxonomy, binary gates, and selected-module CI.
Child B's completion record reports clean/two warm/final verifies (final: 32 unit/P0 and six
integration tests), Linux glibc JDK 17 smoke, and bounded Alpine/musl fallback smoke. Those are
inherited completion claims, not audit reruns.

## Parent acceptance criteria

|  # | Classification | Evidence and limit                                                                                                                  |
|---:|:--------------:|-------------------------------------------------------------------------------------------------------------------------------------|
|  1 |   satisfied    | JSON is the sole source/product table; generic removal and exact P0 inventory have focused tests.                                   |
|  2 |   satisfied    | Discovery recursively rejects non-regular, unknown, missing, empty, and duplicate inputs and sorts paths deterministically.         |
|  3 |   satisfied    | Default lifecycle has no product selector, installs all eight under `target`, and excludes source-native resources.                 |
|  4 |   satisfied    | Outputs/caches are target-local; no native-tree symlink or tracked source binary exists; completion reports unchanged fingerprints. |
|  5 |   satisfied    | `javac -h` produces exactly seven declarations and target-aware ABI macros/widths are gated.                                        |
|  6 |   satisfied    | N01, N02, and the legacy macOS export are the only named ABI exceptions.                                                            |
|  7 |   satisfied    | Every component includes `JNI_OnLoad` returning JNI 1.8; static gates and local Linux smoke are reported.                           |
|  8 |   satisfied    | Required hardening/link settings and exact Mach-O identities are present; legacy tuning/search/framework behavior is absent.        |
|  9 |   satisfied    | Separate glibc 2.17/musl products and exact libc/no-C++/compiler-runtime gates exist.                                               |
| 10 |   satisfied    | Products have independent compile/install chains; macOS installs only the separately signed and inspected output.                   |
| 11 |   satisfied    | Zig deterministically emits the manifest-derived TSV; Java has no product/alias table.                                              |
| 12 |   satisfied    | Lifecycle cleanup plus isolated warm-removal coverage prevents stale generated/classpath/catalog/jar entries.                       |
| 13 |   satisfied    | Jar inventory has only the eight binaries, catalog, expected logback resource, classes, and Gradle metadata.                         |
| 14 |   satisfied    | Package, binary, signature, and digest integration gates cover all eight products; completion records them passing.                 |
| 15 |   satisfied    | Unknown OS/architecture fails; fallback catches only IO, security, and linkage failures, not arbitrary `Error`.                     |
| 16 |   satisfied    | POSIX/Windows security, 64 MiB copy, ownership, immediate/deferred cleanup, and 64-entry scavenging are implemented/tested.         |
| 17 |   satisfied    | Exhaustion reports attempts, paths, causes, the extraction override, and honest possible-noexec diagnosis.                          |
| 18 |   satisfied    | Holder initialization safely publishes one final result and leaves no reload hot path.                                              |
| 19 |   unverified   | Workflow text conforms, but no hosted workflow result was supplied.                                                                 |
| 20 |   satisfied    | Existing build/deploy Gradle/release behavior is unchanged apart from invalid headers, SDK layout, and explicit native inputs.       |
| 21 |   satisfied    | Wall time/RSS are explicitly descriptive; no throughput/performance conclusion is claimed.                                          |
| 22 |    deviated    | B01-B05/B07 and B06 framework conform, but required child/root evidence artifacts were skipped.                                     |
| 23 |   satisfied    | Completion reports selected-module/P0 tests passing with no training selection; CI selection is hardware-only.                      |
| 24 |   unverified   | `git diff --check` passes, but final clean status cannot be established until the authorized audit records are merged.              |

## Child and defect classification

| Requirement                      | Classification | Evidence                                                                                                             |
|----------------------------------|:--------------:|----------------------------------------------------------------------------------------------------------------------|
| Child A native graph/JNI/signing |   ambiguous    | Implementation covers the visible surface, but the required child blueprint and records are absent.                  |
| Child B loader/package/CI        |   satisfied    | Available blueprint, implementation, tests, workflow, inventory, and completion evidence conform.                    |
| B01                              |   satisfied    | Generated resources/caches are target-local and source resources cannot package native products.                     |
| B02                              |   satisfied    | One strict manifest owns folders/targets/products; discovery is recursive, sorted, fail-closed.                      |
| B03                              |   satisfied    | Packaged macOS inputs are the signed/inspected outputs and digest/signature-gated.                                   |
| B04                              |   satisfied    | Invalid header copies are removed; generated declarations and target-aware ABI definitions replace them.             |
| B05                              |   satisfied    | Unknown architecture rejection, Windows ACLs, linkage fallback, bounded extraction/cleanup, and diagnostics conform. |
| B06 P1 gate framework            |   satisfied    | All eight products have mandatory static gates and available runtime-smoke wiring.                                   |
| B07                              |   satisfied    | Optimization, safety, runtime, SDK, signing, and framework policies are explicit and gated.                          |

Only these B06 portions carry forward:

- P5: Linux arm64 real runtime/full platform calls and final x64/arm64 practical kernel-floor proof.
- P6: Windows x64 hosted JDK 17 load (unverified here), Windows ARM64 real runtime/full calls, and
  minimum-family runtime proof.
- P7: macOS hosted JDK 17 load/Apple `codesign` (unverified here), Intel/Apple Silicon real
  runtime/full calls, and macOS 11 runtime proof.

Windows x64 and host-macOS jobs are also P1 environmental gates and remain `unverified`.

## Commands, fixes, skips, and limits

- Inspected `git status --short` first and preserved all pre-existing changes.
- Inspected the P1 diff, test matrix, jar/staged inventory, catalog/hash, symlinks, tracked source
  binaries, and workflow delta.
- `git diff --check`: passed.
- Fixes: none.
- Validation step: removed from the workflow by explicit developer direction; no Gradle, Zig, Docker,
  signing, hosted runner, or runtime command was run by this conformance audit.
- Limits: absent child/root evidence artifacts, hosted results, authorized merge, and clean final
  root.

## Handoff

The completed P1 conformance package is handed off. The temporary status block was removed, the
closeout summary was updated, and P2 was not created.
