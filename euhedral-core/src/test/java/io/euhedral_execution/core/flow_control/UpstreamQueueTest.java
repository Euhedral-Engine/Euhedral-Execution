package io.euhedral_execution.core.flow_control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.euhedral_execution.core.flow_control.UpstreamQueue.UpstreamHandle;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Getter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestReceiver;

@SuppressWarnings("unchecked")
class UpstreamQueueTest {

    private UpstreamQueue queue;
    private PaddedAtomicLong count = new PaddedAtomicLong();
    private MpscQueue<UpstreamHandle> handles = new MpscQueue<>(64);

    @BeforeEach
    void setup() {
        UpstreamQueue.UP_QUEUE.remove();

        queue = new UpstreamQueue(0, handles, count);
    }

    @AfterEach
    void cleanup() {
        UpstreamQueue.UP_QUEUE.remove();
    }

    @Test
    void shouldCreateThreadLocalQueue() {
        MpscQueue<UpstreamHandle>[] arr = new MpscQueue[SystemInfo.getMaxCoreId() + 1];
        Arrays.fill(arr, this.handles);

        UpstreamQueue created = UpstreamQueue.get(arr, count);

        assertNotNull(created);
        assertSame(created, UpstreamQueue.UP_QUEUE.get());
    }

    @Test
    void shouldReuseThreadLocalQueue() {
        MpscQueue<UpstreamHandle>[] arr = new MpscQueue[SystemInfo.getMaxCoreId() + 1];
        Arrays.fill(arr, this.handles);

        UpstreamQueue first = UpstreamQueue.get(arr, count);
        UpstreamQueue second = UpstreamQueue.get(arr, count);

        assertSame(first, second);
    }

    @Test
    void shouldRequestFromOneUpstreamsWhenAtOrBelow32() {
        TestUpstreamHandle first = new TestUpstreamHandle();
        TestUpstreamHandle second = new TestUpstreamHandle();

        handles.offer(first);
        handles.offer(second);
        count.getAndAdd(2);

        queue.request(32);

        assertEquals(32, first.requested);
        assertEquals(0, second.requested);
    }

    @Test
    void shouldRequestWithoutBuffer() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        handles.offer(upstream);
        count.incrementAndGet();

        queue.pull(null, frame -> false, 64);

        assertEquals(64, upstream.requested);
        assertEquals(0, upstream.pulled);
    }

    @Test
    void shouldPullWithConsumer() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        handles.offer(upstream);
        count.incrementAndGet();

        queue.pull(frame -> {}, frame -> false, 64);

        assertEquals(0, upstream.requested);
        assertEquals(64, upstream.pulled);
    }

    /// Verifies a transient acquisition failure retains the live handle for a later pull.
    @Test
    void shouldRetainLiveHandleAfterFailedAcquisition() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();
        upstream.available = false;
        handles.offer(upstream);
        count.incrementAndGet();

        assertEquals(0L, queue.pull(frame -> {}, frame -> false, 64));
        assertEquals(1L, handles.sizeLong());
        assertEquals(1L, upstream.acquisitionAttempts);

        upstream.available = true;

        assertEquals(64L, queue.pull(frame -> {}, frame -> false, 64));
        assertEquals(1L, handles.sizeLong());
        assertEquals(2L, upstream.acquisitionAttempts);
    }

    /// Verifies unavailable live handles remain bounded to one attempt per pull cycle.
    @Test
    void shouldBoundRetriesWhileRetainingUnavailableHandles() {
        TestUpstreamHandle first = new TestUpstreamHandle();
        TestUpstreamHandle second = new TestUpstreamHandle();
        first.available = false;
        second.available = false;
        handles.offer(first);
        handles.offer(second);
        count.getAndAdd(2L);

        assertEquals(0L, queue.pull(frame -> {}, frame -> false, 64));

        assertEquals(2L, handles.sizeLong());
        assertEquals(1L, first.acquisitionAttempts);
        assertEquals(1L, second.acquisitionAttempts);
    }

    @Test
    void shouldIgnoreZeroDemand() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        handles.offer(upstream);

        queue.request(0);

        assertEquals(0, upstream.requested);
        assertEquals(0, upstream.pulled);
    }

    @Test
    void shouldRemoveCompletedUpstreams() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        upstream.complete = true;

        handles.offer(upstream);

        queue.request(10);

        assertEquals(0, queue.getTrueUpstreamCount());
    }

    @Test
    void shouldRequestFromHandleWithoutBuffer() {
        TestUpstreamHandle upstream = new TestUpstreamHandle();

        UpstreamQueue.drain(upstream, null, null, 123);

        assertEquals(123, upstream.requested);
        assertEquals(0, upstream.pulled);
    }

    @Test
    void shouldDefaultUnsupportedOperations() {
        UpstreamQueue.UpstreamHandle handle = new TestUpstreamHandle();

        LatticeSource source = mock(LatticeSource.class);

        handle.addUpstream(source);

        verify(source).complete();
    }

    @Test
    void shouldRejectTerminalDownstreamByDefault() {
        UpstreamQueue.UpstreamHandle handle = new TestUpstreamHandle();

        TestReceiver terminal = new TestReceiver();

        handle.addDownstream(terminal);

        assertNotNull(terminal.error);
        assertInstanceOf(IllegalStateException.class, terminal.error);

        assertEquals("Not supported", terminal.error.getMessage());
    }

    static class TestUpstreamHandle extends UpstreamQueue.UpstreamHandle {

        @Getter
        long id = 0;

        long requested;
        long pulled;
        long acquisitionAttempts;
        boolean complete;
        boolean available = true;

        /// Returns the configured availability while retaining the number of bounded attempts.
        @Override
        public boolean acquireLock() {
            this.acquisitionAttempts++;
            return this.available;
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
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
            // Test
        }

        @Override
        public void onError(Throwable throwable) {
            // Test
        }
    }
}
