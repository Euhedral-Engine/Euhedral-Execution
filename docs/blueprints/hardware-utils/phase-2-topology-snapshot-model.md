# Phase 2 Validated Topology and Immutable Snapshot Foundation

## Status and authority

- Parent plan: `docs/plans/hardware-utils-platform-parity-overhaul.md`
- Inherited completed P1 root commit: `11e21945`
- P2 root branch: `hardware-utils-overhaul/phase-2-topology-snapshot`
- Parent blueprint branch: `hardware-utils-overhaul/phase-2-topology-snapshot-blueprint`
- Owning module: `euhedral-hardware-utils`
- Blueprint model: `gpt-5.6-sol`
- Blueprint reasoning effort: `max`
- Status: parent contract complete; developer review and merge into the P2 root are required before
  either child blueprint starts

This blueprint is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the parent plan, and the
compiled P0 compatibility gate. It freezes the common P2 architecture and divides implementation
into two sequential responsibility children. There is no P2 root implementation action.

The P1-A blueprint and both P1 child audit files named by the phase artifact index were deliberately
deleted by the developer. The surviving P1 parent blueprint, Child B blueprint/completion, root
audit, and P1 closeout summary are the inherited evidence. The missing historical chain remains the
explicit P1 ambiguity already recorded at closeout; P2 does not reconstruct it.

If a child finds that stable ID semantics, public count/index meanings, null-hole behavior, cache
fallbacks, copy boundaries, equality, core-zero policy, version rules, or publication modes must
change, it returns to this parent blueprint. It must not make a private choice.

### Authorized toolchain-policy revision

The developer authorized this documentation revision on 2026-08-01. Every Java command, Maven
command, and Maven build defaults to the exact versions in `mise.toml`; a documented
restricted-environment fallback must use the corresponding pinned installed tools and record its
versions and limits.

## Objective

P2 introduces one validated, immutable topology model between platform discovery and the existing
public facade, and makes topology-derived public snapshots safe to publish concurrently. Completion
must:

1. normalize platform observations into deterministic socket, die, core, logical-CPU, and cache
   identities;
2. preserve stable logical IDs that remain usable by the existing mask and affinity surfaces;
3. make sparse and offline CPU IDs safe without changing public records or mask formatting;
4. guarantee a complete public topology and cache record for every active logical CPU, including a
   conservative whole-model fallback when platform discovery is unavailable or invalid;
5. define exact public ID, count, array-index, and null-hole meanings used by existing core code;
6. defensively own every published array, `BitSet`, list, and wrapper value and make equality and
   hash behavior content-consistent;
7. make `TopologyMapper` own its allowed mask, coalesce concurrent requests without losing the
   newest membership, version only membership changes, and publish through a documented Java
   Memory Model boundary; and
8. preserve the current core-zero management reservation while making the core-zero-only case
   nonempty.

P2 is a common contract and normalization phase. Detailed sysfs/cgroup discovery, bounded Windows
GLPIEx parsing, complete macOS topology synthesis, native affinity, monitor scheduling, and pressure
semantics remain in P3-P7.

## Scope

### Shared owned surface

The two children together own only:

- hardware root/common/internal topology and snapshot implementation;
- `SystemInfo` and `TopologyMapper`;
- the Linux, Windows, and macOS layout adapters at their common provider boundary;
- the unmodifiable value wrappers;
- hardware topology, snapshot, ownership, equality, and remap tests and deterministic fixtures;
- the two child blueprints and their completion/conformance records;
- this blueprint and the P2 planning/closeout sections; and
- the temporary P2 status block required during implementation through audit.

`module-info.java` is a compatibility input. The new internal topology package is not exported and
must not require a module-descriptor change.

### Read-only compatibility inputs

- The P0 compiled API fixture, defect ledger, completion, and conformance evidence.
- The surviving P1 artifacts and closeout described above.
- `ResourceMonitor` only at its `HardwareUtilization -> TopologyMapper` publication boundary.
- The parent plan's named non-training core consumers, especially `ControlPlaneLattice`,
  `ControlPlaneShard`, `ControlPlaneCache`, `GlobalState`, `WorkRequester`, `LatticeEdge`,
  `LatticeVertex`, `UpstreamQueue`, `AbstractFrame`, `AbstractExecutor`, `CloneableObject`,
  `BaseCloneableObject`, and `FrameFactory`.
- Existing affinity and resource providers only where compilation depends on the settled ID/index
  meanings.

### Prohibited work

- Any public record-component, constructor, field, method, descriptor, export, or static-facade
  removal/change.
- Any change to canonical CPU-mask text.
- Pressure formulas, counter units, staleness, normalization, smoothing, polling cadence, monitor
  scheduling/listener lifecycle, or resource-provider collection semantics.
- `PinnedThreadExecutor`, `ThreadTools`, affinity capability, executor lifecycle, or current-CPU
  behavior.
- Detailed Linux, Windows, or macOS native/platform parity work assigned to P5-P7.
- Core production edits, routing redesign, benchmarks, Reactor, Spring, root POM, native build,
  package, loader, or CI changes.
- Any inspection, edit, build, test, documentation, or command under `euhedral-training`, and any
  reactor command that selects it.

## Architectural boundary and package ownership

### Internal topology package

Add an unexported package:

```text
io.euhedral_execution.hardware_utils.internal.topology
```

It owns the high-reasoning contract types. Exact local file decomposition is left to the child,
but the following names and roles are fixed:

- `TopologyProvider`: platform-adapter SPI whose sole operation returns one owned
  `TopologyInput`.
- `TopologyInput`: immutable raw common input containing logical CPUs and optional cache domains.
- `LogicalCpu`: one platform observation with a nonnegative logical CPU ID and canonical socket,
  die, and core source keys plus a `CoreKind` hint.
- `CacheDomain`: one optional L1, L2, or L3 domain with size, line size, and logical-CPU sharers.
- `CoreKind`: `PERFORMANCE`, `EFFICIENCY`, or `UNKNOWN`.
- `TopologyValidationException`: internal actionable failure carrying provider and offending
  key/ID context; it never crosses a public signature.
- `TopologyNormalizer`: validates and canonicalizes a complete input without accessing static
  `SystemInfo` topology state.
- `TopologyModel`: the immutable validated result and the only input used to initialize the public
  `SystemInfo` facade.
- `TopologyBootstrap`: selects the current platform provider, separates topology and resource
  provider initialization, logs one actionable diagnostic, and chooses the whole-model fallback.

These types are internal implementation contracts, not additive public API. Children may use
package-private nested value records where that keeps the package small, but may not rename the
roles or leak them through an exported signature.

### Platform adapters

`LinuxSystemLayout`, `WindowsSystemLayout`, and `OSXSystemLayout` remain in their existing exported
packages with every existing public member preserved. Each becomes or delegates to a
`TopologyProvider` and projects the validated model through its legacy map getters where those
getters already exist.

Each selected layout singleton owns exactly one `TopologyInput` collection and one normalized
`TopologyModel`. A module-internal accessor gives `TopologyBootstrap` that same model; legacy map
getters project the same instance and do not recollect or renormalize. Layout construction failure
is allowed to escape to `TopologyBootstrap`, which installs the common fallback. No second
platform topology singleton or mutable registry is introduced.

An adapter owns only collection and platform-to-common translation:

- it emits logical IDs and canonical source keys;
- it copies native/file/parser buffers before returning;
- it reports optional cache data when present; and
- it throws an actionable platform-scoped exception for malformed required input.

It does not assign global socket/core IDs, synthesize public records, apply core-zero reservation,
build effective topology, or publish snapshots. Platform-specific completeness beyond the minimum
inputs described here remains in P5-P7.

### Public facade and snapshot ownership

The root package continues to own `SystemInfo` and `TopologyMapper`. The common package continues to
own `SystemUtilization`, `UnmodifiableBitSet`, and `UnmodifiableDoubleArray`. No new exported
package is added.

The data flow is:

```text
platform files / documented API / existing native parser
                       |
                       v
                 TopologyProvider
                       |
                       v
             owned immutable TopologyInput
                       |
                       v
              TopologyNormalizer
        validate -> sort -> assign global IDs
        complete cache domains -> freeze ownership
                       |
                       v
                 TopologyModel
                  /          \
                 v            v
       SystemInfo facade   TopologyMapper
       static records      effective membership
                              |
HardwareUtilization ---------+
                              |
                              v
              immutable topology-indexed snapshots
                              |
                              v
                existing core consumers (read-only)
```

## Validated topology contract

### Logical CPU identity

A logical CPU ID is a stable nonnegative integer used simultaneously as:

- the bit position in every public CPU mask;
- the key returned by `SystemInfo.getCpuInfo` and `getCacheLayout`;
- the value of `CpuInfo.cpu()` and `CpuCacheLayout.cpu()`; and
- the index in per-logical-CPU public arrays.

The mapping is platform-specific but deterministic:

- Linux: the kernel logical CPU number. Sparse online IDs remain sparse; an absent/offline CPU is
  not renumbered and creates a hole.
- Windows: `group * 64 + processorNumber`, with `group >= 0` and processor number in `[0, 63]`.
  This is bijective, preserves bit 63, and is independent of relationship enumeration order.
- macOS: stable process-visible logical ordinals `0..N-1`. P7 may improve topology relationships,
  but it must retain these ownership IDs for the same boot/process visibility.
- Common fallback: ordinals `0..max(1, availableProcessors)-1`.

The maximum accepted logical ID is `1_048_575`; therefore the maximum public CPU index span is
`1_048_576`. Negative, duplicate, or larger IDs fail normalization with the provider name and
offending ID. This bound prevents a malformed sparse ID from forcing unbounded arrays. A selected
platform failure causes `SystemInfo` to use the complete common fallback; direct normalizer tests
still observe the actionable validation failure.

The normalizer also rejects more than 65,536 active logical CPUs or a topology whose sum of
`highestLogicalCpuInCore + 1` across all cores exceeds 16,777,216. The latter is the maximum number
of indexed `CpuSnapshot` reference slots needed by one complete publication under the preserved
public array shape. These exact budgets prevent a valid-range but adversarial sparse layout from
causing quadratic memory pollution; they fail the selected provider rather than truncate IDs.

### Source and global topology identity

Each `LogicalCpu` carries nonblank canonical ASCII source keys:

```text
socketKey
dieKey
coreKey
```

Keys are value identity, not discovery sequence. Adapters form them from documented stable source
identity:

- Linux uses physical package ID, die ID when available (otherwise the literal fallback die key),
  and package-local core ID.
- Windows package and core keys are canonical sorted group-affinity signatures; die uses the
  literal fallback die key until a documented source exists.
- macOS uses one package/die key and deterministic performance-level or synthetic core keys.

Keys use lowercase ASCII, have no leading/trailing whitespace, and compare by unsigned UTF-8 byte
order. They are never exposed by the public records.

Adapters use these exact canonical encodings:

```text
Linux socket:  linux:package:<canonical signed decimal package ID>
Linux die:     linux:die:<canonical signed decimal die ID, or 0 when unavailable>
Linux core:    linux:core:<canonical signed decimal local core ID>
Windows socket/core affinity signature:
  windows:<package|core>:g<canonical unsigned decimal group>=<16 lowercase hex mask>
  with multiple nonzero group entries sorted by numeric group and joined by semicolons
macOS socket:  macos:package:0
macOS die:     macos:die:0
macOS core:    macos:core:<8 lowercase hex stable ordinal>
fallback keys: fallback:package:0, fallback:die:0,
               fallback:core:<8 lowercase hex logical ordinal>
```

Canonical decimal has no plus sign or redundant leading zero. A Windows signature includes the
unsigned 64-bit mask as exactly 16 hex digits, so bit 63 is not lost. Empty affinity entries are
omitted and a wholly empty package/core signature is invalid.

`TopologyNormalizer` assigns:

1. dense global socket IDs `0..socketCount-1` by sorted distinct `socketKey`;
2. an internal die identity by `(globalSocketId, dieKey)`; and
3. dense global core IDs `0..coreCount-1` by distinct sorted
   `(globalSocketId, dieKey, coreKey)` tuples.

All CPUs with the same complete tuple are one core. A CPU that presents the same core key under
conflicting die/socket identity is a different tuple; a CPU that is assigned to two tuples is
invalid. This scheme makes Linux local core zero on different sockets/dies globally distinct and
makes global identity independent of filesystem/native enumeration order.

Every logical CPU belongs to exactly one global core and socket. All CPUs in one core have one
socket and one `CoreKind`; a conflicting assignment fails. `UNKNOWN` projects to public
`CoreInfo.pCore() == true`, treating a homogeneous/unknown CPU as general capacity rather than
inventing an efficiency-core classification. Explicit performance and efficiency hints are
preserved.

### Active, sparse, and offline membership

`TopologyInput` contains only logical CPUs the provider considers online and present in its
documented topology scope. Offline or absent host IDs are omitted; their numeric positions remain
holes. Process/configuration restrictions are applied later by utilization membership and
`TopologyMapper.allowedCpus`; the provider does not renumber around them. The normalizer sorts
input copies and never depends on map, directory, native-record, or set iteration order.

The validated model must contain at least one active logical CPU. Empty input, missing core/socket
identity, contradictory ownership, or a CPU without a complete projected public entry fails the
selected provider and activates the common whole-model fallback. Provider and fallback data are
never mixed.

### Cache validation and exact fallbacks

A valid `CacheDomain` has level 1, 2, or 3; positive byte size; a valid cache-line size; a nonempty
owned sharer mask containing only active logical IDs; and no ambiguous, nonidentical overlap with a
second domain of the same level for the same CPU. Optional invalid/missing cache observations are
treated as unavailable and replaced; they do not invalidate otherwise complete topology.

For each active logical CPU and each level, choose its one valid provider domain or use:

| Level |          Fallback bytes | Fallback sharers                                  |
|-------|------------------------:|---------------------------------------------------|
| L1    | `SystemInfo.DEFAULT_L1` | all active logical CPUs in the same global core   |
| L2    | `SystemInfo.DEFAULT_L2` | all active logical CPUs in the same global core   |
| L3    | `SystemInfo.DEFAULT_L3` | all active logical CPUs in the same global socket |

The public `sharesL#` is exactly the fallback/provider sharer-mask cardinality and is at least one.
Every cache mask is emitted through the unchanged canonical `SystemInfo.toHexMask` format. A valid
line size is a power of two in `[16, 1024]`; otherwise use 64 bytes for that domain. The public
global `CACHE_LINE_SIZE_BYTES` is the maximum validated/fallback line size across active CPUs so
padding is never smaller than an observed cache line. Missing CPU zero is irrelevant.

Provider masks are copied, intersected with active membership, and must still contain the owning
CPU. L1/L2 domains may not cross a global socket. L3 cross-socket observations are rejected as
unavailable and receive the socket fallback. `socketL3Cache` sums each distinct canonical L3 mask
once and is null-safe; fallback L3 is therefore counted once per socket.

### Complete immutable model

For every active logical CPU `c`, all of these are non-null and mutually consistent:

```text
CpuInfo[c]
CoreInfo[CpuInfo[c].core]
SocketInfo[CpuInfo[c].socket]
CpuCacheLayout[c]
```

Each core and socket mask contains exactly the active IDs owned by that entity. Each cache layout's
three masks contain its CPU. Public projections and internal collections are immutable snapshots;
no caller/provider map, list, array, or `BitSet` can change them after normalization.

## Public compatibility meanings

All public shapes and accessors remain. Their exact meanings are:

- `CPU_COUNT` / `getCpuCount()`: the logical CPU **index span**, `maxActiveLogicalCpuId + 1`, not
  active cardinality. This intentional clarification makes sparse IDs safe for legacy arrays.
- `CORE_COUNT` / `getCoreCount()`: active global core cardinality. Global core IDs are dense, so it
  also equals `MAX_CORE_ID + 1`.
- `SOCKET_COUNT`: active global socket cardinality. Global socket IDs are dense, so it also equals
  `MAX_SOCKET_ID + 1`.
- `MAX_CORE_ID` and `MAX_SOCKET_ID`: largest valid global IDs in the complete model.
- `getSystemCpus()`: active logical CPU IDs in strictly ascending numeric order.
- `getCpuSet()`: the same active IDs as an immutable public mask.
- `getPCoreSet` / `getECoreSet`: global core-ID masks.
- `getPCpuSet` / `getECpuSet`: logical CPU-ID masks.
- `CpuInfo.cpu/core/socket`: logical CPU ID, dense global core ID, and dense global socket ID.
- `CoreInfo.core/socket`: dense global IDs; its CPU mask contains logical CPU IDs.
- `SocketInfo.socket`: dense global socket ID; its masks contain logical CPU/global core IDs.
- `CpuCacheLayout.cpu`: logical CPU ID; cache masks contain logical CPU IDs.

There are no null holes for active IDs. A sparse inactive logical CPU ID may return null from
`getCpuInfo`/`getCacheLayout`. Effective socket/core/snapshot arrays retain ID indexing and use null
only for inactive holes. Existing core allocation by `MAX_*_ID + 1` and direct active-ID indexing
therefore remains valid without a core production change.

The static facade, nested public record shapes, exports, default cache constants, and mask parser/
formatter remain bytecode compatible under the P0 gate.

## SystemInfo initialization and fallback

`TopologyBootstrap` performs two independent operations:

1. select, collect, normalize, and freeze topology; and
2. select the existing platform `SystemSnapshotProvider`.

A resource provider failure must not discard a valid topology, and a topology provider failure must
not expose a partial topology. `SystemInfo` catches unsupported provider selection, linkage/native
initialization failure, I/O/runtime collection failure, empty input, and
`TopologyValidationException`; it logs one error with the platform and cause, then installs one
complete fallback topology.

The fallback for injected processor count `N` uses `max(1, N)` CPUs, one CPU per core, socket zero,
`UNKNOWN` core kind (public `pCore=true`), L1/L2 self masks, one socket-wide L3 mask, default cache
sizes, shares `1/1/N`, and 64-byte lines. It is deterministic and has no dependency on native
loading or `SystemInfo` fields.

The existing resource snapshotter is retained when it initializes successfully. On an unsupported
OS or resource-provider initialization failure, `SNAPSHOTTER` is null and
`getSystemSnapshot()` retains its existing failure surface; P4 owns monitor/provider lifecycle.
P2 must not fabricate resource samples.

Platform adapters and the normalizer may refer to compile-time cache defaults through an internal
constant owner, but may not read a partially initialized `SystemInfo` topology. This breaks the
current static-initialization cycle.

## Immutable wrapper and snapshot contract

### Wrapper ownership

`UnmodifiableBitSet` continues to clone on construction/wrap, reject all public mutators, return
copies from array conversion and `clone`, and compare/hash by bit content.

`UnmodifiableDoubleArray` changes from aliasing to owned value semantics:

- constructor and `wrap` clone the source array;
- no accessor returns its internal array;
- `copy(buffer, bufferStart, bufferEnd, sourceStart)` copies consecutive delegate values beginning
  at `sourceStart` into buffer indexes beginning at `bufferStart`, stopping at exclusive
  `bufferEnd` or delegate length. It requires non-null buffer,
  `0 <= bufferStart <= bufferEnd <= buffer.length`, and `sourceStart >= 0`;
- `iterate(start, end, consumer)` visits exactly the half-open delegate range `[start, end)` and
  requires `0 <= start <= end <= length` and a non-null consumer;
- `equals` and `hashCode` use `Arrays.equals`/`Arrays.hashCode`, including the ordinary Java double
  rules used by those methods; and
- `toString` remains content-based.

The extra allocation occurs only at sample construction/publication, not in a worker hot loop.

### Public record construction and access

Every `SystemUtilization` record retains its exact component list/order. Compact constructors copy
all mutable components on every public construction path, including direct canonical-constructor
calls:

- incoming `BitSet` values become owned `UnmodifiableBitSet` values;
- incoming wrappers are copied into new owned wrappers;
- incoming `CpuSnapshot[]` and `CoreSnapshot[]` arrays are cloned; and
- explicit array accessors return a clone, never the stored array.

Nested snapshot elements are records and require no recursive object clone after their own mutable
components are owned. Null holes in snapshot arrays are preserved. Null mutable components fail at
the public construction boundary with the component name; P2 does not add numeric pressure/unit
validation owned by P4.

`SocketSnapshot` and `CoreSnapshot` override `equals` and `hashCode` together. They compare every
component, use `Arrays.equals`/`Arrays.hashCode` for nested arrays, and use content equality for
owned bitsets. `CoreSnapshot` removes its current partial equality implementation. Two separately
allocated but value-identical snapshot trees must be equal and have equal hashes; changing any
component or active nested entry must make them unequal. `CpuSnapshot` remains ordinary record
value equality. Wrapper fixes make `SystemSnapshot` and `HardwareUtilization` ordinary generated
record equality content-correct.

### Snapshot ID/index and value meanings

For a published `HardwareUtilization`:

- `globalEffectiveCpus` contains logical CPU IDs.
- Per-CPU wrapper indexes are logical CPU IDs and must cover every active bit; inactive holes may
  contain neutral values.
- `getSocketSnapshot(socketId, effectiveCoreToCpu, quota)` receives a fixed global-core-indexed
  list of length `MAX_CORE_ID + 1`, with null inactive holes.
- `SocketSnapshot.coreSnapshots` has that same length and is non-null at each active core in the
  socket.
- `CoreSnapshot.cpuSnapshots` has length `highest effective logical CPU ID in that core + 1`; it is
  non-null at each CPU in `effectiveCpus` and null at inactive/not-owned holes. This is the
  smallest shape compatible with existing direct logical-ID indexing and the allocation budget
  validated by P2-A.
- Every populated `CpuSnapshot.cpuId`, `CoreSnapshot.coreId`, and `SocketSnapshot.socketId` equals
  its array/list index.
- `globalCpuCount` in both core and CPU snapshots means
  `HardwareUtilization.globalEffectiveCpus().cardinality()`, never local core cardinality or index
  span.
- `lastUsageNs` in every populated CPU and socket snapshot equals
  `HardwareUtilization.timestampNs()` exactly.

The memory fields are populated by name, without changing P4's measurement formula:

```text
globalMemoryLimit = HardwareUtilization.globalMemoryPool
globalBytesUsed   = truncating saturated nonnegative
                    (globalMemoryPool * totalMemoryUtilization)
socket memoryLimit = saturated nonnegative perCpuMemoryPool * active socket CPUs
core memoryLimit   = saturated nonnegative perCpuMemoryPool * active core CPUs
cpu memoryLimit    = nonnegative perCpuMemoryPool
scope memoryUtilization = scope usage / max(scope memoryLimit, 1)
```

Scope usage is `memPerCpuUsageBytes * active scope CPUs`, using saturated nonnegative
multiplication. P2 performs only finite guards needed to avoid NaN/Infinity in newly corrected
scope division; P4 remains responsible for canonical units, clamping all normalized public ratios,
and the meaning of `memPerCpuUsageBytes`.

For these helpers, a negative operand is treated as zero, a NaN product is zero, positive infinity
or finite overflow saturates to `Long.MAX_VALUE`, and a finite in-range double-to-long conversion
truncates toward zero. P2 does not clamp usage to its limit because P4 owns normalization.

`getSocketSnapshot` returns null for a null/empty mapping, inactive socket, or negative/nonfinite
quota. A structurally valid active mapping always produces a complete snapshot. `getCpuSnapshot`
retains a neutral compatibility result for an invalid/inactive ID, but stamps it with the current
`timestampNs`; an active ID whose pressure/throttle arrays do not cover its logical index is a
structural error and may never take that fallback path.

## TopologyMapper contract

### Allowed-mask ownership and membership order

The constructor clones the caller mask once, intersects it with the immutable `SystemInfo` CPU
set, and never exposes or mutates either input. Later caller mutations have no effect.

The two existing public constructors retain their descriptors and delegate to one package-private
constructor accepting a validated `TopologyModel` plus an allowed mask. Production passes the
single model owned by `SystemInfo`; deterministic P2-B tests pass fixture models. This is the only
mapper topology-injection seam and prevents tests from mutating process-global facade state.

For every update, form the candidate in this exact order:

```text
owned utilization.globalEffectiveCpus clone
  AND SystemInfo.getCpuSet()
  AND constructor-owned allowedCpus
  -> apply core-zero reservation
  -> derive global cores and sockets
```

Unknown/sparse bits disappear at the intersections. Pressure, quota, counters, timestamps, and
every other utilization field do not affect membership or versions.

### Exact retained core-zero policy

"Core zero" means dense global core ID 0, not logical CPU 0 and not every platform-local core key
zero.

After all three CPU-set intersections:

1. Find candidate CPUs belonging to global core 0.
2. If the candidate also contains at least one CPU from any other global core, remove every
   candidate CPU belonging to core 0.
3. Otherwise retain the candidate core-zero CPUs unchanged.
4. If the pre-reservation candidate is empty, remain empty; do not add CPUs not allowed by all
   three masks.

Thus alternatives reserve core zero for management, an allowed/effective core-zero-only machine
continues to run, and reservation never reintroduces a disallowed CPU.

### Effective topology shape and versions

The initial `EffectiveSystemTopology` is deeply immutable, contains empty masks, a fixed
`MAX_SOCKET_ID + 1` null-hole socket list, and global version `-1`.

Every later publication has:

- immutable effective socket/core/CPU masks;
- a fixed global-socket-indexed list of length `MAX_SOCKET_ID + 1`, null only for inactive sockets;
- for each active socket, immutable masks and a fixed global-core-indexed
  `effectiveCoreToCpu` list of length `MAX_CORE_ID + 1`, null only for inactive/not-owned cores;
- a nonempty CPU mask at every active core entry; and
- no active CPU outside its public `SystemInfo` core/socket ownership.

The first nonidentical publication has global version 1. Global version increments by exactly one
only when effective CPU/core/socket membership differs from the previously published topology.
Repeating membership with different pressure or other utilization values preserves the same
version and the same published object identity.

Each socket has a persistent private version counter. Its first active publication is version 1.
The counter increments once for every actually published change to that socket's CPU/core
membership, including deactivation; a reactivated socket therefore has a version greater than its
prior active version. A membership change on another socket does not change this socket's version.
Coalesced requests that are never published do not consume global or socket versions.

`getGlobalVersion()` returns the version from the same acquired topology object used by
`getEffectiveTopology()`, eliminating split version/topology observations.
`getEffectiveSocketTopology(id)` captures one acquired system topology and returns null for a
negative, out-of-range, or inactive ID.

### Coalescing and publication memory semantics

Each `update` first clones/sanitizes membership into an immutable request, then assigns a strictly
increasing submission sequence at the request's atomic enqueue linearization point. An
`AtomicReference` pending slot retains the greatest sequence, so a concurrent older request cannot
replace a newer pending request. One CAS-elected drain owner repeatedly takes the newest pending
request, derives topology, and publishes until the slot is empty. The release/recheck protocol must
close the race between clearing the work-in-progress flag and a new enqueue; a pending request is
eventually drained by the current or newly elected owner.

Intermediate pending requests may be coalesced. After all concurrent callers quiesce, the
published membership must equal the greatest submitted sequence. No request may leave the newest
membership permanently unpublished. P2 does not use sample timestamp ordering; P4 owns timestamp
validity and listener ordering.

The `effectiveTopology` field is `volatile`:

- all model construction, mask/list freezing, and socket-version updates occur before its volatile
  write;
- `getEffectiveTopology`, `getGlobalVersion`, and `getEffectiveSocketTopology` begin with one
  volatile read;
- that write/read pair establishes happens-before for the complete immutable object graph; and
- consumers never combine a separate atomic version read with a different topology instance.

CAS operations on pending/work-in-progress state coordinate writers only. They do not replace the
volatile publication boundary. Stronger per-element synchronization is unnecessary because all
published fields are final and deeply owned.

## Deterministic fixtures and tests

Fixtures are small checked-in UTF-8/LF text or programmatic builders under hardware test ownership.
They contain no host paths, timestamps, map-order expectations, or native binaries.

### P2-A topology fixtures

1. **Linux duplicate local cores and sparse/offline CPUs**: active logical IDs
   `{0,2,8,10,16}`; absent IDs model offline holes. Package 0/die 0, package 0/die 1, and package
   1/die 0 all report local core 0. Shuffled input must produce three distinct dense global cores,
   two sockets, `CPU_COUNT == 17`, active cardinality 5, complete masks, deterministic socket/die/
   core ordering, and no null active cache.
2. **Windows processor-group identity**: group 0 processors 0 and 63 and group 1 processors 0 and
   63 map exactly to logical IDs `{0,63,64,127}`. Shuffled relationship/signature input must retain
   the same package/core/global IDs and bit 63.
3. **macOS incomplete topology**: empty, missing-core, and contradictory fake provider inputs fail
   normalization; bootstrap with injected processor count 4 installs the exact one-socket/four-core
   conservative fallback and never dereferences CPU/cache zero from an empty provider map.
4. **Missing caches**: partial L1-only and absent-cache inputs produce the exact default sizes,
   core sibling L1/L2 masks, socket L3 mask, shares, and 64-byte fallback; shuffled valid cache
   domains give identical projections.
5. **Provider mutation resistance**: mutate every source list/map/array/bitset after collection and
   normalization; the model and all public projections remain unchanged.

Required stable test IDs include:

```text
SystemInfoFallbackTest#initializesWithIncompletePlatformTopology
LinuxSystemLayoutFixtureTest#normalizesSparseMultisocketTopology
WindowsTopologyFixtureTest#mapsGroupsAndBitSixtyThreeBijectively
TopologyCacheFallbackTest#completesEveryActiveCpuDeterministically
TopologyOwnershipTest#doesNotAliasProviderStorage
```

P2's Windows test targets the common group-to-logical-ID and canonical relationship boundary, not
the full GLPIEx byte parser assigned to P6. P2's macOS test targets common fallback/bootstrap, not
P7's final public-sysctl synthesis.

### P2-B snapshot/publication fixtures

1. **Snapshot mutation resistance**: reuse and mutate provider pressure arrays, effective masks,
   mapping bitsets, and returned nested arrays after construction/access. Older public snapshots
   remain byte-for-byte/value stable.
2. **Equality/hash behavior**: independently allocated equal system/hardware/socket/core/CPU trees
   and wrappers compare equal with equal hashes; one change to every component class, including a
   nested active entry, is detected.
3. **Named field population**: a two-socket/two-core sparse-CPU fixture asserts exact global/local
   CPU counts, quota, global memory limit/used, local memory limit/utilization, IDs, indexes, null
   holes, and one publication timestamp.
4. **Allowed-mask ownership and core zero**: caller mutation before/after mapper creation cannot
   change ownership; alternatives remove all global-core-zero CPUs, while all allowed/effective
   core-zero-only variants retain them and remain nonempty.
5. **Remap and versions**: initial `-1`, first publication, identical membership, one-socket
   change, other-socket-only change, deactivation, reactivation, and empty membership assert exact
   global/socket versions and fixed null-hole shapes.
6. **Concurrent coalescing**: latch-controlled writers enqueue ordered distinct memberships while
   a drain is paused. Final publication equals the greatest sequence, versions are monotonic, no
   newest update is lost, and every acquired topology is internally self-consistent.
7. **Pressure-independent membership**: multiple utilizations with identical effective masks and
   different pressure/quota/counter payloads preserve version and object identity.

Required stable test IDs include:

```text
SnapshotOwnershipTest#publishedSnapshotsRemainStableAndValueConsistent
SnapshotIndexContractTest#populatesNamedFieldsAndActiveEntries
TopologyMapperCoreZeroTest#fallsBackWhenCoreZeroIsTheOnlyAllowedCore
TopologyMapperPublicationTest#publishesOwnedCoalescedTopology
TopologyMapperVersionTest#versionsOnlyPublishedMembershipChanges
```

Host-dependent smoke assertions remain separate from deterministic fixtures. Tests use latches,
not sleeps, for publication races.

## Failure and fallback behavior

- Invalid raw logical IDs, duplicate CPU ownership, conflicting core/socket identity, excessive
  index span, or empty required topology: `TopologyValidationException` with provider and stable
  key/ID; bootstrap logs once and replaces the whole topology with common fallback.
- Missing/invalid optional cache: deterministic level-specific fallback, not whole-topology
  failure.
- Unsupported OS: common topology fallback and null snapshot provider.
- Platform resource provider failure: null snapshot provider without changing valid topology.
- Unknown allowed/effective CPU bit: intersect away; never create a synthetic public entry.
- Invalid active mapping supplied to snapshot derivation: fail at the internal call boundary or
  return the documented null for an inactive socket; never publish a partially populated active
  tree.
- Allocation overflow in index spans or memory-scope multiplication: topology rejects the former;
  snapshot arithmetic saturates the latter to `Long.MAX_VALUE`.
- Concurrent mapper writer failure before publication: retain the last complete topology, release
  writer ownership in `finally`, and allow a newer pending request to drain. Do not publish a
  partial graph.

No fallback silently claims platform collection parity. Diagnostics distinguish selected-provider
failure from common fallback installation; P5-P7 remain responsible for final platform claims.

## Determinism and mathematical precision

- Logical, global core, and global socket IDs are exact integers; comparisons and array indexes use
  no floating point.
- Source-key ordering is unsigned UTF-8 byte order, independent of locale and default charset.
- Public CPU-mask output remains exact lowercase hexadecimal with 32-bit comma groups.
- Cache sizes, shares, line sizes, counts, IDs, versions, timestamps, and memory byte fields compare
  exactly.
- Snapshot equality uses exact Java primitive/array value semantics; no epsilon is used.
- Nonnegative memory multiplication saturates rather than wraps. Scope division uses a denominator
  of at least one and must produce a finite result.
- P2 defines no pressure curve, EWMA coefficient, time tolerance, unit conversion, or performance
  threshold. Those are not applicable because P4 owns the measurement mathematics.

## Allocation, ownership, and contamination boundaries

Topology collection/normalization runs once during facade initialization. It may allocate bounded
maps, sorted lists, strings, and masks. It must not allocate storage proportional to an unchecked
platform ID. The validated maximum logical index span bounds all later per-CPU arrays.

`TopologyMapper.update` and snapshot construction run at monitor-publication cadence, not per frame.
They may allocate one immutable request/publication and bounded copied arrays/masks. No change is
permitted in `ControlPlaneFragment.cycle`, frame execution, queue operations, or routing hot paths.
Existing core reads retain direct ID-indexed access.

Tests call injectable `TopologyNormalizer`/`TopologyBootstrap` seams rather than mutating
`SystemInfo` static fields, changing `os.name`, or relying on classloader order. A failed fixture
must not contaminate the process-global facade for later tests. Provider buffers are never retained.

P2 adds no runtime file writer, deletion, native allocation, thread, shutdown hook, serializer, or
persistent format. Filesystem destructive safety and native cleanup are therefore not applicable;
fixtures are read-only bounded test inputs. Build output remains under `target` through the
completed P1 graph, and source/native fingerprints are outside P2 ownership.

## Compatibility and migration boundary

The P0 API/module/mask gate must pass. Behavior corrections are exact T01-T06/P2 allowlisted
changes:

- T01: common initialization no longer fails on empty/incomplete platform topology.
- T02: common normalization makes local core identity global and sparse IDs safe; detailed Linux
  collection remains P5.
- T03: common Windows group identity is bijective; bounded full native blob parsing remains P6.
- T04: mapper owns masks, coalesces, versions consistently, and publishes with volatile
  happens-before.
- T05: public snapshots/wrappers deeply own storage, equality/hash agree, and named fields are
  populated by meaning; platform value parity continues in P4-P7.
- T06: core zero remains reserved when alternatives exist and is retained when it is the only
  allowed/effective core.

There is no stored-data migration or wire-format change. That area is not applicable because all
P2 values are in-memory records and masks whose public formats are preserved.

## Dependency order and child ownership

### P2-A - topology model, adapters, and SystemInfo bootstrap

Branch family:

```text
hardware-utils-overhaul/phase-2-topology-model-blueprint
hardware-utils-overhaul/phase-2-topology-model-implementation
hardware-utils-overhaul/phase-2-topology-model-audit
```

Owned implementation, in dependency order:

1. internal raw/validated topology value contracts and validation exception;
2. deterministic normalization, global identity, cache completion, and fallback builder;
3. provider/adaptor boundary for Linux, Windows, and macOS without detailed P5-P7 collection work;
4. `SystemInfo` bootstrap/projection, public count/index meanings, safe resource-provider
   separation, cache-line selection, and null-safe L3 aggregation;
5. Linux sparse/duplicate-local-core, Windows group identity, macOS incomplete, missing-cache,
   ordering, and provider-mutation fixtures; and
6. P0 compatibility and read-only core compile compatibility.

P2-A owns `SystemInfo`, the new internal topology package, layout adapters, and topology/fallback
tests. `TopologyMapper`, `SystemUtilization`, snapshot wrappers, ResourceMonitor behavior, affinity,
and core production are read-only.

### P2-B - immutable snapshots and remap publication

Branch family:

```text
hardware-utils-overhaul/phase-2-snapshot-publication-blueprint
hardware-utils-overhaul/phase-2-snapshot-publication-implementation
hardware-utils-overhaul/phase-2-snapshot-publication-audit
```

P2-B starts only after P2-A implementation and conformance/manual review are merged. Owned
implementation, in dependency order:

1. defensive value semantics for common unmodifiable wrappers;
2. deep construction/access/equality/hash and named-value corrections for public snapshots;
3. allowed-mask ownership and exact membership/core-zero derivation in `TopologyMapper`;
4. fixed null-hole topology shapes, global/socket version state, coalescing, and volatile
   publication;
5. mutation/equality/index/core-zero/remap/version/concurrency fixtures; and
6. P0 compatibility, all P2 tests, and read-only core consumer tests/compile gate.

P2-B owns `TopologyMapper`, `SystemUtilization`, the two wrappers, and their tests. It may consume
but not redesign P2-A's `TopologyModel` and public ID contract. Layout adapters, resource monitor,
platform providers, affinity, and core production are read-only.

After both child audits merge, the P2 root conformance audit checks the combined
provider -> model -> facade -> mapper -> snapshot boundary and performs root closeout. There is no
separate validation branch under the current conformance/manual-review workflow.

## Sizing and split gate

The unsplit P2 implementation is rejected.

- Topology normalization/bootstrap spans three platform adapters, stable cross-platform identity,
  fallback construction, static initialization, caches, and sparse indexing.
- Snapshot/publication spans defensive public value semantics, named numeric population, a
  concurrent coalescing state machine, versions, and Java Memory Model publication.
- The responsibilities can be compiled and validated independently through the frozen
  `TopologyModel` and public ID/index contract.
- Combining them would require one implementation pass to hold platform discovery failure,
  identity sorting, static initialization, deep record ownership, arithmetic, and concurrent
  publication together.

P2-A and P2-B are each one module and one coherent ownership boundary. Splitting adapters away
from the model would force adapters to choose or duplicate raw identity/cache semantics. Splitting
snapshot ownership from `TopologyMapper` would duplicate the active-ID/null-hole and publication
acceptance fixtures. Each child therefore passes its own parent-level gate, but its blueprint must
rerun the workflow gate against the refined file/test inventory and split again if new independent
work appears.

Mandatory order:

```text
parent blueprint review and merge
  -> P2-A blueprint -> implementation -> conformance/manual review, each merged in order
  -> P2-B blueprint -> implementation -> conformance/manual review, each merged in order
  -> P2 root conformance audit and authorized closeout
```

Do not create either child branch before this parent blueprint is merged. Do not create P2-B before
P2-A's audit is merged. Do not run the superseded root implementation.

## Bounded implementation context envelopes

### P2-A required inputs

- `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, and `docs/ARCHITECTURE.md`;
- parent plan settled requirements, T01-T03/T05 common portions, T06, P0-P1 closeout summaries,
  and this blueprint's topology contract;
- P0 API/mask/core-zero artifacts and surviving P1 closeout evidence;
- hardware POM/module descriptor;
- `SystemInfo`, all three layout adapters, Windows relationship value/parser types only at the
  current adapter boundary, and existing topology tests;
- existing cache constants/mask helpers and read-only resource-provider construction sites; and
- summarized core consumer assumptions: stable IDs, `MAX_* + 1` arrays, mask indexing, complete
  active cache, null inactive holes.

P2-A need not reread frame/queue implementation bodies; this parent contract summarizes their
read-only dependency. It excludes resource/pressure internals, affinity/executor code, native
sources, CI, benchmarks, and all training paths.

### P2-A owned outputs

- child blueprint and completion/conformance record;
- `SystemInfo` and the unexported internal topology package;
- common-boundary layout-adapter changes;
- topology/fallback/cache/identity fixtures and tests; and
- temporary P2 status updates.

### P2-B required inputs

- `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, and `docs/ARCHITECTURE.md`;
- parent plan P2 requirements/summary, this blueprint's snapshot/publication contract, and P2-A's
  blueprint/completion/conformance summary plus final model/public projection diff;
- P0 compatibility and defect-ledger contracts;
- `TopologyMapper`, `SystemUtilization`, both wrappers, existing topology/snapshot tests, and only
  the `ResourceMonitor` call boundary;
- core consumer locations that index `effectiveCoreToCpu`, socket/core/CPU snapshot arrays, and
  read mapper versions; and
- P2-A's exact `CPU_COUNT`, `MAX_*`, stable ID, complete-entry, and null-hole guarantees.

P2-B excludes layout collection internals, detailed resource math/monitor lifecycle, affinity/
executor code, native sources, CI, benchmarks, and all training paths.

### P2-B owned outputs

- child blueprint and completion/conformance record;
- `TopologyMapper`, `SystemUtilization`, `UnmodifiableBitSet`, and
  `UnmodifiableDoubleArray` where the settled contracts require changes;
- snapshot/ownership/equality/index/core-zero/remap/version/concurrency fixtures and tests; and
- temporary P2 status updates.

## Implementation model reassessment

### Root context and coupling

The provisional unsplit implementation would span one module but five package ownership regions,
three platform adapters, one new internal schema, four public nested topology records, five public
utilization records, two wrappers, static initialization/fallback, cache-domain normalization,
sparse indexing, exact memory arithmetic, a concurrent coalescing state machine, and downstream
core compile/test interactions. It combines topology, recovery, deterministic ordering,
mathematical precision, defensive memory ownership, and explicit publication semantics.

- P2-A holds roughly four existing production classes, one bounded internal package, and five
  fixture families together; its lifecycle is provider selection -> collect -> validate or fail ->
  whole-model fallback -> one immutable facade publication.
- P2-B holds four existing production classes plus focused tests; its mapper lifecycle is initial
  `-1` -> enqueue/coalesce -> exclusive derive -> version -> volatile publish, including inactive
  and reactivated socket states.
- The only new schema is the in-memory `TopologyInput`/`TopologyModel` boundary. There is no wire,
  filesystem, or migration schema.
- Compile repair crosses exported public records, platform adapters, resource-provider array
  assumptions, and core consumers even though production edits remain hardware-only. Acceptance
  combines unit fixtures, P0 bytecode compatibility, P1-aware hardware verify, and read-only core
  tests.

The existing P1 work shows that broad cross-boundary implementation without its required child
artifact chain leaves conformance ambiguous. P2 therefore does not rely on a later audit to recover
missing implementation reasoning. The exact child envelopes reduce history load, but neither child
is a low-effort mechanical translation.

There is no prior P2 implementation attempt and therefore no evidence that a lower-capability or
lower-effort model can preserve these coupled contracts. The current defects - aliased masks,
dropped updates, partial equality, positional field mistakes, and platform identity collisions -
are evidence against treating either child as mechanical compile repair.

### Child capability decisions

- P2-A implementation: **`gpt-5.6-sol`, reasoning effort `high`**. It must preserve global
  identity, fallback, static initialization, cache completeness, sparse index bounds, and three
  adapter compile surfaces together.
- P2-B implementation: **`gpt-5.6-sol`, reasoning effort `high`**. It must preserve public value
  compatibility, exact named arithmetic, deep ownership, concurrent coalescing/version state, and
  volatile publication together.
- Child conformance/manual reviews and P2 root audit: **`gpt-5.6-sol`, reasoning effort `high`**.

The selected root implementation is `none`; the parent plan's provisional root prompt must be
marked superseded and must not run. Each child blueprint must confirm this selection after its own
refined sizing/model gate and may raise or split, but may not silently downgrade. No evidence
supports `medium` or `low`: the current code has the exact aliasing, identity, null-hole, and lost-
update defects that require coupled repair.

## Commands and acceptance gates

Use the pinned installed JDK 21.0.2 and Maven 3.9.16 through `mise` when available, or the explicit
toolchain fallback in `AGENTS.md`. No command may select training.

### Fast deterministic child loop

Use direct plugin goals so topology/snapshot iteration does not needlessly rerun P1 native
packaging:

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='SystemInfoFallbackTest,LinuxSystemLayoutFixtureTest,WindowsTopologyFixtureTest,TopologyCacheFallbackTest,TopologyOwnershipTest' \
  surefire:test

mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='SnapshotOwnershipTest,SnapshotIndexContractTest,TopologyMapperCoreZeroTest,TopologyMapperPublicationTest,TopologyMapperVersionTest' \
  surefire:test
```

Children may include exact additional settled test class names. Direct goals do not replace final
verification.

### Compatibility and final selected-module gates

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='ApiCompatibilityTest,MaskFormattingCompatibilityTest,CoreZeroReservationCompatibilityTest' \
  surefire:test

mise exec -- mvn -B -pl euhedral-hardware-utils -am verify
mise exec -- mvn -B -pl euhedral-core -am test
```

The final hardware `verify` rechecks the completed P1 native/package gates. Docker/hosted
cross-platform limitations are reported separately; P2 deterministic fixtures may not be skipped
because the host is Linux. The core command is a read-only consumer compatibility gate and must
not lead to core production edits.

### Scope and hygiene

```bash
git diff --check
git diff --name-only <child-parent> -- euhedral-training
git status --short
rg -n 'P2 implementation prompt - PROVISIONAL|phase-2-topology-snapshot-implementation|phase-2-topology-snapshot-model-validation' \
  docs/plans/hardware-utils-platform-parity-overhaul.md \
  docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md
```

The training diff is empty; do not otherwise inspect training. The stale-prompt search must show
only deliberately quoted/superseded history, not a runnable root implementation or validation
dependency.

### Parent acceptance criteria

1. P0 reports no non-additive API/module/mask drift; no exported package changes.
2. Every active logical CPU has one complete deterministic CPU/core/socket/cache projection.
3. Linux sparse IDs and duplicate local cores, Windows group/bit-63 identity, and macOS incomplete
   fallback pass independent of input order.
4. Public CPU count/index and core/socket count/ID meanings match this blueprint exactly.
5. Cache fallbacks, shares, masks, line size, and unique socket L3 sum match exact fixtures.
6. Provider, model, facade, wrapper, mapper, and snapshot storage do not alias caller buffers.
7. Equal wrappers/snapshot trees have equal hashes; every component participates in equality.
8. Active socket/core/CPU snapshot entries are complete, correctly indexed, and carry named field
   values and exact publication timestamps.
9. Allowed masks are constructor-owned and unknown bits are safely ignored.
10. Core zero is removed only when another allowed/effective global core remains; core-zero-only
    topology remains nonempty.
11. Mapper updates coalesce without losing the greatest submitted request and publish only complete
    immutable graphs through one volatile boundary.
12. Global/socket versions follow the exact first/change/deactivate/reactivate rules and ignore
    pressure-only changes.
13. `SystemInfo` installs a complete whole-model fallback after platform topology failure and does
    not conflate it with resource-provider success.
14. No detailed platform parity, pressure/monitor, affinity/executor, core production, native,
    training, or unrelated work enters the diff.
15. All child deterministic tests, P0 gates, hardware verify, applicable read-only core tests,
    `git diff --check`, scope checks, and final status evidence are recorded.

Every criterion and common P2 portion of T01-T06 must be classified `satisfied`, `deviated`,
`unverified`, or `ambiguous` in the root audit. Platform collection portions explicitly carried to
P5-P7 are not silently claimed by P2.

## Risks and unresolved decisions

- Sparse logical CPU IDs can increase array spans. The explicit 1,048,576 bound and exact
  `CPU_COUNT` index-span meaning prevent unbounded allocation; a larger real platform observation
  is a provider failure and common fallback, not truncation.
- Static initialization can recurse through adapters or native/resource providers. The internal
  provider/model boundary and independent resource-provider bootstrap prohibit reads of partial
  facade state.
- Public record components are mutable Java types. Constructor copies, unmodifiable bitsets, and
  clone-returning array accessors are all required; constructor-only copying is insufficient.
- Coalescing can make intermediate updates invisible. Versions count actually published membership
  states, and tests assert final greatest-sequence publication rather than every request.
- P2 common Windows/macOS fixtures do not prove detailed native parsing or final topology quality.
  Those exact portions remain P6/P7 work.
- Full hardware `verify` depends on the P1 native toolchain, and some host affinity/container tests
  may have environmental limits. Deterministic P2 unit tests and Java compilation remain required.

No unresolved architectural decision remains. In particular, ID semantics, null-hole behavior,
cache fallbacks, count/index meanings, copy boundaries, equality, core-zero policy, version rules,
and publication modes are settled.

## Handoff condition

Hand off this parent blueprint for developer review and merge into the P2 root only when:

- the plan's P2 artifact index and branch lineage name both child lifecycles and the root audit;
- the old root implementation is marked superseded and non-runnable;
- child blueprint, implementation, and conformance/manual-review prompts use only their bounded
  context envelopes and exact ownership;
- the developer-review summary records purpose, ownership, contracts, children, selected models,
  risks, and no unresolved decisions;
- only blueprint/plan/planning documentation differs in the authored P2 change, while the
  pre-existing untracked P1 root audit remains untouched;
- `git diff --check` passes; and
- no implementation or child branch has started before this parent blueprint merge.

Do not create P2-A or P2-B branches from this unmerged blueprint child. Do not start implementation.

## Root conformance audit completion evidence

Root audit prepared on `hardware-utils-overhaul/phase-2-topology-snapshot-audit` from P2 root
commit `274a6f0b`. It found one minor blueprint-settled API correction: restore the baseline
`final` flags on `SocketSnapshot.equals/hashCode` and `CoreSnapshot.hashCode`; the focused combined
topology/snapshot suite passed 24 tests after that change. P0 reports zero removals, with only
Java-17 module-version metadata and six authorized additions remaining. The audit records T04
coalescing/publication, full selected-module verification, and the read-only core gate as
unverified because the deterministic R2-R12 race matrix is absent and this host lacks mise/Zig and
the pinned Java 21/Maven 3.9.16 tools. P2 remains pending review/merge and explicit closeout.
