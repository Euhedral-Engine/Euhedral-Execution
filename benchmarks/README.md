# Main benchmarks

This module packages two launchers:

- `euhedral-benchmarks` selects the standard JMH benchmark groups.
- `euhedral-calibration` runs, compares, or work-calibrates configured JMH trials.

Benchmark execution changes machine load and can write retained artifacts. Build and test the
module without launching a campaign with:

```bash
gradle :benchmarks:test :benchmarks:spotlessCheck
```

## Build the launchers

From the repository root, build the distribution:

```bash
gradle :benchmarks:assemble
```

The distribution is written to `benchmarks/build/`:

- `bin/euhedral-benchmarks` and `bin/euhedral-calibration` are the supported launchers.
- `euhedral-benchmark.jar` contains the module classes.
- `lib/` contains the runtime dependencies required by JMH forked JVMs.

Run the scripts rather than invoking the JAR directly. Both scripts use `JAVA_HOME/bin/java`
when `JAVA_HOME` is set, otherwise `java`. Set `JAVA_OPTS` to add JVM options to the launcher.
Use `--minimal` as the first launcher argument to omit its default Logback configuration.

## Standard JMH benchmark groups

Pass one or more group names to `euhedral-benchmarks`:

```bash
benchmarks/build/bin/euhedral-benchmarks core-latency queues-spsc
```

The launcher accepts only group names; it does not expose JMH command-line include, fork, or
iteration flags. Each group uses the JMH annotations in its benchmark class.

| Group                | Runs                                                                                           |
|----------------------|------------------------------------------------------------------------------------------------|
| `all`                | Every standard group below. This can take a long time.                                         |
| `core-latency`       | End-to-end core latency benchmarks.                                                            |
| `core-hc-throughput` | High-contention core throughput benchmarks.                                                    |
| `core-lc-throughput` | Light-contention core throughput benchmarks.                                                   |
| `core-high-scale`    | Large-host core throughput benchmarks. Select only on an exceptionally large host (92+ cores). |
| `batched-mandelbrot` | Batched Mandelbrot execution-efficiency benchmarks.                                            |
| `mandelbrot`         | Mandelbrot stress benchmarks.                                                                  |
| `queues-spsc`        | Single-producer/single-consumer queue benchmarks, including JCTools comparisons.               |
| `queues-mpsc`        | Multi-producer/single-consumer queue benchmarks.                                               |
| `queues-mpmc`        | Multi-producer/multi-consumer queue benchmarks.                                                |

Names are case-insensitive and duplicate names are run once. An unrecognized name causes the
launcher to print the valid group names and exit without running JMH.

### Standard-launcher flags

Set these Java system properties with `JAVA_TOOL_OPTIONS` or `JAVA_OPTS`; they are read before
JMH forks are created and forwarded as needed by the launcher.

| Flag                               | Applies to                         | Effect                                                                                                                                                                                              |
|------------------------------------|------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `-Dgc=true`                        | Any group                          | Adds JMH's GC profiler. Any other value leaves it disabled.                                                                                                                                         |
| `-Dperf=true`                      | Any group                          | Adds JMH's Linux `perf` profiler with cycles, instructions, cache, TLB, and branch events. It requires a Linux host where `perf` is usable by the current user. Any other value leaves it disabled. |
| `-Ddegree=<integer>`               | `mandelbrot`, `batched-mandelbrot` | Sets the Mandelbrot equation degree. The default is `2`.                                                                                                                                            |
| `-DoutputDir=<directory>`          | `mandelbrot`                       | Directory for rendered PNG output. When set without `outputFile`, the file name is `mandelbrot-D<degree>.png`.                                                                                      |
| `-DoutputFile=<name>`              | `mandelbrot`                       | PNG file name. A missing `.png` suffix is added. Without `outputDir`, the benchmark receives the file name but no output directory.                                                                 |
| `-Deuhedral.hct.sources=<integer>` | `core-hc-throughput`               | Number of repeating sources. The default is `max(1, detected-core-count - 1)`.                                                                                                                      |

For example, run latency and high-contention throughput with GC profiling and four high-contention
sources:

```bash
JAVA_TOOL_OPTIONS='-Dgc=true -Deuhedral.hct.sources=4' \
  benchmarks/build/bin/euhedral-benchmarks core-latency core-hc-throughput
```

## Calibration launcher

The calibration launcher takes exactly one mode and one JSON path:

```bash
benchmarks/build/bin/euhedral-calibration run <harness-config.json>
benchmarks/build/bin/euhedral-calibration compare <comparison-config.json-or-experiment-directory>
benchmarks/build/bin/euhedral-calibration calibrate-work <harness-config.json>
```

| Mode             | Input                                            | Effect                                                                                               |
|------------------|--------------------------------------------------|------------------------------------------------------------------------------------------------------|
| `run`            | Harness configuration                            | Resolves enabled trials and sweeps, then runs `CalibrationBenchmark` with each trial's JMH settings. |
| `compare`        | Comparison configuration or experiment directory | Loads completed run output and writes fork-score comparison artifacts. It does not run JMH.          |
| `calibrate-work` | Harness configuration                            | Resolves trials as `run` does, but runs `WeightBenchmark` for synthetic-work calibration.            |

Start from `src/main/presets/examples/example_harness_config.json`. The example shows the supported
trial fields (`forks`, `warmups`, `iterations`, `warmupTime`, `measurementTime`, and optional
`jvmArgs`), reusable calibration profiles, imports, sweeps, run ordering, and artifact retention.
Use `src/main/presets/examples/example_profile_library.json` for a reusable profile library and the
other files in that directory for comparison examples.

### Calibration flags

| Flag                                                      | Effect                                                                                                                        |
|-----------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `-Deuhedral.calibration.trialFilter=<regular-expression>` | Runs only enabled trial IDs whose ID contains a regex match. The launcher fails if the expression matches no enabled trial.   |
| `JAVA_OPTS='<JVM options>'`                               | Adds JVM options to the calibration launcher. Per-trial `jvmArgs` in the harness configuration are appended to each JMH fork. |
| `--minimal`                                               | Must be the first argument. Omits the launcher's default `benchmark-logback.xml` configuration.                               |

The harness configuration controls the remaining run parameters instead of command-line flags:
`randomizeTrialOrder`, `balancedTrialOrder`, `randomSeed`, `failFast`, `repeatCount`, output
directory, raw JMH log retention, and optional observer data retention. `randomizeTrialOrder` and
`balancedTrialOrder` cannot both be enabled. Observer data is collected only when its corresponding
observation option is enabled in the selected calibration profile; retaining observer data alone
does not enable callbacks.

A filtered run example:

```bash
JAVA_TOOL_OPTIONS='-Deuhedral.calibration.trialFilter=baseline' \
  benchmarks/build/bin/euhedral-calibration run \
  benchmarks/src/main/presets/examples/example_harness_config.json
```
