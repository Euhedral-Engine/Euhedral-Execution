package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/// A frame in a typed, multi-stage pipeline with independently fan-in or fan-out stages.
///
/// Build pipelines with [#builder()], add transformations with
/// [Builder#fanOut] or [Builder#fanIn], and bind the pipeline to an input and final
/// consumer with [Builder#composeFannedOut] or [Builder#composeFannedIn]. Each transform and the final
/// consumer is scheduled as a separate frame.
@SuppressWarnings("unused")
public final class PipelineFrame<T> extends AbstractFrame {

    private final QueueIngestSink sink;
    private final @Nullable Function<Object, Object> function;
    private @Nullable Consumer<Object> consumer;
    private final @Nullable Predicate<Object> filter;
    private final boolean ordered;

    private PipelineFrame<T> rootFrame;
    private @Nullable PipelineFrame<?> nextFrame;
    private @Nullable Object data;
    private @Nullable AtomicBoolean activeKillSwitch;

    private boolean filtered = false;
    private boolean cancelled;

    public enum Status {
        SUCCESS,
        FILTERED,
        CANCELLED,
        FAILED
    }

    public record Outcome(Status status, @Nullable Throwable failure) {}

    private static final Outcome SUCCESS = new Outcome(Status.SUCCESS, null);
    private static final Outcome FILTERED = new Outcome(Status.FILTERED, null);
    private static final Outcome CANCELLED = new Outcome(Status.CANCELLED, null);
    private @Nullable CompletableFuture<Outcome> outcome;
    private @Nullable Runnable completion;

    /// Optional observation, installed by the submission owner before publication. Published after
    /// clearing payloads and recycling, but before the owner notification. Dependent callbacks may
    /// run inline on the finalizer: they must not block awaiting pipeline progress or graceful close,
    /// and any submission must respect the single checkout owner.
    public void observe(CompletableFuture<Outcome> outcome) {
        this.rootFrame.outcome = Objects.requireNonNull(outcome);
    }

    /// Registers an owner notification before publication. Invoked after payload clearing, recycling,
    /// and outcome publication (including inline dependent callbacks). Must not block the finalizer.
    public void onCompletion(Runnable completion) {
        this.rootFrame.completion = Objects.requireNonNull(completion);
    }

    private PipelineFrame(
            QueueIngestSink sink,
            long idHash,
            @Nullable FrameManager<T, PipelineFrame<T>> manager,
            @Nullable AtomicBoolean killSwitch,
            @Nullable Function<Object, Object> function,
            @Nullable Consumer<Object> consumer,
            @Nullable Predicate<Object> filter,
            boolean ordered) {
        super(idHash, manager, killSwitch);
        this.sink = sink;
        this.function = function;
        this.consumer = consumer;
        this.filter = filter;
        this.ordered = ordered;
        this.rootFrame = this;
        this.activeKillSwitch = killSwitch;
        if (!ordered) {
            randomizeHash(17L);
        }
    }

    private void updateTerminalConsumer(Consumer<Object> consumer) {
        PipelineFrame<?> current = this;
        while (current.nextFrame != null) {
            current = current.nextFrame;
        }
        current.consumer = consumer;
    }

    private void updateKillSwitch(@Nullable AtomicBoolean killSwitch) {
        this.rootFrame.activeKillSwitch = killSwitch;
    }

    @Nullable
    PipelineFrame<?> getNextFrame() {
        return this.nextFrame;
    }

    @Override
    public void execute() {
        try {
            executeStage();
        } catch (CancelSignal signal) {
            this.cancelled = true;
            throw signal;
        }
    }

    private void executeStage() {
        Object result = this.function == null ? this.data : this.function.apply(this.data);

        if (this.filter != null && !this.filter.test(result)) {
            this.filtered = true;
            return;
        }

        if (this.nextFrame != null) {
            this.nextFrame.data = result;
        }
        if (this.consumer != null) {
            this.consumer.accept(result);
        }
    }

    @Override
    public void doFinally() {
        if (!isAlive() || this.cancelled || this.nextFrame == null || this.filtered) {
            this.rootFrame.finish(!isAlive() || this.cancelled ? CANCELLED : this.filtered ? FILTERED : SUCCESS);
        } else if (!this.sink.offer(this.nextFrame)) {
            if (!isAlive()) {
                this.rootFrame.finish(CANCELLED);
                return;
            }
            var failure = new IllegalStateException("Could not enqueue the next pipeline stage");
            doFinallyWithError(failure);
            throw failure;
        }
    }

    @Override
    public void doFinallyWithError(Throwable throwable) {
        this.rootFrame.finish(throwable instanceof CancelSignal ? CANCELLED : new Outcome(Status.FAILED, throwable));
    }

    private void finish(Outcome result) {
        Runnable notification = this.completion;
        CompletableFuture<Outcome> observer = this.outcome;
        this.completion = null;
        this.outcome = null;
        for (PipelineFrame<?> stage = this; stage != null; stage = stage.nextFrame) {
            stage.data = null;
            stage.filtered = false;
            stage.cancelled = false;
        }
        // No chain state may be read or written after publication to the recycler: the owner
        // may immediately reuse it, including reentrant submission from the detached observer.
        recycle();
        try {
            if (observer != null) observer.complete(result);
        } finally {
            // Every chain publishes its outcome before decrementing the runner's in-flight count.
            // Thus even concurrent finalizers cannot signal graceful completion ahead of an outcome.
            if (notification != null) notification.run();
        }
    }

    @Override
    public boolean isAlive() {
        if (this.rootFrame != this) {
            return this.rootFrame.isAlive();
        }
        return this.activeKillSwitch == null || !this.activeKillSwitch.getAcquire();
    }

    @Override
    public void kill() {
        if (this.rootFrame != this) {
            this.rootFrame.kill();
        } else if (this.activeKillSwitch != null) {
            this.activeKillSwitch.setRelease(true);
        }
    }

    private void replace(T data, long routingSeed) {
        this.data = data;
        this.filtered = false;
        PipelineFrame<?> current = this.nextFrame;
        while (current != null) {
            current.resetHash();
            if (!current.ordered) {
                current.randomizeHash(routingSeed);
            }
            routingSeed++;
            current = current.nextFrame;
        }
    }

    /// Starts a reusable pipeline definition.
    public static <I> Builder<I, I> builder() {
        return new Builder<>(List.of());
    }

    /// A composable pipeline definition from input type `I` to the current output type `O`.
    ///
    /// Builders are immutable: adding a stage or lifecycle option returns a new definition, so a
    /// shared prefix can safely be used to create several pipelines.
    public static final class Builder<I, O> {

        private final List<Stage> stages;
        private final @Nullable Predicate<Object> filter;

        private Builder(List<Stage> stages) {
            this.stages = stages;
            this.filter = null;
        }

        private Builder(List<Stage> stages, Predicate<Object> filter) {
            this.stages = stages;
            this.filter = filter;
        }

        public Builder<I, O> filterOutput(Predicate<? super O> predicate) {
            Objects.requireNonNull(predicate);
            if (this.stages.isEmpty()) {
                return new Builder<>(
                        List.of(), this.filter == null ? cast(predicate) : this.filter.and(cast(predicate)));
            }

            List<Stage> nextStages = new ArrayList<>(this.stages);
            Stage last = nextStages.getLast().copy();
            nextStages.set(nextStages.size() - 1, last);
            last.filter = last.filter == null ? cast(predicate) : last.filter.and(cast(predicate));
            return new Builder<>(List.copyOf(nextStages));
        }

        /// Appends a parallel transformation and advances the builder's output type.
        public <N> Builder<I, N> fanOut(Function<? super O, ? extends N> function) {
            return append(function, false);
        }

        /// Appends a serialized transformation and advances the builder's output type.
        public <N> Builder<I, N> fanIn(Function<? super O, ? extends N> function) {
            return append(function, true);
        }

        private <N> Builder<I, N> append(Function<? super O, ? extends N> function, boolean ordered) {
            Objects.requireNonNull(function);
            List<Stage> nextStages = new ArrayList<>(this.stages);
            if (this.filter != null) {
                nextStages.add(new Stage(Function.identity(), ordered, this.filter));
            }
            nextStages.add(new Stage(value -> function.apply(cast(value)), ordered));
            return new Builder<>(List.copyOf(nextStages));
        }

        /// Composes a pipeline with a parallel consumer.
        /// @return FrameManager for the pipeline
        public FrameManager<I, PipelineFrame<I>> composeFannedOut(
                QueueIngestSink sink, Consumer<? super O> consumer, long password, AtomicBoolean killSwitch) {
            return createFactory(sink, consumer, false, password, Objects.requireNonNull(killSwitch));
        }

        /// Composes a pipeline with a serialized consumer.
        /// @return FrameManager for the pipeline
        public FrameManager<I, PipelineFrame<I>> composeFannedIn(
                QueueIngestSink sink, Consumer<? super O> consumer, long password, AtomicBoolean killSwitch) {
            return createFactory(sink, consumer, true, password, Objects.requireNonNull(killSwitch));
        }

        private FrameManager<I, PipelineFrame<I>> createFactory(
                QueueIngestSink sink,
                Consumer<? super O> consumer,
                boolean terminalOrdered,
                long password,
                AtomicBoolean killSwitch) {
            Objects.requireNonNull(consumer);
            Objects.requireNonNull(killSwitch);

            long[] routingSeed = {ThreadLocalRandom.current().nextLong()};

            FrameManager<I, PipelineFrame<I>> manager = new FrameManager<>(password);
            FrameFactory<I, PipelineFrame<I>> factory = new FrameFactory<>(
                    (idHash, input) -> createChain(
                            idHash, input, manager, killSwitch, consumer, terminalOrdered, routingSeed[0]++, sink),
                    (input, root) -> root.replace(input, routingSeed[0]++));
            manager.setFactory(factory);
            return manager;
        }

        private PipelineFrame<I> createChain(
                long idHash,
                I data,
                @Nullable FrameManager<I, PipelineFrame<I>> recycler,
                @Nullable AtomicBoolean killSwitch,
                Consumer<? super O> consumer,
                boolean terminalOrdered,
                long routingSeed,
                QueueIngestSink sink) {
            PipelineFrame<I> root = null;
            PipelineFrame<?> previous = null;

            for (Stage stage : this.stages) {
                PipelineFrame<I> current = new PipelineFrame<>(
                        sink,
                        idHash,
                        root == null ? recycler : null,
                        root == null ? killSwitch : null,
                        stage.function,
                        null,
                        stage.filter,
                        stage.ordered);
                if (root == null) {
                    root = current;
                    root.data = data;
                } else {
                    current.rootFrame = root;
                    previous.nextFrame = current;
                }
                previous = current;
            }

            Consumer<Object> terminalConsumer = value -> consumer.accept(cast(value));
            PipelineFrame<I> terminal = new PipelineFrame<>(
                    sink,
                    idHash,
                    root == null ? recycler : null,
                    root == null ? killSwitch : null,
                    null,
                    terminalConsumer,
                    cast(this.filter),
                    terminalOrdered);

            if (root == null) {
                root = terminal;
                root.data = data;
            } else {
                terminal.rootFrame = root;
                previous.nextFrame = terminal;
            }

            routeChain(root, recycler != null, routingSeed);
            return root;
        }

        private static void routeChain(PipelineFrame<?> root, boolean isRecyclerBacked, long routingSeed) {
            PipelineFrame<?> current = isRecyclerBacked ? root.nextFrame : root;
            while (current != null) {
                current.resetHash();
                if (!current.ordered) {
                    current.randomizeHash(routingSeed);
                }
                routingSeed++;
                current = current.nextFrame;
            }
        }

        @SuppressWarnings("unchecked")
        private static <V> V cast(Object value) {
            return (V) value;
        }

        private static class Stage {
            final Function<Object, Object> function;
            final boolean ordered;

            @Nullable
            Predicate<Object> filter;

            Stage(Function<Object, Object> function, boolean ordered) {
                this.function = function;
                this.ordered = ordered;
            }

            Stage(Function<Object, Object> function, boolean ordered, @Nullable Predicate<Object> filter) {
                this.function = function;
                this.ordered = ordered;
                this.filter = filter;
            }

            Stage copy() {
                return new Stage(this.function, this.ordered, this.filter);
            }
        }
    }
}
