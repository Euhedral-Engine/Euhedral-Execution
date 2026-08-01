# Phase 1 Child B: Loader, Maven Packaging Gates, Runtime Smoke, and CI

## Authority and scope

This child implements Child B of
[`phase-1-native-build-jni-packaging.md`](phase-1-native-build-jni-packaging.md). The parent
blueprint's catalog schema, eight-product inventory, native graph, ABI exceptions, target flags,
and ad-hoc macOS signing contract are immutable inputs. This child owns only the internal runtime
loader, package/binary/runtime gates, narrow hardware-module Maven test wiring, and
`.github/workflows/hardware-utils-native.yaml`.

The implementation does not inspect or modify training, native source semantics, the Zig graph,
the root POM, or existing build/deploy workflow behavior.

## Implemented design

`NativeProductCatalog` parses the generated UTF-8 TSV under a 65,536-byte bound, validates its
schema and aliases, proves every declared resource exists, and publishes immutable products.
Selection normalizes runtime properties with `Locale.ROOT`, has no architecture default, and
orders candidates by load order and product ID.

`JNIClassLoader.load()` triggers a holder class. The holder constructs the catalog, extractor, and
load seam exactly once; successful class initialization is the publication boundary. Linux load
attempts fall through from glibc to musl only for `IOException`, `SecurityException`, or
`LinkageError`. Exhaustion reports every resource and extraction path and the executable-filesystem
override.

`NativeLibraryExtractor` owns one randomized process directory beneath `euhedral-native-v1`, uses
create-new/no-follow operations, applies POSIX permissions or Windows owner-only ACLs, bounds
copies at 64 MiB, unlinks successful POSIX loads immediately, retains Windows DLLs until shutdown,
and performs bounded conservative stale cleanup.

Failsafe inspects the ordinary jar after `package`. It enforces the exact resource inventory,
catalog and staged/jar byte identity, architecture, imports, exports including the N01/N02 and
legacy macOS exceptions, runtime floors, Mach-O load commands, and packaged signature identity. An
isolated warm build proves product removal and symlink-safe cleanup. The smoke bundle contains only
the ordinary jar, runtime dependency jars, and `NativeLoadSmokeMain`.

The dedicated workflow builds all products once with the selected-module Maven command, then sends
that same artifact to JDK 17 Linux glibc, bounded musl, Windows, and macOS jobs. It uses read-only
permissions, non-persistent checkout credentials, target-local Zig caches, no signing secrets, and
Apple `codesign` verification for both packaged dylibs.

## Acceptance and validation

Required evidence is:

- one clean and two warm `mvn -B -pl euhedral-hardware-utils -am verify` runs with the pinned JDK,
  Maven, Zig, SDK, signer, and LLVM paths;
- all unit, P0 compatibility, package, binary, signature, warm-removal, and Linux build-host smoke
  tests passing;
- exact jar and bounded smoke inventories;
- `git diff --check`, no training diff, no tracked source binaries, no native-tree symlinks, and
  intended final status only; and
- hosted JDK 17 Linux/musl/Windows/macOS results recorded by the new workflow. Hosted results that
  cannot run locally remain environmental validation, not a local pass.

## Implementation model reassessment

The implementation spans immutable publication, strict serialization, cross-platform filesystem
ownership, bounded recovery, archive identity, three binary formats, Maven lifecycle ordering, and
runner behavior. These remain coupled at the native load/package boundary, so the parent-selected
`gpt-5.6-sol` implementation at high reasoning effort remains appropriate.

## Completion record

Implementation is complete in the owned source, test, POM, workflow, and documentation surfaces.
The clean selected-module verification initially found and repaired two test-harness issues:
duplicate ELF names in LLVM version tables and a Maven executable path missing from the forked
Failsafe JVM. A subsequent focused binary inspection passed, and the isolated warm-removal proof
passed.

Local validation used OpenJDK 21.0.2, Maven 3.9.16, Zig 0.16.0, rcodesign 0.29.0, the macOS 26.1
SDK, and `/usr/bin/llvm-readobj` plus `/usr/bin/llvm-objdump` on a 14-CPU Intel Core Ultra 7 155U
host with 62 GiB RAM. The `mise` launcher was unavailable in the shell, so the pinned installed
executables were invoked by absolute path as permitted by `AGENTS.md`.

- Clean selected-module `verify`: passed, 31 unit/P0 tests and 6 integration tests; 88.28 seconds
  elapsed and 1,009,928 KiB maximum RSS.
- Warm selected-module `verify` 1: passed; 6.02 seconds and 1,081,900 KiB maximum RSS.
- Warm selected-module `verify` 2: passed; 5.99 seconds and 1,077,380 KiB maximum RSS.
- Final selected-module `verify` after adding the explicit oversized-copy test: passed, 32 unit/P0
  tests and 6 integration tests with no failures or errors.
- JDK 17 Linux x64 glibc smoke: passed against the packaged jar.
- JDK 17 Alpine/musl smoke: passed in a network-disabled, 512 MiB, two-CPU container after pulling
  `eclipse-temurin:17-jre-alpine`; this exercises glibc `LinkageError` fallback to musl.
- `git diff --check`, training scope diff, tracked source-bin inventory, and native symlink checks:
  passed/empty as required.

The timings are descriptive build observations, not performance or throughput claims. Hosted
Windows x64/JDK 17 loading, hosted macOS/JDK 17 loading and Apple `codesign`, and the static-only
cross-architecture B06 portions remain unverified locally; the new workflow is the required
environmental gate for those results. No P5-P7 platform semantic claim is made.

### Root conformance note

The 2026-08-01 root audit classified Child B `satisfied`. No correction or validation rerun was
made, by developer direction. Hosted Windows/macOS remain unverified; see
`docs/audits/hardware-utils/phase-1-native-build-jni-packaging-conformance.md`.
