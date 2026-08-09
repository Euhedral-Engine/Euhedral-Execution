# Hardware Utils Platform Parity and Pressure Overhaul

## Plan status

- Phase: 5 - Linux platform blueprint
- Status: P5 parent blueprint complete, review and merge required before child branches
- Plan branch: `agent/hardware-utils-overhaul-plan` (created before the updated phase-branch rule)
- Branch point: `900d8c50` (`agent/phase7-cleanup-handoff`)
- Active P1 root: `hardware-utils-overhaul/phase-1-native-build` (completed)
- Active P1 blueprint branch: `hardware-utils-overhaul/phase-1-native-build-blueprint` (historical)
- Completed P2 root: `hardware-utils-overhaul/phase-2-topology-snapshot` at `e2495c5d`
- Completed P3 root: `hardware-utils-overhaul/phase-3-affinity-executor` at `748f34d5`
- Completed P4 root: `hardware-utils-overhaul/phase-4-pressure-monitor` at `487003ba`
- Active P5 root: `hardware-utils-overhaul/phase-5-linux`
- Active P5 parent blueprint branch:
  `hardware-utils-overhaul/phase-5-linux-blueprint`
- Date: 2026-08-06
- Planning model: `gpt-5.6-sol`
- Planning reasoning effort: `max`

This plan is the controlling artifact for the overhaul. Later blueprints may make
implementation-level choices within the boundaries below, but they must return to the developer
before changing a material requirement, compatibility boundary, runtime floor, or module scope.

## Objective

Overhaul `euhedral-hardware-utils` so that:

1. macOS has as much topology, resource-monitoring, affinity/locality, and lifecycle capability as
   public macOS APIs can honestly provide on Intel and Apple Silicon;
2. Linux and Windows defects are corrected rather than merely preserving their current behavior;
3. all platforms feed one well-defined, robust pressure calculation whose exposed values are
   finite doubles in the inclusive range `[0.0, 1.0]`;
4. relevant hardware contention and capacity-loss state contributes to pressure without treating
   productive utilization or throughput as pressure;
5. the default metric polling cadence remains 200 ms and is efficient, deterministic, and
   safe under poll overruns and slow listeners;
6. `euhedral-core`'s `ControlPlaneFragment` consumes the improved pressure safely without a public
   core API change or hot-loop regressions;
7. the JNI binaries remain compatible with the lowest practical runtime environments described
   below; and
8. the universal Zig build remains automatic while becoming manifest-driven, deterministic,
   parallel where independent work exists, and free of stale source-tree artifacts.

The public Java API and its effective role remain compatible. Dependents should not require
significant source changes. Corrected values, newly working platforms, safer lifecycle behavior,
and improved adaptive responses are intentional behavior changes.

## Settled developer requirements

### Compatibility

- Preserve the Java module name, the five currently exported packages, all existing public and
  protected types, constructors, fields, methods, nested types, descriptors, and public record
  component names/order.
- Preserve the `SystemInfo` static facade.
- Preserve canonical CPU-mask text formatting.
- Preserve the current `PinnedThreadExecutor` task model: each `execute` creates a fresh pinned
  thread, so submitted tasks may run concurrently. Repair races and lifecycle behavior without
  turning it into a serialized single-thread executor.
- Preserve the established core-zero management reservation. Correct the core-zero-only edge case
  without removing the policy.
- Additive APIs are permitted. In particular, add a new affinity capability type with
  `EXACT`, `LOCALITY_HINT`, and `UNSUPPORTED`; do not add these constants to an unrelated existing
  enum.
- Correct results that are currently invalid, dimensionally wrong, mutable after publication, or
  unavailable because a platform fails initialization.

The executable compatibility baseline is the tree at commit `900d8c50`. Intentional behavior
corrections must be documented in the blueprint and audit rather than hidden by weakening the
baseline.

### macOS semantics

- Use supported public APIs only.
- Linux and Windows may report exact affinity when the operating system actually guarantees it.
- A Mach affinity tag is a locality hint, not hard CPU pinning. macOS must report
  `LOCALITY_HINT`, apply tag `0` when releasing the hint, and never claim exact placement.
- Existing boolean `setAffinity` overloads return `true` on macOS only when the requested mask can
  be represented by one locality tag and that hint was successfully applied. The separate
  capability query remains `LOCALITY_HINT`; boolean success does not mean exact pinning.
- Euhedral-managed macOS worker threads must retain a stable logical ownership ID for routing and
  monitoring. Outside managed ownership, an unavailable physical/current CPU must remain
  explicitly unsupported or use a documented conservative fallback; it must not be fabricated.
- Do not use private APIs or realtime `THREAD_TIME_CONSTRAINT_POLICY` scheduling.
- Public mask-shaped affinity overloads have deterministic semantics for empty, one-CPU,
  same-group multi-CPU, cross-Windows-group, and multi-locality macOS masks. They fail rather than
  partially applying a request while reporting exact/successful coverage.

### Runtime floors

- Linux:
  - x86-64 and AArch64;
  - target a libc-neutral JNI library through direct stable syscalls where validation proves that
    safe;
  - otherwise retain separately validated glibc 2.17 and musl artifacts;
  - no unexpected `libstdc++` or compiler-runtime dependency;
  - P5 must derive and prove the lowest practical kernel floor per architecture from the required
    syscall/JDK surface rather than inheriting the build host's kernel; it must not select a floor
    newer than 3.10 without developer approval;
  - every newer cgroup, pressure, topology, frequency, and thermal feature is detected at runtime;
  - cgroup v1, v2, hybrid, and bare-host execution are supported without mutating controller
    state.
- macOS:
  - macOS 11 or newer;
  - Intel x86-64 and Apple Silicon AArch64.
- Windows:
  - Windows 10 and Windows Server 2016 or newer on x86-64;
  - Windows 11 on ARM64 for ARM64 runtime validation;
  - resolve newer processor-group APIs dynamically and retain older documented fallbacks.

If the libc-neutral Linux attempt fails a binary or runtime gate, the blueprint must record the
evidence and use the accepted glibc 2.17 plus musl fallback. It must not silently raise a floor.

### Core and training scope

- `euhedral-training` is entirely excluded.
- No later stage may edit, test, build, document, inspect, or migrate training code, data, identity,
  model weights, workflows, or the attached `ClosedLoopRunner.java` selection.
- Do not run a root reactor command that selects `euhedral-training`.
- Core production work targets `ControlPlaneFragment`.
- `ControlPlaneCache` is an existing pressure consumer and must be covered by compatibility tests.
  It is test-only in the approved scope. If P8 proves a production correction is necessary, stop
  and obtain separate developer approval before editing it.
- `FragmentActionPicker`'s input count, weight shape, and policy semantics are unchanged.

### Pressure

- Every exposed pressure or ratio that is contractually normalized must be a finite double in the
  inclusive range `[0.0, 1.0]`.
- `0.0` means no measured service-capacity loss or contention.
- `1.0` means effectively complete measured service-capacity loss.
- A more robust calculation is preferred over preserving the current numeric output.
- Productive CPU work and healthy I/O bytes per second remain telemetry; they are not pressure by
  themselves.
- Unsupported signals are neutral internally, but availability must be tracked so an unsupported
  signal is not confused with a successful zero reading.
- A transient sensor failure follows a bounded, explicit staleness policy rather than immediately
  masquerading as recovery.
- Rich platform signals stay behind internal types. Existing public record shapes and accessors
  carry the compatibility telemetry and final normalized pressure.

### Metric cadence

- `ResourceMonitor(TopologyMapper)` continues to default to `Duration.ofMillis(200)`, exactly
  `200_000_000` ns.
- Scheduled poll attempts start on a monotonic 200 ms grid anchored at the monitor start deadline.
- After each completed evaluation, one immutable snapshot is atomically published. A variable-cost
  poll is not required to finish or publish exactly on a grid boundary.
- Poll cost is accounted for once.
- When a poll overruns, missed deadlines are skipped. There is no catch-up burst and an overrun
  never increases the poll rate.
- The deadline recurrence selects the first future grid boundary after completion. For example, a
  poll that starts at `0` ms and finishes at `450` ms starts its next attempt at `600` ms, not at
  `450` ms and not in catch-up attempts for `200` and `400` ms.
- Counter deltas and smoothing use actual monotonic elapsed time, not an assumed sample count.
- Expensive sensors may run at slower, independently cached cadences, but they do not change the
  200 ms poll-attempt grid and internal validity includes the value age.
- Listener delivery is ordered, non-overlapping, bounded, and latest-value coalesced. At most one
  callback is active and at most one pending latest update is retained.
- Listener notification is best-effort and coalesced. A listener is not guaranteed to observe one
  callback every 200 ms or every published snapshot.

### Zig build

- The default build, with no development or host-only flag, continues to build and bundle every
  declared platform/architecture/runtime product.
- Add one checked-in manifest/configuration file that is the sole inventory for designated native
  source folders and target metadata.
- Runtime loader lookup metadata is generated from or directly consumes that manifest; output
  names, architecture/runtime variants, and lookup paths are not maintained in a second hardcoded
  product table.
- Adding or removing a designated source folder must not require editing build graph logic.
- All eligible native sources under designated folders are discovered automatically according to
  explicit recursive and extension rules, sorted deterministically, and compiled into the
  corresponding aggregate library.
- Independent target compilation and signing nodes may run in parallel.
- Signing remains part of the universal/release graph. The artifact copied into the jar must be the
  artifact that was signed and verified.
- Do not add development-only build modes. Do not over-engineer around the currently small compile
  time.
- Build output belongs under Gradle/Zig target or generated-resource directories, never under
  `src/main/resources/bin`.
- P1 must explicitly select optimization and native hardening/portability settings. Any disabled
  stack protector/check, unwind/frame-pointer behavior, compiler-runtime bundling, or framework
  link needs a measured ABI/compatibility reason rather than inheritance from the current script.

## Scope

### In scope

- `euhedral-hardware-utils` Java implementation and tests.
- Its Linux, Windows, and macOS native implementation.
- JNI declarations, loading, ABI checks, generated headers, runtime probing, and resource cleanup.
- Zig manifest, build graph, packaging, signing order, cache use, and Gradle integration.
- Topology discovery and validation.
- Affinity capability reporting, logical ownership, and executor lifecycle.
- Resource collection and canonical units.
- Snapshot immutability and equality/hash correctness.
- The 200 ms monitor scheduler, listener delivery, reset/staleness behavior, and pressure math.
- `euhedral-core` `ControlPlaneFragment` pressure integration and focused tests.
- Test-only `ControlPlaneCache` compatibility coverage.
- Hardware-specific and selected-module CI jobs.
- A narrowly scoped benchmark in `benchmarks` only if a blueprint makes a runtime performance or
  allocation claim that requires JMH.
- Hardware and core documentation directly required to describe capabilities, floors, and
  validation.

### Non-goals

- Any `euhedral-training` work.
- Changes to training hardware identity, resume behavior, candidates, weights, corpora, or
  `ClosedLoopRunner`.
- Changing public record component lists/order or removing exported APIs.
- Changing the 200 ms default.
- A development-only, host-only, or selectively packaged default native build.
- Private or undocumented macOS APIs.
- Pretending macOS affinity tags are physical CPU pinning.
- Realtime timer/scheduler policy on macOS.
- A new action-picker input, weight migration, or policy-training effort.
- Broad routing, worker-lifecycle, frame, Reactor, or Spring redesign.
- Removing the core-zero management policy.
- Converting `PinnedThreadExecutor` into a conventional single-worker executor.
- Treating throughput benchmarks as correctness tests.
- Broad cleanup unrelated to a known defect or approved blueprint.

## Module and toolchain constraints

### Authorized toolchain-policy revision

The developer authorized this documentation revision on 2026-08-01. It clarifies the existing
toolchain constraint without changing phase scope, acceptance criteria, or implementation design.

- `euhedral-hardware-utils` remains Java 17.
- `euhedral-core` remains Java 21.
- All Java commands, Gradle commands, and Gradle builds default to the exact versions pinned by
  `mise.toml`: Java 21, Gradle 9.8.1, Zig 0.16.0, and the configured Apple codesigning tool. Use
  `mise exec --` when available; a documented restricted-environment fallback may use only the
  corresponding pinned installed tools and must record the substituted invocation and limits.
- The build must not invoke `mise` from inside `build.zig`. Gradle/CI supplies explicit tool and SDK
  inputs.
- JNI calls validate nulls, lengths, ranges, and output capacity before native writes.
- Native initialization is thread-safe and repeatable.
- Native buffers, Mach allocations, file descriptors/channels, Windows handles, temporary files,
  threads, and shutdown hooks have explicit ownership and cleanup.
- `ControlPlaneFragment.cycle()` and its pressure read remain allocation-free, lock-free,
  formatting-free, logging-free, and I/O-free.
- VarHandle modes may change only with a documented happens-before argument.
- Published snapshots defensively own all arrays and `BitSet` values.
- Fixture parsing and build discovery are deterministic.
- Missing optional sensors degrade capability; malformed required topology or ABI input fails with
  an actionable diagnostic.

Zig 0.16.0 is pinned, and Zig's build-system API (`build.zig`, `std.Build`, module/step wiring) has
changed materially across recent releases. Whichever model executes P1 (blueprint or
implementation), it should not rely on memorized Zig syntax from training data - training data for
any current model is likely to contain a mix of pre-0.16 API shapes that will silently fail to
compile or, worse, compile with different semantics than intended. Before writing or reviewing any
`build.zig` manifest logic, the agent should pull current Zig 0.16 documentation/source (or run
`zig build --help` / inspect the pinned toolchain directly in the environment) to confirm the
actual API surface, rather than trust pattern-matched recall. This applies equally to the
OpenAI and Anthropic options above - it's a model-agnostic risk, not one the vendor choice fixes.

## Current-state findings

The audits found that only Linux glibc x86-64/AArch64 is effectively usable today. Linux musl
fallback, Windows loading, and macOS initialization have confirmed blockers. The current packaged
resources also contain stale per-source libraries in addition to the intended aggregate JNI
libraries.

The architectural problem is not just missing macOS code. Platform providers currently disagree
on whether counters are cumulative, deltas, durations, or ratios; `ResourceMonitor` then applies
one set of assumptions to all of them. Pressure, topology, and lifecycle need common internal
contracts before platform parity can be reliable.

### Known-defect ledger

Every audit classifies each applicable whole item or phase-owned portion as exactly `satisfied`,
`deviated`, `unverified`, or `ambiguous`. A `deviated` result needs explicit developer approval;
`unverified` and `ambiguous` are not passes. Shared items stay carried to their named later phases
until P8 closes the whole ID. A known item may not disappear without a regression test or written
disposition. Newly found local defects may be fixed inside the approved blueprint; a newly found
architectural choice returns to blueprint.

| ID | Owning phase | Known defect and required disposition |
| --- | --- | --- |
| B01 | P1 | Native compilation writes into source resources, allowing stale binaries and headers into jars. Stage only generated resources and prove exact clean/rebuild contents. |
| B02 | P1 | Build targets and folders are hardcoded; discovery is shallow, unsorted, and silently skips failures. Replace with one validated folder manifest and deterministic discovery that fails loudly. |
| B03 | P1 | macOS signing is ordered after install and signs the emitted cache file, not necessarily the packaged copy. Sign and verify the staged artifact before its install/package edge completes. |
| B04 | P1 | Build and deploy CI copy Linux `jni_md.h` into Darwin and Win32 include folders. Replace both copies with platform-correct generated declarations and ABI headers. |
| B05 | P1 | `JNIClassLoader` unconditionally sets POSIX permissions, misses `LinkageError` fallback, maps unknown architectures to x64, and has weak temp-file/noexec-filesystem diagnostics and cleanup. Correct all loader paths without changing its public trigger; provide a safe configurable/fallback extraction location or an actionable noexec diagnosis. |
| B06 | P1, P5-P7 | Native binaries lack enforceable architecture, export, import, runtime-floor, and deployment-target gates. Add binary inspection and real smoke calls. |
| B07 | P1 | The build hardcodes `ReleaseFast` plus `-O3`, disables several hardening/debuggability features, bundles compiler runtime despite low-dependency goals, scans SDK paths blindly, and links an apparently unused framework. Select and justify optimization, safety, runtime, SDK, and framework settings explicitly. |
| T01 | P2, P7 | macOS initializes empty topology maps and dereferences CPU/cache zero, causing class initialization failure. Common fallback and the final macOS provider must both be safe. |
| T02 | P2, P5 | Linux treats local `core_id` as globally unique, assumes dense/online CPU IDs, depends on filesystem order, and mishandles missing cache data. Normalize and validate deterministic global identities. |
| T03 | P2, P6 | Windows topology parsing uses incorrect offsets, lacks bounds, drops mask bit 63, mishandles group IDs, and cannot represent multiple groups reliably. Use bounded fixture-driven parsing and a bijective logical-ID mapping. |
| T04 | P2 | `TopologyMapper` aliases caller masks, publishes without a clear memory boundary, drops concurrent updates, and has inconsistent socket version behavior. Define ownership, coalescing, and publication semantics. |
| T05 | P2, P4 | Snapshot arrays/bitsets are mutable after publication; `CoreSnapshot.equals` disagrees with record hash behavior; `SocketSnapshot` field values are populated positionally with wrong meanings. Correct values and deep immutability while preserving record shapes. |
| T06 | P2 | Core-zero reservation can produce an empty topology after intersecting the allowed set. Preserve the reservation when alternatives exist and correctly fall back when core zero is the only allowed core. |
| A01 | P3 | `ThreadTools` base-mask probing is destructive, off by one for sparse IDs, fails to restore the original mask, and can dereference an unsupported pinner. Make probing non-destructive and capability-aware. |
| A02 | P3 | `PinnedThreadExecutor` has singleton acquisition, execute/shutdown, identity-removal, cleaner, shutdown-hook, termination, and interruption races. Repair its state machine while preserving concurrent fresh-thread execution. |
| A03 | P6 | Windows affinity overwrites multi-group success, uses group-relative current CPU values, and has unsafe array/initialization behavior. Return stable Euhedral logical ownership and validate every native buffer. |
| A04 | P7 | macOS reports an unavailable current CPU, treats a locality tag as hard pinning, releases incorrectly, and uses dangerous realtime timer policy with wrong timebase math. Provide honest locality semantics and a safe timer no-op/unsupported path. |
| R01 | P4, P5-P7 | Platform pressure/counter inputs mix cumulative values, interval deltas, durations, ratios, ns, and microseconds. Define canonical units and adapt every provider. |
| R02 | P4, P5 | Linux unlimited quota divides CPU cardinality by a 100000 period; pressure is scaled twice; stale PSI can survive a zero-stall interval. Correct quota, unit, and reset semantics. |
| R03 | P4, P7 | macOS system load is cumulative since boot while process CPU is emitted as a delta, and inactive-memory semantics do not match the public working-set calculation. Emit canonical cumulative counters and correct memory semantics. |
| R04 | P4, P6 | Windows cycle counts are divided by nanosecond time, job quota fraction is treated as CPU count, primary-group masks omit processors, and private working-set subtraction can underflow. Correct units and fallbacks. |
| R05 | P4 | Per-CPU memory usage is dimensionless but labeled bytes, memory divisions can yield NaN/Infinity, and memory pressure is nearly ineffective. Restore dimensional correctness and zero-limit behavior. |
| R06 | P4, P5 | Adaptive disk throughput is called pressure, causing healthy I/O to throttle, while Linux device filtering selects loop devices and excludes ordinary devices. Keep bytes/sec as telemetry and use contention/stall evidence for pressure. |
| R07 | P4 | Per-CPU throttle uses the prior quota and multiplies throttle by pressure; total pressure omits important CPU signals and contains a tautological throttle expression. Define independent normalized domain signals and composition. |
| R08 | P4 | EWMA coefficients assume exactly one 200 ms sample and poll timing subtracts work twice. Use actual elapsed-time constants and fixed-rate deadlines without catch-up. |
| R09 | P4 | Listener callbacks use common-pool futures, can backlog, overlap, arrive out of order, spin, deadlock when a callback calls `addListener`, remain locked after `Error`, and run after close. Use bounded ordered latest-value delivery, permit safe reentrant registration, and catch `Throwable` at the isolation boundary. |
| R10 | P4 | Constructor/start double-prime samples, null providers race, stopped reads poll concurrently, timestamps are insufficiently validated, and close can lose or self-join the polling thread. Define a complete idempotent lifecycle. |
| R11 | P5 | Linux file channels leak, missing paths can log every 200 ms, and a single bounded read may truncate proc/cgroup data. Close resources and use bounded complete reads with rate-limited diagnostics. |
| R12 | P5 | Linux cgroup discovery can write a parent `cgroup.subtree_control`, changes scope during fallback, and does not support cgroup v1. Make all discovery read-only and scope-preserving across v1/v2/hybrid/bare host. |
| R13 | P4-P7 | Pressure omits reliable scheduler, quota, memory reclaim/headroom, I/O stall, steal, thermal, frequency, power, and low-power capacity signals. Add only supported, validity-tracked signals at appropriate cadences. |
| R14 | P4, P5 | Linux cgroup PSI is aggregate but is apportioned to CPUs using unrelated host jiffy activity, fabricating per-CPU contention when host and cgroup scope differ. Define honest global-to-effective-CPU propagation or neutral per-CPU attribution when evidence is unavailable; prohibit host-activity apportionment and fixture the scope mismatch. |
| N01 | P6 | Windows native code uses unchecked array lengths, VLAs, racy initialization, inconsistent timer JNI symbol owners, and newer APIs without robust fallback. Correct ABI and initialization before resource parity is accepted. |
| N02 | P7 | macOS native code leaks Mach buffers, assumes efficiency-core ordering, uses unsafe 64-bit mask shifts, and has incomplete resource/timebase cleanup. Correct ownership and derive conservative topology. |
| C01 | P8 | `ControlPlaneFragment` assumes a non-null, dense current snapshot, reads raw pressure in its hot path, and applies unexplained P/E attenuation. Freeze a safe monotonic response contract and cache a validated primitive cap. |
| C02 | P8 | `ControlPlaneCache` consumes the same pressure with per-update hysteresis and assumes valid dense input. Keep it test-only: reject malformed/older input in the fragment before delegation, validate the combined response, and return for separate developer approval if cache production work is necessary. |

## Target architecture

### Data path

```text
documented OS APIs / procfs / sysfs / cgroups / JNI
                    |
                    v
        internal immutable raw hardware sample
        - canonical units
        - cumulative counters where deltas are needed
        - per-signal validity and monotonic timestamp
        - stable Euhedral logical CPU ownership
                    |
                    v
          common delta and staleness engine
        - reset/regression/wrap handling
        - actual elapsed time
        - independently cached slow sensors
                    |
                    v
            normalized pressure domains
        - CPU contention/throttle/capacity loss
        - memory headroom/reclaim/stall
        - I/O stall/latency/queue pressure
        - reliable thermal/frequency/power loss
                    |
                    v
       immutable public compatibility snapshots
       CpuSnapshot.pressure() in [0.0, 1.0]
                    |
                    +--> TopologyMapper membership only
                    |
                    v
       ControlPlaneFragment response policy
       bounded primitive adaptive batch cap
```

Pressure must not change effective topology membership. Topology follows available/allowed CPUs;
pressure changes work limits.

### Internal sampling boundary

The existing public `SystemSnapshotProvider` and public `SystemUtilization` record shapes remain.
Built-in providers gain or delegate to an unexported detailed-sampler SPI that returns immutable
internal samples with richer signals and validity. A compatibility adapter maps the public provider
contract into the common engine for existing tests and internal callers.

The public compatibility fields have these fixed semantic roles:

- `SystemSnapshot.pressurePerCpu` is the provider's canonical per-logical-CPU scheduler/OS-stall
  ratio for the sample interval.
- `HardwareUtilization.perQuotaCpuThrottleRatio` is the normalized smoothed quota-throttle ratio.
- `HardwareUtilization.perQuotaCpuPressure` is the final smoothed per-logical-CPU composite
  pressure.
- `CpuSnapshot.stallRatio` and `CpuSnapshot.throttleRatio` carry the corresponding normalized
  compatibility-domain ratios.
- `CpuSnapshot.pressure` carries the final composite for that logical CPU.
- `HardwareUtilization.diskIOPressure` is I/O-domain contention only, never throughput divided by
  a peak.
- `HardwareUtilization.pressure()` returns the maximum final composite across effective CPUs. A
  valid sample with no effective CPU represents complete capacity loss and returns `1.0`; the
  pre-first-sample state is handled internally and does not synthesize a public utilization.

The phase-4 blueprint must settle exact internal names, units, validity states, age policy, and
adapter behavior within those roles. Rich memory, thermal, power, and other signals may be folded
into the final composite without exposing them individually. No rich signal requires a public
record component, and the design must not use timestamp-keyed, thread-local, static-global, or
identity-keyed sidecars to recover information after public snapshot construction.

Every populated `CpuSnapshot` derived from one `HardwareUtilization` has
`lastUsageNs == HardwareUtilization.timestampNs()`. `SocketSnapshot.lastUsageNs` carries that same
publication timestamp. This is the timestamp used by downstream acceptance logic; wall-clock
arrival order is not a substitute.

### Pressure semantics

Candidate inputs, when supported and reliable, are:

- CPU: scheduler/PSI wait, quota throttle, steal or external contention, run-queue evidence,
  frequency/capacity loss, and thermal/power limitation.
- Memory: working-set headroom, `memory.high`/equivalent pressure, reclaim, swap/page activity, and
  memory PSI/VM pressure.
- I/O: I/O PSI, wait/latency, queue or saturation evidence. Bytes per second remains telemetry.
- System state: thermal pressure and low-power mode where public APIs expose them.

The phase-4 blueprint exclusively owns per-signal age, transient-failure retention,
expiry-to-unsupported, normalization curves, measurement smoothing constants, and
correlated-signal composition. Core does not apply a second sensor TTL or reinterpret sensor age.
Requirements:

- every individual normalized signal is finite and clamped;
- increasing one pressure signal while all else is fixed cannot lower pressure;
- unsupported inputs contribute neutrally;
- correlated indicators within one domain are not double counted;
- independent bottleneck domains normally compose by `max`, unless the blueprint demonstrates a
  better bounded formula;
- productive CPU utilization alone does not raise pressure;
- high healthy I/O throughput remains low pressure;
- low-throughput sustained I/O stall can be high pressure; and
- wall-clock attack/release constants reproduce accepted 200 ms behavior but remain stable across
  delayed samples.
- the first cumulative sample establishes baselines and cannot synthesize CPU, throttle, disk-rate,
  or stall pressure from since-boot counters; and
- a counter reset/regression rebases only the affected counter without emitting a pressure spike.

### Core response boundary

`ControlPlaneFragment` continues to consume only `CpuSnapshot.pressure()`. It does not learn about
PSI, cgroups, processor groups, Mach APIs, thermal enums, or platform-specific counters.

The phase-8 blueprint must freeze a deterministic monotonic response curve with these endpoints:

```text
eligibleMax = max(1, min(maxBatchSize, frameQuota))
eligibleMin = min(2, eligibleMax)
pressure 0.0 -> eligibleMax
pressure 1.0 -> eligibleMin
```

The curve may remove the current P/E attenuation because normalized lost capacity should have one
meaning across core types. If retained, the blueprint must justify it as a core policy and still
meet the endpoints and monotonicity requirements. The blueprint must specify interpolation,
rounding, and golden values, including `maxBatchSize == 1`. This response correction and its
attenuation disposition are an intentional effective-behavior change that must appear in the
compatibility allowlist and release notes.

Recommended implementation boundary:

- sanitize and translate pressure when `update(CoreSnapshot)` receives a published snapshot;
- cache a primitive adaptive batch cap for the owner loop;
- initialize the cap to the no-pressure limit;
- reject duplicate/regressing publication timestamps, without inventing a second staleness policy;
- retain the last finite accepted value for malformed input, with `0.0` before the first valid
  sample;
- tolerate null, short, sparse, and null-entry arrays;
- linearize timestamp acceptance, primitive-cap publication, and `super.update(snapshot)` as one
  ordered operation even if multiple asynchronous writers overlap;
- use an explicit VarHandle publication argument; and
- perform only a weakly ordered primitive read plus final bound enforcement in the hot path.

P4 supplies the only measurement smoothing consumed by `ControlPlaneFragment`; P8 maps that
already-smoothed composite directly unless its blueprint proves a separate control-policy filter
is required. A single accepted snapshot feeds both fragment batch and cache policy from the same
sanitized composite. A rejected older or malformed snapshot updates neither consumer.

`ControlPlaneCache` remains a consumer because `ControlPlaneFragment.update` delegates to it. The
phase-8 blueprint must validate the combined batch/cache response without editing cache production
code. `ControlPlaneFragment` must reject malformed or older input before delegation. The existing
cache update coefficients map to:

```text
attack:   alpha(0.2 s) = 0.20 -> tau = -0.2 / ln(0.8)  ~= 0.8963 s
recovery: alpha(0.2 s) = 0.02 -> tau = -0.2 / ln(0.98) ~= 9.8997 s
alpha(dt) = -expm1(-dt / tau)
```

The P8 blueprint records this elapsed-time analysis and tests the established response at the
default cadence. It does not add time normalization in the approved scope. If missed periods or
direct malformed callers prove a material cache correctness problem, the blueprint stops and asks
the developer to authorize a separately bounded cache correction. Cache policy hysteresis is not
a second interpretation of hardware pressure.

## Platform capability targets

| Capability | Linux | Windows | macOS |
| --- | --- | --- | --- |
| Logical CPU topology | Exact from sysfs/proc with validated fallback | Exact processor-group mapping from documented APIs | Deterministic public-sysctl model with conservative fallback |
| Core/socket/cache model | Exact where kernel exports it | Exact where GLPIEx exports it | Best public representation; synthetic stable siblings where exact mapping is unavailable |
| P/E classification | Kernel topology/frequency evidence when reliable | Efficiency class when exported | `hw.perflevel*` on Apple Silicon; conservative homogeneous fallback |
| Effective CPU/quota | cgroup v1/v2/cpuset/bare host | process/job/group restrictions | process-visible logical CPUs; no cgroup equivalent |
| CPU contention | PSI/scheduler/quota/steal | documented system/job/capacity evidence | Unsupported/neutral unless a documented public wait or capacity-loss signal is proven; host/process CPU counters are telemetry |
| Memory pressure | cgroup/proc/PSI/reclaim | documented memory status/performance APIs | host/task VM headroom plus public pageout/reclaim evidence; thermal state stays in capacity |
| I/O pressure | PSI/wait; bytes/sec telemetry | documented I/O/capacity evidence; bytes/sec telemetry | Unsupported/neutral unless a documented public stall/latency signal is proven; process I/O is telemetry |
| Thermal/power | feature-detected sysfs at slower cadence | documented power/frequency APIs when available | Runtime-available `NSProcessInfo` thermal and low-power state, weak-linked/guarded to preserve macOS 11 |
| Affinity capability | `EXACT` | `EXACT` when group API succeeds | `LOCALITY_HINT` |
| Current CPU | Kernel CPU ID | Stable mapped group/processor ID | Managed logical owner; physical CPU unsupported |
| Timer adjustment | Safe existing documented behavior | Safe documented behavior | Idempotent no-op/unsupported; no realtime policy |

macOS parity means a usable and truthful implementation, not invented information. Missing public
topology relationships or hard affinity remain explicitly limited.

## Affected components

### Hardware Java

- `euhedral-hardware-utils/src/main/java/module-info.java`
- `io.euhedral_execution.hardware_utils.SystemInfo`
- `io.euhedral_execution.hardware_utils.ResourceMonitor`
- `io.euhedral_execution.hardware_utils.TopologyMapper`
- `io.euhedral_execution.hardware_utils.ThreadTools`
- `io.euhedral_execution.hardware_utils.PinnedThreadExecutor`
- `io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider`
- `io.euhedral_execution.hardware_utils.common.SystemUtilization`
- `io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet`
- `io.euhedral_execution.hardware_utils.common.UnmodifiableDoubleArray`
- `io.euhedral_execution.hardware_utils.internal.JNIClassLoader`
- new unexported topology, raw-sample, pressure, clock/scheduler, validity, and platform-adapter
  types selected by blueprints
- Linux, Windows, and macOS layout/resource/affinity classes

### Native/build/package

- `euhedral-hardware-utils/pom.xml`
- `euhedral-hardware-utils/src/main/resources/build.zig`, or its blueprint-approved relocated
  equivalent
- a new checked-in native folder manifest/configuration
- designated Linux, Windows, and macOS native source folders
- generated JNI declaration/header flow
- generated resource staging under `target`
- stale `build.sh` disposition
- exact jar resource inventory

Native binaries already present under `src/main/resources/bin` are build artifacts. They are not
tracked by Git and are user-owned even though ignored. P1 must fingerprint and preserve them; it
may not delete, move, rewrite, or clean them. P1 instead relocates only tracked native inputs,
deletes only the tracked obsolete `build.sh`, allowlists source resources, and stages generated
products under `target`. Builds must never create, delete, or modify the ignored source binary or
source-local Zig cache paths.

### Tests and fixtures

- existing hardware unit tests
- new topology, provider, native-boundary, lifecycle, fake-clock, pressure-property, packaging, and
  fixture tests
- Linux proc/sysfs/cgroup fixtures
- Windows GLPIEx/processor-group binary fixtures
- macOS sysctl/performance-level fixtures
- focused core tests for `ControlPlaneFragment` and `ControlPlaneCache`
- optional non-training JMH coverage only when required by an explicit performance claim

### Core

- `euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java`
- focused `ControlPlaneFragment` tests
- `ControlPlaneCache` tests only
- a minimal lattice monitor-to-fragment integration test

### Read-only downstream compatibility inventory

These non-training consumers are inspection/compile/test inputs, not additional production-edit
scope:

- core configuration/control: `LatticeConfig`, `ControlPlaneLattice`, `ControlPlaneShard`,
  `ControlPlaneCache`, `GlobalState`, and `WorkRequester`;
- core flow/lifecycle: `LatticeEdge`, `LatticeVertex`, `UpstreamQueue`, `AbstractFrame`,
  `AbstractExecutor`, `CloneableObject`, `BaseCloneableObject`, and `FrameFactory`;
- their focused existing tests;
- non-training callers under `benchmarks`, including core contention/scale/latency, frame, and
  queue benchmarks; and
- Reactor and Spring startup paths that depend transitively on the default
  `ControlPlaneLattice`.

P0/P2/P3 may inspect these paths to establish compatibility. They may not edit them unless a later
approved prompt names the file, and no training consumer is part of this inventory.

### CI and documentation

- native setup portions of `.github/workflows/build.yaml` and `.github/workflows/deploy.yaml` only
  where required to remove invalid JNI header preparation or supply the P1-settled explicit SDK,
  signer, and release credential-file paths; their pre-existing Gradle commands are outside this
  initiative, remain unchanged, and never count as task validation evidence
- a hardware-specific cross-platform workflow with explicit selected-module jobs; no new or
  modified task command may select training
- no root POM/plugin change whose behavior is inherited by `euhedral-training`
- `README.md` platform-support claims and `AGENTS.md` native build/resource instructions when P1
  makes them stale
- capability/runtime/build documentation selected by the blueprints
- this plan, phase blueprints, completion records, and conformance audits

## Phases and dependency order

The work is intentionally linear. Platform phases touch shared adapters and manifest metadata, so
they start only after the common contracts and the preceding audit are complete.

```text
P0 compatibility and deterministic baseline
  -> P1 universal Zig build, JNI ABI, loader, and packaging
  -> P2 validated topology and immutable snapshot foundation
  -> P3 affinity capability and executor lifecycle
  -> P4 200 ms sampling engine and normalized pressure
  -> P5 Linux parity and portability
  -> P6 Windows parity
  -> P7 macOS parity
  -> P8 ControlPlaneFragment integration and release conformance
```

Each unsplit phase has a blueprint, implementation, conformance check, and manual-review/audit path.
A phase
whose sizing gate splits work uses the child and root-integration sequence recorded in its artifact
index. A phase cannot hand off with a material deviation.

## Success criteria

### Compatibility

- An automated comparison against `900d8c50` proves unchanged:
  - the complete module descriptor, including name, `requires`, `exports`, `opens`, `uses`, and
    `provides`;
  - all five exported packages;
  - public/protected types, hierarchy/interfaces, modifiers, generic signatures, fields, methods,
    constructors, checked exceptions, and descriptors;
  - public compile-time constant names/types/values and nested types;
  - public record component names/order;
  - existing static entry points;
  - CPU-mask text format; and
  - intended aggregate native resource paths/library names and JNI entry names.
- Additive public types/members are allowed; removal or modification of an existing surface is not.
- Every intentional behavior correction is an exact allowlist record containing its defect-ledger
  ID, fully qualified member or resource, old behavior, new invariant, and regression-test ID.
  Broad categorical exceptions fail compatibility. Expected records include deep snapshot
  immutability, equality/hash consistency, corrected topology/resource units and values, ordered
  lifecycle/listener behavior, newly working native loading/platforms, affinity capability
  truthfulness, normalized pressure changes, the phase-8 monotonic fragment curve/P/E attenuation
  disposition, and one-time removal of stale per-source native resources while preserving intended
  aggregate lookup compatibility.
- Any baseline delta that matches neither an additive API nor one exact allowlist record fails.
- Existing source consumers compile without significant changes.
- `PinnedThreadExecutor` still permits concurrent fresh-thread tasks.
- Core-zero reservation remains observable.

### Topology and ownership

- Every effective logical CPU maps to one non-null `CpuInfo`, `CoreInfo`, `SocketInfo`, and
  `CpuCacheLayout`.
- Every active public snapshot array entry needed by a mapped CPU is non-null.
- IDs used for public array indexing are deterministic and valid; sparse host CPU IDs cannot cause
  out-of-bounds access.
- Linux core identity is globally unique across sockets/dies while OS CPU IDs remain usable for
  affinity.
- Windows `(group, processor)` values map bijectively to stable Euhedral logical IDs, including
  group boundaries and bit 63.
- macOS Intel and Apple Silicon initialize successfully with deterministic logical ownership and
  conservative cache/core fallbacks.
- Topology publication is immutable and version changes occur only for membership changes.
- Pressure-only updates do not trigger remapping.

### Monitoring and pressure

- Default monitor construction uses exactly 200 ms.
- Fake-clock tests prove poll starts at `0, 200, 400, ...` ms when poll cost is below the period,
  one immutable publication per completed evaluation, and listener delivery independent of that
  count.
- Poll cost is subtracted once; an attempt starting at `0` and completing at `450` starts next at
  `600`, with no attempts at `200`, `400`, or `450`.
- Actual elapsed time controls deltas and smoothing.
- A first cumulative sample establishes baselines and emits no since-boot CPU/throttle/disk/stall
  spike; a reset/regression rebases the affected counter without a spike.
- Duplicate/regressing timestamps, counter reset/wrap, invalid limits, stale data, dynamic quota/
  cpuset changes, stop/start/close, and concurrent reads are deterministic.
- Null, zero, negative, overflow/non-representable, and impractically small public `Duration`
  sample periods have blueprint-settled fail-fast behavior and cannot create a busy loop.
- Slow listeners cannot overlap, reorder timestamps, grow an unbounded queue, block sampling, stay
  wedged after `Throwable`, or run after close. A callback may call `addListener` without spinning,
  deadlocking, or corrupting the current iteration.
- Reflection-backed parameterized coverage maintains an exhaustive list of every ratio-valued
  public accessor. Each is finite and in `[0.0, 1.0]`, including:
  - `SystemSnapshot.pressurePerCpu` entries;
  - `HardwareUtilization.quotaCpuUsage`;
  - `HardwareUtilization.cpuThrottleRatio`;
  - `HardwareUtilization.perQuotaCpuThrottleRatio` entries;
  - `HardwareUtilization.perQuotaCpuPressure` entries;
  - `HardwareUtilization.totalMemoryUtilization`;
  - `HardwareUtilization.diskIOPressure`;
  - `SocketSnapshot.memoryUtilization`;
  - `CoreSnapshot.memoryUtilization`;
  - `CpuSnapshot.memoryUtilization`;
  - `CpuSnapshot.stallRatio`;
  - `CpuSnapshot.throttleRatio`;
  - `CpuSnapshot.pressure`;
  - `HardwareUtilization.pressure()`.
- Property tests cover bounds, idle baseline, monotonicity per signal, mixed/correlated inputs,
  unsupported/stale signals, reset/wrap, and zero divisors.
- Productive CPU work alone does not create pressure.
- Healthy high-throughput I/O remains low pressure; sustained low-throughput I/O stall can become
  high pressure.
- Memory headroom, reclaim/stall, quota throttle, scheduler wait, steal, thermal/frequency loss,
  and low-power state affect pressure only where supported and valid.
- Previously published snapshots cannot change when provider buffers are reused.

### `ControlPlaneFragment` and cache

- `ControlPlaneFragment` consumes only `CpuSnapshot.pressure()`.
- Pressure values `0.0`, `0.25`, `0.5`, `0.75`, and `1.0` have deterministic golden batch caps.
- Raising pressure cannot raise the adaptive batch cap.
- `eligibleMax = max(1, min(maxBatchSize, frameQuota))` and
  `eligibleMin = min(2, eligibleMax)`; `0.0` selects `eligibleMax` and `1.0` selects
  `eligibleMin`, including `maxBatchSize == 1`.
- NaN, infinities, out-of-range values, null/sparse/short arrays, null entries, no first sample, and
  out-of-order snapshots cannot terminate a worker or escape batch bounds.
- All populated CPU snapshots and the socket snapshot from one publication carry the identical
  `HardwareUtilization.timestampNs`.
- Timestamp acceptance, primitive-cap publication, and cache update are linearized. A
  latch-controlled older writer that finishes after a newer writer cannot regress either batch
  cap or cache factor; a rejected snapshot updates neither consumer.
- Core does not apply signal staleness or measurement smoothing beyond P4's final composite.
- The pressure read in the hot loop performs no allocation, lock, I/O, formatting, or logging.
- The publication/read VarHandle modes have a documented happens-before/freshness argument.
- `ControlPlaneCache.capFactor` remains finite in `[0.15, 1.0]`; greater pressure cannot increase
  its target capacity; attack remains faster than recovery.
- At regular 200 ms updates, cache policy preserves its established attack `alpha = 0.20` and
  recovery `alpha = 0.02` response. The blueprint records the equivalent time constants
  (`~0.8963 s` and `~9.8997 s`) and any missed-period limitation without editing cache production.
- Invalid/missing/older snapshots are rejected in `ControlPlaneFragment` before either fragment or
  cache policy changes.
- Combined maximum pressure still permits progress, demand, drain, reset, and shutdown.
- A minimal lattice test proves monitor publication reaches the fragment and all monitor/worker
  threads close.

### Platform runtime

- Linux fixtures cover duplicate local core IDs, sparse/offline CPUs, missing cache data, cgroup
  v1/v2/hybrid/bare host, quota `max`, cpuset changes, PSI zero/reset, files larger than one read
  buffer, ordinary and loop block devices, and read-only controller discovery.
- Linux binaries pass x86-64/AArch64 architecture/export/import checks and real glibc/musl smoke
  calls. Either no libc dependency is proven or the glibc 2.17 plus musl fallback is evidenced.
- Windows fixtures cover one and multiple processor groups, more than 64 processors, mask bit 63,
  packages/cores/caches/efficiency classes, and malformed/truncated blobs.
- Affinity tests cover empty, one-CPU, same-group multi-CPU, and cross-group masks. Windows must not
  report exact success for a cross-group mask when only one group was applied; macOS must not
  report success for an arbitrary CPU set that cannot be represented by one locality tag.
- Windows x86-64 runtime smoke passes on the minimum supported family; ARM64 smoke is validated on
  Windows 11 when runner availability permits.
- macOS fixtures cover Intel SMT, Apple Silicon performance levels, homogeneous systems, missing
  sysctl keys, and absent L3.
- macOS 11 deployment metadata is present; Intel and Apple Silicon runtime smoke passes.
- macOS topology/resources/thermal/low-power use public APIs; affinity reports
  `LOCALITY_HINT`; a representable successfully applied hint preserves legacy boolean `true`;
  unrepresentable multi-locality masks fail rather than partially succeed; release sets tag `0`;
  no realtime scheduling policy is present.
- Host/process CPU load alone does not raise macOS pressure. Runtime availability/weak-link checks
  for thermal/low-power APIs preserve the macOS 11 floor.
- Native null/short arrays, repeated/concurrent initialization, resource cleanup, and loader
  failure diagnostics are tested.

Binary metadata and fixtures do not prove a runtime floor. A missing real smoke run on P5's
blueprint-selected minimum Linux kernel with glibc 2.17/musl, Windows 10/Server 2016 x86-64,
Windows 11 ARM64, or macOS 11 Intel/ARM64 is `unverified` and blocks the corresponding support
claim and final release-ready result unless the developer explicitly approves that deviation. A
modern hosted runner proves only its actual environment, not a minimum-family floor.

### Build and package

- One checked-in manifest is the sole folder/target inventory.
- Runtime loader lookup metadata is derived from that manifest. A parameterized test proves every
  manifest product is packaged at its derived path and discoverable by the loader for its declared
  OS/architecture/runtime variant.
- The no-flag default builds every manifest product.
- Source discovery is recursively defined, extension-bounded, sorted, and deterministic.
- Missing/empty folders, duplicate output names, unsupported target/extension combinations, and
  invalid manifest entries fail with actionable errors.
- Independent target/signing nodes have no unnecessary dependency edges.
- No host-only/development mode exists.
- Existing ignored `src/main/resources/bin` and source-local Zig caches are fingerprint-identical
  before and after migration/builds; source resource allowlists make them unpackageable without
  deleting or moving user-owned artifacts.
- A clean build and a rebuild after removing a manifest product yield a jar with exactly the
  declared product set and no stale libraries, headers, native sources, Zig caches, or scripts.
- The packaged macOS file is the signed file; signature verification runs against the bundled copy.
- Binary gates inspect architecture, JNI exports/`JNI_OnLoad`, imports/`DT_NEEDED`, GLIBC versions,
  PE imports, macOS deployment target, and absence of unintended C++/compiler runtimes.
- Optimization, stack/overflow hardening, unwind/frame-pointer, compiler-runtime, SDK resolution,
  and framework links have explicit blueprint dispositions and binary evidence.
- The audit records clean and warm build timing. It claims an efficiency improvement only when
  measured; graph parallelism and stale-output correctness are required regardless.

### Scope and hygiene

- `git diff --name-only 900d8c50 -- euhedral-training` and
  `git status --short -- euhedral-training` both have no output.
- No stage invokes a training build or test.
- No root Gradle command that selects training is used as validation.
- `git diff --check` is clean.
- Searches find no stale native names, source-tree package paths, invalid header-copy workaround,
  obsolete resource references, or root documentation that still describes macOS as unsupported/
  in progress after P7 succeeds.
- Only blueprint-authorized paths change.
- All skipped runtime checks state the exact environmental limitation and remain `unverified`, not
  silently passed.

## Validation strategy

### Deterministic tests

- Use fake monotonic clocks, manual schedulers, fixture-backed files/APIs, explicit latches, and
  bounded Awaitility conditions.
- Do not use arbitrary sleeps for cadence, listener, shutdown, or race assertions.
- Use property/parameterized tests for normalization, composition, bounds, resets, and invalid
  floating-point values.
- Use immutable fixture snapshots to test old-publication stability after provider buffer reuse.
- Keep host-dependent affinity and topology smoke tests separate from deterministic parser tests.

### Focused Gradle validation

Preferred commands:

```bash
gradle :euhedral-hardware-utils:build
gradle :euhedral-core:test
mise exec -- gradle -B -pl euhedral-reactor-core -am test
mise exec -- gradle -B -pl euhedral-spring-core -am verify
mise exec -- gradle -B -pl benchmarks -am package -DskipTests
```

Use only the commands relevant to the current phase. `-am` for these selected modules must not
select `euhedral-training`; verify the reactor list when introducing a new command.

The normal root `gradle test` from `AGENTS.md` is intentionally not part of this initiative because
the developer excluded the training module. The selected-module sequence is the final Gradle gate.

Hardware tests using Testcontainers require a working Docker daemon. Host affinity tests require
exposed CPUs. Report either environmental limitation separately from Java/native compilation.

### Native/package gates

- Inspect jar contents from a clean checkout and again after manifest removal/rebuild.
- Use appropriate platform tools (`readelf`, `objdump`/`llvm-readobj`, `otool`, `codesign`,
  `dumpbin`, or equivalents) to check binary metadata.
- Exercise every JNI entry with valid, null, short, boundary, and repeat/concurrent initialization
  cases.
- Run a minimal native load/affinity/resource snapshot smoke test on each real operating system and
  architecture available.

### CI split

Cross-compilation/package validation and real runtime validation are separate:

- Cross-build every manifest product on the normal build job.
- Run deterministic fixtures on any compatible host.
- Add real smoke jobs for:
  - Linux glibc x86-64 as a required gate;
  - Linux musl x86-64 as a required gate;
  - Linux glibc/musl AArch64 where stable runners or emulation provide reliable JNI execution;
  - Windows x86-64 as a required gate;
  - Windows ARM64 when a runner is available;
  - macOS Intel as a required gate when the repository has access to `macos-*-intel`; and
  - macOS Apple Silicon as a required gate when the repository has access to an ARM64 macOS
    runner.
- Runner-unavailable architectures remain fixture and binary-gated and are classified
  `unverified` for runtime, never silently treated as passed. An unverified minimum floor blocks
  its support claim/final release-ready classification unless the developer explicitly accepts the
  deviation.
- Signing credentials, if any are introduced, are unavailable to untrusted pull requests. PRs use
  only an explicitly safe ad hoc/test signature path.
- Task validation and runtime jobs live in a hardware-specific selected-module workflow. Removing
  invalid JNI-header preparation and supplying explicit native inputs in the existing build and
  deploy workflows is allowed, but their pre-existing Gradle commands remain outside initiative
  evidence and no task change may broaden or rely on their training execution.

### Performance validation

- Record clean and warm universal Zig graph times before and after P1.
- Prove independent targets/signers are graph-independent rather than assuming sequential loop
  construction means sequential execution.
- Do not claim monitor throughput or allocation improvements from unit-test timing.
- If a blueprint makes a 200 ms sampler overhead/allocation claim, add a narrowly scoped JMH
  benchmark under `benchmarks` and keep training excluded.
- Test `ControlPlaneFragment` hot-path allocation structurally and, if a numeric performance claim
  is made, with JMH.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Correct topology changes effective worker placement | Preserve stable deterministic logical IDs, core-zero policy, and API shapes; fixture every remap boundary and test a real lattice. |
| New pressure changes throughput/latency | Define pressure as lost capacity, use property and golden response tests, validate batch plus cache together, and benchmark only explicit performance claims. |
| Rich signals differ by OS | Track validity/age internally, normalize by semantic domain, and keep unsupported inputs neutral. |
| macOS cannot provide Linux-equivalent pinning/topology detail | Report `LOCALITY_HINT`, maintain managed logical ownership, document unsupported physical identity, and use conservative public-sysctl topology. |
| Lowest-runtime native goals conflict with available APIs | Feature-probe dynamically, enforce binary import/version gates, and use the accepted glibc 2.17 plus musl fallback if libc neutrality is disproven. |
| Automatic folder discovery compiles unintended files | Manifest only designated production roots; explicit extensions, recursion, deterministic order, duplicate detection, and fail-loud validation. |
| Parallel signing packages an unsigned copy | Sign a target-local staged copy and make its install edge depend on signature verification. |
| 200 ms polling makes slow sensors expensive | Decimate/cache expensive sensors with age validity while preserving the poll-start grid. |
| Slow listeners create backlog or reorder data | Single bounded dispatcher with one active and one coalesced latest value; monotonic timestamp tests and close barrier. |
| Public snapshots retain mutable provider storage | Deep-copy at the publication boundary and add mutation-after-publication regression tests. |
| Native tests are host-dependent | Pair deterministic fixtures and binary inspection with explicitly classified real-runner smoke jobs. |
| Scope expands into training or broader core policy | Mechanical path/command checks, explicit prompt prohibitions, and phase-8 production boundary. |
| "Fix any bugs" becomes unbounded | Maintain the known-defect ledger; fix newly found local defects only inside settled scope and return new design choices to blueprint. |

## Branch lineage

No branch creation, merge, rebase, deletion, commit, or push is authorized by this plan alone.
Before every action item, inspect `git status --short` and preserve pre-existing user-owned
changes.

The completed planning branch `agent/hardware-utils-overhaul-plan` predates the updated branch
format and is retained. Each future P0-P8 work phase uses one compliant root phase branch. A root
begins from the completed preceding root; its action items use child branches with `-blueprint`,
`-implementation`, and `-audit` suffixes.

| Plan phase | Root phase branch |
| --- | --- |
| P0 | `hardware-utils-overhaul/phase-0-compatibility-baseline` |
| P1 | `hardware-utils-overhaul/phase-1-native-build` |
| P2 | `hardware-utils-overhaul/phase-2-topology-snapshot` |
| P3 | `hardware-utils-overhaul/phase-3-affinity-executor` |
| P4 | `hardware-utils-overhaul/phase-4-pressure-monitor` |
| P5 | `hardware-utils-overhaul/phase-5-linux` |
| P6 | `hardware-utils-overhaul/phase-6-windows` |
| P7 | `hardware-utils-overhaul/phase-7-macos` |
| P8 | `hardware-utils-overhaul/phase-8-core-release` |

For each root phase:

1. Create the root from the completed preceding root.
2. Create the blueprint child from the root, complete it, and merge it back only when authorized.
3. Create implementation and audit/conformance children in order from the updated root; merge each
   completed child before creating its sibling. P1's authorized conformance-only exception omits
   conformance/manual-review children.
4. Do not start the next root phase from an unmerged child.
5. Implementation and later action items maintain the temporary `AGENTS.md` phase-status block.
   After the audit child is merged and the root phase is complete, remove that block on the root
   before starting the next phase.

If a blueprint's sizing gate creates child blueprints, use the same root phase prefix with a
specific responsibility suffix, give every child its own implementation/conformance/manual-review
action
items, and merge all child results into the root before phase-level audit and closeout. The
blueprint must update this plan's prompts, parent artifacts, lineage, and phase artifact index
before handoff. Replace or expand that phase's index entry to name every parent/child blueprint and
completion record, every child conformance/manual-review record, and any root conformance/audit.
An explicit developer-authorized integrated-conformance exception may omit child conformance
actions only when the plan names the exception, retains reviewed sequential child implementations,
and assigns every child criterion to one final independent conformance action. P4 uses that
exception; it has one integrated conformance action after P4-D and no child validation/audit.

P1 uses that split rule with a developer-authorized conformance-only exception: the validation
action is skipped in favor of conformance checking and manual review. After the parent blueprint
merges, the root advances through the
`phase-1-native-graph-{blueprint,implementation,audit}` family, then the
`phase-1-loader-package-{blueprint,implementation,audit}` family, then the root conformance check
and manual review, and finally `phase-1-native-build-audit`. Each child starts only after its
predecessor merges. The superseded
`phase-1-native-build-implementation` branch is never created.

P2 also uses the split rule. Its parent blueprint freezes the shared identity, count/index,
fallback, immutability, null-hole, core-zero, version, and publication contracts. After that
parent blueprint merges, the root advances through the
`phase-2-topology-model-{blueprint,implementation,audit}` family and then the
`phase-2-snapshot-publication-{blueprint,implementation,audit}` family. Each action starts only
after its predecessor is reviewed and merged. The superseded
`phase-2-topology-snapshot-implementation` branch is never created. Under the current workflow,
each audit is the combined conformance check and manual review; no P2 validation branch or
validation artifact exists.

P3 uses the split rule as well. Its parent blueprint freezes the public affinity capability,
mask, managed-owner, restoration, executor state, registry, cleaner/hook, deadline, and memory-
ordering contracts. After that parent blueprint merges, the root advances through the
`phase-3-affinity-capability-{blueprint,implementation,audit}` family and then the
`phase-3-executor-lifecycle-{blueprint,implementation,audit}` family. Each action starts only
after its predecessor is reviewed and merged. The superseded
`phase-3-affinity-executor-implementation` branch is never created. Under the current workflow,
each child audit is the combined conformance check and manual review; there is no P3 validation
branch or validation artifact. A P3 root audit follows both child audits.

P4 uses a four-way responsibility split. Its parent blueprint freezes canonical units, detailed
sample/validity and compatibility contracts, delta/age rules, pressure formulas/constants,
public projection, duration/lifecycle/scheduler behavior, listener ownership, and memory-ordering
edges. After that parent blueprint merges, the root advances through the
`phase-4-sample-validity-{blueprint,implementation}` family, then
`phase-4-pressure-math-{blueprint,implementation}`, then
`phase-4-listener-publication-{blueprint,implementation}`, and finally
`phase-4-monitor-lifecycle-{blueprint,implementation}`. Each action starts only after its
predecessor is reviewed and merged. By explicit developer direction, P4 is an exception to the
per-child conformance rule: no child validation/conformance/audit branch or artifact is created.
The superseded `phase-4-pressure-monitor-implementation` branch and every P4 validation branch are
never created. One integrated `phase-4-pressure-monitor-audit` conformance action follows the
reviewed and merged P4-D implementation and owns phase closeout.

The audit action remains responsible for root closeout. It first produces its audit on the audit
child. If the developer has not authorized the merge and closeout, it hands off a review-ready
audit, leaves the root incomplete, and prohibits the next phase. Once authorized, resume that audit
action, merge the audit child, switch to the root, remove only that hardware phase's temporary
`AGENTS.md` status block, append the phase closeout summary to this plan, and record the resulting
root commit when committed. The phase is complete only after those closeout outputs are reviewed.

### Initial phase ownership

| Plan phase | Initial package/module ownership                                                                                                                                                                                                                                                             |
|------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| P0         | `euhedral-hardware-utils` test sources/resources and module-local compatibility tooling; non-training core/benchmark consumers are read-only                                                                                                                                                 |
| P1         | hardware Gradle/native build assets, generated resources, `hardware_utils.internal` loader code, and hardware-specific CI                                                                                                                                                                    |
| P2         | hardware root/common/internal topology and snapshot ownership, layout adapters, and hardware tests                                                                                                                                                                                           |
| P3         | parent: hardware root/internal affinity and executor lifecycle, platform affinity facades, and hardware tests; P3-A owns affinity/capability/managed ownership and P3-B owns executor registry/lifecycle                                                                                     |
| P4         | parent: hardware root/common/internal sampling, pressure, monitor lifecycle, provider compatibility adapters, and hardware tests; P4-A owns sampling/validity/adapters, P4-B pressure/public projection, P4-C listener publication, and P4-D monitor lifecycle/scheduling; core is read-only |
| P5         | hardware Linux Java/native implementation, Linux fixtures/tests, and Linux manifest/CI metadata                                                                                                                                                                                              |
| P6         | hardware Windows Java/native implementation, Windows fixtures/tests, and Windows manifest/CI metadata                                                                                                                                                                                        |
| P7         | hardware macOS Java/native implementation, macOS fixtures/tests, and macOS manifest/CI metadata                                                                                                                                                                                              |
| P8         | `euhedral-core` `ControlPlaneFragment`, focused core tests, test-only `ControlPlaneCache`, hardware release/CI/docs, and approved non-training benchmarks                                                                                                                                    |

### Phase artifact index

These prescribed paths are the exact prior-artifact index for later prompts. Each completion record
is appended to its blueprint. Each audit/root-closeout action appends a compact `P# closeout
summary` to that phase's prompt section in this plan with the root branch/commit, child results,
requirement status, approved deviations, and environmental limits. When a prompt names P0-PN
artifact-index entries, read these exact files plus those compact closeout summaries; do not infer
an unbounded feature-history context.

| Phase                         | Blueprint and completion record                                               | Conformance/manual review                                        | Audit                                                                                 |
|-------------------------------|-------------------------------------------------------------------------------|------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| P0                            | `docs/blueprints/hardware-utils/phase-0-compatibility-test-baseline.md`       | conformance/manual review; historical validation record retained | `docs/audits/hardware-utils/phase-0-compatibility-test-baseline-conformance.md`       |
| P1 parent/root integration    | `docs/blueprints/hardware-utils/phase-1-native-build-jni-packaging.md`        | skipped; conformance check and manual review                     | `docs/audits/hardware-utils/phase-1-native-build-jni-packaging-conformance.md`        |
| P1-A native graph/JNI/signing | `docs/blueprints/hardware-utils/phase-1-native-graph-jni-signing.md`          | skipped; conformance check and manual review                     | `docs/audits/hardware-utils/phase-1-native-graph-jni-signing-conformance.md`          |
| P1-B loader/package/CI        | `docs/blueprints/hardware-utils/phase-1-loader-gradle-packaging.md`            | skipped; conformance check and manual review                     | `docs/audits/hardware-utils/phase-1-loader-gradle-packaging-conformance.md`            |
| P2 parent/root integration    | `docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md`           | child conformance/manual reviews plus root manual review         | `docs/audits/hardware-utils/phase-2-topology-snapshot-model-conformance.md`           |
| P2-A topology model/adapters  | `docs/blueprints/hardware-utils/phase-2-topology-model-adapters.md`           | conformance check and manual review                              | `docs/audits/hardware-utils/phase-2-topology-model-adapters-conformance.md`           |
| P2-B snapshots/publication    | `docs/blueprints/hardware-utils/phase-2-snapshot-remap-publication.md`        | conformance check and manual review                              | `docs/audits/hardware-utils/phase-2-snapshot-remap-publication-conformance.md`        |
| P3 parent/root integration    | `docs/blueprints/hardware-utils/phase-3-affinity-executor-lifecycle.md`       | child conformance/manual reviews plus root manual review         | `docs/audits/hardware-utils/phase-3-affinity-executor-lifecycle-conformance.md`       |
| P3-A affinity capability      | `docs/blueprints/hardware-utils/phase-3-affinity-capability.md`               | conformance check and manual review                              | `docs/audits/hardware-utils/phase-3-affinity-capability-conformance.md`               |
| P3-B executor lifecycle       | `docs/blueprints/hardware-utils/phase-3-executor-lifecycle.md`                | conformance check and manual review                              | `docs/audits/hardware-utils/phase-3-executor-lifecycle-conformance.md`                |
| P4 parent/root integration    | `docs/blueprints/hardware-utils/phase-4-resource-monitor-pressure.md`         | one integrated conformance/manual review after P4-D              | `docs/audits/hardware-utils/phase-4-resource-monitor-pressure-conformance.md`         |
| P4-A sample/validity          | `docs/blueprints/hardware-utils/phase-4-sample-validity-contract.md`          | covered by final integrated P4 conformance                       | none; developer-authorized single-conformance flow                                    |
| P4-B pressure/projection      | `docs/blueprints/hardware-utils/phase-4-pressure-mathematics.md`              | covered by final integrated P4 conformance                       | none; developer-authorized single-conformance flow                                    |
| P4-C listener publication     | `docs/blueprints/hardware-utils/phase-4-listener-publication.md`              | covered by final integrated P4 conformance                       | none; developer-authorized single-conformance flow                                    |
| P4-D monitor lifecycle        | `docs/blueprints/hardware-utils/phase-4-monitor-lifecycle-scheduler.md`       | covered by final integrated P4 conformance                       | none; developer-authorized single-conformance flow                                    |
| P5 parent/root integration    | `docs/blueprints/hardware-utils/phase-5-linux-platform.md`                    | child conformance/manual reviews plus root manual review         | `docs/audits/hardware-utils/phase-5-linux-platform-conformance.md`                    |
| P5-A Linux topology model     | `docs/blueprints/hardware-utils/phase-5-linux-topology-model.md`               | conformance check and manual review                              | `docs/audits/hardware-utils/phase-5-linux-topology-model-conformance.md`               |
| P5-B Linux resource provider  | `docs/blueprints/hardware-utils/phase-5-linux-resource-provider.md`             | conformance check and manual review                              | `docs/audits/hardware-utils/phase-5-linux-resource-provider-conformance.md`             |
| P5-C Linux affinity & native  | `docs/blueprints/hardware-utils/phase-5-linux-affinity-native.md`              | conformance check and manual review                              | `docs/audits/hardware-utils/phase-5-linux-affinity-native-conformance.md`              |
| P6 parent/root integration    | `docs/blueprints/hardware-utils/phase-6-windows-platform.md`                 | child conformance/manual reviews plus root manual review         | `docs/audits/hardware-utils/phase-6-windows-platform-conformance.md`                 |
| P6-A Windows topology model   | `docs/blueprints/hardware-utils/phase-6-windows-topology-model.md`            | conformance check and manual review                              | `docs/audits/hardware-utils/phase-6-windows-topology-model-conformance.md`            |
| P6-B Windows resource provider| `docs/blueprints/hardware-utils/phase-6-windows-resource-provider.md`          | conformance check and manual review                              | `docs/audits/hardware-utils/phase-6-windows-resource-provider-conformance.md`          |
| P6-C Windows affinity & native| `docs/blueprints/hardware-utils/phase-6-windows-affinity-native.md`           | conformance check and manual review                              | `docs/audits/hardware-utils/phase-6-windows-affinity-native-conformance.md`           |
| P7                            | `docs/blueprints/hardware-utils/phase-7-macos-platform.md`                    | conformance/manual review                                        | `docs/audits/hardware-utils/phase-7-macos-platform-conformance.md`                    |
| P8                            | `docs/blueprints/hardware-utils/phase-8-control-plane-integration-release.md` | conformance/manual review                                        | `docs/audits/hardware-utils/phase-8-control-plane-integration-release-conformance.md` |

## Prompt sequence

### Reasoning-intensity ranking

Execution still follows P0 through P8. This ranking only identifies how demanding each prompt is.
Implementation selections are provisional until their blueprints complete the mandatory sizing,
split, and implementation-model reassessments. P2-A/P2-B and P4-A through P4-D show settled
parent-blueprint selections; their child blueprints must confirm or upgrade them before
implementation.

| Rank | Prompt                                                     | Selection               |
|-----:|------------------------------------------------------------|-------------------------|
|    1 | P4 parent/child blueprints - sampling/pressure/lifecycle   | `gpt-5.6-sol`, `max`    |
|    2 | P7 blueprint - macOS public-API parity                     | `gpt-5.6-sol`, `max`    |
|    3 | P6 blueprint - Windows processor-group/native parity       | `gpt-5.6-sol`, `max`    |
|    4 | P5 parent & child blueprints - Linux topology/resources/ABI| `gpt-5.6-sol`, `max`    |
|    5 | P3 parent/child blueprints - affinity/executor concurrency | `gpt-5.6-sol`, `max`    |
|    6 | P2 blueprint - topology and snapshot ownership             | `gpt-5.6-sol`, `max`    |
|    7 | P1 blueprint - native build/JNI/package ABI                | `gpt-5.6-sol`, `max`    |
|    8 | P8 blueprint - core hot-loop and release integration       | `gpt-5.6-sol`, `max`    |
|    9 | P0 blueprint - compatibility/test baseline                 | `gpt-5.6-sol`, `high`   |
|   10 | P4-A through P4-D parent-selected implementations          | `gpt-5.6-sol`, `high`   |
|   11 | P5-A, P5-B, P5-C parent-selected implementations           | `gpt-5.6-sol`, `high`   |
|   12 | P7 provisional implementation                              | `gpt-5.6-sol`, `high`   |
|   13 | P6 provisional implementation                              | `gpt-5.6-sol`, `high`   |
|   14 | P3-A and P3-B selected implementations                     | `gpt-5.6-sol`, `high`   |
|   15 | P2-A and P2-B selected implementations                     | `gpt-5.6-sol`, `high`   |
|   16 | P1-A and P1-B selected implementations                     | `gpt-5.6-sol`, `high`   |
|   17 | P8 provisional implementation                              | `gpt-5.6-sol`, `high`   |
|   18 | P0 implementation - compiled compatibility/test baseline   | `gpt-5.6-sol`, `medium` |
|   19 | P8 conformance/manual review                               | `gpt-5.6-sol`, `high`   |
|   20 | P7 conformance/manual review                               | `gpt-5.6-sol`, `high`   |
|   21 | P6 conformance/manual review                               | `gpt-5.6-sol`, `high`   |
|   22 | P5 child/root conformance/manual reviews                   | `gpt-5.6-sol`, `high`   |
|   23 | P3 child/root conformance/manual review                    | `gpt-5.6-sol`, `high`   |
|   24 | P2 child/root conformance/manual review                    | `gpt-5.6-sol`, `high`   |
|   25 | P1 child and root conformance/manual review                | `gpt-5.6-sol`, `high`   |
|   26 | P0 conformance/manual review                               | `gpt-5.6-sol`, `medium` |
|   27 | P8 final conformance audit                                 | `gpt-5.6-sol`, `high`   |
|   28 | P5 child/root conformance audits                           | `gpt-5.6-sol`, `high`   |
|   29 | P4 single integrated conformance/manual-review audit       | `gpt-5.6-sol`, `high`   |
|   30 | P7 conformance audit                                       | `gpt-5.6-sol`, `high`   |
|   31 | P6 conformance audit                                       | `gpt-5.6-sol`, `high`   |
|   32 | P3 child/root conformance audits                           | `gpt-5.6-sol`, `high`   |
|   33 | P2 child/root conformance audits                           | `gpt-5.6-sol`, `high`   |
|   34 | P1 child and root conformance audits                       | `gpt-5.6-sol`, `high`   |
|   35 | P0 conformance audit                                       | `gpt-5.6-sol`, `medium` |

### P0 - compatibility contract and deterministic test baseline

#### P0 blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After authorization, create the root branch
> `hardware-utils-overhaul/phase-0-compatibility-baseline` from the completed
> `agent/hardware-utils-overhaul-plan` branch, then work on its
> `hardware-utils-overhaul/phase-0-compatibility-baseline-blueprint` child. The parent artifact is
> `docs/plans/hardware-utils-platform-parity-overhaul.md`. Initial ownership is limited to
> `euhedral-hardware-utils` test sources/resources and module-local compatibility tooling;
> production sources and non-training downstream consumers are read-only. Inspect
> `git status --short` first and preserve all prior user-owned changes. Read `AGENTS.md`,
> `docs/AGENT_WORKFLOW.md`, the parent plan, the hardware `pom.xml`, `module-info.java`, every
> public hardware Java source, existing hardware tests, and only the non-training downstream
> inventory named in the plan. Do not inspect `euhedral-training`.
>
> Write
> `docs/blueprints/hardware-utils/phase-0-compatibility-test-baseline.md` as an
> implementation-ready blueprint. Settle an executable comparison against `900d8c50` for the
> module name, all exported packages, public/protected descriptors, constructors, constants,
> hierarchy/interfaces, generic signatures, modifiers, checked exceptions, constant values,
> complete module descriptor, nested types, record component names/order, static facade, mask
> formatting, intended aggregate native resource/JNI names, 200 ms default, concurrent
> fresh-thread executor behavior, and core-zero reservation. Allow additive API members while
> rejecting removal/change. Define a checked-in
> defect-ledger test mapping and deterministic fixture/test-helper layout. Distinguish API
> compatibility from known incorrect values that later phases must correct. Every behavior
> exception must record defect ID, fully qualified member/resource, old behavior, new invariant,
> and regression-test ID; unmatched drift fails. Specify contract-bearing files,
> tool/plugin choice, deterministic output format, commands, failures, and acceptance assertions.
> Generate the branch-point baseline through source-level signature extraction or an isolated
> temporary worktree/output path. Do not invoke the current native-generating Gradle build lifecycle in
> the active worktree before P1; prove the active worktree and source-resource inventory are
> unchanged before and after baseline generation.
>
> Define package ownership, naming, data flow, and every high-reasoning contract needed for
> implementation without enumerating minor files unnecessarily. Include a bounded implementation
> context envelope naming required inputs and owned outputs. Explicitly cover memory
> semantics, memory pollution/contamination, and mathematical precision, or record a reasoned
> `not applicable` where an area truly does not apply. Apply the workflow sizing/split gate. If
> independent responsibilities make this blueprint too large, define responsibility-scoped child
> blueprint action items and branch names now, give each a bounded context envelope, and update
> this plan's P0 implementation/conformance/audit lineage, parent artifacts, and phase artifact
> index. Only after this parent blueprint child is merged may those branches be created from the
> updated P0 root; rerun the gate for every child. The root implementation prompt must not run
> after a split.
>
> Allowed edits are the new blueprint, this plan, and closely related planning documentation only.
> Production Java/native/Zig, tests, CI, core, benchmarks, and all training paths are prohibited.
>
> After the blueprint is complete, add its required `Implementation model reassessment`, evaluate
> actual context/coupling/test breadth, select the final implementation model and effort, and
> replace the provisional P0 implementation label and complete prompt body in this plan. Append
> the workflow's developer-review summary to the P0 section of the parent plan: purpose, ownership,
> key contracts, resulting child blueprints, selected implementation model, risks, and unresolved
> decisions. Do not hand off while the implementation prompt remains provisional. If a material
> ambiguity remains, stop and ask the developer.
>
> The output artifact is the finalized blueprint, its parent-plan review summary, and the finalized
> implementation prompt. Handoff for review and merge into the P0 root only when another agent can
> implement the baseline without selecting a tool, deciding what compatibility means, or encoding
> a known bug as required behavior. Do not start implementation until this child is merged.

#### P0 developer-review summary

- Purpose: create one deterministic compiled Java/native-name/behavior compatibility gate against
  `900d8c50` without running or publishing the native build.
- Ownership: hardware-module test-scoped ASM configuration, the
  `io.euhedral_execution.hardware_utils.compatibility` test package, three compatibility resources,
  and the blueprint completion record. Production, downstream modules, CI, benchmarks, and
  training remain read-only/prohibited.
- Key contracts: exact complete module descriptor; baseline public/protected classfile surface as a
  required subset with additive members allowed; typed constants, Lombok-generated members,
  descriptors/generics/exceptions, records/nested/sealed metadata; eight intended aggregate native
  paths and Java-owned JNI names; exact mask text and 200,000,000 ns default; latch-proven
  concurrent fresh threads; core-zero reservation; and a strict exact-subject defect ledger.
- Child blueprints: none. The sizing gate found one bounded, cohesive module-local test
  responsibility.
- Implementation selection: `gpt-5.6-sol`, `medium`.
- Principal risks: subtle classfile normalization, JPMS test compilation, accidentally loading
  hardware classes during extraction, host cleanup in the executor test, and accidentally
  triggering the bound Zig lifecycle. The blueprint fixes each boundary and uses direct Gradle
  plugin goals plus source/resource fingerprints.
- Unresolved decisions: none.

#### P0 implementation prompt

**Model: `gpt-5.6-sol`; reasoning effort: `medium`.**

> After the P0 blueprint child is reviewed and merged, start
> `hardware-utils-overhaul/phase-0-compatibility-baseline-implementation` from the updated P0 root.
> The parent artifact is
> `docs/blueprints/hardware-utils/phase-0-compatibility-test-baseline.md`. Inspect
> `git status --short` first and preserve the pre-existing benchmark image and training output
> without inspecting training. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the P0 requirements and
> review summary in this plan, the complete parent blueprint, and only its bounded implementation
> context envelope. Confirm this prompt has no provisional label.
>
> Implement the blueprint exactly: add only the hardware POM's test-scoped ASM dependency or
> narrowly necessary module-local test configuration; the
> `io.euhedral_execution.hardware_utils.compatibility` ASM surface reader/comparator/report,
> native-name check, ledger parser, and focused behavior tests; the three checked-in compatibility
> resources; the completion record; and the temporary P0 `AGENTS.md` status block. Production
> Java/native/header/Zig/resources, root POM/plugins, CI, core, Reactor, Spring, benchmarks, and
> every training path/command are prohibited.
>
> Generate `api-900d8c50.tsv` only from an isolated `git archive` containing the root POM and
> hardware module at `900d8c50`. Use the blueprint's direct `resources`, `compiler`, `surefire`,
> and `exec:java` plugin goals; do not run Gradle `initialize`, `test`, `verify`, `package`,
> `install`, a root reactor, or Zig in the active worktree. Fingerprint every active main Java and
> resource file, including ignored binaries, before and after generation. The ASM tool must not
> load production classes. Preserve the exact complete module descriptor and baseline
> public/protected surface, allow only additive declarations, and prove deliberate descriptor and
> record-order changes fail. Preserve only the eight intended aggregate native paths and Java-owned
> JNI names; map current native mismatches through exact N01/N02 records rather than blessing them.
>
> Add the complete exact-subject B01-B07, T01-T06, A01-A04, R01-R14, N01-N02, and C01-C02 ledger
> mapping and its later regression IDs. Do not assert a current invalid numeric/topology/pressure/
> lifecycle/affinity/native/core-policy result as compatible. Add exact mask-format, 200,000,000 ns
> default, latch-controlled concurrent fresh-thread, and core-zero reservation tests. Run
> baseline generation twice, the complete direct-goal P0 test command twice, byte-compare fixtures
> and reports, and perform every non-contamination/scope check in the blueprint.
>
> If JPMS/ASM integration, the baseline source, or a native contract requires a production module
> edit, a new exception category, or another unsettled choice, stop and append the conflict and
> evidence to the blueprint. Do not redesign around it. Otherwise append completion notes listing
> changed files, commands/results, fixture hashes, every acceptance-criterion result, approved
> deviations, and exact environmental limits. Add/update only the workflow-required temporary
> `AGENTS.md` block with completed planning context `agent/hardware-utils-overhaul-plan`, active P0
> root, completed blueprint child, active implementation child, and blueprint/completion links.
>
> The output artifact is the green deterministic compatibility baseline plus the completion record
> appended to the blueprint. Handoff only when deliberate descriptor/record mutations fail, no
> artifact is published, the active Java/native resource fingerprint is unchanged, every known
> defect maps to a later exact regression, and only blueprint-owned files changed. Merge this child
> into the P0 root before validation.

#### P0 validation prompt - SUPERSEDED, DO NOT RUN

**Model: `gpt-5.6-sol`; reasoning effort: `medium`.**

> After the P0 implementation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-0-compatibility-baseline-validation` from the P0 root. The parent
> artifact is the implementation completion record in
> `docs/blueprints/hardware-utils/phase-0-compatibility-test-baseline.md`. Ownership remains the
> P0 compatibility test surface, with only blueprint-settled minor corrections permitted. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan, finalized blueprint,
> implementation diff, tests, and completion notes. Do not inspect or run training.
>
> Re-run every P0 validation command and check every acceptance criterion. Verify deterministic
> output, coverage of all five exports and public record shapes, the intentional-correction
> boundary, isolated/source-level baseline generation, active-worktree non-contamination,
> executor concurrency, core-zero reservation, and defect-ledger completeness. Allowed
> edits are minor blueprint-settled test/naming corrections, the blueprint completion record, the
> temporary `AGENTS.md` phase-status block, and
> `docs/validations/hardware-utils/phase-0-compatibility-test-baseline-validation.md`. Production
> changes, weakening the baseline, redesign, downstream changes, and training are prohibited. A
> new architectural decision returns to the blueprint; an ordinary implementation defect returns
> to implementation.
>
> The output artifact is the validation record above, with commands, results, fixes, skipped
> checks, exact environmental limits, and an acceptance-criterion matrix; append its summary to
> the P0 completion record and mark the validation child active/completed in the temporary
> phase-status block. Handoff for merge into the P0 root only with a deterministic green baseline
> and no material deviation. Merge this child before audit.

#### P0 conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `medium`.**

> After the P0 validation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-0-compatibility-baseline-audit` from the P0 root. The parent
> artifact is
> `docs/validations/hardware-utils/phase-0-compatibility-test-baseline-validation.md`. Ownership is
> limited to independent conformance review of the P0 compatibility surface and minor
> blueprint-settled corrections. Inspect `git status --short`. Read `AGENTS.md`,
> `docs/AGENT_WORKFLOW.md`, the plan's summarized parent context, the P0 blueprint and completion
> record, implementation diff, validation record, and relevant tests. For a split P0, consume only
> the child context envelope and summarized parent context. Do not inspect or run training.
>
> Independently audit every P0 requirement and the validation evidence, including deterministic
> output, all exported API/record shapes, correction boundaries, worktree non-contamination,
> executor concurrency, core-zero reservation, and defect-ledger completeness. Allowed edits are
> `docs/audits/hardware-utils/phase-0-compatibility-test-baseline-conformance.md`, the completion
> and validation records, the P0 closeout summary in this plan, the temporary `AGENTS.md`
> phase-status block, and minor blueprint-settled corrections. If a correction is made, rerun and
> record the affected validation. Production changes, weakening, redesign, unrelated files, and
> training are prohibited.
>
> The output artifacts are the audit above, updated completion record, P0 closeout summary in this
> plan, and, after the authorized merge, removal of the temporary P0 status block on the root with
> the resulting root commit recorded when committed. Classify every requirement exactly as
> `satisfied`, `deviated`, `unverified`, or `ambiguous`, with command and file evidence. Append
> audit commands, results, fixes, skipped checks, and environmental limits to the completion
> record. A material deviation returns to the exact P0 blueprint or implementation action.
> Handoff follows the audit/root-
> closeout contract: a review-ready audit may wait for merge authorization, but P0 is complete
> only after the authorized merge, P0 status-block removal, and closeout-summary update; do not
> create P1 earlier.

#### P0 closeout summary

P0 is complete. Audit child `hardware-utils-overhaul/phase-0-compatibility-baseline-audit`
independently classified all 16 acceptance criteria as `satisfied`, reran the direct-goal hardware
compatibility suite with 17 tests passing, and confirmed the `PASS` compatibility report hash,
source/resource non-contamination, and clean diff checks. No approved deviations or environmental
limits remain. The developer authorized closeout on 2026-07-30; the audit child merge is
`ed839216`, and the subsequent P0 closeout commit tracks the audit report and removes the temporary
status block. P1 must branch from that completed root; its blueprint records the exact inherited
commit.

### P1 - universal Zig build, JNI ABI, loader, and packaging

#### P1 blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After authorization, create `hardware-utils-overhaul/phase-1-native-build` from the completed P0
> root, then work on
> `hardware-utils-overhaul/phase-1-native-build-blueprint`. The parent artifact is
> `docs/plans/hardware-utils-platform-parity-overhaul.md`, with the completed P0 phase artifact
> index entry and closeout summary as inherited evidence. Initial ownership is hardware Gradle/
> native build assets, generated resources,
> `hardware_utils.internal` loader code, and hardware-specific CI. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the parent plan, the exact P0
> blueprint/completion, validation, and audit files linked by its phase artifact index entry and
> its closeout summary, `mise.toml`, the hardware `pom.xml`, `build.zig`, native folder tree, JNI
> declarations/headers, `JNIClassLoader`, the native setup in `.github/workflows/build.yaml` and
> `.github/workflows/deploy.yaml`, and clean packaged-resource inventories. Do not inspect
> training.
>
> Write `docs/blueprints/hardware-utils/phase-1-native-build-jni-packaging.md`. Settle the
> checked-in folder manifest format and schema; recursive source extensions/order; aggregate
> component/output naming; all target/libc/architecture metadata; validation failures; parallel
> Zig graph; explicit Java/SDK/signing inputs; generated JNI declaration and target-correct ABI
> header strategy; manifest-derived runtime loader lookup metadata; target/generated-resource
> staging; exact jar manifest; cache behavior; signed staged-copy ordering; loader architecture/
> fallback/permission/temp-file/noexec-filesystem behavior; and architecture/export/import/
> deployment/runtime-floor gates. Decide the stale source binary and obsolete `build.sh`
> migration safely. Explicitly disposition the current ReleaseFast/`-O3`, stack/check/protector,
> unwind/frame-pointer, compiler-runtime, SDK-search, and framework-link settings with portability/
> safety evidence. Preserve the no-flag universal build and prohibit a host-only/development mode.
> The Linux design must attempt validated libc neutrality and use the accepted glibc 2.17 plus
> musl fallback only on recorded failure.
>
> Specify each affected file in dependency order, exact manifest examples, deterministic product
> ordering, failure diagnostics, Gradle build lifecycle/resource wiring, CI signing safety, clean/rebuild
> tests, timing evidence, and binary commands. Platform sensor/topology/pressure/affinity semantics,
> core, benchmarks without an approved measurement need, unrelated CI, and all training work are
> prohibited. Task validation/runtime jobs must use a hardware-specific selected-module workflow;
> no root POM/plugin behavior inherited by training may change. The invalid header-copy steps may
> be removed from the existing build and deploy workflows, but their pre-existing Gradle commands
> are outside initiative evidence and must remain unchanged.
>
> Define package/artifact ownership, naming, data flow, and high-reasoning build, ABI, safety, and
> compatibility contracts without enumerating minor files unnecessarily. Include a bounded
> implementation context envelope naming required inputs and owned outputs. Explicitly settle
> memory semantics, build/runtime memory pollution or artifact contamination, and mathematical
> precision in sizes, alignments, versions, and timestamps; record a reasoned `not applicable`
> only where justified. Apply the workflow sizing/split gate. If independent build, JNI, loader,
> or signing responsibilities exceed one implementation context, define responsibility-scoped
> child blueprint action items, branch names, and context envelopes now, then update all P1
> implementation/conformance/manual-review prompts, parents, and the phase artifact index in this plan.
> Only after this parent blueprint child is merged may those branches be created from the updated
> P1 root; rerun the gate per child. The root implementation prompt must not run after a split.
>
> Edit only the blueprint, plan, and planning docs. Perform the mandatory
> `Implementation model reassessment`, then replace the provisional P1 implementation selection
> and complete body in the plan. Append the workflow-required developer-review summary to the P1
> plan section, including
> purpose, ownership, key contracts, children, selected implementation model, risks, and unresolved
> decisions.
>
> The output artifact is the finalized blueprint, plan summary, and, if split, the complete child
> blueprint/implementation/conformance/manual-review and root prompt sequence. Handoff for
> review and merge into the P1 root only when no child needs to decide manifest format, headers,
> output paths, signing edges, loader lookup generation/extraction, hardening/optimization, or
> runtime gates. Do not create the first child branch before that merge.

#### P1 developer-review summary

- Purpose: replace the source-writing native build and hardcoded loader table with a strict
  manifest, target-local JNI ABI, signed target staging, exact packaging, safe extraction, and
  enforceable binary/runtime gates.
- Ownership: hardware native/Gradle/generated resources and internal loader code, narrowly scoped
  native CI setup, module-local tests, and P1 documentation. Platform semantics, core, root Gradle
  policy, unrelated workflow behavior, and training are excluded.
- Key contracts: exact eight-product JSON schema; recursive sorted discovery; generated seven-class
  JNI declarations plus project-owned target `jni_md.h`; glibc 2.17 plus musl; ReleaseSafe with
  selected hardening; product-private signing and verified staged copy; generated TSV as the only
  loader table; exact jar inventory; bounded owner-private extraction/cleanup; and LLVM plus real
  runner gates.
- Children: P1-A owns the native graph, JNI, signing, and Gradle staging. After its audit merges,
  P1-B owns loader, package/binary gates, runtime smoke, and CI. Root conformance checking,
  manual review, and root audit follow both child audits; validation is skipped.
- Implementation capability: both child implementations, conformance audits/manual reviews, and root
  integration use `gpt-5.6-sol` with `high` reasoning. Each child blueprint must rerun the gate
  and may raise capability/effort, but may not silently downgrade.
- Primary risks: Zig 0.16 API drift, strict Windows UCRT imports, cross-tool signature semantics,
  safe treatment of ignored source artifacts, and hosted runner/Docker availability.
- Unresolved decisions: none. Protected release configuration must supply the two named signing
  secrets before a non-snapshot deployment; that is an operational prerequisite.

#### P1 root implementation prompt - SUPERSEDED, DO NOT RUN

The parent blueprint's sizing gate rejected one P1 implementation context. No
`hardware-utils-overhaul/phase-1-native-build-implementation` branch may be created. Use the two
sequential child lifecycles below.

#### P1-A native graph/JNI/signing blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After the parent P1 blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-1-native-graph-blueprint` from the updated P1 root. The parent
> artifact is
> `docs/blueprints/hardware-utils/phase-1-native-build-jni-packaging.md`. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the completed P0 artifact-index
> files/closeout, the parent blueprint's Child A bounded context envelope, and only its named
> native/JNI/Gradle/tool inputs. Reinspect the installed Zig 0.16 APIs and rcodesign 0.29.0 command
> surface. Do not inspect training or `JNIClassLoader`.
>
> Write `docs/blueprints/hardware-utils/phase-1-native-graph-jni-signing.md`. Translate the parent
> contract into an implementation checklist in dependency order: tool input pinning; tracked
> native relocation and user-owned ignored-artifact fingerprint; strict JSON parser/schema;
> recursive discovery; exact targets/flags; generated JNI declarations and target `jni_md.h`;
> `JNI_OnLoad`; independent compile/sign/verify/install nodes; generated TSV; target caches and
> staging; Gradle `javac -h`, cleanup, Zig, and copy-resource ordering; P0 source-root update;
> tracked `build.sh` deletion; exact existing-workflow invalid-header removal plus explicit
> SDK/Zig/signer/LLVM/credential-file setup; focused tests; and exact failure/validation commands.
> Preserve N01, N02, and the named legacy macOS export as exact exceptions; do not change either
> existing workflow's Gradle command or unrelated behavior.
>
> Reapply the sizing gate and the implementation-model reassessment. The selected implementation
> is `gpt-5.6-sol` with `high` reasoning; confirm it or update this plan before handoff. Allowed
> edits are this child blueprint, the parent plan, and planning documentation only. The output is
> an implementation-ready child blueprint and any required prompt correction. Handoff for review
> and merge only when no implementation choice remains; do not create the implementation child
> before that merge.

#### P1-A native graph/JNI/signing implementation prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P1-A blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-1-native-graph-implementation` from the P1 root. The parent
> artifact is the finalized blueprint/completion file
> `docs/blueprints/hardware-utils/phase-1-native-graph-jni-signing.md`. Inspect
> `git status --short`; read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the summarized parent contract,
> and the exact Child A context envelope. Confirm the child blueprint retained this model
> selection.
>
> Implement only Child A's native graph, JNI, signing, Gradle staging, relocated compatibility
> tests, tracked migration, and exact existing-workflow native setup. Never delete, move, clean,
> or rewrite ignored source binaries or source caches. Do not edit the loader, add the
> hardware-specific workflow, change existing workflow Gradle commands/unrelated behavior, alter
> platform semantics/core, or touch training. Allowed documentation edits are the completion
> record appended to the child blueprint and the compact temporary P1 block in `AGENTS.md`.
>
> Run direct Zig validation, clean and repeated selected-module package/verify commands, malformed
> manifest cases, recursive discovery cases, generated-header/ABI checks, all eight static binary
> gates available locally, signed-staged-copy checks, exact jar/catalog inventory, source
> fingerprints, timing, `git diff --check`, and scope checks. If a schema/ABI/signing/staging
> choice is missing, stop and return to blueprint. Otherwise append changed files, commands,
> results, acceptance evidence, deviations, and environmental limits.
>
> Handoff only when the module lifecycle produces the exact eight products and catalog under
> `target`, the signed macOS outputs are the staged outputs, source artifacts are unchanged, and
> P0 compatibility passes. Handoff for P1-A conformance review and manual review.

#### P1-A native graph/JNI/signing validation prompt - SUPERSEDED, DO NOT RUN

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P1-A implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-1-native-graph-validation` from the P1 root. The parent artifact
> is the P1-A implementation completion record. Read only the completed P0 summary/artifacts, the
> parent P1 contract, P1-A blueprint/completion and implementation diff, and the exact Child A
> inputs/outputs. Do not inspect training or loader/CI code.
>
> Independently rerun strict manifest/discovery failures, clean/warm/repeated universal builds,
> generated JNI widths and declaration inventory, N01/N02 exception exactness, graph independence,
> target flags, imports/exports/versions/deployment metadata, macOS signing and staged digest
> identity, catalog determinism, exact resources, P0 compatibility, timing, ignored-artifact
> fingerprints, and scope checks. Make only minor blueprint-settled corrections.
>
> Write
> `docs/validations/hardware-utils/phase-1-native-graph-jni-signing-validation.md` with commands,
> results, fixes, skips, limits, and an acceptance matrix; append its summary to the completion
> record and update the temporary status block. New architecture returns to blueprint. Merge this
> validation before P1-A audit.

#### P1-A native graph/JNI/signing audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P1-A implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-1-native-graph-audit` from the P1 root. The parent artifact is
> the P1-A completion record and implementation evidence. Read the
> summarized parent contract and exact P1-A blueprint/completion, diff, tests, and conformance/manual-review evidence.
> Do not inspect training or expand into P1-B ownership.
>
> Independently classify every Child A requirement and its portions of B01-B04, B06, and B07 as
> `satisfied`, `deviated`, `unverified`, or `ambiguous`. Verify source preservation, strict
> inventory, ABI exceptions, signing DAG, staged identity, hardening, binary floors, and evidence
> quality. Minor blueprint-settled corrections are allowed with rerun evidence; redesign returns
> to the exact child blueprint.
>
> Write
> `docs/audits/hardware-utils/phase-1-native-graph-jni-signing-conformance.md`, update the child
> completion/conformance summaries and temporary status block, and hand off for merge. P1 remains
> open; do not remove the status block or start P2. Only after this audit merges may P1-B start.

#### P1-B loader/package/CI blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After the P1-A audit is reviewed and merged, create
> `hardware-utils-overhaul/phase-1-loader-package-blueprint` from the updated P1 root. The parent
> artifact is
> `docs/blueprints/hardware-utils/phase-1-native-build-jni-packaging.md`. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, completed P0 artifacts/
> closeout, the parent blueprint's Child B envelope, and the exact P1-A blueprint/completion,
> conformance/manual-review, catalog/staging handoff, and relevant diff. Read only the existing loader,
> module packaging/test wiring, Child A's summarized existing-workflow native setup, and the new
> hardware-workflow path. Do not inspect training.
>
> Write `docs/blueprints/hardware-utils/phase-1-loader-gradle-packaging.md`. Translate the frozen
> TSV/staging contract into an implementation checklist for immutable catalog parsing, generic
> alias/product selection, allowed fallback taxonomy, owner-private bounded extraction, POSIX/
> Windows permissions, immediate/deferred/stale cleanup, noexec diagnostics, load seam and class
> initialization, exact jar/digest/binary/signature/warm-removal gates, smoke bundle/matrix,
> Failsafe wiring, selected-module workflow, and protected credential-file setup. Name every
> filesystem safety assertion and failure test. Do not change the manifest/schema, product graph,
> ABI, flags, or signing order.
>
> Reapply the sizing and implementation-model gates. The selected implementation is
> `gpt-5.6-sol` with `high` reasoning; confirm it or update this plan before handoff. Edit only
> planning documentation. Handoff for review and merge only when no loader, cleanup, package,
> runner, or signing-secret decision remains. Do not create implementation before that merge.

#### P1-B loader/package/CI implementation prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P1-B blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-1-loader-package-implementation` from the P1 root. The parent
> artifact is the finalized blueprint/completion file
> `docs/blueprints/hardware-utils/phase-1-loader-gradle-packaging.md`. Inspect status and read the
> exact Child B context envelope, summarized parent contract, and P1-A handoff. Confirm the child
> blueprint retained this model selection.
>
> Implement only the internal catalog/loader/extraction code, loader/package/binary/smoke tests,
> narrow module POM test wiring, and hardware-specific selected-module workflow. Treat Child A's
> existing-workflow native setup as read-only unless a missing settled gate requires a minor
> correction; never alter its Gradle commands or unrelated behavior. Do not alter the native
> manifest/graph/ABI/signing policy, platform semantics, core, unrelated files, ignored source
> artifacts, or training. Append completion evidence to the P1-B blueprint and update only the
> temporary P1 `AGENTS.md` block.
>
> Run unit failure matrices, clean/warm/repeated selected-module verify, isolated manifest-removal
> rebuild, exact jar/digest/static binary/signature gates, glibc and available musl/Windows/macOS
> smoke gates, source fingerprints, timing, diff checks, and workflow command/scope assertions.
> New design returns to blueprint. Handoff only when the loader has no hardcoded product table,
> fallback and cleanup are deterministic/safe, packaged bytes pass all available gates, and CI
> selects no training. Handoff for P1-B conformance review and manual review.

#### P1-B loader/package/CI validation prompt - SUPERSEDED, DO NOT RUN

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P1-B implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-1-loader-package-validation` from the P1 root. The parent artifact
> is the P1-B completion record. Read the summarized parent contract, P1-A handoff artifacts, and
> exact P1-B blueprint/diff/tests/completion. Do not inspect training or redesign native inputs.
>
> Independently rerun catalog parser/alias/fallback/error taxonomy, zero/oversize copy, POSIX and
> Windows permissions, class-initialization publication, immediate/shutdown/stale cleanup safety,
> noexec diagnostics, exact package and staged digest, warm-removal, all static binary/signature
> gates, required available runner smoke, selected-module CI assertions, release secret
> non-disclosure, P0 compatibility, timing, fingerprints, and scope checks. Make only minor
> blueprint-settled corrections.
>
> Write
> `docs/validations/hardware-utils/phase-1-loader-gradle-packaging-validation.md`, append its
> summary to the completion record, and update the status block. Record unavailable external
> runners as explicit B06 `unverified` portions. Merge before P1-B audit.

#### P1-B loader/package/CI audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P1-B implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-1-loader-package-audit` from the P1 root. The parent artifact is
> `docs/validations/hardware-utils/phase-1-loader-gradle-packaging-validation.md`. Read the
> summarized parent contract, P1-A handoff summary, and exact P1-B blueprint/completion, diff,
> tests, and conformance/manual-review evidence. Do not inspect training.
>
> Independently classify every Child B requirement and its B03-B06 portions. Audit unknown-arch
> rejection, fallback exception boundary, extraction ownership and bounded cleanup, noexec
> honesty, class-init publication, exact package/digest/signature evidence, runner classification,
> workflow scope, and secret handling. Minor blueprint-settled corrections require rerun evidence;
> redesign returns to blueprint.
>
> Write
> `docs/audits/hardware-utils/phase-1-loader-gradle-packaging-conformance.md`, update the child
> records/status block, and hand off for root conformance checking and manual review. The root audit
> records the final P1 disposition.

#### P1 root integration validation prompt - SUPERSEDED, DO NOT RUN

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> SUPERSEDED by the developer-authorized conformance check and manual review. Do not create or run
> this validation action. After both child audits are reviewed and merged, the conformance review
> consumes the same implementation surfaces directly. The former prompt was:
>
> After both child audits are reviewed and merged, create
> `hardware-utils-overhaul/phase-1-native-build-integration-validation` from the P1 root. The
> parent artifacts are the P1 parent blueprint and both child blueprint/completion, validation,
> and audit triples in the artifact index. Inspect `git status --short`. Read `AGENTS.md`,
> `docs/AGENT_WORKFLOW.md`, the completed P0 entry/closeout, the parent blueprint's summarized
> context and 24 acceptance criteria, both child handoff summaries/diffs, exact package
> inventories, and tests. Do not inspect or run training.
>
> Validate the combined producer/consumer boundary rather than repeating only child-local checks:
> JSON -> source graph -> generated JNI -> signed stage -> TSV -> classpath -> jar -> catalog
> selection -> extraction -> runtime load. Run clean, warm, repeated, and isolated
> manifest-removal builds; P0 compatibility; strict manifest/catalog failures; all eight static
> binary/digest/signature gates; loader failure matrices; available glibc, musl, Windows, and
> macOS smoke/signature gates; source/user-artifact fingerprints; selected-module workflow
> checks; timing; diff checks; and the exact acceptance matrix. Verify existing workflow Gradle
> commands are unchanged and no P1 command selects training.
>
> Allowed edits are the root integration record, temporary P1 status block, and minor
> blueprint-settled cross-child test/naming/wiring corrections. A JSON/TSV, ABI, signing,
> extraction, package, or runner-policy decision returns to the parent or owning child blueprint.
> Write
> `docs/validations/hardware-utils/phase-1-native-build-jni-packaging-integration-validation.md`
> with commands, results, fixes, skips, limits, the 24-item matrix, and B06 carry. Handoff for
> review and merge only when every available combined gate passes and every unavailable platform
> runtime portion is explicit. Append a compact root integration completion summary to the parent
> blueprint and merge before the root audit.

#### P1 conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the conformance check and manual review are complete, start
> `hardware-utils-overhaul/phase-1-native-build-audit` from the P1 root. The parent artifact is
> the P1 parent blueprint, implementation records, and conformance/manual-review record; no
> validation artifact is required.
> Ownership is limited to independent P1 root conformance and minor blueprint-settled corrections.
> Inspect `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, completed P0
> artifacts/closeout, the parent P1 blueprint and review summary, both child
> blueprint/completion/conformance/manual-review summaries, the root conformance record, final P1 diff,
> package inventories, and relevant tests. Do not inspect or run training.
>
> Independently evaluate every P1 requirement and the implementation/conformance evidence for deterministic
> discovery, source-tree non-mutation, manifest failures, JNI ABI, jar/loader coverage, fallback
> and extraction behavior, hardening, graph independence, signing, architectures, exports,
> imports, deployment targets, runtime floors, and timing claims. Allowed edits are
> `docs/audits/hardware-utils/phase-1-native-build-jni-packaging-conformance.md`, completion and
> conformance/manual-review records, the P1 closeout summary in this plan, the temporary phase-status block, and
> minor blueprint-settled corrections. If a correction is made, rerun and record the affected
> conformance check. Redesign, new ABI/manifest decisions, unrelated files, and training are prohibited.
>
> The output artifacts are the audit above, child/root record corrections, P1 closeout summary in
> this plan, and, after the authorized merge, removal of the temporary P1 status block on the root
> with the resulting root commit recorded when committed. Classify all 24 parent acceptance
> criteria, both child requirements, B01-B05, B07, and the P1 B06 gate framework exactly as
> `satisfied`, `deviated`, `unverified`, or `ambiguous`, with evidence; carry only the exact named
> architecture/runtime B06 portions to P5-P7. Append commands, results, fixes, skips, and limits
> to the applicable completion/root record. A material ABI, manifest, signing, loader, packaging,
> or CI deviation returns to the exact parent/child action. Handoff follows the audit/root-closeout
> contract: P1 is complete only after the authorized audit merge, P1 status-block removal,
> closeout-summary update, and resulting root commit record; do not create P2 earlier.

#### P1 closeout summary

P1 is complete under the developer-authorized conformance-only workflow. Validation was removed
from the global workflow; conformance checking and manual review are the sole verification path.
The direct audit recorded 21 satisfied criteria, criteria 19/24 unverified, criterion 22 deviated,
Child A ambiguous because its historical artifact chain is absent, Child B satisfied, and B01-B05,
B07, and the P1 B06 gate framework satisfied. No production correction was made. The audit is
`docs/audits/hardware-utils/phase-1-native-build-jni-packaging-conformance.md`; no P2 work was
created.

### P2 - validated topology and immutable snapshot foundation

#### P2 parent blueprint prompt - COMPLETED, REVIEW AND MERGE REQUIRED

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

The completed output is
`docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md` on
`hardware-utils-overhaul/phase-2-topology-snapshot-blueprint`. It froze the provider/model/facade/
mapper/snapshot contracts, applied the split and implementation-model gates, and did not modify
production code. Review and merge it into `hardware-utils-overhaul/phase-2-topology-snapshot`
before creating any P2-A branch.

#### P2 developer-review summary

- Purpose: install a validated deterministic topology foundation and deeply immutable public
  topology-indexed snapshots without changing the public Java/module/mask surface or core code.
- Ownership: the parent contract spans hardware root/common/internal topology and snapshot code,
  layout adapters, and hardware tests. P2-A owns `SystemInfo`, the unexported topology model,
  adapter boundaries, and topology fixtures. P2-B owns `TopologyMapper`, `SystemUtilization`, the
  wrappers, and snapshot/remap fixtures.
- Key contracts: Linux logical CPU IDs remain kernel IDs; Windows IDs are
  `group * 64 + processor`; macOS/fallback IDs are deterministic ordinals. Global sockets/cores
  are dense and sorted from source identity including die. `CPU_COUNT` is the logical CPU index
  span, core/socket counts are dense cardinalities, and inactive array/list positions are null
  holes while every active entry is complete. Missing caches use exact core-local L1/L2 and
  socket-local L3 defaults. Provider and public storage is defensively owned; snapshot equality
  and hash use every component. Mapper requests are sequence-coalesced, versions change only for
  actually published membership, and one volatile topology write/read publishes the whole graph.
  Core zero means global core ID zero and is removed only when another allowed/effective core
  remains.
- Children: P2-A `phase-2-topology-model-*` must complete and merge before P2-B
  `phase-2-snapshot-publication-*`. Each has blueprint, implementation, and combined conformance/
  manual-review audit actions. There is no root implementation or validation branch.
- Selected implementation model: `gpt-5.6-sol` with `high` reasoning for both P2-A and P2-B,
  subject to mandatory confirmation by each child blueprint. Child/root audits use the same model
  and effort.
- Risks: sparse IDs can enlarge bounded arrays; static initialization can recurse; mutable record
  component types require accessor as well as constructor protection; coalescing intentionally
  hides intermediate states; common Windows/macOS fixtures do not prove P6/P7 platform parity;
  full verify depends on the completed P1 native toolchain.
- Unresolved decisions: none. ID semantics, count/index meanings, null holes, cache fallbacks,
  copy boundaries, equality, fallback behavior, core-zero policy, version rules, and publication
  modes are settled in the parent blueprint.

#### P2 root implementation prompt - SUPERSEDED, DO NOT RUN

The sizing gate rejected one P2 implementation context. Do not create
`hardware-utils-overhaul/phase-2-topology-snapshot-implementation`. Use the P2-A and P2-B action
families below, sequentially from the updated P2 root.

#### P2-A topology model/adapters blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After the parent P2 blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-2-topology-model-blueprint` from the updated P2 root. The parent
> artifact is `docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md`. Inspect
> `git status --short` and preserve unrelated changes. Read `AGENTS.md`,
> `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, the parent blueprint's P2-A context envelope,
> the plan's completed P0-P1 artifact-index files/closeouts, `SystemInfo`, all layout adapters,
> current Windows relationship types only at the adapter boundary, and existing topology/cache
> tests. The developer deliberately deleted the P1-A/child-audit historical files; do not
> reconstruct them. Do not inspect training.
>
> Write `docs/blueprints/hardware-utils/phase-2-topology-model-adapters.md`. Translate the frozen
> internal `TopologyProvider -> TopologyInput -> TopologyNormalizer -> TopologyModel -> SystemInfo`
> contract into one bounded implementation checklist. Preserve the exact logical/global identity,
> count/index, cache fallback, active completeness, whole-model initialization fallback, resource-
> provider separation, ordering, allocation bound, ownership, and fixture contracts. Detailed
> Linux/Windows/macOS collection/native parity remains P5-P7. `TopologyMapper`, public snapshot
> implementation, resource/pressure behavior, affinity/executor, core production, and training are
> read-only/prohibited.
>
> Reapply the sizing gate and the implementation-model reassessment. Confirm the parent-selected
> `gpt-5.6-sol`/`high` implementation or update this plan before handoff; do not silently
> downgrade. Edit only the child blueprint, this plan if the gate changes, and closely related
> planning docs. Handoff for review and merge only when implementation must choose no provider,
> identity, fallback, cache, count/index, ownership, or static-initialization rule. Do not create
> implementation before that merge.

#### P2-A developer-review summary

- Purpose: install the common owned topology input/model/normalizer and make `SystemInfo` initialize
  from one complete deterministic platform model or one whole-model fallback.
- Ownership: P2-A owns `SystemInfo`, one unexported internal topology package, the common
  collection/
  projection boundaries of all three layout singletons, current Windows relationship values only
  after parsing, and five deterministic topology fixture families. Mapper/snapshots, resources,
  pressure, affinity/executor, native parity, core, and training remain prohibited.
- Key contracts: Linux IDs remain kernel IDs; Windows uses unsigned `group * 64 + processor`;
  macOS/fallback uses ordinals. Socket/core IDs are dense and source-key sorted, `CPU_COUNT` is the
  logical index span, active entries are complete, and invalid/missing cache data receives exact
  core-local L1/L2 or socket-local L3 fallbacks. Provider and model storage are copied twice at
  trust boundaries. A hidden immutable map carrier gives `SystemInfo` the layout's exact model
  without changing an existing descriptor, exporting the model, or creating a mutable registry.
  Topology publishes through JVM class initialization before resource-provider construction.
- Work unit: one implementation and one combined conformance/manual-review audit remain. The child
  sizing gate passes without another split because collection, normalization, fallback, projection,
  and facade bootstrap form one one-time lifecycle; detailed platforms are already split to P5-P7.
- Selected implementation: confirms `gpt-5.6-sol` with `high` reasoning. No lower-effort evidence
  exists for the coupled sparse/group identity, ownership, bounds, and static-initialization work.
- Risks: sparse allocation pollution is rejected before allocation; Windows efficiency hints remain
  conservative until P6; macOS remains synthetic until P7; final verify depends on P1 native tools.
- Unresolved decisions: none. Provider shapes, identity/order, cache selection, model bridge,
  fallback, ownership, failure behavior, test seams, and initialization order are settled in the
  child blueprint.

#### P2-A topology model/adapters implementation prompt

**Child-confirmed model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P2-A blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-2-topology-model-implementation` from the updated P2 root. Read
> `AGENTS.md`, the plan's P2 summary, the parent blueprint's P2-A envelope, and only the exact
> inputs named by the child blueprint. Inspect status first and confirm the model gate is final.
>
> Implement only the internal topology input/model/normalizer/bootstrap, common-boundary layout
> adapter changes, `SystemInfo` projection/fallback, and P2-A deterministic fixtures/tests. Preserve
> public shapes/exports/masks and the P2-B ownership boundary. Do not change mapper/snapshots,
> pressure/monitor behavior, detailed platform/native collection, affinity/executor, core, or
> training. Append the completion record to the P2-A blueprint and add/update only the temporary
> P2 `AGENTS.md` status block.
>
> Run the child blueprint's direct deterministic tests, P0 API/mask gate, final hardware verify,
> read-only core compile/test gate, and scope/diff checks. A new ID, cache, fallback, count/index,
> or initialization decision returns to the parent/child blueprint. Handoff for conformance/manual
> review only when every active CPU has a deterministic complete projection, sparse/group IDs are
> safe, missing caches are exact, fallback is complete, and provider buffers cannot alias the
> model. Merge implementation before its audit.

#### P2-A topology model/adapters conformance/manual-review prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P2-A implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-2-topology-model-audit` from the updated P2 root. The parent
> artifact is the P2-A blueprint and completion record; there is no validation artifact. Read only
> the summarized parent contract, exact P2-A context/diff/tests/completion, P0 compatibility
> contract, and relevant code. Do not inspect training or expand into P2-B.
>
> Independently classify every P2-A requirement and common P2 portions of T01-T03/T05/T06. Audit
> deterministic global socket/die/core identity, Linux sparse/duplicate-local-core handling,
> Windows group/bit-63 identity, macOS/common fallback, cache completion, count/index meanings,
> immutable provider ownership, initialization cycles, and API/core compatibility. Make only minor
> blueprint-settled corrections and rerun affected gates; a design choice returns to blueprint.
>
> Write `docs/audits/hardware-utils/phase-2-topology-model-adapters-conformance.md`, append command/
> fix/limit evidence to the completion record, update only the P2 status block, and hand off for
> review and merge. Do not start P2-B before this audit merges.

#### P2-B immutable snapshots/remap publication blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After the P2-A audit is reviewed and merged, create
> `hardware-utils-overhaul/phase-2-snapshot-publication-blueprint` from the updated P2 root. The
> parent artifact is `docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md`. Inspect
> status. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, the parent P2-B
> context envelope, the P2-A blueprint/completion/conformance summary and final public model diff,
> P0 compatibility contract, `TopologyMapper`, `SystemUtilization`, both wrappers, existing
> topology/snapshot tests, the `ResourceMonitor` mapper call boundary, and only the named core
> index/version/snapshot consumers. Do not inspect training.
>
> Write `docs/blueprints/hardware-utils/phase-2-snapshot-remap-publication.md`. Translate the
> frozen defensive-copy, accessor ownership, content equality/hash, active entry, named field,
> allowed-mask, core-zero, fixed null-hole shape, global/socket version, sequence-coalescing, and
> volatile publication contracts into one bounded implementation checklist and deterministic race
> matrix. Do not reopen P2-A identity/model design or change pressure, monitor lifecycle, affinity/
> executor, detailed platform work, core production, or training.
>
> Reapply the sizing and implementation-model gates. Confirm the parent-selected
> `gpt-5.6-sol`/`high` implementation or update the plan before handoff. Edit only planning docs.
> Handoff and merge only when implementation needs no copy, equality, array-span, null-hole,
> arithmetic, coalescing, version, core-zero, or publication-mode decision. Do not create
> implementation before merge.

#### P2-B developer-review summary

- Purpose: make public utilization/snapshot values deeply owned and content-correct, and publish
  mapper membership as one immutable fixed-shape topology with exact coalescing and versions.
- Ownership: P2-B owns `TopologyMapper`, `SystemUtilization`, `UnmodifiableBitSet`,
  `UnmodifiableDoubleArray`, and focused wrapper/snapshot/remap/race tests. P2-A model/adapters,
  ResourceMonitor lifecycle/pressure, affinity/executor, core production, detailed platforms,
  native sources, and training remain read-only or prohibited.
- Key contracts: canonical record constructors copy mutable values and nested array accessors
  clone; equality/hash includes every component. Socket arrays use the global core span and core
  arrays use logical CPU indexes with exact null holes. Named memory fields use nonnegative,
  saturating arithmetic. Mapper candidates intersect model, allowed, and utilization masks before
  reserving global core zero only when another core remains. Greatest-sequence requests drain
  through release/recheck; global and persistent socket versions count actual membership
  publications, including socket deactivation/reactivation. One volatile topology write/read
  publishes the complete graph and its version.
- Work unit: one implementation and one combined conformance/manual-review audit remain. The child
  stays unsplit because wrapper/snapshot ownership and mapper publication share the same active-ID,
  fixed-null-hole, mutation, and race acceptance boundary.
- Selected implementation: confirms `gpt-5.6-sol` with `high` reasoning. Lower effort is not
  supported by the coupled public-value, sparse-index, exact-arithmetic, coalescing, version, and
  Java Memory Model repair surface.
- Risks: sparse but bounded arrays must not become cardinality-indexed; canonical constructors and
  accessors both need ownership protection; coalescing intentionally hides intermediate states;
  inactive socket versions persist privately; final verify depends on P1 native tools.
- Unresolved decisions: none. Copy/accessor behavior, equality/hash, named fields, arithmetic,
  spans/null holes, allowed/core-zero membership, coalescing/failure cleanup, versions/overflow,
  and volatile publication are settled in the child blueprint.

#### P2-B immutable snapshots/remap publication implementation prompt

**Child-confirmed model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P2-B blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-2-snapshot-publication-implementation` from the updated P2 root.
> Inspect status; read the exact P2-B context envelope, summarized parent contract, and P2-A
> handoff. Confirm the child model gate is final.
>
> Implement only wrapper ownership/value semantics, public snapshot construction/access/equality/
> named-value corrections, `TopologyMapper` allowed-mask/core-zero/coalescing/version/publication
> logic, and deterministic P2-B fixtures/tests. Do not alter the P2-A model/adapters, resource/
> pressure/monitor behavior, affinity/executor, detailed native/platform collection, core
> production, or training. Append completion evidence to the P2-B blueprint and update only the
> temporary P2 status block.
>
> Run the direct P2-B test matrix, P0 API/mask/core-zero gates, complete hardware verify, read-only
> core tests, and scope/diff checks. A new public field meaning, copy boundary, version rule, or
> memory mode returns to blueprint. Handoff only when old publications resist all source/accessor
> mutation, equality/hash are content-consistent, active snapshot entries are complete, final
> coalesced membership cannot be lost, versions are exact, and volatile publication is evidenced.
> Merge implementation before its audit.

#### P2-B immutable snapshots/remap publication conformance/manual-review prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P2-B implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-2-snapshot-publication-audit` from the updated P2 root. The parent
> artifact is the P2-B blueprint and completion record; there is no validation artifact. Read only
> the summarized parent/P2-A handoff and exact P2-B context, diff, tests, and completion. Do not
> inspect training.
>
> Independently classify every P2-B requirement and its T04-T06/T05 portions. Audit deep copies on
> canonical constructors and accessors, wrapper and nested record equality/hash, named/indexed
> values, active completeness, allowed-mask ownership, exact core-zero intersections, fixed null
> holes, pressure-independent versions, deactivate/reactivate socket versions, no-lost-newest
> coalescing, and volatile happens-before. Make only minor blueprint-settled corrections with rerun
> evidence; redesign returns to blueprint.
>
> Write `docs/audits/hardware-utils/phase-2-snapshot-remap-publication-conformance.md`, append
> commands/fixes/skips/limits to the completion record, update the P2 status block, and hand off for
> review and merge before the root audit.

#### P2 root conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After both child audits are reviewed and merged, create
> `hardware-utils-overhaul/phase-2-topology-snapshot-audit` from the updated P2 root. The parent
> artifacts are the P2 parent blueprint and the exact P2-A/P2-B blueprint/completion/conformance
> triples in the phase artifact index. There is no validation record. Inspect status. Read
> `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, completed P0-P1 indexed artifacts/
> closeouts, the parent acceptance matrix and summarized context, both child handoffs/diffs, and
> relevant tests. Do not inspect or run training.
>
> Independently audit the combined provider -> normalized model -> `SystemInfo` -> mapper -> public
> snapshot flow. Classify all 15 parent criteria and common P2 portions of T01-T06 exactly as
> `satisfied`, `deviated`, `unverified`, or `ambiguous`; carry only detailed platform collection/
> value portions to P5-P7. Recheck API/module/mask compatibility, deterministic topology/cache
> fixtures, mutation/equality/index fixtures, core-zero/remap/version/concurrency fixtures, full
> hardware selected-module verification, read-only core compatibility, memory/publication
> arguments, scope, and diff hygiene.
>
> Allowed edits are `docs/audits/hardware-utils/phase-2-topology-snapshot-model-conformance.md`,
> child/parent completion summaries, this plan's P2 closeout summary, the temporary P2 status
> block, and minor blueprint-settled corrections. Rerun affected checks after a correction. New
> identity, fallback, copy, version, or publication decisions return to the owning blueprint;
> unrelated files and training are prohibited.
>
> Handoff a review-ready audit first. After explicit merge/closeout authorization, merge the audit
> child, switch to the P2 root, remove only the temporary P2 status block, append the root branch/
> commit and final classifications to the P2 closeout summary, and record the resulting root commit
> when committed. P2 is complete only after that authorized closeout; do not create P3 earlier.

#### P2 closeout summary

The developer's authorization to begin P3 designates
`hardware-utils-overhaul/phase-2-topology-snapshot` at `e2495c5d` as the completed P2 predecessor.
The root contains the P2-A and P2-B implementations/conformance records plus the root conformance
record and its minor blueprint-settled record-method flag correction. The inherited root audit
classifies criteria 2-10 and 12-14 plus T01-T03/T05/T06 satisfied; criteria 1, 11, and 15 plus T04
remain unverified because the pinned API/native/core gates and deterministic R2-R12 mapper race
matrix were not evidenced in that audit environment. P3 does not reclassify those limits and owns
neither topology publication nor the missing P2 race tests.

### P3 - affinity capability and executor lifecycle

#### P3 parent blueprint prompt - COMPLETED AND MERGED

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

The completed output is
`docs/blueprints/hardware-utils/phase-3-affinity-executor-lifecycle.md` on
`hardware-utils-overhaul/phase-3-affinity-executor-blueprint`. It freezes capability, masks,
managed ownership, leases/restoration, executor state, registry, cleaner/hook, deadline, and Java
Memory Model contracts. Its sizing gate splits P3 into sequential P3-A and P3-B children and
prohibits a root implementation or validation branch. The reviewed parent is merged into
`hardware-utils-overhaul/phase-3-affinity-executor` at `7d3abea7`; P3-A was created from that root.

#### P3 developer-review summary

- Purpose: make common affinity behavior operationally truthful and make the pinned executor a
  linearizable, concurrent fresh-thread executor with bounded global cleanup.
- Ownership: the parent spans hardware root/internal affinity and lifecycle, three Java affinity
  facades, and focused hardware tests. P3-A owns the additive enum/query, `ThreadTools`, internal
  pinner/controller/lease/managed-owner roles, platform Java facade conformance, and affinity
  tests. P3-B owns `PinnedThreadExecutor`, bounded registry/cleanup support, and lifecycle/race/
  cleanup tests. Native implementations, resources/pressure, topology production, core
  production, and training remain read-only or prohibited.
- Key contracts: `AffinityCapability` is exactly `EXACT`, `LOCALITY_HINT`, or `UNSUPPORTED` and
  `ThreadTools.getAffinityCapability()` reports the operational common path. Requests are copied,
  bounded to the P2 logical span, all-or-nothing, and never partially intersected into success.
  Exact work captures/restores the calling thread's first original binding; macOS applies one
  representable hint and releases tag zero. Managed IDs are scoped tokens, not physical claims.
  The executor has RUNNING/SHUTDOWN/CLOSED states, one NEW thread per accepted execute, lock-
  linearized start/execute/shutdown, truthful instant termination, overflow-safe await, exact
  registry identity removal, noncapturing cleaner action, one bounded hook, and gated `closeAll`.
- Children: P3-A `phase-3-affinity-capability-*` completes and merges before P3-B
  `phase-3-executor-lifecycle-*`. Each has blueprint, implementation, and combined conformance/
  manual-review audit actions. The root has no implementation/validation action; one root audit
  follows both children.
- Selected implementation model: `gpt-5.6-sol` with `high` reasoning for both P3-A and P3-B,
  subject to mandatory confirmation/increase by each child blueprint. Child/root audits use the
  same model and effort.
- Risks: exact apply cannot outrun safe capture/restoration; P3 macOS locality grouping may be
  narrower than P7; restart makes termination an instant observation; arbitrary thread creators
  require create-outside/start-inside locking; interrupt-ignoring tasks retain closed tombstones;
  cleaner/hook tests require deterministic seams; final verify depends on P1 native tools.
- Unresolved decisions: none. Public naming, masks, capability meaning, owner/lease cleanup,
  lifecycle transitions, task acceptance, registry overlap, deadlines, interruption, hooks,
  cleaner reachability, memory modes, split order, and implementation capability are settled.

#### P3 root implementation prompt - SUPERSEDED, DO NOT RUN

The sizing gate rejected one P3 implementation context. Do not create
`hardware-utils-overhaul/phase-3-affinity-executor-implementation`. Use the P3-A and P3-B action
families below, sequentially from the updated P3 root.

#### P3-A affinity capability blueprint prompt - COMPLETED AND MERGED

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After the parent P3 blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-3-affinity-capability-blueprint` from the updated P3 root. The
> parent artifact is
> `docs/blueprints/hardware-utils/phase-3-affinity-executor-lifecycle.md`. Inspect status. Read
> `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, the parent's exact P3-A context
> envelope, the completed P0-P2 artifact-index/closeout summaries, P0 A01/API/mask evidence, P2
> logical-ID/mask/span summary, `ThreadTools`, `internal.ThreadPinner`, the three Java affinity
> facades/native declarations, focused tests, and only named current-CPU call locations. Do not
> inspect training, native implementation bodies, executor internals, or resource/pressure code.
>
> Write `docs/blueprints/hardware-utils/phase-3-affinity-capability.md`. Translate the frozen enum/
> query, operational capability, unsigned mask, exact/locality/unsupported matrix, non-destructive
> base discovery, first-original lease/restoration, tag-zero release, managed-owner nesting,
> current-CPU fallback, ownership, failure, memory, and deterministic fake-provider contracts into
> one bounded implementation checklist. Preserve every existing descriptor/export and defer
> detailed native platform parity to P5-P7.
>
> Reapply the sizing and implementation-model gates. Confirm parent-selected
> `gpt-5.6-sol`/`high` or update this plan before handoff. Edit only child blueprint/plan/planning
> docs. Handoff only when implementation must choose no capability, mask, platform-call,
> restoration, release, ownership, current-CPU, memory-mode, or test-seam rule. Merge before
> implementation and do not start P3-B.

#### P3-A developer-review summary

- Purpose: replace destructive/null-unsafe affinity setup with one truthful common controller,
  bounded unsigned requests, first-original restoration, conservative locality release, and scoped
  managed logical ownership.
- Package boundary: P3-A owns the additive root enum/query, `ThreadTools`, unexported affinity
  controller/provider/value roles, the three Java affinity facades, and focused deterministic
  tests. Module directives, legacy exported `common.ThreadPinner`, JNI declarations/bodies,
  executor lifecycle, resource/pressure code, topology production, core production, and training
  are unchanged or prohibited.
- Key contracts: P3 Linux/Windows common capability remains `UNSUPPORTED` until P5/P6 supply exact
  capture/restore; macOS is a conservative single-ordinal `LOCALITY_HINT` with tag-zero release
  until P7. Every request is copied, bounded by the P2 span/active mask, all-or-nothing, and bit-63
  safe. Exact leases preserve the first original snapshot; nested managed tokens are owner-thread,
  LIFO, idempotent, and independent of placement. Current CPU is exact-provider-first, then managed
  fallback, otherwise `-1`/null.
- Work unit and tests: one implementation checklist covers the controller plus thin facade adapters.
  Instance fakes prove exact and multi-CPU locality semantics; package-local raw-call seams prove
  zero partial facade calls. A01, mutation, release failure, nesting/wrong-thread, API/JNI/mask,
  hardware, core-compatibility, and scope gates are explicit.
- Sizing/model: the child remains one irreducibly coupled but bounded responsibility. The
  parent-selected implementation remains **`gpt-5.6-sol` with `high` reasoning**; no downgrade is
  justified by the remaining mask, failure, ownership, and three-facade coupling.
- Risks/unresolved items: hosted native capability is not semantic evidence and detailed platform
  parity remains deferred. No capability, mask, platform-call, restoration, release, ownership,
  current-CPU, memory-mode, or fake-seam choice remains unresolved.

#### P3-A affinity capability implementation prompt - COMPLETED AND MERGED

**Child-confirmed model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P3-A blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-3-affinity-capability-implementation` from the updated P3 root.
> Inspect status and confirm the child model gate is final. Read only the parent-summary/P3-A
> context envelope, finalized child blueprint, P0 A01/API/mask contract, and P2 ID/span summary.
>
> Implement only `AffinityCapability`, `ThreadTools`/bounded internal affinity roles, three Java
> facade common-contract changes, and deterministic P3-A tests. Do not edit native implementation,
> executor lifecycle, resources/monitor/pressure, topology production, core production, CI,
> benchmarks, or training. Append completion evidence to the child blueprint and update only the
> temporary P3 status block.
>
> Run the child affinity matrix/restoration/ownership suite, P0 API/mask gate, hardware verify,
> read-only core tests, and scope/diff checks. A new capability, mask, restoration, release,
> managed-ID, or native decision returns to blueprint. Handoff only when A01 is evidenced, rejected
> masks make zero platform calls, exact/locality success is honest, every thread-local is cleaned,
> and no unsupported pinner is dereferenced. Merge implementation before its audit.

#### P3-A affinity capability conformance/manual-review prompt - COMPLETED AND MERGED

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P3-A implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-3-affinity-capability-audit` from the updated P3 root. The parent
> artifact is the P3-A blueprint/completion record; there is no validation artifact. Read only its
> exact context/diff/tests, summarized parent/P0-P2 contracts, and relevant code. Do not inspect
> training, executor internals, or detailed native/platform work.
>
> Independently classify every P3-A requirement, parent criteria 1-6/13-16 as applicable, and A01.
> Audit additive compatibility, operational capability, bit-63/bounds/ownership, the complete mask
> matrix, zero partial calls, original restoration, tag-zero release, managed-owner nesting,
> truthful current CPU, thread-local cleanup, happens-before, and test sufficiency. Make only minor
> blueprint-settled corrections with rerun evidence; redesign returns to blueprint.
>
> Write `docs/audits/hardware-utils/phase-3-affinity-capability-conformance.md`, append commands/
> fixes/skips/limits to the completion record, update only the P3 status block, and hand off for
> review/merge. Do not start P3-B before this audit merges.

#### P3-B executor lifecycle blueprint prompt - COMPLETED, REVIEW AND MERGE REQUIRED

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After the P3-A audit is reviewed and merged, create
> `hardware-utils-overhaul/phase-3-executor-lifecycle-blueprint` from the updated P3 root. The
> parent artifact is
> `docs/blueprints/hardware-utils/phase-3-affinity-executor-lifecycle.md`. Inspect status. Read
> `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, the parent's exact P3-B context
> envelope, the P3-A blueprint/completion/conformance summary and final managed-task boundary, P0
> A02/fresh-thread evidence, `PinnedThreadExecutor`, its tests, and exact named worker/benchmark
> call sites. Do not inspect training, native/platform internals, or resource/pressure code.
>
> Write `docs/blueprints/hardware-utils/phase-3-executor-lifecycle.md`. Translate the frozen
> RUNNING/SHUTDOWN/CLOSED state machine, configuration epochs, create-outside/start-inside execute
> linearization, fresh-thread wrapper cleanup, restart/rejection/interruption/termination/deadline
> behavior, singleton/no-overlap registry, exact identity removal, noncapturing cleaner, one-hook
> lifecycle, `closeAll`, E1-E12 schedules, bounded stress, JMM, and contamination contracts into
> one bounded implementation checklist. Preserve concurrent one-thread-per-execute behavior.
>
> Reapply the sizing and implementation-model gates. Confirm parent-selected
> `gpt-5.6-sol`/`high` or update this plan before handoff. Edit planning docs only. Handoff only
> when implementation must choose no state, lock, acceptance, restart, interrupt, deadline,
> registry, cleaner, hook, cleanup, or memory-mode rule. Merge before implementation.

#### P3-B developer-review summary

- Purpose: replace the executor's check-then-put registry, atomic shutdown flag, early task-map
  clearing, per-instance hook, capturing cleaner, and polling deadline with one linearizable
  restartable lifecycle while retaining a fresh concurrent thread for every accepted execution.
- Package boundary: P3-B owns `PinnedThreadExecutor`, optional bounded unexported lifecycle/
  registry support, one new focused lifecycle test, and bounded updates to the two existing
  executor tests. P3-A, platform/native affinity, topology, resources/pressure, core/benchmark
  production, CI, and training are unchanged or prohibited.
- Key contracts: one synchronized lifecycle monitor owns RUNNING/SHUTDOWN/CLOSED, immutable
  configuration, checked epoch, task identity, wait/notify, and create-outside/start-inside
  acceptance. One registry monitor uses registry -> lifecycle lock order, exact weak entries,
  CLOSED-active tombstones, a noncapturing one-CAS cleanup action, one reusable hook identity, and
  gated bounded `closeAll`. Interrupt delivery and registry callbacks stay outside the lifecycle
  monitor.
- P3-A boundary: wrappers bind managed logical ownership before attempting affinity or running
  user code, then attempt release, owner close, and exact task removal in nested cleanup. False
  affinity does not skip work, independent current CPU remains preferred, and the audited Linux
  non-null current-CPU correction is consumed without reopening capability semantics.
- Tests: A02 and the existing fresh-thread anchor remain stable. E1-E12, direct factory and failure
  boundaries, deterministic cleanup/hook/task-affinity fakes, structural noncapture assertions,
  and 50 bounded stress rounds cover lifecycle, registry, interruption, deadlines, no-overlap,
  happens-before, and contamination.
- Sizing/model: the child remains one bounded but irreducibly coupled lifecycle owner. The parent
  selection is confirmed as **`gpt-5.6-sol` with `high` reasoning**; a downgrade is not justified
  by the two-monitor ordering, restartable termination, weak cleanup, and forced race schedules.
- Risks/unresolved items: arbitrary creators may allocate one discarded NEW candidate during a
  race; interrupt-ignoring tasks deliberately retain CLOSED tombstones; real cleaner/GC and JVM
  shutdown timing are not test gates. No state, lock, acceptance, restart, interrupt, deadline,
  registry, cleaner, hook, cleanup, memory-mode, sizing, or implementation-model choice remains
  unresolved.

#### P3-B executor lifecycle implementation prompt

**Child-confirmed model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P3-B blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-3-executor-lifecycle-implementation` from the updated P3 root.
> Inspect status and confirm the child model gate is final. Read only the exact P3-B context,
> summarized parent/P3-A task-binding contract, P0 A02/fresh-thread contract, owned executor/tests,
> and named compatibility call sites.
>
> Implement only `PinnedThreadExecutor`, bounded internal registry/cleanup support, use of the
> P3-A managed-task binding, and deterministic P3-B lifecycle tests. Do not change P3-A capability
> semantics, detailed native/platform code, resources/monitor/pressure, topology, core production,
> task serialization, CI, benchmarks, or training. Append completion evidence to the child
> blueprint and update only the temporary P3 status block.
>
> Run E1-E12, bounded stress, fresh-thread/API gates, hardware verify, read-only core tests, and
> scope/diff checks. A new state, restart, rejection, deadline, hook/cleaner, registry-overlap, or
> memory-mode decision returns to blueprint. Handoff only when A02 is evidenced, no shutdown race
> starts an untracked task, termination is truthful, interruption is preserved, old cleanup cannot
> remove a replacement, and all deterministic cleanup counts reach zero. Merge before its audit.

#### P3-B executor lifecycle conformance/manual-review prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P3-B implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-3-executor-lifecycle-audit` from the updated P3 root. The parent
> artifact is the P3-B blueprint/completion record; there is no validation artifact. Read only its
> exact context/diff/tests, summarized parent/P3-A contract, and named compatibility callers. Do
> not inspect training or expand into platform/resource/core ownership.
>
> Independently classify every P3-B requirement, parent criteria 7-16 as applicable, and A02.
> Audit E1-E12 and stress evidence, fresh concurrent thread identity, start/shutdown/close ordering,
> rejection/command/start failures, interruption/deadlines/termination, singleton/no-overlap,
> cleaner reachability, hook count, identity removal, `closeAll`, contamination, and every JMM edge.
> Make only minor blueprint-settled corrections and rerun affected gates; redesign returns to
> blueprint.
>
> Write `docs/audits/hardware-utils/phase-3-executor-lifecycle-conformance.md`, append commands/
> fixes/skips/limits to completion, update only the P3 status block, and hand off for review/merge
> before the root audit.

#### P3 validation prompt - SUPERSEDED, DO NOT RUN

The current workflow uses each child audit as its combined conformance check and manual review.
Do not create `hardware-utils-overhaul/phase-3-affinity-executor-validation` or
`docs/validations/hardware-utils/phase-3-affinity-executor-lifecycle-validation.md`. The root audit
consumes the two child blueprint/completion/conformance triples directly.

#### P3 root conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After both child audits are reviewed and merged, create
> `hardware-utils-overhaul/phase-3-affinity-executor-audit` from the updated P3 root. The parent
> artifacts are the P3 parent blueprint and exact P3-A/P3-B blueprint/completion/conformance
> triples in the phase artifact index. There is no validation record. Inspect status. Read the
> summarized P0-P2 closeouts, parent acceptance matrix, both child handoffs/diffs, named tests, and
> non-training compatibility call sites. Do not inspect or run training.
>
> Independently audit the combined request -> capability/lease/managed owner -> fresh task ->
> release -> lifecycle/registry cleanup flow. Classify all 16 parent criteria and A01-A02 exactly as
> `satisfied`, `deviated`, `unverified`, or `ambiguous`. Recheck P0 API/masks/fresh-thread behavior,
> the complete affinity matrix, original restoration, E1-E12/stress, Java Memory Model arguments,
> hook/cleaner/map/thread-local cleanup, selected-module hardware verification, read-only core
> compatibility, scope, and diff hygiene.
>
> Allowed edits are
> `docs/audits/hardware-utils/phase-3-affinity-executor-lifecycle-conformance.md`, child/parent
> completion summaries, this plan's P3 closeout summary, temporary P3 status block, and minor
> blueprint-settled corrections. New capability, state, memory mode, cleanup, or platform decision
> returns to the owning blueprint; unrelated files and training are prohibited.
>
> Handoff a review-ready audit first. After explicit merge/closeout authorization, merge the audit
> child, switch to the P3 root, remove only the temporary P3 status block, append root branch/commit
> and final classifications to the P3 closeout summary, and record the resulting root commit when
> committed. P3 is complete only after that authorized closeout; do not create P4 earlier.

#### P3 closeout summary

The developer's authorization to begin P4 design designates
`hardware-utils-overhaul/phase-3-affinity-executor` at `748f34d5` as the completed P3 predecessor.
That root contains the P3-A affinity-capability implementation/audit merged at `2027a47b`, the
P3-B executor-lifecycle implementation/audit merged at `d6389711`, and the combined root audit.

The root audit classifies all 16 P3 parent criteria and A01-A02 as `satisfied`; it records no P3
deviation, ambiguity, or unverified criterion. Its independent fallback run passed the 30-test
combined deterministic suite and five repeated 14-test lifecycle runs, including the 50-round
stress schedule. The child evidence records the passing pinned Java 21 P0/API gate, selected
hardware verification, and 99-test read-only core gate.

The closeout retains the exact audit environment limit: the audit host exposed OpenJDK 17.0.19 and
Maven 3.6.3 but not the pinned Java 21/Maven 3.9.16 selection or Zig. A fresh hardware `verify` and
the Maven-lifecycle core gate therefore stopped at the missing Zig executable; no source/build
workaround was made. Native/platform parity remains assigned to P5-P7. Training was neither
inspected nor run. The authorization to start P4 is the explicit closeout authority; the compact
P3 status text inherited in `AGENTS.md` is not edited by this planning-only P4 blueprint action.

### P4 - 200 ms sampling engine and normalized pressure

#### P4 blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After authorization, create `hardware-utils-overhaul/phase-4-pressure-monitor` from the
> completed P3 root, then work on
> `hardware-utils-overhaul/phase-4-pressure-monitor-blueprint`. The parent artifact is
> `docs/plans/hardware-utils-platform-parity-overhaul.md`, with completed P0-P3 phase artifact
> index entries and closeout summaries as inherited evidence. Initial ownership is hardware root/
> common/internal sampling, pressure, monitor lifecycle, provider compatibility adapters, and
> hardware tests; core is read-only. Inspect `git status --short`. Read `AGENTS.md`,
> `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, the parent plan, the exact P0-P3 files linked
> by those phase artifact index entries and summaries, `ResourceMonitor`,
> `SystemSnapshotProvider`, all
> `SystemUtilization` records, current platform providers as contract inputs, listener/lifecycle
> tests, and read-only `ControlPlaneFragment`/`ControlPlaneCache` pressure-consumer code named in
> the plan. Do not inspect training.
>
> Write `docs/blueprints/hardware-utils/phase-4-resource-monitor-pressure.md`. Settle exact
> canonical units and cumulative/delta rules; the internal immutable detailed sample and validity
> SPI; compatibility adapter; signal age/staleness/reset/wrap/regressing-time behavior; initial
> sample and monitor start/stop/close/read lifecycle; an anchored `t0 + kP` poll-start recurrence
> that selects the first future boundary after completion; overrun skipping including the
> `0 -> 450 -> 600 ms` golden case; one atomic publication per completed evaluation; best-effort
> coalesced listener semantics; independent slow-sensor caching; actual-time asymmetric smoothing;
> bounded latest-value listener dispatcher; snapshot deep-copy/timestamp boundary; and exact
> pressure formulas. Settle fail-fast behavior for null, zero, negative, overflow/
> non-representable, and impractically small public sample durations so none can create a busy
> loop. Listener ownership must define a safe `addListener` call made reentrantly from a callback.
>
> The math must define normalization and correlated-signal handling for CPU scheduler/PSI,
> throttle, steal/external contention, capacity/frequency/thermal loss, memory headroom/reclaim/
> stall, I/O stall/latency, and low-power state. Productive utilization/bytes remain telemetry.
> Every public normalized output is finite `[0.0, 1.0]`; unsupported signals are validity-neutral;
> transient failures follow bounded staleness. Use the plan's fixed field mapping for every
> pressure/ratio accessor and prohibit state-recovery sidecars keyed by timestamps, identities,
> threads, or globals. Require identical publication timestamps in all derived CPU/socket
> snapshots. The first cumulative sample and a reset/regression only establish/re-establish
> baselines and cannot emit a since-boot pressure spike.
>
> Specify formulas, constants, precision, clamp order, timestamp rules, allocation/ownership,
> state machines, exact files, fake clock/scheduler seams, property generators, fixtures,
> listener tests, and validation commands. Platform-specific collection implementation and core
> production edits remain P5-P8. Public record shape changes, action-picker/training changes, and
> every training path/command are prohibited. Edit only blueprint/plan/planning docs.
>
> Define package ownership, naming, raw-sample-to-publication data flow, and all high-reasoning
> contracts without enumerating minor files unnecessarily. Include a bounded implementation
> context envelope naming required inputs and owned outputs. The required formula/constants/
> precision work must include rounding and clamp order, floating-point error/overflow/NaN handling, time
> arithmetic, deterministic evaluation order, allocation/retention and memory
> pollution/contamination, publication memory modes and happens-before edges, listener ownership,
> safety, and compatibility. Apply the workflow sizing/split gate. If sensor SPI, scheduler/
> lifecycle, pressure mathematics, or listener publication are independently oversized, define
> responsibility-scoped child blueprint action items, branch names, and bounded context envelopes
> now, then update every P4 implementation/validation/audit prompt, parent artifact, and phase
> artifact index entry. Only after this parent blueprint child is merged may those branches be
> created from the updated P4 root; rerun the gate per child. Do not run the root implementation
> after a split.
>
> Perform the mandatory `Implementation model reassessment` and replace the provisional P4
> implementation selection and complete prompt body. Append the developer-review summary to the P4 plan section
> with purpose, ownership, key contracts/formulas, children, model, risks, and unresolved
> decisions. The output artifact is the finalized blueprint, plan summary, and implementation
> prompt. Handoff for review and merge into the P4 root only when implementation can translate it
> directly without choosing a unit, formula, threshold, smoothing constant, stale policy,
> lifecycle transition, or listener queue design. Do not start implementation before merge.

#### P4 developer-review summary

- Purpose: replace the mixed-unit, double-primed monitor with one canonical sample-to-pressure-to-
  publication path at the default 200 ms cadence.
- Ownership: P4-A owns detailed sample validity, compatibility adapters, delta/age state, and slow
  caching; P4-B owns formulas, smoothing, public projection, and ratio sanitation; P4-C owns the
  bounded latest-value listener dispatcher; P4-D owns duration validation, monitor lifecycle,
  anchored scheduling, and integrated publication. Platform collectors and core production are
  read-only.
- Key contracts: cumulative nanosecond/byte counters establish baselines before deltas; fast TTL is
  `min(30 s, max(1 s, 5P))`, slow cadence/TTL are 5/15 seconds; reset/wrap/regression cannot spike;
  correlated signals and independent bottleneck domains compose by `max`; memory headroom begins
  at 80 percent and reaches full at 100 percent; reclaim reaches full at two percent of limit per
  second; I/O latency spans 1-50 ms; attack/release EWMA alphas are 0.20/0.05 at 200 ms; public
  ratios are finite `[0.0, 1.0]`; productive utilization/bytes are telemetry; every derived
  CPU/socket timestamp equals the publication timestamp.
- Lifecycle/publication: public periods are 10 ms through 24 hours, constructor sampling is
  prohibited, one stopped initial read is coalesced, `stop()` is additive/restartable, the explicit
  six-state lifecycle distinguishes permanent `CLOSING` from completed `CLOSED`, an already-
  claimed publication may finish only before external close returns, poll starts follow anchored
  `t0 + kP` first-future scheduling, `0 -> 450 -> 600 ms` is mandatory, the monitor no longer
  requests a 1 ns platform timer/scheduler mutation, and one release store follows topology update
  per successful evaluation.
- Listener ownership: one active callback and one coalesced latest value, identity-based ordered
  registration, no callback under the registry lock, safe callback-time `addListener`/`close`,
  `Throwable` isolation, and a truthful close barrier.
- Children: four sequential blueprint/implementation families P4-A through P4-D. By explicit
  developer direction there are no child validation/conformance/audit steps; one integrated P4
  conformance action follows the merged P4-D implementation.
- Model: each child blueprint uses `gpt-5.6-sol`/`max`; the parent-selected implementation minimum
  is `gpt-5.6-sol`/`high`, subject only to child confirmation or upgrade; the one final conformance
  action uses `gpt-5.6-sol`/`high`.
- Risks: current platform pressure is intentionally validity-neutral until P5-P7; due slow sensors
  may skip fast boundaries; truthful external close may wait for user/provider work; fixed
  thresholds become observable when later providers supply rich signals.
- Unresolved decisions: none. Units, schema, validity, TTLs, formulas, constants, precision,
  public mapping, duration bounds, states, recurrence, memory modes, listener queue, split order,
  and the single-conformance workflow are settled in the parent blueprint.

#### P4 root implementation prompt - SUPERSEDED, DO NOT RUN

The sizing gate rejected one root implementation context. Do not create
`hardware-utils-overhaul/phase-4-pressure-monitor-implementation`. Use P4-A through P4-D below,
sequentially from the updated P4 root. There is no per-child validation, conformance, or audit
action.

#### P4-A sample/validity blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After the P4 parent blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-4-sample-validity-blueprint` from the updated P4 root. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the parent's P4-A context
> envelope and fixed unit/validity/delta contracts, summarized P0-P3 closeouts,
> `SystemSnapshotProvider`, current provider records as read-only adapter inputs, and the exact
> existing snapshot/provider tests. Do not inspect pressure/public projection, monitor/listener
> implementation, core beyond the parent's summary, native bodies, or training.
>
> Write `docs/blueprints/hardware-utils/phase-4-sample-validity-contract.md`. Translate the parent
> schema into an exact local inventory for `internal.sampling`, immutable constructors, validity
> factories, compatibility-profile selection, fixed counter/cache state, fast/slow grids, failure
> conversion, and fixtures. Preserve every parent unit, TTL, reset/wrap/regression rule, sidecar
> prohibition, and deep-copy boundary. Apply the sizing/model gates; split again only if this one
> responsibility still cannot fit. Confirm or upgrade the parent-selected implementation model
> and update the P4-A implementation label. Edit only the child blueprint and authorized plan
> prompt/status text. Do not implement.
>
> Handoff only when implementation can add the SPI/adapter/state engine without choosing a type,
> unit, validity transition, cache key, timestamp rule, TTL boundary, or legacy mapping. Merge the
> blueprint into the P4 root before creating P4-A implementation.

#### P4-A sample/validity implementation prompt

**Confirmed blueprint implementation model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P4-A blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-4-sample-validity-implementation` from the updated P4 root.
> Inspect status and read only the parent summary, finalized P4-A blueprint, its exact
> source/test envelope, and named P0-P3 compatibility anchors. Do not inspect training.
>
> Implement only `internal.sampling`, canonical `SystemSnapshotProvider` documentation, fixed
> legacy compatibility adapters, sample/delta/age/slow-cache tests and fixtures, and exact P4-A
> compatibility-ledger records. Current platform collectors are read-only and must remain neutral
> where the parent says their signals are untrustworthy. Do not edit pressure projection,
> `ResourceMonitor`, listener code, core production, native code, module exports, or Maven.
>
> Run the blueprint's focused Java-only tests, mutation/reset/wrap/regression/TTL/property cases,
> P0 compatibility gate, diff/scope checks, and applicable selected-module verification. Append
> changed files, commands/results, acceptance evidence, deviations, and environmental limits to
> the P4-A blueprint. Update only the compact temporary P4 status block in `AGENTS.md`.
>
> Handoff for review and merge when P4-A's immutable interval output is complete and P4-B can
> consume it without platform guesses. There is no P4-A validation or audit action. After merge,
> create only the P4-B blueprint branch.

#### P4-B pressure mathematics blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After P4-A implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-4-pressure-math-blueprint` from the updated P4 root. Inspect
> status. Read the parent P4-B envelope/formula/public mapping, the compact P4-A completion/review
> summary and immutable interval API, `SystemUtilization`, its three focused tests, and named
> read-only core pressure consumers. Do not read provider bodies, monitor/listener internals,
> native code, unrelated core, or training.
>
> Write `docs/blueprints/hardware-utils/phase-4-pressure-mathematics.md`. Enumerate the bounded
> evaluator/projection inventory and exact golden/property mechanics for every parent formula,
> ULP/clamp/overflow rule, asymmetric smoother state, public constructor sanitation, field mapping,
> byte allocation, deep copy, and timestamp invariant. Do not alter a parent threshold or add a
> pressure signal. Apply the sizing/model gates and confirm or upgrade the implementation model.
> Edit planning docs only; do not implement.
>
> Handoff only when no implementation choice remains in normalization, correlation, precision,
> smoother initialization, unsupported clearing, derived records, or direct-constructor behavior.
> Merge before P4-B implementation.

#### P4-B pressure mathematics implementation prompt

**Parent-selected model: `gpt-5.6-sol`; reasoning effort: `high`. The P4-B blueprint must confirm
or upgrade this selection before this prompt is runnable.**

> After the P4-B blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-4-pressure-math-implementation` from the updated P4 root. Read
> only its finalized context, the parent formula tables, P4-A's immutable interval interface, the
> P0 compatibility anchor, `SystemUtilization`, and named tests/read-only consumers.
>
> Implement only `internal.pressure`, settled changes to `SystemUtilization`, public ratio
> sanitation, math/projection tests and fixtures, and exact P4-B ledger entries. Do not edit P4-A
> contracts, `ResourceMonitor`, listener code, platform/native collection, module/Maven, core
> production, or training.
>
> Run every normalization boundary/golden/property case, the reflection-exhaustive ratio test,
> actual-time smoothing tests, public field/timestamp/mutation tests, inherited snapshot tests,
> P0 compatibility, and scope/hygiene commands. Append a complete P4-B completion record and update
> only the temporary P4 status block.
>
> Handoff for review/merge only when every normalized accessor is finite and exact, productive
> telemetry is pressure-neutral, correlation uses `max`, reset baselines do not spike, and the
> immutable candidate is ready for monitor integration. There is no P4-B validation or audit
> action. After merge, create only the P4-C blueprint branch.

#### P4-C listener publication blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After P4-B implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-4-listener-publication-blueprint` from the updated P4 root.
> Inspect status. Read the parent P4-C envelope/listener contract, compact P4-A/P4-B summaries,
> `ResourceMonitor.MonitorListener`, `HardwareUtilization`, and only current listener-related test
> context. Sampling/math/monitor lifecycle/platform/core/native/training implementation is
> read-only or prohibited as stated by the parent.
>
> Write `docs/blueprints/hardware-utils/phase-4-listener-publication.md`. Freeze the local
> `LatestValueDispatcher` inventory, identity registry, one-active/one-pending state machine,
> offer/replace/wake ordering, callback snapshot iteration, Throwable/interrupt cleanup, thread
> start failure, nonblocking `beginClose(terminationHook)` cutoff, exactly-once unlocked termination
> notification, external `awaitClosed()`/reentrant close barrier, lifecycle -> dispatcher lock
> order, memory edges, deterministic seams, and every latch test. Apply the sizing/model gates and
> confirm or upgrade the selected implementation
> capability. Planning docs only; do not implement.
>
> Handoff only when add/offer/dispatch/close can be translated without choosing a queue, lock,
> callback ownership rule, close linearization point, or memory mode. Merge before implementation.

#### P4-C listener publication implementation prompt

**Parent-selected model: `gpt-5.6-sol`; reasoning effort: `low`. The P4-C blueprint must confirm
or upgrade this selection before this prompt is runnable.**

> After the P4-C blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-4-listener-publication-implementation` from the updated P4 root.
> Read only the finalized P4-C context, parent listener/JMM rules, published utilization contract,
> nested listener signature, and named tests.
>
> Implement only `internal.monitor.LatestValueDispatcher`, its deterministic tests, and exact P4-C
> ledger entries. Do not edit `ResourceMonitor`, P4-A/P4-B contracts, platform/native code, module/
> Maven, core production, or training.
>
> Run nonblocking/coalescing/order/non-overlap tests, identity dedupe, callback-time add and close,
> `beginClose` rejection/termination notification, external `awaitClosed()`,
> `RuntimeException`/`Error`/interrupt
> isolation, start failure, bounded retention, P0 compatibility, and scope/diff checks. Append the
> P4-C completion record and update only the temporary P4 status block.
>
> Handoff for review/merge when one active/one latest pending is proven, no callback holds the
> registry lock, close is truthful without self-join, and all retained state clears. There is no
> P4-C validation or audit action. After merge, create only the P4-D blueprint branch.

#### P4-D monitor lifecycle/scheduler blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After P4-C implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-4-monitor-lifecycle-blueprint` from the updated P4 root. Inspect
> status. Read the parent P4-D lifecycle/scheduler/publication envelope, compact P4-A/P4-B/P4-C
> completion/review summaries and interfaces, `ResourceMonitor`, `TopologyMapper`, focused monitor
> tests, and only the named read-only lattice/core consumers. Platform collector bodies, unrelated
> core, native code, and training are prohibited.
>
> Write `docs/blueprints/hardware-utils/phase-4-monitor-lifecycle-scheduler.md`. Translate the
> six-state table, constructor/duration failures, coalesced stopped read, initial/restart freshness,
> `evaluationActive`/`publicationClaimed` close ordering, provider/topology/thread failure
> transitions, explicit P2 logical-span injection, clock/waiter/`TopologyUpdater` seams, anchored
> first-future
> recurrence, one release publication, topology/listener ordering, self-stop/close, cleanup, JMM,
> removal of the monitor's 1 ns timer-resolution request, and integration fixtures into an exact
> bounded inventory. Apply the sizing/model gates and
> confirm or upgrade the model. Do not reopen A-C contracts or implement.
>
> Handoff only when no duration, state, deadline, failure, publication, memory-mode, or cleanup
> decision remains. Merge before P4-D implementation.

#### P4-D monitor lifecycle/scheduler implementation prompt

**Parent-selected model: `gpt-5.6-sol`; reasoning effort: `high`. The P4-D blueprint must confirm
or upgrade this selection before this prompt is runnable.**

> After the P4-D blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-4-monitor-lifecycle-implementation` from the updated P4 root.
> Read only the finalized P4-D context, parent lifecycle/recurrence/JMM contract, merged A-C public
> interfaces/completion summaries, `ResourceMonitor`, `TopologyMapper`, named tests, and read-only
> consumers.
>
> Implement only `ResourceMonitor`, the small clock/waiter/`TopologyUpdater` seams, integration
> tests/fixtures, and
> exact P4-D compatibility-ledger/status records. Compose A-C without changing their settled
> contracts. Platform collection, core production, native code, module exports, Maven, action
> picker, and training are prohibited.
>
> Run every duration/state/failure/concurrent-read test, close before and after the publication
> claim, fake starts `0, 200, 400`, exact-boundary and `0 -> 450 -> 600 ms` overrun tests,
> regression/reanchor, topology-before-single-release publication counts, post-`CLOSING` listener
> cutoff, listener coalescing independence, all A-C suites, P0 compatibility, focused hardware
> verification, read-only core tests, and scope/diff hygiene. Append the P4-D completion record and
> update only the temporary P4 status block.
>
> Handoff for review/merge only when the integrated path satisfies all 22 parent criteria and no
> polling/listener thread, provider buffer, pending value, or state leaks. There is no P4-D
> validation or audit action. After merge, create only the single P4 conformance branch below.

#### P4 validation prompts - SUPERSEDED, DO NOT RUN

By explicit developer direction, do not create a P4 root or child validation branch/artifact and
do not create P4-A/P4-B/P4-C/P4-D conformance or audit branches. Implementation actions run and
record their owned tests; the one integrated conformance action below independently reviews the
merged result.

#### P4 conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P4-D implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-4-pressure-monitor-audit` from the updated P4 root. This is the
> only P4 conformance/manual-review action. Its parent artifacts are the P4 parent blueprint and
> the four indexed child blueprint/completion records; there are no child conformance or validation
> artifacts. Ownership is independent integrated conformance review and minor blueprint-settled P4
> corrections. Inspect `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the
> summarized P0-P3 closeouts, the parent acceptance matrix, compact A-D completion records, merged
> P4 implementation diff, named tests, and read-only consumer context. Do not inspect or run
> training.
>
> Independently audit the end-to-end provider/adapter -> delta/age -> pressure/projection ->
> topology/release publication -> bounded listener flow. Classify all 22 parent criteria and the
> common P4 portions of R01-R10/R13-R14. Recheck canonical units and counter baselines,
> staleness/slow caching, every formula/constant/precision/clamp rule, finite ratio manifest,
> correlated-signal treatment, actual-time smoothing, public field/timestamp/deep-copy ownership,
> duration safety, the six-state lifecycle and publication-claim close ordering, exact 200 ms
> recurrence and `0 -> 450 -> 600 ms`, one
> release publication, Java Memory Model edges, listener bounds/reentrancy/Throwable/close,
> allocation/retention/contamination, P0 compatibility, selected hardware verification, read-only
> core compatibility, and scope/diff hygiene. Carry platform collection portions explicitly to
> P5-P7.
>
> Write `docs/audits/hardware-utils/phase-4-resource-monitor-pressure-conformance.md`. Allowed
> edits are that audit, the applicable A-D completion records, the P4 closeout summary in this plan,
> the temporary P4 status block, and minor blueprint-settled corrections. Rerun affected tests after
> a correction. A new unit, signal, threshold, validity/TTL, formula, lifecycle transition,
> recurrence, memory mode, or listener ownership design returns to the exact owning blueprint;
> platform expansion, core production, unrelated files, and training are prohibited.
>
> Classify every item exactly as `satisfied`, `deviated`, `unverified`, or `ambiguous`, with direct
> evidence and environmental limits. Handoff a review-ready integrated audit first. After explicit
> merge/closeout authorization, merge this one audit child, switch to the P4 root, remove only the
> temporary P4 status block, append the root branch/commit and final classifications to the P4
> closeout summary, and record the resulting root commit when committed. P4 is complete only after
> that authorized closeout; do not create P5 earlier.

### P5 - Linux parity, cgroups, and libc portability

#### P5 parent blueprint prompt - COMPLETED, REVIEW AND MERGE REQUIRED

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

The completed output is `docs/blueprints/hardware-utils/phase-5-linux-platform.md` on `hardware-utils-overhaul/phase-5-linux-blueprint`. It froze cgroup v1/v2/hybrid/bare-host discovery, complete bounded reads, unlimited quota, honest cgroup-aggregate pressure propagation, sparse multisocket topology, Linux 3.10 kernel floor derivation, and glibc 2.17 / musl native ABI contracts. Its sizing gate splits P5 into sequential P5-A, P5-B, and P5-C children. Review and merge it into `hardware-utils-overhaul/phase-5-linux` before creating any child branch.

#### P5 developer-review summary

- Purpose: deliver read-only cgroup v1/v2/hybrid/bare-host resource collection, sparse multisocket Linux topology parsing, direct syscall affinity, and glibc 2.17 / musl ABI portability on Linux 3.10+.
- Ownership: `io.euhedral_execution.hardware_utils.linux.*` (Java), `src/main/native/linux/*` (C++), `native-products.json` (Manifest).
- Key contracts: read-only discovery (zero controller writes); unlimited quota equals effective cpuset cardinality; honest cgroup pressure propagation without host jiffy apportionment; Linux 3.10 kernel floor; glibc 2.17 + musl dual ELF artifacts without C++ runtimes; complete bounded file reads with channel cleanup; block-device loop filter; 60 s rate-limited diagnostic logging.
- Children: P5-A (Topology), P5-B (Resources), P5-C (Affinity & Native ABI).
- Selected model: `gpt-5.6-sol` with `high` reasoning for all implementation and audit action items.
- Risks: sysfs path variations across Linux distros; cgroup v1 vs v2 permission differences; host vs container CPU ID mismatches; JNI array pin safety.
- Unresolved decisions: none. Cgroup scope, units, file-read bounds, device filters, sensor cadences, syscalls, libc targets, and fallbacks are fully settled.

#### P5 root implementation prompt - SUPERSEDED, DO NOT RUN

The sizing gate rejected one P5 root implementation context. Do not create `hardware-utils-overhaul/phase-5-linux-implementation`. Use P5-A, P5-B, and P5-C action families below, sequentially from the updated P5 root.

#### P5-A Linux topology model blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After the parent P5 blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-5-linux-topology-blueprint` from the updated P5 root. The parent
> artifact is `docs/blueprints/hardware-utils/phase-5-linux-platform.md`. Inspect `git status --short`.
> Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, the parent's P5-A context
> envelope, summarized P0-P4 closeouts, `LinuxSystemLayout`, sysfs cpu topology files, and existing
> topology tests. Do not inspect resource collection, native C++ bodies, core, or training.
>
> Write `docs/blueprints/hardware-utils/phase-5-linux-topology-model.md`. Translate the parent
> topology contract into an implementation checklist: sysfs `/sys/devices/system/cpu/` scanning,
> sparse OS CPU ID mapping with null holes, compound `(packageId, dieId, coreId)` global core
> uniqueness, cache domain extraction, P2 cache fallbacks, and P/E core classification from
> cpufreq/caches.
>
> Reapply sizing/model gates and confirm `gpt-5.6-sol`/`high` implementation model. Edit planning
> docs only. Handoff for review and merge into the P5 root before creating P5-A implementation.

#### P5-A Linux topology model implementation prompt

**Child-confirmed model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P5-A blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-5-linux-topology-implementation` from the updated P5 root. Read
> `AGENTS.md`, the plan's P5 summary, finalized P5-A blueprint, and its exact context envelope.
>
> Implement `LinuxSystemLayout`, sparse OS CPU ID indexing, compound global core tuples, cache fallbacks,
> and P5-A fixture tests. Do not edit resource collection, native C++ files, core, or training.
> Append completion notes to the P5-A blueprint and update the temporary P5 status block in `AGENTS.md`.
>
> Run P5-A topology tests, sparse/multisocket fixtures, P0 compatibility gate, and hardware verify.
> Merge implementation before its audit.

#### P5-A Linux topology model conformance/manual-review prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P5-A implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-5-linux-topology-audit` from the updated P5 root. Read P5-A
> blueprint, completion record, and diff.
>
> Independently audit sparse CPU handling, global core uniqueness, cache fallbacks, and P/E core
> classification. Write `docs/audits/hardware-utils/phase-5-linux-topology-model-conformance.md`,
> append command/fix/limit evidence to completion, update the status block, and hand off for merge.

#### P5-B Linux resource provider blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After P5-A audit is reviewed and merged, create
> `hardware-utils-overhaul/phase-5-linux-resources-blueprint` from the updated P5 root. The parent
> artifact is `docs/blueprints/hardware-utils/phase-5-linux-platform.md`. Read parent P5-B context
> envelope, P4 sampling contract, `LinuxResourceProvider`, `LinuxPaths`, cgroup/procfs fixtures.
>
> Write `docs/blueprints/hardware-utils/phase-5-linux-resource-provider.md`. Translate parent
> contract into implementation checklist: read-only v1/v2/hybrid/bare-host discovery, mountinfo/cgroup
> parsing, complete bounded file reads via reusable `ByteBuffer`, 60 s rate-limited diagnostic logging,
> unlimited quota calculation (`effectiveCpus.cardinality()`), cgroup-aggregate pressure propagation
> without host jiffy apportionment, block-device filtering (excluding `loop`/`ram`), fast/slow cadences,
> and `SignalValidity` state tracking.
>
> Reapply sizing/model gates and confirm `gpt-5.6-sol`/`high` implementation model. Edit planning
> docs only. Handoff for review and merge into the P5 root before creating P5-B implementation.

#### P5-B Linux resource provider implementation prompt

**Child-confirmed model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P5-B blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-5-linux-resources-implementation` from the updated P5 root. Read
> finalized P5-B blueprint and context envelope.
>
> Implement `LinuxResourceProvider`, `LinuxPaths`, read-only cgroup discovery, bounded file reads,
> block-device filter, unlimited quota fix, and cgroup-aggregate pressure propagation. Append completion
> record to P5-B blueprint and update status block in `AGENTS.md`.
>
> Run cgroup v1/v2/hybrid/bare-host fixtures, unlimited quota tests, host-activity isolation fixtures,
> block-device filter tests, and hardware verify. Merge implementation before its audit.

#### P5-B Linux resource provider conformance/manual-review prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P5-B implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-5-linux-resources-audit` from the updated P5 root. Read P5-B
> blueprint, completion record, and diff.
>
> Independently audit read-only cgroup discovery, unlimited quota math, host-activity isolation, bounded
> reads, block-device filter, and sensor validity. Write
> `docs/audits/hardware-utils/phase-5-linux-resource-provider-conformance.md`, append evidence, and
> hand off for merge.

#### P5-C Linux affinity & native ABI blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After P5-B audit is reviewed and merged, create
> `hardware-utils-overhaul/phase-5-linux-affinity-native-blueprint` from the updated P5 root. The
> parent artifact is `docs/blueprints/hardware-utils/phase-5-linux-platform.md`. Read parent P5-C
> envelope, `LinuxAffinity`, `LinuxAffinityCalls`, `linux_affinity.cpp`, `linux_jni.h`, and P1 native
> build graph.
>
> Write `docs/blueprints/hardware-utils/phase-5-linux-affinity-native.md`. Translate parent contract
> into implementation checklist: direct Linux syscalls (`sys_sched_setaffinity`, `sys_sched_getaffinity`,
> `sys_getcpu`, `sys_prctl`), Linux 3.10 kernel floor verification, glibc 2.17 + musl dual ELF gates,
> JNI array pinning safety, errno handling, timer slack, and affinity lease capture/restoration.
>
> Reapply sizing/model gates and confirm `gpt-5.6-sol`/`high` implementation model. Edit planning
> docs only. Handoff for review and merge into the P5 root before creating P5-C implementation.

#### P5-C Linux affinity & native ABI implementation prompt

**Child-confirmed model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P5-C blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-5-linux-affinity-native-implementation` from the updated P5 root. Read
> finalized P5-C blueprint and context envelope.
>
> Implement `LinuxAffinity`, `LinuxAffinityCalls`, `linux_affinity.cpp`, `linux_jni.h`, direct syscall
> wrappers, and affinity lease restoration. Append completion record to P5-C blueprint and update status block.
>
> Run affinity matrix tests, original mask restoration tests, glibc 2.17 / musl binary gates, and JNI
> load smoke tests. Merge implementation before its audit.

#### P5-C Linux affinity & native ABI conformance/manual-review prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P5-C implementation is reviewed and merged, create
> `hardware-utils-overhaul/phase-5-linux-affinity-native-audit` from the updated P5 root. Read P5-C
> blueprint, completion record, and diff.
>
> Independently audit direct syscalls, errno translation, Linux 3.10 kernel floor, glibc 2.17 / musl
> gates, and affinity lease restoration. Write
> `docs/audits/hardware-utils/phase-5-linux-affinity-native-conformance.md`, append evidence, and hand
> off for merge.

#### P5 root conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After all three child audits (P5-A, P5-B, P5-C) are reviewed and merged, create
> `hardware-utils-overhaul/phase-5-linux-audit` from the updated P5 root. The parent artifacts are
> the P5 parent blueprint and the three indexed child blueprint/completion/conformance triples.
>
> Independently audit the end-to-end Linux platform provider: topology discovery -> cgroup/resource
> metrics -> native direct syscall affinity. Classify all Linux requirements and defect ledger items
> (T02, R02, R06, R11, R12, R14, B06) as `satisfied`, `deviated`, `unverified`, or `ambiguous`.
>
> Write `docs/audits/hardware-utils/phase-5-linux-platform-conformance.md`, append the P5 closeout
> summary to the parent plan, remove the temporary P5 status block upon authorized merge, and record
> the resulting root commit. P5 is complete only after this authorized closeout; do not create P6
> earlier.

### P6 - Windows processor-group and resource parity

#### P6 parent blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After authorization, create `hardware-utils-overhaul/phase-6-windows` from the completed P5
> root, then work on `hardware-utils-overhaul/phase-6-windows-blueprint`. The parent artifact is
> `docs/plans/hardware-utils-platform-parity-overhaul.md`, with completed P0-P5 phase artifact
> index entries and closeout summaries as inherited evidence. Initial ownership is hardware
> Windows Java/native implementation, Windows fixtures/tests, and Windows manifest/CI metadata.
> Inspect `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`,
> `docs/ARCHITECTURE.md`, the parent plan, the exact P0-P5 files linked by those phase artifact
> index entries and summaries, every Windows Java/native source and declaration, the P1 manifest/
> ABI contract, GLPIEx structures/tests, and Windows resource/affinity/timer paths. Do not inspect
> training.
>
> Write `docs/blueprints/hardware-utils/phase-6-windows-platform.md`. Settle bounded GLPIEx parsing
> with exact structure offsets/alignment; malformed/truncated behavior; bit 63 and multiple groups;
> bijective `(group, processor)` to Euhedral logical IDs; packages/cores/caches/efficiency; group
> affinity apply/release/current ownership, including deterministic rejection rather than partial
> success for unrepresentable cross-group masks; documented dynamic API lookup and Windows 10/
> Server 2016 fallbacks; job/process quota and effective masks; cumulative CPU/throttle/I/O counters;
> memory units; scheduler/capacity/frequency/power signals; timer JNI ownership; buffer validation;
> thread-safe initialization; and x86-64/ARM64 PE ABI/import floors.
>
> Specify exact native structures, integer widths, overflow checks, no VLA replacements, error
> values, dynamic symbol ownership, UCRT/compiler-runtime policy, fixtures, runtime tests, and
> binary commands. Shared-contract redesign, Linux/macOS/core, undocumented/private APIs, and all
> training work are prohibited. Edit only blueprint/plan/planning docs.
>
> Define package/artifact ownership, naming, native-to-Java data flow, and all high-reasoning
> contracts without enumerating minor files unnecessarily.
> Include a bounded implementation context envelope naming required inputs and owned outputs.
> Explicitly settle structure/integer/floating-point precision, deterministic ordering, native and
> JVM memory ownership/publication, bounds and lifetime safety, allocation/retention and memory
> pollution/contamination, portability, and compatibility. Apply the workflow sizing/split gate.
> If topology parsing, resource collection, affinity, or ABI/runtime gates are independently
> oversized, define bounded responsibility child blueprint action items, branch names, and context
> envelopes now, then update all P6 implementation/validation/audit prompts, parent artifacts, and
> the phase artifact index. Only after this parent blueprint child is merged may those branches be
> created from the updated P6 root; rerun the gate per child. Do not run the root implementation
> after a split.
>
> Perform the mandatory `Implementation model reassessment` and replace the provisional P6
> implementation selection and complete prompt body.
>
> Append the workflow developer-review summary to the P6 plan section with purpose, ownership,
> contracts, children, selected implementation model, risks, and unresolved decisions. The output
> artifact is the finalized blueprint, plan summary, and implementation prompt. Handoff for review
> and merge into the P6 root only when implementation needs no offset, identity, unit,
> API-fallback, initialization, buffer, or import decision. Do not start implementation before
> merge.

#### P6 developer-review summary

- Purpose: Deliver GLPIEx topology parsing, multi-group processor mapping, Job Object quota and memory tracking, multi-group thread affinity with lease restoration, VLA-free native code, and PE ABI hardening for Windows 10/11 platforms.
- Ownership: `io.euhedral_execution.hardware_utils.windows.*` (Java), `src/main/native/windows/*` (C++), `native-products.json` (Manifest).
- Key contracts: Exact GLPIEx structure offsets; bijective `(group, processor)` to logical ID mapping; `CpuRate / 10000.0` quota scaling; `WorkingSetSize - PrivateUsage` underflow protection; deterministic multi-group affinity rejection (`false`); timer resolution shutdown hook cleanup; zero VLAs in C++; PE ABI hardening without C++ runtimes.
- Child Action Items: P6-A (Topology & GLPIEx), P6-B (Resources & Job Objects), P6-C (Affinity & PE ABI).
- Selected Model: `gpt-5.6-sol` with `high` reasoning effort for all implementation and audit action items.
- Principal Risks: Win32 structure alignment across 64-bit boundaries; processor group mask bit 63 signed shift bugs; legacy cycle count time division; VLA stack allocations in JNI.
- Unresolved Items: None. GLPIEx offsets, group mapping, units, underflow bounds, affinity fallbacks, timer hooks, PE floors, and CRT policies are fully settled.

#### P6-A Windows topology model blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After P6 parent blueprint child is reviewed and merged, create
> `hardware-utils-overhaul/phase-6-windows-topology-blueprint` from the updated P6 root. The parent
> artifact is `docs/blueprints/hardware-utils/phase-6-windows-platform.md`. Read parent P6-A context
> envelope, P2 topology model, `WindowsSystemLayout`, `win32.*` parsers, GLPIEx fixtures.
>
> Write `docs/blueprints/hardware-utils/phase-6-windows-topology-model.md`. Translate parent contract
> into implementation checklist: exact GLPIEx structure offset parsing (`SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX`,
> `PROCESSOR_RELATIONSHIP`, `CACHE_RELATIONSHIP`, `GROUP_AFFINITY`), bit 63 KAFFINITY mask math, bijective
> `(group, processor)` to Euhedral logical ID mapping (`group * 64 + processor`), P/E core classification via
> `EfficiencyClass`, SMT detection, cache domain BitSet masks spanning multi-group logical IDs, and malformed buffer error handling.
>
> Reapply sizing/model gates and confirm `gpt-5.6-sol`/`high` implementation model. Edit planning docs only. Handoff for review and merge into the P6 root before creating P6-A implementation.

#### P6-A Windows topology model implementation prompt

**Child-confirmed model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P6-A blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-6-windows-topology-implementation` from the updated P6 root. Read
> finalized P6-A blueprint and context envelope.
>
> Implement `WindowsSystemLayout`, `win32.*` parsers, GLPIEx record offset parser, bit 63 mask math,
> global logical ID mapping, and multi-group cache domains. Append completion record to P6-A blueprint and update status block.
>
> Run GLPIEx single-group, multi-group, >64 CPU, bit 63, and malformed buffer fixtures. Merge implementation before its audit.

#### P6-B Windows resource provider blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After P6-A audit is reviewed and merged, create
> `hardware-utils-overhaul/phase-6-windows-resources-blueprint` from the updated P6 root. The parent
> artifact is `docs/blueprints/hardware-utils/phase-6-windows-platform.md`. Read parent P6-B context
> envelope, P4 sampling contract, `WindowsResources`, Win32 job object APIs.
>
> Write `docs/blueprints/hardware-utils/phase-6-windows-resource-provider.md`. Translate parent contract
> into implementation checklist: Job Object CPU rate control (`CpuRate / 10000.0` quota scaling), effective quota CPUs
> (`quotaFraction * availableCpus`), process working set underflow protection (`Math.max(0L, WorkingSetSize - PrivateUsage)`),
> `GetProcessTimes` 100-ns to nanosecond conversion (`* 100L`), `QueryIdleProcessorCycleTime` idle cycle delta normalization,
> cumulative I/O bytes (`ReadTransferCount + WriteTransferCount`), and `SignalValidity` state tracking.
>
> Reapply sizing/model gates and confirm `gpt-5.6-sol`/`high` implementation model. Edit planning docs only. Handoff for review and merge into the P6 root before creating P6-B implementation.

#### P6-B Windows resource provider implementation prompt

**Child-confirmed model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P6-B blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-6-windows-resources-implementation` from the updated P6 root. Read
> finalized P6-B blueprint and context envelope.
>
> Implement `WindowsResources`, Job Object `CpuRate` quota scaling, working set underflow guard, process
> CPU time conversion, idle cycle delta normalization, and cumulative I/O bytes. Append completion record to P6-B blueprint and update status block.
>
> Run Job Object quota scaling tests, working set underflow boundary tests, idle cycle delta tests, and provider contract tests. Merge implementation before its audit.

#### P6-C Windows affinity & native ABI blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After P6-B audit is reviewed and merged, create
> `hardware-utils-overhaul/phase-6-windows-affinity-native-blueprint` from the updated P6 root. The parent
> artifact is `docs/blueprints/hardware-utils/phase-6-windows-platform.md`. Read parent P6-C context
> envelope, `WindowsAffinity`, `WindowsAffinityCalls`, `windows_affinity.cpp`, `windows_resources.cpp`, `windows_system_layout.cpp`, `windows_hardening.cpp`, `windows_jni.h`, and P1 native build graph.
>
> Write `docs/blueprints/hardware-utils/phase-6-windows-affinity-native.md`. Translate parent contract
> into implementation checklist: multi-group affinity application via `SetThreadSelectedCpuSetMasks` (or `SetThreadGroupAffinity`),
> deterministic rejection (`false`) for unrepresentable multi-group requests, original thread group affinity restoration,
> `GetCurrentProcessorNumberEx` global ID query (`group * 64 + processor`), `NtSetTimerResolution` JNI wrapper with thread-safe `std::atomic<bool>` init and `win-timer-release` shutdown hook, complete elimination of VLAs in C++ native code (using fixed stack buffers or dynamic vector allocation), JNI array null/length checks, x86-64 (Win10/Server 2016) and ARM64 (Win11) PE ABI hardening with zero CRT/compiler runtime dependencies (`-fno-exceptions -fno-rtti`).
>
> Reapply sizing/model gates and confirm `gpt-5.6-sol`/`high` implementation model. Edit planning docs only. Handoff for review and merge into the P6 root before creating P6-C implementation.

#### P6-C Windows affinity & native ABI implementation prompt

**Child-confirmed model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After P6-C blueprint is reviewed and merged, create
> `hardware-utils-overhaul/phase-6-windows-affinity-native-implementation` from the updated P6 root. Read
> finalized P6-C blueprint and context envelope.
>
> Implement `WindowsAffinity`, `WindowsAffinityCalls`, `windows_affinity.cpp`, `windows_resources.cpp`, `windows_system_layout.cpp`, `windows_hardening.cpp`, `windows_jni.h`, multi-group affinity with deterministic rejection, lease restoration, `NtSetTimerResolution` shutdown hook, VLA elimination, and PE hardening. Append completion record to P6-C blueprint and update status block.
>
> Run affinity matrix tests, multi-group rejection tests, lease restoration tests, VLA compliance checks, PE binary import gates, and JNI load smoke tests. Merge implementation before its audit.

#### P6 root conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After all three child audits (P6-A, P6-B, P6-C) are reviewed and merged, create
> `hardware-utils-overhaul/phase-6-windows-audit` from the updated P6 root. The parent artifacts are
> the P6 parent blueprint and the three indexed child blueprint/completion/conformance triples.
>
> Independently audit the end-to-end Windows platform provider: GLPIEx topology discovery -> Job Object/process resource metrics -> multi-group native affinity and PE ABI. Classify all Windows requirements and defect ledger items (T03, A03, R04, N01, B06) as `satisfied`, `deviated`, `unverified`, or `ambiguous`.
>
> Write `docs/audits/hardware-utils/phase-6-windows-platform-conformance.md`, append the P6 closeout summary to the parent plan, remove the temporary P6 status block upon authorized merge, and record the resulting root commit. P6 is complete only after this authorized closeout; do not create P7 earlier.

#### P6 closeout summary

The Phase 6 Windows platform provider implementation and conformance audit are complete on `hardware-utils-overhaul/phase-6-windows` (root commit `04b0111`). The audit artifact `docs/audits/hardware-utils/phase-6-windows-platform-conformance.md` evaluates the end-to-end Windows platform provider (GLPIEx topology parsing -> Job Object/process resource metrics -> multi-group native affinity and PE ABI). All 5 Windows defect ledger items (**T03, A03, R04, N01, B06**) and all 9 core platform requirements are classified as `satisfied`. P6-A, P6-B, and P6-C child blueprints, implementations, and audits are fully integrated and verified. The developer authorized closeout; temporary P6 status block removed from `AGENTS.md`. P6 closeout authorizes Phase 7 work.


### P7 - public-API macOS parity with honest locality semantics

#### P7 blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After authorization, create `hardware-utils-overhaul/phase-7-macos` from the completed P6 root,
> then work on `hardware-utils-overhaul/phase-7-macos-blueprint`. The parent artifact is
> `docs/plans/hardware-utils-platform-parity-overhaul.md`, with completed P0-P6 phase artifact
> index entries and closeout summaries as inherited evidence. Initial ownership is hardware macOS
> Java/native implementation, macOS fixtures/tests, and macOS manifest/CI metadata. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`,
> `docs/ARCHITECTURE.md`, the parent plan, the exact P0-P6 files linked by those phase artifact
> index entries and summaries, every macOS Java/native source and declaration, the P1 manifest/ABI
> contract, public sysctl/Mach/host/task/process API usage, and Intel/Apple Silicon fixture plans.
> Do not inspect training.
>
> Write `docs/blueprints/hardware-utils/phase-7-macos-platform.md`. Settle deterministic topology
> from `hw.logicalcpu`, physical/performance-level/cache/memory sysctls and conservative missing-key
> fallbacks; Intel SMT and Apple Silicon performance-level modeling; stable managed logical
> ownership; supported public cumulative CPU/process/I/O counters; host/task memory semantics;
> Mach buffer deallocation and timebase; public `NSProcessInfo` thermal/low-power inputs; sensor
> cadences/validity; runtime availability/weak-link guards preserving macOS 11; affinity tag
> mapping reported only as `LOCALITY_HINT`, with legacy boolean success only for a representable
> successfully applied single-locality mask and deterministic failure for arbitrary
> multi-locality masks; release tag `0`; unsupported physical current CPU outside managed
> ownership; and safe idempotent timer behavior with no realtime policy.
>
> Treat host/process CPU and process I/O counters as telemetry. They do not become CPU/I/O
> pressure without a separately documented public wait, stall, or capacity-loss signal; unsupported
> contention remains validity-neutral.
>
> Specify exact public APIs/frameworks, counter conversions, topology ordering, core/cache
> fallbacks, ownership tables, native allocation cleanup, 64-bit shift bounds, failure behavior,
> deployment target 11, Intel/ARM64 fixtures/runtime tests, imports, and bundled codesign checks.
> Private APIs, fabricated hard affinity/current CPU, realtime scheduling, shared redesign, other
> platforms/core, and all training work are prohibited. Edit only blueprint/plan/planning docs.
>
> Define package/artifact ownership, naming, public-API/native-to-Java data flow, and all
> high-reasoning contracts without enumerating minor files unnecessarily. Include a bounded
> implementation context envelope naming required inputs and owned outputs. Explicitly settle
> integer/floating-point/timebase precision, deterministic
> ordering, native/JVM allocation and publication ownership, deallocation/lifetime safety,
> memory pollution/contamination, portability, and compatibility. Apply the workflow sizing/split
> gate. If topology synthesis, resource collection, locality affinity, or ABI/runtime gates are
> independently oversized, define bounded responsibility child blueprint action items, branch
> names, and context envelopes now, then update every P7 implementation/validation/audit prompt,
> parent artifact, and phase artifact index entry. Only after this parent blueprint child is merged
> may those branches be created from the updated P7 root; rerun the gate per child. Do not run the
> root implementation after a split.
>
> Perform the mandatory `Implementation model reassessment` and replace the provisional P7
> implementation selection and complete prompt body.
>
> Append the workflow developer-review summary to the P7 plan section with purpose, ownership,
> contracts, children, selected implementation model, risks, and unresolved decisions. The output
> artifact is the finalized blueprint, plan summary, and implementation prompt. Handoff for review
> and merge into the P7 root only when implementation needs no public-API, topology synthesis,
> cumulative-unit, ownership, locality, timer, memory-cleanup, or fallback decision. Do not start
> implementation before merge.

#### P7 implementation prompt - PROVISIONAL, DO NOT RUN

**Provisional model: `gpt-5.6-sol`; provisional reasoning effort: `high`. The P7 blueprint must
replace this selection and prompt body before implementation.**

> After the P7 blueprint child is reviewed and merged, start
> `hardware-utils-overhaul/phase-7-macos-implementation` from the P7 root. The parent artifact is
> `docs/blueprints/hardware-utils/phase-7-macos-platform.md`. Ownership is limited to its macOS
> Java/native/fixture/test/manifest/CI context envelope. Inspect `git status --short`. Read
> `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed P0-P6 phase artifact index entries
> and closeout summaries, the parent blueprint, and only its bounded macOS context. Confirm this
> prompt is finalized.
>
> Implement the approved public-API macOS topology/resource/thermal/low-power adapters, stable
> managed logical ownership, locality-hint affinity/release, safe timer behavior, native cleanup,
> fixtures/tests, and named manifest/CI metadata. Do not use private APIs, claim hard affinity,
> fabricate physical current CPU, install realtime policy, redesign common contracts, touch other
> platforms/core, or access training. Allowed edits are blueprint-owned implementation/tests/
> configuration, the completion record, and the compact temporary P7 phase-status block in
> `AGENTS.md`; no other `AGENTS.md` content may change.
>
> Run Intel/Apple Silicon sysctl fixtures, missing-key/homogeneous/no-L3 fallbacks, cumulative
> provider contract tests, memory/timebase/Mach deallocation tests, affinity capability/release
> and representable/unrepresentable mask boolean tests, low-power runtime-availability tests,
> CPU-load-only and process-I/O-only pressure-neutral tests, native boundary/ABI/deployment/
> signature gates, and real Intel/Apple Silicon smoke where available. Stop on any unstated API
> or synthesis choice; otherwise append completion notes with changed files, commands, results,
> acceptance-criteria evidence, approved deviations, and environmental limits. Add/update the
> temporary `AGENTS.md` block with the completed P6 root, active P7 root, completed blueprint
> child, active implementation child, and blueprint/completion links.
>
> The output artifact is the implemented macOS platform layer plus its completion record appended
> to `docs/blueprints/hardware-utils/phase-7-macos-platform.md`.
>
> Handoff only when `SystemInfo` and `ResourceMonitor` initialize on both mac architectures,
> topology entries are complete, normalized pressure receives supported macOS state, locality
> semantics are honest, and no private/realtime behavior remains; an unavailable minimum-family
> runtime remains an explicit release-blocking `unverified` item. Merge this child into the P7
> root before validation.

#### P7 conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P7 validation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-7-macos-audit` from the P7 root. The parent artifact is
> `docs/validations/hardware-utils/phase-7-macos-platform-validation.md`. Ownership is limited to
> independent conformance review and minor blueprint-settled macOS corrections. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the P7 blueprint's summarized
> parent context, the plan's completed P0-P6 phase artifact index entries and closeout summaries,
> implementation diff, fixtures/binaries, completion record, and validation record. For split
> work, consume only the audited child context plus summarized parent. Do not inspect or run
> training.
>
> Independently audit public API use, Intel/Apple Silicon topology and fallbacks, counter units,
> memory/thermal/low-power semantics, Mach/timebase cleanup, shift bounds, managed logical
> ownership, honest `LOCALITY_HINT` behavior and release, absence of fabricated hard affinity/
> current CPU/realtime policy, telemetry pressure neutrality, deployment/import/signature/runtime
> gates, and validation sufficiency. Allowed edits are
> `docs/audits/hardware-utils/phase-7-macos-platform-conformance.md`, completion and validation
> records, the P7 closeout summary in this plan, the temporary phase-status block, and minor
> blueprint-settled corrections. Rerun and record affected validation after a correction. Private
> APIs, semantic redesign, other platforms, core, unrelated files, and training are prohibited.
>
> The output artifacts are the audit above, updated completion record, P7 closeout summary in this
> plan, and, after the authorized merge, removal of the temporary P7 status block on the root with
> the resulting root commit recorded when committed. Classify every macOS portion of R01, R03,
> R13, T01, T05, A04, N02, B06, and P7 requirement exactly as `satisfied`, `deviated`, `unverified`, or
> `ambiguous`, with evidence. Append audit commands, results, fixes, skipped checks, and
> environmental limits to the completion record. A material deviation returns to the exact
> blueprint or implementation action. Handoff follows the audit/root-closeout contract: P7 is
> complete only after the authorized merge, P7 status-block removal, and closeout-summary update;
> do not create P8 earlier.

### P8 - `ControlPlaneFragment` integration and release conformance

#### P8 blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After authorization, create `hardware-utils-overhaul/phase-8-core-release` from the completed P7
> root, then work on
> `hardware-utils-overhaul/phase-8-core-release-blueprint`. The parent artifact is
> `docs/plans/hardware-utils-platform-parity-overhaul.md`, with completed P0-P7 phase artifact
> index entries and closeout summaries as inherited evidence. Initial ownership is `euhedral-core`
> `ControlPlaneFragment`, focused core tests, test-only `ControlPlaneCache`, hardware release/CI/
> docs, and approved non-training benchmarks. Inspect `git status --short`. Read `AGENTS.md`,
> `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, the parent plan, every exact P0-P7 blueprint/
> completion, validation, and audit file linked by the phase artifact index and summaries, final
> hardware public outputs,
> `ControlPlaneFragment`, its focused tests, `ControlPlaneCache` and its pressure tests, lattice
> monitor wiring, non-training selected-module POMs, `README.md`, current native build/resource
> instructions in `AGENTS.md`, and hardware-specific CI/signing configuration. Do not inspect any
> training path or the attached `ClosedLoopRunner` selection.
>
> Write
> `docs/blueprints/hardware-utils/phase-8-control-plane-integration-release.md`. Settle the exact
> pressure-to-batch curve, interpolation, rounding, and golden values; exact `eligibleMin`/
> `eligibleMax` behavior including a maximum of one; P/E attenuation disposition; initial/
> malformed/duplicate/out-of-order publication behavior without a second sensor-staleness policy;
> primitive cached-cap representation; linearized timestamp acceptance, cap publication, and
> `super.update` under overlapping writers; hot-loop read memory modes; sparse/null safety;
> combined test-only cache response and current hysteresis analysis; progress/drain/reset/close
> behavior; minimal monitor-to-lattice test; selected-module validation; cross-build versus
> real-runtime CI gates; signing safety; benchmark need; final API/binary/package/defect-ledger/
> hygiene checks.
>
> Core production edits are limited to `ControlPlaneFragment`. `ControlPlaneCache` is test-only; if
> its production code must change, stop and obtain separate developer approval. Any other core
> production file also requires returning to the developer. `FragmentActionPicker` dimensions/
> weights, a second core measurement smoother/TTL, routing, worker lifecycle outside this
> integration, Reactor/Spring production, training, and unrelated cleanup are prohibited.
>
> Define package ownership, naming, monitor-to-fragment data flow, and every high-reasoning contract
> without enumerating minor files unnecessarily. Include a bounded implementation context envelope
> naming required inputs and owned outputs. Specify contract-bearing files, exact formula/
> table and floating-point/integer precision, bounds, deterministic order, state and memory
> semantics, memory pollution/contamination and no-allocation evidence, safety, compatibility,
> test assertions, platform CI required-versus-unverified rules, selected Gradle commands, and the
> final release checklist. Apply the workflow sizing/split gate. If core integration and release
> conformance are independently oversized, define bounded responsibility child blueprint action
> items, branch names, and context envelopes now, then update all P8 implementation/validation/
> audit prompts, parent artifacts, and the phase artifact index. Only after this parent blueprint
> child is merged may those branches be created from the updated P8 root; rerun the gate per child.
> Do not run the root implementation after a split.
>
> Edit only blueprint/plan/planning docs. Perform the mandatory
> `Implementation model reassessment` and replace the provisional P8 implementation selection and
> complete prompt body. Append the
> workflow developer-review summary to the P8 plan section with purpose, ownership, contracts,
> children, selected implementation model, risks, and unresolved decisions.
>
> The output artifact is the finalized blueprint, plan summary, and implementation prompt. Handoff
> for review and merge into the P8 root only when implementation needs no decision about the
> response curve, attenuation, malformed snapshots, memory modes, cache boundary, CI gate,
> benchmark, or release criterion. Do not start implementation before merge.

#### P8 implementation prompt - PROVISIONAL, DO NOT RUN

**Provisional model: `gpt-5.6-sol`; provisional reasoning effort: `high`. The P8 blueprint must
replace this selection and prompt body before implementation.**

> After the P8 blueprint child is reviewed and merged, start
> `hardware-utils-overhaul/phase-8-core-release-implementation` from the P8 root. The parent
> artifact is
> `docs/blueprints/hardware-utils/phase-8-control-plane-integration-release.md`. Ownership is
> limited to its `ControlPlaneFragment`, focused tests, test-only `ControlPlaneCache`, release/
> CI/docs, and approved non-training benchmark envelope. Inspect `git status --short`. Read
> `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed P0-P7 phase artifact index entries
> and closeout summaries, the parent blueprint, and its bounded non-training context. Confirm this
> prompt is finalized.
>
> Implement only the enumerated `ControlPlaneFragment` pressure response, focused tests, approved
> test-only `ControlPlaneCache` coverage, minimal lattice integration, hardware release/package/
> API/binary tests, hardware-specific CI/configuration, documentation, and an explicitly justified
> non-training benchmark. Documentation edits may include `README.md` and the native build/resource
> portions of `AGENTS.md` when the completed work makes them stale. Do not edit
> `ControlPlaneCache` production, change action-picker inputs/
> weights, add a second core sensor TTL/smoother, redesign broader core routing/lifecycle, change
> Reactor/Spring production, or touch anything under `euhedral-training`. Allowed edits are
> blueprint-owned implementation/tests/configuration/docs, the completion record, and the compact
> temporary P8 phase-status block in `AGENTS.md`; outside the explicitly approved native-resource
> documentation refresh, no other `AGENTS.md` content may change.
>
> Run hardware verify, focused core tests, minimal lattice tests, selected Reactor/Spring
> compatibility checks, optional approved benchmarks, API/package/binary/signature gates, and
> cross-platform jobs available to the environment. Never run a reactor command that selects
> training. Stop on any unstated core policy or release decision; otherwise append completion
> notes with all changed files, commands, results, acceptance-criteria evidence, approved
> deviations, and environmental limits. Add/update the temporary `AGENTS.md` block with the
> completed P7 root, active P8 root, completed blueprint child, active implementation child, and
> blueprint/completion links.
>
> The output artifact is the implemented core/release integration plus its completion record
> appended to
> `docs/blueprints/hardware-utils/phase-8-control-plane-integration-release.md`.
>
> Handoff only when normalized pressure reaches the fragment safely, the hot path remains
> allocation/lock/I/O free, batch/cache responses are finite/monotonic/progressive, selected
> modules compile and test without training, and every known defect has a disposition. Merge this
> child into the P8 root before validation.

#### P8 final conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P8 validation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-8-core-release-audit` from the P8 root. The parent artifact is
> `docs/validations/hardware-utils/phase-8-control-plane-integration-release-validation.md`.
> Ownership is limited to independent conformance review of the complete non-training initiative
> and minor blueprint-settled corrections inside P8 ownership. Inspect `git status --short`. Read
> `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's exact completed P0-P7 phase artifact index
> entries and closeout summaries, the P8 blueprint/completion record and summarized parent
> context, complete non-training implementation diff, the P8 validation record, tests, and
> package/CI evidence. For split work, consume only the audited child context plus summarized
> parent context. Do not inspect, edit, build, or test training.
>
> Independently audit every success criterion and defect-ledger disposition. Check the
> pressure-to-batch formula and precision, bounds/ordering/publication memory semantics, hot-loop
> allocation/lock/I/O constraints, cache test boundary, selected-module isolation, package/API/
> native/runtime/signing evidence, platform required-versus-unverified rules, changed-file scope,
> stale names/docs/resources, cleanup, and validation sufficiency. Run targeted checks when the
> supplied evidence is stale or inadequate; do not silently convert unavailable external gates
> into passes.
>
> Allowed edits are
> `docs/audits/hardware-utils/phase-8-control-plane-integration-release-conformance.md`, completion
> and validation records, the P8 closeout summary in this plan, the temporary phase-status block,
> and minor blueprint-settled corrections. Rerun and record affected validation after a
> correction. New architecture, scope expansion, broad cleanup, unrelated files, and every
> training action are prohibited.
>
> The output artifacts are the final audit above, updated completion record, P8 closeout summary in
> this plan, and, after the authorized merge, removal of the temporary P8 status block on the root
> with the resulting root commit recorded when committed. Classify every requirement exactly as
> `satisfied`, `deviated`, `unverified`, or `ambiguous`, citing commands, files, and binary
> evidence and recording exact environmental limits. Append audit commands, results, fixes,
> skipped checks, and environmental limits to the completion record. A material deviation must
> name the exact phase and blueprint or implementation action to re-enter; it cannot be asserted
> release-ready.
>
> Handoff follows the audit/root-closeout contract. The initiative is complete only when all
> material requirements are satisfied and, after the authorized audit merge, the P8 status block
> is removed and the closeout summary is updated.
