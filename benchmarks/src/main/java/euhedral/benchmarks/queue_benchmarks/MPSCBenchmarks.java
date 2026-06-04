package euhedral.benchmarks.queue_benchmarks;

import euhedral.atomics.PaddedAtomicReferenceArray;
import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hashing.HasherApi;
import euhedral.io.utils.MathFunctions;
import euhedral.queues.PartitionedUnboundedMpscArrayQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;
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
public class MPSCBenchmarks {
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
        private final PaddedAtomicReferenceArray<MpscUnboundedXaddArrayQueue<Integer>> jcTools = new PaddedAtomicReferenceArray<>(4);
        private final PartitionedUnboundedMpscArrayQueue<Integer> euhedral = new PartitionedUnboundedMpscArrayQueue<>(
                4, 4096);
        private final CyclicBarrier start = new CyclicBarrier(17);
        private final CyclicBarrier end = new CyclicBarrier(18);
        private final PinnedThreadExecutor[] executors = new PinnedThreadExecutor[32];
        private QueueConsumer consumer;

        private final Integer[] values = new Integer[4096];

        private final long seed = HasherApi.BASE_SEED;

        @Param({"64", "512", "1024", "2048"})
        private int batchSize;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            for(int i = 0; i < jcTools.length(); i++) {
                jcTools.set(i, new MpscUnboundedXaddArrayQueue<>(4096, 4));
            }
            consumer = new QueueConsumer(blackhole);
            for (int i = 0; i < executors.length; i++) {
                executors[i] = PinnedThreadExecutor.getOrSetIfAbsent(i, "Thread-" + i, Thread.MAX_PRIORITY, true);
            }
            for(int i = 0; i < values.length; i++){
                values[i] = i;
            }
        }

        @Benchmark
        @OperationsPerInvocation(65_536)
        public void jcOfferDrain() throws Throwable {
            for(int t = 0; t < 16; t++) {
                executors[t].execute(() -> {
                    try {
                        start.await();
                        long seed = this.seed;
                        for (int i = 0; i < 4096; i++) {
                            int idx = jcTools.fromRawIdx(HasherApi.mix(seed++));
                            idx = (int) MathFunctions.unsignedMultiplyHigh(idx, euhedral.partitions());
                            while (!jcTools.getPlain(idx).relaxedOffer(values[i])) {
                                Thread.onSpinWait();
                            }
                        }
                        end.await();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            executors[16].execute(() -> {
                try {
                    start.await();
                    int count = 0;
                    while (count != 65_536) {
                        for(int i = 0; i < jcTools.length() && count < 65_536; i++) {
                            int batch = Math.min(batchSize, 65_536 - count);
                            int c = jcTools.getPlain(i).drain(consumer, batch);
                            count += c;

                        }
                        Thread.onSpinWait();
                    }
                    end.await();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            end.await();
        }

        @Benchmark
        @OperationsPerInvocation(65_536)
        public void euhedralOfferDrain() throws Throwable {
            for (int t = 0; t < 16; t++) {
                executors[t].execute(() -> {
                    try {
                        start.await();
                        long seed = this.seed;
                        for (int i = 0; i < 4096; i++) {
                            int idx = jcTools.fromRawIdx(HasherApi.mix(seed++));
                            idx = (int) MathFunctions.unsignedMultiplyHigh(idx, euhedral.partitions());
                            while (!euhedral.offer(idx, values[i])) {
                                Thread.onSpinWait();
                            }
                        }
                        end.await();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            executors[16].execute(() -> {
                try {
                    start.await();
                    long count = 0;
                    while (count != 65_536) {
                        long batch = Math.min(batchSize, 65_536 - count);
                        long c = euhedral.drain(consumer, batch);
                        count += c;
                    }
                    end.await();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            end.await();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            PinnedThreadExecutor.closeAll();
        }
    }
}
