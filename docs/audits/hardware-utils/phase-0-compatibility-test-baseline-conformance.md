# Phase 0 Hardware Utils Compatibility Baseline Conformance Audit

## Scope and result

- Parent validation:
  `docs/validations/hardware-utils/phase-0-compatibility-test-baseline-validation.md`
- Audit branch: `hardware-utils-overhaul/phase-0-compatibility-baseline-audit`
- Baseline commit: `900d8c50`
- Result: passed and authorized for root closeout
- Date: 2026-07-30

This independent audit found no material deviation from the P0 blueprint. No production Java, native
source, header, Zig input, source resource, downstream module, CI, benchmark, or training path was
inspected, built, tested, or edited.

## Evidence

The audit used the documented toolchain fallback because the system defaults are Java 17 and Gradle
3.6.3. The pinned tools were invoked explicitly:

```text
Java:  /home/bagotay/.local/share/mise/installs/java/21.0.2
Gradle: /home/bagotay/.local/share/mise/installs/gradle/3.9.16/apache-gradle-3.9.16
Host:  Linux amd64, kernel 6.8.0-134-generic
```

The following direct goals passed without invoking `initialize`, Zig, native generation, package,
install, publication, a root reactor, or a training selection:

```text
resources:resources compiler:compile resources:testResources compiler:testCompile surefire:test
```

The run completed 17 tests with 0 failures, 0 errors, and 0 skipped. It included all eight P0
contract tests plus the existing hardware tests. The compatibility report was:

```text
format  1
baseline  900d8c50
status  PASS
module  SAME
removed  0
changed  0
added  0
```

Its SHA-256 was
`eea7d3e22e4d7ab1c5217debeb9aafb5e1c277165d8f3b3775436add90c575a2`, matching the implementation and
validation records. The three checked-in fixture hashes also match those records. Before/after
fingerprints of every active hardware Java/resource file, including ignored resources, were
byte-identical. `git diff --exit-code -- euhedral-hardware-utils/src/main/java
euhedral-hardware-utils/src/main/resources` and `git diff --check` passed.

## Requirement classification

| Criteria | Status    | Audit evidence                                                                                                                                                          |
|----------|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1-6      | satisfied | Isolated compiled baseline fixture, exact module/API comparison, additive reporting, mutation tests, and static-facade coverage passed; report is `PASS`/`SAME`.        |
| 7        | satisfied | `NativeCompatibilityTest` passed the eight aggregate paths and Java JNI declaration/name contract, preserving N01/N02 as corrections rather than compatibility goldens. |
| 8-11     | satisfied | The mask, exact 200 ms cadence, fresh-thread concurrency, and core-zero tests all passed in the audit run.                                                              |
| 12-13    | satisfied | `DefectLedgerTest` passed all 35 exact defect IDs and subjects; no invalid behavior is asserted as required compatibility.                                              |
| 14-16    | satisfied | Source/resource fingerprints, direct-goal-only execution, clean diff checks, and 17 green tests prove non-contamination and deterministic test health.                  |

## Audit disposition

- Fixes: none.
- Skipped checks: none.
- Environmental limits: none affecting P0. Cross-platform/native runtime gates are outside this
  compatibility-only phase and remain owned by later phases.
- Handoff: the developer authorized root closeout on 2026-07-30. The audit child merge is
  `ed839216`; the P0 closeout commit tracks this report, removes the temporary P0 status block, and
  is the root inherited by P1.
