# Phase 0 Hardware Utils Compatibility and Test Baseline

## Status and authority

- Parent plan: `docs/plans/hardware-utils-platform-parity-overhaul.md`
- Baseline commit: `900d8c50`
- Root branch: `hardware-utils-overhaul/phase-0-compatibility-baseline`
- Blueprint branch: `hardware-utils-overhaul/phase-0-compatibility-baseline-blueprint`
- Owning module: `euhedral-hardware-utils`
- Blueprint model: `gpt-5.6-sol`
- Blueprint reasoning effort: `high`
- Status: implementation-ready; developer review and merge into the P0 root required before
  implementation

This blueprint is subordinate to the parent plan and `AGENTS.md`. If this blueprint and the source
at `900d8c50` disagree about an existing declaration, the source is authoritative and the
implementation must correct this blueprint's derived inventory rather than changing production
code.

### Authorized toolchain-policy revision

The developer authorized this documentation revision on 2026-08-01. Every Java command, Gradle
command, and Gradle build defaults to the exact versions in `mise.toml`; a documented
restricted-environment fallback must use the corresponding pinned installed tools and record its
versions and limits.

## Objective

Create a deterministic, checked-in compatibility gate for the hardware module. The gate must:

1. compare the current compiled Java surface to the branch-point surface at `900d8c50`;
2. preserve the complete module descriptor and every existing public/protected declaration in the
   five exported packages while allowing additive API declarations;
3. preserve intended aggregate native product paths and Java JNI declaration names;
4. prove canonical mask formatting, the exact 200 ms default, concurrent fresh-thread executor
   behavior, and the core-zero reservation;
5. map every known correction to an exact defect-ledger record and later regression-test ID; and
6. avoid executing the native-generating Gradle build lifecycle or mutating the active source/resource
   tree.

P0 freezes compatibility boundaries. It does not make an incorrect numeric result, unsafe
lifecycle, broken platform path, or stale native artifact into required behavior.

## Scope

### Owned implementation surface

Implementation may edit only:

- `euhedral-hardware-utils/pom.xml`, limited to test-scoped ASM dependencies or narrowly necessary
  module-local test configuration;
- `euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/compatibility/**`;
- `euhedral-hardware-utils/src/test/resources/compatibility/**`;
- the completion record appended to this blueprint; and
- the workflow-required temporary P0 status block in `AGENTS.md`.

The implementation may organize several small test classes differently within the owned
`compatibility` package, but it must preserve the contracts and stable test IDs defined below.

### Read-only inputs

- `euhedral-hardware-utils/src/main/java/module-info.java`;
- every Java source under the five exported packages and the unexported classes needed to derive JNI
  declarations;
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/JNIClassLoader.java`;
- `euhedral-hardware-utils/src/main/resources/build.zig`;
- current Java/native JNI declarations and headers;
- existing hardware tests; and
- the non-training core and benchmark consumer inventory named in the parent plan.

### Prohibited work

- Any production Java, C, C++, header, Zig, native binary, or generated-resource edit.
- Any root POM, CI, core, Reactor, Spring, or benchmark edit.
- Any inspection, edit, command, build, or test under `euhedral-training`.
- Running a root reactor command or the hardware module's bound `initialize`/native build in the
  active worktree.
- Publishing or installing a Gradle artifact.
- Treating stale per-source binaries, incorrect numeric outputs, JNI mismatches, or unsafe
  lifecycle behavior as compatibility requirements.

## Baseline facts

At blueprint time, `git diff --name-status 900d8c50..HEAD -- . ':(exclude)euhedral-training'`
reports only planning-document changes. Hardware production Java, native sources, resources, the
module POM, and `module-info.java` are unchanged from the branch point.

The complete module contract is:

```text
module euhedral.hardware_utils
requires static lombok
requires static org.jspecify
requires it.unimi.dsi.fastutil
requires org.slf4j
requires java.management
requires jdk.management
exports io.euhedral_execution.hardware_utils
exports io.euhedral_execution.hardware_utils.common
exports io.euhedral_execution.hardware_utils.linux
exports io.euhedral_execution.hardware_utils.osx
exports io.euhedral_execution.hardware_utils.windows
```

There are no baseline `opens`, `uses`, or `provides` directives. P0 compares the compiled module
descriptor, not this prose, so any directive, flag, version, or target change fails.

The intended aggregate native product paths are:

```text
/bin/linux/glibc/linux_jni_x64.so
/bin/linux/glibc/linux_jni_arm64.so
/bin/linux/musl/linux_jni_x64.so
/bin/linux/musl/linux_jni_arm64.so
/bin/osx/osx_jni_x64.dylib
/bin/osx/osx_jni_arm64.dylib
/bin/windows/windows_jni_x64.dll
/bin/windows/windows_jni_arm64.dll
```

Ignored per-source products such as `linux_affinity_x64.so`,
`osx_resources_arm64.dylib`, and `windows_system_layout_x64.dll` are stale artifacts, not part of
this intended set. Their later removal is B01, not a compatibility failure.

## Selected design

### Tool choice

Use a small test-scoped ASM 9.9.1 visitor and comparator owned by the hardware module. Add
`org.ow2.asm:asm:9.9.1` with test scope to the hardware POM. Do not add japicmp, Revapi, an
artifact repository, or a root build plugin.

In the hardware POM's existing Surefire configuration, expose only these test system properties:

```text
project.basedir=${project.basedir}
classes.directory=${project.build.outputDirectory}
build.directory=${project.build.directory}
```

Tests normalize those paths for I/O but never emit an absolute path into a fixture or report.

ASM is selected because the comparison must observe the compiled contract, including Lombok
generated getters, JVM descriptors, generic `Signature` attributes, `Exceptions`, constant values,
record components, nested-class access, permitted subclasses, and `module-info.class`. Source text
alone does not faithfully include generated members. Reflection is rejected because loading
hardware classes can initialize `SystemInfo`, load JNI, inspect the host, and contaminate the
comparison with platform state.

The helper has a command-line entry point so the same implementation generates the branch-point
fixture and analyzes current `target/classes`. It never loads a class from either tree.

### Data flow

```text
git archive 900d8c50 (POM + hardware module only)
                  |
                  v
isolated temporary directory
direct compiler:compile goal (no Gradle build lifecycle)
                  |
                  v
baseline target/classes ----+
                             |
                             v
                       ASM surface reader
                             |
                             v
                canonical sorted API manifest
                             |
                             +--> checked-in api-900d8c50.tsv
                             |
current target/classes ------+--> subset/exact comparator
                                      |
                                      +--> deterministic report
                                      +--> JUnit assertion

native declarations + intended product fixture + defect ledger
                                      |
                                      +--> native/ledger contract tests

focused runtime fixtures ------------+--> mask/cadence/executor/core-zero tests
```

### Package and naming

Use package
`io.euhedral_execution.hardware_utils.compatibility`.

Contract-bearing test IDs are:

- `ApiCompatibilityTest#preservesTheBranchPointSurfaceAndCompleteModuleDescriptor`
- `ApiComparatorTest#rejectsChangedDescriptorsAndRecordComponentOrder`
- `NativeCompatibilityTest#preservesAggregateProductsAndJavaJniDeclarations`
- `DefectLedgerTest#mapsEveryKnownCorrectionToAnExactLaterRegression`
- `MaskFormattingCompatibilityTest#preservesCanonicalCpuMaskText`
- `DefaultCadenceCompatibilityTest#defaultsToExactlyTwoHundredMilliseconds`
- `PinnedThreadExecutorCompatibilityTest#submissionsUseConcurrentFreshThreads`
- `CoreZeroReservationCompatibilityTest#reservesCoreZeroWhenAnotherCoreIsAvailable`

The implementation may add focused helper tests, but these IDs are stable ledger and handoff
references.

Required checked-in resources are:

```text
src/test/resources/compatibility/api-900d8c50.tsv
src/test/resources/compatibility/native-contract-900d8c50.tsv
src/test/resources/compatibility/defect-ledger.tsv
```

Required high-reasoning helpers are:

```text
ApiSurface
ApiSurfaceReader
ApiSurfaceComparator
CompatibilityReport
DefectLedger
```

They may be nested or package-private types. `ApiSurface` owns the command-line generation entry
point. Do not create production utilities.

## Compiled API contract

### Inclusion boundary

The reader first parses `module-info.class` and obtains the exact exported-package set. It then
reads every `.class` file beneath the supplied classes directory in relative-path byte order.

Include:

- all public top-level types in a baseline exported package;
- all public or protected nested types whose complete enclosing chain is included;
- every public or protected field, method, and constructor declared by an included type;
- public/protected compiler-generated bridge methods because they are binary entry points;
- record components, nested/nest metadata, and permitted-subclass metadata for included types; and
- every module directive and relevant module flag.

Exclude:

- private and package-private members;
- public types in unexported packages from the Java API comparison;
- annotations, debug information, source filenames, line numbers, local-variable tables, parameter
  names, paths, timestamps, bytecode bodies, and method order; and
- synthetic local/anonymous classes that are not public/protected nested API types.

The separate JNI inventory includes every `ACC_NATIVE` method regardless of Java visibility or
package export because its owner and descriptor define a native entry contract.

### Stable identity and equality

Use these stable keys:

- module: one fixed `module` key;
- module directive: directive kind plus target/source name;
- type: binary class name;
- field: declaring binary class name plus field name;
- method/constructor: declaring binary class name, JVM name, and JVM descriptor;
- record component: declaring binary class name plus zero-padded component index;
- nested relation: declaring binary class plus inner binary class;
- permitted subclass: declaring binary class plus permitted binary class; and
- native declaration: declaring binary class, JVM name, and descriptor.

For a baseline key, compare all recorded values exactly. Values include:

- API-relevant class/member access flags;
- superclass and declared interface binary names;
- raw JVM descriptor;
- generic signature or the literal `-`;
- declared exception binary names sorted lexicographically;
- field constant type and value;
- nest host/member and inner/outer/simple-name relationships;
- record component index, name, descriptor, and generic signature; and
- permitted subclasses.

API-relevant flags include public, protected, static, final, abstract, interface, annotation, enum,
record, sealed/permitted metadata, native, synchronized, strict, volatile, transient, bridge,
varargs, and synthetic where they apply. Ignore `ACC_SUPER` and classfile implementation flags
that do not express a source/binary contract.

Constant encoding is typed so `int:1`, `long:1`, `float:0x1.0p0`, `double:0x1.0p0`,
`char:U+0001`, `boolean:true`, and `string:<escaped>` cannot compare equal accidentally.
Floating constants use `Float.toHexString`/`Double.toHexString`; NaN payloads, if ever present, use
their raw bit representation.

Record component order is contractual. Sorting the manifest must not erase it: the key contains a
six-digit zero-padded index and the value contains the component name and descriptor.

### Module exactness and additive API

The current module section must equal the baseline module section as a set. New `requires`,
`exports`, `opens`, `uses`, or `provides` directives are not additive API members and fail. The
module name and the five exports are exact.

For Java declarations:

1. Every baseline key must exist with exactly the same value.
2. A missing baseline key is `REMOVED` and fails.
3. A present key with different values is `CHANGED` and fails.
4. A new public/protected type or member under one of the existing five exports is `ADDED` and
   passes.
5. An addition cannot mask a missing/changed key. For example, changing a descriptor produces one
   failing removal and one accepted addition.
6. A public type in a newly exported package cannot pass because the module comparison fails first.

No defect-ledger record may waive a Java API removal or change. The parent requirement permits
behavior corrections and additive API, not binary/source incompatibility.

### Static facade

The `SystemInfo` baseline type and all its direct public fields/methods retain their static access
flags through the normal member comparison. This explicitly covers the Lombok-generated
`isX86()` method. The nested records remain nested API types but are not interpreted as static
facade methods.

### Deterministic manifest format

Both API and native fixtures are UTF-8 TSV with LF line endings. The first lines are:

```text
format	1
baseline	900d8c50
```

Each remaining line is:

```text
<kind>	<stable-key>	<canonical-value>
```

Escape backslash first, then tab, LF, and CR as `\\`, `\t`, `\n`, and `\r`. Do not use locale,
platform path separators, absolute paths, timestamps, hash-map iteration order, or JSON object
order. Render and sort data lines by unsigned UTF-8 byte order. Reject a duplicate stable key.

The generator writes to a caller-supplied temporary file with create-new semantics, flushes and
closes it, rereads it, and rejects non-canonical ordering or escaping. It never writes directly to
the checked-in fixture.

### Deterministic report

Write `target/p0-compatibility/compatibility-report.txt` with LF endings:

```text
format	1
baseline	900d8c50
status	PASS|FAIL
module	SAME|CHANGED
removed	<count>
changed	<count>
added	<count>
REMOVED	<stable-key>	<baseline-value>
CHANGED	<stable-key>	<baseline-value>	<current-value>
ADDED	<stable-key>	<current-value>
```

Sort detail lines by category in `REMOVED`, `CHANGED`, `ADDED` order and then by stable key.
`ADDED` is informational. Failure output names every missing/changed key without truncation and
the JUnit assertion points to the report. The same inputs must produce byte-identical fixtures and
reports in two consecutive runs.

## Native compatibility contract

`native-contract-900d8c50.tsv` contains:

- the eight intended aggregate product paths listed above;
- every Java native declaration's owner, name, descriptor, static flag, and visibility;
- its derived short JNI symbol name; and
- current native-source symbol exceptions that are explicitly tied to the defect ledger.

Derive JNI escaping according to the JNI name rules (`_` -> `_1`, `;` -> `_2`, `[` -> `_3`, other
non-alphanumeric UTF-16 code units -> `_0xxxx`). There are no overloaded baseline native methods,
so the short name is the intended entry name. If an overload is later added, it is additive but
must also record the long mangled name containing the encoded argument descriptor.

`NativeCompatibilityTest` scans compiled class files with ASM and requires every baseline native
declaration unchanged. It allows additive native declarations. It also asserts that the intended
product set is exactly the eight aggregate paths; stale per-source products are never added to the
fixture.

The test scans checked-in native sources/headers for JNI symbol tokens. A missing intended Java
symbol passes only when `defect-ledger.tsv` contains an exact subject for that symbol, its observed
old symbol/descriptor, a later invariant, and a later test ID. Extra native symbols are reported
as additions and do not become required baseline entries.

Known branch-point mismatches, including the Windows timer symbol owner and macOS declaration/header
shape mismatches, are defects N01/N02. The fixture records the intended Java-owned JNI name; it
does not redefine the mismatched native spelling as compatible.

Physical aggregate binaries need not exist in a clean P0 checkout and are not generated by P0.
P1 will prove build/package presence. P0 proves the lookup/name contract only.

## Defect ledger contract

### Schema

`defect-ledger.tsv` has this exact header:

```text
defect_id	owner_phase	subject	old_behavior	new_invariant	regression_test_id
```

Rules:

- one logical record per line, UTF-8/LF, no blank fields;
- `defect_id` matches one known plan ID;
- `owner_phase` is an ordered comma-separated list matching `P[1-8](,P[1-8])*`, contains only the
  plan's later owning phases for that ID, and never contains P0;
- `subject` is an exact fully qualified Java member, JNI symbol, native/build resource anchor, or
  named contract anchor; `*`, `...`, "all", and package-only subjects are forbidden;
- multiple rows with the same defect ID are allowed when a defect spans exact subjects;
- old behavior is factual and does not use "buggy", "wrong", or another content-free description;
- new invariant is testable and does not say only "fixed";
- regression test IDs use `fully.qualified.TestClass#method`; and
- duplicate `(defect_id, subject, regression_test_id)` tuples fail.

The ledger test contains the exact expected ID-to-owner mapping. Missing and unknown IDs fail.
Adding a newly discovered defect requires a blueprint/plan update before changing that expected
set.

### Required mapping

The implementation must encode at least these exact anchors and regression IDs. Split a row into
more exact subjects when needed; do not broaden it.

| ID | Owner | Exact subject anchor | Old behavior -> new invariant | Regression test ID |
| --- | --- | --- | --- | --- |
| B01 | P1 | `resource-tree:euhedral-hardware-utils/src/main/resources/bin` | Build output and stale binaries live under source resources -> builds stage only exact generated products under `target` and never mutate the source tree | `io.euhedral_execution.hardware_utils.compatibility.NativePackagingTest#neverWritesNativeProductsIntoSourceResources` |
| B02 | P1 | `resource:euhedral-hardware-utils/src/main/resources/build.zig#native-folder-and-target-inventory` | Hardcoded shallow unsorted discovery skips failures -> one checked-in manifest drives recursive sorted fail-loud discovery | `io.euhedral_execution.hardware_utils.compatibility.NativeManifestTest#discoversEveryDeclaredFolderDeterministically` |
| B03 | P1 | `resource:euhedral-hardware-utils/src/main/resources/build.zig#macos-sign-install-edge` | Signing follows install and may not sign the packaged copy -> staged copy is signed and verified before package install completes | `io.euhedral_execution.hardware_utils.compatibility.NativeSigningTest#packagesTheVerifiedSignedMacosCopy` |
| B04 | P1 | `resource:.github/workflows/build.yaml#jni-platform-headers` | Linux `jni_md.h` is copied to Darwin/Win32 -> every target uses a target-correct generated ABI header | `io.euhedral_execution.hardware_utils.compatibility.JniHeaderTest#usesTargetCorrectPlatformHeaders` |
| B05 | P1 | `io.euhedral_execution.hardware_utils.internal.JNIClassLoader::<clinit>()V` | Loader assumes POSIX, weakly handles fallback/temp/noexec cleanup -> platform-safe extraction, fallback, diagnosis, and cleanup | `io.euhedral_execution.hardware_utils.internal.JNIClassLoaderTest#loadsOrReportsEveryFallbackDeterministically` |
| B06 | P1,P5,P6,P7 | `contract:native-products#architecture-export-import-runtime-floor` | Product metadata/runtime floors are not enforced -> every product has architecture/export/import/floor gates and real smoke evidence | `io.euhedral_execution.hardware_utils.compatibility.NativeBinaryGateTest#checksEveryManifestProduct` |
| B07 | P1 | `resource:euhedral-hardware-utils/src/main/resources/build.zig#native-module-options` | ReleaseFast/O3 and disabled safety/diagnostic settings are inherited without evidence -> every optimization, hardening, unwind, runtime, SDK, and framework choice is explicit and gated | `io.euhedral_execution.hardware_utils.compatibility.NativeBuildPolicyTest#enforcesTheSelectedPortableBuildPolicy` |
| T01 | P2,P7 | `io.euhedral_execution.hardware_utils.SystemInfo::<clinit>()V` | macOS empty maps dereference CPU/cache zero -> common initialization and macOS topology always provide a safe complete fallback | `io.euhedral_execution.hardware_utils.SystemInfoFallbackTest#initializesWithIncompletePlatformTopology` |
| T02 | P2,P5 | `io.euhedral_execution.hardware_utils.linux.LinuxSystemLayout#init()V` | Local core IDs, sparse CPU IDs, filesystem order, and missing caches are mishandled -> globally unique deterministic complete Linux topology | `io.euhedral_execution.hardware_utils.linux.LinuxSystemLayoutFixtureTest#normalizesSparseMultisocketTopology` |
| T03 | P2,P6 | `io.euhedral_execution.hardware_utils.windows.win32.SystemLogicalProcessorInformation#parse(Ljava/nio/ByteBuffer;)Ljava/util/List;` | Offsets/bounds/group IDs/bit 63 are incorrect -> bounded parsing and bijective processor-group identity | `io.euhedral_execution.hardware_utils.windows.WindowsTopologyFixtureTest#parsesMultipleGroupsAndBitSixtyThree` |
| T04 | P2 | `io.euhedral_execution.hardware_utils.TopologyMapper#update(Lio/euhedral_execution/hardware_utils/common/SystemUtilization$HardwareUtilization;)V` | Masks alias, publication is unclear, updates drop, versions disagree -> owned immutable coalesced publication with consistent versions | `io.euhedral_execution.hardware_utils.TopologyMapperPublicationTest#publishesOwnedCoalescedTopology` |
| T05 | P2,P4 | `io.euhedral_execution.hardware_utils.common.SystemUtilization$CoreSnapshot#equals(Ljava/lang/Object;)Z` | Published storage aliases and equality/value population disagree -> deep immutable snapshots with record-consistent equality/hash and named values | `io.euhedral_execution.hardware_utils.common.SnapshotOwnershipTest#publishedSnapshotsRemainStableAndValueConsistent` |
| T06 | P2 | `io.euhedral_execution.hardware_utils.TopologyMapper#update(Lio/euhedral_execution/hardware_utils/common/SystemUtilization$HardwareUtilization;)V` | Core-zero reservation can empty the allowed topology -> reserve core zero only when another allowed core remains | `io.euhedral_execution.hardware_utils.TopologyMapperCoreZeroTest#fallsBackWhenCoreZeroIsTheOnlyAllowedCore` |
| A01 | P3 | `io.euhedral_execution.hardware_utils.ThreadTools::<clinit>()V` | Base-mask probing mutates affinity, misses sparse IDs, and may dereference no pinner -> non-destructive capability-aware discovery restores the original mask | `io.euhedral_execution.hardware_utils.ThreadToolsAffinityTest#discoversAndRestoresTheOriginalMask` |
| A02 | P3 | `io.euhedral_execution.hardware_utils.PinnedThreadExecutor#execute(Ljava/lang/Runnable;)V` | Acquisition, execute/shutdown, identity, cleaner/hook, termination, and interruption race -> one identity-safe lifecycle state machine preserving fresh concurrent threads | `io.euhedral_execution.hardware_utils.PinnedThreadExecutorLifecycleTest#linearizesExecuteShutdownAndCleanup` |
| A03 | P6 | `io.euhedral_execution.hardware_utils.windows.WindowsAffinity#setAffinity([J)Z` | Multi-group status/current IDs/buffers are unsafe or overwritten -> exact representable success, stable logical ownership, and validated buffers | `io.euhedral_execution.hardware_utils.windows.WindowsAffinityTest#handlesProcessorGroupsWithoutPartialSuccess` |
| A04 | P7 | `io.euhedral_execution.hardware_utils.osx.OSXAffinity#setAffinity([J)Z` | Locality tag is called hard affinity, release/current CPU/timer policy are unsafe -> honest locality hint, tag-zero release, managed ownership, and no realtime policy | `io.euhedral_execution.hardware_utils.osx.OSXAffinityTest#reportsAndReleasesOnlyRepresentableLocalityHints` |
| R01 | P4,P5,P6,P7 | `io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider#getSnapshot()Lio/euhedral_execution/hardware_utils/common/SystemUtilization$SystemSnapshot;` | Providers mix cumulative/delta/duration/ratio units -> canonical typed sample units with validity and common deltas | `io.euhedral_execution.hardware_utils.common.ProviderContractTest#normalizesEveryPlatformSampleUnit` |
| R02 | P4,P5 | `io.euhedral_execution.hardware_utils.linux.CgroupV2Resources#updateCpuQuota()V` | Unlimited quota, pressure scaling, and zero-stall reset are incorrect -> effective CPU quota and single-scale interval pressure reset correctly | `io.euhedral_execution.hardware_utils.linux.CgroupPressureTest#handlesUnlimitedQuotaAndZeroStallIntervals` |
| R03 | P4,P7 | `io.euhedral_execution.hardware_utils.osx.OSXResources#getSnapshot()Lio/euhedral_execution/hardware_utils/common/SystemUtilization$SystemSnapshot;` | macOS cumulative/delta CPU and inactive memory semantics conflict -> canonical cumulative counters and correct working-set inputs | `io.euhedral_execution.hardware_utils.osx.OSXResourcesContractTest#emitsCanonicalCumulativeCountersAndMemory` |
| R04 | P4,P6 | `io.euhedral_execution.hardware_utils.windows.WindowsResources#getSnapshot()Lio/euhedral_execution/hardware_utils/common/SystemUtilization$SystemSnapshot;` | Cycle/time, quota, masks, and working set use incorrect units/bounds -> canonical counters, CPU capacity, complete masks, and saturating memory | `io.euhedral_execution.hardware_utils.windows.WindowsResourcesContractTest#emitsCanonicalBoundedResourceValues` |
| R05 | P4 | `io.euhedral_execution.hardware_utils.ResourceMonitor#updateMemory(Lio/euhedral_execution/hardware_utils/common/SystemUtilization$SystemSnapshot;)V` | Per-CPU memory is dimensionless but named bytes and zero limits create invalid math -> byte-correct finite memory inputs and effective pressure | `io.euhedral_execution.hardware_utils.ResourceMonitorPressureTest#keepsMemoryUnitsFiniteAndDimensional` |
| R06 | P4,P5 | `io.euhedral_execution.hardware_utils.ResourceMonitor#updateDiskIO(Lio/euhedral_execution/hardware_utils/common/SystemUtilization$SystemSnapshot;)V` | Healthy throughput is treated as pressure and Linux selects wrong devices -> throughput stays telemetry and supported stall evidence drives pressure | `io.euhedral_execution.hardware_utils.ResourceMonitorPressureTest#doesNotThrottleHealthyIoThroughput` |
| R07 | P4 | `io.euhedral_execution.hardware_utils.common.SystemUtilization$HardwareUtilization#getCpuSnapshot(IDI)Lio/euhedral_execution/hardware_utils/common/SystemUtilization$CpuSnapshot;` | Prior quota and multiplied/omitted signals distort pressure -> independent normalized domain signals compose monotonically | `io.euhedral_execution.hardware_utils.common.PressureCompositionTest#isBoundedAndMonotonicPerSignal` |
| R08 | P4 | `io.euhedral_execution.hardware_utils.ResourceMonitor#runLoop()V` | EWMA assumes 200 ms and poll cost is subtracted twice -> actual elapsed-time smoothing on an anchored skip-ahead grid | `io.euhedral_execution.hardware_utils.ResourceMonitorSchedulerTest#skipsFromZeroThroughFourFiftyToSixHundredMilliseconds` |
| R09 | P4 | `io.euhedral_execution.hardware_utils.ResourceMonitor#updateListeners(Lio/euhedral_execution/hardware_utils/common/SystemUtilization$HardwareUtilization;)V` | Common-pool listeners backlog, overlap, reorder, deadlock, wedge, and outlive close -> bounded ordered latest-value delivery with safe reentrancy and Throwable isolation | `io.euhedral_execution.hardware_utils.ResourceMonitorListenerTest#coalescesOrdersAndClosesListenerDelivery` |
| R10 | P4 | `io.euhedral_execution.hardware_utils.ResourceMonitor#start()V` | Priming, provider, stopped reads, timestamps, and close/self-join race -> complete idempotent lifecycle with one sampler owner | `io.euhedral_execution.hardware_utils.ResourceMonitorLifecycleTest#linearizesStartReadStopAndClose` |
| R11 | P5 | `io.euhedral_execution.hardware_utils.linux.CgroupV2Resources#readToBuffer(Ljava/nio/channels/FileChannel;)I` | Channels leak, reads truncate, and missing paths log each poll -> explicit cleanup, bounded complete reads, and rate-limited diagnostics | `io.euhedral_execution.hardware_utils.linux.LinuxResourceReaderTest#readsCompletelyAndClosesEveryResource` |
| R12 | P5 | `io.euhedral_execution.hardware_utils.linux.LinuxPaths#resolveCgroupPath(Ljava/lang/String;)Ljava/nio/file/Path;` | Discovery writes subtree control and changes scope, with no v1 -> read-only scope-preserving v1/v2/hybrid/bare discovery | `io.euhedral_execution.hardware_utils.linux.CgroupDiscoveryTest#neverWritesAndPreservesProcessScope` |
| R13 | P4,P5,P6,P7 | `contract:internal-hardware-sample#valid-pressure-signal-set` | Reliable contention/capacity-loss signals are absent -> only supported validity-tracked scheduler, quota, memory, I/O, steal, thermal, frequency, power, and low-power signals contribute | `io.euhedral_execution.hardware_utils.common.PressureSignalAvailabilityTest#usesOnlySupportedFreshSignals` |
| R14 | P4,P5 | `io.euhedral_execution.hardware_utils.linux.CgroupV2Resources#updatePerCpuPressure()V` | Aggregate cgroup PSI is apportioned by unrelated host activity -> global pressure propagates honestly or per-CPU attribution stays neutral | `io.euhedral_execution.hardware_utils.linux.CgroupPressureTest#doesNotApportionCgroupPsiByHostJiffies` |
| N01 | P6 | `jni:Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_ntSetTimerResolution` | Native owner/symbols, lengths, VLAs, and initialization are inconsistent -> Java-owned symbols, validated buffers, fixed storage, thread-safe fallback | `io.euhedral_execution.hardware_utils.windows.WindowsNativeBoundaryTest#validatesSymbolsBuffersAndConcurrentInitialization` |
| N02 | P7 | `jni:Java_io_euhedral_1execution_hardware_1utils_osx_OSXSystemLayout_getSysctlString` | Mach buffers leak, topology ordering/shift/timebase assumptions are unsafe -> exact JNI descriptor, explicit deallocation, bounded shifts, conservative topology, and cleanup | `io.euhedral_execution.hardware_utils.osx.OSXNativeBoundaryTest#validatesDescriptorsBoundsAndMachCleanup` |
| C01 | P8 | `io.euhedral_execution.core.control_plane.ControlPlaneFragment#cycle()V` | Hot loop assumes dense valid snapshots and attenuates P/E pressure without contract -> validated monotonic primitive cap with safe sparse input and documented memory modes | `io.euhedral_execution.core.control_plane.ControlPlaneFragmentPressureTest#mapsValidatedPressureMonotonicallyWithoutHotPathAllocation` |
| C02 | P8 | `io.euhedral_execution.core.control_plane.ControlPlaneFragment#update(Lio/euhedral_execution/hardware_utils/common/SystemUtilization$CoreSnapshot;)V` | Fragment/cache can accept malformed or older pressure independently -> one linearized acceptance feeds both consumers and rejects invalid/older snapshots | `io.euhedral_execution.core.control_plane.ControlPlaneFragmentPressureTest#updatesFragmentAndCacheFromOneAcceptedSnapshot` |

For B06 and R01/R13, one exact named contract anchor is allowed because the defect introduces a
new internal contract not represented by a single branch-point member. It is not a wildcard:
later phases must retain that exact anchor and may add member-specific rows.

### Intentional correction boundary

The comparator never allowlists Java API removal/change. The ledger governs behavior, native
implementation, resource migration, and corrected values only.

The following current observations must not appear as golden expected values:

- mutable arrays/`BitSet` values after publication;
- `CoreSnapshot.equals`/record hash disagreement or positional socket field mistakes;
- current Linux, Windows, or macOS topology IDs and fallback values;
- current pressure, throttle, memory, disk, quota, counter, or timebase numbers;
- current listener ordering, monitor lifecycle, affinity truthfulness, or executor races;
- stale per-source native files or mismatched JNI symbols;
- current platform initialization failures; and
- the current P/E attenuation and pressure-to-batch curve.

A later correction is accepted only when:

1. the Java API comparator still passes or the change is additive;
2. the exact ledger record already identifies its subject, old behavior, invariant, and test ID;
3. the named regression test is added in the owning phase; and
4. native/resource drift is either the preserved intended aggregate contract or the exact
   correction identified by the ledger.

Unmatched drift fails.

## Focused behavior contracts

### Canonical CPU-mask text

The test uses exact case and padding assertions:

| Set bits | `SystemInfo.toHexMask` |
| --- | --- |
| none | `0` |
| `0` | `1` |
| `31,32` | `1,80000000` |
| `0,32,63` | `80000001,00000001` |
| `0,64` | `1,00000000,00000001` |
| `127` | `80000000,00000000,00000000,00000000` |

Each output must round-trip through `fromHexMask`. Preserve acceptance of `0x1` and
`80000001,00000001`, rejection of malformed hex, lowercase output, 32-bit comma groups, no leading
zero group, and exactly eight digits for every non-head group.

### Exact default cadence

Do not initialize `ResourceMonitor` or `SystemInfo`. Use ASM to inspect the public constructor
`ResourceMonitor(TopologyMapper)` and require the semantic instruction sequence that passes the
long constant `200L` to `Duration.ofMillis(long)` and delegates to
`ResourceMonitor(TopologyMapper, Duration)`. Ignore labels, frames, line numbers, and the concrete
constant opcode while matching that sequence. Also assert
`Duration.ofMillis(200).toNanos() == 200_000_000L`.

The test asserts the default only, not smoothing, poll recurrence, listener behavior, or pressure
values; those belong to P4. Any later constructor refactor must retain an equivalent exact
200,000,000 ns default and update this structural test without initializing platform state.

### Concurrent fresh-thread executor

Use a valid CPU from `ThreadTools.BASE_MASK`, two submitted tasks, a start gate, an entered count,
and a release gate. Both tasks must enter before either is released, and their `Thread` identities
must differ. The executor's configured thread creator records each created thread, proving one
fresh thread per `execute`. Assertions use bounded futures/latches only for failure termination;
elapsed wall time is not a correctness assertion.

Always close the executor in `finally`, await termination, and verify the CPU registry no longer
returns it. This test preserves concurrency and fresh-thread behavior only. P3 owns lifecycle race
corrections.

### Core-zero reservation

Build utilization from the host's immutable `SystemInfo` topology and all allowed CPUs. Compute:

```text
expected = allowed
if core zero exists:
    expected -= CPUs of core zero
    if expected is empty:
        expected += CPUs of core zero
expected &= allowed
```

After `TopologyMapper.update`, the effective CPU set must equal `expected`. If another allowed core
exists, no CPU belonging exclusively to core zero remains. If core zero is the only allowed core,
it remains so the topology is nonempty. This P0 test freezes the reservation policy; P2 adds fully
fixture-driven edge coverage and fixes allowed-set ordering defects.

## Failure behavior

- Missing/unreadable classes directory: fail before producing a report.
- Missing or duplicate module descriptor: fail.
- Baseline export count other than five: fail.
- Duplicate manifest key, malformed escape, unknown fixture format, CRLF, or noncanonical sort:
  fail with file and line number.
- API removal/change: fail with stable key and both values.
- Additive member: pass and report `ADDED`.
- Missing aggregate product or changed baseline native declaration: fail.
- Native symbol mismatch without an exact ledger record: fail.
- Missing/unknown defect ID, owner mismatch, wildcard subject, blank invariant, or malformed test
  ID: fail with ledger line number.
- Runtime test timeout: fail and still clean up its executor/monitor.
- Any source/resource fingerprint change during baseline generation: fail and preserve the
  temporary evidence directory for inspection.

## Memory, ownership, and contamination

### Memory semantics

P0 changes no production publication mode. The API reader and comparator are single-threaded.
Their mutable maps/lists are confined to the invoking thread and converted to sorted immutable
values before comparison.

The executor behavior test uses `CountDownLatch`/`CompletableFuture` synchronization. A task's
write before `countDown` happens-before the test's successful `await`; the release latch publishes
permission for both tasks to exit. No assertion depends on unsynchronized collections.

The default-cadence test does not initialize the monitor or start delivery. The core-zero test consumes the
volatile/atomic publication through the public mapper accessor exactly as existing consumers do;
it does not claim to settle T04's future publication mode.

### Memory pollution and state contamination

- ASM reads bytes and never calls `Class.forName`, reflection on a production type, or a static
  initializer during API/native extraction.
- The isolated archive contains only the root POM and hardware module from `900d8c50`; no training
  path is archived.
- All baseline compilation output and generated manifests are under a `mktemp -d` directory.
- Active compilation output is under `euhedral-hardware-utils/target`.
- Baseline generation writes only to a caller-supplied temporary output. The checked-in fixture is
  reviewed and added as an ordinary implementation edit.
- Fingerprint all regular files under active `src/main/java` and `src/main/resources`, including
  ignored binaries, before and after generation using relative path, size, and SHA-256. The
  fingerprints must be byte-identical.
- Never invoke `zig`, `exec:exec`, Gradle `initialize`, `test`, `verify`, `package`, `install`, or
  another lifecycle phase in the active worktree during P0. Use the direct plugin goals specified
  below.
- Tests close monitors/executors and leave no registered executor, polling thread, or listener task.

P0 intentionally does not measure heap allocations or native memory. The reader operates off the
hot path and the runtime tests are bounded. Snapshot aliasing/native leaks are ledgered for their
owning phases rather than normalized as baseline behavior.

## Mathematical precision

- JVM descriptors, generic signatures, integer constants, module flags, and record indexes compare
  exactly.
- Default cadence is the exact integer `200_000_000L` nanoseconds with zero tolerance.
- Mask text compares byte-for-byte in lowercase ASCII.
- Typed floating constants use hexadecimal or raw-bit canonical encoding; no decimal tolerance.
- The behavior fixture uses only finite values and nonzero limits needed to construct records.
- P0 defines no pressure tolerance, smoothing coefficient, unit conversion, topology numeric
  correction, or performance threshold. Those values are intentionally not applicable to a
  compatibility-only phase and remain owned by P2/P4-P7.
- Timeouts bound a failed concurrency test but are not timing measurements and produce no
  performance claim.

## Baseline generation and validation commands

Run from the repository root. `P0_TMP` must be the literal path printed by `mktemp -d`; validate
that it is nonempty and not a workspace/root/home path before cleanup.

### 1. Capture active source/resource state

```bash
P0_TMP="$(mktemp -d)"
test -n "$P0_TMP"
find euhedral-hardware-utils/src/main/java \
     euhedral-hardware-utils/src/main/resources \
     -type f -printf '%P\t%s\t' -exec sha256sum {} \; \
  | LC_ALL=C sort > "$P0_TMP/active-before.tsv"
git status --short
```

The `find` output includes ignored native files because it does not consult Git.

### 2. Compile only the necessary active Java/test classes with direct goals

```bash
mise exec -- gradle -B -pl euhedral-hardware-utils \
  clean:clean \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile
```

These are direct plugin goals, not a Gradle build lifecycle. They do not execute the bound
`exec-gradle-plugin:exec` Zig build.

### 3. Create and compile the isolated branch-point source

```bash
mkdir "$P0_TMP/baseline"
git archive --format=tar 900d8c50 pom.xml euhedral-hardware-utils \
  | tar -xf - -C "$P0_TMP/baseline"
mise exec -- gradle -B \
  -f "$P0_TMP/baseline/euhedral-hardware-utils/pom.xml" \
  resources:resources compiler:compile
```

This command may write only beneath the isolated temporary directory. It selects no training
module and publishes no artifact.

### 4. Generate twice and compare with the checked-in fixture

```bash
mise exec -- gradle -B -pl euhedral-hardware-utils \
  -Dexec.mainClass=io.euhedral_execution.hardware_utils.compatibility.ApiSurface \
  -Dexec.classpathScope=test \
  -Dexec.args="$P0_TMP/baseline/euhedral-hardware-utils/target/classes $P0_TMP/api-first.tsv" \
  org.codehaus.mojo:exec-gradle-plugin:3.6.3:java
mise exec -- gradle -B -pl euhedral-hardware-utils \
  -Dexec.mainClass=io.euhedral_execution.hardware_utils.compatibility.ApiSurface \
  -Dexec.classpathScope=test \
  -Dexec.args="$P0_TMP/baseline/euhedral-hardware-utils/target/classes $P0_TMP/api-second.tsv" \
  org.codehaus.mojo:exec-gradle-plugin:3.6.3:java
cmp "$P0_TMP/api-first.tsv" "$P0_TMP/api-second.tsv"
cmp "$P0_TMP/api-first.tsv" \
  euhedral-hardware-utils/src/test/resources/compatibility/api-900d8c50.tsv
```

The direct `exec:java` goal runs only the Java baseline tool and does not execute the module's
bound native `exec` goal.

### 5. Run the P0 tests without the native lifecycle

```bash
mise exec -- gradle -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  surefire:test
cp euhedral-hardware-utils/target/p0-compatibility/compatibility-report.txt \
  "$P0_TMP/report-first.tsv"
mise exec -- gradle -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  surefire:test
cmp "$P0_TMP/report-first.tsv" \
  euhedral-hardware-utils/target/p0-compatibility/compatibility-report.txt
```

The compatibility reports must compare byte-for-byte.

### 6. Prove non-contamination and scope

```bash
find euhedral-hardware-utils/src/main/java \
     euhedral-hardware-utils/src/main/resources \
     -type f -printf '%P\t%s\t' -exec sha256sum {} \; \
  | LC_ALL=C sort > "$P0_TMP/active-after.tsv"
cmp "$P0_TMP/active-before.tsv" "$P0_TMP/active-after.tsv"
git diff --exit-code -- \
  euhedral-hardware-utils/src/main/java \
  euhedral-hardware-utils/src/main/resources
git status --short
git diff --check
```

Do not use a root Gradle command. Do not run or inspect training. Retain the temporary directory
until the completion record captures fixture hashes and comparison results.

## Acceptance criteria

1. The checked-in API fixture is generated from compiled `900d8c50` classes in an isolated
   directory and is deterministic across two generations.
2. The complete compiled module descriptor matches exactly and contains the module name and all
   five exports.
3. Every baseline public/protected type/member, constructor, descriptor, hierarchy/interface,
   generic signature, API modifier, checked exception, constant value, nested relation, permitted
   subclass, and record component name/order is present and unchanged.
4. Additive public/protected declarations under an existing export pass and are reported.
5. Comparator self-tests deliberately change a method descriptor and record component order and
   prove both fail with stable diagnostics.
6. `SystemInfo` remains a static facade through exact access-flag comparison.
7. The native fixture contains only the eight intended aggregate resource paths and all baseline
   Java JNI declarations/names. Known native mismatches require exact N01/N02 ledger records.
8. Mask formatting matches every golden case exactly.
9. The `ResourceMonitor(TopologyMapper)` default is exactly `200_000_000L` ns.
10. Two executor submissions occupy distinct fresh threads concurrently before release.
11. Core zero is reserved when alternatives exist and retained when it is the only usable core.
12. The defect ledger contains every B01-B07, T01-T06, A01-A04, R01-R14, N01-N02, and C01-C02 ID,
    with exact subjects and later regression IDs.
13. No known incorrect numeric, topology, pressure, lifecycle, affinity, native, or core-policy
    result is asserted as compatible.
14. No active Java/native/resource source file changes during generation or validation, including
    ignored files under `src/main/resources/bin`.
15. No artifact is installed/published, no Zig/native lifecycle runs, and no training path or
    command is accessed.
16. Both P0 test runs pass, reports are byte-identical, `git diff --check` is clean, and final
    status contains only authorized planning/compatibility files plus preserved pre-existing user
    artifacts.

## Implementation order

1. Add the test-scoped ASM dependency.
2. Implement the immutable surface model, ASM reader, canonical encoder/decoder, comparator, and
   report.
3. Add comparator mutation tests before generating the branch-point fixture.
4. Generate the branch-point API fixture in the isolated directory and review it for five-export,
   record, nested, Lombok getter, exception, and constant coverage.
5. Add the native contract fixture/scanner and exact JNI mismatch handling.
6. Add and validate the complete defect ledger.
7. Add mask, cadence, executor, and core-zero behavior tests.
8. Run generation twice, compare fixtures, run P0 tests twice, and perform non-contamination/scope
   checks.
9. Append the completion record and update only the temporary P0 `AGENTS.md` status block.

## Sizing and split gate

No child blueprint is required.

- One module and one test-only package are owned.
- The API reader, native inventory, ledger, and four behavior tests all implement one indivisible
  compatibility gate and share the same fixtures/report vocabulary.
- There is no production lifecycle state machine, platform implementation, mathematical pressure
  model, migration, or cross-module compile repair.
- The exact bounded context below is small enough for one implementation agent.
- Splitting the extractor from its comparison/behavior gate would duplicate fixture and handoff
  reasoning without reducing implementation risk.

The implementation should produce roughly one test-scoped dependency change, three resources, and
a bounded set of helper/test classes. If implementation discovers that JPMS test compilation
requires a production `module-info.java` change or that the current bytecode cannot express a
required contract, that is a new design decision and must return to this blueprint rather than
splitting opportunistically.

## Bounded implementation context envelope

### Required inputs

- `AGENTS.md`
- `docs/AGENT_WORKFLOW.md`
- `docs/plans/hardware-utils-platform-parity-overhaul.md`, limited to the settled requirements,
  known-defect ledger, P0 section, and P0 review summary
- this blueprint
- `euhedral-hardware-utils/pom.xml`
- `euhedral-hardware-utils/src/main/java/module-info.java`
- Java sources under the five exported hardware packages
- `internal/JNIClassLoader.java` and native Java declarations
- `src/main/resources/build.zig` and JNI headers/sources, only for aggregate/JNI inventory
- existing hardware tests

The implementation need not reread downstream implementation bodies. This blueprint's compiled
surface and the parent plan's read-only downstream inventory are their compatibility summary.

### Owned outputs

- the POM's test-only ASM dependency/configuration;
- `src/test/java/io/euhedral_execution/hardware_utils/compatibility/**`;
- `src/test/resources/compatibility/**`;
- this blueprint's completion record; and
- the exact temporary P0 status block permitted in `AGENTS.md`.

### Explicit exclusions

- all production source/resource edits;
- root POM and CI;
- core, Reactor, Spring, and benchmarks;
- full native builds and package generation; and
- every training path, source, data file, output, command, and reactor selection.

## Implementation model reassessment

### Context and coupling

- Modules: one.
- Production ownership boundaries: zero; all production is read-only.
- Owned schemas: two canonical TSV formats plus the defect-ledger TSV.
- Lifecycle states implemented: none; four runtime tests observe existing contracts with bounded
  cleanup.
- Concurrency: one latch-driven executor behavior test, not a state-machine repair.
- Precision: exact classfile strings/flags/constants and one exact nanosecond value.
- Filesystem safety: isolated archive/output and before/after fingerprints are important but
  mechanically specified.
- Compile/test interactions: JPMS test compilation, ASM visitation, Lombok-generated members, and
  host hardware initialization must work together.

### Capability decision

The implementation is bounded and its design is settled, but it is not a low-effort mechanical
edit. A correct classfile visitor must preserve subtle record/module/nested/generic details, and
the test command must avoid the bound native lifecycle. The executor behavior test also needs
deterministic cleanup.

Select **`gpt-5.6-sol` with `medium` reasoning effort**. A lower-effort pass is not justified by
prior evidence, and a split would add fixture coordination without reducing the subtle bytecode
surface. High effort is unnecessary because the blueprint fixes the schemas, algorithms,
commands, failure policy, and test contracts.

## Handoff condition

Hand off this blueprint for developer review and merge into the P0 root only when:

- no compatibility meaning, tool choice, output format, defect disposition, or runtime test
  contract remains for implementation to decide;
- the P0 implementation prompt in the parent plan is finalized with the selected model and bounded
  context;
- the phase artifact index remains the unsplit P0 entry; and
- implementation has not started on this unmerged blueprint child.

No unresolved material decision remains.

## Implementation completion record

### Result

P0 implementation is complete on
`hardware-utils-overhaul/phase-0-compatibility-baseline-implementation`. The module now has a
deterministic compiled compatibility gate against `900d8c50`. No production Java, native source,
header, Zig, source resource, downstream module, CI, benchmark, or training file changed.

### Changed files

- `euhedral-hardware-utils/pom.xml`: test-scoped ASM 9.9.1 and the three bounded Surefire path
  properties.
- `euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/compatibility/**`:
  ASM surface/native readers and generators, canonical manifest parser, subset/exact comparator,
  deterministic report, defect-ledger parser, mutation tests, and focused behavior tests.
- `euhedral-hardware-utils/src/test/resources/compatibility/api-900d8c50.tsv`
- `euhedral-hardware-utils/src/test/resources/compatibility/native-contract-900d8c50.tsv`
- `euhedral-hardware-utils/src/test/resources/compatibility/defect-ledger.tsv`
- This completion record and the temporary P0 status block in `AGENTS.md`.

### Commands and results

- Captured SHA-256 fingerprints for every regular file below hardware `src/main/java` and
  `src/main/resources`, including ignored files.
- Ran direct `clean:clean resources:resources compiler:compile resources:testResources
  compiler:testCompile`: passed. This selected no lifecycle phase and did not invoke Zig.
- Created an isolated `/tmp` tree with
  `git archive --format=tar 900d8c50 pom.xml euhedral-hardware-utils` and compiled it with direct
  `resources:resources compiler:compile`: passed.
- Generated the API fixture twice from the isolated compiled baseline through direct
  `org.codehaus.mojo:exec-gradle-plugin:3.6.3:java`: byte-identical.
- Generated the native fixture twice from the same isolated compiled baseline: byte-identical.
- Ran the complete direct-goal command
  `resources:resources compiler:compile resources:testResources compiler:testCompile
  surefire:test` twice after final fixture generation: both runs passed, 17 tests, 0 failures,
  0 errors, 0 skipped.
- Compared the two compatibility reports: byte-identical.
- Recomputed the active source/resource fingerprint and compared it with the initial fingerprint:
  byte-identical.
- `git diff --exit-code -- euhedral-hardware-utils/src/main/java
  euhedral-hardware-utils/src/main/resources`: passed.
- `git diff --check`: passed.

Fixture/report SHA-256:

```text
bc950202e4e4659a5e1263fc45ff91cea5bc23ea3e9214d4918586f9c9f7f994  api-900d8c50.tsv
ca986693f60a5caf6f1eab902aec4282f8adaa1fda4dc564594acbb57273f5af  native-contract-900d8c50.tsv
8632f21f5494707a040d603743c3195fb59b71b1afc3cb2b90292fa3d8766a9c  defect-ledger.tsv
eea7d3e22c4d7ab1c5217debeb9aafb5e1c277165d8f3b3775436add90c575a2  compatibility-report.txt
```

### Acceptance evidence

1. The compiled baseline came only from the isolated `900d8c50` archive and two generations are
   byte-identical.
2. The module descriptor is compared as an exact set, including name, flags, version, all
   requires, and all five exports.
3. Baseline public/protected types and members retain descriptors, hierarchy, interfaces,
   signatures, modifiers, exceptions, typed constants, nested/nest/permitted metadata, and
   ordered record components.
4. Additive declarations pass and are reported; missing or changed baseline declarations fail.
5. Comparator mutation coverage rejects a changed method descriptor, record-component order, and
   module descriptor with stable diagnostics.
6. The compiled member comparison includes the static `SystemInfo` facade and generated `isX86`.
7. The native fixture contains exactly eight aggregate products, every Java native declaration,
   derived short JNI name, visibility/static state, and exact N01/N02 exception records. Future
   overloads receive and require deterministic long JNI names.
8. Every canonical mask case, round trip, accepted prefix, and malformed input contract passes.
9. ASM proves the default constructor delegates with `Duration.ofMillis(200)` and the test proves
   exactly `200_000_000L` ns.
10. Two latch-controlled executor submissions enter concurrently on distinct freshly created
    threads and cleanup removes the CPU registry entry.
11. The host-derived core-zero reservation matches the settled rule and leaves a nonempty
    topology.
12. The exact ledger contains B01-B07, T01-T06, A01-A04, R01-R14, N01-N02, and C01-C02 with the
    prescribed owner phases, subjects, invariants, and later regression IDs.
13. No incorrect numeric, topology, pressure, lifecycle, affinity, native, or core-policy output
    is frozen as a golden value.
14. Before/after production source/resource fingerprints are byte-identical.
15. No artifact was installed or published; no Zig/native lifecycle, root reactor, or training
    path/command was invoked.
16. Both final test runs and deterministic comparisons passed; scope checks show only P0-owned
    files.

Approved deviations: none.

Environmental limits: none for P0. The tests ran on the available Linux host and required no
Docker, cross-platform runner, native generation, or network download.

## Validation completion summary

Validation completed on
`hardware-utils-overhaul/phase-0-compatibility-baseline-validation`. The full blueprint command
sequence passed: the isolated baseline generated byte-identical fixtures twice, both direct-goal
test runs passed 17 tests with no failures or skips, reports were byte-identical, production
source/resource fingerprints were unchanged, and `git diff --check` passed. The checked-in hashes
match the implementation completion record.

The test-helper relocation to the `compatibility.helpers` subpackage is accepted as organization
within the owned test surface and does not alter a production or compatibility contract. No
validation-agent implementation correction was required. `mise` was unavailable, so the commands
used the already-installed pinned JDK 21.0.2 and Gradle 3.9.16 directly under the documented
toolchain fallback. No check was skipped and no environmental limitation prevented P0 validation.

Detailed evidence and the 16-item acceptance matrix are recorded in
`docs/validations/hardware-utils/phase-0-compatibility-test-baseline-validation.md`.

## Conformance audit completion summary

The independent P0 audit completed on
`hardware-utils-overhaul/phase-0-compatibility-baseline-audit`. All 16 acceptance criteria are
classified `satisfied`; no deviation, ambiguity, correction, or skipped check remains. The audit
reran the direct compiler/test goals with pinned JDK 21.0.2 and Gradle 3.9.16, yielding 17 passing
tests with no failures, errors, or skips. The published compatibility report is `PASS` with an
identical SHA-256 (`eea7d3e22e4d7ab1c5217debeb9aafb5e1c277165d8f3b3775436add90c575a2`), and
the active Java/resource fingerprint and production diff remained unchanged. Full command and
requirement evidence is in
`docs/audits/hardware-utils/phase-0-compatibility-test-baseline-conformance.md`.

The developer authorized P0 root closeout on 2026-07-30. The audit child merge is `ed839216`; the
P0 closeout commit tracks the conformance report and removes the temporary P0 status block. P1 must
branch from that completed root.
