# Phase 7 macOS Platform Provider Root Conformance Audit

## Scope and disposition

Independent end-to-end audit of the macOS platform provider on branch
`hardware-utils-overhaul/phase-7-macos-audit`, created from the P7 root
`hardware-utils-overhaul/phase-7-macos` (root commit `16125ef`).

Parent artifacts audited against:

- P7 parent blueprint `docs/blueprints/hardware-utils/phase-7-macos-platform.md`
- P7-A triple: `docs/blueprints/hardware-utils/phase-7-macos-topology-model.md`, its completion
  record, and `docs/audits/hardware-utils/phase-7-macos-topology-model-conformance.md`
- P7-B triple: `docs/blueprints/hardware-utils/phase-7-macos-resource-provider.md`, its completion
  record, and `docs/audits/hardware-utils/phase-7-macos-resource-provider-conformance.md`
- P7-C triple: `docs/blueprints/hardware-utils/phase-7-macos-affinity-native.md`, its completion
  record, and `docs/audits/hardware-utils/phase-7-macos-affinity-native-conformance.md`

The audit traced the full pipeline against source, not against the child audits:
**sysctl topology discovery -> resource & thermal metrics -> locality affinity & Mach-O ABI**. Every
classification below was re-derived from the production code and the test tree, and each child-audit
claim was checked against the artifact it cites.

Source and test files inspected:

-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosSystemLayout.java`
-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosResources.java`
-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosAffinity.java`
-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosAffinityCalls.java`
-
`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/SystemSnapshotCompatibilityAdapter.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/ResourceMonitor.java`
- `euhedral-hardware-utils/src/main/native/macos/macos_system_layout.cpp`
- `euhedral-hardware-utils/src/main/native/macos/macos_resources.cpp`
- `euhedral-hardware-utils/src/main/native/macos/macos_affinity.cpp`
-
`euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/macos/MacosTopologyFixtureTest.java`
-
`euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/macos/MacosResourcesTest.java`
-
`euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/macos/MacosAffinityTest.java`
-
`euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/internal/sampling/ProviderContractTest.java`

### Disposition: closed out (2026-08-07). One material item was raised and resolved by developer-authorized blueprint amendment (fix 1); one audit-integrity defect corrected; the on-host smoke gate (B06 / requirement #12) is satisfied by the developer-confirmed passing hardware-utils CI workflow. All P7 conformance gates are met; the developer authorized closeout.

P7-A (topology) and P7-C (affinity, timer, native ABI) are independently confirmed
`satisfied`. The P7-B resource provider is `satisfied` for the CPU/IO/memory/timebase metrics that
reach the published pipeline.

**Thermal/low-power (resolved).** This audit initially raised the absence of `VALID`
thermal/low-power signals as a conflict between two frozen artifacts. `ProviderContractTest`
freezes the intended hookup for *every* platform: `CgroupV2Resources`, `WindowsResources`, and
`MacosResources` each implement only `SystemSnapshotProvider` and reach the sampling engine through
`SystemSnapshotCompatibilityAdapter.wrap(...)`. In that architecture the `SystemSnapshot`
DTO has no thermal/low-power field, and the adapter's `sampleSlow` returns an all-`UNSUPPORTED`
sample for every legacy provider — so `VALID` thermal/low-power slow signals are structurally
undeliverable for any legacy-wrapped provider (Linux and Windows included), not a macOS-specific
gap. The P7-B blueprint's demand that `MacosResources` implement `sampleSlow` returning `VALID`
thermal/low-power contradicted that contract; implementing it would make `wrap()` return the
provider as-is, bypass the `MACOS_LEGACY` path, and break `ProviderContractTest.testOSXProfile`.

The developer resolved this on 2026-08-07 (fix 1): **thermal and low-power are internal-only inputs
to the pressure calculation, not public values.** `VALID` surfacing of those signals is the job of a
future canonical macOS `DetailedSystemSnapshotProvider` (mirroring
`LinuxResourceProvider`), not of the legacy provider. The P7-B blueprint was amended accordingly (§1
amendment note, §2, §3, §4.4/§4.6, §8.1, §9 Approved Deviations), and the native probes remain in
place for that future provider. With the blueprint corrected to match the frozen architecture and
the P6-accepted Windows precedent, the macOS thermal/low-power portion of R13 and requirements #6/#7
are **`satisfied` (probes present and correct; legacy path correctly neutral; `VALID`
surfacing explicitly deferred and out of P7 scope)**.

**Audit-integrity defect (corrected).** The P7-B child conformance audit cited six tests that do not
exist and an "8/8 tests passed" result against a file containing two tests. This has been corrected
in `docs/audits/hardware-utils/phase-7-macos-resource-provider-conformance.md` (accurate test
inventory; criteria 4/5 reclassified to the internal-only/deferred disposition).

Per the workflow closeout contract and the developer instruction that "a material deviation returns
to the exact blueprint or implementation action," the one material item was returned to the P7-B
blueprint and resolved there (fix 1), and the audit-integrity defect was corrected in the child
audit. **The remaining closeout gates are external to the code:** developer merge authorization and
a disposition for the `unverified` on-host smoke (B06 / requirement #12). This audit does not itself
append the P7 closeout summary, remove the temporary P7 status block from
`AGENTS.md`, or merge — those steps are gated on explicit merge authorization.

## Defect ledger acceptance matrix

macOS-owning ledger items per the parent plan defect ledger: **T01, A04, R01, R03, R13, N02, B06.**

| ID  | Owning phase | Classification                  | Evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
|-----|--------------|---------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| T01 | P2, P7       | satisfied                       | `MacosSystemLayout` never initializes empty maps or dereferences a zero CPU/cache count. On `logicalcpu <= 0` or unreadable keys it falls back to `Runtime.getRuntime().availableProcessors()` and delegates to `TopologyBootstrap.normalize()`. Tested by `MacosTopologyFixtureTest.testMissingKeyConservativeFallback`.                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| A04 | P7           | satisfied                       | Honest locality semantics: `MacosAffinity.getCpu()` returns `-1` (UNSUPPORTED); affinity is a Mach locality-hint tag (ordinal `c` -> tag `c+1`, tag `0` releases) not hard pinning; `applyOrdinal` enforces `bits.cardinality()==1`. Safe timer: `setTimerResolution` rejects negative, clamps to `1L`, and the native `setThreadTickPolicy` is an idempotent no-op with no `THREAD_TIME_CONSTRAINT_POLICY`. Timebase math guarded (see R03/N02). Tested by `MacosAffinityTest` (4 tests).                                                                                                                                                                                                                                                                              |
| R01 | P4, P5-P7    | satisfied                       | Canonical units for surfaced signals: process CPU emitted as cumulative nanoseconds (`ri_user_time + ri_system_time`), disk I/O as cumulative bytes, memory as bytes. `SystemSnapshotCompatibilityAdapter` treats macOS `period()` as nanoseconds (`MACOS_LEGACY` branch).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| R03 | P7           | satisfied                       | Process CPU is emitted as a cumulative counter (not a boot-relative load mixed with a delta), and non-private working set uses `Math.max(0L, virtual - resident)`, matching the public working-set calculation and preventing underflow. Consistent with `getSnapshot()` field wiring.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| R13 | P4-P7        | satisfied (resolved 2026-08-07) | R13 requires "only supported, validity-tracked signals," including **thermal** and **low-power capacity**. The native probes (`getThermalStateNative` clamped 0-3, `isLowPowerModeNative`) exist and map correctly. Per developer decision (fix 1), thermal/low-power are **internal-only pressure inputs, not public values**; `VALID` surfacing is the job of a future canonical `DetailedSystemSnapshotProvider` (as `LinuxResourceProvider` does) and is deferred/out-of-P7-scope. The legacy `MacosResources` correctly surfaces them as `UNSUPPORTED`/neutral through the adapter — uniform with Linux/Windows and consistent with the amended P7-B blueprint. The CPU/quota/memory/I-O-stall portions of R13 are handled honestly as telemetry or `UNSUPPORTED`. |
| N02 | P7           | satisfied                       | Native ownership corrected: `macos_affinity.cpp` releases the JNI array with `JNI_ABORT`, reads the tag directly as `masks[0]` (no unsafe 64-bit mask shift — the residual bit-scan was corrected during the P7-C child audit), and rejects negative tags. `macos_resources.cpp` guards the Mach timebase denominator (`denom > 0`) with a 1:1 fallback. No leaked Mach buffers; no efficiency-core ordering assumption in the native layer.                                                                                                                                                                                                                                                                                                                            |
| B06 | P1, P5-P7    | satisfied                       | Binary inspection/deployment gates exist (`NativeBinaryGateTest`, `NativeSigningTest`, ABI floor/export checks; universal `x86_64`+`arm64`, macOS 11.0 floor, `libc++`/`libstdc++`-free). The "real smoke calls" clause requires execution on a macOS host, which is unavailable in this Linux environment; the developer confirmed the hardware-utils CI workflow (which exercises the macOS jobs) passed on 2026-08-07, satisfying the on-host smoke gate (developer-attested; not re-run in this environment).                                                                                                                                                                                                                                                       |

## Core platform requirement matrix

| #  | Requirement (end-to-end)                                                                                                  | Classification                     | Evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
|----|---------------------------------------------------------------------------------------------------------------------------|------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | sysctl topology discovery: type-safe parsing, Apple Silicon P/E vs Intel SMT, cache-domain BitSets, conservative fallback | satisfied                          | `MacosSystemLayout` + `Sysctl{Int,Long,String}` verified; `nperflevels>=2` -> E-cores `0..eCount-1` / P-cores `eCount..`, homogeneous fallback when `eCount+pCount != logicalcpus`; Intel `threadsPerCore = logicalcpu/physicalcpu`; L1D per-core, L2 by `cpusperl2`, L3 socket-wide when `>0`, L1i excluded. Tested by `MacosTopologyFixtureTest` (6 tests).                                                                                                                             |
| 2  | Process CPU (ns) + disk I/O (bytes) telemetry via `proc_pid_rusage` with `getrusage` fallback                             | satisfied                          | `macos_resources.cpp getProcessRusageNative` and `MacosResources.getSnapshot()`; `testProviderContractGetSnapshot` asserts `cpuUsage`/`diskIOBytes` propagation.                                                                                                                                                                                                                                                                                                                          |
| 3  | Telemetry/pressure isolation: no fabricated zero-stall pressure                                                           | satisfied                          | `getSnapshot()` sets per-CPU pressure to `0.0` as neutral; the `MACOS_LEGACY` fast path tags scheduler-stall/quota-throttle signals `UNSUPPORTED` rather than reporting a false zero stall.                                                                                                                                                                                                                                                                                               |
| 4  | Resident/working-set memory via `hw.memsize` + `task_info`, underflow-guarded                                             | satisfied                          | `getTaskMemoryNative` + `Math.max(0L, virtual - resident)`; `testProviderContractGetSnapshot` asserts `memoryLimit`/`memoryUsage`/`inactiveFileMemory`.                                                                                                                                                                                                                                                                                                                                   |
| 5  | Mach timebase conversion with zero-division protection                                                                    | satisfied                          | `ticksToNanos` guards `denom > 0`, 1:1 fallback + rate-limited warning; `testMachTimebaseZeroDivisionProtection` asserts both the fallback and the scaled path.                                                                                                                                                                                                                                                                                                                           |
| 6  | Thermal severity — internal-only pressure input (not a public signal)                                                     | satisfied (resolved 2026-08-07)    | Native `getThermalStateNative` maps states 0-3 -> `ThermalSeverity` correctly. Per fix 1, thermal is an internal-only pressure input; the legacy provider correctly surfaces it as `UNSUPPORTED`/neutral via the adapter, and `VALID` surfacing is deferred to a future canonical macOS `Detailed` provider. Blueprint amended (§4.4/§8.1). Not a public API value (`SampleStateEngine` consumes it internally; no public thermal getter exists).                                         |
| 7  | Low-power mode — internal-only pressure input (not a public signal)                                                       | satisfied (resolved 2026-08-07)    | Native `isLowPowerModeNative` exists and maps correctly. Same internal-only/deferred disposition as #6, per the amended blueprint.                                                                                                                                                                                                                                                                                                                                                        |
| 8  | Honest locality affinity: tag mapping, tag-0 release, single-locality enforcement, `getCpu()==-1`                         | satisfied                          | `MacosAffinity`/`MacosAffinityCalls`/`macos_affinity.cpp` verified; `MacosAffinityTest` (4 tests).                                                                                                                                                                                                                                                                                                                                                                                        |
| 9  | Safe idempotent timer resolution without realtime scheduling                                                              | satisfied                          | `setTimerResolution` validation + clamp; native `setThreadTickPolicy` no-op.                                                                                                                                                                                                                                                                                                                                                                                                              |
| 10 | Universal Mach-O (`x86_64`+`arm64`), macOS 11.0 floor, no C++ runtime, codesigned                                         | satisfied (build/gate)             | Zig universal build, `-fno-exceptions -fno-rtti -fvisibility=hidden`, `NativeSigningTest`/`NativeBinaryGateTest`. On-host execution: unverified (#12).                                                                                                                                                                                                                                                                                                                                    |
| 11 | `MacosResources` hooks into the sampling engine as intended                                                               | satisfied                          | Per `ProviderContractTest.testOSXProfile`, the intended hookup is a legacy `SystemSnapshotProvider` wrapped by `SystemSnapshotCompatibilityAdapter` (`MACOS_LEGACY`), identical to `WindowsResources`/`CgroupV2Resources`. `MacosResources` conforms exactly. The amended P7-B blueprint (§4.6) now matches this — the earlier §4.6 item 6 requirement to implement `DetailedSystemSnapshotProvider` (which would bypass the adapter and break `testOSXProfile`) was corrected per fix 1. |
| 12 | Runtime smoke on macOS 11 Intel and arm64 hosts                                                                           | satisfied (developer-confirmed CI) | No macOS host is available in this Linux environment, but the developer confirmed the hardware-utils GitHub CI workflow — which runs the macOS jobs — passed on 2026-08-07. This is developer-attested and was not re-run here; it satisfies the parent plan's real-macOS-smoke success criterion.                                                                                                                                                                                        |

## Detailed technical audit

### 1. Topology (P7-A) — satisfied

`MacosSystemLayout` reads public sysctl keys through the type-safe
`SysctlInt/SysctlLong/SysctlString` wrappers. Apple Silicon (`hw.nperflevels >= 2`)
classifies logical CPUs `0..eCount-1` as `EFFICIENCY` and `eCount..` as `PERFORMANCE`, requiring
`eCount + pCount == logicalcpus` or falling back to a homogeneous model. Intel (`nperflevels < 2`)
derives `threadsPerCore = logicalcpu / physicalcpu`. Cache domains: L1D per-core, L2 grouped by
`cpusperl2`, L3 socket-wide when `hw.l3cachesize > 0`, L1i excluded. Missing/zero keys fall back to
`availableProcessors()` +
`TopologyBootstrap.normalize()`. No empty-map initialization or zero dereference (closes T01 for
macOS). Confirmed against source and the six `MacosTopologyFixtureTest` cases.

### 2. Resource metrics (P7-B) — CPU/IO/memory/timebase satisfied; thermal/low-power resolved as internal-only (fix 1, 2026-08-07)

`macos_resources.cpp` implements every probe the blueprint names, and all are correct in isolation:
`getProcessRusageNative` (cumulative CPU ns + disk bytes, `getrusage` fallback),
`getTaskMemoryNative` (`hw.memsize`, `resident_size`, `virtual_size`), `getThermalStateNative`
(dlsym Objective-C, clamps 0-3), `isLowPowerModeNative`, and `getMachTimebaseNative`
(guards `denom > 0`).

The CPU/IO/memory/timebase requirements are delivered through `getSnapshot()` and the adapter's fast
path, and pressure isolation is honest (`UNSUPPORTED`, not false zeros). These are `satisfied`.

Thermal and low-power were raised by this audit and then resolved by the developer (fix 1,
2026-08-07): **they are internal-only inputs to the pressure calculation, not public values,**
and `VALID` surfacing belongs to a future canonical `Detailed` provider — not the legacy
`MacosResources`. The `ProviderContractTest` evidence (section 4) explains why the original
blueprint wording could not be met by the legacy provider, and the amended blueprint now matches the
architecture:

- `MacosResources` is declared `public final class MacosResources implements
  SystemSnapshotProvider` (line 18), with `getSnapshot()` (line 204) as its only sampling entry
  point. This is **exactly** the intended shape — `WindowsResources` and
  `CgroupV2Resources` are the same, and `ProviderContractTest.testOSXProfile` mocks
  `MacosResources` as a `SystemSnapshotProvider` and asserts the `MACOS_LEGACY` adapter path.
- `SystemSnapshot` (the DTO `getSnapshot()` returns) has **12 components and no thermal or low-power
  field**: `timeNs, totalCpus, quotaCpus, period, cpuUsage, cpuThrottle,
  effectiveCpus, pressurePerCpu, memoryLimit, memoryUsage, inactiveFileMemory, diskIOBytes`. There
  is no channel for a legacy provider to carry these signals.
- `ResourceMonitor` wraps the provider (`SystemSnapshotCompatibilityAdapter.wrap(provider)`, line
  97) and calls `provider.sampleSlow(pollStartNs)` at slow cadence (line 246). The adapter's
  `sampleSlow()` (line 238) "returns an all-UNSUPPORTED `SlowHardwareSample` at requestedAtNs
  **without invoking the wrapped delegate**," emitting
  `ThermalSignal(NOMINAL, t, UNSUPPORTED)` and `BooleanSignal(false, t, UNSUPPORTED)`
  (lines 328-329, 336-337) — for **every** legacy provider, by design.

Net effect: no legacy-wrapped provider on any platform can surface `VALID` slow signals; the
architecture defers all slow signals to the future canonical `DetailedSystemSnapshotProvider`. The
original P7-B blueprint §4.6 item 6 (implement `sampleFast`/`sampleSlow` in `MacosResources`)
and acceptance criteria 4/5 (`sampleSlow` returns `VALID` thermal/low-power) conflicted with this
frozen P4 sampling contract that `ProviderContractTest` enforces and that P6 Windows already
conformed to as `satisfied`. Implementing that wording literally — making `MacosResources` a
`DetailedSystemSnapshotProvider` — would make `wrap()` return it as-is (line ~107, "returns the
provider directly if it already implements DetailedSystemSnapshotProvider"), bypass the
`MACOS_LEGACY` mapping, and **break `testOSXProfile`**.

**Resolution (fix 1).** Because thermal and low-power are internal-only pressure inputs — consumed
by `SampleStateEngine` -> `internal.pressure`, with no public getter anywhere — the developer
authorized amending the P7-B blueprint so `MacosResources` stays a legacy `SystemSnapshotProvider`
(surfacing thermal/low-power as `UNSUPPORTED`/neutral through the adapter, uniform with
Linux/Windows), and `VALID` surfacing is deferred to a future canonical macOS `Detailed` provider
(mirroring `LinuxResourceProvider`, which already feeds `VALID` thermal into pressure). The native
probes are correct and remain in place for that provider. With the blueprint corrected, the macOS
thermal/low-power items are **`satisfied`** (probes present; legacy path correctly neutral; `VALID`
surfacing explicitly out of P7 scope).

### 3. Affinity, timer, native ABI (P7-C) — satisfied

`macos_affinity.cpp` reads the affinity tag directly (`rawTag = masks[0]`), releases with
`JNI_ABORT`, rejects negative tags, and calls `thread_policy_set(..., THREAD_AFFINITY_POLICY, ...)`.
`getCpu` returns `-1`. `setThreadTickPolicy` is an idempotent no-op (no realtime policy).
`MacosAffinityCalls.applyOrdinal` enforces single-locality cardinality; ordinal `c` -> tag `c+1`;
tag `0` clears. `setTimerResolution` rejects negatives and clamps to `1L`. This closes A04 and the
macOS portion of N02, and matches the P7-C child audit (including its documented mid-audit
correction of the residual bit-scan). Independently re-confirmed against source.

## Audit-integrity finding (P7-B child conformance) — corrected 2026-08-07

As originally filed, `docs/audits/hardware-utils/phase-7-macos-resource-provider-conformance.md`
classified all six P7-B criteria `satisfied` and cited, as evidence, the tests
`testProcessCpuTimeAccumulation`, `testDiskIoByteAccumulation`, `testTelemetryPressureIsolation`,
`testMemorySnapshotAndUnderflowProtection`, `testThermalSeverityStateMapping`, and
`testLowPowerModeSignalMapping`, reporting "SUCCESS (8/8 tests passed)."

The actual `MacosResourcesTest.java` contains **two** `@Test` methods:
`testMachTimebaseZeroDivisionProtection` and `testProviderContractGetSnapshot`. None of the six
cited tests exist, and no macOS test references `sampleSlow`, `ThermalSignal`, `sampleFast`, or
asserts thermal/low-power signal mapping (the `thermalState`/`isLowPowerMode` mock fields are set
but never asserted). The fast-path validity of macOS signals is instead exercised generically by
`ProviderContractTest.testOSXProfile`, which the original child audit did not cite.

**Correction applied (2026-08-07).** The P7-B child audit has been corrected: it now cites only
tests that exist (the accurate two-test `MacosResourcesTest` inventory plus
`ProviderContractTest.testOSXProfile`), records the accurate `MacosAffinityTest`=4 /
`MacosTopologyFixtureTest`=6 inventories, carries an explicit correction note retracting the six
fabricated names and the "8/8 passed" claim, and reclassifies criteria 4/5 to the internal-only /
deferred disposition (fix 1). This audit-integrity defect is therefore closed.

## Verification commands and test results

```bash
git rev-parse --short HEAD        # 16125ef (P7 root), audit branch hardware-utils-overhaul/phase-7-macos-audit

# Provider interface: SystemSnapshotProvider only, single getSnapshot() entry point
grep -n "class MacosResources\|implements\|getSnapshot\|sampleFast\|sampleSlow" \
  euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/macos/MacosResources.java
# -> line 18 implements SystemSnapshotProvider; line 204 getSnapshot(); no sample* methods

# Monitor wraps non-detailed provider and reads slow signals from the adapter
grep -n "wrap\|sampleSlow\|sampleFast" \
  euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/ResourceMonitor.java
# -> line 97 SystemSnapshotCompatibilityAdapter.wrap(provider); line 246 provider.sampleSlow(...)

# Adapter discards slow signals as UNSUPPORTED without calling the delegate
grep -n "sampleSlow\|MACOS_LEGACY\|UNSUPPORTED\|ThermalSignal\|BooleanSignal" \
  euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/SystemSnapshotCompatibilityAdapter.java
# -> line 238 sampleSlow returns all-UNSUPPORTED; lines 328-337 UNSUPPORTED thermal/low-power

# All three platform providers implement only SystemSnapshotProvider (uniform legacy hookup)
grep -n "implements" \
  .../windows/WindowsResources.java .../linux/CgroupV2Resources.java .../macos/MacosResources.java
# -> WindowsResources, CgroupV2Resources, MacosResources all implement SystemSnapshotProvider only

# SystemSnapshot DTO has no thermal/low-power field
grep -n "record SystemSnapshot" \
  euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/common/SystemUtilization.java
# -> 12 components: timeNs, totalCpus, quotaCpus, period, cpuUsage, cpuThrottle,
#    effectiveCpus, pressurePerCpu, memoryLimit, memoryUsage, inactiveFileMemory, diskIOBytes

# wrap() returns any Detailed provider AS-IS (making MacosResources Detailed would bypass the adapter)
grep -n "wrap\|instanceof DetailedSystemSnapshotProvider" \
  euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/SystemSnapshotCompatibilityAdapter.java

# Actual macOS test inventory
grep -c "@Test" euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/macos/MacosResourcesTest.java   # 2
grep -c "@Test" euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/macos/MacosAffinityTest.java    # 4
grep -c "@Test" euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/macos/MacosTopologyFixtureTest.java # 6
```

The unit suites that DO exist pass on Linux (per the child audits' recorded `gradle` runs). This
root audit did not re-run the build; its findings are static-source classifications, which is
sufficient to establish the conflict: `ProviderContractTest.testOSXProfile` requires
`MacosResources` to be a non-`Detailed` provider routed through the adapter, and the adapter's
`sampleSlow` cannot carry the thermal/low-power signals the P7-B blueprint demands.

### Provider hookup contract (`ProviderContractTest`)

`ProviderContractTest` is the authority on how providers reach the sampling engine, and it is
uniform across platforms:

| Test                   | Provider (mocked)                | Declared as              | Adapter profile    | Fast-path assertion                                                       |
|------------------------|----------------------------------|--------------------------|--------------------|---------------------------------------------------------------------------|
| `testLinuxProfile`     | `CgroupV2Resources`              | `SystemSnapshotProvider` | `LINUX_V2_LEGACY`  | period ×1000; reported ratio `UNSUPPORTED`                                |
| `testWindowsProfile`   | `WindowsResources`               | `SystemSnapshotProvider` | `WINDOWS_LEGACY`   | `productiveCpuNs`/`scopeQuotaThrottledNs` `UNSUPPORTED`                   |
| `testOSXProfile`       | `MacosResources`                 | `SystemSnapshotProvider` | `MACOS_LEGACY`     | period unchanged; `productiveCpuNs`/`scopeQuotaThrottledNs` `UNSUPPORTED` |
| `testCanonicalProfile` | generic `SystemSnapshotProvider` | `SystemSnapshotProvider` | `CANONICAL_PUBLIC` | reported ratio `VALID`                                                    |

macOS's realized shape matches `testOSXProfile` exactly and mirrors `testWindowsProfile`, which P6
accepted as fully `satisfied`. Any change that makes `MacosResources` a
`DetailedSystemSnapshotProvider` would break `testOSXProfile`.

## Environmental limits

- No macOS host is available in this environment. All macOS sysctl, `proc_pid_rusage`,
  `task_info`, `NSProcessInfo`, Mach thread-policy, and Mach-O load/codesign behaviors are exercised
  only through mock probes, fixtures, and JNI ABI contracts on Linux.
- The B06 "real smoke calls" clause and requirement #12 (macOS 11 Intel/arm64 runtime smoke)
  cannot be exercised from this Linux environment. The developer confirmed the hardware-utils GitHub
  CI workflow (which runs the macOS jobs) passed on 2026-08-07, satisfying that gate
  (developer-attested; not re-run here).

## Handoff and next steps

**P7 is review-ready.** The one material blueprint-vs-architecture item and the one audit-integrity
defect were returned to the exact P7-B artifacts and resolved there, per the developer instruction
that a material deviation returns to the exact blueprint or implementation action. Only external
closeout gates remain.

**Resolved (2026-08-07):**

1. **Thermal/low-power surfacing (R13 macOS portion; requirements #6, #7; P7-B blueprint §4.6 and
   acceptance criteria 4, 5).** Resolved via **fix 1** on developer authorization: thermal and
   low-power are internal-only inputs to the pressure calculation, not public values. The P7-B
   blueprint was amended to keep `MacosResources` a legacy `SystemSnapshotProvider` (surfacing
   thermal/low-power as `UNSUPPORTED`/neutral through the adapter, uniform with Linux/Windows) and
   to defer `VALID` surfacing to a future canonical macOS `DetailedSystemSnapshotProvider`
   (mirroring `LinuxResourceProvider`). The native probes remain in place for that future provider.
   This matches the frozen P4 contract (`ProviderContractTest.testOSXProfile`) and the P6 Windows
   precedent; no change to `MacosResources`, the shared `SystemSnapshot` DTO, or P4 was required.

2. **P7-B child audit integrity.** Corrected
   `docs/audits/hardware-utils/phase-7-macos-resource-provider-conformance.md`: it now cites only
   tests that exist, records accurate inventories, retracts the six fabricated names and the "8/8
   passed" claim, and reclassifies criteria 4/5 to the internal-only/deferred disposition.

3. **macOS on-host smoke (B06 / requirement #12).** Resolved: the developer confirmed the
   hardware-utils GitHub CI workflow (which runs the macOS jobs) passed on 2026-08-07. This is
   developer-attested and was not re-run in this Linux environment.

**Closeout performed (2026-08-07).** With all conformance gates met and explicit developer
authorization to close out, this P7 root action appends the P7 closeout summary to the parent plan
(`docs/plans/hardware-utils-platform-parity-overhaul.md`), removes the temporary P7 status block
from `AGENTS.md`, and records the resulting P7 root commit on
`hardware-utils-overhaul/phase-7-macos`. P8 is not created.

Satisfied and independently confirmed: P7-A topology (T01), P7-C affinity/timer/native ABI (A04,
N02), macOS resource CPU/IO/memory/timebase and telemetry isolation (R01, R03, and the non-thermal
portions of R13), and the static Mach-O ABI/codesign gates (B06 static portion).
