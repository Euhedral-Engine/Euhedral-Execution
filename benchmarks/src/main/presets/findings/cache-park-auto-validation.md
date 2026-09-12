# CACHE park automatic-participation findings

## Conclusion

The evidence supports **814375 ns as a candidate for a workload-conditional
CACHE park policy**. Its demonstrated benefit is in the **single-source,
zero/lighter-body-work cases**: +57.59% at 0 work units and +27.81% at 96 work
units, with higher mean throughput in all four passes at both points. This
benefit should be preserved in the conclusion even though applying the longer
park everywhere would regress other workloads.

The useful policy distinction is where to use a longer CACHE park and where to
retain a shorter one. At 17 sources and zero work, 15000 ns clearly wins. At 23
sources and zero work, it favors mean throughput while 814375 ns favors the
minimum observed window. Heavier-body-work cases do not show a consistent
throughput benefit from the longer park. The earlier forced-participant results
remain evidence for that separate operating condition; automatic participation
changes the context in which the park duration is evaluated.

These are sampled operating regions, not calibrated switching boundaries. Only
source counts 1, 17 and 23 and work weights 0, 96 and 576 were tested. In
particular, the two beneficial single-source points do not establish that every
work weight between 0 and 96 benefits, or how far the benefit extends toward
higher source counts.

The user's throughput-first criterion gives decisive results in opposite
directions:

- **1 source, 0 work:** the 814375 ns minimum measurement-window throughput is
  22.594 million executions/s, above the 15000 ns mean of 15.287 million/s.
  The candidate wins even under the literal minimum-window criterion.
- **17 sources, 0 work:** the 15000 ns minimum window is 805.435 million/s,
  above the 814375 ns mean of 754.018 million/s. The existing default wins
  under the same criterion.

These conclusions use throughput and observed lower-tail performance, not CV
as an independent score. No single duration dominates across all nine workloads.
Do not combine their throughputs into one average that hides regressions.

## Where the longer park helps

This map summarizes the sampled source-count/body-work surface. All cells use
automatic participation on the same host with 23 expected physical workers.

| Parallel sources | Zero work: 0 | Lighter work: 96 | Heavier work: 576 |
| ---: | --- | --- | --- |
| 1 | Longer park wins strongly | Longer park improves mean and both observed minima; overlapping distributions | Benefit unestablished; pooled gain but loses three passes |
| 17 | Shorter park wins strongly | No demonstrated material benefit | No demonstrated material benefit |
| 23 | Shorter park favors mean; longer park favors minimum window | Inconclusive due to differing slow-fork timing | No demonstrated material benefit |

**Supported beneficial region:** severe configured source scarcity with zero
or lighter synthetic body work, represented by the 1-source/0-work and
1-source/96-work points. At both points, the longer park raises mean throughput
and both observed minima. Only the zero-work point meets the stronger
minimum-above-competing-mean criterion. Higher variance at 96 work units does
not negate its repeated throughput gain.

**Region favoring the shorter park:** zero body work with many configured
sources, represented most strongly by 17 sources. The 23-source point also
favors shorter-park mean throughput, but its lower-tail tradeoff prevents a
blanket claim that the shorter park is better on every objective there.

**Region without established benefit:** the heavier-work points at all three
source counts, and the lighter-work points at 17 and 23 sources. Small pooled
differences or better isolated extrema are insufficient to assign these cells
to a longer-park policy. This is not proof of equivalence or of zero effect.

The resulting policy hypothesis is to use a longer CACHE park in a
source-scarce, low-body-work operating region and a shorter park outside it
unless further evidence supports another choice. This is a hypothesis to
calibrate, not a runtime rule derived from source count alone. Configured source
scarcity is a fixture descriptor; productive-handle count, active participation
and contention were not measured here.

## Evidence and validation

Sources are the completed artifacts under `experiments/cache-park-auto-validation`:

- `comparisons/throughput_by_workload.tsv`
- `comparisons/throughput_by_workload_pass.tsv`
- All 72 retained `trial_config.json` and `benchmark_output.log` files.

Recomputed the fork means, medians, minima, sample variances, sample counts,
dominance flags, and pass-win counts from the raw JMH `executions` measurements.
The nine workload summaries and all 36 paired-pass means/minima agree with the
saved comparison tables. All 72 expected workload/park/pass combinations are
present, with one JVM fork and five measurement windows each: 360 windows total.
Each workload/park treatment has four independent fork means and 20 windows.

Resolved fixtures match except for the intended source count, work units and
park duration. Both arms use `productivityGateMode: AUTO`, no forced active
participant cutoff, and disabled observers. JMH and JVM identity are consistent
across all runs: JMH 1.37, OpenJDK 21.0.2. Warmup measurements are excluded and no
slow measurement is discarded. Continuous windows within a fork are dependent;
they are not additional independent replications.

See the [experiment configuration](../experiments/cache-park-auto-validation.json)
and [comparison configuration](../comparisons/cache-park-auto-validation.json).

## Mean throughput by workload

Throughput is million executions/s. Delta is candidate relative to the current
default. Pass wins count how many of the four matched workload/pass blocks have
a higher candidate mean; they are descriptive, not a significance test.

| Sources | Work units | 15000 ns mean | 814375 ns mean | Candidate delta | Candidate pass wins | Interpretation |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 0 | 15.287 | 24.092 | +57.59% | 4/4 | Clear candidate win, including minimum-window criterion |
| 1 | 96 | 8.616 | 11.012 | +27.81% | 4/4 | Consistent mean gain; distributions still overlap |
| 1 | 576 | 23.910 | 24.330 | +1.76% | 1/4 | Pooled gain does not reproduce in most passes |
| 17 | 0 | 819.320 | 754.018 | -7.97% | 0/4 | Clear regression, including minimum-window criterion |
| 17 | 96 | 120.505 | 120.896 | +0.32% | 3/4 | Small mixed difference; no demonstrated useful gain |
| 17 | 576 | 25.645 | 25.607 | -0.15% | 1/4 | Small difference; no demonstrated useful gain |
| 23 | 0 | 1121.075 | 1059.754 | -5.47% | 1/4 | Mean regression with a better candidate minimum window |
| 23 | 96 | 126.974 | 126.261 | -0.56% | 1/4 | Near-equal pooled means conceal slow forks in different passes |
| 23 | 576 | 25.634 | 25.629 | -0.02% | 1/4 | Essentially unchanged observed mean |

The small differences are not formal equivalence findings. Four forks provide
limited precision, especially for variance and extrema.

## Observed lower-tail throughput

All values are million executions/s. A minimum fork mean is the lowest of four
independent fork averages. A minimum window is the lowest of 20 measurement
windows. These are observed minima, not guaranteed future throughput bounds.

| Sources | Work units | 15000 ns min fork | 814375 ns min fork | 15000 ns min window | 814375 ns min window |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 0 | 13.727 | 22.781 | 13.639 | 22.594 |
| 1 | 96 | 7.365 | 8.347 | 7.212 | 7.968 |
| 1 | 576 | 22.241 | 22.589 | 16.274 | 19.400 |
| 17 | 0 | 807.669 | 726.450 | 805.435 | 683.122 |
| 17 | 96 | 116.331 | 117.071 | 114.501 | 112.107 |
| 17 | 576 | 25.572 | 25.532 | 25.303 | 25.222 |
| 23 | 0 | 1049.867 | 1038.284 | 797.164 | 1033.222 |
| 23 | 96 | 92.710 | 91.459 | 92.434 | 90.670 |
| 23 | 576 | 25.526 | 25.536 | 25.222 | 25.120 |

Only the 1-source/0-work candidate win and 17-source/0-work baseline win satisfy
minimum-above-other-mean, at both fork and window levels. Neither arm satisfies
that criterion in the other seven workloads.

## Important workload details

**1 source, 96 work units:** the candidate improves mean throughput in every
pass, with gains from 8.62% to 78.91%. Its fork variance is higher (7.070 versus
4.272 in squared million-executions/s units), but its mean and both observed
minima are also higher. Higher variance alone is not a reason to reject this
gain. Its minimum fork mean, 8.347 million/s, remains below the baseline mean
of 8.616 million/s, so this is not a strict minimum-above-mean win.

**1 source, 576 work units:** the +1.76% pooled gain is driven by a +11.89%
first-pass gain. The remaining three pass changes are -2.52%, -1.00% and -0.30%.
The better pooled minima do not establish a consistently faster candidate.

**17 sources, zero work:** every pass regresses, by 5.03% to 12.19%. Both
candidate minima are worse, and even the baseline minimum window exceeds the
candidate mean. This is sufficient evidence against a universal default change
without needing to resolve the marginal workloads.

**23 sources, zero work:** the candidate loses mean throughput in three of four
passes, but its minimum window improves from 797.164 to 1033.222 million/s.
The baseline's minimum fork mean is nevertheless slightly higher. This is a
real mean-versus-window-minimum tradeoff, not an unconditional win for either
arm. The candidate's minimum window still falls below the baseline mean of
1121.075 million/s.

**23 sources, 96 work units:** each arm has a slow fork, in different passes.
The baseline averages 92.710 million/s in pass 0 while the candidate averages
138.227; in pass 3 the baseline averages 138.126 while the candidate averages
91.459. The middle passes are close. The -0.56% pooled delta obscures this
trajectory variation and should not be interpreted as a precise policy effect.

## Scope and next decision

The test broadens evidence beyond the earlier forced-participant, zero-work
fixture. It does not identify the mechanism of the workload-dependent response:
CACHE occupancy, actual active participant counts and branch behavior were not
observed. Source count is not a measurement of productive handles or contention.
Similar throughput at heavier work does not prove that CACHE was unused.

Work units are synthetic micro-calibrator weights, not nanoseconds. In
particular, higher single-source work weights having higher aggregate throughput
does not establish a lower per-frame body cost; automatic runtime behavior can
differ, and this experiment does not measure that explanation.

The common invocation target is 1000000 executions, versus 32000000 in the prior
duration sweeps. Both arms are matched here, but absolute throughput should not
be pooled with earlier campaigns. This host, fixed body work and parallel-source
matrix do not establish performance for ordered streams, bursty arrivals,
application I/O, other topologies or other machines.

Recommendation: retain 814375 ns as the longer-park candidate for the supported
single-source, zero/lighter-work region and 15000 ns as the shorter-park
reference for regions where the longer park regresses or lacks demonstrated
benefit. Leaving the global default unchanged is an implementation status, not
a rejection of the regional policy opportunity.

The useful next calibration question is the boundary of that opportunity:
does the benefit persist at intermediate source counts between 1 and 17, and
where does it disappear between the sampled lighter and heavier work levels?
Match both park durations within each new workload and compare throughput,
observed minima and pass consistency. Before turning that surface into a
runtime switching rule, establish how its workload descriptors map to available
runtime inputs; this matrix does not supply a productive-handle, contention or
body-cost-in-nanoseconds threshold. Application-level validation remains needed.

No new experiment, runtime policy, instrumentation, or default update was made
for this findings revision. All previous artifacts are preserved.
