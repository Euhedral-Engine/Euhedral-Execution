package euhedral.io;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.control_plane.ControlPlane;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.ConsumerFrame;
import euhedral.io.frames.FrameSequencer;
import euhedral.io.frames.FunctionFrame;
import euhedral.io.frames.RunnableFrame;
import euhedral.io.frames.SequencedFrame;
import euhedral.io.interfaces.DispatchPreProcess;
import euhedral.io.interfaces.SlotManager;
import euhedral.io.utils.KeyHasher;
import euhedral.io.utils.MpscFrameRecycler;
import euhedral.io.utils.PinnedThreadExecutor;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

public class NumaAwareExecutor implements AutoCloseable {

    private static AtomicReference<NumaAwareExecutor> INSTANCE = new AtomicReference<>();

    public static NumaAwareExecutor get() {
        return INSTANCE.get();
    }

    public static NumaAwareExecutor getOrCreate(String name, int recycleCapacityPerStream,
            CircuitBreakerRegistry cbRegistry, @Nullable
            MeterRegistry meterRegistry) {
        return INSTANCE.updateAndGet(curr -> {
            if (curr != null && !curr.closed) {
                return curr;
            }

            return new NumaAwareExecutor(name, recycleCapacityPerStream,
                    cbRegistry.circuitBreaker(name + "-CircuitBreaker"),
                    new DRRScheduler.Config(null, 32, name, meterRegistry),
                    DefaultSlotManager.Config.balancedDefault(
                            cbRegistry, meterRegistry, name));
        });
    }

    public static NumaAwareExecutor getOrCreate(String name, int recycleCapacityPerStream,
            CircuitBreaker circuitBreaker, DRRScheduler.Config drrConfig,
            DefaultSlotManager.Config slotManagerConfig) {
        return INSTANCE.updateAndGet(curr -> {
            if (curr != null) {
                return curr;
            }

            return new NumaAwareExecutor(name, recycleCapacityPerStream, circuitBreaker, drrConfig,
                    slotManagerConfig);
        });
    }
    private final ControlPlane controlPlane;
    private final int recycleCapacity;

    private volatile boolean closed = false;

    protected NumaAwareExecutor(String name, int recycleCapacityPerStream,
            CircuitBreaker circuitBreaker,
            DRRScheduler.Config drrConfig, DefaultSlotManager.Config slotManagerConfig) {
        this.recycleCapacity = recycleCapacityPerStream;

        DRRScheduler drr = new DRRScheduler(drrConfig, null);
        DefaultSlotManager slotManager = new DefaultSlotManager(slotManagerConfig,
                circuitBreaker);

        FunctionalPipeline pipeline = new FunctionalPipeline(name, null, drr, slotManager,
                new FunctionalExecutor(null));

        controlPlane = ControlPlane.getOrCreate(name, pipeline,
                drrConfig.registry());
    }

    public <T, R> Flux<R> applyParallelReturnOrdered(Flux<T> input, Function<T, R> function) {
        final long password = KeyHasher.combine(ThreadLocalRandom.current().nextLong(),
                ThreadLocalRandom.current().nextLong());

        FrameSequencer<R> sequencer = new FrameSequencer<>(password);
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
                frame = new FunctionFrame(id, idHash, idHash,
                        (Function<Object, Object>) function, (Consumer<Object>) consumer, dead,
                        recycler);
                frame.setPayload(obj);
            }
            frame.setOrdered(ordered);

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
                    frame = new RunnableFrame(id, idHash, idHash,
                            runnable, dead, recycler);
                }
                frame.setOrdered(false);
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
                frame = new ConsumerFrame(id, idHash, idHash, (Consumer<Object>) consumer,
                        dead,
                        recycler);
                frame.setPayload(obj);
            }
            frame.setOrdered(ordered);

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
