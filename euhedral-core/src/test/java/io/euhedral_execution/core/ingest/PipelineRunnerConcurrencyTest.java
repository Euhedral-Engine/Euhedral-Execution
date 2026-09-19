package io.euhedral_execution.core.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.PipelineFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class PipelineRunnerConcurrencyTest {
    private static final long TIMEOUT_SECONDS = 5;

    @RepeatedTest(10)
    void gracefulCloseWhileAcceptedTransformIsPausedStillPublishesSuccessor() throws Exception {
        Object input = new Object();
        Object transformed = new Object();
        var gate = new Gate("accepted transformation before successor publication");
        try (var run = new RaceRun(
                PipelineFrame.<Object>builder().fanOut(value -> {
                    assertThat(value).isSameAs(input);
                    gate.pause();
                    return transformed;
                }),
                gate)) {
            var outcome = run.submit(input);
            var firstRequest = run.requestOne();
            gate.awaitEntered();
            run.checkFailures();
            assertThat(run.frames).hasSize(1);
            AbstractFrame root = run.frames.getFirst();

            run.runner.completeGracefully();
            assertThat(run.runner.isComplete()).isFalse();
            assertThat(run.completionSnapshots).isEmpty();
            assertThat(outcome.isDone()).isFalse();
            assertThat(run.values).isEmpty();
            assertThat(run.frames).containsExactly(root);
            assertThatThrownBy(() -> run.runner.submit(new Object())).isInstanceOf(IllegalStateException.class);
            // The active bulk drain has not advanced head yet: size is not a pause-state oracle.
            gate.release();
            run.join(firstRequest);
            assertThat(run.frames).containsExactly(root);
            assertThat(run.runner.size()).isOne();
            assertThat(outcome.isDone()).isFalse();
            assertThat(run.runner.isComplete()).isFalse();

            run.join(run.requestOne());
            assertThat(run.frames).hasSize(2);
            assertThat(run.frames.get(1)).isNotSameAs(root);
            assertThat(run.values).hasSize(1);
            assertThat(run.values.getFirst()).isSameAs(transformed);
            assertSuccess(outcome);
            run.assertCompleted(1);
        }
    }

    @RepeatedTest(10)
    void originalCheckoutOwnerReusesRootWhilePriorOutcomeCallbackIsPaused() throws Exception {
        Object firstPayload = new Object();
        Object secondPayload = new Object();
        var gate = new Gate("old outcome callback after recycle before accepted-chain decrement");
        try (var run = new RaceRun(PipelineFrame.<Object>builder(), gate)) {
            var firstOutcome = run.submit(firstPayload);
            var firstCallbacks = new AtomicInteger();
            var secondCallbacks = new AtomicInteger();
            var callbackOutcome = new AtomicReference<PipelineFrame.Outcome>();
            var firstCallback = firstOutcome.thenAccept(outcome -> run.capture(() -> {
                firstCallbacks.incrementAndGet();
                callbackOutcome.set(outcome);
                gate.pause();
            }));
            var firstRequest = run.requestOne();
            gate.awaitEntered();
            run.checkFailures();
            assertSuccess(firstOutcome);
            assertThat(firstCallback.isDone()).isFalse();
            assertThat(firstRequest.isDone()).isFalse();
            assertThat(run.frames).hasSize(1);
            AbstractFrame firstRoot = run.frames.getFirst();
            assertThat(run.values.getFirst()).isSameAs(firstPayload);

            // Both checkouts belong to this test thread, never the finalizer/callback thread.
            var secondOutcome = run.submit(secondPayload);
            var secondCallback = secondOutcome.thenRun(() -> run.capture(secondCallbacks::incrementAndGet));
            assertThat(secondOutcome).isNotSameAs(firstOutcome);
            assertThat(secondOutcome.isDone()).isFalse();
            run.runner.completeGracefully();
            assertThat(run.runner.isComplete()).isFalse();
            assertThat(run.completionSnapshots).isEmpty();
            assertThatThrownBy(() -> run.runner.submit(new Object())).isInstanceOf(IllegalStateException.class);

            gate.release();
            run.join(firstCallback);
            run.join(firstRequest);
            assertThat(firstCallbacks.get()).isOne();
            assertThat(callbackOutcome.get()).isSameAs(firstOutcome.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertThat(secondOutcome.isDone()).isFalse();
            assertThat(secondCallbacks.get()).isZero();
            assertThat(run.runner.isComplete()).isFalse();
            assertThat(run.completionSnapshots).isEmpty();

            run.join(run.requestOne());
            run.join(secondCallback);
            assertThat(run.frames).hasSize(2);
            // Compare reference identity only; no old-generation mutable state is inspected.
            assertThat(run.frames.get(1)).isSameAs(firstRoot);
            assertThat(run.values).hasSize(2);
            assertThat(run.values.get(0)).isSameAs(firstPayload);
            assertThat(run.values.get(1)).isSameAs(secondPayload);
            assertSuccess(firstOutcome);
            assertSuccess(secondOutcome);
            assertThat(firstCallbacks.get()).isOne();
            assertThat(secondCallbacks.get()).isOne();
            run.assertCompleted(2);
        }
    }

    private static void assertSuccess(CompletableFuture<PipelineFrame.Outcome> future) throws Exception {
        var outcome = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(outcome.status()).isEqualTo(PipelineFrame.Status.SUCCESS);
        assertThat(outcome.failure()).isNull();
    }

    /** Two acknowledged states, not a timing delay: entered by worker, released by owner/finally. */
    private static final class Gate {
        private final String name;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private Gate(String name) {
            this.name = name;
        }

        private void pause() {
            this.entered.countDown();
            await(this.released, "release");
        }

        private void awaitEntered() {
            await(this.entered, "entry");
        }

        private void await(CountDownLatch latch, String phase) {
            try {
                if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError(this.name + ": timed out waiting for " + phase);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(this.name + ": interrupted waiting for " + phase, e);
            }
        }

        private void release() {
            this.released.countDown();
        }
    }

    /** Local support shared only by the two concrete lifecycle races above. */
    private static final class RaceRun implements AutoCloseable {
        private final Gate gate;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final List<Future<?>> requests = new CopyOnWriteArrayList<>();
        private final List<AbstractFrame> frames = new CopyOnWriteArrayList<>();
        private final List<Object> values = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<PipelineFrame.Outcome>> outcomes = new CopyOnWriteArrayList<>();
        private final List<List<PipelineFrame.Outcome>> completionSnapshots = new CopyOnWriteArrayList<>();
        private final PipelineRunner<Object> runner;
        private final LatticeSource source;

        private RaceRun(PipelineFrame.Builder<Object, Object> builder, Gate gate) {
            this.gate = gate;
            this.runner = new PipelineRunner<>(builder, this.values::add, true);
            this.source = this.runner.getDelegate();
            new AbstractExecutor(-1) {
                @Override
                public void execute(AbstractFrame frame) {
                    capture(() -> {
                        frames.add(frame);
                        frame.execute();
                    });
                }

                @Override
                public AbstractExecutor hookOnClone(int cpu) {
                    throw new UnsupportedOperationException("test executor is not cloned");
                }
            }.input(new CompletionSource(
                    this.source,
                    () -> capture(() -> {
                        // A missing outcome fails here even if isComplete was already published.
                        completionSnapshots.add(outcomes.stream()
                                .map(future -> {
                                    assertThat(future.isDone()).isTrue();
                                    return future.getNow(null);
                                })
                                .toList());
                    })));
        }

        private CompletableFuture<PipelineFrame.Outcome> submit(Object value) {
            var outcome = this.runner.submit(value);
            this.outcomes.add(outcome);
            return outcome;
        }

        private Future<?> requestOne() {
            var request = this.worker.submit(() -> capture(() -> this.source.request(1)));
            this.requests.add(request);
            return request;
        }

        private void capture(Runnable action) {
            try {
                action.run();
            } catch (Throwable thrown) {
                this.failure.compareAndSet(null, thrown);
                throw new AssertionError("worker or callback failed at " + this.gate.name, thrown);
            }
        }

        private void checkFailures() {
            var thrown = this.failure.get();
            if (thrown != null) throw new AssertionError("worker or callback failed at " + this.gate.name, thrown);
        }

        private void join(Future<?> future) throws Exception {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            checkFailures();
        }

        private void assertCompleted(int accepted) {
            checkFailures();
            assertThat(this.runner.isComplete()).isTrue();
            assertThat(this.runner.size()).isZero();
            assertThat(this.completionSnapshots).hasSize(1);
            assertThat(this.completionSnapshots.getFirst()).hasSize(accepted).allSatisfy(outcome -> {
                assertThat(outcome.status()).isEqualTo(PipelineFrame.Status.SUCCESS);
                assertThat(outcome.failure()).isNull();
            });
        }

        @Override
        public void close() throws Exception {
            // try-with-resources invokes this in finally and suppresses cleanup errors onto a primary failure.
            this.gate.release();
            this.worker.shutdown();
            try {
                if (!this.worker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    this.worker.shutdownNow();
                    assertThat(this.worker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            .as("worker terminated at %s", this.gate.name)
                            .isTrue();
                }
                for (var request : this.requests) join(request);
            } finally {
                this.worker.shutdownNow();
                this.runner.complete();
            }
            checkFailures();
        }
    }

    /** Records real completion; does not synthesize addUpstream or unlimited demand on registration. */
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
