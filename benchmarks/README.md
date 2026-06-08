# How to Use

### Benchmarks

| Benchmark            | Description                                                                                     |
|----------------------|-------------------------------------------------------------------------------------------------|
| all                  | Runs all benchmarks                                                                             |
| core-latency         | Latency benchmarks for euhedral-core                                                            |
| core-throughput      | Throughput benchmarks for euhedral-core                                                         |
| batched-mandelbrot   | Execution efficiency stress test for euhedral-core and Reactor                                  |
| mandelbrot           | Mandelbrot stress test for euhedral-core and Reactor                                            |
| core-high-scale      | Throughput benchmarks for euhedral-core meant to be ran on extremely large instances. 92+ cores |
| queues-spsc          | Runs the SPSC queue benchmarks comparing with JCTools                                           |
| queues-mpsc          | Runs the MPSC queue benchmarks comparing with JCTools                                           |
| queues-mpmc          | Runs the MPMC queue benchmarks comparing with JCTools                                           |


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
