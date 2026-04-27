package euhedral.queues.benchmarks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import euhedral.queues.PartitionedMpmcArrayQueue;
import euhedral.queues.QueueConsumer;
import org.HdrHistogram.ConcurrentHistogram;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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

@BenchmarkMode({Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
public class ContentionBenchmark {
    static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(ContentionBenchmark.class.getSimpleName())
//                .addProfiler("perf", "events=cycles,instructions,cache-misses,L2-loads,L2-load-misses,branch-misses")
                .build();

        new Runner(opt).run();
    }

    @Param({"1", "4", "16"})
    public int partitions;

    private PartitionedMpmcArrayQueue<Long> queue;

    ConcurrentHistogram histogram = new ConcurrentHistogram(3);

    private final QueueConsumer<Long> consumer = (val) -> {
        histogram.recordValue(System.nanoTime() - val);
    };

    @State(Scope.Benchmark)
    public static class BenchState {
        long startTime;
        long endTime;
    }

    private final LongAdder totalOffered = new LongAdder();
    private final LongAdder totalDrained = new LongAdder();

    @Setup(Level.Trial)
    public void setup(BenchState g) {
        queue = new PartitionedMpmcArrayQueue<>(partitions, 4096, false);
        g.startTime = System.nanoTime();
    }

    @Benchmark
    @OperationsPerInvocation(400_000)
    public void contentionBenchmark() throws Exception {
        CountDownLatch end = new CountDownLatch(1);

        int batch = 100_000;
        LongAdder localDrained = new LongAdder();
        ExecutorService exec = Executors.newFixedThreadPool(16);
        for(int i = 0; i < 4; i++){
            exec.submit(() -> {
                for (int j = 0; j < batch; j++) {
                    long v = ThreadLocalRandom.current().nextLong();

                    int spins = 0;
                    while (!queue.offer(v, System.nanoTime())) {
                        if (spins++ > 1000) {
                            Thread.yield();
                        } else {
                            Thread.onSpinWait();
                        }
                    }
                    totalOffered.increment();
                }
            });
        }

        for(int i = 0; i < 4; i++) {
            exec.submit(() -> {
                while(localDrained.sum() < batch * 4) {
                    int count = queue.drain(consumer, 4096);
                    localDrained.add(count);
                    totalDrained.add(count);
                    Thread.yield();
                }
                end.countDown();
            });
        }
        end.await();
        queue.reset();
    }

    @TearDown(Level.Trial)
    public void report(BenchState g) {
        g.endTime = System.nanoTime();
        long durationNs = g.endTime - g.startTime;

        long offered = totalOffered.sumThenReset();
        long drained = totalDrained.sumThenReset();

        double producedRate = (double) offered / durationNs;
        double consumedRate = (double) drained / durationNs;

        System.out.println("\n--- ELEMENT THROUGHPUT ---");
        System.out.println("Offered elements/ns: " + producedRate);
        System.out.println("Drained elements/ns: " + consumedRate);
        System.out.println("Total offered: " + offered);
        System.out.println("Total drained: " + drained);
        System.out.println("Duration ns: " + durationNs);
        System.out.println();
        System.out.println("\n--- LATENCY ---");
        System.out.printf("Latency P0: %dns\n", histogram.getValueAtPercentile(0));
        System.out.printf("Latency P50: %dns\n", histogram.getValueAtPercentile(0.5));
        System.out.printf("Latency P90: %dns\n", histogram.getValueAtPercentile(0.9));
        System.out.printf("Latency P95: %dns\n", histogram.getValueAtPercentile(0.95));
        System.out.printf("Latency P99: %dns\n", histogram.getValueAtPercentile(0.99));
        System.out.printf("Latency P100: %dns\n", histogram.getValueAtPercentile(1.0));
        System.out.println();
        histogram.reset();
    }
}
