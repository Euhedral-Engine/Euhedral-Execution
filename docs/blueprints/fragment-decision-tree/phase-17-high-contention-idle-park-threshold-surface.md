# Phase 17: High-Contention Idle Park/Threshold Surface Completion Record

Status: completed - retained for light body work

Implementation intensity: high

## Production policy

The retained branch is evaluated at the existing safe worker-cycle boundary, after reset service and
the independent extremely-cheap idle branch, but before upstream counts, cache execution, handle
acquisition, or pull traversal. A completed batch publishes one owner-local eligibility decision.
The worker clears that decision before one finite park and must complete another batch before it can
park again. It never parks while holding a handle or executing a frame.

The retained defaults are:

```text
high-contention threshold = 980_000
park duration              = 15_000 ns
light-body maximum         = 200 ns
light-body minimum         = greater than the unchanged 20 ns cheap-idle boundary
```

The branch requires initialized body history, initialized acquisition contention, contention at or
above the threshold, and body cost in `(20, 200] ns`. It does not change DIRECT/STAGED selection.
The existing `650_000`, `90/95 ns`, and `20 ns` constants are unchanged.

Rank zero is never eligible. No election, coordination, atomic, or global contention aggregate was
added. Every other rank consumes only its own completed-batch observation. A stale observation can
cause at most one finite park before that worker must participate and complete another batch.

Startup-fixed developer overrides are:

```text
euhedral.fragment.highContentionIdle.threshold=disabled|0..1000000
euhedral.fragment.highContentionIdle.parkNanos=<positive long>
euhedral.fragment.highContentionIdle.bodyCostMaxNs=<finite value greater than 20>
```

`threshold=disabled` is the comparison/kill switch. Raising `bodyCostMaxNs` allows a later
heavy-body experiment without a source edit. Heavy-work threshold and park defaults are deliberately
not claimed by this phase.

## Adaptive search method

The controlled surface used one repeating meaningful-work source, 23 registered workers, 256 body
rounds, natural handles, normal selection, and all 23 participating workers. Body estimates were
about `250/434/437 ns` min/median/max and all lanes selected STAGED. The initial body-independent
branch was used so the threshold/park mechanism could be mapped before the final light-body scope
was selected.

Search order:

1. For thresholds 750k, 850k, 900k, 950k, and 980k, test 1 us, the full-range geometric midpoint
   31.623 us, and 1 ms.
2. Retain both sides and test 5.623 us and 177.828 us. The short side was promising; the long side
   changed materially with threshold.
3. Refine the central intervals at 13.335 us and 74.989 us.
4. Refine the useful short band at 8.660 us and 20.535 us for the low, middle, and high threshold
   anchors. Intermediate thresholds were pruned because they no longer produced a distinct region.
5. Validate simple 15 us finalists at 950k and 980k with three forks.

The sweep preserved every point. Exploratory points used one fork with one 1-second warmup and two
1-second measurements; their two values are iteration means and JMH uncertainty is undefined. The
finalists used three forks, two 1-second warmups, and three 2-second measurements.

## Complete controlled throughput surface

Values are Mframes/s. A dash means the adaptive search did not retain that threshold at that local
refinement.

| Threshold |   1 us | 5.623 us | 8.660 us |  13.335 us | 20.535 us | 31.623 us | 74.989 us | 177.828 us |   1 ms |
|----------:|-------:|---------:|---------:|-----------:|----------:|----------:|----------:|-----------:|-------:|
|      750k | 10.581 |   16.466 |   12.475 | **20.792** |    18.087 |    15.195 |    11.447 |      9.951 |  7.658 |
|      850k | 10.533 |   13.793 |        - | **20.727** |         - |    15.725 |    12.795 |     11.139 |  9.333 |
|      900k | 11.256 |   16.061 |   15.385 | **19.535** |    18.048 |    16.586 |    14.262 |     12.492 | 10.269 |
|      950k | 10.560 |   10.839 |        - | **20.656** |         - |    18.221 |    15.655 |     14.270 | 11.694 |
|      980k | 10.585 |   14.498 |   13.398 | **20.372** |    20.009 |    19.185 |    16.728 |     15.704 | 13.111 |

The disabled exploratory reference was 10.676 Mframes/s. The 950k/15 us finalist produced
`20.191/20.124/20.149 Mframes/s`, pooled `20.155 +/- 0.087`. The 980k/15 us finalist produced
`20.305/20.370/20.291`, pooled `20.322 +/- 0.110`. The disabled finalist produced
`10.902/10.400/11.909`, pooled `11.071 +/- 1.178`.

### Per-threshold response classification

- **750k:** multi-region/discrete at short parks. The useful region is about 5.6-31.6 us, with the
  stable best region at 13-20 us. Parks at and above 75 us regress; 1 ms is sharply harmful.
- **850k:** broadly unimodal. The useful region is about 5.6-75 us, with the best observed point at
  13.335 us. The long tail approaches neutral near 178 us and regresses at 1 ms.
- **900k:** unimodal with noisy/discrete behavior below 10 us. The stable useful region is about
  13-75 us, best at 13.335 us.
- **950k:** unimodal. The useful region is about 13 us through at least 178 us; 13-32 us is the best
  region. The 1 ms endpoint is only weakly useful.
- **980k:** one broad useful region from about 13 us through 1 ms, with a 13-32 us plateau and a
  monotonic decline after it. This threshold preserves useful behavior for the widest park range.

The 5-9 us region was discarded as a production choice because several points had large iteration
swings. The region above 75 us was not refined further because it was consistently below the main
plateau, even though higher thresholds made it less harmful. The 13-20 us band was retained because
it was strong across every threshold and stable at the measured anchors.

## Contention and worker participation surfaces

The disabled reference contention was `962694/999985/999985` min/median/max. At 1 us every enabled
threshold remained essentially saturated. At 31.623 us the ranges were:

| Threshold | Contention min/median/max | Workers with parks | Park-count min/median/max |
|----------:|:--------------------------|:-------------------|:--------------------------|
|      750k | 866355/943568/996685      | 22/23              | 0/51357/63426             |
|      850k | 761811/954647/992954      | 22/23              | 0/50829/62946             |
|      900k | 798468/950185/994642      | 22/23              | 0/47019/59111             |
|      950k | 910463/985365/999974      | 22/23              | 0/42896/54845             |
|      980k | 888172/996610/999981      | 22/23              | 0/40744/53365             |

At 1 ms, contention medians fell to 799k, 918k, 914k, 957k, and 984k as threshold increased, but
throughput was below the short-park plateau. The most productive 13.335 us points generally retained
near-saturated contention; all thresholds parked 22 of 23 ranks. Thus, throughput is authoritative:
lowering the triggering EWMA was not necessary for the win.

All 23 workers completed work at every retained row. Rank zero had zero contention parks and became
the largest lane at about 10.5 percent of completions in the 980k/15 us finalist. Other workers
continued to execute and repeatedly re-entered; no permanent single-worker state formed. Acquisition
success and completion moved among ranks. This is consistent with temporary staggered participation,
but it does not prove temporal phase separation. The evidence cannot cleanly separate fewer
instantaneous competitors from phase-shifted re-entry.

Every final snapshot in the heavy surface remained STAGED. The branch did not alter the existing
DIRECT/STAGED selector or its distribution.

## Threshold/park interaction

Park duration dominates inside the main 13-20 us plateau: throughput varies little with threshold
there. At 13.335 us, all five thresholds fall between 19.535 and 20.792 Mframes/s. Threshold becomes
important as parks lengthen. At 1 ms, throughput rises monotonically from 7.658 Mframes/s at 750k to
13.111 Mframes/s at 980k. A low threshold plus a long park removes too much execution capacity;
requiring contention closer to saturation makes the same long wait less harmful.

The surface therefore supports this smallest relationship:

```text
light meaningful body + near-saturated contention
    -> park nonzero ranks once for a short 13-20 us interval

longer park
    -> require a higher contention threshold
```

No adaptive coupling is implemented. The fixed production point is 980k/15 us because it lies inside
the shared short-park plateau and was substantially safer than 950k at the four-source boundary.

## Production finalist controls

The first finalist was body-independent. That experiment discovered an important compatibility
boundary and motivated the retained light-body ceiling.

| Workload                                | Disabled | Enabled result                 | Interpretation                                                  |
|:----------------------------------------|---------:|:-------------------------------|:----------------------------------------------------------------|
| 1 source, 256 rounds, three-fork pooled |   11.071 | 20.155 at 950k; 20.322 at 980k | Both finalists stable and about 82-84 percent faster            |
| 4 sources, 96 rounds, three-fork pooled |   43.261 | 41.280 at 950k; 42.617 at 980k | 950k regressed 4.58 percent; 980k limited it to 1.49 percent    |
| 1 source, no-op                         |   32.758 | 32.661 at 950k                 | Existing cheap-idle branch remains authoritative; -0.30 percent |
| 8 sources, 256 rounds                   |   38.257 | 38.215 at 950k                 | Neutral, -0.11 percent                                          |
| 16 sources, 256 rounds                  |   45.430 | 44.721 at 950k                 | One-fork -1.56 percent with only startup parks                  |
| 32 sources, 96 rounds                   |   90.694 | 99.810 at 950k                 | No material park activity; host dispersion, not credited        |

The maintained high-contention throughput control was `0.42803 ops/ns` disabled and
`0.42725 ops/ns` at 980k/15 us, a neutral -0.18 percent.

### Light-body retention gate

The ungated policy materially regressed per-pixel Mandelbrot, so it was not retained as a universal
policy. The user-directed production candidate added the startup-fixed 200 ns light-body maximum.

The gated one-source/96-round row had body estimates around `101/173/175 ns`, remained STAGED, and
produced:

```text
980k / 15 us / 200 ns max:
    13.454 / 14.485 / 10.706 Mframes/s

disabled:
    9.752 / 10.593 / 10.015 Mframes/s
```

The pooled gain was 27.3 percent. All three candidate forks beat every disabled fork, although one
candidate fork retained the known lower discrete regime.

The gated one-source/256-round row had body estimates `250/434/437 ns`, contention at 0.999985, and
exactly zero contention parks on every worker. Its quick result, 10.128 Mframes/s, lies inside the
prior disabled fork range and is not interpreted as a branch effect.

## Actual workloads

Lower time is better. Each Mandelbrot result is one complete 8K degree-2 render.

| Workload             |      Disabled | Ungated 980k/15 us | Gated 980k/15 us/200 ns | Result                                                                   |
|:---------------------|--------------:|-------------------:|------------------------:|:-------------------------------------------------------------------------|
| Mandelbrot per pixel | 354.760 ns/op |      377.691 ns/op |           350.613 ns/op | Ungated regressed 6.46 percent; gate changed this to 1.17 percent faster |
| Batched Mandelbrot   | 390.308 ns/op |      390.639 ns/op |           387.673 ns/op | Ungated neutral; gated 0.68 percent faster                               |

These actual workloads are the reason heavy-body eligibility is excluded by default. A different
heavy threshold/park surface may exist, but this phase does not claim or implement it.

## Dynamic transition and safety

A two-worker abundant -> scarce -> abundant diagnostic used threshold 750k so the two-worker scarce
phase could cross the branch. The nonzero rank's park count moved `0 -> 97 -> 99`. Its contention
moved `12608 -> 705242 -> 559302`; both workers returned to DIRECT and active participation in the
last phase. The two residual parks after abundance returned demonstrate the bounded
stale-observation behavior; the count then stopped. Rank zero never parked.

No source failed to complete, no worker starved, no all-worker park occurred, and no shutdown or
reset timed out. Existing close interrupts and unparks the owner thread. Reset is serviced
immediately after a timed or explicit wake and clears eligibility, parked state, and park count on
the owner thread.

Deterministic tests cover below/at threshold, uninitialized history and contention, rank-zero
protection, body-range edges, disabled comparison, finite return, repeated re-evaluation, reset, and
the unchanged cheap-idle behavior. Existing lifecycle tests cover reset and shutdown wake mechanics.

## Numbered completion record

1. **Branch placement:** worker-cycle boundary before upstream/cache/pull traversal.
2. **Protected poller:** registered rank zero is never eligible.
3. **Threshold adjustment:** startup property with `disabled` sentinel; default 980k.
4. **Park adjustment:** startup positive-long property; default 15,000 ns.
5. **Thresholds tested:** disabled, 750k, 850k, 900k, 950k, 980k.
6. **Parks tested:** 1, 5.623, 8.660, 13.335, 15, 20.535, 31.623, 74.989, 177.828, and 1000 us.
7. **Controlled matrix:** complete throughput table above; raw logs retain all snapshots and counts.
8. **Throughput surface:** broad 13-20 us plateau; long parks increasingly favor high thresholds.
9. **Contention surface:** short winning parks often leave EWMA saturated; long parks lower it more.
10. **Participation:** all workers progress; rank zero dominates modestly; other ranks
    rotate/re-enter.
11. **Interaction:** weak threshold sensitivity on the main plateau, strong sensitivity for long
    parks.
12. **Local refinement:** sub-10 us is discrete; 13-20 us is stable; above 75 us is a declining
    tail.
13. **No-op:** one-source no-op -0.30 percent; existing cheap branch remains independent.
14. **Meaningful work:** gated light row +27.3 percent pooled; universal heavy row about +84
    percent.
15. **Actual work:** ungated Mandelbrot rejected; gated Mandelbrot and batched Mandelbrot positive.
16. **Dynamic transition:** parks start in scarcity and stop after two bounded residual waits.
17. **Phase separation:** staggered participation is consistent with observations but not proven.
18. **Starvation/wake synchronization:** none observed.
19. **Discrete regimes:** 5-9 us and one light finalist fork; neither defines the production point.
20. **Production threshold:** 980,000.
21. **Production park:** 15,000 ns.
22. **Optimum shape:** broad 13-20 us plateau, not a sharp integer optimum.
23. **Raw evidence:** `/tmp/euhedral-phase17-20260813.t578lJ` contains every JSON and log named
    `anchor-*`, `refine1-*`, `refine2-*`, `refine3-*`, `finalist-*`, `controls-*`, `boundary4-*`,
    `light-final-*`, `heavy-gated-*`, `mandelbrot-*`, `batched-mandelbrot-*`, `high-contention-*`,
    and `dynamic-*`. The first interrupted `anchor-980000-31623.json`,
    `finalist-980000-15000.json`, and `mandelbrot-disabled-15000.json` were overwritten by completed
    reruns; their logs document the harness interruption and replacement.
24. **Keep/remove:** keep the one-shot branch only for `(20, 200] ns` body estimates. Retain
    developer overrides for threshold, park, and body maximum. Do not enable it for heavy work by
    default.

### Outcome 1: retain with stable parameter region

High-contention parking materially improves the light meaningful workload, the stable 13-20 us park
region exists across the high-threshold surface, and the 200 ns production gate removes the actual
heavy-work regression. Retain 980k/15 us/200 ns as the conservative fixed policy.
