package euhedral.benchmarks.queue_benchmarks;

import euhedral.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.data_structures.queues.MpmcQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpmcUnboundedXaddArrayQueue;
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
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class MPMCBenchmarks {
    private record QueueConsumer(Blackhole blackhole) implements Consumer<Integer>,
            MessagePassingQueue.Consumer<Integer> {

        @Override
        public void accept(Integer integer) {
            blackhole.consume(integer);
        }
    }

    @Fork(1)
    @BenchmarkMode({Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
    @State(Scope.Benchmark)
    public static class BatchSizeProfile {
        private final MpmcUnboundedXaddArrayQueue<Integer> jcTools = new MpmcUnboundedXaddArrayQueue<>(4096, 4);
        private final MpmcQueue<Integer> euhedral = new MpmcQueue<>(
                4096, 4);
        private final CyclicBarrier start = new CyclicBarrier(16);
        private final CyclicBarrier end = new CyclicBarrier(33);
        private final PinnedThreadExecutor[] executors = new PinnedThreadExecutor[32];
        private QueueConsumer consumer;

        private final Integer[] values = new Integer[2048];

        @Param({"1", "2", "4", "16", "64", "512", "1024", "2048"})
        private int batchSize;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            consumer = new QueueConsumer(blackhole);
            for (int i = 0; i < executors.length; i++) {
                executors[i] = PinnedThreadExecutor.getOrSetIfAbsent(i, "Thread-" + i, Thread.MAX_PRIORITY, true);
            }
            for(int i = 0; i < values.length; i++){
                values[i] = i;
            }
        }

        @Benchmark
        @OperationsPerInvocation(2048 * 32)
        public void jcOfferDrain() throws Throwable {
            for(int t = 0; t < 16; t++) {
                executors[t].execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 2048; i++) {
                            while (!jcTools.relaxedOffer(values[i])) {
                                Thread.onSpinWait();
                            }
                        }
                        end.await();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            for(int t = 16; t < 32; t++) {
                executors[t].execute(() -> {
                    try {
                        start.await();
                        int count = 0;
                        while (count != 2048) {
                            int batch = Math.min(batchSize, 2048 - count);
                            int c = jcTools.drain(consumer, batch);
                            if (c == 0) {
                                Thread.onSpinWait();
                            }
                            count += c;
                        }
                        end.await();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            end.await();
        }

        @Benchmark
        @OperationsPerInvocation(2048 * 32)
        public void euhedralOfferDrain() throws Throwable {
            for (int t = 0; t < 16; t++) {
                executors[t].execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 2048; i++) {
                            while (!euhedral.offer(values[i])) {
                                Thread.onSpinWait();
                            }
                        }
                        end.await();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            for(int t = 16; t < 32; t++) {
                executors[t].execute(() -> {
                    try {
                        start.await();
                        long count = 0;
                        while (count != 2048) {
                            long batch = Math.min(batchSize, 2048 - count);
                            long c = euhedral.drain(consumer, batch);
                            if (c == 0) {
                                Thread.onSpinWait();
                            }
                            count += c;
                        }
                        end.await();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            end.await();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            PinnedThreadExecutor.closeAll();
        }
    }
}
