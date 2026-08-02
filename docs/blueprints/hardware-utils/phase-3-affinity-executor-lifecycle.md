# Phase 3 Affinity Capability and Executor Lifecycle

## Status and authority

- Parent plan: `docs/plans/hardware-utils-platform-parity-overhaul.md`
- Inherited completed P2 root: `hardware-utils-overhaul/phase-2-topology-snapshot` at
  `e2495c5d`
- P3 root branch: `hardware-utils-overhaul/phase-3-affinity-executor`
- Parent blueprint branch: `hardware-utils-overhaul/phase-3-affinity-executor-blueprint`
- Owning module: `euhedral-hardware-utils`
- Blueprint model: `gpt-5.6-sol`
- Blueprint reasoning effort: `max`
- Status: implementation-ready parent contract; developer review and merge into the P3 root are
  required before either responsibility child starts

This blueprint is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, the parent plan, and the
completed P0-P2 evidence named by the phase artifact index. It settles the shared P3 contract and
splits implementation into two sequential responsibility children. There is no P3 root
implementation or validation action.

The inherited P2 documentation still records audit-tooling and race-evidence limits, while the
branch tip is the developer-designated completed P2 root. P3 does not reclassify or reconstruct
that history. It consumes only P2's stable logical-ID, mask-index, bounded-span, and immutable
topology contracts.

If a child needs a different public capability, mask meaning, managed-owner rule, affinity lease,
lifecycle state, task-acceptance linearization point, registry identity rule, cleaner/hook owner,
or memory mode, it must stop and return to this parent blueprint. Compile convenience is not
authority to change one of these contracts.

## Objective

P3 makes common affinity behavior truthful and makes the pinned executor a linearizable,
fresh-thread executor with deterministic cleanup. Completion must:

1. add one public affinity capability type and query without changing an existing descriptor or
   module export;
2. distinguish exact placement, one locality hint, and unsupported behavior at every common
   affinity entry point;
3. preserve a stable Euhedral logical owner for managed worker tasks without inventing an
   unmanaged physical CPU;
4. replace destructive base-mask probing with bounded, non-destructive discovery and per-thread
   restoration leases;
5. reject empty or unrepresentable masks before a platform call and never report partial mask
   coverage as success;
6. preserve legacy macOS boolean success when exactly one representable locality hint is applied,
   while reporting `LOCALITY_HINT`, never `EXACT`;
7. preserve one newly created thread per accepted `execute`, including concurrent tasks;
8. linearize singleton acquisition, restart, execute, shutdown, close, task exit, cleaner, hook,
   and registry removal;
9. make shutdown, rejection, interruption, termination, and deadlines truthful at every public
   observation; and
10. bound and clean every global registry, thread-local lease, task reference, cleaner action, and
    shutdown hook.

P3 installs common Java contracts and deterministic seams. P5-P7 own detailed Linux, Windows, and
macOS native implementations and real-platform parity. A platform facade may report only the
capability its P3 common path can currently perform safely; a later platform phase may raise that
runtime result without changing this API or its semantics.

## Scope

### Shared owned surface

The two children together own only:

- the hardware root affinity facade and executor lifecycle;
- the unexported affinity/lifecycle support beneath
  `io.euhedral_execution.hardware_utils.internal`;
- existing Linux, Windows, and macOS affinity Java facades at the common contract boundary;
- hardware affinity, ownership, executor, race, and cleanup tests;
- the child blueprints and completion/conformance records;
- this blueprint and P3 planning/closeout text; and
- the workflow-required temporary P3 status block during later implementation and audit actions.

The exact public addition is:

```text
euhedral-hardware-utils/src/main/java/
  io/euhedral_execution/hardware_utils/AffinityCapability.java
```

`module-info.java` remains unchanged because the root package is already exported. All existing
public/protected types, methods, constructors, fields, descriptors, constants, and module
directives remain P0-compatible.

Exact contract-bearing production files are:

```text
euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/
  AffinityCapability.java                         (new, P3-A)
  ThreadTools.java                                (P3-A)
  PinnedThreadExecutor.java                       (P3-B)
  internal/ThreadPinner.java                      (P3-A)
  linux/LinuxAffinity.java                        (P3-A)
  windows/WindowsAffinity.java                    (P3-A)
  osx/OSXAffinity.java                            (P3-A)
```

The module descriptor and exported `common/ThreadPinner.java` are exact read-only compatibility
files. P3-B may add bounded unexported registry/cleanup support; P3-A may add bounded unexported
controller/value support. Their minor file decomposition is intentionally not prescribed.

Exact stable test files are:

```text
euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/
  ThreadToolsAffinityTest.java                    (new, P3-A)
  PinnedThreadExecutorLifecycleTest.java          (new, P3-B)
  PinnedThreadExecutorTest.java                   (existing, P3-B)
  compatibility/PinnedThreadExecutorCompatibilityTest.java (existing, P3-B)
```

### Child ownership

P3-A owns:

- `AffinityCapability`;
- `ThreadTools` affinity/managed-owner behavior;
- `internal.ThreadPinner` and the bounded internal affinity controller/value roles;
- `LinuxAffinity`, `WindowsAffinity`, and `OSXAffinity` Java facade conformance;
- affinity capability, masks, original-state restoration, release, unsupported-platform, and
  managed-owner tests.

P3-B owns:

- `PinnedThreadExecutor` and its bounded internal registry/cleanup support;
- fresh-thread task wrapping and its use of the P3-A managed-owner/affinity lease;
- singleton, execute/start/shutdown/close, interruption, termination, cleaner, hook, `closeAll`,
  and contamination tests.

P3-B consumes P3-A's package-private managed-task binding operation. It does not reopen affinity
capability or mask semantics.

### Read-only inputs

- P0's exact additive-API, mask, fresh-thread concurrency, A01, and A02 contracts.
- P1's generated-JNI/loader boundary and surviving closeout evidence.
- P2's stable logical CPU IDs, Windows `group * 64 + processor` mapping, macOS ordinals,
  `CPU_COUNT` index span, active CPU mask, and allocation bounds.
- Existing native affinity declarations and implementations, for signature and deferral review
  only.
- The exported legacy `io.euhedral_execution.hardware_utils.common.ThreadPinner` type. Its class,
  constructors, abstract methods, and descriptors remain unchanged; P3 does not add a new abstract
  capability method that would break external subclasses. The operational platform boundary is
  the unexported `internal.ThreadPinner`.
- The parent plan's named non-training worker uses in `ControlPlaneFragment`,
  `BaseCloneableObject`, `ControlPlaneLattice`, the executor compatibility tests, and benchmarks.

Core and benchmark production are compatibility consumers only. Their current use of
`getOrSetIfAbsent`, `get`, `start`, `execute`, `submit`, `shutdownNow`, `closeAll`, `getCpu`, and
thread properties constrains P3 behavior but does not enter P3 edit scope.

### Prohibited work

- Serializing tasks, adding a queue, pooling/reusing task threads, or converting the executor to a
  single-worker executor.
- Claiming hard macOS affinity or fabricating a physical/current CPU outside managed ownership.
- Detailed native affinity, processor-group, sysfs/proc, Mach topology, or platform runtime work
  assigned to P5-P7.
- Resource providers, timer policy, `ResourceMonitor`, pressure, cadence, listeners, snapshots,
  topology production, or monitor/platform resources.
- Core production, routing, draining, frame behavior, Reactor, Spring, benchmarks, root POM,
  native build/package/loader, CI, or unrelated cleanup.
- Any inspection, edit, build, test, documentation, or command under `euhedral-training`, or a
  reactor command that selects it.

## Package, naming, and data flow

### Public capability

Add the public enum in the already-exported root package:

```java
public enum AffinityCapability {
    EXACT,
    LOCALITY_HINT,
    UNSUPPORTED
}
```

Add this static method to `ThreadTools`:

```java
public static AffinityCapability getAffinityCapability()
```

The result describes the operational common `ThreadTools` path on this runtime, not an OS
marketing claim. It is non-null and stable for the initialized platform facade. Existing boolean
methods remain the compatibility result for one request; the enum explains what a successful
request means.

Do not add the constants to `OSName`, a topology enum, or another unrelated type. Do not export a
new package.

### Internal roles

The exact local file decomposition is left to each child, but these roles and names are fixed:

- `ThreadPinner`: the sealed platform boundary. It exposes current capability, current CPU when
  truthful, bounded original-binding capture, all-or-nothing apply, exact restore, locality-hint
  release, and logical-CPU-to-locality mapping.
- `AffinityController`: the package-internal, instance-testable implementation behind static
  `ThreadTools`. It validates/canonicalizes requests, owns per-thread leases and managed logical
  ownership, and never loads topology through an unbounded or mutable path.
- `AffinityRequest`: an immutable canonical mask plus its highest logical ID and nonzero word/
  locality facts.
- `AffinitySnapshot`: an immutable provider-owned original exact binding. It is absent when exact
  capture is unavailable; absence is not an empty mask.
- `ManagedCpuBinding`: a package-private `AutoCloseable` token that installs one logical owner and
  restores/removes the prior thread-local state exactly once.

These roles remain unexported. Children may combine small value roles in one internal file, but
must not leak an internal type through an exported signature.

### Affinity data flow

```text
public int[] / BitSet / long[] request
                  |
                  v
          copy + canonicalize
          validate full request
                  |
                  v
        AffinityCapability dispatch
          /            |             \
         v             v              v
      EXACT       LOCALITY_HINT    UNSUPPORTED
  capture current  resolve exactly    reject
  exact binding    one locality       without call
         |             |
         +------> all-or-nothing apply
                         |
                         v
                  per-thread lease
                         |
             release/worker finally
                         |
               exact restore or tag 0
```

### Managed task data flow

```text
execute(command)
  -> create one NEW thread outside lifecycle lock
  -> under lifecycle lock: verify RUNNING, register identity, start
  -> thread installs managed logical CPU token
  -> thread attempts one safe affinity lease
  -> command runs once
  -> finally: release lease -> close owner token -> remove task -> signal termination
```

Managed ownership is an Euhedral lane identity, not proof of physical placement. A truthful
platform current-CPU result remains preferred independently of affinity mutation/restoration
capability. Otherwise `ThreadTools.getCpu()` returns the managed logical owner while the token is
active; outside managed ownership it returns `-1` when the platform cannot truthfully map the
current CPU.
`getCpuInfo()` returns the matching `CpuInfo` for a nonnegative known result and otherwise returns
`null`; P3-A adds the JSpecify `@Nullable` return annotation without changing its JVM descriptor.
No fallback invents CPU zero.

## Affinity capability and request contract

### Capability meanings

- `EXACT`: a successful common request applies the complete requested logical CPU set as an exact
  operating-system binding. The provider can capture and restore the current thread's original
  exact binding. If either full apply or safe capture/restore is unavailable, the common path must
  not report `EXACT`.
- `LOCALITY_HINT`: a successful common request applies exactly one locality hint shared by every
  requested logical CPU. It does not guarantee placement on any requested CPU. macOS reports this
  value and never `EXACT`.
- `UNSUPPORTED`: the common path cannot safely honor a request. Every set operation returns false
  without invoking a raw platform setter and release is a no-op. It does not suppress an
  independently truthful current-CPU query; otherwise current CPU is `-1` outside managed
  ownership.

Linux and Windows facades must not report `EXACT` in P3 merely because a legacy raw setter exists.
They may do so only when the common capture/apply/restore contract is operational. P5 and P6 own
the native work that makes those complete platform paths exact. P3 preserves every legacy facade
descriptor but routes `ThreadTools` through the honest operational capability.

The macOS facade may report `LOCALITY_HINT` in P3 only when it can validate one representable hint,
apply it once, and release with Mach affinity tag `0` through the existing facade boundary. P7 owns
final locality grouping/native hardening. If that common path is unavailable, it reports
`UNSUPPORTED`, not an optimistic hint.

The existing public `setAffinity(long[])` method on each platform facade remains bytecode-
compatible and obeys the same pre-call matrix: Linux may report true only for one complete atomic
exact apply; Windows may report true for a cross-group request only when one available API covers
every nonzero group; macOS may report true only for one proved locality hint. These direct facade
methods do not claim safe restoration. The stronger operational `ThreadTools` capability is
`EXACT` only when capture, apply, and restore are all available together.

### Mathematical mask precision

For a `long[] masks`, word `i` is little-endian and bit `b` represents logical CPU
`64 * i + b`, including bit 63. Signed `long` comparison is never used to enumerate or validate
bits. Canonicalization:

1. rejects null without a platform call and returns false through existing boolean entry points;
2. clones the input before inspection;
3. removes trailing zero words from the owned representation;
4. treats a zero-length or all-zero value as the empty request;
5. rejects a nonzero bit at or above P2's logical index span or outside the immutable supported
   topology mask; and
6. rejects a word count above `ceil(CPU_COUNT / 64)` before allocating a second proportional
   buffer.

`int[]` conversion rejects a null array, a negative ID, or an ID outside the same span. Duplicate
IDs collapse to one requested bit and are not a failure. `BitSet` input is cloned before
conversion. No overload intersects away an invalid or unsupported requested bit and then reports
success.

The maximum accepted canonical mask is 1,048,576 bits, 16,384 words, or 131,072 bytes, inherited
from P2. Size arithmetic uses `long` before a checked conversion to `int`. There is no floating
point, rounding, deadline, wall-clock, filesystem, or serialization precision in P3-A.

### Deterministic mask matrix

| Request                             | `EXACT`                                                                                                   | `LOCALITY_HINT`                                                                        | `UNSUPPORTED`  |
|-------------------------------------|-----------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|----------------|
| empty/all zero                      | false; no capture/apply                                                                                   | false; no hint call                                                                    | false; no call |
| one supported CPU                   | true only after full exact apply                                                                          | true only after its one hint applies                                                   | false          |
| same Windows group, multiple CPUs   | true only after the complete word applies atomically                                                      | true only if all map to one locality                                                   | false          |
| cross Windows groups                | true only when one atomic multi-group operation covers all nonzero words; otherwise false before mutation | true only if all CPUs map to one locality; group boundaries alone do not imply success | false          |
| multiple CPUs in one macOS locality | not applicable on macOS                                                                                   | true after exactly one representable hint applies                                      | false          |
| multiple macOS localities           | not applicable on macOS                                                                                   | false before mutation; never apply the first locality only                             | false          |

`not applicable` above is reasoned: macOS is never an `EXACT` provider. Multi-locality semantics
still apply and are explicitly false. A raw Windows fallback that changes one group may not return
true for a cross-group request. A macOS raw setter that selects the first set bit may not return
true until the common layer proves all set bits share that hint.

### Original binding, leases, and release

Static initialization must never discover `BASE_MASK` by setting affinity one CPU at a time.
Instead it performs at most one non-mutating provider capture:

- a successful exact capture is cloned into `BASE_MASK`;
- otherwise `BASE_MASK` is a defensive immutable copy of the P2 active topology mask for
  compatibility/diagnostics only; and
- a fallback `BASE_MASK` is never treated as a captured original binding or used to expand a
  thread's affinity.

Before the first successful exact set on a calling thread, capture that thread's exact original
binding. Store the owned snapshot in one `ThreadLocal` lease. A failed capture prevents the set and
leaves no lease. Repeated successful sets before release retain the first original snapshot.

For locality hints, the lease records only that one hint was successfully applied; release applies
tag `0`. It never calls that release after a rejected/unapplied request. Unsupported requests
create no lease.

Existing `void ThreadTools.releaseAffinity()` performs at most one release for the calling thread:

- exact lease -> attempt restoration of the exact captured snapshot;
- locality lease -> attempt tag-zero release;
- no lease -> no-op.

It removes the thread-local lease in a `finally` block even if restoration fails or throws, logs a
bounded diagnostic outside hot loops, and never substitutes `BASE_MASK` for a missing per-thread
snapshot. The void descriptor is unchanged. Executor cleanup must continue even after a release
failure.

The `ManagedCpuBinding` is independently nested-safe: closing restores the prior managed ID or
calls `ThreadLocal.remove()` when none existed. Tokens are owner-thread-only and idempotent; a
wrong-thread or out-of-LIFO-order close throws `IllegalStateException` without modifying either
thread. Closing the current top token twice is an idempotent no-op. Executor use is one unnested
token per fresh task thread.

## Executor lifecycle architecture

### Public compatibility

Retain every existing `PinnedThreadExecutor` public constructor/method descriptor, including the
narrowed `awaitTermination` declaration that does not expose a checked `InterruptedException`.
Retain name clamping, priority clamping, daemon configuration, CPU getter, `ThreadFactory` getter,
`AutoCloseable`, and all `ExecutorService` entry points inherited through
`AbstractExecutorService`.

The public `pinnedFactory` getter remains a construction compatibility surface. Calling
`newThread` on it directly returns one configured NEW affinity/ownership-wrapped thread but does
not submit, register, or start an executor task. Lifecycle and termination claims cover commands
accepted through `execute`/inherited `submit`; a caller that directly starts a factory thread owns
that thread's start/join. Its wrapper still releases affinity/ownership and tolerates the absence
of an executor task entry.

Every accepted `execute` creates and starts one distinct `Thread`; there is no work queue. Two
blocking tasks submitted to one executor must enter concurrently on different thread identities.
`shutdownNow()` therefore returns an immutable empty list: there are no accepted-but-unstarted
queued commands once it owns the lifecycle lock.

### Executable state machine

One executor identity has these states:

```text
                 start
          +------------------+
          |                  v
      SHUTDOWN <--------- RUNNING
          |   shutdown         |
          |                    | close / cleaner / hook
          | close              v
          +----------------> CLOSED
```

`RUNNING` accepts tasks. `SHUTDOWN` is restartable, rejects tasks, and lets already accepted tasks
finish. `CLOSED` is permanent for that executor identity, rejects tasks, and interrupts/unparks
active tasks. `shutdownNow` transitions `RUNNING -> SHUTDOWN` and interrupts/unparks; it does not
make the identity permanently closed. Repeating any shutdown/close operation is idempotent.

Task membership is orthogonal:

```text
registered + started -> running/exiting -> removed
```

At any instant:

```text
isShutdown   := state != RUNNING
isTerminated := state != RUNNING && activeTasks == 0
```

Termination is an observation, not a permanent fourth state, because the compatibility `start`
method may restart `SHUTDOWN`. `CLOSED` can never restart. A `start` linearized after `shutdown`
wins; a `shutdown` linearized after `start` wins; `close` wins permanently for that identity.

### State transitions and failure behavior

All per-executor state, current immutable thread configuration, epoch, and an identity-keyed active
task map are guarded by one private lifecycle monitor/lock. A termination condition is signaled
after every task removal and every state transition.

- Construction validates CPU range against the P2 span, non-null thread creator/name, and stores
  a clamped priority. The initial state is `RUNNING`, epoch 1.
- `start(name, priority, daemon)` validates arguments before the lock. Under the lock, `RUNNING`
  is an idempotent no-op that does not change configuration; `SHUTDOWN` publishes one new immutable
  configuration, increments the checked epoch, and becomes `RUNNING`; `CLOSED` throws
  `IllegalStateException`.
- `execute(command)` rejects null with `NullPointerException`. It obtains one configuration
  snapshot and asks the creator for a thread outside the lifecycle lock. A null thread or a thread
  whose state is not `NEW` becomes `RejectedExecutionException`; the command is never run.
- Under the lifecycle lock, `execute` rechecks `RUNNING` and the same epoch, rejects otherwise,
  registers the thread by identity, and invokes `Thread.start()` before releasing the lock. If
  `start()` throws, it removes the task, signals waiters, and propagates the failure without
  retaining the command/thread.
- `shutdown()` changes `RUNNING -> SHUTDOWN` under the lock and does not interrupt accepted work.
- `shutdownNow()` changes `RUNNING -> SHUTDOWN` when needed, snapshots every active thread under
  the lock, then interrupts and unparks each outside the lock. A concurrent exit is harmless.
- `close()` changes any nonclosed state to `CLOSED`, snapshots active threads, performs the same
  best-effort interrupt/unpark, triggers explicit cleanup, and returns without an unbounded join.
- A task command runs once. An unchecked failure propagates unchanged from the wrapper to that
  thread's configured uncaught-exception handler only after nested `finally` blocks attempt
  affinity release, owner-token close, and task removal. A recoverable release/owner cleanup
  `RuntimeException` or `LinkageError` is logged and must not replace an already-running command
  failure or prevent task removal. `VirtualMachineError` and `ThreadDeath` are not normalized into
  an affinity failure, although the outer task-removal `finally` still runs. No command failure
  shuts down the executor.
- Wrapper cleanup removes exactly the current thread identity under the lock and signals waiters.
  It cannot remove another task with an equal name or reused numeric thread ID.

Creating a candidate thread outside the lock keeps arbitrary user `threadCreator` code out of the
lifecycle critical section. Starting it inside the lock is required: shutdown cannot miss an
accepted unstarted thread. A rejected race may create one never-started thread object, but it is
not stored after `execute` returns.

### Singleton registry and acquisition

The registry is keyed by the requested `long cpu`, validated to the supported `int` logical span.
Acquisition is linearized under one low-frequency registry monitor; it does not run on a worker
hot loop.

`getOrSetIfAbsent` behaves as follows:

1. a live `RUNNING` entry returns the same executor identity;
2. a live `SHUTDOWN` entry is restarted with the requested configuration and returned;
3. a `CLOSED` entry with active tasks rejects acquisition so two executor identities cannot own
   the same CPU concurrently;
4. a closed/stale entry with no active task is removed only by exact registry-entry identity, then
   one new executor is installed; and
5. concurrent absent acquisition creates and publishes exactly one winning executor/hook/cleaner
   registration. A candidate that is not installed must own no hook, cleanable, or map entry.

`get(cpu)` returns only a live `RUNNING` executor, otherwise null. It may perform identity-safe
stale cleanup but never removes a replacement installed for the same CPU.

Registry entries use a weak executor reference plus a cleanup control object that does not refer
back to the executor. Each running task wrapper holds its executor strongly until its `finally`
block, so cleaner action cannot interrupt a task merely because the submitting caller released
its reference.

### Cleaner, shutdown hook, and `closeAll`

Use one class-wide cleaner and at most one class-wide shutdown hook for the nonempty registry. Do
not register one permanent Runtime hook per executor.

- The cleanup action contains CPU key, exact registry-entry identity, lifecycle control, and hook
  registration identity; it contains no `PinnedThreadExecutor` reference, lambda capture of the
  executor, or command.
- Explicit `close` invokes the same idempotent cleanup action as the cleaner. The action marks the
  control closed and removes the exact entry immediately only when no active task remains. If a
  task remains, its final exit performs the same identity check/removal; the tombstone continues
  to prevent same-CPU overlap.
- Registry removal uses `remove(cpu, exactEntry)` or an equivalent monitor-protected identity
  comparison. An old cleaner can never remove a newer executor.
- The first installed entry registers the one hook. Removing the last exact entry removes that
  hook when the JVM is not already shutting down. Hook add/remove failures roll back acquisition
  or remain bounded shutdown diagnostics; no silently unhooked live registry is published.
- The hook calls `closeAll` through registry/control objects and owns no executor strongly beyond
  its bounded snapshot.

`closeAll` linearizes a registry-wide closing pass: acquisition cannot install/restart while the
snapshot is being marked closed. It closes every entry present at that linearization point, then
allows later acquisitions only after the pass completes and all same-CPU closed active entries
still enforce the no-overlap rule. It is idempotent and does not wait forever for an interrupt-
ignoring command. Empty-registry `closeAll` is a no-op.

The one hook is removed when the registry becomes empty during ordinary runtime. During JVM
shutdown `removeShutdownHook` may throw `IllegalStateException`; that case is expected and does
not register a replacement. This is the only justified runtime-hook cleanup exception.

### Termination, deadlines, and interruption

`awaitTermination(timeout, unit)` keeps its existing no-checked-exception declaration:

- null `unit` throws `NullPointerException`;
- a nonpositive converted timeout performs one immediate truthful termination check;
- if state is `RUNNING`, return false immediately;
- otherwise wait on the termination condition until active tasks reach zero, the executor is
  restarted, the budget expires, or the caller is interrupted;
- restart while waiting returns false because the executor is no longer shutdown;
- interruption restores the caller's interrupt flag and returns false; it is never swallowed;
  and
- spurious wakeups recheck the predicate and remaining budget.

`TimeUnit.toNanos` supplies saturating conversion. Let `budget = max(0, convertedNanos)` and
`elapsed = System.nanoTime() - start`; expiration is `elapsed >= budget`. Subtraction is the only
deadline arithmetic, so `now + timeout` cannot overflow. This is valid for durations below the
signed 64-bit `nanoTime` horizon (about 292 years), including a saturated `Long.MAX_VALUE` budget.
Condition waits use the returned remaining time or recompute from elapsed; no fixed polling sleep,
busy wait, or per-thread sequential join is used.

`shutdownNow` and `close` preserve the interrupt status already present on their caller because
they do not call interrupt-clearing operations. Each active worker receives at least one
best-effort `interrupt` and `unpark`; P3 does not claim it can terminate an interrupt-ignoring
command.

## Java Memory Model and happens-before contract

### Affinity/ownership

- Immutable capability/provider references are assigned during JVM class initialization; normal
  class-initialization happens-before publishes them to every caller.
- `AffinityRequest` and `AffinitySnapshot` clone mutable input before storing it and expose no
  mutable array. Final-field semantics publish their contents after ordinary safe publication.
- Affinity lease and managed logical owner are ordinary `ThreadLocal` values. They are confined to
  one thread; no volatile mode is needed. Token close uses plain access plus owner-thread checking
  and always removes/restores in `finally`.
- Starting a task thread happens-before its actions. Task completion and `Thread` termination have
  the JDK-defined happens-before relationship to a successful join, but P3 termination methods do
  not rely on joins for registry correctness.

### Executor/registry

- The registry monitor publishes the winning executor entry, weak reference, cleanup control, and
  hook identity. Monitor unlock happens-before a later lock that returns or removes the entry.
- The lifecycle monitor publishes state, epoch, immutable configuration, and task membership.
  Monitor unlock in execute/start/shutdown happens-before later locked observations and condition
  wakeup rechecks.
- `Thread.start()` occurs after task registration while holding the lifecycle monitor. The started
  thread sees the final task wrapper/configuration through the JDK start happens-before edge.
- Task removal and condition signal occur under the lifecycle monitor. A later locked
  `isTerminated`/await predicate sees command completion and cleanup membership.
- Cleaner/hook idempotence uses one `AtomicBoolean.compareAndSet(false, true)` in the cleanup
  control. Successful CAS has volatile read/write semantics and is the single transition that
  owns cleanup side effects. Weaker opaque access is not sufficient because registry/hook removal
  must observe the initialized cleanup fields.
- Hook-registration identity and registry `closeAll` gating remain monitor-protected. Do not mix a
  separate volatile hook count or state with an unlocked map snapshot.

No other VarHandle is required. In particular, replacing the coherent lifecycle monitor with
several independent booleans/counters is prohibited: it would reopen execute/shutdown and
termination linearization. If implementation introduces another atomic or VarHandle, its exact
happens-before role and access mode must be appended to the owning child blueprint before code is
accepted.

## Memory pollution, contamination, and cleanup

### Affinity child

- Request masks are bounded before proportional allocation and copied exactly at trust
  boundaries. No platform facade retains a caller array.
- `ThreadLocal` lease and managed-owner values are removed/restored in `finally` after success,
  command failure, rejection after thread creation, affinity failure, and interruption.
- Failed/rejected affinity requests retain neither snapshot nor request.
- `BASE_MASK` is one bounded immutable value; no per-CPU probing threads, arrays per CPU, or
  history list is retained.
- Native/file/native-buffer contamination is not applicable to P3-A because native
  implementation, resource collection, and filesystem work are prohibited. The child must state
  this reason rather than omit the category.

### Executor child

- The active-task map contains only started/not-yet-exited threads and does not retain completed
  commands.
- A rejected candidate thread is unstarted, unregistered, and unreferenced by executor state.
- Registry weak references, cleanup actions, hooks, and task wrappers contain no strong cycle that
  keeps an idle executor alive.
- Exactly one hook exists while the registry is nonempty during ordinary runtime; it is removed
  with the last entry. Repeated restart does not add hooks or cleaners.
- Closed entries remain only while needed to prevent same-CPU overlap with active old tasks; final
  task exit performs identity-safe removal.
- `closeAll` snapshots are bounded by registry size and discarded after the pass.
- Array/mask deadline precision and filesystem/native contamination are not applicable to P3-B;
  it owns no such data. Thread/task/global-reference contamination is fully applicable and tested.

## Deterministic tests and stress bounds

Tests use package-private controller, registry, cleanup-registrar, and hook-registrar seams with
fakes. They do not depend on the host OS, physical placement, `System.gc()`, arbitrary sleeps, or
real JVM shutdown. Every blocking assertion has a five-second diagnostic timeout; the bounded
stress test has a 30-second outer timeout.

### P3-A stable tests

The ledger-stable test remains:

```text
io.euhedral_execution.hardware_utils.ThreadToolsAffinityTest
  #discoversAndRestoresTheOriginalMask
```

The affinity suite must also prove:

1. exact capability capture is non-mutating and a set/release sequence restores the caller's
   distinct sparse original mask, including bit 63;
2. absent capture downgrades the common operational capability and never applies a guessed base
   mask;
3. null, empty, oversize, out-of-span, and unsupported masks make zero raw calls;
4. the full empty/one/same-group/cross-group/same-locality/multi-locality matrix above;
5. a cross-group raw partial result and a multi-locality first-hint opportunity both report false
   without mutation;
6. representable macOS hint success returns true while capability remains `LOCALITY_HINT`, and
   release applies tag zero once;
7. nested managed bindings restore the prior logical owner, wrong-thread close fails, and all
   thread-local values are absent after normal/failing paths;
8. an independent truthful provider CPU is preferred, unavailable current CPU is `-1`/null, and
   managed logical ownership is stable; and
9. mutation of every caller array/bitset and fake-provider buffer after the call cannot change a
   request, snapshot, or `BASE_MASK`.

### P3-B stable tests

The ledger-stable test is:

```text
io.euhedral_execution.hardware_utils.PinnedThreadExecutorLifecycleTest
  #linearizesExecuteShutdownAndCleanup
```

Use latches/barriers or explicit test seams to force these schedules:

| ID  | Forced schedule and assertion                                                                                                                            |
|-----|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| E1  | 32 acquisition callers meet at one barrier; all receive the same identity and one registry/hook/cleaner registration.                                    |
| E2  | creator pauses after returning a NEW thread; shutdown wins before registration; execute rejects and the candidate never starts or remains stored.        |
| E3  | execute registers/starts while holding the lifecycle lock; shutdown wins next and cannot miss that accepted task.                                        |
| E4  | shutdown then start and start then shutdown are forced separately; the lock order determines the documented final state/configuration.                   |
| E5  | close races acquisition on the same CPU; no second identity starts until the old closed identity has no active task and is identity-removed.             |
| E6  | two commands enter before either release latch opens; thread identities differ and both carry the same managed logical CPU.                              |
| E7  | command throws and affinity release also fails; owner/task cleanup, waiter signal, and later executor use still succeed.                                 |
| E8  | shutdown is orderly; shutdownNow/close interrupt and unpark; an interrupt-ignoring task keeps termination false until its latch releases.                |
| E9  | await sees completion, zero/negative timeout, timeout expiration, spurious signal, restart, and caller interruption with the exact return/flag behavior. |
| E10 | an old explicit/cleaner cleanup action runs after a replacement exists; identity removal preserves the replacement.                                      |
| E11 | first/last registry entry adds/removes one fake hook; restart and repeated close/closeAll do not change the bounded count.                               |
| E12 | closeAll and acquisition are barrier-controlled; every snapshot entry is closed, later acquisition resumes, and no old/new same-CPU task overlaps.       |

The compatibility test
`PinnedThreadExecutorCompatibilityTest#submissionsUseConcurrentFreshThreads` remains green.

Run one bounded stress test for 50 rounds with eight acquisition/submitter threads per round and at
most eight live tasks. Alternate execute-vs-shutdown and close-vs-acquire barriers. After each
round assert zero active tasks, zero closed tombstones, an empty registry, zero fake hooks, and no
managed-owner/affinity lease on every test thread. This is race evidence, not a throughput claim.

Cleaner reachability is tested deterministically through an injected cleanup registrar that
captures and invokes the exact action. Do not make `System.gc()` or a ReferenceQueue timing loop an
acceptance gate. A structural assertion verifies that the cleanup action's fields do not include
the executor or command.

## Failure matrix

| Failure                                    | Required outcome                                                                                              |
|--------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| unsupported OS/pinner                      | capability `UNSUPPORTED`; no dereference, set call, or fabricated CPU                                         |
| null/empty/invalid mask                    | boolean false; no provider mutation or lease                                                                  |
| exact capture unavailable                  | common exact request rejected before set; no guessed restoration                                              |
| exact/locality apply fails                 | false; no success lease; original remains unchanged                                                           |
| restoration/release throws                 | lease removed, bounded diagnostic, executor cleanup continues                                                 |
| platform `RuntimeException`/`LinkageError` | capability/apply returns unsupported/false with bounded diagnostic; fatal VM/thread errors propagate          |
| thread creator throws                      | propagate original unchecked failure; no task/command registry entry                                          |
| creator returns null/non-NEW thread        | `RejectedExecutionException`; never start/store it                                                            |
| execute loses shutdown/start epoch race    | `RejectedExecutionException`; candidate discarded                                                             |
| `Thread.start` fails                       | remove identity, signal waiters, propagate; executor remains coherent                                         |
| task throws                                | task ends and cleans; executor remains in its prior lifecycle state                                           |
| close/shutdown repeats                     | state-idempotent; no duplicate hook/cleanup ownership claim; another best-effort worker interrupt is harmless |
| epoch/version overflow                     | `IllegalStateException` before state/configuration publication                                                |
| await caller interrupted                   | return false and restore interrupt flag                                                                       |
| task ignores interrupt                     | close returns; termination/await remain false until actual exit                                               |
| old cleaner runs after replacement         | exact identity removal leaves replacement untouched                                                           |
| hook registration fails                    | acquisition rolls back with no published executor/entry/cleanable                                             |

## Dependency order and implementation checklists

### P3-A - affinity capability and managed ownership

1. Add the enum/query and instance-testable controller without changing module exports.
2. Replace destructive static probing with one bounded non-mutating capture/fallback.
3. Implement canonical request ownership, full validation, and the capability matrix.
4. Implement first-original per-thread leases, exact restore, tag-zero release, and failure cleanup.
5. Implement managed logical-owner tokens and truthful current-CPU fallback.
6. Adapt the three Java facades without detailed P5-P7 native work or partial-success claims.
7. Add the deterministic fake-provider/mutation/matrix tests and P0 gates.

### P3-B - executor registry and lifecycle

1. Introduce the coherent lifecycle/configuration/task control and linearized registry.
2. Validate acquisition and thread creation; preserve fresh concurrent task identity.
3. Implement the exact RUNNING/SHUTDOWN/CLOSED transitions and task wrapper cleanup order.
4. Implement truthful shutdown/interruption/termination/deadline behavior.
5. Implement weak registry entries, noncapturing cleanup actions, one bounded hook, identity
   removal, and `closeAll` gating.
6. Add the E1-E12 schedules, bounded stress test, compatibility gate, and cleanup assertions.

## Sizing and split gate

The parent P3 implementation is rejected and split into P3-A and P3-B.

- The parent spans two independent responsibility owners: affinity capability/managed ownership
  and executor registry/lifecycle.
- P3-A has its own public API, platform boundary, mask mathematics, thread-local lease state, and
  platform-deferral tests. It can compile and be audited before executor repair.
- P3-B has its own singleton, per-identity lifecycle, task membership, deadlines, cleaner/hook,
  and high-contention race matrix. It consumes only P3-A's frozen package-private task binding.
- Each child can be implemented and validated independently. Combining them would require one
  agent to retain two state machines, three platform facades, public compatibility, and twelve
  executor schedules simultaneously.
- The order is mandatory: P3-A blueprint -> implementation -> combined conformance/manual review,
  then P3-B blueprint -> implementation -> combined conformance/manual review, then the P3 root
  audit. Each result merges into the P3 root before the next branch is created.

Do not create `hardware-utils-overhaul/phase-3-affinity-executor-implementation`. There is no P3
validation branch or validation artifact under the current workflow. Each child blueprint must
rerun this sizing gate against its refined inventory; it may split further or raise capability,
but may not silently recombine or downgrade.

At the parent gate, both child scopes are bounded but remain high-reasoning implementation units.
Their exact context envelopes below avoid rereading unbounded feature history.

## Bounded implementation context envelopes

### P3-A required inputs

- `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, and `docs/ARCHITECTURE.md`;
- the parent plan's settled affinity requirements, P3 summary/prompt, and P0-P2 artifact closeout
  summaries;
- this blueprint's public capability, affinity, ownership, memory, P3-A checklist, tests, and
  acceptance sections;
- P0 API/mask/A01 contracts and the P2 logical-ID/mask/span summary;
- hardware POM/module descriptor;
- `ThreadTools`, `internal.ThreadPinner`, the three Java affinity facades and native declarations,
  and existing affinity/executor compatibility tests; and
- only the named `ThreadTools.getCpu/getCpuInfo` non-training caller locations for compile
  compatibility.

P3-A does not read executor implementation beyond the exact package-private task-binding consumer
shape frozen here. It excludes resource/monitor/pressure internals, native implementation bodies,
core production bodies beyond named calls, CI, benchmarks, Reactor, Spring, and training.

P3-A owned outputs are its child blueprint/completion/conformance record, the enum, `ThreadTools`,
bounded internal affinity roles, Java facade changes, focused affinity tests, and temporary P3
status updates.

### P3-B required inputs

- `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, and `docs/ARCHITECTURE.md`;
- the parent plan's P3 summary/final prompts and this blueprint's executor state/JMM/cleanup/test/
  acceptance sections;
- P3-A's blueprint completion/conformance summary and only its final
  `ManagedCpuBinding`/affinity-lease task boundary;
- P0 fresh-thread/A02 contracts and P2 CPU-ID/span summary;
- hardware POM/module descriptor;
- `PinnedThreadExecutor`, its two existing hardware tests, and exact named worker/benchmark call
  sites for read-only compatibility; and
- Java `ExecutorService`, `Thread`, `Cleaner`, monitor/condition, weak-reference, and runtime-hook
  behavior as supplied by the pinned JDK 21 toolchain documentation/source when clarification is
  needed.

P3-B does not reread platform facades/native declarations, topology adapters, resource/pressure
internals, unrelated core bodies, Reactor, Spring, CI, or training.

P3-B owned outputs are its child blueprint/completion/conformance record,
`PinnedThreadExecutor`, bounded internal registry/cleanup support, focused lifecycle tests, and
temporary P3 status updates.

## Implementation model reassessment

### Parent context and coupling

The provisional unsplit implementation would span one Java 17 module, the exported root package,
one unexported affinity/lifecycle region, three exported platform facades, a new public enum, mask
precision, per-thread lease ownership, managed logical identity, a restartable executor state
machine, a singleton weak registry, cleaner/hook reachability, deadline arithmetic, and downstream
worker compatibility. It combines concurrency, memory semantics, topology-index precision,
recovery, and global cleanup across two independently testable responsibilities.

P3-A still holds public API compatibility, three platform facades, bounded unsigned masks,
original-binding ownership, thread-local nesting, and platform capability honesty together. P3-B
still holds three lifecycle states, task identity, execute/shutdown/start races, restartable
termination observations, singleton publication, cleaner reachability, one-hook ownership, and
`closeAll` together. Neither is a low-effort mechanical translation.

The current defects are direct evidence against a lower-capability pass: initialization mutates
affinity and guesses restoration, absent pinners can be dereferenced, acquisition is check-then-put,
cleanup holds the executor strongly, each construction leaks a hook, execute can start after
shutdown, task maps are cleared before actual exit, and await uses overflow-prone deadlines while
ignoring interruption. P2's missing deterministic concurrent-publication evidence is additional
evidence that source inspection alone cannot replace the required barrier tests.

### Capability decisions

- Selected root implementation: **none**. The former P3 root implementation prompt is superseded
  and prohibited.
- P3-A implementation: **`gpt-5.6-sol`, reasoning effort `high`**, subject to mandatory
  confirmation or increase by the P3-A child blueprint.
- P3-B implementation: **`gpt-5.6-sol`, reasoning effort `high`**, subject to mandatory
  confirmation or increase by the P3-B child blueprint.
- P3-A/P3-B combined conformance/manual reviews and the P3 root audit:
  **`gpt-5.6-sol`, reasoning effort `high`**.

`medium` or `low` is not justified for either implementation. The parent split reduces context
history; it does not remove child-local Java Memory Model, cleanup, or capability coupling. If the
selected model is unavailable, split the affected child further or stop for explicit developer
direction; do not silently downgrade.

## Verification commands and gates

Use the exact pinned tools from `mise.toml` through `mise exec --`. A documented restricted-
environment fallback must report substituted versions and limits. No command may select training.

Child blueprints finalize exact class lists. The required shape is:

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='ThreadToolsAffinityTest,*Affinity*Test' \
  surefire:test

mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='PinnedThreadExecutorLifecycleTest,PinnedThreadExecutorCompatibilityTest,PinnedThreadExecutorTest' \
  surefire:test

mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='ApiCompatibilityTest,MaskFormattingCompatibilityTest,PinnedThreadExecutorCompatibilityTest' \
  surefire:test

mise exec -- mvn -B -pl euhedral-hardware-utils -am verify
mise exec -- mvn -B -pl euhedral-core -am test
```

The final hardware verify rechecks P1 native/package gates but does not authorize detailed
platform changes. The core command is read-only compatibility evidence. Host-specific physical
affinity assertions are not substitutes for deterministic fakes; unavailable hosted platform
runtime portions remain explicit P5-P7 work.

Scope/hygiene checks:

```bash
git diff --check
git diff --name-only <child-parent> -- euhedral-training
git diff --name-only <child-parent> -- euhedral-core/src/main
git status --short
```

The two scope diffs are empty. Do not otherwise inspect training.

## Parent acceptance criteria

1. P0 reports no removed/changed Java API, module, mask, or fresh-thread compatibility contract;
   only the enum/query and explicitly reviewed additions are additive.
2. Capability results are stable, operationally truthful, and macOS never reports `EXACT`.
3. Every overload follows the exact mask precision/matrix and no rejected/unrepresentable request
   invokes a partial setter.
4. Representable macOS locality success retains legacy boolean true; multi-locality requests fail
   before mutation and release uses tag zero.
5. Base-mask discovery is non-destructive; exact sets capture and restore the calling thread's
   first original binding; no fallback mask expands affinity.
6. Managed logical ownership is stable only while its token is active, nested-safe, and absent
   after every exit; an independent truthful provider CPU is preferred and an unavailable
   unmanaged physical CPU remains `-1`/null.
7. Singleton acquisition returns one live identity per CPU and stale/cleaner removal is by exact
   entry identity.
8. Every accepted execution creates one distinct NEW thread, and concurrent commands remain
   concurrent without a work queue.
9. RUNNING/SHUTDOWN/CLOSED, restart, execute, shutdown, shutdownNow, close, and `closeAll` follow
   the exact linearization/state table under every E1-E12 schedule.
10. Rejection, command failure, affinity/release failure, interruption, thread-start failure, and
    interrupt-ignoring tasks leave coherent lifecycle/task/registry state.
11. `isShutdown`, `isTerminated`, and `awaitTermination` are truthful, overflow-safe, spurious-
    wakeup-safe, and preserve caller interruption.
12. Cleaner action has no referent/command cycle; one hook is registered for a nonempty registry,
    removed at ordinary empty transition, and never multiplied by restart.
13. Task, thread-local, map, tombstone, hook, cleanable, and command references meet every cleanup
    assertion after deterministic tests and bounded stress.
14. Every monitor/lock/atomic transition has the documented happens-before edge; no independent
    flag weakens coherent lifecycle publication.
15. Detailed P5-P7 native/platform work, resources/monitor/pressure, topology production, core
    production, task serialization, training, and unrelated changes remain outside the diff.
16. Both child suites, P0 gates, selected-module hardware verify, read-only core tests,
    `git diff --check`, scope checks, and final status pass or record exact environmental limits.

Each child audit classifies its owned criteria and A01 or A02 exactly as `satisfied`, `deviated`,
`unverified`, or `ambiguous`. The P3 root audit classifies all 16 criteria plus A01-A02 from the
merged child evidence. A material deviation returns to the owning child or this parent contract.

## Risks and unresolved decisions

- Exact placement and safe restoration are a coupled operational promise. Until P5/P6 provide
  complete native support, Linux/Windows may truthfully report less capability through the common
  path rather than mutate and guess restoration.
- P2 macOS topology is conservative, so P3 representability may be narrower than final P7
  locality grouping. It may be widened later without changing the one-hint contract.
- Restartable `start` means termination is a truthful instant observation, not a permanent state.
  Tests must force restart-during-await rather than assume conventional irreversible termination.
- User thread factories are arbitrary code. Candidate creation stays outside locks and every
  returned thread must be NEW; rejection may still allocate one discarded thread object.
- An interrupt-ignoring command can delay final registry removal. Closed tombstones deliberately
  prevent a second same-CPU executor until actual exit rather than claim false termination.
- Cleaner and Runtime hook tests need deterministic seams; GC and JVM shutdown timing are not
  acceptance evidence.
- Complete hardware verification depends on the P1 native toolchain. Missing cross-platform
  runners are explicit P5-P7 limits, but deterministic P3 Java races may not be skipped.

No lifecycle, ownership, memory-order, capability, mask, deadline, cleanup, or split decision
remains unresolved. Child blueprints refine implementation inventories and test mechanics only;
they do not reopen the contracts above.

## Handoff condition

Hand off this parent blueprint for developer review and merge into the P3 root only when:

- the plan's P3 artifact index, branch lineage, review summary, and prompt sequence name both
  child lifecycles and the root audit;
- the old P3 root implementation and validation dependencies are marked superseded/non-runnable;
- both child context envelopes and parent-selected implementation capabilities are recorded;
- implementation can proceed without selecting a capability, mask, ownership, state, deadline,
  memory mode, hook, cleaner, registry, or cleanup rule;
- only this blueprint and authorized plan/planning text differ from the P3 root;
- `git diff --check` and documentation scope checks pass; and
- no P3-A/P3-B or implementation branch has started before this parent blueprint merge.

Do not start implementation. After authorized review/merge, create the P3-A blueprint branch from
the updated P3 root and rerun its sizing/model gate.
