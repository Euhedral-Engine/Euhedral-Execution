# Phase 4 Listener Publication Blueprint (P4-C)

## Status and authority

This is the implementation-ready blueprint for P4-C. The parent blueprint
`hardware-utils-overhaul/phase-4-resource-monitor-pressure` establishes boundaries. P4-A and P4-B
implementations are completed and merged.

This action is planning-only. It modifies this blueprint but does not change production code, tests,
or authorize an implementation branch. After review, merge this blueprint into the P4 root before
beginning implementation.

## Purpose and fixed boundaries

Scope:

- Build `LatestValueDispatcher` in `internal.monitor`.
- Implement an identity-based listener registry for `ResourceMonitor.MonitorListener`.
- Provide one-active/one-pending delivery.
- Handle nonblocking `beginClose(terminationHook)` and `awaitClosed()` barrier.

Non-goals and fixed boundaries:

- Do not edit `ResourceMonitor`, sampling math, pressure calculations, or public
  `HardwareUtilization` types.
- Platform, core, native, and training modules are prohibited or read-only.
- Do not implement custom queues, locks, or memory models outside the standard JDK.

## Architecture and selected design

The dispatcher uses a one-active/one-pending state machine to guarantee bounded memory and
latest-value delivery.

- **Offer/Replace/Wake**: `offer` replaces any pending `HardwareUtilization` record without
  blocking. If the dispatch thread is idle, it is woken.
- **Snapshot Iteration**: To decouple listener delivery from concurrent registration, the registry
  takes a snapshot of listeners before dispatch.
- **Close Barrier**: Two-phase shutdown. `beginClose(terminationHook)` rejects new offers and drains
  the pending record. The termination hook runs exactly once, fully unlocked, when the dispatch
  thread exits. `awaitClosed()` tolerates reentrant calls.

## Package boundaries and data flow

- **Owner**: `io.euhedral_execution.hardware_utils.internal.monitor`
- **Inputs**: Strictly timestamped immutable `HardwareUtilization` and `MonitorListener`
  registrations.
- **Outputs**: Ordered best-effort delivery to listeners.
- **Data Flow**: `offer(HardwareUtilization)` -> Nonblocking replace -> Wake -> Thread reads active
  record -> Iterates listener snapshot -> Delivers.

## Data schemas, invariants, and algorithms

- **Identity Registry**: Listeners are added/removed based on object identity.
- **Fault Isolation**: `Throwable` and `InterruptedException` in a listener callback are caught and
  logged; they do not crash the dispatch thread or halt delivery to other listeners.
- **Thread Start Failure**: Proper cleanup and exception propagation if the dispatch thread fails to
  start.
- **Lock Order**: Lock acquisition is strictly ordered from the lifecycle down to the dispatcher.

## Memory semantics, safety, and ownership

- Clear memory edges separate the producer (monitor loop) and consumer (dispatch thread).
- Memory-contamination avoidance is achieved via the one-pending replacement model, capping bounded
  memory usage.
- All callbacks and hooks are executed outside dispatcher locks.

## Test fixtures, acceptance criteria, and conformance

- **Deterministic Seams**: Tests must use `CountDownLatch` or similar deterministic barriers.
  `Thread.sleep()` or reliance on the common fork-join pool are prohibited.
- **Acceptance Criteria**:
    - Prove exactly-once unlocked termination notification.
    - Prove bounded pending state (one-active/one-pending).
    - Prove reentrant `awaitClosed()` behavior.
    - Prove `Throwable` cleanup and thread isolation upon listener failure.
- **Conformance Command**:
  `gradle :euhedral-hardware-utils:test --tests "*LatestValueDispatcherTest*"`

## Unresolved blockers

None. The capability fulfills the parent constraint without ambiguity.

## Implementation model reassessment

- **Context load**: Minimal. Confined to one internal dispatcher class and its test class.
- **Coupling**: Extremely low. Does not touch native topology, sampling, or serialization.
- **Demands**: Standard concurrency (locks, condition variables, thread interruption).
- **Reasoning Effort**: `low`. The blueprint provides exact semantics for the state machine and lock
  ordering.
- **Selected Model**: Capability equivalent to `gpt-5.5` at `low` effort is approved for
  implementation. The plan's implementation prompt label must be updated with this selection.

## Completion Record

- **Changed Files**: `LatestValueDispatcher.java`, `LatestValueDispatcherTest.java`
- **Commands Run**: `gradle :euhedral-hardware-utils:test --tests "*LatestValueDispatcherTest*"`
- **Results**: Build successful. All conformance tests passed.
- **Acceptance-criteria evidence**: Proven exactly-once termination hook (unlocked), bounded state
  via one-active/one-pending coalescing, reentrant addition/close safety via snapshot iteration, and
  `Throwable` isolation.
- **Deviations**: None.
