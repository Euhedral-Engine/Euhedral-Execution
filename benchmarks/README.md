# Benchmarks

The benchmark runtime targets Java 21. Package it from the repository root:

```bash
gradle :benchmarks:assemble
```

This creates a thin distribution at `benchmarks/build`:

- `euhedral-benchmark.jar` contains Euhedral's benchmark classes.
- `lib/` contains the runtime dependencies.
- `bin/euhedral-benchmarks` launches JMH with the complete class path required by forked JVMs.

Run the launcher rather than relying on a shaded JAR:

```bash
benchmarks/build/bin/euhedral-benchmarks core-latency core-hc-throughput
```

## Benchmark selection

| Benchmark            | Description                                                                                     |
|----------------------|-------------------------------------------------------------------------------------------------|
| all                  | Runs all benchmarks                                                                             |
| core-latency         | Latency benchmarks for euhedral-core                                                            |
| core-hc-throughput   | High-contention throughput benchmarks for euhedral-core                                         |
| core-lc-throughput   | Light-contention throughput benchmarks for euhedral-core                                        |
| batched-mandelbrot   | Execution efficiency stress test for euhedral-core and Reactor                                  |
| mandelbrot           | Mandelbrot stress test for euhedral-core and Reactor                                            |
| core-high-scale      | Throughput benchmarks for euhedral-core meant to be ran on extremely large instances. 92+ cores |
| queues-spsc          | Runs the SPSC queue benchmarks comparing with JCTools                                           |
| queues-mpsc          | Runs the MPSC queue benchmarks comparing with JCTools                                           |
| queues-mpmc          | Runs the MPMC queue benchmarks comparing with JCTools                                           |


## Flags

| Flag                   | Description                                                                             |
|------------------------|-----------------------------------------------------------------------------------------|
| -Dgc=true              | Turns on gc profiling                                                                   |
| -Dperf=true            | Turns on Linux Perf profiling                                                           |
| -Ddegree=\<Integer>    | Sets the Mandelbrot equation degree. Defaults to 2 if not set.                          |
| -DoutputFile=\<String> | Name of the output image for the Mandelbrot test. Does not create the image if not set. |
| -DoutputDir=\<String>  | Output directory for the Mandelbrot image.                                              |

## Example

```
JAVA_TOOL_OPTIONS=-Dgc=true benchmarks/build/bin/euhedral-benchmarks core-latency core-hc-throughput
```
