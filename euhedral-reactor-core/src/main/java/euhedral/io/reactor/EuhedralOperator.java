package euhedral.io.reactor;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.hashing.HasherApi;
import euhedral.io.frames.FunctionFrame;
import euhedral.io.impl.FrameFactory;
import euhedral.io.impl.FrameFactory.FrameCreate;
import euhedral.io.impl.FrameFactory.FrameReplace;
import euhedral.io.impl.FrameManager;
import euhedral.io.reactor.common.EuhedralSubscriber;
import euhedral.io.reactor.common.FrameSequencer;
import euhedral.queues.PartitionedUnboundedMpscArrayQueue;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

public class EuhedralOperator {

    private final EuhedralScheduler scheduler;
    private final int recycleCapacity;

    private final PaddedAtomicLong seed = new PaddedAtomicLong(
            ThreadLocalRandom.current().nextLong());

    public EuhedralOperator(EuhedralScheduler scheduler, int recycleCapacity) {
        Objects.requireNonNull(scheduler, "Scheduler must not be null");
        if (recycleCapacity < 0) {
            throw new IllegalArgumentException("Recycle capacity must be non-negative");
        }
        this.scheduler = scheduler;
        this.recycleCapacity = recycleCapacity;
    }

    public @NonNull <T, R> Function<Flux<T>, Publisher<R>> flatMapTransformer(
            @NonNull Function<T, R> mapper) {
        return flux -> flatMap(flux, mapper);
    }

    public @NonNull <T, R> Function<Flux<T>, Publisher<R>> flatMapSequentialTransformer(
            Function<T, R> mapper) {
        return flux -> flatMapSequential(flux, mapper);
    }

    public @NonNull <T, R> Function<Flux<T>, Publisher<R>> mapTransformer(Function<T, R> mapper) {
        return flux -> map(flux, mapper);
    }

    public @NonNull <T, R> Publisher<R> flatMap(@NonNull Flux<T> flux,
            @NonNull Function<T, R> mapper) {
        Objects.requireNonNull(flux);
        Objects.requireNonNull(mapper);
        return map(flux, mapper, false);
    }

    public @NonNull <T, R> Publisher<R> flatMapSequential(@NonNull Flux<T> flux,
            @NonNull Function<T, R> mapper) {
        Objects.requireNonNull(flux);
        Objects.requireNonNull(mapper);

        long seed = this.seed.getAndAddRelease(1);
        final long password = HasherApi.mix(seed);

        FrameSequencer<T, R> sequencer = new FrameSequencer<>(password);
        EuhedralSubscriber subscriber = new EuhedralSubscriber();

        flux.transform(sequencer.mapTransformer(mapper, this.recycleCapacity))
                .subscribe(subscriber);

        return sequencer.output().doOnSubscribe(sub -> this.scheduler.ingest(subscriber));
    }

    public @NonNull <T, R> Publisher<R> map(@NonNull Flux<T> flux, @NonNull Function<T, R> mapper) {
        Objects.requireNonNull(flux);
        Objects.requireNonNull(mapper);
        return map(flux, mapper, true);
    }

    private <T, R> Publisher<R> map(Flux<T> flux, Function<T, R> mapper, boolean ordered) {
        long seed = this.seed.getAndAddRelease(1);
        final long password = HasherApi.mix(seed);

        final Sinks.One<Void> killSwitch = Sinks.unsafe().one();
        final AtomicBoolean dead = new AtomicBoolean(false);

        final Sinks.Many<R> returnSink = Sinks.many().unicast()
                .onBackpressureBuffer(new PartitionedUnboundedMpscArrayQueue<>(1, 2048, 1));

        final Consumer<R> consumer = (obj) -> {
            int cycles = 0;
            EmitResult result;
            while (!(result = returnSink.tryEmitNext(obj)).isSuccess()) {
                if (result == EmitResult.FAIL_CANCELLED || result == EmitResult.FAIL_TERMINATED
                        || result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                    if(dead.compareAndSet(false, true)) {
                        killSwitch.tryEmitEmpty();
                    }
                    break;
                }
                if (cycles++ < 128) {
                    Thread.onSpinWait();
                } else if (cycles < 512) {
                    Thread.yield();
                } else {
                    LockSupport.parkNanos(10_000);
                }
            }
        };

        FrameManager<T, FunctionFrame<T, R>> recycler = new FrameManager<>(this.recycleCapacity,
                password);
        FrameCreate<T, FunctionFrame<T, R>> frameCreate = (idHash, data) -> {
            FunctionFrame<T, R> frame = new FunctionFrame<>(idHash,
                    mapper, consumer, dead,
                    recycler);
            frame.setPayload(data);
            frame.setOrdered(ordered);
            return frame;
        };
        FrameReplace<T, FunctionFrame<T, R>> frameReplace = (data, oldFrame) -> {
            oldFrame.setOrdered(ordered);
            oldFrame.replace(data);
        };
        recycler.setFactory(new FrameFactory<>(frameCreate, frameReplace));

        final Flux<FunctionFrame<T, R>> framed = flux.takeUntilOther(killSwitch.asMono())
                .map(obj -> recycler.generate(obj, password));

        EuhedralSubscriber subscriber = new EuhedralSubscriber();
        framed.subscribe(subscriber);

        return returnSink.asFlux().doOnSubscribe(
                        sub -> this.scheduler.ingest(subscriber))
                .doFinally(sig -> {
                    if(dead.compareAndSet(false, true)) {
                        killSwitch.tryEmitEmpty();
                    }
                    recycler.close();
                });
    }
}
