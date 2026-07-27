package io.euhedral_execution.reactor.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;

class EuhedralSubscriberTest {

    @Test
    void forwardsDemandFramesAndCompletionOnce() {
        EuhedralSubscriber subscriber = new EuhedralSubscriber();
        RecordingSubscription subscription = new RecordingSubscription();
        RecordingReceiver receiver = new RecordingReceiver();
        TestFrame frame = new TestFrame();

        subscriber.onSubscribe(subscription);
        subscriber.addDownstream(receiver);
        subscriber.request(0);
        subscriber.request(-1);
        subscriber.request(3);
        subscriber.onNext(frame);
        subscriber.onComplete();
        subscriber.onComplete();
        subscriber.request(4);
        subscriber.onNext(new TestFrame());

        assertTrue(subscriber.isComplete());
        assertFalse(subscriber.hasSubscription());
        assertEquals(3, subscription.requested);
        assertEquals(List.of(frame), receiver.frames);
        assertEquals(1, receiver.completions);
        assertSame(subscriber, receiver.upstream);
    }

    @Test
    void errorsTerminateAndLaterSubscriptionsAreCancelled() {
        EuhedralSubscriber subscriber = new EuhedralSubscriber();
        RecordingSubscription first = new RecordingSubscription();
        RecordingSubscription second = new RecordingSubscription();
        RecordingReceiver receiver = new RecordingReceiver();
        IllegalStateException failure = new IllegalStateException("boom");

        subscriber.onSubscribe(first);
        subscriber.addDownstream(receiver);
        subscriber.onError(failure);
        subscriber.onError(new IllegalArgumentException("ignored"));
        subscriber.onSubscribe(second);

        assertTrue(subscriber.isComplete());
        assertSame(failure, receiver.failure);
        assertEquals(0, receiver.completions);
        assertTrue(second.cancelled);
        assertFalse(first.cancelled);
    }

    @Test
    void rejectsASecondSubscriptionAndDownstream() {
        EuhedralSubscriber subscriber = new EuhedralSubscriber();
        RecordingSubscription firstSubscription = new RecordingSubscription();
        RecordingSubscription secondSubscription = new RecordingSubscription();
        RecordingReceiver firstReceiver = new RecordingReceiver();
        RecordingReceiver secondReceiver = new RecordingReceiver();

        subscriber.onSubscribe(firstSubscription);
        subscriber.onSubscribe(secondSubscription);
        subscriber.addDownstream(firstReceiver);
        subscriber.addDownstream(secondReceiver);

        assertTrue(secondSubscription.cancelled);
        assertSame(subscriber, firstReceiver.upstream);
        assertNull(secondReceiver.upstream);
        assertTrue(secondReceiver.failure instanceof IllegalStateException);
    }

    private static final class RecordingSubscription implements Subscription {

        private long requested;
        private boolean cancelled;

        @Override
        public void request(long demand) {
            this.requested += demand;
        }

        @Override
        public void cancel() {
            this.cancelled = true;
        }
    }

    private static final class RecordingReceiver implements LatticeReceiver {

        private final List<AbstractFrame> frames = new ArrayList<>();
        private LatticeSource upstream;
        private Throwable failure;
        private int completions;

        @Override
        public void push(AbstractFrame frame) {
            this.frames.add(frame);
        }

        @Override
        public void onComplete() {
            this.completions++;
        }

        @Override
        public void onError(Throwable error) {
            this.failure = error;
        }

        @Override
        public void addUpstream(LatticeSource upstream) {
            this.upstream = upstream;
        }
    }

    private static final class TestFrame extends AbstractFrame {

        private TestFrame() {
            super(1);
        }
    }
}
