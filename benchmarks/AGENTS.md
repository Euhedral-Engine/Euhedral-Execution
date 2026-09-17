# Calibration benchmark guidance

The calibration harness measures JMH throughput across independent forks. Optional observers record
the two state callbacks currently exposed by `FragmentObserver`: batch progress and batch completion.
Do not reintroduce removed decision, occupancy, transition, vector-field, body-cost, staleness,
productivity, or pull-bucket calibration surfaces.

Read the root `AGENTS.md` and `docs/ARCHITECTURE.md` before changing scheduler behavior. Benchmark
configuration does not override production routing or fragment policy.

## Configuration

Use `src/main/presets/examples/example_harness_config.json` as the harness example and
`src/main/presets/examples/example_profile_library.json` as the reusable-profile example.

`CalibrationBenchmarkConfig` contains:

- workload: `cpuSet`, source counts, synthetic work, execution target, and timeout;
- observation storage: `rawSampleLimit`, `observeBatchProgress`, and `observeBatchComplete`;
- production idle policy: `idleParkNs`, `contentionHalfLifeNanos`, and optional
  `idleTimingFunction`;

If both observation flags are false, the benchmark does not allocate or export observer data.
`retainObserverData` only controls persistence; it does not enable observation callbacks.

The runner supports profile imports, Cartesian sweeps, repeat ordering, and normal JMH fork,
warmup, iteration, and duration settings. Execute Java and Gradle commands through Mise.

## Running

Package the launcher with:

```bash
mise exec -- gradle :benchmarks:assemble
```

Then use one of the explicit modes:

```bash
benchmarks/build/bin/euhedral-calibration run <harness-config.json>
benchmarks/build/bin/euhedral-calibration compare <experiment-directory-or-comparison-config.json>
benchmarks/build/bin/euhedral-calibration calibrate-work <harness-config.json>
```

Running a benchmark, comparing completed output, and calibrating work are separate actions. Do not
execute benchmark campaigns merely to validate configuration or code changes.

## Observation output

When either observer is enabled and observer data retention is enabled, `TrialExport` writes each
selected scope with SHA-256 sidecars:

- `raw_observations.tsv`: batch-progress and batch-completion counts;
- `statistics.tsv`: scalar distributions for head, steady-state, and combined samples;
- `correlations.tsv`: Pearson and Spearman matrices for those samples.

Each measurement iteration resets scheduler and source state. `retainPerForkResults` places
observation output in a `fork-*` directory. This directory contains
observer output for that JMH fork; it is not a separate comparison input. `retainPerIterationResults`
adds `iteration-*` breakdowns beneath the selected observation output directory.

## Fork comparison

Post-run comparison loads `trial_config.json` and `benchmark_output.log` from each completed run.
The authoritative sample is the list of JMH fork scores in the log. The comparison calculates fork
summaries and a throughput outcome; observer artifacts are not required and are not compared.

Comparison pairing supports `BASELINE`, `KEYED`, and `CROSS`. A comparison emits only:

- `comparison_manifest.json`;
- `comparison_summary.tsv`;
- `configuration_differences.tsv`;
- SHA-256 sidecars for those files.

The comparison configuration has no observation/diagnostic options and no run-versus-fork scope.
Every referenced path identifies one completed run whose fork-score sample is compared.

## Validation

Use focused tests while editing, then run:

```bash
mise exec -- gradle :benchmarks:test :benchmarks:spotlessCheck
```

Changes to `FragmentObserver` or fragment policy also require focused `euhedral-core` tests. Preserve
all local experiment output and do not describe compilation or unit tests as benchmark evidence.
