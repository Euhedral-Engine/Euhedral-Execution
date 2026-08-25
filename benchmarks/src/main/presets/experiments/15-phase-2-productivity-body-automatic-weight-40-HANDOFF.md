# Execution Handoff: Phase 2 AUTO Weight 40

Run the prepared experiment exactly as written. Do not redesign it, add weights or body fixtures,
select a threshold, or rerun individual anomalous forks. Preserve every JVM and every ordered
CONTINUOUS window.

## Why this run exists

The corrected forced ON/OFF surface at 23 workers and 11 sources establishes:

- workUnits 32: ON wins materially;
- workUnits 48: ON wins, with more variance;
- workUnits 64: ON and OFF are effectively neutral;
- workUnits 80: OFF wins materially.

AUTO weight 48 did not reproduce that selector. Relative to retained forced winners it measured
+9.36%, -3.27%, -2.54%, and -6.41% at workUnits 32, 48, 64, and 80. Weight 40 is the single next
candidate in the conservative direction. It remains a dimensionless weight; every worker must
calibrate that weight independently into its own `productivityThresholdNs`.

The resolved nanosecond thresholds are not expected to be equal across heterogeneous workers.
Do not flag cross-core threshold dispersion by itself. The useful mechanical diagnostic is each
worker's resolved threshold paired with that same worker's smoothed body-cost trajectory and gate
activation.

## Prepared files

- Experiment:
  `benchmarks/src/main/presets/experiments/15-phase-2-productivity-body-automatic-weight-40.json`
- Comparison:
  `benchmarks/src/main/presets/comparisons/15-phase-2-productivity-body-automatic-weight-40.json`
- Output:
  `experiments/15-phase-2-productivity-body-automatic-weight-40`

## Preflight and execution

From the repository root:

```bash
git status --short
mise install
mise exec -- java -version
mise exec -- gradle --version
mise exec -- gradle :euhedral-core:test :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run \
  benchmarks/src/main/presets/experiments/15-phase-2-productivity-body-automatic-weight-40.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare \
  benchmarks/src/main/presets/comparisons/15-phase-2-productivity-body-automatic-weight-40.json
```

Do not run another preset. Do not alter experiment 13 or 14 artifacts.

## Required invariant checks

Verify mechanically and report exact counts:

1. 16 completed trial directories and 16 independent JVM forks.
2. Four JVMs for each workUnits value 32, 48, 64, and 80.
3. Six ordered measurement windows per fork, 96 windows total.
4. `continuouslyFed=true` for all 96 windows.
5. Every expanded config has:
   - `productivityGateMode=AUTO`;
   - `productivityThresholdWeight=40`;
   - 23 physical workers from CPU set 2 through 31;
   - 11 parallel sources and zero ordered sources;
   - `lifecycleMode=CONTINUOUS`;
   - ordinary idle durations `50000 / 0 / 0 / 0 / 0 ns`;
   - body weights `96 / 128 / 216 / 288`;
   - two 2-second warmups and six 5-second measurement windows.
6. No contention field participates in the productivity gate.
7. Verify every digest-only `.sha256` sidecar by hashing the sibling file directly. Do not use
   `sha256sum -c` on digest-only sidecars.

## Returned data

Return a self-contained `HANDOFF.md` in the output directory with:

- exact commands and tool versions;
- trial, fork, window, fed-window, telemetry-file, and checksum counts;
- artifact paths and any mechanical anomaly;
- for each workUnits/sampleIndex JVM: JVM ID, ordered W0-W5 throughput, fork mean, trajectory CV,
  raw and smoothed body cost, body-band occupancy, acquisition attempts/success/failure/share,
  contention centroid, productive-handle ratio, exclusion count/fraction, ordinary-idle fraction,
  and DIRECT/STAGED occupancy;
- retained forced-winner throughput and AUTO-minus-winner absolute/percent delta;
- for every worker, its rank/core, resolved `productivityThresholdNs`, smoothed body-cost
  mean/median, threshold-to-body ratio, and exclusion fraction. Keep these paired within the same
  JVM; do not interpret pooled threshold-nanosecond dispersion as weight instability.

Report facts and anomalies only. Do not select weight 40, propose another weight, add a source
topology, or execute portability checks. Stop after returning the artifacts and handoff.
