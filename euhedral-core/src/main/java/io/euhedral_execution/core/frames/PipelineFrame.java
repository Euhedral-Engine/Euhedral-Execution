package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import io.euhedral_execution.hashing.HasherApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/// A frame in a typed, multi-stage pipeline with independently ordered or parallel stages.
///
/// Build pipelines with [#builder(QueueIngestSink)], add transformations with
/// [Builder#mapParallel] or [Builder#mapOrdered], and bind the pipeline to an input and terminal
/// consumer with [Builder#buildParallel] or [Builder#buildOrdered]. Each transform and the terminal
/// consumer is scheduled as a separate frame.
@SuppressWarnings("unused")
public final class PipelineFrame<T> extends AbstractFrame {

    private final QueueIngestSink sink;
    private final @Nullable Function<Object, Object> function;
    private final @Nullable Consumer<Object> consumer;
    private final boolean ordered;

    private PipelineFrame<T> rootFrame;
    private @Nullable PipelineFrame<?> nextFrame;
    private Object data;

    private PipelineFrame(
            QueueIngestSink sink,
            long idHash,
            @Nullable FrameManager<T, PipelineFrame<T>> manager,
            @Nullable AtomicBoolean killSwitch,
            @Nullable Function<Object, Object> function,
            @Nullable Consumer<Object> consumer,
            boolean ordered) {
        super(idHash, manager, killSwitch);
        this.sink = sink;
        this.function = function;
        this.consumer = consumer;
        this.ordered = ordered;
        this.rootFrame = this;
    }

    @Override
    public void execute() {
        Object result = this.function == null ? this.data : this.function.apply(this.data);
        if (this.nextFrame != null) {
            this.nextFrame.data = result;
        }
        if (this.consumer != null) {
            this.consumer.accept(result);
        }
    }

    @Override
    public void doFinally() {
        if (!isAlive() || this.nextFrame == null) {
            this.rootFrame.recycle();
        } else if (!this.sink.offer(this.nextFrame)) {
            this.rootFrame.recycle();
            throw new IllegalStateException("Could not enqueue the next pipeline stage");
        }
    }

    @Override
    public void doFinallyWithError(Throwable throwable) {
        this.rootFrame.recycle();
    }

    @Override
    public boolean isAlive() {
        return this.rootFrame == this ? super.isAlive() : this.rootFrame.isAlive();
    }

    @Override
    public void kill() {
        if (this.rootFrame == this) {
            super.kill();
        } else {
            this.rootFrame.kill();
        }
    }

    private void replace(T data, long routingSeed) {
        this.data = data;
        PipelineFrame<?> current = this;
        while (current != null) {
            current.resetHash();
            if (!current.ordered) {
                current.randomizeHash(routingSeed);
            }
            routingSeed++;
            current = current.nextFrame;
        }
    }

    private PipelineFrame<T> copy(
            long idHash,
            T data,
            @Nullable FrameManager<T, PipelineFrame<T>> manager,
            @Nullable AtomicBoolean killSwitch,
            long routingSeed) {
        PipelineFrame<T> copiedRoot = null;
        PipelineFrame<?> copiedPrevious = null;
        PipelineFrame<?> template = this;

        while (template != null) {
            PipelineFrame<T> copied = new PipelineFrame<>(
                    template.sink,
                    idHash,
                    copiedRoot == null ? manager : null,
                    copiedRoot == null ? killSwitch : null,
                    template.function,
                    template.consumer,
                    template.ordered);
            if (!copied.ordered) {
                copied.randomizeHash(routingSeed);
            }
            routingSeed++;

            if (copiedRoot == null) {
                copiedRoot = copied;
                copiedRoot.data = data;
            } else {
                copied.rootFrame = copiedRoot;
                copiedPrevious.nextFrame = copied;
            }
            copiedPrevious = copied;
            template = template.nextFrame;
        }

        return copiedRoot;
    }

    /// Starts a reusable, compile-time-typed pipeline definition.
    public static <I> Builder<I, I> builder(QueueIngestSink sink) {
        return new Builder<>(Objects.requireNonNull(sink), List.of());
    }

    /// A composable pipeline definition from input type `I` to the current output type `O`.
    ///
    /// Builders are immutable: adding a stage or lifecycle option returns a new definition, so a
    /// shared prefix can safely be used to create several pipelines.
    public static final class Builder<I, O> {

        private final QueueIngestSink sink;
        private final List<Stage> stages;

        private Builder(QueueIngestSink sink, List<Stage> stages) {
            this.sink = sink;
            this.stages = stages;
        }

        /// Appends a parallel transformation and advances the builder's output type.
        public <N> Builder<I, N> mapParallel(Function<? super O, ? extends N> function) {
            return append(function, false);
        }

        /// Appends an ordered transformation and advances the builder's output type.
        public <N> Builder<I, N> mapOrdered(Function<? super O, ? extends N> function) {
            return append(function, true);
        }

        private <N> Builder<I, N> append(Function<? super O, ? extends N> function, boolean ordered) {
            Objects.requireNonNull(function);
            List<Stage> nextStages = new ArrayList<>(this.stages);
            nextStages.add(new Stage(value -> function.apply(cast(value)), ordered));
            return new Builder<>(this.sink, List.copyOf(nextStages));
        }

        /// Binds an input and parallel terminal consumer to this definition.
        public PipelineFrame<I> buildParallel(I data, Consumer<? super O> consumer) {
            return create(data, consumer, false, null, null, 0L);
        }

        /// Builds a parallel terminal with a root-owned kill switch.
        public PipelineFrame<I> buildParallel(I data, Consumer<? super O> consumer, AtomicBoolean killSwitch) {
            return create(data, consumer, false, Objects.requireNonNull(killSwitch), null, 0L);
        }

        /// Builds a parallel terminal through a root-only recycler.
        public PipelineFrame<I> buildParallel(
                I data, Consumer<? super O> consumer, FrameManager<I, PipelineFrame<I>> recycler, long password) {
            return create(data, consumer, false, null, Objects.requireNonNull(recycler), password);
        }

        /// Builds a parallel terminal with root-owned cancellation and recycling.
        public PipelineFrame<I> buildParallel(
                I data,
                Consumer<? super O> consumer,
                AtomicBoolean killSwitch,
                FrameManager<I, PipelineFrame<I>> recycler,
                long password) {
            return create(
                    data,
                    consumer,
                    false,
                    Objects.requireNonNull(killSwitch),
                    Objects.requireNonNull(recycler),
                    password);
        }

        /// Binds an input and ordered terminal consumer to this definition.
        public PipelineFrame<I> buildOrdered(I data, Consumer<? super O> consumer) {
            return create(data, consumer, true, null, null, 0L);
        }

        /// Builds an ordered terminal with a root-owned kill switch.
        public PipelineFrame<I> buildOrdered(I data, Consumer<? super O> consumer, AtomicBoolean killSwitch) {
            return create(data, consumer, true, Objects.requireNonNull(killSwitch), null, 0L);
        }

        /// Builds an ordered terminal through a root-only recycler.
        public PipelineFrame<I> buildOrdered(
                I data, Consumer<? super O> consumer, FrameManager<I, PipelineFrame<I>> recycler, long password) {
            return create(data, consumer, true, null, Objects.requireNonNull(recycler), password);
        }

        /// Builds an ordered terminal with root-owned cancellation and recycling.
        public PipelineFrame<I> buildOrdered(
                I data,
                Consumer<? super O> consumer,
                AtomicBoolean killSwitch,
                FrameManager<I, PipelineFrame<I>> recycler,
                long password) {
            return create(
                    data,
                    consumer,
                    true,
                    Objects.requireNonNull(killSwitch),
                    Objects.requireNonNull(recycler),
                    password);
        }

        private PipelineFrame<I> create(
                I data,
                Consumer<? super O> consumer,
                boolean terminalOrdered,
                @Nullable AtomicBoolean killSwitch,
                @Nullable FrameManager<I, PipelineFrame<I>> recycler,
                long password) {
            Objects.requireNonNull(data);
            Objects.requireNonNull(consumer);

            long prototypeId = HasherApi.mix(ThreadLocalRandom.current().nextLong());
            PipelineFrame<I> prototype = createPrototype(prototypeId, consumer, terminalOrdered);
            if (recycler == null && killSwitch == null) {
                prototype.data = data;
                routeChain(prototype, prototypeId + 1);
                return prototype;
            }

            long[] routingSeed = {ThreadLocalRandom.current().nextLong()};
            if (recycler == null) {
                return prototype.copy(prototypeId, data, null, killSwitch, routingSeed[0]);
            }

            FrameFactory<I, PipelineFrame<I>> factory = new FrameFactory<>(
                    (idHash, input) -> prototype.copy(idHash, input, recycler, killSwitch, routingSeed[0]++),
                    (input, root) -> root.replace(input, routingSeed[0]++));
            recycler.setFactory(factory);
            return recycler.getOrCreate(data, password);
        }

        private PipelineFrame<I> createPrototype(long idHash, Consumer<? super O> consumer, boolean terminalOrdered) {
            PipelineFrame<I> root = null;
            PipelineFrame<?> previous = null;

            for (Stage stage : this.stages) {
                PipelineFrame<I> current =
                        new PipelineFrame<>(this.sink, idHash, null, null, stage.function(), null, stage.ordered());
                if (root == null) {
                    root = current;
                } else {
                    current.rootFrame = root;
                    previous.nextFrame = current;
                }
                previous = current;
            }

            Consumer<Object> terminalConsumer = value -> consumer.accept(cast(value));
            PipelineFrame<I> terminal =
                    new PipelineFrame<>(this.sink, idHash, null, null, null, terminalConsumer, terminalOrdered);

            if (root == null) {
                return terminal;
            }

            terminal.rootFrame = root;
            previous.nextFrame = terminal;
            return root;
        }

        private static void routeChain(PipelineFrame<?> root, long routingSeed) {
            PipelineFrame<?> current = root;
            while (current != null) {
                setRouting(current, current.ordered, routingSeed++);
                current = current.nextFrame;
            }
        }

        private static void setRouting(PipelineFrame<?> frame, boolean ordered, long routingSeed) {
            if (!ordered) {
                frame.randomizeHash(routingSeed);
            }
        }

        @SuppressWarnings("unchecked")
        private static <V> V cast(Object value) {
            return (V) value;
        }

        private record Stage(Function<Object, Object> function, boolean ordered) {}
    }
}
