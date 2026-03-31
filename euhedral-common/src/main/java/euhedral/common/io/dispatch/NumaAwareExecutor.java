package euhedral.common.io.dispatch;

import euhedral.common.io.dispatch.control_plane.CloneConfig;
import euhedral.common.io.dispatch.control_plane.ControlPlane;
import euhedral.common.io.dispatch.frames.AbstractFrame;
import euhedral.common.io.dispatch.frames.ConsumerFrame;
import euhedral.common.io.dispatch.frames.FrameSequencer;
import euhedral.common.io.dispatch.frames.FunctionFrame;
import euhedral.common.io.dispatch.frames.RunnableFrame;
import euhedral.common.io.dispatch.frames.SequencedFrame;
import euhedral.common.io.dispatch.interfaces.DispatchPreProcess;
import euhedral.common.io.dispatch.interfaces.SlotManager;
import euhedral.common.io.dispatch.utils.ContainerAwareResourceMonitor;
import euhedral.common.io.dispatch.utils.KeyHasher;
import euhedral.common.io.dispatch.utils.MpscFrameRecycler;
import euhedral.common.io.dispatch.utils.NumaMapper;
import euhedral.common.io.dispatch.utils.PinnedThreadExecutor;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.scheduler.Scheduler;

public class NumaAwareExecutor implements AutoCloseable {

    private static final DispatchOrders ordered = new DispatchOrders(true,
            KeyHasher.mix(ThreadLocalRandom.current().nextLong()), null);
    private static final DispatchOrders parallel = new DispatchOrders(false,
            KeyHasher.mix(ThreadLocalRandom.current().nextLong()), null);

    private final Scheduler scheduler;
    private final ControlPlane controlPlane;
    private final int recycleCapacity;

    public NumaAwareExecutor(String name, int recycleCapacityPerStream, Scheduler scheduler,
            CircuitBreaker circuitBreaker,
            DRRScheduler.Config drrConfig, DefaultSlotManager.Config slotManagerConfig) {
        this.scheduler = scheduler;
        this.recycleCapacity = recycleCapacityPerStream;

        ContainerAwareResourceMonitor resourceMonitor = new ContainerAwareResourceMonitor(
                Duration.ofMillis(200), scheduler);

        NumaMapper.INSTANCE.update(resourceMonitor.getUtilization());

        DRRScheduler drr = new DRRScheduler(drrConfig, null);
        DefaultSlotManager slotManager = new DefaultSlotManager(slotManagerConfig,
                circuitBreaker);

        FunctionalPipeline pipeline = new FunctionalPipeline(name, null, drr, slotManager,
                new FunctionalExecutor(null));

        controlPlane = ControlPlane.getOrCreate(name, pipeline, resourceMonitor,
                drrConfig.registry());
    }

    public <T, R> Flux<R> applyParallelReturnOrdered(Flux<T> input, Function<T, R> function) {
        final long password = KeyHasher.combine(ThreadLocalRandom.current().nextLong(),
                ThreadLocalRandom.current().nextLong());

        FrameSequencer<R> sequencer = new FrameSequencer<>(scheduler, password);
        Flux<AbstractFrame> frameFlux = sequencer.map(input, function);

        return sequencer.output().doOnSubscribe(_ -> controlPlane.ingest(frameFlux));
    }

    @SuppressWarnings("unchecked")
    public <T, R> Flux<R> apply(Flux<T> input, Function<T, R> function, boolean ordered) {
        final String id = ThreadLocalRandom.current().nextLong() + "";
        final long idHash = KeyHasher.getHash(id);
        final long[] seed = new long[]{ThreadLocalRandom.current().nextLong()};
        final long password = KeyHasher.combine(ThreadLocalRandom.current().nextLong(),
                ThreadLocalRandom.current().nextLong());

        final Sinks.One<Void> killSwitch = Sinks.unsafe().one();
        final AtomicBoolean dead = new AtomicBoolean(false);

        final DispatchOrders orders =
                ordered ? NumaAwareExecutor.ordered : NumaAwareExecutor.parallel;
        final Sinks.Many<R> returnSink = Sinks.many().unicast()
                .onBackpressureBuffer(new MpscUnboundedXaddArrayQueue<>(2048));

        final Consumer<R> consumer = (obj) -> {
            EmitResult result;
            while (!(result = returnSink.tryEmitNext(obj)).isSuccess()) {
                if (result == EmitResult.FAIL_CANCELLED || result == EmitResult.FAIL_TERMINATED
                        || result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                    dead.set(true);
                    killSwitch.tryEmitEmpty();
                    break;
                }
                Thread.onSpinWait();
            }
        };

        int[] recycleCount = new int[]{0};
        FunctionFrame[] recycleBuffer = new FunctionFrame[recycleCapacity];
        MpscFrameRecycler recycler = new MpscFrameRecycler(recycleCapacity, password);

        final Flux<AbstractFrame> framed = input.takeUntilOther(killSwitch.asMono()).map(obj -> {
            if (recycleCount[0] == 0) {
                recycleCount[0] = recycler.batchPoll(recycleBuffer, password);
            }

            FunctionFrame frame;
            if (recycleCount[0] - 1 > 0) {
                frame = recycleBuffer[--recycleCount[0]];
                frame.replace(obj);
            } else {
                frame = new FunctionFrame(id, idHash, orders.hash(),
                        (Function<Object, Object>) function, (Consumer<Object>) consumer, dead,
                        recycler);
                frame.setPayload(obj);
            }

            if (!ordered) {
                frame.randomizeHash(seed[0]++);
            }

            return frame;
        });

        return returnSink.asFlux().doOnSubscribe(
                        sub -> controlPlane.ingest(framed))
                .doFinally(sig -> {
                    dead.set(true);
                    killSwitch.tryEmitEmpty();
                });
    }

    public void run(Runnable runnable, long times) {
        if (times <= 0) {
            return;
        }

        final String id = ThreadLocalRandom.current().nextLong() + "";
        final long idHash = KeyHasher.getHash(id);
        final long[] seed = new long[]{ThreadLocalRandom.current().nextLong()};
        final long password = KeyHasher.combine(ThreadLocalRandom.current().nextLong(),
                ThreadLocalRandom.current().nextLong());

        int[] recycleCount = new int[]{0};
        RunnableFrame[] recycleBuffer = new RunnableFrame[Math.min((int) times, recycleCapacity)];
        MpscFrameRecycler recycler = new MpscFrameRecycler(recycleCapacity, password);

        final Sinks.One<Void> killSwitch = Sinks.unsafe().one();
        final AtomicBoolean dead = new AtomicBoolean(false);

        Flux<AbstractFrame> framed = Flux.generate(AtomicLong::new, (state, sink) -> {
            long count = state.get();
            if (count == times) {
                sink.complete();
            } else {
                if (recycleCount[0] == 0) {
                    recycleCount[0] = recycler.batchPoll(recycleBuffer, password);
                }

                RunnableFrame frame;
                if (recycleCount[0] - 1 > 0) {
                    frame = recycleBuffer[--recycleCount[0]];
                } else {
                    frame = new RunnableFrame(id, idHash, NumaAwareExecutor.parallel.hash(),
                            runnable, dead, recycler);
                }
                frame.randomizeHash(seed[0]++);
                sink.next(frame);
            }
            state.incrementAndGet();
            return state;
        });

        controlPlane.ingest(framed.takeUntilOther(killSwitch.asMono()).doFinally(sig -> {
            dead.set(true);
            killSwitch.tryEmitEmpty();
        }));
    }

    @SuppressWarnings("unchecked")
    public <T> void accept(Flux<T> input, Consumer<T> consumer, boolean ordered) {
        final String id = ThreadLocalRandom.current().nextLong() + "";
        final long idHash = KeyHasher.getHash(id);
        final long[] seed = new long[]{ThreadLocalRandom.current().nextLong()};
        final long password = KeyHasher.combine(ThreadLocalRandom.current().nextLong(),
                ThreadLocalRandom.current().nextLong());

        final DispatchOrders orders =
                ordered ? NumaAwareExecutor.ordered : NumaAwareExecutor.parallel;

        int[] recycleCount = new int[]{0};
        ConsumerFrame[] recycleBuffer = new ConsumerFrame[recycleCapacity];
        MpscFrameRecycler recycler = new MpscFrameRecycler(recycleCapacity, password);

        final Sinks.One<Void> killSwitch = Sinks.unsafe().one();
        final AtomicBoolean dead = new AtomicBoolean(false);

        Flux<AbstractFrame> framed = input.takeUntilOther(killSwitch.asMono()).map(obj -> {
            if (recycleCount[0] == 0) {
                recycleCount[0] = recycler.batchPoll(recycleBuffer, password);
            }

            ConsumerFrame frame;
            if (recycleCount[0] - 1 > 0) {
                frame = recycleBuffer[--recycleCount[0]];
                frame.replace(obj);
            } else {
                frame = new ConsumerFrame(id, idHash, orders.hash(), (Consumer<Object>) consumer,
                        dead,
                        recycler);
                frame.setPayload(obj);
            }

            if (!ordered) {
                frame.randomizeHash(seed[0]++);
            }

            return frame;
        });
        controlPlane.ingest(framed.doOnCancel(() -> {
            dead.set(true);
            killSwitch.tryEmitEmpty();
        }));
    }

    @Override
    public void close() throws Exception {
        this.controlPlane.close();
    }

    private static class FunctionalPipeline extends AbstractCloneablePipeline {

        public FunctionalPipeline(String name, CloneConfig cloneConfig,
                DispatchPreProcess preProcess,
                SlotManager slotManager,
                FunctionalExecutor executor) {
            super(name, cloneConfig, preProcess, slotManager, executor);
        }

        @Override
        public FunctionalPipeline clone(CloneConfig cloneConfig) {
            return new FunctionalPipeline(name, cloneConfig, preProcess, slotManager,
                    (FunctionalExecutor) executor);
        }
    }

    private static class FunctionalExecutor extends AbstractExecutor {

        private final PinnedThreadExecutor executor;

        FunctionalExecutor(PinnedThreadExecutor executor) {
            super(executor);
            this.executor = executor;
        }

        @Override
        public void execute(AbstractFrame frame) {
            if (!frame.isAlive()) {
                frame.throwMeAsError();
            }

            switch (frame) {
                case SequencedFrame s -> s.apply();
                case ConsumerFrame c -> c.consume();
                case FunctionFrame f -> f.apply();
                case RunnableFrame r -> r.run();
                default -> frame.throwMeAsError();
            }
        }

        @Override
        public FunctionalExecutor clone(CloneConfig cloneConfig) {
            return new FunctionalExecutor(executor);
        }

        @Override
        public FunctionalExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
            return new FunctionalExecutor(executor);
        }
    }
}
