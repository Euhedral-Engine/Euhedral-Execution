# Execution Handoff: Phase 2 Source-Deficit Portability

Run exactly the prepared portability experiment and its three comparisons. Do not change the
candidate weight, add body points, increase replication, rerun anomalous forks, or select the final
production value.

## Decision entering this run

The corrected 23/11 forced surface is sufficiently precise for this policy:

- forced ON materially wins through workUnits 48;
- forced ON is effectively neutral by workUnits 64;
- forced OFF wins above workUnits 64.

AUTO weights 48 and 40 were then tested. Weight 40 is the conservative candidate: it retained full
cheap-anchor exclusion at workUnits 32 and reduced mean exclusion at workUnits 80 to 6.5%. Exact
placement between weights 40 and 48 is not worth another anchor-only refinement because the policy
only needs a safe point after which exclusion no longer offers meaningful improvement.

Experiment 15's artifact checksums and raw trajectories are valid, but its final `Mechanical Flags
& Analysis` prose is not. It contradicts its own raw tables. Do not reuse that prose. The raw
experiment-15 fixture results are:

| workUnits | AUTO-40 vs retained winner | mean exclusion |
|---:|---:|---:|
| 32 | +10.08% | 52.2% |
| 48 | -7.61% | 26.8% |
| 64 | -5.06% | 20.7% |
| 80 | -0.36% | 6.5% |

Those retained-winner comparisons span different execution periods, so this portability run uses
contemporaneous forced controls. Two independent JVMs per treatment are enough to establish broad
sign and crossover movement; do not chase small differences.

## Prepared files

- Experiment:
  `benchmarks/src/main/presets/experiments/16-phase-2-productivity-body-source-deficit-portability.json`
- Critical one-source AUTO experiment:
  `benchmarks/src/main/presets/experiments/17-phase-2-productivity-body-one-source-auto-40.json`
- Forced ON versus OFF comparison:
  `benchmarks/src/main/presets/comparisons/16-phase-2-productivity-body-source-deficit-forced.json`
- One-source AUTO versus OFF comparison:
  `benchmarks/src/main/presets/comparisons/16-phase-2-productivity-body-one-source-auto-vs-off.json`
- One-source AUTO versus ON comparison:
  `benchmarks/src/main/presets/comparisons/16-phase-2-productivity-body-one-source-auto-vs-on.json`
- Output:
  `experiments/16-phase-2-productivity-body-source-deficit-portability`

## Exact commands

From the repository root:

```bash
git status --short
mise install
mise exec -- java -version
mise exec -- gradle --version
mise exec -- gradle :euhedral-core:test :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run \
  benchmarks/src/main/presets/experiments/16-phase-2-productivity-body-source-deficit-portability.json
mise exec -- benchmarks/build/bin/euhedral-calibration run \
  benchmarks/src/main/presets/experiments/17-phase-2-productivity-body-one-source-auto-40.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare \
  benchmarks/src/main/presets/comparisons/16-phase-2-productivity-body-source-deficit-forced.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare \
  benchmarks/src/main/presets/comparisons/16-phase-2-productivity-body-one-source-auto-vs-off.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare \
  benchmarks/src/main/presets/comparisons/16-phase-2-productivity-body-one-source-auto-vs-on.json
```

## Required invariant checks

Verify and report:

1. Exactly 48 forced trials/forks with 288 ordered measurement windows in experiment 16, plus four
   AUTO trials/forks with 24 ordered measurement windows in experiment 17.
2. All 312 windows across both output directories have `continuouslyFed=true`.
3. Forced surface: source counts 6, 2, and 1 x workUnits 32, 48, 64, and 80 x
   FORCE_OFF/FORCE_ON x two JVMs = 48 trials.
4. Critical AUTO check: 23/1 x workUnits 48 and 64 x AUTO weight 40 x two JVMs = four trials.
5. Every trial retains 23 workers, CPU set 2 through 31, zero ordered sources, CONTINUOUS lifecycle,
   body weights `96 / 128 / 216 / 288`, and ordinary idle defaults `50000 / 0 / 0 / 0 / 0 ns`.
6. Forced trials have no configured threshold weight. AUTO trials have weight 40 and calibrate it
   independently on each worker.
7. The gate has no contention qualifier.
8. Every `contention_staleness.tsv` contains both `productivityThresholdNs` and
   `smoothedBodyCostNs`; use them as same-cycle, same-worker paired values.
9. Verify digest-only `.sha256` sidecars by hashing each sibling artifact directly.

## Required return

Write `HANDOFF.md` inside the output directory containing:

- exact commands, versions, artifact paths, counts, checksum results, and mechanical anomalies;
- per source-count/workUnits/mode/JVM ordered W0-W5 throughput and fork mean;
- across-JVM mean/CV, within-trajectory CV, absolute and percent ON-minus-OFF throughput;
- raw/smoothed body cost and body-band occupancy;
- acquisition attempts/success/failure/share and contention centroid;
- productive-handle ratio, exclusion count/fraction, ordinary-idle fraction, and DIRECT/STAGED
  occupancy;
- per-worker rank/core, calibrated threshold, same-cycle smoothed body value, threshold/body ratio,
  and exclusion fraction;
- a mechanical indication of the first tested body point where forced ON is neutral or loses at
  each of 23/6, 23/2, and 23/1;
- AUTO-40 versus each contemporaneous forced endpoint at 23/1 workUnits 48 and 64.

Report the surface without choosing a threshold or proposing another experiment. Stop after the
handoff is complete.
