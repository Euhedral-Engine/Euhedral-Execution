package euhedral.io.reactor;

import euhedral.hashing.HasherApi;
import euhedral.io.frames.FunctionFrame;
import euhedral.io.impl.FrameFactory;
import euhedral.io.impl.FrameFactory.FrameCreate;
import euhedral.io.impl.FrameFactory.FrameReplace;
import euhedral.io.impl.FrameManager;
import euhedral.io.reactor.common.BackpressureHandler;
import euhedral.io.reactor.common.EuhedralSubscriber;
import euhedral.io.reactor.common.FrameSequencer;
import euhedral.io.reactor.common.SequencedFrame;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

/// ### The Generic Interface for [EuhedralScheduler]
///
/// This class provides standard Reactor map operators for transforming streams but uses the
/// EuhedralScheduler class to perform them.
///
/// **It Provides:**
/// - flatMap()
/// - flatMapSequential()
/// - concatMap()
///
/// It also has the transformer variants of them so you can inline the calls.
///
/// ```java
/// EuhedralOperator operator = new EuhedralOperator(scheduler);
///
/// Flux<Integer> nums = Flux.range(0, 1000);
/// Flux<String> flux = operator.flatMap(nums, Integer::toString);
///
/// flux.transform(
///     operator.flatMap(Integer::parseInt)
/// ).subscribe();
/// ```
@SuppressWarnings("unused")
public final class EuhedralOperator {

    private final EuhedralScheduler scheduler;
    private final int recycleCapacity;

    private final PaddedAtomicLong seed = new PaddedAtomicLong(
            ThreadLocalRandom.current().nextLong());

    public EuhedralOperator(EuhedralScheduler scheduler) {
        this(scheduler, 16_384);
    }

    public EuhedralOperator(EuhedralScheduler scheduler, int recycleCapacity) {
        Objects.requireNonNull(scheduler, "Scheduler must not be null");
        if (recycleCapacity < 0) {
            throw new IllegalArgumentException("Recycle capacity must be non-negative");
        }
        this.scheduler = scheduler;
        this.recycleCapacity = recycleCapacity;
    }

    public @NonNull <T, R> Function<Flux<T>, Publisher<R>> flatMap(
            @NonNull Function<T, R> mapper) {
        return flux -> flatMap(flux, mapper);
    }

    public @NonNull <T, R> Function<Flux<T>, Publisher<R>> flatMapSequential(
            Function<T, R> mapper) {
        return flux -> flatMapSequential(flux, mapper);
    }

    public @NonNull <T, R> Function<Flux<T>, Publisher<R>> concatMap(Function<T, R> mapper) {
        return flux -> concatMap(flux, mapper);
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

        Flux<SequencedFrame<T, R>> framed = flux.transform(
                sequencer.flatMapSequentialTransformer(mapper, this.recycleCapacity));

        return sequencer.output().doOnSubscribe(sub -> {
            framed.subscribe(subscriber);
            this.scheduler.ingest(subscriber);
        });
    }

    public @NonNull <T, R> Publisher<R> concatMap(@NonNull Flux<T> flux, @NonNull Function<T, R> mapper) {
        Objects.requireNonNull(flux);
        Objects.requireNonNull(mapper);
        return map(flux, mapper, true);
    }

    private <T, R> Publisher<R> map(Flux<T> flux, Function<T, R> mapper, boolean ordered) {
        long[] seed = {this.seed.getAndAddRelease(1)};
        final long password = HasherApi.mix(seed[0]++);

        final Sinks.One<Void> killSwitch = Sinks.unsafe().one();
        final AtomicBoolean dead = new AtomicBoolean(false);

        final Sinks.Many<R> returnSink = Sinks.many().unicast()
                .onBackpressureBuffer(new PartitionedMpscQueue<>(1, 2048, 1));

        final Consumer<R> consumer = (obj) -> {
            int cycles = 0;
            EmitResult result = BackpressureHandler.push(obj, returnSink);
            if (result == EmitResult.FAIL_CANCELLED
                    || result == EmitResult.FAIL_TERMINATED
                    || result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                dead.setRelease(true);
                killSwitch.tryEmitEmpty();
            }
        };

        FrameManager<T, FunctionFrame<T, R>> recycler = new FrameManager<>(this.recycleCapacity,
                password);

        FrameCreate<T, FunctionFrame<T, R>> frameCreate = (idHash, data) -> {
            FunctionFrame<T, R> frame = new FunctionFrame<>(idHash,
                    mapper, consumer, data, dead,
                    recycler);
            if (!ordered) {
                frame.randomizeHash(seed[0]++);
            }
            return frame;
        };
        FrameReplace<T, FunctionFrame<T, R>> frameReplace = (data, oldFrame) -> {
            oldFrame.replace(data);
            if (!ordered) {
                oldFrame.randomizeHash(seed[0]++);
            }
        };
        recycler.setFactory(new FrameFactory<>(frameCreate, frameReplace));

        final Flux<FunctionFrame<T, R>> framed = flux.takeUntilOther(killSwitch.asMono())
                .map(obj -> recycler.getOrCreate(obj, password));

        EuhedralSubscriber subscriber = new EuhedralSubscriber();
        return returnSink.asFlux().doOnSubscribe(
                        sub -> {
                            framed.subscribe(subscriber);
                            this.scheduler.ingest(subscriber);
                        })
                .doFinally(sig -> {
                    dead.setRelease(true);
                    killSwitch.tryEmitEmpty();
                    recycler.close();
                });
    }
}
