package euhedral.io.flow_control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeSource;
import euhedral.io.utils.QueueConsumer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jctools.maps.NonBlockingHashMapLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestReceiver;

@SuppressWarnings("unchecked")
class UpstreamQueueTest {

    private UpstreamQueue queue;

    @BeforeEach
    void setup() {
        UpstreamQueue.UP_QUEUE.remove();

        queue = new UpstreamQueue();
    }

    @AfterEach
    void cleanup() {
        UpstreamQueue.UP_QUEUE.remove();
    }

    @Test
    void shouldCreateThreadLocalQueue() {
        NonBlockingHashMapLong<UpstreamQueue> map =
                new NonBlockingHashMapLong<>();

        AtomicLong counter = new AtomicLong();

        UpstreamQueue created = UpstreamQueue.get(map, counter);

        assertNotNull(created);
        assertEquals(1, counter.get());
        assertSame(created, UpstreamQueue.UP_QUEUE.get());
    }

    @Test
    void shouldReuseThreadLocalQueue() {
        NonBlockingHashMapLong<UpstreamQueue> map =
                new NonBlockingHashMapLong<>();

        AtomicLong counter = new AtomicLong();

        UpstreamQueue first = UpstreamQueue.get(map, counter);
        UpstreamQueue second = UpstreamQueue.get(map, counter);

        assertSame(first, second);
        assertEquals(1, counter.get());
    }

    @Test
    void shouldAddUpstream() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        queue.addUpstream(upstream);

        assertEquals(1, queue.getTrueUpstreamCount());
    }

    @Test
    void shouldRequestFromOneUpstreamsWhenAtOrBelow32() {
        TestUpstreamHandle first = new TestUpstreamHandle();
        TestUpstreamHandle second = new TestUpstreamHandle();

        queue.addUpstream(first);
        queue.addUpstream(second);

        queue.request(32);

        assertEquals(32, first.requested);
        assertEquals(0, second.requested);
    }

    @Test
    void shouldRequestEvenlyAcrossUpstreamsWhenAbove1024() {
        TestUpstreamHandle first = new TestUpstreamHandle();
        TestUpstreamHandle second = new TestUpstreamHandle();

        queue.addUpstream(first);
        queue.addUpstream(second);

        queue.request(2048);

        assertEquals(1024, first.requested);
        assertEquals(1024, second.requested);
    }

    @Test
    void shouldRequestWithoutBuffer() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        queue.addUpstream(upstream);

        queue.pull(null, 64);

        assertEquals(64, upstream.requested);
        assertEquals(0, upstream.pulled);
    }

    @Test
    void shouldPullWithConsumer() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        queue.addUpstream(upstream);

        QueueConsumer buffer = new QueueConsumer(
                frame -> {}
        );

        queue.pull(buffer, 64);

        assertEquals(0, upstream.requested);
        assertEquals(64, upstream.pulled);
    }

    @Test
    void shouldIgnoreZeroDemand() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        queue.addUpstream(upstream);

        queue.request(0);

        assertEquals(0, upstream.requested);
        assertEquals(0, upstream.pulled);
    }

    @Test
    void shouldRemoveCompletedUpstreams() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        upstream.complete = true;

        queue.addUpstream(upstream);

        queue.request(10);

        assertEquals(0, queue.getTrueUpstreamCount());
    }

    @Test
    void shouldCalculateSingleBucketForSmallDemand() {
        queue.cachedUpCount = 64;

        queue.calculatePullBuckets(32);

        assertEquals(1, queue.pullBucket[0]);
    }

    @Test
    void shouldCalculateDistributedBucketsForLargeDemand() {
        queue.cachedUpCount = 8;

        queue.calculatePullBuckets(8192);

        assertEquals(8, queue.pullBucket[0]);
        assertEquals(8192 >> 3, queue.pullBucket[1]);
    }

    @Test
    void shouldFillDrainBuffer() {
        TestUpstreamHandle upstream1 = new TestUpstreamHandle();
        TestUpstreamHandle upstream2 = new TestUpstreamHandle();

        queue.addUpstream(upstream1);
        queue.addUpstream(upstream2);

        queue.pullBucket[0] = 2;

        int count = queue.fillUpstreamBuffer();

        assertEquals(2, count);
    }

    @Test
    void shouldReturnZeroWhenNoBucketsAvailable() {
        queue.pullBucket[0] = 0;

        int count = queue.fillUpstreamBuffer();

        assertEquals(0, count);
    }

    @Test
    void shouldRequestFromHandleWithoutBuffer() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        UpstreamQueue.drain(upstream, null, 123);

        assertEquals(123, upstream.requested);
        assertEquals(0, upstream.pulled);
    }

    @Test
    void shouldDefaultUnsupportedOperations() {
        UpstreamQueue.UpstreamHandle handle =
                new TestUpstreamHandle();

        LatticeSource source = mock(LatticeSource.class);

        handle.addUpstream(source);

        verify(source).complete();
    }

    @Test
    void shouldRejectTerminalDownstreamByDefault() {
        UpstreamQueue.UpstreamHandle handle =
                new TestUpstreamHandle();

        TestReceiver terminal = new TestReceiver();

        handle.addDownstream(terminal);

        assertNotNull(terminal.error);
        assertInstanceOf(
                IllegalStateException.class,
                terminal.error
        );

        assertEquals(
                "Not supported",
                terminal.error.getMessage()
        );
    }

    static class TestUpstreamHandle extends UpstreamQueue.UpstreamHandle {

        long requested;
        long pulled;
        boolean complete;

        @Override
        public long pull(Consumer<AbstractFrame> consumer, long demand) {
            this.pulled += demand;
            return demand;
        }

        @Override
        public void request(long num) {
            this.requested += num;
        }

        @Override
        public boolean isComplete() {
            return complete;
        }

        @Override
        public void push(AbstractFrame frame) {
        }

        @Override
        public void onError(Throwable throwable) {
        }

    }
}