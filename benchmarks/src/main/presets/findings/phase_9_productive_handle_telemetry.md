# Phase 9 Findings: Productive-Handle Telemetry

This document records the implementation evidence and Phase 8 reinterpretation for Phase 9 of
the [Euhedral Tuning Process](../../EUHEDRAL_TUNING_PROCESS.md#12-phase-9---restore-productive-handle-telemetry-and-reframe-the-high-contention-model).

## Historical implementation

The previous sensor was recovered from git history rather than reverted wholesale:

- `3afaa650` introduced worker-local productive-handle observations and replaced the raw live-handle
  threshold in the former policy.
- `759f3b17` refined request and pull evidence, stop-condition handling, completion reconciliation,
  and benchmark visibility.
- `8df0c6d7` removed the sensor while replacing the former control policy with the current calibrated
  decision tree.

The recovered measurement semantics are:

1. Each shared upstream interceptor retains an independent observation for every worker thread.
2. A newly visible handle is optimistically productive until that worker acquires and services it.
3. A successful pull or synchronous request push marks the handle productive.
4. A completed acquired service that produces no work marks it nonproductive.
5. Failed lock acquisition supplies contention evidence but no productivity evidence.
6. A zero pull caused by a stop predicate restores the prior observation because source emptiness
   was not observed.
7. Completed handles are removed from the owner-local productive count exactly once.

The old controller compared productive handles strictly with registered workers. That control rule
was not restored. The current decision tree and its calibrated body/contention structures are
unchanged.

## Restored telemetry

`UpstreamQueue` now exposes the worker-local `productiveHandleCount`. At completed-batch boundaries,
the fragment records that count with the registered worker count and existing contention value.
The benchmark statistics pipeline derives, without capping:

```text
productiveHandleRatio = productiveHandleCount / registeredWorkers
```

`productiveHandleCount` and `productiveHandleRatio` are exported at fork, iteration, and core scope
for cycle-start, batch-progress, and batch-complete observations. They are also loadable from
completed runs, included in scalar comparisons, and correlated with contention and service or
throughput telemetry.

Contention and productivity remain independent:

```text
contention   = failed acquisitions / total acquisition attempts
productivity = useful work observed after successful acquisition
```

No productivity threshold, composite pressure signal, or third decision-tree axis was introduced.

## Phase 9 reproduction

The bounded reproduction preset is
[`22-productive-handle-telemetry.json`](../experiments/22-productive-handle-telemetry.json). It holds
body cost at the M landmark (`workUnits = 144`) and observes both `STAGED` and
`SKIP_THEN_STAGED` across the six Phase 9 geometries. The completed reproduction artifacts are under
`experiments/22-productive-handle-telemetry-reproduction`.
Matched diagnostic comparisons are defined by
[`22-productive-handle-telemetry-pcore.json`](../comparisons/22-productive-handle-telemetry-pcore.json)
and
[`22-productive-handle-telemetry-ecore.json`](../comparisons/22-productive-handle-telemetry-ecore.json).

An initial pilot that forced the staged-family action in every contention band is retained under
`experiments/22-productive-handle-telemetry`. It changed the closed-loop P-core contention regime,
so it was excluded from the Phase 8 reinterpretation. The reproduction restored Phase 8's DIRECT
low-band bootstrap and wrote to a new directory rather than modifying the completed pilot.

System-fork batch-complete steady-state telemetry reported the same productive count and ratio for
both policies in every fixture:

| Topology | Workers | Productive handles | Productive-handle ratio |
|----------|--------:|-------------------:|------------------------:|
| P-core   |       4 |                  4 |                   1.000 |
| P-core   |       4 |                  3 |                   0.750 |
| E-core   |       8 |                  4 |                   0.500 |
| E-core   |       8 |                  2 |                   0.250 |
| P-core   |       4 |                  1 |                   0.250 |
| E-core   |       8 |                  1 |                   0.125 |

The one-fork telemetry reproduction is not a replacement throughput calibration. Its contention
means moved with the closed-loop action trajectory, especially on the P-core fixtures, while the
productive ratios remained fixed by useful source availability. This is direct evidence that the
two measurements describe different physical properties and that Phase 8 contention thresholds
must not be refined from this reproduction.

## Reframed Phase 8 geometry

Combining the authoritative Phase 8 comparisons with the restored Phase 9 ratios gives the following
M-body view:

| Topology | Workers / productive handles | Phase 8 contention | Ratio | Phase 8 staged-family result |
|----------|------------------------------:|-------------------:|------:|--------------------------------|
| P-core   |                         4 / 4 |             ~74.5% | 1.000 | `STAGED` +5.97% (multi-fork) |
| P-core   |                         4 / 3 |             ~84.8% | 0.750 | approximate parity; skip +0.66% (multi-fork) |
| E-core   |                         8 / 4 |             ~82.2% | 0.500 | `SKIP_THEN_STAGED` +26.57% (multi-fork, topology-specific) |
| E-core   |                         8 / 2 |             ~94.0% | 0.250 | `SKIP_THEN_STAGED` +39.70% (single-fork) |
| P-core   |                         4 / 1 |             ~98.9% | 0.250 | approximate parity; `STAGED` +1.60% (single-fork) |
| E-core   |                         8 / 1 |             ~97.9% | 0.125 | `SKIP_THEN_STAGED` +29.13% (single-fork, topology-specific) |

Scalar contention does not distinguish the productive deficit, and equal ratios do not erase
topology differences: 8 E-core / 2-handle and 4 P-core / 1-handle fixtures both have ratio 0.25 but
different contention and policy outcomes. The E-core single-handle result also prevents a monotonic
portable rule from being inferred from ratio alone.

The supported Phase 9 conclusion is therefore limited but useful: high-contention behavior should
be modeled against both contention and productive-handle ratio in Phase 10, with P-core and E-core
evidence separated. No production boundary changes are justified yet.
