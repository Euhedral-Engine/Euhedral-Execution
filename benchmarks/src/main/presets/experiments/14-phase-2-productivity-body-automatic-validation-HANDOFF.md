# Phase 2 contention-independent automatic-threshold validation handoff

Execute this prepared automatic-policy validation and comparison exactly. Do not alter the candidate weight,
add fixtures, rerun forced treatments, begin source portability, or select a production threshold.

## Corrected forced evidence

Experiment 13 completed 48 independent JVMs and 288 fed CONTINUOUS windows with 2,169 of 2,169 checksums
matching. FORCE_OFF resolved to zero with no exclusions; FORCE_ON resolved to `Long.MAX_VALUE` and excluded
exactly 12 of 23 workers. Contention was not part of productivity activation.

The artifact-derived response is:

| workUnits | OFF-state smoothed mean | ON-state smoothed mean | ON minus OFF | sign count |
|---:|---:|---:|---:|---:|
| 16 | 51.5 ns | 40.8 ns | +105.69% | 4/4 positive |
| 32 | 75.7 ns | 60.8 ns | +29.73% | 4/4 positive |
| 48 | 101.3 ns | 84.3 ns | +12.02% | 4/4 positive |
| 64 | 126.5 ns | 104.0 ns | -0.08% | 2 positive / 2 negative |
| 80 | 152.9 ns | 124.3 ns | -13.10% | 4/4 negative |
| 96 | 176.8 ns | 144.6 ns | -18.05% | 4/4 negative |

The returned narrative incorrectly repeated an old `-13.73%` workUnits-64 value. Do not use it. The raw
comparison rows above are authoritative: workUnits 64 spans +1.45%, +0.43%, -1.82%, and -0.39% and is a
neutral plateau.

## Candidate encoding

Use only:

~~~text
productivityGateMode = AUTO
productivityThresholdWeight = 48
~~~

The conservative physical boundary is approximately 105-110 measured nanoseconds: it retains the workUnits
48 win while keeping the neutral workUnits 64 fixture on the non-excluding side when evaluated from its
OFF-state body estimate. Weight 48 is the single encoding hypothesis because `CalibrationExecutor` and
`MicroCalibrator` execute the same `cpuWork` cycle count. Do not assume that it resolves to 105-110 ns.
Exported per-worker `productivityThresholdNs` is authoritative.

The runtime productivity equation remains contention-independent:

~~~text
thresholdNs > 0
&& body history has at least 32 samples
&& cached upstream handle count > 0
&& registeredWorkers > 1
&& workerRank > 0
&& workerRank > productiveHandleCount
&& smoothedBodyCostNs <= thresholdNs
~~~

## Prepared matrix

| workUnits | role | retained forced winner |
|---:|---|---|
| 32 | clearly below | FORCE_ON |
| 48 | near below | FORCE_ON |
| 64 | near above / neutral plateau | conservative FORCE_OFF |
| 80 | clearly above | FORCE_OFF |

There are four independent AUTO JVMs per fixture: 16 JVM trials and 96 ordered CONTINUOUS measurement
windows. The comparison reuses the corresponding 16 retained forced-winner JVMs from experiment 13.

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
  benchmarks/src/main/presets/experiments/14-phase-2-productivity-body-automatic-validation.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare \
  benchmarks/src/main/presets/comparisons/14-phase-2-productivity-body-automatic-validation.json
~~~

Do not run `calibrate-work` or any other Phase 2 preset.

## Validate and return

Verify exactly 16 trial directories/forks and 96 fed windows. Every trial must retain AUTO mode, weight 48,
the exact fixed controls, and four sample indices per body. Verify every checksum.

Return the complete `experiments/14-phase-2-productivity-body-automatic-validation/` directory, including
`comparisons/`, exact command transcripts, and a mechanical table containing:

- workUnits, sample index, JVM ID, and artifact path;
- all 23 per-worker resolved thresholds plus per-JVM and across-JVM min/median/mean/max/CV;
- raw and smoothed body distributions and raw body-band probability occupancy;
- all six ordered throughputs, trajectory mean/CV, and across-JVM throughput mean/CV;
- AUTO minus retained-winner absolute and percentage throughput;
- exclusion count/fraction and ordinary-idle fraction;
- acquisition counts/share, contention centroid, and productive-handle ratio;
- DIRECT/STAGED occupancy and checksum status.

Mechanically flag:

1. failure to retain meaningful exclusion and winner-like throughput at workUnits 32 or 48;
2. material exclusion or repeatable throughput loss at workUnits 80;
3. material exclusion at workUnits 64 outside the neutral tolerance;
4. resolved-threshold variability large enough to cross both the workUnits-48 and workUnits-64 body regions;
5. any apparent dependence of productivity activation on contention;
6. unfed windows, missing telemetry, control drift, or checksum failures.

Do not choose the production threshold, redesign the encoding, or begin 23/6, 23/2, or 23/1 portability.
Return the evidence to the planning agent.
