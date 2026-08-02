# Phase 3-B Pinned Executor Lifecycle

## Status and authority

- Parent plan: `docs/plans/hardware-utils-platform-parity-overhaul.md`
- Parent blueprint: `docs/blueprints/hardware-utils/phase-3-affinity-executor-lifecycle.md`
- P3 root and child branch point: `hardware-utils-overhaul/phase-3-affinity-executor` at
  `2027a47b`
- Blueprint branch: `hardware-utils-overhaul/phase-3-executor-lifecycle-blueprint`
- Owning module: `euhedral-hardware-utils` (Java 17 release target)
- Repository toolchain: the Java 21 and Maven 3.9.16 versions selected by `mise.toml`
- Blueprint model: `gpt-5.6-sol`
- Blueprint reasoning effort: `max`
- Status: implementation-ready child contract; review and merge into the P3 root are required
  before implementation

This child refines only the parent's frozen P3-B responsibility. The parent remains authoritative
for public compatibility, fresh concurrent execution, lifecycle states, registry overlap,
cleaner/hook ownership, deadlines, cleanup, and Java Memory Model requirements. The reviewed P3-A
blueprint, implementation completion record, and conformance audit are merged at the branch point.
They are final inputs, not work reopened by this child.

If implementation needs a different state, lock, acceptance point, restart rule, interrupt rule,
deadline calculation, registry identity rule, cleaner action, hook lifecycle, cleanup order, or
memory access mode, it stops and returns to this blueprint. Implementation may choose only minor
private naming and file decomposition within the roles below.

## Objective

Replace the current check-then-put registry and independently atomic lifecycle flags with one
linearizable, restartable, fresh-thread executor design that:

1. preserves every public descriptor and one distinct NEW thread per accepted `execute`;
2. installs the P3-A managed logical-CPU binding before user code without claiming physical
   placement;
3. makes execute, start, shutdown, close, task exit, and termination observations coherent;
4. publishes at most one live executor identity for each logical CPU and never overlaps a
   replacement with a closed identity's active tasks;
5. uses exact-identity registry removal, a noncapturing cleaner action, and one bounded JVM hook;
6. gives `closeAll` one registry-wide closing linearization without waiting indefinitely;
7. preserves caller interruption and computes termination budgets without deadline overflow; and
8. proves A02, E1-E12, cleanup, reachability, and bounded stress through deterministic seams.

This is lifecycle repair, not a conventional worker pool. It must not serialize commands, queue
work, reuse task threads, or move ownership into core or benchmark consumers.

## Scope and compatibility boundary

### Owned production surface

The implementation may change:

```text
euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/
  PinnedThreadExecutor.java
```

It may add a small number of package-private support types under the unexported
`io.euhedral_execution.hardware_utils.internal` package for lifecycle control, registry entries,
cleanup registration, or hook registration. Keeping those roles as private nested types is also
allowed. No internal type may appear in a public or protected signature.

The hardware POM and `module-info.java` are read-only. No dependency, export, `requires`, `opens`,
`uses`, or `provides` change is required.

### Owned tests

Add the stable test class:

```text
euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/
  PinnedThreadExecutorLifecycleTest.java
```

The implementation may update only assertions or teardown in these existing tests when necessary
to express the already-frozen behavior:

```text
PinnedThreadExecutorTest.java
compatibility/PinnedThreadExecutorCompatibilityTest.java
```

Do not weaken the five-second bounded waits or the P0 concurrent-fresh-thread assertion.

### Read-only compatibility inputs

- P0 A02 and
  `PinnedThreadExecutorCompatibilityTest#submissionsUseConcurrentFreshThreads`;
- the P2 logical CPU span exposed through `SystemInfo.getCpuCount()`;
- the P3-A package-private `ThreadTools.bindManagedCpu(int)` / `ManagedCpuBinding.close()` bridge,
  public affinity calls, and final current-CPU precedence;
- `ControlPlaneFragment`, which acquires, restarts, executes, and immediately calls
  `ThreadTools.getCpuInfo()` inside its worker;
- `BaseCloneableObject`, which uses `get`, `getOrSetIfAbsent`, inherited `submit`, and
  `shutdownNow` for temporary first-touch allocation;
- `ControlPlaneLattice`, which calls global `closeAll` during close;
- `SPSCBenchmarks`, `MPSCBenchmarks`, and `MPMCBenchmarks`, which acquire many CPU executors,
  execute overlapping producer tasks, and use `closeAll` at trial teardown; and
- `HighScaleBenchmark`, which acquires, submits, waits, and calls `shutdownNow` before later
  same-CPU reuse.

Those callers constrain behavior but remain outside the diff. Inherited `submit` must retain its
normal `AbstractExecutorService` behavior through this class's `execute` implementation.

### Prohibited work

- Changes to `ThreadTools`, `AffinityCapability`, the P3-A controller/provider/lease types, or
  platform Java/native affinity code.
- Topology production or adapters, `SystemInfo` production, resources, `ResourceMonitor`,
  pressure, cadence, snapshots, or timer policy.
- Core production, benchmarks, Reactor, Spring, native build/package/loader, CI, root POM, or
  unrelated cleanup.
- Task queues, pooled/reused threads, a persistent worker, one-thread-at-a-time serialization, or
  a new scheduling policy.
- Any inspection, edit, build, test, documentation, or command under `euhedral-training`.

## Frozen P3-A task boundary

The executor consumes exactly this package-private shape already merged in `ThreadTools`:

```java
static ManagedCpuBinding bindManagedCpu(int logicalCpu)

interface ManagedCpuBinding extends AutoCloseable {
    @Override
    void close();
}
```

For every executor-created wrapper, including a thread constructed directly through
`getPinnedFactory().newThread(command)`, the wrapper order is:

```text
enter thread
  -> bind managed logical CPU
  -> attempt ThreadTools.setAffinity(cpu)
  -> run command exactly once
  -> attempt ThreadTools.releaseAffinity()
  -> close managed binding
  -> remove exact active-task identity when executor-submitted
exit thread / deliver any command failure to uncaught handler
```

Managed binding is established before the affinity attempt and before user code. A false affinity
result does not reject or skip an already accepted command. The token is a logical ownership scope,
not evidence of hard placement. P3-A's current-CPU precedence remains unchanged: a truthful
independent provider CPU wins, then an active managed logical owner is the fallback, otherwise the
result is `-1`/null.

This preserves the P3-A Linux correction: Linux can return a non-null `getCpuInfo()` from its
independent current-CPU provider even while mutation capability remains `UNSUPPORTED`. P3-B must
not suppress that provider result, equate managed ownership with physical placement, or change
P3-A capability semantics.

`releaseAffinity` is attempted even when the affinity call returned false because the P3-A
controller makes an unmatched release a harmless no-op. Nested `finally` blocks ensure release,
owner close, and active-task removal are individually attempted.

## Public compatibility and construction

Retain the public class shape, `AbstractExecutorService` inheritance, `AutoCloseable`, and every
existing method descriptor recorded by P0:

```text
getOrSetIfAbsent(long,String,int,boolean)
getOrSetIfAbsent(Function,long,String,int,boolean)
get(long)
closeAll()
getPinnedFactory()
getCpu()
start(String,int,boolean)
shutdown()
shutdownNow()
execute(Runnable)
isShutdown()
isTerminated()
awaitTermination(long,TimeUnit)
close()
```

In particular, `awaitTermination` continues to declare no checked `InterruptedException`.
Preserve the configured thread name, clamp priority to the inclusive JDK
`Thread.MIN_PRIORITY..Thread.MAX_PRIORITY` range, preserve daemon configuration, and return the
validated logical CPU as `int`. The `Function<Runnable, ? extends Thread>` creator is fixed for one
executor identity; restart updates only name, clamped priority, and daemon. Acquisition of an
existing RUNNING identity does not change any configuration.

Both acquisition overloads reject a null creator or name with `NullPointerException` and reject a
CPU outside `0 <= cpu < SystemInfo.getCpuCount()` or outside the `int` span with
`IllegalArgumentException` before narrowing or registry access. `start` validates its name before
locking even when the current RUNNING state will make the call a no-op. `get(cpu)` applies the same
CPU validation and otherwise remains observational.

The public pinned factory remains a direct construction surface. Each `newThread(command)` call:

- rejects a null command with `NullPointerException`;
- snapshots the latest published configuration under the lifecycle monitor;
- invokes the fixed creator and configures the candidate outside that monitor;
- returns one distinct configured NEW ownership/affinity-wrapped thread;
- does not inspect lifecycle state, register an active task, start the thread, or make it subject
  to executor termination; and
- allows the direct caller to own start, interruption, join, and disposal.

A null or non-NEW creator result becomes `RejectedExecutionException`. An unchecked exception
from the creator or from configuring the candidate propagates unchanged. Direct wrappers tolerate
the absence of an active-task entry during final cleanup.

## Per-executor lifecycle architecture

### Required data roles

One executor identity has:

- one final validated `int cpu`;
- one final thread creator;
- one final pinned factory;
- one final lifecycle control;
- one registry-entry/cleanup-registration identity assigned before registry publication;
- one lifecycle state: `RUNNING`, `SHUTDOWN`, or `CLOSED`;
- one immutable current thread configuration;
- one checked `long` configuration epoch, initially 1; and
- one identity-keyed active-task map containing only accepted threads that successfully started
  and have not completed wrapper cleanup.

The lifecycle control is a static nested or package-private object. It has no implicit or explicit
back-reference to `PinnedThreadExecutor`, no pinned-factory reference, and no command field. Its
only temporary paths toward an executor are active `Thread` keys whose running wrappers already
hold that executor intentionally; the map is empty for an idle executor.

State, configuration, epoch, and active membership are guarded by exactly one private
`synchronized` lifecycle monitor. Use that monitor's `wait`/`notifyAll`; do not add a second task
counter, shutdown atomic, volatile configuration, polling loop, per-thread join, `ReentrantLock`,
or separate termination condition.

The registry has one distinct low-frequency `synchronized` monitor. If an operation must hold both
monitors, the only order is registry monitor -> lifecycle monitor. Code holding the lifecycle
monitor never acquires the registry monitor. Task exit, explicit cleanup, and interrupt delivery
release the lifecycle monitor before invoking registry or external operations. Thread creation is
outside both monitors. `Thread.start()` is the one external call deliberately made while holding
the lifecycle monitor.

### State machine and observations

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

| State    | Accept `execute` | `start`                        | Active tasks                         |
|----------|------------------|--------------------------------|--------------------------------------|
| RUNNING  | yes              | no-op, configuration unchanged | continue independently               |
| SHUTDOWN | reject           | new config/epoch, then RUNNING | finish unless explicitly interrupted |
| CLOSED   | reject           | `IllegalStateException`        | interrupt/unpark; identity permanent |

The exact observations are:

```text
isShutdown   := state != RUNNING
isTerminated := state != RUNNING && activeTasks.isEmpty()
```

Termination is an instantaneous predicate, not a permanent state. A SHUTDOWN executor observed
terminated may later restart. CLOSED never restarts. Linearization under the lifecycle monitor
decides concurrent transitions: a `start` after `shutdown` wins, a `shutdown` after `start` wins,
and `close` makes every later `start` fail permanently.

Every state transition and every active-task removal calls `notifyAll` before releasing the
lifecycle monitor. Repeated shutdown/close operations are state-idempotent.

### Configuration and restart

Construction publishes a non-null immutable configuration containing the validated name, clamped
priority, and daemon flag; state is RUNNING and epoch is 1.

`start(name, priority, daemon)`:

1. validates and builds the candidate immutable configuration outside the lifecycle monitor;
2. under the monitor, returns without publication when RUNNING;
3. throws `IllegalStateException` when CLOSED; and
4. when SHUTDOWN, checks that incrementing the epoch will not overflow, then publishes the new
   configuration and incremented epoch before publishing RUNNING and notifying waiters.

If the epoch is `Long.MAX_VALUE`, restart throws `IllegalStateException` before changing state or
configuration. Epoch changes only on a successful SHUTDOWN -> RUNNING restart. It distinguishes a
shutdown/restart cycle even when all visible configuration values are equal.

### Execute linearization

`execute(command)` follows this exact sequence:

1. Reject null with `NullPointerException`.
2. Under the lifecycle monitor, require RUNNING and snapshot the same immutable configuration and
   epoch. A non-RUNNING executor rejects immediately with `RejectedExecutionException`.
3. Outside all locks, construct the executor-tracked ownership/affinity wrapper, call the creator,
   configure the returned thread from the snapshot, and require its state to be NEW. Creator or
   configuration exceptions propagate; null/non-NEW becomes `RejectedExecutionException`.
4. Reenter the lifecycle monitor. Require RUNNING, the identical epoch, and the candidate still
   NEW. If any check fails, reject; the unstarted candidate and command are not retained.
5. Register by `Thread` object identity and call `Thread.start()` before releasing the monitor.
6. If start throws, remove that exact identity, notify waiters, and propagate the original
   failure. No registry or lifecycle state transition is implied.

Once step 5 succeeds, shutdown cannot miss the accepted task. The new thread may block on the same
monitor during final removal until execute releases it; this is intentional. There is no interval
observable outside the monitor in which an accepted unstarted task appears active. Each later
accepted execute repeats construction and therefore creates a different thread identity.

### Wrapper failure and cleanup

The command is invoked once and is not caught as an application error. An unchecked command
failure leaves the executor state unchanged and reaches the thread's configured uncaught-exception
handler after cleanup. Cleanup must preserve that exact failure.

Recoverable `RuntimeException` or `LinkageError` from affinity release or owner close is logged as
a bounded diagnostic, does not replace an in-flight command failure, and cannot prevent the next
cleanup step. `VirtualMachineError` and `ThreadDeath` are not normalized or logged as recoverable;
they propagate after the outer active-task-removal `finally` runs. Active removal itself is
nonthrowing under valid invariants.

For executor-submitted work, the final step removes exactly `Thread.currentThread()` by object
identity under the lifecycle monitor and notifies waiters. If the control is CLOSED and becomes
empty, it records an identity-safe registry-removal request, releases the lifecycle monitor, and
then asks the registry to remove only its exact entry. Names and numeric thread IDs never identify
tasks.

The task wrapper holds its `PinnedThreadExecutor` strongly through the outer `finally`. Therefore
a running accepted task prevents cleaner action merely because the submitting caller discarded
the executor reference. After task removal, the active map and wrapper retain no completed command.

### Shutdown, interruption, and close

- `shutdown()` changes RUNNING to SHUTDOWN under the lifecycle monitor, notifies waiters, and does
  not interrupt or unpark accepted work. Repetition in SHUTDOWN/CLOSED is a no-op.
- `shutdownNow()` changes RUNNING to SHUTDOWN when necessary, snapshots all active thread
  identities under the lifecycle monitor, notifies on a transition, then interrupts and unparks
  each snapshot thread outside every lock. It performs the best-effort snapshot on repeated calls
  and returns `List.of()` or another immutable empty list.
- `close()` permanently changes RUNNING/SHUTDOWN to CLOSED, snapshots active threads, notifies,
  then interrupts/unparks outside every lock and invokes the entry's same idempotent cleanup
  registration used by the cleaner. It never joins or waits without a bound.
- Cleaner and hook closure use the same control transition and interrupt helper. Concurrent exits
  and repeated interrupt/unpark are harmless.

Interrupt delivery calls `interrupt()` and `LockSupport.unpark(thread)` at least once for every
thread in the snapshot. A recoverable per-thread interruption failure is a bounded diagnostic and
does not stop attempts for later snapshot threads. These methods do not call
`Thread.interrupted()`, `sleep`, `join`, or another interrupt-clearing operation, so the caller's
preexisting interrupt status is preserved. An interrupt-ignoring command may remain active;
shutdown methods do not claim otherwise.

## Termination and deadline contract

`isShutdown()` and `isTerminated()` evaluate the frozen predicates while holding the lifecycle
monitor. They do not scan `Thread.isAlive()`, clear the task map, or infer completion from an
interrupt attempt.

`awaitTermination(timeout, unit)`:

1. rejects null `unit` with `NullPointerException`;
2. obtains the saturating `unit.toNanos(timeout)` result and sets
   `budget = max(0, convertedNanos)`;
3. under the lifecycle monitor, returns false immediately for RUNNING;
4. returns true when non-RUNNING and the active map is empty;
5. for a nonpositive budget, otherwise returns false after that single truthful check;
6. waits on the lifecycle monitor for at most the computed remaining budget;
7. after every normal or spurious wakeup, returns false if restart made state RUNNING, true if the
   non-RUNNING active map is empty, or continues with the remaining budget; and
8. if interrupted while waiting, restores the caller's interrupt flag and returns false.

Use only elapsed subtraction:

```text
start = System.nanoTime()
elapsed = System.nanoTime() - start
expired := elapsed >= budget
remaining := budget - elapsed
```

Do not calculate `now + timeout`. Convert a positive remaining value into the millisecond/nanosecond
arguments for `Object.wait` without narrowing overflow. This contract covers all practical
durations below the signed `nanoTime` horizon, including a saturated `Long.MAX_VALUE` budget.
There is no fixed polling park, busy wait, future loop, or sequential worker join.

## Singleton registry

### Entry model and acquisition

The class-wide registry is a monitor-guarded map keyed by the original validated `long cpu`. Each
entry owns:

- the CPU key;
- one `WeakReference<PinnedThreadExecutor>`;
- the corresponding lifecycle control;
- the exact entry identity;
- the cleanup action and its cleanup-registration handle; and
- the hook-registration identity current when the entry was installed.

The entry, control, action, hook identity, and cleanup handle are fully initialized before map
publication under the registry monitor. The cleanup action and handle must not strongly reference
the executor. A registry entry is compared by object identity, never only by CPU or value equality.

`getOrSetIfAbsent` is entirely linearized under the registry monitor after argument validation:

1. A live RUNNING entry returns its executor identity without changing configuration.
2. A live SHUTDOWN entry calls the documented restart while holding registry then lifecycle
   monitor and returns the same identity. The original thread creator remains fixed.
3. A CLOSED entry with any active task rejects with `RejectedExecutionException`; it remains a
   tombstone and no candidate executor is created.
4. A CLOSED, cleared, or otherwise stale entry with no active task is removed only if it is still
   the exact mapped entry. Cleanup/hook bookkeeping for that identity is completed before
   replacement.
5. For an absent CPU, one executor/control/entry is constructed and its cleaner is registered
   first. The existing registry hook is reused or the first hook is then registered. Only after
   both registrations succeed is the exact entry published. Because creation occurs while the
   low-frequency registry monitor is held, concurrent callers do not construct losing executor
   candidates.

Cleaner-registration failure occurs before a new hook is added. Hook-add failure explicitly cleans
the new cleanup registration before propagating. Thus no map entry, leaked per-entry registration,
or externally returned unhooked executor is published. Registry construction invokes no user
thread creator; that function is only stored.

`get(cpu)` under the same registry monitor returns the referent only when it is live and RUNNING.
It returns null for SHUTDOWN, CLOSED, cleared, or absent entries. It may complete exact stale-entry
cleanup when no task is active, but it never restarts and can never remove a replacement.

### Closed tombstones and exact removal

A CLOSED entry is removed immediately only when its active map is empty. While an old task remains,
the entry stays mapped even if the weak referent would otherwise be stale; the wrapper's strong
reference normally keeps that referent alive. Same-CPU acquisition rejects until final wrapper
exit removes the last task and then the exact entry.

Every removal path performs an identity comparison equivalent to `remove(cpu, exactEntry)` under
the registry monitor. An explicit close, delayed cleaner action, task exit, `get` cleanup, hook,
or `closeAll` action associated with an old entry cannot remove or close a newer replacement.

## Cleaner, hook, and `closeAll`

### Noncapturing cleanup

Use one class-wide `Cleaner`. Each installed executor has one cleanup action and one registration.
The action contains only:

- CPU key;
- exact registry-entry identity;
- lifecycle control; and
- exact hook-registration identity.

It contains no executor, command, bound instance method reference, synthetic lambda capture of an
executor, or other path back to the referent. The weak reference is held by the registry entry, not
captured as a strong executor reference.

One `AtomicBoolean.compareAndSet(false, true)` in the cleanup control is the only claim of cleanup
side-effect ownership. The winning action marks lifecycle CLOSED, snapshots active tasks, performs
best-effort interrupt/unpark outside locks, and asks for exact entry removal when empty. Explicit
close calls the registration's `clean()` so the same action is deregistered and run exactly once.
Cleaner, explicit close, hook, `closeAll`, and final task exit may all request removal, but only
exact-entry comparison may mutate the map.

If active tasks remain, cleanup leaves the entry as a CLOSED tombstone. The final task exit removes
it without rerunning cleanup ownership. A delayed action after replacement is a no-op with respect
to that replacement.

### One-hook lifecycle

One registry-wide hook-registration object contains the exact hook `Thread`. The hook runnable is
static/noncapturing and reaches only registry controls through `closeAll`; it never closes over an
executor or command.

- Installing the first entry into an ordinarily empty registry registers exactly one hook before
  entry publication.
- Later entries and every restart reuse the same hook identity.
- Removing the last exact entry during ordinary runtime removes that exact hook.
- Add failure rolls back acquisition. A non-shutdown remove failure is logged and retains the
  exact registration for reuse, so a second hook is never added.
- `removeShutdownHook` throwing `IllegalStateException` during JVM shutdown is expected; it is
  recorded as shutdown-in-progress and never causes replacement-hook registration.

All hook identity and entry-count decisions occur under the registry monitor. Tests use a fake
registrar and assert registrations, removals, and maximum live count; they do not start a real JVM
shutdown.

### Registry-wide close

`closeAll()` holds the registry monitor while it takes the bounded entry snapshot and marks every
entry present in that snapshot CLOSED under the fixed registry -> lifecycle lock order. Acquisition
cannot install or restart during this pass. The point after all snapshot controls are CLOSED and
before the registry monitor is released is the `closeAll` linearization point.

After releasing the registry monitor, `closeAll` delivers interrupt/unpark and invokes cleanup
registrations. Empty entries are exact-removed; active entries remain tombstones. A later
acquisition may proceed after the pass, replacing only an empty exact old entry and rejecting on an
active tombstone. Snapshot storage is bounded by registry size and discarded before return.

Repeated and empty-registry calls are no-ops apart from harmless idempotent cleanup. `closeAll`
never waits for task exit and therefore remains bounded when a command ignores interruption.

## Failure contract

| Failure                                            | Required outcome                                                                        |
|----------------------------------------------------|-----------------------------------------------------------------------------------------|
| invalid CPU, null creator/name/command/unit        | documented boundary exception before mutation or publication                            |
| creator throws                                     | original unchecked failure propagates; no active task/command entry                     |
| creator returns null or non-NEW thread             | `RejectedExecutionException`; never register or start                                   |
| candidate configuration throws                     | original failure propagates; candidate/command not retained                             |
| execute loses shutdown/restart epoch race          | `RejectedExecutionException`; unstarted candidate discarded                             |
| `Thread.start()` throws                            | exact task identity removed, waiters notified, original failure propagates              |
| task throws                                        | cleanup completes; original failure reaches uncaught handler; lifecycle state unchanged |
| affinity application returns false                 | accepted command still runs under managed logical ownership                             |
| affinity/owner recoverable cleanup throws          | bounded diagnostic; later cleanup and original command failure preserved                |
| cleanup raises `VirtualMachineError`/`ThreadDeath` | fatal error propagates; outer task removal still runs                                   |
| orderly shutdown repeats                           | state idempotent; accepted work remains uninterrupted                                   |
| shutdownNow/close repeats                          | state idempotent; a repeated best-effort interrupt is harmless                          |
| epoch increment would overflow                     | `IllegalStateException` before configuration/state publication                          |
| await caller interrupted                           | false return and interrupt flag restored                                                |
| timeout conversion saturates                       | elapsed-subtraction budget remains overflow-safe                                        |
| task ignores interrupt                             | close returns; active membership and termination remain truthful until exit             |
| same-CPU acquire sees CLOSED active entry          | reject; no new identity or overlap                                                      |
| old cleanup runs after replacement                 | exact identity comparison preserves replacement                                         |
| hook add or cleaner registration fails             | acquisition rolls back; nothing live is published                                       |
| hook removal fails outside shutdown                | bounded diagnostic; retain/reuse exact hook identity; never multiply hooks              |
| hook removal reports JVM shutdown                  | accept `IllegalStateException`; do not add a replacement hook                           |

## Java Memory Model and lock proof

- Registry monitor unlock safely publishes an entry, weak reference, lifecycle control, cleanup
  action/registration, and hook identity to every later registry lock.
- Lifecycle monitor unlock publishes state, epoch, immutable configuration, and active membership.
  Later lifecycle locking by state queries, await, restart, shutdown, or task exit observes the
  coherent tuple.
- Final fields publish CPU, creator, factory/control references, immutable configuration contents,
  and noncapturing action fields after ordinary safe registry publication.
- Task registration precedes `Thread.start()` while holding the lifecycle monitor. JDK start
  happens-before publishes the wrapper, command, CPU, and configuration to the new thread.
- Successful `Thread.start()` returns before execute releases the monitor. A task's final removal
  and `notifyAll` under that monitor happen-before a later locked termination predicate.
- `Object.wait` atomically releases and reacquires the lifecycle monitor. State transitions and
  task removals notify under the same monitor, so predicate loops tolerate spurious wakeups and
  cannot miss coherent publication.
- Registry -> lifecycle is the only nested lock order. Task exit and cleanup release lifecycle
  before registry removal; interrupt/unpark, creator, hook registrar callbacks where avoidable,
  logging, and cleanup registration invocation occur without the lifecycle monitor.
- The cleanup control's one `AtomicBoolean.compareAndSet(false, true)` has volatile read/write
  semantics and publishes its final action fields before cleanup/removal. Opaque or plain access
  is insufficient for that ownership transition.
- Hook identity and `closeAll` gating are registry-monitor state. Do not add an unlocked map
  snapshot, volatile hook count, or independent close-all flag.

No other atomic, volatile lifecycle field, VarHandle, or concurrent map is required. If
implementation introduces one, its exact invariant, access mode, and happens-before edge must be
added to this blueprint before implementation proceeds.

## Contamination and reachability contract

- At externally observable points, the active map contains only successfully started threads whose
  wrappers have not completed. Start failure and final exit remove exact identities.
- Rejected and failed candidates are unstarted/unregistered and cease to be referenced by executor
  state when `execute` returns or throws.
- Active wrappers retain the executor and command only through final cleanup. Completed commands
  are absent from active maps, entries, actions, registrations, and hooks.
- Registry entries weakly reference executors. Cleanup actions, cleanup registrations, hook
  threads, and static method references contain no strong path to an idle executor.
- The lifecycle control captured by cleanup is not an inner-class back-reference. Structural
  reachability from an idle action through its entry/control/registration graph finds no executor,
  pinned factory, command, or task thread.
- One cleanup registration exists per installed executor identity and is explicitly cleaned on
  close. Restart creates neither a new entry, cleanable, nor hook.
- CLOSED tombstones exist only while an old active task prevents safe same-CPU replacement. Final
  task exit exact-removes them.
- The hook count is at most one. The ordinary empty-registry transition removes it; a reported hook
  removal failure retains only that one reusable identity.
- `closeAll` retains one bounded registry-size snapshot only for the duration of its pass.
- Executor test seams are restored in teardown only when their isolated registry is empty; no fake
  registrar, test command, latch, or thread remains in class-wide production state.
- Array/mask precision and filesystem/native contamination are not applicable because P3-B owns no
  such data or work. Thread, task, thread-local, map, weak-reference, tombstone, cleanable, and hook
  contamination are fully applicable.

## Deterministic test contract

Tests use latches, barriers, an isolated package-private registry/control harness, a cleanup
registrar fake, a runtime-hook registrar fake, and a task-affinity fake that mirrors
`bindManagedCpu`/set/release. Production behavior still delegates to `ThreadTools`. No acceptance
test uses `System.gc()`, ReferenceQueue timing, arbitrary sleep, real JVM shutdown, or physical CPU
placement timing.

Every blocking assertion has a five-second diagnostic timeout. The stress test has a 30-second
outer timeout. Each test closes/releases all commands in `finally`, empties its isolated registry,
and restores production registrars.

### Stable anchors and compatibility

The A02 ledger anchor is exactly:

```text
io.euhedral_execution.hardware_utils.PinnedThreadExecutorLifecycleTest
  #linearizesExecuteShutdownAndCleanup
```

That method must force at least the E2/E3 acceptance boundary and prove final task, registry,
cleanable, and hook cleanup. The P0 anchor remains green:

```text
io.euhedral_execution.hardware_utils.compatibility.PinnedThreadExecutorCompatibilityTest
  #submissionsUseConcurrentFreshThreads
```

Also retain `PinnedThreadExecutorTest#reusesTheCpuExecutorAndAppliesThreadProperties`, including
priority clamping, daemon/name/CPU observation, close, termination, registry absence, and rejection.

### E1-E12 schedules

| ID  | Forced schedule and required assertion                                                                                                                                                                                                                                                                        |
|-----|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| E1  | 32 acquisition callers meet one barrier; all return the same identity and observe one entry, one hook, and one cleanup registration.                                                                                                                                                                          |
| E2  | Creator pauses after returning a NEW thread; shutdown linearizes before execute registration; execute rejects and the candidate never starts or remains stored.                                                                                                                                               |
| E3  | Execute registers and starts under the lifecycle monitor; shutdown linearizes next and its task snapshot cannot miss that accepted identity.                                                                                                                                                                  |
| E4  | Force shutdown -> start and start -> shutdown separately; monitor order decides final state and only a successful restart changes configuration/epoch. Include CLOSED -> start rejection and epoch-overflow rollback.                                                                                         |
| E5  | Close races same-CPU acquisition while an old task is active; acquisition rejects and no replacement task starts until final exit exact-removes the tombstone.                                                                                                                                                |
| E6  | Two commands enter before either release; distinct Thread identities run concurrently and the task-affinity fake observes the same scoped managed logical CPU for both, with no physical-placement assertion.                                                                                                 |
| E7  | Command throws while affinity release and owner close each fail recoverably; original command failure reaches the uncaught handler, both later cleanup steps run, waiter signals, and later executor use succeeds. Include fatal-cleanup outer task removal.                                                  |
| E8  | `shutdown` leaves accepted work uninterrupted; `shutdownNow` and `close` interrupt/unpark snapshots. An interrupt-ignoring task keeps termination false until its explicit release. Preserve a pre-interrupted caller.                                                                                        |
| E9  | Await covers successful completion, RUNNING, zero/negative timeout, expiration, saturating conversion, injected spurious notify, restart while waiting, and caller interruption with exact return/flag behavior.                                                                                              |
| E10 | Invoke an old explicit/cleaner action after a same-CPU replacement is installed; exact entry and hook identities preserve the replacement. Structurally traverse the idle action/entry/control/registration graph and find no executor, factory, command, task thread, or synthetic outer-instance reference. |
| E11 | First/last entries add/remove one fake hook; multiple CPUs, restart, repeated shutdown/close, direct cleaner invocation, and repeated `closeAll` never increase the maximum live hook count above one.                                                                                                        |
| E12 | Barrier-control `closeAll` against acquisition; all entries in its linearized snapshot become CLOSED, acquisition cannot restart/install during the pass, later acquisition resumes, and no old/new same-CPU tasks overlap.                                                                                   |

Additional boundary assertions cover invalid CPUs, null arguments, throwing/null/non-NEW creators,
thread configuration failure, `Thread.start` failure, direct pinned-factory use without task
membership, immutable-empty `shutdownNow`, shutdown/restart creator retention, and exact task
identity rather than name/numeric-ID removal.

### Bounded stress

Run 50 rounds. Each round uses eight acquisition/submitter threads and permits at most eight live
commands. Alternate barrier-controlled execute-vs-shutdown and close-vs-acquire races. Commands
use bounded latches and always release in `finally`.

After every round assert:

- zero active tasks;
- zero CLOSED tombstones;
- empty isolated registry;
- zero live fake cleanup registrations;
- zero live fake hooks; and
- no managed-owner or affinity-lease state in the task-affinity fake on any participating thread.

This is bounded race/cleanup evidence, not a throughput or physical-affinity claim.

## Bounded implementation checklist

1. Replace the concurrent map/check-then-put and atomic shutdown flag with the monitor-guarded
   lifecycle control, immutable configuration, checked epoch, and exact active map.
2. Add CPU/name/creator validation while retaining public descriptors, fixed creator identity,
   name/daemon behavior, and priority clamping.
3. Implement the pinned factory's direct NEW wrapped-thread contract independently of executor
   membership and lifecycle state.
4. Implement execute's snapshot -> create outside -> epoch/state recheck -> register/start inside
   sequence, including null/non-NEW, creator, configuration, and start failures.
5. Wrap commands with managed binding before affinity/user code and nested release -> owner close ->
   exact task removal cleanup, preserving command/fatal/recoverable failure rules.
6. Implement the exact RUNNING/SHUTDOWN/CLOSED table, restart overflow guard, orderly shutdown,
   best-effort shutdownNow/close interruption, and immutable empty shutdownNow result.
7. Implement monitor-based state queries and elapsed-subtraction `awaitTermination`, including
   restart, spurious wakeup, saturation, and interrupt restoration.
8. Replace the registry with exact weak entries under one monitor, enforcing live reuse,
   SHUTDOWN restart, CLOSED-active rejection, stale exact removal, fixed lock order, and no losing
   cleanup ownership.
9. Add the noncapturing cleanup control/action and explicit cleanup-registration path with exactly
   one CAS and final-task tombstone removal.
10. Add one registry-wide hook identity, deterministic add/remove failure behavior, ordinary last-
    entry removal, JVM-shutdown handling, and noncapturing hook dispatch.
11. Implement gated bounded `closeAll` with its snapshot-closing linearization and outside-lock
    interruption/cleanup.
12. Add the isolated deterministic seams, A02 anchor, E1-E12 schedules, direct factory/failure
    boundaries, structural reachability checks, and 50-round bounded stress.
13. Run focused lifecycle/compatibility gates, hardware verify, read-only core compatibility tests,
    and scope/hygiene checks with the `mise.toml` defaults; append exact evidence below.

No item authorizes a P3-A, platform, core, benchmark, resource, topology, native, CI, or training
edit.

## Acceptance criteria

1. P0 reports no removed or changed public API/module descriptor, and the A02 stable test plus
   concurrent-fresh-thread compatibility anchor pass.
2. Every accepted execution creates and starts one distinct NEW thread; two blocking commands
   enter concurrently and no queue or thread reuse exists.
3. Managed binding precedes affinity and user code, false affinity does not skip work, and every
   normal/failing wrapper attempts release, owner close, and exact task removal without changing
   P3-A current-CPU/capability semantics.
4. RUNNING/SHUTDOWN/CLOSED, immutable configuration, epoch, restart, execute, shutdown,
   shutdownNow, close, and task exit follow the frozen monitor linearization in E2-E8.
5. No shutdown race starts an untracked task; creator/config/start/command/cleanup failures leave
   coherent lifecycle and active membership.
6. `isShutdown`, `isTerminated`, and `awaitTermination` are predicate-truthful,
   restart/spurious-safe, overflow-safe, and preserve caller interruption.
7. E1/E5/E10/E12 prove one live identity per CPU, CLOSED-active no-overlap, exact stale/action
   removal, and registry-wide close gating.
8. Cleanup action and hook are noncapturing; one cleanable belongs to each installed identity; at
   most one hook exists and ordinary final removal returns its count to zero.
9. Repeated close/shutdown/closeAll, delayed cleaner action, hook failures, interrupt-ignoring
   tasks,
   and final task exit satisfy the failure and tombstone contracts.
10. Every lifecycle/registry/CAS edge matches the documented JMM proof; no independent atomic,
    volatile, VarHandle, or reversed nested lock weakens coherent publication.
11. E1-E12 and the 50-round stress finish within bounds with zero tasks, tombstones, registry
    entries, fake cleanables/hooks, managed owners, affinity leases, or retained commands.
12. Parent criteria 7-16 and A02 have direct implementation/test evidence; criteria 1-6 remain
    inherited from the merged, audited P3-A child and are not reimplemented.
13. Detailed native/platform work, resources/pressure, topology, core/benchmark production, task
    serialization, training, and unrelated changes are absent from the diff.
14. Focused tests, P0 gates, hardware verify, read-only core tests, `git diff --check`, scope
    checks,
    and final status pass or record the exact environmental limit without substituting Java 17 for
    the repository's mise-selected JDK 21 toolchain.

## Verification commands

Use the repository defaults through `mise exec --`. The module remains compiled with its Java 17
release target, but commands run on the mise-selected JDK 21; do not select a Java 17 runtime.
No command may select training.

Focused lifecycle suite:

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='PinnedThreadExecutorLifecycleTest,PinnedThreadExecutorCompatibilityTest,PinnedThreadExecutorTest' \
  surefire:test
```

P0 compatibility gates:

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils \
  resources:resources compiler:compile \
  resources:testResources compiler:testCompile \
  -Dtest='ApiCompatibilityTest,MaskFormattingCompatibilityTest,PinnedThreadExecutorCompatibilityTest' \
  surefire:test
```

Module and read-only consumer gates:

```bash
mise exec -- mvn -B -pl euhedral-hardware-utils -am verify
mise exec -- mvn -B -pl euhedral-core -am test
```

The hardware verify may depend on the P1 cross-native toolchain. If unavailable, record the exact
missing mise-selected tool or SDK; do not alter source/build configuration or use another JDK to
make the environment pass.

Scope and hygiene, with the implementation branch point substituted for `<child-parent>`:

```bash
git diff --check
git diff --name-only <child-parent> -- euhedral-training
git diff --name-only <child-parent> -- euhedral-core/src/main benchmarks/src/main
git diff --name-only <child-parent> -- \
  euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/ThreadTools.java \
  euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/AffinityController.java \
  euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/{linux,windows,osx}
git status --short
```

All three scope diffs are empty. Do not inspect training to perform its scope check.

## Sizing and split gate

P3-B remains one bounded implementation child and is not split further.

- Production ownership is one public class plus optional bounded unexported support in one Java
  module; no caller changes are needed.
- Per-executor lifecycle, task membership, registry tombstones, cleanup action, hook identity, and
  `closeAll` are one coupled state machine. Splitting them would require temporary duplicate
  lifecycle truth or a public/internal intermediate contract.
- The P3-A dependency is already merged and reduced to one package-private managed-binding bridge
  plus public set/release calls.
- E1-E12 share the same isolated registry/control seams and together prove the indivisible
  acceptance boundary. A separate test-only child would delay evidence for the exact races the
  implementation creates.
- The inventory is nevertheless bounded: one owning module, one contract-bearing production
  class, optional small support roles, one new stable test class, and two existing compatibility
  tests.

Do not recombine P3-A or split registry/cleanup into a separately merged behavior phase. If this
inventory expands into caller production, P3-A/native/platform work, or a second lifecycle owner,
stop and rerun the sizing gate before coding.

## Implementation model reassessment

The parent-selected implementation capability is confirmed:

- Model: **`gpt-5.6-sol`**
- Reasoning effort: **`high`**

No increase to `max` is required for implementation because this child has one bounded production
owner and an exact checklist, state table, lock order, failure table, and deterministic schedule
matrix. A downgrade is not justified: restartable termination, arbitrary creator code,
create-outside/start-inside linearization, two-monitor ordering, weak reachability, cleaner/hook
identity, and E1-E12 still require high-reasoning concurrency work.

If that model/effort is unavailable, stop for developer direction. Do not silently downgrade or
omit race schedules.

## Exact implementation context envelope

Implementation reads only:

- `AGENTS.md`, the temporary P3 status, and this blueprint's frozen contract/checklist;
- the parent P3 executor/JMM/cleanup/test/acceptance summary;
- the P3-A completion/conformance summary and `ThreadTools` final managed-task bridge;
- P0 A02/fresh-thread evidence and the P2 CPU-span summary;
- hardware POM/module descriptor;
- `PinnedThreadExecutor`, `PinnedThreadExecutorTest`, and
  `PinnedThreadExecutorCompatibilityTest`; and
- the exact named worker/benchmark call snippets listed in this child for compile compatibility.

It does not reread platform/native code, topology adapters, resource/pressure internals, unrelated
core bodies, Reactor, Spring, CI, or training. JDK 21 `Thread`, `Cleaner`, weak-reference,
monitor/wait, Runtime-hook, and `TimeUnit` documentation/source may be consulted only when an exact
JDK behavior in this blueprint needs confirmation.

Implementation outputs are limited to `PinnedThreadExecutor`, bounded private/unexported support,
the three owned test files, this blueprint's completion record, and the temporary P3 status block.

## Handoff condition

Hand off this child blueprint for developer review and merge only when:

- implementation can follow the checklist without choosing any state, lock, acceptance, restart,
  interrupt, deadline, registry, cleaner, hook, cleanup, or memory-mode rule;
- A02, E1-E12, stress bounds, failure outcomes, JMM edges, reachability assertions, and exact test
  seams are explicit;
- the sizing gate keeps one bounded child and the plan records the confirmed
  `gpt-5.6-sol`/`high` implementation model;
- only this blueprint and authorized plan/status planning text differ from `2027a47b`;
- `git diff --check`, documentation scope, and final status checks pass; and
- no implementation code or implementation branch has started before review and merge.

Do not implement P3-B on this branch. After authorized review/merge into the updated P3 root,
create `hardware-utils-overhaul/phase-3-executor-lifecycle-implementation` from that root.

## Implementation completion record

Implementation completed on
`hardware-utils-overhaul/phase-3-executor-lifecycle-implementation`, based on the reviewed and
merged P3 root at `bfca49b6`.

### Changed surface

- Replaced `PinnedThreadExecutor`'s check-then-put map, independent atomic lifecycle fields,
  per-executor hooks, strong cleaner action, joining shutdown, and polling deadline with the frozen
  lifecycle monitor, exact weak registry entries, one registry hook, noncapturing cleanup action,
  elapsed-subtraction await, and gated `closeAll` design.
- Every accepted execution now constructs one distinct NEW thread outside the lifecycle monitor,
  rechecks state/epoch, identity-registers and starts under that monitor, and runs through the P3-A
  managed binding before affinity and user code. Nested cleanup attempts release, binding close,
  and exact task removal while preserving command/fatal failures and caller interruption.
- Added only package-private nested registry, cleanup/hook, task-binding, and deterministic
  thread-configuration seams. No public/protected descriptor, module directive, P3-A source,
  native/platform source, resource/topology/monitor source, core/benchmark production, CI,
  training, Reactor, or Spring file changed.
- Added `PinnedThreadExecutorLifecycleTest`; the two existing executor compatibility tests required
  no changes. Updated only this completion record and the temporary P3 status block in `AGENTS.md`.

### Commands, results, skips, and limits

- `mise` is not installed. The documented fallback used OpenJDK 21.0.11 explicitly from
  `/usr/lib/jvm/java-21-openjdk-amd64` with system Maven 3.6.3. The JDK matches the repository
  default major version; Maven is below the pinned 3.9.16 and disables the build cache.
- The focused lifecycle command passed 16 tests: 14 lifecycle/boundary/stress tests plus
  `PinnedThreadExecutorTest` and the P0 concurrent-fresh-thread compatibility anchor. The complete
  lifecycle class, including its 50-round stress test, also passed five consecutive reruns.
- The P0 API/mask/fresh-thread command passed 3 tests after a clean Java 21 compilation. It reported
  zero removed or changed public descriptors/module directives and only the already-reviewed
  P2/P3-A additions.
- A direct-goal, read-only `euhedral-core` compile/test passed all 99 core tests under Java 21. The
  exact `-pl euhedral-core -am test` lifecycle command cannot reach core because the hardware Zig
  phase fails first.
- `mvn -B -pl euhedral-hardware-utils -am verify` stops before tests at
  `exec-maven-plugin:zig-build`: the `ZIG` executable parameter is missing or invalid. No `zig` or
  `rcodesign` executable is installed, so native packaging/signing verification is unavailable.
  Source or build configuration was not changed to bypass that limit.
- Scope and hygiene checks passed: `git diff --check` is clean, and diffs from `bfca49b6` under
  training, core/benchmark production, P3-A `ThreadTools`/controller, and platform affinity paths
  are empty. Final status contains only the two owned Java files, this completion record, and the
  temporary P3 status block.

### E1-E12, stress, and cleanup evidence

- E1 proves 32 synchronized acquisition callers receive one identity, entry, cleanable, and hook.
  The A02 anchor `linearizesExecuteShutdownAndCleanup` forces E2 candidate rejection after shutdown
  and E3 register/start-before-shutdown visibility, then proves zero tasks, entries, cleanables,
  and hooks.
- E4-E6 prove ordered restart/shutdown/close, checked epoch rollback, CLOSED restart rejection,
  active tombstone replacement exclusion, distinct concurrent thread identities, and managed
  logical ownership without a physical-placement assertion.
- E7-E9 prove original command/fatal failure delivery after recoverable cleanup, later reuse,
  orderly versus interrupting shutdown, interrupt-ignoring truthful termination, immutable-empty
  `shutdownNow`, spurious/restart/saturation/expiration predicates, and restored waiter/caller
  interruption.
- E10-E12 prove delayed exact cleanup cannot remove a replacement, structural cleanup reachability
  has no executor/factory/command/task-thread or synthetic outer path, hook count never exceeds one,
  hook failures reuse the exact identity, and `closeAll` gates acquisition until its complete
  CLOSED snapshot is published.
- Boundary tests cover invalid/null inputs, throwing/null/non-NEW creators, injected configuration
  failure, `Thread.start` failure, direct factory use after close without task membership, creator
  retention across restart, and cleanup/hook registration rollback. Every normal deterministic
  harness and each of 50 stress rounds ends with zero active tasks, CLOSED tombstones, entries,
  fake cleanup registrations, fake hooks, managed owners, and affinity leases.

### Acceptance classification

- A02: `satisfied` by its stable ledger anchor and final zero-count cleanup assertions.
- Parent criteria 7-15: `satisfied` by E1-E12, boundary/failure coverage, the one-monitor lifecycle
  and registry-monitor lock proof, the single cleanup CAS, structural reachability, and empty scope
  diffs. Parent criteria 1-6 remain inherited as `satisfied` from the merged P3-A audit and were not
  reimplemented.
- Parent criterion 16: `satisfied` under its pass-or-record-exact-limit clause. Focused, P0, direct
  core, repetition, and hygiene gates pass; full hardware verify and the exact reactor core command
  have the missing Zig/rcodesign and unpinned-Maven limits recorded above.

No state, lock, acceptance, restart, rejection, interruption, deadline, registry, cleaner, hook,
cleanup-order, or memory-mode decision changed. P3-B is ready for developer review and merge before
its combined conformance/manual-review audit begins.

## Conformance audit completion evidence

The independent P3-B conformance audit completed on 2026-08-01 on
`hardware-utils-overhaul/phase-3-executor-lifecycle-audit` from updated P3 root `6e70cb8d`.

- Every P3-B requirement, applicable parent criteria 7-16, and A02 is classified `satisfied` in
  `docs/audits/hardware-utils/phase-3-executor-lifecycle-conformance.md`.
- No implementation correction was required. The audit independently reviewed E1-E12, 50-round
  stress, fresh concurrent threads, lifecycle/rejection/failure boundaries, interruption/deadline
  truthfulness, registry no-overlap, cleanup reachability, hook count, exact removal, `closeAll`,
  contamination, and the documented JMM edges.
- Focused lifecycle and P0 compatibility gates pass, with the lifecycle class passing five
  consecutive runs. Scope and diff checks are clean. The audit records the unavailable pinned Java
  21/Maven 3.9.16/Zig toolchain and does not replace the implementation record's native limit.
- This audit now awaits developer review and merge before the combined P3 root audit.
