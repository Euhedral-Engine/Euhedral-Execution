# Hardware Utils Platform Parity and Pressure Overhaul

## Plan status

- Phase: 1 - planning and prompt-sequence design
- Status: requirements settled; no production code changed
- Plan branch: `agent/hardware-utils-overhaul-plan` (created before the updated phase-branch rule)
- Branch point: `900d8c50` (`agent/phase7-cleanup-handoff`)
- Date: 2026-07-29
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
- Build output belongs under Maven/Zig target or generated-resource directories, never under
  `src/main/resources/bin`.
- P1 must explicitly select optimization and native hardening/portability settings. Any disabled
  stack protector/check, unwind/frame-pointer behavior, compiler-runtime bundling, or framework
  link needs a measured ABI/compatibility reason rather than inheritance from the current script.

## Scope

### In scope

- `euhedral-hardware-utils` Java implementation and tests.
- Its Linux, Windows, and macOS native implementation.
- JNI declarations, loading, ABI checks, generated headers, runtime probing, and resource cleanup.
- Zig manifest, build graph, packaging, signing order, cache use, and Maven integration.
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

- `euhedral-hardware-utils` remains Java 17.
- `euhedral-core` remains Java 21.
- Use the repository tools pinned by `mise.toml`: Java 21, Maven 3.9.16, Zig 0.16.0, and the
  configured Apple codesigning tool.
- The build must not invoke `mise` from inside `build.zig`. Maven/CI supplies explicit tool and SDK
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
implementation), it should not rely on memorized Zig syntax from training data — training data for
any current model is likely to contain a mix of pre-0.16 API shapes that will silently fail to
compile or, worse, compile with different semantics than intended. Before writing or reviewing any
`build.zig` manifest logic, the agent should pull current Zig 0.16 documentation/source (or run
`zig build --help` / inspect the pinned toolchain directly in the environment) to confirm the
actual API surface, rather than trust pattern-matched recall. This applies equally to the
OpenAI and Anthropic options above — it's a model-agnostic risk, not one the vendor choice fixes.

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
| B04 | P1 | CI copies Linux `jni_md.h` into Darwin and Win32 include folders. Replace this with platform-correct generated declarations and ABI headers. |
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
edited manually. Their removal or relocation occurs only in the P1 implementation after the
blueprint proves clean generated-resource packaging and the developer authorizes that
implementation. That one-time version-controlled removal is distinct from a build execution;
after migration, builds must never create, delete, or modify that source path.

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

- native setup portions of `.github/workflows/build.yaml` only where required to remove invalid JNI
  header preparation; its pre-existing full-reactor command is outside this initiative and never
  counts as task validation evidence
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

Each phase has a blueprint, implementation, and combined verification/conformance audit. A phase
cannot hand off with a material deviation.

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
- After the approved one-time version-controlled stale-artifact removal, build executions never
  create, delete, or modify `src/main/resources/bin`.
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
- No root Maven command that selects training is used as validation.
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

### Focused Maven validation

Preferred commands:

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils -am verify
mise exec -- mvn -B -pl euhedral-core -am test
mise exec -- mvn -B -pl euhedral-reactor-core -am test
mise exec -- mvn -B -pl euhedral-spring-core -am verify
mise exec -- mvn -B -pl benchmarks -am package -DskipTests
```

Use only the commands relevant to the current phase. `-am` for these selected modules must not
select `euhedral-training`; verify the reactor list when introducing a new command.

The normal root `mvn verify` from `AGENTS.md` is intentionally not part of this initiative because
the developer excluded the training module. The selected-module sequence is the final Maven gate.

In a restricted agent environment, use the already installed explicit toolchain:

```bash
env \
  JAVA_HOME=/home/bagotay/.local/share/mise/installs/java/21.0.2 \
  PATH=/home/bagotay/.local/share/mise/installs/zig/0.16.0:/home/bagotay/.local/share/mise/installs/java/21.0.2/bin:/usr/bin:/bin \
  SDKROOT=/home/bagotay/.local/share/mise/installs/macos-sdk/MacOSX26.1.sdk \
  /home/bagotay/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin/mvn \
  -B -pl euhedral-hardware-utils -am verify
```

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
  the invalid JNI-header preparation from the existing root workflow is allowed, but that
  workflow's pre-existing full-reactor command remains outside initiative evidence and no task
  change may broaden or rely on its training execution.

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
`-implementation`, `-validation`, and `-audit` suffixes.

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
3. Create implementation, validation, and audit children in order from the updated root; merge each
   completed child before creating its sibling.
4. Do not start the next root phase from an unmerged child.
5. Implementation and later action items maintain the temporary `AGENTS.md` phase-status block.
   After the audit child is merged and the root phase is complete, remove that block on the root
   before starting the next phase.

If a blueprint's sizing gate creates child blueprints, use the same root phase prefix with a
specific responsibility suffix, give every child its own implementation/validation/audit action
items, and merge all child results into the root before phase-level audit and closeout. The
blueprint must update this plan's prompts, parent artifacts, lineage, and phase artifact index
before handoff. Replace or expand that phase's index entry to name every parent/child blueprint and
completion record, every child validation and audit, and any root integration validation/audit.

The audit action remains responsible for root closeout. It first produces its audit on the audit
child. If the developer has not authorized the merge and closeout, it hands off a review-ready
audit, leaves the root incomplete, and prohibits the next phase. Once authorized, resume that audit
action, merge the audit child, switch to the root, remove only that hardware phase's temporary
`AGENTS.md` status block, append the phase closeout summary to this plan, and record the resulting
root commit when committed. The phase is complete only after those closeout outputs are reviewed.

### Initial phase ownership

| Plan phase | Initial package/module ownership |
| --- | --- |
| P0 | `euhedral-hardware-utils` test sources/resources and module-local compatibility tooling; non-training core/benchmark consumers are read-only |
| P1 | hardware Maven/native build assets, generated resources, `hardware_utils.internal` loader code, and hardware-specific CI |
| P2 | hardware root/common/internal topology and snapshot ownership, layout adapters, and hardware tests |
| P3 | hardware root/internal affinity and executor lifecycle, platform affinity facades, and hardware tests |
| P4 | hardware root/common/internal sampling, pressure, monitor lifecycle, provider compatibility adapters, and hardware tests; core is read-only |
| P5 | hardware Linux Java/native implementation, Linux fixtures/tests, and Linux manifest/CI metadata |
| P6 | hardware Windows Java/native implementation, Windows fixtures/tests, and Windows manifest/CI metadata |
| P7 | hardware macOS Java/native implementation, macOS fixtures/tests, and macOS manifest/CI metadata |
| P8 | `euhedral-core` `ControlPlaneFragment`, focused core tests, test-only `ControlPlaneCache`, hardware release/CI/docs, and approved non-training benchmarks |

### Phase artifact index

These prescribed paths are the exact prior-artifact index for later prompts. Each completion record
is appended to its blueprint. Each audit/root-closeout action appends a compact `P# closeout
summary` to that phase's prompt section in this plan with the root branch/commit, child results,
requirement status, approved deviations, and environmental limits. When a prompt names P0-PN
artifact-index entries, read these exact files plus those compact closeout summaries; do not infer
an unbounded feature-history context.

| Phase | Blueprint and completion record | Validation | Conformance audit |
| --- | --- | --- | --- |
| P0 | `docs/blueprints/hardware-utils/phase-0-compatibility-test-baseline.md` | `docs/validations/hardware-utils/phase-0-compatibility-test-baseline-validation.md` | `docs/audits/hardware-utils/phase-0-compatibility-test-baseline-conformance.md` |
| P1 | `docs/blueprints/hardware-utils/phase-1-native-build-jni-packaging.md` | `docs/validations/hardware-utils/phase-1-native-build-jni-packaging-validation.md` | `docs/audits/hardware-utils/phase-1-native-build-jni-packaging-conformance.md` |
| P2 | `docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md` | `docs/validations/hardware-utils/phase-2-topology-snapshot-model-validation.md` | `docs/audits/hardware-utils/phase-2-topology-snapshot-model-conformance.md` |
| P3 | `docs/blueprints/hardware-utils/phase-3-affinity-executor-lifecycle.md` | `docs/validations/hardware-utils/phase-3-affinity-executor-lifecycle-validation.md` | `docs/audits/hardware-utils/phase-3-affinity-executor-lifecycle-conformance.md` |
| P4 | `docs/blueprints/hardware-utils/phase-4-resource-monitor-pressure.md` | `docs/validations/hardware-utils/phase-4-resource-monitor-pressure-validation.md` | `docs/audits/hardware-utils/phase-4-resource-monitor-pressure-conformance.md` |
| P5 | `docs/blueprints/hardware-utils/phase-5-linux-platform.md` | `docs/validations/hardware-utils/phase-5-linux-platform-validation.md` | `docs/audits/hardware-utils/phase-5-linux-platform-conformance.md` |
| P6 | `docs/blueprints/hardware-utils/phase-6-windows-platform.md` | `docs/validations/hardware-utils/phase-6-windows-platform-validation.md` | `docs/audits/hardware-utils/phase-6-windows-platform-conformance.md` |
| P7 | `docs/blueprints/hardware-utils/phase-7-macos-platform.md` | `docs/validations/hardware-utils/phase-7-macos-platform-validation.md` | `docs/audits/hardware-utils/phase-7-macos-platform-conformance.md` |
| P8 | `docs/blueprints/hardware-utils/phase-8-control-plane-integration-release.md` | `docs/validations/hardware-utils/phase-8-control-plane-integration-release-validation.md` | `docs/audits/hardware-utils/phase-8-control-plane-integration-release-conformance.md` |

## Prompt sequence

### Reasoning-intensity ranking

Execution still follows P0 through P8. This ranking only identifies how demanding each prompt is.
Implementation selections are provisional until their blueprints complete the mandatory sizing,
split, and implementation-model reassessments.

| Rank | Prompt                                                     | OpenAI option           | Anthropic option                                                |
|-----:|------------------------------------------------------------|-------------------------|-----------------------------------------------------------------|
|    1 | P4 blueprint - sampling and pressure mathematics/lifecycle | `gpt-5.6-sol`, `max`    | Opus 4.8, thinking ~32k                                         |
|    2 | P7 blueprint - macOS public-API parity                     | `gpt-5.6-sol`, `max`    | Opus 4.8, thinking ~32k                                         |
|    3 | P6 blueprint - Windows processor-group/native parity       | `gpt-5.6-sol`, `max`    | Opus 4.8, thinking ~32k                                         |
|    4 | P5 blueprint - Linux cgroup/provider/libc portability      | `gpt-5.6-sol`, `max`    | Opus 4.8, thinking ~32k                                         |
|    5 | P3 blueprint - affinity and executor concurrency           | `gpt-5.6-sol`, `max`    | Opus 4.8, thinking ~24k                                         |
|    6 | P2 blueprint - topology and snapshot ownership             | `gpt-5.6-sol`, `max`    | Opus 4.8, thinking ~24k                                         |
|    7 | P1 blueprint - native build/JNI/package ABI                | `gpt-5.6-sol`, `max`    | Opus 4.8, thinking ~24k                                         |
|    8 | P8 blueprint - core hot-loop and release integration       | `gpt-5.6-sol`, `max`    | Opus 4.8, thinking ~32k                                         |
|    9 | P0 blueprint - compatibility/test baseline                 | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~16k                                         |
|   10 | P4 provisional implementation                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~16k                                         |
|   11 | P7 provisional implementation                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~16k                                         |
|   12 | P6 provisional implementation                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~16k                                         |
|   13 | P5 provisional implementation                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~16k                                         |
|   14 | P3 provisional implementation                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~12k                                         |
|   15 | P2 provisional implementation                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~12k                                         |
|   16 | P1 provisional implementation                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~12k                                         |
|   17 | P8 provisional implementation                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~16k                                         |
|   18 | P0 provisional implementation                              | `gpt-5.6-sol`, `medium` | Sonnet 5, thinking ~6k (Haiku 4.5 acceptable if cost-sensitive) |
|   19 | P8 validation                                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~12k                                         |
|   20 | P4 validation                                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~12k                                         |
|   21 | P7 validation                                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~12k                                         |
|   22 | P6 validation                                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~12k                                         |
|   23 | P5 validation                                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~12k                                         |
|   24 | P3 validation                                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~8k                                          |
|   25 | P2 validation                                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~8k                                          |
|   26 | P1 validation                                              | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~8k                                          |
|   27 | P0 validation                                              | `gpt-5.6-sol`, `medium` | Sonnet 5, thinking ~4k (Haiku 4.5 acceptable)                   |
|   28 | P8 final conformance audit                                 | `gpt-5.6-sol`, `high`   | Opus 4.8, thinking ~16k                                         |
|   29 | P4 conformance audit                                       | `gpt-5.6-sol`, `high`   | Opus 4.8, thinking ~16k                                         |
|   30 | P7 conformance audit                                       | `gpt-5.6-sol`, `high`   | Opus 4.8, thinking ~16k                                         |
|   31 | P6 conformance audit                                       | `gpt-5.6-sol`, `high`   | Opus 4.8, thinking ~16k                                         |
|   32 | P5 conformance audit                                       | `gpt-5.6-sol`, `high`   | Opus 4.8, thinking ~16k                                         |
|   33 | P3 conformance audit                                       | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~8k                                          |
|   34 | P2 conformance audit                                       | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~8k                                          |
|   35 | P1 conformance audit                                       | `gpt-5.6-sol`, `high`   | Sonnet 5, thinking ~8k                                          |
|   36 | P0 conformance audit                                       | `gpt-5.6-sol`, `medium` | Sonnet 5, thinking ~4k                                          |

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
> temporary worktree/output path. Do not invoke the current native-generating Maven lifecycle in
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
> this plan's P0 implementation/validation/audit lineage, parent artifacts, and phase artifact
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

#### P0 implementation prompt - PROVISIONAL, DO NOT RUN

**Provisional model: `gpt-5.6-sol`; provisional reasoning effort: `medium`. The P0 blueprint must
replace this selection and prompt body before implementation.**

> After the P0 blueprint child is reviewed and merged, start
> `hardware-utils-overhaul/phase-0-compatibility-baseline-implementation` from the P0 root. The
> parent artifact is
> `docs/blueprints/hardware-utils/phase-0-compatibility-test-baseline.md`; ownership is limited to
> its compatibility test/configuration envelope, while production sources and downstream modules
> remain read-only. Inspect `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the
> plan, the parent blueprint, and only its bounded public-source/test context. Confirm this prompt
> is no longer marked provisional.
>
> Implement only the approved signature/behavior baseline, test resources/helpers, defect-ledger
> mapping, and narrowly required hardware-module test/build configuration. Root POM/plugin changes,
> production Java/native/Zig behavior, core, CI beyond the approved baseline, benchmarks, and
> every `euhedral-training` path or command are prohibited. Do not characterize a known invalid
> numeric result as compatibility. Allowed edits are the blueprint-owned test/configuration
> artifacts, its completion record, and the compact temporary phase-status block in `AGENTS.md`;
> no other `AGENTS.md` content may change.
>
> Run the blueprint's deterministic commands and fix defects within its settled design. If a new
> design choice is required, stop, preserve work, and append the conflict/evidence to the
> blueprint. Otherwise append completion notes listing changed files, commands, results,
> acceptance-criteria evidence, approved deviations, and environmental limits. Add or update the
> workflow-required temporary
> `AGENTS.md` phase-status block with the completed planning context
> `agent/hardware-utils-overhaul-plan`, the active P0 root, completed blueprint child, active
> implementation child, and links to the blueprint and completion record.
>
> The output artifact is the implemented baseline plus its completion record appended to
> `docs/blueprints/hardware-utils/phase-0-compatibility-test-baseline.md`.
>
> Handoff only when the baseline is green, detects a deliberate descriptor/record-shape change,
> requires no published artifact, leaves the active native resource tree unchanged, and maps every
> known defect to a later phase. Merge this child into the P0 root before validation.

#### P0 validation prompt

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

### P1 - universal Zig build, JNI ABI, loader, and packaging

#### P1 blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After authorization, create `hardware-utils-overhaul/phase-1-native-build` from the completed P0
> root, then work on
> `hardware-utils-overhaul/phase-1-native-build-blueprint`. The parent artifact is
> `docs/plans/hardware-utils-platform-parity-overhaul.md`, with the completed P0 phase artifact
> index entry and closeout summary as inherited evidence. Initial ownership is hardware Maven/
> native build assets, generated resources,
> `hardware_utils.internal` loader code, and hardware-specific CI. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the parent plan, the exact P0
> blueprint/completion, validation, and audit files linked by its phase artifact index entry and
> its closeout summary, `mise.toml`, the hardware `pom.xml`, `build.zig`, native folder tree, JNI
> declarations/headers, `JNIClassLoader`, `.github/workflows/build.yaml`, and clean packaged-
> resource inventories. Do not inspect training.
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
> ordering, failure diagnostics, Maven lifecycle/resource wiring, CI signing safety, clean/rebuild
> tests, timing evidence, and binary commands. Platform sensor/topology/pressure/affinity semantics,
> core, benchmarks without an approved measurement need, unrelated CI, and all training work are
> prohibited. Task validation/runtime jobs must use a hardware-specific selected-module workflow;
> no root POM/plugin behavior inherited by training may change. The invalid header-copy step may be
> removed from the existing root workflow, but its pre-existing full-reactor command is outside
> initiative evidence and must not be modified to support this task.
>
> Define package/artifact ownership, naming, data flow, and high-reasoning build, ABI, safety, and
> compatibility contracts without enumerating minor files unnecessarily. Include a bounded
> implementation context envelope naming required inputs and owned outputs. Explicitly settle
> memory semantics, build/runtime memory pollution or artifact contamination, and mathematical
> precision in sizes, alignments, versions, and timestamps; record a reasoned `not applicable`
> only where justified. Apply the workflow sizing/split gate. If independent build, JNI, loader,
> or signing responsibilities exceed one implementation context, define responsibility-scoped
> child blueprint action items, branch names, and context envelopes now, then update all P1
> implementation/validation/audit prompts, parents, and the phase artifact index in this plan.
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
> The output artifact is the finalized blueprint, plan summary, and implementation prompt. Handoff
> for review and merge into the P1 root only when implementation needs no decision about manifest
> format, headers, output paths, signing edges, loader lookup generation/extraction,
> hardening/optimization, or runtime gates. Do not start implementation before that merge.

#### P1 implementation prompt - PROVISIONAL, DO NOT RUN

**Provisional model: `gpt-5.6-sol`; provisional reasoning effort: `high`. The P1 blueprint must
replace this selection and prompt body before implementation.**

> After the P1 blueprint child is reviewed and merged, start
> `hardware-utils-overhaul/phase-1-native-build-implementation` from the P1 root. The parent
> artifact is
> `docs/blueprints/hardware-utils/phase-1-native-build-jni-packaging.md`. Ownership is limited to
> the blueprint's hardware build, generated-resource, loader, packaging-test, and
> hardware-specific CI envelope. Inspect `git status --short`. Read `AGENTS.md`,
> `docs/AGENT_WORKFLOW.md`, the plan's completed P0 phase artifact index entry and closeout
> summary, the parent blueprint, and its exact bounded context envelope. Confirm this prompt is
> finalized.
>
> Implement only the enumerated native folder manifest, Zig graph, manifest-derived loader
> metadata, JNI generation/ABI header flow, Maven generated-resource packaging, safe
> stale-artifact migration, `JNIClassLoader` corrections, package/binary tests, and
> blueprint-approved CI setup. Platform resource/topology/pressure
> calculations, affinity behavior, core, development-only modes, unrelated workflows,
> benchmarks not named by the blueprint, and all training paths/commands are prohibited. Allowed
> edits are the blueprint-owned implementation/tests/configuration, its completion record, and the
> compact temporary P1 phase-status block in `AGENTS.md`; no other `AGENTS.md` content may change.
>
> Run clean and repeated universal builds, rebuild after removing a manifest product, exact jar
> inventory, loader fallback tests, binary gates, signature verification of the bundled copy, and
> recorded clean/warm timings. If implementation exposes an unsettled ABI or packaging choice,
> stop and append it to the blueprint. Otherwise append completion notes with changed files,
> commands, results, acceptance-criteria evidence, approved deviations, and environmental limits.
> Add/update the temporary
> `AGENTS.md` phase-status block with the completed P0 root, active P1 root, completed blueprint
> child, active implementation child, and blueprint/completion links.
>
> The output artifact is the implemented native build/package pipeline plus its completion record
> appended to `docs/blueprints/hardware-utils/phase-1-native-build-jni-packaging.md`.
>
> Handoff only when a no-flag build deterministically builds every manifest product, automatically
> discovers designated-folder sources, leaves source resources untouched after the approved
> one-time migration, packages no stale content, and signs/verifies the packaged macOS artifacts.
> Merge this child into the P1 root before validation.

#### P1 validation prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P1 implementation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-1-native-build-validation` from the P1 root. The parent artifact
> is the implementation completion record in
> `docs/blueprints/hardware-utils/phase-1-native-build-jni-packaging.md`. Ownership remains the
> P1 build/JNI/loader/package/CI envelope, with only blueprint-settled minor corrections permitted.
> Inspect `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed P0
> phase artifact index entry and closeout summary, the finalized P1 blueprint, implementation
> diff, package inventories, tests, and completion notes. Do not inspect or run training.
>
> Re-run clean, warm, repeated, and manifest-removal builds. Verify source-tree non-mutation,
> deterministic discovery, fail-loud invalid manifests, exact jar contents, platform-correct JNI
> declarations/ABI, manifest-to-package-to-loader coverage, loader LinkageError fallback, noexec
> extraction handling/diagnosis, Windows permission handling, selected hardening/runtime settings,
> parallel graph independence, staged signing order, bundled signature, architectures, exports, imports,
> deployment targets, and runtime floors. Compare timing without making an unsupported speed
> claim.
>
> Allowed edits are minor blueprint-settled test/naming/packaging corrections, the P1 completion
> record, the temporary `AGENTS.md` phase-status block, and
> `docs/validations/hardware-utils/phase-1-native-build-jni-packaging-validation.md`. Architecture
> redesign, new manifest/ABI decisions, unrelated files, and all training work are prohibited. A
> new architectural decision returns to blueprint; an ordinary defect returns to implementation.
>
> The output artifact is the validation record above, containing all commands, results, fixes,
> skips, environmental limits, and an acceptance-criterion matrix. Append its summary to the P1
> completion record and update the phase-status block. Handoff for merge into the P1 root only
> when the build/package pipeline passes every available gate and B06's intentionally deferred
> platform runtime portions are explicit. Merge this child before audit.

#### P1 conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P1 validation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-1-native-build-audit` from the P1 root. The parent artifact is
> `docs/validations/hardware-utils/phase-1-native-build-jni-packaging-validation.md`. Ownership is
> limited to independent conformance review of the P1 envelope and minor blueprint-settled
> corrections. Inspect `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the
> completed P0 phase artifact index entry and closeout summary, the P1 blueprint's summarized
> parent context, implementation diff, package inventories, completion record, and validation
> record. For split work, consume only the audited child context and summarized parent. Do not
> inspect or run training.
>
> Independently evaluate every P1 requirement and the validation evidence for deterministic
> discovery, source-tree non-mutation, manifest failures, JNI ABI, jar/loader coverage, fallback
> and extraction behavior, hardening, graph independence, signing, architectures, exports,
> imports, deployment targets, runtime floors, and timing claims. Allowed edits are
> `docs/audits/hardware-utils/phase-1-native-build-jni-packaging-conformance.md`, completion and
> validation records, the P1 closeout summary in this plan, the temporary phase-status block, and
> minor blueprint-settled corrections. If a correction is made, rerun and record affected
> validation. Redesign, new ABI/manifest decisions, unrelated files, and training are prohibited.
>
> The output artifacts are the audit above, updated completion record, P1 closeout summary in this
> plan, and, after the authorized merge, removal of the temporary P1 status block on the root with
> the resulting root commit recorded when committed. Classify every P1, B01-B05, B07, and B06
> gate-framework requirement exactly as `satisfied`, `deviated`, `unverified`, or `ambiguous`, with evidence;
> carry B06 platform runtime portions to P5-P7. Append audit commands, results, fixes, skipped
> checks, and environmental limits to the completion record. A material ABI, manifest, signing,
> or packaging deviation returns to the exact blueprint or implementation action. Handoff follows
> the audit/root-closeout contract: P1 is complete only after the authorized merge, P1
> status-block removal, and closeout-summary update; do not create P2 earlier.

### P2 - validated topology and immutable snapshot foundation

#### P2 blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After authorization, create `hardware-utils-overhaul/phase-2-topology-snapshot` from the
> completed P1 root, then work on
> `hardware-utils-overhaul/phase-2-topology-snapshot-blueprint`. The parent artifact is
> `docs/plans/hardware-utils-platform-parity-overhaul.md`, with completed P0-P1 phase artifact
> index entries and closeout summaries as inherited evidence. Initial ownership is hardware
> root/common/internal topology and snapshot code, layout adapters, and hardware tests. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, the
> parent plan, the exact P0-P1 files linked by those phase artifact index entries and summaries,
> `SystemInfo`, `TopologyMapper`, all layout adapters, `SystemUtilization`, unmodifiable wrappers,
> existing topology/snapshot tests, and non-training core consumers named by the plan. Do not
> inspect training.
>
> Write `docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md`. Settle an internal
> validated topology representation; provider/adaptor boundary; stable logical IDs; global
> socket/die/core identity; sparse/offline IDs; deterministic ordering; cache fallbacks; active
> entry completeness; public count/ID/index meanings; defensive-copy ownership; equality/hash
> behavior; SystemInfo initialization fallback; TopologyMapper coalescing, versions, and
> publication memory semantics; allowed-mask ownership; and the exact retained core-zero policy.
> Define fixtures for Linux duplicate local cores/sparse CPUs, Windows group identity, macOS
> incomplete topology, missing caches, mutation resistance, and remap/version behavior. Detailed
> platform collection parity remains in P5-P7.
>
> Preserve every public record shape, export, static facade, mask format, and current core-zero
> reservation. Pressure, monitor scheduling, executor lifecycle, detailed native platform work,
> core production changes, and training are prohibited. Edit only blueprint/plan/planning docs.
>
> Define package ownership, naming, topology-to-snapshot data flow, and high-reasoning contracts
> without enumerating minor files unnecessarily. Include a bounded implementation context envelope
> naming required inputs and owned outputs. Specify dependency order for contract-bearing
> areas, exact invariants, failure/fallback behavior, sorting, mathematical precision, memory
> access/publication semantics, allocation and memory-pollution/contamination boundaries, tests,
> and commands. Record a reasoned `not applicable` only where justified. Apply the workflow
> sizing/split gate; if independent topology, snapshot, or adapter responsibilities are too large,
> define bounded responsibility child blueprint action items, branch names, and context envelopes
> now, then update this plan's P2 implementation/validation/audit prompts, parent artifacts, and
> phase artifact index. Only after this parent blueprint child is merged may those branches be
> created from the updated P2 root; rerun the gate for every child. Do not run the root
> implementation after a split.
>
> Perform the mandatory `Implementation model reassessment` and replace the provisional P2
> implementation selection and complete body. Append the developer-review summary to the P2 plan
> section with
> purpose, ownership, key contracts, children, selected model, risks, and unresolved decisions.
> The output artifact is the finalized blueprint, plan summary, and implementation prompt. Handoff
> for review and merge into the P2 root only when implementation can proceed without choosing ID
> semantics, null-hole behavior, cache fallbacks, copy boundaries, or publication modes. Do not
> start implementation before merge.

#### P2 implementation prompt - PROVISIONAL, DO NOT RUN

**Provisional model: `gpt-5.6-sol`; provisional reasoning effort: `high`. The P2 blueprint must
replace this selection and prompt body before implementation.**

> After the P2 blueprint child is reviewed and merged, start
> `hardware-utils-overhaul/phase-2-topology-snapshot-implementation` from the P2 root. The parent
> artifact is `docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md`. Ownership is
> limited to its hardware topology/snapshot/layout/test context envelope. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed P0-P1
> phase artifact index entries and closeout summaries, the parent blueprint, and only the
> code/tests in its bounded context. Confirm this prompt is finalized.
>
> Implement the approved internal topology/snapshot foundation, `SystemInfo` and
> `TopologyMapper` corrections, deep immutable publication, equality/hash/value fixes, adapter
> compile repairs, and deterministic fixtures/tests. Detailed Linux/Windows/macOS collection
> parity, pressure, monitor, affinity/executor behavior, core production, and training are
> prohibited. Allowed edits are blueprint-owned implementation/tests, the completion record, and
> the compact temporary P2 phase-status block in `AGENTS.md`; no other `AGENTS.md` content may
> change.
>
> Run the API baseline and all topology/snapshot tests. Stop and return to blueprint on an
> unstated public count/ID or memory-publication decision. Otherwise append completion notes with
> changed files, commands, results, acceptance-criteria evidence, approved deviations, and
> environmental limits. Add/update the temporary
> `AGENTS.md` block with the completed P1 root, active P2 root, completed blueprint child, active
> implementation child, and blueprint/completion links.
>
> The output artifact is the implemented topology/snapshot foundation plus its completion record
> appended to `docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md`.
>
> Handoff only when active logical CPUs have complete deterministic mappings, sparse/missing
> topology is safe, snapshots cannot alias provider storage, remaps/versioning are correct, and
> the core-zero-only case passes. Merge this child into the P2 root before validation.

#### P2 validation prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P2 implementation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-2-topology-snapshot-validation` from the P2 root. The parent
> artifact is the implementation completion record in
> `docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md`. Ownership remains the P2
> topology/snapshot/test envelope, with only blueprint-settled minor corrections permitted.
> Inspect `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`,
> `docs/ARCHITECTURE.md`, the plan's completed P0-P1 phase artifact index entries and closeout
> summaries, the finalized P2 blueprint, implementation diff, tests, and completion notes. Do not
> inspect or run training.
>
> Re-run API/topology/snapshot tests and independently inspect deterministic ordering, sparse
> indexing, global identity, cache fallbacks, defensive copies, record equality/hash consistency,
> allowed-mask ownership, update coalescing, VarHandle/volatile publication reasoning, socket
> versions, pressure-independent membership, and core-zero behavior.
>
> Allowed edits are minor blueprint-settled local test/implementation corrections, the P2
> completion record, the temporary phase-status block, and
> `docs/validations/hardware-utils/phase-2-topology-snapshot-model-validation.md`.
> Architecture/ID/publication redesign, unrelated files, and training are prohibited. A new
> architectural decision returns to blueprint; an ordinary defect returns to implementation.
>
> The output artifact is the validation record above, containing commands, results, fixes, skips,
> environmental limits, and the acceptance matrix. Append its summary to the completion record
> and update the phase-status block. Handoff for merge into the P2 root only when P2's common
> T01-T06 portions pass and platform collection portions of T01-T03/T05 are explicitly deferred
> to P5-P7. Merge this child before audit.

#### P2 conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P2 validation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-2-topology-snapshot-audit` from the P2 root. The parent artifact
> is `docs/validations/hardware-utils/phase-2-topology-snapshot-model-validation.md`. Ownership is
> limited to independent conformance review and minor blueprint-settled P2 corrections. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`,
> `docs/ARCHITECTURE.md`, the plan's completed P0-P1 phase artifact index entries and closeout
> summaries, the P2 blueprint's summarized parent context, implementation diff, completion record,
> validation record, and tests. For split work, consume only the audited child context plus
> summarized parent. Do not inspect or run training.
>
> Independently audit deterministic ordering, sparse indexing, global identity, cache fallbacks,
> defensive copies, equality/hash consistency, allowed-mask ownership, update coalescing,
> publication memory semantics, socket versions, pressure-independent membership, core-zero
> behavior, and validation sufficiency. Allowed edits are
> `docs/audits/hardware-utils/phase-2-topology-snapshot-model-conformance.md`, completion and
> validation records, the P2 closeout summary in this plan, the temporary phase-status block, and
> minor blueprint-settled corrections. If corrected, rerun and record affected validation.
> Redesign, unrelated files, and training are prohibited.
>
> The output artifacts are the audit above, updated completion record, P2 closeout summary in this
> plan, and, after the authorized merge, removal of the temporary P2 status block on the root with
> the resulting root commit recorded when committed. Classify every P2 requirement and common
> T01-T06 portion exactly as `satisfied`, `deviated`, `unverified`, or `ambiguous`, with evidence; carry platform
> collection portions of T01-T03/T05 to P5-P7. Append audit commands, results, fixes, skipped
> checks, and environmental limits to the completion record. A material deviation returns to the
> exact blueprint or implementation action. Handoff follows the audit/root-closeout contract: P2
> is complete only after the authorized merge, P2 status-block removal, and closeout-summary
> update; do not create P3 earlier.

### P3 - affinity capability and executor lifecycle

#### P3 blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After authorization, create `hardware-utils-overhaul/phase-3-affinity-executor` from the
> completed P2 root, then work on
> `hardware-utils-overhaul/phase-3-affinity-executor-blueprint`. The parent artifact is
> `docs/plans/hardware-utils-platform-parity-overhaul.md`, with completed P0-P2 phase artifact
> index entries and closeout summaries as inherited evidence. Initial ownership is hardware root/
> internal affinity and executor lifecycle, platform affinity facades, and hardware tests. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`,
> `docs/ARCHITECTURE.md`, the parent plan, the exact P0-P2 files linked by those phase artifact
> index entries and summaries,
> `ThreadTools`, `PinnedThreadExecutor`, affinity interfaces/facades/native declarations, their
> tests, and non-training worker usage named in the plan. Do not inspect training.
>
> Write `docs/blueprints/hardware-utils/phase-3-affinity-executor-lifecycle.md`. Settle the new
> additive affinity capability type/query; exact/locality/unsupported behavior; managed logical
> ownership; deterministic empty/one-CPU/same-group/cross-group/multi-locality mask behavior with
> no partial-success claim; legacy macOS boolean success when one representable locality hint is
> applied; non-destructive original-mask discovery/restoration; release semantics; executor
> singleton acquisition; fresh-thread task identity; execute/shutdown/start races; rejection;
> interruption; truthful termination/await; cleaner and shutdown-hook ownership; map removal by
> identity; closeAll; and every happens-before/VarHandle/atomic transition. Preserve concurrent
> one-thread-per-execute behavior. Leave detailed platform native implementations to P5-P7.
>
> Specify an executable concurrency state machine, exact files, public additions, failures,
> deterministic latch/barrier tests, stress bounds, and cleanup assertions. Serializing tasks,
> claiming macOS hard affinity, monitor/pressure/platform resources, core production, and training
> are prohibited. Edit only blueprint/plan/planning docs.
>
> Define package ownership, naming, task/affinity/lifecycle data flow, and all high-reasoning
> contracts without enumerating minor files unnecessarily. Include a bounded implementation
> context envelope naming required inputs and owned outputs. Specify the concurrency state machine,
> mathematical precision for masks/deadlines, memory access and happens-before semantics, and
> memory pollution/contamination and cleanup risks; record a reasoned `not applicable` only where
> justified. Apply the workflow sizing/split gate. If affinity capability and executor lifecycle
> are independently oversized, define bounded responsibility child blueprint action items, branch
> names, and context envelopes now, then update P3 implementation/validation/audit prompts,
> parents, and the phase artifact index. Only after this parent blueprint child is merged may those
> branches be created from the updated P3 root; rerun the gate per child. Do not run the root
> implementation after a split.
>
> Perform the mandatory `Implementation model reassessment` and replace the provisional P3
> implementation selection and complete body. Append the workflow developer-review summary to the P3 plan section
> with purpose, ownership, contracts, children, model, risks, and unresolved decisions. The output
> artifact is the finalized blueprint, plan summary, and implementation prompt. Handoff for review
> and merge into the P3 root only when implementation requires no lifecycle, ownership,
> memory-order, or capability decision. Do not start implementation before merge.

#### P3 implementation prompt - PROVISIONAL, DO NOT RUN

**Provisional model: `gpt-5.6-sol`; provisional reasoning effort: `high`. The P3 blueprint must
replace this selection and prompt body before implementation.**

> After the P3 blueprint child is reviewed and merged, start
> `hardware-utils-overhaul/phase-3-affinity-executor-implementation` from the P3 root. The parent
> artifact is
> `docs/blueprints/hardware-utils/phase-3-affinity-executor-lifecycle.md`. Ownership is limited to
> its affinity/executor/facade/test context envelope. Inspect `git status --short`. Read
> `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed P0-P2 phase artifact index entries
> and closeout summaries, the parent blueprint, and its bounded context. Confirm this prompt is
> finalized.
>
> Implement only the additive capability API, common ThreadTools/managed-ownership behavior,
> `PinnedThreadExecutor` state machine/cleanup, minimal platform facade declarations needed to
> compile, and deterministic concurrency/lifecycle tests. Do not implement detailed P5-P7 native
> affinity, resource monitoring, pressure, core changes, task serialization, or training.
> Allowed edits are blueprint-owned implementation/tests, the completion record, and the compact
> temporary P3 phase-status block in `AGENTS.md`; no other `AGENTS.md` content may change.
>
> Run API, race, lifecycle, affinity restoration, unsupported-capability, and cleanup tests. If
> an unstated state transition or public semantic is needed, stop and append the conflict. Otherwise
> append completion notes with changed files, commands, results, acceptance-criteria evidence,
> approved deviations, and environmental limits. Add/update the temporary `AGENTS.md` block with
> the completed P2 root, active P3 root, completed blueprint child, active implementation child,
> and blueprint/completion links.
>
> The output artifact is the implemented affinity/executor lifecycle plus its completion record
> appended to `docs/blueprints/hardware-utils/phase-3-affinity-executor-lifecycle.md`.
>
> Handoff only when concurrent submissions remain concurrent, acquisition is identity-safe,
> execute/shutdown races are deterministic, termination is truthful, original affinity is
> restored where exact affinity exists, mask-shaped overloads never report partial coverage as
> success, and all global hooks/maps clean up. Merge this child into the P3 root before validation.

#### P3 validation prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P3 implementation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-3-affinity-executor-validation` from the P3 root. The parent
> artifact is the implementation completion record in
> `docs/blueprints/hardware-utils/phase-3-affinity-executor-lifecycle.md`. Ownership remains the
> P3 affinity/executor/test envelope, with only blueprint-settled minor corrections permitted.
> Inspect `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed
> P0-P2 phase artifact index entries and closeout summaries, the finalized P3 blueprint,
> implementation diff, tests, and completion notes. Do not inspect or run training.
>
> Re-run API and deterministic/stress lifecycle tests. Inspect the state machine and
> happens-before argument, check fresh-thread concurrency, singleton races, command failure,
> shutdown/close/restart, interrupt preservation, await deadlines, identity removal, cleaner
> reachability, hook count, unsupported pinners, and base-mask restoration.
>
> Allowed edits are minor blueprint-settled local test/implementation corrections, the P3
> completion record, the temporary phase-status block, and
> `docs/validations/hardware-utils/phase-3-affinity-executor-lifecycle-validation.md`.
> Lifecycle/capability redesign, task serialization, unrelated files, and training are prohibited.
> A new architectural decision returns to blueprint; an ordinary defect returns to implementation.
>
> The output artifact is the validation record above, with commands, results, fixes, skips,
> environmental limits, and the acceptance matrix. Append its summary to the completion record
> and update the phase-status block. Handoff for merge into the P3 root only when A01-A02 and P3
> requirements pass without material deviation. Merge this child before audit.

#### P3 conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P3 validation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-3-affinity-executor-audit` from the P3 root. The parent artifact
> is
> `docs/validations/hardware-utils/phase-3-affinity-executor-lifecycle-validation.md`. Ownership
> is limited to independent conformance review and minor blueprint-settled P3 corrections.
> Inspect `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, summarized parent
> context from the P3 blueprint, the plan's completed P0-P2 phase artifact index entries and
> closeout summaries, implementation diff, completion record, validation record, and tests. For
> split work, consume only the audited child context plus summarized parent. Do not inspect or run
> training.
>
> Independently audit the lifecycle state machine and happens-before evidence, concurrency,
> singleton races, command failure, shutdown/close/restart, interrupt preservation, deadlines,
> identity removal, cleaner reachability, hook count, unsupported pinners, base-mask restoration,
> and validation sufficiency. Allowed edits are
> `docs/audits/hardware-utils/phase-3-affinity-executor-lifecycle-conformance.md`, completion and
> validation records, the P3 closeout summary in this plan, the temporary phase-status block, and
> minor blueprint-settled corrections. Rerun and record affected validation after a correction.
> Redesign, task serialization, unrelated files, and training are prohibited.
>
> The output artifacts are the audit above, updated completion record, P3 closeout summary in this
> plan, and, after the authorized merge, removal of the temporary P3 status block on the root with
> the resulting root commit recorded when committed. Classify every A01-A02 and P3 requirement
> exactly as `satisfied`, `deviated`, `unverified`, or `ambiguous`, with evidence. Append audit commands,
> results, fixes, skipped checks, and environmental limits to the completion record. A material
> deviation returns to the exact blueprint or implementation action. Handoff follows the
> audit/root-closeout contract: P3 is complete only after the authorized merge, P3 status-block
> removal, and closeout-summary update; do not create P4 earlier.

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

#### P4 implementation prompt - PROVISIONAL, DO NOT RUN

**Provisional model: `gpt-5.6-sol`; provisional reasoning effort: `high`. The P4 blueprint must
replace this selection and prompt body before implementation.**

> After the P4 blueprint child is reviewed and merged, start
> `hardware-utils-overhaul/phase-4-pressure-monitor-implementation` from the P4 root. The parent
> artifact is
> `docs/blueprints/hardware-utils/phase-4-resource-monitor-pressure.md`. Ownership is limited to
> its common sampling/pressure/lifecycle/provider-adapter/test context; core remains read-only.
> Inspect `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed
> P0-P3 phase artifact index entries and closeout summaries, the parent blueprint, and its bounded
> source/test context. Confirm this prompt is finalized.
>
> Implement only the shared detailed-sample/validity adapter, monitor scheduler/lifecycle,
> delta/staleness/smoothing engine, listener dispatcher, pressure formulas, immutable public
> publication, minimal provider compile adapters, and deterministic tests. Do not expand
> platform-specific collection, edit core production, change public record components, alter
> action-picker policy, or access training. Allowed edits are blueprint-owned implementation/tests,
> the completion record, and the compact temporary P4 phase-status block in `AGENTS.md`; no other
> `AGENTS.md` content may change.
>
> Run fake-clock poll-start/publication/overrun tests including `0 -> 450 -> 600 ms`, invalid
> public-duration constructor tests, irregular-delta/first-sample/reset/stale tests,
> reflection-backed property tests for every ratio accessor, memory/units/timestamp tests,
> mutation tests, slow-listener/reentrant-add/Throwable/close tests, API baseline, and
> focused hardware verify.
> Stop and return to blueprint on any missing mathematical or lifecycle decision. Otherwise append
> completion notes with changed files, commands, results, acceptance-criteria evidence, approved
> deviations, and environmental limits. Add/update the temporary `AGENTS.md` block with the
> completed P3 root, active P4 root, completed blueprint child, active implementation child, and
> blueprint/completion links.
>
> The output artifact is the implemented common sampling/pressure engine plus its completion
> record appended to
> `docs/blueprints/hardware-utils/phase-4-resource-monitor-pressure.md`.
>
> Handoff only when the 200 ms poll-start grid and post-evaluation publication semantics are
> proven, invalid durations fail safely, listeners are bounded/ordered, all pressure values are
> finite and monotonic, productive utilization is not pressure, unsupported signals are neutral,
> first/reset samples do not spike, and old snapshots remain immutable. Merge this child into the
> P4 root before validation.

#### P4 validation prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P4 implementation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-4-pressure-monitor-validation` from the P4 root. The parent
> artifact is the implementation completion record in
> `docs/blueprints/hardware-utils/phase-4-resource-monitor-pressure.md`. Ownership remains the P4
> sampling/pressure/lifecycle/test envelope, with only blueprint-settled minor corrections
> permitted and core read-only. Inspect `git status --short`. Read `AGENTS.md`,
> `docs/AGENT_WORKFLOW.md`, the plan's completed P0-P3 phase artifact index entries and closeout
> summaries, the finalized P4 blueprint, implementation diff, completion notes, and tests. Do not
> inspect or run training.
>
> Independently rederive/check units and pressure bounds; re-run fake-clock, poll-start/
> publication/overrun, duration-boundary, irregular interval, first-sample, reset/wrap, zero-limit,
> stale/failure, listener ordering/coalescing/Error/close, timestamp, immutability, exhaustive
> listener reentrant mutation, ratio-property, API, and hardware verification tests. Check that
> high healthy I/O and productive
> CPU work stay low, each pressure signal is monotonic, correlated signals are not accidentally
> amplified, no state sidecar bypasses snapshot ownership, and no polling/listener resource leaks
> remain. Make only minor blueprint-settled fixes.
>
> Allowed edits are minor blueprint-settled local test/implementation corrections, the P4
> completion record, the temporary phase-status block, and
> `docs/validations/hardware-utils/phase-4-resource-monitor-pressure-validation.md`.
> Mathematical/lifecycle redesign, platform expansion, core production changes, unrelated files,
> and training are prohibited. A new architectural decision returns to blueprint; an ordinary
> defect returns to implementation.
>
> The output artifact is the validation record above, with commands, results, fixes, skips,
> environmental limits, and the acceptance matrix. Append its summary to the completion record
> and update the phase-status block. Handoff for merge into the P4 root only when P4's common
> portions of R01-R10/R13-R14 and all P4 requirements pass, with platform portions of R01-R04,
> R06, and R13-R14 explicitly deferred to P5-P7. Merge this child before audit.

#### P4 conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P4 validation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-4-pressure-monitor-audit` from the P4 root. The parent artifact is
> `docs/validations/hardware-utils/phase-4-resource-monitor-pressure-validation.md`. Ownership is
> limited to independent conformance review and minor blueprint-settled P4 corrections. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the P4 blueprint's summarized
> parent context, the plan's completed P0-P3 phase artifact index entries and closeout summaries,
> implementation diff, completion record, validation record, and tests. For split work, consume
> only the audited child context plus summarized parent. Do not inspect or run training.
>
> Independently audit the formulas, precision, finite `[0.0, 1.0]` bounds, units, correlated-signal
> treatment, exact 200 ms poll-start recurrence and `0 -> 450 -> 600 ms` case, publication
> timing/memory semantics, duration safety, first/reset behavior, staleness, smoothing, listener
> bounds/reentrancy/close, immutable ownership, memory contamination, resource cleanup, and
> validation sufficiency. Allowed edits are
> `docs/audits/hardware-utils/phase-4-resource-monitor-pressure-conformance.md`, completion and
> validation records, the P4 closeout summary in this plan, the temporary phase-status block, and
> minor blueprint-settled corrections. Rerun and record affected validation after a correction.
> Mathematical/lifecycle redesign, platform expansion, core production, unrelated files, and
> training are prohibited.
>
> The output artifacts are the audit above, updated completion record, P4 closeout summary in this
> plan, and, after the authorized merge, removal of the temporary P4 status block on the root with
> the resulting root commit recorded when committed. Classify every P4 requirement and common
> portion of R01-R10/R13-R14 exactly as `satisfied`, `deviated`, `unverified`, or `ambiguous`, with evidence;
> carry the named platform portions to P5-P7. Append audit commands, results, fixes, skipped
> checks, and environmental limits to the completion record. A material deviation returns to the
> exact blueprint or implementation action. Handoff follows the audit/root-closeout contract: P4
> is complete only after the authorized merge, P4 status-block removal, and closeout-summary
> update; do not create P5 earlier.

### P5 - Linux parity, cgroups, and libc portability

#### P5 blueprint prompt

**Model: `gpt-5.6-sol`; reasoning effort: `max`.**

> After authorization, create `hardware-utils-overhaul/phase-5-linux` from the completed P4 root,
> then work on `hardware-utils-overhaul/phase-5-linux-blueprint`. The parent artifact is
> `docs/plans/hardware-utils-platform-parity-overhaul.md`, with completed P0-P4 phase artifact
> index entries and closeout summaries as inherited evidence. Initial ownership is hardware Linux
> Java/native implementation, Linux fixtures/tests, and Linux manifest/CI metadata. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`,
> `docs/ARCHITECTURE.md`, the parent plan, the exact P0-P4 files linked by those phase artifact
> index entries and summaries, all Linux Java/native sources, the P1 manifest/build contracts,
> Linux provider/topology tests, and proposed proc/sysfs/cgroup fixtures. Do not inspect training.
>
> Write `docs/blueprints/hardware-utils/phase-5-linux-platform.md`. Settle read-only cgroup
> v1/v2/hybrid/bare-host discovery and scope; mountinfo/cgroup parsing; cpuset/quota changes;
> unlimited quota; canonical cumulative CPU/throttle/PSI units; pressure reset semantics; process
> and host CPU scope; honest cgroup-aggregate pressure propagation to effective CPUs without host
> jiffy apportionment; sparse/offline multisocket topology/cache; complete bounded file reads;
> channel ownership; diagnostic rate limiting; correct block-device inclusion; scheduler/steal,
> memory reclaim/headroom, I/O stall, frequency and thermal signals with cadences/validity; stable
> affinity syscalls; and the Linux native ABI.
>
> Derive and prove the lowest practical kernel floor per x86-64/AArch64 from the required syscall
> and JDK surface; do not choose a floor newer than 3.10 without developer approval. Prove a
> libc-neutral direct-syscall design on that floor if viable. Otherwise document the failing gate
> and specify glibc 2.17 plus musl artifacts. Define exact syscalls, structure layouts, errno
> handling, imported-symbol allowlists, no-libstdc++ requirement, fallback behavior, file/path
> fixtures, Testcontainers/runtime images, and binary commands.
>
> Shared pressure redesign, Windows/macOS, core, unrelated build changes, and all training work are
> prohibited. Edit only blueprint/plan/planning docs.
>
> Define package/artifact ownership, naming, file/native-to-sample data flow, and all
> high-reasoning contracts without enumerating minor files unnecessarily. Include a bounded
> implementation context envelope naming required inputs and owned outputs. Explicitly settle
> integer/floating-point precision and conversions, deterministic ordering, native/JVM memory
> ownership and publication, buffer safety, allocation/retention and memory
> pollution/contamination, portability, and compatibility. Apply the workflow sizing/split gate.
> If cgroup/resource collection, topology, affinity/native ABI, or portability gates are
> independently oversized, define bounded responsibility child blueprint action items, branch
> names, and context envelopes now, then update every P5 implementation/validation/audit prompt,
> parent, and phase artifact index entry. Only after this parent blueprint child is merged may
> those branches be created from the updated P5 root; rerun the gate per child. Do not run the root
> implementation after a split.
>
> Perform the mandatory `Implementation model reassessment` and replace the provisional P5
> implementation selection and complete prompt body.
>
> Append the workflow developer-review summary to the P5 plan section with purpose, ownership,
> contracts, children, selected implementation model, risks, and unresolved decisions. The output
> artifact is the finalized blueprint, plan summary, and implementation prompt. Handoff for review
> and merge into the P5 root only when implementation needs no cgroup scope, unit, file-read,
> device-filter, sensor-cadence, syscall, libc, or fallback decision. Do not start implementation
> before merge.

#### P5 implementation prompt - PROVISIONAL, DO NOT RUN

**Provisional model: `gpt-5.6-sol`; provisional reasoning effort: `high`. The P5 blueprint must
replace this selection and prompt body before implementation.**

> After the P5 blueprint child is reviewed and merged, start
> `hardware-utils-overhaul/phase-5-linux-implementation` from the P5 root. The parent artifact is
> `docs/blueprints/hardware-utils/phase-5-linux-platform.md`. Ownership is limited to its Linux
> Java/native/fixture/test/manifest/CI context envelope. Inspect `git status --short`. Read
> `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed P0-P4 phase artifact index entries
> and closeout summaries, the parent blueprint, and only its bounded Linux context. Confirm this
> prompt is finalized.
>
> Implement the approved Linux topology/resource/affinity native and Java adapters, cgroup
> variants, signal collection, file ownership/diagnostics, fixtures/tests, and named manifest/CI
> metadata. Do not redesign common pressure, touch Windows/macOS/core, mutate cgroup controllers,
> or access training. Allowed edits are blueprint-owned implementation/tests/configuration, the
> completion record, and the compact temporary P5 phase-status block in `AGENTS.md`; no other
> `AGENTS.md` content may change.
>
> Run all fixture matrices, no-write checks, API/common contract tests, Linux native boundary
> tests, a cgroup-scope-versus-host-activity attribution fixture, glibc/musl x86-64/AArch64 binary
> gates, and real runtime smoke where available. Stop on an unstated scope/fallback/ABI choice;
> otherwise append completion notes with changed files, commands, results, acceptance-criteria
> evidence, approved deviations, and environmental limits. Add/update the temporary `AGENTS.md`
> block with the completed P4 root, active P5 root, completed blueprint child, active
> implementation child, and blueprint/completion links.
>
> The output artifact is the implemented Linux platform layer plus its completion record appended
> to `docs/blueprints/hardware-utils/phase-5-linux-platform.md`.
>
> Handoff only when cgroup v1/v2/hybrid/bare host, quota `max`, PSI reset, sparse topology, normal
> block devices, bounded reads, resource cleanup, affinity, and accepted libc/runtime gates pass
> or unavailable real-runtime gates are explicitly `unverified` and carried as release blockers
> pending a developer-approved deviation. Merge this child into the P5 root before validation.

#### P5 validation prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P5 implementation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-5-linux-validation` from the P5 root. The parent artifact is the
> implementation completion record in
> `docs/blueprints/hardware-utils/phase-5-linux-platform.md`. Ownership remains the bounded Linux
> platform envelope, with only blueprint-settled minor corrections permitted. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed P0-P4
> phase artifact index entries and closeout summaries, the finalized P5 blueprint, implementation
> diff, fixtures, tests, binaries, and completion notes. Do not inspect or run training.
>
> Re-run and adversarially review cgroup scope without writes, v1/v2/hybrid/bare fixtures, unlimited
> quota, cpuset changes, PSI zero/reset/staleness, cgroup-versus-host attribution without
> fabricated per-CPU apportionment, complete large/partial reads, missing-path log behavior,
> ordinary/loop device accounting, duplicate local core IDs, sparse/offline CPUs,
> cache fallback, sensor validity/cadence, JNI buffer validation, affinity restore, imports/
> `DT_NEEDED`/GLIBC versions, architecture, and real glibc/musl smoke. Make only local
> blueprint-settled corrections.
>
> Allowed edits are minor blueprint-settled Linux test/implementation corrections, the P5
> completion record, the temporary phase-status block, and
> `docs/validations/hardware-utils/phase-5-linux-platform-validation.md`. Common-contract redesign,
> other platforms, core, unrelated files, and training are prohibited. A new architectural
> decision returns to blueprint; an ordinary defect returns to implementation.
>
> The output artifact is the validation record above, with commands, results, fixes, skips,
> environmental limits, and the acceptance matrix. Append its summary to the P5 completion record
> and update the phase-status block. Handoff for merge into the P5 root only when Linux portions
> of R01-R02, R06, R11-R14, T02, T05, B06, and all P5 requirements pass, or unavailable
> real-runtime gates are explicitly carried as release-blocking `unverified` items. Merge this
> child before audit.

#### P5 conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P5 validation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-5-linux-audit` from the P5 root. The parent artifact is
> `docs/validations/hardware-utils/phase-5-linux-platform-validation.md`. Ownership is limited to
> independent conformance review and minor blueprint-settled Linux corrections. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the P5 blueprint's summarized
> parent context, the plan's completed P0-P4 phase artifact index entries and closeout summaries,
> implementation diff, fixtures, binaries, completion record, and validation record. For split
> work, consume only the audited child context plus summarized parent. Do not inspect or run
> training.
>
> Independently audit cgroup read-only scope and attribution, fixture completeness, resets,
> bounded reads, device filtering, topology, sensor validity/cadence, native bounds/ownership,
> affinity restore, libc neutrality or accepted glibc 2.17 plus musl fallback, imports/
> `DT_NEEDED`/GLIBC versions, architectures, kernel floors, real-runtime evidence, and validation
> sufficiency. Allowed edits are
> `docs/audits/hardware-utils/phase-5-linux-platform-conformance.md`, completion and validation
> records, the P5 closeout summary in this plan, the temporary phase-status block, and minor
> blueprint-settled corrections. Rerun and record affected validation after a correction. Common
> redesign, other platforms, core, unrelated files, and training are prohibited.
>
> The output artifacts are the audit above, updated completion record, P5 closeout summary in this
> plan, and, after the authorized merge, removal of the temporary P5 status block on the root with
> the resulting root commit recorded when committed. Classify every Linux portion of R01-R02, R06,
> R11-R14, T02, T05, B06, and P5 requirement exactly as `satisfied`, `deviated`, `unverified`, or
> `ambiguous`, with evidence. Append audit commands, results, fixes, skipped checks, and
> environmental limits to the completion record. A material deviation returns to the exact
> blueprint or implementation action. Handoff follows the audit/root-closeout contract: P5 is
> complete only after the authorized merge, P5 status-block removal, and closeout-summary update;
> do not create P6 earlier.

### P6 - Windows processor-group and resource parity

#### P6 blueprint prompt

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

#### P6 implementation prompt - PROVISIONAL, DO NOT RUN

**Provisional model: `gpt-5.6-sol`; provisional reasoning effort: `high`. The P6 blueprint must
replace this selection and prompt body before implementation.**

> After the P6 blueprint child is reviewed and merged, start
> `hardware-utils-overhaul/phase-6-windows-implementation` from the P6 root. The parent artifact
> is `docs/blueprints/hardware-utils/phase-6-windows-platform.md`. Ownership is limited to its
> Windows Java/native/fixture/test/manifest/CI context envelope. Inspect `git status --short`.
> Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed P0-P5 phase artifact index
> entries and closeout summaries, the parent blueprint, and only its bounded Windows context.
> Confirm this prompt is finalized.
>
> Implement the approved Windows topology parser/provider, processor-group mapping, affinity,
> resource/capacity signals, timer/native safety, fixtures/tests, and named manifest/CI metadata.
> Do not redesign shared pressure, touch Linux/macOS/core, use private APIs, or access training.
> Allowed edits are blueprint-owned implementation/tests/configuration, the completion record, and
> the compact temporary P6 phase-status block in `AGENTS.md`; no other `AGENTS.md` content may
> change.
>
> Run GLPIEx one/multi-group/bit63/>64/malformed fixtures, common provider contracts, affinity and
> mask-shape/partial-success/native buffer/race tests, x86-64 and ARM64 PE gates, Windows x86-64
> runtime smoke, and ARM64 smoke where available. Stop on an unstated structure/API/runtime
> decision; otherwise append completion notes with changed files, commands, results,
> acceptance-criteria evidence, approved deviations, and environmental limits. Add/update the
> temporary `AGENTS.md` block with the completed P5 root, active P6 root, completed blueprint
> child, active implementation child, and blueprint/completion links.
>
> The output artifact is the implemented Windows platform layer plus its completion record
> appended to `docs/blueprints/hardware-utils/phase-6-windows-platform.md`.
>
> Handoff only when Windows loads normally, topology and global IDs are complete, counters use
> canonical units, multi-group affinity reports correctly, older documented fallbacks work, and
> runtime/import gates are evidenced; an unavailable minimum-family runtime remains an explicit
> release-blocking `unverified` item. Merge this child into the P6 root before validation.

#### P6 validation prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P6 implementation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-6-windows-validation` from the P6 root. The parent artifact is
> the implementation completion record in
> `docs/blueprints/hardware-utils/phase-6-windows-platform.md`. Ownership remains the bounded
> Windows platform envelope, with only blueprint-settled minor corrections permitted. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed P0-P5
> phase artifact index entries and closeout summaries, the finalized P6 blueprint, implementation
> diff, fixtures, tests, binaries, and completion notes. Do not inspect or run training.
>
> Re-run and adversarially inspect structure offsets/alignment/bounds, truncated blobs, bit 63,
> multiple groups and >64 processors, deterministic logical IDs, packages/cores/caches/efficiency,
> current/release affinity, job quota and mask units, cumulative counters, working-set underflow,
> capacity signals, null/short arrays, concurrent initialization, timer JNI symbols, dynamic API
> fallback, PE architectures/imports, and real runtime smoke. Make only blueprint-settled fixes.
>
> Allowed edits are minor blueprint-settled Windows test/implementation corrections, the P6
> completion record, the temporary phase-status block, and
> `docs/validations/hardware-utils/phase-6-windows-platform-validation.md`. Shared redesign, other
> platforms, core, unrelated files, and training are prohibited. A new architectural decision
> returns to blueprint; an ordinary defect returns to implementation.
>
> The output artifact is the validation record above, with commands, results, fixes, skips,
> environmental limits, and the acceptance matrix. Append its summary to the P6 completion record
> and update the phase-status block. Handoff for merge into the P6 root only when Windows portions
> of R01, R04, R13, T03, T05, A03, N01, B06, and every P6 requirement pass, or unavailable
> minimum-family runtime gates remain explicit release-blocking `unverified` items. Merge this
> child before audit.

#### P6 conformance audit prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P6 validation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-6-windows-audit` from the P6 root. The parent artifact is
> `docs/validations/hardware-utils/phase-6-windows-platform-validation.md`. Ownership is limited
> to independent conformance review and minor blueprint-settled Windows corrections. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the P6 blueprint's summarized
> parent context, the plan's completed P0-P5 phase artifact index entries and closeout summaries,
> implementation diff, fixtures/binaries, completion record, and validation record. For split
> work, consume only the audited child context plus summarized parent. Do not inspect or run
> training.
>
> Independently audit GLPIEx bounds/layout, global processor identity, >64-CPU/group behavior,
> topology/cache/efficiency, affinity apply/current/release and no-partial-success semantics, quota
> and counter units, capacity signals, native buffer safety/initialization, dynamic fallbacks, PE
> ABI/import/runtime floors, real-runtime evidence, and validation sufficiency. Allowed edits are
> `docs/audits/hardware-utils/phase-6-windows-platform-conformance.md`, completion and validation
> records, the P6 closeout summary in this plan, the temporary phase-status block, and minor
> blueprint-settled corrections. Rerun and record affected validation after a correction. Shared
> redesign, other platforms, core, unrelated files, and training are prohibited.
>
> The output artifacts are the audit above, updated completion record, P6 closeout summary in this
> plan, and, after the authorized merge, removal of the temporary P6 status block on the root with
> the resulting root commit recorded when committed. Classify every Windows portion of R01, R04,
> R13, T03, T05, A03, N01, B06, and P6 requirement exactly as `satisfied`, `deviated`, `unverified`, or
> `ambiguous`, with evidence. Append audit commands, results, fixes, skipped checks, and
> environmental limits to the completion record. A material deviation returns to the exact
> blueprint or implementation action. Handoff follows the audit/root-closeout contract: P6 is
> complete only after the authorized merge, P6 status-block removal, and closeout-summary update;
> do not create P7 earlier.

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

#### P7 validation prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P7 implementation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-7-macos-validation` from the P7 root. The parent artifact is the
> implementation completion record in
> `docs/blueprints/hardware-utils/phase-7-macos-platform.md`. Ownership remains the bounded macOS
> platform envelope, with only blueprint-settled minor corrections permitted. Inspect
> `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan's completed P0-P6
> phase artifact index entries and closeout summaries, the finalized P7 blueprint, implementation
> diff, fixtures, tests, binaries, and completion notes. Do not inspect or run training.
>
> Re-run and independently inspect all public API usage, Intel/Apple Silicon topology ordering and
> fallbacks, complete cache/info maps, cumulative counter units, working-memory semantics, thermal/
> low-power validity, Mach buffer/timebase cleanup, shift bounds, managed logical ownership,
> `LOCALITY_HINT`, tag-zero release, unsupported physical CPU behavior, absence of realtime
> policy, CPU/I/O telemetry pressure neutrality, deployment target, frameworks/imports, bundled
> signature, and real runtime smoke. Make only blueprint-settled fixes.
>
> Allowed edits are minor blueprint-settled macOS test/implementation corrections, the P7
> completion record, the temporary phase-status block, and
> `docs/validations/hardware-utils/phase-7-macos-platform-validation.md`. Private APIs, semantic
> redesign, other platforms, core, unrelated files, and training are prohibited. A new
> architectural decision returns to blueprint; an ordinary defect returns to implementation.
>
> The output artifact is the validation record above, with commands, results, fixes, skips,
> environmental limits, and the acceptance matrix. Append its summary to the P7 completion record
> and update the phase-status block. Handoff for merge into the P7 root only when macOS portions
> of R01, R03, R13, T01, T05, A04, N02, B06, and all P7 requirements pass, or unavailable
> minimum-family runtime gates remain explicit release-blocking `unverified` items. Merge this
> child before audit.

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
> test assertions, platform CI required-versus-unverified rules, selected Maven commands, and the
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

#### P8 validation prompt

**Model: `gpt-5.6-sol`; reasoning effort: `high`.**

> After the P8 implementation child is reviewed and merged, start
> `hardware-utils-overhaul/phase-8-core-release-validation` from the P8 root. The parent artifact
> is the implementation completion record in
> `docs/blueprints/hardware-utils/phase-8-control-plane-integration-release.md`. Ownership remains
> the bounded P8 core-integration/release envelope, with only blueprint-settled minor corrections
> permitted. Inspect `git status --short`. Read `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the plan,
> its exact completed P0-P7 phase artifact index entries and closeout summaries, the P8 blueprint
> and completion record, the complete non-training implementation diff, tests, and final
> package/CI evidence. Do not inspect, edit, build, or test training.
>
> Run every phase's required validation that is available: hardware `verify`; focused core and
> lattice tests; selected Reactor/Spring compatibility; approved benchmark only if a performance
> claim exists; compatibility baseline; clean/repeated/manifest-removal jar inventory; native
> architecture/export/import/runtime-floor/deployment/signature gates; deterministic platform
> fixtures; real platform smoke jobs; pressure/cadence/listener/lifecycle/property tests; executor
> races; and final thread/native-resource cleanup. Use explicit selected-module Maven commands and
> no root command that includes training.
>
> Check every success criterion and every defect-ledger ID. Verify `git diff --check`, stale
> names/resources/platform-support documentation, exact changed-file scope,
> `git diff --name-only 900d8c50 -- euhedral-training` has no output, and
> `git status --short -- euhedral-training` has no output. Make only minor
> blueprint-settled corrections such as missing deterministic assertions, local naming/formatting,
> or validation omissions; do not redesign architecture.
>
> Allowed edits are those minor blueprint-settled corrections, the P8 completion record, the
> temporary phase-status block, and
> `docs/validations/hardware-utils/phase-8-control-plane-integration-release-validation.md`. New
> architecture, scope expansion, broad cleanup, unrelated files, and every training action are
> prohibited. A new architectural decision returns to blueprint; an ordinary defect returns to
> implementation.
>
> The output artifact is the validation record above, with every command, result, fix, skip, exact
> environmental limit, changed-file/scope check, known-defect disposition, and acceptance matrix.
> Append its summary to the completion record and update the phase-status block. Handoff for merge
> into the P8 root only when no material deviation remains and every unavailable platform gate is
> explicitly recorded according to the approved required-versus-unverified rules. Merge this
> child before audit.

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
