package io.euhedral_execution.core.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.PipelineFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class PipelineRunnerAdmissionTest {
    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void immediateCloseAfterAcceptanceBeforeRootPublicationCancelsEverySubmissionMode() throws Exception {
        for (var mode : SubmissionMode.values()) {
            var gate = new Gate();
            try (var run = new AdmissionRun(PipelineFrame.<Object>builder(), gate)) {
                gateCheckout(run.runner, gate, null);
                Object input = new Object();
                var submitted = run.start(() -> mode.submit(run.runner, input));
                gate.awaitEntered();
                // The real factory has produced a root, but checkout has not installed its notification yet.
                assertThat(run.runner.size()).isZero();
                assertThat(submitted.isDone()).isFalse();
                run.source.complete();
                assertThat(run.runner.isComplete()).isTrue();
                assertThat(run.completions.get()).isOne();
                assertThat(manager(run.runner).getRecycleQueue().sizeLong()).isZero();

                gate.release();
                var outcome = submitted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (outcome != null) assertOutcome(outcome, PipelineFrame.Status.CANCELLED);
                assertThat(run.executions.get()).isZero();
                assertThat(run.values).isEmpty();
                assertThat(run.runner.size()).isZero();
                assertThat(manager(run.runner).getRecycleQueue().sizeLong()).isOne();
                assertNoAcceptedChains(run.runner);
                run.runner.completeGracefully();
                assertThat(run.completions.get()).isOne();
            }
        }
    }

    @Test
    void gracefulCloseAfterAcceptanceBeforeRootPublicationWaitsForEverySubmissionMode() throws Exception {
        for (var mode : SubmissionMode.values()) {
            var gate = new Gate();
            try (var run = new AdmissionRun(PipelineFrame.<Object>builder(), gate)) {
                gateCheckout(run.runner, gate, null);
                Object input = new Object();
                var submitted = run.start(() -> mode.submit(run.runner, input));
                gate.awaitEntered();
                assertThat(run.runner.size()).isZero();
                run.runner.completeGracefully();
                assertThat(run.runner.isComplete()).isFalse();
                assertThat(run.completions.get()).isZero();
                assertThat(submitted.isDone()).isFalse();

                gate.release();
                var outcome = submitted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(run.runner.size()).isOne();
                assertThat(run.runner.isComplete()).isFalse();
                run.request(1);
                if (outcome != null) assertOutcome(outcome, PipelineFrame.Status.SUCCESS);
                assertThat(run.values).containsExactly(input);
                assertThat(run.executions.get()).isOne();
                assertThat(run.runner.isComplete()).isTrue();
                assertThat(run.runner.size()).isZero();
                assertThat(run.runner.getDemand()).isZero();
                assertThat(run.completions.get()).isOne();
                assertNoAcceptedChains(run.runner);
            }
        }
    }

    @Test
    void completedAdmissionRejectsEveryModeWhileDownstreamNotificationIsStillRunning() throws Exception {
        for (boolean graceful : new boolean[] {false, true}) {
            var gate = new Gate();
            try (var run = new AdmissionRun(PipelineFrame.<Object>builder(), gate, gate::pause)) {
                var closed = run.start(() -> {
                    if (graceful) run.runner.completeGracefully();
                    else run.source.complete();
                    return null;
                });
                gate.awaitEntered();
                assertThat(run.runner.isComplete()).isTrue();
                assertThat(closed.isDone()).isFalse();
                // Rejections never enter manager checkout, so this does not introduce a second checkout owner.
                for (var mode : SubmissionMode.values()) {
                    assertThatThrownBy(() -> mode.submit(run.runner, new Object()))
                            .isInstanceOf(IllegalStateException.class);
                }
                assertThat(manager(run.runner).getRecycleQueue().sizeLong()).isZero();
                assertThat(run.runner.size()).isZero();
                assertThat(run.executions.get()).isZero();
                gate.release();
                closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                run.runner.completeGracefully();
                run.runner.complete();
                assertThat(run.completions.get()).isOne();
            }
        }
    }

    @Test
    void failedCheckoutAfterGracefulCloseReleasesAcceptedChainWithoutPublishingWork() throws Exception {
        var gate = new Gate();
        var failure = new IllegalStateException("factory failure after admission");
        try (var run = new AdmissionRun(PipelineFrame.<Object>builder(), gate)) {
            gateCheckout(run.runner, gate, failure);
            var submitted = run.start(() -> {
                assertThatThrownBy(() -> run.runner.submit(new Object())).isSameAs(failure);
                return null;
            });
            gate.awaitEntered();
            run.runner.completeGracefully();
            assertThat(run.runner.isComplete()).isFalse();
            assertThat(run.completions.get()).isZero();
            gate.release();
            submitted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(run.runner.isComplete()).isTrue();
            assertThat(run.completions.get()).isOne();
            assertThat(run.runner.size()).isZero();
            assertThat(run.executions.get()).isZero();
            assertThat(manager(run.runner).getRecycleQueue().sizeLong()).isZero();
            assertNoAcceptedChains(run.runner);
        }
    }

    @Test
    void gracefulCloseOfIdleRunnerNeedsNoDrainAndDetachesDownstreamWithOutstandingDemand() throws Exception {
        var gate = new Gate();
        try (var run = new AdmissionRun(PipelineFrame.<Object>builder(), gate)) {
            run.request(7);
            assertThat(run.runner.getDemand()).isEqualTo(7);
            var closed = run.start(() -> {
                run.runner.completeGracefully();
                return null;
            });
            closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(run.runner.isComplete()).isTrue();
            assertThat(run.completions.get()).isOne();
            assertThat(run.runner.getDemand()).isZero();
            assertThat(((AbstractIngestSink.Delegate) run.source).getDownstream())
                    .isNull();
            run.request(1);
            assertThat(run.source.pull(
                            frame -> {
                                throw new AssertionError("idle closed runner delivered work");
                            },
                            frame -> false,
                            1))
                    .isZero();
            assertThat(run.runner.getDemand()).isZero();
            assertThat(run.executions.get()).isZero();
            run.runner.completeGracefully();
            assertThat(run.completions.get()).isOne();
        }
    }

    @Test
    void gracefulCompletionClearsPerRunStateButRetainsTheReusableGraph() throws Exception {
        var gate = new Gate();
        try (var run = new AdmissionRun(
                PipelineFrame.<Object>builder().fanOut(Function.identity()).filterOutput(value -> true), gate)) {
            Object input = new Object();
            // This test thread is the sole checkout owner; no background submission is used here.
            var outcome = run.runner.submit(input);
            run.runner.completeGracefully();
            run.request(1);
            assertThat(outcome.isDone()).isFalse();
            run.request(1);
            assertOutcome(outcome, PipelineFrame.Status.SUCCESS);
            assertThat(run.runner.isComplete()).isTrue();
            assertThat(run.completions.get()).isOne();
            assertThat(run.values).containsExactly(input);
            assertThat(((AbstractIngestSink.Delegate) run.source).getDownstream())
                    .isNull();

            // Reacquire exclusive ownership before inspecting a recycled root, never inspect an old borrow.
            var pool = manager(run.runner);
            long password = (long) readField(PipelineRunner.class, run.runner, "password");
            var root = pool.get(password);
            assertThat(root).isNotNull();
            assertThat(pool.get(password)).isNull();
            assertThat(readField(PipelineFrame.class, root, "outcome")).isNull();
            assertThat(readField(PipelineFrame.class, root, "completion")).isNull();
            assertThat(readField(PipelineFrame.class, root, "function")).isNotNull();
            assertThat(readField(PipelineFrame.class, root, "filter")).isNotNull();
            var terminal = (PipelineFrame<?>) readField(PipelineFrame.class, root, "nextFrame");
            assertThat(terminal).isNotNull();
            assertThat(readField(PipelineFrame.class, terminal, "consumer")).isNotNull();
            assertThat(readField(PipelineFrame.class, terminal, "rootFrame")).isSameAs(root);
            assertThat(readField(PipelineFrame.class, terminal, "nextFrame")).isNull();
            for (var stage : List.of(root, terminal)) {
                assertThat(readField(PipelineFrame.class, stage, "data")).isNull();
                assertThat(readField(PipelineFrame.class, stage, "filtered")).isEqualTo(false);
                assertThat(readField(PipelineFrame.class, stage, "cancelled")).isEqualTo(false);
                assertThat(readField(PipelineFrame.class, stage, "sink")).isSameAs(run.runner);
            }
        }
    }

    private static void assertOutcome(CompletableFuture<PipelineFrame.Outcome> future, PipelineFrame.Status status)
            throws Exception {
        var outcome = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(outcome.status()).isEqualTo(status);
        assertThat(outcome.failure()).isNull();
    }

    /** Local white-box seam: no production hooks or mocked execution/finalization. Installed before the owner starts. */
    private static void gateCheckout(PipelineRunner<Object> runner, Gate gate, RuntimeException failure)
            throws Exception {
        var pool = manager(runner);
        var original = pool.getFactory();
        var gated = new FrameFactory<Object, PipelineFrame<Object>>(
                (idHash, input) -> {
                    if (failure != null) {
                        gate.pause();
                        throw failure;
                    }
                    var root = original.create(input);
                    gate.pause();
                    return root;
                },
                original::replace);
        var factory = FrameManager.class.getDeclaredField("factory");
        factory.setAccessible(true);
        factory.set(pool, gated);
    }

    @SuppressWarnings("unchecked")
    private static FrameManager<Object, PipelineFrame<Object>> manager(PipelineRunner<Object> runner) throws Exception {
        return (FrameManager<Object, PipelineFrame<Object>>) readField(PipelineRunner.class, runner, "manager");
    }

    private static Object readField(Class<?> owner, Object instance, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void assertNoAcceptedChains(PipelineRunner<Object> runner) throws Exception {
        synchronized (runner.lifecycleLock) {
            assertThat(readField(PipelineRunner.class, runner, "inFlight")).isEqualTo(0L);
        }
    }

    private enum SubmissionMode {
        SUBMIT,
        SUBMIT_PARTITION,
        SUBMIT_SEED,
        RUN,
        RUN_PARTITION,
        RUN_SEED;

        private CompletableFuture<PipelineFrame.Outcome> submit(PipelineRunner<Object> runner, Object input) {
            return switch (this) {
                case SUBMIT -> runner.submit(input);
                case SUBMIT_PARTITION -> runner.submit(1, input);
                case SUBMIT_SEED -> runner.submit(17L, input);
                case RUN -> {
                    runner.run(input);
                    yield null;
                }
                case RUN_PARTITION -> {
                    runner.run(1, input);
                    yield null;
                }
                case RUN_SEED -> {
                    runner.run(17L, input);
                    yield null;
                }
            };
        }
    }

    private static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private void pause() {
            this.entered.countDown();
            await(this.released);
        }

        private void awaitEntered() {
            await(this.entered);
        }

        private static void await(CountDownLatch latch) {
            try {
                assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("gate acknowledged")
                        .isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("gate interrupted", e);
            }
        }

        private void release() {
            this.released.countDown();
        }
    }

    private static final class AdmissionRun implements AutoCloseable {
        private final Gate gate;
        private final ExecutorService owner = Executors.newSingleThreadExecutor();
        private final List<Future<?>> operations = new ArrayList<>();
        private final List<Object> values = new ArrayList<>();
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final PipelineRunner<Object> runner;
        private final LatticeSource source;

        private AdmissionRun(PipelineFrame.Builder<Object, Object> builder, Gate gate) {
            this(builder, gate, () -> {});
        }

        private AdmissionRun(PipelineFrame.Builder<Object, Object> builder, Gate gate, Runnable notification) {
            this.gate = gate;
            this.runner = new PipelineRunner<>(builder, this.values::add, true, 2);
            this.source = this.runner.getDelegate();
            new AbstractExecutor(-1) {
                @Override
                public void execute(AbstractFrame frame) {
                    executions.incrementAndGet();
                    frame.execute();
                }

                @Override
                public AbstractExecutor hookOnClone(int cpu) {
                    throw new UnsupportedOperationException("test executor is not cloned");
                }
            }.input(new CompletionSource(this.source, () -> {
                completions.incrementAndGet();
                notification.run();
            }));
        }

        private <T> Future<T> start(java.util.concurrent.Callable<T> operation) {
            var future = this.owner.submit(operation);
            this.operations.add(future);
            return future;
        }

        private void request(long demand) throws Exception {
            start(() -> {
                        this.source.request(demand);
                        return null;
                    })
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            this.gate.release();
            this.owner.shutdown();
            try {
                if (!this.owner.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    this.owner.shutdownNow();
                    assertThat(this.owner.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            .as("submission/close owner terminated")
                            .isTrue();
                }
                for (var operation : this.operations) operation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                this.owner.shutdownNow();
                this.runner.complete();
            }
        }
    }

    /** Forward the real registration without injecting addUpstream or unlimited demand. */
    private record CompletionSource(LatticeSource delegate, Runnable completed) implements LatticeSource {
        @Override
        public void addDownstream(LatticeReceiver receiver) {
            this.delegate.addDownstream(new LatticeReceiver() {
                @Override
                public void addUpstream(LatticeSource source) {
                    receiver.addUpstream(source);
                }

                @Override
                public void push(AbstractFrame frame) {
                    receiver.push(frame);
                }

                @Override
                public void onError(Throwable throwable) {
                    receiver.onError(throwable);
                }

                @Override
                public void onComplete() {
                    completed.run();
                    receiver.onComplete();
                }
            });
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            return this.delegate.pull(consumer, stopCondition, demand);
        }

        @Override
        public void request(long demand) {
            this.delegate.request(demand);
        }

        @Override
        public void complete() {
            this.delegate.complete();
        }

        @Override
        public boolean isComplete() {
            return this.delegate.isComplete();
        }
    }
}
