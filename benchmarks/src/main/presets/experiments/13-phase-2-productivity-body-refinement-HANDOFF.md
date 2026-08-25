# Phase 2 contention-independent productivity-body refinement handoff

Execute this prepared local refinement and comparison exactly. Do not alter the fixtures, add points, select
a threshold, run automatic policy, or begin source-deficit portability.

## Corrected coarse evidence

Experiment 12 measured the productivity gate after removing contention from its activation equation. It was
mechanically valid:

- 36 independent JVM trials and 216 ordered CONTINUOUS windows completed;
- all 216 windows remained continuously fed;
- 1,629 of 1,629 artifact checksums matched;
- all controls and ordinary idle defaults were exact;
- FORCE_OFF resolved to threshold 0 and had zero exclusions;
- FORCE_ON resolved to `Long.MAX_VALUE` and excluded the surplus 12 of 23 workers at every body fixture.

The corrected forced response changed sign inside the first coarse interval:

| workUnits | representative smoothed body cost | FORCE_ON minus FORCE_OFF |
|---:|---:|---:|
| 0 | approximately 19-22 ns | +406.14%, +308.05% |
| 112 | approximately 163-201 ns | -25.14%, -26.78% |

All heavier fixtures lost under FORCE_ON. This brackets the physical crossover strictly between the no-op
and workUnits 112; it does not locate a threshold in nanoseconds yet.

## Runtime equation under test

With an empty local cache, productivity exclusion is:

~~~text
thresholdNs > 0
&& body history has at least 32 samples
&& cached upstream handle count > 0
&& registeredWorkers > 1
&& workerRank > 0
&& workerRank > productiveHandleCount
&& smoothedBodyCostNs <= thresholdNs
~~~

Contention is not an input. Retain contention only as explanatory telemetry for the unchanged ordinary-idle
and DIRECT/STAGED policies.

## Prepared refinement

Test workUnits `16, 32, 48, 64, 80, 96`, each with `FORCE_OFF` and `FORCE_ON`. The matrix uses four
independent JVM replicas per treatment, arranged as two complementary balanced-order pairs: 48 JVMs and 288
ordered measurement windows total.

Use measured raw and smoothed body nanoseconds as the x-axis. WorkUnits are only fixture controls. Do not
reuse any measurements or conclusions from the deleted, contention-qualified Phase 2 runs.

## Fixed controls

- corrected CONTINUOUS lifecycle;
- 23 workers on CPU set 2-31 and harness CPU 0;
- 11 continuously fed parallel sources and zero ordered sources;
- ordinary idle durations exactly 50,000 / 0 / 0 / 0 / 0 ns;
- unchanged repeated 15,000 ns productivity park;
- unchanged participation-count/rank rule;
- unchanged DIRECT/STAGED, contention, body-band, pull-bucket, handle, source-assignment, and cache behavior;
- deterministic body work;
- two 2-second warmups and six ordered 5-second windows;
- one fork per trial.

## Exact commands

From the repository root:

~~~bash
git status --short
mise exec -- gradle :euhedral-core:test :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run \
  benchmarks/src/main/presets/experiments/13-phase-2-productivity-body-refinement.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare \
  benchmarks/src/main/presets/comparisons/13-phase-2-productivity-body-refinement.json
~~~

Do not run `calibrate-work` or any other Phase 2 preset.

## Validate and return

Verify exactly 48 trial directories/forks and 288 fed windows, four sample indices per body/mode, all fixed
controls, and every checksum. Preserve every JVM and ordered trajectory, including anomalies.

Return the complete `experiments/13-phase-2-productivity-body-refinement/` directory, including
`comparisons/`, exact command transcripts, and a mechanical table containing:

- workUnits, forced mode, sample index, JVM ID, and artifact path;
- raw and smoothed body mean/median/p25/p75/p95;
- body-band occupancy calculated from the `probability` column by decision type and window;
- all six ordered throughputs, fork mean, within-trajectory CV, and across-JVM mean/CV;
- FORCE_ON minus FORCE_OFF absolute and percentage throughput per paired JVM and across JVMs;
- acquisition attempts, successes, failures, success share, and contention centroid;
- productive-handle ratio, exclusion count/fraction, and ordinary-idle fraction;
- DIRECT/STAGED occupancy and checksum status.

Mechanically flag:

1. any FORCE_OFF exclusion or forced-threshold mismatch;
2. any FORCE_ON cell that does not exclude the expected surplus workers;
3. non-monotonic or sign-unstable ON/OFF response;
4. a crossover not bracketed by adjacent refinement fixtures;
5. body-band summaries that collapse nonzero raw occupancy into the dominant B0 state;
6. unfed windows, telemetry gaps, control drift, checksum failures, or execution-action transitions.

Do not pool windows as replicas, select a threshold, redesign the experiment, or add body points. Return the
evidence to the planning agent.
