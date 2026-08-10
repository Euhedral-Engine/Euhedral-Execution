# Startup Fragment Path Calibration

## Status

- Date: 2026-08-10
- Planning intensity: maximum
- Blueprint intensity: high
- Feasibility implementation intensity: medium
- Production startup calibration: not approved by this plan

Planning remains maximum because the proposed threshold crosses the fragment hot loop, source-handle
ownership, pinned-worker startup, and JVM compilation lifecycle. The only implementation selected
here is a bounded benchmark experiment, so its intensity is medium.

## Objective

Determine whether the no-op cost of the direct fragment path is sufficient to predict when the
staged path has higher system throughput. Do this before adding startup calibration or replacing the
current policy.

The idea is viable only if one fixed threshold derived from direct no-op overhead predicts the
winning path for both plentiful and scarce upstream sources. A result that depends on source shape,
worker fan-out, batch size, or calibration warmup disproves that simple policy.

## Requirements and Boundaries

In scope:

- Measure the existing direct and staged branches with no-op `BenchmarkFrame` work.
- Compare forced direct and forced staged execution for one cheap and one CPU-work fixture.
- Test one plentiful-source shape and one scarce-source shape.
- Reuse the actual request, routing, cache, pull, and execution graph.
- Keep any mode pin package-private, setup-only, and absent from normal decisions.

Out of scope:

- A production startup calibrator in this pass.
- Online production probes, controller threads, socket state machines, source classification, or
  continuous threshold optimization.
- Batch-size redesign, new public configuration, `FragmentActionPicker`, `euhedral-training`, or
  policy-vector changes.
- Changes to routing, ordered-frame behavior, pressure caps, reset ownership, or cache memory
  semantics.

## Current-State Findings

The production branches are not two interchangeable calls with different fixed overhead:

| Branch | Productive path | Important effect |
|---|---|---|
| Direct | local cache -> remote cache -> upstream `pull` -> execute | The upstream handle remains acquired while `pull` invokes the execution consumer. |
| Staged | `request` -> local cache -> remote cache -> upstream `pull` -> execute | Unordered requested frames can be routed into caches, allowing sibling workers to execute after the source handle is released. |

Ordered frames already stop direct pulling and use the established request path. Both branches also
retain their current local-cache-first rule and direct/remote fallbacks.

The current service sample is not pure frame work. `ControlPlaneFragment` times each successful
local-cache, remote-cache, or upstream execution call. Those intervals include the selected queue or
pull path plus downstream execution, while staged `request()` time is excluded. The estimate is
therefore mode-dependent and cannot be compared directly with a no-op direct-path threshold.

The cheapest possible residual estimate would be `max(0, measured path ns/frame - calibrated path
ns/frame)`. The current aggregate cannot support that subtraction: one sample can mix local, remote,
and direct frames, and a staged request can route frames for sibling workers without completing them
on the requesting worker. Measuring only the frame body at `AbstractExecutor` would require timer
reads per frame, which is not acceptable on the no-op path. There is therefore no approved cheap
work-only estimator yet.

Let `H1` and `H2` be direct and staged scheduler cost, `W` be frame work, and `P1` and `P2` be the
effective parallelism each branch achieves. The approximate system costs are:

```text
direct = (H1 + W) / P1
staged = (H2 + W) / P2
```

If `P1 == P2` and `H2 > H1`, direct always wins; there is no work-cost crossover. A crossover exists
only when staging changes effective parallelism. It then depends on `H2` and `P1 / P2`, not on `H1`
alone. In this graph, the primary missing variable is how much parallelism staging unlocks, which in
turn depends on independent upstream-handle availability, active workers, routing, and cache state.

The reported 400 million no-op operations per second also rules out per-frame timing. That aggregate
rate is 2.5 ns per completed operation of wall-clock throughput. Even when work is spread across
cores, two timer reads per frame would be a material part of the cheap path. Any runtime estimate
must remain batch-aggregate.

There is no clean cold-start measurement point today. `firstTouch()` runs pinned before the complete
pipeline is connected. The real paths become available only after clones, distributors, downstream
executors, and worker-local upstream queues exist. At that point workers are already running, and a
synthetic calibration source would need a startup barrier before ingest becomes ready. Calibration
before the fragment loop reaches its compiled steady state would measure interpreter or tiered-JIT
behavior rather than the path used later.

If intrinsic overhead alone were useful, it would belong to each pinned fragment and use plain
owner-thread state. A JVM-wide value is invalid on multi-socket machines, and a socket-wide value
still mixes heterogeneous core types such as the P-cores and E-cores on the benchmark host. Per-core
calibration avoids new shared state, but it still cannot measure the socket-level parallelism gained
by staging. Changing the storage granularity does not supply the missing variable.

## Selected Direction

Do not implement production startup calibration yet. First add one diagnostic JMH benchmark that
pins the existing branches for a complete trial and tests the proposed predictor against source
availability.

The benchmark is a falsification test, not a tuning matrix:

1. Measure forced direct and forced staged no-op throughput with the same topology and batch cap.
2. Measure work-only cost for the cheap and CPU-work fixtures.
3. Measure both forced branches for plentiful and scarce sources at each work cost.
4. Predict direct when measured work is below direct no-op overhead and staged otherwise.
5. Compare that prediction with the observed throughput winner.

Use an odd median of raw samples for the calibration value. Nine samples is the initial bounded
fixture; JMH warmup and at least three forks remain the evidence for steady-state comparisons. The
staged no-op median is reported as a sanity check, including `H2 - H1`, but is not forced into the
proposed rule.

No smoothing or hysteresis can repair a missing predictor. If the hypothesis survives, a later
production blueprint may reuse the current owner-local one-eighth EWMA and completed-batch streak;
it should not add a second statistical controller.

## Success and Stop Conditions

The simple policy remains viable only when all of these hold across forks:

- direct no-op overhead and staged no-op overhead have stable medians after warmup;
- staged no-op cost is explainably higher in the single-worker sanity case;
- measured cheap and CPU-work costs lie on opposite sides of the direct threshold;
- the threshold predicts the faster forced mode for both source shapes; and
- changing only source availability does not reverse the winning mode at a fixed work cost.

Return to design, rather than changing constants, if any condition fails. In particular, a winner
reversal between plentiful and scarce sources proves that the missing utilization variable matters.
A cold-start median that materially differs from the warmed JMH median proves that startup/JIT state
also matters.

Passing the benchmark does not authorize production implementation. It authorizes a small follow-up
blueprint that settles the pinned calibration phase, work-cost subtraction, and startup failure
behavior with the new evidence.

## Work Sequence

1. Follow [`startup-fragment-path-calibration.md`](../blueprints/startup-fragment-path-calibration.md)
   and implement only the forced-mode fixture and diagnostic benchmark.
2. Run its focused Core and benchmark tests, then the JMH comparison with recorded hardware, JVM,
   batch cap, raw samples, medians, means, and confidence intervals.
3. Record either `supported` or `disproved`. Do not implement a startup threshold in the same pass.
