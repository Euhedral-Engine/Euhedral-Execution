package io.euhedral_execution.core.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import test_utils.TestFrame;
import test_utils.TestReceiver;

class QueueIngestSinkTest {
    @Test
    void requestInsidePullConsumerRoutesAfterPullReleasesOwnership() {
        var sink = new QueueIngestSink();
        var source = sink.getDelegate();
        var receiver = new TestReceiver();
        source.addDownstream(receiver);
        var first = new TestFrame("first");
        var second = new TestFrame("second");
        var third = new TestFrame("third");
        sink.offer(first);
        sink.offer(second);
        sink.offer(third);

        assertThat(source.pull(
                        frame -> {
                            assertThat(frame).isSameAs(first);
                            source.request(1);
                            assertThat(receiver.received).isEmpty();
                        },
                        frame -> frame == second,
                        3))
                .isOne();

        assertThat(receiver.received).containsExactly(second);
        assertThat(sink.size()).isOne();
        assertThat(sink.getDemand()).isZero();
    }

    @Test
    void reentrantRequestDrainsNewDemandWithoutAnotherExternalRequest() {
        var sink = new QueueIngestSink();
        var source = sink.getDelegate();
        var receiver = new TestReceiver() {
            @Override
            public void push(io.euhedral_execution.core.frames.AbstractFrame frame) {
                super.push(frame);
                source.request(1);
            }
        };
        source.addDownstream(receiver);
        sink.offer(new TestFrame("first"));
        sink.offer(new TestFrame("second"));

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5), () -> source.request(1));

        assertThat(receiver.received).hasSize(2);
        assertThat(sink.size()).isZero();
        assertThat(sink.getDemand()).isOne();
    }

    @Test
    void lateAttachmentReceivesCompletionExactlyOnce() {
        var sink = new QueueIngestSink();
        var source = sink.getDelegate();
        source.complete();
        var receiver = new TestReceiver() {
            int completions;

            @Override
            public void onComplete() {
                super.onComplete();
                completions++;
            }
        };
        source.addDownstream(receiver);
        source.complete();
        assertThat(receiver.completions).isOne();
        var second = new TestReceiver();
        source.addDownstream(second);
        assertThat(second.error).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completionNotificationDoesNotHoldPublicationLock() throws Exception {
        var sink = new QueueIngestSink();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            sink.getDelegate().addDownstream(new TestReceiver() {
                @Override
                public void onComplete() {
                    try {
                        assertThat(executor.submit(() -> sink.offer(new TestFrame("late")))
                                        .get(2, java.util.concurrent.TimeUnit.SECONDS))
                                .isFalse();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }
            });
            sink.complete();
        }
    }

    @Test
    void attachmentRacingCompletionNotifiesExactlyOnce() throws Exception {
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            for (int iteration = 0; iteration < 100; iteration++) {
                var sink = new QueueIngestSink();
                var source = sink.getDelegate();
                var notifications = new java.util.concurrent.atomic.AtomicInteger();
                var receiver = new TestReceiver() {
                    @Override
                    public void onComplete() {
                        notifications.incrementAndGet();
                    }
                };
                var start = new java.util.concurrent.CountDownLatch(1);
                var closed = worker.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    source.complete();
                });
                start.countDown();
                source.addDownstream(receiver);
                closed.get(5, java.util.concurrent.TimeUnit.SECONDS);
                source.complete();
                assertThat(notifications.get()).isOne();
            }
        }
    }

    @Test
    void gracefulCompletionPreservesStoppedOrderedWork() {
        var sink = new QueueIngestSink();
        var source = sink.getDelegate();
        var receiver = new TestReceiver();
        source.addDownstream(receiver);
        sink.offer(new TestFrame("ordered"));
        sink.completeGracefully();
        assertThat(source.pull(f -> {}, f -> true, 1)).isZero();
        assertThat(sink.isComplete()).isFalse();
        source.request(1);
        source.request(1);
        assertThat(receiver.received).hasSize(1);
        assertThat(sink.isComplete()).isTrue();
    }
}
