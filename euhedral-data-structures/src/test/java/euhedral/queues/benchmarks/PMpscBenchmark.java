package euhedral.queues.benchmarks;

import euhedral.queues.PartitionedMpscArrayQueue;
import euhedral.queues.QueueConsumer;
import euhedral.queues.benchmarks.PMpmcBenchmark.BenchState;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.ConcurrentHistogram;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@BenchmarkMode({Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
public class PMpscBenchmark {

    private static final ConcurrentHistogram histogram = new ConcurrentHistogram(3);

    static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(PMpscBenchmark.class.getSimpleName() + ".BatchDrainResidencyBenchmark")
//                .addProfiler("perf", "events=cycles,instructions,cache-misses,L2-loads,L2-load-misses,branch-misses")
                .build();

        new Runner(opt).run();
    }

    private final QueueConsumer<Long> consumer = (val) -> {
        histogram.recordValue(System.nanoTime() - val);
    };

    @Param({"1", "4", "8"})
    public int partitions;

    private PartitionedMpscArrayQueue<Long> queue;

    @Setup(Level.Trial)
    public void setup(BenchState g) {
        queue = new PartitionedMpscArrayQueue<>(partitions, 4096);
        g.startTime = System.nanoTime();
    }

    @Benchmark
    @Group("BatchDrainResidencyBenchmark")
    @GroupThreads(8)
    public void offer() {
        long key = ThreadLocalRandom.current().nextLong();
        queue.offer(key, System.nanoTime());
    }

    @Benchmark
    @Group("BatchDrainResidencyBenchmark")
    public void drain(PMpmcBenchmark.OpCounter counter) {
        counter.operations += queue.drain(consumer, partitions * 4096);
    }

    @TearDown(Level.Trial)
    public void report() {
        printHistogram("--- Residency E2E LATENCY ---", histogram);
        histogram.reset();
    }

    static void printHistogram(String title, ConcurrentHistogram histogram) {
        System.out.println();
        System.out.println(title);
        System.out.printf("Samples: %d\n", histogram.getTotalCount());
        System.out.printf("Mean: %.3f\n", histogram.getMean());
        System.out.printf("P0: %dns\n", histogram.getValueAtPercentile(0));
        System.out.printf("P50: %dns\n", histogram.getValueAtPercentile(0.5));
        System.out.printf("P90: %dns\n", histogram.getValueAtPercentile(0.9));
        System.out.printf("P95: %dns\n", histogram.getValueAtPercentile(0.95));
        System.out.printf("P99: %dns\n", histogram.getValueAtPercentile(0.99));
        System.out.printf("P100: %dns\n", histogram.getValueAtPercentile(1.0));
        System.out.println();
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class OpCounter {
        public long operations;
    }
}
