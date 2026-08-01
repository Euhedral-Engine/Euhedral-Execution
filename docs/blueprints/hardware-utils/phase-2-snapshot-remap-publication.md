# Phase 2-B Immutable Snapshots, Remap, and Publication

## Status and lineage

- P2 root branch: `hardware-utils-overhaul/phase-2-topology-snapshot`
- Blueprint branch: `hardware-utils-overhaul/phase-2-snapshot-publication-blueprint`
- Implementation branch after review and merge:
  `hardware-utils-overhaul/phase-2-snapshot-publication-implementation`
- Audit branch after implementation review and merge:
  `hardware-utils-overhaul/phase-2-snapshot-publication-audit`
- Parent artifact:
  `docs/blueprints/hardware-utils/phase-2-topology-snapshot-model.md`
- Required predecessor: reviewed P2-A implementation and conformance audit at P2 root commit
  `854bcc1e`
- Blueprint model: `gpt-5.6-sol`
- Blueprint reasoning effort: `max`

This is a planning artifact. It authorizes no production edit, implementation branch, commit,
merge, or push. Implementation begins only after this blueprint is reviewed and merged into the
P2 root.

## Objective

Make the existing hardware-utilization values and effective-topology publications deeply owned,
content-correct, deterministically indexed, and safe for concurrent readers. P2-B translates the
parent's frozen T04-T06 contracts into one implementation pass covering:

1. value semantics for `UnmodifiableBitSet` and `UnmodifiableDoubleArray`;
2. defensive construction/access and complete equality/hash behavior for every public utilization
   record with mutable components;
3. exact named snapshot population and sparse logical-ID/global-core-ID array shapes;
4. constructor-owned allowed membership and the retained global-core-zero reservation;
5. fixed effective-topology list shapes, exact global/socket version state, and greatest-sequence
   request coalescing; and
6. one volatile publication boundary for the complete immutable topology graph.

The implementation must be executable from this checklist without selecting a copy boundary,
equality rule, array span, null-hole meaning, arithmetic rule, coalescing protocol, version rule,
core-zero behavior, or Java Memory Model publication mode.

## Settled predecessor contract

P2-A is complete and audited. P2-B consumes, but does not reopen, these guarantees:

- `TopologyModel` is the single deeply immutable normalized model owned by `SystemInfo`.
- `SystemInfo.topologyModel()` is the package-private production bridge to that exact model.
- Logical CPU IDs are stable public mask bits and per-CPU array indexes. They may be sparse.
- `CPU_COUNT` is `maxActiveLogicalCpuId + 1`.
- Global core and socket IDs are dense; `MAX_CORE_ID + 1 == CORE_COUNT` and
  `MAX_SOCKET_ID + 1 == SOCKET_COUNT`.
- Every active logical CPU has one complete CPU/core/socket/cache projection; inactive logical CPU
  indexes may be null holes.
- Model maps, masks, active-ID arrays, and public projections do not alias provider storage.
- The model already rejects active/index shapes exceeding the parent allocation budgets.

P2-A conformance found no production deviation. Its implementation passed the deterministic
fixtures, P0 compatibility gate, complete hardware verification, and the read-only core gate; the
audit reran its available deterministic and P0 mask/core-zero checks. T04, the snapshot portion of
T05, and T06 deliberately remained for this child.

## Scope and ownership

### Owned production surface

P2-B may change only these production classes:

- `io.euhedral_execution.hardware_utils.TopologyMapper`
- `io.euhedral_execution.hardware_utils.common.SystemUtilization`
- `io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet`
- `io.euhedral_execution.hardware_utils.common.UnmodifiableDoubleArray`

P2-B owns focused hardware-module tests and fixtures for wrapper values, snapshot ownership,
snapshot indexes/fields, mapper membership/core-zero behavior, versions, coalescing, and volatile
publication. It may append its implementation completion record here and update only the temporary
P2 status block in `AGENTS.md` during the later implementation action.

### Read-only boundaries

- `SystemInfo` and `internal.topology` supply the audited P2-A model and stable public projections.
- `ResourceMonitor.poll()` constructs one `HardwareUtilization`, assigns it to the monitor field,
  and then calls `TopologyMapper.update` with that same value. P2-B does not change sampling,
  listener order, exception policy, cadence, or lifecycle.
- `ControlPlaneLattice` reads global versions/topology, indexes the fixed socket list, and passes a
  socket's fixed `effectiveCoreToCpu` list to snapshot derivation.
- `ControlPlaneShard` reads socket versions and indexes `SocketSnapshot.coreSnapshots` by global
  core ID.
- `ControlPlaneFragment` and `ControlPlaneCache` index `CoreSnapshot.cpuSnapshots` by logical CPU
  ID.

Those core consumers remain unchanged. Their direct indexing is an acceptance constraint, not an
invitation to repair core production.

### Explicit non-goals

Do not change:

- P2-A provider, identity, normalization, model, adapter, cache, or fallback design;
- pressure formulas, pressure normalization, monitor polling/listener lifecycle, sampling cadence,
  timestamp ordering, or resource-provider behavior owned by P4;
- affinity, pinned executors, worker lifecycle, routing/draining, or core production;
- detailed Linux, Windows, or macOS collection/native parity owned by P5-P7;
- native sources, JNI, Zig, packaging, module exports, public record component order, public method
  descriptors, or compatibility fixtures except for ordinary P0 test results;
- CI, benchmarks, Reactor, Spring, stored formats, or migration; or
- any training path, command, data, or output.

P2 corrects only finite/saturating arithmetic necessary to populate already-named snapshot fields
without overflow or nonfinite scope utilization. It does not define canonical pressure units or
clamp normalized pressure values.

## Public compatibility and ownership contract

### `UnmodifiableBitSet`

Retain its public class, superclass, constructors, methods, and descriptors. Construction and
`wrap` clone the source. The copy constructor creates an independent value. All public mutators
continue to fail, range getters and array conversions return independent values, and `clone()`
returns a mutable independent `BitSet`. `equals` and `hashCode` use bit content and remain
consistent for ordinary `BitSet` and `UnmodifiableBitSet` operands.

The implementation must not weaken a mutator, expose `delegate`, or rely on the inherited
`BitSet` storage as the authoritative value.

### `UnmodifiableDoubleArray`

Retain every existing public descriptor. Its constructor and `wrap` clone the source array. No
method returns or retains a caller array.

`copy(buffer, bufferStart, bufferEnd, sourceStart)` has this exact behavior:

- require non-null `buffer`;
- require `0 <= bufferStart <= bufferEnd <= buffer.length`;
- require `sourceStart >= 0`;
- copy `delegate[sourceStart]`, then successive values, into `buffer[bufferStart]`, stopping at the
  exclusive `bufferEnd` or the delegate length; and
- when `sourceStart >= length`, copy nothing.

`iterate(start, end, consumer)` requires a non-null consumer and
`0 <= start <= end <= length`, then visits exactly `[start, end)` in ascending index order.

Invalid ranges throw `IndexOutOfBoundsException`; nulls throw `NullPointerException`. Equality and
hash use `Arrays.equals` and `Arrays.hashCode`, including their exact Java double semantics.
`toString` remains `Arrays.toString` content output.

### Public utilization records

Do not reorder, rename, add, or remove a record component. Direct canonical constructors and
factory methods must converge on the same ownership rules.

At every canonical-constructor boundary:

| Record                | Mutable components copied on input                                       | Accessor behavior                 |
|-----------------------|--------------------------------------------------------------------------|-----------------------------------|
| `SystemSnapshot`      | `effectiveCpus`, `pressurePerCpu`                                        | wrappers remain immutable values  |
| `HardwareUtilization` | `globalEffectiveCpus`, `perQuotaCpuThrottleRatio`, `perQuotaCpuPressure` | wrappers remain immutable values  |
| `SocketSnapshot`      | `effectiveCores`, `coreSnapshots`                                        | `coreSnapshots()` returns a clone |
| `CoreSnapshot`        | `effectiveCpus`, `cpuSnapshots`                                          | `cpuSnapshots()` returns a clone  |
| `CpuSnapshot`         | none                                                                     | generated scalar accessors remain |

Incoming bitsets become new `UnmodifiableBitSet` values even if already wrapped. Incoming double
wrappers become newly owned `UnmodifiableDoubleArray` values. Incoming nested arrays are cloned;
null holes are retained exactly. Nested record elements need no recursive clone because their own
mutable components are already owned.

A null mutable component fails immediately with `NullPointerException` naming the record component.
P2-B adds no unrelated numeric constructor validation.

`SocketSnapshot` and `CoreSnapshot` override `equals` and `hashCode` as a pair. Every scalar and
value component participates, and nested arrays use `Arrays.equals`/`Arrays.hashCode`.
`CpuSnapshot` retains generated record equality/hash. Once wrapper value semantics are corrected,
`SystemSnapshot` and `HardwareUtilization` retain generated record equality/hash. Independently
allocated equal trees must compare equal and have equal hashes; a change to any scalar, mask,
wrapper value, null-hole position, or active nested entry must be observable.

## Snapshot derivation contract

### Index and active-entry shape

For one `HardwareUtilization` publication:

- `globalEffectiveCpus` contains logical CPU IDs.
- Per-CPU pressure/throttle wrapper indexes are logical CPU IDs.
- `getSocketSnapshot` receives one socket-specific `effectiveCoreToCpu` list of exactly
  `MAX_CORE_ID + 1` elements. The returned `coreSnapshots` array has the same length.
- A core slot is non-null exactly when that list contains a non-null, nonempty CPU mask for the
  socket. Every other global-core slot remains null.
- `CoreSnapshot.cpuSnapshots` has `effectiveCpus.length()` elements, which is highest effective
  logical CPU ID plus one. Each effective logical CPU index is non-null; every inactive/not-owned
  position is null.
- Populated `socketId`, `coreId`, and `cpuId` equal their enclosing list/array indexes.
- `globalCpuCount` in every populated core and CPU snapshot is
  `globalEffectiveCpus.cardinality()`, not a local count or index span.
- Every populated socket and CPU `lastUsageNs` equals `HardwareUtilization.timestampNs()` exactly.

`getSocketSnapshot` returns null for a null or empty mapping, a mapping with no active core, or a
negative, NaN, or infinite quota. A non-null empty core mask is structurally inactive and remains a
null hole. A mapping containing an active CPU in more than one core, or an active core mask with a
CPU not present in `globalEffectiveCpus`, fails with `IllegalArgumentException`; no partial active
snapshot may escape.

`getCoreSnapshot` requires a non-null CPU mask. For every set bit it creates one complete CPU
snapshot at the logical index. `getCpuSnapshot` returns the compatibility-neutral value for a
negative or inactive logical CPU ID, stamped with the current `timestampNs`. If an active ID is not
covered by both pressure and throttle wrappers, it throws `IllegalStateException` naming the CPU
and missing span instead of returning a neutral active entry.

### Named fields and arithmetic

Define these helpers once and use them consistently:

```text
nonnegative(x) = max(x, 0)

saturatedMultiply(a, b):
  a' = nonnegative(a), b' = nonnegative(b)
  0 if either is 0
  Long.MAX_VALUE if a' * b' would overflow
  otherwise exact a' * b'

saturatedProduct(pool, ratio):
  pool' = nonnegative(pool)
  product = pool' * ratio as double
  0 for NaN, a negative ratio, or a nonpositive product
  Long.MAX_VALUE for positive infinity or product >= Long.MAX_VALUE
  otherwise Java truncation toward zero to long

finiteUtilization(usage, limit):
  (double) nonnegative(usage) / max(nonnegative(limit), 1)
```

Populate fields by name:

```text
globalMemoryLimit = nonnegative(globalMemoryPool)
globalBytesUsed = saturatedProduct(globalMemoryPool, totalMemoryUtilization)

socket activeCpuCount = union cardinality of its active core masks
socket memoryLimit = saturatedMultiply(perCpuMemoryPool, socket activeCpuCount)
socket usage = saturatedMultiply(memPerCpuUsageBytes, socket activeCpuCount)
socket memoryUtilization = finiteUtilization(socket usage, socket memoryLimit)

core activeCpuCount = effectiveCpus.cardinality()
core memoryLimit = saturatedMultiply(perCpuMemoryPool, core activeCpuCount)
core usage = saturatedMultiply(memPerCpuUsageBytes, core activeCpuCount)
core memoryUtilization = finiteUtilization(core usage, core memoryLimit)

cpu memoryLimit = nonnegative(perCpuMemoryPool)
cpu usage = nonnegative(memPerCpuUsageBytes)
cpu memoryUtilization = finiteUtilization(cpu usage, cpu memoryLimit)
```

The socket quota is divided equally among its active cores. A core quota is divided equally among
its active CPUs. Existing pressure combination logic remains unchanged except that the corrected
finite CPU memory utilization feeds it. P2-B does not clamp usage to a limit and does not redefine
the public pressure curve.

The neutral CPU snapshot retains its supplied ID/quota and current period/global values, uses
global active cardinality, uses the same nonnegative global memory fields, and sets local memory,
ratios, and pressure to zero. Its timestamp is the current utilization timestamp.

## `TopologyMapper` architecture

### Construction and topology injection

Retain both public constructor descriptors. They delegate to one package-private constructor with
the exact shape:

```text
TopologyMapper(TopologyModel topologyModel, BitSet allowedCpus)
```

The default public constructor uses `SystemInfo.topologyModel()` and that model's CPU set. The
public mask constructor uses the same production model and the supplied mask. The package-private
constructor is the only deterministic fixture seam; it rejects nulls, clones `allowedCpus`, and
intersects the clone with `topologyModel.cpuSet()`. No mapper operation exposes or mutates the
model mask or caller mask.

Initialize one deeply immutable `EffectiveSystemTopology` with empty masks, a fixed
`topologyModel.maxSocketId() + 1` list filled with nulls, and global version `-1`.

### Candidate membership and core zero

Before enqueue, every `update` makes an owned candidate in this order:

```text
clone utilization.globalEffectiveCpus
  AND topologyModel.cpuSet
  AND constructor-owned allowedCpus
  -> reserve global core ID 0 only when another candidate global core exists
  -> freeze as the request membership
```

Unknown bits disappear before topology lookup. The core-zero mask comes from model global core ID
zero. If the candidate contains any CPU belonging to another global core, remove all candidate
core-zero CPUs. Otherwise retain candidate core-zero CPUs. An empty pre-reservation candidate stays
empty. No step may restore a CPU removed by an intersection.

Pressure, quota, counters, timestamps, and the `SystemSnapshot` do not participate in membership,
coalescing, equality, or version decisions.

### Request coalescing state

Use these roles; local names may vary but semantics may not:

- an `AtomicLong` strictly increasing submission sequence;
- an immutable pending request containing sequence and owned CPU membership;
- an `AtomicReference` pending slot whose replacement rule is greatest sequence wins;
- an `AtomicBoolean` drain-owner flag; and
- one `volatile EffectiveSystemTopology effectiveTopology` as the only reader publication field.

Sequence is assigned immediately before the atomic pending-slot update. The pending-slot CAS loop
retains the request with the greater sequence, so a delayed older writer cannot replace a newer
request. A successful false-to-true owner CAS elects one drain owner. Losing writers return only
after their owned request is installed in the pending slot.

The owner repeatedly atomically takes the pending request, derives a complete candidate graph, and
either publishes it or discards it as membership-identical. Taking the slot before derivation lets
later writers install a newer pending request. Intermediate requests may be coalesced.

On an empty slot, the owner clears the owner flag with volatile/CAS semantics, then rechecks the
pending slot and attempts to reacquire ownership. It returns only when the slot is empty or another
owner is responsible. This release/recheck closes the enqueue-after-empty and enqueue-before-release
races.

If deriving one request throws before publication, retain the last complete topology, remember the
first failure, and continue the same drain/release/recheck protocol so a newer pending request is
not stranded. After pending work is drained or transferred, rethrow that first failure to the
elected caller. No failed or coalesced-unpublished request consumes a version.

### Fixed topology shape

For every derived membership:

- effective CPU, core, and socket masks are immutable;
- the system socket list length is `maxSocketId + 1`, null exactly at inactive sockets;
- each active socket contains only model-owned CPUs/cores for that socket;
- each active socket's `effectiveCoreToCpu` list length is `maxCoreId + 1`, null exactly at
  inactive or other-socket cores;
- every non-null core mask is immutable and nonempty; and
- the union of active socket CPU/core masks equals the system CPU/core masks.

The initial empty list has the same fixed socket span. An empty effective publication after an
active state has a fixed all-null socket list, not `List.of()`.

All lists are unmodifiable and all masks are owned `UnmodifiableBitSet` values before publication.
Records expose no mutable list element. `EffectiveSystemTopology` and
`EffectiveSocketTopology` retain their public component order and descriptors.

### Versions

Global version is stored only in the published `EffectiveSystemTopology`:

- initial version is `-1`;
- the first membership state different from the initial empty state is version `1`;
- each later actually published system membership change adds exactly one;
- identical effective CPU/core/socket membership preserves the same object identity and version;
  and
- pressure-only or other non-membership changes never publish.

Maintain a private `int[maxSocketId + 1]` persistent socket-version array, mutated only by the
exclusive drain owner. For each actually published global change, compare every socket's prior and
next CPU/core membership:

- never-active and still-inactive: unchanged at zero;
- first activation: increment `0 -> 1` and publish socket version 1;
- active and membership-identical while another socket changes: do not increment;
- active membership change: increment once;
- active to inactive: increment once even though the next socket list entry is null; and
- inactive to active: increment once from the retained counter, so reactivation is greater than
  the prior active version.

Use exact checked integer increment. If a global or socket counter is already `Integer.MAX_VALUE`
when an increment is required, fail the request before publication with `IllegalStateException`;
do not wrap or partially update counters. Compute next counters in local owned state and commit the
persistent socket counters only immediately before the same volatile topology publication.

`getEffectiveTopology()` performs one volatile read and returns that value. `getGlobalVersion()`
performs one volatile read and returns that object's `globalVersion`. `getEffectiveSocketTopology`
performs one volatile read, bounds-checks against that object's fixed list, and returns its active
entry or null. There is no separate atomic global-version source.

### Volatile publication and happens-before

All request derivation, list/mask freezing, equality comparison, array construction, and next
socket-version calculation happen before one volatile write to `effectiveTopology`. Every field in
the reachable graph is final and every mutable input has been copied. Reader methods start with one
volatile read. The volatile write/read establishes happens-before for the complete immutable graph.

Pending-slot and owner CAS operations coordinate writers only. They are not a reader publication
substitute. Do not add per-element volatile fields, locks, reader retries, opaque topology reads, a
separate version atomic, or publication through Lombok-generated plain access.

## Bounded implementation checklist

Implement in this dependency order:

1. Add missing range/null checks and owned array/value equality to
   `UnmodifiableDoubleArray`; confirm `UnmodifiableBitSet` satisfies all retained ownership,
   mutator, clone, and content equality tests without changing its API.
2. Add compact constructors to the four records with mutable components, clone-returning nested
   array accessors, and complete paired equality/hash overrides for socket/core snapshots.
3. Centralize nonnegative/saturating memory helpers and correct socket/core/CPU named population,
   global cardinality, timestamp stamping, neutral inactive CPU behavior, and active wrapper-span
   failures.
4. Add the package-private model constructor to `TopologyMapper`; own/intersect its allowed mask and
   build candidates in the frozen order with exact global-core-zero handling.
5. Replace variable-length topology lists with fixed socket/core spans and immutable nonempty
   active entries derived only from the injected model.
6. Replace dropped-on-contention updates and the separate version atomic with greatest-sequence
   pending requests, exclusive draining, persistent socket counters, exact change comparison, and
   release/recheck handoff.
7. Make `effectiveTopology` volatile and make each reader method use exactly one acquired topology
   object. Document the writer/read happens-before argument near the field/state machine.
8. Add the deterministic fixture and race matrix below, while retaining or adapting existing tests
   and stable P0 IDs. Use the injected model seam rather than process-global host topology.
9. Run focused P2-B, P0 compatibility, complete hardware, and read-only core gates. Search for
   stale variable-length list construction, aliased arrays, separate version reads, and dropped
   writer returns.
10. Append the implementation completion record to this blueprint and update only the temporary
    P2 status block. Do not start the audit branch before implementation review and merge.

A compile error may cause local mechanical repair inside these four production classes and owned
tests. Any need to choose a different public field meaning, copy boundary, array shape, arithmetic,
sequence linearization, failure policy, version rule, core-zero policy, or memory mode returns to
this blueprint before more implementation.

## Deterministic fixtures

Use P2-A's normalizer/fallback fixture builders to produce explicit models without mutating
`SystemInfo`. The primary P2-B model has two sockets, at least two global cores, and sparse logical
CPU IDs so all global/local index meanings are observable. Add a core-zero-only model separately.

Required stable test IDs:

```text
SnapshotOwnershipTest#publishedSnapshotsRemainStableAndValueConsistent
SnapshotIndexContractTest#populatesNamedFieldsAndActiveEntries
TopologyMapperCoreZeroTest#fallsBackWhenCoreZeroIsTheOnlyAllowedCore
TopologyMapperPublicationTest#publishesOwnedCoalescedTopology
TopologyMapperVersionTest#versionsOnlyPublishedMembershipChanges
```

Additional focused methods may live in those classes. Tests must cover:

- mutation of every source bitset, double array, mapping mask, and nested array after construction;
- mutation of every array returned by a public accessor;
- independently allocated equal wrapper and snapshot trees plus one-component changes;
- direct canonical constructors as well as `create` factories;
- null mutable components and exact copy/iterate range boundaries;
- sparse CPU IDs, fixed global-core arrays, active completeness, and null inactive holes;
- exact global/local memory fields, saturated overflow/nonfinite products, finite zero-limit
  utilization, quota division, global active cardinality, and timestamp equality;
- unknown allowed/effective bits, caller mutation before/after mapper construction, alternatives
  to core zero, allowed-core-zero-only, effective-core-zero-only, and empty membership;
- first/identical/change/deactivate/reactivate version cases; and
- acquired topology self-consistency while latch-controlled writers contend.

### Deterministic race matrix

No race test uses sleeps. Add a package-private test hook or constructor-injected callback only if
needed to pause after an explicitly named state-machine point; it must not enter the public API or
production hot read path.

| Case                                 | Latch-controlled interleaving                                                                                          | Required assertion                                                                                          |
|--------------------------------------|------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| R1 uncontended first publish         | W1 enqueues sequence 1, becomes owner, drains A                                                                        | A is complete; global/socket first-active versions are 1                                                    |
| R2 older delayed CAS                 | W1 obtains sequence 1 and pauses before pending CAS; W2 obtains sequence 2 and installs B; W1 resumes                  | pending/final state is B; sequence 1 cannot replace 2                                                       |
| R3 enqueue during derive             | Owner takes A and pauses before publication; W2 installs B; owner publishes A then drains B                            | final membership is B; versions reflect only A and B if both actually publish                               |
| R4 intermediate coalescing           | Owner pauses after taking A; W2 installs B; W3 installs C with greater sequence before owner retakes slot              | final state is C; B may be absent and consumes no version if never taken/published                          |
| R5 enqueue after empty read          | Owner observes empty and pauses before clearing owner flag; W2 installs B and loses owner CAS; owner releases/rechecks | current or reacquired owner publishes B; B is not stranded                                                  |
| R6 enqueue after release             | Owner clears flag and pauses before recheck; W2 installs B and becomes owner                                           | exactly one owner drains B; former owner returns without duplicate publication                              |
| R7 identical pressure race           | Writers install different utilization payloads with identical membership                                               | object identity and all versions remain unchanged                                                           |
| R8 derive failure plus newer request | A test hook pauses and then fails derivation of A; W2 installs valid B while A is paused                               | old complete state survives A; B is drained/published; elected caller reports A failure after handoff/drain |
| R9 reader publication                | Owner pauses immediately before volatile write of B while readers repeatedly acquire topology; then release write      | every read is entirely old or entirely B; no mixed masks/lists/versions/null holes                          |
| R10 socket isolation                 | A changes socket 0; coalesced next publication changes only socket 1                                                   | global increments each publication; unchanged socket 0 version does not increment for socket-1-only change  |
| R11 deactivate/reactivate            | Publish both sockets, deactivate socket 1, then reactivate it                                                          | deactivation consumes one private socket version; reactivated entry has the next greater version            |
| R12 final greatest sequence          | Multiple writers enqueue distinct ordered memberships around repeated owner take/release barriers                      | after all callers join, published membership equals greatest submitted sequence and pending is empty        |

Each race test has bounded latch awaits and joins with diagnostic timeouts. It captures every
observed `EffectiveSystemTopology` and validates union/subset/index/null-hole invariants; checking
only the final mask is insufficient for the volatile-publication case.

## Compatibility, verification, and hygiene

Use the exact `mise.toml` toolchain. No command selects training.

### Fast deterministic P2-B loop

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='UnmodifiableBitSetTest,UnmodifiableDoubleArrayTest,SystemUtilizationTest,SnapshotOwnershipTest,SnapshotIndexContractTest,TopologyMapperTest,TopologyMapperCoreZeroTest,TopologyMapperPublicationTest,TopologyMapperVersionTest' \
  surefire:test
```

If wrapper coverage remains combined in `UnmodifiableBitSetTest`, omit only the nonexistent
`UnmodifiableDoubleArrayTest`; do not omit its assertions.

### P0 compatibility gate

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='ApiCompatibilityTest,MaskFormattingCompatibilityTest,CoreZeroReservationCompatibilityTest' \
  surefire:test
```

The API report permits no removal or changed baseline descriptor. P2-B requires no new public
method, record component, exported package, or module change; any additive declaration must be
justified and reviewed before handoff.

### Final selected-module gates

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils -am verify
mise exec -- mvn -B -pl euhedral-core -am test
```

The core gate is read-only. Native/Docker/host limitations are reported separately, but no
deterministic P2-B fixture may be skipped because of host topology.

### Scope checks

```bash
git diff --check
git diff --name-only <p2-b-parent> -- euhedral-training
git diff --name-only <p2-b-parent> -- euhedral-core/src/main
git status --short
rg -n 'AtomicInteger globalVersion|List\.of\(\).*globalVersion|compareAndSet\(false, true\).*return' \
  euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/TopologyMapper.java
```

The two scope diffs are empty. Do not otherwise inspect training.

## Acceptance criteria

1. P0 reports no removed or changed public API/module/mask contract and no exported package change.
2. Both wrappers own construction input, expose no mutable storage, enforce exact ranges, and use
   content-consistent equality/hash.
3. Every mutable record component is copied on direct canonical construction; nested array
   accessors clone and preserve null holes.
4. Equal independently allocated snapshot trees have equal hashes and every scalar/value/nested
   component affects equality.
5. Every active socket/core/CPU snapshot entry is complete at its frozen global/logical index;
   every inactive position is the exact allowed null hole.
6. Global count, IDs, quota, memory limit/usage/utilization, pressure inputs, and timestamps
   populate
   their named fields with exact nonnegative/saturating arithmetic.
7. Neutral invalid/inactive CPU results retain compatibility and current timestamp; a structurally
   uncovered active CPU fails instead of publishing a neutral active entry.
8. The mapper owns/intersects its allowed mask, discards unknown bits, never reintroduces a removed
   bit, and uses only the injected audited model.
9. Global core zero is removed only when another candidate global core remains; all core-zero-only
   and empty candidates retain their valid nonempty/empty meaning.
10. Initial and every published effective topology have fixed socket/core spans, immutable masks
    and lists, complete nonempty active entries, and exact null holes.
11. Global and persistent socket versions follow every first/change/identical/deactivate/reactivate
    rule and do not wrap or change for pressure-only/coalesced-unpublished requests.
12. Greatest-sequence pending replacement and release/recheck draining leave no newest request
    unpublished after callers quiesce, including a failed earlier derivation.
13. One volatile topology write/read publishes each complete graph; readers observe one internally
    consistent object and never combine a separate version source with another topology.
14. ResourceMonitor lifecycle/pressure behavior, P2-A model/adapters, affinity/executor, detailed
    platforms/native sources, core production, and training remain unchanged.
15. Focused tests, race matrix, P0 gates, hardware verify, read-only core tests, `git diff --check`,
    scope checks, and final status evidence pass or record exact environmental limits.

The P2-B conformance/manual-review action must classify all 15 criteria and T04-T06/T05 portions as
`satisfied`, `deviated`, `unverified`, or `ambiguous`.

## Sizing and split gate

P2-B remains one implementation child.

- It owns one Java 17 module, four existing production classes, their focused tests, and a read-only
  core compatibility gate.
- Wrapper ownership, record construction/access, and mapper topology publication meet at the same
  active-ID/fixed-null-hole boundary. Splitting snapshots from the mapper would require two
  children to duplicate the global-core/logical-CPU shape and mutation/race fixtures.
- The state machine is one lifecycle: own candidate -> greatest-sequence enqueue -> exclusive
  derive -> compare/version -> freeze -> volatile publish -> release/recheck.
- The refined context contains no platform collection, static initialization design, monitor
  lifecycle, pressure redesign, affinity, native implementation, migration, or core production.
- The production inventory is about 600 current lines plus focused tests. The deterministic race
  matrix is broad but tests one bounded publication boundary.

Wrapper/value work and mapper concurrency can be locally tested separately, but they cannot be
accepted independently because `EffectiveTopology` masks feed snapshot array construction and the
same old-publication mutation tests cover both. A further split would increase handoff and fixture
coordination without reducing the coupled correctness risk. This child passes the workflow sizing
gate.

## Bounded implementation context envelope

The implementation reads only:

- `AGENTS.md`;
- the plan's P2 summary and finalized P2-B implementation prompt;
- the parent blueprint's immutable-wrapper/snapshot, mapper, P2-B context, tests, and acceptance
  sections;
- this blueprint;
- the P2-A completion/conformance summaries and `TopologyModel`/`SystemInfo.topologyModel()` public
  model boundary, without rereading platform adapters;
- the P0 T04-T06 ledger/API/mask/core-zero contracts;
- the hardware POM/module descriptor;
- the four owned production classes and existing focused hardware tests;
- `ResourceMonitor.poll()` only from `HardwareUtilization.create` through `topology.update`; and
- the named read-only core index/version/snapshot locations in `ControlPlaneLattice`,
  `ControlPlaneShard`, `ControlPlaneFragment`, and `ControlPlaneCache`.

It does not read platform collection internals, detailed resource math or monitor lifecycle,
affinity/executor code, native sources, CI, benchmarks, Reactor, Spring, other core implementation,
or any training path.

Owned outputs are the four production classes, focused P2-B tests/fixtures, this blueprint's
completion record, and the temporary P2 status block.

## Implementation model reassessment

### Refined context and coupling

- Modules: one production/test module plus a read-only core compile/test gate.
- Production owners: four existing classes across the hardware root and common packages.
- Public schemas: five existing utilization records and two effective-topology records; their
  descriptors/component order are fixed and no wire or persisted schema exists.
- Lifecycle states: initial empty `-1`, pending enqueue, drain ownership, derive, identical discard,
  version calculation, volatile publication, release/recheck, socket deactivation, and
  reactivation.
- Concurrency: multiple writers, greatest-sequence coalescing, failure cleanup, and one volatile
  reader boundary require a single Java Memory Model argument.
- Precision: sparse logical/global indexes, exact null holes, checked versions, saturated long and
  double products, finite division, and exact Java array/double equality.
- Repair breadth: wrapper and record constructor changes can expose compile/test assumptions in
  mapper construction, monitor publication, P0 bytecode comparison, and direct-index core tests.

The exact context envelope removes platform and monitor history, but implementation is not a
mechanical edit. The current code demonstrably aliases double arrays, has partial record equality,
populates positional memory fields incorrectly, builds variable list spans, mutates a caller-owned
allowed mask, drops concurrent updates, separates version from topology, and lacks a reader
publication boundary. There is no evidence that a lower-capability or lower-effort pass can repair
those coupled defects without losing a copy, arithmetic, version, or race invariant.

### Capability decision

Confirm the parent-selected **`gpt-5.6-sol` with `high` reasoning effort** for P2-B implementation.
`medium` or `low` is not justified. The work combines public value compatibility, sparse topology,
exact arithmetic, a concurrent coalescing state machine, persistent versions, and explicit
volatile happens-before across the named core consumers. If this model/effort is unavailable, stop
or return to the sizing gate; do not silently downgrade.

The P2-B conformance/manual-review action remains `gpt-5.6-sol` with `high` reasoning effort.

## Risks and unresolved decisions

- Sparse logical IDs enlarge per-core CPU arrays, but P2-A's exact active-count/index-sum budgets
  already bound the shape. P2-B must not substitute cardinality indexing.
- Canonical record constructors are public, so factory-only copying is insufficient. Every mutable
  canonical component and nested array accessor is covered directly.
- Coalescing intentionally hides intermediate membership. Versions count publications, not calls or
  sequences; deterministic barriers distinguish the two.
- Socket deactivation has no public socket record in that publication, so its increment survives
  only in the private counter array until reactivation.
- Array-returning accessors allocate at core read sites. This is required defensive ownership and
  preserves the public descriptor; performance changes beyond this contract belong to a later
  explicitly planned API/core phase.
- Complete hardware verification still depends on P1 native tools. Deterministic Java fixtures and
  compile gates remain mandatory if a hosted native prerequisite is absent.

No architectural decision remains. Copy boundaries, accessor behavior, equality/hash, active
entry completeness, field meanings, arithmetic, array spans, null holes, allowed-mask order,
core-zero behavior, request linearization/coalescing, failure cleanup, global/socket versions,
overflow, and volatile publication are settled above.

## Handoff condition

Hand off this child blueprint for developer review and merge only when:

- the implementation can follow the checklist and race matrix without selecting any copy,
  equality, array-span, null-hole, arithmetic, coalescing, version, core-zero, failure, or
  publication-mode rule;
- the sizing gate remains one coherent child;
- the implementation model is confirmed as `gpt-5.6-sol`/`high` in this blueprint and the plan's
  P2-B prompt is no longer merely parent-provisional;
- the parent plan contains the concise P2-B developer-review summary;
- only this blueprint and authorized planning documentation differ from `854bcc1e`;
- `git diff --check`, training/core-production scope checks, and final status are clean; and
- no P2-B production edit or implementation branch has started.

Do not append an implementation completion record, edit production or `AGENTS.md`, or create the
P2-B implementation branch before this blueprint is reviewed and merged into the P2 root.

## Implementation completion record

Completed on `hardware-utils-overhaul/phase-2-snapshot-publication-implementation` from P2 root
commit `1640b864`.

### Changed surface

- `UnmodifiableDoubleArray` now clones construction input, validates exact copy/iteration ranges,
  and implements array-content equality/hash. Existing `UnmodifiableBitSet` ownership, mutator,
  ordinary-`BitSet` equality, array conversion, and mutable-clone behavior required no production
  correction.
- `SystemUtilization` canonical constructors own every mutable wrapper/array component; nested
  array accessors clone; socket/core equality and hash include all components; snapshot derivation
  uses fixed indexes, complete active entries, current timestamps, global cardinality, named
  nonnegative/saturating memory values, and explicit active-span failures.
- `TopologyMapper` owns and intersects constructor membership, applies core-zero reservation after
  intersection, derives fixed immutable socket/core spans from the injected P2-A model, retains
  persistent socket versions across inactivity, coalesces by greatest submission sequence, drains
  through release/recheck, and publishes the entire final graph with one volatile write/read.
- Added deterministic sparse two-socket and core-zero-only fixtures plus the five stable P2-B test
  IDs, wrapper range/value tests, ownership/accessor mutation tests, named-field/saturation tests,
  fixed-shape/unknown-bit checks, and first/identical/deactivate/reactivate version checks.

### Commands and results

- `mise exec -- ...`: unavailable because `mise` is not installed.
- Fallback tool inspection: OpenJDK `17.0.19` and Maven `3.6.3`; the hardware module's Java 17
  release is compatible, but these are not the pinned Java 21/Maven 3.9.16 defaults.
- Direct P2-B plus P0 mask/core-zero loop through explicit resource/compiler/Surefire goals:
  passed, 13 tests, zero failures.
- P0 API gate: no removals. It remains unverified under the fallback compiler because the report
  contains three Java-17 module-version changes, four already-merged P2-A macOS additions, and the
  two blueprint-required `UnmodifiableDoubleArray.equals/hashCode` additions. Record-method access
  flags match their baseline descriptors after correction.
- `mvn -B -pl euhedral-hardware-utils -am verify`: unavailable past Java compilation because
  `ZIG`/Zig is absent; Maven reports the `zig-build` executable missing.
- `mvn -B -pl euhedral-core -am test`: upstream data-structures tests pass (8 tests), then the same
  missing-Zig hardware lifecycle prevents the read-only core module from starting.
- `git diff --check`, training scope diff, core-production scope diff, stale mapper-state search,
  and final status checks pass. No module descriptor, P2-A model/adapter, ResourceMonitor,
  affinity/executor, native/platform, core production, or training file changed.

### Acceptance evidence and limits

Owned inputs and accessor results cannot mutate older wrapper/snapshot/topology values; independent
trees compare by complete content. Sparse logical CPU/global core positions retain exact null
holes and all active entries are populated. Candidate membership never restores an intersected
bit, core zero survives only when it is the sole candidate core, identical publications preserve
identity, global versions count actual publications, and inactive socket increments persist into
reactivation. The single volatile `effectiveTopology` field is the only reader publication/version
source; all reachable masks/lists/records are frozen before assignment.

The pinned-tool API report, complete native-backed hardware verify, and read-only core test gate
must be rerun in the documented Java 21/Maven 3.9.16/Zig environment before audit handoff. No
deterministic fixture was skipped for host topology.
