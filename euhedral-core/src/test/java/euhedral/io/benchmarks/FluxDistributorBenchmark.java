package euhedral.io.benchmarks;

import euhedral.io.utils.PinnedThreadExecutor;
import euhedral.io.test_utils.TestFrame;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.LoggerFactory;

@BenchmarkMode({Mode.SampleTime, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {
        "-Daffinity.logging.level=OFF",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=error"})
public class FluxDistributorBenchmark {

    static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(FluxDistributorBenchmark.class.getSimpleName())
                .addProfiler("stack")
                .addProfiler("gc")
                .jvmArgs(args)
                .build();
        new Runner(opt).run();
    }

    private final int totalFramesPerProducer = 1_000_000;
    @Param({"8"})
    private int numProducers;
    private PinnedThreadExecutor[] pinned;
    private TestFrame[][] frames;

    @Setup(Level.Trial)
    public void setupTrial() {
        pinned = new PinnedThreadExecutor[14];
        for (int i = 1; i < pinned.length; i++) {
            pinned[i] = PinnedThreadExecutor.getOrSetIfAbsent(i, "test", 0, true);
        }

        frames = new TestFrame[16][totalFramesPerProducer];
        for (int i = 0; i < 16; i++) {
            frames[i] = TestFrame.generateParallel(totalFramesPerProducer);
        }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        for (PinnedThreadExecutor p : pinned) {
            if (p != null) {
                p.close();
            }
        }
    }

//    @Benchmark
//    @OperationsPerInvocation(8_000_000)
//    public void benchOneDistributor(Blackhole bh) throws Exception {
//        runCoreBenchmark(false, bh);
//    }
//
//    private void runCoreBenchmark(boolean useSubDistributor, Blackhole bh) throws Exception {
//        long[] localCounters = new long[16];
//        FluxNode distributor = new FluxNode("Test", useSubDistributor ? 1 : 16);
//        FluxNode subDistributor =
//                useSubDistributor ? new FluxNode("Test", 16) : null;
//
//        FluxInterceptor[] ingest = new FluxInterceptor[16];
//        for (int i = 0; i < numProducers; i++) {
//            ingest[i] = new FluxInterceptor();
//            distributor.addUpstream(ingest[i]);
//        }
//
//        FluxEdge[] downstream = new FluxEdge[16];
//        for (int i = 7; i < 14; i++) {
//            final int shardId = i;
//            downstream[i] = new FluxEdge(new AtomicBoolean(false));
//            downstream[i].subscribe(new BaseSubscriber<>() {
//                @Override
//                protected void hookOnNext(AbstractFrame value) {
//                    localCounters[shardId]++;
//                    bh.consume(value);
//                }
//            });
//        }
//
//        if (useSubDistributor) {
//            FluxEdge parent = new FluxEdge(new AtomicBoolean());
//            FluxEdge child = new FluxEdge(new AtomicBoolean());
//            child.onSubscribe(parent);
//
//            BitSet link = new BitSet(1);
//            link.set(0);
//
//
//            distributor.setDrain(true);
//            distributor.setDownstreamMapping(link, new FluxEdge[]{parent});
//            distributor.setDrain(false);
//            subDistributor.addUpstream(parent);
//        }
//
//        BitSet active = new BitSet(16);
//        active.set(7, 14);
//        FluxNode target = useSubDistributor ? subDistributor : distributor;
//        target.setDrain(true);
//        while (!target.setDownstreamMapping(active, downstream)) {
//            Thread.onSpinWait();
//        }
//        target.setDrain(false);
//
//        CountDownLatch startSignal = new CountDownLatch(numProducers);
//        Thread[] runners = new Thread[numProducers];
//        for (int i = 0; i < numProducers; i++) {
//            final int id = i;
//            runners[i] = pinned[i + 3].getPinnedFactory().newThread(() -> {
//                try {
//                    startSignal.countDown();
//                    startSignal.await();
//
//                    final TestFrame[] myFrames = frames[id];
//                    final int limit = totalFramesPerProducer;
//
//                    for (int j = 0; j < limit; j++) {
//                        ingest[id].onNext(myFrames[j]);
//                    }
//                } catch (Exception ignored) {
//                }
//            });
//            runners[i].start();
//        }
//
//        for (Thread r : runners) {
//            r.join();
//        }
//
//        long actualTotal = 0;
//        for (long count : localCounters) {
//            actualTotal += count;
//        }
//        bh.consume(actualTotal);
//    }

    @Benchmark
    @OperationsPerInvocation(8_000_000)
    public void benchTwoDistributors(Blackhole bh) throws Exception {
//        runCoreBenchmark(true, bh);
    }
}
