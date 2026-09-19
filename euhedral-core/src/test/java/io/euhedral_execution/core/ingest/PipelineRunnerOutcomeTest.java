package io.euhedral_execution.core.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.PipelineFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.core.impl.DefaultExecutor;
import io.euhedral_execution.core.impl.FrameManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

class PipelineRunnerOutcomeTest {
    private static final long PASSWORD = 17L;

    @ParameterizedTest
    @EnumSource(PipelineFrame.Status.class)
    void terminalOutcomeDetachesRunStateBeforeObservationAndSurvivesReuse(PipelineFrame.Status status)
            throws Exception {
        var sink = new QueueIngestSink();
        var source = sink.getDelegate();
        new DefaultExecutor().input(source);
        var failure = new IllegalArgumentException("middle stage");
        var callbackFailure = new IllegalStateException("dependent observer");
        var notificationFailure = new IllegalStateException("owner notification");
        var terminalCalls = new AtomicInteger();
        var notifications = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var manager = PipelineFrame.<Input>builder()
                .fanOut(value -> value)
                .fanIn(value -> {
                    if (value.status() == PipelineFrame.Status.CANCELLED) throw AbstractFrame.CANCEL_SIGNAL;
                    if (value.status() == PipelineFrame.Status.FAILED) throw failure;
                    return value;
                })
                .filterOutput(value -> value.status() != PipelineFrame.Status.FILTERED)
                .composeFannedOut(sink, value -> terminalCalls.incrementAndGet(), PASSWORD, new AtomicBoolean());
        try {
            var first = manager.getOrCreate(new Input(status, new Object()), PASSWORD);
            var originalStages = stages(first);
            var firstOutcome = new CompletableFuture<PipelineFrame.Outcome>();
            var checkedOut = new AtomicReference<PipelineFrame<Input>>();
            first.observe(firstOutcome);
            first.onCompletion(() -> {
                notifications.incrementAndGet();
                throw notificationFailure;
            });
            var dependent = firstOutcome.thenRun(() -> {
                // Same thread owns every checkout. Reacquire before inspecting any mutable frame state.
                var owned = manager.get(PASSWORD);
                checkedOut.set(owned);
                assertThat(owned).isSameAs(first);
                assertCleared(owned);
                assertThat(notifications.get()).isZero();
                callbacks.incrementAndGet();
                throw callbackFailure;
            });
            assertThat(sink.offer(first)).isTrue();
            requestStages(source);

            var result = firstOutcome.get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(status);
            assertThat(result.failure()).isSameAs(status == PipelineFrame.Status.FAILED ? failure : null);
            assertThatThrownBy(() -> dependent.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(callbackFailure);
            assertThat(callbacks.get()).isOne();
            assertThat(notifications.get()).isOne();
            assertThat(terminalCalls.get()).isEqualTo(status == PipelineFrame.Status.SUCCESS ? 1 : 0);
            assertThat(sink.size()).isZero();
            assertThat(manager.get(PASSWORD)).isNull();

            var owned = checkedOut.get();
            assertThat(owned).isNotNull();
            assertThat(stages(owned)).containsExactlyElementsOf(originalStages);
            var nextInput = new Input(PipelineFrame.Status.SUCCESS, new Object());
            manager.getFactory().replace(nextInput, owned);
            var secondOutcome = new CompletableFuture<PipelineFrame.Outcome>();
            owned.observe(secondOutcome);
            owned.onCompletion(notifications::incrementAndGet);
            // Late observation belongs to the detached future, never observe/onCompletion on a published frame.
            var late = firstOutcome.thenApply(value -> {
                assertThat(secondOutcome.isDone()).isFalse();
                return value;
            });
            assertThat(late.get(5, TimeUnit.SECONDS)).isSameAs(result);
            assertThat(sink.offer(owned)).isTrue();
            requestStages(source);
            assertThat(secondOutcome.get(5, TimeUnit.SECONDS).status()).isEqualTo(PipelineFrame.Status.SUCCESS);
            assertThat(secondOutcome.get(5, TimeUnit.SECONDS).failure()).isNull();
            assertThat(firstOutcome.get(5, TimeUnit.SECONDS)).isSameAs(result);
            assertThat(callbacks.get()).isOne();
            assertThat(notifications.get()).isEqualTo(2);
            assertThat(terminalCalls.get()).isEqualTo(status == PipelineFrame.Status.SUCCESS ? 2 : 1);
            var recycled = manager.get(PASSWORD);
            assertThat(recycled).isSameAs(owned);
            assertCleared(recycled);
            assertThat(manager.get(PASSWORD)).isNull();
            assertThat(sink.size()).isZero();
        } finally {
            sink.complete();
            manager.close();
        }
    }

    @Test
    void throwingDependentAndLateRegistrationDoNotChangeRunnerOutcomeOrGracefulCompletion() throws Exception {
        var runner = new PipelineRunner<>(PipelineFrame.<Object>builder(), value -> {}, true);
        var source = runner.getDelegate();
        new DefaultExecutor().input(source);
        try {
            var outcome = runner.submit(new Object());
            var failure = new IllegalStateException("dependent only");
            var earlyCalls = new AtomicInteger();
            var early = outcome.thenRun(() -> {
                earlyCalls.incrementAndGet();
                throw failure;
            });
            runner.completeGracefully();
            assertThat(runner.isComplete()).isFalse();
            source.request(1);
            assertThatThrownBy(() -> early.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(failure);
            var result = outcome.get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(PipelineFrame.Status.SUCCESS);
            assertThat(result.failure()).isNull();
            assertThat(runner.isComplete()).isTrue();
            var lateCalls = new AtomicInteger();
            var late = outcome.thenApply(value -> {
                lateCalls.incrementAndGet();
                return value;
            });
            assertThat(late.get(5, TimeUnit.SECONDS)).isSameAs(result);
            source.request(1);
            runner.completeGracefully();
            runner.complete();
            assertThat(outcome.get(5, TimeUnit.SECONDS)).isSameAs(result);
            assertThat(earlyCalls.get()).isOne();
            assertThat(lateCalls.get()).isOne();
            assertThat(runner.size()).isZero();
        } finally {
            runner.complete();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void throwingManagedFinalizerDoesNotFinalizeTwiceOrStopTheNextFrame(boolean failBody) {
        var sink = new QueueIngestSink();
        var source = sink.getDelegate();
        new DefaultExecutor().input(source);
        var manager = new FrameManager<Object, FinalizerFrame>(PASSWORD);
        var frame = new FinalizerFrame(manager, failBody);
        var nextCalls = new AtomicInteger();
        try {
            assertThat(sink.offer(frame)).isTrue();
            assertThat(sink.offer(new AbstractFrame(2L) {
                        @Override
                        public void execute() {
                            nextCalls.incrementAndGet();
                        }
                    }))
                    .isTrue();
            source.request(2);
            assertThat(manager.get(PASSWORD)).isSameAs(frame);
            assertThat(frame.normalFinalizations).isEqualTo(failBody ? 0 : 1);
            assertThat(frame.errorFinalizations).isEqualTo(failBody ? 1 : 0);
            assertThat(frame.observedFailure).isSameAs(failBody ? frame.bodyFailure : null);
            assertThat(nextCalls.get()).isOne();
            assertThat(manager.get(PASSWORD)).isNull();
            assertThat(sink.size()).isZero();
        } finally {
            sink.complete();
            manager.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void managedExecutorDoesNotPassRecycledFrameToFailureLogger(boolean failBody) throws Exception {
        var sink = new QueueIngestSink();
        var source = sink.getDelegate();
        var manager = new FrameManager<Object, FinalizerFrame>(PASSWORD);
        var frame = new FinalizerFrame(manager, failBody);
        var postRecycleArguments = new ArrayList<AbstractFrame>();
        var loggedFailures = new ArrayList<Throwable>();
        var logger = mock(Logger.class, invocation -> {
            // Capture the logger boundary itself, even when the test runtime has no SLF4J provider.
            // A real formatter/appender can call mutable subclass toString or retain these arguments.
            if (frame.recycled) {
                for (var argument : invocation.getArguments()) {
                    if (argument instanceof AbstractFrame recycled) postRecycleArguments.add(recycled);
                    if (argument instanceof Throwable failure) loggedFailures.add(failure);
                }
            }
            return null;
        });
        var executor = new DefaultExecutor();
        var loggerField = AbstractExecutor.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(executor, logger);
        executor.input(source);
        try {
            assertThat(sink.offer(frame)).isTrue();
            source.request(1);
            assertThat(manager.get(PASSWORD)).isSameAs(frame);
            assertThat(manager.get(PASSWORD)).isNull();
            assertThat(loggedFailures).containsExactly(frame.finalizationFailure);
            assertThat(postRecycleArguments)
                    .as("executor must detach diagnostics before finalization transfers ownership")
                    .isEmpty();
        } finally {
            sink.complete();
            manager.close();
        }
    }

    private static void requestStages(io.euhedral_execution.core.generics.LatticeSource source) {
        for (int stage = 0; stage < 3; stage++) source.request(1);
    }

    private static List<PipelineFrame<?>> stages(PipelineFrame<?> root) throws Exception {
        var next = PipelineFrame.class.getDeclaredField("nextFrame");
        next.setAccessible(true);
        var stages = new ArrayList<PipelineFrame<?>>();
        for (PipelineFrame<?> stage = root; stage != null; stage = (PipelineFrame<?>) next.get(stage)) {
            stages.add(stage);
        }
        return stages;
    }

    private static void assertCleared(PipelineFrame<?> root) {
        try {
            for (var stage : stages(root)) {
                for (var name : List.of("data", "outcome", "completion")) {
                    var field = PipelineFrame.class.getDeclaredField(name);
                    field.setAccessible(true);
                    assertThat(field.get(stage)).as("cleared %s", name).isNull();
                }
                for (var name : List.of("filtered", "cancelled")) {
                    var field = PipelineFrame.class.getDeclaredField(name);
                    field.setAccessible(true);
                    assertThat(field.getBoolean(stage)).as("reset %s", name).isFalse();
                }
            }
        } catch (Exception failure) {
            throw new AssertionError("could not inspect exclusively checked-out chain", failure);
        }
    }

    private record Input(PipelineFrame.Status status, Object payload) {}

    private static final class FinalizerFrame extends AbstractFrame {
        private final boolean failBody;
        private final RuntimeException bodyFailure = new IllegalArgumentException("body");
        private final RuntimeException finalizationFailure =
                new IllegalStateException("finalizer after ownership transfer");
        private int normalFinalizations;
        private int errorFinalizations;
        private Throwable observedFailure;
        private boolean recycled;

        private FinalizerFrame(FrameManager<Object, FinalizerFrame> manager, boolean failBody) {
            super(1L, manager, null);
            this.failBody = failBody;
        }

        @Override
        public void execute() {
            if (this.failBody) throw this.bodyFailure;
        }

        @Override
        public void doFinally() {
            this.normalFinalizations++;
            finish();
        }

        @Override
        public void doFinallyWithError(Throwable failure) {
            this.errorFinalizations++;
            this.observedFailure = failure;
            finish();
        }

        private void finish() {
            var failure = this.finalizationFailure;
            this.recycled = true;
            recycle();
            throw failure;
        }
    }
}
