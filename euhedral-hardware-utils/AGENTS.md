# Working in `euhedral-hardware-utils`

This guide applies to `euhedral-hardware-utils`. Read the repository-wide [AGENTS.md](../AGENTS.md)
first, then use this file when the task changes or depends on hardware topology, affinity, pinned
execution, resource sampling, pressure evaluation, JNI loading, or the native Zig build. Read another
module's guide only when that module is also relevant to the task.

The checked-out implementation, [build.gradle.kts](build.gradle.kts),
[native-products.json](src/main/native/native-products.json), and the nearest tests are
authoritative. [BUILD.md](../BUILD.md) and the hardware section of
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) are context only.

## Module boundary

- The module applies `buildlogic.java-conventions` but compiles with `--release 17`
  (`sourceCompatibility` and `targetCompatibility` 17). Do not use Java 18+ language features or
  APIs in this module, even though the toolchain is Java 21.
- Dependencies: `api` fastutil and SLF4J; `compileOnly` Lombok and JSpecify. JPMS additionally
  requires `java.management` and `jdk.management`. This module must not depend on
  `euhedral-core` or any higher-level module.
- Consumers: `euhedral-core` (`api` dependency), `benchmarks` (`api` dependency, plus the logback
  fragment copied by its `processResources`), and `benchmarks:cfd` transitively. The public types
  used most outside this module are `SystemInfo` and its nested records, `ThreadTools`,
  `PinnedThreadExecutor`, `TopologyMapper` and its nested records, `ResourceMonitor`,
  `UnmodifiableBitSet`, and the `SystemUtilization` snapshot records.
- The logger namespace is `euhedral.hardware_utils.` (`internal/Constants.LOGGER_PREFIX`), not the
  Java package name. Its level is controlled by
  [euhedral-hardware-utils.xml](src/main/resources/logback-fragments/euhedral-hardware-utils.xml)
  through `EuhedralHardwareUtilsLog` or `AllEuhedralLogs`.

### Exported surface

[module-info.java](src/main/java/module-info.java) exports ten packages. Several package names are
misleading; treat every exported package as public API.

| Package                                                                     | Exported | Owns                                                                                                                         |
|-----------------------------------------------------------------------------|----------|------------------------------------------------------------------------------------------------------------------------------|
| `hardware_utils`                                                            | yes      | `SystemInfo`, `ThreadTools`, `AffinityCapability`, `PinnedThreadExecutor`, `TopologyMapper`, `ResourceMonitor`              |
| `common`                                                                    | yes      | `SystemUtilization` records, `UnmodifiableBitSet`, `UnmodifiableDoubleArray`, `OSName`, `SystemSnapshotProvider`, legacy `common.ThreadPinner` |
| `linux`, `macos`, `windows`                                                 | yes      | Platform layout, resource, and affinity facades, including the JNI `native` declarations                                     |
| `internal.sampling` and its `enums`, `primitives`, `samples`, `signals` packages | yes | Detailed provider interface, sample state engine, compatibility adapter, typed signal records                                |
| `internal`                                                                  | no       | Affinity controller, masks, and provider; sealed `internal.ThreadPinner`; native loader, catalog, extractor, file security   |
| `internal.topology`                                                         | no       | Topology normalization, validation, bootstrap, and the hex-mask codec                                                        |
| `internal.pressure`                                                         | no       | Pressure evaluation, projection, constants, and state                                                                        |
| `internal.monitor`                                                          | no       | Latest-value dispatcher, deadline waiter, monotonic clock, and topology updater seams                                        |
| `macos.sysctl`, `windows.win32`                                             | no       | Platform helper types used only inside this module                                                                           |

`common.ThreadPinner` (public abstract class) and `internal.ThreadPinner` (sealed; permits only the
three platform affinity classes) are different types. The `common` class is retained for API
compatibility and has no implementation in the source tree.

## Read by change, not by directory sweep

Paths are relative to `src/main/java/io/euhedral_execution/hardware_utils/` and
`src/test/java/io/euhedral_execution/hardware_utils/`.

| Change                                              | Primary implementation                                                                                                              | Nearest tests                                                                                                                                                                           |
|-----------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Topology discovery, normalization, or fallback      | `SystemInfo`, `internal/topology/`, the platform `*SystemLayout` classes                                                            | `internal/topology/TopologyNormalizerTest`, `SystemInfoTest`, `SystemInfoFallbackTest`, `TopologyCacheFallbackTest`, `TopologyOwnershipTest`, the platform `*TopologyFixtureTest` classes, `linux/LinuxSystemLayoutFixtureTest` |
| Hex CPU masks                                       | `internal/topology/MaskCodec`, `SystemInfo.fromHexMask` and `toHexMask`                                                             | `compatibility/MaskFormattingCompatibilityTest`                                                                                                                                         |
| Effective topology publication and versions         | `TopologyMapper`, `internal/monitor/TopologyUpdater`                                                                                | `TopologyMapperTest`, `TopologyMapperPublicationTest`, `TopologyMapperVersionTest`, `TopologyMapperCoreZeroTest`, `compatibility/CoreZeroReservationCompatibilityTest`                   |
| Affinity policy, leases, managed CPU identity       | `ThreadTools`, `AffinityCapability`, `internal/AffinityController`, `internal/AffinityMasks`, `internal/ThreadPinner`                | `ThreadToolsAffinityTest`, `linux/LinuxAffinityTest`, `macos/MacosAffinityTest`, `windows/WindowsAffinityTest`                                                                          |
| Pinned executor lifecycle, registry, cleanup, hooks | `PinnedThreadExecutor`                                                                                                              | `PinnedThreadExecutorTest`, `PinnedThreadExecutorLifecycleTest`, `compatibility/PinnedThreadExecutorCompatibilityTest`                                                                  |
| Monitor lifecycle, cadence, listener dispatch       | `ResourceMonitor`, `internal/monitor/`                                                                                              | `ResourceMonitorTest`, `internal/monitor/LatestValueDispatcherTest`, `compatibility/DefaultCadenceCompatibilityTest`                                                                   |
| Raw sampling and interval resolution                | `internal/sampling/`, `linux/LinuxResourceProvider`, `linux/CgroupV2Resources`, `linux/LinuxPaths`, `macos/MacosResources`, `windows/WindowsResources` | `internal/sampling/` tests, `linux/LinuxResourceProviderTest`, `macos/MacosResourcesTest`, `windows/WindowsResourcesTest`                                                  |
| Pressure scoring                                    | `internal/pressure/`, `common/SystemUtilization`                                                                                    | `internal/pressure/PressureCompositionTest` (the other pressure tests are placeholders; see caveats), `common/` tests                                                                   |
| Native load, catalog, extraction, file permissions  | `internal/JNIClassLoader`, `NativeProductCatalog`, `NativeLibraryLoader`, `NativeLibraryExtractor`, `NativeFileSecurity`            | `internal/NativeLoaderTest`, `compatibility/NativeManifestTest`, `compatibility/NativePackagingTest`, `compatibility/NativeLoadSmokeIT`                                                  |
| JNI signatures or C++ sources                       | Platform `*Affinity`, `*Resources`, `*SystemLayout`, `macos/sysctl/SysctlNative`; `src/main/native/<os>/`                            | `compatibility/NativeCompatibilityTest`, `JniHeaderTest`, `NativeBinaryGateTest`, `NativeBinaryInspectionIT`                                                                            |
| Zig build flags, products, signing                  | `src/main/native/build.zig`, `native-products.json`, `build.gradle.kts`                                                             | `compatibility/NativeBuildPolicyTest`, `NativeManifestTest`, `NativeSigningTest`, `NativeBinaryGateTest`, `NativePackagingIT`                                                           |
| Any exported signature or `module-info.java`        | Exported packages above                                                                                                             | `compatibility/ApiCompatibilityTest`                                                                                                                                                    |

## Topology and effective membership

- `SystemInfo` resolves the platform topology once in its static initializer. Linux reads
  `/sys/devices/system/cpu` directly; macOS and Windows use JNI. Any exception or `LinkageError`
  falls back to `TopologyBootstrap.fallback(availableProcessors())`: one socket, one CPU per core,
  private L1 and L2, shared L3. Callers must tolerate that fallback shape.
- Logical CPU IDs can be sparse. `CPU_COUNT`, `MAX_CORE_ID`, and `MAX_SOCKET_ID` are distinct
  values. Size ID-indexed arrays by `MAX_*_ID + 1` and iterate `BitSet`s instead of assuming
  `0..count-1` is dense.
- P-core and E-core sets come from the normalizer's classification; `getCoreSet()` is their union.
- `TopologyMapper` publishes an immutable `EffectiveSystemTopology` through one volatile write after
  the whole graph is frozen. Concurrent `update` calls install the highest sequence and one drain
  owner publishes. Global and per-socket versions increase only on a membership change and throw on
  overflow. Inactive sockets are intentional `null` holes in a fixed-size list; do not replace it
  with `List.copyOf`, which rejects nulls.
- Effective membership is the intersection of the monitor's effective CPUs, the topology CPU set,
  and the constructor's allowed CPUs. The constructor copies its allowed set; later caller mutation
  must not leak in.

## Affinity and managed CPU identity

- `ThreadTools` selects one provider per OS and wraps it in a single `AffinityController`.
  `AffinityCapability` is chosen at construction and never changes:
  - `EXACT` (Linux and Windows with JNI loaded): applies the complete mask and restores the
    thread's first captured mask on `releaseAffinity()`.
  - `LOCALITY_HINT` (macOS): scheduler preference only; the physical current CPU is `-1`.
  - `UNSUPPORTED` (JNI load failure, unknown OS, or failed capability discovery): every request
    returns `false` without throwing.
- Leases are per thread. Nested exact applications preserve the first original binding. Pair
  `setAffinity` with `releaseAffinity` on the same thread.
- `ThreadTools.bindManagedCpu` associates a task with a logical CPU without claiming placement.
  Bindings are thread-confined and close LIFO on the creator thread. `getCpu()` returns the
  provider's truthful CPU when supported, otherwise the managed owner, otherwise `-1`.
- Keep requested affinity, managed logical CPU, and observed physical CPU as separate facts in code,
  logs, and tests. A `true` return on `LOCALITY_HINT` does not prove physical placement.

## Pinned executor

- `PinnedThreadExecutor` creates one fresh thread per accepted command; it is not a worker pool. A
  process-wide registry holds one live executor per logical CPU. `get` never restarts an executor;
  `getOrSetIfAbsent` validates the CPU against `SystemInfo.getCpuCount()` before narrowing.
- `shutdown`, `shutdownNow`, `close`, `start` (restart after shutdown), `closeAll`, the `Cleaner`
  action, and the single registry-wide JVM shutdown hook form one lifecycle. Preserve idempotence,
  exactly-once cleanup, and the rule that a closed tombstone is dropped only after its last task
  exits.
- Deterministic tests use `newTestRegistry(...)` with injected cleanup, hook, task-binding, and
  thread-configuration seams. Use those seams rather than the global registry, sleeps, or real
  shutdown hooks.

## Resource monitor, sampling, and pressure

- `ResourceMonitor` states are `NEW -> STARTING -> RUNNING <-> STOPPED -> CLOSING -> CLOSED`, driven
  by `VarHandle` CAS. `close()` interrupts the polling thread, spins until the evaluation and
  publication flags clear, then waits for the dispatcher. New blocking inside evaluation or
  publication directly extends close latency.
- The default cadence is 200 ms (`DefaultCadenceCompatibilityTest` checks the bytecode constant).
  Ticks re-anchor on clock regression and skip overrun ticks instead of bursting. Slow samples run
  only when `SampleStateEngine.isSlowDue` reports them due.
- `evaluateAndPublish` is serialized by `evaluationLock`: optional slow sample, fast sample,
  `SampleStateEngine.processFast`, `PressureEvaluator.evaluate`, then `TopologyUpdater.update` and,
  only while `RUNNING`, `LatestValueDispatcher.offer`. `getUtilization()` evaluates synchronously
  only in `NEW` or `STOPPED` and throws when no value is available.
- `LatestValueDispatcher` guarantees one active callback and at most one pending coalesced value.
  Slow listeners must never create a backlog; do not add queues there.
- Legacy `SystemSnapshotProvider` implementations are wrapped by
  `SystemSnapshotCompatibilityAdapter`. Linux handles cgroup v1, v2, hybrid, and bare-host modes
  through `LinuxPaths` and `CgroupV2Resources`; fixture tests inject paths rather than reading the
  host.
- Signals carry `SignalValidity` (`VALID`, `TRANSIENT_FAILURE`, `UNSUPPORTED`) and
  `SignalResolution`. Propagate unavailable and failed states instead of substituting zeros.
- Sampling and pressure code run on every tick. Avoid new allocation, streams, string formatting,
  and logging on those paths.

## Native build and loading

Native Zig and C++ sources are owned by this module; there is no separate native module.

- Build graph: `compileJava` writes JNI headers to `build/generated-jni/declarations`; `zigBuild`
  runs [build.zig](src/main/native/build.zig); `copyNativeResources` copies `bin/**` and
  `META-INF/euhedral/native-products.tsv` into the main classes directory; `classes` depends on it;
  `test` depends on `prepareNativeSmokeBundle`. Every test run therefore performs the full
  cross-build of all eight products.
- Prerequisites come from [mise.toml](../mise.toml): Zig exactly 0.16.0 (enforced by
  `build.zig`), a JDK 21 `java.home`, the macOS SDK at `SDKROOT` (with `usr` and `System` trees),
  and `RCODESIGN` reporting exactly `apple-codesign 0.29.0`. Tests also need `llvm-readobj` and
  `llvm-objdump`; see BUILD.md for discovery and the `LLVM_READOBJ`/`LLVM_OBJDUMP` overrides.
  Missing tools fail rather than skip. Report those failures as environment blockers, not source
  failures.
- `native-products.json` is the single source of truth for operating systems, architecture
  aliases, components, source roots, gate policies, products, resource paths, build and load order,
  and macOS signing identifiers. `build.zig` validates it strictly and generates the runtime catalog
  TSV. Change products in the manifest, never by editing generated TSV or `bin/` output.
- `NativeBuildPolicyTest` asserts the portable policy textually in `build.zig`: `ReleaseSafe`,
  stack protector and stack checks, frame pointers, async unwind tables, `sanitize_c = .trap`,
  RELRO, no lazy binding, `-z defs`, libc only, no libc++, and no `-O3`. Gate policies cap glibc
  symbols at `GLIBC_2.17`, require macOS 11.0 with only `libSystem.B.dylib`, and restrict Windows
  imports to `KERNEL32.dll` plus two UCRT API sets. A new native dependency or import is a
  portability change: update the manifest allowlist deliberately and pass the binary gate.
- `expected_jni_headers` in `build.zig` must list exactly the classes with `native` methods. Adding,
  removing, or renaming a native method also affects
  [native-contract-900d8c50.tsv](src/test/resources/compatibility/native-contract-900d8c50.tsv),
  checked by `NativeCompatibilityTest`; overloaded natives need long JNI names.
- Runtime loading: platform classes call `JNIClassLoader.load()` from static initializers, and the
  holder-class idiom loads once. The catalog selects candidates by OS alias, architecture alias, and
  load order (glibc before musl on Linux). Libraries are extracted under `java.io.tmpdir` or the
  absolute `-Dio.euhedral.native.extract.dir`, with owner-only permissions and PID owner markers
  for stale-directory handling. Do not weaken permission checks, symlink handling, or fail-loud
  diagnostics.
- `LinuxAffinity` and `WindowsAffinity` catch load failure and degrade to `UNSUPPORTED`.
  `MacosAffinity` lets it propagate, and `ThreadTools` catches the resulting `LinkageError`.
  Preserve the rule that a missing native library degrades affinity and topology to fallbacks
  rather than failing class initialization of `SystemInfo` or `ThreadTools`.
- Resource sampling has no equivalent fallback. `TopologyBootstrap.resources` returns `null` on an
  unsupported OS or initialization failure, leaving `SystemInfo.SNAPSHOTTER` null; the public
  `ResourceMonitor` constructors then throw `NullPointerException`. Do not describe monitoring as
  degrading gracefully unless the change adds and tests that behavior.
- Build products and caches are `build/generated-jni`, `build/generated-resources/native`,
  `build/native-smoke`, and the ignored `zig-cache/` and `zig-global-cache/` directories, which CI
  also caches. Never commit them, and do not delete them as incidental cleanup.

## Compatibility baselines

The `compatibility` test package pins behavior against branch point `900d8c50`:

- [api-900d8c50.tsv](src/test/resources/compatibility/api-900d8c50.tsv): `ApiCompatibilityTest`
  fails on any removed or changed exported member or any module-descriptor change, and asserts
  exactly ten exported packages. Additions are reported but pass. The report is written to
  `build/compatibility/compatibility-report.txt`.
- [native-contract-900d8c50.tsv](src/test/resources/compatibility/native-contract-900d8c50.tsv):
  native products and JNI declarations.
- [defect-ledger.tsv](src/test/resources/compatibility/defect-ledger.tsv): each recorded correction
  maps to an exact regression test, and `DefectLedgerTest` hard-codes the expected ID-to-owner
  mapping.

Treat these files as reviewed contracts. Do not edit a baseline to make a test pass. An intentional
break requires explicit user approval and a baseline update in the same change, called out in the
handoff.

## Validation

Use Mise from the repository root. The focused gate is:

```bash
mise exec -- gradle :euhedral-hardware-utils:test :euhedral-hardware-utils:spotlessCheck
```

Focused iteration examples:

```bash
mise exec -- gradle :euhedral-hardware-utils:test \
  --tests 'io.euhedral_execution.hardware_utils.internal.pressure.*' \
  --tests 'io.euhedral_execution.hardware_utils.internal.sampling.*'

mise exec -- gradle :euhedral-hardware-utils:test \
  --tests 'io.euhedral_execution.hardware_utils.compatibility.*'
```

`--tests` narrows execution but still runs `zigBuild` and native packaging.

For public API, JPMS, topology, affinity, executor, or monitor behavior changes, also exercise the
consumers:

```bash
mise exec -- gradle :euhedral-core:test :benchmarks:compileJava :benchmarks:cfd:compileJava
```

For native, packaging, or loader changes, keep
[hardware-utils-native.yaml](../.github/workflows/hardware-utils-native.yaml) consistent. It
cross-packages on Ubuntu, then load-smokes the artifact on glibc, musl (container), Windows, and
macOS runners through `NativeLoadSmokeMain` in `load-only` or `linux-get-cpu` mode. A local run
covers only the Linux host; report other platforms as unverified unless CI ran them.

### Current task caveats

- No test in this module is tagged `integration`. The `*IT` classes (`NativeBinaryInspectionIT`,
  `NativeLoadSmokeIT`, `NativePackagingIT`) run under the normal `test` task, so
  `:euhedral-hardware-utils:integrationTest` executes no test bodies. Do not cite it as evidence.
- `PressureEvaluatorTest`, `PressurePropertiesTest`, and `PressureSignalAvailabilityTest` contain
  only `assertTrue(true)`. `PressureCompositionTest` is the only behavioral pressure test. Add real
  coverage when changing pressure evaluation, projection, or signal-availability handling.
- `NativeLoadSmokeIT` and parts of `NativeLoaderTest` are `@EnabledOnOs(OS.LINUX)` and skip
  elsewhere. macOS and Windows affinity, resource, and topology tests use fakes and fixtures; they do
  not prove real platform behavior.
- Tests run with JUnit parallel execution from the shared conventions. `ResourceMonitorTest`,
  `PinnedThreadExecutorTest`, and `PinnedThreadExecutorCompatibilityTest` are `@Isolated`. A new
  test that touches `SystemInfo` initialization side effects, `ThreadTools`, the global executor
  registry, real affinity, or system properties needs equivalent isolation.
- Host topology varies. Tests that read real hardware must assert invariants rather than counts and
  must not assume SMT, multiple sockets, hybrid cores, or root access.
- Comments in `PinnedThreadExecutor` and `ThreadTools` mention historical delivery labels such as
  `P3-B`. They do not identify current tasks or documents.

For documentation-only edits to this file, validate links and `git diff --check`; do not run the
native build or test suites solely for the guide. For code changes, finish with the root handoff
checklist and report executed tests, skipped bodies, missing native prerequisites, and unverified
platforms.
