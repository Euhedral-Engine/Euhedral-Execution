package euhedral.common.io.dispatch.benchmarks;

import ch.qos.logback.classic.LoggerContext;
import euhedral.common.io.dispatch.DRRScheduler;
import euhedral.common.io.dispatch.DefaultSlotManager;
import euhedral.common.io.dispatch.DefaultSlotManager.Config;
import euhedral.common.io.dispatch.control_plane.ControlPlane;
import euhedral.common.io.dispatch.utils.ContainerAwareResourceMonitor;
import euhedral.common.io.dispatch.utils.NumaMapper;
import euhedral.common.io.test_utils.TestFrame;
import euhedral.common.io.test_utils.TestPipeline;
import euhedral.common.io.test_utils.TestPipeline.TestExecutor;
import euhedral.common.io.test_utils.TestPublisher;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.scheduler.Schedulers;

@Testcontainers
public class EndToEndBenchmark {

    private static final String RUNNER = "euhedral.common.io.dispatch.benchmarks.EndToEndBenchmark$NumaAwareContainerBenchmark";

    @Test
    public void benchmark() throws Exception {

        File testJar = new File("target/test-jar-with-dependencies.jar");

        GenericContainer<?> container = new GenericContainer<>("eclipse-temurin:25-jre-alpine");
        container.addFileSystemBind(testJar.getAbsolutePath(), "/app/test.jar", BindMode.READ_ONLY);
        container.withCommand("sleep", "3600");
        container.start();

        ExecCreateCmdResponse execCreateCmdResponse = container.getDockerClient()
                .execCreateCmd(container.getContainerId()).withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd("java", "--add-exports", "java.base/jdk.internal.platform=ALL-UNNAMED",
                        "--add-exports", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
                        "-XX:-RestrictContended", "-Daffinity.logging.level=OFF",
                        "-Dorg.slf4j.simpleLogger.defaultLogLevel=error", "-cp", "/app/test.jar",
                        RUNNER).exec();

        container.getDockerClient().execStartCmd(execCreateCmdResponse.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(com.github.dockerjava.api.model.Frame frame) {
                        System.out.print(new String(frame.getPayload()));
                        System.out.flush();
                    }
                }).awaitCompletion();
    }

    @BenchmarkMode({Mode.All})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 20, timeUnit = TimeUnit.SECONDS)
    @Fork(value = 1)
    public static class NumaAwareContainerBenchmark {

        static void main(String[] args) throws Exception {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.getLogger("net.openhft").setLevel(ch.qos.logback.classic.Level.OFF);

            Options opt = new OptionsBuilder().include(
                            NumaAwareContainerBenchmark.class.getSimpleName()).addProfiler("stack")
                    .addProfiler("gc")
                    .jvmArgs("-XX:-RestrictContended", "-Daffinity.logging.level=OFF",
                            "-Dorg.slf4j.simpleLogger.defaultLogLevel=error").build();
            new Runner(opt).run();
        }

//        @Benchmark
//        @OperationsPerInvocation(8_000_000)
        public void benchOneProducerEightMillionOrdered(BenchmarkState state) throws Throwable {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch trigger = new CountDownLatch(8_000_000);

            state.producerPool.submit(() -> {
                TestPublisher subscription = new TestPublisher(state.orderedFramePool);
                subscription.reset(trigger);
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                state.controlPlane.ingest(subscription);
            });
            start.countDown();

            if (!trigger.await(60, TimeUnit.SECONDS)) {
                throw new RuntimeException("Stall detected. Pending: " + trigger.getCount());
            }
        }

//        @Benchmark
//        @OperationsPerInvocation(8_000_000)
        public void benchOneProducerEightMillionParallel(BenchmarkState state) throws Throwable {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch trigger = new CountDownLatch(8_000_000);

            state.producerPool.submit(() -> {
                TestPublisher subscription = new TestPublisher(state.parallelFramePool);
                subscription.reset(trigger);
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                state.controlPlane.ingest(subscription);
            });
            start.countDown();

            if (!trigger.await(60, TimeUnit.SECONDS)) {
                throw new RuntimeException("Stall detected. Pending: " + trigger.getCount());
            }
        }

//        @Benchmark
//        @OperationsPerInvocation(8_000_000)
        public void benchEightProducersEightMillionParallel(BenchmarkState state) throws Throwable {
            CountDownLatch trigger = new CountDownLatch(8_000_000);

            for (int i = 0; i < 8; i++) {
                final int id = i;
                state.producerPool.submit(() -> {
                    try {
                        state.barrier8P.await();
                        state.publishers[id].reset(trigger);
                        state.controlPlane.ingest(state.publishers[id]);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            state.barrier8P.await();

            if (!trigger.await(60, TimeUnit.SECONDS)) {
                throw new RuntimeException("Stall detected. Pending: " + trigger.getCount());
            }
            state.barrier8P.reset();
        }

        @Benchmark
        @OperationsPerInvocation(32_000_000)
        public void bench32Producers32MillionParallel(BenchmarkState state) throws Throwable {
            CountDownLatch trigger = new CountDownLatch(32_000_000);

            for (int i = 0; i < 32; i++) {
                final int id = i;
                state.producerPool.submit(() -> {
                    try {
                        state.barrier32P.await();
                        state.publishers[id].reset(trigger);
                        state.controlPlane.ingest(state.publishers[id]);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            state.barrier32P.await();

            if (!trigger.await(120, TimeUnit.SECONDS)) {
                throw new RuntimeException("Stall detected. Pending: " + trigger.getCount());
            }

            state.barrier32P.reset();
        }

        @State(Scope.Benchmark)
        public static class BenchmarkState {

            public TestFrame[] orderedFramePool = TestFrame.generateOrdered(8_000_000);
            public TestFrame[] parallelFramePool = TestFrame.generateParallel(8_000_000);
            public TestPublisher[] publishers = new TestPublisher[32];
            public ExecutorService producerPool;
            public CyclicBarrier barrier8P = new CyclicBarrier(8 + 1);
            public CyclicBarrier barrier32P = new CyclicBarrier(32 + 1);
            private ControlPlane controlPlane;

            @Setup(Level.Trial)
            public void setupExecutor(Blackhole bh) {
                LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
                loggerContext.getLogger("net.openhft").setLevel(ch.qos.logback.classic.Level.OFF);

                ContainerAwareResourceMonitor resourceMonitor = new ContainerAwareResourceMonitor(
                        Duration.ofMillis(200), Schedulers.boundedElastic());
                NumaMapper.INSTANCE.update(resourceMonitor.getUtilization());

                CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                        .slidingWindowSize(10).minimumNumberOfCalls(50000).failureRateThreshold(10)
                        .waitDurationInOpenState(Duration.ofSeconds(5))
                        .permittedNumberOfCallsInHalfOpenState(1000)
                        .writableStackTraceEnabled(false).build();
                CircuitBreaker circuitBreaker = CircuitBreaker.of("SystemTest", config);

                DRRScheduler.Config drrConfig = new DRRScheduler.Config(null, 32, "SystemTest",
                        null);
                DefaultSlotManager.Config dsmConfig = new Config(null, 2000, 20, 256, 1024,
                        CircuitBreakerRegistry.of(config), null,
                        "SystemTest");

                TestPipeline pipeline = new TestPipeline("SystemTest", null,
                        new DRRScheduler(drrConfig, null),
                        new DefaultSlotManager(dsmConfig, circuitBreaker),
                        new TestExecutor(null, bh));
                controlPlane = ControlPlane.getOrCreate("SystemTest", pipeline, resourceMonitor,
                        null);

                producerPool = Executors.newFixedThreadPool(32);
                for (int i = 0; i < publishers.length; i++) {
                    publishers[i] = new TestPublisher(TestFrame.generateParallel(1_000_000));
                }
            }

            @TearDown(Level.Trial)
            public void tearDownTrial() {
                try {
                    controlPlane.close();
                    producerPool.shutdownNow();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
