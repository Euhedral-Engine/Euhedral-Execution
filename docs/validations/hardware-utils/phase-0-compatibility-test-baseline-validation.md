# Phase 0 Hardware Utils Compatibility Baseline Validation

## Scope and result

- Parent artifact:
  `docs/blueprints/hardware-utils/phase-0-compatibility-test-baseline.md`
- Validation branch:
  `hardware-utils-overhaul/phase-0-compatibility-baseline-validation`
- Baseline commit: `900d8c50`
- Result: passed
- Date: 2026-07-30

The P0 implementation satisfies the finalized blueprint. No production source, native source,
header, Zig input, source resource, downstream module, or training path was edited or exercised by
validation. The intentional relocation of test helpers to
`io.euhedral_execution.hardware_utils.compatibility.helpers` is accepted as test-only organization
within the owned compatibility surface; it changes no production or compatibility contract.

No blueprint-settled implementation correction was required. The developer separately removed the
stray LibreOffice lock file from the compatibility resources during validation; that deletion is
not a validation-agent change.

## Environment and commands

`mise` was unavailable in the validation shell. Following the repository's documented restricted
environment fallback, validation used the already-installed pinned tools directly:

```text
Java:  /home/bagotay/.local/share/mise/installs/java/21.0.2
Maven: /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16
Host:  Linux amd64, kernel 6.8.0-134-generic
```

The following blueprint-prescribed operations passed:

1. Fingerprinted every regular file below hardware `src/main/java` and `src/main/resources`,
   including ignored native binaries.
2. Ran direct goals only:
   `clean:clean resources:resources compiler:compile resources:testResources
   compiler:testCompile`.
3. Extracted `pom.xml` and `euhedral-hardware-utils` at `900d8c50` with `git archive` into
   `/tmp/tmp.bX0RtZBJqz/baseline` and compiled it with direct `resources:resources
   compiler:compile` goals.
4. Generated the API manifest twice through direct
   `org.codehaus.mojo:exec-maven-plugin:3.6.3:java`; both generated files and the checked-in
   fixture compared byte-for-byte.
5. Ran
   `resources:resources compiler:compile resources:testResources compiler:testCompile
   surefire:test` twice. Each run passed 17 tests with 0 failures, 0 errors, and 0 skipped.
6. Compared both compatibility reports byte-for-byte.
7. Compared before/after production source-resource fingerprints byte-for-byte.
8. Ran the production source/resource `git diff --exit-code` check and `git diff --check`; both
   passed.

No lifecycle phase, Zig command, native build, root reactor, `package`, `install`, or artifact
publication command ran.

## Determinism evidence

```text
bc950202e4e4659a5e1263fc45ff91cea5bc23ea3e9214d4918586f9c9f7f994  api-900d8c50.tsv
ca986693f60a5caf6f1eab902aec4282f8adaa1fda4dc564594acbb57273f5af  native-contract-900d8c50.tsv
8632f21f5494707a040d603743c3195fb59b71b1afc3cb2b90292fa3d8766a9c  defect-ledger.tsv
eea7d3e22c4d7ab1c5217debeb9aafb5e1c277165d8f3b3775436add90c575a2  compatibility-report.txt
```

The final report is `PASS`, its module state is `SAME`, and it contains zero removed, changed, or
added entries. The fixture contains the exact five exported packages, 88 ordered public record
component entries, and all 35 known-defect IDs.

## Acceptance-criterion matrix

| # | Result | Evidence |
|---:|:------:|----------|
| 1 | Pass | The isolated `900d8c50` archive compiled outside the workspace; two generated API manifests and the checked-in fixture were byte-identical. |
| 2 | Pass | The report says `module SAME`; fixture inspection lists exactly the five required exports. |
| 3 | Pass | `ApiCompatibilityTest` passed the ASM subset/exact-value comparison over types, members, descriptors, hierarchy, signatures, flags, exceptions, constants, nested metadata, and ordered records. |
| 4 | Pass | Comparator self-tests preserve additions as informational while requiring the baseline subset. |
| 5 | Pass | `ApiComparatorTest#rejectsChangedDescriptorsAndRecordComponentOrder` passed descriptor, record-order, and module mutations. |
| 6 | Pass | The compiled fixture includes the `SystemInfo` static facade and Lombok-generated surface; the exact access comparison passed. |
| 7 | Pass | `NativeCompatibilityTest` passed the exact eight-product and Java JNI declaration/name contract, including N01/N02 exceptions. |
| 8 | Pass | `MaskFormattingCompatibilityTest#preservesCanonicalCpuMaskText` passed every golden and malformed case. |
| 9 | Pass | `DefaultCadenceCompatibilityTest#defaultsToExactlyTwoHundredMilliseconds` proved the constructor delegation and exact `200_000_000L` value. |
| 10 | Pass | `PinnedThreadExecutorCompatibilityTest#submissionsUseConcurrentFreshThreads` proved two distinct fresh threads entered concurrently and cleaned up. |
| 11 | Pass | `CoreZeroReservationCompatibilityTest#reservesCoreZeroWhenAnotherCoreIsAvailable` passed reservation and nonempty fallback behavior. |
| 12 | Pass | `DefectLedgerTest` passed exact ownership for all 35 B/T/A/R/N/C IDs; ledger parsing enforces exact subjects and later test IDs. |
| 13 | Pass | Review found no invalid result golden; tests freeze contract boundaries and map intentional corrections to later regression IDs. |
| 14 | Pass | Before/after fingerprints and the production source/resource Git diff were unchanged. |
| 15 | Pass | Only direct Maven plugin goals ran; no native lifecycle, publication, root reactor, or training access occurred. |
| 16 | Pass | Both 17-test runs and byte comparisons passed; `git diff --check` is clean. The only concurrent non-validation change was the developer's lock-file deletion. |

## Fixes, skipped checks, and limits

- Validation-agent fixes: none.
- Skipped checks: none.
- Environmental limits: `mise` was absent, but the exact pinned JDK 21.0.2 and Maven 3.9.16 were
  already installed and usable through the documented explicit-toolchain fallback. P0 required no
  Docker, native generation, network download, or cross-platform runner.

The validation child is ready for developer review and merge into the P0 root. The P0 conformance
audit must begin only after that merge.
