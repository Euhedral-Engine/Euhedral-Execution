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

| Branch | Productive path                                                        | Important effect                                                                                                               |
|--------|------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| Direct | local cache -> remote cache -> upstream `pull` -> execute              | The upstream handle remains acquired while `pull` invokes the execution consumer.                                              |
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

1. Follow [
   `startup-fragment-path-calibration.md`](../blueprints/startup-fragment-path-calibration.md)
   and implement only the forced-mode fixture and diagnostic benchmark.
2. Run its focused Core and benchmark tests, then the JMH comparison with recorded hardware, JVM,
   batch cap, raw samples, medians, means, and confidence intervals.
3. Record either `supported` or `disproved`. Do not implement a startup threshold in the same pass.

## Next Stage

Plan the next fragment-control blueprint around experimental decision-tree discovery from
`docs/blueprints/startup-fragment-path-calibration.md`

The goal is no longer to jump directly from one benchmark result to a production threshold. The
broader idea is to build the scheduler policy incrementally as an explicit decision tree whose
branches are discovered and justified through controlled benchmarks.

Start with the simplest known split, vary one meaningful input at a time, and only add a new branch
when the current tree cannot explain a resolved change in which execution path wins.

Current known execution paths:

* Tier 1:

    1. Execute owner-local cached work first.
    2. Execute remote cached work directly.
    3. Request upstream work and pull it directly for execution.

* Tier 2:

    1. Request upstream work first.
    2. Execute owner-local cached work.
    3. Execute remote cached work through the cache path.
    4. Execute remaining remote work directly.

Tier 1 is the lower-overhead path. Tier 2 changes the ordering and uses the request/cache path more
aggressively. Both paths can consume local and remote work, so do not reduce this to "direct versus
cached execution." The relevant behavior is the complete ordering of request, local-cache execution,
remote-cache handling, and direct remote execution.

The first feasibility experiment established:

* intrinsic Tier 1 overhead is about 11.3 ns/frame;
* intrinsic Tier 2 overhead is about 28.0 ns/frame;
* no-op work is about 0.285 ns/op and clearly favors Tier 1;
* fixed CPU work is about 225 ns/op and favors Tier 2 under the predeclared rule;
* Tier 2 CPU throughput is extremely stable around 7.44-7.47 million frames/s across plentiful and
  scarce source shapes;
* scarce Tier 1 CPU throughput is extremely stable around 4.25 million frames/s;
* plentiful Tier 1 CPU throughput is strongly bimodal across JVM forks, with one regime around 4.24
  million frames/s and another around 8.5 million frames/s;
* plentiful Tier 1 no-op shows a similar bimodality, around 81 million versus 144 million frames/s;
* startup versus warmed intrinsic-path differences are small, so ordinary warmup does not explain
  the bimodality;
* similar inflection behavior has previously been observed on both homogeneous and heterogeneous CPU
  architectures.

Do not treat the bimodality as ordinary benchmark noise. It is too discrete and repeatable within
each fork.

The strongest hypothesis to investigate next is that Tier 1 has two stable worker-participation
regimes.

For the 225 ns CPU workload:

* one execution lane has a theoretical ceiling near 4.44 million ops/s;
* two execution lanes have a theoretical ceiling near 8.88 million ops/s;
* the observed Tier 1 regimes at roughly 4.24 million and 8.5 million frames/s closely match one
  productive worker versus two productive workers.

The no-op results point in the same direction: the low Tier 1 regime is close to isolated one-worker
Tier 1 throughput, while the high regime appears to reflect useful participation from both workers.

The working hypothesis is therefore:

Tier 1 performance has an inflection based on effective worker participation. Under some
source/request/cache conditions, its ordering may leave only one worker productively supplied. Under
others, both workers remain productively supplied. Tier 2's request-first ordering and stronger use
of the cache path may make work availability more stable across workers, explaining why its CPU
throughput remains nearly constant while Tier 1 exhibits distinct low- and high-throughput regimes.

This is only a hypothesis. The next blueprint must design experiments that can prove or falsify it
before changing production policy.

Primary next experiment:

Add benchmark-only per-worker completed-frame accounting to the existing forced-path fixture and
determine whether the two Tier 1 regimes correspond to different worker participation.

Expected evidence if the hypothesis is correct:

* low Tier 1 regime: approximately one worker accounts for nearly all useful work;
* high Tier 1 regime: both workers account for substantial work, approximately doubling aggregate
  throughput for CPU-heavy work.

If that is confirmed, drill down systematically to identify what selects the participation regime.

Candidate independent variables include:

1. source count;
2. source-to-worker ratio;
3. source ownership or assignment;
4. insertion/order effects;
5. worker/core affinity;
6. which worker receives or pulls work first;
7. upstream handle serialization or independent pullability;
8. request timing and request ownership;
9. local-cache occupancy;
10. remote-cache availability and ownership;
11. whether work is consumed directly from the remote path or enters the cache path first;
12. routing/hash decisions established during graph construction;
13. effective source parallelism relative to execution parallelism.

Do not assume "scarce versus plentiful" is itself the final decision-tree feature. Treat it as the
variable that exposed the phenomenon. Prefer discovering the underlying physical condition, such as
effective source parallelism being lower than available execution parallelism, or Tier 1's
request/pull ordering failing to expose enough work to all workers under a specific topology.

The blueprint should define an iterative experimental method:

1. Start from the current smallest decision tree.
2. Select one unresolved leaf or unexplained regime.
3. State the simplest physical hypothesis explaining it.
4. Design the smallest controlled benchmark that can falsify that hypothesis.
5. Change one variable at a time when practical.
6. Preserve raw per-fork and per-worker evidence.
7. Add a decision-tree split only when a variable produces a reproducible, meaningful winner or
   behavior reversal.
8. Once a split is established, recursively investigate only the leaves that still contain
   materially different behavior.
9. Stop splitting when additional variables do not meaningfully change the execution-path decision.

The intended eventual policy should remain an explicit, understandable decision tree. Benchmarking
may discover machine-specific thresholds inside that tree, but do not turn this into:

* an ML model;
* a 28-dimensional search problem;
* a general adaptive controller;
* continuous online experimentation;
* a large coordination subsystem;
* arbitrary source classification based on labels rather than measurable runtime structure.

The decision tree should encode physical scheduler behavior that can be explained from measurements.

The next blueprint is investigative only. It should specify:

* exact hypotheses;
* benchmark fixtures;
* diagnostic counters;
* variables to sweep;
* controls;
* evidence needed to accept or reject each hypothesis;
* how to distinguish worker-participation changes from cache, affinity, JIT, request-ordering,
  lifecycle, or timing artifacts;
* how results feed into the next decision-tree split;
* explicit stop conditions;
* the minimal production implications if the hypothesis is eventually confirmed.

Do not implement the production policy in this phase.

The immediate objective is to explain the Tier 1 4.24M versus 8.5M CPU throughput regimes and
determine whether effective worker participation is the next real branching variable in the
scheduler decision tree.
