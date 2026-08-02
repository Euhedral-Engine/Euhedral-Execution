# Phase 3-A Affinity Capability and Managed Ownership

## Status and authority

- Parent plan: `docs/plans/hardware-utils-platform-parity-overhaul.md`
- Parent blueprint: `docs/blueprints/hardware-utils/phase-3-affinity-executor-lifecycle.md`
- Parent root: `hardware-utils-overhaul/phase-3-affinity-executor` at `7d3abea7`
- Blueprint branch: `hardware-utils-overhaul/phase-3-affinity-capability-blueprint`
- Owning module: `euhedral-hardware-utils` (Java 17 release target)
- Blueprint model: `gpt-5.6-sol`
- Blueprint reasoning effort: `max`
- Status: implementation-ready child contract; review and merge into the P3 root are required
  before implementation

This child refines only the parent's frozen P3-A responsibility. The parent remains authoritative
for capability meanings, mask mathematics, lease/restoration semantics, managed ownership, and the
P3-A/P3-B boundary. If implementation needs a different capability, mask, platform-call,
restoration, release, ownership, current-CPU, memory-mode, or test-seam rule, it stops and returns
to this blueprint. It does not make a convenient local substitution.

The completed P0-P2 artifact-index entries and closeout summaries are inherited evidence. P0 fixes
the exact module/API/JNI descriptors, additive-API rule, canonical public mask text, and A01 test
anchor. P2 fixes logical CPU identity, the immutable active mask, Windows group mapping, macOS
ordinals, and allocation bounds. P3-A does not reopen those decisions or reclassify their recorded
verification limits.

## Objective

Replace the destructive, nullable-pinner `ThreadTools` affinity path with one truthful,
instance-testable Java controller that:

1. adds exactly the public `AffinityCapability` enum and capability query;
2. validates and owns every request before any platform call;
3. reports only operational exact placement, conservative locality hinting, or unsupported;
4. discovers `BASE_MASK` without changing affinity;
5. restores the calling thread's first captured exact binding or releases one applied locality
   hint with tag zero;
6. gives managed task threads a nested, scoped Euhedral logical owner without claiming physical
   placement;
7. remains safe when the OS is unsupported, a platform singleton is absent, or a recoverable
   native boundary fails; and
8. proves the complete contract with deterministic Java fakes rather than host-affinity timing.

P3-A does not change executor lifecycle. It supplies only the package-private managed-task binding
operation that P3-B will consume after this child is implemented, audited, and merged.

## Scope and compatibility boundary

### Owned production surface

The implementation may change only these contract-bearing files:

```text
euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/
  AffinityCapability.java                         (new public enum)
  ThreadTools.java
  linux/LinuxAffinity.java
  windows/WindowsAffinity.java
  osx/OSXAffinity.java
  internal/ThreadPinner.java
```

It may add a small number of package-private or public-in-an-unexported-package affinity controller,
request, snapshot, lease, provider-seam, and managed-owner files. Such files live under
`io.euhedral_execution.hardware_utils.internal` unless the package-private P3-B bridge must live
beside `ThreadTools`. No internal type appears in a public or protected signature.

`module-info.java`, the exported legacy
`io.euhedral_execution.hardware_utils.common.ThreadPinner`, the hardware POM, and every existing
native declaration are read-only compatibility surfaces. No package export, `requires`, `opens`,
`uses`, or `provides` directive changes.

### Owned tests

Add the stable root-package test:

```text
euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/
  ThreadToolsAffinityTest.java
```

Small package-local facade tests may be added as:

```text
linux/LinuxAffinityTest.java
windows/WindowsAffinityTest.java
osx/OSXAffinityTest.java
```

They exist only to drive package-private raw-call seams and must not load or assert real
cross-platform placement. Do not add a broad integration fixture or a second affinity controller
suite.

### Read-only compatibility inputs

- `euhedral-hardware-utils/pom.xml` and `module-info.java`;
- the P0 API/native fixtures, `ApiCompatibilityTest`, `MaskFormattingCompatibilityTest`, and
  `PinnedThreadExecutorCompatibilityTest`;
- `SystemInfo.getCpuCount()`, `getCpuSet()`, and `getCpuInfo(int)` as the P2 projection boundary;
- the named `ThreadTools.getCpu()` callers in `LatticeVertex`, `UpstreamQueue`, and `FrameFactory`;
- the named `ThreadTools.getCpuInfo()` callers in `ControlPlaneFragment` and
  `benchmarks/.../MandelbulbFrame`; and
- the Java native declarations already present in the three affinity facades.

Those callers are compile/test evidence only. Their production files do not enter the diff.

### Prohibited work

- `PinnedThreadExecutor` implementation or any executor registry/lifecycle/cleanup work owned by
  P3-B;
- native implementation bodies, new or changed native declarations, processor-group APIs,
  sysfs/proc discovery, Mach topology, timer-policy repair, or detailed P5-P7 parity;
- topology production/adapters, resources, `ResourceMonitor`, pressure, snapshots, or cadence;
- core production, benchmarks, Reactor, Spring, CI, root POM, native build/package/loader, or
  unrelated cleanup; and
- any inspection, edit, build, test, documentation, or command under `euhedral-training`.

## Frozen public contract

### Additive capability API

Add this enum in the already exported root package, in this exact declaration order:

```java
public enum AffinityCapability {
    EXACT,
    LOCALITY_HINT,
    UNSUPPORTED
}
```

Add exactly this public method to `ThreadTools`:

```java
public static AffinityCapability getAffinityCapability()
```

It returns a non-null, class-initialization-published, stable result for the operational common
`ThreadTools` path. It does not report an OS's theoretical capability or promise that every later
individual call succeeds.

Retain the descriptors, modifiers, and declared exceptions of `ThreadTools.BASE_MASK`, `getCpu`,
`getCpuInfo`, every `setAffinity` overload, `releaseAffinity`, and `setTimerResolution`. Add
`@Nullable` to `getCpuInfo()`'s return type because `null` is now explicit; the descriptor remains
`()Lio/euhedral_execution/hardware_utils/SystemInfo$CpuInfo;`.

Retain all existing public/protected facade types, `INSTANCE` fields, constructors, methods,
native modifiers, and JNI declarations. The exported legacy `common.ThreadPinner` is unchanged.
The internal sealed `ThreadPinner` may gain non-public operational hooks and an internal provider
contract, but its three public abstract compatibility methods and the three facade inheritance
relationships remain.

### Capability meanings

| Capability      | Successful common request                                                                                | Release                                     | Unmanaged current CPU                                             |
|-----------------|----------------------------------------------------------------------------------------------------------|---------------------------------------------|-------------------------------------------------------------------|
| `EXACT`         | complete requested supported logical set is applied atomically after exact original capture is available | restore the exact first captured binding    | validated independent provider CPU, otherwise no fabricated value |
| `LOCALITY_HINT` | every requested logical CPU maps to one identical representable locality and that one hint applies       | invoke the provider's tag-zero release once | validated independent provider CPU when available, otherwise `-1` |
| `UNSUPPORTED`   | never succeeds and never invokes a raw setter                                                            | no-op                                       | validated independent provider CPU when available, otherwise `-1` |

`EXACT` requires capture, full apply, and exact restore together. A raw setter alone is
insufficient.
`LOCALITY_HINT` makes no placement guarantee. Boolean success means only that the one hint was
applied. `UNSUPPORTED` is a normal runtime result, not an initialization failure. Affinity
mutation/restoration capability does not suppress an independently truthful current-CPU query.

### P3 production capability table

Detailed native work remains deferred, so P3-A freezes this conservative common-path table:

| Selected facade                          | Common capability in P3-A                                                                  | Independent current CPU | Reason                                                                                                                            |
|------------------------------------------|--------------------------------------------------------------------------------------------|-------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| Linux                                    | `UNSUPPORTED`                                                                              | existing native query   | the existing declarations expose a truthful current CPU but no exact capture/restore operation; P5 may raise affinity to `EXACT`  |
| Windows                                  | `UNSUPPORTED`                                                                              | unavailable in P3       | the existing group-relative query is not yet a proved Euhedral logical ID; P6 owns that correction and exact affinity             |
| macOS                                    | `LOCALITY_HINT` when its existing Java/native boundary is present, otherwise `UNSUPPORTED` | unavailable in P3       | P3-A can represent one Mach tag but cannot truthfully report current CPU; P7 may widen locality grouping but never report `EXACT` |
| unsupported OS or absent facade instance | `UNSUPPORTED`                                                                              | unavailable             | no provider is dereferenced                                                                                                       |

The controller seam nevertheless implements and tests `EXACT` now so P5/P6 can supply complete
providers without changing common semantics.

## Internal architecture and test seam

### Required roles

Use one `AffinityController` instance behind static `ThreadTools`. It receives at construction:

- a nullable/absent production provider or deterministic fake provider;
- an owned copy of the immutable supported logical CPU mask;
- the P2 logical index span; and
- the existing bounded logger/diagnostic boundary.

Construction validates `span` in `[1, 1_048_576]`, validates that the nonempty supported mask has
no bit at or above `span`, and performs base discovery exactly once. The controller exposes
package-private instance operations corresponding to the public facade, lease release, managed
binding, and test-only state observations. Tests construct it directly; they do not mutate static
`ThreadTools`, change `os.name`, use reflection to reset final fields, or rely on test order.

The internal sealed `ThreadPinner` is the production platform boundary. Its non-public operational
hooks cover:

- declared capability;
- truthful raw current CPU when available;
- non-mutating exact binding capture;
- complete exact apply and exact snapshot restore;
- logical-CPU-to-locality mapping, one locality apply, and locality release; and
- the unchanged timer operation.

A small package-private provider interface may mirror those hooks so `ThreadToolsAffinityTest` can
use deterministic fakes without subclassing the sealed `ThreadPinner`. Production facades adapt to
that interface. The fake interface is not exported, stored globally, selected through a system
property, or reachable through public API.

Required immutable value roles are:

- `AffinityRequest`: owned canonical `long[]`, highest requested logical ID, number/positions of
  nonzero words, and optionally the already-resolved single locality;
- `AffinitySnapshot`: provider-owned original exact binding, distinct from an empty or absent
  snapshot; and
- one thread-confined lease state discriminating exact restoration from locality release.

Array constructors clone. Array access passed to a provider or returned to a test is another
clone. No record accessor or ordinary getter exposes the stored mutable array.

### P3-B managed-task bridge

`ThreadTools` adds one package-private operation in the root package:

```text
bindManagedCpu(int logicalCpu) -> ManagedCpuBinding
```

`ManagedCpuBinding` is a package-private, no-checked-exception `AutoCloseable` contract named
exactly as shown. It may be a package-private nested type or top-level root-package type; it is not
public/protected and is absent from the P0 API surface. P3-B can call it from
`PinnedThreadExecutor` without importing an unexported implementation type.

Binding rejects a negative, out-of-span, or unsupported/hole ID with
`IllegalArgumentException` before changing thread-local state. The returned token records the
creating thread, its predecessor token/ID, and its own state. This operation is the only P3-A
executor-facing boundary; P3-A does not wrap commands, start threads, or inspect executor state.

## Request ownership and unsigned mask algorithm

The supported topology mask is the P2 `SystemInfo.getCpuSet()` snapshot. A logical ID is supported
only when it is both below `CPU_COUNT` and set in that mask. Sparse holes are invalid requests; they
are never silently intersected away.

For `long[]`, word `i` is little-endian and bit `b` is logical CPU `64 * i + b`, including bit 63.
Use `Long.numberOfTrailingZeros`, `Long.numberOfLeadingZeros`, unsigned shifts, or
`word &= word - 1`;
never use signed comparison to enumerate a word.

Canonicalization is one shared operation used by the controller and direct facade validation:

1. reject `null`;
2. reject an input word count greater than `(span + 63) >>> 6` before cloning or allocating
   another proportional buffer;
3. clone the accepted input before inspection;
4. remove trailing zero words from the owned logical representation;
5. reject zero length/all zero;
6. compute the highest set logical ID with checked `long` arithmetic;
7. reject a bit at/above `span` or outside the owned supported mask; and
8. retain the exact full canonical mask; never truncate, intersect, or split it into reported
   success.

The absolute accepted ceiling is 1,048,576 bits, 16,384 words, and 131,072 mask bytes. Size/index
arithmetic uses `long` before checked conversion to `int`.

Overload rules are exact:

- `setAffinity(int cpu)`: reject negative, at/above span, or unsupported before allocating the
  minimal word array.
- `setAffinity(int[] cpus)`: reject null and an input length above 1,048,576 before cloning; clone,
  validate every ID first, then allocate. Duplicate valid IDs collapse to one bit. Empty is false.
- `setAffinity(BitSet cpus)`: reject null; reject an observable storage/high-bit bound beyond the
  maximum before cloning, then clone and recheck the clone before conversion. Empty is false.
- `setAffinity(long[] masks)`: use the canonicalization above.
- `setAffinity()`: obtain the controller's truthful current logical CPU, then use the same
  single-ID path; `-1` therefore returns false without a provider mutation.

Concurrent mutation of a caller collection is not synchronization supplied by this API. The owned
clone is the request snapshot; all validation and calls use only that snapshot.

## Apply matrix and direct facade rules

### Common controller matrix

| Request                                     | `EXACT`                                             | `LOCALITY_HINT`                             | `UNSUPPORTED`  |
|---------------------------------------------|-----------------------------------------------------|---------------------------------------------|----------------|
| empty/all zero                              | false; no capture/apply                             | false; no mapping/apply                     | false; no call |
| one supported CPU                           | true only after capture availability and full apply | true only after its one locality applies    | false          |
| multiple CPUs in one Windows word/group     | one complete atomic apply or false without mutation | true only if every CPU maps to one locality | false          |
| multiple nonzero Windows words/groups       | one atomic full-mask apply or false before mutation | true only if every CPU maps to one locality | false          |
| multiple CPUs in one locality               | exact full-mask semantics                           | one apply of that locality                  | false          |
| CPUs mapping to different/absent localities | exact full-mask semantics                           | false before apply                          | false          |

For locality, resolve every requested bit before applying anything. An absent mapping, conflicting
mapping, invalid tag, or provider exception rejects the whole request. Do not apply the first hint
while still validating later CPUs.

For exact, capture is non-mutating and apply/restore provider methods are all-or-nothing contracts.
If a provider cannot make that guarantee, it must not declare `EXACT`.

### Existing public platform facades

Each existing `setAffinity(long[])` clones and validates through a package-local/shared pure helper
before its one raw call. It keeps its existing descriptor and boolean result.

- Linux may make exactly one existing JNI call for a complete valid canonical mask and return true
  only for status zero. No Java word-by-word fallback is allowed.
- Windows may make exactly one existing JNI call only when the canonical request has one nonzero
  group word. A request with two or more nonzero group words returns false before JNI in P3-A;
  P6 owns a proved atomic multi-group operation.
- macOS may make exactly one existing JNI call only when all requested CPUs resolve to one
  conservative representable P3 locality. P3 maps each process-visible logical ordinal to its own
  nonzero tag, so after duplicate collapse this is exactly one distinct CPU. Multiple distinct
  ordinals return false before JNI. P7 may widen the mapping from public topology evidence.

Empty/all-zero remains false for every public `setAffinity`. The macOS operational release hook,
not public `setAffinity`, invokes the existing raw boundary once with the all-zero/tag-zero release
representation. No new JNI declaration is added. Package-private facade seams accept a fake raw
call and expose call count/input/status to tests; production uses the same helper with the existing
native method reference.

Direct facade setters do not capture or promise restoration. Therefore their boolean result does
not upgrade the common `ThreadTools` capability.

## Capability initialization and base discovery

Provider/facade selection occurs once under ordinary JVM class initialization. A missing singleton,
unsupported OS, or recoverable selection `RuntimeException`/`LinkageError` installs the unsupported
controller without dereferencing the missing provider. Fatal errors described below propagate.

The controller computes one effective capability:

- a declared `EXACT` provider is effective `EXACT` only if one initialization-time non-mutating
  capture succeeds and produces a nonempty, bounded, supported exact snapshot;
- failed/absent/invalid initial exact capture downgrades the stable common capability to
  `UNSUPPORTED` without applying anything;
- a declared `LOCALITY_HINT` remains `LOCALITY_HINT` only when the mapping/apply/release hooks are
  present by construction; it performs no probing setter call; and
- every other case is `UNSUPPORTED`.

Base discovery performs at most that one exact capture and never sets affinity, creates a probing
thread, iterates by trial CPU, or calls release. If it succeeds, `BASE_MASK` is an
`UnmodifiableBitSet` copy of the captured mask. Otherwise `BASE_MASK` is an
`UnmodifiableBitSet` copy of the supported P2 topology mask.

The fallback base is compatibility/diagnostic data only. It is never an `AffinitySnapshot`, never
creates a lease, and never restores or expands any calling thread's affinity. `BASE_MASK` remains
nonempty because the injected/production supported mask is validated nonempty.

## Per-thread affinity lease

The controller owns one ordinary `ThreadLocal` affinity lease per calling thread.

### Exact acquisition

For the first exact request since release on a thread:

1. capture the calling thread's current exact binding non-destructively;
2. reject on absent/invalid capture, retaining no lease and making no apply call;
3. install a pending lease containing an owned snapshot before the provider apply;
4. invoke one all-or-nothing complete apply; and
5. retain and mark the lease applied only on success; otherwise remove the pending lease in
   `finally`.

A later successful exact set before release reuses the first snapshot and does not recapture.
Failure of a later apply leaves the earlier successful lease intact so release can still restore
the true original. No fallback mask replaces a missing snapshot.

### Locality acquisition

Resolve the complete request to one locality before mutation. On a thread without a locality
lease, install a pending lease before the one apply and remove it if apply fails. On success retain
the applied-locality lease. Later successful hint changes before release retain the same release
obligation; a later failure does not erase an earlier applied lease.

### Release

`ThreadTools.releaseAffinity()` examines only the calling thread's lease:

- exact lease: attempt one exact restore using the first snapshot;
- locality lease: attempt one tag-zero release;
- absent lease: do nothing.

Remove the lease in a `finally` block whether restore/release returns false or throws. Emit one
bounded diagnostic without logging masks or retaining the throwable/request. The public method
remains `void`; it neither retries nor substitutes `BASE_MASK`. P3-B cleanup can therefore continue
after a recoverable release failure.

## Managed ownership and current CPU

Managed ownership is a second, independent ordinary `ThreadLocal` stack. It does not imply that an
affinity request succeeded.

`bindManagedCpu` pushes one token and ID. Closing the current top token on its creating thread:

- restores the exact predecessor token/ID when nested; or
- calls `ThreadLocal.remove()` when there was no predecessor.

Successful close is idempotent. Closing a current top token twice is a no-op. Before successful
close, a wrong-thread close or same-thread out-of-LIFO close throws `IllegalStateException` and
changes neither thread's state. Token fields do not strongly retain a command, executor, or
platform request.

Current CPU selection is deterministic and independent of affinity mutation capability:

1. when a provider exists, ask its independently truthful current-CPU hook and accept only an ID
   in the span and supported mask;
2. a provider without such a query returns `-1` without invoking an untruthful platform boundary;
3. if the result is unavailable/invalid or the provider throws recoverably, return the current
   managed logical owner when present; and
4. otherwise return `-1`.

`getCpuInfo()` calls that operation once. It returns `SystemInfo.getCpuInfo(cpu)` only for a
nonnegative supported result and returns `null` otherwise. It never calls
`SystemInfo.getCpuInfo(-1)`
and never invents CPU zero.

## Failure contract

| Failure                                                            | Result and cleanup                                                           |
|--------------------------------------------------------------------|------------------------------------------------------------------------------|
| unsupported OS or absent singleton                                 | stable `UNSUPPORTED`; no dereference, capture, setter, or physical CPU claim |
| null/empty/oversize/out-of-span/hole request                       | false before provider mutation; no request/snapshot/lease retained           |
| initial exact capture absent/invalid/fails                         | effective capability `UNSUPPORTED`; fallback base only                       |
| per-thread first exact capture absent/invalid/fails                | false; no apply and no lease                                                 |
| exact/locality apply returns false or throws recoverably           | false; remove only a newly pending lease; preserve a prior successful lease  |
| exact restore/locality release returns false or throws recoverably | remove lease in `finally`; bounded diagnostic; caller cleanup continues      |
| locality mapping missing/conflicts/throws recoverably              | false before apply; no new lease                                             |
| provider current CPU invalid/throws recoverably                    | managed-owner fallback, otherwise `-1`                                       |
| wrong-thread/out-of-order managed close                            | `IllegalStateException`; no thread-local mutation                            |
| invalid managed logical ID                                         | `IllegalArgumentException`; no thread-local mutation                         |

At affinity/provider boundaries, normalize `RuntimeException` and `LinkageError` to the table's
false/unsupported/fallback outcome. Do not catch or normalize `VirtualMachineError` or
`ThreadDeath`; outer `finally` cleanup still runs where one already exists. Diagnostics use SLF4J
placeholders, pass the throwable last, have fixed-size messages, and do not stringify masks in a
hot path.

`setTimerResolution` is not redesigned. With a selected provider it preserves the existing
delegation and provider behavior; without one it returns false rather than dereferencing null.
Timer/platform-policy correction remains P5-P7.

## Java Memory Model, ownership, and contamination

- The selected provider, effective capability, supported mask, `BASE_MASK`, and static controller
  are final references assigned during class initialization. Class-initialization happens-before
  publishes the complete graph.
- `AffinityRequest` and `AffinitySnapshot` clone at entry and expose no mutable storage. Their
  fields are final, so safe publication exposes complete immutable values.
- Affinity leases, managed owner stacks, pending/applied flags, and token ordering use plain access
  because they are confined to the creating thread. No volatile, atomic, VarHandle, lock, or
  cross-thread registry is needed.
- Managed token owner identity is final. Wrong-thread checking reads only immutable identity and
  never reads another thread's `ThreadLocal`.
- Provider calls are synchronous. A fake or native adapter receives a fresh array copy and cannot
  mutate the controller's request or snapshot.
- Every success/failure release path removes the affinity lease; every successful outermost owner
  close removes the managed-owner value. Tests expose package-private `hasAffinityLease()` and
  `hasManagedOwner()` observations only for the current test thread.
- Failed requests retain neither caller buffers nor provider buffers. `BASE_MASK` is one bounded
  immutable value; no per-CPU probe arrays, history, thread, registry, or global task reference is
  created.
- Native/file/native-buffer contamination is not applicable because native implementation and
  filesystem/resource work are prohibited. Java array and thread-local contamination are fully
  applicable and tested.

If implementation introduces an atomic, VarHandle, cross-thread mutable field, or global provider
registry, it must stop and return here with the required happens-before argument. Such machinery is
not authorized by this contract.

## Deterministic test contract

Tests use injected providers and raw-call functions. They do not depend on host CPU placement,
change the test runner's lasting affinity, call `System.gc()`, sleep, modify global OS properties,
or use a cross-platform native runtime as semantic evidence. Every helper restores/closes its
current-thread tokens in `finally` and asserts both thread-local seams empty.

`ThreadToolsAffinityTest#discoversAndRestoresTheOriginalMask` is the A01 anchor. With an exact fake
whose initialization snapshot and first per-thread snapshot are distinct sparse masks containing
bit 63, it proves:

- construction makes exactly one non-mutating capture and zero apply/release calls;
- the base mask is the owned initialization snapshot;
- the first exact set captures once, repeated successful sets do not replace it; and
- release restores exactly the first per-thread snapshot once and removes the lease.

The same class also covers:

1. absent/invalid initialization capture downgrades to `UNSUPPORTED`, copies the supported mask as
   base, and never uses it as restoration;
2. null, empty, oversize, negative, out-of-span, sparse-hole, and unsupported requests make zero
   provider calls and create no lease;
3. IDs `0`, `63`, `64`, and `127`, bit 63 in multiple words, trailing-zero trimming, duplicate
   `int[]` IDs, and the exact maximum/rejection boundary;
4. every cell of the empty/one/same-group/cross-group/same-locality/multi-locality capability
   matrix, including an exact fake that accepts the complete cross-group mask atomically;
5. an exact fake configured unable to perform atomic full apply rejects without mutation, and a
   locality fake resolves every CPU before one call;
6. same-locality multi-CPU success makes one hint call, different/missing locality makes zero, and
   release calls tag zero exactly once only after success;
7. failed first apply removes its pending lease, failed later apply retains the first lease, and
   restoration/release failure still empties it;
8. nested managed tokens restore the prior ID; invalid, wrong-thread, out-of-order, double-close,
   normal, and failing paths have the exact outcomes above;
9. independent provider current CPU preference across mutation capabilities, invalid/throwing
   current CPU managed fallback, and unavailable unmanaged `-1`/`null`; and
10. mutation of every caller `long[]`, `int[]`, `BitSet`, provider-received array, captured
    snapshot source, and base source after the call cannot alter owned state.

Facade package tests use counters and copied arguments to prove:

- Linux performs zero raw calls for invalid input and one call for a complete valid mask;
- Windows performs one call for one nonzero group and zero calls for cross-group input;
- macOS performs one call for one representable ordinal, zero for multiple ordinals, reports
  locality rather than exact, and its operational release seam emits tag zero once; and
- raw false status, `RuntimeException`, and `LinkageError` become false without retaining input,
  while fatal errors propagate.

The P0 API test must report zero removed/changed declarations and only the reviewed additive enum,
enum constants/compiler members, and capability query. The module section stays `SAME`. The P0
native declaration comparison stays unchanged. Canonical mask formatting remains green even
though request validation uses the same logical bit meanings.

## Bounded implementation checklist

Implement in this order as one P3-A action item:

1. Add `AffinityCapability` and the additive `ThreadTools.getAffinityCapability()` query; preserve
   every baseline descriptor/export/JNI declaration and add only the nullable return annotation.
2. Add the bounded immutable request/snapshot representations and one fakeable provider contract;
   make internal `ThreadPinner` the sealed production adapter without changing the exported legacy
   pinner.
3. Build `AffinityController` from an owned supported mask/span/provider; validate its constructor,
   select the stable effective capability, and perform exactly one non-mutating base discovery.
4. Implement every overload through one unsigned, copy-first, pre-allocation-bounded canonicalizer;
   reject empty, high, sparse-hole, unsupported, and partial requests before provider mutation.
5. Implement exact first-original pending/applied lease state, repeat-set preservation, exact
   restore, locality whole-request mapping, one-hint apply, and tag-zero release with `finally`
   removal.
6. Implement nested owner-thread `ManagedCpuBinding`, the package-private P3-B bridge, and the
   exact provider/managed/`-1` current-CPU decision tree.
7. Adapt Linux, Windows, and macOS Java facades to the frozen conservative P3 table and direct-call
   rules using package-local fake raw-call seams; do not add JNI or inspect/change native bodies.
8. Normalize only the settled recoverable failures, preserve fatal errors, keep timer delegation
   out of redesign, and ensure every failure path retains no new request/snapshot/thread-local.
9. Add the deterministic controller and facade suites, including A01, full matrix, mutation,
   nesting/wrong-thread, failure, and zero-call assertions.
10. Run the focused P3-A suite, P0 API/native/mask gates, hardware verification, read-only core
    compile/tests, and exact scope/hygiene checks; append completion evidence only after all
    applicable acceptance criteria are met or exact environmental limits are recorded.

No checklist item authorizes executor implementation or detailed platform parity.

## Acceptance criteria

1. `AffinityCapability` and `getAffinityCapability()` are the only public/protected API additions;
   all baseline declarations, JNI declarations, and module directives are unchanged.
2. Capability is non-null, stable, operationally truthful, and follows the conservative P3
   production table; macOS never reports `EXACT`.
3. All overloads copy and validate the complete unsigned request within the P2 span/active mask;
   bit 63 is retained and no invalid bit is intersected into success.
4. Every deterministic matrix cell passes; rejected/unrepresentable requests make zero raw calls,
   Windows never reports partial cross-group success, and macOS never applies a first-only hint.
5. Base discovery is non-destructive and bounded to one capture; fallback base data never restores
   or expands affinity.
6. Exact calls retain and restore the thread's first original binding; locality calls apply one
   fully resolved hint and release tag zero once; all new/failing lease paths clean exactly.
7. Managed ownership is supported-ID-only, owner-thread/LIFO/idempotent/nested-safe, independent of
   physical placement, and absent after outer close.
8. Current CPU follows the independent truthful-provider-first then managed fallback tree; Linux
   remains queryable while affinity mutation is unsupported, unavailable queries yield
   `-1`/`null`, and CPU zero is never fabricated.
9. Unsupported/absent providers and all settled recoverable failures neither dereference null nor
   retain buffers/thread-locals; fatal errors propagate.
10. Final-field/class-initialization/thread-confinement arguments match the implementation; no
    unauthorized atomic, VarHandle, registry, or cross-thread mutable state exists.
11. Deterministic fakes prove original-mask restoration, complete exact/locality/unsupported
    behavior, facade call counts, mutation resistance, and cleanup without host placement.
12. Native implementation, executor lifecycle, resource/pressure/topology production, core
    production, training, and unrelated files remain outside the diff.
13. Focused P3-A tests, P0 API/native/mask gates, selected-module hardware verify, read-only core
    tests, `git diff --check`, scope checks, and final status pass or record exact environmental
    limits.

The P3-A conformance audit later classifies these criteria, parent criteria 1-6 and 13-16 where
applicable, and A01 exactly as `satisfied`, `deviated`, `unverified`, or `ambiguous`.

## Verification commands

Use the pinned `mise.toml` toolchain through `mise exec --`. The direct-goal commands avoid an
unnecessary native lifecycle for fast iteration; the final `verify` still exercises the P1 native
package gates. No command may select training.

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='ThreadToolsAffinityTest,LinuxAffinityTest,WindowsAffinityTest,OSXAffinityTest' \
  surefire:test

mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='ApiCompatibilityTest,NativeCompatibilityTest,MaskFormattingCompatibilityTest,PinnedThreadExecutorCompatibilityTest' \
  surefire:test

mise exec -- mvn -B -pl euhedral-hardware-utils -am verify
mise exec -- mvn -B -pl euhedral-core -am test
git diff --check
git diff --name-only 7d3abea7 -- euhedral-training
git diff --name-only 7d3abea7 -- euhedral-core/src/main
git status --short
```

The two scope diffs are empty. If the pinned native toolchain or a hosted platform runtime is
unavailable, report the exact command/tool limit. Deterministic Java fake coverage is never skipped
for lack of host affinity.

## Sizing and split gate

P3-A remains one bounded implementation child and is not split again.

- It owns one module and one responsibility: request-to-capability/lease/managed-owner behavior.
- The public surface is one enum/query; all other new roles are unexported and feed one controller.
- The three facades share one frozen validation/apply matrix and do not introduce independent
  native implementations. Their package tests are small adapters around the same contract.
- Exact masks, thread-local leases, and current-CPU ownership are coupled: splitting facade work
  from the controller would duplicate or temporarily violate all-or-nothing semantics.
- The P3-B boundary is one package-private binding operation and is independently implementable
  after P3-A merges. Executor state, registry, cleaner, hooks, and races remain excluded.

One implementation agent can retain the exact child context below. A further split would create a
cross-child temporary API for a single controller without reducing the high-reasoning invariants.

## Implementation model reassessment

P3-A touches one Java 17 module, the exported root facade, one unexported provider/controller
region, three exported platform Java facades, one new public enum, and four focused test classes.
It combines unsigned bounded mask mathematics, safe initialization, exact/locality capability
honesty, all-or-nothing platform calls, per-thread pending/applied restoration state, nested owner
tokens, recoverable native failures, and additive API/JNI compatibility.

The exact context envelope is substantially smaller than the parent P3 context, and no executor
registry, global lifecycle state, deadline, filesystem, serialization, resource math, or native
body is involved. However, the implementation still must keep capability, mask, restoration,
thread-local cleanup, three facade deferrals, and downstream nullability compatibility coherent.
A lower-effort pass could easily report raw setters as exact, lose bit 63, install a lease after
mutation, erase a prior lease on later failure, or leak owner state.

The parent-selected implementation model is therefore confirmed without downgrade:

- **Implementation: `gpt-5.6-sol`, reasoning effort `high`.**
- **Combined conformance/manual review: `gpt-5.6-sol`, reasoning effort `high`.**

`medium` or `low` is not justified. The blueprint removes architecture selection but not the
coupled failure and ownership reasoning. If this model is unavailable, stop for developer direction
or split again; do not silently substitute a lower-capability implementation pass.

## Exact implementation context envelope

The implementation reads only:

- `AGENTS.md`;
- the parent plan's P3 developer-review summary/final P3-A prompt and compact P0-P2 closeouts;
- this blueprint;
- the parent blueprint's public capability, affinity, ownership, memory, P3-A checklist, tests,
  acceptance, and P3-A context sections;
- P0's API/module/additive contract, mask fixture, A01 row, and audit summary;
- P2's logical-ID/mask/span summary;
- the hardware POM/module descriptor;
- `ThreadTools`, `internal.ThreadPinner`, exported `common.ThreadPinner`, the three Java affinity
  facades and only their Java native declarations;
- `SystemInfo` only at `getCpuCount`, `getCpuSet`, and `getCpuInfo`;
- the P0 API/native/mask and fresh-thread compatibility tests; and
- only the named non-training `ThreadTools.getCpu/getCpuInfo` call snippets for compile
  compatibility.

It does not read executor implementation, native implementation bodies, topology adapters,
resource/monitor/pressure code, unrelated core bodies, CI, Reactor, Spring, or training. Owned
outputs are exactly the production/test files in this blueprint, its completion record, and the
temporary P3 status update authorized by the implementation prompt.

## Handoff condition

Hand off for developer review and merge into the P3 root only when:

- every capability, mask, platform-call, restoration, release, ownership, current-CPU,
  failure/JMM, and fake-seam rule above is settled;
- the sizing gate still yields one P3-A child;
- the plan records the confirmed `gpt-5.6-sol`/`high` implementation selection and review summary;
- only this child blueprint and authorized plan text differ from `7d3abea7`;
- `git diff --check` and documentation scope checks pass; and
- no production implementation, P3-B branch, commit, merge, or push has started.

After review, merge this blueprint branch before creating
`hardware-utils-overhaul/phase-3-affinity-capability-implementation`. Do not start P3-B until the
P3-A implementation and combined conformance/manual-review audit have both merged into the P3 root.

## Implementation completion record

Implementation completed on
`hardware-utils-overhaul/phase-3-affinity-capability-implementation`, based on the reviewed P3 root
at `b5333c8e`.

### Changed surface

- Added the exact public `AffinityCapability` enum and `ThreadTools.getAffinityCapability()` query.
- Replaced destructive base-mask probing with one bounded `AffinityController`, immutable copied
  requests/snapshots, stable operational capability selection, first-original exact restoration,
  whole-request locality handling, tag-zero release, managed logical-owner tokens, and truthful
  current-CPU fallback.
- Kept Linux and Windows common capability unsupported; macOS supplies only the frozen single-
  ordinal locality contract. Direct facade helpers validate complete requests before one raw call.
- Added deterministic controller and facade tests. No JNI declaration/body, module directive,
  executor, resource/pressure, topology production, core production, CI, benchmark, or training
  file changed.

### Commands and results

- Pinned `mise` could not run because `mise` is not installed. The documented fallback found
  OpenJDK 17.0.19 and Maven 3.6.3; the hardware module's Java 17 sources/tests compile under that
  fallback, but it is not the pinned Java 21/Maven 3.9.16 toolchain.
- The direct P3-A command passed: 10 tests, zero failures. This includes
  `ThreadToolsAffinityTest#discoversAndRestoresTheOriginalMask` (A01), exact/locality/unsupported
  behavior, rejected-mask zero-call checks, bit-63 preservation, restoration/release cleanup,
  managed ownership, current-CPU fallback, and the three facade seams.
- The P0 native, mask-format, and fresh-thread tests passed. The API comparison reported zero
  removals. Its P3-A additions are exactly the enum, its compiler members/constants, and the
  capability query; no unintended facade hook remains. The test itself remains red under the
  fallback because the historic baseline rejects all additions, includes inherited P2 additions,
  and OpenJDK 17 stamps three module-requires versions that the baseline records as absent.
- `mvn -B -pl euhedral-hardware-utils -am verify` and
  `mvn -B -pl euhedral-core -am test` both stopped in the hardware Zig lifecycle before native or
  core tests because the `ZIG` executable parameter is missing/invalid. The latter completed the
  upstream data-structures suite (8 tests) before that stop.
- `git diff --check` passed. Diffs from `b5333c8e` under `euhedral-training`,
  `euhedral-core/src/main`, `module-info.java`, and `src/main/native` are empty.

### Acceptance evidence and limits

A01 is directly evidenced. Invalid, empty, high, sparse-hole, unsupported, cross-group, and
multi-locality requests are rejected before platform mutation. Exact success requires a captured
original and complete apply; locality success follows one fully resolved hint. Exact/locality
leases are removed after release success, false status, or recoverable failure, and managed owners
remove their outermost thread-local value. Unsupported provider selection is stable and never
dereferenced.

Pinned API/module verification, native-backed hardware verification, and read-only core tests
remain environmentally unverified for the exact reasons above; no source or build configuration
was changed to accommodate the fallback environment.

## Conformance audit completion record

The P3-A conformance/manual-review audit was completed on
`hardware-utils-overhaul/phase-3-affinity-capability-audit`, based on the updated P3 root at
`0faaee70`. Its full classification is in
`docs/audits/hardware-utils/phase-3-affinity-capability-conformance.md`.

- Developer review exposed and authorized correction of a blueprint defect that coupled truthful
  current-CPU querying to exact affinity mutation/restoration. The corrected contract makes those
  independent: Linux remains mutation-`UNSUPPORTED` but reports its validated native logical CPU;
  Windows and macOS remain unavailable until P6/P7.
- The correction also centralized every public affinity overload in the owned controller, made
  pending-lease cleanup survive propagated fatal apply errors, expanded deterministic boundary/
  mutation/failure/facade coverage, and repaired two stale core test fixtures for P2 non-null
  immutable inputs. No core production, native body/declaration, module descriptor, executor, or
  training file changed.
- Under `mise` Java 21.0.2/Maven 3.9.16, the focused P3-A suite passed (14 tests), the P0
  API/native/
  mask/fresh-thread gates passed (4 tests), and cache-disabled hardware verification passed,
  including Zig/native packaging/load checks and 63 unit tests.
- The complete cache-disabled `euhedral-core -am test` gate passed: 63 hardware tests and 99 core
  tests, including the unmanaged Linux `ThreadTools.getCpu/getCpuInfo` callers and the corrected
  topology/snapshot fixtures.
- Skipped: host-affinity placement testing, by the blueprint's deterministic-fake rule. Limits:
  `mise` warns about unrelated unavailable user-level tool entries; those warnings did not affect
  Java 21/Maven 3.9.16 or the completed hardware verification.

The corrected audit is review-ready for merge. P3-B remains prohibited until that merge.
