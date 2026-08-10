package io.euhedral_execution.reactor;

import io.euhedral_execution.core.frames.CallbackFrame;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameFactory.FrameCreate;
import io.euhedral_execution.core.impl.FrameFactory.FrameReplace;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.BoundedMpmcQueue;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.reactor.common.EuhedralSink;
import io.euhedral_execution.reactor.common.EuhedralSubscriber;
import io.euhedral_execution.reactor.common.FrameSequencer;
import io.euhedral_execution.reactor.common.SequencedFrame;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

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

    private final int responseQueueSize;

    private final PaddedAtomicLong seed =
            new PaddedAtomicLong(ThreadLocalRandom.current().nextLong());

    public EuhedralOperator(EuhedralScheduler scheduler) {
        this(scheduler, 2_048, 4_096);
    }

    public EuhedralOperator(EuhedralScheduler scheduler, int recycleCapacity, int responseQueueSize) {
        Objects.requireNonNull(scheduler, "Scheduler must not be null");
        if (recycleCapacity < 0) {
            throw new IllegalArgumentException("Recycle capacity must be non-negative");
        }
        this.scheduler = scheduler;
        this.recycleCapacity = recycleCapacity;
        this.responseQueueSize = responseQueueSize;
    }

    public @NonNull <T, R> Function<Flux<T>, Publisher<R>> flatMap(@NonNull Function<T, R> mapper) {
        return flux -> flatMap(flux, mapper);
    }

    public @NonNull <T, R> Function<Flux<T>, Publisher<R>> flatMapSequential(Function<T, R> mapper) {
        return flux -> flatMapSequential(flux, mapper);
    }

    public @NonNull <T, R> Function<Flux<T>, Publisher<R>> concatMap(Function<T, R> mapper) {
        return flux -> concatMap(flux, mapper);
    }

    public @NonNull <T, R> Publisher<R> flatMap(@NonNull Flux<T> flux, @NonNull Function<T, R> mapper) {
        Objects.requireNonNull(flux);
        Objects.requireNonNull(mapper);
        return map(flux, mapper, false);
    }

    public @NonNull <T, R> Publisher<R> flatMapSequential(@NonNull Flux<T> flux, @NonNull Function<T, R> mapper) {
        Objects.requireNonNull(flux);
        Objects.requireNonNull(mapper);

        long seed = this.seed.getAndAddRelease(1);
        final long password = HasherApi.mix(seed);

        FrameSequencer<T, R> sequencer = new FrameSequencer<>(password);
        EuhedralSubscriber subscriber = new EuhedralSubscriber();

        Flux<SequencedFrame<T, R>> framed =
                flux.transform(sequencer.flatMapSequentialTransformer(mapper, this.recycleCapacity));

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

    private <T, R> Publisher<R> map(Flux<T> raw, Function<T, R> function, boolean ordered) {
        EuhedralSink<T, R> response = new EuhedralSink<>(new BoundedMpmcQueue<>(this.responseQueueSize));

        long[] seed = {this.seed.getAndAddRelease(1)};
        final long password = HasherApi.mix(seed[0]++);

        AtomicBoolean senderSwitch = new AtomicBoolean();
        Sinks.One<Void> killSwitch = Sinks.unsafe().one();

        FrameManager<T, CallbackFrame<T, R>> recycler = new FrameManager<>(this.recycleCapacity, password);

        FrameCreate<T, CallbackFrame<T, R>> frameCreate = (idHash, data) -> {
            CallbackFrame<T, R> frame = new CallbackFrame<>(idHash, data, function, response, recycler, senderSwitch);
            if (!ordered) {
                frame.randomizeHash(seed[0]++);
            }
            return frame;
        };
        FrameReplace<T, CallbackFrame<T, R>> frameReplace = (data, oldFrame) -> {
            oldFrame.replace(data);
            if (!ordered) {
                oldFrame.randomizeHash(seed[0]++);
            }
        };
        recycler.setFactory(new FrameFactory<>(frameCreate, frameReplace));

        EuhedralSubscriber subscriber = new EuhedralSubscriber();
        return response.asFlux()
                .doOnSubscribe(sub -> {
                    raw.takeUntilOther(killSwitch.asMono())
                            .map(obj -> recycler.getOrCreate(obj, password))
                            .doOnCancel(() -> {
                                senderSwitch.setRelease(true);
                                killSwitch.tryEmitEmpty();
                            })
                            .doOnError(err -> {
                                senderSwitch.setRelease(true);
                                killSwitch.tryEmitEmpty();
                            })
                            .subscribe(subscriber);
                    this.scheduler.ingest(subscriber);
                })
                .doOnCancel(() -> {
                    senderSwitch.setRelease(true);
                    killSwitch.tryEmitEmpty();
                })
                .doOnError(err -> {
                    senderSwitch.setRelease(true);
                    killSwitch.tryEmitEmpty();
                });
    }
}
