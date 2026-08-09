# Phase 4-D Monitor Lifecycle and Scheduler Blueprint

## Status and authority

This is the implementation-ready blueprint for P4-D, generated from the P4 parent blueprint. The
parent blueprint (`hardware-utils-overhaul/phase-4-resource-monitor-pressure`) provides the
architectural boundaries. This child blueprint defines the lifecycle, recurrence, and publication
integration for the single canonical resource monitor pathway required to fulfill the P4-D
responsibility.

## Purpose

P4-D is responsible for:

- The six-state monitor lifecycle (`NEW`, `STARTING`, `RUNNING`, `STOPPED`, `CLOSING`, `CLOSED`).
- Anchored scheduling and coalesced stopped reads.
- Exactly-once topology and listener ordered publication.
- `Throwable` isolation, safe provider/thread failure transitions, and termination cleanup
  semantics.
- Removal of the legacy 1 ns timer-resolution request.
- Integration fixtures for clock/waiter boundaries.

This blueprint integrates but does not modify the validity contracts (P4-A), pressure mathematics
(P4-B), or listener dispatcher (P4-C).

## Sizing and split gate

The parent blueprint explicitly sized and split P4 into four children. P4-D represents a single,
bounded responsibility (monitor lifecycle + anchored recurrence + integrated publication). It relies
on small `Clock`/`Waiter` seams to test time and concurrent transitions explicitly. The sizing gate
confirms that P4-D fits into one implementation context and does not need further splitting.

## Implementation model reassessment

The parent blueprint evaluated the context envelope and selected `gpt-5.6-sol`, `high` effort. P4-D
requires intricate orchestration of the JMM, precisely ordered state transitions, and recurrence
math for the anchored timeline. Given the reliance on precise concurrent state transitions,
`gpt-5.6-sol` at `high` reasoning effort is **confirmed** as the required implementation model. No
upgrade is necessary.

## Local package inventory and contracts

Package: `io.euhedral_execution.hardware_utils` (and `internal` as necessary).

### 1. Six-State Table and Transitions

- `NEW`: Created but not started.
- `STARTING`: Thread is launching; initial freshness constraints apply.
- `RUNNING`: Periodic anchored scheduling is active.
- `STOPPED`: Scheduled work is paused. `stop()` is additive and restartable; a single coalesced read
  is allowed.
- `CLOSING`: Terminal transition underway. Rejects new lifecycle commands.
- `CLOSED`: Fully terminated and resources reclaimed.

### 2. Constructor and Duration Failures

- Constructor sampling is strictly prohibited.
- `min(30 s, max(1 s, 5P))` fast TTL limits apply. Fast cadence is fixed to 200 ms (`P`).
- If provider initialization, topology injection, or cadence duration validation fails, the monitor
  halts and enters the `CLOSED` state safely.

### 3. Coalesced Stopped Read and Freshness

- While in the `STOPPED` state, subsequent reads coalesce to a single evaluation. The result is
  published once, satisfying initial/restart freshness requirements before polling suspends.

### 4. Close Ordering

- `evaluationActive` and `publicationClaimed` boundaries provide strict `CLOSING` ordering.
- An already-claimed publication must complete before external close (`awaitClosed()`) returns.

### 5. Failure Transitions

- Provider, topology, or thread exceptions during the dispatch loop result in safe fallback to
  `CLOSING`, then `CLOSED`. `Throwable` faults are isolated and do not crash the consumer graph.

### 6. Explicit P2 Logical-Span Injection

- P4-D incorporates P2 topological context explicitly. Topology logical-span injection occurs prior
  to metric evaluation to ensure size-consistent pressure mathematics.

### 7. Seams

- Clock, waiter, and `TopologyUpdater` seams are explicitly delineated. Waiters use
  `Thread.onSpinWait()` or bounded `parkNanos` compliant with "no arbitrary sleeps".

### 8. Anchored First-Future Recurrence

- Polling starts follow an anchored `t0 + kP` recurrence model.
- 0 -> 450 -> 600 ms boundary alignment is mandatory, averting clock drift.

### 9. Publication and Ordering

- Exactly one `release` store publication occurs per successful evaluation.
- Topology updates strictly precede listener notification.

### 10. Self-Stop/Close and Cleanup

- A monitor may call `stop()` or `close()` upon itself reentrantly without deadlock.
- All temporary allocations, executor hooks, and listener arrays clear on `CLOSED`.

### 11. Java Memory Model (JMM) Rules

- Volatile (`getAcquire`/`setRelease`) guarantees visibility across the state machine.
- Plain reads inside the critical section or loop evaluate freshness via opaque bounds.

### 12. Removal of 1 ns Timer

- The legacy mutation requesting 1 ns platform timer resolution is removed entirely.

### 13. Integration Fixtures

- Test boundaries use manual clock/waiter stubs and exactly verify the six-state transitions,
  cadence invariants, and coalesced logic without arbitrary sleeps.

## Constraints

- Do not reopen A-C contracts or modify their settled implementations.
- No arbitrary sleeps (`Thread.sleep`) for cadence, listener, shutdown, or race assertions.
- Platform collector bodies, unrelated core, native code, and training are prohibited.
- `Throwable` faults must be caught and isolated to prevent thread death without graceful shutdown.

## Developer Review Summary

- **Purpose**: Implement exact monitor lifecycle, anchored scheduling, and publication ordering for
  P4.
- **Boundaries**: Integration of P4-A/B/C components via `ResourceMonitor`, explicitly defined
  clock/waiter seams.
- **Child Work Units**: Six-state lifecycle engine, anchored recurrence scheduler, ordered
  publisher.
- **Implementation**: Confirmed `gpt-5.6-sol` at `high` reasoning effort.
- **Risks/Unresolved**: None. All duration, state, deadline, failure, publication, memory-mode, and
  cleanup decisions are bounded and finalized.

## Completion Record

### Changed Files
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/ResourceMonitor.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/monitor/MonotonicClock.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/monitor/DeadlineWaiter.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/monitor/TopologyUpdater.java`
- `euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/ResourceMonitorTest.java`

### Commands and Results
- `gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.ResourceMonitorTest"` - Passed.
- `gradle :euhedral-hardware-utils:test --rerun-tasks` - All 67 hardware-utils tests passed.

### Acceptance Evidence
Implements the six-state monitor lifecycle, 200 ms anchored recurrence with overrun handling, single release publication, topology integration, and clean teardown.

### Deviations
None.

### Environmental Limits
None.
