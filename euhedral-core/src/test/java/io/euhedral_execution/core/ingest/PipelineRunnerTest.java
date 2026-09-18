package io.euhedral_execution.core.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.PipelineFrame;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.impl.DefaultExecutor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import test_utils.TestReceiver;

class PipelineRunnerTest {
    @Test
    void gracefulCompletionCanObserveAndJoinAllConcurrentChainOutcomes() throws Exception {
        var runner = new PipelineRunner<>(PipelineFrame.<Integer>builder(), v -> {}, true);
        var source = runner.getDelegate();
        var outcomes = new ArrayList<java.util.concurrent.CompletableFuture<PipelineFrame.Outcome>>();
        var allDoneAtCompletion = new java.util.concurrent.atomic.AtomicBoolean();
        var completions = new java.util.concurrent.atomic.AtomicInteger();
        source.addDownstream(new TestReceiver() {
            @Override
            public void onComplete() {
                completions.incrementAndGet();
                boolean allDone = outcomes.stream().allMatch(java.util.concurrent.CompletableFuture::isDone);
                allDoneAtCompletion.set(allDone);
                if (allDone) {
                    for (var outcome : outcomes) {
                        assertThat(outcome.join().status()).isEqualTo(PipelineFrame.Status.SUCCESS);
                    }
                }
            }
        });
        outcomes.add(runner.submit(1));
        outcomes.add(runner.submit(2));
        var first = poll(source);
        var second = poll(source);
        runner.completeGracefully();
        var ready = new java.util.concurrent.CountDownLatch(2);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var firstFinished = workers.submit(() -> {
                first.execute();
                ready.countDown();
                await(ready);
                first.doFinally();
            });
            var secondFinished = workers.submit(() -> {
                second.execute();
                ready.countDown();
                await(ready);
                second.doFinally();
            });
            firstFinished.get(5, java.util.concurrent.TimeUnit.SECONDS);
            secondFinished.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(completions.get()).isOne();
        assertThat(allDoneAtCompletion.get()).isTrue();
    }

    @Test
    void gracefulCloseWaitsForCheckedOutStagesAndRejectsNewInputs() {
        List<Integer> results = new ArrayList<>();
        var runner = new PipelineRunner<>(PipelineFrame.<Integer>builder().fanOut(v -> v + 1), results::add, true);
        var source = runner.getDelegate();
        source.addDownstream(new TestReceiver());
        runner.run(1);
        var stage = poll(source);
        runner.completeGracefully();
        source.pull(f -> {}, f -> false, 1);
        assertThat(runner.isComplete()).isFalse();
        assertThatThrownBy(() -> runner.run(2)).isInstanceOf(IllegalStateException.class);
        stage.execute();
        stage.doFinally();
        var terminal = poll(source);
        assertThat(runner.isComplete()).isFalse();
        terminal.execute();
        terminal.doFinally();
        assertThat(results).containsExactly(2);
        assertThat(runner.isComplete()).isTrue();
    }

    @Test
    void submissionReportsSuccessAfterTerminalAndAllowsSafeReentrantReuse() {
        List<Integer> results = new ArrayList<>();
        var runner = new PipelineRunner<>(PipelineFrame.<Integer>builder(), results::add, true);
        var source = runner.getDelegate();
        new DefaultExecutor().input(source);
        var future = runner.submit(1);
        assertThat(future.isDone()).isFalse();
        future.thenRun(() -> runner.run(2));
        source.request(10);
        source.request(10);
        assertThat(future.join().status()).isEqualTo(PipelineFrame.Status.SUCCESS);
        assertThat(results).containsExactly(1, 2);
        runner.completeGracefully();
        assertThat(runner.isComplete()).isTrue();
    }

    @Test
    void submissionReportsFilteredWithoutCallingTerminal() {
        var runner = new PipelineRunner<>(
                PipelineFrame.<Integer>builder().filterOutput(v -> false),
                v -> {
                    throw new AssertionError("filtered terminal");
                },
                true);
        var source = runner.getDelegate();
        new DefaultExecutor().input(source);
        var future = runner.submit(1);
        source.request(10);
        assertThat(future.join().status().name()).isEqualTo("FILTERED");
        runner.completeGracefully();
        assertThat(runner.isComplete()).isTrue();
    }

    @Test
    void submissionReportsCancellationFromFunctionFilterAndConsumer() {
        for (int where = 0; where < 3; where++) {
            final int location = where;
            var runner = new PipelineRunner<>(
                    PipelineFrame.<Integer>builder()
                            .fanOut(v -> {
                                if (location == 0) throw AbstractFrame.CANCEL_SIGNAL;
                                return v;
                            })
                            .filterOutput(v -> {
                                if (location == 1) throw AbstractFrame.CANCEL_SIGNAL;
                                return true;
                            }),
                    v -> {
                        throw AbstractFrame.CANCEL_SIGNAL;
                    },
                    true);
            var source = runner.getDelegate();
            new DefaultExecutor().input(source);
            var future = runner.submit(1);
            source.request(10);
            source.request(10);
            assertThat(future.isDone()).isTrue();
            assertThat(future.join().status().name()).isEqualTo("CANCELLED");
            assertThat(future.join().failure()).isNull();
            runner.completeGracefully();
            assertThat(runner.isComplete()).isTrue();
        }
    }

    @Test
    void submissionReportsOriginalStageFailure() {
        var error = new IllegalArgumentException("stage failure");
        var runner = new PipelineRunner<>(
                PipelineFrame.<Integer>builder().fanOut(v -> {
                    throw error;
                }),
                v -> {
                    throw new AssertionError("failed terminal");
                },
                true);
        var source = runner.getDelegate();
        new DefaultExecutor().input(source);
        var future = runner.submit(1);
        source.request(10);
        assertThat(future.join().status().name()).isEqualTo("FAILED");
        assertThat(future.join().failure()).isSameAs(error);
        runner.completeGracefully();
        assertThat(runner.isComplete()).isTrue();
    }

    @Test
    void immediateDelegateCloseCancelsQueuedAndCheckedOutWork() {
        var runner = new PipelineRunner<>(PipelineFrame.<Integer>builder().fanOut(v -> v + 1), v -> {}, true);
        var source = runner.getDelegate();
        source.addDownstream(new TestReceiver());
        var active = runner.submit(1);
        var stage = poll(source);
        var queued = runner.submit(2);
        source.complete();
        assertThat(stage.isAlive()).isFalse();
        assertThat(queued.isDone()).isTrue();
        assertThat(queued.join().status()).isEqualTo(PipelineFrame.Status.CANCELLED);
        assertThat(active.isDone()).isFalse();
        stage.doFinally();
        assertThat(active.join().status()).isEqualTo(PipelineFrame.Status.CANCELLED);
        assertThat(runner.size()).isZero();
        assertThatThrownBy(() -> runner.submit(3)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closeDuringStoppedDrainDefersCancellationUntilConsumerOwnershipReleased() {
        var runner = new PipelineRunner<>(PipelineFrame.<Integer>builder(), v -> {}, true);
        var source = runner.getDelegate();
        source.addDownstream(new TestReceiver());
        var future = runner.submit(1);
        assertThat(source.pull(
                        f -> {
                            throw new AssertionError("stopped");
                        },
                        f -> {
                            source.complete();
                            return true;
                        },
                        1))
                .isZero();
        assertThat(future.isDone()).isTrue();
        assertThat(future.join().status()).isEqualTo(PipelineFrame.Status.CANCELLED);
        assertThat(runner.size()).isZero();
    }

    @Test
    void configurablePartitionsSupportEverySubmissionModeAndValidateBeforeCheckout() throws Exception {
        List<Integer> values = new ArrayList<>();
        var runner = new PipelineRunner<>(PipelineFrame.<Integer>builder(), values::add, true, 3);
        var source = runner.getDelegate();
        new DefaultExecutor().input(source);
        assertThatThrownBy(() -> runner.run(-1, 1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> runner.run(3, 1)).isInstanceOf(IndexOutOfBoundsException.class);
        runner.run(2, 2);
        runner.run(Long.MAX_VALUE, 3);
        var selected = runner.submit(1, 4);
        var seeded = runner.submit(17L, 5);
        source.request(10);
        assertThat(values).containsExactlyInAnyOrder(2, 3, 4, 5);
        assertThat(selected.join().status()).isEqualTo(PipelineFrame.Status.SUCCESS);
        assertThat(seeded.join().status()).isEqualTo(PipelineFrame.Status.SUCCESS);
        runner.completeGracefully();
        assertThat(runner.isComplete()).isTrue();
        assertThatThrownBy(() -> new PipelineRunner<>(PipelineFrame.<Integer>builder(), v -> {}, true, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completionDuringRequestKeepsDemandAtZero() {
        var runner = new PipelineRunner<>(PipelineFrame.<Integer>builder(), v -> {}, true);
        var source = runner.getDelegate();
        new DefaultExecutor().input(source);
        var future = runner.submit(1);
        runner.completeGracefully();
        source.request(1);
        assertThat(future.isDone()).isTrue();
        assertThat(runner.isComplete()).isTrue();
        assertThat(runner.getDemand()).isZero();
    }

    @Test
    void throwingDownstreamCompletionCannotStrandQueuedSubmissions() {
        var runner = new PipelineRunner<>(PipelineFrame.<Integer>builder(), v -> {}, true);
        runner.getDelegate().addDownstream(new TestReceiver() {
            @Override
            public void onComplete() {
                throw new IllegalStateException("observer");
            }
        });
        var future = runner.submit(1);
        assertThatThrownBy(runner::complete).isInstanceOf(IllegalStateException.class);
        assertThat(future.isDone()).isTrue();
        assertThat(future.join().status()).isEqualTo(PipelineFrame.Status.CANCELLED);
    }

    @Test
    void immediateCloseDoesNotRecycleAnExecutingStageUntilItReturns() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var runner = new PipelineRunner<>(
                PipelineFrame.<Integer>builder().fanOut(v -> {
                    entered.countDown();
                    await(release);
                    return v;
                }),
                v -> {
                    throw new AssertionError("cancelled terminal");
                },
                true);
        var source = runner.getDelegate();
        new DefaultExecutor().input(source);
        var active = runner.submit(1);
        var queued = runner.submit(2);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var request = worker.submit(() -> source.request(1));
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                runner.complete();
                assertThat(active.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            request.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(active.join().status()).isEqualTo(PipelineFrame.Status.CANCELLED);
        assertThat(queued.join().status()).isEqualTo(PipelineFrame.Status.CANCELLED);
        assertThat(runner.size()).isZero();
    }

    @Test
    void admissionRacingImmediateCloseNeverStrandsAnAcceptedSubmission() throws Exception {
        try (var closer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            for (int iteration = 0; iteration < 100; iteration++) {
                var runner = new PipelineRunner<>(PipelineFrame.<Integer>builder(), v -> {}, true);
                var start = new java.util.concurrent.CountDownLatch(1);
                var closed = closer.submit(() -> {
                    await(start);
                    runner.complete();
                });
                java.util.concurrent.CompletableFuture<PipelineFrame.Outcome> future = null;
                start.countDown();
                try {
                    future = runner.submit(iteration);
                } catch (IllegalStateException expected) {
                    /* close won admission */
                }
                closed.get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (future != null) {
                    assertThat(future.get(5, java.util.concurrent.TimeUnit.SECONDS)
                                    .status())
                            .isEqualTo(PipelineFrame.Status.CANCELLED);
                }
                assertThat(runner.size()).isZero();
            }
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    static AbstractFrame poll(LatticeSource source) {
        var frames = new ArrayList<AbstractFrame>();
        assertThat(source.pull(frames::add, f -> false, 1)).isOne();
        return frames.getFirst();
    }
}
