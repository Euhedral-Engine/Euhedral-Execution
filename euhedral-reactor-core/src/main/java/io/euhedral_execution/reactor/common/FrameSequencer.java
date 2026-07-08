package io.euhedral_execution.reactor.common;

import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameFactory.FrameCreate;
import io.euhedral_execution.core.impl.FrameFactory.FrameReplace;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.PartitionedSpscQueue;
import io.euhedral_execution.hashing.HasherApi;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

public class FrameSequencer<T, R> {

    private final long ingestPassword;
    private final long sequencePassword;

    private final PaddedAtomicLong wip = new PaddedAtomicLong(0);
    private final PartitionedSpscQueue<SequencedFrame<T, R>> sequence = new PartitionedSpscQueue<>(
            1, 32_768, 1);

    private final Sinks.Many<R> output = Sinks.unsafe().many().unicast()
            .onBackpressureBuffer(new PartitionedSpscQueue<>(8_192));

    public FrameSequencer(long ingestPassword) {
        this.ingestPassword = ingestPassword;
        this.sequencePassword = HasherApi.combine(ingestPassword,
                ingestPassword + HasherApi.BASE_SEED);
    }

    public void drain(long password) {
        if (password == sequencePassword) {
            drainInternal();
        }
    }

    public Function<Flux<T>, Publisher<SequencedFrame<T, R>>> flatMapSequentialTransformer(
            Function<T, R> function,
            int recycleCapacity) {
        return flux -> flatMapSequential(flux, function, recycleCapacity);
    }

    public Flux<SequencedFrame<T, R>> flatMapSequential(Flux<T> flux, Function<T, R> function,
            int recycleCapacity) {
        final AtomicBoolean killSwitch = new AtomicBoolean(false);

        long[] seed = {ThreadLocalRandom.current().nextLong()};
        FrameManager<T, SequencedFrame<T, R>> recycler = new FrameManager<>(recycleCapacity,
                ingestPassword);
        FrameCreate<T, SequencedFrame<T, R>> frameCreate = (idHash, data) -> {
            SequencedFrame<T, R> frame = new SequencedFrame<>(idHash, data,
                    function,
                    killSwitch, this, recycler);
            frame.randomizeHash(seed[0]++);
            registerFrame(frame);
            return frame;
        };
        FrameReplace<T, SequencedFrame<T, R>> frameReplace = (data, oldFrame) -> {
            oldFrame.replace(data);
            oldFrame.randomizeHash(seed[0]++);
            registerFrame(oldFrame);
        };
        recycler.setFactory(new FrameFactory<>(frameCreate, frameReplace));

        return flux.map(obj -> recycler.getOrCreate(obj, ingestPassword))
                .doFinally(sig -> {
                    killSwitch.set(true);
                    sequence.clear();
                    output.tryEmitComplete();
                    recycler.close();
                });
    }

    public Flux<R> output() {
        return output.asFlux();
    }

    private void drainInternal() {
        if (this.wip.getAndIncrement() == 0) {
            try {
                do {
                    SequencedFrame<T, R> frame;
                    while ((frame = this.sequence.peek()) != null) {
                        if (frame.isReady()) {
                            SequencedFrame<T, R> temp = this.sequence.poll();
                            while (frame != temp) {
                                temp = this.sequence.poll();
                                if (temp == null) {
                                    break;
                                }
                            }
                            if (temp == null) {
                                break;
                            }

                            EmitResult result = BackpressureHandler.push(frame.getRetVal(), output);
                            if (result == EmitResult.FAIL_CANCELLED
                                    || result == EmitResult.FAIL_TERMINATED
                                    || result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                                frame.kill();
                                this.sequence.clear();
                                this.output.tryEmitComplete();
                                return;
                            }
                        } else {
                            break;
                        }
                    }
                } while (this.wip.decrementAndGet() != 0);
            } catch (Throwable t) {
                System.err.println("Uncaught Exception: " + t.getMessage());
            } finally {
                this.wip.setRelease(0);
            }
        }
    }

    private void registerFrame(SequencedFrame<T, R> frame) {
        frame.setSequencerPassword(sequencePassword);
        while (!this.sequence.offer(frame)) {
            Thread.onSpinWait();
        }
    }
}
