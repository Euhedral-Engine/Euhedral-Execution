# Phase 2 productivity-body coarse-surface handoff

Execute the prepared coarse surface exactly and return mechanical artifacts and telemetry. Do not edit the
preset, add body points, select a threshold, or run any refinement, automatic-policy, or source-portability
experiment.

## Runtime semantics being measured

Normal production resolution is:

```text
configured productivityThresholdWeight absent
    -> thresholdNs = calibrated M body-band boundary

configured productivityThresholdWeight <= 0
    -> thresholdNs = 0 (gate disabled)

configured productivityThresholdWeight > 0
    -> thresholdNs = median MicroCalibrator latency for that weight
```

The measured body signal is `FragmentDecisionTree.smoothedBodyCostNs()`: sparse executor-body nanoseconds
captured around each frame body, reduced to the second-smallest value in each non-overlapping 32-sample
window. Values at or above the H boundary require two consecutive windows before the estimate is published.
The threshold comparison is inclusive.

With `localCache == 0`, the exact exclusion decision is:

```text
thresholdNs > 0
&& body history has at least 32 samples
&& cached upstream handle count > 0
&& registeredWorkers > 1
&& workerRank > 0
&& workerRank > productiveHandleCount
&& smoothedBodyCostNs <= thresholdNs
```

Contention is intentionally absent from this equation. The productivity gate is selected only by the
configured body threshold and the existing productive-handle/rank eligibility rule. Contention remains
telemetry and may still affect the separate ordinary idle and DIRECT/STAGED policies, but it cannot enable or
disable productivity exclusion.

An excluded worker performs the unchanged 15,000 ns bounded park and returns to the owner loop. It repeats
that exclusion while the condition remains true, so participation exclusion is effectively indefinite; this
experiment does not tune that park duration or the rank/productive-handle rule.

The coarse preset uses the benchmark-only `productivityGateMode` axis:

- `FORCE_OFF` resolves `thresholdNs` directly to zero.
- `FORCE_ON` resolves `thresholdNs` directly to `Long.MAX_VALUE`.

This bypasses weight calibration and does not force DIRECT/STAGED, rank, cache, or handle conditions. `AUTO`
retains the normal threshold behavior and is not part of this coarse run.

## Fixed fixture and treatment matrix

- Corrected `CONTINUOUS` lifecycle.
- CPU set 2-31: 23 physical workers on the qualified i9-14900K topology; harness CPU 0.
- 11 continuously fed unordered parallel sources; zero ordered sources.
- Current production DIRECT/STAGED, contention, body bands, pull bucket, handle, ranking, assignment, and
  cache behavior.
- Ordinary idle durations exactly `50,000 / 0 / 0 / 0 / 0 ns` for XS/S/M/H/XH.
- Fixed deterministic work; `randomizeWork=false`.
- Two 2-second warmups and six ordered 5-second measurement windows.
- One fork per expanded trial and two independently started sweep repetitions per cell.
- `balancedTrialOrder=true`: repetition zero is forward and repetition one is reverse.
- `totalRequiredExecutions=1,000,000` is the fixed harness invocation/feed quantum for every cell; trajectory
  throughput uses actual completed executions and elapsed time.

Body fixtures are:

```text
0, 112, 172, 252, 384, 768, 3,072, 12,288, 98,304 workUnits
```

Phase 1 measured the first five at approximately:

| workUnits | prior raw body ns | prior smoothed body ns | prior band |
|---:|---:|---:|---|
| 0 | 68.8-72.6 | 21.6-23.4 | XS |
| 112 | 231.5 | 201.2 | S |
| 172 | 316.5 | 287.9 | M |
| 252 | 430.0 | 403.1 | H |
| 384 | 617.4 | 591.0 | XH |

Treat all of those values only as prior fixture qualifications. Use each returned JVM's measured raw and
smoothed body nanoseconds as the physical x-axis. The four heavier points deliberately extend the first
surface without claiming their cost in advance.

The matrix contains 9 bodies x 2 forced modes x 2 independent JVM repetitions = 36 trials/forks and 216
ordered measurement windows. ON/OFF are the only policy difference within a body/repetition comparison.

## Exact commands

From the repository root:

```bash
git status --short
mise install
mise exec -- java -version
mise exec -- gradle --version
mise exec -- gradle :euhedral-core:test :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run \
  benchmarks/src/main/presets/experiments/12-phase-2-productivity-body-coarse.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare \
  benchmarks/src/main/presets/comparisons/12-phase-2-productivity-body-coarse.json
```

Do not run `calibrate-work`; measured runtime body telemetry is authoritative.

## Mechanical validation before returning data

Verify:

1. There are exactly 36 completed trial directories, one fork each, and six ordered CONTINUOUS windows per
   fork.
2. Every `trial_config.json` has 11 parallel sources, zero ordered sources, CPU set 2-31,
   `lifecycleMode=CONTINUOUS`, fixed body-band weights 96/128/216/288, and ordinary idle durations
   50,000/0/0/0/0 ns.
3. Each workUnits/sampleIndex cell has exactly one `FORCE_OFF` and one `FORCE_ON`; no cell has a configured
   `productivityThresholdWeight`.
4. All windows report `continuouslyFed=true`. Report any longer-than-requested heavy-body windows rather
   than discarding them.
5. OFF windows have zero productivity exclusions. ON windows below the physical gate region show nonzero
   exclusions; report a zero-activation ON cell as a mechanical invariant warning, not a threshold verdict.
6. `contention_staleness.tsv` contains `productivityExcluded` and cumulative
   `productivityExclusionCount`; `trajectory_windows.tsv` contains ordinary-idle and productivity-exclusion
   fractions separately.
7. DIRECT/STAGED samples are present in `contention_staleness.tsv`; do not alter their selector.
8. All artifact sidecars match. The sidecars contain bare digests, so verify them with:

```bash
find experiments/12-phase-2-productivity-body-coarse -name '*.sha256' -print0 |
  while IFS= read -r -d '' checksum_file; do
    target_file="${checksum_file%.sha256}"
    expected="$(awk 'NR == 1 { print $1 }' "$checksum_file")"
    actual="$(sha256sum "$target_file" | awk '{ print $1 }')"
    if [ "$expected" != "$actual" ]; then
      echo "MISMATCH: $target_file"
      exit 1
    fi
  done
```

## Return package

Return the complete `experiments/12-phase-2-productivity-body-coarse/` directory, including
`comparisons/`, plus exact command transcripts. Do not summarize CONTINUOUS windows as independent replicas.

Also return a mechanical table keyed by workUnits, measured body cost, forced mode, and JVM identity with:

- raw body mean/median/p25/p75/p95 and smoothed body mean/median;
- body-band occupancy;
- six throughputs in ordered window order and the per-fork trajectory mean/CV;
- successful, failed, and total acquisitions; success share; contention centroid;
- productive-handle ratio;
- productivity exclusion count and sampled exclusion fraction;
- ordinary idle selected fraction;
- DIRECT/STAGED sampled occupancy;
- artifact path and checksum status.

Include the generated ON-minus-OFF absolute and percent throughput deltas from the comparison output, keyed
by workUnits and `origin/sampleIndex`, but do not pool replicas, infer a crossover, select a threshold, or
recommend new body points. Report only mechanical anomalies and missing evidence.
