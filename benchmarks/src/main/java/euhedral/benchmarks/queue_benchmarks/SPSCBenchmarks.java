package euhedral.benchmarks.queue_benchmarks;

import euhedral.experimental.UnboundedSpscQueue;
import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.queues.PartitionedSpscArrayQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.SpscArrayQueue;
import org.jctools.queues.varhandle.SpscUnboundedVarHandleArrayQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class SPSCBenchmarks {

    private record QueueConsumer(Blackhole blackhole) implements Consumer<Integer>,
            MessagePassingQueue.Consumer<Integer> {

        @Override
        public void accept(Integer integer) {
            blackhole.consume(integer);
        }
    }

    @BenchmarkMode({Mode.Throughput, Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
    @State(Scope.Benchmark)
    @Fork(3)
    public static class OfferWhileDrain {

        private final SpscUnboundedVarHandleArrayQueue<Integer> jcTools = new SpscUnboundedVarHandleArrayQueue<>(1024);
        private final UnboundedSpscQueue<Integer> euhedral = new UnboundedSpscQueue<>(
                1024);
        private final CyclicBarrier barrier = new CyclicBarrier(3);
        private final PinnedThreadExecutor[] pool = new PinnedThreadExecutor[2];
        private QueueConsumer consumer;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            consumer = new QueueConsumer(blackhole);
            pool[0] = PinnedThreadExecutor.getOrSetIfAbsent(0, "Prod", Thread.MAX_PRIORITY, false);
            pool[1] = PinnedThreadExecutor.getOrSetIfAbsent(2, "Cons", Thread.MAX_PRIORITY, false);
        }

        @Setup(Level.Invocation)
        public void prep() {
            jcTools.clear();
            euhedral.clear();
            barrier.reset();
        }

        @Benchmark
        @OperationsPerInvocation(2048)
        public void jcOfferAndDrain() throws Throwable {
            pool[0].execute(() -> {
                for (int i = 0; i < 2048; i++) {
                    while (!jcTools.relaxedOffer(i)) {
                        Thread.onSpinWait();
                    }
                }
                try {
                    barrier.await();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });

            pool[1].execute(() -> {
                int count = 0;
                while (count != 2048) {
                    int c = jcTools.drain(consumer, 2048 - count);
                    if (c == 0) {
                        Thread.onSpinWait();
                    }
                    count += c;
                }
                try {
                    barrier.await();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            barrier.await();
        }

        @Benchmark
        @OperationsPerInvocation(2048)
        public void euhedralOfferAndDrain() throws Throwable {
            pool[0].execute(() -> {
                for (int i = 0; i < 2048; i++) {
                    while (!euhedral.offer(i)) {
                        Thread.onSpinWait();
                    }
                }
                try {
                    barrier.await();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });

            pool[1].execute(() -> {
                long count = 0;
                while (count != 2048) {
                    long c = euhedral.drain(consumer, 2048 - count);
                    if (c == 0) {
                        Thread.onSpinWait();
                    }
                    count += c;
                }
                try {
                    barrier.await();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            barrier.await();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            PinnedThreadExecutor.closeAll();
        }
    }

    @BenchmarkMode({Mode.Throughput, Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
    @State(Scope.Benchmark)
    @Fork(3)
    public static class BatchDrain {

        private final SpscArrayQueue<Integer> jcTools = new SpscArrayQueue<>(1024);
        private final PartitionedSpscArrayQueue<Integer> euhedral = new PartitionedSpscArrayQueue<>(
                1024);

        private QueueConsumer consumer;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            consumer = new QueueConsumer(blackhole);
        }

        @Setup(Level.Invocation)
        public void prep() {
            for (int i = 0; i < 1024; i++) {
                jcTools.relaxedOffer(i);
                euhedral.offer(i);
            }
        }

        //        @Benchmark
        @OperationsPerInvocation(1024)
        public void euhedralDrain() {
            euhedral.drain(consumer, 1024);
        }

        //        @Benchmark
        @OperationsPerInvocation(1024)
        public void jcToolsDrain() {
            jcTools.drain(consumer, 1024);
        }
    }
}
