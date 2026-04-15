package euhedral.io.impl;

import euhedral.io.DRRScheduler;
import euhedral.io.DRRScheduler.Config;
import euhedral.io.DefaultSlotManager;
import euhedral.io.control_plane.ControlPlane;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.ConsumerFrame;
import euhedral.io.frames.FrameSequencer;
import euhedral.io.frames.FunctionFrame;
import euhedral.io.frames.RunnableFrame;
import euhedral.io.utils.KeyHasher;
import euhedral.io.utils.MpscFrameRecycler;
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

public class FunctionalApi implements AutoCloseable {

    private static final AtomicReference<FunctionalApi> INSTANCE = new AtomicReference<>();

    public static FunctionalApi get() {
        return INSTANCE.get();
    }

    public static FunctionalApi getOrCreate(String name, int recycleCapacityPerStream,
            @Nullable MeterRegistry meterRegistry) {
        FunctionalApi api = INSTANCE.get();
        if (api != null) {
            return api;
        }
        if (ControlPlane.get() != null) {
            throw new RuntimeException("A ControlPlaneInstance is already in use");
        }
        api = new FunctionalApi(name, recycleCapacityPerStream,
                new Config(null, 32, name, meterRegistry),
                DefaultSlotManager.Config.balancedDefault(
                        meterRegistry, name));
        INSTANCE.set(api);
        return api;
    }

    public static FunctionalApi getOrCreate(String name, int recycleCapacityPerStream,
            Config drrConfig,
            DefaultSlotManager.Config slotManagerConfig) {
        FunctionalApi api = INSTANCE.get();
        if (api != null) {
            return api;
        }
        if (ControlPlane.get() != null) {
            throw new RuntimeException("A ControlPlaneInstance is already in use");
        }
        api = new FunctionalApi(name, recycleCapacityPerStream, drrConfig,
                slotManagerConfig);
        INSTANCE.set(api);
        return api;
    }

    private final ControlPlane controlPlane;
    private final int recycleCapacity;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    protected FunctionalApi(String name, int recycleCapacityPerStream,
            Config drrConfig, DefaultSlotManager.Config slotManagerConfig) {
        this.recycleCapacity = recycleCapacityPerStream;

        DRRScheduler drr = new DRRScheduler(drrConfig, null);
        DefaultSlotManager slotManager = new DefaultSlotManager(slotManagerConfig);

        FunctionalPipeline pipeline = new FunctionalPipeline(name, null, drr, slotManager,
                new FunctionalExecutor(null));

        controlPlane = ControlPlane.getOrCreate(name, pipeline,
                drrConfig.registry());
    }

    public <T, R> Flux<R> applyParallelReturnOrdered(Flux<T> input, Function<T, R> function) {
        if (closed.get()) {
            throw new RuntimeException("This FunctionalApi instance is closed.");
        }

        final long password = KeyHasher.combine(ThreadLocalRandom.current().nextLong(),
                ThreadLocalRandom.current().nextLong());

        FrameSequencer<R> sequencer = new FrameSequencer<>(password);
        Flux<AbstractFrame> frameFlux = sequencer.map(input, function);

        return sequencer.output().doOnSubscribe(sub -> controlPlane.ingest(frameFlux));
    }

    @SuppressWarnings("unchecked")
    public <T, R> Flux<R> apply(Flux<T> input, Function<T, R> function, boolean ordered) {
        if (closed.get()) {
            throw new RuntimeException("This FunctionalApi instance is closed.");
        }

        final long idHash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());
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
                frame = new FunctionFrame(idHash,
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
        if (closed.get()) {
            throw new RuntimeException("This FunctionalApi instance is closed.");
        }

        final long idHash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());
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
                    frame = new RunnableFrame(idHash,
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
        if (closed.get()) {
            throw new RuntimeException("This FunctionalApi instance is closed.");
        }

        final long idHash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());
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
                frame = new ConsumerFrame(idHash, (Consumer<Object>) consumer,
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        this.controlPlane.close();
    }
}
