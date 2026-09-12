# CACHE park calibration conclusion

## Latest confirmation evidence

Verified all 16 completed runs under `experiments/cache-park-confirmation` from
their retained trial configurations and JMH logs. Each duration has eight
independent JVM forks and 40 measurement windows. Recomputed means and minima
agree with `comparisons/throughput_floor.tsv`. The calibration fixtures differ
only in park duration. Warmup measurements are excluded; no slow measurements
are removed. Throughput below is million executions/s.

| Park (us) | Mean | Median fork mean | Minimum fork mean | Minimum window |
| ---: | ---: | ---: | ---: | ---: |
| 752.500 | 766.602 | 791.687 | 592.959 | 410.957 |
| 798.907 | 730.561 | 725.470 | 665.404 | 580.638 |
| 800.000 | 665.183 | 700.307 | 346.029 | 255.495 |
| 814.375 | 760.114 | 760.478 | 685.753 | 622.547 |

814.375 us is the provisional throughput/lower-tail compromise for this fixture.
Its mean is only 0.85% below the highest mean at 752.5 us, with substantially
higher observed minima. It has higher mean, median and both minima than 798.907
and 800 us in this run. It beats 798.907 us on pass mean in all four passes.
This selection uses throughput level and lower-tail behavior, not lowest CV as
an independent objective. If maximizing mean alone, 752.5 us leads this run;
814.375 us does not strictly dominate it.

No setting meets the user's strong criterion that its minimum exceeds every
competing mean, whether minimum means minimum fork average or minimum recorded
measurement window. The 814.375 us minimum fork mean of 685.753 is below the
752.5 and 798.907 us means. Its minimum window of 622.547 is below every competing
mean. These are observed extrema, not guaranteed future throughput bounds.

800 us is not confirmed as equivalent to 798.907 us. Its worst fork's five windows
are approximately 702.850, 257.573, 257.124, 255.495 and 257.101 million/s.
Another fork averages 563.028 million/s. This explains much of its poor result,
but those observations remain part of the evidence. The data cannot establish
that a 1.093 us park difference caused those slow trajectories.

The 752.5 us pass means range from 601.219 to 913.767 million/s; 814.375 us ranges
from 714.883 to 784.033 million/s. The highest pooled mean at 752.5 us therefore
comes with pronounced execution-period variation. Windows within a fork are
dependent; treating 40 windows as 40 independent replications would overstate
the evidence.

## Decision and stopping point

Stop fine-grained fixed-fixture duration sweeps. Recent winners have switched
between 752.5, 814.375 and 798.907 us, and some trajectories show abrupt large
throughput changes. There is insufficient evidence to claim a unique nanosecond
optimum or a proven bimodal response. Further tiny interval subdivision would
risk selecting run variation rather than a reproducible improvement.

Use 814375 ns as the provisional choice if a value is needed for this exact
fixture. The strict minimum-above-all-means winner remains unestablished. No new
experiment is generated or executed, and no production default is changed.
The current source default remains 15000 ns.

These campaigns use zero synthetic body work, 17 sources, CPUs 2-31 with the
harness on CPU 0, and a forced 17 active participants. They do not establish the
ideal default under automatic participation or representative application work.
Before a production-default change, the useful next stage would be workload
and automatic-participation validation of the provisional value, rather than
another finer duration search. That broader validation is not performed here.

All completed run artifacts and prior presets are preserved. Prior temporary
comparison helpers are already absent from the workspace; no core changes or
additional instrumentation were made for this analysis.
