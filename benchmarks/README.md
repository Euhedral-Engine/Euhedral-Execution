# How to Use

### Benchmarks

| Benchmark            | Description                                                                                     |
|----------------------|-------------------------------------------------------------------------------------------------|
| all                  | Runs all benchmarks                                                                             |
| core-high-scale      | Throughput benchmarks for euhedral-core meant to be ran on extremely large instances. 92+ cores |
| core-latency         | Latency benchmarks for euhedral-core                                                            |
| core-throughput      | Throughput benchmarks for euhedral-core                                                         |
| core-throughput-comp | Throughput comparison benchmarks between euhedral-core and Reactor                              |
| batched-mandelbrot   | Execution efficiency stress test for euhedral-core and Reactor                                  |
| mandelbrot           | Mandelbrot stress test for euhedral-core and Reactor                                            |


### Flags

| Flag                   | Description                                                                             |
|------------------------|-----------------------------------------------------------------------------------------|
| -Dgc=true              | Turns on gc profiling                                                                   |
| -Dperf=true            | Turns on Linux Perf profiling                                                           |
| -Ddegree=\<Integer>    | Sets the Mandelbrot equation degree. Defaults to 2 if not set.                          |
| -DoutputFile=\<String> | Name of the output image for the Mandelbrot test. Does not create the image if not set. |
| -DoutputDir=\<String>  | Output directory for the Mandelbrot image.                                              |

### Example
```
java -Dgc=true -jar euhedral-benchmark.jar core-latency core-throughput
```
