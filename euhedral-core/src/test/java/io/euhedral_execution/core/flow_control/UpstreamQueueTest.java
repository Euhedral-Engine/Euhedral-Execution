package io.euhedral_execution.core.flow_control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.euhedral_execution.core.flow_control.UpstreamQueue.UpstreamHandle;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Getter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestFrame;
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

    /// Verifies live-count access never changes meaning after local productivity observations.
    @Test
    void shouldKeepCachedAndTrueCountsAsLiveHandleCounts() {
        TestUpstreamHandle upstream = addHandle();

        assertEquals(1L, queue.getTrueUpstreamCount());
        upstream.pullResult = 0L;
        queue.pull(frame -> {}, frame -> false, 64L);

        assertEquals(1L, queue.getCachedUpCount());
        assertEquals(1L, queue.getTrueUpstreamCount());
        assertEquals(0L, queue.getProductiveHandleCount());
    }

    /// Verifies newly visible live handles are optimistic until this worker disproves them.
    @Test
    void shouldInitiallyClassifyLiveHandlesAsProductive() {
        addHandle();
        addHandle();

        queue.getTrueUpstreamCount();

        assertEquals(2L, queue.getCachedUpCount());
        assertEquals(2L, queue.getProductiveHandleCount());
    }

    @Test
    void shouldKeepProductiveHandleProductiveAfterUsefulPull() {
        addHandle();

        assertEquals(64L, queue.pull(frame -> {}, frame -> false, 64L));

        assertEquals(1L, queue.getProductiveHandleCount());
        assertEquals(0L, queue.nonproductiveCount);
    }

    @Test
    void shouldMarkProductiveHandleNonproductiveAfterEmptyPull() {
        TestUpstreamHandle upstream = addHandle();
        upstream.pullResult = 0L;

        assertEquals(0L, queue.pull(frame -> {}, frame -> false, 64L));

        assertEquals(0L, queue.getProductiveHandleCount());
        assertEquals(1L, queue.nonproductiveCount);
    }

    @Test
    void shouldMarkNonproductiveHandleProductiveAfterUsefulPull() {
        TestUpstreamHandle upstream = addHandle();
        upstream.pullResult = 0L;
        queue.pull(frame -> {}, frame -> false, 64L);

        upstream.pullResult = -1L;
        assertEquals(64L, queue.pull(frame -> {}, frame -> false, 64L));

        assertEquals(1L, queue.getProductiveHandleCount());
        assertEquals(0L, queue.nonproductiveCount);
    }

    @Test
    void shouldKeepNonproductiveHandleNonproductiveAfterAnotherEmptyPull() {
        TestUpstreamHandle upstream = addHandle();
        upstream.pullResult = 0L;

        queue.pull(frame -> {}, frame -> false, 64L);
        queue.pull(frame -> {}, frame -> false, 64L);

        assertEquals(0L, queue.getProductiveHandleCount());
        assertEquals(1L, queue.nonproductiveCount);
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
        assertEquals(1L, queue.getProductiveHandleCount());
    }

    /// Verifies a failed acquisition supplies no new productivity evidence.
    @Test
    void shouldPreserveNonproductiveStateAfterFailedAcquisition() {
        TestUpstreamHandle upstream = addHandle();
        upstream.pullResult = 0L;
        queue.pull(frame -> {}, frame -> false, 64L);
        upstream.available = false;

        queue.pull(frame -> {}, frame -> false, 64L);

        assertEquals(0L, queue.getProductiveHandleCount());
        assertEquals(1L, queue.nonproductiveCount);
        assertEquals(1L, handles.sizeLong());
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
    void shouldRemoveProductiveHandleContributionExactlyOnce() {
        TestUpstreamHandle productive = addHandle();
        TestUpstreamHandle nonproductive = addHandle();
        nonproductive.pullResult = 0L;
        queue.pull(frame -> {}, frame -> false, 4_096L);
        assertEquals(1L, queue.getProductiveHandleCount());

        productive.complete(count);
        productive.complete(count);

        assertEquals(0L, queue.getProductiveHandleCount());
        assertEquals(0L, queue.getProductiveHandleCount());
        assertEquals(1L, queue.getTrueUpstreamCount());
    }

    @Test
    void shouldRemoveNonproductiveHandleWithoutDecrementingProductiveCount() {
        TestUpstreamHandle productive = addHandle();
        TestUpstreamHandle nonproductive = addHandle();
        nonproductive.pullResult = 0L;
        queue.pull(frame -> {}, frame -> false, 4_096L);
        assertEquals(1L, queue.getProductiveHandleCount());

        nonproductive.complete(count);
        nonproductive.complete(count);

        assertEquals(1L, queue.getProductiveHandleCount());
        assertEquals(1L, queue.getProductiveHandleCount());
        assertEquals(1L, queue.getTrueUpstreamCount());
        assertTrue(productive.isProductive());
    }

    @Test
    void shouldKeepProductiveCountWithinLiveBoundsAcrossZeroAndReplacement() {
        TestUpstreamHandle first = addHandle();
        first.pullResult = 0L;
        queue.pull(frame -> {}, frame -> false, 64L);
        first.complete(count);

        assertEquals(0L, queue.getProductiveHandleCount());

        addHandle();

        assertEquals(1L, queue.getProductiveHandleCount());
        assertEquals(1L, queue.getTrueUpstreamCount());
        assertEquals(0L, queue.nonproductiveCount);
    }

    /// Verifies trial-style count refreshes retain the worker's last source observation.
    @Test
    void shouldRetainObservedProductivityAcrossCountRefreshes() {
        TestUpstreamHandle upstream = addHandle();
        upstream.pullResult = 0L;
        queue.pull(frame -> {}, frame -> false, 64L);

        queue.getTrueUpstreamCount();
        queue.getCachedUpCount();
        queue.getTrueUpstreamCount();

        assertEquals(0L, queue.getProductiveHandleCount());
    }

    @Test
    void shouldNotTreatRequestOnlyServiceAsEmptyPullEvidence() {
        TestUpstreamHandle upstream = addHandle();

        queue.request(64L);
        assertEquals(1L, queue.getProductiveHandleCount());

        upstream.pullResult = 0L;
        queue.pull(frame -> {}, frame -> false, 64L);
        queue.request(64L);

        assertEquals(0L, queue.getProductiveHandleCount());
    }

    @Test
    void shouldObserveRealEmptyLiveQueueWithoutProducerPublication() {
        QueueFixture fixture = queueFixture();

        assertEquals(1L, fixture.queue.getProductiveHandleCount());
        assertEquals(0L, fixture.queue.pull(frame -> {}, LatticeVertex.NO_STOP, 64L));
        assertEquals(1L, fixture.queue.getTrueUpstreamCount());
        assertEquals(0L, fixture.queue.getProductiveHandleCount());
        assertEquals(0L, fixture.sink.size());
        assertEquals(0L, fixture.sink.getDemand());
        assertFalse(fixture.sink.isComplete());

        fixture.sink.offer(new TestFrame("offered"));

        assertEquals(0L, fixture.queue.getProductiveHandleCount());
        assertEquals(1L, fixture.queue.pull(frame -> {}, LatticeVertex.NO_STOP, 64L));
        assertEquals(0L, fixture.sink.size());
        assertEquals(1L, fixture.queue.getProductiveHandleCount());
        assertEquals(0L, fixture.queue.pull(frame -> {}, LatticeVertex.NO_STOP, 64L));
        assertEquals(0L, fixture.queue.getProductiveHandleCount());
    }

    @Test
    void shouldNotTreatRealQueueRequestAsEmptyPullEvidence() {
        QueueFixture fixture = queueFixture();

        fixture.queue.request(64L);

        assertTrue(fixture.sink.getDemand() > 0L);
        assertEquals(1L, fixture.queue.getTrueUpstreamCount());
        assertEquals(1L, fixture.queue.getProductiveHandleCount());

        fixture.queue.pull(frame -> {}, LatticeVertex.NO_STOP, 64L);
        fixture.queue.request(64L);

        assertEquals(0L, fixture.queue.getProductiveHandleCount());
        assertFalse(fixture.sink.isComplete());

        fixture.sink.offer(new TestFrame("requested"));
        assertEquals(0L, fixture.queue.getProductiveHandleCount());
        fixture.queue.request(64L);

        assertEquals(0L, fixture.sink.size());
        assertEquals(1L, fixture.queue.getProductiveHandleCount());
    }

    @Test
    void shouldPreserveProductivityWhenStopConditionPreventsObservation() {
        QueueFixture fixture = queueFixture();
        fixture.sink.offer(new TestFrame("ordered"));

        assertEquals(0L, fixture.queue.pull(frame -> {}, frame -> true, 64L));

        assertEquals(1L, fixture.sink.size());
        assertEquals(1L, fixture.queue.getProductiveHandleCount());
    }

    @Test
    void shouldReportPhaseEightRealQueueSignal() {
        QueueFixture productive = queueFixture();
        QueueFixture empty = queueFixture();
        productive.sink.offer(new TestFrame("work"));
        MpscQueue<UpstreamHandle> combinedHandles = new MpscQueue<>(64);
        PaddedAtomicLong combinedCount = new PaddedAtomicLong(2L);
        UpstreamQueue combined = new UpstreamQueue(0, combinedHandles, combinedCount);
        combinedHandles.offer(empty.handle);
        combinedHandles.offer(productive.handle);

        assertEquals(1L, combined.pull(frame -> {}, LatticeVertex.NO_STOP, 4_096L));

        assertEquals(2L, combined.getTrueUpstreamCount());
        assertEquals(1L, combined.getProductiveHandleCount());
        assertFalse(empty.sink.isComplete());
        assertEquals(0L, empty.sink.size());
    }

    @Test
    void shouldReportTwoRealProductiveHandlesAsTwoOpportunities() {
        QueueFixture first = queueFixture();
        QueueFixture second = queueFixture();
        first.sink.offer(new TestFrame("first"));
        second.sink.offer(new TestFrame("second"));
        MpscQueue<UpstreamHandle> combinedHandles = new MpscQueue<>(64);
        PaddedAtomicLong combinedCount = new PaddedAtomicLong(2L);
        UpstreamQueue combined = new UpstreamQueue(0, combinedHandles, combinedCount);
        combinedHandles.offer(first.handle);
        combinedHandles.offer(second.handle);

        assertEquals(2L, combined.pull(frame -> {}, LatticeVertex.NO_STOP, 4_096L));

        assertEquals(2L, combined.getTrueUpstreamCount());
        assertEquals(2L, combined.getProductiveHandleCount());
    }

    @Test
    void shouldPermitWorkerLocalDisagreementAndConvergeOnlyByOwnObservation() throws Exception {
        QueueIngestSink sink = new QueueIngestSink();
        LatticeVertex vertex = new LatticeVertex("worker-local-productivity", 1);
        LatticeVertex.UpstreamInterceptor handle = vertex.new UpstreamInterceptor();
        handle.upstream = sink.getDelegate();
        sink.getDelegate().addDownstream(handle);
        PaddedAtomicLong sharedCount = new PaddedAtomicLong(1L);
        UpstreamQueue first = queueWith(handle, sharedCount);
        UpstreamQueue second = queueWith(handle, sharedCount);
        CountDownLatch firstObservedEmpty = new CountDownLatch(1);
        CountDownLatch bothObservedEmpty = new CountDownLatch(1);
        CountDownLatch workOffered = new CountDownLatch(1);
        CountDownLatch firstObservedWork = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long[] firstValues = new long[3];
        long[] secondValues = new long[4];

        Thread firstWorker = new Thread(() -> {
            try {
                first.pull(frame -> {}, LatticeVertex.NO_STOP, 64L);
                firstValues[0] = first.getProductiveHandleCount();
                firstObservedEmpty.countDown();
                assertTrue(workOffered.await(5L, TimeUnit.SECONDS));
                firstValues[1] = first.getProductiveHandleCount();
                first.pull(frame -> {}, LatticeVertex.NO_STOP, 64L);
                firstValues[2] = first.getProductiveHandleCount();
                firstObservedWork.countDown();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        Thread secondWorker = new Thread(() -> {
            try {
                assertTrue(firstObservedEmpty.await(5L, TimeUnit.SECONDS));
                secondValues[0] = second.getProductiveHandleCount();
                second.pull(frame -> {}, LatticeVertex.NO_STOP, 64L);
                secondValues[1] = second.getProductiveHandleCount();
                bothObservedEmpty.countDown();
                assertTrue(workOffered.await(5L, TimeUnit.SECONDS));
                secondValues[2] = second.getProductiveHandleCount();
                assertTrue(firstObservedWork.await(5L, TimeUnit.SECONDS));
                secondValues[3] = second.getProductiveHandleCount();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });

        firstWorker.start();
        secondWorker.start();
        assertTrue(bothObservedEmpty.await(5L, TimeUnit.SECONDS));
        sink.offer(new TestFrame("worker-one-work"));
        workOffered.countDown();
        firstWorker.join(5_000L);
        secondWorker.join(5_000L);

        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(0L, firstValues[0]);
        assertEquals(1L, secondValues[0]);
        assertEquals(0L, secondValues[1]);
        assertEquals(0L, firstValues[1]);
        assertEquals(0L, secondValues[2]);
        assertEquals(1L, firstValues[2]);
        assertEquals(0L, secondValues[3]);
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

    private TestUpstreamHandle addHandle() {
        TestUpstreamHandle handle = new TestUpstreamHandle();
        handles.offer(handle);
        count.incrementAndGet();
        return handle;
    }

    private static UpstreamQueue queueWith(UpstreamHandle handle, PaddedAtomicLong count) {
        MpscQueue<UpstreamHandle> handles = new MpscQueue<>(64);
        handles.offer(handle);
        return new UpstreamQueue(0, handles, count);
    }

    private static QueueFixture queueFixture() {
        QueueIngestSink sink = new QueueIngestSink();
        LatticeVertex vertex = new LatticeVertex("productive-queue-test", 1);
        vertex.downstreams[0] = new LatticeEdge(new AtomicBoolean());
        LatticeVertex.UpstreamInterceptor handle = vertex.new UpstreamInterceptor();
        handle.upstream = sink.getDelegate();
        sink.getDelegate().addDownstream(handle);
        PaddedAtomicLong count = new PaddedAtomicLong(1L);
        return new QueueFixture(sink, handle, queueWith(handle, count));
    }

    private record QueueFixture(QueueIngestSink sink, LatticeVertex.UpstreamInterceptor handle, UpstreamQueue queue) {}

    static class TestUpstreamHandle extends UpstreamQueue.UpstreamHandle {

        @Getter
        long id = 0;

        long requested;
        long pulled;
        long acquisitionAttempts;
        boolean complete;
        boolean available = true;
        boolean productive = true;
        long pullResult = -1L;

        /// Returns the configured availability while retaining the number of bounded attempts.
        @Override
        public boolean acquireLock() {
            this.acquisitionAttempts++;
            if (this.available) {
                this.productive = false;
                return true;
            }
            return false;
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            this.pulled += demand;
            long result = this.pullResult < 0L ? demand : Math.min(this.pullResult, demand);
            if (result > 0L) {
                this.productive = true;
            }
            return result;
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
        public boolean isProductive() {
            return this.productive;
        }

        @Override
        public void restoreProductivity(boolean productive) {
            this.productive = productive;
        }

        void complete(PaddedAtomicLong count) {
            if (!this.complete) {
                this.complete = true;
                count.decrementAndGet();
            }
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
