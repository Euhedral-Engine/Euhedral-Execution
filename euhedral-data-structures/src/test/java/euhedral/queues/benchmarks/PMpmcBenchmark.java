package euhedral.queues.benchmarks;

import euhedral.queues.PartitionedMpmcArrayQueue;
import euhedral.queues.QueueConsumer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
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
import org.openjdk.jmh.annotations.OperationsPerInvocation;
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
public class PMpmcBenchmark {
    private static final ConcurrentHistogram contentionHistogram = new ConcurrentHistogram(3);
    private static final ConcurrentHistogram residencyHistogram = new ConcurrentHistogram(3);

    static void main(String[] args) throws Exception {
        Options opt1 = new OptionsBuilder()
                .include(PMpmcBenchmark.class.getSimpleName() + ".ContendedBenchmark")
                .threads(Runtime.getRuntime().availableProcessors())
//                .addProfiler("perf", "events=cycles,instructions,cache-misses,L2-loads,L2-load-misses,branch-misses")
                .build();

        Options opt2 = new OptionsBuilder()
                .include(PMpmcBenchmark.class.getSimpleName() + ".SingleProducerSingleConsumer")
//                .addProfiler("perf", "events=cycles,instructions,cache-misses,L2-loads,L2-load-misses,branch-misses")
                .build();

        Options opt3 = new OptionsBuilder()
                .include(PMpmcBenchmark.class.getSimpleName() + ".MultiProducerSingleConsumer")
//                .addProfiler("perf", "events=cycles,instructions,cache-misses,L2-loads,L2-load-misses,branch-misses")
                .build();

//        new Runner(opt1).run();
//        new Runner(opt2).run();
        new Runner(opt3).run();
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

    private final QueueConsumer<Long> contentionConsumer = (val) -> {
        contentionHistogram.recordValue(System.nanoTime() - val);
    };
    private final QueueConsumer<Long> residencyConsumer = (val) -> {
        residencyHistogram.recordValue(System.nanoTime() - val);
    };

    private final LongAdder totalOffered = new LongAdder();
    private final LongAdder totalDrained = new LongAdder();

    @Param({"1", "4", "8"})
    public int partitions;

    private PartitionedMpmcArrayQueue<Long> queue;
    private ExecutorService exec;

    @Setup(Level.Trial)
    public void setup(BenchState g) {
        queue = new PartitionedMpmcArrayQueue<>(partitions, 4096, false);
        g.startTime = System.nanoTime();
        exec = Executors.newFixedThreadPool(16);
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class OpCounter {
        public long operations;
    }

    @Benchmark
    @Group("ContendedBenchmark")
    public void contendedOffer() {
        long rand = ThreadLocalRandom.current().nextLong();
        int spins = 0;
        while(!queue.offer(rand, System.nanoTime())) {
            if(spins++ > 1000) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
    }

    @Benchmark
    @Group("ContendedBenchmark")
    public void contendedDrain(OpCounter counter) {
        counter.operations += queue.drain(contentionConsumer, 4096 * partitions);
    }

    @Benchmark
    @Group("SingleProducerSingleConsumer")
    public void spscOffer() {
        long key = ThreadLocalRandom.current().nextLong();
        queue.offer(key, System.nanoTime());
    }

    @Benchmark
    @Group("SingleProducerSingleConsumer")
    public void spscDrain(OpCounter counter) {
        counter.operations += queue.drain(residencyConsumer, 4096);
    }

    @Benchmark
    @Group("MultiProducerSingleConsumer")
    @GroupThreads(8)
    public void mpscOffer() {
        long key = ThreadLocalRandom.current().nextLong();
        queue.offer(key, System.nanoTime());
    }

    @Benchmark
    @Group("MultiProducerSingleConsumer")
    public void mpscDrain(OpCounter counter) {
        counter.operations += queue.drain(residencyConsumer, partitions * 4096);
    }

    @TearDown(Level.Trial)
    public void report(BenchState g) {
        g.endTime = System.nanoTime();
        long durationNs = g.endTime - g.startTime;

        long offered = totalOffered.sumThenReset();
        long drained = totalDrained.sumThenReset();

        double producedRate = (double) offered / durationNs;
        double consumedRate = (double) drained / durationNs;

        exec.shutdownNow();
        System.out.println("\n--- ELEMENT THROUGHPUT ---");
        System.out.println("Offered elements/ns: " + producedRate);
        System.out.println("Drained elements/ns: " + consumedRate);
        System.out.println("Total offered: " + offered);
        System.out.println("Total drained: " + drained);
        System.out.println("Duration ns: " + durationNs);

        printHistogram("--- Contention E2E LATENCY ---", contentionHistogram);
        printHistogram("--- Residency E2E LATENCY ---", residencyHistogram);
        contentionHistogram.reset();
        residencyHistogram.reset();
    }

    @State(Scope.Benchmark)
    public static class BenchState {

        long startTime;
        long endTime;
    }
}
