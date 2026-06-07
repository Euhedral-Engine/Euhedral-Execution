package euhedral.benchmarks.queue_benchmarks;

import euhedral.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.varhandle.MpscUnboundedVarHandleArrayQueue;
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
    @Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
    @State(Scope.Benchmark)
    public static class BatchSizeProfile {
        private final MpscUnboundedVarHandleArrayQueue<Integer> jcTools = new MpscUnboundedVarHandleArrayQueue<>(4096);
        private final MpscQueue<Integer> euhedral = new MpscQueue<>(4096);
        private final CyclicBarrier start = new CyclicBarrier(17);
        private final CyclicBarrier end = new CyclicBarrier(2);
        private final PinnedThreadExecutor[] executors = new PinnedThreadExecutor[32];
        private QueueConsumer consumer;


        @Param({"64", "512", "2048"})
        private int batchSize;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            consumer = new QueueConsumer(blackhole);
            for (int i = 0; i < executors.length; i++) {
                executors[i] = PinnedThreadExecutor.getOrSetIfAbsent(i, "Thread-" + i, Thread.MAX_PRIORITY, false);
            }
        }

        @Benchmark
        @OperationsPerInvocation(65_536)
        public void jcOfferDrain() throws Throwable {
            for(int t = 0; t < 16; t++) {
                executors[t].execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 4096; i++) {
                            while (!jcTools.offer(1)) {
                                Thread.onSpinWait();
                            }
                        }
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
                        int c = jcTools.drain(consumer, batchSize);
                        count += c;
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
                        for (int i = 0; i < 4096; i++) {
                            while (!euhedral.offer(1)) {
                                Thread.onSpinWait();
                            }
                        }
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
                        long c = euhedral.drain(consumer, batchSize);
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

    @Fork(1)
    @BenchmarkMode({Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
    @State(Scope.Benchmark)
    public static class Regimes {
        private final MpscUnboundedVarHandleArrayQueue<Integer> jcTools = new MpscUnboundedVarHandleArrayQueue<>(4096);
        private final MpscQueue<Integer> euhedral = new MpscQueue<>(4096);

        private final CyclicBarrier start16 = new CyclicBarrier(16);
        private final CyclicBarrier end17 = new CyclicBarrier(17);

        private final CyclicBarrier start2 = new CyclicBarrier(2);
        private final CyclicBarrier end2 = new CyclicBarrier(2);

        private final PinnedThreadExecutor[] executors = new PinnedThreadExecutor[32];
        private QueueConsumer consumer;

        @Setup(Level.Trial)
        public void setup(Blackhole blackhole) {
            consumer = new QueueConsumer(blackhole);
            for (int i = 0; i < executors.length; i++) {
                executors[i] = PinnedThreadExecutor.getOrSetIfAbsent(i, "Thread-" + i, Thread.MAX_PRIORITY, false);
            }
        }

        @Setup(Level.Invocation)
        public void clear() {
            start16.reset();
            end17.reset();
            start2.reset();
            end2.reset();
            jcTools.clear();
            euhedral.clear();
        }

        @Benchmark
        @OperationsPerInvocation(65_536)
        public void jcOffer() throws Throwable {
            for(int t = 0; t < 16; t++) {
                executors[t].execute(() -> {
                    try {
                        start16.await();
                        for (int i = 0; i < 4096; i++) {
                            while (!jcTools.offer(1)) {
                                Thread.onSpinWait();
                            }
                        }
                        end17.await();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            end17.await();
        }

        @Benchmark
        @OperationsPerInvocation(65_536)
        public void euhedralOffer() throws Throwable {
            for(int t = 0; t < 16; t++) {
                executors[t].execute(() -> {
                    try {
                        start16.await();
                        for (int i = 0; i < 4096; i++) {
                            while (!euhedral.offer(1)) {
                                Thread.onSpinWait();
                            }
                        }
                        end17.await();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            end17.await();
        }

        @Benchmark
        @OperationsPerInvocation(65_536)
        public void jcOfferDrain1v1() throws Throwable {
            executors[1].execute(() -> {
                try {
                    start2.await();
                    for (int i = 0; i < 65_536; i++) {
                        while (!jcTools.offer(1)) {
                            Thread.onSpinWait();
                        }
                    }
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            executors[3].execute(() -> {
                try {
                    start2.await();
                    int count = 0;
                    while (count != 65_536) {
                        count += jcTools.drain(consumer, 2048);
                    }
                    end2.await();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            end2.await();
        }

        @Benchmark
        @OperationsPerInvocation(65_536)
        public void euhedralOfferDrain1v1() throws Throwable {
            executors[1].execute(() -> {
                try {
                    start2.await();
                    for (int i = 0; i < 65_536; i++) {
                        while (!euhedral.offer(1)) {
                            Thread.onSpinWait();
                        }
                    }
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            executors[3].execute(() -> {
                try {
                    start2.await();
                    long count = 0;
                    while (count != 65_536) {
                        count += euhedral.drain(consumer, 2048);
                    }
                    end2.await();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            end2.await();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            PinnedThreadExecutor.closeAll();
        }
    }
}
