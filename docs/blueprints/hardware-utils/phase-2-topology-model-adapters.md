# Phase 2-A Topology Model, Platform Adapters, and SystemInfo Bootstrap

## Status and authority

- Parent plan: `docs/plans/hardware-utils-platform-parity-overhaul.md`
- Parent blueprint: `docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md`
- P2 root branch and child branch point: `hardware-utils-overhaul/phase-2-topology-snapshot` at
  `3e45f9a2`
- Blueprint branch: `hardware-utils-overhaul/phase-2-topology-model-blueprint`
- Owning module: `euhedral-hardware-utils`
- Blueprint model: `gpt-5.6-sol`
- Blueprint reasoning effort: `max`
- Status: implementation-ready; developer review and merge into the P2 root are required before
  implementation

This child is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the parent plan, the compiled
P0 compatibility gate, and the frozen parent P2 blueprint. The parent has already settled logical
and global identity, count/index meanings, null holes, cache fallback, whole-model fallback,
ownership, and static-initialization behavior. This blueprint translates those decisions into the
bounded P2-A implementation. It does not reopen them.

The developer deliberately removed the P1-A blueprint and the P1 child-audit history. This child
uses the surviving P1 parent/Child B/root audit and the P1 closeout classification as inherited
build evidence; it does not reconstruct or silently strengthen the missing evidence.

If implementation needs a different ID encoding, allocation bound, cache selection rule, fallback,
public count meaning, provider/model ownership boundary, or initialization order, it must stop and
return to this blueprint and its parent. Compile convenience is not authority to change one of
those contracts.

## Objective

P2-A inserts one deterministic, immutable common topology model between platform observations and
the existing `SystemInfo` facade. Completion must:

1. collect one owned `TopologyInput` from the selected Linux, Windows, or macOS layout adapter;
2. normalize it once into one complete `TopologyModel`, or replace the whole input with the exact
   conservative fallback;
3. preserve Linux kernel logical IDs, Windows processor-group IDs, and macOS/fallback ordinals;
4. assign dense deterministic global socket/core IDs from canonical source identity including die;
5. provide exact L1/L2/L3 completion for every active CPU without mixing provider and fallback
   topology;
6. initialize every existing `SystemInfo` field and accessor from the same immutable model while
   separating resource-provider failure from topology failure;
7. preserve every existing public descriptor, record shape, export, JNI declaration, and mask
   string; and
8. provide deterministic fixtures proving sparse/group IDs, fallback completeness, cache behavior,
   ordering, allocation bounds, and non-aliasing.

P2-A establishes common identity and projection. P5-P7 still own detailed platform discovery,
bounded native parsing, final hybrid-core quality, and real-platform parity.

## Scope

### Owned production surface

Implementation may edit only:

- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/SystemInfo.java`;
- the new unexported package
  `io.euhedral_execution.hardware_utils.internal.topology`;
- `LinuxSystemLayout`, `WindowsSystemLayout`, and `OSXSystemLayout` at their topology collection and
  common-model projection boundaries; and
- current Windows relationship value types only for an ownership copy or a bounded adapter seam
  needed to translate already-parsed relationship values. P2-A must not repair or redesign the
  GLPIEx byte parser.

The P0 test fixture and module POM may be changed only if a mechanical test-wiring correction is
strictly required. No production dependency or module directive is required or permitted.

### Owned tests and fixtures

Implementation owns new or updated hardware tests and small deterministic fixtures for:

- topology normalization, identity, validation, bounds, and deterministic ordering;
- Linux sparse/multisocket/die input and missing caches;
- Windows processor groups and bit 63 after the current relationship-value boundary;
- macOS/incomplete-provider and common fallback behavior;
- cache completion and unique socket L3 aggregation; and
- provider/input/model/public-projection mutation resistance.

Fixtures are UTF-8 with LF endings or programmatic immutable builders. They contain no native
binaries, host paths, timestamps, or dependence on host directory/map iteration order.

### Read-only inputs

- `euhedral-hardware-utils/pom.xml` and `module-info.java`;
- P0 compatibility helpers, API/native fixtures, defect ledger, and mask/core-zero tests;
- the resource-provider construction boundary in `CgroupV2Resources`, `WindowsResources`, and
  `OSXResources` only;
- `LinuxPaths.CPU_INFO_BASE` only as the historical location value, not its cgroup initialization
  or resource behavior;
- `TopologyMapper`, `SystemUtilization`, and both common wrappers for compilation only; and
- the parent blueprint's summarized core assumptions: stable mask IDs, `MAX_* + 1` allocation,
  complete active cache entries, and null inactive holes.

### Prohibited work

- `TopologyMapper`, `SystemUtilization`, `UnmodifiableBitSet`, or `UnmodifiableDoubleArray`
  changes; those are P2-B-owned.
- Resource counter collection, pressure, units, cadence, listeners, monitor lifecycle, or
  provider sampling behavior.
- `ThreadTools`, `PinnedThreadExecutor`, affinity, current-CPU behavior, or native affinity.
- Full Linux online/cgroup/sysfs parity, full Windows GLPIEx bounds/offset parsing, or macOS
  performance-level/sysctl parity assigned to P5-P7.
- Native source, generated JNI, manifest, loader, packaging, signing, POM lifecycle, CI, core
  production, Reactor, Spring, benchmarks, root POM, or unrelated documentation.
- Any inspection, edit, build, test, or command under `euhedral-training`, and any Maven reactor
  command that selects it.

## Package and type contracts

Add this unexported package without changing `module-info.java`:

```text
io.euhedral_execution.hardware_utils.internal.topology
```

Types that must be callable by the existing exported layout packages are `public` only because
Java package access does not cross packages. The module does not export their package, and no new
or existing exported method may name one of them in its descriptor or generic signature.

### `TopologyProvider`

`TopologyProvider` is a functional interface with one operation:

```java
TopologyInput collect() throws Exception;
```

It has no resource-snapshot method, mutable registration method, platform singleton, or global
state. A selected layout constructs one private provider/delegate and invokes it once. Native
linkage failures remain unchecked and are handled by bootstrap alongside collection exceptions.

### `TopologyInput`, `LogicalCpu`, `CacheDomain`, and `CoreKind`

Use immutable common values with these exact semantic fields:

```text
TopologyInput
  providerName: nonblank stable lowercase ASCII diagnostic name
  logicalCpus: owned immutable list of LogicalCpu
  cacheDomains: owned immutable list of CacheDomain

LogicalCpu
  logicalCpuId: stable nonnegative public mask/index ID
  socketKey: canonical source package/socket key
  dieKey: canonical source die key
  coreKey: canonical source core key
  coreKind: PERFORMANCE, EFFICIENCY, or UNKNOWN

CacheDomain
  level: 1, 2, or 3
  sizeBytes: provider-reported byte capacity
  lineSizeBytes: provider-reported line size
  logicalCpuSharers: owned BitSet
```

These may be records. Every constructor copies incoming lists and `BitSet` values. A
`CacheDomain.logicalCpuSharers()` accessor returns a clone, not the stored mask. The normalizer
copies again at its trust boundary so a custom provider implementation cannot bypass ownership by
reusing record objects or mutable buffers.

`CoreKind.UNKNOWN` projects to `CoreInfo.pCore() == true`. A provider may emit PERFORMANCE or
EFFICIENCY only from a platform observation that distinguishes them. Missing or ambiguous data is
UNKNOWN, never an invented efficiency classification.

### `TopologyValidationException`

`TopologyValidationException` is an internal `IllegalArgumentException`. Its stable message and
fields identify:

- provider name;
- the violated contract category;
- the offending logical ID or canonical key when one exists; and
- a short actionable reason.

It is thrown by direct normalization and adapter-boundary tests. It never appears in an exported
method signature. Selected production bootstrap catches it and installs the whole-model fallback.

### `TopologyModel`

`TopologyModel` is final and deeply immutable. It contains the only normalized projection used by
the selected layout, `SystemInfo`, and later P2-B injection. It owns:

- active logical IDs in ascending `int[]` order;
- immutable active CPU, P/E CPU, and P/E global-core masks;
- immutable, deterministic integer-keyed maps of `CpuInfo`, `CoreInfo`, `SocketInfo`, and
  `CpuCacheLayout`;
- CPU index span, dense core/socket cardinalities, maximum dense IDs, and maximum cache line; and
- no resource snapshotter, pressure value, allowed/effective mask, version, thread, or native
  buffer.

The integer maps retain ascending key iteration order and reject all mutation. Array/mask accessors
return clones or the existing immutable public wrapper. Public record values contain only
primitives and canonical strings and may be shared between immutable projections.

`TopologyModel` supplies the existing map shapes used by Linux/Windows layout getters. P2-A adds
the same four map getters to `OSXSystemLayout` only if needed for the common projection bridge;
those are additive methods returning existing public `SystemInfo` record types, never an internal
type. No other additive exported member is expected.

### Hidden projection-to-model bridge

P0 requires the existing exported layout type hierarchy and method descriptors to remain exact,
while the parent requires `SystemInfo` and P2-B to use the exact model normalized by the selected
layout. Java package boundaries prevent a package-private model accessor from reaching
`SystemInfo`, and a public accessor would leak an internal type through an exported signature.

Resolve that boundary with one internal immutable map-view implementation owned by
`TopologyModel`:

```text
existing layout getter descriptor -> Map<Integer, public SystemInfo record>
actual immutable map object       -> internal carrier of its owning TopologyModel
TopologyBootstrap                 -> verifies/extracts the owner internally
```

All four projection maps from one model carry the same final owner. `TopologyBootstrap` accepts
the selected maps, rejects an unowned or mixed-model projection, and returns that existing owner;
it does not reconstruct or renormalize a second model. The carrier interface and extraction method
remain in the unexported internal package. Do not use reflection, `MethodHandles`, an exported
internal-model accessor, a changed layout interface/superclass, `ServiceLoader`, or a mutable
model registry.

This bridge is not a general extension point. It exists only to preserve the frozen public
descriptors and one-model ownership simultaneously.

### `TopologyNormalizer`

`TopologyNormalizer` is stateless. Its operation accepts one `TopologyInput`, validates and copies
it, assigns deterministic global identities, completes caches, constructs all public record
projections, and returns one `TopologyModel`. It never:

- reads `SystemInfo` topology fields;
- reads files, native buffers, OS properties, affinity, or resources;
- applies allowed/effective masks or the core-zero reservation;
- logs and continues after required topology corruption; or
- publishes a partial model.

### `TopologyBootstrap`

`TopologyBootstrap` owns four bounded operations:

1. select the current platform layout/provider from `OSName` without initializing an unselected
   platform layout;
2. invoke that selected layout's `TopologyProvider`, normalize exactly once, and on an
   `Exception`, `LinkageError`, empty/invalid topology, or provider validation failure log one
   platform-scoped error and build the common fallback;
3. verify and extract the one model behind the selected layout's immutable projection maps for
   `SystemInfo`; and
4. after topology fields are assigned, select/construct the current
   `SystemSnapshotProvider`, catching its independent initialization failure and returning null.

Do not catch `VirtualMachineError`, `ThreadDeath`, or another unrelated fatal `Error` as a normal
fallback. `UnsatisfiedLinkError`, `ExceptionInInitializerError`, and `NoClassDefFoundError` are
covered through `LinkageError`. Resource-provider failure logs its own diagnostic and never
replaces valid topology. Unsupported OS installs fallback topology and a null snapshotter.

Bootstrap contains no mutable global registry or cached second topology. The selected public
layout singleton owns one input/model lifecycle; repeated legacy getters return the same immutable
map objects and never collect or normalize again.

### Pure defaults and mask codec

Move cache default values and the mask parser/formatter algorithm behind package-internal pure
helpers if necessary to break initialization recursion. The public `SystemInfo.DEFAULT_L1/L2/L3`
fields remain compile-time constants with their exact P0 values, and public
`fromHexMask`/`toHexMask` keep their descriptors and byte-for-byte behavior.

The internal normalizer and providers may use the pure helpers. They must not read `CPU_COUNT`,
the public maps, or another partially assigned `SystemInfo` topology field. The P0 golden mask
tests remain authoritative.

## Platform adapter boundary

### Common lifecycle

Each existing layout keeps its public final class, `INSTANCE` field descriptor, constructor
visibility, and existing public members. Its production singleton:

1. is created only for its matching `OSName`;
2. constructs a private provider around the existing collection boundary;
3. loads native code, when required, inside that provider call so bootstrap can catch linkage
   failure;
4. collects one owned `TopologyInput`;
5. calls common bootstrap/normalization once and stores the resulting model; and
6. returns that model's same immutable maps from every legacy getter.

On a nonmatching OS, `INSTANCE` remains null as today. Package-private fixture constructors may
accept a fixture root, relationship list, processor count, or direct provider, but no new public
constructor is added.

### Linux

Linux retains kernel logical CPU numbers. The P2 collector scans only `cpu<decimal>` directories
present in its injected CPU root, sorts them by numeric logical ID, and treats omitted IDs as
offline/absent holes. P5 owns final online/cpuset/hotplug discovery; P2 does not infer activity from
`Runtime.availableProcessors()` or renumber holes.

For every present CPU, required topology reads produce:

```text
socketKey = linux:package:<signed decimal physical_package_id>
dieKey    = linux:die:<signed decimal die_id, or 0 when the optional file is absent>
coreKey   = linux:core:<signed decimal core_id>
```

Canonical signed decimal has no plus sign or redundant zero. A missing/unreadable package/core ID
is a required collection failure; an absent die ID uses exactly `0`. The collector no longer uses
`core_cpus_list` to assign public masks: the normalizer derives membership from the complete
identity tuple.

Data/unified cache observations at levels 1-3 become `CacheDomain` values. Instruction-only,
missing, unreadable, or malformed cache entries are optional and omitted. Shared CPU maps are
parsed by the pure canonical mask codec and copied. Structurally equal observations from several
CPU directories are allowed and deduplicated by the normalizer.

The P2 capacity hint is deterministic and conservative. Work at global-core tuple granularity. If
every core has a positive parsed maximum-frequency observation, use frequency as its score. If not,
use the cache score only when every core has valid observed L1 and L2 data:

```text
cache score = saturatedPositive(L1 bytes * L1 sharers)
              + L2 bytes / (L2 sharers > 2 ? L2 sharers : 1)
```

All logical siblings in a tuple must yield the same score; otherwise that core is UNKNOWN. Sort
comparable core scores descending, choose the first boundary having the largest positive adjacent
gap, mark the upper class PERFORMANCE and the lower class EFFICIENCY. If fewer than two distinct
scores exist, or complete comparable scores are unavailable, every affected core is UNKNOWN.
Arithmetic saturates instead of wrapping. This preserves a genuine observed capacity split without
inventing an efficiency class from SMT/cache sharing alone.

P2 makes no final Linux hybrid-capacity claim. P5 may improve the observations without changing
the common `CoreKind` contract.

The topology collector must not initialize `LinuxPaths` merely to obtain `CPU_INFO_BASE`, because
that class currently initializes cgroup resource paths. Use an equivalent topology-only path
constant/injected root. This is separation, not a P2 edit to cgroup behavior.

### Windows

P2 consumes the existing parsed `SystemLogicalProcessorInformation` relationship values. It does
not change their byte offsets, record-size loop, buffer bounds, native method, or JNI declarations;
those are P6.

For each nonempty package/core affinity list, form a canonical signature from every nonzero group
mask:

```text
windows:<package|core>:g<unsigned group>=<16 lowercase hex mask>
```

Sort entries by unsigned group number and join with semicolons. Treat the Java `short` group as
unsigned for identity. Iterate masks while `mask != 0`, using unsigned shifts, so bit 63 is
retained. Map every set bit to:

```text
logicalCpuId = unsignedGroup * 64 + processorNumber
```

The normalizer rejects a result above `1_048_575`. Every core CPU must belong to exactly one
package signature; missing or conflicting package/core ownership is required topology failure.
Die uses `windows:die:0`.

One `CacheRelationship` becomes one cache domain whose sharer mask is the union of all of that
relationship's group affinities. Do not store a group count where a group ID is required and do
not truncate a multi-group domain. Invalid optional cache data falls back in the normalizer.

The current `ProcessorRelationship.pCore` hint is translated conservatively across the complete
core relationship set. When both `true` and `false` occur, map true to PERFORMANCE and false to
EFFICIENCY. When every core is true, map all to PERFORMANCE. When every core is false, map all to
UNKNOWN because the current boolean cannot distinguish a homogeneous machine from an efficiency
class. Conflicting values for the same canonical core fail required topology. P6 owns the final
efficiency-class semantics and richer native data.

A package-private fixture constructor accepts already-built relationship values. It copies the
relationship lists and affinities before translation so later mutation cannot affect the model.
The P2 Windows test proves the common group/signature mapping, not the unsafe raw byte parser.

### macOS

P2 establishes a conservative topology using only process-visible logical ordinals:

```text
logical IDs: 0 .. max(1, availableProcessors) - 1
socketKey:   macos:package:0
dieKey:      macos:die:0
coreKey:     macos:core:<8 lowercase hex logical ordinal>
CoreKind:    UNKNOWN
caches:      absent, therefore exact common fallbacks
```

This gives one synthetic core per logical CPU and one socket. P7 will replace the synthetic
relationships with public-sysctl performance-level/SMT data while retaining the same logical
ownership IDs.

P2 does not call the currently mismatched `getSysctlString` JNI declaration or claim macOS native
topology parity. Native loading/collection failure still reaches common bootstrap and produces the
same complete fallback. The layout gains only the public-record map projections needed to feed the
common facade; it does not expose `TopologyInput` or `TopologyModel`.

## Deterministic normalization

### Input and allocation validation

Normalization performs checks before allocating any ID-indexed array:

- provider name and every source key are nonblank lowercase ASCII with no surrounding whitespace;
- source keys match the exact platform encodings frozen by the parent;
- the logical CPU list is nonempty;
- every logical ID is unique and in `[0, 1_048_575]`;
- active cardinality is at most `65_536`;
- each CPU has one complete socket/die/core tuple and one nonnull `CoreKind`;
- all CPUs in a complete core tuple agree on `CoreKind`; and
- after core grouping, the `long` sum of `(highest logical ID in core + 1)` is at most
  `16_777_216`.

Any violation fails the selected provider. No array, `BitSet`, or collection size is derived from
an unchecked ID, and validation never truncates an otherwise invalid platform input.

### Ordering and global IDs

Compare source keys by unsigned UTF-8 bytes, independent of locale and default charset. Because
accepted keys are ASCII, this is also stable across JVM Unicode behavior, but the implementation
must encode the comparator explicitly rather than rely on locale collation.

Assign identities in this order:

1. sort distinct socket keys and assign global socket IDs `0..socketCount-1`;
2. identify dies by `(globalSocketId, dieKey)`; and
3. sort distinct `(globalSocketId, dieKey, coreKey)` tuples and assign global core IDs
   `0..coreCount-1`.

Logical IDs are never reassigned. Build active CPU lists/maps in ascending numeric order and
core/socket maps in ascending dense ID order. Shuffling providers, cache domains, relationship
records, directories, maps, or sets must produce value-identical models and public projections.

### Exact public meanings

The model and `SystemInfo` publish:

```text
CPU_COUNT     = max active logical CPU ID + 1
CORE_COUNT    = active dense global-core cardinality
SOCKET_COUNT  = active dense global-socket cardinality
MAX_CORE_ID   = CORE_COUNT - 1
MAX_SOCKET_ID = SOCKET_COUNT - 1
```

`getSystemCpus()` returns a fresh ascending array of active logical IDs. `getCpuSet()` contains
exactly those IDs. P/E core masks contain global core IDs; P/E CPU masks contain logical IDs.
Sparse inactive logical positions are absent from maps and return null. There are no holes in
dense core/socket ID ranges and no null entry for an active ID.

For every active CPU `c`:

```text
CpuInfo[c].cpu == c
CoreInfo[CpuInfo[c].core] contains c and has CpuInfo[c].socket
SocketInfo[CpuInfo[c].socket] contains c and CpuInfo[c].core
CpuCacheLayout[c].cpu == c and every L1/L2/L3 mask contains c
```

### Cache canonicalization and completion

First clone each domain mask and intersect it with active CPUs. Discard a domain as unavailable
when its level is outside 1-3, size is nonpositive, resulting mask is empty, or its structural
ownership is invalid. Normalize an invalid line size to 64; a valid line size is a power of two in
`[16, 1024]`.

Reject a provider domain as unavailable when L1/L2 crosses a global socket or L3 crosses a global
socket. Structurally identical domains are deduplicated. For one CPU and level, exactly one
remaining distinct domain may contain the CPU. If zero or more than one nonidentical domain
contains it, use the level fallback for that CPU; do not select by discovery order.

Fallbacks are exact:

| Level |             Bytes | Sharers                                            |
|-------|------------------:|----------------------------------------------------|
| L1    |       `32 * 1024` | every active logical CPU in the same global core   |
| L2    |      `256 * 1024` | every active logical CPU in the same global core   |
| L3    | `4 * 1024 * 1024` | every active logical CPU in the same global socket |

The public mask is the unchanged canonical `SystemInfo.toHexMask` representation and `sharesL#`
is its cardinality. `CACHE_LINE_SIZE_BYTES` is the maximum normalized selected line size across
all active CPU/levels, including 64-byte fallbacks.

`socketL3Cache(socket)` returns zero for an invalid/inactive socket, ignores no active layout,
and sums each distinct canonical L3 mask exactly once. Normalization prevents the same mask from
having ambiguous selected sizes. The method is null-safe for sparse CPU positions and never
dereferences an inactive cache entry.

## SystemInfo initialization and publication

`SystemInfo` remains a static facade with all P0 fields/methods/records unchanged. Its static
initialization order is:

```text
derive architecture flag
  -> TopologyBootstrap selects matching layout singleton/provider
  -> layout collects/normalizes once or owns common fallback
  -> extract exact model from all layout projection maps
  -> assign maps, counts, max IDs, masks, active IDs, and cache-line field
  -> construct/select resource snapshotter independently
  -> debug-render only after every topology field is assigned
```

An unexpected layout-class failure before its normal bootstrap path is also caught at the facade
boundary and replaced by `TopologyBootstrap.fallback(max(1, availableProcessors))`. Fallback and
platform records are never mixed.

The common fallback has one socket, one UNKNOWN core per logical ordinal, default L1/L2 self masks,
one socket-wide default L3 mask, shares `1/1/N`, and 64-byte lines. It uses the exact fallback keys
from the parent and no native call, platform layout, resource provider, or partially initialized
facade field.

After the topology fields are assigned, resource selection remains:

```text
Linux   -> new CgroupV2Resources()
Windows -> WindowsResources.INSTANCE
macOS   -> OSXResources.INSTANCE
other/failure -> null
```

This ordering permits current resource constructors to read the now-complete `SystemInfo` counts.
`getSystemSnapshot()` retains its current null-failure surface when `SNAPSHOTTER` is unavailable;
P2-A does not fabricate a sample or change that public method.

Publication uses JVM class initialization: successful completion of `SystemInfo.<clinit>` safely
publishes its final immutable topology references to every thread. P2-A adds no VarHandle,
volatile field, mutable update, or background lifecycle. P2-B's mapper publication is separate.

Expose one package-private `SystemInfo` accessor returning the exact internal `TopologyModel` for
the later P2-B package-private mapper constructor. The method is invisible to the P0 exported API
comparison and does not change `module-info.java`. No public method returns an internal type.

## Ownership and contamination

- Providers copy native arrays, parsed relationship lists, file strings, maps, and masks before
  returning.
- `TopologyInput` and `TopologyNormalizer` each establish an ownership boundary.
- `TopologyModel` maps, masks, arrays, and projection carrier references are final and immutable.
- Layout getters return the same immutable map object on each call; callers cannot mutate it.
- `SystemInfo.getSystemCpus()` returns a new array and public mask access remains immutable.
- Public record mask accessors continue to parse into a fresh `BitSet`.
- Test providers and fixture constructors never modify `os.name`, process-global `OSName`,
  `SystemInfo` static fields, or native loader state.
- A failed fixture normalizes through an injected bootstrap/model path and cannot contaminate a
  later test.

Topology initialization is one-time and off the worker hot path. P2-A adds no file writer,
deletion, native allocation, executor, polling thread, shutdown hook, lock, or persistent format.

## Failure matrix

| Condition                                        | Direct/internal result                                  | Selected production result            |
|--------------------------------------------------|---------------------------------------------------------|---------------------------------------|
| Empty logical CPU list                           | `TopologyValidationException`                           | one complete common fallback          |
| Negative, duplicate, or too-large logical ID     | actionable validation failure                           | one complete fallback                 |
| Active cardinality/index-sum budget exceeded     | actionable validation failure before indexed allocation | one complete fallback                 |
| Missing/conflicting required ownership           | actionable provider/normalizer failure                  | one complete fallback                 |
| Missing/invalid optional cache                   | exact affected level fallback                           | valid topology retained               |
| Ambiguous overlapping cache domain               | exact affected level fallback                           | valid topology retained               |
| Provider `Exception` or `LinkageError`           | bootstrap records provider/cause                        | one complete fallback                 |
| Unexpected pre-projection layout linkage failure | facade records platform/cause                           | one complete fallback                 |
| Resource-provider initialization failure         | independent diagnostic                                  | valid topology plus null snapshotter  |
| Unsupported OS                                   | explicit diagnostic                                     | common topology plus null snapshotter |
| Caller mutates provider/list/map/array/BitSet    | no published change                                     | model/facade remain stable            |

No selected failure exposes a partially collected platform graph or silently claims platform
parity.

## Deterministic fixtures and assertions

### Required stable tests

The following IDs are mandatory:

```text
io.euhedral_execution.hardware_utils.SystemInfoFallbackTest#initializesWithIncompletePlatformTopology
io.euhedral_execution.hardware_utils.linux.LinuxSystemLayoutFixtureTest#normalizesSparseMultisocketTopology
io.euhedral_execution.hardware_utils.windows.WindowsTopologyFixtureTest#mapsGroupsAndBitSixtyThreeBijectively
io.euhedral_execution.hardware_utils.TopologyCacheFallbackTest#completesEveryActiveCpuDeterministically
io.euhedral_execution.hardware_utils.TopologyOwnershipTest#doesNotAliasProviderStorage
```

Tests may live in the root or internal topology test package needed to reach package-private
fixtures, but their fully qualified names above must remain stable where the P0 ledger names them.

### Linux fixture

Use active IDs `{0,2,8,10,16}` with absent directories/observations for holes. Package 0/die 0,
package 0/die 1, and package 1/die 0 each contain a local core 0 tuple. Assert:

- `CPU_COUNT == 17`, active cardinality 5, three global cores, and two sockets;
- the three local-core-zero tuples have distinct dense global IDs independent of shuffled input;
- all active CPU/core/socket masks and IDs are mutually complete;
- every inactive logical hole returns null; and
- no active cache projection is null.

The fixture exercises only the bounded files/observations defined in this blueprint. P5 retains
online-file races, hotplug, permission, symlink, and full sysfs variants.

### Windows fixture

Build relationship values with group 0 processors 0/63 and group 1 processors 0/63. Shuffle
packages, cores, group-affinity order, and caches. Assert exact logical IDs `{0,63,64,127}`, exact
16-digit signature fragments, a bijection from every group/processor pair, stable dense global
IDs, and complete cache fallback/projection. Include a negative fixture for a core CPU with no or
two package owners.

Do not call `SystemLogicalProcessorInformation.parse(ByteBuffer)` as the proof for this test. Its
full bounds/offset correction and P0 test ID
`WindowsTopologyFixtureTest#parsesMultipleGroupsAndBitSixtyThree` remain P6 work.

### macOS and fallback fixture

Inject empty, missing-key, duplicate-ID, and conflicting-kind inputs into direct normalization and
assert actionable failures. Pass each through bootstrap with processor count 4 and assert the
exact one-socket/four-core fallback, IDs `0..3`, UNKNOWN-to-public-performance projection, default
cache sizes, shares `1/1/4`, self L1/L2 masks, socket-wide L3, 64-byte line size, and no CPU-zero
dereference from an empty platform map.

Also assert the conservative P2 macOS ordinal input normalizes without claiming performance-level
or SMT relationships.

### Cache fixture

Cover partial L1-only, completely absent, invalid line size, inactive sharer bits, duplicate equal
domains, nonidentical overlap, and cross-socket L3. Assert exact provider selection or fallback,
core sibling L1/L2 fallback masks, socket L3 fallback masks, shares, maximum line size, unique
socket L3 sum, and value-identical output after domain shuffle.

### Ownership and bounds fixture

After input construction and again after normalization, mutate every caller-owned list, relationship
list, array, `BitSet`, and reusable provider buffer. Attempt mutation through every returned layout
map. Assert the input/model/layout/public projections and active ID array remain unchanged.

Add focused tests for logical ID `1_048_575`, rejection at `1_048_576`, 65,536 active CPUs without
materializing a quadratic fixture, active-count rejection above it, and the per-core index-sum
budget. Test helpers must validate budgets using compact generated inputs and bounded assertions;
they must not allocate the forbidden snapshot shapes.

### Existing tests

- Keep `SystemInfoTest` and P0 mask compatibility tests green.
- Keep P0 API/native fixtures unchanged unless the compiled current report records only permitted
  additive public methods.
- `TopologyMapperTest` and core-zero compatibility are read-only P2-B behavior checks. They may be
  run as compatibility gates but not edited to accommodate a P2-A failure.

Tests use no sleeps, host CPU-count golden values, JNI calls, or process-global classloader tricks.

## Compatibility and defect-ledger boundary

P0 remains authoritative:

- module name/directives and all five exports are exact;
- every existing public/protected type, hierarchy, field, method, constructor, descriptor,
  generic signature, constant, nested type, and record component remains;
- `SystemInfo` remains a static facade;
- mask formatting remains byte-identical; and
- all seven Java native owner/declaration contracts remain unchanged.

The intended corrections are the common P2 portions of:

- T01: incomplete platform topology becomes a complete fallback;
- T02: sparse IDs and duplicate local core IDs normalize globally and deterministically;
- T03: group/processor values map bijectively including bit 63, without claiming full parser repair;
  and
- T05: provider/model/facade topology storage does not alias caller buffers.

T04, snapshot equality/named-value portions of T05, and T06 implementation belong to P2-B.
Detailed T02 Linux collection and T03 Windows parser parity remain P5/P6. No existing invalid
numeric or platform result becomes a compatibility golden.

The four macOS layout map getters, if the implementation needs them for the exact one-model bridge,
are permitted additive API and must be reported as additions by P0. A design that preserves the
bridge without adding them is acceptable only if it does not leak an internal type, create a
second model, change an existing descriptor, or add a registry. No other public addition is
authorized by this child.

## Implementation checklist

Implement in this dependency order:

1. Add pure topology defaults/mask support and the owned raw value contracts.
2. Implement validation, source-key comparison, global identity assignment, allocation gates,
   cache canonicalization/fallback, public projection, and immutable projection carriers.
3. Implement bootstrap normalization/fallback, selected-model extraction, and independent
   resource-provider selection.
4. Refactor Linux collection into one owned input without initializing resource paths; add the
   bounded injected fixture seam.
5. Refactor Windows relationship translation with unsigned group/mask handling and owned lists;
   leave raw parsing/native declarations unchanged.
6. Add conservative macOS ordinal input and the common projection surface without implementing P7
   sysctl parity.
7. Rebuild `SystemInfo` initialization from the exact selected model, then select resources after
   topology publication.
8. Add fallback, Linux, Windows, cache, ordering/bounds, and ownership fixtures/tests.
9. Run the direct deterministic loop, P0 gates, final hardware verify, read-only core test gate,
   and scope/diff checks.
10. Append the completion record to this blueprint and update only the temporary P2 status block
    in `AGENTS.md`.

An implementation conflict at steps 2, 3, or 7 returns to blueprint; it is not solved with a
public internal-model accessor, global registry, platform-specific fallback, or weakened test.

## Commands and acceptance gates

Use the repository-pinned JDK 21.0.2, Maven 3.9.16, Zig 0.16.0, macOS SDK, signer, and LLVM inputs.
Use `mise exec --` when available. Otherwise use the explicit installed paths documented in
`AGENTS.md`. No command may select training.

### Direct deterministic loop

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='SystemInfoFallbackTest,LinuxSystemLayoutFixtureTest,WindowsTopologyFixtureTest,TopologyCacheFallbackTest,TopologyOwnershipTest,TopologyNormalizerTest,SystemInfoTest' \
  surefire:test
```

This direct-goal loop must not execute the P1 native lifecycle. Exact additional P2-A test class
names may be added to the selector.

### P0 compatibility gate

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='ApiCompatibilityTest,MaskFormattingCompatibilityTest,CoreZeroReservationCompatibilityTest' \
  surefire:test
```

The report must have `module SAME`, zero removed/changed entries, and only exact reviewed additive
entries if the macOS projection requires them.

### Final selected-module gates

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils -am verify
mise exec -- mvn -B -pl euhedral-core -am test
```

The first command rechecks the P1 generated JNI/package/binary gates. Hosted Windows/macOS and
Docker/affinity limitations are reported exactly; they do not justify skipping deterministic P2-A
tests or Java compilation. The core command is read-only compatibility evidence and cannot lead to
a core production edit.

### Scope and hygiene

```bash
git diff --check
git diff --name-only 3e45f9a2 -- euhedral-training
git diff --name-only 3e45f9a2 -- euhedral-core euhedral-reactor-core euhedral-spring-core benchmarks
git status --short
```

Both scope diffs are empty. Search for direct topology reads of partial `SystemInfo`, duplicate
normalization, mutable model maps, stale count-by-cardinality logic, `mask > 0` Windows loops, and
CPU-zero cache dereferences before handoff.

## P2-A acceptance criteria

1. P0 reports the exact module and all baseline declarations unchanged; any additive macOS layout
   getters are exact, reviewed, and expose only existing public record/map types.
2. One unexported provider/input/normalizer/model/bootstrap path owns the selected topology; no
   mutable registry, second normalization, or exported internal-type signature exists.
3. Every active logical CPU has complete mutually consistent CPU/core/socket/cache records, and
   every inactive sparse position is a safe null hole.
4. Linux keeps kernel IDs, distinguishes duplicate local core IDs by socket/die tuple, produces
   dense deterministic global IDs, and does not initialize cgroup resource discovery to collect
   topology.
5. Windows maps `(unsigned group, processor)` bijectively to `group * 64 + processor`, retains bit
   63, canonicalizes relationship signatures independent of enumeration order, and leaves full
   GLPIEx parsing to P6.
6. macOS/common fallback use deterministic ordinals; incomplete selected input becomes the exact
   complete one-socket fallback without dereferencing absent CPU/cache zero.
7. `CPU_COUNT` is the logical index span; core/socket counts are dense cardinalities; max IDs,
   active ID order, P/E masks, and public array-index meanings match the parent exactly.
8. Negative, duplicate, excessive, contradictory, and allocation-polluting inputs fail before
   unsafe allocation with provider/key/ID diagnostics.
9. Cache domains are copied, order-independent, optional-invalid-tolerant, unambiguous, and
   completed with the exact L1/L2 core and L3 socket fallbacks, shares, line size, and canonical
   masks.
10. `socketL3Cache` is sparse/null-safe and counts each distinct canonical L3 mask once.
11. Provider lists/maps/arrays/bitsets, parsed relationship values, model projections, and returned
    active-ID arrays cannot mutate an existing input/model/facade publication.
12. Topology and resource initialization failures are independent; valid topology survives a
    null/failed snapshotter and resource construction observes fully assigned topology counts.
13. JVM class initialization safely publishes one final deeply immutable facade graph; P2-A adds
    no runtime update state or publication primitive.
14. All five stable tests plus ordering/bounds coverage, existing mask tests, P0 gates, final
    hardware verify, and read-only core gate pass or have exact environmental limits recorded.
15. No mapper/snapshot/wrapper, pressure/monitor, affinity/executor, detailed platform/native,
    package/CI, core production, benchmark, training, or unrelated change enters the diff;
    `git diff --check` and final status are clean apart from authorized files.

The P2-A conformance/manual-review action must classify all 15 criteria and the common P2 portions
of T01-T03/T05 as `satisfied`, `deviated`, `unverified`, or `ambiguous`.

## Sizing and split gate

P2-A remains one implementation child and does not split further.

- It owns one Java 17 module, four existing production classes, one bounded unexported package,
  three adapter seams, and five primary fixture families.
- Provider values, normalization, fallback, immutable projection, and `SystemInfo` bootstrap are
  one lifecycle: collect -> validate/normalize -> fallback if required -> publish once. Splitting
  the model from adapters would force both children to duplicate or privately choose key/cache/
  ownership semantics.
- Linux/Windows/macOS translation can be tested independently, but each is a small adapter into the
  same frozen input schema rather than an independent product. Detailed platform implementations
  are already separated into P5-P7.
- There is no mapper concurrency, snapshot value arithmetic, pressure mathematics, affinity
  lifecycle, native build graph, filesystem mutation, or migration in this child.
- The refined implementation context is roughly 1,300 lines of existing topology/relationship
  code plus one new bounded internal package and focused fixtures. It is coherent for one strong
  implementation pass.

A further split would increase cross-child model/projection coordination and make static
initialization harder to review. The child therefore passes the workflow sizing gate.

## Bounded implementation context envelope

The implementation reads only:

- `AGENTS.md`;
- the plan's P2 summary and finalized P2-A implementation prompt;
- the parent blueprint's P2-A topology contract and this child blueprint;
- P0 blueprint completion/audit summaries, API/mask fixtures, defect-ledger T01-T03/T05 entries,
  and P1 closeout/root-audit summary;
- hardware POM/module descriptor;
- `SystemInfo`, the three layout adapters, and the five current Windows relationship value/parser
  classes at their adapter boundary;
- existing `SystemInfoTest`, `TopologyMapperTest`, and P0 API/mask/core-zero tests;
- the top-level constructor/static initialization portions of the three resource providers; and
- read-only compile errors from non-training core consumers.

It does not read resource/pressure internals beyond that constructor boundary, affinity/executor,
native sources, CI, benchmarks, Reactor, Spring, frame/queue bodies, or any training path.

Owned outputs are the new internal topology package, `SystemInfo`, common-boundary layout changes,
P2-A tests/fixtures, this blueprint's completion record, and the temporary P2 status block.

## Implementation model reassessment

### Refined context and coupling

- Modules: one production/test module, plus a read-only core compile/test gate.
- Existing production owners: `SystemInfo` and three exported platform layouts; relationship
  values are bounded adapter inputs.
- New schema: one in-memory owned raw input and one immutable validated model; no wire or persisted
  format.
- Lifecycle: one-time provider selection, collection, validation or fallback, facade field
  assignment, then independent resource-provider selection.
- Precision: exact integer IDs/bounds, unsigned group/mask operations, unsigned UTF-8 ordering,
  canonical mask text, exact cache bytes/shares, and no floating point.
- Safety: provider-buffer ownership, bounded sparse allocation, static-initialization recursion,
  linkage fallback, and hidden same-model projection across exported package boundaries.
- Repair breadth: refactoring three adapters and one facade while preserving P0 bytecode/native
  contracts and satisfying deterministic platform fixtures.

The blueprint removes design ambiguity but does not make this mechanical. A lower-effort pass could
easily introduce a second model, initialize resources during topology collection, lose Windows bit
63, alias a cache mask, or change an exported hierarchy/descriptor. P1's missing Child A artifact
chain is also evidence against relying on a later audit to reconstruct cross-boundary reasoning.
There is no prior P2-A implementation demonstrating that a lower-capability or lower-effort model
can preserve these contracts.

### Capability decision

Confirm the parent-selected **`gpt-5.6-sol` with `high` reasoning effort** for P2-A implementation.
The exact context envelope reduces history load, while high effort remains necessary for coupled
identity, ownership, allocation, three adapters, and static initialization. `medium` or `low` is
not justified. If this model/effort is unavailable, stop or return to the sizing gate; do not
silently downgrade.

The P2-A conformance/manual-review action remains `gpt-5.6-sol` with `high` reasoning effort.

## Risks and unresolved decisions

- Sparse IDs can create large but bounded index spans. Both exact bounds are validated before
  indexed allocation.
- Layouts and `SystemInfo` live in different exported packages. The internal immutable map carrier
  preserves the one-model contract without an exported internal accessor or mutable registry.
- Windows relationship values do not yet encode final trustworthy efficiency semantics. Ambiguous
  values project UNKNOWN; P6 owns richer collection.
- macOS P2 topology is deliberately conservative. P7 owns performance levels, SMT, cache quality,
  and real runtime parity while retaining ordinal ownership IDs.
- The final hardware `verify` depends on P1 native tools and cross products. Unavailable hosted
  runtime evidence stays explicit and does not block deterministic P2-A Java fixtures unless the
  build itself cannot run.

No architectural decision remains for implementation. Identity encodings, count/index meanings,
fallback, cache selection, copy boundaries, model bridge, initialization order, failure handling,
test seams, and implementation capability are settled.

## Handoff condition

Hand off this child blueprint for developer review and merge only when:

- the implementation can follow the checklist without selecting a provider/model/fallback/cache/
  ID/count/ownership/static-initialization rule;
- the sizing gate remains unsplit and the implementation model is confirmed as
  `gpt-5.6-sol`/`high`;
- the parent plan contains the concise P2-A developer-review summary and finalized model label;
- only this blueprint and authorized planning/status text differ from `3e45f9a2`;
- `git diff --check` and scope checks pass; and
- no production or implementation branch work has started.

Do not append an implementation completion record, edit `AGENTS.md`, or create the P2-A
implementation branch before this blueprint is reviewed and merged into the P2 root.

## Implementation completion record

P2-A implementation completed on 2026-08-01 on
`hardware-utils-overhaul/phase-2-topology-model-implementation`, branched from the updated P2 root
at `ff80ae66`.

Implemented scope:

- Added the unexported `internal.topology` provider/input/model/normalizer/bootstrap path with
  immutable owned values, unsigned UTF-8 identity ordering, pre-allocation ID/cardinality/index
  bounds, deterministic dense socket/core IDs, canonical cache selection, and exact whole-model
  fallback.
- Reworked Linux topology-only collection to retain sparse kernel IDs, include package/die/core
  identity, avoid `LinuxPaths` resource initialization, collect optional cache domains, and apply
  the bounded frequency/cache capacity classification.
- Reworked Windows relationship-value translation to use unsigned group IDs, retain mask bit 63,
  form canonical 16-digit affinity signatures, validate package/core ownership, union multi-group
  cache domains, and copy relationship inputs without changing the GLPIEx parser or JNI contract.
- Added the conservative macOS ordinal model and the four reviewed additive public-record map
  projection getters.
- Rebuilt `SystemInfo` initialization around one extracted immutable model, exact sparse index-span
  counts, complete cache projections, null-safe unique L3 aggregation, independent resource
  provider initialization, and class-initialization publication.
- Added the five stable fixture tests plus normalizer ordering, ID bounds, active-count,
  core-index-budget, fallback, cache, and ownership coverage. No mapper/snapshot, resource,
  pressure/monitor, affinity/executor, native/parser, core production, or training file changed.

Verification evidence:

- Direct deterministic loop: passed, 13 tests across the seven selected classes.
- P0 API/mask/core-zero gate: passed; report is `module SAME`, zero removed entries, zero changed
  entries, and exactly four additions, all the reviewed macOS layout map getters.
- `mvn -B -pl euhedral-hardware-utils -am verify`: passed with 43 unit/P0 tests and 6 integration
  tests, including native cross-build, package, signature, binary, load, and warm-removal gates.
- Read-only `mvn -B -pl euhedral-core -am test`: passed; hardware 43 tests, data structures 8,
  hashing 9, and core 99. No read-only consumer file changed.
- `git diff --check` passed; training and non-hardware production scope diffs from `3e45f9a2` are
  empty. Stale cardinality, CPU-zero cache dereference, signed Windows-mask loop, partial topology
  read, and `LinuxPaths.CPU_INFO_BASE` searches found no active production occurrence.

Toolchain note: `mise` and Maven 3.9.16 were unavailable on `PATH`. Verification used the installed
JDK 21.0.11, Maven 3.6.3, Zig 0.16.0, macOS 26.1 SDK, apple-codesign 0.29.0, and system LLVM tools.
Maven reported only that build-cache support requires Maven 3.9; no test or gate was skipped.

The implementation is ready only for the prescribed P2-A conformance/manual review. P2-B remains
out of scope until this implementation and its audit are reviewed and merged.
