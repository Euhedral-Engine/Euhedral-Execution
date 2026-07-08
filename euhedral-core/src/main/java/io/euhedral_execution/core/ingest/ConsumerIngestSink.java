package io.euhedral_execution.core.ingest;

import io.euhedral_execution.core.frames.ConsumerFrame;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameFactory.FrameCreate;
import io.euhedral_execution.core.impl.FrameFactory.FrameReplace;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.hashing.HasherApi;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

@SuppressWarnings("unused")
public final class ConsumerIngestSink<T> extends AbstractIngestSink {

    private static final VarHandle COMPLETE;

    static {
        try {
            COMPLETE = MethodHandles.lookup()
                    .findVarHandle(ConsumerIngestSink.class, "complete", boolean.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private final FrameManager<T, ConsumerFrame<T>> frameManager;
    private final QueueIngestSink sink;

    private final long password = ThreadLocalRandom.current().nextLong();
    private final AtomicBoolean killSwitch = new AtomicBoolean(false);

    boolean complete;

    public ConsumerIngestSink(
            @NonNull Consumer<T> consumer,
            boolean parallel) {
        this(consumer, parallel, new QueueIngestSink());
    }

    public ConsumerIngestSink(
            @NonNull Consumer<T> consumer,
            boolean parallel,
            @NonNull QueueIngestSink sink) {
        Objects.requireNonNull(consumer);
        Objects.requireNonNull(sink);
        this.sink = sink;
        this.frameManager = new FrameManager<>(password);

        final long[] seed = {HasherApi.mix(password + 1)};
        FrameCreate<T, ConsumerFrame<T>> generate = (idHash, data) -> {
            ConsumerFrame<T> frame = new ConsumerFrame<>(idHash, consumer, data,
                    killSwitch, frameManager);
            if (parallel) {
                frame.randomizeHash(seed[0]++);
            }
            return frame;
        };
        FrameReplace<T, ConsumerFrame<T>> replace = (data, oldFrame) -> {
            oldFrame.replace(data);
            if (parallel) {
                oldFrame.randomizeHash(seed[0]++);
            }
        };

        this.frameManager.setFactory(new FrameFactory<>(generate, replace));
    }

    @Override
    public LatticeSource getDelegate() {
        return this.sink.getDelegate();
    }

    public void push(T data) {
        ConsumerFrame<T> frame = this.frameManager.getOrCreate(data, this.password);
        offerInternal(frame);
    }

    public void push(Collection<T> collection) {
        for (T data : collection) {
            ConsumerFrame<T> frame = this.frameManager.getOrCreate(data, this.password);
            offerInternal(frame);
        }
    }

    public void push(Iterator<T> iter) {
        while (iter.hasNext()) {
            ConsumerFrame<T> frame = this.frameManager.getOrCreate(iter.next(), this.password);
            offerInternal(frame);
        }
    }

    @Override
    public void complete() {
        if (COMPLETE.compareAndSet(this, false, true)) {
            this.killSwitch.setRelease(true);
            this.sink.complete();
        }
    }

    public void completeGracefully() {
        if (COMPLETE.compareAndSet(this, false, true)) {
            this.sink.completeGracefully();
        }
    }

    private void offerInternal(ConsumerFrame<T> frame) {
        int cycles = 0;
        while (!sink.offer(frame)) {
            if ((cycles++ & 127) == 0) {
                Thread.onSpinWait();
            } else if (cycles < 512) {
                Thread.yield();
            } else {
                LockSupport.parkNanos(10_000L);
            }
        }
    }
}
